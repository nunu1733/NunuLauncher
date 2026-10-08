---
issue: "#555"
status: proposed
tier: M
requirements: []
updated: 2026-10-08
---

# Overview task cardに実app screenshotが表示される（TaskContainer式body修復）

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
`quickstep/src/com/android/quickstep/views/TaskContainer.kt` 内の式body誤り
（`fun ...() = { ... }` がlambdaを返し本体が実行されない）の修復と、API 37での
overview task card screenshot oracleのみを扱う。#554（recents parcel decoder）は別契約で、
本specのruntime検証はその修正を含む検証treeで行う（Verification節）。

## Problem

#555の記載どおり、API 37のoverviewで `ThumbnailData.makeThumbnail` のwrapToBitmap分岐は
正常にHARDWARE bitmapを返すのに、非実行taskのcardにapp screenshotが描画されない。
診断（branch `issue-555-diagnosis`、evidence 555-api37-overview-card-evidence/README.md）で
root causeはthumbnail pipelineではなく、`TaskContainer.setState` のKotlin式body誤りと確定した:
`fun setState(...) = { ... }` は本体を実行せずlambdaを返すため、
`TaskView.updateTaskViewState` から呼ばれても `TaskThumbnailView` へのstate適用が全くなされない
（TTCache/makeThumbnail がHARDWARE 864x1939を供給し `Log.e(Nunu555)` の一次出力で確認。
TaskContainer.setState直前logは出るが関数本体logは出ない。`= {` → `{` のみのパッチで
`TTV.setState ... SnapshotSplash bmp=HARDWARE` が出てcard描画が復帰）。
なお同じ式body誤りが同fileの `bind()` / `destroy()` / `refreshOverlay()` にもある
（同一欠陥字面。ただし本specでは修正しない — Non-goals。#556で追跡する）。

## Benchmark

編集負担ベンチマーク課題には該当しない（B1〜B7はホーム編集導線の負担測定である）。
本specはoverview card renderingのregression修復であり、編集操作経路に変化がない。
本specはoverview card renderingのregression修復で、編集操作経路に変化がない）。
理由を明示する規約要件を満たすためここに記載する。

## Prior art

- AOSP Launcher3 mainの同一file `quickstep/src/com/android/quickstep/views/TaskContainer.kt`
  では `setState` に相当するstate適用・`bind()` / `destroy()` がblock bodyで定義されている
  （https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/quickstep/src/com/android/quickstep/views/TaskContainer.kt
  、確認日2026-10-08、採用: 式bodyをblock bodyへ戻す根拠）。
- 本forkの状態は `9b90822395`～`#532 Phase 2 rebase` 経由で取り込んだ16-dev snapshot
  （blame: `fd57876c7c0` / `9b90822395d`）に由来し、式bodyはそのsnapshot時点の壊れである。

## Outcome

非実行taskのoverview cardに、そのtaskの保存済みTaskSnapshot（wrapToBitmap由来のbitmap）が
描画される。実行中task（live tile）と非実行taskのどちらのcardも、
タップで対象taskへ復帰できる。ViewPool再利用時に前taskのstateが残存しない。

## Scope

- `TaskContainer.setState` の式body `= {` をblock bodyへ戻す（1行の記号修正。ロジック変更なし）。
- API 37（emulator）でのoverview card screenshot oracle検証。

## Non-goals

- #554のrecents parcel decoder（並行PRに正本がある。本specの検証はその修正を含むtreeで行う）。
- 同fileの `bind()` / `destroy()` / `refreshOverlay()` の同型式body（本件とは別の影響面
  （ViewPool再利用・overlay位置・toast bind）を持ち、失効時の振る舞い検証が必要なため
  #556で追跡する）。
- `TaskThumbnailViewDeprecated` 系・`enableRefactorTaskContentView()` 経路の新規改修。
- green accessibility outline等、TAPL/accessibility挙動の変更。
- API 36での追加oracle（同一欠陥はAPI共通だが、#559のAPI 36 leg計画があるため本specでは37のみ）。

## Behavior scenarios

task名はoracle変数ではなく条件説名である。実検証で使用した組み合わせ:
非実行task=Settings（task 153）/実行task=Clock（task 154）。
非実行taskのsnapshot表示とtap復帰が本specの受入面であり、特定app名には依存しない。

### Scenario: 非実行taskのcardにscreenshotが表示される

Given API 37 provider構成のemulatorで、非実行task（process終了済み）と実行taskがrecentsに存在する
When KEYCODE_APP_SWITCHでoverviewを開く
Then 非実行taskのcardにそのTaskSnapshotが描画される
And 実行taskのcardも表示される

### Scenario: card tapでtask復帰

Given overviewが開いた状態
When 非実行taskのcardをtapする
Then 該当taskがforegroundに復帰する

### Scenario: 書込み経路なし（zero-write）

Given 本diffを適用したtree
When 任意の操作を行う
Then Launcher DB（favorites等）への書込み経路はdiffに存在しない

## Verification

- owner確認用のスクリーンショットは **実機（emulator API 37で取得）** する。
  emulatorは本検証の正本観測面である（既存evidence matrixと同一のAVD/runtime手順）。
- 検証tree: `issue-555-overview-card-fix` headに#554 decoder修正を一時mergeした
  diagnostic tree（`issue-555-diagnosis` branch。diagnostic log込み）。
  理由: #554 parcel decoder未達下ではrecents遷移がtakeover失敗でwedgeし、
  overview到達自体が不安定になるため。#554 merge側は本specの対象差分に含めない。
- `./gradlew spotlessCheck` ＋ `assembleLawnWithQuickstepGithubDebug` 成功。
- 書込み経路なしの確認: diff対象pathは `quickstep/src/com/android/quickstep/views/TaskContainer.kt`
  のみ（specs/docsを除く）。
- upstream patch surface:
  AOSP由来quickstep fileへのbridge変更のため、
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  をPR本文へreportする。

## Accessibility and localization

- 変更は既存render経路の復元であり、label/フォーカス体系は不変。
  TalkBack実施はしない（oracleはscreenshot＋比較。accessibility outline（green box）は
  修正前後で同一機能と観測）。

## Change history

- 2026-10-08: Draft created for #555（diagnostic確定後）。
- 2026-10-08: Review round 2対応 — Behavior scenarioをtask名非依存へ一般化（実evidenceは
  非実行=Settings(153)/実行=Clock(154)）、ViewPool stale-state scenarioを#556側へ移設
  （本specの受入面から外す）。evidence README（PR #561 commit `61cc01760b` 以降のpermalink）との
  traceabilityはそちらを正本とする。
