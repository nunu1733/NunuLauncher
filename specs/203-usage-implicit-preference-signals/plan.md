# Implementation Plan: versioned local usage / implicit-preference signal snapshot

> Issue: #203
> Spec: [spec.md](./spec.md)
> Status: draft — spec の Unresolved decisions (U-1〜U-6) が解消されるまで実装を開始しない。
> 本plan は初回作成時 (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) の調査を基に、2026-09-12 の re-entry check で `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4` までの差分を再検証・追記したものである。

## Current evidence

`origin/main` で確認した事実 (推測と分離)。確認日 2026-09-12 (main = `f9afd8bfde`)。

- Planner 入力: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`
  - `OrganizationInput(snapshot, rules, taxonomy, signals, targets, runMode)`。`ClassificationSignals` は分類専用 (`SignalSource` S1〜S6、`CategoryId` 候補のみ)。usage/frequency/recency を運ぶ欄は存在しない。
  - `RunMode` は #228 の実装により `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` の3値。personalization snapshot は新規 run mode を追加せず既存 modes に optional input として参加する。
- Composition seam: `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  - `OrganizationInputComposer` は #228 実装後2 entry point (`composeFullOrganization()` / `composeScopeComposedOrganization(selection)`) を持ち、両方とも `composeInternal(selection)` に委譲する。personalization snapshot の読み取りは `composeInternal` の stable cut 内への optional 追加になる。
  - `DefaultOrganizationInputComposer` は canonical capture → bundle → (selection / overrides / platform evidence を前後2回読む) stable cut → signals/targets materialize → `OrganizationInputComposition.Ready(input, InputProvenance)`。
  - `InputProvenance` (`CompositionModels.kt`) は `revision + rules + taxonomy + signals + targets + policyBundle + layoutStrategySelection` (7 field、baseline から変更なし — 再確認済み)。`dynamicCutIdentity(bundle, overrides, evidence, selection)` が dynamic input の generation+digest を cut へ束ね、不一致時 `MAX_DYNAMIC_ATTEMPTS(2)` retry → `NotReady(InconsistentPolicyRead)`。
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
- Launcher launch path: `src/com/android/launcher3/touch/ItemClickHandler.java` が click→launch の bridge 候補。fork baseline (`505dbc40`) 以降この file への fork 側 commit は 0 件 (2026-09-12 `git log 505dbc40..origin/main -- <file>` で確認) — upstream のままの最小 bridge でよい。
- 既存 spec 整合: spec 83 は usage signal を明示的に non-goal としており、本plan は composition seam を拡張する新規 input の追加として位置づける。spec 182 は bundle identity と dynamic input identity の分割 (selection snapshot 慣行) を確立済み (status: implemented)。spec 228 も implemented であり、usage なしで成立することを実装で実証済み。
- 依存 Issue: #204 (exchange contract。draft snapshot は branch `issue-204-spec-plan` のみで未accept。`usageSignals` を optional とし詳細を本Issueに委ねている)、#205/#206 (draft snapshot のみ、未accept)。#182/#228 は implemented。

## Design

### Modules and interfaces

新規 module `lawnchair/src/app/lawnchair/organizer/personalization/`。DESIGN.md §9 の target source layout 図は `planning/`〜`ui/` の7 package を列挙するが、「package数をこの図に合わせること自体を目的にしない。interfaceを深く保ち、変更のlocalityが高まる分割だけを採用する」を明記しており、usage signal 専用の純粋domain + adapter 群を1 package にまとめる分割はこれに適合する。ただし新規 top-level organizer package の追加は DESIGN.md に見える構造決定であるため、実装PRで §9 の図と DESIGN.md module row を同時更新すること (docs update は spec 受入後の実装PRが担当)。代替として snapshot 型を `planning/` に置く案もあるが、分類用 `ClassificationSignals` と個人化 signal の混在を避けるため専用 package を推奨する。

```kotlin
// domain (pure, Android-free)
interface PersonalizationSignalSnapshotSource {
    fun read(requests: List<UsageSignalRequest>): PersonalizationSignalReadResult
}
sealed interface PersonalizationSignalReadResult {
    data class Ready(val snapshot: PersonalizationSignalSnapshot) : PersonalizationSignalReadResult
    // NOT_GRANTED は失敗ではなく「NOT_GRANTED snapshot」として Ready で返す想定。
    // ここも U-4/U-6 の解消後に固定する。
}
data class PersonalizationSignalSnapshot(/* spec の契約: schemaVersion, generation,
    contentDigest, capturedAtClass, usageAccess, entries, sources */)

// integration (Android)
class AndroidUsageSignalSnapshotSource(
    appContext: Context,
    /* UsageStatsManager / AppOpsManager / LauncherApps access; per-profile binding は
       AndroidClassificationSignalSnapshotSource と同じ UserCache serial 慣行 */
) : PersonalizationSignalSnapshotSource
class LauncherOriginLaunchCounterStore(/* app-private 永続化; count class / recency class のみ */)
```

- planner 側は本Issueでは変更しない (usage-based strategy は #182 child 形式の別Issue)。`OrganizationInput` への個人化欄の追加方法 (新 field vs 将来 strategy 用の拡張) は U-4 解消後に確定し、それまでは composition/provenance と snapshot 取得までを本Issueの縦切りとする。
- usage access state の読み取り (`AppOpsManager` / `PACKAGE_USAGE_STATS` check) は integration 側に隠す。domain は `UsageAccessState` のみ。

### Seams

- **読み取り seam**: `PersonalizationSignalSnapshotSource` (production: `AndroidUsageSignalSnapshotSource`、test: in-memory fake)。呼び出し側は composer。planner/AI adapter は直接呼ばない。
- **composer 接続**: `DefaultOrganizationInputComposer.composeInternal` への optional source 追加 (`ProductionOrganizationInputComposer` が wiring 点)。必須5入力と異なり absence は `NotReady` にしない (D-010)。代わりに snapshot の identity を provenance へ載せる (U-4)。`dynamicCutIdentity` へ generation+digest を追加し、cut 不安定は既存 retry/`InconsistentPolicyRead` に乗せる。#228 由来の `composeScopeComposedOrganization` path でも同じ optional source が走ってよい (additions と personalization は独立)。
- **launcher-origin hook**: `ItemClickHandler` (またはその Kotlin 呼び出し先) から `LauncherOriginLaunchCounterStore` への最小 bridge。発火は launch 成功後、非同期、例外で launch を失敗させない。

### Data flow

1. (background) launcher 経由 launch 成功 → counter store 更新 (count class / last-launch class)。
2. manual run 開始 → composer の stable cut 内で `PersonalizationSignalSnapshotSource.read(requests)` を2回 (A/E/B 読み)。
   - `GRANTED`: `UsageStatsManager` 日次集計から 30d/7d foreground bucket・recency・active-days bucket を合成し canonical rows 化 → contentDigest。
   - `NOT_GRANTED` / `UNAVAILABLE`: 対応 field を `unavailable` として型化 (absence と区別)。
   - placementAffinity は同じ cut の `LayoutSnapshot` から投影 (planner 重複入力に注意、U-1)。
3. snapshot identity が provenance/dynamic cut に参加。cut 安定なら `Ready`。
4. planner/AI adapter は snapshot の normalized field のみ消費。

### Identity / determinism

- contentDigest は canonical rows (`profile|package|field|value|source` を sort・改行 join → `sha256Canonical`)。既存 `sha256Canonical` (`rules/`) を再利用。
- bucket 計算は usage stats の raw 値のみから決定論的に行う。now 依存の境界 (「今日から30日」) は capturedAtClass の coarse 境界に固定し、同一 capture 内では単一の時点参照を使う (U-5)。
- generation は store 侧 monotonic。同一内容なら digest 同一、generation 不同 — cut は generation も含める (override store と同じ)。

### Alternatives rejected

- **planner が usage API を直接読む**: determinism/purity (P-09)・testability・privacy すべてで却下。
- **`ClassificationSignals` (S1〜S6) を拡張して usage を載せる**: 分類契約 (spec 10/12) と個人化入力の関心事が異なり、provenance 也不能分離。別 snapshot とした。
- **raw UsageEvents の保存による高精度化**: 保持期間が端末依存で不安定、privacy cost 高。bucket 合成で十分 (spec の候補比較)。
- **usage access を required permission にする**: D-010 違反。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | snapshot 型・source interface・bucket 計算 (pure) | planner から Android 型を隔離 |
| `organizer/integration/` | `AndroidUsageSignalSnapshotSource`、`ProductionOrganizationInputComposer` wiring、composer への optional 接続、provenance/dynamic-cut 拡張 | 既存 composition seam の唯一の拡張点 |
| `organizer/rules/PolicyModels.kt` | `PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT` 追加 (U-4 で確定) | identity の kind が必要 |
| Launcher3 bridge (1箇所) | `ItemClickHandler` 由来の launch 観測 hook | launcher-origin signal の最小 bridge。AGENTS.md に従い Issue 番号を近傍コメントに残す |
| `res/values[, -ja]/` | opt-in 導線・rationale 文言 (U-2) | 権限追加は spec + risk 評価が必要 (AGENTS.md)。本plan は app-op ベースであり新 runtime permission ではないことを明記 |
| `docs/product/requirements.md` | FR-013 traceability 更新 | AC-9 |
| Diagnostics | `usageAccessState` typed code と snapshot identity のみ追加許可 | privacy 契約。organizer-diagnostics.md の許可欄更新は同 PR |

実装は child issue 分割を推奨: (1) snapshot 型+pure source+identity test、(2) Android adapter+permission state、(3) composer/provenance 接続、(4) launcher-origin counter、(5) permission UI/文言、(6) diagnostics/requirements 更新。段階 (3) では DESIGN.md §9 図/module row の更新を同 PR に含める。各段階で Organizer は従来どおり動作しなければならない。

## Migration and recovery

- 新規 app-private state (launcher-origin counter、必要なら snapshot cache) のみ。Launcher DB・recovery store は無変更。
- counter store 破損: 読み取り失敗で `LAUNCHER_ORIGIN` field を `unavailable` 化 (fail-closed)。修復は初期化のみ。
- downgrade: 新 binary が読む schema は `personalization-signals-v1` のみ。将来の schema 変更は spec 182 の三ケース downgrade model に従う。
- opt-out/revoke: 次回 composition から `NOT_GRANTED`。過去 snapshot の流用なし。layout への影響なし。

## Testing strategy

- **Unit (pure)**: bucket 計算の境界値・決定性 (同一入力→同一 digest)・absence/unavailable/value の三値区別・canonical rows sort。property test: 任意の usage stats 合成に対し digest が入力の全順序に安定。
- **Composition**: composer test (`tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt` — main に存在することを再確認済み。#228 の `ScopeComposedPlannerTest` / `ApplyProtocolCandidateAvailabilityTest` も cut/identity 拡張の test 先例) 拡張。source がある/ない、cut 不安定 (A/B read 間で generation 変化) → retry→`InconsistentPolicyRead`、`NOT_GRANTED` でも `Ready`、provenance identity への参加、bundle identity 不変 (AC-10)。
- **Integration/instrumentation**: 実 `UsageStatsManager` は instrumentation で permission granted/denied/unavailable の3状態。work profile は emulated profile で per-profile 挙動 (U-6 解消後)。
- **Device**: permission opt-in→run→revoke→run の物理端末 evidence。bucket が端末再起動を跨いでも合成可能なことの代表確認。
- **Privacy**: diagnostics journal の出力が typed code + identity のみであることを contract test。purity guard: `organizer/planning/`, `personalization/` (domain) に android import が現れないことの architecture test (AC-6)。
- **UI**: opt-in 導線の TalkBack/Switch Access/font scaling (spec 52/195 準拠)。

## Risks

- usage stats API の集計精度の機種差 (undercount・期間切れ) → bucket 化で緩和、device evidence で代表確認。精度を前提にした strategy は作らない。
- app-op state 読み取りの端末差 → `UNAVAILABLE` typed 化で吸収。
- `UsageStatsManager` は profile 間で query 可能性が異なる → per-profile fail-closed (U-6)。
- composer の provenance/dynamic cut への新規入力追加は既存 composition test 全体に影響し得る (#228 の composer 拡張が既に同影響を実証) → child issue (3) を単独 PR に分離。

## Explicitly unverified areas

- `UsageStatsManager.queryUsageStats` の実 returns 値・集計粒度は現行 main の code からは確認できない (実装時に instrumentation で実測して bucket 設計を確定する必要がある)。
- profile ごとの usage query 可否 (work profile で `createUserContext` 相当が必要か) は未検証 (#129 の慣行、#228 の `userForProfile` serial bind 慣行を踏襲する予定)。
- `ItemClickHandler.java` 自体は fork で未変更であることを確認済みだが、launch 成功の観測点として `ItemClickHandler` 内のどの呼び出し (通常 click / shortcut / folder 内) を hook するのが最小かは実装時の詳細設計で確定する。
- #204 exchange contract の具体的な消費形式 (本snapshot をそのまま渡すか projection するか) は #204 側の未決定事項 (#204 spec は未accept)。#203 の snapshot 確定が #204 受入の前提の一つである。
