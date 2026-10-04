---
issue: "#60"
status: implemented
updated: 2026-08-24
---

# Plan: executor and shared-writer admission audit follow-ups

Baseline for evidence: main `3663f3157d`. Spec: `spec.md` in this directory.

## Current evidence

- `LayoutWriteCoordinator` (`src/com/android/launcher3/model/LayoutWriteCoordinator.java`):
  lease admission, restore-family reentrancy, organizer scoped capability,
  `runOrDefer` FIFO, `runOrDeferWithOperationFuture` Binder pattern.
  `release` (lines 318-340) runs deferred callbacks inline with no per-entry
  exception isolation — one throwing callback skips later entries.
- Supersession machinery: `LauncherModel.forceReloadForOrganizer` /
  `completeOrganizerReload` (identity check) / `cancelOrganizerReload` /
  `stopLoader`; `OrganizerModelReloadAdapter` (COMPLETED/FAILED/SUPERSEDED/
  TIMEOUT, 10s timeout). No tests cover A-then-B, stale completion,
  cancellation, timeout, or exactly-one-terminal.
- `SQLiteTransaction` (`LauncherDbUtils.java:217-254`): close releases the
  lease in `finally`; Android's `beginTransaction`/`endTransaction` nesting is
  whole-unit atomic and does not provide SAVEPOINT isolation. The #117 erratum
  corrects the prior test/spec expectation while preserving lease lifetime.
- `LauncherProvider.executeControllerTask` (lines 156-191): Binder thread
  `future.get()` while supplier does `MODEL_EXECUTOR.submit(...).get()`;
  release-thread self-wait hazard has no producer and no test.
- No source-scan allowlist exists; `tools/repo-contract/
  validate_high_risk_evidence.py` is PR-gate only.
- Instrumentation tests are compile-verified in CI (no AVD execution per
  Issue #14); unit tests run in CI.

## Change modules and order

1. **FIFO exception isolation (fix)** — `LayoutWriteCoordinator.java`
   Wrap each deferred callback in try/catch so a throwing callback cannot
   skip, duplicate, or reorder later entries or the terminal signal. Add
   FIFO-ordering and throwing-callback tests (unit-level where possible,
   else instrumentation in `tests/organizer-instrumentation`).
2. **Reload supersession tests** — new instrumentation test over the public
   seams (`LauncherModel` reload request lifecycle + adapter outcomes):
   A-then-B supersession, stale completion rejection, cancellation, timeout,
   exactly one terminal signal per request.
3. **Binder future + nested transaction tests** — instrumentation test for
   `LauncherProvider.executeControllerTask` deferral under an organizer
   lease (release happens on the deferred-callback executor thread, not
   `MODEL_EXECUTOR`, proving no self-wait); nested `SQLiteTransaction` test
   through `ModelDbController.newTransaction` proving all-success commit,
   whole-unit rollback after an unsuccessful inner close, and lease lifetime
   across inner close/failure.
4. **Executable writer-inventory allowlist** — source-scan check (python in
   `tools/repo-contract/`, wired into CI `ci.yml`) enumerating every
   `favorites` mutation / raw DB-file writer / tokenless `MODEL_EXECUTOR`
   caller reaching `ModelDbController`, against an allowlist file kept next
   to the scan. New ungated callers fail CI.
5. **Assessment update** — update
   `docs/assessment/issue-44-shared-writer-audit.md` (or append a #60
   assessment) with confirmed/disproven/unsupported per sequence, writer
   inventory with line references, and restart-evidence status; comment on
   Issues #44 and #60.

## Verification

```text
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks
./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \\
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.NestedTransactionTest
python3 tools/repo-contract/<new scan script>
```

(JDK 21, ANDROID_HOME=/opt/homebrew/share/android-commandline-tools.)

## Migration / rollback

No schema change. Rollback is code revert; the allowlist scan is additive CI.
