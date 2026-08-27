# Implementation Plan: Hotseat tokenless writer atomic admission

> Issue: #156
> Spec: [spec.md](./spec.md)
> Status: accepted
> Baseline examined: `7ba2194ce711ce8e9a9c7abe5957919d566e26dd` (`main`, 2026-08-27)

## Current evidence

- `HotseatPredictionController.setPredictedItems()` invokes
  `HotseatRestoreHelper.restoreBackup()` when its prediction batch is empty.
  Both `createBackup()` and `restoreBackup()` submit a runnable directly to
  `MODEL_EXECUTOR`; that runnable opens tokenless
  `ModelDbController.newTransaction()`.
- The ordinary `newTransaction()` path falls through to
  `LayoutWriteCoordinator.acquireBlockingQuietly(MODEL_WRITER)` when no exact
  organizer capability, restore-family reentry, or same-thread model-writer
  reentry applies. `LayoutWriteCoordinator` expressly prohibits this blocking
  acquisition from MODEL_EXECUTOR while an organizer lease is active.
- `LayoutWriteCoordinator.runOrDefer(...)` already queues tokenless work while
  an `ORGANIZER` or restore-family lease is held. Its deferred callback drain
  runs after the outer lease releases and isolates each entry exception, which
  preserves later FIFO entries.
- A helper-local call of
  `runOrDefer(MODEL_WRITER, 0L, false, () -> MODEL_EXECUTOR.execute(dbWork))`
  is **not sufficient**. When the coordinator is empty at gate evaluation but
  another thread obtains an organizer/restore-family lease before `dbWork`
  begins, the original `newTransaction()` can still synchronously block the
  single model thread.
- The exact correlated organizer LoaderTask uses the existing organizer
  capability path and must remain distinct from unrelated tokenless work. The
  existing `SyncPageSelectionBarrier` test pattern establishes correlated-load
  progress without using wall-clock scheduling as a correctness oracle.
- The Issue #60 writer-inventory scanner currently examines `src` and
  `lawnchair/src`, but not `quickstep/src`. A focused source audit finds the
  helper's `restoreBackup` transaction in quickstep; the scanner's current
  receiver-name pattern also misses `createBackup`'s local `dbController`
  receiver. Therefore the current green inventory result is incomplete for
  this issue's direct path.
- Device thread-dump/journal evidence in #156 identifies starvation as a
  distinct third layer after #150's completion-order correction. #155 owns the
  independent QSB-reservation layout-overlap cause and remains outside this
  change.
- Implementation review found that execution-time atomic admission alone does
  not preserve `HotseatEduController.migrate()` ordering: while an external
  holder is active, `createBackup()` only posts to MODEL_EXECUTOR whereas the
  later `ModelWriter` mutation reserves the coordinator FIFO immediately. The
  resulting post-migration snapshot violates backup semantics even though it
  no longer blocks MODEL_EXECUTOR.

| Evidence source | Relevant established fact |
|---|---|
| [Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156) | The helper blocks `launcher-loader` on the organizer lease and pushes recovery LoaderTask behind it until the 10-second adapter timeout. |
| [Issue #156 review](https://github.com/nunu1733/NunuLauncher/issues/156#issuecomment-5432714104) | The initial helper-local `runOrDefer` proposal leaves an admission-to-execution race; inventory must not overclaim structural gate enforcement; quickstep audit needs a scope boundary. |
| [Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150) | The diagnosis explicitly split the executor-starvation cause into #156 and the layout-overlap cause into #155. |
| [Spec #60](../60-executor-writer-admission-audit/spec.md) | Tokenless MODEL_EXECUTOR work must use the established coordinator ownership/deferral model; no parallel public seam is allowed. |
| [Spec #13](../13-safe-layout-application/spec.md) | A7/recovery must wait for the specific correlated reload and verify DB/model convergence; reload failure must recover truthfully. |
| [Spec #58](../58-serialize-runtime-restores/spec.md) | Ordinary tokenless model work defers while a restore-family lease is active. |

## Design

### Modules and interfaces

The solution keeps all serialization ownership in `LayoutWriteCoordinator`.
`HotseatRestoreHelper` uses two linked admission stages. At call time it uses
existing `runOrDefer(MODEL_WRITER, 0L, false, ...)` to reserve a FIFO position
when an organizer or restore-family holder is already present. The reserved
callback only posts to `MODEL_EXECUTOR`. At execution time, the posted body
calls the existing **internal atomic lease-or-defer operation** instead of
invoking ordinary tokenless `newTransaction()` first. The first stage preserves
logical submission order; the second stage closes the post-scheduling TOCTOU.

The operation accepts the Hotseat DB body and an executor hand-off callback. It
performs exactly one decision while holding the coordinator monitor:

1. When no holder exists, it creates a `MODEL_WRITER` lease and transfers that
   lease to a wrapper which executes the DB body.
2. When the caller is already the same-thread `MODEL_WRITER` owner, it obtains
   the existing supported model-writer reentry lease and transfers that view to
   the wrapper.
3. For every other active holder, including organizer, restore-family,
   grid-migration, or another thread's model writer, it appends a deferred
   continuation to the existing FIFO. The continuation submits the *same
   atomic admission attempt* back to `MODEL_EXECUTOR`; it never runs the DB
   body inline on the lease-releasing thread.

The wrapper owns the outer admission lease and closes it in `finally`. Inside
it, the unchanged Hotseat DB body calls ordinary `newTransaction()`. That call
uses existing same-thread `MODEL_WRITER` reentry, so its inner transaction
lease ends with `SQLiteTransaction.close()` while the outer admission lease
protects the full body. If DB-open/transaction creation throws before the
inner transaction owns a lease, the wrapper's `finally` still releases the
outer admission lease. This avoids a new `ModelDbController` API and preserves
the established transaction close semantics.

| Seam | Responsibility after this change | Explicitly unchanged |
|---|---|---|
| `HotseatRestoreHelper` | Reserve call-time FIFO order through existing `runOrDefer`, then submit both existing bodies to MODEL_EXECUTOR where each invokes atomic coordinator admission. Retain DB work, commits, cache refresh, and normal reload behavior. | Public helper methods, backup-table format, callers, user-visible outcome surface, and executor affinity of DB work. |
| `LayoutWriteCoordinator` | Atomically grant/reenter a MODEL_WRITER lease or enqueue a MODEL_EXECUTOR continuation in its existing FIFO. The only serialization lock remains its existing monitor. | Existing owner kinds, exact organizer capability, `runOrDefer` behavior for existing callers, release isolation, and public product semantics. |
| `ModelDbController` | Continue to create transactions by same-thread model-writer reentry inside an already-admitted wrapper. | Ordinary tokenless blocking fallback and organizer/restore reentry rules for existing callers. |
| `OrganizerModelReloadAdapter` / `LauncherModel` | Continue to execute the exact correlated loader under its scoped organizer capability. | Completion protocol, timeout policy, and result types. |
| test-only hooks | Expose only package-private/`@VisibleForTesting` executor/probe controls needed to stage the admission race and restore global state in teardown. | No user-facing configuration, runtime bypass, or new production control surface. |
| writer-inventory validator | Audit `quickstep/src` and record helper transaction paths. | It verifies writer presence and allowlist registration only; it does not claim to prove gate structure. |

### Data flow

```text
HotseatRestoreHelper.createBackup() / restoreBackup()
  -> coordinator.runOrDefer(MODEL_WRITER, 0L, false,
       () -> MODEL_EXECUTOR.execute(() -> coordinator.runModelWriterOrDefer(
           dbBody, retry -> repeat call-time reservation then MODEL_EXECUTOR post)))

  existing external organizer/restore holder at call time
    -> append the MODEL_EXECUTOR-post callback to FIFO immediately
    -> preserve position before later ModelWriter submission
    -> never execute DB work in the holder-release callback

inside one coordinator critical section:
  current == null
    -> acquire MODEL_WRITER lease
    -> execute dbBody in lease-owning wrapper
    -> dbBody.newTransaction() reenters MODEL_WRITER
    -> close inner transaction lease, then outer admission lease

  current == same MODEL_EXECUTOR thread + MODEL_WRITER
    -> same-thread reentry lease
    -> execute dbBody in wrapper, then close reentry view

  current != null otherwise
    -> append { MODEL_EXECUTOR.execute(same atomic attempt) } to existing FIFO
    -> return from MODEL_EXECUTOR without DB access or waiting

outer holder releases
  -> coordinator drains FIFO in call-time order
  -> each helper reservation posts its atomic attempt to MODEL_EXECUTOR
  -> MODEL_EXECUTOR retries the atomic decision before DB work
  -> a holder that appeared after the call-time reservation causes re-deferral
```

This establishes the required safety property for both interleavings. If an
outer lease already exists when the helper runs, the task is queued. If that
lease is acquired only after the helper has been scheduled but before it starts
on MODEL_EXECUTOR, the atomic decision observes the holder and queues the same
continuation. There is no interval between a successful admission decision and
writer ownership.

The only new transient state is an entry in the coordinator's existing
in-memory deferred FIFO plus a short-lived outer `MODEL_WRITER` lease while
the admitted DB body runs. No Launcher row, schema, recovery point, backup
file, process-restart behavior, or user-visible state is added or reinterpreted.

### Deterministic regression oracle

The regression test must test both review-identified properties, not merely the
already-held lease case. The existing TOCTOU scenario stages an empty
coordinator at helper scheduling and an organizer lease before executor
admission. A new call-time-order scenario stages:

1. Acquire `ORGANIZER`.
2. Invoke concrete helper scheduling for `createBackup`.
3. Submit a following `ModelWriter`-equivalent migration mutation through
   `runOrDefer`.
4. Release `ORGANIZER`, then deterministically drain MODEL_EXECUTOR.
5. Assert that the backup DB-body event precedes the migration mutation event,
   the release callback executed neither body, and each event occurs once.

The pre-existing race oracle must also test the review-identified race, not merely the
already-held lease case. Add a bounded test-only executor/probe arrangement
which lets the test stage these ordered events:

1. Invoke the concrete Hotseat helper while the coordinator is empty.
2. Confirm that the initial task has been accepted by MODEL_EXECUTOR but has
   not yet attempted admission.
3. On a separate test thread, acquire the `ORGANIZER` lease successfully.
4. Release the MODEL_EXECUTOR admission barrier.
5. Observe a mutually exclusive result:
   - **pre-fix:** the legacy tokenless `ModelDbController` fallback emits its
     test-only `beforeBlockingAcquire` probe and is held there; this is the
     intentionally-red result, because it reaches blocking admission while the
     organizer owns the lease;
   - **post-fix:** the atomic operation emits its `deferred` probe/queue count,
     returns MODEL_EXECUTOR, and never emits `beforeBlockingAcquire`.
6. While retaining the organizer lease, issue the exact-token correlated reload
   and assert `COMPLETED`. Finally release the organizer lease and assert one
   continuation hand-off and one DB-body execution.

The latches give the test its causal ordering. Every timeout is only a bounded
hang guard; the oracle is which admission event occurred and whether the
correlated reload completes before the explicit lease release. Test-only hooks
must be removed/restored in `@After`/`finally` and must never change production
admission behavior.

### Alternatives rejected

| Alternative | Reason for rejection |
|---|---|
| Helper-local `runOrDefer(..., () -> MODEL_EXECUTOR.execute(dbWork))` only. | Fails the review's admission-to-execution race: a holder can appear after the immediate gate decision and before `dbWork.newTransaction()`. |
| Execution-time atomic admission only. | Prevents blocking but lets a later `ModelWriter` reserve FIFO position before a previously called `createBackup`, producing a post-migration snapshot. Call-time reservation is required in addition. |
| Add a second lock around Hotseat backup/restore. | Violates Issue #156 and Spec #60: `LayoutWriteCoordinator` remains the only serialization seam. It also creates an unreviewed lock order. |
| Change every tokenless `ModelDbController.newTransaction()` to fail fast or defer implicitly. | Broadly changes baseline caller contracts and hides ownership policy. This plan confines atomic admission to a known self-posted MODEL_EXECUTOR writer. |
| Pass organizer token into Hotseat helper and grant cross-thread reentry. | The helper is unrelated tokenless work. Granting capability would defeat the scoped correlated-loader boundary and permit writes during organizer verification. |
| Run the deferred DB body inline during `Lease.close()`. | Changes executor affinity and may put DB work on the organizer release path. FIFO entries must resubmit the admission attempt to MODEL_EXECUTOR. |
| Increase `OrganizerModelReloadAdapter.TIMEOUT_MILLIS` or add sleeps/retries. | Masks starvation rather than removing it, slows failure reporting, and does not create a deterministic regression oracle. |
| Extend #156 to repair every path uncovered by quickstep scanning. | The issue's direct cause is tokenless MODEL_EXECUTOR blocking admission. Other lifecycle/ownership defects require their own risk assessment and Issue. |
| Fold #155's QSB-overlap repair into this change. | The issues have distinct causal paths, acceptance criteria, and high-risk rollback surfaces. Mixing them weakens causality and reviewability. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | Add a narrowly scoped internal atomic `MODEL_WRITER` lease-or-defer operation. It grants/reenters the lease or registers an executor resubmission continuation under the same monitor; a lease-owning wrapper closes in `finally`. Reuse the existing FIFO/entry exception isolation. | Only the coordinator can make admission and ownership one indivisible decision without another lock. |
| `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | Use existing call-time `runOrDefer` to reserve external-holder FIFO position, and have the reservation post the same execution-time atomic coordinator operation. Both public helper methods share one private route. | This preserves `createBackup` before later migration writes while execution-time admission closes the post-scheduling race. |
| `src/com/android/launcher3/model/ModelDbController.java` (test support only, if necessary) | Add a narrowly scoped `@VisibleForTesting` probe immediately before the legacy tokenless blocking fallback, or an equivalent non-production-observable probe. It is used only to prove the pre-fix race reaches the forbidden path. | The red test needs an event-based oracle, not an elapsed-time inference. Production behavior must stay identical. |
| `tests/organizer-instrumentation/com/android/launcher3/hybridhotseat/HotseatRestoreAdmissionTest.java` | Add API 36 instrumentation coverage for concrete `createBackup`/`restoreBackup`, the gate-empty→organizer-acquired→MODEL_EXECUTOR-start race, held-lease deferral, exact correlated reload progress, one-time continuation/body execution, no-contention behavior, missing backup table, and call-time backup-before-migration ordering. | The required interaction crosses helper, model executor, coordinator, `ModelDbController`, correlated loader, and ModelWriter FIFO; a coordinator-only test cannot prove the defect closed. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerReloadSupersessionTest.java` (reuse only) | Reuse `SyncPageSelectionBarrier` behavior or extract only the minimum package-private utility if duplication is unavoidable; do not make a second timing-based reload seam. | The current barrier already expresses deterministic correlated-reload progress. |
| `tools/repo-contract/validate_writer_inventory.py` | Add `quickstep/src` to `source_dirs`; recognize the bounded `dbController` receiver form in the existing ModelDbController mutation pattern; add `HotseatRestoreHelper.java` with a `controller-call` reason recording its atomic MODEL_EXECUTOR admission. | Both helper transaction expressions must be covered by the executable inventory. The tool's guarantee is intentionally limited to path detection/registration. |
| `docs/assessment/issue-60-executor-writer-admission-audit.md` | Update the writer-inventory count/table and document the Hotseat helper's atomic-admission reason. If scan expansion finds unrelated paths, record their exact evidence and link a new Issue rather than modifying their product behavior here. | The assessment is the human-readable companion to the executable inventory and the mandated scope stop condition. |
| `specs/156-hotseat-tokenless-writer-deferral/{spec,plan}.md` | Move status to `accepted` only after review finds no blocking issue. Update to `implemented` only after merged, verified implementation and exact evidence are recorded. | These files are the requirements and implementation-decision source of truth for #156. |

## Execution sequence

1. **Add a deterministic race harness and make it red.** Introduce only the
   smallest test-only executor/probe support needed to schedule the helper with
   an empty coordinator, then acquire `ORGANIZER` before MODEL_EXECUTOR reaches
   admission. On unmodified admission behavior, observe and hold the
   `beforeBlockingAcquire` legacy path; record this as the failing-path proof.
2. **Implement atomic coordinator admission.** Add the internal lease-or-defer
   operation and unit/instrumentation coverage for its null-holder,
   same-thread-MODEL_WRITER-reentry, external-holder-defer, deferred-resubmit,
   exception, and finally-close behavior. Do not change `runOrDefer` callers or
   global `newTransaction()` fallback semantics.
3. **Route both helper paths through it.** Refactor only scheduling/admission in
   `createBackup` and `restoreBackup`. Preserve SQL/transaction contents,
   missing-table early return, cache refresh, and `model.forceReload()` after a
   successful restore transaction.
4. **Preserve call-time backup ordering.** In an external-holder deterministic
   sequence, reserve `createBackup`, submit the following ModelWriter-equivalent
   migration, release the holder, and assert the backup MODEL_EXECUTOR body runs
   first. The reservation callback must only post to MODEL_EXECUTOR.
5. **Turn the race test green and prove loader progress.** In the staged
   sequence, assert no legacy blocking probe, a FIFO deferred continuation,
   return from MODEL_EXECUTOR, pre-release `COMPLETED` correlated reload, then
   exactly one post-release hand-off/body execution. Cover both helpers and
   contention-free compatibility.
6. **Expand the inventory with a stop condition.** Run the validator after
   adding `quickstep/src`. For every newly found path, inspect executor and
   lease admission. Fix it in #156 only when it is the same tokenless
   MODEL_EXECUTOR blocking-admission defect. Record all other findings in the
   assessment and create/link a separate Issue before adding their allowlist
   reason; do not silently suppress findings.
7. **Prove the acceptance matrix.** Run focused tests followed by compile,
   inventory, lint, unit, and build checks. Confirm all causal tests rely on
   event ordering rather than timing.
8. **Collect independent device evidence.** On a clean API 36 emulator/device,
   reproduce the fresh default-workspace flow. Record exact head SHA, #155
   state, OrganizerDiag terminal sequence, and redacted logcat/thread
   observations. The expected result is absence of recovery-leg starvation
   timeout; this is not by itself proof that #155's first-leg layout mismatch
   is fixed.
9. **Prepare high-risk PR evidence.** Because #156 carries `risk: layout-data`
   and changes a writer path, use a separate audit session after source changes
   are finalized. Add the required PR assessment with accepted Spec criteria,
   exact CI run link, and 40-character head SHA only after the `final-status`
   gate succeeds.

## Migration and recovery

No schema, rule-format, backup-format, or persisted-state migration is required.

| Condition | Required behavior |
|---|---|
| Helper task meets organizer/restore-family holder at call time | Existing `runOrDefer` reserves a FIFO entry immediately. Its callback posts the atomic attempt to MODEL_EXECUTOR; no helper DB access or blocking wait occurs before the holder releases. |
| Holder appears after helper scheduling or call-time reservation | Execution-time atomic admission registers or preserves a MODEL_EXECUTOR continuation in the existing in-memory FIFO; no helper DB access or blocking wait occurs. |
| Helper task meets no holder or its own same-thread MODEL_WRITER holder | Atomic admission returns a lease-owning wrapper; inner `newTransaction()` reenters as supported and the wrapper releases its outer view in `finally`. |
| Deferred continuation runs while another holder has appeared | It reattempts atomic admission and re-defers; it does not execute DB work or block the model thread. |
| Deferred helper transaction fails | Existing `SQLiteTransaction` rollback/close behavior applies; wrapper `finally` releases ownership and coordinator entry isolation prevents later FIFO entries from being stranded. |
| Process dies before FIFO drain | The transient task is lost as it is today for any in-memory executor task; no partial helper transaction or new recovery state is created. Normal launcher/model startup behavior remains authoritative. |
| Rollback of this code change | Revert coordinator admission, helper routing, focused test hooks/tests, inventory, and assessment as one logical change. No data migration or user cleanup is needed. |
| Downgrade or ZIP/Android backup restore | Unaffected because no schema, backup payload, preference, or recovery-point format changes. |

The implementation must not weaken `DESIGN.md` §5 invariants or Spec #13's
truthful recovery behavior. Failure to reload/verify after a committed organizer
write remains a recovery condition; this change prevents unrelated helper work
from preventing that reload from running.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-156-01 | Concrete `createBackup` and `restoreBackup` race tests show the exclusive atomic outcomes: grant/reentry with an owned outer lease, or FIFO continuation. The legacy blocking probe is never emitted post-fix. | Focused API 36 instrumentation class `com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest`. |
| AC-156-02 | Stage: helper scheduled with no holder → organizer acquired → executor admission released. Assert busy FIFO registration, immediate MODEL_EXECUTOR return, and exact-token adapter `COMPLETED` before outer lease release. | Same focused class plus existing `SyncPageSelectionBarrier` behavior. |
| AC-156-03 | Count continuation resubmissions and DB-body entries; assert exactly-once after grant, re-defer on a new holder, and no FIFO wedge after missing table/throwing work. | `HotseatRestoreAdmissionTest` plus existing `LayoutWriteCoordinatorTest` FIFO-isolation cases. |
| AC-156-04 | On an uncontended fixture DB, assert backup table creation/cache refresh and restore/reload request behavior match pre-change expectations. | `HotseatRestoreAdmissionTest` on clean fixture DB. |
| AC-156-05 | Scanner includes quickstep, recognizes both helper transaction expressions, and rejects unallowlisted writer paths. Documented inspection decisions identify same-defect versus split findings. Atomic gate structure is not delegated to this scanner. | `python3 tools/repo-contract/validate_writer_inventory.py`; focused checker self-test if added; updated assessment. |
| AC-156-06 | Clean fresh-install manual organization flow records recovery reload progress without a `MODEL_RELOAD_FAILED` timeout caused by Hotseat starvation; evidence records #155 state separately. | API 36/36.1 emulator or device; supported Settings diagnostic export, redacted logcat, and issue/PR evidence. |
| AC-156-07 | With an organizer/restore-family holder, `createBackup` reserves before a following migration ModelWriter submission; after holder release, its DB body executes first on MODEL_EXECUTOR and never on the release thread. | Focused `HotseatRestoreAdmissionTest` deterministic executor case. |
| Style/build/regression | Required repository checks and source-changing shared-writer lane are green on the PR head. | `git submodule update --init --recursive`; `./gradlew spotlessCheck`; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks`; `./gradlew assembleLawnWithQuickstepGithubDebug`; `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac`; focused `connectedLawnWithQuickstepGithubDebugAndroidTest`. |
| High-risk independence | A different agent/session audits final code against accepted AC-156 criteria and links a successful PR `CI / final-status` run. | `docs/assessment/pr-<PR番号>-<slug>.md` plus GitHub Actions `final-status` and `high-risk-evidence` gate. |

The focused connected run must include the final Hotseat admission class and
retain shared-writer coordinator/reload coverage:

```text
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.OrganizerReloadSupersessionTest
```

## Documentation updates

- [x] Update this `spec.md` status/history when reviewed and implemented; AC-156-07のrebased-head検証結果を記録済み。Merge状態はPR merge後に記録する。
- [x] Update `docs/assessment/issue-60-executor-writer-admission-audit.md` inventory table/count, atomic-admission reason, and any separately tracked non-identical finding.
- [ ] Keep `CONTEXT.md` unchanged: no domain-language change.
- [ ] Keep `DESIGN.md` unchanged unless implementation reveals a conflict with an existing serialization invariant.
- [ ] No ADR is expected: the plan applies the existing single-coordinator decision and adds no alternative architecture or public contract.
- [ ] Keep `AGENTS.md` unchanged unless a clean-checkout/CI verification command is newly proven and becomes mandatory.
- [ ] Link the final PR to `Closes #156` and map every AC-156 item to test/device evidence.

## Static implementation evidence (2026-08-27)

The implementation is deliberately not marked `implemented`: the sandbox lacks the required
Android SDK Platform 36.1, Build Tools 36.1.0, and an API 36 emulator/device. The following
SDK-independent checks passed on the implementation worktree before handoff:

| Check | Result |
|---|---|
| `git diff --check` | Passed; no whitespace errors. |
| `python3 tools/repo-contract/validate_writer_inventory.py` | Passed: 19 allowlisted writer files, 1,437 scanned source files, 0 errors, 0 warnings. |
| `python3 tools/repo-contract/validate_repo_contract.py` | Passed. |
| `python3 tools/repo-contract/test_validate_repo_contract.py` | Passed. |
| `python3 tools/repo-contract/test_validate_high_risk_evidence.py` | Passed: 47 tests. |

The connected environment ran the API 36 shared-writer command in this Plan, together with the
required `spotlessCheck`, unit, assemble, and fresh-default-workspace device flows. Following
implementation review, the branch was rebased onto latest `origin/main` and AC-156-07 was
validated on the rebased head. The final commit SHA is recorded in the Issue/PR evidence; a
successful remote CI run remains required before merge but does not block the local
`implemented` state.

## Connected-environment implementation evidence (2026-08-27)

The following verification ran on the local macOS arm64 environment with OpenJDK 21.0.12,
Android SDK Platform 36.1, Build Tools 36.1.0, and the connected
`issue142_api36` API 36 / Android 16 emulator. All commands ran against the
Issue #156 branch; the final source state is committed after these results are recorded.

| Surface | Command / procedure | Result |
|---|---|---|
| Formatting | `./gradlew --no-configuration-cache spotlessCheck` | Passed. `spotlessCheck` was run separately because combining it with Android compile/instrumentation tasks exposes an unrelated Gradle implicit-dependency validation error. |
| Organizer JVM gate | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` | Passed. |
| Full JVM regression | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks` | Passed. |
| Android test compilation | `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` | Passed. |
| Debug APK | `./gradlew assembleLawnWithQuickstepGithubDebug` | Passed. |
| Focused Hotseat suite | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` | Passed: 10 tests on the rebased head. This includes real uncontended `createBackup`, backup-absent `restoreBackup`, backup-present/drop-after-use `restoreBackup`, atomic race, re-defer, exception release, exact-token reload, and actual ModelWriter backup-before-migration ordering. |
| Shared-writer regression | Command at lines 293–294 | Passed: 23 tests on the rebased head. |
| Writer inventory / contracts | `python3 tools/repo-contract/validate_writer_inventory.py`; repository-contract validator and both self-tests | Passed before connected handoff: 19 allowlisted writer files / 1,437 scanned sources / 0 errors / 0 warnings; 47 high-risk self-tests. |

For fresh-workspace evidence, the debug launcher was installed on the isolated emulator,
its package data was cleared, and it was temporarily assigned the HOME role. The initial
review covered 15 targets over two pages; after the explicit apply confirmation, logcat
recorded two call-time `Deferring tokenless runnable` entries and terminal
`APPLY_RECOVERED stage=A7 err=APPLY_FAILURE.VERIFICATION_FAILED`. After more than the
10-second starvation window, `MODEL_RELOAD_FAILED` had **zero** logcat occurrences and
UI stated that the previous layout was restored. The original HOME role
`com.google.android.apps.nexuslauncher` was then restored and the debug package was no
longer present.

[Issue #155](https://github.com/nunu1733/NunuLauncher/issues/155) remained **Open** at
this verification point. The fresh-workspace A7 verification failure is therefore recorded
as the separate #155 layout-overlap outcome; it is not attributed to #156. The positive
#156 criterion is executor progress: the Hotseat task deferred instead of blocking, the
recovery terminated without `MODEL_RELOAD_FAILED`, and the prior layout was restored.

## Residual risks and dependencies

| Item | Treatment |
|---|---|
| #155 QSB-reservation overlap | External dependency for full default-workspace A8 success. Do not block deterministic executor-progress tests on it; annotate its state in device evidence. |
| API 36 timing/concurrency flakiness | Prohibit sleeps/timeouts as causal oracle. Event latches/probes establish order; timeout only fails a hung test. |
| Admission wrapper lease lifetime | Test normal/exception/early-return paths to prove the outer lease is always closed after the inner transaction/reload body and does not wedge deferred work. |
| Source-scan expansion finds additional quickstep writers | Apply the explicit stop condition: same MODEL_EXECUTOR blocking cause is eligible for #156; all other findings are assessed and split before product changes. |
| Data-layout safety | No intended DB data-model change, but writer scheduling changes a high-risk path. Require exact-head CI and independent audit before merge. |

## Execution checklist

- [ ] Empty-gate→organizer-acquired→executor-start reproducer is red on the legacy direct transaction path.
- [x] Re-review accepted `spec.md`; implementation review added AC-156-07, whose evidence is now complete on the rebased head.
- [x] Atomic lease-or-defer operation grants/reenters or FIFO-registers in one coordinator critical section; final API 36 test coverage is green.
- [x] `createBackup` reserves call-time FIFO order before following migration ModelWriter work, while its release callback posts rather than executes DB work; actual ModelWriter DB-rank assertion is green.
- [x] Both helper methods use the one internal atomic admission route; real uncontended helper work is green.
- [x] Race test is green post-fix and exact correlated reload completes before explicit organizer-lease release on the final head.
- [x] Uncontended, missing-table, existing-backup/drop-after-use, exception, re-defer, and exactly-once behaviors pass on the final head.
- [x] quickstep writer-inventory coverage and allowlist entry are green; the expanded scan found no non-identical additional writer.
- [x] Focused and full relevant lint/build/unit/instrumentation checks pass on Android SDK 36.1 / Build Tools 36.1.0 after rebase.
- [x] Fresh-workspace device evidence after rebase separates #156 timeout resolution from #155 layout-overlap behavior: call-time deferral occurred and `MODEL_RELOAD_FAILED` was absent, while A7 `VERIFICATION_FAILED` restored the prior layout.
- [ ] Independent high-risk audit, exact-head CI, and PR evidence are recorded.
