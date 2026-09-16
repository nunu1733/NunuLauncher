---
issue: "#332"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-16
---

# External Agent ExchangeのImport入力をclipboard/file-firstのモバイルUIへ変更する

> Status: **draft** (2026-09-16 起草、同日resume検証済み。baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (= 2026-09-16時点の `origin/main`) の実装sourceと突き合わせ済み。**owner承認 (accepted) ではない**)。本文は確定可能な範囲で整理したが、**D-4 (manual paste editorの実寸) はlarge-font/accessibility evidenceで確定する** ことがIssue本文から指示されているため未決定 (Open questions 2)。D-2 (manual paste fieldの配置)、D-3 (file対応型の範囲) も起草時推奨を明示のうえowner reviewで確定する。

## Problem

現行 (baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b`、#205 PR #325 + #331 PR #333 実装後) のExternal Agent Exchange import画面 (`ExchangeImportField`、`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`) は、長いAI回答をmultiline text inputへ貼り付けることを主前提としている:

1. **貼付付けfieldが無制限に伸びる**: `OutlinedTextField` は `minLines = 4` のみでmaxLines/height上限がなく、数十KBのAI回答を貼るとfieldが内容に比例して伸び、hostingしているpreferences画面 (LazyColumn) 全体のscroll量が増える。手動で前後の文章を削る再編集はスマホ上ではほぼ実用困難である。なお同一fileのexport disclosure側では生成package全文表示に既存のbounded pattern (`heightIn(max = 240.dp)` + `verticalScroll`) が使われており、Import側にのみこの扱いが欠けている。
2. **clipboard読み取りがない**: OS clipboardからの読み込み操作が存在せず (export側の `ClipboardExchangeTransport.copy` は書き込みのみ。`app.lawnchair.util.getClipboardContent` はexchange flowでは未使用)、ユーザーはChatGPT / Gemini等でコピー→NunuLauncherへ切替→fieldへ長押しpaste、という操作を強制される。
3. **file importが副次的**: 「ファイルから」buttonは存在するが主導線ではなく、対応MIME/type/sizeがUI上に明示されていない (`ActivityResultContracts.OpenDocument()` を `text/plain` 限定で起動)。
4. **raw全文が前面に居座る**: 読み込み後も取り込みtext全体がfieldに展開されたままになり、parse状態・認識した形式/version・summaryが中心にならない。失敗時の取り込み結果画面はtyped失敗文言 (現行17種) のみで、何がどう認識されたかの表示がない。

このUI構造では、Exchange workflowの実用性 (#205の目的) がモバイルの入力負荷で損なわれる。

## Outcome

Import画面を **長文を直接編集するUIから、clipboard / fileから読み込んで内部parseするUI** へ変更する。manual pasteはfallbackとして残すが、bounded height + 内部scrollで画面全体を長文が占有しない。読み込み後はraw全文ではなく、parse状態・認識したframing/version・summary・error guidanceを中心に表示し、raw確認は折りたたみ (bounded) とする。

全ての入力source (clipboard / file / manual paste) は単一の共通import path (#329 Import Normalizer (実装済みの場合) → #205 exchange framing/codec → #204 validation) へ流れ、UI側は独自parse logicを持たない。clipboardは明示操作でのみ読み取り、監視・自動送信を行わない。clipboard/file内容はdiagnostics・永続化へ書き込まない。

## Scope

- Import画面 (`ExchangeScreen.Importing` を置き換えるsurface) の再構成: clipboard読込・file読込を主導線に、manual pasteをfallbackにする配置。
- Clipboard import transport (読み取り): 明示操作のみ・1回読み取り・text itemのみ・typed失敗 (空/非text/oversized)。Android platformのclipboard制限 (API 29+ focus要件等) への対応。
- File import transport: SAF `OpenDocument` による選択、対応MIME/type/sizeのUI上の明示、URI permission/lifecycleの扱い、bounded read。
- Bounded manual paste editor: 高さ上限・内部scroll・clear/replace操作。
- 読み込み後のparse-first result presentation: parse状態・認識framing/version・summary・typed error guidance、rawの折りたたみ (bounded) 表示。
- 入力source取得 (clipboard/file) と結果表示のaccessibility (TalkBack / Switch Access / keyboard / 200% font)。
- clipboard/file raw内容のdiagnostics・永続化禁止 (regression)。
- 両entry (idle entry / #331 run内entry) での同一Import surface (hosting規則・選択freezeは #331契約のまま)。
- RepresentativeなChatGPT / Gemini mobile appからのcopy → NunuLauncher importのdevice evidence。

## Non-goals

- #329 Import Normalizerのnormalization semantics実装 (accepted framingの定義・typed失敗2種)。#332のUIは #329のtyped結果とframing種別enumを消費するのみ。
- #204 validator / #205 exchange framing規則 / envelope上限 (1 MiB) の変更。これらは不変。
- Import成功後の次画面導線・取り込み成功状態 (件数summary・CTA・破棄lifecycle): **#328 が所有**。#332はvalidation通過時点 (#328成功状態への移行点、または #331/#205既存のrun接続挙動) までを所有する。
- arbitrary AI appとのprivate API連携、AIアプリの自動操作、share-back intent受信 (#205 Decision 3のまま)。
- clipboard内容のbackground監視 (`OnPrimaryClipChangedListener` 登録の不在は要件)。自動読み取り・自動送信。
- exchange package生成側 (export flow: privacy選択・session置換確認・送信前確認・transport) の変更。#327 (interview-first化) とも変更面の重なりなし。
- #330 (authoring contract簡素化) との兼用。validation失敗の分類は不変。

## 現行実装の確認事実 (baseline `aab0d293d1`)

- `ExchangeFlowUi.kt` (`organizer/ui/exchange/`): `ExchangeScreen.Importing(replyText)` が取り込みtext全文をstateとして保持。`ExchangeImportField` は `OutlinedTextField(value=replyText, minLines=4)` (maxLines/height上限なし) + 「取り込む」button (非blankで有効) + 「ファイルから」button (`ActivityResultContracts.OpenDocument()`、`arrayOf("text/plain")`) + cancel。
- 受領時envelope検査: `onImportTextChange` が `acceptsExchangeImportEnvelope` (1 MiB UTF-8 byte、`IntentImportParser.kt`) でoversized textをstateへ採用せず `INPUT_OVERSIZE` status。
- File読込: `ExchangeFlowStateHolder.importFromFile` → `FileExchangeTransport.read(uri)` (`organizer/integration/exchange/ExchangeTransports.kt`)。bounded read (1 MiB + 1 byteまで読んで全量materialize前にfail)、結果は `Text / Oversize / Failure` の3種。
- Import実行: `holder.import(replyText)` → `ExchangeFlowController.importReply` → `ExchangeImportPipeline.prepare` (framing抽出 → #204 `IntentCodec.decode`) → session照会 → `validate`。成功は即 `run.attachIntent` / `run.start(intent)` (#331 run内entry / #205 fresh run) で画面を閉じ1行status。失敗は `ExchangeScreen.ImportOutcomeScreen` (composable `ExchangeImportOutcome`) でtyped失敗文言 (envelope 4種 + contract 13種 = 17種) + 再取り込み (`openImport` でImport画面へ戻る)。
- 同一fileのexport disclosureは生成package表示を `heightIn(max = 240.dp)` + `verticalScroll` でboundedにしており、raw textのbounded折りたたみ表示はこの既存patternの適用で実現できる (新規component categoryは不要)。
- clipboard読み取りの実装はexchange flowに存在しない。`app.lawnchair.util.getClipboardContent` (generic helper、exchange未使用) と `ClipboardExchangeTransport.copy` (書き込み) のみ存在。
- 現行intent schema versionは `personalized-intent-v2` (`ContextExportContract.INTENT_SCHEMA_VERSION`、`ContextExportModels.kt`)。codec decode成功時にのみ確定する値であり、UIはcodec/seam経由で受け取る (UI側でversion文字列をparseしない)。
- diagnostics: exchange経路 (`ui/exchange` / `integration/exchange` / `personalization/exchange`) にdiagnostics journal・logcat書込みは存在しない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約どおり)。
- hosting: `ManualOrganizationPreferences.kt` がidle branch (Idle/Cancelled) と `State.Selecting` (run内entry、`exchangeBusy` で選択freeze) の両方で `exchangeFlowItems` をhosting。
- AndroidManifestにclipboard権限は不要 (framework API)。SAFも権限不要 (user選択によるURI grant)。

## Behavior scenarios

### Scenario: Import画面の構成 (clipboard/file-first)

Given idle entry (#205) または #331 run内entry の「取り込む」操作でImport画面が開く、
Then 画面は次の構成を持ち、clipboard読込とfile読込が主たる操作として先頭に提示される:

```text
AIの回答を取り込む            (既存title)
[クリップボードから読み込む]   (primary action)
[ファイルから読み込む]         (primary action)
詳細 / うまく読み込めない場合   (fallback section heading)
[手動で貼り付ける]            (bounded editorを開く/表示する)
```

And manual paste editorは主導線の下位に位置し、初期状態で数十KBのtextで画面が占有されることはない (折りたたみ/詳細sectionの扱いは D-2)、
And 両entryで同一のsurface・操作体系であり、hosting規則 (idle: run非active時のみ / run内: 選択freeze中) は #205/#331契約のまま変更しない。

### Scenario: clipboardからの1操作読み込み

Given ユーザーが外部AI app (ChatGPT / Gemini等) で回答をコピーし、NunuLauncherのImport画面に戻っている (appはfocusを持ち、API 29+のclipboard読み取り条件を満たす)、
When ユーザーが「クリップボードから読み込む」を1回押す、
Then OS clipboard (primary clip) から **明示操作の応答として1回だけ** textを読み取り (text itemのみ。`coerceToText` 等によるURI/Intentの解決は行わない)、envelope上限 (1 MiB) 検査を通過したtextを取り込み候補として採用し、**同一操作内で** 共通import path (normalizer (#329実装済みの場合) → framing/codec → validation) を実行して、parse-first result presentation (後述scenario) を表示する、
And 読み取りはこの明示操作のみで行われる。Import画面を開く・resumeする・結果を表示するだけではclipboardへaccessせず、`OnPrimaryClipChangedListener` 等の監視を一切登録しない、
And 読み取ったtextは既存manual paste欄の内容 (あれば) を **置き換える** (clear/replace操作が容易であることの一部)。

### Scenario: clipboard typed失敗 (空 / 読み取り不能)

Given clipboardが空である、他appがcopyした内容がOSにより読み取り不能とされている (例: focus喪失、他appが `EXTRA_IS_SENSITIVE` で保護した内容) 等の理由でprimary clipがnull/emptyとして読める、
When ユーザーが「クリップボードから読み込む」を押す、
Then typed失敗 **clipboard空/読み取り不能** として、(1) AI appで回答をコピーし直す、(2) このappに戻ってから再度読み込む、(3) うまくいかない場合はfile読込かmanual pasteを使う、の順で案内を表示し、画面はImport画面のまま維持される (zero-write)。

> Platform事実 (確認日 2026-09-16): Android 10 (API 29) 以降、default IMEまたはfocusを持つapp以外はclipboard dataにaccessできない ([Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes))。Android 12以降、他appのcopyした内容への初回 `getPrimaryClip()` でsystemが「〜がクリップボードから貼り付けました」toastを表示し、`getPrimaryClipDescription()` では表示されない ([Android 12 behavior changes](https://developer.android.com/about/versions/12/behavior-changes-all))。Android 13以降、他appが `EXTRA_IS_SENSITIVE` を設定した内容はclipboard preview UIで隠される ([Android 13 behavior changes](https://developer.android.com/about/versions/13/behavior-changes-all)、[Secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling))。いずれの場合も、読み取り結果がnull/emptyに帰着するものは本typed失敗にmapする (原因をUIで区別・推測しない)。明示操作に伴うsystem toastの表示は本workflowの動作として許容する。

### Scenario: clipboard typed失敗 (非text)

Given primary clipにtext itemが含まれない (Intent clip、URI clip等)、
When ユーザーが「クリップボードから読み込む」を押す、
Then typed失敗 **clipboard非text** として、AI appの回答textをコピーし直すよう案内し (URI解決・Intent展開は行わないことを固定)、Import画面を維持する (zero-write)。

### Scenario: clipboard typed失敗 (oversized)

Given clipboard textがenvelope上限 (1 MiB UTF-8 byte、#205 Decision 6) を超える、
When ユーザーが「クリップボードから読み込む」を押す、
Then 既存typed失敗 `INPUT_OVERSIZE` として、marker周りの余分な文章を減らして再コピーする案内 (既存 `exchange_failure_input_oversize` 文言) を表示する。上限・案内はmanual paste・file経路と同一 (#205全経路原則の維持)。

### Scenario: file import (MIME/type/size明示)

Given Import画面に「ファイルから読み込む」が表示されている、
Then 対応するfile形式 (plain text / JSON相当。draftは D-3) とサイズ上限 (1 MiB) がUI copyで明示されている、
When ユーザーが操作しSAF (`ActivityResultContracts.OpenDocument`) でfileを選択する、
Then 選択されたURIはactivity resultのcallback内で即座に読み込まれる (既存 `FileExchangeTransport.read` のbounded read。全量materialize前に1 MiB超過を検出)、URI permissionはcallback時の一時read grantのみを使い `takePersistableUriPermission` を呼ばず、URI自体を読み込み完了後に保持しない、
And 読み取り成功時はclipboard経路と同一のparse-first result presentationに進み、file読み取り失敗 (IO/security/stream無し) はtyped失敗 **file読み取り失敗** (既存 `FILE_READ_FAILED`)、上限超過は `INPUT_OVERSIZE` として案内する。

### Scenario: bounded manual paste (fallback)

Given ユーザーがmanual pasteを選ぶ (D-2の形態: 詳細sectionの開閉または常設のbounded field)、
Then editorは **bounded height** (6〜8行程度の規模。実寸は D-4としてlarge-font/accessibility evidenceで確定) で、内容が数十KBに達しても高さが内容に比例して伸びず、**field内部でscroll** する、
And editorは1操作での **clear** (全消去) を提供し、clipboard/file読込は既存内容を置き換える (replace)、
And 受領時のenvelope上限検査 (既存 `onImportTextChange` の `acceptsExchangeImportEnvelope` 挙動) は維持され、oversizedな貼付付けはstateに採用されず `INPUT_OVERSIZE` が案内される、
And manual pasteから取り込む操作は共通import pathを通る (現行 `holder.import(replyText)` と同一)。

### Scenario: parse-first result presentation

Given いずれかのsource (clipboard / file / manual paste) から取り込みtextが共通import pathに渡された、
When 結果が確定する、
Then 表示は **raw全文の常時展開ではなく** 次を中心に構成される:

- 読み込み成否 (source別: 読み込み自体の失敗は前述typed失敗)
- 認識したframing (marker形式 / #329実装済みならfenced json・standalone JSON等のframing種別closed enumをそのまま表示)
- 認識したversion (codecが受理したintent schema version文字列。現行 `personalized-intent-v2`。UIは文字列をseamから受け取りparseしない)
- privacy-safeなsummary (認識段階で確定できる件数のみ。例: 認識した項目数。app label・folder title・export-scoped `ref`・AI自由文 (`rationale`)・`confidence` は表示しない。validation通過後の件数summary・CTAは #328 の取り込み成功状態が所有する)
- 失敗時: typed失敗種別ごとの具体的案内 (既存17種の文言体系を維持。#329実装済みなら19種)

And raw取り込みtextの確認が必要な場合は **折りたたみ** (default閉)・展開時もbounded height + 内部scroll のdetail表示で提供し、200% font時でも主要操作 (再読込・別source・取り込み実行/成功状態への遷移) を画面外へ押し出さない、
And 取り込みtextは結果確定後に保持せず (再表示が必要な場合はsourceからの再読込)、process memory外へ書き出さない。

### Scenario: 共通import pathの維持 (UI側parseなし)

Given いずれのsourceから取り込んだtextも、
Then 入力は単一の共通import path (#329 normalizer (実装済みなら) → #205 framing/codec → #204 validator) を通る。UI層はpipeline/seamが返すtyped結果 (失敗種別・framing種別・version・件数) のみを **純粋なprojection関数** で表示modelへ変換し、textを走査・解釈・再構成するlogicを持たない、
And clipboard/file読込はtext取得のみを担い、#329/#205/#204の判定に影響する変換 (前処理trim・文字置換等) を行わない (transport正規化はnormalizer (#329) の責務)。

### Scenario: 成功時の境界 (#328との接続)

Given 取り込んだtextがvalidationを通過した、
Then #328 (取り込み成功状態) が未実装の間は既存挙動 (即時 `run.attachIntent` / `run.start(intent)` + 1行status) を維持し、#328実装後は #328の取り込み成功状態 (件数summary・未適用表示・CTA) へ移行する。本specはこの接続点を境界として固定し、成功後の表示を #328と二重定義しない。

### Scenario: privacy / diagnostics (regression)

Given いずれの経路の取り込みでも、
When diagnostics・log・永続化を確認する、
Then clipboard/fileから読み取ったraw text・抽出payload・断片はdiagnostics journal・logcat・file永続化のいずれにも書き込まれず (現行 #205 / organizer-diagnostics.md契約の維持)、import textはimport flowのprocess memory上のみ (受領時1 MiB上限付き) に存在しoutcome確定後に保持しない、
And clipboard監視 (listener登録)・自動読み取り・自動送信の経路は存在せず、network通信も発生しない (#205契約の維持)。

### Scenario: accessibility・環境失敗

Given TalkBack / Switch Access / keyboard操作 / 200% font環境、
When Import画面を操作する、
Then 各source操作 (clipboard読込・file読込・manual paste) がTalkBackで互いに識別可能なlabelを持ち、Switch Access / keyboardのみでsource選択から取り込み完了 (またはtyped失敗の把握と再試行) まで完結できる、
And 読み込み結果は既存のlive region pattern (`exchange-status` 相当) で通知され、typed失敗ごとに読み上げ内容が識別できる、
And 200% fontでもbounded editor・raw detailの内部scrollにより主要操作が画面内に到達可能であり、raw textが主要CTAを押し流さない。

## Decisions (draft — owner reviewで確定する)

### D-1: source操作の並びと主導線

主導線 = clipboard読込 (先頭) → file読込。manual pasteは「詳細 / うまく読み込めない場合」の下位section (Issue提示UI sketchに準拠)。editorの初期視認性は D-2。file選択のSAF契約 (OpenDocument・一時grant) は現行実装の継続。

### D-2: manual paste editorの配置 (draft推奨)

案: (a) 詳細section内に折りたたまれ、ラベル押下でbounded editorが開く (Issue sketch準拠)、(b) 常設のbounded editor (高さ上限のみ)。起草時推奨は **(a)** — 主導線が「読み込んでparse」であることを視覚的に固定でき、200% fontでの画面占有率を最小化できる。(b)は「fallbackの発見性」で優る。owner decision待ち。

### D-3: file importの対応型 (draft推奨)

SAF filter = `text/plain` + `application/json`。UI copyで「テキスト (.txt) またはJSON (.json) ・最大1 MiB」を明示する。`text/*` 全体の許容 (file managerがJSONを `text/json` 等として報告する場合の救済) はdevice evidenceで必要と判明したらV2で拡張する (draft時点では型を明示的に絞る)。

### D-4: bounded editorの実寸 (未決定 — evidenceで確定)

高さは6〜8行程度を起点とし、dp換算・maxLinesは **200% font / TalkBack環境でのevidence** (AC-8/AC-9) を見て確定する (Issue本文の指示どおり)。確定前の実装は暫定値 (例: `maxLines` 8 + `heightIn(max = 200.dp)` 程度) をパラメータ化して持ち、evidence PRで固定する。

### D-5: parse-first表示の内容とそのsource

表示項目 = 成否 / framing種別 (#329 enum・未実装ならmarker形式のみ) / 認識version (codec受理値) / privacy-safe件数 / typed失敗案内 / 折りたたみraw (bounded)。値はすべてpipeline/seam由来であり、UI側projectionは純粋関数とする。`rationale` / `confidence` / label / `ref` は非表示 (#328のsummary規約と整合)。

### D-6: staged import seam (実装形態の指針、詳細はplan)

共通pathの判定結果を表示できるよう、pipeline/controllerは認識段階の情報 (framing種別・受理version・件数) をoutcomeに含めて返す (additive拡張。UIが二重にparseしないためのseam)。#329 normalizerは同seamへframing enumを挿入する。#329/#328の実装順序に依存しない設計 (どちらが先でも本specのUIは成立する)。

### D-7: clipboard読み取りの実装規約

- 読み取りはbutton押下の同期処理として1回のみ (`getPrimaryClip`)。画面表示・resume時の自動読み取り禁止。
- text itemのみ (`getItemAt(0)` のtext。nullなら非text失敗)。`coerceToText` / URI解決禁止。
- 読み取り結果のnull/emptyは原因を区別せず「空/読み取り不能」typed失敗に統一。
- `getPrimaryClipDescription()` による事前可用性確認は任意 (toastを発生させない利点があるが、説明と実体が変わるraceを許容する必要があるため必須としない)。
- 読み取り後のtextはenvelope検査を経て取り込み候補stateへ。clipboard内容そのものの保持・再読み取りは行わない。

## Permissions, privacy, and security

- 追加permissionなし (clipboardはframework API、fileはSAF/user選択。#205と同一)。
- clipboard読み取りは明示操作のみ。background監視・自動読み取りなし。Android 12+のsystem表示 (初回読み取りtoast) は明示操作に付随するものとして許容。
- URIは読み取り時のみ使用 (`takePersistableUriPermission` 不使用)。raw textの永続化・diagnostics記録なし。
- clipboard/file入力はuntrusted data (#205 threat modelの維持)。本specは取得UXのみを変更し、fail-closed境界 (framing/codec/validator、zero-write) は不変。

## Accessibility and localization

- 各操作のTalkBack label、Switch Access/keyboard完結、live regionによる結果通知、200% fontでの主要CTA到達性 (AC-8)。
- stringsは `values/` + `values-ja/` の両方へ追加 (spec 123契約)。日本語をUI copyの正本とする (#205 Decision 4と同一)。

## Acceptance criteria

- [ ] AC-1: clipboardから1操作 (button 1回押下) でAI回答を読み込み、共通import pathによるparse結果表示まで到達できる。明示操作以外のclipboard access (画面open/resume時の読み取り、listener登録) が存在しないことがtestされる。
- [ ] AC-2: 対応file (D-3の型) からAI回答を読み込める。対応MIME/type/sizeがUI copyで明示されていること、URI permissionが一時grantのみ (persistable取得なし) で読み取り直後にURIを保持しないことがtestされる。
- [ ] AC-3: manual pasteはfallbackで、bounded height (内容に比例して伸びない) + field内部scroll + clear操作を持ち、clipboard/file読込が既存内容を置き換えることがtestされる。
- [ ] AC-4: 長いIntent (数十KB) を読み込んでも、Import画面の全体高さが入力内容に比例して伸びないこと (paste editor・raw detail双方のbounded) がtestされる。
- [ ] AC-5: 全source (clipboard/file/manual paste) が同一の共通import path (#329実装済みならnormalizerを含む → #205 → #204) を通り、UI層にtextを解釈するlogic (走査・trim・文字置換・version/件数の独自導出) が存在しないことがreview/testで検証される。
- [ ] AC-6: clipboardの空/読み取り不能・非text・oversized、fileの読み取り失敗・oversizedが、それぞれ区別されたtyped表示と具体的案内で表示されることがtestされる (既存 `INPUT_OVERSIZE` / `FILE_READ_FAILED` との整合を含む)。
- [ ] AC-7: raw clipboard/file内容がdefaultのdiagnostics・log・永続化へ保存されないこと、import textがoutcome確定後に保持されないことが検証される (regression)。
- [ ] AC-8: TalkBack (各source操作の識別・typed失敗の読み分け)、Switch Access/keyboard完結、200% font (主要CTA到達性・bounded scroll) のevidenceがある。D-4の実寸確定根拠を含む。
- [ ] AC-9: representativeなChatGPT/Gemini mobile appからのcopy → NunuLauncher import (clipboard読込による) のdevice evidenceがある。file経由 (AI appの回答をfile保存→読込) も併記することが望ましい。
- [ ] AC-10: 読み込み後の表示がparse-firstであること (認識framing/version/summaryの表示、rawのdefault折りたたみ、typed失敗ごとの案内) がtestされる。既存typed失敗表示 (17種。#329実装済みなら19種) とexport flow (生成・送信前確認・transport) がregressionなく機能する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | state holder unit test (clipboard読込 → import path呼出・結果state) + clipboard transport adapter test (明示呼出のみ・listener登録なしのreview) + instrumentation |
| AC-2 | SAF経路のinstrumentation test + UI copy存在test + `FileExchangeTransport` 既存bounded readのregression + URI保持なしのreview |
| AC-3 | editor UI test (bounded: 巨大textで高さ上限・内部scroll、clear、置き換え) + state holder test |
| AC-4 | 画面構成test (巨大入力時の高さ計測または構造assertion。paste editor・raw detail双方) |
| AC-5 | seam構造test (全sourceが同一pipeline入口へ集約) + code review (UI層のparse不在) + 既存 `ExchangeFlowStateHolderTest`/`ExchangeFlowControllerTest` 拡張 |
| AC-6 | typed失敗のstate holder/UI test (空・非text・oversized・file失敗。ja/en strings解決) + clipboard adapterの失敗注入test |
| AC-7 | diagnostics契約のregression test/review (書込み経路の不在) + outcome後のstate破棄test |
| AC-8 | 手動/instrumentation evidence (TalkBack・Switch Access・keyboard・font scale 200%。D-4確定根拠) |
| AC-9 | physical device evidence (docs/assessment/ またはissue記録。#205 AC-10 evidenceと兼ね可) |
| AC-10 | parse-first表示のUI test (framing/version/summary・raw折りたたみ) + 17種 (19種) 失敗表示regression + export flow regression |

## Open questions (acceptance前に解消必要)

1. **manual paste editorの配置 (D-2)**: 詳細sectionへの折りたたみ (起草推奨) vs 常設bounded editor。owner decision。
2. **bounded editorの実寸 (D-4)**: 6〜8行起点のdp/maxLines確定値。AC-8/AC-9 evidence後に確定 (Issue本文が後段決定と指示)。
3. **file対応型の範囲 (D-3)**: `text/plain` + `application/json` (起草推奨) vs `text/*` 許容。device evidence (実AI app・file managerのMIME報告実態) を見て判断してよい。

## Relationship / 責務境界

- **#329 (draft)**: Import Normalizer (text canonicalization・accepted framing)。#332は同normalizerのtyped結果とframing種別closed enum (marker / fenced / standalone。**型名は #329 draftでは未確定**) を共通import path経由で消費し、UI側で独自parseを持たない。#329未実装でも #332は成立する (現行 #205 marker形式pipelineに対して同じseamで接続。framing種別表示はmarker形式のみ)。
- **#328 (draft)**: Import成功後の取り込み成功状態 (件数summary・未適用表示・CTA・破棄)。#332はvalidation通過時点までを所有し、接続点で #328へ渡す (未実装の間は #205/#331既存挙動)。#328 specのNon-goalsも同境界を明記済み。
- **#205 (implemented)**: exchange framing規則・envelope上限 (1 MiB)・transport adapter群・失敗分類 (envelope 4種) は不変。本specはImport surfaceのUX再構成とclipboard読取transportの追加のみ。export側 (privacy選択・session置換確認・送信前確認・transport) は無変更。
- **#204 (implemented)**: validator/schema不変。認識version表示はcodec受理値の表示のみ。
- **#331 (implemented)**: run内entry・選択freeze・`attachIntent`・scope binding gateのhosting契約不変 (両entry同一surface)。
- **#327 / #330 (OPEN)**: instruction/interview設計・authoring contract簡素化。変更面の重なりなし。

## Change history

- 2026-09-16: Draft created for #332。baseline `aab0d293d1` (origin/main) 上で現行実装 (`ExchangeImportField` の無制限multiline field・file読込の副次配置・clipboard読取不在・17種失敗表示・hosting両entry) を確認のうえ起草。Android clipboard platform制限 (API 29+ focus要件・Android 12 clipboard access toast・Android 13 sensitive preview) を公式docで確認しD-7へ反映。D-4 (実寸) はIssue指示どおりevidence確定の未決定事項として明示。
- 2026-09-16: resume検証 (同一baseline)。残存draftをIssue本文・全コメント (0件)・実装source (`ExchangeFlowUi.kt` / `ExchangeTransports.kt` / `IntentImportParser.kt` / `ExchangeFlowController.kt` / `ExchangeImportPipeline.kt` / `ManualOrganizationPreferences.kt` / `ClipboardUtils.kt` / `lawnchair/res` strings / manifest) および #329/#328 draft spec (各branch snapshot) と突き合わせ、(1) import失敗画面の型名を実際の `ExchangeScreen.ImportOutcomeScreen` / `ExchangeImportOutcome` に精緻化、(2) export disclosure側の既存bounded表示pattern (`heightIn(max = 240.dp)` + `verticalScroll`) を問題記述・確認事実へ追記、(3) #329 framing enumの型名が #329 draftで未確定であることを明示、(4) #329/#328 draft specへの参照をIssue URL + branch snapshot表記へ修正、(5) DESIGN.md参照を gate 13 へ修正。statusは **draftのまま** (acceptance判断はowner)。

## References

- [Issue #332](https://github.com/nunu1733/NunuLauncher/issues/332)
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md) (implemented。framing/envelope/transport/session・import transport契約)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md) (codec/validator・schema version)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (run内entry・hosting)
- Spec 329: Import Normalizer — [Issue #329](https://github.com/nunu1733/NunuLauncher/issues/329)。draft snapshotはbranch `issue-329-spec-plan` の `specs/329-import-normalizer/spec.md` (本branch時点では未mergeのため相対linkは未解決)。framing種別closed enum・失敗表示17種→19種
- Spec 328: Import成功後の状態明示 — [Issue #328](https://github.com/nunu1733/NunuLauncher/issues/328)。draft snapshotはbranch `issue-328-spec-plan` の `specs/328-exchange-import-success-state/spec.md` (同上)。validation通過後の中間状態とCTA
- [Android 10 privacy changes (clipboard access)](https://developer.android.com/about/versions/10/privacy/changes) (確認日 2026-09-16)
- [Android 12 behavior changes (clipboard access notifications)](https://developer.android.com/about/versions/12/behavior-changes-all) (確認日 2026-09-16)
- [Android 13 behavior changes (sensitive clipboard preview)](https://developer.android.com/about/versions/13/behavior-changes-all) / [Secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling) (確認日 2026-09-16)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) (raw text記録禁止の正本)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [CONTEXT.md](../../CONTEXT.md) (「外部エージェント交換」「取り込み」), [DESIGN.md](../../DESIGN.md) gate 13「外部agent交換workflow」(§11 Design gates), [spec 123 (ja/en strings契約)](../123-organizer-ui-convergence/spec.md)
