package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.ExitFilter
import com.music.bitchord.playback.smart.FILTER_SWEEP
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_ECHO_SECONDS
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.echoTailSeconds
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom transitions (design doc section 4.8): what the listener saved for one
 * pair of songs in one playlist. Pure data and pure rules, so the app, the
 * backup and the tests share them.
 */
@Serializable
data class CustomTransition(
    /** [MODE_MIX], [MODE_GAPLESS] or [MODE_NO_MIX]. Strings, so the backup stays readable and tolerant. */
    val mode: String = MODE_MIX,
    /** A [MixStyle] key. */
    val style: String = MixStyle.SMOOTH.key,
    /** Length in bars when the outgoing track has a trusted grid. */
    val bars: Int = 8,
    /** Length in seconds when it has not. */
    val seconds: Double = 8.0,
    /**
     * One beat in seconds as the editor measured it when this was made, or 0 when
     * it had no trusted grid. Bars are counted in this beat, never in whatever
     * the analysis says at playback: the two can disagree (a song tracked at 192
     * in one place and 97 in another), and the blend must be the one that was drawn.
     */
    val beatSeconds: Double = 0.0,
    /**
     * Where the blend starts, measured back from the end of the outgoing track,
     * so a duration that differs by a second between versions does not move it.
     */
    val startBeforeEndSeconds: Double = 16.0,
    val bassSwap: Boolean = false,
    /** [FILTER_NONE], [FILTER_LOW_PASS] or [FILTER_HIGH_PASS], on the outgoing track's exit. */
    val filter: String = FILTER_NONE,
    /** 0..1: how far the filter closes. */
    val filterAmount: Double = 0.7,
    /** The two durations this was made against; a pair that no longer matches is not trusted. */
    val outDurationMs: Long = 0L,
    val inDurationMs: Long = 0L,
    /**
     * Use the loop mix (the DJ transition that loops an instrumental bar or four on each song): only for
     * [MODE_MIX]. Style, length, bass swap and filter then do not apply, the loop mix has its own. The
     * outgoing loop starts near [startBeforeEndSeconds]; the controller picks it, because choosing a loop
     * that is truly instrumental needs the vocal mask and the lyrics.
     */
    val loop: Boolean = false,
    /** 1, 2 or 4 bars: the loop's length on both songs. */
    val loopBars: Int = 4,
    /** Ramp the tempo from one song's to the other's (the automatic loop mix); off holds the outgoing tempo. */
    val loopRamp: Boolean = true,
) {
    val mixStyle: MixStyle get() = MixStyle.fromKey(style)

    /** A mix that is the loop mix. */
    val isLoopMix: Boolean get() = mode == MODE_MIX && loop

    /** Gapless and No mix: they never mix, so no failure of a blend can be held against them. */
    val isModeOnly: Boolean get() = mode == MODE_GAPLESS || mode == MODE_NO_MIX

    /** Whether the two songs are still the lengths this was made against (see [CUSTOM_DURATION_TOLERANCE_MS]). */
    fun matchesDurations(outDurationSeconds: Double, inDurationSeconds: Double): Boolean =
        abs(outDurationSeconds * 1000 - outDurationMs) <= CUSTOM_DURATION_TOLERANCE_MS &&
            abs(inDurationSeconds * 1000 - inDurationMs) <= CUSTOM_DURATION_TOLERANCE_MS

    val exitFilter: ExitFilter
        get() = when (filter) {
            FILTER_LOW_PASS -> ExitFilter.LOW_PASS
            FILTER_HIGH_PASS -> ExitFilter.HIGH_PASS
            else -> ExitFilter.NONE
        }

    companion object {
        const val MODE_MIX = "mix"
        const val MODE_GAPLESS = "gapless"
        const val MODE_NO_MIX = "no-mix"
        const val FILTER_NONE = "none"
        const val FILTER_LOW_PASS = "low-pass"
        const val FILTER_HIGH_PASS = "high-pass"

        /** The loop lengths the editor offers, in bars. */
        val LOOP_BARS_CHOICES = listOf(1, 2, 4)
    }
}

/**
 * Everything saved for custom transitions, as the backup carries it: the
 * playlists that have Mix on, and the saved pairs by [customTransitionKey].
 */
@Serializable
data class CustomTransitionSnapshot(
    val mixPlaylists: List<String> = emptyList(),
    val transitions: Map<String, CustomTransition> = emptyMap(),
)

/** `<playlist>|<outgoing>-><incoming>`; media IDs never contain `|`, and nothing parses it back. */
fun customTransitionKey(playlistId: String, outgoingId: String, incomingId: String) =
    "$playlistId|$outgoingId->$incomingId"

/**
 * The saved pairs of [playlistKey] whose two songs are both in [orderedIds] (the playlist as the
 * page lists it) but no longer follow one another in that order, so the page has no line for them
 * and nothing ever plays them. A pair with a song that is not in [orderedIds] is left alone: the
 * list may be only partly loaded, or the song may come back.
 */
fun orphanedPairKeys(snapshot: CustomTransitionSnapshot, playlistKey: String, orderedIds: List<String>): List<String> {
    val present = orderedIds.toSet()
    val adjacent = orderedIds.zipWithNext().toSet()
    val prefix = "$playlistKey|"
    return snapshot.transitions.keys.filter { key ->
        if (!key.startsWith(prefix)) return@filter false
        val ids = key.removePrefix(prefix).split("->")
        if (ids.size != 2) return@filter false
        val (out, into) = ids
        out in present && into in present && (out to into) !in adjacent
    }
}

/** A custom blend may use this much of each song: the last and the first 45 s. */
const val CUSTOM_WINDOW_SECONDS = 45.0

/** How far a song's length may differ from what the transition was made against. */
const val CUSTOM_DURATION_TOLERANCE_MS = 2_000L

/** The playhead may be this far past the start before the saved start no longer fits. */
const val CUSTOM_LATE_SLACK_SECONDS = 0.5

/**
 * Applies [custom] to [planner], the plan the automatic rules produced for the
 * same pair, or hands [planner] back with a `custom-fallback-*` code in its
 * policy reasons when the saved transition no longer fits:
 *
 *  - `custom-fallback-duration`: either song's length differs by more than 2 s
 *    from the one it was made against (a different version or cut);
 *  - `custom-fallback-window`: the blend would start before the last 45 s of
 *    the outgoing song, run past its end, or reach past the first 45 s of the
 *    incoming one;
 *  - `custom-fallback-late`: the playhead is already past the start;
 *  - `custom-fallback-loop`: a loop mix was asked for and could not be made
 *    (no instrumental loop near the chosen start, none that hands over inside
 *    the song, or no analysis). The controller adds this one, since only it
 *    can plan the loop.
 *
 * A loop mix (`custom-loop` in the policy reasons) comes back as the planner's
 * own plan: the loop itself is planned by the controller from the vocal mask
 * and lyrics, which this pure rule cannot see.
 *
 * The caller runs this after every guard (Automix off, short or missing
 * durations, video, party, bit-perfect, the failure record), so custom
 * settings win over the planner only for pairs that may be mixed at all.
 * Tempo handling and the incoming cue stay the planner's.
 *
 * @param outgoing the outgoing track's analysis, for a trusted beat; without one the length is in seconds.
 */
fun applyCustomTransition(
    planner: TransitionPlan,
    custom: CustomTransition,
    outgoing: TrackAnalysis,
    durationSeconds: Double,
    nextDurationSeconds: Double,
    currentTime: Double,
): TransitionPlan {
    fun fallback(code: String) = planner.copy(policyReasons = planner.policyReasons + code)

    if (!custom.matchesDurations(durationSeconds, nextDurationSeconds)) {
        return fallback("custom-fallback-duration")
    }

    if (custom.isLoopMix) return planner.copy(policyReasons = planner.policyReasons + "custom-loop")

    when (custom.mode) {
        CustomTransition.MODE_NO_MIX -> return TransitionPlan(
            blocked = true,
            reason = "custom-no-mix",
            transitionStart = durationSeconds,
            transitionEnd = durationSeconds,
        )
        CustomTransition.MODE_GAPLESS -> {
            val start = (durationSeconds - 0.45).coerceAtLeast(0.0)
            val started = currentTime >= start
            return TransitionPlan(
                shouldStart = started,
                markerVisible = true,
                transitionStart = start,
                transitionEnd = durationSeconds,
                fadeSeconds = 0.12,
                transitionStyle = TransitionStyle.GAPLESS,
                reason = if (started) "custom-gapless" else "before-custom-gapless",
            )
        }
    }

    // The renderer lands its moves on the grid the analysis has; the length is what was drawn.
    val beat = trustedBeatSeconds(outgoing).takeIf { it > 0 } ?: planner.beatSeconds
    val length = if (custom.beatSeconds > 0) custom.bars * 4 * custom.beatSeconds else custom.seconds
    val start = durationSeconds - custom.startBeforeEndSeconds
    val end = start + length
    val ratio = planner.incomingPlaybackRate / planner.outgoingPlaybackRate.coerceAtLeast(0.01)
    if (length <= 0 ||
        start < durationSeconds - CUSTOM_WINDOW_SECONDS - 1e-6 ||
        end > durationSeconds + 1e-6 ||
        planner.incomingCueTime + length * ratio > CUSTOM_WINDOW_SECONDS + 1e-6
    ) {
        return fallback("custom-fallback-window")
    }
    if (currentTime > start + CUSTOM_LATE_SLACK_SECONDS) return fallback("custom-fallback-late")

    val plannerMixes = planner.transitionStyle == TransitionStyle.DJ_BLEND ||
        planner.transitionStyle == TransitionStyle.DJ_FILTER
    val style = custom.mixStyle
    var result = planner.copy(
        shouldStart = currentTime >= start,
        markerVisible = true,
        blocked = false,
        reason = if (currentTime >= start) "custom" else "before-custom",
        transitionStart = start,
        transitionEnd = end,
        fadeSeconds = length,
        beatSeconds = beat,
        transitionBeats = if (beat > 0) (length / beat).roundToInt() else 0,
        exitFilter = custom.exitFilter,
        exitFilterAmount = if (custom.exitFilter == ExitFilter.NONE) 0.0 else custom.filterAmount.coerceIn(0.0, 1.0),
        policyReasons = planner.policyReasons + "custom",
    )
    if (planner.fadeSeconds > 0) {
        val scale = length / planner.fadeSeconds
        result = result.copy(
            incomingHandoffTime = planner.incomingCueTime + (planner.incomingHandoffTime - planner.incomingCueTime) * scale,
            handoffStartSeconds = planner.handoffStartSeconds * scale,
            handoffDuration = planner.handoffDuration * scale,
            pickupSeconds = planner.pickupSeconds * scale,
        )
    }

    result = when (style) {
        MixStyle.FADE -> result.copy(
            transitionStyle = TransitionStyle.EQUAL_POWER,
            bassSwap = false,
            filterSweep = 0.0,
            echoSeconds = 0.0,
        )
        MixStyle.RISE -> result.copy(
            transitionStyle = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.SWEEP,
            filterSweep = FILTER_SWEEP,
            bassSwap = false,
            echoSeconds = 0.0,
        )
        MixStyle.SMOOTH, MixStyle.QUICK -> {
            val base = if (plannerMixes) planner.transitionStyle else TransitionStyle.EQUAL_POWER
            // A bass swap needs a grid to land on; without one it is ignored.
            val swap = custom.bassSwap && beat > 0
            val asBlend = swap || (style == MixStyle.QUICK && beat > 0)
            // Echo only where the planner beat-matched the pair: on an unmatched
            // one the repeats flam against the other tempo (why the planner
            // dropped echo for those).
            val matched = planner.transitionStyle == TransitionStyle.DJ_BLEND
            val echo = if (style == MixStyle.QUICK && beat > 0 && matched) {
                min(beat, MAX_ECHO_SECONDS).takeIf { durationSeconds - end >= echoTailSeconds(it) } ?: 0.0
            } else {
                0.0
            }
            result.copy(
                transitionStyle = if (asBlend) TransitionStyle.DJ_BLEND else base,
                bassSwap = swap,
                echoSeconds = echo,
            )
        }
    }
    return result
}
