---
issue: "#304"
status: draft
requirements:
  - RC-AC-01
  - RC-AC-02
  - RC-AC-03
  - RC-AC-04
  - RC-AC-05
risk: []
updated: 2026-09-19
---

# api36 UI lane の burst 発生 boot で window focus を保持する occluder が gate 証拠から特定され、root cause 結論または残存リスク受容が記録される

## Problem

api36 UI lane（`organizer-instrumentation-issue53-tests`、`organizer-instrumentation-issue52-tests`）
で、実入力注入テストが 1 run 内に連続失敗（burst）する環境フレイクが発生している
（[Issue #300](https://github.com/nunu1733/NunuLauncher/issues/300) 記録の
[run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335) 等）。
共通シグネチャは `launcherWindowFocus=false` — launcher window が焦点を得られないまま
テストが進行し、注入が宛先 window へ届かない。同一 head の api35 lane は green であり、
フレイクは head・diff 非依存、rerun（別 boot）で green になる per-boot の環境状態である。

2026-09-12 の Spec/Plan review で、#300 は緩和（window focus precondition gate、
run-level environment health state、診断強化）を担い、burst の根本原因である
`launcherWindowFocus=false` が起こる per-boot トリガーの特定は本 Issue に分離された。

#300 の緩和 gate（PR #305。本 spec 作成時点で main 未 merge）は、実 CI 発生時に
environment 異常を 1 失敗 + 完全な環境証拠として捕獲することを実証した
（[Issue #304 コメント 1](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647372032)、
[コメント 2](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647683394)）:

| # | 捕捉された frontmost / focused window | run | 観測された証拠 field |
|---|---|---|---|
| 1 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（CI image 内蔵の標準ランチャー） | [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012)（head `083c902973d13e851ecda32137af5e9bbf3da323`、event `pull_request`、2026-09-12 16:13 UTC、issue53 lane） | `interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... nexuslauncher/.NexusLauncherActivity}, frontmostPackage=com.google.android.apps.nexuslauncher` |
| 2 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836)（head `820dae07557631273f46000a01a716ad2b5bbb6c`、event `workflow_dispatch`、2026-09-12 17:54 UTC、issue52 lane） | `interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui}, frontmostPackage=android` |
| 3 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（標準ランチャー） | [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 1（head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、2026-09-13、issue53 lane） | 既存 capture 1 と同じ標準ランチャー frontmost の証拠 |
| 4 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [34733839798](https://github.com/nunu1733/NunuLauncher/actions/runs/34733839798)（head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、2026-09-13、issue52 lane） | 既存 capture 2 と同じ system UI ANR dialog の証拠 |
| 5 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 2（head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、2026-09-13 10:31 UTC、issue53 lane） | 最初の失敗で `interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui}, frontmostPackage=android`。同じAPI 36.1 issue53 laneの自然bootで再発した直接証拠 |
| 6 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（標準ランチャー） | [34940500617](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617) attempt 1（head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event `push`（main）、2026-09-15 07:12 UTC、issue53 lane） | 25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、`awaitResumedLauncher`、`OnboardingOrganizationProposalInstrumentationTest.kt:1200`）。`interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... nexuslauncher/.NexusLauncherActivity}, frontmostPackage=com.google.android.apps.nexuslauncher`。`--failed` rerun（attempt 2、[job 104294416831](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/job/104294416831)）は 25/25 green。capture 1/3 と同一 signature の main baseline 上での自然再発 |
| 7 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（標準ランチャー） | [35035443254](https://github.com/nunu1733/NunuLauncher/actions/runs/35035443254) attempt 1（head `9a599cab5e0b9edc72d5951a7252a153b2c91ca4`、event `pull_request`（PR #325 branch `issue-205-implementation`）、2026-09-15 23:32 UTC、issue53 lane） | 25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、`awaitResumedLauncher`）。`interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... nexuslauncher/.NexusLauncherActivity}, frontmostPackage=com.google.android.apps.nexuslauncher`。capture 1/3/6 と同一 signature |
| 8 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [35045487650](https://github.com/nunu1733/NunuLauncher/actions/runs/35045487650) attempt 1（head `e237dffb6d88e4d13a9d58db5c054ff838c79fab`、event `pull_request`（PR #325 branch）、2026-09-16 02:00 UTC、issue52 lane） | 64 test 中 1 失敗（`ManualOrganizationPreferencesInstrumentationTest#changeListTraversalReachesExpandAndReviewActions`、`ensureWindowFocused`、`InjectedInputEnvironment.kt:183`）。`interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui}, frontmostPackage=android`。capture 2 と同じ `window-focus-gate:.../ComponentActivity` 型の issue52 lane での再発 |
| 9 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610) attempt 1（head `e21edbca253bbd9df87252a0fb43d0352c54f272`、event `pull_request`（PR #325 branch）、2026-09-16 02:45 UTC、issue53 lane、[job 104643853432](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/job/104643853432)） | 25 test 中 13 失敗 = 最初の 1 件が `window-focus-gate:app.lawnchair.debug/app.lawnchair.LawnchairLauncher` の ANR dialog 証拠（`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`、`interactive=true, keyguardLocked=false, frontmostPackage=android`）＋ 残り 12 件が run-level environment health state による `input environment already marked unhealthy by an earlier gate failure; reusing the original evidence` の高速失敗。自然発生での health state 連鎖の初観測 |
| 10 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（標準ランチャー） | [35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610) attempt 2（同 head、2026-09-16 03:00 UTC、issue53 lane、[job 104646832297](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/job/104646832297)） | 25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、`awaitResumedLauncher`、`OnboardingOrganizationProposalInstrumentationTest.kt:1200`）。`launcher-resume-timeout` で capture 1/3/6/7 と同一 signature。同一 run の attempt 1（ANR dialog）と attempt 2（標準ランチャー）で異なる occluder 型が連続 boot で発生し、attempt 3 は 25/25 green |
| 11 | `Application Not Responding: com.google.android.apps.nexuslauncher`（標準ランチャー自身の ANR ダイアログ） | [35228730816](https://github.com/nunu1733/NunuLauncher/actions/runs/35228730816) attempt 1（head `83627e1346ee5a2ad6c15b3dd382d2ad0dbcf3e5`、event `pull_request`（PR #347 branch `issue-345-evidence`）、2026-09-17 13:42 UTC、issue53 lane） | 25 test 中 13 失敗 = 最初の 1 件が `window-focus-gate:app.lawnchair.debug/app.lawnchair.LawnchairLauncher` の ANR dialog 証拠（`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`、`interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.google.android.apps.nexuslauncher}, frontmostPackage=android`）＋ 残り 12 件が `already marked unhealthy ... reusing the original evidence` の高速失敗。自然 capture で初めて ANR の subject が systemui ではなく標準ランチャー自身だった |
| 12 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [35319007498](https://github.com/nunu1733/NunuLauncher/actions/runs/35319007498) attempt 1（head `4fcab060d2a70a458c87ab071d9f09706175cea1`、event `pull_request`（PR #355 branch `issue-337-impl`）、2026-09-18 07:21 UTC、issue52 lane） | 75 test 中 1 失敗（`ManualOrganizationPreferencesInstrumentationTest#changeListTraversalReachesExpandAndReviewActions`、`ensureWindowFocused`）。`interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui}, frontmostPackage=android`。capture 2/4/5/8 と同型の issue52 lane 再発であり、issue52 lane の test class 構成拡大（4→6 クラス・75 test）後の最初の実失敗 |

同じ収集期間の [34733180391](https://github.com/nunu1733/NunuLauncher/actions/runs/34733180391) は
gate を通らない `previewHeadingRestoresFocus...` の Compose timeout であり、occluder capture
ではない既存の非gateフレイクとして分離する。2026-09-15〜16 の
[35000215963](https://github.com/nunu1733/NunuLauncher/actions/runs/35000215963) /
[35005008383](https://github.com/nunu1733/NunuLauncher/actions/runs/35005008383)（いずれも
issue52 lane、`StrategyPickerInstrumentationTest` の `ComposeTimeoutException`）も同じ非gate
フレイクとして分離する。同窓の [35042223633](https://github.com/nunu1733/NunuLauncher/actions/runs/35042223633)
（shared-writer lane、HTTP 409 の runner setup error）と
[35037367128](https://github.com/nunu1733/NunuLauncher/actions/runs/35037367128) attempt 1
（`organizer-unit-tests`、#205 diff 対象の unit test 失敗）は window focus と無関係である。

これにより、burst は単一のLawnchair UI状態ではなく「焦点を保持する system window が存在する boot」
全般で発生していたことが強く示唆される。特に run 34732479463 attempt 2 は、既存の強制試行や
別laneの観測ではなく、issue53 laneの自然bootでANR occluderが入力gateを阻害した証拠である。
capture 6 は緩和導入後の main baseline 上で同じ標準ランチャー signature が再発し、
「1 失敗 + 証拠 + 手動 rerun」での運用継続が実証されると同時に、occluder 系 per-boot 異常が
緩和後も引き続き発生していることを示す。
さらに 2026-09-15 23:32〜2026-09-16 03:00 UTC の約 3.5 時間に、PR #325 branch の CI で
capture 7〜10 の 4 件が連続して発生した。同一 run（35048590610）の連続 boot で
attempt 1 が ANR dialog、attempt 2 が標準ランチャー、attempt 3 が green と分岐したことは、
occluder 型が boot ごとに独立に決まる per-boot 分布であることを同一 head 上で直接示す。
また capture 9 では、最初の gate 失敗後に run-level environment health state が run を
poison し、以後の test が元証拠の再利用で高速失敗した（13 失敗 = gate 証拠 1 + 再利用 12）。
旧来の黙って注入が喪失する burst とは異なり、全失敗が単一の完全な環境証拠に紐づいている。
2026-09-17 の capture 11（run 35228730816、PR #347 branch、issue53 lane）は、health state
連鎖（13 失敗 = gate 証拠 1 + 再利用 12）と同時に、自然 capture で初めて ANR dialog の
subject が `com.android.systemui` ではなく `com.google.android.apps.nexuslauncher`
（標準ランチャー自身）だった例である。これは「system 全般が不調な boot では前景 app が
ANR し、その dialog が焦点を保持する」という読み（H2 系）と整合する一方、標準ランチャー
frontmost 型と ANR 型の境界を連続化する観測であり、機構の確定ではない。
2026-09-18 の capture 12（run 35319007498、PR #355 branch、issue52 lane）は、lane の
test class 構成が 4→6 クラスへ拡大された後の最初の実失敗で、capture 8 と同型の
SystemUI ANR dialog が health state 連鎖を伴わず 1 失敗のみで停留した例であり、
連鎖を伴わない単発型も引き続き発生していることを示す。
一方、ANRがそのbootで生じる原因（capture 2/4/5/8/9/12 の SystemUI、capture 11 の
標準ランチャーを含む）と、ANRになる前のrole/resolve/activity/window遷移は
未取得のままである。次が未確定のままである:

1. **標準ランチャー occluder の発生機構**。test の launcher 起動は明示 component 指定
   （`am start -n <Lawnchair> -a MAIN -c HOME`。main の
   `OnboardingOrganizationProposalInstrumentationTest.kt:1208` 実測）であるにもかかわらず、
   標準ランチャーが frontmost focused に留まった。default HOME role の解決、起動の
   redirect、z-order 残留のいずれかは判別できていない。gate 証拠は
   `mCurrentFocus` 行と frontmost package のみを保持し、window z-order や HOME role
   state は残らない。
2. **gate 診断が強制状態を特定できることの実証**。修復対象状態（非 interactive /
   keyguard）は gate の自動修復で解消し証拠を残さないことが実証済み
   （KEYCODE_SLEEP 強制 → wakeup → green。PR #305 本文）。したがって「診断が状態を
   特定できる」ことの実証対象は修復対象外の occluder 状態であり、少なくとも 1 状態での
   実証が本 Issue の終了条件である。
3. **CI 失敗時の証拠保全の実効性**。保全の導入判断は記録済みであり、
   failure-time evidence preservation は PR #313 で実装され、診断自体の長時間停止は
   Issue #315 として分離して PR #316 で bounded 化された（per-command timeout /
   wall budget / 出力上限。[spec 315](../../specs/315-bounded-failure-evidence-capture/spec.md)
   は `implemented`）。ただし 2026-09-15 の main 上での自然再発
   （run 34940500617）では、capture step が `reactivecircus/android-emulator-runner`
   の `emu kill`（07:22:07.964Z）**後**（07:22:09.957Z 開始）に実行されたため、
   artifact は生成されたものの `adb devices` は device なし、window / activity /
   HOME role / logcat 等は `emulator-5554 not found` で空振りし、機構証拠
   （dumpsys / ANR trace）は取得できていない
   （[Issue #304 コメント 2026-09-15](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5676627394)）。
   この順序問題は 2026-09-16 の実失敗（capture 7〜10 のすべての failed boot:
   [35035443254](https://github.com/nunu1733/NunuLauncher/actions/runs/35035443254)、
   [35045487650](https://github.com/nunu1733/NunuLauncher/actions/runs/35045487650)、
   [35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610)
   attempts 1-2）でも再確認された。いずれも capture step は `emu kill` 後の実行であり、
   35048590610 attempt 1 で生成された
   [failure-time artifact 10427419407](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/artifacts/10427419407)
   は 6,784 bytes で device 依存の証拠を含まない。2026-09-17 の capture 11
   （run 35228730816）でも同様に、capture step は `emu kill`（13:52:00.576Z）後の
   13:52:02Z 開始であり、生成された
   [failure-time artifact 10500317904](https://github.com/nunu1733/NunuLauncher/actions/runs/35228730816/artifacts/10500317904)
   は 6,788 bytes で device 依存の証拠を含まない。2026-09-18 の capture 12
   （run 35319007498）でも同様で、capture step は `emu kill`（07:35:27.864Z）後の
   07:35:30.814Z 開始であり、生成された
   [failure-time artifact 10537145649](https://github.com/nunu1733/NunuLauncher/actions/runs/35319007498/artifacts/10537145649)
   は 6,795 bytes で device 依存の証拠を含まない。
   capture を emulator 生存中（同一 script / failure trap 内）へ移す順序修正が
   機構判別の前提として必要であり、実装は本 Issue の非対象（別 workflow PR）。
4. **root cause の結論そのもの**。上記 1〜3 の証拠を統合した結論、または緩和により
   再発が観測できなくなった場合の残存リスク受容の判断が未記録である。

## Outcome

本 Issue が完了したとき、api36 UI lane の burst は「なぜどの window が焦点を保持して
いたのか」で説明される。gate 診断が occluder 状態を特定できることが少なくとも 1 強制
状態で実証され、実発生 capture は occluder 型ごとに分類され、証拠保全の要否判断と
理由が記録され、root cause 結論（単一 cause または分類）が本 Issue へ記録される。
再発が観測できなくなった場合は「root cause 未確定のまま残存リスクを受容する」判断と
その根拠が記録されて完了する。後続の対策（provisioning 対策・gate 修復拡張）を
実施するかどうかの判断材料が、実装を伴わずに揃う。

## Scope

本 Issue は調査（investigation）であり、成果は証拠・判断・記録である:

- **強制状態による診断特定の実証**（終了条件 1）: ローカル api36 emulator 上で
  occluder 状態を人為的に作り、gate 失敗メッセージがその状態を特定することを
  少なくとも 1 状態で実証する。試行の成否・出力・CI シグネチャとの一致度を
  [plan.md](./plan.md) に記録する。
- **発生時証拠の蓄積と occluder 分類**: gate capture（初期 2 例を含む自然発生 12 例、
  標準ランチャー frontmost 5 例・ANR ダイアログ 7 例。ANR の subject は systemui 6 例・
  標準ランチャー 1 例）を
  occluder 型（標準ランチャー / system dialog / keyguard / 非 interactive / その他 /
  不明）に分類し、証拠行だけから分類可能であることを確認する。
- **標準ランチャー occluder の機構判別に必要な観測の特定**: default HOME role
  （`RoleManager` / `cmd role get-role-holders android.app.role.HOME`）、window z-order
  （`dumpsys window windows`）、HOME category 起動の redirect 挙動のうち、どれが
  判別に必要で、gate 証拠の外でどう取得するかを整理する（取得の実装は非対象）。
- **証拠保全の判断**（終了条件 2）: gate 証拠で捕捉できない状態を列挙し、CI 失敗時の
  logcat / dumpsys artifact 等の導入/不導入と理由を本 Issue へ記録する。
  導入と判断した場合の実装は別 PR である（workflow 変更は
  [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md) の管轄）。
  実装後も実失敗時に機構証拠が取得できているとは限らないため、capture の実効性
  （2026-09-15 観測の `emu kill` 後実行問題の解消要否を含む）を継続評価する。
- **root cause 結論の記録**（終了条件 3）: [plan.md](./plan.md) の判断基準に従い、
  結論または残存リスク受容を本 Issue へ記録する。

## Non-goals

- 緩和策の実装（#300 / PR #305）。gate・health state・診断の変更も本 Issue では
  行わない。
- gate 修復の拡張（ANR dialog dismiss 等の修復追加）の実装。判断材料の提供まで。
- lane 構成・CI workflow・emulator provisioning の変更。証拠保全で必要になった場合も
  本 Issue で判断して記録するのみで、実装は別 PR で行う。
- issue52 lane の `(Focused = 'true')` assertion 失敗の原因断定。#300 と同様、
  同一原因とは主張せず、次回の gate 証拠で判別する。
- api35 lane の TwoPanelOrientationCaptureInstrumentationTest（issue #292、別メカニズム）。
- 新規 Issue の自動起票。分割が必要と判明した場合は本 Issue へ記録する。

## Domain language

なし（実装語のみ。`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: gate 診断は強制された occluder 状態を特定する (RC-AC-01)

Given api36 emulator 上で、focus を保持する外部 window（例: 標準ランチャーの
activity、他 app の activity）を前面化した状態がある
When gate を通る test 実行が `ensureWindowFocused` または `ensureInteractiveUnlocked`
経由の待ちで停止する
Then 失敗メッセージは gate の environment 接頭辞
（`input environment never reached a focused window` /
`input environment prevented the launcher from resuming` /
`input environment blocked the frontmost-window accessibility scan`）で始まり、
`focusedWindow=` / `frontmostPackage=` が強制した occluder を名指しする
And `interactive=` / `keyguardLocked=` はその時点の実際の device state と一致する
And 修復対象状態（非 interactive / keyguard locked）は gate の自動修復で解消して
green になるため、証拠特定の実証対象は occluder 系状態である（KEYCODE_SLEEP 実証済み
の挙動と整合する）

### Scenario: 発生時の証拠行だけで occluder を分類できる (RC-AC-02)

Given #300 の gate が動作する lane で environment anomaly が 1 失敗として停留した
When 失敗メッセージの `evidence=...; interactive=..., keyguardLocked=...,
focusedWindow=..., frontmostPackage=...` を読む
Then occluder の identity（焦点を保持した window の owner component または dialog 種別）
が証拠行だけから判別できる
And gate の分類（ENVIRONMENT_ANOMALY としての停留）と矛盾しない
And 自然発生した 12 capture（標準ランチャー frontmost 5 例、ANR ダイアログ 7 例。
ANR の subject は systemui 6 例・標準ランチャー 1 例）はこの契約を
既に満たす実例である

### Scenario: root cause の結論が証拠つきで記録される (RC-AC-03)

Given 発生時 capture と強制状態試行の証拠が蓄積している
When [plan.md](./plan.md) の判断基準を適用する
Then per-boot トリガーの結論（単一 cause、または occluder 型ごとの分類）と、根拠と
なった run link・試行記録が本 Issue へ記録される。AC-3 の root cause 確定には、
最終的な occluder を直接前面化しただけの強制試行では足りず、仮説の因果経路を作る
制御再現、または自然発生した CI boot における role/resolve/activity/window/z-order
遷移の直接証拠を要求する。最終状態のシグネチャ一致は AC-1/AC-2 の証拠に限る。
Or 緩和により再発が観測できなくなった場合、「root cause 未確定のまま残存リスクを
受容する」判断とその根拠（occluder 一覧、gate の証拠能力、観測期間）が本 Issue へ
記録されて完了する

### Scenario: 証拠保全の判断が実装なしで記録される (RC-AC-04)

Given gate 証拠が保持しない状態（window z-order、HOME role state、logcat、
ANR trace 等）の不足が列挙されている
When CI 失敗時の証拠保全手段の要否を判断する
Then 導入/不導入と理由が本 Issue へ記録される
And 導入と判断した場合も本 Issue では実装せず、別 PR の対象として記録される

### Scenario: 調査証跡は plan.md に残る (RC-AC-05)

Given 強制状態による再現試行をローカル api36 emulator で実施した
When 試行が終了する
Then 成否・作った状態・gate 証拠の出力実物・CI シグネチャとの一致度が
[plan.md](./plan.md) に記録される（#300 の plan.md への強制状態試行記録と同じ扱い）

## Data and state

- 本 Issue は production data・schema・migration に触れない。
- 調査成果の正本は本 Issue（結論・判断）と [plan.md](./plan.md)（試行証跡・証拠一覧）
  である。CI run link、head SHA、確認日を添える。
- 強制状態試行で作る device state はローカル emulator 上の一時的なものであり、
  永続化・commit するものはない。

## Permissions, privacy, and security

None。新規 permission・外部送信はない。証拠に含まれるのは CI emulator 上の
component 名・window title・dumpsys 行であり、個人情報を含まない。

## Accessibility and localization

None。production の accessibility 振る舞いは変更しない。

## Acceptance criteria

- [x] AC-1: gate 診断メッセージが強制状態を特定できることを、少なくとも 1 状態で
      実証する。手順・出力実物・CI シグネチャ一致度を plan.md に記録する
      （RC-AC-01、RC-AC-05）。
- [x] AC-2: 実発生の gate capture が証拠行だけで occluder 型へ分類できることを確認し、
      分類表を本 Issue または plan.md に記録する（RC-AC-02）。
- [ ] AC-3: 実発生時の証拠または強制状態との整合から root cause を特定し、本 Issue に
      結論を記録する。ただし、最終的な occluder を直接強制しただけのシグネチャ一致は
      AC-1/AC-2 の証拠であり、AC-3 の機構確定には、因果経路の制御再現または自然発生
      CI boot の role/resolve/activity/window/z-order 遷移の直接証拠を要する。緩和により
      再発が観測できなくなった場合は、「root cause 未確定のまま残存リスクを受容する」
      判断とその根拠を記録して完了する（RC-AC-03）。
- [x] AC-4: CI 失敗時の追加証拠保全が必要と判断し、対象と理由を本 Issue に記録した。
      failure-time evidence preservationはPR #313で実装し、PR #316（Issue #315）で
      bounded化した。2026-09-15のmain自然再発（run 34940500617）ではartifactが生成
      された一方、capture stepがemulator終了後の実行だったためdevice依存の証拠は
      取得できておらず、emulator生存中へのcapture移行が機構判別の前提課題として
      記録された。2026-09-16のcapture 7〜10（4 failed boot）、2026-09-17の
      capture 11（run 35228730816）、2026-09-18のcapture 12（run 35319007498）でも
      同じ順序問題を
      再確認した（実装は別PR。自然再発時の機構判別はAC-3の未完了範囲として残る）
      （RC-AC-04）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | ローカル api36 emulator での強制状態試行 log と gate 失敗メッセージ実物。plan.md Verification evidence 節に記録 |
| AC-2 | gate capture の証拠行と occluder 分類表（自然発生 12 例 = 標準ランチャー frontmost 5 例 + ANR ダイアログ 7 例（subject: systemui 6・標準ランチャー 1）、非gateフレイクの分離、標準ランチャー強制 1 例、NotificationShadeの実再現/因果対照）。本 Issue または plan.md に記録 |
| AC-3 | 本 Issue の結論コメント（自然発生CIの遷移証拠または因果経路の制御再現と、根拠 run link / 試行記録への参照つき） |
| AC-4 | 本 Issue の判断コメント（列挙した不足状態と理由つき。#313/#316 の実装、2026-09-15 の観測および 2026-09-16〜18 の再確認（capture 7〜12）による `emu kill` 後実行と capture 移行の記録を含む） |

## Open questions

blocking なものはない。調査中に解決すべき問い:

- 明示 component 付き HOME category 起動が default HOME role holder へ redirect
  される platform 挙動の有無（標準ランチャー occluder 機構判別の中心問い）。
- gate の focus 待ち deadline（15 秒、PR #305）と main の `awaitResumedLauncher`
  待ち（120 × 100ms ≒ 12 秒、`OnboardingOrganizationProposalInstrumentationTest.kt:1156`
  実測）の差が、発生状態の観測 window に与える影響。
- gate 付きの発生頻度の定量は PR #305 の merge 後にしか取れない。
- failure-time capture を emulator 生存中に実行する workflow 変更（順序修正）の方法と
  要否。2026-09-15 の自然再発で capture step が `emu kill` 後に実行され device 証拠が
  取れなかったことが確認されており、2026-09-16 の 4 failed boot でも再確認された。
  機構判別（H1/H1'/H2）の前提となる課題。
- NotificationShade型の実instrumentation failureが、既存dirty state・boot race・テスト前の
  外部入力のどれで生じたか。ローカルではfocus保持→gate失敗→shade除去後greenの因果対照を
  得たが、wipe-dataを伴わないreboot直後の再現は未確認。

## Change history

- 2026-09-13: Draft created for #304（#300 の Spec/Plan review による分離決定と、
  2026-09-12 の gate 証拠 2 例の Issue コメントを入力として作成）。
- 2026-09-13: 再開時に `origin/main`=`3aa6e83a1f` とIssue全コメントを再確認し、
  #305 merge後の現行gateで標準ランチャーoccluderを強制した診断実証と、追加証拠保全を
  別PRへ分離する判断を `plan.md` に記録。契約範囲とroot cause未確定の扱いは変更なし。
- 2026-09-13: review指摘を反映し、自然発生CI capture（標準ランチャー 2 例、system UI
  ANR 2 例）と非gateフレイクを分類表へ追加。最終occluderの強制再現はAC-1/AC-2に限り、
  AC-3には因果経路の制御再現または自然発生CIの遷移証拠を要求するよう明記した。
- 2026-09-13: 再レビュー指摘を反映し、H4は現行gateの修復・緩和効果とpre-gate burstへの
  寄与未確認を分離して記録。「既存の2 capture」を当時の自然発生4 captureへ更新した。
- 2026-09-13: 独立監査の指摘を反映し、captureごとのexact head SHAとevent種別を追記した。
- 2026-09-13: ローカル `nunu_qpr2_api36_1` の実instrumentation failureで
  `NotificationShade` が `interactive=true` / `keyguardLocked=false` のままfocusを保持する
  追加occluder型を観測。`input swipe`での制御再現と`KEYCODE_BACK`後の同一test greenを
  plan.mdへ追記した。これは直接occluderの因果対照であり、CI bootの自然発生機構とは分離した。
- 2026-09-13: run 34732479463 attempt 2 のissue53 laneで、自然boot中の最初の失敗が
  `Application Not Responding: com.android.systemui` のfocus保持だったことを確認した。
  SystemUI ANRが自然CIで入力gateを直接阻害する証拠として分類へ追加したが、ANR発生前の
  boot内遷移と、SystemUIがANRへ至った根本機構は未確定のままとした。
- 2026-09-13: review指摘を反映し、自然発生captureを5件（標準ランチャー2件、ANR
  ダイアログ3件）として台帳全体で統一した。CI failure時の追加証拠保全は必要と判断済み、
  実装は別workflow PR pendingであることをspecへ反映した。
- 2026-09-13: AC-3継続調査として、ローカルAPI 36.1のreboot反復（wipe-dataなし）でSystemUI
  ANRのrepeated boot-to-ANR観測と、H2と整合するsystem_server/SurfaceFlinger高負荷・
  WindowManager Binder待ちを3/3で得た。一方、CI相当の2 cores/4GB反復、APK導入単体、
  Gradle 25 testsでは非再現で、CIのx86_64 boot内遷移・ANR traceは未取得のため、root cause
  確定ではなく有力仮説の更新とした。これはAC-3の制御causal-path reproductionとは扱わない。
- 2026-09-13: 同一CI jobのrerun（API 36/x86_64/Pixel 7 Pro/SwiftShader、同じconsole
  warning）で25/25 greenを確認した。console warningは十分条件ではないことを追記し、
  AC-3は未完了のまま、失敗boot固有のresource/display状態とCI failure時trace取得を残課題とした。
- 2026-09-13: failure-time evidence preservationを実装。API36のIssue #52/#53 laneで、
  failure時だけwindow/activity/power/role/resolve、ANR/dropbox、限定logcat、input/
  SurfaceFlinger/pressureをartifact化するhelperとfake-`adb` smoke testを追加した。
  最初の自然再発でartifactを取得するまで、機構証拠そのものは未確認のままとする。
- 2026-09-15: main baseline（head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event
  `push`）の run 34940500617 で標準ランチャーoccluder signatureが自然再発し
  （capture 6、rerun green）、緩和後もoccluder系per-boot異常が継続することを確認した。
  同失敗ではfailure-time artifactが生成されたが、capture stepが`emu kill`後の実行だったため
  device依存の証拠は未取得であり、captureをemulator生存中へ移す順序修正を機構判別の
  前提課題として記録した（bounded化はIssue #315 / PR #316で実施済み）。AC-3は未完了のまま。
- 2026-09-16: 再開確認（`origin/main`=`4f555450bd`、#205 merge 以降に gate 実装・CI
  workflow・本 spec/plan への関連変更なし）。CI run 監査で自然発生 capture 4 件
  （7: 標準ランチャー issue53、8: ANR dialog issue52、9: ANR dialog issue53 +
  health state 連鎖 13 失敗、10: 標準ランチャー issue53。run 35035443254 /
  35045487650 / 35048590610 attempts 1-2、いずれも PR #325 branch 上の
  約 3.5 時間窓）を台帳へ追加し、自然発生 capture を 10 例（標準ランチャー 5・
  ANR dialog 5）とした。同一 run の連続 boot で異なる occluder 型が発生した
  per-boot 分布の直接例、および failure-time capture の `emu kill` 後実行の
  再確認を記録した。root cause（AC-3）は引き続き未確定。
- 2026-09-17: 再開確認（`origin/main`=`703afe3f4c`、前回snapshot後のIssue新コメントなし。
  main 差分は #329/#330/#331/#332/#336 系が主体で、gate実装・`tools/ci/`・本 spec/plan
  への変更なし。`ci.yml` は同一 emulator 構成の issue332 lane 追加のみ）。CI run 監査で
  自然発生 capture 11（run 35228730816、issue53 lane、ANR dialog。自然 capture で
  初めて ANR の subject が標準ランチャー自身）を台帳へ追加し、自然発生 capture を
  11 例（標準ランチャー frontmost 5・ANR dialog 6）とした。failure-time capture の
  `emu kill` 後実行（artifact 10500317904、6,788 bytes）を再確認。root cause（AC-3）は
  引き続き未確定。
- 2026-09-19: 再開確認（`origin/main`=`3076bdae7e`、前回snapshot後のIssue新コメントなし。
  main 差分は #348/#327/#328/#337 系（Exchange の source/test/spec）と #356/#361
  （Organizer の AS-IS 監査・TO-BE UX docs）が主体で、gate実装・`tools/ci/`・本 spec/plan
  への変更なし。`ci.yml` は issue52 lane の test class 追加（4→6 クラス。追加 2 クラスは
  gate helper を import しない）のみで行番号は不変）。CI run 監査で自然発生 capture 12
  （run 35319007498、issue52 lane、SystemUI ANR dialog。lane 構成拡大後の最初の実失敗）を
  台帳へ追加し、自然発生 capture を 12 例（標準ランチャー frontmost 5・ANR dialog 7）と
  した。failure-time capture の `emu kill` 後実行（artifact 10537145649、6,795 bytes）を
  再確認。root cause（AC-3）は引き続き未確定。
