package com.music.bitchord.playback.transitions

import androidx.media3.common.MediaItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.EXTRA_ALBUM_ID
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionTrackInfo

/**
 * What the Automix planner is asked for one pair, from the listener's
 * Beat-aware settings (design doc sections 2 and 3). The controller's only
 * planner-facing code, so it stays one call there.
 *
 * With Beat-aware off everything here is the v1.9.1 answer: default options and
 * no album fields on the tracks. The style and length apply with or without
 * Beat-aware, and at their defaults (Smooth, Medium) change nothing.
 */
internal object TransitionPlanning {

    fun config() = BeatAwareConfig(
        enabled = AppSettings.beatAwareTransitions.value,
        phraseAligned = AppSettings.beatAwarePhraseAligned.value,
        tempoMatching = AppSettings.beatAwareTempoMatching.value,
        albumGapless = AppSettings.beatAwareAlbumGapless.value,
    )

    fun options(outgoingDurationMs: Long): PlannerOptions {
        val options = config().optionsFor(outgoingDurationMs / 1000.0)
        // R3 is for an album *played in order*. The app's own shuffle reorders
        // the queue itself and leaves ExoPlayer's shuffle flag false, so that
        // flag would call a shuffled album "in order"; ask the app's state.
        return if (options.albumSequential && QueueShuffle.enabled.value) {
            options.copy(albumSequential = false)
        } else {
            options
        }
    }

    /**
     * The planner's view of a queue item. The album title and ID only go in with
     * Beat-aware on: v1.9.1 passed neither, and the planner's live / spoken-word
     * rule (R4) also reads the album title, so passing it with Beat-aware off
     * would change which pairs v1.9.1 mixes.
     */
    fun trackInfo(item: MediaItem, durationMs: Long): TransitionTrackInfo {
        val withAlbum = AppSettings.beatAwareTransitions.value
        return TransitionTrackInfo(
            id = item.mediaId,
            durationMs = durationMs,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = if (withAlbum) item.mediaMetadata.albumTitle?.toString().orEmpty() else "",
            albumId = if (withAlbum) albumIdOf(item) else "",
        )
    }

    /**
     * The listener's Mix style and Blend length over the planner's answer
     * (design doc section 2). Applied whenever Automix is on; only a mix the
     * planner chose is restyled, and Smooth + Medium return [plan] itself.
     */
    fun restyle(plan: TransitionPlan, durationMs: Long, positionMs: Long): TransitionPlan = applyMixStyle(
        plan,
        AppSettings.automixStyle.value,
        AppSettings.automixBlendLength.value,
        durationSeconds = durationMs / 1000.0,
        currentTime = positionMs / 1000.0,
    )

    /** For the plan log line: `smooth/medium`. */
    fun styleKey(): String = "${AppSettings.automixStyle.value.key}/${AppSettings.automixBlendLength.value.key}"

    /** The album ID the queue row was built with (see `toSong`), or empty. */
    fun albumIdOf(item: MediaItem): String =
        item.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID).orEmpty()
}
