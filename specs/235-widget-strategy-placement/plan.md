# Implementation Plan: Strategy-aware fixed-span widget placement

> Issue: #235
> Spec: [spec.md](./spec.md)
> Status: draft (spec未承認のため実装不可。phase-1 reviewとowner承認後に実装開始する)
> Baseline evidence: `origin/main` @ `3e113302b96e0236a9f0a0682b676fa347215421` (2026-09-12確認、re-anchor)

## Current evidence (確認済み現行実装)

実装開始時に再検証すること。2026-09-12 re-anchor時点の現行コード。

### Widgetはpreservation層で固定される

- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:373-403` — `determinePreservation` が `item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET -> PreserveReason.WIDGET` を返す (widget分岐は `:392`)。precedenceは `RESERVED_REGION` > `LOCKED` > `UNAVAILABLE_TARGET` > `DOCK` > `WIDGET` > `APP_PAIR` > `LEGACY_SHORTCUT` > `NON_TARGET` > `STRUCTURAL`。
- `PlanningPlacement.place` (同file, lines 42-50) はpreservation理由を持つitemのworkspace占有をstrategy実行前に `allocator.markOccupied` で印付ける。よってwidgetは全strategyでoccupancy constraintであり、`FullRunContext.movableItems` に現れない。
- `appendPreservedPlacements` (`FullRunExecution.kt:79-93`) が全strategy共通のtailとして `Preserved{WIDGET}` を出力する。

### Run mode (spec 228導入後の現行)

- `RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` の3値 (`OrganizationInput.kt:12`)。
- `ScopeComposedOrganization` は選択strategyの `placeFullRun` を先に実行し、その後 `appendCandidatePlacements` が同一strategyの `createsFolders`/`pageScope` 下で候補を追加する (`PlanningPlacement.kt:115-159`)。候補が置けない場合は `UnplacedReason.STRATEGY_SCOPE_FULL` の `unplaced` 行として報告される (silent dropなし)。widget対応strategy選択時、widget再配置はこのfull-run相位で走る (spec D-5)。
- `IncrementalPlacement` は `incrementalCandidateStrategy` (pre-182 canonical tail) を使い、strategyを選ばない。widgetは固定のまま (spec D-5)。

### Strategy機構 (spec 182/237実装済み)

- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` — `StrategyDefinition(identity, createsFolders, eligibleUnitFilter, unitOrder, pageScope, cellTraversal, placeFullRun)`。6 ID登録済み。`strategyFixes` は「movableだがfilter外」を `STRATEGY_PRESERVED` とする (widgetsは現状そもそもmovableに入らないため到達しない — widget対応strategy導入後はwidgetをこの経路に流さない設計にする)。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` — `UnitOrdering` による4分岐executor (`CANONICAL_TIE_BREAK` / `CAPTURED_VISUAL_PAGE_LOCAL` / `CAPTURED_VISUAL_GLOBAL` / `CATEGORY_CONTIGUOUS_PAGE_LOCAL`)。未対応フィールドの組み合わせはloud failure (`IllegalStateException` / `error(...)`)。
- `lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt` — 単一の `Allocator` (`allocatePreferred` / `allocateCapturedThenNew` / `allocateOnPageOnly` / `allocateCapturedPageOnly` (issue 228追加)) + `CellTraversal` (`TOP_LEFT_ROW_MAJOR` / `BOTTOM_UP_ROW_MAJOR`)。`findRowMajorFirstFit` はspan矩形対応・決定的。
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt:40-58` — bundle semantic version `organization-policy-v2.5`。`runtimeSupported` 6 ID、default `CANONICAL_PAGE_COMPACT_V1`。
- `lawnchair/src/app/lawnchair/organizer/rules/LayoutStrategySelectionStore.kt` — 選択store (schema不変で済む)。

### Result / preview表面

- `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt:51-55` — `PlacementCode` は `SINGLE_PLACEMENT` / `FOLDER_MEMBER` / `FOLDER_UNIT` (widget用codeなし)。`Planned.unplaced` + `UnplacedReason.STRATEGY_SCOPE_FULL` (issue 228) あり。
- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` — `PreviewCounts(movedCount, preservedCount, newFolderCount, newPageCount, warningCounts, crossPageMovedCount, preservedByStrategyCount, addedCount)` (`addedCount` はissue 228追加のdefault引数付き拡張。本issueの `widgetMovedCount` 拡張の直接の前例)。
- Strategy pickerのcopyは `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:648-665` (`strategyDisplayName` / `strategyDescription`) と strings (`organization_strategy_*_name/description`)。preview内の移動理由は `MoveChange.rationale` をUI文言へ解決。

## Design (spec承認後に確定する実装詳細)

### Module / seam

変更はplanning module内部 + rules (bundle) + preview projection + UI copy。外部seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult` は変更しない (spec 182と同じ方針)。

1. **Role分類とmovable化 (planning内側)**: `determinePreservation` に選択strategyの `widgetPolicy` を渡す形に拡張する。`widgetPolicy != null` のstrategyではwidget分岐が `null` (movable) を返し、`widgetPolicy == null` (既存6 ID + incrementalCandidateStrategy) では現行どおり `PreserveReason.WIDGET`。`RESERVED_REGION`/`LOCKED`/`UNAVAILABLE_TARGET`/`DOCK` など高位理由は参数化されず常に先に効く。これにより既存strategyの出力は構造的に不変。
2. **`StrategyDefinition` 拡張**: `widgetPolicy: WidgetPlacementPolicy? = null` フィールドを追加。`WidgetPlacementPolicy` は内部sealed data:
   - `PageLocalBand(topLeftFirstFit)` — STABLE_PAGE_TIDY_V2用 (widget band内first-fit)
   - `PageLocalTopAnchored(topLeftFirstFit)` — BOTTOM_FIRST_V2 / CATEGORY_CONTIGUOUS_V2用 (page全域first-fit)
   - `CrossPageIdentityOrdered` — GLOBAL_COMPACT_V3用 (CAPTURED_THEN_NEW走査)
   共通でwidget streamの処理順 (不変key順: span降順 → target key → ItemId) とdegrade規則を運ぶ。実装は1つのexecutor関数 (`placeWidgetStream`) に束ね、policyの組み合わせがexecutor branchを持たないものは現行どおりloud failure。
3. **Executor拡張 (`FullRunExecution`)**: widgetPolicyを持つstrategyの `placeFullRun` は、既存executorの前にwidget stream処理を挟む wrapper とする: (a) `movableItems` からwidget kindを抽出、(a') **strategy-fixed movable item (そのstrategyの `eligibleUnitFilter` でfilter外となる非widget movable item — 既存folder・non-`1×1` app等) のcaptured占有を先に `markOccupied** する (既存executor内のstrategyFixed前処理より前にwidgetが配置されるため、wrapper側で障害物化が必須。既存executorのstrategyFixed markingは同一cellの再markでありoccupancy-idempotentなので二重印付けは無害。`BOTTOM_FIRST_V2` のcanonical flowはstrategy-fixed movable itemを持たないため該当なし)、(b) 不変key順にpolicyのregion/traversalで配置し `Moved{WIDGET_UNIT}` / `Preserved{ALREADY_CANONICAL}` を出力、(c) 配置不能ならscope単位でdegrade (全widget元位置固定 + `STRATEGY_PRESERVED` + `markOccupied`)、(d) 残りのmovableItemsで既存executor (app/folder stream) を実行。既存4分岐executor本体の配置logicは変更しない (widgetとstrategy-fixed占有は既に印付け済みの状態で渡る。strategy-fixed itemの `STRATEGY_PRESERVED` 行の出力は既存executorが担う)。
4. **Allocator拡張**: band制限付きpage-local矩形first-fit。`findRowMajorFirstFit` のcandidate-y集合は `[0] ∪ occupied bottoms` から導かれるため、y-windowを**事後filterとして実装してはならない** (band minRowがcandidate集合に現れず、障害物のない単独widgetで偽のdegradeが発生する — fixture (b) が検出する)。実装はcandidate-y集合の生成時に `minRow` を原点として加える構成とする (例: candidate-y = `distinct([band.minRow] ∪ occupied.bottoms ∪ [0]).filter { band.minRow ≤ it && it + h - 1 ≤ band.maxRow }` の昇順)。page全域 (BOTTOM_FIRST_V2) は既存 `allocateOnPageOnly` で足りる。cross-page (V3) は既存 `allocateCapturedThenNew` を使う (後続child)。第二のoccupancy実装は作らない。
5. **`PlacementCode.WIDGET_UNIT`** 追加 (`PlanningResult.kt` のenum値追加、spec 10 delta)。
6. **Preview**: `PreviewCounts` に `widgetMovedCount: Int = 0` 追加、`PlanPreviewProjector` が `Moved{WIDGET_UNIT}` を `MoveChange` (rationale=`WIDGET_UNIT`) に投影しcountを集計。UIは `WIDGET_UNIT` rationaleの移動理由文言を追加。
7. **Bundle**: `STABLE_PAGE_TIDY_V2` 有効化childで `runtimeSupported` に追加 + semantic version `organization-policy-v2.6` 増分 + digest再計算 + catalog coherence test。`BOTTOM_FIRST_V2` で `-v2.7`。
8. **Copy**: `organization_strategy_tidy_v2_name/description`、`organization_strategy_bottom_first_v2_name/description` (ja/en) 追加。`STABLE_PAGE_TIDY_V1`/`BOTTOM_FIRST_V1` descriptionに「ウィジェットは移動しません」を追記して真実化 (D-6)。移動理由文言 (`WIDGET_UNIT` 用) 追加。

### Widget streamの詳細 (共通実装)

- 抽出: `movableItems.filter { it.kind == APPWIDGET || it.kind == CUSTOM_APPWIDGET }` (movable化済みwidgetのみ。natural preservation済みのwidgetは最初から入らない)。
- 処理順: `compareByDescending(span.height).thenByDescending(span.width).thenBy(targetKeySortValue(target)).thenBy(id)` — `targetKeySortValue` の `WidgetKey` encoding (`"3:provider:appWidgetId"`) を再利用。
- 配置: policyごとのregion走査。target spanは常にcaptured span (`WorkspaceTarget` のspan)。
- Degrade: page-local系はpage単位、cross-page系はstream全体。degrade時は当該widget群を全て元位置に `markOccupied` して `Preserved{STRATEGY_PRESERVED}`。
- Band計算 (STABLE_PAGE_TIDY_V2): page別に `minOf(capturedCell.y)` / `maxOf(capturedCell.y + span.height - 1)`。

### Idempotence証明の実装対応

- 各新strategyのnormative fixture testに「plan適用→materialize→同strategy replan→empty diff」の状態遷移assertionを含める (spec 237のformation fixtureと同じ形式)。specの構成証明と1:1に対応させること。
- spec 11 property suite (cross-strategy runner) に新strategyを追加し、replan idempotence / conservation / bounds / overlap / lock / determinismを全fixtureで検証する。

### Change set (予測)

| Area | Intended change |
|---|---|
| `planning/PlanningPlacement.kt` | `determinePreservation` のwidget分岐のstrategy参数化 (高位理由は不変) |
| `planning/LayoutStrategyRegistry.kt` | `WidgetPlacementPolicy` 型、`StrategyDefinition.widgetPolicy`、`STABLE_PAGE_TIDY_V2` / `BOTTOM_FIRST_V2` 登録 |
| `planning/FullRunExecution.kt` | `placeWidgetStream` (不変key順・region走査・degrade) と既存executorへのwrapper接続 |
| `planning/PlacementAllocator.kt` | band (y-window) 制限付きfirst-fit の最小拡張 |
| `planning/PlanningResult.kt` | `PlacementCode.WIDGET_UNIT` 追加 |
| `rules/BuiltInOrganizerPolicyBundleSource.kt` | runtimeSupported拡張、semantic version増分 (`-v2.6` / `-v2.7`) |
| `application/public/PlanPreview.kt` + `application/preview/PlanPreviewProjector.kt` | `widgetMovedCount` projection |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` + `ui/` | strategy名/descriptionのmapping、移動理由文言 |
| `values/strings.xml` + `values-ja/strings.xml` | 新strategy copy、V1 copy真実化、`WIDGET_UNIT` 移動理由 |
| `CONTEXT.md`、`docs/product/requirements.md` (FR-016 traceability) | 受入PRで反映 |
| spec 10 delta (`PlacementCode.WIDGET_UNIT`)、spec 194 delta (`widgetMovedCount`) | 本spec受入PRが正本化 (spec 235本文に記載済み) |

### Migration / rollback

- 新strategy有効化 = bundle semantic version増分publishのみ (spec 237機構の再利用)。`rule-v2`・selection store schema・DB schema無変更。in-place migrationなし。
- Rollback: 旧strategyはcatalogに残るため、ユーザーは旧選択に戻せる。`CANONICAL_PAGE_COMPACT_V1` oracle・golden corpusは一切変更しない。適用済みlayoutの復旧は既存recovery point契約。
- 高リスク分類: layout-data (spec frontmatter)。最終source-changing PRは `risk: layout-data` label + `final-status` CI成功 + `docs/assessment/pr-<n>-*.md` 独立auditが必須 (AGENTS.md / github-workflow.md)。planning moduleは機械的high-risk path対象外だが、specが `risk: layout-data` を宣言するためlabel運用でgateに載せる。

### Failure handling

- Widget配置不可 (region内にfitなし) → degrade規則 (scope単位で全widget元位置固定、`STRATEGY_PRESERVED`)。plan全体は成功。
- Admitted unit (app/folder stream) の配置失敗は既存どおりloud failure (`error(...)`) — widget streamはapp streamより先にoccupancyを確定させるため、既存placeability argumentを壊さない (STABLE_PAGE_TIDY_V2: `1×1` count ≤ 空きcell数のargumentはwidget occupancy込みで成り立つ。BOTTOM_FIRST_V2: `PREFERRED_THEN_NEW` のoverflowで新規pageを作る)。

## Testing strategy

- **Contract/unit** (public seam `OrganizationPlanner.plan` 経由): 各新strategyのnormative fixture — 2×2 widget, 4×2 widget, 1×1 widget, 複数widget (band内詰め・順序安定), widget+locked app混在, band内障害物によるdegrade, **band内の既存folder / non-1×1 app (strategy-fixed障害物) との重なりなし**, **対象集合外widget (`NON_TARGET` fall-through, direct-seam)**, 単独widgetの左寄せ, bottom-firstの相補領域 (folder形成なし構成), portrait/landscape/tablet/two-panel device profile, page容量境界。全配置でwidgetのtarget spanがcaptured spanと一致するassertion。`Moved{WIDGET_UNIT}` / `Preserved{ALREADY_CANONICAL}` / `Preserved{STRATEGY_PRESERVED}` の理由assertion。
- **Replan idempotence**: 各新strategyで「plan → materialize → replan → empty diff」を直接固定 (specの構成証明との対応)。formation は絡まない (widget対応strategyはcreatesFolders=false (tidy) / true (bottom-first) 両方を検証)。
- **Property**: 既存spec 11 harness/property suiteを全新strategyで実行 (cross-strategy runner)。conservation/bounds/overlap/lock/profile isolation/determinism/idempotence。
- **既存回帰**: golden corpus (byte-equivalence) 無変更で通過。既存 `GlobalCompactStrategyTest` 等6 strategyのtest主張無変更。scope-composed test (`ScopeComposedPlannerTest`) 無変更通過 + widget対応strategyでのscope-composed新規test。
- **Preview**: `PreviewCounts.widgetMovedCount` projection test、`MoveChange.rationale == WIDGET_UNIT` 行test。
- **Bundle**: catalog coherence test (`runtimeSupported == 実装ID`, default含む) + semantic version assertion。
- **UI**: strategy picker instrumentation test (新ID表示・copy)、TalkBack/font-scale (spec 52/195継承)。
- **実機/emulator**: 代表的widget (2×2, 4×2) を含むlayoutでbefore/preview/after/recovery evidence (AC-10)。

## Incremental order (child issue / PR分割案)

1. **Spec/plan受入PR (本PR)** — docsのみ。spec 10/194 delta (`WIDGET_UNIT`, `widgetMovedCount`) は本spec本文に正本化する (受入PRではspec 10/194 fileを編集しない — spec D-4の単一規則)。
2. **実装PR 1**: role分類 + `WidgetPlacementPolicy` + `placeWidgetStream` + allocator拡張 + `STABLE_PAGE_TIDY_V2` 登録・有効化 (bundle `-v2.6`) + `WIDGET_UNIT`/`widgetMovedCount` projection + **spec 10 file delta (`PlacementCode.WIDGET_UNIT`)** + copy (tidy V2 + V1真実化) + 全test表面。
3. **実装PR 2**: `BOTTOM_FIRST_V2` 登録・有効化 (bundle `-v2.7`) + copy + test。
4. **(後続child issue)** `GLOBAL_COMPACT_V3`、`CATEGORY_CONTIGUOUS_V2` の実装・有効化。
5. **実機評価・独立audit** — 最終source-changing PRに対して `docs/assessment/pr-<n>-*.md`。

各実装PRは `Refs #235`。Issue終了条件 (AC-1〜AC-10) を満たす最終PRのみ `Closes #235`。実装PR 1・2を個別PRに分ける理由はreview可能性とbundle増分のper-enablement原則 (ADR-0007 §8)。solo運用でまとめる場合は1 PRに統合してもよいが、その場合もcommitは分離し、auditは最終headに対して実施する。

## Dependencies / blockers

- 依存: spec 182 / ADR-0012 / spec 237 (versioning機構) / spec 228 (scope-composed) — すべて実装済み。
- Blocker: なし (旧Open questionsは D-1〜D-6 として解消済み)。

## Risks

- Heterogeneous-span idempotence (ADR-0012 Decision 4の反例) — 不変key順による構成で回避し、replan property testとnormative fixtureで固定。spec証明と実装の乖離が最大リスクのため、fixtureは証明の各段に対応させる。
- Widget密なhomeでのlayout品質 (4×2が複数・bandがpage大半) — 実機/emulator評価必須 (AC-10)。degrade規則により安全側に落ちる。
- `PreviewCounts`/`PlacementCode` のpublic形状変更 — default引数付き追加で後方互換、既存test無変更で通過することで検証。
- `determinePreservation` 参数化が既存strategy出力を変えるregression — golden corpus + 既存test無修正で検証 (byte単位)。

## Explicitly unverified areas

- `GLOBAL_COMPACT_V3` / `CATEGORY_CONTIGUOUS_V2` の実装詳細 (後続child。specのnormative rulesと証明は確定済み)。
- 新strategy copyの最終文言 (ja/en) — 実装PRで確定。方向性はspec D-6/Preview節に記載。
- 実機widget種別 (2×2 / 4×2 相当の実provider) での評価環境 — 実装PRでemulator/実機evidenceを取る。
- `ManualOrganizationPreferences.kt` のpicker周辺の正確な現在実装 (行番号は2026-09-12時点。実装時に再確認)。
- `LayoutStrategySelectionStore`・composerの詳細 (schema不変で済む見込み。実装時に再確認)。
