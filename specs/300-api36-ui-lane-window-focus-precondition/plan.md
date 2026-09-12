# Implementation Plan: api36 UI lanes における実入力注入の window focus precondition と環境証拠付き fail-fast

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
    （36/57 green の途中。run 全体では 56/57 green — 継続的な全窓遮蔽ではなく、
    実鍵注入が focus を動かせない瞬間があったことを示す）
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
  - `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt`
    - `pressDownUntilFocused`（:1179）— `sendKeyDownUpSync(KEYCODE_DPAD_DOWN)` の
      実注入 loop。**issue52 lane 失敗の source**（最終 `assertIsFocused` が
      `(Focused = 'true')` で失敗）
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

### 推測（メカニズム確定の対象。本 Issue の diagnostics で判別する）

- keyguard が解消されない boot（keyevent 82 が 36.1 で不十分／launcher 起動と競合）
- device が非 interactive のまま進行する boot
- 外部 window（system dialog 等）が焦点を保持する boot
- API 36.1 固有の window focus 遷移の遅延・不成立

いずれも「対象 window が window focus を得られない」という確認済み水準の下位分類であり、
緩和設計はこの水準に鍵を置くため、判別結果に依存しない。issue52 lane の失敗は
run 中盤・56/57 green である点で継続遮蔽とは整合せず、activity 起動直後の focus 移行
競合等の短時間ウィンドウである可能性があるが、同一の gate が同様に守る。

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
  - `describeDeviceState()`: interactive / keyguard / focused window（`dumpsys window`
    の focused window 行）/ frontmost window package（`rootInActiveWindow?.packageName`）
    の 1 行 summary を返す。
  - いずれも実行を 1 箇所に集めた具象関数であり、interface・adapter は追加しない
    （AGENTS 規約: 必要になるまで実体を増やさない）。
- 2 つのテストクラスは同じ package 配下でこの関数を直接呼ぶ（呼び出し側とテストが
  同じ seam を使う規約に反しない。seam は「注入前の環境観測」という test-only の
  手続きであり、production interface を介さない）。

### Data flow（修正後の注入経路）

```text
issue53 lane:
startLauncher → awaitResumedLauncher
  → gate.ensureWindowFocused(launcher)            // NEW: show 前に 1 回（修復込み）
  → proposal.show() → awaitVisibleProposalActions
  → 各注入（deliveredTap / deliveredTapOutside / sendKey）
      先頭で gate.ensureWindowFocused(...)         // NEW: focus 済みなら即 return
      → injectInputEvent / sendKeyDownUpSync
      → delivery 観測（既存の touchLog / focus poll を不変）
  → gate が deadline まで focus を観測できない
      → describeDeviceState() + describeInputEnvironment() を添えた単一の
        明示的失敗（NEW: injection retry を消耗させない）

issue52 lane:
composeRule.setContent → awaitPreview
  → gate.ensureWindowFocused(hostActivity)        // NEW: 最初の DPAD 前
  → pressDownUntilFocused / KEYCODE_ENTER
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

- **CI workflow 側でのみ unlock/wakeup を追加する**: CI boot は救えるがローカル再現・
  per-test 観測ができず、workflow-only 変更は全 gate 実行を強制する品質規約の対象に
  なる。test 側 gate が同じ効果を CI/ローカル双方で持ち、証拠も残す。
- **injection retry 数・timeout の増加**: 喪失状態への再投入を延長するだけで、burst の
  時間コストが増える。メカニズムを隠す。
- **focus 欠落時に `AssumptionViolatedException` で skip**: required evidence が
  skipped に化け、gate として弱まる。環境異常は明示的失敗として報告する。
- **lane 分割・API 統合などの portfolio 変更**: [ci-test-portfolio.md]
  (../../docs/engineering/ci-test-portfolio.md) の管轄であり、必要な証拠の種類が
  違う。本緩和後も burst が継続する場合の再評価対象として Non-goals に置く。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/.../ui/` 新規支援 file | `ensureWindowFocused` / `describeDeviceState`（修復 shell 込み） | 2 クラスが同一 package で共有する test-only 手続き。production に出さない |
| `OnboardingOrganizationProposalInstrumentationTest.kt` | `TouchActivationGate.show()` 直後と各注入経路（`deliveredTap`、`deliveredTapOutside`、`sendKey`）への gate 配線、`describeInputEnvironment` の拡張 | burst の直接 source がこのクラスの注入 loop。diagnostic が既にここにある |
| `ManualOrganizationPreferencesInstrumentationTest.kt` | `pressDownUntilFocused` と `KEYCODE_ENTER` 注入前の gate 配線 | issue52 lane の実鍵注入が焦点依存であるため。同一手続きで守る |

production source、`src/com/android/launcher3/**`、`.github/workflows/**` は変更しない。

## Migration and recovery

- 対象外（テストのみの変更）。schema/rule migration、rollback、backup/restore への
  影響なし。

## Verification

再現・消滅ペアを原則とする（#292 と同様。フレイクは実行環境の競合であり、JVM テストに
落とせないため、決定的な失敗注入＝環境状態の強制で代替する。理由を PR に記載する）。

| Acceptance criterion | Requirement | Automated/manual evidence | Command or environment |
|---|---|---|---|
| AC-1 注入は focus 観測後のみ実行される | TS-AC-01, TS-AC-02 | 実装後、強制状態なしの通常 lane 実行で全 green（gate が通常 path を壊さないこと）＋ gate 配線の code review | api36 emulator（google_apis x86_64、`docs/assessment/evidence/issue-123-ui-mapping.md` の `emulator -avd ... -no-window` 手順と同一構成）+ `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` |
| AC-2 focus 到達不能時の単一 fail-fast | TS-AC-03 | 強制状態（lane 実行直前に `adb shell input keyevent KEYCODE_SLEEP` 等）でのローカル実行 log。gate 1 失敗で run が停留すること | 同上（強制状態 + 両クラス実行） |
| AC-3 issue52 lane の同一 gate | TS-AC-04 | ローカル issue52 全クラス green + CI run | 同上 + `ManualOrganizationPreferencesInstrumentationTest` の class filter |
| AC-4 再現・消滅ペア | TS-AC-05 | 修正前テストで強制状態 → CI 同種シグネチャ（`launcherWindowFocus=false`、`events=[]`）の再現 log。修正後 → 修復続行または gate fail-fast の log | 同上（同一手順） |
| AC-5 gate メッセージの証拠性 | TS-AC-06 | 強制状態で出力された gate メッセージ実物（interactive / keyguard / focused window / frontmost window / input dump を含む） | 同上（log 抜粋を plan へ追記） |
| AC-6 連続 green | TS-AC-07 | PR CI で issue53・issue52 両 lane が rerun なし連続 green（#292 AC-5 と同基準: 同一 job の連続 attempt を含む）。run link を PR に記録 | GitHub Actions `organizer-instrumentation-issue53-tests` / `organizer-instrumentation-issue52-tests` |
| AC-7 変更範囲と整形 | — | `git diff --stat` が tests/organizer-instrumentation のみ、`./gradlew spotlessCheck` green | JDK 21 / Android SDK 36.1 |

含めるべき観点のうち、unit/contract/property/DB-integration は本変更の対象外
（テスト配線のみ）。failure injection 相当は AC-2/AC-4 の環境強制で代替する。
TS-AC-01〜03 の「focus を観測していない間は注入しない」は、コード上の gate 配線と
強制状態実行の証跡で検証する（注入が起きないこと自体を UI テストで直接観測する
手段が無いため、代替証拠を PR に記載する）。

### リスク

- 強制状態（KEYCODE_SLEEP 等）で CI と同じ状態になるとは限らない
  （occluder が外部 window 系の場合）。その場合は AC-5 の gate 証拠が
  occluder 判別の一次資料となり、mechanism 確定は TS-AC-06 の再発時記録で完了させる。
  spec の Open questions に記載の通り、緩和は occluder 非依存であるため、
  再現不能でも実装・merge 判断は AC-2/AC-4 の再現試行の記録で行う。
- gate の修復 shell が keyguard 無し環境で冪等であること（`wm dismiss-keyguard` は
  no-op）を通常 path 実行（AC-1）で確認する。

## Documentation updates

- [ ] spec status/history（承認後に `accepted`、PR 後に `implemented`）
- [ ] plan.md Current evidence/Verification への再現証跡追記（AC-2/AC-4/AC-5）
- [ ] CONTEXT.md — 不要（domain language 変更なし）
- [ ] DESIGN.md — 不要（system structure 変更なし）
- [ ] ADR — 不要（3 条件を満たす判断なし。代替案の比較は本 plan に記録）
- [ ] AGENTS.md — 不要（verified command 変更なし）
- [ ] [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md) — lane 構成は
      不変のため原則不要。burst が継続し lane 構成の再評価に進む場合はその時点で更新

## Execution checklist

- [ ] Current behavior reproduced.（強制状態での喪失シグネチャ再現 = AC-4 前半）
- [ ] Tests fail for the missing behavior.（修正対象がテスト自体のため、AC-4 ペアが
      これに相当）
- [ ] Minimal implementation completed.（gate 1 実体 + 2 クラスへの配線）
- [ ] Migration/recovery verified.（対象外、テストのみ）
- [ ] Full relevant verification completed.（AC-1〜AC-3、AC-5〜AC-7）
- [ ] PR evidence and remaining risks recorded.（run link、強制状態の再現結果、
      occluder 判別の残課題）
