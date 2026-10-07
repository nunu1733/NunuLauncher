---
status: accepted
---

# Lawnchair 16 rebase — 採用baseline・移行方式・rollback（ADR-0018）

> Status: Accepted revision 9（2026-10-08。revision 6はPR #534 review（[comment](https://github.com/nunu1733/NunuLauncher/pull/534#issuecomment-5979154660)）でaccept、merge済み。revision 7はDecision 9の実装境界明確化2点（activity base class、#59 commit seam）を追加し、Phase 2実装PRのreviewで確定。revision 8は#524のruntime検証結果とowner decision（maxSdk 37引き上げ保留）をDecision 7へ反映。revision 9は#545のcompat実装とruntime検証結果（bind path修復成立・thumbnail/G3面は未解決のためmaxSdk 37引き上げは引き続き保留）をDecision 7へ反映。受入は実装PR #550のreviewで確定）。revision 5までのaccepted正本はmain `b759506e28f8922a2f4a02a7fbb83360b710b750`。
> Date: 2026-10-04
> 対応: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) / Phase 0 [#519](https://github.com/nunu1733/NunuLauncher/issues/519)
> 出典: [#442 最終結論C](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5863040551)（2026-09-28）、[upstream-strategy.md](../engineering/upstream-strategy.md) Upgrade policy 5比較軸、Phase 0計測 [issue-516 assessment](../assessment/issue-516-16-rebase-phase0-research.md)
> 置換関係: なし（新規判断）。baseline運用の現行正本 [upstream-strategy.md](../engineering/upstream-strategy.md) Current state は、Phase 4（baseline切替完了時）に本ADRへ合わせて更新する。

## Context

#442の最終判断C（2026-09-28）により、Lawnchair 15 beta 3 baseline（`505dbc40`）から16系へのrebaseが製品要件となった。15系にはbaseline以降の同期先がなく、`16-dev`のみが進行先である。16-devはmerge-base `b011d84c` 以後7,377 commits、`--no-renames` で5,166 files +532,729/−185,654の差分であり、build構成の再編（AGP 9.4.1、vendored `wmshell`、新module群、新submodule）とLauncher3 model周りの構造変化（`LauncherModel.kt`化、grid migration util分割、`MainThreadInitializedObject` の `Overrides` 機構削除）を含む。一方で**upstream旧baselineと候補の比較では** `SCHEMA_VERSION` 32と `downgrade_schema.json` は不変である。現行forkはADR-0004のlock列を持つschema33であり、16系forkにもその形状とmigration契約を保持する（[#522 assessment](../assessment/issue-522-rebase-data-compatibility.md)）。Phase 0計測の詳細は [issue-516 assessment](../assessment/issue-516-16-rebase-phase0-research.md) に記録した。

本ADRは、rebase着手前に「採用upstream SHA・移行方式・branch方針・rollback点・残すbridgeの所有者」を確定し、[upstream-strategy.md](../engineering/upstream-strategy.md) Upgrade policyの5比較軸（product value/Android version support、Launcher3 model/schema/event、Deck retirement後を含むpatch再適用cost、build/toolchain/device test matrix、rollback可能なrelease/migration path）を満たす判断を固定する。

## Decision

1. **採用upstream baselineを `16-dev` commit `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（2026-10-02）に固定する。** Phase 2以後の計測・実装・検証はこのSHAへanchorする。Phase 2着手時に16-devが前進している場合、anchor刷新は本ADRの改訂（差分分類とdispositionの差分更新を含む）によってのみ行い、自動追従しない。
2. **移行方式はfork commitのreplay型rebase**（`git rebase --onto` によるupstream ancestry維持）を専用branchで実施する。baseline側のみに存在するupstream 33 commitsは原則運ばない。不運搬の妥当性は棚卸し済みである（[Phase 0 assessment §5.1](../assessment/issue-516-16-rebase-phase0-research.md): build/docs/翻訳15件と16-dev同等実装確認済み15件は不運搬で挙動損失なし）。**例外として、16-devに同等が存在しない3件を次のように扱う。**
   - `9b48473c`（subgrid検出/警告 **＋ cellX/cellY/spanX/spanYの最近傍丸めrestore変換**）と `53a2092541`（smartspace conflict toggle **＋ rows+1補償・cellY shift・bounds clamp/skip**）は**両方採用・保持する**（[#522 assessment §4](../assessment/issue-522-rebase-data-compatibility.md#4-nova-baseline-2-commitの採否rebase前の決定)）。警告/toggleとデータ変換を一組で移植する。Phase 2はこの確定結果を実装・検証し、UIだけをportして変換を欠落させない。
   - icon shadow修正を含むsubmodule pin（現main `6a11ef76` vs 16-dev `7d9e92bd`。16-dev pinは当該修正が欠落）のpin選択は、backup/restore契約と分離し、Phase 2 planでの判断対象とする。判断までrebase差分に入れず、結果をassessment/ADR改訂として記録する。
3. **branch方針**: rebase作業は専用branch（`issue-516-rebase-16-dev` 系）で行い、`main` は15 baselineのまま維持する。移行期間（Phase 2開始〜Phase 4 baseline切替完了）はEpic #516の「rebase前の変更境界」を適用し、`com.android.launcher3` / SystemUI等への新規bridge追加は原則停止する。切替はrebase branchの全検証完了後、単一のmergeで行う。
4. **bridgeの所有者とdisposition**: 再適用対象はbridge inventory 105 path（fork追加49、upstream無変更8、upstream変更45、upstream削除3。anchor main `8af117b6fc`）であり、所有者は各bridge groupの既存owner（deck-retirement: ADR-0006、model-reload/transaction gates: #60監査と#114、layout-schema/recovery: ADR-0003/0004・#118、organizer-ui: #38/#52/#99、homeedit系: ADR-0013/0014・#448〜#450、organizer-home-entry: #452、new-app-destination: ADR-0015・#497、fork-platform-preexisting: fork baseline由来）がPhase 2でも継続する。**dispositionはassessment §5のpath単位で確定済み: keep 57 / adapt 48 / drop 0**（fork追加・upstream無変更pathはpatch競合なしのkeep。ただしmodel-reloadのfork追加3 pathとlayout-schemaのfork追加4 pathは、接続先の構造変化をadapt側pathで再表現する前提のkeepである）。Phase 2はこの確定済みdispositionに従ってconflict解消を行い、代替発見時はassessment/ADR改訂で変更する。要再表現の3点（`LauncherModel.kt` 化への接続再表現、grid migration分割への追従、`onPostInit` hookの再表現）はPhase 2の重点項目とする。
5. **rollback点**: baseline切替の **cutover merge直前の旧 `main` head** を `pre-lawnchair16-<date>` tagとして固定する（cutover merge commit自身をtag化しない）。rollback操作は「cutover mergeのrevert」または「旧baselineからのrelease再発行」とし、protected `main` へのforce-resetは行わない。upstream同士のschema32不変は補助材料にすぎず、切替対象のforkはschema33を維持する。戻し先はtag化した15系forkであり、歴史的schema32 binaryへのdowngradeと区別する。DB形状だけでpreferences・recoveryの可逆性を主張しない（[#522 assessment §2/§6](../assessment/issue-522-rebase-data-compatibility.md)の条件とT1〜T9）。16→15 APK/data downgradeの実証または「実証しない場合の検証方法と残存リスクのowner decision」をPhase 4で完了するまでbaseline切替を完了とみなさない。
6. **build/toolchain**: compileSdk 37（minor 2）/ buildTools 37.0.0 / targetSdk 37 / minSdk 26 / AGP 9.4.1 / Kotlin 2.4.20 を16-dev側の選択として採用する。vendored `wmshell` 等の新moduleと新submodule `platform_frameworks_libs_systemui`（branch 16-dev）を取り込む。JDK 21継続可否はPhase 2のbuild gateで確認し、building guide更新はPhase 4で行う。
7. **device test matrix**: API 36 CI lanes（現行構成）に加え、保守者実機 Pixel 9a / API 37（#442 §5.2と同一端末）での代表日常操作をPhase 3の検証対象とする。quickstep advertised support rangeは **35..36を維持する**（baselineからの変化29..35→35..36をmatrix上に明記）。API 29〜34端末のサポート境界は #520 の結論を受け [#524](https://github.com/nunu1733/NunuLauncher/issues/524) でowner decisionとして確定済み（revision 8）: **min 35を維持し、API 29〜34のprovider対応は復活しない**（upstream PR #7262のrelease意図、QuickSwitch companion appのAndroid 14+非対応というエコシステム制約）。**maxSdk 37への引き上げは引き続き保留とする（revision 9時点）**: #524のruntime検証（2026-10-07）でAPI 37のprovider bind path破壊（`IWindowManager.createInputConsumer` hidden API破壊）が実測され保留としていた。[#545](https://github.com/nunu1733/NunuLauncher/issues/545)（2026-10-07〜08）で **7件の破壊をcompat修正**（createInputConsumer reflection呼出し、Taskbar throttle flag guard、`taskbar_phone_size` literal pin、KeyButtonRipple flags guard、getTaskThumbnail/takeTaskThumbnailのTaskSnapshotManager経由reflection、wrapToBitmap pixel取得）により解消し、provider構成matrix（`docs/assessment/545-api37-provider-fix-evidence/`）でbind完了・overview起動・task切替がcrash-freeで到達することまで実証した。ただし **thumbnail bitmap生成（黒fallback継続）とrecents遷移のparcel schema（`SystemUiProxy` 系binder callbackの `BadParcelableException`。shell→launcher遷移が完結しない）が未解決**であり、検証に先行する宣言の禁止により引き上げは引き続き保留する（advertised rangeは35..36を維持）。残存2系統はvendor済みAIDL/shell surfaceの37 schema対応という規模であり、別Issueへ分離する（#545に記録）。provider構成環境（`config_recentsComponentName` をLawnchairへ向けるQuickSwitch/RRO構成）での動作検証は非root実機では実施できないため、root化API 37/36 emulator＋variant別RRO overlay＋priv-app配置のmatrix（#524実装PR証跡 `docs/assessment/524-api37-quickstep-evidence/`）を主証跡とし、実機owner確認は通常構成での無影響確認を担当する。実機でのprovider構成確認はPhase 3実機matrixのowner手順とする。
8. **targetSdk 37のbehavior changes対応とAPI 37 quickstep対応は、Phase 1子Issue（[#520](https://github.com/nunu1733/NunuLauncher/issues/520) / [#521](https://github.com/nunu1733/NunuLauncher/issues/521) / [#522](https://github.com/nunu1733/NunuLauncher/issues/522)。起票済み）の結論を待ってrebase後の統合で扱い、rebase差分に混入させない。** 本ADRは「16-dev側のSDK値に追随する」ことのみを確定し、behavior change対応の個別判断は確定しない。

9. **Phase 2のモデル層は方針1（16-dev anchor構造を正とする）へ統一する。** コード比較と不採用理由は [#532 model assessment](../assessment/issue-532-model-architecture-decision.md) に記録する。
   - `LauncherAppState.kt` / `LauncherModel.kt` / `BgDataModel.kt`、Daggerの生成・寿命・assisted factory、WorkspaceData/repositoryの更新入口、grid migrationの分割、preview/quickstep graphはanchorを土台とする。fork-owned coordinator・organizer・homeeditの契約をその既存seamへ移植する。
   - process-wide lease/spec 14のadmissionとexact loader token、spec 150/152のcommit+close後の相関完了とsnapshot、ADR-0013/0015のadmission内再検証・1 transaction・model更新・Undo/配置先、ADR-0004のschema33/lock、spec 118のSQLite transaction所有、restore/grid journalと#522の採用portを保持する。保持するのは契約であり、旧Javaファイルや旧mutable collectionの形ではない。
   - anchorの `INSTANCE/getInstance` facadeは同じDI instanceを返す入口として利用できる。恒久的な二重model、旧Javaモデルを生成するmodel/BgDataModel provider、model同一性・transaction能力を暗黙に変えるbridgeを採用しない。fork-owned非model serviceの小さなDI bindingは既存のowner・scope・初期化順を説明できる場合に限り個別reviewする。
   - 停止head `88af5218cea51b8556b9935b35d3640714b96111` と300件replay履歴を保護する。WIPを一括採択・一括revertせず、新しいappend commitでanchor構造とfork契約を整合させる。path単位のownerと採否を記録し、semantic adaptはPhase 2内で完成させる。
   - この選択の技術review/accept後、[plan §4.1](../../specs/516-16-rebase-phase2/plan.md) に従って再開する。API/型/生成経路の追従はこの方針内で解決する。既存契約の緩和、schema変更、anchor刷新、disposition変更が必要なときだけ正本の追加判断へ戻す。G1の成功だけでPhase 2完了とはせずG1〜G5・高リスクauditを維持する。
   - **実装境界の明確化（G4実行で確定、revision 7）**: ①activity base class も anchorを正とする（`BaseActivity` はframework `Activity` 継承）。forkのandroidx `viewModels()` 契約は `LawnchairLauncher` への `ViewModelStoreOwner`/`HasDefaultViewModelProviderFactory` 実装で移植する（基底クラスのandroidx化はしない）。②#59の「集約commit boolean」契約はanchor `putSync` seam上で表現できないため、「commit後readback検証」を正式な後継とする（[spec 59](../../specs/59-preserve-source-grid-migration-failure/spec.md) 側に注記）。旧boolean契約の復活を要求しない。

## Alternatives considered

### Phase 2で旧15系Javaモデルを正とする / 恒久的二重モデル

不採用。anchorのDI/factory/WorkspaceData/preview/quickstep依存側へ旧構造の互換性を広げ、将来の同期差分を増やす。二重モデルはlayout authority・model寿命・能力tokenの同一性を追加の同期契約にしてしまう。anchorにも既存accessor facadeがあるため、旧呼出し入口を使う目的で旧モデル全体を残す必要はない。固定sourceの比較は [#532 assessment §2〜3](../assessment/issue-532-model-architecture-decision.md) を参照する。

### merge方式による統合（`git merge 16-dev`）

不採用。upstream自体はmerge主体の統合であるが、forkのpatch surfaceはbridge単位で監査・採択されており（[patch-surface baseline](../assessment/upstream-patch-surface-baseline.md)）、mergeではpatchの所在とconflict attributionが履歴上追跡しにくい。Epic #516 Phase 2が「upstream ancestryを維持したrebase」を規定している。

### squash import / 新checkout

不採用。upstream-strategy.md が禁止する「Git履歴を失うsource copy」であり、fork ancestryとbridge単位の監査を失う。

### 15 baselineのまま進む

不採用。#442でC-(3)が製品要件として確定済み（2026-09-28）。15系には同期先が存在しない。

## Consequences

- Phase 2は、固定SHAへのanchor・replay型rebase・bridge disposition（keep/adapt）・schema不変性の確認を前提に計画できる。
- Phase 1子Issue（API 37 quickstep、targetSdk 37 behavior changes、migration/backup互換）の結論がrebase統合の前提契約となり、rebase中のconflict解消で挙動変更が必要な場合はaccepted spec/ADRへ戻す。
- 本ADRがacceptedになるまでproduction rebaseに着手しない（Epic Phase 0 exit）。
- baseline切替後、`upstream-strategy.md` のbaseline情報・patch-surface baselineの再採択・building guide・CI/device matrixの正本更新がPhase 4で要求される。

## Change history

- 2026-10-04: 起草（proposed）。Phase 0計測（[issue-516 assessment](../assessment/issue-516-16-rebase-phase0-research.md)）に基づく。
- 2026-10-04（revision 2）: PR #523 review round 1指摘対応。Decision 2にbaseline側33 commitsの棚卸し結論（30件不運搬可、3件port/pin判断）を反映、Decision 4にpath単位で確定したdispositionとdrop判定の根拠を反映、Decision 5のrollback点を「cutover merge直前の旧main headへのtag固定＋rollback操作の明示」へ修正、Decision 7/8を#520/#521/#522の起票済み実態とadvertised range変化の表現へ同期。
- 2026-10-04（revision 3）: PR #523 review round 2指摘対応。Decision 2のNova restore 2件（`9b48473c` / `53a2092541`）を「UI＋restoreデータ変換契約」の挙動単位へ展開し、採否確定を#522（Phase 1、rebase前）へ移管（icon shadow pinのみPhase 2 plan判断として分離）。Decision 4のdisposition表記をkeep 57 / adapt 48 / drop 0のpath単位内訳へ統一。
- 2026-10-04（revision 4）: **acceptedへ遷移**。PR #523 review round 3（[review](https://github.com/nunu1733/NunuLauncher/pull/523#issuecomment-5975761227)）でblocking findingなし・「ADR-0018は `proposed` → `accepted` へ遷移してよい」の確認を受けた。受入は本PR #523のmergeで完了する。

- 2026-10-04（revision 5、#522）: Nova二件のUI/データ変換を共に保持と確定。schema32不変の比較範囲をupstream B/Uに限定し、fork33維持・cutover直前15 forkへのrollbackと歴史的32 binaryの境界、preferences/recoveryを含む実証条件をDecision 2/5へ反映。既存永続化契約は変更しない。
- 2026-10-04（revision 6 proposed、#532）: G1停止報告の統一方針をDecision 9として具体化。方針1を選択し、旧モデルprovider/恒久二重モデルを不採用、fork契約の移植境界・WIP保護・Phase 2内adaptを明示。既存specの観測可能な契約・G1〜G5・Phase 4 cutover条件は変更しない。実装再開は本revisionとplan revision 2の技術review/accept後。
- 2026-10-04（revision 6、**acceptedへ遷移**）: PR #534 review（[comment](https://github.com/nunu1733/NunuLauncher/pull/534#issuecomment-5979154660)）でblocking findingなし・「ADR-0018 revision 6 / Phase 2 plan revision 2 は accepted へ遷移可」を確認。受入は本PR #534のmergeで完了する。
- 2026-10-04（revision 7 proposed、#532 G4実行）: Decision 9に実装境界の明確化を2点追加（activity base class = anchor正、#59 commit boolean → readback検証への後継）。G4のboot blocker（androidx cast）と#59 seam消失のowner決定として記録。本Phase 2実装PRのreviewで確定。
- 2026-10-07（revision 8、#524）: Decision 7に#520結論後のowner decisionを反映（min 35維持・API 29〜34のprovider対応復活なし）。runtime検証（2026-10-07）でAPI 37のhidden API破壊（`IWindowManager.createInputConsumer` 旧form削除、`registerInputConsumer` はgate非依存でクラッシュ）が実測されたため、**maxSdk 37への引き上げは保留**（検証に先行する宣言の禁止）とし、provider修復を#545へ分離。provider構成検証の主証跡をroot化API 37/36 emulator＋variant別RRO overlay＋priv-app配置のmatrixとし、実機は通常構成の無影響確認を担当する旨を明記。他Decisionとschema/migration契約は変更しない。
- 2026-10-08（revision 9、#545）: Decision 7へ#545のcompat実装とruntime検証結果を反映（bind path修復成立・thumbnail/G3面未解決のため **maxSdk 37引き上げは引き続き保留**、advertised range 35..36維持）。provider構成matrixの主証跡を `docs/assessment/545-api37-provider-fix-evidence/` へ拡張。他Decisionとschema/migration契約は変更しない。
