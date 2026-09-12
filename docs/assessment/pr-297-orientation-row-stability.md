# Independent audit: PR #297 orientation test row selection after first model load (issue 292)

> Status: accepted
> Audit date: 2026-09-12

- Auditor: ZCode independent audit session (subagent separate from the implementation session; no source, test, or spec files modified; the only artifact of this session is this record).
- PR: https://github.com/nunu1733/NunuLauncher/pull/297
- Audited head SHA: `066ca4d8fab03d3fa6c11f8e6987d2e0aea88c2d` (branch `issue-292-orientation-row-stability`, base `main`, PR state OPEN; PR `headRefOid` verified identical). Local checkout `git rev-parse HEAD` matches; worktree has an untracked `.ux-review/` directory only (not part of the diff).
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34674138315 (api35 job `organizer-instrumentation-api35-tests`, job 103500845495 — **pass**, 8m30s, at audit time).
- Criteria: specs/292-two-panel-orientation-row-stability/spec.md (frontmatter `issue: "#292"`, `status: accepted`, requirements TS-AC-01…TS-AC-04) and plan.md AC-1…AC-5.

## Scope

`git diff main...HEAD` contains exactly three files: the test change (`tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`, +35/−2) and the new `spec.md`/`plan.md`. Nothing else — no production code, no CI config. Repo identity checked before GitHub operations: `nunu1733/NunuLauncher`, default branch `main`.

The change is tests-only: in `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`, `ensureLauncherRow` moved after `bringLauncherToForeground()` + new `awaitLauncherModelLoaded()` helper (polls `launcher.model.isModelLoaded`, 100 ms interval, 20 s deadline, explicit message-bearing `assertTrue` on timeout); ordering rationale recorded in comments and the `ensureLauncherRow` doc. The existing desktop/hotseat reuse filter is unchanged.

## Mechanism conclusion (independently re-derived from code)

The claimed causal chain is **confirmed**:

1. The `EMPTY_DATABASE_CREATED` flag is set when the launcher db file is first created (`onEmptyDbCreateCallback`, `src/com/android/launcher3/model/ModelDbController.java:143-145`) and is consumed only by `loadDefaultFavoritesIfNecessary()` (`ModelDbController.java:1114`): when the flag is set it logs `"loading default workspace"` (tag `LauncherProvider`), calls `createEmptyDB` — which deletes **all** favorites rows — re-inserts the default layout, then clears the flag.
2. It runs inside every `LoaderTask` (`loadWorkspaceImpl`, `src/com/android/launcher3/model/LoaderTask.java:483`), i.e. at the launcher's first model bind. The old test pinned its plan row via `ensureLauncherRow` *before* foregrounding the launcher; on a fresh db it therefore inserted its own row with `DatabaseHelper.generateNewItemId()` (cached `mMaxItemId` + 1, `src/com/android/launcher3/model/DatabaseHelper.java:484`), then the first bind wiped it and renumbered the default layout. In the CI default layout (`lawnchair/res/xml/default_workspace_4x5.xml`) 5 hotseat resolves are followed by the Google folder, so sequential id assignment yields folder `_id=6` and Maps — 3rd child — `_id=9`, `rank=2`; `AutoInstallsLayout` folder child parsing sets only `container` + `rank` (no screen/cells, `modified=0`). That is exactly the CI failure row: the no-write assert compared `_id == plannedRowId`, so it compared the default-layout Maps child instead of the (deleted) planned row.
3. The before/after delta the assert flagged is the launcher's own legitimate behavior: on rotation rebind `Folder.updateItemLocationsInDatabaseBatch` (`src/com/android/launcher3/folder/Folder.java:1202`) → `FolderGridOrganizer.updateRankAndPos` → `moveItemsInDatabase` rewrites folder children's `screen`/`cellX`/`cellY`/`modified`. The production seam (stale rejection, no-write) is correct; the test compared the wrong row.
4. The fix's wait predicate is sound: `LauncherModel.isModelLoaded()` = `mModelLoaded && mLoaderTask == null && !mModelDestroyed` (`src/com/android/launcher3/LauncherModel.java:139-141`), and `mModelLoaded` is set only in `LoaderTransaction.commit()` (`LauncherModel.java:685`), which `LoaderTask.run` calls at line 411 *after* `loadWorkspace` (line 280) — hence after `loadDefaultFavoritesIfNecessary`. Every loader task goes through `loadWorkspace`, so `isModelLoaded == true` implies the pending default load (if any) has been consumed and no further load can wipe/renumber favorites. After the wait, `ensureLauncherRow` reuses an existing desktop/hotseat row (never a folder child), so the no-write assert always compares the plan row itself.

## Independent re-execution (own run, emulator-5556, API 35 google_apis arm64)

APKs verified fresh against the head before install: the test APK's dex contains the new assertion string ("did not complete its first load within timeout", classes10.dex), and the app APK version marker `(3e11330)` equals the `main` tip SHA — the head commit changes tests/docs only, so production code is identical.

1. `adb -s emulator-5556 uninstall` of both packages (expected failures — not installed), then install of both APKs — Success.
2. Single test `capturedOrientationMatchesConstructedDeviceProfileAuthority` via `am instrument -w -e class ...#capturedOrientationMatchesConstructedDeviceProfileAuthority app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` — **OK (1 test)**. Post-state verified on device: `databases/launcher_5_4_4.db` exists, favorites empty, and `shared_prefs/com.android.launcher3.prefs.xml` contains `EMPTY_DATABASE_CREATED@launcher_5_4_4.db value=true` — the failure-prone precondition exactly as described.
3. Inserted 5 dummy rows (`_id` 2–6, `container=6`, valid launcher intent, null screen/cells) via `run-as app.lawnchair.debug sqlite3`; verified with SELECT.
4. Full class `am instrument -w -r -e class ...TwoPanelOrientationCaptureInstrumentationTest ...` — **OK (3 tests), 0 failed, 13.991s** (AC-2 reproduction-with-fix: same collision preparation, same procedure, green).
5. Logcat (captured between `logcat -c` and `logcat -d`): `TestRunner: started: orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` at 14:00:10.603, `D LauncherProvider: loading default workspace` at **14:00:11.092** (pid 7584), `TestRunner: finished: ...` at 14:00:13.496 — the one-time default load (all-rows delete + default re-insert) demonstrably occurred **inside** the orientation test window, 489 ms after test start, and the fixed test tolerated it. After the run the flag is cleared (consistent with consumption by this load); the favorites table afterwards shows only the tearDown-restored pre-test snapshot (the dummies), as expected from the setUp-snapshot/tearDown-restore pattern.
6. `./gradlew spotlessCheck` — BUILD SUCCESSFUL, exit 0 (AC-4).

## Spec/plan consistency

- Spec scenarios match the implementation: TS-AC-01 (row selection after first bind — implemented, logcat-proven), TS-AC-02 (no-write assert compares the plan row itself — implemented via bind-wait + desktop/hotseat filter), TS-AC-03 (explicit message-bearing failure on bind-wait timeout — code-verified: `awaitLauncherModelLoaded` deadline 20 s + `assertTrue` message; execution-environment equivalent not artificially constructible, per plan's stated substitution), TS-AC-04 (repro — AC-1).
- AC-1 is recorded evidence (PR #289 CI failures at `_id=9` Maps child, runs 34625444030/34665957437, plus local repro at `_id=8` Gmail child). Not re-executed here (would require rebuilding the pre-fix test APK); the mechanism is independently corroborated by the code chain above and by my step-5 logcat showing the default load inside the test window.
- AC-3 (fresh-install 4-class lane green 3 consecutive times, app data cleared between runs) is recorded in the PR body (3/3 runs, "Tests 26/26 completed. (1 skipped) (0 failed)"). Plausible and consistent with the mechanism (the bind-wait closes the race regardless of flag state); not re-run per audit scope.
- AC-5 (3 consecutive CI api35 greens on this head) — **in progress, not complete at audit time**: attempt 1 of run 34674138315 is green (job 103500845495, pass 8m30s); the required reruns are pending, as are 4 other instrumentation lanes (issue52/53/99/155) and the rest of the gate.

## Findings

Pass — no blocking findings.

- [non-blocking] AC-5 (and issue #292's "multi-run green" closure condition, for which the PR body already uses `Closes #292`) is only 1/3 satisfied at audit time. The record should be finalized with the completed rerun evidence before merge; PR #297 carries no `risk: layout-data`/`risk: migration` label (tests-only change), so the high-risk gate is not the applicable bar here, but the issue's own termination conditions still bind.
- [non-blocking] The collision id differs between evidence sources (CI `_id=9` Maps child, local repro `_id=8` Gmail child). This does not weaken the mechanism: id assignment depends on the `mMaxItemId` cache state, and the plan explicitly documents that the mechanism is independent of the inserted id's value — any inserted id can collide with the renumbered default layout. My re-execution exercised the same preparation (inserted ids 2–6, container=6) and the fixed test passed with the default load firing inside the test window.
- [non-blocking] AC-1 was accepted as recorded evidence (CI logs + implementer's local repro) rather than independently re-executed; residual risk is negligible because the AC-2/AC-4 re-execution plus the code chain re-establish the same mechanism independently.
- [non-blocking] Untracked `.ux-review/` directory present in the local worktree; not part of the PR diff, no action taken.

## Post-audit completion evidence (appended 2026-09-12, implementation session)

Recorded after the audit without modifying the sections above (the audit-time statements remain as written).

- Review follow-up commits after the audited head `066ca4d8fa`: `c584edd83c` (this record only) and `c5d3c38e68` (KDoc-only: `ensureLauncherRow`'s ordering constraint scoped to identity-comparison callers; no executable change, so the audited code head's behavior is unchanged).
- **AC-5 complete — 3 consecutive green api35 lane runs on head `c5d3c38e68`**, all within CI run [34674957358](https://github.com/nunu1733/NunuLauncher/actions/runs/34674957358) (same head, job reruns): attempt 1 job 103503106050, attempt 2 job 103504474524, attempt 3 job 103505334781 — each `success` (~7–9 min). The run also shows all 14 checks pass including `final-status` (merge gate). This resolves the audit's first non-blocking finding and satisfies issue #292's "連続複数runでのgreen確認" termination condition.
- Run [34674138315](https://github.com/nunu1733/NunuLauncher/actions/runs/34674138315) (referenced under CI run above) failed overall on `organizer-instrumentation-issue52-tests` (`ManualOrganizationPreferencesInstrumentationTest`, API 36 lane) — a class this PR does not touch; the api35 job in that run was green, and the issue52 lane passed in run 34674957358. Recorded as an unrelated lane flake; split into its own issue if it recurs.
- PR #297 body was corrected after review: an earlier `gh api -f body=@file` misuse had published the literal path string instead of the markdown, leaving the evidence invisible on the PR. The body now carries the full problem/root-cause/change/verification record including the AC-5 evidence above.
