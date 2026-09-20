---
issue: "#370"
status: draft
requirements: [FR-006, FR-007, NFR-009, NFR-011]
risk: []
updated: 2026-09-20
---

# onboarding提案と再表示hintの接続先をhub時代の情報設計へ更新する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-16, T-19, §5.2 secondary entry 1, §6.1 onboarding journey, §11 の spec 53 行）。
> 処分の正本: [docs/product/organizer-disposition-migration.md][4]
> （accepted、PR #378 merge済み。§3.4 spec 53 = Continue（表記更新）、
> §2.3/§5 順序4 spec 232 = Amend（AC-1案内先・AC-3入口row 1件構成）、
> §7.2(b)/(c) の責務分割——D-01「設定側は入口rowだけを残す」の最終完成
> （General groupのmanual organization直行row廃止＋spec 232 AC-3改訂＋hint更新）は
> 本Issueが所有し、#367は材料row移動のみを所有する）。
> run面の契約根拠: accepted [specs/369-run-display-integration/spec.md][5]
> （PR #386 merge済み。transitional T-07前置き面、T-09統合progress面、
> face mapping純関数`manualOrganizationFace(state)`。Non-goalsで
> 「onboarding『確認』はT-07前置きを経ない。T-07はMANUAL entryの開始点としてのみ導入する」を
> 明示し、本件の接続変更を#370へ委譲）。
> 本specは[Issue #370][1]の成果物である。statusが `draft` の間はimplementation-readyではない。

## Problem

onboarding提案の契約本体（fresh install判定・defer/skip outcome・再表示規則）はspec 53として
implemented済みであり、D-16がその継続を決定している。一方、提案の**外側への接続**と
設定側の入口構成は、TO-BE情報設計（#361 accepted）と噛み合っていない:

- 現行の「確認」は `start(Trigger.ONBOARDING_PROPOSAL)` によりrun admissionへ直行する
  （[OrganizationOnboardingProposal.kt:412-435](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)
  と [215-217](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)、
  baseline `171d0bcf10` 2026-09-20確認）。動線自体は既に直行だが、spec 53 §3.2の表記は
  「既存のIssue #52 review surfaceを表示する」止まりであり、#369（spec受入済み。実装は
  同Issue Phase 2）がrun面のIdle/Cancelled分岐をtransitional T-07前置き面（方法選択）へ
  置換した後、この経路が**前置き面を経由しない**ことを契約として固定していない。
  T-07は「そのまま整理」の方法選択面（#372統合前のtransitional構成、RD-1）であり、
  onboarding経由で方法選択を挟むことはD-16（方法は「そのまま整理」固定）に反する。
  accepted #369 specのNon-goalsも「onboarding『確認』はT-07前置きを経ない。T-07は
  MANUAL entryの開始点としてのみ導入する」と明示しており、接続の契約固定は本Issueへ
  委譲されている。admission直行を契約として固定しない限り、T-07前置き面を1 frameでも
  介在させる回帰をtest oracleが取り逃がす。
- 「後で」の6秒hint（spec 232）のpath文言は「設定 → Home screen → Organize home layout」を
  実label合成で案内する
  （[OrganizationOnboardingProposal.kt:580-585](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt)、
  3番目の引数が `manual_organization_title`）。#367適用後、設定 → Home screenの
  General groupにはhub入口row（`organizer_hub_title` → `HomeScreenOrganizer`）と
  manual organization直行row（`manual_organization_title` → `HomeScreenManualOrganization()`）が
  **暫定併存**しており
  （[HomeScreenPreferences.kt:92-109](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)）、
  D-01「設定側は入口rowだけを残す」の完成途中である。直行rowを案内する現行hintは、
  D-01完成後には実在しないrowを指す。spec 232の真実性規約（hintは実UI labelから合成され
  driftしない）と衝突する。
- spec 232 AC-1（案内先=Home settingsのHome screen内）/ AC-3（`Organize home layout`行が
  General sectionに置かれること）は旧案内先・旧入口構成を固定しており、D-01完成後の
  設定構成（organizer由来rowはhub入口row 1件）と矛盾する。

## Outcome

onboarding提案からOrganizer run面・再開導線への接続が、hubを中心とするTO-BE情報設計と一致する。
「確認」はrun admissionへ直行し（T-07前置きを省略、方法は「そのまま整理」固定）、admission後は
#369の統合run面を辿る。「後で」の6秒hintは設定 → Home screen → hub入口rowを案内し、
copyは実UI labelからの合成（locale追従）を維持する。設定 → Home screenのGeneral groupから
manual organization直行rowを廃止し、organizer由来rowをhub入口row 1件にする（D-01の完成。
#367まで暫定併存していた直行rowとの併存を解消する）。spec 53は接続先の表記更新のみを受け、
spec 232はAC-1案内先とAC-3入口row構成をhub時代へ改訂する。outcome・eligibility・再表示の
契約（spec 53 AC-001/AC-002）は1行も変わらない。

## Scope

- **「確認」の接続契約の固定**: `確認` tap → `start(Trigger.ONBOARDING_PROPOSAL)` による
  admission → **方法選択面（T-07）を表示せず**admitted runの進行表示（#369統合run面の
  T-09準備中以降）へ直接到達すること。Busy時の挙動（admission不成立・遷移なし・提案は
  再試行可能・既存runのrelabel禁止）はspec 53 §3.2契約のまま変更しない。
- **re-entry hintの案内先・copy更新**: hintのpath構成の第3要素を `manual_organization_title`
  からhub入口rowの実label（`organizer_hub_title`）へ置換する。format resource
  （EN/ja）による合成、実UI label参照の真実性規則（spec 232）、6秒寿命・非block・dismiss
  契約は維持する。hintのjavadoc等の案内先記述も同一PRで更新する。
- **設定General groupのmanual organization直行rowの廃止**: #367まで暫定併存していた
  `manual_organization_title` 直行row（destination `HomeScreenManualOrganization()`）を
  設定 → Home screenのGeneral groupから削除し、設定側のorganizer由来rowをhub入口row 1件
  （`organizer_hub_title` → `HomeScreenOrganizer`）にする。廃止後、手動での新規開始は
  hub入口row → hub「整理を開始」→ run面T-07前置き → 「そのまま整理」の経路に集約される
  （`MANUAL_FULL` trigger導出はhub CTAと同一の`HomeScreenManualOrganization()` routeを
  通るため不変）。`manual_organization_title` / `manual_organization_summary` resourceの
  削除は行わない（run面scaffold title等の実在用途が残る。resource cleanupは#377所有）。
- **spec 53の表記更新**（実装PRで実施）: §3.2（entry契約に「T-07前置きを省略し
  方法は『そのまま整理』で固定される」旨を追記。加えて、同節のobservable workflow block
  （`explicit Review/Start -> Capture -> Plan -> Preview -> ...` の直結列挙）は#369が
  固定したcanonical順序（検出→[候補あり時のみ選択]→capture/plan→確認）を反映していない
  旧表記であるため、順序を再複製せず「admission後はspec 52/#369が定義する共通run
  workflowを辿る」旨の参照化へ更新する）、§5.2/§5.3（review surface /
  Review destination ownerの接続先表記をhub時代のrun面参照へ更新）。
  AC-003「#52 workflow再利用」の文言は不変。outcome表・eligibility・§3.3/§3.4・
  diagnostics契約には触れない。
- **spec 232の改訂**（実装PRで実施）: AC-1の案内先をhub入口rowへ、AC-3を
  「organizer由来rowはhub入口row 1件（manual organization直行rowは廃止済み）」構成へ更新。
  該当するBehavior scenario・Test oracle行を連動して更新する。
- **旧案内先・旧入口構成を固定したtestの更新**: hintのlabel構成assert、General groupの
  直行row assert（暫定併存oracle含む）、「確認」経路の遷移assertを更新し、
  obsolete理由をPRに記録する（disposition §3.4のtest migration）。

## Non-goals

- onboarding契約本体の変更: provenance判定（`OrganizationOnboardingInstallProvenance`）、
  outcome semantics（spec 53 §3.1の表）、eligibility、再表示規則、backup/restore協調
  （spec 53 AC-001/AC-002は不変）。
- hintの新設・常設化・表示経路の変更（6秒・非block・`Later`直後の1回表示の現行契約維持）。
  hintにaction buttonや常設導線を付けない。
- T-07前置き面・run面の8状態統合の実装（#369が所有）。#370は#369の面構成を前提に
  onboarding経路の接続を固定するのみであり、run面をforkしない。
- hub shell・status card・材料導線の実装（#366・#367、merge済み）、strategy移設（#368）、
  AI相談（#372）。
- `manual_organization_title` 等のstring resourceの削除（#377 cleanup所有）。
- post-cancel等でrun面がIdle/Cancelledに戻った後の面構成の再設計。onboarding由来の
  routeであってもterminal後のIdle面は#369のrun面契約（T-07）に従い、retry/recaptureの
  triggerはspec 53 §3.3（`ONBOARDING_PROPOSAL`保持）のまま変更しない。
- run state machine、`OrganizationEntry`/`HomeScreenManualOrganization` routeのtrigger導出、
  diagnostics event、persistent store、`organization-run-ux.md` / `requirements.md` /
  `CONTEXT.md` の改訂（正本改訂は#365としてmerge済み）。

## Domain language

- **Organizer hub / hub入口row / 材料**: organizer-to-be-ux.md D-01・§10を正本とする既存語彙。
  hub入口rowは#366実装（label `organizer_hub_title` / subtitle `organizer_hub_summary` /
  destination `HomeScreenOrganizer`）である。`CONTEXT.md` への追加は#365が実施済みであり、
  本specでは複製しない。
- **T-07（run前置き面）**: 方法選択とscope要約の面（TO-BE §5.1）。admissionを含まない。
  #369がMANUAL entryの開始点として導入する（#372統合前のtransitional構成:
  方法選択CTA「そのまま整理」のみで「AIに相談」の選択肢行は新設しない。369 spec RD-1）。
  onboarding「確認」経路はこの面を経ない。
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

### Scenario: 設定側のorganizer入口rowはhub入口row 1件である

Given #370適用後の設定 → Home screen画面を開く
When General groupの行構成を確認する
Then organizer由来の入口rowはhub入口row（`organizer_hub_title` → `HomeScreenOrganizer`）1件であり、
manual organization直行row（`manual_organization_title` → `HomeScreenManualOrganization()`）は
存在しない
And hub入口rowのlabel・subtitle・destinationは#366実装から変化しない
And 設定画面の他の行（General group内の非organizer行、Home screen actions group）の
構成・挙動は変化しない

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
And hub入口row経由（旧直行row経由は廃止済み）からの新規開始は `MANUAL_FULL` のままである

## Data and state

- 読むdata: 変更なし。proposal store・provenance・process stateの既存readのみ。
- 永続化: 変更なし。新規preference/storeなし。hintはprocess内の一時viewでありretentionを持たない。
  設定rowの削除はnavigation構成のみであり、preference key・route schemaに触れない。
- identity: 変更なし。run ID・trigger・`OrganizationEntry` route引数の意味は不変
  （routeは安定したcaller contextのみを保持する現行契約、
  [PreferenceRoutes.kt:119-133](../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt)）。
- migration / backup / restore / rollback: 対象外。Launcher layout DB / `favorites` には
  接触しないためホームレイアウト安全規約の適用対象外である。PR revertで現行copy・
  row構成・表記へ戻る。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataの扱い追加はない。diagnosticsへの新規出力もない。

## Accessibility and localization

- hintのTalkBack契約（title + bodyを1つのannouncementとして伝える、focus復帰、
  200% font scale reflow、非色依存）はcopy変更後も維持する（spec 232 AC-4、NFR-009）。
- path文言の真実性規約（hintは実際にユーザーが見る設定labelから構成されdriftしない）を
  hub入口row labelへ適用する。label合成は既存どおりformat resource（`values/` と
  `values-ja/` で引数集合・placeholder一致）で行い、Kotlin側の文連結を新設しない
  （spec 123 AC-4/AC-5規約）。
- 直行row廃止による設定画面の行数減は、General group内の残存行（hub入口row含む）の
  TalkBack読み上げ・focus順を壊さない。削除される行の読み上げが消えるのみである。
- 「確認」経路の遷移先変更は、spec 53 §6のaccessibility契約（統合run面側の受入基準）の
  適用範囲を変えない。onboarding経路に新しい面を追加しないため、新しい読み上げ要件も生じない。

## Compatibility and migration

- **依存順**: 実装は#369実装（同Issue Phase 2）merge後に行う（Issue本文 `Depends on`。
  spec/planは受入済み PR #386）。#365（正本改訂、PR #379）、#366（hub、PR #380）、
  #367（材料移動、PR #382）、#368（strategy移設、PR #384）、処分文書（PR #378）は
  merge済みであり、本specの前提はすべて成立済みである。
  #367は材料row移動のみを実施し、hint copyには触れていないため、hint更新の実装は
  本Issueが一意に所有する（旧draftにあったmerge順分岐は解消済み）。
- **暫定併存の解消**: #367適用後の現状、General groupにはhub入口rowと直行rowが併存する。
  本Issueは直行rowを廃止してD-01を完成させる。廃止PRの前後で、設定 → Home screenから
  organizerを発見できる経路が途切れない（hub入口rowが常時存在する）。
- **downgrade / rollback**: copy・row構成・表記・test更新のみであり、PR revertで完全に戻る。
  persistent stateの残留物はない。

## Dependencies

- **#369（run面統合）**: 「以降は#369の統合run面を辿る」ACとT-07前置き省略の意味論、
  guard testの対象面の前提。**spec/planは受入・merge済み（PR #386、merge `171d0bcf10`）**。
  実装着手は#369実装（同Issue Phase 2）merge後。#369はonboarding接続変更をnon-goalsとして
  #370へ委譲済み（accepted specのNon-goals「T-07はMANUAL entryの開始点としてのみ導入」）。
- **#366（hub、merge済み PR #380）**: hub入口row（`organizer_hub_title` /
  `organizer_hub_summary` / destination `HomeScreenOrganizer`）とhint案内先の実在の根拠。
- **#367（材料移動、merge済み PR #382）**: 直行row暫定併存状態を作った段階。
  本Issueが併存を解消する（正本disposition §2.3/§7.2(b)の責務分割どおり）。
- **#368（strategy移設、merge済み PR #384）**: run面からstrategy pickerが撤去済みであることの
  根拠（本Issueはrun面へ触れない前提の一部）。
- **#365（正本改訂、merge済み PR #379）/ 処分文書（merge済み PR #378）**: 前提正本。
  本specの参照はこれらに整合済み。

## Acceptance criteria

- [ ] **OCB-AC-01**: 「確認」がT-07前置き面を表示せず `start(Trigger.ONBOARDING_PROPOSAL)`
      によるrun admissionへ直行し、admission後は#369の統合run面を辿る。Busy時は
      spec 53 §3.2契約どおり（admission不成立・遷移なし・再試行可能・relabel禁止）である。
      （Issue受入1）
- [ ] **OCB-AC-02**: 「後で」の6秒hintが設定 → Home screen → hub入口rowを案内し、
      copyが実UI labelからのformat resource合成（EN/ja、locale追従）と一致する。
      hintの6秒・非block・dismiss・focus復帰・表示失敗時の安全性の各契約は変化しない。
      （Issue受入2）
- [ ] **OCB-AC-03**: 設定General groupのmanual organization直行rowが廃止され、
      設定 → Home screenのorganizer由来rowがhub入口row 1件になっている
      （spec 232 AC-3改訂と同一PR）。他の設定行の構成・挙動は変化しない。（Issue受入3）
- [ ] **OCB-AC-04**: defer/skip/outcome永続化・再表示規則（spec 53 AC-001/AC-002）、
      proposal/hint actionのrun-journal非発行、onboarding由来retryの`ONBOARDING_PROPOSAL`保持
      （spec 53 §3.3）に変更がない。persistent state・diagnostics契約へのdiffが存在しない。
      （Issue受入4）
- [ ] **OCB-AC-05**: spec 53（§3.2の接続表記・workflow block参照化。§5.2/§5.3の接続先表記。
      AC-003不変）とspec 232（AC-1案内先、AC-3入口row 1件構成）が本specの定義どおり更新され、
      §3.2に旧 `explicit Review/Start -> Capture -> Plan` 直結workflow表記がnormative textとして
      残留しないこと、旧案内先・旧入口構成を固定したtest（hint label構成assert、
      General group直行row assert（暫定併存oracle含む）、「確認」経路の遷移assert）の更新と
      obsolete理由がPRに記録されている。既存organizer unit gateとinstrumentation laneが
      greenである。（Issue受入5）

## Test oracle

| AC | Evidence |
|---|---|
| OCB-AC-01 | instrumentation（`OnboardingOrganizationProposalInstrumentationTest.realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface`拡張。guard testは**admitted runを実際に再現する**: 遷移先run面が実際に消費する同一process-local runnerに対し、proposalの `admitReview` からproduction admission path（既定実装の `start(ONBOARDING_PROPOSAL)`）または同等のfixture `start()` を実行してから遷移する。既存 `TouchActivationGate` の `reviewOutcome.get()` スタブ（実際のrunner開始を伴わない）をそのまま遷移先観測に使わない。その上で、#369実装のsurface seamの決定的観測点（test-only observer / host trace等。無ければ#370のtest側へ決定的seamを新設する。sampling退避はしない）により「**最初に表示されるfaceがT-09であり、T-07のcompose/render回数が0**」を直接固定する。反復samplingは補助に留め、単独ではrender count 0の証明にもguard testの完了条件にもならない）。既存 `busyReviewKeepsProposalOutcomeUntouchedAndRetryableByRealTouch` の緑維持。unit（`OrganizationOnboardingProposalTest`: admission→REVIEWED記録順序の回帰。face mapping純関数`manualOrganizationFace(state)`のtable-driven test（`ManualOrganizationFaceTest`、#369実装導入）はadmitted状態→T-09対応のcomponent-level証拠として併用） |
| OCB-AC-02 | instrumentation（`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`のlabel構成assert更新: hub入口row label `organizer_hub_title` を含み`manual_organization_title`を含まないこと。EN/ja双方のformat resource合成assert）。string diff（`values/` / `values-ja/` のname集合・placeholder一致）+ emulator screenshot（light/dark × ja/default） |
| OCB-AC-03 | instrumentation（`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`の入口row assertをhub入口row版へ更新、`OrganizerDiagnosticsRouteInstrumentationTest.homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`の暫定併存assertを「直行row不在＋hub入口row表示」へ更新）。obsolete理由（D-01完成・#370のrow廃止）をPRに記録 |
| OCB-AC-04 | 既存unit/instrumentationの無編集green（outcome・provenance・journal非発行の回帰群）。実装PR diff上、persistent store / diagnostics契約コードの無編集確認 |
| OCB-AC-05 | specs 53/232のdiff review（本specのScopeに対応する行のみの変更、change history追記。spec 53 §3.2に旧直結workflow列がnormative textとして残留しないことをdiff上で確認）+ test更新の obsolete理由のPR記録 + `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象instrumentation lane、CI `final-status` green |

## Open questions

実装開始前に解消が必須な問いはない。旧draftにあった非blocking事項は、#369 specの
受入（PR #386）によりすべて解消済みである:

1. **post-#369のonboarding由来routeで、terminal後のIdle面（T-07）の「AIに相談」分岐の
   表示有無**: 解消済み。accepted 369 spec RD-1のとおり、T-07は#372統合前の
   transitional構成（方法選択CTA「そのまま整理」のみ。「AIに相談」の選択肢行は新設しない）
   であり、exchange idle entry rowはspec 205 V1どおりIdle/Cancelled面（transitional T-07）に
   hostされ続ける。onboarding由来routeであってもterminal後のIdle面は同一のtransitional T-07
   であり、本spec（run面をforkしない）と整合する。実装PRで表示を確認し、差異があれば
   Change historyへ記録する。
2. hub入口rowのlabel/resource名は#366実装で確定済み（`organizer_hub_title` /
   `organizer_hub_summary` / destination `HomeScreenOrganizer`）。hint合成は実renderされる
   label resourceを参照し、値を複製しない。
3. spec 232改訂後のAC-3文言は、正本disposition §2.3/§5順序4の責務分割
   （#370が直行row廃止とAC-3改訂を所有）に従い「organizer由来rowはhub入口row 1件」構成で
   確定している。

## Change history

- 2026-09-19: Draft created for #370（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`、PR #364）、処分文書draft（PR #378
  §3.4/§4/§5）、先行spec drafts（#366 `bf00f96175` / #367 `a50f074ac2` /
  #368 `206a4c1198` / #369 `3c39ceb2f8`）、現行実装調査
  （`OrganizationOnboardingProposal.kt`、`PreferenceRoutes.kt`、`PreferenceNavigation.kt`、
  `HomeScreenPreferences.kt`、`strings.xml`/`values-ja/strings.xml`、
  organizer unit/instrumentation test群）を入力に作成。
- 2026-09-20: Re-entry revision（review指摘3点＋責務分割への追従）。
  (1) 正本disposition（accepted、PR #378 merge後）の2026-09-19追記どおり、
  General groupのmanual organization直行row廃止＋spec 232 AC-3改訂（入口row 1件構成）＋
  hint更新を本Issueの所有としてScope/OCB-AC-03へ追加し、#367とのmerge順分岐を解消
  （#367はmerge済みで材料row移動のみ。OCB-ACは5件へ再編）。旧draftの
  「AC-3/構造oracleを#367に一本化」方針は、段階契約の変更により廃止。
  (2) baselineを `171d0bcf10`（#366/#367/#368/#365/#378 merge後）へ更新し、正本・依存の
  状態を「merge済み」へ固定（旧draftの「PR #378 proposed」「#365 OPEN」表記の解消）。
  (3) T-07前置き面非介在のoracleを、#369 spec受入（PR #386）で確定した
  surface seam（transitional T-07の主CTA、face mapping純関数`manualOrganizationFace(state)`、
  T-09統合progress面）へ固定し、T-07 render不在の直接観測を含む形へ具体化
  （review指摘2の解消）。
  (4) 旧Open question 1（terminal後Idle面のAI分岐）は、accepted 369 spec RD-1
  （transitional T-07にAI選択肢行を新設しない）により解消済みとして記録。
- 2026-09-20: Review revision（`issue-370-spec-plan-r2@9bf34803ec` へのreview
  「Changes requested」2点対応）。
  (1) spec 53更新scopeへ§3.2のobservable workflow block（旧
  `explicit Review/Start -> Capture -> Plan` 直結列挙）の参照化を追加し、OCB-AC-05と
  そのoracleへ「旧直結workflow表記の残留なし」を明記（#369 canonical順序との
  §3.2内部矛盾の解消）。
  (2) OCB-AC-01のoracleを修正: 既存 `TouchActivationGate` の `admitReview` スタブ
  （`reviewOutcome.get()` のみでproduction runnerを開始しない）ではadmitted runを
  再現できないため、guard testは同一process-local runnerへの実際の
  `start(ONBOARDING_PROPOSAL)` を伴うadmission経路で遷移先を作ること、
  T-07非介在は決定的観測点（最初の表示face＝T-09、T-07 compose/render回数0）で
  直接固定すること、反復samplingをrender count 0の証明と位置付けないことを契約化。

[1]: https://github.com/nunu1733/NunuLauncher/issues/370
[4]: https://github.com/nunu1733/NunuLauncher/pull/378
[5]: https://github.com/nunu1733/NunuLauncher/pull/386
