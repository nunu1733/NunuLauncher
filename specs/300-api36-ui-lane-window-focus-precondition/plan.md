# Implementation Plan: api36 UI lanes における実入力注入の window focus precondition、environment health state（入口確認・証拠条件つき）、および決定的検証 seam

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
- **lane topology の実測**（現行 → 修正後を分けて記録。review 指摘の確認）:
  - issue53 job（現行）: 1 クラス（`OnboardingOrganizationProposalInstrumentationTest`、
    ci.yml :550）。**修正後**: 状態 test クラス追加により 2 クラスの filter になる
  - issue52 job（現行・修正後とも不変）: **4 クラス**を 1 Gradle invocation で実行
    （`ManualOrganizationProductionE2EInstrumentationTest`、
    `ManualOrganizationPreferencesInstrumentationTest`、
    `StrategyPickerInstrumentationTest`、`MissingAppSelectionInstrumentationTest`、
    ci.yml :394）
  - gate 対象の実入力注入（`uiAutomation.injectInputEvent` / `sendKeyDownUpSync`）は
    issue52 job 内では `ManualOrganizationPreferencesInstrumentationTest` のみが使用し、
    他 3 クラスは使用していない（grep 実測）。burst の実測もこのクラスに限る
  - **gate を通る test の実数**（保証単位がクラスではなく gate を通る実行である根拠。
    review 指摘の確認）:
    - issue53: `OnboardingOrganizationProposalInstrumentationTest` は 20 test のうち
      9 が `TouchActivationGate` 経路を使用し、11 は使用しない（grep 実測）
    - issue52: `ManualOrganizationPreferencesInstrumentationTest` は 40 test のうち、
      実鍵注入 gate（`pressDownUntilFocused` / `KEYCODE_ENTER`）を通るのは
      `changeListTraversalReachesExpandAndReviewActions` の 1 test のみである
      （`sendKeyDownUpSync` の使用箇所は同 test と helper のみ。grep 実測）
  - `tests/organizer-instrumentation` は androidTest 専用 source set であり
    （自前 build.gradle 無し、lawnchair module の androidTest として compile）、
    helper の JVM unit test は存在しない。決定的検証は instrumentation test で行う。

### 推測と未確定（分離後の扱い）

- **issue52 lane の失敗は仮説である**: 実鍵注入後に Compose focus traversal が失敗した
  ことまでしか記録がなく、同時点の window focus は不明である
  （review 指摘）。`hasWindowFocus()==true` のまま traversal だけ失敗する可能性を
  残すため、同一原因とは主張せず、gate と失敗時状態採取（TS-AC-06）で次回判別可能にする。
- **per-boot トリガー（keyguard 解消不成立 / 非interactive / 外部 window /
  API 36.1 の focus 遷移）は未確定**であり、root cause 特定は
  [Issue #304](https://github.com/nunu1733/NunuLauncher/issues/304) に分離した。
  本 plan の強制状態試行は #304 の入力となる証拠収集であり、その成否は本 Issue の
  完了条件ではない。

## Design

### Modules and interfaces

- production module・interface には触れない。変更は `tests/organizer-instrumentation`
  と ci.yml の class filter 1 行に限る。
- 新規の test 支援実体を 1 つだけ追加する:
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/` 配下に
  `InjectedInputEnvironment`（仮称、file 名は PR で確定）を配置し、2 クラスから使う。
  - `ensureWindowFocused(activity, deadline)`: **入口で health state を確認**
    （unhealthy なら修復・待機せず即座に、保持済み証拠を参照する error）した上で、
    対象 activity の window focus を bound 付きで待つ。非 interactive なら
    `input keyevent KEYCODE_WAKEUP`、keyguard showing なら `wm dismiss-keyguard` を
    shell（既存 `runShellCommand` と同経路）で発行してから再待ちする。既に focus 済み
    なら即座に返す。timeout 時は environment 前提（window focus）の崩壊として
    証拠を採取し `markUnhealthy` する。
  - `ensureInteractiveUnlocked()`: **入口で health state を確認**した上で、
    `awaitResumedLauncher` 等の対象 window がまだ存在しない待ちの前に使う
    device-level 修復。待ち自体は行わない。
  - `describeDeviceState()`: interactive / keyguard / focused window（`dumpsys window`
    の focused window 行）/ frontmost window package（`rootInActiveWindow?.packageName`）
    の 1 行 summary。
- **run-level environment health state** は Android 非依存の純粋 state machine クラス
  （仮称 `EnvironmentHealthState`）として切り出す:
  - `markUnhealthy(evidence)`: 最初の 1 回だけ証拠を保持する（2 回目以降は無視）。
  - 入口確認: 保持済み証拠を参照する error を投げるための判定。
  - 有効範囲は **1 instrumentation invocation ＝ 1 process** である。issue52 lane は
    4 クラスが同一 process で共有する（同一 emulator job 内なので環境も共有であり、
    意図した挙動）。issue52 と issue53 は別 job・別 emulator であり state は混在しない。
- **timeout 時の分類**（環境前提崩壊の観測がある場合のみ unhealthy にする。再 review
  指摘 2）:
  - `awaitResumedLauncher` timeout 時: 環境を再観測（interactive / keyguard /
    frontmost window）。非 interactive / keyguard locked / frontmost window が異物
    の観測が取れた場合のみ environment failure（診断＋`markUnhealthy`）。
    環境が正常なら local failure（既存メッセージ＋device state 診断を付加、
    health state 不変）。launcher lifecycle 回帰の検出能力を保つ。
  - `awaitAccessibilityTextBounds` timeout 時: 環境を再観測（対象 activity の
    window focus / frontmost package）。activity が focus を持ち frontmost も正しい
    のに text が無い場合は **node-not-found failure のまま**（製品回帰の検出を保つ、
    health state 不変）。frontmost が異物 / activity が focus を持たない場合のみ
    environment failure（診断＋`markUnhealthy`）。
  - `ensureWindowFocused` timeout: gate が守る前提（window focus）自体の崩壊であるため
    environment failure として `markUnhealthy` する。
  - 分類判定は観測 snapshot（interactive / keyguard / frontmost / 対象 focus）から
    判定を返す純粋関数として切り出し、状態 test クラスで決定的に検証する。
- **決定的検証 seam**（再 review 指摘 3）:
  - 状態 test クラス `app.lawnchair.organizer.ui.InjectedInputEnvironmentStateInstrumentationTest`
    （仮称）を新設し、state machine と分類関数を直接駆動する
    （最初の mark のみ証拠保持 / 入口確認の即時性と証拠同一性 / 分類ケース）。
    instrumentation module は JVM unit test を持たないため、検証は instrumentation
    test で行う。環境状態に依存しない決定的な assertions のみで構成する。
  - helper に runner 引数による failure injection hook を設ける:
    instrumentation 引数（例: `-e nunuInjectEnvironmentHealthFailure <label>`）が
    明示指定された場合にのみ、最初の environment 操作で state を unhealthy に固定する。
    CI lane では指定しない。これにより実際の配線（helper 入口確認 → gate 利用 test の
    即時失敗）を、修復が成功して green になってしまう環境（sleep 等はまさに修復対象）
    に依存せず検証できる。
  - issue52 の traversal 失敗診断のメッセージ生成も、観測値 snapshot から failure
    メッセージを組み立てる純粋関数に切り出し、状態 test クラスで決定的に検証する
    （強制状態実行は oracle にしない方針と整合させる）。
  - 状態 test クラスを issue53 lane の class filter に追加する（ci.yml :550 への
    1 行追加。lane 構成・job 分離・API 構成は不変）。
  - **保証範囲と検証の対応**: health state の収束保証の単位は
    **gate を通る実行（gated execution）**であり、gated class ではない。issue53 の
    20 test 中 9、issue52 の 40 test 中 1（`changeListTraversalReachesExpandAndReviewActions`）
    だけが gate を通るため、helper を呼ばないテストは health state を参照せず通常どおり
    実行される。runner 引数 injection による決定的配線検証も、filter やクラス全体では
    なく gate 利用 test を `-e class Class#method` で明示指定して行う。同一 invocation
    内の gate を通らない実行（同クラス内・他クラスを含む）は保証対象外である
    （review 指摘: 保証単位を「クラス」から「gate を実際に通る経路」へ正確に落とす）。
- いずれも実行を 1 箇所に集めた具象関数・具象クラスであり、仮想的な interface・
  adapter は追加しない（AGENTS 規約: 必要になるまで実体を増やさない。純粋 state
  machine と分類関数は、決定的検証が reviewer により必須化された時点で必要になった
  実体である）。

### Data flow（修正後の注入経路）

```text
issue53 lane（各テスト）:
ensureInteractiveUnlocked()         // 入口で health 確認 → 修復（wakeup/dismiss）
startLauncher → awaitResumedLauncher
  → timeout 時: 環境を再観測 → 異常の観測があれば environment failure
    （診断 + markUnhealthy）/ 正常なら local failure（診断付き）    // NEW
  → gate.ensureWindowFocused(launcher)   // 入口で health 確認 → 修復 → focus 待ち
      → timeout 時: 証拠を 1 回採取（describeDeviceState + describeInputEnvironment）
        markUnhealthy → 単一の明示的失敗                                 // NEW
  → proposal.show() → awaitVisibleProposalActions
  → 各注入（deliveredTap / deliveredTapOutside / sendKey）
      gate.ensureWindowFocused(...)（入口 health 確認込み）             // NEW
      → injectInputEvent / sendKeyDownUpSync
      → delivery 観測（既存の touchLog / focus poll を不変）

issue53 lane（accessibility 走査）:
PreferenceActivity 遷移 → gate.ensureWindowFocused(activity)
  （入口 health 確認 → 修復 → focus 待ち）                              // NEW
  → rootInActiveWindow poll（既存）
  → timeout 時: 環境を再観測 → frontmost 異物 / activity 非focus なら
    environment failure（診断 + markUnhealthy）/ 環境正常なら
    node-not-found failure のまま（製品回帰）                           // NEW

issue52 lane（各テスト）:
composeRule.setContent → awaitPreview
  → gate.ensureWindowFocused(host)（入口 health 確認込み）              // NEW
  → pressDownUntilFocused / KEYCODE_ENTER
      → 失敗時（focus 観測下でも traversal 失敗）: 注入時の
        device/window state を failure メッセージへ付加                 // NEW

決定的検証（CI 常設 + 計画実行）:
InjectedInputEnvironmentStateInstrumentationTest        // state machine + 分類 + 診断メッセージ
  → issue53 lane filter で常設実行（自身の fresh instance を駆動）      // NEW
`am instrument -e nunuInjectEnvironmentHealthFailure <label> \
  -e class <gate 利用 test の Class#method> ...`
  （gate 利用 test を明示指定した別 invocation。issue53 は gate 利用 test、
  issue52 は changeListTraversalReachesExpandAndReviewActions と
  gate を通らない sibling test。lane 間で混ぜない）
  → gate 利用 test が待機なしで即時失敗し同一証拠を参照すること、
    gate を通らない test が通常実行されることを実配線で検証             // NEW
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

- **`checkAtGateEntry()` を待ちの後に置く（初版設計）**: unhealthy 判定後のテストも
  `ensureInteractiveUnlocked`（修復）と `awaitResumedLauncher`（待機）を実行してしまい、
  「最初のテストだけが待機・修復」に反する（再 review 指摘 1）。入口確認を
  environment 操作自体の入口に置く設計へ変更した。
- **`awaitResumedLauncher` / accessibility timeout を無条件に `markUnhealthy`（初版
  設計）**: launcher lifecycle 回帰や organizer entry 消失という製品回帰まで
  environment failure に変換し、後続テストを poisoning する（再 review 指摘 2）。
  timeout 時の再観測による分類を導入した。
- **実環境の強制状態のみで AC-2 を検証する（初版設計）**: sleep / keyguard はまさに
  gate の自動修復対象であり、正常実装なら gate failure に届かず green になる。state
  machine の決定的検証が不可能（再 review 指摘 3）。runner 引数 injection +
  状態 test クラスの seam を導入し、実環境試行は #304 証拠へ分離した。
- **クラス単位の保証（`@Before` / Rule 等の共通入口で health を確認する）**: issue52 の
  40 test 中 gate を通るのは 1 test のみであり、クラス単位の保証は実測を超える範囲を
  environment failure に変換する。保証単位を gate を通る実行に限定し、共通入口は
  追加しない（review 指摘の方向性。クラス全体の収束が必要になった時点で再評価）。
- **4 クラス lane 全体を共通入口（runner listener / test rule）で収束対象にする**:
  helper を配線していないクラスまで強制的に environment failure に変える設計は、
  本 Issue の実測（実入力注入経路を持つのは gated 2 クラス、burst もその 2 クラスに
  限る）を超える保証範囲であり、配線漏れの診断も難しくなる。保証範囲を gated クラスに
  限定し、他クラスの配線が必要になった時点（burst の実測等）で再評価する
  （review 3 指摘 1 の方向性）。
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
  完了条件として常に不成立になり得る。#304 に分離し、本 Issue は gate 診断と
  強制状態試行の記録を提供する。
- **lane 分割・API 統合などの portfolio 変更**: [ci-test-portfolio.md]
  (../../docs/engineering/ci-test-portfolio.md) の管轄であり、必要な証拠の種類が
  違う。本緩和後も burst が継続する場合の再評価対象として Non-goals に置く。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/.../ui/` 新規支援 file | `ensureWindowFocused` / `ensureInteractiveUnlocked` / `describeDeviceState` / 純粋 state machine `EnvironmentHealthState` / 分類関数 / runner 引数 injection hook | 2 クラスが同一 package で共有する test-only 手続き。入口確認と分類を 1 箇所で所有する |
| `tests/organizer-instrumentation/.../ui/InjectedInputEnvironmentStateInstrumentationTest.kt`（新規） | state machine（単一証拠保持・入口確認の即時性・証拠同一性）、分類関数、issue52 診断メッセージ生成の決定的検証 | instrumentation module に JVM unit test が無いため、検証は instrumentation test で常設実行する |
| `OnboardingOrganizationProposalInstrumentationTest.kt` | `TouchActivationGate.show()` 直後と各注入経路（`deliveredTap`、`deliveredTapOutside`、`sendKey`）への gate 配線、`describeInputEnvironment` の拡張、`awaitResumedLauncher` の修復前置き＋timeout 分類、`awaitAccessibilityTextBounds` の gate＋timeout 分類 | burst の直接 source がこのクラスの注入 loop と待ち。非注入系 failure も同一 burst の症状である |
| `ManualOrganizationPreferencesInstrumentationTest.kt` | `pressDownUntilFocused` と `KEYCODE_ENTER` 注入前の gate 配線、traversal 失敗時の device/window state 付加 | issue52 lane の実鍵注入が焦点依存であるため。同一原因の主張はせず、次回判別可能な証拠を残す |
| `.github/workflows/ci.yml`（:550、1 行） | issue53 lane の class filter に状態 test クラスを追加 | state machine の検証を CI で常設化するため。lane 構成・job 分離・API 構成は不変（spec の例外条項） |

production source、`src/com/android/launcher3/**` は変更しない。workflow 変更は
上記 1 行のみであり、quality-strategy の規約（workflow 変更 PR は全 gate を実行）に
従う。

## Migration and recovery

- 対象外（テストのみの変更）。schema/rule migration、rollback、backup/restore への
  影響なし。

## Verification

再現・消滅ペアの原則は、決定的に検証できる部分（state machine、分類関数、配線）と、
実環境にしか存在しない部分（per-boot トリガー）で扱いを分ける。

- **決定的検証**: 状態 test クラス（CI 常設）と runner 引数 injection による
  実配線検証。環境状態に依存しないため、本 Issue の merge 条件である。
- **実環境の強制状態試行**（KEYCODE_SLEEP 等）: gate の自動修復対象であるため green に
  収束する可能性が高く、AC の oracle には使わない。結果（成否・再現した状態・CI
  シグネチャ一致度）を plan.md に記録し、#304 の証拠とする。

| Acceptance criterion | Requirement | Automated/manual evidence | Command or environment |
|---|---|---|---|
| AC-1 注入は focus 観測後のみ実行される | TS-AC-01, TS-AC-02 | 実装後、通常 lane 実行で全 green（gate が通常 path を壊さないこと）＋ gate 配線の code review | api36 emulator（google_apis x86_64、`docs/assessment/evidence/issue-123-ui-mapping.md` の `emulator -avd ... -no-window` 手順と同一構成）+ `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` |
| AC-2 入口確認と run 収束 | TS-AC-03, TS-AC-05 | 状態 test クラス green ＋ runner 引数 injection と **gate 利用 test 明示指定**（`-e class Class#method`）による別 invocation 実行 log: issue53（gate 利用 test）と issue52（`changeListTraversalReachesExpandAndReviewActions` ＋ gate を通らない sibling）を別々に実行し、gate 利用 test が待機・修復なしで即座に失敗し同一証拠を参照すること、gate を通らない test が通常実行されること。lane 間で混ぜない | `adb shell am instrument -w -e nunuInjectEnvironmentHealthFailure review -e class <Class#method> app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner`（`connectedLawnWithQuickstepGithubDebugAndroidTest` の runner 引数でも代替可） |
| AC-3 markUnhealthy の分類 | TS-AC-04 | 状態 test クラスの分類ケース（環境正常 → local / node-not-found failure、異常観測 → environment failure）green ＋ 強制状態実行で得られる分類メッセージの実物（取得できた場合） | 状態 test クラス（issue53 lane / ローカル） |
| AC-4 決定的検証 seam | TS-AC-05 | 状態 test クラスの CI 実行結果（issue53 lane filter 追加後）＋ injection 付き lane 実行 log。強制状態試行の記録（成否・状態・CI シグネチャ一致度）を plan.md へ追記し #304 から参照可能にする | GitHub Actions `organizer-instrumentation-issue53-tests` + ローカル |
| AC-5 issue52 の gate と失敗時診断 | TS-AC-06 | 診断メッセージ生成（固定 snapshot → failure メッセージ）の決定的 test green ＋ issue52 注入経路への配線 code review。強制状態実行は #304 向け optional evidence（取得できた場合のみ plan.md に記録） | 状態 test クラス（issue53 lane / ローカル） |
| AC-6 連続 green | TS-AC-07 | 連続する 3 つの異なる workflow run で issue53・issue52 両 lane が `run_attempt = 1` のまま green。rerun で green にした run はカウントしない。run link と head SHA を PR に記録 | GitHub Actions `organizer-instrumentation-issue53-tests` / `organizer-instrumentation-issue52-tests` |
| AC-7 変更範囲と整形 | — | `git diff --stat` が `tests/organizer-instrumentation` と ci.yml の issue53 class filter 1 行に限られること、`./gradlew spotlessCheck` green | JDK 21 / Android SDK 36.1 |

含めるべき観点のうち、unit/contract/property/DB-integration は本変更の対象外
（テスト配線のみ）。failure injection 相当は状態 test クラスと runner 引数 injection
で決定的に実現した（環境強制は #304 証拠の収集手段として併用する）。
「focus を観測していない間は注入しない」は、コード上の gate 配線と injection 実行の
証跡で検証する（注入が起きないこと自体を UI テストで直接観測する手段が無いため、
代替証拠を PR に記載する）。

### リスク

- 状態 test クラスが instrumentation test であることによる実行コスト追加は軽微である
  （環境待ちを持たない決定的 assertions のみ）。
- runner 引数 injection hook が CI で誤指定された場合、lane が全即時失敗になる。
  hook は明示的な引数名でのみ動作するため、通常の lane 引数とは衝突しない。hook の
  存在を PR と spec に記録する。
- run-level health state の false positive（一時的な gate timeout が run 全体を
  unhealthy にする）は、gate deadline（15 秒）＋修復試みを十分にすることで確率を下げ、
  発生時も最初の証拠が残るため人間の判断が可能である。sticky であることを仕様
  （TS-AC-03）として明示する。
- 分類の再観測が「異常の見逃し」側に倒す設計であること（環境異常でも観測が取れない
  場合は local failure になる）を仕様に明示する。poisoning より見逃しを優先するのは、
  merge gate の診断能力（製品回帰と環境 failure の分離）を保つためである。
- gate を通らない実行（issue52 の 40 test 中 39、issue53 の 20 test 中 11、issue52
  invocation 内の他 3 クラス）は、health state が unhealthy でも通常どおり実行される
  （収束保証の対象外）。これは仕様であり、環境破損下でこれらのテストが時間を要したり
  個別に失敗したりする可能性は残る。実入力注入経路と burst の実測がある gate 利用経路
  のみを対象とする本 Issue の範囲決定に基づく。
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
      不変のため原則不要。状態 test クラスの issue53 lane への追加（1 行）は、
      lane 責務の追加ではなく同 lane 内の class filter 追加であることを PR に記載

## Execution checklist

- [ ] Current behavior reproduced.（state machine・分類は決定的に検証、実環境強制状態
      の再現試行は #304 証拠として記録）
- [ ] Tests fail for the missing behavior.（修正対象がテスト自体のため、状態 test
      クラスと injection 実行がこれに相当）
- [ ] Minimal implementation completed.（gate + health state + 分類 1 実体、
      状態 test クラス、2 クラスへの配線、ci.yml 1 行）
- [ ] Migration/recovery verified.（対象外、テストのみ）
- [ ] Full relevant verification completed.（AC-1〜AC-7）
- [ ] PR evidence and remaining risks recorded.（run link、injection 実行結果、
      root cause 確定は #304 へ引き継ぎ）
