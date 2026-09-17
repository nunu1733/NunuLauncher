---
issue: "#323"
status: draft
requirements: [FR-017]
risk: []
updated: 2026-09-17
---

# PR #322 独立監査 P3指摘のフォローアップ (test強化と契約明確化の追跡)

> Status: **draft** — 本specはspec/plan準備taskが作成したものであり、Ownerのacceptanceを受けていない。実装・acceptanceの判断はOwnerが行う。既存のaccepted spec ([spec 204](../204-ai-personalization-context-intent-contract/spec.md)) は本specでは一切変更しない。

## Problem

PR #322 (#204 AI personalization contract実装) の独立監査 ([docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)) はmerge阻害となるP1/P2なしでPassしたが、非blockingのP3指摘を記録した。それらはIssue #323でcheckbox追跡されている。指摘の性質は均質ではない:

1. **回帰防止のtest不在** (P3-6。P3-5は#330の実装で2026-09-17時点ですでに解消済み) — 検証済みのtyped分類に対する直接testが欠けているのみで、実装・契約は正しい。追加すべきtestと場所が確定している。
2. **設計判断が必要な指摘** (P3-3, P3-9) — 対応にはaccepted spec 204の契約変更または現状受け入れの判断が伴い、いずれもOwner decisionである。
3. **PR #322自身のreview対応で既に解消済みの指摘** (P3-2, P3-4, P3-7, P3-8) — issue本文のcheckbox更新はOwner判断だが、現状の証拠が整理されていないと将来の作業者が解消済み項目に着手するriskがある。

Issue #323に着手する作業者が「何が解消済みで、何が実装可能で、何が判断待ちか」を証拠付きで握らないまま着手することを防ぎ、判断不要なtest強化だけを実装可能な状態にするのが本specの目的である。

## 現在の状態 (2026-09-17再検証、baseline `origin/main` = `703afe3f4c1f5387f768832ea422c7b681c3775a`)

下記はすべて実code確認である (初回 2026-09-15 baseline `0cf82bc1e6`、第2回 2026-09-16 baseline `aab0d293d1`、本回 2026-09-17 baselineで再検証・更新)。実装開始時には再検証する (「Re-entry rule」節)。

前提の変化 (前回snapshotからの差分、`aab0d293d1..703afe3f4c`):

- **#330 (partial intent authoring contract、PR #335) merge** — spec 204のschemaが V2→V3 へ拡張された (`personalization-context-v3` / `personalized-intent-v3`。契約変更の正本は [spec 330](../330-partial-intent-authoring/spec.md)、status implemented)。coverage partition要件から「全ref列挙 (全数cover)」が廃止され、`itemIntents` と `unresolvedRefs` は「互いに素・各ref高々1回」のみを要求する (`IntentValidator.kt:59-70`、旧全数一致検査は撤去)。未言及refはvalidator後の純粋completer (`IntentCompletion`) がcanonical unresolvedへ補完する。**本spec最大の変化**: #330は同時に「`unresolvedRefs` 内部重複 → `INCOMPLETE_COVERAGE`」の直接testを `IntentValidatorTest.coverageSplitViolationsAreStillRejected` に追加しており、**P3-5は#330によって解消済み**となった (下表参照)。
- **#329 (Import Normalizer、PR #339) merge** — import pipeline手前に外形canonicalize (`ImportNormalizer`) が追加された。validatorのtyped分類 (`INCOMPLETE_COVERAGE` / `INVALID_ENUM`) は変更されない。
- **#336 (user-defined categories、PR #341) merge** — planning packageへ `CategoryIdentity` 導入と `FullRunExecution` 等のrefactorが入ったが、**preserve / pageAffinity / regionAffinity のpreference消費構造は無変更** (P3-9観察はline番号シフトのみ)。`ContextExportBuilder` の分類fieldは `resolvedIdentities` (identity型) へ置換されたが、untyped throw siteの数・種類は不変 (P3-3構造不変)。
- **#332 (exchange import input UI、PR #344) merge** — `ExchangeFlowController` の **import側** に `recognizedInfo` の伝播が追加された。**generation側 (`generate` / builder throw経路) は無変更**のためP3-3のcrash経路の記述は不変。

| 指摘 | 状態 | 証拠 (現main `703afe3f4c`) |
|---|---|---|
| P3-2 (intent `pageAffinity` がexport grid page範囲と照合されない) | **解消済み** (第3回再auditで実装。2026-09-17再確認) | `IntentValidator.kt:74-80` が `0 <= p < grid.pageCount` 外を `INVALID_ENUM` でreject (code commentに "review P3-2 follow-up" と記載)。checkbox更新はOwner判断 (Issueコメント `5679570064`) |
| P3-4 (`PlannerMobilityBindingTest` でMOVABLE/CONDITIONAL分岐が未到達) | **解消済み** (第3回再audit。2026-09-17再確認。`aab0d293d1..703afe3f4c` で同test fileは無変更) | `PlannerMobilityBindingTest.kt:106-119` — reservation (row 0) と重複しない行 (y=2) へのfixture移動で両分岐が実到達。`reservedRegionCauseBindsToThePlannerReasonExplicitly` も存在。#331が `Mobility.CANDIDATE` 到達guard (AssertionError) を同testに追加済みで分岐到達構造は不変 |
| P3-7 (`preserveRank` が組合せでのみ効く) | **解消済み** (第5回再auditでmovement-minimization意味論に置換。2026-09-17再確認。line番号のみ `#336` refactorでシフト) | `FullRunExecution.kt:726` (`movementMinimizing` 定義)、`:909-931` (preserve first + captured visual orderへの切替)。旧preserveRank実装は撤去済み |
| P3-8 (plan.md記載とtest実体の不一致) | **解消済み** (第6回再audit、head `520748e8d4`) | `multipleDistinctGroupsCohereWithinEachGroup`、`standaloneGroupSemanticConsumesThroughFolderPlacementSemantics` 追加済み。issue本文にcheckboxは存在しない |
| P3-5 (`unresolvedRefs` 内部重複の `INCOMPLETE_COVERAGE` 分類への直接test不在) | **解消済み (2026-09-17判定。#330 (PR #335) の追加testによる)** | 分類実装は `IntentValidator.kt:63-66` (v3でも不変。全数cover検査撤去後は内部重複が `INCOMPLETE_COVERAGE` の主要な単独分類経路)。直接testは `IntentValidatorTest.coverageSplitViolationsAreStillRejected` (`:159-168`) — `itemIntents = emptyList()`、`unresolvedRefs = listOf(refs[0], refs[0])` が `IncompleteCoverage` とexact一致でassertされる (`DuplicateRef` / `UnknownRef` でないことも型一致から従う)。**AC-1の実装は不要** (下記AC-1参照)。issue本文checkboxの更新はOwner判断 |
| P3-6 (`pageAffinity` 範囲検証への専用unit test不在) | **OPEN (test不在のみ。実装は正しい)** | P3-2の実装 (`IntentValidator.kt:74-80`) に対し、organizer test全体で使用される `pageAffinity` 値は範囲内のみ (`IntentValidatorTest` は `:268`/`:317` で 0、`IntentPreferenceConsumptionTest` は 1。2026-09-17再grep)。`InvalidEnum` をassertする既存testはすべてdecode層のunknown enum名 (`IntentCodecTest:123,130`、`ExchangeImportPipelineTest:330-333`) であり、validatorのpage範囲検証を通るtestは存在しない。範囲外 (負、`pageCount` 以上) を作るtestは依然不存在 |
| P3-3 (export側degenerate入力がuntyped例外でfail-loud) | **OPEN (設計判断が必要。throw siteの数・種類は `#331` 追加分を含め不変、line番号のみシフト)** | >512 items: `ContextExportBuilder.kt:118` の `require` (→`IllegalArgumentException`、上限は `ContextExportModels.kt:36` の `MAX_EXPORT_ITEMS = 512`)。placed itemのref衝突: `ContextExportBuilder.kt:262` の `throw IllegalStateException` (`ContextExportBuilderTest.degenerateAllocatorIsDetectedAtExportScope`、現 `:349` が期待例外として実行はしている)。candidateのref衝突 `:95`、non-AppKey candidate `:88` の `error(...)` (#331追加)。空label: `ContextExportModels.kt:214` の `require(value.isNotEmpty())`。builderは純粋で `AndroidExportSessionStore.save` 前にthrowするためzero-write構造は維持。production caller `ExchangeFlowController.generate` / `generateForSelection` (現 `ExchangeFlowController.kt:79-118`) はbuilderのthrowをcatchせず、その他の失敗 (`InputNotReady` / `SessionStoreFailure` / `EncodeFailure`) がtyped resultで扱われる中、degenerate入力のみがtyped `ExchangeGenerationResult` surfaceを貫くcrash経路として残る (#332の変更はimport側のみでgeneration側は不変) |
| P3-9 (preserve+pageAffinity同時宣言時のhint意味が契約で未定義) | **OPEN (設計判断が必要。適用範囲はsingleton executorのみ。`#336` のplanning refactor (CategoryIdentity導入) でpreference消費構造は無変更、line番号のみシフト)** | `preferenceCellHint` (`FullRunExecution.kt:800-805`、preserve → captured cell) と `biasedPreferredPage` (`FullRunExecution.kt:690-698`、pageAffinity → preferred page、範囲guard `:697`) が `allocateWithCellHint` (`PlacementAllocator.kt:62`、不変) に渡り (`FullRunExecution.kt:950`)、captured cell座標が **affinity page上の空き判定** に使われる。allocatorは有効page内の空きcellにのみ配置するためauthority不変・安全。widget経路のpageAffinity消費は第8回再audit (`af890c1e5b`) で撤去済みのため、観察はsingleton (non-widget) 経路に限定される。なお#331の「CANDIDATE subjectへの `preserve` は `MOBILITY_CONTRADICTION`」(`IntentValidator.kt:103-105`) は別subject classの規則であり、本指摘 (PLACED itemの組合せ) の意味論には影響しない |

また、issue本文に記録された **instrumentation flake観察** (issue52系testの `ComposeTimeoutException` timeout、2回連続・別test名) は本PR diffと無関係な安定性の追跡候補であり、本specの対象外とする (「Non-goals」・「Split candidates」節)。

## Outcome

1. P3-6 に対する回帰防止のunit testが既存の純粋test fileに追加され、検証済みのtyped分類 (`INVALID_ENUM` のpage範囲検証) が後続変更で黙って壊れない。P3-5相当の分類は#330が追加した既存test (`coverageSplitViolationsAreStillRejected`) によりすでに固定されており、本specはその充足を記録する (重複実装しない)。
2. P3-3 / P3-9 について、現状の実装・証拠・対応optionと判断基準がdecision packetとして記録され、Owner判断なしに契約変更や実装変更へ進むpathが存在しない。
3. P3-2 / P3-4 / P3-5 / P3-7 / P3-8 の解消状態がcode証拠付きで記録され (P3-5は#330による解消)、解消済み項目への重複着手を防ぐ。

生産codeの振る舞いは一切変更しない。既存run (intentなし) とintentありrunのplan挙動は不変である。

## Scope

- `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt` へのunit test追加 (AC-2、P3-6)。AC-1相当のtestは#330がすでにmainへ追加済みのため本specの追加作業対象外である。
- 本spec内でのdecision packet記録 (D-323-1 / D-323-2、AC-4)。
- P3解消状態の証拠記録 (本spec「現在の状態」節。AC-4の一部。P3-5の解消判定とAC-1充足記録を含む)。

## Non-goals

- **production source変更** — `ContextExportBuilder` のtyped failure化、`pageAffinity`/preserve hint消費semanticsの変更、validator・planner・allocatorへのいかなる変更も含まない。
- **spec 204の変更** — P3-3/P3-9の対応でspec 204 (accepted) の契約節を変える判断はOwnerが別途行う。本specはその判断をしない。
- **Issue #323本文checkboxの更新** — Owner判断 (Issueコメント `5679570064` のとおり)。
- **instrumentation flakeの調査・追跡Issue作成** — 観察の記録とsplit候補の提示のみ。
- **新Issueの自動作成**。
- #205/#329/#330/#331/#332/#336 (いずれもmerge済み) の追加変更、および #206/#337/#348 (open) のいかなる作業。

## Acceptance criteria

- [x] AC-1 (2026-09-17時点で充足、追加作業不要): `unresolvedRefs` が内部重複を含む場合 (同一exported refが2回出現し、`itemIntents` とは重複しない) に、validatorが `INCOMPLETE_COVERAGE` でrejectすることを直接assertするunit testが `IntentValidatorTest` に存在する (`DUPLICATE_REF` / `UNKNOWN_REF` に分類されないこともassertする)。**#330 (PR #335) が `coverageSplitViolationsAreStillRejected` (`:159-168`) として実装済み** — 本specの実装対象ではなく、充足記録のみを目的とする。実装時に同testが存在し分類を固定し続けていることを再確認する。
- [ ] AC-2: movable refへの `pageAffinity` がexport gridのpage範囲外 (負、および `grid.pageCount` 以上) の場合に、validatorが `INVALID_ENUM` でrejectすることを直接assertするunit testが `IntentValidatorTest` に存在する (両境界を個別に実行する)。
- [ ] AC-3: AC-2の追加は既存純粋test fileへのtest追加のみであり、production source・build設定・dependencyの変更を伴わない。`./gradlew spotlessCheck` とorganizer unit test suite (`app.lawnchair.organizer.*`) が通過する。
- [ ] AC-4: P3-3/P3-9のdecision packet (option、判断基準、影響範囲) とP3-2/P3-4/P3-5/P3-7/P3-8の解消証拠が本specに記録され、`spec 204`・生産codeへの無承認変更を禁止する注記が維持される。本specの実装 (AC-2/AC-3) の完了はP3-3/P3-9の解消を意味しない。

## Decision packets (判断待ち — 本specでは決定しない)

### D-323-1: export側degenerate入力のfailure表現 (P3-3)

- **現状 (2026-09-17更新)**: 純粋builder内のfail-loud。>512 items は `ContextExportBuilder.kt:118` の `require` (→`IllegalArgumentException`)、ref衝突 (placed `:262` / candidate `:95`) とnon-AppKey candidate (`:88`、#331追加) はuntyped `IllegalStateException` 系、空labelは `ContextExportModels.kt:214` の `require` (→`IllegalArgumentException`)。いずれもimport側typed failure経路 (`IntentValidationFailure`) の外であり、export側にはtyped failure型が存在しない。builderは純粋で永続化前であるためzero-writeは構造的に維持される。production caller (`ExchangeFlowController.generate` / `generateForSelection`、現 `:79-118`) はbuilderへの入力を内部canonical seam (snapshot + composed targets) からのみ構成し、信頼できない外部入力は `importReply` のtyped pipeline (#329 normalizer含む) でのみ流入する。つまりdegenerate入力を生み得るのは依然としてLauncher内部bugのみである。ただし例外はtyped `ExchangeGenerationResult` surfaceを貫いてuser-facing crashになる (上表P3-3行参照。#332の変更はimport側のみでgeneration側は不変)。
- **Option (a)**: 現状を受け入れ、fail-fastの意図 (app内部canonical stateの不変条件違反であり、外部入力ではない) をKDoc/監査記録で明文化する。外部AIが触れる表現はexport文書とimport validatorのみであり、degenerate入力はLauncher内部bugの検出である。crash経路がexchange UI表面に到達すること (内部bug時にtyped failureではなくcrashする) を明記して受け入れるかを含めて判断する。
- **Option (b)**: export側typed failure経路を導入する (spec 204のContract 1/生成規則の契約変更を伴い、`AndroidExportSessionStore.save` 前のtyped result化。#205/#331 merge後は呼び元 `ExchangeFlowController.generate` が既に存在するため、typed result化する場合は同controllerの`ExchangeGenerationResult`への統合も併せて定義する)。
- **判断基準**: degenerate入力を生み得るのがLauncher内部bugのみか (2026-09-16時点では Yes — 外部入力はbuilderに流入しない)、内部bug時にcrashとtyped failureのどちらを採るか、契約変更のコスト、fail-loudの検出可能性。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。

### D-323-2: preserve + pageAffinity 同時宣言時のcell hint意味 (P3-9)

- **現状 (2026-09-17更新)**: singleton executorで、同一non-widget itemへの `preserve` と `pageAffinity` (captured pageと異なるpage) の同時宣言時、captured cell座標がaffinity page上の空き判定に使われる (空きならそこへ配置、空きでなければcanonical first fitへ劣化)。spec 204「Planner接続」節は両者を個別のsoft hintとして定義するが、**組合せ宣言時のhint意味は未定義**。allocatorは有効page内の空きcellにのみ配置するため安全性・authority不変は構造的に保証される。#336 (PR #341) のplanning refactor (`CategoryIdentity` 導入、`FullRunExecution` のcategory key変更) はpreference消費構造に触れておらず、本観察の構造は不変 (line番号のみシフト)。
- **Option (a)**: 現行挙動を契約として明文化する (spec 204の該当節へ注記を追加。accepted specの変更のためOwner approvalが必要)。
- **Option (b)**: affinity pageがcaptured pageと異なる場合はcell hintを無効化するよう消費semanticsを変更する (spec 204変更 + 実装変更 + test)。
- **Option (c)**: 契約変更を行わず、現行挙動をpinするcontract testのみ追加する (実装は変わらないが、挙動が意図的であることがtestで固定される)。
- **判断基準**: 実useでの発生可能性 (AIが同一itemに両方を宣言する頻度。#205 exchangeはmerge済みのため、実際の外部generator挙動は観測可能になっている)、意味ぶれのuser可視性、契約変更コスト。#329 (Import Normalizer) / #330 (partial authoring) はいずれもmerge済みであり、authoring慣行の変化 (omission解禁) 後の実発生頻度は実import観測 (#345 evidence等) からの再評価を考慮する。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。

## Split candidates (本taskでは新Issueを作成しない)

- **instrumentation flake追跡**: issue52系testの `ComposeTimeoutException` timeout flake (初回・第2回再auditのCI 1回目attemptで発生、rerunでPASS。別test名で2回)。unit timing test flake (`ReadinessGateTest`、第6回) も同種。安定性追跡として独立Issue化が妥当な候補 (issue本文も「追跡候補」と記載)。2026-09-17時点の追記: 以降にmergeしたPR #335 (#330) / #339 (#329) / #341 (#336) / #344 (#332) のaudit記録 (`docs/assessment/` 配下) にも同種flakeの記録はなく、再発観測はPR #322 CIの2回のみ。open issue一覧 (2026-09-17取得) にも専用追跡Issueは存在しない。

## Dependencies / coordination (本specのACを取り巻く並行作業)

- **#329 (Import Normalizer、PR #339でmerge済み)** — strict parser/validatorの手前に外形canonicalizeが追加された。validatorの分類契約 (`INCOMPLETE_COVERAGE` / `INVALID_ENUM`) は変更されず、AC-1/AC-2への影響はなかった (2026-09-17実code再確認)。
- **#330 (partial intent authoring contract、PR #335でmerge済み)** — 前回snapshotで予告していた通りcoverage partition契約を変更 (v3: 全数cover廃止、互いに素・各ref高々1回のみ要求) し、**AC-1相当の直接testを `coverageSplitViolationsAreStillRejected` として自身で追加した**。その結果、AC-1はmain上で充足され本specの実装対象から外れた (再anchoringの実施内容は「現在の状態」節・改訂履歴のとおり)。AC-2の期待値 (`INVALID_ENUM`) はv3でも不変である。
- **#337 (user-defined categories × AI personalization、open)** — intent schemaの拡張 (user-defined identityの参照/提案) を含み得るため、`IntentValidator.kt` / `IntentValidatorTest.kt` を変更し得る。AC-2実装着手時にstatusを確認し、未調整の同時変更を避ける。
- **#348 (AI-facing contractとvalidatorの同期、open)** — exchange package / authoring契約の同期が対象であり、validator分類そのものの変更を含まない見込みだが、`ExchangePackageComposer` 等の近傍seamに触れ得る。AC-2はvalidatorの直接unit testのため直接的な競合はないが、着手時に確認する。
- **#205/#331/#332/#336 (merge済み)** — 上記「現在の状態」節の前提の変化を参照。spec 204 v2拡張はplaced item側の本spec対象契約に影響しない。

## Behavior scenarios

### Scenario: unresolvedRefs内部重複はcoverage違反 (実装済み — AC-1)

**Given** 2つのexported itemを持つexportとsessionが存在し、
**When** `itemIntents` が空で `unresolvedRefs` が同一refを2回含むintentのvalidationを実行する、
**Then** `INCOMPLETE_COVERAGE` でrejectされ、
**And** `DUPLICATE_REF` でも `UNKNOWN_REF` でもない。

このscenarioは#330の `coverageSplitViolationsAreStillRejected` として実装・固定済みであり、本specの追加作業対象ではない (AC-1)。

### Scenario: 範囲外pageAffinityはINVALID_ENUM (AC-2 — 本specの実装対象)

**Given** 1 pageのexport (pageCount = 1) と、1つのmovable refを含むintent (v3では部分intentでよく、全refのcoverは不要) が存在し、
**When** movable refの `pageAffinity` に `-1` を指定してvalidationを実行する、
**Then** `INVALID_ENUM` でrejectされる。
**And** 同様に `pageAffinity = 1` (= pageCount、範囲外) でも `INVALID_ENUM` でrejectされる。

### Scenario: 既存挙動は不変

**Given** AC-2のtest追加が適用されたrepository、
**When** 既存の全organizer unit testを実行する、
**Then** production差分ゼロのため全testが無修正で通過し、intentなしrunとintentありrunの既存挙動は不変である。

## Verification

- `git diff --check` → PASS。
- `./gradlew spotlessCheck` → PASS。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL (AC-2の新規testを含む全organizer test通過)。
- 詳細は [plan.md](./plan.md) のVerification節。

## Re-entry rule

本specはsnapshot時点 (`origin/main` = `703afe3f4c1f5387f768832ea422c7b681c3775a`、2026-09-17) の調査に基づく。実装や再開前に、then-current `origin/main` と全Issueコメントを再取得し、本baselineからの差分 (特に#337/#348関連やpersonalization packageへの変更) を確認し、本specの「現在の状態」表とP3-6のtest不在 (P3-5は#330により解消済みの再確認を含む) を再検証して必要なら更新する。再検証結果は「改訂履歴」に記録する。

## References

- [Issue #323](https://github.com/nunu1733/NunuLauncher/issues/323)
- [Issue #204](https://github.com/nunu1733/NunuLauncher/issues/204)、[PR #322](https://github.com/nunu1733/NunuLauncher/pull/322) (merged, head `af890c1e5b`)
- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205)、[PR #325](https://github.com/nunu1733/NunuLauncher/pull/325) (merged。`ExchangeFlowController` 経由のbuilder呼び出し追加)
- [Issue #331](https://github.com/nunu1733/NunuLauncher/issues/331)、[PR #333](https://github.com/nunu1733/NunuLauncher/pull/333) (merged。spec 204 V1→V2拡張の正本は [spec 331](../331-exchange-target-scope-coupling/spec.md))
- [Issue #330](https://github.com/nunu1733/NunuLauncher/issues/330)、[PR #335](https://github.com/nunu1733/NunuLauncher/pull/335) (merged。spec 204 V2→V3拡張の正本は [spec 330](../330-partial-intent-authoring/spec.md)。P3-5の直接test追加を含む)
- [Issue #329](https://github.com/nunu1733/NunuLauncher/issues/329)、[PR #339](https://github.com/nunu1733/NunuLauncher/pull/339) (merged。Import Normalizer。validator分類は不変)
- [Issue #332](https://github.com/nunu1733/NunuLauncher/issues/332)、[PR #344](https://github.com/nunu1733/NunuLauncher/pull/344) (merged。import UI。generation側のP3-3経路は不変)
- [Issue #336](https://github.com/nunu1733/NunuLauncher/issues/336)、[PR #341](https://github.com/nunu1733/NunuLauncher/pull/341) (merged。`CategoryIdentity` 導入。preference消費構造は不変)
- [Issue #348](https://github.com/nunu1733/NunuLauncher/issues/348) (open、AI-facing contractとvalidatorの同期)、「Dependencies / coordination」節参照
- [監査記録: docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)
- [Spec 204](../204-ai-personalization-context-intent-contract/spec.md) (accepted、schemaはspec 331/330所有でV2→V3へ拡張済み。本specでは一切変更しない)
- [AGENTS.md](../../AGENTS.md)、[docs/project/github-workflow.md](../../docs/project/github-workflow.md)

## 改訂履歴

- 2026-09-15: 初版draft (baseline `0cf82bc1e6`、snapshot commit `584abbbf5e`)。
- 2026-09-16: 再entry再検証 (baseline `aab0d293d1`)。#205 (PR #325) / #331 (PR #333) merge差分を反映: 「現在の状態」表の証拠行番号更新 (P3-3/P3-4)、#331追加のuntyped throw site 2件をP3-3証拠へ追記、`ExchangeFlowController.generate` がbuilder throwをcatchしない事実をP3-3/D-323-1へ追記、spec 204 v2拡張の影響なしを明記、Dependencies / coordination節 (#329/#330) 追加、flake再発なしをSplit candidatesへ追記。P3-5/P3-6のtest不在はorganizer test全体の再grepで再確認 (AC-1/AC-2の実装内容に変更なし)。
- 2026-09-17: 再entry再検証 (baseline `703afe3f4c`)。#330 (PR #335) / #329 (PR #339) / #336 (PR #341) / #332 (PR #344) merge差分を反映: **P3-5を「解消済み (#330が `coverageSplitViolationsAreStillRejected` で直接test追加)」へ更新し、AC-1を実装対象外の充足記録へ再scope** (前回snapshotで予告していた#330による再anchoringの実施)。spec 204 schemaはv3へ拡張 (`ContextExportContract.INTENT_SCHEMA_VERSION = "personalized-intent-v3"`)、coverage partitionから全数cover検査が廃止 (AC-2 fixture要件を緩和: 部分intentで到達可能)。P3-6はorganizer test全体の再grepで依然OPENと再確認 (AC-2の実装内容は範囲外値2境界で不変)。P3-3/P3-9はthrow site・消費構造とも不変 (line番号のみシフト) を再確認。Dependencies節をmerge済み/open現状へ更新 (#337/#348を追加)。
