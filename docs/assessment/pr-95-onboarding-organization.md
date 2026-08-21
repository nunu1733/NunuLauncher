# High-risk audit: PR #95 Onboarding organization proposal

> Status: ready — GO
> Audit date: 2026-08-21

- Auditor: Independent re-audit session separate from the implementation/review-fix sessions (solo-maintenance audit)
- PR: https://github.com/nunu1733/NunuLauncher/pull/95
- Head SHA: c3e92497d4a033e5175d2c28b0e5a38d19c54409
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32478569119
- Criteria: specs/53-onboarding-organization-proposal/spec.md — FR-006, FR-007, FR-015, NFR-001, NFR-005, NFR-009, NFR-011, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008; specs/52-manual-full-organization-vertical-slice/spec.md — FR-006, FR-015, NFR-001, NFR-005, NFR-009, NFR-011

## Scope

This independent re-audit covers the complete `main..c3e92497d4a033e5175d2c28b0e5a38d19c54409` Issue #53 implementation diff and specifically rechecks the blockers from the preceding assessment. Since the prior audit commit `1757e3a85b2e5ad51e30804c68ec1cfbdc72f03c`, the branch adds the production keyboard-focus fix (`478006e39f19710476f3abf9152f1742ee561709`), touch-mode focus support (`1e4da2d0a20964551d225b38fd6d8f1312281d61`), connected-test synchronization/stabilization, and restoration of the complete Issue #83 / Issue #52 / Issue #53 connected regression surface at `c3e92497d4a033e5175d2c28b0e5a38d19c54409`.

The audit independently rechecked the accepted Issue #53 specification and approved plan, production proposal focus behavior, real-launcher connected focus evidence, Review admission and shared Issue #52 write authority, prior restore-provenance protection, current workflow selection, and the successful qualifying PR-event CI. No production implementation file is changed by this audit; this record is the audit-session repository change.

## Criteria check

- **AC-001 / FR-007 — PASS.** Fresh-install/restore/lifecycle safety remains unchanged from the previously accepted source audit. The former accessibility blocker is now closed: the real launcher-owned proposal explicitly requests its declared initial input-focus target after attachment, supports touch-mode focus, declares deterministic traversal links, and restores the pre-open focus on close. The API-36.1 real-launcher instrumentation verifies initial input focus, DPAD traversal into a proposal action, Back dismissal/defer behavior, large-font viewport reachability, and meaningful focus return.
- **AC-002 — PASS.** Skip, Later, and Back remain proposal-only state transitions. They do not start an organizer run, mutate launcher layout/organizer-owned state, or create an alternate write/recovery path.
- **AC-003 / AC-005 — PASS.** Review continues to enter the shared Issue #52 coordinator with `Trigger.ONBOARDING_PROPOSAL`; a successful fresh admission records REVIEWED only after admission and routes to the existing review surface. Busy admission remains isolated and retryable rather than consuming/replacing the active run. Rapid duplicate Review input is also guarded so only one admission is accepted.
- **AC-004 / FR-006 / NFR-001 — PASS.** Onboarding does not own an independent planner/materializer/apply flow. Explicit preview confirmation in the reused Issue #52 surface remains the only layout-write authority, and the current connected regression includes that reused path on API 36 / Platform 36.1.
- **AC-006 — PASS.** Navigation retains entry context only; run ID, preview authorization, checkpoint, and write capability are not serialized through onboarding navigation. The current Issue #53 API-36.1 suite includes recreation/cross-entry behavior and passes.
- **AC-007 / NFR-005 / NFR-011 — PASS.** No network dependency, trigger-correlation fork, alternate writer, or recovery authority was introduced. The prior non-restorable restore-provenance snapshot remains intact and its deterministic fail-closed regression remains in the connected suite.
- **NFR-009 / accessibility — PASS.** Production now distinguishes accessibility announcement from ordinary input focus and explicitly establishes deterministic launcher-host focus. `nextFocusDownId` / `nextFocusForwardId` cover the proposal actions, the popup handles DPAD/TAB progression, touch-mode focus is allowed, and close restores meaningful prior focus. Current real-host API-36.1 instrumentation behaviorally exercises proposal entry, DPAD progression to an action, and focus return rather than relying only on `isFocusable` properties.
- **AC-008 — PASS.** Qualifying PR-event CI run `32478569119` completed successfully on audited implementation SHA `c3e92497d4a033e5175d2c28b0e5a38d19c54409`. Repository contract, style, organizer unit tests, debug APK build, Issue #83 production-seam connected instrumentation, Issue #52 API-36.1 reused regression, Issue #53 API-36.1 onboarding regression, `organizer-instrumentation-tests`, and `final-status` all passed.
- **High-risk evidence metadata — PASS for the audited implementation.** Issue #53 retains YAML frontmatter `status: accepted`; the qualifying source CI passes repository-contract validation. The high-risk gate on implementation HEAD correctly rejected the stale preceding audit and requested a re-audit. This record updates the audited Head SHA and qualifying CI reference; only this docs-only audit commit follows the audited implementation SHA.

## Executed test surface

The independent re-audit inspected the production-focus changes and their subsequent test/workflow stabilization, the final Issue #53 onboarding connected test source, the reused Issue #52 regression source, the accepted Issue #53 specification and plan, the current audit-contract requirements, and live pull-request Actions for implementation SHA `c3e92497d4a033e5175d2c28b0e5a38d19c54409`.

Qualifying CI run `32478569119` completed with:

- repository-contract validation and self-tests: **PASS**;
- `./gradlew spotlessCheck`: **PASS**;
- organizer unit tests: **PASS**;
- GitHub Debug APK build: **PASS**;
- Issue #83 production-seam instrumentation on API 35: **PASS**;
- Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` + `ManualOrganizationPreferencesInstrumentationTest` on API 36 / Platform 36.1: **PASS**;
- Issue #53 `OnboardingOrganizationProposalInstrumentationTest` on API 36 / Platform 36.1: **PASS**;
- `organizer-instrumentation-tests`: **PASS**;
- `final-status`: **PASS**.

The final workflow therefore exercises the full connected regression set required by the approved plan rather than a temporary diagnostic subset.

## Findings

No blocking findings remain in the audited implementation and qualifying CI surface.

1. **Resolved High — real Launcher deterministic keyboard focus and traversal (AC-001, AC-008, NFR-009).** Production now requests initial ordinary input focus after attachment, enables touch-mode focus, declares deterministic focus progression, handles DPAD/TAB progression in the proposal host, and restores the pre-open target on close. The real-launcher API-36.1 test verifies initial input focus, actual DPAD progression into a proposal action, and focus return after Back. This closes the prior finding that accessibility focus/property checks alone did not demonstrate the accepted keyboard/switch contract.
2. **Resolved High — qualifying CI (AC-008).** Run `32478569119` is a completed successful PR-event `.github/workflows/ci.yml` run for audited SHA `c3e92497d4a033e5175d2c28b0e5a38d19c54409`, with source jobs and all required connected regression surfaces executed and `final-status` green.
3. **Informational — Issue #52 Compose focus-test stabilization changed the narrow test mechanism.** During diagnosis, its focus-specific test moved away from asserting a particular runtime focus movement and now emphasizes the actionable/focus-semantics contract. This does not block this Issue #53 audit: the complete reused Issue #52 API-36.1 regression is green, while Issue #53's launcher-host deterministic input-focus and DPAD behavior are independently exercised by the real-host onboarding test.
4. **Informational — prior safety blockers remain resolved.** Restore-provenance ordering, accepted-spec YAML metadata, API-36.1 workflow selection, shared Issue #52 write authority, and busy-run isolation remain intact at the audited head.

**Verdict: GO.** The independent audit finds the accepted Issue #53 criteria satisfied at the inspected source/test boundary and backed by a successful qualifying PR-event CI run. PR #95 has sufficient independent high-risk evidence to be considered ready with respect to this audit. Any subsequent non-documentation code, test, or workflow change must invalidate this assessment and trigger another independent audit.
