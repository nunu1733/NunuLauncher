---
issue: "#293"
status: draft
spec: ./spec.md
updated: 2026-09-12
---

# Plan: issue #228 follow-up (ja解決test拡張とspec 13 `PreWriteRejection` 追記)

> Baseline: `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4`
> (2026-09-12時点)。本planは spec.md (**draft**) に対応し、記載の実装状態は
> すべてbaseline上での実確認に基づく。**実装開始前に再入場検証を行うこと**
> (spec.mdの参照先がbaseline以降に変更されていないか、PR #289以降の
> 関連diffの有無)。

## 1. 現状の実装と不足 (baseline確認済み)

| 要素 | 場所 | 状態 |
|---|---|---|
| `PreWriteRejection.CANDIDATE_UNAVAILABLE` | `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt` (L57-98、`EXACT_PRECONDITION_FAILED` と `OVERLAP_POLICY_REJECTED` の間) | 存在 (PR #289)。KDocが「Exactly the variants from spec.md」を要求 |
| contract testによる固定 | `tests/unit/app/lawnchair/organizer/application/contract/ApplyResultContractTest.kt` (L72) | 存在 |
| spec 13の閉集合記載 | `specs/13-safe-layout-application/spec.md` (Results節、baseline L255-261) | **`CANDIDATE_UNAVAILABLE` 未記載。本planで解消 (docs-only)** |
| ja解決test | `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` の `japaneseResourcesResolveEveryConcretePreviewString` (baseline L1473-1626) | 既存assert対象に #228由来keyが**1件も含まれない**。18 strings + 2 pluralsの追加が必要 |
| #228由来リソースのja値 | `lawnchair/res/values/strings.xml` と `lawnchair/res/values-ja/strings.xml` | 20件とも `values-ja` に存在。18 stringsはen≠ja、2 pluralsはjaが `other` のみ |

監査記録 ([docs/assessment/pr-289-organizer-missing-app-selection.md](../../docs/assessment/pr-289-organizer-missing-app-selection.md) §5) が
この2項目を「follow-upとして残存」と記録していることが出発点である
(同記録の「18 new keys」はstrings数の数え方の違いであり、resource単位の
正は本planの20件=18 strings+2 pluralsである)。

## 2. 変更内容と手順

### Step 1: spec 13 追記 (docs-only、本branchで草案済み)

- `specs/13-safe-layout-application/spec.md`:
  - 閉集合へ `| CANDIDATE_UNAVAILABLE` を `EXACT_PRECONDITION_FAILED` の次の行に
    追加 (`Results.kt` の宣言順序と一致させる)。
  - Change history 末尾へ2026-09-12付のentryを #185 precedent (L646-650) と
    同形式で追記 (issue #228由来 / PR #289でruntimeとcontract testは確定済み /
    本entryは正本記録のみ / 他のresult shape・lifecycle・behavior変更なし)。
  - frontmatter `updated:` を2026-09-12へ更新。
- 実装PRでは本branchの草案をrebaseして利用し、reviewで受理された時点で
  spec 13の追記が正本として確定する (準備タスクはacceptedと扱わない)。

### Step 2: ja解決test拡張 (test-only)

`japaneseResourcesResolveEveryConcretePreviewString` 内で既存様式を踏襲する。

1. **strings (18件)**: spec.md §対象のリストを既存 `addedPreviewStrings` へ
   追加する。既存の `assertNotEquals("string resource $id falls back to
   English under a Japanese locale", context.getString(id),
   japanese.getString(id))` パターンをそのまま使う (18件ともja≠en確認済みの
   ため成立する)。format文字列はformat引数なしのtemplate比較でよい
   (既存のformat文字列と同じ扱い)。
2. **placeholder survival (任意だが推奨)**: `manual_organization_preview_add_row`
   (引数2件) について、#208の `preview_move_row` 前例と同様に
   `japanese.getString(id, "A", "B")` が両引数を含むことをassertする。
   `preview_add_descriptor` (引数2件) も同様に扱ってよい。
3. **plurals (2件)**: #231ブロックの前例に従う。
   - ja解決: `japanese.resources.getQuantityString(id, 2, 2)` と
     `context.resources.getQuantityString(id, 2, 2)` の `assertNotEquals`
     (jaは `other` のみのためquantity 2で解決する)。
   - en数量区別: `context.resources.getQuantityString(id, 1, 1)` と
     `(id, 2, 2)` の `assertNotEquals` (enは `one`/`other` を持つ)。
   - 対象: `manual_organization_missing_apps_selected_count`,
     `manual_organization_applied_added_count`。
4. **配置とコメント**: 追加は既存リストの末尾付近へ集約し、
   `// Issue #228: missing-app selection and Add rows` のcommentを付ける
   (既存の `// Issue #230:` / `// Issue #231:` 前例と同じ)。
5. **他testへの影響**: 同file内の他test (例: #235由来の
   `previewDetailsHeaderShowsTheSeparateWidgetMoveCount`) は変更しない。

### Step 3: 検証

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
# API 36 emulator接続後 (CI lane organizer-instrumentation-issue52-tests と同じ):
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest
```

- CI: `organizer-instrumentation-issue52-tests` job
  (`ManualOrganizationProductionE2EInstrumentationTest` /
  `ManualOrganizationPreferencesInstrumentationTest` / `StrategyPicker...` /
  `MissingAppSelection...` を同一laneで実行) がgreenであること。
  api35 laneは既知の #292 flakeと無関係な本変更であることをPRに明記する。
- 本PRの差分はtest+docsのみであり、高リスク独立エビデンス契約
  (workflow: testのみの変更・docs-only PRは対象外) の対象外である。
  ただし通常の検証記録として `spotlessCheck` (`check-style`) と
  対象instrumentationの実行結果はPRへ正確に残す。

## 3. Migration / Rollback

- DB・schema・依存・permissionの変更なし。migration不要。
- rollbackはPR revertのみで完結する (docs+test-only)。revert時に
  runtime/stateへの残留影響はない。

## 4. 所有境界と並行作業

- 変更file: `specs/13-safe-layout-application/spec.md`,
  `specs/293-ja-resolution-test-and-rejection-set/{spec,plan}.md`,
  `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt`。
- spec 13は共有正本であるため、本IssueのPRと同時に他Issueがspec 13を
  変更しない。merge順が入れ替わった場合は再入場検証で閉集合の
  当該行を再確認する。
- issue #235のwidget系string追加 (PR #296/#302以降) との競合面は
  同一test fileのみ。行単位で独立しており、rebaseで解決できる範囲である。

## 5. 未確定事項

- なし (製品判断の未決はありません)。実装詳細の選択 (placeholder survival
  assertionの有無) は実装PR内で決定してよく、spec受入条件に影響しない。
