package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.FILTER_SWEEP
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_ECHO_SECONDS
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.echoTailSeconds
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Mix on skip (design doc section 4.6): what a forward skip the listener asked
 * for does. Pure, so both apps can share the rules and the tests.
 */
enum class SkipAction {
    /** The normal instant skip. */
    CUT,

    /** A short equal-power fade with no beat timing: the fallback when the next song is slow to load. */
    PLAIN_FADE,

    /** A short styled blend into the next song's entry point, on the beat when one is known. */
    BLEND,
}

data class SkipDecision(
    val action: SkipAction,
    /** Reason code: skip-blend, skip-fade, skip-cut-off, skip-cut-guard, skip-cut-busy, skip-cut-rapid. */
    val reason: String,
    val fadeSeconds: Double = 0.0,
    /** Wait for the outgoing track's next beat; never more than [SKIP_MAX_BEAT_WAIT_SECONDS]. */
    val startDelaySeconds: Double = 0.0,
    /** Where the incoming track starts: after its silent intro, at most 25 % in. */
    val incomingCueSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
    val filterSweep: Double = 0.0,
    val echoSeconds: Double = 0.0,
    /** The outgoing beat in seconds, 0 when the grid is not trusted. */
    val beatSeconds: Double = 0.0,
) {
    val fadeMs: Long get() = (fadeSeconds * 1000).toLong()
    val startDelayMs: Long get() = (startDelaySeconds * 1000).toLong()
    val incomingCueMs: Long get() = (incomingCueSeconds * 1000).toLong()
}

/** Everything [decideSkip] reads. The app fills it in at the moment of the skip. */
data class SkipContext(
    /** The "Mix on skip" setting. */
    val enabled: Boolean,
    /** Automix itself; Mix on skip sits under it. */
    val automix: Boolean,
    /** Why the app can't blend at all right now (party, bit-perfect, cast, video, paused, ...), or null. */
    val guard: String? = null,
    /** A blend of any kind is armed or running. */
    val transitioning: Boolean = false,
    /** Since the last forward skip, or null when there has been none. */
    val msSinceLastSkip: Long? = null,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val outgoing: TrackAnalysis = TrackAnalysis(),
    val incoming: TrackAnalysis = TrackAnalysis(),
    /** 0 when unknown; then the analysis's own duration is used. */
    val incomingDurationSeconds: Double = 0.0,
    val style: MixStyle = MixStyle.SMOOTH,
    val length: BlendLength = BlendLength.MEDIUM,
)

/** A second skip inside this cuts straight through: rapid skipping never stacks blends. */
const val SKIP_RAPID_MS = 1_500L

/** Less than this left of the outgoing track: just skip. */
const val SKIP_MIN_REMAINING_SECONDS = 5.0

/** The longest wait for the outgoing track's next beat. */
const val SKIP_MAX_BEAT_WAIT_SECONDS = 0.5

/** Beat timing is used only at or above this confidence (the stretch threshold, R6c). */
const val SKIP_MIN_BEAT_CONFIDENCE = 0.55

/** The incoming track is never cued further in than this share of it (design doc section 3). */
const val SKIP_MAX_CUE_SHARE = 0.25

/** How long the controller waits for the incoming player before falling back to [SkipAction.PLAIN_FADE]. */
const val SKIP_READY_WAIT_MS = 600L

/** How long it keeps waiting, the old song still playing, before giving up and skipping normally. */
const val SKIP_GIVE_UP_MS = 2_500L

/** The fallback fade's length. */
const val SKIP_FALLBACK_FADE_SECONDS = 1.0

fun decideSkip(context: SkipContext): SkipDecision {
    if (!context.enabled || !context.automix) return SkipDecision(SkipAction.CUT, "skip-cut-off")
    if (context.guard != null) return SkipDecision(SkipAction.CUT, "skip-cut-guard")
    if (context.transitioning) return SkipDecision(SkipAction.CUT, "skip-cut-busy")
    val since = context.msSinceLastSkip
    if (since != null && since < SKIP_RAPID_MS) return SkipDecision(SkipAction.CUT, "skip-cut-rapid")
    val remaining = context.durationSeconds - context.positionSeconds
    if (context.durationSeconds <= 0 || remaining < SKIP_MIN_REMAINING_SECONDS) {
        return SkipDecision(SkipAction.CUT, "skip-cut-guard")
    }

    val beat = trustedBeatSeconds(context.outgoing)
    val delay = if (beat > 0) nextBeatDelay(context.outgoing, beat, context.positionSeconds) else 0.0
    // The blend and its echo tail both fit in what is left of the outgoing track.
    val fade = min(context.length.skipSeconds, remaining - delay - 0.5)
    val cue = entryCue(context.incoming, context.incomingDurationSeconds)

    val base = SkipDecision(
        action = SkipAction.BLEND,
        reason = "skip-blend",
        fadeSeconds = fade,
        startDelaySeconds = delay,
        incomingCueSeconds = cue,
        beatSeconds = beat,
    )
    return when {
        context.style == MixStyle.FADE -> base
        context.style == MixStyle.RISE || beat <= 0 -> base.copy(
            transitionStyle = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.SWEEP,
            filterSweep = FILTER_SWEEP,
        )
        else -> {
            // Smooth and Quick, on a trusted beat: the outgoing song echoes out.
            // The renderer needs two beats of grid in the blend, and the repeats
            // need outgoing audio after it, which mid-song there always is.
            val echo = min(beat, MAX_ECHO_SECONDS)
            val echoFits = fade / beat >= 2.0 && remaining - delay - fade >= echoTailSeconds(echo)
            base.copy(
                transitionStyle = TransitionStyle.DJ_FILTER,
                filterVariant = if (echoFits) FilterTransitionVariant.ECHO_RIDE else FilterTransitionVariant.SWEEP,
                filterSweep = FILTER_SWEEP,
                echoSeconds = if (echoFits) echo else 0.0,
            )
        }
    }
}

/** [decision], downgraded because the incoming player was not ready in time. */
fun skipFallback(decision: SkipDecision): SkipDecision = decision.copy(
    action = SkipAction.PLAIN_FADE,
    reason = "skip-fade",
    fadeSeconds = min(decision.fadeSeconds, SKIP_FALLBACK_FADE_SECONDS),
    startDelaySeconds = 0.0,
    transitionStyle = TransitionStyle.EQUAL_POWER,
    filterVariant = FilterTransitionVariant.SWEEP,
    filterSweep = 0.0,
    echoSeconds = 0.0,
)

/** One beat of [analysis]'s grid when it can be trusted, otherwise 0. */
internal fun trustedBeatSeconds(analysis: TrackAnalysis): Double {
    if (!analysis.isUsable || analysis.beatConfidence < SKIP_MIN_BEAT_CONFIDENCE) return 0.0
    if (analysis.bpm !in 40.0..220.0) return 0.0
    val fromTempo = 60.0 / analysis.bpm
    // The tempo is the authority; a stored interval that disagrees with it by more than a quarter is not a beat.
    val interval = analysis.beatInterval
    return if (interval > 0 && kotlin.math.abs(interval - fromTempo) <= fromTempo * 0.25) interval else fromTempo
}

/** Seconds to the next beat after [position], or 0 when that is more than [SKIP_MAX_BEAT_WAIT_SECONDS] away. */
private fun nextBeatDelay(analysis: TrackAnalysis, beat: Double, position: Double): Double {
    val anchor = analysis.downbeats.lastOrNull { it <= position } ?: analysis.firstBeat
    val beatsPast = (position - anchor) / beat
    val next = anchor + ceil(beatsPast - 1e-9) * beat
    val delay = max(0.0, next - position)
    return if (delay <= SKIP_MAX_BEAT_WAIT_SECONDS && delay >= 0.02) delay else 0.0
}

/** After the incoming track's silent intro, never more than [SKIP_MAX_CUE_SHARE] into it; 0 without analysis. */
private fun entryCue(incoming: TrackAnalysis, durationSeconds: Double): Double {
    if (!incoming.isUsable) return 0.0
    val duration = durationSeconds.takeIf { it > 0 } ?: incoming.duration
    if (duration <= 0) return 0.0
    return (incoming.audibleStartTime ?: 0.0).coerceIn(0.0, duration * SKIP_MAX_CUE_SHARE)
}
