package com.music.bitchord.playback.smart

import com.music.bitchord.playback.transitions.MixWindow
import com.music.bitchord.playback.transitions.WindowSide
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixWindowStoreTest {
    private val root: File = Files.createTempDirectory("mix-windows-test").toFile()
    private val store = MixWindowStore({ root })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun window(id: String = "song-1", side: WindowSide = WindowSide.TAIL, durationMs: Long = 200_000) = MixWindow(
        trackId = id,
        side = side.key,
        durationMs = durationMs,
        fromSeconds = 155.0,
        toSeconds = 200.0,
        low = List(60) { it % 256 },
        mid = List(60) { (it * 3) % 256 },
        high = List(60) { 255 - it },
        beats = listOf(155.1, 155.6, 156.1),
        downbeats = listOf(155.1),
        bpm = 120.0,
        beatConfidence = 0.9,
        extrapolated = true,
    )

    private fun files() = File(root, "mix-windows").listFiles()!!.filter { it.name.endsWith(".json") }

    @Test
    fun aWindowSurvivesASaveAndALoad() {
        val saved = window()
        store.save(saved)
        assertEquals(saved, store.load("song-1", WindowSide.TAIL, 200_000))
    }

    @Test
    fun sidesAreKeptApart() {
        store.save(window(side = WindowSide.TAIL))
        assertNull(store.load("song-1", WindowSide.HEAD, 200_000))
        store.save(window(side = WindowSide.HEAD).copy(fromSeconds = 0.0, toSeconds = 45.0))
        assertEquals(155.0, store.load("song-1", WindowSide.TAIL, 200_000)?.fromSeconds)
        assertEquals(0.0, store.load("song-1", WindowSide.HEAD, 200_000)?.fromSeconds)
    }

    @Test
    fun aDifferentCutOfTheSongIsAMiss() {
        store.save(window(durationMs = 200_000))
        assertNotNull(store.load("song-1", WindowSide.TAIL, 201_900), "within 2 s is the same cut")
        assertNull(store.load("song-1", WindowSide.TAIL, 203_000))
    }

    @Test
    fun anUnreadableFileIsDroppedAndTreatedAsAMiss() {
        store.save(window())
        val file = files().single()
        file.writeText("{ not json")
        assertNull(store.load("song-1", WindowSide.TAIL, 200_000))
        assertTrue(!file.exists(), "the broken file is removed")
    }

    @Test
    fun aFileFromAnotherSchemaIsDropped() {
        store.save(window())
        val file = files().single()
        file.writeText(file.readText().replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": 99"))
        assertNull(store.load("song-1", WindowSide.TAIL, 200_000))
        assertTrue(!file.exists())
    }

    @Test
    fun blankIdsAndEmptyWindowsAreNotStored() {
        store.save(window(id = ""))
        store.save(window().copy(low = emptyList()))
        assertNull(store.load("", WindowSide.TAIL, 200_000))
        assertNull(store.load("song-1", WindowSide.TAIL, 200_000))
    }

    @Test
    fun theOldestEntriesArePrunedPastTheLimit() {
        val small = MixWindowStore({ root }, maxEntries = 3)
        for (n in 1..5) {
            small.save(window(id = "song-$n"))
            // Spread the file times so "oldest" is well defined on a fast disk.
            files().filter { it.lastModified() >= System.currentTimeMillis() - 60_000 }
                .maxByOrNull { it.lastModified() }
                ?.setLastModified(1_000_000L * n)
        }
        assertEquals(3, files().size)
        assertNull(small.load("song-1", WindowSide.TAIL, 200_000), "the oldest is gone")
        assertNull(small.load("song-2", WindowSide.TAIL, 200_000))
        assertNotNull(small.load("song-5", WindowSide.TAIL, 200_000))
    }
}
