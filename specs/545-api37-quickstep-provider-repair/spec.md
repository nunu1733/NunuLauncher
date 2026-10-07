---
issue: "#545"
status: accepted
requirements: []
updated: 2026-10-08
---

# API 37 Quickstep provider修復 — IWindowManager.createInputConsumer破壊へのcompat対応

> Status: accepted（2026-10-07。PR #548 review round 3でblocking 0・追加指摘なし・Clear
> （[review](https://github.com/nunu1733/NunuLauncher/pull/548#issuecomment-6035965725)。
> head `db6642b1a88e369cc550c28fd02734b1773b1707` を確認）。受入は本PR #548のmergeで完了する。revision 2/3 addendum（PR #549/#551）の説明はChange history参照。Spec status は本ブロックが正とし、addendum（Owner decision 8/9/10）は受入済み。)

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
   値48dpは確認対象level（SDK 35/37.2 table、image 36/37.0）の `navigation_bar_frame_height` →
   `@navigation_bar_height` dereference先と一致し（API 35は静的table確認のみ。`api36-image-
   framework-res-contrast.txt` §3'。runtime matrixは追加しない。sw900dp 56dp variantはtablet
   経路のreaderから到達しない）。API 35でも現APKのbaked IDは別resource
   （`notification_right_icon_headerless_margin` 20dp）へ誤解決するため、literal化は
   quickstepMinSdk=35の範囲全体で意図した値を復元する。
   (d) `wm density 280` のaccommodationは **superseded pre-guard diagnostic run限定** であり
   （reversible。diagnostic runはOwner decision 8/9の証跡収集が目的）、**最終検証matrixはnative
   density（override無し）で実施し**、本修正が意図するphone profile経路をそのまま検証する。
   density変更は最終matrixの手順に含めない。
10. **post-guard検証で実測された第四のAPI 37.0破壊（`KeyButtonRipple` の
   `android.companion.virtualdevice.flags.Flags` クラス欠損による `NoClassDefFoundError`）も
   本Issueのprovider修復範囲に含め、当該flag読取り1箇所にdegrade guard
   （**`NoClassDefFoundError` のみをcatch** → `ViewConfiguration.getTapTimeout()`
   旧挙動へfallback。直接参照式はchecked例外を送出しないため
   `ClassNotFoundException` をcatchに含めるとcompile不能）を追加する**。根拠: (a) post-guard最終matrix (a)（2026-10-07、実行SHA
   `63a8a3a7d5`、native density）で、Owner decision 8/9修正後に
   `SystemUiProxy` 側のTaskbar初期化へ到達したうえで、
   `systemUI/shared/src/com/android/systemui/shared/navigationbar/KeyButtonRipple.java:108`
   （`SDK_INT_FULL >= 3600001` ガード下で `Flags.viewconfigurationApis()` を呼ぶ）が
   API 37.0 imageで毎回 `NoClassDefFoundError` → provider bind完了oracleが再び未達
   （`ma-keybuttonripple-flags-class-check.txt` / `ma-crash-check.txt`）。
   (b) 当該クラスは37.0 imageのframeworkで `com.android.internal.hidden_from_bootclasspath`
   名前空間にのみ存在し、app-visibleな `android.companion.virtualdevice.flags.Flags` は
   class descriptor実測で0件。API 36 imageでは同hidden名前空間はあるが
   `SDK_INT_FULL=36.0` でgate falseのため参照しない（API 36は性格的に不変）。
   (c) fallback値は修正のない旧挙動（`ViewConfiguration.getTapTimeout()`）であり、flag
   （ck ViewConfiguration一部APIの移行）が参照できても得られる差分は小さい。
   (d) 変更は1 file 1箇所の最小bridge（Owner decision 8と同型）。
   本post-guard matrixの全証跡は実装branch commit **`fc5169566c`** に固定（
   https://github.com/nunu1733/NunuLauncher/tree/fc5169566c6782b3e3f30c1deec58a37e667fe0f/docs/assessment/545-api37-provider-fix-evidence 。diagnostic run provenance:
   (a) 実行SHA `63a8a3a7d5`＋debug APK sha256 `bed40262614f…`、(b)(e)(f) 実行SHA
   `2987e525bd`＋candidate2 APK sha256（release `c198865852ed…`/debug `5098ceb4d7af…`）、
   AVD `issue526_api37_pixel_9a` / `issue142_api36`（fingerprintは各preflight fileへ記録済み）。
   具体file: `ma-crash-check.txt`（crash stack + 3修正分signature 0）・
   `ma-keybuttonripple-flags-class-check.txt`（class descriptor対照＋`SDK_INT_FULL` 実測）・
   `me-apk-dimen-check.txt`（48dp pin静的確認）・`d-hiddenapi-logcat-post.txt`/`g3-bal-check-post.txt`）。
   (e)/(f)/hiddenapi/wmshell のPASS結果は本修正で変わり得ない（pathはAPI 37のTaskbar初期化のみ）
   ため、第10 decision適用後の再検証は (a)/(b)+G3 のみを対象とする。
11. **qa検証（2026-10-08、実行SHA `68e68a6a45`）で実測された第五のAPI 37.0破壊
   （`ActivityManagerWrapper.getTaskThumbnail` の `IActivityTaskManager.getTaskSnapshot(int, boolean)`
   削除による `NoSuchMethodError`）も本Issueのprovider修復範囲に含め、当該呼出し1箇所へ
   **API 37+で `android.window.TaskSnapshotManager` 経由のreflection呼出し**を追加する
   （lookup失敗時は既存のnull経路どおり空 `ThumbnailData` へdegrade。crashしない）**。根拠:
   (a) 一次対照 `gettasksnapshot-class-contrast.txt`（実装branch commit `1777e348c9`）:
   compile classpath（framework-16.jar）の `(IZ)` formは37.0 imageのframework.jarから削除され、
   deviceは `(II)` / `(IJI)` のみ。crash stackは `ActivityManagerWrapper.getTaskThumbnail:140`（
   overview thumbnail path。provider構成時のみ到達）。
   (b) 上流自身の修正（android17-release `ActivityManagerWrapper`）が同一pathを
   `TaskSnapshotManager.getInstance().getTaskSnapshot(taskId, convertRetrieveFlag(isLowResolution))`
   へ移行済みであり、本修正はそのcompile時参照できない（framework-16.jarにclass不在）
   reflection版である。`TaskSnapshotManager` は37.0 imageでapp-visible（class descriptor実測。
   第4破壊のhidden_from_bootclasspath対照）。
   (c) `convertRetrieveFlag` の意味論（`isLowResolution ? RESOLUTION_LOW(2) : RESOLUTION_HIGH(1)`）は
   上流source確認済み（同対照file）。
   (d) `SDK_INT >= 37` gate（リテラル。`InputConsumerController` のAPI_37定数と同様）により
   API <=36の経路（既存の直接 `(IZ)` 1経路。base `e214b7b190` にUDC 3-arg reflection分岐は
   存在しない）はbyte identical。matrix (e)/(f) のPASS結果は引き続き有効。
   (e) 変更は1 file 1箇所の最小bridge（Owner decision 8/10と同型）。qa/qb検証でのbind完了
   （両leg `isConnected=true`・4 signature 0件）は第4修正の成立を実証しており、
   `qa-crash-check.txt` / `qa-preflight.txt` / `qb-crash-check.txt` / `qb-preflight.txt`
   （`71c6251203`）を第11 decisionの根拠証跡とする。
   (f) reflection targetの実image一次出力（`getInstance`/`getTaskSnapshot(II)`/`convertRetrieveFlag(Z)I`
   descriptor。`tasksnapshotmanager-reflection-targets.txt`。実装branch commit `65729184c8`）で
   対象methodの存在を固定済み。dex metadataのhiddenapiラベルは `createInputConsumer`（qa/qbで
   reflection成功実証済み）と同一値のためruntime denialと相関しないが、これを実行時の可否の
   仮定には使わず、fixへ識別可能なfailure log markerを設け、qva/qvbで **marker 0＋実thumbnail
   レンダリング** をoracleとする（hiddenapi denialの補助観測も実施）。
12. **qva検証（2026-10-08、実行SHA `bdea76ea75`）で実測された第六の破壊
   （`TaskSnapshot.getHardwareBuffer()` がAPI 37.0でdeprecated・unconditional null化により
   thumbnail bitmapが取得できない。クラッシュではなく黒fallback）も本Issueのoverview成立範囲に
   含め、`ThumbnailData.makeThumbnail` の当該経路1箇所へ **API 37+で `wrapToBitmap()` の
   reflection呼出し** を追加する（失敗時は既存の黒bitmap fallbackへdegrade。marker付きlog）**。
   根拠: (a) 一次対照 `wraptobitmap-contrast.txt`（実装branch commit `8babd3bb10`）: device
   dexdumpで `getHardwareBuffer()` がdeprecated log＋null returnのbodyを持つこと（android17-release
   sourceと一致）、`wrapToBitmap()`（`(Landroid/graphics/ColorSpace;)` と無引数のPUBLIC override）
   が37.0 imageに存在すること、compile classpath（framework-16.jar）にwrapToBitmapが不在である
   ことを固定。(b) qva (a) では第5修正によりoverview/task切替/bindが成立（5 signature 0・
   marker 0）したうえでthumbnailが黒fallbackになり、`getHardwareBuffer is deprecated!` が
   launcher pidで出力された（`qva-crash-check.txt` / `qva-overview.png`）。黒thumbnailは
   overview UIの破綻であり、spec Scenario 1の「破綻なく成立」を満たさない。
   (c) `SDK_INT >= 37` gateによりAPI <=36のhardwareBuffer経路（matrix (e) で正常レンダリング実績）は
   byte identical。(d) 変更は1 file 1箇所の最小bridge（Owner decision 8/10/11と同型）。
   (e) dex metadataのhiddenapiラベルは第11 decisionと同様にruntime denialと相関させず、
   failure marker＋qva/qbv再検証での実thumbnailレンダリングをoracleとする。

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
- 第五破壊の対象（Owner decision 11）: `systemUI/shared/src/com/android/systemui/shared/system/ActivityManagerWrapper.java:140`
  （`getTaskThumbnail` 内の `getService().getTaskSnapshot(taskId, isLowResolution)`。compileは
  framework-16.jarの `(IZ)` form。37.0 imageで削除済み（`(II)`/`(IJI)`のみをdexdump実測）。
  overview thumbnail path（provider構成時のみ到達）で `NoSuchMethodError`。base `e214b7b190` の
  同methodは直接 `(IZ)` 呼出し1経路のみで、reflection分岐は存在しない（v15系main側fileとの
  取り違え訂正。review round 2）。
- 第四破壊の対象（Owner decision 10）: `systemUI/shared/src/com/android/systemui/shared/navigationbar/KeyButtonRipple.java:108`
  （`SDK_INT_FULL >= 3600001` ガード下で `android.companion.virtualdevice.flags.Flags.viewconfigurationApis()`
  を呼ぶ。compileはframework-16.jarの当該classで解決。API 37.0 imageではapp-visibleなclassが
  存在せず（`com.android.internal.hidden_from_bootclasspath` 名前空間のみ。dexdump実測）
  `NoClassDefFoundError`。provider bind後のTaskbar初期化で実行）。
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

### Scenario: getHardwareBuffer廃止環境でthumbnail bitmapがwrapToBitmap経由で取得される

Given API 37.0 image（`TaskSnapshot.getHardwareBuffer()` がdeprecated・unconditional null。
一次対照 `wraptobitmap-contrast.txt`）でprovider構成済みLawnchairのoverviewがthumbnailを
読み込む
When `ThumbnailData.makeThumbnail` が呼ばれる
Then API 37+では `wrapToBitmap()` 経由でsnapshot bitmapが取得され、task cardに実app screenshotが
レンダリングされる。reflection失敗時はmarker付きlogを残し既存の黒bitmap fallbackへdegradeする
（クラッシュしない）。API <=36ではhardwareBuffer経路がbyte identicalに維持される。

### Scenario: getTaskSnapshot削除環境でoverview thumbnail読み込みがクラッシュせず継続する

Given API 37.0 image（`IActivityTaskManager.getTaskSnapshot(int, boolean)` が削除済み。
一次対照 `gettasksnapshot-class-contrast.txt`）でprovider構成済みLawnchairがbindされ、
overview表示により `TaskThumbnailCache` がthumbnailを読み込む
When `ActivityManagerWrapper.getTaskThumbnail` が呼ばれる
Then API 37+では `TaskSnapshotManager` 経由のreflection呼出しが行われ、snapshot取得の成否にかかわらず
launcher processはクラッシュしない。reflection lookup失敗時は既存のnull経路どおり空 `ThumbnailData`
を返す。API 36以下では既存経路（直接 `(IZ)` 1経路）がbyte identicalに維持される。

### Scenario: flag class欠損環境でKeyButtonRippleがクラッシュせず旧tap timeoutへfallbackする

Given API 37.0 image（app-visibleな `android.companion.virtualdevice.flags.Flags` が存在しない。
一次出力 `ma-keybuttonripple-flags-class-check.txt`）でprovider構成済みLawnchairがbindされ、
Taskbar初期化（KeyButtonRipple生成）に到達する
When `KeyButtonRipple` が `Flags.viewconfigurationApis()` を評価する
Then `NoClassDefFoundError` をcatchして
`ViewConfiguration.getTapTimeout()`（旧挙動）へfallbackし、launcher processはクラッシュしない。
classが存在する環境では既存のflag評価挙動が変わらない。

### Scenario: API 37.0 imageでTaskbar初期化がクラッシュせずprovider bindが完了する

Given API 37.0 image（`ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX` がframework.jarから削除済み、
`taskbar_phone_size` のbaked IDが別resourceへ移動済み。一次対照
`pre-guard-field-and-resource-contrast.txt`）でprovider構成済みLawnchairがbindされ、
`TouchInteractionService.onUserUnlocked` からTaskbar初期化に到達する
When `TaskbarRecentAppsController` が `enableRecentTasksThrottle` を評価し、
`TaskbarStashController` が `R.dimen.taskbar_phone_size` を解決する
Then `NoSuchFieldError` をcatchしてthrottle無効（false）として継続し、`taskbar_phone_size` は
literal値（48dp）として解決し、launcher processはクラッシュしない。provider bind完了
（`isConnected=true`）に到達する。flag guardはfield有無によらず `ATLEAST_BAKLAVA_1=false` の
環境（API 36等。当該imageにfieldは無い）では参照自体を行わないため既存の挙動を変えない。
dimen literal化はAPI 36では意図した正規化（`notification_2025_action_list_min_height`
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
- [ ] AC-9: evidence READMEと実装PR packetへ **matrixごとの実行SHA**のv5 mapping
  （qva2 (a): **compat修正6 commits適用後head**、qvb2 (b)/G3: **新candidate commit**、
  (e)/(f)＋hiddenapi/wmshellの再利用分: **旧candidate `2987e525bd`**）と使用APKの対応、
  およびAPKごとのsha256が明記され、artifactとSHAの取り違えが起きない。diagnostic run群
  （pre-guard: `f5a3977281`〜`5c6d40a56d`／post-guard＋(e)(f)再利用元: `fc5169566c`／
  qa-qb: `71c6251203`（第4修正実証＋第五破壊diag）／qva-qbv: `8babd3bb10`（第5修正実証＋
  第六破壊diag。旧candidate `212097886b`））は **最終matrix（qva2/qvb2）と混同しない別行** で
  記載する（再利用分の一次出力は`fc5169566c` 固定のpost-guard matrix（ma-〜mf-）と各行で対応づける）。
- [ ] AC-10d: v5再検証matrix qva (a)/qvb (b) で、overview task cardに **実app screenshotの
  thumbnailがレンダリングされる**（黒fallbackでないことをscreenshotで実証）。`wrapToBitmap`
  reflection failure markerが0件であり、新reflection起因のcrashが無い。diffがcode reviewで
  確認され、API <=36のhardwareBuffer経路不変が確認されている。
- [ ] AC-10c: v4 matrix qva (a)/qvb (b) で実証済み（diagnostic。`8babd3bb10`）:  `getTaskSnapshot` の `NoSuchMethodError` と
  reflection failure log marker（識別可能なtag。plan実装詳細4）が **ともに0件** で、
  (a) でbind完了・overview成立（**実thumbnailのレンダリングを含む**）・task切替、(b) で
  `compatible=true`・overview/task切替・G3確定観測が成立する。加えてqva/qvbのlogcatに
  新reflection呼出し起因のhiddenapi denialが無いこと（補助観測）。`TaskSnapshotManager`
  reflectionのdiffがcode reviewで確認され、API 36以下の経路不変が確認されている。
- [ ] AC-10b: post-guard matrix (a)/(b) で新たに実測された `KeyButtonRipple` の
  `NoClassDefFoundError`（`android.companion.virtualdevice.flags.Flags`）が出ず、(a) で
  bind完了（`isConnected=true`）・overview成立・task切替、(b) で `compatible=true`・sheet非表示・
  overview/task切替が成立する。guard diff（`NoClassDefFoundError` catchのみ。
直接参照式にchecked例外は送出されないためcompile成立）がcode reviewで確認され、
class存在環境・`SDK_INT_FULL<3600001` 環境（API 36）の挙動不変が確認されている。
- [ ] AC-10: matrix (a) で `TaskbarRecentAppsController` の `NoSuchFieldError`
  （`ENABLE_TASKBAR_RECENT_TASKS_THROTTLE_BUGFIX`）と `Resources$NotFoundException`
  （`taskbar_phone_size`）の **いずれも出ず**、native densityのままbind完了
  （`isConnected=true`）へ到達する。flag guardのdiffはcode reviewで、field有無によらず
  `ATLEAST_BAKLAVA_1=false` で参照しない経路（API 36）の不変性が確認される。dimen literal化のdiffは、36 imageでの
  誤解決先（`api36-image-framework-res-contrast.txt`）と37.2 dereference先（48dp）への一次出力
  つきでreviewされ、「API 36での10dp→48dpは意図した正規化である」ことが確認される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | matrix (a) runtime証跡（preflight＋logcat＋screenshot/録画。evidence dir `docs/assessment/545-api37-provider-fix-evidence/`。実行SHA: compat修正commit） |
| AC-2 | matrix (b) runtime証跡（同上＋sheet非表示screenshot。実行SHA: candidate commit） |
| AC-3 | (a)/(b) logcatのhiddenapi観測抜粋＋取得可能なflags一次出力file |
| AC-4 | (b)構成のG3 logcat抜粋＋PR本文のcreator mode判断記載 |
| AC-5 | matrix (e)/(f) runtime証跡（**実行SHA: 旧candidate `2987e525bd`。再実行せず `fc5169566c` 固定のpost-guard証跡を再利用**）＋(e)のgeometry正規化record（post-fix screenshot＋APK静的確認＋誤解決先対照） |
| AC-6 | candidate release buildのmanifest placeholder静的確認出力（不成立時はdrop記録） |
| AC-7 | ADR-0018 diff（実装PR内。candidate成立時のみ） |
| AC-8 | spec（Owner decisions/Prior art）＋実装PR本文の調査記録節 |
| AC-9 | evidence READMEのmatrix↔SHA↔APK対応表（sha256含む。pre-guard diagnostic run（`f5a3977281`〜`5c6d40a56d`）・post-guard matrix（`fc5169566c`。再利用分）・qa/qb diagnostic run（`71c6251203`。第五破壊diag/superseded）をv4最終matrix（qva/qvb）と混同しない別行で記載）＋packet記載 |
| AC-10 | matrix (a) logcat（Taskbar NoSuchFieldError / Resources$NotFoundException 0件・bind完了）＋guard/dimen diff（code review） |
| AC-10b | v3再検証matrix (a)/(b)のruntime証跡（KeyButtonRipple NoClassDefFoundError 0件・bind完了/compatible=true。qa実績: bind成立を `qa-crash-check.txt` が実証）＋guard diff（code review） |
| AC-10c | v4再検証matrix qva (a)/qvb (b)のruntime証跡（NoSuchMethodError 0件＋failure marker 0件＋実thumbnailレンダリング＋hiddenapi補助観測）＋reflection diff（code review） |
| AC-10d | v5再検証matrix qva2 (a)/qvb2 (b)のruntime証跡（実app screenshot thumbnailレンダリングscreenshot＋wrapToBitmap failure marker 0）＋reflection diff（code review） |

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
  **v5追記（Owner decision 12。実行手順）**: qva検証（実行SHA `bdea76ea75`。`qva-crash-check.txt`/
  `qva-preflight.txt`）で第5修正の成立（bind・overview・task切替・5 signature 0・reflection
  marker 0）を確認したうえでthumbnail黒fallback（第6破壊。クラッシュなしの視覚劣化）が発覚
  したため、12の修正commit適用後に **candidateを再度作り直し（`212097886b`を明示revertして36へ
  戻す→`ThumbnailData` fix→qva (a)をcompat 6修正headで実施→新candidate→qvb (b)+G3）**
  （prefix `qva2-*`/`qvb2-*`。旧candidate `212097886b` とqva/qbv artifactは第六破壊の
  diagnostic/superseded evidenceとしてREADMEへ記録）。(e)/(f)等の再利用契約は不変。
  **v4追記（Owner decision 11。実行手順。revert方式）**: qa/qb検証（実行SHA `68e68a6a45` /
  `40eb5dbfab`。証跡は実装branch commit **`71c6251203`** に固定: `qa-crash-check.txt`/
  `qa-preflight.txt`/`qb-crash-check.txt`/`qb-preflight.txt`）で **bind完了（両leg
  `isConnected=true`・4 signature 0件・(b)は`compatible=true`/G3遷移開始）を確認**したうえで
  第五破壊がoverview thumbnail pathで発覚したため、**`71c6251203` の後で明示revert
  `40eb5dbfab`（maxSdk 36へ戻す。旧candidateとrevert pairはsuperseded historyとして保持し
  READMEへ記録）→ `ActivityManagerWrapper` fix → debug build＋qva (a)をcompat 5修正headで実施 →
  新maxSdk 37 candidate commit → debug/release build＋qvb (b)+G3を新candidate SHAで実施**
  （prefix `qva-*`/`qvb-*`）。旧 `40eb5dbfab` とqa/qb artifactは第五破壊のdiagnostic/superseded
  evidenceとしてREADMEへ記録。(e)/(f)/hiddenapi/wmshellの再利用契約はv3どおり（旧candidate
  `2987e525bd`＋`fc5169566c`証跡）。**qva/qvbの新reflection呼出しについてhiddenapi denialの
  補助観測を追加**（既存hiddenapi PASSは本callを未実行のため）。
  **post-guard追記（Owner decision 10。実行手順）**: post-guard matrix (a)/(b) が第四破壊
  （`KeyButtonRipple`）でFAILしたため、post-guard証跡commit **`fc5169566c`** の後に
  **`2987e525bd` を明示revertしてmaxSdk 36へ戻す → `KeyButtonRipple` guard → debug build＋
  matrix (a)をcompat 4修正headで実施 → 新maxSdk 37 candidate → debug/release build＋(b)+G3**
  の順で進める。（現headのmaxSdkが既に37のため「candidateの再作成」は不能。revertで36へ戻してから
  再積む。matrix (a)の実行SHAはこのcompat 4修正head＝④の「compat修正適用後head」の正しい状態であり、
  AC-9の(a)契約と整合する。）**(e)/(f)/hiddenapi/wmshellのPASS結果は旧candidate `2987e525bd` の
  artifact（sha256はdecision 10参照）に固定して再利用**し、READMEのmatrix↔SHA↔APK対応表は
  SHAを分けて記載する（(a)=compat 4修正head、(b)=新candidate、(e)/(f)=旧candidate `2987e525bd`）。
  以下の①〜⑦はOwner decision 10適用前の記録として保持する。
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
  - (a) API 37 debug @ **compat修正5 commits適用後head（v4。prefix `qva-*`）**＋debug用overlay＋priv-app →
    クラッシュ無し（4修復signature＋`getTaskSnapshot` NoSuchMethodError 0）、
    reflection failure marker 0、**実thumbnailレンダリング**、`isConnected=true`、overview成立、
    task切替成功。新reflection呼出し起因のhiddenapi denial補助観測（0件）。
  - (b) API 37 release @ **新candidate commit（maxSdk 37。prefix `qvb-*`）**＋release用overlay＋priv-app →
    `compatible=true`、overview成立（reflection failure marker 0・**実thumbnailレンダリング**）、
    sheet非表示、task切替成功。新reflectionのhiddenapi denial補助観測（0件）。
  - (d) hiddenapi一次出力: (a)/(b)のregisterInputConsumer実行時間帯のlogcatを取得し
    hidden API access系のdenial/block有無を判定。可能ならroot shellで
    `hiddenapi list`（またはflags table）から該当signature行を一次取得する。
  - (e) API 36 debug＋debug用overlay＋priv-app（**実行SHA: 旧candidate `2987e525bd`、candidate debug APK。
    v3では再実行しない。`fc5169566c` 固定のpost-guard証跡（`me-*`）を再利用**）→
    bind・overview成立・task切替の従来oracle＋**Taskbar geometry正規化のrecord**
    （post-fix screenshot＋APKの `taskbar_phone_size=48dp` 静的確認＋pre-fix誤解決先10dp対照）は
    いずれも `fc5169566c` の `me-*` fileで成立済み。
  - (f) stock構成（overlay無し）API 37＋candidate release → 無影響。API 36 stockの簡易確認も
    同様。**v3では再実行しない。実行SHA: 旧candidate `2987e525bd`、candidate release APK。
    `fc5169566c` 固定のpost-guard証跡（`mf-*`: 通常install→HOME設定→system overview継続・
    launcher crash無し・gate/sheet状態）を再利用する**
    （#524 (d)-API36相当の観測を今回の修正後buildで取得済み）。
  - wmshell監視・hiddenapi観測（matrix (d)）: **v3では旧candidate `2987e525bd` の既取得PASSを
    `fc5169566c` 証跡から再利用する**（qva/qvbのlogcatで同種ログ〔新TaskSnapshotManager call由来
    denialを含む〕が自然に再取得される場合は補助観測であり、acceptanceの既取得PASSを置き換えない）。
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
- 2026-10-07: Addendum round 3（PR #549コメント。中1・低1、blocking無し）対応 —
  (1) matrix (e)詳細へgeometry正規化oracleを同期（native density・post-fix screenshot・APKの
  `taskbar_phone_size=48dp` 静的確認・誤解決先対照。Test oracle AC-5行も同期）。(a)の実行SHA表記を
  「compat修正3 commits適用後head」へ統一、AC-9対応表の証跡commitへ `206850b3d7` / `5c6d40a56d` を
  追加。(2) 旧文言の掃除: flag guardの「flagが存在する環境（API 36等）」表現を
  「field有無によらず `ATLEAST_BAKLAVA_1=false` で参照しない」へ修正。誤解決先の正確な値
  （10.000000dp）を一次出力fileへ追記し、minSdk 35分の静的table確認
  （SDK 35: `0x01050283` = `notification_right_icon_headerless_margin` 20dp。runtime matrix不追加）
  を§3'として記録。
- 2026-10-07: Addendum v3（post-guard最終matrix結果にもとづくOwner decision 10追加）—
  post-guard matrix（実装branch evidence `ma-*`〜`mf-*`）で (e)/(f) PASS・hiddenapi 0件・wmshell 0件・
  `taskbar_phone_size=48dp` pinのAPK静的確認成立を確認したうえで、第四破壊
  （`KeyButtonRipple.java:108` の `android.companion.virtualdevice.flags.Flags` クラス欠損。
  hidden_from_bootclasspath名前空間のみ実在をdexdump実測）を発見。(a)/(b)/G3が再びbind未達。
  当該flag読取り1箇所のdegrade guard（旧tap timeout fallback）を本Issue範囲へ追加
  （AC-10b・新Scenario・実施順序追記）。再検証は(a)/(b)+G3に限定（(e)/(f)はpath不変）。
- 2026-10-07: Addendum v3 round 1〜2（PR #551 inline review blocking 3＋round 2 blocking 1/低1）対応 —
  (1) guard契約を `NoClassDefFoundError` のみcatchへ修正（compile不能問題の解消）。
  (2) post-guard matrix証跡を実装branch commit `fc5169566c` にcommit固定参照。(3) matrix↔SHA契約を
  v3 mappingへ全面同期（AC-9: (a)qa-run=compat 4修正head／(b)qb-run=新candidate／
  (e)(f)＋hiddenapi/wmshell再利用分=旧candidate `2987e525bd`。spec/plan module表へ
  `KeyButtonRipple.java` 行追加、build.gradle行を4 commitsへ同期、matrix詳細をv3表記へ更新。
  旧3-commit記述はhistorical明示）。(4) plan Statusを「revision 2 accepted / revision 3 proposed
  addendum」へ更新。
- 2026-10-08: Addendum v4（qa検証結果にもとづくOwner decision 11追加）— qa (a)（実行SHA
  `68e68a6a45`、native density）で第4修正成立（bind完了 `isConnected=true`・4 signature 0件）を
  実証したうえで、第五破壊（`ActivityManagerWrapper.getTaskThumbnail` の `getTaskSnapshot(IZ)`
  削除。overview thumbnail path。`NoSuchMethodError` 実測）を発見。上流android17-releaseが
  `TaskSnapshotManager` 経由へ移行済みであること（`gettasksnapshot-class-contrast.txt`。
  実装branch commit `1777e348c9`）にもとづき、API 37+での同manager経由reflection呼出し
  （lookup失敗時は空 `ThumbnailData` degrade）を本Issue範囲へ追加（AC-10c・新Scenario・
  実施順序v4追記）。qa/qb再実施は第5修正後の新candidateで実施（(e)/(f)再利用契約は不変）。
- 2026-10-08: Addendum v5（qva検証結果にもとづくOwner decision 12追加）— qva (a)（実行SHA
  `bdea76ea75`、native density）で第5修正の成立（bind・overview・task切替・5 signature 0・
  reflection marker 0）を実証したうえで、thumbnail黒fallback（第六破壊。
  `TaskSnapshot.getHardwareBuffer()` が37.0でdeprecated・unconditional null。
  `wraptobitmap-contrast.txt`。実装branch commit `8babd3bb10`）を発見。
  supported pixel source `wrapToBitmap()`（compile classpath不在）のreflection呼出しを
  本Issue範囲へ追加（AC-10d・新Scenario・実施順序v5追記）。再検証は第6修正後の新candidateで
  qva2 (a)/qvb2 (b)+G3を実施（(e)/(f)等の再利用契約は不変）。
