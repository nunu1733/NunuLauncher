---
issue: "#205"
status: draft
requirements: []
risk:
  - privacy
  - layout-data
updated: 2026-09-13
---

# External Agent Exchange: ChatGPT/Gemini等によるOrganizer personalization

> Status: draft — 本specは #204 (Context / PersonalizedIntent exchange contract) を **必須依存** とする。#204の契約は本draft時点 (2026-09-13) でも未acceptであり、origin/mainには未取り込み (draft snapshotは `origin/issue-204-spec-plan` branch、baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` へre-anchor済み)。本specは #204 のschema詳細 (field名、tier名、failure分類) を **確定事実として扱わない**。本文中の #204 draft由来の固有名 (`EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS`、`EXPORT_MISMATCH` 等のfailure class) は全てdraft時点でのプレースホルダであり、#204受入時に accepted契約へ合わせて再確認・改訂する。

## Problem

Organizer personalizationをAIで行う場合、アプリ内にagent frameworkを実装しなくても、ChatGPT / Gemini等の汎用チャット/agent環境へpersonalization contextを渡し、外部で調査・複数stepの検討・ユーザーとの対話を行った結果をsemantic intentとして戻せれば、高品質な個別整理が実現できる。特に端末に聞き慣れないアプリがある場合、外部agentはWeb検索でアプリ用途を確認した上でgrouping / priority / affinityを判断でき、one-shot分類より広い能力を持つ。

一方で、(a) この交換経路が存在しないためユーザーは外部AIに整理案を出してもらう標準手段がなく、(b) Launcher内部IDやraw usage/layout情報をそのままpromptへ出し、自由文の返答を直接layout変更へ変換すれば、privacy / prompt injection / safety / reproducibilityが壊れる。

## Outcome

NunuLauncherがagentそのものを内蔵せず、(1) #204契約の `PersonalizationContextExportV1` とagent向けinstructionを明示分離したexchange packageをユーザー確認の上で外部へ送出でき、(2) 外部agentが返した `PersonalizedIntentV1` を厳格にparse・検証し、(3) 既存 #182 planner → #194/#195 preview → confirm → apply 経路のみで適用できる、正式な External Agent Exchange workflowを提供する。外部agentは調査・推論・対話を自由に行ってよいが、NunuLauncherが受け入れる成果物は #204 のversioned intentのみである。

## Scope

- Exchange package (agent向けinstruction + `PersonalizationContextExportV1` data) の生成。instructionとdataの明確な分離構造。
- Export transportの選定: clipboard copy / Android Share Sheet / file (text) export。初回方式の比較と決定。
- Export前の外部送信内容確認UX (privacy tier、redacted / label-inclusive mode の扱い)。
- Import transport: clipboard paste / text・file import / Android share-back intent。初回方式の比較と決定。
- Importされたtextからの厳格な `PersonalizedIntentV1` 抽出 (accepted framing / exact schema extraction) と、 #204 validatorへの受け渡し。
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
外部agentの返答textから、定められたframing内の `PersonalizedIntentV1` のみを厳格に抽出し、#204 validatorへ渡す取り込みstep。曖昧なJSON拾い上げやpartial解釈を行わない。
_Avoid_: auto-apply、paste-to-layout

## Behavior scenarios

> field名・schema文字列は #204 受入時に確定するものへのプレースホルダである。

### Scenario: export package生成と送信前確認

Given manual organization runにおいてuserが外部agent利用を選択し、#204契約のcontext export生成が利用可能である、
When exchange package生成を実行する、
Then packageはinstruction部 (goal、許可事項、遵守事項、期待返答形式) とdata部 (`PersonalizationContextExportV1`) に機械的に分離されて構成され、
And 既定のdata部はraw package名・内部ItemId/DB row ID・opaque profile identifier・raw usage ms/timestamps・diagnostics/recovery metadataを含まず、
And userは送信前に外部へ出る情報の確認画面を通過し、確認なくclipboard/share/file経路のデータが出ることはない。

### Scenario: privacy mode選択

Given exchange package生成時、
When userがredacted mode (既定) とlabel-inclusive modeを選択できる、
Then modeはdata部のmetadataとして明示され、label-inclusive選択時はapp label・folder titleが外部へ出る旨を確認画面が明示する、
And 既定はredacted modeである。

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
Then 定められたaccepted framing (markerまたは構造) の内側にある完全な `PersonalizedIntentV1` 表現のみを抽出対象とし、framing外・marker不在・複数候補の曖昧性は明示的なparse失敗として扱う、
And 曖昧にJSONらしきものを拾ってpartial applyする経路は存在しない。

### Scenario: validation reject

Given 抽出されたintentが #204 validatorでmalformed / unknown schemaVersion / out-of-scope ID / oversize / 禁止内容 (lock移動・座標直接指定) と判定される、
When 検証結果を表示する、
Then typed failure種別ごとにuserが理解できる説明を表示し、zero-write (selection/layout/planning入力を一切変更しない) であり、
And userは修正した返答を再importできる。

> #204 draftはtyped failure classとして `SCHEMA_MISMATCH` / `EXPORT_MISMATCH` / `OVERSIZE` / `UNKNOWN_REF` / `DUPLICATE_REF` / `INVALID_ENUM` / `FORBIDDEN_CONTENT` / `CAPABILITY_UNSUPPORTED` を定義し、未宣言capabilityはV1ではreject-by-defaultとしている。分類名・個数は#204受入まで仮称として扱い、UIの失敗説明はこの分類に一対対応させる。

### Scenario: 古いexport宛のintent

Given export E1を生成後にuserが再生成してexport E2が存在し、E1宛のintentがimportされる、
Then #204のexport一致検証によりrejectされ、userに再生成済みexportへ対応する旨が表示される。

> #204 draftではこの失敗は `EXPORT_MISMATCH` failure classに対応する (受入まで仮称)。

### Scenario: previewと確認の省略禁止

Given importされたintentがvalidationを通過した、
When plan生成が行われる、
Then 既存の #194 plan preview → #195 explicit confirmation → 既存transactional apply/recovery pathのみを通り、agentの主張 (「この配置が最適」等) でconfirmationを省略しない、
And 別のintentがacceptされた場合、既存previewはsilentに再解釈されず無効化される (#204のregeneration semantics)。

### Scenario: prompt injectionは無効

Given app label・folder title・外部agent応答textに命令文 ("ignore previous instructions" 等) が含まれる、
Then exchange packageではそれらはdata部のdata fieldとしてのみ存在し、instruction部の規則を上書きできず、
And 返答側の命令的textは#204 validatorのschema/allow-list検証でrejectされ、
And 通過したintentでもplannerのlock/bounds/overlap制約は不変である。

### Scenario: accessibility・環境失敗

Given TalkBack / Switch Access / large font環境、またはclipboard利用不可・share target不在の環境、
When export/import workflowを操作する、
Then 確認画面・失敗表示はaccessibility対応され (focus順、読み上げlabel、font scaling)、代替transportへの誘導が提示される。

## Data and state

- 読むdata: #204契約のcontext export生成に必要なcanonical入力 (captured `LayoutSnapshot`、解決済み分類、lock状態、#203 signal snapshot (利用可能時))。本specはこれらの再定義をしない。
- 永続化: V1ではexchange package・import済みtext・export-scoped ID mapをいずれも永続化しない (process-local。#204 draftの規則に従う)。DB migration・backup/restoreへの影響なし。
- export再生成は新規export identityとなる。既存previewの無効化規則は #204 に従う。
- layout変更は既存run (snapshot → plan → preview → confirm → apply) のみで行われ、本workflowは新しいDB書込経路を作らない。
- #204 draft由来の制約 (受入まで仮): export data部の対象種別は #235 のsemantic placement role族 (app/shortcut系、folder、widget) に揃えられ、widgetのspanはexportに含まれずintentからも指定できない。`Unknown` 種別はexportから除外される。intentは既存RunMode (`FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization`) を増やさず、整理対象の追加も行わない (追加候補は #228 のuser明示選択composition inputのみ)。instruction部はこれらの制約と矛盾する約束 (「widget sizeを提案してよい」等) を含んではならない。

## Permissions, privacy, and security

- 追加permissionなし (clipboardはframework API、Share Sheetは既存intent機構、file exportはSAF/user選択)。
- 外部送信はuserの明示操作 (確認画面を経たcopy/share/save) のみ。network通信は本workflowに含まれない。
- 既定exportから除外: raw package名、内部ItemId/DB row ID、opaque profile identifier、raw usage ms/timestamps、diagnostics/recovery metadata (Issue本文・#204 draftと整合)。
- app label・folder titleはuntrusted dataとして扱い、instruction/data分離と #204 側のallow-list検証で対処する。本spec側はpackage構造がその分離を機械的に保証することを要求する。
- clipboard内容・import textをdiagnostics journalへ記録しない (organizer-diagnostics.mdの個人情報規則)。

## Accessibility and localization

- 送信前確認・import失敗・validation reject・transport失敗の各UIはTalkBack読み上げ、Switch Access操作、large font対応を必須とする。
- UI copyは日本語を正本とする他organizer surfaceに合わせ、instruction部のagent向け文言は英語を基本とする (外部agentの認識精度。要検証)。

## Acceptance criteria

前提: #204 contractがacceptedになっていること。未acceptの間、本specの実装は開始しない。

- [ ] AC-1: exchange package生成からimport・previewまでのend-to-end UXが定義され、instruction/data分離構造が機械的に検証できる。
- [ ] AC-2: 既定exportがraw package名・内部ID・profile identifier・raw usage/timestamps・diagnostics metadataを含まないことがcontract testで検証される。
- [ ] AC-3: 送信前確認なしにclipboard/share/file経路でデータが出ないことが検証される。redacted / label-inclusive modeの扱いが決定され、label-inclusive時の明示開示がある。
- [ ] AC-4: importがaccepted framing内の完全な `PersonalizedIntentV1` のみを抽出し、曖昧入力・framing違反を明示的parse失敗としてzero-write処理することがtestされる。
- [ ] AC-5: malformed / unknown schema / out-of-scope ID / 禁止内容が #204 validator経由でzero-write rejectされ、失敗種別がuserに説明されることがtestされる。
- [ ] AC-6: import後も #194 preview + #195 explicit confirmationが必須であり、agent outputの直接適用・confirmation省略経路が存在しないことが検証される。
- [ ] AC-7: untrusted app/folder label・agent応答のprompt injectionがthreat modelとtestに含まれる (instruction部不変、data分離、validator reject)。
- [ ] AC-8: 特定provider appのprivate API / UI automationに依存しないplain-text exchangeであることが確認される (import/exportの対象がversioned text schemaのみ)。
- [ ] AC-9: clipboard失敗・share target不在・file失敗・large font・TalkBack・Switch AccessのUX evidenceがある。
- [ ] AC-10: physical-deviceでChatGPT/Gemini等を用いたrepresentative workflow evidence (export → 外部agent → import → preview → confirm) がある。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | package生成moduleのunit/contract test (structure) + 手動UX確認 |
| AC-2 | export生成のcontract test (#204 test suiteと連携) |
| AC-3 | UI test + 送信経路のinstrumentation test |
| AC-4 | import parserのunit test (framing違反・曖昧入力・部分JSONのreject corpus) |
| AC-5 | #204 validator failure分類の回帰test + import UI test |
| AC-6 | integration test (import→plan→preview→confirmの経路強制) + code review |
| AC-7 | security test corpus (injection label fixture) |
| AC-8 | 依存review (private API/UI automationの不使用) + plain text schema test |
| AC-9 | accessibility・環境失敗の手動/instrumentation evidence |
| AC-10 | physical-device evidence記録 (docs/assessment/ またはissue) |

## Open questions (未決定事項 — 実装前に解決が必要)

1. **#204 acceptance**: 本specの全ACは#204受入を前提とする。#204受入時にtier名・field名・failure分類・framing候補を本specへ反映する。#204 draft (2026-09-13時点、branch `issue-204-spec-plan`) はtier (`EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS`) と8つのfailure class・reject-by-default V1を固定しているが、未acceptであるため本specはこれを仮称としてのみ参照する。
2. **初回transportの決定**: clipboard copy / Share Sheet / file export の比較基準 (Android上の摩擦、clipboard size制限、share target有無) と初回採用組み合わせ。
3. **accepted framingの形式**: import text内で `PersonalizedIntentV1` を囲むmarker・構造の具体形 (#204受入時に #204 と共通化。#204 draft現時点ではtext framing規格は未定義)。
4. **instruction部の文言・schema embedding方式**: Issue本文の例を起点とした具体文言、instruction内へschemaをどう埋め込むか。
5. **redacted modeのlabel surrogate表現**: #204 draft open question (hash surrogate可・形式未確定) と合わせた調整。
6. **#203 usageSignalsの取り扱い**: #203 (OPEN) のsignal契約確定後、export packageのusage projection表現を確定する (#204 draftではcoarse bucket + tier制御の方向)。
7. **instruction部言語**: 英語固定か、user locale追従か。
8. **rate/size制約**: clipboard・share textのsize上限に対する巨大layout (数百item) の扱い。

## Change history

- 2026-09-10: Draft created for #205. External Agent Exchange workflow spec: exchange package (instruction/data分離)、export/import transport、送信前確認、厳格import、#194/#195 preview必須。#204 acceptanceを明示的依存とする。
- 2026-09-13: Re-entry re-anchor。main `6b6bf8dd` → `f9afd8bfde` をmerge (organizer差分: #228 scope-composed run/missing-app selection、#235 widget strategy placement/semantic placement role、#271/#288 diagnostics、requirements FR-016 implemented化)。#204 draftが `f9afd8bfde` baselineへre-anchorされたことを受け、draft段階の固有名 (privacy tier名、failure class名、widget span非投影、reject-by-default V1、RunMode不変・対象追加なし) を仮称として明記。未解決のproduct decisionは解消していない (status: draft維持)。

## References

- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205)
- Issue #204 (contract、draft: branch `origin/issue-204-spec-plan` `specs/204-ai-personalization-context-intent-contract/` — 2026-09-13にbaseline `f9afd8bfde` へre-anchor (commit `65b9fc859d`)。未accept・origin/main未取り込み)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (scope-composed run、target追加はuser明示選択のみ)
- [Spec 235: widget strategy placement](../235-widget-strategy-placement/spec.md) (semantic placement role、widget span不変)
- Issue #203 (usage signals、OPEN), Issue #206 (managed AI、OPEN)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [requirements.md](../../docs/product/requirements.md), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md)
