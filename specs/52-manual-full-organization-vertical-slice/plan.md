# Implementation plan: manual full-organization vertical slice

> Issue: [#52](https://github.com/nunu1733/NunuLauncher/issues/52)
> Spec: [spec.md](./spec.md)
> Status: accepted
> Updated: 2026-08-20
> Risk: `layout-data`

## Preconditions and implementation stop conditions

This plan is executable only after [spec.md](./spec.md) is accepted. The implementation branch starts from the commit on `main` containing the accepted artifacts for Issues #12, #13/#14, #16/#67, #24, #38, #43, #44, [#83](https://github.com/nunu1733/NunuLauncher/issues/83), and [#84](https://github.com/nunu1733/NunuLauncher/issues/84). The existing Issue #52 blockers are closed at planning time, but the implementation must recheck that their accepted outputs—not merely their Issue state—are present in the target commit.

Before production code is written, the implementer must locate and test the production composition for the accepted versioned `RuleSemantics`, `TaxonomyContract`, `ClassificationSignals`, and full target membership supplied by [#83](https://github.com/nunu1733/NunuLauncher/issues/83). These values cannot be guessed, hard-coded in the UI, or reconstructed from a parallel policy. The production capture path must be able to construct the same canonical state used by application/recovery and expose the complete planner input. If no such owner/source exists, stop this issue and open the owning rule/integration follow-up. Do not add a second planner, a UI-only snapshot, or a default policy as a workaround.

Before implementing the explicit recovery preview, the implementer must also identify the accepted read-only application/recovery seam delivered by [#84](https://github.com/nunu1733/NunuLauncher/issues/84), which determines the revision-bound recovery preview/restorability information required by the UX contract without exposing `RecoveryStorePort` or recovery payloads to the UI/coordinator. If the accepted contracts provide only `recover(RecoveryRequest)` and no such inspection capability, stop this issue and open the owning application/recovery contract follow-up. Do not read the recovery store directly or add an implicit recovery-inspection contract inside Issue #52.

The implementation must also stop and open the owning contract follow-up if the required UI behavior cannot be represented by existing `PlanningResult`, `ValidatedLayoutPlan`, `ApplyResult`, or `RecoveryResult` types. In particular, do not widen a result type, introduce deletion, mutate lock state, or add an application/recovery mutation path implicitly.

## Current-code basis

| Evidence | Current state and consequence for this plan |
|---|---|
| Planner seam | `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` exposes only `plan(input: OrganizationInput): PlanningResult`. `OrganizationInput` is the canonical input and includes snapshot, rules, taxonomy, signals, target set, and run mode. Manual orchestration calls this seam; it must not duplicate planner logic. |
| Planner result | `PlanningResult` already distinguishes `Planned`, `Rejected.Invalid`, and `Rejected.Impossible`, and exposes dispositions, reasons, warnings, and unplaced items. The preview projects these typed values to localized UI text. |
| Application seam | `LayoutApplicationModule` is the production composition root for `apply(ValidatedLayoutPlan)`, `inspectRecovery(pointId)`, and recovery confirmation. Its `ReadinessGate` and application protocol already serialize, checkpoint, transact, reload, verify, reconcile, preview, and recover. UI/coordinator code must not access `favorites`, the writer, recovery store, raw revision, or `RecoveryRequest`. |
| Canonical capture and policy composition | `ProductionOrganizationInputComposer` from #83 constructs a fresh full-organization input through canonical capture, the accepted built-in policy bundle, override snapshot, platform evidence, and complete target materialization. `LayoutApplicationModule` keeps the writer private and exposes only narrow internal manual-run façade methods. |
| Apply outcome contract | `ApplyResult` already exposes `NoChanges`, `Applied`, `Rejected`, `RolledBack`, `Recovered`, `Unresolved`, `RecoveryFailed`, and `ConcurrentRun`; `RecoveryResult` exposes the matching explicit recovery outcomes. The result UI maps these existing closed variants directly. |
| Diagnostic seam | `DiagnosticsPort` is available and fail-open. `ApplyProtocol` already projects `CHECKPOINTED`, apply, verification, and automatic recovery events. `LayoutApplicationModule.apply` currently creates its own run ID, while `ApplyProtocol` accepts one; the coordinator requires a non-public correlation handoff so its `RUN_STARTED` through `USER_CONFIRMED` events share the application run ID. |
| Preference/UI seam | `PreferenceRoutes.kt`, `PreferenceNavigation.kt`, and `HomeScreenPreferences.kt` use typed Compose destinations. Issue #38's `HomeScreenPlacementLocks` and `PlacementLockPreferences` establish the nearby entry/route/screen pattern. |
| Test surfaces | Planner, application, diagnostics, and lock test infrastructure exists under `tests/unit/app/lawnchair/organizer/**`; production DB/reload/recovery instrumentation exists under `tests/organizer-instrumentation/**`. Compose UI testing dependencies were added by Issue #38 and can be reused. |

## Modules and intended paths

The exact class/file names below are planned ownership boundaries, not a mandate to create thin wrappers. Small, cohesive files may be merged when the resulting module remains deep and tests use the same public seam. The implementation must keep all platform/DB types on the integration side.

| Path | Planned change | Boundary and notes |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/integration/ManualOrganizationCaptureAdapter.kt` | New production adapter that reads current canonical launcher capture and composes `OrganizationInput` for `RunMode.FullOrganization`. | Reuse application capture/canonical conversion. It owns platform-to-domain conversion only; it does not plan, apply, or write. |
| `lawnchair/src/app/lawnchair/organizer/integration/ManualOrganizationInputSources.kt` | New/extended narrow source port for the already accepted rules, taxonomy, classification signals, and target set. | Production implementation must delegate to the accepted owner. A fake implementation is used by coordinator tests. No hard-coded policy. |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | New coordinator/state model and UI-facing port for manual capture → plan → preview → confirmation → application → result/recovery flow. | Depends on `OrganizationPlanner`, capture/input-source ports, application public types, and `DiagnosticsPort`; no Android DB type in its public model. |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationMessages.kt` | New typed mapping from planner/application/recovery result codes to string resources and UI-safe summaries. | User reasons come directly from typed results. It must not read a diagnostics journal, raw exception text, or private identifiers. |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationModule.kt` | New lazy process-wide composition holder, analogous to `OrganizerLocks`, if one is needed for the preference surface. | Construction performs no layout mutation. It obtains the planner, capture adapter, accepted input sources, application module, and diagnostics port once per process. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Narrow internal correlation overload or internal coordinator-facing method accepting a supplied `RunId`, while retaining the existing public `apply(ValidatedLayoutPlan)` behavior. | The overload delegates to the same `ApplyProtocol`; it adds no public result variant, writer access, or mutation semantics. It exists solely to keep manual pre-apply and application diagnostics correlated. |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | Add a typed manual-organization route. | Place beside Home Screen routes. |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | Register the manual-run destination. | Navigation is composable/typed; process recreation never resumes an unverified write. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Add the Home Screen `layout` group entry for manual organization. | Explicit user action only; no automatic trigger. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | New Compose screen for start, preview, details, confirmation, phase progress, result, and explicit recovery preview/confirmation. | Uses the coordinator's state/intent port. All long capture/plan/apply work runs off the main thread; UI observes state and never mutates persistence directly. |
| `lawnchair/res/values/strings.xml` | Add default-locale strings for manual entry, preview counts/reasons/warnings, confirmation, progress, result, recovery, retry, and accessibility labels. | Crowdin manages translations. Strings must not leak raw diagnostics or private data. |
| `tests/unit/app/lawnchair/organizer/ui/**` | New focused JVM coordinator, mapping, capture-projection, correlation, cancellation, and diagnostics tests. | Tests invoke the same coordinator/ports as production, using fakes rather than mocking private planner/application internals. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**` | New production integration and Compose UI/instrumentation tests. | API 36.1 device/emulator coverage for canonical capture, stale/apply/recovery flow, process recreation, real DB boundary, and accessibility semantics. |
| `DESIGN.md` | Update only if the accepted implementation adds a durable module/interface boundary that changes the target package map or system seam description. | Do not record transient progress. No ADR is expected unless a high-cost unresolved design choice is discovered. |

The following files and paths must remain outside the feature's direct mutation surface unless a separate accepted contract requires a change: `favorites` access from UI, `lawnchair/src/app/lawnchair/organizer/application/adapter/**` (except a minimal, reviewed read-only capture exposure if no existing canonical read seam can be reused), `ModelWriter.java`, `ModelDbController.java`, `LauncherProvider.java`, `DatabaseHelper.java`, `GridSizeMigrationUtil.java`, backup code, Deck runtime, lock persistence/schema, and network/telemetry configuration. Any required change in these areas elevates the review and high-risk audit scope and must be justified in the PR.

## Interface and seam decisions

### 1. Coordinator port

Create a small manual-run coordinator interface with user intents such as start, view details, confirm, cancel before checkpoint, retry, dismiss, begin recovery preview, confirm recovery, and cancel recovery. Its state is a presentation of closed planner/application/recovery results and may include a non-persisted planner artifact while the process is alive. UI tests and production Compose both drive this same coordinator port.

The coordinator depends on a manual capture/input port, `OrganizationPlanner`, an application façade restricted to accepted apply/recover/readiness operations plus any already accepted read-only recovery-inspection operation, and `DiagnosticsPort`. It does not receive a writable database, `RecoveryStorePort`, `LayoutWriterPort`, Android cursor, raw persistence manifest, or recovery payload. If no accepted recovery-inspection operation exists, implementation stops under the precondition above rather than widening this façade locally.

### 2. Canonical capture and input assembly

Use a capture port that returns the exact fields necessary to construct `OrganizationInput` and, on a planned branch, `ValidatedLayoutPlan`. The adapter joins:

- application-compatible canonical layout state/revision and complete device/profile context;
- full-organization target membership, preserving the accepted target-set policy;
- accepted versioned rules, taxonomy, and local classification signals; and
- `RunMode.FullOrganization`.

The adapter maps accepted canonical application state to planner state at one explicit integration seam. It must retain item/profile/page identity and all preservation-relevant attributes without exposing platform/SQLite types to the planner. A plan materializer then maps only the exact `OrganizationInput` plus `PlanningResult.Planned` to `ValidatedLayoutPlan`; it asserts source revision equality, complete conservation, duplicate-free actions, matching profile/lock context, and the v1 no-delete rule before calling the application module.

### 3. Correlated diagnostics without a second protocol

`ManualOrganizationRun` obtains a single `RunId` before capture and emits `RUN_STARTED` with `MANUAL_FULL`/`FULL_ORGANIZATION`, then `CAPTURED`, planning terminal/projection event, `PREVIEWED`, and `USER_CONFIRMED` or `USER_CANCELLED` through `DiagnosticsPort`. The new internal application call carries that ID into the **existing** `ApplyProtocol`, which already emits checkpoint/application/verification/recovery events. The runner never manually duplicates application-stage projections.

The coordinator catches diagnostic failures and continues safely. Journal/projected events contain only contract-approved opaque identifiers, closed codes, versions, dimensions, and count summaries; test fixtures assert non-containment of prohibited content. Explicit recovery uses the existing recovery projection and point correlation rather than starting a second organization run.

### 4. Confirmation and execution semantics

The preview stores no durable write authorization. Confirm invokes the materializer and application seam once for the exact current capture. `Rejected(STALE_REVISION)` or `Rejected(EXACT_PRECONDITION_FAILED)` invalidates preview state and routes to a fresh capture. A no-change result never enters the checkpoint/apply path.

Before checkpoint, a cancel intent terminates the coordinator with no write and emits `USER_CANCELLED`. After the application call begins, the UI may be dismissed or leave the screen, but the coordinator does not add a cancellation side channel to the atomic application protocol. It renders the final typed result when available, and process restart relies on application reconciliation before accepting subsequent actions.

After process recreation, the coordinator must derive user-visible state only from accepted application reconciliation/results and a newly validated capture/inspection where required. It must not reconstruct success counts, warnings, restorable state, or write authorization from `SavedState`, a diagnostics journal, or an expired in-memory planner artifact. If the exact prior presentation summary is no longer available from accepted seams, show only the safely established reconciliation outcome and require a fresh capture/inspection for any subsequent action; never infer a previous success or recovery availability.

### 5. Recovery surface

On `Applied`, the UI offers an explicit recovery action for the returned point only while accepted read-only `inspectRecovery` reports the point as previewable/restorable for the newly captured current revision. That inspection supplies only the typed effect/restorability information required by the UX contract; the UI/coordinator never reads `RecoveryStorePort`, a recovery payload, a raw revision, or a `RecoveryRequest`. After explicit confirmation, the coordinator passes the opaque one-shot capability to `LayoutApplicationModule.confirmRecoveryPreview`; the application module creates the private request and reuses the existing recovery behavior. It maps every `RecoveryResult` variant directly. If the required read-only inspection seam is absent, implementation stops and files the owning contract follow-up rather than substituting a generic point-ID preview or a raw store read.

## Implementation sequence

1. **Confirm the target commit, input-source ownership, and recovery-inspection seam.** Re-read Issue #52 and its comments, this accepted spec, the accepted planner/application/diagnostics/empty-folder/lock artifacts, and `git status`. Identify the production owner of rules, taxonomy, signals, and target membership, and verify that an accepted read-only recovery inspection can support the revision-bound preview without exposing recovery storage. If either owner/seam is absent or not accepted, create the blocking follow-up and stop.

2. **Write seam-level tests before UI implementation.** Add fake input composition, fake planner, fake application façade, and recording diagnostics fixtures. Test the state-machine table below before adding production wiring or Compose. Include a test that the same `RunId` flows from manual start through the application protocol event projections.

3. **Implement canonical capture and materialization.** Add the manual capture/input adapter and `ValidatedLayoutPlan` materializer. Reuse the canonical state/revision path; test profile/device/lock/page/item preservation, full target membership, no planner Android types, and no v1 delete action. Use the accepted source owner for rules/taxonomy/signals rather than encoding policy in the adapter.

4. **Implement the coordinator and diagnostic correlation handoff.** Add the narrow internal application correlation method, then implement fresh-run capture, planner invocation, preview projection, typed planning failure handling, confirmation, no-change behavior, stale invalidation, application result mapping, and recovery-preview/recovery-result intents. Keep application stage execution entirely inside the existing module.

5. **Add Compose navigation and screen UI.** Add route, destination registration, Home Screen entry, screen state rendering, detail expansion, confirmation dialogs, progress announcements, result/retry/exit/recovery actions, and localized strings. Ensure all start/confirm/recovery writes originate only from explicit click callbacks.

6. **Complete accessibility and recreation behavior.** Add semantics/content descriptions, deterministic focus restoration, keyboard/switch traversal, large-font reflow, non-color-only warnings, progress announcements, and UI state loss/recreation behavior. Ensure no saved plan can be replayed without current revision validation, that all restart behavior goes through existing application reconciliation, and that recreated UI displays only state that can be established from accepted result/reconciliation/capture/inspection seams.

7. **Run the layered verification matrix.** Execute formatting, repository-contract checks, organizer JVM tests, debug APK build, targeted instrumentation, and full appropriate CI. Capture UI screenshots/video for the PR. Because the PR carries `risk: layout-data`, arrange an independent audit after the final implementation commit and successful PR-triggered CI; do not modify code after audit without repeating it.

## State and outcome test matrix

| Test ID | Driver and scenario | Expected observation |
|---|---|---|
| RUN-01 | Explicit start with valid non-empty fixture | `RUN_STARTED` → capture → plan → preview; no persistence write before confirmation. |
| RUN-02 | Valid plan, confirm, verified apply | Shared run ID, application protocol events, verified success summary, recovery action available. |
| RUN-03 | Invalid planner result / impossible result | Typed reason/count preview/result, no confirm, no write. |
| RUN-04 | Empty diff | `NoChanges`; zero recovery/Launcher writes and zero reload. |
| RUN-05 | Cancel in capture/planning/preview or before checkpoint | `USER_CANCELLED` where preview exists, zero write, retry performs new capture. |
| RUN-06 | Change revision/profile/device/lock/page/widget/folder/app-pair after preview | Stale/exact-precondition result, old plan discarded, no mutation, fresh capture required. |
| RUN-07 | Checkpoint create/validate/store failure | Typed non-apply result, no Launcher mutation, usable earlier recovery points unaffected. |
| RUN-08 | Writer busy/concurrent run | Typed busy/concurrent result with no checkpoint/write; later retry is possible. |
| RUN-09 | Nth write failure / transaction-close ambiguity | `RolledBack` for confirmed pre-state; otherwise application recovery result is mapped without false success. |
| RUN-10 | Reload/verification failure | Existing automatic recovery result appears as recovered, unresolved, or recovery-failed—not success. |
| RUN-11 | Explicit recovery with matching revision | Accepted read-only inspection produces the revision-bound preview; explicit confirm submits `RecoveryRequest` → `Restored`, with no UI/store read shortcut. |
| RUN-12 | Explicit recovery stale/expired/corrupt/busy/failure | Distinct inspection/recovery result, no blind write, safe next action. |
| RUN-13 | Process recreation at preview, checkpoint, commit, and verification boundaries | No blind replay; reconciliation precedes new operation; UI is rebuilt only from accepted reconciliation/result plus fresh capture/inspection where needed, with no inferred success/counts/recovery availability. |
| RUN-14 | Empty folder, lock, unknown lock, widget, app pair, unavailable profile, unplaced fixture | Typed accepted preservation/rejection; no lock change or delete control. |
| RUN-15 | Diagnostics journal/logger failure and negative data corpus | Run continues; events stay privacy-safe and contain no prohibited values. |
| RUN-16 | TalkBack, 200% font scale, keyboard/switch, focus, warnings, progress | All actions are reachable/announced; no color-only meaning, clipping, or modal trap. |

## Detailed test allocation

| Layer | Tests and evidence |
|---|---|
| JVM coordinator/unit | `ManualOrganizationRunTest`: state transitions, fresh retry, cancellation, stale invalidation, no change, result mapping, explicit recovery request, recovery preview through the accepted read-only seam, and recreation fallback without inferred state. `ManualOrganizationCaptureAdapterTest`: canonical input assembly, target/profile/device/lock preservation, rule-source absence failure. `ManualOrganizationPlanMaterializerTest`: conservation, exact revision/state, no delete, folder/page/action integrity. `ManualOrganizationDiagnosticsTest`: phase order/run-ID correlation and fail-open/non-containment. `ManualOrganizationMessagesTest`: typed mappings without journal dependence. |
| Existing application protocol | Extend only through public/internal application seam tests: supplied run-ID reaches `ApplyProtocol`; existing checkpoint/A0–A8/failure behavior remains unchanged. Any accepted recovery-inspection seam is tested as read-only and must not expose store/payload access to UI code. No test calls a new UI-side writer path. |
| Compose UI | Screen tests for explicit entry, preview counts/reasons/warnings/unplaced and preserved empty folder state, confirmation/cancel, no-changes, each result family, recovery preview, content descriptions, focus restoration, keyboard/switch traversal, progress announcement behavior, non-color-only warnings, and 200% font-scale reflow. |
| Instrumentation | Real Launcher DB capture → planner → application E2E; stale mutation before confirmation/A5; checkpoint/write/reload/verification failure injection; recovery round trip; profile/device/grid context; process recreation/reconciliation; API 36.1 accessibility smoke. Restore fixture DB state after each case. |
| Repository and CI | Contract validator, formatter, organizer JVM gate, debug build, targeted connected instrumentation, and PR `CI / final-status`. Include screenshots/video for UI changes and exact local command/results in the PR. |

## Migration, rollback, and compatibility

No database schema, recovery format, lock storage, rules format, permission, network, or telemetry migration is introduced. The manual UI has no independent persistence. Any resulting layout mutation is exclusively the existing, transactionally protected application operation with recovery point, rollback, verification, retention, and restart reconciliation.

If a released version is reverted, remove the manual entry/route/coordinator/screen while leaving current layout and valid recovery records under existing application ownership. Never use an APK rollback, raw file copy, or Deck behavior as a data-recovery strategy. Existing Deck runtime behavior is not reused or changed by this feature.

## Verification commands

Run the following from a clean checkout after `git submodule update --init --recursive`. Record the exact command, commit, environment, and result in the PR; do not report commands that were not executed.

```bash
# Repository and formatting gates
git submodule update --init --recursive
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew spotlessCheck

# Organizer unit gate (the same package surface used by CI)
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'

# Debug APK and Android-test artifact build
./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest

# API 36.1 emulator/device — replace the serial with the verified test device
./gradlew -PandroidSerialNumber=<serial> \
  connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.lawnchair.organizer
```

The connected execution is local evidence. The high-risk merge gate additionally requires a successful **PR-triggered** `CI / final-status` run on the audited head commit, including non-skipped `organizer-unit-tests`, `check-style`, and `build-debug-apk` source jobs.

## High-risk evidence and rollout

This implementation closes a `risk: layout-data` Issue and may touch the organizer application protocol. Before merge, it therefore requires the independent-evidence process in [GitHub workflow](../../docs/project/github-workflow.md):

1. The PR carries `risk: layout-data`, links this accepted spec, maps implementation evidence to `MFO-AC-01` through `MFO-AC-11`, and contains `Closes #52`.
2. The final implementation commit receives a successful PR-event `CI / final-status` run with the required source jobs.
3. A separate session/agent performs the audit after CI succeeds and adds `docs/assessment/pr-<PR-number>-manual-full-organization.md`. The record uses the repository template and includes independent Auditor, audit date, exact 40-character Head SHA, CI URL, `Criteria:` references to this accepted spec/requirement IDs (and applicable accepted spec/ADR criteria), Scope, Criteria check, Executed test surface with concrete commands, and Findings.
4. If source code changes after the audit, repeat CI/audit for the new head. Docs-only updates after the audited head are allowed only as defined by the mechanical gate.

The PR includes UI screenshots/video for the manual start, preview, confirmation, success, stale, and recovery/failure surfaces. On merge, update `spec.md` and this plan to `implemented` only when the Issue exit criteria, accepted-spec criteria, CI, instrumentation evidence, and independent audit are complete.

## References

- [Issue #52](https://github.com/nunu1733/NunuLauncher/issues/52)
- [Issue #52 specification](./spec.md)
- [AGENTS.md](../../AGENTS.md)
- [DESIGN.md](../../DESIGN.md)
- [Spec 12: deterministic planner](../12-deterministic-full-layout-planner-v1/spec.md)
- [Spec 13: safe application and recovery](../13-safe-layout-application/spec.md)
- [Spec 24: empty-folder policy](../24-empty-folder-policy/spec.md)
- [Spec 38: lock authoring/review](../38-lock-authoring-unknown-review/spec.md)
- [Organization run UX contract](../../docs/product/organization-run-ux.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
- [GitHub Issue/Spec/PR workflow](../../docs/project/github-workflow.md)
