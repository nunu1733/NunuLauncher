# Implementation Plan: Read-only revision-bound recovery preview

> Issue: #84
> Spec: [spec.md](./spec.md)
> Status: draft — **Stage A only. No production implementation may begin until this spec and plan are accepted.**

## Current evidence

The accepted application seam currently exposes `apply(ValidatedLayoutPlan)`, `recover(RecoveryRequest)`, and startup reconciliation. `RecoveryProtocol` obtains the run mutex, validates the recovery store and record, acquires the organizer writer lease, captures current state, compares `expectedCurrentRevision`, and then mutates lifecycle/layout state. It is consequently unsuitable for discovering recovery status before confirmation. `RecoveryStorePort` is internal and returns `StoredRecord`, whose record fields include manifests, digests, pre-revision, lifecycle, and metadata; it is not an acceptable UI/coordinator dependency.

| Confirmed source | Current behavior relevant to #84 | Planning consequence |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Composes `ApplyProtocol`, `RecoveryProtocol`, `RestartReconciler`, one `RunMutex`, and `ReadinessGate`; recovery diagnostics read the store only during actual recovery. No accepted external preview-capture seam exists. | Add one self-capturing preview protocol at this composition root; do not depend on #83’s proposed/blocked capture composition. |
| `.../protocol/RecoveryProtocol.kt` | Maps store availability, record/tombstones, checksum, format, lifecycle, mutex, lease, revision, and lock conditions before the write portion. It already receives `Clock`, though its preflight does not yet enforce logical retention. | Reuse only the pre-mutation classifications as an internal read-only projection; retain the actual mutation request/result shapes while adding retention preflight. |
| `.../protocol/Ports.kt` | `RecoveryStorePort.readRecord()` is read-only, but current `readTombstone()` starts a write transaction to lazily purge expired tombstones. `LayoutWriterPort` supports non-blocking lease acquisition and authoritative capture. | Add a separate inspection-only tombstone read that cannot purge. Use existing lease/capture primitives; acquiring the lease is allowed only for capture, never write/reload. |
| `.../store/RecoveryStore.kt` | Store availability probe and record reads already permit inspection of checksum, format, lifecycle, creation/update time, and tombstone reason. Tombstone retention is persisted as `expiresAtMs`; `VERIFIED` is the only restorable lifecycle. | No recovery DB schema or record format change is needed. The new store method must use a readable query only. |
| `.../lifecycle/RetentionPolicy.kt` | Pure `actionFor(record, nowMillis)` implements 24-hour `VERIFIED` expiry and 24-hour final/tombstone retention; no I/O occurs. | Use the same policy with `Clock` in both preview and `RecoveryProtocol` preflight, without invoking cleanup. |
| `.../lifecycle/LifecycleState.kt` | `VERIFIED` alone is restorable; `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, and `RESTORING` are not. | Project all live non-final non-restorable states as `UNRESOLVED`; do not infer a safe recovery action. |
| `specs/13-safe-layout-application/spec.md` | `RecoveryRequest` contains the reviewed current revision; `recover` must revalidate inside its transaction; verified points last 24 hours. | Preview never changes the public mutation contract. Confirmation delegates internally to the same request path, which must enforce expiry and stale rules again. |
| `docs/engineering/organizer-diagnostics.md` | Recovery events represent explicit recovery operations and must never contain manifests, digests, records, or rows. | Inspection is diagnostically silent; it creates no synthetic recovery run/lifecycle. |
| Issue #52 working spec | UI must use #84’s read-only seam before explicit confirmation and must not access the recovery store. | #84 supplies opaque preview state; the application-owned confirmation executor, not UI/coordinator code, turns it into the existing request. |

The baseline evidence is limited to the `main` state inspected on 2026-08-19. Production implementation must refresh these observations against the accepted Stage A commit before changing code.

## Design

### Modules and interfaces

The public preview result types are new values, not extensions of `RecoveryResult`. Existing `ApplyResult`, `RecoveryResult`, `RecoveryRequest`, `apply`, and `recover(RecoveryRequest)` remain exactly as specified by Spec 13. In particular, no caller-facing overload of `recover` is added.

| File | Change | Responsibility and boundary |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryPreview.kt` **(new)** | Add `RecoveryPreviewResult`, `RecoveryPreviewSummary`, `RecoveryPreviewRejection`, `RecoveryPreviewUnavailable`, and opaque `RecoveryPreviewConfirmation`. | Defines closed, platform-free data values only. The confirmation constructor and all revision access remain non-public; the result has no store, payload, revision, row, item, profile, or mutable lease accessor. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocol.kt` **(new)** | Implement I0–I5 from the accepted spec. | Owns read-only ordering: readiness, mutex, store/retention classification, non-blocking capture-only writer lease, lock check, safe summary, and confirmation creation. It cannot call mutation/reload/diagnostic methods. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewConfirmationExecutor.kt` **(new, internal)** | Consume opaque confirmation, construct `RecoveryRequest` privately, and immediately delegate to existing `RecoveryProtocol.recover`. | The constructed request is not returned or exposed to UI/coordinator code. This is internal delegation, not a new public recovery protocol. #52’s integration layer receives only the executor’s `RecoveryResult` outcome. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Compose preview protocol and internal confirmation executor; expose `inspectRecovery(pointId)` through existing readiness policy; bind the executor only at the application/integration composition boundary. | `inspectRecovery` is the sole new public behavioral seam. `recover(RecoveryRequest)` remains the sole caller-facing mutation seam. Preview emits no diagnostic. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryProtocol.kt` | Make preflight retention-aware with its existing `Clock`; use the no-maintenance tombstone lookup and classify expired record/tombstone before `RESTORING` or a write set. | Enforces the already accepted 24-hour recovery rule at confirmation time without changing public types. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` | Add `readTombstoneForInspection(pointId)` with explicit read-only/no-maintenance semantics. | The internal port remains hidden from UI. Existing writer/store write methods are unchanged. |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` | Implement the inspection tombstone lookup using `readableDatabase` and one query only; do not purge, begin a write transaction, or call retention. | Production adapter evidence that preview and preflight lookup cannot alter tombstone retention or record lifecycle. No schema/format change. |
| `tests/unit/app/lawnchair/organizer/application/contract/RecoveryPreviewContractTest.kt` **(new)** | Test all preview public values and opaque-field restrictions. | Ensures the new API is constructible only through intended capability paths and no confirmation/request leakage occurs. |
| `tests/unit/app/lawnchair/organizer/application/contract/PublicSeamShapeTest.kt` | Extend public-boundary source checks. | Confirms `public` contains values only; behavioral code remains protocol-owned, and public types cannot import/store internal recovery data. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` **(new)** | Test I0–I5 result matrix, expiry boundaries, and zero-side-effect counters through fake ports. | Primary preview behavior surface; uses the same fake writer/store seam as application/recovery tests. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryProtocolTest.kt` | Add retention preflight and confirmation delegation TOCTOU tests. | Proves actual recovery rejects expiry/stale state before `markRestoring`, write set, reload, or layout transaction. |
| `tests/unit/app/lawnchair/organizer/application/protocol/ReadinessGateTest.kt` | Add preview-before/after reconciliation cases. | Proves gate mappings are preview-specific `RECONCILIATION_PENDING` / `RECOVERY_STORE_UNAVAILABLE`. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeLayoutWriter.kt` | Add/retain observable counters and fixtures for lease, capture, reload, and write methods. | Allows tests to assert capture-only lease usage, release, zero transaction/write/reload, and forced contention. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeRecoveryStore.kt` | Implement inspection-only tombstone lookup and counters for every mutating method. | Allows tests to prove no checkpoint, lifecycle advance, restore mark, prune, retention, or write-capable tombstone read. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerRecoveryInstrumentationTest.java` | Add focused production-adapter inspection and retention-boundary cases if the existing organizer recovery driver can invoke the module seam. | Verifies real private DB lookup and writer serialization produces a typed non-write result. Do not add a separate harness. |

No `DESIGN.md` seam changes are planned: preview remains inside the existing Layout Application module. No new ADR is planned because storage, transaction ownership, writer serialization, and retention remain ADR-0003 / Spec 13 decisions. If implementation reveals a change to these authorities, stop rather than extending this plan.

### Data flow

1. The coordinator gives only `RecoveryPointId` to `LayoutApplicationModule.inspectRecovery`. It does not supply or receive a `RevisionId`, `CapturedSnapshot`, manifest, store record, or writer capability.
2. The module applies `ReadinessGate`. It returns `Unavailable(RECONCILIATION_PENDING)` or `Unavailable(RECOVERY_STORE_UNAVAILABLE)` without opening a writer lease or any store write path when reconciliation is not successful.
3. `RecoveryPreviewProtocol` attempts `RunMutex`. Failure returns `Concurrent` immediately. On success, it probes store availability, reads the requested record once, and uses `readTombstoneForInspection` only when the record is absent.
4. The protocol uses `Clock.nowMillis()` plus `RetentionPolicy.actionFor()` to classify an aged `VERIFIED` record as `EXPIRED` without expiring it in storage. It treats a tombstone with `expiresAtMs <= now` as `MISSING` without purging it. It maps valid retained tombstones, checksum, format, and lifecycle as specified.
5. For an unexpired `VERIFIED` point only, the protocol attempts `LayoutWriterPort.tryAcquireLease(ORGANIZER, token)` non-blockingly. On success it performs exactly one authoritative capture and verifies lock availability. No capture occurs through an external context issuer.
6. On success the protocol creates `Restorable(summary, confirmation)`, where the confirmation privately retains point ID and captured revision. It releases lease and mutex in `finally` and emits no diagnostic. No storage, lifecycle, layout, model, or reload operation is invoked.
7. After explicit user confirmation, an application-internal executor consumes the opaque confirmation, constructs `RecoveryRequest` in local scope, calls the existing `RecoveryProtocol.recover`, and returns only `RecoveryResult`. No raw request/revision crosses into the coordinator/UI.
8. `RecoveryProtocol` repeats its existing mutex/lease/current-capture and in-transaction precondition checks, now also applying the same `Clock`/`RetentionPolicy` and read-only tombstone expiry rule before it calls `markRestoring` or prepares a write set. A passed preview therefore remains conditional at expiry and revision boundaries.

### Error/result mapping

| Internal observation | Public inspection result | Rationale |
|---|---|---|
| Gate `IDLE` / `RECONCILING` | `Unavailable(RECONCILIATION_PENDING)` | No operation may observe a recovery point before restart reconciliation. |
| Gate `FAILED` or store `READ_FAILED` | `Unavailable(RECOVERY_STORE_UNAVAILABLE)` | Store state cannot be trusted; do not pretend point absence. |
| Store incompatible | `NotRestorable(INCOMPATIBLE_VERSION)` | Matches existing recovery meaning without exposing format data. |
| No record + no retained tombstone / expired tombstone / pruned-unused tombstone | `NotRestorable(MISSING)` | A point outside tombstone retention is no longer discoverable; pruned unused points are existing recovery `MISSING`. |
| Aged `VERIFIED` record | `NotRestorable(EXPIRED)` | The accepted 24-hour recovery retention applies even before cleanup runs. |
| Retained expired, corrupt, incompatible, or restored tombstone/record | Matching `NotRestorable` reason | Preserves typed user-actionable state without store maintenance. |
| Checksum invalid / unsupported record format | `NotRestorable(CORRUPT/INCOMPATIBLE_VERSION)` | Fails closed before any layout operation. |
| `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `RESTORING` | `NotRestorable(UNRESOLVED)` | Reconciliation/recovery owns those in-progress lifecycle states. |
| Module mutex occupied | `Concurrent` | Matches non-blocking operation serialization, without mutation. |
| External writer lease unavailable | `WriterBusy` | Inspection acquires the existing lease only for capture; it never queues. |
| Current capture has unknown lock state | `NotRestorable(LOCK_STATE_UNAVAILABLE)` | Preserves the fail-closed lock rule. |
| All checks pass on unexpired `VERIFIED` point | `Restorable` | Returns only safe effect and opaque confirmation. |

### Alternatives rejected

| Alternative | Rejection reason |
|---|---|
| Caller-supplied `RecoveryPreviewContext` | No accepted preview-capture seam exists outside the module, and authoritative capture is contractually lease-bound. Depending on proposed/blocked #83 composition would leave #84’s own boundary unclosed. |
| Return `RevisionId` or `RecoveryRequest` from preview/confirmation to UI/coordinator | It leaks a content-derived identifier and permits bypass of capability control. |
| Call `recover(RecoveryRequest)` to obtain the answer | It mutates lifecycle/state and violates preview-before-confirmation. |
| Expose `RecoveryStorePort`, `StoredRecord`, manifest, or a store-derived view model to UI/coordinator code | It leaks protocol internals and would permit bypass of revision validation. |
| Add a caller-facing `recover(RecoveryPreviewConfirmation)` overload | It changes the accepted public mutation protocol. The internal executor instead delegates to existing `recover(RecoveryRequest)`. |
| Reuse `readTombstone()` for inspection/preflight | The current implementation runs retention cleanup in a write transaction, so it cannot establish no-write behavior. |
| Treat elapsed retention as valid until a write triggers cleanup | It makes aged points spuriously restorable and violates Spec 13’s temporal 24-hour rule. |
| Run retention/reconciliation opportunistically during preview | It introduces hidden lifecycle/store mutation and changes operation timing; readiness/reconciliation already have dedicated ownership. |
| Add a diagnostics event for inspection | The accepted diagnostics contract only models actual recovery operations, and no privacy-reviewed preview projection exists. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/84-recovery-preview-seam/spec.md` | Add accepted behavior, API, temporal rules, invariants, scenarios, and criteria after review. | Canonical observable behavior for #84. |
| `specs/84-recovery-preview-seam/plan.md` | Add this executable plan after review. | Canonical module/test/verification ownership for #84. |
| Application public values | Add a distinct closed preview value family and opaque confirmation. | Keeps mutation result types closed and prevents store/payload/revision leakage. |
| Application protocol | Add self-capturing read-only orchestration and internal confirmation delegation. | Centralizes inspection alongside the only valid recovery protocol. |
| Recovery preflight/store port/adapter | Add no-maintenance tombstone read and existing retention re-evaluation. | Makes no-write inspection and final expiry enforcement mechanically testable. |
| JVM contract/protocol tests | Add result-shape, retention, capability, TOCTOU, and no-write tests. | The same application seam used by production must supply all evidence. |
| Existing organizer instrumentation driver | Add a small real-adapter no-write case only if needed after focused JVM coverage. | Validates the production SQLite/lease boundary without a separate harness. |

## Migration and recovery

No data migration is required. The recovery DB schema, `FORMAT_VERSION`, record codec, retention constants, Launcher schema, permissions, backup allowlists, and recovery lifecycle remain unchanged. The only storage adapter change is an explicitly read-only tombstone query. The only recovery-protocol change is to enforce the existing pure retention rule before its mutation boundary.

The implementation must prove that a preview never creates a checkpoint, advances a lifecycle, records `RESTORING`, prunes an unused point, expires a point, opens a write transaction for a tombstone, or modifies the Launcher DB/model. It must also prove that a confirmation crossing retention expiry returns `NotRestorable(EXPIRED)` before `markRestoring` or any layout write. Reverting the production code removes only the optional inspection seam and internal preflight enforcement; existing recovery points remain governed by the unchanged public `recover(RecoveryRequest)` contract. There is no release migration or feature-disable cleanup path.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| RP-AC-01 | Public-shape contract test enumerates preview values and asserts existing mutation types/methods remain unchanged. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.contract.*'` |
| RP-AC-02 | Unexpired `VERIFIED` fixture returns exact `Restorable` summary/capability under capture-only lease. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest'` |
| RP-AC-03 | Parameterized matrix for record/tombstone age boundaries, retention states, store availability, lifecycle, checksum/format, lock, mutex, and lease outcomes. | Same focused preview protocol command. |
| RP-AC-04 | Fake port counters prove every I0–I5 row has zero writes, lifecycle changes, prune/retention calls, write-capable tombstone reads, reloads, and diagnostics. Real adapter test proves lookup does not purge. | Focused JVM tests; add targeted existing organizer instrumentation command once the driver method is identified. |
| RP-AC-05 | Source-boundary and reflection/negative-field tests reject internal recovery-store/platform/revision/payload exposure and assert confirmation executor never returns `RecoveryRequest`. | Contract test command plus `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| RP-AC-06 | Confirmation handoff tests cross retention cutoff and mutate every revision dimension after `Restorable`, then assert existing recovery returns typed expiry/stale rejection with zero `markRestoring`/committed writes. | Focused preview test + `RecoveryProtocolTest`; organizer JVM filter. |
| RP-AC-07 | Held `RunMutex` and refused external lease fixtures assert immediate `Concurrent` / `WriterBusy`; capture and close counters prove short capture-only lease scope/release. | Focused preview protocol command. |
| RP-AC-08 | Formatting, repository contract checks, debug compilation, and CI gate complete. | `./gradlew spotlessCheck`; `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `./gradlew assembleLawnWithQuickstepGithubDebug` |

A high-risk implementation PR must carry the `risk: layout-data` label. Before merge it requires an actual successful `CI / final-status` run and an independent audit document `docs/assessment/pr-<PR番号>-<slug>.md` with head SHA, accepted #84/#13/ADR-0003 criteria, command/test surface, and CI URL. The audit must be performed in an independent session, as required by `AGENTS.md` and the GitHub workflow contract.

## Documentation updates

- [ ] Mark `specs/84-recovery-preview-seam/spec.md` and `plan.md` as `accepted` only after the requested Stage A review accepts both.
- [ ] Link the accepted spec/plan and exact handoff in Issue #84; remove `status: needs-spec` only through the repository’s normal issue workflow.
- [ ] Do **not** change `CONTEXT.md`: no domain-language meaning changes.
- [ ] Do **not** change `DESIGN.md`: #84 remains inside the existing Layout Application boundary.
- [ ] Do **not** create/update an ADR unless implementation hits a decision that meets the ADR threshold.
- [ ] Do **not** change `AGENTS.md` unless the verified command set itself changes.
- [ ] Refresh the Issue #52 working spec/plan against accepted #84 type names and confirmation executor handoff before #52 production code resumes.

## Execution checklist

- [ ] Stage A review accepts both `spec.md` and `plan.md`; no production code, tests, schema, or build file changes are made before that decision.
- [ ] Refresh `main`, Issue #52 working spec, Spec 13, ADR-0003, and diagnostics contract against the accepted documentation commit.
- [ ] Add failing public-shape and protocol no-write/retention tests before production code.
- [ ] Add preview values, self-capturing protocol, internal confirmation executor, read-only tombstone adapter, and recovery retention preflight using the defined boundaries.
- [ ] Prove full result/no-side-effect/retention/TOCTOU matrix through existing fake ports and focused production adapter evidence.
- [ ] Run relevant unit, formatting, repository-contract, and debug-build checks; record exact versions/results in the PR.
- [ ] Have an independent session repeat prescribed evidence on the PR head and write the required high-risk audit record.
- [ ] Record accepted result types, confirmation capability lifecycle, expiry/stale TOCTOU evidence, no-write evidence, and #52 resumption condition in the Issue/PR handoff.

## Stop conditions

Stop Stage B and open an owning application-contract follow-up instead of modifying behavior if any of the following is true:

- Self-capturing inspection cannot retain the current revision inside an opaque confirmation without exposing it or adding a caller-facing mutation overload.
- Existing `recover(RecoveryRequest)` cannot be invoked by internal confirmation delegation without exposing its request to UI/coordinator code or weakening any recheck.
- Existing recovery preflight cannot enforce the accepted retention boundary at confirmation time without changing its public mutation contract.
- A true read-only tombstone lookup cannot be implemented without a recovery-store schema/lifecycle/retention decision change.
- Inspection requires waiting, queueing, transferring a writer lease, or using the lease for a write/reload/store lifecycle operation.
- The accepted specification, Spec 13, ADR-0003, diagnostics contract, or #52’s accepted working artifact conflicts with the resulting behavior.

## References

- [Issue #84](https://github.com/nunu1733/NunuLauncher/issues/84)
- [Spec 13: Safe layout application and recovery](../13-safe-layout-application/spec.md)
- [ADR-0003: Recovery point storage](../../docs/adr/0003-organizer-recovery-point-storage.md)
- [Issue #52 working specification](https://github.com/nunu1733/NunuLauncher/blob/issue-52-manual-full-organization-vertical-slice/specs/52-manual-full-organization-vertical-slice/spec.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
- [Repository workflow and high-risk evidence gate](../../docs/project/github-workflow.md)
