# High-risk audit: PR #114 bounded MODEL_WRITER same-thread re-entry

> Status: final (independent audit)
> Audit date: 2026-08-23

- Auditor: independent ZCode session (subagent), not the implementing session
- PR: https://github.com/nunu1733/NunuLauncher/pull/114
- Head SHA: dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32615506202 (`ci.yml` `pull_request` run on exactly this head SHA; verified via GitHub API `actions/runs/32615506202`: `event=pull_request`, `pull_requests=[114]`, `conclusion=success`; all 10 jobs success including `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`)
- Criteria: specs/60-executor-writer-admission-audit/spec.md AC-06; specs/58-serialize-runtime-restores/spec.md AC-1, AC-3 (plus Issue #113 "Regression evidence / acceptance criteria" checklist items mapped below; Issue #113 has no spec directory, so the accepted specs governing the touched coordinator seam are used for the machine-checked reference)

## Scope

Independently read the complete diff of `dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe`
(single commit on `issue-113-model-writer-reentry`, parent `914698f784`;
+468/-0, 4 files). Working tree clean at audit time; `git diff --check` clean.

Production surface (2 files, +29):

- `src/com/android/launcher3/model/LayoutWriteCoordinator.java` (+21): new
  `tryReenterModelWriter()`. Under `synchronized (lock)` it succeeds only when
  `current != null && current.thread == Thread.currentThread() && current.kind ==
  OwnerKind.MODEL_WRITER`; increments the existing holder `recursionCount` and
  returns `new LeaseImpl(h.kind, h.token)` sharing the outer logical token. No
  new lock, kind, field, or public seam beyond this method.
- `src/com/android/launcher3/model/ModelDbController.java` (+8): inserts
  `coordinator.tryReenterModelWriter()` into `getCoordinatorLease()` between the
  restore-family re-entry and blocking acquisition. I verified the resulting
  admission order directly in source: exact organizer capability
  (`tryAcquireOrganizerCapability(currentOrganizerToken())`) → restore-family
  same-thread re-entry (`tryReenterRestoreFamily()`) → **MODEL_WRITER
  same-thread re-entry (new)** → `acquireBlockingQuietly(MODEL_WRITER)`. This
  matches Issue #113 "Required fix" verbatim.
- Unlock semantics ride the pre-existing restore-family recursion machinery,
  unchanged by this diff: `release()` decrements `recursionCount` and returns
  early while > 0; the outermost close performs `current = null`,
  `lock.notifyAll()`, and the exactly-once deferred drain (with Issue #60
  per-callback exception isolation); `LeaseImpl.close()` keeps its `closed`
  idempotence guard. Shared-writer exclusion is not weakened: exclusion comes
  from `current != null` in `acquireBlocking`, and re-entry is bounded to the
  same thread and the `MODEL_WRITER` kind only.

Test surface (2 files, +439):

- `tests/organizer-instrumentation/com/android/launcher3/organizer/
  ModelWriterTransactionReentryTest.java` (new, 389 lines, exactly 5 `@Test`
  methods, 10 s `DEADLOCK_TIMEOUT_MS` bounded harness against a real
  `ModelDbController` + SQLite DB).
- `tests/organizer-instrumentation/com/android/launcher3/organizer/
  LayoutWriteCoordinatorTest.java` (+2 tests: `modelWriterLeaseIsSameThreadReentrantWithSameToken`,
  `modelWriterReentryIsDeniedToOtherKindsAndThreads`; 8 `@Test` methods total).

No other file changed: no Launcher3/AOSP bridge outside the two named model
files, no organizer public contract, planner, diagnostics schema, recovery
schema, dependency, permission, or migration change. Runtime write paths
affected: gated mutations inside an open baseline transaction
(`acquireMutationLease()` → `getCoordinatorLease()`), i.e. the exact
`Folder.bind → updateItemLocationsInDatabaseBatch → moveItemsInDatabase →
UpdateItemsRunnable` stack from the Issue #113 ANR.

## Criteria check

Machine-checked references: specs/60-executor-writer-admission-audit/spec.md
AC-06 ("nested `SQLiteTransaction` through `ModelDbController` … inner
close/failure does not release the outer lease early"; status `implemented`)
and specs/58-serialize-runtime-restores/spec.md AC-1 (restore-family lease
continuity) / AC-3 (ordinary writes cannot enter a held window; status
`accepted`). Issue #113 checklist mapping:

- **Reproduce the old failure deterministically** (`newTransaction()` +
  `update()` inside): test exists and exercises the exact ANR shape
  (`updateInsideOpenTransactionDoesNotSelfDeadlock`). The pre-fix failing run
  (all four scenarios blocking to the 10 s bound with `Object.wait ←
  acquireBlockingQuietly ← ModelDbController.update`) was produced by the
  implementing session via stash-based baseline comparison (PR body); this
  audit did not independently rebuild an unwired variant (F3). Post-fix
  behavior independently verified below.
- **Nested MODEL_WRITER acquisition no longer self-waits**: PASS.
  `updateInsideOpenTransactionDoesNotSelfDeadlock` PASS in 0.029 s on my run.
- **Multiple updates inside one transaction retain the outer lease**:
  PASS. `multipleGatedMutationsInsideOneTransactionRetainOuterLease` PASS in
  0.01 s (spec 58 AC-1 analog for the baseline writer; lease held through
  outer close only).
- **Competing writer from another thread cannot enter while outer transaction
  open**: PASS. `competingWriterFromOtherThreadBlocksUntilOuterClose` PASS in
  0.52 s — the only multi-hundred-ms duration in the suite, i.e. real blocking
  then entry after outer close. Coordinator-level
  `modelWriterReentryIsDeniedToOtherKindsAndThreads` additionally pins that a
  different kind (RESTORE) and a different thread get `null` from
  `tryReenterModelWriter()` (spec 58 AC-3: exclusion not weakened).
- **Nested close/failure release recursion exactly once; outermost close
  unlocks exactly once** (spec 60 AC-06): PASS.
  `nestedControllerCloseReleasesRecursionOnce` 0.005 s,
  `innerMutationFailureUnwindsItsLeaseViewOnce` 0.003 s,
  `modelWriterLeaseIsSameThreadReentrantWithSameToken` asserts same token,
  held-after-nested-close, and successful third-party acquire only after the
  outermost close.
- **Organizer exact-token re-entry unchanged**: PASS. Diff does not touch
  `tryAcquireOrganizerCapability` / `tryReenter(ORGANIZER)` /
  `runWithOrganizerCapability`; `organizerLeaseIsExclusiveAndReentrantOnlyForOwner`
  and `exactCorrelatedLoaderGetsScopedCapabilityAndTokenlessWorkDefers` PASS.
- **Restore-family re-entry unchanged** (spec 58 AC-1): PASS on my run.
  `performRestoreReentersOuterRestoreFamilyLease`,
  `restoreFamilyIsReentrantAcrossRestoreKindsAndExcludesOthers`,
  `concurrentModelWriterAcquisitionBlocksUntilRestoreLeaseRelease`,
  `tokenlessModelAndProviderWorkDeferBehindRestoreLease`,
  `directPerformRestoreAcquiresLeaseBeforeOpeningDb` all PASS;
  `tryReenterRestoreFamily()` untouched by the diff.
- **Folder-containing workspace exercises the real
  `Folder.bind → … → UpdateItemsRunnable` path without deadlock; cold start
  completes binding with a usable window**: NOT verified locally. No
  end-to-end cold-start/folder-bind instrumentation exists in this PR; the
  regression test reproduces the lease-admission shape, not the binder stack.
  Remains a device-level acceptance item (F1).
- **Pixel 9a / Android 17 manual verification** (Home loads, drawer opens,
  long-press works, no `data_app_anr`): NOT verifiable in this environment.
  Remains open (F1).
- **Existing Issue #14/#58/#60 shared-writer, recovery, restore, and migration
  suites remain green**: PARTIAL / PASS-WITH-NOTES. On the API 36 emulator I
  personally observed 5 failures in suites that are compile-only in CI:
  `NestedTransactionTest#innerCloseWithoutCommitRollsBackInnerWrites`,
  `RestoreLeaseSerializationTest#closeActiveHelperClosesHandleAndReopensFreshHelper`,
  and `OrganizerReloadSupersessionTest#{staleCompletionIsRejectedAfterSupersession,
  subsequentRequestSupersedesPriorRequest, cancellationByForceReloadIsTerminalExactlyOnce}`.
  These are exactly the failures the PR declares pre-existing on unmodified
  `main`; I did not independently reproduce the `main` baseline comparison
  (F2/F3). Everything else in these suites passed on my run (22 tests, 5
  failures, enumerated in Executed test surface). Unit-level organizer gate is
  fully green and CI is fully green.
- **spotlessCheck, targeted unit/integration tests, debug APK assembly,
  repository-contract validation, `git diff --check`**: PASS, all re-executed
  by me (commands below).

## Executed test surface

All commands re-executed personally on head `dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe`
(JDK 21 `/opt/homebrew/opt/openjdk@21`, `ANDROID_HOME=/opt/homebrew/share/
android-commandline-tools`, emulator `nunu_qpr2_api36_1`, API 36
`sdk_gphone64_arm64 emu64a BE4B.251210.005`, `sys.boot_completed=1`).
Instrumentation XML parsed by me from
`build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/*.xml`.

```text
git rev-parse HEAD
  -> dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe (branch issue-113-model-writer-reentry, tree clean)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest
  -> BUILD SUCCESSFUL; XML: tests=5 failures=0 errors=0 skipped=0
     updateInsideOpenTransactionDoesNotSelfDeadlock PASS 0.029s
     multipleGatedMutationsInsideOneTransactionRetainOuterLease PASS 0.010s
     nestedControllerCloseReleasesRecursionOnce PASS 0.005s
     innerMutationFailureUnwindsItsLeaseViewOnce PASS 0.003s
     competingWriterFromOtherThreadBlocksUntilOuterClose PASS 0.520s

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.LayoutWriteCoordinatorTest
  -> BUILD SUCCESSFUL; XML: tests=8 failures=0 errors=0 skipped=0
     (incl. modelWriterLeaseIsSameThreadReentrantWithSameToken,
      modelWriterReentryIsDeniedToOtherKindsAndThreads)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.BinderOperationFutureTest,com.android.launcher3.organizer.NestedTransactionTest,com.android.launcher3.organizer.RestoreLeaseSerializationTest,com.android.launcher3.organizer.OrganizerReloadSupersessionTest
  -> BUILD FAILED (expected: 5 failures, all matching the PR's declared
     pre-existing-on-main set):
     BinderOperationFutureTest 3/3 PASS
     NestedTransactionTest 4/5 PASS (FAIL: innerCloseWithoutCommitRollsBackInnerWrites)
     RestoreLeaseSerializationTest 8/9 PASS (FAIL: closeActiveHelperClosesHandleAndReopensFreshHelper)
     OrganizerReloadSupersessionTest 2/5 PASS (FAIL: staleCompletionIsRejectedAfterSupersession,
       subsequentRequestSupersedesPriorRequest, cancellationByForceReloadIsTerminalExactlyOnce)

./gradlew spotlessCheck
  -> BUILD SUCCESSFUL

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun
  -> BUILD SUCCESSFUL; aggregated from build/test-results/testLawnWithQuickstepGithubDebugUnitTest/*.xml
     (59 files, mtimes from this rerun): 719 tests, 0 failures, 0 errors, 0 skipped

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK (exit 0)
python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> Ran 47 tests — OK
python3 tools/repo-contract/validate_writer_inventory.py
  -> PASS: 18 writer files verified against allowlist (1070 source files scanned, 0 errors, 0 warnings)

./gradlew assembleLawnWithQuickstepGithubDebug
  -> BUILD SUCCESSFUL (Lawnchair.15.Dev.(dd2e8bf).github.debug.apk)

git diff --check
  -> clean

gh api repos/nunu1733/NunuLauncher/actions/runs/32615506202
  -> event=pull_request, head_sha=dd2e8bfce4d26bf36c5e50c974fdd9473538fcbe,
     pull_requests=[114], conclusion=success; jobs API: all 10 jobs success
     incl. final-status, organizer-unit-tests, check-style, build-debug-apk,
     validate-repo-contract
```

CI executes instrumentation suites compile-only/on API 35 runners per prior
decisions; the API 36 runtime observations above are from this local emulator
only.

## Findings

Verdict: **pass-with-notes**. The fix matches Issue #113's required admission
order and bounds exactly; shared-writer exclusion is preserved and regression-
tested; mergeable once this audit record lands as a docs-only commit and the
high-risk gate re-runs green on the new head. Docs-only delta after the audited
SHA is allowed by the gate.

1. **[Blocker-for-issue-close, out-of-PR-scope] F1 — Device acceptance items
   remain open.** Pixel 9a / Android 17 manual dogfood re-test (Home loads,
   app drawer, long-press, no `data_app_anr`) and the real
   `Folder.bind`-containing cold start on hardware are not executable in this
   environment and were not replaced by any local proxy beyond the
   lease-shape regression test. Issue #113 must not close until both are done
   with this build.
2. **[Low] F2 — Five pre-existing API 36 instrumentation failures observed
   first-hand** (enumerated under Executed test surface). They are compile-only
   in CI, so API 36 execution never happens there. The implementer states they
   fail identically on unmodified `main` (stash-based baseline); this audit did
   not independently rebuild `main` to confirm identity. Per #113's evidence
   rule they are recorded individually without collapsing root causes; the
   promised follow-up tracking issue(s) for the API 36 nested-transaction /
   supersession / helper-reopen findings must actually be filed and kept out of
   this PR.
3. **[Low] F3 — Pre-fix deadlock reproduction not independently re-executed.**
   Reproducing the four-scenario failing-first evidence requires running the
   new suite against production code without the wiring; I verified the test
   exercises the exact Issue #113 shape against a real `ModelDbController` and
   passes with the fix, and rely on the implementing session's recorded
   baseline run for the failing half.
4. **[Open] F4 — Issue #104 reclassification** ("can #104 be reclassified after
   revisiting API 36 evidence") is part of the #113 handoff but is not
   decidable from this audit: no API 36 thread dump ties the #104 incident to
   this coordinator defect. Keep as a separate tracked question.
5. **[Info] F5 — Harness property.** `DEADLOCK_TIMEOUT_MS = 10_000` converts a
   reintroduced self-deadlock into a bounded failure (~10 s per scenario)
   instead of a wedged suite; acceptable regression-detection cost.

## Post-audit note (implementing session, 2026-08-23)

Recorded after the audit above; auditor text is unchanged.

- F2's required follow-up tracking issue was filed as
  [#115](https://github.com/nunu1733/NunuLauncher/issues/115) (API 36
  nested-transaction semantics, supersession failures, and helper close/reopen
  failure kept as separately enumerated findings).
- The `main`-baseline identity of the five API 36 failures referenced in F2
  was produced by the implementing session via `git stash -u` + re-run of the
  same suites on unmodified `main` (per-suite XML results in the PR body and
  session log); F3's pre-fix failing-first evidence was produced the same way
  before the coordinator wiring was added. Both remain implementer-produced
  evidence; the auditor's independent re-execution covered the post-fix side.
