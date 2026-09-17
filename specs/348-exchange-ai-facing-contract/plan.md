# Implementation Plan: External Agent ExchangeのAI-facing contractをproduction truthと同期し初回Import成功率を上げる

> Issue: #348
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- instruction部の正本は `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt` の `INSTRUCTION_HEADER` / `INSTRUCTION_FOOTER` (手書き定数)。marker形式の応答を要求している。schemaVersion名 (`personalized-intent-v3`) とpartial authoring文のみで、許可property・enum値・数値制約は未列挙。
- production truth: `ContextExportContract` (`ContextExportModels.kt` — `SCHEMA_VERSION` / `INTENT_SCHEMA_VERSION` / `MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS=100` / `MAX_RATIONALE_CHARS=500`)、`IntentCodec` (`ALLOWED_TOP_KEYS` / `ALLOWED_ITEM_KEYS` / `ALLOWED_GLOBAL_KEYS` / `ALLOWED_SEMANTIC_KEYS` — いずれも `private`)、`Importance` / `ExportRegionKind` (public enum)、confidence 0..100 (`IntentModels.kt:28` と `IntentCodec.kt:102` のliteral、重複)。
- validatorのmobility規則: FIXED → semantic fields禁止 (`IntentValidator.kt:87-93`)、CONDITIONAL → `desiredGroup`/`groupSemantic` 禁止 (`IntentValidator.kt:94-98`)、CANDIDATE → `preserve` 禁止 (`IntentValidator.kt:103-105`)、pageAffinity 0..`grid.pageCount-1` (`IntentValidator.kt:74-80`)。instructionはFIXED/CANDIDATEのみ言及し、CONDITIONALに未言及。
- #345実測 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)): (1) ChatGPT mobile webのmessage-copyは各行末に `\`+改行を入れmarker一致が壊れる (`FRAMING_MISSING` 表示を確認済み)、(2) code-block copyはclean JSONを得られるが `globalPreference.organization` / `grouping` / 小文字enum / `0.82` で `SCHEMA_MISMATCH` / `INVALID_ENUM` (実装確認済みのtyped挙動)、(3) 契約適合payloadはfile経由で「Proposal validated」まで成功。
- composerのunit testは `tests/unit/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposerTest.kt`。tests/unitはlawnchair moduleのtest source set (`build.gradle:370`) なので同一moduleの `internal` 参照が可能。
- import経路: `ExchangeImportPipeline.prepare` (envelope 1 MiB → `ImportNormalizer` → framing/codec)。fenced/standalone受理は `ImportNormalizer` 実装済み (#339)。失敗copyは `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` (framing_missing等、marker表現を参照)。instrumentation testは文字列をresource ID参照する (`ExchangeImportSurfaceInstrumentationTest.kt:270`) ので文言変更は非破壊。

## Design

### Modules and interfaces

- `ExchangePackageComposer` (変更): instruction部を定数文字列から「固定prose + production symbol派生部分」の合成へ変更。公開API (`compose` / `parsePackageStructure`) とimmutable-value契約 (spec 205 AC-12) は不変。composerはexport JSONを解釈しない (現行どおり。output contractはper-export情報を埋め込まない)。
- `IntentCodec` (最小変更): `ALLOWED_*_KEYS` を `private` → `internal` (同一module内のcomposer/test参照用。public surfaceにはしない)。
- `ContextExportContract` (最小変更): `CONFIDENCE_MIN = 0` / `CONFIDENCE_MAX = 100` を追加し、`IntentModels.init` と `IntentCodec.decode` のliteral `0..100` を置換 (production truthの単一化。挙動不変)。
- 新規 `tests/unit/app/lawnchair/organizer/personalization/exchange/Issue348AiFacingContractSyncTest.kt`: derivation pinning / golden payload / #345 regression fixture。
- strings (en/ja): spec AC-9の4 keyの文言更新のみ。
- seam: すべて既存 (`ExchangePackageComposer.compose`、`ExchangeImportPipeline.import`、`IntentCodec.decode`、`IntentValidator.validate`)。新規interface/adapterは作らない。

### Data flow

compose時: production symbol (compile時定数・enum entries) → output contract sectionを整形 → 固定prose (You may / You must / self-check / Response format) と結合 → CONTEXT marker間に従来どおりexport JSON単行 → immutable package文字列。

import時: 無変更 (envelope → normalizer → framing/codec → validator → completion)。本planはこの経路に1行も触れない。

### Alternatives rejected

- build時codegen/metadata生成: instructionという表示物への効用がruntime derivationと同じで、build stepと二次表現だけが増える (spec Decision 1(b))。
- 手書きprose + contract testのみ: test緩和後のdriftを構造的に防げない (spec Decision 1(c))。prose pinningとして補助採用。
- instructionでのmarker要求維持: #345実測のmessage-copyで壊れる既知の形式を要求し続けることになる (spec Decision 2)。
- `ImportNormalizer` による `\` エスケープ救済: verbatim substring制約 (spec 329 D-6) を破り、fence内テキストの書き換えはsemantic repairに接尾する (spec Decision 2)。受信側ではなく発生源 (canonical final form) で緩和する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `ExchangePackageComposer.kt` | instruction部再構成 (Goal / You may / Output contract (derived) / You must (+CONDITIONAL) / self-check / Response format (fenced))。footer更新 | spec Decisions 2/3。AI-facing contractの正の本体 |
| `IntentCodec.kt` | `ALLOWED_*_KEYS` を `internal` へ | derivationのsource-of-truth参照 (public化しない) |
| `ContextExportModels.kt` | `CONFIDENCE_MIN`/`CONFIDENCE_MAX` 追加 | confidence制約の単一化 (3箇所のliteral重複解消) |
| `IntentModels.kt` | `require(it in 0..100)` → contract定数参照 | 同上 |
| `ExchangePackageComposerTest.kt` | section構成・fence要求・marker非要求・partial authoringのassert更新 | 既存assertが旧instruction構造に固定されているため |
| `Issue348AiFacingContractSyncTest.kt` (新規) | AC-2/3/4/5/6/8のcontract/regression test | #345 fixtureと同期保証の検証面 |
| `strings.xml` / `values-ja/strings.xml` | `exchange_failure_framing_missing` / `framing_empty` / `retry_hint` / `normalization_unrecognized` をfenced形式参照へ | AC-9。失敗key・分類は不変 |
| `specs/205-.../spec.md` | change historyへ「Response format要求の変更 (spec 348所有)」注記 | instruction構造は205 Decision 2が正本のため |
| spec 348 front-matter / docs | status更新 | 通常の受入手続き |

## Migration and recovery

- migration不要 (DB/schema/contract不変。`CONFIDENCE_MIN/MAX` は同等定数への置換)。
- rollback: PR revertでinstruction・strings・testが元へ戻る。生成済みpackageの互換性: marker形式・fenced・standaloneのimport受理は本変更でも不変のため、旧instructionで生成されたpackage宛の返答も引き続きimportできる。
- failure中のrollback: 該当なし (zero-write経路のみ)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | composer testの否定assertion + strings review | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.personalization.*'` |
| AC-2 | `Issue348` test: production symbol参照containment | 同上 |
| AC-3 | `Issue348` test: prose pinning | 同上 |
| AC-4 | `Issue348` test: golden fenced payload → pipeline → validate → completion | 同上 |
| AC-5/AC-6 | `Issue348` test: self-check/ask-before-final pinning | 同上 |
| AC-7 | 既存 `ImportNormalizerTest` / `ExchangeImportPipelineTest` / `IntentImportParserTest` 無変更成功 + composer test | 同上 |
| AC-8 | `Issue348` test: #345 fixture → typed failure固定 | 同上 |
| AC-9 | strings更新 + 既存instrumentation test成功 | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.exchange.ExchangeImportSurfaceInstrumentationTest` (emulator) |
| AC-10 | 全organizer unit test + `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug` | `./gradlew spotlessCheck` / `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-11 | emulator (ChatGPT mobile web) またはphysical deviceで request → interview/確認 → final artifact → import のfirst-pass成功を記録 | `docs/assessment/assets-348-*/README.md` |

含めるべき観点: unit/contract (上記)、UI/accessibility (AC-9。文言変更のため既存instrumentation laneの再成功)、failure injection (AC-8が該当)、property/performance/DB (本変更の対象外 — 該当経路に触れないことをAC-10の既存test群で確認)。

## Documentation updates

- [x] spec status/history (本spec + spec 205 change history注記)
- [ ] CONTEXT.md (新規用語なし。`AI-facing contract` をspec内で定義しCONTEXT.mdへの追加はreviewで要否判断)
- [ ] DESIGN.md (module構造変化なし。invariant 13の文言は不変のため更新しない)
- [ ] ADR (該当なし — Decision 1/2はspec Decisionsとして記録。変更困難性の3条件に至らない)
- [ ] AGENTS.md (該当なし)

## Execution checklist

- [ ] Current behavior reproduced. (既存 `ExchangePackageComposerTest` が旧構造を固定していることを確認)
- [ ] Tests fail for the missing behavior. (`Issue348` testを先に追加し、derivation/fence/self-checkが無い現状で失敗することを確認)
- [ ] Minimal implementation completed. (定数化 → internal化 → composer再構成 → strings)
- [ ] Migration/recovery verified. (該当なし。rollbackはrevert互換を確認)
- [ ] Full relevant verification completed. (verification tableの全行)
- [ ] PR evidence and remaining risks recorded.
