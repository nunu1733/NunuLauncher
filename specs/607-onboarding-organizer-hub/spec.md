---
issue: "#607"
status: draft
tier: M
requirements: [FR-006, NFR-011, NFR-014]
updated: 2026-10-17
---

# 初回メニューの整理起動を既存Organizer hub経由にする

> Risk tier: **M** — 入口・navigation・onboarding outcome確定タイミングの変更であり、
> Launcher DB書込み・schema migration・recovery store・上流model/loader bridgeを含まない
> （[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
> 軽量spec形式（[spec-lite](../_template/spec-lite.md)）であり、`plan.md` は要求しない。
> 前提: [spec 53](../53-onboarding-organization-proposal/spec.md)（onboarding提案本体・outcome・
> trigger契約）、[spec 370](../370-onboarding-hub-connection/spec.md)（現行の「確認→run
> admission直行」接続。本specが意図的にsupersedeする）、hub・材料集約（#366/#367）、
> [spec 452](../452-organizer-home-entry/spec.md)（workspace直接run導線。本Issueで不変）。
> 本specは[Issue #607][1]の成果物である。statusは **draft** であり、受入（accepted）ではない。

## Problem

初回メニュー（onboarding提案）の「確認」はspec 370の契約により `start(Trigger.ONBOARDING_PROPOSAL)`
によるrun admissionへ直行し、hubを経由しない。一方、#367以降のOrganizer hubは整理に影響する
恒常入力（分類・ユーザー定義カテゴリ・配置ロック・使用状況等の「材料」）を集約しており、
設定経由の利用者はrun開始前にこれらを確認・変更できる。初回利用者だけがこの準備機会を
持たず、hubが整理前の入力・準備を集約する現在のIAとspec 370時点の短絡導線が不整合になっている。

## Benchmark

純粋な初回導線の一致化であり、B1〜B7（[editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §2）
のいずれも直接の対象ではない（NFR-014の理由記載による免除）。Issueの意図は「初回経由でも
整理開始前に準備する機会を設定経由と一致させる」ことであり、操作数の目標値を新たに課さない。
[spec 452](../452-organizer-home-entry/spec.md) のworkspace直接導線を含む恒常導線の
既存目標は本Issueで変えない。

## Prior art

- Android type-safe navigation（対象: Navigation design guide の type-safe navigation /
  URL: https://developer.android.com/guide/navigation/design/type-safety / 確認日: 2026-10-17）—
  公式のnavigationはdata class / data objectのrouteへtypedな安定引数を渡す。採用:
  caller context（onboarding起点のentry context）の引渡しは既存のtyped route mechanism
  （`PreferenceRoutes.kt` の `OrganizationEntry` 等）を使い、新しいnavigation frameworkは
  導入しない。
- Android back stack（対象: Navigation guide の back stack / URL:
  https://developer.android.com/guide/navigation/backstack / 確認日: 2026-10-17）—
  back stackの最後のdestinationでは、空白のUIを残すのではなくActivityをfinishするか
  別の場所へnavigateするべきである。採用: 既存のActivity Back dispatcherをそのまま使い、
  onboarding起点でhubがrootのときのBack/Upがlauncherへ戻ること、子画面（材料・run面）が
  hubへpopすることを検証する。
- 完了outcome（`REVIEWED`）の記録タイミングは本repositoryのproduct契約（spec 53由来）であり、
  上記のAndroid公式資料に由来するものではない。

## Outcome

fresh install / onboarding未完了状態で初回メニューの「確認」を選ぶと、提案は既存のdefer
semanticsで閉じ（`DEFERRED` 永続化・process内抑制）、run面の代わりに既存のOrganizer hubが
表示される。利用者はhub上の材料を必要に応じて確認・変更したうえで、hubの「整理を開始」→
既存run面前置き（T-07）→ run面の開始で、設定経由と同じ既存coordinator admissionへ進む。
onboarding起点のこのadmissionが実際に成功（`Started`）したときにだけ `REVIEWED` が記録され、
そのrunのtriggerは `ONBOARDING_PROPOSAL` である。hubやrun面前置きを開いただけ・戻った・
閉じた状態ではrunは開始されず、`REVIEWED` は記録されない。

## Scope

- onboarding提案の「確認」の遷移先を、run admission直行から既存hub（`HomeScreenOrganizer`）へ
  変更する。提案からの離脱時には既存のdefer semanticsをそのまま適用する
  （`DEFERRED` 永続化・process内提案抑制。次のqualifying cold startで再表示され得る）。
- hubの「整理を開始」は既存どおり **navigationのみ** を行う。coordinatorがidle/terminalの
  ときは既存run面前置き（T-07）へ進み、active runがあるときは既存run面がそのrunの
  現在のfaceを表示する。admissionはrun面の開始行が引き続き排他的に所有する（既存
  admission gateをhubが再実装・迂回しない）。coordinatorの状態にかかわらず
  hub→run面のnavigationは阻害されない（設定経由と同一）。
- hub開始CTAの重複navigation防止は最小seamとして本specの実装範囲に含める
  （single-top相当または当該CTA限定guard）。現行の `NavigationActionPreference` は
  `navController.navigate(route = destination)` の単純呼び出しであり
  `launchSingleTop` / click guardを持たないため、この防止は本specが新規に要求する
  最小差分である。generic navigation全体の再設計は行わない。
- run面の開始行からのadmission契約（RUN lease、readiness、coordinator）は既存のまま再利用する。
  onboarding起点のentry contextはtrigger/contextの引渡しのみに使い、
  coordinator/run/applicationの内部は均さない。
- `REVIEWED` の記録タイミングを「onboarding起点のadmissionが実際に `Started` した後」へ移す。
  hub表示・T-07前置きの表示・hub Back/Up/Close・Busy/refused admission・既存live runの
  観察では記録しない。
- run UIの開始呼び出しwiring（`ManualOrganizationPreferences.kt`）とonboarding outcomeの
  controller/store（`organizer/ui` 配下）の既存範囲内refactorを含む。新規DB pathは作らない。
- spec 53/370および正本TO-BE文書のうち「確認→admission直行」「T-07前置き省略」を固定する
  箇所のnormative更新を実装PRの範囲に含める（下記「必須となる正本更新」）。

## Non-goals

- hub内の各材料画面・store契約の再設計。既存の材料編集semantics（明示的に保存された
  材料編集のrollback・取消の新設を含まない）はそのまま維持する。
- run面のface構成、admission gate、RUN lease、readiness、整理アルゴリズムの変更。
- coordinator/run/application moduleの内部（state machine、face mapping、trigger依存の
  内部分岐）の変更・均し。"same downstream"は共有run面・gateの再利用を意味し、
  内部実装の一致化を意味しない。
- onboardingのprovenance判定、defer/skip outcome・再表示規則そのものの変更
  （spec 53 AC-001/AC-002は不変。本Issueは「確認」離脱時のdefer適用と
  `REVIEWED` 記録タイミングのみを扱う）。
- workspace長押し等の明示的クイック開始導線の変更（[spec 452](../452-organizer-home-entry/spec.md)
  の「ホームを整理」→ run面直行は不変）。
- onboarding専用hub・事前設定画面の新設。既存hubを再利用する。
- navigation全体の再設計。戻り先制御に必要な最小差分のみを含む。
- 新しいpersistent outcome形式・新規preference/store・diagnostics event・新規UI copyの追加。

## 必須となる正本更新（source coding前に必要）

現行のaccepted文書・正典TO-BEのliveなnormative textに残る
「確認→run admission直行」「onboardingはT-07前置きを省略する」「proposal（確認）tap上の
admission / Busy契約」「確認時点での `REVIEWED` 記録」の参照は、**すべて** 本specの契約
（確認=defer適用+hub表示、admissionはrun面開始行が所有、`REVIEWED` は `Started` 後）へ
更新する。これは節番号の限定列挙ではなく契約であり、実装PRは上記旧契約をliveなnormative
textに残さないことを確認する（歴史的記録は除く）。本specの受入後・source coding前に
**normative更新が必須** である（同じbranch内でspec受入後〜実装PRの間に実施してよい）。
更新は実装PRが所有する。歴史的記録（各specのChange history、処分文書
[organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md) §3.4の
当時の判断）は歴史として残し、書き換えない。各文書の現行節へは本specへのamendment pointerを残す。
現時点で旧契約をnormativeに固定している箇所には少なくとも以下がある:

- [spec 53](../53-onboarding-organization-proposal/spec.md):
  - §3.2（「確認」= admission直行のentry契約 → 「確認」= defer適用 + hub表示。
    admissionはrun面開始行へ移動）、
  - §3.1 outcome表の `REVIEWED` 行（記録タイミングの注記）、
  - **AC-003**（「`review organization` がnavigationの前にadmitする」のadmission所有点を
    run面開始行へ書き換える。#52 workflow再利用の意図は維持する）、
  - **§4.1**（journal起点の「When `review organization` successfully admits...」を
    run面開始行のadmission（trigger `ONBOARDING_PROPOSAL`）へ書き換える）、
  - **§8 Required test/QA scenarios**（「Review while idle/terminal: admits ... then
    opens...」とactive-run時のproposal上Busy scenarioを、確認=defer+hub表示・
    Busy観測点=run面開始行へ書き換える）、
  - §5.2/§5.3（Review destination ownerの接続先表記）。
- [spec 370](../370-onboarding-hub-connection/spec.md):
  - **Outcome** 段落と **Scope の先頭項目**（「確認」の接続契約の固定）—
    「確認→`start(ONBOARDING_PROPOSAL)`→T-07省略」をhub経由契約へ書き換える。
    **T-07前置き省略の契約は本specにより意図的にsupersedeされる**（onboardingはhub →
    標準T-07前置きを経由する）。歴史的な#370記録は歴史として残す。
  - **OCB-AC-01** および「確認は前置き面を経ずにadmitted runの進行表示へ直接到達する」
    scenario、
  - **「Busy時の確認」scenario**（proposal tap上のBusy契約 → run面開始行のBusy観測点へ）、
  - **「outcome semanticsと再表示規則は1行も変わらない」scenario**（`確認` → `REVIEWED` の
    Given/Then前提 → 確認=deferへ）、
  - **「onboarding由来routeからのretry」scenarioのGiven**（「確認」からadmitした前提 →
    run面開始でadmitした前提へ）、
  - **OCB-AC-01のTest oracle**（proposal tap自体が実runnerをstartし `PREAMBLE=0` を証明する
    旧guard test契約 → hub遷移・run面開始のoracleへ）。
- 正本TO-BE [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
  D-16（「確認」はrun admissionへ直行 → hub経由）、T-19（確認の遷移先）、
  §6.1 初回ユーザー journey 2（「確認」→ run admission直行 → hub経由 + 標準前置き）。

上記に挙げない箇所でも、liveなnormative textに旧契約が残る場合は本契約の対象である
（歴史的記録・Change historyは対象外）。旧契約を名前・fixture責務に含むtest
（例: `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface`、proposal上Busy系）は、
assertのみでなく **test名・fixture責務・obsolete理由** を同じ変更で更新する
（旧名のままgreenにしない）。

## Behavior scenarios

### Scenario: 確認はdeferを適用してhubを表示し、runを開始しない

Given fresh install / onboarding未完了状態で初回メニュー（onboarding提案）が表示されている
When ユーザーが「確認」をタップする
Then 既存のdefer semanticsが適用され（`DEFERRED` 永続化・process内提案抑制）提案が閉じ、
既存のOrganizer hub（設定経由と同一の画面・行構成）が表示される
And run admissionは発生せず、run面（T-07前置きを含む）へ直行せず、`REVIEWED` / `SKIPPED`
は記録されず、organizer run-journal eventは発行されない

### Scenario: hubの「整理を開始」は既存run面へのnavigationのみである

Given 「確認」からOrganizer hubが表示されており、coordinatorが_idle_またはterminal
（Idle/Cancelled）である
When ユーザーがhubの「整理を開始」を選択する
Then 既存のrun面前置き（T-07。設定経由の `ホーム設定 → Organizer hub → 整理を開始` と
同一の面・構成）へ進む。この時点でrun admissionは発生しない

### Scenario: hubの「整理を開始」はactive runがあるときもnavigationのみである

Given 「確認」からOrganizer hubが表示されており、coordinatorが別runの進行中である
When ユーザーがhubの「整理を開始」を選択する
Then 既存run面がそのactive runの現在のfaceを表示する（設定経由のhub開始と同一の挙動）。
新しいrunのadmissionは発生せず、既存runの表示・triggerは書き換えられない
And いずれの状態でも、このnavigation自体が阻害されることはない

### Scenario: run面の開始で既存coordinator admissionが発生し、成功後にREVIEWEDを記録する

Given 「確認」→ hub → run面前置き（T-07）まで進んでいる
When ユーザーがrun面の開始行を選択する
Then 既存coordinatorのadmissionが発生し、成功（`Started`）するとrun面がadmitted runを
表示する。onboarding起点のこのrunのtriggerは `ONBOARDING_PROPOSAL` であり、
`REVIEWED` はこの `Started` の後に記録される
And 以降のcapture/plan/preview/確認/適用はspec 53 §3.2どおり共有run workflowであり、
別実装が現れない

### Scenario: Busy時のadmission不成立は開始アクションが提示された場面でのみ観測される

Given onboarding起点でrun面前置き（T-07）が表示されており、開始行が提示されている
（coordinatorは_idle_である）
When ユーザーが開始行を選択する前に別経由でrunがadmissionされ（競合）、
その後にユーザーが開始行を選択する
Then admissionは成立せず（`StartOutcome.Busy` 相当）、`REVIEWED` 記録も発生せず、
onboarding outcomeは `DEFERRED` のまま保持される
And 既存のUI・gate挙動（開始行の再試行可能性、既存runの表示・triggerの非書き換え）は
spec 53 §3.2どおり維持される
And 既存live runの表示を観察しているだけの場面（開始アクションが提示されていない）では
admission試行は発生せず、観察自体は `REVIEWED` にならない

### Scenario: hubで材料を確認・変更してから開始できる

Given 「確認」からOrganizer hubが表示されている
When ユーザーが材料行（分類・カテゴリ・配置ロック・使用状況等）を開いて確認・変更し、
hubへ戻ってから「整理を開始」以降へ進む
Then 変更した材料の状態とentry contextは材料往復・run面遷移を通じて保持され、
run開始時に反映される（既存の材料編集semanticsどおり）
And 明示的に保存された材料編集は、その後のhub退出・onboarding outcomeの状態にかかわらず
既存semanticsのまま保持され、本導線変更による自動rollbackは発生しない

### Scenario: 戻る/閉じる操作はoutcomeを追加変更しない

Given 「確認」からonboarding起点の導線（hub、またはhub→run面前置き）を進んでいる
When ユーザーがrun面前置きでBackする（hubへ戻る）、またはhubでBack / Up / Closeする
（onboarding起点の設定Activityを閉じてlauncherへ戻る）
Then Organizer runは開始されず、outcomeは離脱時に記録済みの `DEFERRED` のまま
追加変更されず、`REVIEWED` / `SKIPPED` は記録されない
And 以後の再表示は既存のdefer/cold-start規則（spec 53 §3.1・AC-002）に従い、
設定起点のhub・run面の既存Up/Back挙動（設定 → Home screenへの戻り）は変化しない

### Scenario: 繰り返しタップは二重の遷移を生まず、開始は既存gateに従う

Given 初回メニューが表示されている（またはonboarding起点のhubが表示されている）
When ユーザーが「確認」を短時間に繰り返しタップする（またはhubの「整理を開始」を
繰り返しタップする）
Then hub設定Activityは1回だけ起動され（二重のactivity起動なし）、
hub→run面のnavigationは `HomeScreenManualOrganization` のback-stack entryが
1件だけである（destination積み増しなし。hub開始CTAの重複navigation防止seam:
single-top相当または当該CTA限定guardによる。現行の `NavigationActionPreference` には
この保証がない）
And hub開始CTAの二重activateではadmission試行が発生しない
（admission試行はrun面開始行の明示的操作まで0である）
And run面の開始の繰り返しは既存の単一admitted run / Busy gateに従う
（正当な再試行ごとの `start()` 呼び出しを本specは1回に制限しない）。
`REVIEWED` はonboarding起点の実際に成功したadmissionに対してのみ記録される

### Scenario: activity recreation / process death後もroute contextは書込み権限を持たない

Given 「確認」からonboarding起点の導線（hubまたはrun面前置き）を進んでいる（run未開始）
When activity recreationまたはprocess deathが発生し、navigation back stackから
画面が復元される
Then 復元された画面は既存のroute契約（routeは安定したcaller contextのみを保持し、
run state・書込み権限を持たない。`PreferenceRoutes.kt` の規約）どおり振る舞い、
entry contextは復元される
And 自動run開始・自動admission・`REVIEWED` の再記録は発生しない
And process death後のrun/preview/選択は既存どおり非永続であり、改めて開始行を選んだ
場合はfresh runとしてadmissionされる

### Scenario: defer/skip/cold-start規則と設定・workspace導線は回帰しない

Given onboarding提案が表示されている
When `スキップ` / `後で` / 提案外のdismissを選択する
Then spec 53 §3.1のoutcome table（`SKIPPED` / `DEFERRED` / dismiss=defer）、
provenance fail-closed、再表示規則が変更なく適用される
And 既存の `DEFERRED` 状態は本導線変更で消費されず、設定経由のmanual entryは
`MANUAL_FULL` のまま変化せず、[spec 452](../452-organizer-home-entry/spec.md) の
workspace直接導線も変化しない
And proposal・hint・hub表示はorganizer run-journal eventを発行しない

## Acceptance criteria

Issue #607の受入条件7件に対応する（AC-1〜AC-7 = Issue本文のチェックリスト順）。

- [ ] **AC-1**: fresh install / onboarding未完了状態で初回メニューからOrganizerを起動すると、
      run面より先にOrganizer hubが表示される。（Issue受入1）
- [ ] **AC-2**: 初回経由でも、hubに集約された事前設定項目（分類・カテゴリ・配置ロック・
      使用状況等）をrun開始前に確認・変更できる。（Issue受入2）
- [ ] **AC-3**: 初回メニューでOrganizerを選択しただけではrunは開始されず、
      run admission / run面へ直行しない。hub表示・T-07前置きの表示・hub Back/Up/Close・
      Busy/refused admission・既存live runの観察では `REVIEWED` を記録しない。
      提案からの離脱時には既存defer semantics（`DEFERRED` 永続化・process内抑制）が適用される。
      （Issue受入3 + 記録タイミング契約）
- [ ] **AC-4**: hubの「整理を開始」を選択すると、設定経由と同じdownstream flow
      （coordinatorがidleの場合: 既存T-07前置き → run面開始行 → 既存coordinator admission。
      active runがある場合: 既存run面がそのrunを表示。共有run面・gate・RUN lease・
      readinessの再利用。coordinator内部は均さない）へ進む。
      onboarding起点のadmission成功（`Started`）後のみ `REVIEWED` を記録し、そのrunの
      triggerは `ONBOARDING_PROPOSAL` である。設定起点のhub開始は `MANUAL_FULL` のまま。
      hub開始CTAの短時間の二重activateでも `HomeScreenManualOrganization` の
      back-stack entryは1件であり、admission試行は開始行の明示的操作まで0である
      （重複navigation防止seam: single-top相当または当該CTA限定guard）。
      提案側の現行one-shot guard（`reviewInFlight` + button disable）に相当する
      二重起動防止は、hub遷移への変更でも失わない。
      （Issue受入4 + trigger/記録タイミング契約 + 重複navigation防止契約）
- [ ] **AC-5**: 初回経由でhubから戻る/閉じる操作をしてもOrganizerは実行されず、
      onboardingが誤って完了扱いにならない。outcomeは `DEFERRED` のまま追加変更されず、
      defer/skip/cold-start規則は不変である。（Issue受入5）
- [ ] **AC-6**: 設定 → Organizer hubの既存導線・hub内の材料画面・run/admission契約に
      回帰がない。既存の材料編集semantics（明示的に保存された編集の保持）は変化しない。
      （Issue受入6）
- [ ] **AC-7**: spec 370由来のnavigation test / specで「確認→run admission直行」を固定している
      箇所を更新し、初回→hub→整理開始→run面、およびhubから戻るケースを検証する。
      「必須となる正本更新」節の対象（spec 53/370、TO-BE D-16/T-19/§6.1）の更新と
      obsolete理由がPRに記録されている。Change history等の歴史記録を除き、liveな
      normative textに旧direct-admission契約（確認→admission直行 / T-07前置き省略 /
      proposal上Busy / 確認時 `REVIEWED`）が残っていないことを確認する。
      旧契約を名前・fixture責務に含むtestは、test名・fixture責務・obsolete理由を
      同じ変更で更新する（assertのみの更新でない）。（Issue受入7）

## Test oracle

| AC | Evidence |
|---|---|
| AC-1/AC-3 | instrumentation（「確認」tap後の遷移先がhubであること、run面・admissionが発生しないこと、離脱時の `DEFERRED` 永続化。spec 370 OCB-AC-01のguard test `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` は遷移先assertのみでなく **test名・fixture責務・obsolete理由** を含めてhub版へ更新する。proposal上Busy系の既存testも同様に名前・fixture責務・obsolete理由を更新する）+ unit（「確認」単独では `REVIEWED` を記録しないこと。既存 `admittedReviewRecordsReviewedOnlyAfterFreshRunAdmission` 相当を「run面開始の `Started` 後」へ移設） |
| AC-2/AC-6 | instrumentation（hub材料行の既存回帰群の無編集green + 初回経由hubで材料行が到達可能であること）+ 実機スクリーンショット（初回経由hubの表示。emulatorは補助のみ） |
| AC-4 | instrumentation（hub開始→T-07前置き表示→run面開始→admission→run面表示。onboarding起点のtriggerが `ONBOARDING_PROPOSAL` であることのjournal/状態assert、設定起点が `MANUAL_FULL` であることの既存assert維持。**repeated-tap back-stack oracle**: real-touch instrumentationでhub開始CTAを短時間に二重activateし、`HomeScreenManualOrganization` のback-stack entryが1件であること・admission試行が開始行の明示的操作まで0であることを決定的に確認する（1 Activity / 1 nav destination。提案側の `reviewInFlight` + button disableに相当するone-shot guard維持のassertを含む））+ unit（Busy時に `REVIEWED` を記録しない回帰の維持。既存 `busyReviewDoesNotRecordReviewedOrNavigateAsAnAdmittedRun` 相当をrun面開始試行のoracleへ移設） |
| AC-5 | instrumentation（run面前置きBack→hub、hub Back/Up/Close→launcher、outcome追加変更なし・run非開始・次cold start再表示の既存規則）+ unit（defer/skip/outcome回帰群の無編集green） |
| AC-7 | specs 53/370・TO-BE文書のdiff review（「必須となる正本更新」節どおりの変更のみ。歴史的記録の非書き換えとamendment pointerをdiff上で確認。**liveなnormative textへの旧direct-admission契約の残留なし** を列挙箇所（spec 53: AC-003 / §4.1 / §8の該当scenario、spec 370: Outcome / Scope先頭項目 / 該当scenario群 / OCB-AC-01とそのTest oracle）のdiff上で確認する）+ obsolete理由のPR記録 + `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象instrumentation lane、CI `final-status` green |

## Verification

- owner確認用のスクリーンショット/録画は **実機で取得** する（emulatorは補助証跡としてのみ可）。
  対象: AC-1（初回→hub表示）、AC-2（hub材料の確認・変更）、AC-4（hub開始→run面）。
  実機によるowner確認はmergeの必須条件であり、本specおよび実装PRはこれを免除・言い換え・
  達成主張しない。emulatorでの確認が完了しても実機確認の代替にはならない。
  実機確認の現在の実施状況はIssue/PR packetへ記録する（本specへは進行状況を書かない）。
- 書込み経路を追加しないことの確認: 実装PRのdiffで触れるpathはonboarding提案の遷移先・
  onboarding outcome controller/storeの既存範囲内refactor（`organizer/ui` 配下）・
  run UIの開始呼び出しwiring（`ManualOrganizationPreferences.kt`）・navigation構成
  （hub開始CTAの重複navigation防止の最小差分: `NavigationActionPreference` /
  `OrganizerHubPreferences`）・navigation test・specs/docs更新に限る。`ManualOrganizationRun` のstate machineや
  application module等のcoordinator/run/application内部、`src/com/android/launcher3/model/**`、
  DB・migrationに関わるpathを含まないことをdiff列挙で確認する。
- 上流のUIだけに触れるbridgeに該当する変更がない場合（navigation構成の変更のみで
  popup項目追加等を含まない場合）はupstream patch surface計測は不要である。
  該当する場合は `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  の計測結果をPR本文へreportする。
- 実行した検証command・結果・実機evidenceはIssue/PR packetへ記録する（本specへは複製しない）。

## Accessibility and localization

- 初回経由で追加される面は既存hub・既存run面前置きの再利用であり、新しい面・
  新しい読み上げ要件・新規copyは生じない。hubの既存TalkBack契約（focus順、label、
  200% font scale reflow、非色依存）が初回経由のentryでも同等に適用されることを
  実機確認で検証する。
- 「確認」tap後のfocus遷移（提案が閉じた後のfocus復帰 → hub画面の初期focus）が
  spec 53 §6・spec 232のfocus契約と同等であることを確認する。

## Change history

- 2026-10-17: Draft created for #607（spec/plan整備task。branch `issue-607-onboarding-organizer-hub`、
  base `68c6a406b562d22d573e2e6eb4d606cd57587efd`）。入力: Issue #607本文とintake comment、
  specs 53/370/232/452、正本TO-BE（D-16/T-19/§6.1）・処分文書§3.4、
  `OrganizationOnboardingProposal.kt` / `OrganizerHubPreferences.kt` / `PreferenceRoutes.kt`、
  `OrganizationOnboardingProposalTest`（baseline green確認済み）。
- 2026-10-17: Main policy decision revision。stagesの明示化（確認→hub / hub開始→T-07前置き /
  run面開始→既存coordinator admission。hub CTAはnavigationのみ。Busy観測点をrun面開始試行へ移動）、
  「確認」離脱時のdefer semantics適用のowner decision確定、戻り先契約の明示、
  repeated tap契約の修正（正当な再試行の `start()` を1回に制限しない）、scope修正
  （`organizer/ui` 配下のcontroller/store refactorとrun UI開始呼び出しwiringを許容）、
  正本更新節の修正（T-07前置き省略契約は意図的にsupersede）、benchmark操作数記述の撤回、
  新規copy要求の削除、解決済みopen questionの削除、絶対path・session進行proseの削除、
  requirements（FR-006/NFR-011/NFR-014）の追加。
- 2026-10-17: Commit-ready revision。Prior artを確定（Android type-safe navigation /
  back stackの2事例。確認日2026-10-17。完了outcomeの記録タイミングは本repository契約であり
  Android公式に帰属させない）、hub開始CTAのscenarioをcoordinator状態依存へ修正
  （idle/terminal→既存T-07前置き、active run→既存run面の現在のface。いずれもadmissionなし）、
  Busy scenarioを開始アクションが提示された場面の実際の試行に限定
  （active runの観察に開始CTAの前提を要求しない。Busy/refusedは `DEFERRED` と既存UI/gate
  挙動を保持）、Open questions節の削除、Verificationから進行状況表記を除去
  （実機owner確認のmerge必須条件とemulator補助の位置付けは維持）。
- 2026-10-17: Round-1 review（`6a93d15d02`への「Request changes」2点）対応revision。
  (1) 「必須となる正本更新」を節番号の限定列挙から、liveなnormative textに残る旧契約
  （direct-admission / T-07前置き省略 / proposal上Busy / 確認時 `REVIEWED`）の全参照を
  更新する契約へ書き換え、spec 53 AC-003（admission所有点をrun面開始行へ書き換え。
  #52 workflow再利用の意図は維持）/ §4.1 / §8該当scenario、spec 370 Outcome /
  Scope先頭項目 / 「Busy時の確認」/「outcome semanticsと再表示規則」/
  「onboarding由来routeからのretry」Given / OCB-AC-01とそのTest oracleを明示。
  AC-7とそのoracleへ「歴史記録を除きlive normative textに旧契約の残留なし」確認と、
  旧契約名testのtest名・fixture責務・obsolete理由の同一変更更新を追加。
  (2) repeated-tap契約に所有seam（hub開始CTAの重複navigation防止: single-top相当または
  CTA限定guard。現行 `NavigationActionPreference` には `launchSingleTop` / click guardの
  保証がない）と決定的oracle（real-touch二重activate後の `HomeScreenManualOrganization`
  1 entry / admission試行0）を追加。提案側one-shot guard（`reviewInFlight` + button
  disable）の維持をoracleへ含める。製品scopeの変更（DEFERRED / hub navigationのみ /
  `Started` 後の `REVIEWED`）はなし。

[1]: https://github.com/nunu1733/NunuLauncher/issues/607
