package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.FILTER_SWEEP
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Style preset and blend length (design doc section 2), against real planner output. */
class MixStyleTest {
    private fun track(id: String, bpm: Double, confidence: Double = 0.9, duration: Double = 200.0): TrackAnalysis {
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

    private fun plan(outBpm: Double = 120.0, inBpm: Double = 120.0, inConfidence: Double = 0.9, duration: Double = 200.0) =
        with(BeatAwareConfig(enabled = true).optionsFor(duration)) {
            planTransition(
                analysis = track("a", outBpm, duration = duration),
                nextAnalysis = track("b", inBpm, confidence = inConfidence),
                currentTrack = TransitionTrackInfo("a", (duration * 1000).toLong()),
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

    private fun styled(plan: TransitionPlan, style: MixStyle, length: BlendLength = BlendLength.MEDIUM, duration: Double = 200.0) =
        applyMixStyle(plan, style, length, duration, currentTime = 10.0)

    private fun bars(plan: TransitionPlan) = plan.fadeSeconds / (4 * plan.beatSeconds)

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    @Test
    fun defaultsReturnThePlanItself() {
        val plan = plan()
        assertSame(plan, styled(plan, MixStyle.SMOOTH, BlendLength.MEDIUM))
    }

    @Test
    fun theBaselineIsAnEightBarDjBlend() {
        val plan = plan()
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertClose(8.0, bars(plan), "bars")
    }

    @Test
    fun nonMixPlansAreNeverRestyled() {
        val weakGrid = plan(inConfidence = 0.1) // R6b: plain crossfade
        assertEquals(TransitionStyle.EQUAL_POWER, weakGrid.transitionStyle)
        val gapless = TransitionPlan(transitionStyle = TransitionStyle.GAPLESS, fadeSeconds = 0.12, transitionEnd = 200.0)
        val blocked = TransitionPlan(blocked = true, reason = "blocked-speech-or-live")
        for (plan in listOf(weakGrid, gapless, blocked)) {
            for (style in MixStyle.entries) for (length in BlendLength.entries) {
                assertSame(plan, styled(plan, style, length), "$style/$length changed ${plan.transitionStyle}")
            }
        }
    }

    @Test
    fun fadeIsAPlainEqualPowerBlendOnTheSameTiming() {
        val plan = plan()
        val fade = styled(plan, MixStyle.FADE)
        assertEquals(TransitionStyle.EQUAL_POWER, fade.transitionStyle)
        assertEquals(false, fade.bassSwap)
        assertEquals(0.0, fade.filterSweep)
        assertEquals(0.0, fade.echoSeconds)
        assertEquals(plan.fadeSeconds, fade.fadeSeconds)
        assertEquals(plan.transitionStart, fade.transitionStart)
        assertEquals(plan.incomingPlaybackRate, fade.incomingPlaybackRate, "timing and tempo are the planner's")
        assertTrue("style-fade" in fade.policyReasons)
    }

    @Test
    fun riseIsAFilterSweep() {
        val rise = styled(plan(), MixStyle.RISE)
        assertEquals(TransitionStyle.DJ_FILTER, rise.transitionStyle)
        assertEquals(FilterTransitionVariant.SWEEP, rise.filterVariant)
        assertEquals(FILTER_SWEEP, rise.filterSweep)
        assertEquals(false, rise.bassSwap)
    }

    @Test
    fun quickCapsAtEightBarsAndSwapsTheBass() {
        val long = plan(duration = 300.0) // 16 bars, squeezed to 26.5 s by the mix window (fixture 011)
        assertClose(26.5, long.fadeSeconds, "baseline")
        val quick = applyMixStyle(long, MixStyle.QUICK, BlendLength.MEDIUM, 300.0, 10.0)
        assertClose(8.0, bars(quick), "quick bars")
        assertEquals(long.transitionEnd, quick.transitionEnd, "the end stays put")
        assertClose(long.transitionEnd - quick.fadeSeconds, quick.transitionStart, "start")
        assertTrue(quick.bassSwap)
    }

    @Test
    fun quickEchoesOutOnlyWhenTheTailFitsAndTheBeatsAreLocked() {
        val plan = plan().copy(phaseLock = true)
        // Ending 1 s before the file: five 0.5 s repeats don't fit.
        val late = plan.copy(transitionEnd = 199.0, transitionStart = 199.0 - plan.fadeSeconds)
        assertEquals(0.0, styled(late, MixStyle.QUICK).echoSeconds)
        // With room after the blend, they do.
        val early = plan.copy(transitionEnd = 190.0, transitionStart = 190.0 - plan.fadeSeconds)
        assertClose(0.5, styled(early, MixStyle.QUICK).echoSeconds, "echo = one beat")
        // Not locked: no echo, which flams against another tempo.
        assertEquals(0.0, styled(early.copy(phaseLock = false), MixStyle.QUICK).echoSeconds)
    }

    @Test
    fun shortHalvesInWholeBarsKeepingTheEnd() {
        val plan = plan()
        val short = styled(plan, MixStyle.SMOOTH, BlendLength.SHORT)
        assertClose(4.0, bars(short), "bars")
        assertEquals(plan.transitionEnd, short.transitionEnd)
        assertClose(plan.transitionEnd - short.fadeSeconds, short.transitionStart, "start")
        assertEquals(16, short.transitionBeats)
        assertEquals(TransitionStyle.DJ_BLEND, short.transitionStyle, "length alone keeps the style")
        assertTrue("length-short" in short.policyReasons)
    }

    @Test
    fun longStretchesToFourBarLinesInsideTheMixWindow() {
        val plan = plan()
        val long = styled(plan, MixStyle.SMOOTH, BlendLength.LONG)
        assertClose(12.0, bars(long), "8 x 1.6 = 12.8, on a four-bar line")
        assertTrue(long.transitionStart >= 200.0 - 30.0, "inside the last 30 s")

        val sixteen = plan(duration = 300.0)
        val longer = applyMixStyle(sixteen, MixStyle.SMOOTH, BlendLength.LONG, 300.0, 10.0)
        assertTrue(longer.fadeSeconds <= 30.0, "capped at 30 s, was ${longer.fadeSeconds}")
        assertTrue(longer.transitionStart >= 270.0, "inside the last 30 s")
        // That plan already fills the window, so Long has no room and keeps Medium's length.
        assertClose(sixteen.fadeSeconds, longer.fadeSeconds, "no room: Medium's length")
    }

    @Test
    fun lengthsAreOrderedForEveryPreset() {
        for (duration in listOf(120.0, 200.0, 300.0)) for (style in MixStyle.entries) {
            val plan = plan(duration = duration)
            val (short, medium, long) = BlendLength.entries.map { applyMixStyle(plan, style, it, duration, 10.0).fadeSeconds }
            assertTrue(short <= medium + 1e-6 && medium <= long + 1e-6, "$style at $duration s: $short / $medium / $long")
            assertTrue(long <= MAX_STYLED_BLEND_SECONDS + 1e-6)
        }
    }

    @Test
    fun shortNeverGoesUnderFourSecondsWhenThereIsRoom() {
        val fast = plan(outBpm = 180.0, inBpm = 180.0) // a bar is 1.33 s
        val short = styled(fast, MixStyle.SMOOTH, BlendLength.SHORT)
        assertTrue(short.fadeSeconds >= MIN_STYLED_BLEND_SECONDS - 1e-6, "was ${short.fadeSeconds}")
        assertClose(0.0, bars(short) % 1.0, "whole bars")
    }

    @Test
    fun withoutAGridLengthIsPlainSeconds() {
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            fadeSeconds = 10.0,
            transitionStart = 188.0,
            transitionEnd = 198.0,
        )
        assertClose(5.0, styled(plan, MixStyle.SMOOTH, BlendLength.SHORT).fadeSeconds, "short")
        assertClose(16.0, styled(plan, MixStyle.SMOOTH, BlendLength.LONG).fadeSeconds, "long")
        assertClose(10.0, styled(plan, MixStyle.QUICK, BlendLength.MEDIUM).fadeSeconds, "quick needs a grid to cap")
    }

    @Test
    fun keysRoundTripAndUnknownKeysFallBackToDefaults() {
        for (style in MixStyle.entries) assertEquals(style, MixStyle.fromKey(style.key))
        for (length in BlendLength.entries) assertEquals(length, BlendLength.fromKey(length.key))
        assertEquals(MixStyle.SMOOTH, MixStyle.fromKey("nonsense"))
        assertEquals(BlendLength.MEDIUM, BlendLength.fromKey(null))
    }
}
