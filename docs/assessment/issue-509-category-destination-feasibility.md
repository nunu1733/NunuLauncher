# Issue #509 — カテゴリ別新規アプリ配置のfeasibility調査（Phase A）

> Status: Complete（technical investigation）/ **implementation deferred** / owner product decision **pending**（判定: **`rebase後へ延期`**。Revision 2で「前へ進める」を撤回。owner判断項目はPhase A exitの未完了項目として§7に明示）
> **Revision 5（2026-10-14）**: #516（Lawnchair 16 rebase Epic）完了と受入ADR-0018成立を受け、新baselineでPhase Aのseam再調査とoracle再実験を実施した（Issue本文の再開条件②）。技術判定・延期理由・owner判断待ちの状態はRevision 4から変更なし（§1.5）。
> Date: 2026-10-14（Revision 5同日。Revision 1〜4: 2026-10-04）
> 対象commit: `main` @ `391ad7eaa0f84f43af67f7101071f91325c998d9`（2026-10-14時点。Lawnchair 16-dev `43a21b43d7cc7850ab54e14b1a57dc9646685f35` baselineへのcutover後。本Revision 5のfork側path:line実測とエミュレータ実証はこのcommitで実施）。Revision 1〜4の対象commit: `8508c14182412c2a9cf6f6240eadfe30b5322047`（再確認 `aac74df8e2cf3503af2f729934cc77d40f57e51b`）
> Environment: **Revision 5**: emulator `issue526_api37_pixel_9a`（API 37、google_apis、arm64）。Build: `Lawnchair.16.Dev.(391ad7e).github.debug.apk`（対象commitから`assembleLawnWithQuickstepGithubDebug`）。fixture install対象は既存の`tests/benchmark-install-targets/`（target01）。badging付きsession作成用probe app（§3.6。`QUERY_ALL_PACKAGES`＋`REQUEST_INSTALL_PACKAGES`を宣言する最小Activity。sourceとraw logは`docs/assessment/issue-509-oracle-evidence/`へdurable snapshotとして保存）。**Revision 1〜4**: `nunu_smoke_api35`（API 35）＋ `nunu_qpr2_api36_1`（API 36.1）、`Lawnchair.15.Dev.(8508c14).github.debug.apk`
> 出典Issue: [#509](https://github.com/nunu1733/NunuLauncher/issues/509)。延期依存先: #516（Lawnchair 16 rebase Epic。**2026-10-14に完了close**）。
> Review: Phase 1 review round 1（ChatGPT、[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973424478)）の指摘1（高）でRevision 1のplatform因果説明の誤りを指摘され、probe実験とAOSP行レベル再確認（§3.1〜§3.3）により判定を訂正した。round 2（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973633431)）の指摘（高1件・中1件・低1件）をRevision 3で対応（状態分離・証拠強度の整合・obsolete draft明記）。round 4（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973715653)）で条件全解除。**Revision 5 review round 1**（ChatGPT、[PR #592 comment](https://github.com/nunu1733/NunuLauncher/pull/592#issuecomment-6160025793)）の指摘（高2・中2・低1）をRevision 6対応（PR #592上の修正commit）で対応: 対象commit完全SHA訂正、oracle証跡のdurable化（`issue-509-oracle-evidence/`）、`isTrustedPackage` gate帰属の証拠強度修正（直接観測→突合による特定）、emulator構成限界の限定表現、§1 ownership区分の整合。

## 1. 結論（Phase A判定: Revision 2〜5で不変）

**`rebase後へ延期`**。Revision 1では「promise icon経路は現代のAndroidで第三者launcherに到達しない」ことを根拠に「前へ進める」と判定したが、この因果説明は誤りであった（review round 1指摘1。訂正の経緯と証拠は§3）。訂正後の事実:

1. **capture時分類を既存seamで接続する技術は成立する**。変更surfaceはfork-owned moduleのみで、`ItemInstallQueue` / `AddWorkspaceItemsTask` / `ModelWriter` / `PersistedItemArray`（upstream由来のplatform file4種）とfork-onlyの`DirectEditContract.java`からなるbridge/wire/write関連5fileへの本機能由来の新規fork patch増分は0行のままである（§2。Revision 5: #532により全て新baselineへ再適用済みで、upstream 16-dev `43a21b43d7`比の差分はfork seam自体の再適用分である）。
2. **実機の主要install経路（trusted installer＝Play経由の新規install）では、captureがinstall完了前に発生し、その時点で分類signalが読めないと強く示唆される**。Session callbackはQUERY_ALL_PACKAGESを持つ第三者launcherへ届き（§3.1〜§3.3でplatform機構を実証）、launcherコードの構造上、**Play等の主要経路がbadging付きpromise sessionとして動く場合**はpromise icon経路が発火してcapture→UPSTREAM snapshot→上流既定配置となり、完了後の再captureは`alreadyAddedPromiseIcon`で抑止される。ただし**Play実機での発火とbadging成立の直接確認は未実施であり（§8.1）、本項は「AOSP＋launcherコード＋probe実証から強く示唆される」段階の記述である**。したがって実機主要経路でのpromise契約（capture時分類）をPhase Aで成立証明できず、**「Play等の主要経路がbadging付きpromise sessionとして動く場合、capture時分類は成立しない」という条件付きの構造認識にとどまる**（§3.4）。
3. よって本機能がカテゴリ振り分けを成功させる対象は「promise経路を通らないcapture（信頼できないinstaller経由のinstall等）」に限られる（§3.3）。これはIssue本文が要求する「上流が追加を決めた新規アプリを追加操作なしで該当フォルダへ置く」の中核経路を外れるものであり、**成功対象の縮小を利用者にどう説明するかという製品判断がowner判断として未成立である**。Issue本文の停止規則「分類品質/対応identityの製品判断が未成立でも実装を停止する」を適用し、実装へは進めない。
4. 「後へ回す条件」の技術的動機（追加のupstream bridge、model/loader bridge拡張、queue wire format/`DirectEditContract`拡張の必要性）には**該当しない**（bridge増分は0のまま）。延期の理由はplatform由来のcapture時点制約と、それに伴う製品判断の未成立である。したがって再開条件は、Issue本文の定義（「#516完了と受入ADRの成立→新baselineでseam再調査→owner再判断」）に加え、**「ownerがpromise経路を成功対象外とする縮小scopeを受容するか」の判断**が先決である（§9）。rebase完了だけでは制約は解消しない。
5. **Revision 5（新baseline再評価）の追加結論**: #516完了後の`main`（Lawnchair 16-dev baseline）で、(a) #497 destination-policy seamはfork-owned moduleとして全て存続し、検証に用いた4field/2kind snapshot・capture hook・stage-2検証・S1→S2→S5優先順位・typed fallbackの機械的契約は0 diffで維持される（§2）、(b) Revision 2のE1結論（shell sessionはbadging欠損でpromise不発・commit後追加は`has app icon: false`）を新baseline APKで再現した（§3.6 E1'）、(c) badging付きsessionを非特権appから構成し、session作成時（badging＋reason=4＋target未install成立）にpromise captureが発火しないことを観測した（§3.6 E4）。`verify()`→`isTrustedPackage` gate（installerがsystem appでない場合の排除。upstream由来・fork patch増分0）への帰属は、dumpsysで満たされた他の全条件との突合から**特定される（直接観測ではない。§3.6の限定表現）**。この構造は、**非system installerのsessionがinstall後の`SessionCommitReceiver` captureへ落ちる＝非promise captureでは分類signalが読める**ことを実機構で裏付ける。promise経路の発火条件（badging＋trusted installer）を持つsessionは、本実験の`google_apis` emulator構成では構成不能であるため（§3.6 E4の帰結4。Play Store付きimageは範囲外）、§1.2の条件付き構造認識と§8.1の実機oracleは不変である。**判定は`rebase後へ延期`のまま更新しない**。

判定の記録に合わせ、Revision 1で作成した進行側の成果物（`specs/509-category-new-app-destination/spec.md`、`docs/adr/0017-category-new-app-destination.md`の草案）は本revisionでbranchから取り下げた。設計草案は履歴（commit `59aa164a62`）に残っており、owner判断で縮小scopeを受容して再開する場合の下書きとして参照できる。

## 2. 調査項目1: 既存seamだけの最短経路（変更候補pathとcall flow）— 技術判定としては成立（Revision 5で新baseline再確認済み）

### 2.1 自動追加のcall flow（Revision 5: `391ad7eaa0`実測。旧baselineの記載はChange history参照）

```text
[install完了] ACTION_SESSION_COMMITTED
  → SessionCommitReceiver.onReceive（MODEL_EXECUTOR）→ processIntent
      - isEnabled(context, user)を確認
        （src/com/android/launcher3/SessionCommitReceiver.java:60-63、本体:112-122。
         add icon preferenceが既定gate。lockHomeScreenはprivate space＋
         privateSpaceRestrictItemDrag flag成立時のみ遮断に変わった）
      - INSTALL_REASON_USER以外・alreadyAddedPromiseIcon=trueは何も積まずreturn（:78-95）
      - ItemInstallQueue.INSTANCE.get(context).queueItem(appPackageName, user)（:102-103）
          → resolver.captureDestination(mContext, packageName, userHandle)   …★capture点
            （src/com/android/launcher3/model/ItemInstallQueue.java:240-245。resolver委譲:240、capture:244）
          → PendingInstallShortcutInfo.mDestinationSnapshotへ載せてqueue XMLへ永続化
            （:104-126のEntryExtension、ATTR_DESTINATION_POLICY宣言:106。first enqueue wins
             は:152-159のaddToQueue重複排除）
  → flushQueueInBackground（:161-183）
      - attachDestinationRoute（:461-477、ファイル末尾）: persisted snapshotを読むだけ。
        有効なUPSTREAM snapshotだけstock pathへ、それ以外はpolicy routeへ
      - commitはMAIN_EXECUTOR経由のcommitInstallQueue（:185-199）が
        commitDelete()＋snackbar closeの後、AddWorkspaceItemsTaskをenqueue（:178-180）
  → AddWorkspaceItemsTask（route decode: :132-135、policy write判定: :135）
      - policy route → ModelWriter.addPendingInstallForDirectEdit（ModelWriter.java:785-789）
        admission内でstage-2再検証→ID採番→INSERT→post-admission bind
        （実行はexecuteOnModelThreadのLayoutWriteCoordinator gate:659-675を通る。#532 G5）
      - stock route → mItemSpaceFinder.findSpaceForItem→writer.addItemsToDatabase（batch、:250-259。既定のまま）
```

promise icon経路（`pm/InstallSessionHelper.tryQueuePromiseAppIcon`、`InstallSessionHelper.java:226-247`）も同じ`queueItem(String, UserHandle)` overload（`ItemInstallQueue.java:233-247`）へ流れるため、capture点は同一である。

### 2.2 変更候補path（全てfork側。Revision 5: upstream 16-dev比でfork seamは#532により再適用済み）

カテゴリモードを実装する場合の変更は全てfork側に限られる:

- `AppDestinationAdapter.kt`（`Resolver.captureDestination`: 121-150）: capture時にS1→S2→S5で分類signalを解決し、mappingでfolderIdへ変換して既存`FOLDER` snapshotを返す。解決不能は既存`UPSTREAM` snapshot。
- `AppDestinationPolicyPrefs`（実体はAppDestinationAdapter.kt:229-247）: policy値とmapping行の永続化。
- `homeedit/ui/AppDestinationPreference.kt`（:109-160）: 選択肢とmapping管理UI。
- `strings.xml` + `values-ja`: 新規文字列。
- ADR-0015 Decision 3/15の拡張はsuccessor ADRで行う（本文上書きなし）。

**Revision 5のpatch surface実測（`43a21b43d7..391ad7eaa0`）**: 上流の`ItemInstallQueue.java`（+86行）、`AddWorkspaceItemsTask.java`（+67行）、`ModelWriter.java`（+943行）、`util/PersistedItemArray.java`（+39行）、`model/DirectEditContract.java`（fork-only、upstream非存在、453行）が差分を持つ。これは#532（`de06b3da1f`/`35a9fc02ed`）による#497 seamの新baselineへの再適用そのものであり、**本機能（カテゴリモード）のために必要な追加patch増分は0**という判定は変わらない。`SessionCommitReceiver.java`、`pm/InstallSessionHelper.java`、`AndroidManifest-common.xml`はupstream 16-dev比で0 diffである（rebaseで上流変更がそのまま入ったが、forkは触っていない）。

**seam契約の新baselineでの存続（Revision 5実測、全て0 diffまたは意味論同一）**: `DirectEditContract.java`の4field・2kind snapshot（:275-320）、`isValidUpstreamSnapshot`（:329-337）、`DestinationResolver`（:431-439。`captureDestination(Context,String,UserHandle)`と`route(String,long,String)`）。`AppDestinationAdapter.kt`のcapture（:121-150）とstage-2 validator（:61-102）。`AppDestinationPlanner.kt`の検証（:83-159）と`DestinationPolicySnapshot.Kind { UPSTREAM, FOLDER }`（:18-25）。分類seam（§2.3）の`CategoryOverrideStore.kt:489-496`、`AndroidClassificationSignalSnapshotSource.kt:27-64`（fail-closed :38-39、profile分離 :34-38）、`OrganizationInputComposer.kt:527-531`（S1→S2→S5）、`organizer/application/adapter/CanonicalProfileId.kt:12-16`。`AddWorkspaceItemsTask`のroute分岐（:132-135/:243-244）。upstream由来の機械的変化として、`ItemInstallQueue`のflush commitが`commitInstallQueue`経由になり、`ModelWriter`の実行が`LayoutWriteCoordinator.runOrDefer` gateを通るようになった（#532 G5）が、いずれもcapture点・snapshot wire・stage-2 admission契約には影響しない。

### 2.3 分類seamの再利用（organizer run全体は起動しない）

- **S1 override**: `CategoryOverrideStoreModule.source(appContext)`（`rules/CategoryOverrideStore.kt:489-496`。production配線実績は`ProductionOrganizationInputComposer.kt:22`）。
- **S2/S5**: `AndroidClassificationSignalSnapshotSource(appContext).read(requests, policy)`（`integration/AndroidClassificationSignalSnapshotSource.kt:27-64`）。`getApplicationInfo`がnull（未install・不可視）なら`Unreadable`でfail-closed（:38-39）。profile分離はuser引数で既存実装どおり（:34-38）。`ClassificationPolicy`は`BuiltInOrganizerPolicyBundleSource`（`rules/BuiltInOrganizerPolicyBundleSource.kt:19-36`。Android category 0..7→組み込みtaxonomy、`googleCategory=TOOLS`、`systemCategory=OTHER`）。
- **優先順位**: `OrganizationInputComposer.materializeSignals`（`integration/OrganizationInputComposer.kt:527-531`）と同一のS1→S2→S5。
- **profile identity**: `canonicalProfileId(userCache, user)`＝userSerial文字列（`organizer/application/adapter/CanonicalProfileId.kt:12-16`、`planning/Identity.kt:23-27`）。resolverは既に同一のuserSerialをcaptureしている（`AppDestinationAdapter.kt:133`）。

## 3. 調査項目2: 分類の時点と品質（Revision 2で全面訂正）

### 3.1 Session callbackは第三者launcherへ届く（Revision 1の誤りを訂正）

Revision 1は「現代のAndroidではinstall-session callbackが第三者launcherへ配信されない」と記載した。これは誤りである。review round 1指摘1を受け、AOSPソースを行レベルで再確認し、さらにprobe実験（§3.2）で実証した:

**platform機構（android15-release / android16-release / mainの3revisionで行単位確認。2026-10-04）**:

- 登録: `LauncherApps.registerPackageInstallerSessionCallback`（SDK）→ `LauncherAppsService.registerPackageInstallerCallback`（android15: `services/core/java/com/android/server/pm/LauncherAppsService.java:356-368`）。権限要求なし。`PackageInstallerService.registerCallback`へprofile filterのみ付して登録（`InstallSessionTracker.java:174-181`のlauncher側登録に対応）。
- 配信: `PackageInstallerService.Callbacks.handleMessage`（android15: `PackageInstallerService.java:2037-2052`）が各eventに`shouldFilterSession(snapshot, cookie.callingUid, sessionId)`を適用する。実体は同:1849-1857（android16: :1892-1897）:
  `uid != session.getInstallerUid() && !snapshot.canQueryPackage(uid, session.getPackageName())`
- **`ComputerEngine.canQueryPackage`（android15: :5436-5485。android16: :5471-）は未install対象に明示的な「new installing case」を持つ**（android15 :5453-5458）: targetが未install（`targetAppId == INVALID_UID`）でも、caller packageの`AndroidPackage`で`mAppsFilter.canQueryPackage(pkg, targetPackageName)`を判定する。
- `AppsFilterBase.canQueryPackage`（android15: :683-。main: :683-695）は`requestsQueryAllPackages(querying)`ならtrue（:694）。**`QUERY_ALL_PACKAGES`を宣言するlauncherは、targetが未installでもsession eventを受け取れる**。launcherは`AndroidManifest-common.xml:43`で`QUERY_ALL_PACKAGES`を宣言し、実機上grantedであることを`dumpsys package`で確認済み。

**probe実験（2026-10-04、API 35 `nunu_smoke_api35`）**: `QUERY_ALL_PACKAGES`のみを宣言する最小Activity（`LauncherApps.registerPackageInstallerSessionCallback`を登録するだけのprobe app。repository外の一時artifact）をinstallし、`pm install-create -i com.android.vending --install-reason 4`でsessionを作成・writeした:

```text
10-04 06:08:45.116 SessionProbe: registered
10-04 06:08:46.326 SessionProbe: onCreated 1755274628
10-04 06:08:46.357 SessionProbe: onActiveChanged 1755274628 true
10-04 06:08:46.359〜.379 SessionProbe: onProgressChanged 1755274628（複数回）
```

第三者data appでもsession eventが配信されることを直接実証した。Revision 1の「配信されない」因果説明は撤回する。

### 3.2 launcher実験でpromise経路が発火しなかった真因: shell sessionのbadging欠損

Revision 1のlauncher実験（§3.5のE1〜E3）でpromise enqueueのログが一切出なかった原因は、platform配信ではなく**`verifySessionInfo`のicon/label要件**である:

- `verifySessionInfo`は`sessionInfo.getAppIcon() != null && !TextUtils.isEmpty(sessionInfo.getAppLabel())`を要求する（`pm/InstallSessionHelper.java:260-261`。新baseline実測）。
- `pm install-create`/`install-write`で作ったshell streaming sessionにはapp icon/labelが付かない。自分のE1ログ自身がこれを記録している:
  `SessionCommitReceiver: Adding package name to install queue. Package name: app.lawnchair.benchmark.target01, has app icon: false, has app label: false`（API 35、03:39:22.944）
- probe実験でも`onBadgingChanged`は一度も記録されなかった（badging metadataがsessionへ反映されないことの裏付け）。
- よってlauncherはeventを受けても`verifySessionInfo`で静かに落ち、promise enqueueは行われない。**実運用のtrusted installer（Play等）のsessionはbadging（icon/label）を運ぶ**ため（ADR-0015 Context §2がpromise icon機能の前提として記述）、この欠損はshell実験固有である。

### 3.3 capture時点ごとの分類可否（訂正版）

| 経路 | capture時点 | アプリのinstall状態 | S1 | S2/S5 | カテゴリ解決 |
|---|---|---|---|---|---|
| **trusted installer（Play等）＋badging付きsession**（実機の主要経路） | promise icon enqueue（install完了前。§3.1の機構＋badging成立で発火） | 未install | store読めるが新規アプリにoverrideなし | **読めない**（`getApplicationInfo`=null→`Unreadable`） | **不可** |
| 同（完了後） | `alreadyAddedPromiseIcon=true`で`SessionCommitReceiver`がreturn（:78-95。新baseline実測） | — | — | — | **再captureは起こらない** |
| 信頼できないinstaller経由のinstall（install reason USER） | `SessionCommitReceiver`（install完了後） | install済み・可視 | 同上 | **読める** | 可（signalがあれば） |
| shell stream（badging無し）・adb（reason不明で自動追加自体が不発。benchmark §7実績） | 完了後（queueに入る場合のみ） | install済み | 同上 | 読める | 可（signalがあれば） |

結論: **AOSP＋launcherコード＋probe実証から、Play等の主要経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆される**（promise captureが分類不能時点で起こり、完了後の再captureは抑止される。Play実機での直接確認は§8.1を再開時の最初のoracleとする。Revision 5のE4実験で、badgingを満たす非system installer sessionがpromise captureを発火させないことを実測し、`verify()`→`isTrustedPackage`が実効gateであることをコード根拠＋突合で裏付けた。§3.6）。効くのは非promise captureに限られ、さらにS2のcoverageは「manifestで`appCategory`を宣言するアプリ」、S5は「新規の`com.google.*`アプリ」に限られる（S1は再install時のoverrideのみ）。ADR-0015 Alternatives/#446メモの「分類品質が不十分（8種＋OTHER）」の記録に、capture時点制約が重なる。

### 3.4 fallback設計の位置づけ（訂正後）

promise段階でのcapture（分類不可→`UPSTREAM` snapshot→上流既定配置）は、#497の契約（first enqueue wins・flush読み出しのみ・stage-2再検証）の中で決定的かつ安全に動く。しかしIssue本文の要件「利用者が明示したカテゴリと既存フォルダの対応により、上流が追加を決めた新規アプリを追加操作なしで該当フォルダへ置く」に対し、**Play等の主要経路がbadging付きpromise sessionとして動く場合、主要経路が常にfallbackになる構造になる**（§3.3。Play実機での直接確認は§8.1）。この場合、成功対象（非promise captureのみ）を利用者に明示し、その価値をownerが受容することが前提になる。この製品判断が未成立のまま実装へ進むこと禁止している（Issue本文・停止規則）。「install完了後にもう一度振り分ける」を採用しない方針（Issue本文どおり）は、訂正後も変わらない。

### 3.5 再現手順（E1〜E3＋probe。API 35 `nunu_smoke_api35`、emulator-5574）

```bash
# 前提: 対象commitのdebug APKをinstall済・既定HOME・前面
adb install -r build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(8508c14).github.debug.apk
adb shell cmd package set-home-activity "app.lawnchair.debug/app.lawnchair.LawnchairLauncher"
adb shell am start -n "app.lawnchair.debug/app.lawnchair.LawnchairLauncher"

# E1: trusted installer属性（vendingは flags=[ SYSTEM ... ] を別途確認済み）でsession作成
APK=tests/benchmark-install-targets/build/outputs/apk/target01/debug/benchmark-install-targets-target01-debug.apk
adb logcat -c
SID=$(adb shell pm install-create -i com.android.vending --install-reason 4 -r -S $(stat -f%z "$APK") | grep -o '[0-9]*')
cat "$APK" | adb shell pm install-write -S $(stat -f%z "$APK") $SID base
adb shell pm path app.lawnchair.benchmark.target01        # → 空（未install）
adb logcat -d | grep -E "ItemInstallQueue|SessionCommitReceiver|InstallSessionHelper"
#   → write中の出力なし。※Revision 1はこれを「配信されない」証拠としたが誤り。
#     真因はshell sessionのbadging欠損（verifySessionInfoのicon/label要件。§3.2）
adb shell pm install-commit $SID
sleep 3
adb logcat -d | grep -E "SessionCommitReceiver|ItemInstallQueue" | grep -v "  at "
#   → "Adding package name to install queue. ... has app icon: false, has app label: false"
#     と queueItem のスタック（SessionCommitReceiver.java:103 → ItemInstallQueue.java:244。新baseline実測）

# E2: reason 3（USER以外）→ "Removing PromiseIcon ... install reason: 3" でenqueueされない
# E3: installerにlauncher自身を指定（isTrustedPackageのlauncher節）→ 同様にwrite中は無出力

# probe: QUERY_ALL_PACKAGESのみの最小ActivityがregisterPackageInstallerSessionCallbackを
#   登録し、同じsessionでonCreated/onActiveChanged/onProgressChangedを受信（§3.1）。
#   → 配信は起こる。launcher実験の不発はbadging欠損によるverifySessionInfo失敗。
```

install reason定数の実測値: API 35/36とも`--install-reason 4`が`INSTALL_REASON_USER`として受入れられた（E1/E3でcompletion enqueue、E2の3は拒否）。`docs/engineering/editing-burden-benchmark.md` §7の記録と一致。

### 3.6 Revision 5のoracle再実験（2026-10-14、API 37 `issue526_api37_pixel_9a`、`391ad7eaa0` APK `Lawnchair.16.Dev.(391ad7e).github.debug.apk`）

#516完了後の再評価として、Revision 2のE1再現と、Revision 2で未実施だった「badging付きsessionの直接構成」を実施した。**durable証跡は`docs/assessment/issue-509-oracle-evidence/`**（READMEに実施session・再現手順・主要観測のlog引用・session ID時系列の限界・証拠強度の限界。probe appのsource（E4a版＋実機install済みAPK/disassemblyから復元したE4b3版）、E1'/E4a/E4b3のraw log、終了時`dumpsys` snapshot、workspace確認pngを収録）。

#### E1'（対照実験。shell session、installer=vending、reason=4、badging無し）

`pm install-create -i com.android.vending --install-reason 4`→write中のlauncher側logはpromise enqueueゼロ。commit 3秒後に`SessionCommitReceiver: Adding package name to install queue. Package name: app.lawnchair.benchmark.target01, has app icon: false, has app label: false`→`ItemInstallQueue`（install後の通常追加）。**Revision 2のE1結論（badging欠損でpromise不発・captureはinstall後）を新baselineで再現した。**

#### E4a（probe: installer=vending偽装のbadging付きsession）

非特権appから`setInstallerPackageName("com.android.vending")`付きsessionを作成すると、`PackageInstallerService.createSessionInternal`のuid check（`AppOpsManager.checkPackage`）で`SecurityException: Package com.android.vending does not belong to 10238`として**session作成時点で拒否される**（commit制限以前の話）。installer名の偽装はplatformレベルで不可能である。

#### E4b3（probe: installer=自己package、badging付きsession。本実験の核心）

非特権probe app（`ProbeActivityV3`。durable証拠の`probe-v3-installed.apk`が実行正）が`setAppIcon`＋`setAppLabel`＋`setInstallReason(4)`＋`setAppPackageName(target)`＋installer=自己package＋target未installの条件下でsessionを作成した。実測:

- `PackageInstallerSession: Writing changed icon /data/system/install_sessions/app_icon.<sessionId>.png` — systemがsession iconを永続化（badging搭載のsystem証跡）。
- `dumpsys package`のsession snapshot: `appPackageName=app.lawnchair.benchmark.target01 appIcon=true appLabel=Benchmark Target 01 installReason=4 installerPackageName=app.lawnchair.probe.sessionbadge`、**create時点で`pm path`空（target未install）**。
- commitは`STATUS_PENDING_USER_ACTION`（ユーザー同意必須）。同意flowを完遂しinstall完了まで記録。完了後は`SessionCommitReceiver: ... has app icon: true, has app label: true`→`ItemInstallQueue`（install後の通常追加。workspaceへicon追加を確認）。なおsession ID時系列の限界（作成sessionと完了callbackのID対応が本logcatからは一意に結べない。durable証拠README「session ID時系列の限界」）があるため、この記述は「同構成のbadging付きsessionの同意flow完遂後の観測」レベルである。
- **launcher側のpromise経路は発火しなかった**。session作成時（badging＋reason=4＋target未installの全条件成立）に`InstallSessionTracker`/`InstallSessionHelper`/`SessionCommitReceiver`のlogは一切なし。コード上の経路は、launcherが`LauncherApps.registerPackageInstallerSessionCallback`でeventを受信した後、`pushSessionDisplayToLauncher`→`getVerifiedSessionInfo`→`verify()`→`isTrustedPackage(installer)`（`pm/InstallSessionHelper.java:163-171`、`ApplicationInfoWrapper.kt:112`の`isSystem()=hasFlag(FLAG_SYSTEM)`）で排除され、`tryQueuePromiseAppIcon`へ到達しない（:226-247）というものである。**本実験の観測（log不在）は、この`isTrustedPackage`排除経路と整合する。ただしlog不在はcallback入口自体へ届かなかった場合とも区別できないため、gate帰属は「dumpsysで満たされた他の全条件（reason・icon・label・target未install）との突合から特定される」段階であり、callback入口〜gate判定の直接観測ではない**（gate通過後の詳細logはverified sessionがnon-nullになった後にのみ出る。durable証拠README「gate帰属の証拠強度」参照）。

#### E4の帰結

1. **badging付きsessionは非特権appでも構成できる**（Revision 2で「shell sessionにしか付かない」と観測したbadging欠損は、shell `pm install-create`にbadging flagが無いことによる。app-sourceなら`SessionParams.setAppIcon/setAppLabel`で付く）。
2. **非system installerのbadging付きsessionは、promise captureを発火させない**。これは`isTrustedPackage`排除経路と突合により特定される（上記の証拠強度限定。upstream由来のgateであり、fork patch増分0のままである）。
3. したがって非system installer経由のinstallは、captureがinstall後の`SessionCommitReceiver`で起こる（=分類signalが読める経路。§3.3第3行の実機構での裏付け）。
4. **「badging＋trusted installer（system app）＋target未installでのpromise capture発火→capture時分類不可」の直接oracleは、本実験の`google_apis` emulator構成（`issue526_api37_pixel_9a`）では構成できない**: shell sessionはbadgingを運べず（E1'）、非特権appはinstallerをsystem app名に偽装できない（E4a）ため。**この主張は本構成に限定され、Play Store付きsystem image（vendingが実際に動作する`google_apis_playstore`等）や実機を含まない**。後者ではvending等のsystem installerが実際にbadging付きsessionを作成する構成があり得、それが§8.1の実機oracleである。
5. E4でpromise経路へ未到達のため、「promise capture時点でS2/S5が読めない」の直接実証は未達のままである（§8.1と同じ未確認範囲）。§1.2/§3.3の条件付き構造認識を強めるものではない。

## 4. 調査項目3: folderとcategoryの対応identity（参考。実装へは進めない）

第一候補はfork-ownedな`CategoryIdentity + profile → persistent folderId`の明示mappingである。#497の指定フォルダと同じid-based classであり、rename不変・削除→`FOLDER_MISSING` typed fallback・同名再作成で復活しない・別profile→`PROFILE_MISMATCH`・Dock→`DOCK_FOLDER`・backup/restore exposure同一・user-defined category削除→capture時解決失敗（fail-closed）の性質は、#497の受入済み意味論と同一である。folderタイトル一致・子の多数決・暗黙のfolder成長・organizer provenance転用は、ADR-0015 Decision 13(b)とIssue本文の禁止どおり採用しない。この節はowner判断で縮小scopeを受容して再開する場合の設計基盤として残す。

## 5. 調査項目4: 再flushの決定性（技術的には継承される）

分類・対応mapping・カタログはenqueue時（captureDestination内）だけ読み、capture後は4field snapshotが不変である。first enqueue wins（`ItemInstallQueue.java:152-159`。新baseline実測）、flush読み出しのみ、stage-2による固定folderIdの再検証（`AppDestinationAdapter.kt:61-102`、`AppDestinationPlanner.kt:83-159`）は#497構造のまま保たれる。ただし主要経路ではcapture自体が分類不能時点で起こるため（§3.3）、この決定性契約はfallback配置の決定性として機能する。

## 6. 調査項目5: 設定とfailure（参考。実装へは進めない）

opt-in UX（既存ポリシー行の拡張）、曖昧・未設定・分類不可の上流既定fallback、対応先folder消失等の既存typed fallback、FileLogへのtyped記録（package名なし、wire拡張不要、通知なし）はいずれも既存seamの延長で設計可能である（§2.2）。network・新権限は不要である。

## 7. Phase A exit条件の照合（Revision 3構造を維持。Revision 5で再評価済み）

- [x] 調査成果物に確認日・対象commit・根拠path/行・再現手順と結果、変更候補path、未確認範囲を記録した（本書。`docs/assessment/issue-509-category-destination-feasibility.md`。Revision 5で新baseline `391ad7eaa0`分を追記）。
- [x] **技術的境界の確定**: profile内classification、明示対応identity、enqueue固定/reflush/stage-2の境界を確定した（§2〜§6。**Revision 5: 新baselineでseam存続を再確認**。§2.2）。ADR-0015を変える部分はsuccessor ADRを要するが、実装停止のため起草しない（Revision 1草案は履歴`59aa164a62`に残す。obsolete draft。§9）。
- [ ] **promise不足時の扱いを含む成功対象scopeについてowner判断が確定した** → **未完了（Revision 5でも未完了のまま）**。promise経路（主要install経路と強く示唆される。§3.3/§3.6/§8.1）のcapture時分類不成立により、成功対象が非promise captureへ縮小することの製品受容がowner判断として未成立である。これが延期の理由そのものである（§1/§9）。再開時はこの項目から始める。
- [x] **前に進める条件は満たさない**: fork-owned moduleのみの変更・既存hook・既存4field snapshot・既存書込み/queue protocolの使用は証明できたが（Revision 5で新baselineでも再確認）、「必要なprofile/**promise**/fallback契約が成立する証拠」をPhase A内で示せなかった（promise契約の成立を裏付ける実機証跡がなく、emulator上の構成限界を§3.6 E4の帰結4で明記、製品scope判断も未成立）。
- [x] **後へ回す条件の技術的動機には非該当**だが、Issue本文の停止規則（製品判断未成立でも実装を停止）により`rebase後へ延期`として記録する（§9）。
- [x] 判定を本Issueに`rebase後へ延期`として記録した（worker comment。Phase 1 review round 1/2の指摘対応と合わせる。Revision 5の再進行記録は§9）。

## 8. 未確認範囲（Revision 5時点）

1. **実機GMS端末＋Play経由installでのpromise capture発火の直接確認**: platform機構はprobe実験（配信実証）＋badging要件のコード根拠＋ADR-0015 Contextのpromise icon前提から強く推定するが、実機でPlay install→promise icon表示→capture時点の分類不可を直接記録していない。**Revision 5のE4（§3.6）で、このoracleが本実験の`google_apis` emulator構成では構成不能であること（shell sessionはbadging不可・非特権appはinstaller偽装不可。Play Store付きimageは範囲外）を実証したため、実機（またはvendingが動作するGMS emulator image）での確認が唯一の構成経路である。** owner再判断時の最初の確認項目とする。
2. **shell sessionのbadging欠損の挙動差**: `pm install-create`にbadging flagが存在しないことはAPI 35/36/37で確認（Revision 5のE4で、badgingはapp-sourceの`SessionParams.setAppIcon/setAppLabel`なら付くことを実証。欠損はshell経路固有）。Play等の実installer sessionでは付くことの直接確認は未実施（同上）。
3. **非promise captureの実際の頻度**（信頼できないinstaller経由installの割合、`INSTALL_REASON_USER`成立率）: 測定していない。縮小scopeの価値評価に必要なdataであり、owner判断の材料とする。
4. work profileでのcapture経路: 構造分析のみ（各profileのlauncher processが自profileのbroadcast/callbackを受け、promise経路の制約は同一）。

## 9. 延期の記録と再開条件（Revision 5: ②のseam再調査完了、判定は不変）

- 本Issueはopenのまま`phase: later`を維持する。依存linkは#516（Lawnchair 16 rebase Epic。**2026-10-14に完了close、ADR-0018 accepted、mainは16-dev `43a21b43d7` baselineへcutover済み**）。
- 延期理由は「追加bridgeの必要性」ではなく「**AOSP＋launcherコード＋probe実証から、Play等の主要install経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆されること**（Play実機は未確認。§3.3/§3.6/§8.1）」と、それに伴う「**成功対象の縮小（非promise captureのみ＋appCategory等のsignal coverage制約）の製品受容がowner判断として未成立**」である。bridge増分0の技術成果（§2）はrevision 1から変更なく成立しており、**Revision 5の新baseline再調査でも維持された**。
- **Revision 5の再進行記録（2026-10-14）**: 再開条件②のうち「#516完了と受入rebase ADRの成立」が成立したため、新baseline（`391ad7eaa0`）でseam再調査（§2）とoracle再実験（§3.6）を実施した。結果: seam存続・bridge増分0・E1結論の再現・`isTrustedPackage` gateの実効性確認。判定を左右する新事実はなく、**判定は`rebase後へ延期`のまま不変**。owner product decision（①）は引き続き未成立であり、本Revisionは①の判断材料を更新するものにとどまる。
- 再開条件（現行版。②のseam再調査は実施済みのため、残るのは①と③）: ①ownerが「promise経路を成功対象外とする縮小scope」を受容するかを判断する（これが先決。受容しない場合、本機能は capture時分類の限界により実装困難であり、FR-009等の別の解決（install後の明示的移動支援）へ分流する）。②受容する場合、新baselineでの再調査はRevision 5で完了済み。§8.1の実機oracle（Play installでのpromise capture発火・badging成立の直接確認）をowner判断の材料として実施するか、emulator構成限界（§3.6 E4の帰結4）を踏まえた縮小証拠での判断とするかをownerが選ぶ。③platform制約（capture時分類の構造的限界）はbaseline更新では解消しない。
- 本調査の他の成果物: Revision 1で起草したspec/ADR草案（履歴`59aa164a62`）は**withdrawn/obsolete draftであり、そのまま再利用しない**。この草案はreview round 1で指摘された論点（ADR-0015 Decision 11との置換関係の明文化、promise/work profile/reflush/category identityのoracle不足）が未修正のままである。再開時は、縮小scopeの受容判断を踏まえたうえで、新baselineでD11 relationの明文化と不足oracleの再設計を行ったうえで起草し直す。

## 10. External reference scan（研究記録）

- AOSP PackageInstallerService（session callbackの配信filter。判定の根幹。android15-release: shouldFilterSession `:1303-1308`/`:1849-1857`、dispatch `:2037-2052`。android16-release: `:1892-1897`。**Revision 5: createSessionInternalのinstaller名uid check（E4aでSecurityExceptionを観測）**）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/PackageInstallerService.java （確認日 2026-10-04、Revision 5追加確認 2026-10-14）
- AOSP LauncherAppsService（`registerPackageInstallerCallback`。権限要求なし。android15-release `:356-368`）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/LauncherAppsService.java （確認日 2026-10-04）
- AOSP ComputerEngine（`canQueryPackage`の"new installing case"。android15-release `:5436-5485`（installing case `:5453-5458`）、android16-release `:5471-`（`:5499-`）、main `:5436-`（`:5461-`））: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/ComputerEngine.java （確認日 2026-10-04。review round 1指摘1で指摘された分岐を行レベルで確認し、probe実験で動作を実証）
- AOSP AppsFilterBase（`requestsQueryAllPackages` fast path。android15-release `:683-`（`:694`）、main `:683-695`）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/AppsFilterBase.java （確認日 2026-10-04）
- Android SDK `LauncherApps.getApplicationInfo(String, int, UserHandle)`: https://developer.android.com/reference/android/content/pm/LauncherApps#getApplicationInfo(java.lang.String,%20int,%20android.os.UserHandle) （確認日 2026-10-04。未install・不可視でnull。`AndroidClassificationSignalSnapshotSource`のfail-closedと一致）
- Smart Launcher Smart Folders: https://docs.smartlauncher.net/products/faq/changelog/5.4 （確認日 2026-10-02。自動分類による全自動追加。本機能は明示mapping＋fallbackだが、capture時点制約により採用判断に至らず）
- Nova Launcher等の第三者launcherのpromise icon表示可否についてのcommunity記録は出典を確認できなかったため根拠に使わず、probe実験とAOSPソースを正とした。

## Change history

- 2026-10-14: Revision 6対応 round 2 — Revision 5 review round 2（ChatGPT、[PR #592 comment](https://github.com/nunu1733/NunuLauncher/pull/592#issuecomment-6162321398)）の残存指摘（高1・中1）への対応。高: E4b3実行sourceの欠落とsession ID時系列の非一意性に対し、実機install済みprobe APK（`probe-v3-installed.apk`）とdisassemblyをdurable証拠へ追加し、復元source`ProbeActivityV3.java`を収録。作成session（`1228612374`）と完了callback（`1120207295`）の1:1対応を証跡から証明できないことをREADME「session ID時系列の限界」と§3.6へ明記し、「install完了後の通常追加」の記述を「同構成sessionの同意flow完遂後の観測」へ弱めた。中: PR本文とIssue #509再進行コメントの旧表現（直接観測・emulator構成不能の一般化・/tmp証跡）を訂正コメント/本文編集で同期。判定・状態は不変。
- 2026-10-14: Revision 6対応 — Revision 5 review round 1（ChatGPT、[PR #592 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-6160025793)）の指摘（高2・中2・低1）への対応。高1: 対象commitの完全SHAを実在する`391ad7eaa0f84f43af67f7101071f91325c998d9`へ訂正。高2: §3.6のoracle証跡を`docs/assessment/issue-509-oracle-evidence/`へdurable化（probe source＋manifest、E1'/E4a/E4b3 raw log、終了時dumpsys snapshot、再現手順）。中1: `isTrustedPackage` gate帰属を「直接観測」から「dumpsysで満たされた他条件との突合による特定」へ証拠強度を修正（log不在はcallback入口未到達と区別できない旨を明記）。中2: 「emulator上で構成不能」を本実験の`google_apis`構成に限定し、Play Store付きimageを範囲外と明記。低1: §1.1の5file列挙から`DirectEditContract`（fork-only）をownership区分として分離。判定・状態は不変。
- 2026-10-14: Revision 5 — #516（Lawnchair 16 rebase Epic）完了とADR-0018受入を受けた新baseline再評価（Issue本文の再開条件②）。対象commit `391ad7eaa0`（Lawnchair 16-dev baseline cutover後）。§2のpath:lineを実測更新し、fork seam（DirectEditContract 4field/2kind・capture hook・stage-2・S1→S2→S5）の存続を確認。§3.6にoracle再実験（E1'対照実験の再現、E4a installer偽装拒否、E4b3 badging付きsession構成とpromise capture不発の観測、`google_apis`構成でのpromise oracle構成不能の明記）を追加。§1.5に再評価結論、§7/§8/§9を現行化。判定（`rebase後へ延期`）・owner product decision pending・Issue open・`phase: later`は不変。
- 2026-10-04: Revision 4 — Phase 1 review round 3（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973674659)）の残存指摘（中1件。高・低は解消確認）に対応。§1/§3.3/§3.4/§9に残っていた無条件の断定を、round 2中指摘と同一の条件付き表現（「Play等の主要経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆される。Play実機は未確認」）へ統一した。
- 2026-10-04: Revision 3 — Phase 1 review round 2（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973633431)）の指摘（高1件・中1件・低1件）に対応。高: §7のowner判断項目を未完了として分離し、状態を「technical investigation complete / implementation deferred / owner product decision pending」へ明示（記録矛盾の解消）。中: §1/§3.3/§3.4/§7のPlay経路に関する断定を証拠強度へ合わせ、「AOSP＋launcherコード＋probeから強く示唆される（Play実機は未確認）」へ修正し、実機直接確認を再開時の最初のoracle（§8.1）として固定。低: 履歴`59aa164a62`の草案をobsolete draftとして明記し、再開時の再起草要件（D11 relation明文化・oracle再設計）を§9へ追記。
- 2026-10-04: Revision 2 — Phase 1 review round 1（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973424478)）指摘1（高）に対応。Revision 1の「session callbackは第三者launcherに届かない」因果説明を撤回し、AOSP行レベル再確認（installing case＋QUERY_ALL_PACKAGES fast path。android15/16/main）とprobe実験で配信を実証。launcher実験の不発の真因をshell sessionのbadging欠損（`verifySessionInfo`のicon/label要件。E1ログ`has app icon: false`が証拠）へ訂正。訂正後は実機主要経路（Play）でpromise captureが発火する構造のため、判定を`前へ進める`から`rebase後へ延期`へ訂正。spec/ADR草案を取り下げ（履歴`59aa164a62`に残存）。
- 2026-10-04: Revision 1 — 初版。対象commit `8508c14182`で調査・実証（fork側path:line実測、E1〜E3）。判定「前へ進める」（後にRevision 2で訂正）。
