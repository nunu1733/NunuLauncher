---
issue: "#348"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-18
---

# External Agent ExchangeのAI-facing contractをproduction truthと同期し初回Import成功率を上げる

> Status: **draft** (2026-09-18。2nd review指摘対応revision)。Issue #348のspec。依存: #204 (accepted・実装済み), #205 (implemented), #329 (implemented), #330 (implemented), #345 (evidence記録済み: [assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md))。本specがproduction contract (#204)・framing抽出規則 (#205)・normalizer (#329) の **動作を1つも変更しない** ことを前提に、AI-facingなinstruction/example層をproduction truthから派生させ、derivationの構造保証外の規則はbehavior-parity testで同期する。

## Problem

External Agent Exchangeの通常UXは、Launcher ↔ 外部AIのartifact交換を1往復に固定する (Launcher → AI: request package 1回、AI → Launcher: final artifact 1回)。外部AI内での調査・bounded interview・整理方針確認は許容するが、import validation errorをAIへ戻してrepairさせるfeedback loopは通常flowではない。成功の主戦略は **finalize前のcontract adherence** であり、失敗後の回復ではない。

#345のrepresentative evidenceでは、この制約に対して初回成功率に2種類の問題が確認された。

1. **transport layer**: ChatGPT mobile webのmessage-copyが各行末に `\` + 改行を入れ、`-----BEGIN NUNULAUNCHER INTENT-----` 完全行marker一致が壊れた (`FRAMING_MISSING`)。
2. **contract layer**: code-block copyではclean JSONを得られたが、外部AIが `globalPreference.organization` / `grouping` 等のunknown field、小文字enum (`"high"`)、非整数confidence (`0.82`) を生成し、#204 contractに適合しなかった。

現在のexchange package instruction ([ExchangePackageComposer](../../lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt)) は `schemaVersion` の名指しとpartial authoring (#330) の説明を持つが、**許可property一覧・必須性・JSON型・enum値・数値制約を列挙していない**。そのため外部AIは「親切な補足」として未定義fieldを作り、strict validatorがfail-closedで拒否する。AI向けschemaがproduction validatorと独立した手書きテキストである限り、この種の初回失敗は構造的に減らせない。

## Outcome

exchange packageのAI-facing instructionは、production側に一元化されたwire descriptorからcompose時に派生する。property名の集合・enum値・静的制限定数はdescriptorとcodecの **共有source-of-truth** から派生し、driftが構造的に不可能になる。descriptorが表示する必須性・canonical JSON型・cross-field規則の各主張は、production pathのbehavior-parity testが1件ずつpinningし、descriptorとcodec/validatorの乖離はtest失敗として検出される。validatorが所有するcontext依存・意味検証規則 (mobility matrix、`pageAffinity` 範囲、ref規則) は、instructionへ明示的なproseとして現れ、production-enforced parity testが対応を固定する。instructionは未定義propertyの追加禁止・finalize直前のself-check・不足時はAI会話内での質問 (独自値で帳尻合わせをしない) を明示し、#329が受理する外形のうち **canonical authoring form (fenced `json` code block 1個 + candidate 1個)** を要求する。production contract・import経路・失敗分類は一切変わらず、strict validation / fail-closed / zero-writeは維持される。#345の契約不適合出力はregression fixtureとして固定され、representative flowでのfirst-pass import成功がevidence化される。

## Scope

- `IntentWireContract` (新規、internal): intent payloadのwire field descriptor。**構造派生の対象** (property名集合・enum値・静的制限定数・schema version定数) と、**表示+parity同期の対象** (必須性・canonical JSON型・cross-field any-of / 非空配列) を区別して保持する。`IntentCodec` のallow-list集合はdescriptorの名前集合から派生する。decodeのpresence/type判定logic自体は変更しない (2nd review 高1への対応: 保証範囲を正直に分割)。
- `ExchangePackageComposer` instruction部の再構成。descriptor派生のoutput contract section、validator規則 (production-enforced) とauthoring policy (producer側制限) を分離した明示prose、finalization self-check section、不足情報時の挙動指示の追加。
- Response formatの変更: canonical authoring formとして単一fenced `json` block + candidate 1個を要求 (marker行の要求は廃止。**import経路の3 accepted framingは不変**)。
- instructionが参照するauthoring formに揃える、import失敗案内copy (en/ja) の文言更新。失敗分類 (19種)・表示対応は不変。
- `confidence` の0..100制約のnamed constant化 (`ContextExportContract`) と `IntentCodec` allow-listのdescriptor派生化 (挙動不変の内部refactor)。
- Contract test: descriptor↔codec↔instructionの構造派生pinning、descriptor主張 (requiredness/type/any-of) のparity fixture、production-enforced parity matrix、canonical-authoring policy matrix (canonical ⊆ accepted)、#345契約不適合fixture。
- One-round-trip UX invariantの明文化と、失敗案内copyで「許容する回復文」と「禁止するrepair導線」の具体的固定 (本spec)。
- owner specのnormative更新: spec 205 (Decision 2、agent scenario、framing失敗時の再回答案内、validation reject後の再import位置づけ) とspec 329 (Outcome/D-1/Non-goalsのcanonical form語) を、実装PRで必須変更として更新する (AC-9 blocking。詳細はDecision 6)。

## Non-goals

- #204 strict validator / codec / #330 completion / #329 normalizerの **受信側動作** 変更。`ImportNormalizer` は `\` エスケープ行の救済 (semantic repairに接尾する可能性) を含め一切変更しない。`IntentCodec` のdecode logic (presence/type判定を含む) は、allow-listのdescriptor派生化とconfidence定数参照を除き1行も変更しない。descriptor駆動へのdecode全面refactorは行わない (plan「Alternatives rejected」)。
- marker framing抽出規則の変更 (#205所有の抽出規則・typed失敗4種は不変。instructionが要求しなくなるだけ)。
- validation error → AI repair request生成、Launcher内LLM repair loop、post-import multi-round retryの通常UX化。
- provider固有private API / UI automation。
- #327 interview-first UXの実装 (本specはそのfinalization時に使われるAI-facing contractの同期のみ所有。#327のcanonical example/templateは本specのdrift防止策の対象)。
- #345 TalkBack / Switch Access等のaccessibility evidence取得。
- schema version bump (v3のまま。production contractの受理範囲は1つも変わらない — instructionがその **表示** を派生するだけ)。

## Domain language

**AI-facing contract (AI向け契約)**:
exchange package instruction部のうち、外部AIが返答をauthoringするために必要なschema情報 (schema version・許可propertyとそのcanonical JSON型・必須性・enum値・数値制約・ref規則・mobility規則・partial authoring semantics) の総称。独立したschemaではなく、production codec/validatorの派生表示 (_view_) である。
_Avoid_: AI schema (productionと別の正本を想起させる)、prompt template (framing規則やCONTEXT data部と混同する)

**Wire descriptor (wire記述子)**:
intent payloadのfieldを `名前 / canonical JSON型 / 必須性 / enum・制限` で記述するproduction内部のdata表現 (`IntentWireContract`)。property名集合・enum値・静的制限は #204 codecと共有するsource-of-truthであり、必須性・canonical型・cross-field規則はparity testでproductionと同期される表示主張である。
_Avoid_: schema定義 (#204契約そのものはvalidator/codecの実装が正本。descriptorはその表示層)

**Accepted framing (受理外形)**:
import側が受理する外形の閉集合 (#205 marker形式 / #329 fenced `json` block / standalone JSON)。所有は #205/#329 であり本specでは変更しない。
_Avoid_: canonical (authoring側の要求と混同する呼称)

**Canonical authoring form / representation (正本authoring形式 / 正本authoring表現)**:
producer要求として外部AIに要求する単一の外形 (「単一のfenced `json` code block内にJSON object 1個、それ以外のcode block/candidateなし」) および、instructionが要求するfield表現 (canonical JSON型・大文字enum・整数confidence等)。productionが受理する集合より狭い **producer-side policy** であり、`canonical authoring ⊆ production accepted` をparity testが保証する。本spec (#348) が所有する。
_Avoid_: 推奨形式 (複数候補を認める呼称)、canonical form 単独使用 (受理外形と混同する)

**Authoring policy matrix (authoring policy matrix)**:
productionが受理するがinstructionが禁止する表現 (例: 空の `desiredGroup`、FIXED itemへの `preserve: false`) の一覧。oracleは「instructionが禁止すること」と「canonical fixtureが必ずproductionで受理されること」。
_Avoid_: parity matrix (production-enforced規則の検証と混同しないよう名前を分ける)

## Production truth inventory (同期対象とその所有の正本・同期方式)

| 真実 | production正本 | 同期方式 |
|---|---|---|
| `personalization-context-v3` / `personalized-intent-v3` | `ContextExportContract.SCHEMA_VERSION` / `INTENT_SCHEMA_VERSION` | 構造派生 (descriptor) |
| top-level / item / global / semantic property名の集合 | `IntentWireContract` 名前集合 (新設。`IntentCodec.ALLOWED_*_KEYS` はここから派生) | 構造派生 (descriptor) |
| enum値 (`Importance`: HIGH/NORMAL/LOW、`ExportRegionKind`: TOP/MIDDLE/BOTTOM) | `Importance.entries` / `ExportRegionKind.entries` | 構造派生 (descriptor) |
| confidence 整数 0–100 | `ContextExportContract.CONFIDENCE_MIN/MAX` (新設。現literal重複を解消) | 構造派生 (descriptor) |
| `freeText` ≤100字 / `rationale` ≤500字 / entries ≤512 / unresolved ≤512 | `ContextExportContract` 既存定数 | 構造派生 (descriptor) |
| 必須性 (`schemaVersion` / `exportId` / item `ref` / `groupSemantic` any-of) | `IntentCodec` / `IntentModels` のdecode/init判定 | descriptor表示 + parity fixture (descriptorの主張1件ごとにproduction pathのfixtureで固定) |
| canonical JSON型 (string / boolean / integer / array / object) | `IntentCodec` のdecode判定 | descriptor表示 + parity fixture (canonical表現は受理・違反は記録したtyped failure) |
| payload 128KiB / envelope 1MiB | `MAX_INTENT_BYTES` / `MAX_EXCHANGE_IMPORT_BYTES` | spec同期対象 (resource guard。instructionへは載せない — authoring指示ではなく受信側資源境界) |
| `pageAffinity ∈ [0, gridContext.pageCount)` | `IntentValidator` (export grid依存のcontext制約) | 明示prose + production-enforced parity test |
| mobility規則のproduction-enforced部分 (FIXED → semantic 5 field禁止 / CONDITIONAL → `desiredGroup`,`groupSemantic` 禁止 / CANDIDATE → `preserve` 禁止) | `IntentValidator` | 明示prose + production-enforced parity test |
| mobility規則のauthoring policy部分 (FIXEDには `preserve: true` またはunresolved/omissionを使う。`preserve: false` は受理されるが無意味) | 該当なし (producer-side policy) | authoring policy matrix (instructionが禁止/非推奨にすること + canonical fixtureの受理) |
| ref規則 (全ref-bearing fieldでCONTEXT内refのみ / 同一refは合計1回 / 分割違反) | `IntentValidator` | 明示prose + production-enforced parity test |
| decoderの互換受容 (非string primitiveのstring化、空 `desiredGroup` → absent正規化等) | `IntentCodec` 実装 | authoring policy matrix (canonical authoringでは禁止。production受理は許容) |

上表のとおり、本specが「driftが構造的に不可能」とするのは **構造派生の行 (名前集合・enum・静的定数)** についてである。descriptor表示 + parity fixtureの行は、descriptor主張とproduction挙動の乖離がtest失敗として検出される同期であり、構造保証ではない。context依存・意味検証規則はproduction-enforced parity testによる同期である。decoderは互換性のためcanonical authoring表現より広い入力を受理し得るが、instructionが要求するのはcanonical authoring representationのみであり、`canonical authoring ⊆ production accepted` をpolicy matrix testが保証する。

## Behavior scenarios

### Scenario: instruction部のoutput contractはwire descriptorから派生する

Given `IntentWireContract` が各group (top-level / item entry / `globalPreference` / `groupSemantic`) のproperty名集合、enum参照、静的制限定数、および必須性・canonical JSON型・cross-field規則の主張 (`schemaVersion`: string, required, 値は `personalized-intent-v3` / `exportId`: string, required / `itemIntents`: object array, optional / `unresolvedRefs`: string array, optional / `globalPreference`: object, optional / `rationale`: string, optional, ≤500字 / `confidence`: integer, optional, 0–100 / item: `ref` string required、`importance` enum HIGH|NORMAL|LOW、`desiredGroup` string array optional・存在すれば非空 (policy)、`groupSemantic` object optional・`category` または `freeText` のany-of、`pageAffinity` integer optional・0..`gridContext.pageCount`−1、`regionAffinity` enum TOP|MIDDLE|BOTTOM、`preserve` boolean optional / `globalPreference`: `minimizeMovement` boolean optional / `groupSemantic`: `category` string optional、`freeText` string optional ≤100字) を保持し、
When exchange packageをcomposeする、
Then instruction部のoutput contract sectionは上記の名前・canonical JSON型・必須性・enum値・制約を **compose時にdescriptorから直接整形して** 含み、
And `IntentCodec` の `ALLOWED_TOP_KEYS` / `ALLOWED_ITEM_KEYS` / `ALLOWED_GLOBAL_KEYS` / `ALLOWED_SEMANTIC_KEYS` はdescriptorの名前集合から派生した既存集合と等しい、
And instruction部はproduction contractに存在しないproperty・enum値・操作 (widget span、座標、DB変更等) を能力として約束しない。

### Scenario: descriptor主張とproduction挙動のparity fixture

Given descriptorの必須性・canonical JSON型・cross-field規則の主張1件ごとに対応するfixture、
When `ExchangeImportPipeline.import` に通す、
Then 主張どおりの挙動が1件ずつ固定される (例: `exportId` 欠落 → `SCHEMA_MISMATCH`。canonical string表現 → 受理。`groupSemantic` 両方null → `SCHEMA_MISMATCH`)、
And descriptorの主張をproductionと矛盾する値へ変更すると該当fixtureが失敗する (descriptor↔production乖離の検出)。

### Scenario: 未定義propertyの追加禁止とfinalize前self-check

Given 外部AIが整理方針を確定するための情報を不足したまま最終応答を組み立てようとしている、
When instruction部を読む、
Then 「output contractに列挙されていないpropertyを追加してはならない (例え補足的・親切な意図でも)」が明示され、
And 「不足情報がある場合はfinalize前にuserへ質問する。判断できないitemは未判断のまま残す (省略または `unresolvedRefs`)。schemaで表現できない概念を独自propertyで表現しない」が明示され、
And 最終応答直前のself-check (advertised schemaVersionのみ・未定義propertyなし・型/enum大文字/数値制約遵守・CONTEXT data外のrefなし・同一refの重複記述なし・未判断itemの推測禁止・最終応答にimport候補1個のみ・余分なcode blockなし) が列挙されている。

### Scenario: validator規則の明示 (production-enforced) とauthoring policyの分離

Given export itemがmobility `MOVABLE` / `CONDITIONAL` / `FIXED` / `CANDIDATE` いずれかを持ち、CONTEXT dataの `gridContext.pageCount` が N である、
When instruction部を読む、Then productionがrejectする規則として「FIXED → `importance` / `pageAffinity` / `regionAffinity` / `desiredGroup` / `groupSemantic` は使用不可」「CONDITIONAL → `desiredGroup` / `groupSemantic` は使用不可」「CANDIDATE → `preserve` は使用不可」「`pageAffinity` は 0 以上 `gridContext.pageCount` − 1 以下の整数」「全ref-bearing field (`itemIntents[].ref`、`desiredGroup` 要素、`unresolvedRefs` 要素) はCONTEXT data内の `ref` のみ」「同一 `ref` は `itemIntents` と `unresolvedRefs` の合計で最大1回」が明示され、
And producer-side policyとして「FIXED itemには `preserve: true` または未判断 (省略 / `unresolvedRefs`) を使う」が明示される (`preserve: false` はproductionで受理されるがinstructionは要求しない)。

### Scenario: canonical authoring form (単一fenced json block) の要求

Given instruction部のResponse format section、
When 外部AIが最終応答を返す、
Then 要求される形式は「 ```json fence 1個のcode block、内部はJSON object 1個のみ。それ以外のcode blockを含めない。candidateは1個のみ。block外の説明文は最小限にする」であり、
And instruction部は `-----BEGIN/END NUNULAUNCHER INTENT-----` marker行を要求しない、
And import経路は従来どおりmarker形式・fenced形式・standalone形式の3 accepted framingをすべて同一のtyped挙動で受理する (既存 `ImportNormalizerTest` / `ExchangeImportPipelineTest` 回帰は無変更で成功)。

> 根拠 (#345実測): ChatGPT mobile webのcode-block copyはfence内のJSON本体をcleanにclipboardへ載せる。fence形式は (a) 全文copyでfenced path、(b) code-block copyでstandalone path、の両方で#329が受理する外形に帰着する確率がmarker形式より高い。fence内部がJSONのみであることはfenced pathのinfo string `json` 判定と、code-block copyでのstandalone path成立の双方に効く。

### Scenario: instruction準拠のcanonical payloadはproduction pipelineを通る

Given exchange packageのinstructionに従い、descriptorのcanonical authoring表現どおり (許可keyのみ・canonical JSON型・大文字enum・整数confidence・CONTEXT data内refのみ) に構成したgolden intent JSONを単一fenced blockに置く、
When `ExchangeImportPipeline.import` (envelope → normalizer → framing → decode → session binding → validator → completion) に実export sessionと同一のcanonical structural inputsとともに通す、
Then 成功し、#330 completion後のcanonical表現が得られる。

### Scenario: production-enforced parity matrix

Given productionがrejectする規則を違反するfixture群、
When `ExchangeImportPipeline.import` に通す、Then 各fixtureの期待typed failureは **1種類に固定** され、次の対応が成り立つ:
- 必須欠落: `schemaVersion` なし → `SCHEMA_MISMATCH` / `exportId` なし → `SCHEMA_MISMATCH` / item `ref` なし → `SCHEMA_MISMATCH`
- canonical JSON型違反: `confidence` 非整数 (例: `"high"`) → `INVALID_ENUM` / `preserve` 非boolean → `SCHEMA_MISMATCH` / `itemIntents` 非配列 → `SCHEMA_MISMATCH`
- enum違反: 小文字 `"high"` → `INVALID_ENUM` / `regionAffinity` 範囲外値 → `INVALID_ENUM`
- 数値境界: `confidence` 0と100 → 成功、−1と101 → `INVALID_ENUM` / `pageAffinity` `pageCount−1` → 成功、`pageCount` → `INVALID_ENUM`
- cross-field: `groupSemantic` の `category` と `freeText` が両方null → `SCHEMA_MISMATCH`
- ref規則: item `ref` / `desiredGroup` 要素 / `unresolvedRefs` 要素のそれぞれの未知ref → `UNKNOWN_REF`、同一refの `itemIntents` 重複 → `DUPLICATE_REF`、両集合出現 → `INCOMPLETE_COVERAGE`
- mobility production-enforced: FIXEDへの `importance` / `pageAffinity` / `regionAffinity` / `desiredGroup` / `groupSemantic` → `MOBILITY_CONTRADICTION`、CONDITIONALへの `desiredGroup` / `groupSemantic` → `MOBILITY_CONTRADICTION`、CANDIDATEへの `preserve` → `MOBILITY_CONTRADICTION`、各合法組み合わせ → 成功
And これらのtestはproduction path (`ExchangeImportPipeline.import`) のみを通り、ad-hocなcodec直呼びで本番seamを模倣しない。

### Scenario: authoring policy matrix (canonical ⊆ accepted)

Given productionが受理するがcanonical authoringが禁止する表現 (空配列 `desiredGroup`、FIXED itemへの `preserve: false`、非string primitiveのstring field (例: `"ref": 3`)、`groupSemantic` への数値等)、
When instruction部を検査し、canonical fixtureを `ExchangeImportPipeline.import` に通す、
Then instructionはcanonical authoring表現 (properなJSON型・列挙値) のみを要求し、policy違反表現を要求・例示しない、
And canonical fixture群は **すべて** productionで受理され (`canonical authoring ⊆ production accepted`)、policy matrixの各行がinstructionの該当文と対応する。

### Scenario: #345契約不適合出力のtyped failure固定 (regression fixture)

Given #345で実測された契約不適合出力をfixture化した入力 (top-levelに `globalPreference.organization` を追加したもの、`grouping` をtop-level/itemに追加したもの、`importance` 小文字 `"high"`、`confidence: 0.82`、および契約適合のcanonical payload)、
When 同一pipeline seamに通す、
Then それぞれ未知propertyは `SCHEMA_MISMATCH`、小文字enumと非整数confidenceは `INVALID_ENUM` でfail-closed rejectされ、canonical payloadのみ成功する、
And normalizer・validatorの実装はこの検証のために1行も変更されない。

### Scenario: one-round-trip invariant (許容する回復文と禁止するrepair導線の固定)

Given import失敗時に表示されるtyped失敗案内copy (en/ja) とexchange package instruction部、
When canonical authoring formへの移行後に表示文言を読む、
Then **許容される回復文** は「最終JSONの再copy・再貼付、またはAIへの最終JSON再送依頼」に限られ (fenced json block主体の表現へ更新)、**禁止される導線** である「validation failureの内容・failure diagnosticsをAIへcopyしてrepairさせる指示」「自動生成されたrepair requestの提示」はpackage instruction・案内copyのいずれにも現れない (この不在を、対象4 strings + instruction/footerについて固定の否定assertionでtestする)、
And 失敗分類19種とその表示対応は不変である。

## Decisions

1. **同期方式: 構造派生 + descriptor表示のparity fixture + parity/policy matrix (4層、保証範囲を明示)**。比較: (a) 同一source-of-truthからの生成 — property名集合・enum値・静的定数に適用。`IntentWireContract` descriptorを一元化し、`IntentCodec` のallow-listとcomposerのoutput contractが同一descriptorから派生する。この範囲のdriftは構造的に不可能。(b) build時metadata生成 — 却下。build stepと二次表現だけが増える。(c) 手書きprose + contract testのみ — 単独では採用しない。prose pinningとparity/policy testとして補助採用。**必須性・canonical JSON型・cross-field規則はdecode logicのdescriptor駆動refactorをせず**、descriptorの表示主張 + production pathのparity fixture (2nd review 高1への対応。選択肢(B)) で同期する。保証範囲はProduction truth inventory表のとおり正直に分類し、構造保証を超える主張をしない。**production validatorと別の独自AI schemaは作らない。**
2. **canonical authoring form: 単一fenced `json` block + candidate 1個**。#345実測の2 copy affordance (message copy / code-block copy) の双方でaccepted framingに帰着しやすいのはfence形式。marker形式はaccepted framingのまま維持し (既存返答の互換、優先度1の抽出規則も不変)、instructionからの要求のみ撤去する。canonical authoring representationはproduction受理集合より狭いproducer-side policyであり、`canonical authoring ⊆ production accepted` をpolicy matrix testが保証する (2nd review 中2対応)。UI失敗案内copyはこのauthoring要求に揃える。#329 normalizerの拡張はしない (message-copyの `\` エスケープ問題はcanonical authoring form選択により **発生源头で緩和** され、受信側の救済はsemantic repairに接尾するため行わない。残余リスクはtyped failureでfail-closed)。
3. **instruction部のsection構成** (spec 205 Decision 2の改訂。normative本文も実装PRで更新): Goal / You may / Output contract (新設、descriptor派生) / You must (production-enforced mobility規則・page範囲・ref規則・partial authoring規則 + FIXEDのauthoring policy) / Before sending your final answer (self-check、新設) / Response format (canonical authoring form)。英文final proseの微調整は実装reviewとrepresentative evidenceで行う (構造・必須要素は本specで固定)。
4. **one-round-trip UX invariant**: Launcher → AI request 1回、AI → Launcher final artifact 1回。AI内での2〜4問程度のbounded interview・Web検索・方針確認は許容。import後のAI repair loopは通常flowに含めない。失敗はtyped表示でfail-closedし、成功戦略はfinalize前のcontract adherenceに置く。#205のframing失敗時の「再依頼」案内は回復手段の説明として許容するが、repair手順の標準化・diagnosticsのAIへの持ち込み指示は追加しない (許容/禁止の具体範囲はBehavior scenario「one-round-trip invariant」で固定)。
5. **#345 regression fixtureの所有**: 本spec (#348) がcontract不適合出力のfixture/評価caseを単unit testとして保持する。transport層の `\` エスケープ実測例は#345 evidence記録 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)) が正本とし、unit fixtureにはしない (normalizerを変更しないため、壊れたmarker行の救済fixtureは恒久的な期待値にならない)。
6. **owner specのnormative更新 — 実装PRで必須実施 (AC-9 blocking)** (1st review 高3 + 2nd review 中3対応)。Phase 1 accepted時点ではspec集合は自己整合しないことを明示的に許容し、**本specの実装PRで** spec 205とspec 329のnormative本文を更新する (更新なしに実装PRは完了しない)。対象: spec 205はDecision 2 (section構成)、「外部agentの自由な調査」scenario (marker要求文)、「importの厳格な抽出」scenarioのframing失敗時再回答案内 (canonical authoring formに整合)、「validation reject」scenario (再importは例外的回復であり通常flowにrepair loopを設計しない旨)、AC-1/AC-4のmarker要求参照。spec 329はOutcome/D-1/Non-goalsに残る `canonical form` 語と「AIがmarker形式を引き続き要求する」前提を「accepted framing (受理外形) は不変。producer向け要求 (canonical authoring form) はspec 348が所有」へ分離更新。双方にchange history (spec 348所有) を追加する。用語は「accepted framing」と「canonical authoring form」に分離し、二重意味を避ける。実装 (framing抽出・normalizer・typed失敗) は双方とも1行も変更しない。
7. **evidence data policy (2nd review 中4対応)**: AC-11のevidence取得はprivacy境界を守る。blocking evidence (最低1本) は **synthetic/test profile または管理されたfixture context** で実施する。repositoryへcommitするartifactはsanitized版のみとし、実ユーザー由来の `EXTERNAL_WITH_LABELS` package / raw AI responseはcommitしない。redacted tierのpackageでもusage bucket・layout構成等のmetadataを含むため、repoへ置く場合は必要最小限へscrubするか、raw artifactは保存せずSHA-256・size・取得条件のみを記録して監査性を確保する。本workflow (#205) 自体の「package本文・返答textをLauncher内で永続化しない」privacy姿勢がevidence記録によって迂回されないこと規約とする。

## Data and state

- 読むdata: production symbol (`ContextExportContract`、`IntentWireContract`、`IntentCodec`、`Importance`、`ExportRegionKind`) とexport JSON (composerは従来どおり解釈しない。output contractはper-export情報 — `exportId`、ref、`gridContext.pageCount` — を埋め込まず、CONTEXT dataそのものを参照させる)。
- 永続化: なし (packageは生成時完了のimmutable値。現行どおり)。evidence記録のartifact保存はDecision 7のpolicyに従う。
- migration / backup / rollback: 影響なし (DB書込経路なし。zero-write境界は不変)。
- 変更するstrings: `exchange_failure_framing_missing`、`exchange_failure_framing_empty`、`exchange_import_retry_hint`、`exchange_failure_normalization_unrecognized` (en + ja)。失敗key・表示対応・19種分類は不変。

## Acceptance criteria

- [ ] AC-1: one-round-trip UX invariant (request 1回・final artifact 1回・AI内bounded interview許容・import後AI repair loop不採用) が本specに明文化され、許容する回復文と禁止するrepair導線がBehavior scenarioどおり具体化され、package instruction/footerと対象4 stringsについて否定assertionがtestされる。
- [ ] AC-2: `IntentWireContract` が構造派生対象 (property名集合・enum参照・静的制限定数・schema version定数) と表示主張対象 (必須性・canonical JSON型・cross-field規則) を区別して一元化し、(a) `IntentCodec` のallow-list集合がdescriptorの名前集合から派生して既存decode挙動と等しいこと、(b) instructionのoutput contract sectionがdescriptorから整形されていること、をproduction symbolを参照するunit testが機械検証し、(c) descriptorの表示主張1件ごとにproduction pathのparity fixtureが対応すること (期待値をtest側へliteral複写しない。descriptor主張をproductionと矛盾する値へ変えるとfixtureが失敗することを含む)。
- [ ] AC-3: instructionがproduction-enforced規則 (mobility規則のproduction部分・`pageAffinity` 範囲・全ref-bearing fieldのref規則・entries上限) とFIXEDのauthoring policyを分離して明示し、production-enforced parity matrix (fixtureごとに期待typed failure 1種類固定、`ExchangeImportPipeline.import` 経由、table-driven) とauthoring policy matrix (instructionがpolicy違反表現を要求しないこと + canonical fixtureがすべてproduction受理 = `canonical authoring ⊆ production accepted`) の両方がtestされる。
- [ ] AC-4: instructionに準拠して構成したgolden canonical payloadが単一fenced block経由で `ExchangeImportPipeline.import` を通り、session束縛・validate・completion後のcanonical表現まで到達する (contract test)。
- [ ] AC-5: finalization self-check (advertised schemaVersionのみ・未定義propertyなし・型/enum/数値制約・ref範囲・重複禁止・未判断itemの推測禁止・candidate 1個・余分なcode blockなし) がinstructionに存在し、testでpinningされる。
- [ ] AC-6: 不足情報時はfinalize前の質問またはaccepted unresolved/omission semanticsを使う旨がinstructionに明示され、独自property・独自値での帳尻合わせが禁止されている (test)。
- [ ] AC-7: Response formatがcanonical authoring form (単一fenced `json` block + candidate 1個) を要求し、marker行を要求しない。既存の3 accepted framingのimport挙動は既存test群が無変更で成功する (regression)。
- [ ] AC-8: #345実測の契約不適合出力 (unknown `globalPreference.organization`、unknown `grouping`、小文字enum、非整数confidence) がregression fixtureとして記録され、各typed failure (`SCHEMA_MISMATCH` / `INVALID_ENUM`) でfail-closed rejectされることがtestされる。canonical payloadは成功する。
- [ ] AC-9: import失敗案内copy (en/ja) がcanonical authoring formに揃い、失敗分類19種・表示対応が不変であることがtestされる (既存instrumentation testはresource ID参照のため文言変更の影響を受けない)。**spec 205 (Decision 2・agent scenario・framing失敗時の再回答案内・validation reject後の再import位置づけ・AC記述) とspec 329 (Outcome/D-1/Non-goalsの受理外形とproducer要求の分離) のnormative更新が、本spec所有のchange history記録とともに実装PRで完了すること** (実装PRの完了条件。更新前は実装PRをmergeしない)。
- [ ] AC-10: strict validation / fail-closed / zero-writeが維持される。本specの変更で `IntentValidator` / `IntentCodec` (allow-list descriptor派生化とconfidence定数化を除く。decodeのpresence/type判定logicを含む) / `ImportNormalizer` / framing抽出の挙動が変わらないことが既存test群の無変更成功で確認される。
- [ ] AC-11: representativeなExternal Agent flowのfirst-pass import成功 (AIへのrepair依頼なし) がevidence化される。evidence protocolとして次を記録する: head SHA / context・intent schemaVersion / privacy tier / provider・surface・model表示・日付 / request package (SHA-256・size等の指紋。rawはDecision 7に従う) / AI内interview有無 / final response (同上) / copy affordance / import transport / 試行回数 (first attemptか)。**試行した全attemptを記録し、成功例のみを選別しない。** 最低1本のblocking evidenceはsynthetic/test profileまたは管理されたfixture contextで、#345と同一条件系 (ChatGPT mobile web系surface) の「fenced response → code-block copy (standalone path) または全文copy (fenced path) → first import成功」を固定する。repoへcommitするartifactはsanitized版のみ (Decision 7)。Gemini等の追加surfaceはnon-blocking。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | spec本文 (許容/禁止の具体固定) + instruction/footer + 対象4 stringsの否定assertion |
| AC-2 | `Issue348` contract test: descriptor↔codec allow-list一致 + composer出力のdescriptor派生containment + descriptor表示主張のparity fixture (主張変更で失敗することを含む) |
| AC-3 | `Issue348` test: production-enforced parity matrix (期待failure 1種固定) + authoring policy matrix (canonical ⊆ accepted) |
| AC-4 | `Issue348` golden test: canonical fenced payload → pipeline.import → completion (実session + structural inputs) |
| AC-5/AC-6 | contract test (self-check / ask-before-final / 独自property禁止のpinning) |
| AC-7 | `ImportNormalizerTest` / `ExchangeImportPipelineTest` / `IntentImportParserTest` 無変更成功 + composer test (marker要求の不在・fence要求の存在) |
| AC-8 | `Issue348` regression test (#345 fixture → typed failure固定) |
| AC-9 | strings更新 + `ExchangeImportSurfaceInstrumentationTest` 成功 + spec 205/329のdiff (実装PR内) |
| AC-10 | 既存全unit test + `spotlessCheck` + assemble |
| AC-11 | device evidence記録 (docs/assessment/ 配下。protocol + Decision 7 privacy policyを満たす) |

含めるべき観点: unit/contract (上記)、UI/accessibility (AC-9)、failure injection (AC-8 + parity matrix)、property/performance/DB (本変更の対象外 — 該当経路に触れないことをAC-10の既存test群で確認)。

## Open questions (non-blocking)

1. instruction英文の最終prose (列挙順・言い回し) は実装reviewとAC-11 evidenceのagent遵守率で調整する (構造・必須要素はDecisions 2/3で固定)。
2. `CONDITIONAL` 制約の文言の見せ方 (item keys一覧内の注記 vs You must sectionの箇条書き) は実装時に確定する。
3. entries上限 (512) をoutput contract sectionに載せる文言の簡潔さ — 載せることはAC-2/AC-3で確定済み。表現は実装時に調整。

## Relationship / 責務境界

- **#204 (accepted・実装済み)**: payload schema・validatorの正本。本specは表示を派生し、descriptorを内部導入するが、受理範囲・decode判定logic・validator挙動に触れない。
- **#205 (implemented)**: exchange framing・package構造の所有者。本specはinstruction部のsection構成とResponse format要求 (canonical authoring form) を **normative更新ごと** 変更する (Decision 6: 実装PRで実施)。framing規則・typed失敗4種・envelope上限・3 accepted framingの受理は不変。
- **#329 (implemented)**: 受理外形の認識層。本specのcanonical authoring formはaccepted framingの **選択** であり、#329実装の変更を含まない。spec 329の用語・前提文のみ実装PRで更新する (Decision 6)。
- **#330 (implemented)**: partial authoring semanticsの正本。instructionはその表示を改訂しない (v3文言を維持)。
- **#327 (OPEN)**: interview-first UX。本specのinstructionがfinalization時のself-checkとask-before-finalを担い、#327のcanonical example/template表示は本specのdrift防止策に従う。
- **#345 (OPEN)**: evidence記録の正本。本specはそのcontract層の知見をregression fixtureと設計改善へ引き取る。

## Change history

- 2026-09-18: Draft created for #348。#345 evidence ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)) を入力に、AI-facing contractのproduction同期、finalization self-check、canonical authoring form (fenced json)、one-round-trip invariant、#345 regression fixtureを定義。
- 2026-09-18 (2nd): 1st ChatGPT review ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/348#issuecomment-5717646827)、head `a7cbcb84be` 基準) の高3点・中2点に対応。**高1**: requiredness/JSON型の同期が未設計 → `IntentWireContract` descriptor導入とrepresentation分離。**高2**: validator固有truthを同期対象へ追加し、保証範囲を正確化、production truth inventory表を追加。**高3**: spec 205/329のnormative更新をDecision 6として追加、accepted framing / canonical authoring formの用語分離。**中4**: parity matrix (table-driven、production path経由) をAC-3へ、golden testを `ExchangeImportPipeline.import` 経由へ。**中5**: AC-11へevidence protocolを追加。
- 2026-09-18 (3rd): 2nd ChatGPT review ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/348#issuecomment-5717882626)、head `e83aa3e909` 基準、**Request changes**) に対応。**高1**: descriptorがrequired/typeまでproduction正本でない問題 → Decision 1を「構造派生 (名前集合・enum・静的定数) / descriptor表示 + parity fixture (requiredness・型・any-of) / production-enforced parity / authoring policy」の4層へ再定義し、decode logicのdescriptor駆動refactorを却下 (保証主張を正直に縮小)。**中2**: production-enforced parity matrix (期待failure 1種固定) とauthoring policy matrix (`canonical ⊆ accepted`) へ分離。FIXEDの `preserve: false`・空 `desiredGroup`・非string primitive受容をpolicy側へ移動。**中3**: spec 205/329のnormative更新を「実装PR必須 (AC-9 blocking)」へ固定し、spec 205のframing失敗時再回答案内・validation reject後位置づけを更新対象へ追加。**中4**: AC-11へevidence data policy (Decision 7: synthetic profile・sanitized artifact・raw commit禁止・指紋記録) を追加。
