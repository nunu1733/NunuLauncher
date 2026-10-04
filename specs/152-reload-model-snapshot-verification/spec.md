---
issue: "#152"
status: implemented
requirements: []
risk:
  - layout-data
updated: 2026-08-30
---

# Post-apply and post-recovery verification proves DB/model convergence

## Problem

Accepted spec 13 ("Post-apply and post-recovery verification") requires the
apply protocol to persist `COMMITTED_UNVERIFIED`, request a new model load,
wait for the generation caused by that request, and then **compare that model
snapshot with an independent DB recapture** before returning success. The
same requirement applies to automatic recovery, explicit recovery, and
restart reconciliation before `Recovered`/`Restored` is returned.

Production performs none of that model-side comparison:

- `ApplyProtocol.continueCommitted` requests the correlated reload, then
  compares only the DB recapture (`recaptureDb()`) with the intended
  state/manifest and returns `Applied`. No code reads the in-memory
  Launcher model state.
- `ApplyProtocol.automaticRecovery`, `RecoveryProtocol.recoverWithOuterLease`,
  and `RestartReconciler.finishCommittedApply`/`finishRestored` compare only
  the DB recapture manifest against the stored record and return
  `Recovered`/`Restored`/`Applied`.
- No production code captures a canonical model snapshot anywhere: the
  correlated reload completion signal carries only an outcome enum, and the
  `AuthoritativeState.*_DB_AND_MODEL` variants are never returned
  (`modelVerified` is always `false`).

Consequently, a loader generation that silently dropped, reordered, or
transformed rows relative to the committed `favorites` content would still
pass verification and return a false `Applied`/`Recovered`/`Restored`. DB and
model convergence is asserted by spec 13 but demonstrated by no test. Issue
#150 fixed and evidenced only the causal reload completion boundary and
explicitly assigned this remaining gap to Issue #152.

## Outcome

Before any apply/recovery success result is returned, the protocol verifies
that the model snapshot produced by the exact correlated reload generation
equals the independent DB recapture on the model-verifiable projection,
while the DB leg keeps its existing full canonical equality with the
intended (or checkpoint pre) state. A divergence on the model leg fails
verification with the same fail-closed automatic-recovery / `RestoreFailed`
behavior as a DB divergence, so DB/model convergence is actually
demonstrated whenever `Applied`, `Recovered`, or `Restored` is returned.

## Scope

- The apply protocol (A7/A8) captures a model snapshot from the exact reload
  generation requested after commit and requires model-projection equality
  with the independent DB recapture, on top of the unchanged full DB
  equality with the intended state, before `Applied`.
- The automatic recovery path requires the same model-projection equality
  (snapshot of the recovery reload generation vs DB recapture) on top of the
  unchanged `db.manifest == checkpoint pre-manifest` comparison before
  `Recovered`.
- The explicit recovery path requires the same model-projection equality
  before `Restored`.
- Restart reconciliation completion paths
  (`finishCommittedApply`/`finishRestored`) require the same
  model-projection equality before resuming `Applied`/`Restored`.
- The model snapshot is captured only at the terminal boundary of the
  correlated reload — after the loader transaction of the requested
  generation has committed and closed and after token-scoped model work
  queued ahead of the completion has drained (the causal completion boundary
  established by Issue #150). The reload adapter owns this binding: a stale,
  unrelated, cancelled, or superseded generation can never be delivered to
  the protocol as a completed snapshot for the request.
- Model verification compares a defined **model-verifiable projection** of
  the layout state. The projection's required fields are pinned in this
  spec: item identity, container, placement, item type, folder membership,
  widget identity (provider + widget id) and bind state, profile identity,
  lock placement occupancy, and the **semantic launch identity** per item
  kind — application component + profile for app icons, shortcut package +
  shortcut id + profile for deep shortcuts, and the faithful launch identity
  the model exposes for legacy shortcut kinds. Fields the model does not
  represent (raw icon bytes, persisted modification timestamps, device
  capabilities, profile inventory, reserved regions) are verified solely by
  the unchanged DB leg, which keeps full canonical equality with the
  intended/checkpoint-pre state.
- When model verification succeeds, the authoritative-state classification
  may report the `*_DB_AND_MODEL` variants instead of the `*_MODEL_UNVERIFIED`
  variants; when model verification fails, the `*_MODEL_UNVERIFIED` variants
  are reported, preserving today's failure contracts.
- Verification failure on the model leg enters the existing failure paths
  unchanged: `VERIFICATION_FAILED` with automatic recovery on apply,
  `RecoveryFailed` with `VERIFICATION_FAILED` on recovery.

## Non-goals

- No weakening of the existing manifest/intended-state comparison; the DB
  leg is unchanged.
- No fixed delays, retry-until-match, recapture-on-mismatch loops, or
  process restart as a verification or convergence mechanism.
- No Launcher DB, recovery store schema, permission, transport, or
  backup-format change. The model snapshot is transient in-memory state and
  is never persisted, exported, or logged with user content.
- No new public API, UI, or settings surface; the verification seam is
  internal to the Layout Application module.
- No change to the planner, classifier, or `app.lawnchair.deck`.
- No change to the #150 causal completion barrier; this spec consumes the
  completed generation it produces.
- No claim that Issue #150 proved convergence; #150's scope is untouched.

## Domain language

- **モデルスナップショット (Model Snapshot)**: 相関リロード生成の完了時に
  読み取った、メモリ上のホームレイアウト状態のcanonical表現。検証では
  model-verifiable projection(モデルが忠実に表現できるフィールドの部分集合)
  として比較され、DB再取得の同じprojectionと突き合わされる。検証専用の
  一時データであり、永続化しない。
  _Avoid_: Backup（永続データと混同）、Layout Snapshot（入力captureと混同）
- **モデル検証可能projection (Model-verifiable Projection)**: メモリ上の
  modelが忠実に表現するレイアウト状態のフィールド部分集合(配置アイテムの
  identity、container、配置、種別、folder構成、widget identityとbind状態、
  profile identity、lock配置の占有、および種別ごとの意味的起動対象
  identity)。modelが表現しないフィールドはDB側の検証だけで担保する。
  _Avoid_: Model LayoutState（完全なcanonical stateと混同）
- **相関リロード生成 (Correlated Reload Generation)**: 適用後の検証のために
  要求された1回のmodel reloadが生んだloader生成。要求と完了が同一tokenで
  結ばれ、#150の因果的完了境界(loader transactionのcommit/close後、
  token-scopedな残務の排 drained後)でのみ完了する生成を指す。
  _Avoid_: Load ID（診断値であり相関の正本ではない）

On acceptance these terms are reflected into `CONTEXT.md`.

## Behavior scenarios

### Scenario: Apply succeeds only with model/DB agreement

Given an apply transaction committed and the correlated reload requested at
A7 completed,
when verification runs,
then the model snapshot captured at that reload's terminal boundary equals
the independent DB recapture on the model-verifiable projection,
the DB recapture equals the intended state/manifest (unchanged DB leg),
the record advances to `VERIFIED`,
and the result is `Applied`.

### Scenario: Model diverges from committed DB

Given the independent DB recapture matches the intended state/manifest,
and the model snapshot of the correlated reload generation differs from it
(for example, a row the loader dropped, reordered, or transformed),
when verification runs,
then the result is not `Applied`: the record does not advance to `VERIFIED`
and automatic recovery runs with `VERIFICATION_FAILED`,
and the authoritative state reported by any resulting failure is a
`*_MODEL_UNVERIFIED` variant.

### Scenario: A snapshot from another generation never reaches verification

Given a loader generation other than the one requested after commit — stale,
unrelated, cancelled, or superseded — finished or was stopped,
when the reload adapter handles the request,
then that generation can never be delivered to the protocol as
`Completed` carrying a snapshot:
the protocol observes only a non-`Completed` outcome and enters the
`MODEL_RELOAD_FAILED` automatic-recovery path,
so no success result can be produced from a foreign generation's state.

### Scenario: Cancelled, superseded, or failed reload never verifies

Given the correlated reload reported cancelled, superseded, failed, or timed
out,
when the protocol handles the reload outcome,
then no model snapshot comparison runs,
automatic recovery is entered with `MODEL_RELOAD_FAILED`,
and no `Applied`, `Recovered`, or `Restored` is returned.

### Scenario: Explicit recovery succeeds only with model/DB agreement

Given a recovery write-set committed and the correlated reload completed,
when verification runs,
then the model snapshot of that reload generation equals the independent DB
recapture on the model-verifiable projection and the DB recapture matches
the checkpoint pre-manifest (unchanged DB leg),
the record advances to `RESTORED`,
and the result is `Restored`.

### Scenario: Recovery model divergence is not a false success

Given the DB recapture matches the checkpoint pre-manifest but the model
snapshot of the recovery reload generation differs,
when verification runs,
then the result is `RecoveryFailed` with `VERIFICATION_FAILED` and a
`*_MODEL_UNVERIFIED` authoritative state — never `Restored` or `Recovered`.

### Scenario: Restart reconciliation completes with model verification

Given a process restart while a record is `COMMITTED_UNVERIFIED` or
`RESTORING`,
when reconciliation finishes the apply or restore,
then the same model-snapshot/DB-recapture equality is required before the
resumed `Applied`/`Restored` result is returned,
and divergence resumes the failure path instead of success.

Operation-level cancellation of an organization run remains out of scope per
accepted spec 13; only the correlated reload's own cancelled/superseded
outcome is covered above.

## Data and state

- Read: at the correlated reload's terminal boundary, the in-memory Launcher
  model workspace state for all organizer-owned `favorites` rows (desktop
  pages, hotseat, folders and their contents, widgets, locked placement,
  profile identity), reduced to the model-verifiable projection defined in
  Scope. The DB leg keeps its existing full canonical comparison; the
  projection exists only so both legs compare like with like.
- Written: nothing new. The model snapshot is transient, process-local, and
  never persisted, exported, or logged with user-identifying content.
- Migration/backup/restore impact: none. No Launcher DB, recovery store
  schema, or backup allowlist change.
- Failure behavior: model-leg divergence reuses the existing fail-closed
  paths (automatic recovery / `RecoveryFailed`); no new result type is
  introduced.

## Permissions, privacy, and security

None. No new permission, transport, or export; the snapshot stays inside the
app process and only its equality outcome is recorded in diagnostics.

## Accessibility and localization

None. No UI surface is added or changed.

## Acceptance criteria

- [ ] AC-152-01: On current `main`, a test at the existing
      `LayoutWriterPort`/reload seams demonstrates the gap: a divergent model
      generation with a matching DB recapture returns `Applied`/`Restored`
      before the fix and fails the new verification after it.
- [ ] AC-152-02: The reload adapter contract guarantees that only the exact
      token-bound reload generation can complete with a snapshot: stale,
      unrelated, cancelled, and superseded generations are rejected inside
      the adapter and never delivered to the protocol as `Completed`
      carrying a snapshot. This is proven at the adapter/instrumentation
      level; separately, protocol tests cover a valid `Completed` snapshot
      whose contents diverge from the DB recapture/intended state and assert
      that no success result is returned.
- [ ] AC-152-03: Apply, automatic recovery, explicit recovery, and restart
      reconciliation each verify equality of the model snapshot
      (model-verifiable projection), the corresponding projection of the
      independent DB recapture, and — unchanged — the existing full
      canonical DB equality with the intended (or checkpoint pre) state
      before returning `Applied`/`Recovered`/`Restored`.
- [ ] AC-152-04: A model/DB divergence, reload failure, or cancellation or
      supersession of the reload can never return `Applied`, `Recovered`, or
      `Restored`; failure results and authoritative states follow the
      existing spec 13 contracts (`*_MODEL_UNVERIFIED` while the model is
      unverified, `*_DB_AND_MODEL` permitted once model verification
      succeeds).
- [ ] AC-152-05: Tests cover the default workspace; folders, widgets, work
      profiles, locked placement, and semantic launch identity; reload
      cancellation and supersession outcomes at the unit (fake seam) level;
      and stale-generation exclusion at the adapter/instrumentation (real
      model) level.
- [ ] AC-152-06: The `risk: layout-data` CI merge gate (`final-status` on the
      exact head SHA) and an independent audit record in
      `docs/assessment/pr-<PR>-<slug>.md` are satisfied before merge.

## Test oracle

| Acceptance criterion | Test surface |
|---|---|
| AC-152-01 | Unit: `ApplyProtocolTest`/`RecoveryProtocolTest` regression case that fails on pre-fix behavior; recorded in the PR |
| AC-152-02 | Adapter/instrumentation: only the exact token-bound generation completes with a snapshot (`OrganizerReloadSupersessionTest`-style); protocol: divergent-snapshot unit cases via `FakeLayoutWriter` |
| AC-152-03 | Unit: three-way (projection + full DB leg) equality cases for apply, automatic recovery, explicit recovery, restart reconciliation |
| AC-152-04 | Unit: divergence/failure-injection matrix (`FaultInjector` reload and verification phases) asserting no false success |
| AC-152-05 | Unit fixtures (default workspace, folders/widgets/profiles/locks, semantic launch identity, reload cancellation/supersession outcomes) + `RealAdapterRowMatrixInstrumentationTest`-style real-model coverage including adapter-level stale-generation exclusion |
| AC-152-06 | CI `high-risk-gate` workflow on the implementation PR + independent audit record |

## Open questions

The fields required to prevent false success are pinned in Scope (including
semantic launch identity per item kind). Implementation (plan step 2) pins
only the remaining representation details of the projection — canonical
encoding of each pinned field and the DB-side projection helper — under the
same stop condition: if a pinned field turns out not to be recoverable
faithfully from the model, implementation stops and the design reopens
instead of silently shrinking the projection.

## Change history

- 2026-08-30: Draft created from Issue #152; gap evidence collected against
  `main` @ `de2d33f551`.
- 2026-08-30: Stage A review revision — capture point restated as the #150
  terminal boundary (not bind-complete); model verification redefined as
  model-verifiable projection equality with the DB leg keeping full canonical
  equality; run-level cancellation removed (out of scope per accepted spec
  13; reload-level cancelled/superseded outcome only); stale-generation
  rejection moved into the adapter contract with AC-152-02 split between
  adapter/instrumentation proof and protocol divergence tests.
- 2026-08-30: Stage A re-review revision — semantic launch identity (per-kind
  launch target: application component, shortcut package + id, widget
  provider/id, legacy-shortcut launch identity, each with profile) pinned in
  the projection as a required field; the minimal bridge surface is updated
  to name `LauncherModel.java`, where the completion captures the snapshot
  from the private `mBgDataModel` and the token identity check gates its
  delivery; the stale-generation scenario rewritten so a foreign generation
  never reaches verification at all (adapter-owned exclusion).

- 2026-08-30: Implementation review revision (PR #180) — legacy shortcut
  launch identity made concrete: the projection carries the canonical
  re-serialized launch intent for `ShortcutLegacy`/`Unknown` rows, derived
  from the persisted DB intent and the in-memory `WorkspaceItemInfo` intent
  through the same canonicalization (writer-port `legacyLaunchIdentityOf` /
  model-side codec). Unsupported-container rows are represented explicitly in
  both projection legs (raw container code is model-observable) instead of
  being dropped, so a row the model loses cannot compare equal by symmetric
  omission; a row class the model does not load at all fails closed. Status
  moved to `implemented` at the merge of PR #180.
