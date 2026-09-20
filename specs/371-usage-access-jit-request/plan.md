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

### 権限predicate

- `lawnchair/src/app/lawnchair/organizer/integration/UsageAccess.kt:16-18`（`object UsageAccess`、
  `isGranted(context)`）: app-op `OPSTR_GET_USAGE_STATS` ベースの共有predicate
  （`MODE_DEFAULT` 時はmanifest permissionへfallback）。JIT要求のtrigger判定はこの
  predicateを再利用する（新しい権限APIを導入しない）。

### signal読み取り点（composition）

- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`:
  `composeInternal`（177-185行目。`composeFullOrganization` / `composeScopeComposedOrganization`
  の共通本体）がcomposition attemptごとに `readPersonalizationSnapshot`（592行目）を通じて
  `PersonalizationSignalSnapshotSource.read()` を1回呼ぶ。失敗・未付与は
  `PersonalizationSignalSnapshot.unavailable(...)` へdegradeし、決してthrowしない
  （spec 203 optional source契約）。**composerはUIを持たない**ため、JIT要求のdialogを
  composer内に置くことはできない。

### trigger点1: 整理run — composed phase入口が単一のchoke point

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（#369実装適用後）:
  - `start()`（430行目overload、本体441行目〜）: `beginOperation`（1256行目。成功時に
    `State.Capturing`と `preparationPhase=DETECTION` をpublish）→ `State.CandidateDetection` →
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
  - `cancel()`（959行目）: `State.Capturing/CandidateDetection/Selecting/Planning/Applying/...`
    をadmission前のcancel可能状態として扱う。`dismiss()` はactive operationを状態を問わず
    cancelする。
  - 教訓: **「run開始操作」の直前にJIT要求を出す設計は、選択面で停止する経路で
    一度もsignalを読まないまま要求を見せる逆JITになる**（初回review指摘1）。
    要求はcomposed phase入口（`runComposedPhase` の直前）に置く必要がある。
- run開始の呼び出しsite: `ManualOrganizationPreferences.kt` の開始系行群
  （`execute { coordinator.start(trigger) }` 複数site）。#369実装によりstate→face写像は
  純関数 `manualOrganizationFace(state)`（`ManualOrganizationFace.kt`。20状態→8ユーザー状態。
  0件 `Selecting` は `PREPARATION` へ決定的に写像）へ集約され、face hostが面を描画する。
- onboarding: `OrganizationOnboardingProposal.kt` の `admitReview`（216, 270行目）が
  `ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)` を呼ぶ。
  #370実装（PR #389）はhint copy/案内先のみを変え、admission経路は無変更であることを
  当該mainのdiffで確認済み（開始呼び出しの行番号は不変）。

### trigger点2: AI依頼生成

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  （`ExchangeFlowStateHolder`、194行目。#369実装で無変更）:
  `requestGeneration`（255行目）は置換確認が必要なら `ExchangeScreen.ReplacementConfirm` へ
  遷移し、不要なら直ちに `generate(tier)`（282行目）/ `generateScoped(...)`（296行目）へ進む。
  `confirmReplacementAndGenerate`（270行目）が置換確認承認後の生成kickoffである。
  `generate` / `generateScoped` は `screen = ExchangeScreen.Generating` を設定し
  `controller.generate(...)` / `controller.generateForSelection(...)` を呼ぶ。
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

### 既存test surface

- `tests/organizer-instrumentation/app/lawnchair/organizer/UsageAccessTransitionProbeTest.kt`:
  shell `appops set <pkg> GET_USAGE_STATS allow/default` でapp-opを切替え、production
  readerの付与/取消追従を検証するpattern（app-op変更は非同期のため1秒待機）。
  JIT要求の付与遷移testもこのpatternを再利用できる。
- `tests/unit/app/lawnchair/organizer/integration/PersonalizationCompositionTest.kt`:
  composer seamの回帰（JIT追加で無編集greenであるべき）。
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`:
  run state machineのunit oracle。#369実装がD-06継続・cancel gate・`preparationPhase` の
  oracleを追加済み。JIT pauseのunit oracleはここに追加する。
  run構築時にgateを注入しない既存testは「要求機会が常に消費済み」の既定gateで
  現行挙動を維持する（下記Design）。
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
    process-localな1bitの消費stateを持つ。APIは2つのみ:
    - `evaluate(): Boolean` — composition trigger点で呼ぶ。消費済みなら `false`。
      未消費で付与済みなら**その場で機会を消費して** `false`（spec scenario
      「初回trigger時に付与済み」）。未消費で未付与なら `true`（要求すべき）。
    - `markRequested()` — 要求dialogを**提示した時点**で呼ぶ。機会を消費する
      （review指摘4。提示後の操作の成否・Busy・遷移失敗とは独立）。
      冪等であり、二重提示を自然に排除する（「dialog可視中」flagは不要になる）。
    選択面の中断など「compositionに到達しない操作」はいずれのAPIも呼ばないため
    機会を消費しない（spec否定scenario）。
  - production instanceはprocess-scoped singleton（`ManualOrganizationModule` と同様の
    `get(context)` pattern、predicateは既存 `UsageAccess.isGranted` に委ねる）であり、
    run machineとexchange holderの**双方から同一instanceを共有する**（機会はprocessで1回）。
    unit testは注入predicateで直接構築する（仮想interfaceは作らない — AGENTS.md設計規約）。
    run構築時にgateを注入しない既存test互換のため、`ManualOrganizationRun` の
    constructor既定値は「常に消費済み（要求しない）」の不変gateとする。
- **統合点1: run machineのcomposed phase入口にpause point（2段階orchestration。review指摘1の解消）**。
  `ManualOrganizationRun` にgateをconstructor注入し、**`runComposedPhase` の入口**
  （#369のcancel gate／journal openの前）に要求機会の判定を置く。3つのcomposition経路
  （検出 `Unavailable` 継続・D-06の0件内部継続・選択確認）がいずれもこの単一入口を通るため、
  経路の漏れが構造的に発生しない。選択面（`State.Selecting`）と同一の「UI待機点」の概念であり、
  composer・`PersonalizationSignalSnapshotSource`・admission契約（single-active-operation）は
  触れない。
  - pause判定の構造: 入口でまずactive再確認（cancel勝利時は機会に触れずreturn）→
    `gate.evaluate()` が `true` の場合のみ `State.AwaitingUsageAccessJit(runId, selection)`
    をpublishしてreturn（journal は開かない。`preparationPhase` も `CAPTURE` に進めない）。
    `false` なら現行どおりcancel gate → composition（付与済み・消費済み環境では
    新stateへ到達しない。既存test互換）。
  - 新state: `State.AwaitingUsageAccessJit(runId, selection: List<CandidateTarget.AppKey>?)`。
    ユーザー向け8状態（TO-BE §8.1）には現れない。face mapping
    （`ManualOrganizationFace.kt`）へは `PREPARATION`（T-09準備中。dialogはその上の
    modal overlay）のentryを1行追加する。
  - 新method `continueAfterUsageAccessGate()`（仮称）: stateが
    `AwaitingUsageAccessJit` でoperationがactiveな場合のみ（単発guard。二重呼び出しはno-op）、
    保持したselectionで `runComposedPhase` を再呼び出しする（機会は提示時に消費済みのため
    入口の判定は即座に通過し、現行と同一のcancel gate → compositionへ進む）。
    dialog解決（付与帰還・断行のいずれ）からの唯一の継続入口。
  - `cancel()` のcancel可能状態集合へ `AwaitingUsageAccessJit` を追加する。
    pauseは `RUN_STARTED` 以前であるため、pause中のcancelはjournal eventを発生させない
    （zero-write契約の回帰。#369のcancel gateとも整合する）。
  - 既知のtransient: `confirmSelection()` / `continueWithEmptySelection()` は入口判定の前に
    `State.Capturing` をpublishするため、pause成立時は1 frame以下の準備中表示を挟み
    得る（dialogがmodal overlayとして直ちに覆う。動作契約への影響なし。unit oracleは
    最終的に `AwaitingUsageAccessJit` に落ち着くことのみをpinする）。
  - dialog host: run state観測点（face host。#369実装後の `ManualOrganizationPreferences` 側）
    が `AwaitingUsageAccessJit` を観測したら共有dialogを提示する（提示時に
    `gate.markRequested()`）。解決時（「続行」/ Back / `ON_RESUME`復帰）に
    `continueAfterUsageAccessGate()` を呼ぶ。「設定を開く」は `ACTION_USAGE_ACCESS_SETTINGS` の
    `startActivity` を `ActivityNotFoundException` をcatchして呼び、`ON_RESUME` 再読取は
    `OrganizerUsageMaterialRows.kt` と同一の `LifecycleEventObserver` patternを
    helper内に閉じ込める。復帰時は付与の成否を問わずcompositionを続行する
    （付与されていれば付与済みで読む、いなければ `Unavailable` で続行。spec scenarioどおり）。
    個々の開始row・onboarding `admitReview`・import→run（`ExchangeFlowUi.kt:690`）は
    **無編集**でよい（pause pointがmachine内のため）。
  - onboarding経由も同一経路で扱われる: `start(ONBOARDING_PROPOSAL)` → 検出 →
    composed phase入口のpause → run面hostがdialogを提示。onboarding操作時点で
    要求を出さない（spec scenarioどおり）。
- **統合点2: 依頼生成のholder gate**。
  `ExchangeFlowStateHolder` の `generate` / `generateScoped` の先頭で `gate.evaluate()` を判定し、
  `true` なら生成を保留する。保留中の `(tier, selection, labels)` をholderの小さなstate
  （`ExchangeScreen` machine外の独立state）に置き、hosting composableが共有dialogをrenderし、
  提示時に `markRequested()`、解決callbackで保留生成を再開する。
  置換確認（`ReplacementConfirm`）は既存flowのまま先行するため、
  「置換確認 → JIT要求 → 生成」の順序が自然に成立する（spec scenarioどおり）。
  run-in生成（`generateForSelection`）も同一gateで扱われる。dialogのBackはdialog
  dismissalとして消費し、run側のBack handler（選択凍結中のrun中断）へ漏らさない。
- **統合点3: T-06常設rowのcopy改訂（review指摘3の解消）**。
  `OrganizerUsageMaterialRows.kt` の文言（`organizer_personalization_usage_access_label/
  _granted/_not_granted`、EN/ja）を§7.3の3要素を1文で満たす内容へ更新する。
  rowの状態表示・遷移・`ON_RESUME`再読取の実装・操作契約は**無変更**である
  （#367 MAT-AC-06のpin対象は状態管理であり、copyは本Issueが所有するpermission copy契約）。
- **seamの境界**: composer・`PersonalizationSignalSnapshotSource`・`UsageAccess` predicate・
  exchange flow controller（生成順序契約）は**無変更**である。
  JIT gateは「signalを読む操作を発火するUI上の点」とcomposition呼び出しの間に挟まる
  UI層のgateであり、interfaceを通してtestする（gate unit + run machine unit +
  hosting instrumentation）。platform型・DB行はgateのinterfaceへ現れない。

### Data flow

1. ユーザーがcomposition起点の操作を行う（開始系row / 選択確認 / 依頼生成）。
2. trigger点で `gate.evaluate()` を判定（run側は `runComposedPhase` 入口。
   exchange側は `generate`/`generateScoped` の先頭）。
3. `false`（消費済み or 付与済み→その場で消費）なら即座に従来どおりcomposition実行。
4. `true` なら要求を保留する（run側: pause state / exchange側: 保留state）し、hostが
   共有dialogを提示して `markRequested()` で機会を消費する。
5. 解決: (a) 「設定を開く」→ intent発火 → `ON_RESUME` で再読取 →（付与の成否を問わず）
   保留操作の継続。(b) 「続行（断る）」/ system Back / dismiss → 保留操作の継続。
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
- **latchを「操作がcompositionへ進んだ時点」で消費**: dialog提示後に操作がBusy・失敗で
  成立しなかった場合、同一process内の次操作で同じ要求を再表示でき、spec
  「提示されたら再表示しない」（JIT-AC-05）に反する（初回review指摘4）。却下 —
  消費点は提示時点に固定する。
- **latchを付与済み初回triggerでも消費しない**: spec scenario「初回trigger時に付与済み」の
  「後続の権限取消でも要求は表示されない」が実現できなくなる（取消後に `evaluate()` が
  再度 `true` を返してしまう）。却下 — 付与済みの初回trigger評価で消費する。
- **active run中のJIT抑制**（run-in生成でのprompt回避）: run-in生成は検出後 `Selecting` から
  到達でき、process初回compositionになり得るため抑制するとuniform ruleに穴が開く。
  promptは選択凍結に抵触しない（dialog Backはdialog dismissalであり、run中断を
  発火させない実装を要求する — 下記Failure handling）。却下（抑制しない）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`（新規） | 要求機会gate（`evaluate`/`markRequested`、process-scoped singleton）・JIT dialog composable・保留継続helper・`ON_RESUME` 再読取helper | organizer UI層の共有表現。run / exchangeの両hostから使え、composer・controllerを触らない |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | constructorへgate注入（既定は不変gate）。`State.AwaitingUsageAccessJit` 追加、`runComposedPhase` 入口（cancel gate前）のpause判定、`continueAfterUsageAccessGate()`、`cancel()` 対象状態への追加。javadoc | composition直前のpause pointはrun state machineのみが持てる（選択面と同一の待機点概念）。単一入口のchoke pointで3経路（`Unavailable` 継続・D-06内部継続・選択確認）を構造的に網羅する |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt` | `AwaitingUsageAccessJit` → `PREPARATION`（T-09）のface mapping entry追加 | #369が確立した決定的face写像の一貫性。新stateが8ユーザー状態の外に出ない保証 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `AwaitingUsageAccessJit` 観測時の共有dialog提示（`markRequested` + 解決時 `continueAfterUsageAccessGate`） | run state観測点が唯一のdialog host。開始row群の個別変更は不要（pauseがmachine内のため） |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `generate` / `generateScoped` 先頭のgate判定、保留生成state、dialog提示と解決callback | 依頼生成trigger点。置換確認の後にJIT要求が来る順序を既存flowの自然な拡張で実現 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | **無実装変更**（状態表示・遷移・`ON_RESUME`再読取は現行どおり）。javadocの文言要件参照を更新 | copy改訂の対象はresourceであり、実装は要件の参照先が変わるのみ |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | JIT dialog文言（title/body/遷移/続行。format resource、§7.3の3要素 + 任意性 + privacy修飾）新規。T-06 row文言（`organizer_personalization_usage_access_label/_granted/_not_granted`、EN/ja）を§7.3準拠へ改訂 | spec 123 AC-4/AC-5・spec 161 LQA規約。JIT（JIT-AC-02）とT-06（JIT-AC-06）双方のcopy契約 |
| `specs/203-usage-implicit-preference-signals/spec.md` | amendment: U-2改訂（常設row＋初回signal読み取り直前のJIT要求1回）、Permission and fallback behavior表の「opt-in (初回)」行のrationale要件をprivacy修飾形へ更新 + JIT要求行追加、JIT受入条件（AC-17以降）追加、change history | Issue本文「Spec」節・disposition §3.10「doc変更: spec 203改訂（#371のPR）」・§4.1 supersession map。local-only無条件表現の修正（review指摘2）を含む |
| `tests/unit/app/lawnchair/organizer/ui/`（新規/更新） | `UsageAccessJitGate` のunit test（evaluate × markRequested × 付与状態、提示時消費、付与済み初回trigger消費、冪等性）+ `ManualOrganizationRunTest` へpause oracle追加（3経路 × gate状態、継続・cancel・journal無event、既存oracleは既定gateで無編集green）+ `ManualOrganizationFaceTest` の対応表1行 | gate logicと2段階orchestrationのinterface test |
| `tests/organizer-instrumentation/`（新規/更新） | JIT要求flow（run開始・選択確認・D-06直行・依頼生成・run-in経路）、1回限り、断って続行、付与して続行、T-06 copy回帰（EN/ja）、dialog Back helper、選択中断の否定oracle | spec Test oracle表（JIT-AC-01〜06, 08, 09） |

source implementation・build設定・dependencyの変更は本Issueの実装PRのscopeであり、
本plan（spec/plan整備task）では行わない。

## Migration and recovery

- persistent state / schema / preference formatの変更は**なし**。migration不要。
  Launcher layout DB・`favorites` への接触なしのためホームレイアウト安全規約の適用対象外。
- failure中のrollback: JIT gateは表示flowのみでzero-write。保留操作はprocess-localであり、
  どの時点でも破棄は無害（未開始の操作は存在しなかったのと同じ。run側のpauseは
  `RUN_STARTED` 以前のためcancel/dismiss/process死のいずれもjournal eventを残さない）。
- release rollback / downgrade: PR revertでJIT要求が消え常設rowのみへ戻る
  （disposition §7.3「downgrade: 従来の常設rowのみ」。残留物なし）。
- backup/restore: 影響なし（latchは対象外、usage accessの付与状態自体はsystem管理のまま）。

## Failure handling

- **設定画面が解決できない**（`ActivityNotFoundException`）: crashさせない。dialogを閉じず
  「続行」の代替を残すか、閉じた場合は「断って続行」と同等の継続にする。
  書込み・永続化なし（spec scenario「設定画面が開けない環境でも壊れない」）。
- **dialog表示中 / 設定遷移中のprocess死**: 保留操作と機会stateが消えるのみ。
  再訪processでは付与状態で判断される（spec scenarioどおり）。
- **run pause中のdialog Back**: dialogのBackはdialog dismissal（= 断って継続）として消費し、
  `ManualOrganizationBackHandler`（run中断）へ漏出させてはならない。pause state自体は
  明示cancel（T-09面の中断row）でのみ解消される。Back优先順位のtestをinstrumentationで
  要求する。
- **run-in生成経路でのdialog Back**: 同上。選択凍結中の意図しないrun中断の防止。
- **付与直後のapp-op伝播遅延**: `UsageAccessTransitionProbeTest` が示すとおりapp-op変更は
  非同期に伝播する。`ON_RESUME` 再読取が即時には付与済みを返さない場合、当該試行は
  `Unavailable` で続行し、次回compositionから付与済みになる — 現行resume契約を再利用する
  issue scope上の既知の限界であり、新規の再試行機構を導入しない。
- **pause中にhost面がdisposeされた場合**（画面離脱）: 既存の `dismiss()` 契約
  （active operationのcancel）によりrunは `Cancelled` へ戻る。機会はdialog提示済みなら
  消費済み、未提示なら未消費のまま破棄される。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| JIT-AC-01 | instrumentation: app-op未付与（shell `appops set`）でrun開始 → 検出 `Unavailable` 経路はcomposition直前にJIT dialog表示assert → 解決後にrun state遷移。候補あり経路は選択確認直前まで非表示＋選択中断で不表示（否定oracle）。D-06直行経路・idle生成・run-in生成の同様assert。付与済みの否定的観測 | organizer instrumentation lane（API 36 emulator） |
| JIT-AC-02 | unit: 新規stringの `values/` / `values-ja/` 存在とplaceholder一致（spec 123 AC-5方式）。+ 文言3要素＋privacy修飾のPR記録 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| JIT-AC-03 | instrumentation: dialogの遷移操作 → shell `appops set ... allow` → `ON_RESUME` 再読取 → 保留composition続行。composer側は `PersonalizationCompositionTest` の付与済み経路で担保 | organizer instrumentation lane（probe testと同一pattern） |
| JIT-AC-04 | instrumentation: 断って続行 → runがpreviewまで進行（`NotReady`不発生）。unit: `PersonalizationCompositionTest` 等の既存composer suiteが無編集でgreen（AC-11/AC-13回帰） | unit gate + instrumentation lane |
| JIT-AC-05 | unit: gate状態遷移（未消費→提示で消費、提示後action不成立でも消費済み、付与済み初回 `evaluate` で消費、composition不到達操作は非消費）。instrumentation: 2回目の開始/生成でdialog不表示（同一process内） | unit gate + instrumentation lane |
| JIT-AC-06 | T-06の既存instrumentation（状態操作）が無編集でgreen + row copy（EN/ja）のresource test + diff review（状態管理契約の無変更） | instrumentation lane + unit gate + PR diff review |
| JIT-AC-07 | specs/203-.../spec.mdのdiff review（U-2・表のrationale要件更新＋JIT行・AC・change historyのamendmentのみ。snapshot/provenance契約節の無変更） | PR diff review |
| JIT-AC-08 | diff review（manifest permission・persistent store・diagnostics eventの無変更）+ failure path unit/instrumentation（`ActivityNotFoundException`注入、pause中cancelのjournal無event、process死模擬） | PR diff review + unit gate |
| JIT-AC-09 | Compose semantics assertion（dialog name/role/state、focus移動と復帰）、200% font scale到達test、emulator screenshot（light/dark × ja/default） | organizer instrumentation lane + 手動evidence記録 |

含めるべき観点: unit/contract（gate logic・run pause orchestration）、integration
（composer回帰・生成順序）、UI/accessibility（dialog操作・font scale・TalkBack）、
failure injection（遷移失敗・process死・pause中cancel・app-op伝播遅延）。

## Documentation updates

- [x] spec status/history: 本spec（`specs/371-usage-access-jit-request/spec.md`）をre-entry
      revisionとして更新。実装PRでspec 203をamendmentし（JIT-AC-07）、最終PRで本specを
      `implemented` へ更新する。
- [ ] spec 203 amendment（実装PR。U-2・表のrationale要件更新＋JIT行・AC・change history）
- [ ] CONTEXT.md: 本PRでは変更しない（JIT要求・材料の語彙は#365が確定済み）
- [ ] DESIGN.md: 変更不要の見込み（UI層のgate追加であり、module構造・seam・不変条件の変更なし）。
      実装PRで再確認する
- [ ] ADR: 作らない（「変更が高コスト/理由がコードから分からない/実際の選択肢があった」の
      3条件に該当する判断はなかった。one-shot・process-localの規則はspecとTO-BE/dispositionに
      記録済み。pause pointの導入は選択面と同一の既存待機点概念の適用である）
- [ ] AGENTS.md: 変更なし

## Execution checklist

- [ ] Current behavior reproduced（常設rowのみでJIT要求が存在しないこと。hub T-06）
- [ ] Gate unit tests fail for the missing behavior（`evaluate`/`markRequested`・提示時消費・
      run pause分岐）
- [ ] Minimal implementation completed（gate + dialog + run pause point + exchange holder gate
      + T-06 copy改訂）
- [ ] Migration/recovery verified（該当なし — zero-write・persistent変更なしの確認）
- [ ] Full relevant verification completed（unit gate、instrumentation lane、a11y evidence、
      `./gradlew spotlessCheck`、CI `final-status`）
- [ ] PR evidence and remaining risks recorded（spec 203 amendment diff、T-06 copy回帰、
      Unverified areasの残項目）

## Dependencies / risk

- **契約上の依存**: #367（merge済み。T-06がhub材料面に存在）。#365（merge済み）。
  ともに充足済み。
- **実装baseline**: `13c95eafe6`（#370実装merge済み。#369実装もmerge済み）をbaselineとする。
  本planの行番号は当該mainで実検証済みである。#372（AI相談統合）が依頼生成entryを移動しても
  holder先頭のgateは契約を維持する（rebase時の統合site修正は発生し得る）。
- **並行**: #368（merge済み）/ #369（merge済み）/ #372 と契約上は並行可能（disposition §8）。
- **risk**:
  - run state machineへのpause state追加は、#369が表示統合として守った
    「内部state machine不変」（spec 369 RD-3、そのスコープは#369自身の変更に対する制約）を
    #371が所有する形で拡張するものである。#369のface mapping test・state機器oracleへの
    影響分は本Issueの実装PRで更新し、理由をPRに記録する。pauseは `RUN_STARTED` 以前の
    pre-journal区間であり、#369の「pre-composed phase はrun-mode-bearing eventを持たない」
    契約および入口cancel gate（RD-6）のactive再確認とは整合する。
  - dialog Backがrun中断handlerへ漏出する（pause/run-in経路） — instrumentationで明示検証。
  - app-op伝播遅延による「付与したのに当該試行がUnavailable」— 既知限界としてPRに記録。
  - composition起点の網羅（run側は `runComposedPhase` 入口のchoke pointで構造的に網羅。
    exchange側は `generate`/`generateScoped` の2点 + import→run再構築はrun側に帰着）—
    gateはmachine内/holder先頭に置くため個別call siteのwrapは不要。
    実装PRのdiff reviewでexchange 2点がgateを通ることを確認する。
- **explicitly unverified areas**:
  - 実device/OEM matrixでの `ACTION_USAGE_ACCESS_SETTINGS` の解決可否（unsupported pathは
    spec化したが、device evidenceは未取得）。
  - 付与直後のapp-op伝播timingの実device分布（probe evidenceはreader追従の検証のみ）。
  - onboarding momentでのJIT要求表示のproduct受容（spec Open questions 1。owner review待ち）。
