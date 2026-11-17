package com.music.bitchord.playback.transitions

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editor's arithmetic (design doc 4.8), kept pure so dragging, snapping and
 * clamping are tested rather than eyeballed. Every function takes and returns a
 * [CustomTransition]; the editor holds one as its draft.
 *
 * A blend is always kept inside the last [CUSTOM_WINDOW_SECONDS] of the
 * outgoing song and short of its end, exactly the rules [applyCustomTransition]
 * enforces, so a draft the editor accepts is a draft that plays.
 */

/** Lengths offered in bars when the outgoing song has a trusted grid. */
val BAR_CHOICES = listOf(4, 8, 16)

/** Lengths offered in seconds when it has not. */
val SECOND_CHOICES = listOf(4.0, 8.0, 12.0, 16.0)

/** A bit before the end of a song, where the automatic planner would also land. */
private const val DEFAULT_TAIL_SECONDS = 2.0

/** The blend's length in seconds: bars on a grid, seconds without one. */
fun blendLengthSeconds(transition: CustomTransition, beatSeconds: Double): Double =
    if (beatSeconds > 0) transition.bars * 4 * beatSeconds else transition.seconds

/** Whether a length option fits inside the 45 s window at all. */
fun lengthFits(lengthSeconds: Double): Boolean = lengthSeconds > 0 && lengthSeconds <= CUSTOM_WINDOW_SECONDS

/** The bar choices that fit the window at this tempo (16 bars at 80 BPM is 48 s, which does not). */
fun availableBars(beatSeconds: Double): List<Int> =
    if (beatSeconds > 0) BAR_CHOICES.filter { lengthFits(it * 4 * beatSeconds) } else emptyList()

/** Where the blend starts on the outgoing song's own timeline. */
fun startSeconds(transition: CustomTransition, durationSeconds: Double): Double =
    durationSeconds - transition.startBeforeEndSeconds

/** Where it ends. */
fun endSeconds(transition: CustomTransition, durationSeconds: Double, beatSeconds: Double): Double =
    startSeconds(transition, durationSeconds) + blendLengthSeconds(transition, beatSeconds)

/** Whether the draft plays as drawn: inside the window and not past the end. */
fun fitsWindow(transition: CustomTransition, durationSeconds: Double, beatSeconds: Double): Boolean {
    val start = startSeconds(transition, durationSeconds)
    val end = start + blendLengthSeconds(transition, beatSeconds)
    return lengthFits(blendLengthSeconds(transition, beatSeconds)) &&
        start >= durationSeconds - CUSTOM_WINDOW_SECONDS - 1e-6 &&
        end <= durationSeconds + 1e-6
}

/**
 * A first draft for a pair that has nothing saved: a Smooth mix of 8 bars (or
 * 8 s without a grid) ending a couple of seconds before the end of the outgoing
 * song, on the beat when there is one. [bassSwap] seeds the switch (the editor
 * passes what the automatic plan would do).
 */
fun defaultTransition(
    outDurationMs: Long,
    inDurationMs: Long,
    beatSeconds: Double,
    beats: List<Double> = emptyList(),
    bassSwap: Boolean = false,
): CustomTransition {
    val duration = outDurationMs / 1000.0
    val bars = availableBars(beatSeconds).let { if (8 in it) 8 else it.lastOrNull() ?: 8 }
    val base = CustomTransition(
        mode = CustomTransition.MODE_MIX,
        bars = bars,
        seconds = 8.0,
        bassSwap = bassSwap && beatSeconds > 0,
        outDurationMs = outDurationMs,
        inDurationMs = inDurationMs,
    )
    val length = blendLengthSeconds(base, beatSeconds)
    return moveStart(base, duration - DEFAULT_TAIL_SECONDS - length, duration, beatSeconds, beats)
}

/**
 * Moves the start to [requestedStartSeconds]: clamped so the blend stays inside
 * the window and short of the end, and snapped to the nearest beat when the
 * outgoing song has [beats] (to a tenth of a second when it has none).
 */
fun moveStart(
    transition: CustomTransition,
    requestedStartSeconds: Double,
    durationSeconds: Double,
    beatSeconds: Double,
    beats: List<Double>,
): CustomTransition {
    val length = blendLengthSeconds(transition, beatSeconds)
    val lowest = max(0.0, durationSeconds - CUSTOM_WINDOW_SECONDS)
    val highest = max(lowest, durationSeconds - length)
    var start = requestedStartSeconds.coerceIn(lowest, highest)
    if (beatSeconds > 0 && beats.isNotEmpty()) {
        // The nearest beat that keeps the whole blend inside the window.
        val usable = beats.filter { it >= lowest - 1e-6 && it <= highest + 1e-6 }
        if (usable.isNotEmpty()) start = snapToBeat(start, usable)
    } else {
        start = (start * 10).roundToInt() / 10.0
        start = start.coerceIn(lowest, highest)
    }
    return transition.copy(startBeforeEndSeconds = durationSeconds - start)
}

/**
 * Changes the length in bars. The end of the blend stays where it was and the
 * start moves, as a length change does elsewhere; if that would leave the
 * window, the start is pulled back inside it.
 */
fun withBars(
    transition: CustomTransition,
    bars: Int,
    durationSeconds: Double,
    beatSeconds: Double,
    beats: List<Double>,
): CustomTransition {
    val end = endSeconds(transition, durationSeconds, beatSeconds)
    val changed = transition.copy(bars = bars)
    return moveStart(changed, end - blendLengthSeconds(changed, beatSeconds), durationSeconds, beatSeconds, beats)
}

/** As [withBars], for a song without a trusted grid. */
fun withSeconds(
    transition: CustomTransition,
    seconds: Double,
    durationSeconds: Double,
): CustomTransition {
    val end = endSeconds(transition, durationSeconds, beatSeconds = 0.0)
    val changed = transition.copy(seconds = seconds)
    return moveStart(changed, end - seconds, durationSeconds, 0.0, emptyList())
}

/** Same pair, different mode. Gapless and No mix keep the rest, so switching back restores the mix. */
fun withMode(transition: CustomTransition, mode: String): CustomTransition = transition.copy(mode = mode)

/**
 * What the editor writes to the store: the draft with the two durations it was
 * made against, so the pair stops applying if either song turns out to be
 * a different cut.
 */
fun CustomTransition.madeFor(outDurationMs: Long, inDurationMs: Long): CustomTransition =
    copy(outDurationMs = outDurationMs, inDurationMs = inDurationMs)

/** Where Preview starts playing: a few seconds before the blend, never before the song. */
fun previewStartSeconds(transition: CustomTransition, durationSeconds: Double, leadSeconds: Double = 8.0): Double =
    max(0.0, min(startSeconds(transition, durationSeconds) - leadSeconds, durationSeconds))
