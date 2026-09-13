# Investigation Plan: api36 UI lane burst の per-boot トリガー（`launcherWindowFocus=false` の occluder）特定

> Issue: #304
> Spec: [spec.md](./spec.md)
> Status: draft（investigation plan。fix plan ではない。root cause 未確定のため、対策の
> architecture は本 plan で決定しない）

## Current evidence

### Re-entry update

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
  `surfaceflinger` 84〜93%、`system_server` 37〜43%、CPU/I/O pressure上昇を示した。
  これは「SystemUI/system_server/SurfaceFlingerの起動時リソース停滞→system UI ANR dialogが
  focusを取得→launcher gateを阻害」という因果経路の制御再現であり、H2を強く支持する。
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
- **CI の artifact 現状**: issue53 lane の failure artifact は test report のみ
  （`.github/workflows/ci.yml:551-560`）。issue52 lane は test report ＋ UI evidence
  （`ci.yml:397-414`）。logcat / dumpsys は failure 時に残らない
  （spec 作成時点の main `f9afd8bfde` 実測。現行mainでもworkflowのartifact定義は同じ）。
- **ローカル強制 occluder capture（AC-1）**: API 36 AVD `issue142_api36`（device
  `emulator-5654`）へ現行mainのdebug APKとandroidTest APKをinstallし、Lawnchairのfocusを
  観測した直後に host側から
  `am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity` を実行した。
  `OnboardingOrganizationProposalInstrumentationTest#realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale`
  は `awaitResumedLauncher` のenvironment anomalyとして1件で失敗し、CI capture 1と同じ
  標準ランチャーのfocused/frontmost証拠を出力した。実物は下記Verification evidenceに記録する。

### 確認済み（code path 実読。現行 `origin/main` `3aa6e83a1f`、PR #305 merge後）

- **注入 site と診断の所在（main）**:
  `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt`
  - `describeInputEnvironment`（:935）— `launcherWindowFocus` / `activityFocus` /
    `treeFocus` / proposal 状態 / target geometry / `topOpenView` を 1 行に組み立てる。
    burst 失敗メッセージ（deliveredTap :1348、DPAD :927）に添えられる。**device 状態（interactive /
    keyguard / focused window / frontmost）は含まない**（gate 導入までの盲点だった領域）。
  - `awaitResumedLauncher`（:1126）— 120 回 × 100ms poll（最大約 12 秒）で
    `Stage.RESUMED` ＋ attached ＋ laid out の launcher を待つ。timeout メッセージ
    （:1151）は環境状態を含まない。
  - `startLauncher`（:1158）— **明示 component 指定**
    （`am start -n app.lawnchair.debug/app.lawnchair.LawnchairLauncher -a
    android.intent.action.MAIN -c android.intent.category.HOME`）。HOME intent の
    resolver 経由の暗黙起動ではない。この点は Issue コメント 1 の「`am start -a MAIN
    -c HOME` をもってしても標準ランチャーが前面に留まった」という解釈に対して、
    「明示 component 起動が HOME role 解決へどう扱われるか（redirect の有無）」を
    機構判別の中心問いにする根拠である。
  - `runShellCommand`（:1169）— `uiAutomation.executeShellCommand` の同期実行。
    強制状態の作成・観測にそのまま使える。
  - `awaitAccessibilityTextBounds`（:568）— `rootInActiveWindow` 前提の走査。
  - `sendKey`（:1184）— `sendKeyDownUpSync`。
- **lane 構成（main、`.github/workflows/ci.yml`）**:
  - issue53 job（:518-560）: 1 クラス filter（:550）。`reactivecircus/
    android-emulator-runner@v2`、api-level 36、target google_apis、x86_64、
    pixel_7_pro、disable-animations、boot timeout 900。
  - issue52 job（:363-414）: 4 クラス filter（:394）を 1 Gradle invocation で実行。
  - api35 lane（:328-335）は同構成で api-level 35。元 burst が同 head で green で
    あったことから、トリガーは api36 環境側に存在する。
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
- 発生頻度と、緩和後の再発継続の有無。

## Hypotheses（仮説と反証方法）

| ID | 仮説 | 支持する証拠 | 反対・弱化する証拠 | 反証・確認方法 |
|---|---|---|---|---|
| H1 | 一部の boot で default HOME role が標準ランチャーに解決され、HOME category 起動が role holder（標準ランチャー）へ効くことで標準ランチャーが前面に残る | occluder 1 の focused window が標準ランチャー。ローカルAPI36でもHOME role holderはNexusだった | 起動は明示component指定であり、暗黙HOME解決ではない（`startLauncher` :1158実測）。同じローカル端末でNexusがHOME role holderのままでも、明示的なLawnchair起動はLawnchairへfocusを移したため、role holder単独ではこのfailureを説明できない | CI boot上のrole stateと、失敗時の実際のactivity/window遷移を同時に取得する。role stateだけでは不足し、`dumpsys window windows` と起動結果の組み合わせが必要 |
| H1' | HOME role は不変で、標準ランチャーの window が z-order 上に残存し焦点を保持する（起動は成功するが焦点が取れない） | occluder 1 で `awaitResumedLauncher` が timeout = Lawnchair が RESUMED に到達していない。焦点が標準ランチャーであることと整合。ローカルでもLawnchairのfocus取得後にNexusを前面化すると同じfailure signatureになった | ローカルの強制操作はCIの自然発生機構ではない。Lawnchair未RESUMEDの説明にはならない（RESUMED判定はlifecycleと独立） | gate 証拠にz-orderが無いため、再発時に `dumpsys window windows` を取得できるようにして判別する（手順4の判断材料）。ローカルではoccluder強制状態で同等dumpを取得できる |
| H2 | system UI の ANR ダイアログが焦点を保持する boot がある（runner 負荷等で systemui が不調になる boot 単位の劣化） | occluder 2/4/5 の focused window が `Application Not Responding: com.android.systemui`。同 run の非 gate test も timeout。ローカルclean-boot反復では、SystemUIのservice/keyguard ANRと、`system_server`/`surfaceflinger`高負荷、WindowManager/Settings Binder待ちを3/3で観測 | CI runnerのANR traceは未取得。ローカルをCI相当の2 cores/4GBで反復すると0/3であり、ローカルの再現はhost/image/dirty state依存の可能性がある | ANR の強制再現をoracleにせず、CI failure時にlogcat / ANR trace / dumpsysをartifact化して、自然CI bootでも同じsystem_server/SurfaceFlinger停滞があるか確認する。現時点では「SystemUI ANR dialogが直接occluder」「その有力な前段機構はresource/display path停滞」までを支持し、CI root cause確定とは扱わない |
| H2' | system UI の NotificationShade が bootまたは直前操作後に可視・focus保持状態で残り、Lawnchairの明示起動より上位に居続ける | ローカル `nunu_qpr2_api36_1` で実instrumentation testが `focusedWindow=...NotificationShade`, `frontmostPackage=com.android.systemui` のまま15秒gate timeout。`input swipe` で同状態を制御再現し、`KEYCODE_BACK` で閉じた後は同じtestがgreen | 今回の自然CI captureではNotificationShadeそのものは未取得。再起動後のcleanな同AVDではshadeは閉じており、既存のdirty stateまたはboot内の別経路の可能性が残る | failure時の`dumpsys window windows`とSystemUI state/logcatをCI artifact化し、NotificationShadeの表示開始イベントとboot/runner操作の順序を照合する。直接shadeを開く試行は因果の対照には使うが、CIの自然発生機構の確定とは分ける |
| H3 | `input keyevent 82` 直後の keyguard 解除不成立・解除と HOME 起動の競合 | Issue 本文の仮説候補 | occluder 1〜5 はいずれも最終 capture で `keyguardLocked=false`。KEYCODE_WAKEUP / dismiss-keyguard の修復実装済み | 最終状態の `keyguardLocked=false` により、解除済み状態が継続している単純な説明は弱化する。ただし解除・起動の途中に競合があった可能性までは否定できないため、遷移証拠がない限り H3 は未確定とする |
| H4 | 非 interactive（screen off）boot | Issue 本文の仮説候補 | 現行の自然発生 occluder capture 1〜5 は最終時点で `interactive=true`。KEYCODE_SLEEP 強制は、PR #305後の現行gateが wakeup 修復して green にできることを示す | 現行gateでは非interactive状態は修復・緩和され、残るpost-gate occluder failureの原因ではない。一方、pre-gate burst [34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)の遷移中に寄与した可能性は、interactive/keyguard/window状態を保持していないため未確認とする |
| H5 | API 36.1 固有の window focus 遷移の遅延・欠落 | Issue 本文の仮説候補（api36 限定の発生） | gate の 15 秒待ちで焦点が到達しなかったため、15 秒以内に解消する単純な遅延説は弱化する。api35 が同 head で green な事実も単純な遅延だけでは説明しにくい | 15 秒超の遅延や完了しない遷移までは現証拠から否定できない。自然発生時の activity/window 遷移または待機後の状態を観測し、単純な遅延・欠落・occluder保持を区別する |

## Occluder classification

| Capture | 型 | 証拠 | 判定 |
|---|---|---|---|
| CI run [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012) | 標準ランチャー activity | head `083c902973d13e851ecda32137af5e9bbf3da323`、event `pull_request`、`interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | 証拠行だけで標準ランチャー型と分類可能 |
| CI run [34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836) | system UI ANR dialog | head `820dae07557631273f46000a01a716ad2b5bbb6c`、event `workflow_dispatch`、`interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 証拠行だけでANR dialog型と分類可能 |
| CI run [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 1 | 標準ランチャー activity | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、既存 capture 1 と同じ `com.google.android.apps.nexuslauncher/.NexusLauncherActivity` frontmost | 証拠行だけで標準ランチャー型と分類可能。自然発生での再発例 |
| CI run [34733839798](https://github.com/nunu1733/NunuLauncher/actions/runs/34733839798) | system UI ANR dialog | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、既存 capture 2 と同じ `Application Not Responding: com.android.systemui` | 証拠行だけでANR dialog型と分類可能。自然発生での再発例 |
| CI run [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463) attempt 2 | system UI ANR dialog | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `pull_request`、2026-09-13 10:31 UTC、issue53 lane。最初の失敗で `interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 同じAPI 36.1 issue53 laneの自然bootで再発した証拠。ANRに至るboot内の原因・遷移は未取得 |
| CI run [34733180391](https://github.com/nunu1733/NunuLauncher/actions/runs/34733180391) | 非gate Compose timeout | head `5420916a0e4cf0b3badc616303e3076cea3f912c`、event `workflow_dispatch`、`previewHeadingRestoresFocus...` の `ComposeTimeoutException` | occluder captureではなく、既存の非gateフレイクとして分類から分離 |
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
   自然発生 5 capture（標準ランチャー 2 例、ANR ダイアログ 3 例）を現集合とし、分類表を
   本 Issue または本 plan に維持する。新規 capture ごとに run link と head SHA を添える。
4. **証拠保全の判断**（RC-AC-04、終了条件 2）: gate 証拠が保持しない状態
   （`dumpsys window windows` の z-order、`cmd role get-role-holders` の HOME role、
   failure 時 logcat、ANR trace、NotificationShadeの表示状態）を列挙し、各不足がどの仮説
   （H1/H1'/H2/H2'/H5）の判別に
   必要かを対応づけた上で、CI failure 時 artifact 化（logcat / dumpsys）の導入と理由を
   [Issue #304 の調査コメント](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5652168567)
   に記録済みである。実装は別 PR（workflow 変更は全 gate 実行の対象。
   ci-test-portfolio.md の管轄）に分離する。
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

**判断: failure時の追加証拠保全を導入する。実装は本Issueでは行わず、workflow変更を
別PRで実施する。** 最小限の候補は次の通りである。

- `dumpsys window windows`（失敗時のwindow列挙とz-order）
- `cmd role get-role-holders android.app.role.HOME` とHOME intentのresolve結果
- `dumpsys activity top` / `dumpsys power`（activity遷移とinteractive状態の補助）
- failure時の限定したlogcat（main/system/crash/events）と、ANRが示された場合のtrace

理由は、ローカル強制runが「標準ランチャーを前面化すればCIと同じsignatureになる」ことを
示した一方、gateのfocused window 1行だけではその前面化がrole解決・z-order残留・起動競合
のどれかを判別できないためである。既存のCI captureを破棄する判断ではなく、現行の
1失敗+証拠を維持したまま、次の再発で機構を確定できる追加観測を残す判断である。

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
- 結論が「証拠保全の導入」である場合: 同様に別 PR（workflow 変更）で実装する。
- 結論が「運用受容」の場合: 追加の実装段階は無く、本 Issue の記録が成果である。

## Change set

| Area | Intended change |
|---|---|
| `specs/304-api36-window-focus-root-cause/plan.md`（本書） | 試行証跡（Verification evidence）・分類表の追記 |
| `specs/304-api36-window-focus-root-cause/spec.md` | 調査過程で契約の修正が必要になった場合の更新 |
| 本 Issue | 結論・判断・分類表・run link の記録 |

production source、test implementation、CI workflow、dependency は変更しない。

## Verification

| Acceptance criterion | Requirement | Evidence | 環境 |
|---|---|---|---|
| AC-1 強制状態での診断特定実証 | RC-AC-01, RC-AC-05 | 本 plan Verification evidence 節への試行記録（メッセージ実物つき） | ローカル api36 emulator（#305 head を使用する場合は手順 1 の記録付き） |
| AC-2 occluder 分類 | RC-AC-02 | gate capture の証拠行と分類表（本 Issue または本 plan） | CI（#305 merge 後）の自然発生 5 capture（標準ランチャー 2、ANR ダイアログ 3） |
| AC-3 root cause 結論または受容 | RC-AC-03 | 本 Issue の結論コメント（判断基準の適用記録つき） | — |
| AC-4 証拠保全の判断 | RC-AC-04 | 本 Issue の判断コメント（不足状態の列挙と理由つき） | — |

**AC-3の現在判定（2026-09-13）**: 未完了。ローカルでは、最終occluderの直接前面化では
ないSystemUI ANRのboot-to-dialog経路と、system_server/SurfaceFlinger/WindowManagerの
停滞を観測できた。しかしCIと同じx86_64 image/runnerでANR traceまたはboot内遷移を取得
できておらず、ローカルの反証（CI相当resource条件では0/3）もあるため、H2を有力仮説へ
更新しただけでroot cause確定・残存リスク受容のいずれにも進めない。

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
  failureは「clean reboot直後のshade残留」とまでは言えず、既存dirty stateまたはboot後の
  未取得イベントを含む仮説H2'として扱う。
- **AC-3制御再現: reboot後のSystemUI ANR（2026-09-13）**: `emulator-5554` の
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
  ことを、最終windowの直接前面化なしにbootから再現できた。
- **AC-3制御再現の反証/境界（2026-09-13）**: 同じAVDをCI相当の
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

## Risks

- **再発が観測できない**: gate の緩和が機能すると、実発生の証拠が恒久的に得られない。
  このため残存リスク受容の判断基準を事前に固定した（上記）。
- **gateの後続変更**: PR #305はmerge済みで今回の試行は現行main上で行った。gateの
  診断契約が今後変更された場合は、同じ強制状態試行をやり直す必要がある。
- **強制状態の外部妥当性**: 人為的に最終 occluder を作った状態が、実発生の per-boot
  状態と同一とは限らない。signature一致はAC-1/AC-2の診断能力を支持するが、AC-3の
  因果機構の外部妥当性は担保しない。
- **ANR の強制再現は非決定的**: H2 の反証は強制ではなく再発時証拠の蓄積に依存する。
  今回はdirty/host条件を含むローカルclean-boot反復でSystemUI ANRとsystem_server/
  SurfaceFlinger停滞を3/3観測したが、CI相当の2 cores/4GB反復では0/3だった。証拠保全の
  導入判断（手順 4）と、CIと同一条件のANR trace取得がH2判別の鍵になる。
- **NotificationShadeの自然発生機構は未確定**: ローカルではfocus保持とtest失敗の因果対照を
  取れたが、reboot後のclean stateでは再現しなかった。CIでの表示開始時刻とboot/runner
  操作の証拠がない限り、dirty state・boot race・外部入力のいずれかを選べない。
- **CI workflow 触れず制約**: z-order・role state が CI で取得できない間、H1/H1' の
  判別がローカル誘発に限られる可能性がある。その場合は判断基準を満たさないため、
  結論を先延ばしにするか、証拠保全の導入を判断する。
- **追加保全は未実装**: failure時のlogcat/dumpsys artifact導入は必要と判断済みで、
  別PRに分離した。
  自然発生captureは追加されたが、遷移・z-order・logcat/ANR traceが未取得のため、
  H1/H1'とH2のCI上の機構はまだ確定できない。ローカルではH2の有力な因果経路を
  観測できたが、外部妥当性は未確認である。

## Explicitly unverified areas

- `reactivecircus/android-emulator-runner@v2` の boot 後 unlock（`input keyevent 82`
  相当）の正確な内部手順。run job log の実測（#300 plan 記録）による間接確認のみ。
- CI emulator image 上の既定の HOME role holder と、Lawnchair debug build が
  default HOME として設定されるタイミング。
- CI失敗bootの`dumpsys dropbox --print system_app_anr` / ANR trace、
  `dumpsys window windows`、`dumpsys activity top`、SystemUI/SurfaceFlingerのlogcat。
  これらがないため、自然CIでのsystem_server/SurfaceFlinger停滞を直接確認できていない。
- API 36.1（Platform 36.1 / Build Tools 36.1.0）の window focus 遷移の framework
  内部挙動。
- gate 導入後の発生頻度（#305 merge 後の定量）。
- Issue #304 コメント 1 の記述「`am start -a MAIN -c HOME` をもってしても標準ランチャー
  が前面に留まった」における起動経路の詳細（main の `startLauncher` は明示 component
  指定であるため、コメントの表現と code の正確な対応）。

## Execution checklist

- [x] PR #305 merge 状態の確認と試行 head の選択記録（手順 1）
- [x] 強制状態試行の実施と Verification evidence への記録（手順 2、AC-1）
- [x] occluder 分類表の維持（手順 3、AC-2）
- [x] 証拠保全の判断記録（手順 4、AC-4）
- [ ] H1/H1' 機構判別の実施または取得不能の明示（手順 5）
- [ ] 結論または残存リスク受容の本 Issue への記録（手順 6、AC-3）
