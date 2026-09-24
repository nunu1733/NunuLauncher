# Implementation Plan: CustomCategoryPreferences instrumentationのCI接続とAC-13自動assert

> Issue: #342
> Spec: [spec.md](./spec.md)
> Status: accepted（2026-09-24。Phase1 re-review [Approved](https://github.com/nunu1733/NunuLauncher/issues/342#issuecomment-5810304096)、snapshot `06441db265`。Phase 2実装は本契約に従う）

## Current evidence

> **Re-entry検証 (2026-09-24; baseline `origin/main` = `b146a63557`)**: 以下の事実をmerged HEAD上で再確認した。本blockが2026-09-17/2026-09-19の旧evidence記述に優先する。旧baseline `3076bdae7e` 以降mainは260 commit進み、次の点が変わった。(1) lane改名: Issue #422によりjob IDは `organizer-instrumentation-category-override-tests` となった（ci.yml 779行目。旧名 `organizer-instrumentation-issue99-tests` は存在しない）。API 36 / pixel_7_pro、起動条件は `surface_organizer_ui` || fullのまま。class listは814行目で `app.lawnchair.organizer.ui.CategoryOverridePreferencesInstrumentationTest` の1 classのみ。`CustomCategoryPreferences` はci.yml全文に依然出現しない（grep exit 1）。`final-status` は970行目、needsに当該laneを含む（982行目）。(2) test file: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/CustomCategoryPreferencesInstrumentationTest.kt` は306行（旧snapshot 298行から+16/-8。rename query修正とfake store Committed契約の差分）、4 methodの名前は不変。vacuous raw-ID文は92-93行目に残存。(3) portfolio doc書換え: `docs/engineering/ci-test-portfolio.md` は#422でPortfolio model + lane↔surface map（human mirror）へ書き換えられ、旧ownership表は存在しない。normative edge正本は `tools/repo-contract/ci_portfolio_map.yml`（category-override lane → surface_organizer_ui、44-46行目）。category-override laneのhuman-readable説明行は「category override authoring UI（#99）」の記載。(4) spec 336 AC-12は149行目、AC-13は150行目で文言不変（fileは他所で+2行）。(5) requirementsはFR-010が19行目、NFR-009が40行目で文言不変。(6) 文字列はrepo root `res/values/strings.xml` の564-595行目に `organizer_custom_category_*` / `organizer_category_override_custom_*`、`res/values-ja/strings.xml` にも存在。`lawnchair/res/values/strings.xml` には該当文字列ゼロ。(7) production UIは本Issue向けに不変: summary nodeは `focusRequester`（150行目）+ `focusable()`（151行目）+ `liveRegion = Polite`（153行目）で、`LaunchedEffect`（135-136行目）がeditor/dialog非表示時にfocus要求。`CategoryEntryRow` は `.clickable(onClick = onRename)`（352行目）+ `semantics { contentDescription = renameLabel }`（353行目）で明示的Roleなし。監査記録Finding 1（test未routing）は実質正確のまま。

以下は初版作成時（2026-09-17、baseline `8fd05a40d51abd24b40a7b93579bb9b76d046f75`）の記録であり、上記re-entry blockと矛盾するlane名・行番号は旧情報として読む。

- **test実体**: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/CustomCategoryPreferencesInstrumentationTest.kt`（初版時298行、現306行、4 test method）。`createComposeRule()` + `UserDefinedCategoryAuthoringCoordinator(store, overrides)` にin-memory fake storeを注入するstatelessなCompose UI test。Launcher DB・app状態を使わない。
  - 既存4 method: `createFlowRendersTypedDuplicateFeedbackAndListsTheCreatedEntry` / `deleteConfirmationStatesCountAndAutomaticReturnWithoutRemapOption` / `partialDeleteRendersTruthfullyAndRetryCompletesTheDelete` / `renameKeepsTheEntryPresentedAsTheSameCategory`。
  - **既存不具合**: `createFlow...` 内の `composeRule.onAllNodesWithText(userId.value).fetchSemanticsNodes().isEmpty()`（現92-93行目）は結果を捨てる式文で、raw ID非表示の確認として機能していない。
- **CI未接続の確認**: `.github/workflows/ci.yml` 全文中に `CustomCategoryPreferences` は出現しない（grep exit 1で機械確認）。全laneのclass listを監査記録Finding 1も同様に確認済み。
- **接続先laneの現状**: `organizer-instrumentation-category-override-tests`（ci.yml 779行目、class list 814行目）はAPI 36 / pixel_7_pro / KVMのclean emulatorで、現在 `app.lawnchair.organizer.ui.CategoryOverridePreferencesInstrumentationTest` の1 classのみを実行する。jobは `final-status` のneedsに既に含まれる（970行目定義、982行目needs。class追加だけでmerge gate効果を得る。job追加・needs編集は不要）。laneの役割commentは「semantics, focus, font-scale, and mutation interactions」の検証を目的とし、本testと同質である。
- **参照できるassert pattern（同一module、CI実行済み）**: `CategoryOverridePreferencesInstrumentationTest`（category-override lane）が、`onNodeWithContentDescription(...).assertHasClickAction()`、cancel/save/error後の `assertIsFocused()`（Compose input focus復帰）、`InputMode.Keyboard` + `performKeyInput(Key.DirectionDown/DirectionCenter)`（DPAD）、`performSemanticsAction(SemanticsActions.OnClick)`（Switch Access等価）、`CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f))`（200% font scale）、`boundsInRoot.height` による48dp検証のすべての確立patternを持つ。本Issueの新assertはこのpatternに倣う。
- **対象UIのa11y実装（assertの根拠）**: `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomCategoryPreferences.kt`
  - summary node: `focusRequester` + `focusable()` + `semantics { liveRegion = LiveRegionMode.Polite }`（135-136行目の `LaunchedEffect` でeditor/dialog非表示時にfocus要求）。
  - entry行（`CategoryEntryRow`）: `heightIn(min = 48.dp)` + `contentDescription = "Rename <名前>"`（`R.string.organizer_custom_category_rename_action`）。明示的Roleは持たない。
  - delete行（`CategoryEntryDeleteRow`）: `contentDescription = "Delete <名前>"`（`organizer_custom_category_delete_action`）、「Custom」marker（`organizer_category_override_custom_marker`）をdescriptionテキストとして表示。
  - editor: `testTag("custom-category-name-field")`、typed errorは `supportingText`（`organizer_custom_category_error_duplicate_name` 等）。
  - 削除確認: `AlertDialog`（plural text `organizer_custom_category_delete_text`）。partial delete状態: `organizer_custom_category_retry` / `back_to_list` 行。
- **文字列resource**: repo root `res/values/strings.xml` 564-595行目に `organizer_custom_category_*` / `organizer_category_override_custom_*` の英語、`res/values-ja/strings.xml` に日本語が存在する。assertに必要な文字列はすべて既存resourceから解決可能。
- **portfolio正本の現状**: `docs/engineering/ci-test-portfolio.md`（Issue #422版）はPortfolio model + lane↔surface mapのhuman mirrorであり、normative edge正本は `tools/repo-contract/ci_portfolio_map.yml`（category-override lane → surface_organizer_ui、44-46行目）。同一surface内のclass追加ではedgeが変わらないためmap file編集は不要。本Issueではcategory-override laneのhuman-readable説明行への#336管理UI追記のみを行う。
- **baseline以降のmain差分 (2026-09-19時点)**: `8fd05a40d5` → `3076bdae7e`（PR #350/#353/#355/#363/#364）はexchange / #327/#328/#337 と Organizer UX文書domainであり、本Issue対象fileには無関係。初版作成時の差分 `703afe3f4c` → `8fd05a40d5`（PR #349）も同様にexchange表面のみだった。

## Design

### Modules and interfaces

production interface・seamの変更は**ない**。変更はtest sourceとworkflow/documentのみ。

- 変更module 1: `tests/organizer-instrumentation/.../CustomCategoryPreferencesInstrumentationTest.kt` — 既存seam（`UserDefinedCategoryAuthoringCoordinator` + fake `UserDefinedCategoryStore` / `CategoryOverrideStore`）をそのまま使う。production型を新たに露出させない。
- 変更module 2: `.github/workflows/ci.yml` — `organizer-instrumentation-category-override-tests` のscript class listへの1 class追加と、lane先頭commentへの#336管理UI追記。
- 変更module 3: `docs/engineering/ci-test-portfolio.md` — category-override laneのhuman-readable説明行へ#336管理UIのco-occupant追記（map fileのedge変更なし）。

### 接続先laneの決定

**推奨: `organizer-instrumentation-category-override-tests` のclass listへ追加する。**（旧名 `organizer-instrumentation-issue99-tests` はIssue #422で改名され現存しない。先例PR #355のissue52 lane追加patternは改名後も有効。）

理由: (1) laneの目的文（semantics/focus/font-scale/mutation interactionの常設検証）が本testの性質と同一であり、カテゴリauthoring UI同士の系列である。(2) 本testはfake storeのみのstateless Compose testで、app状態の隔離要件がなく、独立emulator jobを新設する隔離上の価値がない。(3) runner work増（emulator boot + Gradle setup 1回分）を避けられる。ci-test-portfolio.mdがtotal runner workの増加を明示的に警戒している。(4) issue text自身が「API 35または36 laneのclass list」での実行をoutcomeとして示している。(5) 既存lane class listへのclass追加は最新mainでも継続する既定patternである（2026-09-17の初版後もPR #355がissue52 laneへ `ExchangeImportSuccessInstrumentationTest` とpreferences destination testの `StrategyPickerFreezeInstrumentationTest` を追加しており、preferences系UI testの既存lane追加先例が増えた）。

**Rejected: 専用lane新設**（`organizer-instrumentation-issue332-tests` の#345先例）— issue332 surfaceはexchange系の独立surfaceだったのに対し、本testはcategory-override laneと同種・同familyのfake-store Compose testであり、新jobの運用コストに見合う隔離・帰属の利得がない。report帰属は失敗logのclass名で十分識別可能。

**Rejected: API 35 lane** — 同laneの責務はproduction input seam互換とnested transactionのAPI 35回帰であり、UI a11y evidenceを置く場所ではない。

**Rejected: `organizer-unit-tests` への移動** — instrumentation test（`createComposeRule`、Android resource解決）はJVM unit test taskでは実行できない。

### 新assertの設計（test内）

既存patternに倣い、1個以上の新test methodとして追加する。method構成の目安（実装時に1:1である必要はないが、spec AC-2〜AC-6の各項がどのmethodで満たされるかをPRで対応付ける）:

1. `rowsExposeLocalizedActionLabelsAndLiveRegion` — entry行・delete行の `contentDescription`（`rename_action` / `delete_action` を名前補間で解決）とclick action、summary nodeの `SemanticsProperties.LiveRegion == Polite` をassert。semantic `Role` はassertしない（productionに明示的Roleがなく、要求すればproduction変更が必要になりstop条件に触れる）。
2. `editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode` — 作成保存後・改名保存後・cancel後・削除確定後に `onNodeWithText(organizer_custom_category_summary)` が `assertIsFocused()` になることを `waitUntil` で確認（category-override laneの `cancelRestoresFocus...` / `editorIsReadable...RestoresFocusAfterSave` と同型）。**対象はeditor/dialog exitに限定する。** partial delete状態の「Back to categories」後はassertしない（現productionのfocus `LaunchedEffect` keyが `creating, editorTarget, pendingDelete, statusMessage` で `partialDeleteTarget` を含まず、当該経路ではkeyが変化しないためfocus requestが発火しない。spec Non-goals対象。要求する場合はproduction変更が必要でstop条件に触れる）。**注意: `assertIsFocused()` はCompose/keyboard input focusの検証であり、TalkBack accessibility focusの証拠として扱わない。** TalkBack側のcoverageはmethod 1のlabel+click actionとlive region assertが担う。
3. `keyboardDpadActivatesCreateActionFromTheSummaryNode` — `InputMode.Keyboard` 要求、summary `requestFocus()`、`DirectionDown` → `DirectionCenter` で作成editorが開くこと。
4. `switchEquivalentSemanticsActivationOpensTheRenameEditor` — `performSemanticsAction(SemanticsActions.OnClick)` で改名editorが開くこと。
5. `rowsRemainReachableAtTwoHundredPercentFontScale` — `fontScale = 2f` で、50 code point名のentry行・「Custom」marker・作成action・削除dialogが `assertIsDisplayed()` / `performScrollToNode` で到達可能。48dp高さ検証を同methodか独立methodに含める。
6. raw ID非表示 — 共有helper `assertNoRawIdsPresent(seedId: String, mintedId: String)`（または等価物）をComposeTestRule/test class上に定義し、次の5状態から呼び出す: (1) list、(2) 作成editor、(3) 改名editor、(4) 削除確認dialog、(5) partial delete状態。helperは全semantics nodeの `Text`・`EditableText`・`ContentDescription` 値をsubstring/contains一致（exact一致ではない）で走査し、seed ID（`3f2b8c4e-1234-4abc-9de0-1234567890ab` 形式fixture）と作成pathでmintされるIDの両方を検査する。既存92-93行目のvacuous式文はこのhelper呼び出しに置き換える（結果を捨てない実assertion）。

注意事項:

- 既存4 methodの振る舞いは変更しない（改名・期待値の非必要な変更はしない。vacuous文の実assertion化だけを行う）。既存 `partialDeleteRendersTruthfully...` のpartial-delete flow assert自体は維持するが、focus復帰assertはmethod 2のeditor/dialog範囲に限定する（partial deleteの「Back to categories」後は対象外。spec Non-goals）。
- focus復帰assertの対象nodeはUI実装の `FocusRequester` が付くsummary node（`organizer_custom_category_summary` テキスト）であり、実装詳細（testTag追加等）のproduction変更を要求しない。production変更が必要になった時点でstop条件（下記）。
- 同期は既存の `composeRule.waitUntil(5_000)` 規約に従う。`InputModeManager` / `LocalDensity` の取得もcategory-override lane testの既存patternを使う。

### Data flow

test内に完結: fake storeのseed → coordinator注入 → Compose操作 → semantics tree走査/assert。CI側は既存laneと同一（checkout → JDK/Gradle → KVM → emulator起動 → `connectedLawnWithQuickstepGithubDebugAndroidTest` → 失敗時report upload）。

### Alternatives rejected

上記「接続先laneの決定」に集約。ADRは不要（変更困難な判断ではなく、lane構成はportfolio docに記録を残して将来再評価可能）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/CustomCategoryPreferencesInstrumentationTest.kt` | spec AC-2〜AC-6のassert method追加、既存vacuous raw-ID文の共有helper `assertNoRawIdsPresent` 呼び出しへの置換（AC-6） | 対象test表面そのもの。既存seam・fixtureを再利用 |
| `.github/workflows/ci.yml` | `organizer-instrumentation-category-override-tests` のclass listへ `app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest` を追加、lane commentに#336管理UIの追記（AC-1） | merge gate常設化の正本構成。`final-status` は既存needsで効果を受ける |
| `docs/engineering/ci-test-portfolio.md` | category-override laneのhuman-readable説明行へ#336管理UIのco-occupant追記（map fileのedge変更なしを検証） | Issue参考欄がlane責務の正本と指定するdoc。変更と同じPRで更新 |

## Migration and recovery

- schema/rule migration: なし。
- failure中のrollback: なし（test・workflow・docのみの変更）。
- release rollback / downgrade: 影響なし。差し戻しはclass list行の削除（revert）で復元でき、永続状態を残さない。
- backup/restore compatibility: 影響なし。
- 高リスクgate: workflow文書の規約によりtest-only変更は独立audit要件の対象外。`ci.yml` 変更は `ci` path filterにより全job起動の自己検証を受ける。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 実装PRの `pull_request` CI runでlane jobが新classを実行し成功。`gh api repos/nunu1733/NunuLauncher/actions/runs/<id>` でhead SHA照合 | GitHub Actions（API 36 emulator job） |
| AC-2〜AC-6 | 新assert methodを含むclassの成功。局所再現は実機/emulatorで実行 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest`（API 36 emulator）。compile確認は `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` |
| AC-7 | `git diff --name-status <base>..<head>` が3 pathのみを示す | local |
| AC-8 | lane成功 + `final-status` successのrun URL | GitHub Actions |

共通gate: `./gradlew spotlessCheck`（test fileのformatting）、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（既存unit suiteへの無影響確認）。

含まれる観点: UI/accessibility（本Issue本体）、failure injection（report artifact pathは既存lane stepが担当、変更しない）。

## Documentation updates

- [x] spec status/history（本spec: accepted 2026-09-24）
- [ ] CONTEXT.md — domain language変更なしのため不要
- [ ] DESIGN.md — system structure変更なしのため不要
- [ ] ADR — lane接続判断は変更困難な判断でないため不要（portfolio docへ記録）
- [ ] AGENTS.md — verified command変更なしのため不要
- [ ] spec 336 — 本Issueでは編集しない（status: implementedのまま。実装完了後のAC-12/AC-13自動検証部分の充足はIssue #342のPR記録で示す）

## Execution checklist

- [ ] 現状の再現確認: class listに当該classが無いこと（`grep CustomCategoryPreferences .github/workflows/ci.yml` → exit 1）とvacuous assertの所在を実装branch上で確認。
- [ ] 新assertを先に追加し、意図的な失敗（例: 一時的にliveRegion assertを外す等）で検出性を確認してから通す（失敗を再現するテストの規約。本質が「未接続だった」こと自体はAC-1のCI実行記録が代替証拠になる）。
- [ ] class list追加 + lane comment更新。
- [ ] portfolio doc更新。
- [ ] local verification（上表）実行、PRへ結果記録。
- [ ] 実装PRは `Refs #342` とし、Issue終了条件を満たす最終PRのみ `Closes #342` を含める。

## Stop conditions

実装中に次が判明した場合は実装を止めIssueへ記録する: (1) 新assertがproduction変更（testTag追加、semantics変更等）を要求する — spec non-goalsに反するため #342 のscope再判断が必要。(2) category-override lane上での2 class同時実行がemulator flake（#300系のwindow focus問題等）を示す — その場合は専用lane新設の再評価をIssueで決める。(3) 既存4 methodが現在のmainで失敗する — 別のregressionであり本Issueで握らない。

## Explicitly unverified areas

- 新assertがCI emulator上で通ること（font scale・focusの挙動は同一patternのcategory-override lane testがCI成功済みという間接証拠のみ。本準備taskではdevice実行をしていない。再entry検証（2026-09-24）も文書taskであり、新assertのdevice実行とlane runtime増分は依然未検証）。
- class追加によるlane実行時間の増分（目安 +1〜3分。PRのCI実測で確認する）。
- `ci-test-portfolio.md` の他lane行の欠落（旧ownership表時代のstaleness観察は#422書換えによりobsolete。現docの他lane記載の不足は本Issueで直さない）。
