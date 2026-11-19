# Transitions: progress log

Newest first. Update after every step and commit with it. See `CLAUDE.md` /
`.clinerules` for the working rules, `docs/REBUILD-BRIEF.md` for the rebuild and
`docs/transitions-design.md` for the spec.

## transitions-v2 rebuild (from 18 Nov 2026)

Branch `transitions-v2`, cut from v1.9.1 (`7f45c768`). `transitions` is kept as the
reference (last commit `55713bb2`). Spec 0.7.

| Stage (brief section 6) | Status |
|---|---|
| 0. Branch and docs (brief, CLAUDE.md, .clinerules, spec 0.7, this log) | Done |
| 1. Base and engine: planner options, R3 album gapless, failure record, queue hook, provisional window; fixtures 001-013 | **Done and pushed** 19 Nov. Phone checklist 1-7 pass (2 after the settle fix `5995624c`); item 2's no-mix retest goes to stage 3 |
| 2. Styles, blend length, Mix on skip; fixtures 014-018 | **Done** 19 Nov, tests pass. Phone checks 1, 3-9 pass (8 by code, 9 after `553831cd`); 2 partial: the styles work, and the loop-mix failures seen during it go to stage 3. Not pushed yet |
| 3. Automatic loop mix and the flush fix (dev-only adb hook) | In progress (plan `C:\Users\kcbum\.claude\plans\transitions-v2-stage-3.md`, go 19 Nov). Done: (a) `cf85b64a`, (b) `105013ed`, standby error log `7a3079c9`; phone check of (a)+(b) before (c) |
| 4. Custom transitions; fixtures 019-023 | Not started |
| 5. Cleanup and independent review | Not started |
| 6. New features (brief section 7) | Not started |

Decisions for the rebuild:
- 18 Nov (user): the failure record, the loop settle, the queue hook and the
  Previous reset run whether or not Beat-aware is on. They fix v1.9.1's own song-end replay,
  and the spec's principle 1 now lists them as exceptions.
- Album gapless checks the app's shuffle (`QueueShuffle.enabled`). The old branch checked
  `player.shuffleModeEnabled`, which stays false under the app's own shuffle.
- Album title and ID reach the planner only with Beat-aware on. The old branch always passed
  the title, and v1.9.1's live / spoken-word rule (R4) reads it, so Off was not v1.9.1.

### Stage 3: loop mix (19 Nov, in progress)

User decisions with the go: after a failed loop mix the pair may still get the planner's DJ blend
(only a failed blend drops to a plain crossfade); keep the incoming loop mid-song, but never repeat
one on the song's opening; keep the adb test hook, dev builds only.

| Commit | What |
|---|---|
| `cf85b64a` | (a) `LoopMixPort` clears its clock reading on arm and disarm: a stale reading made the mix clock read past the loop start ("the sink never reached the loop", 6 of 11 loop mixes on 19 Nov) and fed the first alignment (+20-33 s) |
| `105013ed` | (b) An incoming loop on the song's opening (before its second bar line) plays through instead of repeating (`Plan.inRepeats`). The incoming player starts where it lines up with the deck, never before 0:00 (`LoopMix.incomingStart`), so no long silence is inserted |
| `7a3079c9` | Errors on a loop mix's standby player are logged (`standby player error during ...`) |

Tests: shared 184, app 1257 (1 skipped). Still with the 240 s stuck timeout; (c) restores 10 s once
the phone shows the release is on the deck and no long silence is inserted.

**Phone check of (a) + (b), 03:28 build:** 1, 3, 4 PASS (user): loop mixes in a row, Flamingo -> i
don't belong here, Mahiru No Tsuki -> Are you coming back?, Hugs -> Lifer. The first alignments are
now small (-156 ms, then 0.00 ms), and a loop released `incoming +0.01ms against the deck`.
2 FAIL (03:49, Dream, Ivory -> I Can't Say Goodbye): the song was left paused at 0:01. Caused by
`105013ed`: the incoming player now starts where its 0:00 lines up with the deck (29 s into a 41 s
mix), but the session still moved to it mid-ramp (24 s), `handoff at in=0ms`. The driver read the
unstarted player as paused (`loop mix stops: paused`) and the settle left it at the loop's
position. Also, its loop starts on bar 2, so the first-bar opening rule repeated it. Fixed in
`ef721367`: hand over only once the incoming player plays (and no earlier than its start + 1.2 s);
an opening loop is one starting within the first 4 bars.

| Commit | What |
|---|---|
| `9d9ebd56` | (d) Failure rule by kind: `NO_LOOP` / `PLAIN_CROSSFADE` / `NONE`, never less strict |
| `e5ae8ce0` | (d) After a loop fails past its entry, a 1 s fade continues into the incoming song at its mix position (`loop-fallback-continue`) |
| `cd00709d` | Ported: wait for a complete cached copy ("waiting on <song>'s audio in the cache"); "waiting on the audio output" for an unconfigured sink |
| `ef721367` | The handover waits for the incoming player; the 4-bar opening rule |

Installed 03:58 (all of the above). Tests: shared 187, app 1259 (1 skipped).

**Phone check, 03:58 build:** 1 PASS (Dream, Ivory -> I Can't Say Goodbye "(plays through, its
opening)", carried straight on); 2 PASS (Flamingo -> i don't belong here still loops mid-song; other
loop mixes fine); 3 and 4 didn't come up.

| Commit | What |
|---|---|
| `da23a3b0` | (c) Media3's 10 s stuck default restored (240 s from `9304e202` removed). Dev-only adb hook: `adb shell am broadcast -a com.dev.bitchord.DEBUG_LOOP -p com.dev.bitchord --es fire <stall-incoming\|stop-standby\|replace-item>`. Version swap is not a hook suspect (it needs the service's swap internals) |
| `cec153d6` | Dev-only stop-site log (`standby player stopped by <site>`, `retire: stopping a player`) |

Installed 04:08.

### Stage 2: style, blend length, Mix on skip (19 Nov)

Plan: `C:\Users\kcbum\.claude\plans\transitions-v2-stage-2.md` (user's go 19 Nov).

Tests: shared 181 in 25 suites, app 1254 in 114 (1 skipped), 0 failures. Fixtures 001-013 unchanged
at every commit; 014-018 back and passing.

| Commit | What |
|---|---|
| `16a2a17c` | Shared `MixStyle`, `BlendLength`, `applyMixStyle`; fixtures 014-018 |
| `c5898228` | Settings `automix_style`, `automix_blend_length` (same keys as the old branch); `TransitionPlanning.restyle` |
| `37cd148f` | Shared `decideSkip`, `skipFallback`; `SkipBlendTest` |
| `e0ecc615` | Mix on skip: `SkipBlender` (app), controller hooks, `PlaybackService` next hooks, `skipped` handoff, setting `mix_on_skip` |
| `58d01f84` | A skip blend publishes its mix view |

Answers the plan left open: a skip blend that cannot start (next song not ready within 2.5 s,
paused while waiting) is no failure, and the skip happens plainly. One that fails mid-blend
(incoming idle, player error) is recorded against the pair like any blend.
Same as the old branch: strings in English only; the old branch's dead `armDeadline` write for
skip blends is not carried over.

#### Stage 2 phone results (19 Nov, 02:18 build)

| # | Result | Evidence |
|---|---|---|
| 1 | PASS | Seen by the user |
| 2 | PARTIAL | `style=fade/medium`, `rise/short` etc. on `plan` lines and restyled plans (`(planner DJ_BLEND ...)`). Two loop mixes failed during it (below) |
| 3-7 | PASS | `skip skip-blend` with `skip blend starts` and `handoff at in=...` about 30 ms later; `skip-cut-busy` / `skip-cut-rapid` / `skip-cut-guard (paused / this pair failed before)` / `skip-cut-off` as expected |
| 8 | PASS by code | ListenBrainz isn't set up on the phone. A skip blend hands over with `skipped`, so `onTrackBecameCurrent(previousEnded = false)`: no finished listen is submitted and "sleep after this song" doesn't fire |
| 9 | PASS after `553831cd` | First run: the overview said "No Automix transition coming up". Retest: it shows the skip blend |

**The "repeat bug" (check 2) was two loop mixes breaking, not the settle being bypassed.**
Neither style/length nor the skip blend touches those paths.
- 02:22:58, Hugs -> Lifer: the loop handed over at `in=1633ms`, and Lifer's position stayed at
  1.633 s while the loop rendered. 10 s later Media3 raised `StuckPlayerException: Player stuck
  playing with no progress for 10000 ms` (`StuckPlayingDetector`). The failure record took it,
  and the app's recovery reloaded Lifer from 1.6 s, so its start played twice.
- 02:26:49, Mahiru No Tsuki -> Are you coming back?: "a sink was flushed" 20.4 s after takeover
  (the same ~20.5 s as on 16-18 Nov). The settle put the song at the mix's position (234.1 s),
  and the plain crossfade at 236.8 s started the next song again from 0.
- Fix `9304e202` (v1.9.1 bug): both players get a 240 s stuck-playing timeout, longer than any
  loop mix (32 bars at 40 BPM, about 192 s). This is the stage 3 flush lead: the same 10 s timer
  on the standby, whose errors the controller never sees, explains the ~20.5 s flush. Whether it
  cures the flush shows on the phone; stage 3 still builds the adb hook to prove it.

**Retest on the 02:48 build (`9304e202`, `553831cd`):**
- Check 9: **PASS** (user). Tapping "Mixing" during a skip blend shows the skip blend.
- Dream, Ivory -> I Can't Say Goodbye (02:51, 02:53): the user heard a failure and a restart from
  0. In the log, both loop mixes were planned (after 3 s waiting on lyrics) and ended `loop mix
  done`, with no failure and no player error. Likely heard: the incoming loops its first 4 bars by
  design, and it is released 1.2 s behind the deck (`loop align: left at -1207ms`), which is the
  brief's handover-offset item. Stage 3.
- "This pair failed before" at ~03:00: new failures in this process (the record is memory-only).
  X8glgP5Lwfk -> TWm_LfKekC0 at 02:57:35 and, after Previous cleared it, 02:58:40;
  06Un6dHGegg -> ou-2UkMrPJA at 02:59:44. All were "the sink never reached the loop", 5-30 ms after
  arming.
- Loop mixes, 02:18-03:01: before the timeout fix, 2 stuck-timer failures (02:22, 02:26) and
  1 clean; since the fix, 2 clean and no stuck or flush failures; on both builds, 6 arming
  failures ("never reached the loop"). The arming failure is now the main loop problem, and
  the Previous lead doesn't hold (Dream, Ivory armed fine after Previous at 02:53).
- User question: 240 s only during loop mixes? Not cleanly now. It is a builder setting, and the
  error stops the player before app code sees it. Clean version, stage 3: keep the player's
  position moving during a loop mix, then restore the 10 s default.
- Stage 3 candidate (user request): after a failed loop, continue the incoming song from where the
  mix reached instead of the plain crossfade restarting it from 0.

**User decisions (19 Nov, after check 9 passed at 03:02):**
- Continuing the incoming song after a failed loop: stage 3. The arming failure: stage 3, top
  priority.
- The -1.2 s jump back at the loop's release sounds like the song restarting: a bug, not a nice-to-have.
- The incoming song's opening repeating (its own loop) also felt like a restart: consider making
  the incoming loop shorter or optional.
- Stage 3 order: (a) arming failure, (b) handover offset / jump back at release, (c) keep the player
  position moving during loop mixes and restore Media3's 10 s stuck default, (d) failure rule and
  continuing the incoming song after a failed loop, (e) pluggable transition kinds and the tempo
  notes from the brief.

**Check 9:** a skip blend is over in 2-4 s and hands over at once, while the "Mixing" label and
echo tail outlast it. A tap usually came after `finish()` had cleared the view, so the popup
found nothing. That is inferred: taps aren't logged, but it is the only path to that message.
Fix: a skip blend that played out keeps its view for 5 s.

Phone checklist (dev app; the capture runs throughout; any setting changed for a test is
announced and restored):
1. Settings > Automix shows Mix style, Blend length and Mix on skip. Your phone keeps its saved
   values from the old branch (Long, Mix on skip on).
2. Fade, Rise, Quick and Short, Long change a track-end mix (`plan` line shows `style=`).
3. Mix on skip on, Next mid-song: a 2-4 s blend on the beat; the screen shows the new song at once.
4. Swipe, notification and headset Next blend too; a queue tap and Previous are instant.
5. Two quick presses skip two songs, no stacked blend (`skip-cut-busy` / `skip-cut-rapid`).
6. Next onto a song not downloaded yet: a short plain fade (`skip-fade`) or a plain skip after
   2.5 s, never silence.
7. Next with under 5 s left, while paused, or with Mix on skip off: instant.
8. A skipped song is not submitted to ListenBrainz as listened to.
9. During a skip blend, tapping "Mixing" shows the overview.

### Stage 1: base and engine (18 Nov)

Plan: `C:\Users\kcbum\.claude\plans\read-docs-rebuild-brief-md-fully-plus-precious-valiant.md`.

Tests (shared `:shared:jvmTest` / app `testDevDebugUnitTest`, locale test skipped as in CI):
v1.9.1 baseline 122 in 17 suites / 1242 in 111 (1 skipped); after stage 1, 154 in 23 / 1250
in 113 (1 skipped), 0 failures. Fixtures 001-008 pass unchanged at every commit. Dev APK built.

| Commit | What |
|---|---|
| `dc685f78` | CI on `transitions-v2` |
| `bbdb3c55` | Fixtures 001-008 and the harness (no style / custom fields yet) |
| `0d71831f` | Planner options `strictConfidence`, `allowTempoStretch`; `BeatAwareConfig`; fixtures 009-011; octave-fold test |
| `ddc5bb05` | R3 album match; fixtures 012-013 |
| `78fb2cbc` | Settings (4 switches, 21 languages); `TransitionPlanning` |
| `ab1adece` | Failure record: `TransitionFailureLog`, `FailureGate`; loop settle on the target song only (its forward-only rule was wrong, see below) |
| `03ed5b6c` | Queue hook and Previous |
| `b350e225` | Provisional window; marker decision as shared `mixMarker` |

Rewritten rather than ported:
- The controller diff is hooks only. Planning, the failure gate and the log tag live in
  `app/.../playback/transitions/` (`TransitionPlanning`, `FailureGate`, `TransitionLog`); the
  marker decision is shared and tested (`MixMarker.kt`).
- `TransitionFailureLog.clearPair` comes back with stage 4, its only user.
- The sleep timer's fade-out is never held back by the failure record (the old branch's gate
  sat before it).
- No dev-only log helper yet: nothing in stage 1 logs heavily. The old branch's v1.9.1 log
  lines that only moved to the new tag stay under `BitChordCrossfade`.

Phone check 2 (18 Nov, 17:26 build, Beat-aware off): 4 of 5 mixes fine. A Quick Picks loop mix
`uFbiNCSR5V4 -> fCw7V--epV0` failed ("a sink was flushed", 17:30:16, about 20.3 s after takeover)
and playback jumped back to the loop's start. The failure record then worked (plain crossfade,
"this pair failed before").
- **The jump back was our forward-only settle, not a seek.** The log said `loop settle after
  failure: no seek to 366113ms (at 377820ms)`, then the outgoing sink logged `Unexpected audio
  track timestamp discontinuity: expected 1000382177233, got 1000357401541`. During a loop mix
  the outgoing decoder is parked at the takeover point (357.31 s) while the player reports the
  mix's position (377.8 s). The rule took 366.1 s as backwards and skipped the seek, so the
  decoder resumed from 357.4 s. 10 s later the session player hit `StuckPlayerException`
  (`ERROR_CODE_TIMEOUT`), which counted as the pair's second failure. Fixed: the settle seeks
  to the mix's position whenever the session is still on that song. The failure record (no
  loop re-arm) is what prevents the replay. The old branch had the same rule; its 16 Nov Dream,
  Ivory fix worked because of the record, not the direction check.
- **Why the loop died: for stage 3.** It fits the earlier flushes (20.5 s after takeover on
  16 and 17 Nov). Strong lead: Media3 1.11's stuck-player detector, which the app does not
  configure. It fires after 10 s of "playing with no progress": the session player hit exactly
  that here, and the incoming player's input is held the same way during a loop. An error on
  the standby player stops it internally (ExoPlayer `stopInternal`, the stack seen on 17 Nov) and
  never reaches the controller, which listens only to the session player. To prove it in stage 3,
  log errors on the standby during a loop mix.
- Also seen: "the sink never reached the loop" (`zme6S-rVJWg -> bY5AqVorG_8`, 37 ms after
  arming) and "the decks would not build" (`fCw7V--epV0 -> zme6S-rVJWg`, the cached-copy problem
  the old branch's 68e6e71f addresses). Both are stage 3.

#### Stage 1 phone results (18 Nov 17:26 build, then the 17:39 settle-fix build)

The phone's log buffer only reached back to 01:10:16 on 19 Nov: the app's other logging (addon
lookups) rotates it within minutes. So items 2-6 rest on the user's observations, and from now
on a capture runs for the whole check
(`adb logcat -v time BitChordTransitions:V BitChordCrossfade:V AndroidRuntime:E '*:S' > file`).

| # | Result | Evidence |
|---|---|---|
| 1 | PASS | Seen by the user: Settings > Automix shows Beat-aware and its three switches |
| 2 | PASS after `5995624c` | 18 Nov, Beat-aware off: 4 of 5 mixes normal; the failed loop and its jump back are analysed above. 19 Nov ~00:05 retest: the pair said "this pair failed before" and got no mix (NONE). Not in the log. **Note:** the failure record lives in memory per app process, and the 17:39 install restarted the app. So that NONE means two new failures of the pair in the new process, not the 18 Nov ones; their cause is unknown. Re-check with the capture running |
| 3 | PASS | User: DRAMATICA gapless with shuffle off, normal mixes with shuffle on. Not in the log |
| 4 | PASS | User, 01:03-01:05: "analysing the next song · provisional mix in 207 s" with a dimmed window, then a 4-bar loop mix (199 -> 185 BPM) that ran cleanly. Not in the log |
| 5 | PASS | Redone 19 Nov 01:47-01:49 on 3 queues with the capture running: 5 changes, each logged `queue changed: next pair A -> B` and `plans dropped: queue changed`, and the new pair was re-planned 0.06-0.24 s later (a new loop mix found once). For songs not yet analysed, the window was dimmed (`provisional: next is WAITING/ANALYSING`) and then `shown`. All changes came while idle, so nothing was cancelled. No crash |
| 6 | PASS | User ~01:09: "this pair failed before", plain crossfade, no repeated ending. The log's tail shows it: `1EkVojVV_3I->4iqrRR84Znk` `smart-plain-crossfade`, handoff 01:10:49, finish 01:10:52 |
| 7 | PASS, with a stage 3 item | Log: Previous at 01:10:58-01:11:00 and again at 01:12:28-32 (`plans dropped: previous`), then the same pair planned its loop mix again (record cleared). Both times the loop failed while arming, then a plain crossfade played cleanly (01:11:52-01:12:02, 01:13:17) |

**Item 7's arming failure (stage 3).** `loop mix stops (LOOP_ARMING ...): the sink never reached
the loop`, 3 ms after `loop armed` (01:12:52.592 -> .595; the same at 01:11:27.874 -> .888).
`driveLoopArming` gives up when the mix clock (`session.mixSeconds`) reads more than 1 s past the
start before the sink has spliced. Here it read that on the first tick, with the playhead at
153 s and the takeover at 164.03 s. Both times the song had just been re-entered with Previous
(`TIMING track selected ... reason=2`). Lead: the clock's time base (`stream offset
1000000000000us`, taken from the outgoing port) does not match the sink after Previous re-enters
the song. Also seen on 18 Nov at 17:31:50 on another pair, 37 ms after arming.

#### Findings logged, not fixed (19 Nov)

- **Issue 19: album Play stays shuffled once shuffle has been on.** v1.9.1's own behaviour:
  `MediaController.playBoundedContext(..., shuffle = QueueShuffle.enabled.value)`
  (`PlayerConnection.kt`) starts every album or playlist in the app's saved shuffle state, so
  album gapless (R3, shuffle off only) never applies until shuffle is switched off. v2 does not
  touch that path. To discuss: leave as is, or a minimal v1.9.1 fix.
- **Failure rule, to discuss.** A pair dropped to no mix at all after a second failure that a
  bug caused. User's idea: a failed loop mix only drops the loop (a normal crossfade next time);
  only a failed plain crossfade drops to no mix.
- **Expected:** skipping mid-song does not blend yet; Mix on skip returns in stage 2.

Phone checklist (dev app, `logcat -s BitChordTransitions BitChordCrossfade`):
1. Settings > Automix shows Beat-aware and, under it, its three switches.
2. Beat-aware off: an ordinary Automix transition plays as before; `plan` lines end in `gate=NORMAL`.
3. Beat-aware on, DRAMATICA with shuffle off: 1 -> 2 -> 3 gapless (`same-album-gapless`).
   With the app's shuffle on: not gapless (`albumGapless=false shuffle=true`).
4. Play next a never-played song: a dimmed window at once, full strength once analysed.
5. Remove or move a Play next song: `queue changed` and `plans dropped`, and the window follows.
6. A failing pair (e.g. Dream, Ivory -> I Can't Say Goodbye): no repeated tail. A failure logs
   `failure ... -> next attempt: PLAIN_CROSSFADE`, then a plain crossfade.
7. Previous on that pair: the next plan is `gate=NORMAL` again.

### Issue 17: Automix found off. CLOSED 18 Nov, not a bug

The user turned those settings off themselves on 17 Nov. Nothing in the app reset them. The
Automix row stays as in v1.9.1. The dev-only Automix change log added while investigating
was removed again (stale diagnostic). The rule that came out of it (agents never edit the
app's settings over adb) was replaced on 19 Nov by the user's testing rule: settings may be
changed during tests, but each change is announced and everything is restored afterwards.
The investigation is below for reference.

User report: Automix was off after a new dev build was installed.

What the evidence shows:
- **The install is not the cause.** `dumpsys package com.dev.bitchord`: firstInstallTime
  15 Nov 18:16, lastUpdateTime 18 Nov 16:06. firstInstallTime survived, so every install
  was `adb install -r`: an update that keeps app data. No uninstall, no `pm clear`, and
  Android auto-backup only restores on a fresh install.
- **Automix was already off before that install.** Session `cf06026d` read the phone's
  `bitchord_settings.xml` at 15:43 (18 Nov) and found `smart_fade_enabled=false`. Its
  scripted Previews from 15:10 never logged an Automix plan. The install came at 16:04-16:06.
  No build was installed between 17 Nov 15:18 and 18 Nov 16:04.
- **More than Automix changed**, between 17 Nov ~15:25 and 18 Nov 15:03:
  - `smart_fade_enabled` went true → false.
  - `automix_performance_mode` went BALANCED → PERFORMANCE.
  - `beat_aware_transitions` went true → false. 17 Nov plans were phrase-aligned, so Beat-aware
    and phrase were on.
  - `beat_aware_phrase_aligned`, `beat_aware_tempo_matching` and `beat_aware_album_gapless`
    were all stored as false. Their defaults are true, and these rows only show while Automix
    and Beat-aware are both on.
- **Nothing in the code writes these on launch, update or migration.** On both branches the
  only writers are the Settings rows (`SettingsSheet`; the Automix row toggles on a tap
  anywhere along it) and a backup import (`Backup.importFrom` → `AppSettings.importPrefs`,
  which clears and replaces the whole settings file). The TV screens also write Automix, but
  they are not on the phone. The app runs in one process, so there is no stale second copy of
  the preferences writing over the file.
- **An agent edited the settings file.** At 15:43 on 18 Nov session `cf06026d` force-stopped
  the app and set `smart_fade_enabled` back to true with `run-as … sed -i` on
  `bitchord_settings.xml`, without asking. That is not the cause, but it hid the state. The
  rules now forbid it (CLAUDE.md / .clinerules).
- The Beat-aware switches read true again from 16:54 (the file's last write; plans at 16:55
  are phrase-aligned).

Conclusion: the six settings were changed through the Settings screen, or by importing a
backup, between the evening of 17 Nov and 15:03 on 18 Nov. It was not a reset on install,
first launch, migration or auto-backup, so there is no code path to fix. Confirmed by the
user: they changed them in Settings.

# Before the rebuild (transitions branch)

Kept as evidence. Commit hashes below are on `transitions`.

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
| 5. Playlist UI (toggle, BPM/key, transition rows, Analyse playlist) | **Done in code**, app tests 1262 pass; app starts cleanly; **not yet looked at on screen** |
| 6. Editor sheet and Preview | **Done and checked on the phone** (17 Nov): layout, drag, Rise + High-pass, Preview, Save/reopen, Gapless, No mix, window fetch (head and tail). Shared 238 / app 1262 pass |
| 7. Design doc 0.5 (both repos), push | Doc 0.5 written (4.8, codes, fixtures note, verification); copied to the PC repo; pushed after a secrets scan |

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

Step 5 notes: on a playlist page (`BrowseType.PLAYLIST`, YouTube Music or local; not albums,
artists or folders) a Mix switch sits under the header. With it on: tempo and key after the
artist on each row (`SongRow.traits`), a transition line between songs, and an Analyse
playlist button. Transition lines show only in the playlist's own order (no filter, default
sort), since a sort puts rows side by side that never play back-to-back; they are plain labels
until the editor exists (step 6 supplies `onOpenTransition`). `AnalysisBridge` gives the UI
the service's analyzer (same process): tempo/key from memory, the store, or a saved opening
window; Analyse playlist asks four songs at a time, respects Wi-Fi only, and says so if it
cannot start. Playlist ids are normalised (`VLPL..` = `PL..`) in the store so a pair saved on
the page also matches when the playlist is played from a card. Key shows once a song has a
head or whole analysis; a row with neither shows nothing extra.

Step 6 notes: tapping a transition line opens `CustomTransitionEditor` (a bottom sheet). Mode
Mix / Gapless / No mix; for Mix: the outgoing song's last 45 s as a `TransitionDeck` (the
popup's three-band waveform and colours) with the blend as a frame you drag, snapping to
beats and clamped by `moveStart` so it always plays as drawn; the incoming song's first 45 s
read-only; style, length (bars on a grid, seconds without), bass swap (grid only), exit filter
and amount. Reset, Preview, Save. Preview stands the draft in for the pair (clearing its
failure record), plays the playlist through `playDetailSongs` and seeks to 8 s before the
blend. Save also writes the length in seconds so the same blend plays if the grid is not
trusted later. If a window cannot be read the editor still works and offers Try again and
Download both songs (`startDownload`); Wi-Fi only shows its own message. The editor's
arithmetic is in shared `CustomTransitionEditing.kt` (15 tests, including that an accepted
draft is one `applyCustomTransition` applies).
**First things to check on the phone**: the log lines `Mix window <id> <side> from <source>`
(or why it failed); that dragging snaps and the frame stays on screen; that Preview starts
~8 s before and the blend plays (log `plan ... custom=mix/...`).

Step 6 phone check (17 Nov, over adb): editor opens, both windows decode from cache (62-106 beats),
drag moves the frame and keeps its width, Rise + High-pass + Preview play and log custom=mix/rise/8bars/
filter=high-pass. Found and fixed: the played length differed from the drawn one (editor 8 s, playback
10 s, because the analysis tempo said 192 while the window said 97), so `CustomTransition.beatSeconds`
now carries the beat the editor measured; also unlabeled slider, ticks drawn for an untrusted grid, grid
wording. **Still to check:** Save and reopen, Gapless, No mix, the true fetch path (every pair so far
came from cache; pick songs never played), then step 7 (design doc 0.5 in both repos, push).

Step 6 phone results (17 Nov, over adb), all on the dev build:
- Editor layout fits; Save/Preview are pinned below the scrolling controls (they had scrolled off).
- Drag moves the frame and keeps its width; Rise + High-pass + Preview log `custom=mix/rise/8bars/filter=high-pass`;
  Save then reopen restores style, length, filter and frame; the page line reads `Custom . Rise . 8 bars`.
- Gapless Preview: `before-custom-gapless|GAPLESS|fade=120`, armed 4 s before the end, next song at 0.1 s (no gap).
- No mix Preview: `custom-no-mix|fade=0`, nothing armed, the song ended and the queue advanced.
- Window loader: a head window fetched fine; a TAIL window fetched but would not decode because Android's
  Matroska extractor walks every cluster header of the file and cannot seek over unread bytes. Fixed by
  `MatroskaWindow.stitch` (header + Info + Tracks + the tail's whole clusters as a small standalone WebM,
  decoded from memory): 4.09 MB song -> 782 KB, seek landed at 212.961 s for 212.95 s, 97 beats. WebM only;
  M4A/AAC streams fall back to the Try again / Download offer.
- Fixed along the way: played length differed from the drawn one (now `CustomTransition.beatSeconds`);
  Preview now plays a two-song queue of exactly the edited pair, since the playlist queue after the previewed
  song did not match the page (a different next song each run).
- Limits seen: custom transitions never apply on video rows (Automix skips them); a pair needs both songs
  to be the ids the playlist plays. Test transitions I saved on the Elevator Music playlist (Rise, Gapless,
  No mix) are still there: open the pair and tap Use automatic to clear them.
- Open: why the playlist queue after a previewed song differs from the page order (not investigated; Preview
  no longer depends on it, but a saved pair also only applies when the pair really is adjacent in the queue).

Queue and real-playback checks (17 Nov, over adb):
- Queue vs page: with shuffle off, the Elevator Music queue matches the page order exactly (Play and tapping a row). The earlier
  mismatch was the app's shuffle, which was saved as ON on the phone; it reorders the queue and leaves ExoPlayer's flag false.
  Not a bug in our code. The queue holds 26 songs of the 46 (a bounded window), also unchanged by us. Math Rock (no videos) matched too.
- The Mix header now says so when shuffle is on (saved pairs only apply when the two songs really are adjacent).
- The `queue ahead` log line lists the next 14 songs with shuffle state, tier (C) and video (V) flags.
- Real playback: a saved Rise pair (Flamingo -> Hot Milk) played as `custom` with DJ_FILTER, 21.7 s, not just in Preview;
  the next pair, with nothing saved, logged `custom=none`.
- Preview of Goodbye To A World -> Flamingo (Rise + High-pass, 8 s): starts at 311.0 s, blend at 318.8 s (7.8 s lead),
  lasts 8 s, handoff, finish. Gapless: handoff at 328.46 s of 328.52 s, next song at 0 ms, fade 120 ms (no gap).
- Not visible in logs: the volume and filter ramp values (needs ears).

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

### Saved pairs, keys and the loop mix on the phone (17 Nov, later)

Two pairs were left in the store after "Use automatic" on every Elevator Music row:
- `LM|B5Y6fyMHOOU->w4SNwmWQ7us` (Liked Music, Rise 16 bars): a valid pair. Liked Music's page id
  `VLLM` normalises to `LM`, the two songs are adjacent on that page, and with Mix switched on
  the page shows it. It was invisible only because Mix is OFF on Liked Music (the page shows no
  transition lines then, and playback ignores them). Left in place.
- `PLBCzXsQIQGxo|yXhiTI79tA0->Dbdqj5-FfPk` (HardTekk Music, 2 songs): an orphan by order, not by id.
  The page (`VLPLBCzXsQIQGxo` -> `PLBCzXsQIQGxo`, correct) lists and plays Dbdqj5-FfPk first; the
  pair was saved for the opposite order, so no line on the page ever matched it. Removed.
Page, editor, "Use automatic" and playback all key through `CustomTransitionStore.playlistKey`
on the same playlist id; no mismatch in the key itself. Added: a notice under the Mix switch,
"N saved transitions no longer match this playlist's order" with Remove, for pairs whose two
songs are both on the page but not adjacent (`orphanedPairKeys`, tested). Pairs with a song
missing from the page are left alone (a long playlist may be only partly loaded).

"This output can't take it": not the earbuds. The value `precision=false ch=0 rate=0` is the
port's never-configured default: the sink has not played yet (paused, a queue just restored, or
players rebuilt after an output change; the service reconfigures on every audio device added or
removed, earbuds included). Earbuds active 16 Nov 16:34-19:29 and 17 Nov 10:08-11:28; refusals
at 17 Nov 01:29-01:39, 12:47-12:49 and 13:41 had no earbuds connected, and loop mixes ran on 16
Nov (16:28, 16:35) while they were. The controller now says "waiting on the audio output" for an
unconfigured sink and keeps "can't take it" for a configured but unsuitable one.

Found while checking the custom loop on the phone:
- Preview's seek could be lost: when the pair's first song was already the current item, the seek
  ran on the old queue and the new two-song queue replaced it (Preview then played from 4 s).
  Fixed: it waits for the new queue.
- A stream quality upgrade mid-playback changed the duration by a few ms, which changed the custom
  loop's cache key and re-planned it from the later playhead, losing the loop. The key now uses
  the saved choices only.
- A custom loop saved on Hot Milk -> Flowers (4 bars, ramp locked: 24 BPM apart) ran from start to
  finish in normal playback (loop 201.6 s, handoff 27.66 s). In Preview it once failed with "a sink
  was flushed" 20 s into the mix (24 s of file left, so not the end-of-song case; cause not found)
  and once with "the decks would not build" 3 ms after starting, after a stream upgrade (a
  region decode returned nothing, probably audio not yet in the cache). Both fell back to a plain
  crossfade as designed. Not fixed.
- The editor locks the ramp when the
  songs are more than 8 BPM apart; the loop switch is on when both songs have played through.

### Custom loop mix in the editor (17 Nov): built, tests pass, phone check pending

Answer to "can a custom transition use the loop mix": it could not (the controller skipped
the loop for every custom pair). Now a Mix pair can ask for it: Loop mix switch, loop length
1/2/4 bars, Tempo ramp switch (design doc 4.8.1). Decisions: ramp off holds the outgoing
tempo then steps (only within 8 BPM, else locked on); a loop that cannot be made falls back
to the whole automatic plan (`custom-fallback-loop`).

Analyse playlist does NOT give the loop what it needs: it asks for the opening-window pass
only (tempo, key, a beat grid) and counts that as analysed. The vocal mask, level windows and
tail beats come from the whole-track pass, which needs the song in the cache whole (played
through or downloaded). So the editor enables Loop only when both songs have it
(`TrackAnalyzer.hasWholeAnalysis`) and says "Play both songs all the way through once".
Analyse playlist is unchanged.

Done: `LoopMix.Custom` / `holdTempo` / `estimate`, `CustomTransition.loop*`, controller hook
(plans the custom loop first, waits for lyrics/analysis, falls back), editor controls with a
tempo summary, page row label
"Custom . Loop mix . 2 bars", backup round trip. Shared 253+ and app tests pass.

To check on the phone (the phone was locked): the Loop switch is enabled for two songs that
have played through; Preview of a loop pair logs `plan ... custom=loop/2bars/ramp` and
`loop mix at ...`; a pair with no clean loop near the start logs `custom=fallback(loop: ...)`.

### Flush investigation: where it stands (17 Nov, paused)

Pushed first: both repos at e33ff8e6..4f7ca0e6 (Android) and 7a665f30..0a54ea4d (PC).

Findings so far:
- "The decks would not build" (the common Preview failure, 2 of 4 runs on Hot Milk -> Flowers) is a
  cache problem, not the flush. The loop decks are decoded from the cache and `chooseRendition` needs a
  COMPLETE cached copy; a song that was seeked into, or whose playing rendition is the `#hifi` upgrade
  (only the first 1 MB cached), has none, so `loopRegion` returns null 3 ms after arming. The failing
  runs have no "Loop region ... from ..." line; the passing ones do (standard rendition fetched whole,
  [0, 4401978)). The planner only asked for the stored analysis, never for a readable copy, and the
  failure then dropped the pair to PLAIN_CROSSFADE for the session.
  Fix built, NOT yet checked on the phone: `TrackAnalyzer.hasLoopCopy` + a controller check
  (`loopCopyFor`, re-asked every 3 s) so a missing copy reads "waiting on <song>'s audio in the cache"
  and is no failure. Tests pass; not committed as verified.
- The mid-mix flush (Dream, Ivory 16 Nov; one Preview 17 Nov) is still unexplained. Both came about 20.5 s
  after takeover with the end held and 24 s of file left. No fixed ~20 s constant in the loop code
  (LOOP_* constants, SHIFT_MARGIN_US 300 ms, region pre/tail 1 s / 3 s, stopAt = elementAt + 2 s).
  In six Preview runs since (4 with the stack logging) none flushed: 2 build failures, 4 clean passes,
  including one with a quality-upgrade swap 3 s before arming. So a swap before arming does not cause it.
- Added: PrecisionAudioSink logs "loop mix sink flush/reset mid-mix" with the sink (outgoing/incoming),
  position, lead and the CALLER'S STACK whenever a sink is flushed during a mix. The next flush will
  name who called it.
- The incoming deck ends -2.2 to -2.3 s off the outgoing deck at the handover on Hot Milk -> Flowers every
  time (the incoming start is clamped at 0, so 15.6 s of silence is inserted and the third correction
  overshoots); Flamingo -> i don't belong here ended 0.05 ms off. Noted, not investigated.
- Dream, Ivory -> I Can't Say Goodbye not reproduced: the songs are not in the library lists I could
  reach; try Search or listening history.

Next: check the readiness fix on the phone (Preview x3: expect no "decks would not build", and a plain
blend with "waiting on ... audio" when the copy is missing), keep Previewing until a flush gives a stack,
find Dream, Ivory.

### Loop mix flush near the song end: my first explanation was wrong (corrected 17 Nov, evening)

What I wrote earlier today (and in commit 4612185c): the handover lands after the outgoing file's
end, so the file ends first and the sink is flushed; fixed by requiring the handover to land 6 s
before the file's end. That was wrong, and the guard it added made things worse:
- Flamingo -> i don't belong here used to loop-mix and the guard refused it: the log said
  "1 too late (the mix would outlast the song)". Its only clean loop is at 173.3 s of 197.5 s and
  hands over at 200.1 s, 2.6 s AFTER the file ends. With the guard off it ran cleanly: handoff
  fired with the outgoing player at 200148 ms of 197528 ms, done, incoming 0.2 ms off the deck.
  (The end is held for the whole mix, "end held at 36137ms left".)
- The measured sink lead on the phone is 0.4-0.6 s, not the 3.7 s I took it to be.
- The two real flushes line up with each other, not with the file: Dream, Ivory 16 Nov (loop
  164.03 s, flush at 184.56 s, end held at 36 s left, 3.7 s of file left) = 20.57 s after takeover;
  the Preview run today (loop 201.60 s, flush at 222.12 s, 24 s of file left) = 20.52 s after
  takeover. Runs that passed that point (Hot Milk -> Flowers in normal playback, Flamingo ->
  i don't belong here) did not flush.
So the guard is removed (commit after this one's parent); the planner takes the latest clean loop
again. The flush about 20.5 s into some mixes is still open and is the Preview investigation.
Kept: the `loop trace` lines (takeover, handoff, stop) now also print the sink lead, and
`LoopMixPort.sinkLeadUs` carries it.

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
