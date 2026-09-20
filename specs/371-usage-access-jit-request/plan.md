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
  同等のidentity/lifetime契約が必要である（review指摘4）。
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
  既存testは「要求機会が常に解決済み（常に `Proceed`）」の既定gateで現行挙動を維持する
  （下記Design）。`CAPTURE` commit位置の移動（下記Design）により、#369で追加された
  「confirmSelectionがCAPTUREをpublishしてからcomposed phase」という経路の観測順序oracleは
  本Issueの実装PRで更新し、理由（CAPTURE commitのcomposed phase入口への集約）を記録する。
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationFaceTest.kt`（#369新設）:
  face mapping純関数のtable-driven test。`AwaitingUsageAccessJit` /
  `ResumingUsageAccessJit` のface entry追加時に対応表へ行を追加する。
- `ManualOrganizationRunFaceTrace`（`ManualOrganizationFace.kt:44`、#370が追加した
  test-only観測seam）: faceのcommit値を決定的に記録するpattern。JIT要求の
  「選択中断で要求面を経由しない」否定oracle・「resume経路でCAPTUREが入口gateより前に
  publishされない」oracle等で同一patternのtest-only observerを併用できる。
- T-06の回帰surface: #366/#367のinstrumentation（toggle↔preference一致、resume再読取）。
  copy改訂で文言assertが変わる場合の更新は本Issueが行う（状態操作oracleは無編集green）。

## Design

### Modules and interfaces

- **新規: process-scopedな要求機会gate（UI層、`app.lawnchair.organizer.ui`）**。
  小さなclass（仮称 `UsageAccessJitGate`）とCompose dialog/helper（仮称
  `UsageAccessJitRequestUi`）を1 fileに置く（例:
  `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`）。
  - `UsageAccessJitGate` は `isGranted: () -> Boolean` をconstructor注入とし、内部に
    process-localな機会stateを持つ。stateは **`Available` → `Reserved(owner)` →
    `Presented(owner)` → `Resolved`** の原子的な遷移のみであり（全methodは同一monitor下で
    動作）、**「提示による消費」と「解決による待機者解放」を同一遷移に潰さない**
    （再review指摘1）:
    - `evaluate(owner): JitDecision` — composition trigger点で呼ぶ。戻り値は3値:
      - `Proceed` — `Resolved`（消費済み・解決済み）、または `Available` かつ付与済み
        （付与済みの場合は**その場で `Available → Resolved` へ遷移** — 要求不要として
        機会を消費し、待機者がいれば直ちに解放される。spec scenario「初回trigger時に
        付与済み」）。callerは即座にcompositionへ進んでよい。
      - `Present` — `Available` から `Reserved(owner)` への遷移に**原子的に成功**した
        callerだけが受け取る。callerは要求を保留し、dialogを提示してよい唯一の主体である。
      - `Wait` — `Reserved` / `Presented` の場合（他のownerが進行中）。callerは要求を
        保留し、dialogを提示せず、当該要求の**解決**までcompositionへ進まない。
    - `markPresented(owner)` — dialogを**提示した時点**にownerが呼ぶ。
      `Reserved(owner)` → `Presented(owner)`。この時点で**再提示権は消費される**
      （以降 `Available` へ戻る遷移は存在しない。提示後のcancel/失敗でも復活しない）が、
      **待機者はまだ解放されない**。owner不一致・既に `Presented`/`Resolved` の場合は
      no-opの冪等。提示後の操作の成否・Busy・遷移失敗とは独立である（review指摘4）。
    - `resolve(owner)` — ユーザーの応答確定（「続行」/ Back / 復帰後のbounded re-read完了 /
      遷移失敗時の「続行」）でownerが呼ぶ。`Presented(owner)` → `Resolved`。
      **ここで初めて待機者が解放される**。owner不一致・`Resolved` 済みはno-opの冪等。
    - `release(owner)` — **提示する前の** `Reserved(owner)` でのみ有効。
      `Reserved(owner)` → `Available`（機会は未消費のまま残り、次のtriggerで要求される。
      解放後の再獲得は原子的遷移に成功した1つのcallerのみ）。`Presented` 以降は
      解放できない（1回限り契約の維持）。
    - **owner破棄時の規則（2nd再review指摘1 — liveness保証）**: owner操作の破棄
      （run cancel/dismiss、exchange close/navigation破棄）における機会への作用は
      state別に固定する:
      - `Reserved(owner)` → `release(owner)`（未提示のため機会は未消費のまま）。
      - `Presented(owner)` → **放棄解決**として `resolve(owner)` をexactly once実行する
        （gateのみ解決され待機者は解放される。破棄されたownerの保留操作は再開されず、
        JIT要求は再表示されない）。これにより `Presented` が孤児化して待機者を
        永久に塞ぐことが構造的にない。遅延して到着するstale owner callbackは
        二重解決・二重再開を起こさない（解決は冪等、再開はtoken/state一致契約）。
      - `Resolved` → 無作用。      なおsystem settingsへの遷移・復帰はowner破棄ではない（ownerは生存しており、
      既存の「settings遷移中」契約で区別される）。
  - **観測seam（再review指摘2）**: gateは `state: StateFlow<JitGateState>`（revision counter
    付きの不変snapshot）を公開し、`Wait` で保留されたhostはこれをcollectして解決を
    **決定的に観測する**（polling・偶然の再compositionを契約にしない）。
    待機の観測は **当該要求のattempt identityにbind** され（どのreservationの変化を
    待っているかをidentity付きで保持）、staleな通知（古いattemptのstate変化）が
    新しいpendingを起こさない。解決（`Resolved`）を観測した待機者は再評価され
    正確に1回進行する。解放（`Available` 復帰）を観測した待機者は再評価で `Present` を
    競い、原子的遷移に成功した1つだけが提示する。
  - **attempt identity（再review指摘3）**: owner identityは**試行ごとに一意**である。
    run側はrun operation（RunIdで一意）、exchange側は生成attemptごとに発行する一意token
    （monotonic採番。holder固定値を使わない）。`markPresented` / `resolve` / `release` と
    待機観測のすべてが当該attemptのidentityにbindされ、古いattemptの操作・通知が
    新しいattemptへ作用しない。
  - 競合契約: 2つの起点が近接して `evaluate` しても、`Present` を受け取るのは正確に1つであり、
    もう一方は `Wait` で保留される。ownerの解決（`Resolved`）後に保留中の起点は
    再評価され `Proceed` となるため、2つ目のdialogは存在し得ず、提示中・設定遷移中も
    保留起点のcompositionは0回である。
  - production instanceはprocess-scoped singleton（`ManualOrganizationModule` と同様の
    `get(context)` pattern、predicateは既存 `UsageAccess.isGranted` に委ねる）であり、
    run machineとexchange holderの**双方から同一instanceを共有する**（機会はprocessで1回）。
    unit testは注入predicateで直接構築する（仮想interfaceは作らない — AGENTS.md設計規約）。
    run構築時にgateを注入しない既存test互換のため、`ManualOrganizationRun` の
    constructor既定値は「常に `Resolved`（常に `Proceed`）」の不変gateとする。
- **統合点1: run machineのcomposed phase入口にpause point（2段階orchestration。review指摘1の解消）**。
  `ManualOrganizationRun` にgateをconstructor注入し、**`runComposedPhase` の入口**
  （#369のcancel gate／journal openの前）に要求機会の判定を置く。3つのcomposition経路
  （検出 `Unavailable` 継続・D-06の0件内部継続・選択確認）がいずれもこの単一入口を通るため、
  経路の漏れが構造的に発生しない。選択面（`State.Selecting`）と同一の「UI待機点」の概念であり、
  composer・`PersonalizationSignalSnapshotSource`・admission契約（single-active-operation）は
  触れない。
  - pause判定の構造: 入口でまずactive再確認（cancel勝利時はgateに触れずreturn）→
    `gate.evaluate(operation)` を判定。`Proceed` なら現行どおりcancel gate → composition
    （付与済み・解決済み環境では新stateへ到達しない。既存test互換）。`Present` または
    `Wait` の場合のみ `State.AwaitingUsageAccessJit(runId, selection, isOwner = decision == Present)`
    をpublishしてreturn。**journal は開かない。RUN leaseは保持される**（2回目のstartは
    既存の `beginOperation` gateにより `Busy`。review指摘3の事実関係）。
  - **CAPTURE commitの入口集約と二重resume防止の分離（再review指摘4）**: 現行
    `confirmSelection()` / `continueWithEmptySelection()` が `runComposedPhase` 呼び出し**前**に
    `preparationPhase=CAPTURE` + `State.Capturing` をpublishするのをやめ、3経路とも
    ユーザー可視のcapture開始commit（`preparationPhase=CAPTURE` + `State.Capturing` +
    `journalStarted` + `RUN_STARTED`）を `runComposedPhase` 内の既存RD-6 lock区間で
    **authoritativeに**行う（phase-before-state順序は当該lock section内で維持）。
    JIT解決後のresume経路でも同じである: `continueAfterUsageAccessGate()` のlock内では
    **二重resume防止のclaimのみ**を行い（`AwaitingUsageAccessJit` → 内部state
    `State.ResumingUsageAccessJit(runId, selection)` への原子的遷移。ユーザー可視の
    `Capturing`/`CAPTURE` publishは行わない）、lockを抜けてから `runComposedPhase` を
    正確に1回呼ぶ。2回目以降の呼び出しはstateが既にAwaiting/Resuming以外のためno-opであり、
    `ON_RESUME` と継続callbackの近接・二重発火でも `RUN_STARTED` / compositionの二重実行は
    構造的に発生しない。`ResumingUsageAccessJit` は `RUN_STARTED` 以前（pre-journal）であり、
    face mappingでは `PREPARATION` に写像し、cancel可能集合に含まれる（cancelされた場合は
    `runComposedPhase` 入口のactive再確認が勝ち、journalは開かれない）。
  - 新state: `State.AwaitingUsageAccessJit(runId, selection: List<CandidateTarget.AppKey>?,
    isOwner: Boolean)` と `State.ResumingUsageAccessJit(runId, selection)`（内部claim用）。
    ユーザー向け8状態（TO-BE §8.1）には現れない。face mapping
    （`ManualOrganizationFace.kt`）へは両stateとも `PREPARATION`（T-09準備中。dialogは
    その上のmodal overlay）のentryを追加する。
  - dialog host（run側）: face host（#369実装後の `ManualOrganizationPreferences` 側の
    run state観測点）が `AwaitingUsageAccessJit` を観測したら、`isOwner == true` の場合のみ
    共有dialogを提示する（提示時に `gate.markPresented(operation)`）。
    `isOwner == false`（`Wait` で保留されたrun）の場合はdialogを提示せず、gate stateの
    観測seamで解決を待つ: `Resolved` を観測したら `continueAfterUsageAccessGate()`、
    `Available` への解放を観測したら再評価で `Present` を獲得できた場合に提示する。
    ownerの解決操作（「続行」/ Back / 復帰後のbounded re-read完了 / 遷移失敗時の「続行」）は
    `gate.resolve(operation)` を呼んだうえで `continueAfterUsageAccessGate()` を呼ぶ。
    「設定を開く」は `ACTION_USAGE_ACCESS_SETTINGS` の `startActivity` を
    `ActivityNotFoundException` をcatchして呼ぶ（失敗時の契約は下記Failure handlingの
    単一normative経路）。復帰後の再取得は**bounded re-read**とする（下記）。
    個々の開始row・onboarding `admitReview`・import→run（`ExchangeFlowUi.kt:690`）は
    **無編集**でよい（pause pointがmachine内のため）。
  - onboarding経由も同一経路で扱われる: `start(ONBOARDING_PROPOSAL)` → 検出 →
    composed phase入口のpause → run面hostがdialogを提示。onboarding操作時点で
    要求を出さない（spec scenarioどおり）。
  - cancel/dismiss連携: `cancel()` のcancel可能状態集合へ `AwaitingUsageAccessJit` /
    `ResumingUsageAccessJit` を追加する。cancel/dismiss時のgate連携は
    **owner破棄時のstate別規則**に従う: gate stateが `Reserved`（未提示）なら
    `gate.release(operation)`、`Presented`（提示済み）なら放棄解決として
    `gate.resolve(operation)` をexactly once実行する（当該runの操作は再開されず、
    待機者のみ解放される）。`Resolved` なら無作用。pause/claimは `RUN_STARTED` 以前であるため、
    pause中のcancelはjournal eventを発生させず、leaseは現行経路で正確に1回解放される。
- **統合点2: 依頼生成のholder gate（state machine本体へ組み入れ。review指摘4・再review指摘3の解消）**。
  `ExchangeFlowStateHolder` の `generate` / `generateScoped` の先頭で生成attemptを
  発行し（**monotonicなattemptToken**）、`gate.evaluate(attemptToken)` を判定する。
  `Proceed` なら現行どおり生成。`Present` / `Wait` の場合のみ
  **`ExchangeScreen.AwaitingUsageAccessJit(attemptToken, tier, scoped)`** を
  state machine本体へpublishする（`ExchangeScreen` の既存data class群と同一のvariant。
  「machine外の独立state」は採らない）。payloadには**attemptTokenを含め**、
  保留生成のidentityをattemptにbindする。
  - 単回resume: 生成の再開は「**現行screenが同一attemptTokenの
    `AwaitingUsageAccessJit` である場合**に限り」1回だけ適用される（stale tokenを持つ
    遅延callbackはno-op。close → 同じtier/scopedで再生成した場合も、古いcallbackの
    tokenと現行screenのtokenが一致しないため再開しない）。
  - 無効化: `close()`（`Closed` への遷移）、`ReplacementConfirm` 等への別遷移、
    host navigation破棄のいずれでも `AwaitingUsageAccessJit` は退場し、保留生成は破棄される。
    退場時のgate連携はstate別規則に従う: gate stateが `Reserved`（未提示）なら
    `gate.release(当該attemptToken)`、`Presented`（提示済み）なら放棄解決として
    `gate.resolve(当該attemptToken)` をexactly once実行する（当該attemptの生成は
    再開されず、待機者のみ解放される）。`Resolved` なら無作用。
  - dialog host（exchange側）: hosting composableが `AwaitingUsageAccessJit` を観測したら
    gate stateを確認して `Reserved(自attempt)` の場合のみ共有dialogを提示
    （提示時に `markPresented(attemptToken)`）。`Wait` 状態（他attemptが進行中）の場合は
    観測seamで解決を待つ。解決操作で `resolve(attemptToken)` を呼んだうえで、
    現行screenのtoken一致を確認して `generate` / `generateScoped` を再kickoffする。
    置換確認（`ReplacementConfirm`）は既存flowのまま先行するため、「置換確認 → JIT要求 →
    生成」の順序が自然に成立する。run-in生成（`generateForSelection`）も同一gateで
    扱われる。dialogのBackはdialog dismissalとして消費し、run側のBack handler
    （選択凍結中のrun中断）へ漏らさない。
- **統合点3: 復帰時の付与状態再取得 — bounded re-read（review指摘2・再review指摘5の解消）**。
  `UsageAccessJitRequestUi` のhelper内に「settings遷移中」のprocess-local flagを置き、
  復帰（`ON_RESUME`）時に単発の再読みで判定せず、注入可能な **predicate/clock/delayによる
  bounded re-read**で `UsageAccess.isGranted()` を再評価する:
  - GRANTEDを観測できた時点で遅延されていたcompositionを再開する（付与済みで読まれる）。
  - 上限まで観測できなかった場合は未付与としてcompositionを続行する
    （次回compositionから付与済み。既知限界としてspec/PR記録）。
  - 固定sleepは使用しない。unit testは注入した `false → true` のpredicate列と
    virtual clockでの上限境界の両経路を決定的に検証する。instrumentationは
    「上限超過後に必ずfallbackする」ことを確認する（実時間の厳密一致は要求しない）。
  - **最大待機時間は0.5秒以上2秒以内のレンジとして本specで拘束されており
    （GRANTED観測時は観測直後に短縮）、実装PRのcontract commitで範囲内の具体値を
    確定し、spec change historyとPR evidenceへ記録する**（observable behavior）。
    probe evidenceの1秒待機を実務上限の根拠とし、レンジ内で決める。
  - T-06 rowの既存 `ON_RESUME` 再読取（単発・表示用途）は現行契約のまま無変更である
    （状態表示のtimingと、composition再開の判定は別契約である）。
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
2. trigger点で `gate.evaluate(owner)` を判定（run側は `runComposedPhase` 入口、ownerは
   run operation。exchange側は `generate`/`generateScoped` の先頭、ownerは生成attemptの
   attemptToken）。
3. `Proceed`（解決済み or 付与済み→その場で解決扱いの消費）なら即座に従来どおりcomposition実行。
4. `Present` / `Wait` なら要求を保留する（run側: pause state / exchange側:
   `ExchangeScreen.AwaitingUsageAccessJit(attemptToken, ...)`）。`Present` を得たhostが
   共有dialogを提示して `markPresented` で再提示権を消費する。`Wait` のhostは提示せず、
   観測seamで解決を待つ（提示中・設定遷移中も進まない）。
5. 解決: (a) 「設定を開く」→ intent発火 → 復帰後のbounded re-readでGRANTED観測後に
   保留composition再開（上限超過なら未付与で再開）。(b) 「続行（断る）」/ system Back /
   dismiss → 保留操作の継続。いずれも `resolve(owner)` を呼んだうえで継続し、
   待機していた起点がここで初めて進行する。
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
  `evaluate()` の `true` 返却と提示の間に窓があり、2 callerが双方要求を得て2つの保留操作を
  作り得る（TOCTOU）。提示権のreservationにより構造的に排除する。却下。
- **提示（markPresented）を `Consumed` に直結し、待機者の解放も同一stateで行う**
  （3rd review前の設計。再review指摘1で拡張）: ownerがdialogを出した瞬間に待機者が
  進行でき、ownerが設定画面へ遷移している間に未付与のcompositionが先に走る。
  「提示による消費」と「解決による解放」を `Presented → Resolved` の別遷移に分離して
  排除する。却下。
- **reservation解決をpolling / Compose再compositionに依存させて観測する**
  （再review指摘2で排除）: `Wait` が決定論的に解放される保証がない。gate stateの
  決定的な観測seam（`StateFlow` + revision、attempt identity付き待機）を契約化。却下。
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
  （`ExchangeScreen.AwaitingUsageAccessJit(attemptToken, tier, scoped)`）へ組み入れ、
  既存の遷移規律の下に置く。却下（machine外state案）。
- **exchange側のgate ownerをholder固定identityにする**（3rd review前の設計。
  再review指摘3で拡張）: 古い `release` / `markPresented` が後から作られた新reservationへ
  作用し得る。ownerは生成attemptごとのmonotonic tokenにbindする。却下。
- **提示済みownerの破棄を「消費済みなのでgateに触れない」で済ませる**（3rd review前の
  設計。2nd再review指摘1で拡張）: `Presented(owner)` が解決されないまま残り、
  待機者が永久に `Wait` するliveness違反（process再起動まで復旧不能）になる。
  owner破棄時はstate別に作用を固定し（未提示→解放、提示済み→放棄解決、解決済み→無作用）、
  barrierが必ず解決されることを構造的に保証する。却下。
- **active run中のJIT抑制**（run-in生成でのprompt回避）: run-in生成は検出後 `Selecting` から
  到達でき、process初回compositionになり得るため抑制するとuniform ruleに穴が開く。
  promptは選択凍結に抵触しない（dialog Backはdialog dismissalであり、run中断を
  発火させない実装を要求する — 下記Failure handling）。却下（抑制しない）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`（新規） | 要求機会gate（`Available/Reserved/Presented/Resolved` 提示権state、`evaluate`/`markPresented`/`resolve`/`release`、attempt identity bind、`StateFlow` 観測seam、process-scoped singleton）・JIT dialog composable・復帰時bounded re-read helper・`ON_RESUME` 観測helper | organizer UI層の共有表現。run / exchangeの両hostから使え、composer・controllerを触らない。「提示による消費」と「解決による解放」の分離と決定的観測が競合契約の要 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | constructorへgate注入（既定は常に `Proceed` の不変gate）。`State.AwaitingUsageAccessJit(runId, selection, isOwner)` と内部claim用 `State.ResumingUsageAccessJit(runId, selection)` 追加、`runComposedPhase` 入口（cancel gate前）のpause判定、`continueAfterUsageAccessGate()`（lock内はclaimのみ→単回composed phase）、3経路のユーザー可視capture commit（`CAPTURE`/`Capturing`/journal/RUN_STARTED）を入口gate区間へ集約、`cancel()` 対象状態への追加とowner破棄時のstate別gate連携（`Reserved`→`release`、`Presented`→放棄解決の `resolve`、`Resolved`→無作用）。javadoc | composition直前のpause pointはrun state machineのみが持てる（選択面と同一の待機点概念）。単一入口のchoke pointで3経路を構造的に網羅し、二重resume防止（内部claim）とcapture可視commit（入口gate区間）を分離して自己矛盾を排除。放棄解決でbarrierのlivenessを保証 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt` | `AwaitingUsageAccessJit` / `ResumingUsageAccessJit` → `PREPARATION`（T-09）のface mapping entry追加 | #369が確立した決定的face写像の一貫性。新stateが8ユーザー状態の外に出ない保証 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `AwaitingUsageAccessJit` 観測時の共有dialog提示（owner時のみ。`markPresented` + 解決時 `resolve`/`continueAfterUsageAccessGate`）、gate state観測seamのcollect | run state観測点が唯一のdialog host。開始row群の個別変更は不要（pauseがmachine内のため） |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `generate` / `generateScoped` 先頭のattempt発行とgate判定、`ExchangeScreen.AwaitingUsageAccessJit(attemptToken, tier, scoped)` variant追加、dialog提示（自attemptの `Reserved` 時のみ）とtoken一致単回resume、close/別遷移時の無効化とowner破棄時のstate別gate連携（`Reserved`→`release`、`Presented`→放棄解決の `resolve`、`Resolved`→無作用） | 依頼生成trigger点。保留生成のidentity/lifetimeを既存state machineの遷移規律とattempt token bindの下に置き、stale callback・stale gate操作の生成再開/消費/解放を構造的に排除。放棄解決でbarrierのlivenessを保証。置換確認の後にJIT要求が来る順序を既存flowの自然な拡張で実現 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | **無実装変更**（状態表示・遷移・`ON_RESUME`再読取は現行どおり）。javadocの文言要件参照を更新 | copy改訂の対象はresourceであり、実装は要件の参照先が変わるのみ |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | JIT dialog文言（title/body/遷移/続行/遷移失敗。format resource、§7.3の3要素 + 任意性 + privacy修飾）新規。T-06 row文言（`organizer_personalization_usage_access_label/_granted/_not_granted`、EN/ja）を§7.3準拠へ改訂 | spec 123 AC-4/AC-5・spec 161 LQA規約。JIT（JIT-AC-02）とT-06（JIT-AC-06）双方のcopy契約。最終文言は実装PR contract commitで確定し意味要素checklistをPR記録 |
| `specs/203-usage-implicit-preference-signals/spec.md` | amendment: U-2改訂（常設row＋初回signal読み取り直前のJIT要求1回）、Permission and fallback behavior表の「opt-in (初回)」行のrationale要件をprivacy修飾形へ更新 + JIT要求行追加、JIT受入条件（AC-17以降）追加、change history | Issue本文「Spec」節・disposition §3.10「doc変更: spec 203改訂（#371のPR）」・§4.1 supersession map。local-only無条件表現の修正（review指摘2）を含む |
| `tests/unit/app/lawnchair/organizer/ui/`（新規/更新） | `UsageAccessJitGate` のunit test（提示権state遷移: 競合で `Present` は1つ、`Wait` の保留、`markPresented`/`resolve`/`release` の冪等・owner一致・**`Presented` 以降の解放不可**、**提示済み未解決の間は待機者composition 0・解決後に1回進行・settings遷移中も停止**、観測seamの決定性（stale通知の非作用）、**owner破棄時のstate別規則（未提示→解放、提示済み→放棄解決で待機者1回進行、解決済み→無作用。stale owner callbackの後着で二重解決/二重再開なし）**、付与済み初回 `evaluate` で消費）+ `ManualOrganizationRunTest` へpause oracle追加（3経路 × gate decision、**CAPTURE commit移動後の順序oracle更新**（理由を記録）、`Busy`・journal無event・lease exactly once release・二重継続で `RUN_STARTED` 1回、**resume経路でCAPTUREが入口gate区間より前にpublishされない**、既存oracleは既定gateで無編集green — CAPTURE位置の更新分は理由を記録）+ bounded re-readの決定的oracle（`false→true`、virtual clockでの上限境界）+ `ManualOrganizationFaceTest` の対応表追加 | gate logic・2段階orchestration・再取得timingのinterface test |
| `tests/organizer-instrumentation/`（新規/更新） | JIT要求flow（run開始・選択確認・D-06直行・依頼生成・run-in経路）、1回限り、断って続行、**付与して続行（production predicateでGRANTED観測後にcomposition開始）**、T-06 copy回帰（EN/ja）、dialog Back helper、選択中断の否定oracle、**run/exchange競合で提示1つ・解決まで不進行**、**old attemptのcallback/gate操作がnew attemptへ作用しない（close→同条件再生成で古いcallbackが再開しない・古い `release`/`markPresented` が新reservationへ作用しない）**、**JIT保留中close後の遅延callbackが生成を再開しない**、exchange結合oracle（`T-07 AI → [置換確認] → JIT要求 → 生成 → 送信前確認`。#372 merge後のre-entryで再検証） | spec Test oracle表（JIT-AC-01〜06, 08, 09） |

source implementation・build設定・dependencyの変更は本Issueの実装PRのscopeであり、
本plan（spec/plan整備task）では行わない。

## Migration and recovery

- persistent state / schema / preference formatの変更は**なし**。migration不要。
  Launcher layout DB・`favorites` への接触なしのためホームレイアウト安全規約の適用対象外。
- failure中のrollback: JIT gateは表示flowのみでzero-write。保留操作はprocess-localであり、
  どの時点でも破棄は無害（未開始の操作は存在しなかったのと同じ。run側のpause/claimは
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
  書込み・永続化なし。「続行」の選択は `resolve` を伴う通常の解決経路である。
- **付与直後のapp-op伝播遅延**: 復帰後の判定はbounded re-read（注入可能な
  clock/predicate/delay、固定sleep禁止）によりGRANTEDの観測を待つ。上限内に観測できれば
  その試行は付与済みで読まれる（TO-BE §6.4どおり）。上限まで観測できない場合は
  未付与でcompositionを続行し、次回compositionから付与済みになる — 既知限界として
  spec/PRに記録し、新規の再試行機構は導入しない。上限値は実装PRのcontract commitで
  確定・記録する。
- **dialog表示中 / 設定遷移中のprocess死**: 保留操作と機会stateが消えるのみ。
  再訪processでは付与状態で判断される（spec scenarioどおり）。
- **run pause中のdialog Back**: dialogのBackはdialog dismissal（= 断って継続。`resolve` を
  伴う通常の解決経路）として消費し、`ManualOrganizationBackHandler`（run中断）へ
  漏出させてはならない。pause state自体は明示cancel（T-09面の中断row）でのみ解消される。
  Back优先順位のtestをinstrumentationで要求する。
- **run-in生成経路でのdialog Back**: 同上。選択凍結中の意図しないrun中断の防止。
- **exchange保留中のflow退場**: `close()`・別generation遷移・host navigation破棄で
  `AwaitingUsageAccessJit` は退場し、保留生成は破棄される（gate stateが `Reserved` の
  未提示の場合のみ `release(attemptToken)`）。stale tokenを持つ遅延callback・古いattemptの
  gate操作は新attemptへ作用しない（token一致契約）。
- **pause中にhost面がdisposeされた場合**（画面離脱）: 既存の `dismiss()` 契約
  （active operationのcancel）によりrunは `Cancelled` へ戻る。機会への作用はstate別である:
  未提示（`Reserved`）なら `release` されて未消費のまま破棄、提示済み（`Presented`）なら
  放棄解決としてgateのみ解決され（当該runの操作は再開されず、JIT要求は再表示されない）、
  待機していた起点は解放される。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| JIT-AC-01 | instrumentation: app-op未付与（shell `appops set`）でrun開始 → 検出 `Unavailable` 経路はcomposition直前にJIT dialog表示assert → 解決後にrun state遷移。候補あり経路は選択確認直前まで非表示＋選択中断で不表示（否定oracle）。D-06直行経路・idle生成・run-in生成の同様assert。付与済みの否定的観測 | organizer instrumentation lane（API 36 emulator） |
| JIT-AC-02 | unit: 新規stringの `values/` / `values-ja/` 存在とplaceholder一致（spec 123 AC-5方式）。+ 実装PR contract commitでの最終文言と意味要素checklist（3要素・任意性・raw/bucket/送信前確認）のPR記録 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| JIT-AC-03 | unit: 注入predicate/clock（virtual clock）によるbounded re-readの決定的oracle（`false→true` 観測で続行、上限境界で未付与継続。固定sleep不使用）。instrumentation: dialogの遷移操作 → shell `appops set ... allow` → **production predicateでGRANTED観測後にcomposition開始**、上限超過後に必ずfallback。composer側は `PersonalizationCompositionTest` の付与済み経路で担保 | unit gate + organizer instrumentation lane（probe testと同一pattern） |
| JIT-AC-04 | instrumentation: 断って続行 → runがpreviewまで進行（`NotReady`不発生）。遷移失敗注入（`ActivityNotFoundException`）→ dialog維持＋「続行」機能→Unavailable継続のexact oracle。unit: `PersonalizationCompositionTest` 等の既存composer suiteが無編集でgreen（AC-11/AC-13回帰） | unit gate + instrumentation lane |
| JIT-AC-05 | unit: gate提示権state遷移（未消費→提示で消費・`Presented` 以降解放不可、提示後action不成立でも消費済み、付与済み初回 `evaluate` で解決扱いの消費、composition不到達操作は非消費、競合で `Present` は1つ・`Wait` は保留・提示前cancelで解放（再獲得は1つ）、**提示済み未解決の間は待機者composition 0・解決後に1回進行・settings遷移中も停止**、観測seamの決定性・stale通知の非作用、attempt identity bind、**放棄解決（owner=`Presented`・waiter=`Wait`でowner runをcancel/close→owner composition 0・waiter 1回進行・JIT要求再表示なし・stale owner callbackの後着で二重解決/二重再開なし）**）。unit: run resumeの二重発火で `RUN_STARTED`/composition各1回。instrumentation: 2回目の開始/生成でdialog不表示（同一process内）＋run/exchange競合で提示1つ＋解決まで不進行＋old/new attempt分離 | unit gate + instrumentation lane |
| JIT-AC-06 | T-06の既存instrumentation（状態操作）が無編集でgreen + row copy（EN/ja）のresource test + contract commitでの意味要素checklist記録 + diff review（状態管理契約の無変更） | instrumentation lane + unit gate + PR diff review |
| JIT-AC-07 | specs/203-.../spec.mdのdiff review（U-2・表のrationale要件更新＋JIT行・AC・change historyのamendmentのみ。snapshot/provenance契約節の無変更） | PR diff review |
| JIT-AC-08 | diff review（manifest permission・persistent store・diagnostics eventの無変更）+ failure path unit/instrumentation（遷移失敗、pause中cancelのjournal無event、pause中RUN lease保持（2回目start `Busy`）・cancel/dismissでのlease exactly once release、process死模擬、**resume経路でcapture可視commitが入口gate区間より前にpublishされない（face trace観測含む）**、JIT保留中close後の遅延callbackが生成を再開しない） | PR diff review + unit gate |
| JIT-AC-09 | Compose semantics assertion（dialog name/role/state、focus移動と復帰）、200% font scale到達test、emulator screenshot（light/dark × ja/default） | organizer instrumentation lane + 手動evidence記録 |

含めるべき観点: unit/contract（gate提示権state・run pause orchestration・bounded re-read・
attempt identity bind）、integration（composer回帰・生成順序）、UI/accessibility
（dialog操作・font scale・TalkBack）、failure injection（遷移失敗・process死・pause中cancel・
伝播遅延）・競合（同時trigger・二重resume・stale resume・stale gate操作）。

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
      記録済み。pause pointの導入は選択面と同一の既存待機点概念の適用であり、提示権state・
      観測seam・attempt identityはgate内部の実装契約としてspec/planに記録済み）
- [ ] AGENTS.md: 変更なし

## Execution checklist

- [ ] Current behavior reproduced（常設rowのみでJIT要求が存在しないこと。hub T-06）
- [ ] Gate unit tests fail for the missing behavior（提示権state・`evaluate`/`markPresented`/
      `resolve`/`release`・提示時消費と解決解放の分離・run pause分岐・bounded re-read）
- [ ] Minimal implementation completed（gate + dialog + run pause/claim point + exchange
      `AwaitingUsageAccessJit(attemptToken, ...)` variant + T-06 copy改訂）
- [ ] Migration/recovery verified（該当なし — zero-write・persistent変更なしの確認）
- [ ] Full relevant verification completed（unit gate、instrumentation lane、a11y evidence、
      `./gradlew spotlessCheck`、CI `final-status`）
- [ ] PR evidence and remaining risks recorded（spec 203 amendment diff、T-06 copy回帰＋
      意味要素checklist、bounded re-read上限値、Unverified areasの残項目）

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
  - run state machineへのpause/claim state追加とCAPTURE commitの入口集約は、#369が表示統合と
    して守った「内部state machine不変」（spec 369 RD-3、そのスコープは#369自身の変更に対する
    制約）を#371が所有する形で拡張するものである。#369のface mapping test・state機器oracle・
    CAPTURE publish順序oracleへの影響分は本Issueの実装PRで更新し、理由
    （pause point導入とCAPTURE commitの入口gate区間集約）をPRに記録する。pause/claimは
    `RUN_STARTED` 以前のpre-journal区間であり、#369の「pre-composed phase は
    run-mode-bearing eventを持たない」契約および入口cancel gate（RD-6）のactive再確認とは
    整合する。
  - gate提示権のowner管理（run operation / exchange attemptToken）の漏れ — owner破棄時の
    state別規則（未提示→解放、提示済み→放棄解決、解決済み→無作用）の適用漏れは、
    「提示されない要求機会の永続消費」または「`Presented` 孤児化による待機者の凍結」として
    現れるため、cancel/dismiss/closeの全経路で規則の適用をunit/instrumentationで検証する。
    逆に `Presented` 以降の誤解放は1回限り契約違反となるため、state遷移の単体testで防ぐ。
  - dialog Backがrun中断handlerへ漏出する（pause/run-in経路） — instrumentationで明示検証。
  - bounded re-readの上限値の端末分布 — 上限超過時は既知限界（未付与で継続）として
    spec化済み。上限値はcontract commitで確定しPRでevidenceを記録。
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
