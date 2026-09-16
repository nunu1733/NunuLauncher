# Investigation Plan: api36 UI lane burst の per-boot トリガー（`launcherWindowFocus=false` の occluder）特定

> Issue: #304
> Spec: [spec.md](./spec.md)
> Status: draft（investigation plan。fix plan ではない。root cause 未確定のため、対策の
> architecture は本 plan で決定しない）

## Current evidence

### Re-entry update

- **2026-09-16 の再開**: Issue本文・全コメント（前回snapshot
  `1d5d914492280e368e910df8a71a7f545006b1d1` 記録後の新コメントなし）と
  `origin/main`=`4f555450bdf817a832b8827857b5af54f41913a8` を再取得した。
  `git diff --name-status 0cf82bc1e61c1874b280a7120dff9594be4fef71..4f555450bd`
  の結果は #205 External Agent Exchange（PR #325/#326）のみであり、
  `.github/workflows/`、`tools/ci/`、`tests/organizer-instrumentation/`、
  `specs/304-*` への変更を含まない。したがってgate実装・lane構成・ci.yml行番号実測値
  （`0cf82bc1e6` 基準）は現行mainでも有効である。一方、CI run 監査（2026-09-16、
  run 35000215963〜35088536909 の全attempt照会）で 2026-09-15 23:32〜2026-09-16 03:00 UTC
  の PR #325 branch 上に自然発生 occluder capture 4件（capture 7〜10）を新規確認し、
  本planの証拠 ledger・分類表へ追記した。capture順序問題（`emu kill` 後実行）も
  4 failed boot すべてで再確認された。
- **2026-09-15 の再開**: Issue本文・全コメントと `origin/main`=`0cf82bc1e61c1874b280a7120dff9594be4fef71`
  を再取得した。前回のplan更新基準（`3aa6e83a1f`、PR #311/#312/#313 merge直後）以降の
  関連変更は、failure-time captureのbounded化（Issue #315 / PR #316 merge、
  [spec 315](../../specs/315-bounded-failure-evidence-capture/spec.md) はPR #318で
  `implemented` 遷移）のみであり、gate実装
  （`InjectedInputEnvironment.kt`、`OnboardingOrganizationProposalInstrumentationTest.kt`）は
  無変更であることを `git log 3aa6e83a1f..origin/main -- tests/organizer-instrumentation/app/lawnchair/organizer/ui/InjectedInputEnvironment.kt tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
  で確認した（PR #319〜#322 はlane/gateに触れない別surface）。本計画のcode path実測値は
  `0cf82bc1e6` で取り直した。
- 2026-09-15、main baseline（head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event
  `push`）の [run 34940500617](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617)
  で標準ランチャーoccluderの自然再発を捕捉（capture 6）。同時に、failure-time
  artifactは生成されたもののcapture stepが`emu kill`後に実行されdevice証拠が取得できない
  という保全機構の順序問題を確認した（詳細はCurrent evidence・Evidence preservation
  assessment）。
- 2026-09-13 の追加調査は、PR #311 merge後の `origin/main`=`37e3dd8feb9240e90587620e8175330b48604e19`
  を基準に実施した。前回の観測対象だった `3aa6e83a1f6` はPR #311 merge前のmainであり、
  以下の新しいローカルinstrumentation証跡は現在のgate実装と同じheadのAPKで採取した。
- 2026-09-13 に Issue本文・全コメントと `origin/main` を再取得した。前回のsnapshot
  （`origin/main`=`f9afd8bfde121932c0c8ed965225d52a84d86ab4`、snapshot commit
  `1e613e142a585c9ad2a2f718c682344b097960dc`）は再利用せず、現行の
  `origin/main`=`3aa6e83a1f6dc331e9f6712c126c9ff58d050660` を基準に確認した。
- PR #305 は merge済みで、merge commitは
  `0ea17a238b1d0668cf4b3ab16ac9fb663e2dd29e`。したがって以降のgate試行は
  #305の別branchではなく、現行mainのgateを使用した。
- 現行mainには後続の PR #310（Issue #308 の Compose focus同期安定化）も含まれるが、
  今回の試行対象は #304 のoccluder診断経路であり、#308の既存フレイクとは分離して扱う。
- **AC-3継続調査（2026-09-13）**: CI job log を再確認し、issue53 lane の実際の
  emulator は `system-images;android-36;google_apis;x86_64`、`pixel_7_pro`、2 cores、
  `-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim` で、起動時に
  RAM が 4096 MB へ拡張されていた（[run 34732479463 / issue53 job](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463/job/103709155384)、
  job log 2026-09-13確認）。先行のローカルANR試行はarm64・別AVD解像度・別RAM/coresであり、
  CIと同一条件ではないことを明確化した。
- 同日、ローカル `nunu_qpr2_api36_1`（`google/sdk_gphone64_arm64/emu64a:16/BE4B.251210.005/14574095`）で、
  その時点で `app.lawnchair.debug` / `.test` が未導入（ただしAVD dataをwipeしたpristine
  状態ではない）状態の `adb reboot` 反復を3回実施したところ、各bootの
  `sys.boot_completed=1` 後16〜20秒で `mCurrentFocus=...Application Not Responding: com.android.systemui`
  を観測した。`dumpsys dropbox --print system_app_anr` は3回ともSystemUIの
  `SystemUIService`/`KeyguardService`が約20秒待ちでANRになった記録を含み、CPU total 95〜97%、
  `surfaceflinger` 76〜93%、`system_server` 37〜43%、CPU/I/O pressure上昇を示した。
  これは、最終occluderを直接前面化せずに得た「repeated boot-to-ANR reproduction / H2と
  整合する強い機構証拠」である。ただし、制御したのはreboot後の観測であり、resource/display
  stallを操作してfailureの生成・除去を確認した因果path reproductionではない。
- ただし同じAVDをCI相当の `-cores 2 -memory 4096` で起動し、同じboot反復を3回行った場合は
  40秒観測で3/3がNexusLauncherActivityのままgreenだった。APK install単体（60秒）、Gradle
  connected経路の25 tests（`BUILD SUCCESSFUL`、25/25）でもANRは再現しなかった。この反証は
  先行ANRが「API 36.1またはLawnchairテストだけの必要条件」とは確定できず、host負荷、AVDの
  dirty state、image/arch差、またはCI固有の時系列が残ることを示す。したがって、この時点では
  AC-3のroot cause確定条件2（CIと同じ因果経路の外部妥当性）を満たしたとは扱わない。

### 確認済み（CI 実測。run link・head SHA・確認日つき）

- **元 burst（gate 導入前）**: [run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)
  attempt 1（issue53 lane、2026-09-12 確認）: 20 test 中 11 失敗 = touch 注入喪失 8
  （すべて `events=[]`・`launcherWindowFocus=false`・proposal は open/attached/shown）＋
  DPAD traversal 喪失 1 ＋ accessibility frontmost 不一致 1 ＋ `awaitResumedLauncher`
  timeout 1。同一 head の api35 lane は green。rerun green。
- **occluder capture 1（標準ランチャー）**: [run 34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012)
  （head `083c902973d13e851ecda32137af5e9bbf3da323`、event `pull_request`、2026-09-12 16:13 UTC、issue53 lane）:
  `awaitResumedLauncher` timeout が `classifyLauncherAwaitTimeout` により
  environment anomaly として 1 失敗に停留し、
  `evidence=launcher-resume-timeout; interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... com.google.android.apps.nexuslauncher/.NexusLauncherActivity},
  frontmostPackage=com.google.android.apps.nexuslauncher` を出力
  （[Issue #304 コメント 1](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647372032)、
  2026-09-12 UTC 確認）。
- **occluder capture 2（ANR ダイアログ）**: [run 34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836)
  （head `820dae07557631273f46000a01a716ad2b5bbb6c`、event `workflow_dispatch`、2026-09-12 17:54 UTC、issue52 lane）: window focus gate が
  `evidence=window-focus-gate:app.lawnchair.debug/androidx.activity.ComponentActivity;
  interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui},
  frontmostPackage=android` を出力。同一 run では gate を通らない別 test の
  `waitUntil(5_000)` timeout も発生しており、system UI が不調な boot だった裏付け
  （[Issue #304 コメント 2](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647683394)、
  2026-09-12 UTC 確認）。
- **occluder capture 3（標準ランチャー再発、attempt 1）**: [run 34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463)
  （head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、2026-09-13、issue53 lane）で、標準ランチャー
  `com.google.android.apps.nexuslauncher/.NexusLauncherActivity` が frontmost となる
  同一 signature を再捕捉した（[2026-09-13 03:02 UTC のIssue #304コメント](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5650524193)）。
- **occluder capture 4（ANR ダイアログ再発）**: [run 34733839798](https://github.com/nunu1733/NunuLauncher/actions/runs/34733839798)
  （head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、2026-09-13、issue52 lane）で、`Application Not Responding: com.android.systemui`
  が focus を保持する同一型を再捕捉した。同じ収集期間の
  [run 34733180391](https://github.com/nunu1733/NunuLauncher/actions/runs/34733180391) は
  `previewHeadingRestoresFocus...` の Compose timeout で、gate による occluder capture
  ではない既存の非gateフレイクとして分離する。
- **自然CIでのANR occluder再現（run 34732479463 attempt 2）**: 同じ
  [run 34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) を
  再実行したところ（head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、
  2026-09-13 10:31 UTC、issue53 lane）、最初の失敗が
  `OnboardingOrganizationProposalInstrumentationTest#laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`
  で発生し、次の証拠を出力した。
  `interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui},
  frontmostPackage=android; target window never gained focus within 15000ms`。
  これは、既存のANR captureが別laneの単発観測ではなく、同じAPI 36.1 issue53 laneの
  自然bootで再発し、Lawnchairの入力ゲートを直接阻害した証拠である。なお同runのissue52
  laneの失敗は `previewHeadingRestoresFocus...` の `ComposeTimeoutException` であり、
  このANR occluderとは分離する。
- **occluder capture 6（標準ランチャー、main baseline 上で自然再発、2026-09-15）**:
  [run 34940500617](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617)
  attempt 1（head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event `push`（main merge of
  PR #321）、2026-09-15 07:12 UTC 開始、issue53 lane）で、25 test 中 1 失敗
  （`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`）が
  `awaitResumedLauncher`（`OnboardingOrganizationProposalInstrumentationTest.kt:1200`、
  ENVIRONMENT_ANOMALY分類のerror site）で発生し、次の証拠を出力した。
  `interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... com.google.android.apps.nexuslauncher/.NexusLauncherActivity},
  frontmostPackage=com.google.android.apps.nexuslauncher`。
  capture 1/3 と同一 signature。[instrumentation report artifact](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/artifacts/10384814650)
  にも同じ 1 failure が保存されている。`--failed` rerun（attempt 2、
  [job 104294416831](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/job/104294416831)）
  は 25/25 green、[final-status](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/job/104296109004)
  も success。同一SHAで初回のみ失敗し別bootのrerunでgreenであることから、per-bootフレイク
  であることがmain baseline上でも追加確認された（[Issue #304 コメント 2026-09-15](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5676627394)、2026-09-15 UTC 確認）。
- **failure-time evidence の初回実失敗観測（2026-09-15）**: 同一 run の
  [failure-time evidence artifact](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/artifacts/10384774861)
  は生成されたが、実体のemulator証拠は取得できていない。runner logでは
  `07:22:07.964Z` に `reactivecircus/android-emulator-runner` が `emu kill` を実行し、
  その後 `07:22:09.957Z` にcapture stepが開始された。artifactの `adb-devices.txt` は
  deviceなし、window/activity/HOME role等は `emulator-5554 not found`、logcatはtimeout。
  取得できた一次証拠はtest reportとfailure logのfocused window行のみで、`dumpsys window` /
  HOME role / ANR traceの機構証拠ではない。captureをemulator生存中（同一script内の
  failure trap等）に移す別対応が必要である（[Issue #304 コメント 2026-09-15](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5676627394)）。
  なお保全機構自体の先行障害として、2026-09-14 の run 34841787277（issue52 lane）では
  capture処理自体が長時間停止してartifact uploadに到達しない事象が観測され、これは
  Issue #315 として分離され PR #316 のbounded化（per-command timeout 15秒、wall budget
  150秒、出力上限）で対処済みである（[spec 315](../../specs/315-bounded-failure-evidence-capture/spec.md)、
  status `implemented`）。
- **occluder capture 7（標準ランチャー、PR branch 上で自然再発、2026-09-15）**:
  [run 35035443254](https://github.com/nunu1733/NunuLauncher/actions/runs/35035443254)
  attempt 1（head `9a599cab5e0b9edc72d5951a7252a153b2c91ca4`、event `pull_request`
  （PR #325 branch `issue-205-implementation`）、2026-09-15 23:32 UTC、issue53 lane、
  [job 104603478637](https://github.com/nunu1733/NunuLauncher/actions/runs/35035443254/job/104603478637)）
  で、25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、
  `awaitResumedLauncher`、`launcher-resume-timeout`）が発生し、
  `interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... com.google.android.apps.nexuslauncher/.NexusLauncherActivity},
  frontmostPackage=com.google.android.apps.nexuslauncher` を出力した。
  capture 1/3/6 と同一 signature。同 run の `organizer-unit-tests` も失敗したが、
  これは #205 diff 対象の unit test 失敗であり occluder capture とは分離する。
  capture step は `emu kill`（23:32:30Z）後（23:32:35Z 開始）の実行だった
  （2026-09-16 UTC 確認、job log 実読）。
- **occluder capture 8（ANR ダイアログ、issue52 lane、2026-09-16）**:
  [run 35045487650](https://github.com/nunu1733/NunuLauncher/actions/runs/35045487650)
  attempt 1（head `e237dffb6d88e4d13a9d58db5c054ff838c79fab`、event `pull_request`、
  2026-09-16 02:00 UTC、issue52 lane、
  [job 104634354181](https://github.com/nunu1733/NunuLauncher/actions/runs/35045487650/job/104634354181)）
  で、64 test 中 1 失敗
  （`ManualOrganizationPreferencesInstrumentationTest#changeListTraversalReachesExpandAndReviewActions`、
  `ensureWindowFocused`、`InjectedInputEnvironment.kt:183`）が発生し、
  `evidence=window-focus-gate:app.lawnchair.debug/androidx.activity.ComponentActivity;
  interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui},
  frontmostPackage=android` を出力した。capture 2/4 と同じ `ComponentActivity` 型の
  issue52 lane での再発。残る 63 test は通過し、health state による連鎖は観測されなかった。
  capture step は `emu kill`（02:01:54Z）後（02:01:58Z 開始）の実行だった
  （2026-09-16 UTC 確認、job log 実読）。
- **occluder capture 9（ANR ダイアログ＋health state 連鎖、2026-09-16）**:
  [run 35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610)
  attempt 1（head `e21edbca253bbd9df87252a0fb43d0352c54f272`、event `pull_request`、
  2026-09-16 02:45 UTC、issue53 lane、
  [job 104643853432](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/job/104643853432)）
  で、最初の失敗が `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome` の
  `ensureWindowFocused`（`window-focus-gate:app.lawnchair.debug/app.lawnchair.LawnchairLauncher`）で
  `interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui},
  frontmostPackage=android; target window never gained focus within 15000ms` を出力した。
  以後の 12 test はすべて
  `input environment already marked unhealthy by an earlier gate failure; reusing the original evidence: ...`
  で高速失敗し、25 test 中合計 13 失敗（`Tests on emulator-5554 - 16 failed: There was 13
  failure(s)`）となった（job log 実読、2026-09-16 UTC 確認）。これは
  #300 の run-level environment health state（`InjectedInputEnvironment.kt` の
  `EnvironmentHealthState`。first evidence のみ保持し以後の entry check は再待機せず
  失敗する設計）が自然発生で連鎖した初の観測であり、旧来の「黙って注入が喪失する
  burst（run 34677444335 の 11 失敗）」と違い、全失敗が単一の完全な gate 証拠に
  紐づいている。failure-time capture step は `emu kill`（02:45:21.380Z）後
  （02:45:24.563Z 開始）の実行で、生成された
  [artifact 10427419407](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/artifacts/10427419407)
  は 6,784 bytes であり device 依存の機構証拠を含まない。
- **occluder capture 10（標準ランチャー、同一 run の連続 boot、2026-09-16）**:
  同じ [run 35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610)
  attempt 2（同 head、2026-09-16 03:00 UTC、issue53 lane、
  [job 104646832297](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/job/104646832297)）
  で、25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、
  `awaitResumedLauncher`、`OnboardingOrganizationProposalInstrumentationTest.kt:1200`）が
  `launcher-resume-timeout` で発生し、capture 1/3/6/7 と同一の標準ランチャー signature を
  出力した。attempt 3（[job 104650505335](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/job/104650505335)、
  03:10 UTC 開始）は 25/25 green。**同一 head の連続 boot で attempt 1 が ANR dialog、
  attempt 2 が標準ランチャー、attempt 3 が green と分岐した**ことは、occluder 型が
  boot ごとに独立に決まる per-boot 分布であることを単一 run 内で直接示す
  （2026-09-16 UTC 確認、各 attempt job log 実読）。
- **同窓の非 occluder 失敗（2026-09-15〜16、分離）**:
  [run 35000215963](https://github.com/nunu1733/NunuLauncher/actions/runs/35000215963)
  （head `8c2fc68cc15ac7efc6699360a795225178327fc2`、2026-09-15 17:26 UTC）と
  [run 35005008383](https://github.com/nunu1733/NunuLauncher/actions/runs/35005008383)
  （head `ef3edfb3050738c7d0afd3d430b79241df1d4f97`、2026-09-15 18:12 UTC）は
  いずれも issue52 lane の `StrategyPickerInstrumentationTest` 2 test が
  `ComposeTimeoutException`（`waitUntil(5_000)` 系）で失敗したもので、occluder capture
  ではなく既存の非gateフレイク（34733180391 と同系統）として分離する。
  [run 35042223633](https://github.com/nunu1733/NunuLauncher/actions/runs/35042223633)
  （shared-writer lane）の失敗は HTTP 409 の runner setup error であり window focus と
  無関係、[run 35037367128](https://github.com/nunu1733/NunuLauncher/actions/runs/35037367128)
  attempt 1 の `organizer-unit-tests` 失敗は #205 diff 対象である（job log 実読、
  2026-09-16 UTC 確認）。
- **自然CIの非再現対照（同一job rerun、2026-09-13）**: 同じ
  [run 34732479463のrerun job 103724416322](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463/job/103724416322)
  は同一のAPI 36/x86_64/Pixel 7 Pro/SwiftShader条件でboot 52.050秒後にunlockし、
  25/25 testsを完走した。前回と同じ `Failed to start Emulator console for 5554` warningが
  出たが、SystemUI ANRもfocus gate failureも無かった。この対照により、console warningは
  capture 2/4/5の十分条件ではなく、失敗bootに固有の別状態（resource/display経路を含む）が
  必要であることが分かった。
- **現行gateは修復対象状態を緩和する**: KEYCODE_SLEEP 強制（`mWakefulness=Asleep`）→
  gate が wakeup（実行後 `Awake`）→ green（PR #305 本文の Verification evidence、
  ローカル api36 AVD `issue142_api36`、2026-09-12 実施）。非 interactive /
  keyguard は現行gateの自動修復対象であり、修復後はその状態のfailure evidenceを残さない。
  これはpost-gateの残存occluder failureに対する緩和を示すが、pre-gate burstの遷移中に
  非interactive状態が寄与しなかったことまでは示さない。
- **CI の artifact 現状**: issue53 lane は test report に加え、failure 時の
  failure-time emulator evidence（capture `ci.yml:645-649`、upload `:650-655`）と
  gate state test（`InjectedInputEnvironmentStateInstrumentationTest`）を同一 job で
  実行する（`ci.yml:602-657` 実測、`0cf82bc1e6`）。issue52 lane は test report ＋
  UI evidence ＋ failure-time evidence（capture `ci.yml:476-482`）。api35 lane
  （`ci.yml:375`、api-level 35 `:399`）は failure-time capture を持たない。
  ただし capture step は emulator-runner step の**後**に置かれており、emulator は
  runner step 終了時に kill されるため、2026-09-15 の実測では device 依存の証拠は
  取得できていない（上記「failure-time evidence の初回実失敗観測」）。2026-09-16 の
  capture 7〜10（4 failed boot）でも同一の順序問題を再確認した（各 job log の
  `emu kill` → capture step 開始の timestamp 実読）。
- **ローカル強制 occluder capture（AC-1）**: API 36 AVD `issue142_api36`（device
  `emulator-5654`）へ現行mainのdebug APKとandroidTest APKをinstallし、Lawnchairのfocusを
  観測した直後に host側から
  `am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity` を実行した。
  `OnboardingOrganizationProposalInstrumentationTest#realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale`
  は `awaitResumedLauncher` のenvironment anomalyとして1件で失敗し、CI capture 1と同じ
  標準ランチャーのfocused/frontmost証拠を出力した。実物は下記Verification evidenceに記録する。

### 確認済み（code path 実読。`0cf82bc1e6` 時点の行番号実測。2026-09-16 再開時に `4f555450bd` までの差分に gate 実装・`ci.yml`・`tools/ci/` の変更なしを確認済みのため行番号は有効）

- **注入 site と診断の所在（main）**:
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
  - `describeInputEnvironment`（:963）— `launcherWindowFocus` / `activityFocus` /
    `treeFocus` / proposal 状態 / target geometry / `topOpenView` を 1 行に組み立てる。
    burst 失敗メッセージ（deliveredTap error :1423-1427、DPAD traversal 等 :955 付近）に添えられる。
    **device 状態（interactive / keyguard / focused window / frontmost）は含まない**（gate 導入までの盲点だった領域）。
  - `awaitResumedLauncher`（:1156）— 120 回 × 100ms poll（最大約 12 秒）で
    `Stage.RESUMED` ＋ attached ＋ laid out の launcher を待つ。timeout 時は
    `classifyLauncherAwaitTimeout`（:1190）で分類し、ENVIRONMENT_ANOMALY なら gate 証拠
    つきで error（:1199。2026-09-15 の失敗 stack `:1200` はこの箇所）。
  - `startLauncher`（:1208）— **明示 component 指定**
    （`am start -n app.lawnchair.debug/app.lawnchair.LawnchairLauncher -a
    android.intent.action.MAIN -c android.intent.category.HOME`）。HOME intent の
    resolver 経由の暗黙起動ではない。この点は Issue コメント 1 の「`am start -a MAIN
    -c HOME` をもってしても標準ランチャーが前面に留まった」という解釈に対して、
    「明示 component 起動が HOME role 解決へどう扱われるか（redirect の有無）」を
    機構判別の中心問いにする根拠である。
  - `runShellCommand`（:1219）— `uiAutomation.executeShellCommand` の同期実行。
    強制状態の作成・観測にそのまま使える。
  - `awaitAccessibilityTextBounds`（:568）— `rootInActiveWindow` 前提の走査。
  - `sendKey`（:1234）— `ensureWindowFocused` 後 `sendKeyDownUpSync`。
- **lane 構成（main、`.github/workflows/ci.yml`、`0cf82bc1e6` 実測）**:
  - issue53 job（:602-657）: 2 クラス filter（`OnboardingOrganizationProposalInstrumentationTest`
    ＋ gate state test `InjectedInputEnvironmentStateInstrumentationTest`。自然発生時の
    25 test はこの構成の計測）。`reactivecircus/android-emulator-runner@v2`、api-level 36、
    target google_apis、x86_64、pixel_7_pro、disable-animations、emulator-boot-timeout 900。
    failure 時は test report upload（:636-644）、failure-time capture（:645-649、
    `timeout --kill-after=30 300`、`continue-on-error: true`）、その upload（:650-655）。
    capture step は runner step の後のため emulator は kill 済み（順序問題、前述）。
  - issue52 job（:432-497）: 4 クラス filter を 1 Gradle invocation で実行。
    failure 時 capture（:476-482）と upload（:483-488）。
  - api35 lane（:375、api-level 35 :399）は同構成で api-level 35。元 burst が同 head で
    green であったことから、トリガーは api36 環境側に存在する。
  - emulator boot 後の unlock は workflow には書かれず、emulator-runner action 内部の
    `input keyevent 82` 相当に依存する（#300 plan が run 34677444335 の job log
    :628-633 で実測。action 内部の正確な手順は本環境では未検証 → 未検証領域へ記載）。
- **gate の証拠能力（現行mainの `InjectedInputEnvironment.kt` 実読）**:
  - 証拠 field: `interactive`（`PowerManager.isInteractive`）、`keyguardLocked`
    （`KeyguardManager.isKeyguardLocked`）、`focusedWindow`（`dumpsys window` の
    `mCurrentFocus` / `mFocusedWindow` 行。**最初の 1 行のみ**）、`frontmostPackage`
    （`rootInActiveWindow?.packageName`）。
  - 保持しない: window z-order（`dumpsys window windows` の列挙）、HOME role state、
    logcat、ANR trace。occluder 1 の機構判別に必要な情報はこの不足分に当たる。
  - 修復: `input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard` のみ。
    occluder window（標準ランチャー・ANR dialog）は修復対象外で、gate は証拠採取後
    1 失敗に停留させる（設計どおり）。
  - 分類: `classifyLauncherAwaitTimeout`（非 interactive ∨ keyguard ∨ frontmost ≠
    target → ENVIRONMENT_ANOMALY）、`classifyAccessibilityTimeout`（activity 非focus ∨
    frontmost ≠ target → ENVIRONMENT_ANOMALY、それ以外は NODE_NOT_FOUND）。
    `ensureWindowFocused` timeout は無条件に ENVIRONMENT_ANOMALY。
  - deadline: `WINDOW_FOCUS_GATE_TIMEOUT_MILLIS = 15_000`。
  - failure injection hook: runner 引数 `nunuInjectEnvironmentHealthFailure`（CI では
    未指定）。強制状態試行の補助に使える（配線確認用であり、状態作成用ではない）。

### 推測と未確定（確定させていない）

- burst の全事象が occluder 系 boot で説明できるか（capture 2 件は `awaitResumedLauncher`
  系と gate 系だが、元 burst の `events=[]` 注入喪失が同じ boot で同時に起きたかは
  gate 導入前で証拠が無い）。
- 標準ランチャー occluder が per-boot でどう生じるかの機構（仮説 H1/H1'、下表）。
- ANR boot の発生機構（CI runner 負荷・API 36.1 image 固有等。仮説 H2）。
- system UI の NotificationShade が可視・focus保持状態で残る機構（仮説 H2'）。
- 発生頻度。緩和後の再発継続自体は確認済み（capture 6 が main baseline 上で自然再発、
  2026-09-15。さらに 2026-09-15 23:32〜2026-09-16 03:00 UTC の約 3.5 時間に PR #325
  branch 上で capture 7〜10 の 4 件が連続発生 — 2026-09-13 02:2x〜02:56 の連続異常窓と
  同様の時間クラスタリング。ただし定量（run あたりの発生率）は未確定。成功 run も
  同期間に複数存在する）。

## Hypotheses（仮説と反証方法）

| ID | 仮説 | 支持する証拠 | 反対・弱化する証拠 | 反証・確認方法 |
|---|---|---|---|---|
| H1 | 一部の boot で default HOME role が標準ランチャーに解決され、HOME category 起動が role holder（標準ランチャー）へ効くことで標準ランチャーが前面に残る | occluder 1/3/6/7/10 の focused window が標準ランチャー。ローカルAPI36でもHOME role holderはNexusだった | 起動は明示component指定であり、暗黙HOME解決ではない（`startLauncher` :1208実測）。同じローカル端末でNexusがHOME role holderのままでも、明示的なLawnchair起動はLawnchairへfocusを移したため、role holder単独ではこのfailureを説明できない | CI boot上のrole stateと、失敗時の実際のactivity/window遷移を同時に取得する。role stateだけでは不足し、`dumpsys window windows` と起動結果の組み合わせが必要 |
| H1' | HOME role は不変で、標準ランチャーの window が z-order 上に残存し焦点を保持する（起動は成功するが焦点が取れない） | occluder 1/3/6/7/10 で `awaitResumedLauncher` が timeout = Lawnchair が RESUMED に到達していない。焦点が標準ランチャーであることと整合。ローカルでもLawnchairのfocus取得後にNexusを前面化すると同じfailure signatureになり、`dumpsys window windows` でLawnchair windowの `shown=false` / `DRAW_PENDING` も観測された | ローカルの強制操作はCIの自然発生機構ではない。Lawnchair未RESUMEDの説明にはならない（RESUMED判定はlifecycleと独立） | gate 証拠にz-orderが無いため、再発時に `dumpsys window windows` を取得できるようにして判別する（手順4の判断材料）。capture 6〜10 ではfailure-time保全が `emu kill` 後に実行されz-orderは取得できておらず、順序修正が前提 |
| H2 | system UI の ANR ダイアログが焦点を保持する boot がある（runner 負荷等で systemui が不調になる boot 単位の劣化） | occluder 2/4/5/8/9 の focused window が `Application Not Responding: com.android.systemui`。capture 2 と同じ run の非 gate test も timeout。capture 7〜10 と同じ時間窓（2026-09-15〜16）には非 gate Compose timeout（35000215963/35005008383）も並発し、fleet 単位の不調と整合する。ローカルreboot反復（wipe-dataなし）では、SystemUIのservice/keyguard ANRと、`system_server`/`surfaceflinger`高負荷、WindowManager/Settings Binder待ちを3/3で観測 | CI runnerのANR traceは未取得。ローカルをCI相当の2 cores/4GBで反復すると0/3であり、ローカルの再現はhost/image/dirty state依存の可能性がある | ANR の強制再現をoracleにせず、CI failure時にlogcat / ANR trace / dumpsysをartifact化して、自然CI bootでも同じsystem_server/SurfaceFlinger停滞があるか確認する。現時点では「SystemUI ANR dialogが直接occluder」「その前段にあるresource/display path停滞と整合する強い機構証拠」までを支持し、CI root cause確定とは扱わない |
| H2' | system UI の NotificationShade が bootまたは直前操作後に可視・focus保持状態で残り、Lawnchairの明示起動より上位に居続ける | ローカル `nunu_qpr2_api36_1` で実instrumentation testが `focusedWindow=...NotificationShade`, `frontmostPackage=com.android.systemui` のまま15秒gate timeout。`input swipe` で同状態を制御再現し、`KEYCODE_BACK` で閉じた後は同じtestがgreen | 今回の自然CI captureではNotificationShadeそのものは未取得。再起動後のcleanな同AVDではshadeは閉じており、既存のdirty stateまたはboot内の別経路の可能性が残る | failure時の`dumpsys window windows`とSystemUI state/logcatをCI artifact化し、NotificationShadeの表示開始イベントとboot/runner操作の順序を照合する。直接shadeを開く試行は因果の対照には使うが、CIの自然発生機構の確定とは分ける |
| H3 | `input keyevent 82` 直後の keyguard 解除不成立・解除と HOME 起動の競合 | Issue 本文の仮説候補 | occluder 1〜10 はいずれも最終 capture で `keyguardLocked=false`。KEYCODE_WAKEUP / dismiss-keyguard の修復実装済み | 最終状態の `keyguardLocked=false` により、解除済み状態が継続している単純な説明は弱化する。ただし解除・起動の途中に競合があった可能性までは否定できないため、遷移証拠がない限り H3 は未確定とする |
| H4 | 非 interactive（screen off）boot | Issue 本文の仮説候補 | 現行の自然発生 occluder capture 1〜10 は最終時点で `interactive=true`。KEYCODE_SLEEP 強制は、PR #305後の現行gateが wakeup 修復して green にできることを示す | 現行gateでは非interactive状態は修復・緩和され、残るpost-gate occluder failureの原因ではない。一方、pre-gate burst [34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)の遷移中に寄与した可能性は、interactive/keyguard/window状態を保持していないため未確認とする |
| H5 | API 36.1 固有の window focus 遷移の遅延・欠落 | Issue 本文の仮説候補（api36 限定の発生） | gate の 15 秒待ちで焦点が到達しなかったため、15 秒以内に解消する単純な遅延説は弱化する。api35 が同 head で green な事実も単純な遅延だけでは説明しにくい | 15 秒超の遅延や完了しない遷移までは現証拠から否定できない。自然発生時の activity/window 遷移または待機後の状態を観測し、単純な遅延・欠落・occluder保持を区別する |

## Occluder classification

| Capture | 型 | 証拠 | 判定 |
|---|---|---|---|
| CI run [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012) | 標準ランチャー activity | head `083c902973d13e851ecda32137af5e9bbf3da323`、event `pull_request`、`interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | 証拠行だけで標準ランチャー型と分類可能 |
| CI run [34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836) | system UI ANR dialog | head `820dae07557631273f46000a01a716ad2b5bbb6c`、event `workflow_dispatch`、`interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 証拠行だけでANR dialog型と分類可能 |
| CI run [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 1 | 標準ランチャー activity | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、既存 capture 1 と同じ `com.google.android.apps.nexuslauncher/.NexusLauncherActivity` frontmost | 証拠行だけで標準ランチャー型と分類可能。自然発生での再発例 |
| CI run [34733839798](https://github.com/nunu1733/NunuLauncher/actions/runs/34733839798) | system UI ANR dialog | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、既存 capture 2 と同じ `Application Not Responding: com.android.systemui` | 証拠行だけでANR dialog型と分類可能。自然発生での再発例 |
| CI run [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 2 | system UI ANR dialog | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、2026-09-13 10:31 UTC、issue53 lane。最初の失敗で `interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 同じAPI 36.1 issue53 laneの自然bootで再発した証拠。ANRに至るboot内の原因・遷移は未取得 |
| CI run [34940500617](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617) attempt 1 | 標準ランチャー activity | head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event `push`（main）、2026-09-15 07:12 UTC、issue53 lane。25 test 中 1 失敗で `interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher`。`--failed` rerun 25/25 green | 証拠行だけで標準ランチャー型と分類可能。capture 1/3 と同一signatureのmain baseline上での自然再発。failure-time artifactは生成されたがcapture stepが`emu kill`後の実行で機構証拠は未取得 |
| CI run [35035443254](https://github.com/nunu1733/NunuLauncher/actions/runs/35035443254) attempt 1 | 標準ランチャー activity | head `9a599cab5e0b9edc72d5951a7252a153b2c91ca4`、event `pull_request`（PR #325 branch）、2026-09-15 23:32 UTC、issue53 lane。25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、`launcher-resume-timeout`）で `interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | 証拠行だけで標準ランチャー型と分類可能。capture 1/3/6 と同一 signature。同 run の unit test 失敗は #205 diff 対象で分離。capture step は `emu kill` 後 |
| CI run [35045487650](https://github.com/nunu1733/NunuLauncher/actions/runs/35045487650) attempt 1 | system UI ANR dialog | head `e237dffb6d88e4d13a9d58db5c054ff838c79fab`、event `pull_request`、2026-09-16 02:00 UTC、issue52 lane。64 test 中 1 失敗（`ManualOrganizationPreferencesInstrumentationTest#changeListTraversalReachesExpandAndReviewActions`、`window-focus-gate:.../ComponentActivity`）で `interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 証拠行だけでANR dialog型と分類可能。capture 2/4 と同じ型の issue52 lane での自然再発。連鎖なし（1 失敗のみ）。capture step は `emu kill` 後 |
| CI run [35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610) attempt 1 | system UI ANR dialog | head `e21edbca253bbd9df87252a0fb43d0352c54f272`、event `pull_request`、2026-09-16 02:45 UTC、issue53 lane。25 test 中 13 失敗 = 1 gate証拠（`window-focus-gate:.../LawnchairLauncher`、`Application Not Responding: com.android.systemui`、`frontmostPackage=android`）＋ 12 件の `already marked unhealthy ... reusing the original evidence` | 証拠行だけでANR dialog型と分類可能。run-level health state による連鎖の自然発生初観測（#300 設計どおり、全失敗が単一の元証拠に紐づく）。failure-time artifact（6,784 bytes）は `emu kill` 後の capture で機構証拠なし |
| CI run [35048590610](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610) attempt 2 | 標準ランチャー activity | 同 head `e21edbca253bbd9df87252a0fb43d0352c54f272`、2026-09-16 03:00 UTC、issue53 lane。25 test 中 1 失敗（`reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome`、`launcher-resume-timeout`）で capture 1/3/6/7 と同一 signature。attempt 3 は 25/25 green | 証拠行だけで標準ランチャー型と分類可能。同一 run の連続 boot で ANR dialog（attempt 1）→ 標準ランチャー（attempt 2）→ green（attempt 3）と分岐した per-boot 分布の直接例 |
| CI run [34733180391](https://github.com/nunu1733/NunuLauncher/actions/runs/34733180391) | 非gate Compose timeout | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、`previewHeadingRestoresFocus...` の `ComposeTimeoutException` | occluder captureではなく、既存の非gateフレイクとして分類から分離 |
| CI run [35000215963](https://github.com/nunu1733/NunuLauncher/actions/runs/35000215963) / [35005008383](https://github.com/nunu1733/NunuLauncher/actions/runs/35005008383) | 非gate Compose timeout | head `8c2fc68cc15ac7efc6699360a795225178327fc2` / `ef3edfb3050738c7d0afd3d430b79241df1d4f97`、event `pull_request`、2026-09-15 17:26 / 18:12 UTC、issue52 lane。`StrategyPickerInstrumentationTest` 2 test の `ComposeTimeoutException`（5,000ms） | occluder captureではなく、既存の非gateフレイク（34733180391 と同系統）として分離。capture 7〜10 と同時間窓の並発 |
| Local `issue142_api36` forced run (2026-09-13) | 標準ランチャー activity | `interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | CI run 34704064012と同じoccluder型。自然発生機構の証明ではなく、診断能力の誘発実証 |
| Local `nunu_qpr2_api36_1` instrumentation run (2026-09-13) | system UI NotificationShade | `interactive=true`, `keyguardLocked=false`, `focusedWindow=mCurrentFocus=Window{... NotificationShade}`, `frontmostPackage=com.android.systemui`; `dumpsys window windows` は `Surface: shown=true`, `isOnScreen=true` | 実instrumentation testで観測されたが、再起動後はshadeが閉じていたため、per-boot自然発生機構とは断定しない |
| Local controlled `input swipe` on `nunu_qpr2_api36_1` (2026-09-13) | system UI NotificationShade | swipe後に同じ`NotificationShade` focus状態を作成し、対象testが `window-focus-gate` で15秒後に失敗。`input keyevent 4`でshadeを閉じると同じtestが **1 test / 0 failures** | 最終occluderの直接起動ではなく、system UI gestureを使った因果対照。NotificationShadeがfocusを保持するとgate失敗、除去するとgreenになることを示すが、CI bootでのshade発生源は未確定 |

## Investigation steps

1. **前提の固定**: 作業開始時に PR #305 の merge 状態を確認する。今回の再開時点では
   merge済みだったため、現行mainのgateを使って手順2を実施した。未mergeの場合は
   #305のheadをcheckoutして実施するか、mergeを待つ（mainにはgateが存在しないため、
   main単独では診断特定の実証ができない）。
2. **強制状態による診断特定の実証**（RC-AC-01、終了条件 1）: ローカル api36 emulator
   （google_apis、`docs/assessment/evidence/issue-123-ui-mapping.md` の手順と同一構成）で:
   - occluder 強制: 標準ランチャーを前面化
     （`am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity`）した
     状態で gate 利用 test を実行し、gate が environment anomaly で停留し、証拠行が
     標準ランチャーを名指しすることを確認する（他 app activity でも代替可）。
   - 修復対象状態の対比: KEYCODE_SLEEP は修復されて green になることを再確認済み
     （Current evidence）。本手順の実証対象が occluder 系であることを試行記録に明記する。
   - 結果（成否・メッセージ実物・CI シグネチャとの一致度）を本 plan の
     Verification evidence 節に記録する（RC-AC-05）。
3. **発生時証拠の蓄積と分類**（RC-AC-02）: PR #305 merge 後の CI 実行で gate capture
   を収集し、各 capture を occluder 型（標準ランチャー / system dialog / keyguard /
   非 interactive / その他 / 不明）へ分類する。初期 2 capture（occluder 1・2）を含む
   自然発生 10 capture（標準ランチャー 5 例、ANR ダイアログ 5 例）を現集合とし、分類表を
   本 Issue または本 plan に維持する。新規 capture ごとに run link と head SHA を添える。
4. **証拠保全の判断と実効性評価**（RC-AC-04、終了条件 2）: gate 証拠が保持しない状態
   （`dumpsys window windows` の z-order、`cmd role get-role-holders` の HOME role、
   failure 時 logcat、ANR trace、NotificationShadeの表示状態）を列挙し、各不足がどの仮説
   （H1/H1'/H2/H2'/H5）の判別に
   必要かを対応づけた上で、CI failure 時 artifact 化（logcat / dumpsys）の導入と理由を
   [Issue #304 の調査コメント](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5652168567)
   に記録済みである。実装は PR #313 として行われ、 Issue #315（PR #316）で bounded 化された。
   さらに 2026-09-15 の実失敗で capture step が `emu kill` 後に実行され device 証拠が
   空振りだったことが確認されたため、capture を emulator 生存中（同一 script / failure trap
   内）へ移す順序修正の要否を本 Issue で判断して記録する（実装は別 workflow PR。後述
   Next stage handoff）。
5. **H1/H1' の機構判別**: 標準ランチャー occluder が再捕捉された場合、HOME role state、
   resolve 結果、activity/window 遷移、z-order を、自然発生した CI boot または仮説の
   因果経路を再現する制御試行で観測する。最終状態の occluder を host から直接前面化
   するだけの試行は、CI signatureとの一致を示す AC-1/AC-2 の証拠にはなるが、H1/H1'
   の機構判別や AC-3 の root cause 確定には使わない。必要な観測が取得不能な場合は、
   そのことを明示して未確定部分を残した結論または残存リスク受容へ進む。
   NotificationShade等のsystem UI windowを捕捉した場合も同じ観測を行い、表示開始が
   boot provisioning由来か、前回状態の残留か、テスト前操作由来かを分ける。
6. **結論の記録**（RC-AC-03、終了条件 3）: 下記の判断基準を適用し、本 Issue へ結論を
   記録する。再発が観測できなくなった場合は残存リスク受容の判断と根拠を記録して
   完了とする。

## Root cause 確定の判断基準

トリガーを「確定」と記録するのは、次の 3 条件を満たす場合のみである:

1. **観測**: 失敗時に存在した状態が gate 証拠（または同等の adb 取得証拠）で
   捕捉され、焦点を保持していた window の owner が特定されている。
2. **機構**: その状態が per-boot で生じる因果経路の説明があり、(a) 最終 occluderを
   直接前面化するだけではない、仮説の因果経路を作る制御再現、または (b) 自然発生した
   CI boot 上の role/resolve/activity/window/z-order 遷移の直接観測により裏付けが取れて
   いる。最終状態を強制して同じsignatureを得ただけでは、この条件を満たさない。
3. **整合**: 因果経路の再現または自然発生観測が CI シグネチャと一致する（同一の gate
   接頭辞、証拠 field の値の型、失敗形態）。最終状態のsignature一致だけの場合は
   AC-1/AC-2 の証拠として記録し、root cause確定の外部妥当性とは扱わない。

複数 occluder 型が残る場合は「単一 root cause」とまとめず、型ごとの分類として
記録する（occluder 1 と 2 は既に異種である）。

## Evidence preservation assessment

現行gateの1行証拠は、焦点を保持したoccluderの分類には十分である。一方で、今回の
標準ランチャー型についてH1（HOME role / resolver経路）とH1'（z-order残留）を区別する
には不足している。ANR dialog型についても、発生機構を確認するlogcat/ANR traceがない。
NotificationShade型についても、表示されている事実は分類できるが、表示開始イベントと
boot/runner操作の因果は保持されない。

**判断: failure時の追加証拠保全を導入する。** `tools/ci/capture-emulator-failure-evidence.sh`
を追加し、API36のIssue #52/#53 instrumentation laneで、テストstepが失敗した場合だけ
best-effort収集を実行してartifactへ保存する。収集コマンドの失敗は元のテスト失敗を
置き換えず、各snapshotへ終了statusとして記録する。収集対象は次の通りである。

実装後の実効性は2回の実失敗で段階的に確認された。

- 2026-09-13の自然条件再試行（緑）では、capture/uploadがskipされ緑時非干渉のみ確認。
- 2026-09-14、run 34841787277（issue52 lane）で初めて実失敗時にcapture stepが起動したが、
  診断処理自体が長時間停止してartifact uploadに到達しなかった。この障害はIssue #315として
  分離し、PR #316でper-command timeout（15秒）/wall budget（150秒）/出力上限つきの
  bounded化を実装した（[spec 315](../../specs/315-bounded-failure-evidence-capture/spec.md)、
  status `implemented`）。
- 2026-09-15、run 34940500617（main、issue53 lane）でbounded化後初めての自然失敗時に
  artifactは生成されたが、capture stepが `reactivecircus/android-emulator-runner` の
  `emu kill`（07:22:07.964Z）後（07:22:09.957Z開始）に実行されたため、
  `adb-devices.txt` はdeviceなし、window/activity/HOME role等は `emulator-5554 not found`、
  logcatはtimeoutで、device依存の機構証拠は取得できなかった
  （[Issue #304 コメント 2026-09-15](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5676627394)）。
  **現行のstep構成では、次の自然再発でも機構証拠は取得できない。** captureをemulator
  生存中（emulator-runnerのscript内のfailure trap、またはrunner stepと同sessionで動く
  後続処理）へ移す順序修正がH1/H1'/H2の機構判別の前提となる。実装は本Issueの非対象で
  あり、別workflow PR（#315の系譜のfollow-up）として起票する判断を必要とする。
- 2026-09-16、capture 7〜10 の 4 failed boot でも順序問題を再確認した。いずれも
  capture step は `emu kill` 後の実行（例: 35035443254 は 23:32:30Z kill → 23:32:35Z
  capture、35048590610 attempt 1 は 02:45:21.380Z kill → 02:45:24.563Z capture、
  attempt 2 は 03:00:06Z kill → 03:00:08Z capture、35045487650 は 02:01:54Z kill →
  02:01:58Z capture）。35048590610 attempt 1 の
  [artifact 10427419407](https://github.com/nunu1733/NunuLauncher/actions/runs/35048590610/artifacts/10427419407)
  （6,784 bytes）が生成されたが、内容は device 依存の証拠を含まない。bounded化された
  capture step 自体は期待どおり短時間で完了し upload に到達している（順序修正後の
  実失敗であれば実証になる）。

- `dumpsys window windows`（失敗時のwindow列挙とz-order）
- `dumpsys window displays`（display状態）
- `cmd role get-role-holders android.app.role.HOME` とHOME intentのresolve結果
- `dumpsys activity top` / `dumpsys activity activities` / `dumpsys power`（activity遷移とinteractive状態の補助）
- `dumpsys dropbox --print system_app_anr` / `data_app_anr` / `system_server_wtf`、
  `/data/anr` の読み取り結果（ANRが示された場合のtrace）
- failure時の限定したlogcat（main/system/crash/events）、input、SurfaceFlinger状態、
  CPU/I/O pressure、build properties

理由は、ローカル強制runが「標準ランチャーを前面化すればCIと同じsignatureになる」ことを
示した一方、gateのfocused window 1行だけではその前面化がrole解決・z-order残留・起動競合
のどれかを判別できないためである。既存のCI captureを破棄する判断ではなく、現行の
1失敗+証拠を維持したまま、次の再発で機構を確定できる追加観測を残す判断である。
収集helper自体はローカルfake-`adb` smoke testで、成功・失敗コマンドの双方をartifactへ
残して元の処理を継続することを確認する。

## 残存リスク受容の判断基準（root cause 未確定のまま完了する場合）

次のすべてを満たす場合、受容の判断を記録して完了できる:

- gate 導入後、一定の観測期間（実施時に本 Issue へ記録する。目安: #300 の AC-6 と
  同等の連続 green 実績を超える追加 run）で environment anomaly の再発が無い。
- 強制状態試行で H1/H1'/H2 の機構実証に至らなかった（または再現しなかった）。
- 受容の根拠として、occluder 分類表・gate の証拠能力（1 失敗 + 証拠への変換実績）、
  「1 失敗 + 証拠 + 手動 rerun」運用の是非を含む判断を記録する。

## Next stage handoff（結論後の次段階）

- 結論が「provisioning 対策が必要」（例: lane provisioning での標準ランチャー無効化、
  ANR dialog の自動 dismiss 修復の gate への追加）である場合: 別 spec/plan（実装は
  別 PR。ci-test-portfolio.md 更新を伴いうる）を起票する。本 Issue は判断と根拠の
  記録まで行い、実装しない。
- **capture 順序修正（2026-09-15 時点で結論待ちと独立に前提となる課題。2026-09-16 の
  4 failed boot でも再確認）**: failure-time
  capture を emulator 生存中に実行する workflow 変更（emulator-runner の script 内
  failure trap 等）。2026-09-15 の観測により現行構成では機構証拠が取得不能と確認されて
  いるため、AC-3 の機構判別（H1/H1'/H2）に先立つ follow-up issue / PR（Issue #315 の
  系譜）として起票する判断を本 Issue で記録する。実装は本 Issue の非対象。
- 結論が「証拠保全の導入」である場合: failure-time artifactはPR #313/#316で実装済み。
  順序修正後の次の自然再発時にartifactを用いてH1/H1'/H2の機構判別へ進む。
- 結論が「運用受容」の場合: 追加の実装段階は無く、本 Issue の記録が成果である。

## Change set

| Area | Intended change |
|---|---|
| `specs/304-api36-window-focus-root-cause/plan.md`（本書） | 試行証跡（Verification evidence）・分類表の追記 |
| `specs/304-api36-window-focus-root-cause/spec.md` | 調査過程で契約の修正が必要になった場合の更新 |
| `.github/workflows/ci.yml` | API36 Issue #52/#53 laneのfailure-time captureとartifact upload |
| `tools/ci/capture-emulator-failure-evidence.sh` | emulatorのwindow/activity/ANR/logcat等のbest-effort収集 |
| `tools/ci/test_capture_emulator_failure_evidence.sh` | fake-`adb`によるhelper smoke test |
| 本 Issue | 結論・判断・分類表・run link の記録 |

production source、dependency、runtime test implementation は変更しない。

## Verification

| Acceptance criterion | Requirement | Evidence | 環境 |
|---|---|---|---|
| AC-1 強制状態での診断特定実証 | RC-AC-01, RC-AC-05 | 本 plan Verification evidence 節への試行記録（メッセージ実物つき） | ローカル api36 emulator（#305 head を使用する場合は手順 1 の記録付き） |
| AC-2 occluder 分類 | RC-AC-02 | gate capture の証拠行と分類表（本 Issue または本 plan） | CI（#305 merge 後）の自然発生 10 capture（標準ランチャー 5、ANR ダイアログ 5） |
| AC-3 root cause 結論または受容 | RC-AC-03 | 本 Issue の結論コメント（判断基準の適用記録つき） | — |
| AC-4 証拠保全の判断 | RC-AC-04 | 本 Issue の判断コメント（不足状態の列挙と理由つき） | — |

**AC-3の現在判定（2026-09-16 更新）**: 未完了。ローカルでは、最終occluderの直接前面化では
ないSystemUI ANRのboot-to-dialog経路と、system_server/SurfaceFlinger/WindowManagerの
停滞を観測できた。しかしCIと同じx86_64 image/runnerでANR traceまたはboot内遷移を取得
できておらず、ローカルの反証（CI相当resource条件では0/3）もあるため、H2を有力仮説へ
更新しただけでroot cause確定・残存リスク受容のいずれにも進めない。2026-09-15〜16には
自然再発がcapture 6〜10の5件に増え、同一run（35048590610）の連続bootで異なるoccluder型
（ANR dialog→標準ランチャー→green）が分岐するper-boot分布の直接例も得たが、判定基準2
（因果経路の制御再現または自然発生bootの遷移直接観測）を満たす証拠は依然未取得である。
failure-time保全は全failed bootで`emu kill`後の実行のため機構証拠を取得できず、
capture順序修正が引き続き前提課題。

含めるべき観点のうち、unit/contract/property/DB-integration は本 Issue の対象外
（調査のみ）。修正は失敗を再現するテストを伴う規約については、本 Issue の成果が
証拠・判断であり、test を伴わない理由（investigation Issue であること）をここに
記載する。

### Verification evidence

- **前提固定（2026-09-13）**: `git fetch origin main` 後の現行headは
  `3aa6e83a1f6dc331e9f6712c126c9ff58d050660`。PR #305はmerge済み
  （merge commit `0ea17a238b1d0668cf4b3ab16ac9fb663e2dd29e`）であるため、別branchではなく
  現行mainのgateを使用した。
- **build**: `./gradlew assembleLawnWithQuickstepGithubDebug` -> PASS
  (`BUILD SUCCESSFUL in 19s`)。JDK 21.0.12、Android SDK 36.1、AVD `issue142_api36`。
- **決定的状態test**: `InjectedInputEnvironmentStateInstrumentationTest`を
  `adb shell am instrument`で実行 -> **5 tests / 0 failures**。
- **強制状態**: `emulator-5654`（AVD `issue142_api36`）へdebug APKとandroidTest APKを
  install。初期状態はHOME role holderが
  `com.google.android.apps.nexuslauncher`、`mCurrentFocus`もNexusLauncherActivity。
  test `OnboardingOrganizationProposalInstrumentationTest#realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale`
  を開始し、host側の `mCurrentFocus` がLawnchairになった直後に
  `am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity` を実行して
  occluderを前面化した。
- **gate出力実物**:

  ```text
  java.lang.IllegalStateException: input environment prevented the launcher from resuming;
   input environment never reached a focused window;
  evidence=launcher-resume-timeout; interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{5a44e59 u0 com.google.android.apps.nexuslauncher/
  com.google.android.apps.nexuslauncher.NexusLauncherActivity},
  frontmostPackage=com.google.android.apps.nexuslauncher;
  LawnchairLauncher did not reach an attached, laid-out RESUMED state with fontScale=2.0 after HOME launch
  ```

  test resultは **1 test / 1 failure**、経過時間は13.631秒。修復対象の
  `interactive=false` / `keyguardLocked=true` ではなく、`interactive=true`・
  `keyguardLocked=false` のforeign launcher windowを分類した。
- **補助観測**: failure後も `cmd role get-role-holders android.app.role.HOME` は
  `com.google.android.apps.nexuslauncher`、HOME intent resolveも
  `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`、power stateは
  `mWakefulness=Awake`だった。これはrole/z-orderの追加観測が必要な理由を支持するが、
  host側でNexusを意図的に前面化した試行なので、CI bootの自然発生機構を確定する証拠ではない。
- **z-order補助観測（同じ強制手順の直後）**: `dumpsys window windows` では
  `mCurrentFocus` / `mFocusedWindow` と `topResumedActivity` がNexusを指し、同時に
  Lawnchair windowも列挙された。ただしLawnchair側は
  `mHasSurface=true isReadyForDisplay()=false`、`Surface: shown=false`、
  `DRAW_PENDING` だった。これは「foreign launcherがfocusを保持し、Lawnchairのwindowが
  foreground表示へ到達しない」というH1'の誘発状態を直接示す。ただしhost側の意図的な
  前面化であるため、CI bootで同じ状態が生じる機構の確定とは分ける。
- **CI signatureとの一致度**: localは `launcher-resume-timeout` の
  `input environment prevented the launcher from resuming` 接頭辞と、
  `interactive` / `keyguardLocked` / `focusedWindow` / `frontmostPackage` の型・値の
  並びがCI run 34704064012と一致した。よってRC-AC-01/05の「occluderを名指しする診断」
  は満たす。H1/H1'の自然発生機構とH2のANR発生機構は未確定のまま残る。
- **自然CIのANR occluder再捕捉（2026-09-13）**: run 34732479463のattempt 2を
  再実行し、issue53 laneの最初の失敗を確認した。`laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`
  の `ensureWindowFocused` が次を出力した。
  ```text
  input environment never reached a focused window;
  evidence=window-focus-gate:app.lawnchair.debug/app.lawnchair.LawnchairLauncher;
  interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{838ef30 u0 Application Not Responding: com.android.systemui},
  frontmostPackage=android; target window never gained focus within 15000ms
  ```
  これは自然bootでSystemUIのANR dialogがLawnchairより上位に残り、入力gateを直接
  阻害した証拠である。`Application Not Responding: com.android.systemui` を直接の
  occluderとして特定する点はAC-1/AC-2の自然CI証拠で支持されたが、SystemUIがANRに
  至ったboot内の因果経路、ならびにANR前のrole/resolve/activity/window遷移は未取得である。
- **NotificationShadeの実再現（2026-09-13）**: `nunu_qpr2_api36_1` にdebug APKと
  androidTest APKをinstallし、以下の対象testを次のコマンドで実行した。
  ```text
  /Users/nunu/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -r \
    -e class 'app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest#realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale' \
    app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  ```
  対象test:
  ```text
  app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest#realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale
  ```
  初回の2台同時Gradle実行では `nunu_qpr2_api36_1` が次で失敗した:
  ```text
  java.lang.IllegalStateException: input environment never reached a focused window;
  evidence=window-focus-gate:app.lawnchair.debug/app.lawnchair.LawnchairLauncher;
  interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{ea377ad u0 NotificationShade},
  frontmostPackage=com.android.systemui; target window never gained focus within 15000ms
  ```
  failure直後の `dumpsys window windows` は NotificationShade の
  `Surface: shown=true` / `isOnScreen=true`、NexusLauncherActivityの
  `mFocusedApp` / `topResumedActivity`を示した。`cmd role get-role-holders` は
  `com.google.android.apps.nexuslauncher`、powerは `mWakefulness=Awake` であり、
  非interactive/keyguardではない。
- **NotificationShadeの因果対照（2026-09-13）**: `input swipe 540 5 540 1800 600` で
  NotificationShadeを可視・focus保持にし、同じ対象testを実行すると上記の15秒gate失敗を
  再現した。その後 `input keyevent 4` でshadeを閉じ、同じtestを再実行すると
  **1 test / 0 failures、8.816s** で成功した。この対照は、system UI windowのfocus保持が
  gate失敗の直接原因であることを示す。一方、直接shadeを開く操作なので、CI bootで同じ
  状態が自然発生した機構の証明には使わない。
- **再起動対照（2026-09-13）**: 同じ `nunu_qpr2_api36_1` をrebootし、
  `sys.boot_completed=1`後にrole/focus/windowを確認した時点ではNexusLauncherActivityが
  focusを持ち、NotificationShadeは可視windowではなかった。したがって上記の実instrumentation
  failureは「reboot直後のshade残留」とまでは言えず、既存dirty stateまたはboot後の
  未取得イベントを含む仮説H2'として扱う。
- **AC-3継続観測: reboot後のSystemUI ANR（2026-09-13）**: `emulator-5554` の
  `nunu_qpr2_api36_1`（上記build fingerprint、試行時点で`app.lawnchair.debug` / `.test` 未導入、
  ただしwipe-dataなし）へ
  `adb reboot` を3回行い、各回 `sys.boot_completed=1` 後に0/4/8/12/16/20秒で
  `dumpsys window | rg 'mCurrentFocus='` を採取した。3回ともNexusLauncherActivityから
  `Application Not Responding: com.android.systemui` へ遷移した（1回目・2回目は20秒、
  3回目は16秒から）。各回の `dumpsys dropbox --print system_app_anr` に含まれた最新記録は
  次の通りだった。
  - iteration 1: `Process: com.android.systemui`、`Subject: executing service
    com.android.systemui/.SystemUIService, waited 20070ms`、`96% TOTAL`、
    `surfaceflinger 76%`、`system_server 43%`。
  - iteration 2: `SystemUIService, waited 20120ms`、`95% TOTAL`、
    `surfaceflinger 93%`、`system_server 41%`。
  - iteration 3: `com.android.systemui/.keyguard.KeyguardService, waited 20195ms`、
    `97% TOTAL`、`surfaceflinger 84%`、`system_server 37%`。
  同じdropbox証跡にはCPU pressure `avg10=74〜75`、I/O pressure `avg10=24〜34`も含まれた。
  SystemUI main threadは`ServiceManager.getService`またはSettings providerへのBinder待ち、
  Nexus側main threadは`DisplayController` → `WindowContextController.attachToDisplayArea`
  のWindowManager Binder待ち、RenderThreadはBufferQueue release待ちだった。つまり、
  focusを奪うANR dialogの前段にsystem_server/SurfaceFlinger/WindowManagerの処理停滞がある
  ことを、最終windowの直接前面化なしにbootから反復観測できた。この結果はH2と整合する
  強い機構証拠だが、AC-3の制御causal-path reproductionとは扱わない。
- **AC-3観測の反証/境界（2026-09-13）**: 同じAVDをCI相当の
  `-cores 2 -memory 4096 -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim`
  で再起動し、同じ40秒focus観測を3回実施したところ、3/3でNexusLauncherActivityが継続した。
  さらにdebug APKとandroidTest APKのinstall後60秒、Gradle connected経路の2クラス25 tests
  （`BUILD SUCCESSFUL in 54s`、25/25）でもSystemUI ANRは発生しなかった。比較用の
  `issue142_api36`（android-36 arm64 image）は3回のrebootで`system_app_anr`記録を増やさなかったが、
  これはCIのx86_64 imageと同一ではないため補助証拠に留める。この差により、上記のANR
  機構はCI capture 2/4/5と整合する有力な共通機構だが、CI runnerで同じsystem_server/SurfaceFlinger
  停滞が実際に起きたことを直接証明するものではない。
- **CI条件の再確認と追加rerun（2026-09-13）**: docs-onlyの現行PR branchへworkflow_dispatchした
  [run 34757507790](https://github.com/nunu1733/NunuLauncher/actions/runs/34757507790) は
  `paths-filter`によりissue53 laneがskipされたため、source実行のissue53 jobを
  [run 34732479463のrerun job 103724416322](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463/job/103724416322)
  として再試行した。同jobは同じ `android-36/google_apis/x86_64/pixel_7_pro`、2 cores、
  SwiftShader設定で、boot 52.050秒、`input keyevent 82`、`Failed to start Emulator console
  for 5554` を出した後、25/25 tests・`BUILD SUCCESSFUL in 7m 15s` で完了した。ANR/window
  gate failureは発生しなかったため、console warning単体は原因ではなく、同一CI条件でも
  per-bootの結果が分岐することを示す自然な非再現対照になった。ただしfailure側のANR trace
  が無い点は解消しておらず、AC-3のroot cause確定には進めない。
- **main baseline上での自然再発とfailure-time artifactの空振り（2026-09-15、CI log観測。
  本試行はローカル再実行ではなくIssue記録の確認）**: [run 34940500617](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617)
  （head `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd`、event `push`）のissue53 laneで
  `reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome` が
  `awaitResumedLauncher`（`OnboardingOrganizationProposalInstrumentationTest.kt:1200`）で
  1件失敗し、capture 1と同一の標準ランチャーsignatureを出力した。
  [instrumentation report artifact](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/artifacts/10384814650)
  にも同じfailureが保存されている。`gh run rerun 34940500617 --failed` 相当のrerun
  （[job 104294416831](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/job/104294416831)）
  は25/25 green、[final-status job](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/job/104296109004)
  もsuccess。同一SHAで初回のみ失敗したことからper-bootフレイクがmain上でも再確認された。
  同時生成された
  [failure-time evidence artifact](https://github.com/nunu1733/NunuLauncher/actions/runs/34940500617/artifacts/10384774861)
  は、`emu kill`（07:22:07.964Z）後のcapture step開始（07:22:09.957Z）のため
  device依存コマンドがすべて `emulator-5554 not found` / timeout となり、機構証拠
  （`dumpsys window`、HOME role、ANR trace、logcat）は未取得だった
  （[Issue #304 コメント 2026-09-15](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5676627394)）。
  capture順序修正が次の自然再発で機構証拠を得るための前提である。
- **2026-09-16 のCI run 監査（本試行はローカル再実行ではなくCI log/API観測）**:
  前回snapshot（2026-09-15T17:34Z）以降の全 CI workflow run（35000215963〜35088536909、
  main / `docs/issue-205-implemented` / `issue-205-implementation` / `issue-331-spec-plan`
  branch、成功run含めて `run_attempt` を照会し、rerunされたrunは過去attemptのjob一覧と
  log を実読）を監査した。結果: occluder capture 4件（capture 7〜10。上記 Current
  evidence の通り）、非gate Compose timeout 2 run（35000215963/35005008383）、
  HTTP 409 runner setup error 1 run（35042223633、shared-writer lane）、#205 diff 対象の
  unit test 失敗 1 attempt（35037367128 attempt 1、`organizer-unit-tests`）。main 上の
  CI（34986888281、35056438961）はいずれも attempt 1 で全 job 成功しており、main
  baseline での新規 capture なし。捕捉は PR #325 branch（`issue-205-implementation`）の
  CI に集中した（2026-09-15 23:32〜2026-09-16 03:00 UTC の約 3.5 時間窓）。

## Risks

- **再発が観測できない**: gate の緩和が機能すると、実発生の証拠が恒久的に得られない。
  このため残存リスク受容の判断基準を事前に固定した（上記）。ただし 2026-09-15〜16 の
  観測（capture 6〜10）により、修復対象外の occluder 系異常は緩和後も捕捉され続けて
  おり、この risk が現実化するのは「修復対象状態のみになった」場合に限られると
  見直せる。機構証拠（z-order/role/ANR trace）の供給が止まるリスクは capture 順序問題
  の解消まで引き続き有効である。
- **gateの後続変更**: PR #305はmerge済みで今回の試行は現行main上で行った。gateの
  診断契約が今後変更された場合は、同じ強制状態試行をやり直す必要がある。
- **強制状態の外部妥当性**: 人為的に最終 occluder を作った状態が、実発生の per-boot
  状態と同一とは限らない。signature一致はAC-1/AC-2の診断能力を支持するが、AC-3の
  因果機構の外部妥当性は担保しない。
- **ANR の強制再現は非決定的**: H2 の反証は強制ではなく再発時証拠の蓄積に依存する。
  今回はdirty/host条件を含むローカルreboot反復でSystemUI ANRとsystem_server/
  SurfaceFlinger停滞を3/3観測したが、CI相当の2 cores/4GB反復では0/3だった。証拠保全の
  導入判断（手順 4）と、CIと同一条件のANR trace取得がH2判別の鍵になる。
- **NotificationShadeの自然発生機構は未確定**: ローカルではfocus保持とtest失敗の因果対照を
  取れたが、reboot後のclean stateでは再現しなかった。CIでの表示開始時刻とboot/runner
  操作の証拠がない限り、dirty state・boot race・外部入力のいずれかを選べない。
- **failure-time captureが機構証拠を取得できない**: 保全の導入判断と実装（#313）、
  bounded化（#316）は完了しているが、2026-09-15の実失敗でcapture stepが`emu kill`後の
  実行だったためdevice依存の証拠は空振りだった。この構成のままでは、次の自然再発でも
  遷移・z-order・role state・ANR traceは取得できず、H1/H1'/H2のCI上の機構を確定できない。
  2026-09-16のcapture 7〜10（4 failed boot）でも同一の順序問題を再確認した。
  captureをemulator生存中へ移す順序修正が前提であり、別workflow PRとして起票判断を
  要する（Next stage handoff）。bounded化の実効性（timeout時もpartial artifactを残す
  こと）自体は、順序修正後の最初の実失敗まで検証機会がない。

## Explicitly unverified areas

- `reactivecircus/android-emulator-runner@v2` の boot 後 unlock（`input keyevent 82`
  相当）の正確な内部手順。run job log の実測（#300 plan 記録）による間接確認のみ。
- CI emulator image 上の既定の HOME role holder と、Lawnchair debug build が
  default HOME として設定されるタイミング。
- CI失敗bootの`dumpsys dropbox --print system_app_anr` / ANR trace、
  `dumpsys window windows`、`dumpsys activity top`、SystemUI/SurfaceFlingerのlogcat。
  これらがないため、自然CIでのsystem_server/SurfaceFlinger停滞を直接確認できていない。
  2026-09-15の実失敗では、failure-time保全が存在したうえでcapture順序（`emu kill`後）の
  ため取得に失敗しており、2026-09-16の4 failed bootでも再確認された。順序修正後の
  実失敗まで取得可能性自体が未検証のまま残る。
- API 36.1（Platform 36.1 / Build Tools 36.1.0）の window focus 遷移の framework
  内部挙動。
- gate 導入後の発生頻度（#305 merge 後の定量）。
- Issue #304 コメント 1 の記述「`am start -a MAIN -c HOME` をもってしても標準ランチャー
  が前面に留まった」における起動経路の詳細（main の `startLauncher` は明示 component
  指定であるため、コメントの表現と code の正確な対応）。

## Execution checklist

- [x] PR #305 merge 状態の確認と試行 head の選択記録（手順 1）
- [x] 強制状態試行の実施と Verification evidence への記録（手順 2、AC-1）
- [x] occluder 分類表の維持（手順 3、AC-2。2026-09-16 の capture 7〜10 を含む自然発生 10 例）
- [x] 証拠保全の判断記録（手順 4、AC-4）
- [x] failure-time evidence preservation helperとAPI36 laneへの接続を実装し、fake-`adb` smoke testを実施
- [x] 自然条件のIssue #53再試行を実施し、緑時はcapture/uploadがskipされることを確認（実失敗なし）
- [x] capture順序問題の記録（2026-09-15の実失敗でcapture stepが`emu kill`後に実行され機構証拠が未取得であること、および2026-09-16の4 failed bootでの再確認。手順 4 の実効性評価と Next stage handoff へ反映）
- [ ] capture順序修正のfollow-up issue / PR起票判断の本Issueへの記録（実装は非対象）
- [ ] H1/H1' 機構判別の実施または取得不能の明示（手順 5。前提としてcapture順序修正が必要）
- [ ] 結論または残存リスク受容の本 Issue への記録（手順 6、AC-3）
