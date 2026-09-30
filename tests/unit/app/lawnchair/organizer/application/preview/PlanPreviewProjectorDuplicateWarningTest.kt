package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #451 (spec 451 N-6/AC-7): duplicate surplus items surface through the
 * existing preview paths only — a preserve row carrying the
 * `DUPLICATE_LAUNCH_TARGET` reason, a warning row in the existing warning
 * group, and the spec 195 D2 count truth — with no new row kinds. The
 * projection must stay `Ready`: the surplus item always has a Preserve action,
 * so the deterministic join always succeeds.
 */
class PlanPreviewProjectorDuplicateWarningTest {

    @Test
    fun duplicateSurplusProjectsAsPreserveRowAndWarningRowWithMatchingCounts() {
        val source = CanonicalFixtures.appItem(
            itemId = "photos.2",
            title = OptionalText.Present("Photos"),
            cell = GridCell(1, 0),
        )
        val validated = plan(
            sourceItems = listOf(source),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("photos.2")), source)),
        )
        val planned = Planned(
            placements = listOf(preserved("photos.2", PreserveReason.DUPLICATE_LAUNCH_TARGET)),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(
                Warning(WarningCode.DUPLICATE_LAUNCH_TARGET, listOf(DiagnosticParam.ItemParam(ItemId("photos.2")))),
            ),
        )

        val result = PlanPreviewProjector.project(validated, planned) as PlanPreviewProjector.Result.Ready

        // The preserve row speaks the duplicate reason.
        val preserveRow = result.details.changes.filterIsInstance<PreservedChange>().single()
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preserveRow.reason)
        assertEquals(ItemId("photos.2"), preserveRow.item)

        // The warning group carries exactly one concrete row for the surplus.
        val warningRow = result.details.changes.filterIsInstance<ItemWarningChange>().single()
        assertEquals(WarningCode.DUPLICATE_LAUNCH_TARGET, warningRow.code)
        assertEquals(ItemId("photos.2"), warningRow.item)

        // Spec 195 D2: the header count equals the concrete row count; the
        // duplicate warning adds no new row kind.
        assertEquals(mapOf(WarningCode.DUPLICATE_LAUNCH_TARGET to 1), result.details.counts.warningCounts)
        assertEquals(1, result.details.counts.preservedCount)
    }

    /**
     * Spec 451 N-6: a duplicate warning can only exist for an item with a
     * `Preserved` disposition, so the projector's join always succeeds and a
     * missing disposition stays a typed failure (join contract unchanged).
     */
    @Test
    fun duplicateWarningWithoutADispositionFailsClosed() {
        val source = CanonicalFixtures.appItem(itemId = "photos.2", cell = GridCell(0, 0))
        val validated = plan(
            sourceItems = listOf(source),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("photos.2")), source)),
        )
        val planned = Planned(
            placements = emptyList(),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(
                Warning(WarningCode.DUPLICATE_LAUNCH_TARGET, listOf(DiagnosticParam.ItemParam(ItemId("photos.2")))),
            ),
        )

        assertEquals(PlanPreviewProjector.Result.Invalid, PlanPreviewProjector.project(validated, planned))
    }

    /**
     * Spec 451 Accessibility/localization: the new warning and preserve
     * reason wordings resolve in both en and ja, and the summary strings keep
     * their count placeholder across locales.
     */
    @Test
    fun duplicateWordingResourcesExistInEnAndJa() {
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (name in listOf(
                "manual_organization_preview_warning_duplicate_launch_target_item",
                "manual_organization_preview_preserved_reason_duplicate_launch_target",
                "manual_organization_warning_duplicate_launch_target",
                "manual_organization_preserved_duplicate_launch_target",
            )) {
                assertTrue("$name must exist in $localeDir", xml.contains("name=\"$name\""))
            }
            for (name in listOf(
                "manual_organization_warning_duplicate_launch_target",
                "manual_organization_preserved_duplicate_launch_target",
            )) {
                val value = xml
                    .substringAfter("name=\"$name\"")
                    .substringBefore("</string>")
                assertTrue("$localeDir $name must keep the count placeholder", value.contains("%1\$d"))
            }
        }
    }

    private fun preserved(id: String, reason: PreserveReason): PlannedPlacement = PlannedPlacement(
        item = ItemId(id),
        disposition = Disposition.Preserved(reason),
        target = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(1, 0), GridSpan(1, 1)),
    )

    private fun plan(
        sourceItems: List<CanonicalItemState>,
        actions: List<ApplyAction>,
    ): ValidatedLayoutPlan {
        val sourceState = LayoutState(
            pages = listOf(PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0))),
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 6, rows = 6),
            items = sourceItems,
        )
        return ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = actions,
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )
    }

    private fun lawnchairStringsXml(localeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }
}
