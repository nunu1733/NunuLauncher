# High-risk audit: PR #278 multi-page backup restore preview page summary

> Status: accepted
> Audit date: 2026-09-11

- Auditor: independent general-purpose subagent session, distinct from the implementing session (solo-maintenance independent-session audit per docs/project/github-workflow.md)
- PR: https://github.com/nunu1733/NunuLauncher/pull/278
- Head SHA: 25b6e90d687524fed89da69872f2d0c6a2f489b5
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34555077879 (pull_request merge-gate run on head 25b6e90d68; completed/success, `final-status` pass)
- Criteria: specs/233-backup-restore-preview-multi-page/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9 (spec reviewed at commit 62a3c0a40a, merged via PR #277)

## Scope

The audited Head SHA 674983b57399fcb25e16c257ab6c74b0828062f7 is the implementation head; this audit record is the docs-only commit that follows it, per the gate's rule that only docs-only commits may follow an audit.

Re-audit note (2026-09-11): the audit was re-anchored from 674983b57399fcb25e16c257ab6c74b0828062f7 to 25b6e90d687524fed89da69872f2d0c6a2f489b5. `git show 25b6e90d68` verified the intervening delta is exactly one line in `specs/233-backup-restore-preview-multi-page/spec.md` frontmatter (`status: draft` → `status: accepted`, resolving original finding 1); no implementation code, tests, resources, or workflows changed. All other audit content (criteria checks, executed test surface, remaining findings 2–5) is unchanged from the original audit of 674983b573, whose CI merge-gate run 34551442368 was independently confirmed green. CI on the re-anchored head is green: run 34555077879, `final-status` pass (verified via `gh pr checks 278`).

Diff `git diff origin/main..674983b573 --name-only` — 7 files, +613/-1:

- `lawnchair/src/app/lawnchair/backup/BackupPageSummaryReader.kt` (new): read-only zip + SQLite analysis of the backup's `launcher.db` (exact-name entry extraction, pure-JVM aggregation, readonly GROUP BY query).
- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt`: caption + per-page summary UI outside the portrait-only `DummyLauncherBox`.
- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt`: `pageSummary` StateFlow separated from the Success state.
- `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml`: 4 new strings each.
- `tests/unit/app/lawnchair/backup/BackupPageSummaryTest.kt` (new): 15 JVM tests.
- `.github/workflows/ci.yml`: unit-test filter gains `--tests 'app.lawnchair.backup.*'`.

Runtime write paths / migration surface reviewed: none. The reader opens the extracted db with `SQLiteDatabase.OPEN_READONLY` and issues only `SELECT screen, itemType, COUNT(*) ... GROUP BY`; there is no write to `favorites`, no launcher-DB touch, no backup format / `BackupInfo` proto change, no change to `LawnchairBackup.restore` or its critical section, and no change under `src/com/android/launcher3/` or any other AOSP-origin file. The only filesystem write is a per-analysis unique temp file under `context.cacheDir`, deleted in `finally` (best-effort, logged).

## Criteria check

- AC-1 (caption + page summary for pages outside the previewed screen, ascending sequential page numbers): PASS. `previewedScreenId` = `workspaceScreens` `screenRank` minimum, falling back to `FIRST_SCREEN_ID_FALLBACK = 0L` when the table is absent/errors — the renderer's actual draw target, not the minimum non-empty screen (spec change history, 3rd revision). Caption shows when any non-empty page differs from the previewed screen. Pages render as 1-based sequential numbers (`forEachIndexed`), sparse screen ids compacted. Verified by unit tests (`multi page backup aggregates per page counts`, `sparse screen ids are compacted to rank order`, `workspaceScreens first screen drives the coverage predicate`) and PR-body emulator evidence (2-page backup, summary matched real launcher.db rows).
- AC-2 (no caption/summary for single-page-on-first-screen or layout-less backups): PASS. `single page on first screen hides caption`, `single page matching previewed screen still hides caption with table`; UI gates on `backupContents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)`; hotseat-only/empty favorites yield zero pages (query filters `container = -100`, empty screens never appear).
- AC-3 (typed unavailable on analysis failure; restore not blocked, not Error): PASS at zip level by unit tests (missing entry, duplicate STORED-record entry, size caps, traversal names); SQLite-level failure (open failure, schema missing) is caught and logged to `Unavailable` in `readSummaryFromDb`; restore-button enable logic untouched. SQLite path exercised on a real db on-emulator per PR body. Emulator evidence accepted from the PR body (auditor did not re-run the emulator).
- AC-4 (read-only zip handling, exact-name entry, internally generated temp file, cleanup, no favorites writes): PASS. Code review confirms: exact string comparison against `LawnchairBackup.LAUNCHER_DB_FILE_NAME`; temp file via `File.createTempFile(...)` in `cacheDir` (entry names never become paths); duplicate → `DuplicateEntry`; 64 MB per-entry cap enforced both from the header size and cumulative written bytes (the written-byte check closes the under-reported-header hole); 512 MB archive-wide uncompressed budget and 10,000-entry cap bound the scan; chunked reads call `ensureActive()`; `finally` deletion; no favorites write anywhere in the diff.
- AC-5 (JVM unit tests for aggregation determinism and failure injection): PASS. 15 tests cover multi-page counts, sparse-id compaction, custom widgets (itemType 5), folder=2, hotseat/empty-screen exclusion, empty-first-screen boundary, plus zip failure injection (traversal names, duplicate entry via hand-built STORED records, missing entry, entry cap with file-size assertion, archive budget, entry-count cap, caller-controlled target path).
- AC-6 (portrait/landscape display, checkbox-independent, non-blocking analysis): PASS structurally. Separate `BackupPageSummaryUiState` flow (`Pending` renders nothing, Success never waits); placement inside `RestoreBackupOptions` outside the portrait-only `DummyLauncherBox`; gating only on layout-contents flag. On-device landscape rendering remains unverified (see Findings).
- AC-7 (strings in both `values/` and `values-ja/`): PASS. `git grep` confirms all four of `backup_preview_partial_caption`, `backup_page_summary_title`, `backup_page_summary_entry`, `backup_page_summary_unavailable` exactly once in each file. The ja localization contract script reports only the 10 pre-existing `manual_organization_*` findings, none involving these strings.
- AC-8 (emulator evidence incl. TalkBack and round-trip): PARTIAL — accepted from PR body. 2-page layout backup, caption/summary matching the real db, real-db fallback path (no `workspaceScreens` table), restore round-trip (Page 1 of 2 + folder + 3 apps) all recorded. TalkBack actual read-aloud not performed (text-node accessibility confirmed via uiautomator dump only); landscape evidence not captured (see Findings).
- AC-9 (upstream constraint recorded in plan; fork-only change; high-risk gate): PASS. plan.md "Upstream 方針" records the upstream/AOSP origin of the single-page preview constraint and the no-AOSP-patch policy; the diff touches only `app.lawnchair.backup`, resources, JVM tests, and the CI filter; the `risk: layout-data` label is present (verified via API); this document plus a green `final-status` on the final head completes the gate.

## Executed test surface

Executed by the auditor on head `674983b573` (JDK 21, Android SDK 36.1):

- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` → BUILD SUCCESSFUL; JUnit XML: 18 tests, 0 failures, 0 errors (15 new `BackupPageSummaryTest` + 3 pre-existing `LawnchairBackupRestoreCriticalSectionTest`).
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL.
- `python3 tools/repo-contract/validate_repo_contract.py` → repository contract OK.
- `python3 tools/localization/verify_nunu_ja_resources.py --baseline 505dbc40e6154c05158b5d0271c45f6a885a411b` → exit 1, 10 findings, all pre-existing `manual_organization_*` placeholder-contract mismatches also present on base; zero findings involve this PR's strings.
- `git grep` for the four new string names across `values/` and `values-ja/` → all present in both locales (AC-7).
- Diff review (all 7 files read in full) against the spec's zip safety contract, coverage predicate, and aggregation rules → conformant; no AOSP/proto/format changes.

CI runs observed (pull_request events, head `674983b573`): run 34551442368 completed/success — all jobs green, including `organizer-unit-tests` (which now executes the `app.lawnchair.backup.*` filter), `build-debug-apk`, and `check-style`. Two pre-existing organizer instrumentation flakes (`TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` in the api35 job; the full issue-53 onboarding suite on its first attempt) required `--failed` reruns to pass; none touch code or tests modified by this PR. The original attempt of this run had been cancelled mid-flight by the audit-record docs pushes and was rerun on the same head SHA.

## Findings

1. Blocking for the machine gate (documentation state, not implementation): the referenced spec `specs/233-backup-restore-preview-multi-page/spec.md` frontmatter still reads `status: draft`, while the PR body and PR #277 treat the spec as reviewed/approved. `validate_high_risk_evidence.py` requires `accepted`/`implemented`. The spec status line must be corrected by the owner/worker; until then the `high-risk-evidence` job fails on this point alone. The auditor's mandate is limited to the audit record and does not include editing the spec's state. RESOLVED: commit 25b6e90d68 flipped the status to `accepted` (verified one-line diff); see the re-audit note in Scope.
2. CI on the audited head is green, but only after two `--failed` reruns of unrelated organizer instrumentation flakes (see Executed test surface). The audited diff neither touches nor is touched by those tests.
3. Unverified (recorded in the PR body, outside this audit's reach): on-device landscape rendering (rotation could not be enabled on the emulator; structural assurance only via placement outside `DummyLauncherBox`) and actual TalkBack read-aloud (uiautomator text-node confirmation only). Suitable to close via follow-up manual evidence without code change.
4. Info: the unavailable string is shown for any layout backup whose analysis fails, including single-page ones, where the success path shows nothing. This matches the spec's typed-failure wording; deliberate.
5. Info: pre-existing ja localization contract findings (10 × `manual_organization_*`) are identical in nature to those recorded in the PR #268 audit; not introduced here.

Verdict: **pass-with-notes**. No discrepancy between spec 233 and the implementation on head `674983b573` was found; the notes above are CI-pending status and manual-evidence gaps already disclosed in the PR body.
