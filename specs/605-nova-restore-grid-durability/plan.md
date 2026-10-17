---
issue: "#605"
status: accepted
updated: 2026-10-17
---

# Plan: restore the complete-grid pref-store writes in NovaBackupConverter

## Risk tier: H

Backup/restore contract (spec #168 Behavior 1) on the restore path;
`risk: layout-data`. Accepted spec + this plan are the start gate.

## Current-code evidence

- `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt:195-218`
  (complete-grid branch): `gridState.writeToPrefs(context, true)` runs
  FIRST, then `writeToPrefs(context)`, then `applyConvertedGrid(gridInfo)`;
  `writeGridToLawnchairPrefs` is absent. Regression introduced by
  `b18a98af48ab10f4c5339edff1f2728fb505b65d` (S3a of #532) and confirmed by
  the issue's runtime evidence (restart recompute reads defaults; restored DB
  deleted as unmatched).
- Pre-regression order (`b18a98af48^`): `writeToPrefs(context)` →
  `writeGridToLawnchairPrefs` → `applyConvertedGrid` →
  `writeToPrefs(context, true)` last.
- `DeviceGridState.writeToPrefs(context, true)`
  (`src/com/android/launcher3/model/DeviceGridState.java:138-148`) commits
  `KEY_WORKSPACE_SIZE`/`KEY_HOTSEAT_COUNT`/`KEY_DEVICE_TYPE` to
  `LauncherPrefs.getPrefs(context)` = `com.android.launcher3.prefs.xml` —
  the same SharedPreferences file the lawnchair typed prefs
  (`pref_workspaceRows`/`pref_workspaceColumns`/`pref_hotseatColumns`, via
  `Utilities.getPrefs` → `LauncherFiles.SHARED_PREFERENCES_KEY`) live in. A
  synchronous `commit()` on that cached instance merges and flushes the
  pending `batchEdit` `apply()` writes — the #168 durability barrier.
- Restart-time grid recompute: `DeviceProfileOverrides.DBGridInfo(prefs)`
  (`lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt:103-107`) reads
  only the lawnchair typed prefs, so the missing write reverts the grid in
  the next process.
- Coverage gap that let the regression through:
  `NovaConverterBoundaryScenarioBase.assertCommittedGrid()`
  (`tests/organizer-instrumentation/app/lawnchair/backup/NovaConverterBoundaryScenarioBase.kt:275-296`)
  asserts the LauncherPrefs migration metadata and the live IDP `dbFile` but
  never the lawnchair typed prefs — the exact store the next process reads.

## Changed modules and paths

| Module | Path | Change |
|---|---|---|
| NovaBackupConverter | `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | Complete-grid branch: restore `writeGridToLawnchairPrefs(info, smartspaceEnabled)` and the spec #168 ordering — `writeToPrefs(context)` → `writeGridToLawnchairPrefs` → `applyConvertedGrid` → `writeToPrefs(context, true)` last. Add FileLog trace markers on the converter-owned steps (metadata write, barrier) and route the existing "Committing converted grid to prefs" line through `FileLog.i` (which mirrors to logcat) so the whole ordering is deterministically observable. Comments cite #168 and #605. |
| NovaConverterBoundaryScenarioBase | `tests/organizer-instrumentation/app/lawnchair/backup/NovaConverterBoundaryScenarioBase.kt` | `assertCommittedGrid()` additionally asserts: (1) the lawnchair typed prefs equal the converted values; (2) the persisted `com.android.launcher3.prefs.xml` on disk contains them at return time; (3) the FileLog trace order of the restore window is metadata write → committed values → `applyGridInfo` binding → durability barrier (window-scoped by pre-recorded file offset, `FileLog.flushAll` before each read — single-handler FIFO makes file order equal call order). |

No other production path changes. The partial-grid `else` branch is
untouched (spec Non-goals). No new lanes, no `ci.yml` filter change, no
change to the lane script's class list.

## Seam and invariants

- Same three pref stores, same typed setters under `batchEdit`, same
  lease-held main-thread `applyConvertedGrid`; only the presence and ordering
  of the lawnchair write change, per accepted spec #168 Behavior 1.
- The restore-family lease, `restored.db` rename, reload completion barrier,
  and `cleanUpDatabases` guard are unchanged.
- The test oracle stays inside the existing converter-boundary surface and
  the one-restore-per-process execution contract (#299). The order oracle
  uses the production `FileLog` trace only — no new production seam,
  interface, or test-only adapter (AGENTS.md: 仮想interfaceを増やさない).

## Migration / rollback

No schema, pref-format, or backup-format change. Rollback = revert the PR.

## Tests

1. Extended `NovaConverterBoundaryTest` and
   `NovaConverterBoundarySmartspaceOffTest` (existing restore-capture lane;
   one restore per process): `assertCommittedGrid()` asserts the lawnchair
   typed prefs, the persisted XML file, and the FileLog trace order.
2. Red/green and sensitivity evidence (AC-2), recorded in the PR:
   - red on the base (regression) head: typed-prefs value assertion fails and
     the committed-values / barrier markers are absent from the trace;
   - sensitivity: a local barrier-first mutation of the fixed code makes the
     trace-order assertion fail (the oracle detects wrong ordering, not only
     absence);
   - green on the fix head.
3. Existing suites keep passing: `NovaRestoreGridApplicationTest`, the
   capture classes (`NovaRestoreCaptureControlTest`,
   `NovaRestoreCaptureWidgetWindowTest`,
   `NovaRestoreCaptureUnknownProviderTest`,
   `NovaRestoreCaptureNoCallbacksTest`), the cross-process pair,
   `RestoreDbTaskTest`, `BackupAndRestoreDBSelectionTest`.

## Verification

- `./gradlew spotlessCheck`
- `./gradlew assembleLawnWithQuickstepGithubDebug`
- Red check of the extended assertions on the base head, sensitivity run of
  the barrier-first mutation, then the restore-capture lane
  (`tools/ci/run-restore-capture-instrumentation.sh`) on a local API
  emulator after the fix.
- Runtime emulator reproduction (AC-3/AC-4), per the original report
  procedure on the API 37 environment: synthetic complete-grid backup
  (converted 6x5x5) from the default grid — one UI restore → self-restart →
  converted DB active, prefs 6/5/5, no unmatched-deletion log; a further cold
  start keeps the state. AC-4, custom-layout start state, three recorded
  observations: (a) a restore attempt failing or cancelled **before the
  converted DB is bound** (invalid backup file, cancellation at the
  confirmation screen) keeps the existing layout, grid prefs, and active DB;
  (b) one representative late failure — forced process stop mid-restore
  (after the grid writes, before the reload completion) — its resulting state
  and recovery means are recorded as an observation under the unchanged #168
  semantics (staged `restored.db` blocks cleanup; the pre-restore DB is not
  deleted; re-running the restore recovers), with no generalized
  all-failures claim; (c) a successful restore produces the authoritative
  import.
- Physical device (AC-5): the issue's 「API 37実機の元報告手順でowner確認する」
  stays a required final verification for closing #605. Closing without it
  requires an owner decision permalink on #605 accepting the emulator
  execution as equivalent; no such decision exists yet and none is assumed.
  The PR itself merges on the standard high-risk gate; the issue-close gate
  additionally checks AC-5.
- High-risk PR requirements: `final-status` CI gate on the verification
  commit plus the independent audit record `docs/assessment/pr-<n>-<slug>.md`
  before merge.
