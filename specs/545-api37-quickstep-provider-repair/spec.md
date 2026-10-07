---
issue: "#545"
status: accepted
requirements: []
updated: 2026-10-07
---

# API 37 Quickstep provider修復 — IWindowManager.createInputConsumer破壊へのcompat対応

> Status: accepted（2026-10-07。PR #548 review round 3でblocking 0・追加指摘なし・Clear
> （[review](https://github.com/nunu1733/NunuLauncher/pull/548#issuecomment-6035965725)。
> head `db6642b1a88e369cc550c28fd02734b1773b1707` を確認）。受入は本PR #548のmergeで完了する）

**Risk tier: H**（判定理由: vendored upstream code（`systemUI/shared` のAOSP由来file）への変更であり、
provider bind path（SystemUIからの起動経路でlauncher processの生存に直結する）を変える。
Launcher DB書込み・migration・model/loader bridgeは含まないが、[#545](https://github.com/nunu1733/NunuLauncher/issues/545)
前提条件のtier H相当（spec＋plan.md、execution contract）を確定採用する）。

## Problem

provider構成済み（platform `config_recentsComponentName` がLawnchairを指す）API 37 (Android 17)
環境では、SystemUIがLawnchairの `TouchInteractionService` をbindした時点で
`onUserUnlocked → InputConsumerController.registerInputConsumer` が
`NoSuchMethodError` でクラッシュし、launcher processがクラッシュループする
（#524 runtime検証2026-10-07、failure signature `a-bind-crash-signature.txt` / `b-bind-crash-signature.txt`）。

原因はAPI 37.0 frameworkのhidden API破壊である: `IWindowManager.createInputConsumer` の
旧out-param形式 `(IBinder, String, int, InputChannel) → void`（launcherがcompileするform）が
class内から削除され、新formは `(IBinder, String, int) → InputChannel`（return形式）である。
`registerInputConsumer` は `compatible` gate（`QUICKSTEP_MAX_SDK`）非依存で呼ばれるため、
この破壊はdebug/release・定数値と無関係に発生する。provider構成にはlauncherのsystem/priv-app配置が
必須であり（AOSP `LauncherProxyService.updateEnabledState()` の `MATCH_SYSTEM_ONLY`）、
QuickSwitch運用のAPI 37端末でprovider path全体が死んでいる状態である。

## Outcome

API 37 provider構成環境で `registerInputConsumer` がクラッシュせず、Lawnchairがrecents providerとして
機能する（overview表示・task切替が成立する）。hiddenapi enforcement要件が一次出力つきで確定し、
G3（`SystemUiProxy` recents遷移PIのcreator mode）判断がAPI 37実測で確定する。
修復検証の成立を前提に `QUICKSTEP_MAX_SDK` 36→37が実施され、サポート境界（min 35 / max 37）が
ADR-0018 device test matrixへ反映される。API 36およびstock構成には回帰がない。

## Owner decisions（調査結果にもとづく確定判断）

1. **対応方式は vendored `InputConsumerController.java` 内のreflection hookとする**。
   API 37+では `android.view.IWindowManager` の新form（return形式）を `getMethod` 経由で
   反射呼出しし、API 35〜36では旧formのcompile時参照をそのまま使う。
   根拠: (a) AOSP自身の修正（android17-release commit `0ef7f5a0e27f`）が同fileの呼出しを
   return形式への置換だけで済ませており、本質は「API 37では新formを呼ぶ」一点であること。
   (b) compile classpathは `framework-16.jar`（API 36。`build.gradle:436`）であり新formを
   compile時参照できないため、return形式は反射か新jarのいずれかでしか呼べないこと。
   (c) repo内にSDK gate + reflection先例が複数あること（`RecentsAnimationControllerCompat.java:98-113`、
   `ActivityManagerWrapper.java:160-171`）。
2. **compatLib V37 module・`framework-17.jar` のprebuilts追加は行わない**。
   根拠: compatLib factory seam（`QuickstepCompatFactory`）はIWindowManager系を1つも持たず、
   追加するとfactory interface拡大＋upstream file（`InputConsumerController`）へのfork compat機構の
   結合という、in-file reflectionより大きなbridgeになること。`framework-17.jar` の入手は
   AOSP tag `android-17.0.0_r1` からの `m framework` ビルド（Lawnchair upstreamの
   prebuilts/libs/README.md手順）が唯一の妥当経路であり、1メソッド呼出しのためにbuild surfaceを
   増やす価値がないこと。Sable/android-platformsの `android-37` jarはSDK-stubでhidden APIを
   含まず使用不可（2026-10-07確認）。
3. **hiddenapi enforcementは一次出力で確定する**。新formはAIDL上 `@UnsupportedAppUsage` が
   削除されており（commit `0ef7f5a0e27f` のdiff）、unlisted→unsupported tier扱いであれば
   enforcement非該当（呼出可）の見込みだが、これは検証matrix (d) のlogcat一次出力で確定するまで
   仮定に留める。**enforcement blockが観測された場合、本PRはそこで停止してfailure signatureを記録し、
   検証candidate（`quickstepMaxSdk` 36→37）をdropして確定採用しない（36維持）**（検証に先行する
   宣言の禁止。exemption lever
   —sysconfig hiddenapi-package-whitelist / `android:usesNonSdkApi` / HiddenApiBypass依存追加—
   は別判断として記録するだけで、本specでは採用しない）。
4. **WMS側の権限gateは対処不要とする**。`createInputConsumer` のserver側許可は
   `isCallerRecents(callingUid) OR android.permission.INPUT_CONSUMER` であり
   （android16/17-release `WindowManagerService.java`）、provider構成済みlauncherは
   `isCallerRecents` で通過する。#524のAPI 36 provider実績（leg (e) PASS）と整合する。
   実行時のbind成功率はmatrix (a)/(b) で観測する。
5. **G3（`SystemUiProxy.getRecentsPendingIntent` のcreator mode）は修復後のmatrix (b) 構成で
   実測して最終判断する**。BAL block evidenceが出ない場合はlegacy
   `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` の保持で確定する（コード変更なし）。
   block evidenceが出た場合はコード変更せずfailure signatureを記録し、granular mode移行は
   別Issueへ分離する（#527 spec G3節・#524暫定継続判断の引き継ぎ条件どおり）。
6. **wmshellの `PipInputConsumer`（pip/phone・pip2/phone。同一旧form呼出しを持つ）は本Issueの
   対象外とする**（#527 G6境界・vendored shell契約の維持）。WMShellのdagger graphは
   launcher processで構築されないためlauncher側から到達しないのが前提だが、matrix (a)/(b) の
   logcatからwmshell起源の `NoSuchMethodError` を監視し、観測された場合はfailure signatureを
   記録して別Issueへ分離する（本PRではwmshellを変更しない）。
7. **`QUICKSTEP_MAX_SDK` 36→37は「検証candidateへの包含」と「確定採用」を分離して扱う**。
   matrix (b) はmaxSdk 37のrelease APKを要求するため、定数変更を **検証candidate commit** として
   compat修正後に適用し、そのcandidate SHA固定で (b) 以降を実施する。全matrix成立時にのみ
   candidateを最終成果物として保持し、ADR-0018 revision 9（Decision 7のadvertised range 35..37）へ
   反映する。matrix (b)（または AC-3/AC-4/AC-5 のoracle）が成立しない場合はcandidate commitを
   drop/revertして `quickstepMaxSdk` 36を維持し、failure evidenceを記録する
   （検証に先行する宣言の禁止は「確定採用」に対して働く。検証artifactへの包含はこれに当たらない）。
8. **provider bind後のTaskbar初期化で実測された第二のAPI 37.0破壊
   （`DesktopExperienceFlags.ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX` の `NoSuchFieldError`）も
   本Issueのprovider修復範囲に含め、当該flag参照1箇所に最小のdegrade guard（`NoSuchFieldError`
   catch → throttle無効として継続）を追加する**。根拠: (a) 2026-10-07検証（matrix (a) 中間証跡
   `a-resource-skew-analysis.txt` / `a-bind-crash-check.txt`）で、createInputConsumer修復後に
   `TouchInteractionService.onUserUnlocked:889 → TaskbarManager.onUserUnlocked` 経由で当該field参照が
   毎回クラッシュし、provider bind完了oracle（isConnected=true・overview）が到達不能であることが実測された。
   (b) 当該pathはprovider構成時のみ実行されるため、QuickSwitch構成の実機Android 17.0端末でも
   同一クラッシュが起こる（#545の目的である「provider修復」を完遂するために必要）。
   (c) taskbar生成は `ENABLE_TASKBAR_NAVBAR_UNIFICATION` 強制でruntime switch不存在が実測済みであり
   （navigation mode・aconfig・RROのいずれでも回避不能。同分析file）、検証環境の工夫では回避できない。
   (d) fieldはcompile classpathのframework-16.jarには存在するがAPI 37.0 imageのframework.jarからは
   削除済み（dexdump実測）であり、`Utilities.ATLEAST_BAKLAVA_1`（SDK_INT_FULL>=3600001）ガードでは
   37.0 imageを除外できない。guard後の挙動（throttle無効）は、当該flagが存在しないimage上の
   自然な挙動である。(e) 変更は1 file 1箇所の最小bridgeであり、#545参照commentを近傍に残す。
   本判断の一次証跡（diagnostic runの実行SHA/APK sha256/AVD fingerprint、crash stack、
   field有無対照、seam oracle出力、runtime回避不能の実測）は実装branch
   `issue-545-api37-provider-fix` commit `f5a3977281`（
   https://github.com/nunu1733/NunuLauncher/tree/f5a39772819eb0a958db8f27d777292e18bc7fcb/docs/assessment/545-api37-provider-fix-evidence ）と
   `e70a58c1fd`（field/resource一次対照 `pre-guard-field-and-resource-contrast.txt`）に固定する。
9. **第三の破壊（resource-ID skew: `dimen/taskbar_phone_size` のbaked framework ID不一致）は
   test-rig限定ではなく実機でも到達する実破壊と分類し、本Issue内で修正する**
   （`res/values/dimens.xml:437` の `@*android:dimen/navigation_bar_frame_height` 参照を
   literal `48dp` へ置換。#545参照comment付き）。根拠: (a) compileSdk 37.2でlinkしたAPKは
   framework resource ID `0x01050283` をbakeするが、当該IDの解決先はlevel毎に異なる
   （一次出力: `api36-image-framework-res-contrast.txt` / `pre-guard-field-and-resource-contrast.txt` §2）:
   SDK 37.2 tableでは `navigation_bar_frame_height`（→ `@navigation_bar_height` → 48dp）だが、
   API 37.0 imageでは `navigation_bar_height_portrait`（default config値なし）となりphone
   profileの `TaskbarStashController` 初期化（provider bind後のみ実行）が
   `Resources$NotFoundException` でクラッシュする。
   (b) **API 36 imageでは当該IDは `notification_2025_action_list_min_height`（約10dp）へ
   静かに誤解決している**（実imageのframework-res.aatp dump一次出力。leg (e)のoracleは
   bind/overview/task切替で成立していたがgeometryは検証していなかった。pre-guard diagnosticの
   `a-resource-skew-analysis.txt` の記載が正しく、本addendum初版の「36でも48dp解決」という
   推論は誤りとして撤回する）。したがってliteral 48dp化は **API 36では挙動不変ではなく
   意図した修復（10dp誤解決 → 仕様値48dpへの正規化）** であり、matrix (e) で
   native-density screenshot＋overview/task切替に加え、この幾何正規化をrecordする。
   (c) ID解決先のlevel間差異は再発し得るため、baked IDに依存しないliteral化が最も破壊抵抗が高い。
   値48dpは全levelの `navigation_bar_frame_height` → `@navigation_bar_height` dereference先と
   一致する（sw900dp 56dp variantはtablet経路のreaderから到達しない）。
   (d) `wm density 280` のaccommodationは **superseded pre-guard diagnostic run限定** であり
   （reversible。diagnostic runはOwner decision 8/9の証跡収集が目的）、**最終検証matrixはnative
   density（override無し）で実施し**、本修正が意図するphone profile経路をそのまま検証する。
   density変更は最終matrixの手順に含めない。

## Baseline（本specの前提事実）

行番号はすべて `e214b7b19019e0efd2f28f53bf8b56f0b3510b76`（`issue-524-api37-quickstep` head、
PR #546。#524検証証跡とADR-0018 revision 8を含む）固定。実装branchはこのcommitから分岐する
（base PRは `issue-524-api37-quickstep`。ADR-0018 Decision 3/8によりPhase 4 cutoverまでmergeしない）。

- クラッシュ行: `systemUI/shared/src/com/android/systemui/shared/system/InputConsumerController.java:143`
  （`registerInputConsumer()` 内の旧form呼出し。同fileはAOSP素の状態で、main側v15期に存在した
  QuickSwitch由来の `hookDestroyInputConsumer` reflection hookは16-dev stackには存在しない）。
  `unregisterInputConsumer` は `destroyInputConsumer(mToken, DEFAULT_DISPLAY)`（旧formのまま
  API 37に存在、dexdump実測ではcreateInputConsumerのみ消失。クラッシュstackもdestroy通過後の
  create行で発生）のみを呼び、修正対象外。
- 呼出経路: `TouchInteractionService.java:778`（`getRecentsAnimationInputConsumer()` 生成、
  リポジトリ内唯一の生成箇所）→ `:873`（`onUserUnlocked` から `registerInputConsumer()`。
  クラッシュstackと一致）→ `:978`（`onDestroy` から `unregisterInputConsumer()`）。
- gate: `lawnchair/src/app/lawnchair/LawnchairApp.kt:74`（`compatible`、
  `QUICKSTEP_MIN_SDK..QUICKSTEP_MAX_SDK`）。`registerInputConsumer` はgate非依存。
- 定数: `build.gradle:185-186` `quickstepMinSdk = "35"` / `quickstepMaxSdk = "36"`、
  `:203-206` でbuildConfigFieldとmanifestPlaceholdersへ反映、`:267-272` でdebugのみ0/100000へoverride。
- compile classpath: `build.gradle:436` `addFrameworkJar('framework-16.jar')`（API 36 jarを
  classpath先頭へ。`android.view.IWindowManager` はこのjarから解決される。repo内に
  IWindowManager source stubなし、`hidden-api` moduleにもなし）。
  `framework-16.jar` の `Build.VERSION_CODES` は `BAKLAVA`（=36）までを含み
  `CINNAMON_BUN`（=37）を含まないため、API 37 gateはcompile時定数を使えない
  （リテラルまたは `> BAKLAVA`。具体形はplan.mdで確定）。
- G3対象: `quickstep/src/com/android/quickstep/SystemUiProxy.kt:188-198`
  （`getRecentsPendingIntent`。creator側 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`）。
- 第二破壊の対象（Owner decision 8）: `quickstep/src/com/android/launcher3/taskbar/TaskbarRecentAppsController.kt:67-76`
  （`enableRecentTasksThrottle`。`Utilities.ATLEAST_BAKLAVA_1`（`Utilities.java:168`、
  `SDK_INT>=BAKLAVA && SDK_INT_FULL>=3600001`）ガード下で
  `DesktopExperienceFlags.ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX`（framework-16.jarの
  `android/window/DesktopExperienceFlags.class` に存在。API 37.0 imageのframework.jarからは削除済みを
  dexdump実測）を参照。provider bind → `TouchInteractionService.onUserUnlocked:889` →
  `TaskbarManager.onUserUnlocked` で毎回実行され、37.0 image上で `NoSuchFieldError` クラッシュ。
- 第三破壊の対象（Owner decision 9）: `res/values/dimens.xml:437`
  （`<dimen name="taskbar_phone_size">@*android:dimen/navigation_bar_frame_height</dimen>`。
  compileSdk 37.2でID `0x01050283` をbake。当該IDの解決先はlevel毎に異なる: 37.0 imageでは
  `navigation_bar_height_portrait`（default config値なし）でphone profileが
  `Resources$NotFoundException`、API 36 imageでは `notification_2025_action_list_min_height`
  （約10dp）へ静かに誤解決（一次出力 `api36-image-framework-res-contrast.txt`）。
  37.2 tableでは `navigation_bar_frame_height`（→48dp）。同file `:441` の
  `rounded_corner_content_padding` も `@*android:dimen` 参照だが失敗経路に未到達のため
  本Issueでは触れない）。
- ADR-0018 revision 8（Decision 7: advertised 35..36維持・maxSdk 37保留・provider構成検証方法論）
  が前提であり、修復検証成立時にrevision 9で更新する。Decision 8（rebase差分への混入禁止）のとおり
  実装はrebase branch stack上に置く。
- provider構成手段・検証環境は#524と同一: root化API 37/36 google_apis emulator
  （AVD `issue526_api37_pixel_9a` / `issue142_api36`、実在）＋variant別RRO overlay
  （debug用 `app.lawnchair.debug/com.android.quickstep.RecentsActivity`、release用
  `app.lawnchair/com.android.quickstep.RecentsActivity`）＋priv-app配置
  （`/product/priv-app/` ＋privapp-permissions allowlist XML。手順は
  #524証跡 `docs/assessment/524-api37-quickstep-evidence/README.md` §2/§4）。

## Benchmark

対象課題: B1〜B7（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標: **B1〜B7の増分ゼロ**。本変更はprovider bind pathのクラッシュ修復であり、編集・適用・復帰の
各flowに新規の中断要因を追加しない。通常構成（provider未構成）の端末では挙動が変わらず、
provider構成環境では既存のquickstep機能がAPI 37で再び成立するだけであるため、
新たなベンチマーク課題の起票は不要と判断する（#524と同じ判断）。

## Prior art

- AOSP framework commit `0ef7f5a0e27f`「Return InputChannel from createInputConsumer」
  （android17-release。https://android.googlesource.com/platform/frameworks/base/+/0ef7f5a0e27f7270d1b6282176aa5b6be660bd1e 。確認日2026-10-07）。
  採用: 破壊の一次根拠（in-place置換・旧form削除・`@UnsupportedAppUsage` 削除）と
  「return形式を呼ぶ」修正shapeの根拠。main branchには未入る（API 37系統のみ）。
- AOSP `InputConsumerController.java` @ commit `0ef7f5a0e27f`（
  https://android.googlesource.com/platform/frameworks/base/+/0ef7f5a0e27f7270d1b6282176aa5b6be660bd1e/packages/SystemUI/shared/src/com/android/systemui/shared/system/InputConsumerController.java 。確認日2026-10-07）。
  採用: 上流での修正後shape（`inputChannel = createInputConsumer(token, name, display)`）の一次根拠。
  AIDL変更と上流call-site修正を同一snapshotで確認できる。
- repo内reflection先例: `RecentsAnimationControllerCompat.java:98-113`（SDK gate +
  `getDeclaredMethod`）、`ActivityManagerWrapper.java:160-171`（exact SDK gate +
  reflection、fallback直呼出し）。確認日2026-10-07。採用: in-file reflection hookのrepo内慣習根拠。
- Lawnchair upstream prebuilts/libs/README.md（16-dev。framework jarはAOSP tagからの
  `m framework` turbine artifact）。確認日2026-10-07。採用: framework-17.jarが必要になった場合の
  唯一の入手方法の記録。本specでは不採用（Owner decision 2）。
- HiddenApiBypass（LSPosed、v6.1。https://github.com/LSPosed/AndroidHiddenApiBypass 。確認日2026-10-07）。
  不採用: 依存追加は本specの範囲外（Owner decision 3）。enforcement block実測時の対処候補として
  記録するのみ。
- ExpressiveLauncher issue #24（同一クラッシュの実機報告。2026-09-27）と同projectの
  graceful degradation fix。確認日2026-10-07。部分採用: lookup失敗時にクラッシュしない方針のみ
  採用（本spec Scenario 4）。主oracleは修復であり、degradationを修復の代わりにしない。
- #524検証証跡 `docs/assessment/524-api37-quickstep-evidence/`（PR #546）。
  確認日2026-10-07。採用: クラッシュsignature・両API dexdump対照・priv-app/overlay検証手順の
  正本として全matrixで再利用。

## Behavior scenarios

### Scenario: provider構成済みAPI 37でクラッシュせずoverviewが成立する

Given root化API 37 emulatorへvariant用RRO overlayをpreinstallし、固定headの
Lawnchair debug（またはrelease）buildを `/product/priv-app/` へ配置し、
run毎preflight（`cmd overlay lookup` とSystemUI `mRecentsComponentName` が
実行中buildのcomponentと一致）を通過させている
When SystemUIが `TouchInteractionService` をbindし（user unlock後）、recents gesture
（`KEYCODE_APP_SWITCH` 等）を発火する
Then `registerInputConsumer` でクラッシュせず、`LauncherProxyService.isConnected=true` となり、
overview表示・task切替が破綻なく成立する。#524のfailure signature
（`NoSuchMethodError ... createInputConsumer(...InputChannel;)V`）は再現しない。

### Scenario: API 37.0 imageでTaskbar初期化がクラッシュせずprovider bindが完了する

Given API 37.0 image（`ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX` がframework.jarから削除済み、
`taskbar_phone_size` のbaked IDが別resourceへ移動済み。一次対照
`pre-guard-field-and-resource-contrast.txt`）でprovider構成済みLawnchairがbindされ、
`TouchInteractionService.onUserUnlocked` からTaskbar初期化に到達する
When `TaskbarRecentAppsController` が `enableRecentTasksThrottle` を評価し、
`TaskbarStashController` が `R.dimen.taskbar_phone_size` を解決する
Then `NoSuchFieldError` をcatchしてthrottle無効（false）として継続し、`taskbar_phone_size` は
literal値（48dp）として解決し、launcher processはクラッシュしない。provider bind完了
（`isConnected=true`）に到達する。flag guardはflagが存在する環境（API 36等）で既存の挙動を
変えない。dimen literal化はAPI 36では意図した正規化（`notification_2025_action_list_min_height`
約10dpへの静かな誤解決 → 仕様値48dp）であり、API 36 matrix (e) で幾何正規化をrecordする。

### Scenario: API 36では旧formのcompile時参照が継続し回帰がない

Given API 36 emulatorへdebug用overlay＋priv-app配置をした同一構成がある
When 修正後build（SDK gateはAPI 36で旧form側の分岐を通る）でprovider構成動作を行う
Then API 36 provider pathの機能（bind成功・overview成立・task切替）に回帰がない
（#524 leg (e) と同等の結果）。

### Scenario: reflection lookup失敗時はクラッシュせずdiagnosticを残す

Given API 37+環境で何らかの理由により `IWindowManager.createInputConsumer` のreturn形式が
runtimeに存在しない（将来のAPI進化等。本matrixでは発生しない想定外系）
When `registerInputConsumer` が呼ばれる
Then `NoSuchMethodException` 等をcatchしてERROR log（#545参照付き）を残し、input consumerを
未登録のままlauncher processを生存させる（provider bindのクラッシュループを起こさない）。
log以外の永続状態変更はない。

### Scenario: hiddenapi enforcement blockが観測された場合は停止して記録する

Given matrix (a)/(b) のrunで、registerInputConsumer実行時のlogcatにhidden API accessの
denial/block（`Accessing hidden ... blocked` 系）が出た
When 検証結果を判定する
Then failure signatureを一次出力つきで記録し、検証candidate（`quickstepMaxSdk` 36→37）を
dropして確定採用せず（36維持）、spec/Issueへ未達を記録する（検証に先行する宣言の禁止）。exemption対処は本PRでは行わない。

### Scenario: wmshell起源のクラッシュが出た場合は本PRでは触れず分離する

Given matrix (a)/(b) のlogcat監視で `PipInputConsumer`（wmshell）由来の `NoSuchMethodError`
がlauncher processで出た
When 該当signatureを確認する
Then failure signatureを記録して別Issueへ分離し、本PRはwmshellを変更しない
（G6境界。launcher到達性の前提が崩れた場合の追跡起点とする）。

### Scenario: 通常構成とAPI 36/37 stock構成で無影響

Given overlay無し（stock `config_recentsComponentName`、google_apis Pixel imageでは
`com.google.android.apps.nexuslauncher/...`）のAPI 36/37 emulatorへ修正後buildを
通常installしHOMEに設定する
When 通常操作（home・recents gesture・default HOME設定）を行う
Then 挙動変化がなく（Lawnchair側quickstepは無効のまま、"disabling recents" 診断logのみ、
`quickstep_incompatible` sheet非表示）、system側overviewはstock providerで継続する。

## Data and state

- None。Launcher DB・preferences・recovery storeへの読書きなし。本変更は状態を持たない
  （bind時のin-memory channel獲得方法の切替のみ）。

## Permissions, privacy, and security

- None。新規permission・外部通信・sensitive data扱いの追加なし。
  reflection対象は自process内の既存bindに使われる `IWindowManager` 呼出しの形式追従であり、
  権限modelの変更はない（WMS側gateはOwner decision 4のとおり既存の `isCallerRecents`）。

## Accessibility and localization

- UI変更・文字列追加なし。`quickstep_incompatible` sheetは既存UIのまま（matrix (b) での
  非表示確認のみ）。

## Acceptance criteria

- [ ] AC-1: matrix (a)（API 37 debug＋debug用overlay＋priv-app）で、bind後にクラッシュせず
  `isConnected=true`、overview成立・task切替成功。#524 failure signature非再現。
- [ ] AC-2: matrix (b)（API 37 release（maxSdk 37の **検証candidate build**）＋release用overlay＋
  priv-app）で `compatible=true`、overview成立・task切替成功、`quickstep_incompatible` sheet非表示。
- [ ] AC-3: hiddenapi enforcementの一次出力（(a)/(b) runのlogcat。取得可能なら
  `hiddenapi list` 等のflags一次出力）がevidenceへ記録され、新formの呼出可否が確定している。
  block時はScenario 4どおり停止・記録しcandidateをdropしている。
- [ ] AC-4: G3の確定観測（(b)構成でshell→launcher遷移を発生させ、logcat
  `ActivityTaskManager` のBAL block有無を確定判定）が記録され、creator mode判断
  （evidence無し→legacy保持確定／blockあり→別判断分離）がPRへ記載されている。
- [ ] AC-5: matrix (e)（API 36 debug＋debug用overlay＋priv-app）でprovider path機能
  （bind・overview成立・task切替）に回帰がなく、matrix (f)（stock構成API 36/37）で挙動変化がない。
  ただしmatrix (e) では `taskbar_phone_size` の幾何正規化（10dp誤解決 → 48dp。Owner decision 9(b)）
  が意図した変化としてnative-density screenshot付きでrecordされる。
- [ ] AC-6: `QUICKSTEP_MAX_SDK` 36→37が **検証artifact（(b)以降のrelease build）には含まれ**、
  全matrix成立時にのみ最終成果物として保持される。保持されたcandidateについてrelease buildの
  manifest placeholderが `35 / 37` となる静的確認（aapt2 dump等）が記録されている。
  不成立時はcandidateをdropし、36維持とfailure evidenceを記録する。
- [ ] AC-7: ADR-0018 revision 9（Decision 7: advertised 35..37、revision 8保留判断の更新）
  が実装PR内で反映されている（candidate不成立時はrevision 9を適用しない）。
- [ ] AC-8: 調査結果（AOSP変更の一次出力URL/commit、対応方式判断と根拠、framework-17.jarの
  不要判断と必要時の入手方法、hiddenapi要件の実測）がspec/PR/evidenceに記録されている。
- [ ] AC-9: evidence READMEと実装PR packetへ **matrixごとの実行SHA**（(a): compat修正3 commits適用後head、
  (b)〜(f): candidate commit）と使用APK（(b)/(f): candidate release、(e): candidate debug）の
  対応、およびAPKごとのsha256が明記され、artifactとSHAの取り違えが起きない。
- [ ] AC-10: matrix (a) で `TaskbarRecentAppsController` の `NoSuchFieldError`
  （`ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX`）と `Resources$NotFoundException`
  （`taskbar_phone_size`）の **いずれも出ず**、native densityのままbind完了
  （`isConnected=true`）へ到達する。flag guardのdiffはcode reviewで、API 36で挙動が変わらないこと
  （`ATLEAST_BAKLAVA_1` false経路の不変）が確認される。dimen literal化のdiffは、36 imageでの
  誤解決先（`api36-image-framework-res-contrast.txt`）と37.2 dereference先（48dp）への一次出力
  つきでreviewされ、「API 36での10dp→48dpは意図した正規化である」ことが確認される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | matrix (a) runtime証跡（preflight＋logcat＋screenshot/録画。evidence dir `docs/assessment/545-api37-provider-fix-evidence/`。実行SHA: compat修正commit） |
| AC-2 | matrix (b) runtime証跡（同上＋sheet非表示screenshot。実行SHA: candidate commit） |
| AC-3 | (a)/(b) logcatのhiddenapi観測抜粋＋取得可能なflags一次出力file |
| AC-4 | (b)構成のG3 logcat抜粋＋PR本文のcreator mode判断記載 |
| AC-5 | matrix (e)/(f) runtime証跡（実行SHA: candidate commit） |
| AC-6 | candidate release buildのmanifest placeholder静的確認出力（不成立時はdrop記録） |
| AC-7 | ADR-0018 diff（実装PR内。candidate成立時のみ） |
| AC-8 | spec（Owner decisions/Prior art）＋実装PR本文の調査記録節 |
| AC-9 | evidence READMEのmatrix↔SHA↔APK対応表（sha256含む。**pre-guard diagnostic run（Owner decision 8/9の証跡。commit `f5a3977281` / `e70a58c1fd`）を最終matrixと混同しない別行で記載**）＋packet記載 |
| AC-10 | matrix (a) logcat（Taskbar NoSuchFieldError / Resources$NotFoundException 0件・bind完了）＋guard/dimen diff（code review） |

新規の永続testは追加しない（test-audit判断: クラッシュは「実機frameworkのAPI 37で旧formが
消失すること」自体が原因であり、JVM/Robolectricでは再現不能。振る舞いの一次証拠は
provider構成runtime matrixであり、定数assert等の低価値testを追加しない。#524と同じ判断）。
修正を先に失敗させるテストの代替として、(a)/(b)実施前に **修正前headで#524 failure signatureが
再現する状態**（#524証跡。同一AVD）を対照に置く。既存unit/instrumentation gateはgreenで通す。

## Verification

- **実施順序（candidate model。Owner decision 7/8/9）**: ①`InputConsumerController` reflection commit →
  ②Taskbar flag guard commit（Owner decision 8）→ ③`taskbar_phone_size` dimen literal化 commit
  （Owner decision 9）→ build debug → ④matrix (a)（実行SHA=③head。debug APK。**native density**
  ・`wm density reset` 済み）→ ⑤`quickstepMaxSdk` 36→37を **candidate commit** として
  適用し **debug/release両方のAPKをbuild** → ⑥matrix (b)/(f) はcandidate **release** APK、
  matrix (e) はcandidate **debug** APK、matrix (d) とG3は(b)構成で実施
  （実行SHA=candidate commit）→ ⑦全成立ならcandidate保持＋ADR-0018 rev 9。
  不成立ならcandidate drop（36維持）＋failure evidence記録。
  **既存branchのreconciliation（revert方式）**: 現在のcandidate commit `e2fe6f80df`
  （`quickstepMaxSdk` 36→37）は証跡commit（`f5a3977281` / `e70a58c1fd` / `206850b3d7`）の祖先に
  あるため、drop/resetは行わず **`e2fe6f80df` の差分を明示revertしてtreeを36へ戻す**。その後
  ②③→matrix (a)→新candidate commitの順へ進める。旧candidateとrevertのpairはsuperseded historyと
  してbranch上に保持し（証跡commitの到達可能性を維持）、旧candidateのbuild artifact
  （`/tmp/545-evidence-apk/candidate-*`）とそのcommit messageの「matrix (a) provider repair is
  confirmed」記述は **superseded（acceptanceには不使用）** としてevidence READMEへ記録する。
  中間証跡（①commit単体時点の2026-10-07 diagnostic run。実行SHA `416273ce2f`、debug APK sha256
  `4c091d8d0b…`、AVD `issue526_api37_pixel_9a`、wm density 280）はcommit `f5a3977281` /
  `e70a58c1fd` / `206850b3d7` に固定し、AC-9対応表の別行「pre-guard diagnostic run」として
  最終matrixと混同しない。
- **matrix（provider path主証跡。構成手段・preflightは#524 README §2/§4を再利用）**:
  - (a) API 37 debug @ compat修正commit＋debug用overlay＋priv-app → クラッシュ無し、
    `isConnected=true`、overview成立、task切替成功。
  - (b) API 37 release @ candidate commit（maxSdk 37）＋release用overlay＋priv-app →
    `compatible=true`、overview成立、sheet非表示、task切替成功。
  - (d) hiddenapi一次出力: (a)/(b)のregisterInputConsumer実行時間帯のlogcatを取得し
    hidden API access系のdenial/block有無を判定。可能ならroot shellで
    `hiddenapi list`（またはflags table）から該当signature行を一次取得する。
  - (e) API 36 debug＋debug用overlay＋priv-app → 回帰なし。
  - (f) stock構成（overlay無し）API 37＋candidate release → 無影響。**API 36 stockの簡易確認も
    candidate releaseで実施する**（通常install→HOME設定→system overview継続・launcher crash無し・
    gate/sheet状態の記録。#524 (d)-API36相当の観測を今回の修正後buildで再取得する）。
  - wmshell監視: (a)/(b)のlogcat全体から `PipInputConsumer` 起源の例外をgrep（0件確認または
    failure signature記録）。
- 観測手段: 操作結果、logcat、screenshot/録画をevidence dirへ保存しPRへ要約する。
  **evidence READMEにはmatrixごとの実行SHAの対応表を必ず記載する**（AC-9）。
- **G3**: (b)構成を主証跡としてScenario「G3確定観測」を実施する（#527 spec G3節の委譲条件の完結）。
- **実機の位置づけ**: provider構成は非root実機では不可のため、主証跡はemulator matrix
  （#524と同じ制約。実機は通常構成の無影響確認をEpic #516 Phase 3実機matrixへ引き継ぐ）。
- **tier Hの計測要求**: `build.gradle` は上流由来fileのため、実装PR本文へ
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  の計測結果をreportする。
- **branch/merge**: 実装branch `issue-545-api37-provider-fix` は
  `e214b7b19019e0efd2f28f53bf8b56f0b3510b76` から分岐し、base PR `issue-524-api37-quickstep` に
  stackする。ADR-0018 Decision 3/8によりPhase 4 cutoverまでmergeしない。

## Open questions

- なし（accepted時点）。hiddenapi要件とG3は「仮定せず検証で確定する」ことをscenario/evidenceで
  固定済みであり、実装開始前に解消が必要な問いはない。

## Change history

- 2026-10-07: Draft created for #545（入力: #524 runtime検証結果（PR #546証跡）、
  AOSP android17-release commit `0ef7f5a0e27f` 調査、hiddenapi enforcementソース調査、
  repo内compat先例調査）。
- 2026-10-07: Review round 1（PR #548コメント。blocking 1・中1・低1）対応 —
  (1) `QUICKSTEP_MAX_SDK` 36→37を「検証candidateへの包含」と「確定採用」に分離
  （Owner decision 7改訂、実施順序をcandidate modelへ変更、AC-2/AC-6をcandidate前提へ修正）し、
  matrixごとの実行SHA明記をAC-9として追加。(2) matrix (f)へAPI 36 stock簡易確認を明記。
  (3) Prior artのAOSP `InputConsumerController` 参照をcommit固定URLへ修正。
- 2026-10-07: Review round 2（PR #548コメント。中1・低1、blocking無し）対応 —
  (1) candidate commit適用後のbuild対象をdebug/release両方へ明記し、(e)はcandidate debug APK、
  (b)/(f)はcandidate release APK使用へ固定（AC-9へAPK対応とsha256記録を追加）。(2)
  Owner decision 3・Scenario 4等の要約文言をcandidate modelへ同期（block時はcandidate drop・
  確定採用しない）。
- 2026-10-07: Addendum（検証中間証跡にもとづくOwner decision 8追加）— matrix (a) 中間実施で
  createInputConsumer修復のseam oracle成立を確認したうえで、第二の破壊
  （`ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX` NoSuchFieldError。provider bind後のTaskbar初期化。
  API 37.0 imageから当該field削除を実測、taskbar生成のruntime回避不能も実測）を発見。
  当該flag参照1箇所のdegrade guardを本Issue範囲へ追加（AC-10・新Scenario・実施順序②へ反映）。
  resource-ID skewは `wm density 280` accommodationで対処（source変更不要）を記録。
- 2026-10-07: Addendum round 1（PR #549コメント。blocking 2・中1・低1）対応 —
  (1) Owner decision 8の一次証跡を実装branch commit `f5a3977281` / `e70a58c1fd` に固定参照し、
  実行SHA/APK sha256/AVD fingerprint/crash stack/field有無対照/seam oracle/runtime回避不能実測を
  packetへ記載。AC-9対応表へ「pre-guard diagnostic run」別行を追加。(2) resource-ID skewを
  test-rig限定から **実機17.0で再現する実破壊へ再分類**（値が全API levelで48dpに同一であることの
  aapt2実測にもとづく）し、Owner decision 9（`taskbar_phone_size` をliteral 48dpへ。#545参照
  comment付き）として追加。最終matrixはnative densityで実施し、density 280はsuperseded
  diagnostic run限定へ。(3) branch reconciliation手順（旧candidate `e2fe6f80df` のdrop・組み直し・
  superseded記録）を実施順序へ追加。(4) plan Statusをrevision 2 proposed表記へ修正。
- 2026-10-07: Addendum round 2（PR #549コメント。blocking 1・中2）対応 —
  (1) 実API 36 imageのframework-res.aapt2 dump一次出力（`api36-image-framework-res-contrast.txt`、
  実装branch commit `206850b3d7`）により `0x01050283` は36 imageでは
  `notification_2025_action_list_min_height`（約10dp）への静かな誤解決と確定。初版の
  「36でも48dp解決」推論（`pre-guard-field-and-resource-contrast.txt` §2のAPI 36段落）を撤回・
  訂正し、`a-resource-skew-analysis.txt` の記載が正しかったことを明記。Owner decision 9 /
  Scenario / AC-5 / AC-10を「API 36では10dp→48dpの意図した正規化」へ改訂し、matrix (e) へ
  幾何正規化のrecord（native-density screenshot＋overview/task切替）を追加。(2) reconciliationを
  drop/resetから **revert方式** へ変更（証跡commitの到達可能性を維持）。(3) PR本文をheadの正本へ
  同期。
