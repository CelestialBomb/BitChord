package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.CustomTransitionStore
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.smart.AnalysisBridge
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.MixStyle
import com.music.bitchord.playback.transitions.MixWindow
import com.music.bitchord.playback.transitions.SECOND_CHOICES
import com.music.bitchord.playback.transitions.WindowSide
import com.music.bitchord.playback.transitions.WindowSource
import com.music.bitchord.playback.transitions.availableBars
import com.music.bitchord.playback.transitions.blendLengthSeconds
import com.music.bitchord.playback.transitions.defaultTransition
import com.music.bitchord.playback.transitions.endSeconds
import com.music.bitchord.playback.transitions.madeFor
import com.music.bitchord.playback.transitions.moveStart
import com.music.bitchord.playback.transitions.previewStartSeconds
import com.music.bitchord.playback.transitions.startSeconds
import com.music.bitchord.playback.transitions.toPeaks
import com.music.bitchord.playback.transitions.withBars
import com.music.bitchord.playback.transitions.withMode
import com.music.bitchord.playback.transitions.withSeconds
import com.music.bitchord.ui.player.TransitionDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Preview of a loop mix starts this far ahead: the decks take a second to build and the lyrics a few to arrive. */
private const val LOOP_PREVIEW_LEAD_SECONDS = 25.0

/** What the loop option needs to know about the two songs: whether it can be planned at all, and their tempos. */
private data class LoopInfo(val ready: Boolean = false, val outBpm: Double = 0.0, val inBpm: Double = 0.0)

/** What one end of the editor knows about its song's window. */
private sealed interface DeckState {
    data object Loading : DeckState
    class Ready(val window: MixWindow, val source: WindowSource) : DeckState
    data object WaitingForWifi : DeckState
    class Failed(val reason: String) : DeckState
}

@Composable
private fun rememberDeck(song: Song, side: WindowSide, attempt: Int): DeckState {
    var state by remember(song.videoId, side, attempt) { mutableStateOf<DeckState>(DeckState.Loading) }
    LaunchedEffect(song.videoId, side, attempt) {
        state = DeckState.Loading
        val result = AnalysisBridge.mixWindow(song, side)
        state = when {
            result == null -> DeckState.Failed("")
            result.window != null -> DeckState.Ready(result.window, result.source)
            result.source == WindowSource.WAIT_FOR_WIFI -> DeckState.WaitingForWifi
            else -> DeckState.Failed(result.reason)
        }
    }
    return state
}

/**
 * The editor for one pair of songs in one playlist (design doc 4.8): where the
 * blend starts (dragged on the outgoing song's last 45 s, snapping to beats),
 * its style and length, bass swap, an exit filter, or "don't mix this pair".
 *
 * Waveforms and beats come from two 45 s windows, never whole songs; if they
 * cannot be had the editor still works (free start, seconds) and offers to
 * download the songs. Preview plays the draft for real; Save keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTransitionEditor(
    playlistId: String,
    outgoing: Song,
    incoming: Song,
    automixOn: Boolean,
    onPreview: (draft: CustomTransition, startSeconds: Double) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val outMs = outgoing.durationMillis()
    val inMs = incoming.durationMillis()
    val duration = outMs / 1000.0
    val saved = remember(playlistId, outgoing.videoId, incoming.videoId) {
        CustomTransitionStore.get(playlistId, outgoing.videoId, incoming.videoId)
    }

    var attempt by remember { mutableIntStateOf(0) }
    val tail = rememberDeck(outgoing, WindowSide.TAIL, attempt)
    val head = rememberDeck(incoming, WindowSide.HEAD, attempt)
    val tailWindow = (tail as? DeckState.Ready)?.window
    val headWindow = (head as? DeckState.Ready)?.window
    // Beats count only when the grid can be trusted; an unreliable one is neither drawn nor snapped to.
    val beats = tailWindow?.takeIf { it.hasTrustedGrid }?.beats.orEmpty()
    val beatSeconds = tailWindow?.takeIf { it.hasTrustedGrid }?.let { 60.0 / it.bpm } ?: 0.0

    // The loop mix is planned from each song's whole-track analysis (the vocal mask), which the 45 s windows lack.
    val analysisVersion by AnalysisBridge.version.collectAsStateWithLifecycle()
    var loopInfo by remember { mutableStateOf(LoopInfo()) }
    LaunchedEffect(outgoing.videoId, incoming.videoId, analysisVersion) {
        loopInfo = withContext(Dispatchers.IO) {
            LoopInfo(
                ready = AnalysisBridge.hasLoopAnalysis(outgoing) && AnalysisBridge.hasLoopAnalysis(incoming),
                outBpm = AnalysisBridge.traitsOf(outgoing)?.bpm ?: 0.0,
                inBpm = AnalysisBridge.traitsOf(incoming)?.bpm ?: 0.0,
            )
        }
    }

    var touched by remember { mutableStateOf(saved != null) }
    var draft by remember { mutableStateOf(saved ?: defaultTransition(outMs, inMs, 0.0)) }
    // Until the listener touches anything, the first draft follows what the song's end turns out to be.
    LaunchedEffect(tailWindow) {
        if (!touched) draft = defaultTransition(outMs, inMs, beatSeconds, beats)
    }
    // A saved length that no longer exists (a grid that is gone) falls to the nearest one offered.
    LaunchedEffect(beatSeconds) {
        if (beatSeconds > 0 && draft.bars !in availableBars(beatSeconds)) {
            availableBars(beatSeconds).lastOrNull()?.let { draft = withBars(draft, it, duration, beatSeconds, beats) }
        }
    }
    fun edit(change: (CustomTransition) -> CustomTransition) {
        touched = true
        draft = change(draft)
    }

    val start = startSeconds(draft, duration)
    // A loop mix's length is the whole mix, which depends on the two tempos; the frame shows that.
    val ramped = LoopMix.estimate(loopInfo.outBpm, loopInfo.inBpm, draft.loopBars, holdTempo = false)
    val loopEstimate = if (draft.isLoopMix) {
        LoopMix.estimate(loopInfo.outBpm, loopInfo.inBpm, draft.loopBars, holdTempo = !draft.loopRamp)
    } else {
        null
    }
    val end = if (loopEstimate != null) minOf(duration, start + loopEstimate.totalSeconds) else endSeconds(draft, duration, beatSeconds)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleLarge)
            Text(
                "${outgoing.title} → ${incoming.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (!automixOn) {
                Text(stringResource(R.string.mix_needs_automix), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // What the pair does: mix it, play straight on, or cut.
            val modes = listOf(CustomTransition.MODE_MIX, CustomTransition.MODE_GAPLESS, CustomTransition.MODE_NO_MIX)
            SegmentedControl(
                options = listOf(stringResource(R.string.mix_mode_mix), stringResource(R.string.mix_transition_gapless), stringResource(R.string.mix_transition_no_mix)),
                selectedIndex = modes.indexOf(draft.mode).coerceAtLeast(0),
                onSelect = { edit { d -> withMode(d, modes[it]) } },
            )

            if (draft.mode == CustomTransition.MODE_MIX) {
                Text(stringResource(R.string.editor_end_of, outgoing.title), style = MaterialTheme.typography.labelMedium)
                DeckStatus(tail, onRetry = { attempt += 1 }, onDownload = onDownload)
                TransitionDeck(
                    low = tailWindow?.low?.toPeaks() ?: FloatArray(0),
                    mid = tailWindow?.mid?.toPeaks() ?: FloatArray(0),
                    high = tailWindow?.high?.toPeaks() ?: FloatArray(0),
                    fromSeconds = tailWindow?.fromSeconds ?: maxOf(0.0, duration - 45.0),
                    toSeconds = tailWindow?.toSeconds ?: duration,
                    beats = beats,
                    regionStart = start,
                    regionEnd = end,
                    // Dragging works with or without a waveform: a window that could not be read still leaves the start free.
                    onRegionStart = { requested -> edit { d -> moveStart(d, requested, duration, beatSeconds, beats) } },
                )
                Text(
                    stringResource(if (beatSeconds > 0) R.string.editor_drag_hint else R.string.editor_no_grid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(stringResource(R.string.editor_start_of, incoming.title), style = MaterialTheme.typography.labelMedium)
                DeckStatus(head, onRetry = { attempt += 1 }, onDownload = onDownload, quiet = true)
                TransitionDeck(
                    low = headWindow?.low?.toPeaks() ?: FloatArray(0),
                    mid = headWindow?.mid?.toPeaks() ?: FloatArray(0),
                    high = headWindow?.high?.toPeaks() ?: FloatArray(0),
                    fromSeconds = headWindow?.fromSeconds ?: 0.0,
                    toSeconds = headWindow?.toSeconds ?: 45.0,
                    beats = headWindow?.takeIf { it.hasTrustedGrid }?.beats.orEmpty(),
                    regionStart = null,
                    regionEnd = null,
                    onRegionStart = null,
                )

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.editor_loop), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = draft.loop,
                        enabled = loopInfo.ready || draft.loop,
                        onCheckedChange = { on -> edit { d -> d.copy(loop = on) } },
                    )
                }
                if (!loopInfo.ready) {
                    Text(stringResource(R.string.editor_loop_needs_play), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (draft.loop) {
                    Text(stringResource(R.string.editor_loop_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.editor_loop_length), style = MaterialTheme.typography.labelLarge)
                    val choices = CustomTransition.LOOP_BARS_CHOICES
                    SegmentedControl(
                        options = choices.map { if (it == 1) stringResource(R.string.mix_loop_one_bar) else stringResource(R.string.mix_bars, it) },
                        selectedIndex = choices.indexOf(draft.loopBars).coerceAtLeast(0),
                        onSelect = { edit { d -> d.copy(loopBars = choices[it]) } },
                    )
                    // Holding the tempo is only possible when the songs are close; further apart the ramp stays.
                    val locked = ramped != null && ramped.gapBpm > LoopMix.HOLD_TEMPO_MAX_BPM
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.editor_tempo_ramp), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = draft.loopRamp || locked,
                            enabled = !locked,
                            onCheckedChange = { on -> edit { d -> d.copy(loopRamp = on) } },
                        )
                    }
                    if (loopEstimate != null) {
                        val from = loopInfo.outBpm.roundToInt()
                        val to = loopEstimate.targetBpm.roundToInt()
                        Text(
                            when {
                                locked -> stringResource(R.string.editor_ramp_locked, loopEstimate.gapBpm.roundToInt())
                                loopEstimate.held -> stringResource(R.string.editor_ramp_off, from, to)
                                else -> stringResource(R.string.editor_ramp_on, from, to, loopEstimate.rampBars)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (start + loopEstimate.handoverSeconds + LoopMix.OUT_END_GUARD_SEC > duration) {
                            Text(stringResource(R.string.editor_loop_tight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (!draft.isLoopMix) {
                Text(stringResource(R.string.editor_style), style = MaterialTheme.typography.labelLarge)
                SegmentedControl(
                    options = MixStyle.entries.map { stringResource(styleLabel(it)) },
                    selectedIndex = MixStyle.entries.indexOf(draft.mixStyle),
                    onSelect = { edit { d -> d.copy(style = MixStyle.entries[it].key) } },
                )

                Text(stringResource(R.string.editor_length), style = MaterialTheme.typography.labelLarge)
                if (beatSeconds > 0) {
                    val bars = availableBars(beatSeconds)
                    SegmentedControl(
                        options = bars.map { stringResource(R.string.mix_bars, it) },
                        selectedIndex = bars.indexOf(draft.bars).coerceAtLeast(0),
                        onSelect = { edit { d -> withBars(d, bars[it], duration, beatSeconds, beats) } },
                    )
                } else {
                    SegmentedControl(
                        options = SECOND_CHOICES.map { stringResource(R.string.mix_seconds, it.roundToInt()) },
                        selectedIndex = SECOND_CHOICES.indexOfFirst { it == draft.seconds }.coerceAtLeast(0),
                        onSelect = { edit { d -> withSeconds(d, SECOND_CHOICES[it], duration) } },
                    )
                }

                if (beatSeconds > 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.editor_bass_swap), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = draft.bassSwap, onCheckedChange = { on -> edit { d -> d.copy(bassSwap = on) } })
                    }
                }

                Text(stringResource(R.string.editor_filter), style = MaterialTheme.typography.labelLarge)
                val filters = listOf(CustomTransition.FILTER_NONE, CustomTransition.FILTER_LOW_PASS, CustomTransition.FILTER_HIGH_PASS)
                SegmentedControl(
                    options = listOf(stringResource(R.string.editor_filter_none), stringResource(R.string.editor_filter_low), stringResource(R.string.editor_filter_high)),
                    selectedIndex = filters.indexOf(draft.filter).coerceAtLeast(0),
                    onSelect = { edit { d -> d.copy(filter = filters[it]) } },
                )
                if (draft.filter != CustomTransition.FILTER_NONE) {
                    Text(stringResource(R.string.editor_filter_amount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = draft.filterAmount.toFloat(),
                        onValueChange = { v -> edit { d -> d.copy(filterAmount = v.toDouble()) } },
                        valueRange = 0.2f..1f,
                    )
                }
                }
            } else {
                Text(
                    stringResource(if (draft.mode == CustomTransition.MODE_GAPLESS) R.string.editor_gapless_note else R.string.editor_no_mix_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                if (saved != null) {
                    TextButton(onClick = {
                        CustomTransitionStore.reset(playlistId, outgoing.videoId, incoming.videoId)
                        onDismiss()
                    }) { Text(stringResource(R.string.editor_reset)) }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        onPreview(
                            finalDraft(draft, beatSeconds, outMs, inMs),
                            previewStartSeconds(draft, duration, if (draft.isLoopMix) LOOP_PREVIEW_LEAD_SECONDS else 8.0),
                        )
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.editor_preview)) }
                Button(onClick = {
                    CustomTransitionStore.save(playlistId, outgoing.videoId, incoming.videoId, finalDraft(draft, beatSeconds, outMs, inMs))
                    onDismiss()
                }) { Text(stringResource(R.string.editor_save)) }
        }
        }
    }
}

/**
 * What is saved or previewed: the draft stamped with the durations it was made for, and with
 * its length in seconds written alongside the bars so the same blend plays if the grid is
 * not trusted when the pair comes round.
 */
private fun finalDraft(draft: CustomTransition, beatSeconds: Double, outMs: Long, inMs: Long): CustomTransition =
    draft.madeFor(outMs, inMs).let { d ->
        if (beatSeconds > 0) d.copy(seconds = blendLengthSeconds(d, beatSeconds), beatSeconds = beatSeconds) else d.copy(beatSeconds = 0.0)
    }

private fun styleLabel(style: MixStyle): Int = when (style) {
    MixStyle.SMOOTH -> R.string.automix_style_smooth
    MixStyle.FADE -> R.string.automix_style_fade
    MixStyle.RISE -> R.string.automix_style_rise
    MixStyle.QUICK -> R.string.automix_style_quick
}

/** The line above a deck saying what is happening to its waveform, with the way out when it cannot be had. */
@Composable
private fun DeckStatus(state: DeckState, onRetry: () -> Unit, onDownload: () -> Unit, quiet: Boolean = false) {
    when (state) {
        DeckState.Loading -> Text(stringResource(R.string.editor_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is DeckState.Ready -> Unit
        DeckState.WaitingForWifi -> Column {
            Text(stringResource(R.string.editor_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!quiet) TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_retry)) }
        }
        is DeckState.Failed -> Column {
            Text(
                stringResource(R.string.editor_failed, state.reason.ifBlank { "—" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!quiet) {
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_retry)) }
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.editor_download)) }
                }
            }
        }
    }
}
