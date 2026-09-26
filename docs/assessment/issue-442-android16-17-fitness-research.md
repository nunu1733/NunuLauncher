# Android 16/17 での日常利用の適性確認と upstream 同期判断（Issue #442、暫定結論まで）

> Status: accepted（暫定結論まで。最終結論は保守者の実機観測記録とともに本書へ追記する。spec [specs/442-android16-17-fitness-upstream-sync/spec.md](../../specs/442-android16-17-fitness-upstream-sync/spec.md) AC-8）
> Research date: 2026-09-26
> Agent session: ZCode（GLM-5.3-flash）。対象spec/plan: Revision 2（accepted、head `a441eba228bfcda94bd044905df8381fdfa6c790`）
> Issue: https://github.com/nunu1733/NunuLauncher/issues/442（Epic #439、再焦点化方針メモ 2026-09-24承認 Revision 5）

## 1. 問いと判断基準

[Issue #442](https://github.com/nunu1733/NunuLauncher/issues/442)の問い: Lawnchair 15 beta 3 baseline（`505dbc40`）のforkは、Android 16（API 36）/ Android 17（API 37）で日常利用として十分に機能するか。また、進むべき道は (A) 15 beta 3のまま進む、(B) 15系のより新しいupstreamへ同期する、(C) Lawnchair 16へのrebaseを専用Epic+ADRで計画する、のどれか。

判断基準（Issue本文の引用）:

- **A（15 beta 3のまま）**: チェックリストでquickstep無効以外の重大な日常利用の欠陥がなく、7日間crashなし。編集負担の改善（#445〜#450）が15 baseline上で完結できる。
- **B（15系同期）**: 15系にbaseline以降の実質的なcommit（security、model/schema、日常利用の修正）が存在し、patch surfaceの増加が管理可能であること。
- **C（16 rebase計画）**: 次のいずれか。(1) API 36/37でquickstep無効以外の日常利用の重大な欠陥が確認され、15系では修正されない見込み、(2) ADR-0014で選ぶ操作面の方式が16-devで既に解決されている領域（workspace/選択状態等）に依存し、15上で作るとrebaseコストが増える、(3) 保守者がAndroid 16/17でのtargetSdk引き上げ等を製品要件とする。

保守者の作業指示（2026-09-26、[Issue #442コメント](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5844023268)）: 「原則方針C（Rebaseを視野に入れる）を想定」。これは調査開始前のinvestigation directionであり、結論Cを先取りしない。本書ではC-(3)の判断材料として参照し、A/B/C全基準を証拠に適用する。

## 2. upstream同期対象の確定（AC-1）

確認日: 2026-09-26T07:22:59Z。方法: `git ls-remote upstream`（読み取りのみ）とGitHub API（`gh api repos/LawnchairLauncher/lawnchair/...`）。

### 2.1 branches（`git ls-remote upstream refs/heads/...`の出力引用）

```text
505dbc40e6154c05158b5d0271c45f6a885a411b	refs/heads/15-beta
505dbc40e6154c05158b5d0271c45f6a885a411b	refs/heads/15-dev
d73e44f978e7244428078e775ccc2e184cc4cbbf	refs/heads/16-dev
```

### 2.2 tags（`git ls-remote upstream 'refs/tags/v15*' 'refs/tags/v16*'`の出力引用）

```text
4fd596016bad5cf2504da14e8520dfa7cde57e01	refs/tags/v15.0.0-beta1
e508b9ff6d04bb752eca1063cd93eaeb9ee04498	refs/tags/v15.0.0-beta2
ae805192387322c9af9a6f687d031d86174e67fc	refs/tags/v15.0.0-beta2.1
505dbc40e6154c05158b5d0271c45f6a885a411b	refs/tags/v15.0.0-beta3.0
```

v16* tagは存在しない。

### 2.3 GitHub Releases（`gh api repos/LawnchairLauncher/lawnchair/releases?per_page=100`）

API応答のうち15系以降に関係するreleaseの引用（全29件のうち、v14以前は省略。2026-09-26取得）:

```json
[
  {"tag": "nightly", "name": "Lawnchair Nightly", "prerelease": true, "published": "2026-09-25T23:32:17Z"},
  {"tag": "v15.0.0-beta3.0", "name": "Lawnchair 15 Beta 3", "prerelease": true, "published": "2026-04-18T11:11:40Z"},
  {"tag": "v15.0.0-beta2.1", "name": "Lawnchair 15 Beta 2.1", "prerelease": true, "published": "2026-02-28T12:13:31Z"},
  {"tag": "v15.0.0-beta2", "name": "Lawnchair 15 Beta 2", "prerelease": true, "published": "2025-12-25T13:25:21Z"},
  {"tag": "v15.0.0-beta1", "name": "Lawnchair 15 Beta 1", "prerelease": true, "published": "2025-07-14T14:22:47Z"}
]
```

- source link（time-varying。API応答は時点で変化するため、上記引用が2026-09-26時点の観察記録である）: [releases API](https://api.github.com/repos/LawnchairLauncher/lawnchair/releases?per_page=100)、[tags API](https://api.github.com/repos/LawnchairLauncher/lawnchair/tags?per_page=100)。
- 最新のreleaseは **`nightly`（Lawnchair Nightly、prerelease、published 2026-09-25T23:32:17Z）**。起草時の未解決事項「GitHub Releasesの確認」を解消した。
- 15系の最新releaseは `v15.0.0-beta3.0`（published 2026-04-18）。16系のtag付きreleaseは存在しない。
- fork `nunu1733/NunuLauncher` のreleasesは **なし**（API応答: 空配列。source link: [fork releases API](https://api.github.com/repos/nunu1733/NunuLauncher/releases)）。メモ §2「GitHub Releaseなし」の再確認。

### 2.4 結論（選択肢Bの成立可否）

**`15-beta`/`15-dev` はbaseline commit `505dbc40` から1 commitも進んでおらず、15系に「より新しいupstream」は存在しない。選択肢Bは成立しない**（2026-09-24のIssue本文観察を2026-09-26に再確認）。メモ §2「上流との同期なし」は「同期すべき対象が存在しない」ことを意味すると正式に記録する。upstreamの実質的な進行は16-devのみであり、16-devへの同期/rebaseはAGENTS.mdの専用Epic+ADR要件の対象である。

## 3. 16-dev差分の概観（AC-2）

確認日: 2026-09-26。方法: GitHub compare API（`repos/LawnchairLauncher/lawnchair/compare/v15.0.0-beta3.0...16-dev`）とraw.githubusercontent.comによる16-dev head上の対象file参照。

### 3.1 差分規模

- source link（time-varying。16-devは進行中のため応答内容は時点で変化する。本節の数値は2026-09-26観察値）: [compare API `v15.0.0-beta3.0...16-dev`](https://api.github.com/repos/LawnchairLauncher/lawnchair/compare/v15.0.0-beta3.0...16-dev)。固定区間のpermalink: [505dbc40...d73e44f9](https://github.com/LawnchairLauncher/lawnchair/compare/505dbc40e6154c05158b5d0271c45f6a885a411b...d73e44f978e7244428078e775ccc2e184cc4cbbf)（2026-09-26観察時点の2 SHA固定）。
- `status: diverged`、**ahead_by 7,374 commits**、behind_by 33、比較APIのfiles一覧は300件上限に到達（全file差分の列挙は不可能。本格計測はrebase Epic側の `type: upstream` Issueで `measure_upstream_patch_surface.py` を使って行う。plan.mdのとおり本Issueでは実施しない）。
- 上限内で観測されたfileの主要領域: `fastlane/metadata` 183件（翻訳metadata）、`compatLib/src` 20件、`compose/features` 12件、`concurrent/src` 9件、`.github/workflows` 7件、`compatLib/compatLibVBaklava` 4件（新module）。**16-devはbuild構造の再編（compatLibのmodule分割、compose/concurrent/dagger/checks等の新top-level module）を含む大規模差分である**。
- 16-dev head: `d73e44f978e7244428078e775ccc2e184cc4cbbf`（2026-09-26観察。Issue本文記載の2026-09-24時点 `6889441e…` から前進している=活発に進行中）。

### 3.2 `QUICKSTEP_MAX_SDK=35` 引き上げの評価材料（16-dev上の対象file参照）

対象fileはすべて16-dev head `d73e44f978e7244428078e775ccc2e184cc4cbbf` に固定した参照である（raw linkはcommitに固定され不変）:

| 項目 | fork baseline（15 beta 3） | 16-dev head `d73e44f9`（raw link固定参照） |
|---|---|---|
| compileSdk | `release(36)` + minor 1（[baseline `build.gradle:29-36`](https://github.com/nunu1733/NunuLauncher/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/build.gradle)） | `release(37)` + minor 2、buildTools 37.0.0（[16-dev `build.gradle:24-30`](https://github.com/LawnchairLauncher/lawnchair/blob/d73e44f978e7244428078e775ccc2e184cc4cbbf/build.gradle)。行引用: `buildToolsVersion "37.0.0"`、`version = release(37) { minorApiLevel = 2 }`） |
| targetSdk | 35 | **37**（同上 `build.gradle:33`。行引用: `targetSdk = 37`） |
| quickstepMinSdk / quickstepMaxSdk | 29 / 35（[baseline `build.gradle:170-171`](https://github.com/nunu1733/NunuLauncher/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/build.gradle)。行引用: `final def quickstepMinSdk = "29"` / `final def quickstepMaxSdk = "35"`） | **35 / 36**（同上 `build.gradle:147-148`。行引用: `final def quickstepMinSdk = "35"` / `final def quickstepMaxSdk = "36"`） |
| compatLib factory分岐 | `ATLEAST_V -> QuickstepCompatFactoryVV()` まで（[baseline `LawnchairQuickstepCompat.kt:43-51`](https://github.com/nunu1733/NunuLauncher/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt)） | **`ATLEAST_BAKLAVA -> QuickstepCompatFactoryVBaklava()` が追加**（API 36=Baklava用。[16-dev `LawnchairQuickstepCompat.kt:44-56`](https://github.com/LawnchairLauncher/lawnchair/blob/d73e44f978e7244428078e775ccc2e184cc4cbbf/systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt)。[`QuickstepCompatFactoryVBaklava.java`](https://github.com/LawnchairLauncher/lawnchair/blob/d73e44f978e7244428078e775ccc2e184cc4cbbf/compatLib/compatLibVBaklava/src/main/java/app/lawnchair/compatlib/sixteen/QuickstepCompatFactoryVBaklava.java) は `@RequiresApi(36)` で `QuickstepCompatFactoryVV` を継承） |
| recents有効化条件 | `compatible && isRecentsComponent`、範囲29..35（[baseline `LawnchairApp.kt:67-69`](https://github.com/nunu1733/NunuLauncher/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/LawnchairApp.kt)） | 同一構造、範囲35..36（[16-dev `LawnchairApp.kt:60-62`](https://github.com/LawnchairLauncher/lawnchair/blob/d73e44f978e7244428078e775ccc2e184cc4cbbf/lawnchair/src/app/lawnchair/LawnchairApp.kt)。行引用: `private val compatible = Build.VERSION.SDK_INT in BuildConfig.QUICKSTEP_MIN_SDK..BuildConfig.QUICKSTEP_MAX_SDK`） |

評価: **16-devはAPI 36（Baklava）用のcompatLib factoryとSDK範囲35..36を実装済みである**。これは判断基準C-(2)（操作面の方式の16-dev依存）とは別レイヤの事実であり、Cの成立条件には数えない（§6.1.1のrebase計画材料として記録）。一方、**16-devでも `quickstepMaxSdk=36` であり、API 37（Android 17）のrecents有効化は16-devにも存在しない**。保守者実機（Pixel 9a / API 37）でのrecents復活は、rebase後も追加対応（QUICKSTEP_MAX_SDK引き上げとAPI 37動作確認）が必要である。この点はrebase用ADRの起草要件に含めるべきである。

## 4. 既知問題の切り分け（AC-3、failure signature/family単位）

確認日: 2026-09-26。方法: #304/#418のIssue本文・capture記録・run linkの照合（読み取りのみ）。

| Signature/family | 発生環境 | user-facing日常利用への直接証拠 | production exposureの静的根拠 | 分類 | Source（run/job/artifact link と最小signature行） |
|---|---|---|---|---|---|
| window focus保持occluder（標準ランチャー2例、SystemUI ANR dialog 3例、NotificationShade 1例。#304） | CI emulator（API 36.1、`nunu_qpr2_api36_1` 相当） | なし（CI instrumentation内のfocus gate失敗のみ。実機での同現象の報告なし） | 発生機構自体が未確定（#304 AC-3未完了）。SystemUI ANRはplatform processでありlauncherコード外の可能性 | **CI計測環境の問題（実機波及は未確定）** | [Issue #304](https://github.com/nunu1733/NunuLauncher/issues/304)（occluder分類表）。capture例: run [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012)（head `083c9029…`、pull_request）の `focusedWindow=mCurrentFocus=Window{... nexuslauncher/.NexusLauncherActivity}, frontmostPackage=com.google.android.apps.nexuslauncher` |
| SystemUI ANR / focus-gate系（#418 run 35828114497 attempt 4。7/133 failures） | CI emulator（API 36） | なし（同上） | #304のANR occluderと同一系統の可能性（分類時に照合）。launcher側の修正対象箇所は特定されていない | **CI計測環境の問題（実機波及は未確定）** | [Issue #418](https://github.com/nunu1733/NunuLauncher/issues/418)本文: run [35828114497](https://github.com/nunu1733/NunuLauncher/actions/runs/35828114497/attempts/4)（head `16688d1b…`）"failed 7 of 133 tests after the emulator reported `Application Not Responding: com.android.systemui`"。failure-time evidence: [artifact 10738053804](https://github.com/nunu1733/NunuLauncher/actions/runs/35828114497/artifacts/10738053804) |
| Compose timeout系（#418 attempt 3、#304の非gateフレイク run 34733180391、2026-09-25 run 36082413664の5秒timeout） | CI emulator（API 36） | なし | Compose UI testの待ち合わせ問題であり、production挙動の欠陥を示す証拠なし | **CI計測環境の問題（test harness側）** | #418本文: run [35828114497 attempt 3](https://github.com/nunu1733/NunuLauncher/actions/runs/35828114497/attempts/3) `CategoryOverridePreferencesInstrumentationTest.appRowMeetsMinimumFortyEightDpTouchTarget` で `ComposeTimeoutException` after 5 seconds。#304 change history: run [36082413664](https://github.com/nunu1733/NunuLauncher/actions/runs/36082413664) の `OrganizerDiagnosticsRouteInstrumentationTest.openRequestRowAndAwaitT15` で5秒 `ComposeTimeoutException`（report [10843192422](https://github.com/nunu1733/NunuLauncher/actions/runs/36082413664/artifacts/10843192422)） |
| `SlotWriter.moveSlotGapTo` / Activity-destroy process-crash family（#418 attempt 2、#304 2026-09-25記録） | CI emulator（API 36）。instrumentation process死亡 | なし（Activity destroy時のCompose SlotTable破損。日常利用中の同様crashの実機報告なし） | Compose runtime内の破損であり、launcher production code pathに直接根ざすかは未確定 | **未確定**（production exposureの静的根拠が確定できていない。#418が追跡中） | #418本文: run [35828114497 attempt 2](https://github.com/nunu1733/NunuLauncher/actions/runs/35828114497/attempts/2) `ArrayIndexOutOfBoundsException: length=320; index=-1`（`OrganizerHubPreferencesInstrumentationTest.restoreSuccessRepresentsTheRemainingPointAfterHubReturn`）。#304 change history: run [36082413664 attempt 2](https://github.com/nunu1733/NunuLauncher/actions/runs/36082413664) `java.lang.ArrayIndexOutOfBoundsException: length=320; index=-56` が `SlotWriter.moveSlotGapTo` → `CompositionImpl.dispose`（分類コメント [issuecomment-5825872134](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5825872134)） |
| `SnapshotStateObserver` worker-thread access（#304 2026-09-25記録） | CI emulator（API 36） | なし | test観測基盤のアクセス違反 | **CI計測環境の問題（test harness側）** | #304 change history: run [35886970989](https://github.com/nunu1733/NunuLauncher/actions/runs/35886970989)（#418 controlled run）。primary failureは `SnapshotStateObserver` のworker-threadアクセス、live artifact [10764201175](https://github.com/nunu1733/NunuLauncher/actions/runs/35886970989/artifacts/10764201175) |

Issue単位の要約: #304（OPEN、root cause未確定）と #418（OPEN）の全signatureは **CI emulator（API 36）上のinstrumentation計測で発生した記録のみ**であり、実機の日常利用で同種の問題が起きた直接証拠は2026-09-26時点で存在しない。ただしSlotWriter/Activity-destroy familyはproduction exposureの可能性を排除できていないため「未確定」とし、rebase計画のリスク評価（ADR起草時）に引き継ぐ。本調査のemulatorセッション（§5）では#304/#418のsignatureは観測されなかった。

## 5. 適性チェックリスト（AC-4）

### 5.1 agent実行分（API 36 emulator）

- **端末**: AVD `nunu_qpr2_api36_1`（Pixel 6、Android 16 QPR2 / API 36.1、arm64、1080x2400。CIのapi36 laneと同一構成。`docs/assessment/issue-132-operator-driven-mvp-dogfooding.md:51-53`）
- **build**: `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL（35s）。APK `build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(a441eba).github.debug.apk`。**対象commit SHA: `a441eba228bfcda94bd044905df8381fdfa6c790`**
- **確認日**: 2026-09-26（session時刻 16:45–17:32 JST）
- **方法**: `pm set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher` でHOMEロール設定後、adb input（tap/swipe/motionevent）によるUI操作。証跡screenshotは `docs/assessment/evidence-442/` に格納。

| 項目 | 結果 | 証跡 |
|---|---|---|
| HOME起動・描画 | **OK** — default launcherとして起動、widget/フォルダ/dock/QSBが正常描画 | `01-home-initial.png` |
| アプリドロワー（swipe up） | **OK** — 全アプリ一覧が表示、section付き | `02-drawer-open.png` |
| ドロワー内長押しpopup | **OK** — App info / Widgets / Uninstall / Customize が表示 | `04-drawer-photos-popup.png` |
| ページswipe | **OK** — ページ1↔2の移動、indicator表示 | `07-page2-after-install.png` |
| 新規アプリinstall→ホーム配置（`SessionCommitReceiver`→`ItemInstallQueue`→空きセル） | **OK** — `pm install --install-reason 4`（INSTALL_REASON_USER）でfixture app（`app.lawnchair.benchmark.target01`）をinstallすると、ページ2の空きcell(0,4)へアイコン追加を確認（favorites DB row 17: `screen=1, cellX=0, cellY=4`）。logcatに `Adding package name to install queue` を確認。**注意: `--install-reason 1`（POLICY）では追加されない（reason=USERのみqueue対象。`SessionCommitReceiver.java:76`）。adb直接installの既定reasonは1のため、adbでの検証にはreason 4の明示が必要** | `13-page2-icon-confirmed.png` |
| drag&drop（ページ跨ぎ移動） | **OK（1回成功）** — Benchmarkアイコンをページ2のDB cell(0,4)（`screen=1, cellX=0, cellY=4`）からページ1のDB cell(0,2)へ移動成功（DB確認: `screen=0, cellX=0, cellY=2`。本節の座標はすべてDB `favorites` の `screen/cellX/cellY` 値であり、UI上の視覚行番号とは別定義。grid 5行のためcellY=2は画面上は中央行に相当）。**adb gesture timing（motioneventの長押し→move遷移）は不安定で、複数回中1回成功。folder形成・Remove drop targetへのdropは自動化できず（popup開閉に吸収される）。人手dragでの追加確認を実機観測に委ねる** | `14-drag-icon-result.png` |
| フォルダ作成・開閉 | **未自動化確認** — 既存Googleフォルダは正常描画・開閉可（`01`/`39`）。新規フォルダのdrag生成は上記のgesture制約で未確認 | `01`, `39` |
| Remove drop target / undo snackbar | **未自動化確認** — drag開始がpopupに吸収され確認できず。実機観測に委ねる | — |
| アプリ起動・終了 | **OK** — Settings起動（`am start -W` TotalTime 0ms / WaitTime 18ms、warm start）、HOME復帰正常 | `21`（未添付。logcat記録） |
| ウィジェット追加 | **部分OK** — workspace長押しpopup→Widgets pickerの表示と展開（Chrome 4 widgets等）は正常。pickerからのdrag配置は上記gesture制約で未確認 | `29-widget-picker2.png` |
| 通知ドット | **部分OK** — launcherのNotificationListenerは許可済み（`settings get secure enabled_notification_listeners` に `app.lawnchair.debug/...NotificationListener` を確認）。shell発行のtest notificationではdot視認に至らず（system channel宛）。実機観測に委ねる | — |
| ドロワー検索 | **OK** — "sett"入力でSettings/Wi-Fi/Batteryが即時表示 | `23-drawer-search.png` |
| QSB（ホーム検索バー） | **OK** — tapでGoogle Search画面が開く | `37-qsb-tap.png` |
| 回転 | **未確認** — `user_rotation`設定がこのAVDで反映されず、landscape描画を確認できなかった。実機観測に委ねる | — |
| **recents（最近使ったアプリ）** | **無効を確認** — swipe up & holdでrecents overviewは表示されず、ドロワーが開くのみ（`22-recents-gesture.png`）。logcatに `RecentsView: reset - mEnableDrawingLiveTile: false`。**`QUICKSTEP_MAX_SDK=35`（`build.gradle:170-171`）によりAPI 36でrecentsが無効であることの実機挙動と整合**。launcher提供のrecents overview・PAUSE_APPSは使えない。**GestureNavのsystem側recents（3-button navigationのrecents button等、launcher外のsystem経路）での代替利用の成立は本セッションでは確認していない（未確認 / API 37実機観測へ引継ぎ。暫定判断の根拠には含めない）** | `22-recents-gesture.png` |
| 安定性（本セッション中） | **OK** — 約45分の操作セッションで `FATAL EXCEPTION` 0件、`ANR in com` 0件（logcat全量照合）。#304/#418のsignature（occluder、SystemUI ANR、Compose timeout、SlotWriter crash）は1件も観測されず | logcat照合 |

### 5.2 保守者実機分（Pixel 9a / API 37）の記録枠

> **この節は保守者が記録する。agentは記録しない。**

- 端末: Pixel 9a（tegu）、Android 17 / API 37。build fingerprint:
- 対象commit SHA（保守者がinstallするbuild）:
- 観測期間: 開始日 / 終了日（提案: 7日。**保守者確定欄**: 日数＝＿＿、crash収集方法＝＿＿（提案: `adb bugreport` または `logcat -b crash` の手動収集。Play Consoleなし））
- チェックリスト（emulatorで未確認に終わった項目を含む）:
  - [ ] ページswipe、アイコンdrag（同ページ/隣ページ/フォルダへのdrop、Remove drop target、undo snackbar 4秒窓）
  - [ ] フォルダ作成・開閉
  - [ ] アプリ起動・終了の体感と、異常があれば記録
  - [ ] ウィジェット追加・リサイズ・動作
  - [ ] 通知ドットの表示
  - [ ] 検索（drawer検索、QSB）
  - [ ] 回転・2 panel（`isTwoPanelEnabled` 関連。Pixel 9aは非2 panel端末なので「崩れないこと」の確認）
  - [ ] work profileの表示と操作（P2相当。emulatorでは非対象とした）
  - [ ] recents: launcher提供recentsが無効であることの確認と、GestureNavのsystem recentsで日常利用が成立するかの評価
  - [ ] 7日間のcrash/ANR（`logcat -b crash`、`dumpsys dropbox` 等。件数とsignatureを記録）
- 記録範囲の注意: public repositoryへcommitしてよい範囲（個別アプリ名等を含むscreenshotの可否）は保守者が判断する。
- **最終結論の確定条件**: 上記の実機観測結果が本書に記録され、§6の判断基準適用表が全証拠（agent実行分+実機観測）で更新されたとき、保守者がA/B/Cの最終結論を確定する。最終PRだけが `Closes #442` を使う（spec AC-8）。

## 6. 暫定結論（AC-5）

### 6.1 判断基準適用表

| 基準 | 証拠 | 適用結果 |
|---|---|---|
| A: quickstep無効以外の重大な日常利用の欠陥なし | §5.1のagent実行分: 欠陥なし（未確認項目はgesture制約によるもので、欠陥の証拠ではない）。ただし7日観測は未実施 | **条件付き成立**（7日観測の完了が残る） |
| A: 編集負担の改善が15 baseline上で完結できる | メモ §4.3: 視覚的編集画面（ADR-0014案B）は上流変更0〜1ファイルで15上で実装可能 | **成立** |
| B: 15系にbaseline以降の実質commitが存在する | §2.4: `15-beta`/`15-dev` はbaselineで停止。対象が存在しない | **不成立**（Bは除外） |
| C-(1): quickstep無効以外の重大な欠陥があり15系で修正されない見込み | §5.1: 欠陥の証拠なし | **不成立** |
| C-(2): ADR-0014の操作面方式が16-devで既に解決されている領域に依存するか | ADR-0014の対象（workspace/選択状態の操作面）は視覚的編集画面案が上流変更0〜1ファイルであり、15上で作ってもrebaseコストは小さい（メモ §4.3基準2）。**操作面の方式そのものは16-devで解決済みの領域に依存しない** | **不成立** |
| C-(3): 保守者がtargetSdk引き上げ等を製品要件とする | 保守者指示（2026-09-26、[issuecomment-5844023268](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5844023268)）「原則方針C（Rebaseを視野に入れる）を想定」。これはinvestigation directionであり、最終判断は保守者が行う | **保守者の最終判断に委ねる**（指示の存在は記録済み。成立条件の確定はAC-8の実機証拠と保守者判断を待つ） |

### 6.1.1 rebase計画時のsupport/patch材料（判断基準外の事実。Cの成立条件には数えない）

Cの判断基準外だが、結論がC方向になった場合のrebase計画の材料として次を記録する:

- 16-devはAPI 36（Baklava）用のcompatLib factory（`QuickstepCompatFactoryVBaklava`）とSDK範囲35..36を実装済みである（§3.2）。15 baselineのままではAPI 36/37でrecentsが無効であり、この解決は16-dev側に存在する。
- 一方、16-devでも `quickstepMaxSdk=36` であり、API 37（保守者実機）のrecents有効化は16-devにも存在しない。rebase後も追加対応が必要である。

### 6.2 暫定結論

**現時点ではA/C未確定とする。** 根拠:

1. **Bは不成立**（§2.4。15系に同期対象が存在しない）ため、AとCの2択になる。
2. Cの成立条件は、C-(1)不成立（§5.1に欠陥の証拠なし）、C-(2)不成立（§6.1。操作面の方式は16-dev依存ではない）、C-(3)は保守者の最終判断待ちであり、**現時点でCの成立条件は1つも確定していない**。
3. Aの判断基準（欠陥なし・7日crashなし）も現時点で反証されていない（agent実行分は欠陥なし。7日観測はAC-8で保守者が実施）。
4. 保守者の作業指示（方針C想定）はinvestigation directionであり、Cの成立条件を先取りしない（§1、[issuecomment-5844023268](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5844023268)）。
5. **暫定結論は「C-(3)の製品要件確定またはAC-8の実機証拠が入るまでA/C未確定」とし、A/Cのいずれに転んでも計画に使える材料（§2、§3、§4、§6.1.1）を本書に記録する。** 最終結論は保守者がAC-8の実機観測と判断基準の適用で確定する（§7）。

### 6.3 結論がC方向に確定した場合の専用Epic起票範囲とADR起草要件（暫定時点での整理）

- **専用Epicの起票範囲**（AGENTS.md「Lawnchair 16への変更は通常updateとして扱わず、専用EpicとADRを要求する」）:
  1. `type: upstream` Issue: 16-devのlocal object databaseへのfetchと、`measure_upstream_patch_surface.py` による本格的なpatch-surface計測（本Issueではcompare API概観のみ。§3.1）。
  2. `QUICKSTEP_MAX_SDK` の35→37引き上げの評価Issue（16-devは36まで。API 37のcompatLib factoryとrecents動作確認が必要。§3.2）。
  3. targetSdk 37への引き上げとAndroid 16/17 behavior changes（edge-to-edge、等）の調査Issue。
  4. fork側のorganizer/編集機能（#445〜#450）のrebase後の再検証計画。
- **rebase用ADRの起草要件**（[upstream-strategy.md](../../docs/engineering/upstream-strategy.md) §Upgrade policyの5比較軸を満たすこと）: product valueとAndroid version support、Launcher3 model/schema/eventの変更、Deck layoutの変化とproject patchの再適用cost、build/toolchainとdevice test matrix、rollback可能なrelease/migration path。ADR番号はEpic側で採番する（メモ §4.9）。
- **本Issue内でrebaseを着手しない**（メモ §6、Issue本文のリスク節）。

## 7. 実機観測と最終結論の確定条件

- 実機観測の記録枠と確定条件は §5.2 に定めた。
- 保守者が実機観測を完了し本書へ記録した後、最終結論（A/B/Cの確定）を本書の §6.2 の追記として確定する。その最終PRが `Closes #442` を使う。
- #447（ADR-0014）は暫定結論（本§6）を前提に起草を開始できるが、**受入は最終結論を前提とする**（spec Revision 2で確定。メモ §5）。

## 8. Change history

- 2026-09-26: agent実行分（調査1〜4と暫定結論）を記録。対象commit `a441eba228bfcda94bd044905df8381fdfa6c790`。upstream観察（ls-remote、Releases/tags API、compare API、16-dev上のraw file参照）は2026-09-26T07:22:59Z前後。emulatorセッションはAVD `nunu_qpr2_api36_1`（API 36.1）で2026-09-26 16:45–17:32 JSTに実施。実機観測（§5.2）と最終結論は保守者の記録を待つ。
- 2026-09-26: PR #466 review（[判定](https://github.com/nunu1733/NunuLauncher/pull/466#issuecomment-5844712515)）のFindings 1〜4に対応: (1) C-(2)をoracleどおり「不成立」へ修正し、quickstep/compat事実を§6.1.1「rebase計画時のsupport/patch材料（判断基準外）」へ分離、暫定結論を「A/C未確定」へ変更（Cの成立条件は現時点で1つも確定していないため）、(2) §5.1 recents行の「GestureNavのsystem側操作での日常利用は成立する」を「未確認 / API 37実機観測へ引継ぎ」へ修正し暫定判断の根拠から除外、(3) §2.3にAPI応答の引用とimmutable link、§3にcommit固定のraw linkと行引用、§4にrun/job/artifact linkと最小signature行を追加、(4) §5.1 drag&drop行の座標表記をDB `favorites` の `screen/cellX/cellY` 値に統一し、UI視覚行との対応を明記。
