---
issue: "#562"
status: draft
tier: M
requirements: []
updated: 2026-10-09
---

# TaskContainerのbind/destroy/refreshOverlayが実行される（ViewPool再利用・overlay・toast経路）

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
`quickstep/src/com/android/quickstep/views/TaskContainer.kt` 内の式body誤り
（`fun ...() = { ... }` がlambdaを返し本体が実行されない）のうち #555 PR #561 で修復済みの
`setState` 以外の3関数の修復と、そのruntime確認のみを扱う。

## Problem

`TaskContainer.kt` の `bind()` / `destroy()` / `refreshOverlay(thumbnailPosition)` が
Kotlin式bodyになっており、if/else本体を含むlambdaを返すだけで本体が一度も実行されない
（#555診断で確定した `setState` と同型。blame上は16-dev snapshot `fd57876c7c0` / `9b90822395d`
経由で取り込まれた同じ欠陥。upstream mainではblock body）。

呼び出し経路（branch `issue-545-api37-provider-fix` 実測）:

- `bind()`: `TaskView.kt` のtask bind処理（`container.bind()`）から呼ばれる。
  digitalWellBeingToastのbindとdeprecated thumbnail viewのbindが実行されない。
- `destroy()`: `TaskView.kt` のrecycle/reuse経路（`taskContainers.forEach { it.destroy() }`。
  Runnable実装）から呼ばれる（ViewPool.Reusable）。TaskView再利用時に
  `thumbnailView.onRecycle()` / `overlay.reset()` / `isThumbnailValid=false` /
  `thumbnailData=null` が実行されず、stale stateとoverlay残留の可能性。
- `refreshOverlay()`: 同file `setOverlayEnabled()` および `TaskView.onBind` の
  `doOnSizeChange` コールバックから呼ばれる。overlay位置更新・init/resetが
  実行されず、menu位置ずれ・overlay非表示の可能性。

#555 fix（`setState` block body化）によりTaskView再利用経路が再び活性化するため、
`destroy()` が効かないstale stateリスクが顕在化し得る。

また、runtime確認（本spec Verification節。2026-10-10実施）で、
`refreshOverlay` の修復により `overlay.initOverlay()` が再び到達可能になることが
判明した。forkの `TaskOverlayFactoryImpl.TaskOverlay.initOverlay` は従来
`mTaskContainer.thumbnailViewDeprecated.isRealSnapshot`
を無条件に参照しており、refactor task-thumbnail flag
（`com.android.launcher3.enable_refactor_task_thumbnail`。本forkは `FeatureFlagsImpl`
で常時true。16-dev rebase初期に#532でdefault true）経路では
`TaskContainer.getThumbnailViewDeprecated` の `require` にsha違反し、
launcherがcrashする（修復前にDead lambdaがこの不整合を隠していた。
一次出力: `docs/assessment/562-taskcontainer-expression-body/42-postfixv1-crash-refreshOverlay-initOverlay-stack.txt`。
修復の拠所はbase class `TaskOverlayFactory.TaskOverlay` のflag-aware
`isRealSnapshot()` である）。このfork側1行修復を本specのruntime legに含める。

## Benchmark

編集負担ベンチマーク課題には該当しない（B1〜B7はホーム編集導線の負担測定である）。
本specはoverview cardのview state申請系regression修復であり、編集操作経路に変化がない。
理由を明示する規約要件を満たすためここに記載する。

## Prior art

- AOSP Launcher3 mainの同file `quickstep/src/com/android/quickstep/views/TaskContainer.kt`
  では `bind()` / `destroy()` がblock bodyで定義されている
  （確認日2026-10-08。一次出力: `docs/assessment/555-api37-overview-card-evidence/40-upstream-main-taskcontainer-refs-heads-main-2026-10-08.kt.txt`。採用: 式bodyをblock bodyへ戻す根拠）。
- #555で修復済みの `setState` も同一patternsの1行修復で復旧を確認済み
  （specs/555-api37-overview-card-state/spec-lite.md。採用: 同型欠陥の同一修復方針）。

## Outcome

TaskViewが再利用（recycle/rebind）されても、前taskのthumbnail/overlay/splash状態が次taskへ
残留せず、cardがそのtask自身の状態で描画され直す。`setOverlayEnabled()` の呼び出しが
実位置更新につながり、overlay（menu配置基準）がtaskの実際位置に載る。
digitalWellBeingToastのbind経路が復活する（表示条件はUsageStats session等の実行時条件に従う）。

## Scope

- `TaskContainer.bind` の式body `= {` をblock bodyへ戻す（1行の記号修正。ロジック変更なし）。
- `TaskContainer.destroy` の式body `= {` をblock bodyへ戻す（同上）。
- `TaskContainer.refreshOverlay` の式body `= {` をblock bodyへ戻す（同上）。
- `TaskOverlayFactoryImpl.TaskOverlay.initOverlay` の 無条件
  `mTaskContainer.thumbnailViewDeprecated.isRealSnapshot` 参照を、
  base classのflag-aware `isRealSnapshot()` 呼び出しへ変更する（1行。fork側bridge修復）。
  これは `refreshOverlay` 修復により初めて到達可能になった経路のcrash修復であり、
  修復3関数のruntime確認と同一検証セットで完結させる。
- API 37（emulator）での実行確認（Verification節）。

## Non-goals

- `MainThreadInitializedObject` / `ViewPool-init` のANR deadlock観測（#556。別系統）。
- #554 recents parcel decoder、#559 API 36 leg。
- `TaskThumbnailViewDeprecated` / `enableRefactor*()` 経路の新規改修。
- digitalWellBeingToast・overlayの機能追加や表示仕様変更（既定経路の復元のみ）。
- API 36での追加oracle（同一欠陥はAPI共通だが#559のAPI 36 leg計画があるため本specでは37のみ）。

## Behavior scenarios

### Scenario: TaskView再利用時に旧task状態が次taskへ残留しない

Given API 37捉供構成のemulatorで、overviewのcardがTaskView再利用（ViewPool）を通る
When overview → 他task復帰 → overview遷移を繰り返す
Then 再利用されたTaskViewのcardは、そのtask自身のthumbnail・overlay状態で描画される
And 診断log上で `destroy()` / `bind()` の本体が実行されたことが確認できる

### Scenario: overlay位置更新が実行される

Given overviewまたはmenu展開を含む通常操作
When `setOverlayEnabled(enabled, thumbnailPosition)` が呼ばれる
Then `refreshOverlay()` 本体が実行され、overlay位置がthumbnailPositionに整合する

### Scenario: overlay init経路（enabled=true）でlauncherがcrashしない

Given refactor task-thumbnail経路（本forkのdefault設定）でoverviewが開いた状態
When 中央task cardがoverlay有効状態（`enabled=true`）に遷移する操作（card press等）が行われる
Then `overlay.initOverlay()` が実行され、launcherはcrashしない
（修復前の証跡: `initOverlay` 内deprecated view参照による
`IllegalArgumentException: Failed requirement.`。#562 runtime legで検出）

### Scenario: digitalWellBeingToastのbind経路が復活する

Given digitalWellBeingToast（AppTimer）のターゲットが存在するoverview
When `bind()` が呼ばれる
Then 本体が実行され、表示条件が揃う場合toastがcardに表示される
And 条件が揃わない場合は既存の非表示挙動に従う（機能追加なし）

### Scenario: 書込み経路なし（zero-write）

Given 本diffを適用したtree
When 任意の操作を行う
Then Launcher DB（favorites等）への書込み経路はdiffに存在しない

## Verification

- owner確認用のスクリーンショットは **emulator API 37で取得** する
  （AVD `issue526_api37_pixel_9a`。#555/#545 evidence matrixと同一のAVD/runtime手順。
  emulatorは本検証の正本観測面である）。
- 実行証跡: 本体実行の有無は #555と同型の診断tree（一時log `Nunu562`、
  `issue-562-diagnosis` / `issue-562-diagnosis-prefix` branchで事前事後を分離）の
  logcat excerptで判断し、fix tree自体は記号修正に限定する。
  caller側（`TaskView.onRecycle` / `TaskView.call-bind` / `call-destroy` /
  `TaskContainer.setOverlayEnabled`）と本体実行の以前後を同一runで対照する。
- `./gradlew spotlessCheck` ＋ `assembleLawnWithQuickstepGithubDebug` 成功
  （head `7a8ef68fdd` spotless PASS / build PASS。APK sha256はevidence README参照）。
- 書込み経路なしの確認: diff対象pathは
  `quickstep/src/com/android/quickstep/views/TaskContainer.kt`（3行記号修正）、
  `lawnchair/src/app/lawnchair/overview/TaskOverlayFactoryImpl.kt`（1行bridge修復）
  とspec/evidence文書のみ。Launcher DBやpref書込み経路は追加しない。
- 上流のUIだけに触れるbridgeを含むため（AOSP由来quickstep fileへのbridge変更）、
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  によるcandidate HEADの計測結果を **PR本文** へreportする。
- digitalWellBeingToastの表示確認: toast本体は `LauncherApps` のAppUsageLimit
  （screen time limit）が存在するtaskでのみ表示される（`DigitalWellBeingToast.setLimit`）。
  検証環境（API 37 emulator）ではusage limit を設定するshell経路がなく表示を誘発できないため、
  `bind()` 本体実行のcaller/body照合（log）を主証跡とし、表示そのものは
  実limit設定環境での確認に委ねる（残差をIssueへ記録）。

## Accessibility and localization

- 変更は既存render/bind経路の復元であり、label/フーカス体系は不変。
  TalkBack実施はしない（oracleはlog＋screenshot。accessibility outlineは修正前後で同一機能と観測）。

## Change history

- 2026-10-09: Draft created for #562（#555診断成果の同型欠陥追跡）。
- 2026-10-10: runtime leg確定 — `refreshOverlay` 修復により活性化する `initOverlay`
  経路のcrash（deprecated view無条件参照）を検出し、
  `TaskOverlayFactoryImpl` の1行bridge修復をScopeへ追加。検証節へ実測tree/APK sha256を反映。
