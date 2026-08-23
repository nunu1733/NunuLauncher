# High-risk audit: PR #114 bounded MODEL_WRITER same-thread re-entry

> Status: final (independent re-audit of the current head after the review round)
> Audit date: 2026-08-23

Machine-checked fields:

- Auditor: independent ZCode session (subagent), not the implementing session
- Audit date: 2026-08-23
- Head SHA: `74e2f593242a72dfbd6d3ae23b9d266bb83be629`
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32617773591 — verified via GitHub API `actions/runs/32617773591`: `event=pull_request`, `head_sha=74e2f593242a72dfbd6d3ae23b9d266bb83be629` (identical to `gh pr view 114 --json headRefOid`), `pull_requests=[114]`, `status=completed`, `conclusion=success`. Jobs API for this run: all 11 jobs `success`, including `organizer-instrumentation-shared-writer-tests` (`success`, executed — job step "Run shared-writer coordinator instrumentation (API 36 / Platform 36.1)" succeeded; only its `if: failure()` artifact-upload step was skipped) and `final-status`.
- Criteria: specs/60-executor-writer-admission-audit/spec.md AC-06 ("Nested `SQLiteTransaction` through `ModelDbController` commits/rolls back as one unit; inner close/failure does not release the outer lease early"); specs/58-serialize-runtime-restores/spec.md AC-1 (one restore-family lease held across the window) and AC-3 (ordinary model/provider writes cannot enter a held window). Issue #113 has no spec directory; its own "Regression evidence / acceptance criteria" checklist items are mapped individually below against these accepted specs governing the touched coordinator seam.

## Scope

Audited head `74e2f593242a72dfbd6d3ae23b9d266bb83be629` on branch
`issue-113-model-writer-reentry` (local `git rev-parse HEAD` == GitHub PR head;
working tree clean). The full PR delta over base `4ec0eb3dc692eadf108c512df5de3cb1607cf1f5`
(`main`, "docs: reconcile MVP release readiness (#111)") spans three commits,
all read in full:

1. `dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe` — fix + tests (+468/-0, 4 files):
   - `src/com/android/launcher3/model/LayoutWriteCoordinator.java` (+21): new
     `tryReenterModelWriter()`.
   - `src/com/android/launcher3/model/ModelDbController.java` (+8): admission-order
     insertion in `getCoordinatorLease()`.
   - `tests/organizer-instrumentation/.../ModelWriterTransactionReentryTest.java`
     (new, 5 `@Test` methods, 10 s bounded harness).
   - `tests/organizer-instrumentation/.../LayoutWriteCoordinatorTest.java` (+2 tests:
     `modelWriterLeaseIsSameThreadReentrantWithSameToken`,
     `modelWriterReentryIsDeniedToOtherKindsAndThreads`; 8 total).
2. `40d01692` — docs-only first audit record (superseded by this file).
3. `74e2f593` — review response, non-production (+54/-0, 3 files):
   `.github/workflows/ci.yml` new permanent lane `organizer-instrumentation-shared-writer-tests`;
   `docs/engineering/ci-test-portfolio.md` lane registration;
   `docs/engineering/quality-strategy.md` required-evidence paragraph.

No production code changed between `dd2e8bfc` and the audited head; no Launcher3/AOSP
bridge outside the two named model files, no organizer public contract, planner,
diagnostics/recovery schema, dependency, permission, or migration change.

## Independent source verification (current head)

Read directly at HEAD, not from the diff only:

- `LayoutWriteCoordinator.tryReenterModelWriter()` (lines 143–154): under
  `synchronized (lock)` it succeeds only when `current != null &&
  current.thread == Thread.currentThread() && current.kind == OwnerKind.MODEL_WRITER`;
  increments the existing holder's `recursionCount` and returns a `LeaseImpl(h.kind,
  h.token)` sharing the outer logical token. No second lock or seam introduced.
- `release()` (lines 337–370): decrements `recursionCount` and returns early while
  > 0; the outermost close performs `current = null`, drains the deferred FIFO
  exactly once with per-callback exception isolation (Issue #60), and
  `lock.notifyAll()`. `LeaseImpl.close()` keeps its `closed` idempotence guard.
  Shared-writer exclusion is not weakened: `acquireBlocking` waits while
  `current != null` regardless of kind; re-entry stays bounded to same thread +
  `MODEL_WRITER` kind only.
- `ModelDbController.getCoordinatorLease()` (lines ~319–344): I confirmed the
  explicit admission order myself: exact organizer capability
  (`tryAcquireOrganizerCapability(currentOrganizerToken())`) → restore-family
  same-thread re-entry (`tryReenterRestoreFamily()`) → **MODEL_WRITER same-thread
  re-entry (`tryReenterModelWriter()`, new)** → blocking acquisition
  (`acquireBlockingQuietly(MODEL_WRITER)`). This matches Issue #113 "Required fix"
  verbatim.
- `.github/workflows/ci.yml` at HEAD: `organizer-instrumentation-shared-writer-tests`
  (line 197) runs on `ubuntu-latest` with `reactivecircus/android-emulator-runner@v2`,
  `api-level: 36` / `google_apis` / `x86_64`, invoking exactly
  `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.BinderOperationFutureTest`,
  and is listed in `final-status.needs` (line 454). The regression therefore runs
  permanently in the merge gate on every source-changing PR, directly verifying the
  fix in the audited CI run.
- `tests/.../ModelWriterTransactionReentryTest.java`: exercises the real
  `ModelDbController` against an isolated SQLite DB with the exact Issue #113 ANR
  shape (`newTransaction()` + gated `update()` inside, plus insert/delete variants,
  nested close/failure unwinding, and a competing-thread writer that must stay
  blocked until outer close).

## Issue #113 acceptance checklist mapping

Evidence marked "(my run)" below was executed by me personally on the audited head
in the environment listed under Executed test surface.

- [x] **Reproduce the old failure deterministically** (`newTransaction()` + `update()`
      inside): test exists and reproduces the exact lease-admission shape. The
      failing-first (pre-fix) run remains implementer-produced evidence (stash-based
      baseline); see Finding F3. Post-fix side independently verified (my run).
- [x] **Nested MODEL_WRITER acquisition no longer self-waits**: PASS (my run).
      `updateInsideOpenTransactionDoesNotSelfDeadlock` 0.001 s.
- [x] **Multiple updates inside one SQLiteTransaction retain the outer writer lease
      until close**: PASS (my run). `multipleGatedMutationsInsideOneTransactionRetainOuterLease`
      (update+insert+delete, mid-transaction probe asserts lease still held,
      post-close acquire asserts unlock) 0.0 s. Spec 58 AC-1 analog for the
      baseline writer.
- [x] **Competing writer from another thread cannot enter while the outer transaction
      remains open**: PASS (my run). `competingWriterFromOtherThreadBlocksUntilOuterClose`
      0.512 s (500 ms settle window proves exclusion, then serialized entry). Spec 58
      AC-3 analog.
- [x] **Nested close/failure release recursion exactly once; outermost close unlocks
      exactly once** (spec 60 AC-06): PASS (my run). `nestedControllerCloseReleasesRecursionOnce`
      0.032 s, `innerMutationFailureUnwindsItsLeaseViewOnce` 0.005 s, plus
      `modelWriterLeaseIsSameThreadReentrantWithSameToken` (same token, still held
      after nested close, third-party acquire only after outermost close) 0.002 s.
- [x] **Existing organizer exact-token re-entry unchanged**: PASS (my run). Diff does
      not touch `tryAcquireOrganizerCapability` / `tryReenter(ORGANIZER)` /
      capability installation; `organizerLeaseIsExclusiveAndReentrantOnlyForOwner`
      and `exactCorrelatedLoaderGetsScopedCapabilityAndTokenlessWorkDefers` PASS;
      deferred-FIFO/Issue #60 tests PASS.
- [x] **Existing restore-family re-entry unchanged**: PASS by construction + spot
      check. `tryReenterRestoreFamily()` and the restore paths have zero diff; my run
      pins the exclusion direction (`modelWriterReentryIsDeniedToOtherKindsAndThreads`:
      a RESTORE holder gets no MODEL_WRITER re-entry; a foreign thread gets none).
      The dedicated restore-family suites were not part of this round's executed
      class set (see Not independently verified).
- [ ] **Folder-containing workspace exercises the real
      `Folder.bind → updateItemLocationsInDatabaseBatch → moveItemsInDatabase →
      UpdateItemsRunnable` path without deadlock** and **cold start completes binding
      with a usable window**: NOT verifiable here (no end-to-end cold-start/folder-bind
      instrumentation exists; the regression test covers the lease-admission shape,
      not the binder stack). Remains open on issue #113.
- [ ] **Pixel 9a / Android 17 manual verification** (Home loads, drawer opens,
      long-press works, no `data_app_anr`): NOT executable in this environment.
      Remains open on issue #113 (concrete steps recorded in the #113 progress
      comment of 2026-08-23).
- [~] **Existing Issue #14/#58/#60 shared-writer, recovery, restore, and migration
      suites remain green**: PASS on my executed surface — the three-class seam suite
      16/16 (my run), JVM organizer gate 719/719 (my run), and the audited CI run is
      fully green including the other instrumentation lanes (issue52/issue53/issue99/
      api35). The known pre-existing API 36 emulator failures
      (`NestedTransactionTest#innerCloseWithoutCommitRollsBackInnerWrites`,
      `RestoreLeaseSerializationTest#closeActiveHelperClosesHandleAndReopensFreshHelper`,
      3 × `OrganizerReloadSupersessionTest`) were observed by the previous audit round
      and are tracked as issue #115; they are compile-only in CI and were not
      re-executed in this round (see Not independently verified).
- [x] **spotlessCheck, targeted unit/integration tests, debug APK assembly,
      repository-contract validation, `git diff --check` pass**: PASS (my runs).
      Debug APK buildability is evidenced by the packaging tasks inside the connected
      test build (`packageLawnWithQuickstepGithubDebug` executed) and by CI
      `build-debug-apk` success; I did not separately invoke `assembleLawnWithQuickstepGithubDebug`.

PR #114 intentionally leaves the four device-side items above open on issue #113
instead of claiming them. **Deviation found:** despite this intent (and commit
`74e2f593`'s own message claiming otherwise), the live PR body **still begins with
"Closes #113"** as of audit time (verified three times via `gh api
repos/nunu1733/NunuLauncher/pulls/114` and `gh pr view`, body updated_at
2026-08-23T04:23:53Z). Merging as-is would auto-close issue #113 while its device
acceptance checklist is unfinished. See Finding F0.

## Executed test surface (this audit)

All commands re-executed personally on head `74e2f593242a72dfbd6d3ae23b9d266bb83be629`
(JDK 21 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
`ANDROID_HOME=/opt/homebrew/share/android-commandline-tools`; emulator
`nunu_qpr2_api36_1`, API 36, `sys.boot_completed=1`). Instrumentation XML parsed by
me from `build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/*.xml`.

```text
git rev-parse HEAD && gh pr view 114 --json headRefOid
  -> 74e2f593242a72dfbd6d3ae23b9d266bb83be629 (match); working tree clean

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.BinderOperationFutureTest
  -> BUILD SUCCESSFUL; "Starting 16 tests ... Finished 16 tests"; XML aggregate:
     tests=16 failures=0 errors=0 skipped=0
     ModelWriterTransactionReentryTest 5/5:
       updateInsideOpenTransactionDoesNotSelfDeadlock 0.001s
       multipleGatedMutationsInsideOneTransactionRetainOuterLease 0.0s
       nestedControllerCloseReleasesRecursionOnce 0.032s
       innerMutationFailureUnwindsItsLeaseViewOnce 0.005s
       competingWriterFromOtherThreadBlocksUntilOuterClose 0.512s
     LayoutWriteCoordinatorTest 8/8 (incl. modelWriterLeaseIsSameThreadReentrantWithSameToken,
       modelWriterReentryIsDeniedToOtherKindsAndThreads)
     BinderOperationFutureTest 3/3

./gradlew spotlessCheck --rerun-tasks
  -> BUILD SUCCESSFUL (5 tasks executed)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun-tasks
  -> BUILD SUCCESSFUL in 1m21s (386 tasks executed); aggregated from
     build/test-results/testLawnWithQuickstepGithubDebugUnitTest/*.xml
     (59 files, mtimes from this rerun): 719 tests, 0 failures, 0 errors, 0 skipped

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK (exit 0)
python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> Ran 47 tests — OK (exit 0)
python3 tools/repo-contract/validate_writer_inventory.py
  -> PASS: 18 writer files verified against allowlist (1070 source files scanned,
     0 errors, 0 warnings)

git diff --check
  -> clean

gh api repos/nunu1733/NunuLauncher/actions/runs/32617773591
  -> event=pull_request, head_sha=74e2f593242a72dfbd6d3ae23b9d266bb83be629,
     pull_requests=[114], conclusion=success
gh api .../runs/32617773591/jobs
  -> all 11 jobs success incl. organizer-instrumentation-shared-writer-tests
     (instrumentation step itself success, not skipped) and final-status
```

## Not independently verified (honest record)

1. The failing-first (pre-fix) reproduction and the unmodified-`main` identity of the
   five pre-existing API 36 instrumentation failures are implementer-produced
   evidence (prior round + stash-based baselines recorded in the PR body and issue
   #115). Reproducing them requires running production code without the wiring or a
   separate `main` checkout; this audit verified the post-fix side and the source
   shape instead (F3/F2).
2. Device acceptance items (Pixel 9a / Android 17 re-test; real folder-containing
   cold start on hardware) — impossible in this environment; open on issue #113 (F1).
3. Restore-family and app-state instrumentation suites beyond this round's executed
   class set were covered by the green CI lanes on the audited head, not by local
   re-execution here.
4. Whether issue #104 can be reclassified given API 36 evidence — not decidable from
   this audit; no thread dump ties the #104 incident to this defect (F4).

## Findings

Verdict: **pass-with-notes, conditional on one pre-merge body fix (F0)**. The fix
matches Issue #113's required admission order and bounds exactly; shared-writer
exclusion is preserved and regression-tested; the deadlock regression now runs
permanently in the merge-gate CI on API 36, and the audited CI run directly verifies
it (job executed and green). All machine-checked gate requirements are met on the
audited head.

1. **[Must fix before merge] F0 — PR body still declares `Closes #113`.**
   Verified via the GitHub API three times at audit time. This contradicts both the
   review decision (device acceptance items must stay open on #113) and commit
   `74e2f593`'s own message ("PR #114 no longer declares Closes"). One-line PR-body
   edit; no code change needed. Merging without it auto-closes #113 prematurely.
2. **[Open, out-of-PR-scope] F1 — Device acceptance items remain open** on issue
   #113: Pixel 9a / Android 17 manual dogfood re-test and the real folder-containing
   cold start on hardware. Issue #113 must not close until both are done with a
   build containing this fix.
3. **[Low] F2 — Five pre-existing API 36 instrumentation failures** (enumerated
   above) remain compile-only in CI outside the new seam lane; tracked as issue #115.
   Not blocking this PR.
4. **[Low] F3 — Pre-fix failing-first evidence is implementer-produced**, not
   independently re-executed by either audit round. Post-fix behavior is
   independently verified twice on two heads.
5. **[Info] F4 — Issue #104 reclassification** remains an open tracked question on
   #113's handoff; undecidable here.
6. **[Info] F5 — Harness property.** `DEADLOCK_TIMEOUT_MS = 10_000` converts a
   reintroduced self-deadlock into a bounded per-scenario failure (~10 s) instead of
   a wedged suite; acceptable regression-detection cost.
