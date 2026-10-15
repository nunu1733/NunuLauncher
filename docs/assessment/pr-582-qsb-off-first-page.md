# High-risk audit: PR #582 QSB OFF row-backed first-page capture

> Status: accepted
> Audit date: 2026-10-12

- Auditor: Codex independent audit agent/session `/root/audit_582_current`, with fresh context. This auditor did not implement the production repair, setup-order correction, or `94d0c088` main-thread preference follow-up; independence is based on separate inspection and re-execution, not model identity.
- PR: https://github.com/nunu1733/NunuLauncher/pull/582
- Head SHA: 94d0c088df979b4d0aa32158701aa8ab62c530b4
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/38262792114
- Criteria: specs/155-qsb-reservation-reload/spec.md QSB-AC-01 QSB-AC-09; docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008

## Scope

Independently inspected the complete seven-file diff from base `840603371c498fa6162192facf9a2aa73dadc242` to the audited head: 179 insertions and 44 deletions. [Pinned comparison](https://github.com/nunu1733/NunuLauncher/compare/840603371c498fa6162192facf9a2aa73dadc242...94d0c088df979b4d0aa32158701aa8ab62c530b4).

- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/PageCaptureInstrumentationTest.kt`
- `specs/155-qsb-reservation-reload/spec.md`
- `specs/155-qsb-reservation-reload/plan.md`
- `docs/engineering/ci-test-portfolio.md`
- `tools/repo-contract/ci_portfolio_map.yml`
- `docs/assessment/issue-581-qsb-off-first-page.md`

Read [Issue #581](https://github.com/nunu1733/NunuLauncher/issues/581), including its complete comment set (empty, rechecked at 2026-10-12 14:47:47 UTC), and Issue #155 with all comments. Authorization is the accepted #155 spec/plan at `be942c3a4a13030856bb3ec99866b5e21efe9d13`, reviewed text `4898864cfc7279c7ede92e2d523f8b090c2994ca`, and [owner start acceptance](https://github.com/nunu1733/NunuLauncher/issues/155#issuecomment-5432981113). The current spec/plan clarification preserves that page-authority rule; it does not introduce a new policy choice. Also read AGENTS, CONTEXT, DESIGN, ADR-0008, the workflow/audit template, quality strategy, build guide, and test-audit skill.

The production change is confined to the read-only canonical page inventory: append all SQL-derived pages instead of filtering out `FIRST_SCREEN_ID`. The adapter still captures feature/IDP context once, passes the same pages/reservations into `RowManifestCodec`, and derives state, manifest resources and revision from that capture. Inspected the complete adapter and its capture codec boundary, the platform `BgDataModel.collectWorkspaceScreens()` rule, and the real Smartspace preference/restart callback. No writer, Launcher schema, recovery codec/version, public interface, dependency, permission, Loader bridge or CI filter is changed.

## Criteria check

| Criterion | Independent result |
|---|---|
| QSB-AC-01 / ADR-0008: OFF preserves row-backed first page | PASS. SQL yields distinct numeric screen order; OFF with rows now retains screen 0. The existing public capture test requires literal `[0,1]`, and a first-only control requires `[0]`. |
| QSB-AC-01 / ADR-0008: synthesis and ordering controls | PASS. OFF with nonzero rows does not synthesize page 0; OFF empty rows retain the logical first-page fallback; ON with a nonzero row synthesizes page 0 once. Literal expectations also protect nonzero numeric ordering and repeated `captureCurrent`/`recaptureDb` results. |
| ADR-0008: one-shot, preserve-only context | PASS by source inspection. The fix reuses the captured Boolean and changes only inventory construction. Shared `LayoutState`/manifest construction, revision computation and all write/recovery paths remain unchanged. |
| Fixture safety and callback thread | PASS by source inspection and successful fresh-run evidence. The ON control clears original favorites before enabling QSB. Cleanup restores saved rows under OFF, restores the original preference, then waits for reload. Every real preference write, including cleanup, uses `runOnMainSync`, matching the callback's `Activity.recreate` thread requirement. |
| QSB-AC-09: current source CI and separate re-execution | PASS for this bounded repair. Exact-head PR CI attempt 1 completed successfully with actual source jobs and `final-status`; this independent session then requested only the reservation/recovery job again. Attempt 2 and its `final-status` also completed successfully. Links and observed test-count limits are below. |

Test-audit conclusions: the protected contract is canonical page authority, and the credible regression is an OFF `[0,1]` capture throwing the codec's omitted-page invariant. The primary owner remains `LauncherLayoutAdapter.captureCurrent()` over real SQLite and the real preference seam. This framework/storage/thread-affinity risk is distinct from planner and Loader-deletion tests. Literal page lists replace the expectation helper; no production test seam is added. The class is already explicitly filtered in the Conditional `surface_layout_write` reservation/recovery lane, and the three added methods reuse that lane. Portfolio/map changes are documentation comments only; no routing edge or new lane is introduced.

Supplemental RED was independently inspected in `/tmp/516-closure-runs/581-page-off-red.txt`: one `modelOnlyEmptyPagesExcludedFromCapture` test fails at `RowManifestCodec.capture:75` with the intended omitted-page invariant. The auditor independently verified that base main/15 and supplemental API 37 source `41d8753d7a23eac495856f31d57774a7b8e5d07a` have identical adapter blob `a09f1a6e424fb021aa21463e7d45c73aed6fb725`. Audited main/15 and the API 37 port `ae69715379314f24338304ca5a0b2b4f9e9f7c43` both have corrected blob `871261171dc2a031198e827cdb0da8872de29d67`. Parent-produced supplemental GREEN at `/tmp/516-closure-runs/581-page-off-on-green.txt` was also read: all six named methods finish with status code 0 and `OK (6 tests)` in 34.573 s. These are API 37 supplements, not an independently executed main/15 baseline RED or substitutes for the CI re-execution below.

## Executed test surface

`$AUDIT_WORKTREE` below denotes the private checked source/evidence worktree; its host path is omitted from publication. The auditor independently ran the following against the audited source checkout, without local Gradle, ADB, APK installation, or use of any local emulator:

| Command | Result |
|---|---|
| `git diff --check 840603371c498fa6162192facf9a2aa73dadc242 94d0c088df979b4d0aa32158701aa8ab62c530b4` | PASS |
| `python3 tools/repo-contract/validate_ci_portfolio.py` | PASS |
| `python3 tools/repo-contract/test_validate_ci_portfolio.py` | PASS, 21 tests; an existing unclosed-file ResourceWarning was emitted |
| `python3 tools/repo-contract/validate_repo_contract.py` | PASS |
| `python3 tools/repo-contract/test_validate_repo_contract.py` | PASS, 13 tests; expected invalid-fixture diagnostics are part of the successful self-test |
| `gh run rerun 38262792114 -R nunu1733/NunuLauncher --job 114857447944` | Requested only the reservation/recovery job after complete source CI success; independent attempt 2 succeeded |
| `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 582 --head-sha 94d0c088df979b4d0aa32158701aa8ab62c530b4 --root "$AUDIT_WORKTREE"` | PASS after adding this audit; GitHub API evidence and local audit fields/criteria validated. This local result is not the published PR check. |

Original exact-head evidence is [attempt 1](https://github.com/nunu1733/NunuLauncher/actions/runs/38262792114/attempts/1), `pull_request`, associated by GitHub with PR #582 and audited head. `validate-repo-contract`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, shared-writer, production-input and reservation/recovery jobs actually succeeded. [Original final-status](https://github.com/nunu1733/NunuLauncher/actions/runs/38262792114/job/114863605289) succeeded. The auditor checked these GitHub API results directly.

The independent [reservation/recovery re-execution](https://github.com/nunu1733/NunuLauncher/actions/runs/38262792114/job/114864178974) ran on a new GitHub-hosted runner and clean API 36 Google APIs x86_64 Pixel 7 Pro emulator, using Zulu JDK 21 and the repository's Platform 36.1 build. It reused the actual checked-in command:

```bash
bash tools/ci/run-emulator-command-with-failure-capture.sh emulator-5554 build/reservation-recovery-failure-time-evidence -- ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.ProductionPublicSeamInstrumentationTest,app.lawnchair.organizer.application.RealAdapterRowMatrixInstrumentationTest,app.lawnchair.organizer.application.SanitizerInstrumentationTest,com.android.launcher3.organizer.RecoveryStoreLifecycleTest,app.lawnchair.organizer.application.OverlapAcceptanceGateSeamInstrumentationTest,app.lawnchair.organizer.planning.LoaderCursorOverlapAcceptanceContractTest,app.lawnchair.organizer.application.Issue265ManualEditRecoveryInstrumentationTest,app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest,app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotPublicationInstrumentationTest,app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest,com.android.launcher3.organizer.RecoveryStoreChunkedManifestInstrumentationTest,app.lawnchair.organizer.application.Issue265GateFailedRouteInstrumentationTest,app.lawnchair.organizer.application.PageCaptureInstrumentationTest,app.lawnchair.organizer.locks.LockAuthoringInstrumentationTest
```

The actual original job log (`/tmp/582-current-source-reservation.log`) reports `Starting 91 tests`, a last periodic count of **89/91 with 0 skipped and 0 failed**, `Finished 91 tests`, and `BUILD SUCCESSFUL in 9m 1s`. The actual independent job log (`/tmp/582-independent-reservation-rerun.log`) reports `Starting 91 tests`, a last periodic count of **90/91 with 0 skipped and 0 failed**, `Finished 91 tests`, and `BUILD SUCCESSFUL in 9m 48s`. The independent [attempt 2 final-status](https://github.com/nunu1733/NunuLauncher/actions/runs/38262792114/job/114869134983) succeeded; the run finished successfully on the same head. Other source-job success results were retained by the targeted GitHub rerun, so this is not a claim that every source job was independently executed twice.

Successful instrumentation XML is not uploaded by this workflow. The logs do not expose per-method success receipts or a final 91/91 skip summary. The six-method PageCapture coverage on main/15 is therefore an explicit **inference** from the unchanged class filter, independently inspected six `@Test` methods (three at base), unchanged other classes, completed 91-test suite, absence of reported test failures and no skips in the observed periodic counts. No success XML or final exact zero-skip count is claimed. The separate API 37 raw output provides named-method receipts only for that supplemental environment.

## Findings

No blocking finding for the bounded Issue #581 repair. Audit recommendation: **PASS** for source/test head `94d0c088df979b4d0aa32158701aa8ab62c530b4` after the qualified source CI and independent targeted re-execution above.

The earlier [failed source CI](https://github.com/nunu1733/NunuLauncher/actions/runs/38259014998) at `ec1f913a6e903236b7f711f33a239bd1fe327c1e` is not passing evidence. Its preserved XML was independently inspected: 91 tests, six PageCapture failures, zero skips, with `Activity.recreate` reached from the real preference callback on the instrumentation thread, including teardown. Classification: deterministic test-harness thread-affinity defect (quality-strategy category 3), repaired by the source change at the audited head rather than a blind rerun. This audit does not reuse an approval from that failed head.

Limits: this audit proves the bounded canonical page repair and its CI surface, not the distinct #155 QSB-AC-06 Loader-deletion RED, QSB-AC-07 manual A7/A8 journey, physical-device behavior, or #516 Phase 4 cutover. No schema/codec migration was added or exercised by this change. Existing revision/recovery comparison remains fail-closed; blanket cross-version compatibility is not asserted. Publication of this audit and the resulting `high-risk-evidence` check, current-head review/owner decision and merge operation remain the parent workflow's responsibility. Only the permitted docs-only evidence delta may follow this audited source head without a new source audit; substantive source/test/contract changes require current-head re-review and renewed audit as applicable.
