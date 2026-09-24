---
issue: "#342"
status: accepted
requirements:
  - FR-010
  - NFR-009
updated: 2026-09-24
---

# CustomCategoryPreferences instrumentationをCI merge gateへ接続し、カテゴリ管理UIのa11y項目を自動assertに固定する

> **Status:** **accepted** (2026-09-24) — Phase1 re-review [Approved](https://github.com/nunu1733/NunuLauncher/issues/342#issuecomment-5810304096)（snapshot `06441db265`）。関連契約: [spec 336](../336-user-defined-categories/spec.md) AC-12 / AC-13、監査記録 [docs/assessment/pr-341-user-defined-categories.md](../../docs/assessment/pr-341-user-defined-categories.md) Finding 1。本specは [PR #341][2] で特定されたnon-blocking残課題を、test表面とCI構成の変更だけで解消することを契約化する。productionの振る舞いは一切変更しない。

## Problem

Issue #336で追加されたカテゴリ管理UI（`CustomCategoryPreferences`）のinstrumentation test `CustomCategoryPreferencesInstrumentationTest` は、`.github/workflows/ci.yml` のどのinstrumentation laneのclass listにも含まれておらず、CI上で一度も実行されていない（compile-gatedのみ）。mainで壊れていてもmerge gateは検出しない。

また、spec 336 AC-13（TalkBack label、focus復帰、keyboard/DPAD、Switch Access、非色状態、200% font scale、raw ID非表示）のうち、この管理UI表面で自動assertされている項目は実質ない。現状の担保は「coordinator unit test（CI実行済み）+ 手動emulator evidence 8枚（commit `927ef95e07`）」であり、監査記録はAC-13をPARTIALと判定し、自動a11y検証を後続作業（本Issue）として分離した。さらに既存test内のraw ID非表示確認は `onAllNodesWithText(userId.value).fetchSemanticsNodes().isEmpty()` という**結果を捨てる式文**であり、assertionとして機能していない（test file 306行中の92-93行目に実読で確認。失敗しても検出できない）。

## Outcome

`CustomCategoryPreferencesInstrumentationTest` がsource/workflow変更PRごとの必須CI merge gate（`CI / final-status` を構成するinstrumentation job）上で常時実行され、失敗がmergeをblockする。同じtest class内に、カテゴリ管理UI（作成・改名・削除・partial deleteの各flow）のspec 336 AC-13相当項目—名前付きで操作可能なclick action、editor/dialog exitでのCompose/keyboard input focus復帰、keyboard/DPAD、Switch Access等価なsemantics起動、非色状態、200% font scale、raw ID非表示—の明示的な自動assertが追加され、以後は手動evidenceなしでも回帰がCIで検出される。production code、UIの見た目・振る舞い、文字列、依存関係は変更しない。

## Scope

- `CustomCategoryPreferencesInstrumentationTest` を既存API 36 instrumentation lane（`organizer-instrumentation-category-override-tests`）のclass listへ追加し、CI merge gateの常設対象にする。
- 同test classへ、管理UI表面に対する次の自動assertを追加する（対象UIは `CustomCategoryPreferences.kt`、駆動seamは既存testと同じ `UserDefinedCategoryAuthoringCoordinator` + in-memory fake store）:
  - **名前付きで操作可能なclick action**: entry行が「`Rename <名前>`」のlocalized `contentDescription` とclick actionを持ち、delete行が「`Delete <名前>`」のそれを持つこと。status/summary nodeがpolite live regionのsemanticsを持ち、typed feedbackがテキストで読み上げ対象になること。semantic `Role` は本Issueの契約に含めない（productionの `CategoryEntryRow` は `.clickable` + `contentDescription` のみで明示的Roleを持たず、Role要求はproduction変更を強いAC-7に反するため）。
  - **Compose/keyboard input focus復帰**: 作成editor・改名editor・削除確認dialogのいずれかを離れた時点（保存・cancel・確定）で、既存 `FocusRequester` によりsummary nodeへCompose/keyboard input focusが戻ること（`assertIsFocused()` で検証）。対象はeditor/dialog exitに限定する。partial delete状態の「Back to categories」後のfocus復帰は本契約の対象外である（現productionのfocus `LaunchedEffect` のkeyが `creating, editorTarget, pendingDelete, statusMessage` であり `partialDeleteTarget` を含まず、当該経路で復帰が保証されないため。要求する場合はproduction変更を伴いAC-7/Non-goalsの再判断が必要で、本Issueでは扱わない）。これはTalkBackのaccessibility focus復帰の証明ではない。TalkBack側のこの表面のcoverageは名前付きclick actionとpolite live regionのsemantics assertが担う。
  - **keyboard/DPAD**: keyboard入力モードでsummaryからDPAD移動が最初の操作可能行へ到達し、Center keyでeditorが開くこと。
  - **Switch Access等価**: `SemanticsActions.OnClick` によるsemantics起動でentry行から改名editorが開くこと（category-override lane testの確立patternと同一）。
  - **非色状態**: 各user-defined entry行に「Custom」テキストmarkerが色に依らず存在すること。typed error（duplicate name等）がテキストとして提示されること。
  - **200% font scale**: `fontScale = 2f` で、50 code pointの最大長display nameを持つentry行と作成actionが表示・到達可能であり、削除確認dialogが操作可能であること。
  - **48dp touch target**: entry行の高さが48dp以上であること（spec 336のaccessibility barが引き継ぐ項目）。
  - **raw ID非表示**: list・作成editor・改名editor・削除確認dialog・partial delete状態の5状態で、raw `UserCategoryId` 値がsemantics treeに現れないことを、共有contains-based helper `assertNoRawIdsPresent(seedId, mintedId)` による**実assertion**として確認する（既存のvacuous式文を置き換える）。helperは全semantics nodeの `Text`・`EditableText`・`ContentDescription` をsubstring/contains一致（exact一致ではない）で走査し、seed IDと作成pathでmintされたIDの両方を検査する。
- lane責務の正本 `docs/engineering/ci-test-portfolio.md` のcategory-override laneのhuman-readable lane説明行に、#336管理UI表面をclass listのco-occupantとして追記する。lane↔surface edgeのnormative正本 `tools/repo-contract/ci_portfolio_map.yml` は同一surface内のclass追加ではedgeが変わらないため編集不要であることを検証する。

## Non-goals

- production source、UI実装、文字列resource、coordinator/store実装の変更。本Issueで新typed挙動・新stateを追加しない。
- spec 336本文・ACの書き換え（audit記録がresidualを追跡済みであり、本Issueの実装完了がspec 336 AC-12/AC-13の自動検証部分を満たす）。
- assignment flow（#99 override editorのselector）へのuser-defined entry表示assertの追加。`CategoryOverridePreferencesInstrumentationTest` のfixture拡張は別の切り口（editor表面のtest）であり、本Issueの対象test classの外である。selector側の「Custom」markerは既存のJVM unit testと手動evidenceで担保が続く。
- API 35 lane、新規lane jobの作成、#300のwindow-focus前提harnessの導入（本testはCompose test ruleで実入力注入をしないため不要）。
- 手動emulator evidenceの再取得・削除（既存8枚は履歴証拠として残す）。
- 実行時間最適化、emulator provision方法の変更、`final-status` job構成の変更（class追加だけで `final-status` は既存jobを通じて効果を得る）。
- partial delete状態の「Back to categories」後のCompose/keyboard focus復帰。現productionは当該経路でfocus requestを発火せず（上記Scope）、本IssueのAC-3・test oracle・planの対象外とする。将来要求する場合はproduction変更とAC-7の再判断を別Issueで行う。

## Domain language

- **CI merge gate常設対象**: `.github/workflows/ci.yml` のinstrumentation jobの `-Pandroid.testInstrumentationRunnerArguments.class=` class listに列挙され、`final-status` の成否に効くことをいう。
- **名前付きで操作可能なclick action**: localized `contentDescription` label（例: 「Rename <名前>」）とclick actionを併せ持つsemantics nodeをいう。semantic `Role` の有無は含まない。
- それ以外の用語（user-defined category、`UserCategoryId`、partial delete等）はspec 336の定義に従う。本specは上記以外の新用語を追加しない。

## Behavior scenarios

### Scenario: 管理UI testがmerge gateで実行される

Given `origin/main` 上で `CustomCategoryPreferencesInstrumentationTest` がlane class listに列挙されている
When source pathまたはworkflow pathを含むPRが出る
Then 当該lane jobがAPI 36 emulator上で当該classを実行し、job結果が `final-status` に反映される
And test classのcompile漏れ・実行時失敗はmerge gateを失敗させる

### Scenario: 名前付きで操作可能なclick actionとlive region

Given カタログにuser-defined category「Commute」がある
When 管理UIが表示される
Then 「Rename Commute」のcontentDescriptionを持つclick可能なnodeと「Delete Commute」のそれが存在する
And summary nodeはpolite live regionを持ち、duplicate name等のtyped feedbackがテキストで存在する

### Scenario: Compose/keyboard focus復帰

Given 作成または改名editor、あるいは削除確認dialogが開いている
When 保存・cancel・確定のいずれかでeditor/dialogを離れる
Then summary nodeがCompose/keyboard input focusを回復する（既存 `FocusRequester` の挙動の固定）
And editor内でのみ意味を持つfocusが残存しない

### Scenario: keyboard/DPADとSwitch Access等価

Given keyboard入力モードで管理UIが表示されている
When summaryからDPAD downで移動しCenterで起動する
Then 作成actionが到達・起動されeditorが開く
And entry行に対する `SemanticsActions.OnClick` 起動でも改名editorが開く

### Scenario: 非色状態と200% font scale

Given font scale 200%で、50 code pointのdisplay nameを持つentryが存在する
When 管理UIが表示される
Then 「Custom」markerがテキストとして各行に存在し、最長名の行と作成actionが到達可能である
And 削除確認dialogのtext・buttonが操作可能である
And entry行の高さは48dp以上である

### Scenario: raw IDはどの状態でも現れない

Given list・作成editor・改名editor・削除確認dialog・partial delete状態の5状態のいずれかが表示されている
When 共有helper `assertNoRawIdsPresent(seedId, mintedId)` が全semantics nodeのText・EditableText・ContentDescriptionをsubstring一致で走査する
Then どの `UserCategoryId` 値（seedしたIDと作成でmintされたIDの両方）も、exact形でも `Delete <raw-id>` のような埋め込み形でも現れない
And この確認は失敗し得る実assertionである

### Failure / rejection

Given 新assertまたは既存flow assertのいずれかがCI上で失敗する
When PRの `final-status` が評価される
Then merge gateは失敗し、instrumentation report artifactに失敗class・methodが記録される

### Stale state / concurrency

本testはin-memory fake storeのみを使い、Launcher DB・app状態・実storeを共有しない。stale状態・競合の契約はspec 336（store/coordinator層）が担い、本Issueは扱わない。

## Data and state

- 読むdata: なし（test内synthetic fixtureのみ）。
- 永続化: なし。CI失敗時のreport artifact（既存laneのupload stepと同じpath規約）のみ。
- migration / backup / rollback: なし。layoutは扱わない。

## Permissions, privacy, and security

`None` — permission・network・外部送信の追加はない。testはsynthetic identityのみを使い、実ユーザーデータを含まない（既存fixtureと同一規約）。

## Accessibility and localization

- 本spec自体がa11y契約である（Scopeのassert一覧が要件）。assertに使う文字列は既存のlocalized resource（`res/values/strings.xml`、`values-ja`）から解決し、test内に新規文字列をハードコードしない。
- 日本語・英語両localeで文字列が存在することは既存resourceが保証する。新規文字列追加は本Issueの対象外。

## Acceptance criteria

- [ ] **AC-1** — `CustomCategoryPreferencesInstrumentationTest` が `ci.yml` のinstrumentation lane class listに列挙され、source/workflow変更PRの必須CI merge gate上でAPI 36 emulator上で実行される。lane失敗は `final-status` 失敗としてmergeをblockする。
- [ ] **AC-2** — 名前付きで操作可能なclick action: entry行・delete行がlocalized action contentDescriptionとclick actionを持ち、summary nodeがpolite live regionを持つことが自動assertされている。semantic `Role` は要求しない（productionは明示的Roleを持たない）。
- [ ] **AC-3** — Compose/keyboard input focus復帰: editor（作成・改名）と削除確認dialogを離れた後、既存 `FocusRequester` によりsummary nodeへCompose/keyboard input focusが戻ることが `assertIsFocused()` で自動assertされている。これはTalkBack accessibility focus復帰の証明を主張しない。
- [ ] **AC-4** — keyboard/DPADとSwitch Access等価: DPAD移動+Center起動、およびsemantics `OnClick` 起動の経路が自動assertされている。
- [ ] **AC-5** — 非色状態と200% font scale: 「Custom」テキストmarkerの存在、typed feedbackのテキスト提示、fontScale 2fでの最大長名・作成action・削除dialogの到達可能性、48dp touch targetが自動assertされている。
- [ ] **AC-6** — raw ID非表示が、5状態（list・作成editor・改名editor・削除確認dialog・partial delete）から呼ばれる共有contains-based helper `assertNoRawIdsPresent(seedId, mintedId)` による実assertionに置き換わっている。helperは全semantics nodeのText・EditableText・ContentDescriptionをsubstring一致で走査し、seed IDとminted IDの両方を検査する（既存の結果を捨てる式文は残存しない）。
- [ ] **AC-7** — production source・UI実装・文字列resource・依存関係のdiffはゼロである。変更はtest class、`ci.yml` のlane class list（と近接comment）、`ci-test-portfolio.md` のlane説明行追記に限定される。
- [ ] **AC-8** — 実装PRのCI runで、当該laneが追加classを含めて成功し、`final-status` が成功している。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 実装PRの `pull_request` CI run: lane job logに当該classの実行記録、`final-status` success |
| AC-2 | `onNodeWithContentDescription(rename/delete label).assertHasClickAction()` + summary `LiveRegion == Polite` の成功（TalkBack側coverageはこのsemantics assertが担う） |
| AC-3 | `assertIsFocused()` によるCompose/keyboard input focus復帰の成功（accessibility focusの証拠としては扱わない） |
| AC-4〜AC-5 | 同CI run内の当該class成功（新assert method名で確認可能）。局所再現はAPI 36 emulator上で `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest` |
| AC-6 | 5状態からの `assertNoRawIdsPresent` 呼び出し成功。局所再現はAC-4〜AC-5と同じcommand |
| AC-7 | PR diffの `--name-status` 確認（test 1 file + ci.yml + portfolio docのみ） |
| AC-8 | GitHub Actions run URL + `gh api` によるhead SHA照合（merge gate evidence） |

## Open questions

実装開始前に解消すべき問いはない。参考として非blockingな観察: `docs/engineering/ci-test-portfolio.md` はIssue #422でPortfolio model + lane↔surface map（human mirror）へ書き換えられ、旧ownership表は存在しない。normative edge正本は `tools/repo-contract/ci_portfolio_map.yml`（category-override lane → surface_organizer_ui）であり、同一surface内のclass追加ではedge変更もmap file編集も不要である。本Issueではcategory-override laneのhuman-readable説明行への#336管理UI追記に限定し、他lane分の拡充は別途追跡する。

## Change history

- 2026-09-17: Draft created for #342 (spec/plan preparation task; baseline main `8fd05a40d51abd24b40a7b93579bb9b76d046f75`)。
- 2026-09-19: Re-entry検証。current main `3076bdae7ebf8dbb086f251203968c06e9986258`（前回baseline `8fd05a40d51a` 以降の差分はPR #350/#353/#355/#363/#364で、exchange / #327/#328/#337 と Organizer UX文書domain）に対してProblem/Outcome/Scope/ACの全事実を再確認した。対象test file・対象UI・`organizer_custom_category_*` 文字列・issue99 lane構成・spec 336 AC-12/AC-13（spec 337による改訂はexchange投影のみでauthoring契約は不変とspec 336自身が明記）・FR-010/NFR-009・portfolio docはいずれも変化せず、契約の変更は不要だった。
- 2026-09-24: Re-entry改訂（review findings 1-4対応）。baselineを `origin/main` の `b146a63557` へ更新。Finding 1: AC-3/Scope/ScenarioをCompose/keyboard input focus復帰へ狭め、TalkBack accessibility focus証明の主張を除去。Finding 2: label/role契約を名前付きで操作可能なclick action（contentDescription + click action、Role要求なし）へ再定義。Finding 3: AC-6/Scope/Scenarioを共有contains-based helper `assertNoRawIdsPresent`（Text・EditableText・ContentDescriptionのsubstring走査、seed+minted両検査、5状態から呼出し）へ具体化。Finding 4: lane名をIssue #422による改名 `organizer-instrumentation-category-override-tests`（ci.yml 779行目、class list 814行目、`final-status` 970行目/needs 982行目）へ更新、test file 306行・vacuous文92-93行目を確認、portfolio docの#422書換え（Portfolio model + human mirror、normativeは `ci_portfolio_map.yml` 44-46行目）に合わせた記載へ修正。Statusはdraftのまま。
- 2026-09-24: Re-review対応（snapshot `a7c66be43a` へのChanges requested 1点）。Scopeのfocus復帰例から「partial deleteのBack to categories」を削除し、AC-3/plan method 2と同じeditor/dialog exitに統一。現productionのfocus `LaunchedEffect` key（`creating, editorTarget, pendingDelete, statusMessage`）に `partialDeleteTarget` が含まれず当該経路の復帰が保証されないため、Non-goalsへ明示的に対象外を追加（要求する場合は別Issueでproduction変更とAC-7再判断）。AC-3/Scenario/planのfocus対象は従来どおりeditor/dialogのみで変更なし。
- 2026-09-24: statusをdraft→acceptedへ遷移。snapshot `06441db265` へのRe-review [Approved](https://github.com/nunu1733/NunuLauncher/issues/342#issuecomment-5810304096) を受領。Phase 2実装は本契約に従う。

[1]: https://github.com/nunu1733/NunuLauncher/issues/342 "Issue #342"
[2]: https://github.com/nunu1733/NunuLauncher/pull/341 "PR #341 — user-defined categories"
