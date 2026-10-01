# #452 organizer home entry — 実機（physical device）evidence

- Issue: #452 / PR: #486
- 採取日: 2026-10-01
- Build: `Lawnchair.15.Dev.(2a4e1e2).github.debug.apk`（**最終head相当** commit `2a4e1e2aa9`。実装treeはgreen run `36796217597` の `849aefe40b` とbyte同等 — rebase検証済み）。`app.lawnchair.debug`
- 端末: **Pixel 9a**（`56231JEBF08674`、tegu、1080x2424 / 420dpi、Android 16）。#449の実機検証と同一端末。
- locale: **ja-JP**（端末既定。ja copyの実機確認を兼ねる）
- 端末状態の配慮: 既定homeはNova Launcherのまま変更せず、`am start` でLawnchairを起動して撮影。accessibility servicesは既存設定（MacroDroid/Sleep/Bitwarden/Nova）を保存し、TalkBackを一時追加 → 撮影後 **元の文字列と完全一致で復元** を確認。撮影後homeはNovaへ復帰。runは確認面到達後に破棄（適用ゼロ）。

## AC-5: 実機での操作数記録

前提の正直な記録: 端末には **未配置候補が存在する**（利用者のapp一式があるため）ため、spec固定手順の「未配置候補0件」前提は実機では成立しない。0件時の3操作は emulator証跡（[`452-organizer-home-entry-evidence.md`](./452-organizer-home-entry-evidence.md) の `v2-05/06/07`）で実証済み。実機では選択面を1操作挟む形になり、 **合計4操作 ≤ 4（FR-022の目標内）** で確認面へ到達した。

| 操作 | 手段 | 証跡 |
|---|---|---|
| ① 空きスペース長押し | workspace空きセルを700ms長押し | `d02-op1-longpress-popup.png`（popup表示。順序: **ホームを整理** → 編集画面 → 壁紙とスタイル → ウィジェット → ホーム設定） |
| ② 「ホームを整理」tap | popup項目tap | `d03b-op2-run-surface.png`（設定Activityがrun面で直接起動。hub経由なし） |
| ③ 「整理を開始」tap | run面の開始CTA | `d04b-selection-face.png`（未配置候補ありのため選択面。「整理するアプリを選択 0件を選択中」） |
| ④ 「続行」tap | 選択面の「未選択で続行します」CTA | `d04c-selection-bottom.png`（CTA表示）→ `d05b-op4-confirm-face.png`（**確認面「整理案を確認」到達**。整理対象17件、移動4件/保持13件/新規フォルダ1個） |

- 補足: run開始直後にusage accessのJIT promptが初回のみ出た（`d05-usage-jit-prompt.png`。「許可せず続行」でskip）。promptはusage権限未決定時の一時gateで、整理経路の固定手順には含まれない（emulatorと同様、2回目以降のrunでは出ない）。
- **AI相談toggle OFF既定のため方法選択面は出ていない**（#443既定導線どおり）。
- 確認面到達後、runを破棄（「中断」→「破棄しますか？ → 破棄」。適用はゼロ）。`d06-back-returns-to-launcher.png`: BACK 1回でlauncherへ復帰（hub back stackなし）。

## AC-6: TalkBackでの到達・起動（実機）

TalkBack（`com.google.android.marvin.talkback`、端末にインストール済み）を既存service群に追加して有効化し、popupから項目へ到達できることを確認:

- `d07-talkback-popup.png`: TalkBack有効下でpopupを開いた状態。「ホームを整理」が先頭に存在。
- `d09-talkback-activated-run-surface.png`: TalkBack下で「ホームを整理」をtapすると **起動し、run面（整理を開始CTA）が開く** ことを確認（`topResumedActivity=PreferenceActivity`）。既存項目と同一の単tap操作で到達・起動できる。
- 読み上げの等価性の構造的根拠: 項目は既存optionと同じ `OptionItem`（labelResつきconstructor）で構成され、`AccessibilityActionsView` が同じlabelResをaction id・読み上げlabelとして使用する（emulatorのTalkBack focus証跡 `v2-15-talkback-focus-organize.png` も併参照）。
- 制限の正直な記録: adb injection下ではTalkBackの連続focus traversal（focus矩形の連続撮影）が安定して撮れなかった（injected swipeがTalkBack gestureとして解釈されpopupが閉じる場合がある。試行の記録は内部的に保持）。 **Switch Accessは実機で未実施**（switch割当のセットアップが端末の日常設定に介入するため）。Switch AccessはTalkBackと同じaccessibility node tree（uiautomator dumpで「ホームを整理」がclickable node + textとして公開されることを確認）を走査するため、到達機構は同一だが、端末でのSwitch Access確認はownerが日常操作で行うことを推奨する記録とする。

## 各AC対応まとめ（spec Revision 4 のoracleに対する対応）

| AC | 実機evidence |
|---|---|
| AC-5（Revision 4: 実条件で≤4操作 + 正規パス3操作のcross-check） | 上表の4操作（実条件・≤4目標内。選択面の介在を勘定に含めた合計）+ 正規パス3操作はemulator `v2-05/06/07` でcross-check済み |
| AC-6（Revision 4: TalkBack実機到達・起動 + node tree等価。Switch Accessはowner判断で省略可） | `d07` / `d09`（TalkBack到達・起動）+ node tree等価の構造確認。Switch Access手動確認はowner判断で省略（判断記録はIssue #452へ投稿） |
| AC-2（補強） | `d03b`（直接run面起動）/ `d06`（BACK 1回でlauncher復帰） |
| ja copy（補強） | `d02`（ホームを整理・編集画面・壁紙とスタイル…の並びと区別可能なlabel） |
