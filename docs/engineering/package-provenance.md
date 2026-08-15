# Fresh-Install Provenance and Package-Event Classification Contract

> Status: Accepted (research output of [Issue #54](https://github.com/nunu1733/NunuLauncher/issues/54))
> Updated: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-008, FR-009, FR-015; NFR-005, NFR-007, NFR-008, NFR-011
> Decision gates: D-004 (trigger policy)
> Related: [organization-run-ux](../product/organization-run-ux.md) §2.3 (UX contract),
> [spec 10](../../specs/10-pure-organization-planning/spec.md) / [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md) (planner, unchanged),
> [spec 13](../../specs/13-safe-layout-application/spec.md) (application, unchanged),
> [organizer-diagnostics](./organizer-diagnostics.md) §7 (privacy classification),
> [ADR-0002](../adr/0002-replace-deck-layout.md), [ADR-0003](../adr/0003-organizer-recovery-point-storage.md)

## 1. Problem and scope

[organization-run-ux](../product/organization-run-ux.md) §2.3 は、package event 後の
incremental proposal を次の3条件に限ると定めた（Issue #4 のfail-closed契約）。

- (a) event 前の persisted な package+profile presence 観測が target の不在を示す。
- (b) trustworthy な install/session provenance が completed な fresh install を示し、
  update/replacing/device restore/reinstall を除外する。
- (c) package+profile が launchable target へ一意に解決できる。

同契約は意図的に evidence の storage/API 境界を選ばなかった。本書がその決定を
所有する。すなわち、どの証拠源の組合せで package event を
「genuinely new な launchable app の1回のinstall」と証明するか、必要最小限の
persistent state は何か、失敗・矛盾・欠落時にどう振る舞うか、そして後続の
incremental feature（[#55](https://github.com/nunu1733/NunuLauncher/issues/55)）が
消費する最小の adapter seam は何かを固定する。

**In scope**

- baseline と support OS の package-event entry point と install/session API の
  evidence inventory と比較（§2）。
- event classification matrix と canonical な判定順序、closed な分類code（§3）。
- launchable target の一意解決規則（§4）。
- 最小 persistent state（presence memory）の要否、ownership、lifecycle、retention、
  versioning、backup/restore 挙動、privacy classification（§5）。
- process death / reboot / profile / restore 下の挙動（§6–§7）。
- 失敗・stale 時の振る舞いと diagnostics 境界（§8）。
- [#55](https://github.com/nunu1733/NunuLauncher/issues/55) への downstream 受入条件と
  handoff（§9–§10）。

**Out of scope（本契約が意図的に扱わないもの）**

- incremental の UI、plan 適用、自動適用。preview + explicit confirmation は
  [organization-run-ux](../product/organization-run-ux.md) §2.3 の契約どおりであり、
  本書は proposal を出してよいかの判定だけを決める。
- planner（spec 10/12）と application/recovery（spec 13）の公開type・algorithm 変更。
  本書は両 seam を変更しない。
- usage-frequency signal、外部classification、telemetry（NFR-008、default off）。
- package の remove/unavailable/suspend 時の既存配置の保持規則
  （[item-preservation-policy](../product/item-preservation-policy.md) の対象）。

本Issueは research であり、production code、DB/schema、権限、既存契約を変更しない。

## 2. Evidence sources

### 2.1 Baseline entry points（固定commitで確認）

| Path | 役割 |
|---|---|
| `src/com/android/launcher3/model/ModelLauncherCallbacks.kt:40-85` | `LauncherApps.Callback` を `PackageUpdatedTask` の op へ変換。`onPackageAdded`→`OP_ADD`、`onPackageChanged`→`OP_UPDATE`、`onPackagesAvailable`→`OP_UPDATE`、`onPackagesUnavailable(!replacing)`→`OP_UNAVAILABLE`、suspend/unsuspend、`onPackageRemoved`→`OP_REMOVE` |
| `src/com/android/launcher3/LauncherAppState.java:118-125` | `LauncherApps.registerCallback(ModelLauncherCallbacks)` 登録点。`LauncherApps` は複数 listener 登録を許す |
| `src/com/android/launcher3/pm/UserCache.java:104-106` + `src/com/android/launcher3/LauncherModel.java:272-299` | managed profile の available/unavailable/removed 等 → `PackageUpdatedTask(OP_USER_AVAILABILITY_CHANGE, user)` |
| `src/com/android/launcher3/model/PackageUpdatedTask.java:134-149,456-472` | `OP_ADD` の model 処理と（Lawnchair固有の）Deck `addNewlyInstalledApp` hook |
| `src/com/android/launcher3/SessionCommitReceiver.java:59-96` + `AndroidManifest-common.xml:86-93` | `ACTION_SESSION_COMMITTED` を manifest receiver で受信し、`SessionInfo.getInstallReason() == INSTALL_REASON_USER` を検証して install queue へ積む |
| `src/com/android/launcher3/pm/InstallSessionHelper.java:150-259` | session の verify（installer が system/trusted、app package 名あり）、`verifySessionInfo`（install reason USER、icon/label あり、未install）、unarchival 除外 |
| `src/com/android/launcher3/pm/InstallSessionTracker.java:70-147` | `LauncherApps.registerPackageInstallerSessionCallback` による session 追跡（API 29+） |
| `src/com/android/launcher3/model/ItemInstallQueue.java:79-88,286-318` | package 名 + user の pending queue を app-private file へ永続化。`getActivityList(pkg,user).get(0)` で最初の1件を使う（一意性検査なし） |
| `src/com/android/launcher3/util/PackageManagerHelper.java:201` | `LauncherApps.getActivityList(pkg, user)` による解決の既存利用 |

### 2.2 Platform API の検証済み semantics

以下は AOSP `frameworks/base`（`main` @ `1cdfff5`、`android15-release` @ `cc8bb19`、
旧tag で安定性確認、確認日 2026-08-15）と Android SDK Platform 36.1 の
`android.jar` から確認した。script による迎合ではなく出典付きの事実である。

1. **`LauncherApps.Callback.onPackageAdded` は非replaceのinstall でのみ発火する。**
   update（`ACTION_PACKAGE_ADDED` + `EXTRA_REPLACING=true`）は
   `onPackageChanged` へ映射される（`PackageMonitor.doHandlePackageEvent` が
   `EXTRA_REPLACING` で分岐し、`LauncherAppsService.MyPackageMonitor.onPackageModified`
   → `onPackageChanged`）。API 21 から安定。
   出典: [PackageMonitor.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/content/PackageMonitor.java)、
   [LauncherAppsService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)、
   [LauncherApps.Callback reference](https://developer.android.com/reference/android/content/pm/LauncherApps.Callback)
2. **`onPackageAdded` は install reason を問わず発火する。** restore、device setup、
   enterprise policy install でも同じ callback が来る。
   `LauncherAppsService` に install reason の言及はなく、guard は profile 有効性と
   package visibility のみ。よって callback 名だけでは fresh install を証明しない。
3. **`onPackagesAvailable` / `onPackagesUnavailable` は外部storage系event専用**で、
   quiet mode や managed profile の on/off ではない。quiet mode は
   `UserCache` 経由の `OP_USER_AVAILABILITY_CHANGE`（§2.1）として到達する。
4. **`PackageManager.getInstallReason(pkg, user)` はAOSPでは `@hide` / `@TestApi`** であり、
   通常のlauncher production codeが利用できる公開SDK APIとは扱わない。AOSPは
   user ごとの初回install reasonを記録し、replace時に元のreasonを保持するが、
   cross-profile queryには `INTERACT_ACROSS_USERS_FULL` が必要である。従ってこの
   Issueのproduction seamには採用しない。定数は `INSTALL_REASON_UNKNOWN=0`、
   `POLICY=1`、`DEVICE_RESTORE=2`、`DEVICE_SETUP=3`、`USER=4`、
   `ROLLBACK=5`（rollbackは`@hide`）。
   出典: [PackageManager.java (AOSP, @hide/@TestApi)](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/content/pm/PackageManager.java)、
   [InstallPackageHelper.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/InstallPackageHelper.java)
5. **`PackageInfo.firstInstallTime` / `lastUpdateTime` は候補だが必須証拠にしない。**
   AOSPではfresh install、update、uninstall後のreinstall、device restoreで時刻が
   それぞれ設定されるが、通常launcherから別profileの`PackageInfo`を安全に取得する
   公開seamがなく、reinstallはfresh installと同じ時刻へ戻る。したがって時刻は
   取得できるprivileged adapterの補助的な矛盾検査に限り、fresh-install eligibilityの
   証明には使わない。
   出典: [PackageInfo reference](https://developer.android.com/reference/android/content/pm/PackageInfo)、
   [ScanPackageUtils.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ScanPackageUtils.java)
6. **`ACTION_SESSION_COMMITTED` broadcast は「成功した非replace install」のみ、
   default home app（ROLE_HOME）の package 宛てに送られる。** installer app 宛てでは
   ない。manifest receiver 宛ての実broadcastであり（`FLAG_RECEIVER_REGISTERED_ONLY`
   なし、package指定のtargeted broadcast）、**launcher processが死亡していても
   processを起動して配達される**。work profile内のinstallもprofile parentの
   launcherへ`EXTRA_USER`（実際のinstall user）付きで届く。staged sessionは送られ
   ない。追加userへのinstall-existingでも合成`SessionInfo`付きで送られる。
   `EXTRA_SESSION` の `SessionInfo` は `getInstallReason()`（API 26）/
   `getAppPackageName()` / `isUnarchival()`（API 35+）を持つ。このsession commitを
   productionで利用できるfresh-install provenanceの主証拠に選択する。
   出典: [BroadcastHelper.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/BroadcastHelper.java)、
   [PackageInstallerSession.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageInstallerSession.java)、
   [Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
7. **`LauncherApps.getActivityList(pkg, user)`（API 21）** は MAIN/LAUNCHER intent に
   matchする有効なactivityをpackage+profileごとに返す。複数launcher activityは
   全件返る。0件もあり得る（非launchable、disabled、profile無効、visibility不足）。
   personal側launcherからwork profileの列挙はできる。hidden/private profileには
   `ACCESS_HIDDEN_PROFILES` + ROLE_HOMEが追加で要る。
   出典: [LauncherApps reference](https://developer.android.com/reference/android/content/pm/LauncherApps)、
   [LauncherAppsService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)

### 2.3 候補比較と選択

| # | 証拠源 | process death / reboot | 区分できるもの | 致命的な不足 |
|---|---|---|---|---|
| E1 | `LauncherApps` callback 種別（`onPackageAdded` 等） | n/a（event 時のみ） | replacing updateは`onPackageChanged`に行くためADDに混入しない（§2.2-1） | restore/setup/policy install、reinstallも同じ`onPackageAdded`で来る（§2.2-2）。positive triggerにはしない |
| E2 | `PackageManager.getInstallReason` | systemには保存されるが通常launcherの公開SDK seamではない | privileged adapterがある場合だけrestore/setup/policyの補助検査 | cross-profile query権限とhidden API境界。reinstallとfreshは両方`USER` |
| E3 | `PackageInfo.firstInstallTime` / `lastUpdateTime` | systemには保存されるがprofile横断の公開launcher seamではない | privileged adapterがある場合だけ時刻矛盾の補助検査 | reinstallは時刻がリセットされfreshと同じ。restoreも同じ |
| E4 | `ACTION_SESSION_COMMITTED` / `SessionInfo` | **broadcastはprocess死亡を跨いで配達される**（§2.2-6） | trusted installer由来のreason、unarchival（API 35+）、成功した非replace installの裏付け | launcherがdefault homeでない、staged session、delivery raceでは欠落し得る。欠落時はfail-closed |
| E5 | organizer 所有の presence memory（§5） | 保存される（app-private 永続） | reinstall（かつて観測済み）と genuinely new の区分 | launcher が一度も観測していない install→uninstall は記憶に残らない（§11 の残留risk） |
| E6 | model の in-memory 状態（`AllAppsList` 等） | 失われる（load で再構築） | — | 事前状態の権威ある oracle にならない。model task との実行順序も保証されない。**要件から除外** |
| E7 | `LauncherApps.getActivityList(pkg, user)` | query 時点の真実 | launchable target の 0/1/N、有効性、profile 到達性 | —（target 解決専用とする） |

**選択:** FRESH_INSTALLの判定に必要十分な証拠は、**E4（成功した非replace
`SESSION_COMMITTED`の`SessionInfo`があり、reasonが`USER`）かつE5（presence memory
に不在）かつE7（launchable targetがちょうど1件）**である。E1はnegative signalと
inventory同期にだけ使い、callback単体はpositive triggerにしない。
E2（`PackageManager.getInstallReason`）とE3（install time）は通常のlauncher公開SDK
seamではないため、取得できるprivileged adapterの補助的な矛盾検査に限る。E6は要件に
使わない。

この選択の根拠:

- E4はsystemが成功したnon-replacing installとして生成し、targeted broadcastで
  process deathを跨いでdefault homeへ配送する。ただしdefault homeでない期間や
  staged sessionは欠落するため、その場合はfail-closedとする。
- E1–E4はsystem由来であり、reinstallの除外に使えるのはE5のみである。
  session reasonとinstall timeだけではreinstallとfresh installが同じに見える
  （§2.2-4, §2.2-5）。したがって「no persistence」では契約条件 (b) のreinstall
  除外を満たせない（§5で決定）。
- E2/E3はpublic launcher seamでの利用可能性が保証されないため、必須条件にしない。
- E4を必須にすると、default homeでない間のinstallやstaged sessionは判定不能になるが、
  fail-closed契約によりproposalを出さない。
- E6 を使うと、model load 状態と model task の実行順序に判定が依存し、決定性と
  fail-closed 性が壊れる。

## 3. Classification contract

### 3.1 分類type（closed set）

```text
ProvenanceClassification =
    | FreshInstall(target: LaunchableTarget)   // proposal に出てよい唯一の分類
    | NotNew(reason: NotNewReason)
    | Ambiguous(reason: AmbiguityReason)

NotNewReason =
    | UPDATE              // onPackageChanged 由来（replacing update を含む）
    | RESTORE             // install reason が DEVICE_RESTORE
    | DEVICE_SETUP        // install reason が DEVICE_SETUP
    | POLICY              // install reason が POLICY
    | REINSTALL           // presence memory に在籍（観測済みの再出現）
    | AVAILABILITY_RETURN // availability/profile 系 event 由来の出現
    | UNARCHIVAL          // session が unarchival（API 35+、flag 付き）
    | REMOVE              // onPackageRemoved 由来
    | UNAVAILABLE         // onPackagesUnavailable(replacing=false) 由来
    | SUSPEND             // suspend 由来

AmbiguityReason =
    | PROFILE_INACCESSIBLE      // profile が quiet/locked/hidden/無効、query 不可
    | SESSION_UNAVAILABLE        // session provenance が欠落・取得不能
    | INSTALL_REASON_UNAVAILABLE // privileged reason が UNKNOWN、または取得不能
    | EVIDENCE_STALE            // one-shot evidence tokenの再利用、process generation変更、再検証不一致
    | PRESENCE_MEMORY_FAILED    // presence memory の読み込み・検証に失敗
    | TARGET_NOT_LAUNCHABLE     // getActivityList が 0 件
    | TARGET_NOT_UNIQUE         // getActivityList が 2 件以上
    | TARGET_RESOLUTION_FAILED  // query 失敗・例外・visibility 不足
    | SESSION_CONTRADICTORY     // session 証拠が他の証拠と矛盾
    | EVIDENCE_CONTRADICTORY    // 上記以外の矛盾
```

`FreshInstall` は解決済みの唯一の launchable target（package、component、profile
identity）を伴う。`UPDATE` と `REPLACING` を分けない。platform は replace update を
`onPackageChanged` に映射するため、両者は launcher から同一の callback として
観測され、区別する証拠も必要もない（§2.2-1）。

### 3.2 Canonical な判定順序

判定は次の順で行い、最初に失敗した段階の code を返す。順序は契約の一部であり、
実装は変更しない。入力は event 観測（callback 種別、package、user）と
判定時刻のみで、model の in-memory 状態（E6）には依存しない。

1. **Event 種別**: trigger が `onPackageAdded` 以外（changed/available/unavailable/
   removed/suspend/profile 系）なら `NotNew`（対応する reason）。availability 系で
   package が「現れた」ように見えても `AVAILABILITY_RETURN`。
2. **Profile 到達性**: 対象 profile への query が失敗・制限されるなら
   `Ambiguous(PROFILE_INACCESSIBLE)`。
3. **Session provenance**: E4が取得できなければ `Ambiguous(SESSION_UNAVAILABLE)`。
   session reasonが`INSTALL_REASON_USER`でなければ`NotNew`（`RESTORE` /
   `DEVICE_SETUP` / `POLICY`）。reasonが`UNKNOWN`なら
   `Ambiguous(INSTALL_REASON_UNAVAILABLE)`。E2のprivileged reasonが取得できる場合は
   session reasonとの矛盾を後段で検査する。
4. **Evidence lifecycle**: session evidenceは一回のcallbackに紐づくone-shot tokenとして
   扱う。process generationを跨いだtoken、proposal/capture間でpackage/profile/targetが
  変わったtoken、または古いtokenを再利用した場合は`Ambiguous(EVIDENCE_STALE)`。
   E2/E3のprivileged adapterが値を返した場合だけ、session/packageと矛盾しないことを
  確認し、矛盾時は`Ambiguous(EVIDENCE_CONTRADICTORY)`。取得不能は補助証拠の理由に
  せず、time値そのものをpublic launcher seamの必須条件にしない。
5. **Presence memory**: 対象 package+profile が在籍なら `NotNew(REINSTALL)`。
   memory が読めなければ `Ambiguous(PRESENCE_MEMORY_FAILED)`。
6. **Target 一意性**: `getActivityList(pkg, user)` が 0 件なら
   `Ambiguous(TARGET_NOT_LAUNCHABLE)`、2件以上なら `Ambiguous(TARGET_NOT_UNIQUE)`、
   失敗なら `Ambiguous(TARGET_RESOLUTION_FAILED)`。API 35+ かつ archiving flag
   有効時は session 証拠の `isUnarchival()` が true なら `NotNew(UNARCHIVAL)`。
7. **Session 一貫性**: E4 が入手できていれば、その reason と package が他の証拠と
   矛盾しないことを確認する。矛盾すれば `Ambiguous(SESSION_CONTRADICTORY)`。
   E4 が無いことは矛盾ではない（§7）。

全段階を通過した場合のみ `FreshInstall` となる。

### 3.3 Event classification matrix

| 分類 | 代表的な event 列と証拠 | Incremental proposal |
|---|---|---|
| fresh install | session commit → `onPackageAdded`。session reason `USER`、presence 不在、target 1件（time値は補助矛盾検査のみ） | **許可**（preview + confirm は必須） |
| update / replacing | `onPackageChanged`（replaceもここへ映射）。またはone-shot evidenceの再利用・capture時のtarget変更 | しない |
| restore | device restore中のinstall。session reasonが`DEVICE_RESTORE` | しない |
| device setup / policy | setup中やenterprise配布のinstall。session reasonが`DEVICE_SETUP` / `POLICY` | しない |
| reinstall | 過去に観測済み（presence memory在籍）→ uninstall → 再install。session reasonが`USER`でもpresenceが在籍 | しない |
| availability return | 外部storage再マウント（`onPackagesAvailable`）、quiet mode 解除等の profile 系出現 | しない |
| remove | `onPackageRemoved`（replace は除く） | しない（保持規則は preservation policy の対象） |
| unavailable / suspend | `onPackagesUnavailable(replacing=false)`、suspend | しない |
| unarchival | API 35+ で `SessionInfo.isUnarchival()` がtrue、またはpresence在籍（archived appはinstall継続のため観測済み） | しない |
| ambiguous | 証拠の欠落・失効・矛盾、process generation変更、quiet/hidden profile、target 0/2件以上、query失敗、capture時のtarget/profile変更 | しない。manual flowは妨げない |

### 3.4 代表 event sequence（実装test の骨子）

1. **通常install**: `SESSION_COMMITTED`（`EXTRA_USER`、reason `USER`）→
   `onPackageAdded(pkg, user)` → 判定 → `FreshInstall` → presence memory へ追加。
2. **通常update**: `onPackageChanged` のみ。`FreshInstall` 判定に入らない。
3. **restore**: 初回起動時に `onPackageAdded` が連発。各々 reason `DEVICE_RESTORE`
   → 全て `NotNew(RESTORE)`。
4. **reinstall**: install（観測、memory追加）→ uninstall（`onPackageRemoved`、
   memoryは**追記のみ**なので在籍のまま）→ 再install（session reason `USER`）→
   `NotNew(REINSTALL)`。
5. **launcher 死亡中の uninstall + reinstall**: 最初の install を観測済みなら
   memory 在籍のまま → `NotNew(REINSTALL)`。最初の install 自体を未観測なら
   `FreshInstall` と誤分類され得る（§11 残留risk、許容）。
6. **work profile**: profile 内 install も parent launcher へ callback/broadcast が
   届く。`getActivityList(pkg, workUser)` で target 解決。quiet 中は
   `PROFILE_INACCESSIBLE`。
7. **複数 launcher activity 持ち app**: `getActivityList` が2件 →
   `Ambiguous(TARGET_NOT_UNIQUE)`。baseline `ItemInstallQueue` の「最初の1件を
   使う」規則（`ItemInstallQueue.java:297-306`）は organizer には**再利用しない**。
8. **非launchable package（library 等）**: `onPackageAdded` は来得るが
   `getActivityList` 0件 → `Ambiguous(TARGET_NOT_LAUNCHABLE)`。

## 4. Launchable-target uniqueness

- 解決は `LauncherApps.getActivityList(packageName, user)` のみを使う。判定時と
  run capture 時（proposal 受理後）の2回解決し、間で解決結果が変われば stale
  扱いで再判定する（適用側の revision/stale 規則は spec 13 / #55 の所有）。
- **ちょうど1件**のときのみ eligible。0件・2件以上は proposal しない。
  v1 に「best activity」の tie-break や priority 選択は作らない（fail-closed）。
- 同一 package 内の activity-alias 重複も 2件以上として数える。
- profile identity は解決結果の一部として保持し、以降の planner 入力・適用で
  profile 混同が起きないようにする（system invariant 6 の入力側義務）。

## 5. Presence memory（最小persistent state の決定）

**決定: 最小の persistent presence memory を新設する。「no persistence」は採らない。**

- 根拠: reinstall と fresh install はE1–E4の全てで同一に観測される
  （§2.2-4, §2.2-5, §2.3-E5）。契約条件 (b) のreinstall除外を満たす唯一の手段が
  launcher側の記憶である。記憶が無ければ「全ADD eventをambiguousにする」
  しか安全な道がなく、incremental機能が成立しない。
- 逆に、この記憶があれば事前不在（条件 (a)）も同時に証明できる。ただし
  「不在」の意味は「この launcher が一度も観測していない」であり、
  端末の完全なinstall履歴ではない（§11）。

**内容と形状**

```text
PresenceMemory {
    schemaVersion: Int                       // 本契約は 1
    profiles: Map<UserSerial, Set<PackageName>>
}
```

- key は profile ごとの stable な user serial（`UserManager.getSerialNumberForUser`）。
  `UserHandle` の equals だけに依存しない。package 名はそのまま保存する
  （§5 privacy 参照）。それ以外の情報（component、title、時刻、件数、layout）は
  保存しない。
- 保存先は **app-private な単一file**（temp write + atomic rename）。SQLite を
  使わない。全量読み込みと key 照会だけでよく、transaction・部分更新・複合query
  が不要なためである（`ItemInstallQueue` の private file が先例）。
  Launcher layout DB へは置かない。layout DB は backup allowlist に丸ごと含まれ
  （ADR-0003 の調査どおり）、backup 分離が保てない。

**Lifecycle**

- **追記のみ**。次の観測で package+profile を追加する。
  (1) ADD/UPDATE/availability 系 event の処理、(2) 起動時の inventory sweep
  （`LauncherApps.getActivityList(null, user)` で到達可能な全 profile の
  launchable package を一括反映）。uninstall 観測でも削除しない。削除すると
  reinstall が fresh に見えるためである。
- **profile 削除時**（managed profile removed 等）は当該 serial の entry を
  全体として破棄する。serial は再利用されないため、残留は無害だが不要な
  identity 保持は避ける（NFR-008）。
- **破損時**は初期化して空から再構築する（sweep で即座に現在値が復元される）。
  run を止めない。誤分類方向は §11 の残留risk と同じ proposal-only risk である。

**Retention と容量**

- 時間経過、package remove、unavailableで削除しない。削除するとreinstallをfreshと
  誤認し得るためである。
- profileが削除された時、またはapp dataがclear/uninstallされた時だけ当該entryを破棄する。
  任意のFIFO/時間上限は設けない。上限を設けると古いpackageのreinstallをfreshと
  誤認するため、safety contractと両立しない。保存するのはpackage+profileのmembership
  だけであり、event時刻、session id、component、layout等は保持しない。

**Versioning / migration**

- `schemaVersion` を持つ。未知の未来version は読み込めるまで初期化する
  （downgrade で古いdataを信じない）。schema 1 の範囲で破壊的変更は予定しない。

**Backup / restore 挙動**

- 本fileは新規fileであり、baseline の backup 経路（Lawnchair ZIP の `getFiles()`
  allowlist、`res/xml/backupscheme.xml`）に含まれない。**両backup から除外される
  ことを維持する**。restore 先端末で memory が空でも、restore install は
  reason `DEVICE_RESTORE` で除外されるため安全性は reason 証拠が担う
  （記憶の restore は不要であり、実装test で backup 非含有を証明する。
  ADR-0003 / organizer-diagnostics §8 と同じ検証形式）。

**Privacy classification（NFR-008）**

| 項目 | 取り扱い |
|---|---|
| 保存内容の性質 | install 済み package 名の inventory。個人に関連する情報として扱う |
| 保存先 | app-private storage のみ。外部送信経路なし（telemetry なし。[organizer-diagnostics](./organizer-diagnostics.md) §12 と同じ境界） |
| diagnostic 出力 | package 名・serial は [organizer-diagnostics](./organizer-diagnostics.md) §7 の **Never** 分類。classification 結果は code のみ（§8） |
| user 可視・export | しない。export 対象外 |
| 暗号化・hash 化 | しない。照会は完全一致のみで、既知 package 名への salt なし hash は反転可能、salt 保存は複雑化だけで利得がない |
| 消去 | app data clear / uninstall で消える。設定UI からの個別消去は v1 に用意しない |

## 6. Process death, reboot, profile, restore

| 状況 | 挙動 |
|---|---|
| install 時に launcher process 死亡 | `SESSION_COMMITTED` はsystemがprocessを起こして配達する（§2.2-6）。deliveryを受け取れない場合は`SESSION_UNAVAILABLE`でproposalしない。presence memoryはlauncher永続で再起動後も残る |
| reboot 直後の event burst（restore 等） | 各eventを個別に判定。restore は reason で除外され、proposal storm は起きない。rate を更に絞る必要がある場合は #55 の UI 側 policy とし、本契約では固定しない |
| 判定途中での process death | 判定は副作用を持たない（memory 追記を除く）。未完了の判定は破棄され、次の event/sweep で再判定される。partial な判定結果は存在しない |
| proposal 表示中の process death | 本契約の範囲外。run state の取り扱いは [organization-run-ux](../product/organization-run-ux.md) §5 と #55 が所有する。再開時は証拠の再評価（stale なら再判定）を要する |
| work profile の install | parent launcher が callback/broadcast を受ける。target 解決と profile identity は work user で行う |
| quiet / locked / hidden profile | 段階2で `PROFILE_INACCESSIBLE`。private space は ROLE_HOME + `ACCESS_HIDDEN_PROFILES` の下で到達可能な場合のみ判定する |
| profile 追加・削除 | 追加時の既存 app 出現は `AVAILABILITY_RETURN` 系（profile 系 event）として扱い proposal しない。削除時に当該 serial の memory を破棄（§5） |
| device restore / 移行 | restore install は reason `DEVICE_RESTORE`（まれに `DEVICE_SETUP`）で除外。memory は空で開始してよい |
| app data clear 後 | memory は空。reinstall を fresh と誤分類し得る（§11、proposal-only risk として許容） |

## 7. Session evidence policy

- session 証拠（E4）は**必須**である。default home でない期間、staged session、
  追加userへの install-existing、delivery race 等で届かない正当な状況もあるが、
  その場合は `Ambiguous(SESSION_UNAVAILABLE)` としてproposalを出さない（§3.2）。
- 入手できたsessionでは (1) install reason、(2) unarchival判定、(3) package/profile
  identityを使う。E2のprivileged reasonやE3のinstall timeが利用可能な場合は、
  sessionとの矛盾検出だけに使う。
- baseline の `SessionCommitReceiver` / `InstallSessionHelper` の promise icon  flow
  は本契約と独立にそのまま動く。organizer は同broadcast を**観測するだけ**で
  あり、baseline の queue 処理を変更・迂回しない。

## 8. Failure, stale behavior, and diagnostics boundary

- **fail-closed**: 証拠の欠落・取得失敗・矛盾はすべて `Ambiguous` に集約され、
  proposal も layout mutation も発生しない。例外を握り潰して続行しない。
- **stale**: proposal から確認までの間に install 状態・target 解決・profile が
  変わった場合は再判定（再capture）を要求する。古い判定の再利用をしない。
  run 内の revision/stale 規則は spec 13 の所有であり、本契約は判定入力の
  新鮮性だけを定める。
- **diagnostics 境界**: classification は organization run ではないため run journal
  への run event を発行しない（[organizer-diagnostics](./organizer-diagnostics.md) §3 の
  run event は run に紐づく）。判定結果は organizer logger 経由で
  `classification=<NotNew.UPDATE>` 形式の code のみを DEBUG 出力できる。
  package 名、component 名、user serial、session id は一切出力しない
  （§5 privacy、organizer-diagnostics §7 の Never 分類）。判定で proposal が
  成立し user が run を開始した時点で既存の `Trigger = INCREMENTAL_PROPOSAL`
  run event が使えるため、diagnostics 契約への追加・変更は不要である。

## 9. Adapter seam

最小の seam として、organizer integration module（`lawnchair/src/app/lawnchair/
organizer/integration/`、DESIGN §9 の目標構成）に次を置く。planner・application
の公開type は変更しない。

```text
PackageEventProvenance（moduleの対外 interface）
    classify(
        event: PackageEvent,
        sessionEvidence: SessionEvidence?,
        packageName: PackageName,
        profile: ProfileId
    ): ProvenanceClassification

SessionEvidence（domain value。Android/DB型を漏らさない）
    packageName, profile, installReason, isUnarchival, deliveryGeneration
    // session id、component、installer、raw Intentは保持・公開しない

内部実装が隠すもの
    - 既存 SessionCommitReceiver からの typed sink 接続
    - presence memory store（§5）
    - PackageManager / LauncherApps / session 証拠の query と失敗処理
    - §3.2 の判定順序とone-shot evidence lifecycle
```

- **positive event は既存 `SessionCommitReceiver` から typed sink へ渡す。**
  新しいmanifest receiverや`LauncherApps.registerCallback`をorganizer用に追加せず、
  既存receiverの`SessionInfo`検証後に、`PackageEventProvenance`へ
  `SESSION_COMMITTED`を渡す最小bridgeを#55で実装する。これによりROLE_HOME向けの
  system broadcastを一度だけ消費し、baselineのpromise-icon queue処理は維持する。
- `ModelLauncherCallbacks`の既存callbackは、update/remove/availability等のnegative
  signalと起動時inventory同期に使うが、positive triggerにはしない。`PackageUpdatedTask`
  へのhook、executor wrap、二重receiverは不採用である。判定はmodel taskとの順序に
  依存しない（E6不使用、§2.3）。
- 判定はtyped query結果とone-shot session evidenceだけから決まり、副作用はpresence
  memory追記に限る。純粋plannerと同じ検証形式（§3.4のfixture、境界値、決定性）で
  interface経由のtestができる。Android APIのPortは#55の実装でproduction/test実体に
  分離する（AGENTSの設計規約どおり、実体が必要になる場所でのみ導入する）。
- [#55](https://github.com/nunu1733/NunuLauncher/issues/55) は
  `classify` の結果が `FreshInstall` のときだけ proposal flow へ進み、以降は
  既存の planner（`IncrementalPlacement`）と spec 13 の application seam を使う。

## 10. Downstream acceptance criteria（#55 への handoff）

#55 の実装spec は次を含めること。

- **AC-P1**: `FreshInstall` は §3.2 の全段階通過でのみ生成され、各失敗段階が
  §3.1 の code で観測できる。§3.4 の全 sequence が test になっている。
- **AC-P2**: positive eventは既存`SessionCommitReceiver`からtyped sinkへ渡し、
  organizer固有のmanifest receiverや二重の`LauncherApps.Callback`登録を追加しない。
  `ModelLauncherCallbacks` / `PackageUpdatedTask` はnegative signal・inventory同期に
  限定し、callback名だけからFreshInstallを推論しない。
- **AC-P3**: presence memory について、追記のみの更新、package removeでは保持、
  profile削除時の破棄、破損時初期化、backup除外（Lawnchair ZIPとAndroid full backup
  の両方）がtestで証明される。
- **AC-P4**: launchable target が 0 件・2件以上で proposal されず、受理時の
  再解決で変化した場合に再判定される。
- **AC-P5**: session 証拠が欠落した場合は `Ambiguous(SESSION_UNAVAILABLE)` とし、
  `FreshInstall` を生成しない。sessionが存在する場合の矛盾は `Ambiguous` とする。
- **AC-P6**: process death・rebootを跨いだeventでsession配達または欠落を判定でき、
  欠落時はno proposalとなる。one-shot evidence tokenの再利用、process generation変更、
  capture時のpackage/profile/target変更は`EVIDENCE_STALE`として検証する。
- **AC-P7**: 判定log・journal・export に package 名・component 名・user serial・
  session id が出ない（negative fixture）。
- **AC-P8**: personal/work(/private) profile の同一 package が profile 別に
  判定され、quiet/hidden profile で fail-closed する。
- **AC-P9**: proposal→preview→confirm→apply は既存 seam（planner / spec 13 /
  #52 のUI）を変えず、auto-apply 経路が存在しない。

**必要な storage/API 前提（#55 実装時の環境条件）**: minSdk 26（`SessionInfo.getInstallReason`
を利用可能）、API 29+（`registerPackageInstallerSessionCallback`、未満はmanifest
`SESSION_COMMITTED` broadcastのみ）、API 35+かつarchiving flag有効時のみ
`isUnarchival`、ROLE_HOME保持時に`SESSION_COMMITTED`受信、package visibility
（既存launcherと同じ扱い）。`PackageManager.getInstallReason`はAOSPのhidden/
TestApiであり、production seamの必須前提にしない。

**blocker**: なし。#55 は #54（本決定）、#52、#57 の close 後に開始できる。

## 11. Residual risks（許容する誤分類）

いずれも「proposal + explicit confirmation がある v1」の前提で許容する。
auto-apply を将来導入する場合はこれらを再評価し、本契約を更新することが
前提である。

| 残留risk | 起きる条件 | 方向と影響 |
|---|---|---|
| 未観測install の reinstall が fresh と判定 | 最初の install→uninstall が launcher の観測外（死亡中かつ sweep 未実施）で完了した後に再install | false positive。proposal は出るが確認必須。user は却下できる |
| memory 初期化後の reinstall が fresh と判定 | app data clear、破損初期化 | 同上 |
| memory をbackup しないことに起因する空memory | 端末移行・restore 直後 | restore install 自体は reason で除外されるため、実害は「端末移行後に手動で再installした古い app」が proposal になる程度 |
| one-shot evidenceの失効 | process generation変更、古いtokenの再利用、capture時のpackage/profile/target変更 | false negative。manual flowで整理可能 |

false negative は常に manual flow で代替可能であり、fail-closed の方向である。

## 12. Exit criteria mapping

| Issue #54 のexit artifact | 本書の根拠 |
|---|---|
| evidence-source comparison and selected provenance seam | §2.3（比較と選択）、§9（seam） |
| event classification matrix（fresh install / update / replacing / restore / reinstall / availability return / remove / unavailable / ambiguous） | §3.3、§3.4（representative sequences） |
| process-death/reboot/profile/restore behavior | §6、§2.2-6 |
| minimal persistence/retention/privacy decision | §5（「no persistence」を証拠付きで却下） |
| launchable-target uniqueness and duplicate handling | §4 |
| failure/stale behavior and diagnostics boundary | §8 |
| downstream acceptance criteria for the incremental placement feature | §10（AC-P1–P9、前提、blocker なし） |

## 13. Verification of this contract

本Issue（research）の検証方法:

- **source 突合**: §2.1 の file:line を baseline commit で確認済み。
- **platform 事実の検証**: §2.2 の各事項を AOSP（main / android15-release / 旧tag）
  と SDK 36.1 の実在する定数で確認し、出典URL を残した。確認日は 2026-08-15。
- **data-flow review**: §3.2 の判定順序が §2.3 の選択した証拠だけに依存し、
  E6（in-memory model）を使わないことの確認。
- **redaction review**: §5 / §8 が [organizer-diagnostics](./organizer-diagnostics.md)
  §7 の分類と矛盾しないことの確認。

実装時の検証は #55 が所有する。本Issueでは code を変更しない。

## Change history

- 2026-08-15: Issue #54 のresearch成果物として初版。証拠源の比較と選択、
  classification matrix と判定順序、presence memory の要否判断と契約、
  target 一意規則、process death/profile/restore 挙動、diagnostics 境界、
  #55 への downstream 受入条件を定義した。
