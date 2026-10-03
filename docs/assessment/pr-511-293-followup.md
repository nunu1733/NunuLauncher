# Independent audit: PR #511 issue #228 follow-up（ja解決test拡張とspec 13 `PreWriteRejection` 追記、#293）

> Status: **GO**（下記「Verification matrix」「CI run status」「未確認範囲」参照）
> Audit date: 2026-10-03

- Auditor: **独立session**（本recordを作成した監査者）。本PRの実装・review・検証実行・
  spec/plan起草のいずれにも関与していない。作業tree（branch `issue-293-impl`、
  `git rev-parse HEAD` = 監査対象SHA、tracked変更なし）で read-only 照合と
  `spotlessCheck` 再実行のみを行い、本record以外のfileは作成・変更していない
  （commit・pushも行っていない）。
- PR: https://github.com/nunu1733/NunuLauncher/pull/511 （head branch `issue-293-impl`、
  base `main`。tier L の docs+test-only 変更につき `risk: layout-data` /
  `risk: migration` label は付与されておらず（本PRのlabelは0件。API突合済み）、
  高リスクPRの独立エビデンス要件（`high-risk-gate` 機械検証）の対象外。
  参考として同CI上の `high-risk-evidence` check自体は SUCCESS を返している。
  本recordは要件ではなく、owner workflowに任意に追加した監査証跡である）
- 対象head SHA: `726e871209687856c98d803568d6674384c8e114`
  （`gh pr view 511 --json headRefOid` と `git rev-parse origin/issue-293-impl`、
  作業tree HEAD の3者が一致。merge-base `origin/main...origin/issue-293-impl` =
  `10ee58336e88` = 監査時点の main tip であり、rebase drift なし。
  PR commits: `d133f5ca69` spec/plan起草、以降 baseline別 re-entry refresh 6件、
  `9b004b3e25` Phase 1 review round 1対応、`8eddec8cf1` merge、
  `780f0c48e5` test拡張、`805c36d41c` spec 13追記、`726e871209` 実装evidence記録）
- Diff: `git diff origin/main...origin/issue-293-impl` = **4 files, +783/-1**。
  `specs/13-safe-layout-application/spec.md`（+10/-1）、
  `specs/293-ja-resolution-test-and-rejection-set/spec.md`（新規 +343）、
  `specs/293-ja-resolution-test-and-rejection-set/plan.md`（新規 +367）、
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
  （+63）。**production source（`lawnchair/src/**`）、resource本体（`lawnchair/res/**`）、
  CI config、dependency/build設定は diff に一切出現しない**
  （name-status全件照合と `--stat` の path grep で機械確認。`git diff --check` も clean）。

## Criteria

- [specs/293 spec](../../specs/293-ja-resolution-test-and-rejection-set/spec.md)
  AC-293-01〜03（draft、tier L運用の判断資料。正本は Issue #293 終了条件 +
  spec 228 AC-12 + [spec 13](../../specs/13-safe-layout-application/spec.md)
  `PreWriteRejection` 閉集合、というauthority整理はspec冒頭に明記済み）
- [specs/293 plan](../../specs/293-ja-resolution-test-and-rejection-set/plan.md)
  Step 1〜3

## Diff scope の機械検証

- 変更fileは上記4件のみ（`git diff --name-status` 全件照合）。
  `lawnchair/src` / `lawnchair/res` / `.github` / `*.gradle` / `*.toml` /
  `ci_portfolio_map.yml` を含むpath filterで diff を検索し、0件を確認。
- 対象test method `japaneseResourcesResolveEveryConcretePreviewString` のpre-diff版
  （`git show origin/main:tests/...`）に含まれるresource keyを全件列挙し、
  **#228由来keyは0件**であることを確認（既存対象は #230/#231由来のみ）。
  つまり本PRのtest追加は同methodへの初回の#228対象取り込みである。
- diff hunkは3件で、すべて同method領域（L2201付近のstrings list、
  L2241付近のplaceholder assert、L2301付近のplurals assert）に集中。
  同fileの他test・他methodへの変更はない（`unresolvedDurableStatusRestoresFocusToStartAction`
  を含むflaky疑いの他methodはdiff外。grep で0件確認）。

## Resource集合の機械検証（AC-293-01の対象集合）

test diffから追加された `R.string` 17件・`R.plurals` 2件を抽出し、
`origin/main` の `lawnchair/res/values/strings.xml` と
`lawnchair/res/values-ja/strings.xml` をXML parseして突合した（監査者側の独立script）。

- **17 strings**: すべて values / values-ja の両方に存在し、**17/17 が ja≠en**
  （`assertNotEquals` によるen fallback検出様式が成立する）。
  対象: `manual_organization_detecting_missing_apps`、`missing_apps_title`、
  `missing_apps_search_hint`、`missing_apps_select_all`、`missing_apps_clear_all`、
  `missing_apps_continue`、`missing_apps_state_checked`、`missing_apps_state_unchecked`、
  `preview_unavailable_add`、`preview_retry`、`added_count`、`group_added`、
  `preview_add_descriptor`、`preview_add_row`、`unplaced_strategy_scope`、
  `selection_stale`、`candidate_unresolved`。
- **2 plurals**（`manual_organization_missing_apps_selected_count`、
  `manual_organization_applied_added_count`）: 両localeに存在。jaは **`other` のみ**、
  enは `one` + `other` で **one/other のテキストも互いに異なる**
  （en側のone-vs-other `assertNotEquals` が成立する）。
- **除外2件の確認**:
  - `manual_organization_missing_apps_empty` は main の values / values-ja
    **ともに存在しない**（#369実装で削除済み。除外は正しい）。
  - `manual_organization_missing_apps_empty_continue` は values / values-ja
    **ともに存在する**が、`git log -S` で導入commitが `b22adea292`
    （feat(417)）であることを確認。#417由来であり#228由来でないことの
    主張（test内コメント・spec Non-goals）は事実と一致する。
- placeholder survival対象のformat文字列を確認:
  `preview_add_row` = `Add %1$s → %2$s`、`preview_add_descriptor` = `%1$s (%2$s)`
  — いずれもformat引数2件で、testの `getString(id, "A", "B")` 呼び出しと整合し、
  `"A"`/`"B"` 両方を含むことのcontains-check（#208の `preview_move_row` 前例と同様式）
  が妥当に機能する。

## Test追加の様式検証

- 17 strings: 既存 `addedPreviewStrings` リストへの追加で、既存の
  `context.getString(id)` vs `japanese.getString(id)` の `assertNotEquals`
  （failure message「falls back to English under a Japanese locale」）と同一様式。
- placeholder survival 2件: 既存 #208ブロックの `japaneseMoveRow` contains-check と
  同一様式。
- plurals 2件: 既存 #231ブロックと同一の2段構え
  （ja `getQuantityString(id, 2, 2)` vs en同quantityの `assertNotEquals` +
  en `getQuantityString(id, 1, 1)` vs `(id, 2, 2)` のone-vs-other `assertNotEquals`）。
- 追加箇所には既存の `// Issue #230:` / `// Issue #231:` 前例と同じ
  `// Issue #228:` コメントを付与（#369削除・#417由来の除外理由もコメント記載）。
- plan Step 2（1〜5）の記載と実装が1対1で対応していることを突合済み。

## spec 13追記の検証（AC-293-02）

- diffは閉集合への `| CANDIDATE_UNAVAILABLE` 1行追加（`EXACT_PRECONDITION_FAILED`
  の直後。`Results.kt` の宣言でも同隣接）+ frontmatter `updated:` 更新 +
  change history entry 1件のみ。
- **membership一致の機械突合**: PR headの
  `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt` の
  `enum class PreWriteRejection` 定数12件と、spec 13閉集合の variant 12件を
  集合比較した結果 **完全一致**（差分0/0）。
- `tests/unit/app/lawnchair/organizer/application/contract/ApplyResultContractTest.kt`
  の `everyPreWriteRejectionVariantExists` のexpected setに
  `CANDIDATE_UNAVAILABLE` を含むことを確認（同testは `entries.map { it.name }.toSet()`
  との **set比較** であり順序を契約化しない — 本PRが順序を契約化しない判断と整合）。
- change history entry（2026-10-03）は、main上の #185 precedent entry
  （2026-09-01、`OVERLAP_POLICY_REJECTED` 追記）と同形式
  （日付 / Issue帰属 / 閉集合拡張の趣旨 / runtimeとcontract testは PR #289 で
  確定済み / 「No other result shape, lifecycle, or behavior change.」）であることを確認。
- **membership契約の明文化**（Phase 1 round 1対応の反映）:
  specs/293 spec §対象 item 2 と plan Step 1 の両方に、挿入位置は可読性のための
  位置であること、契約は閉集合のmembership一致で列挙順は契約ではないこと、
  並べ替え・order assertion追加は行わないこと、が記載されていることを確認
  （実際のdiffにも順序変更・order assertion追加は存在しない）。

## specs/293 の AC 突合（監査者による再判定）

- **AC-293-01（19リソースがjaで解決しen fallbackしないことをtestで固定。
  evidence = class指定local run + CI run）**: **充足**。
  対象集合はspec §対象の19件と diff の追加19件が1対1で一致（過不足なし）。
  様式は上記のとおり既存前例どおり。local実行（API 36 emulator、
  class指定 `connectedLawnWithQuickstepGithubDebugAndroidTest`）は
  **56 tests / 0 failures** として spec change history（2026-10-03実装完了entry）と
  PR本文の両方に整合して記録されている（監査者はemulatorを再実行していない。
  下記「未確認範囲」）。CI側は本headのlane green（下記CI節）で閉じている。
- **AC-293-02（spec 13が `CANDIDATE_UNAVAILABLE` をruntime/testと矛盾なく
  閉集合記載し、#185前例形式のchange historyを持つ）**: **充足**（上記節のとおり）。
- **AC-293-03（docs+test-only、production source・DB・依存に差分なし）**: **充足**
  （diff 4件の機械照合による）。

## Review history（GitHub記録との突合）

Issue #293 上のChatGPT review記録を全件確認した。

- Phase 1 Spec/Plan re-entry review（2026-10-03、
  [comment 5966185292](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966185292)）:
  **3 findings（中 / 中 / 低）** — (1) Issue本文終了条件の「docs-only」と
  実scope docs+test-onlyの矛盾、(2) spec/planの「`Results.kt` の宣言順序と一致」が
  実体より強い、(3) spec冒頭の「拘束契約」と「accepted不要の判断資料」の
  二重の権威づけ。
- round 1対応（
  [comment 5966232761](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966232761)
  + commit `9b004b3e25`、spec/plan 2 files +40/-7）:
  対応結果を **diffとGitHub実体で独立確認**:
  - (1) Issue本文（`gh issue view 293 --json body`）の終了条件第3項は
    「docs+test-only PRでmerge (production source・DB・依存に差分がない)」へ
    更新済み。
  - (2) specs/293 spec item 2 と plan Step 1 にmembership契約・列挙順非契約・
    並べ替え不要の明文化あり（PR headのfileで確認）。
  - (3) spec冒頭は「実装PR向けのre-entry/完了チェック資料 (accepted不要)」へ
    変更済みで、正本をIssue終了条件 + spec 228 AC-12 + spec 13へ一本化。
- Phase 2 Implementation review（head `726e871209`、
  [comment 5966420959](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966420959)）:
  AC-293-02/03をOK判定、AC-293-01は実装充足・CI evidence未完了のみを指摘し、
  **「追加のblocking findingはありません」**。
  後続の
  [comment 5966453588](https://github.com/nunu1733/NunuLauncher/issues/293#issuecomment-5966453588)
  でPhase 2 clear（PR作成後にlane greenを最終headで確認してPR本文へ記録するよう指定）。
  本監査時点でPR本文にCI記録あり（下記）。

## Issue #293 終了条件との突合

終了条件3項目（checkbox形式）はいずれも本PRが閉じる:

1. ja解決testが新keyすべてでgreen（instrumentation）— 本PRのtest拡張 +
   local 56/0記録 + CI lane green。
2. spec 13が `CANDIDATE_UNAVAILABLE` を閉集合として記載しtestと矛盾しない —
   本PRのspec 13追記 + membership突合（監査者機械照合済み）。
3. docs+test-only PRでmerge（production source・DB・依存に差分がない）—
   diff 4件の機械照合。

PR本文は `Closes #293` を含む（本文31行目で確認）。

## Executed test surface（監査者による対象headでの再実行）

作業tree = 対象head `726e871209`（tracked変更なし）で:

```bash
git diff --check origin/main...origin/issue-293-impl  # → 出力なし、exit 0
./gradlew spotlessCheck                               # → BUILD SUCCESSFUL
```

- **spotlessの感度に関する明示**: `build.gradle` のspotless定義（L516-532）は
  kotlin targetを `lawnchair/src/**/*.kt` と `tests/unit/**/*.kt` のみに限定しており、
  本PRが変更した `tests/organizer-instrumentation/**` は **spotless対象外** である。
  よって `spotlessCheck` green は本PRのinstrumentation test fileのformatを
  直接保証しない（file全体がktlint非対象という構成上の事実）。本headのCIでは
  `check-style` job（job 111150894380）が別途 SUCCESS であり、CI側の結果も一致する。
- emulator instrumentationは **本監査では再実行していない**（監査手順として除外）。
  local 56 tests / 0 failures は spec change history と PR 本文の2箇所に
  整合して記録された証跡を引用（次節）。

## CI run status（対象head上の証跡）

- run [37103954891](https://github.com/nunu1733/NunuLauncher/actions/runs/37103954891)
  （workflow CI、event `pull_request`、headSha `726e871209687856c98d803568d6674384c8e114`
  — API突合により監査対象headと一致、conclusion **success**）:
  - `final-status` merge gate（job
    [111153846627](https://github.com/nunu1733/NunuLauncher/actions/runs/37103954891/job/111153846627)）:
    **success**。
  - lane `organizer-instrumentation-manual-organization-ui-tests`
    （job [111150893580](https://github.com/nunu1733/NunuLauncher/actions/runs/37103954891/job/111150893580)、
    attempt 2）: **success**。対象class
    `ManualOrganizationPreferencesInstrumentationTest` は同laneの第一invocation
    classlistに含まれる（plan Step 3の記載どおり）。
  - その他: `check-style` / `organizer-unit-tests` / `build-debug-apk` /
    `validate-repo-contract` / `changes` / `high-risk-evidence` が success、
    path filterにより対象外laneは skipped。PRのstatusCheckRollupは全件
    SUCCESS/SKIPPED（API突合済み）。

## CI初回attempt失敗（flake）の発生とdisposition

- 同runの **attempt 1** で lane job
  [111148822462](https://github.com/nunu1733/NunuLauncher/actions/runs/37103954891/job/111148822462)
  が失敗。job log（API取得）を監査者が直接確認した結果、失敗は
  `ManualOrganizationPreferencesInstrumentationTest >
  unresolvedDurableStatusRestoresFocusToStartAction[emulator-5554 - 16] FAILED` で、
  signatureは `java.lang.ArrayIndexOutOfBoundsException: length=320; index=-56`
  at `androidx.compose.runtime.SlotWriter.moveSlotGapTo(SlotTable.kt:4351)` →
  activity destroy経路のcrashによりinstrumentation run全体が `Process crashed`。
- **diff外の既存test**: `unresolvedDurableStatusRestoresFocusToStartAction` は
  本PR diffに一切現れない（grep 0件）。本PRはproduction無変更のため、
  失敗と本PR diffの因果は構成上あり得ない。
- **既知flake familyとの一致**: tracking issue
  [#418](https://github.com/nunu1733/NunuLauncher/issues/418)（OPEN）は
  API 36 organizer instrumentation laneの断続失敗を追跡し、同一family
  （`ArrayIndexOutOfBoundsException: length=320; index=-1` +
  `SlotWriter.moveSlotGapTo` + instrumentation process crash）を記録している。
  本発生（index=-56）は同一signature familyで、
  [comment 5966710958](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5966710958)
  （2026-10-03）にPR #511初回attemptの発生として記録済み（内容をAPIで突合済み:
  同run / 同job / 同test / 同signature / `--failed` 再実行で同head green）。
- **disposition**: flake（既知・追跡済み・diff外）として処理するのが正しい。
  同headでの `--failed` 再実行（attempt 2）で当該laneを含む全jobがgreenであり、
  merge判断に影響しない。恒久対策は #418 のscopeで行うべきであり本PRの作業項目ではない。

## Verification matrix

| 項目 | 検証方法 | 結果 |
|---|---|---|
| head SHA一致（PR / origin branch / 作業tree） | `gh pr view` + `git rev-parse` | 一致 `726e871209` |
| diff = 4 filesのみ・production/res/CI/depゼロ | `--name-status` 全件 + path grep | 確認 |
| 対象methodのpre-diff版に#228 key 0件 | `git show origin/main:...` + 全key列挙 | 確認 |
| 17 strings が values/values-ja 両方に存在・17/17 ja≠en | XML parse突合（監査script） | 確認 |
| 2 plurals が ja `other` のみ・en one≠other | XML parse突合 | 確認 |
| `missing_apps_empty` が両localeに不在（#369削除） | XML parse突合 | 確認 |
| `missing_apps_empty_continue` の #417由来 | `git log -S`（導入commit `b22adea292`） | 確認 |
| placeholder文字列のformat引数2件 | XML parse突合 | 確認 |
| test追加様式が既存前例（#208/#231）と同一 | diffと同一method内既存blockの突合 | 確認 |
| spec 13: `CANDIDATE_UNAVAILABLE` 挿入位置 | diff hunk照合 | `EXACT_PRECONDITION_FAILED` 直後 |
| spec 13 閉集合と `Results.kt` enum のmembership一致 | 集合比較script（12件/12件、差分0） | 完全一致 |
| `ApplyResultContractTest` が `CANDIDATE_UNAVAILABLE` を列挙 | `git show` + grep（set比較様式も確認） | 確認 |
| change history entryの#185前例形式 | main上の#185 entry（2026-09-01）と形式突合 | 一致 |
| membership契約・順序非契約の明文化 | specs/293 spec item 2 / plan Step 1 照合 | 記載あり・diffに順序変更なし |
| AC-293-01〜03 | 実artifactとの突合（上記各節） | すべて充足 |
| Phase 1 review 3指摘のround 1対応 | commit `9b004b3e25` diff + Issue本文 + specs突合 | 実装済み |
| Phase 2 review「blocking findingなし」 | comment 5966420959本文 | 確認 |
| Issue #293 終了条件3項目 / `Closes #293` | `gh issue view` / PR本文 | 一致 |
| CI run 37103954891 が対象head上でsuccess | API突合（headSha / event / conclusion） | 一致 |
| `final-status` job 111153846627 success | API突合 | 確認 |
| lane job 111150893580（attempt 2）success | API突合 | 確認 |
| attempt 1 job 111148822462 の失敗signature | job log直接取得 | `AIOOBE length=320; index=-56` at `SlotWriter.moveSlotGapTo`、`unresolvedDurableStatusRestoresFocusToStartAction` |
| 失敗testがdiff外 | diff grep | 0件 |
| #418 記録との同一family・発生記録 | issue本文 + comment 5966710958 API突合 | 一致 |
| `git diff --check` / `spotlessCheck` 再実行 | 監査sessionで実行 | ともにclean / BUILD SUCCESSFUL |
| local instrumentation 56 tests / 0 failures | **記録の整合性確認のみ**（spec change history と PR本文の2箇所） | 2箇所で整合 |
| CI check一式（`check-style` / `high-risk-evidence` 等含む） | `gh pr view --json statusCheckRollup` | 全件 SUCCESS/SKIPPED |

## Findings

**blocking finding なし。**

- [info] 本PRが参照する書式前例のfile名について: 監査指示にあった
  `docs/assessment/pr-502-two-panel-orientation-oracle-race.md` というslugのfileは
  リポジトリに存在せず、実在するPR #502の監査recordは
  `docs/assessment/pr-502-435-stale-oracle-time-independent.md` である
  （本recordはそちらの形式に従った）。record内容への影響なし。
- [info] spec 293のstatusは `draft` のままである（tier L運用ではacceptedを要求せず、
  spec冒頭・PR本文のauthority整理どおり）。本auditはownerによるstatus判断を代替しない。
- [info] local instrumentationの「56 tests / 0 failures」は、本監査では実行log/XMLを
  直接確認できていない（記録の相互整合のみ確認）。CI側の同class実行は
  attempt 2 lane green として別途確認済みであり、AC-293-01のevidence要件
  （「local + CI instrumentation」）は記録上充足する。

## 未確認範囲（明示）

- 監査者はemulator instrumentation testを再実行していない。local run結果
  （56 tests / 0 failures、API 36 emulator）はspec change historyとPR本文の
  記録の引用であり、実行log自体は本sessionで直接確認していない。
- 本recordのcommitによりbranch headは監査対象SHA `726e871209` から先へ進む。
  記録済みCI run 37103954891（およびその全job）は **監査対象head上の証跡** であり、
  record追加後の最終headに対する `final-status` gateは本recordでは検証していない。
  Phase 2 reviewの指示（「rebase等でheadが変わる場合は旧headのrunを流用せず、
  最終headの証跡を使う」）どおり、最終headでのgate確認をPR側の記録で行うこと。
- #418 flakeの根因・恒久対策の進捗は本監査の対象外（tracking issueのscope）。
- spec 13追記後のspec status遷移（`draft` → `implemented` 等）はowner作業であり
  未実施。

## Conclusion

**GO。** diff scope（docs+test-only 4 files）、resource集合（19件の存在・ja≠en・
plurals数量構成・除外2件の帰属）、test様式の前例追従、spec 13閉集合の
runtime/contract testとのmembership一致（12件/12件）、change historyの#185前例形式、
specs/293 AC-293-01〜03、Phase 1 review 3指摘のround 1対応の実在、
Phase 2「blocking findingなし」、Issue #293終了条件3項目と `Closes #293`、
監査対象head上のCI（run 37103954891 / `final-status` / 対象lane）をすべて
監査者が機械的に再確認した。初回attemptのlane失敗はdiff外の既存testにおける
#418追跡済みflake（同一signature family、同head再実行でgreen）であり、
本PRとの因果はない。mergeは最終headでの `final-status` gate確認を条件として
行われるべきである（本record作成時点ではrecord自体が未commitのため、
現時点の監査対象head = PR head = CI対象headが一致している）。
