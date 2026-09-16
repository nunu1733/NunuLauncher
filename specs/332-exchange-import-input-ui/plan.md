# Implementation Plan: #332 Exchange Import入力のclipboard/file-firstモバイルUI

---
issue: "#332"
status: draft
updated: 2026-09-16
---

> Status: **draft** — [spec.md](./spec.md) が **draft (not accepted)** であるため、本planも確定ではない。実装開始条件は spec のowner承認。本planは baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (= 2026-09-16時点の `origin/main`、#205 PR #325 + #331 PR #333 実装後) の実装code調査に基づく。

## Re-entry status

- 本planは2026-09-16時点の `origin/main` (`aab0d293d1`) を基準に作成したsnapshotである。
- 再開時は最新の `origin/main` と全Issueコメントを取得し、本baselineと差分を比較 (#329 / #328 / #330 のmerge状況を特に確認) して、必要なら本planを改訂してから着手する。
- #329 (Import Normalizer) が先にmergeされた場合: common path入口にnormalizer段が挿入済みのため、本planのframing表示・失敗19種対応へ読み替える。#329未mergeの場合: 本planどおり marker形式のまま実装し、#329接続点 (後述) を通して後日拡張する。**どちらの順序でも本planのUI変更は成立するよう設計する (spec D-6)。**

## Current evidence (baseline `aab0d293d1` 実装調査)

Import入力に関係する現行source (すべて確認済み):

| 対象 | Path | 現状 |
|---|---|---|
| Import UI | `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeScreen.Importing(replyText)` state。`ExchangeImportField` = `OutlinedTextField` (`minLines=4`、maxLines/height上限なし、`testTag("exchange-import-field")`) + 「取り込む」(`replyText.isNotBlank()` で有効) + 「ファイルから」(`ActivityResultContracts.OpenDocument()`, `arrayOf("text/plain")`) + cancel。file取得callbackから `holder.importFromFile(context, fileTransport, uri)` |
| 受領時envelope検査 | 同上 `onImportTextChange` + `organizer/personalization/exchange/IntentImportParser.kt` (`acceptsExchangeImportEnvelope`) | 1 MiB UTF-8超過textはstate不採用、`INPUT_OVERSIZE` status |
| clipboard書込 (export側) | `organizer/integration/exchange/ExchangeTransports.kt` `ClipboardExchangeTransport.copy` | 書込専用。読取sideのtransportは存在しない |
| clipboard読取helper (exchange外) | `app/lawnchair/util/ClipboardUtils.kt` `getClipboardContent` | `CustomIconShapePreference` のみ使用。exchange未使用。null安全な `primaryClip?.getItemAt(0)?.text` patternの参照実装になる |
| file読取 | 同 `ExchangeTransports.kt` `FileExchangeTransport.read` | bounded read (limit=`ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES`=1 MiB、buffer=limit+1)。`FileExchangeRead.Text/Oversize/Failure` |
| import実行 | 同 `ExchangeFlowController.importReply` → `organizer/personalization/exchange/ExchangeImportPipeline.kt` (`prepare`: framing抽出→#204 `IntentCodec.decode` / `validate`) | 成功: `run.attachIntent` (#331 run内) or `run.start(intent)` (idle) で即画面close + `IMPORT_ACCEPTED` 1行status。失敗: `ExchangeScreen.ImportOutcomeScreen` (composable `ExchangeImportOutcome`)、envelope 4種 + contract 13種 = 17種のtyped文言 + 再取り込み |
| bounded表示の既存pattern | `ExchangeFlowUi.kt` `ExchangeDisclosure` (export側) | package全文表示が `heightIn(max = 240.dp)` + `verticalScroll` でbounded。Import側のraw折りたたみはこのpatternを流用 |
| status表示 | `ExchangeFlowUi.kt` `exchangeStatusText` / `exchangeStatusText` item (`testTag("exchange-status")`, `liveRegion = Polite`) | 11種の `ExchangeStatus.Kind`。新typed失敗はここへ追加 |
| hosting | `app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | idle entry (行737付近) と #331 run内entry (`State.Selecting`、`exchangeBusy` で `editsEnabled=false` の選択freeze、行313〜353付近) の両方で `exchangeFlowItems` をhost。変更不要 |
| strings | `lawnchair/res/values/strings.xml` (行1318付近〜) + `lawnchair/res/values-ja/strings.xml` (行407付近〜) | `exchange_import_title` (ja: 「AIの回答を取り込む」) 等が既存。file読取buttonのMIME/size明示copyは不在 |
| 権限 | `AndroidManifest.xml` | clipboard権限は不要 (framework API)。SAFも不要 (user選択grant)。追加権限なしで実装可能 |
| diagnostics | `organizer/ui/exchange/` `organizer/integration/exchange/` `organizer/personalization/exchange/` | journal・logcat書込みは存在しない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約)。本変更でも書込み経路を新設しない |
| 既存test | `tests/unit/app/lawnchair/organizer/ui/exchange/` (`ExchangeFlowStateHolderTest`, `ExchangeDisclosureStateTest`)、`tests/unit/app/lawnchair/organizer/integration/exchange/ExchangeFlowControllerTest.kt` | holder levelでtransport注入・失敗注入のpatternが既にある (例: `FileExchangeTransport.writeOverride`、`settleDispatcher`)。exchangeのCompose UI test (instrumentation) は未整備 (`tests/organizer-instrumentation/` にはorganizer UI testの既存pattern例: `MissingAppSelectionInstrumentationTest`) |

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

   - `fun importFromClipboard(transport: ClipboardImportTransport)`: 読取 → 成功textは **既存manual paste内容を置き換えて** `ExchangeScreen.Importing(text)` へ (envelope検査は `onImportTextChange` と同一経路: `acceptsExchangeImportEnvelope` 失敗時 `INPUT_OVERSIZE`・state不採用) → 続けて既存 `import(text)` を起動しcommon pathへ。typed失敗時は新statusを設定し画面・既存入力を保持 (zero-write)。
   - `ExchangeStatus.Kind` へ `CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` を追加 (`exchangeStatusText` に対応strings)。
   - `ExchangeScreen.Importing` にfieldを足す場合は最小に抑える (editor開閉などの折りたたみ状態はcomposable内の `remember` local stateで十分。holder stateの増設は控える)。

3. **`ExchangeImportField` 再構成 (`ExchangeFlowUi.kt`, spec D-1/D-2/D-4)**

   - 構成順: title → 「クリップボードから読み込む」(primary, `Button`) → 「ファイルから読み込む」(対応型・1 MiB上限のcopyを近傍に明示) → 「詳細 / うまく読み込めない場合」section → manual paste (bounded editor) + clear + 「取り込む」→ cancel。
   - bounded editor: D-4確定前の暫定実装はパラメータ化 (`maxLines = 8` + `heightIn(max = <暫定dp>)`)。`OutlinedTextField` は `maxLines` 指定で内容超過時にfield内scrollする。200% font evidence (AC-8/9) 後に暫定値を確定値へ固定する。
   - clear: editor内容を空へ (置換動作の明示的操作)。clipboard/file読込成功時も既存内容を置換。
   - raw取り込みtextは結果確定後保持しない (成功時は既存どおりclose、失敗時の `ImportOutcomeScreen` はtextを保持しない)。

4. **parse-first outcome (spec D-5/D-6)**

   - `ExchangeImportPipeline.Prepared` / `ExchangeImportResult` へ **additive** に認識情報を含める: framing種別closed enum (#205 era: marker形式のみを表す1値。#329 era: 3値へ拡張)、codec受理 `intentSchemaVersion`、認識件数 (decode済みintentのitem数。privacy-safe: label/ref/rationale/confidenceは含めない)。
   - `ExchangeFlowController.importReply` はpipeline結果にこの認識情報を載せて返す (既存17種失敗の分類は不変、区切りを増やさない)。
   - `ExchangeImportOutcome` UI: 失敗時に「成否・認識framing (判明していれば)・typed案内」を中心表示し、raw全文は **折りたたみ (default閉) + `heightIn(max)` + `verticalScroll`** (export disclosureと同pattern) で提示。成功時は #328 未実装のため既存挙動 (即時run接続 + 1行status) を維持 (spec 成功時の境界 scenario)。
   - UI→表示modelの変換は純粋関数 (単元test可能なtop-level関数) に分離し、`exchangeFailureText` と同じ一対対応を保つ。

### #329 Normalizerへの接続点

- common path入口は `ExchangeImportPipeline.prepare` のみ。#329はここにnormalizer段を挿入し、framing enumを3値へ拡張する。#332のUI・transportは **#329有無を知らない**: enum値を受け取って表示するだけ。
- clipboard/file/manual pasteの全経路が同一入口へ集約していることを崩さない (transport内でtrim・文字置換等の前処理を入れない — 正規化は #329の責務)。

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
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeImportField` 再構成、`importFromClipboard`、`ExchangeStatus.Kind` 2種追加、outcome表示拡張、raw折りたたみ |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipeline.kt` | 認識情報 (framing enum・version・件数) のadditive付与 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt` | outcome経由の認識情報中継 (interface形状はできるだけ不変) |
| `lawnchair/res/values/strings.xml` + `lawnchair/res/values-ja/strings.xml` | clipboard読取button、fallback見出し、manual paste button、clear、対応型/size copy、`CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` 失敗文言、framing/version表示label (ja正本・en対訳。spec 123契約) |
| `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` ほか | 下記Verificationの追加 |
| 対象外 (変更しない) | `AndroidManifest.xml` (権限追加なし)、`ExchangeTransports.kt` の `FileExchangeTransport.read` (既存bounded readを再利用)、export flow全般、`ManualOrganizationPreferences.kt` (hosting契約不変)、#204 validator、envelope上限 |

## Migration and recovery

- DB・永続化・migrationは不変 (画面層と取得adapterのみの変更)。recovery/rollbackは「revertで現行UIに戻る」のみで追加作業不要。
- process recreation: `ExchangeScreen` はprocess-local stateであり現行契約のまま (#205の非永続契約)。validated intentの新規保持はしない。

## Verification

既存コマンド優先 (`docs/engineering/building.md` / `quality-strategy.md` 正本):

- `./gradlew spotlessCheck`
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` のうち exchange 関連 (`ExchangeFlowStateHolderTest` / `ExchangeFlowControllerTest` / 新transport test)。CI JVM gateがexchange packageをfilter対象にするかは PR のCI結果で確認し、必要なら `quality-strategy.md` のfilter追加を別途記録する。
- spec AC対応:

| AC | 検証 |
|---|---|
| AC-1 | transport unit test (1回読取・textのみ・null/empty/not-text/oversized注入)。listener不在はcode review + `ClipboardImportTransport` が `OnPrimaryClipChangedListener` を参照しないことの構造確認。holder test (読取→`import()` 呼出・置換・失敗時state保持) |
| AC-2 | 既存 `FileExchangeTransport.read` regression + UI copy存在 (strings test またはcode review) + `takePersistableUriPermission` 不在のreview (現行実装でも未使用、維持) |
| AC-3 | holder/editor test (巨大textでfield高さ不変・`maxLines` 超過時内部scroll、clear、clipboard/file読込による置換) |
| AC-4 | 画面構成testまたはinstrumentation (数十KB入力でpaste editor・raw detail双方がbounded)。Compose測定はinstrumentation (`tests/organizer-instrumentation` の既存UI test patternに倣う) またはUI構造assertion |
| AC-5 | 全sourceが同一 `import()`/pipeline入口へ集約する構造test (holder level) + UI層parse不在のreview |
| AC-6 | typed失敗のholder test (空/非text/oversized/file失敗) + `exchangeStatusText` / `exchangeFailureText` のstrings解決test (ja/en) |
| AC-7 | diagnostics書込み経路不在のregression review + outcome後のtext非保持test (state破棄assertion) |
| AC-8 | 手動/instrumentation evidence: TalkBack・Switch Access・keyboard・font scale 200%。D-4暫定値の確定根拠をここで記録 |
| AC-9 | physical device evidence (representative ChatGPT/Gemini mobile app copy → import)。docs/assessment/ またはIssue記録。#205 AC-10 evidenceと同一workflowで兼ね可 |
| AC-10 | parse-first表示のUI test (framing/version/件数表示・raw折りたたみdefault閉) + 既存17種 (または #329 merge後19種) 失敗表示regression + export flow regression (既存unit test green) |

高リスクlabel (`risk: layout-data` / `risk: migration`) は付かない見込み (DB不変)。ただしPR時の独立エビデンス要件は [github-workflow.md](../../docs/project/github-workflow.md) の判定に従う。

## Documentation updates

- `specs/332-exchange-import-input-ui/spec.md` のstatus遷移と本planの実測修正。
- 該当する正本があれば `DESIGN.md` gate 13 行の参照先追記 (内容不変なら更新不要)。
- 新規ADRは不要見込み (画面再構成であり変更困難な設計判断はspec Decisionとして処理)。

## Dependencies and blockers

- **#329**: 未mergeでも実装可能 (framing 1値)。merge済みならenum 3値・失敗19種に読み替え。実装順の依存なし (spec D-6)。
- **#328**: 成功状態は所有しない。#328が先でも後でも本planは成立。
- **owner decisions (spec Open questions)**: D-2 (manual paste配置 — 起草推奨: 折りたたみsection)、D-3 (file対応型 — 起草推奨: `text/plain` + `application/json`)、D-4 (実寸 — evidence後確定)。D-4確定前は暫定値をパラメータ化して実装し、AC-8/9 evidence PRで固定する。
- CI: exchange unit testがJVM gateのfilter対象かの確認 (初回PRで確認し、結果をPRへ記録)。

## Risks

- **Android clipboard挙動のdevice差**: null/empty帰着の条件・Android 12+のaccess toast表示はOEMで揺らぐ。typed失敗への統一map (原因非表示) で吸収し、AC-9 device evidenceで実機確認する。
- **SAF MIME報告の実態差**: file managerがJSONを `application/octet-stream` 等で報告する場合、D-3起草推奨 (`text/plain`+`application/json`) では選択できない。device evidenceで判明したら `text/*` 拡張をspec改訂で扱う (実装場当たり対応はしない)。
- **LazyColumn item内のnested scroll**: bounded editor/raw detailの内部scrollとリストscrollのgesture競合。export disclosureの `verticalScroll` 実績patternを流用し、instrumentation/manual操作で確認。
- **`ExchangeImportResult` 拡張の波及**: additive変更でも既存44 exchange unit testの `when` 網羅に影響する可能性。sealed interfaceへの追加は網羅 `when` のcompile errorとして検出される (安全側)。
- **diagnostics/privacy regression**: 新code pathでraw textをlogに流す誤書きの防止。AC-7 review checklistをPR templateに明記。

## Explicitly unverified areas

- 実機 (特にOEM ROM) でのclipboard読取挙動とtoast表示の実測 (AC-9で確認)。
- 実際のChatGPT/Gemini mobile appのcopy内容の形式分布 (#329の調査領域。#332では表示のみ影響)。
- Compose instrumentation testとしてのbounded editor高さ計測の実現性 (`tests/organizer-instrumentation` に前例がない場合、UI構造assertionへの代替をPRで記録)。
- exchange unit testのCI JVM gate filter対象化の要否。
- D-4確定値 (200% font/TalkBack evidence後)。

## Execution checklist

1. `ClipboardImportTransport` (読取adapter・test seam付き) + unit test (typed失敗注入)。
2. `ExchangeFlowStateHolder.importFromClipboard` + `CLIPBOARD_EMPTY` / `CLIPBOARD_NOT_TEXT` status + strings (ja/en) + holder test。
3. `ExchangeImportField` 再構成: clipboard/file primary配置、fallback section、bounded editor (D-4暫定パラメータ) + clear、置換semantics + editor/holder test。
4. `ExchangeImportPipeline` 認識情報のadditive付与 + `ExchangeImportOutcome` parse-first表示 (raw折りたたみ) + UI test/表示model unit test。
5. file読取UI copy (対応型/size明示、D-3 MIME filter) + 既存file経路regression。
6. a11y仕上げ: TalkBack label・live region・keyboard/Switch Access完結の確認、testTag整備。
7. device/a11y evidence (AC-8/AC-9) → D-4確定 → spec/plan status更新。
