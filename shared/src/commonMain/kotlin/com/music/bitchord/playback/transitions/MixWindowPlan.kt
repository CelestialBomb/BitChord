package com.music.bitchord.playback.transitions

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The editor's waveforms and beat marks come from two short windows of audio, not
 * from whole songs (design doc 4.8): the last [CUSTOM_WINDOW_SECONDS] of the
 * outgoing song and the first of the incoming one. This file is the pure part:
 * what a window is, which bytes it needs, where its data comes from, and how a
 * beat grid is extended and snapped to.
 */
enum class WindowSide(val key: String) {
    /** The outgoing song's last 45 s. */
    TAIL("tail"),

    /** The incoming song's first 45 s. */
    HEAD("head"),
}

/**
 * One decoded window: three-band peaks for the waveform and a beat grid, kept
 * with the song (not the playlist) so a song in two playlists is measured once.
 * Levels are 0..255, which is all a picture needs and keeps the file small.
 */
@Serializable
data class MixWindow(
    val trackId: String,
    val side: String,
    /** The song's length when this was made; a window for a different cut is not reused. */
    val durationMs: Long,
    /** The stretch of the song the levels cover, in seconds. */
    val fromSeconds: Double,
    val toSeconds: Double,
    val low: List<Int>,
    val mid: List<Int>,
    val high: List<Int>,
    /** Beat times in seconds on the song's own timeline. */
    val beats: List<Double> = emptyList(),
    val downbeats: List<Double> = emptyList(),
    val bpm: Double = 0.0,
    val beatConfidence: Double = 0.0,
    /** True when beats beyond what the tracker measured were filled in from the tempo. */
    val extrapolated: Boolean = false,
) {
    /** A grid good enough to snap to: the same bar the planner uses for a beat-timed blend. */
    val hasTrustedGrid: Boolean
        get() = beats.size >= 8 && beatConfidence >= SKIP_MIN_BEAT_CONFIDENCE && bpm in 40.0..220.0
}

/** Quantises a 0..1 peak envelope to the levels a [MixWindow] stores. */
fun FloatArray.toLevels(): List<Int> = map { (it.coerceIn(0f, 1f) * 255f + 0.5f).toInt() }

/** The inverse of [toLevels], for drawing. */
fun List<Int>.toPeaks(): FloatArray = FloatArray(size) { this[it] / 255f }

/** A span of the stream, in bytes. */
data class ByteRange(val position: Long, val length: Long) {
    val end: Long get() = position + length
}

/**
 * The bytes to pull into the playback cache for one window: the start of the
 * file (container header, track list and seek index, which the decoder needs to
 * find the window) and the window itself.
 */
data class WindowFetchPlan(val header: ByteRange, val window: ByteRange) {
    /** What is fetched, counting any overlap of the two once. */
    val totalBytes: Long
        get() = if (window.position <= header.end) max(header.end, window.end) else header.length + window.length
}

/** The header, track list and seek index of a WebM or M4A stream sit well inside this. */
const val CONTAINER_HEADER_BYTES = 256L * 1024

/** A window is fetched with a little extra on the far side of what is drawn, for the decoder's lead-in and a varying bitrate. */
const val WINDOW_MARGIN_SECONDS = 8.0

/** The most a window may cost. Going over means the stream is not what was assumed, and counts as a failure. */
const val WINDOW_BYTE_BUDGET = 3L * 1024 * 1024

/**
 * Which bytes of a [totalBytes]-long, [durationSeconds]-long stream cover
 * [side]'s window, or null when that can't be worked out (unknown size or
 * length) or would cost more than [WINDOW_BYTE_BUDGET].
 *
 * Bytes are placed in proportion to time. That is exact for constant-bitrate
 * audio and close for the variable-bitrate Opus and AAC streams in use, which
 * is what [WINDOW_MARGIN_SECONDS] absorbs; the decoder reports where it really
 * started, and a window that missed is treated as a failure, never as success.
 */
fun planWindowFetch(
    totalBytes: Long,
    durationSeconds: Double,
    side: WindowSide,
    windowSeconds: Double = CUSTOM_WINDOW_SECONDS,
    budgetBytes: Long = WINDOW_BYTE_BUDGET,
): WindowFetchPlan? {
    if (totalBytes <= 0 || durationSeconds <= 0 || !durationSeconds.isFinite()) return null
    val bytesPerSecond = totalBytes / durationSeconds
    val wanted = ceil((windowSeconds + WINDOW_MARGIN_SECONDS) * bytesPerSecond).toLong().coerceAtMost(totalBytes)
    val header = ByteRange(0, min(CONTAINER_HEADER_BYTES, totalBytes))
    val window = when (side) {
        WindowSide.TAIL -> ByteRange(totalBytes - wanted, wanted)
        WindowSide.HEAD -> ByteRange(0, wanted)
    }
    val plan = WindowFetchPlan(header, window)
    return plan.takeIf { it.totalBytes <= budgetBytes }
}

/** Where a window's data comes from, in the order they are tried. */
enum class WindowSource {
    /** Already decoded and saved with the song. */
    SAVED,

    /** The playback cache holds the bytes: decode them, no network. */
    CACHE,

    /** Fetch just the window's bytes. */
    FETCH,

    /** Would fetch, but Wi-Fi only is on and the connection is metered (or not known to be free). */
    WAIT_FOR_WIFI,
}

/**
 * The order of design doc 4.8: saved, then the playback cache, then a window
 * fetch, and the fetch only when the listener's Wi-Fi-only setting allows it.
 * A connection that is not known to be unmetered counts as metered.
 */
fun decideWindowSource(
    hasSaved: Boolean,
    cacheCovers: Boolean,
    wifiOnly: Boolean,
    metered: Boolean?,
): WindowSource = when {
    hasSaved -> WindowSource.SAVED
    cacheCovers -> WindowSource.CACHE
    wifiOnly && metered != false -> WindowSource.WAIT_FOR_WIFI
    else -> WindowSource.FETCH
}

/**
 * [beats] extended in both directions by whole [interval]s so they cover
 * [fromSeconds]..[toSeconds]. The tracker measures about 30 s at a time and the
 * editor shows 45, and a steady beat is the safe thing to carry on.
 * Unchanged when there is nothing to extend from.
 */
fun extendBeats(beats: List<Double>, interval: Double, fromSeconds: Double, toSeconds: Double): List<Double> {
    if (beats.isEmpty() || interval <= 0 || !interval.isFinite() || toSeconds <= fromSeconds) return beats
    val before = generateSequence(beats.first() - interval) { it - interval }
        .takeWhile { it >= fromSeconds - 1e-6 }.toList().reversed()
    val after = generateSequence(beats.last() + interval) { it + interval }
        .takeWhile { it <= toSeconds + 1e-6 }.toList()
    return before + beats + after
}

/**
 * The beat in [beats] nearest [seconds], or [seconds] itself when there is no
 * grid or the nearest beat is further than [maxDistance]: dragging the start
 * point snaps to beats where there are some and moves freely where there are not.
 */
fun snapToBeat(seconds: Double, beats: List<Double>, maxDistance: Double = Double.MAX_VALUE): Double {
    if (beats.isEmpty()) return seconds
    val nearest = beats.minBy { abs(it - seconds) }
    return if (abs(nearest - seconds) <= maxDistance) nearest else seconds
}
