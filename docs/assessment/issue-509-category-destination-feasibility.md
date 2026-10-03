# Issue #509 — カテゴリ別新規アプリ配置のfeasibility調査（Phase A）

> Status: Complete（判定: **前へ進める** — 既存destination-policy seam上・upstream patch増分0で実装可能。ただしspec受入前に解決すべきowner判断事項を§9に記録）
> Date: 2026-10-04
> 対象commit: `main` @ `8508c14182412c2a9cf6f6240eadfe30b5322047`（2026-10-04時点。本調査の全てのpath:line実測とエミュレータ実証はこのcommitで実施した）
> 再確認: #508（PR #515/#517）merge後の`main` @ `aac74df8e2cf3503af2f729934cc77d40f57e51b`で引用した全ファイルの`git log`と代表行を実再確認し、差異なし（引用ファイルの最終変更はいずれも2026-10-02以前）。
> Environment: reference系emulator `nunu_smoke_api35`（API 35、google_apis、arm64）＋ `nunu_qpr2_api36_1`（API 36.1）。Build: `Lawnchair.15.Dev.(8508c14).github.debug.apk`（対象commitから`assembleLawnWithQuickstepGithubDebug`）。fixture install対象は既存の`tests/benchmark-install-targets/`（Issue #441の固定対象APK）
> 出典Issue: [#509](https://github.com/nunu1733/NunuLauncher/issues/509)。判定依存先: #516（Lawnchair 16 rebase Epic。本判定は「前へ進める」のため延期しない）

## 1. 結論（Phase A判定）

**`前へ進める`**。Issue本文の「前に進める条件」を満たす証拠を得た:

1. 変更surfaceはfork-owned module（`app.lawnchair.homeedit`＋設定UI＋resources）だけである。capture時の分類とカテゴリ→folderId解決を既存capture hook（`AppDestinationBridge`のresolver）の中で完結させ、既存の`FOLDER` snapshot（4field wire、kind 2種）として永続化すれば、以後は#497のstage-2検証・admission内write・typed fallback・UI bindをそのまま使える。§2のとおり、`ItemInstallQueue` / `AddWorkspaceItemsTask` / `DirectEditContract` / `ModelWriter` / `PersistedItemArray`の5つのplatform fileの差分は**0行**である。
2. capture時点の分類は、現行platform上の全ての自動追加経路で**読み可能な時点**に行われる。調査で新たに確認したplatform事実（§3）により、promise icon経路（install完了前のenqueue）は現代のAndroid（API 35/36で実証、AOSPソースで機構確認）ではサードパーティlauncherに到達せず、自動追加は常に`SessionCommitReceiver`（install完了後）でenqueueされる。したがってcapture時点で対象packageは既にinstall済み・可視であり、既存のS2/S5 platform evidence（`LauncherApps.getApplicationInfo`）とS1 overrideが読める。
3. promise icon経路が将来の環境変化等で到達した場合でも、captureは分類不可をtypedに既定選択へ落とし（UPSTREAM snapshotをcapture）、#497の再flush決定性契約（first enqueue wins、flush読み出しのみ、stage-2再検証）がそのまま成立する。§3.4のとおり、この経路は契約上の予防的fallbackであり、通常経路の品質主張に使わない。
4. カテゴリと既存フォルダの対応identityは、#497の指定フォルダと同じid-based class（`CategoryIdentity + profile → favorites行id`）で保持でき、rename・削除・同名再作成・profile・backup/restoreの扱いが#497の受入済み意味論と同一である（§4）。

「後へ回す条件」（追加のupstream bridge、model/loader bridge拡張、queue wire format/`DirectEditContract`拡張の必要性）には**該当しない**。したがって#516（rebase Epic）への延期はしない。

一方で、分類品質の Coverage は本質的に狭い（§3.3。S2はmanifestで`appCategory`を宣言するアプリのみ、S5は`com.google.*`新規アプリのみ、S1は新規アプリに存在しない）。これは機能のOutcome「対応や分類が使えなければ上流既定へ落とす」と矛盾しないが、**機能価値の範囲（どのアプリが振り分け対象になるか）の製品受容、対応catalogの範囲、設定UXの具体形は、spec（Phase 1 Re-Entry）の受入時にownerが確定すべき判断事項である**（§9）。

## 2. 調査項目1: 既存seamだけの最短経路（変更候補pathとcall flow）

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

promise icon経路（`InstallSessionHelper.tryQueuePromiseAppIcon`、`InstallSessionHelper.java:224-242`）も同じ`queueItem(String, UserHandle)` overloadへ流れるため、capture点は同一である（§3で到達条件を論じる）。

### 2.2 変更候補path（全てfork側。platform file差分0）

| File | 変更種別 | 内容 |
|---|---|---|
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt`（`Resolver.captureDestination`: 121-150） | **変更** | カテゴリモード選択時、capture時にS1→S2→S5でカテゴリsignalを解決し、対応mappingでfolderIdへ変換して既存`FOLDER` snapshotを返す。解決不能（分類不可・対応未設定・対応先カテゴリ消失）は既存`UPSTREAM` snapshotを返す（分類fallbackのtyped記録はFileLog＋既存one-shot通知stateで可能。wire拡張不要） |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationPolicyPrefs.kt`（実体はAppDestinationAdapter.kt:229-247） | **変更** | policy値にカテゴリモードを追加し、mapping行（`CategoryIdentity.canonicalValue + userSerial → folderId`）の永続化・読み出しを追加 |
| `lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt`（:109-160の3択dialog） | **変更** | 選択肢にカテゴリモードを追加し、mapping管理（カテゴリ→フォルダ選択）のUIを追加 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | **追加** | カテゴリモード・mapping UI・分類fallback通知の文字列（`destination_policy_*`契約の延長。`AppDestinationSettingsTextTest`と同じsource-contract testの対象） |
| `docs/adr/0017-*`（新設。仮番号は起票時に確定） | **新設** | ADR-0015 Decision 3（選択肢3つ）/Decision 15（3択設定）の拡張。successor ADRとして置換範囲を明示し、Decision 1/4/5/6/7/8/9/10/11/14は不変であることを示す |
| `specs/509-category-new-app-destination/spec.md` | **新設** | Phase B実装spec（Issue本文どおり） |

**差分0のplatform file（既存hook・wire・書込みprotocolをそのまま使う根拠）**:

- `src/com/android/launcher3/model/ItemInstallQueue.java`: capture hook（:244-249）はresolverに委譲しており、resolverの内部解決変更はこのfileに触れない。queue XML attributeのEntryExtension（:96-114）は任意stringを運ぶのみ。flush routing（:187-203）はsnapshot文字列をdecodeするだけで、kind追加に非依存。
- `src/com/android/launcher3/model/DirectEditContract.java`: `serializeDestinationSnapshot`/`parseDestinationSnapshot`（:287-320）は`upstream`/`folder`の2kind・4field（`kind|folderId|userSerial|packageName`）で固定。**新しいCATEGORY kind/属性は追加しない**（Issue本文の初期案禁止どおり、分類→folderId解決をfork側captureで終え既存`FOLDER`としてserializeする）。`isValidUpstreamSnapshot`（:329-337）もそのまま使える。
- `src/com/android/launcher3/model/AddWorkspaceItemsTask.java`: policy routeの1分岐（:129-132、:245）はsnapshotが`FOLDER`を運ぶかどうかに依存しない（DestinationRouteで運搬されるため）。
- `src/com/android/launcher3/model/ModelWriter.java`: `addPendingInstallForDirectEdit`（:657）は固定folderIdのadmission内検証・INSERTで、folderIdの由来（設定指定か分類解決か）に非依存。
- `src/com/android/launcher3/util/PersistedItemArray.java`: attribute hook（#497で追加済み）はそのまま。

### 2.3 分類seamの再利用（organizer run全体は起動しない）

既存の分類sourceは、organizer run/snapshot/recoveryと独立した単体adapterとして存在し、capture点から直接再利用できる（AGENTS.md設計規約「並行する分類機構を作らない」に従う）:

- **S1 override**: `CategoryOverrideStoreModule.source(appContext)`（`lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideStore.kt:489-496`。production配線実績は`ProductionOrganizationInputComposer.kt:22`）。`CategoryOverrideSnapshot.assignments[CategoryOverrideKey(PackageName, ProfileId)]`（`rules/CategoryOverrideSnapshot.kt:10-31`）でpackage+profile単位のoverrideを読める。
- **S2/S5 platform evidence**: `AndroidClassificationSignalSnapshotSource(appContext).read(requests, policy)`（`lawnchair/src/app/lawnchair/organizer/integration/AndroidClassificationSignalSnapshotSource.kt:27-64`）。単一packageの`ClassificationEvidenceRequest`を渡せばよく、`getApplicationInfo`がnull（未install・不可視）のときは`PlatformEvidenceReadResult.Unreadable`でfail-closedする（:38-39）。profileの分離は既存実装どおり`LauncherApps.getApplicationInfo(pkg, 0, user)`のuser引数で保たれる（:34-38。#129の実績）。`ClassificationPolicy`は`BuiltInOrganizerPolicyBundleSource.readActive()`から取得する（`rules/BuiltInOrganizerPolicyBundleSource.kt:18-36`。`androidCategoryMapping`はAndroid category 0..7→組み込みtaxonomy、`googleCategory=TOOLS`、`systemCategory=OTHER`）。
- **優先順位**: `OrganizationInputComposer.materializeSignals`（`integration/OrganizationInputComposer.kt:527-531`）の既存優先順位 S1 override → S2 Android category → S5 system/Google をcaptureでも踏襲する。
- **profile identity**: `canonicalProfileId(userCache, user)`＝userSerial文字列（`application/adapter/CanonicalProfileId.kt:12-16`、`planning/Identity.kt:23-27`）。resolverは既に同一のuserSerialをsnapshotへ書いており（`AppDestinationAdapter.kt:133`）、S1/mappingのkeyと一致する。

## 3. 調査項目2: 分類の時点と品質

### 3.1 自動追加の経路は「install完了後」が現行platformの全て（新規所見）

Issue本文が想定した「promise iconをenqueueする時点」は、**現代のAndroidでは第三者launcherへ到達しない**ことを確認した。これは本調査の主要な新規所見であり、capture時点の分類可否を根本から決める。

**コード上の到達条件**（`8508c14182`実測）:

- promise経路の唯一のtriggerは`InstallSessionTracker`の`onCreated`/`onBadgingChanged`（`pm/InstallSessionTracker.java:82,145`→`tryQueuePromiseAppIcon`）。登録は`LauncherApps.registerPackageInstallerSessionCallback`（同:174-181。Q以降）である。
- `verifySessionInfo`は`!PackageManagerHelper.isAppInstalled(...)`を要求する（`InstallSessionHelper.java:257-258`）。つまりpromise enqueueは定義上「未install時点」である。
- `isTrustedPackage`は`DEBUG(false)`／launcher自身のpackage名／installerの`FLAG_SYSTEM`のいずれか（`InstallSessionHelper.java:69,174-184`）。

**platform側の配信条件**（AOSP `aosp-mirror/platform_frameworks_base` main、確認日2026-10-04）:

- `PackageInstallerService.Callbacks.handleMessage`は各eventに`shouldFilterSession(snapshot, callingUid, sessionId)`を適用する:
  `return uid != session.getInstallerUid() && !snapshot.canQueryPackage(uid, session.getPackageName());`
  （https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageInstallerService.java）
- streaming中のsession target packageは未installのためpackage managerのpackage集合に存在せず、`canQueryPackage`が真にならない。session作成者（installer）でない第三者launcherにはeventが届かない。`LauncherAppsService.registerPackageInstallerCallback`はこの`PackageInstallerService.registerCallback`へprofile filterのみ付して登録する（同`LauncherAppsService.java`）。

**エミュレータ実証**（2026-10-04。手順と出力は§3.5）:

| 実験 | 条件 | 結果 |
|---|---|---|
| E1（API 35、`nunu_smoke_api35`） | `-i com.android.vending`（このimageで`flags=[ SYSTEM ... ]`を確認）＋`--install-reason 4`（USER）でsession作成・write中 | launcher logcatに`ItemInstallQueue`/`SessionCommitReceiver`/`InstallSessionHelper`の出力**ゼロ**。`pm path`失敗（未install）。→ promise enqueue不発 |
| E1-commit | 同sessionを`pm install-commit` | packageがinstallされ、直後に`SessionCommitReceiver: Adding package name to install queue`＋`ItemInstallQueue: queueItem at SessionCommitReceiver.java:95`のスタックが出力（03:39:22.971）。→ **captureはinstall完了後に発生** |
| E2（API 35） | `--install-reason 3`（USER以外。commitのみ） | `SessionCommitReceiver: Removing PromiseIcon ... install reason: 3`でenqueueされない（:76のUSER gateの実機確認。benchmark §7 change historyの記録と一致） |
| E3（API 35） | `-i app.lawnchair.debug`（`isTrustedPackage`のlauncher自身節でtrusted）＋reason 4、write中 | 出力ゼロ・未install。→ installer信頼性ではなくplatform配信条件で落ちていることを切り分け |
| 参考（API 36、`nunu_qpr2_api36_1`） | 同型のsession作成・write | 同様にpromise不発。commit後のcompletion enqueueを確認 |

API 36側のエミュレータは並行作業（#508）で使用されたため、正式transcriptはAPI 35側を正とする。

**含意**:

1. 現行platformで自動追加が起こる経路は`SessionCommitReceiver`（install完了後）に単一化される。したがって**capture時点で対象packageはinstall済み・可視**（QUERY_ALL_PACKAGESは`AndroidManifest-common.xml:43`で宣言済み）であり、既存S2/S5 evidenceの`getApplicationInfo`は成功する。分類→folderId解決をcapture時に行う構成は、主要経路で成立する。
2. promise icon（`FLAG_AUTOINSTALL_ICON`の空きセル配置）自体が第三者launcherでは発生しないため、ADR-0015 Decision 11（promise段階から同じ配置先）は「コード上の契約は維持、runtime到達は環境依存」という位置づけになる。#497の実装（capture hook・wire・stage-2）はpromise経路でも正しく動く予防的契約としてそのまま維持する。
3. 「install完了後にもう一度振り分ける」を採用しない（Issue本文どおり）方針は変わらない。完了後captureの単一経路化により、二段階配置の問題は現行platform上では構造的に発生しない。

### 3.2 capture時点のS1/S2/S5可用性の纏め

| Signal | install完了後capture（現行の全自動追加） | promise capture（到達時は予防的契約） |
|---|---|---|
| S1 override | 読める（store参照）。ただし新規packageには行が存在しない → signal無し | 同左 |
| S2 Android category | 読める（`getApplicationInfo`成功。`info.category`が0..7のときのみmapping一致） | **読めない**（未install→`getApplicationInfo`=null→`Unreadable` fail-closed） |
| S5 system/Google | 読める（新規ユーザーinstallは`FLAG_SYSTEM`なし。`com.google.*`のみ一致） | 同上、読めない |

S2/S5が未取得のときのcapture結果は既存`UPSTREAM` snapshot（＝上流既定への静かな配置）であり、#497のsnapshot契約（欠損・破損とは異なり、**正当な「upstream選択」としてcapture**する。`AppDestinationAdapter.kt:133-149`の現行upstream選択と同じ形）で処理する。分類fallbackの記録が必要な場合はFileLogへのtyped記録＋既存one-shot通知state（`AppDestinationNotice`）で足り、queue wire拡張は不要（調査項目5への回答）。

### 3.3 分類品質（Coverage）の限度

- S2は`ApplicationInfo.category`（manifest `android:appCategory`）が0..7（GAME..PRODUCTIVITY）のときのみsignalになる。`CATEGORY_UNDEFINED`の一般アプリにはsignalがない。ADR-0015 Alternativesと#446メモが「Android category 8種＋OTHERのみで分類品質が不十分」と記録した同じ制約である。
- S5は`com.google.*`新規アプリ→TOOLS、システムアプリ→OTHER。ユーザーが新規installする通常アプリは`FLAG_SYSTEM`を持たないため、S5が効くのはGoogle系アプリに限られる。
- S1はorganizerの分類materials（配置済みアプリへのoverride）であり、新規install時点では存在しない。
- よって分類が成功する対象は「`appCategory`を宣言してinstallされるアプリ」と「新規の`com.google.*`アプリ」に限られる。**これは機能価値の範囲の問題であり、誤配置の問題ではない**（解決不能はすべて上流既定へ落ちるため）。spec受入時にこの範囲を明示してowner判断を得る（§9）。

### 3.4 promise段階の失敗をfallbackとする場合の説明（Issue調査項目2への回答）

- 成功対象: 「install完了後captureで分類が解決したアプリ」。promise段階（到達時のみ）は分類不可のため既定配置となり、これは**fallbackのtyped記録の対象**として説明する。通常追加だけの成功で一般的なpromise対応を主張しない（Issue本文どおり）。現行platformではpromise段階自体が到達しないため（§3.1）、この契約は予防的性質を持つ。
- 利用者への説明: 設定行のカテゴリモード説明に「分類できないアプリは上流既定へ置かれる」ことを明記し、分類fallbackのone-shot通知（既存`AppDestinationNotice`の契約延長）で一度だけ知らせる。文言の具体化はspecで確定する。

### 3.5 再現手順（E1〜E3の正確なcommand。API 35 `nunu_smoke_api35`、emulator-5574）

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
#   → 出力なし（promise enqueue不発）
adb shell pm install-commit $SID
sleep 3
adb shell pm path app.lawnchair.benchmark.target01        # → /data/app/...base.apk（install済み）
adb logcat -d | grep -E "SessionCommitReceiver|ItemInstallQueue" | grep -v "  at "
#   → "Adding package name to install queue..." と queueItem のスタック
#     （SessionCommitReceiver.java:95 → ItemInstallQueue.java:250）

# E2: reason 3（USER以外）
SID=$(adb shell pm install-create -i com.android.vending --install-reason 3 -r ... )
#   commit後: "Removing PromiseIcon ... install reason: 3, alreadyAddedPromiseIcon: false"

# E3: installerにlauncher自身を指定（isTrustedPackageのlauncher節）
SID=$(adb shell pm install-create -i app.lawnchair.debug --install-reason 4 -r ... )
#   write中: 出力なし・未install（E1と同様にpromise不発）
```

install reason定数の実測値: API 35/36とも`--install-reason 4`が`INSTALL_REASON_USER`として受入れられた（E1/E3でenqueue、E2の3は拒否）。`docs/engineering/editing-burden-benchmark.md` §7の`--install-reason 4`＝USERの記録と一致する。

## 4. 調査項目3: folderとcategoryの対応identity

第一候補（Issue本文どおり）: **利用者が明示設定する`CategoryIdentity + profile → persistent folderId`のfork-owned対応**（新規pref。既存`newAppDestination`の`folder:<id>`値の一般化ではない——既存値の形式は変えず、別prefにmapping行を持つ）。

確認した性質（#497の指定フォルダと同一class）:

| 事象 | 挙動 | 根拠 |
|---|---|---|
| folder rename | 対応はfolderIdで不変 | id保持（ADR-0015 Decision 5と同型） |
| folder削除 | stage-2が`FOLDER_MISSING`でtyped fallback（上流既定へ）。対応行は残るが無効 | `AppDestinationPlanner.plan`（`AppDestinationPlanner.kt:127-129`） |
| 同名再作成 | 復活しない（id基準） | 同上 |
| 別profile folder | `PROFILE_MISMATCH`でtyped fallback | `AppDestinationPlanner.kt:131-135`（profile比較はuserSerial） |
| Dock移動 | `DOCK_FOLDER` | `AppDestinationPlanner.kt:137-141` |
| backup/restore | favorites行idの保存・復元に依存する exposure は#497の指定フォルダと同じ受入済みclass。復元後にidが変わればfallback（上流既定）へ落ち、layout損失はない | #497 AC-3の受入済み挙動と同一 |
| カテゴリ削除（user-defined） | 対応行が指すidentityがcatalogから消える → capture時の解決が失敗 → UPSTREAM snapshot（上流既定）。誤配送は起こらない | 解決をcapture時のみに限定する設計（§5） |
| カテゴリrename | `CategoryIdentity`はid基準でrename不変（`CategoryIdentity.kt:35-66`） | #336のidentity契約 |

不採用とする案（Issue本文が自動採用を禁止しているもの。比較記録として残す）:

- **folderタイトル一致**: Deckの失敗の再現（ADR-0015 Decision 13( b)）。採用しない。
- **子の多数決・分類結果からの暗黇のfolder成長**: 意図しないfolder成長を生む。採用しない。
- **既存provenance（organizerのplan provenance/naming semantic）の転用**: Organizerの生成planにnaming semanticがあることは任意のfolder行に永続的なcategory対応がある証明にならない（Issue本文の照合どおり）。採用しない。

対応catalogの範囲（spec受入時のowner判断事項）: S1は新規アプリに存在しないため、capture時に実際に到達するカテゴリはS2（8種）とS5（`com.google.*`→TOOLS）に限られる。mapping UIが全built-in taxonomy＋user-definedを露出すると「到達不能な対応」を作るため、**到達可能なカテゴリへmapping対象を限定する**ことをspecで確定すべきである（初期案: S2の8種＋TOOLS。user-definedはS1依存のため対象外）。

## 5. 調査項目4: 再flushの決定性

分類・対応mapping・カタログは**enqueue時（captureDestination内）だけ**読む。capture後は4fieldの`FOLDER` snapshotが永続化され、flush・process restart・再flushはpersist済みfolderIdを読むだけである。したがって:

- capture後にoverride/catalog/設定/mappingが変わっても、同じinstallの永続化されたfolderIdは置き換わらない（first enqueue wins。`ItemInstallQueue.java:140-146`の重複排除と`mStorage.write`のスキップ）。
- stage-2は固定folderIdの現在の存在/profile/Dock/制約だけを再検証し、**別のカテゴリfolderへの再選択は行わない**（`AppDestinationStage2Validator`はsnapshotを読むだけでcurrent policyを再読しない。`AppDestinationAdapter.kt:61-102`）。
- snapshot欠損・破損・identity不一致の扱いは#497の契約そのまま（`UpstreamDefault(SNAPSHOT_INVALID)` / `Reject(SNAPSHOT_INVALID)`。`AppDestinationPlanner.kt:83-105`）。

この性質は#497の構造から外に新しく作るものがなく、カテゴリ化はcapture時の解決方法が増えるだけであるため、契約の維持は構造的に保証される。

## 6. 調査項目5: 設定とfailure

- **opt-in UX**: 既存のポリシー行（`AppDestinationPreference.kt:109-160`）の選択肢にカテゴリモードを追加する形が最小である（ADR-0015 Decision 15の3択を4択へ拡張するためsuccessor ADRを要求する。§9）。「追加しない」⇔`pref_add_icon_to_home` OFFの整合、ホームロック中の無効化は既存実装がそのまま保持する。
- **排他・整合**: カテゴリモードと固定フォルダモードは同一のpolicy選択の排他選択肢である（同時に効かせない）。mappingはカテゴリモード選択中のみ意味を持つ。
- **曖昧・未設定・分類不可**: すべてcapture時にUPSTREAM snapshotへ落ちる（上流既定）。エラーではない。
- **対応先folderの消失・別profile・Dock化**: stage-2の既存typed fallback（`FOLDER_MISSING`/`PROFILE_MISMATCH`/`DOCK_FOLDER`）が処理する。通知は既存one-shot通知state経由。
- **分類fallbackの記録**: FileLogのtyped記録＋one-shot通知で足り、**queue wireの拡張は不要**（調査項目5の問いへの回答）。package/component名は出力しない（organizer-diagnostics §7 Never準拠。既存`AppDestinationBridge`の実装慣行どおり）。
- **権限・network**: 追加なし。分類readは既存の`LauncherApps`＋app-private storeであり、新権限・外部通信は発生しない。

## 7. Phase A exit条件の照合

- [x] 調査成果物に確認日・対象commit・根拠path/行・再現手順と結果、変更候補path、未確認範囲を記録した（本書。成果物path: `docs/assessment/issue-509-category-destination-feasibility.md`）。
- [x] profile内classification、明示対応identity、promise不足時の扱い、enqueue固定/reflush/stage-2の境界を調査として確定し、owner判断事項を§9に列挙した。ADR-0015を変える部分（Decision 3/15の選択肢拡張）はsuccessor ADRで置換範囲を示す方針である（元ADRは上書きしない）。
- [x] **前に進める条件**: fork-owned moduleの変更だけで、既存hook・既存4field snapshot・既存書込み/queue protocolをそのまま使い、必要なprofile/promise/fallback契約が成立する証拠を示した（§2〜§6）。新しいschema/migrationや別の適用経路を前提にしない。
- [x] **後へ回す条件**: 該当しない（追加のupstream bridge、model/loader bridge拡張、queue wire format/`DirectEditContract`拡張は不要と判明した）。
- [ ] 判定を本Issueに`前へ進める`として記録する（本調査のPR後にworkerが記録する）。

## 8. 未確認範囲

1. **実機GMS端末＋リリース配布形でのsession event不達の直接確認**: エミュレータ（google_apis、API 35/36）とAOSPソースでの確認を代用した。リリースlauncherも本調査と同じdata app構成（`READ_INSTALL_SESSIONS`未宣言・`QUERY_ALL_PACKAGES`宣言）であり差はない判断だが、Phase Bのowner実機確認（install→振り分けの実測）で二重化する。
2. **`canQueryPackage`の未install packageに対するframework実装の行レベル確認**: 挙動はエミュレータで実証した（E1/E3）。AOSP引用は`shouldFilterSession`の式までとした。
3. **実ワールドの`appCategory`宣言率**: 測定していない。ADR-0015/#446メモの「8種のみ・品質不十分」の記録と、本調査のsignal構造の分析（§3.3）を根拠に、coverageの製品受容をowner判断へ出す。
4. **work profileでのcapture分類**: 構造分析のみ（各profileのlauncher processが自profileのbroadcastを受けるため同一経路。分類readも自profileのuserで行われる）。実証はspec 509の検証（AC）で行う。

## 9. 正本への影響とspec受入前に確定すべきowner判断事項

**正本への影響**:

- ADR-0015: Decision 3（選択肢3つ）/Decision 15（3択設定）の拡張をsuccessor ADRで行う（Decision 1/4/5/6/7/8/9/10/11/14は不変）。Decision 11（promise iconの段階から同じ配置先）は契約として維持するが、§3.1のplatform事実（promise経路のruntime到達性）をsuccessor ADRのContextへ記録する。ADR本文のin-place書き換えは行わない（`docs/adr/README.md`規約）。
- spec 497: 実装は正しく、変更不要。promise icon経路のScenario（「promise iconの段階から同じ配置先」）は予防的契約としての意味が明確になるのみである。
- `CONTEXT.md`: Phase B実装時に「分類フォルダ」語（指定フォルダのAvoid欄で「将来機能」とされていた）を更新する。

**spec受入前にownerが確定すべき判断**:

1. **Coverageの製品受容**: 分類成功対象が「`appCategory`宣言アプリ＋新規`com.google.*`アプリ」に限ることを機能価値として受容するか（§3.3）。
2. **対応catalogの範囲**: mapping対象をS2到達可能カテゴリ（＋TOOLS）へ限定する初期案の承認（§4）。
3. **設定UXの具体形**: カテゴリモードの選択面とmapping管理UIの形（4択拡張か、別面か）。
4. **promise段階fallbackの説明**: §3.4の説明方針（予防的契約・typed記録・一度だけの通知）の承認。

## 10. External reference scan（研究記録。workflow「External reference scan」の形式）

- AOSP PackageInstallerService（session callbackの可視性filter。本判定の根幹）: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageInstallerService.java （確認日 2026-10-04。`shouldFilterSession`＝`uid != installerUid && !canQueryPackage(uid, targetPackage)`を採用事実として記録。採用patternというよりplatform制約の確認）
- Android SDK `LauncherApps.getApplicationInfo(String, int, UserHandle)`: https://developer.android.com/reference/android/content/pm/LauncherApps#getApplicationInfo(java.lang.String,%20int,%20android.os.UserHandle) （確認日 2026-10-04。未install・不可視でnullを返す契約。`AndroidClassificationSignalSnapshotSource`のfail-closedと一致）
- Smart Launcher Smart Folders（カテゴリ自動追加の先例）: https://docs.smartlauncher.net/products/faq/changelog/5.4 （確認日 2026-10-02。spec 497 Prior artに既記録。本機能は自動分類ではなく明示mapping＋既定fallbackである点で不採用のまま参照）
- Nova Launcher等の第三者launcherでpromise iconが表示されない既知のplatform制約は、本調査では出典確認できた公式文書を持たないため根拠として使わず、AOSPソースとエミュレータ実証を正とした。

## Change history

- 2026-10-04: Complete。対象commit `8508c14182`で調査・実証。判定「前へ進める」。
