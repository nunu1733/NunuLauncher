# Implementation Plan: Usage Access要求をjust-in-time化する（初回signal読み取り時点での文脈付き任意要求）

> Issue: #371
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-21時点のmain (`13c95eafe6` — #365/#366/#367/#368 merge済み、#369実装
（PR #387）merge済み、#370実装（PR #389）merge済み) を実読みした結果である。推測と
確認済み事実を分けて記載する。

### 現在のUsage Access要求導線（常設rowのみ）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt`
  （#367で新設）: hub材料セクション（T-06）と共有のmaterial行群。記録toggle
  （`SwitchPreference`）とUsage Access行（`ClickablePreference`）。Usage Access行の
  `onClick` は `context.startActivity(Intent(ACTION_USAGE_ACCESS_SETTINGS))` で、
  付与状態は `UsageAccess.isGranted(context)` を `remember` で保持し、
  `DisposableEffect` + `LifecycleEventObserver` で `ON_RESUME` ごとに再読取する
  （spec 203 U-2の「復帰時再読取」実装。遷移失敗は扱っていない）。
  呼び出しsiteは `OrganizerHubPreferences.kt:183`（hub材料セクション）のみで、
  #367により設定側の二重インスタンスは廃止済み。JIT要求の実装は存在しない。
- strings（現行T-06 row文言）: `lawnchair/res/values/strings.xml:1012-1014`
  （`organizer_personalization_usage_access_label/_granted/_not_granted`）、
  `lawnchair/res/values-ja/strings.xml:34-36`。いずれも用途（整理精度向上）と
  許可なしでの全機能利用可能性は示すが、「なぜこのrowで今確認するのか」
  （TO-BE §7.3の2要素目）を示していない。

### 権限predicateと伝播timing

- `lawnchair/src/app/lawnchair/organizer/integration/UsageAccess.kt:16-18`（`object UsageAccess`、
  `isGranted(context)`）: app-op `OPSTR_GET_USAGE_STATS` ベースの共有predicate
  （`MODE_DEFAULT` 時はmanifest permissionへfallback）。JIT要求のtrigger判定と
  復帰時の再取得はこのpredicateを再利用する（新しい権限APIを導入しない）。
- `tests/organizer-instrumentation/app/lawnchair/organizer/UsageAccessTransitionProbeTest.kt`
  が示すとおり、app-opのshell変更は**非同期に伝播する**（testも1秒待機）。したがって
  復帰直後の単発1回読みをproduction契約にすると、付与して戻った試行が伝播遅延により
  未付与と判定され得る（spec scenario/JIT-AC-03との矛盾 — review指摘2）。
  復帰後の再取得はbounded re-readとする（下記Design/Failure handling）。

### signal読み取り点（composition）

- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`:
  `composeInternal`（177-185行目。`composeFullOrganization` / `composeScopeComposedOrganization`
  の共通本体）がcomposition attemptごとに `readPersonalizationSnapshot`（592行目）を通じて
  `PersonalizationSignalSnapshotSource.read()` を1回呼ぶ。失敗・未付与は
  `PersonalizationSignalSnapshot.unavailable(...)` へdegradeし、決してthrowしない
  （spec 203 optional source契約）。**composerはUIを持たない**ため、JIT要求のdialogを
  composer内に置くことはできない。

### trigger点1: 整理run — composed phase入口が単一のchoke point、RUN leaseはadmission済み

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（#369実装適用後）:
  - `start()`（430行目overload、本体441行目〜）: `beginOperation`（1256行目）が
    `operationGate.tryAcquire(RUN)` で**RUN leaseを取得**し `State.Capturing` /
    `preparationPhase=DETECTION` をpublish → `State.CandidateDetection` →
    `application.detectMissingAppCandidates()`。検出結果の受理は `acceptDetection`
    （509行目。lock下のactive再確認付き）を通る。その後の分岐:
    - `Ready` かつ **0件かつexported scopeなし**（D-06）→ `State.Selecting` 進入後、
      `continueWithEmptySelection`（473-478行目→531-546行目）が**内部継続で即座に**
      composed phaseへ進む（composition直行経路。ユーザー確認点は存在しない）。
    - `Ready` かつ候補あり（またはexported scopeあり）→ `State.Selecting` で**停止**
      （compositionはまだ発生しない。選択確認がcompositionの起点）。
    - `Unavailable` → `runComposedPhase(operation, selection = null)` で**即座にcomposition**
      （489-493行目）。
  - `confirmSelection()`（554行目〜）: scope系check後、`preparationPhase=CAPTURE` +
    `State.Capturing` をpublishし `runComposedPhase(operation, selection)`（599行目）でcomposition。
  - `runComposedPhase`（692行目〜）: **run側compositionの唯一の入口**である。
    入口のcancel gate（RD-6。lock下でactive再確認→`preparationPhase=CAPTURE` commit→
    `journalStarted`設定→`RUN_STARTED`発行をatomic化）の後にcompositionを行う。
    `RUN_STARTED`以前（pre-composed phase）のcancelはjournal eventを発生させない
    （現行契約）。D-06内部継続・検出 `Unavailable` 継続・選択確認の3経路がいずれも
    この入口を通るため、**入口にgateを置けば経路の追加・漏れが構造的に発生しない**。
  - **JIT pauseはadmission後である**: pause時点でRUN leaseは保持され、`activeOperation`
    も存在する。2回目の `start()` は `beginOperation` の既存gateにより `Busy` になる。
    journal・`RUN_STARTED`・compositionは未発生。`cancel()`（959行目）/ `dismiss()` は
    現行どおりactive operationをcancelしleaseを正確に1回解放する。
  - 教訓: **「run開始操作」の直前にJIT要求を出す設計は、選択面で停止する経路で
    一度もsignalを読まないまま要求を見せる逆JITになる**（初回review指摘1）。
    要求はcomposed phase入口（`runComposedPhase` の直前）に置く必要がある。
- run開始の呼び出しsite: `ManualOrganizationPreferences.kt` の開始系行群
  （`execute { coordinator.start(trigger) }` 複数site。IO dispatcher）。#369実装により
  state→face写像は純関数 `manualOrganizationFace(state)`（`ManualOrganizationFace.kt:57`。
  20状態→8ユーザー状態。0件 `Selecting` は `PREPARATION` へ決定的に写像）へ集約され、
  face hostが面を描画する。
- onboarding: `OrganizationOnboardingProposal.kt` の `admitReview`（216, 270行目）が
  `ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)` を呼ぶ。
  #370実装（PR #389）はhint copy/案内先のみを変え、admission経路は無変更であることを
  当該mainのdiffで確認済み（開始呼び出しの行番号は不変）。

### trigger点2: AI依頼生成

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  （`ExchangeFlowStateHolder`、194行目。#369/#370実装で無変更）:
  state machine `ExchangeScreen`（87行目〜。`Closed` / `SelectingPrivacy` /
  `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / import系）。
  `requestGeneration`（255行目）は置換確認が必要なら `ExchangeScreen.ReplacementConfirm` へ
  遷移し、不要なら直ちに `generate(tier)`（282行目）/ `generateScoped(...)`（296行目）へ進む。
  `confirmReplacementAndGenerate`（270行目）が置換確認承認後の生成kickoffである。
  `close()`（244行目）がholderの破棄入口である。holderは既存の settlesを
  `exportId`（transport）・import attempt tokenで厳密にbindしており、保留生成も
  同程度のidentity/lifetime契約が必要である（review指摘4）。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`:
  `generate`（79行目）→ `composeExportInputs`（36/61行目。`ExchangeInputAdapter.composeForExport`）、
  `generateForSelection`（91行目）→ `composeScopedExportInputs`（49/66行目）。
  `ExchangeInputAdapter.kt:44,58` が `composer.composeFullOrganization()` /
  `composer.composeScopeComposedOrganization(selection)` を呼ぶ。**いずれもrun compositionと
  同一のcanonical composition seamを通る**ため、依頼生成はprocess初回のsignal読み取りに
  なり得る。特にrun-in生成（選択面から `generateForSelection`）は `Selecting` 状態から
  到達でき、この時点ではrunのcomposed phase（composition）が**まだ一度も走っていない**。
- spec 205 AC-13: activityなsessionが存在する場合の生成開始は置換確認でgateされる。
  JIT要求はこの確認より後に置く（spec scenarioどおり。`requestGeneration` の分岐構造が
  この順序を自然に実現する）。
- import→run再構築path: `ExchangeFlowUi.kt:690` が `run.start(intent = request.validated)` を
  呼ぶ。run側のpause pointで統一的に扱われる（専用gateは不要）。
- #372（open）は `ExchangeFlowUi.kt` と `ManualOrganizationPreferences.kt` を主変更先とする
  （T-15/T-16再配置・送信前確認形式）。本Issueとの共有変更面であるため、統合は
  「どちらが先にmergeしても後続側がre-entryして当時のcurrent mainを再読する」規律の下で
  行う（下記 Dependencies / risk）。

### 既存test surface

- `tests/organizer-instrumentation/app/lawnchair/organizer/UsageAccessTransitionProbeTest.kt`:
  shell `appops set <pkg> GET_USAGE_STATS allow/default` でapp-opを切替え、production
  readerの付与/取消追従を検証するpattern（app-op変更は非同期のため1秒待機）。
- `tests/unit/app/lawnchair/organizer/integration/PersonalizationCompositionTest.kt`:
  composer seamの回帰（JIT追加で無編集greenであるべき）。
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`:
  run state machineのunit oracle。#369実装がD-06継続・cancel gate・`preparationPhase` の
  oracleを追加済み。JIT pauseのunit oracleはここに追加する。run構築時にgateを注入しない
  既存testは「要求機会が常に消費済み」の既定gateで現行挙動を維持する（下記Design）。
  `CAPTURE` commit位置の移動（下記Design）により、#369で追加された
  「confirmSelectionがCAPTUREをpublishしてからcomposed phase」という経路の観測順序oracleは
  本Issueの実装PRで更新し、理由（CAPTURE commitのcomposed phase入口への集約）を記録する。
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationFaceTest.kt`（#369新設）:
  face mapping純関数のtable-driven test。`AwaitingUsageAccessJit` のface entry追加時に
  対応表へ1行追加する。
- `ManualOrganizationRunFaceTrace`（`ManualOrganizationFace.kt:44`、#370が追加した
  test-only観測seam）: faceのcommit値を決定的に記録するpattern。JIT要求の
  「選択中断で要求面を経由しない」否定oracle等で同一patternのtest-only observerを
  併用できる（dialog表示の観測はtest tag/semanticsで行う）。
- T-06の回帰surface: #366/#367のinstrumentation（toggle↔preference一致、resume再読取）。
  copy改訂で文言assertが変わる場合の更新は本Issueが行う（状態操作oracleは無編集green）。

## Design

### Modules and interfaces

- **新規: process-scopedな要求機会gate（UI層、`app.lawnchair.organizer.ui`）**。
  小さなclass（仮称 `UsageAccessJitGate`）とCompose dialog/helper（仮称
  `UsageAccessJitRequestUi`）を1 fileに置く（例:
  `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`）。
  - `UsageAccessJitGate` は `isGranted: () -> Boolean` をconstructor注入とし、内部に
    process-localな提示権stateを持つ。stateは **`Available` → `Reserved(owner)` →
    `Consumed`** の原子的な遷移のみであり（全methodは同一monitor下で動作）、
    TOCTOU競合を構造的に排除する（review指摘1）:
    - `evaluate(owner): JitDecision` — composition trigger点で呼ぶ。戻り値は3値:
      - `Proceed` — 消費済み、または未消費かつ付与済み（付与済みの場合は**その場で
        機会を消費**。spec scenario「初回trigger時に付与済み」）。callerは即座に
        compositionへ進んでよい。
      - `Present` — `Available` から `Reserved(owner)` への遷移に**原子的に成功**した
        callerだけが受け取る。callerは要求を保留し、dialogを提示してよい唯一の主体である。
      - `Wait` — `Reserved(他のowner)` の場合。callerは要求を保留し、dialogを提示せず、
        当該reservationの解決（消費または解放）までcompositionへ進まない。
    - `markPresented(owner)` — dialogを**提示した時点**にownerが呼ぶ。
      `Reserved(owner)` → `Consumed`（owner不一致・消費済みはno-opの冪等）。
      提示後の操作の成否・Busy・遷移失敗とは独立である（review指摘4）。
    - `release(owner)` — **提示前に**ownerの操作がcancel/破棄された場合のみownerが呼ぶ。
      `Reserved(owner)` → `Available`（機会は未消費のまま残り、次のtriggerで要求される）。
    - 選択面の中断など「compositionに到達しない操作」は `evaluate` を呼ばないため
      機会に触れない（spec否定scenario）。
  - 競合契約: 2つの起点が近接して `evaluate` しても、`Present` を受け取るのは正確に1つであり、
    もう一方は `Wait` で保留される。reservation解決（`Consumed`）後に保留中の起点は
    再評価され `Proceed` となるため、2つ目のdialogは存在し得ない。解放
    （`Available` への復帰）後は保留中の起点が改めて `Present` を獲得できる。
  - production instanceはprocess-scoped singleton（`ManualOrganizationModule` と同様の
    `get(context)` pattern、predicateは既存 `UsageAccess.isGranted` に委ねる）であり、
    run machineとexchange holderの**双方から同一instanceを共有する**（機会はprocessで1回）。
    unit testは注入predicateで直接構築する（仮想interfaceは作らない — AGENTS.md設計規約）。
    run構築時にgateを注入しない既存test互換のため、`ManualOrganizationRun` の
    constructor既定値は「常に消費済み（常に `Proceed`）」の不変gateとする。
- **統合点1: run machineのcomposed phase入口にpause point（2段階orchestration。review指摘1の解消）**。
  `ManualOrganizationRun` にgateをconstructor注入し、**`runComposedPhase` の入口**
  （#369のcancel gate／journal openの前）に要求機会の判定を置く。3つのcomposition経路
  （検出 `Unavailable` 継続・D-06の0件内部継続・選択確認）がいずれもこの単一入口を通るため、
  経路の漏れが構造的に発生しない。選択面（`State.Selecting`）と同一の「UI待機点」の概念であり、
  composer・`PersonalizationSignalSnapshotSource`・admission契約（single-active-operation）は
  触れない。
  - pause判定の構造: 入口でまずactive再確認（cancel勝利時はgateに触れずreturn）→
    `gate.evaluate(operation)` を判定。`Proceed` なら現行どおりcancel gate → composition
    （付与済み・消費済み環境では新stateへ到達しない。既存test互換）。`Present` または
    `Wait` の場合のみ `State.AwaitingUsageAccessJit(runId, selection, isOwner = decision == Present)`
    をpublishしてreturn。**journal は開かない。RUN leaseは保持される**（2回目のstartは
    既存の `beginOperation` gateにより `Busy`。review指摘3の事実関係）。
  - **CAPTURE commitの入口集約**（review指摘3の自己矛盾解消）: 現行
    `confirmSelection()` / `continueWithEmptySelection()` が `runComposedPhase` 呼び出し**前**に
    `preparationPhase=CAPTURE` + `State.Capturing` をpublishするのをやめ、3経路とも
    `preparationPhase=CAPTURE` と `State.Capturing` のcommitをgate通過後の
    `runComposedPhase` 内（#369 RD-6 cancel gateと同一lock区間。phase-before-state順序は
    当該lock section内で維持）へ集約する。これによりAwaiting中の表示はcapture開始を
    偽らず（`preparationPhase=DETECTION` のまま新stateのみpublish）、1 frameの
    「capture中」誤表示がなくなる。#369が「idempotent on the paths that already
    committed CAPTURE」とした注記は「全経路で入口がauthoritative」に更新される。
  - 新state: `State.AwaitingUsageAccessJit(runId, selection: List<CandidateTarget.AppKey>?,
    isOwner: Boolean)`。ユーザー向け8状態（TO-BE §8.1）には現れない。face mapping
    （`ManualOrganizationFace.kt`）へは `PREPARATION`（T-09準備中。dialogはその上の
    modal overlay）のentryを1行追加する。
  - 新method `continueAfterUsageAccessGate()`（仮称）: **lock内で**stateが
    `AwaitingUsageAccessJit` かつoperationがactiveであることを確認し、
    **同一lock区間で** `State.Capturing`（および `preparationPhase=CAPTURE`）へのcommitまで
    完了させてからlockを抜け、`runComposedPhase` を正確に1回呼ぶ。2回目以降の呼び出しは
    stateが既にAwaiting以外のためno-opであり、`ON_RESUME` と継続callbackの近接・二重発火
    でも `RUN_STARTED` / compositionの二重実行は構造的に発生しない（review指摘1の後段）。
  - dialog host（run側）: face host（#369実装後の `ManualOrganizationPreferences` 側の
    run state観測点）が `AwaitingUsageAccessJit` を観測したら、`isOwner == true` の場合のみ
    共有dialogを提示する（提示時に `gate.markPresented(operation)`）。
    `isOwner == false`（`Wait` で保留されたrun）の場合はdialogを提示せず、reservationの
    解決を観測した時点で: 消費済みなら `continueAfterUsageAccessGate()`、解放済みなら
    gate再評価で `Present` を獲得してから提示する。
    解決時（「続行」/ Back / 復帰後のbounded re-read完了）に `continueAfterUsageAccessGate()`
    を呼ぶ。「設定を開く」は `ACTION_USAGE_ACCESS_SETTINGS` の `startActivity` を
    `ActivityNotFoundException` をcatchして呼ぶ（失敗時の契約は下記Failure handlingの
    単一normative経路）。復帰後の再取得は**bounded re-read**とする（下記）。
    個々の開始row・onboarding `admitReview`・import→run（`ExchangeFlowUi.kt:690`）は
    **無編集**でよい（pause pointがmachine内のため）。
  - onboarding経由も同一経路で扱われる: `start(ONBOARDING_PROPOSAL)` → 検出 →
    composed phase入口のpause → run面hostがdialogを提示。onboarding操作時点で
    要求を出さない（spec scenarioどおり）。
  - cancel/dismiss連携: `cancel()` のcancel可能状態集合へ `AwaitingUsageAccessJit` を追加し、
    cancel/dismiss時に **dialogが未提示（`Reserved` のまま）の場合は `gate.release(operation)`
    を呼ぶ**（提示済み＝消費済みの場合は触れない）。pauseは `RUN_STARTED` 以前であるため、
    pause中のcancelはjournal eventを発生させず、leaseは現行経路で正確に1回解放される。
- **統合点2: 依頼生成のholder gate（state machine本体へ組み入れ。review指摘4の解消）**。
  `ExchangeFlowStateHolder` の `generate` / `generateScoped` の先頭で
  `gate.evaluate(exchangeOwner)` を判定する。`Proceed` なら現行どおり生成。`Present` /
  `Wait` の場合のみ **`ExchangeScreen.AwaitingUsageAccessJit(tier, scoped)`** を
  state machine本体へpublishする（`ExchangeScreen` の既存data class群と同一のvariant。
  「machine外の独立state」は採らない — pending生成のidentity/lifetimeをstate machineの
  既存遷移規律の下に置く方が、close/遷移との整合が一箇所で保証されるため）。
  - payload `(tier, scoped)` はscreen stateが所有し、**生成の再開は当該screen stateが
    現行のscreenである場合に限り1回だけ**適用される（stale screen値を持つ遅延callbackは
    no-op。既存のexportId/attempt tokenと同等のsettle-bind規律）。
  - 無効化: `close()`（`Closed` への遷移）、`ReplacementConfirm` 等への別遷移、
    host navigation破棄のいずれでも `AwaitingUsageAccessJit` は退場し、保留生成は破棄される。
    退場時にdialogが**未提示**（`Reserved` のまま）であれば `gate.release(exchangeOwner)` を
    呼ぶ（提示済みなら消費済みのため触れない）。
  - dialog host（exchange側）: hosting composableが `AwaitingUsageAccessJit` を観測したら
    `Present` 権を確認してから共有dialogを提示（提示時に `markPresented`）、解決callbackで
    `generate` / `generateScoped` を再kickoffする。置換確認（`ReplacementConfirm`）は
    既存flowのまま先行するため、「置換確認 → JIT要求 → 生成」の順序が自然に成立する。
    run-in生成（`generateForSelection`）も同一gateで扱われる。dialogのBackはdialog
    dismissalとして消費し、run側のBack handler（選択凍結中のrun中断）へ漏らさない。
- **統合点3: 復帰時の付与状態再取得 — bounded re-read（review指摘2の解消）**。
  `UsageAccessJitRequestUi` のhelper内に「settings遷移中」のprocess-local flagを置き、
  復帰（`ON_RESUME`）時に単発の再読みで判定せず、注入可能な **predicate/clock/delayによる
  bounded re-read**（例: 上限数回・短時間。具体的上限値は実装PRで確定し、processが
  塞がらない短時間であること）で `UsageAccess.isGranted()` を再評価する:
  - GRANTEDを観測できた時点で遅延されていたcompositionを再開する（付与済みで読まれる）。
  - 上限まで観測できなかった場合は未付与としてcompositionを続行する
    （次回compositionから付与済み。既知限界としてspec/PR記録）。
  - 固定sleepは使用しない。unit testは注入した `false → true` のpredicate列と
    上限到達の両経路を決定的に検証する。T-06 rowの既存 `ON_RESUME` 再読取
    （単発・表示用途）は現行契約のまま無変更である（状態表示のtimingと、
    composition再開の判定は別契約である）。
- **統合点4: T-06常設rowのcopy改訂（review指摘3の解消）**。
  `OrganizerUsageMaterialRows.kt` の文言（`organizer_personalization_usage_access_label/
  _granted/_not_granted`、EN/ja）を§7.3の3要素を1文で満たす内容へ更新する。
  rowの状態表示・遷移・`ON_RESUME`再読取の実装・操作契約は**無変更**である
  （#367 MAT-AC-06のpin対象は状態管理であり、copyは本Issueが所有するpermission copy契約）。
  最終文言は実装PRのcontract commitで確定し、意味要素checklist（3要素・任意性・
  raw/bucket/送信前確認）をPR evidenceへ記録する。
- **seamの境界**: composer・`PersonalizationSignalSnapshotSource`・`UsageAccess` predicate・
  exchange flow controller（生成順序契約）は**無変更**である。
  JIT gateは「signalを読む操作を発火するUI上の点」とcomposition呼び出しの間に挟まる
  UI層のgateであり、interfaceを通してtestする（gate unit + run machine unit +
  hosting instrumentation）。platform型・DB行はgateのinterfaceへ現れない。

### Data flow

1. ユーザーがcomposition起点の操作を行う（開始系row / 選択確認 / 依頼生成）。
2. trigger点で `gate.evaluate(owner)` を判定（run側は `runComposedPhase` 入口。
   exchange側は `generate`/`generateScoped` の先頭）。
3. `Proceed`（消費済み or 付与済み→その場で消費）なら即座に従来どおりcomposition実行。
4. `Present` / `Wait` なら要求を保留する（run側: pause state / exchange側:
   `ExchangeScreen.AwaitingUsageAccessJit`）。`Present` を得たhostが共有dialogを提示して
   `markPresented` で機会を消費する。`Wait` のhostは提示せず保留する。
5. 解決: (a) 「設定を開く」→ intent発火 → 復帰後のbounded re-readでGRANTED観測後に保留
   composition再開（上限超過なら未付与で再開）。(b) 「続行（断る）」/ system Back /
   dismiss → 保留操作の継続。
6. compositionは既存seamで実行され、その時点の実際の付与状態でsnapshotが構築される
   （付与されていればsystem usage section構築、未付与なら `Unavailable` + launcher-origin保持）。
   JIT gateはcompositionの結果を一切読み書きしない。

### Alternatives rejected

- **gateを開始rowのcall site（9 site + onboarding）で `coordinator.start()` の前に置く**
  （初版planの設計。初回review指摘1で却下）: `start()` は検出 `Ready` 時に `Selecting` で
  停止しcompositionが来ないため、一度もsignalを読まないケースで要求を表示する逆JITに
  なる。かつ9 site + onboardingの個別wrapはsite漏れリスクを常時抱える。却下。
- **pause判定を `runComposedPhase` の3呼び出しsite（検出 `Unavailable` 継続・D-06内部継続・
  選択確認）に個別に置く**: 同一判定の重複であり、将来の経路追加で漏れ得る。
  入口単位のchoke pointがuniform rule（triggerは「signalを読む操作」に結合）の
  構造的保証になる。却下（choke point採用）。
- **gateをcomposer / composition module内に置く**: composition moduleはUI-freeであり
  （AGENTS.md設計規約・spec 203のpurity要求）、dialog待ちを混入させるのはseam汚染。却下。
- **composition後のreactive prompt**（`NOT_GRANTED` snapshotの検出後にdialog表示）:
  当該試行のsnapshotは既に固定されており、付与しても現runがbenefitしない。
  TO-BE §6.4「戻って付与されていればsignal取得を続行」と矛盾。却下。
- **「表示済み」の永続flag**（1回=install生涯）: Issue本文「persistent state変更なし」および
  disposition §7.3「要求stateはprocess-local（再訪時は付与状態で判断）」と矛盾。却下。
- **latchを1bitの提示済みflagのみにする**（2nd review前の設計。review指摘1で拡張）:
  `evaluate()` の `true` 返却と `markRequested()` の呼び出しの間に窓があり、run側と
  exchange側の2 callerが双方 `true` を得て2つの保留操作を作り得る（TOCTOU）。
  提示権のreservation（`Available → Reserved → Consumed`）により構造的に排除する。却下。
- **latchを「操作がcompositionへ進んだ時点」で消費**: dialog提示後に操作がBusy・失敗で
  成立しなかった場合、同一process内の次操作で同じ要求を再表示でき、spec
  「提示されたら再表示しない」（JIT-AC-05）に反する（初回review指摘4）。却下 —
  消費点は提示時点に固定する。
- **latchを付与済み初回triggerでも消費しない**: spec scenario「初回trigger時に付与済み」の
  「後続の権限取消でも要求は表示されない」が実現できなくなる（取消後に `evaluate` が
  再度要求を返してしまう）。却下 — 付与済みの初回trigger評価で消費する。
- **復帰時の単発 `ON_RESUME` 再読みをproduction契約とする**（2nd review前の設計。
  review指摘2で拡張）: app-op変更は非同期に伝播し（probe testが1秒待機で対処）、
  付与して戻った試行が未付与と判定され得る。TO-BE §6.4「戻って付与されていれば
  signal取得を続行」と直接矛盾する。bounded re-read（注入可能clock/predicate）を採用。
  単発読みに契約を弱めてowner決定を取り直す案はscope拡大のため採らない。却下。
- **exchange保留生成を `ExchangeScreen` machine外の独立stateに置く**（2nd review前の
  設計。review指摘4で拡張）: pending生成のattempt identity・invalidate点・遅延resumeの
  取扱いが未定義のままとなり、`close()` / 別generation開始 / navigation破棄後の遅延callbackが
  古い生成を再開する余地を作る。state machine本体のvariant
  （`ExchangeScreen.AwaitingUsageAccessJit(tier, scoped)`）へ組み入れ、既存の遷移規律の下に
  置く。却下（machine外state案）。
- **active run中のJIT抑制**（run-in生成でのprompt回避）: run-in生成は検出後 `Selecting` から
  到達でき、process初回compositionになり得るため抑制するとuniform ruleに穴が開く。
  promptは選択凍結に抵触しない（dialog Backはdialog dismissalであり、run中断を
  発火させない実装を要求する — 下記Failure handling）。却下（抑制しない）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`（新規） | 要求機会gate（`Available/Reserved/Consumed` 提示権state、`evaluate`/`markPresented`/`release`、process-scoped singleton）・JIT dialog composable・復帰時bounded re-read helper・`ON_RESUME` 観測helper | organizer UI層の共有表現。run / exchangeの両hostから使え、composer・controllerを触らない |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | constructorへgate注入（既定は常に `Proceed` の不変gate）。`State.AwaitingUsageAccessJit(runId, selection, isOwner)` 追加、`runComposedPhase` 入口（cancel gate前）のpause判定、`continueAfterUsageAccessGate()`（lock内commit→単回composed phase）、3経路の `CAPTURE`/`Capturing` commitを入口へ集約、`cancel()` 対象状態への追加と未提示時 `release` 連携。javadoc | composition直前のpause pointはrun state machineのみが持てる（選択面と同一の待機点概念）。単一入口のchoke pointで3経路を構造的に網羅し、TOCTOU・二重継続をstate遷移の原子性で排除する |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt` | `AwaitingUsageAccessJit` → `PREPARATION`（T-09）のface mapping entry追加 | #369が確立した決定的face写像の一貫性。新stateが8ユーザー状態の外に出ない保証 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `AwaitingUsageAccessJit` 観測時の共有dialog提示（owner時のみ。`markPresented` + 解決時 `continueAfterUsageAccessGate`） | run state観測点が唯一のdialog host。開始row群の個別変更は不要（pauseがmachine内のため） |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `generate` / `generateScoped` 先頭のgate判定、`ExchangeScreen.AwaitingUsageAccessJit(tier, scoped)` variant追加、dialog提示（owner時）と単回resume、close/別遷移時の無効化と未提示 `release` | 依頼生成trigger点。保留生成のidentity/lifetimeを既存state machineの遷移規律の下に置き、stale callbackの生成再開を構造的に排除。置換確認の後にJIT要求が来る順序を既存flowの自然な拡張で実現 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | **無実装変更**（状態表示・遷移・`ON_RESUME`再読取は現行どおり）。javadocの文言要件参照を更新 | copy改訂の対象はresourceであり、実装は要件の参照先が変わるのみ |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | JIT dialog文言（title/body/遷移/続行/遷移失敗。format resource、§7.3の3要素 + 任意性 + privacy修飾）新規。T-06 row文言（`organizer_personalization_usage_access_label/_granted/_not_granted`、EN/ja）を§7.3準拠へ改訂 | spec 123 AC-4/AC-5・spec 161 LQA規約。JIT（JIT-AC-02）とT-06（JIT-AC-06）双方のcopy契約。最終文言は実装PR contract commitで確定し意味要素checklistをPR記録 |
| `specs/203-usage-implicit-preference-signals/spec.md` | amendment: U-2改訂（常設row＋初回signal読み取り直前のJIT要求1回）、Permission and fallback behavior表の「opt-in (初回)」行のrationale要件をprivacy修飾形へ更新 + JIT要求行追加、JIT受入条件（AC-17以降）追加、change history | Issue本文「Spec」節・disposition §3.10「doc変更: spec 203改訂（#371のPR）」・§4.1 supersession map。local-only無条件表現の修正（review指摘2）を含む |
| `tests/unit/app/lawnchair/organizer/ui/`（新規/更新） | `UsageAccessJitGate` のunit test（提示権state遷移: 競合で `Present` は1つ、`Wait` の保留、`markPresented`/`release` の冪等・owner一致、提示後action不成立でも消費済み、付与済み初回 `evaluate` で消費）+ `ManualOrganizationRunTest` へpause oracle追加（3経路 × gate decision、**CAPTURE commit移動後の順序oracle更新**、`Busy`・journal無event・lease exactly once release・二重継続で `RUN_STARTED` 1回、既存oracleは既定gateで無編集green — CAPTURE位置の更新分は理由を記録）+ bounded re-readの決定的oracle（`false→true`、上限到達。注入clock/predicate）+ `ManualOrganizationFaceTest` の対応表1行 | gate logic・2段階orchestration・再取得timingのinterface test |
| `tests/organizer-instrumentation/`（新規/更新） | JIT要求flow（run開始・選択確認・D-06直行・依頼生成・run-in経路）、1回限り、断って続行、**付与して続行（production predicateでGRANTED観測後にcomposition開始）**、T-06 copy回帰（EN/ja）、dialog Back helper、選択中断の否定oracle、**run/exchange競合で提示1つ・2つ目は解決まで不進行**、**JIT保留中close後の遅延callbackが生成を再開しない**、exchange結合oracle（`T-07 AI → [置換確認] → JIT要求 → 生成 → 送信前確認`。#372 merge後のre-entryで再検証） | spec Test oracle表（JIT-AC-01〜06, 08, 09） |

source implementation・build設定・dependencyの変更は本Issueの実装PRのscopeであり、
本plan（spec/plan整備task）では行わない。

## Migration and recovery

- persistent state / schema / preference formatの変更は**なし**。migration不要。
  Launcher layout DB・`favorites` への接触なしのためホームレイアウト安全規約の適用対象外。
- failure中のrollback: JIT gateは表示flowのみでzero-write。保留操作はprocess-localであり、
  どの時点でも破棄は無害（未開始の操作は存在しなかったのと同じ。run側のpauseは
  `RUN_STARTED` 以前のためcancel/dismiss/process死のいずれもjournal eventを残さず、
  RUN leaseは現行経路で正確に1回解放される）。
- release rollback / downgrade: PR revertでJIT要求が消え常設rowのみへ戻る
  （disposition §7.3「downgrade: 従来の常設rowのみ」。残留物なし）。
- backup/restore: 影響なし（latchは対象外、usage accessの付与状態自体はsystem管理のまま）。

## Failure handling

- **設定画面が解決できない**（`ActivityNotFoundException`）: catchしてcrashさせない。
  dialogは閉じず、遷移できなかった旨を要求面上で分かる形で示し、「続行」の選択肢を残す
  （単一のnormative経路。自動的な代替遷移・dialogの自動closeは行わない。
  spec scenario「設定画面が開けない環境でも壊れない」どおり）。
  書込み・永続化なし。
- **付与直後のapp-op伝播遅延**: 復帰後の判定はbounded re-read（注入可能な
  clock/predicate/delay、固定sleep禁止）によりGRANTEDの観測を待つ。上限内に観測できれば
  その試行は付与済みで読まれる（TO-BE §6.4どおり）。上限まで観測できない場合は
  未付与でcompositionを続行し、次回compositionから付与済みになる — 既知限界として
  spec/PRに記録し、新規の再試行機構は導入しない。
- **dialog表示中 / 設定遷移中のprocess死**: 保留操作と機会stateが消えるのみ。
  再訪processでは付与状態で判断される（spec scenarioどおり）。
- **run pause中のdialog Back**: dialogのBackはdialog dismissal（= 断って継続）として消費し、
  `ManualOrganizationBackHandler`（run中断）へ漏出させてはならない。pause state自体は
  明示cancel（T-09面の中断row）でのみ解消される。Back优先順位のtestをinstrumentationで
  要求する。
- **run-in生成経路でのdialog Back**: 同上。選択凍結中の意図しないrun中断の防止。
- **exchange保留中のflow退場**: `close()`・別generation遷移・host navigation破棄で
  `AwaitingUsageAccessJit` は退場し、保留生成は破棄される（未提示なら `release`）。
  stale screen値を持つ遅延callbackは生成を再開しない（単回・現行screen一致のresume契約）。
- **pause中にhost面がdisposeされた場合**（画面離脱）: 既存の `dismiss()` 契約
  （active operationのcancel）によりrunは `Cancelled` へ戻る。機会はdialog提示済みなら
  消費済み、未提示（`Reserved`）なら `release` されて未消費のまま破棄される。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| JIT-AC-01 | instrumentation: app-op未付与（shell `appops set`）でrun開始 → 検出 `Unavailable` 経路はcomposition直前にJIT dialog表示assert → 解決後にrun state遷移。候補あり経路は選択確認直前まで非表示＋選択中断で不表示（否定oracle）。D-06直行経路・idle生成・run-in生成の同様assert。付与済みの否定的観測 | organizer instrumentation lane（API 36 emulator） |
| JIT-AC-02 | unit: 新規stringの `values/` / `values-ja/` 存在とplaceholder一致（spec 123 AC-5方式）。+ 実装PR contract commitでの最終文言と意味要素checklist（3要素・任意性・raw/bucket/送信前確認）のPR記録 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| JIT-AC-03 | unit: 注入predicate/clockによるbounded re-readの決定的oracle（`false→true` 観測で続行、上限到達で未付与継続。固定sleep不使用）。instrumentation: dialogの遷移操作 → shell `appops set ... allow` → **production predicateでGRANTED観測後にcomposition開始**。composer側は `PersonalizationCompositionTest` の付与済み経路で担保 | unit gate + organizer instrumentation lane（probe testと同一pattern） |
| JIT-AC-04 | instrumentation: 断って続行 → runがpreviewまで進行（`NotReady`不発生）。遷移失敗注入（`ActivityNotFoundException`）→ dialog維持＋「続行」機能→Unavailable継続のexact oracle。unit: `PersonalizationCompositionTest` 等の既存composer suiteが無編集でgreen（AC-11/AC-13回帰） | unit gate + instrumentation lane |
| JIT-AC-05 | unit: gate提示権state遷移（未消費→提示で消費、提示後action不成立でも消費済み、付与済み初回 `evaluate` で消費、composition不到達操作は非消費、**競合で `Present` は1つ・`Wait` は保留・提示前cancelで解放**）。unit: run resumeの二重発火で `RUN_STARTED`/composition各1回。instrumentation: 2回目の開始/生成でdialog不表示（同一process内）＋run/exchange競合で提示1つ | unit gate + instrumentation lane |
| JIT-AC-06 | T-06の既存instrumentation（状態操作）が無編集でgreen + row copy（EN/ja）のresource test + contract commitでの意味要素checklist記録 + diff review（状態管理契約の無変更） | instrumentation lane + unit gate + PR diff review |
| JIT-AC-07 | specs/203-.../spec.mdのdiff review（U-2・表のrationale要件更新＋JIT行・AC・change historyのamendmentのみ。snapshot/provenance契約節の無変更） | PR diff review |
| JIT-AC-08 | diff review（manifest permission・persistent store・diagnostics eventの無変更）+ failure path unit/instrumentation（遷移失敗、pause中cancelのjournal無event、**pause中RUN lease保持（2回目start `Busy`）・cancel/dismissでのlease exactly once release**、process死模擬、**JIT保留中close後の遅延callbackが生成を再開しない**） | PR diff review + unit gate |
| JIT-AC-09 | Compose semantics assertion（dialog name/role/state、focus移動と復帰）、200% font scale到達test、emulator screenshot（light/dark × ja/default） | organizer instrumentation lane + 手動evidence記録 |

含めるべき観点: unit/contract（gate提示権state・run pause orchestration・bounded re-read）、
integration（composer回帰・生成順序）、UI/accessibility（dialog操作・font scale・TalkBack）、
failure injection（遷移失敗・process死・pause中cancel・伝播遅延）・競合（同時trigger・
二重resume・stale resume）。

## Documentation updates

- [x] spec status/history: 本spec（`specs/371-usage-access-jit-request/spec.md`）を
      review revisionとして更新。実装PRでspec 203をamendmentし（JIT-AC-07）、最終PRで
      本specを `implemented` へ更新する。
- [ ] spec 203 amendment（実装PR。U-2・表のrationale要件更新＋JIT行・AC・change history）
- [ ] CONTEXT.md: 本PRでは変更しない（JIT要求・材料の語彙は#365が確定済み）
- [ ] DESIGN.md: 変更不要の見込み（UI層のgate追加であり、module構造・seam・不変条件の変更なし）。
      実装PRで再確認する
- [ ] ADR: 作らない（「変更が高コスト/理由がコードから分からない/実際の選択肢があった」の
      3条件に該当する判断はなかった。one-shot・process-localの規則はspecとTO-BE/dispositionに
      記録済み。pause pointの導入は選択面と同一の既存待機点概念の適用であり、提示権の
      原子性はgate内部の実装詳細としてspec/planに記録済み）
- [ ] AGENTS.md: 変更なし

## Execution checklist

- [ ] Current behavior reproduced（常設rowのみでJIT要求が存在しないこと。hub T-06）
- [ ] Gate unit tests fail for the missing behavior（提示権state・`evaluate`/`markPresented`/
      `release`・提示時消費・run pause分岐・bounded re-read）
- [ ] Minimal implementation completed（gate + dialog + run pause point + exchange
      `AwaitingUsageAccessJit` variant + T-06 copy改訂）
- [ ] Migration/recovery verified（該当なし — zero-write・persistent変更なしの確認）
- [ ] Full relevant verification completed（unit gate、instrumentation lane、a11y evidence、
      `./gradlew spotlessCheck`、CI `final-status`）
- [ ] PR evidence and remaining risks recorded（spec 203 amendment diff、T-06 copy回帰＋
      意味要素checklist、Unverified areasの残項目）

## Dependencies / risk

- **契約上の依存**: #367（merge済み。T-06がhub材料面に存在）。#365（merge済み）。
  ともに充足済み。
- **実装baseline**: `13c95eafe6`（#370実装merge済み。#369実装もmerge済み）をbaselineとする。
  本planの行番号は当該mainで実検証済みである。
- **#372（AI相談統合、open）との並行**: exchange holder・Back処理・
  `ManualOrganizationPreferences` は共有変更面である。**どちらが先にmergeしても後続側が
  re-entryして当時のcurrent mainを再読し、統合site（`generate`/`generateScoped` 先頭のgate、
  `AwaitingUsageAccessJit` variant、Back handler）と結合oracle
  （`T-07 AI → [置換確認] → JIT要求 → 生成 → 送信前確認` の順序、JIT dialog Backの
  screen Back非漏出、保留中close後の遅延callback無効）を再検証する**。
  本specは#372の最終実装形状を先取りしない（契約は「signalを読む操作」に結合）。
- **並行**: #368（merge済み）/ #369（merge済み）と契約上は並行可能（disposition §8）。
- **risk**:
  - run state machineへのpause state追加とCAPTURE commitの入口集約は、#369が表示統合として
    守った「内部state machine不変」（spec 369 RD-3、そのスコープは#369自身の変更に対する
    制約）を#371が所有する形で拡張するものである。#369のface mapping test・state機器oracle・
    CAPTURE publish順序oracleへの影響分は本Issueの実装PRで更新し、理由
    （pause point導入とCAPTURE commitの入口集約）をPRに記録する。pauseは `RUN_STARTED` 以前の
    pre-journal区間であり、#369の「pre-composed phase はrun-mode-bearing eventを持たない」
    契約および入口cancel gate（RD-6）のactive再確認とは整合する。
  - gate提示権のowner管理（run operation / exchange holder）の漏れ — release忘れは
    「提示されない要求機会の永続消費」として現れるため、cancel/dismiss/closeの全経路で
    未提示reservationの解放をunit/instrumentationで検証する。
  - dialog Backがrun中断handlerへ漏出する（pause/run-in経路） — instrumentationで明示検証。
  - bounded re-readの上限値の端末分布 — 上限超過時は既知限界（未付与で継続）として
    spec化済み。PRでevidenceを記録。
  - composition起点の網羅（run側は `runComposedPhase` 入口のchoke pointで構造的に網羅。
    exchange側は `generate`/`generateScoped` の2点 + import→run再構築はrun側に帰着）—
    gateはmachine内/holder先頭に置くため個別call siteのwrapは不要。
    実装PRのdiff reviewでexchange 2点がgateを通ることを確認する。
- **explicitly unverified areas**:
  - 実device/OEM matrixでの `ACTION_USAGE_ACCESS_SETTINGS` の解決可否（unsupported pathは
    spec化したが、device evidenceは未取得）。
  - 付与直後のapp-op伝播timingの実device分布（bounded re-readの上限値の根拠。probe evidenceは
    reader追従の検証のみ）。
  - onboarding momentでのJIT要求表示のproduct受容（spec Open questions 1。owner review待ち）。
  - #372実装merge後の共有面の最終形状（re-entry規律の下で後続側が再読・再検証する）。
