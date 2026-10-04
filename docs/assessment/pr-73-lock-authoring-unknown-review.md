# High-risk audit: PR #73 lock authoring and unknown-state review

> Status: accepted
> Audit date: 2026-08-16

- Auditor: independent audit session (ZCode agent session distinct from the implementing session; solo-maintainer delegation per docs/project/github-workflow.md)
- PR: https://github.com/nunu1733/NunuLauncher/pull/73
- Head SHA: 80cf601d0dc3740bb3d935726cca6ce1bc0f0e14
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/31917320016
- Criteria: specs/38-lock-authoring-unknown-review/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8 / docs/adr/0004-organizer-lock-persistence.md ADR-0004

## Scope

`git diff main...HEAD --stat` on head `80cf601d0d`: 27 files, +3713/-1. All new or
modified files were read in full.

Runtime write path audited (the only production mutation path added by this PR):

- `lawnchair/src/app/lawnchair/organizer/locks/adapter/LockStateDbAdapter.kt` —
  captures via the Issue #14 boundary `LauncherLayoutAdapter.captureCurrent`;
  writes via `LayoutWriteCoordinator.tryAcquire(OwnerKind.ORGANIZER)` (null →
  typed `WRITER_BUSY`, no DB access), then one
  `ModelDbController.newTransaction(lease.token())`, an in-transaction full
  recapture (revision equality → `STALE_REVISION`), exact per-row
  `CanonicalItemState` equality (`current != write.expected` →
  `PRECONDITION_FAILED`), then per-row
  `UPDATE favorites SET organizerLockState=? WHERE _id=?` only
  (`lockColumnValues` puts a single column), `update != 1` rejects, `commit()`
  on success. Every rejection path calls `tx.close()` without `commit()`;
  `LauncherDbUtils.SQLiteTransaction.close()` runs `endTransaction()` (verified
  in the unmodified Issue #14 source), which rolls back an uncommitted
  transaction. No insert/delete path exists in this adapter.
- Domain/planning code is platform-free and performs no I/O:
  `LockAuthoring.kt` (decision), `EffectiveLocks.kt` (effective locks and
  explanation notes), `LockReview.kt` (UNKNOWN listing), `LockPorts.kt`,
  `LockAuthoringModule.kt` (preview/setLock/reviewBatch orchestration),
  `OrganizerLocks.kt` (composition holder; no `LawnchairApp` change).
- UI surfaces audited: `OrganizerLockShortcut.kt` (long-press popup entry for
  application/deep-shortcut rows; state-aware AlertDialog with effect notes
  shown before mutation; only dialog buttons construct `UserReviewedIntent`),
  `PlacementLockPreferences.kt` (management/review Compose screen; state
  rendered as text with `semantics contentDescription`; single and batch review
  confirmed dialogs), `LockMessages.kt` (single typed→resource mapping), plus
  entry wiring in `LawnchairLauncher.getSupportedShortcuts`,
  `PreferenceRoutes/PreferenceNavigation`, `HomeScreenPreferences`.
- Migration target: none. No schema, provider, backup, or grid-migration change.
  Verified by
  `git diff main...HEAD --name-only | grep -E '^(lawnchair/src/app/lawnchair/organizer/application/|src/com/android/launcher3/)'`
  → no matches: no `organizer/application/**`, no `src/com/android/launcher3/**`
  file is touched (AC-6; also no high-risk gate path such as `ModelWriter`,
  `DatabaseHelper`, `LayoutWriteCoordinator` itself).
- Other diff content: `strings.xml` (+67 lines of state/effect/error/result
  strings), `DESIGN.md` §9 package map line, `specs/38-.../spec.md` (accepted)
  and `plan.md`, test-only dependencies in `build.gradle` /
  `libs.versions.toml` (`compose-ui-test`, `compose-ui-test-manifest`; disclosed
  in the PR body; no production dependency change), and the test files listed
  under "Executed test surface".

The referenced CI run was re-verified through the GitHub API (commands below):
`ci.yml`, `pull_request` event, head SHA
`80cf601d0dc3740bb3d935726cca6ce1bc0f0e14`, branch
`issue-38-lock-authoring-unknown-review`, associated with PR #73, completed
`success`, with `organizer-unit-tests`, `check-style`, `build-debug-apk`, and
`final-status` all concluded `success` and nothing skipped.

## Criteria check

- AC-1 (pass). The writer updates only `favorites.organizerLockState` of
  existing rows (`lockColumnValues` = single `ContentValues` entry; `WHERE _id=?`;
  `update != 1` → rejection) inside one transaction. The new revision is
  observable on recapture because `CanonicalMarshalling` encodes
  `lockState.ordinal` into the canonical state
  (`application/canonical/CanonicalMarshalling.kt:115`) from which
  `RevisionCalculator.revisionOf` derives the revision. Evidence: JVM
  `successful change returns committed result and writes single column plan`
  (exact full-row precondition asserted); instrumentation
  `lockUnlockRoundTripsAcrossFolderChildDockWidgetAndAppPair` (column 2/1
  round-trips across all six row kinds plus title/intent identity-column
  untouched check) and `unknownReviewRequiresIntentAndResolvesSinglyAndInBatch`
  (listing recapture reflects the write).
- AC-2 (pass). `LockAuthoringDecision.evaluateChange` rejects
  `INTENT_REQUIRED` whenever `request.intent == null` — for UNKNOWN and for
  every transition; `evaluateReviewBatch` additionally requires a non-empty,
  duplicate-free list and rejects the whole batch with `ITEM_NOT_FOUND` /
  `ITEM_NOT_UNKNOWN` when any listed row is missing or not UNKNOWN, before any
  plan exists. `LockTargetState` is a closed enum of `LOCKED`/`UNLOCKED`; UNKNOWN
  is never a write target. Evidence: JVM `intent is required for unknown
  review`, `intent is required for every change`, `batch review rejects missing
  or already reviewed items atomically`, `batch review of unknown rows produces
  one atomic plan`; instrumentation single+batch E2E including the no-intent
  rejection leaving the column at UNKNOWN(0).
- AC-3 (pass). `EffectiveLocks.explainEffect` produces exactly the ADR-0004
  precedence notes: `FOLDER_PARENT_COVERS_CHILDREN`,
  `FOLDER_CHILDREN_OWN_LOCK_REMAINS`, `FOLDER_CHILD_OWN_LOCK_BINDS`,
  `FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK`,
  `APP_PAIR_PARENT_COVERS_BOTH_MEMBERS`,
  `APP_PAIR_MEMBER_OWN_LOCK_BINDS`,
  `APP_PAIR_MEMBER_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK`, with
  `LockProtectionScope` covering Dock (`DOCK_SLOT`) and widget
  (`WIDGET_REGION`) extents. Both UI dialogs render current-state text + scope
  description + note lines before any mutation, and only the confirm buttons
  supply intent. Evidence: JVM `EffectiveLockEffectsTest` (10 tests, one per
  precedence/scope case); Compose `folderLockDialogExplainsChildCoverageBeforeMutation`
  asserts the folder-covers-children text is displayed while the writer records
  zero writes.
- AC-4 (pass). The writer acquires the shared organizer lease first
  (`tryAcquire(ORGANIZER)`; busy → typed rejection without acquiring the DB),
  re-reads the full revision inside the transaction, compares each row's exact
  `CanonicalItemState`, updates, and commits; every rejection closes the
  transaction uncommitted (rollback via `endTransaction`, no
  `setTransactionSuccessful`). Evidence: instrumentation
  `staleRevisionRejectsWithoutMutation`,
  `preconditionMismatchRejectsWithoutMutation`,
  `coordinatorLeaseContentionReportsBusyWithoutMutation` (retry after release
  still revision-guarded), and `transactionFailureRollsBackEveryRow`
  (trigger-injected mid-transaction failure leaves every batch row UNKNOWN);
  JVM `writer busy maps to typed busy rejection` and `stale revision and
  precondition map to stale capture`.
- AC-5 (pass). Decision-code rejections before any write: `ITEM_NOT_FOUND`
  (item absent from capture, non-persistent ref, or non-numeric row id),
  `PROFILE_UNKNOWN` (profile absent from inventory), `PROFILE_UNAVAILABLE`
  (non-AVAILABLE profile), `UNSUPPORTED_ITEM` (`CanonicalItemKind.Unknown` or
  `PlacementState.UnsupportedContainer`, regardless of stored lock value,
  per D-006), and `PLACEMENT_OUT_OF_PROFILE` (`fitsProfile` bounds: workspace
  cell+span vs columns/rows, dock rank vs hotseatSlots, folder rank vs
  folderMaxColumns*folderMaxRows). Evidence: JVM decision matrix — `missing
  item rejects as stale identity`, `unavailable profile rejects without
  mutation`, `profile absent from inventory rejects`, `unknown kind rejects as
  unsupported regardless of stored state`, `unsupported container rejects`,
  `placement outside device profile rejects`, `dock rank outside hotseat
  rejects`.
- AC-6 (pass). The name-only diff check (above) shows no
  `organizer/application/**` and no `src/com/android/launcher3/**` change, so
  the apply/organization-confirmation path is untouched and lock preservation
  there is owned by the existing Issue #12/#14 tests. The locks seam exposes no
  plan/apply API: JVM test `module exposes no plan or apply surface` asserts
  `LockAuthoringModule` has no `apply`/`recover`/`plan` methods; the module
  surface is `currentCapture/lockStateListing/reviewListing/explain/setLock/
  reviewBatch` only.
- AC-7 (pass). `lawnchair/res/values/strings.xml` gains localized strings for
  states (`locked`/`unlocked`/`needs review`), all seven effect notes plus seven
  scope descriptions, every typed error (failed, stale, busy, item-not-found,
  not-unknown, profile-unavailable, unsupported, intent-required), and results.
  `PlacementLockPreferences` renders state as a text badge (never color alone)
  with `semantics { contentDescription }`, and both the popup AlertDialog and
  the Compose dialog show the explanation before the confirm action. Evidence:
  Compose UI tests over the real composable — `unknownBannerAndTextStateLabels
  AreRendered` (text state labels displayed), `unknownReviewResolvesOnlyThrough
  ConfirmedDialog` (zero writes until confirm), `folderLockDialogExplainsChild
  CoverageBeforeMutation`, `busyFailureRendersLocalizedMessage`. See Findings
  note 1 for the popup-path coverage caveat.
- AC-8 (pass). JVM: `tests/unit/app/lawnchair/organizer/locks/` contains
  `LockAuthoringDecisionTest` (19), `EffectiveLockEffectsTest` (10),
  `LockAuthoringModuleProtocolTest` (11), `LockReviewListingTest` (5) —
  45 tests covering accept/reject/batch/precedence/profile/staleness and
  listing determinism; they run in the CI organizer gate via
  `--tests 'app.lawnchair.organizer.*'` (`.github/workflows/ci.yml` job
  `organizer-unit-tests`). Instrumentation:
  `tests/organizer-instrumentation/app/lawnchair/organizer/locks/` contains
  `LockAuthoringInstrumentationTest` (6 real-DB tests: round-trip across
  folder/child/Dock/widget/app-pair/member, UNKNOWN single+batch review, stale
  revision, precondition mismatch, lease contention busy, injected
  transaction-failure rollback) and `OrganizerLockScreenTest` (4 Compose
  semantics tests). See Findings note 2: instrumentation was not re-executed in
  this audit session.
- ADR-0004 consistency (pass). The implementation matches the ADR's identity
  and effective-lock table: tri-state semantics only, `UNKNOWN` resolvable only
  through review, per-row stored states preserved (folder-parent lock writes do
  not touch child rows), no second lock store (reads/writes go through the
  Issue #14 canonical boundary and Launcher DB), and unsupported rows stay
  non-actionable regardless of lock value (D-006).

## Executed test surface

All commands run on the checked-out head `80cf601d0d` (branch
`issue-38-lock-authoring-unknown-review`, clean tree) with
`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`:

```text
./gradlew spotlessCheck
  -> BUILD SUCCESSFUL (spotlessJavaCheck/KotlinCheck/Check up-to-date after prior CI-equivalent run)

./gradlew --no-parallel testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.locks.*'
  -> BUILD SUCCESSFUL; 45 tests, 0 failures, 0 errors, 0 skipped
     (build/test-results/testLawnWithQuickstepGithubDebugUnitTest/:
      EffectiveLockEffectsTest tests=10 failures=0 errors=0 skipped=0;
      LockAuthoringDecisionTest tests=19 failures=0 errors=0 skipped=0;
      LockAuthoringModuleProtocolTest tests=11 failures=0 errors=0 skipped=0;
      LockReviewListingTest tests=5 failures=0 errors=0 skipped=0)

python3 tools/repo-contract/validate_repo_contract.py
  -> "repository contract OK (/Users/nunu/Documents/work/NunuLauncher)", exit 0

gh api repos/nunu1733/NunuLauncher/actions/runs/31917320016
  -> head_sha 80cf601d0dc3740bb3d935726cca6ce1bc0f0e14, event pull_request,
     head_branch issue-38-lock-authoring-unknown-review, path .github/workflows/ci.yml,
     status completed, conclusion success, pull_requests [73]
gh api repos/nunu1733/NunuLauncher/actions/runs/31917320016/jobs?per_page=100
  -> changes/validate-repo-contract/organizer-unit-tests/build-debug-apk/check-style/final-status all success
```

Not executed in this audit session (out of the audit's mandated surface, per the
gate definition): instrumentation/emulator runs. The merge-gate CI workflow
contains no emulator job, so the AC-8 instrumentation evidence is the PR body's
recorded API 36.1 AVD run (`connectedLawnWithQuickstepGithubDebugAndroidTest`,
39/39 pass) produced by the implementing session; this audit verified those
tests' content, assertions, and coverage by reading them, not by running them.

## Findings

Verdict: pass-with-findings. All eight acceptance criteria of
`specs/38-lock-authoring-unknown-review/spec.md` and the ADR-0004 consistency
requirements are met; no blocking defect was found. Recorded observations:

1. Popup path has no dedicated automated UI test. `OrganizerLockShortcut`'s
   state-aware AlertDialog (the popup entry required by spec §Scope) shares the
   `LockMessages` mapping and dialog logic pattern with the tested Compose
   screen, but no test drives the popup itself (popup presentation on a real
   launcher needs UI automation that the suite does not yet include). AC-7's
   oracle ("Compose UI semantics tests on the management/review screen and
   confirmation dialog") is satisfied by the screen tests; the popup path is
   covered indirectly. Suggest a follow-up issue for popup-entry UI automation.
2. Instrumentation tests are not independently re-executed by this audit and are
   not part of CI: `ci.yml` has no emulator job, so the real-DB/stale/rollback/
   busy evidence for AC-1/AC-2/AC-4 rests on the implementing session's recorded
   local run. This is a repo-level evidence gap (also true for the Issue #14
   suites), not specific to this PR. This audit mitigated it by full source
   review of the adapter against the unmodified Issue #14 transaction/lease
   implementation plus the API-verified merge-gate run.
3. Multi-profile behavior was not exercised on an emulator (no work profile
   provisioned; disclosed in the PR body). Unavailable/absent-profile rejection
   and profile labeling are covered by JVM decision tests and the screen's
   badge mapping; a work-profile instrumentation pass would close this
   completely.
4. Test-only dependencies were added (`androidx.compose.ui:ui-test-junit4`,
   `ui-test-manifest` debugImplementation). The spec's non-goals say "no ...
   dependencies"; the PR discloses this as test-only (no production dependency
   change), which AC-8's Compose-test requirement makes necessary. Acceptable,
   recorded here so the tension with the spec wording is explicit.
5. Minor plan/implementation drift: `specs/38-.../plan.md` places the
   management-screen entry in `PreferencesDashboard.kt` with a screen named
   `OrganizerLockPreferences.kt`; the implementation uses
   `HomeScreenPreferences.kt` and `PlacementLockPreferences.kt`. Same seam, no
   behavioral difference; noted only for traceability.
6. Cosmetic: `LockMessages.rejection` maps `PROFILE_UNKNOWN` and
   `PROFILE_UNAVAILABLE` to one string, and `PLACEMENT_OUT_OF_PROFILE` shares
   the "unsupported" string. The domain rejections remain distinct and typed;
   the consolidation is a wording choice, not a defect.
7. The referenced format example `docs/assessment/pr-64-gate-demo.md` is not on
   this branch (it lived on the unmerged demo PR #64 branch; retrieved from git
   history `025123674c` for reference). This record follows
   `docs/assessment/_template.md` and the machine-checked fields of
   `tools/repo-contract/validate_high_risk_evidence.py`.
