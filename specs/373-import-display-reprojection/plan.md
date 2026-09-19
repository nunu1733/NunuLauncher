# Implementation Plan: T-18取り込み結果の手段別失敗投影とT-17/T-18契約の確定

> Issue: #373
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-19時点のmain（`a2b6aba3189922a6e1cb314eb52cff1d1f973e02`）での確認である。
`3076bdae7e` → `a2b6aba318` の差分は `docs/product/organizer-disposition-migration.md` と
`docs/project/seed-backlog.md` のみ（docs-only）であり、code事実への影響はない。

### 現行の取り込み結果面（T-18失敗面）

`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`（1861行）:

- `ExchangeScreen.ImportOutcomeScreen(outcome, rawText)`（L112-115）: 失敗面のstate。
  `rawText` はspec 332 AC-7のretention boundary（表示中のみのprocess memory保持・1箇所・
  遷移で破棄）を担う唯一のfieldである。
- `ExchangeImportOutcome` composable（L1639-1724）: title「取り込み結果」+
  **typed失敗文言の1行表示**（`exchangeFailureText`）+ 認識情報row（framing/version/entry数、
  L1671-1691）+ raw detail折りたたみ（L1692-1715。`heightIn(max = 240.dp)` + verticalScroll）+
  generic retry hint（`exchange_import_retry_hint`）+ 「再取り込み」button（`holder::openImport`、
  L1720-1722）。**typed失敗20種がprimaryに現れる現行構造であり、本Issueの再構成対象**。
- `exchangeFailureText(failure: ExchangeImportFailure)`（L1802-1821）: envelope 4種 →
  normalization 2種 → contract 14種のdispatch。primary表示の変換点である。
- `exchangeContractFailureText(failure: IntentValidationFailure)`（L1831-1860）: contract 14種の
  網羅 `when`（compile-time oracle）。**T-18失敗面以外に2つのcall siteが存在する**:
  `ManualOrganizationPreferences.kt` L415（`Selecting.scopeRejection` 行）と L478
  （`ScopeMismatchFailed` state）。#373ではこの2面を変更しない（#369/#375の所有）。
- `exchangeStatusTextResource(kind: ExchangeStatus.Kind)`（L1779-1796）: status行16種の
  純粋mapping。source失敗（`CLIPBOARD_EMPTY`/`CLIPBOARD_NOT_TEXT`/`FILE_READ_FAILED`）、
  busy系（`RUN_BUSY`/`IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`）、`CTA_START_FAILED`、
  `IMPORT_DISCARDED` 等を含む。status行はhost面の末尾item（L1006-1017）として表示される。
- `ExchangeImportDisplayInfo` / `exchangeImportDisplayInfo`（L1753-1769）: spec 332 D-5/D-6の
  parse-first表示model（framing/version/entry数の純粋projection）。詳細展開の認識情報として
  維持する。

### 現行の取り込み成功面（T-18成功面）とattempt機構

- `ExchangeScreen.ImportSuccess(summary, entryKind, attemptToken, continuing)`（L130-135）。
- `ExchangeImportSummary` / `exchangeImportSummary`
  （`lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportSummary.kt`）:
  privacy-safe件数のみのdata class（label/ref/自由文field不在が型で保証される）。
  認識件数・判断なし合算・内訳4種・#337のgroup kind split（`builtInCategoryCount` /
  `userCategoryCount` / `proposedGroupCount`）・`minimizeMovement`・`scopeCandidateCount`。
- `ExchangeImportSuccess` composable（L1503-1636）: 見出し（success/warning）+ 認識件数 +
  判断なし + 候補数 + breakdown（groupは既存カテゴリ合算行 `exchange-import-summary-existing-category`
  = built-in + user-defined と提案グループ行 `exchange-import-summary-proposed-group` の2行）+
  全体方針行 + 未適用行 + CTA（idle `exchange_import_cta_idle` / run-in
  `exchange_import_cta_run_in`）+ 破棄 `exchange_import_discard`（=「破棄して閉じる」）。
  **本Issueでは表示変更しない（回帰固定のみ）**。
- `ExchangeImportSuccessBackHandler`（L1462-1500）: hosting画面level・常時compositionの
  Back interception（spec 328 AC-7）。確認dialog（`exchange_import_discard_confirm_*`）、
  CTA処理中の不受理込み。
- `ExchangeFlowStateHolder`（L217-827）: `import()`（L628-642。`strategyArbiterBusy()` gate →
  attempt採番 → IO validate）、`settleImport()`（L651-687。attempt anchor。成功→`ImportSuccess`、
  失敗→`ImportOutcomeScreen(outcome, rawText=replyText)`）、`continueImport()` /
  `settleContinue()`（L745-800）、`discardImport()`（L819-826。continuing中不受理・
  session invalidateなし）、`openImport()`（L261-265。`Importing("")` へ復帰 — raw text破棄）。
  **本Issueではanchor/freeze/success機構に触れない**。

### 現行の失敗分類（20種）

`lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipeline.kt`:

- `ExchangeImportFailure`（L204-210）: `Envelope`（`ExchangeEnvelopeFailure` 4種:
  `InputOversize`/`FramingMissing`/`FramingAmbiguous`/`FramingEmpty`。`IntentImportParser.kt` /
  `ExchangeContract.kt` 所有）+ `Normalization`（`ImportNormalizationFailure` 2種:
  `AmbiguousBlocks`/`UnrecognizedFormat`。`ImportNormalizer.kt` 所有）+ `Contract`
  （`IntentValidationFailure` 14種。#204 12種 + `ScopeMismatch`（run-side
  `ScopeBindingGate` 発火。pipelineは返さない）+ `UnknownCategoryRef`（spec 337））。
- `RecognizedImportInfo`（L181-185）: 認識framing/version/entry数。失敗値にadditive付与済み
  （spec 332 D-6）。

### 現行のhosting / 診断導線

`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`:

- idle branchの `exchangeFlowItems` host（L816-829付近）、`Selecting` branchのhost（L433-444）。
  いずれも `onOpenDiagnostics` は**渡していない**（host関数の引数に存在しない）。
- `onOpenDiagnostics: (() -> Unit)?`（L102、既存の画面引数）はdurable status行（L343）と
  run結果のsafe terminal行（L703/791/878）で既に使用中。**診断面への既存routeが存在する**。
- strategy picker（`strategyPickerEnabled`/`strategyFrozenReason`）とidle start row freeze
  （L348-372、spec 328 freeze affordance）。本Issueでは触れない。

### 現行のstrings / test

- `lawnchair/res/values-ja/strings.xml` L379-485（en `values/strings.xml` 同name集合）:
  `exchange_import_result_title` / `exchange_import_retry_hint` / `exchange_import_retry` /
  `exchange_failure_*`（20種typed文言。L443-462）/ `exchange_import_success_*` /
  `exchange_import_cta_*` / `exchange_import_discard*` / `exchange_status_clipboard_*` /
  `exchange_status_file_read_failed` / busy系status。
- unit: `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt`（1747行。
  attempt anchor・success・discard・source失敗・status mappingの契約。L793
  `fileReadFailureResolvesToTheDedicatedImportGuidance`（status→string mappingのregression）、
  L817 `fileReadFailureGuidanceExistsInBothLocales`）、
  `tests/unit/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipelineTest.kt`
  （失敗分類のfixture源）。
- instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/exchange/`
  `ExchangeImportSurfaceInstrumentationTest.kt`（L225
  `failureAndRetryGuidanceStaysWithinTheRecoveryBoundary` = **spec 348 AC-1 content oracle**。
  4つのguidance stringに対しrecovery表現の必須 + `診断`/`diagnostic` 等のforbidden marker。
  L290 `parseFirstOutcomeShowsRecognitionAndKeepsRawCollapsedByDefault`）、
  `ExchangeImportSuccessInstrumentationTest.kt`。CI独立job
  `organizer-instrumentation-issue332-tests` がmerge gate（`final-status`）に組み込み済み。
- 20種typed文言を固定する現行oracleの実体: `exchangeContractFailureText` の**網羅 `when`**
  （compile-time）+ 上記test群の個別string解決。全20種を1つのtableで走査するtestは存在しない
  （本Issueの手段別oracleが新規に作る）。

## Design

### 失敗面の表示model（手段別失敗投影）

純粋なprojection関数を新設する（UI層の分岐散步を防ぐ。spec 332 AC-5「UI側parseなし」と同型の
seam規約。配置は `organizer/ui/exchange/` 内。`personalization/exchange/` には置かない —
Android resource解決はUI層の関心事であるため）:

```kotlin
enum class ImportFailureRemedy { RETRY_IMPORT, REPASTE, RECREATE_REQUEST }

data class ExchangeImportFailureDisplay(
    val remedy: ImportFailureRemedy,
    val primaryTextRes: Int,        // 手段別primary copy（mapping表）
    val primaryActionLabelRes: Int, // 「もう一度取り込む」/「貼り直す」/「依頼を作り直す」
    val detailTextRes: Int?,        // typed原因の説明（詳細展開。既存typed文言の説明部分）
    val detailTypeName: String?,    // typed分類名（補助情報。TO-BE §10により詳細展開限定で許容）
)

fun exchangeImportFailureDisplay(failure: ExchangeImportFailure): ExchangeImportFailureDisplay
```

- mapping表（spec「primary mapping表」scenario）の20種 + 未知typed fallback
  （`RETRY_IMPORT` + 既定copy。fail-closed）を1つのtableで固定する。`Contract` の
  `IntentValidationFailure` 14種は網羅 `when` で、envelope/normalizationは既存の分岐で処理し、
  分類追加漏れはcompiler + fallback両方で捕まえる。
- 認識情報（`RecognizedImportInfo`）とraw detailの表示は現行の
  `ExchangeImportDisplayInfo` + raw折りたたみを**詳細展開内へ移設**する（内容・契約は不変、
  表示位置のみ変更）。

### 失敗面の操作seam

- `RETRY_IMPORT` / `REPASTE` → `holder.openImport()`（現行の再取り込みと同一path。
  raw text破棄・retention boundary不変）。
- `RECREATE_REQUEST` → host面が既に持つ「依頼文を作成」と同一の起動path
  （`holder.openFlow()`）。run-in entryではhosting lambdaがscoped contextを保持しているため、
  復帰後の生成は現行のscope凍結生成契約どおりになる。置換確認（spec 205 AC-13）は
  生成時の既存gateで効く（失敗面の操作は何も書かない）。
- 面レベル手段: 「中断する」→ `holder.close()`（現行のclose path。zero-write・確認不要・
  session生存）。「診断を開く」→ `onOpenDiagnostics` callback（**`exchangeFlowItems` への
  新規引数追加**。host側2 call siteから既存の画面引数を渡す。default null = 非表示ではなく、
  null時は遷移先不在としてrowを非表示にする — instrumentation環境等での安全な欠落）。

### 変更しないもの（明示）

- `ExchangeFlowStateHolder` のattempt anchor・freeze・success・discard機構（spec 328）。
- `ExchangeImportSummary` / 成功面composable（spec 328）。
- T-17入力面（`ExchangeImportField`。spec 332。`maxLines=8`/`heightIn(max=200.dp)`、
  1 MiB gate、clipboard/file transport、parse-first表示、source失敗in-place表示）。
- `ExchangeImportPipeline` / `ImportNormalizer` / `IntentImportParser` / validator
  （失敗分類・契約は不変）。
- `scopeRejection` 行・`ScopeMismatchFailed` stateの表示（#369/#375の所有）。
  `exchangeContractFailureText` 関数は残す（この2 call siteが使用し続ける）。

## Change set

| 対象 | 変更内容 |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeImportFailureDisplay.kt`（新規） | 手段別projection純粋関数 + enum + data class（mapping表の実装本体） |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeImportOutcome` composableの再構成（primary copy + primary操作 + 面レベル手段（中断する/診断を開く）+ 詳細展開（認識情報・typed原因・raw detail））。`exchangeFlowItems` へ `onOpenDiagnostics` 引数追加。既存 `exchangeFailureText` は詳細展開用のtyped原因解決へ役割変更（T-18失敗面のprimary用途から外す） |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | 2つの `exchangeFlowItems` call siteへ `onOpenDiagnostics` を渡す（既存画面引数の再利用） |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 手段別primary copy（3 category × copy/label）+「中断する」「診断を開く」+ 詳細展開heading等の新規string。typed文言の説明部分の再配置。未使用化stringの削除（reference grep 0件） |
| `tests/unit/app/lawnchair/organizer/ui/exchange/`（新規/拡張） | projection純粋関数のtable-driven test（20種 + fallback、ja/en解決）。holder testの失敗面state拡張（remedy操作 → seam呼出） |
| `tests/organizer-instrumentation/.../ExchangeImportSurfaceInstrumentationTest.kt` | 失敗面構造のinstrumentation（primary面にtyped文言が現れない否定的観測・詳細展開default閉・操作到達・「中断する」確認dialog不在・「診断を開く」遷移）。spec 348 content oracleの対象string更新（IM-AC-08） |
| `tests/organizer-instrumentation/.../ExchangeImportSuccessInstrumentationTest.kt` | 無変更でgreen（回帰） |
| `specs/205-external-agent-exchange/spec.md` | AC-5表示面の手段別再投影への改訂 + change history（#373所有。disposition §5 順7） |
| `specs/332-exchange-import-input-ui/spec.md` | T-17配置表記 + 結果表示の正本境界（#373/spec 205/328側）の明記 + change history |
| `tests/unit/.../ExchangeFlowStateHolderTest.kt` 等の既存oracle | typed文言をprimary表示として固定していた箇所の手段別oracleへの更新。**PR本文へobsolete理由を記録**（IM-AC-08） |

## Data model / identity / control flow

- 永続化・schema・identity変更なし（spec「Data and state」）。失敗settle →
  `ImportOutcomeScreen(outcome, rawText)` → projection純粋関数 → 表示、という既存flowの
  表示層のみの変更である。attempt anchor・settle規則は不変。
- 依頼を作り直す操作は既存 `openFlow()` seamを使い、`ExchangeFlowController` に新規seamを
  追加しない。診断を開くはhost既存のcallbackを使い、新規遷移機構を作らない。

## Migration / rollback / recovery

- persistent state変更なし。PR revertで旧typed文言primary表示へ戻る（表示のみ）。
- rollback時の残留物なし。ダウングレード時のcompat問題なし。
- 失敗はzero-writeであり、適用失敗・復旧の対象は生じない（ホームレイアウト安全規約の
  適用対象外 — `favorites` への接触なし）。

## Failure handling

- 未知typed失敗 → 既定remedy（`RETRY_IMPORT`）+ typed原因詳細展開（fail-closed。crash /
  silent失敗しない）。Kotlin網羅 `when` が分類追加時の更新漏れをcompilerで要求する。
- 「診断を開く」の遷移先不在（callback null）→ row非表示（操作の不存在はtyped失敗ではない）。
- 「依頼を作り直す」操作後の生成失敗（入力未ready等）→ 既存のtyped status語彙
  （`GENERATION_INPUT_NOT_READY` 等）で表示され、失敗面の表示を壊さない（現行契約の継承）。

## Testing strategy

- **unit（JVM）**: projection純粋関数のtable-driven testが主oracle。20種全typedの
  remedy category + primary copy resource + 詳細展開内容、ja/en双方のresource解決、
  未知typed fallback。holder testでremedy操作のseam呼出（`openImport`/`openFlow`/`close`）と
  zero-write（session不変）を検証。`ExchangeImportPipelineTest` のfixtureを再利用し、
  pipeline自体は無編集でgreen。
- **instrumentation**: 失敗面構造（primary面の否定的観測: `exchange_failure_*` のtyped文言が
  primary nodeに存在しない。詳細展開default閉。3種操作 + 中断/診断の到達性）。
  `ExchangeImportSuccessInstrumentationTest` と `organizer-instrumentation-issue332-tests`
  lane（T-17回帰）のgreen。
- **device evidence**: 200% font × ja/default のscreenshot evidence（IM-AC-09）。
  TalkBack実ATでの読み上げ文言・順序のwalkthrough evidenceはspec 332 AC-8と同様の
  合成入力制約があるため、Compose semantics assertion + 手動確認を第一証拠とし、
  実AT walkthroughの要否はPR reviewで判断する（未取得範囲を明記）。
- **回帰gate**: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
  --tests 'app.lawnchair.organizer.*'`、exchange系instrumentation lane、CI `final-status`。

## Accessibility evidence

- Compose semantics assertion: primary copyのlive region、primary操作/中断/診断/詳細展開の
  name/role/state、expand/collapse state、traversal順（primary面のnode数がtyped文言数に
  比例しないことの構造assertionを含む）。
- 200% font scaleでのreflow（primary操作が画面内・bounded詳細展開の内部scroll）。
- ja（正本）とdefault（en）のlight/dark screenshot evidenceをPRへ添付する。

## Incremental implementation order

1. projection純粋関数 + unit test（mapping表固定。UI変更前でも成立する縦切り）。
2. 失敗面composableの再構成（primary/詳細展開/面レベル手段）+ strings + instrumentation。
3. `onOpenDiagnostics` のhost接続。
4. spec 205/332改訂 + 旧oracle更新 + obsolete理由のPR記録。
5. 回帰gate実行（unit / instrumentation lane / spotlessCheck）+ evidence。

（2と3は同一PR内で並行可。1を先に固定することでmapping表のreviewが独立する。）

## Dependencies / blockers

- **#372（OPEN・実装未着手）**: 実装着手の前提（disposition §8）。#372未merge環境では
  「依頼を作り直す」の到達先が現行の生成flow先頭になるが、specの契約（「依頼作成導線への
  復帰」）は成立する。#372のmergeによりT-15へ接続先が変わる場合、spec 372側の
  Contract notes 2（取り込み導線）と合わせて到達表記を更新する。
- **#365（OPEN）**: 正本（CONTEXT.md用語）改訂のmerge後に実装着手する（AGENTS.md規約）。
- **#369 / #375**: 排他の確認済み（#369 Non-goalsはexchange失敗再投影を#373へ委譲、
  本planは `scopeRejection` / `ScopeMismatchFailed` 表示に触れない）。
- **#337残件（AC-9/AC-10）**: 本planに含めない。#337の実装PRが本specのT-18成功面構造に
  整合して着手する（owner記録）。

## Risks

- **spec 348 content oracleとの衝突**: 新規copyが「診断」語彙を含むことで、forbidden marker
  `診断`/`diagnostic` を含むoracle（`failureAndRetryGuidanceStaysWithinTheRecoveryBoundary`）が
  失敗する可能性がある。oracleの意図は「AI repair loopの禁止」であり、「診断を開く」は
  ローカルの診断面への遷移（外部送信なし）で別概念である。oracle更新時はこの区別を
  PRのobsolete記録に明文化する（IM-AC-08）。更新は4つの対象stringの変化に限定し、
  no-AI-repair-loop境界のassertion自体は維持する。
- **既存testの表示前提**: `ExchangeFlowStateHolderTest` にstatus→string mappingや失敗面
  構造を仮定するtestがあり、表示model変更で更新が必要になる。pipeline・anchor等の
  契約testは無編集でgreenすることをgateにする（無編集でredになる = 意図しない契約変更の
  信号）。
- **mapping表のproduct判断**: typed失敗→手段の対応（特に `Contract.Oversize` を貼り直すに
  分類する判断等）は本plan/specのdraft決定であり、owner reviewで変更され得る。実装は
  table-drivenであるため、review指摘はtable更新のみで吸収できる。
- **`exchangeContractFailureText` の存続**: 2 call siteが残る限り関数は消せない。#375が
  SCOPE_MISMATCH表示を改訂した後、#377で統合/置換を評価する（本Issueでは触れない）。

## Explicitly unverified areas

- 実AT（TalkBack実機読み上げ・Switch Access scan）での失敗面walkthroughは未取得
  （spec 332 AC-8と同一の環境制約。上記のとおりPR reviewで要否を判断）。
- #372/#369/#365は本plan作成時点で実装未着手であり、それらのmergeによるhosting面・
  strings・到達経路の変化は本planの前提を変え得る（Re-entry ruleで再確認する）。
- 手段別copyの最終文言は実装PRで確定する（spec Open questions 1）。
