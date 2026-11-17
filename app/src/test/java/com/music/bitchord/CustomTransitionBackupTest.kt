package com.music.bitchord

import com.music.bitchord.data.CustomTransitionStore
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.CustomTransitionSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Custom transitions and the per-playlist Mix flags ride along in the app's own
 * backup file, and come back from it: replacing what is on the device, and
 * leaving it alone when the file is from before they existed.
 */
class CustomTransitionBackupTest {
    private val rise = CustomTransition(
        style = "rise",
        bars = 16,
        startBeforeEndSeconds = 30.0,
        filter = CustomTransition.FILTER_HIGH_PASS,
        filterAmount = 0.4,
        outDurationMs = 212_000,
        inDurationMs = 187_000,
    )
    private val noMix = CustomTransition(mode = CustomTransition.MODE_NO_MIX, outDurationMs = 180_000, inDurationMs = 200_000)

    @Before
    fun clean() = CustomTransitionStore.replaceAll(CustomTransitionSnapshot())

    @After
    fun cleanAfter() = CustomTransitionStore.replaceAll(CustomTransitionSnapshot())

    private fun fill() {
        CustomTransitionStore.setMix("PLyoutube", true)
        CustomTransitionStore.setMix("local:playlist:7", true)
        CustomTransitionStore.setMix("PLoff", true)
        CustomTransitionStore.setMix("PLoff", false)
        CustomTransitionStore.save("PLyoutube", "a", "b", rise)
        CustomTransitionStore.save("PLyoutube", "b", "c", noMix)
        CustomTransitionStore.save("local:playlist:7", "x", "y", rise.copy(bassSwap = true))
    }

    @Test
    fun backupThenRestoreKeepsEverything() {
        fill()
        val before = CustomTransitionStore.snapshot.value
        val text = Backup.encode(Backup.BackupFile(customTransitions = before))

        // A fresh device: nothing saved.
        CustomTransitionStore.replaceAll(CustomTransitionSnapshot())
        assertFalse(CustomTransitionStore.isMixOn("PLyoutube"))
        assertNull(CustomTransitionStore.get("PLyoutube", "a", "b"))

        Backup.restoreCustomTransitions(Backup.decode(text))

        assertEquals(before, CustomTransitionStore.snapshot.value)
        assertTrue(CustomTransitionStore.isMixOn("PLyoutube"))
        assertTrue(CustomTransitionStore.isMixOn("local:playlist:7"))
        assertFalse("a playlist whose Mix was switched off stays off", CustomTransitionStore.isMixOn("PLoff"))
        assertEquals(rise, CustomTransitionStore.get("PLyoutube", "a", "b"))
        assertEquals(noMix, CustomTransitionStore.get("PLyoutube", "b", "c"))
        assertEquals(true, CustomTransitionStore.get("local:playlist:7", "x", "y")?.bassSwap)
        assertNull("pairs are per playlist", CustomTransitionStore.get("local:playlist:7", "a", "b"))
    }

    @Test
    fun theExportWritesTheSection() {
        fill()
        val text = Backup.encode(Backup.BackupFile(customTransitions = CustomTransitionStore.snapshot.value))
        assertTrue(text, "\"customTransitions\"" in text)
        assertTrue(text, "PLyoutube|a->b" in text)
        assertNotNull(Backup.decode(text).customTransitions)
    }

    @Test
    fun aRestoreReplacesRatherThanMerges() {
        fill()
        val backedUp = CustomTransitionStore.snapshot.value
        CustomTransitionStore.setMix("PLnew", true)
        CustomTransitionStore.save("PLnew", "p", "q", noMix)

        Backup.restoreCustomTransitions(Backup.decode(Backup.encode(Backup.BackupFile(customTransitions = backedUp))))

        assertEquals(backedUp, CustomTransitionStore.snapshot.value)
        assertFalse(CustomTransitionStore.isMixOn("PLnew"))
        assertNull(CustomTransitionStore.get("PLnew", "p", "q"))
    }

    @Test
    fun anEmptySectionClearsWhatIsOnTheDevice() {
        fill()
        Backup.restoreCustomTransitions(Backup.decode(Backup.encode(Backup.BackupFile(customTransitions = CustomTransitionSnapshot()))))
        assertEquals(CustomTransitionSnapshot(), CustomTransitionStore.snapshot.value)
    }

    @Test
    fun aBackupFromBeforeTheFeatureLeavesThemAlone() {
        fill()
        val before = CustomTransitionStore.snapshot.value
        val old = Backup.decode("""{"app":"bitchord","version":1,"versionName":"1.9.1","settings":{},"listening":[]}""")
        assertNull(old.customTransitions)

        Backup.restoreCustomTransitions(old)

        assertEquals(before, CustomTransitionStore.snapshot.value)
    }

    @Test
    fun aFileFromALaterBuildStillReads() {
        val later = Backup.decode(
            """{"app":"bitchord","version":1,"customTransitions":{"mixPlaylists":["PL1"],"transitions":{},"somethingNew":true},"alsoNew":[1]}""",
        )
        assertEquals(listOf("PL1"), later.customTransitions?.mixPlaylists)
    }

    @Test
    fun storeEditsBehaveLikeTheUiUsesThem() {
        CustomTransitionStore.save("PL", "a", "b", rise)
        CustomTransitionStore.reset("PL", "a", "b")
        assertNull(CustomTransitionStore.get("PL", "a", "b"))
        CustomTransitionStore.setMix("PL", true)
        CustomTransitionStore.setMix("PL", true)
        assertEquals("turning Mix on twice lists the playlist once", listOf("PL"), CustomTransitionStore.snapshot.value.mixPlaylists)
    }
}
