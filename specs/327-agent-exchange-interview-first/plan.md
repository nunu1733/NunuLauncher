# Implementation Plan: External Agent Exchangeをinterview-firstにし、AIでできることを明示する

> Issue: #327
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

origin/main (`aab0d293d1a98bf59f5b164693f54ee1a63e3f0b`) 時点の確認事実 (worktree `nl-wt-327` で検証)。

- **promptの実source**: `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt`。instruction部は同file内のprivate const `INSTRUCTION_HEADER` / `INSTRUCTION_FOOTER` (英語、static text)。現行構成は #205 Decision 2の4 section (Goal / You may / You must / Response format)。質問に関する文言は「You may: ... Ask the user clarifying questions if the request is ambiguous」の1行 (曖昧時のみの**許可**) のみで、Phase概念・質問数のbound・要約と了承の要求・exampleは存在しない。Response format節は marker行 `-----BEGIN/END NUNULAUNCHER INTENT-----` を含み (例示として裸のmarker行がheader内にある)、`personalized-intent-v2` と `unresolvedRefs` に言及する。#331由来の `subject: CANDIDATE` 扱いの行も含まれる。
- **package合成seam**: `ExchangePackageComposer.compose(exportJson)` は `INSTRUCTION_HEADER + CONTEXT_BEGIN_MARKER + exportJson (単行) + CONTEXT_END_MARKER + INSTRUCTION_FOOTER` を返すimmutable値。`parsePackageStructure` はCONTEXT marker単一対・非空data・非空header/footerを機械検証 (#205 AC-1)。composerを呼ぶのは `organizer/integration/exchange/ExchangeFlowController.generate` (idle / run-in両entryで同一composer。#331で `generateForSelection` が追加済み)。
- **UI copyの実source**: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` (`ExchangeEntryRow` / `ExchangeScopedEntryRow` / `ExchangeDisclosure` / status表示) と `lawnchair/res/values/strings.xml` (en) / `lawnchair/res/values-ja/strings.xml` (ja)。現行の説明は `exchange_entry_subtitle` (「ChatGPT等の外部AIへの依頼文を作成し、回答を検証付きで取り込めます。」) と `exchange_scoped_entry_subtitle` のみで、capability具体例・「直接変更しない」明示・会話flow説明は不在。送信完了は `exchange_transport_success` (「送信 (またはコピー) しました。後でAIの回答を貼り付けて取り込めます。」)。
- **intent schema (exampleの対象)**: `organizer/personalization/ContextExportModels.kt` の `ContextExportContract.INTENT_SCHEMA_VERSION = "personalized-intent-v2"`、`IntentCodec.kt` の閉schema allow-list (top: `schemaVersion`/`exportId`/`itemIntents`/`unresolvedRefs`/`globalPreference`/`rationale`/`confidence`、item: `ref`/`importance`/`desiredGroup`/`groupSemantic`/`pageAffinity`/`regionAffinity`/`preserve`、global: `minimizeMovement`)。`IntentCodec.encode` はcompact単行JSON。
- **既存test表面**: `tests/unit/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposerTest.kt` (4 section存在・marker埋込・schemaVersion string・data単行・構造破壊のtyped reject)。UI側は `tests/unit/app/lawnchair/organizer/ui/exchange/` に `ExchangeFlowStateHolderTest` / `ExchangeDisclosureStateTest` (state machine中心、copy内容のassertなし)。
- **後続evidence状況**: #205 AC-9/AC-10 (a11y・physical-device representative evidence) は後続evidence PR対象のまま残っている。#327のAC-7 (representative provider会話) / AC-8 (a11y) は同じevidence PRに合同で載せられる。

## Design

### Modules and interfaces

変更は既存module内の閉じた差分のみ。新規module・新規interface・新規seamは追加しない。

1. **`ExchangePackageComposer.kt` (pure、既存)**: `INSTRUCTION_HEADER` / `INSTRUCTION_FOOTER` 定数をinterview-first 2-phase構成へ書き換える。構造は (a) Goal、(b) Phase 1 — interview (初回応答でのIntent生成分禁止、原則ヒアリング、2〜4問bound、質問主题の例、要約と了承、skip時の扱い)、(c) You must (既存遵守事項の維持)、(d) Phase 2 — Response format (marker・schema version・coverage・exportId echo + canonical example)、の順。exampleはplaceholderを含むstatic JSON text (composer file内のprivate const、または同packageの `ExchangeIntentExample.kt` へ分離 — 実装時に長さを見て決定、#204側moduleへの変更不可)。
   - composer signature (`compose(exportJson: String)`) は不変。instructionは引き続き静的 (spec Decision 3)。
   - `parsePackageStructure`・`ExchangeContract` (marker・envelope上限) は無変更。
2. **`ExchangeFlowUi.kt` (ui、既存)**: `ExchangeEntryRow` / `ExchangeScopedEntryRow` にcapability説明 (具体例リスト、「AIはホーム画面を直接変更しない」明示、期待される会話flow) を追加。既存 `Column` + `Text` 構成の拡張、testTag付与。`ExchangeStatus` copy (transport success) の文言更新はstrings側で対応。
3. **strings (既存)**: `exchange_entry_subtitle` をcapability説明へ置換または新規strings (`exchange_capability_*` 系) を追加。en (`values/strings.xml`) + ja (`values-ja/strings.xml`) 両方 (ja正本)。

### Canonical example template (具体形)

```json
{"schemaVersion":"personalized-intent-v2","exportId":"PASTE_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA","itemIntents":[{"ref":"AN_ITEM_REF_FROM_THE_CONTEXT_DATA","importance":"HIGH","desiredGroup":["ANOTHER_ITEM_REF"],"groupSemantic":{"category":"A_CATEGORY_ID_FROM_THE_CONTEXT_DATA"},"pageAffinity":0,"regionAffinity":"TOP","preserve":false}],"unresolvedRefs":["A_REF_YOU_CANNOT_DECIDE"],"globalPreference":{"minimizeMovement":false},"rationale":"one short sentence"}
```

- placeholder値 (`PASTE_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA` 等) は実 `exportId` (乱数)・実 `ref` (乱数) と衝突しない構造を持つ全大文字の明示的な非実在値。
- contract test: placeholderを実効値へ機械置換した上で `IntentCodec.decode` を通す (閉schema適合)。placeholder文字列と `ContextExportContract.INTENT_SCHEMA_VERSION` の一致もtestが強制 (#330等によるschema変更時にexample更新を漏れさせない不変条件)。
- exampleにはINTENT marker行を含めない (spec Decision 2)。marker行自体はPhase 2節の既存形式 (裸のmarker行提示) のまま。

### Data flow

不変。`privacy mode選択 → [gate確認] → #204/#331 composition → build → save → compose (instruction差替えのみ) → Pre-send Disclosure → transport` の順序契約、import側pipeline、いずれも変更なし。instruction差替えはpackage生成時の静的なもののため、disclosure同一性契約 (AC-12 of #205) への影響はない。

### Alternatives rejected

- **launcher側での会話flow検証 (初回返答にIntent markerが含まれる場合はreject等)**: importはframing抽出と#204検証のみを所有し、会話の順序は観測不能。safety上の利益がなく (#204 validatorが全authorityを検証済み)、`FRAMING_*` の意味を変える契約破壊になるため不採用。
- **exampleをINTENT marker行で囲む構成**: agentがexampleをmarkerごとechoした場合に `FRAMING_AMBIGUOUS` を誘発する表面が広がるため不採用 (markerの指定はPhase 2節の既存裸行提示で十分)。
- **instructionの動的生成 (scope内容や会話状態に応じた文章組立)**: #205 Decision 2 (固定長instruction + payload上限による構造的size上限) を破り、composerの純粋性・決定性検査を複雑にするため不採用。
- **exampleをpackageとは別file (share時に2要素送信) にする構成**: transportが単一text契約 (#205) のため不採用。instruction内に同梱する。
- **アプリ内ヒアリング画面 (AI質問をlauncher経由で表示)**: Issue non-goal (Launcher内LLM実行の禁止、ヒアリングは外部AIアプリ内)。不採用。
- **capability説明をschema用語 (priority/grouping/keep-position) で書くoption**: Issue本文が明示的に禁止 (ユーザー語の具体例を要求)。不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/ExchangePackageComposer.kt` | instruction定数のinterview-first 2-phase化 + canonical exampleの同梱 | promptの唯一の生成正本 (#205 Decision 2)。pure module内のstatic text |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | entry row (idle/scoped) へのcapability説明追加、testTag | 既存導線UIとの一貫性、a11y |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | capability説明・flow説明・送信完了copyの追加/更新 (ja/en両方) | spec 123契約、UI copyの正本 |
| `tests/.../ExchangePackageComposerTest.kt` (+ 必要に応じ新規example contract test class) | 2-phase指針・example構造・schema適合・回帰のunit test | AC-1〜AC-3 |
| `tests/.../ui/exchange/` | entry row説明表示のtest (必要に応じRobolectric/instrumentation) | AC-4/AC-5 |

変更しないもの: `IntentCodec` / `IntentValidator` / `ExchangeContract` / `IntentImportParser` / `ExchangeImportPipeline` / `ExchangeGenerationGate` / `SessionExportReconstructor` / integration transports / `ExchangeFlowController` / `ManualOrganizationRun` / #204 personalization package / DB / migration。

## Migration and recovery

- schema変更なし・DB書込なし・新規永続化なし。release rollback = instruction/copyが旧に戻るのみで、後処理不要 (既存exchange sessionはTTL 24時間で自然失効。#205契約の継承)。
- 既存active session宛の返答のimport互換性: instruction差替えはsession/data部契約に影響しないため、旧instructionで送信した往復の返答も新buildで問題なくimportできる (framing/schemaは不変)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `ExchangePackageComposerTest` 拡張: (a) 2-phase指針の各文言存在、(b) 既存4 section相当の維持、(c) 遵守事項 (ref/FIXED/CANDIDATE/coverage/exportId) の回帰、(d) `parsePackageStructure` 往復・data単行の維持 | `./gradlew :tests:testLawnWithQuickstepGithubDebugUnitTest --tests '*ExchangePackageComposerTest*'` (unit test) |
| AC-2 | example contract test: example存在・placeholder構造・INTENT marker不在・`INTENT_SCHEMA_VERSION` 一致・placeholder置換後の `IntentCodec.decode` 成功 | 同上 (unit test) |
| AC-3 | skip文言の存在assert (composer unit test) | 同上 |
| AC-4 | entry row UI test (testTag配下のtext、idle/scoped両方) + strings存在 (en/ja) | unit (Robolectric) または instrumentation |
| AC-5 | 導線説明/transport success copyの文言test | 同上 |
| AC-6 | 依存review (instruction全文・UI copy走査、provider固有機能の要求不在) — 実装PR review記録 | review |
| AC-7 | representative provider会話のdevice evidence (ChatGPT/Gemini: 質問→要約→了承→Intent生成→import まで) | physical device、docs/assessment/ またはIssueへ記録 |
| AC-8 | TalkBack / large fontでの説明とCTAのevidence (ja/en) | 手動 (#205 AC-9系の後続evidence PRと合同可) |

含める観察: unit/contract (instruction内容・example schema適合・package構造回帰)、UI (説明表示・両locale strings)、手動 (a11y・representative会話)。full build / device testは本planの文書作成時点では実施しない (docs-only)。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md: 「整理方針確認 (policy confirmation)」用語を受入時に反映
- [ ] DESIGN.md: 変更なし (module構成不変。gate 13の記載は #205/#331のまま)
- [ ] requirements.md: FR-017の補足は不要 (behavior変更はFR-017枠内)

## Dependencies and blockers

- blockerなし。#204/#205/#331はすべてimplemented。
- **#330 (authoring contract簡素化、OPEN)**: schema (`personalized-intent-v2` のcoverage/FIXED扱い等) が変わる場合、instructionの遵守事項文言とcanonical exampleは **同一PRで** 追従が必要 (本planのcontract testが乖離を検出する)。実装順序として #327を先に行う場合、#330は本変更の上にrebaseしてexampleを更新する。
- **#329 (Import Normalizer、OPEN)**: 本変更と独立 (instruction側とimport側の別surface)。#329がexample周りの揺らぎ吸収を変えても、exampleの正本はcomposer側のまま。
- **#332 (import UI、OPEN) / #328 (import後状態表示、OPEN)**: 本planはimport入力欄・import成功後UIに触れない (spec Non-goals)。

## Risks

- instructionの長文化 (interview指針 + example) によりagentの遵守率が下がる可能性。#205 Decision 5のsize構造 (固定長instruction) は維持され、exampleは1 KiB程度に収まる想定。遵守率はAC-7 evidenceで早期把握し、Open question 3の微調整ルートで対応する。
- agentがexampleをverbatimでechoする経路 (spec scenario想定済み)。既存typed失敗でfail-closedであり、placeholder構造と「値を置き換える」指示で発生率を抑える。
- interview-firstが1往復の追加コストになる。skip宣言 (Decision 1) で軽減するが、skip文言が「即Intent」を誘発してヒアリングが全く発生しなくなる(prose上の)リスクはAC-7 evidenceで観察する。
- 質問数bound (2〜4問) は指示であって強制ではない。#205のframing遵守と同様、agent行動の保証外 (Non-goals: 質問内容の完全固定禁止)。

## Explicitly unverified areas

- representative provider (ChatGPT/Gemini) が新しいinstructionに実際どう従うか (質問を投げるか、要約を挟むか、skip時にどう振る舞うか) は未検証 (AC-7 device evidenceで実施)。
- a11y (TalkBack・large font) での説明の読み上げ品質は未検証 (AC-8)。
- ja/enの最終copy (具体例の言い回し等) は実装PRとevidenceで調整予定。本plan時点で文面は確定していない。
- 本planのevidence確認は2026-09-16時点のmain `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` に対して実施。build・testは未実行 (docs-only変更のため)。

## Execution checklist

- [ ] 本specのowner acceptance (status: accepted)。
- [ ] composer unit testを先行追加 (2-phase指針・example構造・回帰のred)。
- [ ] instruction定数の書き換え + canonical example同梱 (green)。
- [ ] entry row capability説明 + strings (ja/en)。
- [ ] UI test・依存review記録。
- [ ] AC-7 representative provider会話evidence。
- [ ] AC-8 a11y evidence。
- [ ] PR evidenceとremaining risksの記録。
