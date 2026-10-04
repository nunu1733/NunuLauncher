# High-risk audit: PR #515 Organizerの変更前後図プレビューと項目単位の除外・再計画（#508）

> Status: accepted（再監査round 2。初回監査の阻止findings 2件は解消。残存はFindings 3〜6の低・中項目とowner確認事項）
> Audit date: 2026-10-04
> Audit history: 初回監査 = head `8af117b6fc`（2026-10-04。blocked判定）。再監査 = head `5dd213419c`（本記録）

- Auditor: 実装を行っていない独立session（general-purposeサブエージェント。実装・reviewを行ったZCode/GLM実装sessionとは別の監査専用sessionとして、本repository上でdiff読み・test再実行・CI確認を独立に実施。初回監査と再監査は同一の監査専用session系譜であり、実装sessionとは別作業）
- PR: https://github.com/nunu1733/NunuLauncher/pull/515
- Head SHA: 5dd213419c856cd6b16febd7d28a95a671b46876
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/37148204910 （final-status: **success**。conclusion=success、event=pull_request、headSha=`5dd213419c...` で監査対象commitと一致。2026-10-04に確認）
- Criteria: specs/508-visual-preview-item-exclusion/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12 (FR-004, FR-005, NFR-001, NFR-002, NFR-004, NFR-009, NFR-010, NFR-014)

## Scope

- 対象diff: `git diff 8508c14182..5dd213419c`（38 files）。実装commit `b9b026f693`（本体）→ `dc418b0021`（Phase 2 review round 1対応）→ `8af117b6fc`（round 2対応）→ `cb7942f8da` / `d473658c98`（初回監査のinstrumentation失敗対応。test側修正のみ）→ `5dd213419c`（coordinator testのrace fix）。branch `issue-508-visual-preview-item-exclusion`、working tree clean、headはPR headと一致。
- **production pathは初回監査headから未変更**: `git diff 8af117b6fc..5dd213419c --stat -- lawnchair/src/` は空（exit 0、差分0）。初回監査head以降の差分は5 fileのみ:
  1. `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt`（+94行相当）— 受入済みspec D-8の面順序（決定 → 図 → 変更一覧）により一覧がviewport下へ移動したことへのtest側対応: fixtureの`testDiagrams()`をpage-less化（図の投影・幾何は`PlanDiagramProjectorTest`が所有）、below-foldのassertion直前に`scrollListToText`（`performScrollToNode` + `awaitDisplayed`）を追加、same-named/distinctAnchors系は各行をassert直前にscroll、展開actionはscroll後にDPAD到達（maxPresses=20）。
  2. `tests/unit/.../ProposalExclusionCoordinatorTest.kt` — `aSupersededReplanResultIsDiscardedZeroWrite`のテストレース決定的化（worker2のplanner entryを`started2` latchで待ってから`release1`。旧版はrelease1がgeneration-2 claim前に発火するとworker1の結果が正当に表面化しうるtest側の競合で、coordinator挙動の問題ではない）。
  3. `docs/assessment/upstream-patch-surface-baseline.json` — `ui/diagram/HomeDiagramParts.kt` を`homeedit-edit-surface` groupへ追加（#508 rationaleをresponsibilityに記載）、`expected_measurement`をheadで再採用（`captured_on: 2026-10-04`、`main_commit: 8af117b6fc...`。counted additions 23314 / deletions 1098 / files 105）。
  4. `docs/assessment/upstream-patch-surface-baseline.md` — Acquisition historyへ2026-10-04のrecapture entry（owner #508、accepted spec `specs/508-visual-preview-item-exclusion/spec.md` を引用）。
  5. `docs/assessment/pr-515-visual-preview-item-exclusion.md` — 初回監査記録（本fileの前版）。
- 初回監査で通読した主要ファイル（planning: `ProposalExclusionKey.kt` / `ProposalExclusionDerivation.kt`、application/preview: `PlanDiagramProjector.kt` / `PlanPreviewProjector.kt`、public: `PlanPreview.kt`、protocol: `PlanPreviewProtocol.kt`、coordinator: `ManualOrganizationRun.kt`、UI: `OrganizationPreviewContent.kt` / `ManualOrganizationPreferences.kt` / `ManualOrganizationFace.kt`、共有部品: `HomeDiagramParts.kt` / `EditSurfaceScreen.kt`、docs同期4件）は本再監査でも有効 — production pathがcommit単位で不変であることを上記diffで検証済みのため。
- runtime書き込み経路・migration対象: 本diffはLauncher DB書込み・migration・recovery経路に変更なし（`src/**`・`ApplyProtocol.kt`・`adapter/**`・`store/**`への変更0）。書込みは既存apply経路（confirmが`pending.previewPlan`をそのままapply）のみ。

## Criteria check

`specs/508-visual-preview-item-exclusion/spec.md`（accepted。Revision 3で受入）の受入条件ごとの判定。test名は実在を確認したものを挙げる。

| AC | 判定 | 根拠 |
|---|---|---|
| AC-1 | **充足（JVM）** | `PlanDiagramProjectorTest.beforeAndAfterProjectTheSamePlanStatesWithTypedPlannedReferences` / `dockItemsSortByRankAndFolderMembersCountThroughTheFolder` / `reservedRegionsProjectOnTheirPersistentPage` / `aPlannedPageInTheSourceStateFailsClosed`。UIが`State`経由で受けるのは`PlanPreviewDetails`（typed図model）と`Summary`のみ。`LayoutState`/`ValidatedLayoutPlan`/DB型の非露出はコード確認。同名folderは構造identity（`Persistent` / `PlannedFolder(ordinal)`）で区別。 |
| AC-2 | **充足（JVM）** | `PlanDiagramProjectorTest.projectionIsDeterministic`（2回一致）。rows・counts・diagramsはprotocolが同一`plan`引数から1回の`inspectPlan`内で導出（構造的に同一plan由来）。`PlanPreviewDetails.diagrams`必須化により「図だけ欠けたdetails」は型上存在しない。 |
| AC-3 | **充足** | diff上、共有部品は幾何・視覚のみで選択規則・セッション・icon解決を含まない。homeedit JVM test群green（再監査で17 suite / 170 test / 失敗0）。#449回帰: 初回監査で赤だったmanual-org-ui instrumentation laneは、test側のscroll対応のみで本再監査のCI で全test green（155件 / failed 0）となり、#449編集画面自体の失敗は初回9件中にも含まれなかった。production UI側は初回監査から未変更のため、初回の失敗がtest側のviewport適応不足であったことがCI greenで裏付けられた。 |
| AC-4 | **ほぼ充足・1点注意** | `ProposalExclusionDerivationTest.excludingAnExistingMemberFlipsOnlyItsRoleToPreserved` / `restoringEveryExclusionReproducesTheBaseInput`、`ProposalExclusionCoordinatorTest.excludingAnExistingMemberDerivesFromTheBaseAndRepublishesThePreview`（派生inputがbase由来・revision同一・role変更のみ）/ `restoringEveryExclusionReturnsToTheBaseProposal`（復帰）。lock/profile/conservation不変は派生がtargets/signalsのみ変更する構造＋testで担保。**注意**: 「保持セルが他項目の配置先にならない（planner占有のtest）」を実plannerで駆動するtestは無い（coordinator testはcanned planner出力。`PlanningPlacement.place`の既存占有構造に依存する主張にとどまる）。Findings 3。 |
| AC-5 | **部分充足** | `ProposalExclusionDerivationTest.excludingACandidateRemovesItAndItsSignalEntryTogether`（signal同期除去）、`PlanDiagramProjectorTest.anUnplacedCandidateStaysExcludableWithItsPlanningKind`（unplaced候補の除外可能性。round 1指摘のoracle反転を確認）、`OrganizationPreviewContentTest.unplacedCandidatesKeepASurfacePathToTheExcludeAction`（key表面のUI path固定）。**未確認**: 全候補除外時のadditions空scope-composed再計画を実planner/coordinatorで駆動する統合test。Findings 3。 |
| AC-6 | **部分充足** | 生成folder/page行への除外actionなしは`OrganizationPreviewContentTest.excludableRowsCarryTheirKeysAndOtherRowsCarryTypedReasons`（STRUCTURAL_ROW理由）で固定。空差分→NoChangesは`ProposalExclusionCoordinatorTest.anEmptyDiffAfterExclusionEndsAsNoChanges`。**未確認**: min-size未満groupのfolder消滅・不要page消滅を再計画で駆動するplanner test（spec test oracleに明記があるが実装testで確認できず）。Findings 3。 |
| AC-7 | **充足** | `ProposalExclusionCoordinatorTest.aReplanningStateBlocksConfirmUntilTheReplanCompletes`（Replanning中confirm不可・apply 0）/ `aSupersededReplanResultIsDiscardedZeroWrite`（到着逆転で古い結果破棄・零書込み。初回監査後のcommit `5dd213419c` でtest自体のスケジューリング競合が決定的化され、検証の信頼性が向上）/ `confirmingAReplannedPreviewAppliesThePreviewedPlanInstance`（applyされたplanがpreview済みplanと同一インスタンス`===`・materialize呼び出しなし）。 |
| AC-8 | **充足（stale直接testを除く）** | `environmentalFailureAfterAnExclusionStopsAtPreviewUnavailable`（sticky禁止＋retry復元）/ `environmentalFailureAfterAFullRestoreStillStopsAtPreviewUnavailable`（全解除∅後も禁止継続）/ `initialPreviewWithoutExclusionsKeepsTheCountOnlyFallback`（初回fallback維持）/ `cancellingAReplanningRunIsZeroWriteAndSilencesTheWorker`（cancel零書込み・worker黙音）/ `anEmptyDiffAfterExclusionEndsAsNoChanges`。staleの零書込みは既存`ManualOrganizationRunTest.previewTimeStaleEndsRunWithA2RejectionAndNeverMaterializesOrApplies`が初回経路を担保するが、再計画経路のstale（`transitionToStale`の`expectedGeneration` gate）への直接testは無い。Findings 3。 |
| AC-9 | **充足** | degraded面（`details == null`）のUI構成は無変更（diff確認。`previewDetailsItems`の`null -> SummaryText(row)`は既存描画と等価）。図のみ欠落の状態は`PlanPreviewDetails.diagrams`必須化＋protocol fail-closed（`PlanPreviewProtocolTest` green）で型上存在しない。 |
| AC-10 | **ほぼ充足・実機確認を除く** | 実装側: 図itemは`mergeDescendants = true`単一node＋`collectionInfo`/`collectionItemInfo`、行の`stateDescription`、対象外理由の`contentDescription`、strings en/ja両配置（22語ずつ）をコードと`OrganizationPreviewContentTest`・`EditSurfaceA11yDescriptionTest`（green）で確認。instrumentation a11y系（`changeRowsArePlainReadableNodesWithoutLiveRegion` / `changeListTraversalReachesExpandAndReviewActions` / `sameBandAdjustmentMovesAreAnnouncedAsPositionAdjustments`）は初回監査で失敗していたが、本再監査のCIでgreen（viewport対応のみの修正で、semantics契約自体は無変更）。**未実施**: emulator TalkBack読み上げ構造確認・200% font scale・実機owner確認（PR本文Unverified欄の記載と一致）。 |
| AC-11 | **未充足部分あり** | `editing-burden-benchmark.md`へのB8追加（§2/§5/§6/Change history）とPR本文の会計表（現行10/操作数8 vs 本経路6/操作数6、目標8未満）は確認。既存B1〜B7の値の変更なし。**未実施**: fixtureでのエミュレータ実行による本経路成立の確認（spec test oracleの明示要件。PR本文Unverified欄と一致）。 |
| AC-12 | **充足** | (1) CI `final-status` success（run 37148204910、source job 3本skipなしで成功）。(2) `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` が **PASS（exit 0）**: `HomeDiagramParts.kt` は`homeedit-edit-surface` groupへ#508 rationale付きで帰属し、`expected_measurement` はheadで再採用（Acquisition history 2026-10-04 entry）。再採用済みbaselineに対するdeltaは files +0 / additions +0 / deletions +0。実測値: counted 105 files / +23314 / −1098。(3) docs同期（CONTEXT 2語 / DESIGN §4.2 / diagnostics流出禁止行 / benchmark B8）完了。(4) 本audit記録は新headで更新済み。**残課題（非阻止）**: PR本文のExecuted evidence欄が初回監査時の非再現値「counted additions +4」のまま未更新（Findings 4）。 |

## Executed test surface

本監査sessionが実際に実行したcommandと結果。**再監査（head `5dd213419c`、2026-10-04）** が現行の証跡であり、初回監査（head `8af117b6fc`、2026-10-04）の結果は参照として最後に残す。

**再監査（head 5dd213419c）の実行結果:**

```bash
git diff 8af117b6fc..5dd213419c --stat -- lawnchair/src/
# → 空（exit 0）。production pathは初回監査headから未変更

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# → BUILD SUCCESSFUL。organizer 166 suite / 1842 test / failures 0 / errors 0 / skipped 0
#   （race fix済み ProposalExclusionCoordinatorTest を含む）

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'
# → BUILD SUCCESSFUL。homeedit 17 suite / 170 test / failures 0 / errors 0 / skipped 0

./gradlew spotlessCheck
# → BUILD SUCCESSFUL

python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline
# → PASS（exit 0）。baseline delta: files +0 / additions +0 / deletions +0（expected_measurementは
#   2026-10-04にheadで再採用済み）。実測: counted 105 files / +23314 / -1098。
#   HomeDiagramParts.kt は [homeedit-edit-surface] 22 file(s) に計上

python3 tools/repo-contract/validate_repo_contract.py
# → 既知の refocus-drafts/ broken link 2件のみ（本PR無関係。mainにも存在）

python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 515 --head-sha 5dd213419c856cd6b16febd7d28a95a671b46876
# → 高リスクエビデンスgateの形式検証。CI run 37148204910 がfinal-status successのcompleted runであることを確認

gh run view 37148204910 -R nunu1733/NunuLauncher
# → conclusion: success / final-status: success / event: pull_request / headSha = 5dd213419c...（一致）
#   成功: organizer-unit-tests(5m42s) / check-style(1m20s) / build-debug-apk(4m8s) / validate-repo-contract /
#   manual-org-ui(16m48s, 155件 / failed 0) / 他instrumentation lane 6本。
#   skip: organizer-instrumentation-db-migration-tests / -restore-capture-tests（0s。required source job 3本は
#   いずれもskipなしで成功）
```

**初回監査（head 8af117b6fc、2026-10-04）の実行結果（参照）:**

- 同一のorganizer unit test（166 suite / 1842 test / 失敗0）・homeedit JVM test（170 test / 失敗0）・spotlessCheck → PASS。
- `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` → **FAIL（exit 1）**: `HomeDiagramParts.kt` 未割当。base `8508c14182` ではPASS。→ 本再監査のPASSで解消。
- CI run [37141429452](https://github.com/nunu1733/NunuLauncher/actions/runs/37141429452) → **failure**（manual-org-ui 155件中9件失敗、final-status failure）→ 本再監査のrun 37148204910 で解消。

## Findings

本監査（再監査round 2）の結論は **merge可（高リスク独立エビデンスの機械要件は充足）**。初回監査findings 1〜2は解消、3は残存（非阻止）、4〜6は残存。以下に解消状況を含めて記録する。

### 1. （解消）CI `final-status` — 初回は failure、再監査で success

初回監査（head `8af117b6fc`）でrun 37141429452のmanual-org-ui laneが155件中9件失敗しfinal-statusがfailureとなった。原因は受入済みspec D-8の面順序（決定 → 図 → 変更一覧）により一覧がviewport下へ移動したことへの**test側の未適応**であり、productionコードの欠陥ではなかった（本監査の初回分析仮説どおり。修正は`scrollListToText`追加とpage-less fixture化のみで、`git diff -- lawnchair/src/` が空であることで裏付け）。修正後のCI run [37148204910](https://github.com/nunu1733/NunuLauncher/actions/runs/37148204910) でmanual-org-ui laneは155件 / failed 0 でgreen、final-status success。図の投影・幾何の検証は専用の`PlanDiagramProjectorTest`（JVM）が所有する分離は妥当であり、instrumentation fixtureが図をpage-lessに縮退してもD-8の検証は損なわれない。

### 2. （解消）patch surface計測 — baseline再採用によりPASS

初回監査でFAIL（`HomeDiagramParts.kt` 未割当）だった計測は、同pathが`homeedit-edit-surface` groupへ#508 rationale付きで帰属され、`expected_measurement` が2026-10-04付でhead再採用（`upstream-patch-surface-baseline.md` Acquisition historyにowner #508・accepted spec引用のentry追加）された結果、`--enforce-baseline` でPASS（exit 0、delta +0/+0/+0）となった。再採用は本diff（#508の実装差分）を取り込む正当なrecaptureであり、group責任記述と帰属の整合も確認した。

### 3. （残存・中）spec test oracleに対するplanner統合testの穴

AC-4の「保持セルが他項目の配置先にならない（planner占有のtest）」、AC-5の「全候補除外時の妥当な再計画（additions空）」、AC-6の「min-size未満groupのfolder消滅・不要page消滅」、AC-8の再計画経路staleについては、実planner（または`inspectPlan`）を除外派生inputで駆動するtestが存在しない（本差分では未対応。coordinator testはcanned planner出力で世代・state遷移を検証）。spec test oracleは「派生input生成とplanner再計画のJVM test（役割変更・signal除去・min-size・page消滅・全除外…）」を要求しており、現状は部分的な充足。fail-closedではなく受入条件の充足度の問題であり、後続Issueまたは本PR内でのtest追加を提案する。

### 4. （残存・低）PR本文のpatch surface記録が古い値のまま

PR本文のExecuted evidence欄は初回監査時に記録された非再現値「counted additions +4」のまま未更新である。実態は本監査が確認したとおり「再採用済みbaselineに対するPASS（delta +0/+0/+0、counted 105 files / +23314 / −1098）」であり、AC-12の「結果をPR本文へ記録」の正確さのために本文の差し替えを推奨する（機械gateは本audit fileとCI runで判定されるため阻止ではない）。

### 5. （残存・低）NotExcludable行のcontentDescription連結がlocale非依存の日本語読点

`ManualOrganizationPreferences.kt` の `contentDescription = "$row、$reason"` は日本語読点「、」をハードコードする。en localeでは "Row text、not excludable: ..." となり不自然（読み上げ自体は機能する）。`manual_organization_diagram_item_a11y` のようにlocalized format string（"%1$s, %2$s"）へ置換するのが望ましい。本差分では未修正（次回対応可との実装側判断を記録）。

### 6. （残存・低）`exclusionLabels`のrunをまたいだ生存

`exclusionLabels`（`remember { mutableStateMapOf }`、keyなし）はpreference screenのcomposition存続期間中累積され、plan結合点3が想定した「runIdが変わったら初期化」を実装しない。除外済みgroupは現行runの`exclusions`のkeyのみ描画するため通常は表示されないが、ItemIdがrun間で衝突した場合にのみ旧runのlabelが表示されうる（`ItemId`はcapture由来の自由文字列。実害の可能性は低いが、防御としてrun切替時のclearが安全）。本差分では未修正（次回対応可との実装側判断を記録）。

### 7. （記録）未実施の証跡 — PR本文Unverified欄と一致

emulator TalkBack読み上げ構造確認・200% font scale・TalkBack実機owner確認・B8 fixtureでのエミュレータ実行は本監査時点で未実施である（AC-10/AC-11の一部）。PR本文のUnverified欄はこれらをowner確認事項として正直に列挙しており、記載の一致を確認した（#507の2026-09-29 owner判断の先例に従う扱い）。manual-org-ui instrumentation laneのgreen（155件 / failed 0）により、確認面の図表示・変更一覧・展開actionのemulator動作自体はCI上で実行・検証されている点は初回監査から改善している。

### 確認できた強み

- base input分離・常にbaseからの直接派生・世代gate・sticky fallback禁止というround 1〜3のspec/plan修正が、`Operation.baseInput`/`baseExcludable`/`currentExclusions`の所有分離と`expectedGeneration` gateとして実装に正確に反映されている（`ProposalExclusionCoordinatorTest` 10件がそのままoracle。race fixにより世代破棄testも決定的）。
- 書込み経路の追加ゼロ: confirmは引き続き`pending.previewPlan`の同一インスタンスをapplyへ渡し、A2 exact precondition・recovery・transactionに触れない。`src/**`・適用/recovery実装への変更0。
- `PlanPreviewDetails.diagrams`必須化＋protocol内aggregate builder＋二重のfail-closed（rows投影と図投影の双方が`Invalid`を`MATERIALIZATION_INVALID`へ写像）により、D-1の「図だけ欠けたdetailsは型上存在しない」が実装されている。
- #449からの共有部品抽出が幾何・視覚のみの最小差分で、選択・セッション・icon解決・semantics権威を共有していない（AC-3のdiff審査を通過）。
- docs同期（CONTEXT 2語・DESIGN §4.2・diagnostics流出禁止行・benchmark B8）が同一PRで完了しており、diagnostics契約の維持が明文化されている。
- 初回監査の指摘への対応が手順どおり: CI失敗はtest側修正（production無変更をdiffで証明）で解消、patch surfaceはfail-closed計測の指摘どおりbaseline帰属＋再採用＋Acquisition history記録で解消。`docs/project/github-workflow.md`の独立エビデンス手順が意図どおり機能した。

### 結論

**merge可（accepted）**。高リスクPR要件（CI `final-status` success + `docs/assessment/pr-515-visual-preview-item-exclusion.md` の独立audit）はhead `5dd213419c` で充足された。残存item（Findings 3〜7）は阻止ではなく、AC-10/AC-11のowner確認事項・PR本文の記録更新・planner統合test補強として、merge判断とともにownerの判断に委ねる。これ以後にproduction変更が入った場合は本auditの無効化と再監査を要する（docs-onlyなcommitは監査対象headを変更しない）。
