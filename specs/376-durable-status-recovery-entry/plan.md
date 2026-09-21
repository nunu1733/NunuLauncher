# Implementation Plan: hub status cardから復元flowへの接続（D-15）

> Spec: [spec.md](./spec.md)（**accepted**。2026-09-22、Phase1 re-entry 4ラウンドreviewの
> 最終 [Approve](https://github.com/nunu1733/NunuLauncher/issues/376#issuecomment-5764528122)
> を経てowner受入）。実装着手可能。
> 本planは main `c05435a947`（2026-09-22時点。PR #379（#365）・#380（#366）・#387（#369）・
> #391（#371）・#393（#372）・#396（#373）merge後）の実装調査に基づく。実装開始時に最新
> `origin/main`・Issueコメントを再確認し、差分を反映してから着手する。

## 1. Current implementation（調査済みの事実、現行main @ `c05435a947`）

### 復元経路（既存。本planでは変更しない部分）

- **application seam**: `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt`
  （578行。2026-09-19以降の変更なし）
  - `inspectRecovery(pointId)`（289–302行）: spec 84のread-only検査。readiness gateでfail-closedし、
    `RecoveryPreviewProtocol.inspect(pointId)`（I0–I5。run mutex非block → 検査projection読取 →
    checksum/format/lifecycle/retention preflight → writer lease非block取得 → 現state capture →
    `Restorable` + one-shot `RecoveryPreviewConfirmation` 発行）へ委譲する。
  - `confirmRecoveryPreview(pointId, confirmation)`（354–361行, internal）:
    `consumePreviewConfirmation` でone-shot registry（**`pendingPreviewConfirmations`、
    module instance fieldの `java.util.IdentityHashMap`。84–85行で宣言。363–381行で
    `@Synchronized` 発行/消費**）を1回消費して内部で `RecoveryRequest` を組み立て、
    `recoverWithApplicationBehavior`（既存のreadiness gate + `RECOVERY_REQUESTED` /
    terminal recovery diagnostics + `RecoveryProtocol.recover`）へ委譲する。公開契約は
    `RecoveryResult` のみ。**registryの所有者はmodule instanceであり、coordinatorの再構築では
    消えない**（spec D4/RS-AC-03の構造的根拠）。
  - `durableOrganizerStatus()`（315–347行）: readiness gate + `ordinaryMutex.tryAcquire` 非block +
    `store.readInspectionSnapshot()` → 純粋な `OrganizerDurableStatusDeriver.derive(...)`。
    fail-closedは `UNAVAILABLE`。
- **検査snapshot**: `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot.kt`
  — `Record(pointId, lifecycle, createdAtMs, updatedAtMs, checksumValid, formatVersion)` /
  `Tombstone(pointId, reason, expiresAtMs)`。SQLite非open・書込みなし（spec 89）。
- **純粋派生**: `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt`
  （101行）— 有効な `VERIFIED` が1件でもretention内にあれば `ORGANIZED_RESTORABLE`（66–71行の
  `any` 判定。**複数点でもrestorableになる**）。優先順は `UNRESOLVED` > `ORGANIZED_RESTORABLE` >
  `RESTORED_OR_EXPIRED`（75–81行）> tombstone派生（84–98行）> `NEVER_ORGANIZED`（99行）。
  retention定数は同packageの `RetentionPolicy.RETENTION_MILLIS`（`RetentionPolicy.kt` 24行、24h、
  spec 13）。
- **coordinator**: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（1642行）。
  2026-09-19以降の変更は#368/#369/#371のみで、**recovery機構（recovery系state・lease・
  `lastVerifiedApply`/`appliedPoint`の扱い）は baseline `a2b6aba318` から無変更**。
  - `ManualOrganizationApplication` façade（71–116行）: `inspectRecovery`（103行）/ `confirmRecovery`
    （104行）/ `readDurableOrganizerStatus()`（107行、実装154行）/ `readinessState`（115行）を
    moduleへ委譲。
  - `ManualOrganizationModule.get()`（161–194行）: cold-process初期化
    （`LauncherAppState.getInstance` + `app.ensureOrganizerStartupReconciliation()`。spec 271
    DS-AC-10のno-callback loader橋を含む。#371の `UsageAccessJitGateProvider.get(app)` が185行に追加済み）。
    [LawnchairApp.kt][la] の `ensureOrganizerStartupReconciliation()` が正体。
  - `beginRecoveryPreview()`（1201–1252行）: **旧entry**。`operationGate.tryAcquire(RECOVERY)`
    （1202行）→ lock下で `lastVerifiedApply != null && appliedPoint != null && activeOperation == null
    && recoveryLease == null` を検査（1203–1214行）→ `State.InspectingRecovery` → lock外で
    `application.inspectRecovery(point)`（1221行。throw時 `cancelRecoveryPreview()`、1222–1224行）→
    lock下で相関gate（spec 230 D2実装。`lastVerifiedApply.result` が `ApplyResult.Applied` で
    pointId等価のときだけsummaryを渡す、1230–1240行）→ `State.RecoveryPreview(preview, correlated)`。
  - `cancelRecoveryPreview()`（1254–1262行）: `stateHolder.value = lastVerifiedApply ?: State.Idle`
    へ復帰（**origin非依存の戻り先。spec D5が束縛し直す対象**）、zero-write。
  - `confirmRecovery()`（1264–1283行）: `State.Recovering` → `application.confirmRecovery(...)` →
    `State.RecoveryResultState(result)`。throw時 `cancelRecoveryPreview()`（1274行）。
  - `dismiss()`（1296–1347行）: recovery lease保持中は `pendingRecovery = null` +
    `lastVerifiedApply ?: State.Idle` へ復帰して `DismissalOutcome.CancelledAndMayNavigate`
    （1296–1310行）。active run中は `State.Cancelled`（1311–1326行）。
    **`RecoveryResultState`（lease解放済み・operation無し）は `NoActiveOperation` で
    state残留** — status card起点の復元成功後にhubへ戻ってもdurable statusが再deriveされない
    原因である。このgapは `dismiss()` を変更せず、**system Backに束縛された明示的新操作
    （spec D5 `leaveRecoveryResultToHub()` 仮称）で埋める**（host cleanup・診断pushでの
    state保持は既存の寿命契約として維持）。
  - `lastVerifiedApply` の生存: 適用成功時に設定（confirm()、1188–1191行。同時に `appliedPoint` も設定）、
    **新しいrunの開始（`beginOperation()`、1406–1407行）でのみ消去**。cancelでは消去されない。
    **`lastVerifiedApply != null` のあいだ表示stateは `Idle`/`Cancelled` になり得ない**
    （全state遷移の確認による構造的排他。spec D5の不変条件）。
  - state列（265–401行）: `State.RecoveryPreview(result, appliedSummary: Summary? = null)`（359行）、
    `State.InspectingRecovery`（349行）、`State.Recovering`（361行）、`State.RecoveryResultState`
    （362行）。#371追加の `AwaitingUsageAccessJit`/`ResumingUsageAccessJit`（381/397行）はrecoveryと非干渉。
- **admission**: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOperationLease.kt` —
  `Kind.RUN / RECOVERY / AUTHORING` の単一flight process-local gate。
- **writer lease**: `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  — `LayoutWriteCoordinator.getInstance().tryAcquire(OwnerKind.ORGANIZER)` へのbridge。
  spec 84 I4 / spec 13 recovery protocolが取得する（無変更）。
- **hub（#366実装済み、PR #380）**: `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`
  （242行）。route `HomeScreenOrganizer`（`PreferenceRoutes.kt` 117–118行、navigation接続
  `PreferenceNavigation.kt` 138行）。`showDurableStatus = state is Idle || state is Cancelled`
  （87行）+ `LaunchedEffect(showDurableStatus, readinessState)` 再読込（90–96行、DS-AC-09）+
  checking行（97–106行、`LiveRegionMode.Polite`、232–242行）。
  `hubDurableStatusItems`（197–220行）は `ORGANIZED_RESTORABLE` を `HubStatusLine`（plain Text、
  222–229行、string resource `manual_organization_durable_status_restorable` のみ）で表示し、
  **CTA・残時間なし**。読み順規約コメント（126–128行、TO-BE §13-5「状態→操作」、残期限は
  #374/#376が挿入）。hubはrun面へは `NavigationActionPreference` で遷移するのみで `start()` を
  直接発行しない（139–150行）。
- **settings側run面/確認面**: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  （1900行。#369/#371/#372/#373で成長したがrecovery UI構造は維持）。
  - durable status読取block（180–196行。hubと同一の `showDurableStatus`/`LaunchedEffect`/
    `showCheckingRow` 様式）、`durableStatusItems`（1072行付近。`ORGANIZED_RESTORABLE` →
    `SummaryText` のみ）、`durableStatusItemCount`（1110行付近）。**本Issueでこのfileの
    durable行は変更しない（表示のみ維持。spec D6/Non-goals）**。
  - Back/dismissの呼出しsite: `onSystemBack` → `interruptAndNavigate` → `coordinator.dismiss()`
    （269–280行、Back callback 303–315行）、host離脱の `DisposableEffect` `onDispose`
    （316–318行）。現行はResultStateで `NoActiveOperation`・state残留のためresult面は
    診断Back後も維持される。**結果面からのhub帰還はこの既存経路には載せず、明示的な
    新操作（spec D5 `leaveRecoveryResultToHub()` 仮称）をsystem Back経路のみに追加する**
    （`onDispose` は現行どおり）。
  - 旧entryの復元action（`State.Applied` 面、850–863行。`beginRecoveryPreview` 呼出し856行）、
    `State.InspectingRecovery`（889–895行）、`State.RecoveryPreview`（897–934行。
    `recoveryHistoryLine` 912行、confirm/cancel decision pair 916–933行 — `confirmRecovery` 920行 /
    `cancelRecoveryPreview` 927行）、`State.Recovering`（936–942行）、`State.RecoveryResultState`
    （944–975行。結果文言 + **safe-support時は「診断を開く」（`onOpenDiagnostics` で
    `HomeScreenOrganizerDiagnostics` へpush。961–966行）/ それ以外は「もう一度開始」**）。
    helpers: `recoveryHistoryLine`（1856–1867行）、`recoveryPreviewMessage`（1869–1882行）、
    `recoveryResultMessage`（1884行〜）。**確認面hostは#366/#369後もこのfileであり、
    status card起点のflowもこの既存の面を共有する**。
    **診断pushでもrun面compositionはdisposeされる**（navigation push）ため、
    `onDispose { coordinator.dismiss() }`（316–318行）が呼ばれる — 現行はResultStateで
    `NoActiveOperation`・state残留のためresult面は診断Back後も維持される。この既存の
    寿命契約を維持することが、結果面のhub帰還を汎用 `dismiss()` に畳み込まない理由である
    （spec D5。hub帰還は明示的な新操作に分離）。
- **strings**: `lawnchair/res/values/strings.xml` — hub 1009–1012行、recovery系 1115–1135行、
  durable系 1136–1139行（`manual_organization_durable_status_restorable` 1136行）。
  `values-ja/strings.xml` — hub 31–34行、recovery系 174–191行（plurals 178/181/184行）、
  durable系 192–195行。**復元CTA label・残時間表示のstringは未存在（本Issueで新設）**。

### テストの現状

- unit: `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`（**77 @Test**）。
  restart oracle: `freshRunInstanceDoesNotReachRecoveryPreview()`（955–971行）、
  `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface()`（846–865行）、
  `recoveryPreviewCarriesTheCorrelatedApplyHistory`（894–909行）、
  `recoveryPreviewWithMismatchedPointIdOmitsApplyHistoryButKeepsConfirmationUsable`（911–927行）、
  `recoveryPreviewFollowsTheLatestVerifiedApply`（929–953行）、`nonRestorablePreviewCarriesNoApplyHistory`
  （973–985行）。
  `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt`（16 @Test）、
  `tests/unit/app/lawnchair/organizer/application/contract/RecoveryPreviewContractTest.kt`（4 @Test）、
  `tests/unit/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriverTest.kt`
  （16 @Test。同dirに `RetentionPolicyTest`/`LifecycleReconcilerTest`）。
- instrumentation（`tests/organizer-instrumentation/`）:
  - `app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` —
    `durableRestorableStatusRendersWhileIdle`（207行）、`durableStatusIsHiddenWhileARunIsActive`
    （452行）、`recoverySurfacesRenderDecisionButtons`（1518行）、
    `verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel`（2340行）ほか。
  - `app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt` —
    `manualRunUsesProductionCaptureApplyVerificationAndRecovery`（169行）、
    `recoveryConfirmationExplainsTargetAndRestoresPreStateAfterExternalChange`（280行）。
  - `app/lawnchair/organizer/ui/OrganizerHubPreferencesInstrumentationTest.kt`（#366）—
    `hubRendersStatusCardAndMaterialsAtIdle`（167行）、`hubHidesStatusRowsWhileRunIsActiveAndReshowsAfterCancel`
    （359行）、**`hubExposesNoRestoreOrRunResultAffordancesAndStartsNothing`（403行。復元CTAなしの
    否定的観測。本Issueで復元CTA部分を更新対象）**、`hubStatusRowsPrecedeTheActionsInReadingOrder`
    （489行）、`hubStatusCardStaysReachableAtTwoHundredPercentFontScale`（514行）、
    `hubRowsExposeNameRoleAndStateToAssistiveTechnology`（589行）、
    `hubFocusRestoresToStartAfterBackFromRunSurface`（655行）。
  - `app/lawnchair/organizer/application/store/OrganizerDurableStatusInstrumentationTest.kt` —
    `verifiedPointIsRestorableAfterProcessDeath`（128行）ほかのprocess-death系。
    **このclassは `.github/workflows/` のどのlane class listにも含まれない**（spec 271 DS-AC-10の
    evidenceはPR時の手元emulator実行）。cold-process evidenceの実行計画は§8に従う。
- CI lane: `organizer-instrumentation-issue52-tests`（`.github/workflows/ci.yml` 432–470行）が
  上記UI class群（hub含む）を実行。unit gateは `organizer-unit-tests` job
  （`--tests 'app.lawnchair.organizer.*'` ほか3filter。ci.yml 186行）。

### Gap（本Issueで埋める）

1. `ORGANIZED_RESTORABLE` 行（hub status card。D-15の契約上のentry面）に操作・残時間がない
   （settings側も含め表示のみ）。
2. coordinatorに `appliedPoint` / `lastVerifiedApply` 非依存の復元開始entryがなく、
   戻り先が `lastVerifiedApply ?: Idle` にorigin非依存に結合している。
3. application moduleに「最新の検証済み1点」の選択readがない（snapshotには必要なmetadataが既にある）。
4. spec 271がcold-process restoreをNon-goalsとしており、DS-AC-07の表示面が旧配置のままである。
   spec 366 HUB-AC-03の否定的観測が復元CTAの追加と衝突する（companion revisionで解消）。

## 2. Ownership / module boundaries

- **application module**（`organizer/application/**`）: 選択readと閉じたentry型を所有する
  （DESIGN.md §4.2のmodule所有規約。snapshot・retention・revisionは漏らさない）。
  変更は `LayoutApplicationModule` へのadditive操作と、`application/public/` への閉じた型追加、
  純粋selectorの新規配置に限定する。token registryの所有は既有のままmodule instanceである
  （変更しない）。
- **coordinator**（`organizer/ui/ManualOrganizationRun.kt`）: cold entry・entry origin・state遷移・
  戻り先契約（preview cancel と 結果面からの明示的hub帰還の両方）を所有。既存state列・lease規約を
  再利用し、新しいstate種別・新しいapplication検査操作を追加しない。
- **UI**: hub status card（`OrganizerHubPreferences.kt`、契約上のentry面）にCTA・残時間の描画と
  navigationを実装する。settings側run面（`ManualOrganizationPreferences.kt`）は
  durable行・確認面・`onDispose` を無変更とし、**結果面のsystem Back経路に明示的hub帰還操作
  （`leaveRecoveryResultToHub()` 仮称）の呼出し1箇所のみ追加する**（spec D5）。
  選択・検査の判断をUIへ置かない。
- **recovery protocol / store**: 無変更。spec 13/84/89の契約面にdiffを出さない。

## 3. Interfaces / seams

新設する公開面は **1つの読み取り操作 + 2つの閉じた型** のみ（仮称。最終名は実装PRで確定）:

```kotlin
// application/public/RestorableRecoveryEntry.kt（新規）
sealed interface RemainingWindow {
    data class HoursRemaining(val value: Int) : RemainingWindow   // 1..24、切捨て
    data object LessThanOneHour : RemainingWindow
}
data class RestorableRecoveryEntry(
    val pointId: RecoveryPointId,        // opaque handle。描画・log・永続化しない
    val remainingWindow: RemainingWindow,
)

// LayoutApplicationModule（additive）
fun readRestorableRecoveryEntry(): RestorableRecoveryEntry?
```

- `null` の意味: retention内の有効な検証済みpointが存在しない、またはfail-closed
  （gate未ready / snapshot unreadable / mutex競合 / 読取失敗）。UIはstatus行の表示にのみ使う
  値を持ち、`null` ではCTAを出さない（spec D6）。
- 実装は `durableOrganizerStatus()` と同一の構造を踏襲する:
  `readinessGate.runWhenReady(unavailable = { null })` → `ordinaryMutex.tryAcquire`（非block、
  失敗でnull）→ `store.readInspectionSnapshot()` → 純粋selectorへ委譲 → `finally` でrelease。
  書込み・lifecycle遷移・cleanup・journal eventは発生しない。
- coordinator façade（`ManualOrganizationApplication`）には `readRestorableRecoveryEntry()` の
  委譲を追加する。`inspectRecovery` / `confirmRecovery` は既存のまま流用する。
- **coordinator cold entry（仮称 `beginRecoveryPreviewFromDurableEntry()`）の契約**:
  1. `operationGate.tryAcquire(RECOVERY)`（競合は静かに不受理）。
  2. lock下で `activeOperation == null && recoveryLease == null` かつ **表示stateが `Idle`/
     `Cancelled`** を要求（不正ならleaseをcloseして静かに不受理）。admission時に
     `readRestorableRecoveryEntry()` を読み、nullなら不受理。
  3. entry origin（仮称 `recoveryEntryOrigin`）を `HubStatusCard` として記録し、
     `State.InspectingRecovery` へ遷移、lock外で `application.inspectRecovery(selectedPointId)`
     （既存spec 84検査の再利用）。throw時はorigin契約どおりのcancelで戻す。
  4. `State.RecoveryPreview(result, correlated=null)` へ載せる（D5の構造的排他によりcorrelatedは常にnull。
     既存の相関gate計算式を流用し、新規の表示分岐を作らない）。
  5. `cancelRecoveryPreview()` とdismiss中のrecovery取消（`recoveryLease != null`）はoriginを
     参照し、status card originでは **pre-entryの表示状態（`Idle`/`Cancelled`）** へ復帰する
     （旧entryは現行どおり `lastVerifiedApply ?: State.Idle`）。
  6. **結果面からのhub帰還は明示的操作に分離**: `dismiss()` は **現行mainから無変更**
     （host cleanup・診断push等の非明示的離脱ではterminal stateを変更しない）。
     新操作（仮称 `leaveRecoveryResultToHub()`）を追加し、`RecoveryResultState &&
     activeOperation == null && recoveryLease == null && origin == HubStatusCard` のときだけ
     pre-entryの表示状態を発行してoriginを解消、`DismissalOutcome.CancelledAndMayNavigate`
     を返す（zero-write）。該当しなければno-op。run面の **system Back経路のみ** がこの操作を
     呼ぶ（`onSystemBack` → `interruptAndNavigate` の分岐に1箇所の呼出し追加。
     `onDispose` は現行どおり `dismiss()` のまま）。
  7. status card entryは `appliedPoint` / `lastVerifiedApply` を読み書きしない。
     entry originとpre-entry表示状態はhub帰還までcoordinatorが保持する
     （process-local・非永続）。**新しいrunの開始（`beginOperation()`）はoriginを解消する**
     （recovery flow stateのresetに追加）。
- **hub表示readの直列化**: hubのstatus cardは `durableOrganizerStatus()` と
  `readRestorableRecoveryEntry()` を並列に呼ばない。同一effect内でstatus readを先に実行し、
  `ORGANIZED_RESTORABLE` のときに限りentry readを続行する（両readは同一の非block
  `ordinaryMutex` を争うため、並列実行は相互競合で行消失/CTA欠落を自作する。spec D6/RS-AC-06）。
- **platform型・DB行はinterfaceへ出ない**（AGENTS.md設計規約）。`RecoveryPointId` のみ、
  spec 84が既に許すopaque相関キーとしてcoordinatorまで到達する。

## 4. Data model / identity / control & data flow

- 新規persisted data・schema変更・migration: **なし**（spec「Data and state」節）。
- identity: `RecoveryPointId` を再利用。新識別子なし。tokenはprocess-local non-persistent
  （既存registry、module instance所有）。entry originはprocess-localなcoordinator state（非永続）。
- 実装で確定したnavigation形態（open question 2）: hub CTAのtapで
  `HomeScreenManualOrganization(durableRecovery = true)` へ同期遷移し、
  **admission自体はrun面destinationが所有する**（`LaunchedEffect` + NonCancellable。
  destination自開始では `rememberSaveable` ガードで1回のみ実行し、不受理時は
  `popBackStack()` で自己復帰、host離脱とadmissionの競合窗口ではcoordinatorを
  pre-entry状態へ戻す）。coroutine内navigateはnavigation-composeのteardownと
  競合するため、navigateはclick handler内で同期実行する。
- control flow（cold process、hub起点）:

```text
Settings → hub（cold起動。route HomeScreenOrganizer） → ManualOrganizationModule.get()
（LauncherAppState + reconciliation） → readiness READY
→ status cardが durableOrganizerStatus()==ORGANIZED_RESTORABLE を表示
→ readRestorableRecoveryEntry() が {pointId(最新VERIFIED), remainingWindow} を返す
→ restorable行（残時間 + CTA）を表示（読み順: 状態→残期限→操作）
→ tap → run面（HomeScreenManualOrganization）へ遷移 + coordinator status card entry
（RECOVERY lease取得。state不正・entry nullは静かに不受理）
→ State.InspectingRecovery → inspectRecovery(pointId)（既存spec 84検査。fresh token発行）
→ State.RecoveryPreview(result, correlated=null)（履歴行なし。D5構造的排他）
→ confirm → State.Recovering → RecoveryProtocol.recover（既存。writer lease・transaction・検証）
→ State.RecoveryResultState → 結果面でsystem Back（明示的hub帰還。専用新操作を呼ぶ）
  → pre-entry状態（Idle/Cancelled）へ復帰 → hub status card再読込
  （残存VERIFIEDあればrestorable再提示、なければRESTORED_OR_EXPIRED）
（結果面からの診断push等の非明示的離脱ではstate不変。Backで戻るとresult面が維持される）
cancel/back/dismiss（preview中） → pre-entry状態（Idle/Cancelled）へ復帰 →
hub側へ戻る（D5。run面側の変更はBack経路の呼出し追加の最小diff）
```

- TOCTOU: status表示時点とtap時点の間でpointが失効した場合は既存のtyped結果
  （`NotRestorable(EXPIRED)` 等）が確認面に出る。confirm時のlayout変化は
  `NotRestorable(STALE_REVISION)`（既存）。いずれもzero-write。

## 5. Expected files / modules to change

| File | Change |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/RestorableRecoveryEntry.kt` | 新規。閉じたentry型 + `RemainingWindow` |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/RestorableRecoveryPointSelector.kt` | 新規。純粋selector（選択 + window計算。`OrganizerDurableStatusDeriver` と同配置・同様式） |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | `readRestorableRecoveryEntry()` 追加（additive） |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | façade委譲追加 + cold entry method追加 + entry origin（`recoveryEntryOrigin` 仮称）とpre-entry表示状態の保持 + preview cancel（`cancelRecoveryPreview()`/dismiss recovery取消）のorigin別戻り先 + **`leaveRecoveryResultToHub()`（仮称）の新規追加（`dismiss()` は現行mainから無変更）** + `beginOperation()` でのorigin解消 |
| `src/com/android/launcher3/LauncherModel.java` | **bridge最小変更（実装中に判明・Issue #299と同一規約）**: cold settings-only process（spec 271 DS-AC-10 bridgeで `startLoaderWithoutCallbacks` 経由でmodel負荷済み・Launcher非bind）では `forceReloadForOrganizer` が即cancelし復元のcorrelated reloadが必ず `MODEL_RELOAD_FAILED` になるため、非bind時はtokenless loaderを起動し `OrganizerReloadRequest.loaderStarted` で生成生成を判定する分岐を追加。#150/#152のterminalize-exactly-once・snapshot gate契約は不変 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | status cardの `ORGANIZED_RESTORABLE` 行に残時間 + CTA（契約上のentry面）。status read→entry readの直列化（D6）。CTA tap → run面遷移 + cold entry呼出し |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | **最小diff**: 結果面のsystem Back経路での明示的hub帰還操作呼出し1箇所のみ（`interruptAndNavigate` 分岐）。durable行・確認面・`onDispose` は無変更 |
| `lawnchair/res/values/strings.xml`, `values-ja/strings.xml` | 残時間表示（format resource。`LessThanOneHour` 区分含む）+ CTA label（既存 `manual_organization_recovery` の再利用可）。EN/ja同期 |
| `tests/unit/.../ui/ManualOrganizationRunTest.kt` | §8のcoordinator oracle追加（admission state検査・preview cancel戻り先・結果面からの明示的hub帰還・host dispose非変化・correlated null・不受理系） |
| `tests/unit/.../application/lifecycle/RestorableRecoveryPointSelectorTest.kt` | 新規（§8） |
| `tests/unit/.../application/protocol/` 既存test群 | entry readのgate/fail-closed/no-write追加（§8） |
| `tests/organizer-instrumentation/.../OrganizerHubPreferencesInstrumentationTest.kt` | restorable行のCTA・残時間・遷移・read直列化oracle追加。`hubExposesNoRestoreOrRunResultAffordancesAndStartsNothing` の復元CTA部分を更新（spec 366 companion revisionと同期）。復元成功→hub復帰→status再読込のoracle |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | **変更なし**（既存oracle `durableRestorableStatusRendersWhileIdle` 等が無編集でgreenであることがsettings側行の表示のみ維持の証拠） |
| `specs/271-organizer-durable-status-projection/spec.md` | companion revision（spec「companion revision」節の1–3、5）。**本specの実装PRで実施** |
| `specs/366-organizer-hub-shell/spec.md` | companion revision（同節の4。HUB-AC-03復元CTA部分のsupersede注記 + Change history）。**本specの実装PRで実施** |
| `DESIGN.md` | §4.2のread-only seam列挙へ選択readを追記 |
| `docs/assessment/evidence/issue-123-ui-mapping.md`（inventory evidence） | 新規row/CTAのmapping追記（spec 366と同一扱い） |

依存Issueの実装はすべてmerge済みである。`ManualOrganizationPreferences.kt` のdurable row周辺の
変更は最小diffに留める（#374/#375が今後同じ行構造へ触れるため）。

## 6. Compatibility constraints

- 既存の旧entry（`State.Applied` → `beginRecoveryPreview()`）、確認面、strings、
  diagnostics契約、recovery protocolは無変更。既存testは無編集でgreenにする
  （`freshRunInstanceDoesNotReachRecoveryPreview`、`cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`、
  `RecoveryPreviewContractTest`、`OrganizerDurableStatusDeriverTest`、settings側の
  `durableRestorableStatusRendersWhileIdle` を含む）。
  **編集が許される既存testは `hubExposesNoRestoreOrRunResultAffordancesAndStartsNothing` の
  復元CTA部分のみ**（spec 366 companion revisionと同一PRで行う。AI依頼・取り込み済み提案・
  run結果の否定的観測は維持）。
- **CTAはhub status cardのみ**（spec D6）。settings側run面のdurable行は表示のみを維持し、
  `ManualOrganizationPreferences.kt` へのdiffは結果面Back経路での明示的hub帰還操作呼出しの
  最小1箇所に限る（#374/#375との衝突面を減らす。
  origin modelは `AppliedSurface`/`HubStatusCard` の2値に保たれる）。
- #374/#375のstatus card行（AI依頼・取り込み済み提案）とは行種別が異なるため直接競合しないが、
  status cardの行構造・読み順（状態→残期限→操作）はspec 366規約に揃える。
- downgrade: 追加はUI/読み取りのみであり、旧版では表示のみへ戻る（disposition §7.3）。

## 7. Migration / rollback / recovery

- migration: なし（schema・persisted data・backup許可list不変）。
- rollback: PR revertで現行挙動へ戻る。additiveな操作・型・stringであり、revert時に
  残骸（persisted state、journal、schema）を残さない。
- recovery: 本機能自体がrecoveryの入口であり、復元実行の安全性（transaction・検証・
  失敗時のtyped結果）は既存spec 13 protocolが所有する。新経路がrecovery storeに
  書き込む経路は存在しないため、失敗注入・rollback testの対象は「新経路が書込みを
  行わないこと」の証明（counter assert）と、既存recovery E2Eの継続greenである。
- AGENTS.mdホームレイアウト安全規約への適合: 復元適用は既存protocolが所有し、
  revision一致（STALE_REVISION拒否）・保持/移動/削除の説明可能性（recovery write-set）・
  transaction・recovery point（復元対象そのもの）・再検証（DB/model convergence）は
  既存契約のまま。本planは適用moduleの安全契約を変更しない。

## 8. Testing strategy

### Unit（JVM。既存fixture方式を踏襲）

- `RestorableRecoveryPointSelectorTest`（新規）:
  - 単一点 / 複数点で最新選択 / retention境界（`createdAt + 24h` の ±1 ms） /
    checksum無効・非VERIFIED・tombstoneの除外 / 空でnull / 同 `createdAtMs` の決定的tie-break /
    `RemainingWindow` の切捨て・1..24 clamp・1時間未満区分。
- `LayoutApplicationModule`（既存test群へ追加）: `readRestorableRecoveryEntry` の
  gate未ready / mutex競合 / snapshot unavailable → null、no-write/no-event counter assert。
- **token registry所有境界（RS-AC-03）**: `RecoveryPreviewProtocolTest` 系へ
  - 同一module instanceでのone-shot消費（2回目は不成立。既存契約の継続）。
  - **検査後にfreshな `LayoutApplicationModule`（fresh registry）を構築すると旧tokenでconfirmが
    `NotRestorable(MISSING)` になる**（process死の構造的surrogate）。
  - **対比として、coordinatorのみを作り直しても旧tokenが消費可能なまま残ること**を
    固定し（registryがmodule instance fieldであることの構造的証明）、testコメントに
    「coordinator再構築はprocess死のsurrogateにならない」旨を明記する。
- `ManualOrganizationRunTest`（既存へ追加）:
  - status card entry（cold entry）: `Idle` から → `InspectingRecovery` →
    `RecoveryPreview(result, correlated=null)`（履歴summaryを運ばない）→ confirm →
    `RecoveryResultState`。`Idle` 開始のcancel → `Idle` 復帰。
  - **`Cancelled` からstatus card entryでpreviewを開きcancel/back/dismiss → `Cancelled` へ復帰し
    `State.Applied` へ戻らない**（D5戻り先契約。`lastVerifiedApply` は参照しない）。
  - **admission state検査**: `State.Applied` 等の `Idle`/`Cancelled` 以外でstatus card entryを
    直接呼ぶと静かに不受理され状態不変（leaseリークなし）。
  - **結果面からのhub帰還**: hub originのconfirm→`RecoveryResultState`→明示的hub帰還操作→
    pre-entry状態（`Idle`/`Cancelled`）へ復帰しstate残留がないこと。**`dismiss()`（host cleanup
    相当）では `RecoveryResultState` が変化しないこと**（診断push→Backでのresult面維持の
    state-machine側証明）。旧Applied origin・origin無しでは明示的操作も含め現行挙動のまま
    であること（対比で固定）。`beginOperation()` がoriginを解消すること。
  - run active中・recovery lease競合中の不受理（状態不変）。
  - 検査throw時にorigin契約どおりの戻り先へ復帰すること（旧entryは既存挙動のまま）。
  - 旧entryの既存oracle（`freshRunInstanceDoesNotReachRecoveryPreview`、
    `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`、相関gate系）が
    **無編集でgreen** であることの回帰確認。
- `RecoveryPreviewContractTest` / `RecoveryPreviewProtocolTest`: 契約系は無編集green
  （spec 84契約の無変更の証拠。上記のregistry所有boundary test追加を除く）。

### Instrumentation

- hub status card行のstatus別render: `ORGANIZED_RESTORABLE` 行に残時間 + CTAがあること、
  他status・fail-closedではCTAなし（`OrganizerHubPreferencesInstrumentationTest` へ追加）。
  `hubExposesNoRestoreOrRunResultAffordancesAndStartsNothing` の復元CTA部分を更新し、
  AI依頼・取り込み済み提案・run結果の否定的観測は維持する。
- **read直列化oracle**（RS-AC-06）: hub Compose testのfake coordinatorがstatus readと
  entry readの呼出しを記録し、(a) 2 readの重複（並列実行）が起きないこと、(b) status readが
  `ORGANIZED_RESTORABLE` 以外のときentry readが呼ばれないこと、(c) 一時的なfail-closed readの後、
  再読込契機（state/gate変化）でCTAが回復すること（永続的CTA欠落なし）を固定する。
- CTA → run面遷移 → 検査 → 確認面（decision pair）→ cancel / confirm の両経路。
  cancel時はhub側へ戻ること（D5のUI側観測）。
- **復元成功→hub復帰→status再読込**: confirm → ResultState → 結果面でBack（明示的hub帰還）→
  hubで行が更新される（単一点なら「restored or expired」、複数点なら残存最新に対するrestorable行）
  （RS-AC-01/02のinstrumentation側）。
- **診断pushでのresult面維持**: `RestoreFailed`（safe-support）の結果面から「診断を開く」→
  診断destination → Back → result/safe-support面が維持される（結果面のstate残留は
  非明示的離脱で壊れないことのUI側観測。RS-AC-04）。
- run進行中はdurable行・CTAが消える否定的観測（既存
  `hubHidesStatusRowsWhileRunIsActiveAndReshowsAfterCancel` の継続）。
- settings側run面の無変更回帰: 既存oracle（`durableRestorableStatusRendersWhileIdle`、
  `recoverySurfacesRenderDecisionButtons`、`verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel`）
  が **無編集でgreen** であること（settings側行が表示のみのままである証拠）。
- **cold-process emulator flow**（RS-AC-01。有効point 1点のみのpreconditionを明記）:
  force-stop → hubを最初の面として起動 → Launcherを開かずにCTA→検査→確認→復元を完了 →
  結果面を離脱してhubへ戻る → 復元後の行が「restored or expired」へ変わる。evidenceをPR/auditへ
  記録（spec 271 DS-AC-10と同型）。
  **`OrganizerDurableStatusInstrumentationTest` はCI lane外のclassであるため、この系統の
  cold-process/process-death evidenceはPR時の手元emulator実行としてPR/auditへ記録する**
  （CI `final-status` の要件を満たすのは `organizer-instrumentation-issue52-tests` laneの
  対象class群である）。
- process死後の再検査のみ再開（RS-AC-03のinstrumentation側）: preview表示後にprocessを落とす →
  再入場では旧confirmを再開せずstatus CTAからの再検査のみで復元できる。
- `ManualOrganizationProductionE2EInstrumentationTest.manualRunUsesProductionCaptureApplyVerificationAndRecovery`
  の継続green（既存recovery経路の無変更証拠）。

### a11y / localization evidence

- Compose semantics test: 読み順「状態→残期限→操作」（既存
  `hubStatusRowsPrecedeTheActionsInReadingOrder` の規約へ残期限+CTAを挿入した形）、
  CTAのrole/state、行statusとの区別。
- 200% font scaleでCTA到達可能（既存 `hubStatusCardStaysReachableAtTwoHundredPercentFontScale`
  の規約継承）。non-color-only。focus restoration（`hubFocusRestoresToStartAfterBackFromRunSurface`
  相当を復元flowにも適用）。
- 新規stringの `values/` / `values-ja/` name集合・placeholder一致の機械確認 +
  hardcoded literal grep（spec 366と同一方式）。

### 検証command（記録対象）

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# organizer instrumentation lane（CI: organizer-instrumentation-issue52-tests の対象class群。
# 手元: connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...）
# cold-process emulator evidence（RS-AC-01/RS-AC-03。CI lane外のため手元実行をPR/auditへ記録）
```

## 9. Accessibility evidence

spec「Accessibility and localization」節の要求を、§8のsemantics test +
emulator screenshot（light/dark × ja/default）+ TalkBack操作記録でPRへ添付する。
status card読み順規約（TO-BE §13-5）はspec 366が実装済みであり、hub側の既存semantics testと
結果を突き合わせる。

## 10. Incremental implementation order

1. **純粋selector + entry型 + module読み取り**（unit test込み。application内で完結）。
2. **coordinator cold entry + entry origin + 戻り先契約**（unit test込み。既存state列の再利用確認）。
3. **UI描画**（hub status cardの残時間 + CTA + read直列化、strings EN/ja、semantics test、
   `hubExposesNoRestore...` の更新。settings側run面はBack経路の呼出し追加1箇所のみ）。
4. **instrumentation / cold-process evidence**。
5. **spec 271 / spec 366 companion revision + DESIGN.md + inventory evidence**
   （同一PR。spec受入はowner review）。
6. **高リスクPR evidence**: `final-status` CI成功 + `docs/assessment/pr-<n>-<slug>.md`。

2と3は依存するが、1は独立してreview可能。単独のaudit可能な小PRとして出す
（disposition §8: 高リスクpathのため単独小PR推奨。#374/#375とbundleしない）。

## 11. Dependency / blocker（高リスクPR要件を含む）

- **実装着手のblocker**:
  - ~~#365（正本改訂、docs-only）のmerge~~ → **解消済み**（PR #379、2026-09-19 merge）。
  - ~~#366（hub）のmerge~~ → **解消済み**（PR #380、2026-09-19 merge。CTAの契約上のentry面は
    実装済み。pre-merge fallback（旧面上のみの先行実装）は採用しない）。
  - 本specのowner受入（`status: draft` → accepted。`status: needs-spec` → readyはownerが付与）。
    **残っている唯一のblocker**。
- **高リスクPR要件**（`risk: layout-data` label。AGENTS.md / github-workflow.md §高リスク契約）:
  - PRに `risk: layout-data` labelが付く。
  - 検証対象head commit上で `CI / final-status`（source job `organizer-unit-tests` /
    `check-style` / `build-debug-apk` をskipなしで含む `pull_request` eventのrun）が成功。
  - `docs/assessment/pr-<PR番号>-<slug>.md` に独立audit記録（Auditor / Audit date /
    Head SHA / CI run link / Criteria=本spec受入条件+spec 84/13/271の受入条件を要件ID付きで参照）。
    auditは実装sessionとは別のsession/agentが実施する。
  - `High-risk gate / high-risk-evidence` checkの成功（`.github/workflows/high-risk-gate.yml`
    による機械検証）。
- **非block**: #374/#375（status cardの別行。未着手）、#367/#368/#369/#370/#371/#372/#373
  （実装済み。行構造の現状は§1に反映済み）。

## 12. Risk

| Risk | Mitigation |
|---|---|
| 選択readがsnapshotの内容をUI判断へ露出する（spec 84/271境界の侵食） | 閉じた2型のみ公開。pointIdはcoordinatorまで。型shape test + 契約test（RP-AC-05系）の継続で担保 |
| status表示とentry readの不整合（表示中の失効・復元済み化） | fail-closed側へ倒す（CTAなし）+ 検査を権威gateに（typed結果表示）。live countdownを作らず再読込時更新 |
| status card表示のstatus readとentry readの自己競合（同一の非block `ordinaryMutex` を2本のreadが争い、CTA欠落が永続化する） | 同一effect内での直列化を契約化（status → `ORGANIZED_RESTORABLE` のときのみentry read。spec D6/RS-AC-06）。fake coordinatorで呼出し重複・回復を固定するoracle |
| cold entryのcancelが旧entryの戻り先契約を壊す／status card起点が旧Applied面へ復帰する | entry originで戻り先を束縛（spec D5）。旧path oracleの無編集greenをAC化し、status card originのpre-entry復帰をunit oracleで固定 |
| 復元成功後の `RecoveryResultState` 残留でhubのdurable statusが再deriveされない／逆にhub帰還をhost cleanupに畳み込んで診断push等でresult面が失われる | hub帰還を **system Backに束縛された明示的操作** に分離し `dismiss()` は無変更（spec D5）。unit oracle（明示的操作→pre-entry状態・dismiss非変化・旧origin対比）とinstrumentation（hub復帰後の行更新・診断push→Backでresult面維持）で固定 |
| tokenの誤用テストが誤った境界（coordinator再構築）でprocess死を代弁する | registry所有境界（module instance）をD4/RS-AC-03で明記し、fresh module構築のsurrogate testで固定。対比testでcoordinator再構築がsurrogateでないことも示す |
| recovery storeへの意図しない書込み | 読み取り経路のno-write/no-event counter assert。既存protocol以外の書込み経路を新設しない設計 |
| cold-process evidenceがCIで実行されない（lane外class） | `final-status` の要件はlane対象classで満たし、cold-process evidenceは手元emulator実行としてPR/auditに明示記録（DS-AC-10と同型の扱い） |

## 13. Explicitly unverified areas

- ~~#366のhub実装の最終形~~ → **検証済み**（現行main `c05435a947` でroute名 `HomeScreenOrganizer`、
  status card描画 `hubDurableStatusItems`、否定的oracle `hubExposesNoRestore...` を確認。§1に反映）。
- `RemainingWindow` の表示copyと複数形の扱い（ja「約N時間」等。実装PRのstring diffで確定）。
- CTA tap後のnavigation実装詳細（route指定・timing。契約は「完結性」と「cancel/backでhub側へ戻る」
  のみ。既存 `NavigationActionPreference` 構造の範囲内で実装PRのreviewで確定）。
- cold-process emulator evidenceの再現手順（DS-AC-10の既存手順を基に、hub最初の面・有効point 1点の
  precondition付きで実装PR時に確定・記録する）。
- `OrganizationOperationLease` のKind構成が将来のauthoring移動で変化する場合の
  cold entry側の追従（契約は「RECOVERYの単一flight」であり、変化しても本specの要求は変わらない）。

[la]: ../../lawnchair/src/app/lawnchair/LawnchairApp.kt
