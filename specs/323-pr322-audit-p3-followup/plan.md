# Implementation Plan: PR #322 独立監査 P3指摘のフォローアップ

> Issue: #323
> Spec: [spec.md](./spec.md)
> Status: **draft** — spec自身がdraft (未accept)。実装開始の可否はOwner判断とする。
> Baseline: `origin/main` = `3076bdae7e` (2026-09-19再検証時点。#205 PR #325 / #331 PR #333 / #330 PR #335 / #329 PR #339 / #336 PR #341 / #332 PR #344 / #348 PR #349 / #328 PR #353 / #337実装 PR #355 merge済み。初回snapshotはbaseline `0cf82bc1e6`、commit `584abbbf5e`。第2回はbaseline `aab0d293d1`、commit `67b304d37b`。第3回はbaseline `703afe3f4c`、commit `c5da1aa190`)

## Current evidence

実装開始時に再検証する。以下は2026-09-19再検証 (baseline `3076bdae7e`) の実code確認。

**結論の先出し: 本Issueの残余は実装作業ではなくOwner判断 (D-323-1 / D-323-2) と解消記録である。本planが実装するcode変更は存在しない。** 旧AC-1相当は#330が、旧AC-2相当は#348が、それぞれmain上で実装済み (下記)。

- `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt`
  - `:80-83` — `unresolvedRefs` 内部重複 (`unresolved.size != intent.unresolvedRefs.size`) → `IntentValidationFailure.IncompleteCoverage`。**旧P3-5の分類対象行** (v3/v4でも不変)。**直接testは#330が追加済み** (下記 `coverageSplitViolationsAreStillRejected`)。
  - `:89-97` — `pageAffinity` のexport grid page範囲検証 (`ordinal < 0 || ordinal >= export.grid.pageCount` → `IntentValidationFailure.InvalidEnum`)。**旧P3-6の検証対象行** (P3-2対応の第3回再audit実装)。**直接testは#348が追加済み** (下記 `Issue348AiFacingContractSyncTest`)。
  - `:56-68` — #337追加: category ref resolution (`groupSemantic.categoryRef` がexport `categories` / session `categoryRefs` に不在 → `UnknownCategoryRef`)。coverage partitionの**前**に走るが、pageAffinityのみを設定するfixture (`groupSemantic` なし) ではno-opのため、旧AC-2の到達分岐は不変。
  - `:120-122` — #331: `Mobility.CANDIDATE` への `preserve` → `MobilityContradiction` (本plan対象外)。
  - check順序 (v4): session失効 → export一致 → item/desiredGroup/unresolved ref解決 → **category ref解決 (#337)** → item ref重複 → coverage partition (**unresolved内部重複** → itemIntents/unresolved重複) → **pageAffinity範囲** → mobility矛盾 (FIXED/CONDITIONAL/CANDIDATE) → 構造的staleness → `IntentCompletion` による補完。
- `lawnchair/src/app/lawnchair/organizer/personalization/IntentCodec.kt:188-192` — `pageAffinity` のdecodeは `optInt` の型検査のみで整数範囲は検証しない。したがって#348のreject fixture (`pageCount` / `-1`) はdecodeを通過し、validatorの範囲検証 (`:89-97`) が到達先となる (分類 `InvalidEnum` の出所の確認済み)。
- `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt`
  - `coverageSplitViolationsAreStillRejected` (現 `:194-217`、#330追加。#337のv4拡張後も不変) がoverlap **と `unresolvedRefs` 内部重複** (`:207-212`: `itemIntents = emptyList()`、`unresolvedRefs = listOf(refs[0], refs[0])` → `IncompleteCoverage` exact assert `:213-216`) を既にcover。**旧AC-1相当はこのtestが充足しており、本planの変更対象ではない。**
  - fixture helper: `device()` / `app(id, x, y, locked)` は不変。`buildState` (`:76-94`) は#337で `activeCatalog` (default `catalog()`) と `resolved` 引数を追加されたが、default呼び出し `buildState(listOf(app("a")))` は1 page (`listOf(Page(PageId("p0"), PageOrder(0)))`、`:81`) のまま (`grid.pageCount == 1`)。
  - `validatorIsTotalOverArbitraryInputs` (`:373`) のJSON文字列 (`:380`) は意図的にstaleな `personalized-intent-v3` 表記のままである (totality入力。schema mismatch系の入力として機能)。schema version定数自体は `personalized-intent-v4` へbump済み (`ContextExportModels.kt:36`、`ContextExportContract.INTENT_SCHEMA_VERSION`)。intent data classは引き続き `PersonalizedIntentV1` 型名。
- `tests/unit/app/lawnchair/organizer/personalization/exchange/Issue348AiFacingContractSyncTest.kt` (新規、#348)
  - `everyProductionClaimHasAKeyedParityCaseOnTheProductionPath` (`:598`) が `IntentWireContract.productionClaims` の全 `PRODUCTION_ENFORCED` claimについて、claimのtyped semanticからaccept/reject fixtureを導出し、production pipeline seam (`ExchangeImportPipeline.import`、`:131-134`) 上でexact assertする。
  - **旧P3-6/AC-2相当**: `pageAffinity.exportBound` claim (`IntentWireContract.kt:283`、`Semantic.IntBounds(pageAffinitySpec.min!!, max = null)` `:287`) に対し、`IntBounds` 分岐 (同test `:466-476`) が `pageCount - 1` をaccept、**`pageCount` (`:472`) と `min - 1` = `-1` (`:474`) を `ExchangeImportFailure.Contract(InvalidEnum)` でexact assert**。`parityState()` (`:290-293`) は1 page grid (`buildState` default `pageCount = 1`)。旧AC-2が要求した「負と `pageCount` 以上の両境界で `INVALID_ENUM`」をpipeline seam経由で実現しており、本planの実装対象ではない。validator直呼びのunit testを追加で足すことは禁止しないが、本spec (spec.md AC-2) の作業対象でもない。
- `lawnchair/src/app/lawnchair/organizer/personalization/ContextExportBuilder.kt` / `ContextExportModels.kt` — P3-3関連 (spec「現在の状態」表・D-323-1参照)。untyped throw site: `:102` (non-AppKey candidate `error`)、`:110` (candidate ref衝突)、`:134` (512件上限 `require`)、`:237` (**category ref割当衝突 — #337追加**)、`:358` (placed item ref衝突)。空label: `ContextExportModels.kt:294` (`ExportItemLabel` の `require(value.isNotEmpty())`)。既存test `ContextExportBuilderTest.degenerateAllocatorIsDetectedAtExportScope` (`:349`、不変) がplaced衝突経路を実行済み。本planでは変更しない。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt:79-118` — production builder caller (`generate` / `generateForSelection` / private `generate`)。`703afe3f4c..3076bdae7e` で無変更。builderのthrowをcatchせず、`InputNotReady` / `SessionStoreFailure` / `EncodeFailure` のみtyped `ExchangeGenerationResult` で返す (D-323-1の判断資料。本planでは変更しない)。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` / `PlacementAllocator.kt` — P3-9関連 (spec「現在の状態」表参照)。#337の変更 (run-scoped formation key等) でpreference消費構造は無変更 (line番号のみシフト: `preferenceCellHint` `:811-813`、`biasedPreferredPage` `:693-704` (範囲guard `:702`)、cell hint wiring `:857-862`/`:885-890`、`allocateWithCellHint` 呼び出し `:961`、`PlacementAllocator.kt:62` 不変)。本planでは変更しない。
- production sourceへの変更計画は存在しない (spec Non-goals)。

## Changes

**なし。** 旧AC-1は#330 (PR #335) が、旧AC-2は#348 (PR #349) が実装済みであり、本Issueが実装するcode変更 (test追加を含む) は存在しない。残余はD-323-1 / D-323-2のOwner判断であり、その対応実装 (判断後) は別Issue/別specのplanで定義する。

### 1. 旧AC-1: `unresolvedRefs` 内部重複 → `INCOMPLETE_COVERAGE` (P3-5) — 実装不要 (#330が充足済み)

- #330 (PR #335) が追加した `coverageSplitViolationsAreStillRejected` (現 `IntentValidatorTest.kt:194-217`) が、`itemIntents = emptyList()`、`unresolvedRefs = listOf(refs[0], refs[0])` が `IncompleteCoverage` とexact一致することを既にassertする (#337のv4拡張後も不変、2026-09-19確認)。本planでの作業は存在しない。

### 2. 旧AC-2: 範囲外 `pageAffinity` → `INVALID_ENUM` (P3-6) — 実装不要 (#348が充足済み)

- #348 (PR #349) が新設した `Issue348AiFacingContractSyncTest` の `pageAffinity.exportBound` parity case (claim: `IntentWireContract.kt:283-288`) が、`pageCount` と `-1` の両境界を `ExchangeImportFailure.Contract(InvalidEnum)` でexact assertする (同test `:472`/`:474`、実行は `ExchangeImportPipeline.import` 経由)。codecは整数範囲をdecode時に検証しないため (`IntentCodec.kt:188-192`)、reject到達先は `IntentValidator.kt:89-97` である。本planでの作業は存在しない。

### 3. AC-4: spec記録済み (本specのdecision packet・解消証拠)

- 追加実装作業なし。D-323-1 / D-323-2 とP3-2/P3-4/P3-5/P3-6/P3-7/P3-8解消証拠 (P3-5は#330、P3-6は#348による解消判定を含む) はspec.mdに記録済み。OwnerがIssue #323のcheckboxを更新するか、D-323-1/D-323-2を判断する際に本specのstatusをどうするかはOwner判断に委ねる。

## 非変更 (明示)

- `lawnchair/src/app/lawnchair/organizer/**` (production・testとも) — 一切変更しない。
- `specs/204-ai-personalization-context-intent-contract/**` — 変更しない (Owner approvalがないため)。
- Issue #323本文のcheckbox — Owner判断。
- 新Issue作成、flake調査 — 行わない (spec「Split candidates」参照)。

## Verification

本planのsnapshot更新 (docs-only) 自体の検証:

```bash
git diff --check
```

- spec/plan内の参照path (source / test / spec / assessment記録) がbaseline commit上で実在することを目視確認する (2026-09-19時点で確認済み)。
- build / device testは不要 (documentation差分のみのため)。

将来の実装 (D-323-1 / D-323-2のOwner判断後の別Issue) では、そのspec/planが検証方法を定義する。参考として、validator近傍の既存検証command:

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
```

- testのみの変更で済む場合でも `risk: layout-data` / `risk: migration` の該当性をその時点のdiffで判定し、PRで明記する (現行の分類固定test群は#330/#348が既にCI対象に含む)。

## Rollback

- 本planによる変更はdocumentationのみのため、commitのrevertで完結。migration・永続化・DB・設定への影響は存在しない。

## Dependencies / unresolved

- 依存: PR #322 / #325 (#205) / #333 (#331) / #335 (#330) / #339 (#329) / #341 (#336) / #344 (#332) / **#349 (#348)** / **#353 (#328)** / **#355 (#337実装)** すべてmerge済み (baseline `3076bdae7e` に包含)。#206には依存しない。
- 並行作業 (同じfile/seamを共有し得る): **#337残余** (Issue open。evidence等の残余scope) は `IntentValidator.kt` / `IntentValidatorTest.kt` / `ContextExportBuilder.kt` を变更し得る。**#356 Epic / #362 (disposition、PR #378 open)** はpersonalization/exchange seamのTO-BE再構成を計画中で、D-323-1/D-323-2の対応実装はmigration順序確定と協調すること。**#348残余**はAC-11 device evidenceのみでvalidator/testを变更しない。着手時に各Issueのstatusを確認し、未調整の同時変更を避ける (spec「Dependencies / coordination」節)。
- 未決定 (本planの実装が存在しないため、直接的なblockerはないが、P3-3/P3-9自体の解消をblockする): D-323-1、D-323-2 (spec「Decision packets」節)。対応実装はOwner判断後の別Issue/別specで行う。

## Re-entry rule

実装開始前にthen-current `origin/main` を取得し、baseline `3076bdae7e` との差分を `git log --oneline <baseline>..origin/main` と `git diff --name-status <baseline>..origin/main` で確認する。`IntentValidator.kt` / `IntentValidatorTest.kt` / `Issue348AiFacingContractSyncTest.kt` / `ContextExportBuilder.kt` への変更 (特に#337残余や#356/#362 TO-BE移行によるvalidator分類・check順序・schema version・throw siteの変更) があれば、本planの証拠行と分類固定の現状を再検証してから着手する。P3-5が#330のtestで、P3-6が#348のparity caseで、引き続き固定されていることも再確認する。全Issueコメントも再取得する。
