# Issue #555 runtime診断evidence — overview thumbnail pipeline root cause（TaskContainer式body欠陥）

- 実施日: 2026-10-08
- 対象Issue: [#555](https://github.com/nunu1733/NunuLauncher/issues/555)
  （[Follow-up][Phase 3] wrapToBitmap分岐実行もbitmapがcardへ到達しない／黒fallback継続のroot cause特定）
- 階層M spec: `specs/555-api37-overview-card-state/spec-lite.md`（proposed → fix branch PRでreview）
- この記録は検証証跡であり、正本の判断はIssue #555 / specに従う。
- 一次出力とREADMEの対応は task ID・bitmap size・run番号をこの文書末尾の一覧で相互突合する
  （2026-10-08 re-review round 2対応。本READMEが全文引用する excerptは落ち着いて**commit済みfile**であること）。

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

## 一次出力（r9検証run。`32-` / `33-` / `34-` / `35-` は同一run `acaa4d7` tree・同一AVD session連続）

| step | 証跡 | 観測 |
|---|---|---|
| (1) pipeline入口 | `32-post-fix-r9-overview-nunu555-excerpt.txt` | `makeThumbnail sdk=37 constructor=android.window.TaskSnapshot hwBuffer=null result=Bitmap(cfg=HARDWARE 540x1211 hw=true gen=2266)`（task 153＝非実行Settings用のoverview thumbnail） |
| (2) cache/data flow | 同上 `TTCache.getThumbnail task=153 ... systemFetch=HARDWARE 540x1211 hw=true` | ThumbnailData→TaskThumbnailCache→TasksRepository flowでbitmapが保持されたまま流れる |
| (3) fix前の途絶（対照） | `31-pre-fix-r4-full-logcat.txt`（pre-fix run。tree `9e89c1d5`） | `TaskView.updateTaskViewState ... thumb=HARDWARE 864x1939` は出るが、`TaskContainer.setState` 本体内の `Log.e(Nunu555)` も `TTV.setState` も **0件**。stateがlambdaとして破棄される直接的証拠 |
| (4) fix後に到達 | `32-post-fix-r9-overview-nunu555-excerpt.txt` 内の task 153行 | `TaskContainer.setState task=153 refactorContentView=false thumb=HARDWARE 540x1211` → `TTV.setState task=153 state=SnapshotSplash bmp=HARDWARE 540x1211`（初手の `state=BackgroundOnly bmp=null` → thumbnail到達後 `SnapshotSplash` へ遷移） |
| (5) screenshot oracle | `20-post-fix-r9-dead-settings-card-rendered.png` | 非実行（force-stop済み）**task 153 = Settings** のcardに実app screenshot表示。*peek状態は`22-post-fix-r9-dead-card-peek.png`*。tap→Settings foreground復帰は `21-post-fix-r9-after-tap-task-returned.png` ＋ `33-post-fix-r9-tap-return-nunu555-excerpt.txt`（focus=`com.android.settings/.Settings`、BadParcelableException 0件 — `35-post-fix-r9-tap-return-full-logcat.txt` 全文） |
| (6) 上流対照 | `40-upstream-main-taskcontainer-refs-heads-main-2026-10-08.kt.txt`（本文末尾のprovenance header参照） | AOSP upstream main `refs/heads/main` snapshotでは `bind()` / `destroy()` がblock body。`setState`相当関数は同snapshotでは存在しない（state適用はTaskContainerViewModel側に移設） — 本fork 16-dev snapshotとの差分の裏付け |

## task IDの対応（2026-10-08 r9 run実測。`dumpsys activity recents`）

- task **153** = `com.android.settings/.Settings`、検証前の `am force-stop com.android.settings` で**非実行**（dead）。
 overview thumbnailは lowRes（540x1211）であり `HARDWARE 540x1211`。
- task **154** = `com.google.android.deskclock/com.android.deskclock.DeskClock`、実行中＝live tile側。
- 本命oracle（非実行taskのcardへ実screenshot表示）は上表 step (5) の **task 153＝Settings**。

## runtime対照

| phase | 構成 | 判定 |
|---|---|---|
| pre-fix | branch `issue-555-diagnosis` head `9e89c1d` debug（Nunu555 log込み。#554 parcel decoder込みtree）debug priv-app @ API 37 emulator-5554 | **FAIL** — dead taskのcardが空（`12-r3-before-fix-dead-card-empty.png` `13-r5-before-fix-dead-card-empty.png`）。`TaskView.updateTaskViewState` で thumb=HARDWARE を受けるにも関わらず TaskThumbnailView未適用 |
| post-fix | `9e89c1d` に対する1行パッチ（fix commit `4a8508498b`相当。diagnosis treeにはcherry-pick `acaa4d7`）debug priv-app | **PASS** — dead Settings cardへ実screenshot表示＋live Clock（154）card live-tile表示＋tap復帰（r9 run。`20-` `21-` `22-`） |

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

1. 本検証中のlauncher process ANR 2回（`/data/anr/anr_2026-10-08-17-37-46-828`）:
   `LawnchairLayoutFactory.fontManager` lazy lock（main thread）×
   `ViewPool-init` background thread（`MainThreadInitializedObject.get()` 待ち）の
   deadlock。#555 fixとは無関係で、AVD再起動後は未発生（r2〜r6の連続検証では発生ゼロ）。
   別Issue起票を推奨する（本spec範囲外）。
2. #554 parcel decoder未merge treeではrecents遷移がtakeover失敗でwedgeし、
   overview到達自体が不安定になる。そのため **検証は#554込みtreeで行い、
   本fix branchにはmergeしていない**（decoder正本は#554 PR #557 branch）。
3. 検証シーケンスで `am force-stop com.android.settings` を使用した（dead card生成のため）。
   影響なし（全oracle正常成立）。
4. 2026-10-08 re-review round 2で判明した初版evidenceの不備と対応:
   - 初版 `32-post-fix-r7r8-nunu555-excerpt.txt` はtap復帰後（teardown時task=null）の
     logcat後段であったため、READMEの引用（task=153行）と不一致。**本版では同じ `acaa4d7` tree・
     同セッションのr9 run（20:19〜20:20）を取り直し、excerptも当該行を含むcommit済み
     実ファイル（`32-` / `33-`)へ差し替えた**。
   - 初版 `40-upstream-main-taskcontainer-response.json` は403 Forbiddenエラー本文が、
     **対照として無効**（Metadata込み本体ではなくエラーページ本文2行のみ）。本版ではGitiles raw (?format=TEXT)をdecodeした実物（`40-upstream-main-taskcontainer-refs-heads-main-2026-10-08.kt.txt`、provenance header付き）へ差し替えた。
   - 本READMEのtask ID・bitmap size・run番号は全て上記「task IDの対応」節および一次出力fileとの再突合済み。

## 未達と引継ぎ

- 同fileの `bind()` / `destroy()` / `refreshOverlay()` の式body修復 → **#556**（要起票・起票済み）。
  影響: ViewPool再利用時のstate残存・overlay位置・digitalWellBeingToast bind。
  （specのBehavior scenarioからViewPool helperGroup stale-stateは#556側に移した。）
- API 36 leg（同一欠陥の36上確認）: #559に含めて検討（既存branchあり）。
- `quickstepMaxSdk` 36→37とADR-0018 range反映の可否判断は、#554 merge＋本fix＋#556後の
  再検証（#545継続）で行う。

## 証跡file一覧（r9＝fix後検証run `acaa4d7`）

```
11-d1-live-only-overview.png                     d1 run（fix前）live tileのみのscreen。補助
12-r3-before-fix-dead-card-empty.png             fix前: dead task cardが空（green outlineのみ）
13-r5-before-fix-dead-card-empty.png             同上（別run）
14-554-run1-overview-reference.png               #554 run1 overview（qva2 leg対照）
20-post-fix-r9-dead-settings-card-rendered.png   r9: dead Settings card（task 153）に実screenshot表示（本命oracle）
21-post-fix-r9-after-tap-task-returned.png       r9: card tap → Settings foreground復帰
22-post-fix-r9-dead-card-peek.png                r9: overview内のdead Settings card peek（中心cardへscroll前）
30-pre-fix-d1-nunu555-excerpt.txt                fix前 d1 log excerpt（makeThumbnail HARDWAREで失効なし）
31-pre-fix-r4-full-logcat.txt                    fix前 full logcat（TaskView log確認用）
32-post-fix-r9-overview-nunu555-excerpt.txt      r9: fix後Nunu555全体（task 153 BackgroundOnly→SnapshotSplashへの到達を含む）
33-post-fix-r9-tap-return-nunu555-excerpt.txt    r9: tap復帰後のNunu555
34-post-fix-r9-overview-full-logcat.txt          r9: overview直後full logcat全文
35-post-fix-r9-tap-return-full-logcat.txt        r9: tap復帰後full logcat全文
40-upstream-main-taskcontainer-refs-heads-main-2026-10-08.kt.txt
                                                  upstream main TaskContainer.ktの取得済み原文（provenance header付き）
50-build2-sample.log                             build log sample
```
