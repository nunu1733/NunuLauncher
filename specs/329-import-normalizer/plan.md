# Implementation Plan: External Agent Exchange Import Normalizer

> Issue: #329
> Spec: [spec.md](./spec.md)
> Status: draft (spec acceptance待ち。実装はspec acceptance後のみ開始する)

## Re-entry status

- 2026-09-16: 初回draft。baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (origin/main、PR #334 merge後)。Issue #329は本文のみでコメントなし。前回snapshot無し。main差分のうち本plan対象への影響: #331実装 (PR #333) は `ExchangeImportPipeline` への `prepare`/`validate` 2段化と `ScopeBindingGate` 追加が中心で、normalizer挿入seam (`prepare` のframing→decode間) は存在し続けている。

## Current evidence

origin/main (`aab0d293d1a98bf59f5b164693f54ee1a63e3f0b`) 時点の確認事実:

- **import pipeline現状**: `organizer/personalization/exchange/ExchangeImportPipeline.kt` の `prepare(importText)` が `IntentImportParser.parse(importText)` (framing抽出) → `IntentCodec.decode(payload)` の順で実行し、`Prepared(intent)` か `ExchangeImportResult.Failure` を返す。`validate(prepared, session, structural, now)` がsession照会・expiry・digest・`SessionExportReconstructor.rebuild` → `IntentValidator.validate` を実行する (#331のscope拡張を含む)。**normalizerの挿入seamは `prepare` の入口 (framing抽出の前) が唯一** で、`ExchangeFlowController.importReply` (`organizer/integration/exchange/ExchangeFlowController.kt`) は `prepare` → session `load` → `validate` の順で呼ぶのみ。
- **framing parser現状**: `organizer/personalization/exchange/IntentImportParser.kt`。`parse` は (1) `utf8ByteLengthExceeds(text, MAX_EXCHANGE_IMPORT_BYTES)` (1 MiB、allocation-bounded実装) → (2) 先頭BOM除去・CRLF/CR→LF → (3) 完全行marker走査 (`isMarkerLine`: trim(' ', '\t') 後完全一致、`internal` visibility)。typed失敗 `ExchangeEnvelopeFailure` 4種 (`InputOversize` / `FramingMissing` / `FramingAmbiguous` / `FramingEmpty`)。marker規則は本planでは **変更しない** (spec D-1優先順1)。
- **envelope上限の適用箇所**: (a) `IntentImportParser.parse` 入口、(b) UI受領時 `ExchangeFlowUi` 内 `onImportTextChange` → `acceptsExchangeImportEnvelope(text)` (`INPUT_OVERSIZE` status)、(c) file読込 `organizer/integration/exchange/ExchangeTransports.kt` `read(uri)` のbounded read (limit = `MAX_EXCHANGE_IMPORT_BYTES`)。normalizer追加後もこの3箇所はそのまま (normalizer自身も入口検査を持つ。二重検査は冪等)。
- **失敗表示**: `ExchangeFlowUi.exchangeFailureText` が `ExchangeImportFailure.Envelope` (4種) + `ExchangeImportFailure.Contract` (#204 13 class) = 17種をexhaustive `when` でstring resourceへmap。stringsは `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` の `exchange_failure_*` (17種)。
- **diagnostics**: exchange経路 (`ui/exchange`・`integration/exchange`・`personalization/exchange`) にdiagnostics journal / logcat書込みは存在しない (grep確認)。organizer run journalは [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約でorganization run/recovery操作のみ。
- **test現状**: `tests/unit/app/lawnchair/organizer/personalization/exchange/` に `IntentImportParserTest` (marker corpus・envelope境界・決定性)、`ExchangeImportPipelineTest` (framing→decode→validate順序)、`ExchangePackageComposerTest`、`SessionExportReconstructorTest`、`ExchangeGenerationGateTest`。`tests/unit/app/lawnchair/organizer/ui/exchange/` に `ExchangeFlowStateHolderTest`。全テストはpure JVM unit test (instrumentation不要)。
- **純粋性guard**: `organizer/personalization/` はAndroid-free (spec 205 planのpurity guard適用範囲)。normalizerも同packageに置きAndroid依存を入れない。

## Design

### Modules and interfaces

新規 `organizer/personalization/exchange/ImportNormalizer.kt` (pure、Android-free):

```kotlin
sealed interface ImportNormalization {
    /** marker形式と認識した場合: 後段の #205 framing抽出へ元textを委譲する。 */
    data class MarkedFraming(val importText: String) : ImportNormalization
    /** marker以外の外形からpayloadを直接認識した場合。framing種別はclosed enum。 */
    data class Payload(val payload: String, val framing: RecognizedImportFraming) : ImportNormalization
    data class Failure(val failure: ImportNormalizationFailure) : ImportNormalization
}
enum class RecognizedImportFraming { MARKER, FENCED_JSON, STANDALONE_JSON }
sealed interface ImportNormalizationFailure {
    data object AmbiguousBlocks : ImportNormalizationFailure
    data object UnrecognizedFormat : ImportNormalizationFailure
}
```

- 入口で (a) envelope検査 (`utf8ByteLengthExceeds` 再利用。超過は既存 `InputOversize` — #205種をそのまま返す)、(b) transport正規化 (先頭BOM・CRLF/CR→LF。`IntentImportParser` と同一規則のhelper) を行う。
- 判定 (spec D-1の順序):
  1. marker行の存在検出に `IntentImportParser.isMarkerLine` (`internal`、同module) を再利用し、INTENT marker行が1つでもあれば `MarkedFraming(元text)` を返す (marker規則・失敗分類は #205 parserがそのまま担う。parserのBOM/CRLF正規化は冪等なので二重実行しても同一結果)。
  2. markerなしの場合、fence走査 (spec D-4 grammar: 行頭3連backtickで始まる行。opening info string = ``` 以降trim。closing = ``` で始まり残り空白のみ)。fenced block 2つ以上 → `AmbiguousBlocks`。ちょうど1つでinfo stringが `json` (trim・case-insensitive) → `Payload(内部trim, FENCED_JSON)`。ちょうど1つで非json tag → 手順3へfall through。
  3. 全体 (先頭末尾空白trim) のstrict JSON parse (`Json.parseToJsonElement`、lenient無効) に成功しrootが `JsonObject` → `Payload(trim済text, STANDALONE_JSON)`。**parse結果は外形認識のみに使い、payloadは元textをそのまま渡す** (再serializeしない。D-6部分文字列制約)。
  4. いずれも不成立 → `UnrecognizedFormat`。
- 処理は行scan・単一passで構成し、backtracking正規表現を使わない (D-8)。

`ExchangeImportPipeline.prepare` 変更 (唯一のproduction接続点):

```text
prepare(importText):
  when (normalizer = ImportNormalizer.normalize(importText)) {
    is Failure(InputOversize)      -> Envelope(InputOversize)          // 既存種
    is Failure(Ambiguous | Unrecog)-> Failure(Normalization(...))      // 新種
    is MarkedFraming               -> (現行どおり) IntentImportParser.parse → Extracted(payload) / FRAMING_* → codec
    is Payload                     -> payloadを直接 IntentCodec.decode へ (framing抽出skip)
  }
  → IntentCodec.decode (変更なし) → Prepared / Contract failure
```

- `ExchangeImportFailure` に第3variant `data class Normalization(val failure: ImportNormalizationFailure)` を追加 (Envelope/Contractは意味不変)。UI失敗表示は17種 → 19種。
- `IntentImportParser`・`IntentCodec`・`IntentValidator`・`SessionExportReconstructor`・`ExchangeFlowController`・transport群は **変更しない** (controllerはpipeline経由で自動的にnormalizer適用を受ける)。

### Data flow

```text
import text (paste受領時/envelope上限check済、file bounded read済)
  → ExchangeFlowController.importReply → ExchangeImportPipeline.prepare
      → ImportNormalizer.normalize (envelope再検査 → transport正規化 → 外形認識)
          ├─ MarkedFraming → IntentImportParser.parse (#205 marker規則、不変) → payload
          ├─ Payload(fenced/standalone) → payload
          └─ Failure → typed zero-write reject (新2種 / 既存InputOversize)
      → IntentCodec.decode (#204、不変) → session/digest/validator (不変)
  → validated intent → 既存run接続 (idle fresh run / #331 run内entry + ScopeBindingGate)
```

### Alternatives rejected

- **normalizerがmarker形式を含む全framingを自前抽出する設計**: #205 marker規則 (verbatim領域・`FRAMING_*` 3種) の実装とtestを二重化し、既存corpusの保証が分断されるため。marker形式は現行parserへ委譲 (`MarkedFraming`) し、normalizerはmarker以外の外形のみ所有する。
- **normalizerがpayloadをmarker形式textへ再構成してからparserへ渡す設計** (uniform round-trip): 恒等変換の往復が増えるだけで利益がないため、`Payload` はcodecへ直行させる。
- **standalone JSON認識を「先頭 `{`・末尾 `}`」の文字検査のみにする案**: 説明文が偶然 `{` 始まり `}` 終わりに折り返す場合に外形誤認し、失敗分類 (`SCHEMA_MISMATCH`) が不正確になる。全体strict parse + root object検査を採用する (parse結果は使用しない)。
- **json tag付きblockのみを数える曖昧性判定** (spec D-3比較案): 認識grammarに「tag種別ごとの候補数」という状態が増える。総数厳格案 (2 block以上でreject) を採用する。
- **無tag fence・`jsonc`等の受理、tilde fence対応**: 外形の明示性が下がり、V2での拡張は容易 (fall throughが認識不能に収束するだけ) なためV1対象外。
- **normalizer失敗のdiagnostics journal記録**: raw text不含でも、exchange import失敗はrun eventではなくjournal対象を広げる利益がない。UI typed表示のみ (D-7)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/ImportNormalizer.kt` (新規) | 外形認識・transport正規化・typed結果 (`ImportNormalization`) | #329所有の純粋logic。purity guard適用範囲内 |
| `organizer/personalization/exchange/ExchangeImportPipeline.kt` | `prepare` へnormalizer挿入、`ExchangeImportFailure.Normalization` 追加 | 唯一のproduction seam。全import経路が自動的に通る |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 新失敗2種の文字列 (再コピー案内・認識可能形式の提示を含む) | UI失敗表示19種の一対対応 |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | `exchangeFailureText` へ `Normalization` case追加 (exhaustive when) | 型→表示の閉じた対応 (既存17種と同形式) |
| tests: `ImportNormalizerTest.kt` (新規)、`ExchangeImportPipelineTest.kt`・`ExchangeFlowStateHolderTest.kt` (拡張) | corpus・property・security test | spec AC対応 |

変更しないもの: `IntentImportParser.kt` (marker規則不変)、`ExchangeContract.kt` (marker定数・1 MiB上限不変。fence grammar定数はnormalizer内に置く)、`IntentCodec` / `IntentValidator` / `ContextExportModels` (#204契約不変)、`ExchangeFlowController` / `ExchangeTransports` (pipeline経由で自動適用)、`SessionExportReconstructor` / `ScopeBindingGate` (#331契約不変)。

## Migration and recovery

- DB schema・storage・permission変更なし。全変更がin-processのparse logic追加のみで、release rollback = revertで完了し、残余データはない (import textは永続化していないため)。
- 既存marker形式importの挙動は不変 (`MarkedFraming` 委譲により同一parser・同一失敗分類)。既存 #205/#331 test corpusがそのまま回帰testとして機能する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `ImportNormalizerTest`: framing別corpus (marker/standalone/fenced/説明文併存/BOM/CRLF/前後空白) とcanonicalization結果固定 | unit test (pure module) |
| AC-2 | `ExchangeImportPipelineTest` 拡張: standalone/fenced入力がcodec/validator/session検証を通る経路、既存marker経路との同一outcome | unit test |
| AC-3 | `ImportNormalizerTest`: json×2 fence・json+非json fence → `AmbiguousBlocks`。inline `{...}`・無tag fence・非JSON文 → `UnrecognizedFormat` | unit test |
| AC-4 | property test: 正規化後payloadはtransport正規化済入力の部分文字列、決定性 (同一入力→同一結果)・冪等性 (正規化済入力の再正規化で不変) | unit test |
| AC-5 | pipeline構造test: `Payload`/`MarkedFraming` 両経路とも `IntentCodec.decode` を必ず通る (framingのみ成功では `Prepared` にならないことの網羅) | unit test |
| AC-6 | regression: 既存 #204 reject corpus (unknown key・out-of-scope ref・forbidden key・invalid enum・coverage漏れ) をstandalone/fenced framingで入力し同一失敗種別 | unit test |
| AC-7 | `ExchangeFlowUi`/stateholder test: 19種失敗表示のmap、新2種の案内copyに認識可能形式の提示を含む | unit + UI test、ja/en strings解決 |
| AC-8 | security corpus: nested wrapper 3型・巨大入力境界 (exact-limit/limit+1)・trailing malicious text・injection文埋め込み (fail-closed oracle) | unit test |
| AC-9 | diagnostics契約: normalizer追加後もexchange経路にjournal/logcat書込みなし (test + review) | unit test / review |
| AC-10 | physical-device evidence: ChatGPT/Gemini representativeコピー (marker付き・JSONのみ・説明文+fenced JSON・BOM/CRLF) をそのまま貼付/file import | physical device、docs/assessment/ またはissue記録 |

含める観察: unit/contract (framing corpus・経路強制)、property (部分文字列・決定性・冪等性)、security (adversarial corpus、fail-closed)、failure injection (envelope境界・不正JSON)、UI (失敗表示19種)。実装PRのbuild検証は `./gradlew spotlessCheck` + exchange関連unit test module (building guideのcommand集合から選択)。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md: 用語「インポート正規化 (Import Normalizer)」+ accepted framing一覧の要約 (acceptance時。#205「交換フレーミング」項への参照調整を含む)
- [ ] DESIGN.md §9 personalization行 + §11 gate 13 への注記 (marker規則は#205所有のまま、外形認識層を#329追加。acceptance時)
- [ ] spec 205 change history への経緯注記 (framing受理枠拡張は#329、marker規則・typed失敗の意味は不変。acceptance時)
- [ ] requirements.md: FR-017 status欄のspec link追加 (acceptance時)
- [ ] ADR: 本変更は「fail-closed境界の内側への追加」であり既存ADRと衝突しない。framing寛容化が実装後に「変更困難・理由がコードから分からない・実際の選択肢があった」を満たすと判断された場合のみADR化する

## Dependencies and blockers

- blockerなし。#205/#204/#331はすべてimplemented (baseline `aab0d293d1` に実体存在)。
- 本planの着手条件は **spec acceptanceのみ** (workflow実行契約に従う)。
- 下流: #332 (clipboard/file-first UI) は本normalizerのtyped結果と `RecognizedImportFraming` enumを消費する。#330 (authoring契約) は本specと並行して検討可能 (変更面の重なりなし)。

## Risks

- **認識grammarと実コピー実態の乖離** (無tag fence・`JSON`大文字・fence前行の引用符付き等): 認識不能rejectはtyped案内付きで安全側に倒れるため安全riskはないが、実用性が上がらない可能性。AC-10 device evidenceで実分布を早期把握し、V2でD-3/Open questionsを再検討する。
- **standalone JSON認識のparseコスト**: envelope上限1 MiBまでのstrict parseが外形認識で1回 + codec decodeで1回走り得る。user-initiated操作・IO dispatcher上であり許容するが、計測上問題になればcodec decode結果の再利用 (外形認識とdecodeの統合) をreviewで検討する。
- **fence grammarの簡易性**: 行頭空白付きfence等のCommonMark細部を意図的に無視するため、特殊な整形で認識不能になる (fail-closed側の誤動作)。安全ではない。
- 実装PRのhigh-risk gate該当性: layout DBへの書込み経路は持たないため `risk: layout-data` / `risk: migration` には該当しない見込み。ただしsecurity sensitiveなparse追加であるため、security corpus (AC-8) を必須とする。label判断は実装PRで行う。

## Explicitly unverified areas

- ChatGPT / Gemini等が実際に出力するコピーtextの分布 (fence tag種別・無tag頻度・BOM/CRLF混在) は未計測 (AC-10 device evidenceで実施)。
- 本draft時点でbuild/testは未実行 (docs-only変更のため)。
- 新失敗2種の最終的なja/en文言は実装時に確定する (spec AC-7は文言内容のみ要求)。

## Execution checklist

- [ ] Spec acceptance (owner review。D-1〜D-8、特にD-3とOpen questionsの確定)。
- [ ] Current behavior reproduced (marker無しJSON入力が現行 `FRAMING_MISSING` で拒否されることの確認test)。
- [ ] `ImportNormalizerTest` 失敗test先行 (framing corpus・曖昧・認識不能・部分文字列property)。
- [ ] Minimal implementation (`ImportNormalizer` + pipeline接続 + 19種表示)。
- [ ] Security/regression verification (AC-4〜AC-9 corpus)。
- [ ] Physical-device representative evidence (AC-10)。
- [ ] PR evidence and remaining risks recorded。
