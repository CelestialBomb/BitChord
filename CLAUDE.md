# BitChord Android: beat-aware transitions branch

This branch is worked on by both Claude Code and Cline. `.clinerules` holds the
same instructions for Cline; change both together.

## At the start of every session

1. Read `docs/transitions-design.md` (the spec; identical in the PC fork).
2. Read `docs/PROGRESS.md` (where the work stands, what is next).
3. Run `git log --oneline -15` and `git status`.

Uncommitted changes may be the other agent's unfinished work: review them
before building on them, and don't discard them.

## After each step

1. Update `docs/PROGRESS.md`: what changed, what was verified and how, what is
   next, open questions.
2. Commit, in small steps, with the same prefixes as the history:
   `feat(transitions):`, `fix(transitions):`, `test(transitions):`, `docs:`, `ci:`.

## Rules

- Never commit keys, tokens, add-on URLs, keystores or `local.properties`.
  They stay in `.gitignore` or GitHub secrets. CI signs nothing.
- Off means unchanged: with "Beat-aware transitions" off the app must behave
  exactly like v1.9.1 (design doc section 1).
- Keep the AGPL headers on files ported from Orchard (design doc section 8).

## Commands

- Shared tests: `./gradlew :shared:jvmTest -Pbitchord.bundleWear=false`
- App tests: `./gradlew testDevDebugUnitTest --init-script .github/ci/skip-upstream-locale-test.init.gradle -Pbitchord.bundleWear=false`
- Dev APK: `./gradlew assembleDevDebug -Pbitchord.bundleWear=false`
  (output in `app/build/outputs/apk/dev/debug/`)
- In PowerShell, quote the property: `'-Pbitchord.bundleWear=false'`,
  otherwise it is split at the dot.

## Device

- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; phone serial `R3GL10HRFBK`.
- Install: `adb -s R3GL10HRFBK install -r <apk>`
- Transition logs: `adb -s R3GL10HRFBK logcat -s BitChordTransitions BitChordCrossfade`
  (`BitChordTransitions` has one line per plan decision, arm, bail, cancel and failure).
