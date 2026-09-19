---
issue: "#370"
status: draft
requirements: [FR-006, FR-007, NFR-009, NFR-011]
risk: []
updated: 2026-09-19
---

# onboarding提案と再表示hintの接続先をhub時代の情報設計へ更新する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-16, T-19, §5.2 secondary entry 1, §6.1 onboarding journey, §11 の spec 53 行）。
> 処分の正本: [docs/product/organizer-disposition-migration.md][4]（PR #378、proposed）
> §3.4（spec 53 = Continue、表記更新のみ）・§4（spec 232 = Amend、AC-1案内先）・§5 順序4。
> 本specは[Issue #370][1]の成果物である。statusが `draft` の間はimplementation-readyではない。

## Problem

onboarding提案の契約本体（fresh install判定・defer/skip outcome・再表示規則）はspec 53として
implemented済みであり、D-16がその継続を決定している。一方、提案の**外側への接続**は
TO-BE情報設計（#361 accepted）と噛み合っていない:

- 現行の「確認」は `start(Trigger.ONBOARDING_PROPOSAL)` によりrun admissionへ直行する
  （[OrganizationOnboardingProposal.kt:215-217](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)
  と [412-435](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)、
  baseline `3076bdae7e` 2026-09-19確認）。動線自体は既に直行だが、spec 53 §3.2の表記は
  「既存のIssue #52 review surfaceを表示する」止まりであり、#369がrun面のIdle/Cancelled分岐を
  T-07前置き面へ置換した後にこの経路が**前置き面（方法選択）を経由しない**ことを契約として
  固定していない。T-07は「そのまま整理 / AIに相談」の方法選択面であり、onboarding経由で
  方法選択を挟むことはD-16（方法は「そのまま整理」固定）に反する。
- 「後で」の6秒hint（spec 232）のpath文言は「Settings → Home screen → Organize home layout」を
  実label合成で案内する
  （[OrganizationOnboardingProposal.kt:580-585](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)、
  3番目の引数が `manual_organization_title`）。#367がGeneral groupからmanual organization
  直行rowを廃止してhub入口rowに集約した後、この案内は実在しない設定rowを指す。
  spec 232の真実性規約（hintは実UI labelから合成されdriftしない）と衝突する。
- spec 232 AC-1（案内先=Home settingsのHome screen内）/ AC-3（`Organize home layout`行が
  General sectionに置かれること）は旧案内先を固定しており、#366/#367適用後のUIと矛盾する。

## Outcome

onboarding提案からOrganizer run面・再開導線への接続が、hubを中心とするTO-BE情報設計と一致する。
「確認」はrun admissionへ直行し（T-07前置きを省略、方法は「そのまま整理」固定）、admission後は
#369の統合run面を辿る。「後で」の6秒hintは設定 → Home screen → hub入口rowを案内し、
copyは実UI labelからの合成（locale追従）を維持する。spec 53は接続先の表記更新のみを受け、
spec 232はAC-1案内先とAC-3入口row位置をhub時代へ改訂する。outcome・eligibility・再表示の
契約（spec 53 AC-001/AC-002）は1行も変わらない。

## Scope

- **「確認」の接続契約の固定**: `確認` tap → `start(Trigger.ONBOARDING_PROPOSAL)` による
  admission → **方法選択面（T-07）を表示せず**admitted runの進行表示（#369統合run面の
  T-09準備中以降）へ直接到達すること。Busy時の挙動（admission不成立・遷移なし・提案は
  再試行可能・既存runのrelabel禁止）はspec 53 §3.2契約のまま変更しない。
- **re-entry hintの案内先・copy更新**: hintのpath構成の第3要素を `manual_organization_title`
  からhub入口rowの実labelへ置換する。format resource（EN/ja）による合成、実UI label参照の
  真実性規則（spec 232）、6秒寿命・非block・dismiss契約は維持する。
- **spec 53の表記更新**（実装PRで実施）: §3.2（entry契約に「T-07前置きを省略し
  方法は『そのまま整理』で固定される」旨を追記）、§5.2/§5.3（review surface /
  Review destination ownerの接続先表記をhub時代のrun面参照へ更新）。
  AC-003「#52 workflow再利用」の文言は不変。outcome表・eligibility・§3.3/§3.4・
  diagnostics契約には触れない。
- **spec 232の改訂**（実装PRで実施）: AC-1の案内先をhub入口rowへ、AC-3の入口row位置を
  #367適用後の設定構成（General groupのorganizer系rowはhub入口row 1件）と整合する形へ更新。
  該当するBehavior scenario・Test oracle行を連動して更新する。
- **旧案内先を固定したtestの更新**: hintのlabel構成assert、General sectionの入口row assert、
  「確認」経路の遷移assertを更新し、 obsolete理由をPRに記録する（disposition §3.4の
  test migration）。

## Non-goals

- onboarding契約本体の変更: provenance判定（`OrganizationOnboardingInstallProvenance`）、
  outcome semantics（spec 53 §3.1の表）、eligibility、再表示規則、backup/restore協調
  （spec 53 AC-001/AC-002は不変）。
- hintの新設・常設化・表示経路の変更（6秒・非block・`Later`直後の1回表示の現行契約維持）。
  hintにaction buttonや常設導線を付けない。
- T-07前置き面・run面の8状態統合の実装（#369が所有）。#370は#369の面構成を前提に
  onboarding経路の接続を固定するのみであり、run面をforkしない。
- hub shell・status card・材料導線の実装（#366）、設定側rowの廃止・材料移動（#367）、
  strategy移設（#368）、AI相談（#372）。
- post-cancel等でrun面がIdle/Cancelledに戻った後の面構成の再設計。onboarding由来のrouteで
  あってもterminal後のIdle面は#369のrun面契約（T-07）に従い、retry/recaptureのtriggerは
  spec 53 §3.3（`ONBOARDING_PROPOSAL`保持）のまま変更しない。
- run state machine、`OrganizationEntry`/`HomeScreenManualOrganization` routeのtrigger導出、
  diagnostics event、persistent store、`organization-run-ux.md` / `requirements.md` /
  `CONTEXT.md` の改訂（正本改訂は#365が所有）。

## Domain language

- **Organizer hub / hub入口row / 材料**: organizer-to-be-ux.md D-01・§10を正本とする既存語彙。
  `CONTEXT.md` への追加は#365が所有し、本specでは複製しない。
- **T-07（run前置き面）**: 方法選択とscope要約の面（TO-BE §5.1）。admissionを含まない。
  #369がMANUAL entryの開始点として導入する。onboarding経路はこの面を経ない。
- **run admission（RUN lease取得）**: hubとrunの境界（TO-BE D-03）。onboarding「確認」は
  このadmissionを直接発生させる。
- 新規の用語追加はしない。

## Behavior scenarios

### Scenario: 確認は前置き面を経ずにadmitted runの進行表示へ直接到達する

Given fresh-install onboarding提案が表示されており、coordinatorが_idle_である
When ユーザーが「確認」をタップする
Then `start(Trigger.ONBOARDING_PROPOSAL)` によりrun admissionが発生し、`REVIEWED` outcomeが
admission成功後に記録される
And 遷移先では方法選択面（T-07前置き）が表示されず、admitted runの進行表示
（#369統合run面のT-09準備中相当以降）が直接表示される
And 以降のcapture/plan/preview/確認/適用の表示は、hubやmanual entryからadmitしたrunと
同一の統合run面であり、別実装が現れない

### Scenario: Busy時の確認はspec 53契約のまま保たれる

Given onboarding提案が表示されており、coordinatorが別runの進行中である
When ユーザーが「確認」をタップする
Then admissionは成立せず（`StartOutcome.Busy`）、遷移も`REVIEWED`記録も発生しない
And 提案は閉じず再試行可能なまま残り、既存runの表示・triggerは書き換えられない

### Scenario: 後でのhintはhub入口rowを案内する

Given onboarding提案が表示されている
When ユーザーが「後で」を選択する
Then 既存のdefer semantics（process内suppress、`DEFERRED`永続化、run-journal非発行）が
そのまま適用される
And 6秒の非blockなhintが表示され、そのpath文言は
「設定 → Home screen → hub入口row」を**その時点の設定UIで実在するlabel**から構成して案内する
And 案内先の実体（設定 → Home screenのGeneral groupにあるhub入口row）は#366により実在する
And EN（`values/`）とja（`values-ja/`）のformat resource引数が一致し、実UIのlabelと言語が一致する

### Scenario: hintの表示・dismiss契約は変化しない

Given re-entry hintが表示されている
When ユーザーがHomeを操作する（hint外touch、Back）または6秒が経過する
Then hintが閉じ、focusが元のtargetへ戻り、outcome persistenceは変更されない
And hint表示経路で例外が発生してもdefer persistenceとlauncherの動作は壊れない
（spec 232 AC-2/AC-4/AC-5の維持。copy変更のみである）

### Scenario: outcome semanticsと再表示規則は1行も変わらない

Given onboarding提案が表示されている
When `スキップ` / `確認` / 提案外のdismiss を選択する
Then spec 53 §3.1のoutcome table（`SKIPPED` / `REVIEWED` / dismiss=defer）、
AC-001のprovenance fail-closed、AC-002の再表示規則が変更なく適用される
And proposal・hintの表示/選択はorganizer run-journal eventを発行しない

### Scenario: onboarding由来routeからのretryはtriggerを保持する

Given 「確認」からadmitしたrunがterminal状態（cancel/失敗/完了）に到達し、
ユーザーが同一run面にとどまっている
When ユーザーが再開・再試行の操作を行う
Then 面構成は#369のrun面契約に従い、新しいfresh runのtriggerは
`ONBOARDING_PROPOSAL`のままである（spec 53 §3.3の維持）
And 設定側（#367以降はhub入口row経由）からの新規開始は `MANUAL_FULL` のままである

## Data and state

- 読むdata: 変更なし。proposal store・provenance・process stateの既存readのみ。
- 永続化: 変更なし。新規preference/storeなし。hintはprocess内の一時viewでありretentionを持たない。
- identity: 変更なし。run ID・trigger・`OrganizationEntry` route引数の意味は不変
  （routeは安定したcaller contextのみを保持する現行契約、[PreferenceRoutes.kt:119-133](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt)）。
- migration / backup / restore / rollback: 対象外。Launcher layout DB / `favorites` には
  接触しないためホームレイアウト安全規約の適用対象外である。PR revertで現行copy・表記へ戻る。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataの扱い追加はない。diagnosticsへの新規出力もない。

## Accessibility and localization

- hintのTalkBack契約（title + bodyを1つのannouncementとして伝える、focus復帰、
  200% font scale reflow、非色依存）はcopy変更後も維持する（spec 232 AC-4、NFR-009）。
- path文言の真実性規約（hintは実際にユーザーが見る設定labelから構成されdriftしない）を
  hub入口row labelへ適用する。label合成は既存どおりformat resource（`values/` と
  `values-ja/` で引数集合・placeholder一致）で行い、Kotlin側の文連結を新設しない
  （spec 123 AC-4/AC-5規約）。
- 「確認」経路の遷移先変更は、spec 53 §6のaccessibility契約（統合run面側の受入基準）の
  適用範囲を変えない。onboarding経路に新しい面を追加しないため、新しい読み上げ要件も生じない。

## Compatibility and migration

- **依存順**: 実装は#366（hub入口rowの存在）と#369（統合run面）のmerge後に行う
  （Issue本文 `Depends on`）。正本改訂#365（OPEN）は実装着手の前置きである
  （AGENTS.md「正本を先に」。#366〜#369と同一判定）。
- **#367との整合**: spec 232 AC-3の入口row位置は#367適用後の設定構成を基準に改訂する。
  #367が先にmergeされhint path文言を先に更新した場合（#367 draft MAT-AC-07）、#370は
  spec 232 AC-1の契約改訂と理由記録を行い、実装差分が重複しないよう確認へ縮小する。
  #370が先の場合はhint copyの実装更新も本Issueが行う。どちらの順序でも
  「hintが実在する設定path（hub入口row）を指す」状態が保たれることを契約とする。
- **downgrade / rollback**: copy・表記・test更新のみであり、PR revertで完全に戻る。
  persistent stateの残留物はない。

## Dependencies

- **#366（hub shell、OPEN・spec draft `bf00f96175`）**: hub入口row（draft plan上は
  `organizer_hub_title` / destination `HomeScreenOrganizer`。最終label/resource名は
  #366実装が所有）が実在することがhint案内先の前提。
- **#369（run面統合、OPEN・spec draft `3c39ceb2f8`）**: 「以降は#369の統合run面を辿る」ACと
  T-07前置き省略の意味論の前提。#369はonboarding接続変更をnon-goalsとして#370へ委譲済み。
- **#367（材料移動、OPEN・spec draft `a50f074ac2`）**: spec 232 AC-3改訂の整合先。
  hint path文言更新の実装重複の有無はmerge順で決まる（前節）。
- **#365（正本改訂、OPEN）**: 実装着手は#365 merge後（spec執筆はblockされない）。
- **処分文書（PR #378、OPEN・proposed）**: §3.4/§4/§5が本件の処分と順序の正本となる予定。
  merge時に差異があれば本specを追従させる。

## Acceptance criteria

- [ ] **OCB-AC-01**: 「確認」がT-07前置き面を表示せず `start(Trigger.ONBOARDING_PROPOSAL)`
      によるrun admissionへ直行し、admission後は#369の統合run面を辿る。Busy時は
      spec 53 §3.2契約どおり（admission不成立・遷移なし・再試行可能・relabel禁止）である。
      （Issue受入1）
- [ ] **OCB-AC-02**: 「後で」の6秒hintが設定 → Home screen → hub入口rowを案内し、
      copyが実UI labelからのformat resource合成（EN/ja、locale追従）と一致する。
      hintの6秒・非block・dismiss・focus復帰・表示失敗時の安全性の各契約は変化しない。
      （Issue受入2）
- [ ] **OCB-AC-03**: defer/skip/outcome永続化・再表示規則（spec 53 AC-001/AC-002）、
      proposal/hint actionのrun-journal非発行、onboarding由来retryの`ONBOARDING_PROPOSAL`保持
      （spec 53 §3.3）に変更がない。persistent state・diagnostics契約へのdiffが存在しない。
      （Issue受入3）
- [ ] **OCB-AC-04**: spec 53（§3.2/§5.2/§5.3の接続先表記。AC-003不変）と
      spec 232（AC-1案内先、AC-3入口row位置）が本specの定義どおり更新され、
      旧案内先を固定したtest（hint label構成assert、General section入口row assert、
      「確認」経路の遷移assert）の更新と obsolete理由がPRに記録されている。
      既存organizer unit gateとinstrumentation laneがgreenである。（Issue受入4）

## Test oracle

| AC | Evidence |
|---|---|
| OCB-AC-01 | instrumentation（`OnboardingOrganizationProposalInstrumentationTest`拡張: 確認tap → admission → 遷移先に方法選択面が現れないこと・admitted runの進行表示が現れることの観測。#369適用後のrun面構成に対するassert）。既存 `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` / `busyReviewKeepsProposalOutcomeUntouchedAndRetryableByRealTouch` の緑維持。unit（`OrganizationOnboardingProposalTest`: admission→REVIEWED記録順序の回帰） |
| OCB-AC-02 | instrumentation（`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`のlabel構成assert更新: hub入口row labelを含み`manual_organization_title`を含まないこと。EN/ja双方のformat resource合成assert）。string diff（`values/` / `values-ja/` のname集合・placeholder一致）+ emulator screenshot（light/dark × ja/default） |
| OCB-AC-03 | 既存unit/instrumentationの無編集green（outcome・provenance・journal非発行の回帰群）。実装PR diff上、persistent store / diagnostics契約コードの無編集確認 |
| OCB-AC-04 | specs 53/232のdiff review（本specのScopeに対応する行のみの変更、change history追記）+ test更新の obsolete理由のPR記録 + `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象instrumentation lane、CI `final-status` green |

## Open questions

実装開始前に解消が必須な問いはない。非blocking事項:

1. **post-#369のonboarding由来routeで、terminal後のIdle面（T-07）に「AIに相談」分岐を
   表示するか**: D-16が固定するのはonboarding「確認」の接続（admission直行）であり、
   terminal後の面構成は#369のrun面契約に従う。方法選択の有無をonboarding由来routeで
   分岐させない（run面をforkしない）ことを本specは契約とするが、#369実装後の表示の
   最終形は#369の実装PRで確認する。
2. hub入口rowの最終label/resource名（draft plan上 `organizer_hub_title`）は#366実装が
   所有する。hint合成は実renderされるlabel resourceを参照し、値を複製しない。
3. spec 232改訂後のAC-3文言の最終形（#367のContract notes 1の解釈がowner reviewで
   否認された場合の代替構成）は、#367のreview結果に追従させる。

## Change history

- 2026-09-19: Draft created for #370（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`、PR #364）、処分文書draft（PR #378
  §3.4/§4/§5）、先行spec drafts（#366 `bf00f96175` / #367 `a50f074ac2` /
  #368 `206a4c1198` / #369 `3c39ceb2f8`）、現行実装調査
  （`OrganizationOnboardingProposal.kt`、`PreferenceRoutes.kt`、`PreferenceNavigation.kt`、
  `HomeScreenPreferences.kt`、`strings.xml`/`values-ja/strings.xml`、
  organizer unit/instrumentation test群）を入力に作成。

[1]: https://github.com/nunu1733/NunuLauncher/issues/370
[4]: https://github.com/nunu1733/NunuLauncher/pull/378
