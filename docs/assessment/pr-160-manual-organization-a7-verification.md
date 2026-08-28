# High-risk audit: PR #160 manual organization A7 verification

> Status: findings; merge blocked pending P1 resolution and re-audit
> Audit date: 2026-08-28

- Auditor: Codex independent audit session; separate from the implementation session; no production or test implementation changes made by the auditor
- PR: https://github.com/nunu1733/NunuLauncher/pull/160
- Head SHA: 44b4bad0c22bd70d823642bea10c80b0565f776a
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33084545112 (verified as a `pull_request` CI run on the exact head; `final-status`, style, build, organizer unit, and connected source jobs passed)
- Criteria: docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

## Scope

Audited the complete `fd3dad799d...44b4bad0c22bd70d823642bea10c80b0565f776a` diff for PR #160, including the Launcher model/loader completion bridge, canonical DB capture ordering, protocol and instrumentation regressions, CI lane registration, accepted Issue #150 spec/plan, and redacted device evidence.

The runtime write/recovery surface reviewed was `LoaderTask`/`LauncherModel` reload coordination, `LayoutWriteCoordinator` lease admission, `RowManifestCodec` capture/materialization, and the existing apply/recovery verification path. No schema, recovery-point format, backup format, public planner/application contract, permission, or transport migration is present in this diff. The audit did not modify production or test source.

## Criteria check

- **AC-150-01 — P1 / not demonstrated:** The production move from bind completion to post-transaction notification is present, but `OrganizerReloadCompletionOrderingTest` does not hold `LoaderTransaction.commit()`/`close()` incomplete. Its `onInitialBindComplete` latch runs on the UI executor (`tests/organizer-instrumentation/com/android/launcher3/OrganizerReloadCompletionOrderingTest.java:152-171`), while `LoaderTask` can continue to `transaction.commit()` and leave the try-with-resources block independently (`src/com/android/launcher3/model/LoaderTask.java:410-437`). The `completionFired` assertion therefore remains dependent on UI/model scheduling and does not satisfy the spec's causal pre-fix oracle.
- **AC-150-02 — partial:** The implementation and the new post-close supersession regression cover queued completion and token replacement. The ordering test's causal evidence remains subject to the AC-150-01 finding.
- **AC-150-03 — partial:** Local protocol failure-path tests pass and no false success was found in the reviewed seams; the completion-boundary oracle has the same unresolved gap.
- **AC-150-04 — evidence caveat:** The redacted debug/release device record is for `b9f7e969cd`, an ancestor of the audited head. The PR comments record a debug `44b4...` run, but no exact-head release export is present in the evidence document. This is a provenance gap against the plan's exact-commit evidence requirement.
- **AC-150-05 — evidence caveat:** The explicit recovery correlation is documented at `b9f7e969cd` and is structurally consistent with the unchanged recovery path; exact-head release provenance is not independently recorded.
- **AC-150-06 — met:** ZIP `NotReady` observability remains explicitly owned by Issue #153 and is not absorbed into this change.
- **AC-150-07 — not met:** Exact-head PR `CI / final-status` passed, but the independent audit has identified a blocking test-oracle issue. The high-risk evidence gate run `33084544696` is the pre-audit run and is expected to require rerun after this record is included; a clean gate and a re-audit are still required after code/test changes.
- **ADR-0003 — no violation found:** The diff preserves the separate recovery-point store and existing typed rollback/recovery behavior; no raw database replacement or migration was introduced.

## Executed test surface

Independent local execution on macOS arm64 with JDK 21.0.12, against the audited checkout:

- `git status --short --branch` — clean before adding this audit record.
- `git diff --check fd3dad799d...44b4bad0c22bd70d823642bea10c80b0565f776a` — passed.
- `./gradlew spotlessCheck` — `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — `BUILD SUCCESSFUL`.
- `python3 tools/repo-contract/validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — 11 tests passed.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — 47 tests passed; actual PR evidence validation before adding this file correctly reported the audit record as missing.

GitHub API evidence for [CI run 33084545112](https://github.com/nunu1733/NunuLauncher/actions/runs/33084545112) confirms `event=pull_request`, exact head SHA, successful `final-status`, and successful non-skipped source jobs including `check-style`, `build-debug-apk`, `organizer-unit-tests`, and the shared-writer/organizer instrumentation lanes. No local emulator was attached in this audit session, so connected-test and device claims rely on that exact-head CI run and the repository's redacted evidence record.

## Findings

The audit found one blocking test-oracle defect and one non-blocking device-evidence provenance gap; the code is not approved for merge at this head.

### P1 — completion-order regression oracle does not hold the transaction boundary

`OrganizerReloadCompletionOrderingTest` calls `assertFalse(completionFired.get())` after its callback reaches a latch, but the latch is in `BgDataModel.Callbacks.onInitialBindComplete`, which `BaseLauncherBinder` schedules through `MAIN_EXECUTOR`. `LoaderTask` does not wait for that UI callback before committing and closing its `LoaderTransaction`; it posts the organizer completion only afterward. Consequently, the test does not construct the required state “completion observed while commit/close is intentionally blocked.” A slow UI thread can let the corrected completion run before the test callback reaches the assertion, and the old implementation's pass/fail distinction is not tied to a held transaction boundary. This contradicts AC-150-01's explicit requirement that scheduling alone cannot make the test pass.

Fix the test seam so the barrier is owned by the transaction owner (or an existing callback invoked synchronously on the loader thread) and directly observes the completion runnable while commit/close is held. Then rerun the exact-head CI and perform this independent audit again.

### P2 — exact-head device evidence provenance

`docs/assessment/evidence/issue-150-device-verification.md` records debug and release success at `b9f7e969cd`, not the audited `44b4...` head. The latest PR comment reports a debug smoke run at `44b4...`, but does not provide the corresponding redacted release journal/invariant evidence. Re-run or explicitly document an approved equivalence for the release path at the final audited head before treating AC-150-04/05 as fully evidenced.

### Merge-gate follow-up

The qualifying CI run is green, but the high-risk gate must rerun after this audit record is committed/pushed. Because the audit found a P1, that rerun should occur only after the test oracle is corrected and a new exact-head CI run succeeds.
