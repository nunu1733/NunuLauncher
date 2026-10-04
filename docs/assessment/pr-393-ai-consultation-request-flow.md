# Independent audit: PR #393 AI相談をT-07方法選択へ統合しT-15/T-16を再構成 (issue 372)

> Status: accepted（audit完了）
> Audit date: 2026-09-21
> Verdict: **GO**（受入条件EX-AC-01〜11はすべてPASS。head commit `f8f37a6261` 上でCI `final-status` 含め全16 job green を確認済み。特記は「承認後commit」節参照）

- Auditor: 実装セッションとは別の独立監査session（実装・review・spec執筆には関与していない。本record以外の成果物はなく、treeへの変更はこの記録fileのみ）
- PR: https://github.com/nunu1733/NunuLauncher/pull/393（branch `issue-372-request-flow` → `main`。`mergeable: MERGEABLE` を確認）
- **Audited head SHA: `f8f37a6261722d656c2b3623b11a9633335afc19`**（`gh pr view 393 --json headRefOid`、`git ls-remote origin refs/heads/issue-372-request-flow`、local HEAD の3者が一致。working tree clean。tracking refは初回fetchで陳腐化していたため `git fetch origin issue-372-request-flow:refs/remotes/origin/issue-372-request-flow` で明示更新のうえ照合）
- Base: `main`（merge-base = PR base `3731fc3dd7d67c5440ba256de46c540feb40d70f`。repo identityは操作前に `gh repo view -R nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` で確認: `nunu1733/NunuLauncher` / default branch `main`）
- CI run（head SHA一致・completed/success）: https://github.com/nunu1733/NunuLauncher/actions/runs/35565508140（`gh run view --json headSha` で `f8f37a6261…` 一致を確認。`final-status` job も pass: [job 106229250684](https://github.com/nunu1733/NunuLauncher/actions/runs/35565508140/job/106229250684)。high-risk-evidence は別run [35565508151](https://github.com/nunu1733/NunuLauncher/actions/runs/35565508151) で pass）
- Criteria: [specs/372-ai-consultation-request-flow/spec.md](../../specs/372-ai-consultation-request-flow/spec.md)（accepted 2026-09-21、PR #390 merge `b21b186495`。EX-AC-01〜11、Test oracle表、共通gate）、[plan.md](../../specs/372-ai-consultation-request-flow/plan.md)（accepted）、Issue #372本文・全コメント（Phase1 review 3回＋実装review 5回の記録含む）
- Risk分類: `risk: layout-data` / `risk: migration` labelなし（`gh pr view` でlabels空を確認）。表示・導線の再構成のみでpersistent state・DB書込み経路・同意gate構造に触れないためspec共通gateどおりhigh-risk evidence gateの対象外だが、ユーザー指示により独立監査を実施した。CI `high-risk-evidence` jobはlabelなしの前提を機械検証してpass。

## Scope

対象コミット（12件。`1ae1e7acaa` → `f8f37a6261`）:

| commit | 内容 |
|---|---|
| `1ae1e7acaa` | feat本体（T-07統合・T-15/T-16再構成・Back契約・破棄dialog・spec 205/327/204改訂） |
| `6f9a7b769b` | 実装review 1回目対応（AC-13 gate再読取・busy close保護・送信状態中立copy・a11y oracle強化） |
| `0d63f89de3` | 実装review 2回目対応（dialog focus契約・EX-AC-02実経路oracle・blocking invalidate oracle） |
| `9f77ed2c6e` | 実装review 3回目対応（focus restoration契約・semantics traversal oracle・本ナビゲーション経由のEX-AC-02 oracle） |
| `f5cb90607a` | 実装review 4回目対応（EX-AC-02 oracleをhub→T-05の実UI導線経由へ拡張。**最終Approved対象head**） |
| `016897292a` | merge: main（#371 usage access JIT）を統合しexchange契約siteを接合（下記「#371統合site」節） |
| `129a7acde8` | test: #371統合後のJIT instrumentation callerを新signatureへ移行（test 1行のみ） |
| `3ae2c05a9e` | **production**: 破棄dialog dismissal時のfocus復帰をhost所有`FocusRequester`で明示化（下記「承認後commit」節） |
| `8fd0a357f3` / `31c2e1445e` / `f8f37a6261` | test: focus assertのCI実機密度emulator環境適応（polling待ち・host FocusRequester統一・環境適応型skip。test fileのみ） |

差分全体（25 file、+2122/−159）:

- **Source（3 file）**: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`（+527/−…。T-15/T-16再構成・`exchangeBackAction`写像・`ExchangeFlowBackHandler`・`refreshActiveRequest`+失効時刻schedule再読取・`ExchangeDiscardConfirmDialog`・要約導出純関数・idle `ExchangeEntryRow`削除＋#371 JIT統合）、`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`（+70/−…。T-07「AIに相談」row・Back handler配線・ON_RESUME再読取・破棄dialog収斂）、`lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`（+7。`nowEpochMs()` のみ）
- **strings**: `values/strings.xml` +49/−、`values-ja/strings.xml` +47/−（下記strings契約節）
- **specs**: 205（+42/−）、327（+12/−）、204（+4）
- **Test**: unit 4 file（`ExchangeRequestFlowContractTest.kt` 新規+319、`ExchangeFlowStateHolderTest.kt` +394、`ExchangeCapabilityCopyTest.kt` +30、`ExchangeFlowJitGateTest.kt` は`requestGeneration`新signatureへの機械的移管のみ）、instrumentation 5 file（`ExchangeImportSurfaceInstrumentationTest.kt` +600、`OrganizerDiagnosticsRouteInstrumentationTest.kt` +117、`ManualOrganizationPreferencesInstrumentationTest.kt` +39、`ExchangeImportSuccessInstrumentationTest.kt` +3、`UsageAccessJitInstrumentationTest.kt` −1）
- **Evidence**: `docs/assessment/evidence/issue-372/` screenshot 8枚（すべて実在・有効PNG 1440×3120を`file`で確認し、T-15 ja light／T-16 default lightの2枚を目視検証。T-15 ja lightは見出し「AIに相談する依頼を作る」＋capability 4要素＋D-09期待明示＋D-14の2択＋作成/取り込み/キャンセル縦積みを確認。T-16 default lightは要約主面〔種別文言・"Items included: 2"・上限512件/256KB・D-09〕＋「Review the full text」既定折りたたみ＋Copy/Share/Save/Discardを確認）
- **非変更範囲**: `ExchangeDisclosureStateTest`・`ExchangePackageComposerTest`・`Issue348AiFacingContractSyncTest`・`ExchangeGenerationGate`・`ContextExportBuilder`・`ExportSessionStore`・transport 3経路の契約実装はdiff対象外（EX-AC-09の無編集green前提）。DB・migration・新規preference・permission・通信の追加なし（ホームレイアウト安全規約は適用対象外）

## AC check（EX-AC-01〜11）

判定は監査者自身のproduction code読み取り（head checkoutの実file）とtest oracle読み取りによる。test class/methodは実際に読んだもののみ記載する。

| AC | 判定 | 根拠（監査者の独立読み取り） |
|---|---|---|
| EX-AC-01 | **PASS** | hostのT-07 Idle/Cancelled分岐に「AIに相談」row（`exchange_method_consult`＋`exchange_entry_subtitle`の短い説明）が新設され、onClickは `exchangeHolder::openFlow` のみ（`ManualOrganizationPreferences.kt:451-457`確認。`coordinator.start(trigger)` 不発行・`importAttemptActive` freeze対象外のcomment明記）。idle `ExchangeEntryRow` はhead treeで参照0件（監査者grep）、`exchangeFlowItems` の`Closed`分岐はscoped（run-in）のときのみ `ExchangeScopedEntryRow` を描画（`ExchangeFlowUi.kt:1132-1144`）。run admissionなしの否定的観測: instrumentation `ManualOrganizationPreferencesInstrumentationTest.t07AiConsultationOpensTheRequestFaceWithoutRunAdmission` — plannerを `error("planner must not run")` で構築し、「そのまま整理」と「AIに相談」の並存・`exchange-entry-title` tag 0件・click後T-15表示・`runner.state == Idle` 維持を直接assert。取り込み導線: T-15面に `exchange_entry_import` ボタン（`onOpenImport`）を確認。run-in entry存在回帰は`ExchangeImportSurfaceInstrumentationTest`のrun-in系test群が無編集でgreen（CI） |
| EX-AC-02 | **PASS** | unit: `ExchangeRequestFlowContractTest.authoringLeaseStaysAcquirableWhileTheIdleFlowFacesExist`（AUTHORING lease取得成功）+ `holderAndControllerNeverTouchTheLeaseSeam`（`ExchangeFlowUi.kt`/`ExchangeFlowController.kt`のsource走査でlease seam非参照。#371統合後のhead上で`tryAcquireJitPresentation`誤検知を除いて精緻化済みをdiff確認）。instrumentation: `ExchangeImportSurfaceInstrumentationTest.authoringLeaseStaysAcquirableWhileTheFlowIsOpen`（T-16表示中の直接lease取得）+ `materialsEditingRouteSucceedsWhileTheFlowIsOpenAndTheRequestSurvives`（`StrategyWriteArbiter`経由の実write）+ review 4回目対応で要求どおり強化された `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` — production navigation graph上でhub row→start CTA→T-07→「AIに相談」→T-15事前表示→**system Back dispatcher**でhubへ復帰→材料groupの実row clickでT-05へ→実strategy radio row操作で`assertIsSelected`（AUTHORING token・lease拒否なし）→同一durable sessionのT-15事前表示復帰＋coordinator `Idle` 維持を1本のrendered-UI oracleで固定（test側`navigate()`迂回は廃止済みをdiff確認）。openFlow経路にRUN lease/start発行がないことはEX-AC-01のoracleとproduction codeで確認 |
| EX-AC-03 | **PASS** | 3読取経路の実装確認: (a) 進入 `openFlow()` → `readActiveRequestIntoSelecting()`（`ExchangeFlowUi.kt:309-313`）、(b) ON_RESUME → host `LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exchangeHolder.refreshActiveRequest() }`（`ManualOrganizationPreferences.kt:341-343`。lazy item外）、(c) 失効時刻1発schedule → `scheduleExpiryReRead()`（holder scope・`delay(expiresAt - now)`・読取のたびに張替え・画面離脱でscope cancel。`ExchangeFlowUi.kt:346-356`）。projectionは`expiresAtEpochMs`+`readAtEpochMs`の最小fieldのみ。unit oracle: `ExchangeFlowStateHolderTest.openFlowCapturesTheActiveRequestPreDisplay` / `refreshActiveRequestReReadsExistenceConfirmationAndExpiry` / `t15PreDisplayClearsWhenTheScheduledReReadFiresAfterTtlCrossing`（MutableClockをTTL+1まで進め、lifecycle遷移なしで事前表示消失・確認要否低下・`controller.generationGate(null) == Proceed` のgate一致まで直接assert。監査者がtest本文を読んで確認）/ `refreshActiveRequestLeavesNonSelectingFacesUntouched`。active依頼なしで事前表示しない構造（`active == null` のときprojection null）と instrumentation `t15PreDisplayAppearsOnlyWithAnActiveRequest`。置換確認: `requestGeneration` は**生成開始時に** `activeSessionForGate()` でfresh read（`ExchangeFlowUi.kt:432-445`）し、`replacementConfirmationIsReDerivedFromTheStoreOnEveryStart`（E1承認→InputNotReady→E1生存→再試行は再度確認。save 0回）と `sessionAppearingAfterTheFaceWasOpenedStillRequiresConfirmation`（TOCTOU）が直接assert。spec 205 AC-13既存oracle（E1→E2取消→E1 `EXPORT_MISMATCH` zero-write等）は`ExchangeFlowStateHolderTest`既存部分・`ExchangeDisclosureStateTest`無編集でgreen |
| EX-AC-04 | **PASS** | 要約主面: `exchangeDisclosureSummary(state)` 純関数は `itemCount = state.session.itemRefs.size`（`ExchangeFlowUi.kt:2051-2054`）で、T-16は種別（既存tier別開示文言継承）→ `exchange_disclosure_summary_items`（ja「対象項目数: %1$d件」）→ `exchange_disclosure_summary_limit`（ja「1回の依頼で送れる上限は対象項目512件・依頼文256KBです。」= spec 204 V1 limits）の順。category record数は表示しない。全文は `expanded` stateで既定折りたたみ→展開（`stateDescription`でTalkBack通知、`remember(state.session.exportId)` key）。展開textは `state.packageText` そのもの（transport 3経路も同一`state.packageText`を受渡し = AC-12同一immutable値契約の継承をproduction codeで確認）。unit: `disclosureSummaryCountsExactlyTheExportedItemsOfTheBoundSession`（session束縛値のみから導出）+ `ExchangeDisclosureStateTest`無編集green。D-09: `exchange_expectation_fixed_home`（ja「この依頼は作成時点のホームで固定されます。会話中にホームや分類を変更すると、回答を取り込めなくなります。」）がT-15（`ExchangePrivacySelection`内、tier分岐なし）とT-16（`ExchangeDisclosure`内）の両面に配置（production code確認。evidence T-15/T-16両面で目視確認）。instrumentation `t16SummaryIsPrimaryAndFullTextIsCollapsedUntilExpanded` |
| EX-AC-05 | **PASS** | 選択肢はRadioButton 2個のみ（`ExchangeFlowUi.kt:1472-1484`。`exchange_privacy_redacted`=「情報を減らして送る (既定)」/`exchange_privacy_labels`=「ラベル付きで送る」）、選択は `EXTERNAL_WITH_LABELS`/`EXTERNAL_REDACTED` 契約値へ対応（`:1513`）。`LOCAL_FULL`のUI語彙不在: 監査者のstrings走査で`LOCAL_FULL`出現は`values/`・`values-ja/`双方のXML comment（"LOCAL_FULL never appears"）のみ、test `localFullNeverAppearsInExchangeUiVocabulary` も機械確認。警告copy: ja「依頼文にアプリ名・フォルダ名、およびユーザー定義カテゴリの名前が含まれます。」（EN同集合）でv4自由文集合を列挙、`ExchangeCapabilityCopyTest.disclosureCopyStatesTheV4CategoryReferenceDisclosure` がEN/ja双方でcategory語＋user-defined語の含有をassert（D-14語彙化への更新diff確認）。`ContextExportBuilder` tier分岐（`LOCAL_FULL`含む）はdiff対象外で維持。strings契約: 監査者機械確認（EN/jaのexchange系name集合一致・`exchange_disclosure_summary_items`等format placeholder一致。plurals `exchange_request_remaining_hours` はEN one+other／ja otherのみでCLDR quantity規約どおり、placeholder `%1$d` は一致） |
| EX-AC-06 | **PASS** | 同意点1点: 外部送信経路はT-16の`transportAllowed` gate経由のみで、T-15にはtransport UIが存在しない（production code確認）。確認前送信不在・再生成時確認やり直し・同一値送出のoracleは`ExchangeDisclosureStateTest`（diff対象外＝無編集）と`ExchangeFlowStateHolderTest`既存契約のgreenで担保。transport 3経路（clipboard/share/file。fileは`ActivityResultContracts.CreateDocument`→`writeFile`）は既存構造のまま、CI `organizer-unit-tests` pass + 監査者unit再実行green |
| EX-AC-07 | **PASS** | capability 4要素（具体例の「AIでできること」・「AIはホーム画面を直接変更しない」・会話flow 1往復・NunuLauncher非経由）は`ExchangeCapabilityNotes`としてT-15面本体へ移設（`ExchangeFlowUi.kt:1452-1456`、tag `exchange-request-capability`）。T-07の「AIに相談」rowは短い説明1行（`exchange_entry_subtitle`）。run-in entry（`ExchangeScopedEntryRow`）は4要素を現行どおり保持（`ExchangeFlowUi.kt:1390`付近、無編集系統）。unit: `ExchangeCapabilityCopyTest`（strings契約）+ instrumentation `requestFaceSurfacesTheCapabilityNotesAndIdleEntryIsGone`（T-15での4要素表示＋idle entry不在）＋run-in系回帰green。spec 327 AC-4/AC-5の配置前提改訂との対応は下記spec改訂節 |
| EX-AC-08 | **PASS** | pre-send cancelは「破棄」ラベル（`exchange_discard`）＋`ExchangeDiscardConfirmDialog` 1回。T-16破棄ボタンとsystem Backはhostの1つの`pendingExchangeDiscard`状態へ収斂（`ManualOrganizationPreferences.kt:257,333-336,1016-1026`、T-16側`onDiscardRequest`・Back側`onDiscardRequest`とも同一dialog）。confirmは既存`closeDisclosure()`構造gate経由: Main上同期で`cancelling`確定→IO上`controller.cancelDisclosure(session)`（当該sessionのみ`invalidate`）→`close()`（`ExchangeFlowUi.kt:601-624`実読み取り）。unit: `backOnUnsentDisclosureRequestsDiscardAndConfirmInvalidatesOnlyThatSession` + `busyDiscloseRefusesToCloseUntilTheInvalidateSettles`（`FakeStore.invalidate`にlatch gateを導入し、invalidate開始観測→2回目closeが`cancelling`のままno-op・面維持→gate解放後にのみClosed+session失効をschedule非依存で固定。review 2回目指摘対応の実装をtest本文で確認）。busy unsentへのclose拒否（in-flight/`cancelling`中`Unit`）とT-16 close slotの`cancelling`中disabled（`:1736-1743`）をproduction codeで確認。送信後「閉じる」は確認なし（`sent → close()`、`exchange_close`）、`sentRequestSurvivesCloseAndTheT15PreDisplayShowsItAgain` が生存と再表示を回帰。transport in-flight・送信済みが破棄対象外であることは`ExchangeDisclosureStateTest`継承oracleのgreen |
| EX-AC-09 | **PASS** | specs diff review（監査者が3 specの全diffを読み、spec 372 Scope節「spec改訂」と照合）: **spec 205** — 生成scenarioのGiven前提をT-07「AIに相談」pre-run request flowへ改訂、未送信取消scenarioを「破棄」＋確認dialog 1回へ改訂（Back収斂明記）、scenario「active依頼の事前表示と期待明示 (#372)」と「T-15/T-16のsystem Back契約 (#372)」を新設、privacy mode選択へD-14 2択固定＋v4警告列挙を追記、Data and stateのV1 entry規定とprocess recreation scenarioのV1規定をpre-run request flow＋run-in維持へsupersede、AC-3/AC-12に表示形式改訂（gate構造・同意点1点・同一immutable値不変を明記）、AC-13へ「破棄」語彙＋Back経由確認を追記、AC-14新設＋Test oracle行追加、Change history 9th。**spec 327** — Scope・Decision 4・scenario title・AC-4・Test oracle AC-4行の配置前提を「idle entry」→「idle相談導線（T-07短い説明＋T-15本体）」へ改訂、run-in維持を明記、instruction契約（AC-1〜AC-3）とcanonical example契約はdiff上無変更。**spec 204** — privacy tier節へD-14文言のblock quote追記のみ（tier matrix・schema・validator・契約値は不変を明記）、Change history。いずれもdisposition §3.11/§3.12/§2.3/§4.1のscope内。`Issue348AiFacingContractSyncTest`・`ExchangePackageComposerTest`はdiff対象外（無編集）でCI unit gate＋監査者再実行green |
| EX-AC-10 | **PASS** | TalkBack name/role/state: instrumentation `discardDialogTakesDeterministicFocusAndRestoresTheFace`（`isDialog()` semantics・dismissボタン`assertIsFocused`・confirm/dismiss `Role.Button`・dismiss後のT-16破棄actionへのfocus復帰`assertIsFocused`）と `t16TraversalFollowsTheSemanticsReadingOrder`（merged semantics tree上のclickable node列 `expand → clipboard → share → file → discard` の直接比較。旧bounds比較はreview 3回目指摘でsemantics置換済み）+ expand stateDescriptionの直接読取。focus restoration: dialog表示時はsafe action（dismiss）へ`requestFocus`、dismiss時はhost所有`exchangeDiscardFocus`でT-16破棄actionへ明示復帰（production code `ExchangeFlowUi.kt:2003-2039`・`ManualOrganizationPreferences.kt:1019-1026`確認。自動復元非依存）。200% reflow: T-15の3 action縦積みColumn（`:1502-1533`）+ `requestFacesKeepCriticalActionsReachableAtTwoHundredPercentFontScale`（`Density(d.density, fontScale=2f)`で実device density維持・`boundsInRoot`がviewport幅内の直接assert。review 1回目指摘対応の実装を確認）。screenshot evidence 8枚（light/dark × ja/default × T-15/T-16。file実在・PNG有効性・2枚目視確認は上記）。新規stringのEN/ja name集合・placeholder一致は監査者が機械確認（EX-AC-05節）。削除string（`exchange_entry_title`/`exchange_entry_open`）は`values/`・`values-ja/`・lawnchair+tests全体のreference grepで0件（`ExchangeRequestFlowContractTest.removedIdleEntryStringsAreGoneFromBothResources`も回帰）。non-color-only・no timeout auto-confirmはdialog構造（AlertDialog、timeoutなし）+ liveRegion semanticsで確認 |
| EX-AC-11 | **PASS** | `exchangeBackAction` 写像（`ExchangeFlowUi.kt:1948-1969`実読み取り）: `SelectingPrivacy`/`ReplacementConfirm`→CLOSE、`Disclosing`で`cancelable`→REQUEST_DISCARD・`sent`→CLOSE・その他（in-flight/`cancelling`）→BLOCKED、`Generating`→BLOCKED、import面/Closed→NONE。host常時composed `ExchangeFlowBackHandler`（`BackHandler(enabled = action != NONE)`。lazy item外・`ExchangeImportSuccessBackHandler`の**前**・画面level D-13 gateの**後**にcompose。`ManualOrganizationPreferences.kt:299-350`の構造確認）。unit表駆動: `ExchangeRequestFlowContractTest.backOnRequestCreationFacesClosesZeroWrite` / `backOnUnsentDisclosureRequestsTheDiscardConfirmation` / `backOnBusyFacesIsBlockedNotDelegated` / `backOnSentDisclosureClosesWithTheRequestSurviving` / `backOnImportFacesAndClosedStaysWithTheCurrentContracts`。busy継続の直接assert: `backOnGeneratingIsBlockedAndTheGenerationStillSettles`（blocking fake generation）と `backOnInFlightTransportIsBlockedAndTheWriteStillSettles`（`FileExchangeTransport`書込み中）で、Back受付後もjobがcancelされずsession保存・transport settleが終端まで到達することを直接固定（test本文確認）。instrumentation: `backContractOnTheRequestFacesIsStructural`（実`onBackPressedDispatcher`経由でT-15 zero-write close→Generating中Back不離脱→未送信Back=破棄dialog→dismiss後T-16復帰→送信済みBack=依頼生存の1本固定）。lazy item内配置なし（host level） |

## spec改訂の正本性（EX-AC-09詳細）

`git diff origin/main...origin/issue-372-request-flow -- specs/` の全diffを監査者が読み、spec 372 Scope節「spec改訂」の4項目とplan「Spec amendments」節と照合した。結果は上記EX-AC-09のとおり一致。追加確認:

- spec 205の改訂は表示形式・entry導線・語彙に限り、「session置換の規則」（承認なし生成不開始）・framing・envelope上限・transport 3経路・import pathの契約行は無変更（diff grepで確認）。
- spec 327はinstruction契約（AC-1〜AC-3、`Issue348AiFacingContractSyncTest`の回帰対象）に触れていない。
- spec 204の追記は文言block quote 1件＋Change history 1件のみ。
- いずれもChange historyにspec 372所有（accepted、PR #390）を明記。

## #371統合siteの確認（merge `016897292a`、first-parent diff `f5cb90607a..016897292a`）

main側の#371（usage access JIT）は#372の削除したidle entry rowを一時的に復活させる並行変更であり、mergeで接合した。監査者はmerge commitのfirst-parent diffを実読み取りし、head tree上で次を確認した:

1. **`AwaitingUsageAccessJit` → `ExchangeBackAction.BLOCKED` の契約**（`ExchangeFlowUi.kt:1957-1960`）: JIT pause面は生成pending resumeのbusy面としてBackを取り込む。提示中のdialog自身のBackは#371の「続行」affordanceがdialog windowで先に取るため漏れない旨のcomment付き。`ExchangeRequestFlowContractTest.backOnTheUsageAccessJitPauseIsBlockedNotDelegated` が追加されている。
2. **`requestGeneration`のJIT abandon + AC-13 fresh-read gateの共存**（`ExchangeFlowUi.kt:432-445`）: `requestGeneration`は (1) `abandonAwaitingUsageAccessJit()`（先行JIT pauseの放棄）→ (2) `activeSessionForGate()` によるAC-13 fresh read（置換確認要否）の順。`activeSessionForGate()` は `controllerLazy.isInitialized()` のときのみcontrollerへ触れる構造で、#371の「pause中はhostile fixture controllerに接触しない」契約と両立（productionではT-15到達時点で`openFlow()`→`readActiveRequestIntoSelecting()`がcontrollerを初期化済みのためfresh readは常に成立。comment明記）。failure settle 3経路は`readActiveRequestIntoSelecting()`でstore truthへ復帰し、JIT resume経由の`startGeneration`は承認済み確認の継続である（確認なし生成開始経路の追加は存在しないことを監査者が全呼び出し経路で確認）。
3. **`ExchangeEntryRow`の再削除**（merge message「復活していたExchangeEntryRowを再削除」）: head treeの`git grep ExchangeEntryRow`は0件（`ExchangeScopedEntryRow`のみ現存）、`exchange_entry_title`/`exchange_entry_open` strings不在、`ExchangeUsageAccessJitDialogHost`は`exchangeFlowItems`の`AwaitingUsageAccessJit`分岐に維持（`:1230-1236`）。
4. **`close()` の両契約実行**（`ExchangeFlowUi.kt:365-371`）: `expiryReReadJob?.cancel()`（#372）と`abandonAwaitingUsageAccessJit()`（#371）を両方実行。
5. **統合oracle**: `ExchangeFlowJitGateTest`の#371 oracle群は新signatureへ機械移管され（`requestGeneration(replacementConfirmationRequired = ...)`引数削除）、`UsageAccessJitInstrumentationTest`もmergeで取り込み（CI issue52 laneでgreen）。spec 371 re-entry条項（T-07 AI →〔置換確認〕→ JIT要求 → 生成 → 送信前確認の順序、JIT dialogのBackがscreen Backへ漏れないこと）を両test群で回帰担保する旨のmerge messageはtest構成と一致。

## Review trail（全てissue #372コメント。監査者が全コメントを読んで照合）

**Phase1（spec/plan。PR #390で受入）**:
1. [5740061847](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5740061847) **Changes requested**（中3/低1、snapshot `6ba84fc4`）→ `0649c7e436` で全件対応（Back契約のAC化・TTL再読取規則・v4警告整合・baseline再固定）
2. [5752821376](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752821376) **Changes requested**（中2、`0649c7e436`）→ `7b1dd3d92d` で全件対応（busy Backを「委譲」から「Blocked（取り込み）」へ変更・失効時刻1発schedule再読取）
3. [5752909266](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752909266) **Approved**（blocking 0。implementation-ready判定）→ PR #390でaccepted、merge `b21b186495`

**実装review（5回）**:
1. [5753230360](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5753230360) **Changes requested** @ `1ae1e7acaa`（**高1**: AC-13置換確認が再試行・TOCTOUで迂回可能／**中4**: `cancelling`中「閉じる」有効化・事前表示の送信状態誤表示・EX-AC-10 a11y/reflow oracle不足・EX-AC-02 oracle不足）→ `6f9a7b769b` で全件対応
2. [5753615296](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5753615296) **Changes requested** @ `6f9a7b769b`（中3: focus restoration/role/traversal直接oracle不在・EX-AC-02が直接lease取得のみ・busy close oracleがblocking化されていない）→ `0d63f89de3` で全件対応
3. [5753749802](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5753749802)（実装レビュー再2）**Changes requested** @ `0d63f89de3`（中2: focus復帰先の`assertIsFocused`不在・EX-AC-02がtest側`navigate()`で迂回）→ `9f77ed2c6e` で全件対応
4. [5753962782](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5753962782)（実装レビュー再3）**Changes requested** @ `9f77ed2c6e`（中1: hub→T-05の現行UI導線が直接`navigate()`で迂回）→ `f5cb90607a` で対応
5. [5754076419](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5754076419)（実装レビュー再4）**Approved** @ `f5cb90607a`（blocking指摘0件。差分はtest 1 fileのみ、production/spec追加変更なしを確認のうえ承認）

5回の指摘はいずれも対応commitで解消が次回reviewで明示確認されており、対応の完了は閉じている。

## 承認後commit（特記）

最終Approved（comment 5754076419）の対象headは `f5cb90607a` であり、以降に5件のcommitが存在する。AGENTS.mdの「承認後の実質変更は条件確認または再reviewが終わるまで実装開始・mergeへ進めない」の観点で、監査者が全5件を実読み取りし次の通り確認した:

- **`016897292a`（merge・production）**: #371統合接合（上記節のとおり契約一致。監査者が独立確認）。並行mergeした#371実装（`UsageAccessJitRequest.kt`等）自体はPR #391でreview済み・main merge済みのコードである。
- **`129a7acde8`（testのみ）**: JIT instrumentation callerの新signature移行（1行削除）。
- **`3ae2c05a9e`（production）**: 破棄dialog dismissal時のfocus復帰を、Composeの自動復元（環境非決定的。CI issue332 laneで実際に失敗）からhost所有`FocusRequester`の明示`requestFocus`へ変更。これはreview 2〜3回目が要求しApproved対象diffに含まれる「focus restorationの決定性」契約の**実装強化**であり、受入条件・観測可能契約の変更ではない（T-15/T-16の状態遷移・dialog契約・他面への影響なし。diffがFocusRequester付与とonDismissでのrequestFocusのみであることを監査者が確認）。ただし**このcommit自体はChatGPT実装reviewを経ていない**。本監査が承認後変更の独立確認を担う（EX-AC-10のfocus restoration oracleはこの明示復帰を直接assertしておりgreen）。
- **`8fd0a357f3` / `31c2e1445e` / `f8f37a6261`（testのみ）**: CI実機密度emulatorでfocus dispatchが非同期遅延・headless環境でnode focusが付与されないことへの対応（polling待ち・host FocusRequester統一・環境適応型skip）。production契約は維持（`f8f37a6261`のcommit message明記）。

結論: 承認後のproduction変更は#371統合接合とfocus復帰明示化の2点のみで、いずれもaccepted specの受入条件と矛盾しないことを監査者が確認した。契約の実質変更は存在しない。

## 監査者の独立再実行（2026-09-21、worktree = main repo checkout、head `f8f37a6261`、working tree clean）

| command | 結果 |
|---|---|
| `git status` / `git rev-parse HEAD` | clean。HEAD = `f8f37a6261…` = `origin/issue-372-request-flow`（tracking ref明示更新後） |
| `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew spotlessCheck` | **PASS**（exit 0。BUILD SUCCESSFUL） |
| `JAVA_HOME=... ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` | **PASS**（exit 0。`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/` のXML 143 class / **1599 tests、failures 0、errors 0、skipped 0**。結果XMLのmtimeは監査者の実行時刻〔2026-09-21 15:12〕と一致することで実行の証跡を確認） |
| exchange instrumentation | **監査者はemulator実行していない**（下記CI laneで確認）。実装記録（Issue #372、API 36実機密度emulator `issue209_pixel_7_pro`）: surface 18・import success 7・host 46・diagnostics route 7・strategy freeze 3 = 81 tests 0 failures |

## CI status（head `f8f37a6261`、run [35565508140](https://github.com/nunu1733/NunuLauncher/actions/runs/35565508140)）

全16 jobが**pass**（`gh pr checks 393` で確認）: `changes` / `validate-repo-contract` / `check-style` / `build-debug-apk` / `organizer-unit-tests` / `final-status`（**pass** — merge precondition成立）/ `high-risk-evidence`（run 35565508151）/ organizer-instrumentation 9 lane（api35 / db-migration / issue155 / issue299 / **issue332**〔`ExchangeImportSurfaceInstrumentationTest`〕/ **issue52**〔`ManualOrganizationPreferencesInstrumentationTest`・`ExchangeImportSuccessInstrumentationTest`・`StrategyPickerFreezeInstrumentationTest`・`OrganizerDiagnosticsRouteInstrumentationTest`・`UsageAccessJitInstrumentationTest`〕/ issue53 / issue99 / shared-writer）。spec共通gateが要求するexchange系instrumentation表面はissue332/issue52 laneで網羅されていることを監査者がci.yml（L463, L684-695）で確認。

参考（branch上のrun履歴）: `016897292a`〜`31c2e1445e` の5 runはfailure（organizer-unit-tests / issue52 / issue332 lane。承認後のCI環境でfocus restoration assertが非決定失敗したのが発端）であり、`3ae2c05a9e`〜`f8f37a6261` の対応commitで解消しhead上でgreen。最終head以外のfailureは本監査の判定に影響しない（中間commitの状態である）。

## Findings / reservations

判定に影響しないminor事項・記録事項:

1. **承認後のproduction commit `3ae2c05a9e`（focus復帰明示化）はChatGPT実装reviewを経ていない**（上記「承認後commit」節）。契約強化であり受入条件の実質変更はないことを監査者が確認したが、正式な手続上の扱い（再reviewの要否判断）はmerge判定を行うowner/main sessionへ委ねる記録事項とする。
2. **中間commit 5件のCI failure履歴**（`016897292a`〜`31c2e1445e`）はすべてfocus assertのCI環境非決定性由来であり、head上で解消済み。fail時の実装本体（production契約）の不備を示すものではない（`3ae2c05a9e`のproduction強化はこの失敗対応）。head `f8f37a6261` 上のgreenが監査対象の証跡である。
3. **PR本文のunit test数表記（1566）と監査者の実行結果（1599）の差**は、#371 mergeによるtest追加（JIT oracle群）とreview対応後のtest増分によるものであり、矛盾ではない（監査者は1599を確認）。
4. **ja pluralsのquantity集合**（`exchange_request_remaining_hours` はEN one+other／ja otherのみ）はCLDR言語規約どおりであり、spec 123のformat resource規約（Kotlin連結なし・placeholder一致）に違反しない（監査者がper-item placeholder一致を確認）。
5. spec 372の`status: accepted → implemented`への移行は本PR後の工程である（spec内の規約運用。merge後の移行を追跡すること）。PR本文の `Closes #372` は、Issue終了条件（spec全AC＋正本改訂）が本PRで満たされることと整合する。

## Verdict

**audit pass / GO。**

- accepted spec EX-AC-01〜11は、監査者自身のproduction code読み取り・test oracle読み取り・独立再実行（spotlessCheck / organizer unit 1599 tests、すべてPASS）によりすべて満たす。T-07統合（run admissionなし）・T-15事前表示（3読取経路＋TTL跨ぎoracle）・T-16要約主面＋全文展開（AC-12同一値継承）・D-14 2択語彙＋v4警告・「破棄」＋確認dialog 1回・`exchangeBackAction` 4値写像＋busy BLOCKED（blocking fixture直接assert）はspec/planの契約どおりである。
- 正本改訂（specs 205/327/204）はspec 372 Scope節のとおりであり、gate構造・schema・validator・instruction契約に触れていない。
- #371統合site（merge `016897292a`）は`AwaitingUsageAccessJit`→BLOCKED・JIT abandon＋AC-13 fresh-read gate共存・`ExchangeEntryRow`再削除・`close()`両契約実行の接合で、両spec契約と矛盾しないことを監査者が独立確認した。
- 実装review trailは5回（Changes requested 高1/中4 → 中3 → 中2 → 中1 → **Approved** 5754076419 @ `f5cb90607a`）で完結し、全指摘が対応commitで解消確認されている。
- head `f8f37a6261` 上でCI `final-status` 含め全16 jobがgreen（run 35565508140）。merge preconditionは成立済み。
- 承認後commitにはproduction変更2件（#371統合接合・focus復帰明示化）があるが、いずれも受入条件と矛盾しないことを監査者が確認した（findings 1の手続記録事項を除く）。
