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
> （D-01, §5.1 T-02〜T-06, §5.2, §6.3）およびaccepted
> [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§3.2/§3.7/§3.8のContinue、§7.2段階(b)、§8実装順）。
> 本specは[Issue #367][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提: [Issue #366][2]のhub shell（T-01、材料セクション・使用状況material・診断常設行含む）が
> 実装・merge済みであること（[PR #380][3]、merge commit `32c72094a4`）。
> 段階契約: 本specはdisposition §7.2の段階(b)「材料集約」であり、D-01の完全実現
> （設定側organizer由来row = 入口row 1件）は#370が所有するmanual organization直行rowの
> 廃止まで完了しない（本spec Contract notes 1）。

## Problem

整理に影響する恒常入力（材料）への導線がhubと設定画面に二重に存在する: 分類
（category override画面、spec 99）、ユーザー定義カテゴリ（spec 336）、配置ロック
（spec 38）がHome screen設定のLayout groupに、使用状況ヒント（記録toggle＋Usage
Access行、spec 203 U-2）がPersonalization groupに置かれている。#366（PR #380）が
Organizer hub（T-01）を実装し、これら材料への導線をhub側に提供済みであるため、設定側の
重複導線は材料の置き場所を二重にするのみである。TO-BE §5.2は「設定側のLayout groupから
organizer系rows（lock/診断/category系）はhubへ移動し、設定には残さない。Home Screen設定の
Personalization groupはT-06へ統合する」と決定している。

D-01の「設定側は入口rowだけを残す」のうち、材料row（Layout group 4行＋Personalization
group）の除去が本Issue（段階(b)）の対象である。run入口row（manual organization直行row、
#232でGeneral groupへ昇進）の除去は、spec 232の改訂（AC-1案内先・AC-3入口row）と
onboarding hint更新とともに[Issue #370][4]が所有する（disposition §2.3、§5）。

## Outcome

材料面への設定側重複導線が消える。ユーザーは設定 → Home screen → Organizer（hub入口row）
からhubに入り、材料セクションから分類・ユーザー定義カテゴリ・配置ロック・使用状況ヒントへ、
常設診断導線から診断へ到達できる。設定 → Home screenからは材料系organizer row
（Layout groupの4行とPersonalization group）が消える。manual organization直行rowは
#370まで現行どおり残る（暫定併存。spec 232 AC-3は本Issueでは不変）。各材料画面の実装・
store契約・AUTHORING/RUN排他leaseは一切変わらない（navigation edgeの削除のみ）。

## Scope

- 設定 → Home screenのLayout groupからorganizer系row 4件（Placement locks /
  Organizer diagnostics / Category overrides / Custom categories）を削除する。
- 設定 → Home screenのPersonalization group（heading＋記録toggle＋Usage Access行）を
  削除する。T-06の材料面は#366が導入したhub側の使用状況material（共有表現）に一本化する。
- 下位画面（材料各画面・診断画面・run面）は既存実装をそのまま受け、routeの追加・変更を
  行わない。変わるのはnavigation edge（どの面から開けるか）の削除のみである。
- 入口表記の追記（契約不変）を同じPRで行う: specs 38 / 99 / 336（disposition §3.2/§3.7/
  §3.8が#367に割当て）、spec 203 U-2とspec 138（入口がsettings rowからhub経由になった旨の
  注記。U-2本体のJIT改訂は#371、138は画面・route不変）、spec 123のinventory evidence文書
  （`docs/assessment/evidence/issue-123-ui-mapping.md`）。
- secondary entry（onboarding floating proposal T-19、workspace長押しlock付与 T-20、
  run面safe terminalからの診断導線、選択面からのrun-in AI相談）はTO-BE §5.2どおり
  すべて現行のまま維持する。本Issueが触れるのは設定Home screenの材料rowのみである。

## Non-goals

- General groupのmanual organization直行rowの廃止、onboarding再入口hintの案内先・copy
  更新、specs 232 / 53の改訂（D-16表記、#370が所有。D-01「入口rowだけを残す」の完成は
  #370で行う）。本Issueでは#232のoracle（General group配置・label不変）とhint oracle
  （path文言3 label）を無編集で維持する。
- strategy pickerの移設・run面からの撤去（D-03、#368）。run面のstrategy pickerは
  本Issueでは触れない。
- 材料各画面のUX再設計、store契約・AUTHORING lease・書込み経路の変更。
- Usage Access要求のJIT化（D-07、#371）。JIT要求は出ない。spec 203のU-2本体改訂は
  #371が所有し、本Issueは入口表記（settings常設row → hub T-06）の注記のみを行う。
- run面の表示統合・canonical順序・T-07前置き面（D-05/D-06、#369）。
- status cardの拡張（#374/#375/#376）。hubのstatus card・開始CTA・診断導線の構成は
  #366実装のままとし、本Issueで変更しない。
- lockのworkspace長押しdialog（T-20）の変更。flow外入口として現行どおり機能する。
- 設定検索への新規登録・削除（現行のpreference画面に設定検索indexは存在しないため
  対象外）。

## Domain language

- **材料**: `CONTEXT.md` の「材料 (organizer materials)」（分類・ロック・整理方針・
  使用状況ヒントの総称。#365が確定）をそのまま使用し、本specでは複製しない。
- **Organizer hub**: `CONTEXT.md` の「Organizer hub」。設定側には入口rowだけを残す旨も
  語彙の定義に含まれる（本specの段階契約の正本）。
- **T-02〜T-06**: organizer-to-be-ux.md §5.1の表面ID。T-02=分類、T-03=ユーザー定義
  カテゴリ、T-04=配置ロック、T-05=整理方針（#368）、T-06=使用状況ヒント。
- **入口row**: 設定 → Home screenからhub（T-01）を開く`NavigationActionPreference`
  （label `organizer_hub_title`。#366で実装済み）。

## Behavior scenarios

### Scenario: hub材料セクションから各材料画面へ到達できる

Given #366実装済みのhubがあり、設定 → Home screen → hub入口rowからhubが開く
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

### Scenario: 設定 → Home screenから材料系organizer rowsが消える

Given 本Issueの変更が適用されている
When 設定 → Home screenを表示する
Then Layout groupにorganizer系row（placement locks / diagnostics / category
overrides / custom categories）が存在しない
And Personalization group（heading＋記録toggle＋Usage Access行）が存在しない
And organizer由来のrowはhub入口rowとmanual organization直行rowが暫定併存する
（段階契約。manual rowの廃止は#370が所有し、D-01の「入口row 1件」は#370で完成する）
And hub入口rowからhubが開き、材料・診断・開始CTAへ到達する

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

### Scenario: spec 232の入口再発見契約は#370まで不変である

Given 本Issueの変更が適用されている
When onboarding提案で「後で」が選ばれ、6秒hintが表示される
Then hintのpath文言（`settings_button_text` → `home_screen_label` →
`manual_organization_title`）は実在するlabelを指し続け、そのpathを辿るとmanual
organization run面へ到達する
And spec 232 AC-3の「`Organize home layout`入口がGeneral groupに配置され、label /
destination / 副説明が不変」契約は本Issueで変更されない（改訂は#370が所有）
And onboarding提案の「確認」CTAの接続先（`OrganizationEntry.ONBOARDING`経由のrun面）は
変更されない

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
  `HomeScreenManualOrganization`）を引数含め再利用する。
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
- 設定 → Home screenから材料系organizer rowが消えることは、organizerの機能がTalkBack
  から到達不能になることを意味しない（hub入口row 1 tapで材料・診断・開始に到達する）。
- onboarding再入口hintのpath文言は本Issueでは変更しない。spec 232の「hintは実際に
  ユーザーが見る設定labelから構成されdriftしない」契約は、参照先row
  （`manual_organization_title`、General group）が本Issueで存続することにより維持される。
  参照先のhub入口rowへの差し替えは#370が行う。
- 削除により未使用になるstring resource（`organizer_personalization_section`）は同じPRで
  削除し、孤立したuser-visible参照を残さない。新規のhardcoded literalを導入しない。

## Acceptance criteria

- [ ] **MAT-AC-01**: hubの材料セクションから分類（T-02）・ユーザー定義カテゴリ（T-03）・
      配置ロック（T-04）の各既存画面へ到達でき、hub材料セクション内の使用状況material
      （T-06）が記録toggleとUsage Access行として機能する。hubから診断（spec 138
      route）へ到達できる。（Issue受入1）
- [ ] **MAT-AC-02**: 設定 → Home screenから材料系organizer rowが消えている: Layout
      groupの4 row（placement locks / diagnostics / category overrides / custom
      categories）とPersonalization group（heading＋記録toggle＋Usage Access行）が
      存在しない。organizer由来のrowはhub入口rowとmanual organization直行rowの暫定
      併存であり、manual rowの廃止によるD-01「入口row 1件」の完成は#370が所有する
      （段階契約。この解釈をIssue #367へ記録する）。（Issue受入2の段階実装、D-01）
- [ ] **MAT-AC-03**: 既存store・lease契約（AUTHORING/RUN排他）に変更がない。run state
      machine、spec 13/52/99/336/38/203/271/138の契約面、diagnostics event、
      persistent store、permissionにdiffが存在せず、材料画面を直接composeする既存
      instrumentation（`CategoryOverridePreferencesInstrumentationTest` /
      `CustomCategoryPreferencesInstrumentationTest` / `OrganizerLockScreenTest` /
      `ManualOrganizationPreferencesInstrumentationTest`）が無編集でgreenである。
      （Issue受入3・4）
- [ ] **MAT-AC-04**: 入口表記の追記（契約不変）が同じPRで行われている: specs 38 / 99 /
      336に「入口はhub経由になった旨」の追記（disposition割当分）、spec 203 U-2と
      spec 138の入口注記、spec 123のinventory evidence文書の設定row削除反映。
      対応する実装と整合している。（Issue受入4・Spec節）
- [ ] **MAT-AC-05**: navigation instrumentation testが更新されている: 設定 → Home
      screenの材料row不在assert（4 row＋Personalization group。LazyColumnの未compose
      nodeがsemantics treeに現れないため、全list走査後の不在観測とする）と、hub起点
      導線（Home screen → hub入口row → 材料/診断navigation）への付け替え。
      `OrganizerDiagnosticsRouteInstrumentationTest` のsettings直接entry oracleの更新と、
      旧oracleがなぜobsoleteか（D-01材料集約・TO-BE §5.2の設定側材料row廃止）がPRに
      記録されている。（Issue受入5）
- [ ] **MAT-AC-06**: 使用状況materialの単一の真実: hub T-06の記録toggleは既存preference
      を、Usage Access行は既存app-op読取と`ON_RESUME`再読取（spec 203 U-2）を操作し、
      設定 → Home screen側に二重インスタンスが存在しない。（#366 HUB-AC-06の後継状態）
- [ ] **MAT-AC-07**: spec 232の受入条件が本Issueで変更されていないことの否定的観測:
      `Organize home layout`行がGeneral groupに残存すること、hint path文言
      （`settings_button_text` / `home_screen_label` / `manual_organization_title`）が
      実在labelを指し続けること。#232 oracle
      （`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`）とhint oracle
      （`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`）が無編集でgreenである。
- [ ] **MAT-AC-08**: lockのworkspace長押しdialog（T-20）とlock管理画面の導線が現行
      どおり機能し、削除により未使用になったstring resource（`organizer_personalization_section`）
      が`values/` と `values-ja/` の双方から一貫して除去されている。hardcoded literalの
      新規導入がない。（Non-goals機械的保証＋孤立resource排除）

## Test oracle

| AC | Evidence |
|---|---|
| MAT-AC-01 | UI instrumentation test: hub材料セクション → T-02/T-03/T-04 navigation、T-06行の機能、hub → diagnostics navigation（既存hub oracleの再実行。emulator screenshot（light/dark × ja/default）） |
| MAT-AC-02 | UI instrumentation test: 設定 → Home screenの否定的観測（4 row・Personalization group 2行の不在。全list走査後のassert）＋hub入口row・manual rowの存在assert＋hub遷移assert |
| MAT-AC-03 | 既存testの無編集green（材料画面直接compose系、run面系）+ 実装PR diff review（`organizer/application/**`・store・run面・route objectの契約面無編集） |
| MAT-AC-04 | specs 38/99/336/203/138のdiff review（入口表記の追記のみで契約節無変更）+ `docs/assessment/evidence/issue-123-ui-mapping.md`のdiff |
| MAT-AC-05 | 更新後の`OrganizerDiagnosticsRouteInstrumentationTest`のdiffと実行結果 + obsolete理由のPR記録 |
| MAT-AC-06 | UI instrumentation test: Home screenにT-06行が不在（全list走査後）、hub側でT-06行が機能しpreference stateが一致、`ON_RESUME`再読取 |
| MAT-AC-07 | `OnboardingOrganizationProposalInstrumentationTest`（hint oracle＋#232 oracle）無編集green + 設定画面へのGeneral group行残存assert |
| MAT-AC-08 | 既存`OrganizerLockScreenTest`（long-press popup経由のlock authoring oracle）green + `organizer_personalization_section`のreference grep（0件）+ `./gradlew spotlessCheck` |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane、CI `final-status` green。

## Contract notes

1. **（解決済み・owner review記録）manual organization直行rowとhintは#370まで維持**:
   初版draft（`a50f074a`）はD-01「設定側は入口rowだけを残す」を本Issueで完成させる解釈
   （General group直行rowの廃止＋hint path文言更新）をContract notesで提示したが、
   owner review（Issue #367 review、2026-09-19）で否認された。accepted dispositionの
   ownership（spec 232のAmendは#370、§5のdoc更新順、§7.2段階(b)は「Layout group/
   Personalization groupの材料row移動」のみを定義）に従い、本specは材料rowに限定する。
   段階中、設定 → Home screenにはhub入口rowとmanual organization直行rowが暫定併存し、
   Issue受入2「organizer系rowsが残っていない（入口rowのみ）」の完全充足は#370の
   manual row廃止＋spec 232/53改訂＋hint更新と同時に行う。この段階解釈をIssue #367へ
   記録し、#370のscopeへmanual row廃止が含まれる旨を追記する（#370起票本文には
   明示がないため）。
2. **T-06は新規destinationを作らない**: #366実装済みのとおり、使用状況material
   （`OrganizerUsageMaterialRows`）はhub材料セクションに組み込まれた共有表現であり、
   独立したT-06 destinationは本Issueでも作らない。TO-BE §5.1のT-06は「ユーザーに
   区別して見せる面」の目的定義であり、2行のmaterial sectionがhub内でその目的を満たす。

## Dependencies

- **前提（実装済み）**: #366（[PR #380][3]、merge commit `32c72094a4`。hub destination
  `OrganizerHubPreferences`、材料セクション、T-06共有composable `OrganizerUsageMaterialRows`、
  診断常設行、hub入口row）、specs 38 / 99 / 336 / 203 / 271 / 138 / 123（implemented）、
  spec 232（implemented）。
- **正本（accepted・merge済み）**: organizer-to-be-ux.md（PR #364）、
  organizer-disposition-migration.md（PR #378。§7.2段階(b)・§8のorderingとownershipの
  正当な根拠）、`CONTEXT.md` の材料/hub語彙（#365、PR #379で確定）。
- **後続**: #368（strategy移設・特例廃止。本Issue完了が前提）、#371（Usage Access
  JIT。T-06常設rowが存在することが前提）、#370（manual row廃止＋specs 232/53改訂＋
  hint更新。本Issueの段階契約の完成）。

## Open questions

実装開始前に解消が必須な問いはない。非blocking事項:

1. 削除により未使用化するstringが`organizer_personalization_section`のみであることの
   最終確認は、実装PRのreference grepで確定する。
2. #370の起票本文に「manual organization直行rowの廃止」が明示されていないため、本Issueの
   PR作成時に#370へのscope追記（またはIssue commentでの責務記録）を行う。spec 370本文の
   「spec 232改訂（AC-1の案内先、AC-3の入口row位置は#367と整合）」は、本specの段階契約
   （manual rowは#367段階では存続）と整合する。

## Change history

- 2026-09-19: Draft created for #367（spec/plan整備task、branch `issue-367-spec-plan`
  `a50f074a`）。accepted TO-BE契約（organizer-to-be-ux.md @ main `3076bdae7e`）、
  #366 spec/plan draft（`bf00f96175`）、現行実装調査を入力に作成。
- 2026-09-19: **Re-entry revision（本revision）**。owner review（Issue #367 review
  comment、「Changes requested」）の2指摘に対応:
  - **高（spec 232/#370責務境界）**: draftがGeneral group直行row廃止とhint path文言更新を
    含み、accepted spec 232 AC-3とaccepted dispositionのownership（spec 232 Amend= #370、
    実装順 #369→#370）を先取りしていた。scopeを材料row（Layout group 4行＋Personalization
    group）に限定し、MAT-AC-02を段階契約へ修正、hint関係の変更とACを削除し、#232/hint
    oracleの無編集維持をMAT-AC-07として契約化した（Contract notes 1に解決記録）。
  - **中（re-entry rule）**: 起草baseline `3076bdae7e` → 本revision baseline
    `1285c13cc6`（origin/main、2026-09-19）の差分を再照合した: PR #378（disposition
    accepted）、PR #379（#365正本改訂。`CONTEXT.md` 語彙確定・requirements FR-006/FR-017
    のhub表記）、PR #380（#366実装。merge commit `32c72094a4`）、PR #381（#366
    implemented docs）。#365/#366をCLOSED/implementedの実態へ更新し、draft時点の#366接続面
    （route/composable/string名）を実装実測へ置換し、accepted dispositionをordering/
    ownershipの正式根拠として参照した。TO-BE §5.2のprimary/secondary entryを再確認し、
    本scopeが設定Home screenの材料rowに限定されるためsecondary entry群（T-19/T-20/
    run-in AI/safe terminal診断）を誤って排除しないことをScope/Non-goalsに明記した。

[1]: https://github.com/nunu1733/NunuLauncher/issues/367
[2]: https://github.com/nunu1733/NunuLauncher/issues/366
[3]: https://github.com/nunu1733/NunuLauncher/pull/380
[4]: https://github.com/nunu1733/NunuLauncher/issues/370
