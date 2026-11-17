package com.music.bitchord

import com.music.bitchord.playback.smart.AnalysisBridge
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.MixStyle
import com.music.bitchord.ui.screens.TransitionSummary
import com.music.bitchord.ui.screens.traitsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a playlist's rows and transition lines say. */
class PlaylistMixLabelsTest {
    private fun bpm(n: Int) = "$n BPM"

    @Test
    fun noSavedTransitionReadsAsAuto() {
        assertEquals(TransitionSummary.Kind.AUTO, TransitionSummary.of(null).kind)
    }

    @Test
    fun theModesReadAsThemselves() {
        assertEquals(TransitionSummary.Kind.GAPLESS, TransitionSummary.of(CustomTransition(mode = CustomTransition.MODE_GAPLESS)).kind)
        assertEquals(TransitionSummary.Kind.NO_MIX, TransitionSummary.of(CustomTransition(mode = CustomTransition.MODE_NO_MIX)).kind)
    }

    @Test
    fun aMixCarriesItsStyleAndLength() {
        val summary = TransitionSummary.of(CustomTransition(style = "rise", bars = 16, seconds = 12.4))
        assertEquals(TransitionSummary.Kind.MIX, summary.kind)
        assertEquals(MixStyle.RISE, summary.style)
        assertEquals(16, summary.bars)
        assertEquals(12, summary.seconds)
    }

    @Test
    fun aLoopMixReadsAsOneWithItsLoopLength() {
        val summary = TransitionSummary.of(CustomTransition(loop = true, loopBars = 2, style = "rise", bars = 16))
        assertEquals(TransitionSummary.Kind.LOOP, summary.kind)
        assertEquals(2, summary.bars)
        // the flag means nothing on a mode that never mixes
        assertEquals(TransitionSummary.Kind.GAPLESS, TransitionSummary.of(CustomTransition(mode = CustomTransition.MODE_GAPLESS, loop = true)).kind)
    }

    @Test
    fun anUnknownStyleKeyFallsBackToSmooth() {
        assertEquals(MixStyle.SMOOTH, TransitionSummary.of(CustomTransition(style = "from-a-later-build")).style)
    }

    @Test
    fun tempoAndKeyShowTogetherOrAlone() {
        assertEquals("124 BPM · C major", traitsLine(AnalysisBridge.Traits(123.6, "C major"), ::bpm))
        assertEquals("90 BPM", traitsLine(AnalysisBridge.Traits(90.0, ""), ::bpm))
        assertEquals("90 BPM", traitsLine(AnalysisBridge.Traits(90.2, "  "), ::bpm))
    }

    @Test
    fun nothingKnownShowsNothing() {
        assertNull(traitsLine(null, ::bpm))
        assertNull("a key with no tempo is not a measurement", traitsLine(AnalysisBridge.Traits(0.0, "C major"), ::bpm))
    }
}
