# Implementation Plan: strategy pickerのT-05移設とrun中変更特例の廃止

> Issue: #368
> Spec: [spec.md](./spec.md)
> Status: accepted（2026-09-19。spec re-review Approve相当 @ `5d5b61ee4e` とowner指示
> （reviewクリア後に実装へ進行）により受入。実行チェックリストの開始条件は確認済み）

## Current evidence

以下はすべてmain `ca9c171e91c2`（2026-09-19、PR #383 merge後。#365正本改訂・#366 hub
shell・#367材料集約をすべて含む）で再確認した現行実装の事実である。前版planが
「要再確認」としていた#366/#367接続面は実名で確定済み。

### run面pickerとrestart特例の現行実装（変更対象）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  （1,750行超）:
  - 119〜158行目: `StrategyWriteArbiter`構築。`writeStrategy` =
    `LayoutStrategySelectionModule.store(context).select(id)` の`Committed`判定、
    `restartRun = { coordinator.dismiss(); coordinator.start(trigger) }`（131〜134行目、
    監査D-3が観察した経路そのもの）、`writeStartBlocked`/`restartSuppressed` =
    exchange truth table呼出（run-in entry判定 `state is State.Selecting` 含む）、
    `restartNeeded = state !is Idle && !is Cancelled`（150〜153行目）。
    158行目: `exchangeHolder.strategyArbiterBusy = { strategyArbiter.busy }`
    （holder→arbiterの逆方向参照）。
  - 263〜295行目: catalog読取（`BuiltInOrganizerPolicyBundleSource.readActive()`）、
    `selectedStrategy`（absent時default表示・読取失敗時null＝fail-closed）、
    `onStrategySelected`（再選択no-op、arbiter経由）。
  - 297〜316行目: `strategyPickerFreeze`（Selecting中は`importAttemptActive`、それ以外は
    `importContinuationActive`）、`strategyPickerEnabled`、`strategyFrozenReason`
    （`exchange_strategy_frozen_continuing` / `exchange_strategy_frozen_import`）。
  - 804〜810行目: `strategyPickerItems(...)` をrun状態`when`ブロックの**外**で無条件呼出
    （＝全run状態・Idleにpickerが常時表示される。これが「run面picker」の本体）。
  - 811〜829行目: exchange `exchangeFlowItems`はidle-like（Idle/Cancelled）のみ。
  - 933行目〜: `strategyPickerItems`定義（selectableGroup、radio行、visual-only
    `RadioButton(onClick = null)`＝spec 283実装済みaffordance、frozen理由行live region、
    testTag `manual-organization-strategy-picker`）。
  - 902〜924行目: `strategyDisplayName`/`strategyDescription`（8 strategy＋unknownの
    resource対応表）。842〜845行目: `readSelectedStrategy`。
  - 1002行目〜: `ManualOrganizationBackHandler`（Backで`coordinator.dismiss()`、
    dispose時も`dismiss()`）。

- `lawnchair/src/app/lawnchair/organizer/ui/StrategyWriteArbiter.kt`（138行）:
  `IDLE → WRITING → RESTART_RESERVED → RESTARTING → IDLE`状態機械。constructor seam
  `writeStrategy`/`restartRun`/`writeStartBlocked`/`restartSuppressed`/`restartNeeded`。
  commit→（Main上でrestart可否判定）→IO上で`restartRun()`。`finally`相当
  （`NonCancellable + mainDispatcher`）で全終端`IDLE`解除。
  前版review「高」指摘の本体: 書込gateは`writeStartBlocked()`のMain上読取のみで、
  `store.select`はIO coroutine上で非同期実行される。このためgate通過後〜publication
  完了前に`ManualOrganizationRun.start()`（独立したRUN admission）が成立し、
  AC-2の「run active中はselection store不変」が構造的に保証できない。

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`:
  - 144〜159行目: `strategyWriteStartBlockedFor`/`strategyRestartSuppressedFor`
    （pure truth table。共に `importContinuationActive || (runInEntry && importAttemptActive)`）。
  - 597行目: `strategyArbiterBusy`（holderのcallback、default no-op）。
  - 633〜636行目/750〜753行目: import開始・CTA開始のarbiter busy拒否。
  - 853/859行目: `ExchangeStatus.Kind.IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`。
  - 1792〜1793行目: status→string対応（`exchange_import_strategy_busy`/
    `exchange_import_cta_strategy_busy`）。

### admission domainとoperation lifetime（前版review「高」「中」指摘の正本となる機構）

- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOperationLease.kt`（45行）:
  process-localな単一admission domain。`Kind { RUN, RECOVERY, AUTHORING }`、単一lock内の
  `tryAcquire`（active token存在中はnull）、`Token.close()`で解放。
  `NoopOrganizationOperationGate`（state machine unit test用のno-op実装）も同file。
  **domain自体は本Issueで変更しない**。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（1,344行）:
  - 161行目付近: `internal object ManualOrganizationModule`。production構築時に
    `operationGate = OrganizationOperationLease`を注入（181行目）。T-05 destination
    （同一module）からも参照可能。
  - `beginOperation`（1102〜1121行目）: `operationGate.tryAcquire(Kind.RUN)` 失敗でnull
    →`StartOutcome.Busy`。lock内で`activeOperation`設定・`State.Capturing`へ遷移。
  - operation終了点（lease解放を伴う）: `finish()`（1133行目〜、`Applied`/
    `NoChanges`/`CandidateResolutionFailed`等の正常・typed終端）、
    `transitionToStale()`（1085〜1100行目、`State.Stale`）、`cancel()`（816〜847行目、
    `State.Cancelled`）、`dismiss()`（1013〜1058行目、`State.Cancelled`。
    `activeOperation == null`なら`NoActiveOperation`を返すだけで終端stateを`Idle`へ
    正規化しない）。
  - recovery: `recoveryLease`（925〜929行目で`Kind.RECOVERY`取得。`dismiss()`で解放）。
  - **重要（前版review「中」指摘の事実根拠）**: 終端state（`Applied`/`NoChanges`/
    `CandidateResolutionFailed`/`Stale`/`Cancelled`等）はoperation終了（`activeOperation
    = null`・lease解放）後も`stateFlow`上に表示stateとして残る。したがって
    `state !is Idle && !is Cancelled`という述語は「operationが生きている」の正本に
    ならない（旧`restartNeeded`は「同じrun面上でrestart対象にするか」の述語だった）。
- 既存のauthoring先行例（本Issueが従うpattern）:
  - `UserDefinedCategoryAuthoring.kt`（95/137/185行目）・`CategoryOverrideAuthoring.kt`
    （146行目）: mutation開始時に`OrganizationOperationLease.tryAcquire(Kind.AUTHORING)`、
    取得失敗はtyped結果（`OrganizationRunActive`）、`finally`で解放。mutation全体が
    RUN/RECOVERYと同一admission domainに入る。

### hub shellと材料セクション（#366実装済み。実名確定）

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`
  （237行）: route `HomeScreenOrganizer`（`PreferenceRoutes.kt:118`、引数なし
  data object）。材料group（160〜179行目）はheading `organizer_hub_materials_heading` の
  `PreferenceGroup`内にT-02（`HomeScreenCategoryOverrides`）・T-03
  （`HomeScreenCustomCategories`）・T-04（`HomeScreenPlacementLocks`）への
  `NavigationActionPreference`＋`OrganizerUsageMaterialRows()`。T-05 entryはこのgroupへ
  同型で追加する。status card・start CTA・diagnostics rowの既存構成は変更しない。
- `OrganizerUsageMaterialRows.kt`（#367）: usage材料2行。材料rowのlabel/subtitle
  resource pattern（`organizer_*_title`/`organizer_*_summary`）の先行例。

### 文字列

- `lawnchair/res/values/strings.xml`:
  - 1399/1400行目: `exchange_import_strategy_busy`/`exchange_import_cta_strategy_busy`
    （arbiter busy中のimport/CTA拒否typed案内。削除候補）。
  - 1403/1404行目: `exchange_strategy_frozen_import`/`exchange_strategy_frozen_continuing`
    （picker frozen理由。削除候補）。
  - 1019行目: `manual_organization_strategy_section`（picker section表題）。
- `values-ja/strings.xml`: 199行目・486/487行目・490/491行目に対応。
- picker行のname/description 8 strategy分は現行どおり使用される（削除対象外）。
- 新規: T-05 entry label/subtitle（`organizer_strategy_*`系。実装PRで命名）、
  operation active中のfrozen理由（D-03語彙「中断してから変更する」）。

### testの現状（旧挙動のoracle）

- `tests/unit/app/lawnchair/organizer/ui/StrategyWriteArbiterTest.kt`（220行）:
  commit+restart（92行目）、`restartNeeded=false`→Committed-no-restart（108行目）、
  `restartSuppressed`→restart不発火（119行目）、`restartThrows`→解放（137行目）、
  cancel→no restart（155行目）、`RESTART_RESERVED`/`RESTARTING` window
  （170〜198行目）、non-idle中busy表明（206〜215行目）。restart系oracleが本体。
- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt`
  （1,747行超）: 1361〜1377行目 `strategyArbiterBusy`経由のimport拒否、
  1526〜1540行目 truth table表明、1546〜1600行目 arbiter連成integration
  （書込→解放→import再試行等）、1709〜1714行目 status string一覧のうちstrategy busy 2件
  ＋frozen 2件。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/exchange/
  ExchangeImportSuccessInstrumentationTest.kt`: 311〜344行目
  `receiptRefusedByTheStrategyArbiterStaysRetryableFromTheHeldText`。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  StrategyPickerInstrumentationTest.kt`（383行）: picker契約の本oracle
  （runtime-supportedのみ、default-as-effective、fail-closed、selectableGroup、
  parent row単一truth、200% font、書込経由公開、再選択no-op）。
  `previewlessRunner()`でrun面ごとcomposeしている（pickerはrun面host前提）。
- `tests/organizer-instrumentation/app/lawnchair/ui/preferences/destinations/
  StrategyPickerFreezeInstrumentationTest.kt`（97行）: frozen affordance oracle
  （disabled行＋live region理由、enabled時理由行なし、continuation理由copy分離）。
  `strategyPickerItems`を直接compose（run面に非依存）。
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/
  OrganizerHubPreferencesInstrumentationTest.kt`（#366）: hub構成のoracle。
  T-05 entry追加のnavigation assertはここに拡張する。
- `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` +
  `ManualOrganizationRunTestSupport.kt`: run coordinatorの決定的test土台
  （`NoopOrganizationOperationGate`切替を含む）。AC-9の(a)/(d)は実
  `OrganizationOperationLease`を使う場合とNoop注入の場合を分けて構成する。
- strategy非依存の既存oracle: `ManualOrganizationPreferencesInstrumentationTest` /
  `ManualOrganizationProductionE2EInstrumentationTest`（pickerを直接参照しない。
  `organizationStrategy`系はprovenance/plan結果のfixture値でrun面UIと無関係）、
  `LayoutStrategySelectionStoreTest`、composer/provenance系unit test、
  `OrganizationOperationLeaseTest`。

## Design

### Modules and interfaces

- **T-05 destination（新設）**: 既存材料と同型の引数なしroute（仮称
  `HomeScreenOrganizerStrategy`。最終名は実装PRで確定）を`PreferenceRoutes.kt`へ追加し、
  `PreferenceNavigation.kt`へ登録する。destination（仮称
  `OrganizerStrategyPreferences.kt`）は現行`strategyPickerItems`＋`readSelectedStrategy`＋
  `strategyDisplayName`/`strategyDescription`を受け持つ。`PreferenceScaffold` +
  `PreferenceLazyColumn`構成でsection表題（`manual_organization_strategy_section`を
  titleに再利用するか、新規title stringにするかは実装PRで確定。既存8行のradio
  契約は不変）。
- **書込のadmission（AC-2/AC-9の本体。前版「書込gate」設計の置換）**:
  `StrategyWriteArbiter`へ`OrganizationOperationGate`を注入し（production =
  `OrganizationOperationLease`、unit test = `NoopOrganizationOperationGate`または
  制御可能なfake gate）、`onStrategySelected`のMain-confined遷移点で
  `tryAcquire(Kind.AUTHORING)`を行う。取得成功は`state = WRITING`と同一直列化点で
  原子に行い、tokenはcoroutine終端（`NonCancellable`内。`Token.close()`はthread-safe）
  で解放する。
  - **typed開始outcome（前版review「中」対応。`onStrategySelected`の返却型変更）**:
    `onStrategySelected(id, onCommitted): StartOutcome`へ変更し、開始結果を
    `Started` / `RefusedRunOrRecoveryActive` / `RefusedAuthoringBusy` / `RefusedWriteBusy`
    で返す（非同期のcommit結果は従来どおり`onCommitted`で通知し、開始outcomeと分離）。
    順序: (1) `state != IDLE` → `RefusedWriteBusy`（single-flight、leaseに触れない）。
    (2) `tryAcquire(AUTHORING)`。失敗したら同一Main処理内でrun/recovery projectionを
    読み、trueなら`RefusedRunOrRecoveryActive`、falseなら`RefusedAuthoringBusy`。
    (3) 取得成功 → `state = WRITING` → coroutine起動、`Started`を返す。
    いずれの拒否もstore呼出なし・`WRITING`へ入らない。分類読取は表示・oracleのための
    情報であり、排他の正本はtoken取得自体（極小の分類競合窓では
    `RefusedAuthoringBusy`側に倒れるが、どちらのoutcomeでも挙動はstore不変・retry可能）。
  - これにより「gate通過後〜publication完了前にrun開始」の競合窓が消える:
    書込先行 → `start()`は`tryAcquire(RUN)`失敗で`StartOutcome.Busy`。
    run先行 → `tryAcquire(AUTHORING)`失敗で書込開始せず。category authoringと同一の
    単一admission domain原則である。
  - exchange truth table seam（`writeStartBlocked`）と`restartSuppressed`/
    `restartNeeded`/`restartRun` seamはconstructor引数から削除し、代わりに
    `runOrRecoveryActive: () -> Boolean` seam（production = coordinatorのoperation
    lifetime projection読取、unit test = fake）を追加する。arbiterの責務は
    「書込single-flight＋admission domain参加＋typed開始outcome」である。
- **operation lifetime projection（前版review「中」対応）**:
  `ManualOrganizationRun`へ`val operationActive: StateFlow<Boolean>`を新設し、
  `activeOperation`/`recoveryLease`が遷移する全site（`beginOperation`/`finish`/
  `transitionToStale`/`cancel`/`dismiss`/abort/recovery開始、いずれも同一`lock`内）で
  更新する。`activeOperation != null || recoveryLease != null`が正本定義であり、
  表示state（`State`列挙）からは導出しない。
  **役割分担（admission domain占有と同値ではないことを明記）**: このprojectionは
  run/recoveryのoccupancyを示す。admission domainは他の`AUTHORING` token
  （category authoring・strategy書込自身）によっても占有され得るが、それらに対する
  globalな観測面は存在しない（設計しない）。したがって:
  - T-05のdisabled affordance＋frozen理由は本projectionのみから導出する
    （run/recovery active時にdisabled＋理由。終端後に自動で有効へ復帰）。
  - 他`AUTHORING`占有・single-flight（projection falseでtoken取得のみ失敗）は、
    T-05 hostがarbiterのtyped開始outcome（`RefusedAuthoringBusy`/`RefusedWriteBusy`）
    を受けてretry文言をlive regionで通知する（行は有効表示のまま。frozen理由とは
    文言が区別される新規string）。防御呼出が黙ってdropしないことが契約。
- **arbiter簡素化**: `RESTART_RESERVED`/`RESTARTING`状態を削除し、状態機械を
  `IDLE → WRITING → IDLE`へ縮小する。`writeStrategy` seamとMain-confined直列化・
  全終端解放（`finally`相当でstate復帰＋token close）は現行のまま。
  `onStrategySelected`は開始outcomeを返却する型へ変更し、`internal enum class
  StartOutcome { Started, RefusedRunOrRecoveryActive, RefusedAuthoringBusy,
  RefusedWriteBusy }`を新設する（最終名は実装PRで確定。否定形/肯定形の命名は既存
  `ManualOrganizationRun.StartOutcome`と読み分けが付くようにする）。KDocを現実に合わせ
  更新する。
- **exchange逆参照の除去**: `ExchangeFlowStateHolder.strategyArbiterBusy` callback、
  `strategyWriteStartBlockedFor`/`strategyRestartSuppressedFor`、
  `ExchangeStatus.Kind.IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`とそのstring対応を
  削除する。strategy書込とexchangeの競合はadmission domainに還元される
  （書込中のCTA `start()`は`Busy`→既存のtyped failure settleで再試行可能。
  import開始（validation本体はrun seamを触らない）は書込と並行して構わない）。
  spec 328の該当条項はContract notes 2の境界で改訂する。
- **単なる委譲moduleは増やさない**（AGENTS.md）: arbiterは削減はするが廃止はしない
  （書込single-flightの正本として残す。処分文書§3.9「書込single-flightは維持」）。
  新規のinterfaceは作らず、既存`OrganizationOperationGate`/lease domainへ参加する。
  `operationActive`は`ManualOrganizationRun`の既存観測面（`stateFlow`/
  `readinessState`）と同型の単純なprojectionである。

### Data flow

- 選択（operation不在・他authoring不在）: T-05 → arbiter `Idle→Writing`＋`AUTHORING`
  取得（Main、原子）→ `Started` → IO上で`store.select`（検証付き書込）→ Main上で
  `onCommitted`（`selectedStrategy`更新）→ 終端で`AUTHORING`解放＋`Idle`。書込は次回
  run開始時のcomposer readで初めてplanningに到達する（fresh composition不変）。
- 選択（operation active）: `RefusedRunOrRecoveryActive` → store呼出なし。UIはoperation
  lifetime projectionによりdisabled＋frozen理由表示（outcomeと同一の正本から導出）。
- 選択（他authoring占有・書込in-flight）: `RefusedAuthoringBusy`/`RefusedWriteBusy` →
  store呼出なし。行は有効表示のまま、T-05 hostがretry文言をlive regionで通知。
- 書込中のrun開始: `start()` → `tryAcquire(RUN)`失敗 → `StartOutcome.Busy`。
- 変更なしのflow: run開始→composition（選択snapshotをcutに参加）→preview→confirm→
  apply→recovery。exchange導線（strategy関係gateを除く）・onboarding導線。

### Alternatives rejected

- **確認dialog付きでrestart特例を維持**: D-03・TO-BE §4.4が明示却下（E-7が残る）。
  Issue scopeも「特例の廃止」を本体とする。
- **pickerをrun面に残しrun中のみ無効化**: TO-BE §4.4却下（「変更したくても手がかりが
  ない」）。材料面への移設がD-03の本体であり、#366/#367の材料集約構成とも不整合。
- **Main上のrun state読取だけを書込gateにする（前版planの設計。review「高」で却下）**:
  `state !is Idle/Cancelled`の事前確認はgate通過後〜`store.select`のpublication完了前に
  `start()`のRUN admissionが成立させる競合窓を閉じられない。加えてstate列挙は終端state
  を表示上保持するためoperation lifetimeの正本でもない（review「中」）。lease取得という
  単一直列化点での相互排他に置換する。
- **`AUTHORING`取得失敗を黙ってno-opにする（rev.2 review「中」で却下）**:
  「typed non-write」契約を検証できず、他`AUTHORING`占有時（projectionがfalseでも
  domainは占有され得る）に有効表示の行の選択が黙ってdropされる。開始outcomeの返却で
  caller（T-05 host）がtypedに案内できるようにする。
- **`operationActive`をadmission domain占有の観測面にする**: AUTHORING tokenに対する
  globalな観測面は存在せず、新設するとlease domainの観測API追加（domain設計への接触）と
  authoring各所の発火実装が必要になる。run/recovery projection＋typed outcomeの
  役割分担で契約は満たすため不採用。
- **run-absent gateをselection store層へ入れる**: storeはRule Management所有の
  run非依存seam（spec 182 Selection contract）であり、run stateをstoreへ漏らすと
  seam責務を壊す（AGENTS.md「platform型やDB行を計画moduleのinterfaceへ漏らさない」の
  同型違反）。admissionは呼出側（arbiter）がlease domain経由で行う。
- **`State`列挙へoperation終端の正規化（全終端で`Idle`へ戻す）を追加する**:
  終端stateは実行面の表示（完了・stale案内等）の正本であり、#369が表示統合を所有する。
  本Issueでstate機械の表示契約を変えると#369との衝突と既存oracleの大規模改訂を生む。
  operation lifetimeを独立projectionにすることで表示契約は無変更で足りる。
- **arbiterを廃止しstore書込へ集約**: 処分文書§3.9が「書込single-flightは維持」を
  定める。store書込自体はatomicだが、UI状態更新（`onCommitted`）と書込の直列化・
  再選択no-op判定の正本が失われるため維持が正。
- **T-05をhub材料セクションへinline埋め込み**: spec Contract notes 3。8行radio groupを
  status card＋CTA＋材料導線と同一listへ混在させると材料導線との役割が不明瞭になり、
  T-02〜T-04（destination）との構成非対称になるため専用destinationを採る。
- **exchange側gate（`strategyArbiterBusy`等）を残置**: pickerとexchangeが同一
  destinationに共存しなくなり、gateの入力（`importAttemptActive`等）をarbiter側から
  観測する経路がなくなる。dead wiringを残すことはspec 328との不一致を隠すだけのため
  削除し、実装PRでContract notes 2の境界どおりspec 328を改訂する。

## Change set

| Path | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `strategyPickerItems`呼出（804〜810行目）、arbiter構築（119〜158行目）、`selectedStrategy`/`onStrategySelected`（263〜295行目）、freeze計算（297〜316行目）、catalog読取を削除。`strategyPickerItems`/`readSelectedStrategy`/display対応表はT-05 destinationへ移動（package公開範囲を見直し） | Issue本体。run面（全状態・両entry）からstrategy UIを撤去する（AC-1/2） |
| `lawnchair/src/app/lawnchair/organizer/ui/StrategyWriteArbiter.kt` | `RESTART_RESERVED`/`RESTARTING`削除、`restartRun`/`restartSuppressed`/`restartNeeded`/exchange truth table `writeStartBlocked` seam削除、`OrganizationOperationGate`＋`runOrRecoveryActive` seam注入、`AUTHORING`取得/全終端解放、`onStrategySelected`をtyped開始outcome（`Started`/`RefusedRunOrRecoveryActive`/`RefusedAuthoringBusy`/`RefusedWriteBusy`）返却へ変更、状態機械を`IDLE → WRITING → IDLE`へ縮小、KDoc更新 | restart特例廃止＋admission参加＋typed拒否の本体（AC-2/4/6/9） |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | `operationActive: StateFlow<Boolean>`新設（`activeOperation`/`recoveryLease`遷移の全site・同一lock内で更新。run/recovery occupancyのprojection。admission domain占有と同値ではない）。run seam本体（`start`/`dismiss`/`cancel`/`finish`/stale/recovery）は無変更 | operation lifetime正本の可観測化（AC-2/9。前版review「中」対応） |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `strategyWriteStartBlockedFor`/`strategyRestartSuppressedFor`削除、`strategyArbiterBusy` callback削除、import/CTAのstrategy busy拒否（633〜636/750〜753行目）と`ExchangeStatus.Kind` 2種・string対応（1792〜1793行目）を削除 | picker移設＋restart廃止後のdead wiring除去（AC-7。挙動変化はstrategy経路のみ） |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | T-05用route（引数なしdata object、仮称`HomeScreenOrganizerStrategy`）追加 | 既存材料destinationと同型。契約stateを持たない |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | `composable<T-05 route>`登録 | 既存登録規約 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerStrategyPreferences.kt`（新設、名称は実装PRで確定） | T-05 destination: picker本体＋`selectedStrategy`読取/表示＋検証付き書込（arbiter経由、`AUTHORING` admission）＋operation active時frozen表示＋typed開始outcomeのhandling（`RefusedAuthoringBusy`/`RefusedWriteBusy`時のretry文言live region通知。黙ってdropしない） | AC-1/2/3/5/6/8/9の受ける面 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | 材料`PreferenceGroup`（160〜179行目）へ「整理方針」entry（`NavigationActionPreference`、label/subtitle新規resource）を追加 | hub材料セクションがT-05への唯一の導線（AC-1。D-01/D-03） |
| `lawnchair/res/values/strings.xml`, `values-ja/strings.xml` | 新規: T-05 entry label/subtitle、operation active中frozen理由（D-03語彙）、他authoring占有/single-flight用retry文言（frozen理由と区別される。EN/ja対訳）。削除（reference grep確認後）: `exchange_strategy_frozen_import`/`exchange_strategy_frozen_continuing`/`exchange_import_strategy_busy`/`exchange_import_cta_strategy_busy`。`manual_organization_strategy_section`はT-05側で継続利用またはtitle用新設に置換 | AC-8。孤立resource排除（spec 123収束） |
| `tests/unit/app/lawnchair/organizer/ui/StrategyWriteArbiterTest.kt` | restart系oracle（restart系seam・`RESTART_RESERVED`/`RESTARTING` window・`restartThrows`）を削除、single-flight（`RefusedWriteBusy`）＋typed開始outcome（`RefusedRunOrRecoveryActive`/`RefusedAuthoringBusy`。fake gate＋fake `runOrRecoveryActive`で決定的に）＋全終端解放のtable-driven oracleへ改訂。削除分のobsolete理由（E-7解消・特例廃止）をPRに記録 | AC-4/6/9 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`（または隣接新設） | AC-9(a)(d): 実lease使用で書込停止中`start()`→`Busy`、終端state群（`Applied`/`NoChanges`/typed failure/`Stale`/`Cancelled`）後に`operationActive == false`かつ書込可能。`operationActive` projectionの遷移test | AC-9（前版review「高」「中」の必須oracle） |
| `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` | truth table test（1526〜1540行目）、`strategyArbiterBusy`連成test（1361〜1377/1546〜1600行目）、status string一覧のstrategy 4件（1709〜1714行目）を削除・更新。obsolete理由（picker run面撤去・restart廃止）をPRに記録。strategy非依存の残testは無編集green | AC-4/7 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/exchange/ExchangeImportSuccessInstrumentationTest.kt` | `receiptRefusedByTheStrategyArbiterStaysRetryableFromTheHeldText`（311〜344行目）を削除（obsolete理由をPRに記録）。他は無編集 | AC-4/7 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt` | hostをrun面composeからT-05 destination composeへ付け替え。既存assert（catalog/default-as-effective/fail-closed/selectableGroup/parent row truth/200% font/書込公開/再選択no-op）は維持。実行面の否定的観測testを追加 | AC-1/3/5 |
| `tests/organizer-instrumentation/app/lawnchair/ui/preferences/destinations/StrategyPickerFreezeInstrumentationTest.kt` | frozen理由をoperation active用新stringへ更新。exchange系理由case（`continuingFreezeUsesItsOwnReasonCopy`等）を削除しobsolete理由を記録。frozen affordance本体（disabled＋live region）oracleは維持。typed outcome経由のretry文言通知（frozen理由と文言が区別されること）のassertを追加 | AC-2/8 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OrganizerHubPreferencesInstrumentationTest.kt` | 材料`PreferenceGroup`に「整理方針」entryが存在しT-05へ開くnavigation assertを追加 | AC-1 |
| `specs/182-layout-strategy-catalog/spec.md` | §Preview integration第1bullet（225行目）を材料面T-05配置・operation不在時のみ書込可・callerはrestartしない へ改訂。§Selection contract write-authority step 4（146行目）を「on success, the caller starts a fresh compose/plan cycle」→「the committed selection reaches planning through the next composer read of a future run; the caller never dismisses or restarts an active run」へ改訂。Scenario（322行目）「selection write is validated...」のrun面表記と「strategy change replans...」を（preview表示中の変更は不可能になった旨へ）改訂。Change historyへ追記 | Issue本文「Spec」節・処分文書§3.9（AC-7） |
| `specs/283-strategy-picker-selected-affordance/spec.md` | Non-goalsの「run 表面への配置の変更」凍結と「dismiss + 再計画の挙動変更」凍結を解除し、AC-1〜AC-9がT-05に適用される旨を追記。Change historyへ追記（契約節本体は変更しない） | 同上（AC-5/7） |
| `specs/328-exchange-import-success-state/spec.md` | strategy固有条項の狭い改訂（Contract notes 2の境界）: run内entry picker freeze・idle picker continuation中無効化・commit時gate・exchange側書込開始gate・`RestartReserved`/`Restarting`状態機械・`IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`（およびAC-3/AC-5内の対応oracle項目 (d2)(d3)(d4)のstrategy部分・(e)(f)(g)(i)）を、lease基準の新契約（書込は`AUTHORING` admission・pickerはrun面不在・restart経路不存在）へ置換・削除。AC-3(d)(d5)のsingle-flight本体とidle start row freeze等の非strategy条項は維持。freeze再設計・durable契約は#374（rev.2）が所有する旨。Change historyへ追記 | Contract notes 2（AC-7）。意図的な正本↔実装不一致を作らない |
| `docs/product/organizer-disposition-migration.md` | §3.9/§3.14のownership境界を現実へ更新: spec 328はPR #353で実装済み（「未実装」表記の事実修正）、strategy固有条項の狭い改訂は#368が実行（本PR）、freeze再設計・durable契約は#374継続。判断本文（D-03処分・#374へのrev.2割当）は変更しない | Contract notes 2（AC-7）。正本を実装前に合わせる（AGENTS.md「正本を先に直す」） |
| `specs/368-strategy-picker-material-relocation/{spec,plan}.md` | status/history更新（実装PRで） | specs README rule |

変更しない: `organizer/application/**`、`organizer/planning/**`、`organizer/rules/**`
（`LayoutStrategySelectionModule`含む）、`organizer/integration/**`、
`organizer/diagnostics/**`、`OrganizationOperationLease`自体（domain設計は不変）、
Launcher3 bridge、selection store format、preview面のstrategy identity表示、
`ManualOrganizationRun`のrun seam契約（`start`/`dismiss`/`cancel`の挙動、state機械の
表示state列挙）、exchange導線本体（strategy関係gateを除く）、onboarding flow接続。

## Migration and recovery

- schema / rule migrationなし。persistent state変更なし（`organizer_strategy_selection/
  selection-v1`不変）。`favorites` への接触なし＝ホームレイアウト安全規約の適用対象外。
- 移行期間なし: run面pickerとT-05の新設は同一PRで行うため、strategy選択が不可能に
  なる期間は存在しない。
- rollback: PR revertでrun面picker＋restart特例へ戻る。本PRが書き得る状態はselection
  storeへの既存経路の書込みのみであり、revert後に不整合は残らない。T-05 entryが消える
  だけで、選択済みstrategyの値は保持され続ける。
- downgrade / backup-restore: 影響なし（新規永続データなし）。spec 182のdowngrade
  3-case modelが引き続きstore読取を所有する。
- failure handling: `AUTHORING`取得失敗はtyped non-write（store不変）。書込失敗は既存
  選択保持＋token解放（spec 182 AC-3b）。書込中のrun開始は`Busy`（既存typed outcome）。
  T-05読取失敗はfail-closed非選択表示（既存）。新規failure状態は存在しない。

## Risk assessment

- **risk: low**。UI構成移設・gate簡素化・test更新・docs表記が主体。
  `organizer/application/**`・Launcher3 writer系を含まず、`risk: layout-data`/
  `risk: migration` labelは付けない（high-risk evidence gateの対象外）。
- リスク点1: `operationActive` projectionの更新漏れ（`activeOperation`/`recoveryLease`
  の遷移siteでの更新忘れ）はT-05 affordanceの永続frozenを生み得る。→ 構造gate（lease）
  は別経路なので安全性は劣化しないが、AC-9(d)の終端別testでprojectionの復帰を固定する。
  更新siteの列挙を実装PRのdiff review対象とする。
- リスク点2: spec 328改訂の境界越え（非strategy条項への誤触）。→ Contract notes 2の
  境界リスト（改訂対象条項の列挙）をspecに固定し、diff reviewで「改訂箇所＝Scope節」と
  の一致を確認する（AC-7）。freeze再設計本体（#374）には着手しない。
- リスク点3: 削除対象stringのreference漏れ（4件以外に未使用化するresource、
  `manual_organization_strategy_section`の置換要否）。→ 実装PRで
  `git grep`によるreference確認を必須化する（AC-8）。
- リスク点4（実装時に実測済み）: two-pane（expanded）設定でT-05と実行面が同時compose
  され得るか。→ **実測の結果、発生しない**: production expanded settings
  （`Preferences.kt` TwoPane）はfirst=`PreferencesDashboard`／second=単一 movable
  `NavHost`であり、`HomeScreenManualOrganization`と`HomeScreenOrganizerStrategy`は
  別destinationのため同時composeされない。さらにrun面の`ManualOrganizationBackHandler`
  は`onDispose`→`dismiss()`を持つため、T-05 compose時点でoperationは終了している
  （`operationActive == false`、evidence READMEに記録）。→ さらにproduction同一
  transition構成のNavHostをclock pin付きで駆動するruntime oracle
  （`StrategyT05ProductionNavigationTest`）で **supported path（run面→hub→T-05）の順序を
  実測**: hop 1（run面→hub）遷移中はoutgoing run面とincoming hubが共存しoperationは
  まだalive（onDispose→dismiss未実行）、hop 1完了時にdismiss→operation終了
  （`State.Cancelled`）、hop 2（hub→T-05）はoperation終了後に開始するためT-05は
  unfrozenでcompose、終端で書込可能、を固定。**すなわちsupported path上でT-05が
  operation active中にcomposeされることはない**。frozen affordanceは将来のnavigation
  変更（#369）に備える防御構造として残す。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | instrumentation: T-05 picker表示・選択表示assert、実行面否定的観測（testTag/section/radio行不在。`MANUAL` entryでIdle/Selecting/Preview代表状態）、hub材料セクション→T-05 navigation assert | `connectedLawnWithQuickstepGithubDebugAndroidTest` 対象class filter |
| AC-2 | unit: `RUN` token保持中の書込開始拒否（store呼出非開始）。instrumentation: operation active模擬状態でのT-05 disabled/理由表示＋否定的観測 | unit test + 対象class filter |
| AC-3 | 既存`LayoutStrategySelectionStoreTest`・composer/provenance unit test無編集green + T-05 rehost後の書込公開assert | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-4 | `StrategyWriteArbiterTest`改訂diff、holder/instrumentation test削除diff、`git grep`でrestart系symbol残存0件、obsolete理由のPR本文記録 | 同上 + grep + PR diff review |
| AC-5 | 既存picker a11y oracle（T-05 rehost）+ light/dark screenshot（spec 283 AC-7 pattern） | 対象class filter + screenshot evidence |
| AC-6 | single-flight table-driven unit test（in-flight中2本目不開始・終端別`Idle`復帰） | unit test |
| AC-7 | strategy非依存exchange test無編集green + specs 182/283/328と処分文書のdiff review（改訂箇所＝spec Scope節＋Contract notes 2境界） | 同上 + PR diff review |
| AC-8 | frozen理由＋retry文言のlive region assert（文言の区別を含む）、string diff（EN/ja対訳）、削除string reference grep 0件 | instrumentation + `git grep` + `./gradlew spotlessCheck` |
| AC-9 | unit: (a) 書込publication前停止→`start()`=`Busy`、(b) `RUN`保持中の選択が`RefusedRunOrRecoveryActive`＋store非呼出、(b2) gate占有＋projection false（他`AUTHORING`保持を模擬）の選択が`RefusedAuthoringBusy`＋store非呼出、(c) 全終端での`AUTHORING`解放（table-driven）、(d) 終端state群後の書込可能 | unit test（実`OrganizationOperationLease`を使用するcaseを含む。outcome分類はfake gate/projectionで決定的に） |

含めるべき観点: UI（T-05構成・実行面否定的観測・hub navigation）、既存回帰（store/
composer/exchange無編集green）、accessibility（frozen理由読み上げ、radio group、
200% font）、localization（EN/ja対訳・削除locale一貫性）、failure injection（書込失敗時の
既存選択保持は既存store unit testが所有。本Issueで新設しない）、two-pane実測記録
（同時composeはproduction navigationで発生しない旨の実測＋synthetic防御oracle、
evidence README参照）。

## Dependencies and ordering

1. **spec/plan owner acceptance**（workflow Start gate。本taskはdraftまで。
   spec Contract notes 1〜3の解釈確認を含む。note 2はoption (A)で一意化済み）。
2. **#365/#366/#367: すべてmerge済み**（baseline `ca9c171e91c2` で確認済み。
   接続面の実名は本plan「Current evidence」に反映済み）。
3. **本Issue実装**（1 PR想定）: source移設・arbiter簡素化＋`AUTHORING` admission・
   `operationActive`新設・test更新・specs 182/283/328改訂・処分文書境界更新・
   string整理を同一PRへ。手順はExecution checklist。
4. **後続**: #369（Depends on #368）、#374（spec 328 rev.2/freeze再設計。
   Contract notes 2の境界を継承）、#377（残oracle清掃）。

## Explicitly unverified areas

- ~~two-pane（expanded）設定でT-05と実行面が同時composeされ得るかの実画面確認~~
  → 実装時に実測済み: production navigationでは同時composeされない（plan「Risk」
  参照。evidence READMEに記録）。operation active中のT-05 frozenはsynthetic
  composition oracleで固定（将来のnavigation変更に備える防御契約）。
- `manual_organization_strategy_section`をT-05画面titleへ再利用するか新設に置換するか
  （spec 161 copy規約に従い実装PRで確定）。
- 削除により未使用化するstringがChange setの4件（＋場合によりsection表題の置換）のみ
  であることの最終確認（reference grep）。
- run面の否定的観測testで網羅する状態集合（Idle/Selecting/Previewの代表3状態を想定。
  全20状態の網羅は実装時に費用対効果で決める）。
- spec 328改訂の最終文言（対象条項の列挙はContract notes 2で固定済み。diff reviewで
  境界一致を確認）。

## Documentation updates

- [ ] `specs/368-strategy-picker-material-relocation/spec.md` / `plan.md` status・history（本PR）
- [ ] `specs/182-layout-strategy-catalog/spec.md` 改訂（本PR。§Preview integration・
      write-authority step 4・2 scenario）
- [ ] `specs/283-strategy-picker-selected-affordance/spec.md` 改訂（本PR。Non-goals解除・
      T-05適用追記）
- [ ] `specs/328-exchange-import-success-state/spec.md` strategy固有条項の狭い改訂
      （本PR。Contract notes 2の境界。rev.2/freeze再設計は#374）
- [ ] `docs/product/organizer-disposition-migration.md` §3.9/§3.14境界更新（本PR。
      spec 328実装済みの事実修正を含む）
- [ ] `CONTEXT.md`: 実施しない（材料・語彙は#365が追加済み）
- [ ] `DESIGN.md`: 実施しない（gate 13のspec 328参照は「strategy書込との相互排他」の
      所有を示すpointerのまま変質しない。spec 328は改訂後も当該責務を保持し続ける）
- [ ] ADR: 不要（D-03の選択肢比較はorganizer-to-be-ux.md §4.4に記録済み。処分文書§7
      「ADR追加なし」どおり。lease domain既存設計への参加は新規の変更困難な判断ではない）

## Execution checklist

1. [ ] 実装開始条件の確認: 本spec/plan `accepted`（Contract notes 1〜3解消済み）。
       #365/#366/#367 merge済みは確認済み（baseline `ca9c171e91c2`）。
2. [ ] 失敗するtestを先に: AC-9のlease相互排他oracle（(a)〜(c)、(b2)のtyped outcomeを
       含む）とoperation lifetime projection testを追加（赤で固定）。実行面strategy UI不在の
       否定的観測assertとhub→T-05 navigation assertを追加（AC-1を赤で固定）。
3. [ ] `StrategyWriteArbiterTest`を先に改訂: restart oracle削除＋`AUTHORING` gate/
       typed outcome/single-flight oracle新設（AC-2/4/6/9を赤で固定）。obsolete理由を
       PR草稿へ記録。
4. [ ] arbiter簡素化（restart状態・seam削除＋lease参加）＋`operationActive`新設＋
       T-05 destination新設（route/登録/destination/hub entry）＋run面からのpicker撤去
       （AC-1/2/4/9）。
5. [ ] exchange逆参照除去（truth table・callback・status 2種）＋holder/instrumentation
       test更新（AC-4/7）。
6. [ ] string新設・削除＋reference grep（AC-8）。
7. [ ] specs 182/283/328改訂＋処分文書境界更新（AC-7。改訂箇所がspec Scope節と
       Contract notes 2の境界と一致することをdiff review）。
8. [ ] full verification＋screenshot evidence（light/dark、T-05選択表示・frozen表示、
       two-pane実測記録）＋PR（`Refs #368`。Issue受入条件を満たす最終PRのみ
       `Closes #368`を検討。workflow契約に従う。#374への境界通知コメントを同時に行う）。
