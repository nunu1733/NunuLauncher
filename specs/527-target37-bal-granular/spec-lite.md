---
issue: "#527"
status: draft
tier: M
requirements: []
updated: 2026-10-06
---

# targetSdk 37のBAL/IntentSender経路 — 経路別granular opt-in移行と最小委譲

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
activity起動の権限付与方法（`ActivityOptions`のBAL mode選択）とその検証のみを扱い、
起動先・結果処理・DB反映・UI構造の既存契約は変更しない。

## Problem

Android 17 (API 37) はBAL (Background Activity Launch) 保護を `IntentSender.sendIntent()`
にまで拡大し、legacy `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` からの移行を要求、
granular mode（`ALLOW_IF_VISIBLE` 等）への置換を推奨する（[behavior changes 17](https://developer.android.com/about/versions/17/behavior-changes-17#activity-security)）。
新baseline（Phase 2 rebase後）でもlauncher APK内のBAL付与はほぼ全経路が同legacy
modeの一律許可であり、(1) launcherが可視のtap起点経路まで無条件の背景起動許可を
与えており、(2) API 37での起動可否が経路別の明示根拠なしにplatform既定に依存する。

## Baseline（本specの前提事実）

- 16-dev採用baseline: upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`
  （ADR-0018固定）。本spec作業branchは `e8aced7dbee4480c178a9cb994b8d55686ef41ce`
  （Phase 2 review closure後の `issue-532-phase2-restart` head）から分岐する。
- Gradle: compileSdk 37（minor 2）、buildTools 37.0.0、minSdk 26、targetSdk 37
  （`build.gradle`）。
- granular定数への移行に追加SDKは不要: android-36.1 / 37.0 両platformの
  `android.app.ActivityOptions` に `MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED(0)` /
  `ALLOWED(1)` / `DENIED(2)` / `ALLOW_ALWAYS(3)` / `ALLOW_IF_VISIBLE(4)` と
  `setPendingIntentBackgroundActivityStartMode` / `setPendingIntentCreatorBackgroundActivityStartMode`
  が存在する（android.jar実測、確認2026-10-06）。`ALLOW_IF_VISIBLE` はSDK 36追加。
- mode意味（[secure BALガイド](https://developer.android.com/guide/components/activities/secure-bal)）:
  `ALLOW_IF_VISIBLE` は「送信appが送信時点で可視」を条件に許可するsender側の最小付与。
  sender側opt-inはtarget 34+の `PendingIntent.send` / `Context.startIntentSender` に、
  API 37では `IntentSender.sendIntent` にも要求される。creator側はtarget 35+で既定
  不許可（作成時の明示付与が必要）。起動は「sender/creatorいずれかのopt-in」で
  許可され、付与したappが可視等の一般BAL例外を満たす必要がある。

## 経路一覧と移行方針（`e8aced7dbe` 固定列挙）

行番号はすべて `e8aced7dbe` のもの。全経路に「granular移行」または「残置理由」の
いずれかを付す（Issue #527終了条件2）。

### G1: `Utilities.allowBGLaunch` 経由 — helperを移行し全呼び出し経路を一括適用

`src/com/android/launcher3/Utilities.java:753-760` が唯一のchoke point（`ATLEAST_U`
でlegacy `ALLOWED` を設定）。呼び出し経路は以下で、いずれも送信時にlauncher UIDの
可視activity/windowが存在する（tap・表示中UI・可視trampoline起点）。

| 呼び出し経路 | site | 送信時の可視性 |
|---|---|---|
| 既定launch options（app/shortcut tap） | `src/com/android/launcher3/views/ActivityContext.java:536,548`、上書き `quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java:1393,1404`、`quickstep/src/com/android/quickstep/RecentsActivity.java:328-330`、`quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java:996-998`、fork `lawnchair/src/app/lawnchair/LawnchairLauncher.kt:469,497-505` | launcher/taskbar/recents表示中のtap |
| work profile shortcut config | `src/com/android/launcher3/pm/ShortcutConfigActivityInfo.java:167-172` | launcher表示中 |
| system popupのRemoteAction（Pause等） | `src/com/android/launcher3/popup/RemoteActionShortcut.java:99-110` | launcher表示中 |
| proxy結果配送（`createPendingResult` PIへのsend） | `src/com/android/launcher3/util/StartActivityParams.java:104-107` | ProxyActivityStarter（launcher UID）が可視のうちにsend |
| widget configure activity起動 | `src/com/android/launcher3/widget/LauncherWidgetHolder.java:319-323` | launcher表示中 |
| trampoline本体の起動options | `quickstep/src/com/android/launcher3/proxy/ProxyActivityStarter.java:88-93`（LC-Note: BAL hardening 13〜15対策） | trampoline activity可視中 |

**移行**: helperを `ATLEAST_BAKLAVA ? MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE :
MODE_BACKGROUND_ACTIVITY_START_ALLOWED`（`ATLEAST_U` 未満は現行どおり設定なし）へ
変更する。API 36未満のruntimeではgranular定数が存在しないためlegacy分岐を残す
（残置理由: 34/35のBAL modelでは`ALLOWED`が唯一の明示許可）。launcher非可視時に
BALを与えないことが`ALLOW_IF_VISIBLE`の契約であり、UI非表示時の許可拡大は残らない。

### G2: legacy modeの直接指定site — 個別移行

| site | 経路 | 移行 |
|---|---|---|
| `quickstep/src/com/android/launcher3/uioverrides/QuickstepInteractionHandler.java:107-110` | widget tap（RemoteViews PI送信） | `ATLEAST_BAKLAVA ? ALLOW_IF_VISIBLE : ALLOWED` |
| 同 `:63-68`（hostView非検出fallback）と `:126-130`（options取得失敗fallback） | widget tapでwidget側options bundleをそのまま渡す経路。**sender側opt-inが乗らないsilent gap** | 両fallbackでも `ActivityOptions.fromBundle` 等で既存bundleを保持しつつ同一modeを設定 |
| `quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt:138-143,164-169` | private spaceのmarket/settings IntentSender（proxy経由） | `ATLEAST_BAKLAVA ? ALLOW_IF_VISIBLE : ALLOWED` |
| `lawnchair/src/app/lawnchair/qsb/providers/Google.kt:37-41` | QSB search/voice（`startIntentSender`。target 34+でsender opt-in要求） | `ATLEAST_BAKLAVA ? ALLOW_IF_VISIBLE : ALLOWED` |
| `lawnchair/src/app/lawnchair/smartspace/BcSmartSpaceUtil.kt:32-36` | smartspace card tap | `ATLEAST_BAKLAVA ? ALLOW_IF_VISIBLE : ALLOWED` |

### G3: creator側 — 明示付与

| site | 経路 | 移行 |
|---|---|---|
| `quickstep/src/com/android/quickstep/SystemUiProxy.kt:188-202` | recents遷移用PIをWMShellへ渡す。shellが発火する時点でlauncherは非可視になり得る | creator modeを `ATLEAST_BAKLAVA ? ALLOW_ALWAYS : ALLOWED` へ（launcher自身のrecents activity起点。upstream wmshellのcreator `ALLOW_ALWAYS` 使用と同一pattern） |
| `lawnchair/src/app/lawnchair/util/LawnchairUtils.kt:104-115` | 再起動PI（`exitProcess`後にAlarmManagerが発火。self-target・`FLAG_IMMUTABLE`・launcher packageのlaunch intent限定） | creator側opt-inを付与（`ATLEAST_BAKLAVA` で `ALLOW_ALWAYS`）。HOME例外への依存をやめ自己再起動を明示的に許可。第三者appへの権限拡大なし |

### G4: 既にgranular — 保持（変更なし）

| site | 現mode | 保持理由 |
|---|---|---|
| `quickstep/src/com/android/quickstep/TaskAnimationManager.java:338-342` | `ATLEAST_BAKLAVA ? ALLOW_ALWAYS : ALLOWED` | quick-switchはgesture/service context（launcher非可視）からshell経由で起動。`ALLOW_ALWAYS`はgranular。`<Baklava`分岐はG1と同じ残置理由 |
| `quickstep/src/com/android/quickstep/actioncorner/ActionCornerHandler.kt:195-198` | `ALLOW_ALWAYS` | granular。action corner UI起点 |
| `quickstep/src/com/android/quickstep/util/SplitSelectStateController.java:918-928`、`SplitWithKeyboardShortcutController.java:89-101` | `ALLOW_ALWAYS` + transient launch | granular。UI_HELPER_EXECUTOR上でshellへ渡すsplit起動で、launcher可視を前提にできない |

### G5: BAL上変更不要 — 確認記録のみ（変更しない）

- `lawnchair/src/app/lawnchair/bugreport/BugReportReceiver.kt:92-160`: 通知の
  contentIntent/view/shareは `PendingIntent.getActivity`（immutable）。通知tapは
  system側BAL例外で許可され、receiver自身（COPY/UPLOAD）はactivityを起動しない
  （clipboardと`startService`のみ）。#521 assessment C5の「receiver要対応」は
  列挙の結果、activity起動なしに確定したためコード変更不要。
- SAF/exchange系（`backup/ui/CreateBackupScreen.kt:81-110`、`RestoreBackupScreen.kt:281-299`、
  `RestoreNovaBackupScreen.kt:232-247`、`organizer/diagnostics/export/ExportUi.kt:63-110`、
  `organizer/ui/exchange/ExchangeFlowUi.kt:3205-3209,3361-3368`、
  `organizer/integration/exchange/ExchangeTransports.kt:53-71`）: foreground activityの
  ActivityResult/chooserのみで、launcherは常に可視。BAL option不要。
- `src/com/android/launcher3/MainProcessInitializer.java:118-127`（debug crash通知PI）、
  `model/FirstScreenBroadcast.java:159-162`（installer検証token PI、空intent）、
  `lawnchair/src/app/lawnchair/smartspace/provider/OnboardingProvider.kt:45-50`（自身の設定画面）:
  通知tap起点または自app限定で、追加付与より既定のままが最小。
- system action登録のstub PI（`TaskbarManagerImpl.java:1891-1977`、
  `ContextualSearchStateManager.java:249-269`、`input/QuickstepKeyGestureEventsManager.kt:68-77`）:
  send時にactivityを起動しない（process内UI切替）ためBAL対象外。
- `quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java:1604`
  （taskbar深shortcut、options bundleなし）: 送信時taskbar可視で一般例外が成立。
  `SYSTEM_DEFINED`（既定）のまま、明示付与を追加しない。
- `TaskbarDragController.java:441-448` / `SplitSelectDataHolder.kt:219-243`（drag/split
  用PI）: foreground操作で生成しshell経由で消費。既定のまま。

### G6: `wmshell/` module — 対象外（残置理由）

`build.gradle:476`（`withQuickstepImplementation projects.wmshell`）でAPKに同梱される
が、bubbles/desktop mode/splitscreenの実行主体はSystemUI側のshell契約であり、
`e8aced7dbe` 時点のlegacy使用は `BubbleTaskViewHelper.java:105-106`（sender ALLOWED）と
`DragToDesktopTransitionHandler.kt:147-148`（creator ALLOWED）のみ（他は既に
`ALLOW_ALWAYS` / `DENIED`）。本issueでは対象外とする。理由: (1) 当該codeの起動可否は
launcher単独のemulator matrixでは観測できずQuickstep provider/system構成（#524環境）
を要求する、(2) shell契約の変更はrebase直後のbridge最小化に反する、(3) 残置は
upstream 16-dev parity。実測環境が整うPhaseでの追跙はEpic #516へ記録する。

### manifest属性 `allowCrossUidActivitySwitchFromBelow`

**不採用**（採用しない旨を明記する）。本保護はtarget 37で明示opt-inにより有効化する
in-task保護であり、launcher taskはwidget configure・SAF/document・assistant/QSB等の
cross-UID activity flowを意図的にhostする。公式ガイドもassistant/system連携を壊し
得る旨の検証要求を付す。採用する場合は別Issue（quickstep/taskbar含む全host経路の
matrix）で判断する。

## Benchmark

対象課題: B1〜B7（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標: **B1〜B7の作業中断・復帰コストの増分ゼロ**。launch・復帰経路（widget
configure/result、SAF/exchange復帰、app/shortcut/widget tap）のBAL回帰が、編集中の
flowに追加操作・やり直し・セッション破棄を発生させないことをもって判定する。
操作数の短縮そのものは本specの対象外（権限付与方法のplatform契約追従であり、
新たなベンチマーク課題の起票は不要と判断する）。

## Prior art

- Android公式: [behavior changes 17 — Activity Security](https://developer.android.com/about/versions/17/behavior-changes-17#activity-security)。確認日2026-10-06。採用: IntentSenderへのBAL保護拡大とlegacy `ALLOWED`からの移行要求をProblemの根拠にする。
- Android公式: [Secure BAL](https://developer.android.com/guide/components/activities/secure-bal)。確認日2026-10-06。採用: sender/creator両側opt-inの要求version、`ALLOW_IF_VISIBLE`の「送信app可視」条件、`allowCrossUidActivitySwitchFromBelow`の意味と検証要求、logcat `ActivityTaskManager` とStrictMode `detectBlockedBackgroundActivityLaunch` による観測をVerificationに使う。
- upstream 16-dev自身のgranular使用（`TaskAnimationManager.java:338-342` の `ALLOW_ALWAYS`、wmshell各所）。確認日2026-10-06。採用: 非可視経路には`ALLOW_ALWAYS`、可視経路には`ALLOW_IF_VISIBLE`という使い分けの先例としてG3/G4の分類に従う。
- それ以外の外部実装例は省略（調査済み）。対応はplatform契約の追従であり、新規設計の採用がない。

## Outcome

API 36/37環境で、launcher可視状态下のユーザー起点起動（app/shortcut/widget tap、
widget configure/result、QSB search/voice、smartspace、popup RemoteAction、通知action、
SAF/exchange復帰、自己再起動）がすべて成功し、そのBAL付与が経路別の最小granular
modeになる。launcher非可視時に無条件のBALを与える経路は、shell連携（recents/split）
とself-target再起動など根拠を明示したものだけが残る。API 35以下の端末挙動は現行と
同じである。

## Scope

- `Utilities.allowBGLaunch` の移行（G1）。version gate: `ATLEAST_BAKLAVA` で
  `ALLOW_IF_VISIBLE`、`ATLEAST_U` で従来の `ALLOWED`、未満は設定なし。
- G2の5 site（fallback 2経路のgap修正を含む）、G3の2 site（creator側付与）。
- 移行の検証用に、debug build限定で `StrictMode.VmPolicy.Builder().detectBlockedBackgroundActivityLaunch()
  .penaltyLog()` を `Application.onCreate` に追加する（`ATLEAST_BAKLAVA` gate。
  logのみでprocessを殺さない）。fork所有のApplication classに限る。
- 経路表（本spec）の全経路について、移行差分または残置理由をPR本文で対応づける。
- 上流ファイル（`Utilities.java`、`QuickstepInteractionHandler.java`、
  `SystemApiWrapper.kt`、`SystemUiProxy.kt`）の変更は定数選択とversion gateに限定し、
  近傍にissue番号付きcommentを残す。

## Non-goals

- Quickstep provider構成・`QUICKSTEP_MAX_SDK`・recents gesture matrixの実機検証（#524）。
- `wmshell/` module内のBAL移行（G6の理由による。upstream parity維持）。
- `allowCrossUidActivitySwitchFromBelow` の採用、Safer Intents (`intentMatchingFlags`)
  のopt-in（Issue本文の非対象。無条件opt-inしない）。
- API 34/35分岐からの `ALLOWED` 全廃（当該runtimeではgranular定数が存在しない）。
- #526（fork UI表示）、#528（LAN/ECH/CT通信）の対象面。
- 通知・RemoteViews・smartspace等のPI作成契約（immutable等）の変更、起動先activity
  側の対応。

## Behavior scenarios

### Scenario: API 37で可視起点の起動がすべて成功する

Given API 37 emulatorでlauncherを表示し（default HOME設定）、
When app icon tap・深shortcut tap・widget tap・QSB search/voice・smartspace card tap・
popup RemoteAction を実行する
Then それぞれの起動が成功し、logcat（`ActivityTaskManager`）に
"Background activity launch blocked" が出ない。API 36 emulatorでも同様。

### Scenario: widget configure/result とproxy結果配送が成功する

Given API 37 emulatorでconfigure activityを持つwidgetを追加する
When widget pickerで選択し、configure activityが開き、結果を返す
Then configure resultが反映されwidgetが配置される（再設定も同様）。
ProxyActivityStarter経由の結果配送（`StartActivityParams.deliverResult`）も
`ALLOW_IF_VISIBLE` で成功する。

### Scenario: 通知actionとSAF/exchange復帰が背景状態でも既定どおり動く

Given API 37 emulator、launcherを背景にした状態でbugreport通知を受け
When COPY/UPLOAD action、通知tap（view/share）を実行する
Then receiverはactivityを起動せずCOPYはclipboardへ・UPLOADはservice開始、
通知tapはsystemの通知例外で開く。引き続きorganizer export（CreateDocument）・
backup create/restore（OpenDocument）・share chooser を実行すると、picker/chooser
からlauncher UIへ結果付きで復帰し、編集中のsessionは継続する（中断コスト増分なし）。

### Scenario: 拒否・失敗時はzero-writeで既存flowに戻る

Given API 37 emulatorでSAF pickerを開き、
When userがpickerをcancelする（またはwidget bind同意を拒否する）
Then 変更は何も書かれず（zero-write）、launcher UIが既存の取消flowへ戻る。
BALが実際にblockされた場合は起動せず、既存のerror handling（log/toast）に従う。

### Scenario: 非default HOME・API 34/35でも現行挙動が維持される

Given default HOMEを解除したlauncher（API 37）、またはAPI 34/35 emulator
When 可視状態でapp/shortcut/widget tapを実行する
Then 起動が成功する（HOME役割のBAL例外に依存しない可視ベースの許可。
API 34/35はlegacy分岐で現行どおり）。

## Verification

- emulator matrix（主証跡。保守者実機Pixel 9a / API 37はEpic #516 Phase 3の実機
  matrixの対象であり、実機確認をowner手順としてEpic側へ引き継ぐ旨をPRへ明記する。
  #526と同じ扱い）:
  - API 37 emulator（必須）とAPI 36 emulator（granular runtime最初のOS）で上記
    Scenario 1〜5を実測する。観測は (a) 成功/拒否の操作結果、(b) logcat
    `ActivityTaskManager` のBAL blocked filter、(c) debug buildのStrictMode
    `detectBlockedBackgroundActivityLaunch` log、(d) 既存lintのdeprecated警告の有無。
  - foreground/background軸: 通知action（receiver背景）、再起動PI（`exitProcess`後）、
    proxy結果配送（configure返却時）。HOME有/無軸: default HOME設定/解除でScenario 5。
  - 再起動PIは「設定からのapp再起動」flowで起動成功を確認する。
- 既存test suite: organizer unit / 既存instrumentation gateがgreen（本変更は定数選択
  のみで、新規の永続testは追加しない — test-audit判断: framework shadow越しの定数
  assertは低価値で、振る舞いはemulator matrixとlintが一次証拠。既存
  `TaskAnimationManagerTest` の `ALLOW_ALWAYS` assertは対象外のため不変）。
- 上流bridgeの計測: `python3 tools/repo-contract/measure_upstream_patch_surface.py
  --target HEAD --enforce-baseline` の結果をPR本文へreportする（上流4 fileへの
  最小変更であることの確認）。
- 書込み経路を追加しないことの確認: 変diffはG1〜G3のmode選択・version gate・
  debug StrictMode追加に限り、PI作成契約・起動先・結果処理・DB pathは不変。

## Accessibility and localization

- UI変更・文字列追加なし（`ActivityOptions`の定数選択とdebug用StrictModeのみ）。
  本来の対象外であり、emulator matrixで起動結果が変わらないことを確認する。

## Change history

- 2026-10-06: Draft created for #527。
