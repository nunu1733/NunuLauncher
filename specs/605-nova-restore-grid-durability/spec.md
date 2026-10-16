---
issue: "#605"
status: draft
requirements: []
updated: 2026-10-17
---

# One Nova restore keeps the converted grid across the restore's self-restart

## Problem

On a device whose current grid differs from the backup grid, one Nova backup
restore succeeds in-process but silently reverts after the restore's designed
self-restart ([Issue #605](https://github.com/nunu1733/NunuLauncher/issues/605),
reproduced 2026-10-17 on `main@38b4818917`): the converter stages and binds the
converted DB (`launcher_6_5_5.db`), the correlated reload completes
capture-valid, then the restarted process recomputes the grid from the
lawnchair typed prefs (`DeviceProfileOverrides.DBGridInfo(prefs)` — log
`Grid recompute from prefs: rows=5 columns=4 hotseat=4`), opens the old
`launcher_5_4_4.db`, and `LawnchairApp.cleanUpDatabases()` deletes the
restored DB as unmatched (deletion observed in logcat). The user sees the
pre-restore default workspace; a matched-grid control restore on the same
build succeeds, isolating the cause to the complete-grid path's persisted
grid state.

Regression origin (pinned in the issue by read-only `git show`): commit
`b18a98af48ab10f4c5339edff1f2728fb505b65d` (S3a of #532, 2026-10-05) removed
`writeGridToLawnchairPrefs(info, smartspaceEnabled)` from the complete-grid
branch of `NovaBackupConverter.convertAndRestore` and moved the
`DeviceGridState.writeToPrefs(context, commit = true)` durability barrier from
last to first. The later #532 E2 re-port restored the `applyGridInfo` live
binding but not the lawnchair typed-prefs write, so the restart-time grid
recompute has no committed values to read. The authoritative contract is
[spec #168 Behavior 1](../168-nova-restore-authoritative/spec.md): the
converter keeps writing the backup grid to **all three pref stores**
(`DeviceGridState.writeToPrefs` x2, `writeGridToLawnchairPrefs`), and the
synchronous `commit()` is issued **last** as the durability barrier that makes
the values survive the self-restart despite `batchEdit`'s async `apply()`.

## Outcome

After one Nova restore with complete grid info, the converted grid survives
the restore's self-restart and any subsequent cold start: the lawnchair typed
prefs, the `DeviceGridState`/LauncherPrefs grid metadata, the live IDP
binding, and the active DB selection all carry the converted values, and DB
cleanup never deletes the restored DB in the restore window. A second restore
is never required.

## Scope

- The complete-grid branch of the `NovaBackupConverter.convertAndRestore`
  grid-application step: restore the `writeGridToLawnchairPrefs` write and the
  spec #168 ordering (`writeToPrefs(context)` → `writeGridToLawnchairPrefs` →
  `applyConvertedGrid` → `writeToPrefs(context, commit = true)` last).
- FileLog trace markers on the converter-owned grid-application steps so the
  ordering contract is deterministically observable (single-handler FIFO, as
  pinned by spec #168 S5's trace requirement).
- Regression evidence on the existing converter-boundary instrumentation
  surface: typed-prefs value persistence, persisted XML at return, and the
  trace-order oracle.

## Non-goals

- Durability of the partial-grid `else` branch (`writeGridToLawnchairPrefs`
  without a barrier). That behavior predates the regression and is unchanged
  by it; a barrier there is a separate decision if ever needed.
- The #168 restore-family lease, correlated reload, completion barrier, and
  `cleanUpDatabases` guard semantics — unchanged.
- Content-level import gaps (not-installed packages, widgets, shortcuts) — as
  in #168.
- New test lanes, CI routing changes, or a test/CI redesign.
- Broad `cleanUpDatabases` policy changes for the old (pre-restore) DB: after
  a successful authoritative restore the previous grid DB's removal is the
  existing #168 semantics; this fix only verifies and records the behavior.

## Domain language

None — implementation-level regression fix under the existing contract.

## Prior art

省略（理由: accepted spec #168 の回帰復旧であり、新規設計・新規API採用・状態遷移の新規扱いを行わない。復元する順序契約は spec #168 Behavior 1 と回帰前commit `b18a98af48^` のコードに既存。順序oracleの実現手段（単一handler thread FIFOのFileLog trace）は既存 `com.android.launcher3.logging.FileLog` の仕様利用である）。

## Behavior scenarios

### Scenario: one restore, grid mismatch, extra-row ON

Given a fresh device on the default grid (e.g. 5x4x4) and a complete-grid Nova
backup whose converted grid is 6x5x5
When one restore runs through the converter's normal entry and the restore's
self-restart completes
Then the restarted process reads rows=6/columns=5/hotseat=5 from the lawnchair
typed prefs, binds and opens `launcher_6_5_5.db`, and shows the imported
workspace
And logcat contains no `Deleting unmatched launcher database file:
launcher_6_5_5.db` line, and a further cold start keeps the same grid, DB, and
workspace.

### Scenario: extra-row OFF

Given smartspace disabled and a complete-grid backup
When one restore completes
Then the committed rows equal the Nova rows (no +1 compensation) across the
lawnchair typed prefs, the `DeviceGridState` metadata, the live IDP binding,
and the active DB selection.

### Scenario: persisted values at return

Given a complete-grid restore
When `convertAndRestore` returns
Then the lawnchair typed pref API reads the converted values **and** the
persisted `com.android.launcher3.prefs.xml` file on disk contains them.

### Scenario: ordering contract is deterministically observable

Given a complete-grid restore
When `convertAndRestore` runs its grid-application step
Then the FileLog trace shows, in this order within the restore window: the
`DeviceGridState` metadata write, the committed lawnchair grid values, the
live `applyGridInfo` binding, and the synchronous commit durability barrier
— the FileLog handler is a single FIFO thread, so file order equals call
order
And an oracle asserting this order fails when the write is missing (the
regression head) and when the barrier is moved before the lawnchair write
(mutation sensitivity).

### Scenario: grid match unchanged

Given the current grid equals the converted grid
When one restore completes
Then every persisted store and the live binding converge to the same DB, as
in spec #168 S2.

### Scenario: partial-grid fallback unchanged

Given a Nova backup with partial grid info (a null column/row/hotseat value)
When one restore completes
Then the `else` branch behavior (typed-prefs write only, null-safe per field)
is unchanged from the pre-fix code.

### Scenario: custom-layout start state — failed restore preserves the layout

Given a device with an existing custom layout (non-default placements) and a
backup file that cannot be restored (invalid or interrupted before commit)
When the restore attempt fails or is cancelled before the converted DB is
bound
Then the existing layout, grid prefs, and active DB are unchanged and the
workspace remains usable (no partial import, no deleted active DB).

### Scenario: custom-layout start state — successful restore is explainable

Given a device with an existing custom layout and a restorable complete-grid
backup
When one restore succeeds
Then the outcome is the imported workspace on the converted grid (the
authoritative restore semantics of #168: every pre-restore placement is
replaced by the import), the outcome is recorded as a runtime observation,
and recovery to the pre-restore state is possible only through the user's own
backup means — consistent with the existing restore contract, which this fix
does not change.

## Data and state

- Reads: the Nova backup zip (unchanged).
- Writes: the same three pref stores as spec #168 Behavior 1; no new keys, no
  format change; only the presence and ordering of the lawnchair typed-prefs
  write change on the complete-grid path.
- DB lifecycle: the restore-family lease, `restored.db` rename, and
  `cleanUpDatabases` guards are unchanged. Rollback = revert the PR.

## Permissions, privacy, and security

None — no new permissions, transports, or data surfaces. Test fixtures remain
the existing privacy-equivalent synthetic backups (test component and
synthetic identity strings only).

## Accessibility and localization

None — no user-visible copy or interaction changes.

## Acceptance criteria

- [ ] AC-1: On the complete-grid path the converter commits the converted grid
      to all three pref stores, and after `convertAndRestore` returns the
      lawnchair typed pref API reads the converted values and the persisted
      prefs XML file on disk contains them, for both extra-row ON and OFF.
- [ ] AC-2: The spec #168 ordering contract (metadata write → lawnchair typed
      setters under `batchEdit` → live `applyGridInfo` binding → synchronous
      `commit()` barrier last) is pinned by a deterministic order oracle in
      the existing converter-boundary surface, and the oracle is demonstrated
      to detect both regressions: the missing lawnchair write (red on the
      regression head) and a barrier-before-typed-setters mutation (red).
- [ ] AC-3: Grid mismatch and grid match: after the restore lifecycle the
      active DB is the converted DB, the converted DB is not deleted by
      cleanup in the restore window, and the live IDP binding matches the
      committed stores (existing #168 oracles keep passing).
- [ ] AC-4: Custom-layout start state: a failed or cancelled restore preserves
      the existing layout and active DB (recoverable, no partial import), and
      a successful restore's outcome is the explainable authoritative import;
      both are recorded as runtime observations following the original report
      procedure on the API 37 environment (the report's own environment). The
      physical-device reading of the issue's owner-confirmation item is
      tracked as an explicit owner decision on the issue (see Open questions).

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `NovaConverterBoundaryScenarioBase.assertCommittedGrid()` extended: lawnchair typed-prefs assertions plus persisted-XML file readback; exercised by `NovaConverterBoundaryTest` (ON) and `NovaConverterBoundarySmartspaceOffTest` (OFF) via `tools/ci/run-restore-capture-instrumentation.sh` |
| AC-2 | Same classes: FileLog trace-order assertion over the restore window (offset-scoped, `FileLog.flushAll` before reads); red on the regression head (missing write / missing markers) plus a recorded barrier-first mutation run showing the order assertion fails |
| AC-3 | The same restore-capture lane (control/widget/unknown-provider/no-callbacks classes, `NovaRestoreGridApplicationTest`, cross-process pair) plus a recorded runtime emulator reproduction (converted DB active after restart; no unmatched-deletion log) |
| AC-4 | Recorded runtime emulator observation: custom-layout start state → failed/cancelled restore keeps the existing layout; successful restore produces the import; both per the original report procedure |

## Open questions

- The issue's acceptance text says 「API 37実機の元報告手順でowner確認する」.
  The report's own environment is an API 37 emulator (Pixel 9a AVD), and no
  physical device is attached to the fix session. AC-4 is executed on the API
  37 emulator with the original report procedure; whether the owner requires
  an additional physical-device pass is an explicit owner decision requested
  on the issue — it is not silently dropped. If required and no device is
  reachable, it stays a pending item in the handoff packet (#168 precedent).
- None otherwise blocking.

## Change history

- 2026-10-17: Draft created for #605 (Worker: Zcode GLM-5.3-flash session).
- 2026-10-17: Round-1 review (#606 comment 6221071418) response: split AC-1
  into value persistence (AC-1) and the deterministic ordering oracle (AC-2,
  FileLog trace order + mutation sensitivity); added AC-4 for the
  custom-layout start state and made the 実機 owner-confirmation reading an
  explicit owner decision instead of a silent pending.
