# Independent audit: PR #276 durable organizer status projection for re-opened settings (#271)

> Status: accepted
> Audit date: 2026-09-11

- Auditor: 独立監査セッション (ZCode/GLM general-purpose subagent、実装セッションとは別の作業主体。solo保守の独立session規定に基づく。round 1: `34ca558234…`対象、round 2: `dcc18d053e…`対象、round 3: `67f427f4e5…`対象 — いずれも別session)
- PR: https://github.com/nunu1733/NunuLauncher/pull/276
- Head SHA: 9aae65b9cef104dd10d4731e48c3c610e4ff63b3
- Audited code head: 67f427f4e5c02c8c513083cad3917bc38362cdf7 (round 3 の検証対象code head。Head SHA までの間は docs/ 配下の監査記録commitのみ)
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34550217567 (pull_request `ci.yml` 実行、Head SHA 上で成功 — 全 job success、`final-status` green)
- Criteria: specs/271-organizer-durable-status-projection/spec.md — DS-AC-01, DS-AC-02, DS-AC-03, DS-AC-04, DS-AC-05, DS-AC-06, DS-AC-07, DS-AC-08, DS-AC-09, DS-AC-10 (DS-AC-10 は round 3 対象commitで強化)

上記の CI run は merge head `92205bf99eb…` 上の成功実行 (`final-status` green、13 job全て success)。round 2 監査の対象 **code** head は `dcc18d053e45f73f62faf42ebd644f8be18ca38c` であり、監査記録を含む merge head までの delta (`dcc18d053e..92205bf99e`) は `docs/assessment/` 配下 3 file (本記録 + evidence画像2枚) のみの docs-only 変更であるため、byte-identical なコードを検証するものとしてこの実行を証拠とする。round 1 の対象headは `34ca55823423dcd76c5f3d16953bf319e7acf815`、そのhead上のCI実行は actions/runs/34484392559、round 1 時点のcriteriaは DS-AC-01..08 だった (DS-AC-09/10 はround 2対象commitでspecへ追加)。round 1 の記録は以下に保持する。

## Scope

対象diffは PR #276 の `6b6bf8dd9f..34ca558234` (base `main`)。20 files changed, +1482/-7。

production code の変更は次の 7 file に限定される:

- `lawnchair/src/app/lawnchair/organizer/application/public/OrganizerDurableStatus.kt` (新規: 閉じた enum `NEVER_ORGANIZED` / `ORGANIZED_RESTORABLE` / `UNRESOLVED` / `RESTORED_OR_EXPIRED` / `UNAVAILABLE`)
- `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt` (新規: 純粋導出)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` (`RecoveryStorePort.readInspectionSnapshot()` + `InspectionSnapshotRead` 追加)
- `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` (同port実装)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` (`durableOrganizerStatus()`)
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (façade `ManualOrganizationApplication` への読み取り専用 delegate 追加)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (Idle/Cancelled での render mapping)

残りは新規/拡張 test (`OrganizerDurableStatusDeriverTest`、`OrganizerDurableStatusInstrumentationTest`、`ManualOrganizationPreferencesInstrumentationTest`、既存 test の façade 実装追加のみ)、EN/ja strings 3 件ずつ、`CONTEXT.md` / `DESIGN.md`、spec/plan。

本PRは `organizer/application/**` (高リスクpath一覧の `lawnchair/src/app/lawnchair/organizer/application/**` に該当) を変更するため、高リスク独立エビデンス契約の適用対象である。監査では次の観点を対象headのdiffと実装に対して独立に確認した:

1. 読み取り専用性: 新規読み取り経路が SQLite open / 書込み / lifecycle遷移 / tombstone purge / journal発行を行わないこと。
2. #89 fence 境界と run-mutex 直列化の維持。
3. deriver の spec 13 retention 語義と plan の導出rule 1–9 の一致。
4. 診断への新規 field / event 追加の不在。
5. projection 型の identity / payload 非漏えい。
6. UI が `ManualOrganizationApplication` façade 経由の閉じた enum のみを読むこと。

### 監査確認結果

**(1) 読み取り専用性 — PASS。** `RecoveryStore.readInspectionSnapshot()` (`RecoveryStore.kt:447-481`) は `snapshotFence.state()` と `snapshotPublisher.reader().read()` のみを通る。`RecoveryInspectionSnapshotReader` は snapshot file の `FileInputStream` 読み取りのみで、SQLite open / probe / write / purge の code path を持たない (source 確認)。`LayoutApplicationModule.durableOrganizerStatus()` (`LayoutApplicationModule.kt:246-278`) は `readInspectionSnapshot()` と `OrganizerDurableStatusDeriver.derive()` のみを呼び、全 failure 経路を `UNAVAILABLE` へ写像し、書込み・発行を行わない。

**(2) #89 fence 境界と run-mutex — PASS。** `readInspectionSnapshot()` は既存 `readInspectionProjection(pointId)` (`RecoveryStore.kt:432-448`) と同一の fence `VALID` + snapshot generation 一致 + 存在しない/stale snapshot は `Unavailable` の pattern そのものである (fence `INCOMPATIBLE` も `Unavailable` へ fail-closed。`InspectionSnapshotRead` には Incompatible variant がなく、spec の fail-closed 語義に合致)。`durableOrganizerStatus()` は module の同一 run mutex (`ordinaryMutex` = apply/recovery protocol と共有の constructor param `mutex`) を `tryAcquire` で非ブロッキング取得し、`finally` で確実に release する。writer 競合は `UNAVAILABLE` (fail-closed)。`readinessGate.runWhenReady(unavailable = ...)` により startup reconciliation 完了前 (`IDLE`/`RECONCILING`/`FAILED`) も fail-closed。

**(3) deriver mapping — PASS。** `OrganizerDurableStatusDeriver.derive()` は plan の導出rule 1–9 と優先順序どおり: (1) 非final lifecycle `{CREATING, READY, APPLYING, COMMITTED_UNVERIFIED, RESTORING}` → `UNRESOLVED`、(2) `CORRUPT`/`INCOMPATIBLE` row → `UNRESOLVED`、(3) `VERIFIED` + checksum 不正 → `UNRESOLVED`、(4) `VERIFIED` かつ `nowMs < createdAtMs + RetentionPolicy.RETENTION_MILLIS` → `ORGANIZED_RESTORABLE`、(5) lapse 後 `VERIFIED` row → `RESTORED_OR_EXPIRED`、(6) `RESTORED`/`EXPIRED` row → `RESTORED_OR_EXPIRED`、(7) retained tombstone `ALREADY_RESTORED`/`EXPIRED` (`expiresAtMs > nowMs`) → `RESTORED_OR_EXPIRED`、(8) retained `CORRUPT`/`INCOMPATIBLE_VERSION` tombstone → `UNRESOLVED`、(9) それ以外 (`PRUNED_UNUSED`/`QUARANTINED` tombstone を含む) → `NEVER_ORGANIZED`。`RetentionPolicy.RETENTION_MILLIS = 86_400_000` (24h, spec 13) を単一sourceとして参照し、literal を複製しない。境界は `nowMs < createdAt + 24h` の排他比較で、`createdAt + 24h` ちょうどでは restorable にならない (DS-AC-04 の語義どおり)。

**(4) 診断 field / event — PASS。** production diff を grep した結果、`RunEvent` / diagnostics / journal への新規 field・phase・発行は存在しない (該当 hit は spec/plan 文書内の記載と既存 test のみ)。

**(5) projection 型の非漏えい — PASS。** `OrganizerDurableStatus` は field を持たない閉じた enum。module 境界を越えるのは enum 値のみで、`DurableRecord`/`DurableTombstone` (pointId / formatVersion を含む snapshot record) は protocol→deriver の内部入力にとどまり、UI / public seam には現れない。

**(6) UI の読み取り経路 — PASS。** UI は `ManualOrganizationRun.readDurableOrganizerStatus()` → `ManualOrganizationApplication` façade → `ProductionManualOrganizationApplication` → module という既存 seam のみを使い、recovery store に直接触れない。render は Idle/Cancelled の場合のみ (`showDurableStatus` を key にした `LaunchedEffect` で状態遷移のたびに再読み取り) で、`ORGANIZED_RESTORABLE` / `RESTORED_OR_EXPIRED` / `UNRESOLVED` のみ行を render し、`NEVER_ORGANIZED` / `UNAVAILABLE` は何も render しない。`UNRESOLVED` は既存 safe-support 行 (`manual_organization_safe_terminal` + diagnostics entry) を再利用。run 中の状態では durable row を render しない。新規文字列は EN/ja 両方に存在する。

## Criteria check

- **DS-AC-01 (PASS)** — `OrganizerDurableStatusInstrumentationTest.verifiedPointIsRestorableAfterProcessDeath` (close/reopen + module-level restart reconciliation 後に `ORGANIZED_RESTORABLE`)。監査セッションで同一 class を emulator 上に独立再実行し 8/8 green。
- **DS-AC-02 (PASS)** — 同 class の `restoredRowPresentsRestoredOrExpiredAfterProcessDeath` (fresh `RESTORED` row) と `expiredRowsEvictedToTombstonesPresentRestoredOrExpired` (eviction 後の `ALREADY_RESTORED`/`EXPIRED` tombstone)。deriver unit test でも両 tombstone reason を直接検証。
- **DS-AC-03 (PASS)** — 同 class の `corruptRowPresentsUnresolvedAfterProcessDeath` / `unreadableVerifiedRowPresentsUnresolvedAfterProcessDeath`; 非final lifecycle と `INCOMPATIBLE` row は deriver unit test (`nonFinalLifecyclesDeriveUnresolved`, `finalCorruptAndIncompatibleRowsDeriveUnresolved`) で網羅。
- **DS-AC-04 (PASS)** — `OrganizerDurableStatusDeriverTest` 16 test: `verifiedAtExactRetentionBoundaryIsNoLongerRestorable` (ちょうど `createdAt + 24h`)、`expiredTombstoneOutsideRetentionDerivesNeverOrganized`、`prunedAndQuarantinedTombstonesDeriveNeverOrganized`、`corruptAndIncompatibleTombstonesDeriveUnresolved`、優先順序 test (`unresolvedRowOutranksARestorableVerifiedRow` 等) を含む。
- **DS-AC-05 (PASS)** — unit: `removingTheRecordInvalidatesTheRestorableProjection`; instrumentation: `prunedCheckpointPresentsNeverOrganized` (record消失後の再導出で restorable にならない) / `unreadableStoreBeforeReconciliationFailsClosed`; UI: `failClosedUnavailableRendersNoDurableRow`。読み取り経路は (1)(2) の確認どおり書込み・journal発行なし。
- **DS-AC-06 (PASS)** — 型は field-free enum (上記 (5))、新規 `RunEvent`/diagnostics 発行なし (上記 (4))、既存 diagnostics 関連 test は diff で触れられていない (façade 実装追加のみ)。
- **DS-AC-07 (PASS)** — `ManualOrganizationPreferencesInstrumentationTest` 38 test (新規 5: `durableRestorableStatusRendersWhileIdle`、`durableUnresolvedStatusRendersSafeSupportGuidance`、`neverOrganizedRendersNoDurableRow`、`failClosedUnavailableRendersNoDurableRow`、`durableStatusIsHiddenWhileARunIsActive`)。監査セッションで独立再実行し 38/38 green。
- **DS-AC-08 (PASS)** — 同一 PR diff に `CONTEXT.md` (「organizer durable status (永続整理状態)」entry)、`DESIGN.md` §4.2 (module が projection seam を所有する旨の1行追記) を含む。

## Executed test surface

監査セッション内で対象 head `34ca55823423dcd76c5f3d16953bf319e7acf815` 上に独立実行:

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (--rerun で強制再実行) → BUILD SUCCESSFUL (exit 0)。result XML: 89 classes / **981 tests / 0 failures / 0 errors / 0 skipped** (新規 `OrganizerDurableStatusDeriverTest` 16 tests を含む)
- 起動済み emulator (`nunu_qpr2_api36_1` API 36、監査セッションは新規起動せず既存 device を使用) 上に:
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest` → **8 tests / 0 failures / 0 errors** (APK build を含め target head 上で成功)
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest` → **38 tests / 0 failures / 0 errors** (初回試行は test 失敗 0 件のまま BUILD FAILED となった device 側の一過性中断があり、同一 command の再実行で green。test 自体の失敗ではないことを XML で確認)

CI 証拠 (監査実行時点):

- run 34484392559 (CI, `pull_request`, head `34ca558234`): 監査時点で in progress。`check-style` / `organizer-unit-tests` / `build-debug-apk` / `changes` / `validate-repo-contract` は success。instrumentation job 群は pending、`final-status` は未確定。`high-risk-evidence` は本 audit 記録の欠如を理由に fail (本記録の追加がその是正である)。merge operator は同一 commit 上での `CI / final-status` 成功と source job の非skip成功を merge 前に確認すること。

## Findings

- **Blocking: なし。** 監査項目 (1)–(6) と DS-AC-01..08 はすべて対象 head で成立した。
- 参考 (非blocking): spec の frontmatter status は PR head 時点で `accepted` のままである (DS-AC-08 が要求する「status updated in the same PR」の対象を `accepted` への到達と解釈した場合に成立)。workflow の spec status 規則では change を land する PR で `implemented` へ遷移させる先例 (specs/231, specs/89) があるため、merge 時点での `implemented` 遷移を merge operator が確認することが望ましい。
- 参考 (非blocking): deriver の `DurableRecord` は plan の記載より 1 つ多い `updatedAtMs` field を持ち、導出には未使用 (snapshot record からの写像の一貫性のための追加と判断される。境界に影響なし)。
- 未確認範囲: `StrategyPickerInstrumentationTest` / `OrganizerDiagnosticsRouteInstrumentationTest` / `ProductionPublicSeamInstrumentationTest` の instrumentation 再実行は本監査では行っていない (PR 記録の emulator 実行結果と、façade 追加のみの test diff 目視を根拠に扱う)。実機 (物理 device) での process death 再現は未検証 (PR 本文と同様、emulator の close/reopen が restart-equivalent 代理)。

## Overall verdict

**PASS (CI final-status の確定を条件とした承認)** — 対象 head の diff に対し DS-AC-01..08 を独立検証済み。読み取り専用・fail-closed・#89 境界維持・診断不変の主張はいずれも source で成立し、独立再実行 (spotless + 981 unit tests + 46 instrumentation tests) は green。merge gate (`final-status` 成功) と本記録の揃った時点で高リスク独立エビデンス契約を満たす。

## Round 2 (owner review対応の再監査)

> 監査日: 2026-09-10
> 対象head: `dcc18d053e45f73f62faf42ebd644f8be18ca38c` (review-response commit。round 1 記録commit `51f22c128f` の直後)。本記録は merge head `92205bf99eb7aabd44c69b78f159fd3f55685942` — 監査記録 + evidence画像の docs-only commit — にpinし、そのhead上のgreenなCI実行を証拠とする (コードは対象headとbyte-identical)
> 契約: 同一spec の DS-AC-01..DS-AC-10 (DS-AC-09/10 は対象commitでspec/planへ同時追加)
> 監査主体: round 1 とは別の独立監査session (solo保守の独立session規定に基づく)

### 背景 — owner review の指摘 (state=COMMENTED, 2026-09-10)

1. **[P1] re-read race**: `LaunchedEffect(showDurableStatus)` は boolean key のため、startup reconciliation 中の fail-closed read (`UNAVAILABLE`) が同一Settingsセッションで復帰しない。
2. **[P1] cold settings entry**: exported `PreferenceActivity` が fresh process の最初の Activity になる経路で、`layoutApplicationModule` (lateinit, `onPostInit` で初期化) の未初期化読み取りと、reconciliation 起動条件 (`activity is Launcher`) の未成立が懸念された。
3. **[P2] loading distinct**: 読み込み中が「never organized」と視覚的に区別できない。

worker は head `dcc18d053e…` で対応 ([response comment](https://github.com/nunu1733/NunuLauncher/pull/276#issuecomment-5621070468))。spec/plan も同一commitで DS-AC-09/DS-AC-10 (same-surface recovery / cold-process entry / checking row) へ拡張した。

### Delta scope

`git diff 51f22c128f..dcc18d053e`: 14 files changed, +328/-40。production 5 file:

- `lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt` (observable mirror `stateFlow` 追加)
- `lawnchair/src/app/lawnchair/LawnchairApp.kt` (`ensureOrganizerStartupReconciliation(waitForModel)` 抽出、guard をactivity handlerからappへ移動)
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (façade `readinessState` + `ManualOrganizationModule.get` のcold-start安全化)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (effect key へ `readinessState` 追加、checking行)
- `lawnchair/res/{values,values-ja}/strings.xml` (`manual_organization_durable_status_checking` +1 ずつ)

残りは test 7 file (新規test 2件とfakeへの `readinessState` 実装追加のみ) と spec/plan 2 file。読み取りpath本体 (`LayoutApplicationModule.durableOrganizerStatus()`, `RecoveryStore.readInspectionSnapshot()`) はdeltaで不変 (diff 0行) — round 1 の確認結果 (1)(2)(3)(4)(5) は当該headでもbyte-identicalに成立。

### 対応主張のdelta検証 (すべて独立にsource確認)

**(a) ReadinessGate.stateFlow が全遷移を同一lock下でミラー — PASS。** 3箇所のstate変更 (`reconcile` の開始/成功/失敗、`failBeforeReconciliation`) はすべて private `set()` に集約され、`stateRef.set(next)` と `stateMirror.value = next` を write lock 下で同一に行う (`ReadinessGate.kt:70-74`)。`stateRef` はprivateで外部からの直接変更は不可、`runWhenReady` は読み取り専用。StateFlow は conflated のため購読側は常に最新stateを得る。unit test `stateFlowMirrorsEveryGateTransition` が IDLE→RECONCILING→READY の列挙を検証。

**(b) façade `readinessState` とSettings effectのre-key — PASS。** `ManualOrganizationApplication.readinessState: StateFlow<ReadinessGate.State>` → `ProductionManualOrganizationApplication` は `module.readinessGate.stateFlow` を返し、`ManualOrganizationRun.readinessState` がdelegate (`ManualOrganizationRun.kt:79, 116-117, 556-557`)。UI側は `collectAsStateWithLifecycle()` で受けて `LaunchedEffect(showDurableStatus, readinessState)` にre-keyし、gateがterminal stateへ到達すると同一surfaceで `Dispatchers.IO` 再読み取りする (`ManualOrganizationPreferences.kt:106-114`)。navigation不要・timing非依存。

**(c) checking行 — PASS。** Idle/Cancelled branch 内でのみ `if (durableStatus == null) item { ProgressText(manual_organization_durable_status_checking) }` をrenderし (`ManualOrganizationPreferences.kt:207-209`)、active run states (Capturing等) のbranchには存在しないため実行中に決して出ない。読み取りが任意の結果 (UNAVAILABLE / NEVER_ORGANIZED を含む) で確定すれば `durableStatus != null` となりchecking行は消え、fail-closed 2 status は従来どおり無render — loading が「never organized」と混同されず、発明されたstatusも出ない。EN/ja双方に文字列追加。

**(d) `ensureOrganizerStartupReconciliation(waitForModel)` と cold path — PASS。**

- Launcher-resume 経路は `activity is Launcher` で `waitForModel = true` を渡し、挙動は変更前と同一 (guard消費 → model load を最大30s待機 → timeout で `failStartupReconciliation()` = gate FAILED のfail-close → それ以外 `reconcileAtStart()`) (`LawnchairApp.kt:277-282, 134-151`)。
- `waitForModel = false` は `!model.isModelLoaded` のとき `compareAndSet` の**前に** return し、once-guard を未消費のまま残す (`LawnchairApp.kt:130-133`)。よって model 未load の cold settings process で trigger を早期消費して gate を FAILED に毒する経路は存在しない — gate は `IDLE` のまま、`durableOrganizerStatus()` は `runWhenReady` 経由で fail-closed (`UNAVAILABLE`)。その後の Launcher resume が `true` 経路で guard を消費し reconciliation を実行する。
- `ManualOrganizationModule.get` は `app.layoutApplicationModule` を読む前に `LauncherAppState.getInstance(app)` を保証する (`ManualOrganizationRun.kt:134-139`)。`LauncherAppState.INSTANCE` は `MainThreadInitializedObject` で、off-main からは main executor へ submit して待つ (thread-safe)。`onPostInit` → `LawnchairApp.onLauncherAppStateCreated()` → `layoutApplicationModule = LayoutApplicationModule.production(...)` が構築され、値の公開後に hook が走ることを `MainThreadInitializedObject.java:58-70` で確認 (Issue #14 のコメントどおり)。
- model のみ挂在なし確認: `LauncherModel.isModelLoaded()` は `mModelLoaded && mLoaderTask == null && !mModelDestroyed` で、fresh process の settings-only 起動では model は未loadのまま (構築だけではloader開始しない) — `false` 経路が実際にdeferになることを裏付ける。

**(e) 診断・書込み・identity 非漏えい — PASS。** deltaの追加行をgrepした結果、新規 `RunEvent`/journal/diagnostics 発行はなし (`Log.e` は移動した既存timeout経路の1件のみ)。読み取り経路に書込みなし (deltaで読み取りpath本体不変)。新規façade面は field-free の閉じたenum `ReadinessGate.State` (`IDLE/RECONCILING/READY/FAILED`) の `StateFlow` のみで、record payload / revision / digest / item identity を運ばない。

### 冷起動エミュレータエビデンス (DS-AC-10)

workerが取得した cold-process flow の証跡 (emulator `nunu_qpr2_api36_1`) を目視検証し、`docs/assessment/` へ収録した:

![Cold process: exported PreferenceActivity deep linkでorganizer画面へ直接遷移。durable status行は無render (fail-closed)。発明されたstatus行もchecking待機の残留もない](pr-276-coldpath-fail-closed.png)

![同一process内でLauncherをresume (reconciliation完了) 後にorganizerを再表示。「Home layout is organized. Its saved pre-organization backup is restorable.」が表示される](pr-276-coldpath-restorable.png)

- `pr-276-coldpath-fail-closed.png` (12:18): cold process → `am start` でexported PreferenceActivityに `DESTINATION_ROUTE` 直遷移。クラッシュなし、durable status行なし (fail-closed、発明されたstatus行なし)。model未loadのためtriggerはdeferされ、30s後のFAILEDも発生していない (画面に失敗表示がないことと整合)。
- `pr-276-coldpath-restorable.png` (12:19): 同一process内で Launcher resume → reconciliation 完了後の再表示で restorable 行を表示。same-process recovery (DS-AC-09の実機相当面) と一致。
- 監査scopeの明記: 本監査sessionはエミュレータのapp dataを変更しないよう指示されていたため、force-stop → cold-start → resume のシーケンス自体の独立再実行は行っていない。代わりに (i) 2枚の画像の目視検証 (状態・時刻・文言がworkerの説明と一致)、(ii) (d) のwiring source検証、(iii) 同一headでの instrumentation 2件 (`statusRecoversOnTheSameModuleAfterReconciliationCompletes`、`durableStatusRecoversWhenReconciliationCompletesOnTheSameSurface`) により代替検証した。

### 実行した検証 (対象head `dcc18d053e45f73f62faf42ebd644f8be18ca38c` 上、本監査session内)

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun` (強制再実行) → BUILD SUCCESSFUL (exit 0)。result XML: 89 classes / **982 tests / 0 failures / 0 errors / 0 skipped** (round 1 の981に `stateFlowMirrorsEveryGateTransition` 追加で+1。`ReadinessGateTest` 14 tests、`ManualOrganizationRunTest` 37 tests)
- 既存起動中の emulator `nunu_qpr2_api36_1` (emulator-5554) を使用 (新規起動・data変更なし):
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest` → **9 tests / 0 failures / 0 errors** (round 1 の8に same-module recovery test 追加で+1)
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest` → **39 tests / 0 failures / 0 errors** (round 1 の38に same-surface recovery test 追加で+1。flake対象の `changeListTraversalReachesExpandAndReviewActions` を含め全green)

### CI status と merge gate

- run 34495038063 (CI `.github/workflows/ci.yml`, `pull_request`, head `dcc18d053e…`, branch `issue-271-organizer-durable-status-projection`): 初回attemptは `organizer-instrumentation-issue52-tests` のflake — `ManualOrganizationPreferencesInstrumentationTest.changeListTraversalReachesExpandAndReviewActions` が `AssertionError: Failed to assert the following: (Focused = 'true')` — により failure (他の全jobはsuccess)。issue53 も別attemptで同種のflakeに当たった。その後の再attemptはjob再実行と、後続commit pushに伴うconcurrency cancellationで中断され、このrunは最終的に **cancelled** として記録された。issue52/issue53 はいずれも本deltaに起因しない既知の不安定job (下記のflake分析どおり)。
- **green実行 (CI証拠): run 34497804951** (CI, `pull_request`, GitHubによりPR #276に紐付け, head `92205bf99eb…` = merge head): issue52/issue53 の同一head再実行を含め **13 job全て success** — `final-status` および要件上のsource jobs (`organizer-unit-tests`, `check-style`, `build-debug-apk`) を含む。merge head へのdeltaは docs-only (監査記録 + evidence画像) でコードは対象head `dcc18d053e…` と byte-identical なため、この実行が対象コードのmerge-gate証拠として有効である。
- `high-risk-evidence` (high-risk-gate workflow) のこれまでのfailは (a) 監査記録の未追加、(b) 記録がred/cancelled runを参照していたこと、の2点によるものであり、本記録のgreen run参照への更新が (b) の是正である。
- **flake分析 (PR diff に起因しないと判断):** (i) 該当testは本deltaで未変更 (deltaのtest変更は新規2testとfake member追加のみ); (ii) 該当testは Preview 状態のみを走り、checking行は Idle/Cancelled 限定・`readinessState` は既定READYのまま不変のため、deltaが当該testのrender面に到達しない; (iii) 新規testはalphabetical実行順で該当testより後に走り汚染源になり得ない; (iv) 同一headでの同一class独立再実行 39/39 green (本監査) に加え、flake対象2 jobとも同一コード上の再実行 (run 34497804951) で green — 再現性のない一回限りのemulator不安定と確認された; (v) 該当test自体に #209/#209-CI 由来のfocus系CI不安定性への追記修正履歴がある。なお merge ref は main 未変更 (`6b6bf8dd9f`) のため CI は PR head コードそのものを検証している。
- **merge 条件の充足:** `final-status` は merge head `92205bf99eb…` 上で green を確認した (run 34497804951)。高リスク契約の要件 — 検証対象コード上で成功したCI merge gateと本独立監査記録 — は両方とも揃った。

### Findings (round 2)

- **Blocking (code): なし。** owner review P1×2 / P2 への対応主張 (a)–(e) はすべて対象headで成立した。
- **Merge gate:** 解消。flake (issue52/issue53) は同一コードの再実行で green となり、merge head `92205bf99eb…` 上で `final-status` green (run 34497804951) を確認済み — PR diff起因ではない (上記分析)。
- 参考 (非blocking): cold settings入口では、初回readが即時に `UNAVAILABLE` へ確定するため checking行は一瞬のみ表示され、model未loadが続く間は durable status 領域は無renderになる (step1 screenshot のとおり)。これは spec の fail-closed 語義・「no invented status」に一致するが、loading表示は「読み取りが結果を知るまで」の表現である点は、将来のUX調整余地として記録する (DS-AC-09 の受入は満たす)。
- 未確認範囲: (i) cold-pathシーケンス本体の独立再実行 (上記の代替検証で扱う、エミュレータdata保護のため); (ii) `StrategyPickerInstrumentationTest` / `OrganizerDiagnosticsRouteInstrumentationTest` / `ProductionPublicSeamInstrumentationTest` の再実行 (deltaはfaçade実装追加のみをdiff目視で確認、round 1と同じ扱い); (iii) 物理deviceでの process death 再現 (round 1と同様、emulatorがrestart-equivalent代理)。

### Round 2 verdict

**PASS (無条件)** — 対象code head `dcc18d053e45f73f62faf42ebd644f8be18ca38c` のdeltaに対し、owner review の P1×2 / P2 への対応を独立検証し、DS-AC-09/DS-AC-10 を含む全criteriaを確認。独立再実行 (spotless + unit 982 + instrumentation 48) は green。merge head `92205bf99eb7aabd44c69b78f159fd3f55685942` (docs-only delta、コードは対象headとbyte-identical) 上で `final-status` green のCI実行 (run 34497804951) を確認済み — 本記録と同runにより高リスク独立エビデンス契約を満たす。

## Round 3 (re-review対応の再監査)

> 監査日: 2026-09-11
> 対象head: `67f427f4e5c02c8c513083cad3917bc38362cdf7` (re-review-response commit。round 2 記録のdocs更新commit `b94cff997d` の直後)
> 契約: 同一spec の DS-AC-01..DS-AC-10 (DS-AC-10 は対象commitで強化)
> 監査主体: round 1/2 とは別の独立監査session (solo保守の独立session規定に基づく)

### 背景 — re-review の指摘 (Request changes, 2026-09-10)

round 2 の対応でも、cold Settings 経路では reconciliation が実際には開始されていなかった。`ensureOrganizerStartupReconciliation(waitForModel = false)` は model 未loadのまま早期returnするため、settings-only process の gate は `IDLE` に留まる。根本原因は `LauncherModel.startLoader` が callbacks が bind されている場合にしか loader を走らせない点であり、Launcher の居ない process では誰も model load を開始しない。さらに UI は初回 read で `UNAVAILABLE` を得ると checking 行を消すため、durable store に restorable record が存在しても `fresh process → PreferenceActivity → Organizer` に滞在する限り Start だけの status-less な面が無期限に残る — Issue #271 の中心要求 (process death 後に restorable point があるのに re-opened Settings が Idle と区別不能にならないこと) と更新後 DS-AC-10 をまだ満たさない、という指摘であった。

worker は head `67f427f4e5…` で対応 ([response comment](https://github.com/nunu1733/NunuLauncher/pull/276#issuecomment-5627137067))。

### Delta scope

`git diff b94cff997d..67f427f4e5`: 7 files changed, +126/-57。production 4 file:

- `src/com/android/launcher3/LauncherModel.java` (`startLoaderWithoutCallbacks()` bridge 追加)
- `lawnchair/src/app/lawnchair/LawnchairApp.kt` (`ensureOrganizerStartupReconciliation()` を parameterless の単一triggerに統合、guarded no-callback kick 追加)
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (`ManualOrganizationModule.get` のtrigger呼び出し + comment 更新)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (pending gate 下での checking 行持続)

残りは spec/plan 2 file (DS-AC-10 強化) と instrumentation test 1 file (既存 blocked-read test への assertion 追加のみ)。読み取りpath本体 (`LayoutApplicationModule.durableOrganizerStatus()`, `RecoveryStore.readInspectionSnapshot()`)、deriver、`ReadinessGate` はdeltaで不変 (diff 0行) — round 1 の確認結果 (1)–(6)、round 2 の (a)–(e) における読み取り・fail-closed・診断非追加の結論は当該headでもbyte-identicalに成立。

### 対応主張のdelta検証 (すべて独立にsource確認)

**(a) `LauncherModel.startLoaderWithoutCallbacks()` bridge — PASS。**

- **既存pathの再利用のみ。** 新public methodは `startLoader(new Callbacks[0], true)` を呼び、既存private `startLoader(Callbacks[])` は `startLoader(newCallbacks, false)` へのdelegateに置き換わった。loader internals の複製はなく、guardの変更は `if (allowEmptyCallbacks || callbacksList.length > 0)` の1行のみ (`LauncherModel.java:428`)。既存の全呼び出し経路 (`startLoader()`、`addCallbacksAndLoad`、`rebindCallbacks`、`forceReload`、`removeCallbacks`) は `allowEmptyCallbacks=false` で挙動不変。
- **zero-callback bind は no-op。** `newCallbacks.length == 0` なので `bindAllCallbacks = true` となり `callbacksList = getCallbacks()` — settings-only process では空配列。binder はゼロ callback を反復するのみで、`cb::clearPendingBinds` ループもゼロ回。LoaderTask は通常どおり DB を load し `LoaderTransaction.commit()` で `mModelLoaded = true` を commitする。
- **doc comment が AGENTS の upstream bridge 規約に適合。** Issue #271 の rationale、既存 `startLoader()` と同一のloader pathであること、install-queue flag のセマンティクス、UI thread と no-callback 前提の呼び出し条件が箇所に記載されている (`LauncherModel.java:397-407`)。
- **concurrent Launcher bind との相互作用 — 安全。** guard (`!isModelLoaded && !hasCallbacks()`) と bridge 呼び出しは `MAIN_EXECUTOR` (= `LooperExecutor(Looper.getMainLooper())`) 上の単一 runnable 内で完結し、Launcher の bind (`Launcher.onCreate` → `addCallbacksAndLoad`、line 566) も main thread のため serialize される。`hasCallbacks()`/`getCallbacks()` は `mCallbacksList` でsynchronized。bind済みなら kick は skip — Launcher 自身の load/bind を置き換えない。kick が先行した場合: (i) loader 実行中なら Launcher の `addCallbacksAndLoad` → `stopLoader()` が停止し、stopped task は `LoaderTransaction` が `mLoaderTask != task` で `CancellationException` を投げるため `mModelLoaded` を commitせず、実 callbacks で loader がやり直される (`LauncherModel.java:670-679`)。(ii) load 完了済みなら `bindDirectly = mModelLoaded && !mIsLoaderTaskRunning` が true となり既に読み込まれた model が実 Launcher へ synchronous bind される。
- **organizer reload token — 非干渉。** `forceReloadForOrganizer` は `!hasCallbacks()` で即 `cancelled` を走らせるため settings-only process では token は設定されない (`LauncherModel.java:504-507`)。空callbacks LoaderTask は `organizerToken == null` / lease token 0 で走り、`completeOrganizerReload(null, …)` は即 return。誤 completion 経路は存在しない。
- **ModelDelegate — 非干渉。** delegate は constructor で1回生成され (`LauncherModel.java:183`)、LoaderTask から呼ばれる `workspaceLoadComplete` / `modelLoadComplete` / `markActive` は基底実装が no-op で、Lawnchair に override はない (grep で確認)。
- **ItemInstallQueue FLAG_LOADER_RUNNING — 主張どおり。** `pauseModelPush` は bitmask OR (`mInstallQueueDisabledFlags |= flag`) で counter でないため、二重pauseのリークは起きない。resume は `BaseLauncherBinder` の bind 完結時 (:422, :562) に行われ、実 Launcher が bind するまで paused が続く — doc comment の「stays paused until a real Launcher binds」は正確で、既存の "the loader runs the next time launcher starts" セマンティクスと一致 (既存 upstream コードも callbacks-empty の `startLoader()` では guard 前に pause していたため、stay-paused 自体は既存挙動と同型)。
- **stopLoader — 既存動作不変。** `startLoader` 冒頭の `stopLoader()` は実行中の空callbacks task も既存と同一機構で停止できる。

**(b) `ensureOrganizerStartupReconciliation()` 単一trigger — PASS。**

- parameterless に統合され、`organizerReconciliationStarted.compareAndSet` の once guard を Launcher-resume path と settings entry で共有 (idempotent、process-scoped)。guard は model 状態の確認の**前に**消費されるが、kick が guarded になったため早期returnして guard を無駄消費する経路は不要になった。
- Launcher-resume path の挙動は不変: guard 消費 → model load 待機 (30s timeout、`ORGANIZER_MODEL_LOAD_TIMEOUT_MS = 30_000L`) → timeout で `failStartupReconciliation()` (gate FAILED、fail-close) → `reconcileAtStart()`。追加された kick は、resume 時点で Launcher が onCreate 済みで callbacks を bind しているため `hasCallbacks() = true` で skip される。
- settings-only path: reconciliation thread が main executor 上に guarded kick を post → 空callbacks load が開始 → 同 thread の既存待機ループが `isModelLoaded` を検知 → `reconcileAtStart()`。timeout 時は従来どおり fail-close。
- **gate READY の唯一の production 経路。** `reconcileAtStart()` の production 呼び出し元はこの trigger thread のみ (grep で確認、test を除く)。これは後述の冷起動エビデンスの因果chainを支える。

**(c) `ManualOrganizationModule.get` — PASS。** `LauncherAppState.getInstance(app)` を保証した後に trigger を呼ぶ。`layoutApplicationModule` の lateinit 読み取り安全化は round 2 (d) の確認どおり不変。

**(d) UI checking 行 — PASS。** Idle/Cancelled branch 内で `showCheckingRow = durableStatus == null || (durableStatus == UNAVAILABLE && gate が IDLE / RECONCILING)` (`ManualOrganizationPreferences.kt:212-219`):

- pending gate 下の `UNAVAILABLE` read 後も checking 行が持続する (pending gate 下の `UNAVAILABLE` は durable truth ではない)。gate 遷移で `LaunchedEffect(showDurableStatus, readinessState)` が re-read し、terminal 到達時に行は消える。
- terminal (READY/FAILED) では checking 行は消える。`UNAVAILABLE` × terminal は従来どおり無render (fail-closed、no invented status)。
- branch 自体が Idle/Cancelled 限定のため、active run states では決して出ない (round 2 (c) の確認どおり)。
- instrumentation test も拡張: blocked-read test に (i) pending gate 下の `UNAVAILABLE` read 後の checking 行 `assertIsDisplayed`、(ii) terminal 後の checking 行 `assertDoesNotExist` を追加。

**(e) spec/plan の一致 — PASS。** DS-AC-10 は「Launcher を開かず同一 Settings surface に留まるだけで terminal gate に到達し、restorable point があれば restorable status を提示すること」へ強化され、scenario 文 (no-callback loader、Launcher bind 後の no-op、checking 行の持続/消失、fail-close は genuine failure/timeout のみ)、acceptance 表、plan の file 対応表 (`LauncherModel.java` 行の追加) と step 4c、changelog がすべて実装と一致する。

### 冷起動エビデンス (強化版 DS-AC-10 — Launcher 未開放)

worker が取得した cold-process flow の証跡 (emulator `nunu_qpr2_api36_1`) を目視検証し、`docs/assessment/` へ収録した:

![Cold processからexported PreferenceActivityを直遷移し、Launcherを一度も開かずに5秒後。同一organizer surfaceに「Home layout is organized. Its saved pre-organization backup is restorable.」が表示済み](pr-276-coldpath-nolaunch-5s.png)

![同一surfaceで45秒後。restorable行が持続し、checking行や発明されたstatusは表示されない](pr-276-coldpath-nolaunch-restorable.png)

- 両画像 (08:54 取得) とも PreferenceActivity 直遷移の organizer surface に restorable 行が表示され、checking 行は残存していない — 5s時点で既に resolve 済み (worker の予告どおり、emulator の温かい状態では model load が速く完了する)。45s で同一行が持続することも確認。
- **因果検証:** `durableOrganizerStatus()` が `ORGANIZED_RESTORABLE` を返すには `runWhenReady` により gate READY が必要で、その唯一の production 経路は (b) で確認したとおり本 trigger の `reconcileAtStart()` である。settings-only の cold process で resolved 行が同一 surface に表示されている事実は、model load + reconciliation が Launcher なしで in-process 完了したことの帰結として成立する。修正前 (round 2 対象head) の同一経路では status-less のまま滞留したことが re-review の指摘であり、delta が変えたのはまさにこの経路のみである。
- 監査scopeの明記: 本監査sessionは app data 保護のため cold flow 本体 (force-stop → cold-start → 待機) の独立再実行は行っていない (起動済みの既存 emulator を使用、data 変更なし)。代替として (i) 2枚の画像の目視検証、(ii) (a)(b) の wiring source 検証、(iii) instrumentation same-surface recovery tests により検証した。なお画像からは「Launcher を一度も開いた」か否かは直接読み取れないため、手順 (Launcher 未開放) は worker の取得手順への依存として明示的に記録する (上記の因果chainが裏付け)。

### 実行した検証 (対象head `67f427f4e5c02c8c513083cad3917bc38362cdf7` 上、本監査session内)

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun` (強制再実行) → BUILD SUCCESSFUL (exit 0)。result XML: 89 classes / **982 tests / 0 failures / 0 errors / 0 skipped** (round 2 と同数 — 本deltaに新規 unit test はなく、instrumentation test の assertion 追加のみ)
- 既存起動中の emulator `nunu_qpr2_api36_1` (emulator-5554、API 36) を使用 (新規起動・data 変更なし):
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest` → **9 tests / 0 failures / 0 errors** (XML確認)
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest` → **39 tests / 0 failures / 0 errors** (XML確認)
  - 合計 48 — worker の報告 (unit 982 / instrumentation 48 全緑) と一致。

### CI status と merge gate (監査時点、対象head `67f427f4e5…`)

- CI run 34544282436 (`ci.yml`, `pull_request`, head `67f427f4e5…`): 監査時点で in progress。`check-style` / `organizer-unit-tests` / `build-debug-apk` / `validate-repo-contract` / `changes` および organizer instrumentation の api35 / db-migration / issue155 / issue99 / shared-writer は success。`organizer-instrumentation-issue52-tests` は pending、`organizer-instrumentation-issue53-tests` は failure。
- **issue53 failure の分析 (PR diff に起因しないと判断):** 失敗は `OnboardingOrganizationProposalInstrumentationTest.awaitResumedLauncher` の `IllegalStateException: LawnchairLauncher did not reach an attached, laid-out RESUMED state after HOME launch` (1/20 tests) — HOME launch 後の resume 待ち timeout という device 状態依存の不安定で、本deltaが触れていない issue-53 onboarding test class である。delta の test 変更対象は `ManualOrganizationPreferencesInstrumentationTest` (issue52 job) であり無関係。同 class は round 2 の green run (34497804951) でも通過しており、round 2 で同種の emulator 不安定が記録済み (その際は同一コードの再実行で green)。
- `high-risk-evidence` (run 34544282422) の fail は "Evaluate high-risk evidence gate" — 本 round-3 監査記録が対象head上にまだ存在しないためである。本記録を含む docs commit の push がその是正である。
- **merge 条件:** 高リスク独立エビデンス契約により、merge 前に (1) 検証対象commit (監査記録 + evidence を含む最終head) 上で CI の merge gate (`final-status`) が実際に成功していること、(2) `high-risk-evidence` が本記録を参照して green になること、の両方が要求される。監査時点では issue52 の完了と、issue53 flake の同一head再実行 (round 2 と同様、既知不安定 job の再試行) が必要であった。flake でない失敗が最終headで生じた場合は本記録の前提を再確認すること。
- **監査後の解決 (2026-09-11):** 本記録を含むhead `7804d634a2…` (= Head SHA) 上で、run 34550217567 (pull_request, `ci.yml`) が完了 — 全 job success、`final-status` green。issue52 の focus-assertion flake は既知の既存不安定として同一headの再実行で解消され、issue53 も同様に解消された。上記 merge 条件 (1) はこのheadで満たされている。なお round 2 までの証拠run (34497804951) は merge head `92205bf99e…` 上の実行で本roundの対象commit上の実行ではないため、ヘッダの Head SHA / CI run 行はこの緑runに統一した (監査時点の経緯は本節に履歴として保持)。

### Findings (round 3)

- **Blocking (code): なし。** re-review P1 への対応主張 (a)–(e) はすべて対象headで成立した。
- 参考 (非blocking): 5s evidence で既に resolved になっているため、checking 行の観察窓は冷起動直後の一瞬しかない。ただし checking 行の持続・消失は instrumentation test で直接検証されており、spec (DS-AC-09/10) の受入は満たす。
- 参考 (非blocking): settings-only process では FLAG_LOADER_RUNNING が実 Launcher の bind まで pause 継続し、install session の model push が保留される。既存セマンティクスの範囲内で doc comment にも記載済みだが、settings から長時間離脱しない利用者で install shortcut の反映が Launcher 初回起動まで遅延し得る点は将来の観察事項として記録する。
- 未確認範囲: (i) cold-path シーケンス本体の独立再実行 (app data 保護 — 上記の代替検証で扱う。手順の Launcher 未開放は worker の取得手順に依存); (ii) `StrategyPickerInstrumentationTest` / `OrganizerDiagnosticsRouteInstrumentationTest` / `ProductionPublicSeamInstrumentationTest` の再実行 (delta は対象classに触れず、round 1/2 と同じ扱い); (iii) 物理deviceでの process death 再現 (round 1/2 と同様、emulator が restart-equivalent 代理); (iv) issue52 job の完了 (監査時点 pending)。

### Round 3 verdict

**PASS (承認 — 対象Head SHA上で `final-status` green を確認済み)** — re-review P1 (cold settings entry が reconciliation を開始しない) への対応を独立検証し、強化版 DS-AC-10 を含む全criteriaを対象head `67f427f4e5…` で確認。upstream bridge (`startLoaderWithoutCallbacks`) は既存pathへの1行 guard 追加のみで、concurrent Launcher bind・organizer reload token・ModelDelegate・install-queue flag のいずれとの相互作用も安全であることを source で確認した。独立再実行 (spotless + unit 982 + instrumentation 48) は green。`final-status` は本記録を含む Head SHA `7804d634a2…` 上で green を確認済み (run 34550217567、上記「監査後の解決」)。merge operator は `high-risk-evidence` が本記録を参照して green になることを確認してから merge すること。
