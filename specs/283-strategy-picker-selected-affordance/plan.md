# Implementation Plan: 整理ストラテジー選択の現在値を視覚的に判別できるようにする

> Issue: #283
> Spec: [spec.md](./spec.md)
> Status: draft (spec draft 提出時点の plan revision 1。spec 承認まで実装を開始しない)
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

## Design

### Ownership and module boundaries

変更は preferences UI の presentation 層 (`strategyPickerItems` と resource) に限定する。planning / application / rules (selection store) / integration / diagnostics は変更しない。picker は既存どおり display-only であり、選択は Rule Management の validated write command 経由のみ ([spec 182](../182-layout-strategy-catalog/spec.md) Selection contract)。

### Interfaces and seams

- 変更なしの seam: `LayoutStrategySelectionAccess.read/select`、`LayoutStrategySelectionWriteResult`、`BuiltInOrganizerPolicyBundleSource`、`selectableGroup` a11y contract、`testTag("manual-organization-strategy-picker")`。
- 呼び出し側と test は既存どおり同一 seam (`ManualOrganizationPreferences` composable + Compose test rule) を使う。内部実装の個別検証を追加しない。
- data model / identity: `selectedStrategy: StrategyId?` は不変。visual indicator はこの state から単方向に導出され、indicator 専用の state を新設しない。
- control / data flow: 不変 (picker 描画 → 選択 click → write command → `Committed` で state 更新 → recompose)。indicator は再 composition の中で `isSelected` から描かれるため、state と視覚の分離が構造的に発生しない。

### Visual affordance

1. 各 strategy `Row` の先頭 (leading) に Material3 `RadioButton(selected = isSelected, onClick = null)` を追加する。repo 内先例 (`ListPreference` / `FontSelectionPreference`) と同一の pattern であり、Nunu 固有の選択表現を新設しない (spec の Scope 契約)。
2. `onClick = null` を維持する。これにより indicator 自体が click 対象にならず、row 全体の `selectable` が唯一の操作対象であり続ける。clickable でない RadioButton には minimum touch target の拡張が適用されないため、row の hit area も分割しない。
3. row 側の `selectable(selected = isSelected, role = Role.RadioButton)` は変更しない。row の merged semantics には row 自身の `Selected` と、merge された indicator の `Selected` が同値で乗るため、単一ノード・単一状態の読み上げ契約を保つ。`clearAndSetSemantics` による indicator 側の state 抹消など、semantics を黙らせる回避策は導入しない (不要な場合に semantics を壊すリスクの方が大きい)。merge 挙動は instrumentation の semantics 表明で確認する。
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
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt` | indicator の選択一致 / 移動 / 単一選択 / merged semantics / font scale / copy の test 追加・拡張 | AC-1..AC-6, AC-8 の正本 evidence。既存 6 test は無修正で通ること (AC-9) |

production source / build 設定 / dependency / 他 module への変更はなし。

## Testing strategy

- 正本 evidence は既存 instrumentation suite (`StrategyPickerInstrumentationTest`) への追加とする。新しい test seam は作らない ([quality-strategy](../../docs/engineering/quality-strategy.md))。
- 追加観点と method:
  - **選択一致 / fail-closed 維持 (AC-1, AC-6)**: unmerged tree で各行の indicator node を取得し `assertIsSelected` / `assertIsNotSelected`。既存 `firstRunShowsTheBundleDefaultAsTheEffectiveSelection` / `failedReadShowsNoActiveSelection` の scenario に indicator 表明を追加する形が最小である。
  - **選択移動 / 単一選択 / 再選択 no-op (AC-2)**: 未選択行 click → write `Committed` 後に、新旧行の indicator 状態と「選択済み indicator を持つ行が1行だけ」を表明。再選択 click では store snapshot の generation 変化なし + 表示不変を表明。
  - **色非依存 (AC-3)**: indicator の「存在と選択状態」を構造 (semantics / node) で表明し、色比較 assertion に依存しない。併せて light/dark screenshot を evidence とする。
  - **merged semantics (AC-4)**: merged tree で各行が単一の click 対象・単一の `Selected` 状態を持つことを表明し、indicator が独立 focus 対象になっていないことを構造で検出。実機 / emulator での TalkBack 手動確認 (読み上げ回数) を PR に記録する (自動化では読み上げ回数を検証できないため、ここは manual evidence)。
  - **font scale (AC-5)**: `LocalDensity provides Density(1f, fontScale = 2f)` 先例 pattern で全行 name + description の表示を確認。
  - **copy (AC-8)**: canonical description resource が historical note (`The original organizer behavior.` と ja 対応句) を含まないことの表明。resource の pin ではなく「historical note の不在」を検証対象にする。
  - **回帰 (AC-9)**: 既存 6 test 無修正通過。`LayoutStrategySelectionStoreTest` (JVM) も無修正で通ることを PR に記録する。
- 実行: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (JVM gate) は影響しないが、PR 前に通す。instrumentation は API 36 emulator で `StrategyPickerInstrumentationTest` を focused 実行する。
- light / dark screenshot evidence (AC-7) は emulator 手動取得で PR へ添付する。

## Migration and recovery

- schema / rule migration: なし。selection store format、`favorites` DB、preference key に触れない。
- failure / rollback: 実装は単一 commit 系の UI + resource 変更であり、問題時は revert で戻る。永続化変更がないため data recovery の考慮は不要である。
- release rollback / downgrade: 新規状態を持たないため影響なし。

## Compatibility constraints

- Compose / Material3 version 現状で `RadioButton(selected, onClick = null)` は repo 内既存使用実績があり、新 dependency を追加しない。
- `selectableGroup` radio-group 契約 (`StrategyPickerInstrumentationTest.strategyPickerIsWrappedInASelectableGroup`) は回帰防止の正本であり、unmerged tree の testTag 検索が indicator 追加によって壊れないことを実装時に確認する (testTag は group の `Column` に付いたままである)。
- picker の 8 行が1つの非仮想化 `Column` にある現状構成は維持する (spec 182 child 8 の a11y 契約)。indicator 追加で row 高さが増えても契約は変わらないが、scroll assertion を含む既存 test がこれを検出する。

## Risks

- indicator が独立した accessibility node として露出し、重複読み上げ / 重複 focus が発生する (AC-4 失敗) → `onClick = null` 先例遵守 + merged tree 表明 + TalkBack 手動確認で検出する。発生時は spec/plan に戻り、semantics を壊さない形へ修正する。
- font scale 極大で indicator と text の行内配置が崩れる → 200% instrumentation と screenshot で検出。
- copy 変更による既存 test の文字列依存の破れ → 現状 `strings.xml` の canonical description に依存する test は見当たらない (実装時に `grep` で再確認し、あれば same-PR で修正)。

## Incremental implementation order

1. Copy fix (ja/en canonical description) + historical-note 不在 test。単独で merge 可能な最小縦切り。
2. Leading RadioButton の追加 + indicator 状態 / 移動 / 単一選択 / merged semantics の instrumentation 追加。
3. 200% font scale instrumentation + light/dark screenshot evidence + TalkBack 手動確認の記録。

各 step で `./gradlew spotlessCheck` と focused instrumentation を通す。

## Explicitly unverified areas

- TalkBack 実機での実際の読み上げ (重複の有無) は本 plan 作成時点で未検証。merged semantics の構造表明が近似であり、最終判断は manual evidence とする。
- `RadioButton(onClick = null)` の merge 後 semantics が行ノード上で `Selected` を正確に1つに保つ挙動は、repo 内先例 (`ListPreference` 等) と Compose の既知挙動に基づく判断であり、baseline 上で runtime 実行していない。
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
