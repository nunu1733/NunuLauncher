package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 (Q1): the adapter projects a validated intent into pure
 * ordering/preference hints keyed by internal ItemId.
 */
class IntentPlannerAdapterTest {

    private val now = 1_000L

    private fun app(id: String, x: Int = 0) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(
            app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId("p0")),
            GridCell(x, 0),
            GridSpan(1, 1),
        ),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun validatedIntent(): ValidatedPersonalizedIntent {
        val snapshot = LayoutSnapshot(
            app.lawnchair.organizer.planning.RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(app.lawnchair.organizer.planning.Page(app.lawnchair.organizer.planning.PageId("p0"), app.lawnchair.organizer.planning.PageOrder(0))),
            listOf(app("a"), app("b", x = 1)),
        )
        val targets = TargetSet(snapshot.items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val refs = built.export.items.map { it.ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs[0], importance = Importance.HIGH, pageAffinity = 0),
                ItemIntent(ref = refs[1], preserve = true),
            ),
        )
        val validation = IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = now + 1,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        return (validation as IntentValidation.Validated).validated
    }

    @Test
    fun projectionResolvesRefsToInternalItemsWithPreferenceOnly() {
        val projection = IntentPlannerAdapter.project(validatedIntent())
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, projection.identity.schemaVersion)
        assertEquals(2, projection.itemPreferences.size)
        val byItem = projection.itemPreferences.associateBy { it.item.value }
        val high = byItem.getValue("a")
        val preserved = byItem.getValue("b")
        assertEquals(Importance.HIGH, high.importance)
        assertEquals(0, high.pageAffinity)
        assertTrue(preserved.preserve == true)
        // The projection carries ordering bias only; no coordinates or DB rows.
        projection.itemPreferences.forEach { preference ->
            assertTrue(preference.role == ExportItemRole.APP_OR_SHORTCUT)
        }
    }

    @Test
    fun projectionIsDeterministicInTheAcceptedIntent() {
        val first = IntentPlannerAdapter.project(validatedIntent())
        val second = IntentPlannerAdapter.project(validatedIntent())
        assertEquals(first, second)
    }
}
