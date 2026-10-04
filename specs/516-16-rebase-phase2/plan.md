# Plan: 16-dev rebase実行（Epic #516 Phase 2 / Issue #532）

> Status: accepted revision 2（2026-10-04。PR #534 reviewでblocking findingなし・「accepted へ遷移可」を確認（[review](https://github.com/nunu1733/NunuLauncher/pull/534#issuecomment-5979154660)。head `d1e1811900277f2fd5f105075b1cef1dd7c1723a` を確認）。受入は本PR #534のmergeで完了する）。revision 1はPR #533でaccepted/merge済み（main `b759506e28f8922a2f4a02a7fbb83360b710b750`）。方針1の選択は [ADR-0018 Decision 9](../../docs/adr/0018-lawnchair-16-rebase.md) に固定。
> Risk tier: H（[spec.md](./spec.md)参照）
> 対象revision: main `38262fb74d144f4655dfbf01c0084e44c86560be`、anchor upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`

## 1. 現在codeの根拠

- fork変更の全量: baseline `505dbc40` 以降の対象main first-parent 300単位（親数でmerge commit 234 / single-parent commit 66）。PR由来/直接commitの識別はsource→replay対応表で確認する。
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

上表は着手時の分類見積り。停止中のreplay-logはCをconflict発生で分類しており同じ集計ではない。再開時にS0でsource diff×adapt pathの定義へ揃え、textual conflict件数とは別に記録する。300というqueue件数と全順序は変更しない。

C群の代表（replay-logへ全量を記録）: PR #79（deck退役本体、95 files × 15 adapt paths）、#341/#498（user categories / destination policy）、#481（edit undo、`Folder.java`/`ModelWriter.java`）、#476/#486（edit surface / home entry）、#75/#77（schema・grid migration）、#160/#314/#319（model reload/LoaderTask）、#464（benchmark、build.gradle/settings.gradle）。`LauncherModel.java`→`.kt` 化・`GridSizeMigrationUtil` 分割・`MainThreadInitializedObject` onPostInit削除の3点（Phase 0 §5「要対応の3点」）がC群のsemantic解消の中心である。

## 4. conflict解消手順

1. conflictが発生したら、当該pathのdisposition（[Phase 0 §5](../../docs/assessment/issue-516-16-rebase-phase0-research.md)）を確認する。
2. **keep**: fork側内容をそのまま復元する（patch競合なしの前提が崩れた場合はadaptiveへ再判定し、replay-logに記録）。
3. **adapt**: 16-dev側の新構造を正とし、forkの契約（各bridge groupのowner ADR/specの受入条件）を満たす形で再表現する。重点3点: `LauncherModel.kt` への `OrganizerModelReloadAdapter` 接続、`GridSizeMigrationDBController/Logic` への `GridMigration*` 接続（transaction ownership [spec 118](../../specs/118-sqlite-migration-transaction-audit/spec.md)維持）、`MainThreadInitializedObject` のonPostInit代替hook（Issue #14要件）。
4. **drop**: 該当なし（Phase 0確定）。16-dev代替を発見した場合は停止してPhase 0 assessment/ADR-0018を改訂する。
5. Nova restore 2挙動（#522 §4採用port）: `NovaBackupConverter.kt` のC単位で、警告/toggleと座標丸め・rows補償を一組で16-dev構造へ実装する。converter通常入口のfixture境界値oracle（fractional四フィールド / smartspace ON-OFF / clamp・skip。**T8**）を同時に追加する。
6. 解消で挙動変更が必要になった場合: その単位で停止し、replay-logへ「ADR/spec改訂要求」を記録する。改訂accepted後に再開する。

### 4.1 G1停止からの再開（revision 2）

方針の正本はADR-0018 Decision 9、固定source比較は [model assessment](../../docs/assessment/issue-532-model-architecture-decision.md)。保持する契約は既存spec/ADRが所有し、本節で緩和しない。モデル全体の再選択は終了している。

| stage | 変更path / seam | 終了条件 |
|---|---|---|
| S0 checkpoint・正本同期 | 停止head `88af5218ce` をfull SHAで固定した保存branch/refを作成。実装branchはそのheadから分岐し、300件replayと追加10件を保護。acceptedになった本判断文書・PR #533 spec/planをrebaseへ同期 | 元300件→replayed commitの一対一対応、A/B/Cとconflictの分離、WIP path/hunkの採否表、candidate ownership inventoryの初版をreplay-logへ記録。ログの「adaptはPhase 3」を「Phase 2未完」へ訂正。追加修復のattributionも残す |
| S1 anchorモデル構造への統一 | `LauncherAppState.kt` / `LauncherModel.kt` / `BgDataModel.kt` / `ModelInitializer.kt`、`LoaderTask`/binder assisted factory、WorkspaceData/repository、`src/.../dagger`・`quickstep/dagger`・preview接続 | 旧Java duplicateと旧model/data providerを除き、modelは同一DI instanceを使う。fork差分を未移植のままG1をgreenにするために削除しない。S2/S3の必須移植と同じ作業branchで進め、単独merge/完了宣言しない |
| S2 writer・reload・startup契約の移植 | `ModelWriter.java` / `ModelDbController.java` / `LoaderTask.java` / `LauncherModel.kt`、`OrganizerModelReloadAdapter.java` / `ModelProjectionCodec.kt`、homeedit caller、LawnchairAppの生成後hook | MODEL_WRITER admissionと通常loader defer、exact organizer loaderだけのtoken能力、commit+close後のqueued完了・cancel/supersession・snapshot、直接編集/Undo/配置先のstage-2検証とatomic DB/model更新を維持。main/default model生成後のstartupを一度だけ実行し、preview/secondary processへ漏らさない |
| S3 schema・grid・restoreの移植 | `DatabaseHelper.java` / `DbDowngradeHelper.java`、`GridSizeMigrationDBController.java` / `GridSizeMigrationLogic.kt`、`ModelDbController`の両migration entry、`RestoreDbTask.java`、Nova converter/prefs | schema33/lock、spec 118のframework transaction所有、grid journal/digest/commit-close/process restart、restore getDb前lease/reentry、Nova二挙動を維持。T3両entryのcoverage、T4/T5/T7/T8追加oracleを完成させる |
| S4 検証・review packet | G1〜G5、candidate ownership inventory、replay-log、CI map/portfolio同期 | 全gateの結果をexact source headに結び付ける。全non-excluded production差分をreplayまたはS1〜S3のowner付きadaptへattribution。高リスク独立audit後にPhase 3へ渡す。T9 cutover closureはPhase 4 |

S1〜S3はreview可能なappend commitに分けるが、コンパイル依存があるため同じ実装branchで直列に進める。WIP 253 pathを一括revertしない。anchor fileを選び直す前にF側のfork delta・WIPの有効な適応を保存し、path/hunkごとに移植先と残存契約を対応付ける。`git reset --hard` / force-pushで300件履歴や他作業を消さない。

**具体的な実装境界**:

- factoryのassisted入力へrequest/capabilityを明示的に運び、通常/preview loaderはtokenlessとする。DIで全loaderへactive organizer tokenを供給しない。完了はLoad IDやbind callbackだけで代替しない。
- `WorkspaceData` のversion/modification IDをorganizerの `RevisionId` と同一視しない。snapshot codecは既存projection契約を維持し、writerはanchorの `addItems/updateItems/removeItem` 等の通知を通してmodel/repositoryを同期する。
- CRUDはanchorのfavorites固定APIへcallerをadaptする。別table操作が必要なcallsiteは用途・owner・leaseを個別に記録し、旧全table CRUDやraw DB書込みを無条件に復活させない。getDb/file rename/table copyもmutation admissionの対象として棚卸しする。
- old onPostInitをglobal singleton utilityへ戻す代わりに、model生成完了後の既存composition/initialization経路へ接続する。Dagger component構築中に `LauncherAppState.getInstance()` を再入させない。
- spec 118のSQLiteOpenHelper transaction所有とspec 14/coordinatorのprocess leaseを区別して維持する。「nested transactionを増やせば安全」とは扱わない。

**契約別のfocused確認**（新しい恒久laneを作らない）:

| 移植リスク | primary regression owner / 現行入口 |
|---|---|
| loader早期完了・stale token・誤thread | `OrganizerReloadCompletionOrderingTest` / `OrganizerReloadSupersessionTest` / `RestoreLeaseDeferredLoaderThreadAffinityTest`。shared-writer laneの実loader境界を再利用 |
| defer中stale・model先行変更・folder/Undo失敗 | `DirectEditModelWriterTest` / `DirectEditUndoModelWriterTest` / `InstallDestinationModelWriterTest` / `DirectEditWriteShapeTest` / `ModelWriterTransactionReentryTest`。shared-writer lane。純validationは既存homeedit JVM test |
| schema/grid/restore/prefs/Nova | §7のT1〜T8の既存ownerと追加oracle。T3は両entryへ拡張。既存成功assert/失敗注入を新model構造でも保ち、compiled-only fixtureへ置換しない |

`test-audit` のfocused discoveryで既存ownerと現行ci.yml class filterを照合済み。今回test/CI routingは変更しない。実装時にtest本文・production callerを再照合して最小の忠実な境界を選び、追加oracleは§7のfilter/map/portfolioへ同時登録する。Android実DB・MODEL_EXECUTOR・process/lifecycleのリスクはJVM mockだけでは閉じない。

G1はS2/S3までの必須契約を残した状態で成功させる。focused testの後にG2〜G5へ進む。G4開始前に最終production sourceのfull SHAを `REBASE_HEAD` に記録し、後続source変更で影響するgateを無効化して再検証する。DI生成の同一性、default-process startup、preview隔離も実装review packetの明示的確認項目とする。

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

停止head以後は§4.1のS0→S4を実行する。方針1とAPI追従の範囲内なら再度1/2/ハイブリッドの選択を要求しない。新たな契約変更やschema/anchor/disposition変更だけは§4.6とADR-0018に従う。

## Change history

- 2026-10-04: revision 1 accepted、PR #533 merge。
- 2026-10-04: revision 2 proposed、#532停止headを固定し、方針1の実装stage・WIP採否・モデル/lease/transactionの境界・既存testのfocused確認を具体化。G1〜G5/T1〜T9の受入条件は維持。queueの誤ったmerge/direct内訳を実親数234/66へ訂正。
- 2026-10-04: revision 2、**acceptedへ遷移**。PR #534 review（[comment](https://github.com/nunu1733/NunuLauncher/pull/534#issuecomment-5979154660)）でblocking findingなしを確認。受入は本PR #534のmergeで完了する。
