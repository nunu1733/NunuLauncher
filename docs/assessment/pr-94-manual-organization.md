# High-risk audit: PR #94 manual full-organization vertical slice

> Status: accepted
> Audit date: 2026-08-20

- Auditor: Independent audit session, separate from the implementation session; no implementation changes were made by this auditor.
- PR: https://github.com/nunu1733/NunuLauncher/pull/94
- Head SHA: b45b044c82149d3926121d3c5f782711cde3d7af
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32357487332
- Criteria: specs/52-manual-full-organization-vertical-slice/spec.md FR-002, FR-003, FR-004, FR-005, FR-006, FR-015, NFR-001, NFR-002, NFR-005, NFR-007, NFR-009, NFR-011

## Scope

This audit covers PR #94 against base `main` and the exact implementation head `b45b044c82149d3926121d3c5f782711cde3d7af`. The PR is labeled `risk: layout-data`. I reviewed `AGENTS.md`, `docs/project/github-workflow.md`, the accepted Issue #52 specification and implementation plan, the relevant organizer design/ADR and diagnostics documents, Issue #52 and all five Issue comments, PR #94 metadata/body, the PR review state, and the complete current diff.

The current diff is 109 files and includes the manual organization UI/coordinator, application materialization and recovery-preview boundaries, diagnostics integration, tests, strings, and the Issue #52 spec/plan. Since the previous audit record, the current head adds only the first-frame focus scheduling change in `ManualOrganizationPreferences`, lifecycle-stable Compose instrumentation setup, and the corresponding plan evidence. The earlier audit's old-head and “adb unavailable” statements are not treated as current evidence.

The reviewed write path is `ManualOrganizationRun` → `ManualOrganizationApplication` → `LayoutApplicationModule.applyWithRunId` → the existing application protocol. Recovery is `inspectRecovery` → opaque `RecoveryPreviewConfirmation` → application-owned confirmation and private `RecoveryRequest` construction. I found no UI-side `favorites` write, `ModelWriter` use, raw `RecoveryStorePort` access, raw recovery-record access, or caller-constructed `RecoveryRequest` in the current PR diff. No schema, permission, network, telemetry, or Deck-runtime mutation is in scope.

## Criteria check

- **MFO-AC-01 / FR-006:** **Partial.** Source review, coordinator tests, Compose tests, and the independent API 36.1 targeted UI run cover explicit preview, confirmation/cancellation behavior, focus, font scale, safe-terminal handling, and no-write fixture behavior. The targeted class uses a fake application/coordinator fixture; it is not the required real Launcher DB → planner → application E2E test. The PR-triggered CI instrumentation job is the existing Issue #83 production-seam surface, not an Issue #52 manual-flow E2E surface.
- **MFO-AC-02 / FR-002, NFR-001:** **Pass by static/source review; runtime evidence partial.** The current path consumes the accepted production input composer and existing planner/application seams. No UI-local policy, second planner, or direct UI database access was found. The manual production capture path was not exercised independently on a real Launcher layout.
- **MFO-AC-03 / FR-003:** **Pass for the reviewed coordinator/UI seam; integration evidence partial.** Unit counters and the Compose fixture cover empty/no-write behavior. A real Launcher DB/model-reload zero-write execution was not run in this audit.
- **MFO-AC-04 / FR-004:** **Partial.** Current unit tests cover stale materialization, cancellation before admission, dismissal before admission, and admitted-application dismissal/cancel behavior. The API 36.1 target covers preview cancellation, but no real Back/stale mutation instrumentation or DB-level race was run.
- **MFO-AC-05 / FR-005:** **Partial.** Unit tests retain and map all application result families and the UI has distinct safe-terminal messages. Application fault injection, recovery round-trip, and post-apply verification were not independently executed through a test database/device.
- **MFO-AC-06 / NFR-002:** **Pass by static/source review; negative integration evidence partial.** The materializer emits preserve/update/insert actions, the manual UI does not expose deletion or lock mutation, and the application boundary remains authoritative. No independent device/test-DB negative run for lock/profile/deletion invariants was executed here.
- **MFO-AC-07 / FR-002, FR-003, NFR-007:** **Partial/pass by fixture and source review.** Scope, counts, typed reasons, warnings, unplaced state, and constraints are projected without developer diagnostics and are exercised by the Compose fixture. The complete real-layout/profile/folder/widget matrix and device rendering were not independently verified.
- **MFO-AC-08 / FR-015, NFR-011:** **Partial/pass by source and JVM evidence.** The coordinator uses one run ID and the closed diagnostics port with fail-open emission. Independent runtime journal output, logger-failure execution, and privacy-negative evidence were not produced in this audit.
- **MFO-AC-09 / NFR-005:** **Not demonstrated.** The current `recompositionRetainsPreviewWithoutReplayingWrite` test is same-process recomposition with a retained in-memory coordinator. It is not process death/restart plus application lifecycle reconciliation, and no connected process-recreation test was run.
- **MFO-AC-10 / NFR-009:** **Partial.** The current API 36.1 run passed the six Compose tests covering preview/cancellation, focus restoration, 200% font scale, safe-terminal disposal/recreation fixture behavior, and recomposition/no-write behavior. TalkBack, keyboard/switch traversal, real Back navigation, full-surface clipping/reflow, and phase announcement behavior were not independently executed.
- **MFO-AC-11 / all listed FR/NFR:** **Partial; not satisfied for merge.** Current-head PR-triggered CI run #188 completed successfully, including `final-status`, `organizer-unit-tests`, `check-style`, and `build-debug-apk`. The current-head High-risk gate run #121 remains red because the prior audit record did not cover the code changes through `b45b044`. The local API 36.1 target passed, but the high-risk gate still requires this updated audit record to be committed and available to GitHub before this can become a PASS.

## Executed test surface

The following checks were independently executed or independently verified for the current head.

- `git status --short --branch && git rev-parse HEAD` — clean worktree before audit edits; HEAD was `b45b044c82149d3926121d3c5f782711cde3d7af`.
- `git diff --check main...b45b044c82149d3926121d3c5f782711cde3d7af` — passed with no whitespace errors.
- `ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools ./gradlew -PandroidSerialNumber=emulator-5554 connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest --console=plain` — passed; `BUILD SUCCESSFUL`, 6 tests finished, 0 skipped, 0 failures on `nunu_qpr2_api36_1(AVD) - 16`.
- API device evidence — `/opt/homebrew/share/android-commandlinetools/platform-tools/adb -s emulator-5554 shell getprop ro.build.version.sdk` returned `36`; the AVD name was `nunu_qpr2_api36_1`, using the provisioned Android SDK Platform 36.1 / Build Tools 36.1.0 / platform-tools 37.0.1 environment.
- GitHub API workflow inspection — PR-triggered CI run `32357487332` / run #188 was verified against commit `b45b044c82149d3926121d3c5f782711cde3d7af` and completed successfully. `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and the separate Issue #83 instrumentation job all completed successfully.
- GitHub API high-risk inspection — High-risk gate run `32357487454` / run #121 failed because the previous audit Head SHA preceded current code changes in `ManualOrganizationPreferences.kt`, `plan.md`, and `ManualOrganizationPreferencesInstrumentationTest.kt`; the log explicitly requested re-audit against the new head.

## Findings

1. **Merge-gate blocker — current-head High-risk gate is red while CI #188 is green.** Run #121 correctly rejected the previous audit as stale after the implementation/test changes through `b45b044`. This local record updates the Head SHA, but because this request explicitly does not commit or push, GitHub cannot yet consume this updated audit file or rerun the gate.
2. **Release-evidence blocker — the current API 36.1 pass is targeted Compose fixture evidence, not full Issue #52 E2E evidence.** It does not prove real Launcher DB capture/application/recovery, stale mutation, fault injection, or model reconciliation.
3. **Release-evidence blocker — process recreation and lifecycle reconciliation remain unverified.** The current recomposition test is intentionally not process death; no connected restart/reconciliation test was executed.
4. **Release-evidence limitation — real Back, keyboard/switch traversal, TalkBack, complete accessibility announcements, and requested UI screenshot/video evidence were not independently verified.** The API 36.1 six-test pass covers only the recorded Compose fixture surface.
5. **Repository state limitation — PR #94 remains draft and Issue #52 remains open with `status: blocked`.** Issue #84 itself is closed and merged, but Issue #52's own completion checklist and implementation/spec status have not been closed. This audit does not infer issue completion from the PR body.

No new production safety defect was asserted from the `b45b044` first-frame focus scheduling change or the stabilized Compose test setup. Those changes explain the current API 36.1 pass, but they do not replace the remaining release-gate evidence.

## Verdict

**NO-GO / not merge-ready.** The current-head PR-triggered CI run #188 is green and the API 36.1 targeted Compose test passes 6/6. The reviewed architecture remains within the accepted planner/application/recovery boundaries. The verdict is nevertheless NO-GO because the current High-risk gate #121 is failed, the updated audit is not yet committed/pushed by explicit instruction, and the accepted Issue #52 E2E, process-recreation, real-navigation/accessibility, and complete release-evidence surfaces are not independently demonstrated.
