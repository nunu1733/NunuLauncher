# Implementation Plan: versioned local usage / implicit-preference signal snapshot

> Issue: #203
> Spec: [spec.md](./spec.md)
> Status: draft — spec の Unresolved decisions (U-1〜U-6) が解消されるまで実装を開始しない。
> 本plan は初回作成時 (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) の調査を基に、2026-09-12 の re-entry check で `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4` までの差分を再検証・追記したものである。
> 2026-09-13 re-entry: `origin/main` = `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` までの差分 (#283/#287/#300/#304 関連) を確認 — 本planが依存する composer / provenance / planning / requirements / ADR に変更なし。同日の owner review (Changes requested) を受け、`generation` 削除 (content-addressed identity)、mandatory dynamic cut 外の single-read + typed downgrade 契約、canonicalization 拡張 (access state / profile availability / 三値 state)、launcher-origin 観測単位の確定、install-age の事実修正・defer を反映した。
> 2026-09-14 re-entry: `origin/main` = `397d3fd957` (merge commit `66d8572e` で本branchへ取り込み) までの差分 (#298/#299/#315 関連) を確認 — composer への変更は `CaptureFailureObserver.onCaptureFailure` への `invariant: CaptureInvariantCategory?` 追加 (diagnostics、#299) のみで、stable cut / `dynamicCutIdentity` / provenance / `OrganizationInput` の形状は不変 (`dynamicCutIdentity` の定義位置は 604-616 行から 629 行へ移動、`MAX_DYNAMIC_ATTEMPTS = 2` は不変)。`docs/engineering/organizer-diagnostics.md` の変更は #299 の `invariant=` field 追加のみで、package / raw usage の Never 境界は不変。同日の owner re-review (Changes requested) を受け、(1) snapshot 実体を `OrganizationInput` の non-null field で downstream へ渡す (Blocking 1)、(2) `usageAccess` を system usage source 専用 state とし launcher-origin entry を `NOT_GRANTED` 下でも保持 (Blocking 2)、(3) app pair を first delivery 対象から除外 (Blocking 3 — `QuickstepLauncher.launchAppPair` は `AppPairsController.launchAppPair` (同 file 240行) → `findLastActiveTasksAndRunCallback` (248行) の非同期 callback 内 `launchSplitTasks` (279行) であり、override 戻り時点では実 dispatch が起きていないことを実読みで確認)、(4) launcher-origin counter は絶対 day anchor を永続化し recency bucket を read 時に投影 (Required)、(5) snapshot 非永続化の明記 (Required)、(6) fallback 表現の修正 (Minor) を反映した。

## Current evidence

`origin/main` で確認した事実 (推測と分離)。初回確認 2026-09-12 (main = `f9afd8bfde`)、再確認 2026-09-13 (main = `c5274b5d0d`)、再確認 2026-09-14 (main = `397d3fd957`)。

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
- Launcher launch path (2026-09-13 に `ItemClickHandler.java` / `views/ActivityContext.java` / `Launcher.java` / `QuickstepLauncher.java` / `AppPairsController.java` を実読みして確認、2026-09-14 に再確認):
  - `ActivityContext.startActivitySafely` (`views/ActivityContext.java:406`) は platform dispatch (`startShortcut` / `startActivity` / `startMainActivity`) の **try 内の成功時にのみ** 既存の `logAppLaunch(statsLogManager, item, instanceId)` (同 file :465、`QuickstepLauncher` が :311 で override) を呼び、`NullPointerException` / `ActivityNotFoundException` / `SecurityException` を catch した (同期失敗の) 場合は呼ばない。「platform への launch request が正常 dispatch された」ことの既定義 observation point が存在する。
  - `ItemClickHandler` の `FLAG_START_FOR_RESULT` 付き shortcut path も `launcher.logAppLaunch(...)` を直接呼ぶ (同じ seam に合流)。
  - **app pair は first delivery の観測対象から除外する** (2026-09-14 review Blocking 3)。`ItemClickHandler.onClickAppPairIcon` → `QuickstepLauncher.launchAppPair` (:1340、`AppPairsController.launchAppPair` への委譲のみ) → `AppPairsController.launchAppPair` (:240) は `findLastActiveTasksAndRunCallback` (:248) を登録するだけで、実 platform dispatch (`launchSplitTasks`) は **非同期 callback 内** (:279) で行われる。override 戻り時点での観測は「dispatch 成功」contract より早くなるため、呼び出し点に観測を置く実装は spec 違反になる。実 dispatch point への seam 追加は Launcher3 bridge を伴うため first delivery では行わない。
  - `LawnchairLauncher : QuickstepLauncher` (`lawnchair/src/app/lawnchair/LawnchairLauncher.kt:99`) が fork 所有の launcher subclass であり、`logAppLaunch` の override 観測点として Launcher3 file を直接編集せずに済む候補になる (現時点で `logAppLaunch` / `launchAppPair` の override は未実装、2026-09-14 再確認)。
  - taskbar (`TaskbarActivityContext`) 独自の dispatch 経路が `logAppLaunch` に合流するかは未検証 (下記 unverified)。`ItemClickHandler.java` 自体への fork 側 commit は 0 件のまま (2026-09-13 再確認、`c5274b5d..origin/main` の diff にも含まれず)。
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

- **読み取り seam**: `PersonalizationSignalSnapshotSource` (production: `AndroidUsageSignalSnapshotSource`、test: in-memory fake)。呼び出し側は composer。planner/AI adapter は直接呼ばない。`read()` は total (throw しない) — platform 失敗は typed `unavailable` snapshot へ変換する。composer 側も防御として try で囲み、想定外の例外があっても personalization を `unavailable` 化して `Ready` を返す (二重化。バグ隠蔽にならないよう diagnostic に typed code を出す)。
- **composer 接続**: `DefaultOrganizationInputComposer.composeInternal` への optional source 追加 (`ProductionOrganizationInputComposer` が wiring 点)。読んだ snapshot は構築する `OrganizationInput` の `personalization` field へそのまま載せる (Blocking 1)。`dynamicCutIdentity` は **変更しない** — personalization は mandatory cut の外側で composition attempt ごとに 1回だけ読み、source 失敗・churn は personalization field の typed `unavailable` downgrade としてのみ現れる。`NotReady` / `InconsistentPolicyRead` / `MAX_DYNAMIC_ATTEMPTS` は mandatory source 専用のまま (spec: review Blocking 2)。snapshot の identity は `InputProvenance` へ optional 参加する (U-4)。#228 由来の `composeScopeComposedOrganization` path でも同じ optional source が走ってよい (additions と personalization は独立)。
- **launcher-origin hook**: spec の観測単位 contract (dispatch 成功) に従い、既定義 observation point `ActivityContext.logAppLaunch` (同期 dispatch 成功時にのみ呼ばれる) を `LawnchairLauncher` で override して `LauncherOriginLaunchCounterStore` へ接続する (Launcher3 file への直接編集を回避)。**app pair は観測対象外** — `launchAppPair` override の戻り時点では実 dispatch (`launchSplitTasks`) が起きておらず、そこで観測すると contract 違反の過早観測になる (2026-09-14 review Blocking 3。実証は Current evidence 参照)。first delivery では app pair 用の hook を作らない。発火は dispatch 成功後、非同期、例外で launch 自体を失敗させない。taskbar 経路が `logAppLaunch` に合流するかは未検証のため、対象経路の確定を child issue の最初の作業とする。

### Data flow

1. (background) launcher 経由 launch dispatch 成功 → counter store 更新 (count 最小 state + 絶対 day anchor)。観測単位は spec の launcher-origin contract (app pair は対象外)。
2. manual run 開始 → composer は mandatory 入力の stable cut とは独立に、`PersonalizationSignalSnapshotSource.read(request)` を **1回** 呼ぶ。
   - `GRANTED`: 単一の window anchor (read 開始時に1回だけ読む clock) を基準に `UsageStatsManager` 日次集計から 30d/7d foreground bucket・recency・active-days bucket を合成し canonical rows 化 → contentDigest。rank universe は request 集合から独立した決定論的 universe (U-5 で確定)。
   - `NOT_GRANTED` / `UNAVAILABLE` / per-profile 失敗: **system usage 由来 field のみ** `unavailable` として型化し (absence と区別)、当該 section の entry 行を省略して header / per-profile 行の state が意味を運ぶ。**launcher-origin entry は `usageAccess` の state にかかわらず保持される** (Blocking 2)。
3. snapshot を `OrganizationInput.personalization` へ載せ、snapshot identity を provenance へ optional 参加させ、既存 mandatory cut が安定していれば `Ready(input, provenance)`。personalization は `Ready` / `NotReady` の分岐に参加しない。
4. planner / AI adapter は `OrganizationInput.personalization` 経由で snapshot の normalized field のみ消費する (first delivery の既存 strategy は読まない)。placement affinity 系は将来 consumer が既存 `LayoutSnapshot` から pure projection する (snapshot 非搭載、owner review 2026-09-13 recommendation)。

### Identity / determinism

- contentDigest は canonical rows の sort・改行 join → 既存 `sha256Canonical` (`rules/PolicyModels.kt:181`) を再利用。rows は spec の grammar に従い **header 行 (`schemaVersion|usageAccess|launcherOriginAvailability`) + per-profile 行 (`profile|profileAvailability`) + entry 行 (`profile|package|field|state(value/absent/unavailable)|value|source`)** を含む (review Blocking 3 + 2026-09-14 Blocking 2)。`NOT_GRANTED` は **system usage 由来 entry 行の省略** + header state で表現され、query failure (`UNAVAILABLE`) と別 digest になる。launcher-origin entry 行は `LAUNCHER_ORIGIN_AVAILABLE` である限り常に存在する。
- launcher-origin recency 投影は決定論的: 永続された count 最小 state + 絶対 day anchor と、snapshot 構築時の window anchor から pure 関数で recency bucket を計算する。永続されるのは相対 class ではなく anchor であるため、時間経過後の read でも正しい bucket が得られる (2026-09-14 review Required)。day boundary の定義 (timezone 等) は U-5 / U-3 の確定対象。
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
- **snapshot identity だけを provenance に載せ、`OrganizationInput` への実体搭載を将来の usage strategy Issue に defer する (初版 draft)**: 現行の `OrganizationInput` / `Ready(input, provenance)` には snapshot を運ぶ欄がなく、identity だけが残って実体が planner / AI adapter に届かず、spec の Outcome を満たさない (2026-09-14 review Blocking 1)。#203 内で non-null field を追加へ変更。
- **`usageAccess != GRANTED` で entries をすべて空にする (初版 draft)**: system Usage Access を拒否しただけで launcher-origin signal も消失し、launcher-origin の permission-independent 性が失われる (2026-09-14 review Blocking 2)。source ごとの省略規則へ変更。
- **app pair を `LawnchairLauncher.launchAppPair` override の戻り時点で観測する (初版 draft)**: 実 dispatch は `findLastActiveTasksAndRunCallback` の非同期 callback 内 `launchSplitTasks` で行われるため、戻り時点の観測は「dispatch 成功」より早い過早観測になる (2026-09-14 review Blocking 3)。first delivery では app pair を対象外とする。実 dispatch point への seam は Launcher3 bridge を伴うため別 accepted decision で再検討。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | snapshot 型・source interface・bucket 計算 (pure) | planner から Android 型を隔離 |
| `organizer/planning/OrganizationInput.kt` | `personalization: PersonalizationSignalSnapshot` (non-null) field 追加、既存 constructor caller の更新 | snapshot 実体を planner seam へ届ける唯一の経路 (2026-09-14 review Blocking 1)。既存 strategy は読まないため挙動不変 |
| `organizer/integration/` | `AndroidUsageSignalSnapshotSource`、`ProductionOrganizationInputComposer` wiring、composer への optional single-read 接続と `OrganizationInput` への snapshot 搭載、`InputProvenance` の optional 参加枠 | 既存 composition seam の唯一の拡張点。`dynamicCutIdentity` は変更しない |
| `organizer/rules/PolicyModels.kt` | `PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT` 追加 | identity の kind が必要 (#228 が closed set 追加の許容を示済み) |
| Launcher 側観測点 | `LawnchairLauncher` での `logAppLaunch` override から counter store への接続 (app pair は対象外のため hook 作らない) | fork 所有 class で完結できれば Launcher3 編集ゼロ。やむを得ず Launcher3 file を触る場合は最小 bridge + Issue 番号コメント (AGENTS.md) |
| `res/values[, -ja]/` | opt-in 導線・rationale 文言 (U-2) | 権限追加は spec + risk 評価が必要 (AGENTS.md)。本plan は app-op ベースであり新 runtime permission ではないことを明記 |
| `docs/product/requirements.md` | FR-013 traceability 更新 | AC-9 |
| Diagnostics | `usageAccessState` typed code と snapshot identity のみ追加許可 | privacy 契約。organizer-diagnostics.md の許可欄更新は同 PR |
| ADR-0007 | optional source の provenance 参加と「usage 変化は plan を stale にしない」semantics の追記 | ADR §6 の protocol 適用範囲が mandatory source であることを明文化。実装 PR で同時更新 |

実装は child issue 分割を推奨: (1) snapshot 型+pure source+identity test、(2) Android adapter+permission state、(3) composer/provenance 接続 + `OrganizationInput.personalization` field 追加 (constructor caller の機械的更新を含む)、(4) launcher-origin counter、(5) permission UI/文言、(6) diagnostics/requirements 更新。段階 (3) では DESIGN.md §9 図/module row と ADR-0007 追記を同 PR に含める。各段階で Organizer は従来どおり動作しなければならない。

## Migration and recovery

- 新規 app-private state は launcher-origin counter store のみ (**snapshot 自体は永続化しない** — cache も作らない。2026-09-14 review Required)。Launcher DB・recovery store は無変更。
- counter store 破損: 読み取り失敗で `LAUNCHER_ORIGIN` field を `unavailable` 化 (fail-closed)。修復は初期化のみ。
- downgrade: counter store が読む schema は `personalization-signals-v1` 配下の初版のみ。将来の schema 変更は spec 182 の三ケース downgrade model に従う。
- opt-out/revoke: 次回 composition から `NOT_GRANTED` (system usage 由来 field のみ unavailable 化。launcher-origin entry は opt-out 対象と別扱い — counter 自体の停止・消去は U-3 の user-visible 操作)。過去 snapshot の流用なし。layout への影響なし。

## Testing strategy

- **Unit (pure)**: bucket 計算の境界値・決定性 (同一入力→同一 digest)・absence/unavailable/value の三値区別・canonical rows (header / per-profile / entry 行) の sort と grammar。property test: 任意の usage stats 合成に対し digest が入力の全順序に安定。`NOT_GRANTED` と `UNAVAILABLE`、全 field `absent` と全 field `unavailable` が別 digest になること、および **`NOT_GRANTED` 下でも launcher-origin entry 行が保持されること** (AC-2 / AC-13)。launcher-origin recency 投影: 永続した day anchor に対し擬似 clock を進めて recency bucket が正しく再分類されること (例: `<24h` で記録した起動が翌日には `1–7d` に投影される — 2026-09-14 review Required)。
- **Composition**: composer test (`tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt` — main に存在することを再確認済み。#228 の `ScopeComposedPlannerTest` / `ApplyProtocolCandidateAvailabilityTest` も composer 拡張の test 先例) 拡張。source がある/ない、`NOT_GRANTED` でも `Ready`、source が失敗/例外しても `Ready` で personalization field のみ `unavailable` (AC-11)、**personalization 由来の `NotReady` / `InconsistentPolicyRead` が存在しないこと**、`Ready.input.personalization` がすべての run mode で non-null であり同一入力から同一 identity が得られること (AC-12)、既存 planner / strategy の test が field 追加前後で無変更に通ること (AC-12 の挙動不変担保)、provenance identity への optional 参加、mandatory `dynamicCutIdentity` の值が personalization 有無で変わらないこと (AC-10)。
- **Integration/instrumentation**: 実 `UsageStatsManager` は instrumentation で permission granted/denied/unavailable の3状態。実 returns の interval 境界・粒度を実測し U-5 の確定入力とする。work profile は emulated profile で per-profile 挙動 (U-6 解消後)。launcher-origin observation は dispatch 成功 / 同期失敗 (`ActivityNotFoundException`) の両方を駆動し、失敗時に counter が進まないことを確認。**app pair 起動では counter が進まないこと** を観測する (AC-14 — hook が存在しないことの回帰担保)。
- **Device**: permission opt-in→run→revoke→run の物理端末 evidence。bucket が端末再起動を跨いでも合成可能なことの代表確認。deep shortcut 起動の observation 記録と、app pair 起動が記録対象外であることの代表確認 (AC-14)。
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

## Explicitly unverified areas

- `UsageStatsManager.queryUsageStats` の実 returns 値・集計粒度・interval 境界の挙動は現行 main の code からは確認できない (instrumentation で実測し、U-5 の rank universe / window semantics 確定の入力とする)。
- profile ごとの usage query 可否 (work profile で `createUserContext` 相当が必要か) は未検証 (#129 の慣行、#228 の `userForProfile` serial bind 慣行を踏襲する予定)。
- taskbar 独自 dispatch 経路が `ActivityContext.logAppLaunch` に合流するか未検証。`logAppLaunch` override が spec の観測単位 contract の対象経路 (app pair を除く) をすべて覆うかは、child issue (4) の最初に launch path を列挙して確認する。
- launcher-origin counter の絶対 day anchor の day boundary 定義 (timezone / DST 境界で epoch-day 相当をどう切るか) は未確定 — U-5 / U-3 の確定入力として instrumentation または実機で確認する。
- `LawnchairLauncher` での `logAppLaunch` override が Quickstep 側の起動 (hero / predicted 委譲等) をすべて通るかは未検証 (上項と同じく実装時に列挙確認)。
- install age の `firstInstallTime` / `lastUpdateTime` の実機挙動 (update 後の値) は未検証。本specでは first delivery から defer 済みのため実装への影響はない。
- #204 exchange contract の具体的な消費形式 (本snapshot をそのまま渡すか projection するか) は #204 側の未決定事項 (#204 spec は未accept)。#203 の snapshot 確定が #204 受入の前提の一つである。
