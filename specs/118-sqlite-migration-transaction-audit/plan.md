# Implementation Plan: Deterministic transaction ownership in Launcher DB upgrade and downgrade

> Issue: #118
> Spec: [spec.md](./spec.md)
> Status: implemented

## Current evidence

- Framework contract: AOSP `SQLiteSession.java` (main, checked 2026-08-23)
  implements nesting as stack bookkeeping without SAVEPOINTs; a child scope
  that ends unsuccessful sets `mChildFailed` on ancestors and forces the
  outermost end to `ROLLBACK;` even after an outer `setTransactionSuccessful()`.
  Recorded in #115 with API 36 emulator evidence.
- `src/com/android/launcher3/model/DatabaseHelper.java`: inner
  `SQLiteTransaction` scopes in case 13, `addIntegerColumn`,
  `updateFolderItemsRank`, `convertShortcutsToLauncherActivities`; catch→wipe
  fallbacks assume the outer transaction can still commit.
- `src/com/android/launcher3/model/DbDowngradeHelper.java:67-72`: own
  `SQLiteTransaction` inside a callback already wrapped by the framework.
- Direct `DbDowngradeHelper.onDowngrade` callers that relied on its inner scope:
  the three compiled suites in
  `tests/organizer-instrumentation/com/android/launcher3/organizer/`
  (`DatabaseHelperSchema33Test`, `DowngradeSchema33Test`,
  `InactiveGridDbNormalizationTest`) were updated to own their transactions.
  `tests/src/.../LauncherDbUtilsTest.java` and everything under
  `tests/multivalentTests` are upstream leftovers that this repository's Gradle
  build does not compile; they are left untouched and are not merge-gate
  evidence.
- Standalone `createEmptyDB` callers requiring it to keep its own transaction:
  `ModelDbController.createEmptyDB()` ← `RestoreDbTask:125`,
  `TestInformationHandler:407/421`, default-layout loading
  (`ModelDbController:1149/1154`), test fixtures.
- Reproduction of the defect is part of the new failure-injection tests: both
  poisoning scenarios fail on unmodified `main`.

## Design

### Modules and interfaces

- `DatabaseHelper` (platform-derived bridge): legacy migration steps run
  directly in the framework callback transaction. No interface change; method
  visibility and signatures unchanged.
- `DbDowngradeHelper.onDowngrade(SQLiteDatabase, int, int)`: contract change —
  the caller owns the surrounding transaction. Documented in javadoc with the
  issue reference. Production caller (`DatabaseHelper.onDowngrade`) relies on
  the framework wrapper; direct test callers wrap their own `SQLiteTransaction`.
- No new seams, adapters, or interfaces.

### Data flow

Unchanged inputs/outputs. Failure flow changes from "poisoned outer + silent
rollback" to either (a) exception propagation out of the callback → framework
rollback → open fails (schema 32→33, unchanged), or (b) caught statement-level
failure → wipe fallback executes as the only child scope → outer commits the
fresh database deterministically.

### Alternatives rejected

- Re-throwing legacy upgrade failures instead of wiping: turns a recoverable
  historical path into a permanent open failure (crash loop); widens behavior
  beyond the historical intent.
- Keeping `DbDowngradeHelper`'s inner scope and removing only the wipe: leaves
  redundant nesting and bricks downgrades on recipe failure.
- Conditional ownership via `db.inTransaction()`: implicit magic; explicit
  caller ownership matches the single-authoritative-owner decision.
- SAVEPOINT-based partial rollback: explicitly non-preferred (#118).

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `DatabaseHelper.java` | Remove inner scopes in case 13 / `addIntegerColumn` / `updateFolderItemsRank` / `convertShortcutsToLauncherActivities`; keep catch semantics; drop `temp_favorites` in `createEmptyDB`; `#118` comments at each site | Category 1/3 remediation with the framework callback as sole owner; deterministic wipe cleanup (review P2) |
| `DbDowngradeHelper.java` | Drop inner `SQLiteTransaction`; document caller-owned transaction | Reconcile ownership with the framework callback (AC-3) |
| `DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest` | Wrap direct downgrade calls in caller-owned transactions | Preserve atomicity under the new contract |
| New `MigrationTransactionOwnershipTest` (organizer-instrumentation) | Three failure-injection scenarios per spec: legacy upgrade wipe persistence, first-statement downgrade failure, failure after partial recipe progress; the first two failed on `main` before the fix | AC-2/AC-4 evidence, TDD anchor |
| Pinned rollback target `rollback32/Schema32RollbackDatabaseHelper` + `Schema32RollbackDbDowngradeHelper` + `Schema32RollbackBinaryTest` (organizer-instrumentation) | Verbatim copies of the pre-schema-33 helpers (commit `866d231ffdfe2dcc8b0e550e65ea6f1301b6674c`, renamed only) executed through real `SQLiteOpenHelper` wrapping against a seeded v33 file: successful rollback (v32 shape, rows kept) and failing recipe (non-recovery pinned) | Production-faithful AC-4 evidence for the field 33→32 path |
| `.github/workflows/ci.yml` + `docs/engineering/ci-test-portfolio.md` | Focused lane `organizer-instrumentation-db-migration-tests` (API 36) running the schema/downgrade/migration suites; wire into `final-status`; portfolio row | The suites were compile-only in CI; #115 showed that hides real failures |
| `specs/118-*` | Spec + this plan | Workflow requirement |

## Migration and recovery

- No schema or rule migration. Runtime behavior changes only on failing
  upgrade/downgrade paths, where the documented intent (wipe fallback persists;
  32→33 rolls back) becomes actually true; the wipe now also cleans the
  `temp_favorites` staging table.
- Release rollback (older app over newer data): recipes are unchanged. A
  rollback executed by a binary built after this change recovers
  deterministically; a rollback to an already-built schema-32 binary keeps that
  binary's bundled pre-#118 behavior and is recorded as a residual risk in the
  spec's Non-goals.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | Spec table + code comments | review |
| AC-4 | New tests red on `main` (first two), green on fix branch; rollback-target replica green | same lane incl. `com.android.launcher3.organizer.rollback32.Schema32RollbackBinaryTest` (API 36 emulator) |
| AC-3 | Downgrade scenarios + updated direct callers compile/pass | same lane incl. `DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest` |
| AC-5 | Existing fixtures green | same lane (`DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest`) + JVM `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| Build/lint | green | `./gradlew spotlessCheck assembleLawnWithQuickstepGithubDebug` |
| Merge gate | CI `final-status` success on verified head; independent audit doc under `docs/assessment/` | high-risk gate (`risk: layout-data`/`risk: migration`) |

## Documentation updates

- [x] spec status/history
- [x] `docs/engineering/ci-test-portfolio.md` lane row
- [x] Near-code comments carrying `#118` rationale (bridge-minimality record)
- [ ] CONTEXT.md/DESIGN.md/ADR: none required (no domain-language, structural,
  or hard-to-reverse design decision beyond the recorded classification)

## Execution checklist

- [x] Failure-injection tests written first and shown failing on current `main`
      (`expected:<33> but was:<13>` upgrade, `expected:<32> but was:<33>`
      downgrade; API 36 emulator).
- [x] Minimal implementation completed.
- [x] Existing fixtures re-run green (10/10 across the four suites).
- [x] Full verification commands recorded in the PR.
- [x] PR evidence and remaining risks recorded (independent audit under
      `docs/assessment/pr-122-sqlite-migration-transaction-ownership.md`;
      residual risk of pre-#118 rollback binaries pinned by
      `rollback32.Schema32RollbackBinaryTest`).
