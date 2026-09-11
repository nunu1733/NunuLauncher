---
issue: "#265"
status: accepted
requirements:
  - AC-265-R1
  - AC-265-R2
  - AC-265-R3
  - AC-265-R4
  - AC-265-R5
risk:
  - layout-data
updated: 2026-09-11
---

# Post-apply manual layout edits: recovery-state consistency and Organizer reconciliation (remaining investigation)

## Problem

[Issue #265](https://github.com/nunu1733/NunuLauncher/issues/265) reported a
device-observed sequence: apply an organizer layout (`APPLY_VERIFIED`), manually
move an item out of an organizer-created folder, request **Restore layout**,
observe no visible restore, see Settings present a pre-organization-like state,
and then see the next organizer run terminate at `INPUT_NOT_READY` with
`INPUT_READINESS / RECONCILIATION_FAILED`.

The issue's investigation phase is complete and owner-accepted (issue comments,
2026-09-10). Its outcome was a **confirmed root-cause boundary plus a residual
unexplained mismatch**:

- **Confirmed and already fixed elsewhere.** The organizer apply path
  normalized folder-child rows to `SPANX/SPANY = NULL`; the platform
  `ModelWriter.moveItemInDatabase` path moves a folder child back to the
  workspace without rewriting the span columns, producing a desktop row with a
  `NULL` span; `RowManifestCodec.toCanonical` rejected that row with an untyped
  `IllegalArgumentException`, which escaped `RecoveryPreviewProtocol.inspect`
  and killed the Settings coroutine. Fixes are merged on `main`:
  - #269 (primary, merged via PR #274): preserve canonical representability
    across folder → workspace moves.
  - #270 (defense-in-depth, merged via PR #273): recovery preview maps capture
    failure to a typed result instead of leaking an exception.
  - #271 (closed, merged via PR #276; spec 271 marked implemented via PR
    #279): Settings status projection persistence.
- **Still unexplained (this spec's scope).** The attached journal of the
  original report shows the second run stopping at `INPUT_NOT_READY` with
  `INPUT_READINESS / RECONCILIATION_FAILED`, which on current `main` requires
  the startup `ReadinessGate` to be `FAILED`. The accepted reproduction of the
  manual-edit path ends in `InputUnavailable(InvalidCanonicalCapture(
  CAPTURE_UNAVAILABLE))` instead. The route by which the reported run reached
  gate `FAILED` was never established, and the attached diagnostics contain
  codes (`BLOCK_FALLBACK_NEEDED`, `BACKUP_ENTRY_NO_CURRENT_PATH`,
  durable-ID ambiguity counts, `reconciliationPersisted`) that do not exist
  anywhere in this repository's history, so the observed build may not have
  been `main`.
- **Separated observation.** A one-off `WriterBusy` on `confirmRecovery`
  immediately after apply (writer lease still settling; recovered on retry;
  user-facing surface "Restore did nothing") was recorded during the
  reproduction and explicitly kept out of the causal chain pending a decision
  on whether it warrants a separate UX/retry issue.

This document specifies the **remaining investigation** only: explain the
original `RECONCILIATION_FAILED` route (or establish it is not reproducible
on `main` / not producible by `main`'s journal), and decide the disposition of
the `WriterBusy` observation. It deliberately does not specify any fix; the
confirmed boundary's fix is owned by #269/#270/#271.

## Re-baseline (2026-09-11)

The prior snapshot was valid for baseline
`6b6bf8dd9fa0c42399185dbb13c30192f1e15962` (merge of PR #274). Per its
re-entry rule this revision is re-based onto
`ed7ce5a205985cd7a59224f60789aae4ba3c5ceb` (merge of PR #279); the issue body
and all comments were re-retrieved on 2026-09-11 (no comments after the
snapshot). The only relevant changes since the prior baseline are the #271
implementation (PR #276), its docs (PR #279), and unrelated spec-233 docs
(PR #277). Verified effects on this spec's scope:

- `ReadinessGate` gained an observable `stateFlow` mirror
  (`ReadinessGate.kt:43`); the transition semantics of `reconcile` (`:45`)
  and `failBeforeReconciliation` (`:66`) are unchanged, so no new
  gate-`FAILED` route was added.
- Startup reconciliation is now triggered by a process-scoped,
  idempotent `LawnchairApp.ensureOrganizerStartupReconciliation()`
  (`LawnchairApp.kt:128`) invoked from Launcher `onActivityResumed` **and**
  from `ManualOrganizationModule.get()` (`ManualOrganizationRun.kt:124-137`):
  re-opening Settings in a fresh process itself drives startup
  reconciliation — including the model-load kick via the #271 bridge
  `LauncherModel.startLoaderWithoutCallbacks()` and, on model-load timeout,
  the pre-reconciliation gate failure (`LawnchairApp.kt:148`). The reported
  build's trigger surface is unknown and does not change the enumeration
  below.
- Settings status presentation now reads the durable `OrganizerDurableStatus`
  projection (`LayoutApplicationModule.durableOrganizerStatus()` /
  `ManualOrganizationRun.readDurableOrganizerStatus()`); status observations
  in the reproduction contract use this seam.

Scope and acceptance criteria are unchanged by the re-baseline.

## Outcome

Either a reproduced, source-attributed explanation of how a run on current
`main` reaches persistent `NotReady(ReconciliationFailed, RECONCILIATION_FAILED)`
(gate `FAILED`) in a post-apply/restore scenario, or documented evidence that
the original journal's failure route cannot be produced by current `main`
(including the non-`main`-build hypothesis), plus an explicit recorded decision
on whether the `WriterBusy` observation becomes its own issue. #265 can then be closed
as an investigation record with no open actionable causal question on current
`main` — AC-265-R3 explicitly allows the non-`main`-build conclusion, under
which the original journal is not further attributable.

## Scope

- Enumerate and test every code path on `main` that can set `ReadinessGate` to
  `FAILED` (or return `RECONCILIATION_FAILED`) in a scenario reachable from the
  reported sequence: apply → manual edit → restore request → process/reopen →
  organizer start.
- Determine, for each candidate route, the durable-state precondition it
  requires (stranded unresolved record, artifact-pair poison state, store
  read failure, reconciliation exception) and whether the reported sequence
  can create that precondition.
- Assess whether the attached journal (28s gap, `APPLY_VERIFIED` →
  `INPUT_NOT_READY`/`INPUT_READINESS`/`RECONCILIATION_FAILED`, absent codes)
  is consistent with any `main` route, including the possibility that the
  observed build carried non-`main` instrumentation.
- Decide the `WriterBusy` disposition.

## Non-goals

- No change to the planner, capture strictness, reconciliation fail-closed
  rules, or the recovery-point contract (ADR-0003, Spec 13). Do not weaken
  reconciliation or verification to make the scenario proceed.
- No re-litigation of the confirmed NULL-span boundary or its accepted
  invariant; that is #269's accepted spec (`specs/269-folder-workspace-representability/spec.md`).
- No fix implementation. If the remaining investigation proves a new defect,
  a separate follow-up issue is created and linked; this spec's acceptance is
  the classification, not a fix.
- No ZIP/Nova restore readiness work beyond routes that share the startup
  reconciliation gate (e.g. the ADR-0011 artifact-pair route) and are
  candidate explanations for the observed journal.

## Terminology

- **ReadinessGate**: `LayoutApplicationModule`'s once-per-store-generation
  startup gate (`IDLE → RECONCILING → READY | FAILED`). `FAILED` makes every
  manual organization compose return `NotReady(ReconciliationFailed,
  RECONCILIATION_FAILED)` for the process lifetime.
- **Unresolved record**: a durable recovery record in `APPLYING`,
  `COMMITTED_UNVERIFIED`, or `RESTORING` at process start, which
  `RestartReconciler.reconcileAll` must resolve before the gate opens.
- **Artifact-pair poison state**: recovery DB absent while the
  `no_backup/recovery-inspection/` snapshot survives (`SuspiciousAbsence`),
  permanently failing startup reconciliation (ADR-0011 / #187).
- **`WriterBusy`**: `PreWriteRejection.WRITER_BUSY` returned by
  `confirmRecovery` when the writer lease is still settling.

## Established facts (from the accepted investigation; do not re-derive)

These are recorded as facts because the owner review accepted them; they are
not open questions:

1. The recovery lifecycle never marks `RESTORED` before restore write,
   correlated reload, recapture, and manifest verification; the control path
   (no manual edit) restores exactly, and the manual-edit path leaves the
   record `VERIFIED` with the layout unmutated when preview fails.
2. Residual `screen`/`cellX`/`cellY` columns on folder-child rows are benign;
   `toCanonical` maps `container >= 0` rows to `FolderChild(parent, rank)`.
3. The unrepresentable row was the platform writer's `NULL` span on a desktop
   row after a folder → workspace move; fixed by #269 (the move path now
   persists a valid `1×1` span; strict canonical capture is unchanged and
   still fails closed on malformed NULL-span rows).
4. `RecoveryPreviewProtocol.inspect` does call `captureCurrent`; its
   exception-leaking behavior is fixed by #270.
5. The attached journal's extra codes do not exist in this repository's
   history or closed diagnostic vocabulary.

## Reproduction / observation contract (investigation)

Target: current `main`, existing device/emulator seam, production adapters,
durable `RecoveryStore`, real `LauncherModel` reloads — the same harness
contract as the accepted two-path reproduction (Path A/B), extended as needed.

For each candidate gate-`FAILED` route, the investigation must produce:

- a deterministic reproduction (or an in-principle argument, if the route
  requires a state the reported sequence cannot create), recording:
  recovery preview result, confirm/result, lifecycle and persisted recovery
  state, recovery-point/backup manifest state, DB/canonical state after
  reload, recapture/verification result, Settings status projection
  (post-#271 seam: `readDurableOrganizerStatus()` and `readinessState`, plus
  the visible surface), and the next organizer readiness result with the
  exact journal codes;
- the precise source condition (file/line or invariant) that sets the gate to
  `FAILED` or returns `RECONCILIATION_FAILED`;
- the journal signature the route emits on `main` (codes that do exist:
  `INPUT_NOT_READY`, `INPUT_READINESS`, `RECONCILIATION_FAILED`), for
  comparison against the attached journal.

## Failure, cancellation, and stale-state expectations

- All instrumentation is read-only with respect to user layouts except where
  the investigation itself performs organizer apply/restore through public
  seams; the Spec 13 recovery contract (recovery point, transactional apply,
  post-apply re-verification) applies unchanged to every mutation.
- If a candidate route cannot be triggered without artificial state injection,
  that is an acceptable negative result: record the required precondition and
  conclude the reported sequence cannot reach it unaided.
- Nothing in this investigation may leave a device or emulator in a stranded
  unresolved-record or poison state; the harness must restore its snapshot.

## Unsupported cases

- Diagnostics from builds that are not `main` cannot be decoded beyond the
  absent-code analysis already recorded; no attempt to reverse-engineer
  non-`main` instrumentation.
- No claim may be made that any candidate route is *the* reported route
  without a journal-signature match or a deterministically reproduced
  precondition.

## Privacy / security / accessibility

- Investigation only; no new data collection. Any journal/diagnostic evidence
  recorded in the issue must follow the existing diagnostics-export rules and
  must not include personal data beyond layout/DB state already covered by the
  accepted diagnostics vocabulary.

## Dependencies

- #269 (closed, merged PR #274) — primary representability fix.
- #270 (closed, merged PR #273) — typed preview capture failure.
- #271 (closed, merged PR #276; spec implemented via PR #279) — Settings
  status projection persistence; its durable `OrganizerDurableStatus`
  projection is the seam through which status observations are recorded, and
  its settings-first reconciliation trigger is part of the reopen step of
  the reported sequence.
- #153 (closed) post-ZIP-restore `NotReady`; #172 (closed) readiness
  diagnostics; #150/#155 (closed) apply-time A7 verification — lineage only.
- ADR-0003 (recovery-point storage), ADR-0011 (ZIP-restore recovery
  artifacts), Spec 13 (safe layout application).

## Compatibility

- No behavior changes are proposed by this spec. Any follow-up fix issue
  created from the outcome owns its own compatibility analysis.

## Acceptance criteria (investigation)

- **AC-265-R1**: Every `main` code path that can produce gate `FAILED` /
  `RECONCILIATION_FAILED` reachable from the reported sequence is enumerated,
  each with its durable-state precondition, source location, and journal
  signature — including at minimum: (a) unresolved-record reconciliation whose
  automatic recovery fallback cannot prepare its write set; (b) the ADR-0011
  artifact-pair poison state and other recovery-store read failures; (c) an
  exception thrown inside `reconcileAll` (post-#269/#270 capture behavior
  included); (d) reconciliation lease/session acquisition failure.
- **AC-265-R2**: For the plausible routes, a deterministic reproduction or a
  recorded negative result with the required precondition exists.
- **AC-265-R3**: A conclusion states whether the attached journal is
  consistent with any `main` route or is attributable to a non-`main` build,
  with the evidence trail (code-absence analysis, journal signature match or
  mismatch).
- **AC-265-R4**: The `WriterBusy` observation has an explicit recorded
  disposition: separate follow-up issue (linked) or will-not-fix with reason.
- **AC-265-R5**: If a new defect is proven, it is recorded as a new
  classification with reproduced evidence and split into its own follow-up
  issue(s); no fix is implemented under #265 itself.

## Unresolved decisions

- None blocking the investigation. The choice between "reproduce the route"
  and "prove it unreachable on `main`" is the investigation's own outcome, not
  a precondition.
