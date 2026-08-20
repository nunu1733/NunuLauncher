# Specification: Issue #53 Onboarding Organization Proposal

- **Status:** Proposed
- **Issue:** #53
- **Stage:** A
- **Risk:** `risk: layout-data`
- **Procurement decision:** NO NEW DEPENDENCY
- **Requirements:** `FR-006`, `FR-007`, `FR-015`; `NFR-001`, `NFR-005`, `NFR-009`, `NFR-011`
- **UX contract:** `docs/product/organization-run-ux.md` §2.2 / Issue #4
- **Diagnostics contract:** `docs/engineering/organizer-diagnostics.md` / Issue #16
- **Execution baseline:** Issue #52 manual full-organization workflow

## 1. Goal

Deliver the MVP onboarding organization proposal as a non-blocking first-run surface. The proposal may be skipped or deferred without changing layout or organizer-owned state. Choosing `review organization` must enter the exact full-organization workflow delivered by Issue #52, with the only onboarding-specific run distinction being the diagnostics trigger `ONBOARDING_PROPOSAL`.

No second planner, application protocol, preview flow, recovery flow, or organization state machine may be introduced.

## 2. Scope

### In scope

- Onboarding eligibility/presentation for the organization proposal.
- Three user outcomes:
  - `skip`
  - `defer` / later
  - `review organization`
- Persistence only where required to preserve the chosen onboarding outcome and prevent accidental replay.
- Routing `review organization` into the Issue #52 manual full-organization workflow.
- Starting an onboarding-entered run with diagnostics trigger `ONBOARDING_PROPOSAL`.
- Reusing Issue #52 capture, planning, preview, explicit confirmation, apply, recovery, cancel, stale, and retry semantics without behavioral fork.
- Focused UI, accessibility, recreation, and trigger-separation tests.

### Out of scope

- A second organizer execution/orchestration pipeline.
- Automatic organization during onboarding.
- Any layout write caused by displaying/completing onboarding rather than explicit Issue #52 confirmation.
- Package-event incremental placement.
- New planner/application/recovery semantics.
- Rule import/export, usage signals, external classification, telemetry/network transport.
- New database/schema migrations unless an accepted owner contract is first updated.
- New post-onboarding organization entry points; Issue #52 remains the persistent manual entry.

## 3. Product behavior

### 3.1 Non-blocking proposal

The onboarding proposal must not block normal launcher use or onboarding completion. Merely displaying or recomposing the proposal performs no organization work and creates no layout/target/lock/rule mutation.

- `skip` continues onboarding and records only the minimum onboarding choice state needed by the host.
- `defer` continues onboarding and records only the minimum onboarding choice state needed by the host.
- `review organization` explicitly transitions into the Issue #52 full-organization flow.

`skip` and `defer` are not organization runs. They emit no organizer run-journal events.

This issue does not define a new nag/retrigger framework. If the existing onboarding host already distinguishes skip versus defer lifecycle behavior, preserve it. If it does not, implement only the smallest persistence necessary to preserve the two explicit choices within onboarding; do not invent a post-onboarding resurfacing policy.

### 3.2 Review organization = exact Issue #52 workflow

After `review organization`, the observable workflow is the same one used by manual full organization:

```text
explicit start
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

### 3.3 Retry and recreation

Retry always starts from a fresh capture. It must not replay or reuse an old:

- snapshot/capture revision;
- plan;
- preview authorization;
- checkpoint;
- write confirmation capability.

Recomposition, activity recreation, process recreation, onboarding lifecycle callbacks, and timeout-like events must never auto-confirm or replay a pending apply.

If a restored navigation surface cannot prove that its preview is still valid, it must require a fresh capture through the existing Issue #52 behavior rather than reconstructing write authority.

### 3.4 Confirmation boundary

Only the explicit confirmation action in the reused Issue #52 preview flow can authorize the apply path.

The following are never confirmation:

- proposal impression;
- skip/defer;
- tapping `review organization`;
- navigation to the review screen;
- app/activity resume;
- recomposition/recreation;
- timeout/lifecycle completion.

## 4. Diagnostics behavior

The accepted organizer diagnostics contract owns run-journal semantics.

### 4.1 Onboarding run start

When `review organization` actually starts a full organization run:

- emit `RUN_STARTED` with `trigger=ONBOARDING_PROPOSAL`;
- use the same `FULL_ORGANIZATION` run mode as the manual flow;
- retain the same run ID through the reused flow;
- subsequent run events must correlate to that onboarding-triggered run according to the diagnostics contract.

### 4.2 Proposal-only actions

Proposal display, `skip`, and `defer` are not organization-run operations and emit no run-journal event.

This issue does not add telemetry/network transport or a parallel onboarding analytics pipeline.

### 4.3 Manual regression

The existing settings/manual entry continues to start with `trigger=MANUAL_FULL`. Trigger selection must be supplied at the entry/start seam and must not fork the underlying state machine.

## 5. Architecture constraints

### 5.1 Trigger must be invocation context, not a new runner

The current Issue #52 coordinator hard-codes `Trigger.MANUAL_FULL` in run events. Generalize only the trigger source needed to reuse that coordinator.

A representative implementation is:

```kotlin
fun start(trigger: Trigger = Trigger.MANUAL_FULL)
```

with the active operation retaining the trigger for every coordinator-emitted event. Exact naming is implementation detail, but the design must satisfy:

- one `ManualOrganizationRun`/full-run state machine remains authoritative;
- manual callers preserve `MANUAL_FULL` by default;
- onboarding callers explicitly supply `ONBOARDING_PROPOSAL`;
- retry/cancel/stale-related coordinator events retain the active run's trigger;
- trigger selection does not change planner/apply semantics.

A broader rename of `ManualOrganizationRun` is not required for this issue and should be avoided unless it materially reduces risk.

### 5.2 Reuse the existing review surface

`ManualOrganizationPreferences` already renders the Issue #52 start/capture/planning/preview/confirm/apply/recovery states. Prefer routing onboarding into that same surface, parameterized only with the run trigger/start context required for Issue #53.

Do not copy the screen into an onboarding-specific result/preview implementation.

If navigation ownership prevents direct reuse, extract only the smallest shared composable seam necessary to keep behavior identical.

### 5.3 Onboarding host ownership

Current `main` code search does not expose a dedicated production class/path named as an onboarding host. `LawnchairLauncher.onCreate()` is a verified launcher lifecycle anchor, but it is not automatically the correct presentation owner.

Implementation must identify the existing first-run/onboarding eligibility owner before editing production code. If no dedicated owner exists, add the smallest launcher-owned proposal host required for this issue; do not create a general multi-step onboarding framework solely for #53.

## 6. Safety, privacy, accessibility, offline

- No layout write occurs before the reused Issue #52 explicit preview confirmation.
- All Issue #52 safe-apply/recovery invariants remain mandatory and cannot be bypassed by onboarding.
- Local full organization continues to work without network.
- No new external dependency, permission, transmission, or telemetry is introduced.
- The proposal and review path satisfy the organization UX accessibility contract: TalkBack labels/roles/states, meaningful focus, 200% font reflow, non-color-only warnings, keyboard/switch access, and no auto-confirming timeout.

## 7. Acceptance criteria

### AC-001 — Non-blocking accessible proposal

An eligible first-run user can see the proposal without blocking normal launcher/onboarding use, and proposal actions are accessible.

### AC-002 — Skip/defer are side-effect free

`skip` and `defer` change no layout or organizer-owned placement/lock/rule state, start no organization run, and emit no organizer run-journal event.

### AC-003 — Review reuses exact Issue #52 workflow

`review organization` enters the same Issue #52 full-organization coordinator and review surface. Capture, planning, preview, explicit confirmation, apply, recovery, cancel, stale, and terminal behavior are not forked for onboarding.

### AC-004 — Explicit confirmation remains the only write authorization

Choosing `review organization`, navigation, lifecycle/recomposition/recreation, or timeout does not apply changes. A write can proceed only through the reused Issue #52 explicit preview confirmation flow.

### AC-005 — Onboarding diagnostics trigger

A run started from onboarding emits/correlates `RUN_STARTED` with `trigger=ONBOARDING_PROPOSAL`; a run started from the settings/manual entry remains `MANUAL_FULL`. Proposal display/skip/defer emit no run-journal event.

### AC-006 — Fresh retry/recreation

Retry starts from a new capture/run and does not reuse stale plan, preview authorization, checkpoint, or write capability. Recreation cannot replay a stale apply.

### AC-007 — Offline and data-safety preservation

The onboarding-entered local full run remains functional offline and preserves all Issue #52/accepted safe-apply data-safety guarantees.

### AC-008 — Focused automated coverage

Automated tests cover proposal display, skip, defer, review, trigger separation, explicit confirmation, retry/recreation, and manual-flow regression. Required project checks pass.

## 8. Required test/QA scenarios

- Proposal displayed: no run starts.
- Skip: onboarding continues, no organizer side effect/journal run.
- Defer: onboarding continues, no organizer side effect/journal run.
- Review: same Issue #52 screen/state machine is reached.
- Review -> preview -> cancel: no write.
- Review -> preview -> explicit confirm: normal safe apply path.
- Retry after rejection/stale: fresh capture/run; no stale replay.
- Recomposition/activity recreation before confirm: no automatic confirmation.
- Recreation after previous terminal state: no stale plan/checkpoint replay.
- Offline review/full run: no network dependency introduced.
- Manual settings entry: remains `MANUAL_FULL` and behaviorally unchanged.
- Onboarding entry: `RUN_STARTED` is `ONBOARDING_PROPOSAL` and later events correlate to the same run.

## 9. Traceability

| Requirement | Coverage |
|---|---|
| FR-006 | Explicit review/confirm path is reused from the full organization workflow. |
| FR-007 | Onboarding is proposal-only until explicit review/confirmation; skip/defer are valid. |
| FR-015 | Existing result/reason/diagnostic behavior is reused; onboarding run trigger is distinguishable. |
| NFR-001 | No onboarding path bypasses checkpoint/apply/recovery safety; retry does not replay stale state. |
| NFR-005 | No network is added to the local full-run path. |
| NFR-009 | Proposal and reused review flow satisfy the accepted accessibility contract. |
| NFR-011 | Run journal distinguishes `ONBOARDING_PROPOSAL` from `MANUAL_FULL` without proposal-only events. |

## 10. Validation gates

At implementation completion, execute the repository-required checks and focused organizer tests. At minimum:

```bash
./gradlew --offline :lawnchair:testDebugUnitTest
./gradlew --offline :app:lawnWithQuickstepDebug
./gradlew spotlessCheck
```

Use current documented task names if they differ on the implementation branch and record exact commands/results in the PR.

Because #53 is `risk: layout-data`, the implementation PR must also satisfy the repository high-risk gate and independent assessment requirements before merge.

## 11. Approval gate

This specification is **Proposed**. Acceptance-criterion IDs are provisional until the repository's explicit spec approval step is performed. Implementation must not begin from this document as though it were accepted.
