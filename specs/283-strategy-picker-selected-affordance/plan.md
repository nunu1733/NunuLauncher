# Implementation Plan: 整理ストラテジー選択の現在値を視覚的に判別できるようにする

> Issue: #283
> Spec: [spec.md](./spec.md)
> Status: draft (spec review の Request changes 対応、plan revision 2。spec 承認まで実装を開始しない)
> Baseline: main `f9afd8bfde121932c0c8ed965225d52a84d86ab4` (2026-09-12 UTC 取得)

## Current evidence

すべて source 読み取りによる確認済み (baseline 上、2026-09-12 確認)。推測は「未確認領域」節に分離する。

- picker 本体は `strategyPickerItems` ([ManualOrganizationPreferences.kt:679-725](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt))。単一 `Column` に `testTag("manual-organization-strategy-picker")` と `semantics { selectableGroup() }` を付与し、catalog 各行を `Modifier.fillMaxWidth().selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(id) }).padding(horizontal = 16.dp, vertical = 8.dp)` の `Row` + `Column` (name `bodyLarge` / description `bodyMedium` の `Text` のみ) で描く。visual indicator は一切ない。
- 選択状態の唯一の truth は `selectedStrategy: StrategyId?` ([ManualOrganizationPreferences.kt:172-179](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt))。読取成功時は persisted selection、absent なら bundle default (default-as-effective)。読取失敗時は `null` で fail-closed (非選択表示)。
- 書込みは `LayoutStrategySelectionModule.store(context).select(id)` ([ManualOrganizationPreferences.kt:180-199](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt))。再選択は no-op、`Committed` 時のみ `selectedStrategy` 更新 + active run の dismiss/start。この制御 flow は本 plan で変更しない。
- catalog は `BuiltInOrganizerPolicyBundleSource.readActive()` → `bundle.layoutStrategies.runtimeSupported` (8 strategy、[BuiltInOrganizerPolicyBundleSource.kt:47-58](../../lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt))。
- copy の現状: `organization_strategy_canonical_description` が en "Fills each screen from the top-left. The original organizer behavior." ([strings.xml:1011](../../lawnchair/res/values/strings.xml))、ja「各ページを左上から詰めます。従来の整理動作です。」([values-ja/strings.xml:191](../../lawnchair/res/values-ja/strings.xml))。後半が historical note である。
- 既存 test surface: [StrategyPickerInstrumentationTest.kt](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt)。6 test: 8行の localized name 提示 (scroll込み)、default-as-effective の `assertIsSelected`、radio semantics + selectableGroup、fail-closed 非選択表示、`SelectableGroup` semantics の存在 (unmerged tree で testTag 検索)、write command 経由の選択 (`UnsupportedStrategy` 拒否込み)。
- 既存 radio 選択 row の repo 内先例: `ListPreference` ([ListPreference.kt:109-115](../../lawnchair/src/app/lawnchair/ui/preferences/components/controls/ListPreference.kt)) と `FontSelectionPreference` ([FontSelectionPreference.kt:227-234](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/FontSelectionPreference.kt)) が、選択可能な row の中に `RadioButton(selected = …, onClick = null)` を置く pattern を採用する。`RadioButton` は `androidx.compose.material3.RadioButton`。
- 200% font scale の instrumentation 先例: `LocalDensity provides Density(1f, fontScale = 2f)` ([CategoryOverridePreferencesInstrumentationTest.kt:89](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/CategoryOverridePreferencesInstrumentationTest.kt))。
- re-entry check: `origin/main` は `a4d7a264570db9cdc936cd2199bb9af92f574889` (2026-09-13 UTC 取得) まで進んでいるが、#283 の対象 surface (picker source / ja-en resource / picker test) には `f9afd8bfde` 以降の差分がないため、既存の分析ベースを維持する。

## Design

### Ownership and module boundaries

変更は preferences UI の presentation 層 (`strategyPickerItems` と resource) に限定する。planning / application / rules (selection store) / integration / diagnostics は変更しない。picker は既存どおり display-only であり、選択は Rule Management の validated write command 経由のみ ([spec 182](../182-layout-strategy-catalog/spec.md) Selection contract)。

### Interfaces and seams

- 変更なしの seam: `LayoutStrategySelectionAccess.read/select`、`LayoutStrategySelectionWriteResult`、`BuiltInOrganizerPolicyBundleSource`、`selectableGroup` a11y contract、`testTag("manual-organization-strategy-picker")`。
- 呼び出し側と test は既存どおり同一 seam (`ManualOrganizationPreferences` composable + Compose test rule) を使う。内部実装の個別検証を追加しない。
- data model / identity: `selectedStrategy: StrategyId?` は不変。visual indicator はこの state から単方向に導出され、indicator 専用の state を新設しない。
- selection semantics ownership: parent row の既存 `selectable(selected = isSelected, role = Role.RadioButton, ...)` が唯一の selection semantics truth である。child `RadioButton(selected = isSelected, onClick = null)` は visual-only indicator として扱い、child 側に `Selected` semantics があることを前提にしない。
- control / data flow: 不変 (picker 描画 → 選択 click → write command → `Committed` で state 更新 → recompose)。indicator は再 composition の中で `isSelected` から描かれるため、state と視覚の分離が構造的に発生しない。

### Visual affordance

1. 各 strategy `Row` の先頭 (leading) に Material3 `RadioButton(selected = isSelected, onClick = null)` を追加する。repo 内先例 (`ListPreference` / `FontSelectionPreference`) と同一の pattern であり、Nunu 固有の選択表現を新設しない (spec の Scope 契約)。
2. `onClick = null` を維持する。これにより indicator 自体が click 対象にならず、row 全体の `selectable` が唯一の操作対象であり続ける。clickable でない RadioButton には minimum touch target の拡張が適用されないため、row の hit area も分割しない。
3. row 側の `selectable(selected = isSelected, role = Role.RadioButton)` は変更しない。parent row が唯一の selection semantics truth であり、child `RadioButton(onClick = null)` に selectable / `Selected` / click / focus semantics があることを前提にしない。child 側へ `clearAndSetSemantics` や独自 semantics を追加してテストを成立させる回避策も導入しない。instrumentation では parent row の `Selected`、単一 click target、余分な selectable / focus target がないことを確認し、実機 / emulator の TalkBack で重複読み上げがないことを確認する。
4. `Column` の text との垂直配置は `Row` の alignment 調整で解決する (実装時に 200% font scale evidence で折り返し支障がないことを確認。どの alignment でも spec の契約は不変)。
5. 採用しない代替: (a) selected 行の container 色 / background treatment のみ — 色非依存契約 (AC-3) を満たす保証がなく、Lawnchair preference に前例のない新表現になる。(b) 行末 check icon — repo 内の radio 選択先例と異なり、radio role semantics と視覚記号の対応が崩れる。(c) `Checkbox` — role の意味論が不正確。

### Copy fix

- `lawnchair/res/values/strings.xml` の `organization_strategy_canonical_description`: "The original organizer behavior." を除去し、結果説明に限定する (作業案: "Fills each screen from the top-left.")。
- `lawnchair/res/values-ja/strings.xml` の同 resource: 「従来の整理動作です。」を除去する (作業案: 「各ページを左上から詰めます。」)。
- 最終文言は実装 PR 内で [spec 161](../161-japanese-ui-copy-lqa/spec.md) の日本語 UI コピー規約・対訳規約に従って確定し、PR に ja/en 対応の目視確認を記録する。canonical 以外の description は変更しない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `strategyPickerItems` の行構成に leading `RadioButton(selected, onClick = null)` を追加 (`androidx.compose.material3.RadioButton` import)。`selectable` / `selectableGroup` / state / write flow は無修正 | presentation の唯一の着地点。seam・truth を変えない |
| `lawnchair/res/values/strings.xml` | `organization_strategy_canonical_description` から historical note を除去 | spec AC-8。en 側の意味変更 |
| `lawnchair/res/values-ja/strings.xml` | 同上 (ja) | spec AC-8。[spec 161](../161-japanese-ui-copy-lqa/spec.md) 対訳規約 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt` | parent row の選択一致 / 移動 / 単一選択、単一 click target・余分な selectable / focus target がないこと、font scale / copy の test 追加・拡張 | AC-1..AC-6, AC-8 の semantics/state evidence。視覚表現そのものは screenshot oracle とし、既存 6 test は無修正で通ること (AC-9) |

production source / build 設定 / dependency / 他 module への変更はなし。

## Testing strategy

- 正本 evidence は既存 instrumentation suite (`StrategyPickerInstrumentationTest`) への追加とする。新しい test seam は作らない ([quality-strategy](../../docs/engineering/quality-strategy.md))。
- 追加観点と method:
  - **選択一致 / fail-closed 維持 (AC-1, AC-6)**: parent row (name + description の merged node) に `assertIsSelected` / `assertIsNotSelected` を行う。既存 `firstRunShowsTheBundleDefaultAsTheEffectiveSelection` / `failedReadShowsNoActiveSelection` の scenario に parent row の表明を追加する形が最小であり、child indicator node の selected semantics は検証対象にしない。
  - **選択移動 / 単一選択 / 再選択 no-op (AC-2)**: 未選択 row click → write `Committed` 後に、新旧 parent row の `Selected` 状態と「selected row が1つだけ」を表明。再選択 click では store snapshot の generation 変化なし + parent row の表示 state 不変を表明する。indicator の実際の移動は screenshot / manual visual evidence で確認する。
  - **色非依存 (AC-3)**: 正本は visual oracle とし、light / dark theme の screenshot で selected mark の形状 (filled mark と unselected ring) が selected / unselected の差として実見できることを確認する。instrumentation は parent row の semantics/state 整合性だけを補助し、indicator の存在や node semantics だけで視覚表現を証明しない。
  - **merged semantics (AC-4)**: merged tree で各 row が parent row の単一 click target と単一の `Selected` state を持つこと、child visual-only RadioButton が独立 selectable / focus target になっていないことを構造で検出する。実機 / emulator での TalkBack 手動確認 (読み上げ回数) を PR に記録する (自動化では読み上げ回数を検証できないため、ここは manual evidence)。
  - **font scale (AC-5)**: `LocalDensity provides Density(1f, fontScale = 2f)` 先例 pattern で semantics/state と row の存在を確認し、代表的な 200% font-scale screenshot で name / description の折り返し、indicator との非重複、clipping なしを確認する。`assertIsDisplayed()` のみを visual oracle としない。
  - **copy (AC-8)**: canonical description resource が historical note (`The original organizer behavior.` と ja 対応句) を含まないことの表明。resource の pin ではなく「historical note の不在」を検証対象にする。
  - **回帰 (AC-9)**: 既存 6 test 無修正通過。`LayoutStrategySelectionStoreTest` (JVM) も無修正で通ることを PR に記録する。
- 実行: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (JVM gate) は影響しないが、PR 前に通す。instrumentation は API 36 emulator で `StrategyPickerInstrumentationTest` を focused 実行する。
- light / dark screenshot evidence (AC-3, AC-7) は emulator 手動取得で PR へ添付し、selected mark の形状差が見える crop を含める。200% font-scale の代表 screenshot (少なくとも1枚、AC-5) も同じ evidence packet に含める。

## Migration and recovery

- schema / rule migration: なし。selection store format、`favorites` DB、preference key に触れない。
- failure / rollback: 実装は単一 commit 系の UI + resource 変更であり、問題時は revert で戻る。永続化変更がないため data recovery の考慮は不要である。
- release rollback / downgrade: 新規状態を持たないため影響なし。

## Compatibility constraints

- Compose / Material3 version 現状で `RadioButton(selected, onClick = null)` は repo 内既存使用実績があり、新 dependency を追加しない。
- `selectableGroup` radio-group 契約 (`StrategyPickerInstrumentationTest.strategyPickerIsWrappedInASelectableGroup`) は回帰防止の正本であり、unmerged tree の testTag 検索が indicator 追加によって壊れないことを実装時に確認する (testTag は group の `Column` に付いたままである)。
- picker の 8 行が1つの非仮想化 `Column` にある現状構成は維持する (spec 182 child 8 の a11y 契約)。indicator 追加で row 高さが増えても契約は変わらないが、scroll assertion を含む既存 test がこれを検出する。

## Risks

- visual-only indicator が独立した selectable / click / focus target として露出し、重複読み上げ / 重複 focus が発生する (AC-4 失敗) → `onClick = null` 先例遵守 + parent row を唯一の selection semantics truth とする構造表明 + TalkBack 手動確認で検出する。発生時は spec/plan に戻り、child に独自 semantics を足して隠すのではなく、row 単位の契約を壊さない形へ修正する。
- font scale 極大で indicator と text の行内配置が崩れる → 200% instrumentation と screenshot で検出。
- copy 変更による既存 test の文字列依存の破れ → 現状 `strings.xml` の canonical description に依存する test は見当たらない (実装時に `grep` で再確認し、あれば same-PR で修正)。

## Incremental implementation order

1. Copy fix (ja/en canonical description) + historical-note 不在 test。単独で merge 可能な最小縦切り。
2. Leading RadioButton の追加 + indicator 状態 / 移動 / 単一選択 / merged semantics の instrumentation 追加。
3. 200% font scale instrumentation + representative 200% screenshot + light/dark selected/unselected screenshot evidence + TalkBack 手動確認の記録。

各 step で `./gradlew spotlessCheck` と focused instrumentation を通す。

## Explicitly unverified areas

- TalkBack 実機での実際の読み上げ (重複の有無) は本 plan 作成時点で未検証。merged semantics の構造表明が近似であり、最終判断は manual evidence とする。
- child `RadioButton(onClick = null)` が runtime の semantics tree にどのような非操作 node として現れるかは、実装前のため未確認。ただし本 plan は child 側の `Selected` semantics や merge 結果を前提にせず、parent row の `Selected` を唯一の truth として検証する。
- 最終 ja/en 文言 (spec 161 review 結果次第)。
- light/dark の視覚区別の実見 (implementation 時の evidence で確定)。

## Documentation updates

- [ ] spec status/history (draft → accepted → implemented)
- [ ] CONTEXT.md (変更なし — domain language 追加なし)
- [ ] DESIGN.md (変更なし — module / seam 変更なし)
- [ ] ADR (不要 — 既存 pattern の採用であり、変更困難な新判断はない)
- [ ] AGENTS.md (変更なし — workflow / verified command 変更なし)
- [ ] `requirements.md` (変更なし — FR-016 / NFR-009 は既存。status 更新も本 issue では行わない)

## Execution checklist

- [ ] 現行挙動の再現 (実機確認は Issue #283 に記録済み。emulator で picker の視覚無差別を確認)。
- [ ] Missing behavior に対して失敗する test を先に追加。
- [ ] 最小実装 (copy → indicator → evidence の順)。
- [ ] migration/recovery: 該当なし (永続化変更なし) を明記して検証省略。
- [ ] spotlessCheck + organizer JVM gate + focused instrumentation + light/dark evidence 完成。
- [ ] PR evidence (screenshot / TalkBack 手動確認) と残 risk を記録。

## Review response history

- 2026-09-13: Spec/plan review の Request changes (P1: child `RadioButton(onClick = null)` の semantics 前提、P2: AC-3 visual oracle、P2: 200% font-scale oracle) に対応。plan revision 2 として、parent row を唯一の selection semantics truth、child を visual-only と明記し、light/dark と representative 200% screenshot を必須 evidence にした。
