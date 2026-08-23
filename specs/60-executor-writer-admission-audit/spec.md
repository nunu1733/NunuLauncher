---
issue: "#60"
status: implemented
requirements:
  - AC-01-writer-inventory
  - AC-02-executable-allowlist
  - AC-03-fifo-exactly-once
  - AC-04-reload-supersession
  - AC-05-binder-future
  - AC-06-nested-transaction
  - AC-07-restart-evidence
updated: 2026-08-24
---

# Executor and shared-writer admission audit follow-ups

## Problem

The Issue #44 audit confirmed the `LayoutWriteCoordinator` mechanism but left
executor and shared-writer safety unproven (see
`docs/assessment/issue-44-shared-writer-audit.md`, rows tracked in #60):

- No complete, executable inventory of runtime `favorites`/DB-file writers;
  the planned source-scan allowlist does not exist.
- `MODEL_EXECUTOR` admission is confirmed for only two named paths; nothing
  prevents a new ungated caller.
- The Binder operation-future + `MODEL_EXECUTOR.submit(...).get()` sequence,
  including release on the deferred-callback thread, is untested for
  self-wait.
- A-then-B organizer reload supersession, stale completion rejection,
  cancellation, timeout, and exactly-one-terminal-signal are untested.
- Deferred FIFO ordering is untested and a throwing callback breaks
  exactly-once for later entries (no per-entry exception isolation in
  `LayoutWriteCoordinator.release`).
- Nested/reentrant `SQLiteTransaction` through `ModelDbController` (inner
  close/failure) is unverified.
- Process death/restart with an active recovery lifecycle has protocol-level
  tests only; no device-level evidence exists.

## Outcome

Every sequence listed in Issue #60 is recorded as confirmed, disproven, or
unsupported, each with a deterministic test or exact source evidence, in an
updated assessment. Confirmed defects are fixed through the existing writer
ownership model (`LayoutWriteCoordinator`, lease kinds, `runOrDefer`); no
parallel public coordinator or seam is added.

### AC-01 writer inventory

The assessment enumerates every runtime `favorites`/DB-file writer with file
and line references and its lease/admission status.

### AC-02 executable allowlist

A test fails when a `favorites`/DB-file mutation or a tokenless
`MODEL_EXECUTOR` caller reaches `ModelDbController` outside the inventoried
allowlist, keeping the inventory aligned with the runtime source in CI.

### AC-03 FIFO exactly-once

Deferred callbacks run in FIFO order; one throwing callback cannot prevent,
duplicate, or reorder terminal handling of later entries.

### AC-04 reload supersession

A-then-B organizer reloads supersede, stale completions are rejected,
cancellation and timeout are terminal exactly once per request.

### AC-05 Binder future

The Binder operation-future sequence is proven not to self-wait on
`MODEL_EXECUTOR`, including release from the deferred-callback thread.

### AC-06 nested transaction

Nested `SQLiteTransaction` through `ModelDbController` uses Android's
whole-unit transaction contract. When every nested scope is marked successful,
the outermost close commits all writes. When any nested scope closes without
success, the whole unit rolls back at the outermost close, even if the outer
scope later calls `commit()`; nested `SQLiteTransaction` does not provide
SAVEPOINT isolation. Inner close/failure must not release the outer lease
early.

Issue #117 erratum: the original AC-06 evidence and comments incorrectly
described inner-only rollback as savepoint isolation. The corrected contract
applies across supported Android API levels and does not change coordinator
lease/re-entry behavior.

### AC-07 restart evidence

Process death/restart with active recovery lifecycle is covered by test where
deterministically possible; otherwise recorded as unsupported with exact
source evidence and a tracking note.

## Scope

- `LayoutWriteCoordinator`, `ModelDbController`, `LauncherDbUtils.
  SQLiteTransaction`, `LauncherProvider`, `LauncherModel`, `ModelWriter`,
  `OrganizerModelReloadAdapter`, and the writer inventory paths.
- Instrumentation and unit tests over the existing seams.
- Assessment and Issue updates only as documentation.

## Non-goals

- No new public coordinator, lease kind, or parallel seam.
- No run-journal / `applyStage` / `RESTART_RECONCILED` event implementation
  (owned by the diagnostics implementation issue #67); this issue only
  records where such events will attach.
- No Lawnchair 16 changes, no schema migration.
