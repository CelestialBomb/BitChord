package com.music.bitchord.playback.transitions

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Whether a pair gets a loop mix, and which: the loop mix's planning, apart
 * from its playing (the controller arms and drives the decks). Plans once
 * per pair and analysis, says why a pair gets none, and fetches the synced
 * lyrics that vouch for a loop being instrumental.
 */
internal class LoopMixPlanner(
    private val scope: CoroutineScope,
    /** A track's synced lyrics; null when it has none. */
    private val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>?,
    /** Whether a complete cached copy of a track is there to decode its loop regions from. */
    private val loopCopyReady: (MediaItem, Long) -> Boolean,
    private val loopPortOf: (ExoPlayer) -> LoopMixPort?,
    private val standby: () -> ExoPlayer,
) {
    /** The cached plan, and the pair (and analyses) it belongs to; null when none is possible. */
    var planned: LoopMix.Plan? = null
        private set
    private var planKey: String? = null

    /** Why the pair gets no loop mix, for the log and the stats line; null when it does. */
    var refusal: String? = null
        private set
    private var lastVerdict = ""

    /** Synced-lyric spans per media id, once asked: [LyricSpans.spans] null when the song has none. */
    private class LyricSpans(val spans: List<LoopMix.Span>?)

    private val lyricSpans = ConcurrentHashMap<String, LyricSpans>()
    private val lyricsAsked = ConcurrentHashMap.newKeySet<String>()
    private val copyChecked = HashMap<String, Pair<Boolean, Long>>()

    /** Whether [planned] is the plan for [currentId] -> [nextId]. */
    fun isPlannedFor(currentId: String, nextId: String): Boolean = planKey?.startsWith("$currentId->$nextId|") == true

    /**
     * The loop mix for [current] -> [next], planned once per pair and analysis,
     * or null when the pair gets none (see [refusal]).
     */
    fun planFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
        state: SmartAnalysis,
    ): LoopMix.Plan? {
        val pair = "${current.mediaId}->${next.mediaId}"
        refusalFor(player, current, next, state, durationMs, nextDurationMs)?.let { why ->
            note(pair, null, why)
            return null
        }
        askLyrics(current, durationMs)
        askLyrics(next, nextDurationMs)
        val outLyrics = lyricSpans[current.mediaId]
        val inLyrics = lyricSpans[next.mediaId]
        if (outLyrics == null || inLyrics == null) {
            note(pair, null, "waiting on lyrics")
            return null
        }
        val key = "$pair|${System.identityHashCode(currentAnalysis)}|${System.identityHashCode(nextAnalysis)}"
        val cached = planned
        // Kept while its loop is still far enough ahead to set up; a seek past
        // that plans again from the new playhead.
        if (key == planKey && (cached == null || cached.out.loopStart * 1000 - player.currentPosition >= MIN_LEAD_MS)) {
            return cached
        }
        val why = StringBuilder()
        val plan = LoopMix.plan(
            out = currentAnalysis,
            outDuration = durationMs / 1000.0,
            inn = nextAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = player.currentPosition / 1000.0 + SETUP_SECONDS,
            sungOut = outLyrics.spans,
            sungIn = inLyrics.spans,
            why = why,
        )
        planKey = key
        planned = plan
        val lyricsNote = "lyrics ${if (outLyrics.spans != null) "out" else "-"}/${if (inLyrics.spans != null) "in" else "-"}"
        note(pair, plan, if (plan == null) "$why; $lyricsNote" else null)
        return plan
    }

    /** No loop mix for [pair], for a reason the controller knows (the failure record). */
    fun refuse(pair: String, why: String) {
        planned = null
        planKey = null
        note(pair, null, why)
    }

    /** The planned loop mix could not be made after all: the pair takes the planner's blend. */
    fun fellBack(why: String) {
        planned = null
        refusal = why
    }

    /** Forgets the plan and the logged verdict (a queue change, Previous). */
    fun forget() {
        planned = null
        planKey = null
        refusal = null
        lastVerdict = ""
    }

    /** "out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars". */
    fun describe(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        val inPlays = if (plan.inRepeats) "" else " (plays through, its opening)"
        return "out ${side(plan.out)} · in ${side(plan.inn)}$inPlays · $tempo$fold"
    }

    private fun note(pair: String, plan: LoopMix.Plan?, why: String?) {
        refusal = why
        val verdict = "$pair: " + (plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${describe(it)}" } ?: "no loop mix ($why)")
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        Log.d(LOG_TAG, "loop $verdict")
    }

    /** Asks for [item]'s synced lyrics once; a loop they vouch for needs no vocal mask. */
    private fun askLyrics(item: MediaItem, durationMs: Long) {
        val id = item.mediaId
        if (!lyricsAsked.add(id)) return
        scope.launch(Dispatchers.IO) {
            val lines = runCatching { syncedLines(item, durationMs) }.getOrNull()
            // Only line-synced lyrics say where a voice is; plain text says nothing about time.
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(line.timeMs, end, blank = line.isGap || line.text.isBlank())
                    },
                )
            }
            lyricSpans[id] = LyricSpans(spans)
        }
    }

    /** Why [current] -> [next] can't be a loop mix right now, or null when it can. */
    private fun refusalFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        state: SmartAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
    ): String? = when {
        !LoopMixRenderer.available -> "no renderer"
        AppSettings.playbackSpeed.value != 1f -> "playback speed is not 1x"
        player.skipSilenceEnabled -> "skip silence is on"
        SleepTimer.afterTrack.value -> "sleep timer"
        state.current != TrackAnalysisState.ANALYSED || state.next != TrackAnalysisState.ANALYSED -> "waiting on analysis"
        isSourceBacked(current) || isSourceBacked(next) -> "analysed off another recording"
        // The decks are decoded from a complete cached copy of each song. A song seeked into, or
        // mid quality upgrade, may not have one yet: a wait, not a mix armed to fail ("the decks
        // would not build"), which would cost the pair its loop.
        !copyFor(current, durationMs) -> "waiting on ${current.mediaMetadata.title ?: "this song"}'s audio in the cache"
        !copyFor(next, nextDurationMs) -> "waiting on ${next.mediaMetadata.title ?: "the next song"}'s audio in the cache"
        // A sink that has not played yet (paused, a restored queue, players rebuilt after an output
        // change such as earbuds connecting) reads as never configured: a wait, not a limit.
        loopPortOf(player)?.let { it.sampleRate == 0 && !it.precision } == true -> "waiting on the audio output"
        loopPortOf(player)?.supportsLoopMix != true -> "this output can't take it (" +
            (loopPortOf(player)?.let { "precision=${it.precision} ch=${it.channelCount} rate=${it.sampleRate}" } ?: "no port") + ")"
        loopPortOf(standby()) == null -> "no standby sink"
        else -> null
    }

    /** [loopCopyReady], remembered for [COPY_RECHECK_MS] when it says no: it reads container headers. */
    private fun copyFor(item: MediaItem, durationMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        copyChecked[item.mediaId]?.let { (ready, at) ->
            if (ready || now - at < COPY_RECHECK_MS) return ready
        }
        val ready = loopCopyReady(item, durationMs)
        copyChecked[item.mediaId] = ready to now
        return ready
    }

    /** A track whose analysis reads a matched YouTube recording rather than what plays. */
    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    companion object {
        /** The loop verdicts have always logged under the controller's tag. */
        private const val LOG_TAG = "BitChordCrossfade"

        /** The earliest a loop may start ahead of the playhead: decks to build, lyrics to fetch. */
        const val SETUP_SECONDS = 15.0

        /** A cached plan whose loop is still at least this far ahead is kept. */
        const val MIN_LEAD_MS = 9_000L

        /** How long a "no complete cached copy" answer stands before it is asked again. */
        const val COPY_RECHECK_MS = 3_000L
    }
}
