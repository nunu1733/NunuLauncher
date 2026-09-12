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

# api36 UI lanes: 実入力注入は対象windowのfocus観測を前提とし、environment gate の単一証拠採取と即時失敗で burst の待機連鎖を収束させる

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
   判別に必要な状態採取を本 Issue で整備する（TS-AC-05）。
7. **どの per-boot 状態が window focus を奪うかは未確定であり、CI には failure 時の
   window 状態証拠が残らない**。root cause の特定は本 Issue から分離し
   [Issue #304](https://github.com/nunu1733/NunuLauncher/issues/304) で追跡する
   （2026-09-12 の Spec/Plan review での決定。Issue #300 終了条件の更新を参照）。

Spec review（#300 コメント、2026-09-12）で確定した設計上の制約:

- **per-test fail-fast だけでは run を収束させられない**。CI はテストクラス全体を
  1 回の instrumentation 実行で流すため、focus 異常が持続すれば後続テストが各々
  gate 待機で失敗し、最悪 20 × 15秒 の失敗連鎖になる。run を収束させるには
  run-level の environment health state が必要である（TS-AC-03）。
- **burst の症状の一部である非注入系 failure も扱う**。`awaitAccessibilityTextBounds`
  は `rootInActiveWindow` を直接 poll し、`awaitResumedLauncher` は gate より前に
  実行されるため、両者に修復・診断を適用しない限り burst の症状が残る（TS-AC-04）。

## Outcome

api36 UI lane では、touch/keyboard 注入は対象 window が window focus を保持している
ことを観測した後にのみ行われる。environment gate が focus を観測できない場合、
修復を試みた上で、**最初の gate 失敗のみ** environment 証拠を採取して明示的に失敗し、
同一 run の残りテストは gate 入口で待機せず即座に失敗してその証拠を参照する。
per-boot 環境問題は「繰り返し待機する失敗連鎖」ではなく「1 つの証拠採取＋即時失敗の
連鎖」として現れ、gate 証拠は root-cause 確定 Issue（#304）の一次資料になる。
非注入系の待ち（`awaitResumedLauncher`、accessibility 走査）も修復・診断の対象に入り、
environment 異常時の全 failure が同一の証拠から説明できる。

## Scope

- `tests/organizer-instrumentation` 内に test 支援実体を 1 つ追加する:
  - `ensureWindowFocused(activity, deadline)`: 対象 activity の window focus を
    bound 付きで待つ。非 interactive（`PowerManager.isInteractive`）なら
    `input keyevent KEYCODE_WAKEUP`、keyguard showing（`KeyguardManager.isKeyguardLocked`）
    なら `wm dismiss-keyguard` を shell で発行してから再待ちする。既に focus 済みなら
    即座に返す（修復を発火しない）。
  - `ensureInteractiveUnlocked()`: `awaitResumedLauncher` 等の window が存在しない
    待ちの前に使う device-level 修復（wakeup / keyguard dismiss）。待ち自体は行わない。
  - `describeDeviceState()`: interactive / keyguard / focused window（`dumpsys window`
    の focused window 行）/ frontmost window package（`rootInActiveWindow?.packageName`）
    の 1 行 summary。
  - **run-level environment health state**: gate 系 failure（注入 gate、
    `awaitResumedLauncher` timeout、accessibility 走査 timeout）の最初の 1 回で証拠を
    採取・保持し、以後の gate 入口は待機せず即座に失敗してその証拠を参照する。
- gate 配線:
  - issue53 lane `OnboardingOrganizationProposalInstrumentationTest`:
    `TouchActivationGate.tapCenterOf`/`deliveredTap`、`deliveredTapOutside`、
    `sendKey`（DPAD）経路、および proposal show 直前。
  - issue52 lane `ManualOrganizationPreferencesInstrumentationTest`:
    `pressDownUntilFocused` と `KEYCODE_ENTER` 注入の前。window focus 観測下でも
    focus traversal が失敗した場合、failure メッセージに注入時の device/window state
    を含める（TS-AC-05）。
- 非注入経路の修復・診断:
  - `awaitResumedLauncher`: 待ちの前に `ensureInteractiveUnlocked()`、timeout 時の
    メッセージに `describeDeviceState()` を追加。
  - `awaitAccessibilityTextBounds`: 対象 activity の window focus を gate で確認し、
    timeout 時のメッセージに frontmost window package と device state を追加。
- 強制状態による再現試行の結果（成否・再現した状態・CI シグネチャとの一致度）を
  plan.md に記録し、#304 の証拠として引き継ぐ。

## Non-goals

- production code（launcher / organizer module）の変更。
- lane 構成・class filter・CI workflow・emulator provisioning の変更
  （lane 分割、API 統合は [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)
  の管轄であり、本緩和後も burst が継続する証拠が出た場合にのみ再評価する）。
- api35 lane および issue #292 の修正への影響。
- **root cause（per-boot トリガー）の特定**。#304 に分離した。本 Issue は証拠採取の
  仕組みと強制状態試行の記録までを行い、試行の成否は本 Issue の完了条件ではない。
- TestProtocol 結合、Orchestrator 導入、Gradle/CI レベルの自動 retry の追加
  （rerun は運用者の判断のままとする）。
- `awaitResumedLauncher` の待機ロジック自体の変更（修復の前置きと診断付加のみ）。

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

### Scenario: environment gate の最初の失敗が run の証拠採取を担う (TS-AC-03)

Given gate が deadline までに対象 window の focus を観測できず、environment 異常が
持続している
When run 内で最初に gate に到達したテストが実行される
Then そのテストだけが待機・修復を行い、environment 証拠（interactive / keyguard /
focused window / frontmost window / input dump）を 1 回採取して単一の明示的メッセージで
失敗する
And 同一 run の後続テストは gate 入口で待機せず即座に失敗し、最初の証拠を参照する
And gate 待機と注入 retry（3 attempt × delivery timeout）は run 全体で高々 1 回だけ
発生する

### Scenario: 非注入経路も environment 修復・診断を通る (TS-AC-04)

Given launcher / PreferenceActivity への遷移待ちまたは accessibility 走査が
environment 異常下で実行される
When `awaitResumedLauncher` または `awaitAccessibilityTextBounds` が待機する
Then 前者は待機前に device-level 修復を試み、timeout 時のメッセージに device state を
含む
And 後者は対象 activity の window focus を gate で確認し、timeout 時のメッセージに
frontmost window package と device state を含む
And これらの失敗も run-level health state を設定し、後続テストの即時失敗の根拠になる

### Scenario: issue52 の実鍵注入は gate を通り、失敗時の状態が残る (TS-AC-05)

Given issue52 lane の compose host が任意の window focus 状態にある
When `pressDownUntilFocused` または `KEYCODE_ENTER` が実鍵注入を行う
Then 注入前に gate（修復込み）を通る
And window focus 観測下で focus traversal がそれでも失敗した場合、その失敗メッセージに
注入時の device/window state が含まれる
And 本 Issue は issue52 を issue53 と同一原因とは主張しない（`hasWindowFocus()==true`
での Compose traversal 失敗の可能性は次回証拠で判別する）

### Scenario: 強制状態での再現試行が #304 の証拠になる (TS-AC-06)

Given ローカル api36 emulator で、lane 実行前に非 interactive／keyguard 等の候補状態を
強制した
When 修正前・修正後のテストを同一手順で実行する
Then 再現の成否、再現した状態、CI シグネチャ（`launcherWindowFocus=false`＋注入喪失＋
activity RESUMED＋view focus 成立）との一致度が plan.md に記録される
And 再現できた状態については、修正後テストが修復または gate fail-fast することを
同一手順で確認する
And 記録は #304 から参照される

### Scenario: 連続 green の確認 (TS-AC-07)

Given 本修正を含む head で CI が実行される
Then issue53 lane と issue52 lane が連続する複数 CI run（同一 job の連続 attempt を含む）
で rerun なしに green になる
And 結果 run への link が PR に記録される

## Data and state

- テストのみの変更であり、読み書きする永続 data、schema、migration、backup/restore への
  影響はない。
- run-level environment health state は instrumentation process 内で閉じ、永続化しない。
  lane は 1 クラス＝1 process で実行されるため、health state の有効範囲は 1 lane run と
  一致する。
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
gate によって frontmost window 前提が確認され、timeout 時の診断が充実するだけで、
走査方法自体は不変である。

## Acceptance criteria

- [ ] AC-1: 注入経路は対象 window focus を観測していない状態で注入せず、修復
      （wakeup / keyguard dismiss）を試みてから待つ（TS-AC-01、TS-AC-02）。
- [ ] AC-2: environment 異常が持続する run で、gate 待機・修復・注入 retry の連鎖は
      run 全体で高々 1 回であり、後続テストは待機せず最初の証拠を参照して即座に失敗する
      （TS-AC-03）。
- [ ] AC-3: `awaitResumedLauncher` が修復前置き＋timeout 時診断を、
      `awaitAccessibilityTextBounds` が gate＋timeout 時診断（frontmost window /
      device state）を通る（TS-AC-04）。
- [ ] AC-4: issue52 の実鍵注入が gate を通る。window focus 観測下での traversal 失敗時
      メッセージに device/window state が含まれる。spec/plan に issue52 と issue53 の
      同一原因の主張が無い（TS-AC-05）。
- [ ] AC-5: 強制状態再現試行の結果（成否・状態・CI シグネチャ一致度）が plan.md に
      記録され、再現できた状態では修正後テストの修復/fail-fast が同一手順で確認される
      （TS-AC-06）。
- [ ] AC-6: issue53・issue52 両 lane が連続する複数 CI run で green（TS-AC-07）。
- [ ] AC-7: production file の diff が 0 であること、および `./gradlew spotlessCheck`
      が green であること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 修正 head のローカル api36 lane 実行（通常状態）の結果と、注入呼び出し箇所の gate 配線 review |
| AC-2 | 強制状態でのローカル実行 log（証拠採取 1 回、後続即時失敗の証跡）。plan.md Verification に記録 |
| AC-3 | 強制状態でのローカル実行 log（`awaitResumedLauncher` / accessibility 経路の診断メッセージ実物） |
| AC-4 | issue52 全クラスのローカル実行結果 + 失敗時メッセージの診断内容確認 |
| AC-5 | 強制状態での修正前/修正後実行結果ペアと CI シグネチャ一致度の評価。plan.md に記録し #304 から参照 |
| AC-6 | CI run link（同一 job の連続 attempt または連続 run）。PR 本文に記録 |
| AC-7 | `./gradlew spotlessCheck` 実行結果と `git diff --stat` の範囲確認。PR に記録 |

## Open questions

なし（blocking なもの）。

- per-boot トリガーの特定は #304 に分離済み（2026-09-12 の Spec/Plan review で決定、
  Issue #300 の終了条件を更新済み）。本 Issue 内の強制状態試行は #304 の入力となる
  証拠収集であり、その成否は本 Issue の完了条件ではない。
- gate の修復で解消しない occluder（例: 特定 system window）が出た場合の対処は、
  gate 証拠と #304 の結論に基づいて別途判断する。
