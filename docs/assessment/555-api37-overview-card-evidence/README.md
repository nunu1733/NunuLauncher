# Issue #555 runtime診断evidence — overview thumbnail pipeline root cause（TaskContainer式body欠陥）

- 実施日: 2026-10-08
- 対象Issue: [#555](https://github.com/nunu1733/NunuLauncher/issues/555)
  （[Follow-up][Phase 3] wrapToBitmap分岐実行もbitmapがcardへ到達しない／黒fallback継続のroot cause特定）
- 階層M spec: `specs/555-api37-overview-card-state/spec-lite.md`（proposed → fix branch PRでreview）
- この記録は検証証跡であり、正本の判断はIssue #555 / specに従う。

## 結論（root cause）

**thumbnail pipelineは正常である。途絶しているのはviewへのstate適用である。**

root cause: `quickstep/src/com/android/quickstep/views/TaskContainer.kt` の
`fun setState(...) = { ... }` がKotlinの**式body**として解釈され、if/else本体を含むラムダを
返すだけで本体が一度も実行されない。`TaskView.updateTaskViewState` から
`container.setState(...)` が呼ばれても戻り値lambdaは破棄されるため、
`TaskThumbnailView.setState`（thumbnail適用・live tile・splash・background切替）が
全く呼ばれない。API 37に限らずAPI 36でも同一である（#524 README leg (e)
`e-overview-api36.png` はcard無しのchip＋wallpaper表示で、当時はoracleが「card表示」を
要求していないため黒card問題は検出されなかった）。

同fileの `bind()` / `destroy()` / `refreshOverlay()` も同型の式bodyであるが
（blame: 16-dev snapshot `fd57876c7c0`/`9b90822395d`経由で取り込まれた欠陥）、
影響面（ViewPool再利用・overlay位置・toast bind）が異なるため本fixでは触れず #556で追跡する。

## 一次出力（log oracle。全てこのsession取得）

| step | 証跡 | 観測 |
|---|---|---|
| (1) pipeline入口 | `32-post-fix-r7r8-nunu555-excerpt.txt`（対照: `30-pre-fix-d1-nunu555-excerpt.txt`） | `makeThumbnail sdk=37 constructor=android.window.TaskSnapshot hwBuffer=null result=Bitmap(cfg=HARDWARE 864x1939 hw=true)` が複数回 — wrapToBitmap reflectionは正常にHARDWARE bitmapを返す |
| (2) cache/data flow | 同上 `TTCache.getThumbnail ... systemFetch=HARDWARE 864x1939`、`31-pre-fix-r4-full-logcat.txt` | ThumbnailData→TaskThumbnailCache→TasksRepository flowでbitmapが保持されたまま流れる |
| (3) fix前の途絶 | `31-pre-fix-r4-full-logcat.txt` | `TaskView.updateTaskViewState ... thumb=HARDWARE 864x1939` は出るが、直後の `TaskContainer.setState` 本体log（TaskContainer.kt内部の `Log.e(Nunu555)`）も `TTV.setState` も **0件**。stateがlambdaとして捨てられている直接的証拠 |
| (4) fix後に復帰 | `32-post-fix-r7r8-nunu555-excerpt.txt` | `TaskContainer.setState task=153 ... thumb=HARDWARE` → `TTV.setState task=153 state=SnapshotSplash bmp=HARDWARE 540x1211` 到達。card描画が復帰（`20-`〜`23-*.png`） |
| (5) screenshot oracle | `22-r7-after-fix-dead-settings-card-rendered.png` | 非実行（force-stop済み）Settings taskのcardに **実app screenshot** が描画されている。tap→Settingsがforeground復帰（`23-`、focus=`com.android.settings/.Settings`、BadParcelableException 0） |
| (6) 上流対照 | `40-upstream-main-taskcontainer-response.json`（base64。AOSP main `TaskContainer.kt`） | AOSP upstream mainでは同関数がblock bodyで定義済み（差分は本fork/16-dev snapshot側の欠陥） |

## runtime対照

| phase | 構成 | 判定 |
|---|---|---|
| pre-fix | branch `issue-555-diagnosis` head `9e89c1d` debug（Nunu555 log込み。#554 parcel decoder込みtree）debug priv-app @ API 37 emulator-5554 | **FAIL** — dead taskのcardが空（`12-` `13-`）。`TaskView.updateTaskViewState` で thumb=HARDWARE を受けるにも関わらず TaskThumbnailView未適用 |
| post-fix | `9e89c1d` に対する1行パッチ（fix commit `4a8508498b`相当。diagnosis treeにはcherry-pick `acaa4d7`） debug priv-app | **PASS** — dead Settings cardへ実screenshot表示＋live Clock card表示＋tap復帰 |

パッチは1行（`fun setState(...) = {` → `fun setState(...) {`）で、revertで即rollback可能。

## API 37構成（再現手順）

- AVD: `issue526_api37_pixel_9a` / fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`
- debug priv-app: `/product/priv-app/LawnchairDebug/LawnchairDebug.apk` ＋
  `/product/etc/permissions/privapp-permissions-app.lawnchair.debug.xml`（55 permission）
- RRO: `/product/overlay/QuickstepConfigOverlay.apk` 値 `app.lawnchair.debug/com.android.quickstep.RecentsActivity`
  （SystemUI `mRecentsComponentName` 一致、`isConnected=true` preflight完了）
- overview発火: `KEYCODE_APP_SWITCH`
- verify: `adb reboot` 待ち → `dumpsys window | grep mCurrentFocus`、`screencap`、
  `logcat | grep Nunu555`、uiautomator dump card bounds

## 検証treeおよびAPK

| 種別 | SHA / APK sha256 |
|---|---|
| 修正前対照（#545 v5 candidate） | `da15f4b2e7`（#545 README表参照） |
| fix前tree | `9e89c1d5`（diagnosis branch。Nunu555 log込み、#554 parcel decoder込み） |
| **fix後検証tree** | `acaa4d7a`（diagnosis branch上にcherry-pick `4a8508498b`） |
| fix後検証APK | `Lawnchair.16.Dev.(acaa4d7).github.debug.apk` sha256 `5823cdaa0f9c71f8cfc606f3dd599f0f97fc1658d7fbd82c3ce2ca38bc43a767` |
| fix branchコミット | `4a8508498b` fix(555): restore TaskContainer.setState block body...（`issue-555-overview-card-fix`） |

検証APK実物はcommitしない（sha256記載。実物は検証session `/tmp/nu555/`）。

## 検証時の逸脱（記録のみ）

1. **本検証中のlauncher process ANR 2回**（`/data/anr/anr_2026-10-08-17-37-46-828`）:
   `LawnchairLayoutFactory.fontManager` lazy lock（main thread）×
   `ViewPool-init` background thread（`MainThreadInitializedObject.get()` 待ちのvisibility inflate）の
   deadlock。#555 fixとは無関係で、AVD再起動後は未発生（r2〜r6の連続検証では発生ゼロ）。
   別Issue起票を推奨する（本spec範囲外）。
2. #554 parcel decoder未merge treeではrecents遷移がtakeover失敗でwedgeし、
   overview到達自体が不安定になる。そのため **検証は#554込みtreeで行い、
   本fix branchにはmergeしていない**（decoder正本は#554 PR #557 branch）。
3. 検証シーケンスで `am force-stop com.android.settings` を使用した（dead card生成のため）。
   影響なし（全oracle正常成立）。

## 未達と引継ぎ

- 同fileの `bind()` / `destroy()` / `refreshOverlay()` の式body修復 → **#556**（要起票）。
  影響: ViewPool再利用時のstate残存・overlay位置・digitalWellBeingToast bind。
- API 36 leg（同一欠陥の36上確認）: #559に含めて検討（既存branchあり）。
- `quickstepMaxSdk` 36→37とADR-0018 range反映の可否判断は、#554 merge＋本fix＋#556後の
  再検証（#545継続）で行う。

## 証跡file一覧

```
11-d1-live-only-overview.png                     d1 run（fix前）live tileのみのscreen
12-r3-before-fix-dead-card-empty.png             fix前: dead task cardが空（green outlineのみ）
13-r5-before-fix-dead-card-empty.png             同上（別run）
14-554-run1-overview-reference.png               #554 run1 overview（qva2対参照）
20-r6-after-fix-clock-card.png                   fix後: Clock cardに正しいsnapshot表示
21-r7-after-fix-live-clock-center.png            fix後: live Clock中央表示
22-r7-after-fix-dead-settings-card-rendered.png  fix後: dead Settings cardに実screenshot表示（本命oracle）
23-r7-after-tap-task-returned.png                fix後: card tap → Settings foreground復帰
30-pre-fix-d1-nunu555-excerpt.txt                fix前 log excerpt（d1）
31-pre-fix-r4-full-logcat.txt                    fix前 full logcat（TaskView log確認用）
32-post-fix-r7r8-nunu555-excerpt.txt             fix後 r7/r8 log excerpt
40-upstream-main-taskcontainer-response.json     upstream main TaskContainer.kt raw（base64、対照用）
50-build2-sample.log                             build log sample
```
