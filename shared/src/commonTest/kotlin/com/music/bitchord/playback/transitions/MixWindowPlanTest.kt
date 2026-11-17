package com.music.bitchord.playback.transitions

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the editor's window loader (design doc 4.8). */
class MixWindowPlanTest {
    private val mb = 1024L * 1024

    @Test
    fun aTailWindowIsTheEndOfTheFilePlusTheHeader() {
        // 4 MB over 240 s is ~17.5 KB/s; (45 + 8) s is ~0.93 MB.
        val plan = planWindowFetch(4 * mb, 240.0, WindowSide.TAIL)!!
        assertEquals(ByteRange(0, CONTAINER_HEADER_BYTES), plan.header)
        assertEquals(4 * mb, plan.window.end, "runs to the end of the stream")
        assertTrue(abs(plan.window.length - 53 * (4 * mb) / 240.0) < 4.0, "53 s of bytes, was ${plan.window.length}")
        assertEquals(CONTAINER_HEADER_BYTES + plan.window.length, plan.totalBytes, "header and window do not overlap")
    }

    @Test
    fun aHeadWindowStartsAtTheTopAndSharesTheHeader() {
        val plan = planWindowFetch(4 * mb, 240.0, WindowSide.HEAD)!!
        assertEquals(0, plan.window.position)
        assertEquals(plan.window.length, plan.totalBytes, "the header is inside the window, counted once")
    }

    @Test
    fun aWindowOverTheBudgetIsRefused() {
        // 40 MB over 200 s: 53 s is ~10.6 MB.
        assertNull(planWindowFetch(40 * mb, 200.0, WindowSide.TAIL))
        assertNull(planWindowFetch(40 * mb, 200.0, WindowSide.HEAD))
        assertTrue(planWindowFetch(40 * mb, 200.0, WindowSide.TAIL, budgetBytes = 12 * mb) != null)
    }

    @Test
    fun unknownSizeOrLengthCannotBePlanned() {
        assertNull(planWindowFetch(0, 200.0, WindowSide.TAIL))
        assertNull(planWindowFetch(4 * mb, 0.0, WindowSide.TAIL))
        assertNull(planWindowFetch(4 * mb, Double.NaN, WindowSide.TAIL))
        assertNull(planWindowFetch(4 * mb, Double.POSITIVE_INFINITY, WindowSide.TAIL))
    }

    @Test
    fun aShortSongIsFetchedWhole() {
        val plan = planWindowFetch(600 * 1024, 30.0, WindowSide.TAIL)!!
        assertEquals(0, plan.window.position)
        assertEquals(600 * 1024, plan.totalBytes)
    }

    @Test
    fun theSourceOrderIsSavedThenCacheThenFetch() {
        assertEquals(WindowSource.SAVED, decideWindowSource(true, true, wifiOnly = true, metered = true))
        assertEquals(WindowSource.CACHE, decideWindowSource(false, true, wifiOnly = true, metered = true))
        assertEquals(WindowSource.FETCH, decideWindowSource(false, false, wifiOnly = true, metered = false))
        assertEquals(WindowSource.FETCH, decideWindowSource(false, false, wifiOnly = false, metered = true))
    }

    @Test
    fun wifiOnlyBlocksTheFetchOnAMeteredOrUnknownConnection() {
        assertEquals(WindowSource.WAIT_FOR_WIFI, decideWindowSource(false, false, wifiOnly = true, metered = true))
        assertEquals(WindowSource.WAIT_FOR_WIFI, decideWindowSource(false, false, wifiOnly = true, metered = null))
        // ...but never blocks what needs no network.
        assertEquals(WindowSource.CACHE, decideWindowSource(false, true, wifiOnly = true, metered = null))
        assertEquals(WindowSource.SAVED, decideWindowSource(true, false, wifiOnly = true, metered = true))
    }

    @Test
    fun beatsAreExtendedInBothDirectionsAtTheSameInterval() {
        val measured = listOf(10.0, 10.5, 11.0, 11.5)
        val extended = extendBeats(measured, 0.5, 8.0, 13.0)
        assertEquals(listOf(8.0, 8.5, 9.0, 9.5, 10.0, 10.5, 11.0, 11.5, 12.0, 12.5, 13.0), extended)
        assertEquals(measured, extendBeats(measured, 0.0, 8.0, 13.0), "no interval: nothing to extend from")
        assertEquals(emptyList(), extendBeats(emptyList(), 0.5, 8.0, 13.0))
        assertEquals(measured, extendBeats(measured, 0.5, 11.0, 10.0), "an empty range changes nothing")
    }

    @Test
    fun snappingMovesToTheNearestBeatWithinReach() {
        val beats = listOf(1.0, 1.5, 2.0, 2.5)
        assertEquals(1.5, snapToBeat(1.6, beats))
        assertEquals(2.0, snapToBeat(1.76, beats))
        assertEquals(1.0, snapToBeat(0.2, beats), "before the first beat: the first beat")
        assertEquals(1.62, snapToBeat(1.62, beats, maxDistance = 0.05), "too far to snap")
        assertEquals(1.5, snapToBeat(1.52, beats, maxDistance = 0.05))
        assertEquals(3.3, snapToBeat(3.3, emptyList()), "no grid: free")
    }

    @Test
    fun levelsRoundTripWithinOneStep() {
        val peaks = floatArrayOf(0f, 0.25f, 0.5f, 1f, 1.7f, -0.4f)
        val levels = peaks.toLevels()
        assertEquals(listOf(0, 64, 128, 255, 255, 0), levels)
        val back = levels.toPeaks()
        assertTrue(abs(back[1] - 0.25f) < 1f / 255f + 1e-6f)
        assertEquals(1f, back[3])
    }

    @Test
    fun aTrustedGridNeedsEightBeatsConfidenceAndASaneTempo() {
        fun window(beats: Int = 12, confidence: Double = 0.9, bpm: Double = 120.0) = MixWindow(
            trackId = "t", side = "tail", durationMs = 1, fromSeconds = 0.0, toSeconds = 1.0,
            low = listOf(1), mid = listOf(1), high = listOf(1),
            beats = List(beats) { it * 0.5 }, bpm = bpm, beatConfidence = confidence,
        )
        assertTrue(window().hasTrustedGrid)
        assertTrue(!window(beats = 7).hasTrustedGrid)
        assertTrue(!window(confidence = 0.3).hasTrustedGrid)
        assertTrue(!window(bpm = 250.0).hasTrustedGrid)
    }
}
