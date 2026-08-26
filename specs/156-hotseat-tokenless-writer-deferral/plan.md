# Implementation Plan: Hotseat tokenless writer deferral

> Issue: #156
> Spec: [spec.md](./spec.md)
> Status: draft
> Baseline examined: `7ba2194ce711ce8e9a9c7abe5957919d566e26dd` (`main`, 2026-08-27)

## Current evidence

- `HotseatPredictionController.setPredictedItems()` invokes
  `HotseatRestoreHelper.restoreBackup()` when its prediction batch is empty.
  Both `createBackup()` and `restoreBackup()` currently submit a runnable
  directly to `MODEL_EXECUTOR`; that runnable opens tokenless
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
- The exact correlated organizer LoaderTask uses the existing organizer
  capability path; it is intentionally distinct from unrelated tokenless work.
  The `SyncPageSelectionBarrier` test pattern proves completion behavior
  without relying on wall-clock scheduling.
- The Issue #60 writer inventory scanner currently examines `src` and
  `lawnchair/src`, but not `quickstep/src`. A focused source audit finds the
  helper's `restoreBackup` transaction in quickstep; the scanner's current
  receiver-name pattern also misses `createBackup`'s local `dbController`
  receiver. Therefore the current green inventory result is incomplete for
  this issue's direct path.
- Device thread-dump/journal evidence in #156 identifies this starvation as a
  distinct third layer after #150's completion-order correction. #155 owns the
  independent QSB-reservation layout-overlap cause and remains outside this
  change.

| Evidence source | Relevant established fact |
|---|---|
| [Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156) | The helper blocks `launcher-loader` on the organizer lease and pushes recovery LoaderTask behind it until the 10-second adapter timeout. |
| [Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150) | The diagnosis explicitly split the executor-starvation cause into #156 and the layout-overlap cause into #155. |
| [Spec #60](../60-executor-writer-admission-audit/spec.md) | Existing contract: tokenless MODEL_EXECUTOR work must use the established coordinator ownership/deferral model; no parallel public seam. |
| [Spec #13](../13-safe-layout-application/spec.md) | A7/recovery must wait for the specific correlated reload and verify DB/model convergence; reload failure must recover truthfully. |
| [Spec #58](../58-serialize-runtime-restores/spec.md) | Existing precedent: ordinary tokenless model work defers while a restore-family lease is active. |

## Design

### Modules and interfaces

The solution remains local to the upstream-facing Hotseat helper and the
existing coordinator seam. Introduce a **private helper-local dispatch method**
inside `HotseatRestoreHelper`; do not add a public interface or alter
`ModelDbController`'s general blocking behavior.

The local dispatch method accepts the existing DB-work body and invokes
`LayoutWriteCoordinator.runOrDefer` with `OwnerKind.MODEL_WRITER`, token `0L`,
and `exactOrganizerToken=false`. Its runnable must then submit the original
body to `MODEL_EXECUTOR`. This preserves the normal executor affinity in the
uncontended case and after deferred release. In particular, the queued entry
must not execute `newTransaction()` inline on the organizer lease-releasing
thread.

Under an organizer/restore-family lease, `runOrDefer` appends only this
executor hand-off to the existing FIFO and returns. Therefore the single model
thread is free to execute the exact-token correlated LoaderTask. Once the
outer lease is released, the coordinator drains the FIFO; the hand-off posts
the unchanged Hotseat DB body to `MODEL_EXECUTOR`, where ordinary
`newTransaction()` admission is again safe.

| Seam | Responsibility after this change | Explicitly unchanged |
|---|---|---|
| `HotseatRestoreHelper` | Gate both tokenless DB-work entry points before any transaction; retain DB work body, commits, cache refresh, and normal reload behavior. | Public methods, backup-table format, callers, and outcome surface. |
| `LayoutWriteCoordinator` | Provide existing defer/FIFO/exact-token capability semantics. | Owner kinds, locks, release behavior, and public APIs. |
| `ModelDbController` | Acquire an ordinary writer lease only when the deferred task reaches MODEL_EXECUTOR after relevant outer lease release. | Its tokenless blocking fallback and organizer/restore reentry rules. |
| `OrganizerModelReloadAdapter` / `LauncherModel` | Continue to run the exact correlated reload under the organizer capability. | Completion protocol, timeout policy, and result types. |
| writer-inventory validator | Audit quickstep source and recognize both receiver forms used by the helper. | Existing heuristic categories and CI entry point. |

### Data flow

```text
empty prediction batch
  -> HotseatRestoreHelper.restoreBackup()
  -> coordinator.runOrDefer(MODEL_WRITER, 0, false,
       () -> MODEL_EXECUTOR.execute(originalDbWork))

ORGANIZER / restore-family lease active:
  -> append executor hand-off to existing FIFO; return immediately
  -> exact-token correlated LoaderTask continues on MODEL_EXECUTOR
  -> correlated reload completes
  -> outer lease closes
  -> FIFO drains; originalDbWork is posted to MODEL_EXECUTOR once

no relevant outer lease:
  -> executor hand-off happens immediately
  -> originalDbWork executes with existing transaction/commit/reload semantics
```

The only new transient state is an entry in the coordinator's existing
in-memory deferred FIFO. No Launcher row, schema, recovery point, backup file,
process-restart behavior, or user-visible state is added or reinterpreted.

### Alternatives rejected

| Alternative | Reason for rejection |
|---|---|
| Add a second lock around Hotseat backup/restore. | Violates Issue #156 and Spec #60: `LayoutWriteCoordinator` remains the only serialization seam. It also risks divergent lock ordering. |
| Change every tokenless `ModelDbController.newTransaction()` to fail fast or defer implicitly. | Broadly changes baseline callers and hides admission ownership from call sites. The issue is the helper's self-posted MODEL_EXECUTOR path, so the narrow caller-side gate is safer. |
| Pass the organizer token into Hotseat helper and grant cross-thread reentry. | The helper is unrelated tokenless work. Granting it exact-token capability would defeat the scoped-correlated-loader boundary and permit writes during organizer verification. |
| Run the deferred DB body inline during `Lease.close()`. | Changes thread affinity and can create blocking work on the organizer release path. Deferring the executor hand-off preserves existing Hotseat execution semantics. |
| Increase `OrganizerModelReloadAdapter.TIMEOUT_MILLIS` or use sleeps/retries. | Masks starvation rather than removing it, slows failure reporting, and fails to provide a deterministic regression oracle. |
| Fold #155's QSB-overlap repair into this change. | The issues have distinct causal paths, acceptance criteria, and high-risk rollback surfaces. Mixing them weakens causality and reviewability. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | Route `createBackup` and `restoreBackup` through one private coordinator-gated executor dispatch helper before either opens `newTransaction()`. Keep each existing DB-work body and its post-commit behavior unchanged. | This is the direct tokenless self-posted MODEL_EXECUTOR path identified by #156. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/HotseatRestoreAdmissionTest.java` (new, preferred) | Add an API 36 instrumentation test that uses the concrete Hotseat helper, a held organizer lease, and the existing correlated-reload barrier/callback seam. Assert deferral and pre-release correlated completion without sleeps. Add normal/deferred completion coverage for both helper methods. | The bug occurs only when the actual helper, the single model executor, and the correlated loader queue interact. A coordinator-only test is insufficient. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerReloadSupersessionTest.java` (reuse only) | Reuse `SyncPageSelectionBarrier` behavior or extract only the minimum package-private test utility if duplication is unavoidable; do not create a second timing-based reload seam. | The current barrier already establishes deterministic pre-completion causality. |
| `tools/repo-contract/validate_writer_inventory.py` | Add `quickstep/src` to `source_dirs`; recognize the bounded `dbController` receiver form in the existing ModelDbController mutation pattern; add `HotseatRestoreHelper.java` with a `controller-call` entry whose reason records `MODEL_EXECUTOR + runOrDefer` admission. | Both helper methods must be mechanically audited. The present scanner otherwise reports green while omitting the issue's direct writer path. |
| `docs/assessment/issue-60-executor-writer-admission-audit.md` | Update the writer-inventory count/table and document the Hotseat helper's coordinator-gated admission. Keep #60's implemented status; record #156 as the corrective follow-up rather than revising its historical verdict. | The assessment is the human-readable companion to the executable inventory. |
| `specs/156-hotseat-tokenless-writer-deferral/{spec,plan}.md` | Move status to `accepted` only after review and no blocking question; update to `implemented` only after merged, verified code. Record exact CI/device evidence and #155 state. | These files are the requirements and implementation-decision source of truth for #156. |

## Execution sequence

1. **Establish failing behavior at the real seam.** Add the focused instrumentation scenario before production changes. Acquire an organizer lease, prepare a usable Hotseat backup table, invoke the concrete helper, and use latch/barrier observations to prove that the old direct executor runnable blocks the model pipeline or prevents the correlated request from completing before release. The red test must not use fixed sleeps or the 10-second timeout as its oracle.
2. **Implement the minimal helper-local gate.** Refactor only the outer scheduling shape of `createBackup` and `restoreBackup` into the private dispatch helper. Preserve SQL/transaction contents, early return for a missing table, cache refresh, and `model.forceReload()` placement after commit.
3. **Make the writer inventory complete.** Expand the scan source roots and receiver pattern, register the helper with its exact admission reason, and add checker regression coverage if the repository-contract test harness is extended. Resolve all newly exposed quickstep findings rather than suppressing them.
4. **Prove the acceptance matrix.** Run focused instrumentation cases first, followed by compile, writer-inventory, lint, unit, and build checks. Confirm no new source scanner warning/error and no test relies on timing.
5. **Collect independent device evidence.** On a clean API 36 emulator/device, reproduce the fresh default-workspace flow. Record the exact head SHA, #155 state, OrganizerDiag terminal sequence, and redacted logcat/thread observations. The expected result is absence of recovery-leg starvation timeout; do not interpret this alone as proof that #155's first-leg layout mismatch is fixed.
6. **Prepare high-risk PR evidence.** Because #156 carries `risk: layout-data` and changes a writer path, use a separate audit session after source changes are finalized. Add the required PR assessment with accepted Spec criteria, exact CI run link, and 40-character head SHA only after the `final-status` gate succeeds.

## Migration and recovery

No schema, rule-format, backup-format, or persisted-state migration is required.

| Condition | Required behavior |
|---|---|
| Helper work is queued while an organizer/restore lease is held | Work remains only in the existing in-memory FIFO until outer lease release; no DB mutation occurs before release. |
| Deferred helper transaction fails | Existing `SQLiteTransaction` failure/rollback behavior applies; the coordinator isolates the entry so later deferred work is not stranded. |
| Process dies before FIFO drain | The transient task is lost as it is today for any in-memory executor task; no partial helper transaction or new recovery state is created. Normal launcher/model startup behavior remains authoritative. |
| Rollback of this code change | Revert the helper gate and its test/inventory/assessment updates as one change. No data migration or user cleanup is needed. |
| Downgrade or ZIP/Android backup restore | Unaffected because no schema, backup payload, preference, or recovery-point format changes. |

The implementation must not weaken `DESIGN.md` §5 invariants or Spec #13's
truthful recovery behavior. In particular, failure to reload/verify after a
committed organizer write remains a recovery condition; this change only
prevents unrelated helper work from preventing that reload from running.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-156-01 | Concrete `createBackup` and `restoreBackup` tests hold an organizer lease, observe one deferred executor hand-off before any transaction work, and show no MODEL_EXECUTOR lease wait. Review asserts both methods share the gate. | Focused API 36 instrumentation class `com.android.launcher3.organizer.HotseatRestoreAdmissionTest`. |
| AC-156-02 | After tokenless helper enqueue, run an exact-token `OrganizerModelReloadAdapter` request under the existing callback barrier; assert `COMPLETED` before outer lease release. Assert barrier/latch state, not elapsed time. | Same focused API 36 instrumentation class; reuse current shared-writer lane configuration. |
| AC-156-03 | Assert helper task executes once after release; include missing backup-table and throwing-entry/normal-later-entry cases using existing coordinator test support where appropriate. | `HotseatRestoreAdmissionTest` plus `LayoutWriteCoordinatorTest` focused execution. |
| AC-156-04 | On an uncontended fixture DB, assert backup table creation/cache refresh and restore/reload request behavior match pre-change expectations. | `HotseatRestoreAdmissionTest` on clean fixture DB. |
| AC-156-05 | Scanner includes quickstep, recognizes both helper transaction expressions, and rejects unallowlisted writer paths. The human assessment table matches the executable allowlist. | `python3 tools/repo-contract/validate_writer_inventory.py`; targeted repository-contract self-test if added. |
| AC-156-06 | Clean fresh-install manual organization flow records recovery reload progress without a `MODEL_RELOAD_FAILED` timeout caused by Hotseat starvation; evidence records #155 state separately. | API 36/36.1 emulator or device; supported Settings diagnostic export, redacted logcat, and issue/PR evidence. |
| Style/build/regression | Required repository checks and source-changing shared-writer lane are green on the PR head. | `git submodule update --init --recursive`; `./gradlew spotlessCheck`; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks`; `./gradlew assembleLawnWithQuickstepGithubDebug`; `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac`; focused `connectedLawnWithQuickstepGithubDebugAndroidTest`. |
| High-risk independence | A different agent/session audits final code against accepted AC-156 criteria and links a successful PR `CI / final-status` run. | `docs/assessment/pr-<PR番号>-<slug>.md` plus GitHub Actions `final-status` and `high-risk-evidence` gate. |

For the focused connected run, use the final test class list after the test is
created; it must include the new Hotseat admission class and retain the
shared-writer coordinator/reload coverage:

```text
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.HotseatRestoreAdmissionTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.OrganizerReloadSupersessionTest
```

## Documentation updates

- [ ] Update this `spec.md` status/history when reviewed, implemented, and merged.
- [ ] Update `docs/assessment/issue-60-executor-writer-admission-audit.md` inventory table/count and source-scan evidence.
- [ ] Keep `CONTEXT.md` unchanged: no domain-language change.
- [ ] Keep `DESIGN.md` unchanged unless implementation reveals a conflict with an existing serialization invariant.
- [ ] No ADR is expected: the plan applies the already accepted coordinator decision and introduces no high-cost architectural choice.
- [ ] Keep `AGENTS.md` unchanged unless a clean-checkout/CI verification command is newly proven and becomes mandatory.
- [ ] Link the final PR to `Closes #156` and map every AC-156 item to test/device evidence.

## Residual risks and dependencies

| Item | Treatment |
|---|---|
| #155 QSB-reservation overlap | External dependency for full default-workspace A8 success. Do not block the deterministic executor-progress tests on it; annotate its state in device evidence. |
| API 36 timing/concurrency flakiness | Prohibit sleep/timeouts as causal oracle. Use the existing pre-completion callback barrier and latches; time bounds only protect against a hung test. |
| Source-scan expansion finds additional quickstep writers | Treat every newly exposed path as an audit finding. Add only documented, coordinator-safe entries; do not broaden allowlist without inspecting admission. |
| Data-layout safety | No intended DB data-model change, but writer scheduling changes a high-risk path. Require exact-head CI and independent audit before merge. |

## Execution checklist

- [ ] Current starvation behavior reproduced or its deterministic pre-fix test is red.
- [ ] Review accepts `spec.md`; status becomes `accepted`.
- [ ] Focused helper admission/reload test is red before the production fix and green after it.
- [ ] Both helper methods use the one private coordinator-gated dispatch path.
- [ ] quickstep writer-inventory coverage and allowlist entry are green.
- [ ] Focused and full relevant lint/build/unit/instrumentation checks pass.
- [ ] Fresh-workspace device evidence separates #156 timeout resolution from #155 layout-overlap behavior.
- [ ] Independent high-risk audit, exact-head CI, and PR evidence are recorded.
