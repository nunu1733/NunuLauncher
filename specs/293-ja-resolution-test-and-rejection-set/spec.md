---
issue: "#293"
status: draft
requirements: []
updated: 2026-09-12
---

# issue #228 follow-up: ja解決test拡張とspec 13 `PreWriteRejection` 追記

> 本specは [Issue #293](https://github.com/nunu1733/NunuLauncher/issues/293) の
> maintenance/docs+test-only follow-upを拘束契約として定義する。要件の正本は
> [spec 228](../228-organizer-missing-app-selection/spec.md) (AC-12、[spec 123](../123-organizer-ui-convergence/spec.md)契約) と
> [spec 13](../13-safe-layout-application/spec.md) (`PreWriteRejection` 閉集合) であり、
> 本specはそれらを再定義せず、`specs/228` change historyが issue #293 へ明示委譲した
> 2項目の完了条件と矛盾しないことを拘束する。source実装・production振る舞いの変更はない。

## Problem

PR #289 (issue #228) のmerge前監査
([docs/assessment/pr-289-organizer-missing-app-selection.md](../../docs/assessment/pr-289-organizer-missing-app-selection.md) §5) が
非blocking follow-up 2件を記録した。

1. **ja-locale解決test (spec 228 AC-12)** — issue #228が追加した20リソース
   (18 strings + 2 plurals) は `values-ja` へのkey存在確認のみで、
   `ManualOrganizationPreferencesInstrumentationTest.japaneseResourcesResolveEveryConcretePreviewString`
   によるja configuration contextでの解決assert (en fallbackの検出) が未整備である。
   spec 123 AC-5/AC-6とspec 228 AC-12のtest oracle
   (「ja configuration contextでのstring解決test」) が機械的に固定されていない。
2. **spec 13の閉集合記載** — issue #228が追加した `PreWriteRejection.CANDIDATE_UNAVAILABLE`
   はruntime (`lawnchair/src/app/lawnchair/organizer/application/public/Results.kt`) と
   `tests/unit/app/lawnchair/organizer/application/contract/ApplyResultContractTest.kt`
   で固定済みだが、正本である spec 13 の閉集合定義に記載されていない
   (`Results.kt` のKDocは「Exactly the variants from spec.md」を要求する)。

## Outcome

- issue #228由来の全20リソースがja configuration contextで解決することを
  既存instrumentation testが機械的にassertする。
- spec 13 の `PreWriteRejection` 閉集合と change history が
  `CANDIDATE_UNAVAILABLE` を runtime / contract test と矛盾なく記載する。

## Scope

### 対象 (item 1: ja解決test拡張)

`tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
の `japaneseResourcesResolveEveryConcretePreviewString` へ、次のissue #228由来
リソースを追加する (2026-09-12時点の `lawnchair/res/values/strings.xml`、
`<!-- Issue #228: missing-app selection and Add rows -->` ブロックと
`unplaced_strategy_scope` / `selection_stale` / `candidate_unresolved` の3件を含む)。

strings (18件、既存の `addedPreviewStrings` リストへ追加):

- `manual_organization_detecting_missing_apps`
- `manual_organization_missing_apps_title`
- `manual_organization_missing_apps_empty`
- `manual_organization_missing_apps_search_hint`
- `manual_organization_missing_apps_select_all`
- `manual_organization_missing_apps_clear_all`
- `manual_organization_missing_apps_continue`
- `manual_organization_missing_apps_state_checked` (選択state checked)
- `manual_organization_missing_apps_state_unchecked` (選択state unchecked)
- `manual_organization_preview_unavailable_add`
- `manual_organization_preview_retry`
- `manual_organization_added_count`
- `manual_organization_group_added`
- `manual_organization_preview_add_descriptor`
- `manual_organization_preview_add_row`
- `manual_organization_unplaced_strategy_scope`
- `manual_organization_selection_stale`
- `manual_organization_candidate_unresolved`

plurals (2件、既存のplurals assertionパターンへ追加):

- `manual_organization_missing_apps_selected_count`
- `manual_organization_applied_added_count`

### 対象 (item 2: spec 13 追記、docs-only)

- `specs/13-safe-layout-application/spec.md` の `PreWriteRejection` 閉集合へ
  `CANDIDATE_UNAVAILABLE` を追記する (code上の宣言順序に従い
  `EXACT_PRECONDITION_FAILED` と `LOCK_STATE_UNAVAILABLE` の間)。
- change history へ #185 precedent と同形式で issue #228 / PR #289 由来の
  拒否コードであることを記録する。

### 非対象

- production source、`ApplyResultContractTest` を含むunit test、
  `ApplyProtocol` / `Results.kt` の変更 (いずれもPR #289で実施済み)。
- issue #235が後から追加したwidget系string
  (`manual_organization_widget_moved_count` 等) のja解決test追加
  (別scope。#235のAC-12相当の追跡が必要なら別Issueとする)。
- 上流Lawnchair文字列の翻訳、ja以外localeへの展開 (spec 123の非対象を引き継ぐ)。
- `PreWriteRejection` の意味論・順序・型shapeの変更 (記載の正本化のみ)。

## Acceptance criteria

| AC | 受入条件 | 必要evidence |
|---|---|---|
| AC-293-01 | 上記20リソースがja configuration contextで解決し、en fallbackしないことがinstrumentation testで固定される。pluralsはja (`other` のみ) での解決に加え、enの `one`/`other` 数量の区別を既存パターンと同様にassertする。 | `connectedLawnWithQuickstepGithubDebugAndroidTest` (対象class指定) のgreen logとCI run |
| AC-293-02 | spec 13 が `CANDIDATE_UNAVAILABLE` を閉集合として記載し、`Results.kt` と `ApplyResultContractTest.kt` の宣言と矛盾しない。change historyへ #185 precedentと同形式の記録がある。 | spec 13 diffとruntime宣言の突合 |
| AC-293-03 | 変更はdocs+test-onlyであり、production source・DB・依存に差分がない。 | PR diffのfile list |

## Constraints

- ja値がenと等価なリソースを `assertNotEquals` パターンへ追加しない
  (2026-09-12時点で18 stringsはすべてja≠enを確認済み。将来の翻訳変更で
  衝突が生じた場合は、当該keyのassert形式を既存pluralsの「解決すること」
  assertionへ寄せ、en fallback検出を弱めない)。
- 既存testのassertion様式 (context vs ja context、failure message、
  pluralsの `getQuantityString(id, 2, 2)` / en one-vs-other区分) を踏襲する。
- 新しいdependency・permission・通信を追加しない。

## Change history

- 2026-09-12: Issue #293のSpec/Plan準備タスクとしてdraft作成
  (baseline `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4`)。
  要求の正本確認: spec 228 AC-12とそのtest oracle、PR #289監査記録 §5、
  spec 13閉集合 (L255-) と change history #185 precedent (L646-)。
