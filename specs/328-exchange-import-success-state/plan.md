# Implementation Plan: External Agent Exchange Import成功後の中間状態と次操作の明示

> Issue: #328
> Spec: [spec.md](./spec.md)
> Status: draft — spec D-2/D-3 (およびsummary内訳) はowner decision確定済み ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396))。spec承認を待ってimplementation-ready。

## Current evidence

origin/main (`a9ec3c2cf9ce6f666e5f9f12eecf82f541f12916`、2026-09-18確認。本branchは同commitをmerge済み) のコード事実。#329 (Import Normalizer)・#330 (partial authoring v3)・#332 (clipboard/file-first import UI)・#336 (user-defined categories)・#348 (AI-facing contract sync)・#327 (interview-first化, implemented) 実装merge後の状態。

### Import成功時の現行挙動

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  - `ExchangeFlowStateHolder.import(replyText)` (L475–523): IO上で `controller.importReply(replyText)` を実行し、`ExchangeImportResult.Validated` のとき **即座に** run接続へ進む:
    - runが `State.Selecting` (run内entry): `run.attachIntent(validated)`。`Attached` → `status = IMPORT_ACCEPTED` + `screen = Closed` (画面を閉じて1行status)。`NotAttachable` → `status = RUN_BUSY` + `Importing(replyText)` へ復帰。
    - それ以外 (idle entry): `run.start(intent = validated)`。`Started` → `IMPORT_ACCEPTED` + `Closed`。`Busy` → `RUN_BUSY` + `Importing` 復帰。
    - 失敗 (`Pipeline(Failure)` / `InputNotReady`) のみ `ExchangeScreen.ImportOutcomeScreen(outcome, rawText)` (失敗専用の「取り込み結果」画面、#332により認識framing/version/entry数のparse-first表示 + 折りたたみraw + 再取り込み付き)。
  - #332の入力経路: `importFromClipboard` / `onFileRead` → 共通receipt helper `receiveAndImport(text)` (L436–443) → envelope gate → `ExchangeScreen.Importing(text)` → `import(text)`。validation通過後の挙動は上記のまま (#332 specが境界として固定)。
  - #327 (implemented) のlanded変更面: entry row直下の `ExchangeCapabilityNotes` (L698/780呼出、定義L729–756、capability例 `exchangeCapabilityExampleResourceIds()` L758) と関連strings/ (`ExchangeCapabilityCopyTest` / `Issue327InterviewFirstContractTest`)。validation通過後のflow (`import()` の即時attach/start) は変更していない。本planの成功状態は `ExchangeScreen.Importing` 以降に追加されるため差替競合なし。
  - `exchange_import_accepted` 文言 (ja: 「整理案を検証しました。プレビューを生成します…」) は実際の遷移とずれる (idle + 検出Readyなら次は選択surface、検出UnavailableならPreview直行でstatus行が非hostingになる)。
  - `ExchangeScreen` states: `Closed` / `SelectingPrivacy` / `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / `ImportOutcomeScreen` (L74–103)。成功用stateは存在しない。
  - **既存のsingle-flight構造パターン** (本planのCTAが踏襲する): `beginTransport()` (L355–362) — 呼出thread上で現在のdisclosure stateを検査し `transportAllowed` なら同期的にin-flightへflipして開始を許可 (拒否時は実行されない)。`settleBelongsTo(disclosure)` (L370) — settleは開始時と同じdisclosure (exportId anchor) が表示中のときのみ適用され、閉じた後/新世代の遅延結果はdropされる。**anchorに使えるのはexportIdがdisclosure generationごとに一意だから** であり、content digestをanchorに流用することはできない (後述)。
  - `exchangeFlowItems` (L558/573〜) は `LazyListScope` extensionであり、全exchange画面は **lazy list item** としてrenderされる (viewport外のitemはcompositionから外れ得る)。#327のcapability説明も同一lazy item構造。
  - **validation中の競合窓 (attempt anchorが必要な理由)**: `import(replyText)` は呼出ごとに独立したIO coroutineを起動し、attempt/coroutine単位のguardは存在しない。validation中でも `close()` (L207–209、`ExchangeImportField` のキャンセル `onCancel = holder::close` L616)、`openImport()` (L202–204)、`receiveAndImport` (L436–443) / `onImportTextChange` (L465–473) の `screen = Importing(...)` 置換は自由に実行できる。つまりcancel後の遅延 `Validated`、A→Bの順不同settleが現行構造で成立し得る。
  - **`ManualOrganizationRun.start()` の例外挙動**: L423–427でdetection/planning中の `Throwable` をcatchし `abort(operation)` した後に **再throwする**。seam呼出側がcatchしない場合、UIへのfailure settleは発生しない (coroutineが例外のまま終端)。再throwは **任意の `Throwable`** で `CancellationException` も含まれ得る — 「CE = scope破棄」とは限らないため、呼出側はcoroutine自体のcancel (`currentCoroutineContext().ensureActive()` 相当) と区別する必要がある。
  - **idle CTA処理中のrun活性化とrunId露出**: `start()` は `beginOperation()` 直後にrunを活性化し (`State.Capturing` → `CandidateDetection`/`Selecting`)、`Started(runId)` (L223) を返す — idle CTAのIO seam実行中 (settle前) でもrunは **active** であり、`onStrategySelected` のcommit時run判定が真になり得る。run側の各stateは `runId` を露出する (`State.Selecting.runId` L251、composed phase L463) ため、settle時のrunId一致確認 (defense-in-depth) が可能。
  - **`attachIntent` (L490–499) の判定範囲**: `state as? State.Selecting` とactive operationの検査のみで **どのrunがSelectingかは問わない** — 元runのdismiss→restart差し替え後の別runがそのまま受理し得る。`State.Selecting` は `runId` (L250) を持つため、owning runId照合によるguardが可能。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `start(trigger, intent)` (L388–429): fresh run接続seam (#205)。**同期関数** (capture/composition/planningまで同時実行、呼出側がIO wrapperを担う — audit P2-1)。新RunId → `CandidateDetection` → `State.Selecting(runId, candidates, intentScopeCount)` または `runComposedPhase` 直行 (検出Unavailable)。`Busy` は `OrganizationOperationLease` gate。**返る前に `beginOperation()` (L1102) でrun stateを先に進める** — つまりseamは「結果を計算してから状態を変える」のではなく、settle前にrun stateを変更する。
  - `attachIntent(intent)` (L490–499、#331): `State.Selecting` 保持中に一度だけbind。`Attached` / `NotAttachable`。synchronized block内でactive runにintentをbindし `intentScopeCount` を更新 (同様にsettle前にrun stateを変更)。
  - `State.Selecting` (L250–255) は `intentScopeCount` (選択surfaceの件数案内) と `scopeRejection` (`SCOPE_MISMATCH` 表示) を持つ。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `exchangeHolder` (L110–116): 画面compositionで無条件に `remember` される (常に生存)。
  - `ManualOrganizationBackHandler(coordinator)` (L186呼出、定義L906–941): **画面levelで常時composition** される `OnBackPressedCallback`。Back = `coordinator.dismiss()`。run active時はrun cancel (bound intent喪失)、idle時は `NoActiveOperation` → navigate away (exchange sub-flow状態は画面破棄で消失)。exchange sub-flow独自のBack扱いは存在しない。
  - `onStrategySelected` (L235–254): store書込が `Committed` かつrun active (`Idle`/`Cancelled` 以外) のとき `coordinator.dismiss()` → `coordinator.start(trigger)` (**実行中runを取り消して新runで差し替える**)。radio再選択 (同一strategy) はno-op。**書込は `execute { store.select(id) }` の非同期path** であり、開始とcommitの間に時間差がある — CTA開始後にcommitした書込もcommit時点のrun判定でdismiss/restartを発火し得る (idle CTA処理中のrun差し替え競合。Designの相互排他gateで閉じる)。
  - idle branchの「整理を開始」row (L283–292、`manual_organization_start`) はexchange sub-flow表示中もtappable (競合affordance)。
  - run内entry (L313–365): `exchangeBusy = exchangeHolder.screen !is Closed` (L324) で **選択編集のみ** freeze (`editsEnabled = !exchangeBusy`、L351)。strategy pickerは凍結対象外。
  - `strategyPickerItems` (L724–728呼出、定義L851): run stateの `when` の **外側** に常時renderされ、全stateで操作可能。
  - idle exchange hosting (L734–747): `idleLike` (Idle/Cancelled) のときのみ。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`
  - `importReply(replyText): ExchangeImportOutcome` — `Pipeline(ExchangeImportResult)` / `InputNotReady(recognized)`。`ExchangeImportResult.Validated(validated: ValidatedPersonalizedIntent)` が成功値。
- **summary導出の正本データ (#330 v3)**:
  - `lawnchair/src/app/lawnchair/organizer/personalization/IntentCompletion.kt`: `RefDecision` = `Authored(intent)` / `UnresolvedAuthored` (明示unresolved + bare entry正規化) / `UnresolvedByOmission` (未言及)。`CompletedPersonalIntent` (`decisions: Map<String, RefDecision>`、全export refがちょうど1回) は `authoredItemCount` / `authoredUnresolvedCount` / `omittedCount` を派生propertyとして持つ。`IntentCompletion.complete` はbare entry (全semantic fieldがnull、L80–85) を `UnresolvedAuthored` へ正規化する (D-6)。
  - `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt` (L115–127, L144–147): validator成功path内でcompletionが実行され、identityはcompleted表現に対して計算される (`identity = IntentIdentityCalculator.identity(completed)`、L116–125。D-5)。**`validated.identity` はcanonical content digestであり、同一semantic内容を再importすれば同一値になる** — 成功状態の画面instance/attemptを区別するanchorには使えない (settle anchorはprocess-localなimport attempt tokenで代替、Design参照)。`ValidatedPersonalizedIntent.completed` はvalidation後に再導出可能 (deterministic)。**v3のcoverage規則は `itemIntents` refsと `unresolvedRefs` のdisjoint性のみ** (L59–70) で、v2の「全ref coverage」不変条件は廃止 — 未言及refはcompletionがcanonical unresolvedへ落とす。authored文書 (`intent.itemIntents` / `intent.unresolvedRefs`) はdiagnostics用のみ。
  - planner消費 (`lawnchair/src/app/lawnchair/organizer/personalization/IntentPlannerAdapter.kt` L22–27): `validated.completed.decisions` のうち `RefDecision.Authored` のみが `ItemPreference` へ射影される。判断なし3表現はplanner効果ゼロで同一。**plannerはさらに `globalMinimizeMovement = validated.intent.globalPreference?.minimizeMovement ?: false` (L42) を消費する** (`GlobalPreference.minimizeMovement: Boolean?`、IntentModels L55) — item希望を持たないglobal-only intentでもplanner効果は存在し、summaryがitem計数だけだと「取り込み結果が空」に見える不一致が生じる (Designの全体方針行で解消)。
  - 旧plan記載の「coverage不変条件により export items = `itemIntents` ∪ `unresolvedRefs`」は **v3では成立しない** (omissionが存在し得る)。`itemIntents.size` / `unresolvedRefs.size` を計数根拠にすると、bare entryが希望に誤計上され、omissionが判断なし件数から漏れる。
- 失敗表示: `exchangeContractFailureText` (13 contract種) + envelope 4種 + #329 normalization 2種 = **typed 19種** + 入力source status (`CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` / `FILE_READ_FAILED` / `INPUT_OVERSIZE`) + `InputNotReady`。
- strings: `lawnchair/res/values/strings.xml` + `values-ja/strings.xml`。成功系は `exchange_import_accepted` / `exchange_run_busy` のみ (`exchange_run_busy` = 「整理が実行中のため取り込めません。終了後に再度取り込んでください。」— CTA時拒否の案内としては「取り込み」が成功済みである点とずれるため、新規stringの可能性あり)。
- tests: `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` (holderの実経路test、FakeStore/run注入、transport single-flight (`aSecondWriteFileWhileOneIsInFlightIsRefused`)・遅延settle drop (`lateResultFromAnOldWriteCannotTouchANewerDisclosure`) の既存pattern、#332 receipt経路test群)、`tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeCapabilityCopyTest.kt` (#327)、`tests/unit/app/lawnchair/organizer/personalization/exchange/Issue327InterviewFirstContractTest.kt` (#327)、`tests/unit/app/lawnchair/organizer/integration/exchange/ExchangeFlowControllerTest.kt`、`tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` (`attachIntent`/`start(intent)`/scope gate)。

推測ではなく上記file/行の確認済み事実 (merge後の本branch working tree、origin/main `a9ec3c2cf9` と同一内容)。build/testは未実行 (本PRはdocs-only)。

## Design

### Modules and interfaces

- **`organizer/personalization/exchange/` (pure) — 取り込みsummary導出**: `ExchangeImportSummary` (data class: 件数のみ。`recognizedCount`、`noJudgmentCount`、`scopeCandidateCount`、**V1必須の内訳counts** `priorityCount` / `groupCount` / `placementCount` / `keepCount`、**全体方針flag** `minimizeMovement: Boolean`) と純粋関数 `exchangeImportSummary(validated: ValidatedPersonalizedIntent): ExchangeImportSummary`。入力は `validated.completed` (`CompletedPersonalIntent`)、`validated.session`、`validated.intent.globalPreference?.minimizeMovement` (planner-effective boolean 1値。plannerが消費する同一値) のみ:
  - `recognizedCount = completed.authoredItemCount` (`RefDecision.Authored` のみ。bare entryは希望に計上されない)。
  - `noJudgmentCount = completed.authoredUnresolvedCount + completed.omittedCount` (合算。provenanceはuser-visibleにしない — spec 330 D-5/D-6のsemantic同一性)。
  - 内訳は **V1必須** (owner確定) で、`decisions` の `Authored.intent` fieldのみから次のitem単位算式で導出: `priorityCount` = `importance != null`、`groupCount` = `desiredGroupRefs` 非空 **または** `groupSemantic != null`、`placementCount` = `pageAffinity != null` **または** `regionAffinity != null`、`keepCount` = `preserve != null` (`false` も1件)。1項目の複数dimension指定は複数行で数える (dimension毎の独立計数)。bare entryは全内訳で非計上。0件行はUIで表示しない。
  - `minimizeMovement = validated.intent.globalPreference?.minimizeMovement == true` (planner-effective)。`true` のときUIは全体方針行「全体方針: 現状の配置をなるべく維持する」を表示し、`false`/`null` では行を表示しない (authoring provenanceではなくplanner効果に寄せる)。global-only intent (item希望0件 + `minimizeMovement = true`) でも取り込み結果が空に見えない。表示値とplanner projection (`globalMinimizeMovement`) の一致をcontract testで固定。
  - label・ref・自由文を型に持たせない (非混入を構造で保証)。Android-free (purity guard範囲内)。
- **`organizer/ui/exchange/ExchangeFlowUi.kt` — 取り込み成功状態 + import attempt anchor**:
  - `ExchangeScreen.ImportSuccess(summary, validated, entryKind, continuing: Boolean = false, attemptToken: Long)` を追加 (`entryKind`: idle / run内)。holderはvalidated intentを **process-localで一時保持** (新規永続化なし)。
  - **import attempt token (単一のsettle anchor)**: holder内にprocess-localな **単調増加token counter** を持つ。`import()` / `receiveAndImport()` がimportを開始する時点 (validation起動前) でtokenを採番し、entry種別と、run内entryでは **owning runId** (`(run.state as? State.Selecting)?.runId`) をcaptureしたうえでIO coroutineへ渡す。validation settle (`Validated` / `Failure` / `InputNotReady`) は **captureしたtokenがcurrent** (そのattemptの `Importing` 画面、またはそこから生成された `ImportSuccess` / `ImportOutcomeScreen` が表示中) のときのみ適用し、それ以外の遅延settleはdropする — **cancel後の遅延 `Validated` による成功状態再出現、A→B順不同settleによる最新の上書き、owning runId不一致 (run差し替え後の別runへの接続) をいずれも構造で排除** する。成功状態のtokenはattempt tokenをそのまま継承するため、validation段階とCTA段階が同一anchorで閉じる。`validated.identity` はcontent digest (#330 D-5) でありanchorに使わない。token採番・照合は全てuiDispatcher上のscreen遷移と同じ場所 (Main-confined) に置く。
  - `ExchangeFlowStateHolder.import()` の `Validated` 分岐を変更: attach/startを即時実行せず、attempt token照合を通過した場合のみ `screen = ImportSuccess(...)` へ。run内entryではsettle時もowning runIdが `State.Selecting` を保持していることを確認する。`IMPORT_ACCEPTED` statusの即時表示は廃止 (文言の誤用解消)。
  - 新規操作 **`continueImport()` — single-flight**:
    1. 呼出thread (production: Main) 上で `screen` を検査: `ImportSuccess` かつ `!continuing` 以外は即座にreturn (二重押下・処理中の再入はseamを呼ばない)。**strategy書込がin-flight (`strategyWriteInFlight`、hostingが管理 — 下記) の場合も即座にreturnしtyped案内する** (書込commitとimport continuationの直列化。seam呼出なし、zero-write)。
    2. 同期的に `screen = ImportSuccess(..., continuing = true)` へflip (check-and-set。`beginTransport()` と同じ「状態経由の開始直列化」パターン)。CTA buttonは `continuing` でdisabled。idle/run内両entryで **strategy pickerもこの時点から無効化** される (`importContinuationActive` 連動 — 下記)。
    3. `scope.launch(Dispatchers.IO)` 内で既存seamを実行 (run内: `run.attachIntent(validated)` / idle: `run.start(trigger, intent)`。`start` は同期 heavy なのでIO wrapperは現行 `import()` と同一の監査対応)。**seam呼出の直前に追加のguard検査は行わない** — `continuing` 中は破棄・Backが不受理 (次項) のため、開始されたseamが途中で撤回される経路が存在しないことが構造で保証される。seam呼出は **try/catchで包む**: catchした例外に対し、まず **呼出coroutine自体のcancel判定** (`currentCoroutineContext().ensureActive()` 相当) を行い、cancel済みなら例外をそのまま再伝播させる (生存scopeのUI stateは触らない)。contextが生存している場合は **`CancellationException` を含む全ての例外** をsettle時点のattempt token照合を通してfailure settleへ変換する (例外前に `start` が `abort(operation)` を完了するためrun側はzero-writeに復帰、`attachIntent` はbind前に判定してrefuseするためbind残骸もない)。seam内部発CEで `continuing = true` に取り残される経路を構造で排除する。
    4. settleは `uiDispatcher` へ戻り、**開始時にflipした `ImportSuccess` stateのattempt token** に紐付く: settle時の `screen` が **同じattempt token** を持ち `continuing` のときのみ適用。token不一致 (閉じた/置き換わった/同一identityの回答で作り直された成功状態) の遅延settleはdrop (`settleBelongsTo` と同じ構造。exportIdがdisclosure generationごとに一意であるのに対し、identityはcontent digestで再importで再現されるため、tokenが必須)。
    5. settle結果の適用: `Started` / `Attached` → 成功settle (**defense-in-depth**: `Started.runId` と、settle時にrun stateが露出するrunId (`State.Selecting.runId` 等) が取得でき一致しない場合は競合でのrun差し替えとみなしfailure settleとして扱う) → `screen = Closed`。`Busy` / `NotAttachable` / runId不一致 / **生存context内のseam例外 (CE含む)** → `continuing = false` へ復帰 (再試行可) + typed status (成功状態維持、zero-write。seam例外用のtyped statusは新規string)。処理中のまま破棄・Backが永久に不能になる経路を残さない。
  - 新規操作 `discardImport()`: `screen` が `ImportSuccess` かつ `!continuing` のときのみ受理 (それ以外は何もせずreturn — **CTA処理中の破棄不受理**。`run.start` / `attachIntent` はsettle前にrun stateを先に変更するseam (`beginOperation` / synchronized bind) であり、処理中の破棄受理は「破棄済み表示」と「開始済みrun」の乖離を避けられないため、破棄側を不受理にして乖離経路を構造で閉じる)。受理時はpending intentを破棄し `screen = Closed`。`ExportSessionStore.invalidate` は呼ばない (再取り込み可能性維持)。`close()` / `openImport()` / 新規import開始も旧attemptをinvalidateする (遅延validation settleはdropされる)。
  - 成功状態composable: heading (成功、または判断なし合算 > 0ならwarning語彙) + summary行 (plurals) + 「未適用」明示行 + **内訳4種 (V1必須: 優先度/グループ/配置先/保持、`ExchangeImportSummary` から)** + **全体方針行 (`summary.minimizeMovement == true` のとき「全体方針: 現状の配置をなるべく維持する」)** + CTA (`Button`、`enabled = !continuing`、文言はD-3確定: idle「この提案で整理を進める」/ run内「選択に戻って整理を確定する」) + 破棄 (`OutlinedButton`「破棄して閉じる」、`enabled = !continuing`) + 再取り込み案内 (破棄時)。testTag・live region・focus (既存 `FocusTargetText`/`liveRegion` パターン準拠)。
- **`ui/preferences/destinations/ManualOrganizationPreferences.kt` — hosting調整 (Back interception + 競合freeze)**:
  - **system Backのinterceptionは成功stateのlazy item内ではなく、画面level (常時composition) に置く**: `ManualOrganizationBackHandler(coordinator)` (L186) の **呼出より後** に、`exchangeHolder.screen is ImportSuccess` でenabledになる `OnBackPressedCallback` (または同等の `BackHandler`) を同一画面levelで登録する。Compose `OnBackPressedDispatcher` は最後に追加されたenabled callbackへ委譲するため、成功state表示中はこのcallbackが常に優先され (continuing中を含む — 成功状態が開いている間は親の `dismiss()` にBackが落ちない)、それ以外は既存の画面level Backが従来どおり動く。成功stateのlazy itemがviewport外 (large font等) でcompositionから外れても、interceptionはhosting画面compositionに常駐するため保護が損なわれない。callback内の挙動はD-2確定: `continuing` でない → 破棄確認dialog (確定で `discardImport()`、キャンセルで成功状態維持) / `continuing` → 確認dialogを出さず **Backを取り込む (no-op — settle待ちの短時間窓の操作不能はCTA/破棄のdisabled表示と同時に示される)**。
  - idle branchの「整理を開始」rowを、`exchangeHolder.importAttemptActive` (下記) の間無効化。validation中も含む — validation中に開始された新runの上へ後から成功状態が載る (idle row開始 → `start` がrun stateを先に進める) 経路を構造で閉じる。
  - **run内branchのstrategy picker凍結**: `State.Selecting` かつ `exchangeHolder.importAttemptActive` の間、strategy pickerの選択変更 (`onStrategySelected` のrun active時 `dismiss()` → `start(trigger)` path) を無効化する (radio無効化 + a11y理由表示)。成功状態表示後だけでなく **validation中 (`Importing`) も含む** — picker凍結を成功状態後だけにすると、validation中の差し替えで別runが `State.Selecting` を持ったまま成功状態が遅延settleし、`attachIntent` が別runを受理する経路が残る (`attachIntent` は「どのrunか」を問わないため、owning runId照合と組み合わせて二重に防ぐ)。
  - **idle branchのstrategy picker凍結 (`continuing` 中)**: idle entryでも `exchangeHolder.importContinuationActive` (下記) の間はstrategy pickerを無効化する。idle CTAで `run.start` がsettle前にrunを活性化するため、この窓のstrategy選択変更はcommit時run判定が真になり、validated intentを持たない別runへの差し替えを起こし得る (「idle pickerは常に操作可」ではない)。`continuing` でない間は操作でき、run非activeのため既存のpreference書込のみで成功状態は不変。
  - **strategy書込との相互排他gate (UI非依存)**: holderとhostingが共有するstate 2点を追加する。**(a) `importContinuationActive`** — holderのpredicateで、CTA flipからsettle終端までtrue。hostingの `onStrategySelected` commit時 (既存のrun active判定の直前) にこれを検査し、trueなら `dismiss()` → `start(trigger)` を **発火しない** (strategyのcommit自体は有効で次回以降のrunに反映)。CTA開始前に始まった書込がcontinuation中に遅延commitする競合を構造で閉じる。**(b) `strategyWriteInFlight`** — hostingのpredicateで、`execute { store.select(id) }` の開始からcommit/no-opまでtrue。holderの `continueImport()` step 1がこれを検査し、trueならCTA開始を拒否する。両predicateともUIのdisabled表示とは独立したstate gateである (disabledだけではCTA直前に開始済みの書込を止められない)。
  - holderへ **`importAttemptActive`** を追加: attempt採番から、そのattemptの終端 (validation結果の適用/到達不能drop、`ImportSuccess` の終了 — CTA成功で `Closed` / `discardImport` / 新import・`close()` による置換) までtrue。idle row・run内picker凍結はこのpredicateに連動する (screen stateだけではvalidation中のattempt生存を判定できないため)。
  - hosting条件 (idleLike / Selecting) 自体は無変更。選択編集freezeは既存 `exchangeBusy` が `ImportSuccess` を包含するため追加変更なし。
- **strings (`values/` + `values-ja/`)**: 成功heading・未適用行・summary系 (plurals)・判断なし説明・CTA (D-3確定文言)・破棄label「破棄して閉じる」・破棄確認dialog (「取り込みを破棄しますか?」+ 再取り込み案内)・再取り込み案内・CTA時busy案内 (`exchange_run_busy` 再利用可否を確認 — 「取り込めません」は成功済みと矛盾するため新規stringが妥当な見込み)。jaを正本とする。
- **変更しないseam**: `ManualOrganizationRun.start/attachIntent/confirmSelection`、`ExchangeFlowController.importReply`、`ExchangeImportPipeline`、`IntentCompletion`/`IntentValidator` (#330の実装)、失敗表示 (`ImportOutcomeScreen`)、export flow (生成〜transport)。CTAは既存seamを呼ぶだけ。

### Data flow

```text
import (clipboard/file/貼付 → 共通receipt path、#332実装済み)
  → import開始時に attempt token 採番 (entryKind + run内はowning runIdをcapture)
  ├─ 競合freeze開始 (idle: 「整理を開始」row無効 / run内: strategy picker凍結 — validation中から)
  → controller.importReply (既存、変更なし) — IO coroutine
  ├─ 遅延settle guard: tokenがcurrentでない (cancel/新import/置換後) → drop
  │    (cancel後の遅延Validatedは成功状態を再出現させない、A→B順不同は最新attemptのみ、
  │     run-inでowning runId不一致 → drop)
  ├─ Failure/InputNotReady (token current) → ImportOutcomeScreen (既存、無変更)
  └─ Validated (token current + run-inはowning runIdがSelecting保持) → summary導出 (pure、
       validated.completed基準・内訳4種必須) → ImportSuccess状態 (token継承)
       ├─ run state不変 (idle: Idle/Cancelled、run内: Selecting+freeze継続、zero-write)
       ├─ system Back → 画面level interception (成功item画面外でも有効、成功状態中は常に親dismissより優先)
       │     ├─ continuing中 → Back取り込み (no-op、確認dialogも出さない)
       │     └─ 非continuing → 確認dialog → 確定で discardImport()
       ├─ 破棄 (明示ボタン「破棄して閉じる」またはBack確認dialog) → discardImport()
       │     (continuing中は不受理 — ボタンdisabled / 操作refuse)
       │     → intent破棄・session無invalidate → Closed (依頼有効期間中は同じ回答textの再取り込みが成立)
       └─ CTA (single-flight: 同期continuing flip → IO seam呼出 → attempt token anchored settle)
             ├─ strategy書込in-flight → CTA開始拒否 (typed案内・再試行可 — 相互排他)
             ├─ continuing開始 → 両entryのstrategy picker無効化 + commit時gate有効化
             │    (strategy書込がcontinuation中に遅延commitしても dismiss/start は発火しない)
             ├─ idle: run.start(trigger, intent) / run内: run.attachIntent — 各1回のみ
             │     ├─ 成功 (runId一致確認済み) → Closed → 既存flow (選択 → planning → preview → confirm → apply)
             │     ├─ Busy/NotAttachable/runId不一致 → continuing解除 + typed status (成功状態維持・再試行可・破棄/Back再可)
             │     ├─ seam例外 (生存contextならCE含む全例外) → 同上のfailure settle (runはseam内でabort済み)
             │     └─ 遅延settle (token不一致 — 閉じた/置き換わった/同identity再importの新attempt) → drop
             └─ continuing中の破棄・Backは不受理 (seam開始〜settleの乖離経路を構造で閉じる)
```

### Alternatives rejected

- **BackHandlerを成功state composable (lazy item) 内に登録し既存 `ManualOrganizationBackHandler` より後に登録して優先させる** (初回draft案、review Required 2): exchange画面は全てlazy list itemであり、large font等で成功itemがviewport外に出るとcompositionから外れ、callback自体が存在しなくなる。その状態のsystem Backは親の `dismiss()` (run cancel / navigate away) に落ち、pending intentが黙って消える (AC-5/AC-7違反)。interceptionを常時compositionされるhosting画面levelへ置く設計に固定。
- **run内 `ImportSuccess` 中もstrategy pickerを許可** (初回draft案、review Required 3): `onStrategySelected` はrun active時に `dismiss()` → `start(trigger)` でrunを差し替えるため、pending intentのbind先・freeze済み選択・成功状態のCTA対象が契約外に入れ替わり得る。run内成功状態中はpickerのrun再開pathを無効化。
- **CTAを素通しでIO coroutineに投げる** (review Required 4): 複数回押下で複数coroutineが同じ成功状態を読み、`start`/`attachIntent` を複数回呼ぶ。settle順により成功後にstale `RUN_BUSY` が残る等のUI競合。`continuing` flagの同期check-and-set + attempt token anchored settleで構造的に排除。
- **`validated.identity` をsettle anchorにする** (前回revision案、2nd review Required 2): identityはcompleted表現へのcontent digest (#330 D-5) であり、同一semantic内容の再importは同一identityになる。旧attempt Aと同一内容の新attempt Bが併存し得るABA形競合で、`identity一致 && continuing` ではAの遅延settleをBと区別できない (既存 `settleBelongsTo` が安全なのはexportIdがdisclosure generationごとに一意なため)。`ImportSuccess` 生成ごとに採番するprocess-local単調増加tokenへ変更 (このtokenはrevision 3でimport開始時採番のattempt tokenへ統合)。
- **`continuing` 中も破棄/Backを受理し、seam開始前のcancel tokenで撤回する** (2nd review Required 1): `run.start` は返る前に `beginOperation()` でrun stateを先に進め、`attachIntent` はsynchronized内でbindする — seam呼出後は外側から取り消せない。cancel可能なのはseam呼出前の僅かな窓だけで、窓guardだけでは「破棄 = zero-write / run接続なし」の契約を構造保証できない。破棄・Back側を `continuing` 中不受理にする方が単純で強く、settle後 (成功で遷移 / 拒否で解除) に操作は復帰する。
- **成功状態 (`ImportSuccess`) 生成後だけをtokenで保護する** (前回revision案、3rd review 高×1): `controller.importReply()` は呼出ごとに独立したIO coroutineで走り、validation settleにguardがない。cancel後の遅延 `Validated` が成功状態を再出現させ、A→Bの順不同settleで古いattemptが最新を上書きし、run内ではvalidation中のpicker凍結漏れ + owning runId不在により別runへの接続が成立し得る。anchorをimport開始時点まで前倒しし (validation settleとCTA settleを同一attempt tokenで閉じる)、競合freezeもattempt生存中へ拡張する。
- **idle entryのstrategy pickerを常時許可とする** (revision 2–3案、4th review 高×1): idle CTAで `run.start` がsettle前にrunを活性化するため、continuing中のstrategy選択変更はcommit時run判定が真になり、validated intentを持たない別runへの差し替え (CTA成功settleの裏で競合runが残る) を起こし得る。idleも `continuing` 中はpickerを無効化し、さらにUI非依存のcommit時gateとCTA側のin-flight書込拒否で直列化する。
- **pickerのUI disabledだけでstrategy競合を止める**: CTA直前に開始された非同期 `store.select()` はCTA後にcommitでき、commit時点のrun判定で `dismiss()` → `start(trigger)` を発火する。UIのenabledはaffordanceでしかなく、commit時gate (state検査) とCTA側のin-flight拒否を必須とする。
- **summaryをitem計数のみに限定する** (revision 2–3案、4th review 中×1): plannerは `globalPreference?.minimizeMovement` を `globalMinimizeMovement` として実際に消費するため、item希望を持たないglobal-only intentで「希望0件」の表示と実際のplanner効果が乖離する。planner-effectiveな全体方針行をsummaryへ追加。
- **summaryを `itemIntents.size` / `unresolvedRefs.size` から導出** (初回draft案、review Required 1): v3ではbare entryが `itemIntents` に現れる (希望誤計上) かつomissionがどちらのlistにも現れない (判断なしの漏れ)。canonical `completed` 基準へ変更。
- **warning条件を明示unresolvedのみにする**: bare entry / omissionは明示unresolvedとsemanticに同一 (spec 330 D-5/D-6) であり、provenanceでwarning挙動を分けるのはdiagnostics区別のUI漏出かつ「判断なし項目の説明」というIssue要件の漏れ。canonical判断なし合算 > 0をwarning条件とする。
- **即時attach/start維持 + 成功bannerを各run状態surfaceに表示**: 成功説明がSelectingとPreview/terminalの2系統のsurfaceに分散し、terminal (NoChanges等) で埋もれる。Issueが求める「中間状態」(次stepの前) と不一致のため不採用。
- **破棄ではなくpending intentの保持 (再表示)**: #205/#331の「validated intentを保持しない」運用と新規lifetime category (保持された未bind intent) を追加することになり、hosting消失・process deathとの整合コストが再取り込み (session TTL内で常に可能、安価) に比して大きいため不採用。明示的破棄 + 再取り込み案内で「黙喪失なし」を満たす。
- **破棄時にsessionをinvalidateする**: 取り込み自体は成功しており依頼はまだ有効。invalidateすると再取り込み (唯一の回復path) を閉じてしまうため不採用。
- **summaryに `rationale`/`confidence` を含める**: untrusted自由文の直接表示・誤解を招く自己申告値であり、件数summaryで要件 (何を認識したか) が足りるため不採用 (spec固定)。
- **`ExchangeStatus` 1行の文言修正のみ**: 「取り込み結果・未適用・次操作」を同時に示す要件を1行statusで満たせず、Preview直行時に表示されない問題も解消しないため不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/` (新規summary file) | `ExchangeImportSummary` + 純粋導出関数 (入力 = `validated.completed` + `session`) | canonical基準のprivacy-safe summary、pure test |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | attempt token採番 (import開始時、entryKind + owning runId capture)、`ImportSuccess(summary, validated, entryKind, continuing, attemptToken)` state (token継承)、validation settleのtoken照合、`continueImport` (single-flight + token anchored settle + 例外failure settle) / `discardImport` (continuing中不受理) / `importAttemptActive`、成功composable (D-3確定文言・破棄label・内訳4種)、`import()` Validated分岐変更、`IMPORT_ACCEPTED` 即時表示廃止 | exchange flow所有のUI state machine。single-flightは `beginTransport`/`settleBelongsTo` と同一ファイルの既存パターンに準拠 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | 画面level Back interception (成功state表示中のみenabled、`ManualOrganizationBackHandler` より後に登録、continuing中はBack取り込みno-op、非continuingは確認dialog)、idle競合row無効化 (**validation中含む** — `importAttemptActive` 連動)、run内strategy picker凍結 (**validation中含む**)、idle picker凍結 (**continuing中**)、`strategyWriteInFlight` 管理、`onStrategySelected` commit時の `importContinuationActive` gate (continuation中は dismiss/start を発火しない) | Back保証・競合排除はhosting画面の常時compositionが正しい場所 (lazy item内不採用)。strategy書込との相互排他は書込のcommit pathがhostingに存在するため |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 新規strings (成功/summary/CTA/破棄/案内/picker凍結理由) | spec 123 ja/en契約 |
| `organizer/ui/ManualOrganizationRun.kt` | **変更なし** (`start(trigger, intent)` / `attachIntent` をそのまま利用) | seam不変 |
| `organizer/integration/exchange/ExchangeFlowController.kt` | **変更なし** (`importReply` が既に `Validated` を返す) | seam不変 |
| `organizer/personalization/` (IntentCompletion/IntentValidator) | **変更なし** (#330実装を消費するのみ) | seam不変 |

## Migration and recovery

- DB schema・storage変更なし。rollback = UI revertのみ。release後の残余dataなし。
- 失敗中のrollback: 不要 (CTAまでzero-write。CTA以降は既存run flowのrecovery)。
- 破棄後の回復: 同じ回答textの再取り込み (export session有効期限内。`CONTEXT_STALE` はhome変更時のみ既存どおり)。
- CTA処理中 (`continuing`) のprocess death: seamがすでにrun stateを進めている可能性はある (`start` のdetection/planning、`attachIntent` のbind済み) が、apply前のprocess-local stateのみでlayout DBへのdurable writeは発生していない。再起動後はUI state (attempt token込み) ごと消失し、run/success状態は復元されない。回復は再取り込み (pending intent非保持の既存philosophyどおり)。`!continuing` でのprocess deathはrun未開始・未接続 (spec scenarioどおり)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | holder unit test: validated → `ImportSuccess`、`start`/`attachIntent` 未呼出、run state不変。**blocking fake import**: (a) import → cancel → 遅延Validated → 成功状態非出現、(b) import A → import B の逆順settle → B (最新) のみが成功状態・Aはdrop、(c) run-inでsettle時owning runId不一致 → drop。hosting instrumentation test | `./gradlew :lawnchair:testLawnchairGithubDebugUnitTest` (既存exchange test suite拡張) |
| AC-2 | string解決 + 表示test (unit/instrumentation) | 同上 |
| AC-3 | holder unit test: CTA → seam呼出 (`Started`/`Attached`)、`Busy`/`NotAttachable` でcontinuing解除+状態維持。**二重押下 (continuing中の再呼出) でseam 1回のみ**、**閉じた成功状態への遅延settle drop** (既存 `lateResultFromAnOldWriteCannotTouchANewerDisclosure` と同型test)、**ABA: 同一identityの回答を再importした新attemptに旧attemptの遅延settleが適用されない** (attempt token不一致drop)、**CTA処理中の破棄/Back不受理**、**seam例外** ((a) 非CE throw → failure settle、(b) **CE throwだがscope生存** → failure settleでcontinuing解除・取り残しなし、(c) scope自体をcancel → UI settle要求なし)、**strategy相互排他** (blocking fakeで (d) idle CTAの `start` をdetection/planning中に停止 → strategy変更試行 → dismiss/start非発火・validated run維持、(e) strategy書込開始 → CTA → 遅延commitでもimported run差し替えなし、(f) `strategyWriteInFlight` 中のCTA開始拒否)、**runId不一致settle** (`Started.runId` ≠ 現在runのrunId → failure settle扱い)。`ManualOrganizationRunTest` 連携 | 同上 |
| AC-4 | `ExchangeImportSummary` unit test: `authoredItemCount` 導出、bare entry非計上 (bare 1件 → 0/1・全内訳0)、omission計上 (未言及2件 → 判断なし2)、合算の境界 (0件で行非表示)、**内訳4種の算式** (importance / desiredGroupRefs+groupSemantic / pageAffinity+regionAffinity / preserve (`false` も1件)、複数dimension指定項目の複数行計数、0件行非表示)、**global-only intent oracle** (`minimizeMovement=true` のみ → item計数0でも `minimizeMovement=true` を返し全体方針行が表示される、`false`/`null` → 行なし)、**表示とplanner projectionの一致** (`globalMinimizeMovement` とのcontract test) + 出力modelのlabel/ref/text非混入 (型構造で保証 + contract test) | 同上 |
| AC-5 | holder unit test: discard → invalidate未呼出・再取り込み往復成立・pending intent消滅・**continuing中のdiscard不受理**。idle競合row無効化 (**validation中含む**) + run内strategy picker凍結 (**validation中含む**) + **idle pickerのcontinuing中無効化** + **commit時gate** (遅延commit → continuation中にdismiss/start非発火) + **`strategyWriteInFlight` 中のCTA拒否** のUI test + system Back確認dialog経路 (確定で破棄・キャンセルで維持) test | unit + instrumentation |
| AC-6 | semantics/live region assertion + TalkBack手動evidence | instrumentation + manual |
| AC-7 | instrumentation: 成功state表示中に成功itemをviewport外へscroll (またはfont scale最大) → system Back → 親dismiss/navigate非発火assertion | instrumentation |
| AC-8 | font scale最大でのinstrumentation/手動evidence | manual (emulator/device) |
| AC-9 | physical device: Import → CTA → 選択/composition → preview のevidence | device (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-10 | 既存suite regression (`ExchangeFlowStateHolderTest`、`ExchangeFlowControllerTest`、失敗表示19種、`ManualOrganizationRunTest`) | 同上 |

含める観察: unit (state machine遷移・summary導出・discard往復・single-flight・continuing中の破棄/Back不受理・ABA遅延settle分離・**validation段階のattempt anchor** (cancel後遅延drop / A→B順不同 / owning runId不一致)・**seam例外settle**)、contract (summary非混入、failure表示regression)、failure injection (CTA時Busy/NotAttachable、二重押下、遅延settle、破棄→再取り込み)、a11y/large font (画面外Back含む)、e2e device evidence。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md (用語: 取り込み成功状態、取り込み破棄、判断なし項目。acceptance時)
- [ ] DESIGN.md §4/§9 該当箇所への反映 (exchange UIに成功状態が加わること。acceptance時に判断)
- [ ] ADR: なし想定 (破棄semantics・summary基準はspec Decisionsに根拠を記録。変更困難性が3条件を満たすかは実装後判断)
- [ ] requirements.md (FR-017 status整理 — 本Issue単独ではFR-017完了を意味しない)

## Dependencies and blockers

- spec D-2/D-3 (およびOpen question 3) はowner decision確定済み ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396)、2026-09-18)。specの承認を待つのみ。
- #205/#331/#204/#228/#194/#195: すべてimplemented/acceptedであり技術blockerなし。
- #329/#330/#332/#348: implemented (origin/main `a9ec3c2cf9`、本branchにmerge済み)。#332の入力UIは本計画の接続点 (`Validated` → `ImportSuccess`) と既に整合済み。#330のcanonical representationはsummary導出の入力として消費する。技術blockerなし。
- #327: **implemented** (PR #350、origin/main `a9ec3c2cf9`、本branchにmerge済み)。landed変更面はentry rowのcapability説明 (`ExchangeCapabilityNotes`)・strings・instruction契約であり、validation通過後のflow (`import()` 即時attach/start) は不変 — 本計画の変更pathとの差替競合なし。回帰surfaceへ #327 tests (`ExchangeCapabilityCopyTest` / `Issue327InterviewFirstContractTest`) を追加。

## Risks

- run接続の延期により、検証通過からCTAまでの間にprocess deathが起きるとintentを失う (従来も同philosophy。回復は再取り込み。spec scenarioで明示済み)。
- `run.start` をCTA時まで遅らせることで、CTA時にlayoutが変化している可能性が生じる — 既存の2段stale検証 (`CONTEXT_STALE` はimport時、revision stale checkはplanning/apply時) で既にカバーされる。CTA遅延実行の経路をtestでcoverageする。
- Back interceptionの登録順依存 (hosting画面level callbackが `ManualOrganizationBackHandler` より後で追加されること) の実装忘れ・順序間違いは「黙喪失」の再発になる — AC-7のinstrumentation testで成功item画面外状態を含めて検証。
- strategy picker凍結のscope間違い (idleでまで凍結する/凍結漏れ) は競合契約の崩れ — idle/run内両entryのUI testで検証。
- `exchange_run_busy` 文案の不適合リスク (「取り込めません」vs 成功済み) — 新規string前提で進め、既存string流用は実装時に比較判断。
- attempt token counterはprocess-localのUI stateであり、永続化・復元の対象にしない (process death時は成功状態ごと消失、回復は再取り込み — spec scenarioどおり)。採番・比較は全てuiDispatcher上のMain-confinedな遷移に置き、synchronized/atomicを導入しない (現行holderのscreen遷移と同じ実行model)。
- `continuing` 中のBack取り込み (no-op) は、settleまでの短時間窓で「Backが反応しない」体感を生む — CTA/破棄のdisabled表示と同時に示されるため混乱は限定的。settleは `start` 完了 / `attachIntent` 完了、`Busy`/`NotAttachable` 拒否、runId不一致、または **生存context内のseam例外 (CE含む)** のいずれかで必ず到達する (catch時に `ensureActive()` 相当でcoroutine自体のcancelを先に判定し、cancel済みなら再伝播 — scope自体の破棄はUI stateごと消失するため問題にならない)。fake seam例外 (非CE/CE両方) のtestをAC-3で必須とする。
- strategy相互排他gateの実装漏れ (commit時gateの検査忘れ、`strategyWriteInFlight` の解除忘れ) はimported run差し替えの再発やCTA永久拒否になる — AC-3/AC-5のblocking fake testで両方向を検証する。
- attempt token照合の実装漏れ・照合対象の取り違え (validation settleに旧anchorを残す等) はcancel後の再出現・順不同上書きを再導入する — blocking fake importによるAC-1の3競合test (cancel後遅延 / A→B逆順 / owning runId不一致) を必須とする。
- `importAttemptActive` のpredicate実装漏れ (validation中のfreezeが効かない) はrun差し替え経路の再発になる — AC-5のvalidation中UI testで検証。

## Explicitly unverified areas

- 本draft時点でbuild/test未実行 (docs-only変更のため)。
- TalkBack実機での読み分け・large font実機表示・viewport外Back の実機evidence・end-to-end evidence は未実施 (実装PR・evidence PRの対象)。
- `exchange_run_busy` 文案がCTA時拒否の案内としてそのまま適合するかは実装時に確認 (適合しなければ新規string)。
- Compose `OnBackPressedDispatcher` の「最後に追加されたenabled callback優先」挙動への依存はframework仕様に基づく設計判断であり、AC-7のinstrumentation testがこの依存の回帰guardを兼ねる。

## Execution checklist

- [ ] Current behavior reproduced (即時attach/start + 1行statusをtestで固定した上で置換)。
- [ ] summary純粋関数とholder遷移の失敗testを先行追加 (canonical計数: bare/omission、内訳4種算式、global-only intent、single-flight: 二重押下・遅延settle・ABA、validation段階のattempt anchor: cancel後遅延drop/A→B順不同/owning runId不一致、continuing中の破棄/Back不受理、seam例外settle: 非CE/CE-scope生存/scope cancel、strategy相互排他: idle CTA中restart非発火/遅延commit/in-flight CTA拒否)。
- [ ] Minimal implementation (attempt token採番/照合 + ImportSuccess (token継承) + single-flight CTA (例外failure settle・strategyWriteInFlight拒否・runId一致確認込み) + discard (continuing guard) + `importAttemptActive`/`importContinuationActive` + 画面level Back interception (continuing中no-op/非continuing確認dialog) + strings (D-2/D-3確定文言・内訳4種・全体方針行・seam例外typed status) + 競合row/picker無効化 (validation中含む・idle pickerはcontinuing中) + commit時gate)。
- [ ] Migration/recovery verified (不要 — 変更なしの確認)。
- [ ] Full relevant verification completed (unit + regression + a11y/large font/viewport外Back + device evidence)。
- [ ] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-16: 初回draft (baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` = origin/main)。現行実装 (`ExchangeFlowUi.import()` の即時attach/start、失敗専用ImportOutcomeScreen、Idle「整理を開始」row・Back handlerの現状) をコード確認して作成。
- 2026-09-18: Re-entry revision (review Required 1–4対応)。origin/mainを `8fd05a40d51a` へ追従 (merge commit `6c3c3f9fd9`、#329/#330/#332/#336/#348実装取り込み済み) の上、Current evidenceを現行コードで全面更新。**(1)** summary導出を `validated.completed` canonical基準へ変更 (`authoredItemCount` / `authoredUnresolvedCount + omittedCount` 合算、Authoredのみの内訳。旧coverage不変条件の記載除去)。**(2)** Back interceptionをhosting画面level (常時composition) へ移動 — lazy item内BackHandler案をrejectedに記録、AC-7 viewport外Back testを追加。**(3)** run内成功状態中のstrategy picker凍結をChange set/data flow/検証へ追加。**(4)** `continueImport()` のsingle-flight設計 (同期continuing flip、identity anchored settle、遅延settle drop) を既存 `beginTransport`/`settleBelongsTo` パターンに整合させて明記、AC-3 oracleへ二重押下/遅延settle testを追加。失敗種別をtyped 19種へ更新。
- 2026-09-18: Re-entry revision 2 (2nd review Required 1–3対応)。origin/mainを `a9ec3c2cf9` へ追従 (merge commit `073e9f8742`、#327/PR #350実装取り込み済み) の上、Current evidenceの行番号を再確認 (#327 landed面: `ExchangeCapabilityNotes` L698/729/780、`exchangeFlowItems` L558/573、BackHandler定義L906、`strategyPickerItems` 定義L851。core import success pathの即時attach/startは不変)。**(1)** `continuing` 中の破棄・Back **不受理** を設計へ追加 (`discardImport()` guard、Back interceptionのcontinuing分岐、seam呼出後のcancel不可性を根拠にrejected alternativeへ記録)。**(2)** settle anchorを `validated.identity` (content digest・ABA競合 — identity anchor案をrejectedへ移動) からprocess-local単調増加generation tokenへ変更。**(3)** #327をimplementedへ参照更新し回帰surfaceへ同testsを追加。D-2/D-3/Q3はowner decision確定 ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396)) を受け文言・確認形式を確定値へ更新。
- 2026-09-18: Re-entry revision 3 (3rd review 高×1・中×2・低×1対応)。Current evidenceへ **validation中の競合窓** の事実を追加 (`import()` は呼出ごとに独立coroutine、`close()` L207–209 / `openImport()` L202–204 / `Importing` 置換はvalidation中も自由、`start()` はL423–427で `abort()` 後に再throw、`attachIntent` はどのrunのSelectingかを問わず `State.Selecting.runId` (L250) で照合可能)。**(1) import validation自体のattempt anchor** (高): token採番をimport開始時点へ前倒しし、validation settle (`Validated`/`Failure`/`InputNotReady`) とCTA settleを同一tokenで閉じる。cancel後遅延drop・A→B順不同・owning runId不一致の3競合を構造で排除 (成功状態生成後だけtokenで守る旧案はrejectedへ)。競合freezeをattempt生存中の全期間へ拡張 (holderに `importAttemptActive` を追加)。**(2) seam例外時契約** (中): `CancellationException` 再throw、それ以外はattempt-bound failure settle (処理中解除・再試行/破棄可能) をDesign/AC-3/oracleへ固定 (旧「実装時に確認」メモを解消)。**(3) summary内訳4種のV1必須化** (中): 「optional」表記を廃止しitem単位算式 (`importance` / `desiredGroupRefs`+`groupSemantic` / `pageAffinity`+`regionAffinity` / `preserve` (`false` も1件))・複数dimension同時計数・0件行非表示をsummary model・composable・AC-4/oracleへ固定。**(4) process deathのcontinuing分岐** (低): `!continuing` (run未開始) と `continuing` (durable writeなし・UI stateごと消失) をMigration/spec scenarioと一致させた。
- 2026-09-18: Re-entry revision 4 (4th review 高×1・中×2対応)。Current evidenceへ追加事実: `onStrategySelected` の書込は `execute { store.select(id) }` の非同期pathで開始とcommitに時間差があり、CTA開始後のcommitもcommit時run判定でdismiss/restartを発火し得る (L235–257)。`start()` は `beginOperation()` 直後にrunを活性化しidle CTA処理中もrunはactive、`Started(runId)` (L223) と各stateの `runId` (L251/L463) でsettle時照合が可能。plannerは `globalMinimizeMovement = validated.intent.globalPreference?.minimizeMovement ?: false` (IntentPlannerAdapter L42) を消費。**(1) strategy変更とimport continuationの相互排他** (高): idleも `continuing` 中はpicker無効化 (旧「idle pickerは常に操作可」案はrejectedへ)、UI非依存の **commit時gate** (`importContinuationActive` 中は dismiss/start 非発火、commit自体は維持) と **`strategyWriteInFlight` 中のCTA開始拒否** をhosting/holderへ追加、defense-in-depthとして成功settle時の `Started.runId` 一致確認。**(2) seam例外契約の精緻化** (中): 無条件CE再throwはseam内部発CEで `continuing` 取り残しを生み得るため、「catch時に `ensureActive()` 相当でcoroutine自体のcancelを判定し、cancel済みなら再伝播、生存contextなら **CEを含む全例外** をfailure settleへ」へ修正 (AC-3へCE-scope生存test)。**(3) global preferenceのplanner-effective表示** (中): `ExchangeImportSummary` へ `minimizeMovement` flagを追加し、`true` で全体方針行「全体方針: 現状の配置をなるべく維持する」を表示 (`false`/`null` は行なし)。global-only intent問題を解消し、planner projectionとの一致をcontract test化 (item計数のみの旧案はrejectedへ)。
