# Smarter Automix and on-device ML: plan

Status: plan only (18 Nov 2026). Nothing here is built. It starts after the loop-mix flush investigation
(see `docs/PROGRESS.md`).

## The request

1. Replace the fixed fallback ladder (loop mix, then beat-matched blend, then plain crossfade) with
   candidate transitions generated per pair (loop mix, beat-matched blend, filter sweep, echo-out on the
   last beat, downbeat cut, gapless), scored on tempo gap, key compatibility, vocal overlap, phrase
   boundaries, energy and time left, with the best one picked.
2. Add echo-out as a new transition for big tempo gaps when no clean loop exists.
3. Optionally learn from skips during or right after a transition and from the custom transitions the
   listener saves.
4. Keep the existing safety rules and the failure record, log the scores and the winner, and keep the
   current behaviour behind a setting so the two can be compared.
5. On-device ML: a vocal-activity model to replace the play-through / lyrics requirement for the vocal
   mask (reused later for a karaoke slider), and Beat This! for beats and downbeats. Analyse once per
   song, cache the results, download models on demand. The brief names LiteRT with the NPU, falling back
   to GPU and then CPU.

## What exists today (read from the code, 18 Nov)

- **Planner.** One fixed ladder in `planTransitionInner` (`TransitionPlanner.kt`), with tiers in
  `TransitionPolicy.kt`. Styles: `EQUAL_POWER`, `GAPLESS`, `DJ_BLEND`, `DJ_FILTER`. The loop mix is planned
  separately in `CrossfadeController` (`loopPlanFor`), and the failure record (`TransitionFailureLog`) sits
  outside the planner.
- **Echo-out.** `CrossfadeController.rideEchoOut` and `FilterTransitionVariant.ECHO_RIDE` exist and are
  used by Mix on skip and the Quick style. The planner never chooses it: `echoSeconds` is fixed at 0.
- **Models.** ONNX Runtime 1.28 on the CPU only (`BeatTracker`, `VocalTracker`: intra-op threads, no
  execution provider). Bundled in `app/src/main/assets`: `beat_this_final0_q8.onnx` (23 MB) and
  `vocals_umxhq_int8.onnx` (9 MB, Open-Unmix). There is no karaoke feature.
- **The play-through requirement is about audio access, not inference speed.** The whole-track analysis
  (`TrackAnalyzer.analyze`) needs a complete cached copy, and only that pass produces the vocal mask and
  level windows. Loop regions are decoded from a complete copy too (`chooseRendition`), which is why a
  song that was seeked into, or is on the `#hifi` rendition, cannot be looped ("the decks would not
  build"). What the loop mix really needs is the last 60 s and first 45 s of each song.
- **Window loader.** The custom-transition editor already analyses the two end windows from ranged
  fetches (header plus the window; the tail is stitched into a standalone WebM, WebM only today).

## A. Smart Automix

### Setting

`Smart Automix`: **Off** (default: today's ladder, unchanged), **Shadow** (the ladder plays, the scorer
only logs what it would have picked) or **On** (the scorer's winner plays).

### Candidates

Loop mix, beat-matched blend (`DJ_BLEND`), filter sweep (`DJ_FILTER`), echo-out (new), downbeat cut (new),
gapless, and plain crossfade as the floor.

### Hard vetoes (never scored away)

The existing guards R0-R2, speech / live, video rows, Listen Together, bit-perfect output, the failure
record (including the pair-level PLAIN_CROSSFADE and NONE verdicts), and readiness: the audio output, a
complete cached copy where the candidate needs one, analysis, lyrics-or-mask for loops. A vetoed
candidate is dropped with a reason code and is listed in the log. Custom transitions keep their place in
the rule order (design doc 4.8): after R0-R2 and the failure gate, ahead of the planner.

### Scoring

Each feature is scored 0..1, then a weighted sum with a small per-candidate prior.

| Feature | What it measures |
|---|---|
| Tempo gap | octave-folded ratio of the two tempos |
| Key compatibility | distance on the Camelot wheel |
| Vocal overlap | sung stretches of each song inside the candidate's overlap region |
| Phrase fit | distance of the start and end to downbeats and phrase boundaries |
| Energy | outgoing energy at the exit against incoming energy at the entry |
| Time left | room for the candidate to finish before the file ends, plus its arm lead |
| Risk prior | a loop mix costs more to arm than a blend |

Weights start hand-set and are calibrated against fixtures 001-023 and the listener's saved custom
transitions. The scorer is pure shared code (`shared/commonMain`), with golden tests and new fixtures
(`androidOnly` until the PC port catches up).

### Echo-out (new)

For a big tempo gap when no clean loop exists. An echo hit on the outgoing song's last phrase beat
(delay at most `MAX_ECHO_SECONDS`, tail from `echoTailSeconds`) while it fades; the incoming song enters
on a downbeat after the tail, with no tempo stretch. Built on the existing `rideEchoOut`; the planner
gets a variant that sets `echoSeconds` and the entry downbeat.

### Downbeat cut (new)

No overlap: the outgoing song ends on a downbeat or phrase boundary and the incoming song starts on one.

### Logging

One line per pair under `BitChordTransitions`: every candidate with its total, its components or its
veto, and the winner. Reason codes become `smart-<kind>`. In Shadow the line also carries
`legacy=<what the ladder chose>`, so the two can be compared without changing what plays.

### Learning (optional, last)

- **First, collect only.** Log skips during or just after a transition (against that transition's
  kind), completed transitions (mildly for it) and saved custom transitions (a strong label).
- **Then,** small clamped on-device weight nudges.
- **Privacy:** on device only, included in the backup, **off by default**.

### Order of work

1. Extract the candidate seams from the planner; a Shadow-mode scorer with tests.
2. Echo-out and downbeat cut as candidates.
3. Setting **On** executes the scorer's winner.
4. Learning, last.

## B. On-device ML

### 1. Analyse the end windows from ranged fetches (first)

Removes the play-through requirement whatever the runtime. Reuse the window loader: decode the 60 s tail
and 45 s head, run beats, vocal activity and level on them, save them per song (extending
`MixWindowStore`), and decode loop regions from the same windows instead of a complete copy. Then
"Analyse playlist" can make songs loop-ready. M4A / AAC streams need their own container handling
(WebM only today).

### 2. Runtime spike before any migration

Benchmark on this phone with an output-parity check (beat F-measure, vocal-mask agreement):

- ONNX Runtime on the CPU (today's baseline).
- ONNX Runtime with NNAPI or QNN.
- LiteRT on the NPU, then the GPU, then the CPU.

Migrate only if it is at least 2-3x faster or clearly better on battery; analysis runs once per song, so
speed matters less than the audio-access change above. Either way put an `InferenceBackend` interface in
front, probe the device, fall back NPU -> GPU -> CPU, sanity-check the outputs (shape, NaN), and remember
the choice per device. Conversion risks: Beat This! (convolutions plus a transformer) and Open-Unmix
(LSTM) may not convert cleanly or may run poorly on an NPU.

### 3. Vocal-activity model

- **For gating loops:** a small fast vocal-activity network, distilled from today's Open-Unmix mask (the
  teacher), 1-2 MB, output in the existing `vocalWindows` format so the planner and loop mix are
  unchanged.
- **For karaoke:** real separation is needed, so keep Open-Unmix for that; share only the hooks.
  Karaoke is its own feature, later.

### 4. Beat This!

Already integrated. Run it on the windows; key every cached result by model version and runtime so an
upgrade re-analyses lazily.

### 5. Models on demand

A `ModelStore`: manifest (name, version, SHA-256, size, minimum app version), Wi-Fi only by default,
resumable, verified, moved into place atomically; fall back to a bundled copy where one is kept.

## Risks and checks

- **Licences.** The Beat This! weights are MIT. The Open-Unmix pretrained weights may carry a
  non-commercial licence: confirm before any upstream PR (the app already bundles them).
- **NPU fragmentation** across SoCs; always keep the CPU path.
- **APK size and thermals** if two runtimes ship.
- **Accuracy regressions** from quantisation or conversion; gate on the parity check.

## Decisions needed

1. Runtime: spike first and keep ONNX Runtime unless LiteRT clearly wins, or commit to LiteRT now?
2. Vocal model: a small gating model plus Open-Unmix for karaoke (recommended), or one separation model?
3. Bundling: keep Beat This! bundled and download only the vocal models, or download both?
4. Open-Unmix weight licence: confirm.
5. Learning: opt-in only?
