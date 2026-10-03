---
issue: "#293"
status: implemented
requirements: []
updated: 2026-10-03
---

# issue #228 follow-up: ja解決test拡張とspec 13 `PreWriteRejection` 追記

Risk tier: **L** ([docs/project/github-workflow.md](../../docs/project/github-workflow.md)
Risk tiers、Issue #444運用。test拡張 + docs-only追記であり、高リスクpath・
新規書込み経路・migrationに触れない。階層LはPRのみで足りるため本specは
実装PRの判断資料であり、acceptedを必要としない)。

> 本specは [Issue #293](https://github.com/nunu1733/NunuLauncher/issues/293) の
> maintenance/docs+test-only follow-upについて、実装PR向けのre-entry/完了チェック
> 資料である (Risk tier Lのため本specのacceptedは要求しない)。要件の正本は
> [Issue #293](https://github.com/nunu1733/NunuLauncher/issues/293) の終了条件、
> [spec 228](../228-organizer-missing-app-selection/spec.md) (AC-12、[spec 123](../123-organizer-ui-convergence/spec.md)契約) と
> [spec 13](../13-safe-layout-application/spec.md) (`PreWriteRejection` 閉集合) であり、
> 本specはそれらを再定義せず、`specs/228` change historyが issue #293 へ明示委譲した
> 2項目の完了条件と矛盾しないことを確認対象とする。source実装・production振る舞いの変更はない。

## Problem

PR #289 (issue #228) のmerge前監査
([docs/assessment/pr-289-organizer-missing-app-selection.md](../../docs/assessment/pr-289-organizer-missing-app-selection.md) §5) が
非blocking follow-up 2件を記録した。

1. **ja-locale解決test (spec 228 AC-12)** — issue #228が追加したリソース
   (PR #289時点で18 strings + 2 plurals。うち
   `manual_organization_missing_apps_empty` は #369実装 (2026-09-20の
   spec 228 Amend、TO-BE D-06「0件なら選択面を表示せず続行」) で削除され、
   現mainには **17 strings + 2 plurals = 19リソース** が残存) は
   `values-ja` へのkey存在確認のみで、
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

- issue #228由来で現mainに残存する全19リソース (17 strings + 2 plurals) が
  ja configuration contextで解決することを
  既存instrumentation testが機械的にassertする。
- spec 13 の `PreWriteRejection` 閉集合と change history が
  `CANDIDATE_UNAVAILABLE` を runtime / contract test と矛盾なく記載する。

## Scope

### 対象 (item 1: ja解決test拡張)

`tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
の `japaneseResourcesResolveEveryConcretePreviewString` へ、次のissue #228由来
リソースを追加する (2026-10-03時点、baseline `10ee58336e8848e0c7723d36d249600524f18a80`
で再確認済みの `lawnchair/res/values/strings.xml`
`<!-- Issue #228: missing-app selection and Add rows -->` ブロック (L1316、
`values-ja` 側はL388) と `unplaced_strategy_scope` / `selection_stale` /
`candidate_unresolved` の3件を含む。PR #289時点に存在した
`manual_organization_missing_apps_empty` は #369実装でvalues/values-jaともに
削除済みのため対象外)。

strings (17件、既存の `addedPreviewStrings` リストへ追加):

- `manual_organization_detecting_missing_apps`
- `manual_organization_missing_apps_title`
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
  `CANDIDATE_UNAVAILABLE` を追記する。挿入位置は可読性のため
  `EXACT_PRECONDITION_FAILED` の直後とする (`Results.kt` の宣言でも
  `CANDIDATE_UNAVAILABLE` は `EXACT_PRECONDITION_FAILED` の直後に置かれている)。
  契約は閉集合の **membership一致** であり列挙順は契約ではない
  (spec 13の列挙順はruntimeの宣言順と全体として一致しておらず、
  `ApplyResultContractTest.everyPreWriteRejectionVariantExists` もset比較で
  順序を契約化していない)。順序合わせのための既存行の並べ替えや
  order assertionの追加は行わない。
- change history へ #185 precedent と同形式で issue #228 / PR #289 由来の
  拒否コードであることを記録する。

### 非対象

- production source、`ApplyResultContractTest` を含むunit test、
  `ApplyProtocol` / `Results.kt` の変更 (いずれもPR #289で実施済み)。
- issue #235が後から追加したwidget系string
  (`manual_organization_widget_moved_count` 等)、issue #336が追加した
  `manual_organization_rejection_invalid_category_provenance`
  (2026-10-03時点 (baseline `10ee58336e8848e0c7723d36d249600524f18a80`) でも
  引き続きvalues側のみに存在しvalues-ja未整備)、issue #417が追加した
  `manual_organization_missing_apps_empty_continue` (0件続行の明示告知。
  en/jaとも存在するが#417由来であり#228由来ではない)、およびissue #443が
  追加した `exchange_ai_consultation_toggle_label` /
  `exchange_ai_consultation_toggle_description` (AI相談凍結toggle。2026-10-03時点で
  values側のみでvalues-ja未整備。manual_organization/exchange系の
  values-only欠落は引き続きこの3件のみで、前回baseline以降の追加
  (#449/#450/#451/#452/#497系) はすべてja対応済み) のja解決test追加
  (別scope。各IssueのAC-12相当の追跡が必要なら別Issueとする)。
- 上流Lawnchair文字列の翻訳、ja以外localeへの展開 (spec 123の非対象を引き継ぐ)。
- `PreWriteRejection` の意味論・順序・型shapeの変更 (記載の正本化のみ)。

## Acceptance criteria

| AC | 受入条件 | 必要evidence |
|---|---|---|
| AC-293-01 | 上記19リソースがja configuration contextで解決し、en fallbackしないことがinstrumentation testで固定される。pluralsはja (`other` のみ) での解決に加え、enの `one`/`other` 数量の区別を既存パターンと同様にassertする。 | `connectedLawnWithQuickstepGithubDebugAndroidTest` (対象class指定) のgreen logとCI run |
| AC-293-02 | spec 13 が `CANDIDATE_UNAVAILABLE` を閉集合として記載し、`Results.kt` と `ApplyResultContractTest.kt` の宣言と矛盾しない。change historyへ #185 precedentと同形式の記録がある。 | spec 13 diffとruntime宣言の突合 |
| AC-293-03 | 変更はdocs+test-onlyであり、production source・DB・依存に差分がない。 | PR diffのfile list |

## Constraints

- ja値がenと等価なリソースを `assertNotEquals` パターンへ追加しない
  (2026-10-03時点 (baseline `10ee58336e8848e0c7723d36d249600524f18a80`) で
  17 stringsはすべてja≠enを再確認済み。将来の翻訳変更で
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
- 2026-09-17: baseline `origin/main` = `703afe3f4c1f5387f768832ea422c7b681c3775a`
  で再入場検証。前回baseline以降の67 commit (#329 Import Normalizer、
  #330 partial intent authoring、#331 exchange target scope coupling、
  #332 Exchange import input UI、#336 user-defined categories、#345 import
  device evidence) により対象test fileが #300/#308 (2026-09-13確認分)
  以来再び変更された (`planningResult()` fixture helperの `taxonomy` 引数が
  `catalog = ActiveCategoryCatalog(...)` 形式へ変更、差分はL2502以降) が、
  `japaneseResourcesResolveEveryConcretePreviewString` (L1606-1748) は
  内容不変で#228keyは0件のまま。#228由来20リソースも不変 (18 strings ja≠en、
  2 plurals en `one`/`other` vs ja `other`)。なお#336が追加した
  `manual_organization_rejection_invalid_category_provenance` はvalues側のみ
  (L1160) に存在しvalues-jaには存在しないため本specの対象外とすることを
  Non-goalsへ明記。この追加によりvalues側 `<!-- Issue #228 -->` ブロックは
  L1173→L1174へ後方移動 (values-ja側はL260のまま)。spec 13閉集合
  (L255-261、`CANDIDATE_UNAVAILABLE` 未記載のまま)、`Results.kt` (L83、
  L74の `EXACT_PRECONDITION_FAILED` とL90の `OVERLAP_POLICY_REJECTED` の間)、
  `ApplyResultContractTest.kt` (L72)、spec 228 change historyの#293委譲
  (L17/L285)、監査記録 §5 (L101-105)、spec 123 AC-5/AC-6 (L132-133) は
  すべて不変。`.github/workflows/ci.yml` は #332用
  `organizer-instrumentation-issue332-tests` laneの追加のみ (additive) で、
  api35 lane (L375) と issue52 lane (L432) は行番号含め不変、
  `final-status` gateはL662→L710へ移動しissue332 laneをneedsへ追加したが
  既存lane構成は不変。#329〜#336による `CONTEXT.md` / `DESIGN.md` /
  `docs/product/requirements.md` (FR-010) の変更は本Issueの対象語彙・契約に
  無関係。契約内容の変更なし。
- 2026-09-19: baseline `origin/main` = `3076bdae7ebf8dbb086f251203968c06e9986258`
  で再入場検証。前回baseline以降の70 commit (#327 interview-first exchange
  instruction、#328 import success state、#337 exchange category group
  proposals v4、#348 AI-facing contract、#356 Organizer AS-IS監査 /
  #361 Organizer TO-BE UXのdocs各PR) によりexchange系runtime・test、
  `specs/327-*` / `specs/328-*` / `specs/337-*` / `specs/348-*`、
  `specs/336-*` (spec 337 v4へのcross-reference改訂のみ) が変わったが、
  対象test fileは前回baselineとbit単位で同一 (blob `c0fa5d459dd6`,
  `japaneseResourcesResolveEveryConcretePreviewString` L1606-1748、
  #228keyは0件のまま)。#228由来20リソースも不変 (18 stringsは機械突合で
  18/18がja≠en、2 pluralsはen `one`/`other` vs ja `other` only)。range内の
  `strings.xml` / `values-ja/strings.xml` 差分は `exchange_*` 領域
  (values L1290-以降 / values-ja L378-以降) に限られ、range内で追加・変更された
  43 nameはすべてvalues-ja対応済み (新たなvalues-only欠落は発生していない)。
  spec 13閉集合 (L255-261、`CANDIDATE_UNAVAILABLE` 未記載のまま)、
  `Results.kt` (L83、L74の `EXACT_PRECONDITION_FAILED` とL90の
  `OVERLAP_POLICY_REJECTED` の間)、`ApplyResultContractTest.kt` (L72)、
  spec 228 change historyの#293委譲 (L17/L285)、監査記録 §5 (L101-105)、
  spec 123 AC-5/AC-6 (L132-133) はすべて不変 (前記6fileは分岐基点
  `f9afd8bfde12` から現mainまでblob単位で無変更のため、本branchの
  spec 13草案は現mainへそのまま適用可能)。`.github/workflows/ci.yml` は
  issue52 lane scriptへの2 class追記 (`ExchangeImportSuccessInstrumentationTest`、
  `StrategyPickerFreezeInstrumentationTest`、in-place 1行編集) のみで、
  api35 lane (L375) / issue52 lane (L432) / `final-status` gate (L710) の
  行位置は不変、issue52 laneは引き続き対象test classを実行する。
  `CONTEXT.md` / `DESIGN.md` / `docs/product/requirements.md` の変更は
  本Issueの対象語彙・契約に無関係。契約内容の変更なし。
- 2026-09-28: baseline `origin/main` = `c5a7840b880ed4c436b67170930ca87d4ef7f148`
  で再入場検証。前回baseline以降の419 commitを調査し、**対象key集合を初めて
  実質更新**した。(1) #369実装 (commit `5b138bd33d`、spec 228は2026-09-20に
  Amend済み: 0候補時は選択面を表示せずcapture/planへ続行) により
  `manual_organization_missing_apps_empty` がvalues/values-jaともに削除された
  ため、対象を18 strings + 2 plurals = 20リソースから **17 strings + 2 plurals
  = 19リソース** へ縮小 (17 stringsのja≠enは機械突合で17/17、2 pluralsは
  en `one`/`other` vs ja `other`のみ、を現baselineで再確認)。
  (2) #417が `manual_organization_missing_apps_empty_continue` (en/jaとも存在)、
  #443が `exchange_ai_consultation_toggle_label` / `_description`
  (values側のみ) を追加 — いずれも#228由来ではないためNon-goalsへ記録
  (現mainのmanual_organization/exchange系values-only欠落は#443の2件と
  #336のprovenanceの計3件)。(3) 対象test fileは #417/#443のjourney test等の
  追加で再び大きく変化したが、`japaneseResourcesResolveEveryConcretePreviewString`
  自体は内容不変のままL1606-1748からL2124-2267へ移動、#228keyは依然0件
  (L2124-2267を機械検索して0 match)。(4) CIが #422 (impact-based CI portfolio)
  で再構成され、旧 `organizer-instrumentation-issue52-tests` laneは廃止。
  対象classは新lane `organizer-instrumentation-manual-organization-ui-tests`
  (API 36、runner `tools/ci/run-manual-organization-ui-instrumentation.sh` の
  classlistに含まれる) が実行し、`surface_organizer_ui` (対象test pathを含む)
  でtest-only変更もself-triggerする。`final-status` gate (L1011) は同laneを
  needsに含む。(5) #444によりworkflowへRisk tiersが導入され、本Issueは
  **階層L** (test+docs-only、高リスクpath不変) と判定して冒頭へ明記した
  (階層LはPRのみで足りるため本spec/planは判断資料扱い)。
  (6) spec 13 (閉集合L255-261、`CANDIDATE_UNAVAILABLE` 未記載のまま)、
  `Results.kt` (L83、L74/L90間)、`ApplyResultContractTest.kt` (L72)、
  spec 228 change historyの#293委譲 (L17/L285)、監査記録 §5 (L105)、
  spec 123 AC-5/AC-6 (L132-133) はいずれも分岐基点 `f9afd8bfde12` から
  現mainまでblob単位で無変更のため、本branchのspec 13草案は現mainへ
  そのまま適用可能。`values` 側 `<!-- Issue #228 -->` ブロックはL1246、
  values-ja側はL320へ移動。
- 2026-10-03: baseline `origin/main` = `10ee58336e8848e0c7723d36d249600524f18a80`
  で再入場検証。前回baseline以降の173 commit (#449複数選択編集画面、#450 edit
  undo、#451重複超過保持、#452整理入口、#479 hub row可視性、#497新規アプリ配置先、
  #504/#505 organizer run publication thread等) を調査した。**契約内容の変化は
  ないが、対象test file・res・CIの行位置が更新された**。
  (1) 対象test fileへ14行追加 (#504系列の `FakeApplication` への #449/#450
  seam実装、L2987以降) があったが、`japaneseResourcesResolveEveryConcretePreviewString`
  (L2124-2267) は内容不変で#228keyは0件のまま (機械検索0 match)。
  (2) `strings.xml` / `values-ja/strings.xml` は #497/#450/#449/#452/#451系の
  追加により `values` 側 `<!-- Issue #228 -->` ブロックがL1246→L1316、
  values-ja側がL320→L388へ後方移動。19リソース (17 strings + 2 plurals) は
  すべてvalues/values-jaに存在し、17 stringsは機械突合で17/17がja≠en、
  2 pluralsはja `other` のみ。range内の追加nameでvalues-ja欠落は0件
  (新たなvalues-only欠落なし、#336/#443の3件のみが既存のまま)。
  (3) CIはlane構成に変化なし: `organizer-instrumentation-manual-organization-ui-tests`
  (L648へ移動、API 36) が対象classを実行し、`final-status` gateはL1023へ移動
  (needsに同lane、L1033)。runner script
  (`tools/ci/run-manual-organization-ui-instrumentation.sh`) は #479 review
  round 3 によりdrag-guard oracleの第二invocationが追加されたが、対象classは
  第一invocationのclasslistに残存。`surface_organizer_ui` (対象test pathを含む)
  でのself-trigger条件も不変。
  (4) spec 13 (閉集合L255-261、`CANDIDATE_UNAVAILABLE` 未記載のまま)、
  `Results.kt` (L40 KDoc、L74/L83/L90)、`ApplyResultContractTest.kt` (L72)、
  監査記録 §5、spec 123 AC-5/AC-6 は分岐基点 `f9afd8bfde12` から現mainまで
  blob単位で無変化 (spec 228 specのみ前回分の#369 Amendまででblob変化済み、
  前回baselineからの変化はなし。#293委譲は不変: L17/L285)、
  本branchのspec 13草案は現mainへそのまま適用可能。契約内容の変更なし。
- 2026-10-03: Phase 1 review round 1 (ChatGPT、
  [コメント](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966185292))
  対応。(1) 中: Issue本文の終了条件「docs-only PRでmerge」が docs+test-only
  scopeと矛盾する指摘により、Issue本文の終了条件を
  「docs+test-only PRでmerge (production source・DB・依存に差分がない)」へ更新
  (AC-293-03は元からdocs+test-only語彙のため語彙一致)。
  (2) 中: spec 13追記の「`Results.kt` の宣言順序と一致」表現が実体より強い指摘を
  受け、§対象 item 2 と plan Step 1 を「挿入位置は可読性のための位置、契約は
  閉集合のmembership一致で列挙順は契約ではない、並べ替え/order assertion追加は
  行わない」へ修正。(3) 低: 冒頭の「拘束契約として定義する」を
  「実装PR向けのre-entry/完了チェック資料 (accepted不要)」へ弱め、
  正本をIssue終了条件 + spec 228 AC-12 + spec 13へ一本化。
- 2026-10-03: 実装完了 (impl branch `issue-293-impl`)。
  (1) AC-293-01: `japaneseResourcesResolveEveryConcretePreviewString` へ
  19リソース (17 strings + 2 plurals) を追加 (17 stringsはen fallback検出の
  `assertNotEquals`、`preview_add_row` / `preview_add_descriptor` は#208前例の
  placeholder survival assert、2 pluralsは#231前例のja解決 + en one-vs-other
  assert)。ローカル検証: `./gradlew spotlessCheck` green
  (spotless対象は`lawnchair/src` / `tests/unit`であり、本件の
  `tests/organizer-instrumentation`は対象外)、
  `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest`
  がAPI 36 emulator (Android 16) で **56 tests / 0 failures**。
  CI結果 (lane `organizer-instrumentation-manual-organization-ui-tests`) はPRで記録する。
  (2) AC-293-02: spec 13へ `| CANDIDATE_UNAVAILABLE` を `EXACT_PRECONDITION_FAILED`
  の直後に追記し、change history entryを#185 precedent形式で追加
  (membership契約、順序合わせの並べ替えなし)。
  (3) AC-293-03: 差分は対象test file + spec 13 + `specs/293-*` のみで、
  production source・DB・依存に差分なし。

- 2026-10-03: **status を implemented へ移行**。実装PR
  [#511](https://github.com/nunu1733/NunuLauncher/pull/511) merge (merge commit
  `eb831de5e5ec54818621eb9da8dd182879e61139`)。AC evidence:
  AC-293-01 — 対象class実行 (ローカル API 36 emulator: 56 tests / 0 failures)
  およびCI lane
  [`organizer-instrumentation-manual-organization-ui-tests`](https://github.com/nunu1733/NunuLauncher/actions/runs/37106454561/job/111155887655)
  green (head `89558a6c56`。初回attemptは #418 既知flake
  `length=320; SlotWriter.moveSlotGapTo` + process crash のため `--failed` 再実行、
  発生記録は [Issue #418コメント](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5966710958))。
  AC-293-02 — spec 13閉集合へ `CANDIDATE_UNAVAILABLE` 追記 + change history記録、
  `Results.kt` / `ApplyResultContractTest.kt` とのmembership突合は
  [独立監査](../../docs/assessment/pr-511-293-followup.md) (verdict GO) で確認。
  AC-293-03 — diff 4 file (test 1 + spec 3) でproduction source / res /
  CI / dependencyの変更なし。全終了条件を満たし Issue #293 はCOMPLETEDでclose
  ([close記録](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966936853))。
