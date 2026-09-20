# Implementation Plan: onboarding提案と再表示hintの接続先をhub時代の情報設計へ更新する

> Issue: #370
> Spec: [spec.md](./spec.md)
> Status: draft（spec未承認。本planはbaseline `171d0bcf10` 時点のコード調査に基づく
> （#368実装PR #384、#369 spec/plan PR #386 merge後）。#369実装merge後に、
> run面の実リソース名・行番号を最終確認のうえ実装着手する）

## Current evidence（baseline `171d0bcf10`、2026-09-20確認）

- **受入済み#369 spec/planがguard testの対象seamを確定させている**
  （[specs/369-run-display-integration/spec.md](../369-run-display-integration/spec.md) /
  [plan.md](../369-run-display-integration/plan.md)、PR #386 merge済み）:
  - state→面の写像はUI層の純関数 `manualOrganizationFace(state)` に集約され
    （369 plan「Modules and interfaces」、Expected filesの`ManualOrganizationPreferences.kt`行）、
    `Idle`/`Cancelled` → T-07前置き面（#372統合前のtransitional構成: 主CTA「そのまま整理」の
    みで「AIに相談」の選択肢行は新設しない。RD-1）、`Capturing`/`CandidateDetection`/`Planning`
    → T-09統合progress面（`preparationPhase`由来のphase行＋中断row）、失敗系5状態 →
    T-13統合面「実行できませんでした」、`NoChanges`/`Stale(APPLY_BLOCKED)` → T-12結果面の変種、
    と対応する。`tests/unit/.../ManualOrganizationFaceTest`（#369実装で新規）が
    table-driven unit testを持つ。
  - 369 spec Non-goals: 「現行のonboarding『確認』は `start(Trigger.ONBOARDING_PROPOSAL)`
    によるadmission直行であり、T-07前置きを経ない（現行契約どおり。T-07はMANUAL entryの
    開始点としてのみ導入する）」——本件の接続契約固定を#370へ委譲している。
  - **よってOCB-AC-01のoracleはこのseamへ固定できる**: admitted状態（`Capturing`等）→
    T-09対応は`ManualOrganizationFaceTest`のtable（無編集green）で契約され、T-07は
    `Idle`/`Cancelled`からのみ到達する。instrumentation guardは「確認 → admission →
    遷移先run面でT-07主CTA（「そのまま整理」）のsemanticsが観測window中に一度も出現せず、
    T-09準備中面の要素に到達する」ことを観測する。#369実装が導入する実際のstring
    resource名（T-07主CTA・T-09準備中見出し/phase行）は、#369実装merge後に
    実装着手時に実装へ合わせて固定する（本planのre-entry必須項目）。

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
  （「まだ証拠のない実装詳細は確定事項として書かない」の前提つき。#369実装merge後に
  route側へ触れた差分が無いかを再確認する）。
- **routeとtrigger導出**: `OrganizationEntry`（`MANUAL`/`ONBOARDING`）と
  `HomeScreenManualOrganization(entry)` は [PreferenceRoutes.kt:119-133](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt)
  にあり、`trigger` は `entry` から純粋導出される（`ONBOARDING → Trigger.ONBOARDING_PROPOSAL`）。
  navigation graphは [PreferenceNavigation.kt](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt)
  が `ManualOrganizationPreferences(trigger = route.trigger, ...)` をrenderする。
  routeは安定したcaller contextのみを保持する（#116契約）。
- **hintのcopy構成**: `OrganizationOnboardingReentryHint.reentryBodyText()` は
  [OrganizationOnboardingProposal.kt:580-585](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)
  で `organization_onboarding_reentry_hint_body` に3つの実label
  （`settings_button_text` / `home_screen_label` / `manual_organization_title`）を渡す。
  寿命は `REENTRY_HINT_TIMEOUT_MS = 6_000L`（[574](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）。
  string resourceは `lawnchair/res/values/strings.xml:1296-1297` と
  `lawnchair/res/values-ja/strings.xml:378-379`（format template「ホーム画面を長押し →
  %1$s → %2$s → %3$s」）。javadoc（[462-468](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)）
  も「Organize home layout」を案内先として記載しており、同一PRで更新する。
- **案内先の実体（#367 merge後の暫定併存状態）**: 設定 → Home screenのGeneral group
  （[HomeScreenPreferences.kt:74](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)）には、
  manual organization直行row（[97-101](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)、
  label `manual_organization_title`、destination `HomeScreenManualOrganization()`。Issue #232で
  Generalへ昇格、comment [92-96](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)は
  「removing this row is owned by #370」を明記）と
  hub入口row（[105-109](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)、
  label `organizer_hub_title` / subtitle `organizer_hub_summary` / destination `HomeScreenOrganizer`）が
  **併存**する。row削除後も `manual_organization_title` は
  [ManualOrganizationPreferences.kt:219](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt)
  （run面scaffold title）で実在用途が残るため、resource削除は行わない（#377所有）。
  `HomeScreenManualOrganization()`（MANUAL既定）の他の使用箇所はhub開始CTA
  （[OrganizerHubPreferences.kt:142](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt)）と
  onboarding確認（`OrganizationOnboardingProposal.kt:427`）であり、直行row削除で
  手動開始はhub CTA経由に集約される（trigger導出は不変）。
- **旧案内先・旧入口構成を固定したtest**:
  - `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
    - `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`（[351](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      hint announcementが3label（[373-380](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)、
      3番目が `manual_organization_title`）を含むことassert。
    - `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`（[525](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      `manual_organization_title` 行がGeneral headingと `home_screen_actions` headingの間にあり
      first viewport内であることassert（[528](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)の
      entryLabel取得）。
    - `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface`（[330](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）:
      確認 → admission → PreferenceActivity resumeを観測
      （[awaitResumedPreferenceActivity](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)、[1099-1115](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt)）。
      route引数・遷移先の面構成は深くassertしていない（本Issueで拡張）。
  - `tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt`
    - `homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`（[486](../../tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt)）:
      直行rowとhub入口rowの**暫定併存**をassert（[515-520](../../tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt)、
      comment「#370 owns the rest」）。本Issueで「直行row不在＋hub入口row表示」へ更新。
  - `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt`:
    controller契約（admission→REVIEWED記録順序、Busy非消費）。hint copyには触れない。
- **先行Issueの状態（すべてre-entry時に再確認済み）**: #365（正本改訂、PR #379）、
  #366（hub、PR #380）、#367（材料移動、PR #382）、#368（strategy移設、PR #384）、
  処分文書（PR #378）、#369 spec/plan（PR #386）はmerge済み。#368によりrun面から
  strategy pickerは撤去済みである（本Issueはrun面へ触れない前提の一部）。
  **#369実装（同Issue Phase 2）のみmerge待ちであり、実装着手の前提である**。
  #369は「onboarding提案のflow接続変更（#370）」をnon-goalsへ明示済み
  （accepted spec。Idle/Cancelled分岐をtransitional T-07前置き面へ置換し、
  T-09統合progress面・T-13統合失敗面を導入する）。
- **確認済み事実と推測の区別**: 上記のpath・行番号・契約引用はsource読み取りによる確認済み。
  「#369実装が導入する実際のstring resource名・compose構成（T-07主CTA、T-09準備中
  見出し/phase行）」は実装前のため、実装着手時（#369実装merge後）に実装へ合わせて
  固定する（本planのre-entry必須項目）。

## Design

### Ownership / module boundaries

- 変更は **presenter層のcopy（hint）・設定navigation構成（直行row削除）・契約固定test** に
  限定される。
- planning / application / diagnostics / run state machine / route schemaには変更を入れない。
- spec 53 / spec 232の文書改訂は#370のscope内（Issue本文Scope、disposition §3.4/§5順序4）
  であるが、正本文書のstatus遷移（accepted扱い）は行わず、実装PR reviewが承認点である。

### Interfaces / seams

- **変えないseam**: `ManualOrganizationRun.start(trigger)`、`StartOutcome`、
  `OrganizationOnboardingProposalController.review(admit)`、
  `HomeScreenManualOrganization(entry)` route（引数 schema・trigger導出を含む）、
  `HomeScreenOrganizer` destination。
- **削除するもの**: `HomeScreenPreferences` General group内の直行row
  （`NavigationActionPreference` 1件と、それを説明する暫定併存comment）のみ。
  preference key・route・resourceの削除は行わない。
- **観測するseam（test）**: proposal UI gate（instrumentation既存の `ProposalGate` 相当）、
  `OrganizationOnboardingReentryHint.reentryBodyText()` / `combinedAccessibilityText()`
  （既存internal test seam）、navigation後の表示面（受入済み#369 specのsurface seam:
  face mapping純関数`manualOrganizationFace(state)`経由の面構成）、
  設定画面のsemantics tree（row有無）。

### Data / control flow（変更後）

1. 「確認」tap → `beginReview()` → `start(ONBOARDING_PROPOSAL)`（変更なし）。
2. `Started` → `REVIEWED`記録 → `close(false)` → `HomeScreenManualOrganization(ONBOARDING)` へ遷移（変更なし）。
3. 遷移先ではT-07前置き面を介さず、admitted runの進行表示が現れることを **testで固定**
   （#369実装適用後に成立するguard。受入済み#369 specのsurface seamに対する観測）。
4. 「後で」tap → `controller.defer()` → `close(false)` → hint表示（変更なし）。
   hintのpath構成第3引数をhub入口row labelに変更（本Issueのproduction code差分の1つ）。
5. 設定 → Home screenのGeneral groupから直行rowを削除（本Issueのproduction code差分の2つ目）。
   organizer由来rowはhub入口row 1件になる。

### Expected files to change

| File | 変更 |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | `reentryBodyText()` の第3引数を `manual_organization_title` → `organizer_hub_title` へ。javadoc（462-468）の案内先記述をhub入口rowへ更新。それ以外の編集は想定しない |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | General groupの直行row（97-101）と暫定併存comment（92-96）を削除。hub入口row（105-109）は残置。`#232` 由来commentは入口row単独の状態を説明する形へ整理 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | `organization_onboarding_reentry_hint_body` のformat templateは3引数のままで不変の想定。案内先の語順・接続表現をja/ENで調整する場合のみtemplateを修正（placeholder一致を維持）。`manual_organization_title` / `manual_organization_summary` はrun面用途が残るため削除しない |
| `tests/organizer-instrumentation/.../OnboardingOrganizationProposalInstrumentationTest.kt` | hint label構成assert（373-380）の第3label差し替え。`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`（525）をhub入口row版へ更新（General headingとhome_screen_actions headingの間＋first viewport）。確認経路のguard assert拡張（transitional T-07主CTA「そのまま整理」のsemantics不在〔反復サンプリング〕＋T-09統合progress面到達。リソース名は#369実装merge後に固定） |
| `tests/organizer-instrumentation/.../OrganizerDiagnosticsRouteInstrumentationTest.kt` | `homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub` の暫定併存assert（515-520）を「直行row不在（`onNodeWithText`が0件）＋hub入口row表示」へ更新し、commentを#370適用後の状態へ |
| `specs/53-onboarding-organization-proposal/spec.md` | 表記更新: §3.2 entry契約（T-07省略・方法固定の明記）、§5.2（review surface表記のrun面参照化）、§5.3（Review destination ownerの接続先表記）。AC-003 / outcome表 / §3.3 / §4 は触れない。frontmatter `updated:` とChange historyに追記（statusはAcceptedのまま、表記のみ改訂の旨を記録） |
| `specs/232-organizer-reentry-discoverability/spec.md` | AC-1案内先をhub入口rowへ、AC-3を「organizer由来rowはhub入口row 1件（直行row廃止済み）」へ。「Scenario: Later 選択直後に再開場所が案内される」のThen行、「Scenario: Home settings の Organizer 入口が上位セクションで発見できる」を連動更新。Test oracleの該当行更新。frontmatter `updated:` とChange historyに追記 |
| `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt` | 変更なし想定（controller契約は不変）。green確認のみ |

### Compatibility constraints

- 実装着手は #369実装（同Issue Phase 2）merge後（Issue本文 `Depends on`）。
  他の依存（#365/#366/#367/#368/#369 spec/plan/処分文書）はmerge済み。
  #369実装のmergeで `OrganizationOnboardingProposal.kt` / `PreferenceRoutes.kt` /
  run面周辺に差分が入った場合は、本planのCurrent evidenceを行番号込みで再確認してから
  着手する（rebase時の調整点。step 1）。
- 直行row削除により、設定 → Home screenからの手動開始経路はhub CTAへ集約される。
  `MANUAL_FULL` trigger導出は不変（同一route）であり、spec 53 §3.3/§4.3の
  trigger分離契約と矛盾しない。
- route schema（`OrganizationEntry`）・trigger導出・minification keep（#116）は不変であり、
  backup/restoreやdowngradeへの影響はない。

### Migration / rollback

- persistent state / Launcher DB / schema / preference keyの変更はゼロ。
  migration対象はspec 53/232の文書とtestのoracleのみである。
- rollback: 実装PRのrevertでcopy・row構成・表記・testが現行へ戻る。文書改訂も同一PRのため
  部分残留しない。revert後は暫定併存状態（#367適用後・#370適用前）へ戻るのみであり、
  hub入口rowは常時存在するため再発見導線が途切れない。

### Failure handling

- hint表示は既存のbest-effort契約（runCatching、defer永続化を壊さない、
  `OrganizationOnboardingProposal.kt:601-604`）を維持し、copy差し替えでこの経路を触らない。
- admission Busy経路は既存実装（button再有効化・提案維持）の回帰testで保護する。
- 直行row削除は既存row（hub入口row）のcomposition順を変えないため、focus順・
  TalkBack読み順の回帰は設定画面の既存instrumentation（General構成assert更新版）で
  検出できる。

## Testing strategy

- **unit**（`app.lawnchair.organizer.*` gate）: 既存controller回帰（admission→REVIEWED順序、
  Busy非消費、provenance fail-closed）が無編集でgreenであること。hint copy合成は
  `reentryBodyText()` の既存internal seamでlabel参照をassert（実render labelとの一致）。
- **instrumentation**（`OnboardingOrganizationProposalInstrumentationTest`）:
  1. hint label構成assertの更新（`organizer_hub_title` を含む、旧labelを含まない。EN/ja）。
  2. 確認tap → admission → 遷移先観測の拡張: **T-07前置き面が一度も現れないことの直接観測**
     （受入済み#369 specで確定したseamに対する観測: transitional T-07の主CTA
     「そのまま整理」のsemanticsが観測window中の反復サンプリングで一度も出現しないこと
     ＝T-07 render count 0相当）＋ admitted runの進行表示（T-09統合progress面:
     準備中見出し/phase行）への到達観測。unit側は#369実装が導入する
     `ManualOrganizationFaceTest`（face mapping純関数`manualOrganizationFace(state)`の
     table-driven test）が無編集でgreenであること（admitted状態→T-09対応・
     T-07は`Idle`/`Cancelled`起点のみの証拠）を併用する。T-07主CTA・T-09面の
     実際のstring resource名は#369実装merge後に実装へ合わせて固定する。
  3. `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold` の入口row assertを
     hub入口row版へ更新。
  4. `OrganizerDiagnosticsRouteInstrumentationTest.homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`
     の暫定併存assertを「直行row不在＋hub入口row表示」へ更新。
  5. 既存のdismiss/timeout/focus/200% font scale/表示失敗系が無編集でgreenであること
     （copy変更のみであることの回帰証拠）。
- **string確認**: `values/` と `values-ja/` の `organization_onboarding_reentry_hint_*` の
  placeholder一致（spec 123 AC-5方式）。`manual_organization_title` はhint構成から外れるが、
  run面scaffold titleの実在用途が残るためresource削除は行わない（孤立参照grepで確認）。
- **device evidence**: emulatorで「後で」→hint表示（ja/EN、light/dark）と「確認」→
  run面遷移、設定 → Home screenの入口row 1件構成のscreenshotをPRへ記録
  （spec 232 AC-5のevidence慣行に倣う）。
- 本Issueは `risk: layout-data` に該当しない（persistent state・DB接触なし）ため
  high-risk独立evidence gateの対象外と判断するが、PR label判断は実装PRで最終確認する。

## Incremental implementation order

1. **#369実装merge後のre-entry確定**: run面実装（transitional T-07前置き面・T-09統合
   progress面の実際のstring resource名・compose構成、`ManualOrganizationFaceTest`の
   実体、`OrganizationOnboardingProposal.kt` / `PreferenceRoutes.kt` への#369差分の有無）
   を読み取り、guard testの観測対象と本planのCurrent evidenceを行番号込みで確定する。
   spec.mdのOpen questions 1（transitional T-07の表示）も実装で確認する。
2. **直行row削除＋hint copy実装**（第3引数差し替え + javadoc更新 +
   instrumentation assert 2件更新）。
3. **確認経路のguard test**（#369実装merge後。T-07面非介在の直接観測 +
   統合run面到達の観測）。
4. **spec 53/232の文書改訂 + obsolete理由のPR記録**（2〜3と同一PR）。
5. **検証一式の実行とPR記録**（unit gate、instrumentation lane、string確認、
   emulator evidence、`git diff --check`、`spotlessCheck`）。

## Dependencies / blockers

- **#369実装（同Issue Phase 2、merge待ち）**: guard testの前提面。merge後、step 1の
  re-entry確定を経て実装着手（バックグラウンド監視中）。
- **#365 / #366 / #367 / #368 / #369 spec/plan / 処分文書PR #378**: merge済み。前提成立済み。
- それ以外のblockerなし。

## Risk

- **並行変更との競合**: `OrganizationOnboardingProposal.kt` は#369実装の影響fileになり得る。
  影響はrebase時の行番号ずれと、#369実装がroute周りに手を入れた場合のguard test対象の
  再確認に限定されると想定するが、#369実装merge後に再検証する（step 1）。
- **T-07面の観測可能性**: 受入済み#369 specはtransitional T-07の主CTA「そのまま整理」を
  面の識別要素として固定しており、semantics検索による不在観測は成立する見込みである。
  もし#369実装のcompose構成がsemantics上で識別できない場合は、観測window中の反復
  サンプリングによる不在確認を最小型のtest helperで実装し、production codeへの侵入を
  避ける（#369実装に既存の識別手段があればそれを使う）。
- **旧labelの参照残存**: `manual_organization_title` はrun面scaffold titleとして実在用途が
  残るため、hint構成から外してもresource削除は行わない（#377のcleanup対象）。

## Explicitly unverified areas

- #369実装（merge待ち）が導入する実際のstring resource名・compose構成
  （transitional T-07主CTA、T-09準備中見出し/phase行）、`ManualOrganizationFaceTest` の
  実体、および `OrganizationOnboardingProposal.kt` / `PreferenceRoutes.kt` への#369実装
  差分の有無。guard testの観測対象は#369実装merge後の実装読み取りで確定する
  （step 1。実装着手前のre-entry必須）。
- post-#369のonboarding由来routeでterminal後にT-07（Idle面）へ戻った際の表示
  （transitional T-07。spec.md Open questions 1。run面forkをしないことを契約とし、
  実装確認はstep 1で行う）。
- 上記はいずれも#369実装merge後の実装着手前に再確認が必須であり、本planの確定事項ではない。
