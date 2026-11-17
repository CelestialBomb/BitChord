package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.ExitFilter
import com.music.bitchord.playback.smart.FILTER_SWEEP
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Custom transitions (design doc section 4.8), against real planner output. */
class CustomTransitionTest {
    private val duration = 200.0

    private fun track(id: String, bpm: Double, confidence: Double = 0.9): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = confidence,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats[4],
            energyCurve = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) },
        )
    }

    private val outgoing = track("a", 120.0)

    private fun planner(inConfidence: Double = 0.9) =
        with(BeatAwareConfig(enabled = true).optionsFor(duration)) {
            planTransition(
                analysis = outgoing,
                nextAnalysis = track("b", 120.0, inConfidence),
                currentTrack = TransitionTrackInfo("a", 200_000),
                nextTrack = TransitionTrackInfo("b", 200_000),
                currentTime = 10.0,
                duration = duration,
                fadeSeconds = 8.0,
                mode = CrossfadeMode.SMART,
                advanced = true,
                phraseBars = phraseBars,
                albumSequential = albumSequential,
                mixWindow = mixWindow,
                strictConfidence = strictConfidence,
                allowTempoStretch = allowTempoStretch,
            )
        }

    private fun custom(
        mode: String = CustomTransition.MODE_MIX,
        style: MixStyle = MixStyle.SMOOTH,
        bars: Int = 8,
        before: Double = 20.0,
        bassSwap: Boolean = false,
        filter: String = CustomTransition.FILTER_NONE,
        outMs: Long = 200_000,
        inMs: Long = 200_000,
    ) = CustomTransition(
        mode = mode,
        style = style.key,
        bars = bars,
        startBeforeEndSeconds = before,
        bassSwap = bassSwap,
        filter = filter,
        filterAmount = 0.6,
        outDurationMs = outMs,
        inDurationMs = inMs,
    )

    private fun apply(
        custom: CustomTransition,
        plan: TransitionPlan = planner(),
        now: Double = 10.0,
        out: Double = duration,
        next: Double = 200.0,
    ) = applyCustomTransition(plan, custom, outgoing, out, next, now)

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    @Test
    fun aSavedMixReplacesTheTimingAndKeepsTheGrid() {
        val result = apply(custom(bars = 8, before = 20.0))
        assertEquals("before-custom", result.reason)
        assertClose(180.0, result.transitionStart, "start")
        assertClose(196.0, result.transitionEnd, "end: 8 bars at 120 BPM is 16 s")
        assertClose(16.0, result.fadeSeconds, "length")
        assertEquals(32, result.transitionBeats)
        assertClose(0.5, result.beatSeconds, "beat")
        assertTrue("custom" in result.policyReasons)
        assertEquals(false, result.blocked)
    }

    @Test
    fun theReasonLosesItsBeforePrefixOnceItStarts() {
        assertEquals("custom", apply(custom(before = 20.0), now = 180.2).reason)
        assertTrue(apply(custom(before = 20.0), now = 180.2).shouldStart)
    }

    @Test
    fun startIsKeptRelativeToTheEnd() {
        val saved = custom(before = 20.0, outMs = 200_000)
        val longer = apply(saved, out = 200.9)
        assertClose(180.9, longer.transitionStart, "moves with the end")
        assertTrue("custom" in longer.policyReasons)
    }

    @Test
    fun aDifferentDurationFallsBackToTheAutomaticPlan() {
        val plan = planner()
        val result = apply(custom(), plan = plan, out = 203.5)
        assertEquals(plan.transitionStart, result.transitionStart)
        assertEquals(plan.reason, result.reason)
        assertTrue("custom-fallback-duration" in result.policyReasons)
        assertTrue("custom" !in result.policyReasons)
        assertTrue("custom-fallback-duration" in apply(custom(), next = 150.0).policyReasons)
    }

    @Test
    fun theWindowIs45SecondsOnBothSides() {
        assertTrue("custom-fallback-window" in apply(custom(before = 46.0)).policyReasons, "starts before the last 45 s")
        assertTrue("custom-fallback-window" in apply(custom(before = 10.0, bars = 8)).policyReasons, "runs past the end")
        assertTrue("custom" in apply(custom(before = 45.0, bars = 4)).policyReasons, "exactly at the edge fits")
        val deepCue = planner().copy(incomingCueTime = 40.0)
        assertTrue("custom-fallback-window" in apply(custom(), plan = deepCue).policyReasons, "reaches past the first 45 s")
    }

    @Test
    fun aPlayheadPastTheStartFallsBack() {
        assertTrue("custom-fallback-late" in apply(custom(before = 20.0), now = 181.0 + 1.0).policyReasons)
        assertTrue("custom" in apply(custom(before = 20.0), now = 180.4).policyReasons, "within the slack")
    }

    @Test
    fun withoutAGridTheLengthIsInSeconds() {
        val bare = TrackAnalysis()
        val plan = TransitionPlan(transitionStyle = TransitionStyle.EQUAL_POWER, fadeSeconds = 8.0)
        val saved = custom(before = 20.0).copy(seconds = 12.0)
        val result = applyCustomTransition(plan, saved, bare, duration, 200.0, 10.0)
        assertClose(12.0, result.fadeSeconds, "seconds")
        assertEquals(0, result.transitionBeats)
        assertClose(0.0, result.beatSeconds, "no beat")
    }

    @Test
    fun gaplessAndNoMixAreModes() {
        val gapless = apply(custom(mode = CustomTransition.MODE_GAPLESS))
        assertEquals(TransitionStyle.GAPLESS, gapless.transitionStyle)
        assertEquals("before-custom-gapless", gapless.reason)
        assertClose(199.55, gapless.transitionStart, "start")
        assertEquals("custom-gapless", apply(custom(mode = CustomTransition.MODE_GAPLESS), now = 199.8).reason)

        val noMix = apply(custom(mode = CustomTransition.MODE_NO_MIX))
        assertTrue(noMix.blocked)
        assertEquals("custom-no-mix", noMix.reason)
    }

    @Test
    fun aModeStillNeedsTheSameDurations() {
        val result = apply(custom(mode = CustomTransition.MODE_NO_MIX), out = 150.0)
        assertEquals(false, result.blocked, "a different cut is not the pair that was saved")
        assertTrue("custom-fallback-duration" in result.policyReasons)
    }

    @Test
    fun presetsChooseTheGesture() {
        val fade = apply(custom(style = MixStyle.FADE, bassSwap = true))
        assertEquals(TransitionStyle.EQUAL_POWER, fade.transitionStyle)
        assertEquals(false, fade.bassSwap)

        val rise = apply(custom(style = MixStyle.RISE))
        assertEquals(TransitionStyle.DJ_FILTER, rise.transitionStyle)
        assertEquals(FILTER_SWEEP, rise.filterSweep)

        val smooth = apply(custom(style = MixStyle.SMOOTH))
        assertEquals(planner().transitionStyle, smooth.transitionStyle, "Smooth keeps the planner's style")
        assertEquals(false, smooth.bassSwap, "the bass swap is the listener's switch")
        assertEquals(true, apply(custom(style = MixStyle.SMOOTH, bassSwap = true)).bassSwap)

        val quick = apply(custom(style = MixStyle.QUICK, bassSwap = true, before = 30.0))
        assertEquals(TransitionStyle.DJ_BLEND, quick.transitionStyle)
        assertTrue(quick.bassSwap)
        assertClose(0.5, quick.echoSeconds, "echo on a beat-matched blend with room")
        assertEquals(0.0, apply(custom(style = MixStyle.QUICK, before = 17.0)).echoSeconds, "no room for the repeats")
        val unmatched = planner().copy(transitionStyle = TransitionStyle.DJ_FILTER)
        assertEquals(0.0, apply(custom(style = MixStyle.QUICK, before = 30.0), plan = unmatched).echoSeconds, "no echo on an unmatched pair")
    }

    @Test
    fun aWeakGridPlanStillTakesTheListenersChoices() {
        val weak = planner(inConfidence = 0.1)
        assertEquals(TransitionStyle.EQUAL_POWER, weak.transitionStyle)
        val result = apply(custom(style = MixStyle.RISE), plan = weak)
        assertEquals(TransitionStyle.DJ_FILTER, result.transitionStyle, "custom wins over the planner's fallback")
        assertTrue("beat-confidence" in result.policyReasons, "the planner's reasons stay on record")
    }

    @Test
    fun theExitFilterRidesAlong() {
        val low = apply(custom(filter = CustomTransition.FILTER_LOW_PASS))
        assertEquals(ExitFilter.LOW_PASS, low.exitFilter)
        assertClose(0.6, low.exitFilterAmount, "amount")
        val high = apply(custom(filter = CustomTransition.FILTER_HIGH_PASS))
        assertEquals(ExitFilter.HIGH_PASS, high.exitFilter)
        val none = apply(custom())
        assertEquals(ExitFilter.NONE, none.exitFilter)
        assertEquals(0.0, none.exitFilterAmount)
    }

    @Test
    fun theAutomaticPlannerNeverSetsAnExitFilter() {
        assertEquals(ExitFilter.NONE, planner().exitFilter)
        assertSame(planner().exitFilter, TransitionPlan().exitFilter)
    }

    @Test
    fun snapshotsRoundTripThroughJson() {
        val snapshot = CustomTransitionSnapshot(
            mixPlaylists = listOf("PLabc", "local:playlist:7"),
            transitions = mapOf(
                customTransitionKey("PLabc", "a", "b") to custom(style = MixStyle.RISE, filter = CustomTransition.FILTER_HIGH_PASS),
                customTransitionKey("PLabc", "b", "c") to custom(mode = CustomTransition.MODE_NO_MIX),
                customTransitionKey("local:playlist:7", "x", "y") to custom(bars = 16, bassSwap = true),
            ),
        )
        val json = Json { encodeDefaults = true }
        assertEquals(snapshot, json.decodeFromString<CustomTransitionSnapshot>(json.encodeToString(snapshot)))
    }

    @Test
    fun anOlderOrSparseFileStillDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val sparse = json.decodeFromString<CustomTransitionSnapshot>(
            """{"transitions":{"p|a->b":{"mode":"gapless","outDurationMs":1000,"someFutureField":1}}}""",
        )
        assertEquals(CustomTransition.MODE_GAPLESS, sparse.transitions.getValue("p|a->b").mode)
        assertEquals(emptyList(), sparse.mixPlaylists)
    }

    @Test
    fun keysAreDirectionalAndPerPlaylist() {
        assertEquals("PL|a->b", customTransitionKey("PL", "a", "b"))
        assertTrue(customTransitionKey("PL", "a", "b") != customTransitionKey("PL", "b", "a"))
        assertTrue(customTransitionKey("PL1", "a", "b") != customTransitionKey("PL2", "a", "b"))
    }
}
