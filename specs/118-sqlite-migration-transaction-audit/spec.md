---
issue: "#118"
status: accepted
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
historical wipe fallback, and no path assumes a poisoned outer transaction can
commit. Failure-injection tests prove rollback/fallback persistence for a
legacy upgrade step and for the 33→32 downgrade recipe.

## Scope

- Audit record for all upgrade/downgrade paths reachable from schema 33.
- Removal of redundant inner `SQLiteTransaction` scopes in `DatabaseHelper`
  legacy migration helpers (`case 13`, `addIntegerColumn`,
  `updateFolderItemsRank`, `convertShortcutsToLauncherActivities`).
- Transaction ownership reconciliation for `DbDowngradeHelper.onDowngrade`: the
  caller owns the transaction (the framework callback transaction in
  production); direct callers wrap their own.
- Failure-injection tests for at least one legacy upgrade path and the 33→32
  downgrade recipe, plus retention of the non-destructive fixtures required by
  [ADR-0004](../../docs/adr/0004-organizer-lock-persistence.md) / Issue #14.

## Non-goals

- No change to the schema 32→33 migration itself; it executes directly in the
  framework transaction and throws on failure (ADR-0004). This behavior must not
  regress.
- No SAVEPOINT usage and no partial rollback inside a migration step.
- No change to which conditions trigger the destructive wipe fallback; only its
  previously broken persistence is fixed.
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
And no partially rebuilt table state leaks through.

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
- [ ] AC-4: Failure-injection tests prove rollback/fallback persistence for at
  least one legacy upgrade path and the 33→32 downgrade path.
- [ ] AC-5: Successful 32→33, fresh 33, and 33→32→33 fixtures remain
  non-destructive per ADR-0004 / Issue #14.
- [ ] AC-6: The remaining destructive fallbacks (legacy upgrade wipe, downgrade
  wipe) are explicitly justified in this spec and exercised by tests; wipe
  triggering conditions are not widened.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | Classification table in this spec; `#118` comments beside each changed site |
| AC-2 | New `MigrationTransactionOwnershipTest` (both poisoning scenarios fail on unmodified `main` and pass after the fix) |
| AC-3 | Same test class, downgrade scenario; updated ownership in the four direct-caller tests |
| AC-4 | Same test class: `legacyUpgradeFailureFallsBackToWipeThatPersists`, `downgradeStatementFailureFallsBackToWipeThatPersists` |
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
