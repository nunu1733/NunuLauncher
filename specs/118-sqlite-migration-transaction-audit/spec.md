---
issue: "#118"
status: implemented
requirements:
  - NFR-001
  - NFR-002
  - NFR-012
updated: 2026-08-23
---

# Deterministic transaction ownership in Launcher DB upgrade and downgrade

## Problem

`SQLiteOpenHelper` wraps `onCreate`, `onUpgrade`, and `onDowngrade` in one
framework `SQLiteDatabase` transaction. Under the canonical Android nested
transaction contract, nesting is pure bookkeeping with no SAVEPOINTs: a nested
scope that ends without success sets `mChildFailed` on every ancestor, and the
outermost end rolls back the whole unit even if the caller marked it successful
([AOSP SQLiteSession.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/sqlite/SQLiteSession.java),
checked 2026-08-23; production evidence in
[#115](https://github.com/nunu1733/NunuLauncher/issues/115)).

`DatabaseHelper.onUpgrade` / `onDowngrade` still contain `LauncherDbUtils.SQLiteTransaction`
scopes from the savepoint era, and several callers catch an unsuccessful inner
scope and then continue or fall back as if the outer transaction could still
commit. The observable defect: when a legacy migration step fails, the intended
wipe fallback never persists; the database file keeps its old version while
`getWritableDatabase()` returns normally, so the model writes rows that assume
schema 33 into an older database until a statement fails at runtime.

## Outcome

Every transaction opened from `DatabaseHelper.onUpgrade` / `onDowngrade` has one
recorded owner classification (Issue #118 categories 1–3). Category 3 paths are
restructured so the framework callback transaction is the single authoritative
owner: migration steps execute directly inside it, failures either propagate
(deliberate ADR-0004 behavior for schema 32→33) or deterministically persist the
historical wipe fallback — which also cleans the `temp_favorites` staging table
the downgrade recipes use — and no path assumes a poisoned outer transaction
can commit. Failure-injection tests prove rollback/fallback persistence for a
legacy upgrade step and for downgrade recipes executed by a helper built from
this tree, including a failure after the recipe has already made progress.

The 33→32 downgrade path itself is evidenced against the actual schema-32
rollback target instead of a stand-in: verbatim copies of that binary's
`DatabaseHelper` / `DbDowngradeHelper` source (commit
`866d231ffdfe2dcc8b0e550e65ea6f1301b6674c`, immediately before the schema-33
bump) are pinned in the instrumentation sources and opened against a seeded
schema-33 file through the real `SQLiteOpenHelper` wrapping. Those tests pin
the committed version, table shape, and data outcome of the field rollback —
including that a failing recipe leaves the file un-downgraded behind an open
that reports success, because the bundled pre-#118 helper still owns the nested
scope. This PR cannot modify installed artifacts, so that behavior remains as
released and is recorded as a residual risk; deterministic recovery applies
from the first rollback executed by binaries built after this change.

## Scope

- Audit record for all upgrade/downgrade paths reachable from schema 33.
- Removal of redundant inner `SQLiteTransaction` scopes in `DatabaseHelper`
  legacy migration helpers (`case 13`, `addIntegerColumn`,
  `updateFolderItemsRank`, `convertShortcutsToLauncherActivities`).
- Transaction ownership reconciliation for `DbDowngradeHelper.onDowngrade`: the
  caller owns the transaction (the framework callback transaction in
  production); direct callers wrap their own.
- Deterministic wipe cleanup: `createEmptyDB()` additionally drops the
  `temp_favorites` staging table hardcoded in `res/raw/downgrade_schema.json`,
  so a fallback after partial recipe progress never leaks staged layout rows.
- Failure-injection tests for at least one legacy upgrade path, for downgrade
  recipes executed by this tree's helper (first-statement failure and failure
  after partial progress), and for the real 33→32 rollback path against the
  pinned schema-32 rollback target replica — plus retention of the
  non-destructive fixtures required by
  [ADR-0004](../../docs/adr/0004-organizer-lock-persistence.md) / Issue #14.

## Non-goals

- Fixing failure recovery inside already-built binaries that bundle their own
  pre-#118 helper code. A field rollback from schema 33 to 32 runs on such a
  binary, whose `DbDowngradeHelper` still owns the nested scope that a failing
  statement poisons and whose wipe fallback therefore still rolls back
  silently. This PR cannot modify installed artifacts; the limitation is
  handled explicitly by pinning the behavior as AC-4 evidence — the rollback
  target test fails if the pinned non-recovery ever drifts — and recording it
  as a residual risk. Deterministic recovery applies from the first rollback
  executed by a binary built after this change (e.g. any future
  schema-version rollback).
- No change to the schema 32→33 migration itself; it executes directly in the
  framework transaction and throws on failure (ADR-0004). This behavior must not
  regress.
- No SAVEPOINT usage and no partial rollback inside a migration step.
- No change to which conditions trigger the destructive wipe fallback; only its
  previously broken persistence is fixed, and its cleanup now covers the
  downgrade staging table.
- No change to `createEmptyDB()`'s own transaction: standalone callers exist
  outside any helper callback (`ModelDbController.createEmptyDB()` used by
  restore and default-layout loading).
- No audit of organizer apply/recovery, restore, grid-migration, or
  coordinator transaction shapes; those were cleared in #115's writer audit and
  are tracked separately (#117, #119, #120).
- Not API-36-specific: the contract applies to all supported platform levels.

## Transaction-owner classification (audit record)

Framework wrapping evidence: `SQLiteOpenHelper.getDatabaseLocked` opens one
transaction around each version-changing callback, calls `db.setVersion()`
inside it, marks success only after the callback returns normally, and lets any
callback exception roll back and propagate.

### `onUpgrade` paths (owner: framework callback transaction)

| Path | Mechanism before fix | Category | Disposition |
|---|---|---|---|
| case 13 (`ALTER TABLE favorites ADD appWidgetProvider`) | inner `SQLiteTransaction`; catch `SQLException` → `break` → wipe | 3 | remove inner scope; keep catch→wipe |
| cases 14/15/19/22/28 via `addIntegerColumn` | inner scope; catch → `false` → wipe | 3 | remove inner scope; keep catch→wipe |
| case 20 via `updateFolderItemsRank` | inner scope; catch → `false` → wipe | 3 | remove inner scope; keep catch→wipe |
| case 25 `convertShortcutsToLauncherActivities` | inner scope; catch `SQLException` → log and continue upgrade | 3 | remove inner scope; keep best-effort log-and-continue (statement-level failure no longer poisons) |
| cases 12/16/17/18/21/23/24/26 (no-op) | none | — | unchanged |
| case 27 screen reorder + drop `workspaceScreens` | direct statements | framework-owned | unchanged |
| cases 29/30 deletes | direct statements | framework-owned | unchanged |
| case 31 `migrateLegacyShortcuts` | no inner scope; internal `DROP COLUMN` → `removeColumn` rebuild fallback catches statement-level `SQLiteException` only | 2 (safe) | unchanged; recorded |
| case 32→33 organizer lock column | direct statements; throw on failure | framework-owned (ADR-0004) | must not regress |
| wipe fallback `createEmptyDB(db)` | own scope + commit; first and only child scope on this path after remediation | 2 (safe) | kept: same method is the sole transaction owner for standalone callers (`ModelDbController.createEmptyDB()` from restore/default-layout paths) |

### `onDowngrade` paths (owner: framework callback transaction)

| Path | Mechanism before fix | Category | Disposition |
|---|---|---|---|
| `DbDowngradeHelper.parse` fails (missing/corrupt schema file) | no scope opened; catch → wipe persists | 2 (safe) | unchanged; recorded |
| unsupported downgrade target | throws before opening its scope; catch → wipe persists | 2 (safe) | unchanged; recorded |
| recipe statement fails inside `DbDowngradeHelper`'s inner scope | scope ends unsuccessful → poisons outer → caught wipe is silently rolled back; database stays at the higher version and every open retries | 3 | move ownership out of `DbDowngradeHelper`: statements run in the caller's transaction so the catch→wipe fallback persists deterministically |

## Behavior scenarios

### Scenario: legacy upgrade step fails and the wipe fallback persists

Given a database file whose version is 13 and whose `favorites` table already
contains a `modified` column and one user row,
When a production `DatabaseHelper` opens the database,
Then the open returns without exception, the file version is 33, and
`favorites` is a fresh empty table with the current shape,
And the pre-existing user row is gone (explicitly justified destructive
fallback: without it the launcher cannot open its layout database at all).

### Scenario: downgrade recipe statement fails and the wipe fallback persists

Given a schema-33 database with one user row whose downgrade recipe is made to
fail (a conflicting table name blocks the first rebuild statement),
When the framework-style wrapper opens one transaction, calls
`onDowngrade(db, 33, 32)`, sets version 32, and marks success,
Then the committed state contains a fresh empty current-shape `favorites`
table, and no user row survives,
And no partially rebuilt table state leaks through: the `temp_favorites`
staging table is gone as well.

### Scenario: downgrade failure after partial recipe progress leaves no staged rows

Given a schema-33 database with one user row staged so that the concatenated
33→22 recipe completes its 32, 31, and 28 blocks (rebuilding `favorites` with
the prior row) before a blocked `CREATE TABLE workspaceScreens` fails,
When the framework-style wrapper runs `onDowngrade(db, 33, 22)` in one
transaction and marks success,
Then the committed state is a fresh empty current-shape `favorites` table at
version 22,
And neither the copied layout row nor the staging/leftover tables
(`temp_favorites`, `workspaceScreens`) survive the fallback.

### Scenario: real 33→32 rollback succeeds with schema-32 table shape

Given a seeded schema-33 file (one row, `organizerLockState` set) and the
schema-33 downgrade recipe file that a field device carries,
When the pinned schema-32 rollback target opens the database through its own
`getWritableDatabase()`,
Then the framework runs that binary's `onDowngrade(33, 32)`, the version
commits at 32, the rebuilt `favorites` keeps the row in schema-32 shape, and
the lock column is absent.

### Scenario: failing recipe on the real rollback target does not recover

Given the same seed plus a conflicting `temp_favorites` table blocking the
first rebuild statement,
When the pinned rollback target opens the database,
Then the open reports success while the committed state stays at version 33
with the pre-migration row, the lock column, and the staging junk intact —
the documented non-recovery of installed binaries and the residual risk this
PR cannot remove.

### Scenario: schema 32→33 upgrade failure still rolls back without wiping

Given the existing trigger-based failure injection on a schema-32 database,
When the upgrade aborts,
Then `getWritableDatabase()` throws, the file remains at version 32 without the
`organizerLockState` column, and the legacy row is unchanged (ADR-0004).

### Scenario: successful fixtures remain non-destructive

Given fresh 33, 32→33, and 33→32→33 databases,
When opened, upgraded, downgraded, and re-upgraded through the production
helpers,
Then layout rows survive and the cycle fixtures behave exactly as in the
existing Issue #14 tests.

## Data and state

- Reads and writes only the launcher layout database files during helper
  callbacks; no new data, identity, or retention changes.
- Migration/rollback semantics are the subject of this spec; backup/restore
  compatibility is unchanged (wipe produces a fresh current-shape empty table,
  as before).
- Layout safety rules apply unchanged: the wipe fallback destroys placement
  data only on the historical legacy-version paths where the alternative is an
  unusable database, and each such trigger now has a passing test.

## Permissions, privacy, and security

None. No permission, network, or sensitive-data change.

## Accessibility and localization

Not applicable; no UI surface.

## Acceptance criteria

- [ ] AC-1: Every upgrade/downgrade path reachable from schema 33 has a recorded
  transaction-owner classification (table above, mirrored near the code).
- [ ] AC-2: No reachable path catches an unsuccessful nested `SQLiteTransaction`
  and then relies on the outer `SQLiteOpenHelper` transaction committing.
- [ ] AC-3: `DbDowngradeHelper` transaction ownership is reconciled with the
  framework callback transaction; direct callers own their transaction.
- [ ] AC-4: Failure-injection tests prove rollback/fallback behavior for at
  least one upgrade path and the 33→32 downgrade path.
  - The 33→32 evidence runs against the actual schema-32 rollback target:
    verbatim source replicas of that binary's helpers pinned at commit
    `866d231ffdfe2dcc8b0e550e65ea6f1301b6674c`, executed through the real
    `SQLiteOpenHelper` wrapping, asserting committed version, table shape, and
    data outcome for both the successful rollback and the failing-recipe case.
  - Deterministic recovery on helpers built from this tree is additionally
    covered by first-statement and partial-progress failure scenarios.
- [ ] AC-5: Successful 32→33, fresh 33, and 33→32→33 fixtures remain
  non-destructive per ADR-0004 / Issue #14.
- [ ] AC-6: The remaining destructive fallbacks (legacy upgrade wipe, downgrade
  wipe) are explicitly justified in this spec and exercised by tests; wipe
  triggering conditions are not widened.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | Classification table in this spec; `#118` comments beside each changed site |
| AC-2 | New `MigrationTransactionOwnershipTest` (both original poisoning scenarios fail on unmodified `main` and pass after the fix) |
| AC-3 | Same test class, downgrade scenarios; updated ownership in the three compiled direct-caller tests |
| AC-4 | `MigrationTransactionOwnershipTest`: `legacyUpgradeFailureFallsBackToWipeThatPersists`, `downgradeStatementFailureFallsBackToWipeThatPersists`, `downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe`; plus `rollback32.Schema32RollbackBinaryTest` against the pinned schema-32 rollback target (`successful33To32RollbackReachesVersion32PreservingRows`, `failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded`) |
| AC-5 | Existing fixtures green: `DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest` (`tests/multivalentTests` and `tests/src` are upstream leftovers not wired into this repository's Gradle build, so their suites are out of merge-gate scope here) |
| AC-6 | Justification in Scenarios section; assertions that the wiped result is deterministic |

## Open questions

- None blocking. CI wiring for the new instrumentation suite follows the
  established focused-lane pattern and is recorded in `ci-test-portfolio.md`.

## Change history

- 2026-08-23: Draft created for #118 with the full classification audit.
- 2026-08-23: Accepted. Both failure-injection scenarios were shown failing on
  unmodified `main` (API 36 emulator `nunu_qpr2_api36_1`: the upgrade scenario
  left the file at version 13 and the downgrade scenario at version 33 after an
  open that reported success) and passing after the remediation.
- 2026-08-23: Revised on PR #122 review. Narrowed the downgrade claim to
  helpers built from this tree and moved failure recovery of already-built
  schema-32 rollback binaries to Non-goals as a residual risk; added the
  partial-progress downgrade failure scenario and deterministic
  `temp_favorites` staging-table cleanup in the wipe fallback.
- 2026-08-23: Revised again on PR #122 re-review. Restored AC-4's original
  33→32 wording; added `rollback32.Schema32RollbackBinaryTest`, which pins the
  actual schema-32 rollback target source (commit
  `866d231ffdfe2dcc8b0e550e65ea6f1301b6674c`) and proves committed version,
  table shape, and data outcome for the successful field rollback and its
  non-recovering recipe-failure behavior (residual risk, kept in Non-goals).
- 2026-08-23: Implemented by PR #122 (squash commit `93889336e7`).
