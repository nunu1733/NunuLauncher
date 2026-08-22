# High-risk audit: PR #102 User-authored category overrides

> Status: proposed
> Audit date: 2026-08-22

- Auditor: Pascal (independent Standards subagent) and Euler (independent Spec subagent); both were read-only, non-implementing audit agents commissioned after the implementation changes.
- PR: https://github.com/nunu1733/NunuLauncher/pull/102
- Head SHA: b1f282f1c6d70244ce42b9397120dd14fc9a0072
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32565866781
- Criteria: specs/99-user-authored-category-overrides/spec.md — AC-2, AC-3, AC-4, AC-5, AC-6, AC-6a, AC-7, AC-8, AC-9, AC-10, AC-11.

## Scope

This audit covers the Stage B implementation of Issue #99. The production diff adds a profile-scoped category-override authoring destination, the Rule Management AtomicFile-backed complete-snapshot store and legacy authority barrier, the canonical UserCache serial-to-`ProfileId` mapper shared with canonical capture, and the process-local organization-operation lease. It also wires the already-established `OrganizationInputComposer` production seam to the compatible snapshot source, preserves the #83 filtered composer-visible identity contract, excludes the private store from backup, and adds the Issue #99 connected-device evidence job.

The review specifically examined the high-risk persistence and coordination surface under `lawnchair/src/app/lawnchair/organizer/application/**`, along with the authoring/store/UI seams that can influence later fresh organization composition. It did not identify a direct layout writer, planner invocation, recovery-point creation, or layout-apply capability exposed to authoring.

## Criteria check

| Accepted criterion | Independent check | Result |
|---|---|---|
| AC-2, AC-3, AC-5 | The Home Screen preference route exposes profile-aware authoring. `CategoryOverrideAuthoringCoordinator` obtains active-v1 categories from the policy bundle; the presentation mapping is separately exhaustive. The editor distinguishes explicit `OTHER` from absent/automatic, supports set/change/remove, and now fails closed on an invalid stored category. | Pass for implementation behavior |
| AC-4 | `CanonicalProfileId.kt` is shared by the capture adapter and authoring inventory path. The connected test asserts the production capture uses that common mapper, and the coordinator re-resolves the inventory immediately before mutation. | Pass for covered behavior; the full production authoring-to-composer handoff remains only partially evidenced |
| AC-6, AC-6a, AC-7 | `CategoryOverrideStore` uses complete-snapshot AtomicFile publication and recovery-aware reads behind its access boundary. It preserves separate stored and composer-visible identities, gates mutation on durable legacy `schema = 2`, rejects uncertain migration, and the compatibility reader fails closed for unsupported/corrupt data. The typed `NoChange` and verification-visible results are retained. | Implementation checks pass; the failure-injection and concurrency evidence matrix is incomplete |
| AC-8, AC-9 | `OrganizationOperationGate` is acquired around authoring mutation while the production manual/onboarding run path uses the same singleton. The tests exercise bidirectional admission exclusion. The authoring flow is limited to S1 persistence and refresh; no planner, layout writer, recovery, or apply path is called. | Pass |
| AC-10 | The Compose destination provides localized profile/state text, semantic labels, minimum row height, cancel/save focus restoration, and 200% font-scale evidence. The revised test uses keyboard input mode, scroll semantics, and actionable controls rather than a production focus workaround. | Pass for checked cases |
| AC-11 | Focused unit tests cover codec/atomic migration-recovery, authoring set-change-remove and availability race handling, presentation coverage, operation leases, and backup exclusion. However, the full production authoring-to-AtomicFile-to-fresh-composition path and the required failure-injection/concurrency matrix are not yet proven. | Follow-up required; not complete |

## Executed test surface

The independent verification used the following commands and results on the audited worktree. The connected-device suite and merge gate are evidenced by the linked `pull_request` GitHub Actions run on the audited implementation SHA.

```bash
./gradlew --no-daemon spotlessCheck
# Result: BUILD SUCCESSFUL

git diff --check
# Result: no whitespace errors

./gradlew --no-daemon testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.*'
# Result: BUILD SUCCESSFUL

./gradlew --no-daemon compileLawnWithQuickstepGithubDebugAndroidTestKotlin
# Result: BUILD SUCCESSFUL

python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
# Result: repository contract validation succeeded

# GitHub Actions pull_request evidence
# https://github.com/nunu1733/NunuLauncher/actions/runs/32565866781
# Result: check-style, build-debug-apk, organizer-unit-tests,
#         organizer-instrumentation-issue99-tests, the other organizer
#         instrumentation jobs, validate-repo-contract, and final-status
#         all succeeded.
```

## Findings

1. The previous high-risk audit referenced an older implementation SHA and CI run. This record is a re-audit against `b1f282f1c6d70244ce42b9397120dd14fc9a0072`; the older evidence is not reused.

2. The previous implementation findings are resolved: the production DPAD workaround was removed and the test enters keyboard input mode; unsupported stored category values fail closed before authoring; a typed `NoChange` result preserves the existing generation; and the committed stored and verification-visible identities are retained separately.

3. AC-6/AC-11 evidence remains incomplete. `CategoryOverrideAtomicAccessTest` does not yet provide the full failure-injected `startWrite`/write/`finishWrite` matrix, post-finish verification-failure proof, and concurrent read/write proof required by the accepted spec.

4. AC-11 evidence remains incomplete for the fresh-run path. The current fresh-run fixture composes from a hand-written committed source; it does not yet prove the complete production sequence of authoring mutation through `AtomicFile` followed by a fresh manual/onboarding composition.

The findings above are follow-up work, not an authorization to weaken the accepted specification. The PR should remain Draft until the missing evidence is added and this independent audit is rerun. Any later non-document implementation change requires a new audit and a new successful `pull_request` CI run.
