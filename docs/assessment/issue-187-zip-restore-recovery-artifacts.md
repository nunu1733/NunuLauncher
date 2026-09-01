# Assessment: Issue #187 — ZIP backup restore resets organizer recovery artifacts consistently (fix verification)

> Status: implemented (AC-1/2/3/5/6/7 verified on this branch; AC-4 emulator evidence below; AC-6 high-risk merge gate to be completed on the main-based implementation PR)
> Date: 2026-09-01
> Spec: [specs/187-zip-restore-recovery-artifacts/spec.md](../../specs/187-zip-restore-recovery-artifacts/spec.md) (accepted) · ADR: [0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md) (accepted; audit re-verified here)
> Build: `15.Dev.(65ce6d6)` debug, branch `docs/issue-187-spec-plan` head `8884339eff`
> Environment: emulator `nunu_qpr2_api36_1` (API 36), serial `emulator-5554`
> Raw evidence (not committed): `/tmp/issue153/` (attempt log), full logcat

## Outcome in one line

The #153 reproduction procedure now **resolves**: after a ZIP backup restore the organizer recovery artifacts are consistently absent (Pristine), the next process's startup reconciliation recreates both the recovery DB and the inspection snapshot, the gate reaches READY, and the manual organizer reaches `PREVIEWED` immediately — no `INPUT_NOT_READY` is journaled. The classifier, reconciler, gate, and diagnostics contract are unchanged.

## AC-4 — reproduction-procedure resolution (emulator evidence)

Procedure (same as the #153 accepted assessment): `pm clear` → set HOME → launch (pre-restore startup reconciliation on a Pristine store creates DB + publishes snapshot) → Settings → ⋮ → Restore backup (the same-input archive) → organizer attempt in a fresh process.

| Step | Observation |
|---|---|
| Pre-restore startup | reconciliation succeeded (gate READY; DB `databases/organizer_recovery.db` + snapshot `no_backup/recovery-inspection/recovery-inspection.v1` present) |
| Restore (`RestoreDbTask` traces: 25 lines) | completed; **post-restore artifact state: both absent** (cleanup removed the snapshot inside the module-mutex section before the databases wipe) |
| Fresh-process attempt (pid 27792, 20:23:23) | `RUN_STARTED → CAPTURED → PLANNED (captured=14, moved=4, preserved=10) → PREVIEWED` |
| Journal | no `INPUT_NOT_READY` rows (`logcat -s OrganizerDiag:V` over the whole window shows none) |
| Post-reconcile artifacts | DB recreated (36,864 bytes, 20:22) + snapshot republished (56 bytes) — consistent pair |

Compare with the #153 baseline on the same procedure: `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED` on every attempt, permanently.

## Publisher call-site audit (serialization-contract prerequisite, ADR-0011 Decision 2)

All inspection-snapshot publishers must run under the module operation mutex (`RunMutex`). Verified by code inspection at head `8884339eff`:

- `rebuildInspectionSnapshot` is reached only through `RecoveryStore.ReconciliationSession.rebuildInspectionSnapshot()` (called by `RestartReconciler.reconcileAll`) and the store's mutation paths (`checkpoint`, advance, prune, recovery-apply) — all executed inside `LayoutApplicationModule` operations that hold the module mutex via `tryAcquire`.
- Module mutex holders never block on `LayoutWriteCoordinator`: the writer seam uses nonblocking `tryAcquire(ORGANIZER)` (`LauncherLayoutAdapter.tryAcquireLease`), apply A5 uses exact-capability reentry `newTransaction(organizerToken)` → `tryAcquireOrganizerLease` (nonblocking), locks use `LockStateDbAdapter` → `tryAcquire(ORGANIZER)`; `capture()` reads `controller.db` directly without acquiring the coordinator.
- `acquireBlockingQuietly` call sites (grep over `src/` + `lawnchair/src/`): `LawnchairBackup.restore` (BACKUP_RESTORE, acquired before the module mutex), `NovaBackupConverter` (BACKUP_RESTORE, same), `RestoreDbTask.performRestore` (RESTORE, under restore-family reentry), `GridSizeMigrationUtil` and `ModelDbController` (GRID_MIGRATION), `getCoordinatorLease` MODEL_WRITER fallback (launcher-side mutations; organizer operations use `newTransaction(organizerToken)` and never reach it). None runs while holding the module mutex → the reverse order (module mutex → coordinator blocking wait) never forms.
- `quiesceForRestore` (`LauncherModel.java:333`) touches only the model lock — no interaction with the module mutex inside the section.

This re-verification supersedes the initial audit recorded at spec time (review round 3) and is to be repeated in the main-based implementation PR's independent audit.

## AC-1..7 mapping

- **AC-1 — decision recorded:** [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md) (accepted): verified colocated cleanup + hard stop, serialization contract, recovery epoch boundary (deliberate invalidation of pre-restore recovery points), rejected alternatives (classifier healing / recovery-DB preservation / hybrid / time-window-only), intermediate-state table.
- **AC-2 — verified cleanup + failure injection + order + interleaving/lock-order tests:** `RecoveryStartupArtifactsTest` (cleanup → both-absent Pristine; failed delete and leftover entries → false for hard stop; idempotency; defined-safe `Existing` classification) and `RunMutexRestoreSuspensionTest` (contending `reconcileAtStart` fails fast without touching the gate; exclusive acquisition drains an in-flight operation finitely; mutex returns to normal after release). The #153 trigger pin `RecoveryStartupStorageClassifierTest.zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence` remains unchanged and green (classifier characterization intact).
- **AC-3 — classifier unchanged:** no diff in `RecoveryStartupStorageClassifier` / `RestartReconciler` / `ReadinessGate`; the existing classifier test suite passes unmodified.
- **AC-4 — reproduction resolution:** table above (emulator `nunu_qpr2_api36_1`, build `65ce6d6`).
- **AC-5 — defined-safe intermediate state:** unit tests cover the `Existing` classification with snapshot absent; the full store publish path (`rebuildInspectionSnapshot` after reconcile) is exercised end-to-end by the AC-4 emulator run (snapshot republished, gate READY).
- **AC-6 — verification:** `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest` (full suite), `./gradlew assembleLawnWithQuickstepGithubDebug` — all BUILD SUCCESSFUL. The **high-risk merge gate** (exact-head `CI / final-status`, independent audit `docs/assessment/pr-<n>-<slug>.md`, `high-risk-gate` workflow) will be completed on the main-based implementation PR per the plan's branch-dependency section.
- **AC-7 — #153 AC-6 verification (non-write/redaction/schema):** the implementation diff touches `Ports.kt` (`RunMutex` extension), `LayoutApplicationModule.kt` (new wrapper), `RecoveryStartupArtifacts.kt` (new file), `LawnchairBackup.kt` — no diagnostics model, no `RunEvent`/closed-code change; existing non-write fixtures (`OrganizationInputComposerTest`, `ManualOrganizationRunTest` INPUT_NOT_READY) and negative-redaction fixtures pass unmodified in the full suite; the AC-4 run journaled only ordinary phases (`RUN_STARTED/CAPTURED/PLANNED/PREVIEWED`) with no redaction-relevant new output. `NotReady` remains non-write (no planner/checkpoint/mutation in any failure path exercised).

## Commands executed

- `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest` (full), `./gradlew assembleLawnWithQuickstepGithubDebug` — BUILD SUCCESSFUL.
- Emulator: streamed install of `65ce6d6`, `pm clear`, restore via Settings UI, scripted organizer attempt, `adb logcat -s OrganizerDiag:V`, `run-as` artifact listings (names/sizes only).
