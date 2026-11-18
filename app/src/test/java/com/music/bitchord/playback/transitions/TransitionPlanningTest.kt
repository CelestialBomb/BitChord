package com.music.bitchord.playback.transitions

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueShuffle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPlanningTest {

    @After
    fun reset() {
        AppSettings.beatAwareTransitions.value = false
        AppSettings.beatAwareAlbumGapless.value = true
        QueueShuffle.setEnabled(false)
    }

    private val item = MediaItem.Builder()
        .setMediaId("a")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("Song").setArtist("Artist").setAlbumTitle("Live at Wembley").build(),
        )
        .build()

    @Test
    fun beatAwareOffAsksTheV191Planner() {
        AppSettings.beatAwareTransitions.value = false
        assertEquals(PlannerOptions(), TransitionPlanning.options(200_000))
    }

    @Test
    fun beatAwareOffPassesNoAlbumSoTheLiveRuleIsUnchanged() {
        AppSettings.beatAwareTransitions.value = false
        val info = TransitionPlanning.trackInfo(item, 200_000)
        assertEquals("", info.album)
        assertEquals("Song", info.title)
    }

    @Test
    fun beatAwareOnPassesTheAlbum() {
        AppSettings.beatAwareTransitions.value = true
        assertEquals("Live at Wembley", TransitionPlanning.trackInfo(item, 200_000).album)
    }

    @Test
    fun albumGaplessOnlyWithTheAppsShuffleOff() {
        AppSettings.beatAwareTransitions.value = true
        AppSettings.beatAwareAlbumGapless.value = true
        QueueShuffle.setEnabled(false)
        assertTrue(TransitionPlanning.options(200_000).albumSequential)
        QueueShuffle.setEnabled(true)
        assertFalse(TransitionPlanning.options(200_000).albumSequential)
    }
}
