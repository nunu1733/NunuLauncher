# Independent audit: PR #387 run面の表示統合・canonical順序固定・D-06条件表示・D-13語彙規約 (issue 369)

> Status: proposed
> Audit date: 2026-09-20 (audit-time CI snapshot記載のとおり)

- Auditor: ZCode independent audit session (implementation sessionとは別の独立session。実装・review・spec執筆には関与していない。本record以外の成果物はない。検証はGitHub APIとhead checkoutの読み取りのみで実施し、treeへの変更はこの記録fileのみ)。
- PR: https://github.com/nunu1733/NunuLauncher/pull/387
- **Audited head SHA: `33c56371ef2f0d7b32925ad19cd2daa4716a0d8a`** (`gh pr view 387 -R nunu1733/NunuLauncher --json headRefOid` とlocal checkout `git rev-parse HEAD` が一致。branch `issue-369-run-display-integration`、working tree clean、`origin` up to date)
- Base: `main` (Lawnchair v15.0.0-beta3.0 baselineのfork。repo identityは操作前に `gh repo view` で確認: `nunu1733/NunuLauncher` / default branch `main`)
- CI run (audit時点): https://github.com/nunu1733/NunuLauncher/actions/runs/35524033721 (head SHA一致、`in_progress`。下記「CI status at audit time」参照)
- Criteria: `specs/369-run-display-integration/spec.md` (`status: accepted` 2026-09-20) RUN-AC-01〜RUN-AC-10、Resolved decisions RD-1〜RD-7、Test oracle表、共通gate。`specs/369-run-display-integration/plan.md` (accepted) のChange set / Verification表。
- Risk分類: 本PRは `risk: layout-data` / `risk: migration` labelなし (labels `[]`を確認)。表示・navigationのみでpersistent state・DB書込み経路に触れないためspec共通gateの定めどおりhigh-risk evidence gateの対象外。CI `high-risk-evidence` checkはpass (labelなしの前提を機械検証)。

## Scope

`gh pr diff 387` の全diff (21 files, +1673/−264) を読み、head checkout上の実fileでlock区間・順序契約・文言参照を照合した。commits: `5b138bd33d8` (実装) → `7bff88466a` (review対応1) → `acc70486e3` (review対応2) → `33c56371ef` (review対応3)。

- **coordinator** `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (+184):
  - `PreparationPhase` enum (`DETECTION`/`CAPTURE`/`PLAN`) と `val preparationPhase: StateFlow<PreparationPhase>` (RD-7)。
  - D-06内部継続 `continueWithEmptySelection`: guard `detection.candidates.isEmpty() && exportedScopeCandidates.isNullOrEmpty()` のとき `State.Selecting` 進入 (既存どおり) の直後にlock区間で `CAPTURE` → `State.Capturing` → `runComposedPhase(selection = null)`。state machine不変 (RD-3)。
  - `acceptDetection` helper: `synchronized(lock)` 下でactive再確認+`detectedCandidates`保持。`start()`の`Ready`/`Unavailable`両経路が通る (RD-6)。
  - cancel gate: `runComposedPhase`入口で `synchronized(lock) { if (!isActiveLocked(operation)) return; diagnosticsRunMode設定; preparationPhase=CAPTURE; journalStarted=true; emit(RUN_STARTED) }` — active判定+`journalStarted`設定+`RUN_STARTED`発行が同一lock区間でatomic (RD-6)。gate通過後のcancelは既存契約どおり`USER_CANCELLED`が後続。
  - RD-7 phase-before-state順序: `beginOperation`がlock内で`DETECTION`リセット→legacy `Capturing` publish、`confirmSelection`のguard publish (二重confirm禁止経路) でlock内`CAPTURE`→`Capturing`、`Planning`遷移でlock内`PLAN`→`Planning`。`(Capturing/Planning, 取り残された前phase)` の中間組合せが観測不能であることをUnconfined collector unit oracleが固定 (下記)。
- **face mapping** `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt` (新設, +96): 純関数 `manualOrganizationFace(state)`。spec対応表どおり20状態→8面。`Selecting`は0件かつ`intentScopeCount == 0`かつ`scopeRejection == null`で`PREPARATION`、それ以外は`SELECTION` (D-06/RD-7決定的写像、conflation非依存)。`Stale`はoriginで分割: `APPLY_BLOCKED`→`RESULT` (T-12)、`DETECTED_BEFORE_REVIEW`→`FAILURE` (T-13) (D-12)。
- **UI** `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (+635/−264):
  - `Selecting`分岐の先頭でface gate (`manualOrganizationFace(currentState) == PREPARATION` → `preparationFaceItems`) が生の選択面構成より**先に**働く (RD-7)。
  - T-07前置き面 (transitional, RD-1): Idle/Cancelled分岐にscope要約row (`manual_organization_preamble_scope`, admission前の事実のみ/RD-5) を追加、start row (「そのまま整理」/「Organize as is」) は既存 `coordinator.start(trigger)` 経由でrun admission。import attempt中のfreeze (spec 328) とdurable status行 (spec 271) とexchange idle entryは現行維持。
  - T-09統合準備中面 `preparationFaceItems`: 見出し (`manual_organization_preparation`) + phase行 (**`preparationPhase` projection由来**、state種別からではない) + 確認なし中断row (RD-4)。
  - T-13統合失敗面: 見出し「実行できませんでした」(`manual_organization_failed`) + 原因 (既存typed mapping再利用: spec 172 copy split、spec 331 re-export、spec 228再検出、spec 52原因件数summary、spec 210入場前stale詳細文) + 再試行/中断 (診断は`InputUnavailable`かつ非`ReconciliationPending`のみ)。
  - T-12結果面変種: `Applied`/`NoChanges`/`Stale(APPLY_BLOCKED)`。`APPLY_BLOCKED`はspec 210のoutcome文・詳細文・recapture文言を不変のまま結果面構成へ。
  - T-11適用中面: checkpoint前のみ1回確認の中断 (`cancel_before_checkpoint` → 中断語彙)、checkpoint後はcoordinator gateが不受理 (`ApplicationInProgress`)。
  - D-13語彙: `PreviewDecisionActions`のcancel側と選択面cancel側を「中断」へ (spec 209のpair構造は維持)、共有確認dialog `ManualOrganizationDiscardConfirmDialog` (破棄confirm/キャンセルdismiss、no timeout)。Back handlerは破棄を伴うときのみ1回確認 (`onSystemBack`の`discardNeeded`)。復元確認のキャンセル (確認不要) と`manual_organization_cancel`の残使用箇所 (dialog dismiss, 復元preview pair) は不変 (#372のpre-send改称scope外)。
- **`MissingAppSelectionScreen.kt`** (−27/+…): 0件notice分岐を削除 (`missing_apps_empty`参照がコードから消滅。count行は常に全体選択数)。選択面confirm CTA (`missing_apps_continue`) とspec 228契約UIは不変。
- **strings**: EN (`values/`) / ja (`values-ja/`) 対称。新規7件 (`preamble_scope`, `preparation`, `interrupt`, `failed`, `discard_confirm_title`, `discard_confirm_text`, `discard`、placeholderなし) + 語彙変更2件 (`manual_organization_start`→「そのまま整理」、`manual_organization_cancel_before_checkpoint`→「Stop before applying」/「適用前に中断」) + 削除1件 (`manual_organization_missing_apps_empty`、双方から削除)。audit自身のreference grep: `manual_organization_missing_apps_empty` の参照は `lawnchair/` + `tests/` で**0件**。`missing_apps_continue`は選択面confirm CTAとして現役使用のため削除対象外 (妥当)。
- **specs 52/228/210**: 下記RUN-AC-08参照。

## RUN-AC-01〜RUN-AC-10 の証拠 (実際に読んだtest class/method)

unit oracleは `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` (以下RunTest) と `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationFaceTest.kt` (FaceTest、新設)。instrumentationは `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` (以下PrefsInstr) と `tests/organizer-instrumentation/app/lawnchair/organizer/ui/MissingAppSelectionInstrumentationTest.kt` (以下MissingInstr)。

- **RUN-AC-01 — met.**
  - D-06継続timing: RunTest.`zeroCandidatesWithoutIntentContinuesThroughSelectingToTheConfirmation` — Unconfined collectorが内部`Selecting(empty)` pass-throughを観測し、最終stateは`Preview`、`composeScopeComposedCalls == 0` (plain直行)、事後の`confirmSelection(emptySet())`は不変 (面が開かなかったことの裏取り)。
  - guard条件 (RD-3): RunTest.`zeroCutWithExportScopeCandidatesStillOpensTheSelectionSurface` — intent-bound×検出0件で`Selecting` (candidates空, intentScopeCount=1) が開き、faceは`SELECTION` (spec 331 mismatch契約の保存)。
  - 最初の可視phaseとT-08復帰 (RD-7順序): RunTest.`preparationPhaseStartsAtDetectionAndNeverReannouncesItAfterTheSelectionSurface` — admission `Capturing`は`DETECTION`を伴い、`Selecting→Capturing`遷移後の`Capturing`はすべて`CAPTURE` (検出の再表示0回)。projection reset: RunTest.`preparationPhaseResetsToDetectionForTheNextRun`。
  - `Unavailable`継続の回帰: RunTest.`detectionUnavailableContinuationAdvancesTheVisiblePhaseWithoutASecondDetection` (既存継続経路がgateを通る。`DETECTION`の再表示なし)。
  - face mapping table: FaceTest.`everyStateMapsToItsSpecifiedFace` — 0件`Selecting` (scope/export候補なし) →`PREPARATION`、0件+`intentScopeCount=2`→`SELECTION`、0件+`scopeRejection`→`SELECTION`、候補あり→`SELECTION`を含む全20状態。
  - instrumentation: PrefsInstr.`preparationFaceExposesHeadlinePhaseAndNoConfirmInterrupt` — blocking detector中に準備中面+検出phase行が表示され、`manual_organization_missing_apps_title`が`assertDoesNotExist` (D-06否定観測)。MissingInstr.`zeroCandidatesContinuesWithoutShowingTheSelectionSurface` — 0件で選択面が構成されず (`title`/`continue`とも`assertDoesNotExist`)、`plainComposeCalls == 1`。
- **RUN-AC-02 — met.** 8状態統合はFaceTestの全state table + PrefsInstr.: T-09が検出→capture→planで同一面構成 (phase行のみ入れ替わり、各phaseでannouncing nodeが1つ: `preparationFaceExposesHeadlinePhaseAndNoConfirmInterrupt` 内の`assertCountEquals(1)` × 3 phase)、T-13構造は`failureFaceExposesHeadlineCauseAndTraversalReachableActions` (見出し+原因`manual_organization_input_not_ready_yet`+再試行+中断)。既存typed原因文言がT-13上に現れることは同一testの`awaitDisplayed`群と、既存test (`reconciliationPendingShowsTryAgainLaterCopy`, `sourceUnavailableShowsBugReportCopyWithRetry`, `staleDetectionBeforeReviewDoesNotClaimReviewedProposalWasDiscarded` 等) のgreenが担保。T-10/T-12変種は既存preview/result系testが無編集系統でgreen (RUN-AC-07)。
- **RUN-AC-03 — met (diff裏取り済み).** `ManualOrganizationRunTest.kt` へのdiffは**纯追加 (削除行0)** をauditが機械確認 — 既存契約oracleは無編集。`ExchangeFlowStateHolderTest`・E2E `staleProductionConfirmationDoesNotWrite` は本PRの変更file setに含まれない (無編集)。CI `organizer-unit-tests` がaudited head上でpass。D-06経路の更新 (`confirmingAnEmptySelectionRunsThePlainFullCompose`系統の置換) はRUN-AC-05のとおりPR本文に記録済み。
- **RUN-AC-04 — met.** T-10提案あり中断の1回確認: PrefsInstr.`preparationFaceExposesHeadlinePhaseAndNoConfirmInterrupt` 後半 (preview表示後に中断→`discard_confirm_title`→破棄→`Cancelled`→T-07 preamble)。選択あり中断の1回確認: MissingInstr.の選択系testが「中断」click→「破棄」clickに更新済み (diff内確認)。T-09の確認なし中断はRD-4どおり`onInterrupt`が直通 (`pendingInterrupt`を経ない) で、terminal面でdialogが出ないことは`failureFaceExposesHeadlineCauseAndTraversalReachableActions`が`discard_confirm_title` `assertDoesNotExist`で固定。checkpoint後不受理は既存coordinator gate oracleの無編集green + UI側`ApplicationInProgress`分岐 (コード確認)。復元確認キャンセルの確認なしは現行語彙のまま (触れていない)。
- **RUN-AC-05 — met.** `MissingInstr.zeroCandidatesShowsTheEmptyNoticeAndStillContinues` → `zeroCandidatesContinuesWithoutShowingTheSelectionSurface` への更新diffを確認 (0件非表示の否定観測+`plainComposeCalls==1`)。obsolete理由 (D-05/D-06適用によるV-09解消・無意味な1 tap廃止) がPR本文「Obsolete oracle記録 (RUN-AC-05)」節に記録済み。`confirmingAnEmptySelectionRunsThePlainFullCompose`の継続timing oracle更新も同節に記録。
- **RUN-AC-06 — met.** PrefsInstr.: phase遷移の1回announce (`LiveRegionMode.Polite`の直接assert × 3 phase + 旧phaseの0 node assert)、focus restoration (見出しへの`assertIsFocused` wait)、DPAD traversal (`pressDownUntilFocused` — 実`KEYCODE_DPAD_DOWN` key stream)、200% reflow (`preparationAndFailureFacesStayReachableAtTwoHundredPercentFontScale` — `fontScale = 2f`で見出し・phase/原因行・全critical actionの`assertHasClickAction`)。screenshot evidence 8枚 (下記)。color-only非依存・no timeout auto-confirmはdialog構造 (AlertDialog, timeoutなし) と既存semanticsに依存 (コード確認)。
- **RUN-AC-07 — met (diff範囲で確認).** spec 210文言 (`stale_outcome`/`stale_proposal_discarded`/`recapture`) は`APPLY_BLOCKED`変種内で文字列参照ごと不変のまま移設。`Preview`/`PreviewUnavailable`/`Applied`/recovery系の既存oracle (`previewRendersScopeReasonWarningAndNoWriteBeforeConfirmation`, `degradedCountOnlyPreviewAnnouncesMissingDetailsAndKeepsConfirm`, `decisionPairStaysDisplayedTogetherAcrossExpansionStates`, `verifiedSuccessSurfaceReportsAppliedOutcomeInPastTense`, `staleApplyAttemptExplainsOutcomeAndNextStep`, `unresolvedResultOffersDiagnosticsInsteadOfStartingAnotherRun` 等) は本diffで無編集または語彙 (`cancel`→`interrupt`)・fixture高さの最小更新のみ。E2E `staleProductionConfirmationDoesNotWrite` 無編集 (CI instrumentation lane待ち、下記)。
- **RUN-AC-08 — met.** specs diff review:
  - spec 52: MFO-AC-01に検出phaseを挿入しD-06継続を注記、Manual-run composition節に「表示は8ユーザー状態へ統合」注記、Change history新設。safe apply契約表・他ACは不変。
  - spec 228: §2の0件規定を「0件でも表示して戻る」→「0件なら選択UIを表示せず続行」へflip、Change historyにAmend記録 (明示選択契約・D-1・AC-2/AC-14不変を明記)。
  - spec 210: 表示面統合の注記節を追加 (`APPLY_BLOCKED`→T-12変種、`DETECTED_BEFORE_REVIEW`→T-13原因。文言契約・outcome構成・E2E不変を明記)、Change history追記。
  - いずれも処分文書§3.3/§3.13のscopeと一致し、safe apply契約・明示選択契約・spec 210文言には触れていない。
- **RUN-AC-09 — met.** 新規7 stringがEN/ja双方に同name・placeholderなしで存在 (diff照合)。削除string (`missing_apps_empty`) のreference grepが0件 (audit実施)。`spotlessCheck`相当のCI `check-style` pass。
- **RUN-AC-10 — met.** RunTest.`cancelDuringDetectionThenDetectorReturnsKeepsTheRunCancelledAndTheJournalEmpty` — blocking fake detector (`detectStarted`/`detectRelease` latch) で検出中に`cancel()`し、detector復帰結果 (a) `Ready(empty)` (b) `Unavailable` (c) `Ready(>0)` の3系すべてで: 最終state `Cancelled`、planner未実行、journal event 0件 (`RUN_STARTED`/`INPUT_NOT_READY`/plan系/apply系不発)、lease `.close()` 1回 (`CountingGate.closeCount == 1`)、projectionは`DETECTION`のまま。対照系 RunTest.`cancelAfterTheComposedGateFollowsRunStarted` — gate通過後cancelで`RUN_STARTED`→`USER_CANCELLED`の順序を固定。T-09 UIの中断rowは`dismiss()`経由だが、cancel/dismissは同一の構造的detach (cancelled flag + `activeOperation = null` + lease一解放 + `Cancelled` publish) を共有し、gateは全進入経路に共通のため被覆は成立 (コード照合)。

## Review trail (実装review 4回、すべてissue 369コメント)

1. [5748643146](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5748643146) **Changes requested** (2026-09-20T08:21Z, head `5b138bd33d8`) → 対応commit `7bff88466a` (face mapping描画接続・Stale origin対応・T-09/T-13 evidence)
2. [5749752250](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5749752250) **Changes requested** (2026-09-20T12:19Z, head `7bff88466a`) → 対応commit `acc70486e3` (T-09 RUN-AC-06 evidence強化)
3. [5751115495](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5751115495) **Changes requested** (2026-09-20T16:34Z, head `acc70486e3`) → 対応commit `33c56371ef` (PLAN phase oracle・4条件screenshot evidence)
4. [5751189926](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5751189926) **Accepted** (2026-09-20T16:47Z, head `33c56371ef`) — blocking 0件。comment本文がaudited head SHAと8枚のscreenshot/blob追跡可能性を明示的に確認している。

## Screenshot evidence (8枚、すべて実在・有効PNG 1080×2400を`file`で確認し、2枚を目視検証)

`docs/assessment/assets-369-run-display-integration/`:

- `t09-preparation-light-default.png` — T-09準備中面 (見出し「Preparing the organization」+ 検出phase行「Checking for apps that are not on the Home screen…」+「Stop」)。目視確認済み。
- `t09-preparation-light-ja.png` / `t09-preparation-dark-default.png` / `t09-preparation-dark-ja.png`
- `t13-failure-light-ja.png` — T-13統合失敗面 (見出し「実行できませんでした」+ spec 172待機copy + 再試行/中断、診断rowなし = copy split)。目視確認済み。
- `t13-failure-light-default.png` / `t13-failure-dark-default.png` / `t13-failure-dark-ja.png`

生成経路はPrefsInstr.`capturesIntegratedFacesAcrossDisplayConditions` (4条件ループ) であり、ファイル命名と一致。

## CI status at audit time

`gh pr checks 387` / run [35524033721] (head `33c56371ef`, `in_progress`) のsnapshot:

- **pass**: `changes`, `high-risk-evidence`, `validate-repo-contract`, `check-style`, `build-debug-apk`, `organizer-unit-tests`
- **pending (in progress)**: `organizer-instrumentation-api35-tests`, `-db-migration-tests`, `-issue155-tests`, `-issue299-tests`, `-issue332-tests`, `-issue52-tests`, `-issue53-tests`, `-issue99-tests`, `-shared-writer-tests` (9 lane)
- **`final-status`: 未green。** merge precondition (`final-status` green) はaudit時点で未充足であり、merge前に別途確認すること (本監査はこれを代替しない)。E2E `staleProductionConfirmationDoesNotWrite` を含むinstrumentation表面のCI上のgreenはこのpending lane群で確定する。

## Findings / reservations

判定に影響しないminor事項:

1. **T-13 oracleの「中断click後もstate不変」主張はfocusまで** (`failureFaceExposesHeadlineCauseAndTraversalReachableActions`): traversalで中断rowまで到達するがclickはせず、`discard_confirm_title` 不存在assertはclickなしでも自明に成立する。terminal面の中断がdialogなしで通ることはコード構造 (`onInterrupt`直通、`dismiss()`→`NoActiveOperation`) から成立するが、oracleとしてはclick後のstate不変まで直接固定していない (将来の強化候補)。
2. **T-09無確認中断 (RD-4) はUI clickでは直接駆動されない**: 同testのT-09段では中断rowをfocusのみし、実際の中断clickは提案存在時 (1回確認) で駆動される。RD-4の無確認挙動は構造 (`onInterrupt`が確認gateを経ない) とunit契約で担保されており不成立リスクは低いが、UI click oracleとしては提案側のみである。
3. **RUN-AC-10 oracleはcoordinator `cancel()`経由**でT-09 UI経路 (`dismiss()`) は同一detach構造を共有するだけの間接被覆 (前述)。挙動は同一だが、UI経由のcancel競合を直接駆動するoracleではない。
4. **CI `final-status` 未確答** (上記)。merge判定はrun完了後の`final-status` greenを以って別途行うこと。PR本文の「同一検証を再実行しgreen」のうちorganizer instrumentation lane部分は実装者environmentの記録であり、本監査はCI unit gate (`organizer-unit-tests` pass) とdiff静的検証で裏取りした。instrumentationのCI裏取りはpending lane完了時点で成立する。
5. spec `status: accepted → implemented` への移行と `organization-run-ux.md` §8参照更新はplanのDocumentation updatesに従い**本PR後の工程**である (本PR内では実施されておらず、planどおり実装PRのscope外運用。merge後の移行を追跡すること)。

## Verdict

**audit pass。** accepted spec RUN-AC-01〜RUN-AC-10は、上記の実際に確認したtest class/method・diff・evidenceによって裏付けられている。RD-3 (state machine不変+内部継続)、RD-6 (cancel gate atomic化)、RD-7 (決定的face mapping+phase-before-state projection) の実装はspec/planの契約どおりである。spec 52/228/210のAmendは処分scope内。実装review trailは4回で最終Accepted (blocking 0)。reservationsはすべてminorであり、merge前に `final-status` greenの確認 (別手続) のみを条件とする。
