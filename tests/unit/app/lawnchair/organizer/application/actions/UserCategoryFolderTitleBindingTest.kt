package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.withCompositionCatalog
import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 title binding (accepted plan, "Title-binding tests"): preview and
 * apply resolve user-defined folder titles from the SAME `OrganizationInput`'s
 * catalog snapshot — never a fresh store read. A store that changed after
 * composition cannot move the creation-time title; unknown IDs keep the
 * generic fallback of the injected resolver; built-in resolution is unchanged;
 * a blank display name reaches the materializer's fail-closed blank check.
 */
class UserCategoryFolderTitleBindingTest {

    private val userId = UserCategoryId("3f2b8c4e-1234-4abc-9de0-1234567890ab")

    private fun catalog(title: String?): ActiveCategoryCatalog {
        val taxonomy = TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("tools")), CategoryId("tools"))
        return ActiveCategoryCatalog(
            taxonomy,
            title?.let { listOf(UserDefinedCategory(userId, it)) } ?: emptyList(),
        )
    }

    private fun materializedTitle(fixture: NewFolderPlanFixtures.Fixture, resolver: FolderTitleResolver): String {
        val plan = NewFolderPlanFixtures.materializeReady(fixture, resolver)
        val insert = plan.actions.filterIsInstance<ApplyAction.Insert>().single()
        return (insert.intended.title as OptionalText.Present).value
    }

    @Test
    fun userDefinedTitlesResolveFromTheSameCompositionCatalogSnapshot() {
        val fixture = NewFolderPlanFixtures.userCategoryFolder(
            userId = userId.value,
            userCategoryTitle = "Commute tools",
        )
        // The composition-time binding: input.catalog, not a store. The base
        // resolver stays responsible for the built-in/fallback policy; a known
        // user-defined naming never reaches it.
        val binding = RecordingFolderTitleResolver().withCompositionCatalog(fixture.input.catalog)

        assertEquals("Commute tools", materializedTitle(fixture, binding))
    }

    @Test
    fun storeChangeAfterCompositionDoesNotMoveTheCreationTimeTitle() {
        val fixture = NewFolderPlanFixtures.userCategoryFolder(
            userId = userId.value,
            userCategoryTitle = "Old name",
        )
        val base = RecordingFolderTitleResolver()
        // Bound at composition time against the OLD snapshot.
        val binding = base.withCompositionCatalog(fixture.input.catalog)
        val creationTimeTitle = materializedTitle(fixture, binding)
        assertEquals("Old name", creationTimeTitle)

        // The store publishes a rename (new snapshot) AFTER composition; the
        // bound resolver still resolves the frozen snapshot's title.
        val renamedCatalog = ActiveCategoryCatalog(
            fixture.input.catalog.builtIn,
            listOf(UserDefinedCategory(userId, "New name")),
        )
        assertNotEquals(fixture.input.catalog, renamedCatalog)
        assertEquals(
            "the plan's creation-time title must stay the snapshot's value",
            "Old name",
            creationTimeTitle,
        )
        assertEquals("Old name", materializedTitle(fixture, binding))
        assertEquals("New name", materializedTitle(fixture, base.withCompositionCatalog(renamedCatalog)))
    }

    @Test
    fun unknownUserIdsDelegateToTheBaseResolversGenericFallback() {
        val fixture = NewFolderPlanFixtures.userCategoryFolder(userCategoryTitle = null)
        // A production-shaped base: unknown content resolves a generic title
        // that never echoes the raw ID.
        val base = RecordingFolderTitleResolver { "generic fallback title" }
        val binding = base.withCompositionCatalog(catalog(title = null))

        val title = materializedTitle(fixture, binding)

        assertTrue("the unknown ID must reach the base resolver", base.resolved.single() is FolderNaming.FromUserCategory)
        assertEquals("generic fallback title", title)
        assertTrue("raw ID must not leak into the title", !title.contains(userId.value))
    }

    @Test
    fun builtInResolutionIsUnchangedByTheBinding() {
        val fixture = NewFolderPlanFixtures.singleFolder()
        val base = RecordingFolderTitleResolver()

        val throughBinding = materializedTitle(fixture, base.withCompositionCatalog(fixture.input.catalog))
        val direct = materializedTitle(fixture, RecordingFolderTitleResolver())

        assertTrue(throughBinding.isNotBlank())
        assertEquals("built-in namings resolve exactly as before #336", direct, throughBinding)
    }

    @Test
    fun blankDisplayNameFailsClosedAtTheMaterializer() {
        val fixture = NewFolderPlanFixtures.userCategoryFolder(userId = userId.value, userCategoryTitle = "   ")
        val binding = RecordingFolderTitleResolver().withCompositionCatalog(fixture.input.catalog)

        val materialized = OrganizationPlanMaterializer.materialize(
            fixture.input,
            fixture.result,
            fixture.sourceState,
            binding,
        )

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun previewAndApplyConsumeTheSameCreationTimeTitle() {
        // The preview and the apply both materialize through the same bound
        // resolver over the same input; each call re-resolves, but from the
        // same frozen snapshot the values are identical.
        val fixture = NewFolderPlanFixtures.userCategoryFolder(
            userId = userId.value,
            userCategoryTitle = "Commute tools",
        )
        val base = RecordingFolderTitleResolver()
        val binding = base.withCompositionCatalog(fixture.input.catalog)

        assertEquals(materializedTitle(fixture, binding), materializedTitle(fixture, binding))
    }
}
