# Implementation Plan: External Agent Exchangeをinterview-firstにし、AIでできることを明示する

> Issue: #327
> Spec: [spec.md](./spec.md)
> Status: draft (spec revision 2に対応。spec acceptance後にimplementation-ready)

## Current evidence

origin/main (`8fd05a40d51abd24b40a7b93579bb9b76d046f75`、#348 merge後) 時点の確認事実。

- **promptの実source**: `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt`。#348実装後の構成は `compose(exportJson)` が `INSTRUCTION_OPEN` (Goal / You may) → `outputContractSection()` (`IntentWireContract` descriptorから整形) → `youMustSection()` (production-enforced規則 + FIXED/CONDITIONAL/CANDIDATE authoring規則 + partial authoring + ask-before-final) → CONTEXT marker間にexport JSON単行 → `INSTRUCTION_FOOTER` (finalize前self-check + Response format) をこの順で連結するimmutable値。質問関連は「You may: Ask the user clarifying questions while you work, before you finalize」と「You must: If information you need is missing, ask the user before you finalize — do not fill the gap by inventing properties or values」のみで、初回応答でのヒアリング原則・質問数bound・整理方針要約/確認・canonical exampleは存在しない。Response formatは単一fenced `json` block要求 (marker行の要求なし、`ExchangePackageComposerTest` が `!pkg.contains("-----BEGIN NUNULAUNCHER INTENT-----")` までassert)。
- **composerの純粋性**: instructionはdescriptor (`IntentWireContract`、静的data) と静的proseからのcompose時整形であり、export JSONを解釈しない。`compose(exportJson: String)` signature・`parsePackageStructure` (CONTEXT marker単一対・非空data・非空header/footer) は #205 AC-1を引き続き満たす。
- **#348の回帰test表面**: `tests/unit/app/lawnchair/organizer/personalization/exchange/Issue348AiFacingContractSyncTest.kt` が (a) descriptor↔codec allow-list一致、(b) composer出力のdescriptor派生containment (enum値・bounds・上限のpositive render)、(c) self-check / ask-before-final文のpinning、(d) repair導線の否定assertion、(e) `PRODUCTION_ENFORCED` parity fixture/matrix、(f) authoring policy matrix、(g) golden canonical payload、(h) #345 regression fixtureを検証する。本変更は (a)〜(d) に触れるtext差分を含むため、このtest群が **無変更で通り続けること** を本planの回帰条件とする。
- **既存composer test**: `ExchangePackageComposerTest.kt` がsection存在と順序 (Goal / You may / Output contract / You must / Before sending / Response format)、canonical authoring form要求 (` ```json `、single fenced code block、marker行の不在)、partial authoring文言 (`Author only what you actually judged`、`you do not have to cover every ref`、`!contains("Cover every")`)、data単行、descriptor property名の全render、tamper typed rejectをassertする。本変更で **既存assertを弱めず** 拡張する。
- **UI copyの実source**: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` — `ExchangeEntryRow` (testTag `exchange-entry-title` / `exchange-entry-subtitle系` / `exchange-entry-open` / `exchange-entry-import`) と `ExchangeScopedEntryRow` (testTag `exchange-scoped-entry-*`) が `stringResource(R.string.exchange_entry_subtitle)` / `exchange_scoped_entry_subtitle` を表示。capability具体例・「直接変更しない」明示・会話flow説明は不在。送信完了は `exchange_transport_success`。#332 implementedのimport入力欄 (`exchange-import-*` testTag群) は本planの対象外。
- **strings**: `lawnchair/res/values/strings.xml` (en) / `values-ja/strings.xml` (ja正本)。`exchange_entry_title` / `exchange_entry_subtitle` / `exchange_scoped_entry_subtitle` / `exchange_transport_success` が現行copy。#348が失敗案内4 keyを更新済み (本planでは触れない)。
- **intent schema (exampleの対象)**: `ContextExportContract.INTENT_SCHEMA_VERSION = "personalized-intent-v3"`、`IntentWireContract` descriptor (top-level / item / globalPreference / groupSemanticの名前集合・enum・制限定数・enforcement分類つき主張)、`IntentCodec` (allow-listはdescriptor派生、compact単行JSON encode)。
- **後続evidence状況**: #205 AC-9/AC-10系・#348 AC-11 (representative provider first-pass evidence) のevidence PR慣行あり (#345 / assets-345-import-evidence)。#327のAC-7 (representative provider会話) / AC-8 (a11y) は同じ後続evidence PRに合同で載せられる。

## Design

### Modules and interfaces

変更は既存module内の閉じた差分のみ。新規module・新規public interface・新規seamは追加しない。

1. **`ExchangePackageComposer.kt` (pure、既存)**:
   - `INSTRUCTION_OPEN` をinterview-first 2-phase構成へ書き換える。構成: Goal (2-phase workflowの明示を含む) → Phase 1指示 (初回応答での最終JSON artifact禁止、原則ヒアリング、2〜4問bound、質問主题の例、skip時の扱い: 要約+最終を同一返答) → 整理方針要約と了承要求 → You may (web検索等の調査許可。旧clarifying questions行はPhase 1指示へ統合)。Output contract section・You must sectionの文は **1つも変更しない**。
   - `INSTRUCTION_FOOTER` を「確認後にのみ最終回答を返す」phase gateの追記 + Response format節へのcanonical example同梱へ拡張する。self-check文・fence要求文は既存のまま保持する。
   - canonical exampleはcomposer file内のprivate定数 (例: `CANONICAL_INTENT_EXAMPLE`) とする。単一file内で完結し、#204側moduleへの変更は行わない。
   - `compose(exportJson: String)` signature・`parsePackageStructure`・`ExchangeContract` (marker・envelope上限) は無変更。instructionは引き続き静的合成 (spec Decision 3)。
2. **`ExchangeFlowUi.kt` (ui、既存)**: `ExchangeEntryRow` / `ExchangeScopedEntryRow` にcapability説明 (具体例リスト、「AIはホーム画面を直接変更しない」明示、期待される会話flowと1往復の受け渡し、会話はNunuLauncherを経由しない旨) を追加。既存 `Column` + `Text` 構成の拡張、新testTag (例: `exchange-entry-capability`) 付与。`ExchangeStatus` copy (transport success) の文言更新はstrings側で対応。展開形式 (常時表示 vs 折りたたみ) は実装時のUX判断 (spec Open question 2)。
3. **strings (既存)**: `exchange_entry_subtitle` / `exchange_scoped_entry_subtitle` をcapability説明へ置換または `exchange_capability_*` 系新規stringsを追加。`exchange_transport_success` を期待flowに言及する文言へ更新。en (`values/strings.xml`) + ja (`values-ja/strings.xml`) 両方 (ja正本)。

### Canonical example template (具体形)

instruction内のexample (静的text、fence・markerで囲まない):

```json
{"schemaVersion":"personalized-intent-v3","exportId":"REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA","itemIntents":[{"ref":"REPLACE_WITH_A_REF_FROM_THE_CONTEXT_DATA","importance":"HIGH","desiredGroup":["REPLACE_WITH_ANOTHER_REF_FROM_THE_CONTEXT_DATA"],"groupSemantic":{"category":"REPLACE_WITH_A_CATEGORY_ID_FROM_THE_CONTEXT_DATA"},"pageAffinity":0,"regionAffinity":"TOP"}],"unresolvedRefs":["REPLACE_WITH_A_REF_YOU_CANNOT_JUDGE"],"globalPreference":{"minimizeMovement":false},"rationale":"One short sentence about the organization policy."}
```

- placeholder値 (`REPLACE_WITH_*`) は全大文字の明示的な非実在値で、実 `exportId` (乱数)・実 `ref` (乱数) と衝突しない。enum値 (`HIGH` / `TOP`)・`pageAffinity: 0`・`minimizeMovement: false` はschema定数として実値のまま (spec Decision 2)。
- v3 partial authoringの **表示例** として、`itemIntents` は判断した1件のみ、`unresolvedRefs` に未判断refの例を1件示す (full coverageを示唆しない)。
- contract test: placeholderをfixtureの実効値へ機械置換した上で `IntentCodec.decode` を通す (閉schema適合、descriptor名前集合への包含)。placeholder文字列の非実在性 (生成packageのCONTEXT data JSONと一致しないこと)、example行の周囲にfence行が無いこと、INTENT marker行の不在、`ContextExportContract.INTENT_SCHEMA_VERSION` との一致もtestが強制する。

### Data flow

不変。`privacy mode選択 → [gate確認] → #204/#331 composition → build → save → compose (instruction差替えのみ) → Pre-send Disclosure → transport` の順序契約、import側pipeline、いずれも変更なし。instruction差替えはpackage生成時の静的なもののため、disclosure同一性契約 (AC-12 of #205) への影響はない。

### Alternatives rejected

- **launcher側での会話flow検証 (初回返答にfenced JSONが含まれる場合はreject等)**: importはframing抽出と#204検証のみを所有し、会話の順序は観測不能。safety上の利益がなく (#204 validatorが全authorityを検証済み)、accepted framingの意味を変える契約破壊になるため不採用。
- **exampleをfenced `json` blockで囲む構成**: Response formatが要求する「返答内のcode blockは1個」とexampleのcode blockが混同され、agentがexample blockを返答の一部と誤認する表面を広げる。またverbatim echo時にfenceごとcopyされやすい。不採用 (fenceは返答要求の说明文としてのみ現れる)。
- **exampleをINTENT marker行で囲む構成**: `ExchangePackageComposerTest` がpackage全体からのmarker行不在をassert (#348) するため実装不能。marker形式はaccepted framingのまま要求しない (spec 348 Decision 2)。不採用。
- **Output contract / You must sectionの再編 (interview指針をそこへ統合等)**: `Issue348AiFacingContractSyncTest` のpositive render / containment assertが文単位でpinningしており、文の削除・意味変更は #348保証の回帰となる。Phase 1差分は `INSTRUCTION_OPEN` とfooterへの追加に局所化する。不採用。
- **instructionの動的生成 (scope内容や会話状態に応じた文章組立)**: #205 Decision 2 (固定長instruction + payload上限による構造的size上限) を破り、composerの純粋性・決定性検査を複雑にするため不採用。
- **exampleをpackageとは別file (share時に2要素送信) にする構成**: transportが単一text契約 (#205) のため不採用。instruction内に同梱する。
- **アプリ内ヒアリング画面 (AI質問をlauncher経由で表示)**: Issue non-goal (Launcher内LLM実行の禁止、ヒアリングは外部AIアプリ内)。不採用。
- **capability説明をschema用語 (priority/grouping/keep-position) で書くoption**: Issue本文が明示的に禁止 (ユーザー語の具体例を要求)。不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/ExchangePackageComposer.kt` | `INSTRUCTION_OPEN` の2-phase化 + `INSTRUCTION_FOOTER` へのphase gate追記 + canonical example定数の同梱。Output contract / You mustは無変更 | promptの唯一の生成正本 (#205 Decision 2 + spec 348 Decision 3構成の上の差分)。pure module内の静的合成 |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | entry row (idle/scoped) へのcapability説明追加、新testTag | 既存導線UIとの一貫性、a11y |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | capability説明・flow説明・送信完了copyの追加/更新 (ja/en両方) | spec 123契約、UI copyの正本 |
| `tests/.../ExchangePackageComposerTest.kt` | 2-phase指針・example構造・schema適合・section順序維持のtest追加/拡張 (既存assertは弱めない) | AC-1〜AC-3 |
| `tests/.../ui/exchange/` (既存test file群) | entry row説明表示のtest (Robolectric) | AC-4/AC-5 |
| `tests/.../Issue348AiFacingContractSyncTest.kt` | **無変更** (回帰条件として実行のみ) | #348保証の維持確認 |

変更しないもの: `IntentWireContract` / `IntentCodec` / `IntentValidator` / `ExchangeContract` / `IntentImportParser` / `ExchangeImportPipeline` / `ImportNormalizer` / `ExchangeGenerationGate` / `SessionExportReconstructor` / integration transports / `ExchangeFlowController` / `ManualOrganizationRun` / #204 personalization package / 失敗案内strings (4 key) / DB / migration。

## Migration and recovery

- schema変更なし・DB書込なし・新規永続化なし。release rollback = instruction/copyが旧に戻るのみで、後処理不要 (既存exchange sessionはTTL 24時間で自然失効。#205契約の継承)。
- 既存active session宛の返答のimport互換性: instruction差替えはsession/data部契約に影響しないため、旧instructionで送信した往復の返答も新buildで問題なくimportできる (framing/accepted framing・schemaは不変)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `ExchangePackageComposerTest` 拡張: (a) 2-phase指針の各文言存在、(b) section順序の維持、(c) 遵守事項 (ref/partial authoring/FIXED/CONDITIONAL/CANDIDATE/exportId echo) の回帰、(d) `parsePackageStructure` 往復・data単行の維持 + `Issue348AiFacingContractSyncTest` 無変更成功 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.personalization.exchange.*'` |
| AC-2 | example contract test: example存在・placeholder構造・fence/marker不在・`INTENT_SCHEMA_VERSION` 一致・placeholder置換後の `IntentCodec.decode` 成功・descriptor key包含 | 同上 (unit test) |
| AC-3 | skip文言の存在assert (composer unit test) | 同上 |
| AC-4 | entry row UI test (testTag配下のtext、idle/scoped両方) + strings存在 (en/ja) | unit (Robolectric) |
| AC-5 | 導線説明/transport success copyの文言test | 同上 |
| AC-6 | 依存review (instruction全文・UI copy走査、provider固有機能の要求不在) — 実装PRのreview記録 | review |
| AC-7 | representative provider会話のdevice evidence (ChatGPT/Gemini: 質問→要約→了承→Intent生成→import まで) | physical device、docs/assessment/ またはIssueへ記録 (後続evidence PR可) |
| AC-8 | TalkBack / large fontでの説明とCTAのevidence (ja/en) | 手動 (#205/#348系の後続evidence PRと合同可) |

含める観察: unit/contract (instruction内容・example schema適合・package構造回帰・#348同期test回帰)、UI (説明表示・両locale strings)、手動 (a11y・representative会話)。`spotlessCheck` と `assembleLawnWithQuickstepGithubDebug` を実装PRで実行する。

## Documentation updates

- [ ] spec status/history (acceptance時にaccepted、merge時にimplementedへ)
- [ ] CONTEXT.md: 「整理方針確認 (policy confirmation)」用語を受入時に反映
- [ ] DESIGN.md: 変更なし (module構成不変。gate 12/13の記載は #204/#205/#331/#348のまま)
- [ ] requirements.md: FR-017の補足は不要 (behavior変更はFR-017枠内)

## Dependencies and blockers

- blockerなし。#204/#205/#331/#330/#329/#332/#348はすべてimplemented (本planのbaselineで確認)。
- **#328 (import成功後の状態明示、OPEN)**: 本planはimport成功後UIに触れない (spec Non-goals)。
- schema/instruction契約の将来変更 (#330系の追加等) が入る場合、canonical exampleの更新は同一PRで行う (本planのcontract testが乖離を検出する)。

## Risks

- instructionの長文化 (Phase 1指針 + example) によりagentの遵守率が下がる可能性。#205 Decision 5のsize構造 (固定長instruction) は維持され、exampleは1 KiB程度に収まる想定。遵守率はAC-7 evidenceで早期把握し、Open question 3の微調整ルートで対応する。
- agentがexampleをverbatimでechoする経路 (spec scenario想定済み)。既存typed失敗でfail-closedであり、placeholder構造と「値を置き換える」指示で発生率を抑える。
- Phase 1指示と #348のself-check (「exactly one importable JSON artifact」) の混同 (初回応答でもartifactを返す誤解釈)。phase語でself-checkを最終回答に限定する文言構成で緩和し、AC-7 evidenceで観察する。
- interview-firstがAI側会話の往復を増やす (UX cost)。ただしLauncher↔AI間のartifact受け渡しは1往復のまま (spec/one-round-trip)。skip宣言 (Decision 1) で軽減する。skip文言が「即Intent」を誘発してヒアリングが全く発生しなくなる (prose上の) リスクはAC-7 evidenceで観察する。
- 質問数bound (2〜4問) は指示であって強制ではない。#205のframing遵守と同様、agent行動の保証外 (Non-goals: 質問内容の完全固定禁止)。

## Explicitly unverified areas

- representative provider (ChatGPT/Gemini) が新しいinstructionに実際どう従うか (質問を投げるか、要約を挟むか、skip時にどう振る舞うか) は未検証 (AC-7 device evidenceで実施)。
- a11y (TalkBack・large font) での説明の読み上げ品質は未検証 (AC-8)。
- ja/enの最終copy (具体例の言い回し等) は実装PRとevidenceで調整予定。本plan時点で文面は確定していない。
- 本planのevidence確認は `8fd05a40d51abd24b40a7b93579bb9b76d046f75` 時点のmainに対して実施。実装前時点ではbuild・testは未実行 (spec/planのみの変更のため)。

## Execution checklist

- [ ] 本specのowner acceptance (status: accepted)。
- [ ] composer unit testを先行追加 (2-phase指針・example構造・回帰のred)。
- [ ] `INSTRUCTION_OPEN` 書き換え + footer phase gate + canonical example同梱 (green、`Issue348AiFacingContractSyncTest` 無変更成功を確認)。
- [ ] entry row capability説明 + strings (ja/en)。
- [ ] UI test・依存review記録。
- [ ] `spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` 実行記録。
- [ ] AC-7 representative provider会話evidence (後続evidence PR可)。
- [ ] AC-8 a11y evidence (後続evidence PR可)。
- [ ] PR evidenceとremaining risksの記録。
