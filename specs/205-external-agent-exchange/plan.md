# Implementation Plan: External Agent Exchange workflow

> Issue: #205
> Spec: [spec.md](./spec.md)
> Status: draft — #204 (Context / PersonalizedIntent contract) のacceptanceが実装開始の前提条件である。

## Re-entry status

- 2026-09-13: 前回snapshot (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) を検証し、現行main `f9afd8bfde121932c0c8ed965225d52a84d86ab4` をmergeして再anchorした。main差分のうち本planに関係する変更: #228 (scope-composed run・missing-app selection)、#235 (widget strategy placement・semantic placement role)、#271/#288 (durable status・diagnostics export filename)、requirements.md FR-016 implemented化 (FR-017は未割当のまま)。#204はdraftのまま (branch `issue-204-spec-plan` commit `65b9fc859d` で同一baselineへre-anchor、未accept・main未取り込み)。既存のdesign・change set・verification構造に影響する矛盾は見つかっていない。
- 2026-09-14: Owner review "Request changes" (2026-09-13、snapshot `517adbe4` 基準) に対応し、main `c5274b5d0d` をmergeして再anchor。**P1 (process death)**: #205側のprocess-local前提を撤去し、pending export identity・ref mappingのdurable保持を #204 draft (2026-09-13 review対応revision `324e6182ae`) のexport session (`ExportSessionStore`) へ一元化する設計へ変更。process recreation後importを検証対象 (AC-11) へ追加。**P1 (framing所有)**: exchange framingを #205所有と確定し (#204はpayload本体のみ)、`IntentImportParser` をframing抽出 (#205) とpayload検証 (#204) の2段に分離。**P2 (prompt injection)**: security test oracleをfail-closed中心へ変更。main差分 (`f9afd8bfde` → `c5274b5d0d`) の確認: #300 (API 36 UI lane window focus gating、test infra)、#287 (grid変更時のunknown lock回収、`LockAuthoring.kt` folder child rank)、#283 (strategy picker選択表示、RadioButton)、#308 (Compose focus同期fix)、#304 (CI emulator evidence capture、docs)。`ManualOrganizationPreferences.kt` の差分はfocus/readiness制御とstrategy picker表示であり、manual run state machine・preview/confirm surfaceという本planの統合点の構造は不変。planning seam・`InputProvenance`・organizer module構造への変更はなし。#204は依然draft (再review待ち) のため実装blockerは継続。

## Current evidence

origin/main (`c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda`) 時点の確認事実:

- `PersonalizationContextExportV1` / `PersonalizedIntentV1` の実装・参照はsource上に存在しない (`Personaliz` でのhitはflowerpot asset・翻訳fileのみ)。#204はdraft snapshotがbranch `origin/issue-204-spec-plan` (`specs/204-ai-personalization-context-intent-contract/`、2026-09-13 owner review対応revision commit `324e6182ae`) にあるのみで、origin/main未取り込み・未accept (再review待ち)。`PolicySourceKind` は6値で `PERSONALIZED_INTENT` は含まない (`lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt`)。
- organizer module構造は `lawnchair/src/app/lawnchair/organizer/{planning,integration,ui,rules,locks,diagnostics,application}` (DESIGN.md §9と一致)。`application` 配下に `actions/adapter/canonical/lifecycle/preview/protocol/public/revision/store` を持つ。
  - planning: `OrganizationPlanner.kt`, `OrganizationInput.kt` (`RunMode` = `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization`), `DeterministicOrganizationPlanner.kt`, `LayoutStrategyRegistry.kt` (#235の `STABLE_PAGE_TIDY_V2` / `BOTTOM_FIRST_V2` を含む), `CandidatePlanningIds.kt` (#228), `PlacementAllocator.kt` 等。planner seamは #182、widget配置の意味論は #235、scope合成runは #228 で拡張済み。
  - integration: `OrganizationInputComposer.kt`, `ProductionOrganizationInputComposer.kt`, `CompositionModels.kt`, `FullTargetSetMaterializer.kt`, `MissingAppCandidateSource.kt` / `AndroidCandidatePorts.kt` (#228) 等 (canonical入力composeの既定位置)。
  - ui: `ManualOrganizationRun.kt`, `MissingAppSelectionScreen.kt` (#228), `OrganizationPreviewContent.kt` 等。preview/confirmation surfaceは #194/#195 で実装済み。#308 fix (2026-09-13以降merge) による `ManualOrganizationPreferences.kt` の変更はfocus/readiness制御と #283 strategy picker選択表示であり、run state machine・preview/confirm surfaceの統合点の構造は不変。
- 外部export precedent: `organizer/diagnostics/export/{ExportUi.kt, ExportWriter.kt, DiagnosticsExportFilename.kt}` (#67/#138 + #288。user-initiated export、diagnostics port経由、個人情報redaction規則は [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md))。
- clipboard precedent: `lawnchair/src/app/lawnchair/util/ClipboardUtils.kt` (現baselineで存在確認済み)。Share Sheet (`ACTION_SEND` + chooser) もbugreport経路で使用実績あり。
- #203 (usage signals) はOPEN。signal snapshot契約は未確定。
- #204 draft (2026-09-13 review対応revision) は、**export sessionをdurable・期限付き (app-private・backup対象外) とし、process deathを跨ぐintent取り込みを契約化** (`ExportSessionStore`、single-active-session、`SESSION_EXPIRED`)、coverage不変条件と `INCOMPLETE_COVERAGE`、`exportId` (instance identity) と `contextDigest` (canonical内容digest) の分離とsource binding (`CONTEXT_STALE`、V1はreject-on-change)、`EXTERNAL_REDACTED` でのsurrogate生成廃止 (自由文class一括除外)、`APP_PAIR`/`SHORTCUT_LEGACY` をintent addressable対象外 (constraint-only投影)、intent addressable種別を `APP_OR_SHORTCUT`/`FOLDER`/`WIDGET` (widget span非投影)、11のtyped failure class、reject-by-default V1を、草案として固定している。framing (外部agent返答の包み方) は #204 scope外であり、本planが #205所有として扱う。これらは依存変数であり、受入時に再確認する。

## Design

### Modules and interfaces

新規module `lawnchair/src/app/lawnchair/organizer/personalization/` (純粋部) と `organizer/ui/` 内のexchange UI:

- `exchange/` (pure, Android-free):
  - `ExchangePackageComposer`: #204 context export生成 (または #204 側module) の結果 + instruction template → exchange package text。instruction/data分離構造をtypedに表現し、instruction部にexchange framing (返答形式の指定) を含める。
  - `IntentImportParser`: **2段構成**。第1段は **#205所有のexchange framing抽出** (import text → framing marker/構造で区切られたpayload領域の一意抽出。framing不成立・複数候補・境界破れは #205固有のtyped framing failure — `IntentImportParser` 側のsealed resultで #204のvalidation failureと区別)。第2段は抽出payloadを #204側のcodec/validator seamへそのまま渡す (schema解釈はしない)。
  - production/test同一seam。interfaceへAndroid型・`Intent`・`ClipboardManager`を漏らさない。
- `exchange/transport/` (thin adapter): clipboard / share / file の各transport。純粋部の結果 (package text) を受け取り、Android APIへ出すだけ。失敗はtyped resultで返す。
- `ui/`: 送信前確認画面、import画面 (paste/text入力/file選択/share-back受信)、失敗・reject表示。既存 `ManualOrganizationRun` / preview surfaceからの導線。UI層はexport↔ref対応をprocess memoryにのみ保持しない (process recreation後のimportは #204のexport session seam経由で再解決する)。
- **process death耐性の所有**: pendingなexport identity・ref↔内部ID mappingのdurable保持は #204 draftの `ExportSessionStore` (durable・期限付き・app-private・backup対象外) が担う。本planは新規storageを追加せず、import時にpayloadの `exportId` から #204 session seamへ問い合わせるのみ。#205側で対応表を複製・永続化しない。
- #204側が所有するvalidator・export session store・planner adapterは本planの対象外。本moduleは検証済みintentを既存planning入力compose経路へ渡すのみ。#204 draftによれば、intentのprovenance参加 (第6policy input `PERSONALIZED_INTENT`) とplanner投影adapterは #204 側の成果物であり、本workflowが新たなRunMode・対象追加を導入することはない (既存 `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` と組合わされる)。

### Data flow

```text
Manual run UI → 確認 (privacy mode選択 + 送信前確認)
  → #204 context export生成 (既存canonical入力から、副作用なし。#204 ExportSessionStoreへdurable session作成)
  → ExchangePackageComposer → package text
  → transport (clipboard/share/file) → [外部agent、app外]
    (この間にLauncher processが破棄されても、session解決は #204のdurable export sessionが担う)
外部agent返答 → import (paste/file/share-back)
  → IntentImportParser 第1段: framing抽出 (#205所有、typed framing failure)
  → 第2段: #204 codec/validator (session照会・contextDigest照合含む、zero-write)
  → accepted intent → 既存 planner/preview (#194) → confirm (#195) → apply (spec 13)
```

全失敗 (transport失敗、framing抽出失敗、validation reject) はUIでtyped表示、書込みなし。

### Alternatives rejected

- promptとdataを単一自由文に混ぜる構成: instruction/data分離が機械検証できず、injection脅威モデルが破綻するため採用しない。
- 返答textからの寛容なJSON抽出 (正規upload・best effort): partial apply・曖昧解釈の危険がありIssueが明示的に禁止するため厳格framingのみ。
- export/importの中間artifact (package本文・返答text) の永続化: V1で必要な使用要件がなく、privacy表面を広げるため永続しない。ただし **export identity・ref↔内部ID mappingのprocess-local保持** は #205側で採用しない (2026-09-14 revisionで取止め): 標準flowは外部アプリ滞在中のprocess deathを含み、durable保持は #204のexport sessionに一元化する。#205が独自に複製すると二重正本・不整合を生む。
- framing規格を #204側へ含める option: #204のscopeはpayload schema・validator・sessionであり、外部agent向けUI規格を含めると契約受入が #205のUX設計に引きずられる。framingは本plan (#205) が所有し、payload schemaの受入後に文言を確定する。
- provider別deep-link / automation: provider lock-inのため非採用 (Non-goals)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/` (新規) | ExchangePackageComposer, IntentImportParser (pure) | 計算とtransport分離、test容易性 (AGENTS.md設計規約) |
| `organizer/personalization/exchange/transport/` (新規) | clipboard/share/file adapter | Android APIをthin adapterに局所化 |
| `organizer/ui/` | 送信前確認・import・失敗UI、導線 | 既存organizer UI surfaceとの一貫性 |
| `organizer/integration/` | (必要最小) import intent → planning入力への接続点 | 既存 `ProductionOrganizationInputComposer` 経路の再利用 |
| #204側module | validator・export生成・export session store (durable) (本plan対象外) | 契約所有は #204。session (process death耐性) も #204所有 |

## Migration and recovery

- DB schema変更なし。新規追加のみ。既存run (personalization未選択) の挙動・provenance不変。#205側は新規storageを追加しない (durable sessionは #204側の`ExportSessionStore`が所有し、Launcher favorites DBと独立・backup対象外)。
- exchange package本文・返答textは永続しないため、rollback = 機能を表示しない (feature flag相当の導線制御は実装時に検討)。release rollbackで特別な後処理は不要。
- 運用中の回復: 返答text喪失 (clipboard消失・agent側履歴喪失等) → 外部agentからの再copy、または再export (新規export identity。#204 single-active-session規則により旧sessionは無効化)。session失効後の到着 → typed失敗と再export案内。往復中のhome変更 → `CONTEXT_STALE` (仮称) によるtyped失敗と再export案内。
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
| AC-7 | injection corpus security test。oracleはfail-closed中心 (影響された応答がunsafe writeに到達しない、zero-write、planner制約不変)。agentのinstruction遵守はoracleに含めない | unit test |
| AC-8 | 依存review + schema test | review / unit test |
| AC-9 | a11y・環境失敗 evidence | 手動 (TalkBack, Switch Access, large font, clipboard無効) |
| AC-10 | representative workflow evidence (app切替往復を含む) | physical device + ChatGPT/Gemini |
| AC-11 | framing抽出を除くsession解決のprocess recreation simulation: export生成 → session seamの保持状態を残したままparser/processor instanceを破棄・再生成 → 失効前import成功。失効後は `SESSION_EXPIRED` (仮称)、不明sessionは `EXPORT_MISMATCH` (仮称)。#204 ExportSessionStore suiteと連携 | unit test (session seam経由) + physical device (AC-10と兼ね可) |

含める観察: unit/contract (package・framing parser)、property (framing抽出の決定性)、security (injection corpus、fail-closed oracle)、UI/a11y、failure injection (transport失敗、session失効・不在、`CONTEXT_STALE` 相当)、process recreation simulation (AC-11)。

## Documentation updates

- [ ] spec status/history (#204受入時の改訂を含む)
- [ ] CONTEXT.md (domain language 4用語、受入時)
- [ ] DESIGN.md §4 (personalization exchange module追記、受入時)
- [ ] ADR: 初回transport選定が「変更困難・理由がコードから分からない・実際の選択肢あり」を満たす場合は作成
- [ ] requirements.md (FR-017系、#204受入時に整理)

## Dependencies and blockers

- **#204 acceptance (blocker)**: schema、tier、validator、intent identity、export session (durable保持・TTL) が #204 側で決定される。**exchange framingのみ例外で、本plan (#205) が所有する** (payload schemaの受入後に文言を確定する)。#204は2026-09-14時点でdraft (branch `issue-204-spec-plan`、2026-09-13 owner review対応revision `324e6182ae`、main `c5274b5d0d` へre-anchor済み、再review待ち、main未取り込み) のため実装開始不可。#204受入後に本spec/planを改訂してから実装へ進む。#204受入gate (capability set初期内容、content limits/session TTL数値、planner投影) は本workflowのUX copy・失敗案内の設計入力でもある。
- #203 (OPEN): usageSignals projectionは #203 確定後。不在でも本workflowは成立 (signalはoptional、#204 draftでもcoarse bucket + tier制御の方向)。
- #206 (OPEN): 兄弟issue。transport/provider adapterの共通化はintent契約 (#204) のみで行い、UI・provider選択は独立に保つ。

## Risks

- clipboardのsize制限・OS毎の挙動差が大規模layoutでexportを壊す可能性 (open question 8)。
- 外部agentの返答がframing指示に従わない頻度が高い場合、UXが反復になる。framing設計は前後の自由文を許容し、typed parse失敗時に再依頼を案内する。instruction文言・framingの検証 (AC-10 evidence) で早期に把握する。
- 往復中にuserがhomeを変更すると `CONTEXT_STALE` (仮称) rejectとなり、再exportが必要になる。失敗説明のUX copyが「なぜ拒否されたか・次に何をすべきか」を正確に伝える必要がある。session TTL (#204受入gate) が短すぎると長いagent往復が失敗しやすくなる。
- #204受入でsession・failure分類の設計が本draftと乖離した場合、parser・確認UI・失敗案内の設計見直しが必要。

## Explicitly unverified areas

- 外部agent (ChatGPT/Gemini) がinstruction部・framing指示に実際にどの程度従うかは未検証 (AC-10で実施)。
- clipboard/Share Sheetの実機挙動 (size上限、target有無) は本draft時点で未計測。
- #204 draftの内容 (durable session仕様、failure class名、TTL、coverage規則等) は将来変更され得るため、それに依存する記述は全て #204受入時に再確認が必要。
- 本planのevidence再確認は2026-09-14時点のdocs-only差分に対して行った。build・testは未実行 (docs-only変更のため、full buildは対象外)。

## Execution checklist

- [ ] #204 accepted、本spec/plan改訂済み (session/failure名の仮称解消を含む)。
- [ ] Current behavior reproduced (導線不在の確認)。
- [ ] framing parser・package composerの失敗testを先行追加 (framing失敗は #204 validation failureと区別されること)。
- [ ] Minimal implementation (composer → transport → import → 既存preview接続)。
- [ ] Process recreation simulation test (AC-11) を含むsecurity/a11y/environment failure verification completed。
- [ ] Physical-device representative workflow evidence recorded (app切替往復を含む)。
- [ ] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-10: 初回draft (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`)。
- 2026-09-13: baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` へのmerge re-anchor。Current evidence更新 (organizer構造の #228/#235 差分、`DiagnosticsExportFilename.kt`、`PolicySourceKind` 6値)、#204 draft再anchor状態をDependenciesへ反映。design・change set・verificationの構造は変更なし。
- 2026-09-14: Owner review "Request changes" への対応とbaseline `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` へのmerge re-anchor。process death耐性を #204 durable export session前提へ設計変更 (P1)、exchange framingの #205所有を確定しparser設計を2段へ分離 (P1)、security test oracleをfail-closed中心へ変更 (P2)。AC-11と検証・migration・risk節を更新。#204はdraft継続のため実装blockerは不変。
