---
issue: "#367"
status: draft
requirements: [FR-006, FR-010, FR-013, FR-015]
risk: []
updated: 2026-09-19
---

# 整理の材料面（分類・カテゴリ・ロック・使用状況）をOrganizer hubへ集約し設定側rowを廃止する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-01, §5.1 T-02〜T-06, §5.2, §6.3）。
> 本specは[Issue #367][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提: [Issue #366][2]のhub shell（T-01、材料セクション・使用状況material含む）が
> 実装・merge済みであること。#366はspec draft（branch `issue-366-spec-plan`、
> commit `bf00f96175`、2026-09-19時点）を本specの接続先として参照している。

## Problem

整理に影響する恒常入力（材料）への導線が設定画面に分散している: 分類（category
override画面、spec 99）、ユーザー定義カテゴリ（spec 336）、配置ロック（spec 38）が
Home screen設定のLayout groupに、使用状況ヒント（記録toggle＋Usage Access行、spec 203
U-2）がPersonalization groupに、診断（spec 138）がLayout groupに置かれている。#366が
Organizer hub（T-01）を新設し、これら材料への導線をhub側に先行提供した後も、設定側の
重複導線が残る。TO-BE D-01は「Home Screen設定のOrganizer群（run入口・strategy・
personalization・category系入口・診断）はhubへ集約し、設定側は入口rowだけを残す」と
決定しており、設定側の重複rowを廃止しない限り材料の置き場所が二重のままになる。

## Outcome

hub（T-01）が整理の材料面への唯一の設定起点になる。ユーザーは設定 → Home screen →
Organizer（hub入口row）からhubに入り、材料セクションから分類・ユーザー定義カテゴリ・
配置ロック・使用状況ヒントへ到達でき、診断はhub常設導線から開ける。設定 → Home
screenからはorganizer系rowが消え、hub入口row 1件だけが残る。各材料画面の実装・
store契約・AUTHORING/RUN排他leaseは一切変わらない（navigation edgeの集約のみ）。

## Scope

- 設定 → Home screenのLayout groupからorganizer系row 4件（Placement locks /
  Organizer diagnostics / Category overrides / Custom categories）を削除する。
- 設定 → Home screenのPersonalization group（記録toggle＋Usage Access行）を削除する。
  T-06の材料面は#366が導入したhub側の使用状況material（共有表現）に一本化する。
- 設定 → Home screenのGeneral groupからmanual organization直行rowを削除し、hub入口row
  （#366が追加）をorganizer系で唯一のrowとする（D-01の「run入口...hubへ集約し、
  設定側は入口rowだけを残す」。本spec末尾のContract notes参照）。
- 下位画面（材料各画面・診断画面・run面）は既存実装をそのまま受け、routeの追加・
  変更を行わない。変わるのはnavigation edge（どの面から開くか）のみである。
- 入口表記の追記（契約不変）を同じPRで行う: specs 38 / 99 / 336 / 203 / 271 / 138、
  spec 123のinventory evidence文書（`docs/assessment/evidence/issue-123-ui-mapping.md`）。
- `OrganizationEntry.ONBOARDING` 経由のonboarding提案→run接続は変更しない。
  ただしonboarding再入口hintのpath文言が実在する設定pathを指し続けることのみ本Issueの
  受入条件とする（詳細はBehavior scenarios）。

## Non-goals

- strategy pickerの移設・run面からの撤去（D-03、#368）。run面のstrategy pickerは
  本Issueでは触れない。
- 材料各画面のUX再設計、store契約・AUTHORING lease・書込み経路の変更。
- Usage Access要求のJIT化（D-07、#371）。JIT要求は出ない。spec 203のU-2本体改訂は
  #371が所有し、本Issueは入口表記（settings常設row → hub T-06）の追記のみを行う。
- run面の表示統合・canonical順序・T-07前置き面（D-05/D-06、#369）。
- status cardの拡張（#374/#375/#376）。hubのstatus card・開始CTA・診断導線の構成は
  #366のままとし、本Issueで変更しない。
- onboarding提案のflow接続変更・hintの案内先変更（D-16表記、#370）。本Issueのhint
  関係変更は「path文言の真実性維持」に限定される。
- lockのworkspace長押しdialog（T-20）の変更。flow外入口として現行どおり機能する。
- 設定検索への新規登録・削除（現行のpreference画面に設定検索indexは存在しないため
  対象外）。

## Domain language

- **材料**: 分類・ロック・方針・使用状況ヒントの総称（organizer-to-be-ux.md §10）。
  正本は#365（OPEN）が `CONTEXT.md` へ追加する語彙であり、本specでは複製しない。
  本spec時点では「整理に影響する恒常入力（分類・ユーザー定義カテゴリ・配置ロック・
  使用状況ヒント）」という語彙借用にとどめる。
- **T-02〜T-06**: organizer-to-be-ux.md §5.1の表面ID。T-02=分類、T-03=ユーザー定義
  カテゴリ、T-04=配置ロック、T-05=整理方針（#368）、T-06=使用状況ヒント。
- **入口row**: 設定 → Home screenからhub（T-01）を開く`NavigationActionPreference`。
  #366が追加する（draft planではlabel `organizer_hub_title`）。

## Behavior scenarios

### Scenario: hub材料セクションから各材料画面へ到達できる

Given #366のhubが実装済みで、設定 → Home screen → hub入口rowからhubが開く
When hubの材料セクションで分類・ユーザー定義カテゴリ・配置ロックの導線をそれぞれタップする
Then 既存のCategory override画面（spec 99）、Custom category画面（spec 336）、
Placement lock画面（spec 38）へ遷移し、画面内容は本Issue適用前と同一である
And 各画面でsystem Backを押すとhubへ戻る（既存preferences navigationのback stack）

### Scenario: 使用状況materialはhubのT-06が唯一のインスタンスである

Given 本Issueの変更が適用されている
When hubの材料セクションで記録toggleを変更する、またはUsage Access行からsystem設定へ
遷移して戻る
Then 変更対象は既存のlauncher-origin記録preferenceと同一のapp-op読取であり、
`ON_RESUME`での付与状態再読取（spec 203 U-2）が維持される
And 設定 → Home screenには記録toggleもUsage Access行も存在しない（Personalization
groupごと消えている）
And JIT要求は出ない（#371まで実装されない）

### Scenario: 設定 → Home screenのorganizer系rowが入口row 1件になる

Given 本Issueの変更が適用されている
When 設定 → Home screenを表示する
Then organizer由来のrowはhub入口row 1件のみであり、そこからhubが開く
And Layout groupにはorganizer系row（placement locks / diagnostics / category
overrides / custom categories）が存在しない
And General groupにmanual organization直行rowは存在しない（run開始はhub起点のみ。
TO-BE §5.2）

### Scenario: 診断へはhub常設導線とrun面safe terminalの両方から到達できる

Given organizer diagnostics destination（spec 138 route）が存在する
When hubの診断導線をタップする、またはrun面のsafe terminal（`requiresSafeSupport()`）
で「診断を開く」をタップする
Then いずれも同一のdiagnostics export画面が開く（route追加・画面複製なし）
And 設定 → Home screenのLayout groupから診断rowは削除されている

### Scenario: 材料の意味論とlease契約は変化しない

Given run coordinatorが進行中（run admission済み）である
When hub → 材料 → 分類編集を試みる
Then 既存のAUTHORING/RUN排他lease契約（spec 99 §lease、spec 336）どおりtyped拒否と
なり、run・材料storeともに書込みが発生しない
And run不在時に材料を編集した場合、恒常store（override snapshot / user category
catalog / lock tri-state / 記録preference）への書込みは本Issue適用前と同一の経路・
同一の形式である

### Scenario: 移設された材料画面が既存の受入条件を引き続き満たす

Given 本Issueの変更が適用されている
When specs 38 / 99 / 336 / 203の受入条件に対応する既存instrumentation test
（`CategoryOverridePreferencesInstrumentationTest`、
`CustomCategoryPreferencesInstrumentationTest`、`OrganizerLockScreenTest`等）を
実行する
Then 画面を直接composeする既存testは無編集でgreenであり、route・画面構成・store契約に
diffが存在しないことの回帰証拠になる

### Scenario: onboarding再入口hintが実在する設定pathを指し続ける

Given onboarding提案で「後で」が選ばれ、6秒hintが表示される
When hintのpath文言（設定 → Home screen → organizer入口row）を観察する
Then pathは実在するlabelだけで構成され、そのpathを辿るとorganizer（hub）へ到達する
And hintの表示契約（6秒寿命、accessibility announcement、defer/skip outcome）は
spec 232どおり不変である
And onboarding提案の「確認」CTAの接続先（`OrganizationEntry.ONBOARDING` 経由のrun
面）は変更されない（#370が所有する接続変更は行わない）

### Scenario: lockのflow外入口が維持される

Given workspaceでアイコン長押しpopupを開いている
When lock操作（T-20）を行う
Then 現行契約（spec 38、ADR-0004）どおりdialog確認付きでlockが付与され、lock管理画面
（T-04）への既存導線は本Issue適用前と同一である

### Scenario: process死・再起動後に導線が復元される

Given process死後に設定 → Home screenを開く
When hub入口rowからhubへ入り材料セクションを観察する
Then 材料・診断の導線は新規に導入された状態に依存せず、既存storeから導出されて表示される
And 本Issueは新しい永続化を一切導入しないため、process死で失われる表示stateはない

## Data and state

- **読む**: 変更なし。材料画面・hubが読むpreference / snapshot / app-op stateは
  すべて現行のseamのままである。
- **書く**: 新規の永続化はない。削除対象rowの backing state（記録preference
  `organizerPersonalizationRecording` 等）は存続し、hub T-06から同一adapter経由で
  書かれる。
- **Identity**: hub入口row・材料導線は新たなidentityを導入しない。既存route
  （`HomeScreenPlacementLocks` / `HomeScreenCategoryOverrides` /
  `HomeScreenCustomCategories` / `HomeScreenOrganizerDiagnostics` /
  `HomeScreenManualOrganization`）を引数のまま再利用する。
- **Migration / backup / restore / rollback**: persistent state変更なし。schema・
  preference format変更なし。Launcher layout DB / `favorites` への接触なしのため
  ホームレイアウト安全規約の適用対象外。PR revertで設定側rowが復活し、材料storeに
  不整合は残らない。downgrade時の残留物なし。
- **利用者の既存設定**: 記録toggle・override・ユーザー定義カテゴリ・lock・診断export
  設定の値は移設の前後で不変であり、再設定を要求しない。

## Permissions, privacy, and security

- None — 新しいpermission、外部送信、sensitive dataの扱い追加はない。
- Usage Access行は既存どおりsystem設定（`ACTION_USAGE_ACCESS_SETTINGS`）への遷移と
  app-op付与状態の表示に留まる（spec 203 U-2。JIT要求は#371）。
- 削除される設定rowに紐づく権限・dataが露呈する経路の追加は存在しない。

## Accessibility and localization

- 本Issueはsurfaceを削除・集約する変更であるため、accessibility上の要求は
  「削除でorganization-run-ux §6の受入基準を損なわないこと」と「残る面の基準維持」
  である。hub側の受入基準（TalkBack name/role/state、focus restoration、200% font
  scale、non-color-only、keyboard/switch traversal）は#366のACが所有し、本Issueで
  低下させない。
- 設定 → Home screenからorganizer系rowが消えることは、organizerの機能が TalkBack
  から到達不能になることを意味しない（hub入口row 1 tapで材料・診断・開始に到達する）。
- onboarding再入口hintのpath文言更新は、accessibility announcementの真実性要件
  （spec 232: 「hintは実際にユーザーが見る設定labelから構成されdriftしない」）から
  要求される。更新後の文言もAndroid resource由来とし、EN（`values/`）とja
  （`values-ja/`）のformat resource引数を揃える。Kotlin側の文連結を新設しない。
- 削除により未使用になるstring resource（Personalization group heading）は同じPRで
  削除し、孤立したuser-visible参照を残さない。

## Acceptance criteria

- [ ] **MAT-AC-01**: hubの材料セクションから分類（T-02）・ユーザー定義カテゴリ（T-03）・
      配置ロック（T-04）の各既存画面へ到達でき、hub材料セクション内の使用状況material
      （T-06）が記録toggleとUsage Access行として機能する。hubから診断（spec 138
      route）へ到達できる。（Issue受入1）
- [ ] **MAT-AC-02**: 設定 → Home screenにorganizer系rowが残っていない。organizer由来の
      rowはhub入口row 1件のみであり、Layout groupの4 rowとPersonalization group、
      General groupのmanual organization直行rowは存在しない。（Issue受入2、D-01）
- [ ] **MAT-AC-03**: 既存store・lease契約（AUTHORING/RUN排他）に変更がない。run state
      machine、spec 13/52/99/336/38/203/271/138の契約面、diagnostics event、
      persistent store、permissionにdiffが存在せず、材料画面を直接composeする既存
      instrumentation（`CategoryOverridePreferencesInstrumentationTest` /
      `CustomCategoryPreferencesInstrumentationTest` / `OrganizerLockScreenTest` /
      `ManualOrganizationPreferencesInstrumentationTest`）が無編集でgreenである。
      （Issue受入3・4）
- [ ] **MAT-AC-04**: 入口表記の追記（契約不変）が同じPRで行われている: specs 38 / 99 /
      336 / 203 / 271 / 138に「入口はhub経由になった旨」の追記、spec 123のinventory
      evidence文書に設定row削除の反映。対応する実装と整合している。（Issue Spec節）
- [ ] **MAT-AC-05**: navigation instrumentation testが更新されている: 設定階層
      （organizer rowはhub入口row 1件のみの構造assert）とhub導線（hub → 材料/診断
      navigation）。旧oracle（HomeScreen → diagnostics row navigation、General group
      のmanual organization row above-the-fold assert）の更新と、旧oracleがなぜ
      obsoleteか（D-01材料集約の完了）がPRに記録されている。（Issue受入5）
- [ ] **MAT-AC-06**: 使用状況materialの単一の真実: hub T-06の記録toggleは既存preference
      を、Usage Access行は既存app-op読取と`ON_RESUME`再読取（spec 203 U-2）を操作し、
      設定側に二重インスタンスが存在しない。（#366 HUB-AC-06の後継状態）
- [ ] **MAT-AC-07**: onboarding再入口hintのpath文言が実在する設定path（設定 → Home
      screen → hub入口row）を指し、EN/jaのformat resourceで構成されている。hintの
      表示契約とonboarding提案→run接続（`OrganizationEntry.ONBOARDING`）は不変である。
- [ ] **MAT-AC-08**: lockのworkspace長押しdialog（T-20）とlock管理画面の導線が現行
      どおり機能する（Non-goals機械的保証の否定的観測）。
- [ ] **MAT-AC-09**: 削除により未使用になったstring resourceが削除され、
      `values/` と `values-ja/` の双方から一貫して除去されている。hardcoded literalの
      新規導入がない。

## Test oracle

| AC | Evidence |
|---|---|
| MAT-AC-01 | UI instrumentation test: hub材料セクション → T-02/T-03/T-04 navigation、T-06行の機能、hub → diagnostics navigation。emulator screenshot（light/dark × ja/default） |
| MAT-AC-02 | UI instrumentation test: 設定 → Home screenの否定的観測（4 row・Personalization group・manual organization行の不在）＋hub入口rowの存在・hub遷移assert |
| MAT-AC-03 | 既存testの無編集green（材料画面直接compose系、run面系）+ 実装PR diff review（`organizer/application/**`・rules・store・`ManualOrganizationPreferences.kt`・`PreferenceRoutes.kt`の契約面無編集） |
| MAT-AC-04 | specs 38/99/336/203/271/138のdiff review（入口表記の追記のみで契約節無変更）+ `docs/assessment/evidence/issue-123-ui-mapping.md`のdiff |
| MAT-AC-05 | 更新後の`OrganizerDiagnosticsRouteInstrumentationTest`・`OnboardingOrganizationProposalInstrumentationTest`のdiffと実行結果 + obsolete理由のPR記録 |
| MAT-AC-06 | UI instrumentation test: T-06 toggle ↔ preference state一致、resume再読取（#366のtestをsettings側インスタンス削除後に再実行可能な形へ更新） |
| MAT-AC-07 | `OnboardingOrganizationProposalInstrumentationTest`のhint path label assert更新 + string diff（format resource、EN/ja） |
| MAT-AC-08 | 既存`OrganizerLockScreenTest`（long-press popup経由のlock authoring oracle）green + diff review |
| MAT-AC-09 | 未使用resourceの機械確認（`organizer_personalization_section`等のreference grep）+ `./gradlew spotlessCheck` |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane、CI `final-status` green。

## Contract notes（owner reviewで確認すべき解釈）

1. **General groupのmanual organization直行rowの廃止を本Issueのscopeに含める**:
   Issue本文のscope箇条書きは「Layout groupのorganizer系rows」と「Personalization
   group」のみを列挙するが、受入条件2が「organizer系rowsが残っていない（入口rowのみ）」
   であり、accepted D-01は「run入口」を含むOrganizer群のhub集約と「設定側は入口row
   だけを残す」を、TO-BE §5.2は「整理の開始・材料・再発見・AI依頼はすべてhub起点」を
   それぞれ定める。よってmanual organization直行row（#232で昇進されたrun入口row）は
   hub入口rowに集約され、設定から消える を本specの契約とする。#232の再発見性の目的は
   同位置のhub入口rowと、#370のhint（hub入口案内）が引き継ぐ。#366段階(a)の受入条件
   （HUB-AC-05のmanual organization行維持）は段階(a)限りの暫定維持であり、本Issueで
   段階(b)へ進む。この解釈がowner reviewで否認された場合、本specの該当行と
   MAT-AC-02/05/07の該当箇所を修正する（hint更新と該当test更新は連動して不要になる）。
2. **T-06は新規destinationを作らない**: #366のdraft planどおり、使用状況materialは
   hub材料セクションに組み込まれた共有表現であり、独立したT-06 destinationは本Issueで
   作らない。TO-BE §5.1のT-06は「ユーザーに区別して見せる面」の目的定義であり、2行の
   material sectionがhub内でその目的を満たす。#366の実装がこの形状を外れた場合は
   snapshot re-entry ruleに従い本specを追従させる。

## Dependencies

- **#366（hub shell、OPEN・実装未着手）**: hub destination、材料セクション、診断導線、
  使用状況material共有composable、hub入口rowが存在することが本Issueの実装前提である。
  spec執筆は#366のdraft spec/plan（`bf00f96175`）との整合で可能である（現時点でそうして
  いる）。実装着手は#366のmerge後。
- **#365（正本改訂、OPEN）**: `CONTEXT.md` の材料語彙等は#365が所有する。実装着手は
  #365のmerge後（AGENTS.md「正本を先に」。#366と同一の判定）。
- **後続**: #368（strategy移設・特例廃止。本Issue完了が前提）、#371（Usage Access
  JIT。T-06常設rowが存在することが前提）、#370（onboarding/hint接続変更。本Issueの
  hint path文言更新を上書きする案内先変更を行い得る）。
- **前提（実装済み・accepted）**: specs 38 / 99 / 336 / 203 / 271 / 138 / 123、
  organizer-to-be-ux.md @ main `3076bdae7e`（accepted、PR #364）。

## Open questions

実装開始前に解消が必須な問いはない。非blocking事項:

1. hub入口rowの最終label/subtitle、材料セクションのheading文言と並び順は#366の実装が
   所有する。本specは「入口row 1件」「材料セクションからT-02/T-03/T-04/T-06に到達」
   という構造のみを拘束する。
2. Contract notes 1の解釈（manual organization直行rowの廃止）はowner reviewの確認を
   待つ。確認前は本specは`draft`のままである。
3. 削除対象stringの最終集合（`organizer_personalization_section`以外に未使用が
   発生するか）は実装PRのreference grepで確定する。

## Change history

- 2026-09-19: Draft created for #367（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`）、#366 spec/plan draft
  （`origin/issue-366-spec-plan` `bf00f96175`）、現行実装調査
  （`HomeScreenPreferences.kt`、`PreferenceRoutes.kt`、`PreferenceNavigation.kt`、
  `ManualOrganizationPreferences.kt`、`OrganizationOnboardingProposal.kt`、
  organizer instrumentation tests）を入力に作成。#366のnon-goals（後続契約の先取り
  禁止）を遵守し、本specが確定するのは#367自身の契約（settings row廃止とhub材料
  セクションの単一化）のみである。

[1]: https://github.com/nunu1733/NunuLauncher/issues/367
[2]: https://github.com/nunu1733/NunuLauncher/issues/366
