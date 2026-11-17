package com.music.bitchord.playback.transitions

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor's arithmetic (design doc 4.8). 120 BPM: a beat is 0.5 s, a bar 2 s. */
class CustomTransitionEditingTest {
    private val duration = 200.0
    private val beat = 0.5

    /** Beats every half second from 0.5 s, like the planner's synthetic grid. */
    private val beats = generateSequence(0.5) { it + 0.5 }.takeWhile { it < 200.0 }.toList()

    private fun draft(bars: Int = 8, before: Double = 20.0) = CustomTransition(
        bars = bars,
        startBeforeEndSeconds = before,
        outDurationMs = 200_000,
        inDurationMs = 180_000,
    )

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    @Test
    fun lengthIsBarsOnAGridAndSecondsWithout() {
        assertClose(16.0, blendLengthSeconds(draft(bars = 8), beat), "8 bars at 120 BPM")
        assertClose(8.0, blendLengthSeconds(draft().copy(seconds = 8.0), 0.0), "no grid")
        assertClose(12.0, blendLengthSeconds(draft().copy(seconds = 12.0), 0.0), "no grid, 12 s")
    }

    @Test
    fun onlyBarLengthsThatFitTheWindowAreOffered() {
        assertEquals(listOf(4, 8, 16), availableBars(0.5), "16 bars at 120 BPM is 32 s")
        assertEquals(listOf(4, 8), availableBars(1.0), "16 bars at 60 BPM is 64 s, over the window")
        assertEquals(listOf(4), availableBars(2.0), "a very slow song only has 4 bars (32 s)")
        assertEquals(emptyList(), availableBars(0.0), "no grid: no bars")
    }

    @Test
    fun aDraggedStartSnapsToTheNearestBeat() {
        val moved = moveStart(draft(), 170.13, duration, beat, beats)
        assertClose(170.0, startSeconds(moved, duration), "snapped")
        val other = moveStart(draft(), 170.3, duration, beat, beats)
        assertClose(170.5, startSeconds(other, duration), "snapped up")
    }

    @Test
    fun theStartIsKeptInsideTheWindowAndShortOfTheEnd() {
        val early = moveStart(draft(bars = 8), 100.0, duration, beat, beats)
        assertClose(155.0, startSeconds(early, duration), "no earlier than 45 s before the end")
        val late = moveStart(draft(bars = 8), 199.0, duration, beat, beats)
        assertClose(184.0, startSeconds(late, duration), "an 8-bar (16 s) blend must end by the end")
        assertTrue(fitsWindow(early, duration, beat))
        assertTrue(fitsWindow(late, duration, beat))
    }

    @Test
    fun snappingNeverPushesTheBlendOutOfTheWindow() {
        // Beats only at 160.2 and 190.0: the nearest to 189 is 190.0, but an 8-bar blend from there runs past the end.
        val sparse = listOf(160.2, 190.0)
        val moved = moveStart(draft(bars = 8), 189.0, duration, beat, sparse)
        assertTrue(fitsWindow(moved, duration, beat), "start ${startSeconds(moved, duration)}")
        assertClose(160.2, startSeconds(moved, duration), "the only beat that fits")
    }

    @Test
    fun withoutAGridTheStartMovesFreelyToATenth() {
        val moved = moveStart(draft().copy(seconds = 8.0), 171.26, duration, 0.0, emptyList())
        assertClose(171.3, startSeconds(moved, duration), "to a tenth of a second")
        assertTrue(fitsWindow(moved, duration, 0.0))
    }

    @Test
    fun changingTheLengthKeepsTheEndWhereItWas() {
        val eight = draft(bars = 8, before = 20.0) // 180..196
        val four = withBars(eight, 4, duration, beat, beats)
        assertClose(196.0, endSeconds(four, duration, beat), "end kept")
        assertClose(188.0, startSeconds(four, duration), "start moved to 4 bars before it")
        val sixteen = withBars(eight, 16, duration, beat, beats)
        assertClose(196.0, endSeconds(sixteen, duration, beat), "end kept")
        assertTrue(fitsWindow(sixteen, duration, beat))
    }

    @Test
    fun aLengthThatWouldLeaveTheWindowPullsTheStartBack() {
        val late = draft(bars = 4, before = 8.0) // 192..200
        val sixteen = withBars(late, 16, duration, beat, beats)
        assertTrue(fitsWindow(sixteen, duration, beat), "start ${startSeconds(sixteen, duration)}")
        assertTrue(endSeconds(sixteen, duration, beat) <= duration + 1e-6)
    }

    @Test
    fun secondsChangeLikeBars() {
        val d = draft().copy(seconds = 8.0, startBeforeEndSeconds = 20.0) // 180..188
        val longer = withSeconds(d, 16.0, duration)
        assertClose(188.0, endSeconds(longer, duration, 0.0), "end kept")
        assertClose(172.0, startSeconds(longer, duration), "start earlier")
    }

    @Test
    fun theDefaultDraftEndsJustBeforeTheEndOnABeat() {
        val d = defaultTransition(200_000, 180_000, beat, beats, bassSwap = true)
        assertEquals(CustomTransition.MODE_MIX, d.mode)
        assertEquals(8, d.bars)
        assertEquals(true, d.bassSwap)
        assertTrue(fitsWindow(d, duration, beat))
        assertTrue(endSeconds(d, duration, beat) in (duration - 3.0)..duration, "ends ${endSeconds(d, duration, beat)}")
        assertTrue(beats.any { abs(it - startSeconds(d, duration)) < 1e-6 }, "starts on a beat")
        assertEquals(200_000, d.outDurationMs)
        assertEquals(180_000, d.inDurationMs)
    }

    @Test
    fun theDefaultDraftWithoutAGridIsEightSecondsAndHasNoBassSwap() {
        val d = defaultTransition(200_000, 180_000, 0.0, bassSwap = true)
        assertEquals(false, d.bassSwap, "a bass swap needs a grid to land on")
        assertClose(8.0, blendLengthSeconds(d, 0.0), "length")
        assertTrue(fitsWindow(d, duration, 0.0))
    }

    @Test
    fun aVeryShortSongStillGetsAWorkableDraft() {
        val d = defaultTransition(30_000, 180_000, beat, beats.filter { it < 30.0 })
        assertTrue(startSeconds(d, 30.0) >= 0.0, "start ${startSeconds(d, 30.0)}")
    }

    @Test
    fun theDraftIsStampedWithTheDurationsItWasMadeFor() {
        val stamped = draft().madeFor(201_000, 179_500)
        assertEquals(201_000, stamped.outDurationMs)
        assertEquals(179_500, stamped.inDurationMs)
    }

    @Test
    fun aDraftTheEditorAcceptsIsOneThePlannerApplies() {
        // The editor's and applyCustomTransition's window rules must agree.
        val d = moveStart(draft(bars = 8), 171.1, duration, beat, beats)
        assertTrue(fitsWindow(d, duration, beat))
        val planner = com.music.bitchord.playback.smart.TransitionPlan(fadeSeconds = 16.0, beatSeconds = beat)
        val applied = applyCustomTransition(planner, d, com.music.bitchord.playback.smart.TrackAnalysis(), duration, 180.0, 10.0)
        assertTrue("custom" in applied.policyReasons, applied.policyReasons.toString())
        assertTrue(applied.policyReasons.none { it.startsWith("custom-fallback") })
    }

    @Test
    fun previewStartsAfewSecondsBeforeTheBlendButNeverBeforeTheSong() {
        assertClose(172.0, previewStartSeconds(draft(before = 20.0), duration), "8 s ahead of 180")
        assertClose(0.0, previewStartSeconds(draft(before = 199.0), duration), "never negative")
    }
}
