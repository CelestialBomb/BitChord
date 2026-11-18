package com.music.bitchord.playback.transitions

import com.music.bitchord.data.settings.TrackAnalysisState

/** How the seek bar shows the next transition's window. */
enum class MarkerState { SHOWN, PROVISIONAL, HIDDEN }

/** The seek-bar marker decision and why, for the `marker` log line. */
data class MixMarker(val state: MarkerState, val why: String) {
    val line: String get() = if (state == MarkerState.SHOWN) "shown" else "${state.name.lowercase()}: $why"
}

/**
 * Whether the planned window goes on the seek bar.
 *
 * Shown once both songs are measured enough that it will not slide: the
 * outgoing one fully analysed, the incoming one measured from at least its
 * opening, and its audio ready. With Beat-aware on, a plan that is only
 * waiting for those is shown as well, provisional (drawn dimmed), so a song
 * added with Play next has a window at once instead of after its analysis,
 * about 28 s for a song never played before. Beat-aware off: hidden, as in
 * v1.9.1.
 *
 * [nextStreamReady] is asked last, because it is the only check that reads the cache.
 */
fun mixMarker(
    blocked: Boolean,
    hasMarker: Boolean,
    reason: String,
    durationKnown: Boolean,
    current: TrackAnalysisState,
    nextMeasured: Boolean,
    next: TrackAnalysisState,
    beatAware: Boolean,
    nextStreamReady: () -> Boolean,
): MixMarker {
    if (blocked) return MixMarker(MarkerState.HIDDEN, "plan blocked ($reason)")
    if (!hasMarker) return MixMarker(MarkerState.HIDDEN, "plan has no marker ($reason)")
    if (!durationKnown) return MixMarker(MarkerState.HIDDEN, "no duration")
    val why = when {
        current != TrackAnalysisState.ANALYSED -> "current is $current"
        !nextMeasured -> "next is $next"
        !nextStreamReady() -> "next stream not ready"
        else -> return MixMarker(MarkerState.SHOWN, "")
    }
    return MixMarker(if (beatAware) MarkerState.PROVISIONAL else MarkerState.HIDDEN, why)
}

/** What a provisional window is waiting for, for the Automix line under the stream stats. */
fun provisionalWaitingOn(current: TrackAnalysisState, nextMeasured: Boolean, next: TrackAnalysisState): String = when {
    current == TrackAnalysisState.FAILED -> "couldn't analyse this song"
    next == TrackAnalysisState.FAILED -> "couldn't analyse the next song"
    current != TrackAnalysisState.ANALYSED && !nextMeasured -> "analysing both songs"
    current != TrackAnalysisState.ANALYSED -> "analysing this song"
    !nextMeasured -> "analysing the next song"
    else -> "waiting for the next song's audio"
}
