package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.planning.CategoryDecision
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.RejectionReason
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UnplacedItem
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-67-03, D-01–D-03: Planning result projection tests.
 */
class PlanningProjectionTest {

    @Test
    fun projectionEchoesTheEffectiveStrategyIdentityInRunVersions() {
        // Spec 182 / AC-6: the journal carries the effective strategy identity
        // as an approved version identifier for every projected outcome.
        for (outcome in listOf(plannedOutcome(), invalidOutcome(), impossibleOutcome())) {
            val result = PlanningResult(
                revision = dummyRevision,
                ruleVersion = dummyRuleVersion,
                taxonomyVersion = dummyTaxonomyVersion,
                organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
                outcome = outcome,
            )
            val event = PlanningProjection.project(result, journalSequence = 1L)

            assertNotNull(event.versions)
            assertEquals("CANONICAL_PAGE_COMPACT_V1", event.versions!!.strategyVersion)
        }
    }

    private fun plannedOutcome() = Planned(
        placements = emptyList(),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    private fun invalidOutcome() = Rejected.Invalid(reasons = emptyList(), warnings = emptyList())

    private fun impossibleOutcome() = Rejected.Impossible(unplaced = emptyList(), warnings = emptyList())

    private val dummyRevision = RevisionId("dummy_hash")
    private val dummyRuleVersion = RuleVersion("1")
    private val dummyTaxonomyVersion = TaxonomyVersion("1")

    @Test
    fun plannedProjection() {
        val placements = listOf(
            PlannedPlacement(ItemId("item1"), Disposition.Preserved(PreserveReason.DOCK), PlacementTarget.Dock(0)),
            PlannedPlacement(ItemId("item2"), Disposition.Preserved(PreserveReason.WIDGET), PlacementTarget.Dock(1)),
            PlannedPlacement(ItemId("item3"), Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), PlacementTarget.Dock(2)),
        )
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Planned(
                placements = placements,
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = emptyList(),
                warnings = emptyList(),
            ),
        )
        val event = PlanningProjection.project(result, 43L, capturedItemCount = 84)
        assertEquals(PhaseCode.PLANNED, event.phase)
        assertNotNull(event.planSummary)
        assertEquals(84, event.planSummary?.capturedItemCount)
        assertEquals(1, event.planSummary?.movedCount)
        assertEquals(2, event.planSummary?.preservedCount)
        assertEquals(1, event.planSummary?.preservedByReason?.get("DOCK"))
        assertEquals(1, event.planSummary?.preservedByReason?.get("WIDGET"))
    }

    @Test
    fun planningInvalidProjection() {
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Rejected.Invalid(
                reasons = listOf(
                    RejectionReason(RejectionCode.BOUNDS_VIOLATION, emptyList()),
                ),
                warnings = emptyList(),
            ),
        )
        val event = PlanningProjection.project(result, 51L)
        assertEquals(PhaseCode.PLANNING_REJECTED, event.phase)
        assertNotNull(event.error)
        assertEquals("BOUNDS_VIOLATION", event.error?.code)
        assertEquals(1, event.error?.reasonTotal)
    }

    @Test
    fun planningInvalidWithMultipleReasons() {
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Rejected.Invalid(
                reasons = listOf(
                    RejectionReason(RejectionCode.BOUNDS_VIOLATION, emptyList()),
                    RejectionReason(RejectionCode.OVERLAP, emptyList()),
                    RejectionReason(RejectionCode.UNKNOWN_ITEM_KIND, emptyList()),
                ),
                warnings = emptyList(),
            ),
        )
        val event = PlanningProjection.project(result, 52L)
        assertEquals(PhaseCode.PLANNING_REJECTED, event.phase)
        assertEquals("BOUNDS_VIOLATION", event.error?.code)
        assertEquals(3, event.error?.reasonTotal)
        // additionalCodes should contain the other codes
        assertNotNull(event.error?.additionalCodes)
        assert(event.error?.additionalCodes?.contains("OVERLAP") == true)
        assert(event.error?.additionalCodes?.contains("UNKNOWN_ITEM_KIND") == true)
    }

    @Test
    fun planningImpossibleProjection() {
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Rejected.Impossible(
                unplaced = listOf(
                    UnplacedItem(ItemId("item1"), GridSpan(1, 1), UnplacedReason.EXCEEDS_GRID_DIMENSIONS),
                ),
                warnings = emptyList(),
            ),
        )
        val event = PlanningProjection.project(result, 52L, capturedItemCount = 10, candidateItemCount = 1)
        assertEquals(PhaseCode.PLANNING_IMPOSSIBLE, event.phase)
        assertNotNull(event.planSummary)
        assertEquals(10, event.planSummary?.capturedItemCount)
        assertEquals(1, event.planSummary?.candidateItemCount)
        assertEquals(1, event.planSummary?.unplacedCount)
        assertEquals(1, event.planSummary?.unplacedByReason?.get("EXCEEDS_GRID_DIMENSIONS"))
    }

    @Test
    fun planningImpossibleParityWithD03() {
        // D-03: capturedItemCount and candidateItemCount must round-trip
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Rejected.Impossible(
                unplaced = listOf(
                    UnplacedItem(ItemId("item1"), GridSpan(1, 1), UnplacedReason.EXCEEDS_GRID_DIMENSIONS),
                ),
                warnings = emptyList(),
            ),
        )
        val event = PlanningProjection.project(result, 52L, capturedItemCount = 10, candidateItemCount = 1)
        assertEquals(10, event.planSummary?.capturedItemCount)
        assertEquals(1, event.planSummary?.candidateItemCount)
        assertEquals(1, event.planSummary?.unplacedCount)
        assertEquals(1, event.planSummary?.unplacedByReason?.get("EXCEEDS_GRID_DIMENSIONS"))
    }

    @Test
    fun plannedWithCategoriesAndWarnings() {
        val placements = listOf(
            PlannedPlacement(ItemId("item1"), Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), PlacementTarget.Dock(0)),
        )
        val result = PlanningResult(
            revision = dummyRevision,
            ruleVersion = dummyRuleVersion,
            taxonomyVersion = dummyTaxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Planned(
                placements = placements,
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = listOf(
                    CategoryDecision(ItemId("item1"), CategoryId("cat1"), SignalSource.S1, Confidence.EXPLICIT),
                ),
                warnings = listOf(
                    Warning(WarningCode.LEGACY_SHORTCUT_REVIEW, emptyList()),
                ),
            ),
        )
        val event = PlanningProjection.project(result, 44L)
        assertEquals(PhaseCode.PLANNED, event.phase)
        assertNotNull(event.planSummary)
        assertEquals(1, event.planSummary?.confidenceCounts?.get("EXPLICIT"))
        assertEquals(1, event.planSummary?.warningByCode?.get("LEGACY_SHORTCUT_REVIEW"))
    }
}
