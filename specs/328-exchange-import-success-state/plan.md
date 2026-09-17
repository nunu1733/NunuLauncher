# Implementation Plan: External Agent Exchange Import成功後の中間状態と次操作の明示

> Issue: #328
> Spec: [spec.md](./spec.md)
> Status: draft — spec D-2 (Back破棄の確認形式) / D-3 (CTA文言) がowner decision待ちのためimplementation-readyではない。

## Current evidence

origin/main (`8fd05a40d51abd24b40a7b93579bb9b76d046f75`、2026-09-17確認) のコード事実。#329 (Import Normalizer)・#330 (partial authoring v3)・#332 (clipboard/file-first import UI)・#336 (user-defined categories)・#348 (AI-facing contract sync) 実装merge後の状態。

### Import成功時の現行挙動

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  - `ExchangeFlowStateHolder.import(replyText)` (L475–523): IO上で `controller.importReply(replyText)` を実行し、`ExchangeImportResult.Validated` のとき **即座に** run接続へ進む:
    - runが `State.Selecting` (run内entry): `run.attachIntent(validated)`。`Attached` → `status = IMPORT_ACCEPTED` + `screen = Closed` (画面を閉じて1行status)。`NotAttachable` → `status = RUN_BUSY` + `Importing(replyText)` へ復帰。
    - それ以外 (idle entry): `run.start(intent = validated)`。`Started` → `IMPORT_ACCEPTED` + `Closed`。`Busy` → `RUN_BUSY` + `Importing` 復帰。
    - 失敗 (`Pipeline(Failure)` / `InputNotReady`) のみ `ExchangeScreen.ImportOutcomeScreen(outcome, rawText)` (失敗専用の「取り込み結果」画面、#332により認識framing/version/entry数のparse-first表示 + 折りたたみraw + 再取り込み付き)。
  - #332の入力経路: `importFromClipboard` / `onFileRead` → 共通receipt helper `receiveAndImport(text)` (L436–443) → envelope gate → `ExchangeScreen.Importing(text)` → `import(text)`。validation通過後の挙動は上記のまま (#332 specが境界として固定)。
  - `exchange_import_accepted` 文言 (ja: 「整理案を検証しました。プレビューを生成します…」) は実際の遷移とずれる (idle + 検出Readyなら次は選択surface、検出UnavailableならPreview直行でstatus行が非hostingになる)。
  - `ExchangeScreen` states: `Closed` / `SelectingPrivacy` / `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / `ImportOutcomeScreen`。成功用stateは存在しない。
  - **既存のsingle-flight構造パターン** (本planのCTAが踏襲する): `beginTransport()` (L355–362) — 呼出thread上で現在のdisclosure stateを検査し `transportAllowed` なら同期的にin-flightへflipして開始を許可 (拒否時は実行されない)。`settleBelongsTo(disclosure)` (L370) — settleは開始時と同じdisclosure (exportId anchor) が表示中のときのみ適用され、閉じた後/新世代の遅延結果はdropされる。
  - `exchangeFlowItems` (L557–684) は `LazyListScope` extensionであり、全exchange画面は **lazy list item** としてrenderされる (viewport外のitemはcompositionから外れ得る)。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `start(trigger, intent)` (L388–429): fresh run接続seam (#205)。**同期関数** (capture/composition/planningまで同時実行、呼出側がIO wrapperを担う — audit P2-1)。新RunId → `CandidateDetection` → `State.Selecting(runId, candidates, intentScopeCount)` または `runComposedPhase` 直行 (検出Unavailable)。`Busy` は `OrganizationOperationLease` gate。
  - `attachIntent(intent)` (L490–499、#331): `State.Selecting` 保持中に一度だけbind。`Attached` / `NotAttachable`。synchronized。
  - `State.Selecting` (L250–255) は `intentScopeCount` (選択surfaceの件数案内) と `scopeRejection` (`SCOPE_MISMATCH` 表示) を持つ。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `exchangeHolder` (L110–116): 画面compositionで無条件に `remember` される (常に生存)。
  - `ManualOrganizationBackHandler(coordinator)` (L186呼出、定義L905–941): **画面levelで常時composition** される `OnBackPressedCallback`。Back = `coordinator.dismiss()`。run active時はrun cancel (bound intent喪失)、idle時は `NoActiveOperation` → navigate away (exchange sub-flow状態は画面破棄で消失)。exchange sub-flow独自のBack扱いは存在しない。
  - `onStrategySelected` (L235–254): store書込が `Committed` かつrun active (`Idle`/`Cancelled` 以外) のとき `coordinator.dismiss()` → `coordinator.start(trigger)` (**実行中runを取り消して新runで差し替える**)。radio再選択 (同一strategy) はno-op。
  - idle branchの「整理を開始」row (L283–292、`manual_organization_start`) はexchange sub-flow表示中もtappable (競合affordance)。
  - run内entry (L313–365): `exchangeBusy = exchangeHolder.screen !is Closed` (L324) で **選択編集のみ** freeze (`editsEnabled = !exchangeBusy`、L351)。strategy pickerは凍結対象外。
  - `strategyPickerItems` (L724–728): run stateの `when` の **外側** に常時renderされ、全stateで操作可能。
  - idle exchange hosting (L734–747): `idleLike` (Idle/Cancelled) のときのみ。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`
  - `importReply(replyText): ExchangeImportOutcome` — `Pipeline(ExchangeImportResult)` / `InputNotReady(recognized)`。`ExchangeImportResult.Validated(validated: ValidatedPersonalizedIntent)` が成功値。
- **summary導出の正本データ (#330 v3)**:
  - `lawnchair/src/app/lawnchair/organizer/personalization/IntentCompletion.kt`: `RefDecision` = `Authored(intent)` / `UnresolvedAuthored` (明示unresolved + bare entry正規化) / `UnresolvedByOmission` (未言及)。`CompletedPersonalIntent` (`decisions: Map<String, RefDecision>`、全export refがちょうど1回) は `authoredItemCount` / `authoredUnresolvedCount` / `omittedCount` を派生propertyとして持つ。`IntentCompletion.complete` はbare entry (全semantic fieldがnull、L80–85) を `UnresolvedAuthored` へ正規化する (D-6)。
  - `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt` (L115–127, L144–147): validator成功path内でcompletionが実行され、identityはcompleted表現に対して計算される (D-5)。`ValidatedPersonalizedIntent.completed` はvalidation後に再導出可能 (deterministic)。**v3のcoverage規則は `itemIntents` refsと `unresolvedRefs` のdisjoint性のみ** (L59–70) で、v2の「全ref coverage」不変条件は廃止 — 未言及refはcompletionがcanonical unresolvedへ落とす。authored文書 (`intent.itemIntents` / `intent.unresolvedRefs`) はdiagnostics用のみ。
  - planner消費 (`lawnchair/src/app/lawnchair/organizer/personalization/IntentPlannerAdapter.kt` L22–27): `validated.completed.decisions` のうち `RefDecision.Authored` のみが `ItemPreference` へ射影される。判断なし3表現はplanner効果ゼロで同一。
  - 旧plan記載の「coverage不変条件により export items = `itemIntents` ∪ `unresolvedRefs`」は **v3では成立しない** (omissionが存在し得る)。`itemIntents.size` / `unresolvedRefs.size` を計数根拠にすると、bare entryが希望に誤計上され、omissionが判断なし件数から漏れる。
- 失敗表示: `exchangeContractFailureText` (13 contract種) + envelope 4種 + #329 normalization 2種 = **typed 19種** + 入力source status (`CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` / `FILE_READ_FAILED` / `INPUT_OVERSIZE`) + `InputNotReady`。
- strings: `lawnchair/res/values/strings.xml` + `values-ja/strings.xml`。成功系は `exchange_import_accepted` / `exchange_run_busy` のみ (`exchange_run_busy` = 「整理が実行中のため取り込めません。終了後に再度取り込んでください。」— CTA時拒否の案内としては「取り込み」が成功済みである点とずれるため、新規stringの可能性あり)。
- tests: `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` (846行。holderの実経路test、FakeStore/run注入、transport single-flight (`aSecondWriteFileWhileOneIsInFlightIsRefused`)・遅延settle drop (`lateResultFromAnOldWriteCannotTouchANewerDisclosure`) の既存pattern、#332 receipt経路test群)、`tests/unit/app/lawnchair/organizer/integration/exchange/ExchangeFlowControllerTest.kt`、`tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` (`attachIntent`/`start(intent)`/scope gate)。

推測ではなく上記file/行の確認済み事実 (baseline `8fd05a40`時点)。build/testは未実行 (本PRはdocs-only)。

## Design

### Modules and interfaces

- **`organizer/personalization/exchange/` (pure) — 取り込みsummary導出**: `ExchangeImportSummary` (data class: 件数のみ。`recognizedCount`、`noJudgmentCount`、`scopeCandidateCount`、optional内訳counts) と純粋関数 `exchangeImportSummary(validated: ValidatedPersonalizedIntent): ExchangeImportSummary`。入力は `validated.completed` (`CompletedPersonalIntent`) と `validated.session` のみ:
  - `recognizedCount = completed.authoredItemCount` (`RefDecision.Authored` のみ。bare entryは希望に計上されない)。
  - `noJudgmentCount = completed.authoredUnresolvedCount + completed.omittedCount` (合算。provenanceはuser-visibleにしない — spec 330 D-5/D-6のsemantic同一性)。
  - optional内訳は `decisions` の `Authored.intent` fieldのみから導出 (importance / desiredGroupRefs・groupSemantic / pageAffinity・regionAffinity / preserve)。
  - label・ref・自由文を型に持たせない (非混入を構造で保証)。Android-free (purity guard範囲内)。
- **`organizer/ui/exchange/ExchangeFlowUi.kt` — 取り込み成功状態**:
  - `ExchangeScreen.ImportSuccess(summary, validated, entryKind, continuing: Boolean = false)` を追加 (`entryKind`: idle / run内)。holderはvalidated intentを **process-localで一時保持** (新規永続化なし)。
  - `ExchangeFlowStateHolder.import()` の `Validated` 分岐を変更: attach/startを即時実行せず `screen = ImportSuccess(...)` へ。`IMPORT_ACCEPTED` statusの即時表示は廃止 (文言の誤用解消)。
  - 新規操作 **`continueImport()` — single-flight**:
    1. 呼出thread (production: Main) 上で `screen` を検査: `ImportSuccess` かつ `!continuing` 以外は即座にreturn (二重押下・処理中の再入はseamを呼ばない)。
    2. 同期的に `screen = ImportSuccess(..., continuing = true)` へflip (check-and-set。`beginTransport()` と同じ「状態経由の開始直列化」パターン)。CTA buttonは `continuing` でdisabled。
    3. `scope.launch(Dispatchers.IO)` 内で既存seamを実行 (run内: `run.attachIntent(validated)` / idle: `run.start(trigger, intent)`。`start` は同期 heavy なのでIO wrapperは現行 `import()` と同一の監査対応)。
    4. settleは `uiDispatcher` へ戻り、**開始した成功状態instanceに紐付く**: settle時の `screen` が同じvalidated intent (identity anchor: `validated.identity`。#330 D-5によりcompleted表現のcontent digestであり、同一semantic内容の再importは同一identity) かつ `continuing` のときのみ適用。それ以外の遅延settleはdrop (`settleBelongsTo` と同じ構造)。
    5. `Started` / `Attached` → `screen = Closed`。`Busy` / `NotAttachable` → `continuing = false` へ復帰 (再試行可) + typed status (成功状態維持、zero-write)。
  - 新規操作 `discardImport()`: pending intentを破棄し `screen = Closed`。`ExportSessionStore.invalidate` は呼ばない (再取り込み可能性維持)。
  - 成功状態composable: heading (成功、または判断なし合算 > 0ならwarning語彙) + summary行 (plurals) + 「未適用」明示行 + CTA (`Button`、`enabled = !continuing`) + 破棄 (`OutlinedButton`、破棄を示すlabel) + 再取り込み案内 (破棄時)。testTag・live region・focus (既存 `FocusTargetText`/`liveRegion` パターン準拠)。
- **`ui/preferences/destinations/ManualOrganizationPreferences.kt` — hosting調整 (Back interception + 競合freeze)**:
  - **system Backのinterceptionは成功stateのlazy item内ではなく、画面level (常時composition) に置く**: `ManualOrganizationBackHandler(coordinator)` (L186) の **呼出より後** に、`exchangeHolder.screen is ImportSuccess` でenabledになる `OnBackPressedCallback` (または同等の `BackHandler`) を同一画面levelで登録する。Compose `OnBackPressedDispatcher` は最後に追加されたenabled callbackへ委譲するため、成功state表示中のみこのcallbackが優先され、それ以外は既存の画面level Backが従来どおり動く。成功stateのlazy itemがviewport外 (large font等) でcompositionから外れても、interceptionはhosting画面compositionに常駐するため保護が損なわれない。callback内はD-2確定内容 (案: 確認dialog → `discardImport()`) を実行する。
  - idle branchの「整理を開始」rowを、`exchangeHolder.screen is ImportSuccess` の間無効化。
  - **run内branchのstrategy picker凍結**: `State.Selecting` かつ `exchangeHolder.screen is ImportSuccess` の間、strategy pickerの選択変更 (`onStrategySelected` のrun active時 `dismiss()` → `start(trigger)` path) を無効化する (radio無効化 + a11y理由表示)。idle entryではpickerを無効化しない (run非activeのため再開pathが発動せず、成功状態は不変 — spec scenarioどおり)。
  - hosting条件 (idleLike / Selecting) 自体は無変更。選択編集freezeは既存 `exchangeBusy` が `ImportSuccess` を包含するため追加変更なし。
- **strings (`values/` + `values-ja/`)**: 成功heading・未適用行・summary系 (plurals)・判断なし説明・CTA・破棄label・破棄確認・再取り込み案内・CTA時busy案内 (`exchange_run_busy` 再利用可否を確認 — 「取り込めません」は成功済みと矛盾するため新規stringが妥当な見込み)。jaを正本とする。
- **変更しないseam**: `ManualOrganizationRun.start/attachIntent/confirmSelection`、`ExchangeFlowController.importReply`、`ExchangeImportPipeline`、`IntentCompletion`/`IntentValidator` (#330の実装)、失敗表示 (`ImportOutcomeScreen`)、export flow (生成〜transport)。CTAは既存seamを呼ぶだけ。

### Data flow

```text
import (clipboard/file/貼付 → 共通receipt path、#332実装済み)
  → controller.importReply (既存、変更なし)
  ├─ Failure/InputNotReady → ImportOutcomeScreen (既存、無変更)
  └─ Validated → ExchangeImportSummary導出 (pure、validated.completed基準) → ImportSuccess状態
        ├─ run state不変 (idle: Idle/Cancelled、run内: Selecting+freeze継続、zero-write)
        ├─ 競合freeze (idle: 「整理を開始」row無効 / run内: strategy picker凍結)
        ├─ system Back → 画面level interception (成功item画面外でも有効) → D-2確定の破棄handling
        └─ CTA (single-flight: 同期continuing flip → IO seam呼出 → identity anchored settle)
              ├─ idle: run.start(trigger, intent) / run内: run.attachIntent — 各1回のみ
              │     ├─ 成功 → Closed → 既存flow (選択 → planning → preview → confirm → apply)
              │     └─ Busy/NotAttachable → continuing解除 + typed status (成功状態維持・再試行可)
              │     └─ 遅延settle (成功状態が閉じた後) → drop
              └─ 破棄 (明示操作/Back+確認) → intent破棄・session無invalidate → Closed
                    (依頼有効期間中は同じ回答textの再取り込みが成立)
```

### Alternatives rejected

- **BackHandlerを成功state composable (lazy item) 内に登録し既存 `ManualOrganizationBackHandler` より後に登録して優先させる** (初回draft案、review Required 2): exchange画面は全てlazy list itemであり、large font等で成功itemがviewport外に出るとcompositionから外れ、callback自体が存在しなくなる。その状態のsystem Backは親の `dismiss()` (run cancel / navigate away) に落ち、pending intentが黙って消える (AC-5/AC-7違反)。interceptionを常時compositionされるhosting画面levelへ置く設計に固定。
- **run内 `ImportSuccess` 中もstrategy pickerを許可** (初回draft案、review Required 3): `onStrategySelected` はrun active時に `dismiss()` → `start(trigger)` でrunを差し替えるため、pending intentのbind先・freeze済み選択・成功状態のCTA対象が契約外に入れ替わり得る。run内成功状態中はpickerのrun再開pathを無効化。
- **CTAを素通しでIO coroutineに投げる** (review Required 4): 複数回押下で複数coroutineが同じ成功状態を読み、`start`/`attachIntent` を複数回呼ぶ。settle順により成功後にstale `RUN_BUSY` が残る等のUI競合。`continuing` flagの同期check-and-set + identity anchored settleで構造的に排除。
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
| `organizer/ui/exchange/ExchangeFlowUi.kt` | `ImportSuccess(summary, validated, entryKind, continuing)` state、`continueImport` (single-flight) / `discardImport`、成功composable、`import()` Validated分岐変更、`IMPORT_ACCEPTED` 即時表示廃止 | exchange flow所有のUI state machine。single-flightは `beginTransport`/`settleBelongsTo` と同一ファイルの既存パターンに準拠 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | 画面level Back interception (成功state表示中のみenabled、`ManualOrganizationBackHandler` より後に登録)、idle競合row無効化、run内strategy picker凍結 | Back保証・競合排除はhosting画面の常時compositionが正しい場所 (lazy item内不採用) |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 新規strings (成功/summary/CTA/破棄/案内/picker凍結理由) | spec 123 ja/en契約 |
| `organizer/ui/ManualOrganizationRun.kt` | **変更なし** (`start(trigger, intent)` / `attachIntent` をそのまま利用) | seam不変 |
| `organizer/integration/exchange/ExchangeFlowController.kt` | **変更なし** (`importReply` が既に `Validated` を返す) | seam不変 |
| `organizer/personalization/` (IntentCompletion/IntentValidator) | **変更なし** (#330実装を消費するのみ) | seam不変 |

## Migration and recovery

- DB schema・storage変更なし。rollback = UI revertのみ。release後の残余dataなし。
- 失敗中のrollback: 不要 (CTAまでzero-write。CTA以降は既存run flowのrecovery)。
- 破棄後の回復: 同じ回答textの再取り込み (export session有効期限内。`CONTEXT_STALE` はhome変更時のみ既存どおり)。
- CTA処理中のprocess death: `start` がIO coroutine内で完了済みなら通常のrun flowへ継続 (既存挙動)。continuing flipのみで死亡した場合は成功状態ごと消失し、回復は再取り込み (pending intent非保持の既存philosophyどおり)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | holder unit test: validated → `ImportSuccess`、`start`/`attachIntent` 未呼出、run state不変。hosting instrumentation test | `./gradlew :lawnchair:testLawnchairGithubDebugUnitTest` (既存exchange test suite拡張) |
| AC-2 | string解決 + 表示test (unit/instrumentation) | 同上 |
| AC-3 | holder unit test: CTA → seam呼出 (`Started`/`Attached`)、`Busy`/`NotAttachable` でcontinuing解除+状態維持。**二重押下 (continuing中の再呼出) でseam 1回のみ**、**閉じた成功状態への遅延settle drop** (既存 `lateResultFromAnOldWriteCannotTouchANewerDisclosure` と同型test)。`ManualOrganizationRunTest` 連携 | 同上 |
| AC-4 | `ExchangeImportSummary` unit test: `authoredItemCount` 導出、bare entry非計上 (bare 1件 → 0/1)、omission計上 (未言及2件 → 判断なし2)、合算の境界 (0件で行非表示)、内訳はAuthored由来のみ + 出力modelのlabel/ref/text非混入 (型構造で保証 + contract test) | 同上 |
| AC-5 | holder unit test: discard → invalidate未呼出・再取り込み往復成立・pending intent消滅。idle競合row無効化 + run内strategy picker凍結のUI test | unit + instrumentation |
| AC-6 | semantics/live region assertion + TalkBack手動evidence | instrumentation + manual |
| AC-7 | instrumentation: 成功state表示中に成功itemをviewport外へscroll (またはfont scale最大) → system Back → 親dismiss/navigate非発火assertion | instrumentation |
| AC-8 | font scale最大でのinstrumentation/手動evidence | manual (emulator/device) |
| AC-9 | physical device: Import → CTA → 選択/composition → preview のevidence | device (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-10 | 既存suite regression (`ExchangeFlowStateHolderTest`、`ExchangeFlowControllerTest`、失敗表示19種、`ManualOrganizationRunTest`) | 同上 |

含める観察: unit (state machine遷移・summary導出・discard往復・single-flight)、contract (summary非混入、failure表示regression)、failure injection (CTA時Busy/NotAttachable、二重押下、遅延settle、破棄→再取り込み)、a11y/large font (画面外Back含む)、e2e device evidence。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md (用語: 取り込み成功状態、取り込み破棄、判断なし項目。acceptance時)
- [ ] DESIGN.md §4/§9 該当箇所への反映 (exchange UIに成功状態が加わること。acceptance時に判断)
- [ ] ADR: なし想定 (破棄semantics・summary基準はspec Decisionsに根拠を記録。変更困難性が3条件を満たすかは実装後判断)
- [ ] requirements.md (FR-017 status整理 — 本Issue単独ではFR-017完了を意味しない)

## Dependencies and blockers

- **spec D-2/D-3のowner確定** (Open questions 1/2) — implementation-readyの前提。
- #205/#331/#204/#228/#194/#195: すべてimplemented/acceptedであり技術blockerなし。
- #329/#330/#332/#348: implemented (2026-09-17時点のorigin/main `8fd05a40`)。#332の入力UIは本計画の接続点 (`Validated` → `ImportSuccess`) と既に整合済み。#330のcanonical representationはsummary導出の入力として消費する。技術blockerなし。
- #327 (OPEN、interview-first化): 本状態と変更面の重なりなし (spec 329も同様の境界記載)。

## Risks

- run接続の延期により、検証通過からCTAまでの間にprocess deathが起きるとintentを失う (従来も同philosophy。回復は再取り込み。spec scenarioで明示済み)。
- `run.start` をCTA時まで遅らせることで、CTA時にlayoutが変化している可能性が生じる — 既存の2段stale検証 (`CONTEXT_STALE` はimport時、revision stale checkはplanning/apply時) で既にカバーされる。CTA遅延実行の経路をtestでcoverageする。
- Back interceptionの登録順依存 (hosting画面level callbackが `ManualOrganizationBackHandler` より後で追加されること) の実装忘れ・順序間違いは「黙喪失」の再発になる — AC-7のinstrumentation testで成功item画面外状態を含めて検証。
- strategy picker凍結のscope間違い (idleでまで凍結する/凍結漏れ) は競合契約の崩れ — idle/run内両entryのUI testで検証。
- `validated.identity` をsettle anchorに使う場合、同一semantic内容の再importが同一identityを持つ (D-5の仕様) — これはanchorとして正しい (同一内容の接続は観測上区別不要) が、実装時にanchorの等価性条件 (identity一致 + continuing) をtestで固定する。
- `exchange_run_busy` 文案の不適合リスク (「取り込めません」vs 成功済み) — 新規string前提で進め、既存string流用は実装時に比較判断。

## Explicitly unverified areas

- 本draft時点でbuild/test未実行 (docs-only変更のため)。
- TalkBack実機での読み分け・large font実機表示・viewport外Back の実機evidence・end-to-end evidence は未実施 (実装PR・evidence PRの対象)。
- `exchange_run_busy` 文案がCTA時拒否の案内としてそのまま適合するかは実装時に確認 (適合しなければ新規string)。
- Compose `OnBackPressedDispatcher` の「最後に追加されたenabled callback優先」挙動への依存はframework仕様に基づく設計判断であり、AC-7のinstrumentation testがこの依存の回帰guardを兼ねる。

## Execution checklist

- [ ] Current behavior reproduced (即時attach/start + 1行statusをtestで固定した上で置換)。
- [ ] summary純粋関数とholder遷移の失敗testを先行追加 (canonical計数: bare/omission、single-flight: 二重押下・遅延settle)。
- [ ] Minimal implementation (ImportSuccess + single-flight CTA + discard + 画面level Back interception + strings + 競合row/picker無効化)。
- [ ] Migration/recovery verified (不要 — 変更なしの確認)。
- [ ] Full relevant verification completed (unit + regression + a11y/large font/viewport外Back + device evidence)。
- [ ] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-16: 初回draft (baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` = origin/main)。現行実装 (`ExchangeFlowUi.import()` の即時attach/start、失敗専用ImportOutcomeScreen、Idle「整理を開始」row・Back handlerの現状) をコード確認して作成。
- 2026-09-18: Re-entry revision (review Required 1–4対応)。origin/mainを `8fd05a40d51a` へ追従 (merge commit `6c3c3f9fd9`、#329/#330/#332/#336/#348実装取り込み済み) の上、Current evidenceを現行コードで全面更新。**(1)** summary導出を `validated.completed` canonical基準へ変更 (`authoredItemCount` / `authoredUnresolvedCount + omittedCount` 合算、Authoredのみの内訳。旧coverage不変条件の記載除去)。**(2)** Back interceptionをhosting画面level (常時composition) へ移動 — lazy item内BackHandler案をrejectedに記録、AC-7 viewport外Back testを追加。**(3)** run内成功状態中のstrategy picker凍結をChange set/data flow/検証へ追加。**(4)** `continueImport()` のsingle-flight設計 (同期continuing flip、identity anchored settle、遅延settle drop) を既存 `beginTransport`/`settleBelongsTo` パターンに整合させて明記、AC-3 oracleへ二重押下/遅延settle testを追加。失敗種別をtyped 19種へ更新。
