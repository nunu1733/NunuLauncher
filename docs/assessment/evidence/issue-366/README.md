# Issue #366 Organizer hub（T-01）emulator evidence

> Status: Captured（2026-09-19。spec Test oracle HUB-AC-01/02/07の実機captureと、本実装PRの補助証跡）
> Spec: [specs/366-organizer-hub-shell/spec.md](../../../../specs/366-organizer-hub-shell/spec.md)
> Runtime: 専用AVD `issue142_api36`（Android 16 / API 36、cold boot、1080x2400 @ 420dpi）。
> APK: 実装PR headでbuildしたdebug APK（`Lawnchair.15.Dev.(412e680).github.debug.apk`）。
> 自動test: `OrganizerHubPreferencesInstrumentationTest`（12 tests）と
> 既存 `ManualOrganizationPreferencesInstrumentationTest`（41 tests、無編集green）を
> 同一AVD上で実行し全件成功。CIでは`organizer-instrumentation-issue52-tests` jobが正本。

## Captures（実解像度のまま保存）

| file | 条件 | 証跡 |
|---|---|---|
| `home-screen-entry-ja-light.png` | 設定 → ホーム画面（ja / light） | HUB-AC-01: General groupのmanual organization行の隣に「オーガナイザー」入口row。既存row群はすべて維持（HUB-AC-05の補助証跡） |
| `hub-opened-from-entry-row-ja-light.png` | 入口rowタップ直後（ja / light） | HUB-AC-01: 入口rowからhub（T-01）が開く。status card領域（「整理案を確認」CTA＋診断導線）と材料群。本AVDのdurable状態はnever organizedのためdurable status行なし（HUB-AC-02のfail-closed描画の実機一致） |
| `hub-en-light-never-organized.png` | hub直起動（en / light） | 同上（en）。`PreferenceActivity`のroute deep linkでcold-process起動（spec 271 DS-AC-10相当の初期化経路を通る） |
| `hub-en-dark-never-organized.png` | hub直起動（en / dark） | HUB-AC-07/視覚収束: dark theme追従 |
| `hub-ja-dark-never-organized.png` | hub直起動（ja / dark） | 同（ja）。新規stringのja resource解決 |
| `hub-ja-light-200pct-never-organized.png` | hub直起動（ja / light / font scale 200%） | HUB-AC-07: 200%で再flowし、status card行・CTA・診断導線・材料headingが到達可能（clipping/critical actionの消失なし） |

## 再現手順

```bash
# hub直起動（route deep link）
adb shell "am force-stop app.lawnchair.debug; am start -W \
  -n app.lawnchair.debug/app.lawnchair.ui.preferences.PreferenceActivity \
  -e 'app.lawnchair.ui.preferences.DESTINATION_ROUTE' \
  '{\"type\":\"app.lawnchair.ui.preferences.navigation.HomeScreenOrganizer\"}'"

# 入口row経由
adb shell "am start -n app.lawnchair.debug/app.lawnchair.ui.preferences.PreferenceActivity \
  -e 'app.lawnchair.ui.preferences.DESTINATION_ROUTE' \
  '{\"type\":\"app.lawnchair.ui.preferences.navigation.HomeScreen\"}'"
# → 「オーガナイザー」rowをタップ（座標は端末依存）

# 言語 / 外観 / font scale
adb shell cmd locale set-app-locales app.lawnchair.debug --locales ja
adb shell cmd uimode night yes
adb shell settings put system font_scale 2.0
```

備考:
- 本AVDは未整理のfresh状態のため、status cardはnever organized（durable status行なし）
  で描画される。durable status各行（restorable / restored-or-expired / unresolved /
  checking / run進行中の隠蔽）は`OrganizerHubPreferencesInstrumentationTest`が
  spec 271と同一stringで自動検証する。
- Back（system）でhubからホーム画面設定へ戻ることを確認済み（TO-BE §9: hub Back=設定へ）。
