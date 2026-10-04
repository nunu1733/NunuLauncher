# Assessment: Issue #172 — Organizer input-unavailable diagnostics (AC-3 episode reproduction)

> Status: implemented (AC-1/2/4/5/6/7 in PR #184, AC-3 this record)
> Audit date: 2026-08-31
> Build: `15.Dev.(50ddb86)` debug, merged main `50ddb86148`
> Environment: emulator `nunu_qpr2_api36_1` (API 36.1, `sdk_gphone64_arm64`), serial `emulator-5554`
> Raw evidence (not committed): `/tmp/issue172/` — full logcat, exported journal, pulled `launcher_5_5_5.db`, screenshots

## Outcome in one line

The #171 one-off episode is **reproduced with a named reason**: post-Nova-restore organizer runs fail capture with `INPUT_NOT_READY / INPUT_READINESS.CAPTURE_INVALID` (two consecutive failures in the restore process, persistent in later processes), the new debug capture-failure line names the exception class (`IllegalArgumentException`), and the exact throw site is identified by evidence-chain analysis below — so a focused fix Issue is split.

## Prerequisite (per #168/#171 harness)

- Nova backup `backup.novabackup` (1.63 MB, dated Aug 29 — the #171-era file found on the emulator) restored through Lawnchair's built-in **Restore Nova backup** route (the same one-pass authoritative import path validated by #168/PR #169).
- Restore completed in process `6528` at 19:32:45: `NovaBackupConverter: Committing converted grid to prefs: rows=5 columns=5 hotseat=5`, `IDP: applyGridInfo: dbFile launcher_5_5_5.db (grid 5x5 h5)`. Unmatched `launcher_5_4_4.db` deleted — the imported workspace is authoritative.
- The restore path itself replaced the process (`6528` → `7391`), matching #171's note that a later observation succeeded after process replacement.
- Baseline before restore (same build, fresh 4x5 workspace): organizer run `RUN_STARTED → CAPTURED → PLANNED (captured=1, preserved=1) → APPLY_NO_CHANGES` — composer healthy.

## Reproduction (named reason)

| Time (device) | Process | Event trace (OrganizerDiag) |
|---|---|---|
| 19:17:43 | 6528 (pre-restore) | `RUN_STARTED` → `CAPTURED` → `PLANNED captured=1 preserved=1` → NoChanges |
| 19:40:20 | 7391 (post-restore, model loaded) | `RUN_STARTED` → `phase=CAPTURE exceptionClass=IllegalArgumentException` (debug line) → `INPUT_NOT_READY err=INPUT_READINESS.CAPTURE_INVALID` |
| 19:41:31 | 7391 (retry, same process) | identical: `RUN_STARTED` → capture failure → `INPUT_NOT_READY … CAPTURE_INVALID` |
| 19:52:52 | 8025 (later process) | identical failure — **persistent, not one-off** |

Two consecutive failures in the restore process with the model loaded reproduce the exact #171 episode pattern. The journal (exported via `run-as`) now closes each run with the terminal record the pre-#172 build never wrote:

```json
{"schemaVersion":1,"journalSequence":10,"runId":"6e7e0dec…","trigger":"MANUAL_FULL","runMode":"FULL_ORGANIZATION","phase":"INPUT_NOT_READY","error":{"family":"INPUT_READINESS","code":"CAPTURE_INVALID"}}
```

The UI shows the new bug-report copy ("Required organization information is unavailable. Nothing was changed. If this keeps happening, please report a bug.") with retry — the AC-4 copy split.

## Root cause chain (evidence-verified)

1. The 5x5 grid (`hotseat=5`) makes Lawnchair's QSB reservation cover the **entire first workspace row** of screen 0: `LauncherLayoutAdapter.captureWorkspaceContext()` reserves `GridCell(0,0) × GridSpan(numSearchContainerColumns, 1)` when `topQsbOnFirstScreenEnabled`, and `numSearchContainerColumns = dbGridInfo.getNumHotseatColumns()` (= 5) for non-multi-display (`InvariantDeviceProfile.java:415-416`).
2. The Nova import (with "Add extra row to show At a Glance" **off**) placed an item **inside** that reserved row: favorites row **115** (`itemType=6` DEEP_SHORTCUT, "この曲なに？", `com.google.android.as` ambient-music shortcut) at `screen=0 cell(2,0) span(1,1)` — pinned by `RestoreDbTask` in the restore log and by the pulled `launcher_5_5_5.db`.
3. `RowManifestCodec.capture` fail-closes exactly this condition: `require(reservations.none { … overlap … }) { "Workspace item overlaps a platform reservation" }` → **IllegalArgumentException**.
4. `LayoutWriterCanonicalCaptureSource` catches it → `CanonicalCaptureReadResult.Invalid` → `NotReady(InvalidCanonicalCapture(CAPTURE_UNAVAILABLE))` → journal `INPUT_NOT_READY / INPUT_READINESS.CAPTURE_INVALID`.
5. The pre-restore baseline had no item at `cellY=0` on screen 0 — which is why capture passed there.

The deep-shortcut `targetKey` require chain (`parseIntent` package/`shortcut_id`) was ruled out: row 115's intent carries both values. Page-inventory and profile-inventory requires were ruled out: screens {0,1} are all in `orderedPages`, and all rows are `profileId=0`.

## Interpretation

- **No readiness-semantics change is warranted**: the composer correctly fail-closed on a layout that the organizer cannot represent (an item occupies cells the platform treats as an authoritative reserved region). This is spec #83 behavior working as designed.
- **The defect is an interop gap owned elsewhere**: the platform loader accepts items in the QSB row (Nova-imported layouts and grid migrations can produce them), while the organizer capture treats the whole QSB row as reserved and therefore permanently refuses input for such workspaces. A user restoring a Nova 5x5 layout keeps `manual_organization_input_unavailable` forever — support-visible via the new journal code only after this issue's instrumentation.
- **The #171 "one-off" characterization is revised**: on this reproduction the failure is persistent for the restored workspace state. The earlier "succeeded in a later process" observation corresponds to a different workspace/DB state, not to process lifetime.

## Focused follow-up

The fix is split to focused Issue [#185](https://github.com/nunu1733/NunuLauncher/issues/185). Candidate directions it must decide (spec-first, per the interop analysis above): map QSB-row-overlapping items into the organizer's preserved/unsupported space instead of failing the whole capture, keep fail-closed but narrow the reservation, or reject at import time. No readiness/fail-closed semantics are changed by this issue.

## Acceptance criteria mapping (issue #172)

- [x] Run ending in `InputUnavailable` produces a privacy-safe terminal diagnostics record with the readiness reason code — PR #184 + journal export above (AC-1).
- [x] Underlying capture exception observable at debug/diagnosis level (phase + exception class, no layout content) — `phase=CAPTURE exceptionClass=IllegalArgumentException`, no message/stack on any surface (AC-2, tightened per review).
- [x] One-off post-restore episode reproduced with a named reason and bounded with failure-time state (gate READY — the failure came from the composer, not the gate; recovery-store availability not implicated; bundle/override/evidence sources never reached because capture is the first read) (AC-3).
- [x] No readiness-behavior change; fail-closed unchanged — PR #184 AC-6, re-verified by the independent audit.

## Commands executed (this assessment)

- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL (build `50ddb86`); installed via streamed install.
- Organizer runs via settings UI (`Review organization` / `Try again` / `Start a new review`); `logcat OrganizerDiag:V *:S` captured throughout.
- `run-as`-level journal export and DB pull under `su`; `sqlite3` inspection of `favorites` (row inventory above).
