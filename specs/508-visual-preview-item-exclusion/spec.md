---
issue: "#508"
status: draft
tier: H
requirements:
  - FR-004
  - FR-005
  - NFR-001
  - NFR-002
  - NFR-004
  - NFR-009
  - NFR-010
  - NFR-014
updated: 2026-10-03
---

# Organizerの確認面で変更前後を図で確認し、項目単位の除外・再計画ができる

> Risk tier: **H** — application-owned preview 投影の拡張（`organizer/application/preview/**` は[高リスクpath一覧](../../docs/project/github-workflow.md#高リスクprへの独立エビデンス要求)に含まれる）と、coordinator（`ManualOrganizationRun`）の確認前段契約（対象集合→計画→確認能力の整合）を変えるため。**既存の書込み経路・適用契約は変えない**: confirm は引き続き preview 済み `ValidatedLayoutPlan` をそのまま `apply` へ渡し（spec 194）、A2 exact precondition・recovery・transaction は不変である。純粋表示だけの先行差分を切る場合は workflow の階層判定に従う。手順: accepted spec + plan.md、Execution and approval contract、`risk: layout-data` label による高リスク独立エビデンス（CI `final-status` + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit）。
> 出典: seed-backlog order 1（図による変更前後のpreviewと、提案の項目単位の除外）。起票Issue [#508](https://github.com/nunu1733/NunuLauncher/issues/508)。#194/#195 の plan preview・確認一覧と、#449 の図描画部品を再利用する。#439 の共通着手ゲートは[close記録](https://github.com/nunu1733/NunuLauncher/issues/439#issuecomment-5945643365)で解消済み。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441確定）。本specは新規課題 **B8** を定義し、実装PRで同文書へ追加する（§Benchmark）。
> Requirements接続: FR-004/005（適用の安全性は既存契約の再利用であり契約変更なし）、NFR-001/002/004（既存の不変条件を派生inputでも維持）、NFR-009/010（a11y・patch surface）、NFR-014（B8の目標）。

## Problem

Organizerの確認面（`State.Preview`）は具体的な変更一覧（spec 195/#208/#228/#234）を表示するが、ページ全体の変更前後は図で比較できず、提案の一部の項目だけを対象から外せない。利用者が一部の配置を保ちたい場合、提案全体を取り消して手で編集し直すしかなく、提案の残りの価値を失う（Issue #508 Problem）。

確認時点で既に materialize 済み `ValidatedLayoutPlan`（canonical preview source。spec 194）が coordinator 内にあり、その `sourceState` / `intendedState` が変更前後の完全なlayout状態である。また #449 の編集画面はページ図の静的描画を持つが、grid描画はprivateで、その投影はcaptureのpersistent数値IDを前提とし、planned folder/page/candidateを含むOrganizerのafter stateへそのまま流用できるとは断定できない（Issue #508の照合結果）。

## Outcome

利用者は同じ提案の変更前後を「変更前」「変更後」の2面の図と既存の変更一覧で確認し、選んだ項目（既存のtop-levelアプリ/ディープショートカット、および追加候補）を今回の整理提案から除外できる。除外（および解除）のたびに、元の整合した入力の対象集合だけを変えた純粋な再計画が走り、新しいpreview（図・一覧・件数・警告）が表示される。確認・適用できるのは表示済みの最新previewと同一のplanだけである。除外した既存項目は現在の配置に保持され、除外した追加候補は作成されない。

## 設計判断（Issueの決定ゲート項目。spec時点で確定する）

### D-1: 図の正本 — application-ownedの純粋投影、total（degradeなし）

`organizer/application/preview/` に純粋な図投影（`ValidatedLayoutPlan` の `sourceState` → before図、`intendedState` → after図）を追加する。UIへは `LayoutState`・`ValidatedLayoutPlan`・DB型・適用可能planを一切露出せず、表示に必要なtypedな図data（ページ順・格子寸法・各アイテムの位置/span/種別/label・dock行・予約領域・folderメンバー数）だけを渡す。新規page/folder/candidateの参照は typedなproposal-local identity（`NewPageOrdinal` / `NewFolderOrdinal` / candidate `ItemId`）で保持し、永続IDへの転用・生値の表示をしない。同名folderの混同は構造identity（参照の等価）で防ぎ、表示名の一致に依存しない。

図投影はtotalである: 図と変更一覧・件数は同一の `ValidatedLayoutPlan` から同時に導かれ、「図だけが欠け、一覧だけが得られる」状態を型上作らない。投影の内部不整合は spec 194 の `MATERIALIZATION_INVALID` と同じ契約違反（fail-closed）であり、継続可能なdegrade pathではない。Compose描画層の失敗はデータ契約のdegradeではなく画面の失敗であり、本契約の対象外である。既存の `details == null`（環境的preview失敗のcount-only fallback）は図を持たず、除外UIも提供しない（現行契約の維持）。

### D-2: 部品共有 — 静的描画の最小抽出のみ

#449 の静的図描画（ページグリッドのセル配置・予約領域描画・読み取り専用アイテム表示）を、選択規則・編集セッション・適用state machineを含まない最小のinternal共有部品として抽出し、編集画面とOrganizer図previewの双方から使う（置き場所はplanで確定する）。#449 側は抽出後も見た目・操作が不変であることを既存test群で確認する。選択規則（#449のeligibility・touched guard・spec 507の重複guard）は共有部品へ流入させない。Organizer図は読み取り専用描画のみで、除外操作は図上ではなく一覧行の明示的なactionとして扱う。icon解決（`IconCache`経由の実icon）は #449 が所有したままで、Organizerの図は label + 種別表現（folderはメンバー数、widgetは占有footprint）で描く。共有部品のitem表示はiconをoptionalに受け、#449は実iconを、Organizer図は非icon表示を渡す。

### D-3: 除外対象 — 既存top-levelアプリ/ディープショートカットと追加候補に限る（初版）

除外できるのは、(a) 現行の計画inputで `ExistingRole.Movable` かつ workspace配置（top-level）かつ kind が `APPLICATION` / `DEEP_SHORTCUT` の既存項目、および (b) `TargetSet.additions` の追加候補（#228で選択されたもの）である。除外可能な行の集合（key・label・種別・候補別）は図投影と同じ純粋投影が現行input由来で計算し、`PlanPreviewDetails` の拡張fieldとしてUIへ公開する。

対象外とする行（folder自体・folder内メンバー・widget・Dock上・app pair・legacy shortcut・ロック等のより強い保持理由を持つ行・生成予定folder/page行）は除外actionを出さず、対象外である理由をa11yで供給する。生成予定folder/pageの行は独立の除外対象にしない（メンバーの再計画結果として生成/消滅する。Issue Scope 4）。初版でfolder親子・widget/Dockの部分編集を広げない。対象を広げる必要が生じた場合は、lock保全・folder整合の具体的問題を添えて別Issueで再判断する。

### D-4: 除外の意味論 — run限定のrole変更とadditions除去（永続lock不変）

既存項目の除外は、**当該runに限り**派生inputの `ExistingRole` を `Movable` から `Preserved` へ変更する。永続lock（`organizerLockState` 列）は一切書き換えない。除外項目は `PreserveReason.NON_TARGET` で元の配置に保持され、その占有セルは既存のplanner占有規則（`PlanningPlacement.place` がpreserved項目のworkspaceセルをallocatorへmarkする）により他の項目の配置候補にならない。追加候補の除外は `TargetSet.additions` から当該候補を除き、当該候補の分類signal entryも同時に除く（planner検証 `checkUnknownSignalItem` との整合。対象から外れた候補に分類は不要）。除外された候補は未配置のまま残り、いかなる作成も生じない。runModeは不変である（`ScopeComposedOrganization` のadditionsが空になってもよい。空選択と同一shape。`FullOrganization` のrunはもともとadditionsを持たない）。除外設定は永続化しない。次回runは従来どおり全対象である。

### D-5: 再計画 — 元の整合したinputの対象集合変更 → 純粋planner → 既存materialize/inspectPlan

除外集合が変わるたび、coordinatorは**元の整合したinput**（`PendingPlan.input`）から対象集合だけを変更した派生input（snapshot revision・rules・taxonomy・catalog・signals（除外候補分を除く）・personalization・intentPreferences不変。派生は常に元inputから直接行い、逐次適用しない）を作り、純粋plannerで再計画し、既存のmaterialize/`inspectPlan` 経路で新しいpreviewを得る。capture内のitem削除・materialized actionsの間引き・intended stateだけの書き換えは行わない。folder min-size・分割・page割当・警告・件数は再導出される。

再計画の結果は既存の結果分岐に従う: 空差分（moved/newFolder/newPage/addedすべて0）は既存の `NoChanges` 扱いでrunを終了する（変更したように成功表示しない）。plannerの `Rejected` は既存の `PlanningRejected` 扱いである（派生inputは妥当な対象集合変更のみを行うため通常到達しない防御path）。

### D-6: 確認の権威 — 世代管理と構造的なconfirm不可

coordinatorに再計画の世代カウンタを追加する。除外集合の変更要求（除外・解除とも）ごとに世代を進め、完了時点で世代が現行でなければ結果を零書込みで破棄する（古い非同期結果が新しいpreviewを上書きしない）。再計画中は新しいstate（`Replanning`: 直前の安定したsummary/detailsと現在の除外集合を運ぶ）を公開し、confirmは構造的に不可である（`confirm()` は `State.Preview` のみ受理する現行規則を維持する）。表示済みの最新previewに対応するplan（`PendingPlan.previewPlan`）だけが適用対象であり、confirm時の適用は従来どおり同一インスタンスを `apply` へ渡す（spec 194 PP-AC-04 と同一契約。A2 exact precondition が最終gate）。

### D-7: 失敗・寿命 — 零書込み、capability不変、count-onlyへの落下禁止

- **stale**（再計画時の再capture revision不一致）: 既存どおり `State.Stale`（preview時のため `DETECTED_BEFORE_REVIEW`）+ `APPLY_REJECTED` event。零書込み。最新homeで提案を作り直す（除外設定は持ち越さない）。
- **環境的失敗**（`WriterBusy` / `Concurrent` / `Unavailable` / `CAPTURE_FAILED`）: **除外を一度でも変更した提案ではcount-only確認へ落とさず**、既存の `State.PreviewUnavailable` で再試行のみを提供する（再試行は同一除外集合で再計画からやり直す）。除外変更を行っていない初回previewは、Add を含まないrunの既存fallback（`details = null` でも確認可）を無変更で維持する（Issue Scope 8）。
- **候補解決失敗**（`CandidateResolutionFailed`）: 既存のtyped stateのまま。
- **契約違反**（`OUTCOME_NOT_PLANNED` / `MATERIALIZATION_INVALID`）: 既存どおりfail-closed。
- **cancel / process再生成**: `PendingPlan` はprocess-localであり、除外を含む古いconfirm capabilityは復活しない。除外設定のreplay・永続的な除外設定は作らない。

### D-8: 確認面UI — 決定→図→一覧→除外の順、図は読み取り専用

`details != null` の確認面の構成順は、(1) 決定group（spec 209の契約不変）→ (2) 図（「変更前」「変更後」の見出しと各ページの静的グリッド・dock行）→ (3) 変更一覧（spec 195/208/228/234の行・grouping・truncation不変）→ (4) 除外済みgroup（除外した項目と「戻す」action）とする。除外actionは変更一覧の対象行（除外可能な行）に行単位で置く。図は読み取り専用でtap・dragを受け付けない。再計画中は進行を示し、確定actionを無効化する。直前の安定したdetailsは再計画中も表示し続ける（直前の提案の正確な内容であり、最新の除外反映前であることが進行表示で告知される）。除外済みの追加候補のlabelは、その候補のAdd行が最後に現れたdetails由来をprocess-localなUI stateとして保持する（除外自体もprocess-localであるため、寿命は一致する）。`details == null` のdegraded面と `PreviewUnavailable` 面は既存の構成・文言を維持する（PreviewUnavailableは除外文脈でも同一面を使う）。

### D-9: ベンチマーク — 新課題B8「提案の1項目を元位置に残し、他の提案を適用する」

NFR-014の追加課題として **B8** を本specで固定し、実装PRで [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) へ追加する（既存B1〜B7の値だけで図previewの価値を達成と主張しない）。詳細は§Benchmark。

## Scope

1. **図投影（application module、純粋）**: `organizer/application/preview/` へ図投影moduleを追加する。入力は `ValidatedLayoutPlan`（+ 除外可能表面の計算に必要な現行input由来の対象role）。出力は `PlanPreviewDiagrams`（before/afterの図表示model）と除外可能表面。`PlanPreviewDetails` への追加field（`diagrams`・`excludableItems`）はadditiveであり、既存の `PreviewChange` / `PreviewCounts` の行契約・件数truth（spec 195 D-2/D-6）は不変である。
2. **共有静的描画部品**: #449 からの最小抽出（D-2）。選択・セッション・適用state machine・icon解決は共有しない。
3. **除外・再計画（coordinator）**: `ManualOrganizationRun` へ除外集合適用entry（絶対集合での適用）、派生input生成（純粋関数）、再計画・再preview、世代管理、`Replanning` state、`State.Preview` への除外集合field（additive・default空）を追加する。派生input生成は `organizer/planning/` 内の純粋関数とする。
4. **確認面UI**: 図（before/after）の描画、除外action、除外済みgroup、再計画中の表示・確定無効化（D-8）。
5. **文書同期（最終PR）**: `CONTEXT.md`（用語2語）、`DESIGN.md`（preview seam族への図投影の追記）、[editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（B8行）、`docs/product/requirements.md`（要件追跡は実装PRで確定）、seed-backlogは起票済み参照のまま。organizer-diagnostics.mdへの図・除外のdiagnostics流出禁止の明文化（契約維持の記載）。

## Non-goals

- 図上drag・セル編集、汎用ホーム編集エンジン、#449 state machine全体の共通化。
- 重複削除提案（order 6、#507実装済み）、plannerの削除disposition、既存folderへの追加（order 5）。
- 永続lockの変更、除外設定の永続化、Undoの変更、stale時の3-way merge/replay（order 12）、AI相談の凍結解除。
- folder親子/widget/Dockの除外対象拡張（D-3の対象外理由を示すのみ）。
- 上流Launcher3/modelへの新しいbridge、`src/**` 変更。
- organizer図での実icon解決（`IconCache`）。label + 種別表現で識別する（D-2）。
- plannerの配置アルゴリズム・strategy catalogの変更（派生inputは既存plannerに渡すだけ）。

## Domain language

- **図プレビュー (visual plan preview)**: materialize 済み `ValidatedLayoutPlan` のsource/intended stateから、確認面のために純粋投影が作る変更前後のページ図。読み取り専用・process-localで、書込み・チェックポイント・recovery操作を行わない。_Avoid_: 編集画面の図（#449の編集セッションが所有する作業投影）、plan preview（一覧を含む上位概念。spec 194）
- **提案除外 (proposal exclusion)**: 1回の整理提案に限って、既存項目を対象から外してcaptured配置のまま保持する、または追加候補を今回の追加から外す明示操作。run限り・process-localであり、永続lock・次回runの対象には影響しない。_Avoid_: lock（永続的な整理対象固定）、削除（ホームから外す操作）

（承認時に `CONTEXT.md` へ反映する）

## Prior art

- Android Developers "Semantics properties in Compose"（対象: Jetpack Compose semantics / URL: https://developer.android.com/develop/ui/compose/accessibility/semantics / 確認日: 2026-10-03）— 採用: 読み取り専用の図グリッドは低level描画のためsemanticsを手動供給する。図の各itemを `mergeDescendants` で単一nodeとし、グリッド構造を `collectionInfo` / `collectionItemInfo` で公開する構成をa11y契約へ採用する。
- 主要ランチャーの自動整理機能の調査（対象: Microsoft Launcher（Google Play listing）・Smart Launcher等の公開資料 / URL: https://www.bing.com/search?q=launcher+home+screen+auto+organize+preview+exclude+deselect+icon+arrangement / 確認日: 2026-10-03）— 不採用（参考にできる公開実装なし）: 変更前後図preview・提案の項目単位除外を持つlauncherの公開実装は確認できなかった。ため、既存の自社契約（spec 194 preview seam・spec 449 図描画）の再利用を優先する（workflow正本「repository内に既に同じ契約がある場合は既存architectureとの整合を優先」）。

## Behavior scenarios

### Scenario: 変更前後を図で比較できる

Given 提案がmaterializeされ、`State.Preview(summary, details)`（details != null）が表示されている,
When 確認面を開く,
Then 「変更前」「変更後」の見出しとともに、各ページの格子図（アイテムの位置/span・widget footprint・folderとメンバー数・dock行・予約領域）が表示され、変更後に新規folder・新規page・追加候補が現れる,
And 図は変更一覧・件数と同一のplan由来であり、書込みは一切発生しない。

### Scenario: 同名のfolderを混同しない

After 図に同名の既存folderと生成予定folderが両方存在する,
When 図が描画される,
Then 両者は構造identity（既存はpersistent参照、生成はplanned ordinal）で区別され、表示名の一致で同一視されない。

### Scenario: 既存アプリを除外すると再計画されて新しいpreviewになる

Given 提案でアプリA（top-level）が新規folderへ移動され、B・Cが同じfolderに入る予定である,
When 利用者がAの変更行の「提案から外す」を選ぶ,
Then 再計画が走り、Aは元の配置に保持され（保持行・NON_TARGET）、B・Cだけの再計画結果（folder形成のmin-size未満ならfolder消滅、件数・警告の再導出）を含む新しい図・一覧・件数が表示される,
And Aの保持セルは他の項目の配置先にならず、lock・profile・他行のfavoritesは一切変化しない。

### Scenario: 追加候補を除外すると未配置のまま残る

Given #228で選択した候補D・EのAdd行が表示されている,
When 利用者がDを除外する,
Then DのAdd行とDの生成folderメンバーが消え、Dは作成されない（Eは従来どおり提案される）,
And 全候補を除外した場合でもrunは妥当に再計画される（additions空のscope-composed runとして既存契約どおり）。

### Scenario: 生成folder/pageは独立の除外対象にならない

Given 提案に生成予定folder行と新規page行がある,
When 一覧を確認する,
Then これらの行には除外actionがなく、folder/pageはメンバーの再計画結果として生成/消滅する（行単位の除外はできない）。

### Scenario: 除外しすぎて変更が空になるとNoChangesで終わる

Given 提案の変更対象をすべて除外した,
When 再計画が完了する,
Then 差分は空となり、既存のNoChanges扱い（確認なし・run終了）となり、「変更した」ような成功表示はされない。

### Scenario: 再計画中は確認できず、古い結果は新しいpreviewを上書きしない

Given 利用者が除外actionを連続して実行した,
When 1つ目の再計画が完了する前に2つ目の除外要求が出る,
Then 画面は再計画中の表示（確定無効）になり、1つ目の結果は世代不一致で破棄され、2つ目の除外集合に対する再計画結果だけが表示される,
And 例外の到着順序に依存しない（零書込み）。

### Scenario: 除外を変更した提案はcount-only確認へ落ちない

Given 除外を1つでも変更した提案の再計画で、preview取得が環境的に失敗した（writer busy等）,
When 失敗が返る,
Then 既存のPreviewUnavailable面（再試行・中断のみ）となり、count-only（details = null）での確認・確定はできない,
And 再試行は同一除外集合で再計画からやり直す。

### Scenario: 再計画でstaleを検出すると零書込みで止まる

Given 提案の表示中にホームが変化した,
When 除外操作による再計画の再captureでrevision不一致を検出する,
Then 既存どおりStale（零書込み・recapture要求）となり、除外設定を含む古い提案は破棄される。

### Scenario: キャンセル・process再生成で除外を含むcapabilityは復活しない

Given 除外を適用したpreviewが表示されている,
When 利用者が中断する、またはprocessが再生成される,
Then 提案・除外設定は消え、再開したrunは従来どおり全対象の新しい提案から始まる（除外のreplayはない）。

### Scenario: 対象外の行は理由つきで除外できない

Given 一覧にfolder行・widgetの保持行・Dockの保持行がある,
When これらの行を確認する,
Then 除外actionはなく、対象外である理由（folder単位/widget/Dockは対象外等）がa11yで供給される。

### Scenario: degradedな初回previewは従来どおり

Given 除外変更を行っていないrunの初回previewが環境的に失敗した,
When 確認面が表示される,
Then 既存どおり `details = null` のcount-only確認（degraded告知つき）となり、図・除外UIは提供されない（既存fallbackの無変更）。

## Benchmark（NFR-014・新課題B8）

改善する課題は **B8: 提案の1項目を元位置に残し、他の提案を適用する** である（§2相当の定義を実装PRでbenchmark正本へ追加する）。開始状態: Organizerの提案が確認面に表示され、指定項目A（fixtureのidentity表で指定。提案ではAが新規folderへ移動される）を元位置に残したい。終了状態: 提案の他の変更がすべて適用され、Aのみ元のセルに存在する。

- **本specが確定するB8目標**: 本機能経路の固定手順の重み付き操作コスト **8未満**（#441 §4の重み表による決定的会計。現行経路は10。操作数の目標は置かない）。
- **本機能経路の固定手順**: hub入口（workspace空きスペース長押し2 + hub row 1 + 方法選択「このまま整理」1 = **4**）→ 提案面でAの行の「提案から外す」tap（1）= **1** → 再計画と新preview表示（操作なし）→ 確定tap（1）= **1**。合計: 重み付き **6**、操作数6。**8未満を満たす。**
- **現行経路（本機能なし。取消→手編集ではなく確定→手編集が最短）**: hub入口 **4** → 確定tap **1** → 適用後、Aが入ったfolderを開くtap **1** + A長押し **2** + popup「ページへ移動…」tap **1** + ページ選択dialog **1** = **5**。合計: 重み付き **10**、操作数8。fixture前提: 適用後にAの元セルが当該ページの最初の空きセルになること（「ページへ移動…」が最初の空きセルへ置く既存契約のため、決定的会計の前提をfixture側で保証する）。
- 会計の検証は実装PRでfixture（§5の拡張）によるエミュレータ実行で行い、B8行としてbenchmark正本へ追加する。Undo（B5）はB8の勘定に含めない。

## Data and state

- 読むdata: `inspectPlan` が返す `PlanPreviewDetails` の拡張field（図model・除外可能表面）。すべて同一のmaterialize済み `ValidatedLayoutPlan` と現行input由来。UIはplanner/application型・DBに直接触れない。
- 除外集合と再計画世代はcoordinatorのprocess-local state（`PendingPlan` と同一の寿命規約）。serialize・journal化・exportしない。`State.Preview` / `Replanning` の除外集合field経由でのみ観測可能。
- 書くdata: なし（新規）。確認・適用は既存のapply経路（preview済みplanをそのまま適用）のみ。migration・backup/restore・rollbackへの影響なし。
- 除外済み候補のlabel（UI state）と展開stateはprocess-localであり、`details` の変化で初期化してよい（既存の展開stateと同一規約）。

## Permissions, privacy, and security

None。新規permission・外部通信・telemetryなし。図と除外表面が運ぶのはcanonical capture由来の表示名（`PreviewLabel`）・種別・位置のみであり、raw package / component / `ItemId` / `PageId` / 生cell / digest / profile serialを表示へ露出しない（spec 194 privacy契約の継続）。図model・除外表面・除外集合をdiagnostics journal / logcat / exportへ投影しないことをorganizer-diagnostics契約へ明文化する。test fixtureは実titleを含まない（synthetic identityのみ）。

## Accessibility and localization

- 図の各itemは `mergeDescendants` で単一の読み上げnode（label + 位置 + 種別。folderはメンバー数、widgetは占有表示）とし、ページグリッドに `collectionInfo` / `collectionItemInfo` を供給する（Prior artの公式guidance採用）。「変更前」「変更後」・ページ見出しは見出しnodeとして読まれる。
- 除外actionは行ごとに到達可能で、除外済みかどうかを `stateDescription` で報告する。対象外の行は対象外である理由をa11yで供給する。再計画中は確定actionの無効化が支援技術から観測可能である。
- 既存の確認面契約（行の単一label構成、status行の `liveRegion`、focus復帰、traversal順序 status → confirm → cancel → 展開action）を維持し、図・除外の追加nodeもtraversal可能にする。200% font scaleで図・一覧・除外行がwrapし、切抜き・横scroll依存がない。
- 追加stringsは `values/` + `values-ja/` の両方へ置く（#123: 日本語実行時の英語fallback禁止）。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | before/after図がapplication-ownedの純粋投影から作られ、`State` 経由でUIが受け取るのは図表示data（と既存details）のみである。`LayoutState` / `ValidatedLayoutPlan` / DB型の非露出、ページ順・grid/span・予約領域・Dock・既存/新規folder（同名混同なし）・新規page・追加候補の識別、planned参照のtyped proposal-local identityをunit testで検証する。 |
| AC-2 | 図・変更一覧・件数・確定対象が同一plan由来で一致する: 同一 `(input, result)` + revisionからの図投影・detailsの決定性（2回実行一致）、図と一覧の行対応（同一planのactionsから導かれること）をcontract testで検証する。図のみが欠けた状態は型上存在しない（投影total。AC-9参照）。 |
| AC-3 | 部品共有が最小である: #449 の選択・編集・stale/Undo と spec 507 の重複確認が回帰しないこと（既存test群 + エミュレータ操作）、共有部品へ選択規則・セッションstate・icon解決が流入しないことをdiffで確認する。 |
| AC-4 | 既存項目の除外・解除がD-4/D-5の経路で動く: 除外項目は `Preserved(NON_TARGET)` 行として元配置に現れ、保持セルが他項目の配置先にならないこと（planner占有のtest）、lock/profile/folder参照/conservationが不変であること、派生inputの決定性と、除外→解除→元の提案への復帰（同一入力から同一plan）をunit testで検証する。 |
| AC-5 | 追加候補の除外が動く: 除外候補のAdd行・生成folderメンバーからの消滅、未配置のまま（create 0件）、signal entryの同期除去、全候補除外時の妥当な再計画（additions空のscope-composed）をunit testで検証する。 |
| AC-6 | 生成folder/pageの連動: 除外でfolder min-size未満のgroupはfolderを作らない、不要になった新規pageは消える、生成folder/page行は独立の除外対象でないことをunit testで検証する。 |
| AC-7 | 確認の権威: 再計画中（`Replanning`）はconfirm不可、高速連続操作・結果の到着逆転で古い結果が新しいpreviewを上書きしない（世代一致test）、confirmは表示済み最新previewのplan（同一インスタンス）のみをapplyへ渡すことをcoordinator testで検証する。 |
| AC-8 | 失敗・寿命: 除外変更後の環境的preview失敗はcount-only確認へ落とさずPreviewUnavailable（再試行は同一除外集合）、除外変更なき初回は既存fallback維持、staleは零書込みで `State.Stale`、cancel/process再生成で除外を含むcapabilityが復活しない、空差分はNoChanges扱い（成功表示なし）をcoordinator testで検証する。 |
| AC-9 | degrade: `details == null` 面は図なし・除外UIなしの既存構成のまま（回帰test）。図のみ欠落・一覧のみの状態は存在しない（投影total性のunit test）。 |
| AC-10 | a11y/i18n: 図itemの単一node・collection semantics・除外actionのstate・対象外理由・再計画中の無効化がTalkBackで読めて操作でき（emulator構造確認 + 実機owner確認）、200% font scaleで崩れず、追加stringsがja/en両localeで解決する。 |
| AC-11 | ベンチマークB8: 本specの固定手順の会計（本経路6・現行10）をPR本文に記録し、fixtureでのエミュレータ実行で本経路が成立することを確認する。`editing-burden-benchmark.md` へB8を追加する。既存B1〜B7の値だけで価値を主張しない。 |
| AC-12 | 上流bridge増分0: `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の結果をPR本文へ記録する。高リスク独立エビデンス（`final-status` 成功run + `docs/assessment/pr-<PR番号>-<slug>.md`）を満たす。`CONTEXT.md`（2語）・`DESIGN.md`・benchmark・organizer-diagnosticsの同期を同じPRで行う。seed-backlogは起票済み参照のみ。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 / AC-2 | `organizer/application/preview/` 図投影のJVM unit test（fixture: 複数page・span・予約領域・dock・同名folder・planned folder/page/candidate・決定性2回一致・図↔一覧の同一plan由来）。`organizer-unit-tests` gateに自動加入（新laneなし） |
| AC-3 | 既存homeedit JVM test群・instrumentation laneのgreen + エミュレータ操作記録（選択→アクション→確定→Undo、重複確認）+ PR diffの共有部品境界確認 |
| AC-4 / AC-5 / AC-6 | 派生input生成（純粋関数）とplanner再計画のJVM test（役割変更・signal除去・min-size・page消滅・全除外・決定性・冪等・conservation/lock/profile不変）。`ManualOrganizationRunTest`（除外適用・世代・state遷移・preview置換） |
| AC-7 / AC-8 / AC-9 | `ManualOrganizationRunTest`: Replanning中のconfirm拒否、世代不一致の破棄（遅延完了の注入）、環境失敗→PreviewUnavailable（同一除外集合の再試行）、初回fallback維持、stale零書込み、cancel/process死、空差分NoChanges、`details = null` 面の図・除外なし |
| AC-10 | 図・除外行のsemantics descriptorのJVM test + strings resource oracle（en/ja非空）+ エミュレータ（TalkBack構造・200% font scale）+ 実機owner確認 |
| AC-11 | benchmark fixture（§5拡張）でのエミュレータ実行記録 + 会計表（PR本文）+ `editing-burden-benchmark.md` のdiff |
| AC-12 | `measure_upstream_patch_surface.py` の出力（PR本文）+ CI `final-status` run URL + `docs/assessment/pr-<PR番号>-<slug>.md`（独立監査）+ 各正文書のdiff |

## Open questions

None。Issueの決定ゲート項目（項目単位の対象、readonly投影の最小形、preserved対象と追加候補の意味論、degrade/全除外/非同期結果の失効）は設計判断D-1〜D-9として確定した。実装が本specの境界（図投影のtotal性、派生inputの妥当性、世代管理）で表現できないものが必要になった場合は境界を弱めずowner reviewで停止する。

## Change history

- 2026-10-03: Draft created for #508（seed-backlog order 1）。Issue本文のScope 1〜8を設計判断D-1〜D-9として確定し、NFR-014の追加課題B8を定義した。

## References

- [Issue #508](https://github.com/nunu1733/NunuLauncher/issues/508)
- [Spec 194: read-only plan preview and PreviewChange projection contract](../194-plan-preview-seam/spec.md)
- [Spec 195: Organizer confirmation UI renders the concrete change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 449: 複数選択の視覚的編集画面](../449-multi-select-surface/spec.md)
- [Spec 228: ホーム未配置アプリを選択してOrganizerの対象へ追加できる](../228-organizer-missing-app-selection/spec.md)
- [Spec 507: 編集画面で重複を確認し、選んだアイコンをまとめてホームから外す](../507-duplicate-removal-proposal/spec.md)
- [Spec 208: proposal placement identity](../208-organizer-proposal-placement-identity/spec.md)
- [Spec 234: destination anchor specificity](../234-organizer-destination-anchor-specificity/spec.md)
- [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（B8追加の正本）
- [DESIGN.md](../../DESIGN.md) / [CONTEXT.md](../../CONTEXT.md)
- [docs/engineering/organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [docs/project/github-workflow.md](../../docs/project/github-workflow.md)（Risk tiers・Execution and approval contract・高リスク独立エビデンス）
