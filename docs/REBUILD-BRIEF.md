# BitChord Transitions: Rebuild Brief (v2)

Purpose: rebuild the transitions work cleanly on a new branch from the v1.9.1 base, using everything learned on the `transitions` branch. Read this whole file, plus CLAUDE.md, docs/PROGRESS.md and the design doc, before planning anything.

## 0. How to rebuild

- **Branches:** create `transitions-v2` from the v1.9.1 base. Keep the old `transitions` branch as the reference: do not delete it, rewrite it or force-push it.
- **Stages:** rebuild in the order of section 6. For each stage:
  - Plan first and wait for my "go".
  - Port what was verified on `transitions`, and rewrite what's messy.
  - Keep the existing tests and fixtures (001–023) and add new ones.
  - All tests pass after every commit.
  - Build and install the dev app, then give me a short phone checklist.
- **Lean code:** no dead code, unused settings or unused strings, and no stale diagnostics. Debug hooks and heavy logs are dev-build only.
- **Controller structure:** one clear state machine, split by concern: planning, loop mix, skip blend, custom transitions, failure gate, and status/UI.
- **Original-app bugs:** problems in the v1.9.1 code itself (the shuffle flag, sink defaults, the quality-upgrade swap) get minimal, clearly commented fixes.
- **Docs:** carry CLAUDE.md, .clinerules, PROGRESS.md and the design doc over, updated. Bump the design doc to 0.7. Keep docs/smart-automix-plan.md if it exists.

## 1. Rules

- **Secrets:** never commit keys, tokens, addon URLs or keystores. Run a secrets scan before every push.
- **Commands:** git, adb.exe and gradlew only.
- **Dev app and phone:**
  - Test on `com.dev.bitchord`, not the regular `com.music.bitchord`.
  - The phone is an SM-S948U1, serial R3GL10HRFBK.
  - Before on-device checks, ask me to unlock it and turn on Stay awake.
  - **Testing:** you may change app settings during tests, but tell me each change and restore everything afterwards.
- **Wi-Fi only:** respect the Wi-Fi-only setting for every fetch and analysis.
- **Licences:**
  - BitChord is GPL-3.0. The planner code ported from Orchard is AGPL: keep its notices, and flag compatibility before any upstream PR.
  - Beat This!: code and weights are MIT.
  - Open-Unmix: the bundled model is umxhq, which is listed as MIT. Verify that against the upstream record. Never use UMX-L, which is non-commercial.

## 2. Every issue I reported, in order

1. **Speed:** transitions felt slow. I want them quicker, and adjustable like Spotify.
2. **Queue / Play next:** Automix "tweaked out" on Dream, Ivory → I Can't Say Goodbye (unmuffled) by ios. The last ~10 s of Dream, Ivory looped.
3. **Pairs that don't blend:** some DJ transitions don't blend well. I want a custom transition per pair, saved per playlist.
4. **Albums:** albums should be gapless. Juelz's album DRAMATICA still crossfaded with Automix on.
5. **Removing from Play next:**
   - The mix still happened, but the overview visual didn't show.
   - Later, a "will not start automix" status showed even though it still transitioned.
6. **Overview popup:** a rare visual glitch in the Automix overview popup. Low priority.
7. **Mix on skip:** pressing Next or swiping mid-song should blend into the next song.
8. **Cleanup:** remove dead and unused code.
9. **Backup:** custom transitions must be in the in-app backup/export.
10. **Waveforms:** the editor's waveforms shouldn't need whole-song downloads.
11. **Failed pairs:** a pair that failed before must still accept a custom transition right away.
12. **Custom loop mix:** I love the automatic loop mix (Flamingo → i don't belong here, 89 → 120 BPM over 13 bars). I want it customizable in the editor: loop on/off, 1/2/4 bars, tempo ramp.
13. **No regression:** that same pair later lost its loop (a plain 6 s crossfade). That must not happen again.
14. **Smarter Automix:** choose the best transition per pair (loop, echo-out, etc.).
15. **Phone AI hardware:** use the NPU to make analysis smarter.
16. **Analyse speed:** Analyse playlist is slow. That's acceptable.
17. **Automix setting:** Automix was found OFF one morning, and I'm not sure I turned it off. Check for settings resets. *Closed 19 Nov: I turned it off myself; not a bug.*
18. **Tempo & pitch:** speed and pitch controls like OuterTune / InnerTune / OpenTune.
    - "Tempo & pitch" in the player menu: speed 0.5–2×, pitch ±12 semitones.
    - A link switch: on = speed changes pitch, like a record.
    - Reset.
    - Global for all songs. A badge on the player when not 1× / 0 st, with one-tap reset.
    - When: section 7, right after the rebuild.
19. **Album Play shuffled:** Play on an album or playlist page should play in order (turning the app's shuffle off). Only the Shuffle button shuffles.
    - v1.9.1 behaviour: Play takes the app's saved shuffle state, so album gapless never applies once shuffle has been on.
    - When: stage 5, as a minimal, commented original-app fix.

## 3. Features built on `transitions` (port these)

### Engine

- **Planner:**
  - Beat-aware planner: TransitionPlanner, TransitionPolicy, TrackAnalysis and BeatTracker.
  - Rules R0–R9, reason codes, `isTransitioning()`.
  - Fixtures in docs/transitions-fixtures.
- **Tiers:**
  - Plain crossfade, DJ-assisted and beat-matched.
  - Double and half tempos fold together (192 vs 96 BPM is the same pulse). Keep that test.
- **Controller:**
  - Phases: IDLE / ARMING / LOOP_ARMING / LOOP_RUNNING / FADING.
  - 250 ms re-plan tick, BAIL_MS = 120.
- **Failure record:**
  - Kept per pair, with verdicts NORMAL → PLAIN_CROSSFADE → NONE.
  - Cleared by editing, saving, resetting or previewing the pair, or by pressing Previous.
- **Queue changes:** a queue-change hook (PLAYLIST_CHANGED → onQueueChanged), a provisional dimmed window, and Previous resets.
- **Album gapless:** album-ID match and in-order detection, only with shuffle off.

### Stage 1

- **Styles:** Smooth, Fade, Rise, Quick.
- **Blend length:** Short ×0.5, Medium, Long ×1.6 (applyMixStyle).
- **Mix on skip:**
  - Waits up to 500 ms for a beat.
  - Rapid skips under 1.5 s cut straight through.
  - Previous stays instant, and queue taps don't blend.
  - Codes: skip-blend, skip-fade, skip-cut-busy, skip-cut-rapid, skip-cut-guard.
  - The skip blend publishes its overview, so the visual shows.

### Automatic loop mix

- **What it does:**
  - Loops clean (instrumental) bars of the outgoing song and loops the incoming song.
  - Ramps the tempo and holds the outgoing song's end.
- **Loop choice:** it takes the latest clean loop.
- **No end-of-song guard:** see section 4 for why it was removed.

### Stage 2: custom transitions per playlist

- **Model:**
  - Mode: MIX / GAPLESS / NO_MIX.
  - Style, bars, start, bass swap, and the filter (none, low-pass or high-pass) with an amount.
  - The beat the editor measured.
  - loop, loopBars (1/2/4) and loopRamp.
  - Every field has a default, so old saves still load.
- **Store:**
  - JSON in SharedPreferences, keyed `<playlistKey>|<outId>-><inId>`, plus a Mix flag per playlist.
  - VLPL… and PL… normalise to one key.
- **When it applies:** only when all of these hold:
  - Automix is on, and Mix is on for that playlist.
  - Both songs play back-to-back in that playlist's order.
  - Neither song comes from Play next, Add to queue or AutoPlay, and neither is a video row.
- **Failure gate:** Gapless and No mix skip it while the song lengths still match the save.
- **Reason codes:** before-custom, custom, custom-gapless, custom-no-mix, and the fallbacks custom-fallback-duration, -window, -late, -loop and -loop-late.
- **Exit filter:** it adds to the style's own filter move rather than replacing it.
- **Custom loop:**
  - Planned at play time: the clean loop nearest the frame, within about 2 bars.
  - Needs both songs fully analysed (vocal mask). Until then the switch stays off with "play both songs all the way through once".
  - Ramp off is allowed only within 8 BPM: it holds the tempo, then steps at release.
  - If no loop fits, it falls back to the whole automatic plan.
- **Backup:**
  - An optional custom-transitions section in the export.
  - Restore replaces, an empty section clears, and older files leave transitions alone.
  - 7 tests.
- **Window loader:**
  - Order: what was saved with the song, then the playback cache, then a ranged fetch of the header plus the ~45 s window.
  - The fetched window is rebuilt as a standalone WebM. WebM only, 3 MB cap, Wi-Fi only.
  - Results are saved per song.
- **Playlist page:**
  - Mix switch, on YouTube Music and local playlists only.
  - Notes when Automix is off or shuffle is on.
  - BPM · key on rows.
  - Transition lines, shown only in the playlist's own order.
  - Analyse playlist: 4 songs at a time, Wi-Fi only. It reads tempo, key and the beat grid from each song's opening.
  - An orphan-pair notice with a Remove button.
- **Editor sheet:**
  - Mix / Gapless / No mix.
  - Waveforms: the last 45 s of the outgoing song and the first 45 s of the incoming one.
  - A draggable frame that snaps to beats.
  - Style, length, bass swap, filter and amount, plus the loop controls.
  - Use automatic / Preview / Save, pinned at the bottom.
  - Try again / Download both songs when the waveforms fail.
- **Preview:**
  - Plays a two-song queue of exactly that pair.
  - Starts ~8 s before the blend (~25 s for loops), using unsaved settings.
  - Waits for the new queue before seeking.

### Status and logs

- **Status wording:**
  - "waiting on the audio output" for an unconfigured sink, for example after a device change.
  - "can't take it" only for a configured but unsuitable sink.
  - "waiting on … audio in the cache" before arming a loop that needs a region decode.
- **Logs:** TRANSITION_TAG lines; queue-ahead (the next 14 songs, with shuffle state, tier and video flags); sink lead; stop-site lines. Keep the useful ones and make the heavy ones dev-only.

## 4. Fixes tried: what worked, what was wrong

| Problem | What we tried | Result |
|---|---|---|
| Dream, Ivory loop retry | Failure record + forward-only settle + Previous reset | Worked for the retry loop |
| Near-end loop failure | Assumed "handover after file end", added a 6 s end-of-song guard | **WRONG.** It blocked good loops (Flamingo). Removed. The outgoing end is held, so mixes survive past the file's end. |
| The real flush | Stack trace: the standby (incoming) player was stopped mid-mix by ExoPlayer.stop(), ~13–20.5 s into the loop | **OPEN** (see below) |
| Queue change visual | onQueueChanged + provisional window + skip blend publishes its view | Worked |
| Album gapless | Album-ID match, in-order, shuffle off | Worked |
| Preview seek lost | Wait for the new queue | Worked |
| Preview played the wrong pair | Two-song queue | Worked |
| Editor length wrong (8 s vs 10 s) | Save the beat the editor measured | Worked |
| Tail window wouldn't decode | Rebuild a standalone WebM | Worked (WebM only) |
| Loop lost after a stream upgrade | Loop cache key uses only the saved choices | Worked |
| "Decks would not build" after a stream upgrade | Cache check before arming | Built, not yet verified |
| A failed Preview blocks the pair | Preview failures shouldn't go into the failure record | Requested; check its status |
| Queue ≠ page order | It was the app's own shuffle flag | Not our bug; shuffle note added |
| Hidden saved pairs | A Liked Music pair (Mix off there) and a HardTekk orphan | Orphan notice added; not a key bug |
| "Can't take it" refusals | Not the earbuds; it was an unconfigured sink | New wording |

**The open flush:**
- **What's known:**
  - The one stack trace shows the standby (incoming) player stopped about 13 s into a loop mix, ending in stopInternal.
  - No playback error appears in that log, so the stop most likely came from app code.
- **Weaker suspect than first thought:** the quality upgrade swaps in place on the playing player, and it already waits for a running transition or shelves itself.
- **How to prove the cause:** stop waiting for a random flush (scripted UI taps were unreliable). Build a dev-only adb broadcast hook that fires each suspect mid-mix, one per broadcast:
  - stop the standby player
  - an in-place item replace, like the upgrade
  - a version swap
- **Then:** the run that flushes names the culprit through the stop-site log line (already on `transitions`, commit 55713bb2). Fix that path and prove it with the same broadcast.
- **Related work on `transitions`:**
  - 68e6e71f: the flush diagnostics and the cached-copy check.
  - docs/smart-automix-plan.md: the full plan for section 7.

## 5. Known open items

- **The flush:** first priority (section 4).
- **Preview failures:** the flush and build failures in Preview likely share that root cause, the stream-quality upgrade.
- **Flaky lyrics:** they make the planner refuse loops it would otherwise take.
- **Handover offset:** the loop mix logs "incoming −2243 ms at handover". It sounds good, so only investigate if it causes problems.
- **Queue window:** the queue holds 26 of 46 songs. Check that the app keeps loading the rest. This is probably the original app's behaviour.
- **Automix setting:** Automix was found OFF one morning. Check for settings resets or migrations.
- **Overview popup:** the rare Automix overview popup glitch.

## 6. Rebuild order

1. **Base and engine:** planner, rules, failure record, queue hook, album gapless. Bring the fixtures and tests along.
2. **Stage 1:** styles, length, Mix on skip.
3. **Loop mix:** the automatic loop mix, plus the flush fix, proven with the forced swap.
   - Also in this stage:
     - **Flush investigation:** a dev-only adb hook that fires each suspect mid-mix (section 4).
     - **Failure rule:** a failed loop mix only drops the loop (a normal crossfade next time); only a failed plain crossfade drops to no mix.
     - **Item 7 lead:** after Previous, the loop's clock reads past the loop start right after arming ("the sink never reached the loop").
     - **Item 2 retest:** the Quick Picks pair that got no mix after the app restarted.
   - **Transition kinds as pieces:** keep loop mix, beat blend, filter, echo-out, gapless and crossfade as separate pluggable pieces with a common shape, so Smart Automix can score them later. No scoring yet.
   - **Tempo design:** a user speed multiplier (issue 18) stacks with the mix's tempo changes. The planner uses the effective BPM, and a pitch shift updates the effective key.
4. **Stage 2:** custom transitions (store, backup, loader, page, editor, Preview, custom loop).
5. **Cleanup and review:** a cleanup pass, then an independent review (/ultrareview).
   - Also: issue 19, album Play in order (a minimal, commented original-app fix).
6. **New features:** section 7.

## 7. Future features (after the rebuild)

1. **Tempo & pitch** (issue 18):
   - Player menu: speed 0.5–2×, pitch ±12 semitones, a link switch (speed changes pitch like a record), reset.
   - Global for all songs; a badge on the player when not 1× / 0 st, with one-tap reset.
   - Mixes stack with it (stage 3 tempo design).
2. **B1, window analysis:**
   - Analyse each song's ends from ranged fetches: beats, vocal and level on the last 60 s and first 45 s.
   - Analyse playlist then makes songs loop-ready.
   - Add M4A/AAC support.
3. **Smart Automix:**
   - Setting: Off / Shadow / On.
   - Candidates: loop mix, beat blend, filter sweep, echo-out, downbeat cut, gapless, plain crossfade.
   - Hard vetoes.
   - Scoring: octave-folded tempo gap, Camelot key distance, vocal overlap, phrase fit, energy, time left, risk.
   - Echo-out: a new planner variant reusing rideEchoOut.
   - Logging: one log line per pair.
   - Learning: from skips and saved custom transitions; opt-in, on-device and included in backup.
4. **On-device ML:**
   - Runtime spike: ORT on the CPU vs ORT with NNAPI/QNN vs LiteRT on the NPU/GPU. Keep ORT unless LiteRT is 2–3× better.
   - An InferenceBackend that falls back NPU → GPU → CPU.
   - A small vocal-activity gate, distilled from Open-Unmix.
   - Beat This! stays bundled.
   - A ModelStore for downloads: manifest, SHA-256, Wi-Fi only, resumable.
5. **Karaoke:** a vocal slider using Open-Unmix umxhq, downloaded on demand.
6. **PC catch-up** (BitChord-PC-Continued, branch `transitions`): port the spec, real blend timing and Beat This! ONNX.
7. **Upstream:** translations, then the upstream PR. A thin Morphe patch is optional.
8. **Search/recommendations:** last, or skip.
