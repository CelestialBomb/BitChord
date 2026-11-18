package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.PHRASE_BARS_LONG
import com.music.bitchord.playback.smart.PHRASE_BARS_SHORT
import kotlin.test.Test
import kotlin.test.assertEquals

class BeatAwareConfigTest {
    @Test
    fun disabledKeepsTheOriginalPlannerArguments() {
        val options = BeatAwareConfig(enabled = false).optionsFor(300.0)
        assertEquals(PlannerOptions(), options, "off must be a strict no-op")
    }

    @Test
    fun eightBarsForShorterTracksAndSixteenForLongerOnes() {
        assertEquals(PHRASE_BARS_SHORT, BeatAwareConfig(enabled = true).optionsFor(199.9).phraseBars)
        assertEquals(PHRASE_BARS_LONG, BeatAwareConfig(enabled = true).optionsFor(240.0).phraseBars)
    }

    @Test
    fun everyNewBehaviourCanBeDisabledIndividually() {
        val original = BeatAwareConfig(
            enabled = true,
            phraseAligned = false,
            tempoMatching = false,
            albumGapless = false,
        ).optionsFor(300.0)
        assertEquals(0, original.phraseBars)
        assertEquals(false, original.albumSequential)
        assertEquals(false, original.allowTempoStretch)
        // The two safety rules stay on for the whole feature.
        assertEquals(true, original.strictConfidence)
        assertEquals(true, original.mixWindow)
    }
}
