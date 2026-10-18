---
issue: "#53"
status: implemented
requirements:
  - FR-006
  - FR-007
  - FR-015
  - NFR-001
  - NFR-005
  - NFR-009
  - NFR-011
risk:
  - layout-data
updated: 2026-10-17
---

# Specification: Issue #53 Onboarding Organization Proposal

- **Status:** Accepted
- **Issue:** #53
- **Stage:** B — implementation
- **Risk:** `risk: layout-data`
- **Procurement decision:** NO NEW DEPENDENCY
- **Requirements:** `FR-006`, `FR-007`, `FR-015`; `NFR-001`, `NFR-005`, `NFR-009`, `NFR-011`
- **UX contract:** `docs/product/organization-run-ux.md` §2.2 / Issue #4
- **Diagnostics contract:** `docs/engineering/organizer-diagnostics.md` / Issue #16
- **Execution baseline:** Issue #52 manual full-organization workflow

## 1. Goal

Deliver the MVP onboarding organization proposal as a non-blocking fresh-install first-run surface. The proposal may be skipped or deferred without changing layout or organizer-owned state. Choosing `review organization` applies the existing defer semantics and opens the existing Organizer hub (Issue #607 amendment); the **fresh** full-organization run through the exact Issue #52 state machine, with the onboarding-specific diagnostics trigger `ONBOARDING_PROPOSAL`, is admitted later by the shared run surface's start row.

No second planner, application protocol, preview flow, recovery flow, or organization state machine may be introduced.

## 2. Scope

### In scope

- Fresh-install onboarding eligibility and presentation for the organization proposal.
- Three user outcomes:
  - `skip`
  - `defer` / later
  - `review organization`
- Minimal app-private persistence for the onboarding proposal outcome.
- Routing an admitted onboarding review run into the Issue #52 full-organization surface.
- Starting an onboarding-entered run with diagnostics trigger `ONBOARDING_PROPOSAL`.
- Reusing Issue #52 capture, planning, preview, explicit confirmation, apply, recovery, cancel, stale, and retry semantics without behavioral fork.
- Focused UI, accessibility, recreation, cross-entry, and trigger-separation tests.

### Out of scope

- A second organizer execution/orchestration pipeline.
- Automatic organization during onboarding.
- Any layout write caused by displaying/completing onboarding rather than explicit Issue #52 confirmation.
- Package-event incremental placement.
- New planner/application/recovery semantics.
- Rule import/export, usage signals, external classification, telemetry/network transport.
- New database/schema migrations unless an accepted owner contract is first updated.
- A general multi-step onboarding framework.
- Timer-based or same-session nagging.
- Automatic proposal display for an application upgrade or a backup/restore launch.
- New post-onboarding organization entry points; Issue #52 remains the persistent manual entry.

## 3. Product behavior

### 3.1 Eligibility and proposal outcome state

Issue #53 uses a bounded onboarding-proposal state owned outside the organizer layout/application data model:

```text
UNSEEN -> SKIPPED
       -> DEFERRED
       -> REVIEWED
```

These are mutually exclusive persisted proposal outcomes; implementation may use different names but must preserve the behavior below.

#### Eligibility

The proposal is eligible only for a **fresh-install onboarding session** that has not yet reached a terminal proposal outcome.

- A normal application upgrade is not eligible merely because the new #53 preference key is absent.
- A backup/restore launch is not eligible merely because the proposal outcome is absent or reset.
- Missing proposal state is therefore insufficient proof of fresh-install eligibility.
- The launcher-owned eligibility seam must use a reliable fresh-install/onboarding classification. If the implementation branch cannot distinguish fresh install from upgrade/restore with an existing reliable signal, implementation stops and records that missing owner contract instead of treating unknown provenance as eligible.

The proposal may first appear only after the launcher is resumed, interactive, and its initial workspace/model presentation is ready. It must not be launched from `Application.onCreate()` or an equivalent pre-UI lifecycle hook.

#### Outcome semantics

| User action | Persisted outcome | Automatic resurfacing |
|---|---|---|
| Skip | `SKIPPED` | Never for the current installation. The persistent manual Issue #52 entry remains available. |
| Later / Defer | `DEFERRED` | Suppressed for the remainder of the current app process/session. It may be presented again on the next qualifying launcher cold start. No timer or same-session retry is allowed. Repeated defer keeps the same behavior. |
| Review organization | Applies defer (`DEFERRED`) on leaving the proposal, then shows the existing Organizer hub. `REVIEWED` is recorded only after the onboarding-originated admission at the shared run surface start row succeeds (`Started`; Issue #607 amendment) | While the outcome is `DEFERRED` (proposal left without an admitted run): same as defer — suppressed for the current process/session and may be presented again on the next qualifying launcher cold start. Once `REVIEWED` is recorded (admission returned `Started`): never. Cancellation/failure of that run does not resurrect the onboarding proposal; retry remains inside the reused Issue #52 surface. |

System Back, outside-dismiss, or an equivalent user dismissal of the proposal is treated as `defer`, not `skip` and never as confirmation.

Proposal display, recomposition, skip, and defer perform no organization work and create no layout/target/lock/rule mutation. They emit no organizer run-journal event.

### 3.2 Review organization = defer + Organizer hub, then the fresh Issue #52 run from the run surface start row

`review organization` closes the proposal by applying the existing defer semantics and opens the existing Organizer hub. It is not confirmation, does not itself authorize any write, and does not start or admit a run.

The entry contract is (amended by Issue #607; the former Issue #370 direct-admission contract is intentionally superseded):

1. tapping `review organization` applies the existing defer semantics (`DEFERRED` persistence, in-process proposal suppression) and closes the proposal;
2. the existing Organizer hub — the same surface and rows as the settings route — is shown; no run admission occurs and the run surface is not opened directly;
3. the hub's start CTA is navigation only: to the existing run preamble face (T-07) when the coordinator is idle/terminal, or to the existing run surface showing the active run's current face otherwise;
4. the run surface's start row exclusively owns admission, requesting a new run from the existing Issue #52 coordinator/state-machine implementation with trigger `ONBOARDING_PROPOSAL` (carried as stable caller context from the onboarding entry);
5. persist `REVIEWED` only after that onboarding-originated admission returns `Started`; then show the existing Issue #52 review surface observing that run.

Connection wording (formerly Issue #370 D-16 "T-07 preamble skip"; superseded by Issue #607): the onboarding entry now follows the same path as the settings route — Organizer hub → standard T-07 preamble → run surface start row admission — and no longer bypasses the preamble.

The onboarding-originated flow must not attach itself to an already-active manual/onboarding run. With an active run, the hub start navigates to the existing run surface showing that run's current face without admission, and the flow must not relabel or display the existing run as onboarding. Busy/refused admission is observed only at the run surface start row's explicit start action; a failed/busy admission there keeps the onboarding outcome `DEFERRED`, and the existing UI/gate behavior (start-row retry, no trigger rewrite) is preserved.

Starting the fresh onboarding run may replace an inactive terminal UI state from a previous run in the same process, exactly as an explicit new Issue #52 start does. It must not reuse the previous run ID, plan, preview authorization, checkpoint, recovery confirmation capability, or active-operation state.

After admission, the observable workflow is the shared organization run workflow defined by Issue #52 as amended by Issue #369 (canonical order: admission → detection → selection only when candidates exist → capture/plan → proposal confirmation → checkpoint/apply → verify/result; the #369 spec owns the per-face integration). The per-state enumeration below is retained as the pre-#369 baseline shape; the normative order and display integration live in Issue #52/#369:

```text
explicit Review/Start
  -> Capture
  -> Plan
  -> Preview
  -> explicit Confirm | Cancel
  -> Checkpoint
  -> Apply
  -> Verify
  -> Success | recovery/failure terminal states
```

All existing Issue #52 safety branches remain intact, including rejected input/plan, stale recapture, checkpoint failure, apply/verify failure, recovery, cancel, and process/recreation behavior.

Onboarding must not skip preview or confirmation and must not introduce an alternate fast path to apply.

### 3.3 Retry, recapture, and recreation

Every retry, recapture, or start-again action that begins a new run starts from a fresh capture and new run ID. It must not replay or reuse an old:

- snapshot/capture revision;
- plan;
- preview authorization;
- checkpoint;
- write confirmation capability.

When the review surface was entered from onboarding, fresh retry/recapture actions retain `ONBOARDING_PROPOSAL`. A subsequent new run explicitly started from the persistent settings/manual entry uses `MANUAL_FULL`.

Recomposition, activity recreation, process recreation, onboarding lifecycle callbacks, and timeout-like events must never auto-confirm or replay a pending apply.

Only entry context needed to choose the next run trigger may be restored by navigation. Write authority is never serialized. If a restored surface cannot prove that its current preview belongs to a live process-local run, it requires a fresh capture rather than reconstructing write authority.

### 3.4 Confirmation boundary

Only the explicit confirmation action in the reused Issue #52 preview flow can authorize the apply path.

The following are never confirmation:

- proposal impression;
- skip/defer/dismiss;
- tapping `review organization`;
- navigation to the review screen;
- app/activity resume;
- recomposition/recreation;
- timeout/lifecycle completion.

## 4. Diagnostics behavior

The accepted organizer diagnostics contract owns run-journal semantics.

### 4.1 Onboarding run start

When the run surface start row admits a fresh full organization run for an onboarding-originated entry (trigger `ONBOARDING_PROPOSAL`; Issue #607 moved the admission origin here from the proposal tap):

- emit `RUN_STARTED` with `trigger=ONBOARDING_PROPOSAL`;
- use the same `FULL_ORGANIZATION` run mode as the manual flow;
- use a new run ID;
- retain that run ID and trigger through coordinator-emitted events;
- subsequent application/recovery run events correlate according to the accepted diagnostics contract.

A failed/busy fresh-entry admission does not create a second run and must not rewrite the trigger of an existing run.

### 4.2 Proposal-only actions

Proposal display, `skip`, `defer`, and proposal dismissal are not organization-run operations and emit no run-journal event.

This issue does not add telemetry/network transport or a parallel onboarding analytics pipeline.

### 4.3 Manual regression

The existing settings/manual entry continues to start new runs with `trigger=MANUAL_FULL`. Trigger selection is invocation context at the start/entry seam and does not fork the underlying state machine.

## 5. Architecture constraints

### 5.1 Trigger is invocation context, not a new runner

The current Issue #52 coordinator hard-codes `Trigger.MANUAL_FULL` in coordinator-emitted events. Generalize only the trigger source and start-admission result needed to reuse that coordinator safely.

A representative shape is:

```kotlin
sealed interface StartOutcome {
    data class Started(val runId: RunId) : StartOutcome
    data object Busy : StartOutcome
}

fun start(trigger: Trigger = Trigger.MANUAL_FULL): StartOutcome
```

Exact naming is implementation detail, but the design must satisfy:

- one `ManualOrganizationRun`/full-run state machine remains authoritative;
- manual callers preserve `MANUAL_FULL` by default;
- onboarding callers explicitly supply `ONBOARDING_PROPOSAL`;
- the active operation retains its trigger for coordinator-emitted events;
- retry/cancel/stale-related coordinator events retain the active run's trigger;
- a busy start cannot attach to or relabel an existing active operation;
- trigger selection does not change capture/planner/materialize/apply/recovery semantics.

A broader rename of `ManualOrganizationRun` is not required for this issue and should be avoided unless it materially reduces risk.

### 5.2 Reuse the existing review surface

`ManualOrganizationPreferences` already renders the Issue #52 capture/planning/preview/confirm/apply/recovery states. Onboarding must use that same composable/state-machine implementation. Issue #370 (D-16 connection wording), as amended by Issue #607: that shared surface is now the Issue #369 integrated run surface (TO-BE 8-state faces); both the manual entry and the onboarding entry reach it through the Organizer hub's start CTA (T-07 preamble). The former onboarding preamble-skip contract is intentionally superseded by Issue #607.

The route/screen may carry only stable entry context required to choose the trigger for future fresh actions (`retry`, `recapture`, `start again`). The onboarding `review organization` action itself starts no run (Issue #607): the entry reaches the run surface only through the hub's navigation-only start CTA, so opening the route never starts a run by itself; with an active run the surface shows that run's current face without adopting or relabeling it, and a fresh onboarding run is admitted only by the explicit start row action.

Do not copy the screen into an onboarding-specific result/preview implementation.

If navigation ownership prevents direct reuse, extract only the smallest shared composable seam necessary to keep behavior identical.

### 5.3 Onboarding host and persistence ownership

For #53, ownership is fixed as follows:

- **Lifecycle/presentation eligibility owner:** launcher UI lifecycle, anchored from `LawnchairLauncher` only after the launcher is resumed and initial workspace/model presentation is ready. `onCreate()` may initialize a narrow controller/store but must not display the proposal or start a run.
- **Proposal state owner:** a narrow app-private onboarding-proposal store using existing preference/storage infrastructure; it is not Launcher DB, recovery storage, organizer rule state, target-set state, or run journal state.
- **Presentation owner:** a small launcher-owned proposal surface controlled by that lifecycle owner. Do not create a general onboarding framework.
- **Review destination owner:** the existing preferences/navigation surface that renders `ManualOrganizationPreferences` (the Issue #369 integrated run surface; reached from settings and, since Issue #607, from onboarding through the Organizer hub), with stable entry context for `ONBOARDING_PROPOSAL` retry/recapture semantics.

The implementation must document the exact selected classes/files in its PR. If the current launcher cannot provide a reliable fresh-install-vs-upgrade/restore classification without a new product/platform contract, stop before production implementation rather than weakening eligibility.

## 6. Safety, privacy, accessibility, offline

- No layout write occurs before the reused Issue #52 explicit preview confirmation.
- All Issue #52 safe-apply/recovery invariants remain mandatory and cannot be bypassed by onboarding.
- Proposal state is not used as write authorization.
- Local full organization continues to work without network.
- No new external dependency, permission, transmission, or telemetry is introduced.
- The proposal and review path satisfy the organization UX accessibility contract: TalkBack labels/roles/states, meaningful focus, 200% font reflow, non-color-only warnings, keyboard/switch access, and no auto-confirming timeout.
- Proposal dismissal/focus behavior must leave the launcher usable and return focus meaningfully.

## 7. Acceptance criteria

### AC-001 — Bounded fresh-install eligibility and accessible non-blocking proposal

Only a reliably classified fresh-install onboarding session is automatically eligible. Upgrade/restore/unknown provenance is fail-closed and does not auto-show the proposal. The proposal appears only after the launcher is interactive, does not block normal launcher use, and its actions are accessible.

### AC-002 — Skip/defer semantics are explicit and side-effect free

`skip` suppresses automatic resurfacing for the installation. `defer` suppresses the proposal for the current process/session and allows it again only on a later qualifying cold start. Dismiss maps to defer. None of these starts an organization run, changes layout or organizer-owned placement/lock/rule state, or emits an organizer run-journal event.

### AC-003 — Review defers to the hub; the run surface start row admits a fresh run reusing the exact Issue #52 workflow

`review organization` applies defer and opens the existing Organizer hub without admission (Issue #607 amendment). The run surface start row — reached via the hub's navigation-only start CTA and the T-07 preamble — admits a new Issue #52 run with `ONBOARDING_PROPOSAL` and enters the same coordinator/review surface; `REVIEWED` is recorded only after that admission returns `Started`. The onboarding-originated flow cannot attach to or relabel a pre-existing active run. Capture, planning, preview, explicit confirmation, apply, recovery, cancel, stale, and terminal behavior are not forked for onboarding; the Issue #52 workflow reuse intent is maintained.

### AC-004 — Explicit preview confirmation remains the only write authorization

Choosing `review organization`, navigation, lifecycle/recomposition/recreation, proposal dismissal, or timeout does not apply changes. A write can proceed only through the reused Issue #52 explicit preview confirmation flow.

### AC-005 — Trigger separation and diagnostics correlation

A fresh run admitted from onboarding emits/correlates `RUN_STARTED` with `trigger=ONBOARDING_PROPOSAL`; a new run started from settings/manual remains `MANUAL_FULL`. Proposal display/skip/defer/dismiss emit no run-journal event. Busy admission does not rewrite another run's trigger.

### AC-006 — Fresh retry/recreation and cross-entry isolation

Retry/recapture/start-again creates a new run/capture and does not reuse stale plan, preview authorization, checkpoint, or write capability. Onboarding retry retains the onboarding trigger; a later explicit manual start uses the manual trigger. Recreation cannot replay a stale apply or reconstruct write authority from navigation state.

### AC-007 — Offline and data-safety preservation

The onboarding-entered local full run remains functional offline and preserves all Issue #52/accepted safe-apply data-safety guarantees.

### AC-008 — Focused automated coverage and repository gates

Automated tests cover eligibility, upgrade/restore fail-closed behavior, proposal display, skip, defer/resurface, dismiss, review admission/busy behavior, trigger separation, explicit confirmation, retry/recreation, cross-entry isolation, accessibility, and manual-flow regression. Required project checks, connected instrumentation, CI, and high-risk independent assessment pass.

## 8. Required test/QA scenarios

- Fresh-install proposal: appears only after interactive launcher readiness; no run starts on display.
- Upgrade launch: proposal does not auto-show when #53 state is absent.
- Backup/restore or unknown provenance: proposal does not auto-show without reliable fresh-install classification.
- Skip: no run/layout/organizer mutation; no journal event; no later automatic resurfacing.
- Defer/dismiss: no run/layout/organizer mutation; no journal event; no same-process resurfacing; next qualifying cold start may show again.
- Review while idle/terminal: `review organization` applies defer and shows the Organizer hub without admission; the hub start navigates to the T-07 preamble; the run surface start row admits a new run ID with `ONBOARDING_PROPOSAL`, and `REVIEWED` is recorded after `Started` (Issue #607).
- Review or hub start while another run is active: the proposal tap performs no admission (defer + hub display); the hub start shows the existing run's current face without admission; Busy/refused admission is observed only at the run surface start row and does not attach/relabel the existing run or record `REVIEWED` (Issue #607).
- Review -> preview -> cancel: no write; proposal remains `REVIEWED` and does not auto-resurface.
- Review -> preview -> explicit confirm: normal safe apply path.
- Retry after input rejection/planning rejection/stale: fresh capture/run; onboarding trigger preserved.
- Recomposition/activity recreation before confirm: no automatic confirmation.
- Process recreation: no serialized preview/checkpoint/write authority; restored entry context may only start a fresh run.
- Previous manual terminal -> onboarding review: previous state is not reused as the onboarding run.
- Previous onboarding terminal -> new settings/manual start: new run uses `MANUAL_FULL`.
- Offline review/full run: no network dependency introduced.
- Accessibility: TalkBack semantics, deterministic focus, 200% font scale, keyboard/switch traversal, and proposal dismissal focus restoration.

## 9. Traceability

| Requirement | Coverage |
|---|---|
| FR-006 | Explicit onboarding Review defers and opens the Organizer hub; the run is admitted at the shared run surface start row and then reuses the full organization workflow; explicit preview confirmation remains required (Issue #607). |
| FR-007 | Fresh-install onboarding is proposal-only; skip/defer/dismiss have bounded, explicit non-writing semantics. |
| FR-015 | Existing result/reason/diagnostic behavior is reused; onboarding run trigger is distinguishable without proposal-only journal events. |
| NFR-001 | No onboarding path bypasses checkpoint/apply/recovery safety; fresh-entry and retry never replay stale state. |
| NFR-005 | No network is added to eligibility, proposal, or local full-run behavior. |
| NFR-009 | Proposal and reused review flow satisfy the accepted accessibility contract. |
| NFR-011 | Run journal distinguishes `ONBOARDING_PROPOSAL` from `MANUAL_FULL`; proposal-only actions are intentionally absent. |

## 10. Validation gates

At implementation completion, execute the current repository commands and focused organizer tests. At minimum:

```bash
./gradlew spotlessCheck --console=plain
git diff --check
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --console=plain
./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest --console=plain
```

Run focused API 36.1 connected instrumentation for the new onboarding proposal surface and the reused Issue #52 review surface, including real Back/dismiss, 200% font scale, keyboard/DPAD traversal, fresh-entry/busy, recreation, and trigger-separation cases. Record exact commands/device/results in the PR.

Because #53 is `risk: layout-data`, the implementation PR must also satisfy:

- successful PR-event `CI / final-status` for the audited implementation head;
- applicable organizer instrumentation jobs on that head;
- a separate-session independent `docs/assessment/pr-<PR>-<slug>.md` assessment with the exact implementation Head SHA, CI URL, criteria mapping, executed test surface, and findings;
- successful mechanical `high-risk-gate` before merge.

## 11. Approval gate

This specification is **Accepted**. AC-001 through AC-008 are frozen for Stage B implementation. Production implementation may begin under the paired approved plan and remains subject to its explicit stop conditions and the repository high-risk evidence gate.

## 12. Revision history

- 2026-09-08: Accepted (Stage B implementation baseline for Issues #52/#53).
- 2026-09-20: Issue #370 connection-wording revision (D-16, disposition §3.4 "Continue, wording update only"). §3.2: the onboarding entry skips the Issue #369 transitional T-07 preamble face (method fixed to "organize as is"), and the post-admission workflow is referenced to the Issue #52/#369 canonical order instead of re-enumerating it. §5.2/§5.3: review-surface wording updated to the Issue #369 integrated run surface and the Organizer hub entry (Issues #366/#370). No acceptance-criteria, outcome, eligibility, trigger, or diagnostics change; AC-003 "reuses the Issue #52 workflow" is unchanged. Implemented by the Issue #370 PR.
- 2026-10-17: Issue #607 canonical amendment (docs-only, before source coding; accepted [spec 607](../607-onboarding-organizer-hub/spec.md) `6299eba07b`). §1 Goal, §3.1 `REVIEWED` row, §3.2 entry contract, AC-003, §4.1, §5.2, §5.3, §8 scenarios, §9 FR-006 row: the onboarding `review organization` action now applies the existing defer semantics and opens the existing Organizer hub without admission; admission with `ONBOARDING_PROPOSAL` is owned exclusively by the run surface start row (reached via the hub's navigation-only start CTA → T-07 preamble); `REVIEWED` is recorded only after that admission returns `Started`. The former Issue #370 direct-admission / T-07-preamble-skip wording above is intentionally superseded; the revision entries below are preserved as history. Outcome/eligibility/trigger/diagnostics semantics are unchanged beyond the recording timing and admission ownership defined by spec 607.
