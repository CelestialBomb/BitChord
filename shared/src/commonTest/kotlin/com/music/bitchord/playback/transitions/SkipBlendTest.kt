package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mix on skip (design doc section 4.6). */
class SkipBlendTest {
    private fun analysis(bpm: Double = 120.0, confidence: Double = 0.9, duration: Double = 200.0, audibleStart: Double = 0.5): TrackAnalysis {
        val beat = 60.0 / bpm
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = confidence,
            firstBeat = 0.5,
            downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration }.toList(),
            audibleStartTime = audibleStart,
        )
    }

    private fun context(
        position: Double = 60.3,
        outgoing: TrackAnalysis = analysis(),
        incoming: TrackAnalysis = analysis(audibleStart = 3.0),
        style: MixStyle = MixStyle.SMOOTH,
        length: BlendLength = BlendLength.MEDIUM,
    ) = SkipContext(
        enabled = true,
        automix = true,
        positionSeconds = position,
        durationSeconds = 200.0,
        outgoing = outgoing,
        incoming = incoming,
        incomingDurationSeconds = 200.0,
        style = style,
        length = length,
    )

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    @Test
    fun offAndGuardsCut() {
        assertEquals("skip-cut-off", decideSkip(context().copy(enabled = false)).reason)
        assertEquals("skip-cut-off", decideSkip(context().copy(automix = false)).reason)
        val guarded = decideSkip(context().copy(guard = "listen together"))
        assertEquals(SkipAction.CUT, guarded.action)
        assertEquals("skip-cut-guard", guarded.reason)
    }

    @Test
    fun aSkipDuringAnyBlendCutsThrough() {
        val busy = decideSkip(context().copy(transitioning = true))
        assertEquals(SkipAction.CUT, busy.action)
        assertEquals("skip-cut-busy", busy.reason)
    }

    @Test
    fun rapidSkipsCutAndNeverStack() {
        assertEquals("skip-cut-rapid", decideSkip(context().copy(msSinceLastSkip = 400)).reason)
        assertEquals("skip-cut-rapid", decideSkip(context().copy(msSinceLastSkip = SKIP_RAPID_MS - 1)).reason)
        assertEquals(SkipAction.BLEND, decideSkip(context().copy(msSinceLastSkip = SKIP_RAPID_MS)).action)
    }

    @Test
    fun theLastSecondsOfASongJustSkip() {
        assertEquals("skip-cut-guard", decideSkip(context(position = 196.0)).reason)
        assertEquals(SkipAction.BLEND, decideSkip(context(position = 194.0)).action)
    }

    @Test
    fun lengthSetsTwoThreeOrFourSeconds() {
        assertClose(2.0, decideSkip(context(length = BlendLength.SHORT)).fadeSeconds, "short")
        assertClose(3.0, decideSkip(context(length = BlendLength.MEDIUM)).fadeSeconds, "medium")
        assertClose(4.0, decideSkip(context(length = BlendLength.LONG)).fadeSeconds, "long")
    }

    @Test
    fun startsOnTheNextBeatWithinHalfASecond() {
        // 120 BPM from 0.5 s: beats at 60.0, 60.5, ... so from 60.3 the next is 0.2 s away.
        val decision = decideSkip(context(position = 60.3))
        assertClose(0.2, decision.startDelaySeconds, "delay")
        assertClose(0.5, decision.beatSeconds, "beat")
    }

    @Test
    fun neverWaitsLongerThanHalfASecondForABeat() {
        // 40 BPM: 1.5 s beats from 0.5 s, so just after 60.5 the next is about 1.4 s away.
        val slow = decideSkip(context(position = 60.6, outgoing = analysis(bpm = 40.0)))
        assertEquals(0.0, slow.startDelaySeconds, "starts at once rather than wait 1.4 s")
        for (position in listOf(10.0, 33.33, 61.01, 150.77)) {
            val delay = decideSkip(context(position = position)).startDelaySeconds
            assertTrue(delay <= SKIP_MAX_BEAT_WAIT_SECONDS, "waited $delay s at $position")
        }
    }

    @Test
    fun noTrustedBeatMeansNoWaitAndAFilterSweep() {
        val weak = decideSkip(context(outgoing = analysis(confidence = 0.3)))
        assertEquals(0.0, weak.startDelaySeconds)
        assertEquals(0.0, weak.beatSeconds)
        assertEquals(TransitionStyle.DJ_FILTER, weak.transitionStyle)
        assertEquals(FilterTransitionVariant.SWEEP, weak.filterVariant)
        assertEquals(0.0, weak.echoSeconds)

        val unanalysed = decideSkip(context(outgoing = TrackAnalysis(), incoming = TrackAnalysis()))
        assertEquals(SkipAction.BLEND, unanalysed.action, "still a blend, just untimed")
        assertEquals(0.0, unanalysed.incomingCueSeconds, "unknown intro: from the start")
    }

    @Test
    fun smoothOnATrustedBeatEchoesOut() {
        val decision = decideSkip(context())
        assertEquals(TransitionStyle.DJ_FILTER, decision.transitionStyle)
        assertEquals(FilterTransitionVariant.ECHO_RIDE, decision.filterVariant)
        assertClose(0.5, decision.echoSeconds, "one beat")
        assertEquals(FilterTransitionVariant.ECHO_RIDE, decideSkip(context(style = MixStyle.QUICK)).filterVariant)
    }

    @Test
    fun anEchoNeedsTwoBeatsOfBlend() {
        // 2 s at 60 BPM is two beats: echo. At 50 BPM it is 1.7: sweep instead.
        val sixty = decideSkip(context(outgoing = analysis(bpm = 60.0), length = BlendLength.SHORT))
        assertEquals(FilterTransitionVariant.ECHO_RIDE, sixty.filterVariant)
        val fifty = decideSkip(context(outgoing = analysis(bpm = 50.0), length = BlendLength.SHORT))
        assertEquals(FilterTransitionVariant.SWEEP, fifty.filterVariant)
        assertEquals(0.0, fifty.echoSeconds)
    }

    @Test
    fun presetsChooseTheGesture() {
        val rise = decideSkip(context(style = MixStyle.RISE))
        assertEquals(TransitionStyle.DJ_FILTER, rise.transitionStyle)
        assertEquals(FilterTransitionVariant.SWEEP, rise.filterVariant)
        assertEquals(0.0, rise.echoSeconds)

        val fade = decideSkip(context(style = MixStyle.FADE))
        assertEquals(TransitionStyle.EQUAL_POWER, fade.transitionStyle)
        assertEquals(0.0, fade.filterSweep)
        assertClose(0.2, fade.startDelaySeconds, "still on the beat")
    }

    @Test
    fun theIncomingSongStartsAfterItsSilentIntroAtMostAQuarterIn() {
        assertClose(3.0, decideSkip(context()).incomingCueSeconds, "after a 3 s intro")
        val longIntro = decideSkip(context(incoming = analysis(audibleStart = 80.0)))
        assertClose(50.0, longIntro.incomingCueSeconds, "capped at 25 % of 200 s")
        val unknownLength = decideSkip(context().copy(incomingDurationSeconds = 0.0, incoming = analysis(duration = 8.0, audibleStart = 3.0)))
        assertClose(2.0, unknownLength.incomingCueSeconds, "falls back to the analysis's duration")
    }

    @Test
    fun theFallbackIsAQuickPlainFadeWithNoWait() {
        val fallback = skipFallback(decideSkip(context(length = BlendLength.LONG)))
        assertEquals(SkipAction.PLAIN_FADE, fallback.action)
        assertEquals("skip-fade", fallback.reason)
        assertClose(SKIP_FALLBACK_FADE_SECONDS, fallback.fadeSeconds, "fade")
        assertEquals(0.0, fallback.startDelaySeconds)
        assertEquals(TransitionStyle.EQUAL_POWER, fallback.transitionStyle)
        assertEquals(0.0, fallback.echoSeconds)
        assertClose(3.0, fallback.incomingCueSeconds, "entry point kept")
    }

    @Test
    fun theBlendAlwaysFitsInWhatIsLeft() {
        val decision = decideSkip(context(position = 194.0, length = BlendLength.LONG))
        assertTrue(decision.startDelaySeconds + decision.fadeSeconds <= 200.0 - 194.0, decision.toString())
    }
}
