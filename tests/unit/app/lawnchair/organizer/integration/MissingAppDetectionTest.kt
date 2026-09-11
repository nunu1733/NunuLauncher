package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 spec §1 / AC-1: the pure missing-app set difference. Fixtures
 * cover folder-contained and duplicated representations, dock and app-pair
 * members, work-profile isolation, availability filtering, and determinism.
 */
class MissingAppDetectionTest {

    private fun app(component: String, profile: String, label: String = component, availability: Availability = Availability.AVAILABLE) = InstalledLaunchableApp(ComponentKey(component), ProfileId(profile), label, availability)

    private fun key(component: String, profile: String) = CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile))

    @Test
    fun inventoryMinusRepresentedYieldsCandidates() {
        val inventory = listOf(
            app("com.example.a/.Main", "0"),
            app("com.example.b/.Main", "0"),
            app("com.example.c/.Main", "0"),
        )
        val represented = setOf(key("com.example.b/.Main", "0"))

        val candidates = MissingAppDetection.detect(inventory, represented)

        assertEquals(
            listOf("com.example.a/.Main", "com.example.c/.Main"),
            candidates.map { it.target.component.value },
        )
    }

    @Test
    fun folderDockAndAppPairRepresentationsAllCount() {
        // The represented set is identity-based; container kinds never matter.
        val inventory = listOf(
            app("com.example.folder/.Main", "0"),
            app("com.example.dock/.Main", "0"),
            app("com.example.pair/.Main", "0"),
            app("com.example.fresh/.Main", "0"),
        )
        val represented = setOf(
            key("com.example.folder/.Main", "0"),
            key("com.example.dock/.Main", "0"),
            key("com.example.pair/.Main", "0"),
        )

        val candidates = MissingAppDetection.detect(inventory, represented)

        assertEquals(listOf("com.example.fresh/.Main"), candidates.map { it.target.component.value })
    }

    @Test
    fun duplicatePlacementsAndDuplicateInventoryRowsCollapse() {
        val inventory = listOf(
            app("com.example.dup/.Main", "0"),
            app("com.example.dup/.Main", "0"),
            app("com.example.fresh/.Main", "0"),
        )
        val represented = setOf(
            key("com.example.dup/.Main", "0"),
            key("com.example.dup/.Main", "0"),
        )

        val candidates = MissingAppDetection.detect(inventory, represented)

        assertEquals(listOf("com.example.fresh/.Main"), candidates.map { it.target.component.value })
    }

    @Test
    fun workProfileIdentityStaysSeparated() {
        val inventory = listOf(
            app("com.example.same/.Main", "0"),
            app("com.example.same/.Main", "10"),
        )
        val represented = setOf(key("com.example.same/.Main", "10"))

        val candidates = MissingAppDetection.detect(inventory, represented)

        assertEquals(
            listOf(CandidateTarget.AppKey(ComponentKey("com.example.same/.Main"), ProfileId("0"))),
            candidates.map { it.target },
        )
    }

    @Test
    fun nonAvailableInventoryEntriesAreExcluded() {
        val inventory = listOf(
            app("com.example.ok/.Main", "0"),
            app("com.example.suspended/.Main", "0", availability = Availability.UNAVAILABLE),
            app("com.example.disabled/.Main", "0", availability = Availability.DISABLED),
        )

        val candidates = MissingAppDetection.detect(inventory, emptySet())

        assertEquals(listOf("com.example.ok/.Main"), candidates.map { it.target.component.value })
        assertTrue(candidates.all { it.availability == Availability.AVAILABLE })
    }

    @Test
    fun emptyWorkspaceMeansWholeAvailableInventoryIsCandidate() {
        val inventory = listOf(
            app("com.example.b/.Main", "0", label = "Bravo"),
            app("com.example.a/.Main", "0", label = "Alpha"),
        )

        val candidates = MissingAppDetection.detect(inventory, emptySet())

        // Deterministic display order: profile, label, component (D-3).
        assertEquals(listOf("Alpha", "Bravo"), candidates.map { it.label })
    }

    @Test
    fun orderIsDeterministicAcrossShuffledInput() {
        val inventory = listOf(
            app("com.example.z/.Main", "0", label = "Zulu"),
            app("com.example.a/.Main", "0", label = "Alpha"),
            app("com.example.m/.Main", "0", label = "Mike"),
        )
        val first = MissingAppDetection.detect(inventory, emptySet())
        val second = MissingAppDetection.detect(inventory.shuffled(java.util.Random(7)), emptySet())
        assertEquals(first, second)
    }

    @Test
    fun propertyCandidatesAreExactlyAvailableInventoryMinusRepresented() {
        val random = java.util.Random(228)
        repeat(64) {
            val inventory = (0 until 12).map {
                app("com.example.$it/.Main", (it % 3).toString(), label = "App$it")
            }
            val represented = inventory.filter { random.nextBoolean() }.map { key(it.component.value, it.profile.value) }.toSet()
            val available = inventory.filter { it.availability == Availability.AVAILABLE }
                .map { key(it.component.value, it.profile.value) }
                .toSet()

            val candidates = MissingAppDetection.detect(inventory, represented).map { it.target }.toSet()

            assertEquals(available - represented, candidates)
        }
    }
}
