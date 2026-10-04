# Issue #332 device evidence — Import入力のclipboard/file-firstモバイルUI

> Status: recorded 2026-09-17 (実装sessionによるevidence取得)
> Device: local emulator `emulator-5554` = AVD `nunu_qpr2_api36_1` (sdk_gphone64_arm64, Android 16 / API 36, 1080x2400)
> Build: `app.lawnchair.debug` (LawnchairWithQuickstepGithubDebug, branch `issue-332-spec-plan`, 当時head — commit記録はPR本文参照)
> 手順: adb (`input tap/swipe/text`, `settings put system font_scale`) によるUI駆動 + `screencap`。uiautomator dumpで要素位置を特定。

## Evidence一覧

| File | 内容 | 対応AC |
|---|---|---|
| `01-import-surface-default-font100.png` | Import画面の初期構成: title「Import the AI reply」→ primary「Import from clipboard」(filled) →「From file」(outlined) → 対応型/上限copy「Text (.txt) or JSON (.json) · up to 1 MiB」→ 折りたたみ「Details / if the import does not work」(初期状態で編集fieldは構成されていない) → Cancel。clipboard/fileが主導線として先頭に提示されている | AC-3 (fallback折りたたみ), AC-2 (UI copy), spec Scenario「Import画面の構成」 |
| `02-fallback-editor-bounded-huge-content.png` | fallback開閉後に長大な1行テキストを入力した状態。editorは内容に比例して伸びず **bounded height** (暫定値 `maxLines=8` + `heightIn(max=200.dp)`) で、はみ出した内容はfield内部scrollする。primary CTA (clipboard/file/型copy) は画面内に保持される | AC-3, AC-4 |
| `03-pre-send-disclosure.png` | 「Create request」→ privacy選択 → 生成後の送信前確認 (Review before sending + Copy/Share/Save/Cancel)。このCopyで **実exchange package text** をclipboardへ設定した (以降のclipboard読取evidenceの入力源) | (workflow文脈。#205契約のregression) |
| `04-clipboard-read-typed-failure.png` | 「Import reply」→「Import from clipboard」**1押下** でclipboard (package text) を読み取り、共通import pathが即時実行された結果。parse-first表示: typed失敗文言「The marked block is empty…」+ **「Recognized format / Marker lines」** (package文書中のINTENT marker言及行を #329 normalizerが検出し、失敗結果に `RecognizedImportInfo(framing=MARKER)` が付与されて表示されている) + raw detailは「Show the imported text」(default閉) + 再案内 + Import again | AC-1, AC-6, AC-10 |
| `05-raw-detail-expanded-bounded.png` | 「Show the imported text」押下後。raw取り込みtext (exchange package全文) が **bounded height + 内部scroll** のdetail表示で展開される (「Hide the imported text」にtoggle切替)。画面全体はrawの長さに比例して伸びない | AC-4, AC-7 (ephemeral表示), AC-10 |
| `06-import-surface-font200.png` | `font_scale 2.0` 設定後のImport画面。title・primary CTA・対応型copy・fallback toggle・Cancelが **1画面内に到達可能** | AC-8 (200% font) |
| `07-editor-font200-bounded.png` | 200% fontでfallback editorを開き、長大テキストを入力した状態。editorは200%フォントでも **bounded** (内部scroll、表示は4行強) で、primary CTA群が押し流されず画面内に維持される | AC-8 (200% font + bounded), D-4確定根拠 |

## D-4確定 (bounded manual paste editorの実寸)

上記 `02` / `07` のevidenceに基づき、**`maxLines = 8` + `heightIn(max = 200.dp)`** (実装済みの暫定パラメータ値) を確定値として採用する:

- 100% font: 長大入力でeditorが200dpで止まり、内部scrollする (`02`)。fallback閉時はeditor自体が構成されない (`01`)。
- 200% font: 同様にboundedを維持し、主要CTA (clipboard/file/対応型copy/Cancel) が画面外へ押し流されない (`06`, `07`)。
- 補助 evidence: Compose instrumentation `ExchangeImportSurfaceInstrumentationTest` が同一のbounded高さ (editor < 400px @Density 1f、raw detail ≤ 260px) と clear/replace/default折りたたみをdevice上で機械検証する。

## 記録範囲と残り (明示)

- AC-1の「1操作でclipboard読み取り→parse結果表示まで」は `04` のとおり実機で確認した。ただしclipboard内容は **本app自身のexport package** (Create request → Copy) であり、representativeなChatGPT/Gemini mobile appからの実copy (AC-9の厳密な対象) は含まない。AC-9の残りは [Issue #205 AC-10 と同様の後続evidence pass](https://github.com/nunu1733/NunuLauncher/issues/205) の対象として明示的に分離する。
- TalkBack実読み上げの録画は含まない。TalkBack識別の機械検証は `ExchangeImportSurfaceInstrumentationTest` のsemantics assertion (各source操作の個別label・click action・raw toggleの状態) とbar括りのtext labelで covering し、 TalkBack通し確認は上記の後続evidence passに含める。
- clipboard read中のsystem toast (Android 12+初回access表示) は本一連の操作では表示を確認しなかった (emulator image差。specでは明示操作に付随する表示として許容済み)。
