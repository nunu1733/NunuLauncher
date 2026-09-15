---
issue: "#323"
status: draft
requirements: [FR-017]
risk: []
updated: 2026-09-15
---

# PR #322 独立監査 P3指摘のフォローアップ (test強化と契約明確化の追跡)

> Status: **draft** — 本specはspec/plan準備taskが作成したものであり、Ownerのacceptanceを受けていない。実装・acceptanceの判断はOwnerが行う。既存のaccepted spec ([spec 204](../204-ai-personalization-context-intent-contract/spec.md)) は本specでは一切変更しない。

## Problem

PR #322 (#204 AI personalization contract実装) の独立監査 ([docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)) はmerge阻害となるP1/P2なしでPassしたが、非blockingのP3指摘を記録した。それらはIssue #323でcheckbox追跡されている。指摘の性質は均質ではない:

1. **回帰防止のtest不在** (P3-5, P3-6) — 検証済みのtyped分類に対する直接testが欠けているのみで、実装・契約は正しい。追加すべきtestと場所が確定している。
2. **設計判断が必要な指摘** (P3-3, P3-9) — 対応にはaccepted spec 204の契約変更または現状受け入れの判断が伴い、いずれもOwner decisionである。
3. **PR #322自身のreview対応で既に解消済みの指摘** (P3-2, P3-4, P3-7, P3-8) — issue本文のcheckbox更新はOwner判断だが、現状の証拠が整理されていないと将来の作業者が解消済み項目に着手するriskがある。

Issue #323に着手する作業者が「何が解消済みで、何が実装可能で、何が判断待ちか」を証拠付きで握らないまま着手することを防ぎ、判断不要なtest強化だけを実装可能な状態にするのが本specの目的である。

## 現在の状態 (2026-09-15検証、baseline `origin/main` = `0cf82bc1e61c1874b280a7120dff9594be4fef71` = PR #322 merge commit)

下記はすべて本spec作成時の実code確認である。実装開始時には再検証する (「Re-entry rule」節)。

| 指摘 | 状態 | 証拠 (現main) |
|---|---|---|
| P3-2 (intent `pageAffinity` がexport grid page範囲と照合されない) | **解消済み** (第3回再auditで実装) | `IntentValidator.kt:73-81` が `0 <= p < grid.pageCount` 外を `INVALID_ENUM` でreject (code commentに "review P3-2 follow-up" と記載)。checkbox更新はOwner判断 (Issueコメント `5679570064`) |
| P3-4 (`PlannerMobilityBindingTest` でMOVABLE/CONDITIONAL分岐が未到達) | **解消済み** (第3回再audit) | `PlannerMobilityBindingTest.kt:109-117` — reservation (row 0) と重複しない行 (y=2) へのfixture移動で両分岐が実到達。`reservedRegionCauseBindsToThePlannerReasonExplicitly` も存在 |
| P3-7 (`preserveRank` が組合せでのみ効く) | **解消済み** (第5回再auditでmovement-minimization意味論に置換) | `FullRunExecution.kt:723-726` (`movementMinimizing`)、`:913-920` (captured visual orderへの切替)。旧preserveRank実装は撤去済み |
| P3-8 (plan.md記載とtest実体の不一致) | **解消済み** (第6回再audit、head `520748e8d4`) | `multipleDistinctGroupsCohereWithinEachGroup`、`standaloneGroupSemanticConsumesThroughFolderPlacementSemantics` 追加済み。issue本文にcheckboxは存在しない |
| P3-5 (`unresolvedRefs` 内部重複の `INCOMPLETE_COVERAGE` 分類への直接test不在) | **OPEN (test不在のみ。実装は正しい)** | 分類実装は `IntentValidator.kt:61-64`。既存test (`IntentValidatorTest.partialResponsesViolateTheCoveragePartition`) はmissing (片方欠落) とoverlap (両集合の重複) のみをcoverし、`unresolvedRefs` 自体の内部重複 (`listOf(x, x)`) を作るtestはorganizer test全体に存在しない |
| P3-6 (`pageAffinity` 範囲検証への専用unit test不在) | **OPEN (test不在のみ。実装は正しい)** | P3-2の実装 (`IntentValidator.kt:73-81`) に対し、全organizer testで使用される `pageAffinity` 値は範囲内 (0または1) のみ。範囲外 (負、`pageCount` 以上) を作るtestは存在しない |
| P3-3 (export側degenerate入力がuntyped例外でfail-loud) | **OPEN (設計判断が必要)** | >512 items: `ContextExportBuilder.kt:71` の `require` (→`IllegalArgumentException`)。ref衝突: `ContextExportBuilder.kt:194` の `throw IllegalStateException` ( `ContextExportBuilderTest.degenerateAllocatorIsDetectedAtExportScope` が期待例外として実行はしている)。空label: `ContextExportModels.kt:184` の `require(value.isNotEmpty())` (→`IllegalArgumentException`)。builderは純粋で `AndroidExportSessionStore.save` 前にthrowするためzero-write構造は維持 |
| P3-9 (preserve+pageAffinity同時宣言時のhint意味が契約で未定義) | **OPEN (設計判断が必要。適用範囲はsingleton executorのみ)** | `preferenceCellHint` (`FullRunExecution.kt:792-796`、preserve → captured cell) と `biasedPreferredPage` (`FullRunExecution.kt:687-696`、pageAffinity → preferred page) が `allocateWithCellHint` (`PlacementAllocator.kt:62-80`) に渡り、captured cell座標が **affinity page上の空き判定** に使われる。allocatorは有効page内の空きcellにのみ配置するためauthority不変・安全。widget経路のpageAffinity消費は第8回再audit (`af890c1e5b`) で撤去済みのため、観察はsingleton (non-widget) 経路に限定される |

また、issue本文に記録された **instrumentation flake観察** (issue52系testの `ComposeTimeoutException` timeout、2回連続・別test名) は本PR diffと無関係な安定性の追跡候補であり、本specの対象外とする (「Non-goals」・「Split candidates」節)。

## Outcome

1. P3-5 / P3-6 に対する回帰防止のunit testが既存の純粋test fileに追加され、検証済みのtyped分類 (`INCOMPLETE_COVERAGE`、`INVALID_ENUM`) が後続変更で黙って壊れない。
2. P3-3 / P3-9 について、現状の実装・証拠・対応optionと判断基準がdecision packetとして記録され、Owner判断なしに契約変更や実装変更へ進むpathが存在しない。
3. P3-2 / P3-4 / P3-7 / P3-8 の解消状態がcode証拠付きで記録され、解消済み項目への重複着手を防ぐ。

生産codeの振る舞いは一切変更しない。既存run (intentなし) とintentありrunのplan挙動は不変である。

## Scope

- `tests/unit/app/lawnchair/organizer/personalization/IntentValidatorTest.kt` へのunit test追加 (2項目、AC-1/AC-2)。
- 本spec内でのdecision packet記録 (D-323-1 / D-323-2、AC-4)。
- P3解消状態の証拠記録 (本spec「現在の状態」節。AC-4の一部)。

## Non-goals

- **production source変更** — `ContextExportBuilder` のtyped failure化、`pageAffinity`/preserve hint消費semanticsの変更、validator・planner・allocatorへのいかなる変更も含まない。
- **spec 204の変更** — P3-3/P3-9の対応でspec 204 (accepted) の契約節を変える判断はOwnerが別途行う。本specはその判断をしない。
- **Issue #323本文checkboxの更新** — Owner判断 (Issueコメント `5679570064` のとおり)。
- **instrumentation flakeの調査・追跡Issue作成** — 観察の記録とsplit候補の提示のみ。
- **新Issueの自動作成**。
- #205/#206 (composer/producer接続) のいかなる作業。

## Acceptance criteria

- [ ] AC-1: `unresolvedRefs` が内部重複を含む場合 (同一exported refが2回出現し、`itemIntents` とは重複しない) に、validatorが `INCOMPLETE_COVERAGE` でrejectすることを直接assertするunit testが `IntentValidatorTest` に存在する (`DUPLICATE_REF` / `UNKNOWN_REF` に分類されないこともassertする)。
- [ ] AC-2: movable refへの `pageAffinity` がexport gridのpage範囲外 (負、および `grid.pageCount` 以上) の場合に、validatorが `INVALID_ENUM` でrejectすることを直接assertするunit testが `IntentValidatorTest` に存在する (両境界を個別に実行する)。
- [ ] AC-3: AC-1/AC-2の追加は既存純粋test fileへのtest追加のみであり、production source・build設定・dependencyの変更を伴わない。`./gradlew spotlessCheck` とorganizer unit test suite (`app.lawnchair.organizer.*`) が通過する。
- [ ] AC-4: P3-3/P3-9のdecision packet (option、判断基準、影響範囲) とP3-2/P3-4/P3-7/P3-8の解消証拠が本specに記録され、`spec 204`・生産codeへの無承認変更を禁止する注記が維持される。本specの実装 (AC-1〜AC-3) の完了はP3-3/P3-9の解消を意味しない。

## Decision packets (判断待ち — 本specでは決定しない)

### D-323-1: export側degenerate入力のfailure表現 (P3-3)

- **現状**: 純粋builder内のfail-loud。>512 items と空labelは `require` (→`IllegalArgumentException`)、ref衝突は `IllegalStateException`。いずれもimport側typed failure経路 (`IntentValidationFailure`) の外であり、export側にはtyped failure型が存在しない。builderは純粋で永続化前であるためzero-writeは構造的に維持される。
- **Option (a)**: 現状を受け入れ、fail-fastの意図 (app内部canonical stateの不変条件違反であり、外部入力ではない) をKDoc/監査記録で明文化する。外部AIが触れる表現はexport文書とimport validatorのみであり、degenerate入力はLauncher内部bugの検出である。
- **Option (b)**: export側typed failure経路を導入する (spec 204のContract 1/生成規則の契約変更を伴い、`AndroidExportSessionStore.save` 前のtyped result化。#205 composer接続時に呼び元での扱いも定義が必要)。
- **判断基準**: degenerate入力を生み得るのがLauncher内部bugのみか (#205接続後に外部起点が存在するか)、契約変更のコスト、fail-loudの検出可能性。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。

### D-323-2: preserve + pageAffinity 同時宣言時のcell hint意味 (P3-9)

- **現状**: singleton executorで、同一non-widget itemへの `preserve` と `pageAffinity` (captured pageと異なるpage) の同時宣言時、captured cell座標がaffinity page上の空き判定に使われる (空きならそこへ配置、空きでなければcanonical first fitへ劣化)。spec 204「Planner接続」節は両者を個別のsoft hintとして定義するが、**組合せ宣言時のhint意味は未定義**。allocatorは有効page内の空きcellにのみ配置するため安全性・authority不変は構造的に保証される。
- **Option (a)**: 現行挙動を契約として明文化する (spec 204の該当節へ注記を追加。accepted specの変更のためOwner approvalが必要)。
- **Option (b)**: affinity pageがcaptured pageと異なる場合はcell hintを無効化するよう消費semanticsを変更する (spec 204変更 + 実装変更 + test)。
- **Option (c)**: 契約変更を行わず、現行挙動をpinするcontract testのみ追加する (実装は変わらないが、挙動が意図的であることがtestで固定される)。
- **判断基準**: 実useでの発生可能性 (AIが同一itemに両方を宣言する頻度と#205実装後の実際のgenerator挙動)、意味ぶれのuser可視性、契約変更コスト。
- **状態**: 未決定。Owner判断。対応実装は別Issue/別specを要する。

## Split candidates (本taskでは新Issueを作成しない)

- **instrumentation flake追跡**: issue52系testの `ComposeTimeoutException` timeout flake (初回・第2回再auditのCI 1回目attemptで発生、rerunでPASS。別test名で2回)。unit timing test flake (`ReadinessGateTest`、第6回) も同種。安定性追跡として独立Issue化が妥当な候補 (issue本文も「追跡候補」と記載)。

## Behavior scenarios

### Scenario: unresolvedRefs内部重複はcoverage違反

**Given** 2つのexported itemを持つexportとsessionが存在し、
**When** `itemIntents` が空で `unresolvedRefs` が同一refを2回含むintentのvalidationを実行する、
**Then** `INCOMPLETE_COVERAGE` でrejectされ、
**And** `DUPLICATE_REF` でも `UNKNOWN_REF` でもない。

### Scenario: 範囲外pageAffinityはINVALID_ENUM

**Given** 1 pageのexport (pageCount = 1) と全refをcoverするintentが存在し、
**When** movable refの `pageAffinity` に `-1` を指定してvalidationを実行する、
**Then** `INVALID_ENUM` でrejectされる。
**And** 同様に `pageAffinity = 1` (= pageCount、範囲外) でも `INVALID_ENUM` でrejectされる。

### Scenario: 既存挙動は不変

**Given** AC-1/AC-2のtest追加が適用されたrepository、
**When** 既存の全organizer unit testを実行する、
**Then** production差分ゼロのため全testが無修正で通過し、intentなしrunとintentありrunの既存挙動は不変である。

## Verification

- `git diff --check` → PASS。
- `./gradlew spotlessCheck` → PASS。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL (新規2 testを含む全organizer test通過)。
- 詳細は [plan.md](./plan.md) のVerification節。

## Re-entry rule

本specはsnapshot時点 (`origin/main` = `0cf82bc1e61c1874b280a7120dff9594be4fef71`、2026-09-15) の調査に基づく。実装や再開前に、then-current `origin/main` と全Issueコメントを再取得し、本baselineからの差分 (特に#205/#206関連やpersonalization packageへの変更) を確認し、本specの「現在の状態」表とP3-5/P3-6のtest不在を再検証して必要なら更新する。

## References

- [Issue #323](https://github.com/nunu1733/NunuLauncher/issues/323)
- [Issue #204](https://github.com/nunu1733/NunuLauncher/issues/204)、[PR #322](https://github.com/nunu1733/NunuLauncher/pull/322) (merged, head `af890c1e5b`)
- [監査記録: docs/assessment/pr-322-ai-personalization-context-intent-contract.md](../../docs/assessment/pr-322-ai-personalization-context-intent-contract.md)
- [Spec 204](../204-ai-personalization-context-intent-contract/spec.md) (accepted。本specでは変更しない)
- [AGENTS.md](../../AGENTS.md)、[docs/project/github-workflow.md](../../docs/project/github-workflow.md)
