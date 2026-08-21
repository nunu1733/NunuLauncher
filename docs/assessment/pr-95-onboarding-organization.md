# High-risk audit: PR #95 Onboarding organization proposal

> Status: blocked — NO-GO
> Audit date: 2026-08-21

- Auditor: Independent re-audit session separate from the implementation/review-fix sessions (solo-maintenance audit)
- PR: https://github.com/nunu1733/NunuLauncher/pull/95
- Head SHA: 846d8c1ddd0956fd35aaf7eb59f078c9752477eb
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32449382110
- Criteria: specs/53-onboarding-organization-proposal/spec.md — FR-006, FR-007, FR-015, NFR-001, NFR-005, NFR-009, NFR-011, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008; specs/52-manual-full-organization-vertical-slice/spec.md — FR-006, FR-015, NFR-001, NFR-005, NFR-009, NFR-011

## Scope

This independent re-audit covers the complete current Issue #53 implementation through `846d8c1ddd0956fd35aaf7eb59f078c9752477eb` and rechecks the blockers from the previous assessment: restore-provenance ordering, repository criteria metadata, and the approved API-36.1 connected-test matrix. The review inspected the implementation/source boundaries, deterministic test design, current CI workflow configuration, live pull-request Actions results, and the uploaded instrumentation failure report.

No production implementation file is changed by this audit. This record is the audit-session repository change, committed after the audited implementation SHA.

## Criteria check

- **AC-001 / FR-007 — PASS at source/test-design level; previous blocker resolved.** Restore provenance is synchronously snapshotted from the restore entry path into `ORGANIZATION_PROPOSAL_RESTORE_SEEN`, a `LauncherPrefs.nonRestorableItem`, before transient restore markers may be consumed by launcher model loading. The classifier checks restore markers/snapshot before fresh-install package-time classification and remains fail-closed for restore, upgrade, and unknown provenance. The connected source includes deterministic coverage for consuming the transient markers after the durable snapshot has been established.
- **AC-002 — PASS at source/unit boundary.** Skip, defer, and Back resolve only onboarding proposal state/process suppression. They do not admit a new organizer run or authorize layout writes.
- **AC-003 / AC-005 — PASS at source/unit boundary; connected evidence is not qualifying.** Review continues through the shared Issue #52 coordinator with `Trigger.ONBOARDING_PROPOSAL`; Busy leaves the proposal actionable and does not replace/relabel an active run; successful admission uses a fresh run. The required production-owner connected case is present but is one of the current API-36 suite failures, so this behavior does not yet have qualifying current-head connected evidence.
- **AC-004 / FR-006 / NFR-001 / NFR-005 — PASS at inspected architecture boundary.** The onboarding proposal does not directly materialize or apply layout changes. Review routes into the shared Issue #52 preview/confirmation surface, and the existing safe writer/recovery boundary remains the write authority.
- **AC-006 — PASS at source/unit boundary; connected evidence is not qualifying.** Navigation serializes onboarding entry context rather than run ID, preview capability, checkpoint, or write authority. Fresh actions re-enter through the shared coordinator. The production owner/navigation connected case currently fails before completing the required lifecycle/resume path.
- **AC-007 / NFR-009 / NFR-011 — PASS at inspected architecture boundary.** No new network dependency or alternate layout-write/recovery authority was introduced.
- **AC-008 — FAIL.** The workflow configuration now matches the approved connected matrix: Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` + `ManualOrganizationPreferencesInstrumentationTest` run on API 36 / Platform 36.1, and Issue #53 `OnboardingOrganizationProposalInstrumentationTest` also runs on API 36 / Platform 36.1. The Issue #52 matrix passed, but the required Issue #53 suite failed 3 of 8 tests. Therefore the current implementation head does not have green required connected evidence or a qualifying successful pull-request CI run.
- **High-risk evidence metadata — PASS.** `specs/53-onboarding-organization-proposal/spec.md` now carries YAML frontmatter with `status: accepted`, and repository-contract validation passes.

## Executed test surface

CI run `32449382110` on audited implementation SHA `846d8c1ddd0956fd35aaf7eb59f078c9752477eb` completed with:

- repository contract validation and validator self-tests: **PASS**;
- organizer unit tests: **PASS**;
- `./gradlew spotlessCheck`: **PASS**;
- GitHub Debug APK build: **PASS**;
- Issue #83 production-seam instrumentation, API 35: **PASS**;
- Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` + `ManualOrganizationPreferencesInstrumentationTest`, API 36 / Platform 36.1: **PASS**;
- Issue #53 `OnboardingOrganizationProposalInstrumentationTest`, API 36 / Platform 36.1: **FAIL** (8 tests, 3 failures);
- `final-status`: **FAIL**.

The uploaded `organizer-instrumentation-reports` artifact identifies these Issue #53 failures:

1. `realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale` — assertion failure at the `laterButton.requestFocus()` check.
2. `realProposalContentKeepsAllActionsReachableAtTwoHundredPercentFontScale` — assertion failure at the `title.requestFocus()` check.
3. `productionOwnerDefersBindWhilePausedThenShowsAndRoutesReviewAfterResume` — `LawnchairLauncher did not reach RESUMED after HOME launch` from `awaitResumedLauncher()` after returning from `PreferenceActivity`.

The artifact establishes the failing assertions/time-out, but this audit does not infer a single root cause from those symptoms alone. Whether the correction belongs in production behavior, test synchronization/focus setup, or both must be established by the implementation/fix session and then revalidated on the same required API-36.1 surface.

## Findings

### Resolved since the previous audit

1. **Restore provenance ordering is resolved at the inspected source boundary.** The synchronous, non-restorable restore snapshot removes the previous dependency on racing transient loader flags and has deterministic coverage in the connected test source.
2. **Issue #53 criteria metadata is repository-compatible.** The accepted spec exposes `status: accepted` in YAML frontmatter and repository-contract validation is green.
3. **The API-36.1 connected matrix configuration is now complete.** The previously missing Issue #52 API-36.1 review/E2E execution is present and passed in the audited run; the Issue #53 suite is also actually executed on API 36.

### High / Blocker — required Issue #53 API-36.1 connected suite is red

The configuration/evidence-selection gap is fixed, but the required current-head Issue #53 connected suite itself is not green: 3 of 8 tests fail, including two 200%-font-scale focusability checks and the production-owner lifecycle/navigation case. This falls directly within AC-008 and also prevents the high-risk evidence contract from accepting CI run `32449382110`, because `organizer-instrumentation-tests` and `final-status` are failed.

Required remediation: diagnose and correct the three Issue #53 API-36.1 failures without weakening the accepted accessibility/lifecycle criteria, obtain a completed successful pull-request `CI / final-status` on the resulting implementation head, then perform another independent audit against that head.

**Verdict: NO-GO.** Do not mark PR #95 ready for review or merge on this assessment.