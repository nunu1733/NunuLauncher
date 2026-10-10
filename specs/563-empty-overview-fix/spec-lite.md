---
issue: "#563"
status: draft
tier: M
requirements: []
updated: 2026-10-11
---

# HOME-toggle再entryでoverviewカードが空になる状態を解消する

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを
含まない。変更pathは高リスクpath一覧に含まれない
（`tools/repo-contract/validate_high_risk_evidence.py` の
HIGH_RISK_PATH_PREFIXES / HIGH_RISK_PATH_FILES のいずれにも該当しない）。

## Problem

API 36 provider環境で、同一launcher process内でapp-origin overview sessionを
card tapで完了させた後の `HOME → dwell → APP_SWITCH` 再entryで、overview状態に
遷移するにもかかわらずカードが1枚も表示されず、swipeでも回復しない
（bug oracle RED。前2 sessionのfix候補9案はすべて不発）。

## Benchmark

bug work itemであるため編集負担ベンチマーク課題（B1〜B7）は対象外。
代わりに本Issueで確立済みのbug oracleを目標値とする（driver と全証跡は
[docs/assessment/563-empty-overview-fix-evidence/](../../docs/assessment/563-empty-overview-fix-evidence/)
の `oracle-driver.sh` が正本）:

- 手順: force-stop → Settings起動 → gesture UP → card tap → HOME → 10s dwell →
  APP_SWITCH → 0/0.5/1/3/6/8s 各時点でuiautomator dump。
- 判定: **各時点すべてのdumpで `task_view_single` nodeが1以上**（node countingは
  `grep -o | wc -l`。XMLが1行のため `grep -c` は行数を数え、node数にならない）。
  RED = settled stateがOverviewのまま全時点0。
- 修正前: RED（BB1。node counts は evidence `xml/BB1-RED-node-counts.txt`）。
- 目標: **GREEN 3/3**（antecedentが成立した有効runのみ数える。TIS rebind gap等で
  toggleがlauncherに届かなかったrunは除外し、除外理由を実行可能証跡
  （logcatの `goToState` 件数）で記録する）。
- 対照: antecedentなしの直接APP_SWITCH entryは引き続き1以上（回帰なし）。

## Prior art

- 上流16-dev `PagedView.getScreenCenter` / `RecentsView.applyLoadPlan` /
  `updatePivots` — 2026-10-11時点で本fork vendored版と同一。unset pivotを
  NaNのまま消費する欠陥と、再entryでのpivot再設定欠落は上流にも存在する
  （`git show upstream/16-dev:...` で確認）。上流に流用可能なfixはないため
  本forkで最小修正する。確認日 2026-10-11。
- Android framework `View`/`RenderNode` — bounds変更で明示pivotがunset
  （NaN）に戻る挙動と、描画時のunset pivot実効値=view中央。本specの
  fallbackはこの実効値に合わせる。確認日 2026-10-11。
- main側 #555 (PR #561) / #562 (PR #567) の `TaskContainer` block body修復 —
  559 stackには未適用だったため本PRでcherry-pick（後述の依存）。

## Outcome

HOME→dwell→APP_SWITCH再entryでも、表示中ページのタスクに正しい可視setが
要求され、thumbnailが描画され、accessibility treeにoverview panel subtree
が現れる。TalkBack/uiautomatorからカードが見えなくなることもない。

## Scope

- `PagedView.getScreenCenter` の計算を純関数 `computeScreenCenter` に抽出し、
  unset (NaN) pivotをview中央として解決する。
- `RecentsView.applyLoadPlan` でrebind時に `updatePivots()` を再実行する。
- 559 stackに未適用だったmainの `TaskContainer` 式body修復4 commit
  （`4a8508498b`, `a37af58430`, `7a8ef68fdd`, `f93d29a619`）をcherry-pick
  （これらなしにはtile stateがviewへ全く適用されず本bugの検証ができない）。
- `TaskContainer` の副作用のみメソッド（`bind`/`destroy`/`refreshOverlay`/
  `setState`）に明示的 `: Unit` を付け、`fun x() = { ... }` の再発を
  compile error化する。

## Non-goals

- 過去2 sessionの修正候補9案（clamp/widen/cache-skip等）の復活。pivotが
  正しく再設定されれば可視set計算は正しく動作するため不要。
- 上流へのfix報告（cutover後に別issueで扱う）。
- `#565` cold-start deadlock、TIS rebind gap 等、本bugと独立の既知欠陥。

## Behavior scenarios

### Scenario: HOME-toggle再entryでカードが表示される

Given app-origin overview sessionをcard tapで完了したlauncher process
When `HOME → 10s dwell → APP_SWITCH` でoverviewに再entryする
Then 0/0.5/1/3/6/8s 各時点のuiautomator dumpに `task_view_single` nodeが
1以上含まれ、screenshotにtask cardの内容が描画されている

### Scenario: 可視setが表示ページに追従する

Given overview再entry直後（pivotが直前のbounds変更でunsetの状態）
When `loadVisibleTaskData` が可視setを計算する
Then `getPageNearestToCenterOfScreen()` は実際のscroll位置に対応するpageを
返し（ClearAll擬似pageではなく）、repositoryは表示中タスクのthumbnailを要求する

### Scenario: unset pivotでの防御

Given 何らかの経路でpivotがNaNのまま `getScreenCenter` が呼ばれる
When `computeScreenCenter` が計算する
Then screenCenterは0ではなく `scroll + size/2` を基準にした値を返す
（unit test `PagedViewScreenCenterTest` が所有）

### Scenario: 式body欠陥の構造的再発防止

Given `TaskContainer` の副作用のみメソッド
When `fun setState(...) = { ... }` のようなlambda返しを再導入しようとする
Then compile errorになる（明示的 `: Unit` により）

### Scenario: 通常経路の回帰なし（zero-write確認）

Given antecedentなしのclean HOME history
When 直接 `APP_SWITCH` を押す
Then 従来どおり1枚以上のカードが表示される（書込み経路の追加なし。
本変更はview層の計算とメソッド呼び出しのみでDB/設定への書込みを含まない）

## Verification

- **実機owner確認（必須・emulatorは代替ではなく補助）**: Scenario 1/5を
  ownerが実機で確認（screenshot/recording、device/build/head SHA添付）して
  merge判断する。emulator証跡（FG8/FG9/FG10 oracle GREEN 3/3、FC2 control、
  per-checkpoint dump + events + log extract + screenshot）は補助証跡として
  evidence directoryに恒久化済み。
- unit test: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests
  'com.android.launcher3.PagedViewScreenCenterTest'` — guardなしでRED
  （expected 4005 but was 0）、修正後GREEN 4/4。organizer-unit-tests gateに
  routing済み（ci.yml filter明示追加）。
- bug oracle: driver `oracle-driver.sh`（evidence正本）でGREEN 3/3
  （FG8/FG9/FG10、全時点 `task_view_single=2`）。RED baseline BB1
  （全時点0、node counts添付）。除外run（FG7: TIS rebind gapでtoggle未到達）
  は logcat `goToState` 件数0で理由を実証。
- 書込み経路なし: diffは `src/com/android/launcher3/PagedView.java`、
  `quickstep/src/com/android/quickstep/views/RecentsView.java`、
  `quickstep/src/com/android/quickstep/views/TaskContainer.kt`（cherry-pick +
  `: Unit`）、cherry-pickされた `TaskOverlayFactoryImpl`、`tests/unit/...`。
- 上流UI bridge計測: stack branchのためcandidate HEAD直接計測は不可
  （upstream ancestor制約、#561と同条件）。`--verify` はbaseline PASS、
  本PR固有差分は PagedView+17行 / RecentsView+6行 / TaskContainer `: Unit` 4行。

## Accessibility and localization

- pivot NaNは`boundsInScreen`をNaN化しoverview subtree全体をaccessibility
  treeから落としていた。本修正でpanel subtreeが復帰する（oracleの
  `task_view_single` node復帰が実測）。文言・フォントスケーリングへの影響なし。

## Test audit record

- Protected contract: unset (NaN) pivot時のscreen-center算術（=可視set計算と
  a11y boundsの前提）。
- Canonical owner: `tests/unit/com/android/launcher3/PagedViewScreenCenterTest`
  （最低層・決定的境界）。**TaskContainer式bodyのfailure modeはunit ownerの
  対象外** — 構造的防止（`: Unit` compile error）で保護する。
- CI routing: organizer-unit-tests gateへfilter明示追加（Permanent）。
  新laneなし。`validate_ci_portfolio.py` PASS。
- Emulator oracleはIssue証拠（CI laneではない）。

## Change history

- 2026-10-11: Draft created for #563 (bisection: BB4/FD8/FE4/FF1、oracle
  RED→GREEN: BB1 vs FG4/FG5/FG6)。
- 2026-10-11: Review round 1対応 — oracle基準をnode counting・全時点判定・
  除外runの実証条件へ厳密化（GREEN 3/3はFG8/FG9/FG10で取り直し）、実機owner
  確認を必須と明記、`: Unit` 再発防止をScopeへ追加。
