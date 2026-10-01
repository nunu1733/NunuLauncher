# #452 organizer home entry — emulator evidence

- Issue: #452 / PR: #486
- 種別: **emulator証跡（補助）**。accepted specのAC-5（実機での操作数記録）とAC-6（実機TalkBack/Switch Access確認）の**実機分はowner確認事項**として残る（#449の「実機確認をownerが確認する」運用と同じ）。本書はPR reviewで参照可能なemulator側の記録である。
- 採取日: 2026-09-30
- Build: `Lawnchair.15.Dev.(3f6bd67).github.debug.apk`（commit `3f6bd67a6f` = review round 1対応後のicon修正込み。`app.lawnchair.debug`）
- 環境: emulator `nunu_qpr2_api36_1`（sdk_gphone64_arm64、API 36、1080x2400）。#441 benchmark fixtureでhomeがseed済みの状態。未配置候補を0件にするため、fixture aliasを持つtest APK（`app.lawnchair.debug.test`）をuninstallしてから採取した（採取中のhome画像の一部に配置済みfixtureが写る）。
- 前提: AI相談toggleは既定OFF（一度も変更していない）。`launcher_popup_order` は未保存（default）。

## 操作数（AC-5のemulator側記録）

採取された固定経路（候補0件・AI toggle OFF既定）:

| 操作 | 手段 | 証跡 |
|---|---|---|
| ① 空きスペース長押し | workspace空きセルを700ms長押し | `v2-05-op1-popup.png`（popup表示。順序: Organize home screen → Edit layout grid → Wallpaper & style → Widgets → Home settings） |
| ② 「Organize home screen」tap | popup項目tap | `v2-06-op2-run-surface.png`（設定Activityがrun面で起動。hub経由なし） |
| ③ 「Start organizing」tap | run面の開始CTA | `v2-07-op3-confirm-face.png`（確認面「Review the proposed organization」到達） |

**3操作 ≤ 4（目標内）**。方法選択面は出ていない（#443 OFF既定のskip）。未配置候補0件のため選択面も出ていない。

補足（正直な記録）: usage accessのJITプロンプト（「Use app usage to organize smarter?」）がrun開始直後に出ることがある（`v2-03-confirm-face.png`採取時に一旦出た → `v2-03b-after-jit.png`はSkip後）。Skipの選択状態はapp dataの寿命でリセットされる（再installで再出現を確認）。上記の3操作はprompt消費後のrunで採取している。このpromptはusage権限の未決定時に出る一時的なgateであり、整理の固定経路の一部ではない（spec 417/#443系の既存契約）。

## 各ACの証跡

| AC | 証跡 | 内容 |
|---|---|---|
| AC-1 | `v2-01-popup-en.png` / `v2-08-popup-ja.png` / `v2-13-popup-editor-unlocked.png` / `v2-14-popup-editor-full-list.png` | 既定で有効、`edit_mode`(無効)と`edit_surface`の間のグループ配置。ja「ホームを整理」は「編集画面」（edit_surface）・「ホーム画面を編集」（edit_mode）と区別可能。editor一覧はDEFAULT_ORDERどおり（Wallpaper quick picker, Lock home screen, Edit home screen, **Organize home screen**, Edit layout grid, Wallpaper & style, Widgets, Home settings） |
| AC-2 | `v2-06-op2-run-surface.png` / `v2-04-back-returns-to-launcher.png` | 項目tapで設定Activityがrun面（開始CTAあり）で直接起動。run破棄後のBACK 1回でlauncherへ復帰（hub back stackなし） |
| AC-3 | `v2-09-lock-enabled-setting.png` / `v2-10-locked-popup.png` / `v2-11-locked-popup-editor.png` / `v2-12-locked-popup-editor-full.png` | ロック中popupは「Wallpaper & style」「Home settings」のみ（organize_home/edit_mode/edit_surface/widgets非表示）。editorでは「Home screen is locked」説明付きでswitch無効化（edit_modeと同一扱い）。解除で再表示を確認 |
| AC-5 | `v2-05/06/07`（上表の3操作） | emulator側記録。**実機での録画/スクリーンショットはowner確認事項** |
| AC-6 | `v2-15-talkback-focus-organize.png` / `v2-16-talkback-activated-run-surface.png` | TalkBack有効化（`com.google.android.marvin.talkback`）下で、単tap focus→二連tapで「Organize home screen」を起動しrun面が開くことを確認（`topResumedActivity=PreferenceActivity`）。labelResつきconstructor（a11y action id）はcode側で担保。**実機TalkBack/Switch Accessの読み上げ確認はowner確認事項** |

## icon（review round 1対応後）

`ic_organize_home`（2×2 rounded-square grid + 右上4-point sparkle）は `v2-13/v2-14`（editorのpreview行）と `v2-01`（popup拡大）で視認できる。

## owner確認事項（merge前の残)

1. AC-5: 実機（端末）で「長押し → 項目tap → 開始tap → 確認面」が4操作以下であることの録画またはスクリーンショット。
2. AC-6: 実機でのTalkBack/Switch Accessにより項目へ到達・読み上げできることの確認。

#449の先例（2026-09-29のowner判断）と同様、ownerが省略を判断した場合はその判断をIssueへ記録する。
