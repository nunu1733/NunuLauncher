---
issue: "#443"
status: draft
requirements:
  - FR-017
  - D-016
updated: 2026-09-26
---

# AI相談（外部AI交換）の凍結適用: 実験的機能toggle（既定OFF）と既定導線からの除外

> Revision 2: 2026-09-26 — Phase1 review（[Issue #443 comment](https://github.com/nunu1733/NunuLauncher/issues/443#issuecomment-5845095513)）の指摘1〜4を反映。指摘1: Gradle CLIの `--tests '!...'` は除外演算子ではない（ローカル実測で確認。`!` はリテラルinclude patternとして扱われ、negation-onlyは `No tests found for given includes` で失敗する）ため、build.gradleのTest task filter（`excludeTestsMatching`）へ実装手段を変更。指摘2: blocking CI対象表を本specへ確定。指摘3: toggle契約をlive readへ一本化。指摘4: face trace oracleを廃止し、method-choice node非出現のoracleへ変更。
> Revision 3: 2026-09-26 — 再review（[Issue #443 comment](https://github.com/nunu1733/NunuLauncher/issues/443#issuecomment-5845366170)）の指摘1〜3を反映。指摘1: OFF自動遷移effectを `PreferenceLazyColumn` の外側のcomposable scopeへhoistし、IO dispatchとrunId keyによる重複実行防止をplanへ明記（`ScopeConfirmed` branchはLazyListScope DSL内で `@Composable` contextではない）。指摘2: 「設定変更後に開始したrunへ適用」へ契約を単純化（run面を離れると `dismiss()` でparked runがcancelされるため、設定変更後に同じparked runへ戻る実ユーザー導線は存在しない）。指摘3: gate外し後の自動観測は行わない（main/weekly CIも同じjobを使うため除外が適用される。凍結suiteの継続観測はlocal/manual実行のみと正確に記録）。

## Problem

再焦点化方針メモ（2026-09-24承認、Revision 5。§4.6 R-8、§4.9 FR-017/D-016）により、外部AI交換（AI相談、FR-017）の機能開発は凍結された（`docs/product/requirements.md` でFR-017は既にFrozen。D-016は#443の草案が正本）。しかし実装はまだ行われていない:

- 方法選択面の「AIに相談」arm（`ManualOrganizationFace.METHOD_CHOICE` のsibling choice）が既定導線に現存し、scope確定後のrunがAI相談へ進める。
- 凍結は「新規の入口を止める」ことと「既存のdurable状態（有効な依頼・取り込み済み提案、24h TTL。`ContextExportModels.kt` の `SESSION_TTL_MS = 24h`）の到達可能性を保つ」ことを同時に満たす必要がある。単に入口を隠すだけでは、期限切れまで到達可能であるべき既存状態が孤立する。
- CIのblocking laneにAI交換のテストが残っており、凍結対象テストが無関係な変更のmerge gateを失敗させている（実測: PR #437 run 35994286069 で `ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` の `ClassCastException` が `organizer-unit-tests` を失敗させた。main push run 36082413664 で `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` の5秒 `ComposeTimeoutException` が manual-organization-ui lane を失敗させた）。#352は「lane外しの後にcloseする」取り決めでclosed/not plannedとなっているが、lane外しがまだ行われていない。

## Outcome

「実験的機能: AIに相談」のtoggle（既定OFF）が設定の実験的機能に置かれる。OFFの間、方法選択面の「AIに相談」armは表示されず、run開始から直接「このまま整理」の流れへ進む。OFFでも、有効な依頼と取り込み済み提案がある間は、Organizer hubのstatus cardから期限切れまで到達できる。凍結対象のAI交換JVMテストはblockingの `organizer-unit-tests` gateの対象から外れ（テストファイルは保持）、凍結対象テストが無関係な変更のmerge gateを失敗させない。AI交換のコード・テストの削除と契約変更はない。

## Scope

- **toggleの導入**: 「実験的機能: AIに相談」のtoggle（既定OFF）を `ExperimentalFeaturesPreferences`（設定の実験的機能画面）へ追加する。preference keyは `PreferenceManager2` にboolean preference（`exchange_ai_consultation_enabled`、既定 `false`、`config.xml` のbool resource経由）として追加する。材料ではなく実験的機能に置く（メモ§4.6で確定済み）。
- **OFF時の入口の非表示**: 方法選択面の「AIに相談」arm（`ManualOrganizationPreferences.kt` の `method-choice-consult` item、`ManualOrganizationRun.kt` が公開する `State.ScopeConfirmed` を消費）を、toggle OFFのとき表示しない。既存の新規作成入口は方法選択面のみである（idle AI相談の新規作成入口は#417でRetire済み）。
- **OFF時のrun導線**: 方法選択面を出さず、scope確定後すぐ「このまま整理」の流れ（`planWithConfirmedScope`）へ進む。実装はcompose面の分岐で行う（Design参照）。D-05のcanonical順序から方法選択面を省略する変形であり、検出・対象選択・適用の契約は変えない。
- **既存durable状態の到達可能性の維持**: toggle OFFでも、有効な依頼と取り込み済み提案がある間は、Organizer hubのstatus cardの依頼row（`ExchangeOpen.REQUEST`）と提案row（`ExchangeOpen.PENDING_REVIEW`）から期限切れまで到達可能に保つ。hubのstatus cardはrun状態と独立にsession-scopedな行を描画しており、toggle OFFはこの行を消さない。期限切れ時に行が消える現行契約も変えない。
- **AI交換JVMテストのblocking gate外し**: `organizer-unit-tests` jobから `app.lawnchair.organizer.ui.exchange.*` package（AI交換UI層の6 class、139 test。`ExchangeFlowStateHolderTest` の#352 oracleを含む）を除外する。実装手段はGradle CLIのnegation patternではなく、`build.gradle` のTest task filterへのproperty-gatedな `excludeTestsMatching` 追加である（Gradleの `--tests` CLIは除外patternを持たない。Design参照）。テストファイルは保持する（削除しない）。gate外し後、凍結suiteは自動CI（main/weekly/scheduled含む）では実行されず、local/manual実行のみで観測する。
- **ON時の保証範囲**: toggleがONのときの動作は、データの安全に関わる不具合だけを直す。機能上の退行は凍結中は直さない（メモ§4.6で確定済み）。

### Blocking CI対象表（凍結テストの扱いの確定）

| 対象 | 現在のCI位置 | 本specの決定 | 理由 |
|---|---|---|---|
| `tests/unit/app/lawnchair/organizer/ui/exchange/*`（6 class、#352 oracle含む） | `organizer-unit-tests`（Permanent gate、全source PRで必須） | **gate対象から除外する**（property-gated `excludeTestsMatching`） | #352の実測どおり無関係な変更のmerge gateを失敗させる。凍結対象のAI交換UI層である |
| `tests/unit/app/lawnchair/organizer/integration/exchange/*`、`personalization.exchange/*` 等（pipeline/contract層） | 同上 | **gate対象に維持する** | AI相談UIの凍結でも、durable契約・import pipelineの契約（spec 374/375、D-08/D-17）はNon-goalsであり、これらのregression oracleは維持する。#352のflakyはこの層にない |
| `ExchangeImportSurfaceInstrumentationTest`（exchange-import-ui lane専用） | Conditional lane（surface_organizer_ui発火時のみ） | **現状維持** | surface_organizer_uiを変更するPRでのみ動き、無関係な変更を失敗させない。凍結でもdurable/import契約のUI oracleとして維持する |
| `MethodChoiceConnectedJourneyInstrumentationTest`（method-choice-journey lane専用） | Conditional lane（surface_organizer_ui発火時のみ） | **現状維持** | 同上。方法選択面自体は削除しないため、ON時契約のoracleとして維持する |
| `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvives...`（manual-organization-ui lane、mixed class内の1 test） | Conditional lane（surface_organizer_ui発火時のみ） | **現状維持** | このtestは本specのAC-4（OFFでもhub rowからdurable状態へ到達できる）と同じ契約を検証するproduction-route oracleであり、凍結でも維持すべき契約である。mixed class内のmethod単位除外はinstrumentation runnerのclass filterでは行えず、`@Ignore`化はテストファイルの実質的無効化になるため行わない。5秒 `ComposeTimeoutException` の失敗は#418分類（comment 5825373905）で非#418 signatureとして記録済みであり、environment系の追跡は#418/#304が所有する |
| `ManualOrganizationPreferencesInstrumentationTest` 内のAI arm test群 | 同上 | **現状維持し、OFF時oracleを追加する** | 既存testはON時契約（`exchange_method_consult` が表示される）を検証する。実装はtoggle既定OFFのため、これらのtestにはON条件を明示的に設定するtest seamが必要になる（plan参照） |

## Non-goals

- AI交換コード・テストの削除、`ExchangeFlowUi.kt` 等の分割、大きなrefactor。
- 依頼・取り込み済み提案のdurable契約（spec 374/375、D-08/D-17）の変更。rebind、scope binding gate、ImportReviewの契約は触らない。
- #206（アプリ内Managed Grounded AI）の実装判断。
- 方法選択面自体の削除（ON時に戻す可能性を保つ）。
- #352のflaky修正（`ExchangeFlowStateHolderTest` の待ち合わせ修正はPR #416の凍結済み実装に含まれ、本Issueでは行わない。テストファイルは保持し、blocking gateから外すだけとする）。
- #372 ComposeTimeout失敗のroot cause調査（#418/#304が所有するenvironment系追跡。本Issueはmanual laneの構成を変えない）。
- 関連open Issue（#323、#327、#328、#337、#345、#348、#351）のclose操作（本specの受入後、保守者がopen-issue-dispositions.mdに基づいて別途行う。本PRの範囲外）。

## Domain language

- **凍結（frozen）**: FR-017に適用された状態語彙。新機能・UX改善・contract拡張を止め、データ安全に関わるbug修正だけを行う。
- 既存語彙の追加・変更なし（`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: toggle OFFでrunが方法選択面を経ず「このまま整理」へ進む

Given toggleがOFF（既定）で、検出候補が1件以上ある状態からrunを開始した
When 対象選択を確定する（scope凍結）
Then 方法選択面のheadline（「整理案の作り方」）と「AIに相談」armはsemantics treeに現れない
And 「このまま整理」の流れ（planning → 確認面）へ直接進む
And 検出・対象選択・適用の契約は変わらず、書込み経路は追加・変更されない

### Scenario: toggle OFFで候補0件のrunも方法選択面を経ない

Given toggleがOFFで、検出候補が0件の状態からrunを開始した
When 検出が完了する（空cutがscope凍結へ直行する現行契約）
Then 方法選択面のnodeは現れず、「このまま整理」の流れへ進む

### Scenario: toggle ONで現行導線へ戻る

Given toggleがONである
When runを開始しscopeを確定する
Then 方法選択面に「このまま整理」と「AIに相談」の両armが表示される（現行契約と同じ）

### Scenario: toggle OFFでもhub status cardから既存状態へ到達できる

Given toggleがOFFで、有効な依頼（24h以内に作成）がある
When Organizer hubを開く
Then status cardに依頼row（`ExchangeOpen.REQUEST` 導線）が表示され、開くとT-15（request face）へ到達する
And 取り込み済み提案があるときは提案row（`ExchangeOpen.PENDING_REVIEW` 導線）が表示され、開くとImportReview（T-18）へ到達する

### Scenario: toggle OFFでも期限切れ時に行が消える

Given toggleがOFFで、依頼または提案が24h TTLを過ぎた
When Organizer hubを開く
Then 対応するrowは表示されない（現行のTTL契約どおり）

### Scenario: toggle OFFへの変更は、その後に開始・再開されるrunへ適用される

Given toggleがONでrunがscope確定後の方法選択面に停まっている
When 設定でtoggleをOFFへ変える（run面を離れる時点で現行の `dismiss()` 契約によりparked runはcancelされる）
Then toggleをOFFへ変えた後に開始するrunは、OFF導線（方法選択面を経ず「このまま整理」へ進む）で動く
And preference読み取りはcompose面のみで、run coordinatorの状態機械（`State.ScopeConfirmed` のpublish契約）は変化しない
And run面のcomposition中にpreference値が変化した場合のlive反映は実装の性質であり、受入条件は「toggle変更後に開始するrun」を対象とする

### Scenario: 失敗・edge case — AI交換JVMテストがblocking gateを失敗させない

Given AI交換UI層のJVMテストclassにflakyなテストが存在する（#352の `ExchangeFlowStateHolderTest`）
When AI交換のproduction/testコードを変更しないPRのCIが走る
Then `organizer-unit-tests` jobは `app.lawnchair.organizer.ui.exchange.*` を除外したfilterで実行され、AI交換UI層テストの失敗はmerge gateを失敗させない
And AI交換テストのファイルはリポジトリに保持されている
And pipeline/contract層のexchangeテスト（`integration.exchange`、`personalization.exchange`）は引き続きgateで実行されている

## Data and state

- 読むdata: `PreferenceManager2` の新規boolean preference（既定OFF）。読み取りはcompose面（方法選択面の描画分岐と自動遷移）のみ。run coordinatorはpreferenceを読まない。
- 永続化するdata: 上記preference（DataStore。既存のpreference永続化機構を使い、新schemaはなし）。
- layout DBへの書込み: なし（本変更は書込み経路を追加・変更しない。run導線の分岐は適用経路の前段の選択だけを変える）。
- migration、backup/restore、rollbackへの影響: なし（新preferenceは既定OFFで、backup/restore契約に含めない。preferenceが欠けている環境では既定OFFとして動作する）。

## Permissions, privacy, and security

- 新規permission、外部送信、sensitive dataの追加: なし。toggle OFFはAI相談の新規入口を止めるため、privacyの面では現状より制限側である。toggle ONのときの挙動（user-mediated text交換、外部provider接続なし）はspec 205でreview済みの契約から変わらない。

## Accessibility and localization

- toggle rowは既存の `SwitchPreference` 構成（label + description）を使い、既存の実験的機能画面のfocus/TalkBack契約に従う。
- 方法選択面を省略した導線のfocus挙動: OFF時はscope確定後、最初のfocus対象がplanning/確認面の先頭になること（方法選択面のfocus対象が存在しないことによるfocus喪失がないこと）を受入シナリオで確認する。
- toggleの文言は英語 `values/strings.xml` に追加する（ja翻訳は既存の翻訳flowに従う。翻訳の不在は受入を妨げない）。

## Acceptance criteria

- [ ] AC-1: toggleが `ExperimentalFeaturesPreferences` に存在し、既定OFFである。preference keyは `exchange_ai_consultation_enabled` で、ON/OFFの状態がDataStoreへ永続化される。
- [ ] AC-2: toggle OFFのとき、(a) 方法選択面のheadlineと「AIに相談」armがsemantics treeに現れない、(b) runがscope確定後、方法選択面を経ず「このまま整理」の流れへ進む（候補あり・候補0件の両方）。
- [ ] AC-3: toggle ONのとき、方法選択面に両armが表示され、現行契約どおり動作する。
- [ ] AC-4: toggle OFFでも、有効な依頼・取り込み済み提案がhub status cardに表示され、`ExchangeOpen.REQUEST` / `ExchangeOpen.PENDING_REVIEW` の両導線が機能する（期限切れ時に行が消えることも含む）。
- [ ] AC-5: AI交換のコード・テストに削除・契約変更がない（diffの範囲で確認。本変更が触るのはpreference追加、compose分岐、build.gradleのtest filter、CI workflow、docsのみであること）。
- [ ] AC-6: `organizer-unit-tests` jobが `app.lawnchair.organizer.ui.exchange.*` を除外して実行される。実行結果XMLで、(a) `ExchangeFlowStateHolderTest`（#352 oracleを含む）が実行対象に含まれないこと、(b) `integration.exchange` / `personalization.exchange` のcontract testが実行されていること、の両方を確認する。AI交換テストのファイルはリポジトリに保持されている。CI portfolio map（`tools/repo-contract/ci_portfolio_map.yml`）と監査表（`docs/engineering/ci-test-portfolio.md`）が同じPRで更新されている。
- [ ] AC-7: 実機（エミュレータ）のスクリーンショットまたは録画で、toggle OFF時に(a)方法選択面の「AIに相談」armが表示されない、(b)runが方法選択面を経ず「このまま整理」へ進むことを確認する。
- [ ] AC-8: 方法選択面を省略した導線のfocus・TalkBack挙動が受入シナリオどおりである（OFF時のscope確定後の最初のfocus対象がplanning/確認面にある）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM unit test（preference既定値）+ 実機スクリーンショット（toggle rowの表示） |
| AC-2 | instrumentation test（OFF時のrun導線・method-choice node非出現）+ 実機スクリーンショット/録画（AC-7と同一evidence） |
| AC-3 | instrumentation test（ON時の両arm表示、現行oracleの維持。既存AI arm testにON条件seamを通じて継続成功） |
| AC-4 | instrumentation test（既存hub row oracle `OrganizerHubPreferencesInstrumentationTest` の維持 + OFF条件下での再実行） |
| AC-5 | PR diffの範囲確認（PR本文へ記録） |
| AC-6 | CI run証跡 + 実行結果XML: `organizer-unit-tests` が成功し、XML中に `ui.exchange` classの結果がなく、`integration.exchange` / `personalization.exchange` classの結果が存在すること。#352 oracle（`ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`）がgateから外れたことをXMLの不在で確認する |
| AC-7 | 実機（エミュレータ）スクリーンショットまたは録画。artifactとして保存し、PRへlinkする |
| AC-8 | instrumentation test（focus対象の検証） |

## Open questions

- なし（Phase1 review2回分で確定した: lane対象表は本specの表のとおり。OFF導線はcompose面の分岐 + LazyColumn外の自動遷移effect。toggle契約は「toggle変更後に開始するrunへ適用」。gate外し後の凍結suiteは自動CIでは実行せずlocal/manualのみ。実装開始前の未解決事項は残っていない）。

## Change history

- 2026-09-26: Draft created for #443.
- 2026-09-26: Revision 2 — Phase1 review指摘1〜4を反映（除外seamをbuild.gradle filterへ変更、blocking CI対象表を追加、toggle契約をlive readへ確定、face oracleをmethod-choice node非出現oracleへ変更）。
- 2026-09-26: Revision 3 — 再review指摘1〜3を反映（自動遷移effectの配置・IO dispatch・重複実行防止をplanへ明記、toggle契約を「toggle変更後に開始するrun」へ単純化、gate外し後の自動観測なしを明記）。
