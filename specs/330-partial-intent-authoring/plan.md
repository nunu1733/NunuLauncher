# Implementation Plan: PersonalizedIntent authoring contractの簡素化 (partial authoring)

> Issue: #330
> Spec: [spec.md](./spec.md)
> Status: draft — specのdraft decisions D-1〜D-6がowner受入れになるまで実装を開始しない (AGENTS.md / [github-workflow.md](../../docs/project/github-workflow.md) start gate)。
> Baseline: `origin/main` = `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (2026-09-16時点。#331実装 PR #333 merge + docs PR #334 merge 後)。

## Re-entry status

- 2026-09-16: 初回起草。Issue #330 (2026-09-16T08:04:05Z作成、comment 0件・owner decisionなし) を確認済み。過去のspec/plan snapshotは存在しない (`specs/330-*` なし)。baseline SHAは前workerの記録と同一 (`aab0d293d1`) であり、起草時点の追加差分確認は不要。
- 2026-09-16: Re-entry (review Required finding対応)。`git fetch origin main` 後、origin/mainはbaseline `aab0d293d1` から不動であることを確認 (validator/codec/composer/instruction周辺の差分なし)。Issue #330 review comment 5698080251 のRequired「bare `{"ref":"X"}` の semantic identity を Spec で固定する」を受け、spec に **D-6** (bare entry = 全semantic fieldがnullのitemIntents entry をcompletionでcanonical unresolvedへ正規化し、明示unresolved / 省略 / bare entryを同一semantic identityとする。review提示の選択肢1) を追加。本planには bare entry正規化の設計 (下記Design) と stable identity / replay test (下記Verification) を反映した。正本実装 (`IntentPlannerAdapter` / `IntentIdentity` / `FullRunExecution` のpreference消費) を再読し、all-null `ItemPreference` がplanner効果を持たない事実を再確認済み。

## Current evidence

`origin/main` (`aab0d293d1`) で確認した実装事実 (実装開始時に再検証する):

- **schema constants**: `organizer/personalization/ContextExportModels.kt` — `SCHEMA_VERSION = "personalization-context-v2"` (L14)、`INTENT_SCHEMA_VERSION = "personalized-intent-v2"` (L26)。`PersonalizationContextExportV1.init` が `capabilities.intentSchemaVersion == INTENT_SCHEMA_VERSION` を要求 (L275)。`MAX_INTENT_ENTRIES = 512` / `MAX_INTENT_UNRESOLVED = 512` / `MAX_INTENT_BYTES = 128 KiB`。
- **typed model**: `organizer/personalization/IntentModels.kt` — `PersonalizedIntentV1(exportId, itemIntents, unresolvedRefs, globalPreference, rationale, confidence)`。`ItemIntent(ref, importance, desiredGroupRefs, groupSemantic, pageAffinity, regionAffinity, preserve)` — 全semantic fieldがnullableであり、refのみのentryも合法。codecは `itemIntents` / `unresolvedRefs` の欠落・nullを空listとして受理 (`IntentCodec.kt` L173-174, L266-267)。**schemaのfield集合はv3でも変更不要** (変わるのはvalidatorのcoverage規則のみ)。
- **coverage検証 (本Issueの変更主対象)**: `organizer/personalization/IntentValidator.kt` L59-71 — (1) `unresolvedRefs` 内重複 → `IncompleteCoverage`、(2) covered ∩ unresolved ≠ ∅ → `IncompleteCoverage`、(3) `covered + unresolved != exportRefs` (全数cover要求) → `IncompleteCoverage`。(1)(2) はv3で維持、(3) が廃止対象。
- **mobility検証 (無変更)**: 同file L86-107 — FIXED refへの `importance`/`pageAffinity`/`regionAffinity`/`desiredGroupRefs`/`groupSemantic` → `MobilityContradiction(ref)`。CONDITIONAL (widget) への `desiredGroupRefs`/`groupSemantic` → reject。CANDIDATE refへの `preserve` → reject (#331)。`preserve` はFIXED refに対して値を問わず許可。
- **validation成功path**: `IntentValidator.validate` は `IntentValidation.Validated(ValidatedPersonalizedIntent(intent, export, session, identity))` を返す。identityは `IntentIdentityCalculator.identity(intent)` (authored文書そのものから計算)。
- **identity**: `organizer/personalization/IntentIdentity.kt` — canonical row grammar: `intent|<version>|<exportId>` / `global|...` / `rationale|...` / item行 (`item|ref|importance|group|semantic|page|region|preserve`、sorted by ref) / `unresolved|ref` (sorted)。digest = sha256。`IntentIdentity.init` が `schemaVersion == INTENT_SCHEMA_VERSION` を要求。
- **planner接続 (無変更semantic)**: `organizer/personalization/IntentPlannerAdapter.kt` — `validated.intent.itemIntents` のみを `ItemPreference` へ投影。`unresolvedRefs` はpreferenceを生まない (diagnosticのみ)。したがって **omission ≡ explicit unresolved のplanner効果等価は、completed表現からauthored decisionのみを投影すれば自動的に成立** する。
- **codec**: `organizer/personalization/IntentCodec.kt` — decode時 `schemaVersion != INTENT_SCHEMA_VERSION` → `SchemaMismatch` (L67)。encode時は定数を書き出す。**定数bumpのみでv1/v2拒否・v3受入が切り替わる** (dual-versionなし)。
- **exchange package instruction**: `organizer/personalization/exchange/ExchangePackageComposer.kt` — INSTRUCTION_HEADER の "You must" に `Cover every "ref" exactly once across "itemIntents" and "unresolvedRefs"` があり、Response formatが `personalized-intent-v2` を指定。**この2箇所がv3で書換対象**。
- **import pipeline (順序無変更)**: `organizer/personalization/exchange/ExchangeImportPipeline.kt` — envelope limit → framing (`IntentImportParser`) → decode (`IntentCodec`) → session照会 → expiry → structural digest → 再構築 (`SessionExportReconstructor`) → validation (`IntentValidator`)。completionは最後のvalidation段の内側 (成功path) に挿入する。
- **UI失敗表示**: `organizer/ui/exchange/ExchangeFlowUi.kt` L985 — `IncompleteCoverage` → `R.string.exchange_failure_incomplete_coverage` (17種の1つ)。種類数は不変、文言の条件説明のみ更新。
- **session store (無影響)**: `organizer/integration/AndroidExportSessionStore.kt` — record `SCHEMA_VERSION = 2` / file `organizer_personalization_export_session_v2.json`。recordはintent schema versionを保持しないためv3 bumpで無影響。`SessionExportReconstructor.kt` L146 は定数経由で `intentSchemaVersion` を再構築するため定数bumpで自動追従。
- **既存test (回帰対象)**: `tests/unit/app/lawnchair/organizer/personalization/` の `IntentValidatorTest` (coverage partition test `partialResponsesViolateTheCoveragePartition` は「missing ref」caseの期待値がv3で反転する → 更新対象)、`IntentCodecTest`、`IntentPlannerAdapterTest`、`ExchangeTargetScopeCouplingTest`、`exchange/ExchangeImportPipelineTest`、`integration/exchange/ExchangeFlowControllerTest.kt`、`AndroidExportSessionStoreTest.kt`、`ui/exchange/ExchangeFlowStateHolderTest.kt`。

## Design

### Modules and interfaces

純粋package `organizer/personalization/` の内側の変更のみ (purity guard対象のまま。Android境界・DB・network無変更)。

```text
organizer/personalization/
├── ContextExportModels.kt   # SCHEMA_VERSION / INTENT_SCHEMA_VERSION → v3 (定数のみ)
├── IntentModels.kt           # 無変更 (field集合はv2と同一)
├── IntentCodec.kt            # 無変更 (定数経由でv3を検証・出力。ALLOWED_KEYS等も無変更)
├── IntentValidator.kt        # coverage規則のnarrow (全数cover要求の廃止) + 成功pathでcompletion呼出
├── IntentCompletion.kt       # 新規 (pure): authored intent + export refs → CompletedPersonalIntent
├── IntentIdentity.kt         # canonicalRepresentation を completed形式から計算 (row grammar維持)
└── IntentPlannerAdapter.kt   # validated.completed の Authored decision のみ投影 (semantic無変更)

organizer/personalization/exchange/
└── ExchangePackageComposer.kt # instruction部: coverage要求の部分authoring文言へ + version表記v3

organizer/ui/exchange/ExchangeFlowUi.kt + lawnchair-res strings (values/, values-ja/)
                              # INCOMPLETE_COVERAGE 文言の条件更新 (17種の数は不変)
```

- **`IntentCompletion.kt` (新規pure module)**:
  - `sealed interface RefDecision { data class Authored(val intent: ItemIntent); data object UnresolvedAuthored; data object UnresolvedByOmission }` — `Authored` は **少なくとも1つのsemantic fieldを持つentryのみ** となる (bare entryはcompleterが `UnresolvedAuthored` へ正規化。spec D-6)。
  - `data class CompletedPersonalIntent(val exportId: String, val decisions: Map<String, RefDecision>, val globalPreference: GlobalPreference?, val rationale: String?, val confidence: Int?)` — 不変条件: `decisions.keys == export refs全体` (完全分割)。validatorがpartition検証 (disjoint) 済みのauthored intentからのみ構成されるため、不変条件は構築時に要求 (require) できる。
  - `fun complete(authored: PersonalizedIntentV1, exportRefs: Set<String>): CompletedPersonalIntent` — pure・total・deterministic。未言及refへは `UnresolvedByOmission` のみ付与し、**全semantic fieldがnullのitemIntents entry (bare entry) は `UnresolvedAuthored` へ正規化する** (spec D-6)。正規化を含めcompleterが行うのはrefへの状態の付与のみであり、`ItemIntent` のfield値を生成・書換する分岐を持たない (spec D-1/D-2/D-4/D-6)。
  - diagnostics用の導出property: `omittedCount` / `authoredUnresolvedCount` (明示unresolved + 正規化済bare entry) / `authoredItemCount` (UI表示・test用。型のまま提供し、個人情報を含まない)。bare entryであったことのfine-grained provenanceは、diagnostics用に保持するauthored文書から取得する (completed側では区別しない)。
- **`IntentValidator.validate` (seam無変更)**: signatureはそのまま。変更点は (1) L69-71の全数cover検査を削除 (overlap・unresolved内重複は維持)、(2) 成功pathで `complete(intent, exportRefs)` を呼び、`ValidatedPersonalizedIntent` が `authored: PersonalizedIntentV1` と `completed: CompletedPersonalIntent` の両方を保持し、`identity` はcompleted形式から計算する。呼び出し側 (`ExchangeImportPipeline`、`ExchangeFlowController`、`ManualOrganizationRun`) は同じseamを叩き続ける。
- **`IntentIdentity` / `IntentIdentityCalculator`**: `canonicalRepresentation(completed: CompletedPersonalIntent)` へ変更。item行は `Authored` decision (semantic fieldを≥1持つ) から、`unresolved|ref` 行は `UnresolvedAuthored` (明示unresolvedと正規化済bare entryの両方) と `UnresolvedByOmission` から同一形式で生成 (D-5 + D-6: 明示/省略/bare entryは同一identity)。`IntentIdentity.init` の `schemaVersion` 要求は定数bumpで自動的にv3へ。
- **`IntentPlannerAdapter.project`**: `validated.completed.decisions` の `Authored` のみを `ItemPreference` へ投影 (`refToItem` 解決は現行どおりsession mapから)。`UnresolvedAuthored` / `UnresolvedByOmission` は投影対象外。D-6により現行が生んでいたall-null `ItemPreference` (planner効果なし。`FullRunExecution.kt` の全consumerが個別fieldのみ参照) は生成されなくなるが、効果は同一のためplanner入力のsemanticは無変更。`PersonalizedIntentProjection` の型・不変条件は無変更。
- **`ExchangeImportPipeline`**: stage構成・順序・失敗統合 (`ExchangeImportFailure` 17種) は無変更。completionはvalidator内部で行われるためpipelineへの新規stageは追加しない (#329 normalizerは将来 `IntentImportParser` とcodecの間に#331と同じ境界で挿入される。本planでは触れない)。

### Data flow (v3、変更点は太字)

```text
exchange reply text
  → envelope limit + framing (#205、無変更)
  → [将来: Import Normalizer (#329。外形のみ。ref追加禁止)]
  → IntentCodec.decode (schemaVersion = personalized-intent-v3 検証。未知versionは SCHEMA_MISMATCH)
  → session binding / expiry / structural digest / reconstruction (無変更)
  → IntentValidator.validate
      ├─ unknown/duplicate/enum/forbidden/mobility/session/stale 検査 (無変更)
      ├─ coverage: disjoint (overlap・unresolved内重複) のみ (全数cover要求は廃止)
      └─ 成功時: **IntentCompletion.complete(authored, exportRefs) → CompletedPersonalIntent**
  → ValidatedPersonalizedIntent(authored, completed, identity over completed)
  → **IntentPlannerAdapter は completed の Authored decision のみ投影** (planner入力のsemantic無変更)
  → OrganizationPlanner.plan → preview (#194) → confirm (#195) → apply (spec 13。無変更)
```

### Alternatives rejected (実装面)

- **境界層 (`ExchangeImportPipeline`) で `unresolvedRefs` へ未言及refを書き足してからvalidatorへ渡す (案Bの実装形)**: validatorの検証対象が人工物になり、`INCOMPLETE_COVERAGE` 等のfailure帰属がauthored文と補完結果で曖昧になる。validator seam内で検証→completionの順に分離する (spec D-1/D-4)。
- **`PersonalizedIntentV1` のlistへcanonical unresolvedを書き足した「拡張済みintent」を同一型で流す**: complete/authoredの区別が型で保証されず、「partial表現がそのままPlannerへ流れる」違反を検出できない。独立した `CompletedPersonalIntent` 型 + decisions map で完全分割を型不変条件にする。
- **`RefDecision` を既存 `ItemIntent` のnullable field (例: `omitted: Boolean`) で表現する**: field集合変更になる (v3はfield無変更が前提)。新規decision型で表現する。
- **bare entryを独立したauthored state (4つ目のdecision、例: `AuthoredBare`) として保持しidentity上も区別する**: planner効果が同一であるpayload群 (bare entry / 明示unresolved / 省略) に対してidentityだけが分岐し、dedupe / replay契約が表現形式に依存する (spec D-6不採用理由)。bare entryはcompletionで `UnresolvedAuthored` へ正規化し、表現形式の区別はauthored文書 (diagnostics) に任せる。
- **omission数のUI表示を本実装に含める**: 表示導線は #332/#205 UIの対象。本実装は `CompletedPersonalIntent` が計数を提供するところまでとし、diagnostics制約 (個人情報を出さない) の範囲でtestする。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/ContextExportModels.kt` | version定数 v3 bump (commentに#330引用) | #204のimmutable semantic version規則 (spec D-3) |
| `organizer/personalization/IntentCompletion.kt` (新規) | `RefDecision` / `CompletedPersonalIntent` / `complete()` | omission意味の単一正本 (推測不在の構造的保証) |
| `organizer/personalization/IntentValidator.kt` | coverage narrow + 成功path completion + `ValidatedPersonalizedIntent` 拡張 | 検証とcompletionのseam統一 (呼び出し側・test同一seam) |
| `organizer/personalization/IntentIdentity.kt` | canonical representationのcompleted形式化 | D-5 + D-6 (明示/省略/bare entry unresolvedの同一identity) |
| `organizer/personalization/IntentPlannerAdapter.kt` | completedのAuthoredのみ投影 | planner入力semantic無変更の明示化 |
| `organizer/personalization/exchange/ExchangePackageComposer.kt` | instructionのcoverage文言・version表記 | authoring責任の縮小をconsumerへ通知 |
| `organizer/ui/exchange/ExchangeFlowUi.kt` + strings (ja/en) | `INCOMPLETE_COVERAGE` 文言更新 | 17種維持・条件説明の整合 |
| tests (下記Verification) | validator/completion/identity/codec/instruction corpus | spec AC-2〜AC-8 |
| `specs/204,205,331/spec.md` Change history、`CONTEXT.md`「パーソナライゼーション意図」定義、`DESIGN.md` gate 12行 | v3拡張の記録 (実装PRと同一PRで) | AGENTS.md正本分担 |

`IntentCodec.kt` / `IntentModels.kt` / `SessionExportReconstructor.kt` / `AndroidExportSessionStore.kt` / `ExchangeImportPipeline.kt` / planner・allocator実装 / preview・confirm・apply path は **無変更** (定数bumpの自動追随を除く)。

## Migration and recovery

- 永続化される旧データは存在しない: intent本文は非永続 (spec 204)、export session record (v2) はintent schemaを保持せず無影響、durable status projectionはdigestを保持しない。
- v2 eraのactivity session (TTL 24時間以内) 宛に返ってきたv2 intentは、bump後 `SCHEMA_MISMATCH` で再export案内になる (spec 331のv1→v2移行と同じ一時的扱い。spec 205の既存失敗UIがそのまま案内する)。
- DB schema・backup/restore・launcher favoritesへの影響はゼロ。`risk: layout-data` はintentがplanner入力に参加する契約変更であることに由来し、実装PRでhigh-risk gate (CI `final-status` + `docs/assessment/`) を満たす。
- rollback: 実装revertで完了 (v3 sessionが残る場合はTTL自然失効のみ)。

## Verification

実行command: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (building.mdの既存lane)。

| AC | Test | 内容 |
|---|---|---|
| AC-2 | `IntentCompletionTest` (新規) | omission → `UnresolvedByOmission` のみ / `ItemIntent` field不生成の全field走査 / bare entry → `UnresolvedAuthored` 正規化 (D-6) / `preserve: false` 等の非null entryは正規化対象外 / 完全omission intent受理 / completeness (decisions keys == export refs) / determinism (同入力→同出力) |
| AC-2 | `IntentPlannerAdapterTest` 拡張 | omission済refとexplicit unresolved refのplanner投影が完全一致する等価test (bare entry由来refを含む3表現等価) |
| AC-3 | `IntentValidatorTest` 既存corpus回帰 + 悪意corpus | FIXED refへの各semantic fieldの `MOBILITY_CONTRADICTION` (v2のtestがそのままgreen維持)、CANDIDATE `preserve` reject維持、`WidgetIntentAuthorityTest` 等planner authority系suiteの無修正通過 |
| AC-4 | `IntentValidatorTest` / `IntentCompletionTest` | complete表現が全export refをちょうど1回含むpartition property / adapter入力がcompleted由来であることの契約test / **D-6 stable identity test: 同一exportに対しbare entry / 明示unresolved / 省略の3表現が同一digest・同一projection** / **replay test: 同一semantic内容のintentの再import (異なる表現形式) が同一identityを返し、dedupe (identity同値判定) が同一intentとして扱う** |
| AC-5 | `IntentValidatorTest` 既存 `UNKNOWN_REF` corpus 回帰 + bare entryの未知ref | 未知ref・未選択候補ref・新規install相当・全field null bare entryの未知refが引き続きreject (D-6: 正規化は検証後のため自動受理されない) |
| AC-6 | `IntentCodecTest` 拡張 | v1/v2文書 `SCHEMA_MISMATCH` / v3受入 / encodeのversion出力 / identity `schemaVersion` assert / `AndroidExportSessionStoreTest` 無修正回帰 (session無影響) |
| AC-7 | validator/completion corpus (4系統) | coverage omission受理 / fixed omission受理+planner保持 / explicit unresolved受理 / malicious lock override reject — Issue ACの直接対応4fixture |
| AC-8 | 境界の固定 | 本specの順序図・文言 (実装時点で#329未実装なら、`ExchangeImportParser` がrefを追加しない既存testで代替) |
| (AC-1) | review対象 | spec比較表 (実装不要) |

加えて: `IntentIdentityCalculator` 同一性test (明示unresolved intent / omission intent / bare entry intentの3表現が同一digest。D-6 stable identity)、同一semantic内容のreplay同値test (表現形式の異なる再importが同一identity・同一projectionを返す)、instruction契約test (`ExchangePackageComposer` 出力に部分authoring文言とv3 versionが含まれる)、`ExchangeImportPipelineTest` / `ExchangeFlowControllerTest` / `ExchangeFlowStateHolderTest` / `ManualOrganizationRunTest` の回帰 (coverage期待値の反転分は更新)。

## Execution checklist

1. spec受入れ (owner) — D-1〜D-6の承認。これが開始gate。
2. `IntentCompletionTest` 先行 (TDD): `RefDecision` / `CompletedPersonalIntent` / `complete()` とcompleteness/推測不在property、bare entry正規化property (D-6)。
3. validator coverage narrow + `ValidatedPersonalizedIntent` 拡張 (authored + completed + identity over completed)。`IntentValidatorTest` のmissing-ref期待値反転と4系統corpus (AC-7) 追加。
4. identityのcompleted形式化 + 同一性test (D-5/D-6: 明示unresolved / omission / bare entryの3表現同一digest、replay同値)。
5. version定数 v3 bump + codec test (v1/v2拒否) + `ExchangePackageComposer` instruction文言 + 文言契約test。
6. `IntentPlannerAdapter` のcompleted消費化 + 投影等価test。planner authority系suite回帰。
7. UI文言 (ja/en) 更新。全既存suite回帰。
8. 正本更新を同一PRへ: specs 204/205/331 Change history、`CONTEXT.md` 用語、「DESIGN.md」gate 12行参照。
9. PR evidence記録 (実行command・結果・未確認範囲)。high-risk gate (audit doc) を満たす。

## Dependencies / blockers

- **spec受入れ (D-1〜D-6) が唯一のblocker**。owner decisionなしでは実装しない。
- #329 (Import Normalizer) はblockerではない (境界は順序図で固定済み。#329実装時にnormalizerがrefを追加しないtestを#329側で追加する)。
- #327 (interview-first) はinstruction文言の最終copyに関与するが、本実装の意味論部分と独立 (文言調整は#327または本実装の後続で可能)。
- #206 (managed AI) は本変更の下流consumer (superset互換により影響なし。#206起草時にv3を参照)。

## Risks

- **coverage緩和による「AIがほとんど判断していないintent」の黙示受理**: 従来はerrorで見えていた判断率の低下が成功に変わる。緩和はpreview (#194)・確認 (#195) が従来どおり最終保護であることに依存する設計であり、importはapplyではない。`CompletedPersonalIntent` の計数をdiagnostics用に提供し、UI表示は#332/#205に委ねる。
- **validatorとcompleterの責務混在**: completionが検証 (例: unknown refの黙示解決) を侵すとfail-closedが崩れる。completerは「検証済みintent + export refs」のみを受け取り、新たなreject分岐を持たない設計で分離する。
- **identity互換**: digest計算対象がauthored→completedへ変わるため、同一内容でもv2時代のdigest値とv3のdigest値は一致しない。永続互換はない (identityはrun内provenanceのみ) が、test fixtureのdigest期待値は更新が必要。
- **D-6による表現形式のidentity消失**: bare entry / 明示unresolved / 省略の3表現が同一digestを持つため、digestだけではAIがどの形式で書いたかを区別できない。これは「同じplanner効果 ⇒ 同じidentity」の意図された結果であり、衝突ではなく意味的正規化である。表現形式の区別が必要な場合 (診断・分析) はauthored文書から行う。この正規化の境界 (bare entryでないentryは正規化されない) をtestで固定する。
- **`INCOMPLETE_COVERAGE` の意味変化**: v2の「全数cover違反」の認識を持つ利用者・文書が混在する。失敗表示文言とspecs 204/205のChange historyで明示する。

## Explicitly unverified areas

- #329 normalizer実装後の実挙動 (未実装。境界は文言・順序図で固定のみ)。
- #327とのinstruction合成後の最終prompt copy (実装PRで調整)。
- 実AI (ChatGPT/Gemini) によるv3部分authoringの実利用率・失敗率改善の実測 (device evidenceはspec 205系のevidence PR文化に従い、必要なら後続Issue)。
- `CompletedPersonalIntent` の最終的な型名・diagnostics計数のUI表示形式 (実装PRで確定してよい軽微事項)。
