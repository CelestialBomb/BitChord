package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.CustomTransitionSnapshot
import com.music.bitchord.playback.transitions.customTransitionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Custom transitions (design doc 4.8): which playlists have Mix on, and the
 * transition saved for each pair of songs in them. Local to the device, for
 * YouTube Music and local playlists alike, in a preference file of its own.
 *
 * The state is a plain [CustomTransitionSnapshot], so the backup carries it
 * as it is and [replaceAll] restores it. Reads are cheap and safe from any
 * thread (the controller asks once per planning tick); writes are rare.
 *
 * Without [init] nothing is persisted: the state lives in memory, which is
 * how the unit tests use it.
 */
object CustomTransitionStore {
    private const val PREF_NAME = "bitchord_custom_transitions"
    private const val KEY_SNAPSHOT = "snapshot_json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var persist: ((String) -> Unit)? = null

    private val _snapshot = MutableStateFlow(CustomTransitionSnapshot())

    /** Observed by the playlist page and the editor. */
    val snapshot: StateFlow<CustomTransitionSnapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        persist = { text -> prefs.edit().putString(KEY_SNAPSHOT, text).apply() }
        prefs.getString(KEY_SNAPSHOT, null)?.let { raw ->
            // A file that no longer parses is dropped rather than crashing start-up: the
            // listener loses their custom transitions, not the app.
            runCatching { json.decodeFromString(CustomTransitionSnapshot.serializer(), raw) }
                .onSuccess { _snapshot.value = it }
        }
    }

    fun isMixOn(playlistId: String): Boolean = playlistId in _snapshot.value.mixPlaylists

    fun setMix(playlistId: String, on: Boolean) = update { current ->
        val others = current.mixPlaylists.filterNot { it == playlistId }
        current.copy(mixPlaylists = if (on) others + playlistId else others)
    }

    fun get(playlistId: String, outgoingId: String, incomingId: String): CustomTransition? =
        _snapshot.value.transitions[customTransitionKey(playlistId, outgoingId, incomingId)]

    fun save(playlistId: String, outgoingId: String, incomingId: String, transition: CustomTransition) = update { current ->
        current.copy(
            transitions = current.transitions + (customTransitionKey(playlistId, outgoingId, incomingId) to transition),
        )
    }

    /**
     * What the controller plays for this pair: the editor's unsaved preview if it
     * is for exactly this pair (Mix need not be on yet), otherwise the saved
     * transition, and only while the playlist has Mix on.
     */
    fun effective(playlistId: String, outgoingId: String, incomingId: String): CustomTransition? {
        preview?.let { p ->
            if (p.key == customTransitionKey(playlistId, outgoingId, incomingId)) return p.transition
        }
        return if (isMixOn(playlistId)) get(playlistId, outgoingId, incomingId) else null
    }

    private class Preview(val key: String, val transition: CustomTransition)

    @Volatile
    private var preview: Preview? = null

    /** Preview in the editor: [transition] stands in for this pair until it is used or [clearPreview] runs. Memory only. */
    fun setPreview(playlistId: String, outgoingId: String, incomingId: String, transition: CustomTransition) {
        preview = Preview(customTransitionKey(playlistId, outgoingId, incomingId), transition)
    }

    fun clearPreview() {
        preview = null
    }

    /** Uses the preview up when the blend it was for begins; a blend for another pair leaves it. */
    fun consumePreview(playlistId: String, outgoingId: String, incomingId: String) {
        if (preview?.key == customTransitionKey(playlistId, outgoingId, incomingId)) preview = null
    }

    /** Back to the automatic plan for this pair. */
    fun reset(playlistId: String, outgoingId: String, incomingId: String) = update { current ->
        current.copy(transitions = current.transitions - customTransitionKey(playlistId, outgoingId, incomingId))
    }

    /** Replaces everything, as a backup restore does: replace, never merge. */
    fun replaceAll(snapshot: CustomTransitionSnapshot) = update { snapshot }

    private fun update(change: (CustomTransitionSnapshot) -> CustomTransitionSnapshot) {
        val next = change(_snapshot.value)
        _snapshot.value = next
        persist?.invoke(json.encodeToString(CustomTransitionSnapshot.serializer(), next))
    }
}
