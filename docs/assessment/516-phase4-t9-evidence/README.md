# T9実測 — 15↔16実APK同一data roundtrip（Epic #516 Phase 4）

> Status: accepted（emulator leg。実機legはowner decision待ち。ADR-0018 Decision 5 / spec AC-3/AC-4）
> 実行日: 2026-10-13（rev4でのやり直し実行。初回実行はPR #590 review round 1でS head確定前の記録として取替え）
> 対応: [specs/516-16-rebase-phase4/spec.md](../../specs/516-16-rebase-phase4/spec.md) AC-3、[plan §6](../../specs/516-16-rebase-phase4/plan.md)

## 環境とAPK identity

- emulator: disposable AVD `issue516_phase4_t9_api36`（system-images;android-36;google_apis;arm64-v8a、1080x2400/420dpi、`-no-snapshot-save`）。serial `emulator-5558`（実行時）。保護対象（emulator-5554/5556・実機Pixel）は不使用。実行前に `adb uninstall app.lawnchair.debug` でdata完全消去（fresh install）。
- **R APK（15 fork・rollback点）**: `Lawnchair.15.Dev.(fef1c27).github.debug.apk`
  - source: R_candidate `fef1c27d497f2927b8792ea2cbadd8d5f53c6583`（cutover直前main head。PR #589 merge commit）
  - versionCode `1500020300` / targetSdk 35
- **16 APK（cutover head）**: `Lawnchair.16.Dev.(88218eb).github.debug.apk`
  - source: 統合head S₂ `88218ebbca...`（最終統合tree。sha256は [apk-shas.txt](apk-shas.txt)）
- 両APKとも同一local debug keystoreでbuild（署名一致。CI build不使用）。

## dataセット（Rで作成した同一data）

- install R → home設定 → default workspace import（folder「Google」+7子・hotseat 4項目・2画面）
- 実ユーザー操作: workspace icon移動2件（page 2のGmail/Photos/Settings周辺のdrag＆drop。favorites行の実書込み）
- 設定変更: Settings →「Allow home screen rotation」をOFF→ON（`pref_allowRotation=true` をshared_prefsへ書込み。toggle操作2回の実書込み）
- proposal dialog操作: 空白long-press →「Organize your Home screen?」→ LATER
- 結果: favorites 15行・workspace 2画面・prefs 3 XML・app_icons.db・organizer_recovery.db・datastore・`files/downgrade_schema.json`

## 手順（plan §6どおり。各段階でcold-process独立読取）

read N = `am force-stop app.lawnchair.debug` → pidof不在確認 → `run-as` で databases/shared_prefs/files をtar取得 → file sha256一覧・favorites全行dump（`_id,itemType,container,screen,cellX,cellY,title,intent`）・`PRAGMA user_version`・`pref_allowRotation` 値を比較。起動検証 = HOME → `topResumedActivity` が `app.lawnchair.debug/app.lawnchair.LawnchairLauncher`・logcat `FATAL EXCEPTION` 0件・uiautomator dumpでworkspace構造（Google folder・Page 1 of 2）同一・screenshot保存。

## 結果

| stage | APK | versionName | 起動 | FATAL | favorites | schema | rot pref |
|---|---|---|---|---|---|---|---|
| read0 | R(15) | 15.Dev.(fef1c27) | ✓ RESUMED | — | 15行（baseline） | 33 | true |
| **15→16** | 16 | 16.Dev.(88218eb) | ✓ RESUMED | 0 | **read0と完全一致** | 33 | true |
| **16→15（rollback leg）** | R(15) | 15.Dev.(fef1c27) | ✓ RESUMED | 0 | **read1と完全一致** | 33 | true |
| **15→16** | 16 | 16.Dev.(88218eb) | ✓ RESUMED | 0 | **read0と完全一致** | 33 | true |

### 比較詳細

- **favorites**: 全stageで全行IDENTICAL（intent列含む。reads/read{0..3}-favorites.sql）。launcher DB fileの変更なし。
- **schema**: `PRAGMA user_version` = 33で全stage不変（#522契約どおりmigrationなし）。
- **rollback leg（read1→read2）のprefs**: 15は16書込みstateを**key単位で完全保持**（read1の6 keyがread2でも全て同一value。追加key `idp_grid_name`/`workspace_screen_order` 相当の16書込みは今回の16起動では発生せず、発生した場合も初回実行で保持を実証済み）。行喪失・key喪失なし。
- **16書込み（read0→read1）の差分分類**（全て説明済みの良性書込み）:
  - `app_icons.db` 再生成・`files/{all_apps,hotseat}_predictions.xml` 新規作成・`files/log-2` 追記・`preferences-shm`（journal churn）・`datastore/preferences.preferences_pb` 再直列化・`organizer_recovery.db`（journal churn、table行数不変）。
  - `shared_prefs/com.android.launcher3.prefs.xml` の変更なし（read0↔read1で同一hash維持をfile diffで確認 — launcher DBとprefs本体は不変）。
  - `files/downgrade_schema.json`: 不変。
- **versionCode downgrade**: 16のversionCode > 15のため、rollback方向のinstallは `adb install -r -d` が必要（`-d`なしでは `INSTALL_FAILED_VERSION_DOWNGRADE` で失敗）。実運用のrollbackはtagからのrelease再発行（ADR Decision 5）が主経路であり、APK直接入替ではこの制約を記録する。

## 限界

- datasetに**ユーザーapp widgetを含まない**（smartspace/hotseatのlauncher内部widgetのみ）。widget ID remap契約はPhase 2 T4 oracle（`RestoreProfileRemapTest`等）が所有する。
- organizer操作（apply/recovery record作成）を含まない（T9は日常操作dataの互換が対象。recovery codecはT6、実DB applyはT1〜T8が所有）。
- google_apis非root emulator。provider構成（priv-app/RRO）は対象外（#545 matrixが所有）。
- **実機leg（Pixel 9a / API 37）未実施** — ADR Decision 5 rev10のとおり、本emulator証跡と残存リスク（実機固有migration経路・provider構成差）に基づくowner decisionで代替可能。owner確認をEpic commentで募る。

## 成果物

- `reads/read{0..3}-{sha256-files,favorites,pref-rotation,schema,pidof}.txt`
- screenshot 4枚（`00-r-home.png`〜`03-16-final-home.png`）
- `apk-shas.txt`（両APK sha256）
- `port-units.log`（Phase 4 port適用記録。本T9の直接的成果物ではないが同一PRで保管）
