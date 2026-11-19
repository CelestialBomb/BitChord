package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.transitions.TransitionFailureLog.Kind
import com.music.bitchord.playback.transitions.TransitionFailureLog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailureGateTest {

    private val gate = FailureGate()

    private fun start(kind: Kind = Kind.BLEND) =
        gate.begin("a", "b", kind, label = "test", atMs = 0, durationMs = 200_000)

    @Test
    fun aStopTheListenerAskedForIsNotAFailure() {
        start()
        gate.asListener("skip", phase = "ARMING") { gate.noteFailure("bail", phase = "ARMING") }
        assertEquals(Verdict.NORMAL, gate.verdict("a", "b"))
    }

    @Test
    fun whatFailedDecidesWhatThePairGetsNext() {
        start(Kind.LOOP_MIX)
        gate.noteFailure("a sink was flushed", phase = "LOOP_RUNNING")
        assertEquals(Verdict.NO_LOOP, gate.verdict("a", "b"))
        start(Kind.BLEND)
        gate.noteFailure("incoming went idle", phase = "FADING")
        assertEquals(Verdict.PLAIN_CROSSFADE, gate.verdict("a", "b"))
        start(Kind.PLAIN)
        gate.noteFailure("player error", phase = "FADING")
        assertEquals(Verdict.NONE, gate.verdict("a", "b"))
    }

    @Test
    fun oneTransitionCountsOnceHoweverManyStopsItHits() {
        start(Kind.LOOP_MIX)
        gate.noteFailure("loop mix: a sink was flushed", phase = "LOOP_RUNNING")
        gate.noteFailure("interrupted", phase = "LOOP_RUNNING")
        assertEquals(Verdict.NO_LOOP, gate.verdict("a", "b"))
    }

    @Test
    fun previousGivesThePairAFreshAttempt() {
        start(Kind.PLAIN)
        gate.noteFailure("player error", phase = "FADING")
        gate.forget("b")
        assertEquals(Verdict.NORMAL, gate.verdict("a", "b"))
    }

    @Test
    fun theSettleSongFollowsTheHandoffAndIsForgottenAtTheEnd() {
        start()
        assertEquals("a", gate.songOf(handedOff = false))
        assertEquals("b", gate.songOf(handedOff = true))
        gate.end()
        assertNull(gate.songOf(handedOff = false))
        gate.noteFailure("after the end", phase = "IDLE")
        assertEquals(Verdict.NORMAL, gate.verdict("a", "b"))
    }
}
