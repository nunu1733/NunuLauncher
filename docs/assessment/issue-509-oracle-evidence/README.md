# Issue #509 Phase A Revision 5 — oracle実験のdurable証跡

assessment `docs/assessment/issue-509-category-destination-feasibility.md` §3.6の実験（Revision 5、2026-10-14実施）について、`/tmp`の一時artifactからrepository内へ必要最小限を移したdurable snapshotである。

- 対象commit: `391ad7eaa0f84f43af67f7101071f91325c998d9`（Lawnchair 16-dev baseline cutover後main）。launcher APK: `Lawnchair.16.Dev.(391ad7e).github.debug.apk`（このcommitから`assembleLawnWithQuickstepGithubDebug`）
- 環境: emulator `issue526_api37_pixel_9a`（API 37、google_apis、userdebug、serial `emulator-5554`）。userdebugのためroot可
- 実験本体はrepositoryを変更しない。本ディレクトリは観測記録であり、production codeへの影響はない
- 実施session: ZCode sess `bdac19a3-9350-4108-aa6d-191465d5ad94`（chatgpt-code-review前提の作業session。solo保守のため実施とreviewを別sessionに分離している）

## ファイル一覧

| ファイル | 内容 |
|---|---|
| `ProbeActivity.java` | E4a版probe（installer=vending偽装。`e4.log`の`SecurityException`を生成したsource） |
| `ProbeActivityV3.java` | E4b3版probe（installer=自己package＋commit-status receiver。`ProbeActivity.java`をE4a→E4b3間でin-place編集したためv3のテキストは別保存されておらず、**実機install済みAPKのdisassemblyから復元したsource**である。復元の正確性は下記`probe-v3-installed-dexdump.txt`とlog文字列の一致で検証） |
| `probe-v3-installed.apk` / `probe-v3-installed-dexdump.txt` | **E4b3実行時にemulatorへinstallされていたprobeのAPK**（`pm path`で取得、MD5 `baa9fa89a3dfafed92c6e6ae6989dd94`）とその`dexdump`逆生成物。bytecode上で`setInstallerPackageName(getPackageName())`、`registerReceiver(COMMIT_RESULT)`、`FLAG_UPDATE_CURRENT|FLAG_MUTABLE`（`0xa0000000`）のcommit-status receiverを確認できる |
| `e1prime.log` | E1'（対照実験。shell session、installer=vending、reason=4、badging無し）のraw log |
| `e4.log` | E4a（probe、installer=vending偽装）のraw log。`createSessionInternal`の`SecurityException`を含む |
| `e4b3-monitor.log` / `e4b3-full.log` | E4b3（probe v3、installer=自己、badging付きsession）の監視logと全量log |
| `final-state.txt` | 実験終了時のpackage/sessions `dumpsys package` snapshot（badging成立のsession metadataを含む） |
| `e4-home.png` | E4b3完了後のlauncher workspace（target iconが通常追加された画面） |

## 再現手順

1. 対象commitからdebug APKをbuild: `./gradlew assembleLawnWithQuickstepGithubDebug`（JDK 21、Android SDK Platform 37.2。`docs/engineering/building.md`参照）
2. probe appのbuild（一時手順。aapt2 linkに`--min-sdk-version 29 --target-sdk-version 34`、`resources.arsc`はuncompressed+zipalign、debug keystore署名。`classes.dex`は`javac --release 11`＋d8）
   - E4aは`ProbeActivity.java`、E4b3は`ProbeActivityV3.java`（復元source。実機install済みAPK `probe-v3-installed.apk`が正）をbuild
   - `adb install -r -g probe.apk`（API 37 emulator）
3. launcherをinstallし既定HOMEへ: `adb install -r -d <launcher.apk>` → `cmd package set-home-activity "app.lawnchair.debug/app.lawnchair.LawnchairLauncher"` → `am start`
4. benchmark target APK（`tests/benchmark-install-targets`のtarget01 flavor）をprobeのfiles dirへpush（rootで`/data/data/app.lawnchair.probe.sessionbadge/files/target.apk`）
5. E1': `pm install-create -i com.android.vending --install-reason 4` → `pm install-write` → write中のlogcat（promise log不在を確認）→ `pm install-commit` → 3秒後logcat
6. E4a/E4b3: targetをuninstall → `am start -n app.lawnchair.probe.sessionbadge/.ProbeActivity` → logcat監視。E4b3はcommit後のsystem dialog（badging由来のlabel表示）を手動tapしてinstall完了まで進める
7. session metadata確認: `dumpsys package` のsessions節（`final-state.txt` 827-838行参照）

## 主要観測（assessment §3.6の引用根拠）

### E1'（対照実験。`e1prime.log`）

- write中のlauncher promise log: **不在**（badging欠損で`verifySessionInfo`が落ちる。期待どおり）
- commit 3秒後: `SessionCommitReceiver: Adding package name to install queue. Package name: app.lawnchair.benchmark.target01, has app icon: false, has app label: false` → `ItemInstallQueue`（install後の通常追加）
- Revision 2のE1結論を新baselineで再現

### E4a（installer偽装拒否。`e4.log` 8-34行）

```text
10-14 11:33:10.384 14289 14289 E SessionBadgeProbe: java.lang.SecurityException: Package com.android.vending does not belong to 10238
10-14 11:33:10.384 14289 14289 E SessionBadgeProbe: 	at android.app.AppOpsManager.checkPackage(AppOpsManager.java:10505)
10-14 11:33:10.384 14289 14289 E SessionBadgeProbe: 	at com.android.server.pm.PackageInstallerService.createSessionInternal(PackageInstallerService.java:830)
```

非特権appのinstaller名偽装は**session作成時点**でplatform uid checkが拒否する（commit制限以前）。

### E4b3（badging付きsession。`e4b3-monitor.log`/`e4b3-full.log`/`final-state.txt`）

- session作成: `SessionBadgeProbe: session created id=1228612374 with badging (icon+label), installer=self, reason=4 (no spoof)`
- system側でbadging永続化: `PackageInstallerSession: Writing changed icon /data/system/install_sessions/app_icon.1228612374.png`
- session metadata（`final-state.txt:827-828`。同構築のsession 1228612374/1642314046等）: `installReason=4 appPackageName=app.lawnchair.benchmark.target01 appIcon=true appLabel=Benchmark Target 01 installerPackageName=app.lawnchair.probe.sessionbadge`、**create時点でtarget未install**（monitor logの`pm path = []`が継続）
- commit: `STATUS_PENDING_USER_ACTION`（-1）→ system dialogのbadging label「Benchmark Target 01」を手動tap → `cb onFinished ... success=true`
- install完了（`firstInstallTime=2026-10-14 11:49:13`）後: `SessionCommitReceiver: ... has app icon: true, has app label: true` → `ItemInstallQueue`（install後の通常追加。`e4-home.png`）
- **核心の負結果**: session作成時（badging＋reason=4＋target未install成立）に、launcher側の`InstallSessionTracker`/`InstallSessionHelper`/`SessionCommitReceiver`のlogは一切なし → promise capture不発

#### session ID時系列の限界（監査上の注記）

`e4b3-full.log`では作成sessionが`1228612374`である一方、成功完了のcallbackは`cb onFinished 1120207295 success=true`として記録され、終了時`dumpsys`でも`1228612374`が`Active Session`として残存し、完了session`1120207295`のmetadata（同badging構成）のみが残る。**現証跡の範囲では「作成した`1228612374`そのものが同意flowで完遂した」ことを一意に結びつけられない**（`1120207295`の作成時logは本logcat収録範囲に含まれず、E4b系の試行間でsessionがどのように対応したかは確定しない。なお`1228612374`は同意未了のActive Sessionのまま残り、同一packageのsessionは複数並行し得る）。したがってassessment §3.6の「install完了後の通常追加」の記述は、**「badging付きsession（同構成・同条件下）のcommit同意flowを完遂したところ、install完了後に`SessionCommitReceiver`が`has app icon: true`でcaptureした」**というレベルの観測であり、「作成session IDと完了session IDの1:1対応の証明」ではない。この限界は判定（promise capture不発・capture時分類不可の構造）に影響しない。

### gate帰属の証拠強度（assessment §3.6の限定表現に対応）

launcher側log不在は、(a) `isTrustedPackage` gateで排除された場合と、(b) launcher callback入口自体へ届かなかった場合の両方と整合する。上記の実験だけでは両者を直接区別できない（gate通過後の詳細logはverified sessionがnon-nullの後にのみ出るため）。したがってassessment §1.5/§3.6では`isTrustedPackage` gateへの帰属を「dumpsysで満たされた他条件との突合から**整合/特定される**（直接観測ではない）」と記述する。`verify()`→`isTrustedPackage`の分岐構造自体は`src/com/android/launcher3/pm/InstallSessionHelper.java:163-171`（`ApplicationInfoWrapper.kt:112`の`isSystem()=hasFlag(FLAG_SYSTEM)`）のコード根拠による。

## 実験範囲の限界

- 本実験の「oracle構成不能」の主張は、**今回使用した`google_apis` image（`issue526_api37_pixel_9a`）＋shell／非特権probeの組み合わせ**に限定される。Play Store付きsystem image（`google_apis_playstore`等）ではvendingが実際に動作するため、実機oracle（assessment §8.1）に近い構成が可能か否かは本実験の範囲外である
- 「promise capture時点でS2/S5が読めない」の直接実証は未達（promise経路に未到達。assessment §8.1の実機oracleが残る）
