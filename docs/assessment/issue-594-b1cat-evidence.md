# Issue #594 B1-CAT確認のdurable証跡

spec 594 Verification（B1-CAT実機確認＝必須・gating。必須証跡は「capture発生log」「capture時の4field snapshot値」「最終DB/layout destination」）の実行記録。実施日: 2026-10-14。環境: emulator `issue526_api37_pixel_9a`（API 37、google_apis、serial `emulator-5554`）。

> **device経路の根拠**: benchmark §7のowner判断（2026-09-26、[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)）により「オーナー判断以降、実機は計測に使用しない。repository内evidenceはemulator実績が正」。spec Verificationの「実機で取得」表記はこのowner判断に従い、本記録（emulator）がrepository evidenceの正である。promise経路の実機観察（best-effort・non-gating）は別途。

- 対象: 実装PR [PR #596](https://github.com/nunu1733/NunuLauncher/pull/596) head `09f5e0a079`（B1-CAT実験時点）→ round 1対応head `72b213e53f`。launcher APK `Lawnchair.16.Dev.(09f5e0a).github.debug.apk`
- 実験詳細: 実施sessionの全コマンド・時刻・結果は実験作業directoryの`b1cat-run-record.txt`に記録（一時artifact。本書はdurable summary）
- 実施session: ZCode sess `bdac19a3-9350-4108-aa6d-191465d5ad94`（solo保守）

## 手順（normativeどおり）

1. launcher install（`-r -d`）→ 既定HOME → 起動。workspaceにフォルダ3個を作成（homeedit「Add to folder…→New folder」UI。id25 Games / id26 Work / id27 Tools、page 2）
2. 設定 → Home screen →「Where to place new apps」→「By category」→ mapping editor（Game/Productivity/Tools行）で各フォルダを選択 → **Done**。prefs確認（`run-as app.lawnchair.debug`）: `pref_new_app_destination.xml` が `category`、`app_destination_category_mappings.xml` の `mappings_v1 = 0|GAME|25;0|PRODUCTIVITY|26;0|TOOLS|27`
3. install実験（normative手順）: `pm install-create -i com.android.vending --install-reason 4 -r -S <size>` → `pm install-write -S <size> <sid> base` → `pm install-commit <sid>`。**destination判定前に `dumpsys package <pkg>` のinstallReason行で `installReason=4` をoracle記録**（4試行すべて記録済み。plain `adb install`は理由不明のため自動追加が起きないことも再確認）
4. 各試行後: `adb logcat -d`（capture log）、`adb shell uiautomator dump`＋スクリーンショット、favorites DB query（配置先container/rank）

## 結果（B1-CAT会計: 成立）

| 試行 | アプリ | signal経路 | capture log | 最終DB destination | 追加操作 |
|---|---|---|---|---|---|
| A | `com.nunu594.catgame`（manifest `appCategory="game"`。/tmpで実build、binary manifestでappCategory=0確認） | S2→GAME | `SessionCommitReceiver`→`ItemInstallQueue` queue投入（20:15:52） | `container=25`（Games）`rank=2`（末尾。既存子rank不変） | **0** |
| B | `com.nunu594.catproductivity`（appCategory=7） | S2→PRODUCTIVITY | queue投入（20:16:16） | `container=26`（Work）`rank=2` | **0** |
| C | `app.lawnchair.benchmark.target02`（appCategoryなし・非com.google.*） | 解決不能 | queue投入（20:16:34）＋**`AppDestination: category destination unresolved: SIGNAL_UNAVAILABLE`**（typed fallback。package名なし契約どおり） | desktop `screen=1 (3,0)`＝上流既定の空きセル。既存アイテム移動なし | 上流既定どおり |
| D | `com.google.nunu594tool`（`com.google.*`） | S5→TOOLS | queue投入（20:16:59） | `container=27`（Tools）`rank=2` | **0** |

- **4field snapshot値について**: snapshotのraw文字列はqueue内部のみでlogに出ない（既存設計）。代わりに (a) capture logの有無、(b) resolved側の最終DB container（= snapshotのfolderIdが反映される先）、(c) resolverが決定的純粋関数であること（`AppDestinationCategoryResolverTest`/`CategoryModeCaptureTest`のJVM oracleでwire形式を直接固定）により確認した。fallback側（試行C）は`UPSTREAM` snapshot選択であり、typed logが直接証拠になる。
- 成功側にAppDestination logが出ないのは仕様どおり（fallback時のみ記録・package名出力禁止）。
- FileLog（`files/log-0`）が全試行の永続記録。

## 会計（spec Benchmark B1-CAT）

- mapping設定後の、S2/S5/S1で解決できたカテゴリ新規アプリ: **追加操作0（重み付きコスト0）** — 試行A/B/Dで成立
- mapping外アプリ: 上流既定（baseline動作と同じ） — 試行Cで成立
- coverage限定（owner判断）どおり、promise経路は本会計の対象外

## 限界

- S1 override経路は未試行（organizer user-definedカテゴリauthoringの手順が別途必要。S1最優先は`CategoryModeCaptureTest`のJVM oracleで担保）
- promise経路（best-effort/non-gating）、work profile分離の実機確認、再flush配置不変の実機scenarioは未試行（構造はJVM oracleで担保）
- 観察メモ: homeedit executor経由のfolder操作直後、workspace表示がDBと一時的に同期しない（再起動で整合。本機能のcapture pathとは無関係）
