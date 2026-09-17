# Issue #345 device evidence — #332 Import入力UIの残りevidence

> Status: recorded 2026-09-17 (evidence取得session。実装は変更していない)
> Device: local emulator `emulator-5554` = AVD `nunu_qpr2_api36_1` (sdk_gphone64_arm64, Android 16 / API 36, 1080x2400)
> Build: `app.lawnchair.debug` (LawnchairWithQuickstepGithubDebug, current `main` = `132dfd787d`, install時に `versionName=15.Dev.(132dfd7)`)
> 手順: adb (`input tap/swipe/input text/keyevent`, `uiautomator dump`, `screencap`) によるUI駆動 + system clipboard writer (ChatGPT側) + SAF file picker。
> 対象画面: Settings → Home screen → Organize home layout → 「Organize with an external AI」→ Create request / Import reply

## 対象AC (spec 332)

- **AC-9**: representativeなChatGPT/Gemini mobile appからのcopy → NunuLauncher import (clipboard読込) のdevice evidence。file経由 (AI appの回答をfile保存→読込) も併記が望ましい。
- **AC-8**: TalkBack (各source操作の識別・typed失敗の読み分け)、Switch Access/keyboard完結、200% font。D-4実寸確定根拠。
- **Issue #345 audit推奨**: `ExchangeImportSurfaceInstrumentationTest` のCI instrumentation lane組込み。

## AC-9: representative app copy → import

### 実行したworkflow

1. 本app (emulator) で Create request → privacy「Exclude app names (default)」→ Review before sending → **Copy** (`01`)。request package (5.4 KiB, INTENTマーカー + CONTEXTデータ) を生成。
2. **representativeなAI surface = ChatGPT mobile web** (emulator上のChrome, `chatgpt.com`)。native ChatGPT/Gemini appはこのAVDに導入できないため (Play Store非搭載、sideload不可)、ChatGPT自身のmobile web UIを代表surfaceとして使用した。
3. request packageをChatGPT mobileへ送信し、INTENTパッケージ形式のreplyを受領 (`02`)。
4. ChatGPT mobileの**copy affordanceを2種類**操作し、clipboard経由で本appへ取り込んだ。
   - (a) message単位のcopyボタン (`03`, `04`)
   - (b) コードブロックのcopy (code copy) ボタン (`07`, `08`)
5. 本app: Import reply →「Import from clipboard」**1押下**でclipboardを読み、parse-first結果を表示 (`05` が押下前のsurface)。取り込み結果は `06` / `09`。

### 結果 (実測)

| # | ChatGPT側copy | 本appの認識framing | 結果 |
|---|---|---|---|
| 1 | message copy (`03`, `04`) | Marker lines | typed failure「The reply has no marked intent block.」(`06`)。raw detailを展開すると、**ChatGPTのmessage copyが各行末を `\` + 改行でエスケープ**しており、マーカー行が `-----BEGIN NUNULAUNCHER INTENT-----\` になって厳密一致しない。 |
| 2 | code block copy (`07`, `08`) | Standalone JSON | typed failure「The reply is not in the expected intent format.」(`09`)。clipboard本文はクリーンなJSONだが、payloadが契約外キーを含む (第2会話: `globalPreference.organization`)。 |
| 3 | (追加試行) 厳密指示のreply | — | 同一turn内に `importance` の小文字 (`"high"`) と小数 `confidence: 0.82` を含むreply、続く再依頼では逆に契約外キー `grouping` を含むreplyが返り、いずれも契約適合しなかった。 |

補足: 本sessionでは「AI製の契約適合payloadをclipboard経由で成功importする」事例は得られていない。得られたのは「代表appのcopy → 1押下import → 正しいtyped failure」のdevice evidenceである。AI出力の契約不適合は本app側の不具合ではなく、`IntentValidator` / `IntentCodec` の意図どおりの拒否である (許可キー集合は `IntentCodec.ALLOWED_*_KEYS`)。

### file経由 (AC-9の併記項目) — 成功パス

デスクトップChatGPT (BrowserUse, ログイン済みセッション) に同じrequestと「許可キーのみ・大文字enum・整数confidence・`grouping`等の追加キー禁止」の指示を与えて生成したreply (1419 bytes, JSON検証済み: top-level 7キー / item keys ⊆ 許可集合 / `globalPreference.minimizeMovement` のみ / `confidence` 整数) を `/sdcard/Download/nunu-intent-reply.txt` へ配置し、本appの Import reply →「From file」で取り込んだ。

- 結果: **「Proposal validated. Generating the preview…」** (`10`)。契約適合payloadはfile経由で成功し、proposal生成へhandoffした。
- D-3 (`text/*` 拡張の要否): representativeなAI replyはいずれもテキスト/JSONとして得られ、追加のfile型は不要だった。spec改訂は不要。

## AC status (PR #347 review反映)

本READMEのevidence取得だけでは **AC-8 / AC-9 は完了にしない** (PR #347 reviewの高/中指摘)。理由は下記「制約」のとおり:

- AC-9: 実施できた representative surface は **ChatGPT mobile web** のみ。native ChatGPT app / Gemini app (mobile) の one-tap copy 実経路と、clipboard経由での成功 import は未取得。
- AC-8: TalkBack / Switch Access は emulator 上に存在し、有効化・service bind まで確認した。未取得なのは **実行入力による walkthrough** (TalkBack の読み上げ文言・順序・focus遷移、Switch Access の scan/選択/復帰) である。
- 残るevidenceを取得するか、AC 文言を「source app 非依存の Android clipboard contract」「semantics 粒度」へ改訂するかは owner 判断とする。

## AC-8: accessibility evidence

### TalkBack

- TalkBackをemulatorで有効化し、`dumpsys accessibility` で `Bound services: TalkBack` / `touchExplorationEnabled=true` を確認。
- import surface表示中のscreenshot `11` はTalkBack有効状態で、TalkBackのfocus indicatorが画面内に描画されている。
- 各source操作の識別 (「Import from clipboard」「From file」の個別label・click action・raw toggleの状態) とtyped失敗の読み分け (live region) は `ExchangeImportSurfaceInstrumentationTest` のsemantics assertionで機械検証済みであり、本READMEはそれを代替するものではない。
- **制約 (未取得)**: 読み上げ音声のwalkthroughは取得できなかった。理由: (1) adbの合成gestureはTalkBackのtouch explorationに採用されずfocusが移動しない、(2) emulatorのqemuウィンドウはデスクトップ自動化層から安定したapp identityを持たないため実クリックを配送できない。audio captureもこの環境では不可。TalkBackの*読み上げ内容*は上記semantics assertionが正本であり、本evidenceは「TalkBack有効下でsurfaceが描画される」ことまでを示す。

### Switch Access

- `com.google.android.accessibility.switchaccess/com.android.switchaccess.SwitchAccessService` を有効化し、`Bound services: Switch Access` を確認。
- 初回有効化で **Switch Access Setup Guide「Choose a switch type」(USB switch / Bluetooth switch / Camera Switch)** が表示された (`12`)。emulatorにはスイッチ実機がなく、Camera Switchも利用できないため、switch入力によるscan/selectの通し操作は実施できなかった。
- 代替として、要素が個別にfocus/click可能でlabelを持つことはinstrumentation assertionと下記keyboard traversalで確認している。

### Keyboard (touchなしの完結)

- import surfaceでkeyboard focus indicatorを確認 (`13`)。
- Tab + Enter で **「From file」を起動**し、SAFのdocument pickerが開くことを確認 (`14`)。つまりsource操作はtouchなしで起動できる。
- 制約: primary「Import from clipboard」のkeyboard起動は本sessionでは再現できず (Tab順が `From file` に到達)、clipboard import自体はtouchで実行した (`05`→`06`/`09`)。

### 200% font / D-4

`../assets-332-import-ui/README.md` の `06` / `07` で取得済み (100% / 200% fontのbounded editor・内部scroll・主要CTA到達)。本sessionはbuildを最新mainへ更新したのみで再取得していない。

## CI組込み (Issue #345 audit推奨)

`.github/workflows/ci.yml` に **新規の独立 job** `organizer-instrumentation-issue332-tests` を追加した (既存 jobへのstep追加ではない)。他のUI laneと同様に専用の `reactivecircus/android-emulator-runner` step を持ち、その job 内で:

- `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.exchange.ExchangeImportSurfaceInstrumentationTest`
- 失敗時は `organizer-instrumentation-issue332-reports` artifact をupload

`final-status` の `needs` に追加し、merge gateの対象にした (現状はローカル実行のみだった)。実CIでの成功確認は本変更のPR (Issue #345 / PR #347) のrunで行う。

## 参照

- spec 332: [spec.md](../../../specs/332-exchange-import-input-ui/spec.md)
- #332 evidence (実装時): [assets-332-import-ui](../assets-332-import-ui/README.md)
- 実装review/audit: [pr-332-import-input-ui.md](../pr-332-import-input-ui.md)
