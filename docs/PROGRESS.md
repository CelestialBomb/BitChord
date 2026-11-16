# Transitions: progress log

Newest first. Update after every step and commit with it. See `CLAUDE.md` /
`.clinerules` for the working rules and `docs/transitions-design.md` for the spec.

## Current state (16 Nov 2026)

Branch `transitions`. Spec v0.3. Shared JVM tests: 139 in 21 suites, all pass.
App unit tests (`testDevDebugUnitTest`, locale test skipped as in CI): 1242,
0 failures, 1 skipped. Dev APK builds and is installed on the test phone
(`com.dev.bitchord`, arm64).

## Bugs found on device (beat-aware Automix, Android)

| # | Bug | Status |
|---|---|---|
| 1 | Transitions loop: play "Dream, Ivory" (Dream, Ivory), Play next "I Can't Say Goodbye (Unmuffled)" (iOS, not a video), go back to "Dream, Ivory" and let it end: the last ~10 s repeat until the song's time runs out. | **Fixed, confirmed on device** 16 Nov (no loop; log below). The loop mix itself still fails for this pair (see "Loop mix fails near the song end"). |
| 2 | Play next / Add to queue never gets a DJ mix. | Hook committed. Bug 1 log: a Play next pair was planned within 70 ms; some pairs got a DJ blend, others were unmixable by tempo. **Awaiting a targeted test.** |
| 3 | Gapless within albums doesn't fire on Juelz's DRAMATICA: no album ID is passed, and the title+artist check is too strict. | Not fixed. Plan line now logs album, artist and the setting. **Awaiting a DRAMATICA log.** |
| 4 | Automix overview (mix popup) sometimes shows visual glitches; works most of the time. | **Low priority.** Waiting for a screenshot from the user. |

### Bug 1: device log (16 Nov, dev build `2c0c1b2b`+)

Dream, Ivory (`1EkVojVV_3I`, 188 s) -> i cant say goodbye(unmuffled) (`4iqrRR84Znk`):

1. Play next at 16:28:25 changed the next song; the pair was re-planned at once.
   DJ planner: `before-unmixable-crossfade` (60 vs 138 BPM, `tempo-distance,unmixable-pair`).
   Loop mix planned instead: 4-bar loops at 164.03 s, 60 -> 69 BPM, next in double time.
2. Loop mix started arming at 152.1 s, decks built in 1.15 s, took over at 164.0 s
   (planned mix length 41.2 s, handover 12.1 s into the incoming song).
3. At 184.6 s (3.7 s before the end, no handoff yet): `a sink was flushed`.
   Recorded as failure 1 -> `PLAIN_CROSSFADE`.
4. Settle: **no seek back** to 168.7 s (the old code would have jumped back 16 s and
   re-armed: this was bug 1).
5. Gate -> plain crossfade armed at 184.8 s, `smart-plain-crossfade`, 6 s planned but
   only 3.5 s left; handed off and finished at 16:29:22. No second failure.

### Loop mix fails near the song end (new, from the bug 1 log)

The loop mix took over at 164 s with a 41 s mix on a 188 s song, and had not
handed off 20 s later when the sink was flushed 3.7 s before the file end.
Hypothesis (not confirmed): the outgoing player keeps advancing through its own
file during the loop, reaches its end before the mix hands over, and that
flushes the sink. Next step: log the outgoing player's position and the mix
clock (`handoffAt`, `elementAt`) at takeover and at each loop wrap, and check
whether `LoopMix.plan` should refuse a mix whose handover lands past the end of
the outgoing file.

### Bug 1: what was done

Suspected cause: a loop mix that failed after its deck took over settled by
seeking the outgoing song back to the loop position. That put the playhead back
in the transition window, the 250 ms re-planning armed the same mix, and the
end of the song replayed.

- `TransitionFailureLog` (shared, unit-tested): failures per outgoing -> incoming
  pair. 1 failure: plain crossfade only. 2+: nothing armed, the song plays out.
- `CrossfadeController`: real failures (player error, arm deadline, incoming
  idle mid-blend, running out before the handoff, a loop mix stopping on its
  own) are recorded once per transition. Skips, seeks, pauses and queue edits
  are not failures (`bailByListener`).
- Arming checks the record in both the Automix and the standard path. For a plain
  crossfade the planner is shown no analysis (rule R5, `smart-analysis-fallback`)
  and the loop mix is skipped.
- After a failed loop mix the settle seek only moves forward, and only while the
  session player is still on the song the target belongs to.
- Previous clears the record for the current and previous song (fresh attempt).

Not yet known: *why* the loop mix fails in this scenario. The logs will say.

### Bug 2: what was done

`PlaybackService.onTimelineChanged` (playlist changes) calls
`CrossfadeController.onQueueChanged()`: plans for the old next song are dropped,
an arming transition for the old pair is cancelled, and the new next song's
analysis is requested at once. `seekToPrevious()` (the app's previous button)
now calls `onPreviousRequested()`; before, it never told the controller.

The plan log line now includes the analysis state, the next song's duration and
both album titles. The working guess is that a song added with Play next has no
duration or analysis in time, but this is not confirmed.

## Logging

`adb -s R3GL10HRFBK logcat -s BitChordTransitions BitChordCrossfade`

`BitChordTransitions` logs: `plan` (one per distinct decision, with the gate
verdict), `loop` verdicts, `gate` (a pair held back by the failure record),
`start`, `arm`, `cancel` (listener), `failure`, `bail`, `loop mix stops`,
`loop settle`, `finish`, `queue changed`, `plans dropped`.

## Next

1. Bug 2 device test (Play next and Add to queue with tempo-compatible songs);
   read the `plan` and `start` lines for the new pair.
2. Bug 3 device test on DRAMATICA; read `album=`, `artist=`, `albumGapless=` and the
   reason. Then pass an album ID through `toTransitionInfo` and relax the
   same-album check (exact artist match fails on features); add fixtures.
   Note the rule is also skipped when analysis finds an interior mix-out
   (`hasInteriorMixOut` in `TransitionPlanner`).
3. Loop mix failing near the song end (above).
4. Mix popup glitches, when a screenshot arrives (low priority).
5. Update the design doc (section 4: failure record and queue hook; section 9)
   in both forks.

## Open questions

- Should a queue edit during a running blend (FADING, LOOP_RUNNING) reach the
  incoming player's copy of the queue? Today only appended items are reconciled
  at the handoff.
- The adb server on this PC is also started by an older adb (version 40 vs 41);
  they restart each other, which can drop a logcat capture.
