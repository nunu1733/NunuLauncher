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

    @Test
    fun representedIdentitiesExtractAppsFromEveryContainerKindAndDuplicates() {
        // Spec AC-1: the snapshot-side extraction — a workspace icon, a dock
        // item, a folder member, an app-pair member, and a duplicate row all
        // count as "represented"; folder containers never do.
        val profile = ProfileId("personal")

        fun appRow(id: String, component: String, placement: app.lawnchair.organizer.application.public.PlacementState) = app.lawnchair.organizer.application.public.CanonicalItemState(
            ref = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                app.lawnchair.organizer.planning.ItemId(id),
            ),
            kind = app.lawnchair.organizer.application.public.CanonicalItemKind.Application,
            targetKey = app.lawnchair.organizer.planning.TargetKey.AppKey(ComponentKey(component), profile),
            profile = profile,
            profileAvailability = app.lawnchair.organizer.application.public.ProfileAvailability.AVAILABLE,
            itemAvailability = app.lawnchair.organizer.application.public.ItemAvailability.AVAILABLE,
            placement = placement,
            title = app.lawnchair.organizer.application.public.OptionalText.Present(id),
            intent = app.lawnchair.organizer.application.public.OptionalText.Present("#Intent;"),
            icon = app.lawnchair.organizer.application.public.OptionalBytes.Absent,
            widget = app.lawnchair.organizer.application.public.WidgetState.NoWidget,
            modified = app.lawnchair.organizer.application.public.ModifiedAtMillis(1_000L),
            lockState = app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED,
            structure = app.lawnchair.organizer.application.public.StructureState.Plain,
        )

        val workspacePage = app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
            app.lawnchair.organizer.planning.PageId("p0"),
        )
        val folderRef = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
            app.lawnchair.organizer.planning.ItemId("folder"),
        )
        val pairRef = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
            app.lawnchair.organizer.planning.ItemId("pair"),
        )
        val state = app.lawnchair.organizer.application.canonical.CanonicalFixtures.state(
            items = listOf(
                appRow(
                    "ws",
                    "com.example.ws/.Main",
                    app.lawnchair.organizer.application.public.PlacementState.Workspace(
                        workspacePage,
                        app.lawnchair.organizer.planning.GridCell(0, 0),
                        app.lawnchair.organizer.planning.GridSpan(1, 1),
                    ),
                ),
                // Same app represented a second time — still one identity.
                appRow(
                    "ws-duplicate",
                    "com.example.ws/.Main",
                    app.lawnchair.organizer.application.public.PlacementState.Workspace(
                        workspacePage,
                        app.lawnchair.organizer.planning.GridCell(1, 0),
                        app.lawnchair.organizer.planning.GridSpan(1, 1),
                    ),
                ),
                appRow("dock", "com.example.dock/.Main", app.lawnchair.organizer.application.public.PlacementState.Dock(0)),
                appRow("folder-member", "com.example.folder.app/.Main", app.lawnchair.organizer.application.public.PlacementState.FolderChild(folderRef, 0)),
                appRow(
                    "pair-member",
                    "com.example.pair.app/.Main",
                    app.lawnchair.organizer.application.public.PlacementState.AppPairChild(
                        pairRef,
                        app.lawnchair.organizer.planning.SplitStage.TOP_OR_LEFT,
                    ),
                ),
                // The folder container itself: FolderKey, never an app identity.
                app.lawnchair.organizer.application.canonical.CanonicalFixtures.appItem(
                    itemId = "folder",
                    kind = app.lawnchair.organizer.application.public.CanonicalItemKind.Folder,
                ).copy(
                    targetKey = app.lawnchair.organizer.planning.TargetKey.FolderKey(
                        app.lawnchair.organizer.planning.FolderId("folder"),
                    ),
                    structure = app.lawnchair.organizer.application.public.StructureState.FolderMembers(
                        listOf(
                            app.lawnchair.organizer.application.public.RankedMember(
                                app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                                    app.lawnchair.organizer.planning.ItemId("folder-member"),
                                ),
                                0,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val snapshot = app.lawnchair.organizer.application.adapter.FakeLayoutWriter(state)
            .captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("represented-test"))

        val represented = MissingAppDetection.representedIdentities(snapshot)

        assertEquals(
            setOf(
                key("com.example.ws/.Main", "personal"),
                key("com.example.dock/.Main", "personal"),
                key("com.example.folder.app/.Main", "personal"),
                key("com.example.pair.app/.Main", "personal"),
            ),
            represented,
        )
    }
}
