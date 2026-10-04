---
status: proposed
---

# Lawnchair 16 rebase — 採用baseline・移行方式・rollback（ADR-0018）

> Status: proposed（2026-10-04起草。review受理後にacceptedへ変更する）
> Date: 2026-10-04
> 対応: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) / Phase 0 [#519](https://github.com/nunu1733/NunuLauncher/issues/519)
> 出典: [#442 最終結論C](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5863040551)（2026-09-28）、[upstream-strategy.md](../engineering/upstream-strategy.md) Upgrade policy 5比較軸、Phase 0計測 [issue-516 assessment](../assessment/issue-516-16-rebase-phase0-research.md)
> 置換関係: なし（新規判断）。baseline運用の現行正本 [upstream-strategy.md](../engineering/upstream-strategy.md) Current state は、Phase 4（baseline切替完了時）に本ADRへ合わせて更新する。

## Context

#442の最終判断C（2026-09-28）により、Lawnchair 15 beta 3 baseline（`505dbc40`）から16系へのrebaseが製品要件となった。15系にはbaseline以降の同期先がなく、`16-dev`のみが進行先である。16-devはmerge-base `b011d84c` 以後7,377 commits、`--no-renames` で5,166 files +532,729/−185,654の差分であり、build構成の再編（AGP 9.4.1、vendored `wmshell`、新module群、新submodule）とLauncher3 model周りの構造変化（`LauncherModel.kt`化、grid migration util分割、`MainThreadInitializedObject` の `Overrides` 機構削除）を含む。一方で `SCHEMA_VERSION` 32と `downgrade_schema.json` は不変である。Phase 0計測の詳細は [issue-516 assessment](../assessment/issue-516-16-rebase-phase0-research.md) に記録した。

本ADRは、rebase着手前に「採用upstream SHA・移行方式・branch方針・rollback点・残すbridgeの所有者」を確定し、[upstream-strategy.md](../engineering/upstream-strategy.md) Upgrade policyの5比較軸（product value/Android version support、Launcher3 model/schema/event、Deck retirement後を含むpatch再適用cost、build/toolchain/device test matrix、rollback可能なrelease/migration path）を満たす判断を固定する。

## Decision

1. **採用upstream baselineを `16-dev` commit `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（2026-10-02）に固定する。** Phase 2以後の計測・実装・検証はこのSHAへanchorする。Phase 2着手時に16-devが前進している場合、anchor刷新は本ADRの改訂（差分分類とdispositionの差分更新を含む）によってのみ行い、自動追従しない。
2. **移行方式はfork commitのreplay型rebase**（`git rebase --onto` によるupstream ancestry維持）を専用branchで実施する。baseline側のみに存在するupstream 33 commitsは原則運ばない。不運搬の妥当性は棚卸し済みである（[Phase 0 assessment §5.1](../assessment/issue-516-16-rebase-phase0-research.md): build/docs/翻訳15件と16-dev同等実装確認済み15件は不運搬で挙動損失なし）。**例外として、16-devに同等が存在しない3件を次のように扱う。**
   - `9b48473c`（subgrid検出/警告 **＋ cellX/cellY/spanX/spanYの最近傍丸めrestore変換**）と `53a2092541`（smartspace conflict toggle **＋ rows+1補償・cellY shift・bounds clamp/skip**）の採否・保持条件は、**#522（Phase 1）の成果物としてrebase着手前に確定する。Phase 2はその確定結果を実装するのみであり、判断をPhase 2へ持ち込まない。**
   - icon shadow修正を含むsubmodule pin（現main `6a11ef76` vs 16-dev `7d9e92bd`。16-dev pinは当該修正が欠落）のpin選択は、backup/restore契約と分離し、Phase 2 planでの判断対象とする。判断までrebase差分に入れず、結果をassessment/ADR改訂として記録する。
3. **branch方針**: rebase作業は専用branch（`issue-516-rebase-16-dev` 系）で行い、`main` は15 baselineのまま維持する。移行期間（Phase 2開始〜Phase 4 baseline切替完了）はEpic #516の「rebase前の変更境界」を適用し、`com.android.launcher3` / SystemUI等への新規bridge追加は原則停止する。切替はrebase branchの全検証完了後、単一のmergeで行う。
4. **bridgeの所有者とdisposition**: 再適用対象はbridge inventory 105 path（fork追加49、upstream無変更8、upstream変更45、upstream削除3。anchor main `8af117b6fc`）であり、所有者は各bridge groupの既存owner（deck-retirement: ADR-0006、model-reload/transaction gates: #60監査と#114、layout-schema/recovery: ADR-0003/0004・#118、organizer-ui: #38/#52/#99、homeedit系: ADR-0013/0014・#448〜#450、organizer-home-entry: #452、new-app-destination: ADR-0015・#497、fork-platform-preexisting: fork baseline由来）がPhase 2でも継続する。**dispositionはassessment §5のpath単位で確定済み: keep 57 / adapt 48 / drop 0**（fork追加・upstream無変更pathはpatch競合なしのkeep。ただしmodel-reloadのfork追加3 pathとlayout-schemaのfork追加4 pathは、接続先の構造変化をadapt側pathで再表現する前提のkeepである）。Phase 2はこの確定済みdispositionに従ってconflict解消を行い、代替発見時はassessment/ADR改訂で変更する。要再表現の3点（`LauncherModel.kt` 化への接続再表現、grid migration分割への追従、`onPostInit` hookの再表現）はPhase 2の重点項目とする。
5. **rollback点**: baseline切替の **cutover merge直前の旧 `main` head** を `pre-lawnchair16-<date>` tagとして固定する（cutover merge commit自身をtag化しない）。rollback操作は「cutover mergeのrevert」または「旧baselineからのrelease再発行」とし、protected `main` へのforce-resetは行わない。`SCHEMA_VERSION` 32不変は補助材料であり、16→15 APK/data downgradeの実証または「実証しない場合の検証方法と残存リスクのowner decision」をPhase 4で完了するまでbaseline切替を完了とみなさない。
6. **build/toolchain**: compileSdk 37（minor 2）/ buildTools 37.0.0 / targetSdk 37 / minSdk 26 / AGP 9.4.1 / Kotlin 2.4.20 を16-dev側の選択として採用する。vendored `wmshell` 等の新moduleと新submodule `platform_frameworks_libs_systemui`（branch 16-dev）を取り込む。JDK 21継続可否はPhase 2のbuild gateで確認し、building guide更新はPhase 4で行う。
7. **device test matrix**: API 36 CI lanes（現行構成）に加え、保守者実機 Pixel 9a / API 37（#442 §5.2と同一端末）での代表日常操作をPhase 3の検証対象とする。quickstep advertised support rangeの変化（29..35→35..36）をmatrix上に明記し、API 29〜34端末の実挙動・サポート境界の判断は [#520](https://github.com/nunu1733/NunuLauncher/issues/520) の結論を待ってこのmatrix上のowner decisionとする。
8. **targetSdk 37のbehavior changes対応とAPI 37 quickstep対応は、Phase 1子Issue（[#520](https://github.com/nunu1733/NunuLauncher/issues/520) / [#521](https://github.com/nunu1733/NunuLauncher/issues/521) / [#522](https://github.com/nunu1733/NunuLauncher/issues/522)。起票済み）の結論を待ってrebase後の統合で扱い、rebase差分に混入させない。** 本ADRは「16-dev側のSDK値に追随する」ことのみを確定し、behavior change対応の個別判断は確定しない。

## Alternatives considered

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
