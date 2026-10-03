---
issue: "#509"
status: draft
tier: M
requirements:
  - FR-008
  - NFR-014
  - NFR-010
  - NFR-002
  - NFR-005
  - NFR-009
  - D-015
  - D-013
updated: 2026-10-04
---

# カテゴリ別の新規アプリ配置（既存destination-policy seam上）

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。Phase A feasibility調査（`docs/assessment/issue-509-category-destination-feasibility.md`）で「前へ進める」判定済み。Launcher DB書込み経路・schema・上流model/loader bridge・queue wire formatの変更を含まない（変更surfaceはfork-owned moduleのみ。§Verification）。

> Risk tier: M — 新しい書込み経路を持たない。新規アプリのINSERTは#497の既存admission内write（`ModelWriter.addPendingInstallForDirectEdit`）をそのまま使い、本機能は既存適用経路の前段（capture時の配置先決定）と選択面を拡張する。高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py`）に触れない。
> 判断の正本: [ADR-0015](../../docs/adr/0015-new-app-destination-policy.md)（accepted）。本specはそのDecision 3/15をsuccessor ADR（[ADR-0017](../../docs/adr/0017-category-new-app-destination.md)。本specと同じPRで新設、受入はmergeで完了）で拡張する。Decision 1/4/5/6/7/8/9/10/11/14は不変。
> Phase A根拠: `docs/assessment/issue-509-category-destination-feasibility.md`（2026-10-04、対象commit `8508c14182412c2a9cf6f6240eadfe30b5322047`）

## Problem

#497により新規アプリを利用者の指定した1フォルダへ追加できるが、カテゴリに応じた複数の既存フォルダへの振り分けはできない。利用者がカテゴリ別にホームを構成している場合、install後の手動振り分けが残る。

## Benchmark

改善する課題: **B1の固定派生scenario「B1-CAT」**（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md) §6のB1（baseline 8操作/操作数3、#497実装後の固定フォルダ結果=追加操作0）とは別会計とする。固定フォルダのB1/B6=0を本機能の改善として二重計上しない）。

- B1-CATの固定scenario: 利用者が2つのカテゴリ（例: GAME→「Games」フォルダ、PRODUCTIVITY→「Work」フォルダ）へmappingを1回設定した後、カテゴリ別の新規アプリ2個とmapping外の新規アプリ1個をinstallする。対応カテゴリの2個は追加操作0（重み付きコスト0）、mapping外の1個は上流既定（ベースライン動作と同じ）に置かれる。
- 目標値: **mapping設定後の、対応カテゴリ新規アプリの追加操作0**（重み付きコスト0・操作数0）。mapping設定は「一度だけの設定コスト」として会計から分離する（NFR-014の固定派生scenarioとして§7に記録する）。

## Prior art

- AOSP PackageInstallerService（session callbackの可視性filter）: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageInstallerService.java （確認日 2026-10-04。promise経路のruntime到達条件の根拠。Phase A assessment §3）
- Smart Launcher Smart Folders: https://docs.smartlauncher.net/products/faq/changelog/5.4 （確認日 2026-10-02。自動分類に基づく全自動追加はADR-0015 AlternativesでRejected。本機能は利用者の明示mapping＋解決不能時の上流既定fallbackであり採用しない）
- Android `LauncherApps.getApplicationInfo`: https://developer.android.com/reference/android/content/pm/LauncherApps#getApplicationInfo(java.lang.String,%20int,%20android.os.UserHandle) （確認日 2026-10-04。分類readのfail-closed契約）

## Outcome

配置先ポリシーでカテゴリモードを選び、カテゴリと既存フォルダの対応（mapping）を明示設定した状態では、上流が追加を決めた新規アプリのうち分類が対応カテゴリへ解決できたものが、利用者の追加操作なしで対応フォルダ内（末尾rank）に置かれる。解決できないアプリ（未mapping・分類不可・対応カテゴリ消失）は上流の既定（空きセル）へ置かれ、既存アイテムは後追いで移動しない。capture（enqueue）時に解決を終えて既存の4field `FOLDER` snapshotとして永続化するため、promise→完了で配置が動かず、再flush・process再起動でも同じ配置になる（#497契約の継承）。

## Scope

- **policy選択の拡張**: 既存のポリシー行（[AppDestinationPreference](../../lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt)）の選択肢にカテゴリモードを追加する（既存の「上流の既定／指定フォルダ／追加しない」と排他的な1選択。「追加しない」⇔`pref_add_icon_to_home` OFFの整合、ホーム画面ロック中の無効化は既存のまま）。
- **mapping管理**: 利用者が`CategoryIdentity + profile → 既存フォルダ（favorites行id）`の対応を明示設定する。mapping対象カタログは**capture時に実際に到達するカテゴリ**に限定する（S2のAndroid category 8種、S5の`com.google.*`→TOOLS、S1 override経由で到達するuser-definedカテゴリ。Phase A assessment §3.3/§4）。フォルダ選択は既存のフォルダ選択dialogと同型（Dock上のフォルダ除外、profile注記）。mappingはid基準であり、rename・同名再作成で復活しない。
- **capture時の解決**: `AppDestinationBridge`のresolver（captureDestination）内で、既存優先順位（S1 override → S2 Android category → S5 system/Google。`OrganizationInputComposer.materializeSignals`と同一順序）で分類signalを解決し、mapping照合して既存`FOLDER` snapshotを返す。分類seamはorganizerの既存source（`CategoryOverrideStoreModule`、`AndroidClassificationSignalSnapshotSource`、`BuiltInOrganizerPolicyBundleSource`）を再利用し、organizer run/snapshot/recoveryは起動しない。解決不能は既存`UPSTREAM` snapshotを返す（上流既定）。
- **分類fallbackの記録**: capture時に分類・mappingが解決できなかったことをtypedにFileLogへ記録する（package名は出力しない）。**設定行での通知は行わない**（分類不能は正常系であり、installのたびの通知は騒音になるため。mapping自体が無効になっている状態は設定行のsummary表示で扱う）。
- **queue wire・書込み経路の変更なし**: `DirectEditContract`の4field snapshot・2kind、`ItemInstallQueue`/`AddWorkspaceItemsTask`/`ModelWriter`/`PersistedItemArray`の差分0。stage-2検証・admission内write・typed fallback・one-shot通知・post-admission bindは#497実装をそのまま使う。
- **successor ADR**: ADR-0015のDecision 3（選択肢）/Decision 15（設定）の拡張と、Decision 11のruntime到達性（promise経路は現行platformで第三者launcherに到達しない。Phase A assessment §3.1）の記録。ADR-0015本文は上書きしない。
- **テスト**: capture解決の純粋関数（分類signal解決→mapping照合→snapshot生成、typed fallback、決定性・冪等性）を既存homeedit JVM gateへ追加する（test-audit適用）。#497の既存JVM/instrumentation testが回帰なく通ることを同じPRで確認する。新規CI laneは作らない。
- **文書**: 本spec受入時に`CONTEXT.md`（「分類フォルダ」語の更新）、successor ADR、ベンチマーク§6/§7へのB1-CAT記録、`docs/product/requirements.md`のFR-008への注記（implementedの範囲が固定1フォルダ＋カテゴリmappingへ広がったこと。カテゴリ自動分類の品質向上は含まない）を同じPRで更新する。

## Non-goals

- install完了後にもう一度振り分ける（二段階配置）。promise段階の分類失敗を通常経路の品質主張に使うこと。
- 新しい分類エンジン・外部分類・使用頻度・AI相談、S3表。
- folder自動作成、タイトル一致・子の多数決・暗黙のfolder成長による対応。
- order 5のplanner既存folder加入、全体整理/増分整理、既存homeの後追い再配置。
- queue wire format・`DirectEditContract`の拡張、新しいkind、`PackageUpdatedTask`への分岐追加。
- 新規CI lane、permission/network追加、schema/migration、Undo/recovery protocolの変更。
- `app.lawnchair.deck`の調査と並行分類機構の追加（AGENTS.md設計規約）。

## Behavior scenarios

### Scenario: mappingしたカテゴリの新規アプリが対応フォルダへ置かれる（B1-CAT）

Given カテゴリモードでGAME→フォルダG、PRODUCTIVITY→フォルダWのmappingが設定されている。`pref_add_icon_to_home`は有効
When manifestで`appCategory="game"`を宣言する新規アプリをinstallする（install reason USER）
Then アプリは利用者の追加操作なしでG内の末尾rankに置かれる（追加操作0）
And capture時のsnapshotは既存4field形式の`FOLDER`（Gのid）であり、書込みは#497の既存admission内INSERT 1行である
And 既存子のrankと`organizerLockState`列は不変である

### Scenario: 分類・mappingが解決できない場合は上流の既定へ静かに落ちる

Given カテゴリモードでmappingが設定されている。新規アプリXは`appCategory`を宣言せず、`com.google.*`でもない
When Xのinstallが完了する
Then Xは上流の既定（空きセル）へ置かれる（captureは既存`UPSTREAM` snapshotとなりstock pathを通る）
And 分類fallbackがFileLogへtypedに記録される（package名なし）。設定行の通知は発生しない
And 既存アイテムは移動しない

### Scenario: capture後のmapping変更で配置が動かない（再flush決定性の継承）

Given GAME→Gのmappingで新規アプリAの自動追加がqueueに投入された（snapshot=G固定）
When flushの前にmappingをGAME→別フォルダへ変更し、processが死に、再起動後のflushでqueueが消化される
Then AはsnapshotどおりG内へ置かれる（capture後のmapping変更を再読しない。first enqueue wins継承）
And stage-2はGの存在・profile・Dock・制約のみを再検証し、別のカテゴリフォルダへ再選択しない

### Scenario: 対応先フォルダが削除された場合は既存のtyped fallback

Given GAME→GのmappingでGが削除された後に新規アプリがinstallされる
Then アプリは上流の既定へ置かれ、理由`FOLDER_MISSING`が既存経路で記録・通知される（#497契約のまま）
And 同名のフォルダを再作成してもmappingは復活しない（id基準）

### Scenario: 対応先カテゴリがcatalogから消えた場合はfail-closed

Given user-definedカテゴリU→フォルダFのmappingが設定されている
When Uが削除された後に、S1でUに分類されるアプリの再installで自動追加が発生する
Then capture時の解決は失敗し、アプリは上流の既定へ置かれる（誤配送は起こらない。zero新規mapping解決）
And 分類fallbackがFileLogへtypedに記録される

## Verification

- owner確認用のスクリーンショット/録画は**実機で取得**する（B1-CAT会計: mapping設定→2カテゴリのinstall→追加操作0。Phase A assessment §8.1の未確認範囲「実機GMS端末でのsession event不達」もこの実機確認で二重化する）。emulatorは補助証跡。
- capture解決の純粋関数JVM test（signal解決の優先順位、mapping照合、typed fallback、決定性。既存homeedit gateへ追加。test-audit適用をPRへ記録）。
- #497の既存test（homeedit JVM gate + 書込み経路harness + queue test）が回帰なく通ること（固定フォルダ/既定/追加しない・fallback・snapshot契約への回帰なし）。
- 書込み経路を追加しないことの確認（diffで触れるpathの列挙。`src/`配下の差分0）と、`python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`の結果をPR本文へreportする（階層M要件）。
- `python3 tools/repo-contract/validate_writer_inventory.py`がPASSすること（新規writer fileなし）。

## Accessibility and localization

- カテゴリモードの選択肢・mapping管理UI・summary・分類fallback説明の文言はすべて文字列リソース由来（`values` + `values-ja`）で、TalkBackで読める。font scalingで崩れない。既存の`AppDestinationPolicyTextKeys`／`AppDestinationSettingsTextTest`のsource-contract契約を延長する。
- フォルダ選択dialogは#448/#497と同じAlertDialog慣行に従い、既存のアクセシビリティ検証と同型の検証を行う。

## Open questions

なし（受入時に確定した判断をここへ記録する）:
1. **Coverageの製品受容**: 分類成功対象は「`appCategory`宣言アプリ＋新規`com.google.*`アプリ（＋S1 overrideが存在する再install）」に限る（Phase A assessment §3.3）。設定面の説明でこの範囲を明示する。
2. **mapping catalogの範囲**: S2到達可能8種＋TOOLS＋S1経由のuser-defined（§Scope）。`OTHER`等capture時に到達しないbuilt-inは露出しない。
3. **分類fallbackの通知なし**: FileLog記録のみ。mapping無効状態は設定行summaryで扱う。
4. **promise経路の扱い**: 現行platformでは到達しない（Phase A assessment §3.1）ため予防的契約として明記し、到達時は分類不可→上流既定として扱う。

## Change history

- 2026-10-04: Draft created for #509（Phase 1 Re-Entry）。出典: Issue #509本文、ADR-0015（accepted）、spec 497（implemented）、Phase A assessment（2026-10-04）。
