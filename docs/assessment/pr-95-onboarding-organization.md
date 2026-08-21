# High-risk audit: PR #95 Onboarding organization proposal

> Status: blocked — NO-GO
> Audit date: 2026-08-21

- Auditor: Independent re-audit session separate from the implementation/review-fix sessions (solo-maintenance audit)
- PR: https://github.com/nunu1733/NunuLauncher/pull/95
- Head SHA: d748635b3bc44becc869d58d628a2bfc48ffaa55
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32447612753
- Criteria: specs/53-onboarding-organization-proposal/spec.md — FR-006, FR-007, FR-015, NFR-001, NFR-005, NFR-009, NFR-011, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008; specs/52-manual-full-organization-vertical-slice/spec.md — FR-006, FR-015, NFR-001, NFR-005, NFR-009, NFR-011

## Scope

This re-audit covers the complete `main..d748635b3bc44becc869d58d628a2bfc48ffaa55` Issue #53 implementation diff and specifically rechecks the three blockers from the prior independent audit: restore-provenance ordering, Issue #53/API-36.1 connected evidence, and Issue #53 accepted-spec metadata. The review again focused on install/restore provenance, launcher lifecycle admission, proposal state and dismissal semantics, Review-to-`ONBOARDING_PROPOSAL` admission, busy-run isolation, navigation/recreation, the reused Issue #52 preview/confirmation/write authority, and the repository high-risk evidence contract.

No production implementation file is changed by this audit. This record is the audit-session repository change.

## Criteria check

- **AC-001 / FR-007 — PASS at inspected source boundary; prior blocker resolved.** The fix no longer depends on `LawnchairLauncher.onCreate()` beating the model loader. `LauncherBackupAgent.onRestoreFinished()` calls `RestoreDbTask.setPending()`, which now synchronously records `ORGANIZATION_PROPOSAL_RESTORE_SEEN` before launcher model loading can consume the transient restore markers. The snapshot is a `LauncherPrefs.nonRestorableItem`, therefore stored in `com.android.launcher3.device.prefs`; the repository backup scheme includes `com.android.launcher3.prefs.xml` but not the device-prefs file. The classifier treats either the transient restore markers or this durable local restore snapshot as `RESTORE`. Unit and connected test source deterministically mirrors loader-first marker consumption and remains fail-closed. The current lifecycle-state check also still prevents presentation while the launcher is no longer resumed.
- **AC-002 — PASS at source/test boundary.** Skip/defer/Back remain confined to proposal preference/process-local state and do not start an organizer run or authorize layout writes. Deferred state stays suppressed for the current process while a new process-local controller may become eligible on a later qualifying cold start.
- **AC-003 / AC-005 — PASS at source/unit boundary; connected matrix incomplete.** Review still calls the shared Issue #52 coordinator with `Trigger.ONBOARDING_PROPOSAL`; `Busy` does not consume the proposal outcome or relabel an existing operation, and `Started` records `REVIEWED` before navigation. The onboarding connected class now exercises the production proposal owner, paused/resumed presentation, Busy/Started admission, and navigation. However the approved plan also requires the reused Issue #52 review surface to be exercised on the API-36.1 connected baseline; current CI runs that class only on API 35.
- **AC-004 / FR-006 / NFR-001 — PASS at source boundary; API-36.1 regression evidence incomplete.** The onboarding action does not directly materialize/apply. The reused `ManualOrganizationPreferences` surface still checks no-write-before-confirmation and cancel/no-write behavior, but that reused surface is currently connected on API 35 rather than the plan-required API-36.1 validation baseline.
- **AC-006 — PASS at source/unit boundary; API-36.1 recreation/cross-entry evidence incomplete.** Navigation retains only entry context and fresh actions start through the shared coordinator; no run ID, preview capability, checkpoint, or write authority is serialized. The required reused-surface recreation/cross-entry connected regression is not yet run on API 36.1.
- **AC-007 / NFR-005 / NFR-009 / NFR-011 — PASS at inspected architecture boundary, subject to the connected gate below.** No network dependency or alternate apply/recovery path was introduced, diagnostics trigger separation remains in the shared run, and the onboarding connected class contains real launcher 200% font-scale, DPAD/focus, Back/defer, and restore-snapshot coverage.
- **AC-008 — FAIL.** The current CI workflow adds an API-36 onboarding step for `OnboardingOrganizationProposalInstrumentationTest`, which fixes the previous absence of Issue #53 connected execution. But the approved Issue #53 plan §11 explicitly requires **the new onboarding class and the reused Issue #52 surfaces on the supported API 36.1 emulator/device**, naming `OnboardingOrganizationProposalInstrumentationTest`, `ManualOrganizationPreferencesInstrumentationTest`, and `ManualOrganizationProductionE2EInstrumentationTest`. Current CI runs the latter two together on API 35 and runs only the onboarding class on API 36. Therefore the required API-36.1 connected matrix is still incomplete.
- **High-risk evidence metadata — PASS; prior blocker resolved.** `specs/53-onboarding-organization-proposal/spec.md` now has YAML frontmatter with `status: accepted`. On current CI, `validate-repo-contract` is green. High-risk run 32447612754 no longer reports the prior metadata error; it fails because the old audit record still pinned the superseded implementation SHA/CI and explicitly requests re-audit against the new head.

## Executed test surface

The independent re-audit inspected the current PR diff, Issue #53 accepted spec and approved plan, the restore backup/marker path, `LauncherPrefs` storage classification, the full-backup scheme, the onboarding connected test, the reused Issue #52 preferences connected test, `.github/workflows/ci.yml`, and the live PR-event Actions state.

At the re-audit cut, CI run 32447612753 on implementation SHA `d748635b3bc44becc869d58d628a2bfc48ffaa55` showed:

- repository-contract validation and self-tests: **PASS**;
- `./gradlew spotlessCheck`: **PASS**;
- organizer unit tests: **IN PROGRESS** at the audit cut;
- GitHub Debug APK build: **IN PROGRESS** at the audit cut;
- organizer instrumentation: **IN PROGRESS** at the audit cut;
- configured instrumentation surface: Issue #83 on API 35, Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` + `ManualOrganizationPreferencesInstrumentationTest` on API 35, and Issue #53 `OnboardingOrganizationProposalInstrumentationTest` on API 36.

The PR conversation contained no separate recorded API-36.1 execution of the two reused Issue #52 connected classes. Accordingly, this audit does not infer that evidence from test declarations.

The re-audit also verified the current high-risk failure (run 32447612754): the validator recognizes the high-risk path and now rejects the previous assessment because production changes followed the previously audited SHA and because its referenced CI was stale. This is the expected mechanical state before this re-audit record.

## Findings

1. **High / Blocker — API-36.1 reused Issue #52 connected evidence is still missing (AC-003, AC-004, AC-006, AC-008).** The approved plan requires the onboarding proposal class **and** `ManualOrganizationPreferencesInstrumentationTest` / `ManualOrganizationProductionE2EInstrumentationTest` on the supported API-36.1 baseline. Current CI executes only the onboarding class on API 36; the reused review/E2E classes remain API 35. Run those reused classes on the same API-36.1 connected baseline (for example by including them in the API-36 step), obtain a fresh successful PR-event `CI / final-status` for the resulting implementation head, then perform another independent audit.
2. **Informational — restore provenance race is resolved.** The new non-restorable restore snapshot is established synchronously from the backup-agent restore completion path before model-loader marker consumption, and the backup scheme excludes that device preference from restored data.
3. **Informational — Issue #53 criteria metadata blocker is resolved.** The accepted spec now exposes repository-compatible YAML frontmatter and current repository-contract validation passes.
4. **Informational — current-head CI was still running at the audit cut.** Even a later green result for run 32447612753 would not close Finding 1 because the API-36.1 class matrix is incomplete by workflow construction.

**Verdict: NO-GO.** Do not mark PR #95 ready for merge on this assessment. The remaining blocker is narrower than the prior audit: complete the approved API-36.1 connected matrix for the reused Issue #52 review/E2E surfaces, obtain a fresh current-head qualifying CI run, and re-audit that implementation head.