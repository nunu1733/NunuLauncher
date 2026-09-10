# High-risk audit: PR #274 fix: preserve folder-child representability across workspace moves

> Status: corrective action required
> Audit date: 2026-09-10

- Auditor: independent audit session, separate from the implementation session
- PR: https://github.com/nunu1733/NunuLauncher/pull/274
- Head SHA: 08b1afe67d3aeff8d6b6b8ca1014e8da74afa932
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34462108832
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-01, `specs/269-folder-workspace-representability/spec.md` AC-269-02, `specs/269-folder-workspace-representability/spec.md` AC-269-03, `specs/269-folder-workspace-representability/spec.md` AC-269-04

## Scope

This follow-up audit covers the complete PR through head
`08b1afe67d3aeff8d6b6b8ca1014e8da74afa932`, including the previously audited
production writer bridge and the follow-up commit that addresses the earlier
audit findings. The follow-up implementation/test and evidence-tooling paths
are:

- `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt`
- `tools/repo-contract/validate_high_risk_evidence.py`
- `tools/repo-contract/test_validate_high_risk_evidence.py`

The production path was rechecked at `src/com/android/launcher3/model/ModelWriter.java`:
desktop entry for an existing `WorkspaceItemInfo` sets in-memory and pending
database spans to `1×1` before notification and enqueueing. The strict
desktop capture boundary remains in `RowManifestCodec`; it does not invent a
span for malformed rows. The follow-up does not change production code,
schema, migration, recovery format, planner semantics, or workflow files.

## Criteria check

- **AC-269-01 — implementation present; execution evidence incomplete.** The real-writer test still covers direct desktop entry, NULL and positive non-`1×1` legacy spans through a Hotseat intermediate, and an AppPair-like source container. The destination-based `ModelWriter` rule is consistent with the criterion. However, `Issue265ManualEditRecoveryInstrumentationTest` is not selected by any current `.github/workflows/ci.yml` instrumentation command, and no local connected emulator was available, so the writer-transition assertions were compiled and inspected but not independently executed in this audit.
- **AC-269-02 — implementation oracle corrected; execution evidence incomplete.** `pathA_manualEditBeforeRestore` now compares the entire `beforeOrganize.manifest` with the entire post-restore manifest, and retains exact `Restorable → Restored` assertions for the first recovery point. The class compiles, but the current CI run does not invoke this class; therefore its API 36.1 execution is not independently evidenced here.
- **AC-269-03 — implementation oracle present; execution evidence incomplete.** `pathC_manualEditThenSecondOrganize` still requires a verified second apply, reload/capture, and exact raw-manifest equality after the repeated organize. As with AC-269-02, the class is not in the current CI command selection and could not be run locally without `adb`.
- **AC-269-04 — focused capture fixture met; control-path execution evidence incomplete.** `RealAdapterRowMatrixInstrumentationTest.desktopApplicationWithNullSpanIsRejectedByCanonicalCapture` inserts a desktop application with both span columns NULL and requires production `RowManifestCodec.capture` to throw without accepting `1×1`. The CI Issue 155 instrumentation command explicitly selects `RealAdapterRowMatrixInstrumentationTest` and completed successfully with 36/36 tests and 0 failures. The control-path `Preview`/`NoChanges` assertion exists in `Issue265ManualEditRecoveryInstrumentationTest`, but that class is not selected by the current CI workflow.
- **Composite acceptance-ID support — verified.** The validator now parses `AC-269-01` through `AC-269-04` as whole requirement tokens, pairs them with the accepted Issue 269 spec, and its added parser regression test passes.

## Executed test surface

- `gh api repos/nunu1733/NunuLauncher/pulls/274 --jq '{number,base_ref:.base.ref,head_ref:.head.ref,head_sha:.head.sha,head_repo:.head.repo.full_name,html_url}'` — verified PR #274 base `main`, head branch `issue-269-folder-workspace-representability`, and exact head `08b1afe67d3aeff8d6b6b8ca1014e8da74afa932`.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/34462108832 --jq '{id,event,status,conclusion,head_branch,head_sha,path,pull_requests:[.pull_requests[].number],run_attempt,html_url}'` — verified rerun attempt 2, `pull_request`, PR #274 association, `.github/workflows/ci.yml`, exact head, `completed`, and `success`.
- `gh api 'repos/nunu1733/NunuLauncher/actions/runs/34462108832/jobs?per_page=100' --jq '[.jobs[] | select(.name=="final-status" or .name=="organizer-unit-tests" or .name=="check-style" or .name=="build-debug-apk" or .id==102826407755) | {id,name,status,conclusion,html_url}]'` — verified `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, and retry job `102826407755` (`organizer-instrumentation-api35-tests`) all completed successfully.
- `gh api repos/nunu1733/NunuLauncher/actions/jobs/102826435920/logs | rg -n "Tests [0-9]+/[0-9]+ completed|BUILD SUCCESSFUL|RealAdapterRowMatrix"` — verified the successful Issue 155 instrumentation command selected `RealAdapterRowMatrixInstrumentationTest`; the job reported 36/36 tests with 0 failures and `BUILD SUCCESSFUL`.
- `rg -n "Issue265Manual|RealAdapterRowMatrix|connectedLawnWithQuickstepGithubDebugAndroidTest" .github/workflows/ci.yml .github/workflows/*.yml` — verified the workflow selects `RealAdapterRowMatrixInstrumentationTest` but has no `Issue265ManualEditRecoveryInstrumentationTest` selection.
- `./gradlew spotlessCheck` — PASS; `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — PASS; 386 actionable tasks.
- `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` — PASS; 389 actionable tasks.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — PASS; 51 tests, including `test_composite_issue_acceptance_ids_are_parsed`.
- `git diff --check 2b7a2702c464538a52731bc52e4141b6f3802784..08b1afe67d3aeff8d6b6b8ca1014e8da74afa932` — PASS; no whitespace errors.
- `adb devices` — unavailable in this audit environment (`command not found`); no local connected instrumentation is claimed.

## Findings

1. **Acceptance-evidence blocker (AC-269-01 through AC-269-03 and the control portion of AC-269-04): the Issue 269 production instrumentation class is not wired into CI.** The new class contains the requested direct/Hotseat/AppPair writer cases and Path A/B/C assertions, and it compiles, but the current workflow has no command selecting it. The green CI run therefore independently proves the dedicated malformed-row capture fixture and the required source jobs, not the writer-transition or organize/recovery flows in that class. Wire the class into a verifiable API 36.1 instrumentation job or provide equivalent independently retrievable execution evidence, then re-audit the resulting head.
2. **No production defect found.** The destination-based `ModelWriter` normalization and strict canonical capture behavior match the accepted design; the follow-up fixture directly exercises the strict NULL-span rejection and passed in CI.
3. The initial API 35 attempt failed one `TwoPanelOrientationCaptureInstrumentationTest`; rerun attempt 2 passed with retry job `102826407755`. This is recorded for traceability and is not an Issue 269 finding.

**Recommendation: do not merge PR #274 yet.** Add independently verifiable CI coverage for `Issue265ManualEditRecoveryInstrumentationTest` (or equivalent retrievable connected evidence), then obtain a successful run for the resulting head and repeat the independent high-risk audit. The audit record intentionally remains `corrective action required`.
