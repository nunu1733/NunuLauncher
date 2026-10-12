# Issue #579 folder-child sentinel fix handoff

Status: implementation committed locally; independent review, exact-head CI,
high-risk audit, and Issue #516 follow-up remain pending.

This packet records the implementation diff through `05c194843596efa5b9e46b519c3ee7e3197edb37`.
The packet itself is a separate documentation-only child commit; the worker
handoff records that containing commit as the branch's exact review head.

## Review / handoff packet

- **Issue and comments:** [#579](https://github.com/nunu1733/NunuLauncher/issues/579),
  retrieved `2026-10-12T08:45:56Z`; state `OPEN`; labels none; comments empty.
- **Scope:** bug repair. The issue body names accepted owners and the bounded
  algorithm; it has no separate owner-acceptance comment.
- **Accepted Spec 13:** `specs/13-safe-layout-application/spec.md` at
  `805c36d41c6835afd55052e5557cfd3c7d4f391a`.
- **Accepted plan 13:** `specs/13-safe-layout-application/plan.md` at
  `ba299cf06c7dfffda198ff440c0800c5a9ffb63e`.
- **Accepted Spec 269:** `specs/269-folder-workspace-representability/spec.md`
  at `e42d2f73cb2340c1f4d4737ccc8afab83a75273`.
- **Accepted plan 269:** `specs/269-folder-workspace-representability/plan.md`
  at `2b7a2702c464538a52731bc52e4141b6f3802784`.
- **Bug oracle:** Issue body at the link above, retrieved at the timestamp above;
  the issue pins the observed source to `f2592577f9675a5153ffa5819651e1001b5c518e`.
  The rejected `cellX/cellY=-1/-1` is the ordinary Launcher folder-child row.
- **Base SHA:** `7985a45a6e0d14977bd0f0c155882de65756254c` (`origin/main`, verified).
- **Implementation head SHA:**
  `05c194843596efa5b9e46b519c3ee7e3197edb37`.
- **Diff:** local compare `7985a45a6e0d14977bd0f0c155882de65756254c..05c194843596efa5b9e46b519c3ee7e3197edb37`;
  remote compare unavailable because this branch has not been pushed. Full diff
  inspected: 8 files changed, 280 insertions(+), 7 deletions.
- **Changed paths:**
  `lawnchair/src/app/lawnchair/organizer/application/canonical/PersistenceManifest.kt`,
  `tests/unit/app/lawnchair/organizer/application/store/RecoveryRecordCodecTest.kt`,
  `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt`,
  `specs/13-safe-layout-application/spec.md`,
  `specs/13-safe-layout-application/plan.md`,
  `specs/269-folder-workspace-representability/spec.md`,
  `docs/engineering/ci-test-portfolio.md`, and
  `tools/repo-contract/ci_portfolio_map.yml`.

## Change and acceptance evidence

`PersistentRow` preserves SQL `NULL` and non-negative cells and admits paired
`-1/-1` only for a candidate non-negative container. `PersistenceManifest`
requires that container to identify a distinct existing schema-33 folder row
with the same profile. Mixed and other negative cells, desktop/Hotseat
containers, orphan/non-folder/AppPair/self parents, and cross-profile parents
remain rejected. The raw signed values are serialized and recovered without
normalization; public `FolderChild` placement stays parent/rank only.

| Acceptance condition | Evidence |
|---|---|
| Reproduce constructor rejection before the fix | The focused recovery-codec suite failed on base `7985a45a6e0d14977bd0f0c155882de65756254c` in the two added sentinel tests at `PersistentRow` construction; the other existing cases passed. |
| Preserve paired sentinel, SQL `NULL`, and positioned child values | `RecoveryRecordCodecTest.folderChildUnsetCellSentinelRoundTripsWithNullAndNonnegativeCells`; post-fix result: 11 tests, 0 failures. Handwritten expected pairs and raw cell/span assertions are used. |
| Keep invalid container/parent/value shapes fail-closed | `RecoveryRecordCodecTest.folderChildUnsetCellSentinelRejectsInvalidContainersAndParents` covers desktop, Hotseat, mixed/null/`-2`, orphan, non-folder, AppPair, cross-profile, and self-parent cases. |
| Verify real SQLite capture/materialization/recapture ownership | Existing `RealAdapterRowMatrixInstrumentationTest.folderWidgetProfileAndLockRoundTripExactly` now carries both NULL and sentinel children through SQLite capture, materialization, and recapture. The class is already in the explicit `organizer-instrumentation-reservation-recovery-tests` filter; no class, lane, or filter was added. Device execution remains pending. |
| Keep private recovery compatibility and rollback limits explicit | Plan 13 appendix records that wire format/schema/migration do not change, but pre-fix readers reject the new sentinel value. Rollback must use a `main` build containing this correction; release 45 is not claimed compatible. |

## Executed validation

- RED command on the base revision:
  `ANDROID_HOME=/Users/nunu/Library/Android/sdk ./gradlew --no-watch-fs --no-configuration-cache --console=plain testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryRecordCodecTest'`
  → two new sentinel tests failed at the pre-fix constructor; existing cases
  passed.
- GREEN command on the fix:
  `ANDROID_HOME=/Users/nunu/Library/Android/sdk ./gradlew --no-watch-fs --no-configuration-cache --console=plain testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryRecordCodecTest'`
  → `BUILD SUCCESSFUL`; 11 tests, 0 failures. A final qualifying rerun after
  import formatting compiled the changed production and unit-test Kotlin source
  tasks; Gradle marked the already-passed test task `UP-TO-DATE`.
- `ANDROID_HOME=/Users/nunu/Library/Android/sdk ./gradlew --no-watch-fs --no-configuration-cache --console=plain spotlessCheck`
  → `BUILD SUCCESSFUL`.
- `python3 tools/repo-contract/validate_ci_portfolio.py` → CI portfolio
  validation OK.
- `python3 tools/repo-contract/test_validate_ci_portfolio.py` → 21 tests OK.
- `python3 tools/repo-contract/validate_repo_contract.py` → repository
  contract OK.
- `python3 tools/repo-contract/test_validate_repo_contract.py` → 13 tests OK.
- `git diff --check` → pass.

## Unverified work and next step

The real SQLite instrumentation method was extended but not run. No device,
instrumentation lane, APK build, GitHub CI, independent review, or high-risk
audit was run in this worker task. The correction is not yet ported to the
Lawnchair 16 validation slice and has no physical capture/TalkBack evidence;
those remain under Issue #516 and parent coordination. Repository review,
exact-head `final-status`, and the separate high-risk audit are still required.

The recovery wire shape and version are unchanged, but a pre-fix reader rejects
paired negative cells. Do not claim old release 45 can read a checkpoint that
contains this sentinel. No fallback rewrite or device-row mutation is part of
this fix.
