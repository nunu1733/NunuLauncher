# Implementation Plan: External Agent ExchangeのAI-facing contractをproduction truthと同期し初回Import成功率を上げる

> Issue: #348
> Spec: [spec.md](./spec.md)
> Status: draft (2nd review対応revision)

## Current evidence

- instruction部の正本は `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt` の `INSTRUCTION_HEADER` / `INSTRUCTION_FOOTER` (手書き定数)。marker形式の応答を要求している。schemaVersion名 (`personalized-intent-v3`) とpartial authoring文のみで、許可property・必須性・JSON型・enum値・数値制約は未列挙。
- production truth (確認済み):
  - `ContextExportContract` (`ContextExportModels.kt`): `SCHEMA_VERSION` / `INTENT_SCHEMA_VERSION` / `MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS=100` / `MAX_RATIONALE_CHARS=500` / `MAX_INTENT_ENTRIES=512` / `MAX_INTENT_UNRESOLVED=512` / `MAX_INTENT_BYTES=128KiB`。
  - `IntentCodec` (`ALLOWED_TOP_KEYS` / `ALLOWED_ITEM_KEYS` / `ALLOWED_GLOBAL_KEYS` / `ALLOWED_SEMANTIC_KEYS` — いずれも `private`)。
  - `Importance` (HIGH/NORMAL/LOW) / `ExportRegionKind` (TOP/MIDDLE/BOTTOM) / `Mobility` (MOVABLE/CONDITIONAL/FIXED/CANDIDATE) — public enum。
  - confidence 0..100: `IntentModels.kt:28` と `IntentCodec` のliteral重複。
  - 必須性 (decode判定): `schemaVersion` / `exportId` 必須 (`IntentCodec.decode`)、item `ref` 必須、`GroupSemantic` は `category` か `freeText` のany-of (`IntentModels.init`)。
  - decoderの互換受容 (canonical authoringより広い): `optString` は非string primitiveのcontentを受理、空 `desiredGroup` はabsentへ正規化して受理、FIXED itemの `preserve: false` も受理 (`IntentValidator` はFIXEDへsemantic 5 fieldのみ禁止)。
  - validator規則: `pageAffinity ∈ [0, grid.pageCount)`、FIXED → semantic 5 field禁止、CONDITIONAL → `desiredGroup`/`groupSemantic` 禁止、CANDIDATE → `preserve` 禁止、未知ref (item/`desiredGroup`/`unresolvedRefs`) → `UNKNOWN_REF`、重複 → `DUPLICATE_REF`、分割違反 → `INCOMPLETE_COVERAGE`。
- #345実測 ([assets-345-import-evidence](../../docs/assessment/assets-345-import-evidence/README.md)): (1) ChatGPT mobile webのmessage-copyは各行末に `\`+改行を入れmarker一致が壊れる、(2) code-block copyはclean JSONだが `globalPreference.organization` / `grouping` / 小文字enum / `0.82` で `SCHEMA_MISMATCH` / `INVALID_ENUM`、(3) 契約適合payloadはfile経由で「Proposal validated」まで成功。
- composerのunit testは `tests/unit/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposerTest.kt`。tests/unitはlawnchair moduleのtest source set (`build.gradle:370`) なので同一moduleの `internal` 参照が可能。
- import経路: `ExchangeImportPipeline.prepare/import` (envelope 1 MiB → `ImportNormalizer` → framing/codec → session束縛 → validator → completion)。golden/parity/policy testは `ExchangeImportPipelineTest` の既存harness (実session + `CanonicalStructuralInputs`) を流用する。
- 失敗copy: `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` (4 keyがmarker表現を参照)。instrumentation testは文字列をresource ID参照する (`ExchangeImportSurfaceInstrumentationTest.kt:270`) ので文言変更は非破壊。
- owner specの現行文 (実装PRで更新する対象): spec 205 Decision 2 (4section・INTENT marker明示)、「外部agentの自由な調査」scenarioのmarker要求文、「importの厳格な抽出」scenarioのframing失敗時「marker付きで再回答」案内、「validation reject」scenarioの再import記述、AC-1/AC-4のmarker参照。spec 329の「marker形式はcanonical form」前提文 (Outcome/D-1/Non-goals)。

## Design

### Modules and interfaces

- `IntentWireContract` (新規、`personalization` package内、internal): wire field descriptor。**構造派生のsource** としてproperty名集合 (4 group)・enum参照 (`Importance` / `ExportRegionKind` entries)・静的制限定数参照 (`freeText` 100 / `rationale` 500 / entries 512 / unresolved 512 / confidence 0..100)・schema version定数を保持し、**表示主張** として必須性・canonical JSON型・cross-field規則 (any-of・非空配列policy) をdataで保持する。decode logicの判定をdescriptorへ置き換えるrefactorは **しない** — 必須性・型・any-ofの主張はparity fixtureがproductionと同期する (spec Decision 1)。
- `IntentCodec` (最小変更): `ALLOWED_*_KEYS` をdescriptorの名前集合からの派生集合に差し替え (挙動不変)。`confidence` 検査を `ContextExportContract.CONFIDENCE_MIN/MAX` 参照に変更 (挙動不変)。presence/type判定logicは無変更。
- `ContextExportContract` (最小変更): `CONFIDENCE_MIN = 0` / `CONFIDENCE_MAX = 100` 追加。
- `IntentModels` (最小変更): `require(it in 0..100)` をcontract定数参照に変更 (挙動不変)。
- `ExchangePackageComposer` (変更): instruction部を「固定prose + descriptor派生output contract + production-enforced規則prose (mobility規則のproduction部分・page範囲・ref規則) + FIXED authoring policy」の合成へ変更。公開API (`compose` / `parsePackageStructure`) とimmutable-value契約 (spec 205 AC-12) は不変。composerはexport JSONを解釈しない (現行どおり。per-export情報はCONTEXT data参照に委ねる)。
- 新規 `tests/unit/app/lawnchair/organizer/personalization/exchange/Issue348AiFacingContractSyncTest.kt`: AC-2/3/4/5/6/8の検証面。descriptor一致 / composer派生containment / descriptor表示主張のparity fixture / production-enforced parity matrix / authoring policy matrix / golden / #345 fixture。すべて `ExchangeImportPipeline.import` 経由。
- strings (en/ja): spec AC-9の4 keyの文言更新のみ。
- seam: すべて既存 (`ExchangePackageComposer.compose`、`ExchangeImportPipeline.import`、`IntentCodec.decode`、`IntentValidator.validate`)。新規のpublic interface/adapterは作らない (descriptorは同一module内internal)。

### Data flow

compose時: `IntentWireContract` → output contract section (名前・canonical型・必須性・enum・制約) を整形 → 固定prose (You may / You must [production-enforced規則 + FIXED policy + partial authoring] / self-check / Response format) と結合 → CONTEXT marker間に従来どおりexport JSON単行 → immutable package文字列。

import時: 無変更 (envelope → normalizer → framing → decode → session束縛 → validator → completion)。本planはこの経路の挙動に1行も触れない (allow-list派生化と定数化は同等内部refactor)。

test時: descriptor主張 ↔ production挙動の同期は、主張ごとのfixtureを `ExchangeImportPipeline.import` に通して固定する。descriptorの主張を矛盾する値へ変更すると該当fixtureが失敗する (乖離検出の自身のテスト)。

### Alternatives rejected

- decode logicのdescriptor駆動refactor (2nd review 高1の選択肢(A)): presence/type/any-of判定をdescriptor共有helperへ置き換えるとAC-10 (挙動不変) のrisk面が広がり、最小縦切りに対して変更が過大。代わりに保証主張を正直に分類し、descriptor主張はparity fixtureで同期する (選択肢(B))。
- build時codegen/metadata生成: instructionという表示物への効用がruntime derivationと同じで、build stepと二次表現だけが増える (spec Decision 1(b))。
- 手書きprose + contract testのみ: 構造派生なしではtest緩和後のdriftを防げない。prose pinningとparity/policy testとして補助採用。
- production-enforced規則とauthoring policyの混在表記: `desiredGroup: []` (受理される) やFIXED `preserve: false` (受理される) を禁止規則として書くとvalidatorと矛盾する。2層に分離 (spec Decision 2、2nd review 中2)。
- instructionでのmarker要求維持: #345実測のmessage-copyで壊れる既知の形式を要求し続けることになる (spec Decision 2)。
- `ImportNormalizer` による `\` エスケープ救済: verbatim substring制約 (spec 329 D-6) を破り、semantic repairに接尾する。発生源 (canonical authoring form) で緩和する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `IntentWireContract.kt` (新規) | wire field descriptor (名前集合 / enum参照 / 静的定数参照 / 必須性・canonical型・cross-fieldの表示主張)。4 group分 | AC-2の共有source-of-truthと表示層 |
| `IntentCodec.kt` | allow-listをdescriptor名前集合から派生へ、confidence検査を定数参照へ | 挙動不変の単一化 |
| `ContextExportModels.kt` | `CONFIDENCE_MIN`/`CONFIDENCE_MAX` 追加 | confidence制約の単一化 |
| `IntentModels.kt` | `0..100` literal → contract定数 | 同上 |
| `ExchangePackageComposer.kt` | instruction再構成 (Goal / You may / Output contract (derived) / You must (production-enforced + FIXED policy + partial authoring) / self-check / Response format (fenced))。footer更新 | spec Decisions 2/3 |
| `ExchangePackageComposerTest.kt` | section構成・fence要求・marker非要求・partial authoringのassert更新 | 既存assertが旧構造固定のため |
| `Issue348AiFacingContractSyncTest.kt` (新規) | descriptor一致 / 派生containment / 主張parity fixture / parity matrix / policy matrix / golden / #345 fixture / self-check・ask-before-final pinning / repair導線不在 | AC-1〜6, 8 |
| `strings.xml` / `values-ja/strings.xml` | 4 keyをfenced形式参照へ (許容回復文の範囲で) | AC-1/AC-9 |
| `specs/205-external-agent-exchange/spec.md` | **実装PRで必須**: Decision 2・agent scenario・framing失敗時の再回答案内・validation reject後位置づけ・AC記述をcanonical authoring form前提へ更新 + change history (spec 348所有) | spec Decision 6。AC-9 blocking |
| `specs/329-import-normalizer/spec.md` | **実装PRで必須**: Outcome/D-1/Non-goalsの `canonical form` 語とmarker要求前提をaccepted framing / producer要求の分離へ更新 + change history (spec 348所有) | 同上 |
| spec 348 front-matter / docs | status更新 | 受入手続き |

## Migration and recovery

- migration不要 (DB/schema/contract不変。allow-list派生化と定数化は同等refactorで、既存unit test群が挙動不変を保証する)。
- rollback: PR revertでinstruction・descriptor利用・strings・spec更新が元へ戻る。生成済みpackageの互換性: marker形式・fenced・standaloneのimport受理は本変更でも不変のため、旧instructionで生成されたpackage宛の返答も引き続きimportできる。
- failure中のrollback: 該当なし (zero-write経路のみ)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | spec本文の許容/禁止固定 + instruction/footer + 対象4 stringsの否定assertion | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.personalization.*'` |
| AC-2 | `Issue348` test: descriptor↔codec allow-list一致 + composer出力のdescriptor派生containment + descriptor表示主張のparity fixture (主張変更で失敗することを含む) | 同上 |
| AC-3 | `Issue348` test: production-enforced parity matrix (期待failure 1種固定) + authoring policy matrix (canonical ⊆ accepted) | 同上 |
| AC-4 | `Issue348` golden test: canonical fenced payload → pipeline.import → completion | 同上 |
| AC-5/AC-6 | `Issue348` test: self-check / ask-before-final / 独自property禁止のpinning | 同上 |
| AC-7 | 既存 `ImportNormalizerTest` / `ExchangeImportPipelineTest` / `IntentImportParserTest` 無変更成功 + composer test | 同上 |
| AC-8 | `Issue348` regression test (#345 fixture → typed failure固定) | 同上 |
| AC-9 | strings更新 + 既存instrumentation test成功 + spec 205/329 diff (実装PR内で完了確認) | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.exchange.ExchangeImportSurfaceInstrumentationTest` (emulator) |
| AC-10 | 全organizer unit test + `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug` | `./gradlew spotlessCheck` / `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-11 | evidence protocol (head SHA / schemaVersion群 / tier / provider・surface・model・日付 / package指紋 / interview有無 / response指紋 / copy affordance / transport / 試行回数。全attempt記録) + Decision 7 privacy policy (synthetic profile・sanitized artifact・raw commit禁止) を満たす記録 | `docs/assessment/assets-348-*/README.md`。最低1本: ChatGPT mobile web系で fenced response → code-block copy または全文copy → first import成功 |

含めるべき観点: unit/contract (上記)、UI/accessibility (AC-9)、failure injection (AC-8 + parity matrix)、property/performance/DB (対象外 — AC-10の既存test群で経路不変を確認)。

## Documentation updates

- [x] spec 348 status/history (本spec)
- [ ] spec 205 normative更新 + change history (**実装PR必須・AC-9 blocking**)
- [ ] spec 329 normative更新 + change history (**実装PR必須・AC-9 blocking**)
- [ ] CONTEXT.md (用語は本specのDomain languageで定義。accepted framing / canonical authoring formの分離はspec 205/329更新時に各spec内で整合。CONTEXT.md側の「交換フレーミング」項はframing規則の記載のみで不変 — 実装PRで要否再判断)
- [ ] DESIGN.md (module構造変化なし。invariant 13の文言は不変 — 実装PRで要否再判断)
- [ ] ADR (該当なし — Decision 1/2はspec Decisionsとして記録)
- [ ] AGENTS.md (該当なし)

## Execution checklist

- [ ] Current behavior reproduced. (既存 `ExchangePackageComposerTest` が旧構造を固定していることを確認)
- [ ] Tests fail for the missing behavior. (`Issue348` testを先に追加し、descriptor/fence/self-checkが無い現状で失敗することを確認)
- [ ] Minimal implementation completed. (descriptor → codec派生化/定数化 → composer再構成 → strings → spec 205/329更新)
- [ ] Migration/recovery verified. (該当なし。revert互換と旧package互換を確認)
- [ ] Full relevant verification completed. (verification tableの全行)
- [ ] PR evidence and remaining risks recorded.
