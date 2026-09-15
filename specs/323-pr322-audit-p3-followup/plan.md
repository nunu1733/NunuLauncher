# Implementation Plan: PR #322 独立監査 P3指摘のフォローアップ

> Issue: #323
> Spec: [spec.md](./spec.md)
> Status: **draft** — spec自身がdraft (未accept)。実装開始の可否はOwner判断とする。
> Baseline: `origin/main` = `0cf82bc1e61c1874b280a7120dff9594be4fef71` (2026-09-15時点。= PR #322のmerge commit `0cf82bc1e6`)

## Current evidence

実装開始時に再検証する。以下はspec作成時 (2026-09-15、baseline `0cf82bc1e6`) の実code確認。

- `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt`
  - `:61-64` — `unresolvedRefs` 内部重複 (`unresolved.size != intent.unresolvedRefs.size`) → `IntentValidationFailure.IncompleteCoverage`。**P3-5の分類対象行**。この分岐を直接実行するtestは存在しない。
  - `:73-81` — `pageAffinity` のexport grid page範囲検証 (`ordinal < 0 || ordinal >= export.grid.pageCount` → `IntentValidationFailure.InvalidEnum`)。**P3-6の検証対象行** (P3-2対応の第3回再audit実装)。この検証を直接実行するtestは存在しない。
  - check順序: session失効 → export一致 → unknown ref → 重複 → coverage partition → **unresolved内部重複** → **pageAffinity範囲** → mobility矛盾 → 構造的staleness。範囲検証はcoverage partitionの後のため、test fixtureはcoverage partitionを通過する (全refをcoverする) 必要がある。
- `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt`
  - fixture helper: `device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)`、`buildState` は **1 page** (`listOf(Page(PageId("p0"), PageOrder(0)))`) のsnapshotを作るため `grid.pageCount == 1`。`app(id, x, y)` はworkspace配置のmovable item、`refsOf` でItemId→refの対応を取る。
  - `partialResponsesViolateTheCoveragePartition` がmissing/overlapを既にcover。新規testはこのfileに追加する。
  - organizer全体のtest実行record: 第8回再auditで1205 tests / 0 failures (head `af890c1e5b`)。
- `tests/unit/app/lawnchair/organizer/personalization/ContextExportBuilderTest.kt:347` — `degenerateAllocatorIsDetectedAtExportScope` がref衝突の `IllegalStateException` を期待例外として実行済み (P3-3の現状記録に使用。本planでは変更しない)。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` / `PlacementAllocator.kt` — P3-9関連 (spec「現在の状態」表参照)。本planでは変更しない。
- production sourceへの変更計画は存在しない (spec Non-goals)。

## Changes

すべて `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt` へのtest追加のみ。

### 1. AC-1: `unresolvedRefs` 内部重複 → `INCOMPLETE_COVERAGE` (P3-5)

- test名案: `duplicateUnresolvedRefsAreCoverageViolations` (既存命名規約に合わせる)。
- fixture: `buildState(listOf(app("a"), app("b", x = 1)))` (2 ref)。
- intent: `itemIntents = emptyList()`、`unresolvedRefs = listOf(refA, refA)` (同一exported refを2回。`itemIntents` との重複なし、未知refなし)。
- assert: `IntentValidationFailure.IncompleteCoverage` と一致 (`DuplicateRef` / `UnknownRef` でないことを型一致で保証)。
- 到達分岐: `IntentValidator.kt:61-64` (partition判定より前の内部重複専用分岐)。

### 2. AC-2: 範囲外 `pageAffinity` → `INVALID_ENUM` (P3-6)

- test名案: `pageAffinityOutsideTheExportGridIsAnInvalidEnum` (同上)。
- fixture: `buildState(listOf(app("a"), app("b", x = 1)))` (1 page / `grid.pageCount == 1`)。
- intent: 全exported refをcoverし (`INCOMPLETE_COVERAGE` を先に通過させる)、movable ref (未lock、`mobility = MOVABLE`) の `ItemIntent` に `pageAffinity = -1` を設定。残refは `pageAffinity` なしでcover。
- assert: `IntentValidationFailure.InvalidEnum` と一致。
- 境界2件: 負値 (`-1`) と `pageCount` 以上 (`1`、= `grid.pageCount`) をそれぞれ実行 (同test内の2 fixtureまたは2 test)。
- 到達分岐: `IntentValidator.kt:73-81`。

### 3. AC-4: spec記録済み (本specのdecision packet・解消証拠)

- 追加実装作業なし。D-323-1 / D-323-2 とP3-2/P3-4/P3-7/P3-8解消証拠はspec.mdに記録済み。実装PRでは本specへの変更を不要とし、status更新の要否をOwner判断に委ねる。

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

- 期待: いずれもPASS。新規test 2件 (境界複数実行を含む) が含まれ、既存organizer testは無修正で通過。
- testのみの変更 (`organizer/personalization` 純粋test file) であり、`risk: layout-data` / `risk: migration` の高リスク要件は対象外 (workflow「高リスクPRの独立エビデンス」節の適用条件外)。PRではdocs/plan上の対象外根拠を明記する。

## Rollback

- test-only commitのrevertで完結。migration・永続化・DB・設定への影響は存在しない。

## Dependencies / unresolved

- 依存: PR #322 merge済み (baseline `0cf82bc1e6` に包含)。#205/#206には依存しない。
- 未決定 (本planの実装をblockしないが、P3-3/P3-9自体の解消をblockする): D-323-1、D-323-2 (spec「Decision packets」節)。対応実装はOwner判断後の別Issue/別specで行う。

## Re-entry rule

実装開始前にthen-current `origin/main` を取得し、baseline `0cf82bc1e61c1874b280a7120dff9594be4fef71` との差分を `git log --oneline <baseline>..origin/main` と `git diff --name-status <baseline>..origin/main` で確認する。`IntentValidator.kt` / `IntentValidatorTest.kt` への変更 (特に#205/#206や追加review対応によるvalidator分類・check順序の変更) があれば、本planのtest位置・到達分岐・assertを再検証してから着手する。全Issueコメントも再取得する。
