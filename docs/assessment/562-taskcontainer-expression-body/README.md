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
caller呼び出し 1回につき本体が1回実行される1:1の状態へ復旧した。**

修復前後の同一手順でのcaller/body対照（一時diag log `Nunu562` を
diagnostic branch「pre-fix / post-fixの2本」で分離して取得）:

| 計測 | caller（修復前後とも同数） | 本体 pre-fix | 本体 post-fix |
|---|---|---|---|
| `TaskView.call-bind` → `bind()` | 16 | **0** | **16** |
| `TaskView.onRecycle` + `call-destroy` → `destroy()` | 11 (11) | **0** | **11** |
| `TaskContainer.setOverlayEnabled(...)` → `refreshOverlay()` | 16 | **0** | 14（※1） |

一次出力: pre-fix = `30-prefix-nunu562-full.txt`（2026-10-09 23:56、pid 4396）。
post-fix = `60-postfixv2-nunu562-full.txt`（2026-10-10 00:38、pid 1234）。
両诊断treeのheadとAPK sha256は「検証tree / APK sha256」節参照。

※1 post-fix数値: `refreshOverlay` には `setOverlayEnabled` 以外の呼び出し元
（`TaskView.onBind` 内 `doOnSizeChange` コールバック、TaskView.kt:1027/1035）があるため、
caller側の `setOverlayEnabled` カウントと一致しない。正常な差である。

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
| **PR head（修復完了 tree）** | `issue-562-taskcontainer-expression-body` head `7a8ef68fdd` | `c1b336144844643684c0af08b9ea64379009f46cb34c3b761c81dbf425eaf8e5` |

PR head treeでの最終確認:

- `./gradlew spotlessCheck`: **PASS**
- `./gradlew assembleLawnWithQuickstepGithubDebug`: **PASS**
- 一時diag logを含まないPR headのrendering確認は、同じlog-apart構成である
  v2 diag tree（head `325b82cd95`、diffは`TaskView.kt`のt-4 log行のみ）で実施した。
  理由は#555と同じ（本体実行の有无を直接認めるにはlog attachが必要なため）。
- 一時diag log実行 logcat全文: `61-postfixv2-full-logcat.txt`
  （AndroidRuntime FATAL EXCEPTION grep: **0件**）。

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
- **修復後**: v2 treeで同一経路（`setOverlayEnabled enabled=true` → initOverlay、
  00:38:12.659にlog確認）を通過し FATAL 0件（§検証treeの `61-` 参照）。

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
