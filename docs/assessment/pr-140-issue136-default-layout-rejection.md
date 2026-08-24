# High-risk audit: PR #140 compose ordinary default layout into planner-valid input

> Status: accepted
> Audit date: 2026-08-25

- Auditor: independent agent session (not the implementing session). This
  record is the re-audit of head `f99ff8cd5e04ab549a917a5b019bd6bcab95a720`
  performed by a fresh independent agent session after review-driven changes;
  the implementing session contributed none of it. The prior-session audit of
  `7b3574f6dc92bdb45cfe334c6584bea26648cd28` (2026-08-24) is retained below as
  clearly attributed history.
- PR: https://github.com/nunu1733/NunuLauncher/pull/140
- Head SHA: f99ff8cd5e04ab549a917a5b019bd6bcab95a720
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32747094320
- Criteria: specs/83-production-organization-input-sources/spec.md FR-002, NFR-002, NFR-007, AC-3, AC-4, AC-5, AC-6; specs/12-deterministic-full-layout-planner-v1/spec.md AC-4, AC-7

## Scope

PR #140 carries `risk: layout-data`, `Closes #136`, and targets branch
`issue-136-default-layout-planning-rejection`; HEAD at this re-audit is
`f99ff8cd5e04ab549a917a5b019bd6bcab95a720` on a clean working tree. History
relative to the previously audited commit:

- `7b3574f6dc92bdb45cfe334c6584bea26648cd28` — original fix (audited and
  accepted 2026-08-24): hotseat slot authority moved from `RANK` to `SCREEN`
  at capture; composer container-identity inversion fixed (`folderId`/
  `appPairId` only on the FOLDER/APP_PAIR item itself); JVM and
  production-seam regressions added.
- `7462af4f9b572660ca1fba9563beab5e2f427303` — docs-only: the prior audit
  record itself.
- `f99ff8cd5e04ab549a917a5b019bd6bcab95a720` — review follow-up audited here:
  "keep schema-nullable SCREEN lossless through capture and write".

The incremental diff `7b3574f6dc..f99ff8cd5e` was reviewed in full by this
session; it touches exactly three code/test files plus the prior audit record:

- `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt`
  - Capture (`Cursor.toPersistentRow`) now keeps the raw nullable `SCREEN`
    column in `PersistentRow.screenId` for every row: desktop still fails
    closed via `requireNotNull(screen)`, and every other container carries
    `PageId(value)` only when the column is non-null. The previously merged
    mapping normalized a hotseat NULL to `PageId("0")` at capture — an
    invented value behind the platform's back; that normalization is gone.
  - Canonical Dock derivation uses the new private
    `PersistentRow.hotseatSlot()` (`screenId?.value?.toIntOrNull() ?: 0`);
    NULL is interpreted as slot 0 exactly like the platform loader's
    `Cursor.getInt`, and only at that derivation.
  - Write (`values()`) is now faithful: `putNull(Favorites.SCREEN)` when the
    row carries no screen; otherwise a numeric put guarded by
    `requireNotNull(row.screenId.value.toLongOrNull())`, which fails closed
    rather than writing a coerced value.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  — `rowFor`'s Dock branch writes `screen = null` exactly when the base row
  was captured with a NULL slot and the plan keeps rank 0 (preserved rows stay
  byte-exact); moved or explicitly-slotted rows write their explicit slot;
  `RANK` remains an untouched passthrough. Page numbering (`maxPage`) and page
  inventory (`referencedPages`) remain scoped to `CONTAINER_DESKTOP`, so
  hotseat NULLs cannot leak into page planning.
- `tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt`
  — new regression `hotseatNullScreenRowRoundTripsExactlyThroughCaptureWriteAndRecovery`
  over a real schema-33 SQLite database: insert a dock row with
  `SCREEN=NULL, RANK=0` → capture asserts the manifest keeps `screenId == null`
  while Dock derives slot 0 → encode asserts the ContentValues contain a null
  `SCREEN` value → insert into a restored database asserts `IS NULL` and
  `RANK = 0` → recapture asserts manifest rows and canonical state equal the
  original capture. The class grew from 11 to 12 `@Test` methods; the
  pre-existing quiet/private/disabled/unavailable case remains intact.

Losslessness conclusions independently derived from this diff:

- No invented values anywhere in the round trip: capture keeps the raw value,
  derivation reads NULL→slot 0 transiently for placement semantics only, and
  encode restores NULL. A NULL-slot preserved dock row is byte-exact through
  capture → plan → apply/recovery encode; explicit-slot preserved rows remain
  byte-exact as before.
- Desktop NULL still fails closed twice: `requireNotNull(screen)` at capture
  and `requireNotNull(row.screenId)` at Workspace placement derivation.
- `PersistenceManifest` contract unchanged: format stays 1 / schema stays 33,
  and `PersistentRow.screenId` was already a nullable `PageId?` with
  null-aware equals/hashCode, so retaining NULL requires no schema or format
  change. Recovery paths were already nullable-safe
  (`RecoveryRecordCodec.writeNullableString/readNullableString`,
  `RecoveryWriteSet.optionalText`), so recovery records preserve NULL too.
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt` has
  an empty diff against `origin/main` at the new head (re-verified below) — no
  validation rule was weakened. The base-fix hunks in
  `OrganizationInputComposer.kt` did not change after the previously audited
  commit; no DB schema/migration, recovery-mechanism, or UI change was
  introduced by the incremental commit.

Defect mechanics of the base fix (RANK→SCREEN authority; composer identity
inversion producing the reported `PLANNING_INVALID.OVERLAP reasons=16` split
1 + 7 + 8) were re-derived from Launcher3 authority sources
(`LoaderCursor.checkItemPlacement`, `GridSizeMigrationUtil.loadHotseatEntries`)
by the prior session at `7b3574f6dc` and remain valid at this head; those
authority sources are untouched upstream code.

## Criteria check

Issue #136 acceptance items plus the two review blocking items, mapped to
evidence (marking what this re-audit re-executed at the new head vs. cites):

- **Review blocker: schema-nullable SCREEN must stay lossless through capture
  AND write, including the PersistenceManifest contract — satisfied,
  independently verified at the new head.** See Scope for the diff-level
  verification: capture retains the raw nullable column for every row, the
  canonical Dock derivation interprets NULL as slot 0 via `hotseatSlot()`
  mirroring `Cursor.getInt`, `values()` writes `putNull` faithfully with a
  fail-closed numeric guard, `rowFor` restores a captured NULL on preserved
  rank-0 dock rows instead of writing 0, the manifest format stays 1/33 with a
  nullable `screenId` field, and recovery codecs already round-trip null. The
  desktop requirement of a non-null page still fails closed, and the new
  instrumentation regression asserts exact column preservation across
  capture → encode → insert → recapture; it executed green in CI lane
  `organizer-instrumentation-api35-tests` at this head.
- **Review blocker: complete the minified-release device journey before merge
  while keeping `Closes #136` — satisfied as implementer-reported execution,
  with provenance caveats recorded in Findings.** The PR body reports the API
  36 Pixel 6-class AVD journey reaching the coherent preview ("15 targets
  across 1 profiles and 2 pages", "4 placements will move", "11 placements
  will be preserved" Dock 4 / out-of-scope 7), Apply+Cancel offered, cancelled
  without applying, favorites placements unchanged afterwards; screenshot kept
  local-only per the issue evidence policy. `Closes #136` remains on the PR,
  and the APP_PAIR snap-position gap discovered during review is tracked in a
  separate follow-up issue (#141, verified open). This session could not
  verify durable journey artifacts and therefore cites it as
  implementer-reported; impact analysis in Findings concludes the residual
  risk is low because the preview path behaves identically under the parent
  commit stamped into the binary and this head, and the changed write path was
  never applied on-device.
- **Isolation of the owning boundary — satisfied, re-derived by the prior
  session and unchanged here.** The incremental commit touches only the codec
  write/capture fidelity and one adapter branch; target composition
  (`full-target-v1`) needed no change.
- **Production-seam regression reproducing before / verifying after —
  satisfied.** Pre-fix reproductions are CITED from the implementing session:
  the JVM regression failed with exactly `DANGLING_REFERENCE ×7 +
  KIND_TARGET_MISMATCH ×8` (arithmetically implied by the old mapping against
  the unchanged validator), and the new NULL-screen instrumentation case
  failed pre-fix with `capture must keep the raw nullable SCREEN expected
  null, but was PageId(value=0)` — exactly what the removed
  `PageId((screen ?: 0L).toString())` normalization would produce against the
  retained `assertNull`. After-fix evidence RE-EXECUTED green by this session:
  JVM regression fresh-timestamped in this re-audit's unit-test run, and the
  instrumentation class ran green in the api35 CI lane at this head.
- **Malformed/unsafe layouts remain typed fail-closed with no write —
  satisfied.** Validation code byte-identical (empty diff re-verified); dock
  slot bounds and duplicate-slot OVERLAP checks operate on SCREEN-derived
  slots as before; unknown lock still aborts composition; existing
  rejection-path organizer tests ran green unchanged in the re-executed suite.
- **No fallback silently drops, duplicates, or rewrites unsupported items —
  satisfied.** Conservation asserted exactly (15 distinct items once each) in
  both regressions; the write side additionally no longer normalizes NULL
  SCREEN behind the platform's back.
- **Relevant organizer tests, release build, formatting, `final-status` pass —
  satisfied.** Organizer unit tests (724), spotless, repo-contract validators,
  and the CI merge gate (`final-status` + source jobs + all six
  instrumentation lanes) were re-executed green by this session at the new
  head (attempt 2 after a single-lane flake, see Findings).
- **`risk: layout-data` label + independent audit evidence — satisfied.**
  Label verified via the GitHub API; this record provides the independent
  audit for the new head.

Spec criteria (all re-checked at the new head):

- **specs/83-production-organization-input-sources/spec.md AC-3 (one canonical
  capture) — pass.** Still exactly one application capture feeding the
  composer; the incremental commit changes only what the capture preserves,
  not where input comes from.
- **AC-4 (complete conservation input) — pass.** Exact-once partition of all
  captured items asserted in the regressions; `additions` explicitly empty.
- **AC-5 (fail closed) — pass.** Untouched validator; desktop NULL SCREEN now
  fails closed at capture exactly as before; genuinely malformed layouts keep
  returning typed non-write results.
- **AC-6 (profile and lock safety) — pass.** Profile identity/availability and
  lock truth flow through `mapItem` unchanged; unknown lock still aborts.
- **FR-002 (explicit full-organization target set; out-of-scope items not
  changed) — pass,** via conservation/partition assertions and unchanged
  preservation precedence.
- **NFR-002 (integrity checks around apply) — pass.** Validator byte-identical
  (empty diff); dock inputs sourced from the column the platform loader
  enforces; the write path now round-trips the nullable column without
  coercion.
- **NFR-007 (compatibility across the supported form factors/profiles/grids)
  — pass on source-consistency grounds:** the representation agrees with
  `LoaderCursor`/`GridSizeMigrationUtil` for the supported schema-33 phone
  profile; no orientation-, profile-, or grid-specific branching introduced;
  broader device-matrix coverage remains tracked by #108/#132.
- **specs/12-deterministic-full-layout-planner-v1/spec.md AC-4 (folder
  grouping/partition/rank/cell correctness) — pass:** planner returns
  `Planned` with all 15 placements and preserved dock slots [0,1,2,3],
  asserted in the re-executed JVM regression.
- **specs/12 AC-7 (invalid/impossible inputs return the complete typed result)
  — pass:** unchanged validator behavior; pre-fix states demonstrate the
  typed-rejection path firing on bad input rather than crashing or mis-writing.

## Executed test surface

Block A — independent checks executed by THIS re-audit session on head
`f99ff8cd5e04ab549a917a5b019bd6bcab95a720`, branch
`issue-136-default-layout-planning-rejection`, clean tree:

```text
git rev-parse HEAD && git status && git log --oneline -3
  PASS — HEAD f99ff8cd5e04ab549a917a5b019bd6bcab95a720, correct branch, clean tree,
         history 7462af4f9b (docs-only) → f99ff8cd5e on top of 7b3574f6dc

git diff --stat 7b3574f6dc..f99ff8cd5e
  PASS — exactly 4 files: prior audit doc (new), RowManifestCodec.kt (+35/-15 net),
         LauncherLayoutAdapter.kt, ProductionOrganizationInputInstrumentationTest.kt (+93);
         full diff reviewed line by line

git diff origin/main...HEAD -- lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt
  PASS — empty diff (validation rules unchanged)

./gradlew spotlessCheck
  PASS — exit 0, BUILD SUCCESSFUL in 2s (own invocation)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*'
  PASS — exit 0, BUILD SUCCESSFUL in 26s (separate invocation); XML totals across
         62 result classes in build/test-results/testLawnWithQuickstepGithubDebugUnitTest:
         tests="724" skipped="0" failures="0" errors="0";
         TEST-app.lawnchair.organizer.integration.DefaultLayoutComposerPlannerRegressionTest.xml
         fresh timestamp from this run (tests=1 failures=0 errors=0)

python3 tools/repo-contract/validate_repo_contract.py
  PASS — exit 0, "repository contract OK"

python3 tools/repo-contract/test_validate_repo_contract.py
  PASS — exit 0, Ran 11 tests ... OK

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  PASS — exit 0, Ran 47 tests ... OK

gh api repos/nunu1733/NunuLauncher/actions/runs/32747094320 --jq '{head_sha,event,status,conclusion,run_attempt,head_branch,path,pull_requests}'
  PASS — head_sha=f99ff8cd5e04ab549a917a5b019bd6bcab95a720, event=pull_request,
         conclusion=success, run_attempt=2, head_branch matches PR, path=.github/workflows/ci.yml,
         pull_requests=[140]

gh api repos/nunu1733/NunuLauncher/actions/runs/32747094320/jobs?per_page=100 --jq '.jobs[] | "\(.name): \(.conclusion)"'
  PASS — attempt 2: all 12 jobs success — changes, organizer-unit-tests, check-style,
         build-debug-apk, validate-repo-contract, final-status, and all six
         organizer-instrumentation lanes (api35, db-migration, issue52, issue53,
         issue99, shared-writer)

gh api repos/nunu1733/NunuLauncher/actions/jobs/<api35 job>/logs
  PASS — api35 lane executed ProductionOrganizationInputInstrumentationTest per
         .github/workflows/ci.yml; BUILD SUCCESSFUL with the class listed for execution
         ("Finished 23 tests", 0 failed); class grew 11→12 @Test methods at this head
         (verified via git show of both heads), the added method being
         hotseatNullScreenRowRoundTripsExactlyThroughCaptureWriteAndRecovery

gh issue view 141 --repo nunu1733/NunuLauncher --json number,title,state
  PASS — #141 "[Bug]: App pair snap position is never captured..." OPEN (follow-up tracked)
```

Not executed locally by this session: connected/emulator tests (covered by the
CI lanes above) and debug/release assemblies (debug assembly covered by the
`build-debug-apk` CI job).

Block B — PRIOR-SESSION evidence at `7b3574f6dc92bdb45cfe334c6584bea26648cd28`
(audited 2026-08-24, kept attributed; not re-run here except where noted):
spotlessCheck, the same organizer+navigation unit-test filter (724/0),
repo-contract validators, merge-gate verification of run 32737474547 (all 12
jobs success, attempt 2 after an issue52 lane flake), and the empty
PlanningValidation diff were all green at that head; details in the prior
record's Executed test surface. The base-commit defect mechanics derived there
remain applicable because the relevant hunks did not change.

## Findings

- **Attempt history, audited base `7b3574f6dc` (prior session, retained).**
  Attempt 1 of run 32737474547 failed ONLY
  `organizer-instrumentation-issue52-tests`
  (`ManualOrganizationPreferencesInstrumentationTest.capturesManualOrganizationReviewSurfaces`,
  `IndexOutOfBoundsException` inside Compose foundation screenshot capture);
  that test stubs the planner entirely so none of the PR's classes are on its
  path; a failed-job rerun went fully green. Attempt history of the referenced
  run evidences attempt 2.
- **Attempt-1 issue53 lane flake on the NEW head, transparent rerun (this
  session).** Attempt 1 of the referenced run 32747094320 failed ONLY
  `organizer-instrumentation-issue53-tests`:
  `OnboardingOrganizationProposalInstrumentationTest.productionOwnerDefersBindWhilePausedThenShowsAndRoutesReviewAfterResume`
  threw a bare `java.lang.AssertionError` after activity HOME/PREFERENCE
  transitions with await-polling assertions. Analysis: that test exercises the
  OrganizationOnboardingProposal UI/lifecycle choreography and shares no code
  path with the classes changed by this PR (codec/adapter/composer); the lane
  was green on the immediately preceding docs-only-head run (32741623937) and
  on today's runs; consistent with the shared-emulator UI flakiness pattern
  recorded for PR #133 and for the base head above. THIS session reran only
  the failed jobs (`gh run rerun 32747094320 --failed`; no code or workflow
  change) and attempt 2 went fully green including `final-status`. The
  referenced run link therefore evidences attempt 2; attempt 1's single-lane
  red is recorded here for merge-gate history.
- **`High-risk gate / high-risk-evidence` is red until this updated record
  lands** (gate run 32747094260 on the new head; likewise run 32737489341 on
  the base head). That is the designed dependency of the gate on the audit
  file and is not counted against the PR.
- **Evidence provenance.** Pre-fix failure reproductions (JVM counts and the
  instrumentation message) and the minified-release device journey are CITED
  from the implementing session, not re-executed by this audit; everything in
  Executed test surface Block A was re-executed here.
- **Release-journey provenance caveat (non-blocking, low residual risk).**
  Durable journey artifacts do not exist (screenshot local-only per policy),
  and `build.gradle` stamps dev-release `versionName` with
  `git rev-parse --short=7 HEAD` at build time, while the PR body reports the
  installed APK as versionName `15.Dev.(7462af4)` — i.e., the binary was
  stamped at the docs-only parent commit rather than this head (most plausibly
  built from the same working tree before committing `f99ff8cd5e`). Impact is
  nil-to-low: default-layout dock rows carry explicit `SCREEN` slots (the
  observed-install fixture mirrors this), so preview behavior is identical
  under both commits, and the journey was cancelled without applying, so the
  changed write path was never exercised on-device. Definitive coverage of the
  NULL-lossless behavior at this exact head comes from the new instrumentation
  regression executing green in the api35 CI lane.
- **Minor observation: out-of-int-range hotseat SCREEN values (non-blocking).**
  `hotseatSlot()` maps a hypothetical SCREEN value beyond Int range to slot 0
  via `toIntOrNull()`, whereas the previously merged `String.toInt()` mapping
  would have thrown (fail closed) and the platform loader's `getInt` wraps
  modulo 2^32 — neither of which the new code replicates for that pathological
  corrupt-DB edge. Unreachable through normal flows (screens are small page /
  slot indices); recorded for completeness, no action required.
- **Minor observation: planned dock rows at slot 0 write NULL SCREEN
  (non-blocking).** In `rowFor`'s Dock branch a planned (base-less) row ranked
  0 also writes NULL instead of an explicit 0. This is semantically identical
  under the loader's getInt semantics, consistent with the no-invented-values
  policy, and byte-exactness is only claimed for preserved rows. No action
  required.
- **Cosmetic, non-blocking (retained from prior audit).** Relocating the
  pre-existing quiet/private/disabled/unavailable case left its opening brace
  and first statement on one line; `spotlessCheck` accepts it locally and in
  CI. No action required.
- No blocking finding was identified. The verdict is **accepted**: the review
  follow-up completes the NULL-lossless contract through capture, manifest,
  derivation, write, and recovery without inventing values anywhere; validation
  remains fail-closed and byte-identical; preserved dock rows round-trip
  byte-exactly; and all machine-verifiable evidence is green on the audited
  head `f99ff8cd5e04ab549a917a5b019bd6bcab95a720`.
