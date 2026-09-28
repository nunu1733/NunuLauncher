# Implementation Plan: AI相談の凍結適用（toggle導入と既定導線からの除外、AI交換JVMテストのgate外し）

> Issue: #443
> Spec: [spec.md](./spec.md)
> Status: draft
> Revision 2: 2026-09-26 — Phase1 review指摘1〜4を反映。
> Revision 3: 2026-09-26 — 再review指摘1〜3を反映（effect配置・IO dispatch・重複実行防止、toggle契約の単純化、自動観測なしの明記）。
> Revision 4: 2026-09-26 — 再review2指摘1〜2を反映（AC-5を許容diff/禁止diff契約へ、AC-8からTalkBack実機確認を外す）。
> Revision 5: 2026-09-26 — 実装review指摘を反映（toggle読み取りをcomposition開始時の1回読みへ確定、DataStore常時購読を外す）。

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

**OFF時のrun導線の実装位置（spec確定済み、再review指摘1を反映）**: compose面の分岐で実装する。ただし自動遷移effectは `PreferenceLazyColumn` の `LazyListScope` DSLの内側（`State.ScopeConfirmed` branch内）には置けない — そこは `@Composable` contextではないため、`LaunchedEffect` を直接書くとcompileできない。構成:

1. `PreferenceLazyColumn` の外側（`ManualOrganizationPreferences` composable本体）で、`state` の `ScopeConfirmed` を取り出す:
   ```kotlin
   val scopeConfirmed = state as? ManualOrganizationRun.State.ScopeConfirmed
   ```
2. toggleを既存のpreference read pattern（`collectAsStateWithLifecycle`）で読む。
3. `PreferenceLazyColumn` の外側のcomposable scopeへ、runIdとtoggle値をkeyにしたeffectを置く:
   ```kotlin
   LaunchedEffect(scopeConfirmed?.runId, exchangeAiConsultationEnabled) {
       if (scopeConfirmed != null && !exchangeAiConsultationEnabled) {
           withContext(Dispatchers.IO) { coordinator.planWithConfirmedScope() }
       }
   }
   ```
   - **IO dispatch**: 既存のユーザー操作経路（`execute { coordinator.planWithConfirmedScope() }` = `scope.launch { withContext(Dispatchers.IO) { ... } }`、`ManualOrganizationPreferences.kt:299-303`）と同じthreading disciplineを守る。`planWithConfirmedScope()` は `runComposedPhase()` へ入りinput composition / planningまで同期的に進めるため、Main dispatcherからの直接呼出しは重い処理をUI threadで実行する。effect内でも `withContext(Dispatchers.IO)` で包む。
   - **重複実行防止**: keyに `scopeConfirmed?.runId` を含むため、同一runでの再compositionではeffectが再起動しない。runが変わる（別runId）か、toggle値が変わった場合のみ再起動する。runIdがnull（ScopeConfirmedでない）のときはeffect本体が何もしない。二重起動の競合は `planWithConfirmedScope()` 自体の状態guard（`State.ScopeConfirmed` 以外はno-op、`ManualOrganizationRun.kt:1117-1122`）がfail-safeになる。
4. LazyList側の `State.ScopeConfirmed` branchは、OFFのときheadline（`method-choice-headline`）・`method-choice-plain`・`method-choice-consult`・`exchangeFlowItems` hostingを一切emitしない。ONのときは現行どおり両armをemitする。`scopeRejection` / `scopeDiscardFailed` のtyped failure rowはON導線でのみ現れる状態であるため、OFFでは描画機会がなく契約変更は不要。

**toggle契約（再review指摘2の確定、実装reviewでone-shot readへ確定）**: 「toggle変更後に開始するrunへ適用」。実装はrun面のcomposition開始時にpreferenceを1回読む（`remember { prefs2.exchangeAiConsultationEnabled.firstBlocking() }`、composition中は固定）。設定変更後にrun面へ再入場したcompositionが新値を読むため、実ユーザー導線（設定変更 → run面へ入り直し）で契約が満たされる。run面を離れると現行の `DisposableEffect(coordinator) { onDispose { coordinator.dismiss() } }`（`ManualOrganizationPreferences.kt:470-472`）がparked runをcancelするため、同じparked runへ戻る実ユーザー導線は存在しない。この実ユーザーlifecycleを変えない（「状態機械/既存契約を変えない」方針）。specのscenario「toggle OFFへの変更は、その後に開始・再開されるrunへ適用される」がこの契約である。live DataStore購読はrun面上で不要であり、instrumented run pathから背景snapshot trafficを外すためone-shot readを採用した（[PR #467 comment](https://github.com/nunu1733/NunuLauncher/pull/467#issuecomment-5848003677)の実装review指摘対応）。理由: run面を離れると現行の `DisposableEffect(coordinator) { onDispose { coordinator.dismiss() } }`（`ManualOrganizationPreferences.kt:470-472`）がparked runをcancelするため、ユーザーが設定へ移動してtoggleを変え、同じparked runへ戻る実ユーザー導線は存在しない。この実ユーザーlifecycleを変えない（「状態機械/既存契約を変えない」方針）。specのscenario「toggle OFFへの変更は、その後に開始・再開されるrunへ適用される」がこの契約である。

**face oracleの廃止（Phase1 review指摘4の確定）**: OFF導線はcompose分岐のため `ScopeConfirmed` compositionは存在し、METHOD_CHOICE faceは1回commitされる。よって「METHOD_CHOICE faceがcommitされない」というoracleは立てない。検証対象は「OFF時に方法選択面のnode（headline `manual_organization_method_title`、`method-choice-plain`、`method-choice-consult`）がsemantics treeに現れない」ことと「runがplanning/確認面へ進む」ことである。face traceは既存oracleとして変更しない。

**focus挙動**: OFF時は `ScopeConfirmed` 到達 → 自動 `planWithConfirmedScope()` → planning/確認面へ遷移するため、方法選択面のfocus対象は実質組まれない（1 composition分のnodeは存在しうるが、自動遷移が同frame内で進むためsemantics treeには確認面が現れる）。`focusRequester` は既存のplanning/確認面の先頭対象へそのまま進む。AC-8のinstrumentation oracleで確認する。

**既存AI arm testのON条件seam（review指摘2の対応）**: 既存のAI arm test（`entryFaceHasNoAiRowAndTheMethodChoiceFaceOffersItAfterConfirm` 等）はON前提の契約を検証する。toggle既定OFFのため、これらのtestにはON条件を設定する必要がある。実装方法: `ManualOrganizationPreferences` composableへtest-onlyのoverride引数（既存の `exchangeHolderOverride` と同じtest seam pattern）を追加するか、preferenceをtestでONに設定する。planでは「preferenceのtest設定」を第一案とする（DataStoreのtest書込みseamは既存のpreference testで確認）。いずれもproduction契約は変更しない。

**JVM gate外しの実装（review指摘1の確定）**: `build.gradle` の既存 `tasks.withType(Test).matching { it.name == "testLawnWithQuickstepGithubDebugUnitTest" }.configureEach` ブロック（:96）へ、次を追加する:

```groovy
// Issue #443: the AI exchange UI unit tests (app.lawnchair.organizer.ui.exchange.*)
// are frozen (FR-017) and leave the blocking merge gate. The exclusion is
// property-gated so local/manual runs keep the full suite when the property
// is absent; automated CI (PR/main/weekly) runs with the property and
// excludes the frozen suite (no automatic observation, spec #443).
if (providers.gradleProperty("nunu.excludeAiExchangeUnitTests").isPresent()) {
    filter {
        excludeTestsMatching("app.lawnchair.organizer.ui.exchange.*")
    }
}
```

CIの `organizer-unit-tests` jobは `-Pnunu.excludeAiExchangeUnitTests=true` を付けて実行する。**gate外し後の自動観測は行わない（再review指摘3の確定）**: `ci.yml` はmain push・weekly schedule・workflow_callで同じ `organizer-unit-tests` jobを使うため、propertyをjobへ付けるとmain/weekly CIでも139件は除外される。`planner-stress.yml` は `--tests '*PlannerGeneratedPropertyTest*'` のみで `ui.exchange` はそもそも対象外である。よって凍結suite（6 class / 139 test）は「ファイルは保持されるが、自動CIでは実行されず、local/manual実行のみで観測する」ことが本specの決定であり、bit-rot観測jobは追加しない（凍結中は機能開発が止まるため、これらのテストがbit-rotする変更容易は凍結の例外（データ安全bug修正）に限られ、その場合は例外手順のPR内で該当classをlocal実行する）。

**実測根拠**: 上記Current evidenceのinit script検証（property-gatingで1644/0件 ↔ 1783/139件の切替を確認済み）。

### Data flow

- toggle ON/OFFはpreference（DataStore）からcompose面が読む。run coordinatorはpreferenceを読まない。
- OFF時: run開始 → 検出 → 対象選択（候補あり時） → scope確定（`ScopeConfirmed` publish、現行どおり） → LazyColumn外のeffectが自動 `planWithConfirmedScope()`（IO dispatch） → planning → 確認 → 適用。
- ON時: 現行どおり。scope確定 → 方法選択面 → いずれかのarm。
- hub status card: 変更なし（run状態と独立のsession-scoped row）。OFFでもpre-open導線（`exchangeOpen` 引数）は機能し続ける。
- JVM gate: CIはproperty付きでui.exchange除外。local/manualは全件（自動CIでの凍結suite観測は行わない）。

### Alternatives rejected

- **Gradle CLIのnegation pattern（`--tests '!...'`）**: 除外演算子ではない（実測: 96 test全件実行、negation-onlyはBUILD FAILED）。却下。
- **coordinator側でOFF分岐を持つ**（scope確定時に `ScopeConfirmed` をpublishせず直接composed phaseへ進む）: 状態機械の契約がON/OFFで変わり、spec 417の状態oracle群に広い影響が出る。preferenceをcoordinatorへ注入すると、呼び出し側とテストが同じseamを使う規約に反する注入が増える。却下。
- **toggle OFF時に方法選択面の「AIに相談」armだけを消す**（自動実行なし）: 方法選択面自体が表示され続けるため、「方法選択面を出さない」というOutcomeを満たさない。却下。
- **run admission時にtoggle値をsnapshotしrun単位で固定する**: seamが増え（snapshot保持場所）。採用したのはcomposition開始時の1回読み（surface単位）であり、「toggle変更後に開始するrun」契約を満たす。live DataStore購読（`asState()`）は初期実装で用いたが、instrumented run path上の背景snapshot trafficが不要なリスク源のため不採用へ変更した。
- **parked runへの即時適用を製品要件にする**: run面を離れると `dismiss()` でparked runがcancelされる現行lifecycleを変える必要があり、「状態機械/既存契約を変えない」方針を超える。不採用（再review指摘2）。
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
| AC-5 | PR diff確認（許容diff: test setup/ON条件設定、新規OFF導線oracle、preference test追加、CI filter変更。禁止diff: AI交換productionコード・既存test oracleの削除、凍結対象exchange contractの変更）。PR本文へ照合結果を記録 |
| AC-6 | CI run証跡 + 実行結果XML: `organizer-unit-tests` 成功、XML中に `ui.exchange` class不在、`integration.exchange` / `personalization.exchange` class存在。#352 oracleのgate外れをXML不在で確認 | PR CI run（`build.gradle` 変更によりfull portfolioが発火するため、全lane + permanent gateの証跡が同一runで取れる） |
| AC-7 | 実機screenshot/録画（OFF時の(a)(b)確認） | emulator、artifactをPRへlink |
| AC-8 | instrumentation test（focus対象の検証。OFF遷移後にmethod-choice nodeが存在せず、planning/確認面の期待nodeがfocus対象として存在する。TalkBack実機確認は本Issueでは要求しない — spec AC-8に判断を記録） | manual-organization-ui lane相当class |

含めるべき観点: unit/contract（preference既定値、filter exclusion）、UI/accessibility（focus挙動。TalkBack実機確認はspec AC-8に記録のとおり要求しない）、failure（preference読み取りがrun coordinatorの状態機械へ書き込まないことをunit oracleで確認）。

## Documentation updates

- [x] spec status/history
- [ ] `docs/product/organizer-to-be-ux.md`（D-04/D-17の凍結注記）
- [ ] `docs/engineering/ci-test-portfolio.md`（gate外しの監査記録。対象・根拠・検出条件と「自動観測なし」を記載。quality-strategyの除外記録要件を満たす）
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
