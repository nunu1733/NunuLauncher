# Issue #524 runtime検証matrix — API 37 Quickstep provider対応 (QUICKSTEP_MAX_SDK 36→37)

- 実施日: 2026-10-07
- 対象head: `96746bc161` (branch `issue-524-api37-quickstep`, `docs(524): ADR-0018 revision 8 ...`)
- 変更前対照: `e8aced7dbe` (`audit(532): independent audit record for PR 535 Phase 2 ...`)
- spec: `specs/524-api37-quickstep-provider/spec-lite.md` (accepted, main)
- この記録は検証証跡であり、正本の判断はIssue #524 / spec / ADR-0018に従う。

## 1. Build結果

| target | commit | 結果 | 証跡APK | sha256 |
|---|---|---|---|---|
| spotlessCheck | `96746bc161` | BUILD SUCCESSFUL in 10s | — | — |
| pre-change release (`assembleLawnWithQuickstepGithubRelease`) | `e8aced7dbe` (detached) | BUILD SUCCESSFUL in 6m 40s | `apk/pre-change-release-maxSdk36.apk` | `569faa7a297ab5cda9c13fa03c5d9fa10e0a5e88b45224caac3678aec88b3dfd` |
| head debug (`assembleLawnWithQuickstepGithubDebug`) | `96746bc161` | BUILD SUCCESSFUL in 8m 14s (debug+release同時run) | `apk/head-debug.apk` | `06bdae9566a2331774878f37098eeeec097c319b19dc8058c26c91dc5e6f0570` |
| head release (`assembleLawnWithQuickstepGithubRelease`) | `96746bc161` | 同上 | `apk/head-release.apk` | `78504433e0544af41cbff31396101bcf35a1246ed16e750ee55ac599bd5b79b9` |

静的確認（manifest placeholder `xyz.paphonb.quickstepswitcher.min/maxSdk`）:

- pre-change release: `35 / 36`
- head release: `35 / 37`
- head debug: `0 / 100000`（debug override）

初回build時に `local.properties` がworktreeになくSDK location未定義で失敗したため
`sdk.dir=$HOME/Library/Android/sdk` を作成し、submodule `platform_frameworks_libs_systemui`
を `git submodule update --init --recursive` でcheckoutした（両者ともAGENTS.mdの検証済み手順）。

## 2. 検証matrix結果（spec Verification (a)〜(e)）

overlay値・各APKのpackageName:

- debug用overlay: `app.lawnchair.debug/com.android.quickstep.RecentsActivity`（probe作成の
  `nunu.overlay.quickstepconfig` を流用）
- release用overlay: `app.lawnchair/com.android.quickstep.RecentsActivity`（同一package名のまま
  APK差し替え。aapt2 compile/link → zipalign → apksigner（debug keystore）で新規作成）

### 総合判定

| leg | 構成 | 判定 |
|---|---|---|
| (a) | API 37 debug @ head ＋ debug用overlay | **FAIL** — provider bind時にlauncher processがクラッシュ（API 37 hidden API break）。SDK gate自体は正しく開いている（"disabling recents" 無し） |
| (b) | API 37 release @ head ＋ release用overlay | **FAIL** — 同一signature（debug/releaseで同一クラッシュ） |
| (c) | API 37 pre-change release (maxSdk 36) ＋ release用overlay（非system adb install） | **PASS（SDK gate / sheet oracle限定。provider bind oracleは未実施）** — `checkRecentsComponent=true`＋`compatible=false`＋`quickstep_incompatible` sheet表示・"disabling recents" 無し（specどおり）。非systemのためSystemUIはbindせず、overview不成立はmaxSdk差だけでは説明されない。provider-levelのclean効果対照はpriv-app maxSdk 36ではgate非依存crashが先行するため採取不可（Finding 2が制約） |
| (d) | API 37 stock構成（overlay削除）＋ head release | **PASS** — 挙動変化なし: sheet無し、"disabling recents" 診断log有り、system overviewはstock providerで成立 |
| (e) | API 36 debug @ head ＋ debug用overlay（priv-app） | **PASS** — `isConnected=true`、Lawnchair overview成立、task切替成功。API 36ではprovider pathが機能し、hidden API breakはAPI 37固有と実証 |
| (d)-API36 | API 36 stock構成 | **PASS** — sheet無し、"disabling recents" 有り |

### Finding 1（構成要件）: provider pathにはsystem/priv-appインストールが必要

AOSP `LauncherProxyService.updateEnabledState()` は
`resolveServiceAsUser(mQuickStepIntent, MATCH_SYSTEM_ONLY, user)` で有効判定するため、
`adb install` のlauncher（FLAG_SYSTEM無し）は `mIsEnabled=false` となりSystemUIは決してbindしない
（`a-nosystem-bind-evidence.txt`）。QuickSwitchがMagisk moduleでlauncherをsystem領域へ置くのは
この要件のため。specの「RRO overlay preinstall」に加えて、本検証ではlauncher APKを
`/product/priv-app/` へ配置し、privapp-permissions allowlist XML
（/product/etc/permissions/privapp-permissions-*.xml、13 permission）を追加した。
allowlist無しのpriv-appは `Signature|privileged permissions not in privileged permission
allowlist` でsystem_serverがboot loopした（XMLはクラッシュメッセージが列挙したpermission群）。

### Finding 2（API 37 hidden API break）: IWindowManager.createInputConsumer

provider構成済みAPI 37では、SystemUIがlauncherの `TouchInteractionService` をbindした瞬間、
`onUserUnlocked → InputConsumerController.registerInputConsumer` で:

```
java.lang.NoSuchMethodError: No interface method createInputConsumer(
  Landroid/os/IBinder;Ljava/lang/String;ILandroid/view/InputChannel;)V
  in class Landroid/view/IWindowManager; ...
  at com.android.systemui.shared.system.InputConsumerController.registerInputConsumer(InputConsumerController.java:143)
  at com.android.quickstep.TouchInteractionService.onUserUnlocked(TouchInteractionService.java:873)
```

が発生し、launcher processがクラッシュループする（debug/release両方、pre/post変更両方で同一）。
framework側の一次出力（`hidden-api-createInputConsumer-dexdump.txt`。両AVDから
`/system/framework/framework.jar` をpullし、build-tools 37.0.0 `dexdump` で
`Landroid/view/IWindowManager;` を抽出。取得日2026-10-07）:

- API 37.0 device（emulator-5554 / AVD `issue526_api37_pixel_9a`）:
  `createInputConsumer (Landroid/os/IBinder;Ljava/lang/String;I)Landroid/view/InputChannel;`
  （return形式）。旧out-param形式はclass内に存在しない
- API 36 device（emulator-5556 / AVD `issue142_api36`）:
  `createInputConsumer (Landroid/os/IBinder;Ljava/lang/String;ILandroid/view/InputChannel;)V`
  （out-param形式。launcherがcompileするformと一致）

確認済みの範囲は「旧form消失をNoSuchMethodErrorとして実測＋両APIのframework signature対照」までである。
hiddenapi enforcement flagの一次出力は未取得であり、新form（return形式）の呼出可否
（BLOCKED/非BLOCKED）は #545 の調査事項とする（本README初版の「hiddenapi BLOCKED」表記は
一次出力のない転記のため、確定事項から除外して修正。review round 1指摘）。

これはspec Owner decision 3が想定した「runtime検証で隠蔵API差分が見つかった場合」に該当する。
本検証ではコード変更を行わずfailure signatureを記録するのみ（検証に先行する宣言の禁止）。
対応方式（compatLib V37かvendored `systemUI/shared` のcompat抽象化か）も、signature追従と
hiddenapi要件の調査結果で決めるため #545 の調査事項とする。

### leg (a) — API 37 debug @ head ＋ debug用overlay: FAIL

- 構成: AVD `issue526_api37_pixel_9a`（API 37.0, google_apis, arm64）、head debugを
  /product/priv-app/LawnchairDebugへ配置、debug用overlay有効
- preflight（`a-preflight-privapp.txt`）: `cmd overlay lookup` =
  `app.lawnchair.debug/com.android.quickstep.RecentsActivity`、SystemUI
  `mRecentsComponentName` 同一、packageは `SYSTEM DEBUGGABLE`、`mIsEnabled=true mBound=true`
- 観測: bind→`NoSuchMethodError`→クラッシュループ（`a-bind-crash-signature.txt`）。
  "disabling recents" 無し（`a-launcher-logcat.txt`、gate/logicはdebug overlayを自身と認識）。
  overview/task切替は不成立（`a-overview-fail.png` = "Lawnchair (Debug) keeps stopping"）。
  G3（BAL block観測）は遷移自体が成立せず観測不能。`a-g3-logcat.txt` にBAL block 0件
  （クラッシュが先行するためblock signatureも出ない）
- 補足: pre-unlock bind時にApplication onCreateがCE storage初期化でクラッシュする二次signatureも記録

### leg (b) — API 37 release @ head ＋ release用overlay: FAIL

- 構成: 同AVD、head releaseを/product/priv-app/LawnchairRelease.apkへ配置（専用allowlist付与）、
  release用overlayへ差し替え
- preflight（`b-preflight.txt`）: lookup = `app.lawnchair/com.android.quickstep.RecentsActivity`、
  SystemUI参照同一、`SYSTEM`、`mIsEnabled=true mBound=true`
- 観測: leg (a)と同一の `NoSuchMethodError`（`b-bind-crash-signature.txt`）。
  "disabling recents" 無し（gateはrelease overlayを自身と認識しSDK gateも開く）。
  overview不成立（`b-overview-fail.png` = "Lawnchair keeps stopping"）。
  sheet非表示は確認できず（クラッシュループが安定表示を妨害）。BAL block 0件（`b-g3-logcat.txt`）

### leg (c) — API 37 pre-change release (maxSdk 36) ＋ release用overlay: PASS（SDK gate / sheet oracle限定）

- 構成: priv-app releaseを除去し、pre-change APKを `adb install -r -d`（/data、非system）。
  release用overlayは維持
- preflight（`c-preflight.txt`）: lookup = `app.lawnchair/com.android.quickstep.RecentsActivity`、
  SystemUI `mRecentsComponentName` 同一（build packageName `app.lawnchair` と一致）
- 観測（**oracleはapplication側SDK gate/UIに限定される**。非systemのため
  `LauncherProxyService` の `MATCH_SYSTEM_ONLY` 解決が失敗し、SystemUIはbindしない）:
  - `checkRecentsComponent()`=true（overlay一致）のもと `compatible=false` →
    `quickstep_incompatible` sheet表示（`c-incompatible-sheet.png`:
    "Incompatible system integration"）→ spec Scenario 2どおりのgate oracle
  - "disabling recents" 無し（`c-logcat.txt` grep 0件。`checkRecentsComponent()`=trueのため
    診断logは出ない — specの明記どおり、(c)のoracleはsheet）
  - overview不成立: gesture/APP_SWITCH後もfocusはlauncher home（`c-overview-fail.png`、
    `c-result.txt`）。ただしこれは `mIsEnabled=false`（非system app）でも必ず起きるため、
    **maxSdk差の効果対照根拠には使わない**
- 逸脱と制約: specはpriv-app前提の記述だが、sheet oracleを安定観測するため非system（adb install）で
  実施した。priv-app構成でのmaxSdk 36はFinding 2のgate非依存crashが先行するため、
  **provider-levelのclean効果対照（priv-app × maxSdk 36 vs 37）は本検証では採取できない**
  （制約として記録。#545の修復検証で解消される）。priv-app相当の観測はleg (b)が担う
  （b: gate開→bind crash、c: gate閉→sheet）。

### leg (d) — API 37 stock構成 ＋ head release: PASS

- 構成: overlay削除＋priv-app除去→reboot→preflight→head releaseを `adb install -r`→HOME=app.lawnchair
- preflight: `cmd overlay lookup` =
  `com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity`（本imageのstock値。
  spec記載の `com.android.launcher3/...` はAVD image依存で、google_apis Pixel imageでは
  NexusLauncherがstock provider。overlay除去によりstock値へ復帰したことの証跡として有効）
- 観測:
  - launcher起動で "disabling recents" 診断logが出る（`d-logcat.txt`:
    `config_recentsComponentName (ComponentInfo{com.google.android.apps.nexuslauncher/...}) is not Lawnchair, disabling recents`）
  - `quickstep_incompatible` sheet 無し（`d-home-no-sheet.png`）
  - recents gestureではLawnchair overviewは開かず、APP_SWITCHではsystem側overviewが開く
    （`d-overview-system.png`、focus=`com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity`）
  - 通常構成での無影響（maxSdk 37に上げてもgateは閉じたまま）を確認

### leg (e) — API 36 debug @ head ＋ debug用overlay（priv-app）: PASS

- 構成: AVD `issue142_api36`（API 36, google_apis, arm64）を `-writable-system` で起動→
  disable-verity→reboot→remount→debug用overlay＋head debug priv-app＋allowlistをpush→reboot
- preflight（`e-preflight.txt`）: lookup = debug component、SystemUI参照同一、
  **`isConnected=true`**、backoff=0 — API 36ではprovider bind＋connectionが成立
- 観測:
  - "disabling recents" 無し
  - APP_SWITCH→`TouchInteractionService.onOverviewToggle`→`OverviewCommandHelper.addCommand`→
    Lawnchair overview表示（`e-overview-api36.png`、launcher OverviewStateのtask card）
  - task card tap→Settingsへ切替成功（`e-task-switch-api36.png`、focus=`com.android.settings/.Settings`）
- 注記: このAVDには旧head `f102d72` のdata copyが残っており、`adb install -r` でhead `96746bc`
  へ更新してから実行（`e-result.txt`）。provider切替boot時にstock nexuslauncherが1回クラッシュ
  （Application Error dialog）したが、本Issueのoracle範囲外のため観測として記録するのみ

### leg (d)-API36 — API 36 stock構成: PASS

- 構成: overlay削除＋priv-app除去＋data uninstall→reboot→preflight（stock値 nexuslauncher/...）→
  head debugを `adb install -r`→HOME設定
- 観測: "disabling recents" 有り（`d-api36-logcat.txt`）、sheet無し（`d-api36-home.png`）

## 3. G3（recents遷移PI creator mode）の観測結果

- (a)/(b)とも **BAL block無し**。ただしprovider構成API 37では遷移そのものが
  `IWindowManager.createInputConsumer` のNoSuchMethodErrorで成立しないため、
  「遷移が成功した上でblock無し」の主証跡にはならない
- 補助証跡としてAPI 36（leg (e)）では同一PI経路（SystemUiProxy経由のshell→launcher遷移）で
  overview起動・task切替が成功し、BAL blockは出ていない（`e-logcat.txt`）
- **G3判断（creator mode）は本検証では確定しない**。本Issueで確定するのは
  「**コード変更なし・legacy `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` の暫定継続**」までである
  （現時点でgranular移行を要求するfailure evidenceが無いことのみ確認。API 36の成功はtarget 37の
  BAL条件下での最終判断を代替しない）。**API 37での最終判断（確定観測と保持/移行の決定）は
  provider修復後の #545 へ引き継ぐ**（#527 spec G3節の委譲条件は API 37での実測を要求するため
  本Issueでは完結しない）

## 4. 使用コマンド列の要点と逸脱

- overlay: `adb root` → `adb remount` → `rm/push /product/overlay/QuickstepConfigOverlay.apk` →
  `chmod 644` → `restorecon` → `adb reboot` → `getprop sys.boot_completed` 待ち → `adb root`
- release用overlay作成: `aapt2 compile --dir res -o res.zip` →
  `aapt2 link -I platforms/android-37.0/android.jar --manifest AndroidManifest.xml -R res.zip
  --auto-add-overlay` → `zipalign -f 4` → `apksigner sign --ks ~/.android/debug.keystore`（pass: android）
- 逸脱（いずれもREADME記載済み）:
  1. 指示の「adb install -r で入れ替え」のみではprovider pathが機能しないため（Finding 1）、
     provider構成legではlauncher APKを `/product/priv-app/` へ配置しprivapp-permissions
     allowlist XMLを追加した
  2. (c)のみadb-installed（非system）で実施した（sheet oracleの安定観測のため。priv-app相当は(b)）
  3. swipe gestureでのoverview発火が `input swipe`（hold無し）ではapp drawer誘発となるため、
     overview発火は `KEYCODE_APP_SWITCH` を主に使用した（provider bind済みlegでは
     TouchInteractionService.onOverviewToggleがlogcatで確認できる）
  4. stock overlay値はspec記載の `com.android.launcher3/...` ではなく
     `com.google.android.apps.nexuslauncher/...`（google_apis imageのstock provider）
  5. API 37 emulatorが検証中に一旦process死亡したため `-writable-system -no-snapshot` で再起動した

## 5. 未確認事項

- API 37 provider構成でのoverview成立・task切替（leg (a)/(b)の本oracle）— hidden API breakのため未達
- (b)での `quickstep_incompatible` sheet非表示の視覚確認 — クラッシュループのため未達（logでgate開は確認）
- API 37でのG3機械観測（BAL block有無の確定的判定）— 遷移未成立のため未達

## 6. 証跡file一覧

```
apk/pre-change-release-maxSdk36.apk   e8aced7dbe release (maxSdk 36)
apk/head-debug.apk                    head debug (maxSdk override 0..100000)
apk/head-release.apk                  head release (maxSdk 37)
a-preflight.txt                       leg (a) 初回（adb install状態）のpreflight
a-nosystem-bind-evidence.txt          Finding 1: 非system launcherはbindされない証跡
a-preflight-privapp.txt               leg (a) priv-app構成のpreflight
a-launcher-logcat.txt                 leg (a) "disabling recents" 無し証跡
a-bind-crash-signature.txt            leg (a) NoSuchMethodErrorクラッシュsignature
a-overview-fail.png                   "Lawnchair (Debug) keeps stopping"
a-overview-attempt.png                HOME role喪失時のresolver（参考）
a-probe-appdrawer-not-overview.png    input swipeがhold無しではapp drawerを開く例（参考）
a-g3-logcat.txt                       leg (a) gesture前後logcat（BAL block 0件）
b-preflight.txt                       leg (b) preflight（release component一致）
b-bind-crash-signature.txt            leg (b) 同一NoSuchMethodError signature
b-overview-fail.png                   "Lawnchair keeps stopping"
b-g3-logcat.txt                       leg (b) gesture前後logcat（BAL block 0件）
c-preflight.txt                       leg (c) preflight（非system構成の注記付き）
c-incompatible-sheet.png              quickstep_incompatible sheet表示
c-overview-fail.png                   overview不成立（gesture/APP_SWITCH後homeのまま）
c-logcat.txt                          leg (c) logcat（"disabling recents" 0件）
c-result.txt                          leg (c) 結論まとめ
d-home-no-sheet.png                   leg (d) home（sheet無し）
d-overview-system.png                 leg (d) system側overview（nexuslauncher RecentsActivity）
d-logcat.txt                          leg (d) "disabling recents" 診断log
e-preflight.txt                       leg (e) preflight（isConnected=true）
e-overview-api36.png                  leg (e) Lawnchair overview
e-task-switch-api36.png               leg (e) task切替後のSettings
e-logcat.txt                          leg (e) logcat
e-result.txt                          leg (e) 結論まとめ
d-api36-home.png                      leg (d)-API36 home（sheet無し）
d-api36-logcat.txt                    leg (d)-API36 "disabling recents" log
hidden-api-createInputConsumer-dexdump.txt  Finding 2の一次出力（API 37/36 framework.jarのdexdump対照。取得日2026-10-07）
```

## 7. 検証後のowner decision反映（2026-10-07）

本検証の結果を受け、owner decisionを次のとおり更新した（正本: ADR-0018 revision 8 Decision 7、Issue #524、#545）:

1. **`quickstepMaxSdk` 36→37は保留**（36維持）。Finding 2のとおりAPI 37のprovider pathは定数と無関係に破壊されており、引き上げは「検証に先行する宣言」となるため。spec Scenario 1（provider構成済みAPI 37でoverview成立）は成立しない。
2. **provider修復は#545へ分離**（framework-17.jar入手・新form対応方式・hiddenapi exemption要件の調査を含む。vendored `systemUI/shared` のcompat抽象化を伴う可能性が高く、risk tier H相当のspecを要する）。
3. **G3判断は「コード変更なし・legacy `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` の暫定継続」で確定**（API 37での最終判断は#545へ引き継ぐ）。本検証で確認できたのは「現時点でgranular移行を要求するfailure evidenceが無い」ことのみで、根拠としてAPI 36 provider構成（leg (e)）で同一PI経路の遷移が成功しBAL block 0件（`e-logcat.txt`）。API 37での確定観測は#545に含めた。
4. 本branchでは **build.gradle等のsource変更を行わない**（本README・ADR-0018 revision 8のみを記録する）。

検証APK 3本はリポジトリへcommitしない（生成物のため。sha256は§1のとおり。実物は検証sessionの `/tmp/524-evidence-apk/` に保存）。
