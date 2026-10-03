# High-risk audit: PR #515 Organizerの変更前後図プレビューと項目単位の除外・再計画（#508）

> Status: blocked（CI `final-status` が **failure** で確定。高リスク独立エビデンスのmerge要件を満たさない。詳細はFindings）
> Audit date: 2026-10-04

- Auditor: 実装を行っていない独立session（general-purposeサブエージェント。実装・reviewを行ったZCode/GLM実装sessionとは別の監査専用sessionとして、本repository上でdiff読み・test再実行・CI確認を独立に実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/515
- Head SHA: 8af117b6fc7ce074f9ac32df416e74d697953e8d
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/37141429452 （final-status: **failure**。run全体 conclusion=failure、2026-10-04に完走を確認）
- Criteria: specs/508-visual-preview-item-exclusion/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12 (FR-004, FR-005, NFR-001, NFR-002, NFR-004, NFR-009, NFR-010, NFR-014)

## Scope

- 対象diff: `git diff 8508c14182..8af117b6fc`（35 files, +3532/−242）。実装commit `b9b026f693`（本体）→ `dc418b0021`（Phase 2 review round 1対応）→ `8af117b6fc`（round 2対応）。branch `issue-508-visual-preview-item-exclusion`、working tree clean、headはPR headと一致。
- 主要ファイルを実装・契約の両面から通読した:
  - planning: `lawnchair/src/app/lawnchair/organizer/planning/ProposalExclusionKey.kt`（新規。planning所有のneutral closed型。`organizer.ui`への依存なし）・`ProposalExclusionDerivation.kt`（新規。純粋。常にbase inputから直接導出、`excludableKeys`による妥当性検証、additions+signal同期除去、`Invalid`でfail-closed）
  - application/preview: `PlanDiagramProjector.kt`（新規。`sourceState`→before / `intendedState`→afterの純粋投影。planned pageのsource出現・未宣言page参照・Movable memberのsource不在は`Invalid`でfail-closed。candidate面は`input.targets.additions`正本でunplaced候補も除外可能 — round 1指摘（高）の修正を確認）
  - application/public: `PlanPreview.kt`（`PlanPreviewDetails.diagrams` をnon-null必須化。「図だけ欠けたdetails」は型上構築不能。`PreviewDiagram*` / `PreviewExcludableItem`のtyped model。raw id・生座標は表示に露出しない契約のKDoc明記）
  - application/protocol: `PlanPreviewProtocol.kt`（rows投影と図投影の両成功後に`PlanPreviewDetails`を1回だけ構築するaggregate builder。どちらかの不整合は`MATERIALIZATION_INVALID` fail-closed。`inspectPlan` signature・手順は不変）
  - coordinator: `ManualOrganizationRun.kt`（`applyProposalExclusions`絶対集合admission・`replanGeneration`世代gate・`State.Replanning`・`replanGeneration > 0`のsticky count-only禁止（全解除∅後も継続）・`retryPlanPreview`拡張（同一除外集合のpreview再取得。planner再実行なし）・`cancel`の`Replanning`受付・`Operation`によるbase（`baseInput`/`baseExcludable`/`currentExclusions`）所有と`pending`の現行分離。既存confirm/apply/recovery経路（A2 exact precondition・preview済みplanの同一インスタンス適用）は未変更であることをdiffで確認）
  - UI: `OrganizationPreviewContent.kt`（`PreviewRowExclusion` = `Excludable(key)` | `NotExcludable(typed reason 4種)`の閉集合・`unplacedCandidateExclusions`純粋ヘルパー — round 2指摘（高）の修正を確認）、`ManualOrganizationPreferences.kt`（D-8順: 決定→図→一覧→除外済みgroup→未配置候補group。Replanning面はconfirm無効・直前安定details表示・除外変更可。図は読み取り専用・`mergeDescendants`単一node・collection semantics）、`ManualOrganizationFace.kt`（`Replanning`はCONFIRMATION変種）
  - 共有部品: `ui/diagram/HomeDiagramParts.kt`（新規。幾何・視覚のみ。選択・セッション・icon解決・semantics権威を持たない）と `homeedit/ui/EditSurfaceScreen.kt`（`placeInGrid`→`diagramCellPlacement`委譲、ページBox・予約領域・アイテム視覚内容の共有部品化。#449側のsemantics wrapper・選択経路は不変）
  - docs同期: `CONTEXT.md`（図プレビュー・提案除外の2語）、`DESIGN.md`（§4.2 preview seam族への追記）、`docs/engineering/organizer-diagnostics.md`（図プレビュー・除外集合・再計画世代のdiagnostics流出禁止行）、`docs/engineering/editing-burden-benchmark.md`（B8: §2課題定義・§5 Fixture 19のB8対象A役割・§6現行10/操作数8 vs 提案除外経路目標8未満=6・Change history）
- runtime書き込み経路・migration対象: 本diffはLauncher DB書込み・migration・recovery経路に変更なし（`src/**`・`ApplyProtocol.kt`・`adapter/**`・`store/**`への変更0。`git diff --stat`で確認）。書込みは既存apply経路（confirmが`pending.previewPlan`をそのままapply）のみ。

## Criteria check

`specs/508-visual-preview-item-exclusion/spec.md`（accepted。Revision 3で受入）の受入条件ごとの判定。test名は実在を確認したものを挙げる。

| AC | 判定 | 根拠 |
|---|---|---|
| AC-1 | **充足（JVM）** | `PlanDiagramProjectorTest.beforeAndAfterProjectTheSamePlanStatesWithTypedPlannedReferences` / `dockItemsSortByRankAndFolderMembersCountThroughTheFolder` / `reservedRegionsProjectOnTheirPersistentPage` / `aPlannedPageInTheSourceStateFailsClosed`。UIが`State`経由で受けるのは`PlanPreviewDetails`（typed図model）と`Summary`のみ。`LayoutState`/`ValidatedLayoutPlan`/DB型の非露出はコード確認。同名folderは構造identity（`Persistent` / `PlannedFolder(ordinal)`）で区別。 |
| AC-2 | **充足（JVM）** | `PlanDiagramProjectorTest.projectionIsDeterministic`（2回一致）。rows・counts・diagramsはprotocolが同一`plan`引数から1回の`inspectPlan`内で導出（構造的に同一plan由来）。`PlanPreviewDetails.diagrams`必須化により「図だけ欠けたdetails」は型上存在しない。 |
| AC-3 | **部分充足** | diff上、共有部品は幾何・視覚のみで選択規則・セッション・icon解決を含まない。homeedit JVM test群は本監査でgreen（17 suite / 170 test / 失敗0）。ただしmanual-org-ui instrumentation laneがCIで赤（本PR起因の9件失敗。Findings 1）のため、「既存test群 + エミュレータ操作」の証跡として不成立。#449編集画面自体の失敗testは9件中に含まれない。 |
| AC-4 | **ほぼ充足・1点注意** | `ProposalExclusionDerivationTest.excludingAnExistingMemberFlipsOnlyItsRoleToPreserved` / `restoringEveryExclusionReproducesTheBaseInput`、`ProposalExclusionCoordinatorTest.excludingAnExistingMemberDerivesFromTheBaseAndRepublishesThePreview`（派生inputがbase由来・revision同一・role変更のみ）/ `restoringEveryExclusionReturnsToTheBaseProposal`（復帰）。lock/profile/conservation不変は派生がtargets/signalsのみ変更する構造＋testで担保。**注意**: 「保持セルが他項目の配置先にならない（planner占有のtest）」を実plannerで駆動するtestは無い（coordinator testはcanned planner出力。`PlanningPlacement.place`の既存占有構造に依存する主張にとどまる）。Findings 3。 |
| AC-5 | **部分充足** | `ProposalExclusionDerivationTest.excludingACandidateRemovesItAndItsSignalEntryTogether`（signal同期除去）、`PlanDiagramProjectorTest.anUnplacedCandidateStaysExcludableWithItsPlanningKind`（unplaced候補の除外可能性。round 1指摘のoracle反転を確認）、`OrganizationPreviewContentTest.unplacedCandidatesKeepASurfacePathToTheExcludeAction`（key表面のUI path固定）。**未確認**: 全候補除外時のadditions空scope-composed再計画を実planner/coordinatorで駆動する統合test。Findings 3。 |
| AC-6 | **部分充足** | 生成folder/page行への除外actionなしは`OrganizationPreviewContentTest.excludableRowsCarryTheirKeysAndOtherRowsCarryTypedReasons`（STRUCTURAL_ROW理由）で固定。空差分→NoChangesは`ProposalExclusionCoordinatorTest.anEmptyDiffAfterExclusionEndsAsNoChanges`。**未確認**: min-size未満groupのfolder消滅・不要page消滅を再計画で駆動するplanner test（spec test oracleに明記があるが実装testで確認できず）。Findings 3。 |
| AC-7 | **充足** | `ProposalExclusionCoordinatorTest.aReplanningStateBlocksConfirmUntilTheReplanCompletes`（Replanning中confirm不可・apply 0）/ `aSupersededReplanResultIsDiscardedZeroWrite`（到着逆転で古い結果破棄・零書込み・例外到着順非依存）/ `confirmingAReplannedPreviewAppliesThePreviewedPlanInstance`（applyされたplanがpreview済みplanと同一インスタンス`===`・materialize呼び出しなし）。 |
| AC-8 | **充足（stale直接testを除く）** | `environmentalFailureAfterAnExclusionStopsAtPreviewUnavailable`（sticky禁止＋retry復元）/ `environmentalFailureAfterAFullRestoreStillStopsAtPreviewUnavailable`（全解除∅後も禁止継続）/ `initialPreviewWithoutExclusionsKeepsTheCountOnlyFallback`（初回fallback維持）/ `cancellingAReplanningRunIsZeroWriteAndSilencesTheWorker`（cancel零書込み・worker黙音）/ `anEmptyDiffAfterExclusionEndsAsNoChanges`。staleの零書込みは既存`ManualOrganizationRunTest.previewTimeStaleEndsRunWithA2RejectionAndNeverMaterializesOrApplies`が初回経路を担保するが、再計画経路のstale（`transitionToStale`の`expectedGeneration` gate）への直接testは無い。Findings 3。 |
| AC-9 | **充足** | degraded面（`details == null`）のUI構成は無変更（diff確認。`previewDetailsItems`の`null -> SummaryText(row)`は既存描画と等価）。図のみ欠落の状態は`PlanPreviewDetails.diagrams`必須化＋protocol fail-closed（`PlanPreviewProtocolTest` green）で型上存在しない。 |
| AC-10 | **未充足部分あり** | 実装側: 図itemは`mergeDescendants = true`単一node＋`collectionInfo`/`collectionItemInfo`、行の`stateDescription`、対象外理由の`contentDescription`、strings en/ja両配置（22語ずつ）をコードと`OrganizationPreviewContentTest`・`EditSurfaceA11yDescriptionTest`（green）で確認。**未実施**: emulator TalkBack構造確認・200% font scale・実機owner確認（PR本文Unverified欄の記載と一致）。加えてa11y系instrumentation test 3件（`changeRowsArePlainReadableNodesWithoutLiveRegion` / `changeListTraversalReachesExpandAndReviewActions` / `sameBandAdjustmentMovesAreAnnouncedAsPositionAdjustments`）がCIで失敗しており、既存a11y回帰の証跡も現在赤。Findings 1。 |
| AC-11 | **未充足部分あり** | `editing-burden-benchmark.md`へのB8追加（§2/§5/§6/Change history）とPR本文の会計表（現行10/操作数8 vs 本経路6/操作数6、目標8未満）は確認。既存B1〜B7の値の変更なし。**未実施**: fixtureでのエミュレータ実行による本経路成立の確認（spec test oracleの明示要件。PR本文Unverified欄と一致）。 |
| AC-12 | **未充足** | docs同期（CONTEXT/DESIGN/diagnostics/benchmark）は完了。`docs/assessment/`の本audit記録は作成済み。しかし (1) CI `final-status` がfailure（Findings 1）、(2) `measure_upstream_patch_surface.py` が本head上でFAILとなり、PR本文記録の「counted additions +4」は再現不能（Findings 2）。AC-12の要件を満たさない。 |

## Executed test surface

本監査sessionがhead `8af117b6fc`上で実際に実行したcommandと結果。

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# → BUILD SUCCESSFUL。organizer 166 suite / 1842 test / failures 0 / errors 0 / skipped 0
#   （新規 PlanDiagramProjectorTest / ProposalExclusionDerivationTest / ProposalExclusionCoordinatorTest を含む。

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'
# → BUILD SUCCESSFUL。homeedit 17 suite / 170 test / failures 0 / errors 0 / skipped 0（#449/#507のJVM回帰なし）

./gradlew spotlessCheck
# → BUILD SUCCESSFUL

python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline
# → FAIL（exit 1）: "changed path is neither explicitly excluded, project-owned, nor assigned to a bridge
#   responsibility: lawnchair/src/app/lawnchair/ui/diagram/HomeDiagramParts.kt"
#   --enforce-baseline無しでも同一FAIL。b9b026f693 / dc418b0021 でも同一FAIL、base 8508c14182ではPASS。
#   PR diff numstat: EditSurfaceScreen.kt +25/−79、HomeDiagramParts.kt +165（新規ファイル。base main 4e770fb196→
#   8508c14182間でEditSurfaceScreen.ktに変更は無いことを numstat で確認済み）

gh run view 37141429452 -R nunu1733/NunuLauncher
# → conclusion: failure。final-status: failure。成功: organizer-unit-tests(4m11s) / check-style(1m10s) /
#   build-debug-apk(5m47s) / validate-repo-contract / 他instrumentation lane 6本。
#   失敗: organizer-instrumentation-manual-organization-ui-tests(18m29s, 155件中9件失敗)。
#   skip: organizer-instrumentation-restore-capture-tests / -db-migration-tests（-表示。required source job 3本は
#   いずれもskipなしで成功）
```

比較のため main 直近CI run（37124380959 / 37119782791 / 37108737396）はいずれもsuccessであり、失敗laneのmain上でのlatest成功を確認した。

## Findings

本監査の結論は **merge不可（blocked）** である。以下、阻止要件を1〜2、品質・受入条件の残課題を3〜6に、確認できた強みを最後に列挙する。

### 1. （阻止・高）CI `final-status` が failure — merge不可

run [37141429452](https://github.com/nunu1733/NunuLauncher/actions/runs/37141429452) の `organizer-instrumentation-manual-organization-ui-tests` が **155件中9件失敗**（全て `ManualOrganizationPreferencesInstrumentationTest`）で完走し、`final-status` は failure となった（`if: always()`集約jobの実結果を2026-10-04に確認）。失敗test:

1. `distinctAnchorsInsideOneBandRenderDistinctDestinationTextOnTheCard`
2. `decisionPairStaysDisplayedTogetherAcrossExpansionStates`
3. `largeChangeGroupsTruncateBehindExpandAction`
4. `sameNamedPlacementsAcrossBucketsStayDistinguishableOnTheCard`
5. `previewDetailsRenderConcreteChangeListMatchingPreviewCounts`
6. `previewDetailsHeaderShowsTheSeparateWidgetMoveCount`
7. `changeListTraversalReachesExpandAndReviewActions`
8. `changeRowsArePlainReadableNodesWithoutLiveRegion`
9. `sameBandAdjustmentMovesAreAnnouncedAsPositionAdjustments`

失敗メッセージはいずれも変更一覧の行・展開action（"Show all 6 items"）が **表示されていない／見つからない** というもの。本監査の分析（仮説）: 本PRがD-8順に従い変更一覧の前にbefore/after図4項目を挿入した結果、fixtureの図（空diagramでもgrid面が画面高さ超の大きさで描画される）が変更一覧をviewport外へ押し下げ、`assertIsDisplayed`／LazyColumn未compose領域への`assertExists`が失敗している。main直近runが全てsuccessであること、失敗が確認面の変更一覧描画に集中していることから、flakyではなく本PR起因のUI変更と既存instrumentation testの非同期（test側が図挿入後のlayoutに未追従、またはproduction側の表示経路の問題）である。**AGENTS.mdの高リスクPR要件（final-status success）を満たさないため、修正とCI再実行のうえ再監査（または本auditの更新）が必要**。修正方向の提案: (a) 失敗testへ図を越えるscroll到達を追加する（D-8順が正しく、testが図挿入前に書かれている場合）か、(b) fixtureのdiagram高さ・図の表示条件を見直す（空diagramであっても巨大なgrid面を描画すべきか）。いずれが正かは「確認面に図が常に現れる」仕様（D-1/D-8）に対する判断を要する。

### 2. （高）`measure_upstream_patch_surface.py` がhead上でFAIL — PR本文の「counted additions +4」は再現不能

本監査の再実行では、`--enforce-baseline` 有無にかかわらず **FAIL（exit 1）**: 新規production file `lawnchair/src/app/lawnchair/ui/diagram/HomeDiagramParts.kt` が `docs/assessment/upstream-patch-surface-baseline.json` のいずれのbridge groupにも `project_owned_addition_prefixes`（`organizer/`、`migration/`）にも含まれないため、fail-closedで分類拒否された。実装commit `b9b026f693`・`dc418b0021` でも同一FAIL（本PR内のどの時点でも計測は通過していない）。base `8508c14182` ではPASSする。PR本文は「counted additions +4（EditSurfaceScreen.kt の共有部品委譲）」と記録しているが、これは本ツールの出力として再現できず、EditSurfaceScreen.ktの実際のnumstatは+25/−79である。AC-12（「測定結果をPR本文へ記録」）の記録内容が実測と不一致。提案: baseline JSONへ当該pathの所属（例: homeedit-edit-surface groupへの追加、または#508所有の新group）を定義して再計測し、PR本文の記録を実出力で差し替える。なおCIの `validate-repo-contract` jobは `--verify`（baseline file自体の検証）のみで通過しており、本FAILはCI gate上は顕在化していない（= 手順書どおりのPR本文記録要件が経つだけのため、監査による発見が必要だった）。

### 3. （中）spec test oracleに対するplanner統合testの穴

AC-4の「保持セルが他項目の配置先にならない（planner占有のtest）」、AC-5の「全候補除外時の妥当な再計画（additions空）」、AC-6の「min-size未満groupのfolder消滅・不要page消滅」、AC-8の再計画経路staleについては、実planner（または`inspectPlan`）を除外派生inputで駆動するtestが存在しない。coordinator testはcanned planner出力で世代・state遷移を検証しており、planner占有率・min-size・page消滅はplannerの既存構造（`PlanningPlacement.place`のmarkOccupied、`FolderPolicy.minGroupSize`）に依存する主張にとどまる。spec test oracleは「派生input生成とplanner再計画のJVM test（役割変更・signal除去・min-size・page消滅・全除外…）」を要求しており、現状は部分的な充足。後続Issueまたは本PR内でのtest追加を提案（fail-closedではなく保守性・受入条件の充足の問題）。

### 4. （低）NotExcludable行のcontentDescription連結がlocale非依存の日本語読点

`ManualOrganizationPreferences.kt` の `contentDescription = "$row、$reason"` は日本語読点「、」をハードコードする。en localeでは "Row text、not excludable: ..." となり不自然（読み上げ自体は機能する）。`manual_organization_diagram_item_a11y` のようにlocalized format string（"%1$s, %2$s"）へ置換するのが望ましい。

### 5. （低）`exclusionLabels`のrunをまたいだ生存

`exclusionLabels`（`remember { mutableStateMapOf }`、keyなし）はpreference screenのcomposition存続期間中累積され、plan結合点3が想定した「runIdが変わったら初期化」を実装しない（コードコメントは「stale entryは描画されない」で解決と主張）。除外済みgroupは現行runの`exclusions`のkeyのみ描画するため通常は表示されないが、ItemIdがrun間で衝突した場合にのみ旧runのlabelが表示されうる（`ItemId`はcapture由来の自由文字列。実害の可能性は低いが、防御としてrun切替時のclearが安全）。

### 6. （記録）未実施の証跡 — PR本文Unverified欄と一致

emulator/instrumentation実行による確認面の図・除外操作の証跡、TalkBack読み上げ確認、200% font scale、B8 fixtureでのエミュレータ実行、TalkBack実機owner確認は本監査時点で未実施である。PR本文のUnverified欄はこれらをowner確認事項として正直に列挙しており、記載の一致を確認した（#507の2026-09-29 owner判断の先例に従う扱い）。AC-10/AC-11の一部として未充足であることを本記録に明記する。

### 確認できた強み

- base input分離・常にbaseからの直接派生・世代gate・sticky fallback禁止というround 1〜3のspec/plan修正が、`Operation.baseInput`/`baseExcludable`/`currentExclusions`の所有分離と`expectedGeneration` gateとして実装に正確に反映されている（`ProposalExclusionCoordinatorTest` 10件がそのままoracleになっている）。
- 書込み経路の追加ゼロ: confirmは引き続き`pending.previewPlan`の同一インスタンスをapplyへ渡し、A2 exact precondition・recovery・transactionに触れない。`src/**`・適用/recovery実装への変更0。
- `PlanPreviewDetails.diagrams`必須化＋protocol内aggregate builder＋二重のfail-closed（rows投影と図投影の双方が`Invalid`を`MATERIALIZATION_INVALID`へ写像）により、D-1の「図だけ欠けたdetailsは型上存在しない」が実装されている。
- #449からの共有部品抽出が幾何・視覚のみの最小差分で、選択・セッション・icon解決・semantics権威を共有していない（AC-3のdiff審査を通過）。
- docs同期（CONTEXT 2語・DESIGN §4.2・diagnostics流出禁止行・benchmark B8）が同一PRで完了しており、diagnostics契約の維持が明文化されている。

### 結論

**merge不可（blocked）**。CI `final-status` の失敗（Findings 1）とpatch surface記録の不一致（Findings 2）が解消され、AC-12の機械要件（final-status success + 本audit記録の整合）が揃った時点で、本auditの更新または再監査を要する。実装本体（世代gate・base派生・fail-closed投影・docs同期）の設計とJVM-level品質は高い評価に値するが、確認面UIの変更が既存instrumentation契約を破壊していることがCIによって検出された。これは`final-status` gateと独立監査が意図どおり機能した事例である。
