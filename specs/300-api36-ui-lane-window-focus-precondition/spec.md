---
issue: "#300"
status: draft
requirements:
  - TS-AC-01
  - TS-AC-02
  - TS-AC-03
  - TS-AC-04
  - TS-AC-05
  - TS-AC-06
  - TS-AC-07
risk: []
updated: 2026-09-12
---

# api36 UI lanes: 実入力注入は対象windowのfocus観測を前提とし、environment health state による単一証拠採取と即時失敗で burst の待機連鎖を収束させる

## Problem

api36 UI lane（`organizer-instrumentation-issue53-tests`、`organizer-instrumentation-issue52-tests`）で、
実入力注入（`UiAutomation.injectInputEvent` による touch、`sendKeyDownUpSync` による key）を
使うテストが、1 run 内に連続失敗（burst）する環境フレイクが複数回発生している。

- [run 34625444030](https://github.com/nunu1733/NunuLauncher/actions/runs/34625444030)
  attempt 1: issue53 lane で多数失敗（issue #292 調査時に「emulator 全体の不調」パターン
  として記録、rerun green）。
- [run 34674138315](https://github.com/nunu1733/NunuLauncher/actions/runs/34674138315)
  （PR #297）: issue52 lane `ManualOrganizationPreferencesInstrumentationTest >
  changeListTraversalReachesExpandAndReviewActions` が 1 失敗、rerun green。
- [run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)
  attempt 1（PR #297 rebase後 head）: issue53 lane で 11 失敗。同一 head の api35 lane
  は green であり、本フレイクは PR diff と無関係である。

失敗シグネチャ（run 34677444335、job log 実測）:

```text
java.lang.IllegalStateException: touch injection never reached the proposal after 3 attempts;
events=[]; launcherWindowFocus=false, activityFocus=android.widget.TextView{... .F...... ...},
proposalOpen=true, proposalAttached=true, targetShown=true, ... topOpenView=...ProposalView{...}
```

確認できた事実:

1. **注入が「宛先windowに届かない」形でだけ失敗する**。8 失敗すべてで `events=[]`
   （touchLog が完全に空、DOWN も届いていない）、かつ `launcherWindowFocus=false`。
   proposal は open・attached・visible で、対象 button は正しい位置・サイズで shown である。
2. **view-level focus は成立している**。`activityFocus` の toString フラグ `.F......`
   （isFocused=true）が示す通り、view 自体は focus を持つ。既存の per-test 待ち
   （`awaitVisibleProposalActions`、`awaitInputFocus`）は view の可視性・view focus を
   見るため通過し、失敗は injection delivery だけに現れる。
3. **同種の症状が touch 以外にも現れた**。同一 run で DPAD traversal 待ち
   （`No proposal action received input focus after DPAD traversal; launcherWindowFocus=false`）、
   frontmost window 前提の accessibility 走査（`organizer entry ... was not found in the
   accessibility tree`）、さらに `awaitResumedLauncher` のタイムアウト
   （`LawnchairLauncher did not reach an attached, laid-out RESUMED state after HOME launch`）
   が続発した。
4. **`injectInputEvent` の真偽値は delivery を保証しない**。`deliveredTap` は
   injection 戻り値 true を確認した上で 3 attempt × 1.5s 待つが、window focus 欠落下では
   再試行が同じ喪失状態に再投入されるだけである。
5. **環境は per-boot で決まる**。emulator は runner により boot 直後に
   `input keyevent 82`（unlock 相当）を 1 回受けるのみで、`screen_off_timeout` は
   無限大に設定されている。rerun（別 boot）では green であり、head・diff 非依存である。
6. **issue52 lane の失敗は仮説段階である**。`(Focused = 'true')` assertion 失敗は
   「実鍵注入が focus 遷移を起こせなかった」と整合するが、同時点の window focus を
   記録する diagnostic が存在せず、`hasWindowFocus()==true` のまま Compose focus
   traversal だけ失敗した可能性も残る。issue52 を issue53 と同一原因とは主張しない。
   判別に必要な状態採取を本 Issue で整備する（TS-AC-06）。
7. **どの per-boot 状態が window focus を奪うかは未確定であり、CI には failure 時の
   window 状態証拠が残らない**。root cause の特定は本 Issue から分離し
   [Issue #304](https://github.com/nunu1733/NunuLauncher/issues/304) で追跡する
   （2026-09-12 の Spec/Plan review での決定。Issue #300 終了条件の更新を参照）。

Spec review で確定した設計上の制約（2026-09-12 の 2 回の review）:

- **run を収束させるには run-level の environment health state が必要である**
  （review 1 指摘 1）。さらに（review 2 指摘 1）health 確認は environment 操作
  （修復・待機）の**入口**で行われなければならない。別個の `checkAtGateEntry()` を
  待ちの後に置く設計では、unhealthy 判定後のテストも修復・待機を実行できてしまい、
  「最初のテストだけが待機・修復する」という要件を満たせない。
- **`markUnhealthy` は environment 前提の崩壊を示す観測がある場合に限る**
  （review 2 指摘 2）。`awaitResumedLauncher` の timeout は launcher の lifecycle 回帰、
  accessibility 走査の timeout は organizer entry が本当に消える UI 回帰でも発生する。
  window focus gate を正常に通過し frontmost window も正しいのに対象 text が無い場合は
  検出すべき製品回帰であり、これを sticky unhealthy にすると後続テストが全て
  environment 即時失敗になり、merge gate の診断能力を落とす。
- **run-level health state は決定的に検証できる必要がある**（review 2 指摘 3）。
  強制状態（KEYCODE_SLEEP 等）はまさに gate の自動修復対象であるため、正常実装なら
  gate failure に届かず green になり、「最初だけ証拠を採取し、以後待機せず同じ証拠を
  参照する」state machine を実環境で実証できない。決定的な failure injection seam を
  設ける。実環境の強制状態試行は #304 用の証拠として別扱いする。
- **health state の有効範囲の根拠は「1 instrumentation invocation ＝ 1 process」である**
  （review 2 指摘 4）。issue52 job は 4 クラス
  （`ManualOrganizationProductionE2EInstrumentationTest`、
  `ManualOrganizationPreferencesInstrumentationTest`、`StrategyPickerInstrumentationTest`、
  `MissingAppSelectionInstrumentationTest`）を同じ Gradle invocation で流す
  （ci.yml 実測）。issue53 job は 1 クラスである。検証は本番 lane topology ごとの
  別実行で行い、issue52/53 のクラスを同一 process に混ぜない。
- **health state の保証範囲は、helper を配線した gated test クラスに限る**
  （review 3 指摘 1）。issue52 invocation の他 3 クラスは health state を参照しないため、
  「本番 4 クラス filter 全体が即時失敗する」という主張は成立しない。収束保証の対象を
  gated クラスに限定し、決定的配線検証も gated クラスの明示指定で行う。
- **topology は「現行」と「修正後」を分けて記述する**（review 3 指摘 2）。状態 test
  クラスの issue53 lane 追加により、修正後の issue53 filter は 2 クラスになる。
  配線検証は修正後 filter 全体ではなく、gated クラスを明示指定して行う。
- **連続 green の判定条件は固定する**（review 3 指摘 3）。「rerun なし」と
  「同一 job の連続 attempt を含む」は矛盾する（attempt 増加は rerun による）ため、
  「異なる workflow run を 3 回連続、各 `run_attempt = 1` で両 lane green。rerun で
  green にした run はカウントしない」と固定する。

## Outcome

api36 UI lane では、touch/keyboard 注入は対象 window が window focus を保持している
ことを観測した後にのみ行われる。すべての environment 操作（修復・focus 待ち）は入口で
run-level health state を確認し、unhealthy なら何も修復・待機せず即座に、最初の
failure が採取した environment 証拠を参照して失敗する。この保証は environment helper を
配線した gated test クラス（issue53: `OnboardingOrganizationProposalInstrumentationTest`、
issue52: `ManualOrganizationPreferencesInstrumentationTest`）に適用され、同一 invocation
内の他クラスは health state を参照しないため通常どおり実行される。証拠採取は「environment 前提の
崩壊を示す観測」がある場合に限られ、環境が正常な失敗（launcher lifecycle 回帰、
UI 回帰）は従来どおり個別の failure として報告される。per-boot 環境問題は
「繰り返し待機する失敗連鎖」ではなく「1 つの証拠採取＋即時失敗の連鎖」として現れ、
state machine は決定的な injection seam で検証され、gate 証拠は root-cause 確定
Issue（#304）の一次資料になる。

## Scope

- `tests/organizer-instrumentation` 内に test 支援実体を 1 つ追加する:
  - `ensureWindowFocused(activity, deadline)`: **入口で health state を確認**した上で、
    対象 activity の window focus を bound 付きで待つ。非 interactive
    （`PowerManager.isInteractive`）なら `input keyevent KEYCODE_WAKEUP`、keyguard
    showing（`KeyguardManager.isKeyguardLocked`）なら `wm dismiss-keyguard` を shell で
    発行してから再待ちする。既に focus 済みなら即座に返す（修復を発火しない）。
    timeout 時は environment 前提（window focus）の崩壊として証拠を採取し
    `markUnhealthy` する。
  - `ensureInteractiveUnlocked()`: **入口で health state を確認**した上で、
    `awaitResumedLauncher` 等の window が存在しない待ちの前に使う device-level 修復
    （wakeup / keyguard dismiss）。待ち自体は行わない。
  - `describeDeviceState()`: interactive / keyguard / focused window（`dumpsys window`
    の focused window 行）/ frontmost window package（`rootInActiveWindow?.packageName`）
    の 1 行 summary。
  - **run-level environment health state**（純粋な state machine クラス、process-static、
    helper object が所有）: 最初の `markUnhealthy(evidence)` で証拠を保持し、以後の
    入口確認は待機・修復なしで即座に、同じ証拠を参照する error を投げる。有効範囲は
    1 instrumentation invocation ＝ 1 process ＝ 1 lane job であるが、**参照するのは
    gated test クラスのみ**であり、同一 invocation 内の gated でないクラス（issue52
    lane の他 3 クラス）は収束保証の対象外で通常どおり実行される。
- gate 配線:
  - issue53 lane `OnboardingOrganizationProposalInstrumentationTest`:
    `TouchActivationGate.tapCenterOf`/`deliveredTap`、`deliveredTapOutside`、
    `sendKey`（DPAD）経路、および proposal show 直前。
  - issue52 lane `ManualOrganizationPreferencesInstrumentationTest`:
    `pressDownUntilFocused` と `KEYCODE_ENTER` 注入の前。window focus 観測下でも
    focus traversal が失敗した場合、failure メッセージに注入時の device/window state
    を含める（TS-AC-06）。
  - 配線対象はこの 2 クラス（gated test クラス）のみであり、他クラスへの共通入口
    （runner listener / test rule）は追加しない。
- 非注入経路の修復・診断・**分類**:
  - `awaitResumedLauncher`: 待ちの前に `ensureInteractiveUnlocked()`。timeout 時に
    環境を再観測（interactive / keyguard / frontmost window）し、environment 異常の
    観測が取れた場合のみ environment failure（診断＋`markUnhealthy`）。環境が正常なら
    local failure として従来どおり失敗する（診断は付加）。
  - `awaitAccessibilityTextBounds`: 対象 activity の window focus を gate で確認。
    timeout 時に環境を再観測し、frontmost window が異物または activity が focus を
    持たない場合のみ environment failure（診断＋`markUnhealthy`）。環境が正常なら
    node-not-found failure のまま（製品回帰の検出を保つ）。
- **決定的検証 seam**:
  - health state は Android 非依存の純粋クラスとし、同 package の新規 instrumentation
    test クラス（状態の遷移・証拠の単一性・入口確認の即時性を直接駆動）で検証する。
    状態 test クラスは自身の fresh な state instance を駆動し、process-static な
    singleton や injection hook に依存しない。
  - helper には、instrumentation runner 引数が明示指定された場合にのみ health state を
    unhealthy に固定する failure injection hook を設ける（CI lane では指定しない）。
    これにより、実際の配線（helper 入口確認 → 後続テスト即時失敗）を環境に依存せず
    実行できる。
  - 新規状態 test クラスを issue53 lane の class filter に追加する（1 行の workflow
    変更。lane 構成・job 分離・API 構成は不変）。
- 強制状態による実環境再現試行の結果（成否・再現した状態・CI シグネチャとの一致度）を
  plan.md に記録し、#304 の証拠として引き継ぐ（本 Issue の merge 条件ではない）。

## Non-goals

- production code（launcher / organizer module）の変更。
- lane 構成・job 分離・API 構成・emulator provisioning の変更
  （[ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md) の管轄）。
  ただし issue53 lane の class filter への状態 test クラス追加（1 行）は例外とする。
- api35 lane および issue #292 の修正への影響。
- **root cause（per-boot トリガー）の特定**。#304 に分離した。本 Issue は証拠採取の
  仕組みと強制状態試行の記録までを行い、試行の成否は本 Issue の完了条件ではない。
- TestProtocol 結合、Orchestrator 導入、Gradle/CI レベルの自動 retry の追加
  （rerun は運用者の判断のままとする）。
- `awaitResumedLauncher` の待機ロジック自体の変更（修復の前置き・timeout 時の分類と
  診断付加のみ）。

## Domain language

なし（実装語のみ。`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: 注入は対象windowのfocus観測後に行われる (TS-AC-01)

Given issue53 lane のテストが実行され、対象 window（launcher または compose host）が
window focus を保持している
When touch（`deliveredTap` / `deliveredTapOutside`）または DPAD（`sendKey`）の注入経路が
実行される
Then 各注入は、直前の focus 観測を前提として実行される
And focus を観測できていない間は注入を行わない（喪失を再試行しない）

### Scenario: 環境修復を試みてから諦める (TS-AC-02)

Given 対象 window が window focus を持たず、device が非 interactive または keyguard showing
である
When gate が実行される
Then gate は wakeup／keyguard dismiss を行い、focus 到達を bound 付きで再待ちする
And `awaitResumedLauncher` のように window がまだ存在しない待ちの前でも、
device-level 修復（`ensureInteractiveUnlocked`）が同じ方針で試みられる
And focus が到達した後は、テストは修復なしの場合と同一の手順で続行する

### Scenario: health 確認はすべての environment 操作の入口で行われる (TS-AC-03)

Given gated test クラス（helper を配線した `OnboardingOrganizationProposalInstrumentationTest`
または `ManualOrganizationPreferencesInstrumentationTest`）内のあるテストが既に
environment failure で `markUnhealthy` されている
When 同一 gated クラスの後続テストが `ensureInteractiveUnlocked` または
`ensureWindowFocused` を呼ぶ
Then helper は修復・待機を一切実行せず、入口で即座に失敗する
And 失敗メッセージは最初の failure が採取した environment 証拠を参照する
And gate 待機と注入 retry（3 attempt × delivery timeout）は gated クラス内で高々 1 回
だけ発生する
And 同一 invocation 内の gated でないクラスは health state を参照せず通常どおり実行される
（収束保証の対象外である）

### Scenario: markUnhealthy は前提崩壊の観測がある場合に限られる (TS-AC-04)

Given `awaitResumedLauncher` または `awaitAccessibilityTextBounds` が timeout した
When timeout 時に環境を再観測する
Then 非 interactive / keyguard locked / frontmost window が異物 / 対象 window が
focus を持たない、のいずれかの観測が取れた場合のみ environment failure として
証拠採取・`markUnhealthy` する
And 環境が正常な場合（例: activity が focus を持ち frontmost も正しいのに
organizer entry の text が無い）、health state は変化せず、従来型の
node-not-found / local failure として報告される（製品回帰の検出能力を保つ）
And `ensureWindowFocused` の timeout は、gate が守る environment 前提（window focus）
自体の崩壊であるため、environment failure として `markUnhealthy` する

### Scenario: health state は決定的に検証される (TS-AC-05)

Given health state の純粋 state machine と、runner 引数による failure injection hook
が実装されている
When 同 package の状態 test クラスが state machine を直接駆動する
Then 最初の `markUnhealthy` だけが証拠を保持し、以後の入口確認が待機なしで同じ証拠を
参照する error を投げることが決定的に検証される
And runner 引数と gated クラスの明示指定（`-e class`）による別 invocation 実行では、
実配線（helper 入口確認 → gated クラスの後続テストの即時失敗）が環境に依存せず
検証される
And 状態 test クラスは自身の fresh な state instance を駆動するため、injection hook や
他テストの health 状態に依存しない
And 実環境の強制状態（KEYCODE_SLEEP 等）による再現試行は #304 用の証拠として
記録され、本 state machine の検証条件ではない

### Scenario: issue52 の実鍵注入は gate を通り、失敗時の状態が残る (TS-AC-06)

Given issue52 lane の compose host が任意の window focus 状態にある
When `pressDownUntilFocused` または `KEYCODE_ENTER` が実鍵注入を行う
Then 注入前に gate（修復込み）を通る
And window focus 観測下で focus traversal がそれでも失敗した場合、その失敗メッセージに
注入時の device/window state が含まれる
And 本 Issue は issue52 を issue53 と同一原因とは主張しない（`hasWindowFocus()==true`
での Compose traversal 失敗の可能性は次回証拠で判別する）

### Scenario: 連続 green の確認 (TS-AC-07)

Given 本修正を含む PR の CI が実行される
Then 異なる workflow run を 3 回連続で実行し、各 run で issue53 lane と issue52 lane が
`run_attempt = 1` のまま green になる
And rerun で green になった run は連続 green の成立に数えない（実施した rerun は
head SHA とともに PR に記録する）

## Data and state

- テストのみの変更であり、読み書きする永続 data、schema、migration、backup/restore への
  影響はない。
- run-level environment health state は instrumentation process 内で閉じ、永続化しない。
  有効範囲は 1 instrumentation invocation ＝ 1 process であるが、**参照するのは gated
  test クラスのみ**である（issue52 lane の他 3 クラスは参照せず、収束保証の対象外）。
  issue52 と issue53 は別 job・別 emulator であり、state は混在しない。topology は
  現行（issue53 = 1 クラス、issue52 = 4 クラス）と修正後（issue53 に状態 test クラスを
  追加して 2 クラス、issue52 不変）を分けて plan.md に記録する。
- failure injection hook は runner 引数が明示指定された場合にのみ動作し、CI lane では
  指定しない。通常実行の挙動を変えない。
- gate の修復 shell（wakeup、keyguard dismiss）は lane の前提状態（interactive・
  unlocked）を本 Issue で初めて明示要求するものであり、既存テストが keyguard 状態に
  依存する前提は存在しない。
- 既存の setUp → tearDown の状態復元 pattern は変更しない。

## Permissions, privacy, and security

None。追加 permission・外部送信はない。gate は instrumentation が既に持つ UiAutomation
shell（`input`、`wm dismiss-keyguard`、`dumpsys window`）と framework API
（`PowerManager`、`KeyguardManager`）のみを使う。失敗メッセージに含める window 情報は
CI 上の component 名のみであり、個人情報を含まない。

## Accessibility and localization

None。production の accessibility 振る舞いは変更しない。テスト内の accessibility 走査は
gate によって frontmost window 前提が確認され、timeout 時の分類・診断が充実するだけで、
走査方法自体は不変である。環境正常時の node-not-found は製品回帰として
そのまま報告される。

## Acceptance criteria

- [ ] AC-1: 注入経路は対象 window focus を観測していない状態で注入せず、修復
      （wakeup / keyguard dismiss）を試みてから待つ（TS-AC-01、TS-AC-02）。
- [ ] AC-2: health 確認がすべての environment 操作（修復・focus 待ち）の入口で行われ、
      unhealthy な run の gated クラス内の後続テストは修復・待機を一切実行せず最初の
      証拠を参照して即座に失敗する。保証範囲は gated test クラスに限られ、決定的配線
      検証は gated クラスを明示指定した別 invocation（issue53:
      `OnboardingOrganizationProposalInstrumentationTest`、issue52:
      `ManualOrganizationPreferencesInstrumentationTest`）で行う（TS-AC-03、
      TS-AC-05）。
- [ ] AC-3: `markUnhealthy` の分類が検証される。環境正常下での accessibility
      node-not-found と `awaitResumedLauncher` timeout は health state を変化させず、
      環境異常の観測が取れた timeout のみが environment failure になる
      （TS-AC-04）。
- [ ] AC-4: health state state machine が、状態 test クラス（issue53 lane filter に
      追加）と runner 引数 injection により決定的に検証される。実環境の強制状態
      試行結果は #304 の証拠として plan.md に記録される（TS-AC-05）。
- [ ] AC-5: issue52 の実鍵注入が gate を通る。window focus 観測下での traversal 失敗時
      メッセージに device/window state が含まれる。spec/plan に issue52 と issue53 の
      同一原因の主張が無い（TS-AC-06）。
- [ ] AC-6: 異なる workflow run を 3 回連続で実行し、各 run で issue53・issue52 両
      lane が `run_attempt = 1` のまま green。rerun で green になった run は数えない
      （TS-AC-07）。
- [ ] AC-7: 変更範囲が `tests/organizer-instrumentation` と issue53 lane の class
      filter 1 行に限られること、および `./gradlew spotlessCheck` が green であること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 修正 head のローカル api36 lane 実行（通常状態）の結果と、注入呼び出し箇所の gate 配線 review |
| AC-2 | 状態 test クラスの実行結果 + runner 引数 injection と gated クラス明示指定（`-e class`）による別 invocation 実行 log（issue53 / issue52 それぞれの gated クラスで、後続テストが待機なしで失敗する証跡）。plan.md Verification に記録 |
| AC-3 | 状態 test クラスの分類ケース結果 + 強制状態実行で得られる分類メッセージの実物。plan.md に記録 |
| AC-4 | 状態 test クラスの CI 実行結果（issue53 lane）+ injection 付き lane 実行 log。強制状態試行の記録は #304 参照用 |
| AC-5 | issue52 lane（本番 filter の 4 クラス実行）のローカル実行結果 + 失敗時メッセージの診断内容確認 |
| AC-6 | CI run link（連続 3 workflow run、各 `run_attempt = 1`、head SHA 付き）。PR 本文に記録 |
| AC-7 | `./gradlew spotlessCheck` 実行結果と `git diff --stat` の範囲確認。PR に記録 |

## Open questions

なし（blocking なもの）。

- per-boot トリガーの特定は #304 に分離済み（2026-09-12 の Spec/Plan review で決定、
  Issue #300 の終了条件を更新済み）。本 Issue 内の強制状態試行は #304 の入力となる
  証拠収集であり、その成否は本 Issue の完了条件ではない。
- gate の修復で解消しない occluder（例: 特定 system window）が出た場合の対処は、
  gate 証拠と #304 の結論に基づいて別途判断する。
