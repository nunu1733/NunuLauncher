---
issue: "#152"
status: draft
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
three-way equality: the canonical model snapshot produced by the exact
correlated reload generation, the independent DB recapture, and the intended
(or checkpoint pre) state. A divergence on the model leg fails verification
with the same fail-closed automatic-recovery / `RestoreFailed` behavior as a
DB divergence, so DB/model convergence is actually demonstrated whenever
`Applied`, `Recovered`, or `Restored` is returned.

## Scope

- The apply protocol (A7/A8) captures a canonical model snapshot from the
  exact reload generation requested after commit and requires equality with
  the independent DB recapture and the intended state before `Applied`.
- The automatic recovery path requires the same equality (model snapshot of
  the recovery reload generation, DB recapture, checkpoint pre-manifest)
  before `Recovered`.
- The explicit recovery path requires the same equality before `Restored`.
- Restart reconciliation completion paths
  (`finishCommittedApply`/`finishRestored`) require the same equality before
  resuming `Applied`/`Restored`.
- The model snapshot is bound causally to the correlated reload: it is
  captured only from the model state produced by the loader generation whose
  completion the adapter awaited; a snapshot of any other generation cannot
  satisfy verification.
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
  読み取った、メモリ上のホームレイアウト状態のcanonical表現。DB再取得と
  突き合わせる検証専用の一時データであり、永続化しない。
  _Avoid_: Backup（永続データと混同）、Layout Snapshot（入力captureと混同）
- **相関リロード生成 (Correlated Reload Generation)**: 適用後の検証のために
  要求された1回のmodel reloadが生んだloader生成。要求と完了が同一tokenで
  結ばれている生成を指す。
  _Avoid_: Load ID（診断値であり相関の正本ではない）

On acceptance these terms are reflected into `CONTEXT.md`.

## Behavior scenarios

### Scenario: Apply succeeds only with three-way equality

Given an apply transaction committed and the correlated reload requested at
A7 completed,
when verification runs,
then the canonical model snapshot captured from that exact reload generation,
the independent DB recapture, and the intended state/manifest are equal,
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

### Scenario: Snapshot from another generation cannot satisfy verification

Given a model snapshot captured from a different loader generation than the
one requested after commit (stale, unrelated, or superseded),
when verification runs,
then that snapshot is rejected as if the reload had not completed
(`MODEL_RELOAD_FAILED` / verification failure path),
and no success result is returned.

### Scenario: Superseded or failed reload never verifies

Given the correlated reload reported superseded, failed, or timed out,
when the protocol handles the reload outcome,
then no model snapshot comparison runs,
automatic recovery is entered with `MODEL_RELOAD_FAILED`,
and no `Applied`, `Recovered`, or `Restored` is returned.

### Scenario: Explicit recovery succeeds only with three-way equality

Given a recovery write-set committed and the correlated reload completed,
when verification runs,
then the canonical model snapshot of that reload generation, the independent
DB recapture, and the checkpoint pre-manifest are equal,
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

### Scenario: Cancellation during post-commit verification

Given the organization run is cancelled after commit but before verification
completes,
then no success result is returned,
the record is left in a lifecycle state consistent with the #150/spec 13
failure contracts,
and recovery remains available through the existing paths.

## Data and state

- Read: the in-memory Launcher model workspace state (desktop pages, hotseat,
  folders and their contents, widgets, locked placement, profile identity)
  after the correlated reload generation completes; the canonical
  representation covers all organizer-owned `favorites` rows, matching the
  canonical DB representation produced by the existing manifest codec.
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
- [ ] AC-152-02: The canonical model snapshot used in verification is
      captured only from the model state produced by the exact correlated
      reload generation; a snapshot from a stale, unrelated, or superseded
      generation is rejected and cannot produce a success result.
- [ ] AC-152-03: Apply, automatic recovery, explicit recovery, and restart
      reconciliation each verify equality of the canonical model snapshot,
      the independent DB recapture, and the intended (or checkpoint pre)
      state before returning `Applied`/`Recovered`/`Restored`.
- [ ] AC-152-04: A model/DB divergence, reload failure, or supersession can
      never return `Applied`, `Recovered`, or `Restored`; failure results and
      authoritative states follow the existing spec 13 contracts
      (`*_MODEL_UNVERIFIED` while the model is unverified, `*_DB_AND_MODEL`
      permitted once model verification succeeds).
- [ ] AC-152-05: Tests cover the default workspace; folders, widgets, work
      profiles, and locked placement; cancellation; and stale-generation
      rejection, at both unit (fake seam) and instrumentation (real model)
      levels.
- [ ] AC-152-06: The `risk: layout-data` CI merge gate (`final-status` on the
      exact head SHA) and an independent audit record in
      `docs/assessment/pr-<PR>-<slug>.md` are satisfied before merge.

## Test oracle

| Acceptance criterion | Test surface |
|---|---|
| AC-152-01 | Unit: `ApplyProtocolTest`/`RecoveryProtocolTest` regression case that fails on pre-fix behavior; recorded in the PR |
| AC-152-02 | Unit: stale-generation/supersession cases via `FakeLayoutWriter`; instrumentation: `OrganizerReloadSupersessionTest`-style correlation on the real adapter |
| AC-152-03 | Unit: three-way equality cases for apply, automatic recovery, explicit recovery, restart reconciliation |
| AC-152-04 | Unit: divergence/failure-injection matrix (`FaultInjector` reload and verification phases) asserting no false success |
| AC-152-05 | Unit fixtures + `RealAdapterRowMatrixInstrumentationTest`-style real-model coverage: default workspace, folders/widgets/profiles/locks, cancellation, stale generation |
| AC-152-06 | CI `high-risk-gate` workflow on the implementation PR + independent audit record |

## Open questions

None blocking. One implementation-risk watch item is tracked in the plan:
whether the in-memory model representation can reproduce byte-equivalent
canonical equality for every organizer-owned row class; if not, stop and
reopen the design decision instead of weakening the comparison.

## Change history

- 2026-08-30: Draft created from Issue #152; gap evidence collected against
  `main` @ `de2d33f551`.
