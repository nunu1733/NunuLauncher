package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Issue #212 / #234: destination specificity contract for the organizer
 * preview projection.
 *
 * The preview destination is derived from the validated plan's intended
 * placement (PlanPreviewProjector.PositionContext) by bucketizing the exact
 * anchor cell into 3x3 row/column bands (`floor(coord * 3 / dimension)`,
 * clamped) and carrying 1-based row and column display ordinals. These tests
 * characterize, on the F-03-observed 4-column grid, the mapping from resolved
 * anchor cells to band labels and — since Issue #234 — the R1 PASS state: the
 * projection keeps `(page, rowOrdinal, columnOrdinal)` lossless, so distinct
 * anchors inside one band project to distinct destinations and the
 * candidate-anchor set derivable from a projected destination is exactly the
 * single resolved anchor. This is a projection-layer claim: it never consults
 * the UI formatter (that responsibility lives in OrganizationPreviewContentTest).
 */
class DestinationRegionMappingTest {

    /**
     * Band boundaries on the 4-column baseline, derived through the projection
     * seam: `floor(x * 3 / columns)` — x=0,1 -> LEFT; x=2 -> CENTER; x=3 ->
     * RIGHT. The LEFT band therefore spans two distinct anchor columns.
     */
    @Test
    fun fourColumnBaselineMapsDistinctAnchorsIntoTheSameCoarseBand() {
        assertEquals(ColumnBand.LEFT, projectedColumnBand(x = 0, columns = 4))
        assertEquals(ColumnBand.LEFT, projectedColumnBand(x = 1, columns = 4))
        assertEquals(ColumnBand.CENTER, projectedColumnBand(x = 2, columns = 4))
        assertEquals(ColumnBand.RIGHT, projectedColumnBand(x = 3, columns = 4))
    }

    /**
     * Grid-size boundary (spec Phase 2.2): the band rule is
     * `floor(coord * 3 / dimension)`, so the band widths shift with the column
     * count — on 5 columns LEFT covers x=0,1, CENTER x=2,3, RIGHT x=4.
     */
    @Test
    fun columnBandRuleIsGridDependent() {
        // 2 columns: x=1 -> floor(3/2)=1 CENTER; no anchor ever maps RIGHT.
        assertEquals(ColumnBand.LEFT, projectedColumnBand(x = 0, columns = 2))
        assertEquals(ColumnBand.CENTER, projectedColumnBand(x = 1, columns = 2))
        // 5 columns: 0,1 LEFT; 2,3 CENTER; 4 RIGHT.
        assertEquals(ColumnBand.LEFT, projectedColumnBand(x = 0, columns = 5))
        assertEquals(ColumnBand.LEFT, projectedColumnBand(x = 1, columns = 5))
        assertEquals(ColumnBand.CENTER, projectedColumnBand(x = 2, columns = 5))
        assertEquals(ColumnBand.CENTER, projectedColumnBand(x = 3, columns = 5))
        assertEquals(ColumnBand.RIGHT, projectedColumnBand(x = 4, columns = 5))
    }

    /**
     * F-03 shape on the 4-column baseline: two items resolve to distinct
     * anchors (0,0) and (1,0) of the same page. Before Issue #234 both
     * projected to the identical coarse destination; the projection now keeps
     * the column display ordinal, so the destinations differ and each
     * projected destination narrows the candidate anchors to exactly the one
     * resolved anchor — the R1 PASS state (visibleCandidates == {resolved
     * anchor}) asserted at the projection layer.
     */
    @Test
    fun f03ShapeDistinctResolvedAnchorsProjectToDistinctDestinations() {
        val fixture = plan(
            columns = 4,
            rows = 6,
            moves = listOf(
                MoveFixture(id = "a", sourceCell = GridCell(0, 0), targetCell = GridCell(0, 0)),
                MoveFixture(id = "b", sourceCell = GridCell(0, 1), targetCell = GridCell(1, 0)),
            ),
        )

        val result = PlanPreviewProjector.project(
            fixture.plan,
            planned(*fixture.moves.map { movedTo(it) }.toTypedArray()),
        ) as PlanPreviewProjector.Result.Ready

        val destinations = result.details.changes
            .filterIsInstance<MoveChange>()
            .associate { it.item.value to it.destination as PreviewPosition.Workspace }

        // The intended anchors are genuinely distinct cells...
        assertEquals(GridCell(0, 0), intendedCell(fixture, "a"))
        assertEquals(GridCell(1, 0), intendedCell(fixture, "b"))
        // ...and the projection keeps them distinct: same band and row
        // ordinal, different column ordinals (1-based: x=0 -> 1, x=1 -> 2).
        val destinationA = destinations.getValue("a")
        val destinationB = destinations.getValue("b")
        assertEquals(PreviewPosition.Workspace(2, false, RowBand.TOP, ColumnBand.LEFT, 1, 1), destinationA)
        assertEquals(PreviewPosition.Workspace(2, false, RowBand.TOP, ColumnBand.LEFT, 1, 2), destinationB)
        assertNotEquals(destinationA, destinationB)

        // visibleCandidates(projected destination, grid) == {resolved anchor}:
        // the anchors under the coarse band that share the projected row AND
        // column ordinal collapse to the single resolved anchor.
        val candidates = anchorsUnder(RowBand.TOP, ColumnBand.LEFT, columns = 4, rows = 6)
            .filter { it.y + 1 == destinationA.rowOrdinal && it.x + 1 == destinationA.columnOrdinal }
        assertEquals(setOf(GridCell(0, 0)), candidates.toSet())
    }

    /**
     * R1 specificity inside one band (Issue #234): the projection carries the
     * 1-based row ordinal AND the 1-based column ordinal, so two anchors
     * differing only in column produce distinct projections.
     */
    @Test
    fun projectionKeepsTheColumnCoordinateInsideABand() {
        val fixture = plan(
            columns = 4,
            rows = 6,
            moves = listOf(
                MoveFixture(id = "a", sourceCell = GridCell(0, 0), targetCell = GridCell(0, 1)),
                MoveFixture(id = "b", sourceCell = GridCell(0, 2), targetCell = GridCell(1, 1)),
            ),
        )

        val result = PlanPreviewProjector.project(
            fixture.plan,
            planned(*fixture.moves.map { movedTo(it) }.toTypedArray()),
        ) as PlanPreviewProjector.Result.Ready

        val destinations = result.details.changes
            .filterIsInstance<MoveChange>()
            .associate { it.item.value to it.destination as PreviewPosition.Workspace }
        // (0,1) and (1,1) differ only in column: distinct projections now.
        assertEquals(PreviewPosition.Workspace(2, false, RowBand.TOP, ColumnBand.LEFT, 2, 1), destinations.getValue("a"))
        assertEquals(PreviewPosition.Workspace(2, false, RowBand.TOP, ColumnBand.LEFT, 2, 2), destinations.getValue("b"))
        assertNotEquals(destinations.getValue("a"), destinations.getValue("b"))
        assertNotEquals(intendedCell(fixture, "a"), intendedCell(fixture, "b"))
    }

    /**
     * Grid-size boundary (spec 212 Phase 2.2 + #234): the band rule is
     * `floor(coord * 3 / dimension)` and the ordinal is `coord + 1`; together
     * they keep every projected destination unique to its anchor on every
     * column count (2/4/5).
     */
    @Test
    fun projectedDestinationIdentifiesTheAnchorOnEveryColumnCount() {
        for (columns in intArrayOf(2, 4, 5)) {
            for (x in 0 until columns) {
                val fixture = plan(
                    columns = columns,
                    rows = 6,
                    moves = listOf(MoveFixture("a", GridCell(0, 0), GridCell(x, 0))),
                )
                val result = PlanPreviewProjector.project(
                    fixture.plan,
                    planned(*fixture.moves.map { movedTo(it) }.toTypedArray()),
                ) as PlanPreviewProjector.Result.Ready
                val destination = (result.details.changes.single() as MoveChange).destination as PreviewPosition.Workspace

                // The (row, column) ordinal pair pins exactly one anchor.
                assertEquals(x + 1, destination.columnOrdinal)
                assertEquals(1, destination.rowOrdinal)
                val candidates = anchorsUnder(destination.rowBand, destination.columnBand, columns = columns, rows = 6)
                    .filter { it.y + 1 == destination.rowOrdinal && it.x + 1 == destination.columnOrdinal }
                assertEquals("columns=$columns, x=$x", setOf(GridCell(x, 0)), candidates.toSet())
            }
        }
    }

    // -- fixture helpers (mirror PlanPreviewProjectorTest) --

    private data class MoveFixture(val id: String, val sourceCell: GridCell, val targetCell: GridCell)

    private class PlanFixture(
        val plan: ValidatedLayoutPlan,
        val moves: List<MoveFixture>,
    )

    private fun projectedColumnBand(x: Int, columns: Int): ColumnBand {
        val fixture = plan(
            columns = columns,
            rows = 6,
            moves = listOf(MoveFixture("a", GridCell(0, 0), GridCell(x, 0))),
        )
        val result = PlanPreviewProjector.project(
            fixture.plan,
            planned(*fixture.moves.map { movedTo(it) }.toTypedArray()),
        ) as PlanPreviewProjector.Result.Ready
        return ((result.details.changes.single() as MoveChange).destination as PreviewPosition.Workspace).columnBand
    }

    private fun intendedCell(fixture: PlanFixture, id: String): GridCell = (
        (
            fixture.plan.actions.single { action ->
                (action.ref as? ApplicationItemRef.PersistentItem)?.itemId == ItemId(id)
            } as ApplyAction.Update
            ).intended.placement as PlacementState.Workspace
        ).cell

    /** Grid cells the coarse band label leaves as destination candidates. */
    private fun anchorsUnder(rowBand: RowBand, columnBand: ColumnBand, columns: Int, rows: Int): Set<GridCell> = (0 until rows).flatMap { y -> (0 until columns).map { x -> GridCell(x, y) } }
        .filter { band(it.y, rows, RowBand.entries) == rowBand && band(it.x, columns, ColumnBand.entries) == columnBand }
        .toSet()

    private fun <B : Enum<B>> band(coordinate: Int, dimension: Int, bands: List<B>): B = bands[((coordinate * 3) / dimension).coerceIn(0, bands.size - 1)]

    private fun plan(columns: Int, rows: Int, moves: List<MoveFixture>): PlanFixture {
        val pages = listOf(
            PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
            PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
        )
        val sourceItems = moves.map { move ->
            CanonicalFixtures.appItem(
                itemId = move.id,
                cell = move.sourceCell,
                page = ApplicationPageRef.PersistentPage(PageId("p0")),
            )
        }
        val sourceState = LayoutState(
            pages = pages,
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = columns, rows = rows),
            items = sourceItems,
        )
        val actions = moves.map { move ->
            val expected = CanonicalFixtures.appItem(
                itemId = move.id,
                cell = move.sourceCell,
                page = ApplicationPageRef.PersistentPage(PageId("p0")),
            )
            val intended = CanonicalFixtures.appItem(
                itemId = move.id,
                cell = move.targetCell,
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
            )
            ApplyAction.Update(ApplicationItemRef.PersistentItem(ItemId(move.id)), expected, intended)
        }
        val validated = ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = actions,
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )
        return PlanFixture(validated, moves)
    }

    private fun movedTo(move: MoveFixture): PlannedPlacement = PlannedPlacement(
        item = ItemId(move.id),
        disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
        target = PlacementTarget.WorkspaceTarget(
            PageRef(PageId("p1")),
            move.targetCell,
            GridSpan(1, 1),
        ),
    )

    private fun planned(vararg placements: PlannedPlacement) = Planned(
        placements = placements.toList(),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )
}
