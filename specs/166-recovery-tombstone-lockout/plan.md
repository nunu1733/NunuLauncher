# Implementation Plan: Recovery tombstone admission and capacity diagnostic

> Issue: [#166](https://github.com/nunu1733/NunuLauncher/issues/166)
> Spec: [spec.md](./spec.md)
> Status: **accepted** — Sol-High conditional approval is recorded in `spec.md`.
> Risk: `layout-data`

## Current code evidence

- `RetentionPolicy.planCreate` counts every point row whose action is `Keep`,
  including `RESTORED` rows still inside the tombstone window.
- `RecoveryStore.checkpoint` currently translates admission blocking into the
  generic `CheckpointResult.StoreUnavailable`, which `ApplyProtocol` maps to
  `PreWriteRejection.RECOVERY_STORE_UNAVAILABLE` at A4.
- `writeTombstone` already preserves exact lifecycle-derived reasons and the
  24-hour expiry in the same SQLite transaction as checkpoint admission.
- The diagnostics contract derives accepted `ErrorEntry` codes from
  `PreWriteRejection`, so a new enum member automatically becomes observable
  through the existing journal/export projection once the result mapping is
  wired.

## Proposed change surface

1. `lawnchair/.../application/lifecycle/RetentionPolicy.kt`
   - Treat final non-restorable records as admission-evictable even while
     their tombstone retention window is active.
   - Add a distinct pure `CreateDecision.AdmissionBlocked` result.
2. `lawnchair/.../application/protocol/Ports.kt` and
   `application/public/Results.kt`
   - Add `CheckpointResult.AdmissionBlocked` and
     `PreWriteRejection.RECOVERY_POINT_ADMISSION_BLOCKED`.
3. `application/store/RecoveryStore.kt`
   - Return `CheckpointResult.AdmissionBlocked` only for the pure capacity
     decision; keep genuine store failures as `StoreUnavailable`.
   - Keep child-first tombstoning, point-row/chunk deletion, insertion, and
     rollback in the existing transaction.
   - When an eligible final record is moved before its normal deadline, use
     `record.updatedAtMillis + TOMBSTONE_RETENTION_MILLIS` for the tombstone
     expiry; do not extend it to checkpoint time plus 24 hours.
4. `application/protocol/ApplyProtocol.kt`
   - Map admission blocking to `RECOVERY_POINT_ADMISSION_BLOCKED` while
     retaining A4 tracking.
5. `application/diagnostics/projection/ApplyProjection.kt`
   - Project admission blocking as `CHECKPOINT_REJECTED` / A4.
6. `tests/unit/...`
   - Extend policy, fake-store, protocol, projection, and closed-code tests.
7. `tests/organizer-instrumentation/.../RecoveryStoreLifecycleTest.kt`
   - Exercise three `RESTORED` records and the fourth production checkpoint,
     including exact tombstone reason/expiry and close/reopen state.
8. `specs/13-safe-layout-application/spec.md`
   - Clarify that final non-restorable point rows may be moved to tombstones
     during admission and do not consume the three capacity-bearing slots.

## Invariants and rollback

- The recovery DB transaction must either tombstone eligible records and insert
  the new checkpoint together, or leave both the records and tombstones as
  they were.
- Early admission tombstoning preserves the original final-state deadline;
  age-based tombstoning after that deadline retains the existing further-24h
  metadata behavior.
- Before recovery completion, `RESTORING` retains the complete record and all
  manifest chunks. Finalization deletes chunks child-first, then the point row,
  and is committed with the tombstone before the result is observed.
- `VERIFIED` remains restorable for its full 24-hour window and is the only
  final state eligible for count eviction as a restorable point.
- `APPLYING`, `COMMITTED_UNVERIFIED`, and `RESTORING` are never selected for
  eviction.
- The Launcher database is not touched by checkpoint admission.
- Source rollback requires no migration; the current schema and logical
  record format remain valid. It restores the former conservative admission
  behavior without rewriting existing data.

## Verification sequence after acceptance

1. Add red-first unit tests for pure admission and typed diagnostics.
2. Implement the smallest lifecycle/protocol/store changes through the
   existing `RecoveryStorePort` seam.
3. Add and run production SQLite instrumentation coverage for the fourth
   checkpoint and failure injection.
4. Run `./gradlew spotlessCheck` and the organizer unit test surface.
5. Run relevant instrumentation tests and `assembleLawnWithQuickstepGithubDebug`.
6. Run repository-contract validators.
7. Obtain exact-head PR CI and an independent high-risk audit before merge.

## Stop conditions

- Do not weaken the 24-hour tombstone observability or delete active records.
- Do not add a new public apply-result variant; the new code remains within the
  existing `ApplyResult.Rejected` shape.
- Stop and update the spec/Issue if review requires a different retention
  decision, a new UI surface, or a schema/recovery-format change.
