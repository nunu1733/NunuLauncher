# Implementation Plan: External Agent Exchange Import Normalizer

> Issue: #329
> Spec: [spec.md](./spec.md)
> Status: accepted (2026-09-17。ChatGPT re-review Accepted (blocking/required 0件、head `150bc0b54b` 基準) を受け実装開始)

## Re-entry status

- 2026-09-16: 初回draft。baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (origin/main、PR #334 merge後)。Issue #329は本文のみでコメントなし。前回snapshot無し。main差分のうち本plan対象への影響: #331実装 (PR #333) は `ExchangeImportPipeline` への `prepare`/`validate` 2段化と `ScopeBindingGate` 追加が中心で、normalizer挿入seam (`prepare` のframing→decode間) は存在し続けている。
- 2026-09-17: 1st owner review ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/329#issuecomment-5699609908)、head `b7b96fbb01`) のRequired 3点対応。baseline `15f4f0209f` (origin/main、PR #338 merge後 = #330 intent schema v3実装込み) へrebaseし、plan型契約を修正: (1) envelope検査を `prepare` 先頭の #205所有gateへ移し、normalizer結果型の失敗は新2種のみに限定 (`InputOversize` のtyped identityは不変)、(2) nested fenceのtest oracleをD-4 grammarへ統一 (fence内info付きfence開始行→`SCHEMA_MISMATCH`、独立2 block→曖昧)、(3) `Prepared` へ `RecognizedImportFraming` をadditive fieldとして追加し認識framingをproduction outcomeまで保持 (#332共通path契約)。#330影響の再確認: `ContextExportModels` / `IntentCompletion` / `ExchangePackageComposer` 等は変更されたが、本plan対象の `IntentImportParser` / `ExchangeImportPipeline` / `ExchangeFlowUi` 失敗表示の構造は無変更 (schema文字列 `personalized-intent-v3` 化、13 class・17種表示は不変)。

## Current evidence

origin/main (`15f4f0209f`、#330 v3実装後。`IntentImportParser` / `ExchangeImportPipeline` / `ExchangeFlowUi` 失敗表示は `aab0d293d1` から構造無変更を再確認済み) 時点の確認事実:

- **import pipeline現状**: `organizer/personalization/exchange/ExchangeImportPipeline.kt` の `prepare(importText)` が `IntentImportParser.parse(importText)` (framing抽出) → `IntentCodec.decode(payload)` の順で実行し、`Prepared(intent)` か `ExchangeImportResult.Failure` を返す。`validate(prepared, session, structural, now)` がsession照会・expiry・digest・`SessionExportReconstructor.rebuild` → `IntentValidator.validate` を実行する (#331のscope拡張を含む)。**normalizerの挿入seamは `prepare` の入口 (framing抽出の前) が唯一** で、`ExchangeFlowController.importReply` (`organizer/integration/exchange/ExchangeFlowController.kt`) は `prepare` → session `load` → `validate` の順で呼ぶのみ。`Prepared` はcontrollerで構築されず (pipelineのみが構築)、既存消費者は `.intent` のみ。
- **framing parser現状**: `organizer/personalization/exchange/IntentImportParser.kt`。`parse` は (1) `utf8ByteLengthExceeds(text, MAX_EXCHANGE_IMPORT_BYTES)` (1 MiB、allocation-bounded実装) → (2) 先頭BOM除去・CRLF/CR→LF → (3) 完全行marker走査 (`isMarkerLine`: trim(' ', '\t') 後完全一致、`internal` visibility)。typed失敗 `ExchangeEnvelopeFailure` 4種 (`InputOversize` / `FramingMissing` / `FramingAmbiguous` / `FramingEmpty`)。marker規則は本planでは **変更しない** (spec D-1優先順1)。`utf8ByteLengthExceeds` は同module `internal` 関数として共有可能。
- **envelope上限の適用箇所**: (a) `IntentImportParser.parse` 入口、(b) UI受領時 `ExchangeFlowUi` 内 `onImportTextChange` → `acceptsExchangeImportEnvelope(text)` (`INPUT_OVERSIZE` status)、(c) file読込 `organizer/integration/exchange/ExchangeTransports.kt` `read(uri)` のbounded read (limit = `MAX_EXCHANGE_IMPORT_BYTES`)。normalizer追加後もこの3箇所はそのまま (prepare入口でも同一helperで検査し、normalizerへは検査済みtextのみ渡す。重複検査は冪等)。
- **失敗表示**: `ExchangeFlowUi.exchangeFailureText` が `ExchangeImportFailure.Envelope` (4種) + `ExchangeImportFailure.Contract` (#204 13 class) = 17種をexhaustive `when` でstring resourceへmap。stringsは `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` の `exchange_failure_*` (17種)。
- **payload schema現状**: #330により `INTENT_SCHEMA_VERSION = "personalized-intent-v3"` (Kotlin型名 `PersonalizedIntentV1` は不変)。`IntentCodec` / `IntentValidator` のfailure classは13種のまま (#330は `INCOMPLETE_COVERAGE` の条件narrowのみでclass数不変)。normalizerはschemaVersionを解釈しないためv3に直接依存しない。
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

- **入口契約は「envelope検査済みtext」** (review Required 1対応)。1 MiB envelope検査の所有者は #205 gateであり、`ExchangeImportPipeline.prepare` が先頭で既存 `utf8ByteLengthExceeds` (同module `internal`) を用いて検査し、超過は既存どおり `Envelope(InputOversize)` として返す (typed identity不変)。normalizerの結果型にenvelope失敗variantは存在せず、失敗は新2種のみ。超過入力がnormalizerへ到達しないことはprepareの検査順序 (構造test) で保証する。
- 入口でtransport正規化 (先頭BOM・CRLF/CR→LF。`IntentImportParser` と同一規則のhelper) を行う。
- **standalone認識の深さ上限 (実装時決定)**: 厳格parseの前に単一passのbracket深度scan (文字列リテラル回避、正規表現なし) を行い、深度が `MAX_JSON_DEPTH = 64` を超える入力は `UnrecognizedFormat` でfail-closedにする。意図schemaは浅いため正當payloadに影響せず、1 MiB envelope内の深い入れ子adversarial入力が外形認識段の再帰parseで資源を浪費することを防ぐ (D-8のbounded処理の具体化。AC-8 corpusに `deeplyNestedStandaloneInputFailsClosedWithoutInterpreting` として包含)。
- 判定 (spec D-1の順序):
  1. marker行の存在検出に `IntentImportParser.isMarkerLine` (`internal`、同module) を再利用し、INTENT marker行が1つでもあれば `MarkedFraming(元text)` を返す (marker規則・失敗分類は #205 parserがそのまま担う。parserのBOM/CRLF正規化は冪等なので二重実行しても同一結果)。
  2. markerなしの場合、fence走査 (spec D-4 grammar: 行頭3連backtickで始まる行。opening info string = ``` 以降trim。closing = ``` で始まり残り空白のみ)。fenced block 2つ以上 → `AmbiguousBlocks`。ちょうど1つでinfo stringが `json` (trim・case-insensitive) → `Payload(内部trim, FENCED_JSON)`。ちょうど1つで非json tag → 手順3へfall through。
  3. 全体 (先頭末尾空白trim) のstrict JSON parse (`Json.parseToJsonElement`、lenient無効) に成功しrootが `JsonObject` → `Payload(trim済text, STANDALONE_JSON)`。**parse結果は外形認識のみに使い、payloadは元textをそのまま渡す** (再serializeしない。D-6部分文字列制約)。
  4. いずれも不成立 → `UnrecognizedFormat`。
- 処理は行scan・単一passで構成し、backtracking正規表現を使わない (D-8)。

`ExchangeImportPipeline.prepare` 変更 (唯一のproduction接続点):

```text
prepare(importText):
  0. utf8ByteLengthExceeds (既存 #205 gate)            -> Failure(Envelope(InputOversize))   // 既存種、型は不変
  1. when (normalizer = ImportNormalizer.normalize(importText)) {
       is Failure(Ambiguous | Unrecog)                 -> Failure(Normalization(...))        // 新2種のみ
       is MarkedFraming                                -> (現行どおり) IntentImportParser.parse
                                                          → Extracted(payload, framing=MARKER) / FRAMING_* → codec
       is Payload(payload, framing)                    -> payloadを直接 IntentCodec.decode へ (framing抽出skip)
     }
  2. IntentCodec.decode (変更なし) -> Prepared(intent, framing) / Contract failure
```

- `ExchangeImportFailure` に第3variant `data class Normalization(val failure: ImportNormalizationFailure)` を追加 (Envelope/Contractは意味不変)。UI失敗表示は17種 → 19種。
- **`Prepared` へ `framing: RecognizedImportFraming` をadditive fieldとして追加** (review Required 3対応)。marker経路は `MARKER`、`Payload` 経路は認識値。既存消費者 (`ExchangeFlowController`・run接続) が読む `.intent` は不変で、controllerは構築に関与しないため変更不要。認識framingは `Prepared` まで保持され、#332のparse-first表示が共通pathから参照できる (#332 spec「pipeline/controllerは認識段階の情報をoutcomeに含めて返す」と整合)。`validate` / `import` は `Prepared` をそのまま受け渡すのみで変更なし。
- `IntentImportParser`・`IntentCodec`・`IntentValidator`・`SessionExportReconstructor`・`ExchangeFlowController`・transport群は **変更しない** (controllerはpipeline経由で自動的にnormalizer適用を受ける)。

### Data flow

```text
import text (paste受領時/envelope上限check済、file bounded read済)
  → ExchangeFlowController.importReply → ExchangeImportPipeline.prepare
      → 1 MiB envelope検査 (既存 #205 gate、先頭。超過 → Envelope(InputOversize) でzero-write、normalizerへ到達しない)
      → ImportNormalizer.normalize (transport正規化 → 外形認識)
          ├─ MarkedFraming → IntentImportParser.parse (#205 marker規則、不変) → payload (framing=MARKER)
          ├─ Payload(fenced/standalone) → payload (framing=認識値)
          └─ Failure → typed zero-write reject (新2種のみ)
      → IntentCodec.decode (#204、不変) → Prepared(intent, framing) → session/digest/validator (不変)
  → validated intent → 既存run接続 (idle fresh run / #331 run内entry + ScopeBindingGate)
```

### Alternatives rejected

- **normalizerがmarker形式を含む全framingを自前抽出する設計**: #205 marker規則 (verbatim領域・`FRAMING_*` 3種) の実装とtestを二重化し、既存corpusの保証が分断されるため。marker形式は現行parserへ委譲 (`MarkedFraming`) し、normalizerはmarker以外の外形のみ所有する。
- **normalizerがpayloadをmarker形式textへ再構成してからparserへ渡す設計** (uniform round-trip): 恒等変換の往復が増えるだけで利益がないため、`Payload` はcodecへ直行させる。
- **normalizerの結果型に `ExchangeEnvelopeFailure` を運ぶvariantを設ける設計** (review Required 1比較案): envelope検査は #205所有gateであり、その失敗をnormalizerの型に重複させると「どの層が上限を所有するか」が曖昧になり、`INPUT_OVERSIZE` のtyped identityも二重の発生源を持つ。`prepare` 先頭で先に決着させ、normalizer型から分離する設計を採用。
- **認識framingをnormalizer API内部だけに留め、spec D-5を後退させる設計** (review Required 3比較案): #332 specが「pipeline/controllerは認識段階の情報をoutcomeに含めて返す」「#329 normalizerは同seamへframing enumを挿入する」ことを要求しており、後退させると #332契約の再設計が必要になる。`Prepared` へのadditive field (既存消費者無変更) で満たす設計を採用。`Validated` (stage 2以降) への伝播は #332の要求 (parse状態表示) に不要なため行わない。
- **standalone JSON認識を「先頭 `{`・末尾 `}`」の文字検査のみにする案**: 説明文が偶然 `{` 始まり `}` 終わりに折り返す場合に外形誤認し、失敗分類 (`SCHEMA_MISMATCH`) が不正確になる。全体strict parse + root object検査を採用する (parse結果は使用しない)。
- **json tag付きblockのみを数える曖昧性判定** (spec D-3比較案): 認識grammarに「tag種別ごとの候補数」という状態が増える。総数厳格案 (2 block以上でreject) を採用する。
- **無tag fence・`jsonc`等の受理、tilde fence対応**: 外形の明示性が下がり、V2での拡張は容易 (fall throughが認識不能に収束するだけ) なためV1対象外。
- **normalizer失敗のdiagnostics journal記録**: raw text不含でも、exchange import失敗はrun eventではなくjournal対象を広げる利益がない。UI typed表示のみ (D-7)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/ImportNormalizer.kt` (新規) | 外形認識・transport正規化・typed結果 (`ImportNormalization`)。envelope検査は所有しない | #329所有の純粋logic。purity guard適用範囲内 |
| `organizer/personalization/exchange/ExchangeImportPipeline.kt` | `prepare` 先頭へのenvelope gate明示化、normalizer挿入、`ExchangeImportFailure.Normalization` 追加、`Prepared` へ `framing` field追加 | 唯一のproduction seam。全import経路が自動的に通る |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 新失敗2種の文字列 (再コピー案内・認識可能形式の提示を含む) | UI失敗表示19種の一対対応 |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | `exchangeFailureText` へ `Normalization` case追加 (exhaustive when) | 型→表示の閉じた対応 (既存17種と同形式) |
| tests: `ImportNormalizerTest.kt` (新規)、`ExchangeImportPipelineTest.kt`・`ExchangeFlowStateHolderTest.kt` (拡張) | corpus・property・security test | spec AC対応 |

変更しないもの: `IntentImportParser.kt` (marker規則不変)、`ExchangeContract.kt` (marker定数・1 MiB上限不変。fence grammar定数はnormalizer内に置く)、`IntentCodec` / `IntentValidator` / `ContextExportModels` (#204契約不変)、`ExchangeFlowController` / `ExchangeTransports` (pipeline経由で自動適用)、`SessionExportReconstructor` / `ScopeBindingGate` (#331契約不変)。

## Migration and recovery

- DB schema・storage・permission変更なし。全変更がin-processのparse logic追加のみで、release rollback = revertで完了し、残余データはない (import textは永続化していないため)。
- 既存marker形式importの挙動は不変 (`MarkedFraming` 委譲により同一parser・同一失敗分類)。既存 #205/#331 test corpusのうちmarker形式のcorpusはそのまま回帰testとして機能する。唯一の意図的再分類は **marker行を一切含まないplain prose** で、`FRAMING_MISSING` からnormalizerの `UnrecognizedFormat` (typed案内付き) へ変わる (spec D-1優先順1の帰結。既存test `envelopeFailuresPassThroughWithoutSessionAccess` を更新し、`FRAMING_MISSING` はBEGIN-only入力で担保)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `ImportNormalizerTest`: framing別corpus (marker/standalone/fenced/説明文併存/BOM/CRLF/前後空白) とcanonicalization結果固定 | unit test (pure module) |
| AC-2 | `ExchangeImportPipelineTest` 拡張: standalone/fenced入力がcodec/validator/session検証を通る経路、既存marker経路との同一outcome | unit test |
| AC-3 | `ImportNormalizerTest`: json×2 fence・json+非json fence → `AmbiguousBlocks`。inline `{...}`・無tag fence・非JSON文 → `UnrecognizedFormat` | unit test |
| AC-4 | property test: 正規化後payloadはtransport正規化済入力の部分文字列、決定性 (同一入力→同一結果)・冪等性 (正規化済入力の再正規化で不変) | unit test |
| AC-5 | pipeline構造test: `Payload`/`MarkedFraming` 両経路とも `IntentCodec.decode` を必ず通る (framingのみ成功では `Prepared` にならないことの網羅) | unit test |
| AC-6 | regression: 既存 #204 reject corpus (unknown key・out-of-scope ref・forbidden key・invalid enum・coverage漏れ) をstandalone/fenced framingで入力し同一失敗種別 | unit test |
| AC-7 | `ExchangeFlowUi`/stateholder test: 19種失敗表示のmap、新2種の案内copyに認識可能形式の提示を含む。`Prepared.framing` が経路ごとに `MARKER` / `FENCED_JSON` / `STANDALONE_JSON` を返すことの表明 | unit + UI test、ja/en strings解決 |
| AC-8 | security corpus: nested wrapper (marker内fence→`SCHEMA_MISMATCH`、fence内marker→受理、fence内info付きfence開始行→`SCHEMA_MISMATCH`、独立2 block→曖昧reject)・envelope境界 (exact-limit/limit+1、`prepare` 先頭gateで決着しnormalizer未呼出)・trailing malicious text・injection文埋め込み (fail-closed oracle) | unit test |
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

- blockerなし。#205/#204/#331/#330はすべてimplemented (baseline `15f4f0209f` に実体存在。#330のv3 schemaはnormalizerに対して透過)。
- 本planの着手条件は **spec acceptanceのみ** (workflow実行契約に従う)。
- 下流: #332 (clipboard/file-first UI) は本normalizerのtyped結果と、`Prepared` まで伝播した `RecognizedImportFraming` enumを共通import path経由で消費する (#332 specの「認識段階の情報をoutcomeに含めて返す」additive拡張と整合)。

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

- [x] Spec acceptance (2026-09-17 accepted。ChatGPT re-review Accepted、head `150bc0b54b` 基準。D-3/Open questionsはdraft案のまま実装へ)。
- [x] Current behavior reproduced (`envelopeFailuresPassThroughWithoutSessionAccess` 更新: marker無しproseは本specにより `FRAMING_MISSING` からnormalizerの `UnrecognizedFormat` へ再分類される — D-1の意図的挙動変更。`FRAMING_MISSING` はmarker path (BEGINのみでEND無し) として同一test内で維持)。
- [x] `ImportNormalizerTest` 失敗test先行 (framing corpus・曖昧・認識不能・部分文字列property・深度上限・決定性・冪等性。18 test)。
- [x] Minimal implementation (`ImportNormalizer` + pipeline接続 + `Prepared.framing` + 19種表示)。
- [x] Security/regression verification (AC-4〜AC-9 corpus。pipeline test 10 test追加・1 test更新)。
- [ ] Physical-device representative evidence (AC-10)。#205と同じく後続evidence PRでの実施を予定 (受入条件は残置)。
- [ ] PR evidence and remaining risks recorded。

### 実行した検証 (2026-09-17、JDK 21.0.12 / AGP環境)

- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.personalization.exchange.*'` → PASS (69 tests、exchange面: normalizer 18 + pipeline 16 + 既存corpus)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → PASS (organizer全体surface)
- `./gradlew spotlessCheck` → PASS (一度違反を `spotlessApply` で解消後)
- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL
