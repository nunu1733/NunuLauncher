# Plan: Issue #53 Onboarding Organization Proposal

- **Status:** Draft — blocked on spec acceptance
- **Issue:** #53
- **Stage:** A
- **Spec:** `specs/53-onboarding-organization-proposal/spec.md` (Proposed)
- **Implementation start:** NOT AUTHORIZED until the spec is accepted per `AGENTS.md`
- **Primary risk:** `risk: layout-data`

## 1. Strategy

Implement Issue #53 as a thin onboarding entry into the already-delivered Issue #52 full-organization flow.

The intended production delta is:

1. make the Issue #52 run trigger an invocation parameter instead of a hard-coded manual constant;
2. allow the existing Issue #52 review surface to start/retry with that trigger;
3. add the non-blocking onboarding proposal and route `review organization` into the existing surface;
4. test side-effect boundaries, trigger separation, recreation, and manual regression.

No planner/application/recovery algorithm change is planned.

## 2. Phase 0 — Preflight and ownership verification

Before production edits:

- confirm Issue #52 remains closed/merged and its public surface is stable;
- re-read Issue #53 and its diagnostics handoff comment;
- re-read `docs/product/organization-run-ux.md` §2.2 and `docs/engineering/organizer-diagnostics.md`;
- locate the actual first-run/onboarding eligibility owner on the implementation branch;
- check for parallel work touching `ManualOrganizationRun`, `ManualOrganizationPreferences`, or the same onboarding host;
- record the selected host/seam in the PR.

Stop if onboarding requires new organization semantics rather than eligibility/presentation/routing.

## 3. Phase 1 — Parameterize Issue #52 run trigger

### Primary files

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`

### Implementation

Change the coordinator entry from a fixed manual trigger to a caller-supplied trigger with a manual default, for example:

```kotlin
fun start(trigger: Trigger = Trigger.MANUAL_FULL)
```

Extend the active operation to retain the trigger:

```kotlin
private data class Operation(
    val runId: RunId,
    val trigger: Trigger,
    ...
)
```

Then replace coordinator-local `Trigger.MANUAL_FULL` event literals with `operation.trigger` (or the pending plan's operation trigger) for:

- `RUN_STARTED`;
- `CAPTURED`;
- planning projection correlation;
- `PREVIEWED`;
- `USER_CANCELLED`;
- `USER_CONFIRMED`;
- coordinator-generated stale rejection;
- dismiss/cancel terminal correlation.

Keep `runMode=FULL_ORGANIZATION` unchanged.

Do not add an onboarding branch to capture/planning/materialize/apply logic.

### Diagnostic integration check

The application protocol emits some apply/recovery phases beneath the UI coordinator. Verify the accepted journal/correlation layer carries the `RUN_STARTED` trigger through those events by run ID. If it does not, fix the smallest diagnostics correlation seam required by the accepted contract; do not thread product-specific onboarding branches through `ApplyProtocol`.

### Unit tests

Add tests for:

- default `start()` remains `MANUAL_FULL`;
- `start(ONBOARDING_PROPOSAL)` emits onboarding `RUN_STARTED`;
- coordinator-emitted later phases retain the active trigger;
- manual and onboarding use identical state transitions/planner/apply calls;
- retry creates a fresh run ID and preserves the caller's selected trigger when the UI asks to retry;
- cancel/dismiss cannot mutate after authorization boundary or reuse stale pending state.

## 4. Phase 2 — Parameterize/reuse the existing review surface

### Primary files

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
- optionally `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` / `PreferenceNavigation.kt` only if the chosen onboarding routing requires a serializable route argument
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`

### Implementation

Prefer a minimal parameter on the existing screen, e.g.:

```kotlin
@Composable
fun ManualOrganizationPreferences(
    ...,
    trigger: Trigger = Trigger.MANUAL_FULL,
)
```

Every user action that begins a **new** run (`start`, retry, recapture, start again) must call `coordinator.start(trigger)`.

Confirmation, cancel, recovery, and result handling stay exactly as they are.

The manual settings route passes/uses the default `MANUAL_FULL`. The onboarding route passes `ONBOARDING_PROPOSAL`.

If the navigation framework cannot safely carry the trigger through recreation, convert the relevant route from an object to a serializable route value or introduce an equivalent stable entry-context argument. Do not infer trigger from mutable global state.

### Instrumentation regression

Verify:

- manual route behavior is unchanged;
- onboarding-triggered screen renders the same capture/planning/preview/confirm/recovery UI;
- recreation before confirmation does not auto-confirm;
- retry/recapture starts a fresh run with the original entry trigger.

## 5. Phase 3 — Add onboarding proposal/eligibility host

### Host selection

No dedicated onboarding-named production host was identified by current `main` search. `LawnchairLauncher.onCreate()` is a verified lifecycle anchor only, not a predetermined UI owner.

Phase 0 must select the real first-run/onboarding owner. If none exists, add the smallest launcher-owned host necessary for #53 and document why it is the least invasive seam.

Do not build a general onboarding framework for this issue.

### Likely file surface

Host-dependent, plus:

- `lawnchair/res/values/strings.xml` for proposal/action/accessibility text;
- one focused onboarding UI file if no existing surface is suitable;
- one focused instrumentation test file under `tests/organizer-instrumentation/...`.

### Proposal behavior

Expose:

- Skip
- Later / Defer
- Review organization

Rules:

- display/recomposition starts no organizer run;
- skip/defer persist only the minimum existing onboarding choice state and continue;
- skip/defer emit no organizer run-journal event;
- review navigates to/reuses the Issue #52 review surface with `ONBOARDING_PROPOSAL`;
- review itself does not confirm/apply anything;
- no lifecycle or timeout callback may invoke `confirm()`;
- no post-onboarding nag/retrigger policy is introduced.

## 6. Phase 4 — Recreation and stale-state protection

Test the boundaries explicitly because Issue #53 calls them out.

Required behaviors:

- recomposition does not duplicate proposal actions or start a run;
- activity recreation before review does not start a run;
- recreation after entering review cannot synthesize confirmation;
- retry after `InputUnavailable`, planning rejection, or stale state starts a new capture/run;
- old pending plan/checkpoint/write authorization is never serialized/replayed;
- if navigation state is restored, trigger context is restored but write authority is not.

Reuse the existing Issue #52 in-memory confirmation capability behavior; do not add persisted confirmation state.

## 7. Expected file surface

### Likely modified

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
- `lawnchair/res/values/strings.xml`
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`

### Host-dependent

- existing first-run/onboarding owner, or one small new proposal host file;
- `PreferenceRoutes.kt` / `PreferenceNavigation.kt` only if that is the chosen reusable route seam;
- a focused onboarding instrumentation test.

Avoid planner, application protocol, database, and recovery-store edits unless a verified diagnostics correlation defect blocks the accepted trigger contract. If such a defect materially broadens the issue, stop and record it.

## 8. Acceptance-to-test mapping

| Spec AC | Primary evidence |
|---|---|
| AC-001 | onboarding UI test: proposal accessible/non-blocking, no auto-start |
| AC-002 | UI/fake diagnostics: skip/defer produce no organizer run or layout-state call |
| AC-003 | unit + instrumentation: onboarding and manual enter the same coordinator/screen transitions |
| AC-004 | instrumentation: review/navigation/recreation cannot apply without explicit confirm |
| AC-005 | unit/journal assertions: onboarding `RUN_STARTED`, manual `MANUAL_FULL`, correlated later phases |
| AC-006 | unit + recreation test: fresh run/capture, no stale pending authorization replay |
| AC-007 | offline execution plus existing safe-apply/recovery regression suite |
| AC-008 | focused suites + repository gates recorded in PR |

## 9. Manual QA matrix

| Scenario | Expected result |
|---|---|
| Proposal shown | Launcher/onboarding remains usable; no organization run starts |
| Skip | Continues; no layout/organizer state change; no run-journal event |
| Defer | Continues; no layout/organizer state change; no run-journal event |
| Review organization | Opens exact Issue #52 review workflow; no write yet |
| Capture/plan succeeds | Existing preview is shown |
| Cancel preview | No write; terminal cancel behavior unchanged |
| Confirm preview | Existing checkpoint/apply/verify flow executes |
| Stale at apply | Existing stale/retry behavior; fresh capture required |
| Retry | New run ID/capture; onboarding trigger preserved |
| Recreate before confirm | No automatic confirm or write |
| Restore navigation | Entry trigger may restore; write authorization does not |
| Offline | Full local workflow remains available; no new network dependency |
| Manual settings entry | Same behavior as before, trigger `MANUAL_FULL` |
| Onboarding entry | `RUN_STARTED` uses `ONBOARDING_PROPOSAL` |

## 10. Validation commands

At minimum:

```bash
./gradlew --offline :lawnchair:testDebugUnitTest
./gradlew --offline :app:lawnWithQuickstepDebug
./gradlew spotlessCheck
```

Also run the focused organizer instrumentation tests on the supported emulator/device configuration and record exact commands/results.

Because the implementation is `risk: layout-data`, require before merge:

- green `final-status` for the tested head;
- independent `docs/assessment/pr-<PR>-<slug>.md` evidence from a separate session/agent.

## 11. Rollback

No data migration is planned.

Rollback order:

1. remove/disable the onboarding proposal host;
2. remove onboarding route trigger wiring;
3. revert the trigger parameterization to the existing manual default if necessary.

The underlying Issue #52 workflow remains intact, so rollback does not require layout conversion or recovery-store migration.

## 12. Suggested commit sequence

After spec acceptance and explicit GitHub commit authorization:

1. `refactor(organizer): parameterize full-run trigger`
2. `feat(onboarding): route organization proposal to full run`
3. `test(onboarding): cover proposal and recreation flow`

Keep unrelated cleanup out of the issue.

## 13. Stop conditions

Stop implementation and record the blocker if:

- onboarding requires different planning/apply/recovery semantics from Issue #52;
- a lifecycle requirement would need persisted write confirmation;
- a new schema, permission, external dependency, or network transport becomes necessary;
- diagnostics correlation cannot satisfy `ONBOARDING_PROPOSAL` without changing the accepted diagnostics contract;
- an active parallel PR owns the same organizer/onboarding seam without coordination.

## 14. Approval gate

This plan is intentionally **Draft** because the paired spec is still Proposed. It is a planning artifact only; implementation starts after the project performs the explicit spec-approval step and freezes the acceptance criteria.
