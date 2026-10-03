# Implementation Plan: Organizerの変更前後図previewと項目単位の除外・再計画（#508）

> Issue: #508
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — spec冒頭の根拠と同じ（`organizer/application/preview/**` の高リスクpath拡張 + coordinatorの確認前段契約。書込み経路・適用契約は不変）。手順は現行どおり: accepted spec + 本plan.md、Execution and approval contract、`risk: layout-data` label による高リスク独立エビデンス（CI `final-status` 成功run + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit。auditは実装sessionとは別のgeneral-purposeサブエージェント作業で行う）。
> Phase 1（本書の初版）: spec + planの起草とreviewを追跡する。Phase 2（実装）は同じbranch/PRで行い、本planのRevisionで追跡する（#448/#449/#507と同じ進め方）。

## Current evidence

本planの `path:line` 根拠は2026-10-03にmain `8508c14182` 上で検証した（Issue #508の照合対象 `10ee58336e` から本planで再照合。#507実装込み）。

**preview seamと投影（本機能が拡張する既存資産）**

- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt:17-32` — `PlanPreviewResult`（`Previewed` / `Stale` / `NotPlannable` / `CandidateResolutionFailed` / `Unavailable` / `WriterBusy` / `Concurrent`）。`:51-59` — `PlanPreview`（plan + details）/ `PlanPreviewDetails`（changes + counts）。`:72-155` — `PreviewChange` 行契約（spec 208 identity込み）。`:263-287` — `PreviewCounts`。
- `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` — 純粋投影の既存実装（`ValidatedLayoutPlan` + `Planned` → 行一覧 + counts）。図投影は同directoryへの追加となる。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/PlanPreviewProtocol.kt` — `inspectPlan(input, result)` のprotocol（readiness gate / RunMutex / serialization contention / writer lease / capture 1回 / revision照合 / materialize / projection）。**本機能はこのprotocolの手順・結果型を変えない**（`inspectPlan` のsignature不変。図投影・除外可能表面はprojector内の追加計算）。
- `lawnchair/src/app/lawnchair/organizer/application/public/ValidatedLayoutPlan.kt:16-43` — `ValidatedLayoutPlan`（`sourceState` / `intendedState` が変更前後の正本。`sourceRevision` はA2 gateの照合対象）。
- `lawnchair/src/app/lawnchair/organizer/application/public/LayoutState.kt:27-49` — `LayoutState`（pages / profiles / deviceCapabilities / items / reservedWorkspaceRegions）。`:55-58` — `ApplicationPageRef`（`PersistentPage` / `PlannedPage`）。`:92-96` — `ApplicationItemRef`（`PersistentItem` / `PlannedCandidate` / `PlannedFolder`）。`:98-111` — `PlacementState`。`:117-132` — `CanonicalItemState`（title / kind / placement / lockState / structure）。`:175-182` — `StructureState`（folder members / app pair）。

**coordinator（本機能が拡張する確認前段）**

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:527-712` — `State`（`Preview(summary, details)` は `:637`、`PreviewUnavailable` は `:645`）。`:1982-2036` — `handlePlanPreview`（preview結果の分岐。Add有りrunのfallback禁止は既に #228 AC-14 で確立済み）。`:2471-2483` — `enterPreview`（pending置換 + `State.Preview` 公開）。`:2102-2176` — `confirm`（`pending.previewPlan != null` なら同一インスタンスをapplyへ。nullならconfirm時materialize）。`:2680-2690` — `PendingPlan`（operation / input / result / summary / previewPlan）。`:1698-1712` — `retryPlanPreview`（`pending` から再実行。PreviewUnavailableの再試行）。`:2056-2100` — `cancel`。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:1756-1972` — `runComposedPhase`（composed input → planner.plan → 結果分岐。空差分は `:1949-1957` のNoChanges/Impossible分岐）。`:2548-2580` — `PlanningResult.summary`（Summary組立）。

**再計画の対象集合変更が依存する既存契約**

- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt:259-272` — `TargetSet` / `ExistingTargetMembership` / `ExistingRole`（`Movable` / `Preserved`）。`:175-196` — `CandidateItem` / `CandidateTarget`。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:52-66` — **preserved項目のworkspaceセルはallocatorへmarkOccupiedされる**（除外項目の元セルが他項目の配置候補にならない構造的根拠）。`:437-485` — `determinePreservation`（`role == ExistingRole.Preserved -> PreserveReason.NON_TARGET` は `:474`。production composerはrole Preservedを必ずより強い述語と同時に割り当てるため、NON_TARGETは現行production到達不能 = 本機能が最初の正当な生産者になる）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt:571-575` — `checkUnknownSignalItem`（signalはsnapshot items ∪ additions のいずれかを指す必要 → 追加候補除外時にsignal entryの同期除去が必要な根拠）。`:633-657` — `checkIncompleteTargetPartition` / `checkAdditionsUnderFull`（role変更はmembershipを変えないためpartitionは完全のまま。runMode不変でFullOrganization+additions違反も生じない）。
- `lawnchair/src/app/lawnchair/organizer/integration/FullTargetSetMaterializer.kt:27-57` — role割当規則（`Movable` は workspace配置の APPLICATION / DEEP_SHORTCUT / FOLDER のみ。D-3の対象はこのうちAPPLICATION/DEEP_SHORTCUT）。
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt:447-471` — 対象集合とprovenanceの組立（`scopeComposedTargetsIdentity`。派生inputはcomposerを再実行しないためprovenance再計算なし。決定性は入力の等価性から従属）。

**#449の図描画（部品共有の対象）**

- `lawnchair/src/app/lawnchair/homeedit/ui/EditSurfaceScreen.kt:496-539` — `DiagramGrid`（ページ見出し + ページグリッド縦積み + dock行。private）。`:541-597` — `PageGrid`（セル配置Box + 予約領域描画。private）。`:599-609` — `Modifier.placeInGrid`（cell→offset/size計算。private）。`:611-648` — `DockRow`。`:650-793` — `DiagramItemView`（folderはicon+メンバー数、widgetはfootprint、それ以外はiconまたはlabel。`icon: ImageBitmap?` がnullable = 非icon表示が既定で可能）。`:794-861` — `editSurfaceItemSemantics` / `editSurfaceItemDescription`（semantics供給の単一権威。純粋descriptor）。
- `lawnchair/src/app/lawnchair/homeedit/EditSurfaceProjection.kt:20-41` — `EditSurfaceDiagram`（persistent数値id前提。organizer図へは流用しない）。`EditSurfaceScreen.kt` の選択・アクションバー・dialogはorganizer図と共有しない。

**確認面UI**

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:956-1005` — `State.Preview` の描画（`details == null` のdegraded面と `details != null` の決定group先頭+一覧）。`:1007-1038` — `PreviewUnavailable` 面（再試行・中断）。`:314-319` — `execute`（Dispatchers.IO上でmachine entryを実行。publication threadはmain）。`:1825-` — `previewDetailsItems`（grouping・truncation）。`:1588-` — `PreviewDecisionActions`。
- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt:168-260` — `sections`（純粋行構築。groupingはspec 195 D-6）。`:435-449` — `preservedReasonText`（`NON_TARGET` の文言 `manual_organization_preview_preserved_reason_non_target` はen/ja既存）。

**テスト・CI**

- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' ...`（`organizer-unit-tests` gate。organizer application/planning/ui配下の新規JVM testは自動加入。新laneなし）。
- `tools/repo-contract/ci_portfolio_map.yml` — instrumentation lane↔surface。確認面UIの新規instrumentation testは既存 `manual-org-ui` 系laneへ追加する（新laneなし。test-audit skillを適用して配置を確定する）。
- `docs/engineering/editing-burden-benchmark.md` §4（重み表）/ §5（fixture）/ §7（検証手順）— B8の追加先。

## Design

### Modules and interfaces

```text
organizer/application/（高リスクpath。additiveのみ）
├── preview/
│   ├── PlanPreviewProjector.kt            # 既存。行一覧・counts（不変）
│   │                                      #   ※除外可能表面の計算に必要な対象role情報を
│   │                                      #    internal引数で追加受取（public seam不変）
│   └── PlanDiagramProjector.kt            # 新設: 純粋。ValidatedLayoutPlan（+ 現行inputの
│                                          #   対象role）→ PlanPreviewDiagrams + 除外可能表面。
│                                          #   before = plan.sourceState、after = plan.intendedState。
│                                          #   Android型なし・I/Oなし・決定的
├── public/
│   └── PlanPreview.kt                     # PlanPreviewDetails へ additive な拡張:
│                                          #   diagrams: PlanPreviewDiagrams? = null
│                                          #   excludableItems: List<PreviewExcludableItem> = []
│                                          #   （図model・除外表面の公開型も同fileへ。production
│                                          #    inspectPlanは常にdiagrams非nullで構築する）
│                                          #   既存のchanges/counts/行契約は不変
└── protocol/
    └── PlanPreviewProtocol.kt             # 手順・結果型・inspectPlan signatureは不変。
                                           #   projector呼出のみ拡張（新規計算を同protocol内で実行）

organizer/planning/
└── ProposalExclusionDerivation.kt         # 新設: 純粋。元のOrganizationInput + 除外集合
                                           #   → 派生OrganizationInput（targets.existingのrole変更、
                                           #    additions・signal entriesの同期除去。常に元input
                                           #    から直接導出）。妥当性検証（対象键がMovable
                                           #    top-level app/deep-shortcut ∪ additionsに一致、
                                           #    partition完全性）をtyped結果で返す

organizer/ui/
└── ManualOrganizationRun.kt               # 拡張:
                                           #   - State.Preview へ exclusions field（default空。additive）
                                           #   - 新State.Replanning（summary + 直前の安定details + exclusions）
                                           #   - applyProposalExclusions(next: Set<ProposalExclusionKey>)
                                           #     （絶対集合。State.Preview/Replanningから受理。
                                           #      世代カウンタ claimed under lock、完了時世代一致検査）
                                           #   - 再計画: derive → planner.plan → 既存handlePlanPreview
                                           #     相当の分岐（除外集合非空なら環境失敗でfallback不可）
                                           #   - PendingPlan へ replanGeneration は不要（Operation側へ保持）
                                           #   - confirm/cancel/stale系は既存規則のまま

homeedit/ui/
└── EditSurfaceScreen.kt                   # 静的描画部品の抽出元。抽出後も見た目・操作不変
                                           #   （選択・ActionBar・dialog・icon解決はここに残る）

app.lawnchair.ui/diagram/（新設。中立なfork側package。置き場所の最終判断は実装時）
└── StaticDiagram.kt                       # 新設: internal共有の静的図描画部品（最小）:
                                           #   - Modifier.placeInGrid 相当のセル配置計算
                                           #   - ページグリッドBox（背景・padding）と予約領域描画
                                           #   - 読み取り専用アイテム表示（icon nullable・
                                           #    folder=メンバー数・widget=footprint・label fallback）
                                           #   - 意味論なし（選択状態・eligibility・sessionは持たない）
                                           #   #449のEditSurfaceScreenとorganizer確認面の双方から利用

ui/preferences/destinations/
└── ManualOrganizationPreferences.kt       # 確認面の拡張（D-8の順序）:
                                           #   決定group → 図（変更前/変更後）→ 変更一覧 → 除外済みgroup。
                                           #   除外action（対象行単位）・再計画中の無効化・
                                           #   Replanning面の進行表示。degraded/PreviewUnavailable面は不変
```

### Public surface additions（閉じた列挙）

```text
PlanPreviewDiagrams {
  before: PreviewDiagram
  after:  PreviewDiagram
}

PreviewDiagram {
  columns: Int, rows: Int
  pages: List<PreviewDiagramPage>        // 表示順。永続pageはisPlanned=false、
                                         // planned pageはNewPageOrdinalを保持するtyped参照
  dockItems: List<PreviewDiagramItem>    // rank順
  reservedRegions: List<PreviewDiagramRegion>  // page参照つき
}

PreviewDiagramPage { ref: PreviewDiagramPageRef, items: List<PreviewDiagramItem> }

PreviewDiagramItem {
  ref: PreviewDiagramItemRef             // Persistent(ItemId) | PlannedFolder(NewFolderOrdinal)
                                         // | PlannedCandidate(ItemId)。表示には使わない
  label: PreviewLabel                    // 既存型（Named / KindFallback）
  kind: CanonicalItemKind
  placement: Workspace(pageRef, cell, span)   // dockは別list。folder memberは親folderの
                                         // memberCountで表現（図はfolder単位。メンバー特定は一覧が所有）
  memberCount: Int?                      // folderのみ
}

PreviewExcludableItem {
  key: ProposalExclusionKey              // Existing(ItemId) | Candidate(ItemId)。opaque
  label: PreviewLabel
  kind: CanonicalItemKind
  isCandidate: Boolean
}

ProposalExclusionKey                     // coordinator APIとStateが運ぶ鍵（organizer.ui内）
```

- 図modelは `LayoutState` / `ValidatedLayoutPlan` を運ばず、表示に必要な値のみ。`ItemId` / `NewFolderOrdinal` はopaque相関キー（表示・diagnostics禁止。spec 194と同一規約）。
- `PreviewCounts`・行契約・`PreviewPosition` 正規化（spec 194/208/234）は不変。

### Data flow（除外の例）

```text
除外action tap（対象行）→ UI: next = 現在のexclusions ± key → coordinator.applyProposalExclusions(next)
  under lock: state ∈ {Preview, Replanning}、activeOperation/pending 存在、鍵がpending inputの
    除外可能集合に一致することを検証 → 世代++ → State.Replanning(summary, stableDetails, next)
  worker:
    ProposalExclusionDerivation.derive(元のinput, next)   // 純粋。常に元inputから直接
      → derivedInput（role変更 / additions・signal除去。snapshot revision不変）
    planner.plan(derivedInput) → PlanningResult
      （空差分 → finish(State.NoChanges)。Rejected → 既存PlanningRejected）
    handlePlanPreview相当（derivedInput, result）:
      Previewed → 世代一致を検査 → pending = PendingPlan(op, derivedInput, result, newSummary,
                    previewPlan) → State.Preview(newSummary, newDetails, exclusions = next)
      Stale → 既存 State.Stale(DETECTED_BEFORE_REVIEW) + APPLY_REJECTED event（零書込み）
      環境失敗かつ next 非空 → pending を (derivedInput, result, summary, null) へ置換 →
                    State.PreviewUnavailable(summary)（再試行は retryPlanPreview が
                    pending から同一除外集合で再実行）
      CandidateResolutionFailed / NotPlannable(OUTCOME_NOT_PLANNED|MATERIALIZATION_INVALID)
                    → 既存typed state / fail-closed
      世代不一致（より新しい要求が存在）→ 結果を破棄（零書込み・state不変）
confirm → 既存どおり pending.previewPlan（同一インスタンス）を apply へ。A2 exact precondition 不変
```

### 結合点（実装時に確認・確定する事項）

1. **除外可能表面の計算に必要なrole情報のprojectorへの供給**: `PlanPreviewProjector` / `PlanDiagramProjector` は `inspectPlan` の内部で `input.targets` を受け取る（public seamのsignature不変）。role情報は行契約へ露出せず、除外可能表面の計算のみに使う。
2. **`Replanning` のface mapping**: `ManualOrganizationFace` の種別判定へ `Replanning` を追加する（T-10確認面の変種として扱い、新規面は作らない）。traversal・focus契約（spec 209）への影響を確認する。
3. **除外済み候補labelの保持**: UI側で「直前のdetailsのAdd行label」を保持する。runIdが変わったら初期化する（`Selecting` surfaceと同じ規約）。
4. **共有部品の置き場所と形状**: `app.lawnchair.ui.diagram`（または同等の中立package）へ置く。`DiagramItemView` から選択semanticsを除去した読み取り専用版と、#449が使う選択対応版の関係（同一部品にnullableなclick handlerを渡す形で一致できるか）を実装時に確定する。#449側の既存instrumentation test（選択・traversal・spec 507の重複確認）がgreenであることを以て抽出の無害性を検証する。
5. **B8 fixture**: benchmark §5のfixtureへ「Aの元セルが適用後に最初の空きセルになる」前提を追加するseedingを確定する（既存fixtureの拡張。新規fixtureファイルは作らない）。

## Change set（想定diff範囲）

- `lawnchair/src/app/lawnchair/organizer/application/preview/**`（新規図投影 + 除外可能表面）— 高リスクpath
- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt`（additive拡張）— 高リスクpath
- `lawnchair/src/app/lawnchair/organizer/application/protocol/PlanPreviewProtocol.kt`（projector呼出の拡張のみ。手順不変）— 高リスクpath
- `lawnchair/src/app/lawnchair/organizer/planning/ProposalExclusionDerivation.kt`（新規。純粋）
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（State拡張・再計画・世代）
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt`（Replanningの種別判定のみ）
- `lawnchair/src/app/lawnchair/ui/diagram/**`（新規共有静的描画）+ `homeedit/ui/EditSurfaceScreen.kt`（抽出による置換。振る舞い不変）
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`（確認面拡張）
- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`
- `tests/unit/app/lawnchair/organizer/**`（図投影・派生・coordinator test）+ 既存instrumentation laneへの追加test
- docs（最終PR）: `CONTEXT.md` / `DESIGN.md` / `docs/engineering/editing-burden-benchmark.md` / `docs/engineering/organizer-diagnostics.md` / 本spec・本plan / `docs/assessment/pr-<PR>-<slug>.md`
- **触れない**: `src/**`、`organizer/application/protocol/ApplyProtocol.kt`・`adapter/**`・`store/**`（適用・recovery実装）、plannerの配置実装、`FullTargetSetMaterializer`（composerは再実行しない）、#449/#450/#507の選択・適用・Undoの各契約実装。

## Verification

- JVM（`organizer-unit-tests` gateに自動加入）:
  - 図投影: fixture（複数page・span・予約領域・dock・同名folder・planned folder/page/candidate）・決定性（2回一致）・図↔一覧の同一plan由来・planned参照のtyped保持。
  - 派生input: role変更・additions/signal同期除去・妥当性検証のtyped拒否・決定性・conservation/lock/profile不変（planner property testの既存corpusで派生inputも検証）。
  - planner再計画: min-size未満groupのfolder消滅・不要pageの消滅・全除外・全変更空→NoChanges・冪等性。
  - coordinator: 除外適用・世代（遅延完了注入による破棄）・Replanning中confirm拒否・環境失敗→PreviewUnavailable（同一除外集合再試行）・初回fallback維持・stale零書込み・cancel/process死・`details = null` 面の図・除外なし・previewPlan同一インスタンス適用。
  - UI純粋部: 除外可能行の判定・除外済みgroupの行構築・semantics descriptor。
- instrumentation（既存laneへの追加。test-audit skillで配置確定）: 確認面の図表示・除外操作→再計画→確定の一連、TalkBack構造、200% font scale。
- emulator実行: benchmark B8 fixture（§5拡張）で固定手順の成立。スクリーンショット（図before/after・除外・除外済みgroup・再計画中・200% font）をPRへ記録し、実機での表示・操作・TalkBack読み上げをowner確認に含める（階層Hでも実機owner確認は行う）。
- `./gradlew spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` / `python3 tools/repo-contract/validate_repo_contract.py` / `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`（PR本文へ記録）。
- 高リスク独立エビデンス: CI `final-status` 成功run URL + `docs/assessment/pr-<PR番号>-<slug>.md`（Auditor: 実装を行っていないgeneral-purposeサブエージェント）。

## Migration and recovery

- 永続data・schema・migration・backup/restoreへの影響なし（図・除外・派生inputはすべてprocess-local）。
- rollback: 機能全体が確認面の表示と再計画経路に閉じるため、revertで既存の確認・適用・復旧経路へ戻る（既存契約を変えないため）。
- 適用・復旧の契約（spec 13 / A2 gate / recovery point / 相関reload）は本機能では一切変更しない。確認は常に表示済みpreviewと同一のplanを適用する。

## Rollout /並行作業

- 共有seam: `PlanPreviewDetails` の拡張（additive）と `EditSurfaceScreen` の描画抽出。並行作業がある場合は本planのChange setを先行確定とし、`ManualOrganizationRun.kt` と `ManualOrganizationPreferences.kt` の同時編集を避ける（workflow「Parallel work」）。

## Open items（Phase 2で解決する結合点）

- 結合点1〜5（Design節）。いずれも実装の詳細確定であり、specの受入条件・契約を弱めるものはない。確定結果は本planのRevisionへ記録する。
