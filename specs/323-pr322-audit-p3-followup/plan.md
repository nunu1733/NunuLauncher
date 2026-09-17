# Implementation Plan: PR #322 独立監査 P3指摘のフォローアップ

> Issue: #323
> Spec: [spec.md](./spec.md)
> Status: **draft** — spec自身がdraft (未accept)。実装開始の可否はOwner判断とする。
> Baseline: `origin/main` = `703afe3f4c1f5387f768832ea422c7b681c3775a` (2026-09-17再検証時点。#205 PR #325 / #331 PR #333 / #330 PR #335 / #329 PR #339 / #336 PR #341 / #332 PR #344 merge済み。初回snapshotはbaseline `0cf82bc1e6`、commit `584abbbf5e`。第2回はbaseline `aab0d293d1`、commit `67b304d37b`)

## Current evidence

実装開始時に再検証する。以下は2026-09-17再検証 (baseline `703afe3f4c`) の実code確認。

- `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt`
  - `:63-66` — `unresolvedRefs` 内部重複 (`unresolved.size != intent.unresolvedRefs.size`) → `IntentValidationFailure.IncompleteCoverage`。**P3-5の分類対象行**。#330 (v3) で全数cover検査 (`covered + unresolved != exportRefs`) は撤去されたため、この分岐は `INCOMPLETE_COVERAGE` の主要な単独分類経路になった。**直接testは#330が追加済み** (下記 `coverageSplitViolationsAreStillRejected`)。
  - `:74-80` — `pageAffinity` のexport grid page範囲検証 (`ordinal < 0 || ordinal >= export.grid.pageCount` → `IntentValidationFailure.InvalidEnum`)。**P3-6の検証対象行** (P3-2対応の第3回再audit実装)。この検証を直接実行するtestは依然存在しない。
  - `:103-105` — #331追加: `Mobility.CANDIDATE` への `preserve` → `MobilityContradiction` (本plan対象外。AC fixtureはplaced itemのみ使うため無関係)。
  - check順序 (v3): session失効 → export一致 → unknown ref → 重複 → coverage partition (**unresolved内部重複** → itemIntents/unresolved重複) → **pageAffinity範囲** → mobility矛盾 (FIXED/CONDITIONAL/CANDIDATE) → 構造的staleness → `IntentCompletion` による補完。v3では全数cover要求が廃止されたため、**test fixtureは全refをcoverする必要がない** (部分intentで範囲検証へ到達可能。full coverはv3のsupersetとして引き続き有効)。
- `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt`
  - fixture helper: `device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)`、`buildState` は **1 page** (`listOf(Page(PageId("p0"), PageOrder(0)))`) のsnapshotを作るため `grid.pageCount == 1`。`app(id, x, y)` はworkspace配置のmovable item、`refsOf` でItemId→refの対応を取る (#336の `CategoryIdentity` 型注記追加を除きhelperは不変、2026-09-17確認)。
  - `coverageSplitViolationsAreStillRejected` (現 `:145-168`、#330追加) がoverlap **と `unresolvedRefs` 内部重複** (`unresolvedRefs = listOf(refs[0], refs[0])` → `IncompleteCoverage` exact assert) を既にcover。**AC-1相当はこのtestが充足しており、本planの変更対象ではない。**
  - `validatorIsTotalOverArbitraryInputs` のJSON文字列は #330 により `personalized-intent-v3` に更新済み (現 `:331`)。**命名に関する注意**: schema version文字列はv3だがintent data classは引き続き `PersonalizedIntentV1` という型名のまま (spec 331/330はversion文字列のみbump)。AC fixtureは型を直接構築するため影響なし。
  - organizer test全体 (#329/#330/#332/#336追加のtestを含む) をgrep再確認 (2026-09-17): 範囲外 `pageAffinity` (負、`pageCount` 以上) をvalidatorに通すtestは存在しない。既存の `pageAffinity` 使用は範囲内のみ (`IntentValidatorTest:268`/`:317` で 0、`IntentPreferenceConsumptionTest` で 1)。`InvalidEnum` をassertする既存testはdecode層のunknown enum名のみ (`IntentCodecTest:123,130`、`ExchangeImportPipelineTest:330-333`)。
- `tests/unit/app/lawnchair/organizer/personalization/ContextExportBuilderTest.kt:349` — `degenerateAllocatorIsDetectedAtExportScope` がref衝突の `IllegalStateException` を期待例外として実行済み (P3-3の現状記録に使用。本planでは変更しない)。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` / `PlacementAllocator.kt` — P3-9関連 (spec「現在の状態」表参照)。#336のplanning refactor (CategoryIdentity導入) でpreference消費構造は無変更 (line番号のみシフト: `preferenceCellHint` `:800-805`、`biasedPreferredPage` `:690-698`、`allocateWithCellHint` 呼び出し `:950`、`PlacementAllocator.kt:62` 不変)。本planでは変更しない。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt:79-118` — production builder caller (`generate` / `generateForSelection`)。builderのthrowをcatchしない (spec D-323-1の判断資料。#332の変更はimport側のみ。本planでは変更しない)。
- production sourceへの変更計画は存在しない (spec Non-goals)。

## Changes

`tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt` へのtest追加 (AC-2、1 test) のみ。AC-1相当は#330が追加済みのため変更しない。

### 1. AC-1: `unresolvedRefs` 内部重複 → `INCOMPLETE_COVERAGE` (P3-5) — **実装不要 (#330が充足済み)**

- #330 (PR #335) が追加した `coverageSplitViolationsAreStillRejected` (`:159-168`) が、`itemIntents = emptyList()`、`unresolvedRefs = listOf(refs[0], refs[0])` が `IncompleteCoverage` とexact一致することを既にassertする (`DuplicateRef` / `UnknownRef` でないことも型一致から保証)。AC-1の本planでの作業は存在しない。実装時に同testの存在と内容を再確認するのみ。

### 2. AC-2: 範囲外 `pageAffinity` → `INVALID_ENUM` (P3-6)

- test名案: `pageAffinityOutsideTheExportGridIsAnInvalidEnum` (既存命名規約に合わせる)。
- fixture: `buildState(listOf(app("a")))` (1 ref、1 page / `grid.pageCount == 1`)。
- intent: movable ref (未lock、workspace配置の `app("a")`) 1件の `ItemIntent` に `pageAffinity = -1` を設定。v3では全ref coverは不要 (部分intentでpartition通過後、範囲検証へ到達する)。full cover fixture (2 item目を `unresolvedRefs` または `pageAffinity` なしの `ItemIntent` でcover) もv3のsupersetとして等価に有効であり、どちらでも到達分岐は同じ。
- assert: `IntentValidationFailure.InvalidEnum` と一致。
- 境界2件: 負値 (`-1`) と `pageCount` 以上 (`1`、= `grid.pageCount`) をそれぞれ実行 (同test内の2 fixtureまたは2 test)。
- 到達分岐: `IntentValidator.kt:74-80` (coverage partitionの後、mobility矛盾の前)。

### 3. AC-4: spec記録済み (本specのdecision packet・解消証拠)

- 追加実装作業なし。D-323-1 / D-323-2 とP3-2/P3-4/P3-5/P3-7/P3-8解消証拠 (P3-5は#330による解消判定を含む) はspec.mdに記録済み。実装PRでは本specへの変更を不要とし、status更新の要否をOwner判断に委ねる。

## 非変更 (明示)

- `lawnchair/src/app/lawnchair/organizer/personalization/**` (production) — 一切変更しない。
- `specs/204-ai-personalization-context-intent-contract/**` — 変更しない (Owner approvalがないため)。
- Issue #323本文のcheckbox — Owner判断。
- 新Issue作成、flake調査 — 行わない (spec「Split candidates」参照)。

## Verification

```bash
git diff --check
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
```

- 期待: いずれもPASS。AC-2の新規test (境界2件実行を含む) が含まれ、#330追加の `coverageSplitViolationsAreStillRejected` を含む既存organizer testは無修正で通過。
- testのみの変更 (`organizer/personalization` 純粋test file) であり、`risk: layout-data` / `risk: migration` の高リスク要件は対象外 (workflow「高リスクPRの独立エビデンス」節の適用条件外)。PRではdocs/plan上の対象外根拠を明記する。

## Rollback

- test-only commitのrevertで完結。migration・永続化・DB・設定への影響は存在しない。

## Dependencies / unresolved

- 依存: PR #322 / #325 (#205) / #333 (#331) / #335 (#330) / #339 (#329) / #341 (#336) / #344 (#332) すべてmerge済み (baseline `703afe3f4c` に包含)。#206には依存しない。
- 並行作業 (同じfile/seamを共有し得る): **#337** (user-defined categories × AI personalization、open) はintent schema拡張で `IntentValidator.kt` / `IntentValidatorTest.kt` を変更し得る。**#348** (AI-facing contractとvalidatorの同期、open) はvalidator分類の変更を含まない見込みだが近傍seamに触れ得る。着手時に両Issueのstatusを確認し、未調整の同時変更を避ける (spec「Dependencies / coordination」節)。AC-1のcoverage分類は #330 が `coverageSplitViolationsAreStillRejected` で既に固定済みであり、本planはそれを壊す変更を含まない。
- 未決定 (本planの実装をblockしないが、P3-3/P3-9自体の解消をblockする): D-323-1、D-323-2 (spec「Decision packets」節)。対応実装はOwner判断後の別Issue/別specで行う。

## Re-entry rule

実装開始前にthen-current `origin/main` を取得し、baseline `703afe3f4c1f5387f768832ea422c7b681c3775a` との差分を `git log --oneline <baseline>..origin/main` と `git diff --name-status <baseline>..origin/main` で確認する。`IntentValidator.kt` / `IntentValidatorTest.kt` への変更 (特に#337/#348によるvalidator分類・check順序・schema versionの変更) があれば、本planのtest位置・到達分岐・assertを再検証してから着手する。P3-5が#330のtestで引き続き固定されていることも再確認する。全Issueコメントも再取得する。
