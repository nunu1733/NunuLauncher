---
issue: "#205"
status: draft
requirements: []
risk:
  - privacy
  - layout-data
updated: 2026-09-15
---

# External Agent Exchange: ChatGPT/Gemini等によるOrganizer personalization

> Status: draft — 本specは #204 (Context / PersonalizedIntent exchange contract) を **必須依存** とする。#204の契約は本draft時点 (2026-09-15) でも未acceptであり、origin/mainには未取り込み (draft snapshotは `origin/issue-204-spec-plan` branch。2026-09-14のowner re-review対応revision `12f773ad61` がmain `397d3fd957` へre-anchor済み、再review待ち。受入必須gate Q1/Q3/Q4 (planner投影、capability set、content limits/session TTL) が未解決)。#204 draftはexport sessionをdurable化しており、本specのprocess death耐性はこれに依存する (「Data and state」参照)。本specは #204 のschema詳細 (field名、tier名、failure分類) を **確定事実として扱わない**。本文中の #204 draft由来の固有名 (`EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS`、`SESSION_EXPIRED` 等のfailure class) は全てdraft時点でのプレースホルダであり、#204受入時に accepted契約へ合わせて再確認・改訂する。**exchange framing (外部agent返答の包み方)、process recreation後のrun再構築semantics、Pre-send Disclosureとpackage生成の順序契約は本specが所有する** (「Domain language」「Behavior scenarios」参照)。

## Problem

Organizer personalizationをAIで行う場合、アプリ内にagent frameworkを実装しなくても、ChatGPT / Gemini等の汎用チャット/agent環境へpersonalization contextを渡し、外部で調査・複数stepの検討・ユーザーとの対話を行った結果をsemantic intentとして戻せれば、高品質な個別整理が実現できる。特に端末に聞き慣れないアプリがある場合、外部agentはWeb検索でアプリ用途を確認した上でgrouping / priority / affinityを判断でき、one-shot分類より広い能力を持つ。

一方で、(a) この交換経路が存在しないためユーザーは外部AIに整理案を出してもらう標準手段がなく、(b) Launcher内部IDやraw usage/layout情報をそのままpromptへ出し、自由文の返答を直接layout変更へ変換すれば、privacy / prompt injection / safety / reproducibilityが壊れる。

## Outcome

NunuLauncherがagentそのものを内蔵せず、(1) #204契約の `PersonalizationContextExportV1` とagent向けinstructionを明示分離したexchange packageをユーザー確認の上で外部へ送出でき、(2) 外部agentが返した `PersonalizedIntentV1` を厳格にparse・検証し、(3) 既存 #182 planner → #194/#195 preview → confirm → apply 経路のみで適用できる、正式な External Agent Exchange workflowを提供する。外部agentは調査・推論・対話を自由に行ってよいが、NunuLauncherが受け入れる成果物は #204 のversioned intentのみである。

## Scope

- Exchange package (agent向けinstruction + `PersonalizationContextExportV1` data) の生成。instructionとdataの明確な分離構造。
- Export transportの選定: clipboard copy / Android Share Sheet / file (text) export。初回方式の比較と決定。
- Export前の外部送信内容確認UX (privacy tier、redacted / label-inclusive mode の扱い)。確認は **生成済みexchange package** に対して行われ、確認対象と送信対象の同一性が保証される順序契約を含む。
- Import transport: clipboard paste / text・file import / Android share-back intent。初回方式の比較と決定。
- Importされたtextからの厳格な `PersonalizedIntentV1` 抽出。**exchange framing (返答text内でintent本体を囲むmarker・構造と、その抽出規則) は本spec (#205) が所有し、ここで確定する**。抽出されたpayloadのschema検証は #204 validatorが所有する。framing不適合は #205側のtyped parse失敗であり、#204のvalidation failureと区別される。
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
- #204 validator・planner接続adapter自体の実装 (#204側の成果物)。
- Diagnostics journalへの個人情報 (label等) の記録。既存 [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 規則を変更しない。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映。#204の用語案と合わせて調整する)。

**外部エージェント交換 (External Agent Exchange)**:
`PersonalizationContextExportV1` を外部chat/agent環境へ持ち出し、 `PersonalizedIntentV1` として持ち帰る、NunuLauncherが提供するuser-driven workflow全体。agent実行部はNunuLauncherの外にある。
_Avoid_: AI連携 (provider依存を想起させる)、integration (#206 managed pathとの混同)

**交換パッケージ (Exchange Package)**:
1回の外部agent受け渡しに使う、agent向けinstruction部と `PersonalizationContextExportV1` data部を明示分離した単一text (またはfile) 表現。promptとdataの区別が機械的に判別できる構造を持つ。
_Avoid_: prompt (data部も含む曖昧な呼称)、backup

**送信前確認 (Pre-send Disclosure)**:
exchange packageをclipboard・share・file等でapp外へ出す直前に、外部へ出る情報の種別と内容をユーザーに提示し、明示的な同意を得る確認step。確認なしに外部送信経路を開かない。
_Avoid_: privacy policy (静的文書との混同)

**持ち帰りIntent取り込み (Intent Import)**:
外部agentの返答textから、exchange framingの内側にある `PersonalizedIntentV1` のみを厳格に抽出し、#204 validatorへ渡す取り込みstep。曖昧なJSON拾い上げやpartial解釈を行わない。
_Avoid_: auto-apply、paste-to-layout

**交換フレーミング (Exchange Framing)**:
外部agentの返答text内で、最終成果物である `PersonalizedIntentV1` 本体を囲むことをagentに指示するmarker・構造と、そこから本体を一意に抽出する規則。本spec (#205) が所有して定義する。framing内のpayloadのschema解釈は行わない (それは #204)。framingの不成立・曖昧性は #205側のtyped parse失敗である。
_Avoid_: schema (payload本体の契約は #204)、system prompt (instruction部の一部でありframing抽出規則と混同しない)

## Behavior scenarios

> field名・schema文字列は #204 受入時に確定するものへのプレースホルダである。

### Scenario: export package生成と送信前確認

Given manual organization surfaceがrun操作非activeの状態でuserが外部agent利用を選択し、#204契約のcontext export生成が利用可能である、
When privacy modeを選択した上でexchange package生成を実行する、
Then packageはinstruction部 (goal、許可事項、遵守事項、期待返答形式) とdata部 (`PersonalizationContextExportV1`) に機械的に分離された **単一のimmutableな値** として生成され、
And 既定のdata部はraw package名・内部ItemId/DB row ID・opaque profile identifier・raw usage ms/timestamps・diagnostics/recovery metadataを含まず、
And 送信前確認画面は **生成済みのpackage** を対象に外部へ出る情報の種別と内容を提示し、明示的な送信承認なしにclipboard/share/file経路のデータが出ることはない。

### Scenario: disclosure対象とtransport対象の同一性

Given 生成済みexchange packageに対して送信前確認が表示されている、
When userが明示的に送信を承認する、
Then 承認後に実行されるtransport (clipboard copy / Share Sheet / file export) は、確認画面に提示されたpackageと **同一のimmutableな値** をそのまま送出する (承認後に別内容のpackageへ差し替えられる経路が存在しない)、
And 確認画面でprivacy modeの変更等が行われた場合はpackageを再生成し、送信前確認からやり直す (旧packageが送信されることはない)、
And 確認の前に外部送信経路が開かれることはない (確認と送信の順序は逆転しない)。

### Scenario: privacy mode選択

Given exchange package生成前の設定step、
When userがredacted mode (既定) とlabel-inclusive modeを選択できる、
Then 選択したmodeは生成されるpackageのdata部metadataとして明示され、label-inclusive選択時はapp label・folder titleが外部へ出る旨を送信前確認画面が明示する、
And 既定はredacted modeであり、mode変更時はpackageが再生成され送信前確認からやり直す。

> #204 draftではこの2 modeは privacy tier `EXTERNAL_REDACTED` (既定候補) / `EXTERNAL_WITH_LABELS` (明示選択時) として命名されている (内部engine向け `LOCAL_FULL` は本workflow対象外)。tier名は#204受入まで仮称として扱う。

### Scenario: transport実行

Given 確認済みexchange package、
When userが選択したtransport (clipboard copy / Share Sheet / file export) を実行する、
Then clipboard失敗・share target不在・file書込失敗はいずれもtypedな失敗としてuserへ通知され、silent失敗しない、
And export操作自体はlayout DBへ一切書き込まない。

### Scenario: 外部agentの自由な調査

Given 外部agentがexchange packageを受領、
Then packageのinstruction部は、unknown appについてのWeb検索、複数source比較、usage/current groupingのpreference signalとしての考慮、必要時のuserへの追加質問を禁止しておらず、
And 最終回答を `PersonalizedIntentV1` として返すよう要求する、
And NunuLauncherは中間思考・tool実行を再現・検証しない (最終intentのみを検証する)。

### Scenario: importの厳格な抽出

Given userが外部agentの返答textをimport操作 (clipboard paste / text入力 / file選択 / share-back) で渡す、
When 取り込みを実行する、
Then 本specが定義するexchange framingの内側にある完全な `PersonalizedIntentV1` 表現のみを抽出対象とし、marker不在・複数候補・framing境界の破れは #205側のtyped parse失敗として扱う、
And 抽出したpayloadのschema解釈は行わず、そのまま #204 validatorへ渡す、
And 曖昧にJSONらしきものを拾ってpartial applyする経路は存在しない。

### Scenario: validation reject

Given 抽出されたintentが #204 validatorでmalformed / unknown schemaVersion / out-of-scope ID / oversize / 禁止内容 (lock移動・座標直接指定) と判定される、
When 検証結果を表示する、
Then typed failure種別ごとにuserが理解できる説明を表示し、zero-write (selection/layout/planning入力を一切変更しない) であり、
And userは修正した返答を再importできる。

> #204 draft (2026-09-14 re-review対応revision) はtyped failure classとして `SCHEMA_MISMATCH` / `EXPORT_MISMATCH` / `SESSION_EXPIRED` / `CONTEXT_STALE` / `OVERSIZE` / `UNKNOWN_REF` / `DUPLICATE_REF` / `INCOMPLETE_COVERAGE` / `INVALID_ENUM` / `FORBIDDEN_CONTENT` / `MOBILITY_CONTRADICTION` (per-item mobility projection (`MOVABLE` / `CONDITIONAL` / `FIXED` + `fixReason`) との意味矛盾) / `CAPABILITY_UNSUPPORTED` を定義し、未宣言capabilityはV1ではreject-by-default、intentは `itemIntents` refs ∪ `unresolvedRefs` が全addressable refsの分割であることを要求している。分類名・個数は#204受入まで仮称として扱い、UIの失敗説明はこの分類に一対対応させる。

### Scenario: 古いexport宛のintent

Given export E1を生成後にuserが再生成してexport E2が存在し、E1宛のintentがimportされる、
Then #204のexport一致検証によりrejectされ、userに再生成済みexportへ対応する旨が表示される。

> #204 draftではこの失敗は `EXPORT_MISMATCH` failure classに対応し、activityなexport sessionは同時に1つ (新規export生成が既存sessionを無効化するsingle-active-session規則) である (受入まで仮称)。

### Scenario: process deathを跨ぐ往復

Given export Eの生成後、userが外部agentアプリへ切り替え、その滞在中にLauncher processが破棄される、
When userが戻り、E宛のagent返答をsession有効期限内にimportする、
Then 抽出と検証は #204のdurableなexport session経由でref解決・export一致を行い、再exportなしで成立する、
And NunuLauncher側でexchange package本文・返答textを永続化していないことが壊れの原因にならない (返答text自体を失った場合の回復は、外部agent側の再copyまたは再exportである)、
And session失効後にimportした場合はtypedな失敗として拒否され、再exportが必要である旨が表示される。

> #204 draftではsession失効は `SESSION_EXPIRED` failure classに対応する (受入まで仮称)。session (exportId、ref↔内部ID map、privacy tier、session-localな `sourceContextDigest`、生成・失効時刻) のdurable保持は #204側が所有し、本workflowは追加の永続化を持たない。

### Scenario: process recreation後のrun再構築

Given process deathを跨いでimportが抽出・検証を通過した、
When validated intentを既存のmanual organization flowへ接続する、
Then 接続は **fresh runの再構築** として行われ、既存run状態の復元は行わない。新しいRunIdを発行し、run state machineはIdleから通常flow (missing-app detection/selectionを含む) を辿る、
And #228のmissing-app selection・scope構成はprocess-local非永続が既存の不変条件であるため復元せず、通常flowの再選択に委ねる、
And intentのsource bindingは2段で検証される: import時のsource context一致検証 (#204、`CONTEXT_STALE` 仮称) と、fresh runのplanning/apply時の既存capture revision stale check。import成立後・planning前にhomeが変化した場合は既存Stale pathでzero-write終了する、
And V1ではexchangeのexport・import導線はmanual run操作が非activeのときにのみ提示される。activeなrun操作中に到達したimport (share-back受信等) でvalidated intentからのrun開始が既存single-active-operation gateで拒否された場合は、typedな案内 (run終了後の再import) でzero-write終了し、validated intentを保持しない。

### Scenario: 往復中のhome変更

Given export Eの生成後、外部agentの検討中にuserがhomeで配置・lock・分類等を変更した、
When E宛のintentがimportされる、
Then source binding検証によりrejectされ、既存selection/layout/planning入力は不変であり、
And userに再exportが必要な旨が表示される (V1ではrebaseしない)。

> #204 draftではこの失敗は `CONTEXT_STALE` failure classに対応する (受入まで仮称)。

### Scenario: previewと確認の省略禁止

Given importされたintentがvalidationを通過した、
When plan生成が行われる、
Then 既存の #194 plan preview → #195 explicit confirmation → 既存transactional apply/recovery pathのみを通り、agentの主張 (「この配置が最適」等) でconfirmationを省略しない、
And 別のintentがacceptされた場合、既存previewはsilentに再解釈されず無効化される (#204のregeneration semantics)。

### Scenario: prompt injectionの閉じ込み

Given app label・folder title・外部agent応答text・agentが参照したWeb source等に命令文 ("ignore previous instructions" 等) が含まれる、
Then それらはinstructionではなくdataとして扱われ、外部agentがadversarial dataに影響され得ること自体は前提とする (agentのinstruction遵守を信頼の前提にしない)、
And safety境界は「**adversarial dataに影響された返答であっても、unsafeなmutationに到達しない**」ことである。すなわちいかなる応答も #204 validatorのschema・allow-list・closed enum・coverage検証を通過するまで受理されず、通過したintentでもplannerのlock/bounds/overlap制約は不変であり、
And validation・検証に至らなかった入力はzero-writeで破棄される。

### Scenario: accessibility・環境失敗

Given TalkBack / Switch Access / large font環境、またはclipboard利用不可・share target不在の環境、
When export/import workflowを操作する、
Then 確認画面・失敗表示はaccessibility対応され (focus順、読み上げlabel、font scaling)、代替transportへの誘導が提示される。

## Data and state

- 読むdata: #204契約のcontext export生成に必要なcanonical入力 (captured `LayoutSnapshot`、解決済み分類、lock状態、#203 signal snapshot (利用可能時))。本specはこれらの再定義をしない。
- exchange packageは生成時に完了するimmutableな値であり、送信前確認はこの生成済み値に対して行われる。確認対象とtransport対象の同一性は同一値の受渡しで構造的に保証する (package本文の永続化もdigest等の追加永続化もしない)。privacy mode変更等で再生成が起きた場合は確認からやり直す。
- import成立後のrun接続はfresh run再構築のみであり、本workflowはrun stateの永続化・復元を持たない。exchange導線の提示はmanual run操作非active時に限定する (V1)。validated intentからのrun開始は既存single-active-operation gateを通る。
- 永続化の分担: 本workflow ( #205側) はexchange package本文・import済み返答textを永続化しない。**pendingなexport identityとexport-scoped ref↔内部ID mappingのdurable保持は #204契約のexport sessionが所有する** (#204 draft: app-private・backup対象外・期限付き。#205の往復flowでは外部アプリ滞在中にLauncher processがkillされることが通常に起こり得るためdurable化された)。本workflowはprocess deathを跨ぐimportを #204のexport session経由でのみ成立させ、独自の永続化経路を追加しない。session失効・不在時のimportはtypedな失敗であり、回復は再exportである。
- export再生成は新規export identityとなり、#204 draftのsingle-active-session規則 (activityなsessionは1つ、新規生成が既存sessionを無効化) に従う。既存previewの無効化規則も #204 に従う。
- layout変更は既存run (snapshot → plan → preview → confirm → apply) のみで行われ、本workflowは新しいDB書込経路を作らない。
- #204 draft由来の制約 (受入まで仮): export data部の対象種別は #235 のsemantic placement role族 (`APP_OR_SHORTCUT` / `FOLDER` / `WIDGET`) に揃えられ、per-itemのmobility projection (`MOVABLE` / `CONDITIONAL` / `FIXED` + `fixReason`、仮称) がexportに含まれ、intentの意味検証はこれに対して行われる (`MOBILITY_CONTRADICTION` 仮称)。widgetのspanはexportに含まれずintentからも指定できない。`APP_PAIR` / `SHORTCUT_LEGACY` / `Unknown` はintent addressable対象外であり、app pairとlegacy shortcutはconstraint集計としてのみ投影される。exportの `ref` は生成ごとの乱数であり、canonical状態のfingerprint (`sourceContextDigest`) はsession-localでexport文書・intent応答のいずれにも現れない。intentは既存RunMode (`FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization`) を増やさず、整理対象の追加も行わない (追加候補は #228 のuser明示選択composition inputのみ)。instruction部はこれらの制約と矛盾する約束 (「widget sizeを提案してよい」等) を含んではならない。

## Permissions, privacy, and security

- 追加permissionなし (clipboardはframework API、Share Sheetは既存intent機構、file exportはSAF/user選択)。
- 外部送信はuserの明示操作 (確認画面を経たcopy/share/save) のみ。network通信は本workflowに含まれない。
- 既定exportから除外: raw package名、内部ItemId/DB row ID、opaque profile identifier、raw usage ms/timestamps、diagnostics/recovery metadata (Issue本文・#204 draftと整合)。
- app label・folder titleはuntrusted dataとして扱う。instruction/data分離 (package構造が機械的に保証することを本specが要求) は外部agentへの誘導を改善する **risk低減** であって、agentがdata内の命令文に影響されないことの保証ではない。safetyの正本境界は「いかなる返答も #204 validatorのschema・allow-list検証とplanner制約を越えられない」こと (fail-closed) に置く。
- clipboard内容・import textをdiagnostics journalへ記録しない (organizer-diagnostics.mdの個人情報規則)。

## Accessibility and localization

- 送信前確認・import失敗・validation reject・transport失敗の各UIはTalkBack読み上げ、Switch Access操作、large font対応を必須とする。
- UI copyは日本語を正本とする他organizer surfaceに合わせ、instruction部のagent向け文言は英語を基本とする (外部agentの認識精度。要検証)。

## Acceptance criteria

前提: #204 contractがacceptedになっていること。未acceptの間、本specの実装は開始しない。

- [ ] AC-1: exchange package生成からimport・previewまでのend-to-end UXが定義され、instruction/data分離構造が機械的に検証できる。
- [ ] AC-2: 既定exportがraw package名・内部ID・profile identifier・raw usage/timestamps・diagnostics metadataを含まないことがcontract testで検証される。
- [ ] AC-3: 送信前確認なしにclipboard/share/file経路でデータが出ないことが検証される。redacted / label-inclusive modeの扱いが決定され、label-inclusive時の明示開示がある。
- [ ] AC-4: importが本specが定義するexchange framing内の完全な `PersonalizedIntentV1` のみを抽出し、曖昧入力・framing違反を #205側のtyped parse失敗としてzero-write処理することがtestされる。
- [ ] AC-5: malformed / unknown schema / out-of-scope ID / 禁止内容が #204 validator経由でzero-write rejectされ、失敗種別がuserに説明されることがtestされる。
- [ ] AC-6: import後も #194 preview + #195 explicit confirmationが必須であり、agent outputの直接適用・confirmation省略経路が存在しないことが検証される。
- [ ] AC-7: untrusted app/folder label・agent応答・agentが参照した外部sourceのprompt injectionがthreat modelとtestに含まれる。test oracleは外部agentのinstruction遵守を前提とせず、fail-closed (schema/allow-list reject、zero-write、planner制約不変、影響された返答でもunsafe mutationへ到達しない) を中心とする。
- [ ] AC-8: 特定provider appのprivate API / UI automationに依存しないplain-text exchangeであることが確認される (import/exportの対象がversioned text schemaのみ)。
- [ ] AC-9: clipboard失敗・share target不在・file失敗・large font・TalkBack・Switch AccessのUX evidenceがある。
- [ ] AC-10: physical-deviceでChatGPT/Gemini等を用いたrepresentative workflow evidence (export → 外部agentへのapp切替 → import → preview → confirm) がある。
- [ ] AC-11: process recreation後のimportが成立する (export → 外部アプリ滞在中のprocess死 → session有効期限内のimportが再exportなしで成功)。session失効後のimportはtypedな失敗でrejectされ、失敗説明に再exportの案内がある。pendingなexport identity・ref mappingの復元が #204のexport sessionに一元化され、本workflowが独自の永続化を持たないことが検証される。加えて、process recreationを挟んだ **import成功 → fresh run再構築 (新RunId、通常flowでのselection再選択) → preview表示** までのintegration evidenceがある。
- [ ] AC-12: 送信前確認が生成済みpackageに対して行われ、確認対象とtransport対象が同一immutable valueであること (確認後の差し替え・確認前の送信経路の不在) 、privacy mode変更等の再生成時に確認からやり直すことがtestされる。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | package生成moduleのunit/contract test (structure) + 手動UX確認 |
| AC-2 | export生成のcontract test (#204 test suiteと連携) |
| AC-3 | UI test + 送信経路のinstrumentation test |
| AC-4 | import parserのunit test (framing違反・曖昧入力・部分JSONのreject corpus) |
| AC-5 | #204 validator failure分類の回帰test + import UI test |
| AC-6 | integration test (import→plan→preview→confirmの経路強制) + code review |
| AC-7 | security test corpus (injection label fixture)。oracleはfail-closed中心 (影響された応答がunsafe writeに到達しないこと、zero-write、planner制約不変) |
| AC-8 | 依存review (private API/UI automationの不使用) + plain text schema test |
| AC-9 | accessibility・環境失敗の手動/instrumentation evidence |
| AC-10 | physical-device evidence記録 (docs/assessment/ またはissue) |
| AC-11 | session store seam経由のprocess recreation simulation test (#204 suiteと連携) + process recreationを挟んだ import → fresh run再構築 → preview のintegration test + physical-device app切替evidence (AC-10と兼ね可) |
| AC-12 | package同一性のunit test (composer→transportの同一値受渡し) + 確認画面順序のUI test (再生成時の確認やり直し、確認前送信の不在) |

## Open questions (未決定事項 — 実装前に解決が必要)

1. **#204 acceptance**: 本specの全ACは#204受入を前提とする。#204受入時にtier名・field名・failure分類・session規則 (TTL等) を本specへ反映する。#204 draft (2026-09-14 re-review対応revision、branch `issue-204-spec-plan` commit `12f773ad61`) はtier (`EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS`)、12のfailure class (process death対策のdurable export sessionと `SESSION_EXPIRED` / `CONTEXT_STALE` / `INCOMPLETE_COVERAGE` / `MOBILITY_CONTRADICTION` を含む)、session-localな `sourceContextDigest` と乱数 `ref` 割当 (cross-export unlinkability)、single-active-session、coverage不変条件、reject-by-default V1を固定しているが、未accept・再review待ち (受入必須gate Q1/Q3/Q4未解決) であるため本specはこれを仮称としてのみ参照する。
2. **初回transportの決定**: clipboard copy / Share Sheet / file export の比較基準 (Android上の摩擦、clipboard size制限、share target有無) と初回採用組み合わせ。
3. **exchange framingの具体形式**: 返答text内で `PersonalizedIntentV1` を囲むmarker・構造の具体形。**所有は本spec (#205)** であり、#204には含まれない (#204はpayload本体のschemaと検証のみを所有)。framing文案の確定には #204受入時のpayload field名確定を入力とする。agentがframing指示に従わない場合のUX (typed parse失敗と再依頼案内) は本spec範囲で設計する。
4. **instruction部の文言・schema embedding方式**: Issue本文の例を起点とした具体文言、framing指示を含む返答形式の指定、instruction内へschemaをどう埋め込むか。
5. **redacted modeのlabel surrogate表現**: **#204 draft側で解決済み (2026-09-13 revision、受入まで仮)**。V1ではsurrogate (hash含む) を生成せず、`EXTERNAL_REDACTED` は自由文class全体を除外する。本specのredacted modeはこれに従い、surrogate設計を独自に持たない。
6. **#203 usageSignalsの取り扱い**: #203 (OPEN) のsignal契約確定後、export packageのusage projection表現を確定する (#204 draftではcoarse bucket + tier制御の方向)。
7. **instruction部言語**: 英語固定か、user locale追従か。
8. **rate/size制約**: clipboard・share textのsize上限に対する巨大layout (数百item) の扱い。

## Change history

- 2026-09-10: Draft created for #205. External Agent Exchange workflow spec: exchange package (instruction/data分離)、export/import transport、送信前確認、厳格import、#194/#195 preview必須。#204 acceptanceを明示的依存とする。
- 2026-09-13: Re-entry re-anchor。main `6b6bf8dd` → `f9afd8bfde` をmerge (organizer差分: #228 scope-composed run/missing-app selection、#235 widget strategy placement/semantic placement role、#271/#288 diagnostics、requirements FR-016 implemented化)。#204 draftが `f9afd8bfde` baselineへre-anchorされたことを受け、draft段階の固有名 (privacy tier名、failure class名、widget span非投影、reject-by-default V1、RunMode不変・対象追加なし) を仮称として明記。未解決のproduct decisionは解消していない (status: draft維持)。
- 2026-09-14: Owner review "Request changes" (2026-09-13、snapshot `517adbe4` 基準) への対応revision。main `c5274b5d0d` をmergeしてre-anchor。**P1 (process death)**: 往復flowでのprocess recreation後importをbehavior scenario・AC-11へ追加し、pending export identity・ref mappingのdurable保持を #204 draftのexport sessionに一元化する責務分担へ改訂 (本workflowは独自永続化を持たない)。**P1 (framing所有)**: exchange framingの所有を #205 として確定し (#204はpayload本体のschema/検証のみ)、Domain language・Scope・AC-4・open questionsへ反映。**P2 (prompt injection)**: scenario・AC-7・security test oracleを「外部agentが影響されない」ではなく「adversarial dataに影響された返答でもunsafe mutationに到達しない」(fail-closed) を中心へ改訂。加えて #204 draftの2026-09-13 review対応revision (`324e6182ae`、durable export session・single-active-session・coverage不変条件・`APP_PAIR`/`SHORTCUT_LEGACY` 対象外・V1 surrogate廃止) を仮称として反映し、「往復中のhome変更」(`CONTEXT_STALE` 仮称) scenarioを追加。未解決のproduct decisionは解消していない (status: draft維持)。
- 2026-09-15: Owner re-review "Request changes" (snapshot `02f7f90` 基準) への対応revision。main `397d3fd957` をmergeしてre-anchor (main差分は #298/#299/#315 のcapture/restore/diagnostics系で、本specの統合点への構造変更なし)。**P1 (process death後のrun再構築)**: import成立後の接続を **fresh run再構築** (新RunId、既存single-active-operation gate、通常flowでの #228 selection再選択) と固定し、既存run状態の復元を行わないことを明示。stale bindingの2段構成 (import時 `CONTEXT_STALE` + 既存planning/apply時revision stale check) と、V1でのexchange導線提示条件 (run非active時のみ) をscenario化し、AC-11をprocess recreationを挟んだ import → fresh run → preview のintegration evidenceへ拡張。**P2 (disclosure順序)**: `privacy mode選択 → package生成 → Pre-send Disclosure → explicit send → 同一packageをtransport` の順序契約へ修正。packageを生成時に完了するimmutableな値と定義し、確認対象とtransport対象の同一性を同一値の受渡しで保証 (digest等の追加永続化はしない)、再生成時は確認からやり直すことをscenario・AC-12化。あわせて #204 draftの2026-09-14 re-review対応revision (`12f773ad61`、`sourceContextDigest` のsession-local化・乱数 `ref` 割当・per-item mobility projection・`MOBILITY_CONTRADICTION` 追加で12 failure class) を仮称として反映。#204は依然draft (再review待ち・gate Q1/Q3/Q4未解決) のため実装blockerは不変 (status: draft維持)。

## References

- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205)
- Issue #204 (contract、draft: branch `origin/issue-204-spec-plan` `specs/204-ai-personalization-context-intent-contract/` — 2026-09-14 owner re-review対応revision commit `12f773ad61`、main `397d3fd957` へre-anchor済み。durable export session・intent schema・validator・session-localな `sourceContextDigest`・per-item mobility projectionを所有。未accept・origin/main未取り込み・再review待ち・受入必須gate Q1/Q3/Q4未解決)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (scope-composed run、target追加はuser明示選択のみ)
- [Spec 235: widget strategy placement](../235-widget-strategy-placement/spec.md) (semantic placement role、widget span不変)
- Issue #203 (usage signals、OPEN), Issue #206 (managed AI、OPEN)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [requirements.md](../../docs/product/requirements.md), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md)
