# High-risk audit: PR #193 #178 gate grammar and recovery-store cleanups

> Status: accepted
> Audit date: 2026-09-02

- Auditor: independent Codex audit session, separate from the implementation session; the auditor changed no production, test, or spec file — this record is the only artifact produced by this session
- PR: https://github.com/nunu1733/NunuLauncher/pull/193
- Head SHA: ea32f6eb8732a517708f966891dd311b6233a177
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33598734977 (verified via the GitHub API as a completed, successful `pull_request` run of `.github/workflows/ci.yml`, associated with PR #193 on the exact audited head SHA; run attempt 2 has successful, non-skipped `final-status`, `organizer-unit-tests`, `check-style`, and `build-debug-apk` jobs. Attempt 1 failed only in `organizer-instrumentation-api35-tests`: `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`, in a file outside this six-file diff, asserted unequal row state. The API shows that lane re-ran successfully on the same head in attempt 2.)
- Criteria: specs/174-recovery-record-cursor-window/spec.md CW-AC-01, CW-AC-04, CW-AC-10, docs/adr/0009-chunk-recovery-manifests.md ADR-0009, docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

## Scope

This audit covers the requested base-to-head delta `c47c9d9464..ea32f6eb8732a517708f966891dd311b6233a177`. `git status --porcelain` was empty before this record was added and `git rev-parse HEAD` returned the stated audited SHA. The full diff contains exactly six files: `tools/repo-contract/validate_high_risk_evidence.py`, its self-test, `docs/project/github-workflow.md`, `RecoveryStore.kt`, `RecoveryDbHelper.kt`, and `RecoveryStoreLifecycleTest.kt`. `docs/engineering/building.md` is untouched (`git diff --quiet ... -- docs/engineering/building.md` exited 0).

The grammar change permits an optional all-caps hyphen prefix before the existing `FR`/`NFR`/`AC` family and retains `(?<![A-Za-z0-9_-])` / `(?![A-Za-z0-9_-])` boundaries. The added self-tests prove `CW-AC-01` and `CW-AC-10` parse and verify, while malformed extensions and embedded/longer values do not. In particular, the source review confirms that `XCW-AC-01` and `CW-AC-01foo` cannot be extracted as `CW-AC-01`.

The non-blocking cleanup delta is confined to the requested work: the dead private `RecoveryStore.storedFromEncoded()` helper is deleted; `RecoveryDbHelper` now accurately describes creation from the shared current-schema DDL; `validateChunkCoverage` gains the requested defense-in-depth limitation note; and the new real-SQLite lifecycle test injects the `TOMBSTONE` fault into the quarantine transaction itself. It verifies the pre-commit rollback retains the `READY` point and all chunks with no tombstone, and that the ambiguous post-commit case stores `QUARANTINED` (canonical reason 6), removes chunks child-first, and leaves zero orphan chunks. No production ownership or ordering statement changed.

## Criteria check

All cited documents exist and have an acceptable status before being cited: `specs/174-recovery-record-cursor-window/spec.md` is `implemented`; `docs/adr/0009-chunk-recovery-manifests.md` is `implemented`; and `docs/adr/0003-organizer-recovery-point-storage.md` is `accepted`. Repository searches located each exact ID in its cited document.

- **CW-AC-01 — met by static diff review.** The dead-code removal and comment-only edits do not alter the recovery-record/tombstone contract, ownership, lifecycle, or transaction ordering. The new test operates through the existing reconciliation session and quarantine seam, preserving the chunked-manifest/physical-schema decision in ADR-0009 and checkpoint-before-Launcher ordering in ADR-0003.
- **CW-AC-04 — met by static diff review.** `quarantineFaultInjectionRollsBackBeforeCommitAndKeepsCanonicalTombstoneAfterCommit` is a dedicated test of `writeTombstone` followed by child-first `deletePoint` in the quarantine transaction, not a retention or prune proxy. It closes the stated oracle: quarantine rollback is observed and its committed tombstone has canonical reason 6.
- **CW-AC-10 — met by static diff review.** The deletion implementation is unchanged; the new test explicitly asserts zero chunks for the committed point and `RecoveryManifestChunks.countOrphanChunks(db) == 0`, while the rollback case preserves the original chunk count. This directly supplements the requirement that quarantine delete children before the parent and leave no committed orphan chunks.
- **ADR-0009 — met by static diff review.** The delta is consistent with its chunk-manifest representation and explicit child-first ownership decision; it neither changes logical record format nor adds a foreign-key dependency.
- **ADR-0003 — met by static diff review.** No Launcher DB write path, recovery-store location, or checkpoint-before-layout ordering changes. The touched production text correctly states that the recovery DB remains separate and current-schema based.

## Executed test surface

- `git status --porcelain` — empty before this record; `git rev-parse HEAD` — `ea32f6eb8732a517708f966891dd311b6233a177`.
- `git diff c47c9d9464..ea32f6eb8732a517708f966891dd311b6233a177` and `git diff --check c47c9d9464..ea32f6eb8732a517708f966891dd311b6233a177` — full six-file diff read; no whitespace errors. `git diff --quiet c47c9d9464..ea32f6eb8732a517708f966891dd311b6233a177 -- docs/engineering/building.md` — exit 0 (no building-guide change).
- `python3 tools/repo-contract/validate_repo_contract.py` — passed: `repository contract OK`.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — passed: 11 tests, 0 failures. Its expected invalid-fixture subprocess printed a contract failure as part of the passing negative test.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — passed: 50 tests, 0 failures, including the new whole-token prefixed-family parsing and definition tests.
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 193 --head-sha ea32f6eb8732a517708f966891dd311b6233a177` — passed: the gate classified the recovery-store paths as high risk and accepted this record's independent CI evidence and all three cited documents.
- `gh issue view 178 --repo nunu1733/NunuLauncher --comments` and `gh issue view 178 --repo nunu1733/NunuLauncher --json number,title,body,comments` — completed; the Issue contains the two requested parts and no comments. `gh pr view 193 --repo nunu1733/NunuLauncher --json headRefOid` — returned `ea32f6eb8732a517708f966891dd311b6233a177`, equal to `git rev-parse HEAD`.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/33598734977` — verified `event: pull_request`, `path: .github/workflows/ci.yml`, `status: completed`, `conclusion: success`, audited `head_sha`, `head_branch: issue-178-gate-criteria-grammar`, and `pull_requests[0].number: 193`. `gh api 'repos/nunu1733/NunuLauncher/actions/runs/33598734977/jobs?per_page=100'` — latest attempt has 13 successful jobs, including successful non-skipped `final-status`, `organizer-unit-tests`, `check-style`, and `build-debug-apk` on the audited SHA. `gh api 'repos/nunu1733/NunuLauncher/actions/runs/33598734977/attempts/1/jobs?per_page=100'` — attempt 1's API-35 instrumentation job was `completed` / `failure` on the same SHA; `gh run view 33598734977 --repo nunu1733/NunuLauncher --attempt 1 --log-failed` identifies the unrelated `TwoPanelOrientationCaptureInstrumentationTest` assertion. The attempt-2 job list reports the same lane `completed` / `success`.
- `./gradlew spotlessCheck` — `BUILD SUCCESSFUL in 6s`; all five Spotless tasks completed (up-to-date).
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — `BUILD SUCCESSFUL in 29s`; focused organizer JVM test task completed successfully.
- `./gradlew assembleLawnWithQuickstepGithubDebug` — `BUILD SUCCESSFUL in 6s`; debug APK assembly completed successfully.
- `$HOME/Library/Android/sdk/platform-tools/adb devices` — `emulator-5554` present and `device`. `$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -e class com.android.launcher3.organizer.RecoveryStoreLifecycleTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` — `OK (20 tests)`. The same command with `RecoveryStoreChunkedManifestInstrumentationTest` — `OK (7 tests)`.

## Findings

No blocking findings. The previously unavailable GitHub, Gradle, and ADB evidence was re-run successfully after the sandbox restriction was lifted. The complete six-file diff implements the two Issue #178 deliverables as scoped: whole-token grammar and regression coverage, an untouched building guide, and the four PR-175 audit cleanups without a behavioral widening of the recovery-store protocol.

### 1. CI attempt-1 API-35 instrumentation flake — non-blocking and resolved

API attempt 1 failed only in `organizer-instrumentation-api35-tests` on `app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`, which asserted unequal row state. `git diff --name-only c47c9d9464..ea32f6eb8732a517708f966891dd311b6233a177` contains neither that test nor its production path. The same API-35 lane was re-run successfully in attempt 2 on the exact audited SHA; both focused recovery-store classes also pass locally. This is recorded as an unrelated CI flake, not a defect in this diff.

### Independence

This was an independent Codex audit session separate from the implementation session. The auditor changed no production, test, or spec file; this record is the only artifact and is intended for a docs-only commit after the audited head, which the gate lineage rule permits.
