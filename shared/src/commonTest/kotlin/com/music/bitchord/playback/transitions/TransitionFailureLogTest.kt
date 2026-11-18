package com.music.bitchord.playback.transitions

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionFailureLogTest {
    private val log = TransitionFailureLog()

    @Test
    fun freshPairIsNormal() = assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("a", "b"))

    @Test
    fun firstFailureFallsBackToPlainCrossfade() {
        assertEquals(TransitionFailureLog.Verdict.PLAIN_CROSSFADE, log.recordFailure("a", "b"))
        assertEquals(TransitionFailureLog.Verdict.PLAIN_CROSSFADE, log.verdict("a", "b"))
    }

    @Test
    fun secondFailureStopsArmingAltogether() {
        log.recordFailure("a", "b")
        assertEquals(TransitionFailureLog.Verdict.NONE, log.recordFailure("a", "b"))
        assertEquals(TransitionFailureLog.Verdict.NONE, log.recordFailure("a", "b"))
        assertEquals(3, log.failureCount("a", "b"))
    }

    @Test
    fun pairsAreDirectional() {
        log.recordFailure("a", "b")
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("b", "a"))
    }

    @Test
    fun otherPairsAreUnaffected() {
        log.recordFailure("a", "b")
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("b", "c"))
    }

    @Test
    fun clearingASongForgetsBothSides() {
        log.recordFailure("a", "b")
        log.recordFailure("c", "a")
        log.recordFailure("x", "y")
        log.clearFor("a")
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("a", "b"))
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("c", "a"))
        assertEquals(TransitionFailureLog.Verdict.PLAIN_CROSSFADE, log.verdict("x", "y"))
    }

    @Test
    fun idsContainingTheSeparatorAreHandled() {
        val out = "https://host/a->b?x=1"
        log.recordFailure(out, "c")
        log.recordFailure("a", "b->c")
        log.clearFor(out)
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict(out, "c"))
        assertEquals(TransitionFailureLog.Verdict.PLAIN_CROSSFADE, log.verdict("a", "b->c"))
        log.clearFor("b->c")
        assertEquals(TransitionFailureLog.Verdict.NORMAL, log.verdict("a", "b->c"))
        assertEquals(0, log.failureCount("a", "b->c"))
    }
}
