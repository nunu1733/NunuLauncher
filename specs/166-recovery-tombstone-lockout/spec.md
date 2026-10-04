---
issue: "#166"
status: accepted
requirements:
  - TOMBSTONE-ADMISSION-DECISION
  - CAPACITY-ERROR-OBSERVABLE
  - RESTORED-FOURTH-CHECKPOINT
  - ACTIVE-CAPACITY-FAIL-CLOSED
  - RECOVERY-GUARANTEES-PRESERVED
risk:
  - layout-data
updated: 2026-08-31
---

# Recovery tombstones do not lock out the next apply

The GitHub [Issue #166](https://github.com/nunu1733/NunuLauncher/issues/166)
body is authoritative for the device reproduction and the observed journal
evidence. This specification defines the admission decision and the
observable failure code needed to close that issue.

## Problem

After a successful apply is recovered, its recovery point enters lifecycle
`RESTORED` for 24 hours so that a later request can receive the typed
`ALREADY_RESTORED` result. The current checkpoint admission counts three such
final records as capacity-bearing recovery points. A fourth apply is therefore
rejected with the generic `RECOVERY_STORE_UNAVAILABLE`, even though the three
records are no longer restorable and their metadata can be retained in the
tombstone table.

## Decision

The recovery-store guarantee is preserved, but admission is corrected to honor
the existing retention contract that tombstones do not count toward the three
non-expired recovery-point limit.

When checkpoint admission runs, final non-restorable records
(`RESTORED`, `EXPIRED`, `CORRUPT`, and `INCOMPATIBLE`) are eligible to move to
the tombstone table immediately, even when their 24-hour tombstone window has
not elapsed. The move and creation of the replacement checkpoint remain one
recovery-database transaction. The tombstone keeps its exact typed reason and
the original retention deadline (`updatedAtMs + 24h`) when moved early, so
this does not shorten or extend the period in which
`ALREADY_RESTORED`, `EXPIRED`, `CORRUPT`, or `INCOMPATIBLE_VERSION` can be
reported. A record that is converted after its existing deadline keeps the
current age-based behavior of starting a further 24-hour metadata tombstone.
`VERIFIED` points remain restorable for 24 hours and continue to count toward
capacity; active/unresolved records are never evicted.

If all three capacity-bearing records are active/unresolved, checkpoint
admission returns a new typed `RECOVERY_POINT_ADMISSION_BLOCKED` rejection.
Genuine store availability, read, or write failures continue to use
`RECOVERY_STORE_UNAVAILABLE` or the existing checkpoint failure codes.

No recovery format, database schema, Launcher layout, recovery-point payload,
or ADR-0003 guarantee changes.

## Scope

- Update the pure retention/admission decision and its production SQLite
  caller so final non-restorable records are tombstoned before capacity is
  evaluated.
- Preserve exact tombstone reasons, expiry, transactionality, close/reopen
  validation, and rollback behavior.
- Add the capacity-exhaustion code to the application rejection enum and the
  existing diagnostics projection/export path.
- Add unit coverage for the pure policy, the fake recovery-store seam, the
  apply protocol diagnostic event, and production SQLite admission.
- Update the retention contract and Issue #166 implementation plan with the
  accepted decision and verification evidence.

## Non-goals

- No reduction of the 24-hour retention window for restorable `VERIFIED`
  points or for tombstone metadata.
- No eviction of `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING` records.
- No change to recovery restore behavior, lifecycle transitions, checkpoint
  payload format, database schema, backup policy, or Launcher3 bridge.
- No new UI, permission, transport, or diagnostics field. The existing
  privacy-safe journal/export surface is the approved observation surface.
- No change to the #164 A7 fix or its verification.

## Behavior scenarios

### Three restored points permit the fourth checkpoint

Given three recovery points in lifecycle `RESTORED`, each still within its
24-hour tombstone window
When a fourth checkpoint is admitted
Then the three records are atomically moved to tombstones with reason
`ALREADY_RESTORED`
And the fourth checkpoint reaches `READY`
And each prior point remains queryable as an `ALREADY_RESTORED` tombstone until
its original `RESTORED.updatedAtMs + 24h` deadline, without an expiry extension.

### Three active points remain fail-closed

Given three recovery points in `APPLYING`, `COMMITTED_UNVERIFIED`, or
`RESTORING`
When a new checkpoint is admitted
Then no prior point is evicted, no new record is committed, and the result is
`RECOVERY_POINT_ADMISSION_BLOCKED`.

### Mixed active and final records

Given active records plus final non-restorable records
When a new checkpoint is admitted
Then only final non-restorable records may move to tombstones, active records
remain untouched, and admission succeeds whenever fewer than three
capacity-bearing records remain.

### Genuine store failure remains distinguishable

Given the recovery store is unavailable before admission or a checkpoint write
fails
When apply is attempted
Then the existing `RECOVERY_STORE_UNAVAILABLE` or checkpoint failure code is
reported, not `RECOVERY_POINT_ADMISSION_BLOCKED`.

### Transactional failure preserves the prior set

Given final records are eligible for tombstoning and the replacement insert
fails before commit
When the transaction ends
Then the old recovery records and tombstones remain exactly as before the
attempt, and no Launcher state is changed.

## Acceptance criteria

- [ ] **AC-166-01 — Admission decision is explicit and compatible:** The
  accepted spec and retention contract state that final non-restorable records
  are retained as exact 24-hour tombstones but do not consume the three
  capacity-bearing point slots; active/unresolved records remain protected.
- [ ] **AC-166-02 — Capacity rejection is observable:** A distinct
  `RECOVERY_POINT_ADMISSION_BLOCKED` code is emitted through the existing
  `CHECKPOINT_REJECTED` / A4 diagnostics path, while generic store failures
  keep their existing code.
- [ ] **AC-166-03 — Three RESTORED regression:** A production-equivalent
  retention/admission test proves that three `RESTORED` records allow a fourth
  checkpoint, preserve all three `ALREADY_RESTORED` tombstones, and leave the
  fourth point `READY` after close/reopen validation. It also proves that each
  tombstone expires at the original `RESTORED.updatedAtMs + 24h` deadline,
  rather than `checkpointNow + 24h`.
- [ ] **AC-166-04 — Active capacity remains fail-closed:** Three active records
  reject a fourth attempt with the new typed code, without evicting records,
  creating a checkpoint, or mutating Launcher state. A repeated ordinary
  checkpoint attempt without rebuilding the inspection snapshot returns the
  same typed code, proving a proven no-commit rejection does not dirty the
  snapshot fence.
- [ ] **AC-166-05 — Atomicity and reason preservation:** Mixed-state, expiry
  boundary, failure-injection, and rollback tests prove that tombstone reason,
  expiry, transactionality, and the existing recovery guarantees are intact.
- [ ] **AC-166-06 — Scope guard:** No schema, recovery format, public result
  variant, UI, permission, transport, planner, or #164 behavior changes.
- [ ] **AC-166-07 — Independent high-risk evidence:** The implementation PR
  passes the `risk: layout-data` gate with exact-head `CI / final-status` and an
  independent audit record.

## Test oracle

| AC | Evidence |
|---|---|
| AC-166-01 | Accepted spec plus the updated retention section in spec 13; review records the ADR-0003 guarantee comparison. |
| AC-166-02 | `ApplyProtocolDiagnosticsTest` and `ApplyProjectionTest` assert the code, family, phase, and A4 stage; serializer/export contract remains green. |
| AC-166-03 | JVM policy/fake-store regression plus production `RecoveryStore` instrumentation test with three `RESTORED` points, fourth checkpoint, tombstone reads, and reopen. |
| AC-166-04 | JVM `ApplyProtocolTest` with three active points and zero writer writes; policy test asserts `AdmissionBlocked`; production `RecoveryStore` instrumentation repeats the rejected checkpoint without rebuilding the inspection snapshot and verifies all active rows remain. |
| AC-166-05 | Clock-controlled expiry boundary, production mixed active/final admission with exact `RESTORED`/`CORRUPT`/`INCOMPATIBLE` reasons, and production fault-injection tests assert no partial eviction, exact tombstone metadata, and no early-move expiry extension. |
| AC-166-06 | Existing public-seam, schema, backup-exclusion, and diagnostics contract tests; diff inspection. |
| AC-166-07 | `spotlessCheck`, organizer unit tests, relevant instrumentation tests, debug build, repository-contract checks, successful PR CI, and independent audit. |

## Open questions

- Sol-High reviewed the decision on 2026-08-31 and granted conditional
  approval. The review required explicit physical row/chunk deletion,
  same-transaction tombstone creation, original-deadline expiry preservation,
  and a distinct A4 checkpoint-admission result; those conditions are included
  in this spec and plan.
- ADR-0003 needs no change: the separate recovery DB, complete-record
  requirement before recovery completion, and recovery guarantees remain
  unchanged.

## Change history

- 2026-08-31: Drafted from Issue #166, spec 13 retention, ADR-0003, and the
  current `RetentionPolicy`/`RecoveryStore` admission path. Proposed decision
  keeps exact 24-hour tombstone observability while removing non-restorable
  final records from capacity pressure.
- 2026-08-31: Accepted after Sol-High review. The diagnostic code is
  `RECOVERY_POINT_ADMISSION_BLOCKED`; checkpoint admission uses
  `CHECKPOINT_REJECTED` / A4, and early tombstones retain the original final
  state's `updatedAtMs + 24h` expiry deadline.
