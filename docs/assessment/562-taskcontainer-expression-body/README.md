# Issue #562 runtime確認evidence — TaskContainer bind/destroy/refreshOverlay 式body修復

- 実施日: 2026-10-09 〜 2026-10-10
- 対象Issue: [#562](https://github.com/nunu1733/NunuLauncher/issues/562)
  （[Follow-up]: TaskContainer.bind/destroy/refreshOverlayの式body欠陥修復）
- 階層M spec: `specs/562-taskcontainer-expression-body/spec-lite.md`（draft。PRでreview）
- 診断先例: #555（`setState` 式body欠陥、`docs/assessment/555-api37-overview-card-evidence/README.md`）
- この記録は検証証跡であり、正本の判断はIssue #562 / specに従う。

## 結論

**3関数（`bind()` / `destroy()` / `refreshOverlay()`）は、caller側呼び出しが常時
発生するにもかかわらず本体が一度も実行されない状態だった。block body化により、
本体が実行される状態へ復旧した（bind/destroyは実callと本体1:1。refreshOverlayは
複数callerのため1:1主張をしない — 下表参照）。**

修復前後の同一手順でのcaller/body対照（一時diag log `Nunu562` を
diagnostic branch「pre-fix / post-fixの2本」で分離して取得）:

| 計測 | caller（呼び出しsite記録） | 本体 pre-fix | 本体 post-fix |
|---|---|---|---|
| `TaskView.call-bind` → `bind()` | 16 | **0** | **16**（無条件の単純call edgeの為caller数=実call数。caller/body 1:1成立） |
| `TaskView.onRecycle` + `call-destroy` → `destroy()` | 11 | **0** | **11**（同上。1:1成立） |
| `TaskContainer.setOverlayEnabled(...)` → `refreshOverlay()` | 16 | **0** | 13〜14（※1） |

一次出力: pre-fix = `30-prefix-nunu562-full.txt`（2026-10-09 23:56、pid 4396）。
post-fix = `60-postfixv2-nunu562-full.txt`（2026-10-10 00:38、pid 1234）。
両診断treeのheadとAPK sha256は「検証tree / APK sha256」節参照。

※1 `refreshOverlay` には複数callerがある: (a) `setOverlayEnabled(enabled, thumbnailPosition)`
内部（overlayEnabledStatus/position変化時のみ実call）、(b) `TaskView.onBind` 内
`doOnSizeChange` コールバック（TaskView.kt:1027/1035）。よって `setOverlayEnabled`
入口ログ数と本体実行数の直接比較（1:1主張）は本計測では成立しない。
**本項の主oracleは「pre-fixで本体0回 → post-fixで本体実行を確認」+
`setOverlayEnabled enabled=true` → `overlay.initOverlay()` 到達（FATAL 0件 — crash節）**
である。実callと本体の厳密な1:1対応が必要な場合はcall直前にcaller logを
置いて再計測する（本specでは実施しない）。

## 検証環境

- API 37 emulator `issue526_api37_pixel_9a`
  fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`
  （`63-device-fingerprint.txt`）
- debug priv-app構成（#555 evidence READMEと同一）:
  `/product/priv-app/LawnchairDebug/LawnchairDebug.apk` ＋
  `/product/etc/permissions/privapp-permissions-app.lawnchair.debug.xml` ＋
  RRO `/product/overlay/QuickstepConfigOverlay.apk`
  （`config_recentsComponentName = app.lawnchair.debug/com.android.quickstep.RecentsActivity`）
- シナリオ: recentsに `Settings` / `DeskClock` / `YouTube` が存在、
  `Settings`を`am force-stop`で非実行化、`KEYCODE_APP_SWITCH`でoverview表示。
  overview開閉とcard遷移の操作を数回繰り返してViewPoolのrecycle/reuse通過を誘発。

## 検証tree / APK sha256

| tree | branch / head | APK sha256 |
|---|---|---|
| pre-fix diag | `issue-562-diagnosis-prefix`（`5b4d48b42f` = base `d0ed8645cc` + 一時diag/log） | `c330b99f3c0683d6f310c1365a3a40d5d81ffa8a0148944e99335830282f8380` |
| post-fix diag | `issue-562-diagnosis` head `325b82cd95`（fix `a37af58430` 本＋ caller logs ＋ `TaskOverlayFactoryImpl` 修復込み） | `cfedfc042b76ff6c5ec316a09693eab95024a67f8bc049bc7a03f27d1a2ae4fb` |
| **PR head（修復完了 tree）** | `issue-562-taskcontainer-expression-body` head `f93d29a619`（review round 2反映） | `7a680861853b05c72ecddb38a4d5ddd22fee24153d73ac0818b34f9a4f0e78ea` |
| （初回PR head。round 1レビュー前の rescinded head） | `7a8ef68fdd` | `c1b336144844643684c0af08b9ea64379009f46cb34c3b761c81dbf425eaf8e5` |

PR head treeでの最終確認（round 2 leg。v3診断treeで実施、下節参照）:

- `./gradlew spotlessCheck`: **PASS**（head `f93d29a619`）
- `./gradlew assembleLawnWithQuickstepGithubDebug`: **PASS**
- 一時diag logを含まないPR headの行动確認は、log付構成の
  v2/v3診断tree（diffは一時log行のみ）で実施した。
  理由は#555と同じ（本体実行の有无を直接認めるにはlog attachが必要なため）。
- 一時diag log実行 logcat全文（round 2 leg）: `83-postfixv3-full-logcat.txt`
  （AndroidRuntime FATAL EXCEPTION grep: **0件**）。

## Review round 2 leg（initOverlay flag-gating修復後の再確認）

review round 1 finding 3へ対応し、`TaskOverlayFactoryImpl.initOverlay` の
2つの `updateDisabledFlags` を `enableRefactorTaskThumbnail()` 側で限定した
（base `TaskOverlayFactory.TaskOverlay.initOverlay` と同一構造。refactor pathでは
`TaskView.updateTaskViewState` がflags担当）。この变更で新たに挙動が変わる
`refactor=true` 経路の最小oracleを取得するため、再runtime legを実施した。

| 計測 | 値 / 証跡 |
|---|---|
| v3診断tree | `issue-562-diagnosis` head `bbd113dabe`（flag-gating修復込み。一時diag log行以外はPR headと同一差分） |
| v3 diag APK | sha256 `84376b8741b3abc9da96443dd9fe345c4831e4e56d450b6b6899db8a4a9df996` |
| caller/body照合 | caller-bind 16 / 本体16、onRecycle 11 / destroy 11（1:1）、`setOverlayEnabled` 8 / `refreshOverlay` 本体13、`enabled=true` 4件（`82-postfixv3-nunu562-full.txt`） |
| crash | FATAL 0件（`83-postfixv3-full-logcat.txt`） |
| actionsView oracle（最小） | overview下部のactions pill（「Screenshot」）が描画され、disabled flagsの固定による非活性化が起こっていない: `80-postfixv3-overview.png`。refactor=true経路ではflagsが `TaskView.updateTaskViewState`（central task側）から更新されることになり、initOverlay側の2重更新を除いた後も同経路でactionsの可視・非固定が維持される |

## ViewPool再利用（destroy() の効果）

`60-postfixv2-nunu562-full.txt` 冒頭時系列（10-10 00:38:10、pid 1234）:

```
TaskView.call-bind container → TaskContainer.bind task=195（YouTube）
TaskView.onRecycle → call-destroy → TaskContainer.destroy task=195
TaskView.call-bind → TaskContainer.bind task=195（同TaskViewがふたたび195へbind）
TaskView.call-bind → TaskContainer.bind task=194 / 193 / 72（別taskへの再bind）
```

同一TaskViewがtask 195でdestroyされた後、task 194/193/72として再度bindされている
（ViewPool経由の実際の再利用）。
`destroy()` がこの間に `thumbnailData=null` / `isThumbnailValid=false` /
`overlay.reset()` / `thumbnailView.onRecycle()` を実行しているため、
「旧taskのthumbnail/overlay状態が次taskに残留する」経路が取られている。
対応する描画の一次出力: `62-postfixv2-overview.png`。

## crash検出と `TaskOverlayFactoryImpl` bridge修復

- **検出条件**: v1 tree（3関数block body化のみ。2026-10-10 00:03）で、
  overview中央task cardをpressする操作を経由して `setOverlayEnabled enabled=true`
  が発生し、`overlay.initOverlay()` に到達した直後。
- **stack（dropbox保存実績）: `42-postfixv1-crash-refreshOverlay-initOverlay-stack.txt`**
  （PID 1206、tree `fe466c492a` / `16.Dev.(fe466c4)`）:

```
java.lang.IllegalArgumentException: Failed requirement.
	at com.android.quickstep.views.TaskContainer.getThumbnailViewDeprecated(TaskContainer.kt:99)
	at app.lawnchair.overview.TaskOverlayFactoryImpl$TaskOverlay.initOverlay(TaskOverlayFactoryImpl.kt:39)
	at com.android.quickstep.views.TaskContainer.refreshOverlay(TaskContainer.kt:163)
	at com.android.quickstep.views.TaskContainer.setOverlayEnabled(TaskContainer.kt:154)
```

- **説明**: fork側 `TaskOverlayFactoryImpl.TaskOverlay.initOverlay` が
  `mTaskContainer.thumbnailViewDeprecated.isRealSnapshot` を無条件に参照しており、
  refactor task-thumbnail flag（本fork `FeatureFlagsImpl.enableRefactorTaskThumbnail()`
  default true）の経路では `TaskContainer.getThumbnailViewDeprecated` の `require`
  に違反する。3関数の式body欠陥（dead lambda）が #532 Phase 2 以降
  この不整合を隠していた。
- **修復**: `TaskOverlayFactoryImpl.TaskOverlay.initOverlay` 内の参照を
  base classのflag-aware `isRealSnapshot()` 呼び出しへ変更（1行）。
- **修復後**: v2右v3 treeで同一経路（`setOverlayEnabled enabled=true` → initOverlay、
  v2:00:38:12.659 / v3:別港）を通過し FATAL 0件（§検証treeの `61-`/`83-` 参照）。
  round 2ではrefactor pathのflags更新をTaskView側に一本化するbase構造した変更後も
  同経路がFATAL 0で動作することを再確認し、actionsView oracleを取得（次節）。

## 残差（specにも記載）

1. **digitalWellBeingToastの視覚表示**: `bind()` 本体実行はcaller/body照合で確認済み。
   toast UIは `LauncherApps.AppUsageLimit`（screen time limit）が存在するtaskでのみ表示
   される（`DigitalWellBeingToast.setLimit`）。検証環境ではusage limitを設定する
   shell経路がなく表示を誘発できないため、**表示そのものは未確認**とし、
   実limit設定環境での確認をIssueへ残す（本specの機能対象外。bind経路の復元まで）。
2. **task menuの視覚的位置**: menu展開を引き起こす長押し操作が
   adb input harnessからはcard launchと区別せず反応せず、
   menu展開中のscreenshot証跡は取得できず。その内部経路である
   `refreshOverlay`→`overlay.initOverlay` の実行とcrash解消は上記の通りlogで確認済み
   （menuの視覚oracleは省略。理由をIssue/PRへ記載）。

## 本Issue Non-goalsに該当する観測（#556の参照値）

- 検証中のboot直後にlauncher processのANRが2回（00:23:49 / 00:25:51）発生し、
  うち1回は回復せずrebootで復旧した。stack excerpt（
  `70-anr-556-deadlock-main-excerpt.txt` ／ `71-anr-556-deadlock-holder-thread34-excerpt.txt`）は
  mainスレッドが `LawnchairLayoutFactory.getFontManager` のlazy lockを待ち、
  そのlock保持thread（34）が `TaskView.inflateViewStubs` → `MainThreadInitializedObject`
  経由でmainを待つ循環待ちであることを示す。
- #562 issueのNon-goals節が該当（MainThreadInitializedObject / ViewPool-initの
  ANR deadlock）。#556 で追跡する。本specの diff 前後で
  このANRの誘発/回避に変化はない（boot後HOME復帰→回復後に検証を実施）。

## 証跡ファイル一覧（同dir commit分）

| file | 内容 |
|---|---|
| `10-prefix-overview.png` | 修復前に取得したoverview（caller/body熱測時の画面） |
| `30-prefix-nunu562-full.txt` | 修復前diag log（caller only、本体0件。pid 4396） |
| `31-prefix-full-logcat.txt` | 修復前logcat全文 |
| `32-prefix-recents-summary.txt` | 修復前recents構成 |
| `42-postfixv1-crash-refreshOverlay-initOverlay-stack.txt` | v1 treeでのcrash stack（dropbox実績） |
| `60-postfixv2-nunu562-full.txt` | 修復後diag log（caller/body 1:1。pid 1234） |
| `61-postfixv2-full-logcat.txt` | 修復後logcat全文（FATAL 0件） |
| `62-postfixv2-overview.png` | 修復後overview（recycle/reuse後の描画確認） |
| `63-device-fingerprint.txt` | 検証AVD fingerprint |
| `64-postfixv2-recents-tasks.txt` | 修復後recents構成 |
| `70-` / `71-anr-556-*.txt` | #556（Non-goals）のANR deadlock stack excerpt |
