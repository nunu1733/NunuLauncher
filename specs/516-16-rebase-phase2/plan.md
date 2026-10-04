# Plan: 16-dev rebase実行（Epic #516 Phase 2 / Issue #532）

> Status: accepted（2026-10-04。PR #533 review round 5でblocking解消・Approved（[review](https://github.com/nunu1733/NunuLauncher/pull/533#issuecomment-5976918255)。head `be377805fe85b46175aef3f33c0f6ba9005aa5ca` を確認）。受入は本PR #533のmergeで完了する）
> Risk tier: H（[spec.md](./spec.md)参照）
> 対象revision: main `38262fb74d144f4655dfbf01c0084e44c86560be`、anchor upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`

## 1. 現在codeの根拠

- fork変更の全量: baseline `505dbc40` 以降のmain first-parent 300単位（PR merge 296 + 直接commit 4）。
- 上流変更の全量: [Phase 0 assessment](../../docs/assessment/issue-516-16-rebase-phase0-research.md) §3（5,166 files）。
- 再適用対象: bridge inventory 105 path（fork追加49 / upstream無変更8 / upstream変更45 / upstream削除3）。
- 競合面の判定材料: 本plan §3のインベントリ（各単位が48 adapt pathに触れるかでA/B/C分類。生成日 2026-10-04、生成scriptは `git diff-tree` first-parent diff × adapt path集合）。

## 2. 変更module / seam / 方式

- **rebase branch**: `issue-516-rebase-16-dev`（`43a21b43` から作成済み）。`main` はrebase完了・検証完了まで変更しない。
- **replay単位**: per-PR squash replay。merge commit `M` には `git cherry-pick -m 1 <M>`（first parent基準の差分）を適用し、messageを `PR #<番号>: <title>` へrewordする。直接commitは単純cherry-pickする。元commit SHAはreplay-logに記録する。
- **順序**: first-parentの現在順（時系列）を厳守する。並べ替えによるsemantic conflictを避ける。
- **submodule**: `platform_frameworks_libs_systemui` のpinは、当該submodule差分を含む単位に最初に到達した時点でreplayを停止し、「選択SHA（`7d9e92bd` + icon shadow修正の再適用、または修正済み `6a11ef76` 維持）・保持方法・根拠」をADR-0018の改訂（Decision 2の判断記録）としてassessment/ADRへ反映し、review/accept後にreplayを再開する。判断確定まで当該単位のsubmodule差分を適用しない。選択結果はreplay-logと最終review packetへ固定する。

## 3. インベントリと実行順（2026-10-04時点。実行中の増分はreplay-logへ記録）

| 群 | 単位数 | 定義 | 取扱い（分類ラベルであり、実行順には使わない。§8参照） |
|---|---:|---|---|
| A | 132 | docs/specs/tools/.github等の非production単位 | 競合なし想定。適用して次へ |
| B | 86 | production差分だがadapt path非接触（fork-owned新規file中心） | 競合なし想定。依存先API変化のcompile追従は後続単位またはgate時に解消 |
| C | 82 | 48 adapt pathのいずれかに接触 | conflict解消の主体。dispositionに従い再表現する |

C群の代表（replay-logへ全量を記録）: PR #79（deck退役本体、95 files × 15 adapt paths）、#341/#498（user categories / destination policy）、#481（edit undo、`Folder.java`/`ModelWriter.java`）、#476/#486（edit surface / home entry）、#75/#77（schema・grid migration）、#160/#314/#319（model reload/LoaderTask）、#464（benchmark、build.gradle/settings.gradle）。`LauncherModel.java`→`.kt` 化・`GridSizeMigrationUtil` 分割・`MainThreadInitializedObject` onPostInit削除の3点（Phase 0 §5「要対応の3点」）がC群のsemantic解消の中心である。

## 4. conflict解消手順

1. conflictが発生したら、当該pathのdisposition（[Phase 0 §5](../../docs/assessment/issue-516-16-rebase-phase0-research.md)）を確認する。
2. **keep**: fork側内容をそのまま復元する（patch競合なしの前提が崩れた場合はadaptiveへ再判定し、replay-logに記録）。
3. **adapt**: 16-dev側の新構造を正とし、forkの契約（各bridge groupのowner ADR/specの受入条件）を満たす形で再表現する。重点3点: `LauncherModel.kt` への `OrganizerModelReloadAdapter` 接続、`GridSizeMigrationDBController/Logic` への `GridMigration*` 接続（transaction ownership [spec 118](../../specs/118-sqlite-migration-transaction-audit/spec.md)維持）、`MainThreadInitializedObject` のonPostInit代替hook（Issue #14要件）。
4. **drop**: 該当なし（Phase 0確定）。16-dev代替を発見した場合は停止してPhase 0 assessment/ADR-0018を改訂する。
5. Nova restore 2挙動（#522 §4採用port）: `NovaBackupConverter.kt` のC単位で、警告/toggleと座標丸め・rows補償を一組で16-dev構造へ実装する。converter通常入口のfixture境界値oracle（fractional四フィールド / smartspace ON-OFF / clamp・skip。**T8**）を同時に追加する。
6. 解消で挙動変更が必要になった場合: その単位で停止し、replay-logへ「ADR/spec改訂要求」を記録する。改訂accepted後に再開する。

## 5. 実行の記録（replay-log）

`specs/516-16-rebase-phase2/replay-log.md` を正本として、単位ごとに次を追記する: 単位番号/PR番号、元commit SHA、分類（A/B/C）、結果（適用/conflict解消内容/skip理由）、挙動変更の有無。sessionをまたぐ作業はこのlogでhandoffする（[workflow Agent handoff](../../docs/project/github-workflow.md)）。

## 6. migration / rollback

- Launcher DB schemaはfork SCHEMA_VERSION 33を維持する（[#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md)。upstream 32→32はupstream比較に限る）。schema変更を要求する解消はADR-0004と同様の受入を要求する。
- rollback: rebase branchは作業branchであり、破棄はbranch削除でよい（main不変）。baseline切替のrollback点はADR-0018 Decision 5（Phase 4）。

## 7. 検証（AC-4 gate。全replay完了後）

| gate | command / 方法 |
|---|---|
| G1 build/format | `./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew spotlessCheck` |
| G2 unit | organizer unit test gate（[quality-strategy](../../docs/engineering/quality-strategy.md)のcommand） |
| G3 surface ownership | **candidate ownership inventory**（旧105 inventoryの各ownerを新pathへ写像した未採択inventory。`LauncherModel.java`→`.kt`、grid migration分割、onPostInit代替で新たに触れるpathを含む）を `--baseline-file` で渡して計測し、**全non-excluded production差分がowner分類され、増分・移動がreview済みであること** を合格条件とする。旧inventoryの `counted_files == 105` の一致は合格条件から外す（adapt後のpath移動でfail-closedするため）。accepted baselineの正式な再採択はPhase 4に残し、candidateをそのまま自動採択しない（Phase 4で最終headを再計測する） |
| G4 data互換（[#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md) T1〜T9の実行表） | 下表 |
| G5 CI | PRのCI merge gate（final-status）。本spec/plan自体はdocs-onlyだが、replay結果のPRは高リスクgateの対象 |

### G4: T1〜T9実行表（owner / 再利用or追加 / lane / evidence。出典: #522 assessment §test表）

| T | ownerと対象 | 再利用/追加 | lane / routing | evidence |
|---|---|---|---|---|
| T1 schema33/列維持 | DatabaseHelperSchema33Test、DowngradeSchema33Test、InactiveGridDbNormalizationTest | 再利用 | surface_db_schema、db-migration API36 Conditional | fresh33/default1、32→33 UNKNOWN、失敗rollback、33→32→33 row保持、inactive32/source33 lock |
| T2 transaction ownership | MigrationTransactionOwnershipTest、Schema32RollbackBinaryTest、NestedTransactionTest | 再利用 | db-migration API36 + production-input API35 Conditional | legacy upgrade/downgrade部分失敗のwipe commitと旧binary failureの33残存の区別 |
| T3 grid migration分割 | GridMigrationSuccessTest/FailureTest | 再利用・**両entry（tryMigrateDB/attemptMigrateDb）へ拡張** | surface_db_schema/layout_write、db-migration Conditional | 各operation前後throw、commit-close、digest破損、process restart。両entryのgreen必須 |
| T4 restore lifecycle | `RestoreLeaseSerializationTest`（**shared-writer lane**）、`ModelWriterTransactionReentryTest`（**shared-writer lane**）、`RestoreProfileRemapTest`（**db-migration lane**） | 再利用 + **追加（performRestore成功経路の実DB assert。未実装）** | 追加oracle `RestoreDbTaskSuccessPathTest` は **db-migration lane filterへ追加**。layout_write/backup_restore分類、追加採用時にfilter/portfolio/map更新 | profile remap/消滅削除、widget ID復元、surviving lock |
| T5 ZIP artifact | backup/recovery既存tests | 再利用 + **追加（real ZIP32/33 fixture。未実装）** | 追加oracle `RealZipRestoreE2E` は **db-migration lane filterへ追加**（restore data pathを実fileで実行する既存emulator laneであり、failure-time captureを共有する。self-trigger surfaceは同test fileのportfolio map登録）。unit部分はorganizer-unit-tests gate（Permanent） | row/lock/prefs・epoch、cold-start READY/Pristine |
| T6 recovery codec | RecoveryRecordCodecTest等 | 再利用 | unit Permanent + reservation-recovery Conditional | schema3 checksum、chunk欠損、NotRestorable |
| T7 prefs | LauncherPrefsCommitTest、NovaRestoreGridApplicationTest等 | 再利用 + **追加・拡張（未実装）** | db_schema/backup_restore既存lane候補。key変換は最低層。**routing変更は実装PRで ci_portfolio_map.yml/portfolioを同期** | legacy XML conflict/missing key、listener/cache readback |
| T8 Nova 2挙動 | NovaRestoreGridApplicationTest + #299 A/B群 | 再利用 + **追加（converter境界fixture。未実装）** | backup_restore restore-capture API36 Conditional（A/B間force-stop維持） | fractional四フィールド、smartspace ON/OFF、clamp/skip、warning/toggle表示 |
| T9 実APK F/R | 15↔16実APK roundtrip | **追加の互換実証（on-demand）** | Permanent laneは増やさない。API36 emulator + API37 Pixel 9a実機。Phase 4 owner closure | R→16→R→16同一data、DB/prefs/recovery独立比較 |

**G4実行の固定情報**（[#522 assessment §6](../../docs/assessment/issue-522-rebase-data-compatibility.md)の要求）:

- **最終source SHAの記録手順**: replay完了時、`replay-log.md` 冒頭のG4 evidence headerへ **`REBASE_HEAD=<full 40桁SHA>`** を記録し、**その記録後にG4を開始する**。G4の全実行結果（command、lane run URL、成否）はREBASE_HEADと対にしてreplay-logへ記録する。
- **command/script + class filter**（現在の実入口。実行時のci.yml/scriptが正本）:
  - T1（schema33/列維持）: db-migration lane（ci.yml `organizer-instrumentation-db-migration-tests`）— `bash tools/ci/run-emulator-command-with-failure-capture.sh … -- ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…` のfilterに既存の `DatabaseHelperSchema33Test,DowngradeSchema33Test,InactiveGridDbNormalizationTest` が含まれる。
  - T2（transaction ownership）: 同db-migration lane filterの `MigrationTransactionOwnershipTest,Schema32RollbackBinaryTest` + shared-writer lane（ci.yml）の `NestedTransactionTest`。
  - T3（grid migration分割）: 同db-migration lane filterの `GridMigrationSuccessTest,GridMigrationFailureTest`（tryMigrateDB/attemptMigrateDb両entryのgreen必須）。
  - T4: 追加oracle `RestoreDbTaskSuccessPathTest` を **db-migration lane filterへ追加**。既存oracleのlane: `RestoreLeaseSerializationTest`・`ModelWriterTransactionReentryTest` = **shared-writer lane**、`RestoreProfileRemapTest` = **db-migration lane**（現行ci.yml filterどおり）。
  - T5: 追加oracle `RealZipRestoreE2E` を **db-migration lane filterへ追加**（一意のbinding。backup_restore laneへの拡張はしない。理由とself-trigger surfaceはT5行のとおり）。unit部分はorganizer-unit-tests gate。
  - T6: RecoveryRecordCodecTest等はorganizer-unit-tests gate（Permanent）+ 既存reservation-recovery Conditional lane。
  - T7: 追加oracle `PrefsLegacyXmlMigrationTest` のJVM部分を **organizer-unit-tests filterへ追加**、device依存readback部分を **db-migration lane filterへ追加**。既存oracleのlane: `LauncherPrefsCommitTest`・`DeckRetirementMigrationInstrumentationTest` = **db-migration lane**、`NovaRestoreGridApplicationTest` = **restore-capture script（per-class）**。
  - T8: 追加oracle `NovaConverterBoundaryTest` を `tools/ci/run-restore-capture-instrumentation.sh` の **per-class独立invocationに1行追加**（#299契約のclass毎独立起動・A/B間force-stopを崩さない）+ 既存Nova A/B群を再利用。
  - T9: on-demand release-compatibility evidence（既存connected invocationのみでは不十分。API 36 emulator + Pixel 9a / API 37実機。closureはPhase 4 owner decision）。
- **fixture**: T1 `legacy32/fresh33` fixture群（既存）。T2 旧schema32 binary fixture（既存）。T3 grid migration fixture（fast/general、target既存/新規）。T4 実DB+profile remap fixture。T5 real ZIP32/33 fixture（新規作成）。T8 fractional四フィールド・subgrid・smartspace ON/OFF・clamp/skip fixture（新規作成）。
- **追加oracle名と重複境界**: T4 `RestoreDbTaskSuccessPathTest`（責務: performRestore成功入口の実DB assert。既存3 classはlease/remap/reentryを所有し重複しない）。T5 `RealZipRestoreE2E`（real ZIP復元とcold-start状態。既存critical-section/mutex testsは並行制御のみ）。T7 `PrefsLegacyXmlMigrationTest`（legacy XML→DataStore key変換。既存commit testはwrite pathのみ）。T8 `NovaConverterBoundaryTest`（converter通常入口の境界fixture。既存A/B群はUI接続・process分離の確認）。
- **routing同期**: T4/T5/T7/T8のfilter追加は実装PRで `tools/repo-contract/ci_portfolio_map.yml` / [ci-test-portfolio](../../docs/engineering/ci-test-portfolio.md) を同時同期し、test file自身が対象laneをself-triggerできることを `compute_ci_gating.py` のmapで確認する（新規恒久laneは増やさない）。
- **T4/T5/T7/T8は追加oracleの実装がgateの前提**である。T9はPhase 2時点の証跡とcutover前の再確認を区別し、closureはPhase 4 owner decision（ADR-0018 Decision 5）。

## 8. 実行順のまとめ

1. 本plan/specのreview・accepted（本PR）
2. **replay queueは300単位のfirst-parent時系列を唯一の実行順とする**（並べ替えは行わない。A/B/Cは各単位の分類ラベルであり、処理規則の対応表である。replay-logの単位番号はこの単一時系列indexで固定する）
3. 時系列に沿ってcherry-pickし、C分類の単位は§4の手順で解消。submodule pin判断点（§2）で停止→ADR改訂→review/accept→再開
4. G1〜G5 gate（§7。T4/T5/T7/T8の追加oracle実装を含む）→ PR（`Refs #516`、`Refs #532`。Phase 3検証はEpic側で継続のため、本PRは#532の終了条件が満たされた時点で `Closes #532` となる）
