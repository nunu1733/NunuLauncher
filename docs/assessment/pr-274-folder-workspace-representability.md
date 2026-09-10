# High-risk audit: PR #274 fix: preserve folder-child representability across workspace moves

> Status: accepted
> Audit date: 2026-09-10

- Auditor: independent follow-up audit session, separate from the implementation session
- PR: https://github.com/nunu1733/NunuLauncher/pull/274
- Head SHA: 7c29444fc234579440f76532e09189a06cd42652
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34465078591
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-01, `specs/269-folder-workspace-representability/spec.md` AC-269-02, `specs/269-folder-workspace-representability/spec.md` AC-269-03, `specs/269-folder-workspace-representability/spec.md` AC-269-04

## Scope

This final follow-up audit covers the complete PR diff from merge-base
`a96da395056210ad882d262750fbd51b8198f080` through code head
`7c29444fc234579440f76532e09189a06cd42652`, including the workflow change
that selects the Issue 269 production recovery oracle in the Issue 155 API
36.1 instrumentation job. The reviewed implementation and evidence surfaces
are:

- `src/com/android/launcher3/model/ModelWriter.java`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt`
- `.github/workflows/ci.yml`
- `tools/repo-contract/validate_high_risk_evidence.py`
- `tools/repo-contract/test_validate_high_risk_evidence.py`
- the accepted Issue 269 spec/plan and the referenced Spec 13 and recovery ADRs

The production writer bridge remains destination-based: entering the desktop
with an existing `WorkspaceItemInfo` sets both the in-memory and pending DB
span to `1×1` before notification and enqueueing. Canonical capture remains
strict and does not reinterpret malformed NULL desktop spans. The workflow
addition changes only test selection; no production schema, migration,
recovery format, or planner seam is changed by the final follow-up.

## Criteria check

- **AC-269-01 — accepted.** The production writer path normalizes direct
  folder-to-workspace entry, folder-to-Hotseat-to-workspace entry, and stale
  positive spans at the desktop destination. `Issue265ManualEditRecoveryInstrumentationTest`
  exercises real `ItemInfo`/`ModelWriter.moveItemInDatabase` transitions,
  including NULL, positive non-`1×1`, and AppPair-like source cases. The
  final CI workflow selects this class, and the Issue 155 job completed all
  42 selected tests with zero failures and `BUILD SUCCESSFUL`.
- **AC-269-02 — accepted.** The Path A manual-edit recovery oracle compares
  the complete pre-organize manifest with the complete post-restore manifest,
  and requires the `Restorable` then `Restored` lifecycle. The class is now
  selected by the successful Issue 155 job above, so the implementation and
  execution evidence are both present.
- **AC-269-03 — accepted.** The Path C oracle requires a verified second
  organize after the manual move, including reload/capture and exact raw
  manifest equality. The same selected class ran in the successful Issue 155
  instrumentation job with no failed tests.
- **AC-269-04 — accepted.** The strict malformed-row fixture rejects a
  desktop application with both raw span columns NULL without inventing
  `1×1`; the control path retains the `Preview`/`NoChanges` assertion. The
  selected instrumentation suite completed with 42/42 tests and 0 failures.
  Composite acceptance-ID parsing is covered by the validator regression
  test and passes.

## Executed test surface

- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` —
  verified repository `nunu1733/NunuLauncher` and default branch `main`.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/34465078591 --jq '{id,event,status,conclusion,head_sha,path,pull_requests:[.pull_requests[].number],run_attempt,html_url}'` —
  verified pull-request CI, PR #274, workflow `.github/workflows/ci.yml`,
  attempt 2, completed/success, and head
  `7c29444fc234579440f76532e09189a06cd42652`.
- `gh api 'repos/nunu1733/NunuLauncher/actions/runs/34465078591/jobs?per_page=100' --jq '.jobs[] | [.name,.id,.status,.conclusion] | @tsv'` —
  verified every source job and `final-status` completed successfully,
  including Issue 155 job `102834999517` and final-status job
  `102837233452`.
- `gh api 'repos/nunu1733/NunuLauncher/contents/.github/workflows/ci.yml?ref=7c29444fc234579440f76532e09189a06cd42652' --jq .content | base64 --decode | rg -n -C 12 'Issue265ManualEditRecoveryInstrumentationTest|Issue 155'` —
  verified the Issue 265 class is in the Issue 155 selector at the audited
  code head.
- `gh run view -R nunu1733/NunuLauncher --job 102834999517 --log | rg -n -C 4 'Issue265ManualEditRecoveryInstrumentationTest|OK \\([0-9]+ test|[0-9]+ tests|0 failed|Finished 42 tests|BUILD SUCCESSFUL|INSTRUMENTATION_CODE'` —
  verified the actual command includes
  `Issue265ManualEditRecoveryInstrumentationTest`, the run starts 42 tests,
  reports 0 failed throughout, finishes 42 tests, and reaches
  `BUILD SUCCESSFUL`.
- `./gradlew spotlessCheck` — PASS; `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` —
  PASS; organizer unit suite completed successfully.
- `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` —
  PASS; instrumentation sources compile successfully.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — PASS;
  all high-risk evidence self-tests pass, including composite acceptance IDs.
- `git diff --check a96da395056210ad882d262750fbd51b8198f080..7c29444fc234579440f76532e09189a06cd42652` — PASS; no whitespace errors.

The first API 35 attempt in CI exposed one unrelated
`TwoPanelOrientationCaptureInstrumentationTest` failure; the failed job was
rerun and the final CI run (attempt 2) passed. This is retained for traceability
and is not an Issue 269 finding.

## Findings

No blocking findings. The prior evidence blocker is resolved: the final
workflow selects `Issue265ManualEditRecoveryInstrumentationTest`, and the
successful pull-request CI logs provide execution evidence for the writer,
recovery, second-organize, control-path, and strict-capture assertions. The
implementation remains within the accepted Issue 269 scope and preserves the
fail-closed capture and exact recovery contracts.

**Recommendation: accepted; merge eligible.**
