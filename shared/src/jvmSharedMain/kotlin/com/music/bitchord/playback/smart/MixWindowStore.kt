package com.music.bitchord.playback.smart

import com.music.bitchord.playback.transitions.CUSTOM_DURATION_TOLERANCE_MS
import com.music.bitchord.playback.transitions.MixWindow
import com.music.bitchord.playback.transitions.WindowSide
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Decoded editor windows (waveform peaks and beat grid), one small file per song
 * and side, so reopening a transition is instant and a song in several playlists
 * is measured once. Like [AnalysisStore] it re-earns itself, so it is not part of
 * the in-app backup; like the analysis it is dropped, not migrated, when its
 * schema changes, and pruned by count.
 *
 * A window made for a different cut of the song (its length differs by more than
 * [CUSTOM_DURATION_TOLERANCE_MS]) is a miss: its times would not line up.
 */
class MixWindowStore(
    private val directoryOf: () -> File,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val directory by lazy { File(directoryOf(), DIRECTORY) }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(trackId: String, side: WindowSide, durationMs: Long): MixWindow? {
        if (trackId.isBlank()) return null
        val file = File(directory, fileNameFor(trackId, side))
        if (!file.exists()) return null
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            require(stored.version == SCHEMA_VERSION) { "schema ${stored.version}" }
            // Names are hashed, so confirm this really is the song and side asked for.
            require(stored.window.trackId == trackId && stored.window.side == side.key) { "different song" }
            stored.window
        }.onFailure { file.delete() }
            .getOrNull()
            ?.takeIf { kotlin.math.abs(it.durationMs - durationMs) <= CUSTOM_DURATION_TOLERANCE_MS }
    }

    fun save(window: MixWindow) {
        val side = WindowSide.entries.firstOrNull { it.key == window.side } ?: return
        if (window.trackId.isBlank() || window.low.isEmpty()) return
        runCatching {
            directory.mkdirs()
            val file = File(directory, fileNameFor(window.trackId, side))
            val temporary = File(directory, file.name + ".tmp")
            try {
                temporary.writeText(json.encodeToString(Stored.serializer(), Stored(SCHEMA_VERSION, window)))
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }
        prune()
    }

    private fun prune() {
        val files = directory.listFiles { file -> file.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    }

    private fun fileNameFor(trackId: String, side: WindowSide) =
        "${trackId.hashCode().toUInt()}_${trackId.length}_${side.key}.json"

    @Serializable
    private class Stored(val version: Int, val window: MixWindow)

    private companion object {
        const val DIRECTORY = "mix-windows"
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 600
    }
}
