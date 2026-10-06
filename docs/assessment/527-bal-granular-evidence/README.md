# Issue #527 — BAL granular opt-in移行のemulator検証証跡

実施日: 2026-10-06。実施head: `60d65ed789ae91b20d6263284155ab0707b22683`（PR #541）。
APK: `assembleLawnWithQuickstepGithubDebug`（app.lawnchair.debug）。
観測: logcat full capture（`Background activity launch blocked` filter）＋
debug build限定StrictMode `detectBlockedBackgroundActivityLaunch`（penaltyLog）。
保守者実機Pixel 9a / API 37はEpic #516 Phase 3の実機matrix（owner手順）へ引き継ぐ
（spec Verificationどおり）。

追記（2026-10-07、review round 1対応）: StrictMode policyを既存VM policy継承
（`VmPolicy.Builder(StrictMode.getVmPolicy())`）へ修正（review指摘 medium）。
修正head `4e92d4d2c1` 以降のAPKでAPI 37 emulatorにて既知のrestart-PI blockを再現し、
`BackgroundActivityLaunchViolation` が引き続き記録されることを確認
（`api37-strictmode-policy-inherit-verify.txt`）。

## 環境

| AVD | API | 確認内容 |
|---|---|---|
| `issue526_api37_pixel_9a`（emulator-5554） | 37 | 全scenario |
| `issue142_api36`（emulator-5556） | 36 | icon tap・QSB・widget bind/configure/result・widget tap |
| `api35-test`（emulator-5558） | 35（legacy分岐） | icon tap・QSB tap |

## 結果サマリ

**BAL block検出: API37で1件のみ（自己再起動PI。後述）。API36/35は0件。**
当該1件を除き、実測した起動はすべて成功し`ActivityTaskManager: Background activity launch blocked`は出ていない。

### API 37（api37-*.png）

| # | 証跡 | scenario | 結果 |
|---|---|---|---|
| 01 | api37-01-home-default-set.png | setup | default HOME設定、LawnchairLauncher resumed |
| 02 | api37-02-appicon-tap-chrome.png | S1 app icon tap | Chrome FirstRunActivity起動 ✓ |
| 03 | api37-03-longpress-menu.png | S1 |長押しmenu（shortcut候補確認） |
| 04 | api37-04-deepshortcut-newtab.png | S1 deep shortcut | Chrome「New tab」shortcut起動 ✓（LauncherApps.startShortcut + options） |
| 05 | api37-05-qsb-search-tap.png | S1 QSB search | GoogleAppGlobalSearchImplicitGatewayInternal起動 ✓（Google.kt startIntentSender） |
| 06 | api37-06-qsb-voice-tap.png | S1 QSB voice | 同一PI経路（音声activityは即close。searchで同一経路を実証済み） |
| 07 | api37-07-widget-drop-attempt.png | S2 | pickerからのdrag手順 |
| 08 | api37-08-widget-bind-consent.png | S2 bind | `AllowBindAppWidgetActivity`（ACTION_APPWIDGET_BIND同意）→ Create ✓ |
| 09 | api37-09-widget-placed-after-config.png | S2 configure/result | `DigitalCitiesAppWidgetConfigActivity`起動→style選択→resultでworkspace配置 ✓ |
| 10 | api37-10-widget-tap.png | S1 widget tap | Clock app起動 ✓（RemoteViews PI / QuickstepInteractionHandler） |
| 11 | api37-11-widget-reconfigure.png | S2 reconfigure | 「Change widget settings」→ configure activity再起動 ✓ |
| 12 | api37-12-widget-reconfigured-result.png | S2 result | 再設定result復帰 ✓ |
| 13 | api37-13-no-default-home-icon-tap.png | S5 HOME無し | default HOME解除状態でもicon tap起動 ✓（HOME例外非依存） |
| 14 | api37-14-restart-launcher.png | 再起動 | app再起動成功（新pid・HOME復帰）。**ただし再起動PI自体はblock（下記）** |
| 15 | api37-15-notification-tap-view.png | S3 通知tap | 背景状態からbugreport通知tap→VIEW activity起動（system通知例外） ✓ |
| 16 | api37-16-saf-createdocument-picker.png | S3 SAF | CreateDocument picker（documentsui）起動 ✓ |
| 17 | api37-17-saf-return-backup-created.png | S3 SAF復帰 | SAVE→PreferenceActivityへ結果復帰 ✓ |
| 18 | api37-18-saf-cancel-zero-change.png | S4 拒否 | OpenDocumentをcancel→変更なしでdashboard復帰（zero-write） ✓ |
| 19 | api37-19-final-home-state.png | 最終状態 | |

通知action（背景・Clock前面の状態で実施）:
- 「Upload crash log」action tap → receiverは**activityを起動せず** `UploaderService`（`:bugReport` process）開始 ✓（top activityはClockのまま）
- 通知body tap → Chrome（VIEW先）起動 ✓（system通知BAL例外。blockなし）
- bugreport通知のPOSTには `POST_NOTIFICATIONS` のgrantが必要だった（API 13+ runtime permission。検証時に付与）

### 自己再起動PIのblock（spec G5で予見された条件の実測）

`api37-restart-pi-block-signature.txt` にlog全文。要点:

- `ActivityTaskManager: Background activity launch blocked!` —
  `callingPackage: app.lawnchair.debug; callingPackageTargetSdk: 37`、
  `intent: HOME cmp=app.lawnchair.debug/app.lawnchair.LawnchairLauncher`、
  `isPendingIntent: true`、`realCallingPackage: android.uid.system:1000`（AlarmManager）、
  `balAllowedByPiSender: BSP.NONE; resultIfPiSenderAllowsBal: BAL_BLOCK`、
  `resultIfPiCreatorAllowsBal: BAL_ALLOW_ALLOWLISTED_COMPONENT`。
- debug StrictMode aidも同一違反を記録（`BackgroundActivityLaunchViolation`。
  「app.lawnchair.debug could opt in to grant BAL privileges when creating」）。
- **ユーザー影響なし**: 再起動自体はHOME消失によるsystem復帰で成功（機能は動作）。
  PIの二重保証のみ失われている。
- round 1 reviewの「AlarmManager（system sender）は一般BAL例外に当たる」という想定は
  API 37実機挙動としては成立しなかった（sender側opt-inなしではblockされる）。
- 対応はaccepted spec G5の契約どおり **本issueでは新規grantを追加せず**、本signatureを
  根拠に最小mode選択（creator側opt-in。`BAL_ALLOW_ALLOWLISTED_COMPONENT` が
  許可経路と示唆）を後続判断へ渡す。

### API 36（api36-*.png）

| # | 証跡 | 結果 |
|---|---|---|
| 01 | api36-01-icon-tap.png | Chrome icon tap起動 ✓ |
| 02 | api36-02-qsb-tap.png | QSB search tap起動 ✓ |
| 03 | api36-03-widget-configure-opened.png | bind同意→configure activity起動 ✓ |
| 04 | api36-04-widget-placed.png | configure resultで配置 ✓ |
| 05 | api36-05-widget-tap.png | widget tap→Clock起動 ✓ |

BAL block: 0件。

### API 35（api35-*.png。legacy `ALLOWED` 分岐 — コード上は変更不変）

| # | 証跡 | 結果 |
|---|---|---|
| 01 | api35-01-icon-tap-legacy.png | Chrome icon tap起動 ✓ |
| 02 | api35-02-qsb-tap.png | QSB tapは **E2E起動未確認**（当該imageにGoogle search gatewayが不在でlauncherに留まった。legacy分岐は`ATLEAST_U`時の`ALLOWED`設定のみでコード上変更前と同一。BAL block 0件） |

BAL block: 0件。

## 未実施・制約

- smartspace card tap（BcSmartSpaceUtil）: emulatorにsmartspace providerの実dataがなく
  tap actionが構成されないため未実測。同一のmigration pattern（helper経由の
  `ALLOW_IF_VISIBLE`）はQSB（Google.kt）で実証済み。実機matrixで確認対象。
- share chooser（ExchangeTransports）: organizer exchange flow到達に長い操作経路が必要で
  今回未実測。foreground activityの`startActivity`（PI不使用）でありBAL option対象外
  （spec G5どおり）。SAF復帰で同一 ActivityResult classを実証済み。
- Quickstep provider構成（recents/split/taskbar）: #524環境（rooted provider構成）が必要。
  本issueではG3/G4/G6のとおりコード不変。
