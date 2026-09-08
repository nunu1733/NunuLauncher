# High-risk audit: PR #243 Organizer destination anchor-specific display

> Status: accepted
> Audit date: 2026-09-07

- Auditor: Independent audit session (general-purpose subagent), not the implementing session
- PR: https://github.com/nunu1733/NunuLauncher/pull/243
- Head SHA: c790a78d55f8758f438a05c58845f761a86c69f4
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34170503045 (pull_request-triggered `.github/workflows/ci.yml` run, attempt 2, completed/success, all 13 jobs green — see Findings for attempt 1)
- Criteria: specs/234-organizer-destination-anchor-specificity/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8

## Scope

The audited diff is the single PR head commit `c790a78d55f8758f438a05c58845f761a86c69f4` (verified locally via `git rev-parse HEAD` on branch `agent/issue-234-destination-anchor-specificity` with a clean tree, and remotely via `gh pr view 243 --json headRefOid` — both return the same 40-hex SHA; GitHub reports the PR as OPEN against `main`). The diff is 15 files, +334/−122: two production projection/formatter files, one production wording implementation, two string resources, seven test files, and three spec/plan documents.

High-risk classification: the PR carries no risk labels, but `tools/repo-contract/validate_high_risk_evidence.py` classifies it high-risk through its path backstop — the diff touches `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` and `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt`, both under the `lawnchair/src/app/lawnchair/organizer/application/` high-risk prefix. This record discharges that gate.

Runtime write-path review (independent diff reading, not trusting the PR description):

- Production changes are confined to the presentation layer: `PlanPreview.kt` (additive `columnOrdinal` field with KDoc on `PreviewPosition.Workspace`), `PlanPreviewProjector.kt` (single added line `columnOrdinal = position.cellX + 1` inside `PositionContext.workspacePosition`), `OrganizationPreviewContent.kt` (new `workspaceDestination` wording property and new `destinationText()` function, both move branches rerouted to it), `ManualOrganizationPreferences.kt` (mechanical `ResourceOrganizationPreviewWording`/factory follow-through), and the two `strings.xml` files (new `manual_organization_preview_workspace_destination` in `values/` and `values-ja/`).
- No planner, apply, protocol, persistence, DB, migration, backup/restore, manifest, gradle, permission, network, or transport file appears in the diff (`git diff --name-only main...c790a78d55` enumerated 15 files, all presentation/tests/docs). The change is zero-write; nothing in it can mutate persisted home layout, recovery state, or schema.
- Destination-only formatter contract verified by hunk inventory: `git diff -U0` on `OrganizationPreviewContent.kt` shows exactly 4 hunks (wording interface +6 lines, the same-band move branch, the normal move branch, and the new `destinationText` function). The bodies of `positionText`, `descriptorText`, the `DescriptorKey` collision-supplement path, `newFolderRowText` (still `positionText(change.placement, wording)` at line 352), and `rowOrdinalNote` have zero diff. At head, `destinationText` is called only from the two move branches (lines 288 and 290); non-workspace destinations hit `else -> positionText(destination, wording)`, preserving dock/folder/app-pair copy verbatim.
- Equality-test diff review: `PreviewApplyPlacementEqualityTest` and `PreviewApplyPersistedPlacementEqualityTest` changes are mechanical `PreviewPosition.Workspace` constructor updates (added `columnOrdinal`) plus comment updates; the cell-exact chain is retained and the persisted test additionally gains `assertEquals(destination.columnOrdinal, cell.x + 1)`. The old assertion that two distinct anchors project to the identical destination was necessarily replaced (it characterized the pre-fix R1 FAIL state); no assertion was relaxed.

## Criteria check

| Accepted criterion | Independent check | Result |
|---|---|---|
| AC-1 (anchor uniqueness, 3 layers) | Projection layer: `DestinationRegionMappingTest` flipped to `f03ShapeDistinctResolvedAnchorsProjectToDistinctDestinations` (asserts `Workspace(2,false,TOP,LEFT,1,1)` vs `…,1,2)`, `assertNotEquals`, and `visibleCandidates` collapsing to `{(0,0)}` after filtering by both ordinals) and `projectionKeepsTheColumnCoordinateInsideABand`; new `projectedDestinationIdentifiesTheAnchorOnEveryColumnCount` parameterizes 2/4/5 columns and asserts a single-anchor candidate set for every column — with no UI formatter dependency (imports and fixture code touch only `PlanPreviewProjector`). Formatter layer: `OrganizationPreviewContentTest` asserts distinct copies for same-band different-column rows (`…, row 1, column 1` vs `…, row 1, column 2`) with ordinals matching projection values. Rendered card layer: AC-7 instrumentation. Non-workspace destinations are excluded per the re-review 2 scoping and pinned by `nonWorkspaceDestinationsKeepExistingWording` (dock slot 2 / folder "Work" position 2 / app pair "Pair" copies unchanged). No raw `cellX`/`cellY` value or `ItemId` appears in any new copy (1-based ordinals only). | Pass |
| AC-2 (same-band column adjustment, page included) | `sameBandAdjustmentUsesDedicatedRowAndOrdinalNoteWhenRowsDiffer` now includes a pure column-direction case (same row ordinal, column ordinal 1→2) rendering `position adjusted within top center, page 1, row 1, column 2` with no row note — the #195 D5 residual is closed for the destination part. The re-review boundary fixture `sameBandAdjustmentsOnDifferentPagesRenderDistinctDestinationText` renders page 1 and page 2 same-band rows with different destination texts (`assertEquals(2, rows.toSet().size)`), so cross-page same-band adjustments cannot collapse. Page is unconditionally present in both branches. | Pass |
| AC-3 (single derivation, destination-only path) | `columnOrdinal` is derived exactly once, as `position.cellX + 1` in `PlanPreviewProjector.PositionContext.workspacePosition`; `destinationText` reads only `destination.rowOrdinal`/`destination.columnOrdinal` from `PreviewPosition` and never recomputes coordinates (no `deviceCapabilities` or grid math in the formatter). Specificity is granted only on the destination-only path; `positionText` and the #208 collision-local supplement conditions are unchanged (zero diff, verified above), as is `newFolderRowText`. | Pass |
| AC-4 (placement equality non-regression, test responsibility split) | Both equality tests pass in the independent local run (1 test each, 0 failures). Diff review confirms mechanical constructor updates only, with the cell-exact persisted chain strengthened (`rowOrdinal == cell.y + 1` retained; `columnOrdinal == cell.x + 1` added). `DestinationRegionMappingTest` is now a projection-layer claim that never consults the UI formatter, and rendered-copy claims live in `OrganizationPreviewContentTest` — the layer separation required by owner review point 3. | Pass |
| AC-5 (branch stability R5) | `rowOrdinalNote` and its branch condition are unchanged; the normal-move and same-band branches differ only in the destination source (`destinationText`). `sectionsAreDeterministicForIdenticalDetails` (line 541 at head) is untouched by the diff and passes in the local run. Branch fixtures for normal move, same-band with and without row note are present in `OrganizationPreviewContentTest`. | Pass |
| AC-6 (en/ja copy, #123) | `manual_organization_preview_workspace_destination` added to both `values/strings.xml` (`%1$s, %2$s, row %3$d, column %4$d`) and `values-ja/strings.xml` (`%2$s・%1$s・%3$d行目・%4$d列目`): four positional placeholders each, identical argument mapping (%1=region, %2=page, %3=row ordinal, %4=column ordinal; textual order differs per locale, which positional `%n$` exists for). `ResourceOrganizationPreviewWording` and the test `TestWording` both resolve the new property, so ja cannot fall back at runtime. The instrumentation fallback-detection test `japaneseResourcesResolveEveryConcretePreviewString` lists the new string and passed in the CI issue52 lane. | Pass |
| AC-7 (rendered card evidence) | `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` is inverted to `distinctAnchorsInsideOneBandRenderDistinctDestinationTextOnTheCard`: the fixture keeps page-2 anchors (0,0) vs (1,0) in one band and now asserts two DIFFERENT displayed texts (`…, row 1, column 1` / `…, row 1, column 2`), each built from the real resources via the new `workspaceDestination` helper; `assertEquals(0, application.applyCalls)` retained. The containing class `ManualOrganizationPreferencesInstrumentationTest` runs in the `organizer-instrumentation-issue52-tests` lane, which concluded `success` in run 34170503045 (attempt 2) on the audited SHA. | Pass |
| AC-8 (a11y and privacy) | The move row remains a single composed string node in all new expectations (one `onNodeWithText` per row); the 200% font-scale tests (`Density(1f, fontScale = 2f)`, lines 318 and 1194 of the instrumentation test) are unchanged and executed in the passing issue52 CI lane. Privacy: the new destination copy contains only region/page words and 1-based ordinals — no raw cell coordinates, `ItemId`, package, or component (verified across the new string resources and every updated test expectation). | Pass |

## Executed test surface

Independence and target verification (all executed by this audit session):

```bash
git status                 # clean tree on agent/issue-234-destination-anchor-specificity
git rev-parse HEAD         # c790a78d55f8758f438a05c58845f761a86c69f4
gh pr view 243 --repo nunu1733/NunuLauncher --json headRefOid   # same SHA, PR OPEN
git diff --name-only main...c790a78d55   # 15 files, all presentation/tests/docs
gh pr diff 243 --repo nunu1733/NunuLauncher --name-only
```

Local re-execution on the audited commit (JDK 21, Android SDK Platform 36.1):

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# BUILD SUCCESSFUL in 43s (386 actionable tasks)
# JUnit XML summary (build/test-results/testLawnWithQuickstepGithubDebugUnitTest):
#   88 organizer test classes, 957 tests, 0 failures, 0 errors, 0 skipped
#   DestinationRegionMappingTest: 5 tests, 0 failures
#   OrganizationPreviewContentTest: 22 tests, 0 failures
#   PlanPreviewProjectorTest: 24 tests, 0 failures
#   PreviewApplyPlacementEqualityTest: 1 test, 0 failures
#   PreviewApplyPersistedPlacementEqualityTest: 1 test, 0 failures

./gradlew spotlessCheck
# BUILD SUCCESSFUL
```

CI evidence verified through GitHub's records (not through any implementing-session claim):

```bash
gh api repos/nunu1733/NunuLauncher/actions/runs/34170503045
# event=pull_request, path=.github/workflows/ci.yml, head_branch=agent/issue-234-destination-anchor-specificity,
# head_sha=c790a78d55f8758f438a05c58845f761a86c69f4 (exact match), status=completed, conclusion=success,
# run_attempt=2, pull_requests=[243]
gh api 'repos/nunu1733/NunuLauncher/actions/runs/34170503045/jobs?per_page=100'
# all 13 jobs success: final-status, organizer-unit-tests, check-style, build-debug-apk,
# validate-repo-contract, changes, and all seven organizer-instrumentation-* lanes
# (issue52, issue53, issue99, issue155, api35, db-migration, shared-writer)
```

The instrumentation suite was not re-run locally (no emulator was engaged in this audit session); for AC-7/AC-8 the passing `organizer-instrumentation-issue52-tests` lane on the exact audited SHA is the evidence, per the plan's verification table.

## Findings

- Verdict: GO. The diff satisfies all eight acceptance criteria of the accepted spec `specs/234-organizer-destination-anchor-specificity/spec.md`; the runtime write-path review found no planner/apply/persistence/migration change and no permission/network addition; local re-runs (957 organizer unit tests, spotlessCheck) and the GitHub-verified CI run on the exact head SHA are green.
- CI attempt history (recorded for transparency): attempt 1 of run 34170503045 failed in the `organizer-instrumentation-issue53-tests` lane (failed step: "Run Issue 53 onboarding proposal instrumentation (API 36 / Platform 36.1)"), which failed `final-status`; the run was re-executed and attempt 2 — on the identical head SHA `c790a78d55f8758f438a05c58845f761a86c69f4` — is fully green (13/13 jobs). The failing lane exercises the Issue 53 onboarding proposal feature, not the paths changed by this PR, and no failure relates to the audited change. Attempt 2 is the qualifying merge-gate evidence. The PR body should record this re-run.
- The PR's `high-risk-evidence` check is currently failing solely because this audit record was not yet on the branch at the time of the check run. Once this file lands as a docs-only commit on the PR head, the gate is satisfiable: `validate_high_risk_evidence.py` permits docs-only commits after the audited Head SHA. No code change is required.
- The spec frontmatter at the audited head reads `status: accepted`; the plan's documentation checklist defers the `implemented` flip to merge time. Flip it when the PR merges (docs-only change, no re-audit needed).
- Residual risk already recorded in the spec (non-goal): multiple new folders placed in different cells of the same band still render identical placement wording; the spec requires a separate issue if observed. No action in this PR.
- Local verification environment note: the local unit-test run used Gradle build cache (`17 executed, 369 up-to-date` tasks); the compile and test tasks for the changed sources executed and the JUnit XML results were independently summed by this session from `build/test-results/testLawnWithQuickstepGithubDebugUnitTest/`.
