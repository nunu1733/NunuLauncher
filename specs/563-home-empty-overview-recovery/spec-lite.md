---
issue: "#563"
status: draft
tier: M
requirements: []
updated: 2026-10-10
---

# HOME-origin overview空カードの修復 — Overview entry時の可視task data再load

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
launcher overviewの表示window内でのview data load timingの修正のみを扱い、
task list取得経路（`RecentTasksList` / `SystemUiProxy` wire）、DB、organizer、
`FallbackRecentsView`（3-button nav用`RecentsActivity`）の既存契約は変更しない。

## Problem

API 36 provider環境（`google/sdk_gphone64_arm64/...BE2A.250530.026.F3...`、emulator-5556）で、
app起点overview session（gesture/direct → card tapによるtask launch）の後に
Settings→HOME→APP_SWITCHでoverviewに入ると、概要state自体は完了しtask listも取得済みなのに
画面・accessibility treeともにtask cardが0件の空overviewになり、swipe等の操作でも回復せず
launcher強制停止以外の脱出手段がない。fixed APKで3/3再現（F1/F2/F5、XML byte-identical
`6285a51a…`、[assessment](../../docs/assessment/issue-563-api36-home-empty-overview.md)）。

診断buildの一次出力により、空overviewのentryでは`applyLoadPlan`後に画面内task viewの
thumbnail/dataがloadingされないことが実測された（画面内879/855/848で`thumb=false`、
画面外843のみ`thumb=true` — 可視判定がpager geometry未確定のまま計算され、
shell recents animationを経ないHOME-origin entryではlayout後の再評価が行われない）。

## Benchmark

該当課題なし。本変更はorganizer等の編集surfaceではなく、recents overviewの
表示信頼性の修正であり、編集負担ベンチマーク（B1〜B7）の計測対象経路に触れない。

## Prior art

- なし（調査済み）。upstream `16-dev`（anchor `43a21b43d7cc7850ab54e14b1a57dc9646685f35`以降）の
  `quickstep/src/com/android/quickstep/views/RecentsView.java` へのcommitは0件
  （`git log 43a21b43..upstream/16-dev -- <path>` 実測、2026-10-10確認）。
  本欠陥は当該anchor木に既に存在し、上流に修正strategiesもpatchも存在しない。
- AOSP Launcher3の同surfaces（RecentsView / LauncherRecentsView）に本問題への
  対応既知事例なし（2026-10-10時点の調査。vendor元コードの当該pathに
  entry後の可視data再評価hookは存在しない）。

## Outcome

app起点overview sessionの後でも、HOME-origin（shell recents animationを経ない）の
overview entryでtask cardが表示され、tap等の通常操作が機能する。
同entryでのoverviewが「カード0件・回復不能」にならない。

## Scope

- `quickstep/src/com/android/quickstep/views/LauncherRecentsView.java` の
  `onStateTransitionComplete` において、recents可視stateへの遷移完了時に
  layout適用後の次frameで `loadVisibleTaskData(TaskView.FLAG_UPDATE_ALL)` を
  1回再実行する（postOnAnimation）。
- 診断用の一時logは含めない（診断buildとその証跡は調査branch
  `issue-563-overview-recovery-fix` に保持）。

## Non-goals

- launcher cold-start deadlock（Issue #565）、`SystemUiProxy.mRecentTasks` null bind race、
  SystemUI TIS rebind gap（いずれも[assessment](../../docs/assessment/issue-563-api36-home-empty-overview.md)
  のrelated findings）の修正。
- `FallbackRecentsView` / `RecentsActivity` 経路への同変更（本欠陥の再現経路は
  launcher in-window overviewのみ）。
- task list取得・wire層（#559修正内容）への追加変更。maxSdk、thumbnail生成（#555）、
  DB/layout書込みはすべて非対象。

## Behavior scenarios

### Scenario: app起点session後のHOME-origin entry（本修正のoracle）

Given fixed APK上でgesture overviewが成立しcard tapでtask launchまで完了した同一
launcher process
When Settings→HOME（10s待機）→APP_SWITCH
Then 0/0.5/1/3/6/8sの全checkpointのuiautomator dumpで `task_view_single` ≥1
And wire signature（unread12 / BadParcelableException / FATAL / 5000ms pending timeout）がすべて0
And dumpsys上のsettled stateがOverviewである

### Scenario: app起点sessionなしのHOME-origin entry（回帰control）

Given 起動直後でapp起点overview sessionが1つも完了していないlauncher process
When HOME→APP_SWITCH
Then Scenario 1と同じ結果（既存どおりPASS）

### Scenario: direct / gesture entry（既存経路の非回帰）

Given launcher process
When Settingsから直接APP_SWITCH、またはgesture swipe
Then overviewにcardが表示され、card tapで起動元taskへ復帰する

### Scenario: 失敗時のzero-write

Given いずれかのoverview entry
When 可視data再load passが実行される
Then Launcher DB / preferences / file書込みは発生しない（変更pathは
`quickstep/src/com/android/quickstep/views/LauncherRecentsView.java` のみで
書込み経路を含まない）

## Verification

- bug oracle（本spec Scenario 1。詳細手順は
  [assessment](../../docs/assessment/issue-563-api36-home-empty-overview.md)）で
  RED→GREENを実証する。REDは修正前APKで3/3（F1/F2/F5。diagnostic build D2/D5/D6/D7でも同型4回）。
  GREENは修正後APKで同一手順を3回実行して全PASSとする。
- controls: Scenario 2 / 3を実行し既存経路が不変であることを確認する。
- 実機owner確認について: 本欠陥の再現環境がAPI 36 provider emulator
  （emulator-5556、#559/#563の証跡と同一）に限定されており、実機では
  provider構成自体が存在しないため実機取得は不可能。emulatorのscreenshot/XML/logcat
  を補助証跡ではなく主証跡として扱う例外理由とする（Issue #563の終了条件も同環境で定義）。
- unit / instrumentation testは追加しない。理由: 対象欠陥はshell recents animationの
  有無とentry順序に依存する状態遷移であり、既存instrumentation frameworkでは
  shell animation状態を制御できない。test-audit gateの回答:
  protected contract = launcher overview表示契約（本spec / #563 assessmentのoracle）、
  credible regression = 本修正による通常entryの可視data load消失、
  boundary = `LauncherRecentsView` のoverview表示契約、
  既存coverage = 本経路のunit/instrumentation coverageは存在しない
  （shell animation制御不能のため）、impact surface = overview表示のみ、
  CI分類 = 新lane不使用（手順oracle + 既存CI gate）。新規lane/恒久surfaceは追加しない。
- 書込み経路を追加しないことの確認: 変更は`LauncherRecentsView.java`1 file
  （view表示hook1箇所）のみ。
- 上流UI bridgeへの変更のため
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  の結果をPR本文へreportする。

## Accessibility and localization

- 本修正はaccessibility treeにcard nodeが復帰する方向の変更であり、TalkBackでは
  修正前（overview_panel subtreeがa11yから消え操作不能）から修正後（card node出現）への
  改善となる。font scaling等の他のa11y側面は既存動作を変更しない。localization影響なし。

## Change history

- 2026-10-10: Draft created for #563（診断build D2/D5/D6/D7の一次出力に基づく）。
