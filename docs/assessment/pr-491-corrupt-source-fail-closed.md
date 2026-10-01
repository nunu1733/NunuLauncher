# High-risk audit: PR #491 durable grid-migration recovery source が unreadable なとき fail closed する（#461）

> Status: **GO（コード・受入条件）/ merge gate は CI 再実行待ち**
> Audit date: 2026-10-01

- Auditor: 独立session（subagent監査者）。実装・review・検証実行に関与していない。作業tree（= PR head そのもの。`git rev-parse HEAD` と監査対象SHAが一致、tracked変更なし）で read-only 照合と test 再実行を行った。
- PR: https://github.com/nunu1733/NunuLauncher/pull/491 （head branch `issue-461-corrupt-source-fail-closed`、base `main`。**注: 現時点で `risk: layout-data` を含む label が1つも付与されていない**。後述 Findings 1）
- Head SHA: 647badcd3a6db6a3276a1611f88c6c0a0c45fe21
  （`gh pr view 491` の `headRefOid` と一致。base `origin/main` = GitHub main = `833ded935f`。`git rev-list --count HEAD..origin/main` = 0 で rebase 済み。diff = 8 files, +665/-8）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36820281984
  （pull_request event、CI workflow、head `647badcd3a`、2026-10-01 05:32:44Z開始/05:51Z完了。**db-migration lane（`GridMigrationFailureTest` 含む）と全 instrumentation lane は green だが、`organizer-instrumentation-production-input-tests` の既知family flake により `final-status` は現時点 failure**。同 run の failed job を再実行して green にすることが merge gate の残条件。詳細は「Executed test surface」）
- 注: 対象head上の `High-risk gate` run [36820281990](https://github.com/nunu1733/NunuLauncher/actions/runs/36820281990) は `high-risk PR (high-risk path change(s): src/com/android/launcher3/model/ModelDbController.java): independent evidence required / FAIL: no docs/assessment/pr-491-<slug>.md audit record for this PR` で failure。本 record がその解消（ただし上記の CI green 再取得が前提）。
- Criteria: [spec 461](../../specs/461-corrupt-durable-source-readability-fail-closed/spec.md)（accepted）の AC-461-01, AC-461-02, AC-461-03, AC-461-04, AC-461-05
- Criteria: [spec 59](../../specs/59-preserve-source-grid-migration-failure/spec.md)（implemented）の AC-59-06（本修正が施行する fail-closed の既存契約）

## Scope

- 対象diff: `origin/main`..`647badcd3a`（8 files, +665/-8）。production 1 file（`src/com/android/launcher3/model/ModelDbController.java`）、tests 2 file、spec 2 file、CI/portfolio 3 file。**UI / `lawnchair/src/app/lawnchair/organizer/application/**` / `RecoveryStore` は diff に出現しない**（後述の production-input lane flake の非関連根拠）。
- production変更は1 seam のみ: `ModelDbController.validatedJournalSourceFile`（:825）へ
  (a) raw magic header check（先頭16 byte = `SQLite format 3\0`、`read < 16`・`IOException` も同一 `IllegalStateException` branch）と
  (b) 非破壊整合性 probe `probeJournalSourceIntegrity`（:872）を追加。(b) は
  `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY, new NonDeletingErrorHandler())` で開き（`CREATE_IF_NECESSARY` なし）、`PRAGMA quick_check` が単一 result `ok` であることを要求し、`SQLiteException`（`SQLiteDatabaseCorruptException` 含む）・non-ok は `IllegalStateException` へ合流、`finally` で必ず close する。`onCorruption` で delete/recreate しない controller-private の `NonDeletingErrorHandler`（:908, private static class）が probe 専用に付与され、既定 `DatabaseErrorHandler`・`DatabaseHelper`・`NoLocaleSQLiteHelper` の他経路は不変。
- 4 呼び出し口は共有 seam 経由で全て fail closed: `reconcileActiveDatabaseJournal`（:567）、`compensateAndRestore`（:601）、`restoreSourceAuthority`（:752）、`validateFinalizedJournalSource`（:802）。validation は writable open（`openJournalSource`）より前に throw するため、既定 handler の delete→空DB再作成 path に到達しない。
- DB schema 変更・migration・新規書込み経路・権限・通信の追加なし。test は既存 `GridMigrationFailureTest` に1 method 追加、既存 `GridMigrationTestSupport` に fixture helper 追加のみ（新 class / lane / file なし）。
- 対象外: 本 audit は実機 UI 操作を伴わない。AVD focused run はローカル emulator-5554（AVD `issue142_api36`、API 36）で実施。

## Criteria check

総合: **AC-461-01〜05 すべて PASS（独立再実行とコード照合に基づく）**。

- **AC-461-01（既存 non-SQLite dim oracle が AVD で pass、test 本体不変）**: **PASS**。diff は `GridMigrationFailureTest` に新 method を追加するのみ（既存 method `restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper` は :337-354 のまま byte-identical）。独立 focused run（下記）の XML に同 method の testcase が pass で存在。test は `tryMigrateDB` の `RuntimeException` を `fail()` で要求し、`publishedHelper()` null と journal `RESTORE_PENDING`（TARGET_DB/SOURCE_DB/sourceState）を assert する。
- **AC-461-02（valid-header + body-corrupt の新 method と byte-identical 保全）**: **PASS**。新 method `restorePendingJournalWithValidHeaderCorruptBodyFailsClosedAndPreservesCorruptSource`（test :356-378）が pass。fixture `corruptDatabaseBodyPreservingValidHeader` は実 DB を作成（大きな rows で page1 以降を使用）→ 事前 raw bytes を read → page size 以降を `0x5A` で破壊（header + page1 は保持）→ 破壊後 bytes を返す。test は attempt 前に capture した bytes と、失敗後の `context.getDatabasePath(SOURCE_DB)` の raw bytes を `assertRawBytesIdentical`（raw file I/O、db open を介さない）で比較し、削除・再作成・変更がないことを要求する。修正前の識別力は「header check 通過 → writable open が既定 handler で空DBへ self-heal → 例外なしで `fail()` に到達」の制御流から成立する（本 audit では修正を revert しないため red 側の再実行はしていない。未確認範囲）。
- **AC-461-03（4 dim 合流・非破壊 probe・CLOSE 保証）**: **PASS**。truncated（<16 bytes）/ zero-byte / non-SQLite は magic check の `read < 16 || !Arrays.equals(...)` で、`IOException` は外側 catch で、valid-header + body-corrupt / quick_check non-ok / `SQLiteException` は `probeJournalSourceIntegrity` で、すべて同一 `IllegalStateException` branch に合流する（:853-859, :872-902）。probe は `OPEN_READONLY` のみ（`CREATE_IF_NECESSARY` なし）、custom non-deleting handler、`finally` close を code 照合。truncated / zero-byte dim は spec Non-goals どおり恒久 test を持たず inspection 判定（non-SQLite dim は AC-461-01 oracle、body-corrupt dim は AC-461-02 が所有）。
- **AC-461-04（既存 missing-source / success-path の結果不変、focused run の PR 記録）**: **PASS（PR本文の記録のみ stale、後述 Findings 2）**。独立 focused run で `GridMigrationFailureTest` + `GridMigrationSuccessTest` = 33 tests / 0 failures / 0 errors / 0 skipped。missing-source（`restoreFailedJournalWithMissingSourceFailsClosedWithoutCreatingSource`、`finalizedValidationWithMissingSourceAbortsBeforeTargetCanBeUsed`）と success-path（`initialTargetTransactionContainsBackupJournalMigrationUnknownAndTmpCleanup`、`generalControllerMigrationPreservesSourceUntilTargetIsPublished` ほか）を含む。PR への記録は commit `647badcd3a` の message（FailureTest 30/30、Success+Failure 33/33）と head CI の db-migration lane green が担う。PR 本文は旧 revision の記録（29/29, 32/32）のままで要更新。
- **AC-461-05（db-migration lane への routing と portfolio/map の同一PR更新、edge 不変）**: **PASS**。`.github/workflows/ci.yml` の db-migration lane class list 末尾に `com.android.launcher3.model.GridMigrationFailureTest` を追加（「stays unrouted behind #461」comment も更新）。`docs/engineering/ci-test-portfolio.md`（lane 行・Routing 分離 disposition・model/** mapping 注記）と `tools/repo-contract/ci_portfolio_map.yml`（comment のみ追加）を同一PRで更新。map の lane↔surface edge は不変（diff は comment 3 行のみ）。独立に `python3 tools/repo-contract/validate_ci_portfolio.py` → `CI portfolio validation OK`、self-test 21 tests OK を再実行し、`validate-repo-contract` job も head CI で green。head CI の db-migration job は class list を含む 50 tests を BUILD SUCCESSFUL で完走し、class filter による実行を実ログで確認（path mapping のみに依拠しない）。
- **AC-59-06（参照契約）**: journal/Favorites を対象確認。corrupt source の削除→空DB publish→source preferences 書換→journal/BACKUP 削除という逆状態に到達し得た経路が、writable open 前の validation で遮断されることを code 照合。missing-source は従来どおり fail closed。

## Executed test surface

独立sessionによる対象head `647badcd3a6db6a3276a1611f88c6c0a0c45fe21`（作業tree = head、tracked変更なし）での再実行:

```bash
git rev-parse HEAD
# → 647badcd3a6db6a3276a1611f88c6c0a0c45fe21

./gradlew spotlessCheck --rerun-tasks
# → BUILD SUCCESSFUL in 13s（5 actionable tasks: 5 executed、強制再実行）

./gradlew assembleLawnWithQuickstepGithubDebug
# → BUILD SUCCESSFUL（445 actionable tasks: 1 executed, 444 up-to-date）

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationFailureTest,com.android.launcher3.model.GridMigrationSuccessTest
# → BUILD SUCCESSFUL in 9s。Starting 33 tests / Finished 33 tests on issue142_api36(AVD) - 16
#   XML: build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/
#        TEST-issue142_api36(AVD) - 16-_-lawnWithQuickstepGithub.xml
#   tests=33 failures=0 errors=0 skipped=0（timestamp 2026-10-01T05:36:25Z）
#   確認した testcase: restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper（pass）、
#     restorePendingJournalWithValidHeaderCorruptBodyFailsClosedAndPreservesCorruptSource（pass、0.028s）、
#     restoreFailedJournalWithMissingSourceFailsClosedWithoutCreatingSource（pass）、
#     finalizedValidationWithMissingSourceAbortsBeforeTargetCanBeUsed（pass）

git diff --check origin/main...HEAD
# → exit 0（whitespace error なし）

python3 tools/repo-contract/validate_ci_portfolio.py
# → CI portfolio validation OK（routing の lane/surface edge と ci.yml の整合）
python3 tools/repo-contract/test_validate_ci_portfolio.py
# → Ran 21 tests ... OK（validator self-test）
```

注: 最初に `spotlessCheck` を `assemble` と同一 invocation で `--rerun-tasks` 実行した際、Gradle の task-dependency validation（`:spotlessJava` が `:compatLib:compileDebugAidl` を暗黙参照）で tooling 起因の failure になったため、上記のとおり分離・逐次実行した（コード起因ではない。単独実行は green）。

CI（対象head上の pull_request run。実行結果は GitHub Actions の記録そのもの）:

- run [36820281984](https://github.com/nunu1733/NunuLauncher/actions/runs/36820281984): **conclusion = failure、`final-status` = failure**。
  - success: `changes`、`check-style`、`organizer-unit-tests`、`validate-repo-contract`、`build-debug-apk`、`organizer-instrumentation-db-migration-tests`（job 110234700135。class list に `GridMigrationFailureTest` を含む 50 tests、BUILD SUCCESSFUL 6m14s）、`-shared-writer-tests`（surface_layout_write fan-out）、`-restore-capture-tests`、`-reservation-recovery-tests`、`-production-input-tests` 以外の全 UI lane（`-category-override-tests`（Issue #490 の lane）も本 run は green）、`-onboarding-proposal-tests`、`-method-choice-journey-tests`、`-exchange-import-ui-tests`
  - **failure: `organizer-instrumentation-production-input-tests`**（job 110234700290）— `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` が `expected:<STALE_REVISION> but was:<RECOVERY_STORE_UNAVAILABLE>`（26 tests / 1 skipped / 1 failed）。
- 失敗の独立根拠: 同 test class は Issue #435（writer race を「負荷で競合順序が入れ替わる時間依存 flaky oracle」と明記）の追跡対象で、#418（API 36 organizer instrumentation の断続失敗トラッカー）の family。本PRの diff は `organizer/application/**`・`RecoveryStore`・UI を含まない（Scope 参照）。当日の main run も別 lane（36751599263: manual-organization + method-choice、36715379223: reservation-recovery、36380059358: manual-organization）で断続 failure しており、head 非依存の環境 flake と判定する。
- 旧 head（review 前 `ef17190b69`、本PR履歴上は rebase 前で head の祖先ではない）の run [36816422048](https://github.com/nunu1733/NunuLauncher/actions/runs/36816422048) では、db-migration lane green の一方、`category-override`（`CustomCategoryPreferencesInstrumentationTest.renameKeepsTheEntryPresentedAsTheSameCategory`、ComposeTimeoutException）と `manual-organization`（`ManualOrganizationPreferencesInstrumentationTest.emptyHomeSelectAllMethodFaceAiArmImportAttachReachesThePreview`、Compose IndexOutOfBounds）が failure。両者も UI Compose の既知 flake family（#490 / #418）で、本PR diff は当該 UI を変更しない。

**Gate への含意**: 上記の理由により、対象head上で `final-status` が green の CI run は本 audit 時点で未取得。`High-risk gate` の機械要件（成功した pull_request run）を満たすには、run 36820281984 の failed job（production-input）を再実行して green にすることが必要。再実行で green になれば、本 record の Head SHA / CI run 参照はそのまま gate 条件を満たす。

## Findings

1. **`risk: layout-data` label が PR #491 に付与されていない**（`gh pr view 491 --json labels` が空、repo には label 自体は存在）。AGENTS.md の「高リスクPRの独立エビデンス」は label を前提とする運用で、現状は validator の path backstop（`ModelDbController.java`）だけで high-risk 判定されている。main 側で label 付与が必要（監査者は付与していない）。
2. **PR 本文が review 前 revision のまま stale**。「先頭16バイト magic check のみ」「read-only openDatabase probe は不採用」という記述と 29/29・32/32 の test 数は旧 head のもので、現 head は magic + 非破壊 quick_check probe（custom non-deleting handler）で FailureTest 30/30・combined 33/33。AC-461-04 の「focused run を PR に記録」は commit message / CI run / 本 record で満たされるが、PR 本文の更新（実装session/owner）を推奨。
3. **CI merge gate 未充足（環境 flake）**: 上記のとおり `final-status` は production-input lane の `TwoPanelOrientationCaptureInstrumentationTest` flake で failure。再実行で green 化が必要。known flake を理由に masking せず、GitHub の run 記録を green にすることが要件（#435 / #418 と同 family、本PR diff の非対象 path）。
4. **spec change history の承認記録の所在**: spec 461 の Change history は「Status set to accepted (approval recorded on the issue)」と記載するが、Issue #461 には comment が1件もない（`gh issue view 461 --json comments` が空）。plan/workflow の要求（accepted spec + plan revision）自体は repository 内の status: accepted で満たされるが、owner approval の link が辿れないため、記録の是正または明示を推奨。
5. **軽微なコード観察（非阻塞）**: `probeJournalSourceIntegrity` の `finally` は、validation failure が無い場合の `close()` 例外を握りつぶす（comment に意図の記載あり）。read-only probe の close 失敗に限られ、fail-closed 判定と保全性への影響はない。
6. **本 audit の未確認範囲**: (a) 新 method の red 方向（修正前挙動）は修正 revert 不可のため code 制御流からの推論で、独立再実行はしていない。(b) truncated / zero-byte dim は spec Non-goals どおり恒久 test なしの inspection 判定。(c) AVD はローカル `issue142_api36` のみ（他 AVD / CI x86_64 は AC-461-05 の lane green で補完）。(d) production-input の green 再実行は本 audit の作業範囲外（main が実施）。

## 判定

**GO（コード・受入条件）** — spec 461 の AC-461-01〜05 と参照契約 AC-59-06 を、対象headの実コード・diff・独立 focused AVD run（33/33 green、XML で tests=33 failures=0 errors=0、両 corrupt-source oracle の実行を確認）・spotlessCheck 強制再実行・assemble・head CI の db-migration / shared-writer lane green で確認した。review 5924983374 の高2（非破壊完全性検証、byte-identical 保全 assert）と中1（spec/plan accepted 化、main への rebase 済み: behind 0）は head 上で実装・反映済みである。

merge の残条件は2点: (1) run 36820281984 の failed job 再実行による `final-status` green（production-input lane の既知 family flake。green 化により本 record が High-risk gate の機械要件を満たす）、(2) `risk: layout-data` label の付与と PR 本文 evidence の更新（運用上の推奨）。コード・受入条件に関する独立 audit の結論は上記のとおり GO であり、再実行後の head で本 record 以降にコード変更は許容されない（docs-only のみ）。

test-audit skill（focused audit）の観点も確認した: protected contract は controller entry の fail-closed（real SQLite/filesystem 境界が必要）、canonical owner は既存 `GridMigrationFailureTest`、新規 production/test seam なし、impact surface は surface_db_schema で既存 lane 再利用、CI classification は Permanent の regression gate、path mapping ではなく class filter の実実行を CI ログで確認、flake は #422 class 5（環境）family として Issue #435/#418 に接続し、green 化のための test 弱体化は行っていない。
