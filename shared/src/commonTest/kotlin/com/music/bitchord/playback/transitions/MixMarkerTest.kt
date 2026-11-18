package com.music.bitchord.playback.transitions

import com.music.bitchord.data.settings.TrackAnalysisState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MixMarkerTest {

    private fun marker(
        current: TrackAnalysisState = TrackAnalysisState.ANALYSED,
        next: TrackAnalysisState = TrackAnalysisState.ANALYSED,
        beatAware: Boolean = false,
        blocked: Boolean = false,
        streamReady: Boolean = true,
    ) = mixMarker(
        blocked = blocked,
        hasMarker = true,
        reason = "smart-phrase-8-bar",
        durationKnown = true,
        current = current,
        nextMeasured = next == TrackAnalysisState.ANALYSED || next == TrackAnalysisState.REFINING,
        next = next,
        beatAware = beatAware,
        nextStreamReady = { streamReady },
    )

    @Test
    fun bothMeasuredIsShown() {
        assertEquals(MixMarker(MarkerState.SHOWN, ""), marker())
        assertEquals("shown", marker().line)
    }

    @Test
    fun beatAwareOffHidesAPairStillBeingMeasuredAsV191Did() {
        val m = marker(next = TrackAnalysisState.WAITING)
        assertEquals(MarkerState.HIDDEN, m.state)
        assertEquals("hidden: next is WAITING", m.line)
    }

    @Test
    fun beatAwareOnShowsItProvisional() {
        assertEquals("provisional: next is WAITING", marker(next = TrackAnalysisState.WAITING, beatAware = true).line)
        assertEquals("provisional: current is ANALYSING", marker(current = TrackAnalysisState.ANALYSING, beatAware = true).line)
        assertEquals("provisional: next stream not ready", marker(beatAware = true, streamReady = false).line)
    }

    @Test
    fun aBlockedPlanIsNeverShownEvenProvisionally() {
        assertEquals("hidden: plan blocked (smart-phrase-8-bar)", marker(blocked = true, beatAware = true).line)
    }

    @Test
    fun theCacheIsOnlyReadOnceBothSongsAreMeasured() {
        var asked = false
        mixMarker(
            blocked = false,
            hasMarker = true,
            reason = "r",
            durationKnown = true,
            current = TrackAnalysisState.ANALYSING,
            nextMeasured = true,
            next = TrackAnalysisState.ANALYSED,
            beatAware = true,
            nextStreamReady = { asked = true; true },
        )
        assertFalse(asked)
    }

    @Test
    fun theStatusLineSaysWhatItIsWaitingFor() {
        val analysed = TrackAnalysisState.ANALYSED
        assertEquals("analysing the next song", provisionalWaitingOn(analysed, false, TrackAnalysisState.ANALYSING))
        assertEquals("analysing both songs", provisionalWaitingOn(TrackAnalysisState.ANALYSING, false, TrackAnalysisState.WAITING))
        assertEquals("couldn't analyse the next song", provisionalWaitingOn(analysed, false, TrackAnalysisState.FAILED))
        assertEquals("waiting for the next song's audio", provisionalWaitingOn(analysed, true, analysed))
    }
}
