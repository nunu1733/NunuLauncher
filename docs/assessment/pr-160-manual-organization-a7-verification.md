# High-risk audit: PR #160 manual organization A7 verification

> Status: accepted; no blocking findings in the re-audited implementation
> Audit date: 2026-08-28

- Auditor: Codex independent re-audit session; separate from the implementation session; no production or test implementation changes made by the auditor
- PR: https://github.com/nunu1733/NunuLauncher/pull/160
- Head SHA: 7bc64df92a0fa4291662e3795a192f1a035ad8b8
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33128406357 (verified as a `pull_request` CI run on the exact head; `final-status`, style, build, organizer unit, and connected source jobs passed)
- Criteria: docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

## Scope

Re-audited the complete `fd3dad799d...7bc64df92a0fa4291662e3795a192f1a035ad8b8` diff for PR #160, with focused follow-up on the prior P1 completion-ordering oracle and P2 device-evidence provenance findings. The accepted requirements checked were AC-150-01 through AC-150-07 in `specs/150-manual-organization-a7-verification/spec.md`, together with ADR-0003.

The runtime write/recovery surface reviewed was `LoaderTask`/`LauncherModel` reload coordination, `LayoutWriteCoordinator` lease admission, `RowManifestCodec` capture/materialization, and the existing apply/recovery verification path. No schema, recovery-point format, backup format, public planner/application contract, permission, or transport migration is present in this diff. The implementation commit after the previous audit (`7bc64df...`) changes only the completion-ordering test and documentation; no production source changed after the previously audited production head (`44b4bad...`). The auditor did not modify production or test source.

## Criteria check

- **AC-150-01 — met:** The revised `OrganizerReloadCompletionOrderingTest` now makes the negative oracle causal. `onInitialBindComplete` is held on the main executor, and `LoaderTask.waitForIdle()` occurs after `bindWorkspace` and before `transaction.commit()` (`src/com/android/launcher3/model/LoaderTask.java:300-311`); the held callback therefore prevents main-looper idleness and keeps the loader before the transaction boundary. The test synchronously checks `completionFired` at barrier entry and additionally proves it remains false for the three-second hold probe (`tests/organizer-instrumentation/com/android/launcher3/OrganizerReloadCompletionOrderingTest.java:56-109`). Under the pre-fix ordering, the completion was signalled before the barrier callback; the corrected post-close notification cannot fire while the causal hold is active.
- **AC-150-02 — met:** Completion is posted only after the transaction resource closes (`src/com/android/launcher3/model/LoaderTask.java:410-437`), and the supersession regression covers a replacement arriving during the post-close/pre-delivery gap. `LauncherModel.forceReloadForOrganizer` preserves and delivers the superseded token outside the model lock.
- **AC-150-03 — met:** Protocol success, supersession, timeout, and typed failure-path coverage pass through the public organizer seam; no false-success path was found in the reviewed implementation.
- **AC-150-04 — met:** The redacted debug/release device record documents the A7 runtime and build at `44b4bad0c2`, including the release journal via the supported Settings diagnostics export surface. The later `7bc64df...` commit is test/docs-only, and exact-head CI reran the revised regression test, so no production behavior changed after the device evidence was collected.
- **AC-150-05 — met:** The same evidence records the recovery correlation and invariant checks at the production-equivalent `44b4bad...` implementation head; the recovery store and apply path remain unchanged through `7bc64df...`.
- **AC-150-06 — met:** ZIP `NotReady` observability remains explicitly owned by Issue #153 and is not absorbed into this change.
- **AC-150-07 — met subject to gate refresh:** Exact-head PR [CI run 33128406357](https://github.com/nunu1733/NunuLauncher/actions/runs/33128406357) is completed and green, including `final-status`. The first high-risk gate run at this head failed only because the audit record still named the prior `44b4...` head; this re-audit updates the record to `7bc64df...`.
- **ADR-0003 — no violation found:** The diff preserves the separate recovery-point store and existing typed rollback/recovery behavior; no raw database replacement or migration was introduced.

## Executed test surface

Independent local execution on macOS arm64 with JDK 21.0.12, against the re-audited checkout:

- `git status --short --branch` — clean before updating this audit record.
- `git diff --check fd3dad799d...7bc64df92a0fa4291662e3795a192f1a035ad8b8` — passed.
- `./gradlew spotlessCheck` — `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — `BUILD SUCCESSFUL`.
- `python3 tools/repo-contract/validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — 11 tests passed.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — 47 tests passed.
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 160 --head-sha 7bc64df92a0fa4291662e3795a192f1a035ad8b8 --root /Users/nunu/Documents/work/NunuLauncher` — `PASS`; the required local gate check accepts this record and the exact-head CI evidence.

GitHub API evidence for [CI run 33128406357](https://github.com/nunu1733/NunuLauncher/actions/runs/33128406357) confirms `event=pull_request`, exact head SHA, successful `final-status`, and successful non-skipped source jobs including `check-style`, `build-debug-apk`, `organizer-unit-tests`, and the shared-writer/organizer instrumentation lanes. No local emulator was attached in this audit session, so connected-test and device claims rely on that exact-head CI run and the repository's redacted evidence record.

## Findings

The previous P1 and P2 findings are resolved. No blocking runtime, layout-data, recovery, or specification finding remains in the re-audited implementation.

### Previous P1 — resolved: causal completion-ordering oracle

The revised test does not rely on an arbitrary delay or callback scheduling. The held main-thread callback prevents the loader's `waitForIdle()` from returning before `transaction.commit()`, while the synchronous completion flag check and three-second hold probe establish that completion cannot be observed during the held boundary. The pre-fix ordering would signal completion before entering this barrier, so the test distinguishes the defect rather than merely testing eventual ordering.

### Previous P2 — resolved: device-evidence provenance

The evidence document now identifies the debug/release build and runtime journal at `44b4bad0c2`, the last production-changing commit. The only later PR commit is test/documentation changes, and the exact `7bc64df...` CI run passed the updated test suite. This is sufficient to establish production-behavior equivalence for the re-audit; no production source drift was found.

### Merge-gate follow-up

The audit record must be committed/pushed so the repository's high-risk workflow can rerun against the updated `Head SHA`. Until that workflow rerun succeeds, the repository gate remains pending even though the exact-head CI run is green and the local audit validator is expected to pass.
