# Issue #576 compose-measure experiment: evidence index

> Status: evidence record (research, Diagnostic). Spec stays `accepted`, not implemented.
> Executed: 2026-10-15. Head: `2a6bcb3188c2ceecd6e5f606d66aa1bd98edfa04`.
> Contract: [spec 576](../../../specs/576-compose-measure-diagnosis/spec.md) (AC-1/AC-2/AC-3),
> original evidence: [discovery](./../issue-576-compose-measure-discovery.md).
> Outcome: 5 baseline PASS + 5 candidate PASS, original signature NOT reproduced,
> classification stays class 6 unknown. Evidence PR uses `Refs #576`, never `Closes`.

## Documents in this directory

| File | Content |
|---|---|
| `baseline-results.md` | 5 baseline runs: verbatim 9-line XML per run plus source/head/APK hashes, start/end/exit, verbatim `BUILD SUCCESSFUL` line, TestRunner start/finish lines |
| `candidate-results.md` | 5 candidate runs, same receipt shape, plus overlay reference |
| `candidate-overlay.diff` | Exact LOCAL overlay applied for the candidate arm (1 line), restored afterwards |
| `../issue-576-compose-measure-experiment.md` | Experiment record: matrix, AC assessment, limits, teardown |

## Common invocation (all 10 runs, identical)

```bash
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false \
  connectedLawnWithQuickstepGithubDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest#staleApplyAttemptExplainsOutcomeAndNextStep'
```

Gradle exit `0` on all 10 runs. Each run executed the single existing method
`staleApplyAttemptExplainsOutcomeAndNextStep` with existing assertions unchanged.

## Common environment

- Host: macOS arm64, JDK 21.0.12. Original CI was x86_64; image build ID matches
  (`BE2A.250530.026.F3/13894323`), arch differs and is recorded as a difference.
- Device: dedicated owned AVD `nunu_issue576_api36_20261015` on `emulator-5558`,
  API 36, `google_apis` `arm64-v8a`, fingerprint
  `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`,
  `pixel_7_pro` 1440x3120 560dpi.
- Boot flags: `-gpu swiftshader_indirect -memory 2048 -cores 2 -no-window
  -no-snapshot -no-boot-anim`; animation scales 0 on all three globals, both arms.
- Order: sequential, 5 baseline then 5 candidate. Cold boot per arm. NOT
  randomized or counterbalanced, so no performance causality is claimed from
  timing differences.
- AVD was shut down between arms for the candidate build (host memory); same
  AVD and disk, no wipe.

## Receipt legend (what each run entry holds)

- Full original 9-line result XML, fenced verbatim, plus its SHA-256.
- `head.txt` (source HEAD), `test-blob.txt` (test-file git blob hash), APK
  SHA-256 lines, `start.txt`/`end.txt` (UTC), `exit.txt`.
- Actual `BUILD SUCCESSFUL in ...` line, quoted verbatim from that run's
  `gradle.log`, plus that log's SHA-256.
- Relevant `TestRunner started:` / `finished:` lines from the Gradle-produced
  scoped per-test logcat under `results/`, plus that file's SHA-256 and line count.

## What is NOT in this repo (by design)

- Full raw `gradle.log` archives (~850 lines each, mostly compile boilerplate)
  are not committed. Log receipts are selected excerpts; each run's raw-log
  checksum is published so the excerpt source is pinned.
- The global ~3000-line `logcat.txt` per run is omitted for data minimization;
  scoped per-test logcat only.
- One redaction: a single `file:///...` problems-report line in
  `baseline-1/gradle.log` contains the operator's local checkout path. It is not
  quoted here; the redaction is called out and the raw-log checksum covers the
  original.

## Build history (exact facts from scratch logs)

- Foreground build timed out (120s + 600s) before tests; those were build
  timeouts, not test failures.
- Emulator off plus serial workers: `BUILD SUCCESSFUL in 21m 15s` (main + test
  build, `issue576-build-serial.log`).
- Candidate-only rebuild: `BUILD SUCCESSFUL in 1m 9s`
  (`issue576-build-candidate.log`).
- Overlay restored after the experiment: `git diff --exit-code -- tests lawnchair/src`
  passes; working tree holds only these evidence documents.
