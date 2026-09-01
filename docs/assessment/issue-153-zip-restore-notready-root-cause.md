# Assessment: Issue #153 — ZIP restore NotReady root cause (historical replay ladder)

> Status: investigation complete (AC-1/2/3/4/5 met on this branch; AC-6 fix pending — blocking dependency of closing #153)
> Date: 2026-09-01
> Spec: [specs/153-zip-restore-notready-root-cause/spec.md](../specs/153-zip-restore-notready-root-cause/spec.md) (accepted)
> Environment: emulator `nunu_qpr2_api36_1` (API 36, `sdk_gphone64_arm64`), serial `emulator-5554`, debug builds
> Same-input archive: created once at H0 from the pristine default 4×5 workspace, 75,715 bytes, code-identification SHA-256 prefix `5731f64f4f4bbc94` (stored under `/tmp`, not committed)
> Raw evidence (not committed): `/tmp/issue153/` — per-head attempt logs, exported journals, APKs
> Journals exported via the official Settings export surface (SAF), redacted copies in this record only as closed codes/counts/opaque IDs

## Outcome in one line

The ZIP-restore episode **reproduces on every ladder head including current main** with a named reason — `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED` (observable from head `50ddb86148` on) — and the root cause is identified at the owning seam: **`LawnchairBackup.restore()` deletes the whole `databases/` directory (including the organizer recovery DB) while `no_backup/recovery-inspection/` (published by the pre-restore process's successful startup reconciliation) survives; `RecoveryStartupStorageClassifier` then classifies every later process as `SuspiciousAbsence` → `READ_FAILED`, so `RestartReconciler.reconcileAll` fails at the availability branch before any SQLite open, the `ReadinessGate` stays `FAILED`, and every compose returns `NotReady(ReconciliationFailed)` — permanently.** Removing the stale snapshot directory immediately restores normal operation (verified twice, on H3 and H4).

## Head × outcome table (same archive, same procedure)

Procedure per head (per accepted spec): build head → install → `pm clear` → set HOME → launch → Settings → ⋮ → Restore backup (same archive) → attempts in the restore process and across fresh processes (force-stop/relaunch), until the stop condition (first preview, or ≥40 min and ≥3 fresh processes all input-unavailable).

| Head | Change | Restore completed | Outcome | Stop condition evidence |
|---|---|---|---|---|
| H0 `74c2156767` (episode observation head, PR #148) | — | 14:03:19 | INPUT_UNAVAILABLE on every attempt | **PERSISTENT**: 47 min, 5 process generations (15028/15994/17131/18104+19017), all input-unavailable |
| H1 `2d811b701c` (PR #158, #155 fix) | +QSB cell reservation | 14:54 | INPUT_UNAVAILABLE on every attempt | **PERSISTENT**: 40 min, 7 fresh processes |
| H2 `6fd276b50d` (PR #157, #156 fix) | +hotseat deferral | 15:50 | INPUT_UNAVAILABLE on every attempt | **PERSISTENT**: 40 min, 7 fresh processes |
| H3 `50ddb86148` (PR #184, #172 surface) | +INPUT_NOT_READY journaling | 16:44 | `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED` on every attempt; stale-snapshot removal → immediate `PREVIEWED` | **PERSISTENT** until out-of-band removal (attempt 16:46:04 and 16:56:31 both `RECONCILIATION_FAILED`; removal at ~17:03 → attempt 17:05:14 `RUN_STARTED → CAPTURED → PLANNED → PREVIEWED`) |
| H4 `667e8915f2` (PR #186, #185 / current main) | +QSB interop | 17:12 | Same `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED`; post-restore artifact state verified on-device; stale-snapshot removal → immediate `PREVIEWED` | **PERSISTENT until removal** (attempt 17:13:46 `RECONCILIATION_FAILED`; removal at 17:15 → attempt 17:16:00 `PREVIEWED`) |

Baseline health at H0 before archive creation: `RUN_STARTED → CAPTURED → PLANNED (captured=14, moved=4, preserved=10) → PREVIEWED → USER_CANCELLED` — composer healthy on the same build/workspace.

H0–H2 (pre-#172 heads) carry no reason-code surface by design; their symptom signature (persistent input-unavailable across ≥3 fresh processes and ≥40 min, recovered only by out-of-band state change) matches the H3/H4 mechanism, and the classifier, restore path, and procedure are identical across heads. The attribution of H0–H2 to the same root cause is recorded with that caveat.

## Post-restore artifact state (verified on-device, H4)

```text
databases/organizer_recovery.db          → ABSENT (deleted by restore; never recreated afterwards)
no_backup/recovery-inspection/recovery-inspection.v1 → PRESENT (56 bytes, published pre-restore)
```

At H3 the same state was verified before the first probe; a 0-byte `databases/organizer_recovery.db` observed mid-investigation was an investigator-created sqlite3 probe artifact and was removed; the failure reproduced identically without it (16:56:31 attempt, clean state).

## Root cause chain (code- and evidence-verified)

1. `pm clear` → first launcher process: startup reconciliation runs on a **Pristine** store → opens the recovery DB (creates `databases/organizer_recovery.db` with schema v3) → publishes the empty inspection snapshot `no_backup/recovery-inspection/recovery-inspection.v1` → fence valid → `ReadinessGate.READY`.
2. Restore backup (`LawnchairBackup.restore()`, [LawnchairBackup.kt:87](../../lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)): `getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()` wipes the **entire** `databases/` directory — the organizer recovery DB is collateral damage — while `no_backup/` is untouched. The launcher process is replaced.
3. Every subsequent process: `RecoveryStartupStorageClassifier.classify` sees main DB absent + snapshot inventory non-empty → **`SuspiciousAbsence`** → `RecoveryStore.startupAvailability()` = `READ_FAILED` → `RestartReconciler.reconcileAll` returns `Failed` at the `READ_FAILED` branch **before any SQLite open** — so the DB is never recreated and the state never heals.
4. `LayoutApplicationModule.composeManualFullOrganizationInput` → gate non-READY, `FAILED` → `NotReady(ReconciliationFailed)` → journal `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED` (H3+), bug-report copy, non-write (no planner, no checkpoint, no mutation — verified: only `RUN_STARTED`/`INPUT_NOT_READY` journal rows).
5. Permanent until the stale snapshot directory is removed out-of-band (causal experiment performed twice: H3 17:05:14 and H4 17:16:00, both immediately `PREVIEWED`).

## Revision of the original episode characterization

The original #150/#153 evidence described a transient ~30-minute window that recovered in a later fresh process. On the recorded procedure (cleared state → launcher relaunch → restore) the condition is **persistent, not transient**: the stale snapshot is never cleaned by any in-app path (the fail-closed classifier intentionally refuses to open the store). The later "recovery in a fresh process" observation corresponds to a different artifact state — e.g., a restore performed before any startup reconciliation had published a snapshot (Pristine store at first open), or out-of-band state change — the same way #172's AC-3 revised #171's "one-off" characterization.

## Relationship to Issue #150

**Independent.** #150 owns the A6→A7 request-scoped reload completion barrier at the `LayoutWriterPort`/`OrganizerModelReloadAdapter` seam (post-apply verification). The #153 defect lives in the recovery-store startup availability classifier and the backup-restore artifact handling — a different seam, different lifecycle stage (pre-compose, no apply in flight), and the reproduced failure closes at `INPUT_NOT_READY` before any `CHECKPOINTED`/`APPLY_*` event. Nothing in the #150 completion-barrier path participates.

## Deterministic test at the owning seam (AC-3)

`RecoveryStartupStorageClassifierTest.zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence` (this branch) pins the exact production trigger — store opened + snapshot published (pre-restore), then the restore-equivalent artifact transition (`databases/` deleted recursively, `no_backup/` retained) — to the currently observed fail-closed outcome (`SuspiciousAbsence`). It is a characterization pin of the defect; the healing decision (clean the stale snapshot in the restore path, or make the classifier prune a stale snapshot whose DB is absent, or other) belongs to the focused fix issue, whose red/green flip of this pin (and equivalent instrumentation-level coverage) becomes the fix's regression evidence.

## Acceptance-criteria mapping (spec #153)

- **AC-1 — headごとのattempt分類と理由コード取得: met.** All attempts on all heads classified from journal/logcat; H3/H4 attempts yield `INPUT_NOT_READY / RECONCILIATION_FAILED` via the official export (journals `issue153_h3.jsonl`, `issue153_h4.jsonl` in `/tmp`, redacted rows quoted above); H0–H2 recorded symptom presence only (by design).
- **AC-2 — 境界の記録: met.** Attempt/process table above with generations, elapsed, outcomes, stop-condition satisfaction; model readiness recorded as observable facts only (no 30 s timeout line observed; gate state inferred from `RECONCILIATION_FAILED` vs other codes; `IDLE`/`RECONCILING` not distinguished).
- **AC-3 — root causeとdeterministic test: met.** Causal change localized: none of #155/#156/#172/#185 removed it — the defect persists on current main. Root cause identified at the owning seam (restore artifact handling + startup classifier interaction); deterministic trigger pin added and passing.
- **AC-4 — non-writeとredactionの不変: met on this branch.** Unit suite (386 tasks) green with no changes to existing tests, `spotlessCheck` green, debug build green. All evidence surfaces quote only opaque run IDs, closed codes, counts, timestamps. Hash usage limited to code identification (archive SHA-256 prefix, head SHAs). **Fix-side verification remains owned by AC-6.**
- **AC-5 — #150との関係: met.** Independent, with seam-level justification above; recorded in the Issue #153 comment.
- **AC-6 — fix受入: pending (blocking).** The focused fix issue owns the fix; this issue does not close until the fix is implemented and its non-write/redaction verification is confirmed.

## Investigator-made state changes (disclosed)

- A 0-byte `databases/organizer_recovery.db` was created by a read-only sqlite3 probe at 16:49–16:50 on H3 (sqlite3 CLI creates the file on open). It was removed at 16:55; the failure reproduced identically without it before and after.
- The stale `no_backup/recovery-inspection/` directory was removed twice as the causal experiment (H3 ~17:03, H4 17:15) — this is also the only observed recovery path short of wiping app data.

## Commands executed

- Per-head: `./gradlew assembleLawnWithQuickstepGithubDebug` at each head (all BUILD SUCCESSFUL), streamed install, `pm clear`, restore via Settings UI, attempts via scripted UI navigation with `uiautomator` dump, `adb logcat -s OrganizerDiag:V`, journal export via Settings → Organizer diagnostics → Export (SAF).
- Branch verification: `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest` (full, `--rerun-tasks`), `./gradlew assembleLawnWithQuickstepGithubDebug` — all BUILD SUCCESSFUL.
