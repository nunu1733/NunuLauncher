# High-risk audit: PR #75 preserve source grid migration failure

> Status: accepted
> Audit date: 2026-08-17

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/75
- Head SHA: f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32029042463
- Criteria: specs/59-preserve-source-grid-migration-failure/spec.md AC-59-01, AC-59-02, AC-59-03, AC-59-04, AC-59-05, AC-59-06, AC-59-07, AC-59-08, NFR-001, NFR-002, NFR-012

## Scope

Audited the complete `main..f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1`
diff: 20 files, +2895/-153. The production surface is `ModelDbController`,
`GridSizeMigrationUtil`, `GridMigrationJournal`, `FavoritesTableDigest`,
`GridMigrationRuntime`, `GridMigrationOperation`, `LauncherPrefs`, and
`LauncherDbUtils`; the controller-entry instrumentation suite and schema
lifecycle tests were inspected through that diff.

The latest commit, `f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1` (`fix: fail
closed on unresolved recovery and validate durable sources`), responds to the
two High blocking findings of the 2026-08-17 re-review. First, unresolved
active durable-recovery failures no longer become boolean migration failures:
`migrateGridIfNeeded()` routes every non-recovery-pending
`RuntimeException` from `reconcileActiveDatabaseJournal()` through
`failClosedActiveRecovery()` (`ModelDbController.java:363-373,513-531`),
which quarantines the still-active journal-bearing helper (close, `mOpenHelper
= null`, recovery-pending exception) or propagates the raw failure when
recovery already republished a proven source; the candidate-target
reconciliation catch likewise rethrows when recovery swapped the active helper
mid-failure (`ModelDbController.java:418-429`). `tryMigrateDB()` does not
swallow the exception, and `LoaderTask.loadWorkspaceImpl()` rethrows it before
`loadDefaultFavoritesIfNecessary()` and normal workspace loading
(`LoaderTask.java:419-425,459-471`). Second, journal-source identity,
canonical-path, and existence validation is generalized to every durable
recovery phase through `validatedJournalSourceFile()`
(`ModelDbController.java:799-820`), called before any writable source helper
is constructed or a source is republished: non-FINALIZED
`reconcileActiveDatabaseJournal()` before `openJournalSource()`
(`ModelDbController.java:533-547`), `compensateAndRestore()` and
`restoreSourceAuthority()` before `publishFreshSource()`
(`ModelDbController.java:576-580,728-731`), and
`validateFinalizedJournalSource()` via the same validator
(`ModelDbController.java:778-796`). A missing or mispointed journal source can
therefore no longer be manufactured as an empty database and published as
source authority. Finalized metadata-cleanup failure after successful target
validation stays non-fatal and leaves validated `FINALIZED` metadata for retry
(`ModelDbController.java:709-720`).

## Criteria check

- AC-59-01: PASS. The accepted contract requires the framework schema-32
  upgrade transaction to convert legacy rows to `UNKNOWN` or leave the schema-32
  rows unchanged on failure. The schema-33 instrumentation fixtures remain in
  the complete diff; the controller does not rewrite an already schema-33
  source. This is consistent with ADR-0004's non-wiping upgrade requirement.
- AC-59-02: PASS. `ModelDbController` keeps the source separate from the target,
  and `GridSizeMigrationUtil` attaches the source and copies into the target.
  `GridMigrationSuccessTest.java:67-75` asserts exact source `LOCKED` and
  `UNLOCKED` values after general migration; failure tests retain source
  authority and values across transaction-close, detach, source-close,
  preference, restore, and FINALIZED reconciliation faults, and the new
  regressions assert no source artifacts are created when the journal source
  is missing.
- AC-59-03: PASS. `ModelDbController.java:363-511` reconciles active durable
  state, then acquires `GRID_MIGRATION` before candidate setup; the lease spans
  transaction closure, finalization, source close, preference persistence, and
  recovery. `reconcileActiveDatabaseJournal` and `reconcileDurableJournal` at
  lines 533-574 and 657-726 route active and candidate admission through the
  same phase dispatcher.
- AC-59-04: PASS. The initial target transaction records same-target backup and
  `TARGET_OLD`, performs migration, marks all target rows `UNKNOWN`, removes
  `favorites_tmp`, changes the journal to `MIGRATED_PENDING_FINALIZATION`, and
  commits as one unit (`ModelDbController.java:444-465`;
  `GridSizeMigrationUtil.java:154-190`).
  `GridMigrationSuccessTest.java:44-52` verifies the resulting target is
  unknown, temporary state is absent, and recovery metadata is cleaned only
  after finalization.
- AC-59-05: PASS. `finalizeMigration` follows successful initial transaction
  closure and performs target publication, delegated source close, synchronous
  destination preference write/readback, `FINALIZED`, validation, then metadata
  cleanup (`ModelDbController.java:553-596`); a cleanup failure after the
  validated target is logged and left for a later non-fatal retry
  (`ModelDbController.java:709-720`). The fast success test asserts publication
  only after controller migration completes.
- AC-59-06: PASS. Initial and finalization failures return through source
  restoration rather than the migration path's `createEmptyDB()` method.
  `restoreTarget` records `RESTORE_PENDING`, restores and validates the
  canonical digest, and records `RESTORE_FAILED` on failure
  (`ModelDbController.java:598-655`). The re-review fixes additionally fail
  closed for every unresolved active durable-recovery failure
  (`ModelDbController.java:363-373,513-531`) and validate the journal source's
  identity, canonical path, and existence before any writable source helper is
  constructed or republished (`ModelDbController.java:533-547,576-580,728-731,
  778-820`), so a lost or mispointed source cannot be recreated as an empty
  database. `GridMigrationFailureTest.java:234-380` covers corrupt backup
  recovery to source, missing source refusal, target-as-source refusal, and
  the new non-FINALIZED missing-source, target-as-source, corrupt-source
  quarantine, and post-publication preference-failure cases.
- AC-59-07: PASS. The schema fixture changes retain the inactive-grid case, and
  the production helper remains the source-normalization seam; the migration
  controller opens the candidate separately from the active source.
- AC-59-08: PASS. The changed schema lifecycle instrumentation continues to
  exercise `32 -> 33 -> 32 -> 33`, preserving layout rows while re-upgrading
  pre-existing rows to `UNKNOWN`, as required by the accepted spec and ADR-0004.
- NFR-001: PASS. Durable same-target journal phases, source-authority
  restoration, digest validation, retry, recovery-pending quarantine, and the
  new controller-boundary fail-closed behavior prevent a failed migration —
  including unresolved durable-recovery reconciliation — from silently
  selecting or continuing to load an untrusted target.
- NFR-002: PASS. Source and target identities are checked before migration,
  and the journal-source identity/path/existence checks now guard every
  durable recovery phase, not only FINALIZED.
  `GridMigrationFailureTest.java:295-334` exercises missing-source and
  target-as-source failure on `RESTORE_FAILED` and
  `MIGRATED_PENDING_FINALIZATION`.
- NFR-012: PASS. The reviewed tests cover schema lifecycle, inactive-grid
  normalization, migration success/failure, crash/retry, digest mismatch,
  preference failure, metadata cleanup retry, invalid FINALIZED authority, and
  the newly added unresolved-recovery and durable-source validation cases
  through `ModelDbController.tryMigrateDB`.

## Executed test surface

This audit independently inspected the PR and CI evidence and executed the
following local repository checks against
`f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1`:

```text
GIT_MASTER=1 git diff --no-ext-diff main..f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1
  -> inspected complete implementation diff (20 files, +2895/-153)

git diff --check main..f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1
  -> no output, exit 0

gh pr view 75 -R nunu1733/NunuLauncher --json headRefOid,headRefName,commits,files,labels,state
  -> OPEN PR head is f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1; it contains the audited commit

gh api repos/nunu1733/NunuLauncher/actions/runs/32029042463
  -> pull_request CI on the audited SHA/head branch, associated with PR #75;
     completed/success; jobs changes, build-debug-apk, check-style,
     validate-repo-contract, organizer-unit-tests, final-status all success
     (verified via actions/runs/32029042463/jobs?per_page=100)

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK, exit 0

python3 tools/repo-contract/test_validate_repo_contract.py
  -> Ran 11 tests; OK, exit 0

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> Ran 47 tests; OK, exit 0

python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 75 --head-sha f8bddc7944b5a6eb3cb9ecb9e50142a17a06e6f1 --root /Users/nunu/Documents/work/NunuLauncher
  -> PASS: audit covers the audited SHA with independent CI evidence
```

CI run `32029042463` is independent Gradle evidence, not a claim of local
Gradle execution: `final-status`, `organizer-unit-tests`, `check-style`, and
`build-debug-apk` each completed successfully and none of those jobs was
skipped. The run executed the configured organizer JVM test, `spotlessCheck`,
and debug-APK build surfaces. This auditor did not rerun Gradle or an emulator;
in particular, the `GridMigrationSuccessTest` plus `GridMigrationFailureTest`
instrumentation cases, including the four new non-FINALIZED regressions, were
inspected and compile-verified by CI's androidTest build and the implementer's
`compileLawnWithQuickstepGithubDebugAndroidTestWithJavac` run, but not
independently executed on an AVD or device in this session.

## Findings

Verdict: pass-with-findings. The implementation at the audited SHA satisfies
AC-59-01 through AC-59-08 and NFR-001, NFR-002, and NFR-012 by independent
code/diff review plus qualifying CI evidence. No blocking defect was found.

1. The prior audit head and CI run were stale relative to the review-response
   commit. This record is retargeted to the latest PR code commit and the
   matching qualifying pull_request CI run.
2. Instrumentation/emulator execution was not independently rerun. The
   qualifying CI source jobs do not run the Android instrumentation suite, so
   the implementer's compile-only verification is not represented as
   independent audit execution.
3. The final change closes both High blockers of the 2026-08-17 re-review:
   unresolved active durable-recovery failures now quarantine the active
   helper and propagate recovery-pending at the `tryMigrateDB()` boundary so
   `LoaderTask` cannot continue loading from that database, and journal-source
   identity/path/existence validation is applied to every durable recovery
   phase before any writable source helper exists, with missing-source and
   target-as-source regressions on non-FINALIZED phases. The recovery-pending
   exception intentionally leaves no active helper rather than selecting
   unverified target authority.
