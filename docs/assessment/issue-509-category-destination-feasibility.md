# Issue #509 — カテゴリ別新規アプリ配置のfeasibility調査（Phase A）

> Status: Complete（technical investigation）/ **implementation deferred** / owner product decision **pending**（判定: **`rebase後へ延期`**。Revision 2で「前へ進める」を撤回。owner判断項目はPhase A exitの未完了項目として§7に明示）
> Date: 2026-10-04（Revision 3同日）
> 対象commit: `main` @ `8508c14182412c2a9cf6f6240eadfe30b5322047`（2026-10-04時点。本調査のfork側path:line実測とエミュレータ実証はこのcommitで実施）
> 再確認: #508（PR #515/#517）merge後の`main` @ `aac74df8e2cf3503af2f729934cc77d40f57e51b`で引用したfork側ファイルの`git log`と代表行を実再確認し、差異なし（引用ファイルの最終変更はいずれも2026-10-02以前）
> Environment: reference系emulator `nunu_smoke_api35`（API 35、google_apis、arm64）＋ `nunu_qpr2_api36_1`（API 36.1）。Build: `Lawnchair.15.Dev.(8508c14).github.debug.apk`（対象commitから`assembleLawnWithQuickstepGithubDebug`）。fixture install対象は既存の`tests/benchmark-install-targets/`。追加の検証用probe app（§3.2。`QUERY_ALL_PACKAGES`のみを宣言する最小Activity。調査用の一時artifactでありrepositoryへはcommitしない）
> 出典Issue: [#509](https://github.com/nunu1733/NunuLauncher/issues/509)。延期依存先: #516（Lawnchair 16 rebase Epic。実在番号）
> Review: Phase 1 review round 1（ChatGPT、[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973424478)）の指摘1（高）でRevision 1のplatform因果説明の誤りを指摘され、probe実験とAOSP行レベル再確認（§3.1〜§3.3）により判定を訂正した。round 2（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973633431)）の指摘（高1件・中1件・低1件）をRevision 3で対応（状態分離・証拠強度の整合・obsolete draft明記）。

## 1. 結論（Phase A判定: Revision 2）

**`rebase後へ延期`**。Revision 1では「promise icon経路は現代のAndroidで第三者launcherに到達しない」ことを根拠に「前へ進める」と判定したが、この因果説明は誤りであった（review round 1指摘1。訂正の経緯と証拠は§3）。訂正後の事実:

1. **capture時分類を既存seamで接続する技術は成立する**。変更surfaceはfork-owned moduleのみで、5つのplatform file（`ItemInstallQueue` / `AddWorkspaceItemsTask` / `DirectEditContract` / `ModelWriter` / `PersistedItemArray`）の差分は0行のままである（§2）。
2. **実機の主要install経路（trusted installer＝Play経由の新規install）では、captureがinstall完了前に発生し、その時点で分類signalが読めないと強く示唆される**。Session callbackはQUERY_ALL_PACKAGESを持つ第三者launcherへ届き（§3.1〜§3.3でplatform機構を実証）、launcherコードの構造上、**Play等の主要経路がbadging付きpromise sessionとして動く場合**はpromise icon経路が発火してcapture→UPSTREAM snapshot→上流既定配置となり、完了後の再captureは`alreadyAddedPromiseIcon`で抑止される。ただし**Play実機での発火とbadging成立の直接確認は未実施であり（§8.1）、本項は「AOSP＋launcherコード＋probe実証から強く示唆される」段階の記述である**。したがって実機主要経路でのpromise契約（capture時分類）をPhase Aで成立証明できず、**「Play等の主要経路がbadging付きpromise sessionとして動く場合、capture時分類は成立しない」という条件付きの構造認識にとどまる**（§3.4）。
3. よって本機能がカテゴリ振り分けを成功させる対象は「promise経路を通らないcapture（信頼できないinstaller経由のinstall等）」に限られる（§3.3）。これはIssue本文が要求する「上流が追加を決めた新規アプリを追加操作なしで該当フォルダへ置く」の中核経路を外れるものであり、**成功対象の縮小を利用者にどう説明するかという製品判断がowner判断として未成立である**。Issue本文の停止規則「分類品質/対応identityの製品判断が未成立でも実装を停止する」を適用し、実装へは進めない。
4. 「後へ回す条件」の技術的動機（追加のupstream bridge、model/loader bridge拡張、queue wire format/`DirectEditContract`拡張の必要性）には**該当しない**（bridge増分は0のまま）。延期の理由はplatform由来のcapture時点制約と、それに伴う製品判断の未成立である。したがって再開条件は、Issue本文の定義（「#516完了と受入ADRの成立→新baselineでseam再調査→owner再判断」）に加え、**「ownerがpromise経路を成功対象外とする縮小scopeを受容するか」の判断**が先決である（§9）。rebase完了だけでは制約は解消しない。

判定の記録に合わせ、Revision 1で作成した進行側の成果物（`specs/509-category-new-app-destination/spec.md`、`docs/adr/0017-category-new-app-destination.md`の草案）は本revisionでbranchから取り下げた。設計草案は履歴（commit `59aa164a62`）に残っており、owner判断で縮小scopeを受容して再開する場合の下書きとして参照できる。

## 2. 調査項目1: 既存seamだけの最短経路（変更候補pathとcall flow）— 技術判定としては成立

### 2.1 自動追加のcall flow（現行、`8508c14182`実測）

```text
[install完了] ACTION_SESSION_COMMITTED
  → SessionCommitReceiver.onReceive（MODEL_EXECUTOR）→ processIntent
      - isEnabled（pref_add_icon_to_home + lockHomeScreen）を確認
        （src/com/android/launcher3/SessionCommitReceiver.java:61-64,98-102）
      - INSTALL_REASON_USER以外・alreadyAddedPromiseIcon=trueは何も積まずreturn（:75-87）
      - ItemInstallQueue.INSTANCE.get(context).queueItem(appPackageName, user)（:94-95）
          → resolver.captureDestination(mContext, packageName, userHandle)   …★capture点
            （src/com/android/launcher3/model/ItemInstallQueue.java:244-249）
          → PendingInstallShortcutInfo.mDestinationSnapshotへ載せてqueue XMLへ永続化
            （:96-114のEntryExtension、ATTR_DESTINATION_POLICY。first enqueue wins
             は:140-146のaddToQueue重複排除）
  → flushQueueInBackground（:149-172）
      - attachDestinationRoute（:187-203）: persisted snapshotを読むだけ。
        有効なUPSTREAM snapshotだけstock pathへ、それ以外はpolicy routeへ
  → AddWorkspaceItemsTask（:129-132のroute decode）
      - policy route → ModelWriter.addPendingInstallForDirectEdit（ModelWriter.java:657）
        admission内でstage-2再検証→ID採番→INSERT→post-admission bind
      - stock route → WorkspaceItemSpaceFinder→addItemToDatabase（既定のまま）
```

promise icon経路（`InstallSessionHelper.tryQueuePromiseAppIcon`、`InstallSessionHelper.java:224-242`）も同じ`queueItem(String, UserHandle)` overloadへ流れるため、capture点は同一である。

### 2.2 変更候補path（全てfork側。platform file差分0 — 訂正後も変わらず）

カテゴリモードを実装する場合の変更は全てfork側に限られる:

- `AppDestinationAdapter.kt`（`Resolver.captureDestination`: 121-150）: capture時にS1→S2→S5で分類signalを解決し、mappingでfolderIdへ変換して既存`FOLDER` snapshotを返す。解決不能は既存`UPSTREAM` snapshot。
- `AppDestinationPolicyPrefs`（実体はAppDestinationAdapter.kt:229-247）: policy値とmapping行の永続化。
- `homeedit/ui/AppDestinationPreference.kt`（:109-160）: 選択肢とmapping管理UI。
- `strings.xml` + `values-ja`: 新規文字列。
- ADR-0015 Decision 3/15の拡張はsuccessor ADRで行う（本文上書きなし）。

**差分0のplatform file（既存hook・wire・書込みprotocolをそのまま使える根拠）**: `ItemInstallQueue.java`（capture hook :244-249はresolverに委譲、queue XML attribute :96-114、flush routing :187-203はkind追加に非依存）、`DirectEditContract.java`（4field・2kind :275-320、`isValidUpstreamSnapshot` :329-337、`DestinationResolver` :431-439）、`AddWorkspaceItemsTask.java`（route分岐 :129-132/:245）、`ModelWriter.java`（:657。folderIdの由来に非依存）、`PersistedItemArray.java`（attribute hook）。

### 2.3 分類seamの再利用（organizer run全体は起動しない）

- **S1 override**: `CategoryOverrideStoreModule.source(appContext)`（`rules/CategoryOverrideStore.kt:489-496`。production配線実績は`ProductionOrganizationInputComposer.kt:22`）。
- **S2/S5**: `AndroidClassificationSignalSnapshotSource(appContext).read(requests, policy)`（`integration/AndroidClassificationSignalSnapshotSource.kt:27-64`）。`getApplicationInfo`がnull（未install・不可視）なら`Unreadable`でfail-closed（:38-39）。profile分離はuser引数で既存実装どおり（:34-38）。`ClassificationPolicy`は`BuiltInOrganizerPolicyBundleSource`（`rules/BuiltInOrganizerPolicyBundleSource.kt:19-36`。Android category 0..7→組み込みtaxonomy、`googleCategory=TOOLS`、`systemCategory=OTHER`）。
- **優先順位**: `OrganizationInputComposer.materializeSignals`（`integration/OrganizationInputComposer.kt:527-531`）と同一のS1→S2→S5。
- **profile identity**: `canonicalProfileId(userCache, user)`＝userSerial文字列（`application/adapter/CanonicalProfileId.kt:12-16`、`planning/Identity.kt:23-27`）。resolverは既に同一のuserSerialをcaptureしている（`AppDestinationAdapter.kt:133`）。

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

- `verifySessionInfo`は`sessionInfo.getAppIcon() != null && !TextUtils.isEmpty(sessionInfo.getAppLabel())`を要求する（`InstallSessionHelper.java:255-256`）。
- `pm install-create`/`install-write`で作ったshell streaming sessionにはapp icon/labelが付かない。自分のE1ログ自身がこれを記録している:
  `SessionCommitReceiver: Adding package name to install queue. Package name: app.lawnchair.benchmark.target01, has app icon: false, has app label: false`（API 35、03:39:22.944）
- probe実験でも`onBadgingChanged`は一度も記録されなかった（badging metadataがsessionへ反映されないことの裏付け）。
- よってlauncherはeventを受けても`verifySessionInfo`で静かに落ち、promise enqueueは行われない。**実運用のtrusted installer（Play等）のsessionはbadging（icon/label）を運ぶ**ため（ADR-0015 Context §2がpromise icon機能の前提として記述）、この欠損はshell実験固有である。

### 3.3 capture時点ごとの分類可否（訂正版）

| 経路 | capture時点 | アプリのinstall状態 | S1 | S2/S5 | カテゴリ解決 |
|---|---|---|---|---|---|
| **trusted installer（Play等）＋badging付きsession**（実機の主要経路） | promise icon enqueue（install完了前。§3.1の機構＋badging成立で発火） | 未install | store読めるが新規アプリにoverrideなし | **読めない**（`getApplicationInfo`=null→`Unreadable`） | **不可** |
| 同（完了後） | `alreadyAddedPromiseIcon=true`で`SessionCommitReceiver`がreturn（:74-87） | — | — | — | **再captureは起こらない** |
| 信頼できないinstaller経由のinstall（install reason USER） | `SessionCommitReceiver`（install完了後） | install済み・可視 | 同上 | **読める** | 可（signalがあれば） |
| shell stream（badging無し）・adb（reason不明で自動追加自体が不発。benchmark §7実績） | 完了後（queueに入る場合のみ） | install済み | 同上 | 読める | 可（signalがあれば） |

結論: **AOSP＋launcherコード＋probe実証から、Play等の主要経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆される**（promise captureが分類不能時点で起こり、完了後の再captureは抑止される。Play実機での直接確認は§8.1を再開時の最初のoracleとする）。効くのは非promise captureに限られ、さらにS2のcoverageは「manifestで`appCategory`を宣言するアプリ」、S5は「新規の`com.google.*`アプリ」に限られる（S1は再install時のoverrideのみ）。ADR-0015 Alternatives/#446メモの「分類品質が不十分（8種＋OTHER）」の記録に、capture時点制約が重なる。

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
#     と queueItem のスタック（SessionCommitReceiver.java:95 → ItemInstallQueue.java:250）

# E2: reason 3（USER以外）→ "Removing PromiseIcon ... install reason: 3" でenqueueされない
# E3: installerにlauncher自身を指定（isTrustedPackageのlauncher節）→ 同様にwrite中は無出力

# probe: QUERY_ALL_PACKAGESのみの最小ActivityがregisterPackageInstallerSessionCallbackを
#   登録し、同じsessionでonCreated/onActiveChanged/onProgressChangedを受信（§3.1）。
#   → 配信は起こる。launcher実験の不発はbadging欠損によるverifySessionInfo失敗。
```

install reason定数の実測値: API 35/36とも`--install-reason 4`が`INSTALL_REASON_USER`として受入れられた（E1/E3でcompletion enqueue、E2の3は拒否）。`docs/engineering/editing-burden-benchmark.md` §7の記録と一致。

## 4. 調査項目3: folderとcategoryの対応identity（参考。実装へは進めない）

第一候補はfork-ownedな`CategoryIdentity + profile → persistent folderId`の明示mappingである。#497の指定フォルダと同じid-based classであり、rename不変・削除→`FOLDER_MISSING` typed fallback・同名再作成で復活しない・別profile→`PROFILE_MISMATCH`・Dock→`DOCK_FOLDER`・backup/restore exposure同一・user-defined category削除→capture時解決失敗（fail-closed）の性質は、#497の受入済み意味論と同一である。folderタイトル一致・子の多数決・暗黙のfolder成長・organizer provenance転用は、ADR-0015 Decision 13(b)とIssue本文の禁止どおり採用しない。この節はowner判断で縮小scopeを受容して再開する場合の設計基盤として残す。

## 5. 調査項目4: 再flushの決定性（技術的には継承される）

分類・対応mapping・カタログはenqueue時（captureDestination内）だけ読み、capture後は4field snapshotが不変である。first enqueue wins（`ItemInstallQueue.java:140-146`）、flush読み出しのみ、stage-2による固定folderIdの再検証（`AppDestinationAdapter.kt:61-102`、`AppDestinationPlanner.kt:83-159`）は#497構造のまま保たれる。ただし主要経路ではcapture自体が分類不能時点で起こるため（§3.3）、この決定性契約はfallback配置の決定性として機能する。

## 6. 調査項目5: 設定とfailure（参考。実装へは進めない）

opt-in UX（既存ポリシー行の拡張）、曖昧・未設定・分類不可の上流既定fallback、対応先folder消失等の既存typed fallback、FileLogへのtyped記録（package名なし、wire拡張不要、通知なし）はいずれも既存seamの延長で設計可能である（§2.2）。network・新権限は不要である。

## 7. Phase A exit条件の照合（Revision 3）

- [x] 調査成果物に確認日・対象commit・根拠path/行・再現手順と結果、変更候補path、未確認範囲を記録した（本書。`docs/assessment/issue-509-category-destination-feasibility.md`）。
- [x] **技術的境界の確定**: profile内classification、明示対応identity、enqueue固定/reflush/stage-2の境界を確定した（§2〜§6）。ADR-0015を変える部分はsuccessor ADRを要するが、実装停止のため起草しない（Revision 1草案は履歴`59aa164a62`に残す。obsolete draft。§9）。
- [ ] **promise不足時の扱いを含む成功対象scopeについてowner判断が確定した** → **未完了**。promise経路（主要install経路と強く示唆される。§3.3/§8.1）のcapture時分類不成立により、成功対象が非promise captureへ縮小することの製品受容がowner判断として未成立である。これが延期の理由そのものである（§1/§9）。再開時はこの項目から始める。
- [x] **前に進める条件は満たさない**: fork-owned moduleのみの変更・既存hook・既存4field snapshot・既存書込み/queue protocolの使用は証明できたが、「必要なprofile/**promise**/fallback契約が成立する証拠」をPhase A内で示せなかった（promise契約の成立を裏付ける実機証跡がなく、製品scope判断も未成立）。
- [x] **後へ回す条件の技術的動機には非該当**だが、Issue本文の停止規則（製品判断未成立でも実装を停止）により`rebase後へ延期`として記録する（§9）。
- [x] 判定を本Issueに`rebase後へ延期`として記録した（worker comment。Phase 1 review round 1/2の指摘対応と合わせる）。

## 8. 未確認範囲

1. **実機GMS端末＋Play経由installでのpromise capture発火の直接確認**: platform機構はprobe実験（配信実証）＋badging要件のコード根拠＋ADR-0015 Contextのpromise icon前提から強く推定するが、実機でPlay install→promise icon表示→capture時点の分類不可を直接記録していない。rebase後の再調査（owner再判断時）の最初の確認項目とする。
2. **shell sessionのbadging欠損の挙動差**: `pm install-write`のストリーミングでicon/labelが付かないのはエミュレータ2台（API 35/36）で確認。Play等の実installer sessionでは付くことの直接確認は未実施（同上）。
3. **非promise captureの実際の頻度**（信頼できないinstaller経由installの割合、`INSTALL_REASON_USER`成立率）: 測定していない。縮小scopeの価値評価に必要なdataであり、owner判断の材料とする。
4. work profileでのcapture経路: 構造分析のみ（各profileのlauncher processが自profileのbroadcast/callbackを受け、promise経路の制約は同一）。

## 9. 延期の記録と再開条件

- 本Issueをopenのまま`phase: later`へ変更する。依存linkは#516（Lawnchair 16 rebase Epic、実在番号）。
- 延期理由は「追加bridgeの必要性」ではなく「**AOSP＋launcherコード＋probe実証から、Play等の主要install経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆されること**（Play実機は未確認。§3.3/§8.1）」と、それに伴う「**成功対象の縮小（非promise captureのみ＋appCategory等のsignal coverage制約）の製品受容がowner判断として未成立**」である。bridge増分0の技術成果（§2）はrevision 1から変更なく成立している。
- 再開条件（Issue本文の定義＋訂正後の実態を明記）: ①ownerが「promise経路を成功対象外とする縮小scope」を受容するかを判断する（これが先決。受容しない場合、本機能は capture時分類の限界により実装困難であり、FR-009等の別の解決（install後の明示的移動支援）へ分流する）。②受容する場合、#516完了と受入rebase ADRの成立後に新baselineでseam再調査（§3のplatform事実の実機確認を含む）→owner再判断。③rebase完了だけでは制約は解消しない。
- 本調査の他の成果物: Revision 1で起草したspec/ADR草案（履歴`59aa164a62`）は**withdrawn/obsolete draftであり、そのまま再利用しない**。この草案はreview round 1で指摘された論点（ADR-0015 Decision 11との置換関係の明文化、promise/work profile/reflush/category identityのoracle不足）が未修正のままである。再開時は、縮小scopeの受容判断を踏まえたうえで、新baselineでD11 relationの明文化と不足oracleの再設計を行ったうえで起草し直す。

## 10. External reference scan（研究記録）

- AOSP PackageInstallerService（session callbackの配信filter。判定の根幹。android15-release: shouldFilterSession `:1303-1308`/`:1849-1857`、dispatch `:2037-2052`。android16-release: `:1892-1897`）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/PackageInstallerService.java （確認日 2026-10-04）
- AOSP LauncherAppsService（`registerPackageInstallerCallback`。権限要求なし。android15-release `:356-368`）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/LauncherAppsService.java （確認日 2026-10-04）
- AOSP ComputerEngine（`canQueryPackage`の"new installing case"。android15-release `:5436-5485`（installing case `:5453-5458`）、android16-release `:5471-`（`:5499-`）、main `:5436-`（`:5461-`））: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/ComputerEngine.java （確認日 2026-10-04。review round 1指摘1で指摘された分岐を行レベルで確認し、probe実験で動作を実証）
- AOSP AppsFilterBase（`requestsQueryAllPackages` fast path。android15-release `:683-`（`:694`）、main `:683-695`）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/AppsFilterBase.java （確認日 2026-10-04）
- Android SDK `LauncherApps.getApplicationInfo(String, int, UserHandle)`: https://developer.android.com/reference/android/content/pm/LauncherApps#getApplicationInfo(java.lang.String,%20int,%20android.os.UserHandle) （確認日 2026-10-04。未install・不可視でnull。`AndroidClassificationSignalSnapshotSource`のfail-closedと一致）
- Smart Launcher Smart Folders: https://docs.smartlauncher.net/products/faq/changelog/5.4 （確認日 2026-10-02。自動分類による全自動追加。本機能は明示mapping＋fallbackだが、capture時点制約により採用判断に至らず）
- Nova Launcher等の第三者launcherのpromise icon表示可否についてのcommunity記録は出典を確認できなかったため根拠に使わず、probe実験とAOSPソースを正とした。

## Change history

- 2026-10-04: Revision 4 — Phase 1 review round 3（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973674659)）の残存指摘（中1件。高・低は解消確認）に対応。§1/§3.3/§3.4/§9に残っていた無条件の断定を、round 2中指摘と同一の条件付き表現（「Play等の主要経路がbadging付きpromise sessionとして動く場合はcapture時分類が成立しないと強く示唆される。Play実機は未確認」）へ統一した。
- 2026-10-04: Revision 3 — Phase 1 review round 2（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973633431)）の指摘（高1件・中1件・低1件）に対応。高: §7のowner判断項目を未完了として分離し、状態を「technical investigation complete / implementation deferred / owner product decision pending」へ明示（記録矛盾の解消）。中: §1/§3.3/§3.4/§7のPlay経路に関する断定を証拠強度へ合わせ、「AOSP＋launcherコード＋probeから強く示唆される（Play実機は未確認）」へ修正し、実機直接確認を再開時の最初のoracle（§8.1）として固定。低: 履歴`59aa164a62`の草案をobsolete draftとして明記し、再開時の再起草要件（D11 relation明文化・oracle再設計）を§9へ追記。
- 2026-10-04: Revision 2 — Phase 1 review round 1（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-5973424478)）指摘1（高）に対応。Revision 1の「session callbackは第三者launcherに届かない」因果説明を撤回し、AOSP行レベル再確認（installing case＋QUERY_ALL_PACKAGES fast path。android15/16/main）とprobe実験で配信を実証。launcher実験の不発の真因をshell sessionのbadging欠損（`verifySessionInfo`のicon/label要件。E1ログ`has app icon: false`が証拠）へ訂正。訂正後は実機主要経路（Play）でpromise captureが発火する構造のため、判定を`前へ進める`から`rebase後へ延期`へ訂正。spec/ADR草案を取り下げ（履歴`59aa164a62`に残存）。
- 2026-10-04: Revision 1 — 初版。対象commit `8508c14182`で調査・実証（fork側path:line実測、E1〜E3）。判定「前へ進める」（後にRevision 2で訂正）。
