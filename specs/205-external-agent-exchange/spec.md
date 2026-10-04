---
issue: "#205"
status: accepted
requirements: [FR-017]
risk:
  - privacy
  - layout-data
updated: 2026-09-16
---

# External Agent Exchange: ChatGPT/Gemini等によるOrganizer personalization

> Status: **accepted** (2026-09-16) — 5回のreview round (owner re-review 2026-09-13/14/15 + ChatGPT re-review 2026-09-15 ×2) を経て、ChatGPT 2nd re-review (**Approve**、snapshot `0e4f154` 基準、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/205#issuecomment-5683891236)。head SHA `0e4f154cfd1c3dec083415eaa17cf9789d5eb588`) で固定された。依存する #204 (Context / PersonalizedIntent exchange contract) は **accepted (2026-09-15、6th review Approve)** かつ **実装済み** (PR #322 merge。`organizer/personalization/` module・`ExportSessionStore`・planner `intentPreferences` 消費が現mainに存在)。#203 (usage signals) も **実装済み** (PR #321)。**exchange framing (外部agent返答の包み方)、session置換の確認UX、process recreation後のrun再構築semantics、Pre-send Disclosureとpackage生成の順序契約は本specが所有する**。#204由来の名称 (privacy tier、12 failure class、session TTL 24時間、V1 content limits、固定6 capability set等) はすべて accepted #204 契約の実名である。実装はplan.mdのExecution checklistに従う。

## Problem

Organizer personalizationをAIで行う場合、アプリ内にagent frameworkを実装しなくても、ChatGPT / Gemini等の汎用チャット/agent環境へpersonalization contextを渡し、外部で調査・複数stepの検討・ユーザーとの対話を行った結果をsemantic intentとして戻せれば、高品質な個別整理が実現できる。特に端末に聞き慣れないアプリがある場合、外部agentはWeb検索でアプリ用途を確認した上でgrouping / priority / affinityを判断でき、one-shot分類より広い能力を持つ。

一方で、(a) この交換経路が存在しないためユーザーは外部AIに整理案を出してもらう標準手段がなく、(b) Launcher内部IDやraw usage/layout情報をそのままpromptへ出し、自由文の返答を直接layout変更へ変換すれば、privacy / prompt injection / safety / reproducibilityが壊れる。

## Outcome

NunuLauncherがagentそのものを内蔵せず、(1) #204契約の `PersonalizationContextExportV1` とagent向けinstructionを明示分離したexchange packageをユーザー確認の上で外部へ送出でき、(2) 外部agentが返した `PersonalizedIntentV1` を厳格にparse・検証し、(3) 既存 #182 planner → #194/#195 preview → confirm → apply 経路のみで適用できる、正式な External Agent Exchange workflowを提供する。外部agentは調査・推論・対話を自由に行ってよいが、NunuLauncherが受け入れる成果物は #204 のversioned intentのみである。

## Scope

- Exchange package (agent向けinstruction + `PersonalizationContextExportV1` data) の生成。instructionとdataの明確な分離構造 (**CONTEXT marker行** による機械検証)。
- Export transport: clipboard copy / Android Share Sheet / file (text) export の3経路。
- Export前の外部送信内容確認UX (privacy tier、redacted / label-inclusive mode)。確認は **生成済みexchange package** に対して行われ、確認対象と送信対象の同一性が保証される順序契約を含む。
- **activityなexport sessionが存在する状態での新規生成に対する無効化確認UX**、および未送信packageの送信前確認取消による当該sessionの明示的失効 (#204 single-active-session規則との整合。後述)。
- Import transport: text貼付付け (clipboard含む) / file選択。Android share-back intent受信はV1では提供しない (Decision参照)。
- Importされたtextからの厳格な `PersonalizedIntentV1` 抽出。**exchange framing (返答text内でintent本体を囲むmarker・構造と、その抽出規則) は本spec (#205) が所有し、このrevisionで具体形式を固定した**。抽出されたpayloadのschema検証は #204 validatorが所有する。framing不適合は #205側のtyped parse失敗であり、#204のvalidation failureと区別される。
- Process recreation後のimport成立から既存manual organization flowへの再接続semantics (**fresh run再構築**。既存run状態の復元はしない)。
- Import失敗・検証reject・取り込み中止のobservableな失敗UI。
- 外部agentの能力 (Web検索、複数step、ユーザーへの追加質問) をworkflow上妨げないpackage内容。
- Provider中立性: 特定consumer appのprivate API / UI automationへの依存禁止。
- 上記のaccessibility (TalkBack / Switch Access / large font) と、clipboard失敗・share target不在等の環境失敗UX。
- Physical-deviceでChatGPT/Gemini等を実際に使ったrepresentative workflow evidence。

## Non-goals

- ChatGPT/Geminiアプリの自動操作、consumer serviceの非公開API連携、UI automation (Issue本文のnon-goals)。
- NunuLauncher内agent loop、agentのchain-of-thought取得・保存。
- agent outputの自動適用 (importだけではlayoutを変更しない)。
- raw DB mutation import、arbitrary scripts/plugin execution。
- #204 schema の独自派生版・再定義。schema定義は #204 が唯一の正本。
- アプリ内完結型のmanaged AI path (#206 の対象)。transport/provider adapterの共通化は #204 intent契約でのみ行う。
- #204 validator・planner接続adapter・export session store自体の実装変更 (いずれも #204 側の成果物として現mainに存在。本workflowはこれを利用する)。
- Diagnostics journalへの個人情報 (label等) の記録。既存 [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 規則を変更しない。
- share-back受信 (`ACTION_SEND` receiver) のV1提供 (Decision 3。V2再検証)。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映。#204の用語と合わせて調整する)。

**外部エージェント交換 (External Agent Exchange)**:
`PersonalizationContextExportV1` を外部chat/agent環境へ持ち出し、 `PersonalizedIntentV1` として持ち帰る、NunuLauncherが提供するuser-driven workflow全体。agent実行部はNunuLauncherの外にある。
_Avoid_: AI連携 (provider依存を想起させる)、integration (#206 managed pathとの混同)

**交換パッケージ (Exchange Package)**:
1回の外部agent受け渡しに使う、agent向けinstruction部と `PersonalizationContextExportV1` data部を明示分離した単一text (またはfile) 表現。data部はCONTEXT marker行 (`-----BEGIN NUNULAUNCHER CONTEXT-----` / `-----END NUNULAUNCHER CONTEXT-----`) で囲まれ、instruction/dataの区別が機械的に判別できる。
_Avoid_: prompt (data部も含む曖昧な呼称)、backup

**送信前確認 (Pre-send Disclosure)**:
exchange packageをclipboard・share・file等でapp外へ出す直前に、外部へ出る情報の種別と内容をユーザーに提示し、明示的な同意を得る確認step。確認なしに外部送信経路を開かない。
_Avoid_: privacy policy (静的文書との混同)

**持ち帰りIntent取り込み (Intent Import)**:
外部agentの返答textから、exchange framingの内側にある `PersonalizedIntentV1` のみを厳格に抽出し、#204 validatorへ渡す取り込みstep。曖昧なJSON拾い上げやpartial解釈を行わない。
_Avoid_: auto-apply、paste-to-layout

**交換フレーミング (Exchange Framing)**:
外部agentの返答text内で、最終成果物である `PersonalizedIntentV1` 本体を囲むmarker対と、そこから本体を一意に抽出する規則。本spec (#205) が所有して定義する。V1の具体形式は **完全行marker** `-----BEGIN NUNULAUNCHER INTENT-----` / `-----END NUNULAUNCHER INTENT-----` (行頭・行末のASCII空白を除き完全一致、case-sensitive) と、「一意なmarker対1組・その間の領域をverbatim抽出」という規則である (「Behavior scenarios: importの厳格な抽出」)。framing内のpayloadのschema解釈は行わない (それは #204)。framingの不成立・曖昧性は #205側のtyped parse失敗 (`FRAMING_MISSING` / `FRAMING_AMBIGUOUS` / `FRAMING_EMPTY`) である。
_Avoid_: schema (payload本体の契約は #204)、system prompt (instruction部の一部でありframing抽出規則と混同しない)

**交換セッション置換確認 (Session Replacement Confirmation)**:
activityなexport session (#204) が存在する状態で新規exchange package生成を開始するとき、#204 single-active-session規則により既存exchangeが無効化されることを明示し、userの承認を得る確認step。承認なしには生成を開始しない。
_Avoid_: 上書き保存 (既存exchangeの回答が以降import不可になる破壊的操作であることの表示を省く呼称)

## Behavior scenarios

### Scenario: export package生成と送信前確認

Given manual organization surfaceがrun操作非activeの状態でuserが外部agent利用を選択し、#204契約のcontext export生成が利用可能である、
When privacy modeを選択した上でexchange package生成を実行する、
Then packageはinstruction部 (goal、許可事項、遵守事項、期待返答形式を含む英文) とdata部 (CONTEXT marker行で囲まれた `PersonalizationContextExportV1` のcanonical JSON) に機械的に分離された **単一のimmutableな値** として生成され、
And 既定のdata部はraw package名・内部ItemId/DB row ID・opaque profile identifier・raw usage ms/timestamps・diagnostics/recovery metadataを含まず、
And 送信前確認画面は **生成済みのpackage** を対象に外部へ出る情報の種別と内容を提示し、明示的な送信承認なしにclipboard/share/file経路のデータが出ることはない。

### Scenario: activityなexchangeがある状態での新規生成 (session置換確認)

Given 有効期限内のexport session (activityなexchange) が存在し (`#204 ExportSessionStore.active`)、userが新規exchange package生成を開始する、
When 生成の最初のstepに達する、
Then 既存exchangeが新規生成により無効化される旨 (#204 single-active-session。当該exchange宛のagent回答は以降import不可となる `EXPORT_MISMATCH`) を明示する確認が表示され、承認なしには生成 (context export生成・session保存・package合成) を開始しない、
And 辞退した場合は既存sessionは不変であり、既存exchange宛の回答は引き続きimportできる。

> launcher側はpackageが実際に外部へ送られたか否かをtransport完了からは判定できないため、この確認はsessionの「送信済み/未送信」を区別せず、activityなsessionの存在に対して一律に適用する。flow内でのprivacy mode変更による再生成は、当該flowのpackageが未送信であることがflow自身で追跡できるため確認を要さず、当該sessionは再生成により置換される。

### Scenario: 未送信packageの送信前確認取消

Given 生成済み・未送信のexchange packageに対して送信前確認が表示されている、
When userが送信を承認せず取り消す、
Then 当該生成で作成されたsessionのみを明示的に失効させ (#204 `ExportSessionStore.invalidate`)、activityなsessionを残さない、
And 当該packageは一度もtransportされていないため、他のexchange・既存run状態・layout DBに影響しない (zero-write)。

### Scenario: E1送信済み → E2生成取消 → E1 import (representative)

Given E1を生成・送信済みでE1のsessionがactivityである、
When userが新規生成を開始し、session置換確認を承認してE2を生成した後、E2の送信前確認を取り消す、
And 後からE1宛のagent回答をimportする、
Then E1はE2生成の時点で #204 single-active-session規則により既に無効化されているため `EXPORT_MISMATCH` でrejectされる (zero-write)。失敗説明は「新規exportの生成により旧exchangeが無効化されたこと・再exportが必要なこと」を案内する、
And session置換確認を辞退していた場合はE1のimportは引き続き成立する。

### Scenario: disclosure対象とtransport対象の同一性

Given 生成済みexchange packageに対して送信前確認が表示されている、
When userが明示的に送信を承認する、
Then 承認後に実行されるtransport (clipboard copy / Share Sheet / file export) は、確認画面に提示されたpackageと **同一のimmutableな値** をそのまま送出する (承認後に別内容のpackageへ差し替えられる経路が存在しない)、
And 確認画面でprivacy modeの変更等が行われた場合はpackageを再生成し、送信前確認からやり直す (旧packageが送信されることはない)、
And 確認の前に外部送信経路が開かれることはない (確認と送信の順序は逆転しない)。

### Scenario: privacy mode選択

Given exchange package生成前の設定step、
When userがredacted mode (既定) とlabel-inclusive modeを選択できる、
Then 選択したmodeは生成されるpackageのdata部metadata (privacy tier) として明示され、label-inclusive選択時はapp label・folder titleが外部へ出る旨を送信前確認画面が明示する、
And 既定はredacted modeであり、mode変更時はpackageが再生成され送信前確認からやり直す。

> #204 accepted契約ではこの2 modeは privacy tier `EXTERNAL_REDACTED` (既定) / `EXTERNAL_WITH_LABELS` (明示選択時) として固定されている (内部engine向け `LOCAL_FULL` は本workflow対象外)。`EXTERNAL_REDACTED` はuser作成自由文class全体を除外し、surrogate (hash等) を生成しない。

### Scenario: transport実行

Given 確認済みexchange package、
When userが選択したtransport (clipboard copy / Share Sheet / file export) を実行する、
Then clipboard失敗・share target不在・file書込失敗はいずれもtypedな失敗としてuserへ通知され、silent失敗しない、
And export操作自体はlayout DBへ一切書き込まない。

### Scenario: 外部agentの自由な調査

Given 外部agentがexchange packageを受領、
Then packageのinstruction部は、unknown appについてのWeb検索、複数source比較、usage/current groupingのpreference signalとしての考慮、必要時のuserへの追加質問を禁止しておらず、
And 最終回答を `PersonalizedIntentV1` としてINTENT marker行で囲んで返すよう要求し、
And NunuLauncherは中間思考・tool実行を再現・検証しない (最終intentのみを検証する)。

### Scenario: importの厳格な抽出 (exchange framing V1)

Given userが外部agentの返答textをimport操作 (text貼付付け / file選択) で渡す、
When 取り込みを実行する、
Then 取り込みtextはUTF-8として、行末をLFに正規化し (CRLF/CR → LF)、先頭のUTF-8 BOMを除去した上で、**完全行marker** `-----BEGIN NUNULAUNCHER INTENT-----` (開始) / `-----END NUNULAUNCHER INTENT-----` (終了) によって区切られた領域を抽出する。marker行とは、行頭・行末のASCII空白 (space / tab) を除去した残りがmarker文字列と完全一致する行である (case-sensitive)。
And 抽出の成功条件は「BEGIN marker行がちょうど1つ、END marker行がちょうど1つ、かつBEGIN行がEND行より前」であり、領域内のtextは **verbatim** (内部を改変しない。領域全体の先頭・末尾の空白のみをtrim) で抽出され、schema解釈を行わず #204 codec/validatorへ渡す、
And marker対の前後の自由文 (agentの説明・注記) は許容され、抽出対象にならない、
And marker不在・END先行は `FRAMING_MISSING`、marker行の複数出現 (nested marker相当行を含む) は `FRAMING_AMBIGUOUS`、有効なmarker対だが領域が空・空白のみは `FRAMING_EMPTY` の **#205側typed parse失敗** としてzero-write処理され、userに再依頼 (agentへframing付きで再回答を求める) を案内する、
And payload内のJSON string valueにmarker相当の **部分文字列** が含まれても、それは完全行一致には該当しないため抽出を乱さない (framingはescaping機構を持たない。正当なJSONは (canonical単行・pretty print多行のいずれでも) marker行と完全一致する行を含み得ないため、escaping不要。marker相当行が領域内に現れた場合はmarker複数出現として `FRAMING_AMBIGUOUS` でfail-closed rejectされる)、
And 曖昧にJSONらしきものを拾ってpartial applyする経路は存在しない。

### Scenario: import envelope上限 (resource safety)

Given marker前後に大きな自由文を含む返答、または全体が巨大な返答 (marker不在を含む) がimportされる、
When 取り込みを実行する、
Then #205所有の **import envelope上限** (import text全体、marker前後の自由文を含む。UTF-8 byte基準で 1 MiB = 1,048,576 bytes) を超える入力は、行末正規化・marker走査等の全量処理の **前に** `INPUT_OVERSIZE` (#205側typed失敗) としてzero-write拒否され、
And 上限は貼付付け・clipboard・fileの全import経路で同一に適用される (可能な経路では全量の保持・正規化の前にfailする)、
And 上限内の入力は正常にframing抽出へ進む (巨大prefix/suffix + 小さな正当payloadの組合せも、全体が上限内なら受理される)、
And `INPUT_OVERSIZE` は #204 payload本体の `OVERSIZE` (128 KiB、抽出後payloadへの上限) とは別契約・別失敗種別であり、UX案内も異なる (envelope超過は「marker周りの余分な文章を減らして再取り込み」、payload超過は「intent本体が大きすぎる」)。

### Scenario: validation reject

Given 抽出されたintentが #204 validatorでmalformed / unknown schemaVersion / out-of-scope ID / oversize / 禁止内容 (座標直接指定等) と判定される、
When 検証結果を表示する、
Then typed failure種別ごとにuserが理解できる説明を表示し、zero-write (selection/layout/planning入力を一切変更しない) であり、
And userは修正した返答を再importできる。

> #204 accepted契約のtyped failure class (12): `SCHEMA_MISMATCH` / `EXPORT_MISMATCH` / `SESSION_EXPIRED` / `CONTEXT_STALE` / `OVERSIZE` / `UNKNOWN_REF` / `DUPLICATE_REF` / `INCOMPLETE_COVERAGE` / `INVALID_ENUM` / `FORBIDDEN_CONTENT` / `MOBILITY_CONTRADICTION` / `CAPABILITY_UNSUPPORTED`。うち `CAPABILITY_UNSUPPORTED` はV1固定6 capability set常時宣言契約により **V1では到達不能なreserved class** である (#204)。UIの失敗説明はこの分類に一対対応させる (#205側の `FRAMING_MISSING` / `FRAMING_AMBIGUOUS` / `FRAMING_EMPTY` / `INPUT_OVERSIZE` 4種と合わせて16種の失敗表示を定義する。#204 `OVERSIZE` と #205 `INPUT_OVERSIZE` は別契約・別種別)。

### Scenario: 古いexport宛のintent

Given export E1を生成後にuserがsession置換確認を承認してexport E2を生成し、E1宛のintentがimportされる、
Then #204のexport一致検証により `EXPORT_MISMATCH` でrejectされ、userに新規exportへ対応する旨 (再exportと再依頼) が表示される。

> #204 accepted契約: activityなexport sessionは同時に1つ (新規export生成が既存sessionを無効化するsingle-active-session規則)。本workflowでは新規生成の開始をsession置換確認でgateする (前述scenario) ため、無言の無効化経路は存在しない。

### Scenario: process deathを跨ぐ往復

Given export Eの生成後、userが外部agentアプリへ切り替え、その滞在中にLauncher processが破棄される、
When userが戻り、E宛のagent返答をsession有効期限内にimportする、
Then 抽出と検証は #204のdurableなexport session経由でref解決・export一致を行い、再exportなしで成立する、
And NunuLauncher側でexchange package本文・返答textを永続化していないことが壊れの原因にならない (返答text自体を失った場合の回復は、外部agent側の再copyまたは再exportである)、
And session失効 (TTL 24時間) 後にimportした場合は `SESSION_EXPIRED` としてtypedな失敗で拒否され、再exportが必要である旨が表示される。

> session (exportId、ref↔内部ID map、privacy tier、structural `sourceContextDigest`、signal provenance、生成・失効時刻) のdurable保持は #204契約・実装 (`ExportSessionStore` / `AndroidExportSessionStore`、app-private・backup対象外・AtomicFile) が所有し、本workflowは追加の永続化を持たない。

### Scenario: process recreation後のrun再構築

Given process deathを跨いでimportが抽出・検証を通過した、
When validated intentを既存のmanual organization flowへ接続する、
Then 接続は **fresh runの再構築** として行われ、既存run状態の復元は行わない。新しいRunIdを発行し、run state machineはIdleから通常flow (missing-app detection/selectionを含む) を辿る、
And #228のmissing-app selection・scope構成はprocess-local非永続が既存の不変条件であるため復元せず、通常flowの再選択に委ねる、
And intentのsource bindingは2段で検証される: import時のsource context一致検証 (#204 structural `sourceContextDigest` 再計算照合、`CONTEXT_STALE`) と、fresh runのplanning/apply時の既存capture revision stale check。import成立後・planning前にhomeが変化した場合は既存Stale pathでzero-write終了する、
And V1ではexchangeのexport・import導線はmanual run操作が非activeのときにのみ提示される。activeなrun操作中に到達したimportでvalidated intentからのrun開始が既存single-active-operation gateで拒否された場合は、typedな案内 (run終了後の再import) でzero-write終了し、validated intentを保持しない。

### Scenario: 往復中のhome変更

Given export Eの生成後、外部agentの検討中にuserがhomeで配置・lock・分類等を変更した、
When E宛のintentがimportされる、
Then source binding検証 (#204 structural `sourceContextDigest` 照合) により `CONTEXT_STALE` でrejectされ、既存selection/layout/planning入力は不変であり、
And userに再exportが必要な旨が表示される (V1ではrebaseしない)。

> #204 accepted契約: stale判定対象は構造的状態 (layout・lock・availability・分類・placement) のみであり、#203 usage signalの変化・再取得は `CONTEXT_STALE` の原因にならない (signal provenanceはsessionへの記録のみ)。

### Scenario: previewと確認の省略禁止

Given importされたintentがvalidationを通過した、
When plan生成が行われる、
Then 既存の #194 plan preview → #195 explicit confirmation → 既存transactional apply/recovery pathのみを通り、agentの主張 (「この配置が最適」等) でconfirmationを省略しない、
And 別のintentがacceptされた場合、既存previewはsilentに再解釈されず無効化される (#204のregeneration semantics)。

### Scenario: prompt injectionの閉じ込み

Given app label・folder title・外部agent応答text・agentが参照したWeb source等に命令文 ("ignore previous instructions" 等) が含まれる、
Then それらはinstructionではなくdataとして扱われ、外部agentがadversarial dataに影響され得ること自体は前提とする (agentのinstruction遵守を信頼の前提にしない)、
And safety境界は「**adversarial dataに影響された返答であっても、unsafeなmutationに到達しない**」ことである。すなわちいかなる応答も exchange framing抽出 (#205) と #204 validatorのschema・allow-list・closed enum・coverage検証を通過するまで受理されず、通過したintentでもplannerのlock/bounds/overlap制約は不変であり、
And validation・検証に至らなかった入力はzero-writeで破棄される。

### Scenario: accessibility・環境失敗

Given TalkBack / Switch Access / large font環境、またはclipboard利用不可・share target不在の環境、
When export/import workflowを操作する、
Then 確認画面・失敗表示はaccessibility対応され (focus順、読み上げlabel、font scaling)、代替transport (file export等) への誘導が提示される。

## Decisions (このrevisionで固定したV1決定)

1. **exchange framing具体形**: 完全行marker `-----BEGIN NUNULAUNCHER INTENT-----` / `-----END NUNULAUNCHER INTENT-----`、marker対前後の自由文は許容、escapingなし (正当なJSONはmarker完全一致行を含み得ない)、複数・nested markerは `FRAMING_AMBIGUOUS`、marker不在は `FRAMING_MISSING`、空blockは `FRAMING_EMPTY`、CRLF/CR → LF正規化・BOM除去。**所有は本spec (#205)** (#204はpayload本体のschemaと検証のみを所有)。
2. **exchange package構造**: instruction部 (英語。Goal / You may / You must / Response format の4section構成。Response formatにINTENT marker行を明示) + data部 (`-----BEGIN NUNULAUNCHER CONTEXT-----` / `-----END NUNULAUNCHER CONTEXT-----` 行で囲んだ `PersonalizationContextExportV1` canonical JSON単行)。分離はmarker行により機械検証可能 (AC-1)。最終prose文言の微調整はAC-10 evidenceで行う (構造・必須要素は固定)。
3. **初回transport**: export = clipboard copy + Share Sheet (text/plain `ACTION_SEND` + chooser) + file (SAFによるtext file保存) の3経路。import = text貼付付け (clipboard pasteを含む) + file選択。**share-back intent受信はV1で提供しない**: `ACTION_SEND` receiverをmanifest登録するとlauncherが全textのshare targetとなる受信面が広がる割に、貼付付けで同等のUXが成立するため。V2で需要に応じて再検証する。
4. **instruction部言語**: 英語固定 (外部agentの認識精度。AC-10 evidenceで検証)。UI copy (確認・失敗表示) は日本語を正本とする他organizer surfaceに合わせる。
5. **size扱い (export側)**: exchange packageのsizeは **#204 payload上限** (export文書全体 ≤256 KiB、items ≤512等) で上限制御し、超過は生成時typed失敗とする (fail-closed、切り詰め・分割送信はしない)。package全体 (instruction + marker行 + payload) は固定長instruction + payload上限で構造的上限内に収まる。clipboard/Share Sheetのtransport失敗時はfile exportへの誘導で対処する。
6. **import envelope上限 (#205所有)**: import text **全体** (marker前後の自由文を含む) の最大sizeを **UTF-8 byte基準で 1 MiB (1,048,576 bytes)** に固定する。これは #204 `MAX_INTENT_BYTES` (128 KiB、抽出後payloadへの上限) とは **別契約** であり、自由文を許容するframingを採用したことに起因するexchange envelope全体のresource safety境界である (外部agent応答はuntrusted inputであり、巨大入力の無制限な保持・正規化・走査を防ぐ)。byte基準を正本とする (計算資源の実単位)。貼付付け・clipboard・fileの全import経路に同一上限を適用し、可能な経路では全量の保持・正規化の前にfailする。超過は #205側typed失敗 `INPUT_OVERSIZE` としてzero-write処理する ( #204 `OVERSIZE` はpayload本体の上限でありUX案内が異なるため別typeとする)。

## Data and state

- 読むdata: #204契約のcontext export生成に必要なcanonical入力 (captured `LayoutSnapshot`、解決済み分類、lock状態、#203 signal snapshot (利用可能時))。本specはこれらの再定義をしない。
- exchange packageは生成時に完了するimmutableな値であり、送信前確認はこの生成済み値に対して行われる。確認対象とtransport対象の同一性は同一値の受渡しで構造的に保証する (package本文の永続化もdigest等の追加永続化もしない)。privacy mode変更等で再生成が起きた場合は確認からやり直す。
- **session置換の規則**: activityなsessionが存在する状態での新規exchange flow開始は、session置換確認を必須とする (承認なしの生成開始経路は存在しない)。flow内でのprivacy mode変更による再生成は確認不要 (当該flowのpackageは未送信と追跡できる)。送信前確認の取消は、当該未送信sessionの明示的失効 (#204 `ExportSessionStore.invalidate`) とする。生成の失敗 (session保存失敗を含む) はtyped失敗としてpackageを出さず、既存sessionの状態は #204 store実装 (`AtomicFile`) のatomic性に従う。
- import成立後のrun接続はfresh run再構築のみであり、本workflowはrun stateの永続化・復元を持たない。exchange導線の提示はmanual run操作非active時に限定する (V1)。validated intentからのrun開始は既存single-active-operation gateを通る。
- **export生成とimport検証の入力source単一化**: exchange flowのcontext export生成と、import時の検証用export view再構築は、同じcanonical capture → `ExportInputs` / structural projection 導出の **単一adapter** を共有する (既存 `ProductionOrganizationInputComposer` / `FullTargetSetMaterializer` のfull-target composition経由)。#228のscope selectionはrun内のcomposition概念であり、exchange flow (run外) のexport生成・import再構築では関与しない。この単一化により、export時とimport時でprojection drift (同一homeなのに `ref` 対応・role・mobility・grid投影が変わる) が構造的に起こらない。
- 永続化の分担: 本workflow ( #205側) はexchange package本文・import済み返答textを永続化しない。**pendingなexport identityとexport-scoped ref↔内部ID mappingのdurable保持は #204契約のexport sessionが所有する** (app-private・backup対象外・TTL 24時間。`AndroidExportSessionStore` として現mainに実装済み)。本workflowはprocess deathを跨ぐimportを #204のexport session経由でのみ成立させ、独自の永続化経路を追加しない。session失効・不在時のimportはtypedな失敗であり、回復は再exportである。
- layout変更は既存run (snapshot → plan → preview → confirm → apply) のみで行われ、本workflowは新しいDB書込経路を作らない。
- #204 accepted契約由来の制約 (実名): export data部の対象種別は #235 のsemantic placement role族 (`APP_OR_SHORTCUT` / `FOLDER` / `WIDGET`) に揃えられ、per-itemのmobility projection (`MOVABLE` / `CONDITIONAL` / `FIXED` + `fixReason`: `RESERVED_REGION` / `LOCKED` / `UNAVAILABLE` / `DOCK` / `APP_PAIR_MEMBER` / `FOLDER_MEMBER`) がexportに含まれ、intentの意味検証はこれに対して行われる (`MOBILITY_CONTRADICTION`)。widgetのspanはexportに含まれずintentからも指定できない。`APP_PAIR` / `SHORTCUT_LEGACY` / `Unknown` はintent addressable対象外であり、`preservedConstraints` のconstraint集計としてのみ投影される。exportの `ref` と `exportId` は同一乱数seam (`RandomIdAllocator`) からの生成ごとの乱数であり、structural `sourceContextDigest` はsession-localでexport文書・intent応答のいずれにも現れない。`usageSignals` は #203 snapshotの正規化bucket projection (`foreground30d`/`foreground7d` 0–4、`recency` 0–3、`activeDays` 0–4、`launcherCount` 0–4、`launcherRecency` 0–3、tier制御付き・snapshot不在時はfield省略) である。intentは既存RunMode (`FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization`) を増やさず、整理対象の追加も行わない (追加候補は #228 のuser明示選択composition inputのみ)。validated intentは #204 `IntentPlannerAdapter` により `OrganizationInput.intentPreferences` へ投影され、planner (`PolicySourceKind.PERSONALIZED_INTENT` 第7policy input) で消費される。instruction部はこれらの制約と矛盾する約束 (「widget sizeを提案してよい」等) を含んではならない。

## Permissions, privacy, and security

- 追加permissionなし (clipboardはframework API、Share Sheetは既存intent機構、file export/importはSAF/user選択)。
- 外部送信はuserの明示操作 (確認画面を経たcopy/share/save) のみ。network通信は本workflowに含まれない。
- 既定exportから除外: raw package名、内部ItemId/DB row ID、opaque profile identifier、raw usage ms/timestamps、diagnostics/recovery metadata (Issue本文・#204 accepted契約と整合。いずれのtierでも含まれない)。
- app label・folder titleはuntrusted dataとして扱う。instruction/data分離 (package構造が機械的に保証することを本specが要求) は外部agentへの誘導を改善する **risk低減** であって、agentがdata内の命令文に影響されないことの保証ではない。safetyの正本境界は「いかなる返答も exchange framing抽出と #204 validatorのschema・allow-list検証とplanner制約を越えられない」こと (fail-closed) に置く。
- clipboard内容・import textをdiagnostics journalへ記録しない (organizer-diagnostics.mdの個人情報規則)。
- share-back受信 (`ACTION_SEND` receiver) を登録しない (Decision 3)。launcherを全textのshare targetにする受信面をV1で追加しない。

## Accessibility and localization

- 送信前確認・session置換確認・import失敗・validation reject・transport失敗の各UIはTalkBack読み上げ、Switch Access操作、large font対応を必須とする。
- UI copyは日本語を正本とする他organizer surfaceに合わせ、instruction部のagent向け文言は英語固定とする (Decision 4)。

## Acceptance criteria

前提: #204 contractは **accepted・実装済み** (現main) であり、本specの実装blockerは解消している。本specは2026-09-16にacceptedとなった (chatgpt re-review Approve)。

- [ ] AC-1: exchange package生成からimport・previewまでのend-to-end UXが定義され、instruction/data分離構造 (CONTEXT marker行) が機械的に検証できる。
- [ ] AC-2: 既定exportがraw package名・内部ID・profile identifier・raw usage/timestamps・diagnostics metadataを含まないことがcontract testで検証される (#204 `ContextExportBuilder`/tier契約に基づくpackage levelの検証)。
- [ ] AC-3: 送信前確認なしにclipboard/share/file経路でデータが出ないことが検証される。redacted (`EXTERNAL_REDACTED`) / label-inclusive (`EXTERNAL_WITH_LABELS`) modeの扱いが #204 accepted契約に従い、label-inclusive時の明示開示がある。
- [ ] AC-4: importが本specが定義するexchange framing (完全行marker `-----BEGIN NUNULAUNCHER INTENT-----` / `-----END NUNULAUNCHER INTENT-----`) により一意に区切られた `PersonalizedIntentV1` のみを抽出する。次がtestされる: (a) marker対前後の自由文は許容されること、(b) marker不在・END先行は `FRAMING_MISSING`、marker行複数出現 (nested相当行を含む) は `FRAMING_AMBIGUOUS`、空blockは `FRAMING_EMPTY` のtyped parse失敗としてzero-write処理されること、(c) payload内JSON string value中のmarker相当部分文字列は抽出を乱さないこと、(d) CRLF/CR入力はLF正規化後に同一結果となること (決定性・冪等性)、(e) import text全体がenvelope上限 (1 MiB) を超える入力 (巨大prefix/suffix + 小さな正当payload、marker不在の巨大入力、上限境界値) は全量処理の前に `INPUT_OVERSIZE` でzero-write拒否され、上限内の同等入力は受理されること。
- [ ] AC-5: malformed / unknown schema / out-of-scope ID / 禁止内容が #204 validator経由でzero-write rejectされ、失敗種別 (#204 12 class + `FRAMING_*` 3種 + `INPUT_OVERSIZE`) がuserに説明されることがtestされる。
- [ ] AC-6: import後も #194 preview + #195 explicit confirmationが必須であり、agent outputの直接適用・confirmation省略経路が存在しないことが検証される。
- [ ] AC-7: untrusted app/folder label・agent応答・agentが参照した外部sourceのprompt injectionがthreat modelとtestに含まれる。test oracleは外部agentのinstruction遵守を前提とせず、fail-closed (framing/schema/allow-list reject、zero-write、planner制約不変、影響された返答でもunsafe mutationへ到達しない) を中心とする。
- [ ] AC-8: 特定provider appのprivate API / UI automationに依存しないplain-text exchangeであることが確認される (import/exportの対象がversioned text schemaのみ)。
- [ ] AC-9: clipboard失敗・share target不在・file失敗・large font・TalkBack・Switch AccessのUX evidenceがある。
- [ ] AC-10: physical-deviceでChatGPT/Gemini等を用いたrepresentative workflow evidence (export → 外部agentへのapp切替 → import → preview → confirm) がある。
- [ ] AC-11: process recreation後のimportが成立する (export → 外部アプリ滞在中のprocess死 → session有効期限 (24時間) 内のimportが再exportなしで成功)。session失効後のimportは `SESSION_EXPIRED` でtypedにrejectされ、失敗説明に再exportの案内がある。pendingなexport identity・ref mappingの復元が #204のexport sessionに一元化され、本workflowが独自の永続化を持たないことが検証される。加えて、process recreationを挟んだ **import成功 → fresh run再構築 (新RunId、通常flowでのselection再選択) → preview表示** までのintegration evidenceがある。
- [ ] AC-12: 送信前確認が生成済みpackageに対して行われ、確認対象とtransport対象が同一immutable valueであること (確認後の差し替え・確認前の送信経路の不在) 、privacy mode変更等の再生成時に確認からやり直すことがtestされる。
- [ ] AC-13: activityなexport sessionが存在する状態での新規package生成開始時にsession置換確認が表示され、承認なしには生成 (既存sessionの無効化を含む) が行われないこと。送信前確認の取消は当該未送信sessionのみを明示的に失効させ、他に影響しないこと。E1送信済み → 確認承認によるE2生成 → E2取消 → E1 import が `EXPORT_MISMATCH` でzero-write rejectされ失敗説明が再生成を案内すること、および確認辞退時はE1のimportが引き続き成立することがtestされる。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | package生成moduleのunit/contract test (CONTEXT marker行による構造検証) + 手動UX確認 |
| AC-2 | export生成のcontract test (#204 suite連携 + package level検証) |
| AC-3 | UI test + 送信経路のinstrumentation test |
| AC-4 | import parser/pipelineのunit test。reject corpus: marker不在・END先行 (`FRAMING_MISSING`)、marker複数/nested (`FRAMING_AMBIGUOUS`)、空block (`FRAMING_EMPTY`)、自由文前後許容、JSON value内marker部分一致の無影響、CRLF/LF同一結果 (決定性property test)、envelope上限 (巨大prefix/suffix + 小valid payload → `INPUT_OVERSIZE`、marker不在巨大入力、上限境界値 exact-limit/limit+1、上限内受理の対照) |
| AC-5 | #204 validator failure分類の回帰test + import UI test (16種失敗表示) |
| AC-6 | integration test (import→plan→preview→confirmの経路強制) + code review |
| AC-7 | security test corpus (injection label fixture)。oracleはfail-closed中心 (影響された応答がunsafe writeに到達しないこと、zero-write、planner制約不変) |
| AC-8 | 依存review (private API/UI automation/`ACTION_SEND` receiver登録の不使用) + plain text schema test |
| AC-9 | accessibility・環境失敗の手動/instrumentation evidence |
| AC-10 | physical-device evidence記録 (docs/assessment/ またはissue) |
| AC-11 | session store seam経由のprocess recreation simulation test (#204 suiteと連携) + process recreationを挟んだ import → fresh run再構築 → preview のintegration test + physical-device app切替evidence (AC-10と兼ね可) |
| AC-12 | package同一性のunit test (composer→transportの同一値受渡し) + 確認画面順序のUI test (再生成時の確認やり直し、確認前送信の不在) |
| AC-13 | orchestration seamのunit test (session store fake: 承認なし生成不開始・辞退時既存session不変) + 確認UI test + E1→E2生成取消→E1 import scenario test (`EXPORT_MISMATCH` zero-write・失敗説明) + 取消によるsession失効test |

## Open questions (non-blocking — 実装・evidence中に確定)

1. **instruction部の最終prose文言**: 構造・必須要素・marker文言はDecision 1/2で固定済み。微調整はAC-10 representative evidenceでのagent遵守率を見て行う。
2. **実機clipboard/Share Sheet上限の実測**: 巨大layout (数百item) での実測はAC-9/AC-10 evidenceで記録する。設計上の対応 (fail-closed + file誘導) はDecision 5で固定済み。

## Change history

- 2026-09-10: Draft created for #205. External Agent Exchange workflow spec: exchange package (instruction/data分離)、export/import transport、送信前確認、厳格import、#194/#195 preview必須。#204 acceptanceを明示的依存とする。
- 2026-09-13: Re-entry re-anchor。main `6b6bf8dd` → `f9afd8bfde` をmerge (organizer差分: #228 scope-composed run/missing-app selection、#235 widget strategy placement/semantic placement role、#271/#288 diagnostics、requirements FR-016 implemented化)。#204 draftが `f9afd8bfde` baselineへre-anchorされたことを受け、draft段階の固有名 (privacy tier名、failure class名、widget span非投影、reject-by-default V1、RunMode不変・対象追加なし) を仮称として明記。未解決のproduct decisionは解消していない (status: draft維持)。
- 2026-09-14: Owner review "Request changes" (2026-09-13、snapshot `517adbe4` 基準) への対応revision。main `c5274b5d0d` をmergeしてre-anchor。**P1 (process death)**: 往復flowでのprocess recreation後importをbehavior scenario・AC-11へ追加し、pending export identity・ref mappingのdurable保持を #204 draftのexport sessionに一元化する責務分担へ改訂 (本workflowは独自永続化を持たない)。**P1 (framing所有)**: exchange framingの所有を #205 として確定し (#204はpayload本体のschema/検証のみ)、Domain language・Scope・AC-4・open questionsへ反映。**P2 (prompt injection)**: scenario・AC-7・security test oracleを「外部agentが影響されない」ではなく「adversarial dataに影響された返答でもunsafe mutationに到達しない」(fail-closed) を中心へ改訂。加えて #204 draftの2026-09-13 review対応revision (`324e6182ae`、durable export session・single-active-session・coverage不変条件・`APP_PAIR`/`SHORTCUT_LEGACY` 対象外・V1 surrogate廃止) を仮称として反映し、「往復中のhome変更」(`CONTEXT_STALE` 仮称) scenarioを追加。未解決のproduct decisionは解消していない (status: draft維持)。
- 2026-09-15: Owner re-review "Request changes" (snapshot `02f7f90` 基準) への対応revision。main `397d3fd957` をmergeしてre-anchor (main差分は #298/#299/#315 のcapture/restore/diagnostics系で、本specの統合点への構造変更なし)。**P1 (process death後のrun再構築)**: import成立後の接続を **fresh run再構築** (新RunId、既存single-active-operation gate、通常flowでの #228 selection再選択) と固定し、既存run状態の復元を行わないことを明示。stale bindingの2段構成 (import時 `CONTEXT_STALE` + 既存planning/apply時revision stale check) と、V1でのexchange導線提示条件 (run非active時のみ) をscenario化し、AC-11をprocess recreationを挟んだ import → fresh run → preview のintegration evidenceへ拡張。**P2 (disclosure順序)**: `privacy mode選択 → package生成 → Pre-send Disclosure → explicit send → 同一packageをtransport` の順序契約へ修正。packageを生成時に完了するimmutableな値と定義し、確認対象とtransport対象の同一性を同一値の受渡しで保証 (digest等の追加永続化はしない)、再生成時は確認からやり直すことをscenario・AC-12化。あわせて #204 draftの2026-09-14 re-review対応revision (`12f773ad61`、`sourceContextDigest` のsession-local化・乱数 `ref` 割当・per-item mobility projection・`MOBILITY_CONTRADICTION` 追加で12 failure class) を仮称として反映。
- 2026-09-16: Owner re-review "Request changes" (2026-09-15、snapshot `376dc35` 基準、指摘3点) への対応revision。main `0cf82bc1e6` をmergeしてre-anchor (差分: #203実装 PR #321、#204 acceptance + 実装 PR #322。organizer/uiは無変更でrun state machine統合点は不変、planning/integration差分は #203/#204実装自身)。**P1 (framing具体形式)**: exchange framingを完全行marker `-----BEGIN/END NUNULAUNCHER INTENT-----`・自由文許容・escapingなし (JSONはmarker完全一致行を含み得ない)・`FRAMING_MISSING`/`FRAMING_AMBIGUOUS`/`FRAMING_EMPTY` typed失敗・CRLF正規化として固定し、AC-4を具体corpusへ更新。**P1 (session置換semantics)**: 新規package生成開始をsession置換確認でgateし (承認なしの生成・無言無効化経路を排除)、未送信packageの送信前確認取消は当該sessionの明示的失効 (`ExportSessionStore.invalidate`) とする設計へ改訂。「E1送信済み → E2生成取消 → E1 import」representative scenarioとAC-13を追加。**Re-anchor**: #204はaccepted (6th review Approve) かつ実装済み、#203は実装済み。全仮称記述を実名へ解消 (tier、12 failure classとV1での `CAPABILITY_UNSUPPORTED` 到達不能、TTL 24時間、content limits、固定6 capability、usageSignals bucket projection、structural `sourceContextDigest`、`intentPreferences` planner投影)。open questions (transport選定、instruction文言・embedding、redacted surrogate、#203 usageSignals、instruction言語、size制約) をDecisions 1–5として解消し、statusをdraft → proposedへ更新 (owner acceptance待ち)。
- 2026-09-16 (2nd): ChatGPT re-review "Request changes" (snapshot `9b1cd9d` 基準。前回3指摘は解消確認済み) への対応revision。**P1 (import envelope上限)**: framingがmarker前後の自由文を許容した結果、#204 payload上限 (128 KiB) の手前で巨大入力が無制限に処理され得る resource safety境界を閉じた。import text全体への **envelope上限 1 MiB (UTF-8 byte基準、#205所有・#204 `MAX_INTENT_BYTES` とは別契約)** をDecision 6として固定。貼付付け・clipboard・fileの全経路へ同一適用・可能な経路では全量保持/正規化の前にfail・超過は #205側typed失敗 `INPUT_OVERSIZE` (zero-write、UX案内は #204 `OVERSIZE` と区別) とし、scenario・AC-4 (e)・AC-5 (16種失敗表示)・test oracle (巨大prefix/suffix + 小valid payload、marker不在巨大入力、境界値) へ反映。Decision 5も #204 payload上限と #205 envelope上限を区別する文言へ修正。**P2 (SessionExportReconstructorのseam特定)**: export生成とimport検証の再構築が共有する canonical capture → `ExportInputs` 導出の単一adapter (既存 `ProductionOrganizationInputComposer` / `FullTargetSetMaterializer` のfull-target composition経由) をData and stateへ明記。#228 scope selectionはrun内概念でありexchange flowでは関与しないことを固定。planへ parity contract test (build → save → 同一structural stateからのreconstructで ref/role/mobility/grid 一致、signal/label変更の無影響、構造変化で `CONTEXT_STALE` 収束) を追加。
- 2026-09-16 (3rd): **accepted**。ChatGPT 2nd re-review Approve ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/205#issuecomment-5683891236)、head `0e4f154cfd1c3dec083415eaa17cf9789d5eb588`) を受け、statusをproposed → acceptedへ更新。approving reviewはnon-blocking implementation noteとして (1) UTF-8 byte上限判定のallocation-bounded実装 (fast reject + bounded byte count。実装時にplanへ反映済み)、(2) review-responseコメントのfull SHA誤記 (short SHA `0e4f154` は一致) を残した。canonical docs (CONTEXT.md用語・DESIGN.md module記載・requirements.md FR-017 status) を同じcommitで更新。

## References

- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (**accepted**・実装済み。`lawnchair/src/app/lawnchair/organizer/personalization/` に `ContextExportBuilder` / `ContextExportCodec` / `IntentCodec` / `IntentValidator` / `ExportSessionStore` / `IntentPlannerAdapter` / `SourceContextIdentity` / `RandomIdAllocator` 等、`organizer/integration/AndroidExportSessionStore` が存在)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (scope-composed run、target追加はuser明示選択のみ)
- [Spec 235: widget strategy placement](../235-widget-strategy-placement/spec.md) (semantic placement role、widget span不変)
- Issue #203 (usage signals、**実装済み** PR #321), Issue #206 (managed AI、OPEN)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [requirements.md](../../docs/product/requirements.md) (FR-017、#204受入済み), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md)
