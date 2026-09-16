---
issue: "#329"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-16
---

# External Agent ExchangeのImport Normalizer (外部AI出力の安全な揺らぎ吸収)

> Status: **draft** (2026-09-16 起草、baseline `aab0d293d1` 時点のmain実装を確認済み)。後述のDecisions (D-1〜D-8) は起草時点の推奨固定案であり、owner reviewで比較・確定する。特にD-3 (fence block総数による曖昧性判定) と新typed failure 2種の命名は、reviewでの確定を前提としたdraft proposalである。

## Problem

現行External Agent Exchangeのimport ([spec 205](../205-external-agent-exchange/spec.md)) は、完全行marker `-----BEGIN/END NUNULAUNCHER INTENT-----` で囲まれた `PersonalizedIntentV1` のみを厳格に受理する (fail-closed)。この安全境界は維持する必要があるが、実際のChatGPT / Gemini等のconsumer UIからユーザーがコピーする内容には、以下のような **意味には影響しない無害な揺らぎ** が入りやすい。

- AIがmarkerを付けず、JSON本体だけを返した / ユーザーがJSON部分だけコピーした (standalone JSON)
- AIがmarkdownの ```json code fence で囲んで返した
- 短い説明文 + 単一の ```json code block (AIが説明を添えるのは一般的)
- BOM / CRLF / 前後空白 (clipboard・file経路のtransport揺らぎ)

現行実装ではこれらはすべて `FRAMING_MISSING` でrejectされ、ユーザーはスマホ上で手動整形するか、AIにmarker付きで再送信し直す必要がある。これではExchange workflow自体が実用困難になる。

一方で、寛容化の方向を誤ると「文章中のJSONらしき部分を拾ってpartial適用する」危険な経路が復活する。#205が明示的に禁止した fuzzy extraction (複数候補からの推測選択、`{...}` の任意拾い、partial解釈) は許容できない。

## Outcome

#204 strict validatorの手前に、**意味を推測せず、認識可能な外形だけを安全にcanonicalizeする Import Normalizer** を追加する。

```text
External AI output (貼付付け / file import)
      ↓
#205 import envelope上限 (1 MiB)          … 不変 (#205 Decision 6)
      ↓
Import Normalizer (#329新設)              … 外形認識とframing/transport正規化のみ
      ↓
canonical PersonalizedIntentV1 payload text (verbatim)
      ↓
#205 exchange framing規則 (marker形式)    … 不変 (canonical form)
      ↓
#204 strict codec / validator             … 不変 (schema・allow-list・semantic検証)
      ↓
accepted (既存run接続) or zero-write reject
```

入力には一定の寛容性を持たせつつ、domain contract / allow-list / semantic validationは従来どおり厳格に維持する。NormalizerはJSONの内容を一切解釈・変更せず、認識した領域をそのまま (verbatim) 次段へ渡す。

## Scope

- Import textに対する **accepted framing (受理外形) の明示列挙** と、各framingのcanonicalization結果の固定 (D-1〜D-4)。
- Import Normalizer段階のtyped failure (認識不能・曖昧) の定義と、#205 framing失敗・#204 validation失敗との型レベルの区別 (D-5)。
- Normalization boundaryの固定: normalizerが変更できるのはframing / transport表現のみであり、Intentの意味は変更しない (D-6)。
- 貼付付け・clipboard・fileの全import経路への同一適用 (#205 envelope上限と同じ全経路原則)。
- Normalizer失敗時のユーザー向け案内 (認識可能な形式の提示と再コピー方法)。
- Diagnostics / 永続化におけるraw AI response取り扱いpolicy (D-7: 無制限保存禁止)。
- 寛容化によってprompt injection / partial applyが復活しないことのsecurity要件とregression coverage (D-8)。

## Non-goals

- #204 semantic validatorの緩和。schema・allow-list・coverage検証は一切変更しない。
- **意味レベルのcanonicalization**。未言及refの `unresolved` 扱い、FIXED itemのauthoring責任軽減、partial authoring schema (V2) は #330 (authoring contract簡素化) の対象であり、本specはframing/transport表現のみを扱う。normalizerはfield値・ref集合・schemaVersionを決して書き換えないため、#330の成果を本specで先取り・兼用しない。
- arbitrary free textからのIntent推論、AI responseの自動修復LLM呼び出し、invalid Intentのpartial apply (Issue本文のnon-goals)。
- provider (ChatGPT/Gemini) 固有formatへの密結合。認識する外形はprovider中立なtext構造のみ。
- import入力の取得UX (clipboard読込・file選択・bounded editor・parse結果中心表示) は #332 の対象。本specのnormalizerはpure parser責務であり、UI・入力取得を持たない。
- exchange packageのinstruction部 (AIへの要求形式) の変更。prompt/interview設計は #327、import成功後の状態表示は #328 の対象。本specはAIが **従来どおりのmarker形式を返すことを引き続き要求する前提** のまま、marker以外で戻ってきた場合の揺らぎのみ吸収する。
- markdown全体 (CommonMark) の実装。fence認識は簡易決定性grammarに限定する (D-4)。
- #205のexchange framing規則 (marker形式の抽出規則・typed失敗3種) の変更。marker形式はcanonical formとして現行規則のまま残る。

## 現行実装の確認事実 (baseline `aab0d293d1`)

- `IntentImportParser.parse` (`organizer/personalization/exchange/IntentImportParser.kt`): 1 MiB envelope検査 → 先頭BOM除去・CRLF/CR→LF正規化 → 完全行marker抽出。typed失敗は `INPUT_OVERSIZE` / `FRAMING_MISSING` / `FRAMING_AMBIGUOUS` / `FRAMING_EMPTY` の4種。
- `ExchangeImportPipeline.prepare` : framing抽出 → #204 `IntentCodec.decode`。`validate`: session照会・expiry・structural digest・`SessionExportReconstructor` → `IntentValidator.validate` (#331のscope拡張を含む)。
- UI失敗表示 (`ExchangeFlowUi.exchangeFailureText`): envelope 4種 + #204 contract 13種 = **17種** の一対対応 (ja/en strings解決済み)。
- UI受領時 (`onImportTextChange`) とfile読込 (`ExchangeTransports.read` のbounded read) でも同一の1 MiB envelope上限を適用済み。
- exchange経路はdiagnostics journalへ何も記録しない (run journalはorganization run/recovery操作のみ。[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 契約どおり)。

## Behavior scenarios

### Scenario: 完全行marker形式は現行どおり (regression)

Given 外部agentの返答が #205 のexchange framing (完全行marker `-----BEGIN NUNULAUNCHER INTENT-----` / `-----END NUNULAUNCHER INTENT-----`) に従う、
When 取り込みを実行する、
Then 現行の #205 framing規則が **変更なく** 適用され、marker対前後の自由文は許容され、領域内はverbatim抽出されて #204 codec/validatorへ渡る (既存test corpusはすべて不変のまま通る)。

### Scenario: standalone JSON object の取り込み

Given 返答text全体が (先頭BOM・CRLF正規化後、先頭末尾の空白のみ除去して) 単一のJSON valueとしてstrict parseに成功し、かつrootがJSON objectである (marker行もfenced code blockも含まない)、
When 取り込みを実行する、
Then そのobject textが **trim以外そのまま** payloadとして認識され、#204 codec/validatorへ渡る (再serialize・空白整形・key順変更は行わない)、
And JSONとしてparseできない文章 (説明文に埋まった `{...}` を含むtext等) は、このframingでは **決して** 受理されない (全体が単一JSON objectであることだけが受理条件のため、部分拾いが構造的に不可能)。

### Scenario: 説明文 + 単一の ```json code block

Given 返答textが説明文を含み、backtick fence (```) で囲まれたcode blockが **全体でちょうど1つ** 存在し、そのinfo string (``` の直後の文字列、前後空白除去・case-insensitive) が `json` である、
When 取り込みを実行する、
Then そのblock内部 (fence行で挟まれた領域、外側空白のみtrim) がpayloadとして認識され、#204 codec/validatorへ渡る、
And fenceの前後の説明文は許容され、payload領域外として破棄される (解釈も保存もしない)。

### Scenario: 複数code block・曖昧な入力は推測せずreject

Given 返答textにfenced code blockが **2つ以上** 存在する (両方json tag付き、json + 非json tag混在のいずれでも)、
When 取り込みを実行する、
Then 複数候補から推測選択せず、normalizer段階のtyped失敗 (曖昧) としてzero-write rejectし、「最終的なJSON 1つだけをコピー/再送する」案内を表示する。

> 比較: 「json tag付きblockだけを数え、jsonが1つなら他の非json blockがあっても受理する」案もある (D-3)。起草時点では、候補総数の一意性をblock種別によらず保証する **総数厳格案** を推奨する (「複数JSON候補からの推測選択禁止」の精神に最も近く、認識grammarも単純)。実worldのコピー実態は #332 evidenceで把握した上でV2で再検討できる。

### Scenario: 認識可能な外形がない入力はreject

Given 返答textが (a) marker行を含まず、(b) fenced code blockを含まず (またはinfo stringなしのfence 1つのみ)、(c) 全体が単一JSON objectでもない (例: 説明文中にinlineの `{...}` が現れるだけ、 fenced block 1つだがtagが `json` でない)、
When 取り込みを実行する、
Then normalizer段階のtyped失敗 (認識不能) としてzero-write rejectし、認識可能な形式 (marker囲み / ```json block 1つ / JSON全体) を短く提示して、(1) AIにmarker付きで再送信を依頼する、(2) JSON部分だけをコピーし直す、の再コピー案内を表示する。

### Scenario: nested wrapper の帰結

Given 返答textが入れ子の包みを含む、
When 取り込みを実行する、
Then次の決定的な帰結のみが発生する (再帰的なunwrapは一切行わない):
- marker対の **内側** にfence行がある → marker領域はverbatim抽出され、fence行ごと #204 codecへ渡り `SCHEMA_MISMATCH` でtyped reject (unwrapしない)
- fenceの **内側** にmarker対がある → marker形式が最優先で認識され、外側のfence行はmarker前後の自由文として許容され、marker規則で一意抽出される
- fence blockの **内側** にfence開始行がある (closingは ``` のみの行のため、内側の ```json 等の行ではblockが閉じない) → blockは最初のclosing行まで伸び、内部にfence行を含むpayloadは正当なJSONになり得ないため #204 codecの `SCHEMA_MISMATCH` に収束する。対照的に、入れ子ではなく **それぞれ独立に閉じた** fence blockが2つ存在する場合は「複数block」の曖昧rejectに収束する
And いずれの入れ子も、解釈の分岐・推測を発生させない。

### Scenario: transport正規化 (BOM / CRLF / 前後空白) の冪等性

Given 返答textがUTF-8 BOM付き、CRLF改行、前後空白を含む、
When 取り込みを実行する、
Then 先頭BOM除去・CRLF/CR→LF正規化・認識領域外側のtrimのみが行われた上でframing認識が決定的に行われ (受理経路は #205 marker形式と同一の正規化規則)、同じ内容の入力は常に同じ結果を返す (決定性・冪等性)。正当なJSON string valueは生の改行を含めないため、この正規化が正当なpayloadの意味を変えることはない。

### Scenario: 正規化後は必ず #204 strict validatorを通る

Given いずれのaccepted framingで認識された入力も、
When pipelineが進行する、
Then normalizerの出力はJSON text (object) であり、必ず既存の #204 `IntentCodec.decode` → session検証 → `IntentValidator.validate` を通る。normalizerが検証を代替・省略する経路、normalizerから直接planner/runへ接続する経路は存在しない、
And out-of-scope ID (`UNKNOWN_REF`)、unknown field (`SCHEMA_MISMATCH`)、禁止内容 (`FORBIDDEN_CONTENT`)、invalid enum (`INVALID_ENUM`)、coverage漏れ (`INCOMPLETE_COVERAGE`) は、marker形式と同一にtyped rejectされる (寛容化による検証スキップなし)。

### Scenario: 巨大入力・marker不在巨大入力

Given import text全体 (説明文込み) が1 MiB envelope上限を超える、または上限内だがmarkerもfenceもなく巨大な文章のみである、
When 取り込みを実行する、
Then 前者は従来どおり全量処理の前に `INPUT_OVERSIZE` でzero-write拒否され (全import経路で同一。#205 Decision 6不変)、後者はnormalizerの認識不能typed失敗としてzero-write拒否される。normalizerはenvelope上限を超える入力に対してscan・parseを開始しない (単一pass・boundedな処理のみ)。

### Scenario: typed outcome の区別とユーザー案内

Given importが失敗する、
When 失敗を表示する、
Then 失敗は (1) normalizer失敗 (本spec新設: 曖昧・認識不能)、(2) #205 framing失敗 (marker形式の `FRAMING_*` 3種 + `INPUT_OVERSIZE`)、(3) #204 validation失敗 (13 class) の3層にtypedに区別され、UI失敗表示は全種に一対対応する (17種 → **19種**)、
And normalizer/framing失敗の案内は「取り込める形式が無い」ための再コピー手順 (認識可能形式の短い提示・AIへの再依頼) に、#204 validation失敗の案内は「内容が契約に合わない」ための修正・再依頼に、それぞれ焦点が分かれる。

### Scenario: raw AI responseの保存禁止

Given いずれの経路で取り込みが成功・失敗した場合も、
When diagnostics・log・永続化を確認する、
Then import text (raw AI response)・抽出payload・その断片はdiagnostics journal・logcat・file永続化のいずれにも書き込まれない (現行 #205 / organizer-diagnostics.md 契約不変)。import textはimport flowのprocess memory上のみ (受領時1 MiB上限付き) に存在し、outcome確定後に保持しない、
And 将来normalizer失敗のobservabilityを追加する場合は、closed enum (失敗種別・認識framing種別・入力UTF-8 byte長) のみを許可し、raw textの記録を禁止する。

## Decisions (draft — owner reviewで確定する)

### D-1: accepted framing一覧 (判定順序固定)

全経路で、(0) 1 MiB envelope検査 (不変) → (1) transport正規化 (先頭BOM除去・CRLF/CR→LF) → (2) 以下の順で外形認識:

| 優先順 | framing | 受理条件 | canonicalization |
|---|---|---|---|
| 1 | **完全行marker形式** (canonical, #205規則不変) | INTENT marker行 (完全一致規則) が1つでも存在 | 現行 `IntentImportParser` 規則をそのまま適用 (unique marker対・verbatim領域・`FRAMING_*` 3種)。marker行が存在する限り他の外形判定は行わない (fence行はmarker前後の自由文/領域内payloadとして扱われ、fenceとしては解釈しない) |
| 2 | **単一fenced json block** (前後説明文可) | marker行なし。backtick fenceで囲まれたblockが全体でちょうど1つで、info stringが `json` (trim・case-insensitive) | block内部 (fence行間) を外側空白のみtrimしてpayload化 |
| 3 | **standalone JSON object** | marker行なし・受理対象fenced blockなし (非json tagのfence 1つのみの場合を含む)。正規化後text全体 (先頭末尾空白のみ除去) がstrict JSONとしてparse成功し、rootがobject | 全体をtrimのみしてpayload化 (再serializeしない) |
| - | 上記いずれにも該当しない | - | typed reject (認識不能) |

補足:

- fenced blockが2つ以上 (tag種別に関係なく) → typed reject (曖昧)。理由は「複数code block」scenario参照 (D-3)。
- fence対が閉じていない (closing fence行なし) → blockとして数えない。残余の ``` 行のためstandalone parseも失敗し、認識不能rejectに収束する。
- marker行が1つでも存在する時点で優先順1へ分岐するため、marker破損 (`FRAMING_MISSING` 等) とfence/JSON認識の結果が混在することはない (決定性)。
- 誤ってCONTEXT marker対 (`-----BEGIN/END NUNULAUNCHER CONTEXT-----`) を返した入力はINTENT markerとみなされず、現行どおり拒否される (案内はINTENT markerを指定)。

### D-2: fuzzy extraction禁止の明示列挙

Normalizerは以下を一切行わない (「曖昧ならreject」の実装規約):

- 複数のJSON候補 (複数fence block、marker+fenceの併存でない単一認識不能物) から推測で選ぶ
- 文章中の `{...}` を任意に拾う (standalone JSON受理条件は「全体が単一JSON object」のみ)
- typoを意味推測で修正する / unknown fieldを別fieldへmappingする / invalid semantic valueを近い値へ丸める
- payload内部の文字列を追加・削除・並べ替える (抽出領域はverbatim)

### D-3: fence block数判定は総数厳格 (draft推奨)

json tag付きblockのみ数えて「jsonが1つなら他blockがあっても受理」する案と比較した。起草時点では **fence block総数で判定** (2つ以上ならreject) を推奨する。認識grammarが単純で、候補一意性の保証が明示的になるため。実コピーでの「json 1つ + 補足script block」の発生頻度は #332 / device evidenceで把握後に再検討できる。

### D-4: fence grammarは簡易決定性 (CommonMark全体実装はしない)

- fence行 = 行頭から3連backtick (```) で始まる行 (行頭空白・tilde fence `~~~` はV1対象外)。
- opening fence行のinfo string = ``` 以降の行内text (trim)。closing fence行 = ``` で始まり **残りが空白のみ** の行 (CommonMarkと同じく、closingにinfo stringは許さない。この制約により、内側に現れた ```json 等の行ではblockが閉じず、nested wrapperは妥当なJSONではないpayloadへ帰着する)。
- block = opening fence行と、それ以降最初のclosing fence行に挟まれた領域。独立に閉じたblockが2つ以上あればD-3の複数block rejectへ帰着する。
- info stringの受理は `json` のみ (trim + ASCII case-insensitive。`jsonc` / `json5` 等は非json扱い)。

### D-5: typed failure分類の拡張

- 既存の #205 envelope 4種 (`INPUT_OVERSIZE` / `FRAMING_MISSING` / `FRAMING_AMBIGUOUS` / `FRAMING_EMPTY`) と #204 contract 13種は **名称・意味とも不変**。
- 新設するnormalizer失敗は2種 (名称は実装時に確定してよい):
  1. **曖昧 (ambiguous blocks)**: fenced block 2つ以上。「最終的なJSON 1つだけをコピー/再送」案内。
  2. **認識不能 (unrecognized format)**: 受理外形いずれにも該当しない。認識可能形式の短い提示 + 再コピー案内。
- 正常結果はpayload textに加え、**認識framing種別 (marker / fenced / standalone) をclosed enumで返す** (UI表示・将来のclosed-enum diagnostics・#332のparse状態表示が型で利用できる。raw textは含まない)。
- 失敗表示は17種 → 19種となる。normalizer失敗は #204 codecの前に決着するため、validator失敗との重畳表示は行わない。

### D-6: normalization boundary (semantic無変更の形式的定義)

Normalizerの出力payloadは、入力text (transport正規化後) からの **部分文字列** でなければならない。許される差分は次の4種のみ:

1. 先頭UTF-8 BOMの除去
2. CRLF / CR → LF
3. 認識したframing行 (marker対 / fence対) の除去
4. 認識領域の外側空白trim

field値の意味補正、out-of-scope IDの削除、schema versionの書換え、JSONの再serialize・整形は禁止 (部分文字列制約により構造的に不可能になる。property testで検証する)。

### D-7: diagnostics / 保存policy

- raw AI response (import text)・payload・断片のdiagnostics journal / logcat / 永続化への書込みを禁止 (現行契約不変。exchange import失敗はrun eventではない)。
- import textのprocess memory保持は既存の受領時1 MiB上限に従い、outcome確定後に保持しない。
- 将来的なnormalizer失敗observabilityはclosed enum (失敗種別・framing種別・入力byte長) のみ許可。

### D-8: security要件 (寛容化による復活防止)

- 全受理経路が正規化後に #204 codec/validatorを必ず通る (bypass・検証省略経路の不存在を構造testで保証)。
- 全normalizer失敗はzero-write (partial intent保持なし。既存 #205 zero-write規約と同一)。
- envelope上限 (1 MiB) は全経路で最初のgateのまま (normalizerは上限超過入力をscanしない)。処理は単一pass・行scan中心で、pathological backtrackingを持つ正規表現に依存しない。
- framing認識は外形のみで、field内容・命令文を解釈しない。payload外 (説明文・trailing text) は解釈も保存もしない。prompt injection対策の正本境界は #205 AC-7 (fail-closed) のまま、認識外形が増えた分のcorpusを追加する。

## Security regression coverage (must-test)

寛容化によってprompt injection / partial applyが復活しないことを、次のcorpusで検証する:

- **複数code block**: json tag×2 → 曖昧reject。json 1つ + 非json 1つ → 曖昧reject (D-3)。
- **nested wrapper**: marker内fence → `SCHEMA_MISMATCH`。fence内marker → marker規則で受理 (一意性)。fence内fence → 複数block reject。
- **巨大入力**: envelope超過 (境界値 exact-limit / limit+1) は全量処理前に `INPUT_OVERSIZE`。上限内の巨大説明文 + 小さな正当payloadは受理。
- **unknown schema**: 正規化受理後も #204 `SCHEMA_MISMATCH` (例: `schemaVersion` 不一致・未知key)。
- **out-of-scope ref**: #204 `UNKNOWN_REF` (normalizer通過で緩和されない)。
- **trailing malicious text**: 単一block後の指示文混入は領域外として破棄され受理を阻害しない。2つ目のJSONらしきblockを伴う場合は曖昧reject。
- **injection corpus**: label・説明文への "ignore previous instructions" 等の埋め込みが、framing認識・validator判定・planner制約へ影響しない (#205 AC-7 oracleの拡張適用)。
- **semantic不変**: D-6部分文字列property (決定性・冪等性を含む)。

## Acceptance criteria

- [ ] AC-1: accepted framing一覧 (D-1) がspecどおり実装され、各framingのcanonicalization結果 (marker / standalone JSON / 単一fenced json / 説明文併存 / BOM / CRLF / 前後空白) がunit testで固定される。
- [ ] AC-2: marker無しの standalone JSON と 単一fenced json block が、marker形式と同一のvalidation・zero-write・run接続経路でimportできる。
- [ ] AC-3: 複数候補 (fenced block 2つ以上) と曖昧入力は推測せずtyped reject (曖昧) され、認識不能入力 (inline `{...}`、無tag fence、非JSON文) はtyped reject (認識不能) される。
- [ ] AC-4: normalizerはIntent semanticを変更しない (D-6部分文字列property test。field修正・mapping・丸め・再serializeの不在)。
- [ ] AC-5: 正規化後は必ず既存 #204 strict parser/validatorを通る (normalizer成功 → codec/validator未通過で受理される経路の不存在をtest)。
- [ ] AC-6: out-of-scope ID / unknown field / invalid semantic value / 禁止内容はmarker形式と同一にrejectされる (regression test)。
- [ ] AC-7: normalizer失敗と #205 framing失敗と #204 validator失敗がtypedに区別され、UI失敗表示が19種一対対応する。normalizer失敗の案内に認識可能形式の提示と再コピー手順が含まれる。
- [ ] AC-8: malformed / multiple-block / oversized / adversarial入力のunit test corpus (Security regression coverage節の全項目) が存在する。
- [ ] AC-9: raw AI responseがdiagnostics・log・永続化へ書き込まれないことが検証される (現行契約の回帰test)。
- [ ] AC-10: physical-deviceでChatGPT / Gemini等のrepresentativeコピー結果 (marker付き・JSONのみ・説明文+fenced JSON・BOM/CRLF混在) がそのまま取り込めるevidenceがある。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ImportNormalizer` unit test (framing別corpus + canonicalization結果の固定) |
| AC-2 | pipeline unit test (standalone/fenced → codec/validator通過) + device evidence (AC-10) |
| AC-3 | normalizer unit test (曖昧・認識不能corpus) |
| AC-4 | property test (部分文字列制約・決定性・冪等性) |
| AC-5 | pipeline構造test (受理経路がcodecを必ず通る。fake codec注入または経路網羅) |
| AC-6 | regression test (既存 #204 corpusをstandalone/fenced framingで再入力) |
| AC-7 | 失敗表示UI test (19種) + ja/en strings解決 |
| AC-8 | security corpus unit test (Security regression coverage節) |
| AC-9 | diagnostics契約test / code review (書込み経路の不在) |
| AC-10 | physical-device evidence (docs/assessment/ またはissue記録) |

## Open questions (non-blocking — reviewで確定)

1. **D-3の確定**: fence block総数厳格案 (推奨) vs json tag付きのみ数える案。#332 evidenceでの実コピー分布を見て判断してもよい。
2. **新typed failure 2種の最終命名** (実装時でよい)。
3. **無tag fence 1つの受理** (内容がJSON objectとしてparseできる場合のみ受理する案): V1では認識不能rejectとする起草判断だが、実際のAI出力で無tag fenceが頻出する場合はV2で再検討する (外形明示性と実用性のtradeoff)。
4. **info string `JSON` (大文字) の扱い**: 起草案ではcase-insensitive受理。厳格に小文字 `json` のみとする選択肢もある。

## Relationship / 責務境界

- **#205 (implemented)**: exchange framingのうち **marker形式の規則** は #205所有のまま不変。本specはその手前の外形認識層 (Import Normalizer) を新設し、framing受理枠を拡張する。acceptance時にspec 205へ経緯注記を追加する (marker規則・#205 typed失敗の意味変更なし)。
- **#204 (implemented)**: payload schema・validator・sessionは一切変更しない。normalizer出力は #204 codec/validatorの入力のまま。
- **#330 (OPEN)**: authoring contract簡素化 (未言及ref・FIXED item・schema V2) は意味レベルの契約変更であり、本spec (framing/transportのみ) と責務を分離する。本spec導入後も #330の成果なしに未言及refは `INCOMPLETE_COVERAGE` のまま (semantic無変更)。
- **#332 (OPEN)**: import入力の取得UI (clipboard/file-first・bounded editor・parse状態表示) は #332所有。#332は本specのnormalizer (typed結果 + framing種別enum) を共通import pathとして利用し、UI側で独自parseを持たない。
- **#327 (OPEN) / #328 (OPEN)**: instruction/interview設計とimport成功後UI。本specと直接の変更面の重なりなし。

## Change history

- 2026-09-16: Draft created for #329。baseline `aab0d293d1` (origin/main) 上で、現行実装 (`IntentImportParser`・`ExchangeImportPipeline`・`ExchangeFlowUi` 17種失敗表示・全経路envelope上限・diagnostics不記録) を確認のうえ起草。accepted framing比較 (D-1〜D-4)、typed outcome拡張 (D-5)、normalization boundary (D-6)、diagnostics policy (D-7)、security regression要件 (D-8) をdraft decisionとして整理。

## References

- [Issue #329](https://github.com/nunu1733/NunuLauncher/issues/329)
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md) (framing/envelope上限/session — marker規則は本specでも不変)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md) (payload schema/validator)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (scope binding gate・失敗分類13 class)
- Issue #330 (authoring contract簡素化 — 意味レベルとの責務分離), Issue #332 (clipboard/file-first import UI — 下流), Issue #327, #328
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) (diagnostics契約。raw text記録禁止の正本)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [DESIGN.md](../../DESIGN.md) §9 (organizer module構成)
