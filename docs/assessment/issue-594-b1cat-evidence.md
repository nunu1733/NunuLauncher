# Issue #594 B1-CAT確認のdurable証跡

spec 594 Verification（B1-CAT確認＝必須・gating。必須証跡は「capture発生log」「**capture時の4field snapshot値**」「最終DB/layout destination」）の実行記録。実施日: 2026-10-14。環境: emulator `issue526_api37_pixel_9a`（API 37、google_apis、serial `emulator-5554`）。

> **device経路の根拠**: benchmark §7のowner判断（2026-09-26、[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)）により「オーナー判断以降、実機は計測に使用しない。repository内evidenceはemulator実績が正」。spec Verificationの「実機で取得」表記はこのowner判断に従い、本記録（emulator）がrepository evidenceの正である。promise経路の実機観察（best-effort・non-gating）は別途。
> **実験head**: 修正後head `0dd033c95e`（launcher APK `Lawnchair.16.Dev.(0dd033c).github.debug.apk`）。初回実験（head `09f5e0a079`）はreview指摘（Doneがcategory modeを選択しない不整合・snapshot値未取得）を受けて再実行したものであり、本記録が正とする。
> 実施session: ZCode sess `bdac19a3-9350-4108-aa6d-191465d5ad94`（solo保守）。実験詳細（全コマンド・時刻・logcat・DB query・queue XML全文）は実験作業directoryの`b1cat2-run-record.txt`と`queue-xml-trial{A,B,C,D}.txt`に記録（一時artifact。本書はdurable summary）。

## 手順

1. launcher install（修正後head。`-r -d`）→ 既定HOME → 起動。workspaceにフォルダ3個（id25 Games / id26 Work / id27 Tools、page 2）
2. **UI手順のみでcategory modeを設定**: 一旦「上流の既定」へ切り替え（`pref_new_app_destination=upstream`確認）→ 設定 → Where to place new apps →「By category」→ mapping editor（Game/Productivity/Tools行）で各フォルダを選択 → **Done**。prefs実値で `pref_new_app_destination=category` ＋ `app_destination_category_mappings.xml` の `mappings_v1 = 0|GAME|25;0|PRODUCTIVITY|26;0|TOOLS|27` を確認（**修正後headのDoneはmapping保存とcategory mode選択を同時に行う。UI手順とprefs状態の整合を実証**）
3. install実験（normative手順）: `pm install-create -i com.android.vending --install-reason 4 -r -S <size>` → `pm install-write` → **`pm install-commit`直後にqueue XML（`files/apps_to_install.xml`）の`destination_policy`属性を取得**（flush前の永続値。capture手法: 試行Aはlauncher processをforce-stopしてSESSION_COMMITTED receiverのみでflush遅延を作り、試行B/C/Dは`Launcher.onPause`の`pauseModelPush(FLAG_ACTIVITY_PAUSED)`利用——設定を前面にしたままcommit→enqueue→XML永続化→cat→HOME復帰でflush）。**destination判定前に `dumpsys package <pkg>` のinstallReason行で `installReason=4` をoracle記録**（4試行すべて）
4. 各試行後: `adb logcat -d`（capture/typed fallback log）、favorites DB query（最終container/rank）、スクリーンショット

## 結果（B1-CAT会計: 成立。4/4試行で4field snapshot実値取得）

| 試行 | アプリ | signal経路 | **capture時4field snapshot（queue XML実値）** | installReason | 最終DB destination | 追加操作 |
|---|---|---|---|---|---|---|
| A | `com.nunu594.catgame`（manifest `appCategory="game"`） | S2→GAME | `folder\|25\|0\|com.nunu594.catgame` | 4 | `container=25`（Games）`rank=2`（末尾。既存子rank 0/1不変） | **0** |
| B | `com.nunu594.catproductivity`（appCategory=7） | S2→PRODUCTIVITY | `folder\|26\|0\|com.nunu594.catproductivity` | 4 | `container=26`（Work）`rank=2` | **0** |
| C | `app.lawnchair.benchmark.target02`（appCategoryなし・非com.google.*） | 解決不能 | `upstream\|0\|0\|app.lawnchair.benchmark.target02` | 4 | workspace `screen=1 cell(3,0)`＝上流既定の空きセル。既存アイテム移動なし | 上流既定どおり |
| D | `com.google.nunu594tool`（`com.google.*`） | S5→TOOLS | `folder\|27\|0\|com.google.nunu594tool` | 4 | `container=27`（Tools）`rank=2` | **0** |

- **4field snapshotのwire形式はJVM oracle（`CategoryModeCaptureTest`）と一致**（kind|folderId|userSerial|packageName。試行Aの実値 `folder|25|0|...` はresolver決定論の実機裏付け）。
- 試行Cではtyped fallback log `AppDestination: category destination unresolved: SIGNAL_UNAVAILABLE` を記録（package名なし契約どおり。成功側にAppDestination logが出ないのも仕様どおり）。
- FileLog（`files/log-0`）が全試行の永続記録。

## 会計（spec Benchmark B1-CAT）

- mapping設定後の、S2/S5/S1で解決できたカテゴリ新規アプリ: **追加操作0（重み付きコスト0）** — 試行A/B/Dで成立
- mapping外アプリ: 上流既定（baseline動作と同じ） — 試行Cで成立
- coverage限定（owner判断）どおり、promise経路は本会計の対象外

## 限界

- S1 override経路は未試行（organizer user-definedカテゴリauthoringの手順が別途必要。S1最優先は`CategoryModeCaptureTest`のJVM oracleで担保）
- promise経路（best-effort/non-gating）、work profile分離の実機確認、再flush配置不変の実機scenarioは未試行（構造はJVM oracleで担保）
- **付帯観測（本機能scope外）**: install SESSION_COMMITTED経由のcold start直後のprocessで2回、launcher-loader threadとmain threadの循環待ち（Dagger lockを介したdeadlock）を観測（thread dump `/data/anr/trace_00`で確認）。通常起動経路では発生せず、issue #565で追跡されたcold-start死枠と同系の観測であり、本機能のcapture pathとは無関係（capture自体はMODEL_EXECUTORの既存queue処理内で完結）。追跡が必要な場合は別Issueとする。

## Change history

- 2026-10-14: 再実験版（head `0dd033c95e`）。初回実験（head `09f5e0a079`、4field snapshot未取得・Done修正前）をreview指摘（[PR #596 comment](https://github.com/nunu1733/NunuLauncher/pull/596#issuecomment-6168284980)）により置き換えた。4/4試行でcapture時4field snapshot実値を取得（flush前のqueue XML直接読取）。