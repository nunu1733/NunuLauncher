# Implementation Plan: AI相談の凍結適用（toggle導入と既定導線からの除外、AI交換テストのlane外し）

> Issue: #443
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- 方法選択面の「AIに相談」arm: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:693-700`（`method-choice-consult` item、`exchangeHolder::openMethodChoiceFlow`）。sibling armの「このまま整理」は `method-choice-plain` item（`planWithConfirmedScope`）。
- run coordinatorのscope確定: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` の `State.ScopeConfirmed`（:537付近、AC-1で「このまま整理」と「AIに相談」のsibling armsが消費）と `planWithConfirmedScope()`（:1117）。空cutは `State.ScopeConfirmed` を直接publishする（:895-919）。
- face mapping: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt:80`（`State.ScopeConfirmed → METHOD_CHOICE`）。
- hub status cardのsession-scoped rows: `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt:266-299`（依頼row `ExchangeOpen.REQUEST`、提案row `ExchangeOpen.PENDING_REVIEW`。run状態と独立に描画）。run面のpre-openは `ManualOrganizationPreferences.kt:506-513`（`exchangeOpen` 引数、`REQUEST → exchangeHolder.openFlow()`、`PENDING_REVIEW → openPendingImportReview()`）。
- host mode: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt:272-275, 418-451`（`ExchangeHostMode.IMPORT_ONLY` が既定。`openMethodChoiceFlow()` のみが `METHOD_CHOICE` へ変える）。
- 実験的機能画面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt`（既存の `SwitchPreference` 群。preferenceManager2のadapterを使う）。
- preference宣言のパターン: `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt`（`booleanPreferencesKey` + `defaultValue`。既定値は `res/values/config.xml` のbool resource経由の先例: `config_default_organizer_personalization_recording`）。
- AI交換のJVMテスト: `tests/unit/app/lawnchair/organizer/ui/exchange/`（6 class、`ExchangeFlowStateHolderTest` を含む96 test method）。`organizer-unit-tests` jobは `--tests 'app.lawnchair.organizer.*'` で拾う（`.github/workflows/ci.yml:345`）。
- Gradle filterの除外構文は検証済み（2026-09-26、ローカル `--dry-run`）: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests '!app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolderTest'` は正常にtask graphを構成する。
- CI発火surface: `surface_organizer_ui` は `lawnchair/src/app/lawnchair/organizer/ui/**` と `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**` 等を含む（`.github/workflows/ci.yml:169-190`）。`surface_jvm` は `tests/unit/**` を含む。
- AI交換のinstrumentation lane: method-choice-journey lane（`ci.yml:897-930`、`MethodChoiceConnectedJourneyInstrumentationTest` 専用）とexchange-import-ui lane（`ci.yml:825-876`、`ExchangeImportSurfaceInstrumentationTest` 専用）。どちらもsurface_organizer_uiで発火する。
- 凍結対象テストが無関係な変更を失敗させた実測: PR #437 run 35994286069（`organizer-unit-tests` 1/1818失敗、#352 oracleの `ClassCastException`）、main push run 36082413664（manual-organization-ui laneの `issue372ConsultationSessionSurvives...` 5秒 `ComposeTimeoutException`。#418分類コメント 5825373905 は非#418 signatureと判定）。

## Design

### Modules and interfaces

| Module | 変更 | interface/seam |
|---|---|---|
| `PreferenceManager2` | `exchangeAiConsultationEnabled` boolean preference（既定OFF）を追加 | 既存のpreference seam。新規interfaceなし |
| `ExperimentalFeaturesPreferences` | toggle rowを追加 | 既存の `SwitchPreference` 構成 |
| `ManualOrganizationPreferences` | 方法選択面compose分岐へtoggle条件を注入 | 既存のcompose分岐。新規seamなし |
| `ManualOrganizationRun`（変更なし） | OFF時導線はcompose面の分岐で実装するためcoordinator状態機械は触らない | — |
| `.github/workflows/ci.yml` | `organizer-unit-tests` jobの `--tests` filterへ除外を追加 | CI job定義 |
| `tools/repo-contract/ci_portfolio_map.yml` / `docs/engineering/ci-test-portfolio.md` | lane監査情報の更新 | 正本map |

**OFF時のrun導線の実装位置（spec open questionの決定）**: compose面の分岐で実装する。`ManualOrganizationPreferences.kt` の `State.ScopeConfirmed` compose branchで、toggle OFFのとき「AIに相談」armを描画せず、「このまま整理」armのみを描画する。さらにOFFのときは `ScopeConfirmed` 状態へ到達した瞬間に「このまま整理」を自動実行する（`LaunchedEffect` で `planWithConfirmedScope()` を呼ぶ）ことで、方法選択面を経由しない。

選択理由:

- run coordinatorの状態機械（`State.ScopeConfirmed` のpublish契約、`planWithConfirmedScope` の消費契約）を変更しない。attachIntent、claimGenerationEpoch、discard等の `ScopeConfirmed` を前提とする契約（spec 417で確立）はすべて維持される。coordinator側で分岐すると、ON/OFFで状態遷移系列が変わり、既存の状態機械oracle（unit test、instrumentation）に広い影響が出る。
- 自動実行は「方法選択面を出さない」を確実に満たす（armだけを消すと1フレームでも方法選択面が見える）。`LaunchedEffect` はcomposition後の1回だけ実行されるため、描画された方法選択面はOFF時には実質存在しない（face trace recorderで検証可能）。
- test-only render trace（`ManualOrganizationRunFaceTrace`）を使い、OFF時に `METHOD_CHOICE` faceがcompositionにcommitされないことをoracle化できる。

**focus挙動**: OFF時は `ScopeConfirmed` 到達 → 自動 `planWithConfirmedScope()` → planning/確認面へ遷移するため、方法選択面のfocus対象は組まれない。`focusRequester` は既存のplanning/確認面の先頭対象へそのまま進む。

### Data flow

- toggle ON/OFFはpreference（DataStore）からcompose面が読む。run coordinatorはpreferenceを読まない（状態機械への影響なし）。
- OFF時: run開始 → 検出 → 対象選択（候補あり時） → scope確定（`ScopeConfirmed` publish、現行どおり） → compose分岐が自動 `planWithConfirmedScope()` → planning → 確認 → 適用。
- ON時: 現行どおり。scope確定 → 方法選択面 → いずれかのarm。
- hub status card: 変更なし（run状態と独立のsession-scoped row）。OFFでもpre-open導線（`exchangeOpen` 引数）は機能し続ける。

### Alternatives rejected

- **coordinator側でOFF分岐を持つ**（scope確定時に `ScopeConfirmed` をpublishせず直接composed phaseへ進む）: 状態機械の契約がON/OFFで変わり、spec 417の状態oracle群に広い影響が出る。preferenceをcoordinatorへ注入すると、呼び出し側とテストが同じseamを使う規約に反する注入が増える。却下。
- **toggle OFF時に方法選択面の「AIに相談」armだけを消す**（自動実行なし）: 方法選択面自体は表示され続けるため、「方法選択面を出さない」というOutcomeを満たさない。却下。
- **AI交換テストを `@Ignore` 化する**: テストファイル保持の原則ではあるが、テスト本体の変更（#352の修正はPR #416に凍結済み）であり、lane外しで十分である。skip化はしない。却下。
- **AI交換のinstrumentation lane（method-choice-journey、exchange-import-ui）の起動条件を変える**: これらはsurface_organizer_ui変更PRでのみ動き、無関係な変更を失敗させていない。凍結の目的（無関係な変更がAI交換テストのflakyで失敗しない）はJVM gateの除外で達成される。laneの起動条件変更は別判断とする（spec open question、reviewで確定）。採用しない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | `exchangeAiConsultationEnabled`（既定OFF、`config_default_exchange_ai_consultation` bool resource）を追加 | preference正本 |
| `lawnchair/res/values/config.xml` | 既定値bool resourceを追加 | 既存パターン |
| `lawnchair/res/values/strings.xml` | toggle label/description、（必要なら）OFF時のplanning面導行文案 | UI文言正本 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | toggle row追加 | 実験的機能画面 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | 方法選択面compose分岐へOFF条件（arm非表示 + 自動planWithConfirmedScope） | 方法選択面の正本compose面 |
| `tests/unit/app/lawnchair/organizer/**`（新規） | preference既定値test、face分岐のunit oracle（可能な範囲） | JVM gate |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**`（新規） | OFF時run導線、arm非表示、ON時現行導線、focus挙動のinstrumentation oracle | UI契約の正証 |
| `.github/workflows/ci.yml` | `organizer-unit-tests` filterへ `--tests '!app.lawnchair.organizer.ui.exchange.*'` を追加 | lane外し |
| `tools/repo-contract/ci_portfolio_map.yml` | unit gateのfilter変更を反映（必要な注記） | 正本map |
| `docs/engineering/ci-test-portfolio.md` | 監査表へlane外しの記録 | 監査表正本 |
| `docs/product/organizer-to-be-ux.md` | D-04/D-17の凍結注記（OFF時は方法選択面を省略する変形であること） | 製品文書正本 |

## Migration and recovery

- schema/rule migration: なし。新preferenceは既定OFFで、既存環境では欠損値が既定OFFとして読める。
- failure中のrollback: toggleをONへ戻せば現行導線へ戻る（preferenceは即時反映）。
- release rollback/downgrade: preference keyが前versionに存在しない場合も、前versionはこのkeyを読まないため影響なし。
- backup/restore compatibility: 本preferenceをbackup契約へ含めない（既定OFFへ戻るのが安全側）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit test（preference既定値OFF）+ 実機screenshot | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（AI交換除外後のfilter）+ emulator |
| AC-2 | instrumentation test（OFF導線・arm非表示・候補0件） | `connectedLawnWithQuickstepGithubDebugAndroidTest`（manual-organization-ui lane相当class） |
| AC-3 | instrumentation test（ON時両arm、既存oracle維持） | 同上 + 既存 `entryFaceHasNoAiRowAndTheMethodChoiceFaceOffersItAfterConfirm` 等の継続成功 |
| AC-4 | 既存hub row oracleのOFF条件下での継続成功 | `OrganizerHubPreferencesInstrumentationTest` |
| AC-5 | PR diff確認 | PR本文へ記録 |
| AC-6 | CI run証跡（AI交換package除外での `organizer-unit-tests` 成功。#352 oracleがgateから外れたことの確認） | PR CI run |
| AC-7 | 実機screenshot/録画（OFF時の(a)(b)確認） | emulator、artifactをPRへlink |
| AC-8 | instrumentation test（focus対象） | manual-organization-ui lane相当class |

含めるべき観点: unit/contract（preference既定値、face分岐）、UI/accessibility（focus・TalkBack）、failure（toggle変更の進行中runへの影響は状態機械に書き込まないことをunit oracleで確認）。

## Documentation updates

- [x] spec status/history
- [ ] `docs/product/organizer-to-be-ux.md`（D-04/D-17の凍結注記）
- [ ] `docs/engineering/ci-test-portfolio.md`（lane外しの監査記録）
- [ ] CONTEXT.md: 不要（domain languageの追加なし）
- [ ] DESIGN.md: 不要（system structureの変更なし。run coordinatorの契約は不変）
- [ ] ADR: 不要（判断はメモ§4.6で承認済み。本planの実装位置選択はspec/planで記録し、変更困難な判断には至らない）
- [ ] AGENTS.md: 不要

## Execution checklist

- [ ] Current behavior reproduced（ON時の現行導線の既存oracleを確認）。
- [ ] Tests fail for the missing behavior（OFF導線・arm非表示の新oracleが先に失敗すること）。
- [ ] Minimal implementation completed（preference → toggle → compose分岐 → CI filter）。
- [ ] Migration/recovery verified（preference欠損環境での既定OFF、rollbackはtoggle ON）。
- [ ] Full relevant verification completed（spotlessCheck、unit gate、instrumentation lanes、build）。
- [ ] PR evidence and remaining risks recorded。
