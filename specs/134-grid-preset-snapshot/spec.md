---
issue: "#134"
status: accepted
requirements: [NFR-007]
updated: 2026-08-25
---

# 端末種別に整合するグリッドプリセット目録

> Stage A gate: 本specと同伴の[plan.md](./plan.md)は2026-08-25にIssue [#134][1]で承認された。
> 両文書はStage B実装の拘束契約であり、実装発見が決定と矛盾する場合は暗黙の代替を導入せず停止条件として扱う。

## Problem

ランチャーは起動時にグリッドプリセット目録を組み立てるが、その時点では端末種別(device type)が未確定のため、**どのホストでも電話カテゴリ分のプリセット(`3_by_3`, `4_by_5`, `5_by_5`)で凍結される**。タブレット等の非電話ホストでの観測可能な結果(API 36.1 Pixel Tablet AVD、検証head `b80d7a9360`):

1. タブレットで有効な唯一の宣言プリセット`6_by_5`を名前指定で切り替えようとすると、production seam上で例外が発生し切替できない。
2. 「現在のグリッド名」照会が、live寸法(4x5/hotseat 4)を凍結済み電話プリセットへ天井合わせした`4_by_5`を返す。これは当該ホストでは無効なプリセットであり、永続された`idp_grid_name`(例: `6_by_5`)とも食い違う。名前からの状態復元が信頼できない。
3. ホストによっては「有効プリセットのいずれとも一致しないlive寸法」で安定してしまい、寸法preferenceへの直接書き込みは次回起動で既定chainにより戻される。

電話ホストでは、凍結された目録が偶然正しいため影響しない。親証跡は[#108](https://github.com/nunu1733/NunuLauncher/issues/108)互換性行列で、タブレット6x5セルは本缺陷によりBLOCKED / UNVERIFIED(production preset遷移として)。

## Outcome

プリセット目録が、参照のたびにauthoritativeな現在device typeから解決される。これにより、名前指定によるプリセット切替・グリッド名マッピング・永続化されたgrid状態が、phone / tablet / multi-displayの各ホストで相互に一致する。プリセット同一性の正本は単一のまま(並列のgrid制御pathは作らない)。

## Scope

- 起動後の全タイミングで、有効プリセット集合(目録)が現在のauthoritative device typeから解決されること。構築時snapshotに固定されないこと。
- 名前指定によるプリセット切替(`setCurrentGrid`)が、有効プリセットに対して成功し、プロセス再起動後も維持されること。
- 現在グリッド名照会(`getCurrentGridName`)が常に有効プリセット名を返すこと。完全一致が無い場合は本specに明記した決定的な近似規則によること。
- 電話ホストでの目録内容・順序・各関数の結果は現状と同一であること。
- 当該ホストで無効または未知のプリセット名への要求はfail-closedであること。

## Non-goals

- `device_profiles.xml`の宣言内容・device category割当の変更。
- 第二のgrid制御pathの追加。organizer planner / layout application / recoveryの各seamは変更しない。
- preference key・永続format・Launcher DB schemaの変更。
- 他のstatic deviceType読み手(`getGridOptionFromFileName` / `getGridNameFromSize` / `getGridOptionFromName`)の修正。これらは初回grid初期化完了後の呼び出しのみの現状であり、缺陷が出た時点で分割する。
- 実機`TYPE_MULTI_DISPLAY`でのruntime検証。入手可能なemulatorには存在せず(#108行列のresidual limitation)、本Issueでもsupport主張をしない。
- 既存`app.lawnchair.deck`の調査(AGENTS.md設計規約)。

## Domain language

承認時に`CONTEXT.md`へ反映する。

- **有効プリセット (enabled preset)**:
  宣言カタログのうち、現在の端末種別に対してプラットフォーム宣言上有効と判定されたグリッドプリセット。
  _Avoid_: 利用可能グリッド(設定UIの表示と混同する場合)、サポート対象(NFR-007のsupport範囲と混同する場合)

## Behavior scenarios

### Scenario: タブレットでの名前指定グリッド切替

Given TYPE_TABLETホストでlauncherがHOMEとして動作し、有効プリセットが`6_by_5`のみである
When production seam経由で`setCurrentGrid("6_by_5")`を実行する
Then live workspace寸法が6列×5行/hotseat 6になり、再読込後も維持される
And プロセス強制停止と再起動の後も同じ寸法・グリッド名が維持される(durable)
And 現在グリッド名照会が`6_by_5`を返し、永続されたgrid名(`idp_grid_name`)と一致する

### Scenario: 現在グリッド名は常に有効プリセットから返る

Given 任意の端末種別で、live寸法が有効プリセットのいずれとも完全一致しない(例: タブレット既定の4x5/hotseat 4)
When 現在グリッド名を照会する
Then 戻り値は有効プリセット集合の要素である
And 完全一致があればそれを返す。無ければ宣言順で最初の「両寸法以上」のプリセット(天井合わせ)、全プリセットが未満なら最後の有効プリセットを返す
And この近似は決定的(宣言順tie-break、locale・時刻非依存)であり、近似になり得ることは本specにより明文書である

### Scenario: 無効・未知プリセット要求はfail-closed

Given タブレットホスト(`3_by_3`は電話専用カテゴリで無効)
When 存在しない名前、または当該ホストで無効な名前で切替を試みる
Then rows / columns / hotseat列数のいずれのpreferenceも変化しない(no persistent change)
And 要求名と当該ホストの有効プリセット集合を特定できるtyped diagnosticが得られる
And 切替は部分適用されない(1keyだけ書かれる等が起こらない)

### Scenario: 電話ホストでの不変性

Given TYPE_PHONEホスト
When 既存のグリッド操作(公式UI経由および`setCurrentGrid`による3x3 / 5_by_5への切替)を実行する
Then 目録の内容・順序・全関数の結果が本変更前と同一であり、既存test laneが無修正で通る

### Scenario: 姿勢変化で端末種別が変わるホスト(foldable)

Given 展開/折りたたみで判定されるdevice typeが変わるホスト(#108実績: 開=TYPE_TABLET、閉=TYPE_PHONE)
When 姿勢変更後にグリッド名照会または切替を行う
Then 目録は変更後のdevice typeから再解決され、旧姿勢で凍結された目録を使用しない

## Data and state

- 読む正本: `device_profiles.xml`の宣言カタログ、`DisplayController.Info`由来の現在device type(grid初期化chainが既に使用する権威と同一)、rows / columns / hotseat列数preferences、既存chainが管理する永続grid名(`idp_grid_name`)。
- 書く状態: 本修正が新規に永続化するものはない。`setCurrentGrid`による既存3 key書き込みは現状通り。grid切替はアクティブなLauncher DBファイル識別子(`launcher_<rows>_<cols>_<hotseat>.db`)を切り替えるLawnchair既存機構の上に乗る。**favorites行の削除・再挿入は行わない**ため、AGENTS.mdのホームレイアウト安全規約が求めるrecovery pointは本seamには要求されない。ただしDBファイル同一性に波及するため、実装PRは`risk: layout-data`扱いとする(Issue受入条件)。
- migration / backup / restore / rollback: schema変更なし。PR revertで現行挙動へ戻る(タブレットは#134症状へ戻るのみで、データ破壊はない)。restore時の`reinitializeAfterRestore` chainは「名前解決が有効目録内へ収束する」という本契約に従って現状通り動く。

## Permissions, privacy, and security

None — 新しいpermission、外部送信、sensitive dataの取り扱い追加はない。

## Accessibility and localization

UI flow・文字列の変更なし。設定UIのグリッド一覧内容がhost正しくなることが本質である(例: タブレットで`6_by_5`が選択可能になる)。focus / label / font scaling要件の追加はない。

## Acceptance criteria

- [ ] AC-1: TYPE_TABLET hostでproduction seam経由の`setCurrentGrid("6_by_5")`が成功し、6x5/hotseat 6が適用され、プロセス再起動後も維持される。現在グリッド名照会と永続grid名が一致する。
- [ ] AC-2: 到達可能な全device typeで現在グリッド名照会が有効プリセット名を返す。完全一致優先、無ければScenario記載の決定的天井合わせ近似(近似規則の明文は本specが正本)。
- [ ] AC-3: 電話hostの既存lane(API 35 / 36.1)が無修正でpassする。
- [ ] AC-4: 無効・未知プリセット名の要求でpreference不変かつtyped diagnostic(部分適用なし)。
- [ ] AC-5: タブレットpreset switchingのregression testがrepository harnessに存在し、[#108互換性行列](../../docs/assessment/issue-108-organizer-mvp-compatibility.md)の#134セルがfix/evidenceへ日付付きで参照更新されている。
- [ ] AC-6: 実装PRは`risk: layout-data` labelを付け、high-risk独立エビデンスgate(audited head上でのCI merge gate成功+別sessionによる`docs/assessment/pr-<N>-<slug>.md`)を満たす。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | API 36.1 Pixel Tablet AVD上のinstrumentation(named-preset遷移 + force-stop/restart耐性probe)。command blockをPRへ記録 |
| AC-2 | JVM pure mapping test(deviceType別有効filter、天井合わせの決定性・全域性)+ phone/tablet instrumentation assertion。`TYPE_MULTI_DISPLAY`はpure levelのみ(実機不在を明記しclaimしない) |
| AC-3 | 既存`Issue108GridEvidenceInstrumentationTest`のphone遷移(4x5→3x3、4x5→5_by_5)無修正green + JVM unit test gate |
| AC-4 | instrumentation negative-path test(3 pref key不変assert + diagnostic内容assert) |
| AC-5 | PR内harness/document差分 + 行列documentへのdated addendum(evidence link付き) |
| AC-6 | `high-risk-gate` workflowのaudited head上での成功 + 実装sessionと別のaudit sessionが作成したassessment記録 |

## Open questions

- blockingなし。非blocking: (1) fail-closed時の例外型(現状の`NoSuchElementException`系を維持するか専用型へするか)は観測可能契約(不変+diagnostic)を満たす限り実装detail。(2) 目録解決のmemoizationはstateless解決の計測根拠が出てから別Issueで検討(既定は導入しない)。

## Change history

- 2026-08-25: Issue #134上でStage A承認。`accepted`へ更新。
- 2026-08-25: #134 Stage A向け`proposed`仕様を作成。Issue本文の観測事実と[#108 assessment](../../docs/assessment/issue-108-organizer-mvp-compatibility.md)の実行証跡を入力に、目録解決権威・単一正本原則・fail-closed契約・近似規則を固定。

[1]: https://github.com/nunu1733/NunuLauncher/issues/134
