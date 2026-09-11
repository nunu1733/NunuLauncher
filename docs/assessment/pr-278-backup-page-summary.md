# Independent audit: PR #278 (issue 233 multi-page backup restore preview page summary)

- Date: 2026-09-11 (UTC)
- Auditor: independent general-purpose subagent session, distinct from the implementing session
- Audited implementation head SHA: `674983b57399fcb25e16c257ab6c74b0828062f7` (PR #278 head `issue-233-backup-preview-page-summary` at audit start)
- Base: `origin/main` (spec/plan merged via PR #277, commit `62a3c0a40a`)
- Spec referenced: [specs/233-backup-restore-preview-multi-page/spec.md](../../specs/233-backup-restore-preview-multi-page/spec.md) and [plan.md](../../specs/233-backup-restore-preview-multi-page/plan.md), acceptance criteria AC-1..AC-9
- High-risk gate applicability: applicable. PR #278 carries the `risk: layout-data` label (verified via API) and changes `lawnchair/src/app/lawnchair/backup/**`, a high-risk path per [github-workflow.md](../project/github-workflow.md). This document is the required independent audit record.
- Diff inspected: full. `git diff origin/main..674983b573 --name-only` = 7 files, +613/-1:
  - `lawnchair/src/app/lawnchair/backup/BackupPageSummaryReader.kt` (new, 252 lines)
  - `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt`, `RestoreBackupViewModel.kt`
  - `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml`
  - `tests/unit/app/lawnchair/backup/BackupPageSummaryTest.kt` (new, 15 `@Test`)
  - `.github/workflows/ci.yml` (adds `--tests 'app.lawnchair.backup.*'` to the unit-test filter)
  - No changes under `src/com/android/launcher3/` or any AOSP-origin file; no `BackupInfo` proto or backup zip format change; no `LawnchairBackup.restore` / critical-section change.

## Verification performed by the auditor (on head `674983b573`, JDK 21)

- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` → BUILD SUCCESSFUL. JUnit XML: 18 tests, 0 failures, 0 errors (15 new `BackupPageSummaryTest` + 3 pre-existing `LawnchairBackupRestoreCriticalSectionTest`).
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL.
- `python3 tools/repo-contract/validate_repo_contract.py` → repository contract OK.
- `python3 tools/localization/verify_nunu_ja_resources.py --baseline 505dbc40e6154c05158b5d0271c45f6a885a411b` → exit 1 with 10 findings, all pre-existing `manual_organization_*` placeholder-contract mismatches identical in nature to those recorded in the PR #268 audit; zero findings involve the new `backup_page_summary_*` / `backup_preview_partial_caption` strings.
- AC-7 string presence (`git grep "name=\"…\""`): all four new strings (`backup_preview_partial_caption`, `backup_page_summary_title`, `backup_page_summary_entry`, `backup_page_summary_unavailable`) exist exactly once in both `lawnchair/res/values/strings.xml` and `lawnchair/res/values-ja/strings.xml`.

## Code review against the spec contracts

- Zip safety contract (spec "Data and state" / AC-4): PASS. `extractLauncherDbEntry` accepts only the exact-match entry name `launcher.db` (compared against `LawnchairBackup.LAUNCHER_DB_FILE_NAME`); the output path is an internally generated temp file (`File.createTempFile("backup_page_summary", ".db", context.cacheDir)`), never derived from entry names (path traversal structurally impossible; tested with `../launcher.db` and `x/launcher.db`). Duplicate entry → `DuplicateEntry`; per-entry cap 64 MB enforced both via `entry.size` and cumulative written bytes; archive-wide uncompressed cap 512 MB and entry-count cap 10,000 bound the scan of unrelated entries; chunked reads call `ensureActive()` for cancellation. The temp file is deleted in `finally` (best-effort with log). The DB is opened with `SQLiteDatabase.OPEN_READONLY` and only `SELECT ... GROUP BY` is issued; there is no write to `favorites` anywhere in the diff.
- Coverage predicate (AC-1 caption condition): PASS. `previewedScreenId` = `workspaceScreens` `screenRank` minimum (`SELECT _ID FROM workspaceScreens ORDER BY screenRank LIMIT 1`), falling back to `FIRST_SCREEN_ID_FALLBACK = 0L` when the table is absent or errors — matching the renderer's `FIRST_SCREEN_ID`, not the minimum non-empty screen (spec change history 3rd revision). Caption shows when any non-empty page differs from the previewed screen, including the empty-first-screen case (unit test `empty first screen with items on later screen shows caption`).
- Aggregation rules: PASS. Query filters `container = -100` (`CONTAINER_DESKTOP`), so hotseat (-101) is excluded; only screens present in grouped rows appear, so empty screens are excluded; pages sorted by screen id and displayed as 1-based sequential numbers via `forEachIndexed` (sparse ids compacted); folder count = itemType 2; widget count = itemType 4 + 5; item total = all rows of the screen (folder counted once as one row; no double counting of children is possible in a row-based GROUP BY).
- UI/state (AC-6 structure): analysis runs in a separate `StateFlow<BackupPageSummaryUiState>` (`Pending` renders nothing, so Success display is never blocked); caption/summary are placed inside `RestoreBackupOptions` outside the portrait-only `DummyLauncherBox` and gated on `backupContents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)` only — independent of screenshot presence and checkbox state, and rendered in both orientations since the options column is shared. Restore-button enable logic is untouched. `Unavailable` renders the typed string and does not affect restore (AC-3 zip-level; SQLite-level failure was exercised on-emulator per PR body).
- Scope (AC-9): changes confined to `app.lawnchair.backup`, resources, JVM tests, and the CI filter; plan's Upstream 方針 section records the upstream-origin constraint and the no-AOSP-patch policy.

## Emulator evidence accepted from the PR body (not re-executed by the auditor)

- AC-1 / AC-8 (partial) / AC-3 (SQLite level): 2-page layout backup on API 36 emulator; caption and per-page summary matched the real launcher.db row composition (screen 0 = 1 folder row, screen 1 = 3 app rows); the backup's real db lacks `workspaceScreens`, so the screen-0 fallback path and the readonly SQLite open path were exercised on a real db; uiautomator dump confirms the texts are accessibility nodes; restore round-trip verified (Page 1 of 2 + folder + 3 apps on page 2).

## CI status at audit time (head `674983b573`)

- CI run https://github.com/nunu1733/NunuLauncher/actions/runs/34551442368 (pull_request): `check-style`, `validate-repo-contract`, `changes` pass; `build-debug-apk`, `organizer-unit-tests`, and instrumentation jobs pending at audit time — `final-status` confirmation pending; the merge operator must confirm it green on the final head (including this audit commit) before merge.
- High-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/34551467244: `high-risk-evidence` job fail at audit time is expected — this audit record is the missing artifact it checks for; the gate re-runs on the head that includes this commit.

## Findings

1. Info: the unavailable string (`backup_page_summary_unavailable`) is shown whenever analysis fails for a layout backup, even when the backup is single-page. This matches the spec's typed-failure wording ("page 情報を表示できない"旨) and is deliberate, but means a single-page backup with an unanalyzable db gains a line of text where the success path shows nothing. No action required.
2. Info: `entry.size > maxEntryBytes` is checked from the zip header, which a hostile zip can under-report; the cumulative written-byte check closes this hole (tested by `oversized launcher db entry is rejected before writing beyond the cap`, asserting the target file never exceeds the cap). Correctly defended.
3. Info (pre-existing, not introduced by this PR): the ja localization contract script exits 1 with 10 `manual_organization_*` findings identical in nature to those on base; none involve this PR's strings.
4. Unverified areas (recorded as such in the PR body): on-device landscape rendering (rotation could not be enabled on the emulator; placement outside the portrait-only `DummyLauncherBox` provides structural assurance only) and actual TalkBack read-aloud (text-node accessibility confirmed via uiautomator dump only). These remain open beyond this audit.

## Verdict

**PASS (pass-with-notes)**. On head `674983b573`, AC-1, AC-2, AC-4, AC-5, AC-7 are verified by the auditor's independent test run and code review; AC-3 is verified at zip level by unit test and at SQLite level by PR-body emulator evidence; AC-6 is verified structurally (state separation, placement, gating) plus PR-body evidence, with on-device landscape unverified; AC-8 is satisfied by the PR-body emulator evidence except TalkBack read-aloud; AC-9 is satisfied (risk label present, plan records the upstream policy, this document plus a green `final-status` on the final head completes the gate). No spec/implementation discrepancy found. Merge requires the `final-status` CI run to be green on the final head, which was pending when this audit concluded.
