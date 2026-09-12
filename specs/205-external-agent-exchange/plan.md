# Implementation Plan: External Agent Exchange workflow

> Issue: #205
> Spec: [spec.md](./spec.md)
> Status: draft — #204 (Context / PersonalizedIntent contract) のacceptanceが実装開始の前提条件である。

## Re-entry status

- 2026-09-13: 前回snapshot (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) を検証し、現行main `f9afd8bfde121932c0c8ed965225d52a84d86ab4` をmergeして再anchorした。main差分のうち本planに関係する変更: #228 (scope-composed run・missing-app selection)、#235 (widget strategy placement・semantic placement role)、#271/#288 (durable status・diagnostics export filename)、requirements.md FR-016 implemented化 (FR-017は未割当のまま)。#204はdraftのまま (branch `issue-204-spec-plan` commit `65b9fc859d` で同一baselineへre-anchor、未accept・main未取り込み)。既存のdesign・change set・verification構造に影響する矛盾は見つかっていない。

## Current evidence

origin/main (`f9afd8bfde121932c0c8ed965225d52a84d86ab4`) 時点の確認事実:

- `PersonalizationContextExportV1` / `PersonalizedIntentV1` の実装・参照はsource上に存在しない (`Personaliz` でのhitはflowerpot asset・翻訳fileのみ)。#204はdraft snapshotがbranch `origin/issue-204-spec-plan` (`specs/204-ai-personalization-context-intent-contract/`、commit `65b9fc859d`) にあるのみで、origin/main未取り込み・未accept。`PolicySourceKind` は6値で `PERSONALIZED_INTENT` は含まない (`lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt`)。
- organizer module構造は `lawnchair/src/app/lawnchair/organizer/{planning,integration,ui,rules,locks,diagnostics,application}` (DESIGN.md §9と一致)。`application` 配下に `actions/adapter/canonical/lifecycle/preview/protocol/public/revision/store` を持つ。
  - planning: `OrganizationPlanner.kt`, `OrganizationInput.kt` (`RunMode` = `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization`), `DeterministicOrganizationPlanner.kt`, `LayoutStrategyRegistry.kt` (#235の `STABLE_PAGE_TIDY_V2` / `BOTTOM_FIRST_V2` を含む), `CandidatePlanningIds.kt` (#228), `PlacementAllocator.kt` 等。planner seamは #182、widget配置の意味論は #235、scope合成runは #228 で拡張済み。
  - integration: `OrganizationInputComposer.kt`, `ProductionOrganizationInputComposer.kt`, `CompositionModels.kt`, `FullTargetSetMaterializer.kt`, `MissingAppCandidateSource.kt` / `AndroidCandidatePorts.kt` (#228) 等 (canonical入力composeの既定位置)。
  - ui: `ManualOrganizationRun.kt`, `MissingAppSelectionScreen.kt` (#228), `OrganizationPreviewContent.kt` 等。preview/confirmation surfaceは #194/#195 で実装済み。
- 外部export precedent: `organizer/diagnostics/export/{ExportUi.kt, ExportWriter.kt, DiagnosticsExportFilename.kt}` (#67/#138 + #288。user-initiated export、diagnostics port経由、個人情報redaction規則は [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md))。
- clipboard precedent: `lawnchair/src/app/lawnchair/util/ClipboardUtils.kt` (現baselineで存在確認済み)。Share Sheet (`ACTION_SEND` + chooser) もbugreport経路で使用実績あり。
- #203 (usage signals) はOPEN。signal snapshot契約は未確定。
- #204 draft (2026-09-13時点) は、export対象種別を #235 のsemantic placement role族に揃えること、widget spanをexport/intent両方から除外すること、`Unknown` をexport対象から除外すること、reserved workspace regionを制約として投影すること、intentが新規RunMode・対象追加を持たないこと、8つのtyped failure classとreject-by-default V1を、草案として固定している。これらは依存変数であり、受入時に再確認する。

## Design

### Modules and interfaces

新規module `lawnchair/src/app/lawnchair/organizer/personalization/` (純粋部) と `organizer/ui/` 内のexchange UI:

- `exchange/` (pure, Android-free):
  - `ExchangePackageComposer`: #204 context export生成 (または #204 側module) の結果 + instruction template → exchange package text。instruction/data分離構造をtypedに表現する。
  - `IntentImportParser`: import text → accepted framing抽出 → `PersonalizedIntentV1` typed model (または生payload) → #204 validatorへ。framing違反・曖昧性はtyped parse failure。
  - production/test同一seam。interfaceへAndroid型・`Intent`・`ClipboardManager`を漏らさない。
- `exchange/transport/` (thin adapter): clipboard / share / file の各transport。純粋部の結果 (package text) を受け取り、Android APIへ出すだけ。失敗はtyped resultで返す。
- `ui/`: 送信前確認画面、import画面 (paste/text入力/file選択/share-back受信)、失敗・reject表示。既存 `ManualOrganizationRun` / preview surfaceからの導線。
- #204側が所有するvalidator・planner adapterは本planの対象外。本moduleは検証済みintentを既存planning入力compose経路へ渡すのみ。#204 draftによれば、intentのprovenance参加 (第6policy input `PERSONALIZED_INTENT`) とplanner投影adapterは #204 側の成果物であり、本workflowが新たなRunMode・対象追加を導入することはない (既存 `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` と組合わされる)。

### Data flow

```text
Manual run UI → 確認 (privacy mode選択 + 送信前確認)
  → #204 context export生成 (既存canonical入力から、副作用なし)
  → ExchangePackageComposer → package text
  → transport (clipboard/share/file) → [外部agent、app外]
外部agent返答 → import (paste/file/share-back)
  → IntentImportParser (framing抽出) → #204 validator (zero-write)
  → accepted intent → 既存 planner/preview (#194) → confirm (#195) → apply (spec 13)
```

全失敗 (transport失敗、parse失敗、validation reject) はUIでtyped表示、書込みなし。

### Alternatives rejected

- promptとdataを単一自由文に混ぜる構成: instruction/data分離が機械検証できず、injection脅威モデルが破綻するため採用しない。
- 返答textからの寛容なJSON抽出 (正規upload・best effort): partial apply・曖昧解釈の危険がありIssueが明示的に禁止するため厳格framingのみ。
- export/importの中間artifact永続化: V1で必要な使用要件がなく、privacy表面を広げるためprocess-localとする (#204 draftと整合)。
- provider別deep-link / automation: provider lock-inのため非採用 (Non-goals)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/` (新規) | ExchangePackageComposer, IntentImportParser (pure) | 計算とtransport分離、test容易性 (AGENTS.md設計規約) |
| `organizer/personalization/exchange/transport/` (新規) | clipboard/share/file adapter | Android APIをthin adapterに局所化 |
| `organizer/ui/` | 送信前確認・import・失敗UI、導線 | 既存organizer UI surfaceとの一貫性 |
| `organizer/integration/` | (必要最小) import intent → planning入力への接続点 | 既存 `ProductionOrganizationInputComposer` 経路の再利用 |
| #204側module | validator・export生成 (本plan対象外) | 契約所有は #204 |

## Migration and recovery

- DB schema変更なし。新規追加のみ。既存run (personalization未選択) の挙動・provenance不変。
- exchange中間artifactは永続しないため、rollback = 機能を表示しない (feature flag相当の導線制御は実装時に検討)。release rollbackで特別な後処理は不要。
- 適用・復旧は既存spec 13 recovery pathをそのまま使い、本workflowは新しい適用経路を作らない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | package構造 unit/contract test | unit test (pure module) |
| AC-2 | export field契約test (#204 suite連携) | unit test |
| AC-3 | 確認画面・transport instrumentation test | instrumentation / 手動 |
| AC-4 | parser reject corpus unit test | unit test |
| AC-5 | validator failure表示 test (#204 failure分類依存) | unit + UI test |
| AC-6 | import→preview→confirm統合test | instrumentation |
| AC-7 | injection corpus security test | unit test |
| AC-8 | 依存review + schema test | review / unit test |
| AC-9 | a11y・環境失敗 evidence | 手動 (TalkBack, Switch Access, large font, clipboard無効) |
| AC-10 | representative workflow evidence | physical device + ChatGPT/Gemini |

含める観察: unit/contract (package・parser)、property (framing抽出の決定性)、security (injection corpus)、UI/a11y、failure injection (transport失敗)。

## Documentation updates

- [ ] spec status/history (#204受入時の改訂を含む)
- [ ] CONTEXT.md (domain language 4用語、受入時)
- [ ] DESIGN.md §4 (personalization exchange module追記、受入時)
- [ ] ADR: 初回transport選定が「変更困難・理由がコードから分からない・実際の選択肢あり」を満たす場合は作成
- [ ] requirements.md (FR-017系、#204受入時に整理)

## Dependencies and blockers

- **#204 acceptance (blocker)**: schema、tier、validator、framing、intent identityが全て #204 側で決定される。#204は2026-09-13時点でdraft (branch `issue-204-spec-plan`、baseline `f9afd8bfde` へre-anchor済み、main未取り込み) のため実装開始不可。#204受入後に本spec/planを改訂してから実装へ進む。
- #203 (OPEN): usageSignals projectionは #203 確定後。不在でも本workflowは成立 (signalはoptional、#204 draftでもcoarse bucket + tier制御の方向)。
- #206 (OPEN): 兄弟issue。transport/provider adapterの共通化はintent契約 (#204) のみで行い、UI・provider選択は独立に保つ。

## Risks

- clipboardのsize制限・OS毎の挙動差が大規模layoutでexportを壊す可能性 (open question 8)。
- 外部agentの返答がframing指示に従わない頻度が高い場合、UXが反復になる。instruction文言の検証 (AC-10 evidence) で早期に把握する。
- #204受入でframing・tier設計が本draftと乖離した場合、parser・確認UIの設計見直しが必要。

## Explicitly unverified areas

- 外部agent (ChatGPT/Gemini) がinstruction部に実際にどの程度従うかは未検証 (AC-10で実施)。
- clipboard/Share Sheetの実機挙動 (size上限、target有無) は本draft時点で未計測。
- #204 draftの内容は将来変更され得るため、それに依存する記述 (tier名、failure class名、widget span非投影、reject-by-default V1等) は全て #204受入時に再確認が必要。
- 本planのevidence再確認は2026-09-13時点のdocs-only差分に対して行った。build・testは未実行 (docs-only変更のため、full buildは対象外)。

## Execution checklist

- [ ] #204 accepted、本spec/plan改訂済み。
- [ ] Current behavior reproduced (導線不在の確認)。
- [ ] Parser/package composerの失敗testを先行追加。
- [ ] Minimal implementation (composer → transport → import → 既存preview接続)。
- [ ] Security/a11y/environment failure verification completed。
- [ ] Physical-device representative workflow evidence recorded。
- [ ] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-10: 初回draft (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`)。
- 2026-09-13: baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` へのmerge re-anchor。Current evidence更新 (organizer構造の #228/#235 差分、`DiagnosticsExportFilename.kt`、`PolicySourceKind` 6値)、#204 draft再anchor状態をDependenciesへ反映。design・change set・verificationの構造は変更なし。
