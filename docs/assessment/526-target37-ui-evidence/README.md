# PR #526 targetSdk 37 UI検証 — emulator証跡索引

PR #538（spec 526実装）のemulator matrix検証で取得したスクリーンショット。commit `d5111b5909`以降に追加された`api37-phone-backup-*`は、Issue #537解消後の追補分（head `3019ca6782`ビルド、API 37 Pixel 9a emulator、PreferenceActivity配下のbackup/restore route）。それ以外はPR #538本文の検証matrixが参照する修正前後の証跡。

## backup/restore route（#537解消後の追補分）

- `api37-phone-backup-overflow-menu.png` — Settingsダッシュボードの⋮オーバーフローメニュー。Create backup / Restore backup / Restore Nova backupの可視導線。
- `api37-phone-backup-route-portrait.png` — Create backup routeの縦画面（preview・What to back up・Create fully表示）。
- `api37-phone-backup-route-landscape.png` — 同routeの横画面（回転、reflow・inset解決）。
- `api37-phone-backup-route-resize-720.png` — `wm size 720x1616`への縮小（resize）でのreflow。検証後`wm size reset`で復元。
- `api37-phone-backup-route-fontscale20-portrait.png` / `api37-phone-backup-route-fontscale20-landscape.png` — fontScale 2.0での縦/横。
- `api37-phone-backup-route-ja.png` — per-app locale `ja`での表示（バックアップを作成 / レイアウトと設定 / 壁紙 / 作成）。
- `api37-phone-backup-restore-picker.png` — Restore backup導線がSAF document pickerを起動した状態。

## それ以前の証跡（PR #538本文のmatrixが参照）

`api37-phone-scenario1-*`、`api37-phone-scenario3-*`、`api37-phone-scenario4-*`、`api37-phone-fontscale13/20*`、`api37-phone-talkback-notice.png`、`api37-phone-locale-ja-notice.png`、`api37-phone-organizer-hub-*`、`api37-phone-prefs-input-ime-visible.png`、`api36-phone-*`、`api37-tablet-*`。項目ごとの対応はPR #538本文のemulator matrix表を参照する。
