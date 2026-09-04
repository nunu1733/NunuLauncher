package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.planning.FolderNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #201 materializer contract: the resolved generated-folder title is
 * frozen into the plan exactly once per planned folder (creation-time locale
 * snapshot), preview and apply read that same value, blank resolutions fail
 * closed, and non-insert actions never touch titles.
 */
class GeneratedFolderNamingMaterializerTest {

    @Test
    fun resolvesEachPlannedFolderExactlyOnceAndFreezesTitleIntoInsert() {
        val fixture = NewFolderPlanFixtures.multiFolderWithBoundaryIds()
        val resolver = RecordingFolderTitleResolver()
        val titlesByName = mutableMapOf<FolderNaming, String>()

        val plan = NewFolderPlanFixtures.materializeReady(fixture, resolver)

        assertEquals(
            "resolve must be called exactly once per planned folder",
            fixture.result.outcome.let { (it as app.lawnchair.organizer.planning.Planned).newFolders.size },
            resolver.resolved.size,
        )
        val inserts = plan.actions.filterIsInstance<ApplyAction.Insert>()
        assertEquals(plan.newFolders.size, inserts.size)
        for (folder in plan.newFolders) {
            val naming = folder.naming as FolderNaming.FromCategory
            val insert = inserts.single {
                (it.ref as? app.lawnchair.organizer.application.public.ApplicationItemRef.PlannedFolder)?.ordinal == folder.ordinal
            }
            val title = insert.intended.title as OptionalText.Present
            assertTrue(title.value.isNotBlank())
            assertEquals(
                "intended title must be the resolver's value for this naming",
                titlesByName.getOrPut(naming) { title.value },
                title.value,
            )
        }
    }

    @Test
    fun blankResolutionFailsClosedInsteadOfFallback() {
        val fixture = NewFolderPlanFixtures.multiFolderWithBoundaryIds()
        val blankResolver = FolderTitleResolver { "   " }

        val materialized = OrganizationPlanMaterializer.materialize(
            fixture.input,
            fixture.result,
            fixture.sourceState,
            blankResolver,
        )

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun preserveAndUpdateActionsNeverAlterTitles() {
        val fixture = NewFolderPlanFixtures.multiFolderWithBoundaryIds()
        val plan = NewFolderPlanFixtures.materializeReady(fixture, RecordingFolderTitleResolver())

        val updates = plan.actions.filterIsInstance<ApplyAction.Update>()
        val preserves = plan.actions.filterIsInstance<ApplyAction.Preserve>()
        assertTrue(preserves.isNotEmpty())
        for (action in updates) {
            assertEquals(
                "non-insert actions must keep the captured title",
                action.expected.title,
                action.intended.title,
            )
        }
    }
}
