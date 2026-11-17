# Transitions: progress log

Newest first. Update after every step and commit with it. See `CLAUDE.md` /
`.clinerules` for the working rules and `docs/transitions-design.md` for the spec.

## Current state (16 Nov 2026, evening)

Branch `transitions`. Spec v0.4 (identical in the PC repo). Shared JVM tests:
171 in 23 suites, all pass. App unit tests (`testDevDebugUnitTest`, locale test
skipped as in CI): 1242, 0 failures, 1 skipped. PC `cargo test transitions`: 7
pass, fixtures 012-018 skipped there as `androidOnly`. Dev APK with the mix view
fix and the cleanup installed on the test phone at 23:20 (`com.dev.bitchord`, arm64).

### Stage 2: custom transitions per playlist (17 Nov, in progress)

Approved plan: `C:\Users\kcbum\.claude\plans\pasted-content-id-2848-next-stage-silly-orbit.md`
(Preview plays for real; no EQ in v1; 45 s windows; "Analyse playlist" button;
backup must include custom transitions and Mix flags; the editor loads only the
45 s windows it needs, cache first, Wi-Fi only, full download as a last resort).

| Step | Status |
|---|---|
| 1. `CustomTransition`, `applyCustomTransition`, fixtures 019-023 | **Done**, shared tests 187 pass |
| 2. Store, Mix flag, backup section + round-trip test | **Done**, app tests 1249 pass (7 new) |
| 3. Controller hook, high-pass exit, preview override | **Done**, app tests 1254 pass; no UI yet, so nothing to try on the phone |
| 4. `MixWindowStore`, window plan, window loader | **Done in code**, shared 208 / app 1255 pass; **not yet run against a real stream** (needs the editor, step 6) |
| 5. Playlist UI (toggle, BPM/key, transition rows, Analyse playlist) | To do |
| 6. Editor sheet and Preview | To do |
| 7. Design doc 0.5 (both repos), push | To do |

Step 2 notes: backup checked first. The in-app export only wrote `bitchord_settings`
and listening, so a store in its own preference file would have been left out; it now
carries an optional `customTransitions` section (schema stays 1; older builds skip it,
older files without it leave the store alone, an empty section clears it). Android
auto-backup already includes every non-excluded preference file. Local playlists
themselves are still not in the in-app backup (existing gap, unchanged).

Step 3 notes: in `considerSmartTransition`, after the guards and the failure gate, a pair
is custom only when both rows are `CONTEXT` tier and carry the same `playbackSourceId`
(Play next, Add to queue and AutoPlay rows never are) and `CustomTransitionStore.effective`
answers (saved + Mix on, or an editor preview for exactly that pair). It replaces the global
style and length for the pair, skips the loop mix, and logs `custom=<mode/style/bars/filter>`
(or `fallback`, with the `custom-fallback-*` code in the policy list). A failed pair gets no
custom plan. The exit filter merges into `tracks.outgoing` (lowest low-pass, highest high-pass),
so it adds to the style's own move. **Limit:** custom transitions run inside Automix, so they
need Automix on; the editor should say so.

Failure record and custom transitions (user request, 17 Nov): saving, resetting or
previewing a pair clears that pair's failure record (`CustomTransitionStore.onPairEdited` ->
`TransitionFailureLog.clearPair`), so the new transition applies at once, Preview included.
A custom Gapless or No mix that applies to the pair (durations still match) is never held
back by the record; a stale-duration one falls back to the automatic plan, which stays
protected by it. Restoring a backup does not clear failures.

Step 4 notes: the editor's waveforms and beat grids come from two 45 s windows, never a
whole song. Pure parts in shared (`MixWindowPlan.kt`: byte plan with a 3 MB budget, the
saved -> cache -> fetch -> Wi-Fi-wait decision, beat extension, snapping; `MixWindowStore`:
one small JSON per song and side, 600 entries, dropped on a different cut or schema). App
side: `TrackAnalyzer.mixWindow` reuses `MixWaveform.build` and `region()` (the existing
decode + `BeatTracker` at any offset), and `AudioCache.fetchWindow` fetches only the header
and the window under the pinned analysis key the opening fetch already uses (so a head
window is usually already cached), recording the stream's real length so the extractor can
seek. A decode that did not reach the window is a failure, never a flat picture. Wi-Fi only
blocks a fetch on a metered or unknown connection but never cache or saved reads.
**Risk to check first on the phone:** that the extractor seeks into a partly cached WebM
(needs its seek index inside the 256 KB header fetch); the log line is `Mix window <id>
<side> from <source>: ...` or the reason it failed.

Step 1 notes: reason codes `before-custom` / `custom`, `custom-gapless`,
`custom-no-mix`; fallbacks are policy reasons on the planner's own plan
(`custom-fallback-duration`, `-window`, `-late`). `TransitionPlan` gained
`exitFilter` / `exitFilterAmount` (defaults `NONE` / 0, so legacy plans are
unchanged). A custom transition replaces the style and length settings for its
pair. Echo-out (Quick) only on a pair the planner beat-matched.

### 16 Nov, late: toast fix and dead-code cleanup

- **Device test:** all five Mix on skip tests passed (blends; previous and queue
  taps instant; clean double skips; styles and lengths work).
- **Bug 5, fixed `95e5ca8a`, awaiting device check:** after removing a Play next
  song, a toast "No Automix transition coming up" appeared while a mix played.
  Log: the plan for the new next song was redone 0.2 s after the removal
  (23:02:40), so it was not a stale plan. Cause: during a filter or echo skip
  blend the "Mixing" label was shown and tappable, but `publishMixView` returned
  early (a skip blend has no planner plan). The popup found no view, nothing
  re-plans mid-blend, and after 2.5 s it closed with that toast. Fix: a skip blend
  publishes a blend view (beat from its decision, span from where it really
  started, waveforms decoded once it runs). Also: the `marker` log line is reset on
  a queue change; it was deduped on its verdict alone and stayed silent for a new
  pair. Check on device: during a skip blend, tap "Mixing": the overview shows the
  blend instead of the toast.
- **Cleanup `08bd93d4`:** reviewed `git diff v1.9.1..HEAD`; removed only dead code
  the branch added: a dead `armDeadline` write, a doc-only import, an unreachable
  check in `noteFailure`, a duplicate `skipGuard` call, `TransitionFailureLog.clear`
  and `isEmpty` (test-only), and needless `internal` visibility (`sameAlbum` is
  private again, as upstream). All added strings, settings keys and setters are
  used; no compiler warnings in added code. No upstream code touched.

**Before any upstream PR:** the new strings (Mix style, Blend length, Mix on skip
and their options) are English only. Add translations for the 20 locales that
have the Beat-aware strings.

## Stage 1 customisation and Mix on skip (16 Nov, spec 0.4)

Approved plan, user decisions: queue taps don't blend (next, swipe, headset,
Auto do); style and length apply whenever Automix is on; English strings only
for now; the design doc is committed and pushed in the PC repo too.

| Commit | What |
|---|---|
| `df01df19` | Shared `MixStyle`, `BlendLength`, `applyMixStyle`; 13 tests; fixtures 014-018 |
| `7a87f8eb` | Settings `automix_style`, `automix_blend_length` under Automix; controller restyles each plan |
| `53b5e530` | Shared `decideSkip`, `skipFallback`; 14 tests |
| `57cff84e` | Mix on skip in the controller and `SessionPlayer`; setting `mix_on_skip`; skip booked as a skip |

How it works: see design doc sections 2 and 4.7. Logs: `plan ... style=<style>/<length>`
and `skip <reason> ...` under `BitChordTransitions`.

**Device test checklist** (items 1-5 passed on 16 Nov; 6-8 not yet checked):
1. Settings > Automix shows Mix style, Blend length and Mix on skip.
2. Style Fade / Rise / Quick and Short / Long change a track-end mix (log `plan` line shows `style=`).
3. Mix on skip on, press next mid-song: a 2-4 s blend, on the beat, with the
   screen on the new song at once; the new song starts after its silent intro.
4. Swipe, notification and headset next blend too; a queue tap and previous are instant.
5. Two quick presses: two songs skipped, no stacked blend (`skip-cut-busy` / `skip-cut-rapid`).
6. Next on a song not downloaded yet: a short plain fade (`skip-fade`) or a plain
   skip after 2.5 s, never silence while the old song is still meant to play.
7. Next with under 5 s left, paused, or with Mix on skip off: instant.
8. ListenBrainz: a skipped song is not submitted as listened to.

**Risks to watch on device:** the button can feel up to ~0.5 s slower (beat wait);
Play next rows (USER_QUEUE) are not pruned at a blended handover, as for every
blend; the echo-out on a 2 s blend at fast tempos may sound busy.

## Bugs found on device (beat-aware Automix, Android)

| # | Bug | Status |
|---|---|---|
| 1 | Transitions loop: play "Dream, Ivory" (Dream, Ivory), Play next "I Can't Say Goodbye (Unmuffled)" (iOS, not a video), go back to "Dream, Ivory" and let it end: the last ~10 s repeat until the song's time runs out. | **Fixed, confirmed on device** 16 Nov (no loop; log below). The loop mix itself still fails for this pair (see "Loop mix fails near the song end"). |
| 2 | Play next / Add to queue never gets a DJ mix. | **Audio fixed, confirmed on device** 16 Nov. Remaining: the mix window doesn't show on the progress bar after Play next (2b below). |
| 2b | After Play next, no mix section on the progress bar / overview. | **Fix committed** `0ed30bb0` (user chose: provisional window + overview text); **awaiting device test**. |
| 3 | Gapless within albums doesn't fire on Juelz's DRAMATICA: no album ID is passed, and the title+artist check is too strict. | **Fix committed** `d604cb28`, unit-tested; **awaiting device test**. |
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

### Bug 3: device log and fix (16 Nov)

DRAMATICA, shuffle off. Track 1 "start the drama (intro)" (82.3 s) -> track 2
"HIGHER": album `DRAMATICA`/`DRAMATICA`, artist `Juelz`/`Juelz`, setting on, yet
`smart-phrase-8-bar` with the window at 54.9-68.6 s (67-83 %). The album match
passed; R3 was skipped by its extra `!hasInteriorMixOut` condition (analysis
found an exit 13.7 s before the end). Track 2 -> 3 "MY MY MY" would also have
failed: artist `Juelz` vs `Juelz, Z3lla` (exact match).

Fix (`d604cb28`):
- R3 no longer requires "no interior mix-out". Only reachable with
  `albumSequential`, which nothing passed before Beat-aware, so fixtures 001-008
  are unchanged (verified: all 11 existing fixtures re-recorded identical).
- `sameAlbum`: album IDs decide when both sides have one; otherwise album title
  (trimmed, case-insensitive) plus at least one shared credited artist.
- The controller passes the `bitchord.albumId` extra, and asks for album gapless
  only with shuffle off. The plan line logs `albumId=` and `shuffle=`.
- New tests: 6 in `BeatAwarePlannerTest`, fixtures 012 and 013 (`androidOnly`).

PC port needed: `sameAlbum` and R3 in `src/transitions/`, then drop `androidOnly`
from 012-013. Design doc R3 wording should say "played in order (shuffle off)".

### Bug 2b: why the mix window doesn't show after Play next (16 Nov log)

Spring Carnival -> Regenerate (Play next at 202 s of 264 s):
- 16:35:34 queue changed; the marker was re-evaluated within 230 ms: hidden,
  `next is WAITING`, `nextDur=0ms`. So the hook and refresh work.
- 16:35:46 the next song's opening had downloaded; analysis ran (ANALYSING).
- 16:36:02 analysis landed. The marker was shown in the same tick the blend
  armed (window 233.6 s, armed at 230.5 s) and is cleared when the blend starts
  (`startFade`), so it was on screen for ~3 s at most.

The marker is gated on both songs being analysed, by design ("a marker that
slides along the bar while you watch it is worse than none"). A song never
played before needs its opening downloaded and analysed, ~28 s here, so a late
Play next shows no window. A song analysed earlier showed its marker 1.4 s after
Play next (Dream, Ivory -> i cant say goodbye, 16:35:02).

Decision (user, 16 Nov): both a provisional window and overview text.
Implemented in `0ed30bb0`, Beat-aware only (off: unchanged):
- `TransitionWindow.provisional`: set when the plan is only waiting on analysis
  or the next song's audio (not when blocked or without a marker).
- `ThinSlider` draws a provisional window at 40 % strength and fades it to full
  once measured; `PlayerScrubber` passes the flag (before `centerLabel`, which
  callers give as a trailing lambda).
- Automix line: "Automix · analysing the next song · provisional mix in N s"
  (or "analysing this song", "analysing both songs", "couldn't analyse …",
  "waiting for the next song's audio").
- Log: `marker … provisional: next is WAITING` under `BitChordTransitions`.

Known limit: when an analysis has failed for good, the window stays dimmed
(it will not move, but it is still the fallback plan).

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
`loop settle`, `finish`, `queue changed`, `plans dropped`, `marker`
(shown / provisional / hidden), `skip <reason>`, `skip blend starts`,
`skip blend abandoned`.

## Next

1. Device tests: tap "Mixing" during a skip blend (bug 5); Mix on skip checklist
   items 6-8; DRAMATICA tracks 1 -> 2 -> 3 gapless (`same-album-gapless`); Play
   next on a never-played song shows a dimmed window at once that brightens (and
   may move) when analysis lands.
2. PC port (spec 0.4, all "PC: New, to do"): album match and R3, failure record,
   `applyMixStyle`, `decideSkip`; then drop `androidOnly` on 012-018.
3. Translations for the new strings (before any upstream PR).
4. Loop mix failing near the song end (above).
5. Mix popup glitches, when a screenshot arrives (low priority).

## Open questions

- Should a queue edit during a running blend (FADING, LOOP_RUNNING) reach the
  incoming player's copy of the queue? Today only appended items are reconciled
  at the handoff.
- The adb server on this PC is also started by an older adb (version 40 vs 41);
  they restart each other, which can drop a logcat capture.
