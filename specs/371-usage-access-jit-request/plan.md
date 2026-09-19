# Implementation Plan: Usage Access要求をjust-in-time化する（初回signal読み取り時点での文脈付き任意要求）

> Issue: #371
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-19時点のmain (`a2b6aba318`) を実読みした結果である。推測と確認済み事実を分けて記載する。

### 現在のUsage Access要求導線（常設rowのみ）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt:202-238`:
  Personalization group（記録toggle＋Usage Access行）。Usage Access行は
  `ClickablePreference` で、`onClick` が `context.startActivity(Intent(ACTION_USAGE_ACCESS_SETTINGS))`
  （235行目）。付与状態は `UsageAccess.isGranted(context)` を `remember` で保持し、
  `DisposableEffect` + `LifecycleEventObserver` で `ON_RESUME` ごとに再読取する
  （216-224行目。spec 203 U-2の「復帰時再読取」実装）。JIT要求の実装は存在しない。
  #366（draft `bf00f96175`）がこの使用状況materialを共有composableとしてhubへ提供し、
  #367（draft `a50f074ac2`）が設定側Personalization groupを削除してhub T-06へ一本化する。

### 権限predicate

- `lawnchair/src/app/lawnchair/organizer/integration/UsageAccess.kt`: app-op
  `OPSTR_GET_USAGE_STATS` ベースの共有predicate（`MODE_DEFAULT` 時はmanifest permissionへ
  fallback）。JIT要求のtrigger判定はこのpredicateを再利用する（新しい権限APIを導入しない）。

### signal読み取り点（composition）

- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt:586-618`
  （`readPersonalizationSnapshot`。本体は592行目〜）: composition attemptごとに
  `PersonalizationSignalSnapshotSource.read()` を1回呼ぶ。失敗・未付与は
  `PersonalizationSignalSnapshot.unavailable(...)` へdegradeし、決してthrowしない
  （spec 203 optional source契約）。`ProductionOrganizationInputComposer.kt:32` が
  `AndroidPersonalizationSignalSnapshotSource` を注入する。
- `lawnchair/src/app/lawnchair/organizer/integration/AndroidPersonalizationSignalSnapshotSource.kt`:
  system usage + launcher-originの2 sourceをmergeし、`usageAccess` stateを運ぶ。
  **composerはUIを持たない**ため、JIT要求のdialogをcomposer内に置くことはできない。

### trigger点1: 整理runの開始

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:377-429`
  （`ManualOrganizationModule.start`。overload 377行目、本体388-429行目）:
  `beginOperation` → `detectMissingAppCandidates` →
  （候補ありなら `Selecting` へ停止、なし/失敗なら `runComposedPhase`）の同期flow。
  composition（signal読み取りを含む）は `start()` 呼び出し内で発生する。
  したがってJIT要求は `start()` を呼ぶ**前**に解決されている必要がある。
- UI呼び出しsite: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  の開始・再試行行群（369, 469, 484, 499, 521, 534, 657, 710, 798行目の
  `execute { coordinator.start(trigger) }`。`execute` は257行目、`scope.launch` + IO dispatcher）。
  これらのsiteはrun状態別に分かれているが、process初回のcompositionが起こり得るのは
  Idle状態の開始行（およびonboarding経由）のみである。防御的に全siteを同一helperで
  wrapしてもlatchにより冪等である。
- onboarding: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt:216,270`
  が `ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)` を直接呼ぶ
  （Compose overlay内）。ここもprocess初回compositionの起点になり得る。

### trigger点2: AI依頼生成

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`:
  `requestGeneration`（278行目）は置換確認が必要なら `ExchangeScreen.ReplacementConfirm`
  へ遷移し、不要なら直ちに `generate(tier)` / `generateScoped(...)` へ進む。
  `confirmReplacementAndGenerate`（293行目）が置換確認承認後の生成kickoffである。
  `generate`（305行目付近）と `generateScoped`（319行目付近）が `screen = Generating` を設定し
  `controller.generate(...)` / `controller.generateForSelection(...)` を呼ぶ実際の生成開始点である。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`:
  `generate` → `composeExportInputs` → `ExchangeInputAdapter.composeForExport`
  （`ExchangeInputAdapter.kt:44`、`composer.composeFullOrganization()`）。
  run-in entryは `composeScopedExportInputs` → `composer.composeScopeComposedOrganization(selection)`。
  **いずれもrun compositionと同一のcanonical composition seamを通る**ため、
  依頼生成はprocess初回のsignal読み取りになり得る。
  特に注意: run-in entry（選択面から `generateForSelection`）は検出後 `Selecting` 状態から
  到達でき、この時点ではrunのcomposed phase（composition）が**まだ一度も走っていない**。
  したがって「run-in生成がprocess初回のsignal読み取りになる」経路は実際に存在し、
  gate対象に含める必要がある（specのuniform rule）。
- spec 205 AC-13: activityなsessionが存在する場合の生成開始は置換確認でgateされる
  （承認なしに生成・無効化が行われない）。JIT要求はこの確認より後に置く（spec scenarioどおり）。

### 既存test surface

- `tests/organizer-instrumentation/app/lawnchair/organizer/UsageAccessTransitionProbeTest.kt`:
  shell `appops set <pkg> GET_USAGE_STATS allow/default` でapp-opを切替え、production
  readerの付与/取消追従を検証するpattern（app-op変更は非同期のため1秒待機）。
  JIT要求の付与遷移testもこのpatternを再利用できる。
- `tests/unit/app/lawnchair/organizer/integration/PersonalizationCompositionTest.kt`:
  composer seamの回帰（JIT追加で無編集greenであるべき）。`ContextExportBuilderTest` /
  `ExchangeTargetScopeCouplingTest` もexport側の回帰surface。
- T-06常設rowの回帰surfaceは#366/#367のdraft planが定義するtoggle↔preference一致 /
  resume再読取のtestである（本Issueでは無編集greenが要求される）。

### strings

- `lawnchair/res/values/strings.xml:1006-1011`、`lawnchair/res/values-ja/strings.xml:28-33`:
  現行の常設row文言（rationale要素: local-only、整理精度向上、許可なしでも全機能利用可能）。
  JIT要求の文言はこれらと矛盾しない内容を新規stringとして追加する。

## Design

### Modules and interfaces

- **新規: process-scopedなJIT gate（UI層、`app.lawnchair.organizer.ui`）**。
  小さなclass（仮称 `UsageAccessJitGate`）とCompose dialog/helper（仮称
  `UsageAccessJitRequestUi`）を1 fileに置く（例:
  `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`）。
  - `UsageAccessJitGate` は `isGranted: () -> Boolean` をconstructor注入とし、
    `requested: Boolean`（in-memory latch）と `shouldRequest(): Boolean = !requested && !isGranted()`、
    `markRequested()` のみを持つ。production singletonは `ManualOrganizationModule.get(context)`
    と同様の `get(context)` patternで取得し、predicateは既存 `UsageAccess.isGranted` に委ねる。
    unit testは注入predicateで直接構築する（仮想interfaceは作らない — AGENTS.md設計規約）。
    latchは「表示した時点」ではなく「gateを通って操作が実際にcompositionへ進む時点」で消費する
    設計とする（Busy等で操作が実行されなかった場合に機会を無駄にしない）。
    ただしJIT dialog表示中にlatchを消費しない場合、dialog表示中の重複triggerを避けるため
    「dialog可視中」フラグをhost側で持つ（表示中の再入は無視するのみで、
    spec契約の「1回」はcomposition進行時の消費で定義される）。
  - dialog/helperは「gate判定 → 表示 → 解決 → pending action実行」をhostに提供する
    composable-level関数とAlertDialog（material3。既存の `ExchangeFlowUi.kt:1471` と
    同一component規約）からなる。遷移操作は `ACTION_USAGE_ACCESS_SETTINGS` の
    `startActivity` を `ActivityNotFoundException` をcatchして呼ぶ。
    `ON_RESUME` 再読取は `HomeScreenPreferences.kt:216-224` と同一の
    `LifecycleEventObserver` patternをhelper内に閉じ込める。
- **統合点（trigger site）**:
  1. run開始: `ManualOrganizationPreferences.kt` の開始系行のactionをgate helper経由にする
     （`execute { coordinator.start(trigger) }` の前段）。全siteを同一helperでwrapする
     （latchにより冪等。site一覧の最終確定は実装PRで行う — 下記Unverified areas）。
  2. 依頼生成: `ExchangeFlowStateHolder` の `generate` / `generateScoped` の先頭でgate判定し、
     必要なら生成を保留する。保留中の `(tier, scoped)` をholderの小さなstate
     （`ExchangeScreen` machine外の独立state、例: `jitRequestPending`）に置き、
     hosting composableが共有dialogをrenderし、解決callbackで保留生成を再開する。
     置換確認（`ReplacementConfirm`）は既存flowのまま先行するため、
     「置換確認 → JIT要求 → 生成」の順序が自然に成立する（spec scenarioどおり）。
  3. onboarding: `OrganizationOnboardingProposal.kt` の開始呼び出しをgate helper経由にする。
- **seamの境界**: composer・`PersonalizationSignalSnapshotSource`・`UsageAccess` predicate・
  run state machine・exchange flow controller（生成順序契約）は**無変更**である。
  JIT gateは「signalを読む操作を発火するUI上の点」とcomposition呼び出しの間に挟まる
  UI層のgateであり、interfaceを通してtestする（gate unit + hosting instrumentation）。
  platform型・DB行はgateのinterfaceへ現れない。

### Data flow

1. ユーザーがrun開始 / 依頼生成を操作する。
2. hostが `gate.shouldRequest()` を判定（app-op未付与 && latch未消費 && dialog非表示）。
3. falseなら即座に従来どおりaction実行。trueならJIT dialogを表示し、actionをpendingに保持する。
4. 解決: (a) 「設定を開く」→ intent発火 → `ON_RESUME` で再読取 →（付与の成否を問わず）
   pending action実行。(b) 「続行（断る）」/ system Back / dialog dismiss → pending action実行。
5. pending action実行時にlatchを消費する。以後同一process内では `shouldRequest()` が常にfalse。
6. compositionは既存seamで実行され、その時点の実際の付与状態でsnapshotが構築される
   （付与されていればsystem usage section構築、未付与なら `Unavailable` + launcher-origin保持）。
   JIT gateはcompositionの結果を一切読み書きしない。

### Alternatives rejected

- **gateをcomposer / `ManualOrganizationRun.start` 内に置く**: composition moduleはUI-freeであり
  （AGENTS.md設計規約・spec 203のpurity要求）、run state machineのadmission契約に
  dialog待ちを混入させるのはseam汚染。却下。
- **composition後のreactive prompt**（`NOT_GRANTED` snapshotの検出後にdialog表示）:
  当該試行のsnapshotは既に固定されており、付与しても現runがbenefitしない。
  TO-BE §6.4「戻って付与されていればsignal取得を続行」と矛盾。却下。
- **「表示済み」の永続flag**（1回=install生涯）: Issue本文「persistent state変更なし」および
  disposition §7.3「要求stateはprocess-local（再訪時は付与状態で判断）」と矛盾。却下。
- **「初回NOT_GRANTED読み取り」までlatchを保持**（process途中のrevoke後に再prompt）:
  意図的な取り消しの後にpromptするのは「騒がせない」規則（spec 203拒否行）に反し、
  「最初にsignalを読む時点」（D-07の文言）への結合も弱まる。却下 —
  latchはprocess初回triggerで消費する（specのscenarioどおり）。
- **active run中のJIT抑制**（run-in生成でのprompt回避）: run-in生成は検出後 `Selecting` から
  到達でき、process初回compositionになり得るため抑制するとuniform ruleに穴が開く。
  抑制logicのための追加stateも不要。promptは選択凍結に抵触しない
  （dialog Backはdialog dismissalであり、run中断を発火させない実装を要求する —
  下記Failure handling）。却下（抑制しない）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`（新規） | JIT gate（latch + predicate注入）・JIT dialog composable・pending action helper・`ON_RESUME` 再読取helper | organizer UI層の共有表現。run / exchange / onboardingの全hostから使え、composer・controllerを触らない |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | 開始系行のactionをgate helper経由に変更 + JIT dialogのhosting | run開始trigger点。既存 `execute` wrapperの前段に置く |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `generate` / `generateScoped` 先頭のgate判定、保留生成state、解決callback | 依頼生成trigger点。置換確認の後にJIT要求が来る順序を既存flowの自然な拡張で実現 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | onboarding開始呼び出しをgate helper経由に変更 | onboarding経由の初回composition trigger点 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | JIT dialog文言（title/body/遷移/続行。format resource、§7.3の3要素 + 任意性 + local-only） | spec 123 AC-4/AC-5・spec 161 LQA規約 |
| `specs/203-usage-implicit-preference-signals/spec.md` | amendment: U-2改訂、Permission and fallback behavior表の「opt-in (初回)」行更新 + JIT要求行追加、JIT受入条件（AC-17以降）追加、change history | Issue本文「Spec」節・disposition §3.10「doc変更: spec 203改訂（#371のPR）」・§4.1 supersession map |
| `tests/unit/app/lawnchair/organizer/ui/`（新規） | `UsageAccessJitGate` のunit test（predicate × latch状態遷移、1回限り、付与済み初回trigger） | gate logicのinterface test |
| `tests/organizer-instrumentation/`（新規/更新） | JIT要求flow（run開始・依頼生成・run-in経路）、1回限り、断って続行、付与して続行、T-06回帰、dialog Back helper | spec Test oracle表（JIT-AC-01〜06, 08, 09） |

source implementation・build設定・dependencyの変更は本Issueの実装PRのscopeであり、
本plan（spec/plan整備task）では行わない。

## Migration and recovery

- persistent state / schema / preference formatの変更は**なし**。migration不要。
  Launcher layout DB・`favorites` への接触なしのためホームレイアウト安全規約の適用対象外。
- failure中のrollback: JIT gateは表示flowのみでzero-write。pending actionはprocess-localであり、
  どの時点でも破棄は無害（未開始の操作は存在しなかったのと同じ）。
- release rollback / downgrade: PR revertでJIT要求が消え常設rowのみへ戻る
  （disposition §7.3「downgrade: 従来の常設rowのみ」。残留物なし）。
- backup/restore: 影響なし（latchは対象外、usage accessの付与状態自体はsystem管理のまま）。

## Failure handling

- **設定画面が解決できない**（`ActivityNotFoundException`）: crashさせない。dialogを閉じず
  「続行」の代替を残すか、閉じた場合は「断って続行」と同等の継続にする。
  書込み・永続化なし（spec scenario「設定画面が開けない環境でも壊れない」）。
- **dialog表示中 / 設定遷移中のprocess死**: pending actionとlatchが消えるのみ。
  再訪processでは付与状態で判断される（spec scenarioどおり）。
- **run-in生成経路でのdialog Back**: dialogのBackはdialog dismissalとして消費し、
  `ManualOrganizationBackHandler`（run中断）へ漏出させてはならない。
  Back优先順位のtestをinstrumentationで要求する（選択凍結中の意図しないrun中断の防止）。
- **付与直後のapp-op伝播遅延**: `UsageAccessTransitionProbeTest` が示すとおりapp-op変更は
  非同期に伝播する。`ON_RESUME` 再読取が即時には付与済みを返さない場合、当該試行は
  `Unavailable` で続行し、次回compositionから付与済みになる — 現行resume契約を再利用する
  issue scope上の既知の限界であり、新規の再試行機構を導入しない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| JIT-AC-01 | instrumentation: app-op未付与（shell `appops set`）でrun開始 → JIT dialog表示assert → 解決後にrun state遷移。idle生成・run-in生成の同様assert。付与済みの否定的観測 | organizer instrumentation lane（API 36 emulator） |
| JIT-AC-02 | unit: 新規stringの `values/` / `values-ja/` 存在とplaceholder一致（spec 123 AC-5方式）。+ 文言3要素のPR記録 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| JIT-AC-03 | instrumentation: dialogの遷移操作 → shell `appops set ... allow` → `ON_RESUME` 再読取 → pending action続行。composer側は `PersonalizationCompositionTest` の付与済み経路で担保 | organizer instrumentation lane（probe testと同一pattern） |
| JIT-AC-04 | instrumentation: 断って続行 → runがpreviewまで進行（`NotReady`不発生）。unit: `PersonalizationCompositionTest` 等の既存composer suiteが無編集でgreen（AC-11/AC-13回帰） | unit gate + instrumentation lane |
| JIT-AC-05 | unit: gate latch状態遷移（未消費→消費、付与済み初回triggerで消費）。instrumentation: 2回目の開始/生成でdialog不表示（同一process内） | unit gate + instrumentation lane |
| JIT-AC-06 | #366/#367のT-06 instrumentationが無編集でgreen + diff review（常設row契約の無変更） | instrumentation lane + PR diff review |
| JIT-AC-07 | specs/203-.../spec.mdのdiff review（U-2・表・AC・change historyのamendmentのみ。snapshot/provenance契約節の無変更） | PR diff review |
| JIT-AC-08 | diff review（manifest permission・persistent store・diagnostics eventの無変更）+ failure path unit/instrumentation（`ActivityNotFoundException`注入、process死模擬） | PR diff review + unit gate |
| JIT-AC-09 | Compose semantics assertion（dialog name/role/state、focus移動と復帰）、200% font scale到達test、emulator screenshot（light/dark × ja/default） | organizer instrumentation lane + 手動evidence記録 |

含めるべき観点: unit/contract（gate logic）、integration（composer回帰・生成順序）、
UI/accessibility（dialog操作・font scale・TalkBack）、failure injection（遷移失敗・process死・
app-op伝播遅延）。

## Documentation updates

- [x] spec status/history: 本spec（`specs/371-usage-access-jit-request/spec.md`）をdraftとして作成。
      実装PRでspec 203をamendmentし（JIT-AC-07）、最終PRで本specを `implemented` へ更新する。
- [ ] spec 203 amendment（実装PR。U-2・表・AC・change history）
- [ ] CONTEXT.md: 本PRでは変更しない（JIT要求・材料の語彙追加は#365が所有）
- [ ] DESIGN.md: 変更不要の見込み（UI層のgate追加であり、module構造・seam・不変条件の変更なし）。
      実装PRで再確認する
- [ ] ADR: 作らない（「変更が高コスト/理由がコードから分からない/実際の選択肢があった」の
      3条件に該当する判断はなかった。one-shot・process-localの規則はspecとTO-BE/dispositionに記録済み）
- [ ] AGENTS.md: 変更なし

## Execution checklist

- [ ] Current behavior reproduced（常設rowのみでJIT要求が存在しないこと。`HomeScreenPreferences.kt` / hub T-06）
- [ ] Gate unit tests fail for the missing behavior（latch・trigger条件）
- [ ] Minimal implementation completed（gate + dialog + 3種host統合）
- [ ] Migration/recovery verified（該当なし — zero-write・persistent変更なしの確認）
- [ ] Full relevant verification completed（unit gate、instrumentation lane、a11y evidence、
      `./gradlew spotlessCheck`、CI `final-status`）
- [ ] PR evidence and remaining risks recorded（spec 203 amendment diff、T-06回帰、
      Unverified areasの残項目）

## Dependencies / risk

- **実装着手の前提**: #365（正本改訂、OPEN）のmerge後（正本先行の原則。#366/#367と同一判定）、
  および #366 → #367 のmerge後（T-06常設rowがhub材料面に存在すること。Issue本文
  `Depends on: #367`）。
- **並行**: #368 / #369 / #372 と並行可能（disposition §8「#371は#367後並行可」）。
  #369/#372がtrigger点の面を移動しても、JIT gateは「signalを読む操作」に結合しているため
  契約は不変である。ただしrebase時の統合siteの修正は発生し得る。
- **risk**:
  - dialog Backがrun中断handlerへ漏出する（run-in経路） — instrumentationで明示検証（上記）。
  - app-op伝播遅延による「付与したのに当該試行がUnavailable」— 既知限界としてPRに記録。
  - 開始系call siteの網羅（9 site + onboarding + exchange 2経路）— 全siteの同一helper wrapと
    latchの冪等性で緩和。最終site一覧は実装PRのdiff reviewで確認する。
- **explicitly unverified areas**:
  - 実device/OEM matrixでの `ACTION_USAGE_ACCESS_SETTINGS` の解決可否（unsupported pathは
    spec化したが、device evidenceは未取得）。
  - 付与直後のapp-op伝播timingの実device分布（probe evidenceはreader追従の検証のみ）。
  - #366/#367 merge後の実際のT-06 composable形状と、本Issue diffのcontext shift。
  - onboarding momentでのJIT要求表示のproduct受容（spec Open questions 1。owner review待ち）。
