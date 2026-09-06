---
issue: "#52"
status: accepted
requirements:
  - FR-002
  - FR-003
  - FR-004
  - FR-005
  - FR-006
  - FR-015
  - NFR-001
  - NFR-002
  - NFR-005
  - NFR-007
  - NFR-009
  - NFR-011
risk:
  - layout-data
updated: 2026-08-20
---

# Manual full-organization vertical slice

## Problem

The planner and transactional application/recovery module are available as separate Foundation capabilities, but a user cannot yet complete a safe full-organization run. This feature supplies the first **manual, end-to-end** workflow from an explicit user action through canonical capture, deterministic planning, review, conditional confirmation, safe application, verification, result, and recovery.

The workflow must preserve the safety priority of the organizer: no user-visible layout improvement justifies a stale write, an incompletely accounted item, a lock/profile change, a partial layout, or a false success. It is the MVP delivery for **FR-006**, and it reuses the accepted planner and application contracts rather than creating a second planning or persistence path.

## Outcome

A user can open the Home Screen settings, explicitly choose to organize the home layout, review a full-layout proposal, and confirm it. The run captures current state, constructs the canonical `OrganizationInput`, invokes the only planner entry point (`OrganizationPlanner.plan`), presents an accessible preview, and applies a validated plan solely through the existing layout-application/recovery module. The user then receives a truthful typed result and, after a verified apply, can reach a revision-bound recovery action.

> A confirmation is conditional consent for the captured layout revision, profile inventory, and device context. It is never an unconditional authorization to mutate the current Launcher database.

| Workflow stage | Required owner/boundary | Observable commitment |
|---|---|---|
| Entry and navigation | Organizer UI and preference navigation | A run starts only after the user selects the manual action. |
| Capture and input assembly | Launcher integration adapter | The planner receives the canonical `OrganizationInput`; UI code does not read `favorites` directly. |
| Planning | `OrganizationPlanner` | The existing deterministic planner is the only full-organization planner. |
| Preview and confirmation | Organizer UI | Counts, reasons, warnings, unplaced items, lock/profile constraints, and safe actions are visible before any write. |
| Apply and automatic recovery | Layout Application module | A verified checkpoint, serialized transaction, reload, and verification are used without UI-side DB/recovery mutation. |
| Result and explicit recovery | Organizer UI plus application-owned `inspectRecovery` / opaque confirmation | No success is displayed before verification; recovery remains revision-bound and result-specific without exposing a `RecoveryRequest`. |

## Dependencies and readiness

This specification depends on the accepted planner behavior in [spec 12](../12-deterministic-full-layout-planner-v1/spec.md), safe application/recovery behavior in [spec 13](../13-safe-layout-application/spec.md), empty-folder policy in [spec 24](../24-empty-folder-policy/spec.md), lock review in [spec 38](../38-lock-authoring-unknown-review/spec.md), the diagnostics contract in [organizer diagnostics](../../docs/engineering/organizer-diagnostics.md), the production planner-input composition from [Issue #83](https://github.com/nunu1733/NunuLauncher/issues/83), and the read-only recovery-preview seam from [Issue #84](https://github.com/nunu1733/NunuLauncher/issues/84). The organization-run UX contract, ADR-0003, and ADR-0004 remain governing sources for run/recovery flow, recovery storage, and lock semantics.

Implementation may begin only when the following are true on `main`: the dependencies identified by Issue #52 are closed and their accepted artifacts are present; the production source for the versioned rules, taxonomy, and classification signals can construct the already accepted `OrganizationInput`; and the run can use the canonical capture representation needed by the application module. If any of these conditions is not true, implementation must stop and open the owning contract or integration follow-up. This feature must not invent default rules, classification policy, a UI-specific snapshot, or a parallel writer.

## Scope

The feature includes the following work.

- A manual organization entry in Home Screen settings and typed navigation to the manual-run surface.
- A capture/integration adapter that builds the planner's accepted canonical input from current Launcher state, current device capabilities, profile inventory, accepted rule/taxonomy/signal sources, and full-organization target membership.
- A run coordinator that performs capture, planning, preview, conditional confirmation, conversion to `ValidatedLayoutPlan`, application, and result/recovery orchestration.
- Preview, details, confirmation, progress, result, and recovery UI for the manual trigger.
- Privacy-safe run-phase diagnostics for the manual flow, using the existing diagnostics port and closed event model.
- Focused unit, Compose UI, integration, and instrumentation evidence for normal, stale, failure, cancellation, recovery, recreation, accessibility, and no-write paths.

The feature deliberately excludes onboarding proposal (**#53**), package-event incremental placement (**#55**), automatic organization, rule import/export, usage-frequency signals, external classification, telemetry/network transport, new planner policy, application/recovery architecture, lock authoring semantics, and empty-folder deletion policy.

## Architecture and ownership boundaries

### Manual-run composition

The implementation introduces one manual-run coordinator behind a narrow organizer UI port. It owns ephemeral UI state and invokes existing ports; it does not own planning policy, SQL, recovery records, lock mutation, or diagnostics storage. The coordinator may expose state such as `capturing`, `preview`, `awaiting confirmation`, `applying`, `result`, and `recovery preview`, but that state is not a second source of truth for layout state or a substitute for the application lifecycle.

The capture adapter converts platform data into `OrganizationInput`. It must preserve all captured layout items, pages, device capabilities, profile availability, lock state, target membership, rule/taxonomy versions, and classification signals needed by the planner. The adapter must reuse the canonical capture/canonical-state conversion used at the application boundary; any platform or persistence-only representation remains on the integration side of the boundary.

The coordinator materializes a `ValidatedLayoutPlan` only from the exact `OrganizationInput` and `PlanningResult.Planned` associated with one capture. The plan preserves one explicit action per represented item and uses no `Delete` action. A generic confirmation never changes lock state and never authorizes a deletion, overwrite/replacement, or profile-identity change.

| Concern | Required boundary | Prohibited shortcut |
|---|---|---|
| Planning | `OrganizationPlanner.plan(OrganizationInput)` | A UI-only planner, heuristic, or planner input that omits canonical capture fields. |
| Layout writes | `LayoutApplicationModule.apply(ValidatedLayoutPlan)` | Writing `favorites`, using `ModelWriter`, or opening a database transaction from UI/coordinator code. |
| Recovery | `LayoutApplicationModule.inspectRecovery` plus its opaque confirmation handoff | Raw DB copy, backup restore as undo, recovery-store access from UI, a caller-constructed `RecoveryRequest`, or a bare point-ID restore. |
| Lock state | Accepted lock/application operations | Toggling lock state as part of generic confirmation or applying when lock state is unavailable. |
| User explanations | Planner/application typed result values and localized mappings | Reading the diagnostics journal to construct UI text. |
| Developer diagnostics | `DiagnosticsPort` projections | Raw exception text, item identity, package/component, profile identifier, coordinates, rule contents, or recovery payloads. |

### Run identity and diagnostics

The coordinator creates one opaque `RunId` for a manual run. To keep the manual pre-apply events and application events correlated, it passes that same ID to an **internal** application-module invocation path. The existing public result variants and mutation protocol remain unchanged. The legacy no-argument correlation path remains available for callers outside the coordinator.

The following event sequence is required when the corresponding transition occurs. Diagnostics are fail-open: a journal/logger failure cannot make a layout run fail, nor can it be used to derive user-visible reasons.

| Transition | Required diagnostic event | Notes |
|---|---|---|
| User starts manual full organization | `RUN_STARTED` | `trigger=MANUAL_FULL`, `runMode=FULL_ORGANIZATION`, version identifiers, and device-size summary only. |
| Capture completes | `CAPTURED` | No captured items, revisions, identities, or coordinates are emitted. |
| Planner succeeds or rejects | `PLANNED`, `PLANNING_REJECTED`, or `PLANNING_IMPOSSIBLE` | Use the accepted count/error projection only. |
| Preview is rendered | `PREVIEWED` | Emitted once per displayed preview. |
| User confirms or cancels before apply | `USER_CONFIRMED` or `USER_CANCELLED` | Cancellation is terminal and writes nothing. |
| Checkpoint onward | Existing application projections | `CHECKPOINTED`, apply, rollback/recovery, and verification events are emitted by the application protocol with the same run ID. |

Explicit recovery is not a new organization run. It uses the existing recovery projections keyed by recovery point. The UI first requests the accepted read-only `inspectRecovery` preview and passes its opaque one-shot confirmation back to the application module after explicit user consent; only the application module constructs the private `RecoveryRequest`.

## Observable behavior

### Entry, capture, and planning

The only MVP trigger is a deliberate user action in the Home Screen settings. Opening settings, onboarding, an app install/update/restore, a package event, process startup, background work, and timeout must not start a full run. The entry action is reachable by TalkBack and keyboard/switch navigation and explains that the user will review changes before anything is applied.

The coordinator starts a new capture for every start or retry. It does not reuse a prior snapshot, preview, checkpoint, or plan. Capture failure, unavailable required input, invalid rules, unsupported state, unavailable profile, capacity impossibility, or planner rejection shows a safe non-write result with retry and exit actions as appropriate. The preview is shown only for `PlanningResult.Planned`.

An empty change set is shown as **No changes**. It does not become a write-looking confirmation action and must not create a recovery point, reload the model, write either database, or mutate layout state.

### Preview and details

For a plannable, non-empty full run, the preview identifies the manual/full trigger, target profile scope, and target-set scope. It displays counts for moved, preserved, new placement/folder/page, unchanged where available, unplaced, and warnings. It exposes main typed reasons, including preservation and placement reasons, and does not hide warnings or unplaced items behind confirmation.

The preview is backed by the application-owned read-only plan preview ([Spec 194](../194-plan-preview-seam/spec.md)). Before publishing `State.Preview`, the coordinator acquires the change-level preview through `inspectPlan`: a fresh capture under a short non-blocking writer lease is verified against the planning snapshot revision, the exact plan is materialized once, and the per-item `PreviewChange` projection is published as optional preview `details` alongside the existing count summary. The executable `ValidatedLayoutPlan` itself stays coordinator-private and is never exposed through run state. Confirm applies that previewed plan directly; the application seam's revision and exact preconditions remain the final gate.

Environmental preview failures (`WriterBusy`, `Concurrent`, readiness unavailability, capture failure) degrade to a count-only preview with `details == null`, and confirm materializes as before. Plan/projection integrity violations (`OUTCOME_NOT_PLANNED`, `MATERIALIZATION_INVALID`) fail closed: the run ends as a planning rejection and never falls back to an unconfirmed apply. A preview-time stale capture ends the run with the existing typed stale result and A2 stale-rejection event.

When change-level details are present, the confirmation UI renders them per [Spec 195](../195-organizer-confirmation-change-list/spec.md): header counts come from the projected `PreviewCounts`, concrete group totals follow that specification so rows and each group heading share one truth (the warnings group counts only its item-warning rows; global / multi-item warnings remain header-count-only), changes group into moves / new folders / new pages / preserved / warnings in projection order, large groups truncate behind a per-group expand action that reports its state to accessibility, same-band adjustments are announced as explicit position adjustments (with row-ordinal notes), and a `details == null` preview announces the missing list while keeping the existing count-only surface and confirm flow. Wording, localization (values/ + values-ja/), and a11y behavior of the list are owned by that specification; this section fixes only the data path and safety.

The preview distinguishes locked placements and occupied regions, unavailable/disabled/quiet/private-space constraints, profile-related restrictions, widgets/app pairs, legacy shortcuts, and empty folders that remain preserved. The v1 preview must not offer an empty-folder delete option. A profile-identity change is rejected and never reaches confirmation. If future owner policy ever permits destructive behavior, it is out of scope for this version and requires its separate typed action, item-level effect display, and dedicated confirmation contract before being surfaced.

User-facing reason text is derived directly from the planner/application result codes and their allowed parameters. Developer diagnostics remain separate, count/category-only records.

### Confirmation, staleness, and apply

Confirm is available only for a non-empty, non-rejected preview. It names the intended effect, relevant counts/warnings, and the availability of recovery after a verified apply. Confirm never applies a saved plan unconditionally. When a change-level preview was obtained (spec 194), confirm applies the already-previewed `ValidatedLayoutPlan` without re-materializing; otherwise it materializes at confirm time as before. In both paths the application seam re-checks the complete current revision and exact preconditions before any mutation.

Before any mutation, the application seam compares the complete current revision and exact preconditions. A changed layout, page/device profile, profile inventory/availability, lock state, widget, folder/app-pair structure, or other captured input causes a typed stale/precondition rejection. The UI states that the layout changed, discards the old preview, restores meaningful focus, and requires a new capture and plan. It never silently replays or patches an old plan.

After confirmation, the UI displays phase-aware progress. A dismissal/back/cancel request before checkpoint cancels the run with no write. Once the application seam has started its atomic application protocol, a dismissal/back/cancel request must not interrupt the transaction unsafely; the UI explains this short non-cancellable interval and subsequently displays the truthful terminal result. This feature does not add an undocumented cancellation channel to the application contract.

### Result and recovery

The result surface maps all accepted `ApplyResult` variants to distinct, localized user-visible outcomes. It never claims success merely because the transaction was attempted or committed. A verified success displays applied/preserved/unplaced summary counts, warnings, the manual trigger, and a recovery action while the point remains restorable. The recovery action first presents the accepted revision-bound read-only preview and then passes its opaque confirmation to the application-owned confirmation handoff after explicit consent; UI/coordinator code never constructs or receives a `RecoveryRequest`.

| Application outcome | Required manual-run UI behavior |
|---|---|
| `NoChanges` | State that no changes were applied; no recovery action is shown for this run. |
| `Applied` | Show verified success, counts/warnings, and an available recovery action. |
| `Rejected` | State that nothing was applied. For stale/precondition causes, discard the preview and offer recapture; for checkpoint/store/writer causes, offer safe retry or exit. |
| `RolledBack` | State that no layout change remains and that the failed attempt was rolled back; offer retry/exit. |
| `Recovered` | State that the attempted change could not complete and the prior layout was restored; do not label it success. |
| `Unresolved` | State that current layout verification is incomplete, do not offer further blind writes, and provide the safe diagnostic/support path. |
| `RecoveryFailed` | State that automatic recovery could not be verified, do not label the layout restored, and provide the safe diagnostic/support path. |
| `ConcurrentRun` | State that another organizer operation is active; do not write and offer retry later. |

For explicit recovery, `Restored`, `NotRestorable`, `RestoreFailed`, `WriterBusy`, and `ConcurrentRun` are likewise presented distinctly. A stale, expired, corrupt, already-restored, or unavailable recovery point must not perform a write. The UI offers a fresh recovery preview only where a new current revision can be captured safely.

### Process recreation and lifecycle

Process recreation must not cause a blind plan replay, a blind apply, or a false terminal state. Before accepting a new operation, the existing application reconciliation handles unresolved checkpoint/application/recovery lifecycle records. The manual UI may restore only a non-mutating presentation state when its capture context is still valid; otherwise it announces the safe outcome and requires recapture. Restart reconciliation and recovery results remain typed and diagnostics-correlated through the existing application contract.

## Scenario matrix

| ID | Scenario | Required observable outcome |
|---|---|---|
| MFO-01 | User explicitly starts from Home Screen settings | A new manual/full run starts, emits `RUN_STARTED`, and begins fresh capture. |
| MFO-02 | Capture and planning produce a valid non-empty plan | Accessible preview/details precede confirmation; no write occurs before confirm. |
| MFO-03 | Planner returns invalid or impossible | Typed reject/impossible explanation, no confirmation/write, retry begins new capture. |
| MFO-04 | Planner produces an empty diff | **No changes** result; no checkpoint, layout/recovery write, or reload. |
| MFO-05 | User cancels from capture/planning/preview or before checkpoint | No write; terminal cancellation event where applicable; safe exit or new capture. |
| MFO-06 | User confirms but revision/profile/device/lock context changed | The old plan is discarded; no write; recapture/replan is required. |
| MFO-07 | Checkpoint create or validation fails | Typed non-apply result; no Launcher DB write; retry/exit is shown. |
| MFO-08 | Transaction rolls back | No layout change remains; result is rollback, not recovery/success. |
| MFO-09 | Commit, reload, or verification becomes uncertain or fails | Existing application recovery protocol runs; UI distinguishes recovered, unresolved, and recovery-failed outcomes. |
| MFO-10 | Verified apply succeeds | Verified result and revision-bound recovery action are shown. |
| MFO-11 | User requests recovery after success | Recovery preview uses current revision; only the application module consumes the explicitly confirmed opaque capability and constructs its private recovery request. |
| MFO-12 | Recovery point is stale/expired/corrupt/busy or recovery fails | No blind write; exact typed result and safe next action are shown. |
| MFO-13 | Process dies before/after checkpoint or around commit | No automatic replay; application reconciliation determines the durable state before new operations. |
| MFO-14 | Empty folder, locked placement, widget, app pair, unavailable profile, or unplaced item is present | Preview reflects the accepted typed preservation/constraint behavior; v1 offers no deletion or lock mutation. |
| MFO-15 | TalkBack, 200% font scale, keyboard/switch navigation, or warning display | Required actions, progress, reason, and recovery are accessible without color-only state or focus loss. |
| MFO-16 | Plan preview capture is stale, environmentally unavailable, or violates plan/projection integrity (spec 194) | Typed stale (no write, existing A2 event), count-only fallback with `details == null` (compatibility), or fail-closed planning rejection; the confirmation surface keeps functioning and never applies without the previewed plan or a fresh confirm-time materialization. |
| MFO-17 | Confirmation renders the concrete change list (spec 195) | Rows and header counts share one truth (`PreviewCounts` for headers; the warnings group counts its item-warning rows per spec 195), groups truncate behind accessible expand actions, degraded `details == null` announces the missing list, and TalkBack / switch / 200% font-scale behavior stays accessible. |
| MFO-18 | Confirmation or recovery-preview decision is offered (spec 209) | The confirm/cancel (and restore/cancel) decision actions render as Material3 buttons directly below the state heading, remain visible together regardless of change-list expansion or scroll, report the button role to TalkBack, and traversal reaches status → confirm → cancel → expand actions. |

## Accessibility and privacy requirements

The manual surface satisfies the organization-run UX accessibility contract. Trigger, preview summary, details, warnings, confirmation, progress, cancellation semantics, results, retry, and recovery all provide meaningful names, roles, states, and results to TalkBack. Focus is deterministically restored after opening, details expansion, stale/reject results, and recovery results. At 200% font scale, no required content/action is clipped, overlapped, horizontally scroll-dependent, or unreachable. Keyboard and switch traversal reaches every action, warnings combine text with non-color-only state, and progress announces each transition once without becoming a modal trap. No timeout auto-confirms or auto-cancels.

Diagnostics and support surfaces must preserve the accepted privacy boundary. They record only opaque IDs, trigger/run mode, closed phase/error codes, versions, device-size summary, and count-only plan/apply summaries. They never include item/package/component/title, user/profile identity, coordinate, category decision, rule content, revision, database row, recovery payload, exception message, stack trace, or free-form UI text. The feature introduces no telemetry, network transport, storage permission, or export destination beyond the existing user-initiated diagnostics capability.

## Migration, rollback, and rollout

No Launcher schema, recovery format, lock persistence, permission, network, or rule-format migration is part of this feature. The run writes only through the accepted application module, which supplies transaction rollback and recovery. Reverting the feature removes its entry/UI/coordinator code; it does not invalidate already-valid layout rows or recovery points. A verified recovery point remains governed by the existing retention contract.

Implementation is delivered in one high-risk feature PR that closes Issue #52 after this specification is accepted. The PR carries `risk: layout-data`, includes exact local verification results and UI evidence, receives a successful `CI / final-status` run, and includes an independent audit record under `docs/assessment/` in accordance with the high-risk evidence gate.

## Acceptance criteria

| AC | Acceptance criterion | Required evidence |
|---|---|---|
| MFO-AC-01 | Explicit manual start completes capture → plan → accessible preview → explicit confirmation → checkpoint/apply → verification/result through the accepted planner and application seams. | Coordinator protocol tests, Compose UI test, and API 36.1 instrumentation E2E test. |
| MFO-AC-02 | A canonical `OrganizationInput` is built from current accepted capture/rule/taxonomy/signal sources; no UI-specific planner or direct UI database read/write exists. | Capture-adapter tests, dependency review, and static/source-boundary tests. |
| MFO-AC-03 | Empty diff returns no changes and performs zero Launcher/recovery writes and zero model reload. | Public-seam counters and UI result test. |
| MFO-AC-04 | Cancel before checkpoint writes nothing; stale confirmation discards the old preview and forces recapture/replan. | Coordinator race/cancel tests and instrumentation stale test. |
| MFO-AC-05 | Checkpoint, apply, verification, automatic recovery, and explicit recovery failures have distinct safe outcomes with no false success. | Application-result mapping tests, fault-injection tests, and Compose result tests. |
| MFO-AC-06 | Generic confirmation does not alter lock state and does not authorize deletion, overwrite/replacement, or profile-identity change. | Preview/confirmation negative tests and action-set assertions. |
| MFO-AC-07 | Preview presents required scope, counts, reasons, warnings, unplaced state, lock/profile constraints, and preserved empty-folder state without developer diagnostics. | Compose semantics/content tests and fixture-based coordinator tests. |
| MFO-AC-08 | Manual run emits the required correlated, privacy-safe diagnostic phases; journal/logger failures do not fail the run. | Projection tests, fail-open tests, and negative non-containment tests. |
| MFO-AC-09 | Process recreation does not blindly replay a plan/apply; unresolved application lifecycle is reconciled before a new action. | Restart/recreation instrumentation test and lifecycle integration test. |
| MFO-AC-10 | TalkBack, focus restoration, 200% reflow, non-color-only warnings, keyboard/switch traversal, and phase progress satisfy the Issue #4 acceptance contract. | Compose semantics, font-scale, focus, and navigation tests. |
| MFO-AC-11 | Formatting, organizer JVM tests, instrumentation/debug-build checks, repository-contract checks, CI final status, and independent high-risk audit all succeed and are recorded in the PR. | Exact command output, CI URL, and `docs/assessment/pr-<n>-<slug>.md`. |

## References

- [Issue #52: manual full-organization vertical slice](https://github.com/nunu1733/NunuLauncher/issues/52)
- [AGENTS.md: issue-driven development, home-layout safety, and test rules](../../AGENTS.md)
- [DESIGN.md: organizer architecture and invariants](../../DESIGN.md)
- [Spec 12: deterministic full-layout planner](../12-deterministic-full-layout-planner-v1/spec.md)
- [Spec 13: safe layout application and recovery](../13-safe-layout-application/spec.md)
- [Spec 24: empty-folder preservation and deletion](../24-empty-folder-policy/spec.md)
- [Spec 38: lock authoring and unknown-state review](../38-lock-authoring-unknown-review/spec.md)
- [Organization run UX contract](../../docs/product/organization-run-ux.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Issue #83: production OrganizationInput source composition](https://github.com/nunu1733/NunuLauncher/issues/83)
- [Issue #84: read-only revision-bound recovery preview](https://github.com/nunu1733/NunuLauncher/issues/84)
- [GitHub workflow and high-risk evidence gate](../../docs/project/github-workflow.md)
