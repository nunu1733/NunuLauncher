# High-risk audit: PR #102 User-authored category overrides

> Status: accepted
> Audit date: 2026-08-22

- Auditor: **Independent verification session**. This audit was performed in a separate verification session after the implementation commits and independently re-checked the final code diff, accepted specification, test surfaces, and GitHub Actions evidence.
- PR: https://github.com/nunu1733/NunuLauncher/pull/102
- Head SHA: 455bdcb04a2c6cba839b3b4c0328dcd91f4a3aad
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32552491471
- Criteria: specs/99-user-authored-category-overrides/spec.md — AC-2, AC-3, AC-4, AC-5, AC-6, AC-6a, AC-7, AC-8, AC-9, AC-10, AC-11.

## Scope

This audit covers the Stage B implementation of Issue #99. The production diff adds a profile-scoped category-override authoring destination, the Rule Management AtomicFile-backed complete-snapshot store and legacy authority barrier, the canonical UserCache serial-to-`ProfileId` mapper shared with canonical capture, and the process-local organization-operation lease. It also wires the already-established `OrganizationInputComposer` production seam to the compatible snapshot source, preserves the #83 filtered composer-visible identity contract, excludes the private store from backup, and adds the Issue #99 connected-device evidence job.

The review specifically examined the high-risk persistence and coordination surface under `lawnchair/src/app/lawnchair/organizer/application/**`, along with the authoring/store/UI seams that can influence later fresh organization composition. It did not identify a direct layout writer, planner invocation, recovery-point creation, or layout-apply capability exposed to authoring.

## Criteria check

| Accepted criterion | Independent check | Result |
|---|---|---|
| AC-2, AC-3, AC-5 | The Home Screen preference route exposes profile-aware authoring. `CategoryOverrideAuthoringCoordinator` obtains active-v1 categories from the policy bundle; the presentation mapping is separately exhaustive. The editor distinguishes explicit `OTHER` from absent/automatic and supports set, change, and remove. | Pass |
| AC-4 | `CanonicalProfileId.kt` is shared by the capture adapter and authoring inventory path. The connected test asserts the production capture uses that common mapper, and the coordinator re-resolves the inventory immediately before mutation. | Pass |
| AC-6, AC-6a, AC-7 | `CategoryOverrideStore` uses complete-snapshot AtomicFile publication and recovery-aware reads behind its access boundary. It preserves separate stored and composer-visible identities, gates mutation on durable legacy `schema = 2`, rejects uncertain migration, and the compatibility reader fails closed for unsupported/corrupt data. | Pass |
| AC-8, AC-9 | `OrganizationOperationGate` is acquired around authoring mutation while the production manual/onboarding run path uses the same singleton. The tests exercise bidirectional admission exclusion. The authoring flow is limited to S1 persistence and refresh; no planner, layout writer, recovery, or apply path is called. | Pass |
| AC-10 | The Compose destination provides localized profile/state text, semantic labels, minimum row height, cancel/save focus restoration, and 200% font-scale evidence. The revised test scrolls the LazyColumn through its semantics before activating off-screen editor actions, rather than depending on touch injection or a clipped control. | Pass |
| AC-11 | Focused unit tests cover codec/atomic migration-recovery, authoring set-change-remove and availability race handling, presentation coverage, operation leases, fresh-run composer consumption, and backup exclusion. Connected tests cover production mapper equivalence and the profile/accessibility/focus matrix. The CI run linked above executes the Issue #99 connected suite, the existing organizer suites, build, style, contract checks, and `final-status`. | Pass |

## Executed test surface

The independent verification used the following commands and results on the audited worktree. The connected-device suite and merge gate were executed by the linked `pull_request` GitHub Actions run on the audited SHA.

```bash
./gradlew spotlessApply spotlessCheck
# Result: BUILD SUCCESSFUL

git diff --check
# Result: no whitespace errors

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# Result: GitHub Actions job organizer-unit-tests succeeded

./gradlew assembleLawnWithQuickstepGithubDebug
# Result: GitHub Actions job build-debug-apk succeeded

python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
# Result: GitHub Actions job validate-repo-contract succeeded

# GitHub Actions pull_request evidence
# https://github.com/nunu1733/NunuLauncher/actions/runs/32552491471
# Result: organizer-instrumentation-issue99-tests, API 35, Issue 52, Issue 53,
#         organizer-unit-tests, check-style, build-debug-apk,
#         validate-repo-contract, and final-status all succeeded.
```

## Findings

No blocking finding was identified. The latest verification fixed two test-only risks discovered by the dedicated CI path: unsupported collection API usage and use of text-node/touch interactions for controls that are profile-disambiguated or initially outside the 200% font-scale viewport. The final test targets actionable semantic rows and uses `performScrollToNode` on the `PreferenceLazyColumn` scroll semantics before operating the editor controls.

The audit SHA is the implementation head. Any later non-document code change requires a new independent audit and a new successful `pull_request` CI run before merge. The docs-only audit commit that follows this record is permitted by the high-risk gate rule because it does not modify the audited implementation surface.
