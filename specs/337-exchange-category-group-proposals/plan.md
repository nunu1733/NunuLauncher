---
issue: "#337"
status: draft
updated: 2026-09-19
---

# Plan: AI personalizationにおけるユーザー定義カテゴリの参照と新規グループ提案

> 本planはspec.md (**accepted**、PR #354 merge commit `de77e280b7`) の実装計画である。baseline: `34ba8ff447` (2026-09-18時点 `origin/main`、#328 / PR #353 merge後)。1st revision planからの変更点は「1st revisionからの主な変更」節に記録する。
>
> **Re-entry (2026-09-19)**: spec受理後、Phase2 contract coreが [PR #355](https://github.com/nunu1733/NunuLauncher/pull/355) (merge commit `b728ed4d9f`、独立監査 [docs/assessment/pr-355-issue337-category-refs.md](../../docs/assessment/pr-355-issue337-category-refs.md)) でmainへ着地した。本planを現行 `main` (`3076bdae7ebf`) へ再anchorし、着地済み範囲と残件を「Re-entry後の着地状態と残件」節に記録した。Ownerは [Issue #337](https://github.com/nunu1733/NunuLauncher/issues/337) コメント (2026-09-18) で #362 disposition ([PR #378](https://github.com/nunu1733/NunuLauncher/pull/378)、Open・本revision時点で未merge。`docs/product/organizer-disposition-migration.md` §3.18) に基づく本Issueの扱いを **Continue (契約不変)** と記録しており、spec 337の契約改訂は不要である。

## Current implementation (調査済み事実、`34ba8ff447` — v3実装の実装前調査。歴史的記録として保持。着地後の状態は次節)

### 契約・検証面 (pure module、`organizer/personalization/`)

- `ContextExportModels.kt` — `ContextExportContract` (v3 constants、content limits、`CONFIDENCE_MIN/MAX`)、`PrivacyTier`、`FreeTextClass` (現状 `APP_LABEL` のみ)、`ExportItem` (`category: String?` / `groupSemantic: String?`)、`PersonalizationContextExportV1` (envelope。`categories` 相当のfieldなし)、`ExportSession` (`itemRefs` / `sourceContextDigest` / `scopeCandidates` / `scopeCandidateDigest`)。
- `ContextExportBuilder.kt` — 純粋builder。`CategoryIdentity?.exportPresentationValue()` が唯一のcategory redaction点 (built-in→raw値、user-defined→`null`)。`ExportInputs` は `snapshot` / `targets` / `resolvedIdentities` / `userLabels` / `signals` / `usageKeysByItem` / `nowEpochMs` を受け、**catalogは直接受けない** (resolved identityはcomposer出力の `signals.entries` から派生。`ExchangeInputAdapter.composeFrom` が `resolvedIdentities` を組み立てる)。
- `ContextExportCodec.kt` — envelope / itemのJSON codec。decodeは `schemaVersion` 一致を要求 (不一致は `SchemaMismatch`)。
- `SessionExportReconstructor.kt` (`personalization/exchange/`) — session + 現行 `CanonicalStructuralInputs` からvalidation viewを再構築。label / usageは非authorityとして省略。category fieldは `toExportItemCore` 経由でredaction規則を共有 (spec 205 parity)。
- `IntentModels.kt` — `GroupSemantic(category: String?, freeText: String?)` (any-of、freeText ≤100字は型上は空文字も許容)。`category` はschema上validateされない自由文字列。
- `IntentCodec.kt` — `ALLOWED_SEMANTIC_KEYS` は `IntentWireContract.groupSemantic` 由来。`decodeGroupSemantic` は両field欠如 (`{}`) を `SchemaMismatch`、100字超を `Oversize` として返す (既存frontier)。
- `IntentValidator.kt` — 検証順序: expiry → export一致 → ref解決 (item / desiredGroup / unresolved) → duplicate → coverage (v3 disjoint) → `pageAffinity` 範囲 → mobility → structural digest → completion。**category値の検証は存在しない**。
- `IntentCompletion.kt` / `IntentIdentity.kt` — completed表現 (3 decision状態) とidentity digest。`groupSemantic` は `category` / `freeText` をそのままidentity rowに含む (spec 330 D-5規律: identityはsemantic内容のみの関数)。
- `IntentWireContract.kt` — descriptor (`FieldSpec` + `ConstraintClaim`、`PRODUCTION_ENFORCED` / `AUTHORING_POLICY`) と `policySentence`。`groupSemantic.anyOf` claim (`category` to `freeText`) は `PRODUCTION_ENFORCED`。
- `IntentPlannerAdapter.kt` — `Authored` decisionのみを `ItemPreference` (ItemIdentity、role、importance、desiredGroup、`groupSemantic: GroupSemantic`、affinity、preserve) へ投影。**`groupSemantic` は文字列のままplannerへ渡る**。
- `exchange/ExchangeImportPipeline.kt` — `ExchangeImportFailure` 3系統 (Envelope 4 / Normalization 2 / Contract 13 = UI 19種)。

### Planner面 (`organizer/planning/`)

- `OrganizationInput.kt` — `catalog: ActiveCategoryCatalog` (必須field、#336)。`taxonomy` (`allowedCategories`)。`intentPreferences` (第7policy input)。
- `CategoryIdentity.kt` — `BuiltIn` / `UserDefined`、`UserCategoryId` (UUID v4 regex検証)、`ActiveCategoryCatalog` (`allowedIdentities` / `contains` / `displayNameOf`)、canonical順序 (built-in byte順 → user-defined stable ID byte順)。
- `FolderFormation.kt` — `formFolderGroups`: `(profile, CategoryIdentity)` でグルーピングし、`fallbackCategory` のgroupを除外、`minGroupSize` / capacityでpartition。`FolderCandidate(item, profile, category)`。
- `FullRunExecution.kt`:
  - dispatchは `strategy.unitOrder` で決まる (`CANONICAL_TIE_BREAK` → `executeCanonicalPageCompact`、`CAPTURED_VISUAL_GLOBAL` → `executeGlobalCompact`、page-local系 → `executePageLocalLiftThenPlace`)。
  - `executeCanonicalPageCompact` (L735付近) のみが `effectiveCategory(itemId)` = intent `groupSemantic.category` がtaxonomyに一致すれば `BuiltIn`、非一致は黙ってclassification decisionへフォールバック、を使い、formation候補と `FullUnit.sortCategory` (CANONICAL_TIE_BREAKのtie-break) の両方に適用する。
  - `executeGlobalCompact` (L581付近) のformationは **classificationのみ** (`context.classification.decisions[...]`) でintent semanticsを見ない (現行挙動、v4でも不変)。
  - `intentComponentRanks` (L37) — `{self} ∪ desiredGroup` の推移閉包componentにcanonicalなrankを振る (ordering用)。`groupRank` はCANONICAL orderingの第1キー。
- `LayoutStrategyRegistry.kt` — `createsFolders = true`: `CANONICAL_PAGE_COMPACT_V1` / `BOTTOM_FIRST_V1/V2` (unitOrder `CANONICAL_TIE_BREAK`)、`GLOBAL_COMPACT_V1/V2` (`CAPTURED_VISUAL_GLOBAL`)。`STABLE_PAGE_TIDY_V1/V2` / `CATEGORY_CONTIGUOUS_V1` は `createsFolders = false`。
- `PlanningResult.kt` — `FolderNaming.FromCategory(CategoryId)` / `FromUserCategory(UserCategoryId)`、`folderNamingFor(CategoryIdentity)`、`NewFolder`。`FolderTitleResolver` (`application/public/`) は非blank契約 + generic fallback、`withCompositionCatalog` が `FromUserCategory` をcatalog snapshotで解決する。
- `ui/GeneratedFolderTitles.kt` — `FolderNaming` のexhaustive `when` (title解決)。`application/public/FolderTitleResolver.kt` は `else ->` 分岐あり。
- `PlanningPlacement.kt` — candidate tail (`appendCandidatePlacements`) はclassification基準でformation (intent semanticsを見ない、現行挙動)。

### Rule Management面 (`organizer/rules/`)

- `UserDefinedCategoryStore.kt` — AtomicFile store (generation + digest、capacity 64、typed failures: `InvalidName` / `DuplicateName` / `UnknownId` / `CapacityExceeded` / `StoreUnreadable` / `UnsupportedSchema` / `Conflict` / `WriteFailed` / `VerificationFailed`)。`UserDefinedCategoryNameRules` (`normalize` = trim + NFC、`isValid` = 非空 / ≤50 code points / `|` なし / 改行なし、`MAX_CODE_POINTS = 50`)。
- composition側のcatalog読み失敗は #336 により `InputCompositionCode` のtyped NotReady (exchange生成は `ExchangeInputResult.NotReady` として既存経路で受ける)。

### Integration / UI面

- `integration/exchange/ExchangeInputAdapter.kt` — export生成とimport検証の単一canonical seam (`composeFullOrganization` / `composeScopeComposedOrganization` → `ExportInputs`)。`resolvedIdentities = resolvedIdentitiesOf(input.signals.entries.map { it.item to it.candidate })`。
- `integration/exchange/ExchangeFlowController.kt` — generate / cancelDisclosure / importReply。import成立後はfresh run再構築。
- `integration/AndroidExportSessionStore.kt` — `SESSION_FILE_NAME = "organizer_personalization_export_session_v2.json"`、`SCHEMA_VERSION = 2`、strict `Json` (unknown keyはdecode失敗 → `runCatching` でnull = fail-closed)、`schemaVersion` 不一致はnull。
- `ui/exchange/ExchangeFlowUi.kt` — import surfaceとfailure copy (`exchangeContractFailureText` のexhaustive `when`、strings en/ja)。`personalization/exchange/ExchangeImportSummary.kt` (#328) — count-onlyのprivacy形状保証。
- `ui/UserDefinedCategoryAuthoring.kt` (`UserDefinedCategoryAuthoringCoordinator.create(displayName)`) — #336 authoring path (lease検査 + typed failures)。
- `ui/StrategyWriteArbiter.kt` (#328) — strategy書込とimportの相互排他。本Issueは新しいwriterを追加しないため変更不要。

## 1st revisionからの主な変更 (plan)

- Interface 3〜4 (item field) をref一本化へ変更 (`categoryRef` / `folderCategoryRef`、`categories` projection)。
- Interface 8〜9 (adapter / planner) を「解決済みidentity + formation key (Existing / Proposed)」へ変更し、`FolderNaming.FromProposalLabel` を追加。
- Interface 9 (validator) は追加classを `UNKNOWN_CATEGORY_REF` の1つに限定し、digest gateの実位置 (pipeline段でreconstruction前) をplan / flow / testへ明記。
- Interface 10 (AI-facing) はcanonical templateを変更しない方針へ変更。
- Testing strategyの「prompt-like文を拒否するsecurity oracle」を削除し、値域test + allow-list testへ置換。
- 失敗classは 14 contract / 20 UI (`UNKNOWN_CATEGORY_REF` のみ追加。1st revisionの 15 / 21 から `CONFLICTING_GROUP_SEMANTIC` を削除)。

## Re-entry後の着地状態と残件 (2026-09-19、current main `3076bdae7ebf` で実コード確認済み)

Phase2第1弾 (PR #355) でplanの **Phase 1〜4と、Phase 5のsummary model側が着地済み**。下記は現在のコード/treeでの確認結果である (実装は着手済みのため、本節が残作業の正となる。作業状態の正本は [Issue #337](https://github.com/nunu1733/NunuLauncher/issues/337) コメント (2026-09-18、PR #355 merge報告) とその後の記録である)。

### 着地済み (PR #355、検証済み)

- **契約 (Phase 1〜2)**: `ContextExportContract` の `SCHEMA_VERSION = "personalization-context-v4"` / `INTENT_SCHEMA_VERSION = "personalized-intent-v4"`、envelope `categories` (ref + `CategoryRefKind` + built-in `taxonomyId` + tier制御付き user-defined `displayName`)、`ExportItem.categoryRef` / `folderCategoryRef` へのref一本化、session `categoryRefs` (additive、record version 2維持)、`GroupSemantic(categoryRef, proposalLabel)` exactly-one-of (#336 name規則の値域)、validator `UNKNOWN_CATEGORY_REF` 解決 (D-5の決定表どおり pipeline段digest gate → validator段ref解決)。
- **Planner (Phase 3)**: `FormationKey.Existing/Proposed` によるfolder形成 (`FolderFormation`)、`FolderNaming.FromProposalLabel`、ordering keyはproposalを消費しない (v3 `freeText` parity)。
- **AI-facing (Phase 4)**: descriptor v4 (`exactlyOneOf`、`proposalLabel.lengthLimit`、`refScope.groupSemanticCategoryRef`、`policy.categoryRefFromContext`)、instructionのYou-must、canonical template不変。
- **Summary model (AC-10のmodel側)**: `ExchangeImportSummary` が `categoryKindByRef` により `builtInCategoryCount` / `userCategoryCount` / `proposedGroupCount` を区別。
- **Oracle (着地済み分)**: AC-4 formation key matrix (`IntentPreferenceConsumptionTest` — proposal folder形成、`GLOBAL_COMPACT` 不変性、非connected同一label統合、異label分岐)、AC-6 store/session不変 (**import + plan段まで**: `ExchangeImportPipelineTest.validatedProposalImportLeavesTheCategoryStoreUntouched` が実store byte不変までpin、`validatedImportNeverWritesTheDurableSessionRecord`)、AC-1 encode時256 KiB Oversize (`ContextExportCodecTest.oversizeExportsAreFailClosedAtEncodeTime`。ただし `categories = emptyList()` 経由、下記残件)。

### 残件 (Issue exit条件。後続PRで対応 — 現在のコードに存在しないことを確認済み)

1. **AC-9 promotion UX**: `ui/exchange/ExchangeFlowUi.kt` にpromotion actionは未存在 (「run-scoped proposal, which nothing saves」のcommentのみ)。`UserDefinedCategoryAuthoringCoordinator.create` への接続 (lease busy・`DuplicateName` / `CapacityExceeded` 等のtyped表示、AI専用writerを作らないことのtest) とstrings (en/ja)。plan「Interfaces / seams」14のpromotion部分。
2. **AC-10 UI完全化**: summary modelのkind countはあるが、UI (`ExchangeFlowUi.kt` のbreakdown) は `builtInCategoryCount + userCategoryCount` を1行「existing category」に合算しており、「既存built-in / 永続user-defined / run-scoped proposal」の3種区別が未完了。**着手は [Issue #373](https://github.com/nunu1733/NunuLauncher/issues/373) (OPEN、T-17/T-18取り込みUI再構成) の表示構造と整合させること** (#362 dispositionより。TO-BE [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md) T-18 = 取り込み結果面へ集約)。
3. **AC-6 apply段oracle**: import・plan段はpin済み。残りは既存transactional apply harness経由でproposal由来planのapply時にcatalog / `CategoryOverrideStore` 不変 (snapshot equality / write count = 0) をassertする。
4. **AC-4 candidate tail負oracle**: `appendCandidatePlacements` がproposal有無でcandidate placementを変えないことの専用testが未存在 (planner側matrixはcanonical-family / global-compactのみ)。
5. **AC-1 / AC-2 補助oracle**: (a) category refのcross-export unlinkability専用test (`UnlinkabilityAndIdentityContractTest` にcategory refケースなし — 同一状態の2回exportでcategory ref集合が共有されないこと)、(b) `categories` を含む文書での256 KiB容量 / `Oversize` 経路 (現行Oversize testは `categories = emptyList()`)、(c) malformed `categories` (未知kind・未advertise item ref・redacted tierの `displayName`) のdecode → `SchemaMismatch` fixture。
6. **AC-15 / AC-16**: representative external agent flowのdevice evidenceとaccessibility evidence (後続evidence pass。spec 327 AC-7/AC-8と同一扱い)。
7. **AC-14残り (docs)**: 実装PR済み分 (spec 204のgroupSemantic定義・content limits行・v4 history行、spec 205のData and state・7th history行、spec 336の投影規律改訂・AC-14・status注記、spec 348のexactly-one-of / cross-field行・5th history行) に対する残り:
   - **spec 348のv3残留行** (Production truth inventory等の現状記述がv4実装と不整合): :34「失敗分類 (19種)」、:48 non-goal「schema version bump (v3のまま…)」、:76 「`personalization-context-v3` / `personalized-intent-v3`」、:80 「`freeText` ≤100字…」行、:175・:192・:204 の「19種」記述。それぞれv4 (`personalization-context-v4` / `personalized-intent-v4`、`proposalLabel` ≤50 code points、UI失敗20種) への現状更新または時点注記が必要。
   - **change historyのv4行の時系列/採番**: spec 204 (昇順historyの中に2026-09-18行が2026-09-16行より前に挿入)、spec 205 / 348 (新行を先頭に追加し、各fileの既定順序と不整合)。各fileの規約に揃える。
   - **`ExchangeImportPipeline.kt` KDoc**: :197付近の「thirteen #204 contract classes」→ fourteen (contract 14 class)。

### 依存の現状更新

- [Issue #373](https://github.com/nunu1733/NunuLauncher/issues/373) (OPEN): T-17/T-18の取り込みUI再構成の所有者。AC-9 / AC-10のUI残件はこれと整合して着手する (#362 disposition (PR #378、本revision時点で未merge) のIssue #337コメント 2026-09-18: 「表示面はTO-BEのT-18へ集約されるため、#373の表示構造と整合して着手するのが自然」)。
- #361 TO-BE UX決定 (PR #364、accepted): [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md) :361 がspec 205 / 328 / 331 / 332 / 337への影響を「Amend (影響評価後)」に分類。本spec 337の契約は現時点ではAmend不要 (#362 disposition: Continue) だが、#373系実装時にT-18面との契約差分の影響評価が発生しうる。
- #356 AS-IS audit (PR #363): 現状UI/data flowの監査記録。AC-9/AC-10 UI着手時の現状把握の入力。

## Ownership / module boundaries

- 契約・検証・identityの変更はすべて `organizer/personalization/` (pure) に閉じる。Android型・DB rowをpure seamへ入れない (AGENTS.md規約)。
- catalog読みは既存composition seam (`OrganizationInputComposer` → `OrganizationInput.catalog`) からのみ行う。builderがstoreを直接読まない (単一cut保証)。
- label値域の正本は #336 の `UserDefinedCategoryNameRules` 1箇所とし、intent codecはそれを再利用する (規則の二重定義を作らない)。
- category永続化のwriterは既存 `UserDefinedCategoryAuthoringCoordinator` のみ。exchange側に新writerを生やさない。
- session recordの進化は `ExportSessionStore` seamの内側 (実装 `AndroidExportSessionStore`) で行う。
- planner側の変更は formation key と naming のみ。strategy権限 (`createsFolders` / `eligibleUnitFilter` / `unitOrder` / `pageScope` / `cellTraversal`) は変更しない。

## Interfaces / seams (変更点)

1. **`ContextExportContract`**: `SCHEMA_VERSION = "personalization-context-v4"`、`INTENT_SCHEMA_VERSION = "personalized-intent-v4"` (D-8)。`MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS` は廃止し、label上限は #336 name規則 (50 code points) を参照する。
2. **envelope**: `PersonalizationContextExportV1.categories: List<ExportCategory>` 追加。`ExportCategory(ref, kind: CategoryRefKind, taxonomyId: String?, displayName: ExportCategoryName?)`、`CategoryRefKind.BUILT_IN/USER_DEFINED`、`ExportCategoryName(freeTextClass: FreeTextClass, value: String)`、`FreeTextClass.USER_CATEGORY_NAME` 追加。envelope不変条件: ref一意、`kind` と `taxonomyId`/`displayName` の存在規則、redacted tierでuser-defined `displayName` なし。
3. **`ExportItem`**: `category: String?` → `categoryRef: String?`、`groupSemantic: String?` → `folderCategoryRef: String?` (D-1/D-3)。initで「非null refはenvelopeのadvertised集合に属する」はbuilder側不変条件 + codec round-tripで固定する (model単体は集合を知らないため)。
4. **`ContextExportBuilder`**: catalog projection生成 (canonical identity順、ref割当、3-namespace一意性検査)、item / candidate / folder semanticのref投影。`ExportInputs` へcatalog (または identity→ref解決に必要な入力) を追加する — 追加位置は既存 `ExportInputs` の流儀に合わせ、Android型を入れない。`exportPresentationValue()` は廃止またはref投影専用へ置き換える。
5. **`ContextExportCodec`**: `categories` encode/decode、item field名変更。
6. **`ExportSession`**: `categoryRefs: Map<String, CategoryIdentity>` 追加。`AndroidExportSessionStore` のrecordへadditive field (default空) を追加 (record version 2 / file名は不変。両方向の挙動をtestでpin)。
7. **`SessionExportReconstructor`**: validation viewの `categories` をsession mapping + 現行catalogから再構築 (advertise済みrefのみ、name値はauthorityにしない)。session mappingにidentityがあるが現行catalogに無い場合は当該refをadvertiseから外す (validatorが `UNKNOWN_CATEGORY_REF` に落とす)。
8. **`GroupSemantic`**: `category: String?` → `categoryRef: String?`、`freeText: String?` → `proposalLabel: String?`、initでexactly-one-of + 値域 (normalize / isValid)。codec / descriptor / completion / identity / adapterを一括改訂。
9. **`IntentValidator`**: item ref解決の後にcategory ref解決 (`UNKNOWN_CATEGORY_REF`) を追加し、`IntentValidationFailure` へ1 class追加。cross-item整合規則は追加しない (semantic帰属単位 = formation key)。検証順序はspec D-5 (pipeline段: session → expiry → digest照合 → reconstruction → validator / validator段: item ref → category ref → duplicate → coverage → pageAffinity → mobility → digest再確認 → completion) に固定する。**digest照合はpipeline段でreconstruction・validationより先**にsettleするため、割当ありdeleteは `CONTEXT_STALE`、割当なしdeleteは `UNKNOWN_CATEGORY_REF` になる (実装変更は不要)。
10. **`IntentPlannerAdapter` / `ItemPreference`**: `groupSemantic: GroupSemantic` を「解決済み `groupCategory: CategoryIdentity?` + `groupProposalLabel: String?`」へ分離。raw文字列をplannerへ渡さない。identity計算はauthored内容 (`categoryRef` / `proposalLabel`) のまま (catalog内容に依存させない)。
11. **planner formation**: `FolderFormation.kt` の keyを `FormationKey = Existing(CategoryIdentity) | Proposed(label)` へ一般化 (全順序: Existing先 → Proposed (label UTF-8 byte順))。`folderNamingFor` は `Existing` → 既存variant、`Proposed` → 新 `FolderNaming.FromProposalLabel(label)`。`FullRunExecution.executeCanonicalPageCompact` の `effectiveCategory` を「formation key解決」 + 「ordering用 `sortCategory` (Proposedはclassification identity)」へ置換。`executeGlobalCompact` / candidate tail は不変。
12. **`FolderNaming` / title解決**: `FolderNaming.FromProposalLabel(label)` 追加。`GeneratedFolderTitles` のexhaustive `when` と golden corpus / test helperのnaming文字列化を更新。`FolderTitleResolver` 契約 (非blank・fallback) は不変。
13. **AI-facing instruction** (`IntentWireContract` + `ExchangePackageComposer`): descriptor field更新 (`categoryRef` / `proposalLabel`、exactly-one-of claim、ref scope claim、label上限)、You-must / self-check文言。**canonical templateは変更しない**。
14. **UI**: `ExchangeImportSummary` へkind別countを追加 (label / ref / free-text fieldは追加しない)、import成功サマリ / previewの差別化表示、promotion action (coordinator接続) と strings (en/ja)、failure copy 2種追加。

## Data model / identity

- category ref: `RandomIdAllocator.newId()` (item refと同一seam)。builderで3 namespace横断の一意性を検査。
- 解決の正本: import時は「reconstructed `categories` のadvertise集合 → session `categoryRefs` → import時catalog membership」の3段 (spec D-5)。export時は catalog snapshot → ref割当。
- identityのcanonical表現 (`canonicalValue`) は既存のまま (plan / digest互換)。`CategoryIdentity` / `UserCategoryId` 型は変更しない。
- intent identity: `groupSemantic` のidentity rowは `categoryRef` / `proposalLabel` (解決前のauthored内容) を使う — spec 330 D-5の「authored内容の関数」規律を維持。解決済みidentityはplanner projectionのみに現れる。
- formation keyの全順序により、proposalの有無だけが異なるrunで既存groupのordinalが不変である (testで固定)。

## Control / data flow (export→import)

```text
[export]
composer.composeFullOrganization()/composeScopeComposedOrganization()   (catalog込みの1回のcut)
  → ExportInputs (+catalog projection)
  → ContextExportBuilder: categories生成 (ref割当・canonical順) + item/folder category ref投影
  → BuiltExport(export, session+categoryRefs) → AndroidExportSessionStore
  → ExchangePackageComposer (descriptor派生instruction + CONTEXT data)
[import]
ExchangeImportPipeline.prepare (envelope→normalizer→framing→codec v4)
  → validate: session lookup → expiry → structural digest (identity基準・不変)
  → SessionExportReconstructor (categories + ref再構築)
  → IntentValidator (item ref解決 → category ref解決 → … → mobility → digest再確認)
  → completion → identity → run接続 (fresh run) → intentPreferences (identity + formation key解決済み)
  → planner (formation key: Existing/Proposed / ordering keyはclassification)
  → #194 preview → #195 confirm → apply (catalog書込みなし)
[post-run promotion (明示時)]
ExchangeFlowUi/サマリ → UserDefinedCategoryAuthoringCoordinator.create() (#336 lease + typed failures)
```

## Expected files to change (実装時に確定する詳細を含む)

- `lawnchair/src/app/lawnchair/organizer/personalization/`: `ContextExportModels.kt`、`ContextExportBuilder.kt`、`ContextExportCodec.kt`、`IntentModels.kt`、`IntentCodec.kt`、`IntentValidator.kt`、`IntentCompletion.kt`、`IntentIdentity.kt`、`IntentWireContract.kt`、`ExportSessionStore.kt`、`IntentPlannerAdapter.kt`
- `lawnchair/src/app/lawnchair/organizer/personalization/exchange/`: `ExchangePackageComposer.kt`、`ExchangeImportPipeline.kt` (failure追加)、`SessionExportReconstructor.kt`、`ExchangeImportSummary.kt` (kind別count)
- `lawnchair/src/app/lawnchair/organizer/integration/`: `AndroidExportSessionStore.kt` (additive record field)、`exchange/ExchangeInputAdapter.kt` (catalog引き回し)
- `lawnchair/src/app/lawnchair/organizer/planning/`: `FolderFormation.kt` (FormationKey)、`FullRunExecution.kt` (formation key / ordering key)、`PlanningResult.kt` (`FolderNaming.FromProposalLabel` + `folderNamingFor`)
- `lawnchair/src/app/lawnchair/organizer/ui/`: `GeneratedFolderTitles.kt` (variant追加)、`exchange/ExchangeFlowUi.kt` (サマリ差別化 / promotion / failure copy)、`UserDefinedCategoryAuthoring.kt` (create入口の再利用。契約変更なし)
- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`: failure 2種追加、サマリ差別化 / promotion copy
- 既存test更新: `Issue336ExchangeProjectionTest` (user-defined投影のrivision)、`ContextExportBuilderTest` / `ContextExportCodecTest` / `IntentCodecTest` / `IntentValidatorTest` / `IntentPlannerAdapterTest` / `ExchangeTargetScopeCouplingTest` / `SourceContextIdentityTest` / `SessionExportReconstructorTest` / `ExchangeImportSummaryTest` / `ExchangePackageComposerTest` / `Issue348AiFacingContractSyncTest`、planner側は `CategoryIdentityCatalogTest` / `PlannerGeneratedPropertyTest` / golden corpus
- docs (実装PR必須): specs 204 / 205 / 336 / 348のnormative更新 + change history、`CONTEXT.md` 用語、`DESIGN.md` 該当gate、`docs/engineering/organizer-diagnostics.md` (必要時)

## Compatibility constraints

- v3までの流れ (spec 331 / 330) と同一: single-version runtime、旧version `SCHEMA_MISMATCH`、intent本文非永続化のためdurable移行影響はsession宛の再export案内のみ。
- **planner効果の等価性** (wire byte互換ではない): category参照 / proposalを含まないv4 intent + user-defined catalog空のexportは、v3と同じplan bytesを生む (回帰testで固定。intent-less runのbyte互換を最初に固定する)。
- `SourceContextIdentity` / `CandidateScopeIdentity` のdigest定義・入力は **変更しない** (#336 freshness規律)。session recordの新fieldはdigest入力外。
- `UserDefinedCategoryStore` / `CategoryOverrideStore` / override schema 2の変更禁止。`StrategyDefinition` の宣言fieldの変更禁止。

## Migration / rollback / recovery

- 永続schemaの進化はsession recordのadditive fieldのみ (record version 2維持)。旧recordはdefault空mappingで受理、新recordは旧binaryでdecode失敗 → 「session無し」fail-closed。
- rollback: 本機能にlayout DB / DB migration変更がないため、recovery契約への影響なし。失敗時はすべてtyped zero-write (既存pipeline規律)。
- v4 blocker時の撤回: version constantと新fieldを戻せばv3挙動へ戻る (単一version運用のためfeature flagは持たない)。

## Failure handling

- 新typed failure `UNKNOWN_CATEGORY_REF` — `ExchangeImportPipeline` の `ExchangeImportFailure.Contract` 経由でUIの20種目 (追加1種、合計20種) として表示。copy: 原因 + remedy (再export / 再依頼)。割当ありdeleteは既存 `CONTEXT_STALE` (pipeline段) が先にsettleし、新classは割当なしdelete / 未advertise ref / session mapping欠落に限定される。
- label値域違反は既存 `Oversize` (長さ) / `SchemaMismatch` (形状) を再利用し、新しいclassを作らない。
- promotion失敗は#336 typed failuresのそのままの表示 (`DuplicateName` / `CapacityExceeded` / `Conflict` / `OrganizationRunActive` 等)。`InvalidName` はlabel値域の一致により到達しない (testで固定)。
- export時catalog不在は既存 `InputReadinessReason` (NotReady) — 新失敗種を作らない。

## Testing strategy

Unit / contract (pure seams):
- builder: `categories` のtier別内容 (name有無matrix)、canonical順序、ref乱数性 (決定的allocator fixture) / 一意性 / 3-namespace横断、item category ref投影、`MAX_EXPORT_BYTES` 下の容量、raw built-in値・user ID非出現の文書走査。
- codec: v4 round-trip、v1 / v2 / v3拒否、`categoryRef`/`proposalLabel` exactly-one-of、label境界 (50 / 51 code points、空、`|`、改行)。
- validator: category ref corpus (正常built-in / user-defined、未advertise、session mapping破損、import catalog不在、rename成立、proposal-only)、失敗class決定表 (割当ありdelete → `CONTEXT_STALE` / 割当なしdelete → `UNKNOWN_CATEGORY_REF` / 未advertise ref → `UNKNOWN_CATEGORY_REF`) の pin、semantic帰属単位 corpus (同一label・非connectedの統合、同一label + 別component異semanticの分岐、同一categoryRef・非connectedの統合、both-field / 空label / 長さの既存class)、検証順序 (既存classとの排他) 回帰。
- completion / identity: `categoryRef` / `proposalLabel` をauthored内容としてidentityに反映、omission / unresolved規律不変の回帰 (spec 330 AC suite)。
- adapter: 解決済みidentity + labelへの分離投影、raw文字列がplanner projectionへ現れないこと。
- planner: formation key (Existing / Proposed) のmatrix (strategy別 positive / negative)、`FromProposalLabel` naming、ordering非参加 (classification key維持)、ordinal安定性、determinism / idempotence (mixed catalog、既存 #336 property suite拡張)、proposalが存在しないrunのbyte不変。
- reconstructor: `categories` / item ref再構築のparity (build→save→reconstruct)、削除categoryのadvertise除外、renameが検証結果を変えないこと。
- summary: kind別count、形状保証 (label / refを持たない) の維持。

Integration (pipeline / store / lease):
- `ExchangeImportPipeline.import` 経由のparity matrix追加行 (`UNKNOWN_CATEGORY_REF` / `SCHEMA_MISMATCH` / `OVERSIZE` 各1種固定)。
- `AndroidExportSessionStore`: `categoryRefs` の永続化、旧record受理、backup除外の再確認。
- promotion: `UserDefinedCategoryAuthoringCoordinator.create` 接続、duplicate / capacity / busyのtyped経路、import経路のstore不変property (AC-6)。

UI / instrumentation:
- importサマリ差別化表示 (3種)、promotion flow (成功・typed失敗・busy)、TalkBack / focus / non-color / large font (AC-10 / AC-16)。

Device evidence (後続pass、本planでは実行しない):
- AC-15の4系統representative flow (spec 348 Decision 7のprivacy policyに従うsanitized記録)。

## Accessibility evidence

- 新規 / 変更surface (サマリ、promotion確認、失敗表示) のTalkBack・focus restoration・keyboard / DPAD・Switch Access・非色区別・200% font scaleをinstrumentation / manual evidenceとして取得 (AC-16)。

## Security / privacy validation

- export文書走査test: user-defined ID・(redacted時) 名前が文書に現れない、refのcross-export非安定性 (同一状態2生成でcategory ref集合が共有されない)、item levelにraw built-in値が現れない。
- 名前文字列を `categoryRef` に使う応答 → `UNKNOWN_CATEGORY_REF` (allow-list照合) 、名前→categoryの解決経路が存在しないことのunit test。**heuristicな「promptらしさ」判定は導入しない** (1st revision planの該当oracleは削除)。
- diagnostics走査: ref↔identity対応・名前・proposal labelがjournal / diagnosticsへ現れないこと。
- session: 名前 / labelを含まないこと (ref→identityのみ) の走査。

## Incremental implementation order

1. **Phase 1 (export面)**: version bump + `categories` projection + item category ref投影 + session `categoryRefs` (builder / codec / store / reconstructor + tests)。spec 336 normative更新を含む。— **着地済み (PR #355)**
2. **Phase 2 (intent面)**: `GroupSemantic` v4 + validator (ref解決 / 失敗class決定表 / 1 class) + completion / identity / adapter (tests)。— **着地済み (PR #355)**
3. **Phase 3 (planner)**: formation key (Existing / Proposed) + `FolderNaming.FromProposalLabel` + title解決 + ordering key (planner tests、byte互換回帰)。— **着地済み (PR #355)**
4. **Phase 4 (AI-facing)**: descriptor / instruction更新 + parity / policy matrix拡張 (#348方式) + spec 204 / 205 / 348更新。template不変の回帰test。— **contract着地済み (PR #355)。spec 348の現状記述の残留行とhistory採番は「Re-entry後の着地状態と残件」7へ残存**
5. **Phase 5 (UI)**: importサマリ差別化 + promotion (coordinator接続) + strings + a11y。— **summary model側着地済み。UI差別化の完全化 (3種区別) とpromotion UX、AC-6 apply段 / AC-4 candidate tail / AC-1・AC-2補助oracleが残存 (「Re-entry後の着地状態と残件」1〜5)。UI着手は #373と整合**
6. **Phase 6 (evidence)**: representative flow device evidence (AC-15) と a11y evidence (AC-16)。

各Phaseは独立test群を持ち、Phase 1-2で契約が、Phase 3でplanner効果が確定する。PR分割はPhase単位を基本とし、高リスクlabelの該当可否を各PRで判定する (planner変更を含むPRは `risk: layout-data` を検討する)。PR #355では `risk: layout-data` + 独立監査 ([pr-355-issue337-category-refs.md](../../docs/assessment/pr-355-issue337-category-refs.md)) が適用された。

## Dependency / blocker

- ~~**本specのOwner acceptance** (D-1〜D-8、Resolved decisions) — 実装開始のblocker。~~ **解消済み** (2026-09-18、Phase1 review Approve。spec status: accepted)。
- #327 (implemented) / #328 (implemented): 本specはその契約を変更せず、回帰testで不変を固定する。
- #336 / #330 / #348契約前提: 解消済み (implemented)。
- **#373 (OPEN)**: AC-9 / AC-10のUI残件の着手順の依存先 (表示構造の整合。#362 disposition)。UI residualを先に実装するとT-18再構成で手戻りするriskがあるため、#373のspec/plan確定を待つか、contract面 (summary model・promotion coordinator接続) と表示面を分離して着手する。
- **#361 TO-BE UX (accepted)**: spec 337関係の変更は「Amend (影響評価後)」分類。現時点の残件実装でAmendは不要だが、T-18面の実装時に影響評価が発生しうる。

## Risk

- **#336規律の意図的revision**: 「user-definedはexchangeに出さない」規律を置き換えるため、spec 336本文・AC-14の更新漏れが正本矛盾を生む → 実装PR必須更新チェックリスト (AC-14) で管理。
- **planner変更面**: formation keyの一般化はfolder形成の中核に触れる → intent-less / ref-less runのbyte互換を最初に固定し、既存suite (golden corpus含む) を全実行してから進める。
- **UI失敗種の増加 (19→20)**: 既存失敗表示対応表・instrumentation testの更新範囲が広い → 対応表の追加1行に限定する設計 (exhaustive `when` がcompile時に漏れを検出)。
- **builder / export容量**: `categories` 追加で256 KiB上限到達が早まる → 容量testと、超過時は既存typed `Oversize` (fail-closed) の確認。
- **label値域のtightening (100字→50 code points)**: 既存の長いfreeTextを使う外部AI運用があればv4でrejectされる → 失敗classは `Oversize` で既存案内と整合し、再依頼案内で回復可能 (互換性節に明記)。
- **promotion UXのlease干渉**: run中操作のbusy経路がユーザー混乱を招く可能性 → 案内copyと提示位置 (run非active時のみ) をAC-9 / AC-10 testで固定。

## Explicitly unverified areas

- 本planのfile単位の変更範囲は実装時に確定する (model / field名、`ExportInputs` へのcatalog入力の形)。— Phase 1〜4分はPR #355で確定・着地済み。
- `FolderNaming.FromProposalLabel` を文字列化する既存test helper (golden corpus / recording resolver) の更新箇所は実装時にcompile errorで全量を洗い出す。— PR #355で処理済み (unit lane green、1517 tests)。
- #327のinstruction文言とdescriptor claimの最終表現 (You-must文の具体形) は実装時に #348 のpolicy matrix方式で確定する。— PR #355で確定済み (descriptor v4 claims)。
- **device evidence (AC-15) と a11y evidence (AC-16) は実機 / emulatorとowner操作が必要で、本planでは実施しない** (未実施)。
- 「Re-entry後の着地状態と残件」節の残件リストは2026-09-19時点の `main` (`3076bdae7ebf`) に対するgrep / file読みによる確認であり、その後のmergeで変化しうる。再利用時はre-entry ruleに従い現mainで再確認する。
- #373のspec/planは未確定 (Issue OPEN) であり、AC-9 / AC-10 UI残件の最終の表示構造・着手順は#373側の確定を待つ部分がある。
