# Implementation Plan: run面の表示統合・canonical順序固定・D-06条件表示・D-13語彙規約

> Issue: #369
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-20時点のmain（`b84d277f811264db73e9a941f7876e9e193e0e26`。#365/#366/#367/#368
merge後。PR #379/#380/#382/#384）での再確認である（初版は2026-09-19
`3076bdae7ebf8dbb086f251203968c06e9986258`基準。#368のstrategy picker撤去・
`StrategyWriteArbiter` restart経路廃止と、#368がcoordinatorへ追加した`operationActive`
projectionを反映して行番号・行数を更新済み）。

### 現行のrun coordinator（`ManualOrganizationRun.kt`）

`lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（1373行）:

- sealed interface `State` は正確に20状態: `Idle`, `Capturing`, `CandidateDetection`,
  `Selecting`, `ScopeMismatchFailed`, `Planning`, `InputUnavailable`,
  `CandidateResolutionFailed`, `PlanningRejected`, `NoChanges`, `Preview`,
  `PreviewUnavailable`, `Applying`, `Stale(origin)`, `Applied`, `Cancelled`,
  `InspectingRecovery`, `RecoveryPreview`, `Recovering`, `RecoveryResultState`。
- `start(trigger)` / `start(trigger, intent)`: `beginOperation`でRUN lease取得
  （`OrganizationOperationLease.Kind.RUN`）→ `State.Capturing` →
  `setIfActive(State.CandidateDetection)` → `detectMissingAppCandidates()`を**同期的**に実行
  → `Ready`なら**常に**`State.Selecting`（0件でも）→ UIの`confirmSelection`待ち。
  `Unavailable`なら選択面を開かず`runComposedPhase(operation, selection = null)`（spec 228 §7）。
  **detector復帰後の分岐にactive/cancelの再確認がない**（初版review「高」指摘の根拠）。
  `cancel()`は`CandidateDetection`を受理して`activeOperation`を外しleaseをcloseするため、
  検出中cancel後にdetectorが復帰すると、cancel済みoperationで`runComposedPhase`が
  `RUN_STARTED`を発行しcompositionを実行し得る（`runComposedPhase`はactive確認の前に
  `RUN_STARTED`を発行し`journalStarted = true`にする）。既存UIには検出中のcancel affordanceが
  なく、#369のT-09中断rowがこの競合を初めてユーザー到達可能にする。
- `beginOperation`（L1128-1152）: admission時に**まず**`State.Capturing`をpublishし（L1144）、
  その後`start()`が`State.CandidateDetection`をpublishする。stateのみを見るUIでは、
  admission直後にcaptureが最初の可視phaseとして観測され得る（2nd review「中」指摘の根拠）。
- `confirmSelection`（L453-497）: `State.Selecting`でのみ受理。empty selectionはplain full
  compose、非emptyはscope-composed。intent-bound時のearly scope equality gate
  （SET_MISMATCH→選択面再表示）を内包。
- `runComposedPhase`（L585-747）: active確認の前にRUN_STARTED eventを発行し
  `journalStarted = true`（L602）→ `State.Capturing` → composition →
  scope binding digest gate（intent-bound時。mismatchは選択面復帰または
  `ScopeMismatchFailed`終端）→ `CAPTURED` → `State.Planning` → plan → `PlanningProjection` →
  Planned/Rejected/Impossible分岐 → `handlePlanPreview`。
- `handlePlanPreview`（L757-811）: `PlanPreviewResult.Stale` →
  `transitionToStale(origin = DETECTED_BEFORE_REVIEW)`。`Previewed` → `State.Preview`。
  Add-runの環境失敗 → `State.PreviewUnavailable`（spec 228 AC-14）。
- `confirm()`（L865-938）: materialize（previewed planなら直接）→ candidate resolution失敗は
  typed再検出終端 → `State.Applying` → `applicationAdmitted.set(true)` → apply →
  `STALE_REVISION`/`EXACT_PRECONDITION_FAILED`は`State.Stale(APPLY_BLOCKED)`、他は
  `State.Applied`。
- `cancel()`（L831-862）: `applicationAdmitted`済みなら不受理（L834）。
  `dismiss()`（L1036-1084）: `ApplicationInProgress`を返してBack不逮捕（UI側で無視）。
  いずれも確認dialogはUIに存在しない。
- journal: 検出/選択windowはeventなし（`journalStarted` flag、L851-861のcomment）、
  RUN_STARTEDはcomposed phase開始時にmode確定後に発行。`USER_CANCELLED`は
  journal開始後のみ。
- #368由来の追加: `operationActive` StateFlow（run/recovery operationの生存projection。
  表示用であり、本Issueのcancel gate設計には影響しない）。

### 現行のrun面UI（`ManualOrganizationPreferences.kt`）

`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
（1553行。#368でrun面からstrategy pickerが撤去された）。`PreferenceLazyColumn`の
`when (state)`分岐で1状態1画面群。UIは`coordinator.stateFlow`を
`collectAsStateWithLifecycle()`で直接購読し（L98）、run actionは`scope.launch`+
`withContext(Dispatchers.IO)`で実行する（L212-216）。このためcoordinator内部の連続遷移の
中間state（0件`Selecting`、admission直後の`Capturing`）がmain collectorから観測され得る
（StateFlowのconflationは中間値の非観測を保証しない。2nd review「中」2件の根拠）:

- Idle/Cancelled（L234-273）: checking行＋durable status行群（spec 271）＋開始row
  （`manual_organization_start`、L252。import attempt中freeze・spec 328）。
- `Capturing`/`CandidateDetection`/`Planning`（L276-293, L348-355）: 各1行の`ProgressText`
  （liveRegion Polite）。中断rowなし。3状態で面が分かれている。
- `Selecting`（L294-347）: `missingAppSelectionItems`（L326）＋SCOPE_MISMATCH rejection行＋
  `exchangeFlowItems`（run-in entry、L334）。
- 失敗系（L356-426）: `InputUnavailable`/`ScopeMismatchFailed`/`CandidateResolutionFailed`/
  `PlanningRejected`がそれぞれ独立の`FocusTargetText`＋`manual_organization_retry` row。
- `NoChanges`（L427-438）/ `Preview`（L439-485）/ `PreviewUnavailable`（L486-516）/
  `Applying`（L517-528、「Cancel before applying」row）/ `Stale`（L529-562、spec 210の
  3要素構成）/ `Applied`（L563-616、完了形件数＋復元＋safe terminal診断）。
- 復元系（L617-707）: `InspectingRecovery`/`RecoveryPreview`（spec 230 history行）/
  `Recovering`/`RecoveryResultState`。
- `ManualOrganizationBackHandler`（L806-843）: Back → `coordinator.dismiss()` →
  `CancelledAndMayNavigate`ならそのままback（**確認dialogなし**）。
  `ApplicationInProgress`なら不受理。
- focus復帰機構（`focusTargetIndex`/L154、`LaunchedEffect`/L192）と
  spec 195の`previewDetailsItems`（L1130）、spec 209のdecision pair
  （`PreviewDecisionActions`/`DecisionActionsRow`）。

### 現行の接続経路

- `PreferenceRoutes.kt` L132-145: `OrganizationEntry.MANUAL/ONBOARDING` →
  `HomeScreenManualOrganization(entry)` → `Trigger.MANUAL_FULL`/`ONBOARDING_PROPOSAL`。
- `PreferenceNavigation.kt` L126-131: destination組立（`ManualOrganizationPreferences`）。
- `OrganizationOnboardingProposal.kt` L216, L270: onboarding「確認」は
  floating viewから直接`ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)`
  を呼び（**admission直行。D-16と既に整合**）、run面へ遷移する。T-07前置きは経ない。
- hub（#366実装済み、`OrganizerHubPreferences.kt`）: hub CTA → 既存run destination（暫定）。
  #369でT-07前置き面経由に置換される面を持つのはrun destinationのIdle/Cancelled分岐である。

### 現行のstrings（語彙の基準点）

`lawnchair/res/values/strings.xml` / `values-ja/strings.xml`:

- `manual_organization_cancel` = "Cancel"/"キャンセル" — Preview/PreviewUnavailable/
  RecoveryPreviewのdecision pairとApplyingのcheckpoint前rowで使用。
- `manual_organization_cancel_before_checkpoint` = "Cancel before applying"/"適用前にキャンセル"。
- `manual_organization_missing_apps_empty`（0件notice）/`manual_organization_missing_apps_continue`
  （"Continue"/"続行"）— D-06適用で未使用化する候補。
- `manual_organization_retry` = "Try again"/"再試行"、`manual_organization_start_again`
  = "Start a new review"、`manual_organization_recapture`（spec 210、文言不変）。
- exchange系: `exchange_import_discard*`（破棄+確認。D-13適合済み）、
  `exchange_start_frozen_import`（idle start row freeze、spec 328）。

### 現行のtest面

- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`（1994行）:
  既定のfake detectionは`Unavailable`（= 選択面を経ない現行経路に依存するtestが多い）。
  `detected(...)`（L1434）で候補あり経路、L1521
  `confirmingAnEmptySelectionRunsThePlainFullCompose`が
  `detection = detected()`（0件 → Selecting → `confirmSelection(emptySet())`）を固定 —
  **D-06適用で期待値更新が必要な唯一系統**。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/MissingAppSelectionInstrumentationTest.kt`
  L225 `zeroCandidatesShowsTheEmptyNoticeAndStillContinues` — 0件表示oracle
  （RUN-AC-05の更新対象）。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
  — 面構成・stale・結果・a11y・ja解決のoracle群。
- `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite`
  — spec 210 AC-5のE2E（origin主張つき。無編集greenが回帰証拠）。
- `ExchangeFlowStateHolderTest` / `ExchangeImportSuccessInstrumentationTest` /
  `ExchangeImportSurfaceInstrumentationTest` — exchange導線の非依存回帰。

## Design

### Modules and interfaces

表示統合の主戦場はUI層（`ManualOrganizationPreferences.kt`）である。coordinator
（`ManualOrganizationRun.kt`）への変更は次の3点に限定する（spec RD-3/RD-6/RD-7）:

1. **D-06の0件時内部継続**（表示統合。state machine不変）— `start()`の検出`Ready`分岐。
2. **検出完了後の進入判定と`RUN_STARTED`発行のatomic化**（cancel gate）—
   `runComposedPhase()`入口と検出結果受理helper。
3. **準備phaseの可視projection（`PreparationPhase`）**（表示用の決定的導出）—
   admission時・composed phase進入時・plan遷移時の更新のみ。

いずれも既存契約の範囲内であり、DESIGN.md §4.4の「UI adapter: ...確認、結果、復旧を表示する」
の所有境界に沿い、planning/application/protocol moduleには触れない。

**面コンポーネントの分離とface mapping（純関数）**: `when (state)`分岐を面単位の
private composable群へ整理する（`PreparationFace`（T-09）、`IntegratedFailureFace`（T-13）、
`ResultFace`（T-12）等）。面への写像はUI層の純関数`manualOrganizationFace(state)`
（内部関数。unit test可能）に集約し、spec対応表のとおり各stateを面へ写像する。
**D-06の非表示はこの写像で保証する**（RD-7）: `Selecting`のうち候補0件かつ
`intentScopeCount == 0`かつ`scopeRejection == null`のものはT-09準備中面へ写像し、T-08の
composableを構成しない。観測timing（conflationの有無）に依存しない。scope rejectionや
export scope候補がある`Selecting`はT-08のままである（spec 331 mismatch契約）。
各面は既存の`FocusTargetText`/`ProgressText`/`SummaryText`/`DecisionActionsRow`/
`summaryItems`/`appliedResultItems`/`previewDetailsItems`等のhelperを再利用する。
新規の状態公開はcoordinatorの可視phase projection（下記）1件のみである
（interface追加は「production用とtest用の実体が必要になるまで」作らない規約に従い最小化。
conflationに依存した非表示保証は2nd reviewで不成立と判定されたため、この1件の追加が必要と
なった）。

**可視phase projection（`PreparationPhase`）**: coordinatorに
`val preparationPhase: StateFlow<PreparationPhase>`（enum: `DETECTION` / `CAPTURE` / `PLAN`）
を追加する。更新はstate遷移と同一lock区間内で行い、activeでないoperationは更新しない。
**更新順序の契約（RD-7。3rd review指摘の解消）**:
`PreparationPhase.CAPTURE`/`PLAN`は、T-09を可視に戻す`State.Capturing`/`State.Planning`の
publishより**先に**、同一lock区間内で確定する。これにより
`(stateFlow = Capturing/Planning, preparationPhase = 前phaseのまま)`という中間組合せは
いかなるtimingでも観測できず、可視列は常に`検出 → [選択] → capture → plan`となる:

- `beginOperation()`: `DETECTION`へ設定（admission直後のlegacy `Capturing`は検出として投影
  される。canonical順序の最初の可視phaseは常に検出： RD-7）。
- `confirmSelection()`の成功経路: 既存のlock区間内で、二重confirm禁止のguardとしての
  `State.Capturing` publishに**先立って**`CAPTURE`へ設定する（publish順序の変更のみで、
  guard構造・lock構造は現行のまま。候補あり経路のT-08→T-09復帰で`DETECTION`が
  再表示されないことを保証する）。
- D-06内部継続（`continueWithEmptySelection`）: lock区間内で`CAPTURE`へ設定してから
  `State.Capturing`をpublishする。
- `runComposedPhase()`のcancel gate（lock内、`RUN_STARTED`発行と同じ区間）: 未設定の場合
  `CAPTURE`へ設定（`Unavailable`経路。RD-6のatomic区間）。
- `State.Planning`への遷移（`setIfActive`直前の同一lock区間）: `PLAN`へ設定。

T-09のphase行は`state`種別からではなくこのprojectionから描画する
（`collectAsStateWithLifecycle()`で購読）。0件`Selecting`のinternal継続窓でもphase表示は
検出（または継続後のcapture）のまま推移し、capture→検出への逆順表示が発生しない。
projectionはstate machineに新たな状態を追加せず、表示の決定性のための導出値である。

**確認dialog**: 中断/Back確認用の共通composable（1箇所）をUI層に追加する。
表示条件の判定はobservable stateから導出する（`Selecting`の選択非空、`Preview`/
`PreviewUnavailable`のplan存在、等）。coordinator APIの変更は不要である
（confirm応答後の`cancel()`/`dismiss()`は既存契約どおり）。

**D-06の0件時内部継続（coordinator・state machine不変）**: `start()`の検出`Ready`分岐を
次のようにする。coordinatorは0件でも既存どおり`State.Selecting`へ進入し（遷移graph・
entry条件は不変）、直後にcoordinator内部の専用continuationでcomposed phaseへ進む
（spec RD-3）:

```kotlin
is CandidateDetectionResult.Ready -> {
    acceptDetection(operation, detection)   // lock下でactive再確認+detectedCandidates保持
    val exportedScopeCandidates = operation.intent?.session?.scopeCandidates
    if (detection.candidates.isEmpty() && exportedScopeCandidates.isNullOrEmpty()) {
        setIfActive(operation, State.Selecting(runId, detection.candidates, ...))
        continueWithEmptySelection(operation)   // D-06: 内部継続（選択面を表示せずcapture/planへ）
    } else {
        setIfActive(operation, State.Selecting(runId, detection.candidates, ...))
    }
}
```

`continueWithEmptySelection(operation)`は`confirmSelection`の空選択経路と同じ内部手続き
（lock下でactive+`State.Selecting`確認 → `State.Capturing` → `runComposedPhase(operation,
selection = null)`）を、UI actionを介さずに実行する専用private methodである。ユーザーの
明示actionを偽装せず（spec 228 D-1は候補があるときの明示選択を要求する。0件は「選ぶものが
ない」ため対象外）、選択stateへの書込みも発生しない。guard条件により、intent-bound
（export scope候補あり）×検出0件では選択面が開き、spec 331のmismatch表示契約が保存される。
`Selecting`進入後の遷移graph・lock構造・journal規則は変更しない。0件経路でも`Selecting`へ
進入するため、既存state期待値のうち「Ready→`Selecting`進入」は不変であり、更新対象は
「0件で`confirmSelection`待ちで停止する」継続timing oracleのみである。

**cancel gate（検出完了後の進入判定と`RUN_STARTED`発行のatomic化）**:
`runComposedPhase()`の入口を次の構造にする（spec RD-6）:

```kotlin
private fun runComposedPhase(operation: Operation, selection: List<CandidateTarget.AppKey>?) {
    val diagnosticsRunMode = if (selection != null) SCOPE_COMPOSED else FULL
    synchronized(lock) {
        if (!isActiveLocked(operation)) return   // cancel済み → 何もせず戻る（journal空のまま）
        operation.diagnosticsRunMode = diagnosticsRunMode
        operation.journalStarted = true
        emit(RunEvent(..., phase = PhaseCode.RUN_STARTED, ...))   // lock保持下で発行
    }
    setIfActive(operation, State.Capturing)
    ...
}
```

active判定・`journalStarted`設定・`RUN_STARTED`発行を同一lock区間内で行うことで、
「cancel が gate の前に取る → journalは空のまま」「gate通過後にcancelが取る →
`RUN_STARTED`の後に`USER_CANCELLED`が続く」のいずれかに決定的に帰着し、中間（cancel済み
runが`RUN_STARTED`を発行する）を構造的に排除する。diagnostics emitはcoordinator lockの
内側から一方向にのみ呼ばれ（emitterがcoordinatorを呼び出す経路はない）、fail-open
（例外握り）であるためlock下での発行は安全である。検出結果の受理もlock下の単一helper
（`acceptDetection`: active再確認+`detectedCandidates`保持）へ集約し、`start()`の
`Unavailable`経路・D-06内部継続・`confirmSelection`後の継続の全経路が同一gateを通る。
gate通過後の既存の`isActive`確認（composition後、planning後）は現状どおり残す。

### Data flow

表示のdata flowは`stateFlow` → composeが基本である。変化するのはstate → 面の写像のみ
（spec対応表）。T-09のphase行は`State`の種別からではなく可視phase projection
（`PreparationPhase`、RD-7）から決定的に導出され、admission直後のlegacy `Capturing`が
captureとして表示されることはない。面への写像は純関数`manualOrganizationFace(state)`に
集約される。T-13の原因文言は既存の
typed結果→string mapping（`InputReadinessReason.copyKind()`、
`manual_organization_candidate_unresolved`、`manual_organization_rejected`/
`impossible`＋summaryItems、`exchangeContractFailureText`、spec 210の
`stale_proposal_not_reviewed`）をそのまま再利用する。

### Alternatives rejected

- **StateFlowのconflationに依存して中間stateを「見えない」扱いにする案**（0件`Selecting`や
  admission直後`Capturing`を高速連続更新で隠す）: UIは`stateFlow`を直接購読し、run actionは
  `Dispatchers.IO`で実行されるため、main collectorが中間値を観測し得る。conflationは中間値の
  非観測を保証しない（2nd review「中」2件で不成立と判定）ため不採用。face mappingと
  可視phase projectionで決定的に担保する（RD-7）。
- **0件時のUI主導auto-confirm**（UIが0件`Selecting`を観測して`confirmSelection(emptySet())`
  を発行）: 明示選択契約（spec 228 D-1）のユーザーactionを偽装し、state/表示の瞬間不整合を
  生むため不採用（spec RD-3）。
- **0件時の`Selecting`不進入**（coordinatorが0件で`Selecting`へ遷移せず直接composed phaseへ）
  : accepted正本（TO-BE D-05「内部state machineの契約は変更しない」、disposition §3.3
  「内部state machine・typed outcome不変」「`ManualOrganizationRunTest`のstate期待値は
  不変」）を子specだけで例外化することになり、TO-BE D-05自体の改訂を要するため不採用
  （spec RD-3。review指摘の選択肢(b)は採らない）。内部継続なら表示結果（選択面非表示）は
  同一で、正本の契約文をすべて真のまま保てる。
- **進入判定のみのgate（emitをlock外に残す案）**: gate通過後・emit前の窓にcancelが割り込む
  と`CandidateDetection`/`Capturing`がcancel受理状態のため`RUN_STARTED`漏れが残る。
  判定と発行のatomic化（本plan）が必要である。
- **8状態へのstate machine再編**（coordinatorの`State`を8種へ畳む）: D-05は表示統合のみを
  要求し、typed outcome/journal相関の安全契約を変えるため不採用。origin/変種の保持が
  spec 210/331/172の原因文言精度を支えている。
- **T-07を独立destination（新route）とする案**: admissionとnavigationの順序に競合窓が生まれ
  （start成功前に遷移、Busy時の後始末）、#366のhub CTA → run destination構成からの変更が
  大きくなる。Idle/Cancelled分岐の前置き面化で同じ観測結果が得られるため不採用。
  owner reviewで独立面が選ばれた場合は本planの該当箇所を修正する。
- **T-14（復元の確認）の統合再設計**: TO-BE §8.1は復元系3状態を「復元の確認」1状態へ対応させる
  が、spec 84/230の契約が現行表示で満たされており、本Issueの受入条件（Issue scopeは
  T-10/T-11/T-12/T-13を明記しT-14を含まない）を超えるため本Issueでは行わない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | `start()`の検出Ready分岐にD-06 0件時内部継続（intent-aware guard付き。`continueWithEmptySelection`）を追加。`runComposedPhase()`入口のcancel gate（active判定+`journalStarted`設定+`RUN_STARTED`発行を同一lock下でatomic化）と検出結果受理helper（`acceptDetection`）を追加。可視phase projection `preparationPhase: StateFlow<PreparationPhase>`（`DETECTION`/`CAPTURE`/`PLAN`）を新設し、`beginOperation()`/`confirmSelection()`成功経路（guard publishに先行）/D-06内部継続/cancel gate/`Planning`遷移の各同一lock区間で**state publishに先立って**更新（RD-7順序契約）。javadoc追記 | 選択面非表示の継続判定点・journal開始のatomic性・準備phaseの決定的導出と公開順序はcoordinatorのみが持てる（spec RD-3/RD-6/RD-7）。他の遷移は不変 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | state→面の写像を純関数`manualOrganizationFace(state)`へ集約（0件`Selecting`〔scope rejection・export scope候補なし〕→T-09。RD-7の決定的非表示保証）。Idle/Cancelled分岐をT-07前置き面化（方法選択CTA「そのまま整理」＋scope要約。durable status行・exchange idle entry・import freeze affordanceは現行維持）。`Capturing`/`CandidateDetection`/`Planning`をT-09統合progress面（phase行は`preparationPhase`由来＋中断row）へ統合。失敗系5状態をT-13統合面（見出し「実行できませんでした」＋原因＋再試行/中断（＋該当時診断））へ統合。`NoChanges`/`Stale(APPLY_BLOCKED)`をT-12結果面の変種へ統合。確認dialog追加とBack handlerへの組込み、cancel/中断labelのD-13語彙化 | 表示統合の全変更が収束する唯一のrun面。helper群は再利用。面写像の純関数化でD-06と対応表をunit test可能にする |
| `lawnchair/src/app/lawnchair/organizer/ui/MissingAppSelectionScreen.kt` | 0件notice経路の削除（`missing_apps_empty`表示分岐。選択面本体・spec 228契約UIは不変） | 0件選択面が到達不能になるため。mismatch再表示経路（候補0件＋scopeRejection）の表示は維持 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 新規: T-07方法選択・scope要約、T-09見出し・phase・中断、T-13見出し、確認dialog（破棄/中断）文案等。変更: `manual_organization_cancel_before_checkpoint`系の中断語彙化。削除: 未使用化string（`manual_organization_missing_apps_empty`等。reference grepで確定） | spec 123契約（EN/ja・format resource） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` | D-06経路の継続timing oracle更新（`confirmingAnEmptySelectionRunsThePlainFullCompose`等の0件経路を「内部継続で直ちにcomposed phase」の期待へ。`Selecting`進入自体は不変のまま観測）。guard条件の新test（intent-bound×検出0件で選択面が開く）。可視phase projectionのoracle（blocking detectorでdetector停止中に`preparationPhase`が`DETECTION`であること。`Unavailable`/0件継続後に`CAPTURE`へ決定的に変わること）。RUN-AC-10のblocking fake detector oracle（検出中cancel × detector復帰結果3系： 0件/候補あり/`Unavailable`。最終状態`Cancelled`・当該runIdのjournal event不発・lease close 1回。対照系： gate通過後cancelで`RUN_STARTED`→`USER_CANCELLED`順序） | spec RD-3/RD-6/RD-7。D-06・cancel競合・projectionの対象経路以外は無編集 |
| `tests/unit/.../ManualOrganizationFaceTest`（新規。UI層純関数のunit test） | face mapping純関数のtable-driven test: 対応表の全state→面の写像と、0件`Selecting`（scope rejection/export scope候補なし）→T-09、0件＋`scopeRejection`→T-08、候補あり→T-08を固定 | spec RD-7/RUN-AC-01。conflation非依存の決定性oracle |
| `tests/organizer-instrumentation/.../MissingAppSelectionInstrumentationTest.kt` | `zeroCandidatesShowsTheEmptyNoticeAndStillContinues`を0件非表示oracleへ更新 | RUN-AC-05。obsolete理由をPRに記録 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | 面統合に伴う表示assert更新（T-09/T-13/T-12構造、確認dialog、label変更）。phase announce回数・focus復帰・200%のa11y oracle追加。既存原因文字列のT-13上の表示assert | RUN-AC-02/04/06 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | 原則無編集green（spec 210 AC-5）。表示面移設で文言assertの面参照が変わる場合のみ最小限更新 | 回帰証拠 |
| specs 52 / 228 / 210 | spec 52: MFO-AC-01へ検出phase挿入＋D-06反映＋Manual-run composition節のstate列挙に表示統合注記。spec 228: §2 0件規定を非表示へ、0件scenario/AC調整。spec 210: 統合先注記（APPLY_BLOCKED→T-12、DETECTED_BEFORE_REVIEW→T-13）。いずれもsafe apply契約・明示選択契約・spec 210文言は不変 | 処分文書§3.3/§3.13、Issue Spec節。同じ実装PRで実施 |

## Migration and recovery

- schema/rule migration: なし。persistent state・`organizer_*` store・Launcher DB /
  `favorites` への接触なし（ホームレイアウト安全規約の適用対象外）。
- failure中のrollback: 適用semantics自体は不変（spec 13/52）。表示統合は適用経路に触れない。
- release rollback/downgrade: PR revertで旧面構成へ戻る。run stateはprocess-local singleton
  であり、revert時に残留物はない。downgradeした旧binaryは自身のUI（個別面・0件選択面・
  確認なしcancel）で動作し、store formatの互換問題は存在しない。
- backup/restore compatibility: 影響なし（表示のみ）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| RUN-AC-01 | unit: D-06内部継続（0件＋intent未bound → 選択面を構成せずplain compose直行。state列は`Selecting`経由）、guard（intent-bound×検出0件 → 選択面）、検出`Unavailable`継続の既存test green。face mapping純関数のtable-driven unit test（0件`Selecting`→T-09、0件＋scopeRejection→T-08、候補あり→T-08等）。blocking detectorで`preparationPhase`がdetector停止中`DETECTION`であることのoracle（captureが先行しない）。候補あり経路`CandidateDetection → T-08 → confirmSelection → T-09`のoracle（T-08復帰後の最初のT-09 phaseが`CAPTURE`、`DETECTION`再表示/再announce 0回。RD-7順序契約）。instrumentation: T-07→そのまま整理→T-09検出→0件続行、0件で選択面要素の否定的観測 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane |
| RUN-AC-02 | instrumentation: T-09が検出/capture/planで同一面（phase行は`preparationPhase`由来で更新）、T-13見出し＋原因＋手段の構造、T-12変種表示 | 同上 |
| RUN-AC-03 | `ManualOrganizationRunTest`/`ExchangeFlowStateHolderTest`等のD-06継続timing oracle以外の無編集green + E2E `staleProductionConfirmationDoesNotWrite` green | 同上 + connected test lane |
| RUN-AC-04 | instrumentation: 中断/Back確認dialog（提案・選択ありで1回）、復元確認cancelの確認なし、Applying checkpoint前の確認付き中断、checkpoint後不受理（既存gate oracle継続） | 同上 |
| RUN-AC-05 | 旧oracle更新diff + obsolete理由のPR本文記録 | PR review |
| RUN-AC-06 | Compose semantics（announce回数、traversal、name/role/state）+ focus restoration + 200% font scale + light/dark × ja/default screenshot（emulator） | organizer instrumentation lane + emulator evidence |
| RUN-AC-07 | 既存preview/confirmation/result/recovery oracleのgreen（面移設のみの更新）+ spec 210文言assertの継続 | 同上 |
| RUN-AC-08 | specs 52/228/210のdiff review | PR review |
| RUN-AC-09 | 新規stringのEN/ja name集合・placeholder一致の機械確認 + 削除string reference grep（0件）+ hardcoded literal grep | `./gradlew spotlessCheck` + grep |
| RUN-AC-10 | unit: blocking fake detectorで検出中cancel × detector復帰（0件/候補あり/`Unavailable`）の3系 + gate通過後cancelの対照系。最終状態・journal event列・lease close回数を固定 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |

含めるべき観点: unit/contract（D-06・cancel gate競合・cancel/dismiss契約）、
UI/accessibility（§6受入基準）、
failure injection（既存fakeのinspectPlan/apply注入経路でT-13/T-12の各原因を駆動）、
回帰（exchange・strategy・durable statusの非依存test）、E2E（stale zero-write）。

## Documentation updates

- [ ] `docs/product/organizer-disposition-migration.md` §3.3へD-06解釈の2026-09-20追記
      （RD-3/RD-6。本spec/plan PRで実施。accepted文書へのreview対応追記は§2.3追記の
      前例どおり）
- [ ] spec 52 / 228 / 210（実装PRでAmend。RUN-AC-08）
- [ ] spec status/history（本spec: accepted → implemented、実装PRのhead/runを記録）
- [ ] CONTEXT.md — 用語追加は#365が所有するため本Issueでは触れない（「中断/破棄/キャンセル」
      の語彙はTO-BE §9を借用）
- [ ] DESIGN.md — 変更なし（module構造・gate・invariantは不変）
- [ ] ADR — 不要（IA/表示の判断はTO-BE §4に記録済み。ADRの3条件に該当する新規判断なし）
- [ ] organization-run-ux.md §8 coverage表の参照更新は#369実装時に参照更新のみ（処分文書§3.1）

## Execution checklist

- [ ] Current behavior reproduced（0件選択面表示・個別失敗面・確認なしcancel/Backを
      既存instrumentationで観測）。
- [ ] D-06内部継続のunit test（0件＋intent未bound → 選択面非表示でcomposed phase直行）を
      先に追加し、現行実装でfailすることを確認。
- [ ] face mapping純関数のtable-driven unit test（0件`Selecting`→T-09等）、
      可視phase projectionのoracle（detector停止中`DETECTION`、継続後`CAPTURE`）、
      候補あり経路のT-08→T-09復帰oracle（戻り後の最初のphaseが`CAPTURE`、
      `DETECTION`再announce 0回）を先に追加し、現行実装でfailすることを確認
      （中間state保持のdeterministic oracle）。
- [ ] RUN-AC-10のblocking fake detector oracle（検出中cancel × detector復帰3系＋対照系）を
      先に追加し、現行実装でfailすること（cancel済みrunの`RUN_STARTED`発行）を確認。
- [ ] coordinatorのD-06内部継続（guard付き）とcancel gate（`runComposedPhase`入口のatomic化、
      `acceptDetection`）、`preparationPhase` projection（RD-7順序契約:
      `confirmSelection()`のguard publishと`Planning`遷移に先立つ更新を含む）を実装し
      unit test green。
- [ ] T-07/T-09/T-13/T-12の面統合（face mapping純関数経由）とD-13語彙・確認dialogを実装し、
      既存testを更新。
- [ ] a11y evidence（announce/focus/200%/screenshot）を収集。
- [ ] specs 52/228/210を同じPRで改訂し、RUN-AC-08のdiff reviewを可能にする。
- [ ] obsolete oracleの理由記録をPR本文へ記載（RUN-AC-05）。
- [ ] Full relevant verification（unit gate + instrumentation lane + spotlessCheck +
      CI `final-status`）を完了し、結果をPRへ記録。
