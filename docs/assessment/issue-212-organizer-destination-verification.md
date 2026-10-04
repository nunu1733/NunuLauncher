# Assessment: Issue #212 — Organizer proposal destination verification

Status: `implemented` (investigation complete)

Date: 2026-09-06
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/212
Spec: [specs/212-organizer-proposal-destination-verification/spec.md](../../specs/212-organizer-proposal-destination-verification/spec.md)

## Verdict

**Case A — planner/apply are correct; the coarse presentation is the current design, and it fails spec R1.**

- Preview destination, apply destination, and the persisted write-set input are one and the same resolved placement. No silent fallback, clamp, or re-resolution happens after preview: the equality chain required by spec R2 holds by construction and is pinned by tests.
- The observed F-03 mismatch ("two items show the same `top left, page 2`, they land on different cells") is **not a functional bug**. The two items genuinely land on different cells; the display is also correct about what it says — it is simply too coarse to identify either cell.
- The rendered destination is a 3×3 row/column band label (`top left` etc.) bucketized from the exact anchor cell. On a 4-column grid one band spans 2 columns (and the LEFT band spans anchor columns 0–1), so a single coarse label leaves 2–4 candidate anchors and cannot uniquely identify the resolved placement. **R1 is NOT satisfied by the current presentation** — the spec explicitly forbids closing this as "already satisfied".

R1/R2/R3 verdicts:

| Requirement | Verdict | Evidence |
|---|---|---|
| R1 — preview identifies the resolved placement | **FAIL** (coarse band label alone) | `DestinationRegionMappingTest`, `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` |
| R2 — preview == apply == persisted placement | **PASS** | `PreviewApplyPlacementEqualityTest` (planner→materializer→preview→write-set input, cell-exact), `PreviewApplyPersistedPlacementEqualityTest` (real `ApplyProtocol` → post-apply capture rebuilt from persisted rows, cell-exact), `ManualOrganizationProductionE2EInstrumentationTest.manualRunUsesProductionCaptureApplyVerificationAndRecovery` (production DB leg: exact preconditions before confirm, capture after apply, recovery restore) |
| R3 — rendered card is acceptance evidence | **PASS** (delivery verified; specificity itself fails R1) | instrumentation test on the real `ManualOrganizationPreferences` card |

Classification per the spec decision matrix: **Case A** (resolved == apply == persisted; region labels are coherently defined; the actual card shows coarse labels only and cannot distinguish distinct resolved placements). Follow-up for R1 goes to the #195 presentation line.

## Source trace (destination data flow)

Confidence: all entries **[SRC]** source-verified at head `ceb1e287d5` of this branch.

| Stage | Concrete field/type | Coordinate meaning | Can mutate destination? | Owner |
|---|---|---|---|---|
| Requested planner decision | `PlacementTarget.WorkspaceTarget(page, cell, span)` from `Allocator.findRowMajorFirstFit` (`lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt:105`) | resolved page + anchor cell (first-fit traversal) | yes — allocation time | `PlanningPlacement` / strategy executors |
| Final resolution | planner `Planned.placements[].target` → materialized into `ValidatedLayoutPlan.intendedState` items (`OrganizationPlanMaterializer.materialize`, `application/actions/OrganizationPlanMaterializer.kt:88-92`) | same exact page + cell + span, frozen | no after this point (reservation guard upstream, `overlapsReservation`) | `OrganizationPlanMaterializer` |
| Proposal model | `PreviewChange` projection: `MoveChange.source/.destination` (`application/public/PlanPreview.kt:60`) | derived view of `intendedState` placements | no — pure projection | `PlanPreviewProjector` |
| Band derivation | `PositionContext.workspacePosition` — `band = floor(coord * 3 / dimension)` clamped, `rowOrdinal = cell.y + 1` (`application/preview/PlanPreviewProjector.kt:288-319`) | 3×3 bucket of the exact cell | no | `PlanPreviewProjector` |
| Formatter | `OrganizationPreviewContent.regionText`/`positionText` (`organizer/ui/OrganizationPreviewContent.kt:143-274`) + `manual_organization_preview_*` resources (`lawnchair/res/values/strings.xml:1116-1141`) | display only; `page %d` + `top left` style region | no | `OrganizationPreviewContent` |
| Rendered Organizer card | `ManualOrganizationPreferences` change list rows (`ui/preferences/destinations/ManualOrganizationPreferences.kt:88-93,672-709`) | preview destination | no | preferences UI |
| Apply request | the same `ValidatedLayoutPlan` captured at preview (`ManualOrganizationRun.confirm`, `organizer/ui/ManualOrganizationRun.kt:347-386` — `pendingPlan.previewPlan` is reused, no re-materialization when present) | apply destination == resolved placement | guarded | `ManualOrganizationRun` |
| Persisted workspace | `prepareApplyWriteSet` → `IntendedStateResolution.resolveAndFinalize` substitutes identities only, never coordinates (`application/adapter/LauncherLayoutAdapter.kt:180-270`, `application/actions/IntendedStateResolution.kt:37-105`) | final placement; exact precondition + `MaterializedStateValidator.matches` gate identity drift | no (A2/A5 exact preconditions reject drift) | `LauncherLayoutAdapter` / `ApplyProtocol` |

Key point for R2: there is exactly one coordinate authority. Preview derives its band from `plan.intendedState`; apply writes `plan.intendedState` (identity substitution only). Nothing recomputes placement between preview and commit. If the workspace drifted, apply fails closed (`STALE_REVISION` / `EXACT_PRECONDITION_FAILED`, `ApplyProtocol.applyWithOuterLease:109-117`) and the run enters `State.Stale` — a silent in-band relocation is structurally impossible.

## Region ⇆ cell mapping (4-column baseline, spec Phase 2)

Band rule: `band(coord) = floor(coord * 3 / dimension)`, coerced into 0..2, applied to the **start cell** (not the span center).

4 columns (F-03 grid):

| Column band | anchor x | Row band (6 rows) | anchor y |
|---|---|---|---|
| LEFT | 0, 1 | TOP | 0, 1 |
| CENTER | 2 | CENTER | 2, 3 |
| RIGHT | 3 | BOTTOM | 4, 5 |

- `top left` on the 4×6 page = anchor cells {(0,0), (1,0), (0,1), (1,1)} — 4 cells. Two items at (0,0) and (1,0) both render `top left, page N` with identical row ordinals: exactly the F-03 display.
- Grid-size boundary (5 columns): LEFT={0,1}, CENTER={2,3}, RIGHT={4}; 2 columns: LEFT={0}, CENTER={1}, RIGHT=∅ (unreachable). The mapping is grid-dependent, deterministic, and total.
- Multiple items legitimately share one region label by design (`Allocator` packs items left-to-right into the same band). The region is a bucket, not a cell.

## Presentation branch stability (spec Phase 5 / R5)

Two branches, both deterministic on the projection inputs:

- `“X”: src → dst (reason)` — `moveRow` when source/destination differ in page, row band, or column band (`OrganizationPreviewContent.moveRowText:129-141`).
- `“X”: position adjusted within <region>(from row a to row b)` — `sameBandAdjustment` branch when both ends normalize to the same page + row band + column band; the row-ordinal note renders only when the row ordinal actually changed (`rowOrdinalNote:203-210`).

Branch choice is a pure function of `(pageDisplayOrdinal, rowBand, columnBand, rowOrdinal)` pairs; same input ⇒ same output (`OrganizationPreviewContentTest.sectionsAreDeterministicForIdenticalDetails`). `position adjusted within top center (from row 2 to row 1)` in the F-03 observation is the same-band branch with a row change — no hidden meaning beyond that. No presentation defect found.

## Test evidence (executed on this branch, head `ceb1e287d5` + tests)

JVM (`./gradlew testLawnWithQuickstepGithubDebugUnitTest`):

- `app.lawnchair.organizer.application.preview.DestinationRegionMappingTest` — 4/4 pass. Characterizes: band boundaries on 4/2/5 columns through the projection seam; the F-03 shape (distinct anchors (0,0),(1,0) → identical coarse projection incl. row ordinal); `visibleCandidates("top left", 4×6) = 4` anchors.
- `app.lawnchair.organizer.application.actions.PreviewApplyPlacementEqualityTest` — pass. Real planner targets → `OrganizationPlanMaterializer` → intended state == preview source == `IntendedStateResolution.resolveAndFinalize` output, cell-exact; projection deterministic.
- `app.lawnchair.organizer.application.protocol.PreviewApplyPersistedPlacementEqualityTest` — pass. The same chain continued through the real `ApplyProtocol`: planner targets → materializer → preview projection → `protocol.apply(plan)` → post-apply `captureCurrent` rebuilt from persisted rows (`FakeLayoutWriter` production-equivalent capture, so reads never echo the write set's intended state). Asserts preview == committed == persisted cell-exact for both in-band anchors, including that the rendered band + row ordinal recompute from the persisted cells.
- `app.lawnchair.organizer.planning.PlannerGeneratedPropertyTest` — 7/7 pass (existing surface; planner-only, does not touch preview/apply — coverage boundary recorded in spec).

Instrumentation (emulator `nunu_qpr2_api36_1`, API 36, `connectedLawnWithQuickstepGithubDebugAndroidTest`, 23/23 pass):

- `ManualOrganizationPreferencesInstrumentationTest.distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` (new, R1/R3 evidence): two moves with distinct in-band anchors render the identical destination text `top left, page 2` on the actual change-list card; no write occurs. Proves the R1 gap is user-visible at the rendered surface, not only in the projection model.
- Existing `previewDetailsRenderConcreteChangeListMatchingPreviewCounts`, `sameBandAdjustmentMovesAreAnnouncedAsPositionAdjustments` still pass — branch stability and rendering unchanged.

## Non-goals honored

No production planner, formatter, or copy was changed in this investigation. The change set is exactly: three JVM characterization tests, one instrumentation evidence test, this assessment, and the spec/plan status updates.

## Follow-up required

R1 is open product work, not resolvable inside an investigation: the Organizer card needs a destination presentation that reduces `visibleCandidates` to the single resolved anchor (row ordinal is already carried by the projection and would cover same-band row moves; column specificity needs either exact coordinates, per-column wording, or a unique region+supplement combination).

Handed off to **Issue #234** ("Organizer proposal card の destination 表示を resolved anchor を一意に識別できる具体性へ更新"), which carries: this assessment and the Case A verdict, the `visibleCandidates(display, grid) == {resolved anchor}` contract, the 4×6 `top left` = 4-candidate-anchor counterexample, the re-evaluation of the closed #195 D5 "text-only is sufficient" decision against this evidence, and `DestinationRegionMappingTest` / `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` as the regression boundary.
