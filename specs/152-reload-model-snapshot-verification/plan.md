---
issue: "#152"
status: implemented
updated: 2026-08-30
---

# Plan: bind the correlated reload generation to a canonical model snapshot and verify DB/model convergence

> Issue: #152
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk: `risk: layout-data` — changes the apply/recovery verification path that
> gates `Applied`/`Recovered`/`Restored`

## Current evidence

Confirmed on `main` @ `de2d33f551` (2026-08-30). The verification chain today
is DB-only:

- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt`
  - `continueCommitted` (L299–350): after
    `writer.requestCorrelatedReload(lease)` returns `Completed` (L321), it
    calls `writer.recaptureDb()` (L333) and compares only
    `db.layoutState == writeSet.intendedState && db.manifest ==
    writeSet.intendedManifest` (L335–336) before
    `store.advance(VERIFIED)` and `ApplyResult.Applied` (L349). The reload
    result contributes nothing but its outcome enum.
  - `automaticRecovery` (L352–471): after the recovery reload completes
    (L437), verifies only `writer.recaptureDb().manifest ==
    stored.preManifest` (L447) before `ApplyResult.Recovered` (L470).
  - `authoritativeState(kind, modelVerified)` (L499–510): every call site
    passes `modelVerified = false`; `AuthoritativeState.*_DB_AND_MODEL`
    (`public/Results.kt` L131–136) is never returned except at L465, which is
    unrelated to verification success.
- `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryProtocol.kt`
  - `recoverWithOuterLease`: reload (L190), then
    `writer.recaptureDb().manifest == stored.preManifest` (L197) before
    `RecoveryResult.Restored` (L212).
- `lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt`
  - `finishCommittedApply` (L375–394) and `finishRestored` (L397–429): same
    manifest-only recapture comparison before resuming `Applied`/`Restored`.
- `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt`
  - `LayoutWriterPort.recaptureDb()` (L58) is documented as "independently of
    the model snapshot" — the model snapshot it defers to does not exist.
  - `LayoutWriterPort.requestCorrelatedReload` (L67) returns `ReloadResult`
    (L138–143), a bare outcome enum with no payload.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  - `recaptureDb()`/`captureCurrent()` (L85–86) are the same direct-SQL
    capture; `RowManifestCodec.capture` (`adapter/RowManifestCodec.kt` L59–67)
    reads `favorites` via SQLite only. Nothing reads `BgDataModel`.
- `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java`
  - `requestAndWait` (L51–94): allocates `requestId` (L52) and blocks on the
    completion signal; the completion `Runnable` registered at L68–72 carries
    no state. `requestId` is passed to the model but never used again.
- `src/com/android/launcher3/LauncherModel.java`
  - `forceReloadForOrganizer` (L467–489): stores an `OrganizerReloadRequest`
    token; the exact `LoaderTask` receives only `organizerLeaseToken` (L427).
  - `completeOrganizerReload` (L493–503): identity-checks the token, runs
    `token.completed` — a bare `Runnable`. `mLastLoadId`/`getLastLoadId()`
    (L160, L640, L803) and `BgDataModel.lastLoadId` (L142) are loader
    generation counters, and spec 13/`RevisionCalculator.kt` (L23) pin them as
    diagnostic-only, never a correlation or revision identity.
- Repo-wide `grep -i modelSnapshot` finds zero hits; the in-memory state that
  would be captured exists (`BgDataModel.workspaceItems`, `itemsIdMap`,
  `folders`, `getAllWorkspaceItems()`), but no organizer code reads it.
- Test doubles: `tests/unit/.../adapter/FakeLayoutWriter.kt`
  (`recaptureDb` L242, `requestCorrelatedReload` L283) and the protocol test
  matrices (`ApplyProtocolTest` SA-01..SA-24, `RecoveryProtocolTest`,
  `RestartReconcilerTest`) all express the DB-only contract today, so the new
  verification will fail them until both production and fakes model the
  snapshot leg. Instrumentation seams for the real adapter already exist
  (`OrganizerReloadSupersessionTest`, `OrganizerReloadCompletionOrderingTest`,
  `RealAdapterRowMatrixInstrumentationTest`).

Root cause class (not a timing bug): the reload seam's terminal signal
transports no state, so "the model produced by this generation" was never
observable to the protocol. #150 made the completion boundary causal; #152
makes the completed generation's state observable and compared.

## Design

### Modules and interfaces

- **`LayoutWriterPort` seam (extended, not replaced).** `ReloadResult`
  becomes payload-carrying on success:
  `Completed(modelSnapshot: ModelSnapshot)`; the failure variants stay
  payload-free. The protocol cannot observe the model by any other path, so
  no second seam is added.
  - `ModelSnapshot` (new data class in the protocol package): the
    **model-verifiable projection** of the model-side workspace state for all
    organizer-owned rows, plus the diagnostic loader generation id observed
    at completion (never used for equality or correlation decisions —
    correlation is the causal token path).
- **`OrganizerModelReloadAdapter` (Java, same package as `LauncherModel`).**
  The completion signal it waits on gains the snapshot payload captured by
  `LauncherModel` at the #150 terminal boundary (see the `LauncherModel`
  bullet): `Completed` carries `Outcome.COMPLETED` + snapshot to the waiting
  adapter thread; superseded / cancelled / failed / timed-out outcomes carry
  no snapshot. **The staleness contract lives here and in
  `completeOrganizerReload`'s token identity check**: only the loader
  generation bound to this request's token can complete with a snapshot, and
  the diagnostic generation id carried in the snapshot is recorded but never
  used for equality or correlation decisions.
- **`LauncherModel.java` — the concrete capture/gating point (in the
  documented minimal bridge surface).** `mBgDataModel` is private
  (`LauncherModel.java` L155) and no existing safe seam reaches the
  terminalized token's completion boundary (`loadAsync` at L535 hands out
  `mBgDataModel`, but on an unrelated async load path, not the #150 terminal
  boundary — rejected below). The completion runnable that `startLoader`
  wires into `BaseLauncherBinder` (L412–415) is defined in `LauncherModel`
  itself, so it can read the private field directly: the lambda captures the
  snapshot from `mBgDataModel` when it runs (i.e. inside
  `BaseLauncherBinder.notifyOrganizerReloadComplete`, on MODEL_EXECUTOR,
  after the #150 drain), and passes it to
  `completeOrganizerReload(token, snapshot)`. The existing token identity
  check under `mLock` gates delivery: a snapshot captured for a token that
  was superseded before the identity check is discarded together with that
  token, and `token.completed` receives the snapshot only when the token is
  still current. No stale-generation race exists because any newer loader
  generation must first replace or cancel the outstanding token
  (`stopLoader`/`forceReloadForOrganizer`) before its transaction can
  commit. The bridge surface is therefore exactly
  `OrganizerModelReloadAdapter.java` + `LauncherModel.java`;
  `LoaderTask` and `BaseLauncherBinder` are unchanged (the completion
  reaching the binder remains a plain `Runnable`).
- **`ModelProjectionCodec` (new, organizer `application/adapter`).** Model-side
  counterpart of `RowManifestCodec`: converts `BgDataModel`-derived items
  into the **model-verifiable projection** — item identity, container,
  placement, item type, folder membership, widget identity (provider +
  widget id) and bind state, profile identity, lock placement occupancy, and
  the pinned per-kind **semantic launch identity** (application component +
  profile; shortcut package + shortcut id + profile; the faithful launch
  identity the model exposes for legacy shortcut kinds) — using the shared
  canonical ordering (`CanonicalItemOrder`) and profile mapping
  (`CanonicalProfileId`). The DB recapture's existing `LayoutState` is
  reduced to the same projection at comparison time, so both legs compare
  like with like. Fields the model does not faithfully represent (raw icon
  bytes, persisted `modified` timestamps, device capabilities/IDP state,
  profile inventory, reserved workspace regions) are excluded from the
  projection and remain covered solely by the unchanged full DB equality.
  Step 2 of the execution checklist pins the remaining representation
  details (canonical encoding per pinned field, DB-side projection helper).
- **Protocols unchanged in shape.** `continueCommitted`,
  `automaticRecovery`, `RecoveryProtocol.recoverWithOuterLease`,
  `RestartReconciler.finishCommittedApply/finishRestored` gain one comparison
  on the snapshot leg:
  `snapshot.projection == db.layoutState.projectedToModelVerifiable()` —
  the DB recapture reduced to the same projection — while the existing DB
  equalities (`db` vs intended on apply; `db.manifest` vs `stored.preManifest`
  on recovery) stay untouched. Mismatch routes to the existing
  `VERIFICATION_FAILED` paths. `modelVerified = true` is passed to
  `authoritativeState(...)` only after the model leg passes. The protocol
  performs no generation reasoning of its own: staleness is excluded by the
  adapter contract, and the protocol only ever sees either a valid
  `Completed(snapshot)` or a non-`Completed` outcome.
- **Fakes.** `FakeLayoutWriter` learns to produce a configurable model
  projection (equal by default; divergent contents for the protocol-level
  divergence tests) and to report non-`Completed` outcomes (failed /
  superseded / cancelled reload) separately from a divergent snapshot, so
  unit tests express the projection contract without Android; stale-generation
  exclusion itself is proven at the adapter/instrumentation level per
  AC-152-02.
- Launcher3/AOSP surface: exactly two same-package bridge files change —
  `OrganizerModelReloadAdapter` (payload transport) and `LauncherModel.java`
  (capture from private `mBgDataModel` in the completion path plus the
  identity-gated `completeOrganizerReload` handoff). The bridge is
  documented here as the #152 reason, satisfying the minimal-bridge rule;
  `LoaderTask` and `BaseLauncherBinder` are unchanged.

### Data flow

1. A5/A6 commit unchanged → `COMMITTED_UNVERIFIED`.
2. `requestCorrelatedReload(lease)` — adapter blocks; the model binds the
   token to the exact `LoaderTask`; when the completion runnable runs at the
   #150 terminal boundary (inside `notifyOrganizerReloadComplete` on
   MODEL_EXECUTOR, after the loader transaction committed/closed and
   token-scoped queued work drained), `LauncherModel` captures
   `ModelSnapshot` from its private `mBgDataModel`; the token identity check
   in `completeOrganizerReload` gates delivery to the waiting adapter.
3. Protocol receives `Completed(snapshot)`; runs the unchanged DB recapture.
4. Snapshot-leg equality: `snapshot.projection ==
   db.layoutState.projectedToModelVerifiable()`, on top of the unchanged DB
   equalities (`== intendedState`/`intendedManifest` on apply;
   `db.manifest == stored.preManifest` on recovery).
5. All equal → `VERIFIED`/`RESTORED` advance and success result with
   `*_DB_AND_MODEL` authoritative classification where applicable.
   Any unequal leg or non-`Completed` outcome → existing
   `VERIFICATION_FAILED` / `MODEL_RELOAD_FAILED` automatic-recovery /
   `RecoveryFailed` paths with `*_MODEL_UNVERIFIED` classification.
6. The snapshot object is dropped when the protocol call returns; nothing is
   persisted.

### Alternatives rejected

- **Correlate via `getLastLoadId()`/`lastLoadId` equality** — spec 13 pins
  load id as diagnostic-only; a numeric equality check is a timing correlation
  in disguise. Rejected.
- **Full `LayoutState` equality between model snapshot and DB recapture** —
  current `LayoutState` carries fields the in-memory model does not
  faithfully represent (persisted `modified`, raw icon bytes, device
  capabilities, profile inventory, reserved regions); assuming recoverability
  would either weaken the DB contract or require a field-by-field proof
  before implementation. Rejected in favor of the model-verifiable
  projection, with the DB leg keeping full canonical equality.
- **Reusing the existing `LauncherModel.loadAsync(Consumer<BgDataModel>)`**
  as the capture seam — it hands out `mBgDataModel`, but on an unrelated
  async load path with no binding to the terminalized request token, so the
  snapshot would not be causally tied to the awaited generation. Rejected in
  favor of capturing inside the completion path defined in `LauncherModel`
  itself.
- **Capture the snapshot at request time** — pre-commit state; not the
  generation's output. Rejected.
- **Recapture-on-mismatch retry / delay before recapture** — banned by the
  issue constraints and spec 13's causal-barrier rule. Rejected.
- **Separate `captureModelSnapshot()` port called after `Completed`** — the
  protocol could capture from an already-superseded generation; binding the
  payload to the completion signal keeps the causality inside the seam.
  Rejected in favor of the payload-carrying result.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `protocol/Ports.kt` | `ReloadResult.Completed` carries `ModelSnapshot` (model-verifiable projection); add `ModelSnapshot` type | Only seam the protocol may observe the model through |
| `com.android.launcher3` bridge (`OrganizerModelReloadAdapter.java`, `LauncherModel.java`) | Completion lambda in `startLoader` captures the snapshot from private `mBgDataModel` at the #150 terminal boundary; `completeOrganizerReload(token, snapshot)` gates delivery on the token identity check; `OrganizerModelReloadAdapter` transports the payload | Causal binding and staleness exclusion live where the token and the private model state live; `LoaderTask`/`BaseLauncherBinder` stay unchanged |
| `adapter/ModelProjectionCodec.kt` (new) | Model → model-verifiable projection conversion reusing `CanonicalItemOrder`/`CanonicalProfileId`, including pinned semantic launch identity; DB-side projection helper over `LayoutState` | Representation equality on the subset the model faithfully represents |
| `adapter/OrganizerModelReloadAdapter.java` / `LauncherLayoutAdapter.kt` | Transport snapshot through `requestAndWait` → `requestCorrelatedReload` | Production `LayoutWriterPort` implementation |
| `protocol/ApplyProtocol.kt`, `RecoveryProtocol.kt`, `RestartReconciler.kt` | Snapshot-projection comparison before success results; `modelVerified` wiring | Acceptance AC-152-03/04 |
| `tests/unit FakeLayoutWriter` + protocol tests | Snapshot-aware fake; divergent-snapshot contents and reload-outcome (failed/superseded/cancelled) cases | Unit oracle per spec |
| `tests/organizer-instrumentation` | Real-model verification cases: default workspace, folders/widgets/profiles/locks, semantic launch identity, reload cancellation/supersession, adapter-level stale-generation exclusion | AC-152-05 real-device leg |

## Migration and recovery

- No schema, format, permission, transport, or backup change; nothing to
  migrate. Release rollback (older build) is unaffected because no persisted
  representation changes.
- Behavior migration is intentional and fail-closed: workspaces that previously
  passed verification with a divergent model will now fail `VERIFICATION_FAILED`
  and enter automatic recovery — the direction spec 13 requires. No recovery
  path weakens: recovery still verifies against the checkpoint pre-manifest,
  now plus the model leg.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-152-01 | New unit regression (divergent model, matching DB) demonstrated failing on pre-fix commit, passing after | `./gradlew :tests:unit` variant used by the module; PR records both runs |
| AC-152-02 | Adapter/instrumentation: only the exact token-bound generation completes with a snapshot (supersession/cancellation/stale-completion cases, `OrganizerReloadSupersessionTest`/`OrganizerReloadCompletionOrderingTest` style); protocol unit tests: valid `Completed(snapshot)` with divergent contents returns no success | Unit suite + `connectedLawnWithQuickstepGithubDebugAndroidTest` subset |
| AC-152-03 | Unit: projection-equality + full-DB-leg cases across the four protocol paths | Unit suite |
| AC-152-04 | `FaultInjector` reload/verification failure matrix asserting no false success; `*_MODEL_UNVERIFIED` assertions | Unit suite |
| AC-152-05 | Unit fixtures (default workspace, folders/widgets/profiles/locks, semantic launch identity, reload cancellation/supersession outcomes) + real-model instrumentation including adapter-level stale-generation exclusion | Emulator instrumentation run |
| AC-152-06 | `high-risk-gate` CI `final-status` on head SHA + independent `docs/assessment/pr-<PR>-152-*.md` audit by a separate session | CI on the implementation PR |

Regression gates per `AGENTS.md`: `./gradlew spotlessCheck` and
`./gradlew assembleLawnWithQuickstepGithubDebug` on the implementation PR.

## Documentation updates

- [ ] `specs/152-reload-model-snapshot-verification/spec.md` → `accepted` on
      approval, `implemented` at merge of the implementation PR.
- [ ] `CONTEXT.md`: add モデルスナップショット / 相関リロード生成 terms on
      acceptance.
- [ ] `DESIGN.md` §4.2/§10: one-line statement that post-apply/recovery
      verification proves DB/model convergence (no progress notes).
- [ ] `specs/150-*/spec.md` cross-references: leave as-is (they already
      assign the gap to #152); no rewrite needed.
- [ ] ADR: not required unless the model-verifiable projection field
      enumeration (execution checklist step 2) forces a design decision
      beyond the projection defined in the spec.

## Execution checklist

1. Write the AC-152-01 regression test against current `main`; confirm it
   fails for the right reason (no snapshot leg exists).
2. Pin the remaining projection representation details
   (`ModelProjectionCodec` feasibility step): the required fields — identity,
   container, placement, type, folder membership, widget identity/bind
   state, profile identity, lock occupancy, and the pinned per-kind semantic
   launch identity — are already fixed by the spec; this step pins each
   field's in-memory model source and canonical encoding and reduces the DB
   `LayoutState` to the same projection. **Stop condition**: if any pinned
   field is not faithfully recoverable from the model, stop and reopen the
   design (spec Open questions) instead of silently shrinking the
   projection.
3. Extend `ReloadResult`/bridge/adapter/protocols per the change set; fakes
   updated in the same commit as the port change.
4. Implement failure/cancellation outcome handling and the adapter-level
   stale-generation exclusion; keep all existing failure contracts intact.
5. Full verification table; record commands and results in the PR.
6. PR: `Closes #152`, per-AC evidence, spec status/history updates, risk label
   `risk: layout-data`, independent audit record before merge.

## Stop conditions

- The pinned projection fields (including semantic launch identity) cannot
  be recovered faithfully from the model (execution checklist step 2) →
  stop; open the design question on the issue; do not compare a silently
  shrunken projection.
- Any change to recovery store schema, Launcher DB schema, or backup format
  becomes necessary → out of scope; separate accepted design required.
- The fix requires changing the #150 causal completion barrier semantics →
  stop; resolve the seam ownership between #150 and #152 first.

## High-risk merge gate

The implementation PR carries `risk: layout-data` and merges only when:

- CI's merge gate (`final-status`) has actually succeeded on the exact head
  SHA, and
- `docs/assessment/pr-<PR>-152-reload-model-snapshot-verification.md` records
  the independent audit (head SHA, spec acceptance criteria, test surfaces,
  CI run links), written by a session other than the implementing one.
