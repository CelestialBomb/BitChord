package com.music.bitchord.playback.transitions

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.TrackAnalysis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SkipBlenderTest {

    private val blender = SkipBlender()

    @Before
    fun on() {
        AppSettings.mixOnSkip.value = true
        AppSettings.smartFadeEnabled.value = true
    }

    @After
    fun reset() {
        AppSettings.mixOnSkip.value = false
        AppSettings.smartFadeEnabled.value = false
    }

    private fun press(at: Long, guard: String? = null) = blender.decide(
        nowMs = at,
        guard = guard,
        transitioning = false,
        positionMs = 60_000,
        durationMs = 200_000,
        outgoing = TrackAnalysis(),
        incoming = TrackAnalysis(),
        incomingDurationMs = 200_000,
        pair = "a->b",
        phase = "IDLE",
    )

    @Test
    fun aFirstPressBlendsAndASecondWithinTheRapidWindowCuts() {
        assertEquals(SkipAction.BLEND, press(at = 10_000).action)
        assertEquals("skip-cut-rapid", press(at = 10_000 + SKIP_RAPID_MS - 1).reason)
    }

    @Test
    fun pressesFurtherApartBothBlend() {
        press(at = 10_000)
        assertEquals(SkipAction.BLEND, press(at = 10_000 + SKIP_RAPID_MS + 1).action)
    }

    @Test
    fun theSettingOffCutsAndAGuardCuts() {
        AppSettings.mixOnSkip.value = false
        assertEquals("skip-cut-off", press(at = 1_000).reason)
        AppSettings.mixOnSkip.value = true
        assertEquals("skip-cut-guard", press(at = 10_000, guard = "paused").reason)
    }

    @Test
    fun aSlowNextSongFallsBackToAPlainFadeAndEndClearsIt() {
        val decision = press(at = 1_000)
        blender.start(decision, nowMs = 1_000)
        assertEquals(700L, blender.waitedMs(nowMs = 1_700))
        assertEquals("skip-fade", blender.fallBack().reason)
        assertEquals(SkipAction.PLAIN_FADE, blender.pending?.action)
        blender.end()
        assertNull(blender.pending)
    }

    @Test
    fun aLoopsContinueFadeIsArmedLikeASkipButIsNoSkip() {
        blender.start(SkipDecision(SkipAction.PLAIN_FADE, "loop-fallback-continue", fadeSeconds = 1.0), nowMs = 0, asSkip = false)
        assertEquals(false, blender.isSkip)
        blender.start(press(at = 10_000), nowMs = 10_000)
        assertEquals(true, blender.isSkip)
    }
}
