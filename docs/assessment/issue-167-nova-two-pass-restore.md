# Assessment: Issue #167 — Nova backup restore requires two passes

Status: `implemented` (investigation complete; focused fix tracked in a separate Bug Issue)

Date: 2026-08-29
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/167
Upstream reference: LawnchairLauncher/lawnchair#6504 (Nova import, forwardported as #6959 / commit `c047f372`)

Raw evidence (not committed, per issue non-goals): `/tmp/nova167/stageA/` (device), `/tmp/nova167/stageB/` (emulator). All identifiers below are hashes/counts/normalized summaries.

## Verdict

The defect is reproduced and the root cause is established at source level. Pass 1 of a Nova backup import stages and sanitizes the converted workspace correctly, but the live in-process grid never converges to the backup grid; the next model load runs `LawnchairApp.cleanUpDatabases()`, which **silently deletes every `databases/launcher*` file that does not match the live grid DB name — including the DB holding the just-sanitized import** — and loads the default workspace. A second identical import succeeds only because prefs/DB state has already converged.

Classification: **upstream defect (grid application in `NovaBackupConverter`) made destructive by fork-only code (`cleanUpDatabases`) and the fork grid-DB naming scheme** — an interaction defect. See "Upstream vs fork classification".

## Environments

| | Stage A (device) | Stage B (emulator) |
|---|---|---|
| Build | `main` @ `090d3efd` (`assembleLawnWithQuickstepGithubDebug`), APK sha256 `daccaaea…d121a1` | same APK, not rebuilt |
| Hardware | Pixel 9a (`56231JEBF08674`), Android 17 / API 37 | AVD `nunu_qpr2_api36_1` (Pixel 6, Google APIs), Android 16 / API 36 |
| Package | `app.lawnchair.debug` (versionCode 1500020300) | same |
| Backup | `2026-08-28_15-49.novabackup`, sha256 `fa7210a9…884f5` (Nova 8.8.8 Prime, backed up on the same device) | same file |
| Default grid | `launcher_5_4_5.db` (5 rows x 4 cols x 5 hotseat) | `launcher_5_4_4.db` (5x4x4) |
| Backup grid | 6 rows x 5 cols x 5 hotseat → converter target `launcher_6_5_5.db` | same |

## Reproduction sequence

1. Fresh install of the debug build, set as default home, default workspace.
2. Lawnchair Settings → ⋮ overflow → "Restore Nova Backup" → pick the `.novabackup` → Restore. App restarts itself.
3. Observe workspace + persisted state (pass 1).
4. Repeat the identical restore (pass 2). Observe again.

No precondition escalation was needed: the defect reproduced on the plain case, twice, byte-for-byte deterministically on the emulator (cycle 1 and cycle 2 identical at the logcat level).

## Pass 1 vs pass 2 (device / emulator)

| State | Baseline | After pass 1 | After pass 2 |
|---|---|---|---|
| Visible workspace | default | **default (import lost)** | imported Nova layout |
| Active DB (device) | `launcher_5_4_5.db`, 17 default rows | `launcher_6_5_5.db` rebuilt fresh-default, 18 rows | `launcher_6_5_5.db`, 125 rows |
| Active DB (emulator) | `launcher_5_4_4.db`, 15 rows | `launcher_6_5_5.db` fresh-default, 16 rows | `launcher_6_5_5.db`, 12 rows (emulator lacks the backup's apps; see "Secondary findings") |
| Expected (from backup) | 187 favorites rows in nova.db; 3 screens; 5x5 subgrid; hotseat 5 | — | reached only on pass 2 |

Key logcat discriminations (emulator cycle 1; device equivalent in `/tmp/nova167/stageA/logcat-pass1.log`):

- Pass 1: `RestoreDbTask` sanitize dump = 128 rows (import WAS staged and sanitized) → `LauncherProvider: new DB already created, skipping migration` → `loading default workspace` (old pid) → `E: migrateGridIfNeeded: target db is same as current: launcher_5_4_5.db` → `System.exit` → new process: `loading default workspace` again (default rows). `Migration failed: retaining launcher database` x3 in pass 1, 0x in pass 2.
- Pass 2: sanitize 128 rows → `migrateGridIfNeeded: no grid migration needed` → no default-workspace load → restart binds the persisted import.
- The deletion itself has **no log line**: `LawnchairApp.cleanUpDatabases()` (`lawnchair/src/app/lawnchair/LawnchairApp.kt:145-155`) deletes `databases/launcher*` files not prefixed by the live `idp.dbFile` via an unlogged `file.delete()` (L152). Deletion is proven by: sanitize committed 128 rows into `launcher_6_5_5.db`; the live process demonstrably ran on `launcher_5_4_5.db`; `ModelDbController.loadDefaultFavoritesIfNecessary` (`ModelDbController.java:1114-1163`) is the only path that deletes `launcher*` files; and the post-restart process re-created `launcher_6_5_5.db` via the EMPTY_DATABASE_CREATED path, impossible had the sanitized file survived.

## Source trace (pass-1 failure sequence, event-ordered)

1. `NovaBackupConverter.convertAndRestore` (`NovaBackupConverter.kt:147-205`) writes the backup grid to three separate stores: `DeviceGridState.writeToPrefs(context, true)` (migration_* keys, commit), `writeToPrefs(context)` (LauncherPrefs canonical keys, async), `writeGridToLawnchairPrefs` (Lawnchair `pref_workspaceColumns=5 / pref_workspaceRows=6 / pref_hotseatColumns=5`, one commit) — then field-assigns `idp.dbFile = "launcher_6_5_5.db"` (L188). None of this re-initializes the live grid deterministically: the only in-process recompute trigger for Lawnchair grid prefs is `hotseatColumns`' `reloadGrid` listener (`PreferenceManager.kt:38,52`); `pref_workspaceRows/Columns` have **no listener** (L53-54).
2. The live IDP therefore stays on the pre-import grid (`launcher_5_4_5.db` device / `launcher_5_4_5` emulator after the hotseat-only change). A temp `ModelDbController` (L196) opens `launcher_6_5_5.db` via the field-assigned `dbFile` and `RestoreDbTask.performRestore` sanitizes the 128-row import into it (`RestoreDbTask.java:205-240`) — the data is correct on disk at this instant.
3. `reloadAfterRestore` → `app.getModel().forceReload()` reloads through the **app's own controller**, which still holds the old-grid helper; `loadDefaultFavoritesIfNecessary` (`LoaderTask.java:481-483` → `ModelDbController.java:1114-1163`) unconditionally runs `cleanUpDatabases()`, deleting `launcher_6_5_5.db` (name not prefixed by the live `launcher_5_4_5`), then loads the default workspace (`AutoInstalls: default_layout_4x5_h5`).
4. The designed self-restart re-creates `launcher_6_5_5.db` from the empty-DB path. Prefs and DB are now converged.
5. Pass 2: converter restages `restored.db`; helper creation renames it onto the now-active `launcher_6_5_5.db` (`LawnchairApp.renameRestoredDb`, `LawnchairApp.kt:121-126`); `no grid migration needed`; the import lands and persists.

Residual detail (open item A, tracked in the fix issue): the pass-1 recompute read hotseat=5 (fresh) with rows/cols=5/4 (stale defaults), although (a) the converter's grid parse succeeds for this backup (`nova.xml` `desktop_grid = "5x5 subgrid"` matches `novaGridRegex`) and (b) all three Lawnchair grid pref writes land (emulator post-convergence prefs: `pref_workspaceRows=6`, `pref_workspaceColumns=5`, `pref_hotseatColumns=5`). Static analysis says a recompute running after the synchronous `commit()` should read 6/5/5; the runtime observed 5/4/5. The exact recompute/cache-invalidation interleaving must be pinned by the trace specified in the fix issue. This does not change the failure sequence above: the fix must make grid application authoritative in step 1 regardless of which recompute path wins.

## Upstream vs fork classification

- The grid-application block (`writeToPrefs` x2, `dbFile` field assignment, `writeGridToLawnchairPrefs`) exists **verbatim upstream** (commit `c047f372`, forwardport of #6504; fork commit `3fe30650c2` only re-indented it; `writeGridToLawnchairPrefs` is from upstream `505dbc40e6`). The unreliable in-process grid convergence is an **upstream defect unchanged in the fork**.
- `LawnchairApp.cleanUpDatabases()` is **fork code** and turns the divergence into silent data loss. Upstream's `LauncherFiles.GRID_DB_FILES` cleanup list does not even know the fork's `launcher_{rows}_{cols}_{hotseat}.db` names, so only the fork path deletes the staged import.
- `RestoreDbTask.restoreIfNeeded` / `reinitializeAfterRestore` never run in the Nova path (converter never calls `setPending`), so no post-restore grid reinitialization/cleanup happens either.

=> **Interaction defect requiring a fork-specific fix** (make one restore authoritative), even though the divergent grid application originates upstream.

## Secondary findings (out of scope here, recorded for follow-up)

1. `E WorkspaceItemProcessor: Desktop items loading interrupted — IllegalStateException: Cache accessed on wrong thread` from `IconCache.getTitlesAndIconsInBulk` during reload after both pass-2 restores (reported via `LayoutWriteCoordinator: Deferred callback threw` on the emulator). Same call path, same pass, both environments. Self-recovered by the designed restart, but it is a real threading defect on the restore-reload path.
2. Content-level import gaps independent of the two-pass defect: the converter skips all 58 Nova drawer-group rows; items whose packages are not installed are dropped at load; the backup's widget is dropped when the app is missing (`NameNotFoundException`). These are converter/loader semantics, not the state-transition bug.
3. The Organizer-after-external-restore recovery/checkpoint investigation (separate issue) remains blocked until one restore is deterministic: this assessment defines the required stable post-fix state as "one Nova restore → converted layout active in both the visible workspace and the active grid DB after the normal restart, with no default-workspace load".

## Focused fix issue

Link: https://github.com/nunu1733/NunuLauncher/issues/168

## Acceptance-criteria mapping (Issue #167)

- Reproduced on the known physical-device environment: yes (fresh `main` build; original observation was on the #164-era build — same defect shape).
- Pass 1 / pass 2 captured with build SHA, environment, screenshots, logcat, DB/prefs/file-state evidence: yes (`/tmp/nova167/stageA`, `/tmp/nova167/stageB`).
- Reproduced on an emulator with a repeatable sequence: yes — 2 cycles, deterministic.
- Smallest known preconditions: plain fresh-install case; grid mismatch (default 5x4x4/5x4x5 vs backup 6x5x5) is the only load-bearing precondition observed.
- Restore sequence traced through conversion, restore lifecycle, DB selection/rename, grid prefs/IDP, model/helper reopen, reload, restart: yes.
- Upstream vs fork classification: interaction defect (upstream grid application + fork `cleanUpDatabases`), as above.
- Upstream evidence reconciled: #6504 review risk (`convertAndRestore` not replacing the active DB) is a different facet of the same unreliable-activation design; the actual loss mechanism observed here additionally involves fork cleanup deleting the correctly staged DB.
- Focused production-fix Bug Issue: created (link above).
- Fix contract: one restore produces the expected active workspace after the normal restart/reload lifecycle; a second restore is not an accepted workaround.
- Stable post-fix state for the Organizer investigation: defined above.
