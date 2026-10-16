---
issue: "#605"
status: draft
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
| NovaBackupConverter | `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | Complete-grid branch: restore `writeGridToLawnchairPrefs(info, smartspaceEnabled)` and the spec #168 ordering — `writeToPrefs(context)` → `writeGridToLawnchairPrefs` → `applyConvertedGrid` → `writeToPrefs(context, true)` last. Comments updated to cite #168 and #605. |
| NovaConverterBoundaryScenarioBase | `tests/organizer-instrumentation/app/lawnchair/backup/NovaConverterBoundaryScenarioBase.kt` | `assertCommittedGrid()` additionally asserts the lawnchair typed prefs equal the converted values and the persisted `com.android.launcher3.prefs.xml` on disk contains them at return time. |

No other production path changes. The partial-grid `else` branch is
untouched (spec Non-goals).

## Seam and invariants

- Same three pref stores, same typed setters under `batchEdit`, same
  lease-held main-thread `applyConvertedGrid`; only the presence and ordering
  of the lawnchair write change, per accepted spec #168 Behavior 1.
- The restore-family lease, `restored.db` rename, reload completion barrier,
  and `cleanUpDatabases` guard are unchanged.
- The test oracle stays inside the existing converter-boundary surface and
  the one-restore-per-process execution contract (#299): no new lane, no
  class list change in `tools/ci/run-restore-capture-instrumentation.sh`,
  no `ci.yml` filter change.

## Migration / rollback

No schema, pref-format, or backup-format change. Rollback = revert the PR.

## Tests

1. Extended `NovaConverterBoundaryTest` and
   `NovaConverterBoundarySmartspaceOffTest` (existing restore-capture lane;
   one restore per process): `assertCommittedGrid()` also asserts the
   lawnchair typed prefs and the persisted XML file. Red on the regression
   head, green on the fix head (AC-3 evidence recorded in the PR).
2. Existing suites keep passing: `NovaRestoreGridApplicationTest`, the
   capture classes (`NovaRestoreCaptureControlTest`,
   `NovaRestoreCaptureWidgetWindowTest`,
   `NovaRestoreCaptureUnknownProviderTest`,
   `NovaRestoreCaptureNoCallbacksTest`), the cross-process pair,
   `RestoreDbTaskTest`, `BackupAndRestoreDBSelectionTest`.

## Verification

- `./gradlew spotlessCheck`
- `./gradlew assembleLawnWithQuickstepGithubDebug`
- Red check of the extended assertion on the regression head
  (`b18a98af48ab10f4c5339edff1f2728fb505b65d`'s current descendant = base),
  then the restore-capture lane (`tools/ci/run-restore-capture-instrumentation.sh`)
  on a local emulator after the fix.
- Runtime emulator reproduction with a synthetic complete-grid backup
  (converted 6x5x5) from the default grid: one UI restore → self-restart →
  converted DB active, prefs 6/5/5, no unmatched-deletion log; a further cold
  start keeps the state; a custom-layout start-state observation is recorded.
- Physical API 37 device owner confirmation if reachable; otherwise recorded
  as pending in the PR (#168 precedent).
- High-risk PR requirements: `final-status` CI gate on the verification
  commit plus the independent audit record `docs/assessment/pr-<n>-<slug>.md`
  before merge.
