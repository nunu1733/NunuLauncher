---
issue: "#283"
status: accepted
requirements:
  - FR-016
  - NFR-009
risk: []
updated: 2026-09-13
---

# 整理ストラテジー選択の現在値を視覚的に判別できるようにする

> Status: accepted。本specは [Issue #283](https://github.com/nunu1733/NunuLauncher/issues/283) の実機確認に基づく user-visible defect を拘束契約として定義する。ユーザー承認を受け、implementation-ready とする。

## Problem

Organizer の手動run表面にある strategy picker ([spec 182](../182-layout-strategy-catalog/spec.md) child 8) は、TalkBack / Compose semantics としては各候補の選択状態を正しく公開している。しかし視覚表現としては、各行が strategy name と description の `Text` のみで構成され、persistent visual indicator (radio indicator、check mark、selected container treatment のいずれも) を持たない。

source上の現状 (main `f9afd8bfde`、2026-09-12 確認):

- `strategyPickerItems` の各行は `Modifier.fillMaxWidth().selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(id) })` の `Row` で、中身は name (`bodyLarge`) と description (`bodyMedium`) の2つの `Text` だけである ([ManualOrganizationPreferences.kt:679-725](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt))。`isSelected` は semantics と再選択 no-op 判定に使われる一方、row の視覚表現には一切投影されない。
- そのため sighted user には、picker 初期表示時に effective selected strategy ([spec 182](../182-layout-strategy-catalog/spec.md): persisted selection、absent なら bundle default。store 読取失敗時は fail-closed で非選択表示) がどれか、また選択操作後に選択が移ったかが、画面を見ても判別できない。

TalkBack では選択状態が伝わるため、accessibility semantics と visual presentation の情報量が逆転している。ストラテジーの切り替えは Organizer の結果を大きく変える主要設定 (FR-016) であり、「現在値の確認」「切り替え後の確認」「preview 結果と選択中 strategy の対応付け」といった基本操作でユーザーに selection state の推測を強いている。

同じ画面の copy 問題: canonical strategy (`CANONICAL_PAGE_COMPACT_V1`) の description が、ja「各ページを左上から詰めます。従来の整理動作です。」、en "Fills each screen from the top-left. The original organizer behavior." であり、後半が実装・製品履歴の説明になっている。strategy を選ぶユーザーにとって結果を予測するための情報ではない ([strings.xml:1011](../../lawnchair/res/values/strings.xml)、[values-ja/strings.xml:191](../../lawnchair/res/values-ja/strings.xml))。

## Outcome

picker を見ただけで、effective selected strategy が一意に判別できる。選択操作の成功後、視覚的な選択表示が新しい strategy へ移り、TalkBack の単一論理ノード契約 ([spec 182](../182-layout-strategy-catalog/spec.md) child 8 の a11y contract) と既存の selection persistence / planner semantics は変化しない。canonical strategy の description は observable behavior の説明に限定される。

## Scope

- `ManualOrganizationPreferences` の strategy picker presentation に、selected / unselected を色以外の手段でも区別する persistent visual affordance を追加する。既存の Material3 / Lawnchair preference の radio 選択 pattern (`ListPreference`、`FontSelectionPreference` が採用する `RadioButton(selected = …, onClick = null)` + 選択可能な row) に倣い、Nunu 固有の選択表現は新設しない。
- canonical strategy の ja/en description から実装履歴説明 (`従来の整理動作` / `The original organizer behavior.`) を除去する。
- 既存 semantics / `selectableGroup` / keyboard / Switch Access / large-font 挙動の回帰防止。
- light / dark の代表状態で selected / unselected が視覚的に区別できることの evidence 残置。

## Non-goals

- planner、composer、application、selection store の semantics 変更。`LayoutStrategySelectionModule` の read/write contract、fail-closed 規則、Rule Management write command 経由の選択 ([spec 182](../182-layout-strategy-catalog/spec.md) Selection contract) は不変である。
- effective selection の導出規則の変更 (absent 選択時に bundle default を表示する規則、読取失敗時に非選択表示とする規則の維持)。
- 選択状態の新規永続化、新規 preference、新規 diagnostics event。
- picker の候補集合・順序・section 表題・run 表面への配置の変更。
- preview 表面の strategy identity 表示 (`manual_organization_preview_strategy`) や consequence counts の変更 ([spec 194](../194-plan-preview-seam/spec.md) / [spec 195](../195-organizer-confirmation-change-list/spec.md) / [spec 235](../235-widget-strategy-placement/spec.md))。
- 選択時の active run の dismiss + 再計画の挙動変更 ([spec 182](../182-layout-strategy-catalog/spec.md)、現行 `onStrategySelected` の契約維持)。
- canonical strategy 以外の strategy description の文言修正 (具体問題が確認されていないため)。ja/en 以外の locale の新規翻訳。
- 色覚以外の視覚様式 (animation、shape 変化の常時演出) を用いた新表現の導入。

## Domain language

追加なし。[CONTEXT.md](../../CONTEXT.md) の既存語彙 (レイアウトストラテジー、effective selection、runtime-supported catalog) のみを使用する。

## Behavior scenarios

### Scenario: 初期表示で effective selected strategy が視覚的に一意に判別できる

Given strategy picker が表示されており、selection store の読取が成功している
When picker が初期描画される
Then runtime-supported catalog のうち、effective selection (persisted selection、absent なら bundle default) に対応する1行だけが persistent visual indicator により選択済みとして表示される
And 他の行は選択済みに見えない
And 表示される選択行は Compose semantics の `Selected` 状態と同一の truth から導出される (`selectedStrategy` と indicator の分離が発生しない)

### Scenario: 選択成功後、視覚的選択が新しい strategy へ移る

Given picker が表示されている
When ユーザーが未選択の行を選択し、write command が `Committed` を返す
Then visual indicator が新しい strategy の行へ移り、以前の行は未選択の視覚表現へ戻る
And 同時に2つ以上の行が選択済みに見える状態は発生しない
And この間も semantics の `Selected` 状態と visual indicator が一致する

### Scenario: 再選択は視覚的にも no-op である

Given effective selection が `S` である
When ユーザーが `S` の行を再選択する
Then write も run restart も発生せず ([spec 182](../182-layout-strategy-catalog/spec.md) radio semantics)、視覚表現も変化しない

### Scenario: 読取失敗時は視覚的にも選択を表示しない (fail-closed維持)

Given selection store の読取が失敗している
When picker が描画される
Then すべての行が未選択の視覚表現で表示される
And 既存の fail-closed 契約 (composer と一致する非選択表示) が視覚表現によって変質しない

### Scenario: 選択状態の判別が色だけに依存しない

Given light または dark theme で picker が表示されている
When selected 行と unselected 行を比較する
Then selected 行には selected mark を含む RadioButton の形状が見え、unselected 行との差を色 (背景・文字色) だけでなく形状として判別できる
And 色覚多様性の観点で、選択判別が特定の色の組の識別に依存しない

### Scenario: TalkBack は1行を1つの論理的な選択肢として読み上げる

Given strategy picker が `selectableGroup` semantics を持つ1つの radio group として実装されている ([spec 182](../182-layout-strategy-catalog/spec.md) child 8)
When TalkBack ユーザーが strategy 行にフォーカスする
Then strategy name + description + selected state が1つの論理ノードとして読み上げられる
And 追加された visual indicator が `row + nested control` のような重複フォーカス / 重複読み上げ (行と indicator が別ノードとして2回読まれる等) を生まない
And 行全体の選択操作が維持され、indicator 自体が独立した操作対象にならない

### Scenario: keyboard / Switch Access / large-font の既存挙動を維持する

Given picker が表示されている
When keyboard / DPAD / Switch Access で行を移動し選択する、または 200% font scale で描画する
Then 既存の traversal と選択操作の到達性が変化しない
And 200% font scale で strategy name / description が折り返して読めるまま (clipping なし) であり、indicator の存在が text の reflow を壊さない

### Scenario: canonical strategy の description が結果の説明に限定される

Given canonical strategy の行が表示されている
When ja または en の description を読む
Then `従来の整理動作` / `The original organizer behavior.` のような実装・製品履歴の説明が含まれない
And description は選択した場合に何が起こるか (observable behavior) を説明する

### Scenario: planner / application / selection-store semantics に変更がない

Given 上記のいずれかの視覚・copy 変更が適用されている
When 既存の strategy 選択 → preview → apply → recovery の contract test 群を実行する
Then selection store の generation / digest / fail-closed 規則、composer の読取規則、planner の結果が変化しない
And 既存の `StrategyPickerInstrumentationTest` の契約 (runtime-supported のみ提示、default-as-effective、fail-closed、selectableGroup、write command 経由) が回帰なしで維持される

## Data and state

- 新規 state / 永続化なし。visual indicator は既存 `selectedStrategy` (`StrategyId?`)、すなわち semantics と同一の状態から一方向に導出される。
- migration / backup / restore: 影響なし。DB、schema、preference key、selection store format を変更しない。
- layout を扱わない: 本変更は organizer-owned state を一切書き換えない。

## Permissions, privacy, and security

None。新規 permission、外部送信、sensitive data は存在しない。diagnostics への新規出力もない。

## Accessibility and localization

- 選択状態は NFR-009 および [spec 182](../182-layout-strategy-catalog/spec.md) Accessibility and localization の契約 (TalkBack の label/state/selection announcement、Switch Access / keyboard traversal、200% font scaling) を満たしたまま視覚化される。視覚化により accessibility semantics を退化させてはならない。
- selection semantics の唯一の truth は既存の parent row の `selectable(selected = isSelected, role = Role.RadioButton, ...)` とする。追加する child `RadioButton(selected = isSelected, onClick = null)` は visual-only indicator であり、child 側に `Selected` / selectable / click / focus semantics があることを前提にしない。child 側へ独自 semantics を追加してこの契約を作らない。
- selected / unselected の判別は色非依存である (color contrast に加え、indicator の存在自体が情報として機能する)。
- 追加 indicator は既存の Material3 / Lawnchair preference radio pattern に倣い、row 単位の merged semantics を保つ。indicator が独立した selectable / click / focus 対象・読み上げ対象になる実装は本specの失敗とみなす。
- 文言修正は [spec 161](../161-japanese-ui-copy-lqa/spec.md) の日本語 UI コピー規約・対訳規約に従う。`organization_strategy_canonical_description` の ja/en を対応させ、historical note を除去した observable behavior 説明に絞る。en 側も同一の意味変更要件を満たす。
- light / dark 両 theme の代表状態で、selected / unselected の視覚区別を screenshot evidence として PR に残す。

## Acceptance criteria

- [ ] AC-1: picker 初期表示時、effective selected strategy が persistent visual indicator により一意に判別でき、semantics の `Selected` 状態と同一の truth から導出されている。
- [ ] AC-2: 選択成功後、indicator が新しい strategy へ移り、複数行が選択済みに見える状態が発生しない。再選択は視覚的にも no-op である。
- [ ] AC-3: selected / unselected の判別が、light / dark theme の実画面で selected mark の形状差として確認でき、色だけに依存しない。
- [ ] AC-4: row 全体の選択操作が維持され、indicator が独立した focus / 操作対象にならず、TalkBack は name + description + selected state を1つの論理ノードとして読む (重複読み上げなし)。
- [ ] AC-5: 既存 `selectableGroup` semantics、Switch Access / keyboard traversal、200% font scale 挙動が回帰していない。200% font scale の代表画面で name / description の折り返し、indicator との非重複、clipping なしを screenshot または bounds 検査で確認する。
- [ ] AC-6: selection store の fail-closed 表示規則 (読取失敗時に非選択表示) と absent 時の default-as-effective 表示規則が視覚表現でも維持される。
- [ ] AC-7: light / dark の代表状態で selected / unselected が視覚的に区別できる evidence が PR に残る。
- [ ] AC-8: canonical strategy の ja/en description から実装履歴説明が除かれ、observable behavior の説明になる。
- [ ] AC-9: planner / application / selection-store / composer の semantics と既存 picker 契約 test 群に回帰がない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `StrategyPickerInstrumentationTest` 拡張: parent row の `Selected` state が effective selection (default-as-effective を含む) と一致。visual indicator の同一性は screenshot oracle で補助確認 |
| AC-2 | instrumentation: 選択後の parent row の `Selected` state 移動 + 単一選択、再選択 no-op の回帰。indicator の実際の移動は screenshot / manual visual evidence で確認 |
| AC-3 | 正本は visual oracle: light / dark theme の selected / unselected 行を含む screenshot で selected mark の形状差を目視確認する。instrumentation は parent row の semantics/state 整合性だけを補助的に表明し、indicator の見た目を semantics assertion だけで証明しない |
| AC-4 | instrumentation: parent row が唯一の selection semantics truth であり、各 row が単一の click target で、child visual-only RadioButton が独立 selectable / focus target になっていないことを表明する。実機 / emulator での TalkBack 手動確認 (読み上げ回数) も記録する |
| AC-5 | instrumentation: `selectableGroup` 存在の既存表明維持、200% font scale (`LocalDensity` fontScale = 2f pattern、[CategoryOverridePreferencesInstrumentationTest](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/CategoryOverridePreferencesInstrumentationTest.kt)) で semantics/state を確認し、代表 screenshot で text の reflow、indicator との非重複、clipping なしを確認する |
| AC-6 | instrumentation: 既存 `failedReadShowsNoActiveSelection` / `firstRunShowsTheBundleDefaultAsTheEffectiveSelection` を parent row の state で拡張し、visual indicator の fail-closed / default-as-effective 表示は screenshot oracle で確認 |
| AC-7 | emulator screenshot (light / dark × selected / unselected) を PR へ添付 |
| AC-8 | instrumentation または unit: canonical description resource が historical note を含まないことの表明。ja/en 対応の目視確認を PR へ記録 |
| AC-9 | 既存 `StrategyPickerInstrumentationTest` + selection store unit test ([LayoutStrategySelectionStoreTest](../../tests/unit/app/lawnchair/organizer/rules/LayoutStrategySelectionStoreTest.kt)) の無修正通過 (contract 変更がないことの回帰証拠) |

## Open questions

- (実装時に確定、非 blocking) indicator の配置 (row の先頭 / 末尾) は既存 Lawnchair radio 選択 pattern の先例から plan で決める。spec が拘束するのは「persistent・色非依存・単一 truth・row 全体選択の維持」までである。
- (実装時に確定、非 blocking) canonical description の最終文言は [spec 161](../161-japanese-ui-copy-lqa/spec.md) の規約に沿って plan / 実装 PR で確定する。spec は「historical note を含まない observable behavior 説明」のみを拘束する。

## Change history

- 2026-09-13: Draft created for #283 (baseline main `f9afd8bfde`, 2026-09-12 UTC 取得)。
- 2026-09-13: Spec/plan review の Request changes (P1: `RadioButton(onClick = null)` は visual-only、P2: AC-3 の visual oracle、P2: 200% font-scale evidence) を反映。selection semantics の唯一の truth を parent row に固定し、視覚・font-scale evidence を screenshot または bounds 検査で拘束した。
- 2026-09-13: ユーザー承認により spec を `accepted` に遷移し、実装を開始する。
