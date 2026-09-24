# PR #412 / Issue #398 representative evidence — `BOTTOM_REGION_V1`（Bottom-half layout）

> Captured: 2026-09-23、emulator `nunu_qpr2_api36_1`（API 36 / Platform 36.1、4 columns × 5 rows grid、clean Google APIs image、synthetic layout）
> App: `app.lawnchair.debug` debug build（branch `issue-398-spec`、head `f680c663fe` 時点の実装コード。APK version suffix `067acd3`）
> Flow: 実UI経由（Settings → Home screen → Organizer hub → Organization strategy picker → 「Organize as is」→ selection面で全7 candidate選択 → usage access JIT は Skip → preview 確認 → Apply）

| File | What it shows |
|---|---|
| `01-before.png` | 適用前のhome（page 1）: Photos/Contacts/Gmail（row 2, 1-based）/Drive/Settings（row 3）/YouTube（row 4）が上・中段に散在し、隙間が多い。Google folder はrow 5に既存配置 |
| `02-strategy-picker.png` | Organization strategy picker（T-05 materials面）: 9 strategyのradio行に新規 `Bottom-half layout`（"Uses the lower half of each screen as the main area and keeps the top open, adding screens when needed. Widgets stay in place."）が表示され、選択可能 |
| `03-preview.png` | run面のpreview: `Strategy: Bottom-half layout` の明示 + counts（9 placements move / 7 apps to add / 12 preserved / 3 new folders / 0 new pages / Moved across screens: 1 / Kept in place by the strategy: 1）と Proposed changes の具体行 |
| `04-after.png` | 適用・検証後のhome（page 1）: **全配置アイコンが下部優先領域（0-based rows 2-4）内に配置され、rows 0-1は意図的余白として空く**。3新規folder（Photography / Productivity / Tools）が領域内に配置。既存Google folderは元位置（row 5左端）に保持 = "Kept in place by the strategy: 1" |

## 対応

- spec 398 AC-12「representative physical-device before/preview/after evidence」の代替（emulator evidence）として取得。spec AC-12が許容するIssue本文明示改訂 + owner decision comment のセット（2026-09-23、Issue #398上）と合わせてAC-12充足の根拠とする。
- 物理デバイスでの追加evidenceは追跡Issue（#351/#345前例と同じ扱い）に委譲する。

## 再現手順

1. `nunu_qpr2_api36_1` AVD起動 → `adb root`
2. debug APKをinstall → `cmd package set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher`
3. `launcher_5_4_4.db` の favorites に散在配置を挿入（本記録のコミット時点では初期レイアウト。再現にはDB直挿入または手動配置）
4. Home → Settings → Home screen → Organizer → Organization strategy で `Bottom-half layout` を選択
5. 戻って `Organize as is` → 候補全選択 → Continue → （usage access JIT は Skip）→ preview 確認 → `Apply reviewed organization` → `Organization was applied and verified.` 表示 → HOME でafter撮影
