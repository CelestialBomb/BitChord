package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Section 3 of docs/transitions-design.md: the new options against the real planner. */
class BeatAwarePlannerTest {
    private fun track(
        id: String,
        bpm: Double,
        confidence: Double = 0.9,
        duration: Double = 200.0,
    ): TrackAnalysis {
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

    private fun plan(outgoing: TrackAnalysis, incoming: TrackAnalysis, config: BeatAwareConfig) =
        with(config.optionsFor(outgoing.duration)) {
            planTransition(
                analysis = outgoing,
                nextAnalysis = incoming,
                currentTrack = TransitionTrackInfo(outgoing.trackId, (outgoing.duration * 1000).toLong()),
                nextTrack = TransitionTrackInfo(incoming.trackId, (incoming.duration * 1000).toLong()),
                currentTime = 10.0,
                duration = outgoing.duration,
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
    fun eitherWeakGridFallsBackToPlainCrossfade() {
        val plan = plan(track("a", 120.0), track("b", 120.0, confidence = 0.1), BeatAwareConfig(true))
        assertEquals("before-plain-crossfade-window", plan.reason)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(listOf("beat-confidence"), plan.policyReasons)
    }

    @Test
    fun legacyPolicyIsUnaffectedWhenBeatAwareIsOff() {
        val plan = plan(track("a", 120.0), track("b", 120.0, confidence = 0.1), BeatAwareConfig(false))
        assertEquals("before-smart-duration", plan.reason)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }

    @Test
    fun noTempoMatchingNeverChangesEitherDeckRate() {
        val plan = plan(track("a", 120.0), track("b", 111.0), BeatAwareConfig(true, tempoMatching = false))
        assertEquals(1.0, plan.incomingPlaybackRate)
        assertEquals(1.0, plan.outgoingPlaybackRate)
        assertTrue("stretch-disabled" in plan.policyReasons, plan.policyReasons.toString())
        assertEquals(32, plan.transitionBeats, "still an 8-bar, phrase-aligned overlap")
    }

    @Test
    fun shortTrackUsesEightBarsLongTrackUsesSixteenInsideTheMixWindow() {
        val short = plan(track("a", 120.0, duration = 200.0), track("b", 120.0), BeatAwareConfig(true))
        assertEquals(32, short.transitionBeats)
        assertEquals(16.0, short.fadeSeconds, 1e-6)

        val outgoing = track("a", 120.0, duration = 300.0)
        val long = plan(outgoing, track("b", 120.0, duration = 300.0), BeatAwareConfig(true))
        assertEquals(64, long.transitionBeats)
        assertTrue(long.transitionStart >= 270.0, "must not start before the last 30 s")
        assertTrue(long.incomingCueTime <= 30.0, "must not cue deep into the incoming track")
    }

    @Test
    fun sameAlbumCanBecomeGaplessWhenAsked() {
        val outgoing = track("a", 120.0)
        val incoming = track("b", 120.0)
        val info = TransitionTrackInfo("a", 200_000, album = "Album", artist = "Artist", albumId = "1")
        val next = TransitionTrackInfo("b", 200_000, album = "Album", artist = "Artist", albumId = "1")
        val options = BeatAwareConfig(enabled = true).optionsFor(200.0)
        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = info,
            nextTrack = next,
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
            phraseBars = options.phraseBars,
            albumSequential = options.albumSequential,
            mixWindow = options.mixWindow,
            strictConfidence = options.strictConfidence,
            allowTempoStretch = options.allowTempoStretch,
        )
        assertEquals(TransitionStyle.GAPLESS, plan.transitionStyle)
        assertEquals("before-gapless-window", plan.reason)
    }
}
