package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.transitions.WindowSide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * The playlist page and the transition editor's way to the service's
 * [TrackAnalyzer], which lives in the playback service but in the same process.
 * Null until the service has started, when every call answers "nothing yet".
 */
object AnalysisBridge {
    @Volatile
    var analyzer: TrackAnalyzer? = null

    private val _version = MutableStateFlow(0)

    /** Bumped whenever something new may have been learned, so rows and the editor redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun bump() {
        _version.value += 1
    }

    /** What a playlist row shows under its title. */
    data class Traits(val bpm: Double, val key: String)

    /**
     * Tempo and key of [song] if either is known: the analysis in memory or
     * stored, else the tempo of its saved opening window. Reads a file, so call
     * it off the main thread.
     */
    fun traitsOf(song: Song): Traits? {
        val a = analyzer ?: return null
        a.knownAnalysis(song.videoId)?.let { return Traits(it.bpm, it.key) }
        return a.savedWindow(song.videoId, WindowSide.HEAD, song.durationMillis())
            ?.takeIf { it.bpm > 0 }
            ?.let { Traits(it.bpm, "") }
    }

    /** The editor's window for one end of [song], see [TrackAnalyzer.mixWindow]; null before the service is up. */
    suspend fun mixWindow(song: Song, side: WindowSide): TrackAnalyzer.WindowResult? {
        val a = analyzer ?: return null
        val uri = song.toMediaItem().localConfiguration?.uri ?: return null
        return a.mixWindow(song.videoId, uri, song.durationMillis() / 1000.0, side).also { bump() }
    }

    /** Why [analysePlaylist] did or did not run. */
    sealed interface Outcome {
        /** Wi-Fi only is on and the connection is metered or unknown: nothing was started. */
        data object NeedsWifi : Outcome

        /** The service is not running yet. */
        data object NotReady : Outcome

        data class Done(val analysed: Int, val total: Int) : Outcome
    }

    /**
     * Asks for the tempo and key of every song of a playlist that has none yet,
     * four at a time (each request opens a fetch of the song's opening), and
     * keeps the rows redrawing as results arrive. Respects Wi-Fi only; cancel the
     * calling coroutine to stop.
     */
    suspend fun analysePlaylist(songs: List<Song>): Outcome {
        val a = analyzer ?: return Outcome.NotReady
        if (AppSettings.wifiOnlyDownloads.value && AppSettings.meteredConnection.value != false) {
            return Outcome.NeedsWifi
        }
        val todo = songs.filter { a.knownAnalysis(it.videoId) == null }
        var analysed = songs.size - todo.size
        for (batch in todo.chunked(BATCH)) {
            for (song in batch) {
                val uri = song.toMediaItem().localConfiguration?.uri ?: continue
                a.request(song.videoId, uri, song.durationMillis() / 1000.0)
            }
            var waited = 0L
            while (coroutineContext.isActive && waited < BATCH_TIMEOUT_MS &&
                batch.any { a.knownAnalysis(it.videoId) == null }
            ) {
                delay(POLL_MS)
                waited += POLL_MS
                bump()
            }
            analysed += batch.count { a.knownAnalysis(it.videoId) != null }
            bump()
            if (!coroutineContext.isActive) break
        }
        return Outcome.Done(analysed, songs.size)
    }

    private const val BATCH = 4
    private const val POLL_MS = 1_500L
    private const val BATCH_TIMEOUT_MS = 45_000L
}
