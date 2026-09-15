---
issue: "#293"
status: draft
spec: ./spec.md
updated: 2026-09-15
---

# Plan: issue #228 follow-up (ja解決test拡張とspec 13 `PreWriteRejection` 追記)

> Baseline: `origin/main` = `0cf82bc1e61c1874b280a7120dff9594be4fef71`
> (2026-09-15再検証時点。初版draft時のbaselineは
> `f9afd8bfde121932c0c8ed965225d52a84d86ab4`)。本planは spec.md (**draft**)
> に対応し、記載の実装状態はすべてbaseline上での実確認に基づく。
> **実装開始前に再入場検証を行うこと** (spec.mdの参照先がbaseline以降に
> 変更されていないか、PR #289以降の関連diffの有無)。
>
> 2026-09-13再入場検証結果: 初版baseline以降のmain差分 (33 commit) のうち
> 対象test fileへの変更は #300 (window-focus gate helpers) と #308
> (`awaitDisplayed` 等) であり、`japaneseResourcesResolveEveryConcretePreviewString`
> 自体は内容不変のまま後方へ移動 (旧L1473→現L1606)。#228由来20リソース、
> spec 13、`Results.kt`、`ApplyResultContractTest.kt`、spec 228、
> 監査記録 §5 に影響する変更はなし。`.github/workflows/ci.yml` は #304系で
> 変更されているが `organizer-instrumentation-issue52-tests` laneと
> `final-status` gate構成は不変。
>
> 2026-09-14再入場検証結果: 前回baseline (`c5274b5d0d`) 以降の54 commit
> (#299 restore capture、#298 reload thread affinity、#315 failure evidence
> capture) により `specs/299-*` / `specs/298-*` / `specs/315-*`、runtime
> (`NovaBackupConverter.kt`、`LayoutApplicationModule.kt`、`LauncherModel.java`、
> `LoaderTask.java` 等) および `tests/unit` / `tests/organizer-instrumentation`
> の一部が変更されたが、いずれも本planの対象file・行番号に影響しない。
> `japaneseResourcesResolveEveryConcretePreviewString` (L1606-1748)、
> spec 13閉集合 (L255-261)、`Results.kt` (L83)、`ApplyResultContractTest.kt`
> (L72)、spec 228 change historyの#293委譲、監査記録 §5 (L101-105) は
> すべて不変。`.github/workflows/ci.yml` は #299用
> `organizer-instrumentation-issue299-tests` laneの追加のみで、
> `organizer-instrumentation-issue52-tests` lane (L432) と `final-status`
> gate (L662、L672で同laneを包含)、api35 lane (L375) は不変。
> 以下の行番号は現baseline (`397d3fd0`) 基準
> (2026-09-14再確認、前回baseline `c5274b5d0d` と同値)。
>
> 2026-09-15再入場検証結果: 前回baseline (`397d3fd0`) 以降の66 commit
> (#203 personalization signal snapshot、#204 AI personalization
> context/intent exchange、#298 reload thread affinity) により `specs/203-*` /
> `specs/204-*`、runtime (`strings.xml` への #203 personalization文字列7件追加で
> `values` 側 `<!-- Issue #228 -->` ブロックがL1166→L1173へ後方移動、
> `Results.kt` 等のorganizer application層は変更なし) および
> `tests/organizer-instrumentation` への新規test file追加 (#203 probe test 2件、
> #298 `RestoreLeaseDeferredLoaderThreadAffinityTest`) があったが、
> いずれも本planの対象file・行番号に影響しない。対象test fileは前回baselineと
> bit単位で同一 (`japaneseResourcesResolveEveryConcretePreviewString`
> L1606-1748、#228keyは0件のまま)、#228由来20リソース (18 stringsはja≠enのまま、
> 2 pluralsはen `one`/`other` vs ja `other`)、spec 13閉集合 (L255-261、
> `CANDIDATE_UNAVAILABLE` 未記載のまま)、`Results.kt` (L83、L74の
> `EXACT_PRECONDITION_FAILED` とL90の `OVERLAP_POLICY_REJECTED` の間)、
> `ApplyResultContractTest.kt` (L72)、spec 228 change historyの#293委譲、
> 監査記録 §5 (L101-105) はすべて不変。`.github/workflows/ci.yml` は #298用の
> shared-writer lane (L243) へのtest class追加 (in-place編集、行数不変) のみで、
> `organizer-instrumentation-issue52-tests` lane (L432)、`final-status` gate
> (L662、L672で同laneを包含)、api35 lane (L375) は行番号含め不変。
> なお issue #292 (api35 laneの `TwoPanelOrientationCaptureInstrumentationTest`
> flake追跡) はPR #297 (fix `56c5fec7`、`7419579006`、merge `f95fbfed3d`) で
> 修正され、2026-09-12にCOMPLETEDでclose済み。Step 3のapi35注意書きを本日更新した。
> 以下の行番号は現baseline (`0cf82bc1`) 基準 (2026-09-15再確認、
> 前回baseline `397d3fd0` と同値)。

## 1. 現状の実装と不足 (baseline確認済み)

| 要素 | 場所 | 状態 |
|---|---|---|
| `PreWriteRejection.CANDIDATE_UNAVAILABLE` | `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt` (enum宣言 L71-、`CANDIDATE_UNAVAILABLE` L83、`EXACT_PRECONDITION_FAILED` と `OVERLAP_POLICY_REJECTED` の間) | 存在 (PR #289)。KDocが「Exactly the variants from spec.md」を要求 |
| contract testによる固定 | `tests/unit/app/lawnchair/organizer/application/contract/ApplyResultContractTest.kt` (L72) | 存在 |
| spec 13の閉集合記載 | `specs/13-safe-layout-application/spec.md` (Results節、L255-261) | **`CANDIDATE_UNAVAILABLE` 未記載。本planで解消 (docs-only)** |
| ja解決test | `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` の `japaneseResourcesResolveEveryConcretePreviewString` (L1606-1748) | 既存assert対象に #228由来keyが**1件も含まれない**。18 strings + 2 pluralsの追加が必要 |
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
  - Change history 末尾へentryを #185 precedent (L645-650) と
    同形式で追記 (issue #228由来 / PR #289でruntimeとcontract testは確定済み /
    本entryは正本記録のみ / 他のresult shape・lifecycle・behavior変更なし)。
  - frontmatter `updated:` を本追記の実施日へ更新。
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
  かつてapi35 laneを断続的に失敗させていた #292 flakeはPR #297で修正され
  issue #292は2026-09-12にclose済みであるため、api35 laneの失敗が発生した場合は
  本変更とは無関係な新規要因として分離・記録する。
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
  実績として、初版draft以降の #300/#308 も同一fileを変更したが
  ja解決test領域 (L1606-1748) には触れておらず、本planの追加点との
  実際の競合は発生していない。

## 5. 未確定事項

- なし (製品判断の未決はありません)。実装詳細の選択 (placeholder survival
  assertionの有無) は実装PR内で決定してよく、spec受入条件に影響しない。
