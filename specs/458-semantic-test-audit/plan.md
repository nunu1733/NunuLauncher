---
issue: "#458"
status: accepted
updated: 2026-09-25
---

# Phase 2 実装計画（bounded change list の適用）

`audit.md` §8 の変更リストを適用する。承認根拠: ChatGPT review 5次
（[issuecomment-5831254336](https://github.com/nunu1733/NunuLauncher/issues/458#issuecomment-5831254336)
「Phase 1 はクリア。Phase 2 実装へ移行可。」）。

## 変更する module / seam

### 1. test fixture 修復（契約 assertion は不変、audit §8.6 の分類に基づく前提修復）

| file | 修復内容 | 分類 |
|---|---|---|
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265GateFailedRouteInstrumentationTest.kt` | `organizeAndConfirm` / `writerBusy_immediateConfirmAfterApply` を accepted な #417 scope-first flow に整合（`Selecting` → `confirmSelection(emptySet())` → `planWithConfirmedScope` → `Preview` → `confirm`）。+ @Before で #371 JIT gate の granted fast path（appops GET_USAGE_STATS allow。routed な Issue265ManualEditRecovery と同一 pattern）。routed な ManualOrganizationProductionE2E.startPlain と同一 flow。gate assertion は不変。**加えて本 file は spec 265 の provision に基づき本 PR で初めて track される**（#265「no code lands」で untracked だった working-tree harness。`.git/info/exclude` の local exclude を解除。audit R-10 追記参照） | category 2 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/PageCaptureInstrumentationTest.kt` | 期待 page list を accepted な #155/ADR-0008 契約（`topQsbOnFirstScreenEnabled` 時、rowless first screen が platform-authoritative として先頭、以降 row pages 昇順）に整合。page ordering・空 page 排除・決定性の assertion 構造は不変（§8.6 実施後の単独実行 V11 で category 3 仮定を撤回し category 2 に修正済み） | category 2 |
| `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementMigrationInstrumentationTest.kt` | 第 1 test の冒頭で `ensureActiveDbExists(context)` を呼ぶ（同 class 第 2 test の既存 pattern） | category 3 |

### 2. Move boundary（R-15）

- 新規 `tests/unit/app/lawnchair/organizer/application/store/RecoveryDbBackupExclusionTest.kt`
  （package `app.lawnchair.organizer.application.store`）: `LauncherFiles.ALL_FILES` に
  `RecoveryDbSchema.FILE_NAME` が含まれないことの JVM test（旧 instrumentation と同一
  assertion）。
- 削除 `tests/organizer-instrumentation/com/android/launcher3/organizer/BackupExclusionTest.java`。

### 3. CI routing（ci.yml + helper script、audit §8.1/§8.2）

- `organizer-instrumentation-db-migration-tests` の class list に
  GridMigrationSuccessTest, LauncherPrefsCommitTest,
  DeckRetirementMigrationInstrumentationTest, RestoreProfileRemapTest を追加
  （GridMigrationFailureTest は #461 所有のため含めない）。
- `organizer-instrumentation-reservation-recovery-tests` の class list に
  RecoveryStoreInspectionInstrumentationTest, RecoveryInspectionSnapshotPublicationInstrumentationTest,
  OrganizerDurableStatusInstrumentationTest, RecoveryStoreChunkedManifestInstrumentationTest,
  Issue265GateFailedRouteInstrumentationTest, PageCaptureInstrumentationTest,
  LockAuthoringInstrumentationTest を追加。
- `organizer-instrumentation-category-override-tests` に OrganizerLockScreenTest を追加。
- `organizer-instrumentation-manual-organization-ui-tests` の helper
  （run-manual-organization-ui-instrumentation.sh）に
  OrganizerDiagnosticsExportTimestampInstrumentationTest を追加。
- `run-restore-capture-instrumentation.sh` 末尾に
  NovaRestoreGridApplicationTest の独立 connected invocation を追加（#299 の既存 stage
  順序は不変）。
- `organizer-unit-tests` の `--tests` に `app.lawnchair.migration.*` と
  `app.lawnchair.DeviceProfileOverridesPresetResolutionTest` を追加（§8.3、wildcard 化は
  行わない）。
- `changes` job の surface filter 追加（§8.2）: `surface_layout_write` に
  `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**`、
  `surface_organizer_ui` に同 locks glob と
  `tests/organizer-instrumentation/app/lawnchair/organizer/diagnostics/export/**`、
  `surface_db_schema` に `tests/organizer-instrumentation/com/android/launcher3/LauncherPrefsCommitTest.java`
  と `tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreProfileRemapTest.java`。
- lane↔surface edge（ci_portfolio_map.yml）は不変。validator で edge 集合不変を証跡化。

### 4. 文書更新（同じ PR）

- `docs/engineering/ci-test-portfolio.md`: lane 監査表（追加 class と契約）、surface path
  表、未 routing inventory section を #458 disposition 記録へ更新、UI lane overlap 監査
  と flake 分類の追記、JVM gate filter 記述。
- `docs/engineering/quality-strategy.md`: organizer unit-test gate の filter 記述更新。
- `specs/458-semantic-test-audit/audit.md`: Phase 2 実施結果・検証証跡の追記、status 更新。

## migration / rollback

- production code・DB schema・workflow の構造は変更しない（class filter と test path
  filter の追加のみ）。rollback は class list から該当 class を外すだけでよい。
- 削除する instrumentation class は 1 file（BackupExclusionTest.java）のみで、JVM test が
  同一 assertion を所有する。

## 検証

1. `python3 tools/repo-contract/validate_ci_portfolio.py` /
   `test_validate_ci_portfolio.py` / `validate_repo_contract.py` /
   `test_validate_repo_contract.py`（PyYAML venv 使用、edge 集合不変の証跡）
2. `./gradlew spotlessCheck`
3. `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*' --tests 'app.lawnchair.bugreport.*' --tests 'app.lawnchair.backup.*' --tests 'app.lawnchair.migration.*' --tests 'app.lawnchair.DeviceProfileOverridesPresetResolutionTest'` の local 実行（既存集合の縮小なし + 2 class 追加の確認）
4. fixture 修復 3 class の focused 再実行（local emulator）
5. PR 上の実 GitHub Actions run で対象 lane + JVM gate が green かつ timeout headroom 内
   （AC-458-P2-06 の正本証跡。V5 環境停止の確定分類に使う情報もここで得る）

## 実行の体制

実装と review は同じ session で行うが、merge 前に general-purpose subagent による独立
監査（`docs/assessment/pr-<n>-458-semantic-test-audit.md`）を行う（ユーザ指定フロー）。
