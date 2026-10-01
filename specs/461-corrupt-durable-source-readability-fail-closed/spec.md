---
issue: "#461"
status: draft
requirements:
  - AC-461-01
  - AC-461-02
  - AC-461-03
  - AC-461-04
risk:
  - layout-data
updated: 2026-10-01
---

# Fail closed when a durable grid-migration recovery source is unreadable

> Issue: [#461](https://github.com/nunu1733/NunuLauncher/issues/461) · Refs #59 #86 #458

## Problem

Issue #458's focused local validation
([specs/458-semantic-test-audit/audit.md §8.6](../../specs/458-semantic-test-audit/audit.md))
detected that
`GridMigrationFailureTest.restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper`
— an existing but unrouted instrumentation test — fails deterministically on
both pixel_6 and pixel_7_pro AVDs (API 36.1, AVD-independent): given a durable
`RESTORE_PENDING` journal and a corrupt (present but unreadable) source
database, `ModelDbController.tryMigrateDB(null)` returns normally instead of
failing closed.

Commit f8bddc7944 ("fail closed on unresolved recovery and validate durable
sources") claims that journal-source validation is generalized "so recovery can
no longer manufacture or publish an empty source database". The claim holds for
a missing source but not for a corrupt one: `validatedJournalSourceFile`
(`src/com/android/launcher3/model/ModelDbController.java:816`) validates
identity, path, and existence only, never readability. A corrupt source passes,
and the subsequent writable open (`openJournalSource` →
`refreshMaxItemIdFromCommittedRows` → `getWritableDatabase`) lets the default
`DatabaseErrorHandler` silently delete the corrupt file and recreate an empty
database. `restoreSourceAuthority` then completes the only non-exception
control-flow path: an empty source is republished as authoritative, source
preferences are written, and the journal plus `BACKUP_TABLE` are deleted as
restore metadata. That reverses source-preserving recovery and violates
AC-59-06 of [spec 59](../59-preserve-source-grid-migration-failure/spec.md)
(a failed migration preserves source authority and never manufactures an empty
database — a direct layout-data loss). No rationale for an intentional change
exists, so production is judged buggy.

## Outcome

Before any writable helper opens a journal-recorded recovery source, the
controller verifies that the recorded source file is readable as a SQLite
database. Every durable recovery phase then fails closed — the active helper is
quarantined, `GridMigrationRecoveryPendingException` propagates, and the journal
and the corrupt source file are left untouched — so recovery can no longer
replace a corrupt source with a manufactured empty database.

## Scope

- `validatedJournalSourceFile` additionally validates readability: after the
  existing existence check, the first 16 bytes of the source file must equal
  `SQLite format 3\000` (raw file read; an `IOException` or a shorter file is
  also a validation failure). A mismatch throws `IllegalStateException` exactly
  like the existing validation failures.
- All four call sites inherit the check through the one shared seam:
  reconcile pre-validation (`reconcileActiveDatabaseJournal`, :561),
  compensation (`compensateAndRestore`, :595), source-authority restoration
  (`restoreSourceAuthority`, :746), and finalized-source recovery
  (`validateFinalizedJournalSource`, :796).
- Failure behavior: validation throws before `openJournalSource`, so
  `migrateGridIfNeeded` quarantines the active helper through
  `failClosedActiveRecovery` (`mOpenHelper = null`) and propagates
  `GridMigrationRecoveryPendingException`. The journal stays in its current
  phase and the corrupt source file is neither deleted nor recreated.
- Route the existing `GridMigrationFailureTest` (unchanged) into the
  db-migration lane (surface_db_schema), updating
  `docs/engineering/ci-test-portfolio.md` and
  `tools/repo-contract/ci_portfolio_map.yml` in the same PR.

## Non-goals

- A read-only `SQLiteDatabase.openDatabase` probe: not adopted, because its
  default `DatabaseErrorHandler.onCorruption()` can delete the corrupt file —
  the very evidence fail-closed handling must preserve — and it opens SQLite
  machinery for what is a byte-level identity check.
- Changing `DatabaseErrorHandler`, `DatabaseHelper`, `NoLocaleSQLiteHelper`, or
  any other open path's self-heal behavior (active-database admission,
  non-migration databases).
- Modifying the existing regression test or adding new tests: the existing
  corrupt-source test remains the sole regression oracle for this contract.
- Changing any other #59, #86, or #458 contract: tri-state encoding, placement
  policy, backup policy, journal phases, or UI behavior.
- No database schema change, no migration, no new write path.

## Domain language

None. Journal-phase vocabulary (`RESTORE_PENDING` etc.) and "source authority"
remain owned by spec 59.

## Prior art

- SQLite file format, "Magic Header String" (§1.3.1) and "The Database Header"
  (§1.3): every valid SQLite database file begins with the 16 bytes
  `53 51 4c 69 74 65 20 66 6f 72 6d 61 74 20 33 00` (`"SQLite format 3\000"`).
  https://www.sqlite.org/fileformat2.html (confirmed 2026-10-01). Adopted as the
  minimal readable-database predicate checked before any writable open.

## Behavior scenarios

### Scenario: RESTORE_PENDING journal with a corrupt source fails closed (primary regression)

Given a durable `RESTORE_PENDING` journal whose recorded source file exists but
is not a readable SQLite database
When the controller reconciles the active database journal before any use of
the target
Then the reconciliation throws before any writable helper opens the source
And the active helper is quarantined, `publishedHelper()` is null, and
`tryMigrateDB` propagates `GridMigrationRecoveryPendingException`
And the journal stays `RESTORE_PENDING` with its recorded source/target
identity, and the corrupt source file remains on disk with its corrupt bytes —
not deleted and not recreated as an empty database
And no source preferences are written and no journal or `BACKUP_TABLE` metadata
is deleted.

### Scenario: Readability mismatch dims

Given a journal source file that is truncated to fewer than 16 bytes, zero
bytes, or holds non-SQLite content
When the controller validates the journal source
Then each mismatch reason fails the same readability validation with
`IllegalStateException` before any writable open.

### Scenario: Recovery entry points share the seam

Given any durable recovery phase that opens or republishes the journal source —
active-database reconciliation, compensation, source-authority restoration, or
finalized-source recovery
When the recorded source fails readability validation
Then each entry point fails closed without manufacturing, publishing, or
modifying the source database.

### Scenario: Healthy and missing sources are unaffected

Given a journal whose source file exists and begins with the SQLite magic
header, or one whose source file is missing entirely
When recovery or a new migration runs
Then behavior is unchanged: healthy sources still restore and complete,
missing sources still fail closed, and success-path migrations still finalize.

## Data and state

- Reads: the journal's recorded source identity and path (existing), plus the
  first 16 bytes of the source database file via a raw read-only file
  inspection. No database is opened for validation.
- Persists: nothing. The fix adds no durable state, no schema change, no
  migration, and no backup/restore impact.
- Layout: no layout items are written by this change; it prevents the
  empty-source republish that loses layout authority (AC-59-06).

## Permissions, privacy, and security

None — no new permissions and no external communication; the change only
inspects an app-private database file before opening it.

## Accessibility and localization

None — no UI change.

## Acceptance criteria

- [ ] AC-461-01: On an AVD, the existing
  `GridMigrationFailureTest.restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper`
  (unchanged) passes: `tryMigrateDB` throws a `RuntimeException`,
  `publishedHelper()` is null, and the journal remains `RESTORE_PENDING` with
  its recorded source/target identity; the corrupt `SOURCE_DB` file remains on
  disk with its corrupt bytes (not deleted, not recreated as an empty
  database).
- [ ] AC-461-02: The existing missing-source fail-closed test and the
  grid-migration success-path tests keep their current results: a focused AVD
  run of `GridMigrationSuccessTest` plus `GridMigrationFailureTest` is recorded
  in the PR.
- [ ] AC-461-03: A journal source that is truncated (fewer than 16 bytes),
  zero-byte, or non-SQLite fails readability validation with
  `IllegalStateException` before any writable open; all mismatch reasons funnel
  into the same validation-failure branch.
- [ ] AC-461-04: `GridMigrationFailureTest` is routed into the db-migration
  lane (surface_db_schema) and both `docs/engineering/ci-test-portfolio.md` and
  `tools/repo-contract/ci_portfolio_map.yml` are updated in the same PR, with
  lane↔surface edges unchanged.

## Test oracle

| AC | Evidence |
|---|---|
| AC-461-01 | The existing `GridMigrationFailureTest.restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper` (unchanged oracle; red before the fix, green after) on a pixel_6 API 36.1 AVD. Corrupt-file preservation is recorded in the same run evidence. |
| AC-461-02 | Focused AVD run of `GridMigrationSuccessTest` + `GridMigrationFailureTest` (all existing tests, unchanged results), recorded in the PR. |
| AC-461-03 | The non-SQLite dim is owned by the existing corrupt-source oracle test; the truncated/zero-byte dims funnel into the same `IllegalStateException` branch and are confirmed by focused validation evidence and code review — no new permanent test (see Non-goals). |
| AC-461-04 | PR CI run: `organizer-instrumentation-db-migration-tests` green with `GridMigrationFailureTest` in the lane class list; portfolio doc and map updated in the same PR. |

## Open questions

None. Root cause, the contract-violation judgment against AC-59-06, and the fix
seam are settled in Issue #461.

## Change history

- 2026-10-01: Draft created for #461. Refs #59 #86 #458.
