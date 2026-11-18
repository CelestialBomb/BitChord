package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.PHRASE_BARS_LONG
import com.music.bitchord.playback.smart.PHRASE_BARS_SHORT

/**
 * The listener-facing Beat-aware transitions settings, independent of Android
 * and Media3. Keeping this mapping here means the queue, player and settings UI
 * are the only places that need to know about the existing playback engine.
 *
 * Disabled is a strict no-op: every field in [optionsFor] keeps the v1.9.1
 * planner defaults, so existing Automix behaviour is byte-for-byte unchanged.
 *
 * Spec: docs/transitions-design.md (Android base: BitChord v1.9.1).
 */
data class BeatAwareConfig(
    val enabled: Boolean = false,
    val phraseAligned: Boolean = true,
    val tempoMatching: Boolean = true,
    val albumGapless: Boolean = true,
) {
    fun optionsFor(outgoingDurationSeconds: Double): PlannerOptions {
        if (!enabled) return PlannerOptions()
        return PlannerOptions(
            phraseBars = if (phraseAligned) {
                if (outgoingDurationSeconds >= LONG_PHRASE_MIN_SECONDS) PHRASE_BARS_LONG else PHRASE_BARS_SHORT
            } else {
                0
            },
            albumSequential = albumGapless,
            mixWindow = true,
            strictConfidence = true,
            allowTempoStretch = tempoMatching,
        )
    }

    companion object {
        /** Eight bars for shorter songs, sixteen once there is room for sixteen. */
        const val LONG_PHRASE_MIN_SECONDS = 240.0
    }
}

/** Arguments for `planTransition`, with defaults matching the untouched planner. */
data class PlannerOptions(
    val phraseBars: Int = 0,
    val albumSequential: Boolean = false,
    val mixWindow: Boolean = false,
    val strictConfidence: Boolean = false,
    val allowTempoStretch: Boolean = true,
)
