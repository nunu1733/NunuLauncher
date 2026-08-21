# Fresh-Install Provenance and Package-Event Classification

> Status: Accepted (research output of [Issue #54](https://github.com/nunu1733/NunuLauncher/issues/54))
> Updated: 2026-08-21
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-008, FR-009 (Later/deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)), FR-015; NFR-005, NFR-007, NFR-008, NFR-011
> Decision gates: D-004 (trigger policy)

## 1. Research outcome

[organization-run-ux](../product/organization-run-ux.md) §2.3 は、package event 後の
incremental proposal を、(a) event前のprior absence、(b) trustworthyなfresh-install
provenance、(c) package+profileから一意なlaunchable targetが揃った場合だけ許可する。

Issue #54の調査結果は、baselineで(a)を証明する権威ある履歴sourceが存在しない、である。
`LauncherApps` callback、`SessionInfo`、`PackageInfo` timestamp、current inventoryのどれも、
launcherが観測する前に発生したinstall→uninstall→reinstallを除外できない。従って、現baselineで
`FreshInstall`を生成するincremental eligibilityは**有効化しない**。
[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)は要件変更のOption Bを選択し、
FR-008/FR-009のpackage-event incremental placementをMVP外のLater/deferred capabilityとした。
package eventはproposalを出さず、manual organizationだけを利用可能にする。

`ever-seen` setの欠落、current inventoryでの不在、空store、session reason `USER`、
`firstInstallTime`のfresh-lookingな値は、過去install履歴が未観測の場合の不在証明ではない。
missing、unknown、corrupt、stale、contradictory evidenceはすべてno proposalである。

**本Issueで確定した境界**

- 現在のpackage callbackだけからfresh installを推論しない。
- `ACTION_SESSION_COMMITTED`は成功した非replace installのprovenanceを示すが、過去のinstall履歴を示さない。
- current inventoryをpresence memoryとして保存しても、inventory作成前のreinstallは除外できない。
- したがって、永続store、public classifier、SessionCommitReceiverとModelLauncherCallbacksの複合bridge、schema/migrationを本Issueで選択・実装しない。
- race、generation、crash、atomic consume/updateの規則を定義しないまま、2つのevent入力を組み合わせる実装も行わない。
- package-event incremental placementは[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)によりMVP外である。将来の再開は、権威ある履歴sourceと変更されたproduct requirementを新しいdecision Issueで確定してから行う。

本書はIssue #54で確認したsource comparisonと観測事実だけを所有する。incremental eligibilityを
無効化する判断とその理由の正本は[ADR-0005](../adr/0005-fresh-install-presence-evidence.md)である。
観測可能なincremental behavior/spec、module/interface ownership、実装順序、migration、rollbackは、
MVPでは作成しない。将来のLater capabilityとして再開する場合は、新しいproduct decisionと承認済みspecが必要である。

## 2. Baseline evidence inventory

固定baseline（`505dbc40e6154c05158b5d0271c45f6a885a411b`）のentry pointは次のとおりである。

| Path | 観測事実 | fresh-install proofとしての限界 |
|---|---|---|
| `src/com/android/launcher3/model/ModelLauncherCallbacks.kt:40-85` | `LauncherApps.Callback`を`PackageUpdatedTask`へ変換。`onPackageAdded`→`OP_ADD`、`onPackageChanged`→`OP_UPDATE`、availability/remove/suspendを別opへ変換 | restore/setup/policy installとreinstallもADD系に見え得る。callback名はproofではない |
| `src/com/android/launcher3/LauncherAppState.java:118-125` | `LauncherApps.registerCallback`の登録点 | callbackは過去install履歴を持たない |
| `src/com/android/launcher3/pm/UserCache.java:104-106`、`src/com/android/launcher3/LauncherModel.java:272-299` | managed profile availabilityをuser eventとしてmodelへ渡す | profile lifecycleの変化は既存inventoryのcontinuityを壊す |
| `src/com/android/launcher3/SessionCommitReceiver.java:59-96`、`AndroidManifest-common.xml:86-93` | `ACTION_SESSION_COMMITTED`を受け、`SessionInfo`のUSER reason等を検証してinstall queueへ渡す | sessionは今回のinstallを示すが、launcher観測前のinstall→uninstallを示さない |
| `src/com/android/launcher3/pm/InstallSessionHelper.java:150-259` | trusted installer、package、USER reason、icon/label、未install、unarchivalを検証 | 未観測の過去install履歴を補えない |
| `src/com/android/launcher3/pm/InstallSessionTracker.java:70-147` | API 29+のPackageInstaller session callback追跡 | process/listener gapと過去履歴を解決しない |
| `src/com/android/launcher3/model/ItemInstallQueue.java:286-318` | `getActivityList(pkg,user).get(0)`を使う既存queue | first-item tie-breakはunique target ruleではない |
| `src/com/android/launcher3/util/PackageManagerHelper.java:201` | `LauncherApps.getActivityList(pkg,user)`の既存利用 | current targetの解決だけで、prior absenceを証明しない |

既存Deckの`PackageUpdatedTask.java:456-472`のpackage-event organization hookは#57 / PR #79でretireされ、
replacement hookは存在しない。Later capabilityとしてincremental placementを再開する場合も二重のorganizer hookを追加してはならない。

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
Session reasonは今回のinstallの分類には使えるが、launcher観測前のinstall履歴を提供しない。

- [PackageInstallerSession.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/PackageInstallerSession.java)
- [BroadcastHelper.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/BroadcastHelper.java)
- [PackageManager.java @ cc8bb19](https://android.googlesource.com/platform/frameworks/base/+/cc8bb19595c661f5cf42f330457000e643c67d1b/core/java/android/content/pm/PackageManager.java)
- [InstallPackageHelper.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/InstallPackageHelper.java)

### 3.3 Target resolution

`LauncherApps.getActivityList(packageName,user)`はMAIN/LAUNCHERに一致する有効なactivityを全件
返す。0件または2件以上はunique targetではない。baseline queueのfirst-item tie-breakは使わない。
profile accessibility、package visibility、quiet/hidden stateのquery failureはambiguousであるが、
unique targetであってもprior absenceの不足を補えない。

- [LauncherApps.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/content/pm/LauncherApps.java)
- [LauncherAppsService.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/LauncherAppsService.java)
- [LauncherApps reference](https://developer.android.com/reference/android/content/pm/LauncherApps)

### 3.4 Install timestamps

AOSPの`PackageInfo.firstInstallTime`/`lastUpdateTime`はupdateとreinstallを区別する補助候補だが、
通常launcherのprofile横断public seamではない。reinstallはfirstInstallTimeがfresh installと同様に
設定され得るため、過去履歴のproofにはならない。

- [PackageInfo reference](https://developer.android.com/reference/android/content/pm/PackageInfo)
- [ScanPackageUtils.java @ 1cdfff5](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/pm/ScanPackageUtils.java)

## 4. Classification conclusion

現baselineでは、reinstallを完全に除外できるprior-absence evidenceがないため、次の分類だけを
安全に確定する。

| Event/evidence | Classification | Incremental proposal |
|---|---|---|
| changed/replacing、restore、device setup、policy | `NotNew` | しない |
| observed prior presence後のremove→再install | `NotNew(REINSTALL)` | しない |
| availability return、remove、unavailable、suspend、unarchival | `NotNew` | しない |
| session欠落、prior-absence未証明、current inventoryのみ、unknown/corrupt evidence、profile/query failure、target 0/複数、race/crash不明 | `Ambiguous` | しない。manual flowのみ利用可能 |
| session commitがUSERでtargetが一意でも、event前の権威ある履歴がない | `Ambiguous(PRIOR_ABSENCE_UNPROVEN)` | しない |

### 4.1 Counterexample proving current inventory is insufficient

1. presence store/inventoryが存在しない状態で、package Xをinstallする。
2. launcherが観測する前にXをuninstallする。
3. launcherが初回complete inventoryを作成し、Xが不在として記録される。
4. XをUSER sessionでreinstallする。
5. current inventory、USER reason、unique targetは揃うが、Xが過去に存在した事実は失われている。

したがってcurrent inventoryのabsenceはevent前のabsenceではなく、`FreshInstall`の根拠にならない。
[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)は要件を変更するOption Bを選択し、この能力をMVP外へdeferした。

### 4.2 Process, reboot, profile, and restore behavior

現baselineではincremental eligibilityが無効なため、次の全てのpackage eventは、証拠が一見揃って
見えても`no proposal`である。layout mutationはなく、manual organizationだけが利用可能である。

| 状況 | 分類・挙動 |
|---|---|
| process death中にinstall、再起動後に`SESSION_COMMITTED`/package callbackを受信 | `Ambiguous(PRIOR_ABSENCE_UNPROVEN)`、no proposal。process deathを跨ぐ履歴coverageは存在しない |
| reboot後のpackage event、restore/setupのevent burst | `Ambiguous`または`NotNew`、no proposal。rebootはFreshInstallの根拠にならない |
| profile追加、削除、quiet/locked/hidden状態変更後のevent | `Ambiguous`または`NotNew`、no proposal。profile identityの変更はprior absenceを無効化する |
| device restore後のpackage event | `NotNew(RESTORE)`または証拠不足による`Ambiguous`、no proposal |
| storeを採用しない現行decision | presence storeのownership、lifecycle、retention、versioning、migration、backup/restoreは**N/A**。保存・復旧・削除・version gateを実装しない |
| package remove/unavailable/suspend/availability return | `NotNew`、no proposal。既存layoutの扱いはpreservation policyに従う |

`ModelLauncherCallbacks`と`SessionCommitReceiver`は現在別のplatform entry pointであり、baselineに
両者をatomicにconsume/updateする共有generationやtransactionはない。ordering、correlation key、
generation owner、crash/replay、durable-write failureの規則を承認しないまま、両入力を組み合わせる
classifier/bridgeを実装してはならない。これらはMVPでは定義しない。Later capabilityとして再開する場合は、
新しいproduct decision、承認済みspec、planで定義する。

## 5. Privacy and diagnostics boundary

package、component、user/profile serial、session id、layout coordinate、rule内容は
[organizer-diagnostics](./organizer-diagnostics.md) §7のNever分類であり、research/分類の内部ログへ
出力しない。package eventがambiguous/not-newの場合はorganization runを開始しない。manual flowは
常に利用可能で、auto-incrementalは有効化しない。

## 6. Handoff and deferred capability

[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) selected Option B. FR-008/FR-009
package-event incremental placement is outside the MVP, and #55 is to be closed as not planned/deferred
once this scope decision is merged on `main`. No #55 spec, plan, production package-event organizer code,
presence store, classifier, session bridge, replacement hook, permission, telemetry, or migration is authorized.

The current handoff is therefore **no proposal from package events** and **manual organization remains
available**. The accepted reinstall counterexample and all fail-closed classifications remain unchanged.
Confirmation UI does not permit a false-positive fresh-install classification.

A later capability may be reconsidered only through a new bounded product-decision issue that first defines
an authoritative pre-observation package/profile history source; profile lifecycle and retention boundaries;
callback/session ordering, correlation, generation ownership, atomic consume/update, crash/replay, and
durable-write-failure behavior; and exact no-proposal behavior for unavailable, stale, corrupt, missing, or
contradictory evidence. Only after that decision is accepted may a separate feature issue create a spec and plan.

## 7. Verification and change history

- source pathはbaseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b`で突合した。
- AOSP根拠は固定commit URLで確認した（確認日 2026-08-15）。
- Issue #54のexit artifacts（evidence comparison、classification matrix、process/reboot/profile/restore
  behavior、target uniqueness、failure behavior、store lifecycle N/A、downstream blocker）は本書でcoverageする。
- 2026-08-15: Issue #54 research outputとして初版。
- 2026-08-15: review指摘により、current inventoryをprior absenceと扱わず、incremental eligibilityを
  現baselineでは無効化。#55の未承認spec/planと未確定のstore/bridge decisionを撤回した。
- 2026-08-21: [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) selected Option B. The
  negative technical conclusion is unchanged; FR-008/FR-009 package-event incremental placement is deferred
  outside the MVP and #55 has no implementation handoff.
