# Implementation Plan: Managed Grounded AI personalization

> Issue: #206
> Spec: [spec.md](./spec.md)
> Status: draft — 実装開始条件 (D-011 privacy/threat model承認、Open decisions解消) を満たす前に実装しない。#204受入 (契約blocker) は2026-09-15に達成済み (PR #322 merge済み)。兄弟issue #205 も2026-09-16にaccepted + 実装済み (PR #325)。残る実装開始hard blockerは **D-011承認のみ** (requirements.mdのD-011行は #206を明示的にgate内に置いている)。

## Current evidence

確認済みの現行状態 (2026-09-16に再検証、`origin/main` @ `4f555450bd`。前回確認は同日 `0cf82bc1e6` 基準)。実装開始時に再検証する。前回確認からの主差分は本節末尾に記載する。

- **#204契約は実装済み (PR #322、2026-09-15 accepted)**。純粋package `lawnchair/src/app/lawnchair/organizer/personalization/` に `ContextExportBuilder.kt` / `ContextExportCodec.kt` / `ContextExportModels.kt` (`PrivacyTier`、`ContextExportContract` のcontent limits/TTL 24h定数、fixed 6 capability set) / `IntentCodec.kt` / `IntentModels.kt` / `IntentValidator.kt` (typed failure `IntentValidationFailure` 全class) / `IntentPlannerAdapter.kt` (`PersonalizedIntentProjection`) / `IntentIdentity.kt` (`PolicySourceKind.PERSONALIZED_INTENT` identity + no-intent sentinel) / `ExportSessionStore.kt` (pure interface) / `SourceContextIdentity.kt` / `RandomIdAllocator.kt` / #203 signal系model。Android実装は `organizer/integration/AndroidExportSessionStore.kt` (file-backed、`noBackupFilesDir`、single-active-session、corruption時はfail-closedでsession不在扱い)。契約testも実装済み (`IntentValidatorTest`, `IntentCodecTest`, `ContextExportBuilderTest`, `IntentPlannerAdapterTest`, `PlannerMobilityBindingTest`, `UnlinkabilityAndIdentityContractTest` 等)。
- **planner接続は実装済み**: `OrganizationInput` にoptional field `intentPreferences: PersonalizedIntentProjection?` (既存runはnull、既存runのplan/preview/apply挙動は不変)。`PolicySourceKind` は8値へ拡張 (`PERSONALIZATION_SIGNAL_SNAPSHOT` (#203)、`PERSONALIZED_INTENT` (#204) 追加)。intent preference消費は全planner executorへ接続済み (`PlanningPlacement.kt` / `PlanningResultCanonicalization.kt` 経由、PR #322の `feat(204): connect intent preference consumption across all planner executors`)。消費はordering/preference bias限定で、保持判断 (`determinePreservation`)・constraints・`TargetSet.additions` (user明示選択のみ) は不変。
- **#203 (usage signals) は実装済み (PR #321、spec accepted)**: `PersonalizationSignalSnapshot` (+`PersonalizationSignalSnapshotSource`)、`AndroidSystemUsageSignalReader` / `AndroidPersonalizationSignalSnapshotSource` / `LauncherOriginLaunchRecorder` / `LauncherOriginSignalReader` / `UsageAccess` (いずれも `organizer/integration/`)。settings surfaceは `HomeScreenPreferences.kt` の `organizer_personalization_section` (`organizerPersonalizationRecording` preference + usage access状態表示)。usage signal不在でも本機能は成立する (optional input、D-010)。
- **AI/network provider codeはorganizerに存在しない。** `organizer/personalization/` 配下にAI provider・network・credential実装は存在しない (2026-09-16に `4f555450bd` 上で再確認。#205実装のexchange transports (clipboard/share/file) もnetwork/provider APIを含まない)。AI adapter・credential delivery・opt-in設定はすべて新規実装である。
- **純粋性の規律 (本planの配置制約)**: `tests/unit/app/lawnchair/organizer/planning/PurityGuardTest.kt` は `organizer/planning` と `organizer/personalization` を **`walkTopDown()` (subdirectory含む)** で走査し、`android.` / `androidx.` / `kotlinx.coroutines` / `java.io` / `java.net` / `java.nio` 等のimportを禁止する (2026-09-16に `4f555450bd` 上で確認。#205実装の `organizer/personalization/exchange/` pure packageもこの走査対象下にある)。よってmanaged AIのpure契約 (capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、session) は `organizer/personalization/managedai/` に置けるが、**network/SDK/Keystore依存のprovider・credential実装は `organizer/personalization/` 配下に置けない**。#204の前例 (`ExportSessionStore` pure interface + `AndroidExportSessionStore` 実装) に従い、実装側は `organizer/integration/` に置く (#205も `personalization/exchange/` pure + `integration/exchange/` 実装の同じ構成を採っている)。
- **app全体のnetwork前提**: `lawnchair/AndroidManifest.xml` はbaseline Lawnchair由来の `android.permission.INTERNET` を既に保持する。`gradle/libs.versions.toml` にretrofit 3.0.0 / okhttp 5.3.2 (bundle `retrofit`) が存在し、`lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt`、`ui/preferences/data/liveinfo/LiveInformationService.kt`、`bugreport/KatbinService.kt` 等のpreferences系serviceがokhttp系networkingの先例である。よって新規permissionや新規network library追加は必須ではない (provider SDK追加与否はOpen decision)。
- **organizerのno-transport規律**: `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt` (spec 67 AC-67-12) はdiagnostics module (`organizer/diagnostics/`) 配下のnetwork/worker importを禁止する (存在を `4f555450bd` 上で再確認)。organizer-diagnostics.mdは「organizer diagnosticsは外部transportを持たずdefault off」(NFR-008) が正本。AI diagnostics field追加時はこの正本とtestの更新が必要。managed AI provider実装はdiagnostics module外に置くため本testと競合しないが、secret流出禁止 (AC-5) のscanner testは本test族として新設する。
- **planner seam**: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` が唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。signature不変を `4f555450bd` 上で再確認。AI intentは直接ここへ入らず、#204実装済みの `IntentPlannerAdapter` → `OrganizationInput.intentPreferences` を経る (adapter自体は既存実装であり、#206が新設するのはprovider呼び出し前後のbounded request部分である)。
- **provenance**: `organizer/rules/PolicyModels.kt` の `PolicySourceKind` (8値) と `organizer/integration/CompositionModels.kt` / `OrganizationInputComposer.kt` が `InputProvenance` / policy input identityを所有。第5 `LAYOUT_STRATEGY_SELECTION` (#182)、第6 `PERSONALIZATION_SIGNAL_SNAPSHOT` (#203)、第7 `PERSONALIZED_INTENT` (#204、no-intent時は `PersonalizedIntentIdentity.noIntentSentinel()` のsentinel identity)。
- **preview/confirm/apply**: `organizer/application/preview/` (spec 194 `inspectPlan`)、`organizer/ui/` (spec 195 confirmation、`ManualOrganizationRun.kt`、`OrganizationOperationLease.kt` 等)、spec 13 apply/recovery。いずれも実装済みで、AI pathはこれを複製しない。
- **run mode現況**: `organizer/planning/OrganizationInput.kt` の `RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` (#228) の3値 (intentPreferences追加はadditiveであり不変)。
- **#205**: **accepted (2026-09-16) + 実装済み** (PR #325 merge `3df9c7af`、docs PR #326、Issue #205 closed、spec 205 status: implemented、`origin/main` 取り込み済み)。実装は `organizer/personalization/exchange/` (pure: `ExchangeContract` / `ExchangeGenerationGate` / `ExchangeImportPipeline` / `ExchangePackageComposer` / `IntentImportParser` / `SessionExportReconstructor`) + `organizer/integration/exchange/` (`ExchangeFlowController` / `ExchangeTransports` / module類) + `organizer/ui/exchange/`。network/provider APIを含まないuser-mediated text交換 (clipboard/share/file) であり、#206のprovider API接続とはtransportを共通化しない。exchange framing所有、process recreation後のrun再構築semantics、disclosure順序契約は #205 側が所有する。exchange entryはrun画面のstrategy picker下、run operationがidle/cancelledの間のみ表示される (spec 205 V1 rule。#206のUI entry統合時の参照点)。
- **2026-09-15 (`397d3fd9`) → 2026-09-16 (`0cf82bc1e6`) のmain差分と本planへの影響**: (1) **#204受入 + 実装 (PR #322)** — 上記のとおり本planの前提を大きく更新 (契約seamが実装済みとなり、暫定扱いだった参照を確定名へ置換。純粋/Android分離の前例とpurity guard範囲も確定)。(2) **#203実装 (PR #321)** — signal snapshot + settings surface実装済み。(3) **#298実装 (PR #319/#320)** — Nova restore reload thread affinity。AI/transport/credential seamと無関係。(4) requirements.mdへFR-017登録、D-011行へFR-017対象明記、ADR-0007 §9 (#203 optional input)、CONTEXT.md (契約用語)、DESIGN.md (module treeへ `personalization/` 行、gate表へ row 12「AI personalization context/intent exchange contract」)、organizer-diagnostics.md (`invariant=` field)。`OrganizationPlanner.plan` signature、`RunMode` (3値)、`NoTransportContractTest` は `0cf82bc1e6` 上で再確認済み。
- **2026-09-16 (`0cf82bc1e6`) → 2026-09-16 (`4f555450bd`) のmain差分と本planへの影響**: **#205受入 + 実装** (上記 #205 bullet)。`ContextExportBuilder` は #205 `SessionExportReconstructor` との再構成parity用内部共通化 (`toExportItemCore`) を得たが、builderの外部seam (`build(ExportInputs, tier, RandomIdAllocator)`) は不変であり、本planのData flow前提への影響はない。requirements.md D-011行の更新 (#205 exchangeはgate外、**#206 in-app provider API接続はgate内**) により、実装開始hard blocker (D-011承認) は維持される。FR-017 statusへspec 205受入が追記された (#206側のrequirements更新要件は不変: D-011承認反映 + 該当PR時のstatus更新)。CONTEXT.mdへ #205用語、DESIGN.mdへgate 13が追加され、#206のdocumentation更新はこれと共存する。`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PolicySourceKind` (8値)、`PurityGuardTest` 走査範囲、`NoTransportContractTest`、organizer配下network import不在は `4f555450bd` 上で再確認済み。
- 推測 (未確認): provider APIのstructured output / grounding optionの現行仕様詳細、各providerのauth / credential delivery model (direct BYOKのproduction support可否、short-lived credential / OAuth / backend relayの要否)、grounding metadata (実際のtool call / search実行をresponseから確認できるか) の返却形式。実装時にprovider選定とともに調査し、調査記録をIssueへ残す。

## Design

### Modules and interfaces

#204実装の純粋/Android分離前例に従う。pure契約は `organizer/personalization/managedai/`、network・SDK・Keystore依存の実装は `organizer/integration/` に置く (`PurityGuardTest` が `organizer/personalization` をsubdirectory含め走査しnetwork/IO系importを禁止するため。2026-09-16に現行main上で確認済み)。

```text
organizer/personalization/managedai/        # pure (PurityGuardTest走査対象。network/IO/Android禁止)
├── AiProviderCapabilities.kt   # typed capability宣言 (STRUCTURED_OUTPUT, WEB_GROUNDING, ...)
├── ManagedAiRequestPolicy.kt   # timeout / size上限 / grounding許可 (immutable value)
├── ManagedAiGroundingProvenance.kt # groundingAvailable / groundingEnabled / groundingUsed (不変条件検証を型に持たせる。closed state model表現も可)
├── ManagedAiOutcome.kt         # typed sealed outcome: Success(intent表現, grounding provenance) | <Failure taxonomy全套>
├── ManagedAiQualityClass.kt    # ONE_SHOT / GROUNDED / GROUNDING_ENABLED_UNVERIFIED の導出 (provenanceから)
├── ManagedAiProviderAdapter.kt # fun interface: request(context表現, policy) -> ManagedAiOutcome (pure seam。実装は注入)
└── ManagedAiSession.kt         # 1試行の実行単位 (cancel対応、並行発行禁止)

organizer/integration/                       # Android/network側 (#204のAndroidExportSessionStoreと同じ境界)
├── managedai provider adapter実装 (okhttp直 + JSONが第一候補。初回provider 1つ。Open decision 1で選定。delivery model評価を含む)
└── credential / auth delivery実装 (選定されたmodelに従う。direct BYOK選定時はkey store。機構はOpen decision 3)
```

- **quality class導出の規律**: `GROUNDED` はadapter実装がprovider response内のtool call記録・grounding metadataから `groundingUsed=true` を確認できた場合のみ返す。actual useをresponseから確認できないprovider実装は `GROUNDING_ENABLED_UNVERIFIED` を返し、`GROUNDED` を返してはならない (contract testで強制)。provenance 3状態は許容不変条件 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable`) を検証し、違反は `UNEXPECTED_GROUNDING` のtyped failureとする (成功を禁止)。invalid combinationのtable-driven contract testで網羅する。内部表現はboolean 3個でもclosed state modelでもよいが、不変条件検証は必須である。

- **Seam原則**: 呼び出し側もtestも同じpublic seam (`ManagedAiProviderAdapter`) を使う。test double adapterで成功・全套失敗を擬似的に起こし、provider実体がなくてもUI・flow・diagnosticsを検証できる。
- **純粋側とAndroid側の分離**: capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、sessionはpure Kotlinとし、provider SDK・okhttp・Keystore依存は `organizer/integration/` の実装側に閉じる。Provider SDK型をinterfaceへ漏らさない (spec「Provider abstraction」)。#204の前例 (`ExportSessionStore` pure seam + `AndroidExportSessionStore` 実装、purity guardが純粋package全体をsubdirectory含めcover) と同じ構成である。
- **UI**: `organizer/ui/` 配下にopt-in設定・credential管理 (UI構成は選定されたdelivery modelに従う)・送信内容確認 (grounding有効時はprovider-side検索query生成の明示を含む)・失敗表示・quality class表示を追加。既存settings経路のconventionに従う (D-012)。
- **diagnostics**: typed failure category、quality class・grounding provenance等のprivacy-safe metadataのみを既存run journalへ出力する。field追加はorganizer-diagnostics.mdの正本更新と同じPRで行う。API key・raw prompt/responseは対象外。

### Data flow

```text
user明示操作 (opt-in済み + 選定delivery modelのcredential存在)
  -> ContextExportBuilder.build(ExportInputs, tier, RandomIdAllocator) により
     PersonalizationContextExportV1 + ExportSession を生成 (#204実装済み。pure)
  -> ExportSessionStore.save(session) 成功を確認 (#204実装済み。durable書込失敗時は送信をfail-closed抑制)
  -> ContextExportCodec.encode(export) でwire表現を生成 (#204実装済み。pure)
  -> 送信内容確認UI (privacy disclosure。tier内容とgrounding有効時はprovider-side検索query生成の明示を含む)
     -> user承認
  -> ManagedAiSession: adapter.request(export表現, policy) [bounded 1 request, cancel可]
  -> ManagedAiOutcome
       Success: IntentCodec.decode(bytes) -> IntentValidator.validate(intent, export, session,
                nowEpochMs, SourceContextIdentity再計算値) (#204実装済み。fail-closed zero-write)
                -> ValidatedPersonalizedIntent (content digest付きidentity)
                -> IntentPlannerAdapter.project(validated) -> OrganizationInput.intentPreferences
                -> OrganizationPlanner.plan (既存seam)
                -> spec 194 preview -> spec 195 confirm -> spec 13 apply
       Failure: typed failure UI (zero-write)。deterministic継続の明示的選択肢のみ提示
```

- intent受領後はAI要素はなく、以降は全て既存pathである。
- 並行発行禁止: `ManagedAiSession`は同時に1試行のみ。cancel後の遅延結果は破棄する。

### Alternatives rejected

- **アプリ内agent framework / tool registry**: 複雑性・保守性・セキュリティで過剰 (Issue本文の必須設計)。採用しない。
- **provider SDKをdomainへ直接入れ**: SDK型がplanner/rulesへ流出し移行性を損なう。adapterの背後に隠す。
- **#205とtransportを共通化**: Issue本文が同一機能のtransport違いとして扱わないことを明示。#204 intent契約以降のみ共通。
- **AI失敗時の自動fallback (別providerへ再送、one-shotへdowngrade)**: silent semantic downgrade禁止に反する。typed failure + user選択のみ。
- **Jetpack Security Crypto (`EncryptedSharedPreferences`/`MasterKey`) をcredential保存の既定とする**: libraryがdeprecatedのため (出典: Android developer cryptography guidance、確認日2026-09-13)。Keystoreをroot of trustとした現行推奨mechanismの比較 (Open decision 3) に置き替える。
- **direct BYOKを全provider共通の標準production pathとする前提**: providerによってはclient-side長期keyの直接利用を推奨しない場合がある。delivery modelはprovider毎に評価し (spec「BYOK / credential management」、Open decision 1)、非対応providerでdirect BYOKを標準扱いしない。
- **provider直接座標生成**: #204契約が禁止。planner/allocatorが最終安全配置を所有する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/managedai/` (新規、pure) | capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、session | 本機能本体の契約面。PurityGuardTest走査下で純粋性を強制 |
| `organizer/integration/` (既存packageへ追加) | provider adapter実装 1つ (Open decision 1の選定結果。delivery model評価を含む) とcredential / auth delivery実装 (選定modelに従う。direct BYOK選定時はkey store。保存機構はOpen decision 3: Keystore root of trust + 現行推奨mechanism比較) | network/SDK/Keystore依存は純粋package外へ (#204のAndroidExportSessionStore前例と同じ境界) |
| `organizer/ui/` + settings | opt-in、credential管理 (選定されたdelivery modelに従うUI構成)、送信内容確認 (tier内容表示。grounding時はquery生成明示を含む)、失敗UI、quality class表示 | AC-3, 5, 6, 7のUI面。#203のsettings surface (`organizer_personalization_section`) と同一preferences経路に追加 |
| `docs/engineering/organizer-diagnostics.md` | privacy-safe metadata field追加 (受入時に確定) | 正本更新 |
| `tests/unit/.../managedai/` + secret流出scanner (新規) | adapter contract test (test double)、全套failure、malformed/injection fixture、purity guard (managedai/直下のSDK依存禁止) | AC-2, 7, 10 |
| `specs/206-.../spec.md`, `plan.md` | status更新、Open decisions解消の記録 | 正本管理 |

network/permission/dependency変更: 予定制約として、新規permission追加なし (既存 `INTERNET`)、provider SDK追加なしが第一候補 (okhttp直 + JSON)。いずれかを変更する場合はspec受入後、risk評価を伴う別判断である。

## Migration and recovery

- DB schema変更なし。export sessionのdurable保持は #204実装済み (`AndroidExportSessionStore`: `noBackupFilesDir`、backup対象外、Launcher favorites DBと独立、single-active-session) であり、#206が新たに永続化するのはcredential deliveryのstate (direct BYOK選定時はkey store) のみ。既定をbackup対象外とする (Open decision 3で確定)。
- managed AI未opt-in時の挙動は完全に従来どおり。featureはdefault off。
- rollback: 機能をdefault offへ戻す (設定) またはrevert。export/session/intentの耐久性・失効semanticsはaccepted #204契約 (実装済み) に従い、#206は独自の永続化契約を追加しない (session TTL 24時間、`SESSION_EXPIRED` / `EXPORT_MISMATCH` / `CONTEXT_STALE` は #204 validatorがfail-closedで分類)。AI失敗は常にzero-writeであり、失敗からの復旧は「何も起きていない」状態である。
- backup/restore: secret (key/token等) の取り扱いをOpen decision 3で確定するまで、backup対象外を維持する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (#204唯一契約) | adapterが #204 codec/validator以外のintent解釈を持たないことのcode review + contract test | unit test |
| AC-2 (bounded request) | adapter interfaceが1 request/response単位であることの契約test、多段orchestration API不存在check | unit test |
| AC-3 (capability typed) | capability modelのenum表現、grounding provenanceからのquality class導出 (`groundingUsed` 確認時のみ `GROUNDED`、確認不能時 `GROUNDING_ENABLED_UNVERIFIED`) のtest、不変条件違反combination (例: `groundingUsed=true` かつ `groundingEnabled=false`) が成功にならず `UNEXPECTED_GROUNDING` に分類されるtable-driven test、UI表示のUI test | unit + UI test |
| AC-4 (grounding/保守fallback) | grounding有効/無効のtest doubleでunresolved戻りを検証、grounding metadata不在時の `GROUNDING_ENABLED_UNVERIFIED` 経路を検証 | unit test |
| AC-5 (credential delivery lifecycle) | 選定delivery modelのlifecycle test (direct BYOK時: 入力/masked表示/削除)、log/diagnostics/bugreportにsecret (key/token) が出ないsecurity test | unit test + NoTransportContractTest族的scanner |
| AC-6 (opt-in/disclosure) | default off回帰test、送信確認UI test (TalkBack含む)。grounding有効時はprovider-side検索query生成・外部検索の明示文面を含むことの検証 | UI test |
| AC-7 (typed zero-write) | Failure taxonomy全套のtable-driven test。failure時に入力不変の検証 | unit test |
| AC-8 (immutable intent) | preview中に再requestされないことのtest、再実行で別identity | unit test |
| AC-9 (既存preview/apply再利用) | AI専用preview path不存在check、既存preview/apply testの無修正通過 | review + unit test |
| AC-10 (contract + security + device) | test double全套、malformed/injection fixture、実機representative evidence | unit test + 実機 |
| AC-11 (deterministic回帰) | 既存offline run suiteの無修正通過 | `./gradlew test` |
| AC-12 (threat model承認) | 承認済みthreat model文書 (BYOK residual risk、credential delivery model境界、provider生成検索query boundary、endpoint boundary、送信data分類を含む) | review |

- **高リスク分類**: network/privacy関連のため `risk: privacy` を付与。planner/provenance統合を含むPRは `risk: layout-data` 払いとし、high-risk gate (CI `final-status` + `docs/assessment/pr-<n>-*.md`) を満たす。

## Documentation updates

- [ ] spec status/history (受入時)
- [ ] `CONTEXT.md`: 「Managed Grounded AI path」「bounded provider request」「provider capability」「BYOK」「quality class」「grounding provenance」追加 (受入時。#204/#203/#205の契約用語は反映済みであり、#206固有用語のみ追加)
- [ ] `DESIGN.md`: §4へmanaged AI module行追加、§11 gate表更新 (受入時。gate表は #205によりrow 13まで存在するため、#206分は新rowとして追加する)
- [ ] `docs/product/requirements.md`: FR-017は登録済み (受入PR #322。statusはspec 204 + spec 205受入を参照済み)。残る更新はD-011 statusの承認反映とFR-017のstatus更新 (該当PR時)
- [ ] `docs/engineering/organizer-diagnostics.md`: privacy-safe metadata許容 (最初の実装PR)
- [ ] ADR: BYOK保存機構とcustom endpoint判断は「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」を満たす可能性が高いため、受入時にADR要否を判断する

## Execution checklist / implementation order

1. (前提) D-011 privacy/threat model承認、Open decisions 1〜10の解消 (少なくとも 1, 2, 3, 7, 8, 10)。#204受入は達成済み (2026-09-15、PR #322)。
2. child A: pure契約 — capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface + test double全套contract test (AC-2, 3, 7)。
3. child B: credential / auth delivery実装 (Open decision 1で選定されたmodelに従う。direct BYOK選定時はKeystore root of trust + 現行推奨mechanismのkey store。OAuth / short-lived credential選定時はtoken・session lifecycle、relay選定時はrelay認証lifecycle) + opt-in設定 + privacy disclosure UI (AC-5, 6)。
4. child C: 初回provider adapter (structured output)。malformed/injection security test (AC-1, 10一部)。
5. child D: grounding capability統合 + grounding provenance/quality class表示 + 保守fallback・`GROUNDING_ENABLED_UNVERIFIED` 経路 (AC-3, 4)。
6. child E: preview統合、実機evidence、diagnostics正本更新 (AC-8, 9, 10, 11)。
7. 2つ目のproviderは共通adapter contract妥当性確認後に別Issue。

## Dependencies / blockers

- **D-011 privacy/threat model承認 (hard blocker、残存)**: 実装開始条件。requirements.md D-011行はFR-017 (本件を含む) がD-011対象である旨を明記済み。threat modelにはcredential delivery model境界とprovider生成検索query boundaryを含める (AC-12)。
- **#204 (resolved)**: accepted (2026-09-15) + 実装済み (PR #322、`origin/main` `4f555450bd` 上に存在)。context/intent schema、validator、planner接続adapter、export session storeは全て実装済みであり、#206はこれを契約通りに利用する。#206側での独自schema派生・validator再実装は禁止 (AC-1)。
- **#203 (resolved)**: 実装済み (PR #321)。usageSignals projectionはoptional inputであり、signal不在でも成立する。
- **provider API仕様調査** (Open decision 1/10の入力): structured output / grounding API仕様に加え、各providerのauth / credential delivery model (direct BYOKのproduction support可否、client-side key guidance整合) とgrounding metadata (実利用確認可否) の調査を実装前のresearchとして記録する。

## Risks

- network path追加によるNFR-008/D-011違反 — default off、明示opt-in、diagnostics正本更新を必須にする。
- provider SDK/型のdomain漏出 — purity guard testで `organizer/personalization/managedai/` (pure契約面) のSDK/network依存を禁止する (provider・credential実装は `organizer/integration/` に置く)。
- credential流出 — secret (key/token等) をlog/diagnostics/bugreportへ出さないscanner test、masked表示 (direct BYOK時)、residual risk文書化。
- **credential delivery model不整合** — 選定providerがdirect mobile BYOKを推奨しない場合にproduction pathとして受入してしまう。Open decision 1の評価基準 (provider公式guidance整合確認) を受入gateに含める。
- **delivery model選定と実装の乖離による不要なBYOK key store実装** — child BはOpen decision 1の選定modelに従って実装し (AC-5の分岐)、direct BYOK以外のmodel選定時には端末内key storeを実装前提にしない。
- **grounding provenanceのinvalid combination成功扱い** — 不変条件違反 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable` 違反) は `UNEXPECTED_GROUNDING` としてtyped zero-write failureに分類し、contract testで網羅する (AC-3)。同意なしの検索実行を成功としない。
- **provider生成検索queryによるprivacy境界** — grounding有効時、Launcherはquery内容を送信前に検証できない。送信確認での明示 (契約) とOpen decision 10 (provider要件 / tier追加制限) で境界を確定するまでgroundingを実装しない。
- **quality classの誤表示** — `groundingUsed` 確認なしに `GROUNDED` を表示・記録する実装をcontract testで禁止する。
- **純粋package境界の侵食** — provider/credential実装を `organizer/personalization/` 配下へ置くとPurityGuardTestが検知するが、network依存を `managedai/` pure契約へ漏らさないようchild A/Cのreviewで純粋/実装境界を確認する。
- AI失敗時の誤った自動retry/fallback — 並行発行禁止・retry禁止をsession契約で固定しtestする。

## Explicitly unverified areas

- 初回providerのstructured output / grounding API仕様詳細 (未調査。provider選定researchで確認)。
- 各providerのauth / credential delivery model (direct BYOKのproduction support可否、short-lived credential / OAuth / backend relayの要否、provider公式client-side key guidance) (未調査。Open decision 1の評価入力)。
- grounding実行のactual useをprovider responseから確認できるか (tool call記録・grounding metadataの返却形式) (未調査。確認できないproviderでは `GROUNDING_ENABLED_UNVERIFIED` 扱いとなる)。
- credential保存機構の選定とbackup/restore互換性の詳細 (Open decision 3。direct BYOK選定時のみ適用)。
- `ExportInputs` の組み立てに必要なcomposition時入力 (解決済み分類、user labels、#203 usage key解決) をmanaged AI起動時のrun contextから取得する統合詳細 (#204実装は `ExportInputs` 契約を提供するが、UI/run flowからの呼び出し経路は #206実装時に設計する)。
- run画面へのmanaged AI entryの統合詳細。#205実装によりexchange entryがstrategy picker下・idle/cancelled時のみ表示 (spec 205 V1 rule) となっており、managed AI entry (起動条件、表示位置、exchange entryとの順序) は実装時に #205実装と整合する形で設計する。
- #204契約の最終形は未検証項目ではなくなった (accepted + 実装済み)。ただしV1 schemaの将来拡張 (`CAPABILITY_UNSUPPORTED` 有効化、subset advertisement、label surrogate、rebase) は #204側の将来version課題であり、本planの前提外である。
