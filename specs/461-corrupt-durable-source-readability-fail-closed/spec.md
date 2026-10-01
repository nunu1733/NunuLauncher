---
issue: "#461"
status: accepted
requirements:
  - AC-461-01
  - AC-461-02
  - AC-461-03
  - AC-461-04
  - AC-461-05
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

A header-magic check alone does not fix the failure class: a file that begins
with a valid SQLite header but has a corrupt body (corrupt pages after the
header) would still pass and still be opened writable. Readability is therefore
specified as a non-destructive completeness check, not a header signature.

## Outcome

Before any writable helper opens a journal-recorded recovery source, the
controller verifies — without mutating the source file or its sidecars — that
the file is an intact, readable SQLite database: a valid SQLite header, a
read-only open under a non-deleting error handler, and a passing
`PRAGMA quick_check`. Every durable recovery phase then fails closed — the
active helper is quarantined, `GridMigrationRecoveryPendingException`
propagates, and the journal and the corrupt source file are left byte-identical
— so recovery can no longer replace a corrupt source with a manufactured empty
database.

## Scope

- `validatedJournalSourceFile` additionally validates readability and
  integrity: after the existing existence check,
  (a) the first 16 bytes of the source file must equal
  `SQLite format 3\000` (raw file read; an `IOException` or a shorter file is
  also a validation failure), and
  (b) the file must open read-only —
  `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY, errorHandler)` with a
  controller-private `DatabaseErrorHandler` that never deletes or recreates the
  file — and `PRAGMA quick_check` must return the single result `ok`.
  A `SQLiteException` or `SQLiteDatabaseCorruptException` during the open or
  the check, and any non-`ok` result, is a validation failure. The probe never
  adds `CREATE_IF_NECESSARY` and always closes the database in a `finally`.
  Any mismatch throws `IllegalStateException` exactly like the existing
  validation failures, so all unreadable dims funnel into one failure branch.
- The custom `DatabaseErrorHandler` is a private static class inside
  `ModelDbController` (no new public type). It preserves the corrupt file as
  evidence: `onCorruption` deletes and recreates nothing and surfaces the
  corruption as an exception instead. The default error handler's behavior
  everywhere else is unchanged.
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
- Add one valid-header + body-corrupt fixture to the existing
  `GridMigrationTestSupport` and one regression method to the existing
  `GridMigrationFailureTest` that asserts fail-closed behavior and byte-identical
  preservation of the corrupt source. No new lane, class, or test file.
- Route the existing `GridMigrationFailureTest` (including the new method) into
  the db-migration lane (surface_db_schema), updating
  `docs/engineering/ci-test-portfolio.md` and
  `tools/repo-contract/ci_portfolio_map.yml` in the same PR.

## Non-goals

- A read-only probe through the *default* `DatabaseErrorHandler`: rejected,
  because its `onCorruption()` deletes the corrupt file — the very evidence
  fail-closed handling must preserve. The adopted probe always supplies the
  controller-private non-deleting handler; the probe is non-destructive by
  construction and never registers the custom handler on any other open path.
- Changing `DatabaseHelper`, `NoLocaleSQLiteHelper`, or the default
  `DatabaseErrorHandler` / any other open path's self-heal behavior
  (active-database admission, non-migration databases).
- Full `integrity_check` semantics (UNIQUE and index-content verification) and
  foreign-key checking: `quick_check` is the accepted cost/coverage point for
  gating a writable recovery open; neither check gates this validation.
- Modifying the existing oracle test methods or adding new test classes/lanes:
  the existing corrupt-source method stays byte-identical as the non-SQLite-dim
  oracle; the body-corrupt dim gets one new method inside the existing class.
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
  https://www.sqlite.org/fileformat2.html (confirmed 2026-10-01). Adopted as
  the cheap first predicate before the open probe.
- SQLite `PRAGMA quick_check`: like `integrity_check` except it does not verify
  UNIQUE constraints or index-content correspondence; on success it returns a
  single row `ok`, otherwise rows describing the problems; non-destructive
  read-only diagnostic running in O(N).
  https://www.sqlite.org/pragma.html (confirmed 2026-10-01). Adopted as the
  completeness gate that a header signature cannot provide (valid header +
  corrupt body detection), at lower cost than `integrity_check`.

## Behavior scenarios

### Scenario: RESTORE_PENDING journal with a corrupt source fails closed (primary regression)

Given a durable `RESTORE_PENDING` journal whose recorded source file exists but
is not an intact, readable SQLite database
When the controller reconciles the active database journal before any use of
the target
Then the reconciliation throws before any writable helper opens the source
And the active helper is quarantined, `publishedHelper()` is null, and
`tryMigrateDB` propagates `GridMigrationRecoveryPendingException`
And the journal stays `RESTORE_PENDING` with its recorded source/target
identity, and the corrupt source file remains on disk byte-identical — not
deleted, not recreated as an empty database, not modified
And no source preferences are written and no journal or `BACKUP_TABLE` metadata
is deleted.

### Scenario: Readability mismatch dims

Given a journal source file that is truncated to fewer than 16 bytes, zero
bytes, holds non-SQLite content, or holds a valid header with a corrupt body
When the controller validates the journal source
Then each mismatch reason — and any `quick_check` failure or corruption
exception during the read-only probe — fails the same readability validation
with `IllegalStateException` before any writable open
And the validation itself leaves the source file and its sidecars unchanged.

### Scenario: Recovery entry points share the seam

Given any durable recovery phase that opens or republishes the journal source —
active-database reconciliation, compensation, source-authority restoration, or
finalized-source recovery
When the recorded source fails readability validation
Then each entry point fails closed without manufacturing, publishing, or
modifying the source database.

### Scenario: Healthy and missing sources are unaffected

Given a journal whose source file exists, begins with the SQLite magic header,
and passes the read-only `quick_check` probe, or one whose source file is
missing entirely
When recovery or a new migration runs
Then behavior is unchanged: healthy sources still restore and complete,
missing sources still fail closed, and success-path migrations still finalize
And the probe adds no `CREATE_IF_NECESSARY` flag and leaves no
`-journal`/`-wal`/`-shm` sidecar behind (the favorites database runs the
default rollback-journal mode; the probe opens read-only and always closes).

## Data and state

- Reads: the journal's recorded source identity and path (existing), the first
  16 bytes of the source database file via a raw read-only file inspection, and
  a `quick_check` result through a read-only SQLite open. No writable handle is
  created and no sidecar is generated or mutated.
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
  (unchanged, non-SQLite dim oracle) passes: `tryMigrateDB` throws a
  `RuntimeException`, `publishedHelper()` is null, and the journal remains
  `RESTORE_PENDING` with its recorded source/target identity.
- [ ] AC-461-02: On an AVD, a new regression method inside the existing
  `GridMigrationFailureTest` (using a new valid-header + body-corrupt fixture in
  `GridMigrationTestSupport`) passes for a durable `RESTORE_PENDING` journal
  whose source has a valid SQLite header but a corrupt body: `tryMigrateDB`
  throws a `RuntimeException`, `publishedHelper()` is null, the journal remains
  `RESTORE_PENDING`, and the corrupt source file's raw bytes are preserved
  byte-identical (asserted on the captured pre-attempt bytes).
- [ ] AC-461-03: Readability validation is non-destructive and complete:
  truncated (fewer than 16 bytes), zero-byte, non-SQLite, and
  valid-header + body-corrupt sources — plus any `quick_check` failure or
  corruption exception during the read-only probe — all funnel into the same
  `IllegalStateException` branch before any writable open; the probe uses
  `OPEN_READONLY` (never `CREATE_IF_NECESSARY`) with the controller-private
  non-deleting error handler and always closes the database.
- [ ] AC-461-04: The existing missing-source fail-closed test and the
  grid-migration success-path tests keep their current results: a focused AVD
  run of `GridMigrationSuccessTest` plus `GridMigrationFailureTest` is recorded
  in the PR.
- [ ] AC-461-05: `GridMigrationFailureTest` is routed into the db-migration
  lane (surface_db_schema) and both `docs/engineering/ci-test-portfolio.md` and
  `tools/repo-contract/ci_portfolio_map.yml` are updated in the same PR, with
  lane↔surface edges unchanged.

## Test oracle

| AC | Evidence |
|---|---|
| AC-461-01 | The existing `GridMigrationFailureTest.restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper` (unchanged oracle; historically red, §8.6) on an AVD. |
| AC-461-02 | New body-corrupt regression method in `GridMigrationFailureTest` (red→green evidence: without the fix the valid-header + corrupt-body source is republished and the method fails; with the fix it passes with the byte-identical preservation assertion). |
| AC-461-03 | The non-SQLite dim is owned by the existing oracle method; the valid-header + body-corrupt dim by the new method; truncated/zero-byte dims and the probe's failure semantics (exception types, non-`ok` result, always-close, no `CREATE_IF_NECESSARY`) funnel into one `IllegalStateException` branch, confirmed by focused validation evidence and code review — no new permanent test for those dims (see Non-goals). |
| AC-461-04 | Focused AVD run of `GridMigrationSuccessTest` + `GridMigrationFailureTest` (all existing tests unchanged plus the one new method), recorded in the PR. |
| AC-461-05 | PR CI run: `organizer-instrumentation-db-migration-tests` green with `GridMigrationFailureTest` in the lane class list; portfolio doc and map updated in the same PR. |

## Open questions

None. Root cause, the contract-violation judgment against AC-59-06, and the fix
seam are settled in Issue #461; the readability definition and the
preservation-assertion regression were settled by review 5924983374.

## Change history

- 2026-10-01: Draft created for #461. Refs #59 #86 #458.
- 2026-10-01: Revised after review 5924983374: readability upgraded from a
  header-only magic check to a non-destructive completeness check (read-only
  open with a controller-private non-deleting `DatabaseErrorHandler` +
  `PRAGMA quick_check` == `ok`), the former read-only-probe non-goal withdrawn
  for the custom-handler probe, one new valid-header + body-corrupt fixture and
  regression method with a byte-identical preservation assertion added to the
  existing test class, and the four unreadable dims pinned to one failure
  branch. Status set to accepted (approval recorded on the issue).
