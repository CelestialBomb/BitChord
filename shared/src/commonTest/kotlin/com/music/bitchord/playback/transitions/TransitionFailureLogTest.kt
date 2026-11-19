package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.transitions.TransitionFailureLog.Kind
import com.music.bitchord.playback.transitions.TransitionFailureLog.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionFailureLogTest {
    private val log = TransitionFailureLog()

    @Test
    fun freshPairIsNormal() = assertEquals(Verdict.NORMAL, log.verdict("a", "b"))

    @Test
    fun aFailedLoopMixDropsOnlyTheLoop() {
        assertEquals(Verdict.NO_LOOP, log.recordFailure("a", "b", Kind.LOOP_MIX))
        assertEquals(Verdict.NO_LOOP, log.verdict("a", "b"))
    }

    @Test
    fun aFailedBlendDropsToAPlainCrossfade() {
        assertEquals(Verdict.PLAIN_CROSSFADE, log.recordFailure("a", "b", Kind.BLEND))
    }

    @Test
    fun aFailedPlainCrossfadeArmsNothing() {
        assertEquals(Verdict.NONE, log.recordFailure("a", "b", Kind.PLAIN))
    }

    @Test
    fun eachFailureStepsDownFromWhatThePairWasLeftWith() {
        log.recordFailure("a", "b", Kind.LOOP_MIX)
        assertEquals(Verdict.PLAIN_CROSSFADE, log.recordFailure("a", "b", Kind.BLEND))
        assertEquals(Verdict.NONE, log.recordFailure("a", "b", Kind.PLAIN))
        assertEquals(3, log.failureCount("a", "b"))
    }

    @Test
    fun aVerdictNeverGetsLessStrict() {
        log.recordFailure("a", "b", Kind.PLAIN)
        assertEquals(Verdict.NONE, log.recordFailure("a", "b", Kind.LOOP_MIX))
        log.recordFailure("c", "d", Kind.BLEND)
        assertEquals(Verdict.PLAIN_CROSSFADE, log.recordFailure("c", "d", Kind.LOOP_MIX))
    }

    @Test
    fun pairsAreDirectional() {
        log.recordFailure("a", "b", Kind.BLEND)
        assertEquals(Verdict.NORMAL, log.verdict("b", "a"))
    }

    @Test
    fun otherPairsAreUnaffected() {
        log.recordFailure("a", "b", Kind.BLEND)
        assertEquals(Verdict.NORMAL, log.verdict("b", "c"))
    }

    @Test
    fun clearingASongForgetsBothSides() {
        log.recordFailure("a", "b", Kind.BLEND)
        log.recordFailure("c", "a", Kind.BLEND)
        log.recordFailure("x", "y", Kind.BLEND)
        log.clearFor("a")
        assertEquals(Verdict.NORMAL, log.verdict("a", "b"))
        assertEquals(Verdict.NORMAL, log.verdict("c", "a"))
        assertEquals(Verdict.PLAIN_CROSSFADE, log.verdict("x", "y"))
    }

    @Test
    fun idsContainingTheSeparatorAreHandled() {
        val out = "https://host/a->b?x=1"
        log.recordFailure(out, "c", Kind.BLEND)
        log.recordFailure("a", "b->c", Kind.BLEND)
        log.clearFor(out)
        assertEquals(Verdict.NORMAL, log.verdict(out, "c"))
        assertEquals(Verdict.PLAIN_CROSSFADE, log.verdict("a", "b->c"))
        log.clearFor("b->c")
        assertEquals(Verdict.NORMAL, log.verdict("a", "b->c"))
        assertEquals(0, log.failureCount("a", "b->c"))
    }
}
