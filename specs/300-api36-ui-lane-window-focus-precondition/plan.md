# Implementation Plan: api36 UI lanes における実入力注入の window focus precondition、run-level environment health state、および環境診断

> Issue: #300
> Spec: [spec.md](./spec.md)
> Status: draft（spec 承認後に実装開始）

## Current evidence

### 確認済み（CI log 実測・code path）

- **CI 失敗 3 run、rerun green、head 非依存**:
  - [run 34625444030](https://github.com/nunu1733/NunuLauncher/actions/runs/34625444030)
    attempt 1（issue53 lane。issue #292 調査時に「emulator 全体の不調」として記録）
  - [run 34674138315](https://github.com/nunu1733/NunuLauncher/actions/runs/34674138315)
    attempt 1、job `organizer-instrumentation-issue52-tests`（id 103500845521）:
    `changeListTraversalReachesExpandAndReviewActions` が
    `AssertionError: Failed to assert the following: (Focused = 'true')` で 1 失敗
    （36/57 green の途中、run 全体では 56/57 green）
  - [run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)
    attempt 1、job `organizer-instrumentation-issue53-tests`（id 103509755539）:
    20 test 中 11 失敗 = touch 注入喪失 8
    （すべて `events=[]`・`launcherWindowFocus=false`・`proposalOpen=true`・
    `targetShown=true`）＋ DPAD traversal 喪失 1（`launcherWindowFocus=false`）＋
    frontmost window 前提の accessibility 走査失敗 1 ＋ `awaitResumedLauncher`
    タイムアウト 1。同 head の api35 lane は green。
- **注入 site の全容**:
  - `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
    - `TouchActivationGate.tapCenterOf`（:1298）— `uiAutomation.injectInputEvent`
      による DOWN/UP。**burst 失敗の直接 source**
    - `TouchActivationGate.deliveredTap`（:1333）— 3 attempt × 1.5s delivery 待ち、
      失敗メッセージ（:1345）に `describeInputEnvironment`（:935、`launcherWindowFocus`
      を含む）を添えている
    - `TouchActivationGate.deliveredTapOutside`（:1367）— hint 外 touch。同様の
      3 attempt loop（失敗メッセージに環境 dump 無し）
    - `sendKey`（:1184）— `sendKeyDownUpSync` による DPAD（`awaitAnyInputFocus`
      :915 と対で使用。**DPAD 喪失失敗の source**）
    - `awaitAccessibilityTextBounds`（:568）— `uiAutomation.rootInActiveWindow`
      （frontmost window 前提。**accessibility 走査失敗の source**）
    - `awaitResumedLauncher`（:1126）— **RESUMED 待ち timeout 失敗の source**。
      proposal 系注入 site より前に実行される
  - `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
    - `pressDownUntilFocused`（:1179）— `sendKeyDownUpSync(KEYCODE_DPAD_DOWN)` の
      実注入 loop。**issue52 lane 失敗の source**
    - :1130 — `sendKeyDownUpSync(KEYCODE_ENTER)` の実注入
- **view focus と window focus の分離が既存待ちを素通しさせる**: 失敗時の
  `activityFocus` dump（`android.widget.TextView{... .F...... ...}`）は
  `isFocused=true` を示す。既存の待ち（`awaitVisibleProposalActions`、`awaitInputFocus`）
  は view 可視性・view focus を見るため通過し、失敗は注入 delivery だけに現れる。
  つまり「既存待ちの追加」ではなく「window focus の観測」が要である。
- **retry は喪失状態への再投入にすぎない**: `injectInputEvent` の真偽値は system への
  受け付けを示し、宛先 window への delivery を保証しない（`downInjected=true` であり
  ながら `events=[]` の実測）。window focus 欠落下では 3 attempt とも同じ経路へ落ちる。
- **環境は runner boot 時に決まる**: emulator runner は boot 完了後
  `input keyevent 82`（unlock 相当）を 1 回送るだけである
  （run 34677444335 job log :628-633 実測。06:13:11 に keyevent、直後に animation 無効化）。
  `screen_off_timeout` は boot property で無限大（:233）。
  failure 時の window／keyguard 状態の証拠は CI 上に残らない
  （artifact は test report のみ）。
- **CI はクラス全体を 1 回の instrumentation 実行で流す**
  （`-Pandroid.testInstrumentationRunnerArguments.class=...`、ci.yml）。
  focus 異常が持続する場合、per-test の gate 失敗が繰り返され、per-test fail-fast だけでは
  run の失敗待機連鎖（最悪 20 × gate deadline）が残る。run 収束には run-level の
  environment health state が必要である（2026-09-12 Spec/Plan review 指摘 1）。

### 推測と未確定（分離後の扱い）

- **issue52 lane の失敗は仮説である**: 実鍵注入後に Compose focus traversal が失敗した
  ことまでしか記録がなく、同時点の window focus は不明である
  （review 指摘 4）。`hasWindowFocus()==true` のまま traversal だけ失敗する可能性を
  残すため、同一原因とは主張せず、gate と失敗時状態採取（TS-AC-05）で次回判別可能にする。
- **per-boot トリガー（keyguard 解消不成立 / 非interactive / 外部 window /
  API 36.1 の focus 遷移）は未確定**であり、root cause 特定は
  [Issue #304](https://github.com/nunu1733/NunuLauncher/issues/304) に分離した
  （review 指摘 2。Issue #300 終了条件を更新済み）。本 plan の強制状態試行は
  #304 の入力となる証拠収集であり、その成否は本 Issue の完了条件ではない。

## Design

### Modules and interfaces

- production module・interface には触れない。変更は `tests/organizer-instrumentation`
  内に限る。
- 新規の test 支援実体を 1 つだけ追加する:
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/` 配下に
  `InjectedInputEnvironment`（仮称、file 名は PR で確定）を配置し、2 クラスから使う。
  - `ensureWindowFocused(activity, deadline)`: 対象 activity の window focus を
    bound 付きで待つ。非 interactive（`PowerManager.isInteractive`）なら
    `input keyevent KEYCODE_WAKEUP`、keyguard showing（`KeyguardManager.isKeyguardLocked`）
    なら `wm dismiss-keyguard` を shell（既存 `runShellCommand` と同経路）で発行して
    から再待ちする。既に focus 済みなら即座に返す（修復を発火しない）。
  - `ensureInteractiveUnlocked()`: `awaitResumedLauncher` のように対象 window がまだ
    存在しない待ちの前に使う device-level 修復。待ち自体は行わない。
  - `describeDeviceState()`: interactive / keyguard / focused window（`dumpsys window`
    の focused window 行）/ frontmost window package（`rootInActiveWindow?.packageName`）
    の 1 行 summary。
  - **run-level environment health state**（process-static、helper object が所有）:
    - `markUnhealthy(evidence)`: gate 系 failure（注入 gate timeout、
      `awaitResumedLauncher` timeout、accessibility 走査 timeout）の最初の 1 回で
      environment 証拠を保持する。
    - `checkAtGateEntry()`: gate 入口で呼ばれ、unhealthy なら待機せず即座に
      error を投げ、保持済みの最初の証拠を参照させる。
    - 有効範囲は 1 instrumentation process ＝ 1 lane run である（CI は 1 クラスを
      1 実行で流す）。process 内 static で永続化しない。
  - いずれも実行を 1 箇所に集めた具象関数であり、interface・adapter は追加しない
    （AGENTS 規約: 必要になるまで実体を増やさない）。
- 2 つのテストクラスは同じ package 配下でこの関数を直接呼ぶ。

### Data flow（修正後の注入経路）

```text
issue53 lane（各テスト）:
ensureInteractiveUnlocked()                      // NEW: awaitResumedLauncher 前
startLauncher → awaitResumedLauncher
  → timeout 時: describeDeviceState() を添えて失敗 + markUnhealthy   // NEW
  → gate.checkAtGateEntry()                      // NEW: unhealthy なら即時失敗
  → gate.ensureWindowFocused(launcher)           // NEW: show 前に 1 回（修復込み）
      → timeout 時: 証拠を 1 回採取（describeDeviceState + describeInputEnvironment）
        markUnhealthy → 単一の明示的失敗
  → proposal.show() → awaitVisibleProposalActions
  → 各注入（deliveredTap / deliveredTapOutside / sendKey）
      gate.checkAtGateEntry() → gate.ensureWindowFocused(...)  // NEW
      → injectInputEvent / sendKeyDownUpSync
      → delivery 観測（既存の touchLog / focus poll を不変）

issue53 lane（accessibility 走査）:
PreferenceActivity 遷移 → gate.ensureWindowFocused(activity)      // NEW
  → rootInActiveWindow poll（既存）
  → timeout 時: frontmost window package + device state を添えて失敗
    + markUnhealthy                                               // NEW

issue52 lane（各テスト）:
composeRule.setContent → awaitPreview
  → gate.checkAtGateEntry() → gate.ensureWindowFocused(host)      // NEW
  → pressDownUntilFocused / KEYCODE_ENTER
      → 失敗時（focus 観測下でも traversal 失敗）: 注入時の
        device/window state を failure メッセージへ付加            // NEW
```

- `describeInputEnvironment`（既存、issue53 側）は `describeDeviceState()` の出力を
  連結する形で拡張し、diagnostic の書式を 1 箇所に集める。
- issue52 側の host activity は `ActivityLifecycleMonitorRegistry`
  （`Stage.RESUMED` → 単一 activity、既存 `awaitResumedPreferenceActivity` と同手法）
  で取得し、`activity.window.decorView.hasWindowFocus` を観測する。
- deadline 定数は gate 用に 1 つ追加する（提案: 15 秒。CI の遅い runner での
  focus 移行待ちと、fail-fast までの時間の両立。PR で根拠を記録）。
  `MAX_INJECTION_ATTEMPTS_PER_TAP=3`、`DELIVERY_TIMEOUT_MILLIS=1500` は不変。
- gate 失敗メッセージには接頭辞を付け、CI log 上で injection 喪失系と区別できるように
  する（例: `input environment never reached a focused window: ...`）。

### Alternatives rejected

- **per-test fail-fast のみ（review 指摘 1 の代替案）**: focus 異常が持続する場合、
  後続テストが各々 gate 待機で失敗し（最悪 20 × 15秒）、「緩和」がむしろ待機連鎖の
  延長になる。run-level health state を採用し、待機連鎖を run 全体で高々 1 回に制限する。
- **JUnit runner / @ClassRule による run abort**: テスト毎の失敗所有が不明瞭になり、
  report 上の失敗数と原因の対応が崩れる。health state は「各テストが即座に・同じ証拠を
  参照して失敗する」形で run を収束させる。
- **CI workflow 側でのみ unlock/wakeup を追加する**: CI boot は救えるがローカル再現・
  per-test 観測ができず、workflow-only 変更は全 gate 実行を強制する品質規約の対象に
  なる。test 側 gate が同じ効果を CI/ローカル双方で持ち、証拠も残す。
- **injection retry 数・timeout の増加**: 喪失状態への再投入を延長するだけで、burst の
  時間コストが増える。メカニズムを隠す。
- **focus 欠落時に `AssumptionViolatedException` で skip**: required evidence が
  skipped に化け、gate として弱まる。環境異常は明示的失敗として報告する。
- **root cause 特定を本 Issue 内で必須にする**: 緩和が機能すれば再発が観測できなくなり、
  「実発生の証拠」が恒久的に得られない。過去 run の window 状態証拠も存在しないため、
  完了条件として常に不成立になり得る。review 指摘 2 の選択肢に従い #304 に分離し、
  本 Issue は gate 診断と強制状態試行の記録を提供する。
- **lane 分割・API 統合などの portfolio 変更**: [ci-test-portfolio.md]
  (../../docs/engineering/ci-test-portfolio.md) の管轄であり、必要な証拠の種類が
  違う。本緩和後も burst が継続する場合の再評価対象として Non-goals に置く。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/.../ui/` 新規支援 file | `ensureWindowFocused` / `ensureInteractiveUnlocked` / `describeDeviceState` / run-level health state（修復 shell 込み） | 2 クラスが同一 package で共有する test-only 手続き。run 収束の状態は 1 箇所で所有する |
| `OnboardingOrganizationProposalInstrumentationTest.kt` | `TouchActivationGate.show()` 直後と各注入経路（`deliveredTap`、`deliveredTapOutside`、`sendKey`）への gate 配線、`describeInputEnvironment` の拡張、`awaitResumedLauncher` の修復前置き＋timeout 診断、`awaitAccessibilityTextBounds` の gate＋timeout 診断 | burst の直接 source がこのクラスの注入 loop と待ち。非注入系 failure も同一 burst の症状である（review 指摘 3） |
| `ManualOrganizationPreferencesInstrumentationTest.kt` | `pressDownUntilFocused` と `KEYCODE_ENTER` 注入前の gate 配線、traversal 失敗時の device/window state 付加 | issue52 lane の実鍵注入が焦点依存であるため。同一原因の主張はせず、次回判別可能な証拠を残す（review 指摘 4） |

production source、`src/com/android/launcher3/**`、`.github/workflows/**` は変更しない。

## Migration and recovery

- 対象外（テストのみの変更）。schema/rule migration、rollback、backup/restore への
  影響なし。

## Verification

再現・消滅ペアを原則とする（#292 と同様。フレイクは実行環境の競合であり、JVM テストに
落とせないため、決定的な失敗注入＝環境状態の強制で代替する。理由を PR に記載する）。
強制状態試行は #304 へ引き継ぐ証拠収集であり、その成否は本 Issue の merge 条件ではない
（spec Open questions および Issue #300 終了条件の更新を参照）。

| Acceptance criterion | Requirement | Automated/manual evidence | Command or environment |
|---|---|---|---|
| AC-1 注入は focus 観測後のみ実行される | TS-AC-01, TS-AC-02 | 実装後、強制状態なしの通常 lane 実行で全 green（gate が通常 path を壊さないこと）＋ gate 配線の code review | api36 emulator（google_apis x86_64、`docs/assessment/evidence/issue-123-ui-mapping.md` の `emulator -avd ... -no-window` 手順と同一構成）+ `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` |
| AC-2 run 収束（証拠採取 1 回・後続即時失敗） | TS-AC-03 | 強制状態（lane 実行直前に `adb shell input keyevent KEYCODE_SLEEP` 等）でのローカル実行 log。gate 待機が run 全体で 1 回、後続テストが待機せず最初の証拠を参照して失敗すること | 同上（強制状態 + 両クラス実行） |
| AC-3 非注入経路の修復・診断 | TS-AC-04 | 強制状態でのローカル実行 log（`awaitResumedLauncher` / `awaitAccessibilityTextBounds` の診断メッセージ実物、health state 設定の確認） | 同上 |
| AC-4 issue52 の gate と失敗時診断 | TS-AC-05 | ローカル issue52 全クラス green + 強制状態での失敗メッセージ診断内容確認 | 同上 + `ManualOrganizationPreferencesInstrumentationTest` の class filter |
| AC-5 強制状態試行の記録 | TS-AC-06 | 修正前/修正後の同一手順実行結果（成否・再現した状態・CI シグネチャ一致度）を plan.md へ追記。#304 から参照可能にする | 同上（同一手順） |
| AC-6 連続 green | TS-AC-07 | PR CI で issue53・issue52 両 lane が rerun なし連続 green（#292 AC-5 と同基準: 同一 job の連続 attempt を含む）。run link を PR に記録 | GitHub Actions `organizer-instrumentation-issue53-tests` / `organizer-instrumentation-issue52-tests` |
| AC-7 変更範囲と整形 | — | `git diff --stat` が tests/organizer-instrumentation のみ、`./gradlew spotlessCheck` green | JDK 21 / Android SDK 36.1 |

含めるべき観点のうち、unit/contract/property/DB-integration は本変更の対象外
（テスト配線のみ）。failure injection 相当は AC-2〜AC-5 の環境強制で代替する。
「focus を観測していない間は注入しない」「後続テストが待機しない」は、コード上の
gate 配線と強制状態実行の証跡で検証する（注入が起きないこと自体を UI テストで直接
観測する手段が無いため、代替証拠を PR に記載する）。

### リスク

- 強制状態（KEYCODE_SLEEP 等）で CI と同じ状態になるとは限らない
  （occluder が外部 window 系の場合）。その場合も gate 証拠が occluder 判別の
  一次資料となり、root cause 確定は #304 の追跡対象として残る。本 Issue の完了条件は
  precondition 化・診断強化・連続 green である（Issue #300 終了条件の更新を参照）。
- run-level health state の false positive（一時的な gate timeout が run 全体を
  unhealthy にする）は、gate deadline（15 秒）＋修復試みを十分にすることで確率を下げ、
  発生時も最初の証拠が残るため人間の判断が可能である。sticky であることを仕様
  （TS-AC-03）として明示する。
- gate の修復 shell が keyguard 無し環境で冪等であること（`wm dismiss-keyguard` は
  no-op）を通常 path 実行（AC-1）で確認する。

## Documentation updates

- [ ] spec status/history（承認後に `accepted`、PR 後に `implemented`）
- [ ] plan.md Current evidence/Verification への再現証跡追記（AC-2〜AC-5）
- [ ] Issue #300 終了条件の更新（root cause 特定を #304 へ分離。2026-09-12 review
      response で実施済み）
- [ ] CONTEXT.md — 不要（domain language 変更なし）
- [ ] DESIGN.md — 不要（system structure 変更なし）
- [ ] ADR — 不要（3 条件を満たす判断なし。代替案の比較は本 plan に記録）
- [ ] AGENTS.md — 不要（verified command 変更なし）
- [ ] [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md) — lane 構成は
      不変のため原則不要。burst が継続し lane 構成の再評価に進む場合はその時点で更新

## Execution checklist

- [ ] Current behavior reproduced.（強制状態での喪失シグネチャ再現試行 = AC-5 の記録対象）
- [ ] Tests fail for the missing behavior.（修正対象がテスト自体のため、AC-5 の
      修正前/修正後ペアがこれに相当）
- [ ] Minimal implementation completed.（gate + health state 1 実体 + 2 クラスへの配線）
- [ ] Migration/recovery verified.（対象外、テストのみ）
- [ ] Full relevant verification completed.（AC-1〜AC-7）
- [ ] PR evidence and remaining risks recorded.（run link、強制状態の再現結果、
      root cause 確定は #304 へ引き継ぎ）
