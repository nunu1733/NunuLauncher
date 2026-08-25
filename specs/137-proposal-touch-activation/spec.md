---
issue: "#137"
status: accepted
requirements:
  - NFR-009
updated: 2026-08-25
---

# Onboarding proposalのactionを単一touchで活性化できるようにする

## Problem

Fresh-install直後に表示されるonboarding organization proposal([spec 53](../53-onboarding-organization-proposal/spec.md)で実装済み)において、release/minified buildで `Later` と `Review organization` を通常touchしても、buttonがinput focusを得るだけでactionが実行されない。focusが当たった状態でkeyboard `Enter` を押すと即座に実行される。`Skip` は当該runで直接検証されていないが、同一surfaceを共有するため同じ欠陥の潜在的対象でありregression/受入caseとして扱う。

観測はIssue #137本文と、[#132 dogfooding branch](https://github.com/nunu1733/NunuLauncher/tree/codex/issue-132-dogfooding)の `docs/assessment/evidence/issue-132-exploratory-baseline.md`(redacted durable subset)に記録済みである。tap後のUI stateは `text="Later" clickable=true enabled=true focused=true` でproposalが残留し、keyboard `Enter` では正常に実行・遷移した。

同じセッションでworkspace設定、preference navigation、manual organization、organizer result surfaceの `Try again` はtouchで正常に動作しているため、emulator入力全般の障害ではなく、このproposal表面に局所した欠陥である。

source上の根拠:

- 回帰導入commitは `1e4da2d0a20964551d225b38fd6d8f1312281d61`(`fix(onboarding): allow touch-mode proposal focus`, 2026-08-21)である。これはPR #95監査でのdeterministic keyboard focus要求(NFR-009)への対応として、title・3 action button・popup rootへ `isFocusableInTouchMode = true` を追加した。
- Android platformでは `focusableInTouchMode` のviewへの最初のtapはclick実行ではなくfocus取得として消費される(first tap takes focus)。upstream Launcher3自身も `NavigableAppWidgetHostView.requestChildFocus` でfocused childから `setFocusableInTouchMode(false)` を剥がしており、FITM childがtouch clickを壊すことへの対処先例がある。
- 既存の `OnboardingOrganizationProposalInstrumentationTest` はaction実行を `performClick()` 呼び出しとkey eventだけで検証しており、実際のtouch gesture経路を一度も検証していない。このため回帰がCIで検知されなかった。

反復tapでも1度もclickが発火しない厳密なframework-level機構(毎tapが「最初のtap」扱いになっているのか、gesture途中cancelなのか)は現時点で確認済み事実ではない。plan側Phase 0の診断で確定させる。本specの受入条件はこの機構確定に依存しない観測可能振る舞いとして定義する。

## Outcome

Proposalの3 action(`Skip` / `Later` / `Review organization`)が、keyboardや2回目以降の追加入力を要求せず、1回の通常touchで即座に実行される。同時に、#95で受入済みのDPAD/keyboardによる決定的focus進行・初期focus entry・close時focus復帰(NFR-009)は損なわれない。

## Scope

- proposal action buttonからの `focusableInTouchMode` 除去(または同等の最小修正)による単一touch活性化。
- touch mode中のprogrammatic requestFocusによる初期focus entry(決定的エントリ)の維持。
- keyboard/DPAD traversal、Back dismiss、focus restorationの現行挙動維持。
- 実際のtouch gesture注入を用いたproduction UI regression testの追加(`performClick()` やfocus獲得をsuccessとみなさない)。
- `Skip` / `Later` / `Review organization` の3 actionすべてのtouch活性化検証。

## Non-goals

- proposal状態model、outcome永続化、review admission順序(#53契約)の変更。
- organizer state machine、capture/planning/apply/recoveryの変更。
- Launcher DB、schema、migration。
- proposal表面のCompose書き換えやonboarding framework化。
- diagnostics契約の変更(proposal-only actionがjournal eventを出さない規則は不変)。
- 他のfloating viewやpreferences surfaceへの一般化修正。
- full Switch Access evidence matrixの整備([#109](https://github.com/nunu1733/NunuLauncher/issues/109)が正本。本issueはkeyboard/DPADまでの回帰保護にとどめる)。
- 文言・資源変更。

## Domain language

なし(用語追加なし。既存語彙は [CONTEXT.md](../../CONTEXT.md) を参照)。

## Behavior scenarios

### Scenario: 単一touchでLater

Given fresh-installでeligibleなprocessでproposalが表示されている(touch mode中)
When ユーザーが `Later` を1回tapする
Then proposalが閉じ、outcome `DEFERRED` が記録される
And layoutとrun journalは変化しない
And 同一process内でproposalが再表示されない

### Scenario: 単一touchでReview organization

Given proposalが表示されている
When ユーザーが `Review organization` を1回tapする
Then shared coordinatorへのfresh start admissionが先に実行され、`Started` なら `REVIEWED` を記録してreview surfaceへ遷移する
And `Busy` ならproposalが閉じずretry可能なまま残る(touchでも再試行できる)

### Scenario: 単一touchでSkip

Given proposalが表示されている
When ユーザーが `Skip` を1回tapする
Then outcome `SKIPPED` が記録されてproposalが閉じる
And 以後そのinstallationで自動再表示されない
And layoutとrun journalは変化しない

### Scenario: keyboard activationのregression保護

Given DPAD/keyboard操作でactionにinput focusがある
When `Enter` / `DPAD_CENTER` を押す
Then 対応するactionが実行される
And title→Later→Skip→Reviewの決定的focus進行とclose時focus復帰は現行どおりである

### Scenario: touch modeからのkeyboard切替

Given touch mode中にproposalが表示され初期focusがtitleにある
When DPAD_DOWN等のkey入力を行う
Then key入力によりtouch modeが離脱し、focus navigationが成立して次のactionへ進行する

### Scenario: 失敗/edge case

Given proposalが表示されている
When 高速連打、activity recreation、process再起動が発生する
Then duplicate run開始やoutcome上書き競合は発生しない(既存 `reviewInFlight` guardとclaim presentation契約は不変)
And 200% font scaleでも3 actionがviewport内でtouch可能である
And proposal表示中に他のlauncher操作がblockされない

## Data and state

- 読むdata: 既存のproposal outcome preference(`OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME`)のみで、読み方・正本とも変更しない。
- 永続化: 新規stateなし。既存outcome記録(`SKIPPED` / `DEFERRED` / `REVIEWED`)だけが書かれる。
- migration、backup/restore、downgradeへの影響: なし。
- layout扱い: 本specの全scenarioはlayout不変であり、#53 spec §3.1の「proposal-only actionはorganization workを行わない」契約を維持する。

## Permissions, privacy, and security

None — 新permission、外部送信、sensitive dataの扱い変更はない。修正は単一app内view属性とtestである。

## Accessibility and localization

- NFR-009: TalkBack label/role/state、200% font reflow、非色依存の警告表現は現行維持。
- 初期focusの決定的entryとclose時focus復帰を維持する。touch活性化とkeyboard活性化の両立が本specの中心要件である。
- 本issueでaccessibility semantics(TalkBack label/role/state、focus entry/restoration)は変更しない。回帰保護の対象はkeyboard/DPADまでとし、full Switch Access evidence matrixは[#109](https://github.com/nunu1733/NunuLauncher/issues/109)が正本として所有するため本issueでは追加しない。
- 文言変更なしのためtranslation影響なし。

## Acceptance criteria

- [ ] AC-1: supported phone-class runtime(API 36.1 emulator)で、実際のtouch gesture注入により `Skip` / `Later` / `Review organization` の各actionが1回で活性化する。focus獲得のみをsuccessとみなさず、outcome記録・画面遷移等の副作用で判定する。
- [ ] AC-2: keyboard/DPAD activationとfocus orderが機能し続ける。`Enter` 実行、title→Later→Skip→Reviewの決定的進行、close時focus復帰、200% font scaleでの操作性が回帰testで保護される。
- [ ] AC-3: touch活性化された `Later` はaccepted onboarding policyどおりdeferし(layout不変、同process再表示なし、次のqualifying cold startで再有資格)、`Skip` はaccepted dismissal behaviorを保持し、`Review organization` はduplicateなく1回だけfresh manual workflowへ遷移する(Busy時はtouchで再試行可能)。
- [ ] AC-4: recreation/restart後もstuckまたはduplicate proposalが残らない。
- [ ] AC-5: AC-1〜AC-4を満たすproduction UI regression testが存在し、実touch gesture注入を使用する。新testは修正前code上でfailすることを確認してPRへ記録する(実施seamはplan Phase 0 gateの所見に従い、debug buildでpre-fix再現が成立しない場合はdebug instrumentationをoracleに使わない)。
- [ ] AC-6: 関連unit/instrumentation suite、release build、formatting、repository gates、CI merge gate(`final-status`)が成功する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | API 36.1 connected instrumentation: real launcher floating host上でmotion event注入(`Instrumentation.sendPointerSync` 等のgesture注入seam)により3 actionそれぞれを実行し、FakeStore outcomeとview closedをassert |
| AC-2 | 既存keyboard/DPAD traversal instrumentationの維持 + key event assertions(必要に応じてtouch→key切替case追記) |
| AC-3 | touch活性化後のstore assertion、同process再表示抑制assertion、busy admission retry case(既存fake admission seam) |
| AC-4 | 表示中proposalのrecreationで二重表示なし、`Later` / `Skip` / `Review organization` 各outcome後のcold start再表示制御、`Busy` 時の非消費retry。planのrecreation/cold-start matrixに対応。process death相当で自動化が安全でないcellはrelease/minified手動evidenceへ明示 |
| AC-5 | 新regression testのpre-fix fail記録(fix commit前の実行結果) |
| AC-6 | `spotlessCheck`、repo-contract validator、organizer unit tests、assemble debug/androidTest、connected tests、CI `final-status` link |

## Open questions

- 反復tapでclickが発火しないframework-level機構の正確な分類(H1: 毎tap focus再取得 / H2: gesture途中intercept-cancel)。plan Phase 0(gate)の診断対象であり、受入条件は観測可能振る舞いで定義済みのためnon-blockingである。
- debug buildでpre-fix failureが成立しない場合のregression oracle seam選定。plan Phase 0 gateのNO-GO判定手順に従い、実装開始までに確定する。受入条件自体は変えないためnon-blockingである。

## Change history

- 2026-08-25: Draft created for #137.
- 2026-08-25: Review対応。AC-5/oracleをPhase 0 gate所見と同期し、recreation/cold-start oracleをAC-4へ明示、Switch Access evidenceの責務境界(#109)を明記。
- 2026-08-25: Accepted。AC-1〜AC-6を凍結しStage B実装を開始する。
