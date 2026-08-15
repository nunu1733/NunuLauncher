# Fresh-Install Provenance and Package-Event Classification

> Status: Accepted (research output of [Issue #54](https://github.com/nunu1733/NunuLauncher/issues/54))
> Updated: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-008, FR-009, FR-015; NFR-005, NFR-007, NFR-008, NFR-011
> Decision gates: D-004 (trigger policy)
> Storage decision: [ADR-0005](../adr/0005-fresh-install-presence-evidence.md)
> Downstream behavior: [Issue #55 spec](../../specs/55-convergent-incremental-placement/spec.md)

## 1. Research outcome

[organization-run-ux](../product/organization-run-ux.md) §2.3 は、package event 後の
incremental proposal を次の証拠が揃った場合だけ許可する（Issue #4 のfail-closed契約）。

1. **Completed fresh-install provenance**: default homeへ配送された成功済みの
   非replace `ACTION_SESSION_COMMITTED` と、その `SessionInfo` が存在し、package/profileが
   event対象と一致し、`INSTALL_REASON_USER`であり、unarchivalではない。
2. **Prior absence evidence**: eventより前に完了したprofile inventoryがpackageを不在と
   記録し、そのinventoryからeventまでの観測連続性が有効である。ever-seen setに記録が
   ないことだけは不在の証拠としない。
3. **Unique launchable target**: package+profileから`LauncherApps.getActivityList`で
   現在のlaunchable activityがちょうど1件に解決できる。

この3条件のいずれかがmissing、unknown、corrupt、stale、contradictoryなら
`Ambiguous`として扱い、incremental proposalもlayout mutationも行わない。未観測
reinstallを「確認必須のfalse positive」として許容しない。完全なprior-absence evidenceを
作れない環境では、false negative（proposalなし）のみを許容する。

本書はIssue #54のsource comparisonとdecisionを正本とする。観測可能なbehaviorと受入条件は
[#55 spec](../../specs/55-convergent-incremental-placement/spec.md)、実装順序・migration・
rollbackはその[plan](../../specs/55-convergent-incremental-placement/plan.md)、module/interface
ownershipは[DESIGN.md](../../DESIGN.md)、persistent storeの高コストな選択は
[ADR-0005](../adr/0005-fresh-install-presence-evidence.md)が正本である。

**本Issueで変更しないもの**: production behavior、Launcher DB/recovery DB schema、planner/
application public contract、permission、manifest。#55の実装開始条件は#54、#52、#57がclosedで、
#55 specがacceptedになっていることである。

## 2. Baseline evidence inventory

固定baseline（`505dbc40e6154c05158b5d0271c45f6a885a411b`）のentry pointは次のとおりである。

| Path | 観測事実 |
|---|---|
| `src/com/android/launcher3/model/ModelLauncherCallbacks.kt:40-85` | `LauncherApps.Callback`を`PackageUpdatedTask`へ変換。`onPackageAdded`→`OP_ADD`、`onPackageChanged`→`OP_UPDATE`、availability/remove/suspendを別opへ変換する |
| `src/com/android/launcher3/LauncherAppState.java:118-125` | `LauncherApps.registerCallback`の登録点 |
| `src/com/android/launcher3/pm/UserCache.java:104-106`、`src/com/android/launcher3/LauncherModel.java:272-299` | managed profile availabilityをuser eventとしてmodelへ渡す |
| `src/com/android/launcher3/SessionCommitReceiver.java:59-96`、`AndroidManifest-common.xml:86-93` | `ACTION_SESSION_COMMITTED`をmanifest receiverで受け、`SessionInfo`のUSER reason等を検証してinstall queueへ渡す |
| `src/com/android/launcher3/pm/InstallSessionHelper.java:150-259` | sessionのtrusted installer、package、USER reason、icon/label、未install、unarchivalを検証する既存helper |
| `src/com/android/launcher3/pm/InstallSessionTracker.java:70-147` | API 29+のPackageInstaller session callback追跡 |
| `src/com/android/launcher3/model/ItemInstallQueue.java:286-318` | `getActivityList(pkg,user).get(0)`を使う既存queue。organizerのunique-target ruleには再利用しない |
| `src/com/android/launcher3/util/PackageManagerHelper.java:201` | `LauncherApps.getActivityList(pkg,user)`の既存利用 |

既存Deckの`PackageUpdatedTask.java:456-472`のpackage-event organization hookは#57のretirement対象であり、
#55は二重のorganizer hookを追加しない。

## 3. Fixed platform findings

確認日: 2026-08-15。AOSP sourceはmutable branchではなく、次の固定commitを使用した。

- AOSP main snapshot `1cdfff555f4a21f71ccc978290e2e212e2f8b168`
- AOSP Android 15 release snapshot `cc8bb19595c661f5cf42f330457000e643c67d1b`
- repository baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`

### 3.1 Callback semantics

`PackageMonitor`は`ACTION_PACKAGE_ADDED`の`EXTRA_REPLACING=true`をupdateとして処理し、
`LauncherAppsService`は`onPackageChanged`へ配送する。従ってcallback名だけでfresh installを
証明できない。restore、device setup、enterprise policy installでも`onPackageAdded`は発火し得る。
`onPackagesAvailable`/`onPackagesUnavailable`はexternal application availabilityのeventであり、
quiet modeの証拠ではない。quiet/profile availabilityはLauncher3のuser-event pathで扱われる。

- [PackageMonitor.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/com/android/internal/content/PackageMonitor.java)
- [LauncherAppsService.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/LauncherAppsService.java)
- [LauncherApps.Callback reference](https://developer.android.com/reference/android/content/pm/LauncherApps.Callback)

### 3.2 Session provenance

AOSPは成功した非replace sessionだけについて`PackageInstallerSession`からsession commit broadcastを
生成し、`BroadcastHelper`はdefault home（ROLE_HOME）のpackageへtargeted broadcastを送る。installer
packageへのbroadcastではない。`sendBroadcastAsUser`はmanifest receiverをprocess death後にも起動し得る。
work profile installはprofile parentのhomeへ`EXTRA_USER`付きで送られる。staged sessionは除外される。

`SessionInfo.getInstallReason()`の定数はSDK 36.1/AOSPで`UNKNOWN=0`、`POLICY=1`、
`DEVICE_RESTORE=2`、`DEVICE_SETUP=3`、`USER=4`、`ROLLBACK=5`である。`PackageManager.getInstallReason`
はAOSP sourceでは`@hide`/`@TestApi`であり、通常launcher production seamの必須APIにしない。

- [PackageInstallerSession.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/PackageInstallerSession.java)
- [BroadcastHelper.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/BroadcastHelper.java)
- [PackageManager.java @ cc8bb19](https://android.googlesource.com/platform/frameworks/base/+/cc8bb19595c661f5cf42f330457000e643c67d1b/core/java/android/content/pm/PackageManager.java)
- [InstallPackageHelper.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/InstallPackageHelper.java)

### 3.3 Target resolution

`LauncherApps.getActivityList(packageName,user)`はMAIN/LAUNCHERに一致する有効なactivityを全件
返す。0件または2件以上はunique targetではない。baseline queueのfirst-item tie-breakは使わず、
ちょうど1件だけをeligibleとする。profile accessibility、package visibility、quiet/hidden stateの
query failureはambiguousである。

- [LauncherApps.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/content/pm/LauncherApps.java)
- [LauncherAppsService.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/LauncherAppsService.java)
- [LauncherApps reference](https://developer.android.com/reference/android/content/pm/LauncherApps)

### 3.4 Install timestamps

AOSPの`PackageInfo.firstInstallTime`/`lastUpdateTime`はupdateとreinstallを区別する補助候補だが、
通常launcherのprofile横断public seamではない。reinstallはfirstInstallTimeがfresh installと同様に
設定され得るため、FreshInstallの必須証拠にしない。

- [PackageInfo reference](https://developer.android.com/reference/android/content/pm/PackageInfo)
- [ScanPackageUtils.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/ScanPackageUtils.java)

## 4. Classification matrix

観測可能な分類とproposal可否は#55 specのclosed typeで実装する。研究上の結果は次のとおり。

| Event/evidence | Classification | Proposal |
|---|---|---|
| session commit、USER reason、非unarchival、valid prior-absence、target 1件 | `FreshInstall` | 許可。ただしproposal + preview + explicit confirmation |
| changed/replacing、restore、device setup、policy | `NotNew` | しない |
| observed prior presence後のremove→再install | `NotNew(REINSTALL)` | しない |
| availability return、remove、unavailable、suspend、unarchival | `NotNew` | しない |
| session欠落、prior-absence未証明、store unknown/corrupt、continuity gap、profile/query failure、target 0/複数、stale/contradictory | `Ambiguous` | しない。manual flowは妨げない |

### 4.1 Prior-absence evidence

`everSeen(package,profile) == false`は、event前の不在を証明しない。FreshInstallに使えるのは、
eventより前に完了したprofile inventoryがpackageを不在と記録し、そのinventoryからsession commitまでの
観測連続性が有効である場合だけである。

process death、listener gap、profile availability change、store recovery、unknown schemaの後は
coverage barrierを無効とし、完全inventoryが再完了するまでproposalを出さない。storeの破損や未知versionを
空storeへ初期化して判定を継続してはならない。未観測reinstallのfalse positiveは許容せず、
false negative（proposalなし）のみを許容する。永続storeのschema、migration、backup/restore、
corruption behaviorは[ADR-0005](../adr/0005-fresh-install-presence-evidence.md)、観測可能な
scenario/acceptanceは[#55 spec](../../specs/55-convergent-incremental-placement/spec.md)が所有する。

### 4.2 Representative sequences

1. **Fresh install**: complete inventory barrier → successful `SESSION_COMMITTED`/USER → package/profile
   match → exactly one launchable target → `FreshInstall`。
2. **Update/replacing**: `onPackageChanged` → `NotNew(UPDATE)`。ADD callbackだけでは判定しない。
3. **Restore/setup/policy**: session reasonが該当値 → `NotNew`。
4. **Observed reinstall**: prior inventory/presence record → remove → USER session commit →
   `NotNew(REINSTALL)`。
5. **Missing/unknown coverage**: no prior inventory、listener/process gap、store corruption、unknown
   schema、profile inaccessible → `Ambiguous`、no proposal。
6. **Ambiguous target**: target 0件または2件以上 → `Ambiguous`、no proposal。
7. **Work/private profile**: profile identityを保持してquery。accessibility不明またはhidden permission
   不足 → `Ambiguous`。

## 5. Privacy and diagnostics boundary

Presence evidenceはapp-private storageに限り、package name/profile identityを外部送信しない。
package、component、user/profile serial、session id、layout coordinate、rule内容は
[organizer-diagnostics](./organizer-diagnostics.md) §7のNever分類であり、classification codeだけを
内部diagnosticへ射影する。ambiguous/not-newはorganization runを開始せず、FreshInstall後にuserが
reviewを開始した場合だけ既存の`INCREMENTAL_PROPOSAL` run contractへ進む。

## 6. Downstream handoff

#55のspec/planが次を所有する。

- package/provenance adapterのobservable input/outputとclosed classification code
- prior-absence coverage barrier、missing/corrupt/unknown-versionのfail-closed behavior
- SessionCommitReceiverからのtyped sink bridgeと、既存Deck hookとの単一owner条件
- preview/confirmation、planner/application seam、stale recapture、profile/target test matrix
- migration、backup exclusion、rollback、instrumentation/contract testの実装順序

参照先:

- [#55 spec](../../specs/55-convergent-incremental-placement/spec.md)
- [#55 plan](../../specs/55-convergent-incremental-placement/plan.md)
- [DESIGN.md §4.4/§7](../../DESIGN.md)
- [ADR-0005](../adr/0005-fresh-install-presence-evidence.md)

## 7. Verification and change history

- source pathはbaseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b`で突合した。
- AOSP根拠は固定commit URLで確認した（確認日 2026-08-15）。
- Issue #54のexit artifacts（evidence comparison、classification matrix、process/profile/restore behavior、
  persistence/privacy decision、target uniqueness、failure behavior、downstream handoff）は本書と参照先で
  coverageする。
- 2026-08-15: Issue #54 research outputとして初版。
- 2026-08-15: review指摘により、受入条件を#55 specへ、persistent store decisionをADR-0005へ、
  public seam ownershipをDESIGN.mdへ移管。未観測reinstall、unknown/corrupt storeのfalse positiveを
  fail-closedへ変更。
