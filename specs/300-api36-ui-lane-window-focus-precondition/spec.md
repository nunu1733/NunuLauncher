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

# api36 UI lanes: 実入力注入テストは対象windowのfocus到達を前提とし、focus欠落ではenvironment証拠付きで即座に失敗する

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
3. **同種の失敗が touch 以外の実入力にも現れる**。同一 run で DPAD traversal 待ち
   （`No proposal action received input focus after DPAD traversal; launcherWindowFocus=false`）、
   frontmost window 前提の accessibility 走査（`organizer entry ... was not found in the
   accessibility tree`）、さらに `awaitResumedLauncher` のタイムアウト
   （`LawnchairLauncher did not reach an attached, laid-out RESUMED state after HOME launch`）
   が続発した。issue52 lane の失敗（実 DPAD 注入後の `(Focused = 'true')` assert 失敗）
   も同一クラス（実注入が focus 遷移を起こせない）である。
4. **`injectInputEvent` の真偽値は delivery を保証しない**。`deliveredTap` は
   injection 戻り値 true を確認した上で 3 attempt × 1.5s 待つが、window focus 欠落下では
   再試行が同じ喪失状態に再投入されるだけで、run の予算を連鎖失敗に費やす。
5. **環境は per-boot で決まる**。emulator は runner により boot 直後に
   `input keyevent 82`（unlock 相当）を 1 回受けるのみで、`screen_off_timeout` は
   無限大に設定されている。rerun（別 boot）では green であり、head・diff 非依存である。

つまり、burst の正体は「対象 window が window focus を得られない per-boot 環境状態の下で、
実入力注入が静かに喪失し続け、view focus／可視性前提の既存待ちがそれを検出できず、
依存テストが順に落ちる」ことである。**どの状態（keyguard、非インタラクティブ、外部
window、API 36.1 固有の focus 遷移）が focus を奪っているかは未確定であり、CI には
failure 時の window 状態証拠が残らない。**この確定が本 Issue の成果物の一つである。

## Outcome

api36 UI lane の touch/keyboard 注入テストは、対象 window が window focus を保持している
ことを観測した後にのみ注入する。環境が interactive・keyguard 解除・focus 到達に失敗した
場合は、修復を試みた後、失敗メッセージ単体で「何が focus を持っていたか／device がどんな
状態だったか」を特定できる証拠を添えて即座に 1 回だけ失敗する。per-boot 環境問題は
11 連鎖の injection timeout ではなく、単一の明示的・診断可能な失敗として現れ、
`final-status` gate が原因不明の burst ではなく原因特定可能な 1 失敗を報告する。

## Scope

- `tests/organizer-instrumentation` 内の 2 テストクラスに、注入前の
  **window focus precondition gate** を追加する:
  - issue53 lane `OnboardingOrganizationProposalInstrumentationTest`:
    `TouchActivationGate.tapCenterOf`/`deliveredTap`、`deliveredTapOutside`、
    `sendKey`（DPAD）経路。各注入の前に gate を通し、focus が観測されたときだけ注入する。
  - issue52 lane `ManualOrganizationPreferencesInstrumentationTest`:
    `pressDownUntilFocused` と `KEYCODE_ENTER` 注入の前に同じ gate を通す
    （compose host activity の window を対象にする）。
- gate は、device が非 interactive・keyguard showing の場合に instrumentation shell
  （wakeup、keyguard dismiss）による修復を行い、bound 付きで focus 到達を待つ。
- gate が deadline までに focus を観測できない場合、テストを単一の明示的メッセージで
  失敗させる。メッセージには少なくとも: device の interactive 状態、keyguard 状態、
  focused window の識別、frontmost window の package、既存の input environment dump
  （`describeInputEnvironment`）を含む。
- **メカニズム確定の証拠作り**: ローカル api36 emulator で候補状態（非 interactive /
  keyguard 等）を強制し、現行テストが CI と同種の喪失シグネチャを再現すること、および
  修正後テストが修復または fail-fast することを、再現・消滅ペアとして plan.md に記録する。

## Non-goals

- production code（launcher / organizer module）の変更。振る舞いの前提は正しく、
  変更対象はテストの環境前提の取り扱いのみである。
- lane 構成・class filter・CI workflow・emulator provisioning の変更
  （lane 分割、API 統合は [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)
  の管轄であり、本緩和後も burst が継続する証拠が出た場合にのみ再評価する）。
- api35 lane および issue #292 の修正への影響。
- TestProtocol 結合、Orchestrator 導入、Gradle/CI レベルの自動 retry の追加
  （メカニズムを隠すだけの緩和はしない。rerun は運用者の判断のままとする）。
- `awaitResumedLauncher` の失敗モード自体の変更（既に明示的メッセージを持つ。
  burst の下流症状であること以外は本 Issue で扱わない）。

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
Then gate は instrumentation shell による wakeup／keyguard dismiss を行い、focus 到達を
bound 付きで再待ちする
And focus が到達した後は、テストは修復なしの場合と同一の手順で続行する

### Scenario: focus が到達しない環境ではenvironment証拠付きで即座に失敗する (TS-AC-03)

Given gate が deadline までに対象 window の focus を観測できない
When gated 注入経路に到達したテストが実行される
Then テストは gate の単一の明示的メッセージで失敗する
And メッセージには device interactive 状態、keyguard 状態、focused window の識別、
frontmost window package、input environment dump が含まれる
And 後続テストは injection retry の消耗（3 attempt × delivery timeout）を繰り返さず、
run は gate の 1 失敗として停留する

### Scenario: issue52 lane の DPAD traversal も同一の gate を通る (TS-AC-04)

Given issue52 lane の compose host が window focus を保持していない
When `pressDownUntilFocused` または `KEYCODE_ENTER` 注入が実行される
Then 同一の focus precondition が鍵注入の前に適用される
And 失敗する場合、そのシグネチャは node semantics の assert 不一致ではなく
gate の environment 証拠付き失敗である

### Scenario: 喪失シグネチャの再現と消滅（メカニズム実証） (TS-AC-05)

Given ローカル api36 emulator で、lane 実行前に非 interactive／keyguard 等の候補状態を
強制した
When 現行（修正前）テストを同一手順で実行する
Then CI と同種のシグネチャ（注入喪失、`launcherWindowFocus=false`）で失敗する
And 修正後テストは同一準備・同一手順で、修復による続行または gate での fail-fast の
いずれかになる

### Scenario: 再発時のメカニズム確定 (TS-AC-06)

Given gate が CI で失敗した
Then 失敗メッセージ単体で、focus を保持していた window と device 状態が特定できる
And 特定された状態は Issue #300 に記録され、メカニズム確定の終了条件を満たす

### Scenario: 連続 green の確認 (TS-AC-07)

Given 本修正を含む head で CI が実行される
Then issue53 lane と issue52 lane が連続する複数 CI run（同一 job の連続 attempt を含む）
で rerun なしに green になる
And 結果 run への link が PR に記録される

## Data and state

- テストのみの変更であり、読み書きする永続 data、schema、migration、backup/restore への
  影響はない。
- gate の修復 shell（wakeup、keyguard dismiss）は lane の前提状態（interactive・unlocked）
  を本 Issue で初めて明示要求するものであり、既存テストが keyguard 状態に依存する前提は
  存在しない。
- 既存の setUp → tearDown の状態復元 pattern は変更しない。

## Permissions, privacy, and security

None。追加 permission・外部送信はない。gate は instrumentation が既に持つ UiAutomation
shell（`input`、`wm dismiss-keyguard`、`dumpsys window`）と framework API
（`PowerManager`、`KeyguardManager`）のみを使う。失敗メッセージに含める window 情報は
CI 上の component 名のみであり、個人情報を含まない。

## Accessibility and localization

None。production の accessibility 振る舞いは変更しない。テスト内の accessibility 走査は
frontmost window 前提が gate によって満たされるだけで、走査方法自体は不変である。

## Acceptance criteria

- [ ] AC-1: 修正後の注入経路は、対象 window focus を観測していない状態で注入を実行しない
      （TS-AC-01、TS-AC-02）。
- [ ] AC-2: focus 到達不能な強制状態で、修正後テストは gate の証拠付きメッセージで
      1 回だけ失敗し、injection retry の連鎖が発生しない（TS-AC-03）。
- [ ] AC-3: issue52 lane が同一 gate を通る。ローカル全クラス green + CI green
      （TS-AC-04）。
- [ ] AC-4: 再現・消滅ペアが記録される。強制状態下で現行テストが CI 同種シグネチャで
      失敗し、修正後テストが修復または fail-fast する（TS-AC-05）。
- [ ] AC-5: gate 失敗メッセージが環境状態（interactive / keyguard / focused window /
      frontmost window / input dump）を単体で特定できる（TS-AC-06）。
- [ ] AC-6: issue53・issue52 両 lane が連続する複数 CI run で green（TS-AC-07）。
- [ ] AC-7: production file の diff が 0 であること、および `./gradlew spotlessCheck`
      が green であること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 修正 head のローカル api36 lane 実行（強制状態含む）の結果と、注入呼び出し箇所のコード上の gate 配線 |
| AC-2 | 強制状態でのローカル実行 log（gate 1 失敗で停留する証跡）。plan.md Verification に記録 |
| AC-3 | ローカル issue52 全クラス実行結果 + CI run link |
| AC-4 | 修正前/修正後の同一手順ローカル実行結果ペア。plan.md Current evidence/Verification に記録 |
| AC-5 | 強制状態で出力される gate メッセージの実物（log 抜粋） |
| AC-6 | CI run link（同一 job の連続 attempt または連続 run）。PR 本文に記録 |
| AC-7 | `./gradlew spotlessCheck` 実行結果と `git diff --stat` の範囲確認。PR に記録 |

## Open questions

なし（blocking なもの）。

「どの per-boot 状態が focus を奪うか」は未確定だが、本 Issue 内の成果物
（TS-AC-05 / TS-AC-06）であり、実装開始の blocker ではない。緩和は確認済みのメカニズム
水準（window focus 前提）に鍵を置いており、具体的な occluder に依存しない。occluder が
修復不能な種別（例: 特定 system window）だった場合は、gate の証拠により後続対応が
1 つの小さな対処（lane provisioning での排除等）に限定される。
