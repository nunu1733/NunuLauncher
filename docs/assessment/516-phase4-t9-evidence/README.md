# T9実測 — 15↔16実APK同一data roundtrip（Epic #516 Phase 4）

> Status: accepted（emulator leg。実機legはowner decision待ち。ADR-0018 Decision 5 / spec AC-3/AC-4）
> 実行日: 2026-10-13
> 対応: [specs/516-16-rebase-phase4/spec.md](../../specs/516-16-rebase-phase4/spec.md) AC-3、[plan §6](../../specs/516-16-rebase-phase4/plan.md)

## 環境とAPK identity

- emulator: 新規disposable AVD `issue516_phase4_t9_api36`（system-images;android-36;google_apis;arm64-v8a、pixel_7_pro profile、1080x2400/420dpi、`-no-snapshot-save`）。serial `emulator-5558`（実行時）。保護対象（emulator-5554/5556・実機Pixel）は不使用。
- **R APK（15 fork・rollback点候補）**: `Lawnchair.15.Dev.(fef1c27).github.debug.apk`
  - source: R_candidate `fef1c27d497f2927b8792ea2cbadd8d5f53c6583`（cutover直前main head。PR #589 merge commit）
  - sha256: `55c6206c8fca33ca3b68c23369cd8fbdaa334bd7b185f03a3dc5d7dbfa62620a`（[r-apk-sha.txt](r-apk-sha.txt)）
  - versionCode `1500020300` / targetSdk 35
- **16 APK（cutover head）**: `Lawnchair.16.Dev.(20f7b9f).github.debug.apk`
  - source: 統合head S `20f7b9f33dd31501cb7a589b8d941b2da5be816c`
  - sha256: `c5ae1ae0d859352be07c4991f33cb849bac2a99a7daa64e7852f90c0c46633e8`（[16-apk-sha.txt](16-apk-sha.txt)）
- 両APKとも同一local debug keystoreでbuild（署名一致。CI build不使用）。
- 各install前に `dumpsys package` でversionName/versionCodeを記録（下表）。

## dataセット（Rで作成した同一data）

- install R → home設定（`cmd package set-home-activity`）→ default workspace import（folder「Google」+7子・hotseat 4項目・2画面）
- 実ユーザー操作: workspace icon移動2件（Photos/Settingsのcell移動。favorites行の実書込み）
- 設定変更: Lawnchair設定 → Home screen → Infinite scrolling ON（`pref_infiniteScrolling=true` をshared_prefsへ書込み）
- 結果: favorites 15行（folder含む）・workspace 2画面・prefs 3 XML・app_icons.db・organizer_recovery.db（recovery_points 0行）・datastore（preferences.preferences_pb・live-information.preferences_pb）・`files/downgrade_schema.json`

## 手順（plan §6どおり。各段階でcold-process独立読取）

read N = `am force-stop app.lawnchair.debug` → pidof確認（不在）→ `run-as` で databases/shared_prefs/files をtar取得 → file sha256・favorites全行dump（`_id,itemType,container,screen,cellX,cellY,title,intent`）・`PRAGMA user_version` を比較。起動検証 = HOME intent → `topResumedActivity` が `app.lawnchair.debug/app.lawnchair.LawnchairLauncher` ・pidof存在・logcat `FATAL EXCEPTION` 0件・uiautomator dumpでworkspace構造（Google folder・Page 1 of 2・hotseat）同一・screenshot保存。

## 結果

| stage | APK | versionName | 起動 | FATAL | favorites | schema | 備考 |
|---|---|---|---|---|---|---|---|
| read0 | R(15) | 15.Dev.(fef1c27) | ✓ | — | 15行（baseline） | 33 | pidof不在確認のうえ取得 |
| **15→16** | 16 | 16.Dev.(20f7b9f) | ✓ RESUMED | 0 | **read0と完全一致** | 33 | launcher DB file byte一致 |
| **16→15（rollback leg）** | R(15) | 15.Dev.(fef1c27) | ✓ RESUMED | 0 | **read1と完全一致** | 33 | `adb install -r -d` 必要（下記） |
| **15→16** | 16 | 16.Dev.(20f7b9f) | ✓ RESUMED | 0 | **read0と完全一致** | 33 | UI構造同一（folder・page・hotseat） |

### 比較詳細

- **favorites**: 全stageで全行IDENTICAL（read0↔read1↔read2↔read3、intent列含む）。launcher DB fileはread0↔read1でbyte一致、read2でもbyte一致。
- **schema**: `PRAGMA user_version` = 33で全stage不変（#522契約どおりmigrationなし）。
- **rollback leg（read1→read2）**: 15は16書込みstateを**完全に保持**（prefs XMLは16追加key `workspace_screen_order`/`idp_grid_name`/`last_prediction_enabled_state` を含め同一、予測cache XMLも保持）。行喪失・key喪失なし。
- **16書込み（read0→read1）の差分分類**（全て説明済みの良性書込み）:
  - `shared_prefs/com.android.launcher3.prefs.xml` への追加key 2件（`workspace_screen_order=0, 1`・`idp_grid_name=4_by_6`）— 16のDeviceProfile/grid名の永続化。追加のみで喪失なし。
  - `device.prefs.xml` の `smartspaceWidgetId`/`hotseatWidgetId` 再割当（3→7, 2→8。rollback時に再び再割当 7→15, 8→14）— launcher内部widgetの再bind。favoritesにapp widget行は不在のためlayout影響なし。
  - `app_icons.db` 再生成・`files/{all_apps,hotseat}_predictions.xml` 新規・`files/log-2`・`preferences-shm`・`datastore/preferences.preferences_pb`・`organizer_recovery.db`（journal churn、全table行数不変）。
  - `files/downgrade_schema.json`: 不変。
- **versionCode downgrade**: 16のversionCode > 15のため、rollback方向のinstallは `adb install -r -d` が必要（`-d`なしでは `INSTALL_FAILED_VERSION_DOWNGRADE` で失敗）。実運用のrollbackはtagからのrelease再発行（ADR Decision 5）が主経路であり、APK直接入替ではこの制約を記録する。

## 限界

- datasetに**ユーザーapp widgetを含まない**（smartspace/hotseatのlauncher内部widgetのみ）。widget ID remap契約はPhase 2 T4 oracle（`RestoreProfileRemapTest`等）が所有する。
- organizer操作（apply/recovery record作成）を含まない（T9は日常操作dataの互換が対象。recovery codecはT6、実DB applyはT1〜T8が所有）。
- google_apis非root emulator。provider構成（priv-app/RRO）は対象外（#545 matrixが所有）。
- **実機leg（Pixel 9a / API 37）未実施** — ADR Decision 5 rev10のとおり、本emulator証跡と残存リスク（実機固有migration経路・provider構成差）に基づくowner decisionで代替可能。owner確認をEpic commentで募る。

## 成果物

- `reads/read{0..3}-sha256-files.txt`（file単位sha256）・`reads/read{0..3}-favorites.sql`（favorites全行dump）・`reads/read{0..3}-ls-R.txt`・`reads/read{0..3}-pidof.txt`
- screenshot 4枚（`00-r-first-home.png`〜`03-16-final-home.png`）
- `port-units.log`（Phase 4 port適用記録。本T9の直接的成果物ではないが同一PRで保管）
