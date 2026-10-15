---
issue: "#453"
status: implemented
tier: M
requirements: [FR-023]
updated: 2026-10-01
---

# 整理方針（strategy）選択肢を3つへ絞る（T-05 pickerの表示フィルタ）

Risk tier: **M**。strategy picker（材料面T-05）の表示を絞る変更であり、新しいDB書込み
経路・schema migration・recovery store・上流model/loader bridgeを一切作らない。選択の
書込みは既存のvalidated write command（`LayoutStrategySelectionAccess.select`）と
`StrategyWriteArbiter` 経由のまま、catalog（`LayoutStrategyCatalog.runtimeSupported`）と
bundle digestも変更しない（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。

## Problem

strategy picker（T-05、`OrganizerStrategyPreferences`）はcatalogの `runtimeSupported`
9種をそのままradio listで並べている。内部のV1/V2の違い（widget対応の有無）しかない
項目が並び、利用者にとって区別できない9択になっている（[Issue #453][1]、実機証跡
`evidence-work/02-strategy-picker.png`）。picker自身のコメントも
「catalog's rows may exceed one small screen」と認識している。

## Benchmark

該当課題なし。整理方針の選択は一度だけの設定操作であり、編集負担ベンチマーク
（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）
の課題B1〜B7のどの手順にも含まれない。§4は「一度だけの設定操作は課題のコストに
含めず、別に記録する」（B1の配置先設定と同一区分）としており、本件も同じ扱いとする
（Issue #453終了条件4）。したがって操作コストの目標値は設定しない。本件の効果は
測定上はT-05の選択肢数（9 → 3＋選択済み非表示行）として記録する。

## Prior art

- Material 3 Radio button guidelines / https://m3.material.io/components/radio-button/guidelines / 確認日 2026-10-01 / 採用: radio buttonは「一覧から1つだけ選べるとき」の選択部品という公式の位置づけ。絞り込み後もradio group契約（selectableGroup、単一選択）を維持する根拠。
- 選択肢数の上限値（最大3）自体はrepo内の製品判断（再焦点化方針メモ§4.5、FR-023）が正本であり、外部例からの採用ではない。既存のpicker契約（spec 182 child 8、spec 368、Issue #218 a11y契約）があるため、外部例より既存architectureとの整合を優先した。

## Outcome

T-05のpickerは、区別できる意図ごとの3選択肢（既定「標準コンパクト」、
「ページごとに整える」、「下半分に集める」）だけを並べる。非表示にしたstrategyを
保存済みの利用者には、選択を変更するまでその方針を選択中の行として表示し、整理runは
従来どおりそのstrategyで計画する。catalog・bundle digest・書込み契約・fail-closed読み
はすべて変更しない。

## Scope

1. **表示する3つの確定**（メモ§4.5暫定案を採用。Issue未解決事項への決定）:

   | 順 | StrategyId | 表示名（ja / en） | 意図 |
   |---|---|---|---|
   | 1 | `CANONICAL_PAGE_COMPACT_V1` | 標準コンパクト / Standard compaction（名称変更なし） | 全体を上から詰める（既定） |
   | 2 | `STABLE_PAGE_TIDY_V2` | ページごとに整える / Tidy each screen（変更） | 各ページを整える |
   | 3 | `BOTTOM_REGION_V1` | 下半分に集める / Bottom-half layout（jaのみ変更） | 下半分に集める |

   - 第3枠を `GLOBAL_COMPACT_V2`（ページをまたぐ詰め込み）にする案は不採用。既定
     「標準コンパクト」との差が既存フォルダの扱い（spec 237）という内部詳細に寄り、
     「詰め込む」という意図が被る。本Issueが解決する「区別できない選択肢の並列」を
     第3枠で再現するため、意図が重ならない `BOTTOM_REGION_V1`（上部を余白として残す）
     を採る。`BOTTOM_FIRST_V1/V2` は下半分志向で `BOTTOM_REGION_V1` と被り、
     `CATEGORY_CONTIGUOUS_V1` は分類という別軸の複雑な意図であり、いずれも3枠には
     採らない。#413（`BOTTOM_REGION_V1` の実機evidence）は暫定案に含まれるため
     引き続き価値が保たれる（メモ§4.5）。
   - **既定表記について**: 「（既定）」をラベルに焼かない。初回は既定行がradio選択
     されて示され（spec 182のeffective choice契約）、ラベルとrun面summary
     （`manual_organization_preview_strategy`）で名前を共有するため、静的な既定
     マーカーは冗長と判断した。

2. **表示フィルタの導入**: `strategyPickerItems` は、`catalog.runtimeSupported` から
   上記3つをこの順に選んだ表示リストをcomposeする（UI層の純粋な表示判断。bundle・
   composer・provenanceには参加しない）。3つが将来のbundleから除かれた場合は、その
   分だけ行を減らす（防御的動作。現行bundleでは3つとも存在する）。

3. **非表示strategy選択済み時の表示**（メモ§4.5で確定済みの「変更するまで選択中と
   して表示する」の実現方法の決定）:
   - 読み取りが `Ready` で、保存済み選択が **catalogの `runtimeSupported` に含まれ、
   かつ表示3つに含まれない** 場合、表示3行の**後に**そのstrategyの行（既存の行と
   同じradio行。localized name＋description）を1行追加し、その行を選択中とする。
   表示3行は選択中にしない。#453が保つべきは「active catalogには残っているが
   curated 3択から隠した既存strategy」の選択維持であり、追加行の対象をこの範囲に
   限定する。
   - 追加行は同じ `selectableGroup` の member であり、TalkBackは「4 of 4」のように
     グループ内の選択中行として読み上げる。既存の「picker内の選択中ノードは常に
     ちょうど1つ」の不変条件を保つ。
   - 利用者が表示3行のいずれかを選ぶと、validated write commandで置換され、追加行は
     消える。追加行のtapはradio semanticsのno-op（既存の再選択no-opと同一）。
   - 保存済み選択が `runtimeSupported` 外（未知・将来binary由来のremoved ID）の場合
     は追加行を出さず、既存のfail-closed表示（どの行も選択中としない）を維持する。
     plannerが受け付けない値を有効な選択肢と同じ視覚・semanticsで見せる新しい
     failure UXは作らない。invalid-stateの回復導線は本Issueの対象外とする。
   - 読み取り失敗（`Unreadable` / `UnsupportedSchema`）も既存どおり何も選択表示せず、
     追加行も出さない（fail-closed表示。既定の発明をしない）。

4. **文言（ラベル・説明文）の確定**（Issue未解決事項への決定。en（`values`）とja
   （`values-ja`）のみ。他localeは本forkのorganizer stringsを保持しないため対象外）:

   - `STABLE_PAGE_TIDY_V2`（表示名変更）: ja「ページごとに整える」/ en「Tidy each screen」。
     説明文でwidget対応を明記する（widget非対応版を隠すことの損失への配慮）:
     ja「アプリを別のページへ移動せず、各ページのすき間を詰めます。ウィジェットも
     同じページ内で整えます。」/ en "Closes gaps on each screen without moving apps to
     other screens. Widgets are tidied within their screen as well."
   - `BOTTOM_REGION_V1`（ja表示名のみ変更）: ja「下半分に集める」（従来「下側半分に
     まとめる」）。名称・説明文（上部を余白として残す旨）はen既存のまま。
   - `STABLE_PAGE_TIDY_V1`（非表示側の名情報。選択済み表示とrun面summaryでのみ使用）:
     ja「ページ内で隙間を詰める（ウィジェットは移動しません）」/ en
     "Tidy each screen (widgets stay in place)"。`STABLE_PAGE_TIDY_V2` の新名称と
     衝突・紛らしくならないよう、widget不動であることを名に入れて区別する
     （V1を選んでいた利用者の意図の可視性を保つ）。説明文は既存のまま。
   - `CANONICAL_PAGE_COMPACT_V1`、その他の非表示strategy（`BOTTOM_FIRST_V1/V2`、
     `GLOBAL_COMPACT_V1/V2`、`CATEGORY_CONTIGUOUS_V1`）の名称・説明文は変更しない。
     いずれも表示3行と衝突しないためである。
   - run面summary等のpicker以外のstrategy表示（`strategyDisplayName` 使用箇所）は
     同一関数を共有するため、名称変更が自動で一貫適用される。専用の表示変更コードは
     追加しない。

5. **契約不変の明示**:
   - catalog・`LayoutStrategyCatalog.runtimeSupported`・bundle digest・`StrategyId`
     は変更しない（ADR-0012「A behavior change is a new ID」の領域に触れない）。
     ADR-0012はbundle digest内でruntime-supported setを宣言する契約であり、表示の
     絞り込みはbundle外のUI判断であるため、ADR-0012の変更は不要（Issue scope 4の
     確認結果）。
   - 書込みは `LayoutStrategySelectionAccess.select`（write-timeにcatalog全体で検証）と
     `StrategyWriteArbiter` 経由のまま。表示を絞っても書込み検証はcatalog全体に対して
     行われるため、非表示strategyの保存済み選択は引き続き有効（spec 182 AC-3b/AC-7不変）。
   - 保存済み選択のmigration・置換はしない（fail-closed読み契約は不変）。
   - 実装PRで、次の3つのaccepted specへ、本件による表示契約の改訂（意図curated
     subsetを表示）を #368 と同じ形式のamendment注記＋change historyへ記録する:
     - **spec 182**: 「only strategies in the active bundle's runtime-supported set are
       offered」箇所の表示側の読み替え（書込み検証・fail-closed契約は不変）。
     - **spec 283**: Non-goalsの「picker の候補集合・順序・section 表題の変更」を
       本Issueに限って解除し、selected affordance契約（selected 1行の視覚判別）を
       curated 3+1行構成へ読み替える。
     - **spec 368**: T-05が受けるpickerの説明（runtime-supported catalogのradio
       group）をcurated表示へ同期する。

## Non-goals

- strategyのcatalog・bundle digest・`StrategyId` の変更（V1の削除、新strategy追加）。
- 保存済み選択のmigration・黙示的な別strategyへの置換。
- 方法選択面（run内の「このまま整理」/「AIに相談」）の扱い（#443）。
- strategyごとの挙動の品質改善（`BOTTOM_REGION_V1` のevidence欠如は #413）。
- 非表示strategyを再選択可能にする導線（「詳細な選択肢を開く」等の追加UI）。
- run面へのstrategy読み取り専用表示の新設（spec 368が明示的に撤去済み）。

## Behavior scenarios

### Scenario: 初回表示は意図ごとの3択

Given 選択storeに保存がなく（first-run）、bundleが読み取り可能である
When 利用者がT-05（整理方針）を開く
Then pickerは「標準コンパクト」「ページごとに整える」「下半分に集める」の3行だけを
この順でcomposeし、既定「標準コンパクト」行が選択中として示され、他のstrategy行は
composeされない

### Scenario: 表示3行の選択は既存のvalidated writeでcommitされる

Given T-05が開いておりrun/recovery操作がactiveでない
When 利用者が表示3行のいずれかを選ぶ
Then 選択はRule Managementのvalidated write command（write-time catalog検証つき）で
原子的にpublishされ、選択した行がpicker内で唯一の選択中行になる
And 書込み検証は表示リストではなくcatalog全体に対して行われる

### Scenario: 非表示strategy選択済みなら、変更するまでその行を選択中として表示する

Given 選択storeに、catalogのruntime-supportedに含まれながら表示3つには含まれない
strategy（例: `STABLE_PAGE_TIDY_V1`）が保存済みである
When 利用者がT-05を開く
Then pickerは表示3行に加えて、そのstrategyの行（localized name＋description）を
最後に1行composeし、その行が唯一の選択中行である
When 利用者が表示3行のいずれかを選ぶ
Then 新しい選択がcommitされ、追加行は表示されなくなる
And 変更するまで、整理runは保存済みのそのstrategyで計画する（composition契約不変）

### Scenario: runtime-supported外・読み取り失敗は従来どおりfail-closed表示（zero-write）

Given 選択storeの保存値がruntime-supported外の未知IDであるか、または破損等の理由で
読み取りが `Ready` を返さない
When 利用者がT-05を開く
Then どの行も選択中と表示せず、既定を選択中ともせず、追加行も出さず、いかなる書込みも
行わない（既存のfail-closed表示を維持する）

## Verification

- **実機スクリーンショット**（owner確認。emulatorは補助証跡のみ）:
  - 初回状態でpickerが3行のみであること（終了条件2）。
  - 非表示strategy（例: `STABLE_PAGE_TIDY_V1`）選択済み状態で4行目として選択中表示
    されること、表示3行を選ぶと3行に戻ること（終了条件2）。
  - 選択の維持: 表示3行の1つを選択 → T-05を離れる → 再度T-05を開く → 同じ行が
    唯一の選択中のまま表示されること（終了条件2の「選択の書込み・維持」の観測。
    process restartまでは要求しない。store形式・読み経路は非変更のため、画面再入場
    でのpersistence観測でtier Mとして十分）。
- **instrumentation test**（`tests/organizer-instrumentation/`。picker表面の既存所有
  laneを更新。新規laneは追加しない）:
  - 表示3行の構成・順序・localized名。非表示strategyの行がcomposeされないこと。
  - 非表示strategy選択済み時（`STABLE_PAGE_TIDY_V1` などruntime-supported内）の
    4行目追加・唯一の選択中行・選択後の消失。
  - runtime-supported外の未知IDが保存済みの場合は通常のselected radio行を追加せず、
    選択中表示も出ないこと（既存fail-closed表示の回帰契約）。
  - radio semantics（selectableGroup、親row選択契約、200% font scale）の既存契約を
    3+1行構成へ更新。
  - fail-closed読み取りの既存test（選択表示なし）の維持。
- **読み経路無変更の証明**（終了条件3）: 差分が
  `organizer/rules`・`organizer/integration`・`organizer/application`・上流 `src/` に
  触れないことをdiffで示す。保存済み非表示strategyでrunが計画することは、既存の
  composer seam test（`OrganizationInputComposerTest` の
  `twoValidSelectionsComposeEndToEndWithDistinctRulesIdentities`: `STABLE_PAGE_TIDY_V1`
  保存で `input.rules.organizationStrategy == STABLE_PAGE_TIDY_V1` を検証）が既に所有
  しており、organizer unit gate（`./gradlew testLawnWithQuickstepGithubDebugUnitTest
  --tests 'app.lawnchair.organizer.*' ...`）の再実行で確認する。fail-closed読み
  （`selectionOutsideRuntimeSupportedSetFailsClosedWithoutPlannerFallback`）も既存testが
  所有するため、重複する新規unit testは追加しない。
- **書込み経路無追加の確認**: 変更fileは
  `OrganizerStrategyPreferences.kt`、`values/strings.xml`、`values-ja/strings.xml`、
  上記test群、spec文書のみ（実装時にPR本文へ列挙）。

## Accessibility and localization

- 1つの `selectableGroup` 内のradio行というIssue #218 a11y契約を維持する。追加行も
  group memberであり、TalkBackは行名・選択状態・説明を1nodeとして読み上げる。
- 追加行は1つの新しいfocus/selectable target（1つの論理的な選択肢）として増える。
  維持する契約はspec 283と同じ「1 row = 1 logical option」であり、行内のvisual-onlyな
  child `RadioButton(onClick = null)` が独立したfocus対象を増やさないことである
  （既存の親row選択契約testを3+1行構成へ更新して検証）。
- 200% font scaleで行が折り返し、幅を超えないこと（既存のvisual evidence testを
  3+1行構成へ更新）。
- 対象localeはen/jaのみ（他localeはorganizer strategy stringsを持たない）。ja文言は
  LQA観点で「ページごとに整える」「下半分に集める」の意図差が読み取れることを
  スクリーンショットで確認する。

## Change history

- 2026-10-01: Draft created for #453.
- 2026-10-01: Review revision 1 (PR #492, ChatGPT review
  [#issuecomment-5929627257](https://github.com/nunu1733/NunuLauncher/pull/492#issuecomment-5929627257)):
  追加行の対象をruntime-supported内の非表示strategyへ限定し、未知ID・
  `UnsupportedSchema`/`Unreadable` のfail-closed表示契約を一意化（指摘1）。spec 283を
  同期対象へ追加し、a11y節のfocus対象の記述を「1 row = 1 logical option」契約へ修正
  （指摘2）。実機evidenceへ選択維持の観測手順（再入場で同一行が唯一selected）を追加、
  instrumentation oracleへ未知IDの回帰契約を追加（指摘3）。
- 2026-10-01: Accepted。Re-review Approve（blockingなし、head
  `55a33cc60f9c02073257a83bf5d76420ba5592bb`
  [#issuecomment-5929785395](https://github.com/nunu1733/NunuLauncher/pull/492#issuecomment-5929785395)）。
  非blocking指摘の既存test名の表記修正（余分なハイフン除去）のみ本文へ反映
  （同commentの判断により再review対象外）。
- 2026-10-01: Implemented（実装PR
  [#493](https://github.com/nunu1733/NunuLauncher/pull/493) merge `5755236d68`..`b49d84d7f7`。
  最終確認review
  [Approve](https://github.com/nunu1733/NunuLauncher/pull/493#issuecomment-5933056684)。
  実機（Pixel 9a / Android 17 / ja-JP）証跡:
  [docs/assessment/assets-453-strategy-choice-reduction/](../../docs/assessment/assets-453-strategy-choice-reduction/)。Issue #453 closed。）

[1]: https://github.com/nunu1733/NunuLauncher/issues/453
