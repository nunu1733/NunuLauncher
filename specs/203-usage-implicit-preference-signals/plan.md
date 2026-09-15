# Implementation Plan: versioned local usage / implicit-preference signal snapshot

> Issue: #203
> Spec: [spec.md](./spec.md)
> Status: **accepted** (2026-09-15T04:15Z, owner acceptance: [Review result: Approve](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674638975) — 対象 head `7a08f9c86a1965e922378c66a4c6e3c74f7df815`)。実装着手は許可された。ただし U-5 に依存する実装 PR は merge 前に probe 完了条件 (a)〜(d) を記録する (spec U-5)。
> 実装計画は本planの child issue 分割 (1)〜(6) に従い、単一実装 branch 上で段階的に適用する (solo 保守のため1 PR に集約し、各段階の検証を PR 本文に記録する)。
> 本plan は初回作成時 (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) の調査を基に、2026-09-12 の re-entry check で `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4` までの差分を再検証・追記したものである。
> 2026-09-13 re-entry: `origin/main` = `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` までの差分 (#283/#287/#300/#304 関連) を確認 — 本planが依存する composer / provenance / planning / requirements / ADR に変更なし。同日の owner review (Changes requested) を受け、`generation` 削除 (content-addressed identity)、mandatory dynamic cut 外の single-read + typed downgrade 契約、canonicalization 拡張 (access state / profile availability / 三値 state)、launcher-origin 観測単位の確定、install-age の事実修正・defer を反映した。
> 2026-09-14 re-entry: `origin/main` = `397d3fd957` (merge commit `66d8572e` で本branchへ取り込み) までの差分 (#298/#299/#315 関連) を確認 — composer への変更は `CaptureFailureObserver.onCaptureFailure` への `invariant: CaptureInvariantCategory?` 追加 (diagnostics、#299) のみで、stable cut / `dynamicCutIdentity` / provenance / `OrganizationInput` の形状は不変 (`dynamicCutIdentity` の定義位置は 604-616 行から 629 行へ移動、`MAX_DYNAMIC_ATTEMPTS = 2` は不変)。`docs/engineering/organizer-diagnostics.md` の変更は #299 の `invariant=` field 追加のみで、package / raw usage の Never 境界は不変。同日の owner re-review (Changes requested) を受け、(1) snapshot 実体を `OrganizationInput` の non-null field で downstream へ渡す (Blocking 1)、(2) `usageAccess` を system usage source 専用 state とし launcher-origin entry を `NOT_GRANTED` 下でも保持 (Blocking 2)、(3) app pair を first delivery 対象から除外 (Blocking 3 — `QuickstepLauncher.launchAppPair` は `AppPairsController.launchAppPair` (同 file 240行) → `findLastActiveTasksAndRunCallback` (248行) の非同期 callback 内 `launchSplitTasks` (279行) であり、override 戻り時点では実 dispatch が起きていないことを実読みで確認)、(4) launcher-origin counter は絶対 day anchor を永続化し recency bucket を read 時に投影 (Required)、(5) snapshot 非永続化の明記 (Required)、(6) fallback 表現の修正 (Minor) を反映した。
> 2026-09-15 re-entry: `origin/main` = `f12d67bcb6` までの差分 (#298 実装 PR #319/#320: LoaderTask / test / CI / docs) を確認 — 本planが依存する composer / provenance / planning / requirements / ADR に変更なし。同日の owner re-review (Changes requested) を受け、(1) snapshot field を non-null な closed 三値型に統一 (Blocking 1 — nullable 表記の排除)、(2) taskbar を launcher-origin の first delivery 対象に **含める** と決定し、その根拠となる合流経路 (`LauncherTaskbarUIController.onTaskbarIconLaunched` → `Launcher.logAppLaunch`) と判別手段の不在を Current evidence として実読みで記録 (Blocking 2 — 対象外とすると合流する起動を除外する追加 fork 機構が必要になり、契約と実装が一致しない)、(3) `QuickstepLauncher.logAppLaunch` の既存 side effect (All Apps session InstanceId 補正 / prediction rank / `LAUNCHER_APP_LAUNCH_TAP` / hotseat prediction ranking info) を実読みで確認し、override の `super` 呼び出し exactly once・side effect 不変・counter 書き込み失敗の非伝播を契約に追加 (Required)、(4) system usage と launcher-origin の合成 owner を `AndroidPersonalizationSignalSnapshotSource` (aggregator) として明示 (Required)、(5) U-4 を `InputProvenance` への non-null 8番目 field 追加として確定、(6) decision 解消用 probe / instrumentation を実装開始禁止の対象外と明記、(7) U-2 / U-3 / U-5 に具体的 draft 値を反映した。
> 2026-09-15 (2nd) re-entry: snapshot `e55051cdaa` への review (Changes requested、[Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674398728)) を受け、(1) **Blocking 1** — recency bucket 境界を system usage / launcher-origin 共通の半開区間 `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)` で統一 (launcher-origin は同境界の暦日量子化)、「8–30d / ≥30d」の重複表記を除去、(2) **Blocking 2** — snapshot 型から `capturedAtClass` を除去し **sparse object 契約** (unavailable section の package-level entry は object 側にも構築しない) を導入。canonical rows と object 形状が一致し、contentDigest が consumer-observable projection を完全に identify する。entry field 型は `SignalField<T> = Value / Absent` (closed 二値) となり `Unavailable` は section-level state 専用、(3) **Required** — quantile algorithm を nearest-rank の数式 (`B_k = x_⌈kN/5⌉`、`bucket(v) = |{k : B_k ≤ v}|`) で確定し N=5/6/7/11 の期待値を test に記載、probe を acceptance 前提から **実装ゲート** (完了条件 (a)〜(d) を明記) へ再位置づけ、を反映した。
> 2026-09-15 (3rd) re-entry: snapshot `7cfeef92bb` への review (Changes requested、[Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674510548)) を受け、(1) **Blocking 1** — active な本文に残る旧 `Unavailable` semantics (Migration and recovery の revoke 記述等) を sparse object 契約へ統一し、snapshot 型を section 分離の sealed 型 (`SystemUsageSection = Available(entries) / Unavailable`、`LauncherOriginSection = Available(entries) / Unavailable`) として実装型を一意化 (部分 availability を nullable 無しで表現)、(2) **Blocking 2** — nearest-rank の bucket 対応を `bucket(v) = |{k : B_k < v}|` (境界と同値は下位側) へ修正し、N=5 相異なる値で `x_1→0 … x_5→4` を明示、(3) **Required** — Testing strategy の probe 完了条件に (d) (day anchor の DST / timezone-change 挙動) を追加し (a)〜(d) を揃えた。
> 2026-09-15 (probe amendment): U-5 probe (API 36 emulator、[evidence](../../docs/assessment/pr-321-u5-probe-evidence.md)) により、`queryUsageStats(INTERVAL_DAILY)` の interval は local calendar day に整列しない ~24h rolling bucket であり、暦日 keyed full-containment は過半の interval を喪失する逸脱を検出。acceptance 契約に従い merge 前に spec U-5 を修正 (rolling 24h 窓 + 返却 interval をそのまま集計、active-days は foreground>0 interval 数の近似、7d は独立 query)、実装 (`AndroidSystemUsageSignalReader`) を整合させた。(d) の day anchor は 3 zone 実測で契約どおりであることを確認。
> 2026-09-15 (4th) re-entry: snapshot `46598d47ae` への review (Changes requested、[Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674595500)) を受け、Identity / determinism 節に残存していた旧 bucket 式 (`≤`) を spec U-5 / Testing strategy と同一の `bucket(v) = |{k : B_k < v}|` へ統一した (前回指摘 Blocking 2 の残存箇所。他の指摘はすべて解消済みと確認された)。

## Current evidence

`origin/main` で確認した事実 (推測と分離)。初回確認 2026-09-12 (main = `f9afd8bfde`)、再確認 2026-09-13 (main = `c5274b5d0d`)、再確認 2026-09-14 (main = `397d3fd957`)、再確認 2026-09-15 (main = `f12d67bcb6`)。

- Planner 入力: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`
  - `OrganizationInput(snapshot, rules, taxonomy, signals, targets, runMode)` (2026-09-14 再確認)。`ClassificationSignals` は分類専用 (`SignalSource` S1〜S6、`CategoryId` 候補のみ)。usage/frequency/recency を運ぶ欄は存在せず、**`OrganizationInputComposition.Ready(input, provenance)` も `input + provenance` の2 field のみである** (`CompositionModels.kt`)。したがって snapshot の実体を planner (`OrganizationPlanner.plan(input)`) へ届けるには `OrganizationInput` への field 追加が必須である (2026-09-14 review Blocking 1 — identity を provenance に載せるだけでは実体が届かない)。本planはこの field 追加を #203 の変更範囲に含める。
  - `RunMode` は #228 の実装により `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` の3値。personalization snapshot は新規 run mode を追加せず既存 modes に optional input として参加する。
- Composition seam: `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  - `OrganizationInputComposer` は #228 実装後2 entry point (`composeFullOrganization()` / `composeScopeComposedOrganization(selection)`) を持ち、両方とも `composeInternal(selection)` に委譲する。personalization snapshot の読み取りは `composeInternal` の流れの中への optional 追加になる (mandatory stable cut の **外側** — 後述の Seams 参照)。
  - `DefaultOrganizationInputComposer` は canonical capture → bundle → (selection / overrides / platform evidence を前後2回読む) stable cut → signals/targets materialize → `OrganizationInputComposition.Ready(input, InputProvenance)`。#299/#315 以降に `CaptureFailureObserver.onCaptureFailure` が `(exceptionClass, invariant: CaptureInvariantCategory?)` へ拡張されたが (diagnostics のみ)、cut / readiness / provenance の semantics は不変 (2026-09-14 再確認)。
  - `InputProvenance` (`CompositionModels.kt`) は `revision + rules + taxonomy + signals + targets + policyBundle + layoutStrategySelection` (7 field、2026-09-14 再確認済み)。`dynamicCutIdentity(bundle, overrides, evidence, selection)` (OrganizationInputComposer.kt:629、#299 由来の移動後) は bundle sha + overrides の versionOrGeneration+sha + evidence の **sha のみ** (generation 不参加) + selection の versionOrGeneration+sha を束ね、不一致時 `MAX_DYNAMIC_ATTEMPTS(2)` retry → `NotReady(InconsistentPolicyRead)`。**evidence の digest-only 参加は、content-addressed な input が generation なしで cut に参加できる既存の先例である。**
  - #228 実装で追加された `InputReadinessReason.StaleCandidateSelection` / `InputCompositionCode.CANDIDATE_SELECTION_STALE` は closed set への追加が許容されることを示す先例。`CompositionDiagnostic` は opaque (package 等を含まない) のままである。
  - `scopeComposedTargetsIdentity`: 選択 additions の canonical content で target identity の digest を決定論的に拡張する (#228、ADR-0007 §targets に追記済み)。personalization snapshot の digest 参加も同型で実装できることを示す直接の先例。
- Production wiring: `integration/ProductionOrganizationInputComposer.kt` が `DefaultOrganizationInputComposer` への注入点 (`AndroidClassificationSignalSnapshotSource` 等)。personalization source の注入もここに1行追加する形になる。
- Platform adapter の慣行: `AndroidClassificationSignalSnapshotSource` (`integration/AndroidClassificationSignalSnapshotSource.kt`)
  - Android dependency は integration 側のみ。per-profile 読みは `LauncherApps` + `UserCache` serial bind、失敗は fail-closed (`Unreadable`)。identity は `PolicyInputIdentity(PLATFORM_CLASSIFICATION_EVIDENCE, "platform-evidence-v1", sha256Canonical(rows))`。
  - この型が personalization snapshot source の直接のtemplateになる。#228 追加分の新先例: `integration/AndroidCandidatePorts.kt` の `userForProfile` (canonical serial → `UserHandle` 解決、非解決は null で caller が failure semantics を決める)、`integration/MissingAppCandidateSource.kt` の pure core + typed `DetectionUnavailableReason` 分離。
- Policy identity: `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt`
  - `PolicySourceKind` は現状6値 (`ORGANIZER_POLICY_BUNDLE`, `CATEGORY_OVERRIDE_SNAPSHOT`, `LAYOUT_STRATEGY_SELECTION`, `PLATFORM_CLASSIFICATION_EVIDENCE`, `MATERIALIZED_CLASSIFICATION_SIGNALS`, `MATERIALIZED_FULL_TARGET_SET`) — baseline から変更なし (再確認済み)。`PolicyInputIdentity(source, versionOrGeneration, sha256)`。
  - `POLICY_BUNDLE_VERSION` は #235 実装により `organization-policy-v2.6`。本Issueはこの version を変更しない。
- Usage permission 前例: `lawnchair/src/app/lawnchair/ui/preferences/components/SuggestionsPreference.kt` が `android.Manifest.permission.PACKAGE_USAGE_STATS` を `checkCallingOrSelfPermission` で確認済み (app-op ベース。runtime permission ではない)。organizer 配下に usage 系 code は現状ない。
- Launcher launch path (2026-09-13 に `ItemClickHandler.java` / `views/ActivityContext.java` / `Launcher.java` / `QuickstepLauncher.java` / `AppPairsController.java` を実読みして確認、2026-09-14 に再確認、2026-09-15 に taskbar 関連を追加実読み):
  - `ActivityContext.startActivitySafely` (`views/ActivityContext.java:406`) は platform dispatch (`startShortcut` / `startActivity` / `startMainActivity`) の **try 内の成功時にのみ** 既存の `logAppLaunch(statsLogManager, item, instanceId)` (同 file :465、`QuickstepLauncher` が override) を呼び、`NullPointerException` / `ActivityNotFoundException` / `SecurityException` を catch した (同期失敗の) 場合は呼ばない。「platform への launch request が正常 dispatch された」ことの既定義 observation point が存在する。
  - `ItemClickHandler` の `FLAG_START_FOR_RESULT` 付き shortcut path も `launcher.logAppLaunch(...)` を直接呼ぶ (同じ seam に合流)。
  - **app pair (launcher activity 経由) は first delivery の観測対象から除外する** (2026-09-14 review Blocking 3)。`ItemClickHandler.onClickAppPairIcon` → `QuickstepLauncher.launchAppPair` (:1340、`AppPairsController.launchAppPair` への委譲のみ) → `AppPairsController.launchAppPair` (:240) は `findLastActiveTasksAndRunCallback` (:248) を登録するだけで、実 platform dispatch (`launchSplitTasks`) は **非同期 callback 内** (:279) で行われる。override 戻り時点での観測は「dispatch 成功」contract より早くなるため、呼び出し点に観測を置く実装は spec 違反になる。実 dispatch point への seam 追加は Launcher3 bridge を伴うため first delivery では行わない。
  - **taskbar は `Launcher.logAppLaunch` に合流する (2026-09-15 実読み — 2026-09-15 review Blocking 2 の scope 決定根拠)**: `LauncherTaskbarUIController.onTaskbarIconLaunched` (`quickstep/src/com/android/launcher3/taskbar/LauncherTaskbarUIController.java:336-341`) は `mLauncher.logAppLaunch(...)` を呼ぶ。呼び出し元は `TaskbarActivityContext` の click handler 内 3箇所 (`:1122` taskbar 上 app pair、`:1182` taskbar 上 workspace item / deep shortcut / promise icon、`:1195` taskbar all-apps `AppInfo`) である。**seam 上に taskbar 由来の判別手段は存在しない**: `getStatsLogManager()` は `StatsLogManager.newInstance` の都度生成 (`views/ActivityContext.java:248`) であり instance 比較で判別できず、taskbar item は hotseat 等と container 値を共有し、taskbar 固有の marker (`CONTAINER_TASKBAR` 等) はコード内に存在しない。taskbar overview mode では `launchFromTaskbar` → `launchFromOverviewTaskbar` が `findLastActiveTasksAndRunCallback` の非同期 callback 内で実 dispatch を行うため `onTaskbarIconLaunched` の時点 (呼び出し直後に同期呼ばれる) は dispatch 発火 **前** であり、in-app mode の同期 dispatch 失敗 (`startItemInfoActivity` 内 catch) も観測後に発生して補正されない。したがって taskbar 由来の観測時点は「起動要求発火」である (spec の surface 別 contract)。taskbar を対象外にするには合流する起動を除外する追加の fork 機構 (判別の導入) が必要になり、first delivery の最小変更と矛盾するため **含める** を採用した。`LauncherTaskbarUIController.java` / `TaskbarActivityContext.java` への fork 側 commit は 0 件 (baseline `505dbc40` 〜 origin/main)。
  - **`QuickstepLauncher.logAppLaunch` は単なる event log ではない (2026-09-15 実読み — 2026-09-15 review Required)**: (a) All Apps session の InstanceId 補正 (`mAllAppsSessionLogId`)、(b) `mAllAppsPredictions` からの prediction rank 付与 (`withRank`)、(c) `LAUNCHER_APP_LAUNCH_TAP` logging、(d) `mHotseatPredictionController.logLaunchedAppRankingInfo(info, instanceId)` を行う。`LawnchairLauncher` での override はこれらの既存挙動を保存しなければならない (Seams 参照)。
  - `LawnchairLauncher : QuickstepLauncher` (`lawnchair/src/app/lawnchair/LawnchairLauncher.kt:99`) が fork 所有の launcher subclass であり、`logAppLaunch` の override 観測点として Launcher3 file を直接編集せずに済む候補になる (現時点で `logAppLaunch` / `launchAppPair` の override は未実装、2026-09-15 再確認)。
  - `ItemClickHandler.java` 自体への fork 側 commit は 0 件のまま (2026-09-13 再確認、`c5274b5d..origin/main` の diff にも含まれず)。
- 既存 spec 整合: spec 83 は usage signal を明示的に non-goal としており、本plan は composition seam を拡張する新規 input の追加として位置づける。spec 182 は bundle identity と dynamic input identity の分割 (selection snapshot 慣行) を確立済み (status: implemented)。spec 228 も implemented であり、usage なしで成立することを実装で実証済み。
- 依存 Issue: #204 (exchange contract。draft snapshot は branch `issue-204-spec-plan` のみで未accept。`usageSignals` を optional とし詳細を本Issueに委ねている)、#205/#206 (draft snapshot のみ、未accept)。#182/#228 は implemented。

## Design

### Modules and interfaces

新規 module `lawnchair/src/app/lawnchair/organizer/personalization/`。DESIGN.md §9 の target source layout 図は `planning/`〜`ui/` の7 package を列挙するが、「package数をこの図に合わせること自体を目的にしない。interfaceを深く保ち、変更のlocalityが高まる分割だけを採用する」を明記しており、usage signal 専用の純粋domain + adapter 群を1 package にまとめる分割はこれに適合する。ただし新規 top-level organizer package の追加は DESIGN.md に見える構造決定であるため、実装PRで §9 の図と DESIGN.md module row を同時更新すること (docs update は spec 受入後の実装PRが担当)。代替として snapshot 型を `planning/` に置く案もあるが、分類用 `ClassificationSignals` と個人化 signal の混在を避けるため専用 package を推奨する。

```kotlin
// domain (pure, Android-free)
interface PersonalizationSignalSnapshotSource {
    /** total: 投げない。permission state・query/profile 失敗はすべて typed snapshot で表現する */
    fun read(request: UsageSignalRequest): PersonalizationSignalSnapshot
}
// section の構造的不存在は sealed 型で表現する (null を使わない — 2026-09-15 (3rd) review
// Blocking 1。部分 availability を一意に表現できる):
sealed interface SystemUsageSection {
    data class Available(val entries: Map<ProfileKey, Map<PackageName, SystemUsageEntry>>) : SystemUsageSection
    data object Unavailable : SystemUsageSection
}
sealed interface LauncherOriginSection {
    data class Available(val entries: Map<ProfileKey, Map<PackageName, LauncherOriginEntry>>) : LauncherOriginSection
    data object Unavailable : LauncherOriginSection
}
data class PersonalizationSignalSnapshot(
    val schemaVersion: String,               // "personalization-signals-v1"
    val contentDigest: String,               // canonical rows への sha256
    val usageAccess: UsageAccessState,       // GRANTED / NOT_GRANTED / UNAVAILABLE
    val launcherOriginAvailability: LauncherOriginAvailability,
    val profileAvailability: Map<ProfileKey, SystemUsageProfileAvailability>,
    val systemUsage: SystemUsageSection,     // sparse: 利用不能時は Unavailable (entries 無し)
    val launcherOrigin: LauncherOriginSection,
)
data class SystemUsageEntry(
    val foreground30dBucket: SignalField<Bucket>,        // Value / Absent (closed 二値)
    val foreground7dBucket: SignalField<Bucket>,
    val recencyBucket: SignalField<RecencyClass>,
    val activeDaysBucket: SignalField<ActiveDaysClass>,
)
data class LauncherOriginEntry(
    val countClass: SignalField<CountClass>,
    val recencyClass: SignalField<RecencyClass>,
)
// NOT_GRANTED / UNAVAILABLE / per-profile 失敗は「当該 section を availability state で表現し、
// 当該 section の package-level entry を構築しない snapshot」
// として Ready に相当する形で返す。personalization 由来の失敗で呼び出し側を失敗させない
// (spec: optional source 契約 / review Blocking 2)。

// integration (Android) — system usage と launcher-origin の合成 owner はこの aggregator
// 単一クラスである (2026-09-15 review Required: 合成責任の明示)
class AndroidPersonalizationSignalSnapshotSource(
    private val systemUsage: AndroidSystemUsageSignalReader,
    private val launcherOrigin: LauncherOriginSignalReader,
) : PersonalizationSignalSnapshotSource {
    // 両 reader の結果を merge → bucket 計算 (domain の pure 関数) → canonical rows 化 →
    // contentDigest → snapshot 組み立て。per-source availability (usageAccess /
    // launcherOriginAvailability / profileAvailability) の判定もここで行い、
    // 片方の source が失敗しても相手の section は生かす
}

// integration (Android) — aggregator に注入される単一責務の reader 2つ
class AndroidSystemUsageSignalReader(
    appContext: Context,
    /* UsageStatsManager / AppOpsManager / LauncherApps access。usage access state 判定と
       per-profile query を担当。per-profile binding は AndroidClassificationSignalSnapshotSource
       と同じ UserCache serial 慣行 */
)
class LauncherOriginSignalReader(
    private val counterStore: LauncherOriginLaunchCounterStore,
    /* counter store の読み出しと、読んだ最小 state から domain の pure 投影関数
       (day anchor → recency class) を呼ぶ役目 */
)
class LauncherOriginLaunchCounterStore(
    /* app-private 永続化。永続するのは count 最小 state + 絶対 day anchor (epoch-day 相当) のみ。
       相対 recency class は永続せず、snapshot 構築時に anchor から pure 投影する
       (2026-09-14 review Required) */
)
```

- **`OrganizationInput` の拡張 (2026-09-14 review Blocking 1)**: `OrganizationInput` に non-null `personalization: PersonalizationSignalSnapshot` field を追加する (7番目の field)。composer は各 attempt で構築した snapshot を `OrganizationInput` に載せ、`Ready(input, provenance)` 経由で planner seam へ渡す。`OrganizationInputComposition.Ready` 自体は変更しない。`NOT_GRANTED` / `UNAVAILABLE` も valid snapshot であるため nullable にしない。既存 constructor caller (composer と composer/planner の tests) の更新は機械的であり、planner 側の既存 strategy は新 field を読まないため挙動は不変。
- usage-based strategy 自体の実装は本Issueの対象外 (#182 child 形式の別Issue)。本Issueの縦切りは「snapshot 取得 + `OrganizationInput` への搭載 + composition/provenance への optional 参加」までである (identity だけが provenance に残り実体が downstream に届かない初版 draft は review Blocking 1 により却下)。
- usage access state の読み取り (`AppOpsManager` / `PACKAGE_USAGE_STATS` check) は integration 側に隠す。domain は `UsageAccessState` のみ。

### Seams

- **読み取り seam**: `PersonalizationSignalSnapshotSource` (production: `AndroidPersonalizationSignalSnapshotSource`、test: in-memory fake)。呼び出し側は composer。planner/AI adapter は直接呼ばない。`read()` は total (throw しない) — platform 失敗は section-level availability state (当該 section の entries を構築しない) へ変換する (sparse object 契約)。composer 側も防御として try で囲み、想定外の例外があっても personalization の該当 section を利用不能化して `Ready` を返す (二重化。バグ隠蔽にならないよう diagnostic に typed code を出す)。system usage と launcher-origin の合成は aggregator (`AndroidPersonalizationSignalSnapshotSource`) が単一責務で担い、2つの reader (`AndroidSystemUsageSignalReader` / `LauncherOriginSignalReader`) を注入される (2026-09-15 review Required — 合成 owner の明示)。per-section availability の判定は aggregator が行い、片方の失敗で相手の section を落とさない。
- **composer 接続**: `DefaultOrganizationInputComposer.composeInternal` への optional source 追加 (`ProductionOrganizationInputComposer` が wiring 点)。読んだ snapshot は構築する `OrganizationInput` の `personalization` field へそのまま載せる (2026-09-14 review Blocking 1)。`dynamicCutIdentity` は **変更しない** — personalization は mandatory cut の外側で composition attempt ごとに 1回だけ読み、source 失敗・churn は personalization の section-level availability (当該 section の entries を構築しない) としてのみ現れる。`NotReady` / `InconsistentPolicyRead` / `MAX_DYNAMIC_ATTEMPTS` は mandatory source 専用のまま (spec: review Blocking 2)。snapshot の identity は `InputProvenance` の **non-null 8番目 field** `personalization: PolicyInputIdentity` として参加する (U-4 確定 — 2026-09-15 review recommendation 採用。constructor caller の機械的更新を Change set に含める)。#228 由来の `composeScopeComposedOrganization` path でも同じ optional source が走ってよい (additions と personalization は独立)。
- **launcher-origin hook**: spec の観測単位 contract に従い、既定義 observation point `ActivityContext.logAppLaunch` を `LawnchairLauncher` で override して `LauncherOriginLaunchCounterStore` へ接続する (Launcher3 file への直接編集を回避)。**taskbar 由来の合流は除外しない** — `LauncherTaskbarUIController.onTaskbarIconLaunched` が同一 seam に合流することは upstream の既存挙動であり、seam 上に判別手段が存在しないため、taskbar icon tap は spec の対象に含める (2026-09-15 review Blocking 2。実証は Current evidence 参照)。**item 型フィルタ**: override は app pair (`AppPairInfo`) と promise icon / market 導線 (promise 属性) を明示的に除外する (spec 対象外。launcher activity 経由の app pair はそもそも hook に到達しないため、フィルタが効くのは taskbar 経由の合流である)。**既存挙動の保存 (2026-09-15 review Required)**: override は **すべての path で `super.logAppLaunch(...)` を exactly once 呼ぶ** — 現行 `QuickstepLauncher.logAppLaunch` は All Apps session の InstanceId 補正、prediction rank 付与、`LAUNCHER_APP_LAUNCH_TAP` logging、`mHotseatPredictionController.logLaunchedAppRankingInfo` を行う単純でない処理であり、これらを変更しない。counter 書き込みは super 呼び出し後の best-effort 非同期処理とし、想定外の例外を含むあらゆる失敗を override 内で吸収する (launch flow / logging への伝播を禁止)。**app pair (launcher activity 経由) 用の hook は作らない** — `launchAppPair` override の戻り時点では実 dispatch (`launchSplitTasks`) が起きておらず、そこで観測すると contract 違反の過早観測になる (2026-09-14 review Blocking 3)。taskbar 経由の app pair は item 型フィルタで落とす。

### Data flow

1. (background) launcher 経由の起動観測 (`LawnchairLauncher.logAppLaunch` override 発火 — launcher activity surface は dispatch 成功時点、taskbar は起動要求発火時点。app pair / promise icon は item 型フィルタで除外、taskbar recents は seam に合流しない) → counter store 更新 (count 最小 state + 絶対 day anchor)。書き込みは best-effort 非同期で、あらゆる失敗を吸収し launch flow / logging に影響させない。
2. manual run 開始 → composer は mandatory 入力の stable cut とは独立に、`PersonalizationSignalSnapshotSource.read(request)` を **1回** 呼ぶ。aggregator が `AndroidSystemUsageSignalReader` / `LauncherOriginSignalReader` の両結果を合成する。
   - `GRANTED`: 単一の window anchor (read 開始時に1回だけ読む clock) を基準に `UsageStatsManager` 日次集計から 30d/7d foreground bucket・recency・active-days bucket を合成し canonical rows 化 → contentDigest。rank universe は request 集合から独立した決定論的 universe (spec U-5 確定値: window 内に usage record を持つ launchable app)。
   - `NOT_GRANTED` / `UNAVAILABLE` / per-profile 失敗: **system usage section を availability state で表現し、当該 section の package-level entry を object / canonical rows ともに構築しない** (sparse object 契約 — 2026-09-15 (2nd) review Blocking 2)。header / per-profile 行の state が意味を運ぶ。**launcher-origin section は `usageAccess` の state にかかわらず構築される** (2026-09-14 review Blocking 2)。
3. snapshot を `OrganizationInput.personalization` へ載せ、snapshot identity を `InputProvenance` の non-null 8番目 field として運び、既存 mandatory cut が安定していれば `Ready(input, provenance)`。personalization は `Ready` / `NotReady` の分岐に参加しない。
4. planner / AI adapter は `OrganizationInput.personalization` 経由で snapshot の normalized field のみ消費する (first delivery の既存 strategy は読まない)。placement affinity 系は将来 consumer が既存 `LayoutSnapshot` から pure projection する (snapshot 非搭載、owner review 2026-09-13 recommendation)。

### Identity / determinism

- contentDigest は canonical rows の sort・改行 join → 既存 `sha256Canonical` (`rules/PolicyModels.kt:181`) を再利用。rows は spec の grammar に従い **header 行 (`schemaVersion|usageAccess|launcherOriginAvailability`) + per-profile 行 (`profile|profileAvailability`) + entry 行 (`profile|package|field|state(Value/Absent)|value|source`)** を含む (review Blocking 3 + 2026-09-14 Blocking 2)。`NOT_GRANTED` は **system usage section の entry 行の省略** + header state で表現され、query failure (`UNAVAILABLE`) と別 digest になる。**rows の省略規則と snapshot object の形状は sparse object 契約で一致しており、contentDigest は snapshot object の consumer-observable projection を 1:1 に identify する** (2026-09-15 (2nd) review Blocking 2)。部分 availability は section 分離の sealed 型 (`SystemUsageSection` / `LauncherOriginSection`) で表現される (2026-09-15 (3rd) review Blocking 1)。launcher-origin entry 行は `LAUNCHER_ORIGIN_AVAILABLE` である限り常に存在する。
- launcher-origin recency 投影は決定論的: 永続された count 最小 state + 絶対 day anchor と、snapshot 構築時の window anchor から pure 関数で recency bucket を計算する。永続されるのは相対 class ではなく anchor であるため、時間経過後の read でも正しい bucket が得られる (2026-09-14 review Required)。day boundary は spec U-5 確定値 (device 現行 timezone の暦日) に従い、DST / timezone 変更の実挙動は probe (完了条件 (d)) で確認する。
- snapshot identity は content-addressed: `PolicyInputIdentity(PERSONALIZATION_SIGNAL_SNAPSHOT, "personalization-signals-v1", contentDigest)`。**generation は持たない** — cut への参加もしないため「同一内容で generation だけ異なる」状態が発生せず、composer の再読み自己矛盾 (2026-09-13 review Blocking 1) が構造的に起こらない。既存 platform evidence identity が同型 (schema 文字列 + rows digest) の先例。provenance への搭載は non-null 8番目 field (`InputProvenance.personalization`、U-4 確定) であり、すべての `Ready` で運ばれる。
- bucket 計算は usage stats の raw 値と単一の window anchor のみから決定論的に行う pure 関数。window anchor は source が read 開始時に1回だけ読む clock 参照とし、composition 内で2つ目の時点参照を作らない。quantile algorithm は spec U-5 確定値 (nearest-rank: `B_k = x_⌈kN/5⌉`、`bucket(v) = |{k : B_k < v}|` — 境界と同値は下位側。N=5 相異なる値で `x_1→0 … x_5→4`) を実装し、interface test に N=5/6/7/11 の期待値を記載する (2026-09-15 (4th) review で Identity / determinism 側の旧式 `≤` を `<` へ統一)。recency 境界は半開区間 `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)` (launcher-origin は同境界の暦日量子化)。window は spec U-5 probe amendment (実測: interval は暦日非整列の ~24h rolling bucket) に従い **rolling 24h 窓 `[anchor − N·24h, anchor]`** で query し、返された interval を edge フィルタなしでそのまま集計する (30d と 7d は独立 query)。probe 実行の記録は [evidence](../../docs/assessment/pr-321-u5-probe-evidence.md)。

### Alternatives rejected

- **generation 付き identity を `dynamicCutIdentity` に参加させ、cut 不安定時は既存と同様 `InconsistentPolicyRead` へ落とす (初版 draft)**: 同一内容でも capture 毎に generation が進むため A/B 読みが一致せず cut が安定しない自己矛盾 (review Blocking 1)。さらに optional source が Organizer 全体を `NotReady` にし得る点で FR-013 / D-010 違反 (review Blocking 2)。content-addressed identity + cut 外 single-read へ変更。
- **canonical rows を `profile|package|field|value|source` のみにする (初版 draft)**: `usageAccess` state・per-profile availability が digest に反映されず、NOT_GRANTED と query failure が同一 digest になり得た (review Blocking 3)。header / per-profile 行と三値 state を grammar に追加。
- **planner が usage API を直接読む**: determinism/purity (P-09)・testability・privacy すべてで却下。
- **`ClassificationSignals` (S1〜S6) を拡張して usage を載せる**: 分類契約 (spec 10/12) と個人化入力の関心事が異なり、provenance 也不能分離。別 snapshot とした。
- **raw UsageEvents の保存による高精度化**: 保持期間が端末依存で不安定、privacy cost 高。bucket 合成で十分 (spec の候補比較)。
- **usage access を required permission にする**: D-010 違反。
- **request 集合を rank universe にする**: 対象 app の増減だけで既存 app の bucket が変わり、signal が layout 以外の要因で動く (review Required specification)。request 集合から独立した universe のみ許容。
- **snapshot identity だけを provenance に載せ、`OrganizationInput` への実体搭載を将来の usage strategy Issue に defer する (初版 draft)**: 現行の `OrganizationInput` / `Ready(input, provenance)` には snapshot を運ぶ欄がなく、identity だけが残って実体が planner / AI adapter に届かず、spec の Outcome を満たさない (2026-09-14 review Blocking 1)。#203 内で non-null field を追加へ変更。
- **`usageAccess != GRANTED` で entries をすべて空にする (初版 draft)**: system Usage Access を拒否しただけで launcher-origin signal も消失し、launcher-origin の permission-independent 性が失われる (2026-09-14 review Blocking 2)。source ごとの省略規則へ変更。
- **app pair を `LawnchairLauncher.launchAppPair` override の戻り時点で観測する (初版 draft)**: 実 dispatch は `findLastActiveTasksAndRunCallback` の非同期 callback 内 `launchSplitTasks` で行われるため、戻り時点の観測は「dispatch 成功」より早い過早観測になる (2026-09-14 review Blocking 3)。first delivery では app pair を対象外とする。実 dispatch point への seam は Launcher3 bridge を伴うため別 accepted decision で再検討。
- **taskbar を launcher-origin 対象外とする / 未確定のまま child issue へ送る (初版 draft)**: taskbar icon tap は upstream の `LauncherTaskbarUIController.onTaskbarIconLaunched` が同一 seam (`Launcher.logAppLaunch`) に合流し、seam 上に判別手段が存在しないため、対象外とするには合流する起動を除外する追加の fork 機構が必要になる。spec の launcher-origin 定義 (NunuLauncher UI からの起動要求) とも整合しない (2026-09-15 review Blocking 2)。**含める** へ変更し、surface 別の観測時点 (taskbar は起動要求発火) を contract に明記。
- **snapshot field を nullable (`FieldValue?`) で表現する (初版 draft)**: 三値 contract (value / absent / unavailable) と矛盾し、第四の状態 `null` が生じて consumer 側の区別が未定義になる。snapshot 実体が `OrganizationInput` 経由で downstream に露出する以上 interface contract 上の問題である (2026-09-15 review Blocking 1)。non-null closed 型 `SignalField<T>` へ変更。
- **provenance 参加を nullable / optional 枠にする (初版 draft U-4)**: snapshot 実体が常に non-null で `OrganizationInput.personalization` に載ることと非対称になり、「optional source」の optional 性が readiness から composition result の存在まで拡大解釈される (2026-09-15 review)。non-null 8番目 field (`InputProvenance.personalization`) へ変更。
- **taskbar 等の合流を counters 側で「推測フィルタ」する**: `getStatsLogManager` の instance 比較・container 値等による判別は、`StatsLogManager.newInstance` の都度生成や taskbar item の container 共有により信頼できる根拠を持たない (2026-09-15 実読み)。推測フィルタは避け、spec に明記した item 型フィルタ (app pair / promise) のみを行う。
- **snapshot に `capturedAtClass` (clock 由来の表示 metadata) を field として残し digest から除外する (本 revision 初版)**: digest 対象と consumer-observable projection が不一致になり、異なる `capturedAtClass` を持つ snapshot が同一 identity を持つ矛盾が生じる (2026-09-15 (2nd) review Blocking 2)。snapshot 型から除去し、表示目的の情報は diagnostics が snapshot の外側で記録する。
- **availability のある section を単一の `entries` map に同居させ、部分 availability を nullable な section field で表す (本 revision 初版)**: system usage 利用不能 + launcher-origin 利用可能の表現が未定義になり、nullable section は「signal 値の null 禁止」規律と衝突する (2026-09-15 (3rd) review Blocking 1)。`SystemUsageSection` / `LauncherOriginSection` の sealed 型 (Available / Unavailable) へ変更し、構造的不存在を closed 型で表現する。
- **unavailable section も per-package entry (三値 `SignalField` の `Unavailable`) として object に持つ (本 revision 初版)**: canonical rows は unavailable section の行を省略する一方、object は全 (package, profile) entry を持つため、digest が object 全体を identify せず、package 集合の異なる snapshot が同一 digest になり得た (2026-09-15 (2nd) review Blocking 2)。sparse object 契約 (unavailable section の entry は object 側にも構築しない) へ変更し、`Unavailable` は section-level availability state 専用とした。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | snapshot 型 (`SignalField<T>` closed 二値・sparse object 契約を含む)・source interface・bucket 計算 (nearest-rank quantile / recency 半開区間) と recency 投影 (pure) | planner から Android 型を隔離 |
| `organizer/planning/OrganizationInput.kt` | `personalization: PersonalizationSignalSnapshot` (non-null) field 追加、既存 constructor caller の更新 | snapshot 実体を planner seam へ届ける唯一の経路 (2026-09-14 review Blocking 1)。既存 strategy は読まないため挙動不変 |
| `organizer/integration/CompositionModels.kt` | `InputProvenance` に `personalization: PolicyInputIdentity` (non-null、8番目 field) 追加、constructor caller の更新 | U-4 確定 (2026-09-15 review recommendation)。snapshot identity は常に `Ready` の provenance に運ばれる |
| `organizer/integration/` | `AndroidPersonalizationSignalSnapshotSource` (aggregator — 2 source の合成 owner)、`AndroidSystemUsageSignalReader`、`LauncherOriginSignalReader`、`ProductionOrganizationInputComposer` wiring、composer への optional single-read 接続と `OrganizationInput` への snapshot 搭載 | 既存 composition seam の唯一の拡張点。合成責任の明示 (2026-09-15 review Required)。`dynamicCutIdentity` は変更しない |
| `organizer/rules/PolicyModels.kt` | `PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT` 追加 | identity の kind が必要 (#228 が closed set 追加の許容を示済み) |
| Launcher 側観測点 | `LawnchairLauncher` での `logAppLaunch` override から counter store への接続。`super.logAppLaunch(...)` を全 path で exactly once 呼び、既存 logging / prediction side effect を保存。counter 書き込みは best-effort 非同期で失敗を吸収。item 型フィルタ (app pair / promise icon) を含む | fork 所有 class で完結できれば Launcher3 編集ゼロ。taskbar 合流は upstream 既存挙動として含める (2026-09-15 review Blocking 2)。やむを得ず Launcher3 file を触る場合は最小 bridge + Issue 番号コメント (AGENTS.md) |
| `res/values[, -ja]/` | opt-in 導線・rationale 文言 (U-2) | 権限追加は spec + risk 評価が必要 (AGENTS.md)。本plan は app-op ベースであり新 runtime permission ではないことを明記 |
| `docs/product/requirements.md` | FR-013 traceability 更新 | AC-9 |
| Diagnostics | `usageAccessState` typed code と snapshot identity のみ追加許可 | privacy 契約。organizer-diagnostics.md の許可欄更新は同 PR |
| ADR-0007 | optional source の provenance 参加と「usage 変化は plan を stale にしない」semantics の追記 | ADR §6 の protocol 適用範囲が mandatory source であることを明文化。実装 PR で同時更新 |

実装は child issue 分割を推奨: (1) snapshot 型 (`SignalField<T>` 二値・sparse object 含む) + pure source + identity test、(2) Android adapter (system usage reader + aggregator) + permission state、(3) composer/provenance 接続 + `OrganizationInput.personalization` field 追加 + `InputProvenance.personalization` 追加 (constructor caller の機械的更新を含む)、(4) launcher-origin counter (logAppLaunch override・item 型フィルタ・super 保存・store)、(5) permission UI/文言 (U-2: settings 常設導線)、(6) diagnostics/requirements 更新。段階 (3) では DESIGN.md §9 図/module row と ADR-0007 追記を同 PR に含める。各段階で Organizer は従来どおり動作しなければならない。probe / instrumentation (UsageStatsManager 実測等) は実装開始禁止の対象外であり、U-5 に依存する実装 PR は merge 前に spec U-5 の probe 完了条件 (a)〜(d) を満たして記録する。

## Migration and recovery

- 新規 app-private state は launcher-origin counter store のみ (**snapshot 自体は永続化しない** — cache も作らない。2026-09-14 review Required)。Launcher DB・recovery store は無変更。
- counter store 破損: 読み取り失敗で `launcherOriginAvailability=LAUNCHER_ORIGIN_UNAVAILABLE` とし launcher-origin section の entry を構築しない (fail-closed、sparse object 契約)。修復は初期化のみ。
- downgrade: counter store が読む schema は `personalization-signals-v1` 配下の初版のみ。将来の schema 変更は spec 182 の三ケース downgrade model に従う。
- opt-out/revoke: usage access の revoke は次回 composition から `NOT_GRANTED` (system usage section を利用不能化 — 該当 section の package-level entry を構築しない。launcher-origin section は保持 — 2026-09-14 review Blocking 2)。launcher-origin counter 自体の停止・消去は U-3 draft の user-visible 操作 (settings の toggle — default ON、OFF で記録停止 + 既存記録消去 — と明示的な消去操作)。counter store は backup / restore・export 対象外。過去 snapshot の流用なし。layout への影響なし。

## Testing strategy

- **Unit (pure)**: bucket 計算の境界値・決定性 (同一入力→同一 digest)・`SignalField<T>` 二値 (Value / Absent) の区別と non-null 性・sparse object 契約 (unavailable section の entry 不存在)・canonical rows (header / per-profile / entry 行) の sort と grammar。property test: 任意の usage stats 合成に対し digest が入力の全順序に安定、**かつ contentDigest が snapshot object の consumer-observable projection と 1:1 に対応すること (同一 digest ↔ object 等価)** (2026-09-15 (2nd) review Blocking 2)。`NOT_GRANTED` と `UNAVAILABLE`、「system usage section 利用不能 (entry なし)」と「同 section 利用可能で全 field `Absent`」が別 digest になること、および **`NOT_GRANTED` 下でも launcher-origin entry 行が保持されること** (AC-2 / AC-13)。U-5 確定値の検証: rank universe (usage record 存在 app)・nearest-rank quantile の期待値 (N=5/6/7/11 — `B_k = x_⌈kN/5⌉`、`bucket(v) = |{k : B_k < v}|`、N=5 相異なる値で `x_1→0 … x_5→4`、境界と同値は下位側・同値同 bucket)・少数サンプル (`Absent`)・recency 半開区間境界 `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)` と launcher-origin 暦日量子化 (当日 / 1–6日前 / 7–29日前 / ≥30日前)。launcher-origin recency 投影: 永続した day anchor に対し擬似 clock を進めて recency bucket が正しく再分類されること (例: 当日に記録した起動が翌日には `1–6日前` に投影される — 2026-09-14 review Required)。
- **Launcher hook (unit)**: `LawnchairLauncher.logAppLaunch` override の検証 (AC-15 / AC-14 / AC-16)。`super.logAppLaunch(...)` が **exactly once** 呼ばれること (mock で呼び出し回数を検証)、既存 side effect (All Apps session InstanceId 補正・prediction rank・`LAUNCHER_APP_LAUNCH_TAP`・hotseat prediction ranking info) が override 前後で不変であること、counter store の例外注入時に例外が launch flow / logging へ伝播しないこと、item 型フィルタにより app pair (`AppPairInfo`) / promise icon が counting されず通常 app / deep shortcut が counting されること。
- **Composition**: composer test (`tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt` — main に存在することを再確認済み。#228 の `ScopeComposedPlannerTest` / `ApplyProtocolCandidateAvailabilityTest` も composer 拡張の test 先例) 拡張。source がある/ない、`NOT_GRANTED` でも `Ready`、source が失敗/例外しても `Ready` で personalization の該当 section のみ利用不能化 (該当 section の entries を構築しない、AC-11)、**personalization 由来の `NotReady` / `InconsistentPolicyRead` が存在しないこと**、`Ready.input.personalization` がすべての run mode で non-null であり同一入力から同一 identity が得られること (AC-12)、`Ready.provenance.personalization` が常に non-null な snapshot identity を運ぶこと (U-4)、既存 planner / strategy の test が field 追加前後で無変更に通ること (AC-12 の挙動不変担保)、mandatory `dynamicCutIdentity` の值が personalization 有無で変わらないこと (AC-10)。
- **Integration/instrumentation**: 実 `UsageStatsManager` は instrumentation で permission granted/denied/unavailable の3状態。probe 完了条件 (a)〜(d) は 2026-09-15 に API 36 emulator で実行・記録済み ([evidence](../../docs/assessment/pr-321-u5-probe-evidence.md); interval semantics の逸脱検出と spec U-5 amendment を含む)。probe は instrumentation test (`UsageStatsIntervalProbeTest`) として再実行可能に保つ。work profile は emulated profile で per-profile 挙動 (U-6 は 2026-09-14 re-review で妥当確認済み)。launcher-origin observation は dispatch 成功 / 同期失敗 (`ActivityNotFoundException`) の両方を駆動し、失敗時に counter が進まないことを確認。**app pair 起動では counter が進まないこと** (AC-14 — launcher 経由は hook 不在、taskbar 経由は item 型フィルタの回帰担保) および **taskbar icon tap が合流経路経由で counter が進むこと** (AC-16) を観測する。
- **Device**: permission opt-in→run→revoke→run の物理端末 evidence。bucket が端末再起動を跨いでも合成可能なことの代表確認。deep shortcut 起動の observation 記録と、app pair 起動が記録対象外であることの代表確認 (AC-14)。taskbar 表示端末での taskbar icon tap 観測の代表確認 (AC-16、実施可能な場合のみ記録し、未実施は evidence の未確認範囲として明記)。
- **Privacy**: diagnostics journal の出力が typed code + identity のみであることを contract test。purity guard: `organizer/planning/`, `personalization/` (domain) に android import が現れないことの architecture test (AC-6)。
- **UI**: opt-in 導線の TalkBack/Switch Access/font scaling (spec 52/195 準拠)。

## Risks

- usage stats API の集計精度の機種差 (undercount・期間切れ) → bucket 化で緩和、device evidence で代表確認。精度を前提にした strategy は作らない。
- app-op state 読み取りの端末差 → `UNAVAILABLE` typed 化で吸収。
- `UsageStatsManager` は profile 間で query 可能性が異なる → per-profile fail-closed (U-6)。
- composition ごとの usage query cost (`queryUsageStats` 30d 分) → `NOT_GRANTED` は app-op check のみで fast path、`GRANTED` 時も composer 呼び出し executor 上で実行し UI thread を block しない。cache による短期再利用は digest の決定性と意図が分かりにくくなるため first delivery では導入しない。
- single read のため mandatory cut と snapshot の時点が僅かにずれ得る → spec の stale semantics (usage 変化は plan を stale にしない、capture revision が正本) により許容。将来 planner が usage を消費する際に ADR-0007 §6 手続きで再判断。
- composer への新規入力追加 (cut 外であっても wiring・provenance) は既存 composition test 全体に影響し得る (#228 の composer 拡張が既に同影響を実証) → child issue (3) を単独 PR に分離。
- `OrganizationInput` への field 追加は constructor caller 全体 (composer + 多数の unit test fixture) に機械的変更を強いる → named parameter / default 付き追加の可否を child issue で確認し、機械的更新を1 PR に閉じる。既存 strategy の挙動が変わらないことは既存 test の無変更通過で担保する (AC-12)。
- app pair を launcher-origin から除外した結果、split 起動が launcher-origin signal に記録されない過小報告が残る → 仕様上の既知の blind spot として spec に明記済み。実 dispatch point への観測 seam (Launcher3 bridge) が必要になった場合は別 accepted decision で再検討する。
- taskbar 由来の観測は「起動要求発火」時点であり、overview mode の非同期 dispatch 失敗・in-app mode の同期 dispatch 失敗を補正しないため過大報告になり得る → spec に surface 別観測時点として明記済みの既知 limitation。launcher-origin は高精度を要求しない local signal であり、system usage と別 source identity で解釈する。
- `QuickstepLauncher.logAppLaunch` の既存 side effect (All Apps session InstanceId 補正 / prediction rank / `LAUNCHER_APP_LAUNCH_TAP` / hotseat prediction ranking info) を override が壊すリスク (2026-09-15 review Required) → super exactly once 契約と unit regression test (AC-15)、counter 書き込みの best-effort 化で担保する。

## Explicitly unverified areas

- `UsageStatsManager.queryUsageStats` の実 returns 値・集計粒度・interval 境界の挙動は 2026-09-15 に probe で実測し記録済み ([evidence](../../docs/assessment/pr-321-u5-probe-evidence.md); interval は暦日非整列の ~24h rolling bucket → spec U-5 を rolling 窓に修正)。機種差 (集計精度・保持期間) は引き続き端末依存であり、bucket 化で緩和する。
- profile ごとの usage query 可否 (work profile で `createUserContext` 相当が必要か) は未検証 (#129 の慣行、#228 の `userForProfile` serial bind 慣行を踏襲する予定)。
- taskbar の合流は 2026-09-15 に実読みで確認済み (`LauncherTaskbarUIController.onTaskbarIconLaunched` → `Launcher.logAppLaunch`、呼び出し元 3箇所)。残る未検証は、taskbar folder の open view 等その他の upstream 経路が同一 seam に合流するかの完全な列挙である。**scope は確定済み** (taskbar icon tap を含む) であり、列挙の結果で scope が変わることはない (追加経路は同一 seam の upstream 挙動に従う)。
- launcher-origin day anchor の day boundary は spec U-5 確定値 (device 現行 timezone の暦日) に従う。残る未検証は DST 遷移・timezone 変更時の実挙動の probe による確認である (probe 完了条件 (d))。
- `LawnchairLauncher` での `logAppLaunch` override が Quickstep 側の起動 (hero / predicted 委譲等) をすべて通るかは未検証 (実装時に列挙確認。追加経路の発見は scope を変えず観測範囲の記録にのみ影響する)。
- install age の `firstInstallTime` / `lastUpdateTime` の実機挙動 (update 後の値) は未検証。本specでは first delivery から defer 済みのため実装への影響はない。
- #204 exchange contract の具体的な消費形式 (本snapshot をそのまま渡すか projection するか) は #204 側の未決定事項 (#204 spec は未accept)。#203 の snapshot 確定が #204 受入の前提の一つである。
