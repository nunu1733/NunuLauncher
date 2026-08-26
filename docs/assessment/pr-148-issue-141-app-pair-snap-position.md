# High-risk audit: PR #148 decode app-pair snap position from persisted member ranks

> Status: accepted
> Audit date: 2026-08-26

- Auditor: independent agent session (not the implementing session). This
  record was produced by a fresh audit session that executed every verification
  command below itself and reviewed the full diff and surrounding contracts;
  the implementing session contributed none of it.
- PR: https://github.com/nunu1733/NunuLauncher/pull/148
- Head SHA: f25481dafb10d6f0033d7f096bb18ce14f8f9a18
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32923175368
- Criteria: specs/10-pure-organization-planning/spec.md FR-002, NFR-002; specs/83-production-organization-input-sources/spec.md FR-002, NFR-002; specs/12-deterministic-full-layout-planner-v1/spec.md AC-2, AC-5, AC-7

## Scope

PR #148 carries `risk: layout-data`, `Closes #141`, and targets branch
`issue-141-app-pair-snap-position`. This session confirmed a clean working
tree at HEAD `f25481dafb10d6f0033d7f096bb18ce14f8f9a18` (the single commit of
the PR, +407/-16) before executing anything. The diff touches exactly four
files — two production, two tests:

- `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt`
  - App-pair capture (`toCanonical`) now decodes each member row's persisted
    rank with the platform encoding `(splitPosition shl 16) + snapPosition`:
    well-formed pairs get faithful per-member stages plus one shared
    `OptionalSnapPosition.Present(SnapPositionToken)` whose value is the
    decimal persistent snap half, required equal across both members with
    complementary decoded stages; any other rank shape projects
    `OptionalSnapPosition.Absent`. The previous unconditional
    `OptionalSnapPosition.Absent` is gone for well-formed pairs; out-of-domain
    ranks keep projecting Absent.
  - The pair precondition relaxed from "exactly ranks 0 and 1" to "exactly two
    member rows"; both versions throw at capture on violation.
  - New private decoder helpers mirror the platform constants
    (`APP_PAIR_STAGE_BITS = 16`, snap domain {0, 1, 2}) with an in-code
    rationale comment; child-row projection `appPairStage()` prefers the
    decoded stage and falls back to the legacy `rank == 0` heuristic.
  - The persistence manifest keeps raw encoded ranks untouched (lossless).
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  - `DefaultOrganizationInputComposer.mapItem` passes the captured token
    through to both `AppPairMember`s (`Absent → null`), and stops populating
    `CapturedItem.members` for `APP_PAIR` items (now `emptyList`), matching
    spec #10's "`members` … folder child ids; iff kind = FOLDER".
- `tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt`
  - `appPairMemberRanksCaptureSnapPositionComposeIntoPlannerAcceptedInput`:
    in-memory schema-33 rows with fixture ranks computed from live
    `SplitScreenConstants` through capture → compose → planner `Planned`,
    asserting the shared token, faithful stages, lossless manifest ranks,
    preserved partition (pair + 2 members), and preserved placement at the
    captured cell.
  - `undecodableAppPairRanksStayTypedMalformedAtThePlanner`: legacy plain
    ranks (0, 1), out-of-domain snap halves (3 / 65539), and mismatched snaps
    each yield exactly one `MALFORMED_APP_PAIR` rejection naming the pair.
- `tests/unit/app/lawnchair/organizer/integration/DefaultLayoutComposerPlannerRegressionTest.kt`
  - JVM regressions for both token states: present token composes to
    planner-accepted input with preserved members; absent token stays typed
    `MALFORMED_APP_PAIR`.

No DB schema/migration, recovery-store, writer, or permission surface is
touched; the risk path is planner input composition only.

Independent re-derivation performed by this session (not taken from the PR
body):

- Platform format verified in source:
  `quickstep/src/com/android/quickstep/util/AppPairsController.java`
  (`BITMASK_SIZE = 16`; `encodeRank = (splitPosition << BITMASK_SIZE) +
  snapPosition`; `convertRankToStagePosition = rank >> BITMASK_SIZE`;
  `convertRankToSnapPosition = rank & BITMASK_FOR_SNAP_POSITION`; save path
  writes `encodeRank(SPLIT_POSITION_TOP_OR_LEFT, snap)` /
  `encodeRank(SPLIT_POSITION_BOTTOM_OR_RIGHT, snap)` after coercing
  `SNAP_TO_NONE` to `SNAP_TO_50_50` and guarding with
  `isPersistentSnapPosition`) and
  `wmshell/src/com/android/wm/shell/common/split/SplitScreenConstants.java`
  (`SPLIT_POSITION_TOP_OR_LEFT = 0`, `SPLIT_POSITION_BOTTOM_OR_RIGHT = 1`,
  `SNAP_TO_30_70 = 0`, `SNAP_TO_50_50 = 1`, `SNAP_TO_70_30 = 2`,
  `isPersistentSnapPosition` = exactly {0, 1, 2}, with the "must not be
  changed -- they are persisted" comment). The codec's mirrored constants and
  mask match bit-for-bit over this domain; the codec's unsigned `ushr` equals
  the platform's signed `>>` for all in-domain ranks, and both disagree only
  outside {0, 1} stage halves where the codec fails closed to Absent.
- Decoder math spot-checks: rank 1 = encodeRank(0, 1) decodes TOP_OR_LEFT +
  snap 1 ("1"); rank 65537 = encodeRank(1, 1) decodes BOTTOM_OR_RIGHT + "1";
  rank 3 has snap half 3 ∉ {0, 1, 2} → no token; rank 65539 likewise;
  legacy plain ranks 0/1 decode snaps 0 vs 1 (mismatched) with identical
  stages → Absent. All three malformed fixtures therefore reach the composer
  with `null` member tokens, which
  `PlanningValidation.checkMalformedAppPair` rejects typed (null or unequal
  member tokens are malformed), matching the new tests' expectations.
- Second-defect claim verified against validation source: non-FOLDER items
  with non-empty `members` receive `KIND_TARGET_MISMATCH`
  (PlanningValidation.kt), so the pre-change composer behavior (populating
  `members` for pairs) rejected every app pair regardless of token. The fix is
  required, not cosmetic.

## Criteria check

- specs/10-pure-organization-planning/spec.md (status: accepted) — FR-002,
  NFR-002. The identity table defines `SnapPositionToken` as a non-empty
  String backed by the "encoded rank", and `CapturedItem.members` as "folder
  child ids; iff kind = FOLDER". After this change captured pairs satisfy the
  documented AppPairMetadata validity rules without inventing values: exactly
  two members, complementary stages from the persisted split halves, distinct
  ids, present value-equal tokens, membership expressed solely via
  `AppPairMetadata.members` plus each member's `AppPairMember` placement
  (FR-002 target-set/preservation semantics unchanged — pair and members stay
  in the captured inventory and remain `Preserved`). Integrity validation
  (NFR-002, V-07) keeps rejecting undecodable pairs typed instead of
  normalizing them; strictness is unchanged, verified against
  `checkMalformedAppPair` and the absent-token regression.
- specs/83-production-organization-input-sources/spec.md (status:
  implemented) — FR-002, NFR-002. The composition seam must project the fresh
  canonical capture into `LayoutSnapshot` losslessly and partition every
  captured item exactly once, never rounding invalid state into silent
  preservation. Capture now supplies the authoritative persisted snap value so
  the projection of a saved-pair layout is representable end to end, while
  undecodable ranks stay lossless in the manifest and fail closed downstream;
  the instrumentation regression exercises this production seam (capture →
  compose → plan) rather than a test-only path.
- specs/12-deterministic-full-layout-planner-v1/spec.md (status: accepted) —
  AC-2, AC-5, AC-7. AC-7: invalid inputs return the complete typed P-01
  result without partial plan or exception — the absent-token cases reject
  with exactly one `MALFORMED_APP_PAIR` naming the pair. AC-5: full
  materialization preserves existing targets exactly — the accepted-pair case
  yields `Disposition.Preserved` at the captured cell for the pair with both
  members retained. AC-2: validation coverage corpus extended with app-pair
  rank-decode regressions at both seams (JVM and instrumentation).

## Executed test surface

All commands re-executed by this session on commit
`f25481dafb10d6f0033d7f096bb18ce14f8f9a18`, clean tree:

- `git rev-parse HEAD` → `f25481dafb10d6f0033d7f096bb18ce14f8f9a18`;
  `git status --porcelain=v1` → empty (clean) before any execution.
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL, exit 0.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`
  → BUILD SUCCESSFUL, exit 0; JUnit XML result aggregation by this session:
  61 suites, 724 tests, 0 failures, 0 errors, 0 skipped (includes the two new
  `DefaultLayoutComposerPlannerRegressionTest` cases).
- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL, exit 0.
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → BUILD
  SUCCESSFUL, exit 0.
- `adb devices` → `emulator-5556 device` reachable.
- `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest`
  → BUILD SUCCESSFUL, exit 0; log shows "Starting 14 tests on
  nunu_qpr2_api36_1(AVD) - 16" / "Finished 14 tests" (API 36 emulator),
  covering both new production-seam regressions.
- CI merge gate on the exact audited head, verified via GitHub API by this
  session: `gh run list --workflow ci.yml` shows run 32923175368, event
  `pull_request`, status completed, conclusion success, head SHA
  `f25481dafb10d6f0033d7f096bb18ce14f8f9a18`, branch
  `issue-141-app-pair-snap-position`, associated pull request [148];
  `gh pr checks 148` shows `final-status`, `organizer-unit-tests`,
  `check-style`, `build-debug-apk`, `validate-repo-contract`, and all six
  `organizer-instrumentation-*` jobs green. The
  `organizer-instrumentation-api35-tests` job runs
  `ProductionOrganizationInputInstrumentationTest` in its class filter, so the
  new production-seam regressions also ran in CI on the audited head. The
  failing `high-risk-evidence` check at audit time is expected: it gates on
  this record's existence and turns green once this docs-only file is
  committed after the audited head.

## Findings

No defect blocking merge was found. Observations recorded honestly:

- Minor behavior delta in the child-stage fallback: `Int.appPairStage()` now
  tries the decoded stage first, so a legacy unencoded rank of 1 projects
  canonical stage `TOP_OR_LEFT` where the old `rank == 0` heuristic projected
  `BOTTOM_OR_RIGHT`. This field exists only in the canonical layer — the
  composer drops it when projecting `PlacementState.AppPairChild` to
  `CapturedPlacement.AppPairMember`, and any pair whose ranks lack the
  persisted encoding carries no token and is rejected typed anyway. No
  planner-visible behavior change; noted for future readers of the codec.
- Mirrored constants instead of importing `SplitScreenConstants`: acceptable.
  The in-code rationale (data-format facts kept flavor-independent) holds, and
  drift risk is mitigated because the instrumentation fixture computes its
  ranks from live `SplitScreenConstants`; that class runs in CI
  (`organizer-instrumentation-api35-tests`) and locally on API 36 as above.
- `require(children.size == 2)` throws during capture for corrupt pair rows;
  `LayoutWriterCanonicalCaptureSource` maps the RuntimeException to
  `CanonicalCaptureReadResult.Invalid` → typed `NotReady`, i.e. fail-closed
  and equivalent in effect to the previous precondition.
- Token domain is correctly narrow: free-snap (`SNAP_TO_NONE`) pairs never
  persist that way (save coerces to `SNAP_TO_50_50` before encoding), so an
  absent token genuinely means foreign/corrupt data, and keeping those
  rejected rather than normalized matches the issue's non-goals.
- Counting discrepancy in the implementer's PR body: it claims "725 tests"
  for the unit-test filter; this session measured 724 (0 failures). Immaterial
  reporting difference, no impact on evidence.
- Residual process note: the `high-risk-evidence` check is red until this
  record is committed; only docs-only commits may follow the audited head per
  the gate. Nothing else is unresolved.
