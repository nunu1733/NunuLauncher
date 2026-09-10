# High-risk audit: PR #274 fix: preserve folder-child representability across workspace moves

> Status: accepted
> Audit date: 2026-09-10

- Auditor: independent follow-up audit session, separate from the implementation session
- PR: https://github.com/nunu1733/NunuLauncher/pull/274
- Head SHA: a0a91e416efda1f18ee89f323a02d9a1238a913f
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34471574884
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-01
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-02
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-03
- Criteria: `specs/269-folder-workspace-representability/spec.md` AC-269-04

## Scope

This independent follow-up re-audit covers the effective PR diff from the
new main merge-base `a38256976eb6f608244d8a7d2b10c65190636531` through code
head `a0a91e416efda1f18ee89f323a02d9a1238a913f`:

```text
git diff a38256976eb6f608244d8a7d2b10c65190636531..a0a91e416efda1f18ee89f323a02d9a1238a913f
10 files changed, 1532 insertions(+), 15 deletions(-)
```

The PR branch merged current `main` at `a38256976e`; the merge also brings
the already-merged Issue #270 history into the branch. The audit boundary is
the branch-side delta from that new base, while the prior audit record and
its resolved Issue #265 CI evidence blocker remain below for traceability.
The reviewed surfaces are the destination-based `ModelWriter` bridge, the
Issue #265 recovery and real-writer instrumentation, the strict row-matrix
control, the Issue 155 selector in `.github/workflows/ci.yml`, the accepted
Issue #269 spec/plan and Spec 13 revision, and the high-risk evidence
validator changes.

## Criteria check

- **AC-269-01 — accepted.** The destination-based `ModelWriter` branch
  persists in-memory and DB `1×1` spans for every existing
  `WorkspaceItemInfo` entering the desktop, covering NULL and positive
  non-`1×1` folder-child rows, direct moves, the folder → Hotseat → workspace
  path, and an AppPair-like source. The selected Issue155 suite completed
  with 42/42 tests and zero failures.
- **AC-269-02 — accepted.** The production Path A oracle uses a real
  `ItemInfo`/`ModelWriter.moveItemInDatabase` transition, then requires
  `Restorable → Restored` and exact equality with the pre-organize manifest.
  The Issue265 class was selected and executed in the successful Issue155
  job.
- **AC-269-03 — accepted.** The production Path C oracle performs the manual
  move, second organize, reload, and repeated organize checks, including
  exact raw-manifest equality and verified apply. The same selected class
  completed successfully in Issue155.
- **AC-269-04 — accepted.** The strict capture fixture keeps an external or
  malformed desktop row with both raw span columns NULL fail-closed rather
  than inventing `1×1`; the unchanged Path B control retains exact restore
  assertions. The 42-test Issue155 suite and validator self-tests passed.

## Executed test surface

- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` —
  verified the target repository and default branch `main`.
- `gh api repos/nunu1733/NunuLauncher/pulls/274 --jq
  '{base_ref:.base.ref,head_sha:.head.sha,head_ref:.head.ref}'` — verified
  PR #274 targets `main` and has head `a0a91e416efda1f18ee89f323a02d9a1238a913f`.
- `git diff --stat a38256976eb6f608244d8a7d2b10c65190636531..a0a91e416efda1f18ee89f323a02d9a1238a913f` and
  `git diff --check a38256976eb6f608244d8a7d2b10c65190636531..a0a91e416efda1f18ee89f323a02d9a1238a913f` —
  verified the complete effective scope and no whitespace errors.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/34471574884 --jq
  '{id,event,status,conclusion,head_sha,path,pull_requests:[.pull_requests[].number],run_attempt,html_url}'` —
  verified PR #274 `pull_request` CI, attempt 2, completed/success, workflow
  `.github/workflows/ci.yml`, and the exact audited head.
- `gh api 'repos/nunu1733/NunuLauncher/actions/runs/34471574884/jobs?per_page=100' --jq
  '.jobs[] | [.name,.id,.status,.conclusion] | @tsv'` — verified every
  source job and `final-status` completed successfully, including
  `organizer-unit-tests`, `check-style`, `build-debug-apk`, and Issue155 job
  `102855688122`.
- `gh run view -R nunu1733/NunuLauncher --job 102855688122 --log | rg -n -C 3
  'Issue265ManualEditRecoveryInstrumentationTest|Starting [0-9]+ tests|Tests [0-9]+/[0-9]+|Finished [0-9]+ tests|0 failed|BUILD SUCCESSFUL'` —
  verified the actual Issue155 command selected
  `Issue265ManualEditRecoveryInstrumentationTest`, started 42 tests, kept
  0 skipped/0 failed, finished 42 tests, and reached `BUILD SUCCESSFUL`.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — PASS;
  repository high-risk evidence self-tests pass, including composite
  acceptance-ID handling.

CI attempt 1 of run `34471574884` had one Issue52 UI test failure,
`ManualOrganizationPreferencesInstrumentationTest.previewHeadingRestoresFocusAndCancelReturnsFocusToStartAction`,
with a Compose `SlotWriter.moveSlotGapTo` `ArrayIndexOutOfBoundsException`.
The failed job was rerun; attempt 2 is the successful evidence recorded
above. This was unrelated to Issue #269 and did not reproduce in the final
attempt. The earlier high-risk gate attempt `34471574798` failed because the
audit was stale; the gate must be re-evaluated after this audit-only commit.

## Findings

No blocking findings. The new main merge-base is explicit, the effective
diff remains within the accepted Issue #269 scope, and the production
writer/recovery/strict-capture contracts remain intact. The prior Issue #265
evidence blocker is resolved: the final workflow selects
`Issue265ManualEditRecoveryInstrumentationTest`, and the successful Issue155
logs provide execution evidence for normalization, recovery, second
organize, control-path, and strict-capture assertions.

**Recommendation: accepted; merge eligible, subject to the new high-risk
evidence gate passing for the audit-only commit.**

## Previous audit scope (retained)

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

### Previous criteria check

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

### Previous executed test surface

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

### Previous findings

No blocking findings. The prior evidence blocker is resolved: the final
workflow selects `Issue265ManualEditRecoveryInstrumentationTest`, and the
successful pull-request CI logs provide execution evidence for the writer,
recovery, second-organize, control-path, and strict-capture assertions. The
implementation remains within the accepted Issue 269 scope and preserves the
fail-closed capture and exact recovery contracts.

**Recommendation: accepted; merge eligible.**
