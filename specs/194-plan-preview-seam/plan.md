# Implementation Plan: Read-only plan preview and PreviewChange projection contract

> Issue: #194
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

- coordinator の現行フロー: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - start: capture (`composeFullOrganization`) → `planner.plan` → `Planned` なら `Summary` 生成 → `PendingPlan(operation, input, result, summary)` 保存 → `State.Preview(summary)` (`ManualOrganizationRun.kt:245-265`)。
  - confirm: `State.Preview` のとき `State.Applying` → `application.materialize(input, result)` (`ManualOrganizationRun.kt:328`) → 失敗なら `State.Stale` + `APPLY_REJECTED` (A2, `STALE_REVISION`) (`emitStaleRejection`, `:651`) → 成功なら `application.apply(plan, runId)` (`:355`)。
- module の現行 materialize: `LayoutApplicationModule.materializeManualFullOrganizationPlan` (`application/protocol/LayoutApplicationModule.kt:153-166`) — readiness gate → `writer.captureCurrent` → revision 照合 → `OrganizationPlanMaterializer.materialize`。lease / mutex は未取得。
- spec 84 先例: `RecoveryPreviewProtocol` (`application/protocol/RecoveryPreviewProtocol.kt`) — module RunMutex non-blocking → `faults.serializationContention()` → writer lease non-blocking → `captureCurrent` 1回 → `finally` 解放。
- writer lease は process-wide で全 kind 排他 (`src/com/android/launcher3/model/LayoutWriteCoordinator.java:99-110` の単一 holder)。MODEL_WRITER 等との競合は現実に起こり得るため、`WriterBusy` は graceful degradation の対象 (spec §Coordinator integration)。
- materializer: `application/actions/OrganizationPlanMaterializer.kt` — 純粋関数。`ApplyAction.Preserve/Update/Insert` を構築し、`Update` は `intended != source` のときのみ発火。planned folder は `ApplicationItemRef.PlannedFolder(ordinal)`。
- planning 側: `Planned` (`planning/PlanningResult.kt:12-18`) — `placements: List<PlannedPlacement>` (item + disposition + target)、`newFolders` (ordinal + members)、`warnings`。既存 folder への再親的政策は v1 に存在しない (`PlanningPlacement.kt:501` — 既存 folder member は `PreserveReason.STRUCTURAL` で現位置保持)。
- capture title: `CanonicalItemState.title: OptionalText` (`application/public/LayoutState.kt:125`)。planning 入力には title が存在しない (`OrganizationInputComposer.mapItem` は読み捨て)。title は `plan.sourceState` 経由で入手可能。
- UI: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` — `State.Preview` は `currentState.summary` のみを消費 (`:153-160`)。本 Issue では `State.Preview` に optional `details: PreviewDetails?` を追加するが、既存 UI は `summary` のみを読み続けるため rendering は不変 (regression-free)。#195 が `details` を消費する。
- 既存 test: `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` (FakeApplication が `ManualOrganizationApplication` を実装)、`tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` (write-free 証明パターン)、`application/adapter/FakeLayoutWriter.kt` (lease / capture counter)。

## Design

### Modules and interfaces

```text
lawnchair/src/app/lawnchair/organizer/application/
├── public/PlanPreview.kt               # PlanPreviewResult / PlanPreview / PreviewChange /
│                                       # PreviewPosition / PreviewLabel / PreviewCounts /
│                                       # RowBand / ColumnBand / PreviewFolderRef / rejection types
├── preview/PlanPreviewProjector.kt     # 純粋 projection object (I/O なし)
└── protocol/PlanPreviewProtocol.kt     # RunMutex + writer lease + capture + revision gate
```

- `LayoutApplicationModule` に `internal fun inspectPlan(input, result): PlanPreviewResult` を追加し、readiness gate の unavailable mapping (`Unavailable(RECONCILIATION_PENDING|FAILED)`) と protocol 呼び出しを担う (`inspectRecovery` と同型)。
- `ManualOrganizationApplication` (internal facade) に `fun inspectPlan(input, result): PlanPreviewResult` を追加。`materialize` は degradation 経路のため維持。
- `PlanPreviewProjector.project(plan: ValidatedLayoutPlan, planned: Planned): ProjectionResult` — `Ready(changes, counts)` / `Invalid`。join 失敗は `Invalid` (`NotPlannable(MATERIALIZATION_INVALID)` に写像)。
- interface の外へ漏らさない complexity: capture / revision 照合 / materialize / join の順序、writer lease の寿命、`RevisionId` の生値。

### Data flow

```text
start() ──> composeFullOrganization() ──> planner.plan(input) ──> Planned (非空)
   └──> inspectPlan(input, result)
          ├─ Previewed  ──> PendingPlan(+非公開 plan) ──> State.Preview(summary, details)  [PREVIEWED]
          ├─ Stale      ──> State.Stale + APPLY_REJECTED(A2, STALE_REVISION)
          ├─ WriterBusy / Concurrent / Unavailable / CAPTURE_FAILED
          │             ──> State.Preview(summary, details=null)  [compatibility fallback]
          └─ OUTCOME_NOT_PLANNED / MATERIALIZATION_INVALID
                        ──> State.PlanningRejected (fail-closed, apply 不可能,
                            UI 互換の presentation alias)
confirm()
   ├─ preview あり ──> apply(preview.plan, runId)   (materialize 省略, A2 が最終 gate)
   └─ preview なし ──> materialize(input, result) ──> apply(plan, runId)   [現行どおり]
```

### Alternatives rejected

- **UI が `PlanningResult` を直接解釈する案** (assessment Option 1): materializer 意味論の UI 側再実装となり drift リスク。非採用。
- **composer が title を planning 入力へ追加する案**: planner 入力契約 (spec 10/12) の変更と #182 の label 境界判断を伴う。非採用 (assessment §4-2)。
- **opaque `PlanPreviewConfirmation` の導入**: recovery とは異なり confirm の対象は in-memory plan であり、A2 exact precondition が TOCTOU を閉じる。token registry は不要な complexity。非採用。
- **preview 失敗時の run 失敗 / 新 State 追加**: WriterBusy は現実に起こる (lease 全 kind 排他) ため、既存 count-only UI を壊さず新 diagnostics も不要な degradation を採用。
- **`ValidatedLayoutPlan` へ rationale metadata を追加**: spec の責務分担 (assessment §5-3) に反し #182 との継ぎ目も悪化。非採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `application/public/PlanPreview.kt` (新規) | result / change / position / label / counts 型 | application-owned preview surface; coordinator と #195 が消費 |
| `application/preview/PlanPreviewProjector.kt` (新規) | 純粋 projection + 正規化規約 | interface 経由で単体テスト可能な単一 seam |
| `application/protocol/PlanPreviewProtocol.kt` (新規) | mutex / lease / capture / revision gate / materialize / projection 呼び出し | spec 84 の protocol パターン踏襲 |
| `application/protocol/LayoutApplicationModule.kt` | `inspectPlan` 追加 + readiness mapping | module-owned operation の composition root |
| `organizer/ui/ManualOrganizationRun.kt` | `ManualOrganizationApplication.inspectPlan` 追加、`State.Preview(summary, details: PreviewDetails?)` への拡張、`PendingPlan` に preview 済み plan を非公開保持、start / confirm 分岐 (fallback + fail-closed) | preview 表示経路と confirm 対象の変更 (spec scope 3)、UI-facing seam (review Blocking 1) |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` (新規) | 投影契約・正規化境界・決定性・Summary 対応一致 | PP-AC-03/04/05/08/12 |
| `tests/unit/.../application/protocol/PlanPreviewProtocolTest.kt` (新規) | write-free 証明・全 result path・serialization contention (P2) | PP-AC-02/03/06/13 |
| `tests/unit/.../ui/ManualOrganizationRunTest.kt` (拡張) | preview 経路 / fallback / fail-closed / 同一 plan インスタンス適用 / details 公開・plan 非露出 | PP-AC-04/07/11 |
| `specs/52-manual-full-organization-vertical-slice/spec.md` | Preview and details / Confirmation 拡張 + scenario 1行 | spec scope 4 |
| `docs/engineering/organizer-diagnostics.md` | title / PreviewChange 流出禁止の明文化 | spec scope 5 |
| `CONTEXT.md` | plan preview 用語追加 | domain language |
| `DESIGN.md` | §4.2 に read-only preview seam の言及 | module surface の正本反映 |

## Migration and recovery

- schema / rule migration なし。DB への書込みは本機能のいかなる経路にも存在しない。
- failure 中の rollback: 該当なし (zero-write)。preview と confirm の間で layout が変わった場合は apply の A2 typed rejection が既存契約どおり機能する。
- release rollback / downgrade: 新型は process-local のため、rollback 時に残存 artifact を生まない。
- backup / restore compatibility: 影響なし (#187 の restore 排他契約も変更しない)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| PP-AC-02/03/06/13 | `PlanPreviewProtocolTest` (write-free counters、determinism、stale、serializationContention) | `./gradlew :lawnchair:testDebugUnitTest --tests "app.lawnchair.organizer.application.protocol.PlanPreviewProtocolTest"` |
| PP-AC-03/04/05/08/10 | `PlanPreviewProjectorTest` (join / normalization / boundary / synthetic identity) | 同上 `--tests "app.lawnchair.organizer.application.preview.PlanPreviewProjectorTest"` |
| PP-AC-04/07 | `ManualOrganizationRunTest` 拡張 (同一 plan インスタンス、fallback、fail-closed、`details` 公開と plan 非露出、Summary 供給維持) | 同上 `--tests "app.lawnchair.organizer.ui.ManualOrganizationRunTest"` |
| PP-AC-11 | `ManualOrganizationRunTest` (`OUTCOME_NOT_PLANNED` / `MATERIALIZATION_INVALID` 注入 → planning rejection、materialize/apply 呼出し 0) | 同上 `--tests "app.lawnchair.organizer.ui.ManualOrganizationRunTest"` |
| PP-AC-12 | `PlanPreviewProjectorTest` または planner contract test (fixture corpus で Summary ≡ PreviewCounts) | 同上 `--tests "app.lawnchair.organizer.application.preview.PlanPreviewProjectorTest"` |
| 全体 | organizer JVM test 全体 + formatting + debug build | `./gradlew spotlessCheck` / `./gradlew assembleLawnWithQuickstepGithubDebug` |

含めるべき観点: unit/contract (projector・protocol・coordinator)、property (決定性、band 正規化の境界値、fixture corpus での v1 planner 対応観測)、failure injection (serialization contention、writer busy、mutex 競合、revision 不一致、capture RuntimeException、join 不整合、Rejected outcome)。DB/integration・UI は本 Issue の変更外 (zero-write と UI 無変更を contract test で裏取り)。

## Documentation updates

- [ ] spec status/history (本 spec、spec 52 拡張 — 実装 PR で適用)
- [ ] CONTEXT.md (plan preview 用語 — 実装 PR で適用)
- [ ] DESIGN.md (§4.2 read-only seam 言及 — 実装 PR で適用)
- [ ] ADR (不要 — Option 3 選択は spec と assessment に記録済みであり、ADR 3条件の「変更が高コスト」に相当する確定済み先例 [spec 84] がある)
- [ ] AGENTS.md (変更不要)

## Execution checklist

- [x] Current behavior traced (coordinator / module / materializer / UI / tests)。
- [ ] spec / plan 承認 (review revision)。
- [ ] `application/public/PlanPreview.kt` + `PlanPreviewProjector.kt` を interface 経由の test 先行で実装。
- [ ] `PlanPreviewProtocol.kt` + module wiring 実装。
- [ ] coordinator (`ManualOrganizationRun`) 更新 (`State.Preview(summary, details)`、fallback / fail-closed 分岐) + 既存 test の維持。
- [ ] spec 52 / organizer-diagnostics.md / CONTEXT.md / DESIGN.md 更新 (実装 PR で適用)。
- [ ] `./gradlew spotlessCheck` + organizer JVM test filter + `./gradlew assembleLawnWithQuickstepGithubDebug` 成功を PR へ記録。
- [ ] `risk: layout-data` label + high-risk independent-evidence gate (CI `final-status` + `docs/assessment/pr-194-plan-preview-seam.md`) を満たす。
