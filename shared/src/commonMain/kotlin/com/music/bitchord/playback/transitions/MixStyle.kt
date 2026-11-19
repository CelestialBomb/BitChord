package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.FILTER_SWEEP
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_ECHO_SECONDS
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.echoTailSeconds
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The listener's Automix style preset (design doc section 2). It only chooses
 * among gestures the renderer already has; it never makes a pair more
 * ambitious than the planner's tier allowed.
 */
enum class MixStyle(val key: String) {
    /** The planner's own adaptive choice. The default, and a no-op. */
    SMOOTH("smooth"),

    /** Equal-power fade only: no filters, no bass swap, no echo. Beat timing is kept. */
    FADE("fade"),

    /** Filter sweep: the outgoing track is filtered away as the incoming one opens. */
    RISE("rise"),

    /** Short and punchy: at most eight bars, bass swap, echo-out when the beats are matched. */
    QUICK("quick"),
    ;

    companion object {
        fun fromKey(key: String?): MixStyle = entries.firstOrNull { it.key == key } ?: SMOOTH
    }
}

/** How long Automix blends run, relative to what the planner chose. */
enum class BlendLength(val key: String, val factor: Double, val skipSeconds: Double) {
    SHORT("short", 0.5, 2.0),

    /** The default, and a no-op for track-end blends. */
    MEDIUM("medium", 1.0, 3.0),
    LONG("long", 1.6, 4.0),
    ;

    companion object {
        fun fromKey(key: String?): BlendLength = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** Shortest blend a length change may produce. */
const val MIN_STYLED_BLEND_SECONDS = 4.0

/** Longest: the Beat-aware mix window, also used as a cap with Beat-aware off. */
const val MAX_STYLED_BLEND_SECONDS = 30.0

/** Quick never runs longer than this many bars. */
const val QUICK_MAX_BARS = 8

/**
 * Applies [style] and [length] to a plan the planner already made.
 *
 * Only a mix the planner chose ([TransitionStyle.DJ_BLEND] or
 * [TransitionStyle.DJ_FILTER]) is restyled. Blocked plans, gapless album
 * handoffs and plain crossfades (the planner's fallbacks for missing analysis,
 * weak grids and unmixable pairs, rules R0-R7) are returned unchanged, so a
 * preset can never override a safety rule. [MixStyle.SMOOTH] with
 * [BlendLength.MEDIUM] returns [plan] itself.
 *
 * The blend keeps its end and moves its start. With a trusted grid the new
 * length is whole bars (multiples of four once it is four bars or more, so a
 * phrase-aligned start stays on a four-bar line). The result stays within
 * [MIN_STYLED_BLEND_SECONDS]..[MAX_STYLED_BLEND_SECONDS], after the start of
 * the outgoing track's last [MAX_STYLED_BLEND_SECONDS], and inside the first
 * [MAX_STYLED_BLEND_SECONDS] of the incoming track.
 *
 * @param durationSeconds the outgoing track's length.
 * @param currentTime where the outgoing track is now, for [TransitionPlan.shouldStart].
 */
fun applyMixStyle(
    plan: TransitionPlan,
    style: MixStyle,
    length: BlendLength,
    durationSeconds: Double,
    currentTime: Double,
): TransitionPlan {
    if (style == MixStyle.SMOOTH && length == BlendLength.MEDIUM) return plan
    if (plan.blocked || plan.fadeSeconds <= 0.0) return plan
    if (plan.transitionStyle != TransitionStyle.DJ_BLEND && plan.transitionStyle != TransitionStyle.DJ_FILTER) return plan

    val fade = styledLength(plan, style, length, durationSeconds)
    val start = plan.transitionEnd - fade
    val scale = fade / plan.fadeSeconds
    var result = plan.copy(
        fadeSeconds = fade,
        transitionStart = start,
        shouldStart = currentTime >= start,
        transitionBeats = if (plan.beatSeconds > 0) (fade / plan.beatSeconds).roundToInt() else plan.transitionBeats,
        incomingHandoffTime = plan.incomingCueTime + (plan.incomingHandoffTime - plan.incomingCueTime) * scale,
        handoffStartSeconds = plan.handoffStartSeconds * scale,
        handoffDuration = plan.handoffDuration * scale,
        pickupSeconds = plan.pickupSeconds * scale,
        policyReasons = plan.policyReasons + listOfNotNull(
            "style-${style.key}".takeIf { style != MixStyle.SMOOTH },
            "length-${length.key}".takeIf { length != BlendLength.MEDIUM },
        ),
    )

    result = when (style) {
        MixStyle.SMOOTH -> result
        MixStyle.FADE -> result.copy(
            transitionStyle = TransitionStyle.EQUAL_POWER,
            bassSwap = false,
            filterSweep = 0.0,
            filterVariant = FilterTransitionVariant.SWEEP,
            echoSeconds = 0.0,
        )
        MixStyle.RISE -> result.copy(
            transitionStyle = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.SWEEP,
            filterSweep = FILTER_SWEEP,
            bassSwap = false,
            echoSeconds = 0.0,
        )
        MixStyle.QUICK -> {
            val echo = quickEcho(result, durationSeconds)
            result.copy(
                bassSwap = result.transitionStyle == TransitionStyle.DJ_BLEND || result.bassSwap,
                echoSeconds = echo,
                filterVariant = if (echo > 0 && result.transitionStyle == TransitionStyle.DJ_FILTER) {
                    FilterTransitionVariant.ECHO_RIDE
                } else {
                    result.filterVariant
                },
            )
        }
    }
    return result
}

/** The styled overlap in seconds; see [applyMixStyle] for the rules. */
private fun styledLength(plan: TransitionPlan, style: MixStyle, length: BlendLength, durationSeconds: Double): Double {
    val bar = if (plan.beatSeconds > 0) plan.beatSeconds * 4 else 0.0
    // Medium is the planner's own length, to the millisecond: an adaptive
    // blend need not be whole bars, and rounding it would move it for nothing.
    // Only Quick's eight-bar cap can shorten it.
    val quickCaps = style == MixStyle.QUICK && bar > 0 && plan.fadeSeconds > QUICK_MAX_BARS * bar + 1e-6
    if (length == BlendLength.MEDIUM && !quickCaps) return plan.fadeSeconds
    var target = plan.fadeSeconds * length.factor
    if (style == MixStyle.QUICK && bar > 0) {
        target = min(target, QUICK_MAX_BARS * bar)
    }
    // Room on each side: the outgoing track's last 30 s, the incoming one's first 30 s.
    val outRoom = plan.transitionEnd - max(0.0, durationSeconds - MAX_STYLED_BLEND_SECONDS)
    val inRate = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
    val inRoom = (MAX_STYLED_BLEND_SECONDS - plan.incomingCueTime) / inRate.coerceAtLeast(0.01)
    val ceiling = minOf(MAX_STYLED_BLEND_SECONDS, outRoom, inRoom, plan.transitionEnd)
    // Never longer than the room allows, even when that is under the floor:
    // a plan the planner squeezed into a short tail stays inside it.
    val floor = min(MIN_STYLED_BLEND_SECONDS, ceiling)
    if (bar <= 0) return target.coerceIn(floor, max(floor, ceiling))

    var bars = target / bar
    bars = if (bars >= 4) (bars / 4).roundToInt() * 4.0 else max(1.0, bars.roundToInt().toDouble())
    // Whole bars under the ceiling: four at a time while that still leaves
    // Long at least as long as Medium, then one at a time; then up to the floor.
    val wanted = if (length.factor > 1) min(plan.fadeSeconds, ceiling) else floor
    while (bars > 1 && bars * bar > ceiling + 1e-6) {
        bars -= if (bars > 4 && (bars - 4) * bar >= wanted - 1e-6) 4 else 1
    }
    while (bars * bar < floor - 1e-6 && (bars + 1) * bar <= ceiling + 1e-6) bars += 1
    var result = min(bars * bar, max(floor, ceiling))
    // Long is never shorter than Medium, Short never longer.
    if (length.factor > 1) result = max(result, min(plan.fadeSeconds, ceiling))
    if (length.factor < 1) result = min(result, plan.fadeSeconds)
    return result
}

/**
 * Echo-out for [MixStyle.QUICK]: only on a beat-matched blend (both grids
 * trusted, tempo matched), where the repeats land on the incoming beat too —
 * the planner dropped echo for unmatched pairs because there they flam — and
 * only when the outgoing file has its repeats' worth of audio after the blend.
 */
private fun quickEcho(plan: TransitionPlan, durationSeconds: Double): Double {
    if (plan.transitionStyle != TransitionStyle.DJ_BLEND || !plan.phaseLock || plan.beatSeconds <= 0) return 0.0
    val echo = min(plan.beatSeconds, MAX_ECHO_SECONDS)
    return if (durationSeconds - plan.transitionEnd >= echoTailSeconds(echo)) echo else 0.0
}
