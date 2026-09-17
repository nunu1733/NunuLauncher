---
issue: "#348"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-18
---

# External Agent ExchangeのAI-facing contractをproduction truthと同期し初回Import成功率を上げる

> Status: **draft** (2026-09-18)。Issue #348のspec。依存: #204 (accepted・実装済み), #205 (implemented), #329 (implemented), #330 (implemented), #345 (evidence記録済み: [assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md))。本specがproduction contract (#204)・framing規則 (#205)・normalizer (#329) の **動作を1つも変更しない** ことを前提に、AI-facingなinstruction/example層をproduction truthから派生させる。

## Problem

External Agent Exchangeの通常UXは、Launcher ↔ 外部AIのartifact交換を1往復に固定する (Launcher → AI: request package 1回、AI → Launcher: final artifact 1回)。外部AI内での調査・bounded interview・整理方針確認は許容するが、import validation errorをAIへ戻してrepairさせるfeedback loopは通常flowではない。成功の主戦略は **finalize前のcontract adherence** であり、失敗後の回復ではない。

#345のrepresentative evidenceでは、この制約に対して初回成功率に2種類の問題が確認された。

1. **transport layer**: ChatGPT mobile webのmessage-copyが各行末に `\` + 改行を入れ、`-----BEGIN NUNULAUNCHER INTENT-----` 完全行marker一致が壊れた (`FRAMING_MISSING`)。
2. **contract layer**: code-block copyではclean JSONを得られたが、外部AIが `globalPreference.organization` / `grouping` 等のunknown field、小文字enum (`"high"`)、非整数confidence (`0.82`) を生成し、#204 contractに適合しなかった。

現在のexchange package instruction ([ExchangePackageComposer](../../lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt)) は `schemaVersion` の名指しとpartial authoring (#330) の説明を持つが、**許可property一覧・型・enum値・数値制約を列挙していない**。そのため外部AIは「親切な補足」として未定義fieldを作り、strict validatorがfail-closedで拒否する。AI向けschemaがproduction validatorと独立した手書きテキストである限り、この種の初回失敗は構造的に減らせない。

## Outcome

exchange packageのAI-facing instructionが、production codec/validatorが所有する同一のsource-of-truth (schema version・allow-list・enum・数値制約) から **compose時に派生** する。instructionは未定義propertyの追加禁止・finalize直前のself-check・不足時はAI会話内での質問 (独自値で帳尻合わせをしない) を明示し、#329が受理するframingのうち **単一のcanonical final form (fenced `json` code block 1個 + candidate 1個)** を要求する。production contract・import経路・失敗分類は一切変わらず、strict validation / fail-closed / zero-writeは維持される。#345の契約不適合出力はregression fixtureとして固定され、representative flowでのfirst-pass import成功がevidence化される。

## Scope

- `ExchangePackageComposer` instruction部の再構成。production truth (`ContextExportContract`、`IntentCodec` allow-list、`Importance` / `ExportRegionKind` enum) からの **runtime derivation** によるoutput contract sectionの追加。
- Finalization self-check section (最終応答直前の確認事項) の追加。
- 不足情報時の挙動指示 (finalize前に質問 / accepted unresolved・omission semanticsを使う / 独自propertyを作らない) の明示。
- Response formatの変更: 単一fenced `json` block + candidate 1個を要求 (marker行の要求は廃止。**import経路のmarker受理は不変**)。
- instructionが参照する推奨final formに揃える、import失敗案内copy (en/ja) の文言更新。失敗分類 (19種)・表示対応は不変。
- `confidence` の0..100制約のnamed constant化 (`ContextExportContract`) と `IntentCodec` allow-listの同一module内 `internal` 公開 (derivationのため)。
- Contract test: derivation pinning・instruction準拠golden payloadのdecode/validate成功・#345契約不適合fixtureのtyped failure固定。
- One-round-trip UX invariantの明文化 (本spec)。

## Non-goals

- #204 strict validator / codec / #330 completion / #329 normalizerの動作変更。`ImportNormalizer` は `\` エスケープ行の救済 (semantic repairに接尾する可能性) を含め一切変更しない。
- marker framing規則の変更 (#205所有の抽出規則・typed失敗4種は不変。instructionが要求しなくなるだけ)。
- validation error → AI repair request生成、Launcher内LLM repair loop、post-import multi-round retryの通常UX化。
- provider固有private API / UI automation。
- #327 interview-first UXの実装 (本specはそのfinalization時に使われるAI-facing contractの同期のみ所有。#327のcanonical example/templateは本specのdrift防止策の対象)。
- #345 TalkBack / Switch Access等のaccessibility evidence取得。
- schema version bump (v3のまま。production contractは1文字も変わらない — instructionがその **表示** を派生するだけ)。

## Domain language

**AI-facing contract (AI向け契約)**:
exchange package instruction部のうち、外部AIが返答をauthoringするために必要なschema情報 (schema version・許可property・型・enum値・数値制約・ref規則・partial authoring semantics) の総称。独立したschemaではなく、production codec/validatorの派生表示 (_view_) である。
_Avoid_: AI schema (productionと別の正本を想起させる)、prompt template (framing規則やCONTEXT data部と混同する)

**Canonical final form (正本final形式)**:
外部AIに最終応答として要求する、#329が受理するframingのうち単一の形式。本specでは「単一のfenced `json` code block内にJSON object 1個、それ以外のcode block/candidateなし」を指す。import経路はmarker形式を含む3受理外形を従来どおりすべて受理する。
_Avoid_: 推奨形式 (複数候補を認める呼称)

## Behavior scenarios

### Scenario: instruction部のoutput contractはproduction truthから派生する

Given production mainの `ContextExportContract.SCHEMA_VERSION` (`personalization-context-v3`) / `INTENT_SCHEMA_VERSION` (`personalized-intent-v3`)、`IntentCodec` のallow-list (`schemaVersion` / `exportId` / `itemIntents` / `unresolvedRefs` / `globalPreference` / `rationale` / `confidence`、item keys `ref` / `importance` / `desiredGroup` / `groupSemantic` / `pageAffinity` / `regionAffinity` / `preserve`、`globalPreference` keys `minimizeMovement`、`groupSemantic` keys `category` / `freeText`)、`Importance` (`HIGH` / `NORMAL` / `LOW`)、`ExportRegionKind` (`TOP` / `MIDDLE` / `BOTTOM`)、confidence整数0–100、`freeText` ≤100字、`rationale` ≤500字、
When exchange packageをcomposeする、
Then instruction部のoutput contract sectionは上記の名前・値・制約を **compose時にproduction symbolから直接整形して** 含み、
And instruction部はproduction contractに存在しないproperty・enum値・操作 (widget span、座標、DB変更等) を能力として約束しない。

### Scenario: 未定義propertyの追加禁止とfinalize前self-check

Given 外部AIが整理方針を確定するための情報を不足したまま最終応答を組み立てようとしている、
When instruction部を読む、
Then 「output contractに列挙されていないpropertyを追加してはならない (例え補足的・親切な意図でも)」が明示され、
And 「不足情報がある場合はfinalize前にuserへ質問する。判断できないitemは未判断のまま残す (省略または `unresolvedRefs`)。schemaで表現できない概念を独自propertyで表現しない」が明示され、
And 最終応答直前のself-check (advertised schemaVersionのみ・未定義propertyなし・型/enum大文字/数値制約遵守・CONTEXT data外のrefなし・同一refの重複記述なし・未判断itemの推測禁止・最終応答にimport候補1個のみ) が列挙されている。

### Scenario: canonical final form (単一fenced json block) の要求

Given instruction部のResponse format section、
When 外部AIが最終応答を返す、
Then 要求される形式は「 ```json fence 1個のcode block、内部はJSON object 1個のみ。それ以外のcode blockを含めない。candidateは1個のみ。block外の説明文は最小限にする」であり、
And instruction部は `-----BEGIN/END NUNULAUNCHER INTENT-----` marker行を要求しない、
And import経路は従来どおりmarker形式・fenced形式・standalone形式の3外形をすべて同一のtyped挙動で受理する (既存 `ImportNormalizerTest` / `ExchangeImportPipelineTest` 回帰は無変更で成功)。

> 根拠 (#345実測): ChatGPT mobile webのcode-block copyはfence内のJSON本体をcleanにclipboardへ載せる。fence形式は (a) 全文copyでfenced path、(b) code-block copyでstandalone path、の両方で#329が受理する外形に帰着する確率がmarker形式より高い。fence内部がJSONのみであることはfenced pathのinfo string `json` 判定と、code-block copyでのstandalone path成立の双方に効く。

### Scenario: instruction準拠のgolden payloadはdecode・validateを通る

Given exchange packageのinstructionに従い、許可keyのみ・大文字enum・整数confidence・CONTEXT data内refのみで構成したgolden intent JSONを単一fenced blockに置く、
When `ImportNormalizer` → `IntentCodec.decode` → `IntentValidator.validate` (既存pipeline seam) に通す、
Then 成功し、#330 completion後のcanonical表現が得られる。

### Scenario: #345契約不適合出力のtyped failure固定 (regression fixture)

Given #345で実測された契約不適合出力をfixture化した入力 (top-levelに `globalPreference.organization` を追加したもの、`grouping` をtop-level/itemに追加したもの、`importance` 小文字 `"high"`、`confidence: 0.82`、および契約適合のcanonical payload)、
When 同一pipeline seamに通す、
Then それぞれ未知propertyは `SCHEMA_MISMATCH`、小文字enumと非整数confidenceは `INVALID_ENUM` でfail-closed rejectされ、canonical payloadのみ成功する、
And normalizer・validatorの実装はこの検証のために1行も変更されない。

### Scenario: one-round-trip invariantの維持 (失敗案内copyの整合)

Given import失敗時に表示されるtyped失敗案内copy (en/ja)、
When canonical final formへの移行後に表示文言を読む、Then 推奨final形式に参照が揃い (fenced json block主体、marker表現は「従来形式も受理」の補助的言及に留める)、失敗分類19種とその表示対応は不変である、
And 案内copyもinstruction部も、import failure diagnosticsをAIへcopyしてrepair依頼させる手順を通常flowとしては提示しない (再copy・再依頼は回復手段としての言及に留める)。

## Decisions

1. **同期方式: runtime derivation + contract test (prose pinning)**。比較: (a) 同一source-of-truthからの生成 (採用。composerが `ContextExportContract` / `IntentCodec` allow-list / enum entriesをcompose時に参照し、output contract sectionを整形する。driftが構造的に不可能。同一Gradle module内なので可視性変更は最小で済む)、(b) production定義からのbuild時metadata生成 (却下。build stepと二次表現を増やす割に、instructionという表示物への効用が(a)と同じ)、(c) 手書きprose + 強いcontract testのみ (却下。test通過時点での同期は保証するが、test緩和やcompile後のprose改変でdriftが再発する。prose部分のpinningには補助として採用)。**production validatorと別の独自AI schemaは作らない。**
2. **canonical final form: 単一fenced `json` block + candidate 1個**。#345実測の2 copy affordance (message copy / code-block copy) の双方で#329受理外形に帰着しやすいのはfence形式。marker形式はimport経路のcanonical受理のまま維持し (既存返答の互換、優先度1の抽出規則も不変)、instructionからの要求のみ撤去する。UI失敗案内copyはこの推奨に揃える。#329 normalizerの拡張はしない (message-copyの `\` エスケープ問題はcanonical final form選択により **発生源头で緩和** され、受信側の救済はsemantic repairに接尾するため行わない。残余リスクはtyped failureでfail-closed)。
3. **instruction部のsection構成** (spec 205 Decision 2の改訂): Goal / You may / Output contract (新設、derived) / You must (mobility・partial authoring規則。CONDITIONALへの `desiredGroup`/`groupSemantic` 禁止を追加) / Before sending your final answer (self-check、新設) / Response format (fenced形式)。英文final proseの微調整は実装reviewとrepresentative evidenceで行う (構造・必須要素は本specで固定)。
4. **one-round-trip UX invariant**: Launcher → AI request 1回、AI → Launcher final artifact 1回。AI内での2〜4問程度のbounded interview・Web検索・方針確認は許容。import後のAI repair loopは通常flowに含めない。失敗はtyped表示でfail-closedし、成功戦略はfinalize前のcontract adherenceに置く。#205のframing失敗時の「再依頼」案内は回復手段の説明として許容するが、repair手順の標準化・diagnosticsのAIへの持ち込み指示は追加しない。
5. **#345 regression fixtureの所有**: 本spec (#348) がcontract不適合出力のfixture/評価caseを単unit testとして保持する。transport層の `\` エスケープ実測例は#345 evidence記録 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)) が正本とし、unit fixtureにはしない (normalizerを変更しないため、壊れたmarker行の救済fixtureは恒久的な期待値にならない)。

## Data and state

- 読むdata: production symbol (`ContextExportContract`、`IntentCodec`、`Importance`、`ExportRegionKind`) とexport JSON (composerは従来どおり解釈しない。output contractはper-export情報 — `exportId`、ref、`gridContext.pageCount` — を埋め込まず、CONTEXT dataそのものを参照させる)。
- 永続化: なし (packageは生成時完了のimmutable値。現行どおり)。
- migration / backup / rollback: 影響なし (DB書込経路なし。zero-write境界は不変)。
- 変更するstrings: `exchange_failure_framing_missing`、`exchange_failure_framing_empty`、`exchange_import_retry_hint`、`exchange_failure_normalization_unrecognized` (en + ja)。失敗key・表示対応・19種分類は不変。

## Acceptance criteria

- [ ] AC-1: one-round-trip UX invariant (request 1回・final artifact 1回・AI内bounded interview許容・import後AI repair loop不採用) が本specに明文化され、package instructionと失敗案内copyがrepair loopを通常flowとして指示しないことがtestで確認される。
- [ ] AC-2: AI-facing contract (output contract section) がcompose時にproduction truthから派生し、instruction内のschema version・許可top-level/item/global/semantic keys・enum値 (`Importance` / `ExportRegionKind`)・confidence 0–100整数・`freeText`/`rationale` 上限が、production symbolを参照するunit testで機械検証される (期待値をtest側へliteral複写しない)。
- [ ] AC-3: instructionが未定義propertyの追加禁止・型/enum casing/数値制約・export-scoped ref (CONTEXT data外のref不使用)・同一ref多重記述禁止・#330 partial authoring semantics・FIXED/CONDITIONAL/CANDIDATEのauthoring規則を明示する。prose文はcontract testでpinningされる。
- [ ] AC-4: instructionに準拠して構成したgolden intent JSONが単一fenced block経由で `ImportNormalizer` → `IntentCodec` → `IntentValidator` を通り、completion後のcanonical表現まで到達する (contract test。canonical example/templateとproduction codec/validatorの同期保証)。
- [ ] AC-5: finalization self-check (advertised schemaVersionのみ・未定義propertyなし・型/enum/数値制約・ref範囲・重複禁止・未判断itemの推測禁止・最終応答にcandidate 1個のみ) がinstructionに存在し、testでpinningされる。
- [ ] AC-6: 不足情報時はfinalize前の質問またはaccepted unresolved/omission semanticsを使う旨がinstructionに明示され、独自property・独自値での帳尻合わせが禁止されている (test)。
- [ ] AC-7: Response formatが単一fenced `json` block + candidate 1個を要求し、marker行を要求しない。既存の3受理外形 (marker / fenced / standalone) のimport挙動は既存test群が無変更で成功する (regression)。
- [ ] AC-8: #345実測の契約不適合出力 (unknown `globalPreference.organization`、unknown `grouping`、小文字enum、非整数confidence) がregression fixtureとして記録され、各typed failure (`SCHEMA_MISMATCH` / `INVALID_ENUM`) でfail-closed rejectされることがtestされる。canonical payloadは成功する。
- [ ] AC-9: import失敗案内copy (en/ja) がcanonical final formに揃い、失敗分類19種・表示対応が不変であることがtestされる (既存instrumentation testはresource ID参照のため文言変更の影響を受けない)。
- [ ] AC-10: strict validation / fail-closed / zero-writeが維持される。本specの変更で `IntentValidator` / `IntentCodec` (confidence制約の定数化を除く) / `ImportNormalizer` / framing抽出の挙動が変わらないことが既存test群の無変更成功で確認される。
- [ ] AC-11: representativeなExternal Agent flow (request生成 → 外部AI (ChatGPT等) でのinterview/確認付き応答 → final artifact → clipboardまたはfile import) が、AIへのrepair依頼なしにfirst-passでimport成功し、[docs/assessment](../../docs/assessment/) 配下にevidence記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | spec本文 + instruction/footer文字列の否定assertion (repair手順指示の不在) + strings review |
| AC-2 | `ExchangePackageComposerTest` / 新規 `Issue348` contract test (production symbol参照でcontainment assert) |
| AC-3 | contract test (禁止文・mobility規則・partial authoring文のpinning) |
| AC-4 | golden payloadをpipeline seamへ通すunit test (`ExchangeImportPipelineTest` のfixture流用) |
| AC-5 | contract test (self-check必須要素のpinning) |
| AC-6 | contract test (質問/omission指示・独自property禁止のpinning) |
| AC-7 | `ImportNormalizerTest` / `ExchangeImportPipelineTest` / `IntentImportParserTest` 無変更成功 + composer test (marker要求の不在・fence要求の存在) |
| AC-8 | `Issue348` regression test (fixture → typed failure固定) |
| AC-9 | unit test (文言参照の整合) + `ExchangeImportSurfaceInstrumentationTest` 成功 |
| AC-10 | 既存全unit test + `spotlessCheck` + assemble |
| AC-11 | physical/emulator device evidence記録 (docs/assessment/ 配下) |

## Open questions (non-blocking)

1. instruction英文の最終prose (列挙順・言い回し) は実装reviewとAC-11 evidenceのagent遵守率で調整する (構造・必須要素はDecisions 2/3で固定)。
2. `CONDITIONAL` 制約の文言の見せ方 (item keys一覧内の注記 vs You must sectionの箇条書き) は実装時に確定する。

## Relationship / 責務境界

- **#204 (accepted・実装済み)**: payload schema・validatorの正本。本specは表示を派生するだけで、contract自体に触れない。
- **#205 (implemented)**: exchange framing・package構造の所有者。本specはinstruction部のsection構成 (Decision 2/4の改訂) とResponse format要求を変更する。framing規則・typed失敗4種・envelope上限は不変。変更の正本は本specで、spec 205へchange history注記を行う。
- **#329 (implemented)**: 受理外形の認識層。本specのcanonical final formは#329の受理外形の **選択** であり、#329実装の変更を含まない。
- **#330 (implemented)**: partial authoring semanticsの正本。instructionはその表示を改訂しない (v3文言を維持)。
- **#327 (OPEN)**: interview-first UX。本specのinstructionがfinalization時のself-checkとask-before-finalを担い、#327のcanonical example/template表示は本specのdrift防止策に従う。
- **#345 (OPEN)**: evidence記録の正本。本specはそのcontract層の知見をregression fixtureと設計改善へ引き取る。

## Change history

- 2026-09-18: Draft created for #348。#345 evidence ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)) を入力に、AI-facing contractのproduction同期 (runtime derivation)、finalization self-check、canonical final form (fenced json)、one-round-trip invariant、#345 regression fixtureを定義。
