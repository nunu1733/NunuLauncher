---
status: proposed
---

# targetSdk 37移行時のAndroid 16/17 behavior changes（Issue #521）

> Status: Proposed（研究成果。実装完了・runtime互換性の証明ではない）
> 確認日: 2026-10-04
> 対応: [#521](https://github.com/nunu1733/NunuLauncher/issues/521) / Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 1
> 前提: [ADR-0018](../adr/0018-lawnchair-16-rebase.md) Decision 1/6/8、[Phase 0 §6.3](./issue-516-16-rebase-phase0-research.md#6-主要な発見)
> Risk tier: L（assessmentのみ。source、manifest、SDK値、test、CIを変更しない）。researchなのでspec/plan不要。実装は後続Issueのaccepted specで扱う。

## 1. 結論と境界

targetSdk **35→37** によりAPI36/37のtarget条件を跨ぐ。upstream取込みだけでfork全体の対応が完了するわけではない。特に別Activityの編集画面はsystem bar insetを消費せず、回転によるActivity再作成で未確定sessionを再初期化する。固有UIの対応は **[#526](https://github.com/nunu1733/NunuLauncher/issues/526)** が所有する。upstreamにも残るBAL旧modeは **[#527](https://github.com/nunu1733/NunuLauncher/issues/527)**、任意検索候補URLのLANアクセスとHTTPSのECH/CT確認・必要修正は **[#528](https://github.com/nunu1733/NunuLauncher/issues/528)** が所有する。

3件とも **Phase 2のrebase完了後、新baseline上で実施**する実装Issueであり、Phase 3の完了条件へ引き渡す。#521は調査と起票を終了条件とするため、これらの実装完了を待たず研究成果の受入・mergeでcloseできる。LAN permissionの追加/非対応化、回転時の保持/破棄、BAL例外の個別modeは後続specの判断であり、本書で先取りしない。

分類:

- **(a)**: 固定upstream候補の対応を取り込める項目。コードの存在による静的判定であり、Phase 3でmerged treeと実挙動を再確認する。
- **(b)**: fork固有面への対応、またはupstreamでも未解決でfork統合が所有する残存面。後者は「共通残存」と明記する（Nunu固有と誤記しない）。検証して必要な修正を行う実装Issueへ渡す。
- **(c)**: 関連API不使用、適用条件を満たさない、または既存対応で追加変更が不要と判断した項目。依存library/merged manifest/runtimeの確認限界は§6に残す。

API37 Quickstep/recentsは[#520](https://github.com/nunu1733/NunuLauncher/issues/520)/[#524](https://github.com/nunu1733/NunuLauncher/issues/524)、schema/migration/backupデータ互換は[#522](https://github.com/nunu1733/NunuLauncher/issues/522)の所有であり、ここで結論を置き換えない。

## 2. 調査identityと方法

| 対象 | 固定identity / 方法 |
|---|---|
| fork | `a65a1d169c743ef28e7f07c4717b5efc08c9e1b1`（#523 merge後のmain） |
| 現baseline | `505dbc40e6154c05158b5d0271c45f6a885a411b`（15 beta 3） |
| 採用upstream候補 | `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（ADR-0018固定16-dev） |
| SDK | fork `build.gradle:34-37`: compile36.1 / target35 / min26。候補 `build.gradle:25-34`: compile37 minor2 / target37 / min26、buildTools37.0.0 |
| 正本 | #521本文と全コメント（取得2026-10-04T03:29Z、OPEN、コメント0、type: research / phase: mvp）。#516 Phase 1/3、#442 §6.3、ADR-0018、CONTEXT、DESIGN、workflow、quality strategyを確認 |
| 公式資料 | Android Developersのtarget16/17、all-app16/17、17 release notesと関連API文書をHTTPS取得して本文確認。**全URLの確認日は2026-10-04**。公開資料の当日snapshotに対する評価であり、release notesのbeta修正履歴を最終OSの実測と扱わない |
| コード照合 | `git show <SHA>:<path>` / `git grep -n -E '<API>' <SHA> -- <paths>` / `git diff <fork> <candidate> -- <path>`。Lunaはread-onlyでsurface棚卸し、Workerは公式条件と差分を照合 |

forkのorganizer/homeeditは候補upstreamに存在しない（`git ls-tree -r <SHA> -- lawnchair/src/app/lawnchair/organizer lawnchair/src/app/lawnchair/homeedit`: fork183/25 files、候補0/0）。共通PreferenceActivityの対応が別のHomeEditSurfaceActivityへ自動適用されるとは扱わない。

### コード証拠索引

以下の行番号は上記固定SHAのもの（`F`=fork、`U`=候補）。同pathの現行mainが前進しても調査identityを変えない。

| ID | 固定コードと観測 |
|---|---|
| C1 | F/U `lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt:37` / `:40`、`ui/theme/Theme.kt:81` / `:84`: `enableEdgeToEdge()`。F/U `lawnchair/AndroidManifest.xml:67` / `:72`: PreferenceActivityはback callback有効。root manifestは向きunspecified、resizeable、configuration changes処理 |
| C2 | F `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt:118-122`: super→setContent→reloadCapture。`:96-105`にcapture/session/selection等のActivity field、`:172-210`でcaptureとEMPTY sessionを再初期化。`EditSurfaceScreen.kt:109-113`: fillMaxSizeのColumnと固定padding、system bars/cutout inset処理なし。F manifest `:84-90`:独立・非exported Activity。Uにこのsurfaceなし |
| C3 | F `organizer/ui/exchange/ExchangeFlowUi.kt:3490-3496,3624-3625`: Compose BackHandler。F `backup/ui/CreateBackupScreen.kt:78`、`RestoreBackupScreen.kt:131`、`RestoreNovaBackupScreen.kt:100`: BackHandler。F/U `src/com/android/launcher3/BaseActivity.java:288` / `:365`: registerOnBackInvokedCallbackがonBackPressedへ明示委譲。U `Launcher.java:611,2108`: animation callback |
| C4 | F `lawnchair/src/app/lawnchair/backup/ui/CreateBackupScreen.kt:64,101-114`、`RestoreBackupScreen.kt:85,290-295`: 向き別UIとSAF。F `organizer/diagnostics/export/ExportUi.kt:84-109`、`ExportWriter.kt:60-70`: IO dispatcherからcontent URIへ書出し。F `organizer/integration/exchange/ExchangeTransports.kt:55-83`: ユーザー起点chooser/SAF、独自PendingIntentなし |
| C5 | U `src/com/android/launcher3/Utilities.java:741-745`: allowBGLaunchは旧`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`。U `util/StartActivityParams.java:103-107`: callback送信も同helper。F `bugreport/BugReportReceiver.kt:89-157`: immutable PendingIntentで通知action/URI grantを構成 |
| C6 | F/U `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/ContactsSearchProvider.kt:64-76` / `:64-73`: FはData projectionに`account_type/account_name`等を持つがUは削除済み。F `:96-108`にread、U `:86-99`は当該readなし。両側 `:50-52`はREAD_CONTACTS gate |
| C7 | F/U `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/CustomWebSearchProvider.kt:45,50-53,70-77`: 任意suggestion URLをOkHttpで実行、例外は空候補。UもLAN permission対応なし。F `bugreport/KatbinService.kt:19`、`UploaderUtils.kt:3-11`: public HTTPS upload。`gradle/libs.versions.toml`: OkHttp F5.3.2/U5.5.0、Espresso両側3.7.0 |
| C8 | F `src/com/android/launcher3/util/LooperIdleLock.java:25`: public MessageQueue.IdleHandlerを使用（private queue reflectionではない）。Gradle側Espresso3.7.0。AOSP BPのRobolectric runnerはあるが本PRはその更新/実行を行わない |
| C9 | F `lawnchair/src/app/lawnchair/HeadlessWidgetsManager.kt:74-75`、`LawnchairAppWidgetHostView.kt:42-44`、`src/com/android/launcher3/widget/LauncherAppWidgetHostView.java:136-151`: **AppWidgetHostView.updateAppWidget**（受信表示）。providerの**AppWidgetManager.updateAppWidget**送信とは別API。source内で大量bitmap/iconを生成してprovider送信する経路は確認されない |
| C10 | F `organizer/application/store/RecoveryDbHelper.kt:17-46`、`RecoveryStore.kt:132-135`、`backup/NovaBackupConverter.kt:450,491-499`: Android SQLite。F `NovaBackupConverter.kt:731`、`organizer/application/adapter/LauncherLayoutAdapter.kt:471`、`RowManifestCodec.kt:411-412`: serialized Intent parse。received Intent extraから取り出して直ちに起動する経路とは別 |
| C11 | F `lawnchair/src/app/lawnchair/bugreport/UploaderService.kt:64-89`: dataSync FGS。organizer/homeedit/backupはWorkManager/JobScheduler/FGSを使わない。organizer diagnosticsはlocal-only（[diagnostics contract](../engineering/organizer-diagnostics.md)） |
| C12 | F/Uのtracked production `.java/.kt` とmanifestをAPI tokenで検索（tests、plugin API定義、submodule内容は除外）。`scheduleAtFixedRate`, `BODY_SENSORS`, `BluetoothSocket`, `MediaStore.getVersion`, `setContentCaptureEnabled`, `System.load(`/`System.loadLibrary`, `MessageQueue.class`/`mMessages`, `AndroidKeyStore`/`KeyGenParameterSpec`, `NEURAL_PROCESSING_UNIT`, `READ_SMS`/`SMS_RECEIVED`, `MediaPlayer`/`requestAudioFocus`/volume操作にconsumerなし。反射はOverScrollerCompatのinstance field等に限定され、static finalを変更するproduction consumerを確認しない。検索不一致はlibrary内部の不使用を証明しない |

`organizer/...` の短縮pathは `lawnchair/src/app/lawnchair/organizer/...`、`backup/...` 等も同様。固定コードは [fork tree](https://github.com/nunu1733/NunuLauncher/tree/a65a1d169c743ef28e7f07c4717b5efc08c9e1b1) / [upstream tree](https://github.com/LawnchairLauncher/lawnchair/tree/43a21b43d7cc7850ab54e14b1a57dc9646685f35) で再取得できる。

## 3. targetSdk依存の一覧と分離

公式targetページのleaf behavior、all-appページに含まれるtarget条件、release notesの追加target条件を確認した。APIが追加されただけのBluetooth等は§4へ分ける。表中の(b)は静的に確認した欠落または要検証surfaceであり、runtime failureが再現済みという意味ではない。

| ID / OS・target条件 | behavior change / 公式根拠（確認2026-10-04） | 影響面・分類 | 対応所在と根拠 / Phase 3 |
|---|---|---|---|
| T01 / OS36+、target36+ | [edge-to-edge opt-out廃止](https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge)。OS35上はopt-out属性が引き続き有効 | 共通設定 **(a)** / 編集Activity **(b)** | C1は対応コードあり。C2にはinset消費なし。#526でbar/cutout/IMEと確認・キャンセルCTAを確認修正。opt-out属性はF/U resourceに見つからないが、これだけでinset対応済みとしない |
| T02 / OS36+、target36+ | [predictive back既定有効](https://developer.android.com/about/versions/16/behavior-changes-16#predictive-back)。framework onBackPressed/KEYCODE_BACKに依存不可 | launcher/設定 **(a)** / forkの中断・破棄・編集busy **(b)** | C1/C3のOnBackInvokedDispatcher/AndroidX BackHandlerは対応経路。callback内部でonBackPressedを呼ぶこととOSからの旧callback配送を区別。#526で既存zero-write/cancel/confirm契約を再検証。Quickstepは#520/#524 |
| T03 / OS36、target36+ | [elegantTextHeight無効化](https://developer.android.com/about/versions/16/behavior-changes-16#elegant-text-height) | organizer/edit/backup等のtext layout **(b)** | 明示的false指定のconsumerなしでもArabic/Thai等の行高・clipの影響は否定できない。#526で対象言語・font scalingを確認、実測不備を修正。日本語のみの確認で完了にしない |
| T04 / OS36、target36+ | [scheduleAtFixedRateのmissed実行を最大1回へ](https://developer.android.com/about/versions/16/behavior-changes-16#schedule-at-fixed-rate) | planner/queue/diagnostics **(c)** | C12で該当schedulerなし。coroutine/Handlerと同じAPIとは扱わない |
| T05 / OS36+、target36+、sw>=600dp | [向き・resizability・aspect ratio制限を無視](https://developer.android.com/about/versions/16/behavior-changes-16#ignore-orientation) | 共通launcher/DeviceProfile＋fork UI **(b)** | rootはresizeableだがC2のsession再初期化とC4の向き別UIは別問題。#526。Pixel 9aだけではsw600dp面を検証できない |
| T06 / OS36、target36+ | [health granular permissions](https://developer.android.com/about/versions/16/behavior-changes-16#health-fitness-permissions) | organizer usage hints **(c)** | C12。UsageStatsはBODY_SENSORS/Health Connectではなく、permissionを置換する必要なし |
| T07 / OS36、target36+ | [MediaStore.getVersionがapp固有](https://developer.android.com/about/versions/16/behavior-changes-16#mediastore-lockdown) | backup/diagnostics **(c)** | C4はSAF、C12にgetVersion consumerなし。SAFのURI権限やbackup互換をこの変更で説明しない |
| T08 / OS36+、target36+で選択media権限UI | [app所有写真のpreselection/取消](https://developer.android.com/about/versions/16/behavior-changes-16#owned-photos) | backup/export **(c)** | C4の文書pickerはmedia permission requestではない。merged manifestの画像機能はPhase3で確認するが本researchの対象固有面にmedia所有権依存は確認しない |
| T09 / OS37、target37+ | [RemoteViews bitmap+Iconの合算memory上限](https://developer.android.com/about/versions/17/behavior-changes-17#memory-limit-widget) | widget host/organizerのspan保持 **(c)** | C9。1.5×width×height×4超過で送信provider側がIllegalArgumentException（[updateAppWidget reference](https://developer.android.com/reference/android/appwidget/AppWidgetManager#updateAppWidget(int%5B%5D,%20android.widget.RemoteViews))）。host受信をprovider送信と誤認しない。外部providerのtargetと不良RemoteViewsはPhase3 widget smokeで確認 |
| T10 / OS37、target37+ | [lock-free MessageQueue](https://developer.android.com/about/versions/17/behavior-changes-17#lock-free-messagequeue) | model reload/loader/CI idle **(c)** | C8/C12。private mMessages reflectionなし、Espresso3.7.0は[公式最低版](https://developer.android.com/about/versions/17/changes/messagequeue#espresso-action)を満たす。forkの相関reloadをidle heuristicへ置換しない。merged dependency/Robolectric4.17+（使う場合）の確認とAPI37上のreload/idle観測は残す |
| T11 / OS37、target37+ | [static final変更禁止](https://developer.android.com/about/versions/17/behavior-changes-17#static-final-fields) | reflection/hidden-api/DI **(c)** | C12。reflective invocationやinstance field変更はstatic final writeではない。dependency/submodule内とinstrumentation fixturesは未確認として§6へ残す |
| T12 / OS37、target37+ | [CJKV IME/physical keyboardのaccessibility](https://developer.android.com/about/versions/17/behavior-changes-17#a11y-ime-pk) | rename/検索/import入力 **(c)**、統合確認 | 標準TextViewはplatformが対応。独自InputConnectionでcandidate dataを処理するfork consumerを確認しない。Compose dependencyの結果は入力・TalkBackのPhase3観測で確認する（既定対応だけでruntime合格としない） |
| T13 / OS37、target37+・library/server対応時 | [ECH既定有効/GREASE](https://developer.android.com/about/versions/17/behavior-changes-17#ech-by-default) | custom/public suggestions、Katbin **(b・共通残存)** / organizer local **(c)** | C7/C11。library更新だけでECH対応有無を断定不可。#528でmerged libraryとserverの通信を確認、必要修正 |
| T14 / OS37、target37+・LAN通信時 | [ACCESS_LOCAL_NETWORK runtime permission](https://developer.android.com/about/versions/17/behavior-changes-17#local-network-protection-permission) | 任意suggestion URL **(b・共通残存)** / organizer/backup **(c)** | C7はLAN URLを排除せずpermission guardもなし。#528で採否・拒否/取消をspec化。ブラウザへ渡すsearch URLとapp自身がfetchするsuggestion URLを分ける。SAF/clipboardにLAN権限を加えない |
| T15 / OS37、target37+、physical keyboard | [password文字の非表示既定](https://developer.android.com/about/versions/17/behavior-changes-17#hide-pwd-kbd) | forkのrename/import **(c)** | rename/importはpassword fieldではない。独自password visibility consumerなし。標準password widgetの既定を無効化する変更は不要 |
| T16 / OS37、target37+ | [標準SMS OTPの3時間遅延](https://developer.android.com/about/versions/17/behavior-changes-17#sms-otp-protection) | organizer/notification dots **(c)** | C12にSMS provider/receiver consumerなし。通知dotはSMS本文読取でなくNotificationListenerの別契約 |
| T17 / OS37・target17文書掲載 | [BAL/IntentSender hardening、granular mode推奨](https://developer.android.com/about/versions/17/behavior-changes-17#activity-security) | launcher result/widget config/bugreport actions **(b・共通残存)** | C5。Uも旧modeを使用。#527でsender/creator、HOME例外、visible/background、IntentSender.sendIntentを区別して対応。SDK34/35以来のopt-inは既存要件であり35→37の新要件と混ぜない。target37のin-task保護は明示opt-in（§4） |
| T18 / OS37、target37+ | [CT既定有効](https://developer.android.com/about/versions/17/behavior-changes-17#ct-default) | HTTPS **(b・共通残存)** / local diagnostics **(c)** | C7。#528でpublic/custom TLS endpointとエラーを確認。[network security config](https://developer.android.com/privacy-and-security/security-config#CertificateTransparencySummary)のlibrary適用・trust anchor例外を確認し、全域CT/TLS無効化は採用しない |
| T19 / OS37、target37+ | [System.loadのnative fileはread-only必須](https://developer.android.com/about/versions/17/behavior-changes-17#safer-dcl) | backup ZIP/SQLite/recovery **(c)** | C10/C12。backupをimportすることはnative code loadではなく、consumerなし。packaged/dependency native loadはartifact確認が必要（§6） |
| T20 / OS37、target37+ | [CP2 Dataのaccount列制限](https://developer.android.com/about/versions/17/behavior-changes-17#restrict-pii-fields-cp2-data-view) | contacts search **(a)** | C6: Uはaccount列projection/readを削除済み。rebaseでこの削除を保持。account情報を戻す場合はRawContacts等の新契約が必要で本researchの範囲外。Phase3でREAD_CONTACTS許可時に結果が出るか確認 |
| T21 / OS37、target37+・READ_CONTACTSなし | [CP2 Data strict SQL](https://developer.android.com/about/versions/17/behavior-changes-17#enforce-strict-sql-checks) | contacts search **(c)** | C6: permission未許可ならquery前に空結果へ戻る。取消raceはcatchで空結果。Launcher SQLite全般のstrict SQL化ではない |
| T22 / OS37、target37+ | [setContentCaptureEnabled(false)無効化](https://developer.android.com/about/versions/17/behavior-changes-17#deprecate-setcontentcaptureenabled) | diagnostics/import画面 **(c)** | C12にこのAPIによるcapture禁止の依存なし。新FLAG_SECURE採用は別の製品判断。local-only/redaction契約から自動的に全画面capture禁止を導出しない |
| T23 / OS37、target37+、background audio | [WIU FGSまたはalarm条件等の追加制約](https://developer.android.com/about/versions/17/behavior-changes-17#bg-audio) | organizer/backup/diagnostics **(c)** | C11/C12: playback/focus/volume consumerなし。外部widget/appの再生主体はlauncherではない |
| T24 / OS37、target37+、sw>=600dp | [向き/resize制限opt-out不可](https://developer.android.com/about/versions/17/behavior-changes-17#large-screen-ignore-constraints) | fork UI **(b)** | T05と同じ#526。PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITYに依存した解決はtarget37で不可。tablet/foldable/desktop windowをmatrixへ入れる |
| T25 / OS37、target37、Bluetooth RFCOMM | [read EOFが-1](https://developer.android.com/about/versions/17/behavior-changes-17#bluetooth-rfcomm-socket-change) | 全fork固有面 **(c)** | C12にBluetoothSocket consumerなし。InputStreamでのSAF読込とは別 |
| T26 / OS37、target37+、非system app | [Keystore上限50,000・ERROR_TOO_MANY_KEYS](https://developer.android.com/about/versions/17/behavior-changes-all#per-app-keystore-limits) | recovery/backup/signing **(c)** | C12: runtimeのAndroidKeyStore大量生成なし。APK署名keyと端末内runtime key生成は別。all-app文書に載るが50k/200k・numeric errorはtarget分岐なのでここにも列挙 |
| T27 / OS37、target17・直接NPUアクセス | [FEATURE_NEURAL_PROCESSING_UNIT宣言](https://developer.android.com/about/versions/17/release-notes#core_functionality_privacy_performance) | external agent exchange **(c)** | C12に直接NPU consumerなし。external exchangeはclipboard/SAFであり推論runtimeではない。未実装managed AI #206に要件を移す場合はそのspecで再調査 |

## 4. target引上げだけでは発火しない項目

targetページの掲載場所だけでmandatoryなtarget changeと扱わない。全URLの確認日は2026-10-04。

| 条件と公式根拠 | forkとの照合 / 分離 |
|---|---|
| Android16 [Safer Intents](https://developer.android.com/about/versions/16/behavior-changes-16#safer-intents)は受信側`intentMatchingFlags`明示opt-in | F/U manifestに同属性なし。target37への引上げだけで強制適用としない。opt-inは別spec。external app側のfilterに合うaction/componentはPhase3 app launchで確認 |
| Android16 [Bluetooth bond loss/encryption intents](https://developer.android.com/about/versions/16/behavior-changes-16#new-intents-to-handle-bond-loss)、[removeBond API](https://developer.android.com/about/versions/16/behavior-changes-16#bond-removal-api) | 新API/通知の採用面、C12にconsumerなし。Bluetooth featureを追加しない |
| Android16 [GPU syscall filtering](https://developer.android.com/about/versions/16/behavior-changes-16#gpu-syscall-filtering) | 公式はsupported OpenGL/Vulkanに影響なしと説明。raw Mali ioctl consumerなし。Pixel 9aでの通常renderをraw GPU開発ioctlと同一視しない |
| Android16 [JobScheduler quota](https://developer.android.com/about/versions/16/behavior-changes-all#job-quota-opt)、[ordered broadcast priority](https://developer.android.com/about/versions/16/behavior-changes-all#ordered-broadcast-priority)、[16KB compat](https://developer.android.com/about/versions/16/behavior-changes-all#16-kb-compatibility-mode) | OS36全app。target35の既存appも影響し得る。C11の同期UI/IOをJobSchedulerのjobと扱わない。packaged native/artifactとpackage installイベントはPhase3/Phase2 buildの確認面 |
| Android16 [intent redirect保護](https://developer.android.com/about/versions/16/behavior-changes-all#intent-redirect-attacks) | OS全app。C10のserialized Intent parseがあるだけでuntrusted nested Intent起動と断定しない。SAF/exchange、widget config/result、外部app復帰をPhase3で確認。全面opt-outは採用しない |
| Android17 [app memory limits](https://developer.android.com/about/versions/17/behavior-changes-all#app-memory-limits)、[cross-profile loopback禁止](https://developer.android.com/about/versions/17/behavior-changes-all#block-cross-profile-loopback)、[IME回転](https://developer.android.com/about/versions/17/behavior-changes-all#ime-rotate) | OS全app。T09のRemoteViews parcel上限とprocess memory制限は別。large home/preview/recovery、work profile/IMEはPhase3観測へ渡す。loopback依存は確認しない |
| Android17 [implicit URI grant](https://developer.android.com/about/versions/17/behavior-changes-all#restrict-implicit-uri-grants) | 公式本文はAndroid17のprep・**Android18で制限**とする。API37 mandatory制限として捏造しない。C4/C5の明示grant/ClipDataを保持しSAF/share smokeで確認 |
| Android17 [usesCleartextTraffic廃止](https://developer.android.com/about/versions/17/behavior-changes-all#uses-clear-traffic-deprecation) | target35→37固有ではない。#528でmerged network-security-configとcustom URL通信を確認する。cleartext許可を無条件追加しない |
| target37 [in-task Activity Security opt-in](https://developer.android.com/guide/components/activities/secure-bal#developer-opt-in) | `allowCrossUidActivitySwitchFromBelow=false`の明示採択が必要。target引上げだけの既定強制としない。#527で現在manifestとassistant/widget/document復帰を確認し、採用するならspecに明記 |

## 5. Phase 3再検証への入力

Epic #516のPhase 3計画は次の表を引用し、merged baseline SHA、APK target/merged manifest、OS build・API、library version、command/resultと実機証拠を記録する。ここは計画であり、今回の実行結果ではない。

| ID / owner | 環境・操作 | 合格条件 / 観測 |
|---|---|---|
| V1 / #526 | API36 CI＋API37 Pixel9a、gesture/3-button、cutout/IME、homeedit/organizer/preview/diagnostics/backup/destination picker | system barによるCTA/図の被覆なし。Backで中断/破棄/cancelの既存契約、busyで無確認applyなし。戻る・keyboard focus・TalkBackを確認 |
| V2 / #526 | sw600dp以上のtablet/foldable/desktop、portrait↔landscape、split/freeform resize、Activity recreation | 選択/未確定計画の保持または安全破棄はaccepted specどおり。勝手にcommitしない。stale captureを適用せず、lock/Undo/一括applyの既存gateを維持。Pixel9a単独を大画面証拠にしない |
| V3 / #526 | font scaling、Arabic/Thai等＋日本語、外部keyboard/CJKV IME、rename/import/search入力 | clipping/隠れたボタンなし。文字変換とTalkBack feedback、入力取消/画面回転が安全 |
| V4 / #527 | API37 foreground/background・default HOME有/無、widget bind/configure/result、PendingIntent/IntentSender、shortcut/app launch、bugreport action、exchange/SAF復帰 | 成功と拒否がspecどおり。BAL blocked log/StrictMode・lintを分類。HOME例外だけで全app互換を主張しない。新modeでbackground許可を過剰に拡大しない |
| V5 / #528 | API37 LAN許可/拒否/取消、Internet/custom HTTPS、merged OkHttpとserver、API36比較 | LANの採択specどおりの通信/説明。ECH対応・GREASE/CT適用をlibrary条件と分けて記録。TLS検証を全域無効化しない。Katbin成功/失敗とorganizer local-only維持 |
| V6 / rebase統合・#516 | API37 contacts permission grant/deny/revoke、widget/Smartspace更新・配置・restore、large RemoteViews provider | C6の列削除がmerged treeで保たれ検索成功。provider送信とhostエラーを区別し、外部provider失敗をlauncherのtarget変更へ誤帰属しない |
| V7 / #516・#522 | API36/37大きいhomeでplanner→preview→apply→相関reload→独立DB照合、recovery、process restart、backup/SAF/import、package install/work profile | MessageQueue変更でtimeout/false successなし。conservation/lock/profile/transaction契約を既存test表面で再確認。#522のbackup/rollback条件を別途満たす |
| V8 / Phase2 build・#516 | merged dependency/packaged native/submodule/test tooling点検、API37 instrumentation idle | Espresso>=3.7.0を保持。Robolectricを実行する構成なら>=4.17/PAUSEDを確認。private MessageQueue/static final write/native DCL consumerを再scan。新規test/CIを追加する場合のみtest-audit skill/既存portfolioを適用 |

## 6. 証拠の限界・終了条件

- 本研究はsourceと公式資料の静的調査。target37 APK build、merged manifest生成、API36/37 emulator/実機操作、network handshake、NPU/native artifactのruntime観測は行っていない。現在target35でのAPI37実機成功（#442等）はtarget37での合格証拠に代用しない。
- API token searchはdependency bytecode、submodule内部、packaged native binaryを含まない。C12の(c)は指定source面でのconsumer不在という根拠付き判断であり、全libraryの無影響証明ではない。Phase2/3のV8でclosureする。
- (a)もrebase結果へ実際に取り込まれて初めて解決する。特にcontacts account列の削除を旧baseline側差分で復活させない。upstream anchorを変えるならADR-0018の改訂手続と本表の再評価が必要。
- sdk minor2のbuild/toolchain採択はADR-0018で確定済み。公開behavior資料の更新やartifact依存で新たな変更が見つかればPhase3の追跡Issueへ記録する。未知を(c)へ黙示変換しない。

| #521終了条件 | 成果物 |
|---|---|
| behavior一覧が証拠付き | §2の固定identity/コード、§3のT01〜T27、§4のtarget外条件、公式URLと確認日 |
| (a)/(b)/(c)と(b)実装Issue | §1/§3、#526（固有UI）、#527（BAL共通残存）、#528（通信共通残存）。全件rebase後の実施とspec未受入を明記 |
| Phase3の確認項目 | §5 V1〜V8。Epicの既存再検証へ入力、未実行結果は§6 |

## 7. Prior art / change history

External reference scanはAndroid/platform扱いのresearchとして実施。

| 対象 / URL（確認2026-10-04） | 採用するpatternまたは不採用理由 |
|---|---|
| Android [target16](https://developer.android.com/about/versions/16/behavior-changes-16)、[target17](https://developer.android.com/about/versions/17/behavior-changes-17)、[all16](https://developer.android.com/about/versions/16/behavior-changes-all)、[all17](https://developer.android.com/about/versions/17/behavior-changes-all)、[17 release notes](https://developer.android.com/about/versions/17/release-notes) | target・OS・opt-in条件を区別する。all-app内のtarget条件/NPUも拾い、一般機能追加を必須修正と混同しない |
| Android [MessageQueue migration](https://developer.android.com/about/versions/17/changes/messagequeue)、[secure BAL](https://developer.android.com/guide/components/activities/secure-bal)、[network security config](https://developer.android.com/privacy-and-security/security-config)、[LAN](https://developer.android.com/privacy-and-security/local-network-permission) | public idle API/対応test version、sender/creatorとvisible条件、domain別network判断の根拠に採用。global opt-outや一律BAL許可は不採用 |
| [Lawnchair固定candidate](https://github.com/LawnchairLauncher/lawnchair/tree/43a21b43d7cc7850ab54e14b1a57dc9646685f35) | C1/C3/C6の取込み可能対応とC5/C7の残存を分離。Nunu固有UIへ自動的に波及するとは扱わない |

- 2026-10-04: 初版。#521の研究成果として固定SHAと公式条件を照合、後続実装#526/#527/#528を起票、Phase3入力を記録。production code/SDK変更なし。研究の受入状態はPRの独立Review・Owner decision・mergeで確定する。
