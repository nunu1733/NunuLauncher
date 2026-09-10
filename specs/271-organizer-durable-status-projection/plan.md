# Implementation Plan: Re-opened organizer Settings presents the durable organizer status

> Issue: #271
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- `ManualOrganizationRun.kt:226-229` — `appliedPoint` / `lastVerifiedApply` /
  `pendingRecovery` are process-local fields; `beginRecoveryPreview()`
  (`ManualOrganizationRun.kt:439`) is gated on them, and the Settings surface
  (`ManualOrganizationPreferences.kt:179-188`) renders the start entry from
  `State.Idle` with no durable input.
- The recovery store already persists everything the status needs:
  `RecoveryStore.kt` records carry `lifecycle`/`created_at_ms`/`updated_at_ms`/
  `payload_checksum`, tombstones carry `reason`/`expires_at_ms`
  (`RecoveryDbSchema.RECORD_COLUMNS`, `TABLE_RECOVERY_TOMBSTONES`).
- The derived whole-store view already exists as the inspection snapshot:
  `RecoveryInspectionSnapshot` (`RecoveryInspectionSnapshot.kt`) holds
  `Record(pointId, lifecycle, createdAtMs, updatedAtMs, checksumValid,
  formatVersion)` + `Tombstone(pointId, reason, expiresAtMs)`, published only
  by the module-owned writer path (`publishCurrentProjection`,
  `RecoveryStore.kt:1517`) and by startup reconciliation
  (`RestartReconciler.kt:74,133` → `session.rebuildInspectionSnapshot()`).
  A fresh process therefore has a fresh, generation-valid snapshot as soon as
  `reconcileAtStart()` completes (`LawnchairApp.kt:235-257` triggers it on the
  first `Launcher` resume).
- `ReadinessGate` (`ReadinessGate.kt`) states `IDLE → RECONCILING → READY/FAILED`
  and exposes `runWhenReady`; apply/recover/preview already gate on it.
- Spec 13 (`specs/13-safe-layout-application/spec.md`) §“Recovery record and
  lifecycle” + §“Retention” define the lifecycle set and the 24h-from-creation
  retention; retention constants are owned by `RetentionPolicy.kt`.
- #89 contract (`specs/89-inspection-safe-recovery-store-read/spec.md`): the
  inspection snapshot seam is the module-owned read path that never opens
  SQLite from a UI-driven read.
- The `RecoveryStorePort` surface (`Ports.kt:172`) currently exposes only
  per-point reads (`readInspectionProjection(pointId)`); no whole-store read
  exists yet.

### 失敗連鎖 (source-verified)

process death → `ManualOrganizationModule.instance` lost → re-opened Settings
collects `State.Idle` → `Idle` renders indistinguishably from "never organized"
while the recovery DB still holds `VERIFIED` (restorable) or an unresolved
record.

### 対比と先行実装

- `readInspectionProjection(pointId)` (#89) already established the
  snapshot-only, fence-gated read pattern; this issue adds the whole-store
  variant plus a closed-vocabulary derivation, reusing the same fence.
- `LifecycleReconciler` plus `RestartReconciler`/`ReadinessGate` together
  guarantee that after a completed, successful reconciliation (`READY` gate)
  the durable record rows are: `VERIFIED` (restorable, retention-managed),
  final rows (`RESTORED`/`EXPIRED` kept until eviction, `CORRUPT`,
  `INCOMPATIBLE`), or a `VERIFIED` row silently reconciled with an invalid
  checksum (`RestartReconciler.kt:173-177`). Non-final rows surface only when
  reconciliation fails or has not run, in which case the readiness gate is not
  `READY` and the projection fail-closes to `UNAVAILABLE`. The derivation maps
  this small closed set, not the full state machine.

## Design

### Modules and interfaces

1. **`OrganizerDurableStatus` (new, `application/public/OrganizerDurableStatus.kt`)**
   Closed enum: `NEVER_ORGANIZED`, `ORGANIZED_RESTORABLE`, `UNRESOLVED`,
   `RESTORED_OR_EXPIRED`, `UNAVAILABLE`. Carries no fields — this is the
   privacy boundary (DS-AC-06): the type cannot leak a payload, revision,
   digest, identity, or timestamp.

2. **`OrganizerDurableStatusDeriver` (new, pure, `application/lifecycle/`)**
   Pure function over minimal inputs (no store/platform types):
   - `DurableRecord(lifecycle: LifecycleState, createdAtMs: Long, checksumValid: Boolean)`
   - `DurableTombstone(reason: RecoveryStorePort.TombstoneReason, expiresAtMs: Long)`
   - `nowMs: Long`
   Retention windows come from `RetentionPolicy` constants (single source).
   Derivation (priority order):
   1. any record row in a non-final lifecycle `{CREATING, READY, APPLYING,
      COMMITTED_UNVERIFIED, RESTORING}` → `UNRESOLVED` (defensive: normally
      unreachable with a `READY` gate — completed reconciliation resolves or
      prunes these rows or fails the gate — but the mapping keeps the closed
      vocabulary total);
   2. any record row in `CORRUPT` or `INCOMPATIBLE` lifecycle → `UNRESOLVED`
      (final rows skipped by reconciliation candidates, so they persist after
      a completed reconciliation);
   3. any `VERIFIED` record row with `checksumValid == false` → `UNRESOLVED`
      (reachable: `RestartReconciler` reconciles unreadable rows silently
      without advancing them, so the row stays `VERIFIED` with an invalid
      checksum);
   4. any `VERIFIED` record row within retention (`nowMs < createdAtMs +
      retention`) → `ORGANIZED_RESTORABLE`;
   5. any `VERIFIED` record row past retention (lapsed, pre-eviction) →
      `RESTORED_OR_EXPIRED`;
   6. any record row in final `RESTORED` or `EXPIRED` lifecycle →
      `RESTORED_OR_EXPIRED` (the durable state for up to 24h after a
      successful restore / retention lapse, until eviction writes the
      tombstone);
   7. any retained tombstone with reason `ALREADY_RESTORED` or `EXPIRED`
      (`expiresAtMs > nowMs`) → `RESTORED_OR_EXPIRED`;
   8. any retained tombstone with reason `CORRUPT` or `INCOMPATIBLE_VERSION`
      → `UNRESOLVED` (it represented an applied result whose recovery is now
      unusable — never silently "never organized");
   9. otherwise → `NEVER_ORGANIZED` (no rows; tombstones `PRUNED_UNUSED` /
      `QUARANTINED` never represented an applied result).
   Unit-testable without Android (DS-AC-04).

3. **`RecoveryStorePort.readInspectionSnapshot()` (new port method, closed result)**
   ```kotlin
   sealed interface InspectionSnapshotRead {
       data class Value(val records: List<InspectionProjection.Record>,
                        val tombstones: List<InspectionProjection.Tombstone>) : InspectionSnapshotRead
       data object Unavailable : InspectionSnapshotRead
   }
   ```
   `RecoveryStore` implements it exactly like `readInspectionProjection`
   (`RecoveryStore.kt:427`): fence `VALID` + snapshot generation match +
   `snapshotPublisher.reader().read()`; any other fence state, missing or
   stale file → `Unavailable`. No SQLite open, no write, no lifecycle
   mutation, no tombstone purge (keeps the #89 boundary for the UI-driven
   read).

4. **`LayoutApplicationModule.durableOrganizerStatus()` (new public method)**
   Concurrency and gating mirror the existing inspection read
   (`RecoveryPreviewProtocol.inspect`): the read acquires the module run mutex
   non-blocking (contention → `UNAVAILABLE` — a writer between mark-dirty and
   republish must never be classified from the pre-mutation snapshot, per
   spec 89 §"Inspection and concurrency semantics"), then applies
   `readinessGate.runWhenReady`:
   ```kotlin
   fun durableOrganizerStatus(): OrganizerDurableStatus =
       readinessGate.runWhenReady(
           unavailable = { OrganizerDurableStatus.UNAVAILABLE },
           block = { /* tryAcquire run mutex; map store.readInspectionSnapshot()
                        through the deriver; map failure/contention → UNAVAILABLE */ },
       )
   ```
   The application module owns the projection (it owns the recovery store, the
   run mutex, and the clock); the UI only reads the closed enum. Mapping the
   snapshot values into the deriver inputs lives here (protocol layer), not in
   the store.

5. **UI seam (existing)**
   - `ManualOrganizationApplication` += `fun readDurableOrganizerStatus():
     OrganizerDurableStatus` (one more read on the existing narrow façade; no
     new module), implemented by `ProductionManualOrganizationApplication` via
     the module.
   - `ManualOrganizationRun.readDurableOrganizerStatus()` delegates to the
     application seam (read-only; no state mutation, no lock interaction).
   - `ManualOrganizationPreferences.kt`: each time `state` transitions into
     `Idle` or `Cancelled` (keyed on that condition, so an in-place cancel
     re-reads), read the status on `Dispatchers.IO` and render a `SummaryText`
     row for `ORGANIZED_RESTORABLE`, `RESTORED_OR_EXPIRED`, and `UNRESOLVED`;
     render nothing for `NEVER_ORGANIZED` and `UNAVAILABLE`. For `UNRESOLVED`,
     render the existing safe-support rows (`manual_organization_safe_terminal`
     + `manual_organization_open_diagnostics`, `onOpenDiagnostics`) after the
     status line. Any other run state renders exactly as today (active
     process-local state keeps precedence).

6. **Strings** (`values/strings.xml`, `values-ja/strings.xml`): three new
   `manual_organization_durable_status_*` strings (restorable /
   restored-or-expired / unresolved heading). The unresolved guidance reuses
   existing strings. No plurals, no placeholders.

### Data flow

- Settings (Idle/Cancelled) → `ManualOrganizationRun.readDurableOrganizerStatus()`
  → `ManualOrganizationApplication` façade →
  `LayoutApplicationModule.durableOrganizerStatus()` → run mutex tryAcquire +
  `ReadinessGate.runWhenReady` → `RecoveryStore.readInspectionSnapshot()`
  (fence-gated, snapshot-only) → snapshot records/tombstones mapped to deriver
  inputs in the protocol layer → `OrganizerDurableStatusDeriver.derive(...)`
  → closed enum back up the same path → UI render mapping.
- No step writes, mutates lifecycle, purges tombstones, or emits journal
  events; every failure maps to `UNAVAILABLE` at the module boundary.

### 変更しない seam

- Recovery record format, DB schema, lifecycle transitions, retention
  enforcement (spec 13 untouched; no schema version bump).
- `ApplyProtocol` / `RecoveryProtocol` / `RecoveryPreviewProtocol` /
  `RestartReconciler` logic.
- `ManualOrganizationRun` state machine and its transitions (only an additive
  read-only delegate method).
- Diagnostics: no new `RunEvent` fields, phases, or emissions.
- `LawnchairBackup` allowlists (projection is not persisted).

### Alternatives rejected

- **A second persisted projection table/file updated on lifecycle changes**:
  duplicates authority, needs its own retention-alignment invalidation, and
  adds a migration surface; the recovery store already persists the truth
  (spec's design decision).
- **Direct SQLite read at Settings open** (`listRetentionRecords`-style):
  violates the #89 UI-read boundary (SQLite-free inspection reads) and would
  need its own availability/probe handling.
- **Reusing `RecoveryInspectionSnapshot` types directly as the deriver input**:
  couples the pure policy to a store-owned type; the deriver takes minimal
  inputs instead.
- **Exposing `RecoveryStore` to UI**: rejected; the module owns the store
  (spec 83: "recovery storageはIssue #14のapplication moduleが所有する").

## Change set

| Path | Change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/OrganizerDurableStatus.kt` | new: closed status enum | field-free public type is the privacy boundary |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt` | new: pure derivation + minimal input types | retention policy lives next to `RetentionPolicy`; pure = JVM-testable |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` | add `readInspectionSnapshot()` + closed read result to `RecoveryStorePort` | the port is the module↔store seam |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` | implement `readInspectionSnapshot()` (fence-gated, snapshot-only) | only implementation of the port; reuses #89 fence pattern |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | add `durableOrganizerStatus()` (mutex + readiness gated, fail-closed) | module owns store, mutex, clock → owns the projection |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | add `readDurableOrganizerStatus()` to the application façade + run delegate | existing narrow façade is the UI↔module seam |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | render mapping for `Idle`/`Cancelled` | the surface named by the issue |
| `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml` | 3 new strings each | localized user-visible status text |
| `tests/unit/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriverTest.kt` | new: fixtures, boundaries, priority, invalidation | pure deriver → JVM unit tests |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/store/OrganizerDurableStatusInstrumentationTest.kt` | new: close/reopen restart scenarios per DS-AC-01/02/03/05 | durability needs real SQLite across reopen |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` | extend: durable status render scenarios incl. fail-closed + active-run precedence (DS-AC-07) | existing surface test harness with fake application |
| `CONTEXT.md`, `DESIGN.md` | domain term + seam ownership line | 正本 updates (DS-AC-08) |
| `specs/271-organizer-durable-status-projection/{spec,plan}.md` | spec/plan status updates | specs README rule |

## Migration and recovery

- No migration: no schema change, no new persisted data. The port addition is
  implemented by the only `RecoveryStorePort` implementation (`RecoveryStore`).
- Rollback: revert the PR; no durable state is created or mutated by the
  feature, so removal restores the exact prior behavior.
- Failure behavior: every failure mode of the read (store unavailable, gate
  not READY, fence not VALID, missing/stale/unreadable snapshot, mapping
  exception) maps to `UNAVAILABLE` → no status row. The read performs no
  writes and emits no journal events.

## Risk assessment

- **risk: low.** Read-only seam over existing persisted data; no layout-data
  writes, no migration, no permission/communication/dependency additions; the
  highest-risk area is the #89 boundary, which is preserved by reusing the
  fence-gated snapshot pattern verbatim.
- Privacy: the projection type is field-free; no identifiers cross the seam.

## Test and verification

| AC | Evidence | Command |
|---|---|---|
| DS-AC-01 | `OrganizerDurableStatusInstrumentationTest`: checkpoint → advance to `VERIFIED` → fresh store + reconciliation-session snapshot rebuild (process-death surrogate) → `durableOrganizerStatus()` = `ORGANIZED_RESTORABLE` | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...OrganizerDurableStatusInstrumentationTest` |
| DS-AC-02 | same test class: `VERIFIED` → `RESTORING` → `RESTORED` → close/reopen asserts the fresh `RESTORED` row; then retention eviction → `ALREADY_RESTORED` tombstone path; separate `EXPIRED` tombstone path | same |
| DS-AC-03 | same test class: a `CORRUPT` row and a checksum-invalid `VERIFIED` row (corrupted after checkpoint) → `UNRESOLVED` after a reconciliation-equivalent snapshot rebuild (session over a test-owned `RunMutex`); `INCOMPATIBLE` rows and non-final rows are covered by deriver unit tests (no legal production seeding path for `INCOMPATIBLE` row advancement) | same |
| DS-AC-04 | `OrganizerDurableStatusDeriverTest`: ±1 ms retention boundary at `createdAt + 24h`, tombstone expiry boundary, priority ordering, checksum-invalid `VERIFIED`, final `RESTORED`/`EXPIRED` rows, `PRUNED_UNUSED`/`QUARANTINED` → `NEVER_ORGANIZED`, `CORRUPT`/`INCOMPATIBLE_VERSION` tombstones → `UNRESOLVED` | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.lifecycle.*'` |
| DS-AC-05 | deriver unit test (record removed from inputs → not restorable) + instrumentation test (tombstone purged → `NEVER_ORGANIZED`; fresh store before snapshot rebuild → `UNAVAILABLE`) + render test for no-row | unit + instrumentation commands above |
| DS-AC-06 | type is field-free (code review); no `RunEvent` emission in the new path; existing diagnostics tests stay green | full unit suite command |
| DS-AC-07 | `ManualOrganizationPreferencesInstrumentationTest`: durable row present for the three informative statuses on `Idle`, absent for `NEVER_ORGANIZED`/`UNAVAILABLE`, absent during active run states, unresolved guidance rows present | instrumentation command (UI class) |
| DS-AC-08 | PR diff | review |

Regression: `./gradlew spotlessCheck` and
`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`
must stay green; the organizer instrumentation suite for touched classes re-run
via the class filter.

## Documentation updates

- [ ] `CONTEXT.md`: add "durable organizer status" domain term (one entry, no
  implementation detail).
- [ ] `DESIGN.md` §4.2: one line — the application module owns the derived,
  read-only durable status projection seam over the recovery store; UI reads
  the closed status only.
- [ ] Spec/plan status transitions (`draft` → `accepted` → `implemented`) in
  the same PRs that introduce/land the change.
- [ ] No ADR: no high-cost, code-invisible decision remains (derive-vs-persist
  is recorded in the spec's design decision and is cheap to reverse).

## Execution checklist

1. [ ] Reproduce current behavior: reopen after a durable `VERIFIED` point
   presents a bare `Idle` (instrumentation assertion on the existing surface —
   the failing test for the missing behavior).
2. [ ] Add `OrganizerDurableStatus` enum + pure deriver + unit tests
   (DS-AC-04); the reproduction-mapping tests fail before the deriver exists.
3. [ ] Add `RecoveryStorePort.readInspectionSnapshot()` + `RecoveryStore`
   implementation (fence-gated, snapshot-only).
4. [ ] Add `LayoutApplicationModule.durableOrganizerStatus()` with run-mutex +
   readiness gating and fail-closed mapping.
5. [ ] Extend the `ManualOrganizationApplication` façade + `ManualOrganizationRun`
   delegate.
6. [ ] Settings render mapping + strings (EN/ja); the step-1 test now passes
   (DS-AC-01/07).
7. [ ] Instrumentation tests: durable status across close/reopen
   (DS-AC-02/03/05) and Settings render scenarios (DS-AC-07).
8. [ ] Run verification commands; update docs (DS-AC-08); open PR (Refs/Closes
   per workflow).
