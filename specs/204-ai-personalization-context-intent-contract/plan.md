# Implementation Plan: AI personalization用 Context / PersonalizedIntent exchange contract

> Issue: #204
> Spec: [spec.md](./spec.md)
> Status: draft
> Baseline: `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4` (2026-09-13時点)。初版 (`6b6bf8dd` 基準、2026-09-10) からの再入場検証は「Current evidence」の再入場検証節を参照。

## Current evidence

確認済みの現行状態 (2026-09-13, `origin/main` @ `f9afd8bfde121932c0c8ed965225d52a84d86ab4`)。実装開始時に再検証する。

- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt` — `OrganizationInput(snapshot, rules, taxonomy, signals, targets, runMode)`。`RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` (#228、`TargetSet.additions` が非空になり得る唯一のmode)。`CapturedItem` は `ItemKind` として `APPLICATION`/`DEEP_SHORTCUT`/`SHORTCUT_LEGACY`/`FOLDER`/`APPWIDGET`/`CUSTOM_APPWIDGET`/`APP_PAIR`/`Unknown` を持つ (#235でwidgetが第一級計画対象)。`LayoutSnapshot.reservedWorkspaceRegions` (`ReservedWorkspaceRegion`) はitem外のplatform占有領域constraint。`ItemId`/`ProfileId`/`CategoryId` 等は `planning/Identity.kt` のopaque value class。
- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` — 唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。前回baselineから未変更。
- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` — #182内部strategy catalog。#235によりwidget placement role/stream/band (`PlanningPlacement.kt` / `PlacementAllocator.kt`、`PlacementCode.WIDGET_UNIT`) が追加済み。`PlanningResult` は `organizationStrategy: StrategyId` と #228 の `unplaced` を持つ。
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` / `CompositionModels.kt` — `InputProvenance`/`PolicyInputIdentity` を所有。`layoutStrategySelection` は第5 policy input (code comment明記)。`PolicySourceKind` は現時点で6値 (`ORGANIZER_POLICY_BUNDLE`, `CATEGORY_OVERRIDE_SNAPSHOT`, `LAYOUT_STRATEGY_SELECTION`, `PLATFORM_CLASSIFICATION_EVIDENCE`, `MATERIALIZED_CLASSIFICATION_SIGNALS`, `MATERIALIZED_FULL_TARGET_SET`)。#228の `StaleCandidateSelection` 等、composition failure型も拡張済み。
- `lawnchair/src/app/lawnchair/organizer/rules/` — `BuiltInOrganizerPolicyBundleSource.kt` (ADR-0007のpolicy authority)、`LayoutStrategySelectionStore.kt`、`CategoryOverrideStore.kt` (schema version + generation + digest のatomic store族の先例)。ADR-0007へは #228 のscope-composed target identity拡張が追記済み (本契約と矛盾なし)。
- `lawnchair/src/app/lawnchair/organizer/application/preview/` — spec 194 plan preview (`inspectPlan`)。spec 195 confirmation UIは `organizer/ui/`。#271 durable status / #288 diagnostics export filename は本契約と直交するadditive変更。
- unit test置き場は `tests/unit/app/lawnchair/organizer/` (planning/integration/rules/ui 等)。既存guard: `planning/PurityGuardTest.kt`、property test基盤 `planning/PlannerGeneratedPropertyTest.kt`、widget系 `planning/WidgetPlacementStrategyTest.kt`、scope-composed系 `planning/ScopeComposedPlannerTest.kt` / `integration/ScopeComposedCompositionTest.kt`。実行commandはbuilding guideの `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`。
- #203 (usage signals) は OPEN で、`PersonalizationSignalSnapshot` / usage signal実装は現mainに存在しない (`specs/203-*` も未取り込み)。よって `usageSignals` はoptional fieldとし、不在でも契約が成立するよう設計する。
- #205/#206 (外部agent exchange / managed AI) は OPEN。consumer不在でも本契約 (codec + validator + adapter) は単体でtest可能な純粋moduleとして成立する。
- 推測 (未確認): intent採用時の `InputProvenance` 第6 input追加が `CompositionModels.kt` のsealed構造 (`SourceUnavailable` 等の全網羅箇所) へ与える影響の詳細。実装child issueで確認する。

**再入場検証 (2026-09-13)**: 初版baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` から現baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` への差分を確認した。主な変更は #235 (widget strategy placement: `STABLE_PAGE_TIDY_V2`/`BOTTOM_FIRST_V2`、widget placement role/policy seam)、#228 (missing-app selection: `CandidateResolution.kt`/`CandidatePlanningIds.kt`/`MissingAppCandidateSource.kt`、`ScopeComposedOrganization`)、#271/#288 (durable status/diagnostics filename)。契約の核 (`OrganizationPlanner.plan` seam、`InputProvenance` 第5 input構造、preview path、ADR-0007 authority model) は不変であり、本planはspec側のkind投影・制約projectionの明確化と本節の再アンカーのみで現行に追従する。Issue/comments再取得では、snapshotコメント (2026-09-10) 以降の追記はない。

## Design

### Modules and interfaces

新規package `lawnchair/src/app/lawnchair/organizer/personalization/` (DESIGN.md §9の論理構成に従う追加。純粋Kotlin、Android型・DB・networkなし)。

```text
organizer/personalization/
├── ContextExportModels.kt    # PersonalizationContextExportV1 typed model (pure)
├── ContextExportBuilder.kt   # canonical inputs -> export (pure, tier-aware)
├── IntentModels.kt           # PersonalizedIntentV1 typed model (pure)
├── IntentCodec.kt            # JSON <-> typed model (closed schema, limits)
├── IntentValidator.kt        # strict validation -> typed failure (pure)
├── IntentIdentity.kt         # content digest / schema identity
└── IntentPlannerAdapter.kt   # validated intent -> planner-internal semantic inputs (pure)
```

- **Seam原則**: 呼び出し側もtestも同じpublic seam (builder / codec+validator / adapter) を使う。internal実装 (ref割当、digest計算) を直接検証しない。
- **ContextExportBuilder** の入力は既存canonical入力のみ (`LayoutSnapshot` 相当のcaptured data、解決済み分類、lock状態。#203導入後はsignal snapshot)。tierに応じるfield除外はbuilder内の単一点で行う。
- **export-scoped ID map** はbuilderが生成しprocess-local保持 (V1では永続化しない)。`ItemId` を外部schemaへ漏らさない。
- **IntentValidator** は spec の失敗class (`SCHEMA_MISMATCH`/`EXPORT_MISMATCH`/`OVERSIZE`/`UNKNOWN_REF`/`DUPLICATE_REF`/`INVALID_ENUM`/`FORBIDDEN_CONTENT`/`CAPABILITY_UNSUPPORTED`) をtyped sealed resultで返す。fail-closed、zero-write。
- **IntentPlannerAdapter** はvalidated intentをplanner内部のsemantic入力へ投影する (投影先はspec Open question 1。受入時に固定)。
- **Provenance統合**: accept済みintentは `PolicySourceKind.PERSONALIZED_INTENT` として `InputProvenance` へ第6 policy inputを追加する (`LayoutStrategySelectionSnapshot` と同じgeneration/digest契約族)。intent未使用runはこのinputを持たない (既存runへの影響なし)。
- **Diagnostics**: `PlanningResult` echoへ intent identity (digest) を追加する。個人情報 (label、package、座標) はdiagnosticsへ出さない (organizer-diagnostics.mdの規則拡張を同PRで)。

### Data flow

```text
canonical inputs --(ContextExportBuilder, tier)--> PersonalizationContextExportV1
    --> [AI/agent: #205/#206またはtest double] --> PersonalizedIntentV1
    --(IntentCodec + IntentValidator)--> ValidatedPersonalizedIntent (digest付き) | typed failure
    --(IntentPlannerAdapter)--> planner semantic inputs
    --(OrganizationPlanner.plan: 既存唯一seam)--> PlanningResult (intent identity echo)
    --(spec 194/195/13: 既存preview/confirm/apply)--> 適用
```

- network・providerとの入出力は本moduleの外 (#205/#206)。codecはtransport非依存のcanonical JSON表現のみ定義する。
- regenerationは新規exportId/intent identity。既存previewの流用はspecどおり無効化する。

### Alternatives rejected

- **AIへ `OrganizationInput` を直接渡す**: lock/bounds安全性、privacy (raw ID/package)、schema移行性で不適切 (Issue本文が明示)。採用しない。
- **intentを `RuleSemantics` へ直書き**: intentはrun-scopedのdynamic inputでありimmutable bundle policyではない。ADR-0007のauthority model (bundle ≠ dynamic state) に反する。
- **#182 catalogへの「AI strategy」追加**: intentはsemantic preferenceであり組み込みstrategyとは別物。catalogはcuratedでなければならない (spec 182 Non-goals)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | Context/Intent model、builder、codec、validator、identity、adapter | 契約本体。pure module |
| `organizer/integration/CompositionModels.kt` | `PolicySourceKind.PERSONALIZED_INTENT` 追加、intent identityの`InputProvenance`参加 | determinism/provenance保証 |
| `organizer/integration/OrganizationInputComposer.kt` | accepted intentの読み取りとstable cutへの組入れ (intent利用runのみ) | spec 183系のconsistent-cut規律を維持 |
| `organizer/planning/PlanningResult.kt` | intent identity echo (optional) | diagnostics/previewでの追跡 |
| `docs/engineering/organizer-diagnostics.md` | intent identity/digest のallowed field追加 | privacy規則の正本更新 |
| tests (下記Verification) | contract/property/security tests | AC対応 |
| `specs/204-.../spec.md`, `plan.md` | status更新、Open questions解消の記録 | 正本管理 |

実装はchild issueへ分割する (下記Execution order)。本Issueの実装縦切りは「pure codec/validator + provenance統合」までとし、#205/#206接続を含まない (Issue本文のImplementation orderどおり)。

## Migration and recovery

- 新規純粋module追加のみ。DB schema変更なし、永続化artifact追加なし (V1) → migration不要。
- intent未使用runは全て従来どおり (互換性: 既存testが無修正で通ることが回帰基準)。
- rollback: 実装のrevertで完了。保持state・migrationがないため復旧処理は不要。validation失敗時のzero-writeにより、失敗からの復旧も「何も起きていない」状態である。
- backup/restore: 触れない (process-localのみ)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (FR-017) | requirements.md更新 (受入PR) | review |
| AC-2 (versioned schema) | model contract test: schemaVersion固定、unknown version拒否 | unit test (`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`) |
| AC-3 (provider非依存) | 同一intentをtest doubleと将来providerが同じseamで取り込める契約test | unit test |
| AC-4 (planner authority) | `FORBIDDEN_CONTENT` 検証test (座標指定/lock移動含むintentのreject) + purity guard拡張 | unit test |
| AC-5 (fail-closed zero-write) | 各typed failureのtable-driven test。malformed/oversize/duplicate/unknown | unit test |
| AC-6 (intent identity/determinism) | 同一accepted intent + 同一inputs → byte-equal plan。regenerate → 別identity。preview無効化 | unit + property test |
| AC-7 (prompt injection) | injection label fixtureを含むexport/intentのsecurity test群 | unit test |
| AC-8 (privacy tier) | tier別export field集合の契約test (package/profile/raw usage不混入の全field走査) | unit test |
| AC-9 (preview path再利用) | planが`PlanningResult`→`inspectPlan`以外の経路を取らないことのコードレビュー + 既存preview testの無修正通過 | review + unit test |
| AC-10 (test計画) | 本表 | 本plan |

追加観点:
- **Property test**: validatorは全fail classでtotal functionである (入力任意byte列に対しrejectまたはvalidのいずれか、例外・hangなし)。export builderは同一inputs→同一digest。既存のproperty基盤 (`tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` 族) に整合させる。
- **Purity test**: personalization packageのAndroid依存・I/O依存ゼロ (`tests/unit/app/lawnchair/organizer/planning/PurityGuardTest.kt` 族に追加)。
- **Widget projection test**: widget itemを含むexport/intentでspan不変が保たれること (`FORBIDDEN_CONTENT` のspan指定reject、adapter投影後も `PlacementCode.WIDGET_UNIT` 系のstrategy宣言semanticsを弱めない) を #235 の `WidgetPlacementStrategyTest` と同水準のfixtureで検証する。
- **高リスク分類**: 本契約自体はpure追加だが、provenance/planner統合PRは `risk: layout-data` 払いとし、high-risk gate (CI `final-status` + `docs/assessment/pr-<n>-*.md`) を満たす。

## Documentation updates

- [ ] spec status/history (受入時)
- [ ] `CONTEXT.md`: 「Personalization Context Export」「Personalized Intent」「export-scoped ID」追加 (受入時)
- [ ] `DESIGN.md`: §4へpersonalization module行追加、§11 gate表へ本契約の正本を行追加 (受入時)
- [ ] `docs/product/requirements.md`: FR-017追加、FR-014との境界備考、D-011言及 (受入PR)
- [ ] `docs/engineering/organizer-diagnostics.md`: intent identity許容 (最初の実装PR)
- [ ] ADR: なし (契約自体はspecで十分。将来provider接続 (#205) でprivacy/threat model判断時に検討)

## Execution checklist / implementation order

1. (前提) spec受入。Open questions 1 (planner投影) と 4 (content limits) を受入時に固定する。
2. child A: `personalization/` pure package — models + builder + codec + validator + identity、全contract/property/security test (AC-2,3,4,5,7,8)。
3. child B: provenance統合 (`PERSONALIZED_INTENT` 第6 input) + adapter + `PlanningResult` echo + composer stable cut 組入れ (AC-6,9)。`risk: layout-data` + high-risk gate。
4. child C: #203契約確定後、`usageSignals` projectionの追記とtest (spec Open question 6)。
5. #205/#206: provider接続 (本Issueの範囲外)。

## Dependencies / blockers

- #203 の契約確定が `usageSignals` 詳細のblocker (V1ではoptionalにより実質blockでない)。
- #205/#206 は本契約のconsumerとして本Issue実装後 (Issue本文: 「Provider integrationより先に本contractをacceptedにする」)。
- planner投影 (Open question 1) の確定がchild Bのblocker。

## Risks

- intent→planner投影が既存determinism/idempotence (INV-7/8) を壊す恐れ → child Bで既存harness/property suiteの無修正通過を必須化し、intentあり/なしのcross-run testを追加する。
- widget/span投影の漏れ: intentのpageAffinity等が #235 のwidget stream/band・span不変semanticsを迂回する形で実装される恐れ → `FORBIDDEN_CONTENT` のspan指定rejectと投影後planのwidget意味論testで固定する (Verification参照)。
- privacy tier判定の漏れ (将来field追加時に意図せず外部送信) → tier別field集合をclosed classで表現し、新field追加時にtier明示を強制するtype設計。
- export/intent中間データの意図しない永続化 → process-local制限をpurity/persistence testで固定。

## Explicitly unverified areas

- #203 signal snapshotの最終schema (未実装・draft spec未取り込み)。`usageSignals` のfield詳細は仮枠。
- `InputProvenance` sealed構造への第6 input追加影響の詳細 (child Bで確認)。
- #205/#206 UI・transport経路での実際の取り込み (範囲外)。
