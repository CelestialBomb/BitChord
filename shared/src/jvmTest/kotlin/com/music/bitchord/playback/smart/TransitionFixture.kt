package com.music.bitchord.playback.smart

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * JSON fixture model shared with the PC port (see docs/transitions-fixtures).
 * Deliberately tiny: analysis is expanded to a full [TrackAnalysis] here and in
 * the Rust runner, so a fixture stays readable instead of listing hundreds of
 * beat and energy samples.
 */
@Serializable
data class FixtureAnalysis(
    val duration: Double = 200.0,
    val bpm: Double = 120.0,
    val beatConfidence: Double = 0.9,
    val key: String = "",
    val keyConfidence: Double = 0.0,
    val vocal: Double = 0.0,
    /** First downbeat, matching the synthetic grid in PhraseBarAutomixTest. */
    val firstBeat: Double = 0.5,
    /** Empty means [TrackAnalysis.contentEndTime] = duration - 2. */
    val contentEndTime: Double = 0.0,
    /** Empty means a ready analysis; anything else is passed through as-is. */
    val status: String = TrackAnalysis.STATUS_READY,
)

@Serializable
data class FixtureTrack(
    val id: String = "",
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val analysis: FixtureAnalysis = FixtureAnalysis(),
)

@Serializable
data class FixtureSettings(
    val automix: Boolean = true,
    val beatAware: Boolean = false,
    val phraseAligned: Boolean = false,
    val tempoMatching: Boolean = true,
    val albumGapless: Boolean = false,
    val fallbackFadeSeconds: Double = 8.0,
    /** `MixStyle` key: smooth, fade, rise, quick. Omitted means smooth. */
    val mixStyle: String = "smooth",
    /** `BlendLength` key: short, medium, long. Omitted means medium. */
    val blendLength: String = "medium",
)

@Serializable
data class FixtureExpected(
    val reason: String = "",
    val blocked: Boolean = false,
    val transitionStyle: String = "EQUAL_POWER",
    val transitionStartSeconds: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val incomingCueSeconds: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    val outgoingPlaybackRate: Double = 1.0,
    val policyReasons: List<String> = emptyList(),
)

@Serializable
data class FixtureCase(
    val name: String,
    val settings: FixtureSettings = FixtureSettings(),
    val outgoing: FixtureTrack,
    val incoming: FixtureTrack,
    val positionSeconds: Double = 10.0,
    val expected: FixtureExpected = FixtureExpected(),
    /** The PC runner skips these until the Rust port covers the case. */
    val androidOnly: Boolean = false,
)

/** The analysis the fixture describes, with its synthetic steady grid expanded. */
fun FixtureAnalysis.toTrackAnalysis(trackId: String): TrackAnalysis {
    val beat = if (bpm > 0) 60.0 / bpm else 0.0
    val downbeats = if (beat > 0) {
        generateSequence(firstBeat) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
    } else {
        emptyList()
    }
    val energy = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) }
    return TrackAnalysis(
        status = status,
        trackId = trackId,
        duration = duration,
        bpm = bpm,
        beatInterval = beat,
        beatConfidence = beatConfidence,
        downbeats = downbeats,
        phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
        firstBeat = firstBeat,
        key = key,
        keyConfidence = keyConfidence,
        audibleStartTime = firstBeat,
        contentEndTime = contentEndTime.takeIf { it != 0.0 } ?: (duration - 2),
        mixInTime = downbeats.getOrElse(4) { firstBeat },
        energyCurve = energy,
        vocalProbability = vocal,
    )
}

fun FixtureTrack.toTransitionTrackInfo() =
    TransitionTrackInfo(id, durationMs, title, artist, album, albumId)

private fun Double.roundSix() = (this * 1_000_000).roundToLong() / 1_000_000.0

fun TransitionPlan.toExpected() = FixtureExpected(
    reason = reason,
    blocked = blocked,
    transitionStyle = transitionStyle.name,
    transitionStartSeconds = transitionStart.roundSix(),
    fadeSeconds = fadeSeconds.roundSix(),
    incomingCueSeconds = incomingCueTime.roundSix(),
    incomingPlaybackRate = incomingPlaybackRate.roundSix(),
    outgoingPlaybackRate = outgoingPlaybackRate.roundSix(),
    policyReasons = policyReasons,
)

val fixtureJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/** `docs/transitions-fixtures`; override with TRANSITIONS_FIXTURES_DIR. */
fun transitionFixturesDir(): File {
    System.getenv("TRANSITIONS_FIXTURES_DIR")?.let { return File(it) }
    // Resolve the repo root rather than guessing the test task's working dir.
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "docs/transitions-design.md").isFile) return File(dir, "docs/transitions-fixtures")
        dir = dir.parentFile ?: break
    }
    return File("../docs/transitions-fixtures")
}
