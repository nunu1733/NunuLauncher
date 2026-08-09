# Android Emulator Smoke-Test Baseline

> Status: Completed assessment
> Issue: #9
> Requirements: NFR-007, NFR-011
> Verified: 2026-08-09
> Verification target: Lawnchair `v15.0.0-beta3.0` at `505dbc40e6154c05158b5d0271c45f6a885a411b`
> APK: `Lawnchair.15.Dev.(505dbc4).github.debug.apk` (GitHub Debug variant)

## 結論

Pinned baselineの GitHub Debug APK を clean Android 15 emulator へ install し、launcherとして起動、基本navigation、process restart、clean reinstall のすべてが致命的 crash/ANR なしで完了した。本手順は clean checkout から再実行可能である。

## Selection rationale

| 項目 | 選定値 | 根拠 |
|---|---|---|
| API level | 35 (Android 15) | `build.gradle` の `targetSdk 35`。ユーザーが最も経験する互換レベル。 |
| ABI | arm64-v8a | 検証hostが Apple Silicon (darwin arm64)。host-speed化とApple Silicon環境の代表性。 |
| System image | `google_apis` (非 Play) | Play Store固有機能は今回のlauncher smokeに不要。小さい依存面でheadless検証を再現するため非Play imageを選択した。 |
| Device profile | Pixel 6 (1080x2400, 420dpi) | AVD device定義として標準的。grid 5 rows x 4 columns が初期化される。 |

`minSdk 26` (Android 8.0) は下限互換性の境界であるが、smoke baselineとしては targetSdk に最も近い API 35 を代表的profileとして選ぶ。API 26 等の下限検証は別Issueのmatrix拡張で扱う。

Source references:

- [NunuLauncher pinned baseline](https://github.com/nunu1733/NunuLauncher/tree/505dbc40e6154c05158b5d0271c45f6a885a411b)
- [Lawnchair build.gradle at the pinned baseline](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/build.gradle)
- [Android Emulator command-line documentation](https://developer.android.com/studio/run/emulator-commandline)
- [Android SDK command-line tools documentation](https://developer.android.com/tools)

## Environment

| Tool | Version |
|---|---|
| Host OS | macOS (darwin 25.5.0, arm64) |
| JDK | OpenJDK 21.0.12 (Homebrew `openjdk@21`) |
| Android Emulator | 37.1.11.0 (build_id 15917651) |
| Platform Tools (adb) | 37.0.1 |
| SDK Platform | android-36.1 (compile), emulator API 35 (runtime) |
| Build Tools | 36.1.0 |
| Gradle Wrapper | 9.3.0 |

### SDK packages (installed)

```text
build-tools;36.1.0
emulator                                37.1.11
platform-tools                          37.0.1
platforms;android-36.1
system-images;android-35;google_apis;arm64-v8a   revision 9
```

### Runtime device properties

```text
ro.build.version.sdk       = 35
ro.build.version.release   = 15
ro.build.id                = AE3A.240806.043
ro.product.cpu.abi         = arm64-v8a
ro.product.model           = sdk_gphone64_arm64
ro.product.brand           = google
wm size                    = 1080x2400
wm density                 = 420
```

## Exact reproduction commands

### 1. Clean checkout and pinned source

```bash
git clone --recursive https://github.com/nunu1733/NunuLauncher.git
cd NunuLauncher
git checkout --detach 505dbc40e6154c05158b5d0271c45f6a885a411b
git submodule update --init --recursive
test "$(git rev-parse HEAD)" = "505dbc40e6154c05158b5d0271c45f6a885a411b"
```

このcheckoutを使うことで、以下の固定APK名とSHA-256を再現対象にできる。通常の `main` buildではdocumentation commitもversion文字列へ入るため、APK file名とSHAを本表の固定値と比較しない。

### 2. Environment setup

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH
```

### 3. Install emulator package and system image (first time only)

```bash
yes | sdkmanager --licenses
sdkmanager "emulator"
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
```

### 4. Create AVD

```bash
avdmanager create avd \
  -n nunu_smoke_api35 \
  -k "system-images;android-35;google_apis;arm64-v8a" \
  -d "pixel_6" \
  --force
```

> `devices.xml` 読み込みwarningが出るが、AVD作成自体は成功する（`pixel_6` device定義が適用される）。

### 5. Boot emulator (headless, clean state)

```bash
emulator -avd nunu_smoke_api35 \
  -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect \
  -wipe-data -port 5554 &
```

### 6. Wait for boot

```bash
adb wait-for-device
# poll until sys.boot_completed == 1 (typically 15-60s)
adb shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 2; done'
```

### 7. Build APK

```bash
./gradlew assembleLawnWithQuickstepGithubDebug
```

### 8. Install APK

```bash
APK="build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(505dbc4).github.debug.apk"
adb install -r "$APK"
```

### 9. Set as default home and launch

```bash
adb shell cmd package set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

## APK identity

| Property | Value |
|---|---|
| File | `Lawnchair.15.Dev.(505dbc4).github.debug.apk` |
| Size | 42,114,149 bytes |
| SHA-256 | `197dc5e826d8107a6dfc8f253f26d409a1e36152c71d9e31ab643a84e2fa6404` |
| Package | `app.lawnchair.debug` |
| Launcher activity | `app.lawnchair.debug/app.lawnchair.LawnchairLauncher` |

SHA-256は `docs/engineering/building.md` 記載のbaseline値と完全一致する。

## Verification results

### Acceptance criteria mapping

| Criterion | Result | Evidence |
|---|---|---|
| clean checkout から手順を再実行できる | PASS | clone、baseline checkout、submodule初期化を含めて記録。AVDは `--force` で再作成可能。既存emulator/portは事前停止する。 |
| GitHub Debug APK を emulator に install できる | PASS | `adb install` → `Success`。`pm list packages` に `app.lawnchair.debug` が現れる。 |
| launcher activity を開始でき、致命的 crash/ANR がない | PASS | `topResumedActivity` = `LawnchairLauncher`。`dumpsys dropbox` に crash/ANR recordなし。`FATAL`/`ANR` logcatなし。 |
| process restart 後も起動できる | PASS | `am force-stop` 後、HOME intentで再起動。PID 3954 → 4493。crashなし。 |
| private package/layout data を artifact や文書へ保存していない | PASS | logcat/DB snapshotを成果物へ貼らない。launcher DB名(`launcher_5_4_4.db`)のみ記録。 |

### Launch evidence

初回起動（PID 3954）:

```text
ActivityManager: Start proc 3954:app.lawnchair.debug/u0a207 for top-activity {app.lawnchair.debug/app.lawnchair.LawnchairLauncher}
topResumedActivity = ActivityRecord{... app.lawnchair.debug/app.lawnchair.LawnchairLauncher}
```

`dumpsys dropbox` に `data_app_crash` / `data_app_anr` / `system_app_anr` のlawnchair関連recordなし。

### Process restart evidence

```text
before force-stop:  pidof app.lawnchair.debug = 3954
am force-stop app.lawnchair.debug
after HOME intent:  pidof app.lawnchair.debug = 4493  (新プロセスで再起動)
topResumedActivity  = ActivityRecord{... app.lawnchair.debug/app.lawnchair.LawnchairLauncher}
crash logcat:       (empty)
```

### Basic navigation evidence

`adb shell input touchscreen swipe` で all-apps drawerを開閉:

```text
swipe up (540,1800 → 540,400):
  AllAppsRecyclerView{... V...}          ← visible
  FloatingHeaderView{... V...}
  UniversalSearchInputView{... VFE...C}
swipe down (540,400 → 540,1800):
  topResumedActivity = LawnchairLauncher   ← homeに復帰
crash logcat: (empty)
```

### Clean reinstall evidence

```text
adb uninstall app.lawnchair.debug         → Success
adb install <APK>                          → Success
set-home-activity                          → Success
launch HOME                                → pid 4880, topResumedActivity = LawnchairLauncher
crash logcat: (empty)
```

## Relevant log summary

logcat全体（lawnchair PID, 初回clean boot）の重大度別件数:

```text
 251  W
  69  D
  30  I
  28  E
   6  V
   0  F    ← fatal exception なし
```

### Non-fatal warnings（すべてlauncher動作を阻害しない既知の挙動）

| Category | Message | Impact |
|---|---|---|
| Recents | `config_recentsComponentName ... is not Lawnchair, disabling recents` | emulatorのdefault recentsが別launcherのため、Lawnchairは自身のrecentsを無効化する。Quickstep gestureは機能しないがlauncher基本機能に影響なし。 |
| Widget host | `AppWidgetManager: App widget provider info is null` | workspace初期化時のwidget host ID探索。non-fatal。 |
| Theme | `ThemeUtils: CustomButton is an AppCompat widget that can only be used with Theme.AppCompat` | 上流由来のcosmetic warning。表示・操作に影響なし。 |
| HWUI | `Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED` / `Unknown dataspace 0` | swiftshader software GPU (headless) 環境固有。実機では発生しない。 |
| AutoInstalls | `Favorite not found: com.android.contacts/...dialer` 等 | default workspace XMLがemulatorに存在しないappを参照。該当iconはskipされる。 |
| ziparchive | `Unable to open 'base.dm'` | deoptimization metadataがない通常挙動。 |

## Manifest / permission observations

install時（role-based自動付与）とruntime（ユーザー未許可）:

- **Granted (install/role)**: `SET_WALLPAPER`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `EXPAND_STATUS_BAR`, `QUERY_ALL_PACKAGES`, `ACCESS_HIDDEN_PROFILES`, `FOREGROUND_SERVICE` 等。launcher roleとして期待される権限。
- **Not granted (runtime, user-sensitive)**: `POST_NOTIFICATIONS`, `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `CALL_PHONE`。いずれも明示的許可が必要な権限であり、未許可でもlauncher起動は成功する。

manifestや権限への変更は本Issueの範囲外であり、production sourceは一切変更していない。

## Privacy

- 実ユーザーデータ、実端末のホームレイアウト、package一覧を成果物や文書へ保存していない。
- logcatは検証時の参照のみとし、transcriptをcommitしない。
- launcher DBの内容はdumpせず、ファイル名(`launcher_5_4_4.db`)のみをgrid profile確認のために記録した。
- emulatorは `-wipe-data` で起動したclean stateであり、個人データを含まない。

## Emulator profile for performance budget (NFR-006 input)

性能Issue（#15）のreference device入力として、次のemulator profileを提案する。

```text
Device class:  Pixel 6 emulator (google_apis, arm64-v8a)
API:           35
Screen:        1080x2400 @ 420dpi
Grid:          5 rows x 4 columns (default InvariantDeviceProfile)
GPU:           swiftshader_indirect (software)
Host:          Apple Silicon (darwin arm64)
```

software GPUであるため、frame timingやrendering性能の絶対値は実機参考値にならない。相対比較やregression検知用途にとどめる。実機同等の性能測定には物理端末またはhardware-accelerated emulatorが必要である。

## Follow-up issues

smoke baseline範囲外で、別Issueとして推奨するもの:

1. **API level matrix**: minSdk 26 など下限APIでの互換性確認（NFR-007 の網羅）。
2. **Quickstep recents on emulator**: emulatorのdefault recents component差し替えなしでQuickstep gestureを検証する方法の調査、または物理端末での確認。
3. **Tablet/foldable profile**: orientation、grid変更、large screen profileでのsmoke拡張。

これらは本baselineの blocker ではなく、追加検証の候補である。

## Reproducibility checklist

- [x] exact SDK / emulator / AVD / build / install / launch commands recorded
- [x] API, ABI, grid/device profile, APK SHA recorded
- [x] launch / restart / reinstall evidence recorded
- [x] relevant log summary recorded
- [x] no private package/layout data in artifacts
- [x] no failure requiring a follow-up Issue (all observed warnings are expected upstream behavior)
