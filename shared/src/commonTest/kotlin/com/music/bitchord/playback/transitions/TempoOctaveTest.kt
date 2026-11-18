package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.incomingRateBetween
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.stretchReachable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A track tracked at double or half its felt tempo (192 against 96) is the same
 * pulse: the planner folds the incoming tempo into the outgoing one's octave
 * before it decides anything, so such a pair is a plain, unstretched beat match.
 */
class TempoOctaveTest {
    private val duration = 200.0

    private fun track(id: String, bpm: Double): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
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

    private fun plan(outBpm: Double, inBpm: Double): TransitionPlan =
        with(BeatAwareConfig(enabled = true).optionsFor(duration)) {
            planTransition(
                analysis = track("a", outBpm),
                nextAnalysis = track("b", inBpm),
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

    @Test
    fun theIncomingTempoIsFoldedIntoTheOutgoingOctave() {
        assertEquals(96.0, alignTempoOctave(96.0, 192.0))
        assertEquals(192.0, alignTempoOctave(192.0, 96.0))
        assertEquals(96.0, alignTempoOctave(96.0, 96.0))
        assertEquals(1.0, incomingRateBetween(96.0, 192.0), 1e-9)
        assertEquals(1.0, incomingRateBetween(192.0, 96.0), 1e-9)
        assertTrue(stretchReachable(96.0, 192.0))
        assertTrue(stretchReachable(192.0, 96.0))
    }

    @Test
    fun aDoubleTimePairIsABeatMatchedMixWithNoStretch() {
        for ((out, into) in listOf(192.0 to 96.0, 96.0 to 192.0, 140.0 to 70.0, 70.0 to 140.0)) {
            val plan = plan(out, into)
            assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, "$out -> $into: ${plan.reason}")
            assertEquals(1.0, plan.incomingPlaybackRate, "$out -> $into must not stretch")
            assertEquals(1.0, plan.outgoingPlaybackRate, "$out -> $into must not stretch")
            assertTrue("tempo-distance" !in plan.policyReasons, "$out -> $into: ${plan.policyReasons}")
            assertTrue("unmixable-pair" !in plan.policyReasons, "$out -> $into: ${plan.policyReasons}")
        }
    }

    @Test
    fun aNearOctavePairNeedsOnlyTheSmallStretchToMeet() {
        // 190 against 96: 96 doubles to 192, so the two meet with a ~1% nudge.
        val plan = plan(190.0, 96.0)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, plan.reason)
        assertTrue(abs(plan.incomingPlaybackRate - 1.0) < 0.03, "was ${plan.incomingPlaybackRate}")
        assertTrue(stretchReachable(190.0, 96.0))
    }

    @Test
    fun aPairThatIsNotAnOctaveApartIsNotForcedToMatch() {
        // 192 against 130 needs ~48% either way; folding cannot help.
        assertTrue(!stretchReachable(192.0, 130.0))
        val plan = plan(192.0, 130.0)
        assertTrue(plan.transitionStyle != TransitionStyle.DJ_BLEND || plan.incomingPlaybackRate == 1.0, plan.reason)
        assertTrue("tempo-distance" in plan.policyReasons, plan.policyReasons.toString())
    }
}
