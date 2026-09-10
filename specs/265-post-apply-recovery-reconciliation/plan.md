---
issue: "#265"
status: draft
updated: 2026-09-10
---

# Investigation plan: remaining `RECONCILIATION_FAILED` route and `WriterBusy` disposition

Companion to [spec.md](./spec.md). This is an **investigation** plan, not a fix
plan: the confirmed NULL-span boundary fix is merged (#269 / PR #274) and the
preview typed-failure fix is merged (#270 / PR #273). The plan below targets
the two remaining open items of #265: the unexplained original-journal
`INPUT_READINESS / RECONCILIATION_FAILED` route, and the one-off `WriterBusy`
observation.

Baseline: `origin/main` @ `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`
(merge of PR #274). All paths below were verified present at this revision;
line numbers are indicative and must be re-confirmed at execution time.

## Current implementation / paths to trace

### Gate-`FAILED` producing surfaces

| Concern | Path (baseline) |
|---|---|
| Readiness gate state machine | `lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt` — `reconcile()` sets `FAILED` when `block` throws or `succeeded()` is false. One-shot per `LayoutApplicationModule` instance / store generation. |
| Startup invocation | `LawnchairApp.kt:242-258` → `LayoutApplicationModule.reconcileAtStart()` (`LayoutApplicationModule.kt:325`): lease/session acquisition failure returns `Failed` (⇒ gate `FAILED` via `succeeded`? — verify: acquisition failures return before `readinessGate.reconcile`, so the gate stays `IDLE`/`RECONCILING`; confirm which surface the journal would then show). |
| Compose gating | `LayoutApplicationModule.composeManualFullOrganizationInput` (`:147-159`): gate `FAILED` ⇒ `NotReady(ReconciliationFailed)`, journal `INPUT_NOT_READY` / `INPUT_READINESS` / `RECONCILIATION_FAILED`. |
| Plan preview gating | `inspectPlan` (`:194-198`): `PlanPreviewUnavailable.RECONCILIATION_FAILED`. |

### Candidate routes to enumerate (AC-265-R1)

1. **Unresolved-record automatic-recovery fallback.**
   `RestartReconciler.reconcileAll` (`RestartReconciler.kt:70`): records in
   `APPLYING` / `COMMITTED_UNVERIFIED` / `RESTORING` whose authoritative class
   is neither pre-state nor intended-post-state go to
   `recover(session, record, lease, COMMIT_OUTCOME_UNKNOWN)`. If
   `prepareRecoveryWriteSet` returns not-`Ready` (e.g. row-manifest
   precondition mismatch after a manual edit), the record stays `unresolved`
   ⇒ summary `hasUnresolvedFailures()` ⇒ gate `FAILED`. Key question: can the
   reported sequence (apply verified, then manual edit, then restore request)
   strand a record in a lifecycle the reconciler cannot resolve, given the
   restore in the report never entered `RESTORING` on `main`?
2. **Exception inside `reconcileAll`.** Any `RuntimeException` from
   `writer.recaptureDb()` / capture during reconciliation sets `FAILED`
   directly. Post-#269, NULL-span rows are representable; post-#270 the typed
   capture-failure surface exists in preview — verify whether
   `reconcileAll`'s capture/recapture call sites convert typed capture
   failures to unresolved records or propagate them.
3. **Recovery-store read failures / artifact-pair poison state.**
   `RecoveryStartupArtifacts` / `RecoveryInspectionSnapshotReader`
   (`store/RecoveryStartupArtifacts.kt`, ADR-0011, #187): DB absent +
   snapshot present ⇒ `SuspiciousAbsence` ⇒ reconciliation fails on every
   process start. Not obviously reachable from the reported sequence (no ZIP
   restore in the report) — record as candidate with precondition.
4. **Lease/session acquisition failure at start.** Confirm whether this
   leaves the gate non-`FAILED` (then it cannot explain the journal) and
   close the candidate.

### Journal-signature comparison (AC-265-R3)

For each route, record the exact journal codes `main` emits (existing closed
vocabulary: `PhaseCode`, `ErrorFamily`, `InputCompositionCode`,
`ReconciliationClassification`; `CompositionModels.kt:45,100`,
`PlanPreview.kt:34`) and compare with the attached journal of the original
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
  accepted #265 two-path reproduction (Path A/B). The prior working-tree
  harness (`Issue265ManualEditRecoveryInstrumentationTest`) was the basis for
  the merged #269 recovery instrumentation; reuse/extend that surface rather
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
- Independent of #271 (status projection); findings may inform how #271
  presents unresolved-record states.

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
