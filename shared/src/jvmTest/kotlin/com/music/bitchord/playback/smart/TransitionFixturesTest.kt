package com.music.bitchord.playback.smart

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

/**
 * Records and verifies the planner's answers on the existing v1.9.1 code.
 *
 * Run with TRANSITIONS_RECORD=1 to rewrite the JSON fixtures from the current
 * current planner; otherwise this asserts them. Nothing in the planner is
 * changed by recording. The JSON is the same shape the PC fork reads, so both
 * apps can be compared on identical inputs.
 */
class TransitionFixturesTest {

    private fun track(id: String, bpm: Double, duration: Double = 200.0) = FixtureTrack(
        id = id,
        durationMs = (duration * 1000).toLong(),
        analysis = FixtureAnalysis(duration = duration, bpm = bpm),
    )

    private val cases = linkedMapOf(
        "001-matched-tempo" to FixtureCase(
            name = "Two confident tracks at the same tempo",
            outgoing = track("a", 120.0),
            incoming = track("b", 120.0),
        ),
        "002-tempo-distance" to FixtureCase(
            name = "Tempi too far apart for the transparent stretch window",
            outgoing = track("a", 120.0),
            incoming = track("b", 170.0),
        ),
        "003-incoming-low-confidence" to FixtureCase(
            name = "Only the incoming grid is weak",
            outgoing = track("a", 120.0),
            incoming = track("b", 120.0).let {
                it.copy(analysis = it.analysis.copy(beatConfidence = 0.1))
            },
        ),
        "004-both-low-confidence" to FixtureCase(
            name = "Neither grid can be trusted at all",
            outgoing = track("a", 120.0).let { it.copy(analysis = it.analysis.copy(beatConfidence = 0.1)) },
            incoming = track("b", 120.0).let { it.copy(analysis = it.analysis.copy(beatConfidence = 0.1)) },
        ),
        "005-outgoing-tempo-out-of-range" to FixtureCase(
            name = "A measured tempo outside 40-220 BPM cannot be used",
            outgoing = track("a", 250.0),
            incoming = track("b", 120.0),
        ),
        "006-short-outgoing-track" to FixtureCase(
            name = "Too little track to spend on a mix",
            outgoing = track("a", 120.0, duration = 30.0),
            incoming = track("b", 120.0),
        ),
        "007-live-recording" to FixtureCase(
            name = "Live recordings are never mixed",
            outgoing = track("a", 120.0).copy(title = "Song (Live)"),
            incoming = track("b", 120.0),
        ),
        "008-slower-incoming-track" to FixtureCase(
            name = "A slower incoming track is sped up to meet the grid",
            outgoing = track("a", 120.0),
            incoming = track("b", 111.0),
        ),
    )

    private fun planFor(case: FixtureCase): TransitionPlan = planTransition(
        analysis = case.outgoing.analysis.toTrackAnalysis(case.outgoing.id),
        nextAnalysis = case.incoming.analysis.toTrackAnalysis(case.incoming.id),
        currentTrack = case.outgoing.toTransitionTrackInfo(),
        nextTrack = case.incoming.toTransitionTrackInfo(),
        currentTime = case.positionSeconds,
        duration = case.outgoing.analysis.duration,
        fadeSeconds = case.settings.fallbackFadeSeconds,
        mode = if (case.settings.automix) CrossfadeMode.SMART else CrossfadeMode.STANDARD,
        advanced = true,
    )

    @Test
    fun plannerMatchesRecordedFixtures() {
        val record = System.getenv("TRANSITIONS_RECORD") == "1"
        val dir = transitionFixturesDir()
        assertTrue(cases.isNotEmpty(), "there must be fixtures to check")
        for ((file, case) in cases) {
            val target = File(dir, "$file.json")
            val actual = planFor(case)
            if (record) {
                dir.mkdirs()
                target.writeText(fixtureJson.encodeToString(case.copy(expected = actual.toExpected())) + "\n")
                continue
            }
            assertTrue(target.isFile, "missing fixture ${target.absolutePath}")
            val expected = fixtureJson.decodeFromString<FixtureCase>(target.readText()).expected
            assertEquals(
                expected,
                actual.toExpected(),
                "planner answer changed for $file",
            )
        }
    }
}
