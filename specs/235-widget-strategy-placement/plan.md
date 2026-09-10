# Implementation Plan: Strategy-aware fixed-span widget placement

> Issue: #235
> Spec: [spec.md](./spec.md)
> Status: draft (spec未承認のため実装不可。Open questions解消後に再審査すること)
> Baseline evidence: `origin/main` @ `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` (2026-09-10確認)

## Current evidence (確認済み現行実装)

実装開始時に再検証すること。

### Widgetはpreservation層で固定される

- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:220-250` — `determinePreservation` が `item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET -> PreserveReason.WIDGET` を返す。この判定は `LOCKED`/`RESERVED_REGION`/`UNAVAILABLE_TARGET`/`DOCK` より低く、`APP_PAIR`/`LEGACY_SHORTCUT`/`NON_TARGET`/`STRUCTURAL` より高いprecedence。
- `PlanningPlacement.place` (同file, lines 40-48) はpreservation理由を持つitemのworkspace占有をstrategy実行前に `allocator.markOccupied` で印付ける。よってwidgetは6戦略すべてでoccupancy constraintであり、`FullRunContext.movableItems` に現れない。
- `appendPreservedPlacements` (`FullRunExecution.kt:79-93`) が全strategy共通のtailとして `Preserved{WIDGET}` を出力する。

### Strategy機構 (spec 182/237実装済み)

- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` — `StrategyDefinition(identity, createsFolders, eligibleUnitFilter, unitOrder, pageScope, cellTraversal, placeFullRun)`。6 ID登録済み。`strategyFixes` は「movableだがfilter外」を `STRATEGY_PRESERVED` とする規則を持つ (widgetsはそもそもmovableに入らないため到達しない)。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` — `UnitOrdering` による4分岐executor (`CANONICAL_TIE_BREAK` / `CAPTURED_VISUAL_PAGE_LOCAL` / `CAPTURED_VISUAL_GLOBAL` / `CATEGORY_CONTIGUOUS_PAGE_LOCAL`)。`executePageLocalLiftThenPlace` (page-local lift-then-place), `executeGlobalCompact` (cross-page first-fit + folder formation), `executeCanonicalPageCompact`。未対応フィールドの組み合わせはloud failure (`IllegalStateException`)。
- `lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt` — 単一の `Allocator` (`allocatePreferred` / `allocateCapturedThenNew` / `allocateOnPageOnly`) + `CellTraversal` (`TOP_LEFT_ROW_MAJOR` / `BOTTOM_UP_ROW_MAJOR`)。複数spanの矩形走査は既に`span`引数で扱う。
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt:40-58` — bundle semantic versionは `organization-policy-v2.5` (`GLOBAL_COMPACT_V2` 有効化時の増分コメント付き)。`runtimeSupported` 6 ID、default `CANONICAL_PAGE_COMPACT_V1`。
- `lawnchair/src/app/lawnchair/organizer/rules/LayoutStrategySelectionStore.kt` — 選択store (schema不変で済む)。

### Result / preview表面

- `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` — `PreserveReason.WIDGET` は既存値。`PlacementCode` は `SINGLE_PLACEMENT` / `FOLDER_MEMBER` / `FOLDER_UNIT` (widget用codeなし)。
- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt:240-250` — `PreviewCounts(movedCount, preservedCount, newFolderCount, newPageCount, warningCounts, crossPageMovedCount, preservedByStrategyCount)`。widget分離countなし。
- Strategy名・説明copyは `ui/ManualOrganizationRun.kt` 周辺およびlocalized resources。

## Design (draft — Open questions解消後に確定)

### Module / seam

変更はすべてplanning module内部 + rules (bundle) + preview projection。外部seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult` は変更しない (spec 182と同じ方針)。

1. **Role分類 (planning内側)**: `determinePreservation` のwidget分岐を、strategyが「widgetをmovableにする」場合に回避できる形に再構成する。案: preservation判定をstrategy定義が参照する `WidgetPlacementPolicy` (nullable) で参数化し、policyを持つstrategyではwidgetが `movableItems` に入る (locked/reserved/unavailable/dock等の高位理由は変わりなく優先)。`PreserveReason.WIDGET` は「widgetを movable にしない全strategy」で現行どおり使われ続ける — したがって既存strategyの出力は構造的に不変。
2. **`StrategyDefinition` 拡張**: role別配置意思を宣言フィールドとして追加 (例: `widgetPolicy: WidgetPlacementPolicy?`)。`FullRunExecution` は宣言されたpolicyに対するexecutor branchを持ち、branchのない組み合わせは現行どおりloud failure (registryの設計契約を維持)。
3. **`WidgetPlacementPolicy` (internal)**: preferred regionの決定的定義 (traversal方向またはregion境界の計算式)、page affinity (captured page固定 or cross-page許可)、候補走査順 (自位置最優先を含む)、fallback順。純dataとして宣言し、探索は導入しない。
4. **Allocator拡張**: region限定の矩形候補走査 (既存`Allocator`の`span`対応を再利用し、実装は1つに保つ)。page scopeは既存3種を再利用。
5. **`PlacementCode.WIDGET_UNIT`** (または等価) 追加 — `PlanningResult` のenum拡張。public seam形状変更であるためspec 10 deltaとして受入PRに明記。
6. **Preview**: `PreviewCounts` へ `widgetMovedCount` (cross-page内訳を含むかはspec 194 deltaで決定) 追加。`PlanPreviewProjector` のprojection拡張。
7. **Bundle**: 新strategy ID分の `runtimeSupported` 追加 + semantic version増分 (`organization-policy-v2.6` 等) + digest再計算 + catalog coherence test。

### Data flow

compose (bundle v2.x + selection) → `plan` (role分類 → widgetPolicy有無でwidgetのmovable化 → executor: widget stream配置 → app/folder stream配置 → appendPreservedPlacements) → `PlanningResult` (widget移動は `Moved{WIDGET_UNIT}`) → preview (widgetMovedCount) → apply/recovery (無変更)。

### Idempotence証明方針 (strategy別)

- Page-local系: widgetのtargetが「captured位置の関数」(自位置最優先の決定的候補走査) であれば、材料化後のreplanは同じ候補走査で自位置を返す → empty diff。lift-then-placeのplaceability argumentをwidget版に拡張 (page内のfree矩形はlifting後もcaptured配置を含むため必ず置ける)。
- Bottom/upper系: region分離がpage内で決定的なら同様。region境界計算がapp配置結果に依存する場合は依存方向を固定し (widget先 or region式は入力のみの関数)、証明をcarefully構成する。
- Global compact系 (cross-page widget): **未解決** (spec Open question 2)。spec 237の単調消費argumentをwidget streamに適用できるかの検討が実装前必須。適用できない場合はpage-local限定または対象外。

### Change set (予測)

| Area | Intended change |
|---|---|
| `planning/PlanningPlacement.kt` | `determinePreservation` のwidget分岐のstrategy参数化、widget占有のmarker制御 |
| `planning/LayoutStrategyRegistry.kt` | `WidgetPlacementPolicy` 型、新strategy ID登録 |
| `planning/FullRunExecution.kt` | widget stream配置のexecutor branch (role別順序の明示的実装) |
| `planning/PlacementAllocator.kt` | region限定矩形候補走査 (単一実装維持) |
| `planning/PlanningResult.kt` | `PlacementCode.WIDGET_UNIT` 等の追加 |
| `rules/BuiltInOrganizerPolicyBundleSource.kt` | runtimeSupported拡張、semantic version増分 |
| `application/public/PlanPreview.kt` + `application/preview/PlanPreviewProjector.kt` | widget移動count projection |
| `ui/` + `values*/` | strategy picker copy (app領域とwidget領域の関係説明、ja/en) |
| spec 10/194/195 deltas, `CONTEXT.md`, `requirements.md` (FR-016 traceability) | 受入PRで反映 |

### Migration / rollback

- 新strategy有効化 = bundle semantic version増分publishのみ (spec 237機構の再利用)。`rule-v2`・selection store schema・DB schema無変更。in-place migrationなし。
- Rollback: 旧strategyはcatalogに残るため、ユーザーは旧選択に戻せる。`CANONICAL_PAGE_COMPACT_V1` oracle・golden corpusは一切変更しない。適用済みlayoutの復旧は既存recovery point契約。
- 高リスク分類: layout-data。最終source-changing PRは `final-status` CI + `docs/assessment/pr-<n>-*.md` 独立auditが必須 (AGENTS.md)。

### Failure handling

- Widget配置不可 → `STRATEGY_PRESERVED`/`ALREADY_CANONICAL` によるtruthful保留 (resize/drop禁止)。
- Admitted unitの配置失敗のloud failure契約 (既存 `error(...)` path) との境界: widgetのfallback保留はstrategy定義に含まれるものであり、fallback消滅後の配置失敗のみloud failureとする (spec文言をplan実装時に整合)。

## Testing strategy

- **Contract/unit** (public seam経由): 各新strategyのnormative fixture — 2×2 widget, 4×2 widget, 1×1 widget, widget+locked app混在, fragmented obstacle + 4×2 widget (packing order), 複数widgetの順序安定, portrait/landscape/tablet/two-panel device profile, page容量境界。widgetのspanが全配置でcaptured spanと一致するassertion。
- **Property**: 既存spec 11 harness/property suiteを全新strategyで実行 (cross-strategy runner)。特にreplan idempotence (empty diff) とconservation/bounds/overlap/lock。
- **既存回帰**: golden corpus (byte-equivalence) 無変更で通過。既存 `GlobalCompactStrategyTest` 等の主張無変更。
- **Preview**: `PreviewCounts` のwidget count projection test、Compose UI test、TalkBack/font-scale。
- **実機**: 代表的widget (2×2, 4×2) を含むlayoutでのbefore/preview/after/recovery evidence (物理端末、building guide環境)。

## Incremental order (child issue分割案)

1. Research/decision: spec Open questions 1-3 (ID命名・packaging、global compact可否、movement cost形式化) の所有者判断 → spec受入。
2. Role分類 + `WidgetPlacementPolicy` 内部seam + 最初の1 strategy (stable/tidy系が自然: page-localで証明が最容易)。
3. 二つ目のfamily (bottom/upper系)。
4. (決定次第) global compact系のwidget対応。
5. Preview projection + UI copy (widget count分離、説明文)。
6. 実機評価・独立audit。

各child mainlineはruntimeSupportedを実装済みIDのみ宣言し、bundle semantic versionを増分publishする。

## Dependencies / blockers

- 依存: spec 182 / ADR-0012 / spec 237 (versioning機構) — すべて実装済み。
- Blocker: spec Open questions 1-6 (特にglobal compact冪等性証明とmovement cost形式化) が解消されるまで実装開始不可。

## Risks

- Heterogeneous-span idempotence (ADR-0012 Decision 4の反例) — widget移動の中核リスク。各strategyに完全な証明なしに実装しない。
- `PreviewCounts`/`PlacementCode` のpublic形状変更がspec 10/194との整合を壊すリスク — 受入PRでdelta正本を先に更新。
- Widget密なhomeでのlayout品質 (4×2が複数) — 実機評価必須。

## Explicitly unverified areas

- Global compact系でのwidget cross-page移動の可否と証明 (未解決)。
- `executeCanonicalPageCompact` 系 (preferred-page分岐) にwidget移動を入れる場合の証明 (本planでは対象family外としている)。
- Preview UIの具体的widget表示形式 (spec 194/195 delta待ち)。
- `ManualOrganizationRun.kt` のpicker copy現状の正確な文言 (実装時に確認)。
- LayoutStrategySelectionStore・composerの詳細 (schema不変で済む見込みだが実装時に再確認)。
