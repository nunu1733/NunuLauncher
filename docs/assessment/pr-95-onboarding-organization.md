# High-risk audit: PR #95 Onboarding organization proposal

> Status: blocked — NO-GO
> Audit date: 2026-08-21

- Auditor: Independent re-audit session separate from the implementation/review-fix sessions (solo-maintenance audit)
- PR: https://github.com/nunu1733/NunuLauncher/pull/95
- Head SHA: 1ebcd1f65772715aef00121aa72127f93b5f9e1a
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32451234916
- Criteria: specs/53-onboarding-organization-proposal/spec.md — FR-006, FR-007, FR-015, NFR-001, NFR-005, NFR-009, NFR-011, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008; specs/52-manual-full-organization-vertical-slice/spec.md — FR-006, FR-015, NFR-001, NFR-005, NFR-009, NFR-011

## Scope

This independent re-audit covers the complete `main..1ebcd1f65772715aef00121aa72127f93b5f9e1a` Issue #53 implementation diff and specifically rechecks the blockers from the prior assessment. Since the prior audit commit `d9b43be6d9d707f8ff1615cf7d163832ed695d2d`, the implementation branch contains one additional non-documentation commit, `1ebcd1f65772715aef00121aa72127f93b5f9e1a` (`test(onboarding): stabilize launcher host evidence`), changing only `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`.

The audit therefore retains the previously inspected production-safety conclusions for restore provenance, proposal state, Review-to-`ONBOARDING_PROPOSAL` admission, busy-run isolation, reuse of the Issue #52 preview/confirmation/write authority, retry/recreation isolation, offline behavior, and repository metadata, while independently reviewing whether the new test stabilization closes the API-36.1 failures without weakening the accepted accessibility contract. No production implementation file is changed by this audit; this record is the audit-session repository change.

## Criteria check

- **AC-001 / FR-007 — PASS for fresh-install/restore/lifecycle safety, but accessibility portion remains blocked.** The previously audited non-restorable restore snapshot and current-lifecycle admission remain unchanged by the latest test-only commit. However AC-001 also requires accessible proposal actions, and the accepted accessibility contract requires deterministic focus and keyboard/switch access. The latest real-Launcher test no longer verifies actual input focus or DPAD traversal after presentation.
- **AC-002 — PASS at source/test boundary.** Skip/defer/Back remain proposal-only state transitions and do not start an organizer run, mutate layout/organizer-owned state, or emit organizer run-journal events.
- **AC-003 / AC-005 — PASS at inspected source boundary; current connected run is incomplete.** Review still uses the shared Issue #52 coordinator with `Trigger.ONBOARDING_PROPOSAL`, `Busy` remains isolated, and successful admission starts a fresh run before navigation. The current CI never reached the Issue #53 connected step because the preceding Issue #52 API-36.1 regression step failed.
- **AC-004 / FR-006 / NFR-001 — PASS at inspected architecture boundary; current required CI evidence is red.** Onboarding still has no independent planner/materializer/apply path; write authorization remains the reused Issue #52 explicit preview confirmation path. The required connected regression job did not pass on the current head.
- **AC-006 — PASS at inspected source boundary; current connected evidence is incomplete.** Navigation continues to retain entry context only and does not serialize run ID, preview authorization, checkpoint, or write capability. Current-head CI did not execute the subsequent Issue #53 recreation/cross-entry suite because Issue #52 connected regression failed first.
- **AC-007 / NFR-005 / NFR-011 — PASS at inspected architecture boundary.** No network dependency, alternate writer/recovery authority, or trigger-correlation fork was introduced by the latest test-only change.
- **NFR-009 / accessibility — FAIL.** The accepted specification requires meaningful/deterministic focus, keyboard/switch access, and meaningful focus return; the required QA scenario explicitly calls for deterministic focus and keyboard/switch traversal, and the approved plan requires keyboard/DPAD/switch traversal to every proposal action. Production `OrganizationOnboardingProposalView.show()` records the pre-open focus, attaches the floating view, and calls `announceAccessibilityChanges()`, while `getAccessibilityInitialFocusView()` returns the title. `AbstractFloatingView.announceAccessibilityChanges()` requests Android accessibility focus when accessibility is enabled; it does not establish ordinary keyboard/input focus. The latest real-Launcher 200%-font test removed `laterButton.requestFocus()` and the DPAD key/traversal assertion, replacing them with `isFocusable` property assertions. The isolated `PreferenceActivity` content test now waits for window focus and then explicitly forces `title.requestFocus()` / `laterButton.requestFocus()`, which proves those views can accept focus when a test grants it, but does not prove the real launcher-owned popup establishes deterministic input focus or supports real-host traversal from its actual presentation state.
- **AC-008 — FAIL.** Current PR-event CI run 32451234916 is not successful. Repository contract, style, organizer unit tests, debug APK build, and Issue #83 production-seam instrumentation passed. The Issue #52 API 36 / Platform 36.1 step failed 1 of 13 tests: `ManualOrganizationPreferencesInstrumentationTest.keyboardAndSwitchStyleTraversalReachesReviewActions` timed out after 5000 ms. Because that step failed, the Issue #53 API-36.1 connected step was skipped and `final-status` failed. Independently, the latest Issue #53 stabilization weakens the real-host keyboard/DPAD assertion described above, so a future green rerun of the current tests alone would not close the accessibility finding.
- **High-risk evidence metadata — PASS, audit freshness updated by this record.** Issue #53 retains YAML frontmatter `status: accepted`, and current `validate-repo-contract` passes. The previous high-risk gate correctly rejected the stale audit after the new test commit. This assessment updates the audited implementation SHA and CI reference and also places substantive text directly under `## Findings` so the high-risk validator does not interpret the section as empty.

## Executed test surface

The independent re-audit inspected the one-commit delta after the prior audit, the current onboarding connected test source, the production `OrganizationOnboardingProposalView` focus behavior, `AbstractFloatingView` accessibility-focus behavior, the accepted Issue #53 specification and approved plan, the current audit record contract, and live PR-event Actions for implementation SHA `1ebcd1f65772715aef00121aa72127f93b5f9e1a`.

CI run 32451234916 completed with:

- repository-contract validation and self-tests: **PASS**;
- `./gradlew spotlessCheck`: **PASS**;
- organizer unit tests: **PASS**;
- GitHub Debug APK build: **PASS**;
- Issue #83 production-seam instrumentation: **PASS**;
- Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` + `ManualOrganizationPreferencesInstrumentationTest` on API 36 / Platform 36.1: **FAIL** — 13 tests executed, 1 failure in `keyboardAndSwitchStyleTraversalReachesReviewActions` due `ComposeTimeoutException` after 5000 ms;
- Issue #53 `OnboardingOrganizationProposalInstrumentationTest` on API 36 / Platform 36.1: **SKIPPED** because the prior connected step failed;
- `organizer-instrumentation-tests`: **FAIL**;
- `final-status`: **FAIL**.

The preceding assessment had observed the same Issue #52 API-36.1 surface pass on an earlier implementation SHA, so this run alone does not establish whether the current Issue #52 timeout is deterministic or flaky. That distinction does not change the audit verdict: the current implementation SHA has no completed successful qualifying PR-event CI run, and the independent accessibility finding is separate from that run failure.

## Findings

The re-audit found two current blockers: the current-head required connected/`final-status` CI is red, and the latest test stabilization weakens the accepted real-launcher keyboard-focus evidence rather than demonstrating the required behavior.

1. **High / Blocker — real Launcher deterministic keyboard focus and traversal are not demonstrated (AC-001, AC-008, NFR-009).** The accepted spec/plan requires deterministic/meaningful focus plus keyboard/DPAD/switch traversal. Production popup opening establishes accessibility focus only through `announceAccessibilityChanges()` and does not explicitly move ordinary input focus into the proposal. The latest real-host test removes the failing input-focus request and DPAD traversal assertion and checks only `isFocusable`; the remaining isolated-content test manually grants focus in `PreferenceActivity`. This is insufficient evidence for the accepted real-host contract and can mask an actual keyboard/switch usability gap. Establish deterministic meaningful input focus in the production launcher-owned proposal (without regressing accessibility/touch behavior), retain meaningful focus restoration on close, and exercise actual real-Launcher keyboard/DPAD traversal to proposal actions on API 36.1 without relying solely on property checks or a test-forced isolated focus state.
2. **High / Blocker — current qualifying CI is not successful (AC-008).** Run 32451234916 fails `organizer-instrumentation-tests` and `final-status`. The Issue #52 API-36.1 regression step times out in `ManualOrganizationPreferencesInstrumentationTest.keyboardAndSwitchStyleTraversalReachesReviewActions`; the Issue #53 connected suite is therefore skipped. Diagnose/stabilize the connected keyboard/switch regression as appropriate and obtain a completed successful PR-event CI run on the resulting implementation head.
3. **Informational — the production-owner lifecycle stabilization is directionally reasonable but was not exercised in this run.** Explicitly relaunching HOME before waiting for the same `LawnchairLauncher` to resume removes an assumption that Back from `PreferenceActivity` alone resumes HOME. The current CI did not reach the Issue #53 suite, so this head has no execution evidence for that changed case yet.
4. **Informational — waiting for real window focus before explicit `requestFocus()` is a reasonable synchronization improvement for the isolated content test, but it is not a substitute for real-host focus ownership.** It can stabilize the isolated focus API call while leaving the production launcher popup's initial input-focus behavior unverified.
5. **Informational — prior safety blockers remain resolved.** Restore-provenance ordering, accepted-spec YAML metadata, and the API-36.1 workflow matrix remain fixed; the latest implementation delta is test-only.

**Verdict: NO-GO.** Do not mark PR #95 ready for merge on this assessment. Fix or explicitly satisfy the accepted real-launcher focus/traversal contract with behavioral API-36.1 evidence, obtain a fresh successful PR-event CI run including Issue #52 and Issue #53 connected suites plus `final-status`, and then perform another independent audit against that implementation head.