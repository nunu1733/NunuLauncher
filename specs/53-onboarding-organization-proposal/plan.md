# Plan: Issue #53 Onboarding Organization Proposal

- **Status:** Draft — blocked on spec acceptance
- **Issue:** #53
- **Stage:** A
- **Spec:** `specs/53-onboarding-organization-proposal/spec.md` (Proposed — review-ready)
- **Implementation start:** NOT AUTHORIZED until the spec is accepted per `AGENTS.md`
- **Primary risk:** `risk: layout-data`
- **Baseline:** `main` after Issue #52 merge (`836ccff7d90bf9df3538f8f08acb0353e407a6b3`)

## 1. Strategy

Implement Issue #53 as a thin launcher-owned onboarding proposal that admits a fresh run into the already-delivered Issue #52 full-organization flow.

The intended production delta is:

1. add a bounded fresh-install proposal outcome/eligibility owner outside Launcher DB and organizer run state;
2. parameterize the Issue #52 run trigger and return an explicit fresh-start admission result;
3. allow the existing Issue #52 review surface to retain entry trigger context for retry/recapture without auto-starting from composition;
4. present Skip / Later / Review from the launcher only after it is interactive;
5. on Review, start a fresh `ONBOARDING_PROPOSAL` run **before** navigation and navigate only after admission succeeds;
6. test eligibility, no-side-effect proposal behavior, busy/cross-entry isolation, recreation, accessibility, and manual regression.

No planner/application/recovery algorithm change is planned.

## 2. Phase 0 — Preflight and frozen ownership decisions

Before production edits:

- confirm Issue #52 remains closed/merged and its public state machine/review surface is stable;
- re-read Issue #53 and its diagnostics handoff comment;
- re-read `docs/product/organization-run-ux.md` §2.2 and `docs/engineering/organizer-diagnostics.md`;
- check for parallel work touching `ManualOrganizationRun`, `ManualOrganizationPreferences`, preference navigation, or `LawnchairLauncher`;
- confirm the implementation branch still has a reliable way to classify fresh install separately from upgrade/restore before adding any automatic proposal display.

The Stage A ownership decisions are intentionally fixed rather than deferred to implementation:

| Concern | Owner / rule |
|---|---|
| Fresh-install eligibility trigger | Launcher UI lifecycle, anchored from `LawnchairLauncher` only after resume + initial workspace/model readiness. No proposal from `Application.onCreate()`. |
| Proposal outcome persistence | One narrow app-private proposal store using existing preference/storage infrastructure. Never Launcher DB, recovery store, target/rule state, or run journal. |
| Proposal presentation | One small launcher-owned surface/controller. Do not build a general onboarding framework. |
| Organization execution | Existing Issue #52 `ManualOrganizationRun` state machine and application seams only. |
| Review destination | Existing preferences navigation / `ManualOrganizationPreferences` surface. |
| Write authorization | Existing Issue #52 preview confirmation capability only; nothing in onboarding state is authorization. |

If no reliable fresh-install-vs-upgrade/restore classification can be established from accepted/current platform state, stop and record the missing owner contract. Do **not** use “proposal preference missing” as the eligibility predicate.

Stop if onboarding requires new organization semantics rather than eligibility/presentation/routing.

## 3. Phase 1 — Add bounded proposal outcome / eligibility state

### Likely files

Use the smallest existing preference package that can own app-private launcher UI state, plus one focused controller/store. Representative paths/names are illustrative:

- `lawnchair/src/app/lawnchair/organizer/ui/onboarding/OnboardingOrganizationProposalStore.kt`
- `lawnchair/src/app/lawnchair/organizer/ui/onboarding/OnboardingOrganizationProposalController.kt`
- existing preference manager/store file only if required to register the value
- focused JVM test under `tests/unit/...`

Do not add a Launcher DB column or migration.

### State model

Represent the accepted product behavior with a closed proposal outcome equivalent to:

```kotlin
enum class ProposalOutcome {
    UNSEEN,
    DEFERRED,
    SKIPPED,
    REVIEWED,
}
```

The store is not an organizer state machine. It only answers whether the launcher may show the proposal and records user proposal outcomes.

### Eligibility rules

Implement a narrow `evaluateEligibility(...)` boundary with these invariants:

- fresh-install onboarding + `UNSEEN` => eligible at the first qualifying launcher-ready opportunity;
- fresh-install onboarding + `DEFERRED` => eligible on a later **cold-start process**, never again in the same process where defer occurred;
- `SKIPPED` => never auto-show for that installation;
- `REVIEWED` => never auto-show for that installation;
- normal app upgrade => fail-closed / no automatic proposal;
- backup/restore or unknown provenance => fail-closed / no automatic proposal;
- missing persisted outcome alone => not proof of fresh install.

Use a process-local “already shown/deferred this process” guard for the same-session suppression rule. Do not implement a timer, alarm, worker, notification, or background retry.

### Proposal-only side-effect tests

Add tests proving:

- eligibility evaluation never accesses the organization planner/application writer;
- display/recomposition does not create a run ID;
- skip stores `SKIPPED` only;
- defer/dismiss stores `DEFERRED` and suppresses same-process redisplay;
- a simulated next qualifying cold-start process may show `DEFERRED` again;
- upgrade/restore/unknown provenance never becomes eligible merely from an absent key;
- proposal-only actions emit no `RunEvent`.

## 4. Phase 2 — Parameterize Issue #52 run trigger and fresh-start admission

### Primary files

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`

### Implementation

Change the coordinator entry from a fixed manual trigger to caller-supplied invocation context and return a typed/closed admission result. Representative shape:

```kotlin
sealed interface StartOutcome {
    data class Started(val runId: RunId) : StartOutcome
    data object Busy : StartOutcome
}

fun start(trigger: Trigger = Trigger.MANUAL_FULL): StartOutcome
```

Extend the active operation to retain the trigger:

```kotlin
private data class Operation(
    val runId: RunId,
    val trigger: Trigger,
    ...
)
```

The successful start boundary must allocate a fresh run ID, clear only inactive previous-run UI/pending state as the existing explicit Start does, and enter `Capturing`. A busy start must be a no-op: it cannot clear state, allocate a second run, change trigger attribution, or let a new caller attach to the active operation.

Replace coordinator-local `Trigger.MANUAL_FULL` event literals with `operation.trigger` (or the pending plan's operation trigger) for every coordinator-emitted event, including:

- `RUN_STARTED`;
- `CAPTURED`;
- planning projection correlation;
- `PREVIEWED`;
- `USER_CANCELLED`;
- `USER_CONFIRMED`;
- coordinator-generated stale rejection;
- dismiss/cancel terminal correlation.

Keep `runMode=FULL_ORGANIZATION` unchanged.

Do not add an onboarding branch to capture/planning/materialize/apply/recovery logic.

### Diagnostic integration check

The application protocol emits apply/recovery phases beneath the UI coordinator. Verify the accepted diagnostics correlation layer carries the run's trigger by run ID according to `organizer-diagnostics.md` rather than requiring product-specific branches in `ApplyProtocol`.

If this is not true on the implementation head, make only the smallest correlation fix already required by the accepted diagnostics contract. If satisfying the trigger requires a new diagnostics contract, stop.

### Unit tests

Add/retain tests for:

- default `start()` remains `MANUAL_FULL`;
- `start(ONBOARDING_PROPOSAL)` returns `Started(newRunId)` and emits onboarding `RUN_STARTED`;
- coordinator-emitted later phases retain the active trigger;
- a busy onboarding start during an active manual run returns `Busy`, creates no run, changes no state, and does not relabel diagnostics;
- a busy manual start during an active onboarding run behaves symmetrically;
- a fresh onboarding start after an inactive prior manual terminal uses a new run ID and does not reuse the prior pending/authorization state;
- manual and onboarding use identical planner/materialize/apply calls and state transitions;
- retry creates a fresh run ID while preserving the caller-selected entry trigger;
- cancel/dismiss cannot mutate after application admission or reuse stale pending state.

## 5. Phase 3 — Parameterize and reuse the existing review route/surface

### Primary files

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt`
- the existing preferences activity/start-destination bridge used to open a concrete `PreferenceRoute`
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`

### Stable route context

The current route is `data object HomeScreenManualOrganization`. Convert it only as much as needed to preserve entry context through activity/navigation recreation. Prefer a small serializable route value, for example:

```kotlin
@Serializable
data class HomeScreenManualOrganization(
    val entry: OrganizationEntry = OrganizationEntry.MANUAL,
) : PreferenceRoute

@Serializable
enum class OrganizationEntry { MANUAL, ONBOARDING }
```

Map that navigation-only value to `Trigger.MANUAL_FULL` / `Trigger.ONBOARDING_PROPOSAL` at the screen/start seam. Do not serialize run IDs, plans, checkpoints, preview capabilities, or write authorization.

### Review surface

Parameterize the existing screen with the trigger used for **future fresh actions**:

```kotlin
@Composable
fun ManualOrganizationPreferences(
    ...,
    trigger: Trigger = Trigger.MANUAL_FULL,
)
```

Every user action that begins a new run (`retry`, recapture, start again, ordinary manual start) calls `coordinator.start(trigger)`.

Confirmation, cancel, recovery, and result handling stay exactly as delivered by #52.

Important: the onboarding route itself does **not** auto-start from `LaunchedEffect`/composition. The initial onboarding run is already admitted by the launcher Review action before navigation. This avoids replay on recomposition/activity recreation.

### Navigation/admission contract

The launcher Review action executes in this order:

1. call the shared Issue #52 coordinator `start(ONBOARDING_PROPOSAL)`;
2. if `Started`, persist proposal outcome `REVIEWED`;
3. navigate/open preferences with onboarding entry context;
4. if `Busy`, do not mark `REVIEWED`, do not navigate as though onboarding started, and leave the proposal safely retryable/dismissible.

This ordering prevents a process-local previous terminal/active state from being mistaken for the onboarding run.

### Instrumentation regression

Verify:

- manual settings route behavior is unchanged and a new manual start is `MANUAL_FULL`;
- an already-admitted onboarding run renders through the same screen/state transitions;
- screen composition/recomposition does not start another run;
- recreation before confirmation does not auto-confirm or allocate another run;
- retry/recapture starts a fresh run with the original route trigger;
- prior manual terminal -> admitted onboarding review shows the new onboarding run, not the prior terminal;
- prior onboarding terminal -> a subsequent explicit new manual start uses `MANUAL_FULL`;
- navigation restoration retains entry trigger only, never write authority.

## 6. Phase 4 — Add launcher-owned proposal presentation

### Lifecycle anchor

`LawnchairLauncher` owns the user-visible timing because the accepted behavior is a non-blocking first-run launcher proposal.

Do not display from `onCreate()` merely because it is an available hook. Add/invoke the narrow proposal controller only after all of the following are true:

- the launcher Activity is resumed;
- the launcher is in/has reached its normal interactive state;
- initial workspace/model presentation needed for normal use is ready;
- proposal eligibility is true;
- the proposal has not already been shown/deferred in this process.

Use an existing post-bind/ready lifecycle signal if one exists on the implementation head. If no reliable readiness signal exists, stop and record that host gap rather than using an arbitrary delay.

### Presentation surface

Add one small launcher-owned proposal UI surface. It may be implemented with the most local existing Lawnchair floating/Compose surface pattern, but it must not become a general onboarding framework.

Expose exactly the accepted outcomes:

- Skip
- Later
- Review organization

Rules:

- display/recomposition starts no organizer run;
- Back/outside/user dismissal is equivalent to Later;
- Skip records `SKIPPED` and closes the proposal;
- Later/dismiss records `DEFERRED` and closes the proposal for the current process;
- Review follows the fresh-start admission order from Phase 3;
- proposal display/skip/defer/dismiss emits no organizer run-journal event;
- no lifecycle callback or timeout invokes `confirm()`;
- no timer/alarm/notification/background worker is introduced;
- normal launcher interaction remains available after dismiss/skip/defer.

### Accessibility

The proposal must provide:

- TalkBack names/roles for proposal and all three actions;
- deterministic initial focus and meaningful focus return on close;
- 200% font-scale reflow without clipping/unreachable actions;
- keyboard/DPAD/switch traversal to every action;
- no color-only state or warning;
- no timeout that selects an outcome or confirms organization.

## 7. Phase 5 — Recreation, cross-entry, and stale-state protection

Test these boundaries explicitly because the shared process-local #52 coordinator makes entry isolation important.

Required behaviors:

- proposal recomposition does not duplicate proposal actions or start a run;
- activity recreation before Review does not start a run;
- launcher recreation after defer does not show again in the same process;
- a later qualifying cold start can show a deferred proposal again;
- recreation after entering Review cannot synthesize confirmation or another start;
- retry after `InputUnavailable`, planning rejection, or stale state starts a new capture/run with onboarding trigger;
- old pending plan/checkpoint/write authorization is never serialized/replayed;
- restored navigation carries only stable entry context;
- active manual run + onboarding Review => `Busy`, no attach/relabel/write;
- inactive manual terminal + onboarding Review => fresh onboarding run ID/state;
- onboarding terminal + later explicit manual start => fresh manual run ID/trigger;
- admitted atomic application remains governed by existing #52 Back/dismiss safety and cannot be interrupted by proposal/navigation code.

Reuse the existing Issue #52 in-memory confirmation capability behavior; do not add persisted confirmation state.

## 8. Expected file surface

### Likely modified

- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` — narrow launcher-ready proposal invocation only
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt`
- `lawnchair/res/values/strings.xml`
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`

### Likely new and narrow

- one onboarding proposal state/controller/store file or a very small set under an existing Lawnchair/organizer UI package;
- one onboarding proposal UI surface file if no local existing surface can host it cleanly;
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` or equivalent focused class.

Avoid planner, application protocol, Launcher DB, recovery-store, rule, target-set, schema, permission, or network edits. If one becomes necessary, stop and re-scope through the owning contract.

## 9. Acceptance-to-test mapping

| Spec AC | Primary evidence |
|---|---|
| AC-001 | eligibility unit tests + launcher instrumentation: fresh-install only, upgrade/restore/unknown fail-closed, interactive non-blocking accessible presentation |
| AC-002 | store/UI instrumentation: skip terminal suppression; defer/dismiss same-process suppression + next cold-start eligibility; zero organizer run/journal/layout side effects |
| AC-003 | coordinator unit + launcher/preferences instrumentation: Review fresh admission, busy no-attach, same #52 coordinator/screen transitions |
| AC-004 | instrumentation: Review/navigation/recomposition/recreation cannot apply without explicit reused preview confirm |
| AC-005 | unit/journal assertions: onboarding `RUN_STARTED`, manual `MANUAL_FULL`, correlated later phases, busy preserves existing trigger |
| AC-006 | unit + recreation/cross-entry instrumentation: fresh run/capture, trigger preserved by entry, no stale authorization replay |
| AC-007 | offline execution plus existing #52 production E2E / safe-apply regression suite |
| AC-008 | focused suites + repository gates + API 36.1 connected evidence + independent high-risk audit |

## 10. Manual QA matrix

| Scenario | Expected result |
|---|---|
| Fresh-install launcher becomes ready | Proposal may appear once; launcher remains usable; no organization run starts |
| App upgrade with missing #53 state | Proposal does not auto-show |
| Backup/restore or unknown provenance | Proposal does not auto-show unless reliably classified fresh-install |
| Skip | Closes; stores skipped; no layout/organizer/journal side effect; never auto-resurfaces |
| Later | Closes; stores deferred; no layout/organizer/journal side effect; no same-process resurfacing |
| Next qualifying cold start after Later | Proposal may appear again; still no run until Review |
| Dismiss/Back on proposal | Same semantics as Later |
| Review while coordinator idle/terminal | Fresh onboarding run admitted, `REVIEWED` stored, exact #52 surface opens |
| Review while another run active | Busy; no attach/relabel/new run/write; proposal remains safely actionable |
| Capture/plan succeeds | Existing preview is shown |
| Cancel preview | No write; proposal remains completed/reviewed |
| Confirm preview | Existing checkpoint/apply/verify flow executes |
| Stale at apply | Existing stale behavior; retry requires fresh capture |
| Retry | New run ID/capture; onboarding trigger preserved |
| Recreate before confirm | No automatic confirm, duplicate start, or write |
| Restore navigation | Entry trigger may restore; run/write authorization does not |
| Prior manual terminal -> onboarding Review | New onboarding run replaces prior inactive UI state for this entry |
| Prior onboarding terminal -> manual new start | New run uses `MANUAL_FULL` |
| Offline | Proposal and full local workflow require no new network dependency |
| Accessibility | TalkBack, focus return, 200% reflow, DPAD/keyboard/switch reach all actions |

## 11. Validation commands

Use the current repository task names established by Issue #52 rather than provisional module names.

### JVM / repository / build

```bash
./gradlew spotlessCheck --console=plain
git diff --check
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --console=plain
./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest --console=plain
```

Add a narrower new onboarding unit-test selector if the new proposal controller/store is outside `app.lawnchair.organizer.*`; record the exact successful command in the PR.

### API 36.1 connected instrumentation

Run the new onboarding class and the reused #52 surfaces on the supported API 36.1 emulator/device. Representative exact task shape:

```bash
./gradlew \
  -PandroidSerialNumber=emulator-5554 \
  connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest \
  --console=plain

./gradlew \
  -PandroidSerialNumber=emulator-5554 \
  connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest \
  --console=plain

./gradlew \
  -PandroidSerialNumber=emulator-5554 \
  connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest \
  --console=plain
```

If the final package/class name differs, use the actual implemented class and record it exactly. Required connected evidence includes:

- fresh-install eligible presentation timing;
- upgrade/restore/unknown fail-closed fixture where feasible at the chosen seam;
- skip/defer/dismiss behavior;
- Review admission and busy no-attach;
- real Back/dismiss;
- recreation/no duplicate start/no auto-confirm;
- manual/onboarding trigger separation;
- 200% font scale;
- keyboard/DPAD traversal and actionable semantics;
- existing #52 production E2E regression remaining green.

## 12. High-risk evidence and rollout

Because the implementation is `risk: layout-data`, require before merge:

1. PR includes `Closes #53`, links the accepted spec, and maps AC-001 through AC-008 to evidence.
2. PR-event CI `final-status` succeeds on the implementation head with applicable organizer source jobs/instrumentation.
3. A separate session/agent audits the implementation head after CI and records `docs/assessment/pr-<PR>-onboarding-organization.md` (or repository-standard slug) with:
   - independent Auditor;
   - audit date;
   - exact 40-character implementation Head SHA;
   - successful CI URL;
   - Criteria references to this spec and applicable accepted contracts;
   - Scope;
   - Criteria check;
   - Executed test surface with concrete commands/results;
   - Findings and substantive GO/NO-GO verdict.
4. `high-risk-gate` succeeds.
5. Any source change after the audit requires fresh CI/audit according to repository workflow; docs-only audit follow-up follows the existing mechanical exception.

Do not treat a green mechanical high-risk gate as a substitute for a substantive independent GO verdict.

## 13. Rollback

No data migration is planned.

Rollback order:

1. disable/remove the launcher proposal invocation/surface;
2. leave the stored proposal outcome inert or remove it if safe and compatibility-neutral;
3. remove onboarding route entry context;
4. revert trigger/start-admission parameterization back to the manual default only after confirming no other accepted caller depends on it.

The underlying Issue #52 workflow remains intact, so rollback does not require layout conversion or recovery-store migration.

## 14. Suggested commit sequence

After spec acceptance and explicit implementation authorization:

1. `feat(onboarding): add bounded proposal eligibility state`
2. `refactor(organizer): parameterize full-run start context`
3. `feat(onboarding): route admitted review to full run`
4. `feat(onboarding): present launcher organization proposal`
5. `test(onboarding): cover eligibility and cross-entry safety`

Keep unrelated cleanup out of the issue.

## 15. Stop conditions

Stop implementation and record the blocker if:

- fresh install cannot be reliably distinguished from upgrade/restore using an accepted/current owner signal;
- launcher interactive/workspace readiness cannot be observed without an arbitrary delay;
- onboarding requires different planning/apply/recovery semantics from Issue #52;
- a lifecycle requirement would need persisted write confirmation;
- a new Launcher DB schema, organizer-state migration, permission, external dependency, or network transport becomes necessary;
- diagnostics correlation cannot satisfy `ONBOARDING_PROPOSAL` without changing the accepted diagnostics contract;
- the Review action cannot distinguish fresh-run admission from an already-active shared coordinator without broadening the #52 contract beyond the typed start outcome;
- an active parallel PR owns the same organizer/onboarding/navigation seam without coordination.

## 16. Approval gate

This plan remains **Draft** because the paired spec is **Proposed — review-ready**. It is a planning artifact only. Production implementation starts only after the project performs the explicit spec-approval step and freezes the acceptance criteria.
