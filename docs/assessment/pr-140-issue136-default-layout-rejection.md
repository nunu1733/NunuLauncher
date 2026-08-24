# High-risk audit: PR #140 compose ordinary default layout into planner-valid input

> Status: accepted
> Audit date: 2026-08-24

- Auditor: independent agent session (not the implementing session)
- PR: https://github.com/nunu1733/NunuLauncher/pull/140
- Head SHA: 7b3574f6dc92bdb45cfe334c6584bea26648cd28
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32737474547
- Criteria: specs/83-production-organization-input-sources/spec.md FR-002, NFR-002, NFR-007, AC-3, AC-4, AC-5, AC-6; specs/12-deterministic-full-layout-planner-v1/spec.md AC-4, AC-7

## Scope

PR #140 carries `risk: layout-data`, `Closes #136`, and is a single commit
`7b3574f6dc92bdb45cfe334c6584bea26648cd28` on branch
`issue-136-default-layout-planning-rejection`; the working tree was clean at
the audited head. Changed files:

- `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt`
  — schema-33 favorites row ↔ canonical/manifest conversion. Capture now keeps
  hotseat `SCREEN` lossless in `PersistentRow.screenId` (NULL read as slot 0,
  mirroring `Cursor.getInt`) for `CONTAINER_HOTSEAT` rows and derives
  `PlacementState.Dock` from that slot instead of `RANK`; `RANK` stays a
  passthrough column. `values()` already wrote `screenId` back to `SCREEN`, so
  the write side needs no codec change.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  — `rowFor` mirrors the capture when materializing dock rows: slot into
  `SCREEN`, original `RANK` preserved via `base?.rank ?: 0`; planned-page
  numbering (`maxPage`) is scoped to `CONTAINER_DESKTOP` rows only, which is
  required now that hotseat rows carry a numeric-looking `screenId`.
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  — `mapItem` assigns `folderId`/`appPairId` only to the `FOLDER`/`APP_PAIR`
  item itself (id-matched), never to members; members stay linked through
  placement and the parent member list.
- Tests: `tests/unit/app/lawnchair/organizer/integration/DefaultLayoutComposerPlannerRegressionTest.kt`
  (new JVM composer→planner regression over the 15-item default-layout shape)
  and `tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt`
  (new production-seam case `defaultLayoutRowsCaptureSlotsFromScreenAndComposeIntoValidPartition`
  over real schema-33 rows; the pre-existing quiet/private/disabled/unavailable
  case was relocated below it with its body intact, not removed).

Runtime write-path relevance: `RowManifestCodec.values()` is the sole column
writer used by `applyWriteSet`/recovery actions inside the adapter transaction;
this change alters which columns carry dock position for preserved and planned
dock rows (slot in `SCREEN` instead of `RANK`-only with `SCREEN` coerced to 0),
and the composer change alters only the in-memory planner input. Verified
independently: `lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt`
has an empty diff against `origin/main` — no validation rule was weakened; no
DB schema/migration change (`PersistenceManifest` stays format 1 / schema 33);
no recovery-mechanism or UI change; no item drop/duplication/rewrite fallback
was introduced.

Defect mechanics were re-derived from Launcher3 authority sources at the
audited head, not taken from the PR text: `LoaderCursor.checkItemPlacement`
(`src/com/android/launcher3/model/LoaderCursor.java`, hotseat branch uses
`item.screenId` as the occupied slot) and `GridSizeMigrationUtil.loadHotseatEntries`
(`src/com/android/launcher3/model/GridSizeMigrationUtil.java`, reads
`Favorites.SCREEN` into `DbEntry.screenId`) establish `SCREEN` as the hotseat
slot authority. Under the pre-fix mapping: four default-layout dock rows all
carry `RANK = 0`, so reading Dock from `RANK` yields four `Dock(0)` → the single
`duplicateDockRank` OVERLAP reason; `mapItem` left the FOLDER item's own
`folderId` null (1 KIND_TARGET_MISMATCH) and set `folderId` on each of the 7
APPLICATION members (7 more mismatches = 8) while `folderParents` stayed empty,
dangling each of the 7 members — reproducing the reported
`PLANNING_INVALID.OVERLAP reasons=16` split (1 + 7 + 8) against the unchanged
validator. A secondary pre-fix hazard was also confirmed in source: the old
`rowFor` wrote preserved dock rows with `SCREEN` coerced to 0 by `values()`,
which would have corrupted the slot authority of any applied dock layout; the
fix makes preserved dock rows round-trip losslessly.

## Criteria check

Issue #136 acceptance items, mapped to evidence (marking what this session
re-executed vs. cites):

- **Isolation of the owning boundary — satisfied, independently re-derived.**
  See Scope: the capture-side slot authority and composer contract inversion
  were re-verified against `LoaderCursor`/`GridSizeMigrationUtil` and the
  unchanged `checkContainerIntegrity`/`checkKindTargetMismatch` rules; target
  composition (`full-target-v1`) needed no change, matching the PR's isolation
  claim.
- **Production-seam regression reproducing before / verifying after —
  satisfied; before-fix evidence is CITED, after-fix evidence RE-EXECUTED.**
  The implementing session reports the new JVM regression failed pre-fix with
  exactly `DANGLING_REFERENCE ×7 + KIND_TARGET_MISMATCH ×8`; this session did
  not revert-and-rerun that claim, but the counts are arithmetically implied by
  the old mapping against the unchanged validator (Scope). After the fix, the
  regression asserts Ready composition, `Planned` over all 15 items, exact
  partition roles, dock slots [0,1,2,3], and folder identity on the container
  item only — re-executed green by this session (see Executed test surface).
  The production-seam instrumentation case (real schema-33 fixture rows →
  `RowManifestCodec.capture` → compose → partition) ran green in CI lane
  `organizer-instrumentation-api35-tests` of the referenced run.
- **Malformed/unsafe layouts remain typed fail-closed with no write —
  satisfied.** Validation code is untouched; dock slot bounds
  (`PlanningValidation` rejects `rank < 0 || rank >= hotseatSlots`) and the
  duplicate dock-rank OVERLAP check now operate on SCREEN-derived slots, so a
  genuinely malformed dock still rejects typed; unknown lock still fails
  composition (`mapItem` returns null → `InvalidCanonicalCapture`). Existing
  rejection-path organizer tests ran green unchanged in the re-executed suite.
- **No fallback silently drops, duplicates, or rewrites unsupported items —
  satisfied.** Conservation is asserted exactly (15 distinct items, once each)
  in both regressions; `UnsupportedContainer` handling, lock/profile mapping,
  and availability mapping are unchanged in source; the write side now
  preserves original dock `RANK`/`SCREEN` instead of rewriting them.
- **Relevant organizer tests, release build, formatting, `final-status` pass —
  satisfied with one cited-only element.** Organizer unit tests (724), spotless,
  repo-contract, and the CI merge gate (`final-status` + source jobs + all six
  instrumentation lanes) were re-executed green by this session on the audited
  head; the R8/minified release assembly remains implementer-reported only
  (see Findings).
- **`risk: layout-data` label + independent audit evidence — satisfied.** Label
  verified via the GitHub API; this record provides the independent audit.

Spec criteria:

- **specs/83-production-organization-input-sources/spec.md AC-3 (one canonical
  capture) — pass.** The composer still consumes exactly one application
  capture (`CapturedSnapshot` via the capture port); no second snapshot, UI DB
  access, or planner duplication was added; both regressions drive the
  production seam (`FakeLayoutWriter.captureCurrent` /
  `RowManifestCodec.capture` over a real schema database).
- **AC-4 (complete conservation input) — pass.** Exact-once partition of all
  captured items asserted in both regressions; `additions` stays explicitly
  empty; the target materializer itself is unchanged.
- **AC-5 (fail closed) — pass.** Mandatory-source and malformed-capture
  failure paths are unchanged; genuinely invalid layouts (overlapping dock
  slots, out-of-range slots, broken references, kind/target mismatches)
  continue to produce typed non-write results from the untouched validator.
- **AC-6 (profile and lock safety) — pass.** Profile identity/availability and
  lock truth flow through `mapItem` unchanged except the container-identity
  fields; `OrganizerLockState.UNKNOWN` still aborts composition instead of
  being rounded to unlocked.
- **FR-002 (explicit full-organization target set; out-of-scope items not
  changed) — pass,** via the conservation/partition assertions above and the
  unchanged preservation precedence (dock and structural members Preserved).
- **NFR-002 (integrity: conservation, bounds, overlap, container references,
  lock, profile isolation checked around apply) — pass.** All corresponding
  validator checks are byte-identical (empty diff), and the dock inputs feeding
  them are now sourced from the same column the platform loader enforces.
- **NFR-007 (compatibility across the adopted revision's supported form
  factors/profiles/grids) — pass on source-consistency grounds:** the dock
  representation now agrees with `LoaderCursor`/`GridSizeMigrationUtil` for the
  supported schema-33 phone profile; no orientation-, profile-, or grid-specific
  branching was introduced; broader device-matrix coverage remains tracked by
  the existing compatibility efforts (#108/#132), which this record does not
  replace.
- **specs/12-deterministic-full-layout-planner-v1/spec.md AC-4 (folder
  grouping/partition/rank/cell correctness) — pass:** the planner returns
  `Planned` with all 15 placements, preserved dock targets [0,1,2,3], and the
  folder handled per policy, asserted in the re-executed JVM regression.
- **specs/12 AC-7 (invalid/impossible inputs return the complete typed result)
  — pass:** unchanged validator behavior as recorded under spec 83 AC-5; the
  pre-fix state demonstrates the typed-rejection path firing on bad input
  rather than crashing or mis-writing.

## Executed test surface

Independent checks executed by this audit session on head
`7b3574f6dc92bdb45cfe334c6584bea26648cd28`, branch
`issue-136-default-layout-planning-rejection`, clean tree:

```text
git rev-parse HEAD && git branch --show-current && git status --porcelain
  PASS — HEAD 7b3574f6dc92bdb45cfe334c6584bea26648cd28, correct branch, no local changes

./gradlew spotlessCheck
  PASS — exit 0, BUILD SUCCESSFUL in 1s (run as its own invocation; spotless tasks
         up-to-date against the current sources)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*'
  PASS — exit 0, BUILD SUCCESSFUL in 14s (separate invocation); XML totals across
         62 result classes in build/test-results/testLawnWithQuickstepGithubDebugUnitTest:
         tests="724" skipped="0" failures="0" errors="0";
         TEST-app.lawnchair.organizer.integration.DefaultLayoutComposerPlannerRegressionTest.xml:
         tests="1" failures="0" errors="0" (fresh timestamp from this run);
         app.lawnchair.ui.preferences.navigation.PreferenceRouteRetentionTest included

python3 tools/repo-contract/validate_repo_contract.py
  PASS — exit 0, "repository contract OK"

python3 tools/repo-contract/test_validate_repo_contract.py
  PASS — exit 0, Ran 11 tests ... OK (negative fixtures correctly flagged)

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  PASS — exit 0, Ran 47 tests ... OK

gh api repos/nunu1733/NunuLauncher/actions/runs/32737474547 --jq '{head_sha,event,conclusion,pull_requests}'
  PASS — head_sha=7b3574f6dc92bdb45cfe334c6584bea26648cd28, event=pull_request,
         conclusion=success (attempt 2), pull_requests contains 140

gh api repos/nunu1733/NunuLauncher/actions/runs/32737474547/jobs?per_page=100 --jq '.jobs[] | "\(.name): \(.conclusion)"'
  PASS — all 12 jobs success: changes, organizer-unit-tests, check-style,
         build-debug-apk, validate-repo-contract, final-status, and all six
         organizer-instrumentation lanes (api35, db-migration, issue52, issue53,
         issue99, shared-writer); the api35 lane executes
         ProductionOrganizationInputInstrumentationTest per .github/workflows/ci.yml

git diff origin/main...HEAD -- lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt
  PASS — empty diff (validation rules unchanged)
```

Not executed by this session (per audit scope): connected/emulator tests and
debug/release assemblies locally. Connected instrumentation evidence comes from
the referenced CI run; the release assembly is cited from the PR description
only (see Findings).

## Findings

- **Attempt-1 instrumentation flake and transparent re-run.** Attempt 1 of the
  referenced merge-gate run failed ONLY
  `organizer-instrumentation-issue52-tests`:
  `ManualOrganizationPreferencesInstrumentationTest.capturesManualOrganizationReviewSurfaces`
  threw `java.lang.IndexOutOfBoundsException: Index 2, size 2` from Compose
  foundation internals during screenshot capture. Analysis: that test stubs the
  planner entirely (`OrganizationPlanner { planningResult() }` over a
  `FakeApplication`), so none of the classes changed by this PR are on its code
  path; the diff contains no UI code; the lane was green on today's `main` run.
  Consistent with the shared-emulator/UI flakiness pattern recorded for PR
  #133, THIS audit session requested a re-run of the failed jobs
  (`gh run rerun 32737474547 --failed`; no code or workflow change) and
  attempt 2 went fully green including that lane and `final-status`. The
  referenced run link therefore evidences attempt 2; attempt 1's single-lane
  red is recorded here for the merge-gate history.
- **`High-risk gate / high-risk-evidence` is red until this record lands**
  (run `32737489341`). That is the designed dependency of the gate on the audit
  file and is not counted against the PR.
- **Evidence provenance.** Pre-fix failure reproduction
  (`DANGLING_REFERENCE ×7 + KIND_TARGET_MISMATCH ×8`) and the local release
  build (`assembleLawnWithQuickstepGithubRelease`) are CITED from the
  implementing session's commit/PR description, not re-executed by this audit;
  everything listed under Executed test surface was re-executed here. Residual
  risk of the cited-only items is assessed low: the failure counts are implied
  by the old mapping against the unchanged validator, and the changed code is
  plain Kotlin mapping logic compiled and exercised by the debug CI build and
  the full JVM/instrumentation surface.
- **Residual: end-to-end release-journey confirmation.** The issue's "same
  release user journey reaches a coherent preview/result" is evidenced here at
  the composer→planner and capture→compose→partition seams; the final
  minified-release dogfood confirmation belongs to the parent task #132 and is
  not claimed by this record.
- **Cosmetic, non-blocking.** Relocating the pre-existing
  `productionComposerPreservesQuietPrivateDisabledAndUnavailableProfileWithoutEvidenceFallback`
  case left its opening brace and first statement on one line; `spotlessCheck`
  accepts it locally and in CI. No action required.
- No blocking finding was identified. The verdict is **accepted**: root causes
  match the fix, validation remains fail-closed and byte-identical, the write
  path round-trips preserved dock rows losslessly (removing a latent
  slot-corruption rewrite), and all machine-verifiable evidence is green on the
  audited head.
