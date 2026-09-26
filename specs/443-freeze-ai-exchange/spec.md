---
issue: "#443"
status: draft
requirements:
  - FR-017
  - D-016
updated: 2026-09-26
---

# AI相談（外部AI交換）の凍結適用: 実験的機能toggle（既定OFF）と既定導線からの除外

## Problem

再焦点化方針メモ（2026-09-24承認、Revision 5。§4.6 R-8、§4.9 FR-017/D-016）により、外部AI交換（AI相談、FR-017）の機能開発は凍結された（`docs/product/requirements.md` でFR-017は既にFrozen。D-016は#443の草案が正本）。しかし実装はまだ行われていない:

- 方法選択面の「AIに相談」arm（`ManualOrganizationFace.METHOD_CHOICE` のsibling choice）が既定導線に現存し、scope確定後のrunがAI相談へ進める。
- 凍結は「新規の入口を止める」ことと「既存のdurable状態（有効な依頼・取り込み済み提案、24h TTL。`ContextExportModels.kt` の `SESSION_TTL_MS = 24h`）の到達可能性を保つ」ことを同時に満たす必要がある。単に入口を隠すだけでは、期限切れまで到達可能であるべき既存状態が孤立する。
- CIのblocking laneにAI交換のテストが残っており、凍結対象テストが無関係な変更のmerge gateを失敗させている（実測: PR #437 run 35994286069 で `ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` の `ClassCastException` が `organizer-unit-tests` を失敗させた。main push run 36082413664 で `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` の5秒 `ComposeTimeoutException` が manual-organization-ui lane を失敗させた）。#352は「lane外しの後にcloseする」取り決めでclosed/not plannedとなっているが、lane外しがまだ行われていない。

## Outcome

「実験的機能: AIに相談」のtoggle（既定OFF）が設定の実験的機能に置かれる。OFFの間、方法選択面の「AIに相談」armは表示されず、run開始から直接「このまま整理」の流れへ進む。OFFでも、有効な依頼と取り込み済み提案がある間は、Organizer hubのstatus cardから期限切れまで到達できる。AI交換のテストはblocking CI laneの対象から外れ（テストファイルは保持）、凍結対象テストが無関係な変更のmerge gateを失敗させない。AI交換のコード・テストの削除と契約変更はない。

## Scope

- **toggleの導入**: 「実験的機能: AIに相談」のtoggle（既定OFF）を `ExperimentalFeaturesPreferences`（設定の実験的機能画面）へ追加する。preference keyは `PreferenceManager2` にboolean preference（`exchange_ai_consultation_enabled`、既定 `false`、`config.xml` のbool resource経由）として追加する。材料ではなく実験的機能に置く（メモ§4.6で確定済み）。
- **OFF時の入口の非表示**: 方法選択面の「AIに相談」arm（`ManualOrganizationPreferences.kt` の `method-choice-consult` item、`ManualOrganizationRun.kt` が公開する `State.ScopeConfirmed` を消費）を、toggle OFFのとき表示しない。既存の新規作成入口は方法選択面のみである（idle AI相談の新規作成入口は#417でRetire済み）。
- **OFF時のrun導線**: 方法選択面を出さず、scope確定後すぐ「このまま整理」の流れ（`planWithConfirmedScope`）へ進む。実装はcompose面の分岐（`ManualOrganizationPreferences.kt` の `State.ScopeConfirmed` compose branch）でOFFのとき「このまま整理」armだけを描画しないことで行うのではなく、run開始から直接「このまま整理」の流れへ進む（後述のDesign参照）。D-05のcanonical順序から方法選択面を省略する変形であり、検出・対象選択・適用の契約は変えない。
- **既存durable状態の到達可能性の維持**: toggle OFFでも、有効な依頼と取り込み済み提案がある間は、Organizer hubのstatus cardの依頼row（`ExchangeOpen.REQUEST`）と提案row（`ExchangeOpen.PENDING_REVIEW`）から期限切れまで到達可能に保つ。hubのstatus cardはrun状態と独立にsession-scopedな行を描画しており、toggle OFFはこの行を消さない。期限切れ時に行が消える現行契約も変えない。
- **AI交換テストのblocking lane外し**: `organizer-unit-tests` jobのGradle `--tests` filterへAI交換packageの除外（`--tests '!app.lawnchair.organizer.ui.exchange.*'`）を加え、AI交換classのJVM unit/contractテストをblocking gateの対象から外す。テストファイルは保持する（削除しない）。method-choice-journey laneとexchange-import-ui laneはsurface_organizer_ui発火のまま維持するが、これらは凍結対象のAI交換classだけを実行するlaneではないため、本specでは起動条件を変えない（下記Open questions参照）。
- **ON時の保証範囲**: toggleがONのときの動作は、データの安全に関わる不具合だけを直す。機能上の退行は凍結中は直さない（メモ§4.6で確定済み）。

## Non-goals

- AI交換コード・テストの削除、`ExchangeFlowUi.kt` 等の分割、大きなrefactor。
- 依頼・取り込み済み提案のdurable契約（spec 374/375、D-08/D-17）の変更。rebind、scope binding gate、ImportReviewの契約は触らない。
- #206（アプリ内Managed Grounded AI）の実装判断。
- 方法選択面自体の削除（ON時に戻す可能性を保つ）。
- #352のflaky修正（`ExchangeFlowStateHolderTest` の待ち合わせ修正はPR #416の凍結済み実装に含まれ、本Issueでは行わない。テストファイルは保持し、blocking laneから外すだけとする）。
- 関連open Issue（#323、#327、#328、#337、#345、#348、#351）のclose操作（本specの受入後、保守者がopen-issue-dispositions.mdに基づいて別途行う。本PRの範囲外）。

## Domain language

- **凍結（frozen）**: FR-017に適用された状態語彙。新機能・UX改善・contract拡張を止め、データ安全に関わるbug修正だけを行う。
- 既存語彙の追加・変更なし（`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: toggle OFFでrunが方法選択面を経ず「このまま整理」へ進む

Given toggleがOFF（既定）で、検出候補が1件以上ある状態からrunを開始した
When 対象選択を確定する（scope凍結）
Then 方法選択面（「整理案の作り方」見出しと「AIに相談」arm）は表示されない
And 「このまま整理」の流れ（planning → 確認面）へ直接進む
And 検出・対象選択・適用の契約は変わらず、書込み経路は追加・変更されない

### Scenario: toggle OFFで候補0件のrunも方法選択面を経ない

Given toggleがOFFで、検出候補が0件の状態からrunを開始した
When 検出が完了する（空cutがscope凍結へ直行する現行契約）
Then 方法選択面は表示されず、「このまま整理」の流れへ進む

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

### Scenario: toggle OFFで方法選択面が非表示でもdurable行は消えない

Given toggleがOFFで、runがscope確定後「このまま整理」へ進んでいる間に有効な依頼がある
When runの進行中にhubへ戻る
Then 依頼rowはrun状態と独立に表示され続ける（HUB-AC-02のrun-active hidingはrun操作に対してのみ働き、toggle OFFはこの行を消さない）

### Scenario: 失敗・edge case — toggle OFFへの変更は進行中のrunの状態を壊さない

Given toggleがONでrunがscope確定後の方法選択面に停まっている
When 設定でtoggleをOFFへ変える
Then 進行中のrunの状態（`State.ScopeConfirmed`）は変化しない（preference読み取りはcompose面とrun開始導線の分岐でのみ行い、run coordinatorの状態機械へは書き込まない）
And 次のrun開始からOFF導線が使われる

### Scenario: 失敗・edge case — AI交換テストがblocking gateを失敗させない

Given AI交換のJVMテストclassにflakyなテストが存在する（#352の `ExchangeFlowStateHolderTest`）
When AI交換のproduction/testコードを変更しないPRのCIが走る
Then `organizer-unit-tests` jobはAI交換packageを除外したfilterで実行され、AI交換テストの失敗はmerge gateを失敗させない
And AI交換テストのファイルはリポジトリに保持されている

## Data and state

- 読むdata: `PreferenceManager2` の新規boolean preference（既定OFF）。読み取りはcompose面（方法選択面の描画分岐）とrun導線（scope確定後の分岐）のみ。
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
- [ ] AC-2: toggle OFFのとき、(a) 方法選択面の「AIに相談」armが表示されない、(b) runがscope確定後、方法選択面を経ず「このまま整理」の流れへ進む（候補あり・候補0件の両方）。
- [ ] AC-3: toggle ONのとき、方法選択面に両armが表示され、現行契約どおり動作する。
- [ ] AC-4: toggle OFFでも、有効な依頼・取り込み済み提案がhub status cardに表示され、`ExchangeOpen.REQUEST` / `ExchangeOpen.PENDING_REVIEW` の両導線が機能する（期限切れ時に行が消えることも含む）。
- [ ] AC-5: AI交換のコード・テストに削除・契約変更がない（diffの範囲で確認。本変更が触るのはpreference追加、compose分岐、CI filter、docsのみであること）。
- [ ] AC-6: `organizer-unit-tests` jobの `--tests` filterがAI交換packageを除外し、AI交換テストのファイルがリポジトリに保持されている。CI portfolio map（`tools/repo-contract/ci_portfolio_map.yml`）と監査表（`docs/engineering/ci-test-portfolio.md`）が同じPRで更新されている。
- [ ] AC-7: 実機（エミュレータ）のスクリーンショットまたは録画で、toggle OFF時に(a)方法選択面の「AIに相談」armが表示されない、(b)runが方法選択面を経ず「このまま整理」へ進むことを確認する。
- [ ] AC-8: 方法選択面を省略した導線のfocus・TalkBack挙動が受入シナリオどおりである（OFF時のscope確定後の最初のfocus対象がplanning/確認面にある）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM unit test（preference既定値）+ 実機スクリーンショット（toggle rowの表示） |
| AC-2 | instrumentation test（OFF時のrun導線・arm非表示）+ 実機スクリーンショット/録画（AC-7と同一evidence） |
| AC-3 | instrumentation test（ON時の両arm表示、現行oracleの維持） |
| AC-4 | instrumentation test（既存hub row oracle `OrganizerHubPreferencesInstrumentationTest` の維持 + OFF条件下での再実行） |
| AC-5 | PR diffの範囲確認（PR本文へ記録） |
| AC-6 | CI run証跡: AI交換テストを変更しないPRで `organizer-unit-tests` がAI交換package除外で成功すること。加えて、#352のoracle（`ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`）を含むpackageがblocking gateから外れたことをCI runの実行結果で確認する |
| AC-7 | 実機（エミュレータ）スクリーンショットまたは録画。artifactとして保存し、PRへlinkする |
| AC-8 | instrumentation test（focus対象の検証） |

## Open questions

- **AI交換のinstrumentation lane（method-choice-journey、exchange-import-ui）の扱い**: これらはsurface_organizer_uiで発火する専用laneであり、AI交換classだけを実行する。凍結によりこれらのlaneをblocking対象から外すか（起動条件の変更）、現状維持とするか。本specは現状維持を出発点とするが、reviewで決定する（#352の指摘は「AI交換テストが無関係な変更を失敗させないこと」であり、method-choice-journey laneはsurface_organizer_uiを変更したPRでのみ動くため、無関係な変更を失敗させてはいない）。JVM unit gate（`organizer-unit-tests`）は全PRのpermanent gateであり、こちらの除外が#352の指摘の直接の解消である。
- **OFF時のrun導線の実装位置**: compose面の分岐（`State.ScopeConfirmed` compose branchでOFFのとき「このまま整理」armだけを描画する）か、run coordinator側の分岐（OFFのときscope確定が `ScopeConfirmed` をpublishせず直接composed phaseへ進む）か。specは「方法選択面を出さない」ことを要求するが、どちらの実装でも観測可能な振る舞いは満たす。planで確定する（Design参照）。
- **#352のテスト自体のskip化**: lane外しによってblocking gateから外れるため、`@Ignore` 等のskip化は不要と考える（テストファイル保持の原則にも整合する）。reviewで確認する。

## Change history

- 2026-09-26: Draft created for #443.
