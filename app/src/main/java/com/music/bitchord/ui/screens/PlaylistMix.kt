package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.CustomTransitionStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AnalysisBridge
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.MixStyle
import com.music.bitchord.playback.transitions.orphanedPairKeys
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** How a transition row reads: what the pair will do, in the words the row shows. */
data class TransitionSummary(val kind: Kind, val style: MixStyle = MixStyle.SMOOTH, val bars: Int = 0, val seconds: Int = 0) {
    enum class Kind { AUTO, MIX, LOOP, GAPLESS, NO_MIX }

    companion object {
        /** No saved transition means the automatic plan. */
        fun of(custom: CustomTransition?): TransitionSummary = when (custom?.mode) {
            null -> TransitionSummary(Kind.AUTO)
            CustomTransition.MODE_GAPLESS -> TransitionSummary(Kind.GAPLESS)
            CustomTransition.MODE_NO_MIX -> TransitionSummary(Kind.NO_MIX)
            else -> if (custom.isLoopMix) {
                TransitionSummary(Kind.LOOP, bars = custom.loopBars)
            } else {
                TransitionSummary(Kind.MIX, custom.mixStyle, custom.bars, custom.seconds.roundToInt())
            }
        }
    }
}

/** "124 BPM · C major", or just the tempo, or null when nothing is known. */
fun traitsLine(traits: AnalysisBridge.Traits?, bpmFormat: (Int) -> String): String? {
    if (traits == null || traits.bpm <= 0) return null
    return listOfNotNull(bpmFormat(traits.bpm.roundToInt()), traits.key.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/**
 * What the playlist page needs to offer Mix: the toggle, tempo and key on rows,
 * the transition between rows, and Analyse playlist. Null for anything that is
 * not a playlist (albums, artists, folders).
 */
class PlaylistMixState(
    val enabled: Boolean,
    val automixOn: Boolean,
    /** The app's shuffle is on: songs rarely come in the playlist's order, so saved pairs rarely play. */
    val shuffleOn: Boolean,
    val onToggle: (Boolean) -> Unit,
    val traitsLine: (Song) -> String?,
    val summary: (Song, Song) -> TransitionSummary,
    /** Opens the editor for a pair; null while there is none, which leaves the rows as plain labels. */
    val onOpenTransition: ((Song, Song) -> Unit)?,
    val analysing: Boolean,
    val message: String?,
    val onAnalyse: () -> Unit,
    /** Saved pairs whose songs are on this page but no longer follow one another, and the way to drop them. */
    val orphans: Int = 0,
    val onRemoveOrphans: () -> Unit = {},
)

@Composable
fun rememberPlaylistMix(
    page: DetailPage,
    isFolder: Boolean,
    onOpenTransition: ((Song, Song) -> Unit)? = null,
): PlaylistMixState? {
    if (page.type != BrowseType.PLAYLIST || isFolder) return null
    val context = LocalContext.current
    val snapshot by CustomTransitionStore.snapshot.collectAsStateWithLifecycle()
    val version by AnalysisBridge.version.collectAsStateWithLifecycle()
    val automixOn by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val shuffleOn by AppSettings.shuffleEnabled.collectAsStateWithLifecycle()
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    val enabled = CustomTransitionStore.playlistKey(page.browseId) in snapshot.mixPlaylists

    // Tempo and key per song id, filled in from what the analyzer has or has stored. Only
    // songs without an answer are asked again, so a long playlist is not re-read on every tick.
    var traits by remember(page.browseId) { mutableStateOf(emptyMap<String, AnalysisBridge.Traits>()) }
    LaunchedEffect(page.browseId, enabled, songs, version) {
        if (!enabled) return@LaunchedEffect
        val missing = songs.filter { it.videoId !in traits }
        if (missing.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            missing.mapNotNull { song -> AnalysisBridge.traitsOf(song)?.let { song.videoId to it } }.toMap()
        }
        if (found.isNotEmpty()) traits = traits + found
    }

    // What the page reads, once per page and change: the playlist id it keys on and how many saved pairs it can show.
    LaunchedEffect(page.browseId, songs.size, snapshot) {
        if (enabled && songs.isNotEmpty()) {
            val shown = songs.zipWithNext().count { (a, b) -> CustomTransitionStore.get(page.browseId, a.videoId, b.videoId) != null }
            val key = CustomTransitionStore.playlistKey(page.browseId)
            val saved = snapshot.transitions.keys.count { it.startsWith("$key|") }
            Log.d("BitChordMix", "page ${page.browseId} -> key $key: ${songs.size} songs, $saved saved pairs, $shown shown")
        }
    }

    val orphanKeys = remember(snapshot, songs, page.browseId) {
        if (enabled) orphanedPairKeys(snapshot, CustomTransitionStore.playlistKey(page.browseId), songs.map { it.videoId }) else emptyList()
    }

    var analysing by remember(page.browseId) { mutableStateOf(false) }
    var message by remember(page.browseId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    return PlaylistMixState(
        enabled = enabled,
        automixOn = automixOn,
        shuffleOn = shuffleOn,
        onToggle = { CustomTransitionStore.setMix(page.browseId, it) },
        traitsLine = { song -> traitsLine(traits[song.videoId]) { context.getString(R.string.mix_bpm, it) } },
        summary = { out, into ->
            TransitionSummary.of(CustomTransitionStore.get(page.browseId, out.videoId, into.videoId))
        },
        onOpenTransition = onOpenTransition,
        orphans = orphanKeys.size,
        onRemoveOrphans = { CustomTransitionStore.removeKeys(orphanKeys) },
        analysing = analysing,
        message = message,
        onAnalyse = {
            if (!analysing) {
                analysing = true
                message = null
                scope.launch {
                    val outcome = AnalysisBridge.analysePlaylist(songs)
                    message = when (outcome) {
                        AnalysisBridge.Outcome.NeedsWifi -> context.getString(R.string.mix_needs_wifi)
                        AnalysisBridge.Outcome.NotReady -> context.getString(R.string.mix_not_ready)
                        is AnalysisBridge.Outcome.Done -> context.getString(R.string.mix_analysed, outcome.analysed, outcome.total)
                    }
                    analysing = false
                }
            }
        },
    )
}

/** The Mix switch under the playlist header, with Analyse playlist once it is on. */
@Composable
fun MixHeaderRow(mix: PlaylistMixState, palette: ArtworkPalette, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mix_title), style = MaterialTheme.typography.titleMedium, color = palette.onBackground)
                Text(
                    stringResource(R.string.mix_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                )
            }
            Switch(
                checked = mix.enabled,
                onCheckedChange = mix.onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent),
            )
        }
        if (mix.enabled) {
            if (!mix.automixOn) {
                Text(
                    stringResource(R.string.mix_needs_automix),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (mix.shuffleOn) {
                Text(
                    stringResource(R.string.mix_shuffle_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (mix.orphans > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.mix_orphans, mix.orphans, mix.orphans),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onBackgroundVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = mix.onRemoveOrphans) {
                        Text(stringResource(R.string.mix_orphans_remove), color = palette.accent)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = mix.onAnalyse, enabled = !mix.analysing, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        stringResource(if (mix.analysing) R.string.mix_analysing else R.string.mix_analyse),
                        color = palette.accent,
                    )
                }
            }
            mix.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.onBackgroundVariant)
            }
        }
    }
}

/** The tappable line between two songs: what their transition will be. */
@Composable
fun TransitionRow(summary: TransitionSummary, palette: ArtworkPalette, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val text = when (summary.kind) {
        TransitionSummary.Kind.AUTO -> stringResource(R.string.mix_transition_auto)
        TransitionSummary.Kind.GAPLESS -> stringResource(R.string.mix_transition_gapless)
        TransitionSummary.Kind.NO_MIX -> stringResource(R.string.mix_transition_no_mix)
        TransitionSummary.Kind.LOOP -> stringResource(
            R.string.mix_transition_loop,
            if (summary.bars == 1) stringResource(R.string.mix_loop_one_bar) else stringResource(R.string.mix_bars, summary.bars),
        )
        TransitionSummary.Kind.MIX -> {
            val length = if (summary.bars > 0) {
                stringResource(R.string.mix_bars, summary.bars)
            } else {
                stringResource(R.string.mix_seconds, summary.seconds)
            }
            stringResource(R.string.mix_transition_custom, summary.style.label(), length)
        }
    }
    val tint = if (summary.kind == TransitionSummary.Kind.AUTO) palette.onBackgroundVariant else palette.accent
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⇅  $text", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun MixStyle.label(): String = stringResource(
    when (this) {
        MixStyle.SMOOTH -> R.string.automix_style_smooth
        MixStyle.FADE -> R.string.automix_style_fade
        MixStyle.RISE -> R.string.automix_style_rise
        MixStyle.QUICK -> R.string.automix_style_quick
    },
)
