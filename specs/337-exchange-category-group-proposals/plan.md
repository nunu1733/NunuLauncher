---
issue: "#337"
status: draft
updated: 2026-09-18
---

# Plan: AI personalizationにおけるユーザー定義カテゴリの参照と新規グループ提案

> 本planはspec.md (draft — 2nd revision) の実装計画である。specのdecision (D-1〜D-8) がOwner reviewで受入れされるまで実装を開始しない。baseline: `34ba8ff447` (2026-09-18時点 `origin/main`、#328 / PR #353 merge後)。1st revision planからの変更点は「1st revisionからの主な変更」節に記録する。

## Current implementation (調査済み事実、`34ba8ff447`)

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
- Interface 5〜6 (intent / validator) を `categoryRef` + `proposalLabel` exactly-one-of、label値域 = #336 name規則、component一意検査 (`CONFLICTING_GROUP_SEMANTIC`) へ変更。
- Interface 8〜9 (adapter / planner) を「解決済みidentity + formation key (Existing / Proposed)」へ変更し、`FolderNaming.FromProposalLabel` を追加。
- Interface 10 (AI-facing) はcanonical templateを変更しない方針へ変更。
- Testing strategyの「prompt-like文を拒否するsecurity oracle」を削除し、値域test + allow-list testへ置換。
- 失敗classは 15 contract / 21 UI (1st revisionの 14 / 20 から+1)。

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
9. **`IntentValidator`**: item ref解決の後にcategory ref解決 (`UNKNOWN_CATEGORY_REF`) とcomponent一意検査 (`CONFLICTING_GROUP_SEMANTIC`) を追加。`IntentValidationFailure` へ2 class追加。検証順序はspec D-5に固定。
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
  → IntentValidator (item ref解決 → category ref解決 → component一意 → … → mobility → digest)
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

- 新typed failure `UNKNOWN_CATEGORY_REF` / `CONFLICTING_GROUP_SEMANTIC` — `ExchangeImportPipeline` の `ExchangeImportFailure.Contract` 経由でUI 21種目 / 22種目として表示。copy: 原因 + remedy (再export / 再依頼)。
- label値域違反は既存 `Oversize` (長さ) / `SchemaMismatch` (形状) を再利用し、新しいclassを作らない。
- promotion失敗は#336 typed failuresのそのままの表示 (`DuplicateName` / `CapacityExceeded` / `Conflict` / `OrganizationRunActive` 等)。`InvalidName` はlabel値域の一致により到達しない (testで固定)。
- export時catalog不在は既存 `InputReadinessReason` (NotReady) — 新失敗種を作らない。

## Testing strategy

Unit / contract (pure seams):
- builder: `categories` のtier別内容 (name有無matrix)、canonical順序、ref乱数性 (決定的allocator fixture) / 一意性 / 3-namespace横断、item category ref投影、`MAX_EXPORT_BYTES` 下の容量、raw built-in値・user ID非出現の文書走査。
- codec: v4 round-trip、v1 / v2 / v3拒否、`categoryRef`/`proposalLabel` exactly-one-of、label境界 (50 / 51 code points、空、`|`、改行)。
- validator: category ref corpus (正常built-in / user-defined、未advertise、session mapping破損、import catalog不在、rename成立、proposal-only)、component一意 corpus (同一 / 相違ref / 相違label / 混在)、検証順序 (category ref → component → 既存classの排他) 回帰、delete時の単一class決定。
- completion / identity: `categoryRef` / `proposalLabel` をauthored内容としてidentityに反映、omission / unresolved規律不変の回帰 (spec 330 AC suite)。
- adapter: 解決済みidentity + labelへの分離投影、raw文字列がplanner projectionへ現れないこと。
- planner: formation key (Existing / Proposed) のmatrix (strategy別 positive / negative)、`FromProposalLabel` naming、ordering非参加 (classification key維持)、ordinal安定性、determinism / idempotence (mixed catalog、既存 #336 property suite拡張)、proposalが存在しないrunのbyte不変。
- reconstructor: `categories` / item ref再構築のparity (build→save→reconstruct)、削除categoryのadvertise除外、renameが検証結果を変えないこと。
- summary: kind別count、形状保証 (label / refを持たない) の維持。

Integration (pipeline / store / lease):
- `ExchangeImportPipeline.import` 経由のparity matrix追加行 (`UNKNOWN_CATEGORY_REF` / `CONFLICTING_GROUP_SEMANTIC` / `SCHEMA_MISMATCH` / `OVERSIZE` 各1種固定)。
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

1. **Phase 1 (export面)**: version bump + `categories` projection + item category ref投影 + session `categoryRefs` (builder / codec / store / reconstructor + tests)。spec 336 normative更新を含む。
2. **Phase 2 (intent面)**: `GroupSemantic` v4 + validator (ref解決 / component一意 / 2 class) + completion / identity / adapter (tests)。
3. **Phase 3 (planner)**: formation key (Existing / Proposed) + `FolderNaming.FromProposalLabel` + title解決 + ordering key (planner tests、byte互換回帰)。
4. **Phase 4 (AI-facing)**: descriptor / instruction更新 + parity / policy matrix拡張 (#348方式) + spec 204 / 205 / 348更新。template不変の回帰test。
5. **Phase 5 (UI)**: importサマリ差別化 + promotion (coordinator接続) + strings + a11y。
6. **Phase 6 (evidence)**: representative flow device evidence (AC-15) と a11y evidence (AC-16)。

各Phaseは独立test群を持ち、Phase 1-2で契約が、Phase 3でplanner効果が確定する。PR分割はPhase単位を基本とし、高リスクlabelの該当可否を各PRで判定する (planner変更を含むPRは `risk: layout-data` を検討する)。

## Dependency / blocker

- **本specのOwner acceptance** (D-1〜D-8、Resolved decisions) — 実装開始のblocker。
- #327 (implemented) / #328 (implemented): 本specはその契約を変更せず、回帰testで不変を固定する。
- #336 / #330 / #348契約前提: 解消済み (implemented)。

## Risk

- **#336規律の意図的revision**: 「user-definedはexchangeに出さない」規律を置き換えるため、spec 336本文・AC-14の更新漏れが正本矛盾を生む → 実装PR必須更新チェックリスト (AC-14) で管理。
- **planner変更面**: formation keyの一般化はfolder形成の中核に触れる → intent-less / ref-less runのbyte互換を最初に固定し、既存suite (golden corpus含む) を全実行してから進める。
- **UI失敗種の増加 (19→21)**: 既存失敗表示対応表・instrumentation testの更新範囲が広い → 対応表の追加2行に限定する設計 (exhaustive `when` がcompile時に漏れを検出)。
- **builder / export容量**: `categories` 追加で256 KiB上限到達が早まる → 容量testと、超過時は既存typed `Oversize` (fail-closed) の確認。
- **label値域のtightening (100字→50 code points)**: 既存の長いfreeTextを使う外部AI運用があればv4でrejectされる → 失敗classは `Oversize` で既存案内と整合し、再依頼案内で回復可能 (互換性節に明記)。
- **promotion UXのlease干渉**: run中操作のbusy経路がユーザー混乱を招く可能性 → 案内copyと提示位置 (run非active時のみ) をAC-9 / AC-10 testで固定。

## Explicitly unverified areas

- 本planのfile単位の変更範囲は実装時に確定する (model / field名、`ExportInputs` へのcatalog入力の形)。
- `FolderNaming.FromProposalLabel` を文字列化する既存test helper (golden corpus / recording resolver) の更新箇所は実装時にcompile errorで全量を洗い出す。
- #327のinstruction文言とdescriptor claimの最終表現 (You-must文の具体形) は実装時に #348 のpolicy matrix方式で確定する。
- device evidence (AC-15) と a11y evidence (AC-16) は実機 / emulatorとowner操作が必要で、本planでは実施しない。
