package com.music.bitchord.playback.transitions

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.TrackAnalysis

/**
 * Mix on skip (design doc 4.7): what a forward skip does, and the skip blend
 * in flight. The rules are [decideSkip]; this holds the state they need (the
 * last press, for the rapid-skip cut; the decision being carried out and when
 * it was pressed) and logs one `skip <reason>` line per press. The controller
 * arms and drives the blend.
 */
internal class SkipBlender {

    /** The Mix on skip blend in flight, or null when the transition (if any) is an ordinary one. */
    var pending: SkipDecision? = null
        private set

    private var pressedAt = 0L

    /** The last forward skip, for the rapid-skip rule; 0 when there has been none. */
    private var lastForwardSkipAt = 0L

    /** Decides a forward skip pressed at [nowMs]; [guard] is why it can't blend, or null. */
    fun decide(
        nowMs: Long,
        guard: String?,
        transitioning: Boolean,
        positionMs: Long,
        durationMs: Long,
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        incomingDurationMs: Long,
        pair: String,
        phase: Any,
    ): SkipDecision {
        val since = lastForwardSkipAt.takeIf { it != 0L }?.let { nowMs - it }
        lastForwardSkipAt = nowMs
        val decision = decideSkip(
            SkipContext(
                enabled = AppSettings.mixOnSkip.value,
                automix = AppSettings.smartFadeEnabled.value,
                guard = guard,
                transitioning = transitioning,
                msSinceLastSkip = since,
                positionSeconds = positionMs / 1000.0,
                durationSeconds = durationMs / 1000.0,
                outgoing = outgoing,
                incoming = incoming,
                incomingDurationSeconds = incomingDurationMs / 1000.0,
                style = AppSettings.automixStyle.value,
                length = AppSettings.automixBlendLength.value,
            ),
        )
        TransitionLog.d(
            "skip ${decision.reason} $pair at ${positionMs}ms" +
                if (decision.action == SkipAction.CUT) {
                    " (phase=$phase${guard?.let { ", $it" }.orEmpty()})"
                } else {
                    ": fade=${decision.fadeMs}ms wait=${decision.startDelayMs}ms cue=${decision.incomingCueMs}ms " +
                        "style=${decision.transitionStyle}/${decision.filterVariant} echo=${decision.echoSeconds} " +
                        "beat=${decision.beatSeconds}"
                },
        )
        return decision
    }

    /**
     * False for the fade a failed loop mix continues into the incoming song
     * with: armed the same way, but the listener skipped nothing, so it is
     * booked as the song ending and a fade that can't start skips nothing.
     */
    var isSkip: Boolean = true
        private set

    /** A skip blend (or, with [asSkip] false, a loop's continue fade) is being armed for [decision] at [nowMs]. */
    fun start(decision: SkipDecision, nowMs: Long, asSkip: Boolean = true) {
        pending = decision
        pressedAt = nowMs
        isSkip = asSkip
    }

    /** How long ago the blend in flight was asked for. */
    fun waitedMs(nowMs: Long): Long = nowMs - pressedAt

    /** The next song was not ready in time: a quick plain fade once it is (`skip-fade`). */
    fun fallBack(): SkipDecision = skipFallback(checkNotNull(pending)).also { pending = it }

    /** The transition is over, however it ended. */
    fun end() {
        pending = null
    }
}
