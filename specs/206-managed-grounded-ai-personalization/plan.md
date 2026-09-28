# Implementation Plan: Managed Grounded AI personalization

> Issue: #206
> Spec: [spec.md](./spec.md)
> Status: draft — 実装開始条件 (FR-017凍結解除判断、D-011 privacy/threat model承認、Open decisions解消) を満たす前に実装しない。#204受入 (契約blocker) は2026-09-15に達成済み (PR #322 merge済み)。兄弟issue #205 も2026-09-16にaccepted + 実装済み (PR #325) だが、**2026-09-24の再焦点化によりFR-017はFrozen (D-016、spec 443) となり**、外部AI交換は実験的toggle (既定OFF) の背後に移された。spec 443のnon-goalsは#206の実装判断を明示的に残しており、[seed-backlog.md](../../docs/project/seed-backlog.md) (order 15) は着手条件を「FR-017凍結解除判断、D-011」とする。残る実装開始hard blockerは **(1) FR-017凍結解除判断 (保守者のproduct decision)、(2) D-011承認** の2つである。
>
> Risk tier: **H (暫定判断 — 実装review時に確定)**。理由: external transmission・credential保管というprivacy/security表面の新規追加 (NFR-008/D-011 gate、AGENTS.mdの通信・dependency追加規則)、`risk: privacy` label運用により独立エビデンス手続の適用が見込まれる。純粋な変更path基準では本機能は新規DB書込み経路を作らない (既存 #182/#194/#195/#13 seamを再利用) が、上記リスク表面により階層Hの手続 (accepted spec + plan.md + Execution and approval contract) で進める。判定規則の正本は [docs/project/github-workflow.md](../../docs/project/github-workflow.md)「Risk tiers」。

## Current evidence

確認済みの現行状態 (2026-09-28に再検証、`origin/main` @ `c5a7840b88`。前回確認は2026-09-19 `3076bdae7e` 基準)。実装開始時に再検証する。前回確認からの主差分は本節末尾に記載する。

- **#204契約は実装済みかつv2/v3/v4へ拡張済み (PR #322、2026-09-15 accepted。拡張はspec 331/330/337所有)**。純粋package `lawnchair/src/app/lawnchair/organizer/personalization/` に `ContextExportBuilder.kt` / `ContextExportCodec.kt` / `ContextExportModels.kt` (`PrivacyTier`、`ContextExportContract` のcontent limits/TTL 24h定数、fixed 6 capability set。wire schemaは `personalization-context-v4` / `personalized-intent-v4` — v4はspec 337所有: export envelopeの `categories` projection、item-level `categoryRef`/`folderCategoryRef` 一本化、`CONFIDENCE_MIN/MAX` 定数化) / `IntentCodec.kt` / `IntentModels.kt` (`GroupSemantic` はv4で `categoryRef`/`proposalLabel` のexactly-one-of) / `IntentValidator.kt` (typed failure `IntentValidationFailure` 14 class — v2で `SCOPE_MISMATCH`、v4で `UNKNOWN_CATEGORY_REF` 追加) / `IntentCompletion.kt` (v3: validator成功pathの純粋completer。全export refの完全分割 `CompletedPersonalIntent` を構成し、identity計算とplanner投影の唯一の対象。authored部分文書はdiagnostics限定) / `IntentPlannerAdapter.kt` (`PersonalizedIntentProjection`) / `IntentIdentity.kt` (`PolicySourceKind.PERSONALIZED_INTENT` identity + no-intent sentinel。identityはv3ではcompleted表現基準) / `IntentWireContract.kt` (#348: wire fieldのcanonical facts — 型・必須性・enum綴り・上限 — の単一source。`IntentCodec` allow-list、exchange instructionのOutput contract、contract-sync test oracleがここからrenderされる) / `ExportSessionStore.kt` (pure interface。sessionはv2でscope candidate集合 + candidate投影digest、v4で `categoryRefs` (ref→`CategoryIdentity`) mappingを保持。**#417 (2026-09-24) のAmendにより**、sessionはdurableなentry origin `ExportEntryOrigin` (`IDLE`/`RUN_IN`) を作成時に1回記録し、storeはfailure-aware invalidation `invalidateIf` を持つ。schema/validator/framingは不変) / `PendingImportedIntentStore.kt` (#374: 取り込み済みintentのdurable化。exchange側の消費でありmanaged pathは直接使わない) / `CandidateScopeIdentity.kt` (v2) / `SourceContextIdentity.kt` / `RandomIdAllocator.kt` / #203 signal系model。Android実装は `organizer/integration/AndroidExportSessionStore.kt` (file-backed、`noBackupFilesDir`、single-active-session、corruption時はfail-closedでsession不在扱い。record file名は `organizer_personalization_export_session_v2.json` のまま、v4の `categoryRefs` は空defaultのadditive field)。契約testも実装済み (`IntentValidatorTest`, `IntentCodecTest`, `ContextExportBuilderTest`, `IntentPlannerAdapterTest`, `PlannerMobilityBindingTest`, `UnlinkabilityAndIdentityContractTest`, `IntentCompletionTest`, `Issue348AiFacingContractSyncTest` 等)。
- **planner接続は実装済み**: `OrganizationInput` にoptional field `intentPreferences: PersonalizedIntentProjection?` (既存runはnull、既存runのplan/preview/apply挙動は不変)。`PolicySourceKind` は9値へ拡張 (`PERSONALIZATION_SIGNAL_SNAPSHOT` (#203)、`PERSONALIZED_INTENT` (#204)、`USER_DEFINED_CATEGORY_CATALOG` (#336) 追加)。intent preference消費は全planner executorへ接続済み (`PlanningPlacement.kt` / `PlanningResultCanonicalization.kt` 経由、PR #322の `feat(204): connect intent preference consumption across all planner executors`)。消費はordering/preference bias限定で、保持判断 (`determinePreservation`)・constraints・run mode・`TargetSet` 意味論は不変。#331によりcandidate宛preference (`subject: CANDIDATE` のref → `CandidateItem`) の消費も接続済みで、candidateがexport/session/scope binding gateを経由しないintent消費runへ参加する経路は存在しない。#337 (v4) により `groupSemantic` は `FormationKey` (`Existing(CategoryIdentity)` / `Proposed(label)`) としてrun-scopedにfolder形成・namingに効く (`FolderFormation.kt`、`FolderNaming.FromProposalLabel`。既存strategy authority・`determinePreservation` は不変で、ordering keyはproposalを無視)。
- **#203 (usage signals) は実装済み (PR #321、spec accepted)**: `PersonalizationSignalSnapshot` (+`PersonalizationSignalSnapshotSource`)、`AndroidSystemUsageSignalReader` / `AndroidPersonalizationSignalSnapshotSource` / `LauncherOriginLaunchRecorder` / `LauncherOriginSignalReader` / `UsageAccess` (いずれも `organizer/integration/`)。settings surfaceは `HomeScreenPreferences.kt` の `organizer_personalization_section` (`organizerPersonalizationRecording` preference + usage access状態表示)。usage signal不在でも本機能は成立する (optional input、D-010)。
- **AI/network provider codeはorganizerに存在しない。** `organizer/personalization/` 配下にAI provider・network・credential実装は存在しない (2026-09-28に `c5a7840b88` 上で再確認。#205/#329/#331/#332/#327/#328/#372〜#375実装のexchange transports (clipboard/share/file) もnetwork/provider APIを含まない)。AI adapter・credential delivery・opt-in設定はすべて新規実装である。
- **純粋性の規律 (本planの配置制約)**: `tests/unit/app/lawnchair/organizer/planning/PurityGuardTest.kt` は `organizer/planning` と `organizer/personalization` を **`walkTopDown()` (subdirectory含む)** で走査し、`android.` / `androidx.` / `kotlinx.coroutines` / `java.io` / `java.net` / `java.nio` 等のimportを禁止する (2026-09-28に `c5a7840b88` 上で確認。#205実装の `organizer/personalization/exchange/`、#329の `ImportNormalizer`、#331の `ScopeBindingGate`、#374の `PendingImportedIntentStore` pure packageもこの走査対象下にある)。よってmanaged AIのpure契約 (capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、session) は `organizer/personalization/managedai/` に置けるが、**network/SDK/Keystore依存のprovider・credential実装は `organizer/personalization/` 配下に置けない**。#204の前例 (`ExportSessionStore` pure interface + `AndroidExportSessionStore` 実装) に従い、実装側は `organizer/integration/` に置く (#205も `personalization/exchange/` pure + `integration/exchange/` 実装の同じ構成を採っている)。
- **app全体のnetwork前提**: `lawnchair/AndroidManifest.xml` はbaseline Lawnchair由来の `android.permission.INTERNET` を既に保持する。`gradle/libs.versions.toml` にretrofit 3.0.0 / okhttp 5.3.2 (bundle `retrofit`) が存在し、`lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt`、`ui/preferences/data/liveinfo/LiveInformationService.kt`、`bugreport/KatbinService.kt` 等のpreferences系serviceがokhttp系networkingの先例である。よって新規permissionや新規network library追加は必須ではない (provider SDK追加与否はOpen decision)。
- **organizerのno-transport規律**: `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt` (spec 67 AC-67-12) はdiagnostics module (`organizer/diagnostics/`) 配下のnetwork/worker importを禁止する (存在を `c5a7840b88` 上で再確認)。organizer-diagnostics.mdは「organizer diagnosticsは外部transportを持たずdefault off」(NFR-008) が正本。AI diagnostics field追加時はこの正本とtestの更新が必要。managed AI provider実装はdiagnostics module外に置くため本testと競合しないが、secret流出禁止 (AC-5) のscanner testは本test族として新設する。
- **planner seam**: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` が唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。signature不変を `c5a7840b88` 上で再確認。AI intentは直接ここへ入らず、#204実装済みの `IntentPlannerAdapter` → `OrganizationInput.intentPreferences` を経る (adapter自体は既存実装であり、#206が新設するのはprovider呼び出し前後のbounded request部分である)。
- **provenance**: `organizer/rules/PolicyModels.kt` の `PolicySourceKind` (9値) と `organizer/integration/CompositionModels.kt` / `OrganizationInputComposer.kt` が `InputProvenance` / policy input identityを所有。`LAYOUT_STRATEGY_SELECTION` (#182)、`PERSONALIZATION_SIGNAL_SNAPSHOT` (#203)、`PERSONALIZED_INTENT` (#204、no-intent時は `PersonalizedIntentIdentity.noIntentSentinel()` のsentinel identity)、`USER_DEFINED_CATEGORY_CATALOG` (#336、defined empty catalog時は `UserDefinedCategoryCatalogIdentity.emptyCatalogSentinel()`、mandatory dynamic cutに参加)。
- **preview/confirm/apply**: `organizer/application/preview/` (spec 194 `inspectPlan`)、`organizer/ui/` (spec 195 confirmation、`ManualOrganizationRun.kt`、`OrganizationOperationLease.kt` 等)、spec 13 apply/recovery。いずれも実装済みで、AI pathはこれを複製しない。
- **run mode現況**: `organizer/planning/OrganizationInput.kt` の `RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` (#228) の3値 (intentPreferences追加はadditiveであり不変。`c5a7840b88` 上で再確認)。`OrganizationInput` は #336により `catalog: ActiveCategoryCatalog` (no default — 全compositionがcatalog snapshotを明示) を必須fieldとして保持する。
- **#205**: **accepted (2026-09-16) + 実装済み** (PR #325 merge `3df9c7af`、docs PR #326、Issue #205 closed、spec 205 status: implemented、`origin/main` 取り込み済み)。実装は `organizer/personalization/exchange/` (pure: `ExchangeContract` / `ExchangeGenerationGate` / `ExchangeImportPipeline` / `ExchangePackageComposer` / `IntentImportParser` / `SessionExportReconstructor`。#329で `ImportNormalizer`、#331で `ScopeBindingGate` / `CandidateScopeIdentity`、#374/#375でdurable pending intent / rebind系を追加) + `organizer/integration/exchange/` (`ExchangeFlowController` / `ExchangeTransports` / `ExchangeInputAdapter` / `ClipboardImportTransport` / `PendingImportStartupReconcile` 等) + `organizer/ui/exchange/`。network/provider APIを含まないuser-mediated text交換 (clipboard/share/file) であり、#206のprovider API接続とはtransportを共通化しない。exchange framing所有、process recreation後のrun再構築semantics、disclosure順序契約は #205 側が所有する。export entryは2系統であった (spec 331 §1): idle entry (scope = full organization、candidate集合∅) と run内entry (#228 missing-app選択flow内。`ExchangeInputAdapter.composeForExport` の2つのoverloadが同じcanonical composition seamから生成)。intent消費runにはscope binding gate (選択集合とexport scope candidate集合の完全一致 + candidate投影digest照合、不一致は `SCOPE_MISMATCH`) が適用される。#332によりimport入力はclipboard/file-firstの専用surface (`ExchangeFlowUi.kt`、`ExchangeImportSurfaceInstrumentationTest` はCI独立job) になった。#327 (interview-first instruction) と #328 (import成功状態 + `ExchangeImportSummary` + `StrategyWriteArbiter` — exchange flowのimport/CTA gateとstrategy picker gateを単一state machineで直列化) も実装済み。**その後の #417/#443による導線変更**: #417が方法選択面をscope確定後へ移しidle AI相談新規作成入口をRetire、#443が「AIに相談」armを実験的toggle (既定OFF) の背後へ移した (OFF時は方法選択面を経ず「このまま整理」へ、hub status cardは有効な依頼・取り込み済み提案を期限切れまで表示。exchange UI層JVM testはblocking `organizer-unit-tests` gateから除外、test fileは保持)。これらは #206のUI entry統合・scope対象決定 (Open decision 11)・attach/CTA経路設計時の参照点である。
- **2026-09-15 (`397d3fd9`) → 2026-09-16 (`0cf82bc1e6`) のmain差分と本planへの影響**: (1) **#204受入 + 実装 (PR #322)** — 上記のとおり本planの前提を大きく更新 (契約seamが実装済みとなり、暫定扱いだった参照を確定名へ置換。純粋/Android分離の前例とpurity guard範囲も確定)。(2) **#203実装 (PR #321)** — signal snapshot + settings surface実装済み。(3) **#298実装 (PR #319/#320)** — Nova restore reload thread affinity。AI/transport/credential seamと無関係。(4) requirements.mdへFR-017登録、D-011行へFR-017対象明記、ADR-0007 §9 (#203 optional input)、CONTEXT.md (契約用語)、DESIGN.md (module treeへ `personalization/` 行、gate表へ row 12「AI personalization context/intent exchange contract」)、organizer-diagnostics.md (`invariant=` field)。`OrganizationPlanner.plan` signature、`RunMode` (3値)、`NoTransportContractTest` は `0cf82bc1e6` 上で再確認済み。
- **2026-09-16 (`0cf82bc1e6`) → 2026-09-16 (`4f555450bd`) のmain差分と本planへの影響**: **#205受入 + 実装** (上記 #205 bullet)。`ContextExportBuilder` は #205 `SessionExportReconstructor` との再構成parity用内部共通化 (`toExportItemCore`) を得たが、builderの外部seam (`build(ExportInputs, tier, RandomIdAllocator)`) は不変であり、本planのData flow前提への影響はない。requirements.md D-011行の更新 (#205 exchangeはgate外、**#206 in-app provider API接続はgate内**) により、実装開始hard blocker (D-011承認) は維持される。FR-017 statusへspec 205受入が追記された (#206側のrequirements更新要件は不変: D-011承認反映 + 該当PR時のstatus更新)。CONTEXT.mdへ #205用語、DESIGN.mdへgate 13が追加され、#206のdocumentation更新はこれと共存する。`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PolicySourceKind` (8値)、`PurityGuardTest` 走査範囲、`NoTransportContractTest`、organizer配下network import不在は `4f555450bd` 上で再確認済み。
- **2026-09-16 (`4f555450bd`) → 2026-09-17 (`703afe3f4c`) のmain差分と本planへの影響**: (1) **#331実装 (PR #333、spec 331 implemented)** — #204契約v2拡張: `ExportItemSubject` (`PLACED`/`CANDIDATE`)、`Mobility.CANDIDATE`、`ExportSession` のscope candidate集合 + candidate投影digest (`AndroidExportSessionStore` も永続化)、`IntentValidationFailure.ScopeMismatch` (13th class)、`ScopeBindingGate` (runの選択集合とexport scopeの完全一致)、`ExchangeInputAdapter.composeForExport` のrun内entry overload (同一canonical composition seam)。#206への影響: specのUnsupported cases / Stale state節を現行契約へ再anchor、Open decision 11 (managed AIのexport scope対象) 新設。(2) **#330実装 (PR #335、spec 330 implemented)** — #204契約v3拡張: 部分authoring (`itemIntents` と `unresolvedRefs` の互いに素、未言及refはcanonical unresolved)、`IntentCompletion` / `CompletedPersonalIntent` (validator成功pathが全export refの完全分割を構成し、identity計算とplanner投影の唯一の対象)、`INCOMPLETE_COVERAGE` の条件narrow。#206への影響: specのIntended flow / grounding非対応fallback記述をv3実態へ更新。wire schemaは `personalization-context-v3` / `personalized-intent-v3` (型名 `PersonalizationContextExportV1` / `PersonalizedIntentV1` は不変)。(3) **#329実装 (PR #339)** — exchange import normalizer。#206に直接影響なし。(4) **#332実装 (PR #344 + evidence PR #347)** — exchange import入力UI (clipboard/file-first)。managed AI entryのUI統合の参照点が変化 (「Explicitly unverified areas」参照)。(5) **#336実装 (PR #341)** — `PolicySourceKind` 9値化 (`USER_DEFINED_CATEGORY_CATALOG`)、`OrganizationInput.catalog` 必須化、`ExportInputs.resolvedIdentities` 追加、export presentationのuser定義カテゴリ名redaction。#206のmanaged AI export組立はこの拡張済み `ExportInputs` 契約を使う。(6) `ContextExportBuilder.build(ExportInputs, tier, RandomIdAllocator)` 外部seam、`IntentPlannerAdapter.project(ValidatedPersonalizedIntent)` 外部seam、`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PurityGuardTest` 走査範囲、`NoTransportContractTest`、organizer配下network import不在は `703afe3f4c` 上で再確認済み。
- **#337 (category refs & group proposals、v4所有) の現状**: spec **accepted (2026-09-18)**、**contract core実装済み** (PR #355、`feat(337): v4 category refs, run-scoped formation keys and the summary kind split` 等)。残りは spec 337「Implementation progress」記載の AC-9 promotion UX (`UserDefinedCategoryAuthoringCoordinator` 経由)、AC-10 preview側、AC-15 device evidence、AC-16 accessibility evidence (遅延evidence pass)。spec 337のnon-goalsは **本specを「#206 managed AI pathの実装 (単一契約のconsumerとしてv4を利用する)」と明示** しており、#206がv4を単一契約として消費することは両specで整合する。
- **#348 (`IntentWireContract`) の含意**: wire field factsの単一sourceが実在する。`ExchangePackageComposer` はinstructionのOutput contract節をここからrenderし、`IntentCodec` もallow-listをここから得る (`Issue348AiFacingContractSyncTest` がproduction parityを検査)。**managed AI adapterのstructured output schema bindingもこの単一sourceから派生させる** (spec 348が排除した「production validatorと乖離する手書きAI向けschema」のfailure classを #206で再導入しない)。
- **#361 TO-BE UX (accepted) + #362 disposition (accepted) の現状**: `docs/product/organizer-to-be-ux.md` (2026-09-18〜19 owner review完、long-term正本) は Organizer hub IA (D-01)、AI相談の「整理案の作り方」への統合 (D-04)、idle/run-in相談のlease境界 (D-17) をproduct decisionとして確定し、評価表中で **「managed-AI / incremental等の将来capability」の受け皿がhubの方法選択・status cardである** ことを明示する。処分正本 `docs/product/organizer-disposition-migration.md` (#362 accepted) は本件を「**Continue (deferredのまま)**」(hub「方法」が受け皿、D-011 gate・privacy/threat model承認が引き続き前提) と処分し、`LOCAL_FULL` tier UI語彙を #206向けにDefer (D-14) する。**migrationは大部分実行済み** (hub T-01 #366、材料集約 #367、strategy picker T-05移設 #368、run面 #369、usage access JIT #371、#372〜#376、#377 cleanup — implemented。onboarding接続 #370はaccepted、着地状態は実装開始時に再確認)。#206のentry/opt-in UI配置はこの決定と整合させる (spec「UI / accessibility」節)。入力fact baseは #356 AS-IS監査 (`docs/assessment/organizer-as-is-ux-data-flow-audit.md`)。
- **2026-09-17 (`703afe3f4c`) → 2026-09-19 (`3076bdae7e`) のmain差分と本planへの影響**: (1) **#337実装 (PR #354 spec / #355 impl)** — #204契約v4拡張 (上記 #204 bulletのとおり。schema v4、`GroupSemantic` exactly-one-of、`UNKNOWN_CATEGORY_REF` 14th class、session `categoryRefs`、`FormationKey`/`FolderNaming.FromProposalLabel`)。spec側の影響: Failure taxonomy 14 class化、schema version表記、Dependencies表更新。plan側の影響: adapter schema bindingの単一source原則 (#348)、AC-1検証行の14 class更新。(2) **#348実装 (PR #349、AC-11 evidenceのみ後続)** — `IntentWireContract` 追加。planのDesign節へ schema binding単一source原則を追記。(3) **#327実装 (PR #350)** — interview-first instruction。exchange側のみで #206に直接影響なし。(4) **#328実装 (PR #353、spec受入 + 実装 + independent audit込み)** — import成功状態 + `StrategyWriteArbiter` (strategy書込・import・CTAの単一state machine直列化)。managed AIのattach/CTA設計時の参照点が増加 (「Explicitly unverified areas」参照)。(5) **#356監査 (PR #363) + #361 TO-BE UX受入 (PR #364)** — managed AI entryのIA受け皿がproduct decisionとして確定。planのUI配置指針を #361整合へ更新。(6) requirements.md FR-017 status行へ spec 328 参照追加 (D-011行は不変、#206は引き続きgate内)。`ContextExportBuilder.build(ExportInputs, tier, RandomIdAllocator)` 外部seam、`IntentPlannerAdapter.project(ValidatedPersonalizedIntent)` 外部seam、`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PolicySourceKind` (9値)、`PurityGuardTest` 走査範囲、`NoTransportContractTest`、organizer配下network import不在は `3076bdae7e` 上で再確認済み。
- **2026-09-19 (`3076bdae7e`) → 2026-09-28 (`c5a7840b88`) のmain差分と本planへの影響**: (1) **製品再焦点化 (2026-09-24承認、Epic #439 / Issue #440、メモRevision 5)** — **FR-017 Frozen、D-016「AI相談の凍結」新設**、status語彙 `frozen` 追加、FR-008再定義 (Now-1)、FR-018〜023 / NFR-013〜014 / D-013〜016追加。プロダクト中心成果は「ホーム画面の日常的な編集と散らからない状態の維持にかかる手間の削減」(R-1/R-2) へ移り、[seed-backlog.md](../../docs/project/seed-backlog.md) は本件をorder 15 (Now段階の成果条件に入れない独立track、着手条件「FR-017凍結解除判断、D-011」) とした。**実装開始hard blockerが「FR-017凍結解除判断」+「D-011承認」の2つに変化** (spec header・Dependencies表・Execution checklistへ反映)。[spec 443](../../specs/443-freeze-ai-exchange/spec.md) (accepted 2026-09-26、実装PR #467 merge `ea8d57d068`): 「実験的機能: AIに相談」toggle (既定OFF、`ExperimentalFeaturesPreferences` + `exchange_ai_consultation_enabled`)、OFF時の方法選択面スキップ、hub status cardのdurable状態到達性維持、`organizer-unit-tests` gateからの `app.lawnchair.organizer.ui.exchange.*` 除外 (property-gated `excludeTestsMatching`、test file保持)。spec 443 non-goalsは本件の実装判断を明示的に除外。(2) **TO-BE移行の実行** — #366 (hub T-01 + status card)、#367 (材料のhub集約、settings row廃止)、#368 (strategy pickerのT-05移設、run時変更特例廃止)、#369 (run面表示統合・`ManualOrganizationFace.kt` 新設)、#371 (usage access JIT要求 `UsageAccessJitRequest.kt`)、#372 (AI相談request flow T-15)、#373 (import失敗の手段別再投影)、#374 (取り込み済みintent durable化 `PendingImportedIntentStore` + `AndroidPendingImportedIntentStore` + `PendingImportStartupReconcile`)、#375 (scope remedy rebind `RebindIntentRebuilder` / `PendingIntentReconcile` / `ExchangeMutationGate`)、#376 (durable status復元entry `RestorableRecoveryPointSelector`)、#377 (余剰整理) がimplemented。UI統合の参照点を更新 (「Explicitly unverified areas」)。(3) **#417 (scope-first method choice、implemented)** — 方法選択面 (「このまま整理 / AIに相談」) が対象scope確定後に移動、旧T-07前置き面は廃止、idle AI相談新規作成入口はRetire。**#204契約Amend**: `ExportSessionStore` へ `ExportEntryOrigin` (durable entry origin、`IDLE`/`RUN_IN`) と `invalidateIf` (failure-aware invalidation) 追加 (schema/validator/framing不変、wire schemaはv4のまま)。(4) **#444 (risk-tiered workflow)** — risk tiers H/M/L導入 (AGENTS.md + github-workflow.md)。plan冒頭へ暫定tier H判定を記録。実装・契約語彙の定義を `CONTEXT.md` から所有spec/`DESIGN.md` へ移動 (CONTEXT.mdは境界語のみ)。(5) **#445〜#448編集系** — ADR-0013 (直接編集の書込み契約) / ADR-0014 / ADR-0015、`organizer/homeedit/` module (DESIGN.mdへ追加)、AGENTS.md安全規約へADR-0013 carve-out。本件は直接編集ではなく既存 #182/#194/#195/#13 seamを再利用するため影響なし。(6) **#398 (BOTTOM_REGION_V1)** — strategy catalogへ下部領域successor追加。planner seam契約への影響なし。**契約seamは全て不変** (`c5a7840b88` 上で再確認): `ContextExportBuilder.build(ExportInputs, tier, RandomIdAllocator)` 外部seam、`IntentPlannerAdapter.project(ValidatedPersonalizedIntent)` 外部seam、`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PolicySourceKind` (9値)、wire schema (`personalization-context-v4` / `personalized-intent-v4`)、`IntentWireContract.kt` (不変)、`PurityGuardTest` 走査範囲、`NoTransportContractTest`、organizer配下network import不在。
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

- **structured output schema bindingの単一source原則 (#348)**: provider requestに渡すstructured output schema (`personalized-intent-v4` 相当) は、`organizer/personalization/IntentWireContract.kt` (wire fieldの型・必須性・enum綴り・上限の単一source) から派生させる。adapter実装がfield factsを第二の手書きschemaとして再宣言することを禁止し (production validatorとの乖離 = spec 348が排除した初回failure classの再導入)、schema派生の正しさは `Issue348AiFacingContractSyncTest` 同様のcontract-sync test (managed AI用fixtureをdescriptorから導出) で検証する。pure契約側で `IntentWireContract` を参照する場合、そのimportは `organizer/personalization/` 配下同士であり純粋性規律に反しない。

- **Seam原則**: 呼び出し側もtestも同じpublic seam (`ManagedAiProviderAdapter`) を使う。test double adapterで成功・全套失敗を擬似的に起こし、provider実体がなくてもUI・flow・diagnosticsを検証できる。
- **純粋側とAndroid側の分離**: capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、sessionはpure Kotlinとし、provider SDK・okhttp・Keystore依存は `organizer/integration/` の実装側に閉じる。Provider SDK型をinterfaceへ漏らさない (spec「Provider abstraction」)。#204の前例 (`ExportSessionStore` pure seam + `AndroidExportSessionStore` 実装、purity guardが純粋package全体をsubdirectory含めcover) と同じ構成である。
- **UI**: `organizer/ui/` 配下にopt-in設定・credential管理 (UI構成は選定されたdelivery modelに従う)・送信内容確認 (grounding有効時はprovider-side検索query生成の明示を含む)・失敗表示・quality class表示を追加。既存settings経路のconventionに従う (D-012)。entryのIA上の位置はaccepted #361 TO-BE UX (D-01 hub集約、D-04「整理案の作り方」への統合、managed-AIの受け皿 = hub方法選択・status card) と整合させる。**現行IA (#417/#443適用後)**: 方法選択面はscope確定後に現れ、「AIに相談」armは実験的toggle (既定OFF) の背後にある。managed AIのentry構成 (独立arm / exchange toggleとの関係 / 表示条件) はFR-017凍結解除判断とあわせて実装時に設計する。`PrivacyTier.LOCAL_FULL` はexchange UI語彙から除外済みで本件向けにDefer (#362 disposition、D-14)。attach/CTA経路は #328 `StrategyWriteArbiter` の単一state machine直列化 (busy中の新規import・CTA起動拒否) と共存させる。
- **diagnostics**: typed failure category、quality class・grounding provenance等のprivacy-safe metadataのみを既存run journalへ出力する。field追加はorganizer-diagnostics.mdの正本更新と同じPRで行う。API key・raw prompt/responseは対象外。

### Data flow

```text
user明示操作 (opt-in済み + 選定delivery modelのcredential存在)
  -> ContextExportBuilder.build(ExportInputs, tier, RandomIdAllocator) により
     PersonalizationContextExportV1 (wire schema: personalization-context-v4) + ExportSession を生成
     (#204実装済み。pure。ExportInputsは拡張済み契約 — resolvedIdentities (#336) を含む。
      compositionはplannerと同じcanonical seam (OrganizationInputComposer) から得る
      (spec 331 §1の前例)。scope対象はOpen decision 11)
  -> ExportSessionStore.save(session) 成功を確認 (#204実装済み。durable書込失敗時は送信をfail-closed抑制)
  -> ContextExportCodec.encode(export) でwire表現を生成 (#204実装済み。pure)
  -> 送信内容確認UI (privacy disclosure。tier内容とgrounding有効時はprovider-side検索query生成の明示を含む)
     -> user承認
  -> ManagedAiSession: adapter.request(export表現, policy) [bounded 1 request, cancel可]
     (structured output schemaは IntentWireContract 単一sourceから派生)
  -> ManagedAiOutcome
       Success: IntentCodec.decode(bytes) -> IntentValidator.validate(intent, export, session,
                nowEpochMs, SourceContextIdentity再計算値) (#204実装済み。fail-closed zero-write。
                v3部分authoring可 — 未言及refはcanonical unresolved)
                -> ValidatedPersonalizedIntent (content digest付きidentity。identityは
                IntentCompletion が構成する完全表現 CompletedPersonalIntent 基準 — v3)
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

- DB schema変更なし。export sessionのdurable保持は #204実装済み (`AndroidExportSessionStore`: `noBackupFilesDir`、backup対象外、Launcher favorites DBと独立、single-active-session。#417のAmendによりsessionはentry origin `ExportEntryOrigin` を記録) であり、#206が新たに永続化するのはcredential deliveryのstate (direct BYOK選定時はkey store) のみ。既定をbackup対象外とする (Open decision 3で確定)。
- managed AI未opt-in時の挙動は完全に従来どおり。featureはdefault off。
- **凍結下のexchange表面との分離**: #443の凍結中、exchange UI・導線は「データ安全に関わるbug修正のみ」の保証範囲である。#206の実装は凍結対象package (`organizer/ui/exchange/` 等) への機能追加を行わず、managed AI固有のnamespace (`managedai/` 系) に置く。凍結対象testのblocking gate除外 (`app.lawnchair.organizer.ui.exchange.*`) に依存したtest配置を行わない。
- rollback: 機能をdefault offへ戻す (設定) またはrevert。export/session/intentの耐久性・失効semanticsはaccepted #204契約 (実装済み) に従い、#206は独自の永続化契約を追加しない (session TTL 24時間、`SESSION_EXPIRED` / `EXPORT_MISMATCH` / `CONTEXT_STALE` は #204 validatorがfail-closedで分類)。AI失敗は常にzero-writeであり、失敗からの復旧は「何も起きていない」状態である。
- backup/restore: secret (key/token等) の取り扱いをOpen decision 3で確定するまで、backup対象外を維持する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (#204唯一契約) | adapterが #204 codec/validator (v4、completion込) 以外のintent解釈を持たないことのcode review + contract test。#204 validator所有の14 failure class (`SCOPE_MISMATCH` / `UNKNOWN_CATEGORY_REF` 含む) のマッピング経路検証。structured output schemaが `IntentWireContract` 単一sourceから派生していることのcontract-sync検証 | unit test |
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
- [ ] `CONTEXT.md`: 本spec Domain languageの用語のうち **境界語と判断されたもののみ** 追加 (受入時。#444により実装・契約語彙は所有spec Domain languageが正本であり、`CONTEXT.md` は境界語のみを保持する)
- [ ] `DESIGN.md`: §4へmanaged AI module行追加、§11 gate表更新 (受入時。gate表は #205によりrow 13まで存在するため、#206分は新rowとして追加する)
- [ ] `docs/product/requirements.md`: **FR-017は現在Frozen (2026-09-24、D-016)**。実装着手時は凍結解除判断の記録と、FR-017 status (凍結解除/本件への適用) の更新、D-011承認の反映を行う (該当PR時。seed-backlog order 15の着手条件)
- [ ] `docs/engineering/organizer-diagnostics.md`: privacy-safe metadata許容 (最初の実装PR)
- [ ] ADR: BYOK保存機構とcustom endpoint判断は「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」を満たす可能性が高いため、受入時にADR要否を判断する

## Execution checklist / implementation order

1. (前提) **FR-017凍結解除判断** (保守者のproduct decision。D-016/#443/seed-backlog order 15)、**D-011 privacy/threat model承認**、Open decisions 1〜11の解消 (少なくとも 1, 2, 3, 7, 8, 10, 11)。#204受入は達成済み (2026-09-15、PR #322)。
2. child A: pure契約 — capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface + test double全套contract test (AC-2, 3, 7)。
3. child B: credential / auth delivery実装 (Open decision 1で選定されたmodelに従う。direct BYOK選定時はKeystore root of trust + 現行推奨mechanismのkey store。OAuth / short-lived credential選定時はtoken・session lifecycle、relay選定時はrelay認証lifecycle) + opt-in設定 + privacy disclosure UI (AC-5, 6)。
4. child C: 初回provider adapter (structured output)。malformed/injection security test (AC-1, 10一部)。
5. child D: grounding capability統合 + grounding provenance/quality class表示 + 保守fallback・`GROUNDING_ENABLED_UNVERIFIED` 経路 (AC-3, 4)。
6. child E: preview統合、実機evidence、diagnostics正本更新 (AC-8, 9, 10, 11)。
7. 2つ目のproviderは共通adapter contract妥当性確認後に別Issue。

## Dependencies / blockers

- **FR-017凍結解除判断 (hard blocker、2026-09-24新規)**: FR-017 (AI personalization) は再焦点化 (D-016、[spec 443](../../specs/443-freeze-ai-exchange/spec.md)) によりFrozen。spec 443のnon-goalsは本件の実装判断を除外しており、判断は保守者の手で行う ([seed-backlog.md](../../docs/project/seed-backlog.md) order 15、[organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)「Continue (deferredのまま)」)。凍結解除の形式・範囲が確定するまで実装を開始しない。
- **D-011 privacy/threat model承認 (hard blocker、残存)**: 実装開始条件。requirements.md D-011行はFR-017 (本件を含む) がD-011対象である旨を明記済み。threat modelにはcredential delivery model境界とprovider生成検索query boundaryを含める (AC-12)。
- **#204 (resolved)**: accepted (2026-09-15) + 実装済み (PR #322)。さらにv2/v3/v4拡張 (spec 331 PR #333 / spec 330 PR #335 / spec 337 PR #355、いずれもcontract実装済み) と #417のAmend (entry origin + `invalidateIf`) が `origin/main` `c5a7840b88` 上に存在。context/intent schema (v4)、validator (14 failure class)、completion、planner接続adapter、export session store (candidate scope + `categoryRefs` + entry origin 含む)、`IntentWireContract` 単一wire descriptorは全て実装済みであり、#206はこれを契約通りに利用する。#206側での独自schema派生・validator再実装は禁止 (AC-1)。
- **#337 (v4所有者、実装継続中)**: spec accepted (2026-09-18)、contract core実装済み (PR #355)。残り (AC-9 promotion UX、AC-10 preview側、AC-15/16 evidence) はspec 337側のscopeであり #206のblockerではないが、managed AIがv4の `groupSemantic` (categoryRef/proposalLabel) を出力する以上、promotion UXの着地状態を実装開始時に再確認する。
- **#348 (resolved)**: implemented (PR #349、AC-11 evidenceのみ後続pass)。`IntentWireContract` をschema bindingの単一sourceとして利用する (Design節)。
- **#203 (resolved)**: 実装済み (PR #321)。usageSignals projectionはoptional inputであり、signal不在でも成立する。
- **provider API仕様調査** (Open decision 1/10の入力): structured output / grounding API仕様に加え、各providerのauth / credential delivery model (direct BYOKのproduction support可否、client-side key guidance整合) とgrounding metadata (実利用確認可否) の調査を実装前のresearchとして記録する。

## Risks

- network path追加によるNFR-008/D-011違反 — default off、明示opt-in、diagnostics正本更新を必須にする。
- **凍結されたexchange表面への意図しない干渉** — FR-017凍結 (D-016/#443) 下でexchange UI・導線に機能追加を行うと凍結契約 (「データ安全に関わるbug修正のみ」) に違反する。managed AI実装は凍結対象packageを機能拡張目的で変更せず、凍結解除判断の範囲内で作業する (Migration and recovery節)。
- **risk tier誤判定** — #444の階層判定をplan冒頭 (暫定H) と実装PR本文へ記録し、reviewが確認する。`risk: privacy` label運用に従い独立エビデンス手続の適用判断を実装PRで行う。
- provider SDK/型のdomain漏出 — purity guard testで `organizer/personalization/managedai/` (pure契約面) のSDK/network依存を禁止する (provider・credential実装は `organizer/integration/` に置く)。
- credential流出 — secret (key/token等) をlog/diagnostics/bugreportへ出さないscanner test、masked表示 (direct BYOK時)、residual risk文書化。
- **credential delivery model不整合** — 選定providerがdirect mobile BYOKを推奨しない場合にproduction pathとして受入してしまう。Open decision 1の評価基準 (provider公式guidance整合確認) を受入gateに含める。
- **delivery model選定と実装の乖離による不要なBYOK key store実装** — child BはOpen decision 1の選定modelに従って実装し (AC-5の分岐)、direct BYOK以外のmodel選定時には端末内key storeを実装前提にしない。
- **grounding provenanceのinvalid combination成功扱い** — 不変条件違反 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable` 違反) は `UNEXPECTED_GROUNDING` としてtyped zero-write failureに分類し、contract testで網羅する (AC-3)。同意なしの検索実行を成功としない。
- **provider生成検索queryによるprivacy境界** — grounding有効時、Launcherはquery内容を送信前に検証できない。送信確認での明示 (契約) とOpen decision 10 (provider要件 / tier追加制限) で境界を確定するまでgroundingを実装しない。
- **quality classの誤表示** — `groundingUsed` 確認なしに `GROUNDED` を表示・記録する実装をcontract testで禁止する。
- **純粋package境界の侵食** — provider/credential実装を `organizer/personalization/` 配下へ置くとPurityGuardTestが検知するが、network依存を `managedai/` pure契約へ漏らさないようchild A/Cのreviewで純粋/実装境界を確認する。
- AI失敗時の誤った自動retry/fallback — 並行発行禁止・retry禁止をsession契約で固定しtestする。
- **AI向けschemaの第二定義によるproduction validatorとの乖離** — managed AI provider requestのstructured output schemaを手書きで再宣言すると、spec 348がexchange側で排除した「validatorが拒否する形をAIへ教えてしまう」failure classを #206で再導入する。schema派生は `IntentWireContract` 単一sourceから行い、contract-sync test (descriptor導出fixture) で検証する (AC-1)。

## Explicitly unverified areas

- 初回providerのstructured output / grounding API仕様詳細 (未調査。provider選定researchで確認)。
- 各providerのauth / credential delivery model (direct BYOKのproduction support可否、short-lived credential / OAuth / backend relayの要否、provider公式client-side key guidance) (未調査。Open decision 1の評価入力)。
- grounding実行のactual useをprovider responseから確認できるか (tool call記録・grounding metadataの返却形式) (未調査。確認できないproviderでは `GROUNDING_ENABLED_UNVERIFIED` 扱いとなる)。
- credential保存機構の選定とbackup/restore互換性の詳細 (Open decision 3。direct BYOK選定時のみ適用)。
- `ExportInputs` の組み立てに必要なcomposition時入力 (解決済み分類 (#336 `resolvedIdentities` 含む)、user labels、#203 usage key解決) をmanaged AI起動時のrun contextから取得する統合詳細 (#204実装は `ExportInputs` 契約を提供し、#205の `ExchangeInputAdapter` が同一canonical composition seamから組立てる前例を持つが、managed AI側の呼び出し経路は #206実装時に設計する)。
- managed AI pathのexport scope対象 (Open decision 11): runの凍結scope (配置済み全体 + 選択済み未配置candidate) に限定するか、#331と同様のcandidate包含を明示的に許可するか。旧idle起動 (scope = full organization、candidate集合∅) は現行IAには存在しない (#417でidle AI相談新規作成入口はRetire)。いずれの場合もcompositionは #331 §1のcanonical composition path単一化に従う。
- run画面・hubへのmanaged AI entryの統合詳細。現行参照点 (2026-09-28時点): **Organizer hub (T-01、#366) + status card** (#443凍結下も有効な依頼・取り込み済み提案rowを期限切れまで表示)、**方法選択面 (scope確定後、#417。「このまま整理 / AIに相談」arm、「AIに相談」は実験的toggle既定OFF、#443)**、**材料面 (T-05、#367/#368)**、#372のAI相談request face (T-15)、#332のimport入力surface、#328のimport成功状態と `StrategyWriteArbiter` (strategy書込・import・CTA起動の単一state machine直列化)、#374/#375のdurable取り込み済み提案とrebind。accepted #361 TO-BE UX (D-01 hub集約、D-04、managed-AIの受け皿 = hub方法選択・status card) が長期IA正本。managed AI entry (起動条件、表示位置、凍結されたexchange armとの共存・順序、`StrategyWriteArbiter` busy時の拒否扱い) は **FR-017凍結解除判断とあわせて** 実装時に設計する。
- **FR-017凍結解除判断の形式・範囲** (未決定。保守者のproduct decision): 凍結解除・本件への部分適用・継続deferのいずれを採るか、解除時にexchange凍結表面 (toggle・hub row) をどう扱うかは本planの確定対象外である。
- #337の残り実装 (AC-9 promotion UX、AC-10 preview側、AC-15/16 evidence) の着地状態。managed AIがv4 `groupSemantic` (categoryRef/proposalLabel) を出力するため、実装開始時にspec 337のImplementation progressを再確認する。
- #204契約の最終形は未検証項目ではなくなった (accepted + 実装済み、v2〜v4拡張含む)。ただし将来拡張 (`CAPABILITY_UNSUPPORTED` 有効化、subset advertisement、label surrogate、rebase、v4以降のversion進行) は #204/spec 330/331/337側の将来version課題であり、本planの前提外である。
