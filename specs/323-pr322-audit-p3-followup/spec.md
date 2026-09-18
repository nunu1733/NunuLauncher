---
issue: "#323"
status: draft
requirements: [FR-017]
risk: []
updated: 2026-09-19
---

# PR #322 独立監査 P3指摘のフォローアップ (test強化と契約明確化の追跡)

> Status: **draft** — 本specはspec/plan準備taskが作成したものであり、Ownerのacceptanceを受けていない。実装・acceptanceの判断はOwnerが行う。既存のaccepted spec ([spec 204](../204-ai-personalization-context-intent-contract/spec.md)) は本specでは一切変更しない。

## Problem

PR #322 (#204 AI personalization contract実装) の独立監査 ([docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)) はmerge阻害となるP1/P2なしでPassしたが、非blockingのP3指摘を記録した。それらはIssue #323でcheckbox追跡されている。指摘の性質は均質ではない:

1. **回帰防止のtest不在だった指摘** (P3-5, P3-6) — 検証済みのtyped分類に対する直接testが欠けているだけの指摘で、**両方とも2026-09-19時点で他Issueの実装によりmain上で解消済み** (P3-5は#330、P3-6は#348。下表参照)。追加実装は存在しない。
2. **設計判断が必要な指摘** (P3-3, P3-9) — 対応にはaccepted spec 204の契約変更または現状受け入れの判断が伴い、いずれもOwner decisionである (D-323-1 / D-323-2)。
3. **PR #322自身のreview対応で既に解消済みの指摘** (P3-2, P3-4, P3-7, P3-8) — issue本文のcheckbox更新はOwner判断だが、現状の証拠が整理されていないと将来の作業者が解消済み項目に着手するriskがある。

Issue #323に着手する作業者が「何が解消済みで、何が判断待ちか」を証拠付きで握らないまま着手することを防ぎ、残余の実装可能項目が存在しないことを明示するのが本specの目的である。残余はOwner判断 (D-323-1 / D-323-2) と解消状態の記録のみである。

## 現在の状態 (2026-09-19再検証、baseline `origin/main` = `3076bdae7e`)

下記はすべて実code確認である (初回 2026-09-15 baseline `0cf82bc1e6`、第2回 2026-09-16 baseline `aab0d293d1`、第3回 2026-09-17 baseline `703afe3f4c`、本回 2026-09-19 baselineで再検証・更新)。実装開始時には再検証する (「Re-entry rule」節)。

前提の変化 (前回snapshotからの差分、`703afe3f4c..3076bdae7e`):

- **#348 (AI-facing contract同期、PR #349) merge** — `IntentWireContract` (wire field定義とtyped `ConstraintClaim` の正本) が新設され、claimごとのparity test (`Issue348AiFacingContractSyncTest`) がproduction pipeline seam (`ExchangeImportPipeline.import`) 上で全 `PRODUCTION_ENFORCED` claimを実行する。**本spec最大の変化**: `pageAffinity.exportBound` claim (`IntBounds(min=0, max=null)`) のparity caseが範囲外2境界 (`-1`、`pageCount`) を `ExchangeImportFailure.Contract(InvalidEnum)` でexact assertし、**P3-6は#348によって解消済み**となった (下表参照)。codecはdecode時に整数範囲検証を行わない (`IntentCodec.kt:188-192` は型検査のみ) ため、両reject fixtureは `IntentValidator` の範囲検証へ到達する。
- **#337 (user-defined categories × AI personalization) の実装がmerge (PR #355)** — intent schemaはV3→V4へ拡張された (`personalization-context-v4` / `personalized-intent-v4`。`ContextExportContract.INTENT_SCHEMA_VERSION` は `ContextExportModels.kt:36`。契約変更の正本は [spec 337](../337-exchange-category-group-proposals/spec.md))。validatorへcategory ref resolution check (`UNKNOWN_CATEGORY_REF`、`IntentValidator.kt:56-68`) がcoverage partitionの前に追加されたが、P3-5/P3-6対象の分類 (`IncompleteCoverage` / `InvalidEnum`) は不変。**export builderへuntyped throw siteが1件増えた** (category ref割当衝突。P3-3/D-323-1の判断資料を更新)。Issue #337自体は残余scope (evidence等) のためopenのまま。#362 disposition (PR #378、open時点) は本契約を「Continue (契約不変)」と判定済み。
- **#328 (exchange import success state、PR #353) merge** — import成功state・`StrategyWriteArbiter` 等の追加。validator分類とexport builderのgeneration側throw経路は無変更。
- **#356 Epic (Organizer TO-BE再設計) 起立と #357〜#360 (research) / #362 (planning、PR #378 open)** — TO-BE移行でpersonalization/exchange seamの再構成が計画段階で進行中。#362 disposition は #337契約を維持すると明記。P3-3/P3-9の対応実装 (Owner判断後) は着手時に #362 のmigration順序を確認すること。

| 指摘 | 状態 | 証拠 (現main `3076bdae7e`) |
|---|---|---|
| P3-2 (intent `pageAffinity` がexport grid page範囲と照合されない) | **解消済み** (第3回再auditで実装。2026-09-19再確認) | `IntentValidator.kt:89-97` が `0 <= p < grid.pageCount` 外を `INVALID_ENUM` でreject (code commentに "review P3-2 follow-up" と記載)。checkbox更新はOwner判断 (Issueコメント `5679570064`) |
| P3-4 (`PlannerMobilityBindingTest` でMOVABLE/CONDITIONAL分岐が未到達) | **解消済み** (第3回再audit。2026-09-19再確認。`703afe3f4c..3076bdae7e` で同test fileは無変更) | `PlannerMobilityBindingTest.kt:106-119` — reservation (row 0) と重複しない行 (y=2) へのfixture移動で両分岐が実到達。`reservedRegionCauseBindsToThePlannerReasonExplicitly` も存在。#331が `Mobility.CANDIDATE` 到達guard (AssertionError) を同testに追加済みで分岐到達構造は不変 |
| P3-7 (`preserveRank` が組合せでのみ効く) | **解消済み** (第5回再auditでmovement-minimization意味論に置換。2026-09-19再確認。line番号のみ `#337` でシフト) | `FullRunExecution.kt:728-730` (`movementMinimizing` 定義)、`:930-939` (preserve first + captured visual orderへの切替)。旧preserveRank実装は撤去済み |
| P3-8 (plan.md記載とtest実体の不一致) | **解消済み** (第6回再audit、head `520748e8d4`) | `multipleDistinctGroupsCohereWithinEachGroup`、`standaloneGroupSemanticConsumesThroughFolderPlacementSemantics` 追加済み。issue本文にcheckboxは存在しない |
| P3-5 (`unresolvedRefs` 内部重複の `INCOMPLETE_COVERAGE` 分類への直接test不在) | **解消済み (2026-09-17判定。#330 (PR #335) の追加testによる。2026-09-19再確認)** | 分類実装は `IntentValidator.kt:80-83` (v4でも不変。#337のcategory ref checkは `:56-68` に先行追加されたが本分類は不変)。直接testは `IntentValidatorTest.coverageSplitViolationsAreStillRejected` (`:194-217`) — `itemIntents = emptyList()`、`unresolvedRefs = listOf(refs[0], refs[0])` (`:207-212`) が `IncompleteCoverage` とexact一致でassertされる (`:213-216`。`DuplicateRef` / `UnknownRef` でないことも型一致から従う)。**AC-1の実装は不要** (下記AC-1参照)。issue本文checkboxの更新はOwner判断 |
| P3-6 (`pageAffinity` 範囲検証への専用unit test不在) | **解消済み (2026-09-19判定。#348 (PR #349) の追加testによる)** | 検証実装は `IntentValidator.kt:89-97` (P3-2対応、v4でも不変)。直接testは `Issue348AiFacingContractSyncTest.everyProductionClaimHasAKeyedParityCaseOnTheProductionPath` (`:598`) が `pageAffinity.exportBound` claim (`IntentWireContract.kt:283`、`Semantic.IntBounds(pageAffinitySpec.min!!, max = null)` `:287`) に対して生成するparity case: `pageCount - 1` をaccept、**範囲外2境界 `pageCount` (`:472`) と `min - 1` = `-1` (`:474`) を `ExchangeImportFailure.Contract(InvalidEnum)` でexact assert**。実行seamはproduction pipeline (`ExchangeImportPipeline.import`、同test `:131-134`) であり、AC-2が要求した分類 (`INVALID_ENUM`) と両境界を実coverage (validator直呼びではなくpipeline経由) で固定する。codecは整数範囲を検証しないため (`IntentCodec.kt:188-192`)、reject到達先はvalidatorの範囲検証である。**AC-2の実装は不要** (下記AC-2参照)。issue本文checkboxの更新はOwner判断 |
| P3-3 (export側degenerate入力がuntyped例外でfail-loud) | **OPEN (設計判断が必要。throw siteは `#337` で1件増加、他は不変)** | >512 items: `ContextExportBuilder.kt:134` の `require` (→`IllegalArgumentException`、上限は `ContextExportModels.kt:39` の `MAX_EXPORT_ITEMS = 512`)。placed itemのref衝突: `ContextExportBuilder.kt:358` の `throw IllegalStateException` (category refsとの突合を含むよう拡張、`ContextExportBuilderTest.degenerateAllocatorIsDetectedAtExportScope`、現 `:349` が期待例外として実行はしている)。candidateのref衝突 `:110`、non-AppKey candidate `:102` の `error(...)`、**category ref割当衝突 `:237` (新規。#337)** — いずれもuntyped `IllegalStateException` 系。空label: `ContextExportModels.kt:294` の `require(value.isNotEmpty())` (`ExportItemLabel`)。builderは純粋で `AndroidExportSessionStore.save` 前にthrowするためzero-write構造は維持。production caller `ExchangeFlowController.generate` / `generateForSelection` (`ExchangeFlowController.kt:79-118`、`703afe3f4c..3076bdae7e` で無変更) はbuilderのthrowをcatchせず、その他の失敗 (`InputNotReady` / `SessionStoreFailure` / `EncodeFailure`) がtyped resultで扱われる中、degenerate入力のみがtyped `ExchangeGenerationResult` surfaceを貫くcrash経路として残る |
| P3-9 (preserve+pageAffinity同時宣言時のhint意味が契約で未定義) | **OPEN (設計判断が必要。適用範囲はsingleton executorのみ。line番号のみシフト、消費構造は不変)** | `preferenceCellHint` (`FullRunExecution.kt:811-813`、preserve → captured cell) と `biasedPreferredPage` (`FullRunExecution.kt:693-704`、pageAffinity → preferred page、範囲guard `:702`) が `allocateWithCellHint` (`PlacementAllocator.kt:62`、不変) に渡り (`FullRunExecution.kt:885-890`、folder経路 `:857-862`、allocator呼び出し `:961`)、captured cell座標が **affinity page上の空き判定** に使われる。allocatorは有効page内の空きcellにのみ配置するためauthority不変・安全。widget経路のpageAffinity消費は第8回再audit (`af890c1e5b`) で撤去済みのため、観察はsingleton (non-widget) 経路に限定される。なお#331の「CANDIDATE subjectへの `preserve` は `MOBILITY_CONTRADICTION`」(`IntentValidator.kt:120-122`) は別subject classの規則であり、本指摘 (PLACED itemの組合せ) の意味論には影響しない |

また、issue本文に記録された **instrumentation flake観察** (issue52系testの `ComposeTimeoutException` timeout、2回連続・別test名) は本PR diffと無関係な安定性の追跡候補であり、本specの対象外とする (「Non-goals」・「Split candidates」節)。

## Outcome

1. P3-5 / P3-6 に相当する分類固定は、#330 (`coverageSplitViolationsAreStillRejected`) と #348 (`pageAffinity.exportBound` parity case) がそれぞれmain上で実現しており、本specはその充足を記録する (重複実装しない)。**本specに残存する実装作業は存在しない。**
2. P3-3 / P3-9 について、現状の実装・証拠・対応optionと判断基準がdecision packetとして記録され、Owner判断なしに契約変更や実装変更へ進むpathが存在しない。
3. P3-2 / P3-4 / P3-5 / P3-6 / P3-7 / P3-8 の解消状態がcode証拠付きで記録され (P3-5は#330、P3-6は#348による解消)、解消済み項目への重複着手を防ぐ。

生産codeの振る舞いは一切変更しない。既存run (intentなし) とintentありrunのplan挙動は不変である。

## Scope

- 本spec内でのdecision packet記録 (D-323-1 / D-323-2、AC-4)。
- P3解消状態の証拠記録 (本spec「現在の状態」節。AC-4の一部。P3-5 (#330) / P3-6 (#348) の解消判定とAC-1/AC-2充足記録を含む)。
- test追加を含む実装作業は存在しない (AC-1/AC-2ともにmain上で充足済み)。

## Non-goals

- **production source変更** — `ContextExportBuilder` のtyped failure化、`pageAffinity`/preserve hint消費semanticsの変更、validator・planner・allocatorへのいかなる変更も含まない。
- **spec 204の変更** — P3-3/P3-9の対応でspec 204 (accepted、schemaはV4) の契約節を変える判断はOwnerが別途行う。本specはその判断をしない。
- **Issue #323本文checkboxの更新** — Owner判断 (Issueコメント `5679570064` のとおり)。
- **instrumentation flakeの調査・追跡Issue作成** — 観察の記録とsplit候補の提示のみ。
- **新Issueの自動作成**。
- #205/#329/#330/#331/#332/#336/#337実装/#348実装 (いずれもmerge済み) の追加変更、および #206/#356 Epic系 (#357〜#362、#365〜#377) / #352 / #170 のいかなる作業。

## Acceptance criteria

- [x] AC-1 (2026-09-17時点で充足、追加作業不要): `unresolvedRefs` が内部重複を含む場合 (同一exported refが2回出現し、`itemIntents` とは重複しない) に、validatorが `INCOMPLETE_COVERAGE` でrejectすることを直接assertするunit testが存在する (`DUPLICATE_REF` / `UNKNOWN_REF` に分類されないこともassertする)。**#330 (PR #335) が `coverageSplitViolationsAreStillRejected` として実装済み** (現 `IntentValidatorTest.kt:194-217`、#337のv4拡張後も不変)。本specの実装対象ではなく、充足記録のみを目的とする。
- [x] AC-2 (2026-09-19時点で充足、追加作業不要): movable refへの `pageAffinity` がexport gridのpage範囲外 (負、および `grid.pageCount` 以上) の場合に、typed分類 `INVALID_ENUM` でrejectすることを直接assertするtestが存在し、両境界を個別に実行する。**#348 (PR #349) が `Issue348AiFacingContractSyncTest` の `pageAffinity.exportBound` parity caseとして実装済み** — `-1` と `pageCount` の両境界を `ExchangeImportFailure.Contract(InvalidEnum)` でexact assertし (同test `:472`/`:474`)、実行seamは `IntentValidator` 直呼びではなくproduction pipeline (`ExchangeImportPipeline.import`) である。AC成立のための seam 差異 (validator直 unit testではなくpipeline経由のparity test) は、分類契約の固定というAC目的を満たす。`IntentValidatorTest` 直呼びのunit testを追加で足すことは禁止しないが、本specの作業対象ではない。
- [x] AC-3 (2026-09-19時点でmoot — AC-2の追加作業が#348により不要となったため): AC-2の実体は#348 (PR #349) のtest-only変更としてmainへ入っており、production source・build設定・dependencyの変更を伴わない形で充足されている。本spec自身が新たにtestを追加する作業は存在しない。
- [ ] AC-4: P3-3/P3-9のdecision packet (option、判断基準、影響範囲) とP3-2/P3-4/P3-5/P3-6/P3-7/P3-8の解消証拠が本specに記録され、`spec 204`・生産codeへの無承認変更を禁止する注記が維持される。本specの完了はP3-3/P3-9の解消を意味しない。

## Decision packets (判断待ち — 本specでは決定しない)

### D-323-1: export側degenerate入力のfailure表現 (P3-3)

- **現状 (2026-09-19更新)**: 純粋builder内のfail-loud。>512 items は `ContextExportBuilder.kt:134` の `require` (→`IllegalArgumentException`)、ref衝突 (placed `:358` / candidate `:110` / **category ref `:237` — #337 (PR #355) 追加**) とnon-AppKey candidate (`:102`) はuntyped `IllegalStateException` 系、空labelは `ContextExportModels.kt:294` の `require` (→`IllegalArgumentException`)。untyped throw siteは#331追加分 (`:102`/`:110`) に続き#337で `:237` が増え、計4 site + 2 `require` となった。いずれもimport側typed failure経路 (`IntentValidationFailure`) の外であり、export側にはtyped failure型が存在しない。builderは純粋で永続化前であるためzero-writeは構造的に維持される。production caller (`ExchangeFlowController.generate` / `generateForSelection`、`:79-118`。`703afe3f4c..3076bdae7e` で無変更) はbuilderへの入力を内部canonical seam (snapshot + composed targets + userLabels) からのみ構成し、信頼できない外部入力は `importReply` のtyped pipeline (#329 normalizer、#348 wire contract含む) でのみ流入する。つまりdegenerate入力を生み得るのは依然としてLauncher内部bugのみである。ただし例外はtyped `ExchangeGenerationResult` surfaceを貫いてuser-facing crashになる (上表P3-3行参照)。
- **Option (a)**: 現状を受け入れ、fail-fastの意図 (app内部canonical stateの不変条件違反であり、外部入力ではない) をKDoc/監査記録で明文化する。外部AIが触れる表現はexport文書とimport validatorのみであり、degenerate入力はLauncher内部bugの検出である。crash経路がexchange UI表面に到達すること (内部bug時にtyped failureではなくcrashする) を明記して受け入れるかを含めて判断する。
- **Option (b)**: export側typed failure経路を導入する (spec 204のContract 1/生成規則の契約変更を伴い、`AndroidExportSessionStore.save` 前のtyped result化。`ExchangeFlowController.generate` が既にtyped result surfaceを持つため、そちらへの統合も併せて定義する)。
- **判断基準**: degenerate入力を生み得るのがLauncher内部bugのみか (2026-09-19時点でも Yes — 外部入力はbuilderに流入しない)、内部bug時にcrashとtyped failureのどちらを採るか、契約変更のコスト、fail-loudの検出可能性。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。着手時は #362 (TO-BE disposition、PR #378) のexchange seam migration順序を確認する。

### D-323-2: preserve + pageAffinity 同時宣言時のcell hint意味 (P3-9)

- **現状 (2026-09-19更新)**: singleton executorで、同一non-widget itemへの `preserve` と `pageAffinity` (captured pageと異なるpage) の同時宣言時、captured cell座標がaffinity page上の空き判定に使われる (空きならそこへ配置、空きでなければcanonical first fitへ劣化)。spec 204「Planner接続」節は両者を個別のsoft hintとして定義するが、**組合せ宣言時のhint意味は未定義**。allocatorは有効page内の空きcellにのみ配置するため安全性・authority不変は構造的に保証される。#337 (PR #355) の変更 (`FullRunExecution` へのrun-scoped formation key導入等) はpreference消費構造に触れておらず、本観察の構造は不変 (line番号のみシフト: `preferenceCellHint` `:811-813`、`biasedPreferredPage` `:693-704`、`allocateWithCellHint` 呼び出し `:961`、`PlacementAllocator.kt:62` 不変)。
- **Option (a)**: 現行挙動を契約として明文化する (spec 204の該当節へ注記を追加。accepted specの変更のためOwner approvalが必要)。
- **Option (b)**: affinity pageがcaptured pageと異なる場合はcell hintを無効化するよう消費semanticsを変更する (spec 204変更 + 実装変更 + test)。
- **Option (c)**: 契約変更を行わず、現行挙動をpinするcontract testのみ追加する (実装は変わらないが、挙動が意図的であることがtestで固定される)。
- **判断基準**: 実useでの発生可能性 (AIが同一itemに両方を宣言する頻度。#205/#348 merge後の実importは観測可能。#348のwire契約で `pageAffinity` と `preserve` の併記はschema上許可される)、意味ぶれのuser可視性、契約変更コスト。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。着手時は #362 (TO-BE disposition) のplanning seam migration順序を確認する。

## Split candidates (本taskでは新Issueを作成しない)

- **instrumentation flake追跡**: issue52系testの `ComposeTimeoutException` timeout flake (初回・第2回再auditのCI 1回目attemptで発生、rerunでPASS。別test名で2回)。unit timing test flake (`ReadinessGateTest`、第6回) も同種。安定性追跡として独立Issue化が妥当な候補 (issue本文も「追跡候補」と記載)。2026-09-19時点の追記: (1) **新規観測** — PR #355 (#337実装) のCIで旧head (`917d093854`) の `organizer-instrumentation-issue52-tests` laneが1回失敗し、監査記録 ([docs/assessment/pr-355-issue337-category-refs.md](../../docs/assessment/pr-355-issue337-category-refs.md)) がemulator側systemui ANR型のinfra flakeと診断済み。(2) **類縁の既存記録** — 同型のemulator infra起因timeoutは [pr-278](../../docs/assessment/pr-278-backup-page-summary.md) 監査 (EmulatorConsole/ColorBuffer破損時の `ManualOrganizationPreferencesInstrumentationTest` timeout、複数回) と [pr-273](../../docs/assessment/pr-273-recovery-preview-capture-failure.md) 監査 (issue99 lane、`CategoryOverridePreferencesInstrumentationTest`) にも記録がある。(3) **他flakeの専用追跡Issueは既に存在** — #352 (`ExchangeFlowStateHolderTest` unit testのscreen状態レース) と #170 (PR-169監査の `TwoPanelOrientationCaptureInstrumentationTest` flake他) だが、いずれも本件のissue52系 `ComposeTimeoutException` ファミリーとは別test群である。issue52系ファミリー専用の追跡Issueは依然存在しない。
- **D-323-1 / D-323-2 の判断後実装**: Owner判断が下りた場合、それぞれ別Issue/別specとして立てる (本specは判断しない)。#362 (TO-BE disposition) との協調を条件付ける。

## Dependencies / coordination (本specの記録を取り巻く並行作業)

- **#337 (user-defined categories × AI personalization)** — 実装はPR #355でmerge済み (schema V4、`UnknownCategoryRef` 追加、builder throw site `:237` 追加)。Issue自体は残余scope (evidence等) でopen。#362 disposition (PR #378、open時点) は本契約を「Continue (契約不変)」と判定。残余作業が `IntentValidator.kt` / `IntentValidatorTest.kt` / `ContextExportBuilder.kt` を变更し得るため、P3-3/P3-9の対応実装着手時にstatusを確認する。
- **#348 (AI-facing contract同期)** — 実装はPR #349でmerge済み。IssueはAC-11 device evidence (emulator上のChatGPTログインが必要なowner作業) のためのみopen。validator分類の追加変更は予定されていない。
- **#356 Epic / #357〜#360 (research) / #362 (planning、PR #378 open) / #365〜#377 (TO-BE feature群)** — Organizer TO-BE再設計。personalization/exchange seamの再構成が計画段階で進行中 (現時点でproductionへの影響なし)。D-323-1/D-323-2の対応実装は #362 のmigration順序確定後に協調すること。
- **#352 / #170** — 他test群のflake追跡Issue (本spec「Split candidates」参照)。本件issue52系ファミリーとは別。
- **#205/#329/#330/#331/#332/#336 (merge済み)** — 上記「現在の状態」節の前提の変化を参照。spec 204のV2〜V4拡張はP3-5/P3-6対象の分類契約 (`INCOMPLETE_COVERAGE` / `INVALID_ENUM`) に影響しない (2026-09-19実code再確認)。

## Behavior scenarios

### Scenario: unresolvedRefs内部重複はcoverage違反 (実装済み — AC-1、#330)

**Given** 2つのexported itemを持つexportとsessionが存在し、
**When** `itemIntents` が空で `unresolvedRefs` が同一refを2回含むintentのvalidationを実行する、
**Then** `INCOMPLETE_COVERAGE` でrejectされ、
**And** `DUPLICATE_REF` でも `UNKNOWN_REF` でもない。

このscenarioは#330の `coverageSplitViolationsAreStillRejected` (`IntentValidatorTest.kt:194-217`) として実装・固定済みであり、本specの追加作業対象ではない (AC-1)。

### Scenario: 範囲外pageAffinityはINVALID_ENUM (実装済み — AC-2、#348)

**Given** 1 pageのexport (pageCount = 1) と、1つのmovable refを含むintent (部分intentでよく、全refのcoverは不要) が存在し、
**When** movable refの `pageAffinity` に `-1` を指定してimport pipelineでvalidationを実行する、
**Then** typed分類 `INVALID_ENUM` でrejectされる。
**And** 同様に `pageAffinity = 1` (= pageCount、範囲外) でも `INVALID_ENUM` でrejectされる。

このscenarioは#348の `Issue348AiFacingContractSyncTest` が `pageAffinity.exportBound` claimから導出するparity case (`:472`/`:474`、production pipeline seam経由) として実装・固定済みであり、本specの追加作業対象ではない (AC-2)。

### Scenario: 既存挙動は不変

**Given** 本specの記録更新が適用されたrepository、
**When** 既存の全organizer unit testを実行する、
**Then** 本specによる差分はdocumentationのみのため全testが無修正で通過し、intentなしrunとintentありrunの既存挙動は不変である。

## Verification

本specのsnapshot更新自体はdocs-onlyであり、検証は `git diff --check` とspec内参照pathの実在確認で足りる。将来P3-3/P3-9の対応実装を行う場合の検証は、その別specのplanで定義する。詳細は [plan.md](./plan.md) のVerification節。

## Re-entry rule

本specはsnapshot時点 (`origin/main` = `3076bdae7e`、2026-09-19) の調査に基づく。実装や再開前に、then-current `origin/main` と全Issueコメントを再取得し、本baselineからの差分 (特に #337残余 / #356 Epic系 / #362 関連やpersonalization packageへの変更) を確認し、本specの「現在の状態」表を再検証して必要なら更新する。P3-5 (#330のtest) とP3-6 (#348のparity case) が引き続きmain上で分類を固定していることも再確認する。再検証結果は「改訂履歴」に記録する。

## References

- [Issue #323](https://github.com/nunu1733/NunuLauncher/issues/323)
- [Issue #204](https://github.com/nunu1733/NunuLauncher/issues/204)、[PR #322](https://github.com/nunu1733/NunuLauncher/pull/322) (merged, head `af890c1e5b`)
- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205)、[PR #325](https://github.com/nunu1733/NunuLauncher/pull/325) (merged。`ExchangeFlowController` 経由のbuilder呼び出し追加)
- [Issue #331](https://github.com/nunu1733/NunuLauncher/issues/331)、[PR #333](https://github.com/nunu1733/NunuLauncher/pull/333) (merged。spec 204 V1→V2拡張の正本は [spec 331](../331-exchange-target-scope-coupling/spec.md))
- [Issue #330](https://github.com/nunu1733/NunuLauncher/issues/330)、[PR #335](https://github.com/nunu1733/NunuLauncher/pull/335) (merged。spec 204 V2→V3拡張の正本は [spec 330](../330-partial-intent-authoring/spec.md)。P3-5の直接test追加を含む)
- [Issue #329](https://github.com/nunu1733/NunuLauncher/issues/329)、[PR #339](https://github.com/nunu1733/NunuLauncher/pull/339) (merged。Import Normalizer。validator分類は不変)
- [Issue #332](https://github.com/nunu1733/NunuLauncher/issues/332)、[PR #344](https://github.com/nunu1733/NunuLauncher/pull/344) (merged。import UI。generation側のP3-3経路は不変)
- [Issue #336](https://github.com/nunu1733/NunuLauncher/issues/336)、[PR #341](https://github.com/nunu1733/NunuLauncher/pull/341) (merged。`CategoryIdentity` 導入。preference消費構造は不変)
- [Issue #348](https://github.com/nunu1733/NunuLauncher/issues/348)、[PR #349](https://github.com/nunu1733/NunuLauncher/pull/349) (merged。AI-facing contract同期と `IntentWireContract` / parity test新設。P3-6の直接test追加を含む。正本は [spec 348](../348-exchange-ai-facing-contract/spec.md)。IssueはAC-11 evidenceのためopen)
- [Issue #337](https://github.com/nunu1733/NunuLauncher/issues/337)、[PR #355](https://github.com/nunu1733/NunuLauncher/pull/355) (merged。spec 204 V3→V4拡張の正本は [spec 337](../337-exchange-category-group-proposals/spec.md)。validatorのcategory ref checkとbuilder throw site `:237` 追加を含む。Issueは残余scopeのためopen)
- [Issue #328](https://github.com/nunu1733/NunuLauncher/issues/328)、[PR #353](https://github.com/nunu1733/NunuLauncher/pull/353) (merged。import success state。validator分類・generation側throw経路は不変)
- [Issue #356](https://github.com/nunu1733/NunuLauncher/issues/356) (Organizer TO-BE Epic) / [Issue #362](https://github.com/nunu1733/NunuLauncher/issues/362) (disposition、[PR #378](https://github.com/nunu1733/NunuLauncher/pull/378) open) — D-323-1/D-323-2対応実装時の協調対象
- [Issue #352](https://github.com/nunu1733/NunuLauncher/issues/352) / [Issue #170](https://github.com/nunu1733/NunuLauncher/issues/170) (他test群のflake追跡。「Split candidates」参照)
- [監査記録: docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)
- [Spec 204](../204-ai-personalization-context-intent-contract/spec.md) (accepted、schemaはspec 331/330/337所有でV2→V3→V4へ拡張済み。本specでは一切変更しない)
- [AGENTS.md](../../AGENTS.md)、[docs/project/github-workflow.md](../../docs/project/github-workflow.md)

## 改訂履歴

- 2026-09-15: 初版draft (baseline `0cf82bc1e6`、snapshot commit `584abbbf5e`)。
- 2026-09-16: 再entry再検証 (baseline `aab0d293d1`)。#205 (PR #325) / #331 (PR #333) merge差分を反映: 「現在の状態」表の証拠行番号更新 (P3-3/P3-4)、#331追加のuntyped throw site 2件をP3-3証拠へ追記、`ExchangeFlowController.generate` がbuilder throwをcatchしない事実をP3-3/D-323-1へ追記、spec 204 v2拡張の影響なしを明記、Dependencies / coordination節 (#329/#330) 追加、flake再発なしをSplit candidatesへ追記。P3-5/P3-6のtest不在はorganizer test全体の再grepで再確認 (AC-1/AC-2の実装内容に変更なし)。
- 2026-09-17: 再entry再検証 (baseline `703afe3f4c`)。#330 (PR #335) / #329 (PR #339) / #336 (PR #341) / #332 (PR #344) merge差分を反映: **P3-5を「解消済み (#330が `coverageSplitViolationsAreStillRejected` で直接test追加)」へ更新し、AC-1を実装対象外の充足記録へ再scope** (前回snapshotで予告していた#330による再anchoringの実施)。spec 204 schemaはv3へ拡張 (`ContextExportContract.INTENT_SCHEMA_VERSION = "personalized-intent-v3"`)、coverage partitionから全数cover検査が廃止 (AC-2 fixture要件を緩和: 部分intentで到達可能)。P3-6はorganizer test全体の再grepで依然OPENと再確認 (AC-2の実装内容は範囲外値2境界で不変)。P3-3/P3-9はthrow site・消費構造とも不変 (line番号のみシフト) を再確認。Dependencies節をmerge済み/open現状へ更新 (#337/#348を追加)。
- 2026-09-19: 再entry再検証 (baseline `3076bdae7e`)。#348 (PR #349) / #328 (PR #353) / #337実装 (PR #355) merge差分を反映: **P3-6を「解消済み (#348が `Issue348AiFacingContractSyncTest` の `pageAffinity.exportBound` parity caseで範囲外2境界 `pageCount`/`-1` を `Contract(InvalidEnum)` exact assert)」へ更新し、AC-2を実装対象外の充足記録へ再scope、AC-3をmootとして記録**。codecは整数範囲をdecode時に検証しないためreject到達先がvalidator範囲検証であることを実codeで確認 (`IntentCodec.kt:188-192`、`IntentValidator.kt:89-97`)。P3-5は `coverageSplitViolationsAreStillRejected` がv4でも不変であることを再確認。spec 204 schemaはv4へ拡張 (`ContextExportContract.INTENT_SCHEMA_VERSION = "personalized-intent-v4"`、`ContextExportModels.kt:36`)。**P3-3は#337によりuntyped throw siteが1件増加** (category ref割当衝突 `ContextExportBuilder.kt:237`) しD-323-1の判断資料を更新 (`ExchangeFlowController` 自体は無変更)。P3-9は消費構造不変 (line番号のみシフト) を再確認。Dependencies節を更新 (#337実装merge済み・#348実装merge済み/Issue open、#356 Epic/#362 disposition (PR #378) 追加)。Split candidatesのflake節へpr-355監査の新規観測 (issue52 laneのsystemui ANR型infra flake) とpr-273/pr-278監査の類縁記録、#352/#170の存在を追記 (issue52系ファミリー専用追跡Issueは依然不存在)。
