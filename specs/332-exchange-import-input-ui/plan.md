# Implementation Plan: #332 Exchange Import入力のclipboard/file-firstモバイルUI

---
issue: "#332"
status: accepted
updated: 2026-09-17
---

> Status: **accepted** (2026-09-17) — specがreview **Approved** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/332#issuecomment-5706349675)、head `bccab1ca0d0d5609a1f80c612f8dc75191d53004` 基準) となったため、本planに従い実装を着手する。本planは baseline `9290afc2be80f8dc41a5defa14d7888619fadcad` (= `origin/main`。`45711f53dd40b5cc67013f4a4193d2c6d5b0dcc1` からの差分は #336 spec/plan status更新のみでruntime契約への影響なしを確認済み) の実装code調査に基づく。

## Re-entry status

- 2026-09-17: Re-entry ruleを適用し、最新 `origin/main` (`45711f53dd40b5cc67013f4a4193d2c6d5b0dcc1`) と全Issueコメントを再取得・比較した。#329 (Import Normalizer) は実装merge済み (PR #339) のため、本planは「framing 3値・失敗19種」の読みで改訂した。#330 (PR #335) もmerge済みでschema versionは `personalized-intent-v3`。#328 (成功状態) は未実装 (OPEN)。
- 2026-09-17: Phase1 review指摘対応 (Issue 332 reviewコメント、対象head `56b23f5d56` のRequired 1/2/3)。(1) 認識件数を「authored document entry count」へ意味固定 (bare entryを含み `UnresolvedByOmission` を含まない。user-meaningfulなpreference件数は #328所有)。(2) raw detail表示のための取り込みtextのephemeral保持境界を設計へ追加 (結果surface state 1 field、表示中のみ保持、`openImport`/`close`/遷移で破棄)。(3) file読込成功時をclipboardと同一の受領helper・common import path実行へ変更 (file選択の1操作でparse結果まで)。provenance: `origin/main` を `9290afc2be` まで再確認 (差分は #336 spec/plan status更新のみ)。
- #329の接続点は既に実装されている: `ExchangeImportPipeline.prepare` のnormalizer段、`Prepared(intent, framing)` の認識framing伝播、`exchangeFailureText` のnormalization 2種。本planの残りは #332固有の取得UX (clipboard transport・UI再構成・bounded editor・parse-first表示) と、outcomeへのversion・認識エントリ数・失敗時framingのadditive付与である。
- #328が先にmergeされた場合: 成功時の表示が #328の成功状態へ差し替わるのみで、本planの変更面 (取得UX・失敗表示) は影響を受けない。

## Current evidence (検証済みbaseline `9290afc2be80f8dc41a5defa14d7888619fadcad` 実装調査)

Import入力に関係する現行source (すべて確認済み):

| 対象 | Path | 現状 |
|---|---|---|
| Import UI | `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeScreen.Importing(replyText)` state。`ExchangeImportField` = `OutlinedTextField` (`minLines=4`、maxLines/height上限なし、`testTag("exchange-import-field")`) + 「取り込む」(`replyText.isNotBlank()` で有効) + 「ファイルから」(`ActivityResultContracts.OpenDocument()`, `arrayOf("text/plain")`) + cancel。file取得callbackから `holder.importFromFile(context, fileTransport, uri)` |
| 受領時envelope検査 | 同上 `onImportTextChange` + `organizer/personalization/exchange/IntentImportParser.kt` (`acceptsExchangeImportEnvelope`) | 1 MiB UTF-8超過textはstate不採用、`INPUT_OVERSIZE` status。同一上限gateが `ExchangeImportPipeline.prepare` 入口にもある (#329で追加、normalizerより前) |
| clipboard書込 (export側) | `organizer/integration/exchange/ExchangeTransports.kt` `ClipboardExchangeTransport.copy` | 書込専用。読取sideのtransportは存在しない |
| clipboard読取helper (exchange外) | `app/lawnchair/util/ClipboardUtils.kt` `getClipboardContent` | `CustomIconShapePreference` のみ使用。exchange未使用。null安全な `primaryClip?.getItemAt(0)?.text` patternの参照実装になる |
| file読取 | 同 `ExchangeTransports.kt` `FileExchangeTransport.read` | bounded read (limit=`ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES`=1 MiB、buffer=limit+1)。`FileExchangeRead.Text/Oversize/Failure`。**現状は成功時 `screen = ExchangeScreen.Importing(read.text)` への代入のみでimport実行は行われない (「取り込む」の追加操作が必要。spec AC-5/file scenarioとの不一致 → 本planで変更)** |
| #329 normalizer (実装済み) | `organizer/personalization/exchange/ImportNormalizer.kt` | `normalize()` がtransport正規化 (BOM・CRLF/CR→LF) → marker形式判定 (priority 1、#205 parserへ委譲) → 単一fenced `json` block (priority 2) → standalone JSON object (priority 3)。結果 `MarkedFraming` / `Payload(framing)` / `Failure(AmbiguousBlocks|UnrecognizedFormat)`。framing enumは `RecognizedImportFraming { MARKER, FENCED_JSON, STANDALONE_JSON }` |
| import実行 | 同 `ExchangeFlowController.importReply` → `organizer/personalization/exchange/ExchangeImportPipeline.kt` (`prepare`: envelope gate → #329 normalizer → framing抽出 → #204 `IntentCodec.decode`) | 成功: `Prepared(intent, framing)` (framingは #329が付与済み) → session照会 → `validate` → `run.attachIntent` (#331 run内) or `run.start(intent)` (idle) で即画面close + `IMPORT_ACCEPTED` 1行status。失敗: `ExchangeScreen.ImportOutcomeScreen` (composable `ExchangeImportOutcome`)、envelope 4種 + normalization 2種 + contract 13種 = 19種のtyped文言 + 再取り込み (`openImport`)。失敗結果には認識情報 (framing/version/エントリ数) が未付与。**outcome画面遷移時に `Importing(replyText)` stateは破棄され、raw textはoutcomeへ渡されない (現状 → 本planでephemeral保持を追加)** |
| pipeline unit test (既存拡張) | `tests/unit/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipelineTest.kt` | #329で +144行。normalizer経由のframing別success・typed失敗を検証済み。`Prepared.framing` のassertionも既存 |
| bounded表示の既存pattern | `ExchangeFlowUi.kt` `ExchangeDisclosure` (export側) | package全文表示が `heightIn(max = 240.dp)` + `verticalScroll` でbounded。Import側のraw折りたたみはこのpatternを流用 |
| status表示 | `ExchangeFlowUi.kt` `exchangeStatusText` / `exchange-status` item (`testTag("exchange-status")`, `liveRegion = Polite`) | 11種の `ExchangeStatus.Kind`。新typed失敗 (`CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT`) はここへ追加 |
| hosting | `app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | idle entry (行737付近) と #331 run内entry (`State.Selecting`、`exchangeBusy` で `editsEnabled=false` の選択freeze、行353付近) の両方で `exchangeFlowItems` をhost。変更不要 |
| strings | `lawnchair/res/values/strings.xml` (行1318付近〜) + `lawnchair/res/values-ja/strings.xml` (行407付近〜) | `exchange_import_title` (ja: 「AIの回答を取り込む」) 等が既存。#329のnormalization失敗文言2種も追加済み (19種分)。clipboard読取button・fallback見出し・manual paste・clear・対応型/size明示copyは不在 |
| 権限 | `AndroidManifest.xml` | clipboard権限は不要 (framework API)。SAFも不要 (user選択grant)。追加権限なしで実装可能 |
| diagnostics | `organizer/ui/exchange/` `organizer/integration/exchange/` `organizer/personalization/exchange/` | journal・logcat書込みは存在しない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約)。本変更でも書込み経路を新設しない |
| 既存test | `tests/unit/app/lawnchair/organizer/ui/exchange/` (`ExchangeFlowStateHolderTest`, `ExchangeDisclosureStateTest`)、`tests/unit/app/lawnchair/organizer/integration/exchange/ExchangeFlowControllerTest.kt`、`tests/unit/app/lawnchair/organizer/personalization/exchange/` (`ImportNormalizerTest`, `ExchangeImportPipelineTest`, `ExchangePackageComposerTest`) | holder levelでtransport注入・失敗注入のpatternが既にある (例: `FileExchangeTransport.writeOverride`、`settleDispatcher`)。exchangeのCompose UI test (instrumentation) は未整備 (`tests/organizer-instrumentation/` にはorganizer UI testの既存pattern例: `MissingAppSelectionInstrumentationTest`) |

## Design

### Modules and interfaces

**原則**: spec AC-5 のとおり、UI層にtext解釈logicを置かない。clipboard/fileは「text取得のみ」、判定はすべて既存common path (`ExchangeImportPipeline`) が行う。新設moduleは「取得adapter」と「表示projection」のみ。

1. **`ClipboardImportTransport` (新設、`organizer/integration/exchange/`)**

   ```kotlin
   sealed interface ClipboardImportRead {
       data class Text(val text: String) : ClipboardImportRead
       data object EmptyOrUnavailable : ClipboardImportRead  // null clip / 空text / 読取失敗
       data object NotText : ClipboardImportRead            // text itemなし (Intent/URI clip等)
   }
   class ClipboardImportTransport(private val context: Context) {
       fun read(): ClipboardImportRead
   }
   ```

   - 1 button押下 = 1回の `getPrimaryClip()` 呼出しのみ。listener登録・resume時読取は存在しない (spec D-7)。
   - text itemのみ: `primaryClip?.getItemAt(0)?.text`。`coerceToText` 等によるURI/Intent解決はしない。
   - 失敗map: `primaryClip == null` / text取得結果がempty → `EmptyOrUnavailable`。clip非nullだがtext itemなし → `NotText`。原因推測はしない。
   - **上限検査はtransport内でしない**: `FileExchangeTransport.read` と同様、取得textはholderの受領検査 (`acceptsExchangeImportEnvelope`) に一任し、上限・案内文言を全経路で1箇所に統一する。oversized clipは取得自体は成功し、受領検査で `INPUT_OVERSIZE` になる (spec Scenario: clipboard typed失敗 oversize)。
   - test seam: `ClipboardManager` は直接fakeできないため、constructorに `readClip: (Context) -> ClipboardImportRead` 相当の注入を持たせるか、`read()` をinternal open関数にして失敗注入testを可能にする (`FileExchangeTransport.writeOverride` と同様の既存pattern)。

2. **`ExchangeFlowStateHolder` 拡張 (`ExchangeFlowUi.kt`)**

   - **共通受領helper (新設、private)**: `fun receiveAndImport(text: String)`: envelope検査 (`acceptsExchangeImportEnvelope`。失敗時 `INPUT_OVERSIZE`・state不採用) → **既存manual paste内容を置き換えて** `ExchangeScreen.Importing(text)` へ → 続けて既存 `import(text)` を起動しcommon pathへ。clipboard・file両sourceがこの1つの受領helperへ集約される (AC-5)。
   - `fun importFromClipboard(transport: ClipboardImportTransport)`: 読取 → `Text` は `receiveAndImport(text)` へ。typed失敗 (`EmptyOrUnavailable` → `CLIPBOARD_EMPTY`、`NotText` → `CLIPBOARD_NOT_TEXT`) は新statusを設定し画面・既存入力を保持 (zero-write)。
   - `importFromFile` 変更: `FileExchangeRead.Text` を `receiveAndImport(read.text)` へ流す (現状の `screen = Importing(read.text)` のみから変更。file選択の1操作でparse結果まで到達する)。`Oversize` / `Failure` は既存どおり `INPUT_OVERSIZE` / `FILE_READ_FAILED`。
   - **raw textのephemeral保持 (spec D-6/AC-7)**: 失敗outcomeでraw detailを表示するため、`ExchangeScreen.ImportOutcomeScreen` に取り込みtextの保持fieldを1つ追加する (outcome surface stateの一部)。保持はこの1箇所のみ。`openImport()` (再取り込み)・`close()`・別画面への遷移時に破棄する。editor側の `Importing(replyText)` からoutcome遷移時に移し替え、二重保持しない。diagnostics/log/永続化への書き出しは行わない。
   - `ExchangeStatus.Kind` へ `CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` を追加 (`exchangeStatusText` に対応strings)。
   - `ExchangeScreen.Importing` にfieldを足す場合は最小に抑える (editor開閉などの折りたたみ状態はcomposable内の `remember` local stateで十分。holder stateの増設は控える)。

3. **`ExchangeImportField` 再構成 (`ExchangeFlowUi.kt`, spec D-1/D-2/D-4)**

   - 構成順: title → 「クリップボードから読み込む」(primary, `Button`) → 「ファイルから読み込む」(対応型・1 MiB上限のcopyを近傍に明示) → 「詳細 / うまく読み込めない場合」section → manual paste (bounded editor) + clear + 「取り込む」→ cancel。
   - bounded editor: D-4確定前の暫定実装はパラメータ化 (`maxLines = 8` + `heightIn(max = <暫定dp>)`)。`OutlinedTextField` は `maxLines` 指定で内容超過時にfield内scrollする。200% font evidence (AC-8/9) 後に暫定値を確定値へ固定する。
   - clear: editor内容を空へ (置換動作の明示的操作)。clipboard/file読込成功時も既存内容を置換。
   - raw取り込みtextは **結果surface (`ImportOutcomeScreen`) 表示中のみephemeral保持** (保持箇所はoutcome stateの1 fieldのみ) とし、再取り込み (`openImport`)・close・画面遷移で破棄する。成功時は既存どおりclose (text破棄)。diagnostics/log/永続化への書き出しは行わない (AC-7 retention boundary)。

4. **parse-first outcome (spec D-5/D-6)**

   - **framingは既に `Prepared` に付与済み** (#329)。本変更はoutcomeに残る認識情報をadditiveに完成させる: (1) `ExchangeImportResult.Failure` への認識framing付与 (判明範囲のみ。例: marker行ありのframing失敗 → `MARKER`、decode失敗 → 認識framing。envelope oversize・normalizer失敗は認識前に確定するため付与しないか、判明範囲のみ)。失敗種別の区分け (19種) は増やさない。(2) codec受理 `intentSchemaVersion`、**認識エントリ数 (authored document entry count)** の付与。意味はspec D-5のとおり: decode済みdocumentの `items` entry数であり、bare entry (`ref` のみでsemantic fieldが無い。#330でcanonical unresolvedへ正規化) も1 entryとして数え、documentに書かれていないref (`UnresolvedByOmission`) は数えない。user-meaningfulな「希望件数」「preference件数」は本変更では出さない (validation後の解釈であり #328の所有)。privacy-safe: label/ref/rationale/confidenceは含めない。
   - `ExchangeFlowController.importReply` はpipeline結果にこの認識情報を載せて返す (既存19種失敗の分類は不変、区切りを増やさない)。
   - `ExchangeImportOutcome` UI: 失敗時に「成否・認識framing (判明していれば)・typed案内」を中心表示し、raw全文は **折りたたみ (default閉) + `heightIn(max)` + `verticalScroll`** (export disclosureと同pattern) で提示。raw detailのsourceは前述のephemeral保持field (結果surface表示中のみ)。成功時は #328 未実装のため既存挙動 (即時run接続 + 1行status) を維持 (spec 成功時の境界 scenario)。
   - UI→表示modelの変換は純粋関数 (単元test可能なtop-level関数) に分離し、`exchangeFailureText` と同じ一対対応を保つ。

### #329 Normalizerとの接続 (実装済み)

- common path入口は `ExchangeImportPipeline.prepare` のみで、normalizer段は稼働済み。#332のUI・transportはframing enum値を受け取って表示するだけ。
- clipboard/file/manual pasteの全経路が同一入口へ集約していることを崩さない (transport内でtrim・文字置換等の前処理を入れない — 正規化は #329層の責務)。

### #328への接続点

- validation通過後の成功表示は現行挙動を維持。`ExchangeImportField`/outcomeの再構成で成功pathの挙動 (attach/start/close/status) を変更しない。#328 merge時に成功状態への遷移へ差し替わるのみ。

### Alternatives rejected

- **clipboard内容の継続監視・自動読取 (リッチな「貼り付け候補」chip等)**: spec Non-goal (background監視禁止)。明示操作のみ。
- **Import画面をDialog/別Activity化してLazyColumn hostingから離脱**: hosting契約 (#205/#331) とinstrumentation test基盤を変える高リスク変更。本Issueのscope (入力UX) と無関係。
- **UI層でのframing事前判定 (押下前にclipboard内容を検査して表示)**: UI側parseの導入になりAC-5違反。読取後のcommon path判定のみ。

## Change set

| File | 変更 |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ClipboardImportTransport.kt` | 新設 (読取transport) |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeImportField` 再構成、共通受領helper `receiveAndImport`、`importFromClipboard`、`importFromFile` の1操作化 (`FileExchangeRead.Text` → 受領helper)、`ExchangeStatus.Kind` 2種追加、`ImportOutcomeScreen` へのephemeral raw field追加と破棄 (`openImport`/`close`/遷移)、outcome表示拡張 (framing/version/エントリ数・raw折りたたみ) |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipeline.kt` | 認識情報のadditive付与: `Failure` への認識framing (判明範囲)・codec受理version・認識エントリ数 (authored document entry count) のoutcome化 (`framing` 自体は #329で既存) |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt` | outcome経由の認識情報中継 (interface形状はできるだけ不変) |
| `lawnchair/res/values/strings.xml` + `lawnchair/res/values-ja/strings.xml` | clipboard読取button、fallback見出し、manual paste button、clear、対応型/size copy、`CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` 失敗文言、framing/version表示label (ja正本・en対訳。spec 123契約) |
| `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` ほか | 下記Verificationの追加 |
| 対象外 (変更しない) | `ImportNormalizer.kt` (実装済み)、`AndroidManifest.xml` (権限追加なし)、`ExchangeTransports.kt` の `FileExchangeTransport.read` (既存bounded readを再利用)、export flow全般、`ManualOrganizationPreferences.kt` (hosting契約不変)、#204 validator、envelope上限 |

## Migration and recovery

- DB・永続化・migrationは不変 (画面層と取得adapterのみの変更)。recovery/rollbackは「revertで現行UIに戻る」のみで追加作業不要。
- process recreation: `ExchangeScreen` はprocess-local stateであり現行契約のまま (#205の非永続契約)。validated intentの新規保持はしない。

## Verification

既存コマンド優先 (`docs/engineering/building.md` / `quality-strategy.md` 正本):

- `./gradlew spotlessCheck`
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` のうち exchange 関連 (`ExchangeFlowStateHolderTest` / `ExchangeFlowControllerTest` / `ExchangeImportPipelineTest` / 新transport test)。CI JVM gateがexchange packageをfilter対象にするかは PR のCI結果で確認し、必要なら `quality-strategy.md` のfilter追加を別途記録する。
- spec AC対応:

| AC | 検証 |
|---|---|
| AC-1 | transport unit test (1回読取・textのみ・null/empty/not-text/oversized注入)。listener不在はcode review + `ClipboardImportTransport` が `OnPrimaryClipChangedListener` を参照しないことの構造確認。holder test (読取→`import()` 呼出・置換・失敗時state保持) |
| AC-2 | holder test (SAF callbackから受領helper経由で共通import path実行・parse結果まで一操作) + 既存 `FileExchangeTransport.read` regression + UI copy存在 (strings test またはcode review) + `takePersistableUriPermission` 不在のreview (現行実装でも未使用、維持) |
| AC-3 | holder/editor test (巨大textでfield高さ不変・`maxLines` 超過時内部scroll、clear、clipboard/file読込による置換) |
| AC-4 | 画面構成testまたはinstrumentation (数十KB入力でpaste editor・raw detail双方がbounded)。Compose測定はinstrumentation (`tests/organizer-instrumentation` の既存UI test patternに倣う) またはUI構造assertion |
| AC-5 | 全source (clipboard/file/manual paste) が同一受領helper・同一 `import()`/pipeline入口へ集約する構造test (holder level。fileの1操作実行を含む) + UI層parse不在のreview |
| AC-6 | typed失敗のholder test (空/非text/oversized/file失敗) + `exchangeStatusText` / `exchangeFailureText` のstrings解決test (ja/en) |
| AC-7 | diagnostics書込み経路不在のregression review + 結果surface表示中のephemeral保持 (1箇所) ・`openImport`/`close`/遷移時破棄のstate test (retention boundary assertion) |
| AC-8 | 手動/instrumentation evidence: TalkBack・Switch Access・keyboard・font scale 200%。D-4暫定値の確定根拠をここで記録 |
| AC-9 | physical device evidence (representative ChatGPT/Gemini mobile app copy → import)。docs/assessment/ またはIssue記録。#205 AC-10 evidenceと同一workflowで兼ね可 |
| AC-10 | parse-first表示のUI test (framing/version/エントリ数表示・raw折りたたみdefault閉) + **エントリ数境界fixture test**: export scopeに複数refがあり、document `items` がsemantic entry + bare entryのみを含み別refがomission、というfixtureで「表示/metadata count == authored document `items.size` (bare含む・omission除外)」を直接assertする (`ExchangeImportPipelineTest` にfixture追加。`completed.authoredItemCount` 等のpost-validation値への誤置換も検出) + 既存19種失敗表示regression (`ImportNormalizerTest` / `ExchangeImportPipelineTest` で #329分はcoverage済み) + export flow regression (既存unit test green) |

高リスクlabel (`risk: layout-data` / `risk: migration`) は付かない見込み (DB不変)。ただしPR時の独立エビデンス要件は [github-workflow.md](../../docs/project/github-workflow.md) の判定に従う。

## Documentation updates

- `specs/332-exchange-import-input-ui/spec.md` のstatus遷移と本planの実測修正。
- 該当する正本があれば `DESIGN.md` gate 13 行の参照先追記 (内容不変なら更新不要)。
- 新規ADRは不要見込み (画面再構成であり変更困難な設計判断はspec Decisionとして処理)。

## Dependencies and blockers

- **#329**: 実装merge済み (PR #339)。framing 3値・失敗19種・`Prepared` framing伝播は稼働中。
- **#328**: 成功状態は所有しない。#328が先でも後でも本planは成立。
- **owner decisions (spec Open questions)**: D-2 (manual paste配置 — 起草推奨: 折りたたみsection)、D-3 (file対応型 — 起草推奨: `text/plain` + `application/json`)、D-4 (実寸 — evidence後確定)。D-4確定前は暫定値をパラメータ化して実装し、AC-8/9 evidence PRで固定する。
- CI: exchange unit testがJVM gateのfilter対象かの確認 (初回PRで確認し、結果をPRへ記録)。

## Risks

- **Android clipboard挙動のdevice差**: null/empty帰着の条件・Android 12+のaccess toast表示はOEMで揺らぐ。typed失敗への統一map (原因非表示) で吸収し、AC-9 device evidenceで実機確認する。
- **SAF MIME報告の実態差**: file managerがJSONを `application/octet-stream` 等で報告する場合、D-3起草推奨 (`text/plain`+`application/json`) では選択できない。device evidenceで判明したら `text/*` 拡張をspec改訂で扱う (実装場当たり対応はしない)。
- **LazyColumn item内のnested scroll**: bounded editor/raw detailの内部scrollとリストscrollのgesture競合。export disclosureの `verticalScroll` 実績patternを流用し、instrumentation/manual操作で確認。
- **`ExchangeImportResult` 拡張の波及**: additive変更でも既存exchange unit testの網羅 `when` に影響する可能性。sealed class/data classへのフィールド追加と `Failure` メタデータはcompile errorとして検出される網羅 `when` が既に存在するため (安全側)。#329が `Prepared` へ `framing` を追加した際の先行例 (`ExchangeImportPipelineTest` 拡張) と同様の影響範囲。
- **diagnostics/privacy regression**: 新code pathでraw textをlogに流す誤書きの防止。ephemeral保持fieldが結果surfaceのlifetimeを超えて生存させないこと (AC-7 retention boundary test)。AC-7 review checklistをPR templateに明記。

## Explicitly unverified areas

- 実機 (特にOEM ROM) でのclipboard読取挙動とtoast表示の実測 (AC-9で確認)。
- 実際のChatGPT/Gemini mobile appのcopy内容の形式分布 (#329領域。#332では表示のみ影響)。
- Compose instrumentation testとしてのbounded editor高さ計測の実現性 (`tests/organizer-instrumentation` に前例がない場合、UI構造assertionへの代替をPRで記録)。
- exchange unit testのCI JVM gate filter対象化の要否。
- D-4確定値 (200% font/TalkBack evidence後)。

## Execution checklist

1. `ClipboardImportTransport` (読取adapter・test seam付き) + unit test (typed失敗注入)。
2. `ExchangeFlowStateHolder`: 共通受領helper `receiveAndImport` + `importFromClipboard` + `CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` status + strings (ja/en) + holder test。
3. `ExchangeImportField` 再構成: clipboard/file primary配置、fallback section、bounded editor (D-4暫定パラメータ) + clear、置換semantics + editor/holder test。
4. `ExchangeImportPipeline` 認識情報のadditive付与 (失敗時framing・version・認識エントリ数。framingの `Prepared` 伝播は #329で既存) + `ImportOutcomeScreen` ephemeral raw field (保持・破棄) + `ExchangeImportOutcome` parse-first表示 (raw折りたたみ) + UI test/表示model unit test。
5. `importFromFile` の1操作化 (`FileExchangeRead.Text` → 受領helper) + file読取UI copy (対応型/size明示、D-3 MIME filter) + 既存file経路regression。
6. a11y仕上げ: TalkBack label・live region・keyboard/Switch Access完結の確認、testTag整備。
7. device/a11y evidence (AC-8/AC-9) → D-4確定 → spec/plan status更新。
