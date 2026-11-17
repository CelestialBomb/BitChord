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
) {
    val mixStyle: MixStyle get() = MixStyle.fromKey(style)

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
 *  - `custom-fallback-late`: the playhead is already past the start.
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

    val beat = trustedBeatSeconds(outgoing).takeIf { it > 0 } ?: planner.beatSeconds
    val length = if (beat > 0) custom.bars * 4 * beat else custom.seconds
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
        transitionBeats = if (beat > 0) custom.bars * 4 else 0,
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
