# High-risk re-audit: PR #148 decode app-pair snap position from persisted member ranks

> Status: accepted
> Audit date: 2026-08-26

- Auditor: independent re-audit session (not the implementing session, and
  distinct from the earlier audit session whose record this file supersedes).
  This session executed every verification command below itself, reviewed the
  full cumulative diff of both code commits plus surrounding contracts, and
  diagnosed the CI job failures observed on the review-fix head; the
  implementing session contributed none of it.
- PR: https://github.com/nunu1733/NunuLauncher/pull/148
- Head SHA: ca97b1bba7933787306d6e4ce57feedbebe4b922
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32929990557
- Criteria: specs/10-pure-organization-planning/spec.md FR-002, NFR-002; specs/83-production-organization-input-sources/spec.md FR-002, NFR-002; specs/12-deterministic-full-layout-planner-v1/spec.md AC-2, AC-5, AC-7

## Scope

This record is a **re-audit** and supersedes the previous independent audit
pinned at `f25481dafb10d6f0033d7f096bb18ce14f8f9a18` (CI run 32923175368):
after that audit was recorded, the implementing session pushed a CODE commit
(`ca97b1bb`, a requested-changes fix), so per
`tools/repo-contract/validate_high_risk_evidence.py` the earlier pin no longer
covers the head. This session re-executed everything against the new head.

PR #148 carries `risk: layout-data`, `Closes #141`, and targets branch
`issue-141-app-pair-snap-position`. The PR's commits under audit:

- `f25481dafb10d6f0033d7f096bb18ce14f8f9a18` — initial fix (+407/-16, four
  files): app-pair capture decodes each member row's persisted rank with the
  platform encoding `(splitPosition shl 16) + snapPosition`
  (`APP_PAIR_STAGE_BITS = 16`, snap domain {0, 1, 2} mirrored from
  `SplitScreenConstants`); well-formed pairs get faithful per-member stages
  plus one shared `OptionalSnapPosition.Present(SnapPositionToken)` (value =
  decimal persistent snap half, required equal across both members with
  complementary decoded stages); any other rank shape projects `Absent`. The
  composer stops populating `CapturedItem.members` for `APP_PAIR` items and
  threads the captured token into both `AppPairMember`s (`Absent → null`).
  Instrumentation and JVM regressions cover present-token acceptance,
  undecodable ranks (legacy plain ranks, out-of-domain snap halves, mismatched
  snaps) staying typed `MALFORMED_APP_PAIR`, lossless manifest ranks, and
  preserved partition/placement.
- `4024385835a44af60d2078655de066ac58b20904` — docs-only commit (the
  superseded audit record; no production surface).
- `ca97b1bba7933787306d6e4ce57feedbebe4b922` (review fix, 11 files,
  +210/-62) — resolves the blocking finding in issue #141's "Review result:
  Changes requested" comment: `require(children.size == 2)` made degenerate
  APP_PAIR rows crash canonicalization with `IllegalArgumentException` instead
  of reaching the planner's typed `MALFORMED_APP_PAIR`. The implementer's reply
  (issue comment 5420570552) chose representability in canonical state. This
  session verified each change against the surrounding contracts:

  - `application/public/LayoutState.kt`: `StructureState.AppPairMembers`
    becomes `members: List<AppPairMemberState>` (item ref + stage per decoded
    row, carried in persisted rank order) + `snapPosition`; the list is not
    restricted to two entries.
  - `application/adapter/RowManifestCodec.kt`: the `require` is gone. Children
    come from `childrenByParent[...]` sorted by rank before projection, so
    member order is deterministic. The shared snap token is adopted only for an
    exactly-two-member pair whose ranks both decode inside the persisted
    encoding with complementary stages and equal snaps; everything else carries
    `Absent` (no invented value). Every child row projects via `appPairStage()`
    (decode-first, legacy rank==0 fallback unchanged from the initial fix).
  - `integration/OrganizationInputComposer.kt`: projects every member row 1:1
    into `AppPairMetadata.members`; non-PersistentItem refs fail closed
    (`return null`). Cardinality and stage/snap coherence stay planner-owned
    (V-07). `CapturedItem.members` stays empty for pairs.
  - `application/canonical/CanonicalMarshalling.kt`: the digest now writes a
    size prefix followed by item ref + stage byte per member, then the snap
    position — total and delimiter-free over degenerate cardinalities.
  - `locks/EffectiveLocks.kt`,
    `application/adapter/LauncherLayoutAdapter.kt`
    (`resolvePersistentReferences`),
    `application/protocol/MaterializedStateValidator.kt`: mechanical,
    order-preserving list mapping replacing first/second accessors. A repo-wide
    grep confirms zero remaining consumers of the removed accessors.
  - Tests: new JVM regression
    `DefaultLayoutComposerPlannerRegressionTest.appPairWithAbnormalMemberCountStaysTypedMalformed`
    (0/1/3 members → composition Ready → exactly one `MALFORMED_APP_PAIR`
    naming ItemId("70")); new instrumentation regression
    `ProductionOrganizationInputInstrumentationTest.appPairRowWithAbnormalMemberCountStaysTypedMalformedAtThePlanner`
    (schema-33 rows with 0/1/3 member rows inserted through the refactored
    `insertAppPairFixture(memberRanks: List<Int>)` → capture throws nothing,
    structure lossless, composition Ready, planner rejects typed).

No DB schema/migration, recovery-store, or permission surface changes in
either commit; the risk path remains planner input composition and canonical
representation only.

Independent re-derivation performed by this session on the review-fix head:

- Planner trace of the degenerate fixtures against
  `PlanningValidation.checkMalformedAppPair`: `members.size != 2` flags
  malformed exactly once per pair; reverse-membership comparison matches for
  each fixture (0==0, 1==1, 3==3 member ids); `KIND_TARGET_MISMATCH` does not
  fire (parent target/appPairId/appPair stay coherent, including the empty but
  non-null metadata case); overlap and container-integrity checks are
  unaffected because pair members carry `AppPairMember` placements, not
  workspace cells. Exactly one `MALFORMED_APP_PAIR` naming the pair id results
  — matching both new tests' assertions, which passed locally and in CI.
- No silent-pass path: grep confirms the only production constructors are
  codec capture (member list ↔ child rows' `AppPairChild` placements agree by
  construction, same sorted source list) and the composer's 1:1 projection;
  every other touchpoint maps an existing structure without reordering.
- Digest-format safety: classification digests are computed fresh and compared
  only within a process generation (capture vs apply/reconcile recomputation,
  all with one binary's marshalling); the organizer subsystem has never shipped
  in a release, so the sized-list format change has no cross-version or
  persisted-digest compatibility surface.

## Criteria check

- specs/10-pure-organization-planning/spec.md (status: accepted) — FR-002,
  NFR-002. The identity table defines `SnapPositionToken` as a non-empty String
  backed by the "encoded rank", and `CapturedItem.members` as folder child ids
  iff kind = FOLDER. At the audited head, captured pairs satisfy the documented
  AppPairMetadata validity rules without inventing values: exactly two members
  with complementary persisted stages, distinct ids, value-equal present
  tokens; membership expressed solely via `AppPairMetadata.members` plus each
  member's `AppPairMember` placement (FR-002 target-set/preservation semantics
  unchanged — pairs and members stay in the captured inventory and remain
  Preserved). Integrity validation (NFR-002, V-07) now covers degenerate
  cardinality as well: malformed member counts reach the planner and are
  rejected typed instead of throwing at capture, verified against
  `checkMalformedAppPair` and both new regressions.
- specs/83-production-organization-input-sources/spec.md (status:
  implemented) — FR-002, NFR-002. The composition seam must project the fresh
  canonical capture into `LayoutSnapshot` losslessly and partition every
  captured item exactly once, never rounding invalid state into silent
  preservation. Capture supplies the authoritative persisted snap value where
  the persisted encoding carries one, keeps raw encoded ranks lossless in the
  manifest otherwise, and fails closed downstream — including degenerate
  member counts, exercised through the real production seam (capture → compose
  → plan) in the instrumentation regression rather than a test-only path.
- specs/12-deterministic-full-layout-planner-v1/spec.md (status: accepted) —
  AC-2, AC-5, AC-7. AC-7: invalid inputs return the complete typed P-01 result
  without partial plan or exception — the absent-token cases reject with
  exactly one `MALFORMED_APP_PAIR` naming the pair, and the review-fix cases
  extend this to abnormal cardinality with no capture-time exception. AC-5:
  full materialization preserves existing targets exactly — the accepted-pair
  case yields `Disposition.Preserved` at the captured cell with both members
  retained. AC-2: validation coverage extended twice — rank-decode regressions
  (initial fix) plus degenerate-cardinality regressions (review fix) at both
  the JVM and instrumentation seams.

## Executed test surface

All commands below were executed by this session on commit
`ca97b1bba7933787306d6e4ce57feedbebe4b922`, clean tree:

- `git rev-parse HEAD` → `ca97b1bba7933787306d6e4ce57feedbebe4b922`;
  `git status --porcelain` → empty (clean), re-checked after all executions.
- `git diff 4024385835..ca97b1bba7` reviewed in full (the incremental
  review-fix diff), plus `git diff --stat 718995320c..f25481dafb` to scope the
  initial fix.
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL, exit 0.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`
  → BUILD SUCCESSFUL, exit 0; JUnit XML aggregation parsed by this session:
  61 suites, 725 tests, 0 failures, 0 errors, 0 skipped, including
  `appPairWithAbnormalMemberCountStaysTypedMalformed`.
- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL, exit 0.
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → BUILD
  SUCCESSFUL, exit 0.
- `adb devices` → `emulator-5554 device` (API 36, AVD nunu_qpr2_api36_1)
  reachable.
- `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest`
  → BUILD SUCCESSFUL, exit 0; fresh result XML written during this session's
  run: 15 tests, 0 failures, 0 errors, 0 skipped, covering
  `appPairRowWithAbnormalMemberCountStaysTypedMalformedAtThePlanner`
  (0/1/3-member fixtures) and both initial-fix regressions.
- Diagnostic rerun of the CI-failed class on the same emulator:
  `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest`
  → BUILD SUCCESSFUL, exit 0; 3 tests, 0 failures at this head (see Findings).
- CI merge gate on the exact audited head, verified via GitHub API by this
  session: `gh run view 32929990557` shows event `pull_request`, branch
  `issue-141-app-pair-snap-position`, head SHA
  `ca97b1bba7933787306d6e4ce57feedbebe4b922`, status completed, conclusion
  success, workflow `.github/workflows/ci.yml`; `gh pr checks 148` shows
  `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`,
  `validate-repo-contract`, and all six `organizer-instrumentation-*` jobs
  green (two jobs green after `--failed` reruns — see Findings). The
  failing `high-risk-evidence` check at re-audit time is expected: it gates on
  this record's existence and turns green once this docs-only commit lands
  after the audited head.

## Findings

No defect blocking merge was found in either code commit. Observations recorded
honestly:

- Canonical type ripple assessed and accepted. The sized-list digest changes
  digest values for any state containing app pairs, but digests are compared
  only between values computed by the same binary within one process
  generation (capture vs apply/reconciliation recomputation), and the organizer
  subsystem predates any release, so there is neither an in-place mismatch nor
  a cross-version compatibility surface. Lock-view behavior for degenerate
  pairs (empty locked-member set) is unreachable past planning, which rejects
  them first. Ordering assumptions hold: member order originates from a single
  rank-sorted child list at capture and every later transformation
  (composer projection, reference resolution, materialized-state validation,
  marshalling) preserves list order.
- Capture no longer throws on corrupt pair rows, so the fail-closed
  `CanonicalCaptureReadResult.Invalid` path mapped by
  `LayoutWriterCanonicalCaptureSource` is now unreachable for APP_PAIR
  cardinality defects; those rows instead produce a typed planner rejection.
  This is strictly better aligned with the #141 contract (no exception, typed
  failure, no invention) and is what the review requested.
- Carried observation from the superseded audit, still accurate at this head:
  `Int.appPairStage()` tries the decoded stage first, so a legacy unencoded
  rank of 1 projects canonical stage TOP_OR_LEFT where the old rank == 0
  heuristic projected BOTTOM_OR_RIGHT; the field never reaches the planner and
  such pairs carry no token and are rejected typed anyway.
- Two CI jobs failed on the first execution of run 32929990557 and passed on
  rerun; this session diagnosed both as flakes unrelated to the audited diff
  before accepting the run:
  - `organizer-instrumentation-api35-tests`:
    `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`
    failed its rowsBefore/rowsAfter equality because the planned row (a folder
    child) had its placement/modified columns filled by the Launcher's own
    rotation relayout — exactly the hazard the test's own comment acknowledges
    ("unrelated rows may legitimately change … the launcher fills
    placement/modified on folder children"), except it assumed its own row was
    exempt. The staleness rejection itself worked (STALE_REVISION asserted,
    marker title never landed), proving the organizer did not write. The class
    passes locally at this head (see Executed test surface). Green on first
    `--failed` rerun.
  - `organizer-instrumentation-issue53-tests`:
    `OnboardingOrganizationProposalInstrumentationTest.realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface`
    threw `touch injection never reached the proposal after 3 attempts;
    events=[]; launcherWindowFocus=false` with focus stuck on a TextView, amid
    sustained adb exit-code-1 noise and an emulator-console startup failure on
    the runner — UI-injection/environment flakiness in a file with zero
    intersection with this diff. Green on second `--failed` rerun.
  - Residual follow-up (non-blocking, should be tracked in its own issue,
    separate from #141): the TwoPanel test's no-write assertion needs to
    tolerate launcher-relayout writes to its fixture row, and the flaky
    instrumentation jobs may warrant a bounded retry policy.
- Test-count discrepancy resolved: the superseded audit measured 724 unit
  tests at `f25481d` while the PR body claimed 725; this session measures 725
  at `ca97b1bb`. Both numbers were correct for their respective heads — the
  review fix added one JVM test method. No reporting error remains.
- Residual process note: the `high-risk-evidence` check is red until this
  docs-only record is committed; only docs-only commits may follow the audited
  head per the gate. Nothing else is unresolved.
