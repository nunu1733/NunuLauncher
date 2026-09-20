# Implementation Plan: AI相談のT-07方法選択統合と依頼作成（T-15）・送信前確認（T-16）の再構成

> Issue: #372
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-21時点のmain（`13c95eafe6`。PR #389 merge後。`a2b6aba318` → `13c95eafe6` には
#365（正本改訂・PR #379）/ #368（strategy picker材料面移設・PR #384）/ #369（run面表示統合・
PR #387）/ #370（onboarding hub接続・PR #389）が含まれる）での確認である。初版planが
前提としていた#368/#369 draft時点の記述は、このre-entryで現行mainの実装に合わせて
更新した（Phase1 review指摘4のre-anchor）。

### 現行のexchange flow UI（`ExchangeFlowUi.kt`、1801行）

`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`:

- `ExchangeScreen` sealed interface（L87-141付近）: `Closed` / `SelectingPrivacy` /
  `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / `ImportOutcomeScreen` /
  `ImportSuccess`。flow画面はhost面のinline blockとして描画される（新規destinationではない）。
  `SelectingPrivacy(replacementConfirmationRequired: Boolean)` はpre-display用のsession情報を
  保持しない（本Issueでprojectionを追加する）。
- `ExchangeDisclosureState`（L149付近）: 生成済みpackageのimmutableな状態束
  （`session` / `packageText` / `tier` / `sent` / `transportInFlight` / `cancelling`）。
  `cancelable`（未送信・非in-flight・非cancelling）と`transportAllowed`が同一値であり、
  確認と送信の順序契約（spec 205 AC-12）の構造gateである。
- `ExchangeFlowStateHolder`（L194付近）: `openFlow()`（L233。`activeSession()` の有無のみを
  `SelectingPrivacy.replacementConfirmationRequired` に写像し、session自体は保持しない）、
  `requestGeneration`（L255。確認要否で`ReplacementConfirm`へ分岐）、
  `confirmReplacementAndGenerate`（L270付近）/ `declineReplacement`（辞退 = `close()`。
  既存依頼を生存させflowを閉じる）、`generate`/`generateScoped`（L282/L296）、
  `closeDisclosure`（L342。Main上で`cancelling`確定 → IO上で`controller.cancelDisclosure` →
  `close()`。**確認dialogは存在しない**。cancelableでない場合は`close()`のみ）、
  import attempt機構（spec 328: attempt anchor・`importAttemptActive` /
  `importContinuationActive` freeze述語）。
- **holderのoperation scope**: hostが`val scope = rememberCoroutineScope()`で作り
  `ExchangeFlowStateHolder`へ渡す。`generate`/`generateScoped`（L284/L302）、
  `closeDisclosure`（L354）、`startTransport`（L420。`FileExchangeTransport`の`writeFile`
  書込みを含む）、import系（L490/L597/L711）はすべてこのscopeで`launch(Dispatchers.IO)`
  する。**compositionがdisposeされるとscopeはcancelされる** — したがってbusy state中の
  画面離脱はoperationを中断させる（Phase1 review 2回目指摘1の実装根拠）。
- `exchangeFlowItems`（L825/L840。test用overload付き）: host面の`LazyListScope`へflow blockを
  供給する。`Closed`時にscopedなら`ExchangeScopedEntryRow`（run-in）、idleなら
  `ExchangeEntryRow`。flow画面ごとにkey付きitem、末尾にstatus行。
- `ExchangeEntryRow`（L964、V-27）: idle entry row。title＋subtitle＋
  `ExchangeCapabilityNotes`＋「依頼文を作成」（`openFlow`）「回答を取り込む」（`openImport`）
  の2ボタン。testTag `exchange-entry-*`。**本Issueの撤去対象**。
- `ExchangeCapabilityNotes`（L1006）＋example resource ids: spec 327のcapability説明4要素。
  idle/run-in両entryから使われている（本IssueでT-15へ移設し、run-inは継続利用）。
- `ExchangePrivacySelection`（L1079、V-29）: tier radio 2択（`exchange_privacy_redacted` 既定 /
  `exchange_privacy_labels`）＋label付き警告＋置換notice（`requiresConfirmation`時）＋
  「依頼文を生成」「キャンセル」（`holder::close`）。
- `ExchangeReplacementConfirm`（L1143、V-30）: 置換確認のinline block。
  ja copy「破棄して作成」/「既存を維持」（承認/辞退。warning文言は「無効化される」語彙）。
- `ExchangeDisclosure`（L1175、V-32）: title＋tier別開示文言＋hint＋
  **package全文の常時提示**（`heightIn(max = 240.dp)`＋verticalScroll）＋copy/share/fileボタン
  ＋cancel/close（`cancelable`なら`exchange_cancel`=「キャンセル」→`closeDisclosure()`、
  そうでなければ`exchange_close`=「閉じる」）。
- `ExchangeImportField`（V-33）/`ExchangeImportSuccess`（L1500付近）: spec 332/328の現行UI。
  本Issueでは変更しない（#373対象）。
- `ExchangeImportSuccessBackHandler`（L1405）: import成功状態専用のBack handler
  （`BackHandler(enabled = importSuccessState != null)`）。lazy item外のalways-composed配置
  （spec 328 D-2）。
- **exchange側strategy gateは存在しない**: #368が`strategyWriteStartBlockedFor` /
  `strategyRestartSuppressedFor` / `strategyArbiterBusy` /
  `IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`を削除済み（#368 spec「exchange逆参照の除去」）。
  初版plan記載のstrategy相互排他truth tableは現行mainにない。

### 現行のhost（`ManualOrganizationPreferences.kt`、1793行）

`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`:

- `ExchangeFlowStateHolder` の構築（L102-110付近、`remember`、controller factory遅延）。
  `strategyArbiterBusy` 接続は#368で廃止済み。
- **Back処理（#369適用後、L215-288付近）**: 画面所有の`OnBackPressedCallback`
  （`DisposableEffect`で登録。`onSystemBack()`がD-13の確認gateを実装:
  Selecting（選択あり）/ Preview / Applyingは`ManualOrganizationDiscardConfirmDialog`
  経由の破棄確認1回、それ以外は直通の`interruptAndNavigate()`）と、その後にcomposedされる
  `ExchangeImportSuccessBackHandler`（L288。有効時は先にBackを取る）。#369のコメントは
  「pre-send cancel→破棄 rename belongs to #372」と明示している。exchange flow
  （T-15/T-16）表示中のBackはこのいずれにも捕らわれず、画面レベルのdismiss/navigate
  （`coordinator.dismiss()` → navigation）へ流れる。**本Issueが固定する対象**。
- Idle/Cancelled分岐（= #369適用後のtransitional T-07前置き面。L237-290付近）: checking行＋
  durable status行群（spec 271）＋「そのまま整理」CTA相当のidle start row
  （`manual_organization_start`系。`importAttemptActive`中はdisabled＋
  `exchange_start_frozen_import` subtitle。spec 328のfreeze affordance）。
  **「AIに相談」選択肢rowはまだ存在しない**（spec 369 RD-1が#372に委ねる）。
- Selecting分岐（T-08）: `missingAppSelectionItems`＋SCOPE_MISMATCH行＋
  `exchangeFlowItems`（run-in、L460付近）。選択面のexchange中凍結は現行契約。
- idle `exchangeFlowItems` のhost（L893-910付近、list最下部。「Issue #205: the external agent
  exchange surface closes the list」コメント付き）。
- `ManualOrganizationDiscardConfirmDialog`（L914/932付近、`pendingInterrupt`状態束）:
  #369のD-13共有破棄確認dialog。focus移動・confirm/dismissの明示role付き
  （organization-run-ux §6）。**本Issueのexchange破棄確認で再利用する**。

### 現行のcontroller / gate / session store

- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`
  （198行）: `activeSession()`（L70。`store.active(clock())`）、
  `generationGate()`（`ExchangeGenerationGate.evaluate`）、`generate(tier)` の順序契約
  （gate → build → save → compose → disclose。encode失敗時のghost session除去あり）、
  `cancelDisclosure(session)`（当該sessionのみ`invalidate`）。
- `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeGenerationGate.kt`
  （40行）: active sessionなし→Proceed / 確認未了→RequiresConfirmation / 承認→Proceed /
  辞退→Aborted。**本Issueでは不変**。
- `lawnchair/src/app/lawnchair/organizer/integration/AndroidExportSessionStore.kt`
  `active()`（L107-109）: `readSession()?.takeIf { !it.isExpired(nowEpochMs) }` —
  **失効sessionを不在として返す**。T-15再読取（進入・ON_RESUME）がこのseam経由であれば、
  期限到達後の表示消失と確認要否の更新は既存の実効gateと同じ判定源になる。
- `ContextExportModels.kt` L411-461: `ExportSession` は `expiresAtEpochMs`（T-15残時間の導出源）
  と `itemRefs`（T-16対象項目数の導出源。exported ref全体を保持）を持つ。新fieldは不要である。
- `ContextExportBuilder.kt`: tier分岐は `LOCAL_FULL` と `EXTERNAL_WITH_LABELS` を同一扱い
  （監査D-2）。label付きtierの自由文はアプリ名・フォルダ名に加え**ユーザー定義カテゴリの
  displayName**（spec 337 v4、`FreeTextClass.USER_CATEGORY_NAME`）を含む。
  **本Issueでは触れない**（契約値維持、D-14）。

### 現行のstrings / test

- `lawnchair/res/values-ja/strings.xml` L399-439付近（en `values/strings.xml` 同name集合）:
  `exchange_entry_*`（entry row、撤去対象。`exchange_entry_subtitle`はT-07の短い説明へ継承）、
  `exchange_capability_*`（4要素。維持・移設）、`exchange_privacy_*`（tier語彙。D-14語彙へ
  改訂。`exchange_privacy_labels_warning`はユーザー定義カテゴリ名を欠くため改訂対象）、
  `exchange_replacement_*`（「破棄して作成」等。title/notice/warningは「交換」語彙のため
  依頼語彙へ改訂）、`exchange_disclosure_*`（送信前確認。要約への再構成対象）。
  `exchange_cancel`=「キャンセル」はT-15の中止（zero-write）で継続利用、
  `exchange_close`=「閉じる」。
- unit: `tests/unit/app/lawnchair/organizer/ui/exchange/`
  `ExchangeFlowStateHolderTest.kt`（generation/import/cancel/attempt anchorの広範な契約。
  #368でstrategy系testは除去済み）、`ExchangeDisclosureStateTest.kt`、
  `ExchangeCapabilityCopyTest.kt`。
- instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/exchange/`
  `ExchangeImportSurfaceInstrumentationTest.kt`、`ExchangeImportSuccessInstrumentationTest.kt`、
  `tests/.../ui/ManualOrganizationPreferencesInstrumentationTest.kt`
  （T-07面。#369で拡張済み）。

## Design

### Modules and interfaces

- **`ExchangeFlowUi.kt`（ui/exchange。主変更先）**:
  - `ExchangeScreen.SelectingPrivacy` をT-15面として再構成する。pre-display用に
    `openFlow()`が取得したactive sessionの**最小projection**（存在 +
    `expiresAtEpochMs`）を保持する（現行はBooleanのみ。`ExportSession`自体はUIへ渡さず、
    表示に必要なfieldのみのprojectionに限定する）。
  - **新規holder関数 `refreshActiveRequest()`**: `controller.activeSession()` を再読取し、
    現在のscreenが`SelectingPrivacy`のとき、存在・`expiresAtEpochMs`・
    `replacementConfirmationRequired`を再読取値で更新する。`openFlow()`はこの更新処理と
    同一の判定で初期状態を作る（判定源の単一化）。`active()` が失効sessionを不在として
    返すため、TTL跨ぎ後の最初の呼び出しで事前表示が消え、確認要否も下がる。
    さらにactive sessionを観測した読取では、**失効時刻に1回の再読取をholder scopeで
    scheduleする**（`delay(expiresAtEpochMs - clock())`相当の1発job。連続tickはしない）。
    schedule再読取はlifecycle遷移に依存せずfireし、T-15を表示したままTTLを跨いでも
    表示を失効状態へ一致させる。unit testはfake clock＋仮想時間dispatcherで直接advanceし
    検証する（Phase1 review 2回目指摘2）。
  - **新規純関数（Back応答の写像）**: `exchangeBackAction(screen): ExchangeBackAction`
    （`Close`〔zero-write close〕/ `RequestDiscard` / `Blocked` / `None` の4値）。
    `SelectingPrivacy`/`ReplacementConfirm`→Close、`Disclosing`で`cancelable`→RequestDiscard、
    `Disclosing`で`sent`→Close、`Generating`および`Disclosing`でin-flight/`cancelling`→
    **Blocked（handlerがBackを取り込み画面離脱させない。operationのsettle後は通常契約へ戻る）**、
    それ以外（`Importing`・`ImportOutcomeScreen`・`ImportSuccess`・`Closed`）→None。
    Blockedは「既定経路への委譲」ではなく**画面離脱の遮断**である（委譲するとhostの
    `rememberCoroutineScope` cancelでgeneration/file transportが中断し、specの継続契約と
    矛盾する〔Phase1 review 2回目指摘1〕）。unit testから全状態を表駆動で検証できる。
  - **新規 `ExchangeFlowBackHandler(holder, onDiscardRequest)`**: `ExchangeFlowStateHolder`
    を観測し、上記写像のとおり`BackHandler(enabled = action != None)`でBackを取る
    （Closeなら`holder.close()`、RequestDiscardなら`onDiscardRequest()`、Blockedなら
    無操作でconsume）。
    **host面に常時compositionする**（lazy item内に置かない。spec 328 D-2の原則）。
  - `ExchangePrivacySelection` → T-15: 見出し、active依頼事前表示（存在＋残時間。
    format resource）、D-09期待明示行、tier 2択（D-14語彙＋v4整合の警告）、依頼を作成CTA
    （置換確認経由）、回答を取り込む導線（`openImport`）。cancelは「キャンセル」
    （確認不要、`close()`）。
  - `ExchangeDisclosure` → T-16: 要約主面（種別=既存tier別開示文言、対象項目数=session
    `itemRefs.size`を「対象項目数」語彙で、上限=spec 204 V1 content limitsの告知文言）＋
    全文の折りたたみ/展開（展開stateはTalkBackへ伝える）＋D-09期待明示行＋transport 3経路
    （既存）＋**「破棄」（pre-send cancelable時。押下で`onDiscardRequest` → 確認dialog経由で
    `closeDisclosure()`）**/閉じる（送信後。確認なし）。
  - `ExchangeEntryRow`（idle）を削除し、`exchangeFlowItems` の`Closed`分岐は
    scoped（run-in）のときのみentry rowを描画する。idleの`Closed`は何も描画しない。
    `ExchangeCapabilityNotes`はT-15から参照する形で維持する（run-in entryも継続利用）。
    `exchangeFlowItems` の引数に`onDiscardRequest: () -> Unit`を追加し、T-16の
    「破棄」ボタンとhostのBack handlerを同じ確認dialogへ収斂させる。
  - 要約導出は純関数に切り出し、unit testから直接検証可能にする
    （例: `exchangeDisclosureSummary(state)`。`itemRefs.size`・tier・上限定数の射影）。
  - **holderの状態機械は変更しない**: `closeDisclosure` のMain上`cancelling`確定 →
    `invalidate` の構造gate、`settleTransport` のdisclosure束縛、attempt anchorは
    現行契約のまま（破棄確認dialogはUI層のaffordance。confirm応答後に既存
    `closeDisclosure()` を呼ぶのみ）。Back応答写像もholder状態の読取のみで書換えない。
- **`ManualOrganizationPreferences.kt`（host。T-07面への統合とBack配線）**:
  - Idle/Cancelled分岐（transitional T-07前置き面）に「AIに相談」方法選択rowを追加する
    （短い説明＝既存`exchange_entry_subtitle`の継承または改訂。spec Contract notes 1）。
    onClickは`exchangeHolder.openFlow()`のみであり、`coordinator.start(...)` を発行しない。
    idle start row freeze（`importAttemptActive`）の対象にはしない（現行entry rowと同一の
    扱い。spec Dependencies参照）。
  - `ExchangeFlowBackHandler` を`ExchangeImportSuccessBackHandler`の**直前**にcomposedする
    （優先順: import成功状態 → exchange flow → 画面レベルD-13 gate/dismiss。
    OnBackPressedDispatcherは後にcomposedされた有効callbackが先に受ける）。
  - exchange破棄確認用の`pendingExchangeDiscard`状態（Boolean）をhostに置き、
    `ManualOrganizationDiscardConfirmDialog`をexchange用stringsで再利用する
    （confirm → `exchangeHolder.closeDisclosure()`、dismiss → 状態解除。T-16維持）。
    focus移動とroleは既存dialogの実装を流用する（organization-run-ux §6）。
  - **T-15再読取の配線**: hostで`LifecycleEventEffect(Lifecycle.Event.ON_RESUME)`
    （lifecycle-runtime-compose 2.10.0）により、`exchangeHolder.screen` が
    `SelectingPrivacy`のとき`refreshActiveRequest()`を呼ぶ。lazy item内にeffectを
    置かない（viewport離脱で停止するため）。
  - idle `exchangeFlowItems` のhost（現L893-910）から`Closed`時のentry blockが消える
    （UI側の変更で自然に消える。hostの呼び出し形状は変えない）。
    flow open中の表示位置（方法選択の直下/list末尾）はspec 123収束の範囲で実装PRが確定する。
- **strings（`lawnchair/res/values/` + `values-ja/`）**:
  - 新規（T-15見出し・事前表示・残時間format resource・D-09期待明示・要約
    〔種別は既存開示文言を再利用・対象項目数・上限告知〕・全文展開toggle・
    破棄ラベル〔T-16〕・破棄確認dialog〔title/message/confirm/dismiss〕・
    取り込み導線label〔`exchange_entry_import`の転用可〕）。
  - 改訂（tier語彙のD-14化「情報を減らして送る（既定）/ラベル付きで送る」、
    `exchange_privacy_labels_warning`のv4自由文集合整合〔アプリ名・フォルダ名・
    ユーザー定義カテゴリの名前を列挙〕、`exchange_replacement_*`の依頼語彙揃え、
    `exchange_entry_subtitle`の短い説明としての継承確認）。
  - `exchange_cancel`はT-15のzero-write中止として継続利用し、T-16の不可逆操作とは分離する。
  - format resource規約（spec 123 AC-4/AC-5）。未使用化stringの削除はreference grepで
    確定する（`exchange_entry_title`/`exchange_entry_open`等が候補）。
- **spec改訂（同じPR）**: spec 205 / 327 / 204（spec.mdのScope節「spec改訂」のとおり。
  変更箇所は「Spec amendments」参照）。

### Data flow

T-07「AIに相談」→ `openFlow()`（active session読取。存在ならT-15に事前表示projectionを
添付）→ T-15（tier選択＋D-09＋[active依頼があるとき置換確認]。
ON_RESUMEで`refreshActiveRequest()`）→ `requestGeneration` / `confirmReplacementAndGenerate`
→ `generate(tier)`（既存順序契約: gate → build → save → compose → disclose）→
T-16（要約主面＋全文展開＋D-09＋transport＋破棄/閉じる）→
`startTransport`（同一immutable値の送出）/「破棄」（ボタンまたはBack → 確認dialog →
`closeDisclosure` で当該sessionのみ失効）/ `close()`（送信後・T-15中止。
zero-write）。system Back → `exchangeBackAction` 写像（Close/RequestDiscard/Blocked/None。
Blockedはbusy state〔Generating・in-flight・cancelling〕の画面離脱遮断で、operationを
scope cancelから保護する）。
取り込み導線: T-15「回答を取り込む」→ `openImport()` → 既存T-17入力面（現行のまま）。
表示に必要な追加dataはactive sessionの`expiresAtEpochMs` と生成済みsessionの
`itemRefs`（既存fieldのみ）であり、新規の読取seam・永続化・diagnostics eventはない。

### Alternatives rejected

- **T-07に「AIに相談」の4要素本体を常時展開する案**: 方法選択面がAI説明で支配され、
  「そのまま整理」の最短性（TO-BE B′採用理由）を損なう。T-15へ本体を置けば依頼作成を
  実行する前に必ず通るため、informed choiceの実効性は保たれる（spec Contract notes 1）。
- **idle entry rowを「AIに相談」のsub-rowとして残す案**: D-04の「独立サブシステムとして
  見せない」と矛盾し、F-07が残る。撤去がIssue本文のscopeである。
- **holderに破棄確認状態を追加する案**: `cancelling` まで含む既存の構造gateは
  `ExchangeDisclosureStateTest`/`ExchangeFlowStateHolderTest`で固定済みであり、
  UI層のaffordance（spec 328の「UI disabledはaffordanceにすぎない」原則と同型）として
  dialogを実装する方が契約面の変更が最小である。Back handlerも同じUI層affordanceに
  収斂させる（Phase1 review指摘1）。
- **T-16の破棄確認dialog／Back handlerをlazy item内に置く案**: itemがviewportを離れると
  compositionから外れ、Backやdialogが不在になる（large fontで発生し得る）。
  spec 328 D-2 reviewで確立したalways-composed host配置の原則に従う。
- **busy stateのBackを既定経路へ委ねる案（r2で一旦採用し撤回）**: `Generating`/in-flight/
  `cancelling`中はexchange handlerを無効化してhostのdismiss/navigateへ委ねる設計だったが、
  holderのoperationはhostの`rememberCoroutineScope()`で実行されるため画面離脱＝scope cancel
  となり、「generation/file transportは継続してsettleする」契約と矛盾する（Phase1 review
  2回目指摘1）。Blocked（Back取り込み・画面離脱遮断）へ変更した。逆にoperationを
  composition非依存の所有scopeへ移す案は、holderの生成・cancel・import全経路の所有権変更を
  伴い、本Issueの「holder状態機械は変更しない」原則に反するため採らない。
- **残時間を時計に追随して常時更新する案（秒針tick）**: specが要求しない過剰な更新であり、
  再読取（進入・ON_RESUME・失効時刻schedule）+ 実効gateで表示の正しさは十分に保たれる。
  進入・ON_RESUMEの再読取のみ（r2案）は、foregroundのままTTLを跨いだ表示矛盾を解消
  できないため、失効時刻の1発scheduleを追加した（Phase1 review 2回目指摘2）。
- **T-16要約の件数をlive compositionから再計算する案**: 確認対象とtransport対象の同一性
  （AC-12）が同一immutable値の受渡しで担保されている現行設計を壊す。表示値も同一対象
  （session）から導出する。
- **T-16要約にcategory件数を追加する案**: `categoryRefs` から導出は可能だが、pre-v4 session
  （`categoryRefs` 空）で誤った「0件」を表示し得るうえ、categoryの有無・名前の要否は
  既存種別文言が既に説明する。対象項目数の語彙明示（指摘3の要件）で誤読は防げるため
  表示しない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeEntryRow`（idle）削除、`SelectingPrivacy`のT-15再構成（pre-display projection・事前表示・D-09・tier語彙・取り込み導線）、`refreshActiveRequest()`、`ExchangeDisclosure`のT-16再構成（要約主面・全文展開・D-09・「破棄」ラベル）、`exchangeBackAction`純関数、`ExchangeFlowBackHandler`、破棄確認dialog連携、要約導出の純関数、`exchangeFlowItems`のClosed分岐変更 | flow面の再構成が収束する唯一のUI module。既存composable/testTag/helper群を再利用 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | T-07前置き面への「AIに相談」方法選択row追加（`openFlow`のみ。start不発行）、`ExchangeFlowBackHandler`のhost composition（import成功handlerの直前）、`pendingExchangeDiscard`＋dialog再利用、ON_RESUME再読取の配線 | T-07面とentry統合・Back配線のhostはrun面のみ（#369適用後のIdle/Cancelled分岐と既存back配線の直上） |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 新規・改訂・削除（specのLocalization節。`exchange_privacy_labels_warning`のv4整合を含む） | spec 123契約（EN/ja双方・format resource） |
| `specs/205-external-agent-exchange/spec.md` | entry規定supersede、AC-3/AC-12表示形式、AC-13語彙、T-15事前表示・T-16要約・Back契約・pre-send破棄確認の追記、Change history | disposition §3.12（#372所有の分段改訂） |
| `specs/327-agent-exchange-interview-first/spec.md` | Decision 4配置前提・AC-4/AC-5配置の更新、Change history | disposition §2.3（#372所有） |
| `specs/204-ai-personalization-context-intent-contract/spec.md` | privacy tier節へのD-14文言追記、Change history | disposition §3.11（#372所有・文言のみ） |
| `tests/unit/.../exchange/ExchangeFlowStateHolderTest.kt` ほかunit/instrumentation | T-15事前表示・再読取・T-16要約・破棄確認・Back写像・entry統合の新規oracleと表示面移設のみの既存oracle更新 | spec Test oracle表 |

## Spec amendments（実装PRで実施する正本改訂の箇所）

- **spec 205**: (1) Behavior scenarios「export package生成と送信前確認」のGiven前提を
  T-07方法選択経由へ、「privacy mode選択」に2択固定（D-14語彙・`LOCAL_FULL`不出現）を追記、
  「process recreation後のrun再構築」末尾のV1 entry規定をpre-run request flow規定へ、
  Data and state の「exchange導線の提示はmanual run操作非active時に限定する (V1)」を
  T-07経由idle相談＋run-in維持へ改訂。(2) T-15事前表示（再読取規則含む）・D-09期待明示・
  pre-send cancelの破棄＋確認・T-15/T-16のsystem Back契約のscenario/AC追加。(3) AC-3/AC-12の
  表示形式（要約主面＋全文展開・対象項目数）への改訂とgate構造不変の明示、AC-13の破棄語彙化。
  (4) Change historyへ#372分（9th）を追記。
- **spec 327**: Scope「アプリ内capability説明」・Decision 4・AC-4/AC-5の配置前提を
  「idle entry」→「idle相談導線（T-07短い説明＋T-15本体）」へ。run-in entryへの適用は維持。
  Change history追記。instruction契約（AC-1〜AC-3、`Issue348AiFacingContractSyncTest`）は触れない。
- **spec 204**: 「privacy tier」節に「UI選択肢は2種（redacted / labels、TO-BE D-14語彙）。
  `LOCAL_FULL`は内部契約値として維持し外部workflow UIに出現させない（#206向けDefer）」を
  追記。tier matrix・schema・validatorは不変。Change history追記。

## Migration and recovery

- schema/rule migration: なし。persistent state・session store format・backup/restore
  （backup除外class）への接触なし。
- failure中のrollback: 表示変更のみであり、PR revertで旧entry row＋旧確認面へ戻る。
  revert時にsession/import互換の問題はない（session契約不変）。
- release rollback/downgrade: 残留物なし。旧版のentry rowと新版のsessionは同一契約で
  互換である（`ExportSessionStore` v2のまま）。
- 依頼の実効性は生成時gate・取り込み時検証が担保するため、表示変更の失敗（表示不整合）は
  zero-writeであり、取り込み時のtyped失敗（`SESSION_EXPIRED`/`EXPORT_MISMATCH`/
  `CONTEXT_STALE`）でfail-closedになる。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| EX-AC-01 | instrumentation: T-07「AIに相談」→T-15表示（coordinator `Idle`維持・`start`不発行）、取り込み導線到達、idle entry row不在の否定的観測（`exchange-entry-*` tag）、run-in entry存在回帰 | `connectedLawnWithQuickstepGithubDebugAndroidTest`（organizer instrumentation lane） |
| EX-AC-02 | unit: flow状態下でのauthoring lease取得成功 + holder/controllerがlease seamに接触しないことの構造確認。instrumentation: flow表示中の材料編集経路回帰 | JVM unit test + organizer instrumentation lane |
| EX-AC-03 | unit: 事前表示projection・再読取（`refreshActiveRequest`）による確認要否更新・lifecycle遷移なしのfake clock TTL超過で不在（失効時刻schedule再読取・仮想時間advance）+ 既存AC-13系test green。instrumentation: 事前表示の表示/非表示・破棄語彙確認dialog | unit + instrumentation lane |
| EX-AC-04 | unit: 要約導出純関数（対象項目数/種別/上限。語彙明示を含む）+ `ExchangeDisclosureStateTest`回帰。instrumentation: 要約主面・展開・D-09両面表示 | unit + instrumentation lane |
| EX-AC-05 | unit/instrumentation: 選択肢2個・`LOCAL_FULL`文字列UI不在（strings走査含む）・label付き警告のv4整合（EN/ja双方のstring内容）・契約値対応回帰 | unit + instrumentation + strings grep |
| EX-AC-06 | spec 205 AC-3/AC-12対応test green + transport 3経路回帰 | JVM unit test |
| EX-AC-07 | unit: `ExchangeCapabilityCopyTest`拡張（T-15での4要素）。instrumentation: run-in entry説明回帰 | unit + instrumentation lane |
| EX-AC-08 | unit: 破棄確認受付→当該sessionのみ`invalidate`・辞退時生存・`cancelling`後不受理（既存disclosure契約の継承）。instrumentation: 破棄ラベル・dialog・送信後「閉じる」確認なし | unit + instrumentation lane |
| EX-AC-09 | specs 205/327/204のdiff review + `Issue348AiFacingContractSyncTest`/`ExchangePackageComposerTest`無編集green | CI + PR review |
| EX-AC-10 | Compose semantics assertion（name/role/state・展開state・dialog role・traversal）+ focus restoration + 200% font scale + light/dark × ja/default screenshot。EN/ja name集合・placeholder一致の機械確認 + 削除string reference grep（0件） | instrumentation + 手動a11y evidence + grep |
| EX-AC-11 | unit: `exchangeBackAction`写像の全状態表駆動test（Close/RequestDiscard/Blocked/None）+ Back経由の破棄確認→`invalidate`（当該sessionのみ）・dismiss生存・送信済みclose生存 + blocking fake generation / `FileExchangeTransport` でbusy中のBack受付後もjobがcancelされずsession保存・transport settleが終端まで到達することの直接assert。instrumentation: T-15/T-16でのBack遷移・T-07復帰・Back起因dialog・busy中のBackで画面離脱しないことの観測 | unit + instrumentation lane |

含めるべき観点: unit/contract（holder・gate・要約導出・Back写像の決定性）、UI（T-07/T-15/T-16の
遷移とa11y）、回帰（AC-3/AC-12/AC-13・attempt anchor・freeze・run-in契約・instruction契約の
無編集green）、failure injection（生成失敗3種の既存status回帰、TTL失効後の操作）。

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、CI `final-status` green。
`risk: layout-data`/`risk: migration` は付けない（表示・導線のみ）。

## Documentation updates

- [ ] spec status/history（specs 205 / 327 / 204の改訂と、本specのstatus進行）
- [ ] CONTEXT.md（「依頼」「中止語彙規約」等は#365が収録済み。本PRでは触れない）
- [ ] DESIGN.md（gate 13の記述変更は不要。module構造・seamの変更なし）
- [ ] ADR（該当なし。IA・表示変更でありADRの3条件を満たさない。disposition §5も「追加・改訂なし」）
- [ ] AGENTS.md（該当なし）

## Execution checklist

- [ ] Current behavior reproduced（現行main `13c95eafe6` 上: Idle/Cancelled面のidle entry
  block、T-15/T-16の現行構成、Backがflowを素通りしてdismiss/navigateへ流れる現状を観測）
- [ ] Tests fail for the missing behavior（T-15事前表示・再読取・T-16要約・破棄確認・
  Back写像・entry統合の新規oracleを先に追加）
- [ ] Minimal implementation completed（host（row・Back配線）→ holder（projection・
  refresh）→ T-15 → T-16 → strings → spec改訂の順）
- [ ] Migration/recovery verified（該当なし。revert可能性のみ確認）
- [ ] Full relevant verification completed（共通gate + a11y evidence）
- [ ] PR evidence and remaining risks recorded（旧表示oracleのobsolete理由: F-07/E-3/E-5/監査D-2の解消、Contract notesの解釈確定状況、Phase1 review指摘の対応状況）

## Dependencies / blockers / risk

- **依存は全て解消済み（2026-09-21時点）**: #365（PR #379）/ #368（PR #384）/ #369
  （PR #387）/ #370（PR #389）はすべてmerge済み。実装着手の外部blockerはない。
  本planはmain `13c95eafe6` をbaselineとする。
- **#368適用後の形状（確認済み）**: exchange側strategy gate
  （`strategyWriteStartBlockedFor`系・`strategyArbiterBusy`・busy status）は削除済みで、
  初版planにあった接続・再確認事項は解消した（Current evidence参照）。
- **#369適用後のT-07形状（確認済み）**: 前置き面はIdle/Cancelled分岐の「そのまま整理」CTA
  （idle start row）であり、「AIに相談」rowはこれに並べて新設する。back配線は画面所有
  callback＋import成功handlerの2段であり、本planの`ExchangeFlowBackHandler`はその間に
  入れる。
- **#373/#374との整合（後続）**: T-17入力面・失敗表示（#373）、status card依頼表示と
  置換確認copyへの取り込み済み提案破棄の追記（#374）は本PRでは行わない。
  T-15事前表示は#374のstatus card実装までの暫定的な再発見面である（spec明記済み）。
- **risk**: 低。表示・導線の再構成のみであり、persistent state・DB書込み経路・同意gate構造・
  session契約に触れない。主要リスクは（a）既存exchange契約testの表示面移設時の
  意図しない緩和、（b）破棄確認dialog追加によるcancel経路の重複実行（`cancelling` gateで
  構造的に防止。Backとボタンは同一dialog状態に収斂させる）、（c）Back配線の優先順誤り
  （import成功・画面レベルgateとの順序。compose順で構造的に固定し、instrumentationで観測）。
  いずれも回帰test（EX-AC-03/06/08/11）とhost面の変更範囲限定で対処する。

## Explicitly unverified areas

- 実機clipboard/Share Sheet挙動・200% font scaleでのT-16要約面のreflowはa11y/device
  evidence（EX-AC-10）で確定する。
- `LifecycleEventEffect` の利用（lifecycle-runtime-compose 2.10.0）がmoduleの依存宣言上
  直接参照可能かは実装PRの初手で確認し、不可能なら`DisposableEffect` + `LifecycleObserver`
  の等価実装に置き換える（契約は「ON_RESUMEで再読取」でありAPI指定はしない）。
- 失効時刻schedule再読取のunit oracleは、holder testの既存のfake clock／dispatcher仕掛けで
  仮想時間advanceが直接可能かを実装初手に確認する（不可能な場合はclockを引数に取る
  判定関数の分離で同等のoracleを構成する。契約は「失効時刻の再読取で表示が失効状態へ
  一致」であり、実現APIは指定しない）。
- `ExportSession` の残時間表示に必要な最小projectionの最終形（`expiresAtEpochMs`の
  直接保持 vs 表示用projection data class）は実装PRで確定する（いずれも既存fieldのみから
  導出可能）。
- 最終copy文言（T-15見出し・事前表示・期待明示・要約・破棄確認dialog）と、T-15事前表示・
  置換確認・期待明示の面上配置（縦順）は200% reflowとTalkBack読み順を満たす範囲で
  実装PRのreviewで確定する（spec 123収束対象）。

## Change history

- 2026-09-19: 初版作成（baseline `a2b6aba318`。#369/#368 draft時点の形状に基づく推定を
  含む）。
- 2026-09-21: Re-entry revision（Phase1 review指摘4件対応＋baseline再固定）。
  baselineをmain `13c95eafe6` へ更新し、Current evidenceを現行mainの実装形状
  （#369適用後のback配線・transitional T-07、#368によるexchange側strategy gate削除、
  #370適用後のhost）で書き直した。DesignへBack契約の実装形状（`exchangeBackAction`純関数・
  `ExchangeFlowBackHandler`・破棄確認dialog収斂）とT-15再読取（`refreshActiveRequest`・
  ON_RESUME配線）を追加。VerificationへEX-AC-11を追加し、EX-AC-03/04/05のevidenceを
  spec改訂へ追従。依存の全解消と、未検証領域の縮小を反映。
- 2026-09-21: Round 2 revision（[Phase1 review 2回目](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752821376)
  指摘2件対応）。Current evidenceへ「holderのoperationはhostの`rememberCoroutineScope()`
  scopeで実行され、composition破棄でcancelされる」事実を追記。Designの
  `exchangeBackAction`へ`Blocked`（busy stateの画面離脱遮断）を追加し、`refreshActiveRequest`
  へ失効時刻の1発schedule再読取を追加。VerificationのEX-AC-03/11 oracleを更新
  （blocking fake generation / `FileExchangeTransport`直接assert、仮想時間TTL advance）。
  Alternatives rejectedへr2案の撤回理由を記録。
