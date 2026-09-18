package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CompletedPersonalIntent
import app.lawnchair.organizer.personalization.GlobalPreference
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.RefDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #328 (spec 328 AC-4): the pure import summary derivation. The canonical
 * representation is the ONLY item-count input — bare entries never count as
 * wishes, unmentioned refs count as no-judgment, each breakdown dimension
 * counts independently, and the global orientation line is the
 * planner-effective `minimizeMovement` value.
 */
class ExchangeImportSummaryTest {

    private fun completed(
        decisions: Map<String, RefDecision>,
        global: GlobalPreference? = null,
    ): CompletedPersonalIntent = CompletedPersonalIntent(
        exportId = "export-1",
        decisions = decisions,
        globalPreference = global,
        rationale = null,
        confidence = null,
    )

    private fun authored(ref: String, intent: ItemIntent) = ref to RefDecision.Authored(intent)

    private fun summary(
        decisions: Map<String, RefDecision>,
        global: GlobalPreference? = null,
        scope: Int = 0,
    ) = exchangeImportSummary(completed(decisions, global), scope)

    @Test
    fun authoredDimensionsCountIndependentlyAndMissingOnesDoNot() {
        val result = summary(
            mapOf(
                authored("r1", ItemIntent(ref = "r1", importance = Importance.HIGH)),
                authored(
                    "r2",
                    ItemIntent(ref = "r2", desiredGroupRefs = listOf("r1"), groupSemantic = null),
                ),
                authored("r3", ItemIntent(ref = "r3", pageAffinity = 1)),
                authored("r4", ItemIntent(ref = "r4", preserve = true)),
                authored("r5", ItemIntent(ref = "r5", preserve = false)),
            ),
        )
        assertEquals(5, result.recognizedCount)
        assertEquals(0, result.noJudgmentCount)
        assertEquals(1, result.priorityCount)
        assertEquals(1, result.groupCount)
        assertEquals(1, result.placementCount)
        assertEquals("preserve = false is still a stated wish", 2, result.keepCount)
    }

    @Test
    fun oneItemCanCountInSeveralDimensions() {
        val result = summary(
            mapOf(
                authored(
                    "r1",
                    ItemIntent(
                        ref = "r1",
                        importance = Importance.HIGH,
                        groupSemantic = app.lawnchair.organizer.personalization.GroupSemantic(category = "media", freeText = null),
                        regionAffinity = app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM,
                    ),
                ),
            ),
        )
        assertEquals(1, result.priorityCount)
        assertEquals(1, result.groupCount)
        assertEquals(1, result.placementCount)
    }

    @Test
    fun bareEntriesAndOmissionsAreCanonicalNoJudgmentOnly() {
        // A bare entry arrives as UnresolvedAuthored (D-6); an unmentioned ref
        // as UnresolvedByOmission. Neither counts as a wish nor into any
        // breakdown; both merge into the single no-judgment count (D-5).
        val result = summary(
            mapOf(
                "r1" to RefDecision.UnresolvedAuthored,
                "r2" to RefDecision.UnresolvedByOmission,
                authored("r3", ItemIntent(ref = "r3", importance = Importance.LOW)),
            ),
        )
        assertEquals(1, result.recognizedCount)
        assertEquals(2, result.noJudgmentCount)
        assertEquals(1, result.priorityCount)
        assertEquals(0, result.groupCount)
        assertEquals(0, result.placementCount)
        assertEquals(0, result.keepCount)
    }

    @Test
    fun globalOnlyIntentIsNotAnEmptyImport() {
        val result = summary(
            decisions = mapOf("r1" to RefDecision.UnresolvedByOmission),
            global = GlobalPreference(minimizeMovement = true),
        )
        assertEquals(0, result.recognizedCount)
        assertEquals(1, result.noJudgmentCount)
        assertTrue("the planner-effective flag must surface", result.minimizeMovement)
        assertEquals(0, result.priorityCount + result.groupCount + result.placementCount + result.keepCount)
    }

    @Test
    fun absentOrFalseMinimizeMovementIsPlannerEquivalentAndHidden() {
        // `null` and `false` are the same planner value (globalMinimizeMovement
        // = false), so neither shows the orientation line.
        assertFalse(summary(mapOf("r1" to RefDecision.UnresolvedByOmission)).minimizeMovement)
        assertFalse(
            summary(
                mapOf("r1" to RefDecision.UnresolvedByOmission),
                global = GlobalPreference(minimizeMovement = false),
            ).minimizeMovement,
        )
    }

    @Test
    fun scopeCandidateCountPassesThroughForTheRunInEntry() {
        assertEquals(3, summary(mapOf("r1" to RefDecision.UnresolvedByOmission), scope = 3).scopeCandidateCount)
    }

    @Test
    fun summaryModelCarriesNoLabelRefOrFreeTextField() {
        // The privacy boundary is the model shape itself: counts, one boolean
        // and one scope count — nothing that could render a label, ref,
        // rationale or confidence.
        val allowed = setOf(
            "recognizedCount",
            "noJudgmentCount",
            "priorityCount",
            "groupCount",
            "placementCount",
            "keepCount",
            "minimizeMovement",
            "scopeCandidateCount",
        )
        val actual = ExchangeImportSummary::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(allowed, actual)
    }
}
