# Implementation Plan: versioned local usage / implicit-preference signal snapshot

> Issue: #203
> Spec: [spec.md](./spec.md)
> Status: draft — spec の Unresolved decisions (U-1〜U-6) が解消されるまで実装を開始しない。
> 本plan は初回作成時 (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) の調査を基に、2026-09-12 の re-entry check で `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4` までの差分を再検証・追記したものである。
> 2026-09-13 re-entry: `origin/main` = `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` までの差分 (#283/#287/#300/#304 関連) を確認 — 本planが依存する composer / provenance / planning / requirements / ADR に変更なし。同日の owner review (Changes requested) を受け、`generation` 削除 (content-addressed identity)、mandatory dynamic cut 外の single-read + typed downgrade 契約、canonicalization 拡張 (access state / profile availability / 三値 state)、launcher-origin 観測単位の確定、install-age の事実修正・defer を反映した。

## Current evidence

`origin/main` で確認した事実 (推測と分離)。初回確認 2026-09-12 (main = `f9afd8bfde`)、再確認 2026-09-13 (main = `c5274b5d0d`)。

- Planner 入力: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`
  - `OrganizationInput(snapshot, rules, taxonomy, signals, targets, runMode)`。`ClassificationSignals` は分類専用 (`SignalSource` S1〜S6、`CategoryId` 候補のみ)。usage/frequency/recency を運ぶ欄は存在しない。
  - `RunMode` は #228 の実装により `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` の3値。personalization snapshot は新規 run mode を追加せず既存 modes に optional input として参加する。
- Composition seam: `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  - `OrganizationInputComposer` は #228 実装後2 entry point (`composeFullOrganization()` / `composeScopeComposedOrganization(selection)`) を持ち、両方とも `composeInternal(selection)` に委譲する。personalization snapshot の読み取りは `composeInternal` の stable cut 内への optional 追加になる。
  - `DefaultOrganizationInputComposer` は canonical capture → bundle → (selection / overrides / platform evidence を前後2回読む) stable cut → signals/targets materialize → `OrganizationInputComposition.Ready(input, InputProvenance)`。
  - `InputProvenance` (`CompositionModels.kt`) は `revision + rules + taxonomy + signals + targets + policyBundle + layoutStrategySelection` (7 field、baseline から変更なし — 再確認済み)。`dynamicCutIdentity(bundle, overrides, evidence, selection)` (OrganizationInputComposer.kt:604-616) は bundle sha + overrides の versionOrGeneration+sha + evidence の **sha のみ** (generation 不参加) + selection の versionOrGeneration+sha を束ね、不一致時 `MAX_DYNAMIC_ATTEMPTS(2)` retry → `NotReady(InconsistentPolicyRead)`。**evidence の digest-only 参加は、content-addressed な input が generation なしで cut に参加できる既存の先例である。**
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
- Launcher launch path (2026-09-13 に `ItemClickHandler.java` / `ActivityContext.java` / `Launcher.java` / `QuickstepLauncher.java` / `AppPairsController.java` を実読みして確認):
  - `ActivityContext.startActivitySafely` (Launcher3) は platform dispatch (`startShortcut` / `startActivity` / `startMainActivity`) の **try 内の成功時にのみ** 既存の `logAppLaunch(statsLogManager, item, instanceId)` を呼び、`NullPointerException` / `ActivityNotFoundException` / `SecurityException` を catch した (同期失敗の) 場合は呼ばない。「platform への launch request が正常 dispatch された」ことの既定義 observation point が存在する。
  - `ItemClickHandler` の `FLAG_START_FOR_RESULT` 付き shortcut path も `launcher.logAppLaunch(...)` を直接呼ぶ (同じ seam に合流)。
  - app pair は `ItemClickHandler.onClickAppPairIcon` → `QuickstepLauncher.launchAppPair` → `AppPairsController.launchAppPair` (実行中 task の再利用あり) で `startActivitySafely` を通らず、`logAppLaunch` が発火しない — 別 hook が必要。
  - `LawnchairLauncher : QuickstepLauncher` (`lawnchair/src/app/lawnchair/LawnchairLauncher.kt`) が fork 所有の launcher subclass であり、`logAppLaunch` / `launchAppPair` の override 観測点として Launcher3 file を直接編集せずに済む候補になる。
  - taskbar (`TaskbarActivityContext`) 独自の dispatch 経路が `logAppLaunch` に合流するかは未検証 (下記 unverified)。`ItemClickHandler.java` 自体への fork 側 commit は 0 件のまま (2026-09-13 再確認)。
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
data class PersonalizationSignalSnapshot(/* spec の契約: schemaVersion, contentDigest,
    capturedAtClass (digest 外), usageAccess, profileAvailability, entries, sources */)
// NOT_GRANTED / UNAVAILABLE / per-profile 失敗は「対応 field を unavailable 化した snapshot」
// として Ready に相当する形で返す。personalization 由来の失敗で呼び出し側を失敗させない
// (spec: optional source 契約 / review Blocking 2)。

// integration (Android)
class AndroidUsageSignalSnapshotSource(
    appContext: Context,
    /* UsageStatsManager / AppOpsManager / LauncherApps access; per-profile binding は
       AndroidClassificationSignalSnapshotSource と同じ UserCache serial 慣行 */
) : PersonalizationSignalSnapshotSource
class LauncherOriginLaunchCounterStore(/* app-private 永続化; count class / recency class のみ */)
```

- planner 側は本Issueでは変更しない (usage-based strategy は #182 child 形式の別Issue)。`OrganizationInput` への個人化欄の追加方法 (新 field vs 将来 strategy 用の拡張) は、usage を消費する strategy 導入 Issue で確定する。本Issueの縦切りは composition/provenance への optional 参加と snapshot 取得までである。
- usage access state の読み取り (`AppOpsManager` / `PACKAGE_USAGE_STATS` check) は integration 側に隠す。domain は `UsageAccessState` のみ。

### Seams

- **読み取り seam**: `PersonalizationSignalSnapshotSource` (production: `AndroidUsageSignalSnapshotSource`、test: in-memory fake)。呼び出し側は composer。planner/AI adapter は直接呼ばない。`read()` は total (throw しない) — platform 失敗は typed `unavailable` snapshot へ変換する。composer 側も防御として try で囲み、想定外の例外があっても personalization を `unavailable` 化して `Ready` を返す (二重化。バグ隠蔽にならないよう diagnostic に typed code を出す)。
- **composer 接続**: `DefaultOrganizationInputComposer.composeInternal` への optional source 追加 (`ProductionOrganizationInputComposer` が wiring 点)。`dynamicCutIdentity` は **変更しない** — personalization は mandatory cut の外側で composition attempt ごとに 1回だけ読み、source 失敗・churn は personalization field の typed `unavailable` downgrade としてのみ現れる。`NotReady` / `InconsistentPolicyRead` / `MAX_DYNAMIC_ATTEMPTS` は mandatory source 専用のまま (spec: review Blocking 2)。snapshot の identity は `InputProvenance` へ optional 参加する (U-4)。#228 由来の `composeScopeComposedOrganization` path でも同じ optional source が走ってよい (additions と personalization は独立)。
- **launcher-origin hook**: spec の観測単位 contract (dispatch 成功) に従い、既定義 observation point `ActivityContext.logAppLaunch` を `LawnchairLauncher` で override して `LauncherOriginLaunchCounterStore` へ接続する (Launcher3 file への直接編集を回避)。app pair は `launchAppPair` override で member ごとに observation。発火は dispatch 成功後、非同期、例外で launch 自体を失敗させない。taskbar 経路が `logAppLaunch` に合流するかは未検証のため、対象経路の確定を child issue の最初の作業とする。

### Data flow

1. (background) launcher 経由 launch dispatch 成功 → counter store 更新 (count class / last-launch class)。観測単位は spec の launcher-origin contract。
2. manual run 開始 → composer は mandatory 入力の stable cut とは独立に、`PersonalizationSignalSnapshotSource.read(request)` を **1回** 呼ぶ。
   - `GRANTED`: 単一の window anchor (read 開始時に1回だけ読む clock) を基準に `UsageStatsManager` 日次集計から 30d/7d foreground bucket・recency・active-days bucket を合成し canonical rows 化 → contentDigest。rank universe は request 集合から独立した決定論的 universe (U-5 で確定)。
   - `NOT_GRANTED` / `UNAVAILABLE` / per-profile 失敗: 対応 field を `unavailable` として型化 (absence と区別)。entries 空 + header state。
3. snapshot identity を provenance へ optional 参加させ、既存 mandatory cut が安定していれば `Ready`。personalization は `Ready` / `NotReady` の分岐に参加しない。
4. planner/AI adapter は snapshot の normalized field のみ消費。placement affinity 系は将来 consumer が既存 `LayoutSnapshot` から pure projection する (snapshot 非搭載、owner review 2026-09-13 recommendation)。

### Identity / determinism

- contentDigest は canonical rows の sort・改行 join → 既存 `sha256Canonical` (`rules/`) を再利用。rows は spec の grammar に従い **header 行 (`schemaVersion|usageAccess`) + per-profile 行 (`profile|profileAvailability`) + entry 行 (`profile|package|field|state(value/absent/unavailable)|value|source`)** を含む (review Blocking 3)。`NOT_GRANTED` は entries 空 + header state で表現され、query failure (`UNAVAILABLE`) と別 digest になる。
- snapshot identity は content-addressed: `PolicyInputIdentity(PERSONALIZATION_SIGNAL_SNAPSHOT, "personalization-signals-v1", contentDigest)`。**generation は持たない** — cut への参加もしないため「同一内容で generation だけ異なる」状態が発生せず、composer の再読み自己矛盾 (review Blocking 1) が構造的に起こらない。既存 platform evidence identity が同型 (schema 文字列 + rows digest) の先例。
- bucket 計算は usage stats の raw 値と単一の window anchor のみから決定論的に行う pure 関数。window anchor は source が read 開始時に1回だけ読む clock 参照とし、composition 内で2つ目の時点参照を作らない。interval 境界・timezone・calendar boundary の扱いは U-5 で確定するまで実装しない。

### Alternatives rejected

- **generation 付き identity を `dynamicCutIdentity` に参加させ、cut 不安定時は既存と同様 `InconsistentPolicyRead` へ落とす (初版 draft)**: 同一内容でも capture 毎に generation が進むため A/B 読みが一致せず cut が安定しない自己矛盾 (review Blocking 1)。さらに optional source が Organizer 全体を `NotReady` にし得る点で FR-013 / D-010 違反 (review Blocking 2)。content-addressed identity + cut 外 single-read へ変更。
- **canonical rows を `profile|package|field|value|source` のみにする (初版 draft)**: `usageAccess` state・per-profile availability が digest に反映されず、NOT_GRANTED と query failure が同一 digest になり得た (review Blocking 3)。header / per-profile 行と三値 state を grammar に追加。
- **planner が usage API を直接読む**: determinism/purity (P-09)・testability・privacy すべてで却下。
- **`ClassificationSignals` (S1〜S6) を拡張して usage を載せる**: 分類契約 (spec 10/12) と個人化入力の関心事が異なり、provenance 也不能分離。別 snapshot とした。
- **raw UsageEvents の保存による高精度化**: 保持期間が端末依存で不安定、privacy cost 高。bucket 合成で十分 (spec の候補比較)。
- **usage access を required permission にする**: D-010 違反。
- **request 集合を rank universe にする**: 対象 app の増減だけで既存 app の bucket が変わり、signal が layout 以外の要因で動く (review Required specification)。request 集合から独立した universe のみ許容。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | snapshot 型・source interface・bucket 計算 (pure) | planner から Android 型を隔離 |
| `organizer/integration/` | `AndroidUsageSignalSnapshotSource`、`ProductionOrganizationInputComposer` wiring、composer への optional single-read 接続、`InputProvenance` の optional 参加枠 | 既存 composition seam の唯一の拡張点。`dynamicCutIdentity` は変更しない |
| `organizer/rules/PolicyModels.kt` | `PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT` 追加 | identity の kind が必要 (#228 が closed set 追加の許容を示済み) |
| Launcher 側観測点 | `LawnchairLauncher` での `logAppLaunch` override (+ app pair 用 `launchAppPair` override) から counter store への接続 | fork 所有 class で完結できれば Launcher3 編集ゼロ。やむを得ず Launcher3 file を触る場合は最小 bridge + Issue 番号コメント (AGENTS.md) |
| `res/values[, -ja]/` | opt-in 導線・rationale 文言 (U-2) | 権限追加は spec + risk 評価が必要 (AGENTS.md)。本plan は app-op ベースであり新 runtime permission ではないことを明記 |
| `docs/product/requirements.md` | FR-013 traceability 更新 | AC-9 |
| Diagnostics | `usageAccessState` typed code と snapshot identity のみ追加許可 | privacy 契約。organizer-diagnostics.md の許可欄更新は同 PR |
| ADR-0007 | optional source の provenance 参加と「usage 変化は plan を stale にしない」semantics の追記 | ADR §6 の protocol 適用範囲が mandatory source であることを明文化。実装 PR で同時更新 |

実装は child issue 分割を推奨: (1) snapshot 型+pure source+identity test、(2) Android adapter+permission state、(3) composer/provenance 接続、(4) launcher-origin counter、(5) permission UI/文言、(6) diagnostics/requirements 更新。段階 (3) では DESIGN.md §9 図/module row と ADR-0007 追記を同 PR に含める。各段階で Organizer は従来どおり動作しなければならない。

## Migration and recovery

- 新規 app-private state (launcher-origin counter、必要なら snapshot cache) のみ。Launcher DB・recovery store は無変更。
- counter store 破損: 読み取り失敗で `LAUNCHER_ORIGIN` field を `unavailable` 化 (fail-closed)。修復は初期化のみ。
- downgrade: 新 binary が読む schema は `personalization-signals-v1` のみ。将来の schema 変更は spec 182 の三ケース downgrade model に従う。
- opt-out/revoke: 次回 composition から `NOT_GRANTED`。過去 snapshot の流用なし。layout への影響なし。

## Testing strategy

- **Unit (pure)**: bucket 計算の境界値・決定性 (同一入力→同一 digest)・absence/unavailable/value の三値区別・canonical rows (header / per-profile / entry 行) の sort と grammar。property test: 任意の usage stats 合成に対し digest が入力の全順序に安定。`NOT_GRANTED` と `UNAVAILABLE`、全 field `absent` と全 field `unavailable` が別 digest になること (AC-2)。
- **Composition**: composer test (`tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt` — main に存在することを再確認済み。#228 の `ScopeComposedPlannerTest` / `ApplyProtocolCandidateAvailabilityTest` も composer 拡張の test 先例) 拡張。source がある/ない、`NOT_GRANTED` でも `Ready`、source が失敗/例外しても `Ready` で personalization field のみ `unavailable` (AC-11)、**personalization 由来の `NotReady` / `InconsistentPolicyRead` が存在しないこと**、provenance identity への optional 参加、mandatory `dynamicCutIdentity` の值が personalization 有無で変わらないこと (AC-10)。
- **Integration/instrumentation**: 実 `UsageStatsManager` は instrumentation で permission granted/denied/unavailable の3状態。実 returns の interval 境界・粒度を実測し U-5 の確定入力とする。work profile は emulated profile で per-profile 挙動 (U-6 解消後)。launcher-origin observation は dispatch 成功 / 同期失敗 (`ActivityNotFoundException`) の両方を駆動し、失敗時に counter が進まないことを確認。
- **Device**: permission opt-in→run→revoke→run の物理端末 evidence。bucket が端末再起動を跨いでも合成可能なことの代表確認。app pair / deep shortcut 起動の observation 記録の代表確認。
- **Privacy**: diagnostics journal の出力が typed code + identity のみであることを contract test。purity guard: `organizer/planning/`, `personalization/` (domain) に android import が現れないことの architecture test (AC-6)。
- **UI**: opt-in 導線の TalkBack/Switch Access/font scaling (spec 52/195 準拠)。

## Risks

- usage stats API の集計精度の機種差 (undercount・期間切れ) → bucket 化で緩和、device evidence で代表確認。精度を前提にした strategy は作らない。
- app-op state 読み取りの端末差 → `UNAVAILABLE` typed 化で吸収。
- `UsageStatsManager` は profile 間で query 可能性が異なる → per-profile fail-closed (U-6)。
- composition ごとの usage query cost (`queryUsageStats` 30d 分) → `NOT_GRANTED` は app-op check のみで fast path、`GRANTED` 時も composer 呼び出し executor 上で実行し UI thread を block しない。cache による短期再利用は digest の決定性と意図が分かりにくくなるため first delivery では導入しない。
- single read のため mandatory cut と snapshot の時点が僅かにずれ得る → spec の stale semantics (usage 変化は plan を stale にしない、capture revision が正本) により許容。将来 planner が usage を消費する際に ADR-0007 §6 手続きで再判断。
- composer への新規入力追加 (cut 外であっても wiring・provenance) は既存 composition test 全体に影響し得る (#228 の composer 拡張が既に同影響を実証) → child issue (3) を単独 PR に分離。

## Explicitly unverified areas

- `UsageStatsManager.queryUsageStats` の実 returns 値・集計粒度・interval 境界の挙動は現行 main の code からは確認できない (instrumentation で実測し、U-5 の rank universe / window semantics 確定の入力とする)。
- profile ごとの usage query 可否 (work profile で `createUserContext` 相当が必要か) は未検証 (#129 の慣行、#228 の `userForProfile` serial bind 慣行を踏襲する予定)。
- taskbar 独自 dispatch 経路が `ActivityContext.logAppLaunch` に合流するか未検証。`logAppLaunch` override + `launchAppPair` override が spec の観測単位 contract の対象経路をすべて覆うかは、child issue (4) の最初に launch path を列挙して確認する。
- `LawnchairLauncher` での `logAppLaunch` override が Quickstep 側の起動 (hero / predicted 委譲等) をすべて通るかは未検証 (上項と同じく実装時に列挙確認)。
- install age の `firstInstallTime` / `lastUpdateTime` の実機挙動 (update 後の値) は未検証。本specでは first delivery から defer 済みのため実装への影響はない。
- #204 exchange contract の具体的な消費形式 (本snapshot をそのまま渡すか projection するか) は #204 側の未決定事項 (#204 spec は未accept)。#203 の snapshot 確定が #204 受入の前提の一つである。
