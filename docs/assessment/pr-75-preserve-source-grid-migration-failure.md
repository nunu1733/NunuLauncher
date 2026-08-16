# High-risk audit: PR #75 preserve source grid migration failure

> Status: accepted
> Audit date: 2026-08-16

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/75
- Head SHA: 115549f6febbb573d183190dbd788b12c0708876
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/31953268395
- Criteria: specs/59-preserve-source-grid-migration-failure/spec.md AC-59-01, AC-59-02, AC-59-03, AC-59-04, AC-59-05, AC-59-06, AC-59-07, AC-59-08, NFR-001, NFR-002, NFR-012

## Scope

Audited the complete `main..115549f6febbb573d183190dbd788b12c0708876`
diff: 20 files, +2732/-153. The production surface is `ModelDbController`,
`GridSizeMigrationUtil`, `GridMigrationJournal`, `FavoritesTableDigest`,
`GridMigrationRuntime`, `GridMigrationOperation`, `LauncherPrefs`, and
`LauncherDbUtils`; the controller-entry instrumentation suite and schema
lifecycle tests were inspected through that diff.

The latest commit, `115549f6febbb573d183190dbd788b12c0708876` (`fix: fail
closed on invalid finalized grid migration`), is the PR #75 head. It changes
`ModelDbController` FINALIZED reconciliation so failed finalized-target
validation requires a journal source whose name matches source preferences,
has a distinct canonical path, exists, and matches any supplied helper. A
valid source is republished and recovery proceeds; if it cannot be established,
the active target is closed, `mOpenHelper` is cleared, and a
`GridMigrationRecoveryPendingException` is propagated. This prevents an
invalid finalized target from remaining loadable.

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
  preference, restore, and FINALIZED reconciliation faults.
- AC-59-03: PASS. `ModelDbController.java:363-502` reconciles active durable
  state, then acquires `GRID_MIGRATION` before candidate setup; the lease spans
  transaction closure, finalization, source close, preference persistence, and
  recovery. `reconcileActiveDatabaseJournal` and
  `reconcileDurableJournal` at lines 505-682 route active and candidate
  admission through the same phase dispatcher.
- AC-59-04: PASS. The initial target transaction records same-target backup and
  `TARGET_OLD`, performs migration, marks all target rows `UNKNOWN`, removes
  `favorites_tmp`, changes the journal to `MIGRATED_PENDING_FINALIZATION`, and
  commits as one unit (`ModelDbController.java:431-456`;
  `GridSizeMigrationUtil.java:154-190`).
  `GridMigrationSuccessTest.java:44-52` verifies the resulting target is
  unknown, temporary state is absent, and recovery metadata is cleaned only
  after finalization.
- AC-59-05: PASS. `finalizeMigration` follows successful initial transaction
  closure and performs target publication, delegated source close, synchronous
  destination preference write/readback, `FINALIZED`, validation, then metadata
  cleanup (`ModelDbController.java:520-540`). The fast success test asserts
  publication only after controller migration completes.
- AC-59-06: PASS. Initial and finalization failures return through source
  restoration rather than the migration path's `createEmptyDB()` method.
  `restoreTarget` records `RESTORE_PENDING`, restores and validates the
  canonical digest, and records `RESTORE_FAILED` on failure
  (`ModelDbController.java:543-599`). The final fix additionally fails closed
  when a FINALIZED target cannot be trusted: it restores only through a valid
  source or quarantines the target with recovery pending
  (`ModelDbController.java:638-668,733-769`).
  `GridMigrationFailureTest.java:234-290` covers corrupt backup recovery to
  source, missing source refusal, and target-as-source refusal.
- AC-59-07: PASS. The schema fixture changes retain the inactive-grid case, and
  the production helper remains the source-normalization seam; the migration
  controller opens the candidate separately from the active source.
- AC-59-08: PASS. The changed schema lifecycle instrumentation continues to
  exercise `32 -> 33 -> 32 -> 33`, preserving layout rows while re-upgrading
  pre-existing rows to `UNKNOWN`, as required by the accepted spec and ADR-0004.
- NFR-001: PASS. Durable same-target journal phases, source-authority
  restoration, digest validation, retry, and the new recovery-pending
  quarantine prevent a failed migration from silently selecting an untrusted
  target.
- NFR-002: PASS. Source and target identities are checked before migration and,
  for failed FINALIZED reconciliation, the source journal identity/path/existence
  checks prevent the target from being used as its own recovery source.
  `GridMigrationFailureTest.java:280-291` exercises that invalid identity.
- NFR-012: PASS. The reviewed tests cover schema lifecycle, inactive-grid
  normalization, migration success/failure, crash/retry, digest mismatch,
  preference failure, metadata cleanup retry, and the newly added invalid
  FINALIZED authority cases through `ModelDbController.tryMigrateDB`.

## Executed test surface

This audit independently inspected the PR and CI evidence and executed the
following local repository checks against
`115549f6febbb573d183190dbd788b12c0708876`:

```text
GIT_MASTER=1 git diff --no-ext-diff main..115549f6febbb573d183190dbd788b12c0708876
  -> inspected complete implementation diff (20 files, +2732/-153)

GIT_MASTER=1 git diff --check main..115549f6febbb573d183190dbd788b12c0708876
  -> no output, exit 0

gh pr view 75 -R nunu1733/NunuLauncher --json headRefOid,headRefName,commits,files,labels,state
  -> OPEN PR head is 115549f6febbb573d183190dbd788b12c0708876; it contains the audited commit

gh run view 31953268395 -R nunu1733/NunuLauncher --json event,headSha,headBranch,status,conclusion,jobs
  -> pull_request CI on the audited SHA/head branch; completed/success

gh api repos/nunu1733/NunuLauncher/actions/runs/31953268395
  -> GitHub associates the run with PR #75 and .github/workflows/ci.yml

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK, exit 0

python3 tools/repo-contract/test_validate_repo_contract.py
  -> Ran 11 tests; OK, exit 0

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> Ran 47 tests; OK, exit 0

python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 75 --head-sha 115549f6febbb573d183190dbd788b12c0708876 --root /Users/nunu/Documents/work/NunuLauncher
  -> PASS: audit covers the audited SHA with independent CI evidence
```

CI run `31953268395` is independent Gradle evidence, not a claim of local
Gradle execution: `final-status`, `organizer-unit-tests`, `check-style`, and
`build-debug-apk` each completed successfully and none of those jobs was
skipped. The run executed the configured organizer JVM test, `spotlessCheck`,
and debug-APK build surfaces. This auditor did not rerun Gradle or an emulator;
in particular, the 28 new `GridMigrationSuccessTest` plus
`GridMigrationFailureTest` instrumentation cases were inspected but not
independently executed on an AVD or device in this session.

## Findings

Verdict: pass-with-findings. The implementation at the audited SHA satisfies
AC-59-01 through AC-59-08 and NFR-001, NFR-002, and NFR-012 by independent
code/diff review plus qualifying CI evidence. No blocking defect was found.

1. The prior audit head and CI run were stale. This record is retargeted to the
   latest PR code commit and the matching qualifying pull-request CI run.
2. Instrumentation/emulator execution was not independently rerun. The
   qualifying CI source jobs do not run the Android instrumentation suite, so
   the parent session's emulator result is not represented as independent audit
   execution.
3. The final change closes the previous blocker: corrupt FINALIZED metadata can
   no longer leave a target loadable when the valid distinct source cannot be
   established. The recovery-pending exception intentionally leaves no active
   helper rather than selecting unverified target authority.
