# API 37 (Android 17) のQuickstep/recents対応評価 — QUICKSTEP_MAX_SDK引き上げとcompat前提（Issue #520、Epic #516 Phase 1）

> Status: draft（2026-10-04起草。PR reviewでblocking findingがなくなった時点でacceptedへ遷移する）
> Research date: 2026-10-04
> Agent session: ZCode（GLM-5.3-flash）
> Issue: Phase 1子Issue [#520](https://github.com/nunu1733/NunuLauncher/issues/520)。Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516)、兄弟Issue [#521](https://github.com/nunu1733/NunuLauncher/issues/521) / [#522](https://github.com/nunu1733/NunuLauncher/issues/522)、Phase 0 [#519](https://github.com/nunu1733/NunuLauncher/issues/519)
> 対象revision: 16-dev `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（[ADR-0018](../adr/0018-lawnchair-16-rebase.md) Decision 1のanchor。`git ls-remote upstream refs/heads/16-dev` で2026-10-04に再確認。upstream headと一致、Phase 0観測以後に前進なし）、fork main `a65a1d169c743ef28e7f07c4717b5efc08c9e1b1`
> 入力: [Phase 0 assessment §6-2](issue-516-16-rebase-phase0-research.md)、[#442 §3.2/§5.2/§6.3](issue-442-android16-17-fitness-research.md)、[ADR-0018](../adr/0018-lawnchair-16-rebase.md) Decision 7/8

## 1. 問いと方法

[#520](https://github.com/nunu1733/NunuLauncher/issues/520)の4つの問いに対し、次の方法で証拠を収集した。

1. **16-dev `43a21b43` 上のlocal Git object database解析**（worktree変更なし。読み取りのみ）: `build.gradle`、`lawnchair/src/app/lawnchair/LawnchairApp.kt`、`compatLib/` 全module、`LawnchairQuickstepCompat.kt`、`src/com/android/launcher3/Utilities.java`、quickstep/taskbar関連のrecents gate。行番号は本SHA固定の参照である。
2. **upstream履歴とPRの参照**（読み取りのみ）: `git log -S` によるSDK定数変更commitの特定、GitHub APIによるupstream PR #7262本文の取得。
3. **外部参照調査**（[github-workflow.md](../project/github-workflow.md) External reference scan。research Issueのため成果物である本書に記録）: AOSP framework/SystemUIの一次ソース、Android 17公式behavior changes、Lawnchair公式docs、upstream issue/PR。確認日はすべて2026-10-04。

本書は観測と判断材料の記録のみを行い、採用判断（`QUICKSTEP_MAX_SDK`引き上げ・V37 factory実装・サポート境界）は実装IssueとADR-0018 device test matrix上のowner decisionに委ねる（§7）。

## 2. Quickstep/recents有効化の機構（4問い共通の前提）

16-dev `43a21b43` におけるrecents有効化は、次の2段gateである（[Phase 0 §6-2](issue-516-16-rebase-phase0-research.md)が記録した「advertised range」の実体）。

1. **`compatible`**: `Build.VERSION.SDK_INT in BuildConfig.QUICKSTEP_MIN_SDK..QUICKSTEP_MAX_SDK`（`lawnchair/src/app/lawnchair/LawnchairApp.kt:60`。範囲は `build.gradle:147-148` の `quickstepMinSdk = "35" / quickstepMaxSdk = "36"`）。**debug buildTypeのみ `0..100000` に上書きされ常にcompatibleになる**（`build.gradle:232-235`）。したがってSDK範囲はrelease buildでのみ効く。
2. **`isRecentsComponent`**: platform resource `android:string/config_recentsComponentName` を読み、そのcomponentが自分自身の `com.android.quickstep.RecentsActivity` を指していることを要求する（`LawnchairApp.kt:192-211`）。非一致時は「disabling recents」をlogcatに出す。
3. 両者のANDが `recentsEnabled` であり、`QuickStepContract.sRecentsDisabled = !recentsEnabled` として共有staticへ反映される（`LawnchairApp.kt:62,70`）。

platform側の対応機構（一次ソース確認、確認日2026-10-04。AOSP main `packages/SystemUI/.../OverviewProxyService.java` blob `e3cf4119`）: SystemUIは `mRecentsComponentName = ComponentName.unflattenFromString(context.getString(com.android.internal.R.string.config_recentsComponentName))` でrecents providerを決定し、`Intent(ACTION_QUICKSTEP).setPackage(mRecentsComponentName.getPackageName())`（action `android.intent.action.QUICKSTEP_SERVICE`）でそのpackage内のserviceへbindする。`updateEnabledState()` は `MATCH_SYSTEM_ONLY` で解決可否を検証し、bind失敗時は指数backoffでretryする。default launcherが変わってもrecents componentは構成resourceで決まるため、**recents providerはhome roleではなく端末のsystem構成（RRO等のoverlay）で決まる**。

Lawnchair側の `TouchInteractionService` は同じaction `android.intent.action.QUICKSTEP_SERVICE` を宣言し（`quickstep/AndroidManifest.xml:71-78`）、`RecentsActivity` はoverview hostである（同 `:80-88`）。Lawnchairをrecents providerにするには、QuickSwitch（旧QuickstepSwitcher、Lawnstep後継。**root必須のMagisk/KernelSU/APatch module**）がsystem構成をLawnchairへ向ける必要がある（§9 Prior art参照）。`lawnchair/AndroidManifest.xml:201-208` のmeta-data `xyz.paphonb.quickstepswitcher.minSdk/maxSdk` は本repository内に消費者を持たず、このmodule側の消費を意図した「対応SDK範囲の宣言」である（PR #7262が同じ定数を「quickswitch compatibility」と呼んでいる。§3）。

この機構から、#442 §5.2の実機観測（Pixel 9a / API 37でhomeはLawnchairのまま、GestureNav経由のoverviewはPixel Launcherプロセスから提供された）は機構と整合する: Pixelの `config_recentsComponentName` はPixel Launcherを指すため、Lawnchairの `isRecentsComponent` はfalseであり、`QUICKSTEP_MAX_SDK` を上げてもLawnchair側recentsは有効化されず、system側overviewが継続する。

## 3. 問い1: `QUICKSTEP_MAX_SDK` の36→37引き上げの根拠とcompat面

### 3.1 引き上げの「妥当性の根拠」となりうる材料

1. **API 35→36でcompat chainの挙動差分がゼロであった実績**: `QuickstepCompatFactoryVBaklava`（API 36）の3つのoverride（`ActivityManagerCompat` / `ActivityOptionsCompat` / `RemoteTransitionCompat`）は、`QuickstepCompatFactoryVV`（API 35）と本体が同一である（`compatLib/compatLibVBaklava/src/.../sixteen/` 3 file。`ActivityManagerCompatVBaklava` は `ActivityManagerCompatVV` と同じく空subclass、`ActivityOptionsCompatVBaklava.makeCustomAnimation` のbodyはVVと完全一致）。VVもVU（API 34）と実質同一であり、chainの最後の実質差分はVUで、factory bodyの差は`RemoteTransition` のlambda→method reference化のみである。compatLibはAPI 34以降「挙動差分ゼロ・framework jar差し替えのみ」のpatternが継続している。
2. **Android 17にrecents/quickstep関連の announced platform変更がない**: 公式behavior changes（all apps / target 37、§9）にrecents・overview・quickstep・`TaskInfo`・window transitions・predictive backに関する項目は存在しない。launcher隣接項目は BAL（IntentSender拡張、target 37）、RemoteViews bitmapメモリ上限（widget host。target 37）、lock-free `MessageQueue`（target 37）、大画面でのorientation/resizability制限無視（target 37）、app memory limits（all apps）等であり、これらはtargetSdk 37対応（[#521](https://github.com/nunu1733/NunuLauncher/issues/521)）の範囲である。
3. **16-devにAPI 37固有の分岐が存在しないことの確認**: `SDK_INT_FULL` を使う最も高い分岐はAndroid 16 minor 1（`3600001`。`src/com/android/launcher3/Utilities.java:160-162`、`quickstep/src/com/android/quickstep/AbsSwipeUpHandler.java:1694` 等）であり、`CINNAMON_BUN`（API 37の `VERSION_CODES` 定数。local SDK platform `android-37.0` の `android.jar` で `public static final int CINNAMON_BUN = 37` を `javap` により確認）は16-dev tree全体に0件。API 37上では既存の `ATLEAST_BAKLAVA`（`>=` 判定）経路がそのまま選択される。
4. **製品側の要請**: 保守者実機がPixel 9a / API 37（#442 §5.2）であり、forkが16-dev追随を続ける限り、将来のAndroid 17端末シェア増は「QuickSwitch構成済み環境でのprovider対応」の需要に直結する。

### 3.2 単純な定数引き上げを先に採用してはならない理由（Epic Non-goalsの実体）

`QUICKSTEP_MAX_SDK = 37` は「Android 17で本buildをQuickstep providerとして構成してよい」ことの**宣言**である（§2。消費者はrelease buildの `compatible` gateとQuickSwitch）。宣言を検証に先行させた場合の失敗モードは、**QuickSwitch構成済みのAndroid 17端末でoverviewが壊れる**ことである。system側overviewが成立している端末ではprovider切替によって使えるrecentsが壊れるため、現状より悪化する。この「構成済みだが非対応build」状態に対するUIは既存で、`quickstep_incompatible` sheet（「Your device is configured to have system gestures, known as Quickstep, provided by %1$s, but this version of %1$s isn't compatible with your Android version...」`lawnchair/res/values/strings.xml`。表示条件は `isRecentsComponent == true && !recentsEnabled`、`LawnchairApp.kt:246`）と、QuickSwitch公式docsの「Incompatible system integration」troubleshootingが対応する。つまり引き上げの妥当性判断は、(a) §3.3のcompat面がAPI 37で実際に成立することのruntime検証、(b) QuickSwitch側のAndroid 17対応状況、(c) §6の価値判断、の3点を満たした後の実装Issueでのみ確定する。

### 3.3 API 37で実際に必要なcompat面の列挙

| 面 | 43a21b43時点の状態 | 引き上げ時に必要な作業 |
|---|---|---|
| compatLib factory解決 | `ATLEAST_BAKLAVA -> QuickstepCompatFactoryVBaklava()`（`LawnchairQuickstepCompat.kt:50`）。`>=` 判定のためAPI 37でも解決失敗しない（最終 `else -> error("Unsupported SDK version")` に到達しない） | コード変更不要。ただし§4のV37 module追加は検証で差分が出た場合の受け皿 |
| 隠蔵API compile面 | compatLib各moduleは `prebuilts/libs/framework-10..16.jar` に対してcompile（`compatLib/compatLibVBaklava/build.gradle` の `addFrameworkJar('framework-16.jar')`）。main app moduleも `framework-16.jar`（`build.gradle:378,420`） | V37 moduleを作る場合は `framework-17.jar` をprebuiltsへ追加（入手方法の確認を含む。§8） |
| runtime隠蔵API挙動 | VBaklava chainが使う隠蔵API（`getRunningTasks`/`getRecentTasks`/`TaskSnapshot`/`RemoteTransition` IPC等。`compatLib/src/main/java/app/lawnchair/compatlib/ActivityManagerCompat.java`）のAndroid 17での挙動は未検証 | QuickSwitch構成可能なAPI 37環境（rooted実機またはroot化emulator）でoverview一連の動作確認。fail時にのみcompat修正が発生する |
| version分岐 | `ATLEAST_BAKLAVA` / `ATLEAST_BAKLAVA_1` まで。API 37専用分岐なし | 検証で37固有の回避が必要になったときだけ `ATLEAST_37` 系定数（`SDK_INT_FULL` 方式のminor対応含む）を追加 |
| manifest / meta-data | `TouchInteractionService`・`RecentsActivity` 宣言、`xyz.paphonb.quickstepswitcher.minSdk/maxSdk` はSDK非依存の宣言 | 定数変更のみでmeta-dataへ反映（同一placeholder） |
| system overviewとの競合 | Pixel等では `config_recentsComponentName` がOEM launcherを指すため、引き上げは無影響（§2） | 競合は発生しない。影響するのはQuickSwitch構成済み端末のみ（§3.2） |

## 4. 問い2: API 37用compatLib factoryの要否と内容

**要否: 条件付きで「追加がpattern整合」。追加しない場合も機能上は成立する。**

- 16-devのcompatLib構成（`43a21b43` の `compatLib/`）: base module（interface `ActivityManagerCompat` / `ActivityOptionsCompat` / `RemoteTransitionCompat` / `RecentsAnimationRunnerCompat` / `QuickstepCompatFactory` + AOSP由来の隠蔵APIコピー `android.window.RemoteTransition`、`TransitionInfo` 等）+ per-level module `compatLibVQ/Vr/VS/VT/VU/VV/VBaklava`。各moduleは対応するframework jarでcompileし、直下のlevelへ `api` 依存で連鎖する。
- `QuickstepCompatFactoryVBaklava` はmerge commit `dd4a08be61`（`Merge tag 'android-16.0.0_r3' into 16-dev`、2025-11-15）でAOSP 16統合とともに導入され、`framework-16.jar` が同時にprebuiltsへ入った。**factoryとしてはV→Baklavaで挙動差分ゼロ**であり、moduleの実効的意味は「API 36のframework jarに対するcompile単位と、将来の差分の受け皿」である。
- API 37においても、factory解決はVBaklavaで成立するため、**機能の成立にV37 moduleは必須でない**。一方で、pattern上の選択肢は次の2つであり、実装Issueで決める:
  - **(a) `compatLibV37` 相当を追加**（命名はupstreamの `sixteen` 系package命名に追随。`framework-17.jar` をprebuiltsに追加し、factoryは空subclassとしてVBaklavaを継承）: API 37検証で隠蔵API差分が見つかった場合の受け皿が先に用意される。VV→VBaklavaと同様、差分ゼロでもmodule自体は残る。
  - **(b) 追加せずVBaklavaのままにする**: 検証で差分ゼロが確認できた場合、module追加は不要。差分が見つかった時点で(a)へ移行する。
- いずれの場合も、**検証なしにmoduleを追加するだけでは何も保証されない**。compat前提の実体はruntime検証（§3.3）である。

## 5. 問い3: `quickstepMinSdk` 35化の製品影響とサポート境界（owner decision材料）

### 5.1 変更の出所

minSdk 29→35の引き上げは、upstream commit `f8164291da`「chore: Prepare for eventual release (#7262)」（2026-09-15。`git log -S 'quickstepMinSdk = "35"' -- build.gradle` で特定）による。PR本文は「update quickswitch compatibility to just 15-16」「Raised the minimum supported Android version to Android 15」と記載し、**API差分対応ではなくrelease前の製品サポート境界の決定**であることが明確である。maxSdk 36はそれ以前から存在した（同commit時点で `quickstepMaxSdk = "36"` は変更なし）。

### 5.2 影響の実体

| 端末構成 | baseline（29..35） | 16-dev追随後（35..36） | 影響 |
|---|---|---|---|
| 通常構成（config_recentsComponentNameがLawnchairを指さない。Pixel、OEM端末等） | recents無効（`isRecentsComponent=false`） | 同一 | **変化なし**。SDK範囲は到達しないgateである |
| QuickSwitch構成済み（rooted。configがLawnchairを指す）で API 29〜34 | Lawnchairがprovider | `recentsEnabled=false` + `quickstep_incompatible` sheet表示 | **provider機能を失う**。system構成の切替（QuickSwitchで戻す）が必要 |
| QuickSwitch構成済みで API 35〜36 | Lawnchairがprovider（29..35内） | 同一 | 変化なし |
| debug build | 常にcompatible | 同一 | 変化なし（debugは `0..100000`） |

つまり35化の実害は「**QuickSwitch構成済みのrooted環境でAndroid 14（API 34）以下を使うユーザー**」に集中し、それ以外の構成では挙動が変わらない。app自体のinstall下限は `minSdk 26`（`build.gradle:31`）であり、API 27〜34端末でもappとしての利用は継続する（launcher機能・organizer機能はquickstep rangeと独立）。

### 5.3 owner decisionの材料（ADR-0018 Decision 7のdevice test matrix上で確定すべき論点）

1. **選択肢**: (a) 16-dev（35..36）に追随する — upstreamのrelease意図と一致。検証対象はAPI 35/36のみ。API ≤34のQuickSwitch構成ユーザーは切り捨て。(b) 下限を29へ戻す（例: 29..36 / 29..37）— baseline時代のprovider対応を維持。ただしcompatLib chain VQ..VUは現行16-dev treeに実装ごと残存しているものの、forkがその範囲の動作検証を所有することになる（upstreamは35+でのみ検証している状態）。(c) 暫定35..36でrebaseし、実測需要（QuickSwitch構成ユーザーの有無）を根拠に後日再判定。
2. **エコシステム制約**: QuickSwitch公式docsによれば、QuickSwitch companion app自体はAndroid 14以降をサポートせず（Android 14+はterminal方式）、moduleのroot解決依存（Magisk/KernelSU/APatch）とROM依存がある。API 29〜34向けのprovider対応を維持しても、エコシステム側の制約がforkの外にある点はowner判断の前提として明記すべきである。
3. **テストmatrix上の記載**: 現行CI lanes（API 36）と保守者実機（API 37）では、provider構成環境での動作は機械検証されていない（`isRecentsComponent` が常にfalseのため）。(b)を採る場合、API 29〜34のうちどのlevelを検証対象にするか（例: 最も低いVQのみ、または連続範囲）をmatrixに明記する必要がある。

## 6. 問い4: launcher側recentsを有効化する価値の判断材料

### 6.1 `recentsEnabled` が開放する機能（43a21b43のコード面上の全消費者）

| 機能 | 場所（43a21b43固定） | recents無効時の状態 |
|---|---|---|
| overview UI（task cards・task切替・RecentsView host） | `quickstep/AndroidManifest.xml` の `RecentsActivity`、quickstep module全体 | SystemUIは構成上のprovider（OEM launcher等）へ委譲。Lawnchairのoverview UIは出ない |
| drawer内ASI（global）検索 | `lawnchair/src/app/lawnchair/search/algorithms/LawnchairSearchAlgorithm.kt:229`（`isASISearchEnabled` が `!isRecentsEnabled` でfalse） | local searchのみ |
| PAUSE_APPS shortcut（アプリ一時停止） | `LawnchairLauncher.kt:286` | 非表示 |
| taskbar recents button / taskbar pinning | `quickstep/src/com/android/launcher3/taskbar/customization/TaskbarFeatureEvaluator.kt:31-35`、`TaskbarManagerImpl.java:1180` | recents buttonなし。pinning系の一部は残り得る |
| desktop mode（大画面desktop windowing入口） | `QuickstepLauncher.java:333`（`canEnterDesktopMode && isRecentsEnabled`） | 無効 |
| widget depth | `QuickstepLauncher.java:360` | 無効 |
| one-handed mode対応flag | `RecentsAnimationDeviceState.java:167` | 連携なし |
| quickswitch/quickstep preference面 | `PreferencesDashboard.kt:204`（recents無効時はdebug buildでのみ表示）、`QuickstepPreferences.kt:77` | warning表示・設定非表示 |
| status bar clock隠し | `LawnchairApp.kt:76,91` | 機能しない |
| GestureNavContract浮遊surface | `LawnchairLauncher.kt:333`（`!isRecentsEnabled && enableGnc` で `LawnchairFloatingSurfaceView` を使用） | **無効時の代替UXとして存在**（`prefs.enableGnc` 時） |
| window corner radius（recents連動の角丸） | `systemUI/shared/.../QuickStepContract.java:429`（`sRecentsDisabled` で0を返す） | 角丸補正なし |

### 6.2 system側overview成立済みとの比較

- 保守者実機（Pixel 9a / API 37）ではsystem側overview（Pixel Launcher）が日常利用として成立している（#442 §5.2。task cards・screenshot/selectionアクション含む）。**通常構成の端末では、launcher側recentsを有効化してもユーザーが得る概観は「overviewのUIがOEM品からLawnchair品に置き換わる」ことしかなく、手势・挙動の破綻リスクと引き換えになる。**
- 価値が正になるのはQuickSwitch構成可能なrooted環境であり、その場合に得られるのは§6.1の統合機能群（Lawnchair検索・PAUSE_APPS・taskbar・desktop mode等とoverviewの統合）である。
- 検証なしでprovider対応を宣言した場合のdownsideは§3.2のとおり「構成済み端末でのoverview破壊」である。失敗時の復帰はQuickSwitchでの構成解除（またはapp更新uninstall）であり、app内からの復旧導線は `quickstep_incompatible` sheetの「App info」ボタンまでである。

### 6.3 価値判断のまとめ

`QUICKSTEP_MAX_SDK` の37引き上げ（およびcompat面の整備）の価値は、(i) 保守者自身の環境がAPI 37であること、(ii) forkの方向性（上流追随）として将来のAndroid 17 QuickSwitch需要に備えること、に対して正に働く。一方、stock端末の大多数では挙動が変わらず、価値はrooted環境のQuickSwitch需要に比例する。**owner decisionとしては「(a) rebase後に検証込みで37対応を実施する」「(b) 37対応は需要観測まで先送りし、rebase後も35..36のままにする」の2択が提示できる。**

## 7. 結論と実装Issue

1. **問い1**: 引き上げの根拠は「API 34以降のcompat chain差分ゼロの実績」「Android 17にrecents関連のannounced変更なし」「API 37固有分岐が16-devに存在しない」ことであり、妥当性はruntime検証とQuickSwitchエコシステム確認を経て初めて確立される。単純な定数引き上げは「Android 17でprovider構成可能」の宣言を検証に先行させるため、Epic Non-goalsどおり先に採用しない。compat面の列挙は§3.3。
2. **問い2**: V37 factoryは機能上は不要（VBaklavaが `>=` 分岐で成立）だが、pattern整合の受け皿として `framework-17.jar` 追加＋空subclass moduleの形で実装Issueの判断対象にする。追加の実効的意味は「検証で差分が出た場合の先置き」である（§4）。
3. **問い3**: minSdk 35化はAPI差分ではなくrelease前の製品サポート境界決定（upstream PR #7262）であり、実害はQuickSwitch構成済みrooted環境のAPI 29〜34に集中する。サポート境界の判断材料は§5.3のとおりADR-0018 device test matrix上のowner decisionへ提示する。
4. **問い4**: launcher側recentsの価値はQuickSwitch構成環境に集中し、stock端末ではsystem overviewで日常利用が成立している。owner decisionは「検証込みで37対応を実施するか、需要観測まで先送りするか」の2択（§6.3）。
5. **実装Issue**: §6.3の選択肢(a)が採られた場合に備え、[実装Issue #524](https://github.com/nunu1733/NunuLauncher/issues/524)「[Phase 2][Implementation]: API 37 (Android 17) Quickstep provider対応の検証と有効化 — rebase後の新baseline上で実施」を起票した。内容: runtime検証（QuickSwitch構成可能なAPI 37環境でのoverview動作）、検証結果に応じた `QUICKSTEP_MAX_SDK` 変更とV37 module追加の判断、サポート境界のADR-0018 matrix反映。**本Issue（#520）自体は実装を含まない。**
6. 製品判断が必要な項目（サポート境界、37対応の先送り可否）は、本書§5.3/§6.3を材料としてowner decisionに付される。判断が確定した時点でADR-0018のdevice test matrix更新を行う（ADR-0018 Decision 7の規定どおり#520の結論を待つ運用）。

## 8. 未確認範囲とリスク

- **runtime観測なし**: 本書は静的解析と一次ソース・docsの参照のみである。API 37上でLawnchairがproviderとして機能するか（overviewの表示・task切替・live tile相当の挙動）は、QuickSwitch構成可能な環境（rooted実機/emulator）での検証を待つ必要がある。debug buildはSDK範囲を通過するが、`isRecentsComponent` は端末構成依存のため、通常のエミュレータではprovider動作を再現できない。
- **QuickSwitch module内部の未確認**: meta-data `xyz.paphonb.quickstepswitcher.minSdk/maxSdk` のmodule側での消費は、key名・PR #7262の言い回し・公式docsから推定したものであり、module source（skittles9823/QuickSwitch）の直接確認は行っていない。
- **framework-17.jarの入手方法未確認**: V37 module追加時に必要なprebuiltの取得経路（upstreamがframework-16.jarをどう入手したかを含む）は実装Issueでの確認事項である。
- **Android 17 QPR minor（SDK_INT_FULL）の定数値未確認**: API 37 minor 1 が `3700001` になるという想定は未検証である（local `android-37.0` はminor 0のみ）。
- **upstream動向**: 16-dev headは2026-10-04時点で `43a21b43` のまま前進しておらず、upstream自身もAPI 37対応（maxSdk 37化・V37 factory）に着手していない。upstreamが着手した場合の扱いはADR-0018のanchor刷新手続きに従う。
- **#521との境界**: 本書のcompat面列挙（§3.3）は「recents/quickstep提供の成立」に限定する。targetSdk 37引き上げに伴うbehavior changes（BAL、widgetメモリ上限、MessageQueue等）は [#521](https://github.com/nunu1733/NunuLauncher/issues/521) の対象であり、本書では列挙のみを行った。

## 9. Prior art

| 対象 | URL | 確認日 | 採用するpatternまたは不採用理由 |
|---|---|---|---|
| AOSP SystemUI `OverviewProxyService.java`（platform/frameworks/base main。blob `e3cf4119`） | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/recents/OverviewProxyService.java | 2026-10-04 | recents providerが `config_recentsComponentName` + `QUICKSTEP_SERVICE` bindで決まる機構の一次根拠。#520のgate分析の土台として採用 |
| AOSP framework `config_recentsComponentName`（`frameworks/base/core/res`。既定値 `com.android.launcher3/com.android.quickstep.RecentsActivity`。RRO overlay可） | cs.android.com framework base config.xml、CSDN等のPMS/SystemUI解析記事による裏付け（https://blog.csdn.net 系記事。一次はframework source） | 2026-10-04 | provider決定が端末構成（overlay）でありhome roleでないことの根拠。blogは補助根拠として扱い、Lawnchair側の読み取り実装（`LawnchairApp.kt:192-211`）と突き合わせて採用 |
| Android 17 behavior changes（all apps / target 17） | https://developer.android.com/about/versions/17/behavior-changes-all 、https://developer.android.com/about/versions/17/behavior-changes-17 | 2026-10-04 | recents/quickstep関連のannounced変更ゼロの確認根拠。launcher隣接項目（BAL、widgetメモリ上限等）は#521へ分離 |
| Android 17 overview（API 37、QPR 37.1/37.2） | https://developer.android.com/about/versions/17 | 2026-10-04 | API levelの公式確認 |
| upstream PR #7262「chore: Prepare for eventual release」 | https://github.com/LawnchairLauncher/lawnchair/pull/7262 | 2026-10-04 | minSdk 35化が「quickswitch compatibility to just 15-16」というrelease判断であることの根拠 |
| QuickSwitch公式docs（root必須のMagisk module。companion appはAndroid 14+非対応） | https://docs.lawnchair.app/integrations/quickswitch 、module repo https://github.com/skittles9823/QuickSwitch | 2026-10-04 | QuickSwitchの役割（provider構成のsystemlessな切替）とエコシステム制約の根拠。`quickstep_incompatible` 文言との対応もここから確認 |
| QuickSwitchのAndroid 14以降での不具合報告（community） | https://www.reddit.com/r/LawnchairLauncher/comments/1lnm6qc/i_keep_getting_this_error_in_quick_switch | 2026-10-04 | エコシステム側にforkの外の制約が存在することの補助根拠。owner decisionの前提記載にのみ使用 |

## 10. Change history

- 2026-10-04: 初版。機構確認（§2）、問い1〜4の回答（§3〜§6）、結論と実装Issue起票（§7）、未確認範囲（§8）、Prior art（§9）を記録。対象: 16-dev `43a21b43d7cc7850ab54e14b1a57dc9646685f35`、fork main `a65a1d169c743ef28e7f07c4717b5efc08c9e1b1`。
