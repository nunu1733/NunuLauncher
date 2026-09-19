# Implementation Plan: run面の表示統合・canonical順序固定・D-06条件表示・D-13語彙規約

> Issue: #369
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-19時点のmain（`3076bdae7ebf8dbb086f251203968c06e9986258`）での確認である。

### 現行のrun coordinator（`ManualOrganizationRun.kt`）

`lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（1345行）:

- sealed interface `State` は正確に20状態: `Idle`, `Capturing`, `CandidateDetection`,
  `Selecting`, `ScopeMismatchFailed`, `Planning`, `InputUnavailable`,
  `CandidateResolutionFailed`, `PlanningRejected`, `NoChanges`, `Preview`,
  `PreviewUnavailable`, `Applying`, `Stale(origin)`, `Applied`, `Cancelled`,
  `InspectingRecovery`, `RecoveryPreview`, `Recovering`, `RecoveryResultState`。
- `start(trigger)` / `start(trigger, intent)`（L377-429）: `beginOperation`でRUN lease取得
  （`OrganizationOperationLease.Kind.RUN`）→ `State.Capturing` → `detectMissingAppCandidates()`
  → `Ready`なら**常に**`State.Selecting`（0件でも。L407-420）→ UIの`confirmSelection`待ち。
  `Unavailable`なら選択面を開かず`runComposedPhase(operation, selection = null)`（spec 228 §7）。
- `confirmSelection`（L438-482）: `State.Selecting`でのみ受理。empty selectionはplain full
  compose、非emptyはscope-composed。intent-bound時のearly scope equality gate
  （SET_MISMATCH→選択面再表示）を内包。
- `runComposedPhase`（L570-732）: RUN_STARTED event → `State.Capturing` → composition →
  scope binding digest gate（intent-bound時。mismatchは選択面復帰または
  `ScopeMismatchFailed`終端）→ `CAPTURED` → `State.Planning` → plan → `PlanningProjection` →
  Planned/Rejected/Impossible分岐 → `handlePlanPreview`。
- `handlePlanPreview`（L742-796）: `PlanPreviewResult.Stale` →
  `transitionToStale(origin = DETECTED_BEFORE_REVIEW)`。`Previewed` → `State.Preview`。
  Add-runの環境失敗 → `State.PreviewUnavailable`（spec 228 AC-14）。
- `confirm()`（L849-922）: materialize（previewed planなら直接）→ candidate resolution失敗は
  typed再検出終端 → `State.Applying` → `applicationAdmitted.set(true)` → apply →
  `STALE_REVISION`/`EXACT_PRECONDITION_FAILED`は`State.Stale(APPLY_BLOCKED)`、他は
  `State.Applied`。
- `cancel()`（L816-847）: `applicationAdmitted`済みなら不受理（L819）。
  `dismiss()`（L1013-1058）: `ApplicationInProgress`を返してBack不逮捕（UI側で無視）。
  いずれも確認dialogはUIに存在しない。
- journal: 検出/選択windowはeventなし（`journalStarted` flag、L836-846のcomment）、
  RUN_STARTEDはcomposed phase開始時にmode確定後に発行。`USER_CANCELLED`は
  journal開始後のみ。

### 現行のrun面UI（`ManualOrganizationPreferences.kt`）

`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
（1751行）。`PreferenceLazyColumn`の`when (state)`分岐で1状態1画面群:

- Idle/Cancelled（L331-373）: checking行＋durable status行群（spec 271）＋開始row
  （`manual_organization_start`、import attempt中freeze・spec 328）。
- `Capturing`/`CandidateDetection`/`Planning`（L375-391, L447-453）: 各1行の`ProgressText`
  （liveRegion Polite）。中断rowなし。3状態で面が分かれている。
- `Selecting`（L393-445）: `missingAppSelectionItems`＋SCOPE_MISMATCH rejection行＋
  `exchangeFlowItems`（run-in entry）。
- 失敗系（L455-524）: `InputUnavailable`/`ScopeMismatchFailed`/`CandidateResolutionFailed`/
  `PlanningRejected`がそれぞれ独立の`FocusTargetText`＋`manual_organization_retry` row。
- `NoChanges`（L526-536）/ `Preview`（L538-583）/ `PreviewUnavailable`（L585-614）/
  `Applying`（L616-626、「Cancel before applying」row）/ `Stale`（L628-660、spec 210の
  3要素構成）/ `Applied`（L662-714、完了形件数＋復元＋safe terminal診断）。
- 復元系（L716-802）: `InspectingRecovery`/`RecoveryPreview`（spec 230 history行）/
  `Recovering`/`RecoveryResultState`。
- `ManualOrganizationBackHandler`（L1002-1038）: Back → `coordinator.dismiss()` →
  `CancelledAndMayNavigate`ならそのままback（**確認dialogなし**）。
  `ApplicationInProgress`なら不受理。
- focus復帰機構（`focusTargetIndex`/L199-206、`LaunchedEffect`/L237-255）と
  spec 195の`previewDetailsItems`（L1327-1429）、spec 209のdecision pair
  （`PreviewDecisionActions`/`DecisionActionsRow`）。

### 現行の接続経路

- `PreferenceRoutes.kt` L119-133: `OrganizationEntry.MANUAL/ONBOARDING` →
  `HomeScreenManualOrganization(entry)` → `Trigger.MANUAL_FULL`/`ONBOARDING_PROPOSAL`。
- `PreferenceNavigation.kt` L120-127: destination組立（`ManualOrganizationPreferences`）。
- `OrganizationOnboardingProposal.kt` L214-218, L269-271: onboarding「確認」は
  floating viewから直接`ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)`
  を呼び（**admission直行。D-16と既に整合**）、run面へ遷移する。T-07前置きは経ない。
- hub（#366 draft）: hub CTA → 既存run destination（暫定）。#369でT-07前置き面経由に置換
  される面を持つのはrun destinationのIdle/Cancelled分岐である。

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

- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`（1100行超）:
  既定のfake detectionは`Unavailable`（= 選択面を経ない現行経路に依存するtestが多い）。
  `detected(...)`で候補あり経路、L1292 `confirmingAnEmptySelectionRunsThePlainFullCompose`が
  `detection = detected()`（0件 → Selecting → `confirmSelection(emptySet())`）を固定 —
  **D-06適用で期待値更新が必要な唯一系統**。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/MissingAppSelectionInstrumentationTest.kt`
  L226-240 `zeroCandidatesShowsTheEmptyNoticeAndStillContinues` — 0件表示oracle
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
（`ManualOrganizationRun.kt`）への変更はD-06の0件skip 1箇所に限定する。これは
DESIGN.md §4.4の「UI adapter: ...確認、結果、復旧を表示する」の所有境界に沿い、
planning/application/protocol moduleには触れない。

**面コンポーネントの分離**: `when (state)`分岐を面単位のprivate composable群へ整理する
（`PreparationFace`（T-09）、`IntegratedFailureFace`（T-13）、`ResultFace`（T-12）等）。
各面は`State`の部分集合を引数に受け、既存の`FocusTargetText`/`ProgressText`/
`SummaryText`/`DecisionActionsRow`/`summaryItems`/`appliedResultItems`/
`previewDetailsItems`等のhelperを再利用する。新規の状態公開・seamは作らない
（interface追加は「production用とtest用の実体が必要になるまで」作らない規約に従い最小化）。

**確認dialog**: 中断/Back確認用の共通composable（1箇所）をUI層に追加する。
表示条件の判定はobservable stateから導出する（`Selecting`の選択非空、`Preview`/
`PreviewUnavailable`のplan存在、等）。coordinator APIの変更は不要である
（confirm応答後の`cancel()`/`dismiss()`は既存契約どおり）。

**D-06 skip（coordinator・唯一の遷移変更）**: `start()`の検出`Ready`分岐（L407-420）を
次のようにする:

```kotlin
is CandidateDetectionResult.Ready -> {
    operation.detectedCandidates = detection.candidates
    val exportedScopeCandidates = operation.intent?.session?.scopeCandidates
    if (detection.candidates.isEmpty() && exportedScopeCandidates.isNullOrEmpty()) {
        runComposedPhase(operation, selection = null)   // D-06: 選択面不進入
    } else {
        setIfActive(operation, State.Selecting(runId, detection.candidates, ...))
    }
}
```

guard条件により、intent-bound（export scope候補あり）×検出0件では選択面が開き、
spec 331のmismatch表示契約が保存される。`Selecting`への進入条件以外の遷移・lock構造・
journal規則は変更しない。

### Data flow

表示のdata flowは変わらない（`stateFlow` → compose）。変化するのはstate → 面の写像のみ
（spec対応表）。T-09のphase行は`State`の種別（`CandidateDetection`/`Capturing`/
`Planning`）から決定的に導出され、新規の状態保持を持たない。T-13の原因文言は既存の
typed結果→string mapping（`InputReadinessReason.copyKind()`、
`manual_organization_candidate_unresolved`、`manual_organization_rejected`/
`impossible`＋summaryItems、`exchangeContractFailureText`、spec 210の
`stale_proposal_not_reviewed`）をそのまま再利用する。

### Alternatives rejected

- **0件時のUI主導auto-confirm**（UIが0件`Selecting`を観測して`confirmSelection(emptySet())`
  を発行）: 明示選択契約（spec 228 D-1）のユーザーactionを偽装し、state/表示の瞬間不整合を
  生むため不採用（spec Contract notes 3）。
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
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | `start()`の検出Ready分岐にD-06 0件skip（intent-aware guard付き）を追加。javadoc追記 | 選択面不進入の遷移判定点はcoordinatorのみが持つ。他の遷移は不変 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | Idle/Cancelled分岐をT-07前置き面化（方法選択CTA「そのまま整理」＋scope要約。durable status行・exchange idle entry・import freeze affordanceは現行維持）。`Capturing`/`CandidateDetection`/`Planning`をT-09統合progress面（phase行＋中断row）へ統合。失敗系5状態をT-13統合面（見出し「実行できませんでした」＋原因＋再試行/中断（＋該当時診断））へ統合。`NoChanges`/`Stale(APPLY_BLOCKED)`をT-12結果面の変種へ統合。確認dialog追加とBack handlerへの組込み、cancel/中断labelのD-13語彙化 | 表示統合の全変更が収束する唯一のrun面。helper群は再利用 |
| `lawnchair/src/app/lawnchair/organizer/ui/MissingAppSelectionScreen.kt` | 0件notice経路の削除（`missing_apps_empty`表示分岐。選択面本体・spec 228契約UIは不変） | 0件選択面が到達不能になるため。mismatch再表示経路（候補0件＋scopeRejection）の表示は維持 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 新規: T-07方法選択・scope要約、T-09見出し・phase・中断、T-13見出し、確認dialog（破棄/中断）文案等。変更: `manual_organization_cancel_before_checkpoint`系の中断語彙化。削除: 未使用化string（`manual_organization_missing_apps_empty`等。reference grepで確定） | spec 123契約（EN/ja・format resource） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` | D-06経路の期待値更新（`confirmingAnEmptySelectionRunsThePlainFullCompose`等の0件経路を「Selecting不進入」に）。guard条件の新test（intent-bound×検出0件で選択面が開く） | spec Contract notes 3。D-06対象経路以外は無編集 |
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
| RUN-AC-01 | unit: D-06 skip（0件＋intent未bound → plain compose直行・`Selecting`不進入）、guard（intent-bound×検出0件 → 選択面）、検出`Unavailable`継続の既存test green。instrumentation: T-07→そのまま整理→T-09検出→0件続行、0件で選択面要素の否定的観測 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane |
| RUN-AC-02 | instrumentation: T-09が検出/capture/planで同一面（phase行更新）、T-13見出し＋原因＋手段の構造、T-12変種表示 | 同上 |
| RUN-AC-03 | `ManualOrganizationRunTest`/`ExchangeFlowStateHolderTest`等のD-06対象外無編集green + E2E `staleProductionConfirmationDoesNotWrite` green | 同上 + connected test lane |
| RUN-AC-04 | instrumentation: 中断/Back確認dialog（提案・選択ありで1回）、復元確認cancelの確認なし、Applying checkpoint前の確認付き中断、checkpoint後不受理（既存gate oracle継続） | 同上 |
| RUN-AC-05 | 旧oracle更新diff + obsolete理由のPR本文記録 | PR review |
| RUN-AC-06 | Compose semantics（announce回数、traversal、name/role/state）+ focus restoration + 200% font scale + light/dark × ja/default screenshot（emulator） | organizer instrumentation lane + emulator evidence |
| RUN-AC-07 | 既存preview/confirmation/result/recovery oracleのgreen（面移設のみの更新）+ spec 210文言assertの継続 | 同上 |
| RUN-AC-08 | specs 52/228/210のdiff review | PR review |
| RUN-AC-09 | 新規stringのEN/ja name集合・placeholder一致の機械確認 + 削除string reference grep（0件）+ hardcoded literal grep | `./gradlew spotlessCheck` + grep |

含めるべき観点: unit/contract（D-06・cancel/dismiss契約）、UI/accessibility（§6受入基準）、
failure injection（既存fakeのinspectPlan/apply注入経路でT-13/T-12の各原因を駆動）、
回帰（exchange・strategy・durable statusの非依存test）、E2E（stale zero-write）。

## Documentation updates

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
- [ ] D-06 skipのunit testを先に追加し、現行実装でfailすることを確認。
- [ ] coordinatorのD-06 skip（guard付き）を実装しunit test green。
- [ ] T-07/T-09/T-13/T-12の面統合とD-13語彙・確認dialogを実装し、既存testを更新。
- [ ] a11y evidence（announce/focus/200%/screenshot）を収集。
- [ ] specs 52/228/210を同じPRで改訂し、RUN-AC-08のdiff reviewを可能にする。
- [ ] obsolete oracleの理由記録をPR本文へ記載（RUN-AC-05）。
- [ ] Full relevant verification（unit gate + instrumentation lane + spotlessCheck +
      CI `final-status`）を完了し、結果をPRへ記録。
