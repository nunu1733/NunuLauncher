# Implementation Plan: AI相談の凍結適用（toggle導入と既定導線からの除外、AI交換JVMテストのgate外し）

> Issue: #443
> Spec: [spec.md](./spec.md)
> Status: draft
> Revision 2: 2026-09-26 — Phase1 review指摘1〜4を反映。

## Current evidence

- 方法選択面の「AIに相談」arm: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:693-700`（`method-choice-consult` item、`exchangeHolder::openMethodChoiceFlow`）。sibling armの「このまま整理」は `method-choice-plain` item（`planWithConfirmedScope`）。
- run coordinatorのscope確定: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` の `State.ScopeConfirmed`（:537付近）と `planWithConfirmedScope()`（:1117）。空cutは `State.ScopeConfirmed` を直接publishする（:895-919）。
- face mapping: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt:80`（`State.ScopeConfirmed → METHOD_CHOICE`。無条件）。face trace（`ManualOrganizationRunFaceTrace`）はpost-apply `SideEffect` でcompositionにcommitされたfaceを記録する。**OFF導線をcompose分岐で実装する限り、`ScopeConfirmed` compositionは存在するためMETHOD_CHOICE faceは1回commitされる。face traceの不在はoracleに使えない**（review指摘4）。
- hub status cardのsession-scoped rows: `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt:266-299`。run面のpre-openは `ManualOrganizationPreferences.kt:506-513`。
- host mode: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt:272-275, 418-451`（`IMPORT_ONLY` 既定。`openMethodChoiceFlow()` のみが `METHOD_CHOICE` へ変える）。
- 実験的機能画面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt`。
- preference宣言のパターン: `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt`（`booleanPreferencesKey` + `defaultValue`。既定値は `res/values/config.xml` のbool resource経由の先例: `config_default_organizer_personalization_recording`、config.xml:117）。
- **Gradle CLI `--tests` の除外は不可能（review指摘1、ローカル実測2026-09-26）**:
  - `--tests 'app.lawnchair.organizer.*' --tests '!app.lawnchair.organizer.ui.exchange.*'` で実行すると、`ExchangeFlowStateHolderTest` は96 test全件実行された（XML `tests="96" skipped="0"`、timestamp確認済み）。`!` 付きpatternは除外ではなくリテラルinclude patternとして扱われる。
  - negation-onlyの `--tests '!app.lawnchair.organizer.ui.exchange.*'` は `No tests found for given includes: [!app.lawnchair.organizer.ui.exchange.*](--tests filter)` でBUILD FAILED。
- **`excludeTestsMatching` による除外は実証済み（同日、init scriptで検証）**:
  - `Test.filter { excludeTestsMatching("app.lawnchair.organizer.ui.exchange.*") }` をinit scriptで注入し `--tests 'app.lawnchair.organizer.*'` と併用した結果: total 1644、`ui.exchange` 0件（baseline 1783、`ui.exchange` 139。差分139 = ui.exchange 6 class全件が除外）。`ExchangeFlowStateHolderTest` のXMLが結果dirから消えることを確認。
  - 同一filterをGradle property（`nunu.excludeAiExchangeUnitTests`）でgatingした結果: propertyありで1644/0件、propertyなしで1783/139件（現行動作から変化なし）。
- AI交換のJVMテスト構成: `tests/unit/app/lawnchair/organizer/ui/exchange/` 6 class（`ExchangeFlowStateHolderTest` 96、`ExchangeRequestFlowContractTest` 17、`ExchangeCapabilityCopyTest` 5、`ExchangeDisclosureStateTest` 7、`ExchangeFlowJitGateTest` 7、`ExchangeImportFailureDisplayTest` 7 = 計139）。pipeline/contract層は `integration.exchange`（3 class）と `personalization.exchange`（13 class）で、gate対象に維持する。
- `organizer-unit-tests` job: `.github/workflows/ci.yml:329-345`、`--tests 'app.lawnchair.organizer.*'` 等のCLI filter。validator（`validate_ci_portfolio.py`）はjob IDの存在と `permanent_gates` 固定集合のみを検査し、`--tests` の中身は検査しない。
- `build.gradle` はsource filter（`**` include）に一致し、どのsurfaceにも属さないため、変更するとper-path fail-closed ruleでfull portfolioが発火する（`tools/ci/compute_ci_gating.py` の `unmapped` 判定）。つまりfilter変更PRでは全laneが動き、AC-6の証跡が取れる。
- manual-organization-ui lane: `tools/ci/run-manual-organization-ui-instrumentation.sh` のclass list（11 class。`OrganizerDiagnosticsRouteInstrumentationTest` と `UsageAccessJitInstrumentationTest`、`exchange.ExchangeImportSuccessInstrumentationTest` を含むmixed構成）。#372 AI相談scenarioは `OrganizerDiagnosticsRouteInstrumentationTest:419` の1 test（class全体は8 test）。
- method-choice-journey / exchange-import-ui lane: いずれも1 class専用のConditional lane（surface_organizer_ui発火時のみ）。
- 既存AI arm test: `ManualOrganizationPreferencesInstrumentationTest.entryFaceHasNoAiRowAndTheMethodChoiceFaceOffersItAfterConfirm`（:425）等、`exchange_method_consult` をON前提で検証するtest群（:445,460,465,545,657,721）。`MethodChoiceConnectedJourneyInstrumentationTest` はUIをcomposeせずholderを直接駆動するため、toggleの影響を受けない。

## Design

### Modules and interfaces

| Module | 変更 | interface/seam |
|---|---|---|
| `PreferenceManager2` | `exchangeAiConsultationEnabled` boolean preference（既定OFF）を追加 | 既存のpreference seam。新規interfaceなし |
| `ExperimentalFeaturesPreferences` | toggle rowを追加 | 既存の `SwitchPreference` 構成 |
| `ManualOrganizationPreferences` | 方法選択面compose分岐へtoggle条件を注入 | 既存のcompose分岐。新規seamなし |
| `ManualOrganizationRun`（変更なし） | OFF時導線はcompose面の分岐で実装するためcoordinator状態機械は触らない | — |
| `build.gradle` | `testLawnWithQuickstepGithubDebugUnitTest` taskへproperty-gatedな `filter.excludeTestsMatching` を追加 | Gradle Test task filter。CIが `-P` propertyで発動させる |
| `.github/workflows/ci.yml` | `organizer-unit-tests` jobのgradle commandへ `-Pnunu.excludeAiExchangeUnitTests=true` を追加 | CI job定義 |
| `tools/repo-contract/ci_portfolio_map.yml` / `docs/engineering/ci-test-portfolio.md` | gate外しの監査情報を更新 | 正本map |

**OFF時のrun導線の実装位置（spec確定済み）**: compose面の分岐で実装する。`ManualOrganizationPreferences.kt` の `State.ScopeConfirmed` compose branchで:

1. toggleをliveに読む（`collectAsStateWithLifecycle` 経由の既存preference read pattern）。
2. OFFのとき「AIに相談」arm（`method-choice-consult` item）と `exchangeFlowItems` hostingを描画しない。
3. OFFのとき `LaunchedEffect(Unit)` で `coordinator.planWithConfirmedScope()` を自動実行し、方法選択面を経ずplanningへ進む。

**toggle契約（review指摘3の確定）: live read**。preferenceはcompositionのたびに現在値を読む。scope確定後に停まっているrunがあっても、toggleをOFFへ変えてrun面へ戻ると、そのcompositionでOFF導線が適用される（arm非表示 + 自動遷移）。「次のrunから」ではない。specのBehavior scenario「toggle OFFへの変更は、scope確定後に停まっているrunにも即座に適用される」がこの契約である。coordinatorの状態機械には一切書き込まないため、`State.ScopeConfirmed` のpublish契約・attachIntent・claimGenerationEpoch・discard等のspec 417契約はすべて不変である。

**face oracleの廃止（review指摘4の確定）**: OFF導線はcompose分岐のため `ScopeConfirmed` compositionは存在し、METHOD_CHOICE faceは1回commitされる。よって「METHOD_CHOICE faceがcommitされない」というoracleは立てない。検証対象は「OFF時に方法選択面のnode（headline `manual_organization_method_title`、`method-choice-plain`、`method-choice-consult`）がsemantics treeに現れない」ことと「runがplanning/確認面へ進む」ことである。face traceは既存oracleとして変更しない。

**focus挙動**: OFF時は `ScopeConfirmed` 到達 → 自動 `planWithConfirmedScope()` → planning/確認面へ遷移するため、方法選択面のfocus対象は実質組まれない（1 composition分のnodeは存在しうるが、自動遷移が同frame内で進むためsemantics treeには確認面が現れる）。`focusRequester` は既存のplanning/確認面の先頭対象へそのまま進む。AC-8のinstrumentation oracleで確認する。

**既存AI arm testのON条件seam（review指摘2の対応）**: 既存のAI arm test（`entryFaceHasNoAiRowAndTheMethodChoiceFaceOffersItAfterConfirm` 等）はON前提の契約を検証する。toggle既定OFFのため、これらのtestにはON条件を設定する必要がある。実装方法: `ManualOrganizationPreferences` composableへtest-onlyのoverride引数（既存の `exchangeHolderOverride` と同じtest seam pattern）を追加するか、preferenceをtestでONに設定する。planでは「preferenceのtest設定」を第一案とする（DataStoreのtest書込みseamは既存のpreference testで確認）。いずれもproduction契約は変更しない。

**JVM gate外しの実装（review指摘1の確定）**: `build.gradle` の既存 `tasks.withType(Test).matching { it.name == "testLawnWithQuickstepGithubDebugUnitTest" }.configureEach` ブロック（:96）へ、次を追加する:

```groovy
// Issue #443: the AI exchange UI unit tests (app.lawnchair.organizer.ui.exchange.*)
// are frozen (FR-017) and leave the blocking merge gate. The exclusion is
// property-gated so local and scheduled runs keep the full suite by default.
if (providers.gradleProperty("nunu.excludeAiExchangeUnitTests").isPresent()) {
    filter {
        excludeTestsMatching("app.lawnchair.organizer.ui.exchange.*")
    }
}
```

CIの `organizer-unit-tests` jobは `-Pnunu.excludeAiExchangeUnitTests=true` を付けて実行する。ローカル・scheduled（planner-stress）runはpropertyなしで全件実行する（凍結対象テストが消えず、main/scheduled sweepで引き続き観測できる）。`validate_ci_portfolio.py` はjob IDとlane起動条件のみを検査するため、validator変更は不要だが、map/監査表へ記録する。

**実測根拠**: 上記Current evidenceのinit script検証（property-gatingで1644/0件 ↔ 1783/139件の切替を確認済み）。

### Data flow

- toggle ON/OFFはpreference（DataStore）からcompose面がliveに読む。run coordinatorはpreferenceを読まない。
- OFF時: run開始 → 検出 → 対象選択（候補あり時） → scope確定（`ScopeConfirmed` publish、現行どおり） → compose分岐が自動 `planWithConfirmedScope()` → planning → 確認 → 適用。
- ON時: 現行どおり。scope確定 → 方法選択面 → いずれかのarm。
- hub status card: 変更なし（run状態と独立のsession-scoped row）。OFFでもpre-open導線（`exchangeOpen` 引数）は機能し続ける。
- JVM gate: CIはproperty付きでui.exchange除外。scheduled/localは全件。

### Alternatives rejected

- **Gradle CLIのnegation pattern（`--tests '!...'`）**: 除外演算子ではない（実測: 96 test全件実行、negation-onlyはBUILD FAILED）。却下。
- **coordinator側でOFF分岐を持つ**（scope確定時に `ScopeConfirmed` をpublishせず直接composed phaseへ進む）: 状態機械の契約がON/OFFで変わり、spec 417の状態oracle群に広い影響が出る。preferenceをcoordinatorへ注入すると、呼び出し側とテストが同じseamを使う規約に反する注入が増える。却下。
- **toggle OFF時に方法選択面の「AIに相談」armだけを消す**（自動実行なし）: 方法選択面自体が表示され続けるため、「方法選択面を出さない」というOutcomeを満たさない。却下。
- **run admission時にtoggle値をsnapshotしrun単位で固定する**: review指摘3の代替案。live readに比べてseamが増え（snapshot保持場所）、preference即時反映の挙動との差がテストで検証しにくい。live readを契約として採用したため不採用。
- **AI交換テストを `@Ignore` 化する**: テストファイル保持の原則ではあるが、テスト本体の変更（#352の修正はPR #416に凍結済み）であり、gate外しで十分である。skip化はしない。却下。
- **manual-organization-ui laneから#372 AI相談scenarioを除外する**: method単位の除外はclass filterでは行えず、class分割はテストファイル構成の変更になる。またこのtestはAC-4と同じdurable到達可能性契約を検証するproduction-route oracleであり、凍結でも維持すべき契約である。現状維持（spec対象表どおり）。却下。
- **exchange-import-ui / method-choice-journey laneを非blocking化する**: いずれもsurface_organizer_ui変更PRでのみ動き、無関係な変更を失敗させていない。ON時契約のoracleとして維持する。却下。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | `exchangeAiConsultationEnabled`（既定OFF、`config_default_exchange_ai_consultation` bool resource）を追加 | preference正本 |
| `lawnchair/res/values/config.xml` | 既定値bool resourceを追加 | 既存パターン |
| `lawnchair/res/values/strings.xml` | toggle label/description | UI文言正本 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | toggle row追加 | 実験的機能画面 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | 方法選択面compose分岐へOFF条件（arm非表示 + `exchangeFlowItems`非hosting + 自動planWithConfirmedScope） | 方法選択面の正本compose面 |
| `build.gradle` | property-gated `excludeTestsMatching` をunit test taskへ追加 | JVM gate外しのseam |
| `.github/workflows/ci.yml` | `organizer-unit-tests` commandへ `-Pnunu.excludeAiExchangeUnitTests=true` | gate外しの発火 |
| `tests/unit/app/lawnchair/organizer/**`（新規） | preference既定値test | JVM gate |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**`（新規・既存変更） | OFF時run導線・node非出現、ON条件seamの適用、focus挙動のoracle | UI契約の正証 |
| `tools/repo-contract/ci_portfolio_map.yml` | unit gateのfilter変更注記 | 正本map |
| `docs/engineering/ci-test-portfolio.md` | 監査表へgate外しの記録（対象・根拠・検出条件を記載。quality-strategyの除外記録要件を満たす） | 監査表正本 |
| `docs/product/organizer-to-be-ux.md` | D-04/D-17の凍結注記（OFF時は方法選択面を省略する変形であること） | 製品文書正本 |

## Migration and recovery

- schema/rule migration: なし。新preferenceは既定OFFで、既存環境では欠損値が既定OFFとして読める。
- failure中のrollback: toggleをONへ戻せば現行導線へ戻る（preferenceは即時反映）。
- release rollback/downgrade: preference keyが前versionに存在しない場合も、前versionはこのkeyを読まないため影響なし。
- backup/restore compatibility: 本preferenceをbackup契約へ含めない（既定OFFへ戻るのが安全側）。
- CI gate外しのrollback: `-Pnunu.excludeAiExchangeUnitTests` を外せば現行の全件実行へ戻る。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit test（preference既定値OFF）+ 実機screenshot | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（propertyなし=全件実行でpreference testを検証）+ emulator |
| AC-2 | instrumentation test（OFF時のrun導線・method-choice node非出現・候補0件） | `connectedLawnWithQuickstepGithubDebugAndroidTest`（manual-organization-ui lane相当class） |
| AC-3 | instrumentation test（ON時両arm、既存oracle維持） | 同上（既存AI arm testへON条件seamを適用して継続成功） |
| AC-4 | 既存hub row oracleのOFF条件下での継続成功 | `OrganizerHubPreferencesInstrumentationTest` |
| AC-5 | PR diff確認 | PR本文へ記録 |
| AC-6 | CI run証跡 + 実行結果XML: `organizer-unit-tests` 成功、XML中に `ui.exchange` class不在、`integration.exchange` / `personalization.exchange` class存在。#352 oracleのgate外れをXML不在で確認 | PR CI run（`build.gradle` 変更によりfull portfolioが発火するため、全lane + permanent gateの証跡が同一runで取れる） |
| AC-7 | 実機screenshot/録画（OFF時の(a)(b)確認） | emulator、artifactをPRへlink |
| AC-8 | instrumentation test（focus対象） | manual-organization-ui lane相当class |

含めるべき観点: unit/contract（preference既定値、filter exclusion）、UI/accessibility（focus・TalkBack）、failure（toggle変更の進行中runへの影響は状態機械に書き込まないことをunit oracleで確認）。

## Documentation updates

- [x] spec status/history
- [ ] `docs/product/organizer-to-be-ux.md`（D-04/D-17の凍結注記）
- [ ] `docs/engineering/ci-test-portfolio.md`（gate外しの監査記録。対象signature・根拠・検出条件を記載）
- [ ] `tools/repo-contract/ci_portfolio_map.yml`（unit gate注記）
- [ ] CONTEXT.md: 不要（domain languageの追加なし）
- [ ] DESIGN.md: 不要（system structureの変更なし。run coordinatorの契約は不変）
- [ ] ADR: 不要（判断はメモ§4.6で承認済み。実装位置選択はspec/planで記録）
- [ ] AGENTS.md: 不要

## Execution checklist

- [ ] Current behavior reproduced（ON時の現行導線の既存oracleを確認）。
- [ ] Tests fail for the missing behavior（OFF導線・node非出現の新oracleが先に失敗すること）。
- [ ] Minimal implementation completed（preference → toggle → compose分岐 → build.gradle filter → CI property）。
- [ ] Migration/recovery verified（preference欠損環境での既定OFF、rollbackはtoggle ON、CI filter rollbackはproperty削除）。
- [ ] Full relevant verification completed（spotlessCheck、unit gate、instrumentation lanes、build）。
- [ ] PR evidence and remaining risks recorded。
