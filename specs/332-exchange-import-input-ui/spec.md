---
issue: "#332"
status: implemented
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-17
---

# External Agent ExchangeのImport入力をclipboard/file-firstのモバイルUIへ変更する

> Status: **implemented** (2026-09-17) — review **Approved** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/332#issuecomment-5706349675)、head `bccab1ca0d0d5609a1f80c612f8dc75191d53004` 基準) 後に実装を着手し、実装review **Approved** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/332#issuecomment-5707583713)、head `9d16111c20fa030fdea5a1aa4c0b23678e55e9d4` 基準) を経て [PR #344](https://github.com/nunu1733/NunuLauncher/pull/344) (merge commit `90f18294b25f346cf1d199d6855ee2a2303ff5d9`) でmainへ取り込まれた。独立監査記録: [docs/assessment/pr-332-import-input-ui.md](../../docs/assessment/pr-332-import-input-ui.md) (Approve)。起草2026-09-16、re-entry改訂・Phase1 review指摘対応 (3回のreview) 済み。検証済みbaseline `9290afc2be80f8dc41a5defa14d7888619fadcad`。#329 (Import Normalizer) 実装済みのframing種別 `RecognizedImportFraming` (marker / fenced json / standalone JSON) とtyped失敗19種を前提とする。**D-4 (manual paste editorの実寸) は2026-09-17のdevice evidence ([assets-332-import-ui](../../docs/assessment/assets-332-import-ui/README.md)) で確定: `maxLines = 8` + `heightIn(max = 200.dp)`** (100% / 200% fontの両方でbounded・内部scroll・主要CTA到達を確認)。D-2 (manual paste fieldの配置) は起草推奨の折りたたみsection、D-3 (file対応型) は `text/plain` + `application/json` をaccepted値として実装した (device evidenceで必要判明時にspec改訂)。**AC-8/AC-9の手動/物理evidenceは2026-09-17に [Issue #345](https://github.com/nunu1733/NunuLauncher/issues/345) で一部取得し [assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md) に記録したが、両ACは未完了のまま維持する**: representativeなsurfaceとして使えたのは ChatGPT mobile web のみ (native ChatGPT/Gemini app は Play 非搭載 AVD へ導入不可) で、実ATによるTalkBack読み上げ walkthrough・Switch Access の scan/選択・native app の one-tap copy・clipboard経由の成功 import は取得できていない。取得済み範囲 (ChatGPT mobile copy → 1押下clipboard importのtyped結果、契約適合AI replyのfile取り込み成功、keyboard `From file` 起動、TalkBack有効下のsurface描画) は同READMEに明記した。`ExchangeImportSurfaceInstrumentationTest` はCI lane (新規独立job `organizer-instrumentation-issue332-tests`) としてmerge gateへ組み込んだが、実AT walkthroughの代替とはしない。残るevidenceの取得、または AC 文言の改訂 (source app非依存のclipboard contractへ) は owner 判断とする。

## Problem

現行 (検証済みbaseline `9290afc2be80f8dc41a5defa14d7888619fadcad`、#205 PR #325 + #331 PR #333 + #329 PR #339 実装後) のExternal Agent Exchange import画面 (`ExchangeImportField`、`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`) は、長いAI回答をmultiline text inputへ貼り付けることを主前提としている:

1. **貼付付けfieldが無制限に伸びる**: `OutlinedTextField` は `minLines = 4` のみでmaxLines/height上限がなく、数十KBのAI回答を貼るとfieldが内容に比例して伸び、hostingしているpreferences画面 (LazyColumn) 全体のscroll量が増える。手動で前後の文章を削る再編集はスマホ上ではほぼ実用困難である。なお同一fileのexport disclosure側では生成package全文表示に既存のbounded pattern (`heightIn(max = 240.dp)` + `verticalScroll`) が使われており、Import側にのみこの扱いが欠けている。
2. **clipboard読み取りがない**: OS clipboardからの読み込み操作が存在せず (export側の `ClipboardExchangeTransport.copy` は書き込みのみ。`app.lawnchair.util.getClipboardContent` はexchange flowでは未使用)、ユーザーはChatGPT / Gemini等でコピー→NunuLauncherへ切替→fieldへ長押しpaste、という操作を強制される。
3. **file importが副次的**: 「ファイルから」buttonは存在するが主導線ではなく、対応MIME/type/sizeがUI上に明示されていない (`ActivityResultContracts.OpenDocument()` を `text/plain` 限定で起動)。
4. **raw全文が前面に居座る**: 読み込み後も取り込みtext全体がfieldに展開されたままになり、parse状態・認識した形式/version・summaryが中心にならない。失敗時の取り込み結果画面はtyped失敗文言 (現行19種: envelope 4種 + #329 normalization 2種 + contract 13種) のみで、何がどう認識されたかの表示がない。

このUI構造では、Exchange workflowの実用性 (#205の目的) がモバイルの入力負荷で損なわれる。

## Outcome

Import画面を **長文を直接編集するUIから、clipboard / fileから読み込んで内部parseするUI** へ変更する。manual pasteはfallbackとして残すが、bounded height + 内部scrollで画面全体を長文が占有しない。読み込み後はraw全文ではなく、parse状態・認識したframing/version・summary・error guidanceを中心に表示し、raw確認は折りたたみ (bounded) とする。

全ての入力source (clipboard / file / manual paste) は単一の共通import path (#329 Import Normalizer (実装済み) → #205 exchange framing/codec → #204 validation) へ流れ、UI側は独自parse logicを持たない。clipboardは明示操作でのみ読み取り、監視・自動送信を行わない。clipboard/file内容はdiagnostics・永続化へ書き込まない。

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

- #329 Import Normalizerのnormalization semantics実装 (accepted framingの定義・typed失敗2種、PR #339で実装済み)。#332のUIは #329のtyped結果とframing種別enum (`RecognizedImportFraming`) を消費するのみ。
- #204 validator / #205 exchange framing規則 / envelope上限 (1 MiB) の変更。これらは不変。
- Import成功後の次画面導線・取り込み成功状態 (件数summary・CTA・破棄lifecycle): **#328 が所有**。#332はvalidation通過時点 (#328成功状態への移行点、または #331/#205既存のrun接続挙動) までを所有する。
- arbitrary AI appとのprivate API連携、AIアプリの自動操作、share-back intent受信 (#205 Decision 3のまま)。
- clipboard内容のbackground監視 (`OnPrimaryClipChangedListener` 登録の不在は要件)。自動読み取り・自動送信。
- exchange package生成側 (export flow: privacy選択・session置換確認・送信前確認・transport) の変更。#327 (interview-first化) とも変更面の重なりなし。
- #330 (authoring contract簡素化、PR #335で実装済み) との兼用。validation失敗の分類は不変。

## 現行実装の確認事実 (検証済みbaseline `9290afc2be80f8dc41a5defa14d7888619fadcad`)

- `ExchangeFlowUi.kt` (`organizer/ui/exchange/`): `ExchangeScreen.Importing(replyText)` が取り込みtext全文をstateとして保持。`ExchangeImportField` は `OutlinedTextField(value=replyText, minLines=4)` (maxLines/height上限なし) + 「取り込む」button (非blankで有効) + 「ファイルから」button (`ActivityResultContracts.OpenDocument()`、`arrayOf("text/plain")`) + cancel。
- 受領時envelope検査: `onImportTextChange` が `acceptsExchangeImportEnvelope` (1 MiB UTF-8 byte、`IntentImportParser.kt`) でoversized textをstateへ採用せず `INPUT_OVERSIZE` status。加えて #329実装により `ExchangeImportPipeline.prepare` 入口でも同一上限gateが走る (normalizerより前。spec 329 D-5)。
- File読込: `ExchangeFlowStateHolder.importFromFile` → `FileExchangeTransport.read(uri)` (`organizer/integration/exchange/ExchangeTransports.kt`)。bounded read (1 MiB + 1 byteまで読んで全量materialize前にfail)、結果は `Text / Oversize / Failure` の3種。
- Import実行: `holder.import(replyText)` → `ExchangeFlowController.importReply` → `ExchangeImportPipeline.prepare` (**#329 normalizer → framing抽出 → #204 `IntentCodec.decode`**。envelope gateはその前) → session照会 → `validate`。成功は `Prepared(intent, framing)` (#329で `RecognizedImportFraming` が付与済み。DESIGN.md gate 13の「認識framingの `Prepared` 伝播」) として即 `run.attachIntent` / `run.start(intent)` (#331 run内entry / #205 fresh run) で画面を閉じ1行status。失敗は `ExchangeScreen.ImportOutcomeScreen` (composable `ExchangeImportOutcome`) でtyped失敗文言 (envelope 4種 + normalization 2種 + contract 13種 = 19種) + 再取り込み (`openImport` でImport画面へ戻る)。失敗結果に認識framing・version等の認識情報は現在付与されていない。
- 受領→outcome遷移の現状: file読込成功時は `ExchangeScreen.Importing(read.text)` への代入のみでimport実行は行われない (「取り込む」の追加操作が必要)。またoutcome画面への遷移時に `Importing(replyText)` stateは破棄され、outcome画面はraw textを保持しない。
- #329 normalizer (`organizer/personalization/exchange/ImportNormalizer.kt`): accepted framingは `RecognizedImportFraming { MARKER, FENCED_JSON, STANDALONE_JSON }` の3種。typed失敗 `ImportNormalizationFailure.AmbiguousBlocks` / `UnrecognizedFormat` (strings `exchange_failure_normalization_ambiguous` / `_unrecognized` 実装済み)。transport正規化 (BOM除去・CRLF/CR→LF統一) はnormalizer層が所有。
- 同一fileのexport disclosureは生成package表示を `heightIn(max = 240.dp)` + `verticalScroll` でboundedにしており、raw textのbounded折りたたみ表示はこの既存patternの適用で実現できる (新規component categoryは不要)。
- clipboard読み取りの実装はexchange flowに存在しない。`app.lawnchair.util.getClipboardContent` (generic helper、exchange未使用) と `ClipboardExchangeTransport.copy` (書き込み) のみ存在。
- 現行intent schema versionは `personalized-intent-v3` (`ContextExportContract.INTENT_SCHEMA_VERSION`、`ContextExportModels.kt`。#330でv2からbump)。codec decode成功時にのみ確定する値であり、UIはcodec/seam経由で受け取る (UI側でversion文字列をparseしない)。
- diagnostics: exchange経路 (`ui/exchange` / `integration/exchange` / `personalization/exchange`) にdiagnostics journal・logcat書込みは存在しない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約どおり)。
- hosting: `ManualOrganizationPreferences.kt` がidle branch (行737付近) と `State.Selecting` (run内entry、行353付近、`exchangeBusy` で選択freeze) の両方で `exchangeFlowItems` をhosting。
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
Then OS clipboard (primary clip) から **明示操作の応答として1回だけ** textを読み取り (text itemのみ。`coerceToText` 等によるURI/Intentの解決は行わない)、envelope上限 (1 MiB) 検査を通過したtextを取り込み候補として採用し、**同一操作内で** 共通import path (normalizer (#329) → framing/codec → validation) を実行して、parse-first result presentation (後述scenario) を表示する、
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
And 読み取り成功時はclipboard経路と同一の受領helper・共通import pathへ流れ、**file選択の操作からparse-first result presentation (共通import pathの実行) まで追加の「取り込む」操作なしで到達する** (clipboardと同一の1操作受領)、file読み取り失敗 (IO/security/stream無し) はtyped失敗 **file読み取り失敗** (既存 `FILE_READ_FAILED`)、上限超過は `INPUT_OVERSIZE` として案内する。

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
- 認識したframing (`RecognizedImportFraming` のclosed enum値: marker形式 / fenced json / standalone JSON。#329が `Prepared` へ伝播済み。認識に至らなかった失敗では判明範囲のみ)
- 認識したversion (codecが受理したintent schema version文字列。現行 `personalized-intent-v3`。UIは文字列をseamから受け取りparseしない)
- 認識エントリ数 (authored document entry count): decode済みdocumentの `items` entry数。bare entry (`ref` のみ記載でsemantic fieldが無いentry。#330でcanonical unresolvedへ正規化される) も **1 entryとして数え**、export documentに書かれていないref (canonical `UnresolvedByOmission`、#330) は **数えない**。これはparse段階 (decode時点) で確定するdocument構造の件数であり、user-meaningfulな「希望件数」「preference件数」ではない (それらはvalidation後の解釈であり、#328 の取り込み成功状態が所有する)。app label・folder title・export-scoped `ref`・AI自由文 (`rationale`)・`confidence` は表示しない
- 失敗時: typed失敗種別ごとの具体的案内 (既存19種の文言体系を維持)

And raw取り込みtextの確認が必要な場合は **折りたたみ** (default閉)・展開時もbounded height + 内部scroll のdetail表示で提供し、200% font時でも主要操作 (再読込・別source・取り込み実行/成功状態への遷移) を画面外へ押し出さない、
And raw detailを結果surface上に提供するため、取り込みtextは **結果surface (`ImportOutcomeScreen` 相当) 表示中のみprocess memory上へephemeral保持** される。保持は1箇所 (outcome surface state) に限定し、再取り込み (`openImport`)・close・別画面への遷移の時点で破棄する。diagnostics・log・file・永続化を含むprocess memory外への書き出しは禁止 (AC-7のretention boundary)。

### Scenario: 共通import pathの維持 (UI側parseなし)

Given いずれのsourceから取り込んだtextも、
Then 入力は単一の共通import path (#329 normalizer → #205 framing/codec → #204 validator) を通る。UI層はpipeline/seamが返すtyped結果 (失敗種別・framing種別・version・件数) のみを **純粋なprojection関数** で表示modelへ変換し、textを走査・解釈・再構成するlogicを持たない、
And clipboard/file読込はtext取得のみを担い、#329/#205/#204の判定に影響する変換 (前処理trim・文字置換等) を行わない (transport正規化はnormalizer (#329) の責務)。

### Scenario: 成功時の境界 (#328との接続)

Given 取り込んだtextがvalidationを通過した、
Then #328 (取り込み成功状態) が未実装の間は既存挙動 (即時 `run.attachIntent` / `run.start(intent)` + 1行status) を維持し、#328実装後は #328の取り込み成功状態 (件数summary・未適用表示・CTA) へ移行する。本specはこの接続点を境界として固定し、成功後の表示を #328と二重定義しない。

### Scenario: privacy / diagnostics (regression)

Given いずれの経路の取り込みでも、
When diagnostics・log・永続化を確認する、
Then clipboard/fileから読み取ったraw text・抽出payload・断片はdiagnostics journal・logcat・file永続化のいずれにも書き込まれず (現行 #205 / organizer-diagnostics.md契約の維持)、import textはimport flowのprocess memory上のみ (受領時1 MiB上限付き) に存在し、結果surface表示中のephemeral保持に限定される (再取り込み・close・画面遷移で破棄。outcome確定後の継続保持はしない)、
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

### D-4: bounded editorの実寸 (確定 — 2026-09-17 device evidence)

**確定値: `maxLines = 8` + `heightIn(max = 200.dp)`** (実装パラメータ `IMPORT_EDITOR_MAX_LINES` / `IMPORT_EDITOR_MAX_HEIGHT`)。確定根拠は [assets-332-import-ui](../../docs/assessment/assets-332-import-ui/README.md) のdevice evidence: 100% font (`02`) と200% font (`06`, `07`) の両方で、長大入力時にeditorが高さ上限で止まりfield内部scrollし、主要CTA (clipboard読込・file読込・対応型copy・Cancel) が画面外へ押し流されないこと、およびCompose instrumentation `ExchangeImportSurfaceInstrumentationTest` による同一境界の機械検証。将来の実寸変更は本値の更新とevidence再取得で行う。

### D-5: parse-first表示の内容とそのsource

表示項目 = 成否 / framing種別 (`RecognizedImportFraming` 3値。失敗時は判明範囲のみ表示) / 認識version (codec受理値) / 認識エントリ数 (authored document entry count。bare entryを含み、`UnresolvedByOmission` を含まない。user-meaningfulなpreference件数は扱わない — それは #328のvalidation後成功状態の所有) / typed失敗案内 / 折りたたみraw (bounded。結果surface表示中のみのephemeral保持)。値はすべてpipeline/seam由来であり、UI側projectionは純粋関数とする。`rationale` / `confidence` / label / `ref` は非表示 (#328のsummary規約と整合)。

### D-6: staged import seam (実装形態の指針、詳細はplan)

#329実装により、認識framingの `Prepared` への伝播は済んでいる (DESIGN.md gate 13)。本specはこれに **additive** に (1) 失敗結果への認識framing付与 (判明範囲。失敗種別の区分けは増やさない)、(2) codec受理version・認識エントリ数 (authored document entry count。D-5) のoutcome付与、(3) raw detail表示のための取り込みtextのephemeral引き回し (結果surface stateの1 field。表示中のみ保持、遷移で破棄) を行い、UIが二重にparseしないseamを完成させる。#328の実装順序に依存しない設計 (どちらが先でも本specのUIは成立する)。

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

- [ ] AC-1: clipboardから1操作 (button 1回押下) でAI回答を読み込み、共通import path (normalizerを含む) によるparse結果表示まで到達できる。明示操作以外のclipboard access (画面open/resume時の読み取り、listener登録) が存在しないことがtestされる。
- [ ] AC-2: 対応file (D-3の型) からAI回答を読み込める。file選択の1操作から共通import pathのparse結果表示まで追加操作なしで到達すること、対応MIME/type/sizeがUI copyで明示されていること、URI permissionが一時grantのみ (persistable取得なし) で読み取り直後にURIを保持しないことがtestされる。
- [ ] AC-3: manual pasteはfallbackで、bounded height (内容に比例して伸びない) + field内部scroll + clear操作を持ち、clipboard/file読込が既存内容を置き換えることがtestされる。
- [ ] AC-4: 長いIntent (数十KB) を読み込んでも、Import画面の全体高さが入力内容に比例して伸びないこと (paste editor・raw detail双方のbounded) がtestされる。
- [ ] AC-5: 全source (clipboard/file/manual paste) が同一の共通import path (#329 normalizer → #205 → #204) を通り、UI層にtextを解釈するlogic (走査・trim・文字置換・version/件数の独自導出) が存在しないことがreview/testで検証される。
- [ ] AC-6: clipboardの空/読み取り不能・非text・oversized、fileの読み取り失敗・oversizedが、それぞれ区別されたtyped表示と具体的案内で表示されることがtestされる (既存 `INPUT_OVERSIZE` / `FILE_READ_FAILED` との整合を含む)。
- [ ] AC-7: raw clipboard/file内容がdefaultのdiagnostics・log・永続化へ保存されないこと、import textの保持が結果surface表示中のephemeral (1箇所) に限定され、再取り込み・close・画面遷移で破棄されることが検証される (retention boundaryの固定、regression)。
- [ ] AC-8: TalkBack (各source操作の識別・typed失敗の読み分け)、Switch Access/keyboard完結、200% font (主要CTA到達性・bounded scroll) のevidenceがある。D-4の実寸確定根拠を含む。**一部取得 (未完了)**: 200% font / D-4 は [assets-332-import-ui](../../docs/assessment/assets-332-import-ui/README.md) (`06`/`07`)、keyboard は Tab+Enter で `From file` 起動までを部分確認 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md) `13`/`14`)、TalkBack は有効化状態と surface 描画 (`11`) までを取得。**実ATでの読み上げ文言・順序・focus遷移、Switch Access の scan/選択/復帰は未取得** (合成入力が AT に採用されず、emulator に switch 実機が無い)。`ExchangeImportSurfaceInstrumentationTest` は source label と typed failure 本文 + `LiveRegion.Polite` を直接assertする (`sourceLabelsAndTypedFailureAnnouncementAreExposed`。表示・click action・bounded layout・parse-first/raw detailは同classの他test) が、これは回帰ゲートであり実AT walkthrough (読み上げ文言・順序) の代替とはしない。keyboard も `From file` 起動までの部分確認である。
- [ ] AC-9: representativeなChatGPT/Gemini mobile appからのcopy → NunuLauncher import (clipboard読込による) のdevice evidenceがある。file経由 (AI appの回答をfile保存→読込) も併記することが望ましい。**一部取得 (未完了)**: ChatGPT mobile web (native app は Play 非搭載 AVD に導入不可) の message copy / コードブロック copy → 本app「Import from clipboard」1押下で、認識 framing (Marker lines / Standalone JSON) と typed failure を実測 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md) `03`–`09`)。契約適合のAI製replyは file経由で「Proposal validated」まで到達 (`10`)。**未取得: native ChatGPT / Gemini app の one-tap copy 実経路、および clipboard 経由での成功 import** (AI出力が契約不適合だったため)。要件の意図が「source appに依存しない Android clipboard contract の確認」であれば AC 文言の改訂が必要で、これは owner 判断とする。
- [ ] AC-10: 読み込み後の表示がparse-firstであること (認識framing/version/summaryの表示、rawのdefault折りたたみ、typed失敗ごとの案内) がtestされる。認識エントリ数の境界はfixture testで直接assertする: export scopeに複数refがあり、authored documentの `items` がsemantic entry + bare entry (`ref` のみ) のみを含み、別のrefはdocumentに書かれていない (omission) 状況で、**表示/metadataのエントリ数 == authored document `items` のentry数 (bare含む、omission除外)** であること。post-validation値 (`completed.authoredItemCount` 等) への誤置換もこのfixtureで検出可能にする。既存typed失敗表示 (19種) とexport flow (生成・送信前確認・transport) がregressionなく機能する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | state holder unit test (clipboard読込 → import path呼出・結果state) + clipboard transport adapter test (明示呼出のみ・listener登録なしのreview) + instrumentation |
| AC-2 | holder test (SAF callbackから共通import path実行・parse結果まで一操作) + SAF経路のinstrumentation test + UI copy存在test + `FileExchangeTransport` 既存bounded readのregression + URI保持なしのreview |
| AC-3 | editor UI test (bounded: 巨大textで高さ上限・内部scroll、clear、置き換え) + state holder test |
| AC-4 | 画面構成test (巨大入力時の高さ計測または構造assertion。paste editor・raw detail双方) |
| AC-5 | seam構造test (全sourceが同一pipeline入口へ集約) + code review (UI層のparse不在) + 既存 `ExchangeFlowStateHolderTest`/`ExchangeFlowControllerTest` 拡張 |
| AC-6 | typed失敗のstate holder/UI test (空・非text・oversized・file失敗。ja/en strings解決) + clipboard adapterの失敗注入test |
| AC-7 | diagnostics契約のregression test/review (書込み経路の不在) + 結果surface表示中のephemeral保持 (1箇所) ・再取り込み/close/遷移時破棄のstate test |
| AC-8 | 手動/instrumentation evidence (TalkBack・Switch Access・keyboard・font scale 200%。D-4確定根拠) |
| AC-9 | physical device evidence (docs/assessment/ またはissue記録。#205 AC-10 evidenceと兼ね可) |
| AC-10 | parse-first表示のUI test (framing/version/エントリ数表示・raw折りたたみdefault閉) + エントリ数境界fixture test (semantic entry + bare entryを含み別refがomissionのdocumentで、表示/metadata count == `items.size` (bare含む・omission除外) を直接assert。`ExchangeImportPipelineTest` fixture) + 既存19種失敗表示regression (`ImportNormalizerTest` / `ExchangeImportPipelineTest` で #329分はcoverage済み) + export flow regression (既存unit test green) |

## Open questions (acceptance前に解消必要)

1. **manual paste editorの配置 (D-2)**: 詳細sectionへの折りたたみ (起草推奨) vs 常設bounded editor。owner decision。→ **実装は起草推奨の折りたたみ (a) で実施 (accepted値)**。
2. ~~**bounded editorの実寸 (D-4)**~~: **解決済み (2026-09-17)**。device evidenceにより `maxLines = 8` + `heightIn(max = 200.dp)` に確定 (D-4参照)。
3. **file対応型の範囲 (D-3)**: `text/plain` + `application/json` (起草推奨) vs `text/*` 許容。→ **実装は起草推奨の2型で実施 (accepted値)**。device evidenceで`text/*`拡張の必要が判明したらspec改訂で扱う。

## Relationship / 責務境界

- **#329 (implemented, PR #339)**: Import Normalizer (text canonicalization・accepted framing)。#332は同normalizerのtyped結果とframing種別closed enum (`RecognizedImportFraming`: marker / fenced / standalone) を共通import path経由で消費し、UI側で独自parseを持たない。[spec 329](../329-import-normalizer/spec.md)。
- **#328 (draft)**: Import成功後の取り込み成功状態 (件数summary・未適用表示・CTA・破棄)。#332はvalidation通過時点までを所有し、接続点で #328へ渡す (未実装の間は #205/#331既存挙動)。#328 specのNon-goalsも同境界を明記済み。
- **#205 (implemented)**: exchange framing規則・envelope上限 (1 MiB)・transport adapter群・失敗分類 (envelope 4種) は不変。本specはImport surfaceのUX再構成とclipboard読取transportの追加のみ。export側 (privacy選択・session置換確認・送信前確認・transport) は無変更。
- **#204 (implemented)**: validator/schema不変。認識version表示はcodec受理値の表示のみ。
- **#331 (implemented)**: run内entry・選択freeze・`attachIntent`・scope binding gateのhosting契約不変 (両entry同一surface)。
- **#327 (OPEN) / #330 (implemented, PR #335)**: instruction/interview設計・authoring contract簡素化。変更面の重なりなし。

## Change history

- 2026-09-16: Draft created for #332。baseline `aab0d293d1` (origin/main) 上で現行実装 (`ExchangeImportField` の無制限multiline field・file読込の副次配置・clipboard読取不在・17種失敗表示・hosting両entry) を確認のうえ起草。Android clipboard platform制限 (API 29+ focus要件・Android 12 clipboard access toast・Android 13 sensitive preview) を公式docで確認しD-7へ反映。D-4 (実寸) はIssue指示どおりevidence確定の未決定事項として明示。
- 2026-09-16: resume検証 (同一baseline)。残存draftをIssue本文・全コメント (0件)・実装source (`ExchangeFlowUi.kt` / `ExchangeTransports.kt` / `IntentImportParser.kt` / `ExchangeFlowController.kt` / `ExchangeImportPipeline.kt` / `ManualOrganizationPreferences.kt` / `ClipboardUtils.kt` / `lawnchair/res` strings / manifest) および #329/#328 draft spec (各branch snapshot) と突き合わせ、(1) import失敗画面の型名を実際の `ExchangeScreen.ImportOutcomeScreen` / `ExchangeImportOutcome` に精緻化、(2) export disclosure側の既存bounded表示pattern (`heightIn(max = 240.dp)` + `verticalScroll`) を問題記述・確認事実へ追記、(3) #329 framing enumの型名が #329 draftで未確定であることを明示、(4) #329/#328 draft specへの参照をIssue URL + branch snapshot表記へ修正、(5) DESIGN.md参照を gate 13 へ修正。statusは **draftのまま** (acceptance判断はowner)。
- 2026-09-17: Re-entry改訂 (Re-entry rule適用)。#329が実装mergeされた (PR #339) ため、起草時の「実装済みの場合」条件付記述を実装済み前提へ統一: framing種別を `RecognizedImportFraming { MARKER, FENCED_JSON, STANDALONE_JSON }` として確定、失敗文言を19種 (normalization 2種追加済み) に更新、pipeline順序を「envelope gate → #329 normalizer → framing抽出 → decode」へ更新、D-6を「framing伝播は #329で実装済み。#332は失敗時framing付与とversion・件数付与」と再定義。#330実装 (PR #335) によるschema version `personalized-intent-v3` へのbumpを反映。baselineを `45711f53dd` へ更新。
- 2026-09-17: Phase1 review指摘対応改訂 (Issue 332へのreviewコメント「Changes requested (Phase1: spec/plan Re-Entry revision)」、対象head `56b23f5d56` のRequired 1/2/3)。**(1)** parse-stage件数を「認識エントリ数 (authored document entry count)」として意味固定: bare entryも1 entry、`UnresolvedByOmission` は数えない、user-meaningfulなpreference件数は #328所有と明示 (#330 v3 semantics対応)。**(2)** raw input lifecycleの自己矛盾を解消: raw detail表示のため、取り込みtextを結果surface表示中のみprocess memoryへephemeral保持し、再取り込み・close・画面遷移で破棄するretention boundaryへ統一 (scenario・AC-7・D-6を整合)。**(3)** file import成功時をclipboardと同一の受領helper・共通import path実行 (file選択の1操作からparse結果まで追加操作なし) へ統一 (file scenario・AC-2を明示)。provenance: `45711f53dd` 以降の `origin/main` 差分 (`9290afc2be` まで確認) は #336 spec/plan status更新のみでruntime契約への影響なしを確認し、baseline記録を更新。
- 2026-09-17: 実装完了後のD-4確定。emulator (API 36) 上のdevice evidence ([assets-332-import-ui](../../docs/assessment/assets-332-import-ui/README.md)) により、100% / 200% fontの両方でbounded editor・内部scroll・主要CTA到達を確認し、D-4を `maxLines = 8` + `heightIn(max = 200.dp)` に確定。clipboard 1押下読取 → 共通path → parse-first表示 (認識framing「Marker lines」表示・raw折りたたみ) を実機で確認 (`04`, `05`)。AC-9のrepresentative app実copyとTalkBack通し確認は後続evidence passへ明示分離 ([Issue #205 AC-10の前例](https://github.com/nunu1733/NunuLauncher/issues/205) と同一扱い)。
- 2026-09-17: **implemented**。実装review Approved ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/332#issuecomment-5707583713)) を経て [PR #344](https://github.com/nunu1733/NunuLauncher/pull/344) merge (commit `90f18294b25`)。独立監査 [pr-332-import-input-ui.md](../../docs/assessment/pr-332-import-input-ui.md) (Approve)。AC-8/AC-9の物理evidence残りは [Issue #345](https://github.com/nunu1733/NunuLauncher/issues/345) で追跡。
- 2026-09-17: [Issue #345](https://github.com/nunu1733/NunuLauncher/issues/345) evidence pass (review指摘反映込み)。AC-8/AC-9の手動/物理evidenceを取得し [assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md) に記録: (1) ChatGPT mobile webからのcopy (message copy・コードブロックcopy) → 「Import from clipboard」1押下 → 認識framing + typed failureの実測 (`03`–`09`)、(2) 契約適合のAI製replyのfile取り込み成功 (「Proposal validated」) (`10`)、(3) TalkBack有効下のsurface描画・Switch Access setup要求・keyboard部分確認 (Tab+Enterで `From file` 起動まで) (`11`–`14`)。同classに source label と typed failure 本文 + `LiveRegion.Polite` を直接assertするtest (`sourceLabelsAndTypedFailureAnnouncementAreExposed`) を追加し、README/AC-8の記述を実coverageへ揃えた。合わせて `ExchangeImportSurfaceInstrumentationTest` を新規独立job `organizer-instrumentation-issue332-tests` としてCIへ組み込み、`final-status` の対象にした。**PR #347のreview指摘 (高/中) を受け、AC-8/AC-9は完了扱いにせず未チェックのまま維持**する: native ChatGPT/Gemini app の実経路、実AT (TalkBack読み上げ/Switch Access scan) のwalkthrough、clipboard経由の成功importは未取得であり、要件の粒度そのものの改訂 (source app非依存のclipboard contract / semantics粒度) は owner 判断とする。
- 2026-09-17: 再レビュー指摘対応 (同日review「Changes requested (Phase1指摘対応再レビュー)」、対象head `a06efcffc9` の残件)。認識エントリ数のbare/omission境界を直接assertするtest oracleをAC-10/Test oracleへ明文化: export scopeに複数refがあり、document `items` がsemantic entry + bare entryのみを含み別refがomission、というfixtureで「表示/metadata count == `items.size` (bare含む・omission除外)」を検証し、post-validation値 (`completed.authoredItemCount` 等) への誤置換を検出可能にする。Required 1のsemantic定義・Required 2/3は前回reviewで解消済みと確認された。

## References

- [Issue #332](https://github.com/nunu1733/NunuLauncher/issues/332)
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md) (implemented。framing/envelope/transport/session・import transport契約)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md) (codec/validator・schema version)
- [Spec 329: Import Normalizer](../329-import-normalizer/spec.md) (implemented。accepted framing3種・normalization typed失敗2種・認識framingの `Prepared` 伝播)
- [Spec 330: partial intent authoring v3](../330-partial-intent-authoring/spec.md) (implemented。schema version v3 bump)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (run内entry・hosting)
- Spec 328: Import成功後の状態明示 — [Issue #328](https://github.com/nunu1733/NunuLauncher/issues/328)。draft snapshotはbranch `issue-328-spec-plan` の `specs/328-exchange-import-success-state/spec.md`。validation通過後の中間状態とCTA
- [Android 10 privacy changes (clipboard access)](https://developer.android.com/about/versions/10/privacy/changes) (確認日 2026-09-16)
- [Android 12 behavior changes (clipboard access notifications)](https://developer.android.com/about/versions/12/behavior-changes-all) (確認日 2026-09-16)
- [Android 13 behavior changes (sensitive clipboard preview)](https://developer.android.com/about/versions/13/behavior-changes-all) / [Secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling) (確認日 2026-09-16)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) (raw text記録禁止の正本)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [CONTEXT.md](../../CONTEXT.md) (「外部エージェント交換」「持ち帰りIntent取り込み」「インポート正規化」), [DESIGN.md](../../DESIGN.md) gate 13「外部agent交換workflow」(§11 Design gates), [spec 123 (ja/en strings契約)](../123-organizer-ui-convergence/spec.md)
