package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.ContainerCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #152: unit contract of the model-verifiable projection itself.
 * Re-review P1: unsupported containers are represented explicitly — never
 * dropped — and legacy shortcut rows carry their faithful launch identity.
 */
class ModelProjectionTest {

    @Test
    fun unsupportedContainerIsRepresentedExplicitlyNotDropped() {
        val item = CanonicalFixtures.appItem(itemId = "odd.row")
            .copy(placement = PlacementState.UnsupportedContainer(ContainerCode(-103)))
        val projection = CanonicalFixtures.state(items = listOf(item)).projectedToModelVerifiable()

        val projected = projection.items.single()
        assertEquals(
            ModelPlacement.UnsupportedContainer(ContainerCode(-103)),
            projected.placement,
        )
    }

    @Test
    fun legacyShortcutCarriesLaunchIdentityFromTheProvidedProjector() {
        val legacy = CanonicalFixtures.appItem(
            itemId = "shortcut.legacy",
            kind = CanonicalItemKind.ShortcutLegacy,
            intent = OptionalText.Present("#Intent;action=X;end"),
        )
        val app = CanonicalFixtures.appItem()
        val projection = CanonicalFixtures.state(items = listOf(legacy, app))
            .projectedToModelVerifiable(legacyLaunchIdentityOf = { item ->
                (item.intent as? OptionalText.Present)?.value
            })

        val legacyProjected = projection.items.first { it.kind is CanonicalItemKind.ShortcutLegacy }
        assertEquals("#Intent;action=X;end", legacyProjected.legacyLaunchIdentity)
        assertNull("Non-legacy kinds carry no legacy identity", projection.items.first { it.kind is CanonicalItemKind.Application }.legacyLaunchIdentity)
    }

    @Test
    fun legacyIdentityMasksLoaderManagedFlags() {
        // The loader adds task-management flags to legacy shortcut intents at
        // load time; they are not organizer-owned launch semantics, so both
        // legs mask the FLG component.
        assertEquals(
            "#Intent;action=X;end",
            app.lawnchair.organizer.application.adapter.canonicalLegacyIntentUri(
                "#Intent;action=X;FLG=0x10000000;end",
            ),
        )
    }

    @Test
    fun projectedItemsAreOrderedCanonicallyRegardlessOfInputOrder() {
        val a = CanonicalFixtures.appItem(itemId = "app.a")
        val z = CanonicalFixtures.appItem(itemId = "app.z")
        val projection = CanonicalFixtures.state(items = listOf(z, a)).projectedToModelVerifiable()

        assertEquals(
            listOf("app.a", "app.z"),
            projection.items.map { (it.ref as ApplicationItemRef.PersistentItem).itemId.value },
        )
    }

    @Test
    fun plannedReferencesAreExcludedFromTheProjection() {
        val planned = CanonicalFixtures.appItem(
            itemId = "app.planned",
        ).copy(ref = ApplicationItemRef.PlannedCandidate(app.lawnchair.organizer.planning.ItemId("app.planned")))
        val projection = CanonicalFixtures.state(items = listOf(planned)).projectedToModelVerifiable()

        assertTrue(projection.items.isEmpty())
    }
}
