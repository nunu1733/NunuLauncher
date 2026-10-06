# Issue #537 General route crash — 証跡索引

Issue #537（`BasePreferenceManager$FontPref` `$stable` NoSuchFieldError）の調査記録 [issue-537-general-route-crash-investigation.md](../issue-537-general-route-crash-investigation.md) が参照する証跡の一覧。commit `d5111b5909` の調査session（2026-10-06、emulator `issue526_api37_pixel_9a`）で取得した。

## ファイル

- `general-route-crash-logcat.txt` — cache-echo reproduction build（`Lawnchair.16.Dev.(575e37b).github.debug.apk`）をemulatorへinstallしGeneral routeを開いた際のlogcat抜粋。`ComposeInternal`のcomposition errorと`AndroidRuntime: FATAL EXCEPTION`の2ブロック。スタックはIssue #537報告（`GeneralPreferences.kt:103`）と同一。
- `fontpref-no-stable-crash-apk-dexdump.txt` — クラッシュAPKの`classes14.dex`における`BasePreferenceManager$FontPref`のクラス定義抜粋。Static fieldsが空＝Compose compiler生成の`$stable`フィールドが欠落していることが根拠。
- `fontpref-stable-healed-apk-dexdump.txt` — 修復後ビルドの`classes14.dex`における同一クラスの定義抜粋。`public static final int $stable`が存在する。
- `build-cache-echo-commands.txt` — 根因チェーン5/6に関わるコマンドとキャプチャ出力のトランスクリプト。§1〜§4（2026-10-06）はfresh worktreeビルドのcache復元集計、`--rerun-tasks`実行、`javap`での回復確認。§5（2026-10-07追補）は修復後のcache状態で`:compileLawnWithQuickstepGithubDebugKotlin`のtask単位`FROM-CACHE`と健全な出力を直接観測したもの。「初回の破損出力がcache経由で流れた」は§5観測と整合する最有力の推論であり、初回のtask単位ログは未保存（詳細はtranscript冒頭の注記とassessment本文）。
- `537-general-route-ok-head-3019ca6.png` — HEAD `3019ca6782`ビルドでのGeneral route正常表示（Font (experimental)配下5項目+Icons、クラッシュ0）。
- `537-general-route-ok-healed-575e37b.png` — 修復後の`575e37baad`ビルド（クラッシュcommitそのもの）でのGeneral route正常表示。
