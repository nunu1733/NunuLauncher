---
issue: "#337"
status: draft
updated: 2026-09-18
---

# Plan: AI personalizationにおけるユーザー定義カテゴリの参照と新規グループ提案

> 本planはspec.md (draft) の実装計画である。specのdraft decisions (D-1〜D-8、Open questions 1〜5) がOwner reviewで受入れされるまで実装を開始しない。baseline: `8fd05a40d51abd24b40a7b93579bb9b76d046f75` (2026-09-18時点 `origin/main`)。

## Current implementation (調査済み事実)

### 契約・検証面 (pure module、`organizer/personalization/`)

- `ContextExportModels.kt` — `ContextExportContract` (v3 constants、content limits、`CONFIDENCE_MIN/MAX`)、`PrivacyTier`、`FreeTextClass` (現状 `APP_LABEL` のみ)、`ExportItem` (`category: String?`)、`PersonalizationContextExportV1` (envelope。`categories` 相当のfieldなし)、`ExportSession` (`itemRefs`/`sourceContextDigest`/`scopeCandidates`/`scopeCandidateDigest`)。
- `ContextExportBuilder.kt` — 純粋builder。`CategoryIdentity?.exportPresentationValue()` が唯一のcategory redaction点 (built-in→raw値、user-defined→`null`)。`ExportInputs` は `snapshot`/`targets`/`resolvedIdentities`/`userLabels`/`signals`/`usageKeysByItem`/`nowEpochMs` を受ける (**catalogを直接は受けない**。resolved identitiesはcomposer出力の `signals.entries` から派生)。
- `SessionExportReconstructor.kt` — session + 現行 `CanonicalStructuralInputs` からvalidation viewを再構築。label/usageは非authorityとして省略。category fieldは `toExportItemCore` 経由でredaction規則を共有 (spec 205 parity)。
- `IntentModels.kt` — `GroupSemantic(category: String?, freeText: String?)` (any-of、freeText ≤100字)。`category` はschema上validateされない自由文字列。
- `IntentValidator.kt` — 検証順序: expiry → export一致 → ref解決 (item/desiredGroup/unresolved) → duplicate → coverage (v3 disjoint) → `pageAffinity` 範囲 → mobility → structural digest → completion。**category値の検証は存在しない**。
- `IntentCompletion.kt` / `IntentIdentity.kt` — completed表現 (3 decision状態) とidentity digest。`groupSemantic` は `category`/`freeText` をそのままidentity rowに含む (spec 330 D-5規律: identityはsemantic内容のみの関数)。
- `IntentCodec.kt` — `ALLOWED_SEMANTIC_KEYS` は `IntentWireContract.groupSemantic` 由来。`decodeGroupSemantic` がcategory/freeTextをdecode。
- `IntentWireContract.kt` — descriptor (`FieldSpec` + `ConstraintClaim`、`PRODUCTION_ENFORCED` / `AUTHORING_POLICY`)。`ExchangePackageComposer` のinstruction生成と `IntentCodec` allow-listの共有source。
- `IntentPlannerAdapter.kt` — `Authored` decisionのみを `ItemPreference` (ItemIdentity、role、importance、desiredGroup、`groupSemantic: GroupSemantic`、affinity、preserve) へ投影。**`groupSemantic` は文字列のままplannerへ渡る**。

### Planner面 (`organizer/planning/`)

- `OrganizationInput.kt` — `catalog: ActiveCategoryCatalog` (必須field、#336)。`intentPreferences` (第7policy input)。
- `CategoryIdentity.kt` — `BuiltIn`/`UserDefined`、`UserCategoryId` (UUID v4 regex検証)、`ActiveCategoryCatalog` (`allowedIdentities`/`contains`/`displayNameOf`)。
- `FullRunExecution.kt` (L736付近) — `effectiveCategory(itemId)`: intent `groupSemantic.category` が `input.taxonomy.allowedCategories` に一致すれば `BuiltIn`、**非一致は黙ってclassification decisionへフォールバック**。freeTextのplanner消費はない。`folderNamingFor(category)` → `FolderNaming.FromCategory` / `FromUserCategory` (#336)。

### Rule Management面 (`organizer/rules/`)

- `UserDefinedCategoryStore.kt` — AtomicFile store (generation+digest、capacity 64、typed failures: `InvalidName`/`DuplicateName`/`UnknownId`/`CapacityExceeded`/`StoreUnreadable`/`UnsupportedSchema`/`Conflict`/`WriteFailed`/`VerificationFailed`)、`UserDefinedCategoryCatalogSource`、`UserDefinedCategoryCatalogIdentity` (provenance/sentinel)。
- composition側のcatalog読み失敗は #336 により `InputCompositionCode` のtyped NotReady (exchange生成は `ExchangeInputResult.NotReady` として既存経路で受ける)。

### Integration / UI面

- `organizer/integration/exchange/ExchangeInputAdapter.kt` — export生成とimport検証の単一canonical seam (`composeFullOrganization` / `composeScopeComposedOrganization` → `ExportInputs`)。
- `organizer/integration/exchange/ExchangeFlowController.kt` — generate / cancelDisclosure / importReply。import成立後はfresh run再構築。
- `organizer/integration/AndroidExportSessionStore` (session永続化。record file `organizer_personalization_export_session_v2.json` 相当)。
- `organizer/ui/exchange/ExchangeFlowUi.kt` (import surface)、`organizer/ui/UserDefinedCategoryAuthoring.kt` (`UserDefinedCategoryAuthoringCoordinator` — #336 authoring path。create/rename/delete、lease検査込み)、stringsは `lawnchair/res/values/strings.xml` + `values-ja`。
- `exchange/ExchangeImportPipeline.kt` (envelope→normalizer→framing→codec→session→digest→reconstruct→validate。`ExchangeImportFailure` 3系統19種)。

## Ownership / module boundaries

- 契約・検証・identityの変更はすべて `organizer/personalization/` (pure) に閉じる。Android型・DB rowをpure seamへ入れない (既存AGENTS.md規約)。
- catalog読みは既存composition seam (`OrganizationInputComposer` → `OrganizationInput.catalog`) からのみ行う。builderがstoreを直接読まない (単一cut保証)。
- category永続化のwriterは既存 `UserDefinedCategoryAuthoringCoordinator` のみ。exchange側に新writerを生やさない。
- session recordの進化は `ExportSessionStore` seamの内側 (実装 `AndroidExportSessionStore`) で行う。

## Interfaces / seams (変更点)

1. **`ContextExportContract`**: `SCHEMA_VERSION = "personalization-context-v4"`、`INTENT_SCHEMA_VERSION = "personalized-intent-v4"` (D-8)。
2. **envelope**: `PersonalizationContextExportV1.categories: List<ExportCategory>` 追加 (additive model変更だが、wire意味変更のためversion bump)。`ExportCategory(ref, kind: CategoryRefKind, name: String?)` — `name` はbuilt-in時のみ常に存在、user-defined時はtier制御。`CategoryRefKind.BUILT_IN/USER_DEFINED`。
3. **`ExportItem.category`**: 意味を「advertise済みcategory refまたはbuilt-in raw値」へ改訂 (D-3)。user-defined分類itemは自身のcategory refを持つ。builder不変条件 (refs一意、`categories` のref一意、item category ref ∈ advertise済みref集合) を追加。
4. **`ExportSession`**: `categoryRefs: Map<String, CategoryIdentity>` (ref→identity) を追加。identityはplanning-domain型のままsessionにのみ存在 (export文書には出ない)。record schemaのbump/additiveは実装時に既存record versioning規律へ従う。
5. **`GroupSemantic`**: `category: String?` → `categoryRef: String?` (D-4)。any-of・freeText上限は不変。codec/descriptor/completion/identity/adapterを一括改訂。
6. **`IntentValidator`**: category ref解決段の追加 (D-5) と `IntentValidationFailure.UnknownCategoryRef` 追加。解決には `export.categories` (advertise済みref集合) + `session.categoryRefs` (identity解決) + catalog存在検査 (import composition cutのcatalog) を使う。検証関数の入力にimport時catalog projectionが渡せるよう `validate(...)` の引数またはreconstructed view経由で渡す (reconstructed viewの `categories` とsession mappingで解決する形を推奨 — pipelineの既存順序を保つ)。
7. **`SessionExportReconstructor`**: validation viewに `categories` を再構築して載せる (authority field扱い)。session mappingのidentityが現行catalogに存在しない場合、当該refをadvertiseから外す (validatorが `UNKNOWN_CATEGORY_REF` に落とす)。**ref↔identityのsession mapping自体は不変** (digest対象外のsession metadata)。
8. **`IntentPlannerAdapter` / `ItemPreference`**: `groupSemantic: GroupSemantic` の代わりに解決済みidentity (existing ref時) とproposal label (freeText時) を分離して運ぶ形式へ (`groupCategory: CategoryIdentity?` + `groupLabel: String?` 等。詳細は実装時に確定 — spec契約は「plannerへraw文字列categoryを渡さない」のみ)。
9. **`FullRunExecution.effectiveCategory`**: identity直接消費へ置換。文字列taxonomy照合と黙ってfall-through経路を削除。freeText labelはnew-folder naming sourceへ (D-6、strategyが `createsFolders` の場合のみ、非blank検証+generic fallback)。
10. **AI-facing instruction** (`IntentWireContract` + `ExchangePackageComposer`): `categoryRef` field spec、`categories` 参照規則、You-must/self-check文、canonical例。#348のdescriptor同期test方式を踏襲。
11. **UI**: `ExchangeFlowUi` import成功サマリ (existing/proposed/persisted区別)、promotion action、preview系のfolder title表示は既存path流用。strings (en/ja) 追加。

## Data model / identity

- category ref: `RandomIdAllocator.newId()` (item refと同一seam)。export内一意検査をbuilderに追加。
- 解決の正本: import時はsession mapping → identity → import-time catalog membership の3段 (spec D-5)。export時はcatalog snapshot → ref割当。
- identityのcanonical表現 (`canonicalValue`) は既存のまま (plan/digest互換)。本Issueは `CategoryIdentity` / `UserCategoryId` 型を変更しない。
- intent identity: `groupSemantic` のidentity rowは `categoryRef` (解決前のauthored内容) を使う — spec 330 D-5の「authored内容の関数」規律を維持。解決済みidentityはplanner projectionのみに現れる (identity計算をcatalog内容に依存させない = 同一authored intentのidentityはcatalog変化で変わらない。catalog依存のdeterminismは `InputProvenance` のcatalog参加が担う)。

## Control / data flow (export→import)

```text
[export]
composer.composeFullOrganization()/composeScopeComposedOrganization()   (catalog込みの1回のcut)
  → ExportInputs (+catalog projection)
  → ContextExportBuilder: categories生成 (ref割当) + item category ref投影
  → BuiltExport(export, session+categoryRefs) → AndroidExportSessionStore
  → ExchangePackageComposer (descriptor派生instruction + CONTEXT data)
[import]
ExchangeImportPipeline.prepare (envelope→normalizer→framing→codec v4)
  → validate: session lookup → expiry → structural digest (identity基準・不変)
  → SessionExportReconstructor (categories再構築)
  → IntentValidator (+category ref解決, UNKNOWN_CATEGORY_REF)
  → completion → identity → run接続 (fresh run) → intentPreferences (identity解決済み)
  → planner (effectiveCategory identity直接消費 / freeText label)
  → #194 preview → #195 confirm → apply (catalog書込みなし)
[post-run promotion (明示時)]
ExchangeFlowUi/サマリ → UserDefinedCategoryAuthoringCoordinator.create() (#336 lease + typed failures)
```

## Expected files to change (実装時に確定する詳細を含む)

- `lawnchair/src/app/lawnchair/organizer/personalization/`: `ContextExportModels.kt`、`ContextExportBuilder.kt`、`ContextExportCodec.kt`、`IntentModels.kt`、`IntentCodec.kt`、`IntentValidator.kt`、`IntentCompletion.kt` (field名変更追従)、`IntentIdentity.kt` (row語彙)、`IntentWireContract.kt`、`ExportSessionStore.kt`、`IntentPlannerAdapter.kt`
- `lawnchair/src/app/lawnchair/organizer/personalization/exchange/`: `ExchangePackageComposer.kt`、`ExchangeImportPipeline.kt` (failure追加)、`SessionExportReconstructor.kt`、`IntentImportParser.kt` (不変の見込み)
- `lawnchair/src/app/lawnchair/organizer/integration/`: `AndroidExportSessionStore` (record schema)、`exchange/ExchangeInputAdapter.kt` (catalog引き回し)
- `lawnchair/src/app/lawnchair/organizer/planning/`: `FullRunExecution.kt` (effectiveCategory/label消費)
- `lawnchair/src/app/lawnchair/organizer/ui/`: `exchange/ExchangeFlowUi.kt`、preview/サマリ関連、`UserDefinedCategoryAuthoring.kt` (create入口の再利用。契約変更なし)
- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`: 失敗1種追加 (`UNKNOWN_CATEGORY_REF`)、サマリ/差別化/promotion copy
- docs (実装PR必須): specs 204/205/336/348のnormative更新+change history、`CONTEXT.md` 用語、`DESIGN.md` gate 12/13、`docs/engineering/organizer-diagnostics.md` (必要場合のみ)

## Compatibility constraints

- v3までの流れ (spec 331/330) と同一: single-version runtime、旧version `SCHEMA_MISMATCH`、intent本文非永続化のためdurable移行影響はsession宛の再export案内のみ。
- built-in categoryのitem投影raw値・planner効果のbyte互換 (built-in-only catalog + category参照なしintent) を回帰testで固定。
- `SourceContextIdentity` / `CandidateScopeIdentity` のdigest定義・入力は **変更しない** (#336 freshness規律)。session recordの新fieldはdigest入力外。
- `UserDefinedCategoryStore` / `CategoryOverrideStore` / override schema 2の変更禁止。

## Migration / rollback / recovery

- 永続schemaの進化はsession recordのみ。additive fieldで読み手が既存recordを受理できる形を第一候補とし、record version bumpが必要な場合は旧version recordの受理 (read-side互換) を確定してから書く (実装PRで決定を記録)。
- rollback: 本機能にlayout DB/DB migration変更がないため、recovery契約への影響なし。失敗時はすべてtyped zero-write (既存pipeiline規律)。
- v4 blocker時の撤回: version constantと新fieldを戻せばv3挙動へ戻る (単一version運用のためfeature flagは持たない)。

## Failure handling

- 新typed failure `UNKNOWN_CATEGORY_REF` — `ExchangeImportPipeline` の `ExchangeImportFailure.Contract` 経由でUI 20種目として表示。copy: 原因 (参照したカテゴリが現在外面/存在しない) + remedy (再export・再依頼)。
- promotion失敗は#336 typed failuresのそのままの表示 (`DuplicateName`/`CapacityExceeded`/`Conflict`/`OrganizationRunActive` 等)。
- export時catalog不在は既存 `InputReadinessReason` (NotReady) — 新失敗種を作らない。

## Testing strategy

Unit / contract (pure seams):
- builder: tier別 `categories` 内容 (name有無matrix)、ref乱数性 (決定的allocator fixture)、ref一意、item category ref投影 (#336 absent→ref差分)、`MAX_EXPORT_BYTES` 下の容量。
- codec: v4 round-trip、v1/v2/v3拒否、`categoryRef`/`freeText` any-of、limits。
- validator: category ref corpus (正常built-in/user-defined、未知ref、session mapping不一致、import catalog不在、rename成立、freeText-only、捏造名)。検証順序 (既存classとの排他) 回帰。
- completion/identity: `categoryRef` をauthored内容としてidentityに反映、omission/unresolved規律不変の回帰 (spec 330 AC-2/4/5 suite)。
- adapter: 解決済みidentity + labelへの分離投影、raw文字列がplanner projectionへ現れないこと。
- planner: `effectiveCategory` identity消費、freeText label (createsFolders on/off)、category ordering非参加、determinism/idempotence (mixed catalog、既存 #336 property suite拡張)。
- reconstructor: categories/item ref再構築のparity (build→save→reconstruct)、削除categoryのadvertise除外。

Integration (pipeline / store / lease):
- `ExchangeImportPipeline.import` 経由のparity matrix追加行 (未知 `categoryRef` → `UNKNOWN_CATEGORY_REF` 1種固定)。
- session store: record進化 (旧record受理)、`categoryRefs` 永続化、backup除外の再確認。
- promotion: `UserDefinedCategoryAuthoringCoordinator.create` 接続、duplicate/capacity/busyのtyped経路、import経路のstore不変property (AC-5)。

UI / instrumentation:
- importサマリ差別化表示、promotion flow (成功・typed失敗・busy)、TalkBack/focus/non-color/large font (AC-10/13)。

Device evidence (後続pass、本planでは実行しない):
- AC-12の4系統representative flow (spec 348 Decision 7のprivacy policyに従うsanitized記録)。

## Accessibility evidence

- 新規/変更surface (サマリ、promotion確認、失敗表示) のTalkBack・focus restoration・keyboard/DPAD・Switch Access・非色区別・200% font scaleをinstrumentation/manual evidenceとして取得 (AC-13)。

## Security / privacy validation

- export文書走査test: user-defined ID・(redacted時) 名前が文書に現れない、refのcross-export非安定性 (同一状態2生成でcategory ref集合が共有されない)。
- injection corpus: 名前文字列を `categoryRef` に使う応答、prompt-like文を `freeText` に含む応答 → fail-closed。
- diagnostics走査: ref↔identity対応・名前がjournalへ現れないこと。
- session: 名前を含まないこと (ref→identityのみ) の走査。

## Incremental implementation order

1. **Phase 1 (契約core)**: version bump + `categories` projection + item category ref投影 + session mapping (builder/codec/store/reconstructor + tests)。spec 336 normative更新を含む。
2. **Phase 2 (intent側)**: `GroupSemantic.categoryRef` + validator解決 + `UNKNOWN_CATEGORY_REF` + completion/identity/adapter (tests)。
3. **Phase 3 (planner)**: `effectiveCategory` identity消費 + freeText label消費 (planner tests)。
4. **Phase 4 (AI-facing)**: descriptor/instruction/canonical例 + parity/policy matrix拡張 (#348方式) + spec 204/205/348更新。
5. **Phase 5 (UI)**: importサマリ差別化 + promotion (coordinator接続) + strings + a11y。
6. **Phase 6 (evidence)**: 代表flow device evidence (AC-12)。

各Phaseは独立test群を持ち、Phase 1-2で契約が、Phase 3でplanner効果が確定する。PR分割はPhase単位を基本とし、高リスクlabel (`risk: layout-data` 対象path) の該当可否を各PRで判定する。

## Dependency / blocker

- **本specのOwner acceptance** (draft decisions D-1〜D-8、Open questions 1〜5) — 実装開始のblocker。
- #327 (OPEN): canonical example/templateの合成先。Phase 4で材料共有が必要 (実装blockerではない)。
- #336/#330/#348契約前提: 解消済み (implemented)。

## Risk

- **contract張力**: #336の「user-definedはexchangeに出さない」規律を意図的に改訂するため、spec 336本文・AC-14の更新漏れが正本矛盾を生む → 実装PR必須更新チェックリスト (AC-14) で管理。
- **UI失敗種の増加 (19→20)**: 既存失敗表示対応表・instrumentation testの更新範囲が広い → 対応表の追加1行に限定する設計。
- **builder/export容量**: `categories` 追加で256 KiB上限到達が早まる可能性 (最大34+64 entry) → 容量testと、超過時は既存typed EncodeFailure (fail-closed) の確認。
- **planner変更面**: `effectiveCategory` は全runの中核分岐 → built-in-only回帰suite (#336 mixed-catalog suite) を全実行し、intentなしrunのbyte互換を最初に固定してから変更する。
- **promotion UXのlease干渉**: run中操作のbusy経路がユーザー混乱を招く可能性 → 案内copyと提示位置 (run非active時のみ) をAC-10/AC-7 testで固定。

## Explicitly unverified areas

- specのdraft decisions (D-2 redacted tier扱い、D-6 label消費、D-7 promotion範囲/位置、失敗class独立) はOwner判断待ちであり、plan上の実装詳細 (model/field名、record schema進化の可否判断) もacceptance後に確定する。
- session record (`AndroidExportSessionStore`) の物理schema詳細とrecord version現値は本調査でfile名規約までしか確認していない (実装Phase 1冒頭で再確認する)。
- #327のspec (branch上にのみ存在の見込み) は未読。Phase 4着手時にmain/branchの最新を確認する。
- `ExchangeGenerationGate` / `ScopeBindingGate` へのcategory投影の影響範囲は、scope candidate投影がidentity基準を維持する限り変更不要と判断したが、Phase 1のtestで回帰を取るまで未検証。
