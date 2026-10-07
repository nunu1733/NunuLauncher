---
issue: "#524"
status: draft
tier: M
requirements: []
updated: 2026-10-07
---

# API 37 (Android 17) でのQuickstep provider対応 — QUICKSTEP_MAX_SDK 37への検証込み引き上げ

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
build config定数（`QUICKSTEP_MAX_SDK`）の変更と、provider構成環境でのruntime検証、
G3（recents遷移PIのcreator mode）判断、ADR-0018 device test matrix反映のみを扱う。
入力は[#520 assessment](../../docs/assessment/issue-520-quickstep-api37-research.md)（accepted）と
#527 spec G3節の委譲である。

## Problem

QuickSwitch等でprovider構成済み（platform `config_recentsComponentName` がLawnchairを指す）
Android 17環境では、Lawnchair release buildのquickstep gate（`QUICKSTEP_MAX_SDK = 36`）が閉じており、
Lawnchairをrecents providerとして使えない。#520 assessmentは「検証に先行する宣言」を禁止しており、
引き上げはruntime検証とセットでのみ行える。保守者実機がPixel 9a / API 37であり、
forkが16-dev追随を続ける限り、検証込みの対応をrebase後の新baseline上で先に済ませる必要がある。

## Owner decisions（確定済み。本specに記録する）

1. **37対応を実施する**（[#520 assessment](../../docs/assessment/issue-520-quickstep-api37-research.md) §6.3選択肢(a)）。
   根拠: 保守者実機がPixel 9a / API 37、forkのupstream追随方針、rebase Phase 2完了と
   provider構成手段の実行可能性確認により、検証込みで実施する前提が整っている。
2. **サポート境界は min 35 / max 37**（16-devの35..36に37を加える）。API 29〜34のprovider対応は
   復活させない。根拠: upstream PR #7262のrelease意図、QuickSwitch companion app自体が
   Android 14+非対応というエコシステム制約（#520 assessment §5.3）、forkが検証所有する範囲の最小化。
3. **compatLib V37 moduleは追加しない**（#520 assessment §4選択肢(b)）。runtime検証で隠蔵API差分が
   見つかった場合のみ、別判断で追加する。根拠: VBaklava factoryが `>=` 分岐でAPI 37でも成立、
   `framework-17.jar` の入手方法未確認、差分ゼロ検証済みでないmodule追加はbuild surfaceだけを増やす。
4. **G3（`SystemUiProxy` recents遷移PIのcreator mode）は本Issueのprovider/runtime matrix環境で
   実測して判断する**（#527 spec G3節の委譲）。既定は legacy `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`
   の保持（senderはWMShell=system特権でBAL例外が成立するため）。provider構成環境の実測で
   BAL blockの証拠が出た場合のみ、granular mode（G4先例 `TaskAnimationManager` の
   `ATLEAST_BAKLAVA ? ALLOW_ALWAYS : ALLOWED` pattern）への移行を検討する。機械置換はしない
   （#527 review round 1の指摘どおり。`ALLOW_ALWAYS` はlegacy `ALLOWED` と同義ではない）。

## Baseline（本specの前提事実）

行番号はすべて `e8aced7dbee4480c178a9cb994b8d55686ef41ce`（Phase 2 review closure後の
`issue-532-phase2-restart` head）固定。作業branchはこのcommitから分岐する。

- 2段gate: `compatible = Build.VERSION.SDK_INT in BuildConfig.QUICKSTEP_MIN_SDK..QUICKSTEP_MAX_SDK`、
  `recentsEnabled = compatible && isRecentsComponent`
  （`lawnchair/src/app/lawnchair/LawnchairApp.kt:74-76`）。範囲は `build.gradle:185-186` の
  `quickstepMinSdk = "35"` / `quickstepMaxSdk = "36"` で、`:203-206` でbuildConfigFieldと
  manifestPlaceholdersへ反映される。debug buildTypeのみ `build.gradle:267-274` で0..100000へ
  overrideされ、releaseでのみSDK範囲gateが効く。
- `isRecentsComponent` はplatform resource `android:string/config_recentsComponentName` を読み、
  自分自身の `com.android.quickstep.RecentsActivity` を指すことを要求する
  （`LawnchairApp.kt:348-369`）。非一致時はlogcatへ "disabling recents" を出す。
- 構成済みだが非対応SDK（`isRecentsComponent == true && !recentsEnabled`）では
  `quickstep_incompatible` sheetを表示する（`LawnchairApp.kt:399-402`）。
- `lawnchair/AndroidManifest.xml` のmeta-data `xyz.paphonb.quickstepswitcher.minSdk/maxSdk` は
  同一placeholderで反映される（定数変更だけで両者へ伝播する）。
- compatLibのfactory解決は `ATLEAST_BAKLAVA -> QuickstepCompatFactoryVBaklava()` の `>=` 判定
  （`systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt:48-58`）であり、
  API 37でも解決失敗しない。
- G3対象: `quickstep/src/com/android/quickstep/SystemUiProxy.kt:188-202`
  （`getRecentsPendingIntent`。creator側 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`、
  `FLAG_MUTABLE + FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT`）。行番号は#527 spec G3節と一致する。
- applicationId: `github` flavorは `app.lawnchair`（`build.gradle:309`）、`debug` buildTypeのみ
  `applicationIdSuffix ".debug"` で `app.lawnchair.debug`（`build.gradle:267-268`）。
  `checkRecentsComponent()` はoverlay値のpackageNameと実行中processのpackageNameの完全一致を
  要求する（`LawnchairApp.kt:362`）ため、provider構成overlayはbuild variant別に値を変える。
- recents providerはhome roleではなく端末system構成（`config_recentsComponentName`、RRO overlay等）
  で決まる（#520 assessment §2。AOSP `OverviewProxyService` 一次根拠）。通常構成（Pixel等）では
  `QUICKSTEP_MAX_SDK` を上げてもLawnchair側recentsは有効化されず、system側overviewが継続する。
- ADR-0018 Decision 7（device test matrixへのadvertised range明記とowner decision）と
  Decision 8（rebase差分への混入禁止）が前提である。

## Benchmark

対象課題: B1〜B7（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標: **B1〜B7の増分ゼロ**。本変更はprovider対応SDK範囲の宣言（platform契約の追従）であり、
編集・適用・復帰の各flowに新規の中断要因を追加しない。通常構成の端末では挙動が変わらず、
provider構成環境では既存のquickstep機能が新たに成立するだけであるため、
新たなベンチマーク課題の起票は不要と判断する（#527と同じ判断）。

## Prior art

- AOSP SystemUI `OverviewProxyService.java`（platform/frameworks/base main。blob `e3cf4119`）。
  確認日2026-10-07（#520 assessment §9の再確認）。採用: recents providerが
  `config_recentsComponentName` + `QUICKSTEP_SERVICE` bindで決まる機構の一次根拠。
- AOSP framework `config_recentsComponentName`（`frameworks/base/core/res`。RRO overlay可）。
  確認日2026-10-07。採用: provider構成手段としてRRO overlayを /product/overlay へpreinstallする方式は、
  QuickSwitchと同じ機構（端末system構成のsystemlessな切替）であることの根拠。
- QuickSwitch公式docs（root必須のMagisk/KernelSU/APatch module。companion appはAndroid 14+非対応）。
  確認日2026-10-07。採用: 構成手段の先例と、API 29〜34対応を復活させない判断の材料となる
  エコシステム制約。
- upstream PR #7262「chore: Prepare for eventual release」。確認日2026-10-07。採用:
  min 35 / max 36がrelease判断であることの根拠。upstreamはAPI 37対応（maxSdk 37化・V37 factory）に
  着手していない。
- Android 17 behavior changes（all apps / target 17）。確認日2026-10-07。採用:
  recents/quickstep関連のannounced変更ゼロの確認。
- #527 spec-lite G3節（repository内既存契約）。確認日2026-10-07。採用: G3のcreator mode判断の
  本Issueへの委譲と「機械置換しない」制約。

## Outcome

API 37環境で、provider構成済み環境のrelease buildでもLawnchairがQuickstep providerとして機能し、
overview（表示・task切替）が破綻なく成立する。通常構成の端末では挙動が変わらない。
G3のcreator mode判断が実測証拠つきで記録され、サポート境界（min 35 / max 37）が
ADR-0018 device test matrixへ反映される。

## Scope

- `build.gradle` の `quickstepMaxSdk` を36→37へ変更する（`quickstepMinSdk = "35"` は不変）。
  manifest meta-data `xyz.paphonb.quickstepswitcher.minSdk/maxSdk` は同一placeholderで自動反映される。
- provider構成環境でのruntime検証と証跡記録（Verification matrix (a)〜(e)）。構成手段は
  /product/overlay へのRRO overlay preinstall（QuickSwitchと同じ機構）で、overlay値は
  対象build variant（debug用 / release用）別に用意しrun毎にpreflight照合する。
- G3判断: 実測証拠をPRへ記録する。legacy modeの保持が既定であり、BAL blockの証拠が出た場合のみ
  granular modeへの移行を最小modeで検討する（コード変更は証拠が出た場合に限り、上流fileへの
  最小差分とし近傍にissue番号付きcommentを残す）。証拠が出ない場合のコード変更は行わない。
- ADR-0018 Decision 7のdevice test matrix更新（quickstep advertised support range 35..36→35..37の
  明記）は **実装PR側** で行い、rebase branch上のADR-0018 revision 7に対して改訂する。
  main側のADR-0018（revision 6）は編集しない。
- compatLib V37 moduleは含まない（runtime検証で隠蔵API差分が実測された場合に別判断で追加する）。

## Non-goals

- targetSdk 37 behavior changes全般（#521）、fork UI表示（#526）、BAL/IntentSender granular移行本体
  （#527）、LAN/ECH/CT通信（#528）の対象面。
- QuickSwitch module自体の改修（fork外のエコシステム）。
- rebase差分への混入（ADR-0018 Decision 8）。実装PRはbase `issue-532-phase2-restart` とし、
  Phase 4 cutoverまでmergeしない。
- `wmshell/`（vendored shell契約。#527 G6と同じ境界）。
- API 29〜34のprovider対応の復活（`quickstepMinSdk` は変更しない）。
- `framework-17.jar` のprebuilts追加とcompatLib V37 module追加。
- `QUICKSTEP_MAX_SDK` 以外のSDK値・build設定の変更。

## Behavior scenarios

### Scenario: provider構成済みAPI 37でrelease buildのoverviewが成立する

Given root化API 37 emulatorへrelease用RRO overlay（`config_recentsComponentName` →
`app.lawnchair/com.android.quickstep.RecentsActivity`）をpreinstallし、Lawnchair release buildを
default HOMEに設定する
When 変更後release build（maxSdk 37）でrecents gestureを発火する
Then `isRecentsComponent=true` かつ `recentsEnabled=true` となり、overview表示・task切替が
破綻なく成立し、`quickstep_incompatible` sheetは表示されない。
debug build（SDK gate 0..100000 override、debug用overlay `app.lawnchair.debug/...`）でも同様に成立する。

### Scenario: 変更前相当release buildはprovider gateが閉じたまま失敗する（効果対照）

Given 同一release用overlay環境でmaxSdk 36のrelease build（変更前相当）を用意する
When recents gestureを発火する
Then `compatible=false` → `recentsEnabled=false` となり、`quickstep_incompatible` sheetが表示され、
overviewが破綻するfailure signature（logcat "disabling recents" ほか）を記録する。
この記録は変更の効果対照であり、永続的な状態変更は行われない。

### Scenario: 通常構成とAPI 36で無影響・回帰なし

Given overlay無効（`config_recentsComponentName` がLawnchairを指さない）のAPI 36/37 emulator
When 通常操作（home・recents gesture・default HOME設定）を行う
Then 挙動変化がなく（recentsはsystem側提供のまま、sheet非表示）、API 36 debug＋overlay有効の
provider動作にも回帰がない（maxSdk 37は36を含む）。

### Scenario: G3のrecents遷移PI発火でBAL blockが出ない

Given provider構成済みAPI 37環境（(b)のrelease用overlay＋release build maxSdk 37を主証跡とし、
(a)のdebug構成を補助とする）でLawnchairがrecents providerとして動作している
When recents gestureを発火しshell→launcher遷移（`SystemUiProxy.getRecentsPendingIntent` 経路）を
発生させる
Then 遷移が成功し、logcat `ActivityTaskManager` にBAL blockが出ない。blockの証拠が出た場合のみ
failure signatureを記録し、granular mode移行の判断材料とする（変更しない場合、creator modeは
legacyのまま）。

### Scenario: overlay構成に失敗した場合は検証を実施せず宣言もしない

Given RRO overlayのpreinstallまたは `config_recentsComponentName` の反映に失敗し、
`isRecentsComponent` がtrueにならない
When provider構成のruntime検証を試みる
Then 検証を実施せず未確認範囲としてIssue/PRへ記録し、`QUICKSTEP_MAX_SDK` の変更を行わない
（検証に先行する宣言の禁止。#520 assessment §3.2）。

## Verification

- **provider構成手段**: root化google_apis API 37 emulator
  （AVD `issue526_api37_pixel_9a`。実在確認済み）上で、framework-resの
  `config_recentsComponentName` を本appの `RecentsActivity` へ向けるRRO overlayを
  /product/overlay へpreinstallする（QuickSwitchと同等の構成手段。実行可能性probe実施済み）。
  overlay値は **build variant別に2種を用意**し、対象runの前に差し替える（rm＋push＋reboot）:
  - debug用: `app.lawnchair.debug/com.android.quickstep.RecentsActivity`（probeで実証済みの値）
  - release用: `app.lawnchair/com.android.quickstep.RecentsActivity`
  `checkRecentsComponent()` は完全一致要求（`LawnchairApp.kt:362`）のため、overlay値と
  実行中buildのpackageNameの不一致はprovider pathへ入れない。
- **provider-path run毎のpreflight（証跡化必須）**: `cmd overlay lookup --user 0 android
  android:string/config_recentsComponentName` の出力がそのrunのbuildのpackageNameと一致すること、
  およびSystemUI側参照（`dumpsys activity service com.android.systemui/.SystemUIService` の
  `LauncherProxyService.mRecentsComponentName`）が同一componentであることを各runの証跡へ記録する
  （overlay差し替え忘れ・無効ケースとの取り違え防止）。
- **matrix（provider pathの主証跡）**:
  - (a) API 37 debug build＋debug用overlay → `isRecentsComponent=true`、overview表示・task切替・
    破綻なし（debugは `build.gradle:267-274` でSDK gate 0..100000 override、`compatible` 常時true）。
  - (b) API 37 release build（変更後 maxSdk 37）＋release用overlay → `compatible=true`、
    overview成立。
  - (c) API 37 release build（変更前相当 maxSdk 36）＋release用overlay → `compatible=false` →
    `recentsEnabled=false` → `quickstep_incompatible` sheet（`LawnchairApp.kt:399-402`）と
    overview破綻のfailure signature記録（変更の効果対照）。
  - (d) overlay無効（通常構成。overlayをdisableまたは不一致値のまま）API 36/37 → 挙動変化なし
    （recentsはsystem側提供のまま、sheet非表示）。
  - (e) API 36 debug＋debug用overlay → provider動作に回帰なし（maxSdk 37は36を含む）。
- 観測手段: 操作結果、logcat（`LawnchairApp` の "disabling recents" / `ActivityTaskManager` の
  BAL block / SystemUI `OverviewProxyService`）、screenshot/録画をPRへ添付する。
- **G3観測**: provider構成環境のうち **(b) release構成（release用overlay＋maxSdk 37）を主証跡**、
  (a) debug構成を補助としてScenario 4を観測し、creator mode判断（保持またはgranular移行提案）を
  証拠つきでPRへ記録する。
- **実機の位置づけ**: 保守者実機Pixel 9a / API 37は非rootのためprovider構成できず、
  実機owner確認は通常構成での無影響確認を担当する。provider path検証の主証跡はemulator matrixであり、
  実機でのprovider構成確認はEpic #516 Phase 3実機matrixへ引き継ぐ旨をPRへ明記する
  （#527/#526と同じ扱い）。
- **既存test suite**: 新規の永続testは追加しない（test-audit判断: 定数assertは低価値で、
  振る舞いはprovider構成matrixが一次証拠）。既存unit/instrumentation gateをgreenで通す。
- **tier Mの計測要求**: PR本文へ
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  の計測結果をreportする（`build.gradle` は上流由来fileであるため）。
- **branch/merge**: 作業branchは `issue-524-api37-quickstep`
  （`e8aced7dbee4480c178a9cb994b8d55686ef41ce` から分岐）。実装PRはbase `issue-532-phase2-restart`。
  ADR-0018 Decision 3（branch方針）によりPhase 4 cutoverまでmergeしない。

## Accessibility and localization

- UI変更・文字列追加なし（build config定数変更と検証が本体）。`quickstep_incompatible` sheetは
  既存UIであり、文言の追加・変更をしない。matrix (b)/(c) でsheetの表示/非表示が意図どおり
  切替わることを確認する（表示条件は `isRecentsComponent == true && !recentsEnabled`）。

## Change history

- 2026-10-07: Draft created for #524（入力: #520 assessment accepted（PR #525）、#527 spec G3委譲）。
- 2026-10-07: Review round 1（PR #544コメント）対応 — provider構成overlayをbuild variant別
  （debug用 `app.lawnchair.debug/...`、release用 `app.lawnchair/...`）に固定し、run毎の
  preflight照合（`cmd overlay lookup` とSystemUI `LauncherProxyService` 参照の一致証跡）を
  Verificationへ追加。BaselineへapplicationId事実（`build.gradle:267-268`/`:309`）と
  `checkRecentsComponent` の完全一致要求（`LawnchairApp.kt:362`）を追記。G3観測の主証跡を
  (b) release構成へ同期、Scenarios/Scopeを同期。
