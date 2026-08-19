# Implementation Plan: Read-only revision-bound recovery preview

> Issue: #84
> Spec: [spec.md](./spec.md)
> Status: draft — **Stage A only. No production implementation may begin until this spec and plan are accepted.**

## Current evidence

The accepted application seam presently exposes only `apply(ValidatedLayoutPlan)` and `recover(RecoveryRequest)` from `LayoutApplicationModule`. `RecoveryProtocol` obtains the run mutex, validates the recovery store and record, acquires the organizer writer lease, captures current state, compares `expectedCurrentRevision`, and then mutates lifecycle/layout state. It is consequently unsuitable for discovering recovery status before confirmation. `RecoveryStorePort` is internal and returns `StoredRecord`, whose record fields include manifests, digests, pre-revision, lifecycle, and metadata; it is not an acceptable UI/coordinator dependency.

| Confirmed source | Current behavior relevant to #84 | Planning consequence |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Composes `ApplyProtocol`, `RecoveryProtocol`, `RestartReconciler`, one `RunMutex`, and `ReadinessGate`; recovery diagnostics read the store only during actual recovery. | Add one third protocol at this composition root; do not add a UI/recovery-store shortcut. |
| `.../protocol/RecoveryProtocol.kt` | Maps store availability, record/tombstones, checksum, format, lifecycle, mutex, lease, revision, and lock conditions before the write portion. | Reuse only the pre-mutation classifications as an internal read-only projection; preserve the actual recovery protocol unchanged. |
| `.../protocol/Ports.kt` | `RecoveryStorePort.readRecord()` is read-only, but current `readTombstone()` starts a write transaction to lazily purge expired tombstones. `LayoutWriterPort` supports non-blocking lease acquisition and authoritative capture. | Add a separate inspection-only tombstone read that cannot purge, and use existing lease/capture primitives without calling any write/reload method. |
| `.../store/RecoveryStore.kt` | Store availability probe and record reads already permit inspection of checksum, format, lifecycle, and tombstone reason; `VERIFIED` is the only restorable lifecycle. | No recovery DB schema or record format change is needed. The new store method must use a readable query only. |
| `.../lifecycle/LifecycleState.kt` | `VERIFIED` alone is restorable; `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, and `RESTORING` are not. | Project all live non-final non-restorable states as `UNRESOLVED`; do not infer a safe recovery action. |
| `specs/13-safe-layout-application/spec.md` | `RecoveryRequest` contains the reviewed current revision; `recover` must revalidate inside its transaction. | Preview never changes this mutation request/result or its TOCTOU guarantee. |
| `docs/engineering/organizer-diagnostics.md` | Recovery events represent explicit recovery operations and must never contain manifests, digests, records, or rows. | Inspection is diagnostically silent; it creates no synthetic recovery run/lifecycle. |
| Issue #52 working spec | UI must use #84’s read-only seam before explicit confirmation and must not access the recovery store. | Provide an opaque context/confirmation handoff that lets #52 display status without raw revision or payload access. |

The baseline evidence is limited to the `main` state inspected on 2026-08-19. Production implementation must refresh these observations against the accepted Stage A commit before changing code.

## Design

### Modules and interfaces

The public result types are new values, not extensions of `RecoveryResult`. Existing `ApplyResult`, `RecoveryResult`, `RecoveryRequest`, `recover`, and their enum members remain exactly as specified by Spec 13.

| File | Change | Responsibility and boundary |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryPreview.kt` **(new)** | Add `RecoveryPreviewRequest`, opaque `RecoveryPreviewContext`, opaque `RecoveryPreviewConfirmation`, `RecoveryPreviewResult`, `RecoveryPreviewSummary`, `RecoveryPreviewRejection`, and `RecoveryPreviewUnavailable`. | Defines closed, platform-free data values only. Constructors for the context and confirmation are non-public outside the application module; neither type exposes revision, manifest, digest, row, item, or profile data. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocol.kt` **(new)** | Implement I0–I5 from the accepted spec. | Owns the read-only ordering, run mutex, availability/record/tombstone mapping, non-blocking writer lease, authoritative capture, freshness/lock checks, and safe projection. It cannot call mutation/reload/diagnostic methods. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewContextIssuer.kt` **(new, internal)** | Convert an already fresh canonical application capture into an opaque `RecoveryPreviewContext`; convert a `RecoveryPreviewConfirmation` to the existing `RecoveryRequest`. | This is the only capability issuer/consumer. It stays application/integration-internal so UI/coordinator code cannot read a raw `RevisionId` or construct a confirmation request from one. It does not write or acquire a lease. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Compose the preview protocol and issuer; expose `inspectRecovery(RecoveryPreviewRequest)` through the same readiness policy as `apply`/`recover`; make the context issuer available only to the application/integration boundary. | The only behavioral public entry added by #84 is `inspectRecovery`. `recover(RecoveryRequest)` continues to be the only mutation path. No preview diagnostic is emitted. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` | Add an internal read-only tombstone lookup with explicit no-maintenance semantics, e.g. `readTombstoneForInspection(pointId)`. | Prevents inspection from calling the existing lazy-purge `readTombstone`; preserves the port’s internal status and does not expose it publicly. Existing writer/store write methods remain unchanged. |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` | Implement the inspection tombstone lookup using `readableDatabase` and one query only, without retention, purge, transaction success, `advance`, or `close/reopen`. | Production adapter evidence that lookup cannot alter tombstone retention or record lifecycle. No schema/format change. |
| `tests/unit/app/lawnchair/organizer/application/contract/RecoveryPreviewContractTest.kt` **(new)** | Test all preview public values and opaque-field restrictions. | Ensures the new API is constructible only through intended capability paths and has no platform/store/payload/revision accessor. |
| `tests/unit/app/lawnchair/organizer/application/contract/PublicSeamShapeTest.kt` | Extend public-boundary source checks. | Confirms the `public` package has values only; behavioral code remains protocol-owned, and public types cannot import/store internal recovery data. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` **(new)** | Test the full I0–I5 result matrix and zero-side-effect counters through fake ports. | The primary behavioral test surface; uses the same fake writer/store seam already used by application/recovery tests. |
| `tests/unit/app/lawnchair/organizer/application/protocol/ReadinessGateTest.kt` | Add preview-before/after reconciliation cases. | Proves gate mappings are `RECONCILIATION_PENDING` / `RECOVERY_STORE_UNAVAILABLE`, not a write-oriented recovery result. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeLayoutWriter.kt` | Add/retain observable counters and fixtures for lease, capture, reload, and write methods. | Allows protocol tests to assert no transaction/write/reload and to force stale/lock/lease conditions. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeRecoveryStore.kt` | Implement inspection-only tombstone lookup and counters for every mutating method. | Allows tests to prove no checkpoint, lifecycle advance, restore mark, prune, retention, or write-capable tombstone read. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerRecoveryInstrumentationTest.java` | Add focused production-adapter inspection cases if the existing organizer recovery driver can invoke the module seam. | Verifies the real private DB lookup plus actual writer serialization produces a typed result without layout/recovery mutation. Do not add a separate instrumentation seam. |

No `DESIGN.md` seam changes are planned: the preview remains inside the existing Layout Application module. No new ADR is planned because storage, transaction ownership, and writer serialization remain ADR-0003 / Spec 13 decisions. If implementation reveals a change to these authorities, stop rather than extending this plan.

### Data flow

1. The application/integration capture boundary takes a fresh canonical `CapturedSnapshot` using existing capture machinery and gives its internal revision only to `RecoveryPreviewContextIssuer`.
2. The issuer returns a non-serializable opaque `RecoveryPreviewContext` to the coordinating code. The coordinator may pair it only with `RecoveryPointId` in `RecoveryPreviewRequest`; it cannot inspect the revision or snapshot.
3. `LayoutApplicationModule.inspectRecovery` first applies `ReadinessGate`. It rejects an unreconciled/failed module as typed `Unavailable` without opening a writer lease or store write path.
4. `RecoveryPreviewProtocol` attempts `RunMutex`. Failure returns `Concurrent` immediately. On success, it probes store availability, reads the requested record once, and uses the new read-only tombstone query only if the record is absent.
5. The protocol checks checksum, supported format, and lifecycle. It maps tombstone/record state to the exact closed preview reason. Only `VERIFIED` remains eligible; all other non-final lifecycle states map to `UNRESOLVED`.
6. The protocol attempts `LayoutWriterPort.tryAcquireLease(ORGANIZER, token)` without blocking. It captures once under that lease and compares the fresh revision with the issuer-retained expected revision, then rejects stale/unknown lock state without building a write set.
7. On a matching `VERIFIED` point, the protocol returns `Restorable` with the safe constant effect summary and an opaque `RecoveryPreviewConfirmation`; it releases lease and mutex in `finally`.
8. After explicit user confirmation, the application/integration boundary consumes the opaque confirmation immediately to create the existing `RecoveryRequest`. It calls the unchanged `LayoutApplicationModule.recover` path. `RecoveryProtocol` still repeats lease acquisition, current capture, and its in-transaction precondition checks; it may reject stale state even after a successful preview.

### Error/result mapping

| Internal observation | Public inspection result | Rationale |
|---|---|---|
| Gate `IDLE` / `RECONCILING` | `Unavailable(RECONCILIATION_PENDING)` | No operation may observe a recovery point before restart reconciliation. |
| Gate `FAILED` or store `READ_FAILED` | `Unavailable(RECOVERY_STORE_UNAVAILABLE)` | Store state cannot be trusted; do not pretend point absence. |
| Store incompatible | `NotRestorable(INCOMPATIBLE_VERSION)` | Matches existing recovery meaning without exposing format data. |
| No record + no tombstone / pruned-unused tombstone | `NotRestorable(MISSING)` | Existing recovery maps pruned unused records to missing. |
| Expired, corrupt, incompatible, restored tombstone/record | Matching `NotRestorable` reason | Preserves typed user-actionable state. |
| Checksum invalid / unsupported record format | `NotRestorable(CORRUPT/INCOMPATIBLE_VERSION)` | Fails closed before any layout operation. |
| `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `RESTORING` | `NotRestorable(UNRESOLVED)` | Reconciliation/recovery owns those in-progress lifecycle states. |
| Module mutex occupied | `Concurrent` | Matches non-blocking operation serialization, without mutation. |
| External organizer writer lease absent | `WriterBusy` | Inspection only observes contention; it never queues. |
| Current revision mismatch | `NotRestorable(STALE_REVISION)` | Current context is no longer the user-reviewed basis. |
| Unknown/unavailable lock state | `NotRestorable(LOCK_STATE_UNAVAILABLE)` | Preserves the fail-closed lock rule. |
| All checks pass on `VERIFIED` | `Restorable` | Returns only safe effect and opaque confirmation. |

### Alternatives rejected

| Alternative | Rejection reason |
|---|---|
| Call `recover(RecoveryRequest)` to obtain the answer | It mutates lifecycle/state and violates preview-before-confirmation. |
| Expose `RecoveryStorePort`, `StoredRecord`, manifest, or a store-derived view model to UI/coordinator code | It leaks protocol internals and would permit bypass of revision validation. |
| Return `RevisionId` in the preview result so UI builds `RecoveryRequest` | Revisions are content-derived identifiers and explicitly prohibited from the UI-facing result surface; it also weakens capability control. |
| Add a new `recover(RecoveryPreviewConfirmation)` mutation overload | It changes the accepted public mutation protocol instead of retaining `recover(RecoveryRequest)`. |
| Reuse `readTombstone()` for inspection | The current implementation runs retention cleanup in a write transaction, so it cannot establish no-write behavior. |
| Run retention/reconciliation opportunistically during preview | It introduces hidden lifecycle/store mutation and changes operation timing; readiness/reconciliation already have dedicated ownership. |
| Add a diagnostics event for inspection | The accepted diagnostics contract only models actual recovery operations, and no privacy-reviewed preview projection exists. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/84-recovery-preview-seam/spec.md` | Add accepted behavior, API, invariants, scenarios, and criteria after review. | Canonical observable behavior for #84. |
| `specs/84-recovery-preview-seam/plan.md` | Add this executable plan after review. | Canonical module/test/verification ownership for #84. |
| Application public values | Add a distinct closed preview value family. | Keeps mutation result types closed and prevents store/payload leakage. |
| Application protocol | Add read-only orchestration and opaque context issuance. | Centralizes inspection alongside the only valid recovery protocol. |
| Recovery store port/adapter | Add no-maintenance tombstone read. | Makes “no store mutation” mechanically testable. |
| JVM contract/protocol tests | Add result-shape, mapping, capability, TOCTOU, and no-write tests. | The same application seam used by production must supply all evidence. |
| Existing organizer instrumentation driver | Add a small real-adapter no-write case only if needed after focused JVM coverage. | Validates the production SQLite/lease boundary without a separate harness. |

## Migration and recovery

No data migration is required. The recovery DB schema, `FORMAT_VERSION`, record codec, retention policy, tombstone retention, Launcher schema, permissions, backup allowlists, and existing recovery lifecycle remain unchanged. The only storage adapter change is an explicitly read-only query for tombstones.

The implementation must prove that a preview never creates a checkpoint, advances a lifecycle, records `RESTORING`, prunes an unused point, expires a point, or modifies the Launcher DB/model. Reverting the production code removes only the optional inspection seam; existing recovery points continue to be governed by the unchanged `recover(RecoveryRequest)` protocol. There is no release migration or feature-disable cleanup path.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| RP-AC-01 | New public-shape contract test enumerates preview values and asserts current mutation types remain unchanged. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.contract.*'` |
| RP-AC-02 | `VERIFIED` record + matching context fixture returns exact `Restorable` summary/capability. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest'` |
| RP-AC-03 | Parameterized unit matrix for all tombstone/record/lifecycle/store/mutex/lease/stale/lock outcomes. | Same focused preview protocol command. |
| RP-AC-04 | Fake port counters prove every I0–I5 row has zero writes, lifecycle changes, prune/retention calls, reloads, and diagnostics. Real adapter test proves tombstone inspection does not purge. | Focused JVM tests; add the targeted existing organizer instrumentation command once the driver method is identified. |
| RP-AC-05 | Source-boundary test and reflection/negative field test reject internal recovery-store/platform/revision/payload exposure from public types. | Contract test command above plus `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| RP-AC-06 | Handoff test obtains `Restorable`, changes every revision dimension, confirms through unchanged recovery, and expects ordinary stale result with zero committed writes. | Focused preview + existing `RecoveryProtocolTest`; organizer JVM filter. |
| RP-AC-07 | Held `RunMutex` and refused external lease fixtures return immediate `Concurrent` / `WriterBusy` with no mutation. | Focused preview protocol command. |
| RP-AC-08 | Formatting, repository contract checks, debug compilation, and CI gate complete. | `./gradlew spotlessCheck`; `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `./gradlew assembleLawnWithQuickstepGithubDebug` |

A high-risk implementation PR must carry the `risk: layout-data` label. Before merge it requires an actual successful `CI / final-status` run and an independent audit document `docs/assessment/pr-<PR番号>-<slug>.md` with head SHA, accepted #84/#13/ADR-0003 criteria, command/test surface, and CI URL. The audit must be performed in an independent session, as required by `AGENTS.md` and the GitHub workflow contract.

## Documentation updates

- [ ] Mark `specs/84-recovery-preview-seam/spec.md` and `plan.md` as `accepted` only after the requested Stage A review accepts both.
- [ ] Link the accepted spec/plan and exact handoff in Issue #84; remove `status: needs-spec` only through the repository’s normal issue workflow.
- [ ] Do **not** change `CONTEXT.md`: no domain-language meaning changes.
- [ ] Do **not** change `DESIGN.md`: #84 remains inside the existing Layout Application boundary.
- [ ] Do **not** create/update an ADR unless implementation hits a decision that meets the ADR threshold.
- [ ] Do **not** change `AGENTS.md` unless the verified command set itself changes.
- [ ] Refresh the Issue #52 working spec/plan against the accepted #84 type names and confirmation handoff before #52 production code resumes.

## Execution checklist

- [ ] Stage A review accepts both `spec.md` and `plan.md`; no production code, tests, schema, or build file changes are made before that decision.
- [ ] Refresh `main`, Issue #52 working spec, Spec 13, ADR-0003, and diagnostics contract against the accepted documentation commit.
- [ ] Add failing public-shape and protocol no-write tests before production code.
- [ ] Add preview values, opaque capability issuer, protocol, and read-only tombstone adapter using the defined boundaries.
- [ ] Prove the full result/no-side-effect matrix through the existing fake ports and focused production adapter evidence.
- [ ] Run all relevant unit, formatting, repository-contract, and debug-build checks; record exact versions/results in the PR.
- [ ] Have an independent session repeat the prescribed evidence on the PR head and write the required high-risk audit record.
- [ ] Record the accepted request/result types, capability lifecycle, stale/TOCTOU evidence, no-write evidence, and #52 resumption condition in the Issue/PR handoff.

## Stop conditions

Stop Stage B and open an owning application-contract follow-up instead of modifying behavior if any of the following is true:

- An opaque current-context/confirmation capability cannot be issued and consumed without exposing a raw `RevisionId` to UI-facing code.
- The existing `recover(RecoveryRequest)` path cannot consume the confirmation through an internal adapter without adding a new public mutation overload or weakening its in-transaction checks.
- A true read-only tombstone lookup cannot be implemented without a recovery-store schema/lifecycle/retention change.
- Inspection would require a writer lease that blocks, queues, transfers ownership, or modifies a record/layout/model.
- The accepted specification, Spec 13, ADR-0003, the diagnostics contract, or #52’s accepted working artifact conflicts with the resulting behavior.

## References

- [Issue #84](https://github.com/nunu1733/NunuLauncher/issues/84)
- [Spec 13: Safe layout application and recovery](../13-safe-layout-application/spec.md)
- [ADR-0003: Recovery point storage](../../docs/adr/0003-organizer-recovery-point-storage.md)
- [Issue #52 working specification](https://github.com/nunu1733/NunuLauncher/blob/issue-52-manual-full-organization-vertical-slice/specs/52-manual-full-organization-vertical-slice/spec.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
- [Repository workflow and high-risk evidence gate](../../docs/project/github-workflow.md)
