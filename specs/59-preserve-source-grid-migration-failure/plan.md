# Implementation Plan: Preserve source layout when grid migration fails

> Issue: [#59](https://github.com/nunu1733/NunuLauncher/issues/59)
> Spec: [spec.md](./spec.md)
> Status: accepted
> Updated: 2026-08-16

## Current evidence

- [ADR-0004](../../docs/adr/0004-organizer-lock-persistence.md) requires
  schema-32 legacy values and migration targets to become `UNKNOWN`, while a
  failed migration keeps its source authoritative.
- [The Issue #44 shared-writer audit](../../docs/assessment/issue-44-shared-writer-audit.md)
  identifies source lock mutation before copy, target publication before the
  lease, and empty-database fallback as the corrective scope for Issue #59.
- The production route is `ModelDbController.tryMigrateDB()` through private
  `migrateGridIfNeeded()` and `GridSizeMigrationUtil`. The implementation must
  retain that controller entry and the existing `LayoutWriteCoordinator` lease.

## Design

### Ownership and seam

`ModelDbController` owns source authority, candidate target lifecycle, one
reconciliation dispatcher for active, target, and migration-entry admission,
publication, delegated source close, and destination preferences.
`LayoutWriteCoordinator` remains the sole public serialization seam. The
controller acquires its existing `GRID_MIGRATION` lease after source restore or
open and before candidate target setup. It releases the lease only after
finalization or completed failure handling.

Introduce one package-private, production-used `GridMigrationRuntime` owned by
the controller. Production constructs its normal runtime in the controller.
Tests may supply the same internal runtime seam to inject operation failures and
observe ordering, but every migration test must call `ModelDbController.tryMigrateDB`.
The runtime receives local source and target handles, performs no publication,
no source close, and no preference write. It has no public API, static pre-hook,
test-only migration entry, or independent transaction boundary.

`GridSizeMigrationUtil` remains the algorithm implementation beneath that
runtime. Its operation assumes the controller holds the lease. It must not open
or publish helpers, write preferences, own recovery, or use a separate
transaction from the initial target transaction.

`LauncherPrefs.putSync` commits every editor synchronously and returns the
Boolean aggregation of all editor commits. The controller then reads the grid
state back and accepts the preference transition only when both the aggregate
commit result and readback match the requested state.

`DatabaseHelper.onUpgrade case 32` remains the only writer that changes legacy
source rows to `UNKNOWN`. For schema 33, column validation must not change row
values. Inactive-grid opening uses that same helper upgrade path.

### Same-target journal protocol

The target database contains the durable journal and backup snapshot. It is not
a raw database-file copy, a preference record, or a `RecoveryStore` record. The
journal identifies the source and target identities, captures the source and
destination preference values needed to finalize or compensate, and records a
versioned canonical SHA-256 digest of the backup `favorites` table plus one phase
from the contract below.

| Phase | Write point | Controller action |
|---|---|---|
| `TARGET_OLD` | Inside the initial target transaction only | Copy the pre-migration target state to the same-target backup and create journal intent before target mutation. This phase must not survive commit or rollback. |
| `MIGRATED_PENDING_FINALIZATION` | Last durable write in the initial target transaction | Target migration committed, but source remains authoritative. On restart or retry, reconcile before target use or another migration. |
| `RESTORE_PENDING` | Before restoring after finalization failure | Source remains authoritative. Restore the same-target backup. |
| `RESTORE_FAILED` | When preference progression, restore, or digest validation fails | Return `false` with observable recovery-pending state. Do not swallow it or claim rollback completed. Retry before target use or another migration. |
| `FINALIZED` | After publication, delegated close, and preferences succeed | Validate the finalized target before use, then delete backup and journal together in one target transaction as recovery-metadata cleanup. |

The initial target transaction has this exact ordering:

1. Validate source and target are different database files. Prepare the target
   schema without publishing its helper.
2. Create the same-target backup snapshot and journal with `TARGET_OLD` intent.
3. Run copy and placement through the fast or general path.
4. Mark every resulting target row `UNKNOWN` and remove `favorites_tmp`.
5. Change the journal to `MIGRATED_PENDING_FINALIZATION` and commit.

If this transaction fails or closes unsuccessfully, it rolls back as a unit and
the source remains open and authoritative. `favorites_tmp` is migration
temporary state. Backup and journal rows are recovery metadata, so their later
deletion after `FINALIZED` must not be characterized as temporary cleanup.

### Finalization, compensation, and recovery

After the initial transaction closes successfully, the controller finalizes
while holding the lease: publish the candidate target, delegate source close,
persist destination preferences, then record `FINALIZED`. The `FINALIZED` write
is part of finalization. A failure to record it must compensate and restore,
rather than leave an authority change without a recoverable phase. The controller
must retain enough source helper information to reopen the source if delegated
close completes then throws. That case is a failure, not a successful close.

If destination preference persistence partially succeeds then throws, compensate
all destination preference values to their captured source values before
returning failure. The controller must reopen the source after a completed
delegated close, restore `mOpenHelper` to it, set `RESTORE_PENDING`, and restore
the same-target backup. Source, destination, and unknown current preferences
all progress through this source-authority path when finalization cannot be
proved. A restore exception or failed versioned canonical-digest validation sets
`RESTORE_FAILED`, exposes recovery-pending state, returns `false`, and blocks
target use and new migration until retry succeeds.

On startup, target opening, and migration entry, the controller uses one
reconciliation dispatcher to check the journal before using that target.
`MIGRATED_PENDING_FINALIZATION` completes finalization only when destination
preferences and the pending target validate. Otherwise, including unknown
current preferences, it restores source authority then enters restoration.
`RESTORE_PENDING` and `RESTORE_FAILED` retry restoration. No path calls
`createEmptyDB()` for a failed migration. Restore validates the canonical digest
before metadata cleanup. `FINALIZED` validates target identity, backup digest,
all-`UNKNOWN` rows, and absent `favorites_tmp` before deleting backup and journal
together in one transaction.

### Rejected alternatives

- Raw file copy cannot atomically couple source-independent target backup,
  journal, and target migration state in the target database.
- `RecoveryStore` coupling adds another persistence authority outside the target
  transaction.
- A prefs-only journal cannot atomically describe target backup and migration
  commit.
- Best-effort swallow can report rollback success after restoration failed.
- A static pre-hook does not exercise a real delegated close, preference partial
  success, or controller lifecycle.
- A second coordinator violates the existing `LayoutWriteCoordinator` seam and
  obscures migration ordering.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/ModelDbController.java` | Make `tryMigrateDB` and private `migrateGridIfNeeded` admit recovery before target use, hold the existing lease before target setup, own finalization and compensation, reopen source after delegated-close throw, and remove empty-database fallback. | It owns active helper authority and the caller-visible result. |
| `src/com/android/launcher3/model/GridMigrationRuntime.java` | Add one package-private production runtime for initial target transaction execution and test fault observation. | It gives controller-entry tests the same production operation without a test-only migration path. |
| `src/com/android/launcher3/model/GridMigrationOperation.java` | Name production operation boundaries for fault injection and ordering observation. | It keeps the single runtime seam explicit without another coordinator. |
| `src/com/android/launcher3/model/GridMigrationJournal.java` and `FavoritesTableDigest.java` | Persist journal phases, preference states, and a versioned canonical backup digest; verify backup and restored `favorites` before cleanup. | Recovery state and data-integrity proof belong with the target database. |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | Run fast and general copy and placement under the runtime's single initial target transaction, then mark `UNKNOWN` and clean `favorites_tmp`. | It owns SQLite migration algorithms. |
| `src/com/android/launcher3/model/DatabaseHelper.java` | Add target-local journal and backup schema support and preserve the existing schema-32 `onUpgrade` behavior without mutating valid schema-33 source rows. | It owns schema creation and upgrade. |
| `src/com/android/launcher3/LauncherPrefs.kt` | Aggregate every synchronous editor commit result in `putSync`. | A partial multi-editor commit is not a successful preference transition. |
| `tests/organizer-instrumentation/com/android/launcher3/model/`, `tests/organizer-instrumentation/com/android/launcher3/organizer/`, and `LauncherPrefsCommitTest.kt` | Add shared controller fixture support plus controller-entry success, restart, retry, partial preference, delegated-close-then-throw, schema, inactive-grid, and multi-editor preference tests. | The acceptance contract is observable through controller lifecycle and the synchronous preference seam. |

## Test-first sequence

1. Add the shared fixture helper that creates schema-33 source locks, a
   pre-existing target, captured preferences, and journal states.
2. Add controller-entry fast and general tests that observe one initial target
   transaction containing backup, `TARGET_OLD`, copy or placement, `UNKNOWN`,
   `favorites_tmp` cleanup, and `MIGRATED_PENDING_FINALIZATION`.
3. Add a controller-entry ordering test for lease admission, transaction closure,
   publication, delegated source close, and preference persistence.
4. Add controller-entry faults after copy, after initial commit, during partial
   preference persistence, and after delegated close completes then throws.
5. Restart the controller against `MIGRATED_PENDING_FINALIZATION`,
   `RESTORE_PENDING`, and `RESTORE_FAILED`; cover source, destination, and
   unknown current preferences, digest validation before cleanup, and finalized
   target validation with atomic backup and journal deletion.
6. Add schema-32 upgrade, inactive-grid, and `32 -> 33 -> 32 -> 33` fixtures.
7. Implement the smallest controller, runtime, utility, and helper changes that
   make those tests pass.

## Verification

| Acceptance criterion | Automated evidence |
|---|---|
| AC-59-01, AC-59-08 | Real SQLite `DatabaseHelperSchema33Test` upgrade and lifecycle fixtures. |
| AC-59-02 | Controller-entry fast and general success tests, plus controller-entry copy failure. |
| AC-59-03, AC-59-05 | Controller-entry ordering and reconciliation tests using the production `GridMigrationRuntime`, including all-editor preference aggregation plus readback, finalized-target validation, and atomic backup plus journal cleanup. |
| AC-59-04 | Controller-entry fast and general transaction-observation tests. |
| AC-59-06 | Controller-entry restart, recovery retry, source, destination, and unknown preference progression, canonical-digest validation, partial preference compensation, and delegated-close-then-throw tests. |
| AC-59-07 | `InactiveGridDbNormalizationTest` multi-database fixture. |
| All | `python3 tools/repo-contract/validate_repo_contract.py` and `python3 tools/repo-contract/test_validate_repo_contract.py`. |
| All code changes | `./gradlew spotlessCheck` and `./gradlew assembleLawnWithQuickstepGithubDebug`. |

## Local execution evidence, 2026-08-16

These commands ran successfully against the local working tree with JDK 21 and
an Android API 36.1 AVD. This is local execution evidence only, not
implementation-commit, PR CI, or independent-audit evidence.

| Command | Outcome |
|---|---|
| `./gradlew compileLawnWithQuickstepGithubDebugKotlin compileLawnWithQuickstepGithubDebugJavaWithJavac compileLawnWithQuickstepGithubDebugAndroidTestKotlin compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` | Passed. |
| `./gradlew spotlessCheck` | Passed. |
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` | Passed. |
| `./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest` | Passed. |
| `adb -s emulator-5562 shell am instrument -w -r -e class com.android.launcher3.LauncherPrefsCommitTest,com.android.launcher3.model.GridMigrationSuccessTest,com.android.launcher3.model.GridMigrationFailureTest,com.android.launcher3.organizer.DatabaseHelperSchema33Test,com.android.launcher3.organizer.DowngradeSchema33Test,com.android.launcher3.organizer.InactiveGridDbNormalizationTest app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner` | Passed, `OK (34 tests)`. |
| `python3 tools/repo-contract/validate_repo_contract.py` | Passed. |
| `python3 tools/repo-contract/test_validate_repo_contract.py` | Passed. |
| `python3 tools/repo-contract/test_validate_high_risk_evidence.py` | Passed. |

No implementation commit or PR exists. Therefore no commit-bound CI result, PR
CI result, or independent high-risk audit exists to record.

## Documentation updates

- [x] `spec.md` records the accepted same-target journal and recovery contract.
- [x] `specs/13-safe-layout-application/plan.md` remains the short historical
  cross-reference. No update is needed because Issue #59 retains ownership.
- [x] `CONTEXT.md`, `DESIGN.md`, and ADR-0004 need no change.

## Execution checklist

- [x] Recovery phases, authority, and retry behavior are decision-complete.
- [x] AC-59-04 distinguishes initial transaction cleanup from recovery metadata.
- [x] AC-59-06 requires visible failed recovery and retry.
- [x] Tests use controller entry and one production runtime only.
- [x] Local working-tree implementation and execution evidence cover AC-59-01 through AC-59-08.
- [ ] Implementation-commit evidence remains unavailable because no implementation commit exists.
- [ ] PR CI and independent high-risk audit remain unavailable because no Issue #59 code PR exists.
