# Investigation Plan: api36 UI lane burst の per-boot トリガー（`launcherWindowFocus=false` の occluder）特定

> Issue: #304
> Spec: [spec.md](./spec.md)
> Status: draft（investigation plan。fix plan ではない。root cause 未確定のため、対策の
> architecture は本 plan で決定しない）

## Current evidence

### Re-entry update

- 2026-09-13 に Issue本文・全コメントと `origin/main` を再取得した。前回のsnapshot
  （`origin/main`=`f9afd8bfde121932c0c8ed965225d52a84d86ab4`、snapshot commit
  `1e613e142a585c9ad2a2f718c682344b097960dc`）は再利用せず、現行の
  `origin/main`=`3aa6e83a1f6dc331e9f6712c126c9ff58d050660` を基準に確認した。
- PR #305 は merge済みで、merge commitは
  `0ea17a238b1d0668cf4b3ab16ac9fb663e2dd29e`。したがって以降のgate試行は
  #305の別branchではなく、現行mainのgateを使用した。
- 現行mainには後続の PR #310（Issue #308 の Compose focus同期安定化）も含まれるが、
  今回の試行対象は #304 のoccluder診断経路であり、#308の既存フレイクとは分離して扱う。

### 確認済み（CI 実測。run link・head SHA・確認日つき）

- **元 burst（gate 導入前）**: [run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335)
  attempt 1（issue53 lane、2026-09-12 確認）: 20 test 中 11 失敗 = touch 注入喪失 8
  （すべて `events=[]`・`launcherWindowFocus=false`・proposal は open/attached/shown）＋
  DPAD traversal 喪失 1 ＋ accessibility frontmost 不一致 1 ＋ `awaitResumedLauncher`
  timeout 1。同一 head の api35 lane は green。rerun green。
- **occluder capture 1（標準ランチャー）**: [run 34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012)
  （head `083c902973`、2026-09-12 16:13 UTC、issue53 lane）:
  `awaitResumedLauncher` timeout が `classifyLauncherAwaitTimeout` により
  environment anomaly として 1 失敗に停留し、
  `evidence=launcher-resume-timeout; interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... com.google.android.apps.nexuslauncher/.NexusLauncherActivity},
  frontmostPackage=com.google.android.apps.nexuslauncher` を出力
  （[Issue #304 コメント 1](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647372032)、
  2026-09-12 UTC 確認）。
- **occluder capture 2（ANR ダイアログ）**: [run 34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836)
  （2026-09-12 17:54 UTC、issue52 lane）: window focus gate が
  `evidence=window-focus-gate:app.lawnchair.debug/androidx.activity.ComponentActivity;
  interactive=true, keyguardLocked=false,
  focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui},
  frontmostPackage=android` を出力。同一 run では gate を通らない別 test の
  `waitUntil(5_000)` timeout も発生しており、system UI が不調な boot だった裏付け
  （[Issue #304 コメント 2](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647683394)、
  2026-09-12 UTC 確認）。
- **修復対象状態は burst を再現しない**: KEYCODE_SLEEP 強制（`mWakefulness=Asleep`）→
  gate が wakeup（実行後 `Awake`）→ green（PR #305 本文の Verification evidence、
  ローカル api36 AVD `issue142_api36`、2026-09-12 実施）。非 interactive /
  keyguard は gate の自動修復対象であり、証拠を残さない。
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
- 発生頻度と、緩和後の再発継続の有無。

## Hypotheses（仮説と反証方法）

| ID | 仮説 | 支持する証拠 | 反対・弱化する証拠 | 反証・確認方法 |
|---|---|---|---|---|
| H1 | 一部の boot で default HOME role が標準ランチャーに解決され、HOME category 起動が role holder（標準ランチャー）へ効くことで標準ランチャーが前面に残る | occluder 1 の focused window が標準ランチャー。ローカルAPI36でもHOME role holderはNexusだった | 起動は明示component指定であり、暗黙HOME解決ではない（`startLauncher` :1158実測）。同じローカル端末でNexusがHOME role holderのままでも、明示的なLawnchair起動はLawnchairへfocusを移したため、role holder単独ではこのfailureを説明できない | CI boot上のrole stateと、失敗時の実際のactivity/window遷移を同時に取得する。role stateだけでは不足し、`dumpsys window windows` と起動結果の組み合わせが必要 |
| H1' | HOME role は不変で、標準ランチャーの window が z-order 上に残存し焦点を保持する（起動は成功するが焦点が取れない） | occluder 1 で `awaitResumedLauncher` が timeout = Lawnchair が RESUMED に到達していない。焦点が標準ランチャーであることと整合。ローカルでもLawnchairのfocus取得後にNexusを前面化すると同じfailure signatureになった | ローカルの強制操作はCIの自然発生機構ではない。Lawnchair未RESUMEDの説明にはならない（RESUMED判定はlifecycleと独立） | gate 証拠にz-orderが無いため、再発時に `dumpsys window windows` を取得できるようにして判別する（手順4の判断材料）。ローカルではoccluder強制状態で同等dumpを取得できる |
| H2 | system UI の ANR ダイアログが焦点を保持する boot がある（runner 負荷等で systemui が不調になる boot 単位の劣化） | occluder 2 の focused window が `Application Not Responding: com.android.systemui`。同 run の非 gate test も timeout | 発生機構（なぜ ANR に至るか）は未観測 | ANR の強制再現は非決定的であり oracle にしない。再発時に logcat / ANR trace が取れるかを証拠保全の判断で評価。型としては occluder 1 と独立に「焦点保持 system window が存在する boot」として分類 |
| H3 | `input keyevent 82` 直後の keyguard 解除不成立・解除と HOME 起動の競合 | Issue 本文の仮説候補 | occluder 1・2 とも `keyguardLocked=false`。KEYCODE_WAKEUP / dismiss-keyguard の修復実装済み | gate 証拠の `keyguardLocked` field で反証済みと扱う。ただし `isKeyguardLocked` が false でも keyguard アニメーション中の遷移等の中間状態は理論上あり得るため、再発時証拠で継続確認 |
| H4 | 非 interactive（screen off）boot | Issue 本文の仮説候補 | occluder 1・2 とも `interactive=true`。KEYCODE_SLEEP 強制は wakeup 修復で green | 反証済みと扱う（修復対象であり burst の原因にならないことまで含めて実証済み） |
| H5 | API 36.1 固有の window focus 遷移の遅延・欠落 | Issue 本文の仮説候補（api36 限定の発生） | gate の 15 秒待ちでも焦点が到達しなかった = 遅延ではなく「保持」である。api35 が同 head で green な事実は遷移が遅いだけなら説明が難しい | 遷移遅延説で残す場合は、焦点が最終的に到達する状態を観測する必要がある。gate が 15 秒で切り上げるため、再発時に待ち延長なしで観測できる範囲（証拠行）で判別する |

## Occluder classification

| Capture | 型 | 証拠 | 判定 |
|---|---|---|---|
| CI run [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012) | 標準ランチャー activity | `interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | 証拠行だけで標準ランチャー型と分類可能 |
| CI run [34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836) | system UI ANR dialog | `interactive=true`, `keyguardLocked=false`, `focusedWindow=...Application Not Responding: com.android.systemui`, `frontmostPackage=android` | 証拠行だけでANR dialog型と分類可能 |
| Local `issue142_api36` forced run (2026-09-13) | 標準ランチャー activity | `interactive=true`, `keyguardLocked=false`, `focusedWindow=...com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, `frontmostPackage=com.google.android.apps.nexuslauncher` | CI run 34704064012と同じoccluder型。自然発生機構の証明ではなく、診断能力の誘発実証 |

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
   非 interactive / その他 / 不明）へ分類する。既存 2 capture（occluder 1・2）を
   初期集合とし、分類表を本 Issue または本 plan に維持する。新規 capture ごとに
   run link と head SHA を添える。
4. **証拠保全の判断**（RC-AC-04、終了条件 2）: gate 証拠が保持しない状態
   （`dumpsys window windows` の z-order、`cmd role get-role-holders` の HOME role、
   failure 時 logcat、ANR trace）を列挙し、各不足がどの仮説（H1/H1'/H2/H5）の判別に
   必要かを対応づけた上で、CI failure 時 artifact 化（logcat / dumpsys）の導入/不導入
   と理由を本 Issue へ記録する。導入と判断した場合も実装は別 PR
   （workflow 変更は全 gate 実行の対象。ci-test-portfolio.md の管轄）。
5. **H1/H1' の機構判別**: 標準ランチャー occluder が再捕捉された場合（または手順 2 の
   強制で同等状態を作れた場合）、HOME role state と z-order の観測（手順 4 の判断結果
   に従い、CI で取得できるようになった手段またはローカル再現）により、role 解決起因
   （H1）と z-order 残留（H1'）を判別する。判別に必要な観測が取得不能な場合は、
   そのことを明示して未確定部分を残した結論または残存リスク受容へ進む。
6. **結論の記録**（RC-AC-03、終了条件 3）: 下記の判断基準を適用し、本 Issue へ結論を
   記録する。再発が観測できなくなった場合は残存リスク受容の判断と根拠を記録して
   完了とする。

## Root cause 確定の判断基準

トリガーを「確定」と記録するのは、次の 3 条件を満たす場合のみである:

1. **観測**: 失敗時に存在した状態が gate 証拠（または同等の adb 取得証拠）で
   捕捉され、焦点を保持していた window の owner が特定されている。
2. **機構**: その状態が per-boot で生じる経路の説明があり、誘発再現（手順 2 の強制
   状態と同等の操作）または boot 上の直接観測（role state / z-order 等）により
   裏付けが取れている。
3. **整合**: 誘発状態が CI シグネチャと一致する（同一の gate 接頭辞、証拠 field の
   値の型、失敗形態）。一致しない場合は差異を明記する。

複数 occluder 型が残る場合は「単一 root cause」とまとめず、型ごとの分類として
記録する（occluder 1 と 2 は既に異種である）。

## Evidence preservation assessment

現行gateの1行証拠は、焦点を保持したoccluderの分類には十分である。一方で、今回の
標準ランチャー型についてH1（HOME role / resolver経路）とH1'（z-order残留）を区別する
には不足している。ANR dialog型についても、発生機構を確認するlogcat/ANR traceがない。

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
| AC-2 occluder 分類 | RC-AC-02 | gate capture の証拠行と分類表（本 Issue または本 plan） | CI（#305 merge 後）＋既存 2 capture |
| AC-3 root cause 結論または受容 | RC-AC-03 | 本 Issue の結論コメント（判断基準の適用記録つき） | — |
| AC-4 証拠保全の判断 | RC-AC-04 | 本 Issue の判断コメント（不足状態の列挙と理由つき） | — |

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

## Risks

- **再発が観測できない**: gate の緩和が機能すると、実発生の証拠が恒久的に得られない。
  このため残存リスク受容の判断基準を事前に固定した（上記）。
- **gateの後続変更**: PR #305はmerge済みで今回の試行は現行main上で行った。gateの
  診断契約が今後変更された場合は、同じ強制状態試行をやり直す必要がある。
- **強制状態の外部妥当性**: 人為的に作った occluder 状態が、実発生の per-boot 状態と
  同一とは限らない。判断基準の 3（シグネチャ整合）で担保する。
- **ANR の強制再現は非決定的**: H2 の反証は強制ではなく再発時証拠の蓄積に依存する。
  証拠保全の導入判断（手順 4）が H2 判別の鍵になる。
- **CI workflow 触れず制約**: z-order・role state が CI で取得できない間、H1/H1' の
  判別がローカル誘発に限られる可能性がある。その場合は判断基準を満たさないため、
  結論を先延ばしにするか、証拠保全の導入を判断する。
- **追加保全は未実装**: failure時のlogcat/dumpsys artifact導入は別PRに分離したため、
  次の自然発生captureまではH1/H1'とH2の機構を確定できない。

## Explicitly unverified areas

- `reactivecircus/android-emulator-runner@v2` の boot 後 unlock（`input keyevent 82`
  相当）の正確な内部手順。run job log の実測（#300 plan 記録）による間接確認のみ。
- CI emulator image 上の既定の HOME role holder と、Lawnchair debug build が
  default HOME として設定されるタイミング。
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
