# Implementation Plan: onboarding提案と再表示hintの接続先をhub時代の情報設計へ更新する

> Issue: #370
> Spec: [spec.md](./spec.md)
> Status: draft（spec未承認。本planはbaseline `3076bdae7e` 時点のコード調査に基づく）

## Current evidence（baseline `3076bdae7e`、2026-09-19確認）

- **「確認」経路は既にadmission直行である**: `OrganizationOnboardingProposalView.beginReview()`
  （[OrganizationOnboardingProposal.kt:412-435](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）
  は `controller.review(admitReview)` を `Dispatchers.IO` で呼び、`admitReview` の既定実装は
  `ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)`
  （同file [215-217](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)
  と [269-271](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）。
  `Started` のときのみ `resolved = true; close(false)` のうえ
  `PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization(OrganizationEntry.ONBOARDING))`
  （[424-429](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）へ遷移し、
  `Busy` のときはbuttonを再有効化して提案上にとどまる。`REVIEWED`記録はadmission成功後である
  （`OrganizationOnboardingProposalController.review`、同file [124-130](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）。
  **よって#370の遷移本体のproduction code変更は、#369適用後にT-07前置き面がこの経路に
  現れないことを保証するtest（guard）が主であり、遷移コードの再設計は不要と想定する**
  （「まだ証拠のない実装詳細は確定事項として書かない」の前提つき。#369の実装形態次第で
  route側に最小の調整が発生する可能性をUnverified areasに記載）。
- **routeとtrigger導出**: `OrganizationEntry`（`MANUAL`/`ONBOARDING`）と
  `HomeScreenManualOrganization(entry)` は [PreferenceRoutes.kt:119-133](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt)
  にあり、`trigger` は `entry` から純粋導出される（`ONBOARDING → Trigger.ONBOARDING_PROPOSAL`）。
  navigation graphは [PreferenceNavigation.kt:120-128](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt)
  が `ManualOrganizationPreferences(trigger = route.trigger, ...)` をrenderする。
  routeは安定したcaller contextのみを保持する（#116契約）。
- **hintのcopy構成**: `OrganizationOnboardingReentryHint.reentryBodyText()` は
  [OrganizationOnboardingProposal.kt:580-585](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)
  で `organization_onboarding_reentry_hint_body` に3つの実label
  （`settings_button_text` / `home_screen_label` / `manual_organization_title`）を渡す。
  寿命は `REENTRY_HINT_TIMEOUT_MS = 6_000L`（[574](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）。
  string resourceは `lawnchair/res/values/strings.xml:1287-1288` と
  `lawnchair/res/values-ja/strings.xml:369-370`（format template「ホーム画面を長押し →
  %1$s → %2$s → %3$s」）。
- **案内先の実体（#367適用前）**: General group（[HomeScreenPreferences.kt:85](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)）内に
  #232で昇格されたmanual organization直行row
  （[104-109](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)、
  label `manual_organization_title`、destination `HomeScreenManualOrganization()`）がある。
  #366 draft planはhub入口row（`NavigationActionPreference`、label `organizer_hub_title` /
  subtitle `organizer_hub_summary` / destination `HomeScreenOrganizer`。**draft段階の仮名で、
  最終名は#366実装が所有**）を同一groupへ追加し、#367 draft（MAT-AC-02）が直行rowを廃止する。
- **旧案内先を固定したtest**:
  - `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
    - `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`（[351](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      hint announcementが3label（`manual_organization_title` を含む）を含むことassert（[368-380](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）。
    - `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`（[525](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      `manual_organization_title` 行がGeneral headingと `home_screen_actions` headingの間にあることassert。
    - `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface`（[330](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      確認 → admission → PreferenceActivity resumeを観測（[awaitResumedPreferenceActivity](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)、[1099-1115](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）。route引数までは深くassertしない。
  - `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt`:
    controller契約（admission→REVIEWED記録順序、Busy非消費）。hint copyには触れない。
- **先行spec draftsとの境界**:
  - #369 draft（`3c39ceb2f8`）はrun面Idle/Cancelled分岐のT-07置換を所有し、
    「onboarding提案のflow接続変更（#370）」をnon-goalsへ明示。
    影響file listに `OrganizationOnboardingProposal.kt` / `PreferenceRoutes.kt` を含む
    （並行変更の可能性があるためrebase時の調整点）。
  - #367 draft（`a50f074ac2`）のMAT-AC-07は「hint path文言が実在する設定path
    （設定 → Home screen → hub入口row）を指すこと」を受入条件とし、#370を
    「本Issueのhint path文言更新を上書きし得る案内先変更の所有者」と位置づける。
  - 処分文書draft（PR #378）§3.4: spec 53 = Continue（表記更新のみ、test migrationは
    hint・遷移先のtest更新のみ）。§4: spec 232 = Amend（AC-1案内先）。§5: 順序4 = specs 53/232。
- **確認済み事実と推測の区別**: 上記のpath・行番号・契約引用はsource読み取りによる確認済み。
  「#369適用後のrun面route構成」「hub入口rowの最終resource名」はdraft段階であり推測を
  含まないよう本planでは参照のみに留める。

## Design

### Ownership / module boundaries

- 変更は **presenter層のcopy（hint）とnavigation経路の契約固定** に限定される。
- planning / application / diagnostics / run state machine / route schemaには変更を入れない。
- spec 53 / spec 232の文書改訂は#370のscope内（Issue本文Scope、disposition §3.4/§4）であるが、
  正本文書のstatus遷移（accepted扱い）は行わず、実装PR reviewが承認点である。

### Interfaces / seams

- **変えないseam**: `ManualOrganizationRun.start(trigger)`、`StartOutcome`、
  `OrganizationOnboardingProposalController.review(admit)`、
  `HomeScreenManualOrganization(entry)` route（引数 schema・trigger導出を含む）。
- **観測するseam（test）**: proposal UI gate（instrumentation既存の `ProposalGate` 相当）、
  `OrganizationOnboardingReentryHint.reentryBodyText()` / `combinedAccessibilityText()`
  （既存internal test seam）、navigation後の表示面（#369適用後のrun面構成）。

### Data / control flow（変更後）

1. 「確認」tap → `beginReview()` → `start(ONBOARDING_PROPOSAL)`（変更なし）。
2. `Started` → `REVIEWED`記録 → `close(false)` → `HomeScreenManualOrganization(ONBOARDING)` へ遷移（変更なし）。
3. 遷移先ではT-07前置き面を介さず、admitted runの進行表示が現れることを **testで固定**
   （#369適用後に成立するguard。#369前は「T-07が存在しない」ため現行testで同等）。
4. 「後で」tap → `controller.defer()` → `close(false)` → hint表示（変更なし）。
   hintのpath構成第3引数をhub入口row labelに変更（本Issueの唯一のproduction code差分）。

### Expected files to change

| File | 変更 |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | `reentryBodyText()` の第3引数を `manual_organization_title` → hub入口row label resourceへ。javadoc（[462-467](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）の案内先記述を更新。それ以外の編集は想定しない |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | `organization_onboarding_reentry_hint_body` のformat templateは3引数のままで不変の想定。案内先の語順・接続表現をja/ENで調整する場合のみtemplateを修正（placeholder一致を維持） |
| `tests/organizer-instrumentation/.../OnboardingOrganizationProposalInstrumentationTest.kt` | hint label構成assert（[368-380](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）の第3label差し替え。`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`（[525](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）は#367適用状態に応じて hub入口row / 直行row のいずれかを実在labelでassert（#367 merge済みならhub入口row版へ更新し obsolete理由をPR記録）。確認経路のguard assert追加 |
| `specs/53-onboarding-organization-proposal/spec.md` | 表記更新: §3.2 entry契約（T-07省略・方法固定の明記）、§5.2（review surface表記のrun面参照化）、§5.3（Review destination ownerの接続先表記）。AC-003 / outcome表 / §3.3 / §4 は触れない。frontmatter `updated:` とChange historyに追記（statusはAcceptedのまま、表記のみ改訂の旨を記録） |
| `specs/232-organizer-reentry-discoverability/spec.md` | AC-1案内先をhub入口rowへ、AC-3を#367整合（General groupのorganizer系rowはhub入口row）へ。「Scenario: Later 選択直後に再開場所が案内される」のThen行、「Scenario: Home settings の Organizer 入口が上位セクションで発見できる」を連動更新。Test oracleの該当行更新。frontmatter `updated:` とChange historyに追記 |
| `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt` | 変更なし想定（controller契約は不変）。green確認のみ |

**#367 merge済みの場合の重複排除**: #367が先にhint path文言を実装していた場合、
第1tableの1行目・2行目とhint assert更新は #367の実装と同一結果になり得る。
その場合は#370では「既に実在するpath（hub入口row）を指すことの確認test」と
spec 232 AC-1の契約改訂・理由記録を実施し、実装差分を重複させない
（spec.md Compatibilityの#367との整合節どおり）。

### Compatibility constraints

- 実装着手は #365（正本改訂）merge後、かつ #366（hub入口row存在）と #369（統合run面）
  merge後（Issue本文 `Depends on`）。#369のrebaseで `OrganizationOnboardingProposal.kt` /
  `PreferenceRoutes.kt` に触れた場合は本planのCurrent evidenceを行番号込みで再確認する。
- #367が未mergeの間は、General groupにmanual organization直行rowとhub入口rowが並存する。
  hintはどちらの順序でも「実在する設定path（hub入口row）」を指すため、
  #367の前後どちらでlandしても契約を満たす。
- route schema（`OrganizationEntry`）・trigger導出・minification keep（#116）は不変であり、
  backup/restoreやdowngradeへの影響はない。

### Migration / rollback

- persistent state / Launcher DB / schema / preference keyの変更はゼロ。
  migration対象はspec 53/232の文書とtestのoracleのみである。
- rollback: 実装PRのrevertでcopy・表記・testが現行へ戻る。文書改訂も同一PRのため
  部分残留しない。#367が先にhint文言を変更していた場合、revertしても#367側の
  MAT-AC-07契約（実在path指すこと）は維持される（#370 revertで旧labelへ戻す場合は
  #367の契約と矛盾しない実装状態をPR記録で確認する）。

### Failure handling

- hint表示は既存のbest-effort契約（runCatching、defer永続化を壊さない、
  `OrganizationOnboardingProposal.kt:601-604`）を維持し、copy差し替えでこの経路を触らない。
- admission Busy経路は既存実装（button再有効化・提案維持）の回帰testで保護する。

## Testing strategy

- **unit**（`app.lawnchair.organizer.*` gate）: 既存controller回帰（admission→REVIEWED順序、
  Busy非消費、provenance fail-closed）が無編集でgreenであること。hint copy合成は
  `reentryBodyText()` の既存internal seamでlabel参照をassert（実render labelとの一致）。
- **instrumentation**（`OnboardingOrganizationProposalInstrumentationTest`）:
  1. hint label構成assertの更新（hub入口row labelを含む、旧labelを含まない。EN/ja）。
  2. 確認tap → admission → 遷移先観測の拡張: 方法選択面（T-07前置き）が介在しないことと、
     admitted runの進行表示が現れることの観測（#369適用後のrun面構成に対するassert。
     #369の面ID・compose構成が確定するまで具体的assert対象を固定しない）。
  3. `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold` の#367整合
     （merge順に応じたlabel/row assert）。
  4. 既存のdismiss/timeout/focus/200% font scale/表示失敗系が無編集でgreenであること
     （copy変更のみであることの回帰証拠）。
- **string確認**: `values/` と `values-ja/` の `organization_onboarding_reentry_hint_*` の
  placeholder一致（spec 123 AC-5方式）、reference grep（`manual_organization_title` を
  hint構成から外した後に孤立参照がないこと。label自体は#367まで実在するため削除しない）。
- **device evidence**: emulatorで「後で」→hint表示（ja/EN、light/dark）と「確認」→
  run面遷移のscreenshotをPRへ記録（spec 232 AC-5のevidence慣行に倣う）。
- 本Issueは `risk: layout-data` に該当しない（persistent state・DB接触なし）ため
  high-risk独立evidence gateの対象外と判断するが、PR label判断は実装PRで最終確認する。

## Incremental implementation order

1. **spec 232/53改訂内容の最終確定**（#365/#366/#367/#369のmerge状態を再確認し、
   spec.mdのOpen questions 1〜3を解消。#367のContract notes 1のowner review結果を反映）。
2. **hint copy実装**（第3引数差し替え + string調整 + instrumentation hint assert更新）。
   このstep単独でland可能（#366 merge後）。
3. **確認経路のguard test**（#369 merge後。方法選択面非介在 + 統合run面到達の観測）。
4. **spec 53/232の文書改訂 + obsolete理由のPR記録**（2〜3と同一PR）。
5. **検証一式の実行とPR記録**（unit gate、instrumentation lane、string確認、
   emulator evidence、`git diff --check`、`spotlessCheck`）。

## Dependencies / blockers

- **#366（OPEN）**: hub入口rowの実在がhint案内先の前提。merge後に実装着手。
- **#369（OPEN）**: 「統合run面を辿る」ACとguard testの前提。merge後にstep 3着手。
- **#365（OPEN）**: 正本改訂（organization-run-ux §2.1/§2.2、CONTEXT語彙）。
  AGENTS.md「正本を先に」によりmerge後に実装着手（spec/plan執筆は完了済み）。
- **#367（OPEN）**: merge順によりstep 2の実装/確認が入れ替わる（前述）。
- **PR #378（処分文書、proposed）**: merge時に§3.4/§4/§5の文言を再確認し、
  本spec/planとの差異を解消してから着手する。

## Risk

- **並行変更との競合**: `OrganizationOnboardingProposal.kt` は#369の影響file listに含まれる。
  影響はrebase時の行番号ずれと、#369がroute周りに手を入れた場合のguard test対象の
  再確認に限定されると想定するが、#369実装後に再検証する。
- **hub入口rowのresource名未確定**: #366 draft planの `organizer_hub_title` は仮名。
  実装前に#366の実装PRで実名を確認し、hintはresource参照で値を複製しない。
- **旧labelの参照残存**: `manual_organization_title` は#367まで実在するため、
  hint構成から外してもresource削除は行わない（#367/#377のcleanup対象）。

## Explicitly unverified areas

- #369適用後のrun面route・compose構成（T-07前置き面の実装形態、onboarding routeへの
  影響の有無）。guard testの具体的assert対象は#369実装後に確定する。
- #366適用後のhub入口rowの最終label/subtitle/resource名。
- #367のContract notes 1（manual organization直行row廃止の解釈）のowner review結果
  （否認された場合はspec 232 AC-3改訂文言を追従させる）。
- post-#369のonboarding由来routeでterminal後にT-07（Idle面）へ戻った際の
  「AIに相談」分岐の表示有無（spec.md Open questions 1。run面forkをしないことを契約とし、
  最終形は#369実装で確認）。
- 上記はいずれも実装着手前の再確認が必須であり、本planの確定事項ではない。
