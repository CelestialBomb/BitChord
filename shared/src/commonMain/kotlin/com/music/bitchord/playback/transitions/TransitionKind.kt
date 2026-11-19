package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle

/**
 * The kinds of transition Automix makes, in one shape (brief section 6, stage
 * 3): what the failure record charges a pair when one fails, and what a later
 * Smart Automix will score side by side. Nothing scores them yet; the
 * controller's order (a loop mix when one can be made, else the planner's
 * plan) is unchanged.
 */
enum class TransitionKind(val failure: TransitionFailureLog.Kind) {
    /** Both songs loop instrumental bars while one tempo ramps between them. */
    LOOP_MIX(TransitionFailureLog.Kind.LOOP_MIX),

    /** A beat-matched (or beat-aligned) DJ blend, bass swap included. */
    BEAT_BLEND(TransitionFailureLog.Kind.BLEND),

    /** A filter sweep or a bass handoff over unmatched tempos. */
    FILTER_SWEEP(TransitionFailureLog.Kind.BLEND),

    /** The outgoing song echoes out on its last beat. */
    ECHO_OUT(TransitionFailureLog.Kind.BLEND),

    /** A near-seamless handoff: an album played through, or a custom gapless pair. */
    GAPLESS(TransitionFailureLog.Kind.PLAIN),

    /** An equal-power fade, at the planner's point or the listener's length. */
    CROSSFADE(TransitionFailureLog.Kind.PLAIN),
    ;

    companion object {
        /** What the planner's (restyled) [plan] plays as. */
        fun of(plan: TransitionPlan): TransitionKind = when (plan.transitionStyle) {
            TransitionStyle.GAPLESS -> GAPLESS
            TransitionStyle.EQUAL_POWER -> CROSSFADE
            TransitionStyle.DJ_FILTER -> when {
                plan.filterVariant == FilterTransitionVariant.ECHO_RIDE || plan.echoSeconds > 0 -> ECHO_OUT
                else -> FILTER_SWEEP
            }
            TransitionStyle.DJ_BLEND -> if (plan.echoSeconds > 0) ECHO_OUT else BEAT_BLEND
        }
    }
}
