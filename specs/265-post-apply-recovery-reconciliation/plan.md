---
issue: "#265"
status: draft
updated: 2026-09-11
---

# Investigation plan: remaining `RECONCILIATION_FAILED` route and `WriterBusy` disposition

Companion to [spec.md](./spec.md). This is an **investigation** plan, not a fix
plan: the confirmed NULL-span boundary fix is merged (#269 / PR #274) and the
preview typed-failure fix is merged (#270 / PR #273). The plan below targets
the two remaining open items of #265: the unexplained original-journal
`INPUT_READINESS / RECONCILIATION_FAILED` route, and the one-off `WriterBusy`
observation.

Baseline: `origin/main` @ `ed7ce5a205985cd7a59224f60789aae4ba3c5ceb`
(merge of PR #279; re-based 2026-09-11 per the snapshot re-entry rule,
superseding `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`). The only relevant
change since the prior baseline is the #271 implementation (PR #276):
`ReadinessGate` gained an observable `stateFlow` mirror (transition
semantics unchanged), startup reconciliation gained a settings-first
trigger (`LawnchairApp.ensureOrganizerStartupReconciliation()` also called
from `ManualOrganizationModule.get()`), and Settings now reads the durable
`OrganizerDurableStatus` projection. All paths below were re-verified at
this revision; line numbers are indicative and must be re-confirmed at
execution time.

## Current implementation / paths to trace

### Gate-`FAILED` producing surfaces

| Concern | Path (verified @ `ed7ce5a205`) |
|---|---|
| Readiness gate state machine | `lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt` — `reconcile()` (`:45`) sets `FAILED` when `block` throws or `succeeded()` is false; `failBeforeReconciliation()` (`:66`) sets `FAILED` without running the block. One-shot per `LayoutApplicationModule` instance / store generation. Post-#271 the gate additionally exposes a `stateFlow` mirror (`:43`, transition semantics unchanged); the harness can record transitions via `ManualOrganizationRun.readinessState`. |
| Startup invocation | `LawnchairApp.ensureOrganizerStartupReconciliation()` (`LawnchairApp.kt:128`) — process-scoped, idempotent; called from Launcher `onActivityResumed` and from `ManualOrganizationModule.get()` (`ManualOrganizationRun.kt:124-137`), so a settings-only fresh process drives the model load itself (`LauncherModel.startLoaderWithoutCallbacks()`, the #271 bridge). Model-load timeout ⇒ `failStartupReconciliation()` (`LawnchairApp.kt:148`) ⇒ gate `FAILED` **before** any reconcile block. Otherwise → `LayoutApplicationModule.reconcileAtStart()` (now `:372`): mutex/lease/session acquisition failures return `Failed` before `readinessGate.reconcile` (`:386`), so they do not by themselves set the gate `FAILED` (verified in source; route 4 below confirms which journal surface, if any, they produce). |
| Compose gating | `LayoutApplicationModule.composeManualFullOrganizationInput` (now `:149`): gate `FAILED` ⇒ `NotReady(ReconciliationFailed)`, journal `INPUT_NOT_READY` / `INPUT_READINESS` / `RECONCILIATION_FAILED`. |
| Plan preview gating | `inspectPlan` (`:194`): `PlanPreviewUnavailable.RECONCILIATION_FAILED`. |

### Candidate routes to enumerate (AC-265-R1)

1. **Unresolved-record automatic-recovery fallback.**
   `RestartReconciler.reconcileAll` (`RestartReconciler.kt:70`; unchanged
   since the prior baseline): records in `APPLYING` / `COMMITTED_UNVERIFIED`
   / `RESTORING` whose authoritative class is neither pre-state nor
   intended-post-state go to
   `recover(session, record, lease, COMMIT_OUTCOME_UNKNOWN)` (fallback sites
   `:343`/`:361`/`:366`; `recover()` def `:456`). If
   `prepareRecoveryWriteSet` returns not-`Ready` (e.g. row-manifest
   precondition mismatch after a manual edit), the record stays `unresolved`
   ⇒ summary `hasUnresolvedFailures()` ⇒ gate `FAILED`. Key question: can the
   reported sequence (apply verified, then manual edit, then restore request)
   strand a record in a lifecycle the reconciler cannot resolve, given the
   restore in the report never entered `RESTORING` on `main`?
2. **Exception inside `reconcileAll`.** Any `RuntimeException` from
   `writer.recaptureDb()` / capture during reconciliation sets `FAILED`
   directly. Post-#269, the ordinary folder → workspace move path
   normalizes desktop spans to `1×1` (`ModelWriter.java:202`), so that user
   flow no longer produces a NULL-span desktop row; strict canonical capture
   still rejects malformed/external NULL-span desktop rows
   (`RowManifestCodec.kt:261`) — a synthetic NULL-span fixture is an
   invalid-row case, not the ordinary user flow #269 fixed. Post-#270,
   preview capture failures are typed; verify separately whether
   capture/recapture failures arising during restart reconciliation are
   converted to unresolved outcomes or can still escape to
   `ReadinessGate.reconcile` and set the gate `FAILED` (internal catch blocks
   `:211`/`:235`).
3. **Recovery-store read failures / artifact-pair poison state.**
   `RecoveryStartupArtifacts` / `RecoveryInspectionSnapshotReader`
   (`store/RecoveryStartupArtifacts.kt`, ADR-0011, #187): DB absent +
   snapshot present ⇒ `SuspiciousAbsence` ⇒ reconciliation fails on every
   process start. Not obviously reachable from the reported sequence (no ZIP
   restore in the report) — record as candidate with precondition.
4. **Lease/session acquisition failure or model-load timeout at start.**
   Source-verified at this baseline: acquisition failures inside
   `reconcileAtStart` (`:372-399`) return `Failed` before the gate reconcile,
   so they leave the gate non-`FAILED` — confirm which journal surface, if
   any, they produce and close the candidate. Distinct pre-reconciliation
   surface: the settings-first model-load timeout
   (`failStartupReconciliation`, `LawnchairApp.kt:148`) sets gate `FAILED`
   without a reconcile block; assess whether it is producible inside the
   reported sequence's 28s window.

### Journal-signature comparison (AC-265-R3)

For each route, record the exact journal codes `main` emits (existing closed
vocabulary: `PhaseCode`, `ErrorFamily`, `InputCompositionCode`,
`ReconciliationClassification`;
`lawnchair/src/app/lawnchair/organizer/integration/CompositionModels.kt:45,100`,
`lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt:34`)
and compare with the attached journal of the original
report (28s gap; `APPLY_VERIFIED` → `INPUT_NOT_READY` with
`INPUT_READINESS` / `RECONCILIATION_FAILED`). The prior code-absence analysis
(`git log --all -S` over `BLOCK_FALLBACK_NEEDED`,
`BACKUP_ENTRY_NO_CURRENT_PATH`, `reconciliationPersisted`) already suggests a
non-`main` build; extend it only if a new code is claimed.

### `WriterBusy` disposition (AC-265-R4)

Re-examine `confirmRecovery` → `recoverWithOuterLease` lease acquisition
(`RecoveryProtocol.kt`) and `PreWriteRejection.WRITER_BUSY` handling in
`ManualOrganizationRun`. Decide: does an immediate-after-apply restore request
still observe `WriterBusy` on current `main` (re-run the harness control path
with an immediate confirm)? If yes with a user-visible "Restore did nothing"
surface and no retry guidance, propose a separate UX/retry follow-up issue;
otherwise record will-not-investigate with the evidence.

## Seams to use

- Device/emulator instrumentation harness with production adapters + durable
  `RecoveryStore` + real `LauncherModel` reloads — same contract as the
  accepted #265 two-path reproduction (Path A/B). The harness
  (`tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt`)
  is merged on `main` (via #269 / PR #274); reuse/extend that surface rather
  than inventing a new one.
- Public seams only for mutations: `ManualOrganizationRun` orchestration,
  `LayoutApplicationModule` apply/recover, `ModelWriter.moveItemInDatabase`
  for the manual edit.
- Direct store-file manipulation (to construct stranded-record / poison-state
  preconditions) is allowed for investigation fixtures only, on the emulator,
  with snapshot restore in teardown; it is not a product seam.

## Expected evidence artifacts

- Per-route: source trace (file:line), precondition, reproduction transcript
  or negative-result argument, journal signature, verdict
  (reachable / unreachable-from-reported-sequence / needs-non-main-build).
- WriterBusy: re-run result and disposition decision.
- All evidence recorded in #265 comments; no code lands from this plan.

## Testing strategy

No product tests are produced by the investigation itself. If a route is
proven reachable as a defect, the follow-up issue it spawns must specify the
minimum deterministic failing-path test design (per the issue's original
acceptance criteria), reusing the #269 instrumentation-test pattern.

## Incremental order

1. Route enumeration from source (read-only; routes 1-4 above), with the
   gate-state behavior of `reconcileAtStart` acquisition failures clarified.
2. Journal-signature table; first-pass consistency verdict against the
   attached journal.
3. Deterministic reproduction attempts for the plausible routes (expect route
   1 and possibly 2; routes 3-4 likely negative results).
4. `WriterBusy` re-run and disposition.
5. Final classification comment on #265; follow-up issue creation if a new
   defect is proven; closure recommendation for #265.

Steps 1-2 are pure source analysis; steps 3-4 need the emulator harness;
step 5 is documentation.

## Dependencies / sequencing

- After (already merged) #269 and #270 — the remaining question is only
  meaningful on the fixed `main`, since the pre-fix manual-edit path ended in
  `CAPTURE_UNAVAILABLE`, not gate `FAILED`.
- #271 is merged (PR #276; spec implemented via PR #279): its durable
  `OrganizerDurableStatus` projection is the seam for Settings status
  observations, and its settings-first startup trigger
  (`ensureOrganizerStartupReconciliation` from `ManualOrganizationModule.get()`)
  is active in any reproduction that re-opens Settings in a fresh process.

## Risks

- The original journal may be unreproducible because it came from a non-`main`
  build; the plan treats that as a valid outcome (AC-265-R3), not a failure.
- Emulator-only state injection (stranded records) may not faithfully model a
  real-process crash mid-restore; where used, the argument must state the
  modeling assumption explicitly.
- Line-number drift after further merges; re-verify paths at execution time.

## Explicitly unverified areas

- Whether `reconcileAll`'s internal catch blocks (`RestartReconciler.kt:211,235`)
  cover all capture/exception surfaces or whether a typed capture failure can
  still escape to `ReadinessGate.reconcile` as `FAILED`.
- Whether the reported 28s-gap journal can be produced by any `main` route at
  all (this is the open question by definition).
- Whether `WriterBusy` on immediate confirm still occurs on current `main`.
