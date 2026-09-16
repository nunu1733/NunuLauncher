---
issue: "#293"
status: draft
requirements: []
updated: 2026-09-16
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
リソースを追加する (2026-09-16時点、baseline `4f555450bdf817a832b8827857b5af54f41913a8`
で再確認済みの `lawnchair/res/values/strings.xml`
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
  `CANDIDATE_UNAVAILABLE` を追記する。挿入位置は spec 13テキスト上
  `EXACT_PRECONDITION_FAILED` の直後 (`LOCK_STATE_UNAVAILABLE` の前) であり、
  `Results.kt` での宣言順序 (`EXACT_PRECONDITION_FAILED` の次が
  `CANDIDATE_UNAVAILABLE`) と一致する。
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
  (2026-09-16時点 (baseline `4f555450bdf817a832b8827857b5af54f41913a8`) で
  18 stringsはすべてja≠enを再確認済み。将来の翻訳変更で
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
- 2026-09-13: baseline `origin/main` = `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda`
  で再入場検証。baseline以降の#283/#300/#308等による同一test fileの変更
  (#300 window-focus gate helpers、#308 `awaitDisplayed` 追加) で
  `japaneseResourcesResolveEveryConcretePreviewString` 自体は内容不変のまま
  L1606-1748へ移動、#228由来リソース・spec 13・`Results.kt`・
  `ApplyResultContractTest.kt`・spec 228・監査記録に影響する変更はなし。
  挿入位置の記載を宣言順序に対してより正確に整理 (契約内容の変更なし)。
- 2026-09-14: baseline `origin/main` = `397d3fd95764878366e7c9e5ce41ab65e6f3f9ca`
  で再入場検証。前回baseline以降の54 commit (#299/#298/#315系のrestore
  capture・diagnostics evidence work) により `specs/299-*` / `specs/298-*` /
  `specs/315-*` とruntimeの一部が追加・変更されたが、#228由来20リソース
  (18 stringsはja≠enのまま、2 pluralsはen `one`/`other` vs ja `other`)、
  `japaneseResourcesResolveEveryConcretePreviewString` (L1606-1748、内容不変、
  #228keyは依然0件)、spec 13閉集合 (L255-261、`CANDIDATE_UNAVAILABLE` 未記載のまま)、
  `Results.kt` (L83)、`ApplyResultContractTest.kt` (L72)、spec 228 change
  historyの#293委譲、監査記録 §5 はすべて不変。`.github/workflows/ci.yml` は
  #299用 `organizer-instrumentation-issue299-tests` laneの追加のみで、
  `organizer-instrumentation-issue52-tests` laneと `final-status` gate構成は不変。
  契約内容の変更なし。
- 2026-09-15: baseline `origin/main` = `0cf82bc1e61c1874b280a7120dff9594be4fef71`
  で再入場検証。前回baseline以降の66 commit (#203 personalization signal、
  #204 AI personalization context/intent、#298 reload thread affinity実装) により
  `specs/203-*` / `specs/204-*` / runtime (`strings.xml` への#203文字列追加で
  `values` 側 `<!-- Issue #228 -->` ブロックがL1166→L1173へ後方移動) および
  `tests/organizer-instrumentation` への新test file追加 (#203 probe test 2件、
  #298 `RestoreLeaseDeferredLoaderThreadAffinityTest`) があったが、対象test fileは
  前回baselineとbit単位で同一 (`japaneseResourcesResolveEveryConcretePreviewString`
  L1606-1748、#228keyは0件のまま)、#228由来20リソース (18 strings ja≠en、
  2 plurals en `one`/`other` vs ja `other`)、spec 13閉集合 (L255-261、
  `CANDIDATE_UNAVAILABLE` 未記載のまま)、`Results.kt` (L83)、
  `ApplyResultContractTest.kt` (L72)、spec 228 change historyの#293委譲、
  監査記録 §5 はすべて不変。`.github/workflows/ci.yml` は #298用の
  shared-writer lane (L243) へのtest class追加 (in-place編集、行数不変) のみで、
  lane行番号 (L375/L432) と `final-status` gate (L662/L672) は不変。
  なお issue #292 (api35 flake追跡) はPR #297で修正され2026-09-12にclose済み
  (plan Step 3のflake注意書きを本日更新)。契約内容の変更なし。
- 2026-09-16: baseline `origin/main` = `4f555450bdf817a832b8827857b5af54f41913a8`
  で再入場検証。前回baseline以降の33 commitはすべて #205 (External Agent
  Exchange: spec/plan、exchange系runtime/UI、PR #325/#326) であり、
  `strings.xml` / `values-ja/strings.xml` への `exchange_*` 文字列追加は
  純追加 (values L1289-、values-ja L378-、いずれも#228ブロックより後方) で
  `values` 側 `<!-- Issue #228 -->` ブロックはL1173のまま。対象test fileは
  前回baselineとbit単位で同一 (`japaneseResourcesResolveEveryConcretePreviewString`
  L1606-1748、#228keyは0件のまま)、#228由来20リソース (18 strings ja≠en、
  2 plurals en `one`/`other` vs ja `other`)、spec 13 (閉集合L255-261、
  `CANDIDATE_UNAVAILABLE` 未記載のまま、#205はspecs/13に無変更)、`Results.kt`
  (L83、L74の `EXACT_PRECONDITION_FAILED` とL90の `OVERLAP_POLICY_REJECTED`
  の間)、`ApplyResultContractTest.kt` (L72)、spec 228 change historyの#293委譲、
  監査記録 §5 (L101-105)、spec 123 AC-5/AC-6 (L132-133) はすべて不変。
  `.github/workflows/ci.yml` は前回baseline以降無変更 (api35 L375、
  issue52 lane L432、`final-status` L662/L671-672)。#205による
  `CONTEXT.md` / `DESIGN.md` / `docs/product/requirements.md` の変更は
  本Issueの対象語彙・契約に無関係。契約内容の変更なし。
