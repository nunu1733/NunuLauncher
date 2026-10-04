package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * AC-1 (revision subset) — proves the revision changes for every dimension
 * listed in spec.md §“Revision semantics”, and is stable across
 * locale/timezone/scheduling.
 *
 * Issue #14 Stage B step 1.
 */
class RevisionCalculatorTest {

    @Test
    fun revisionIsStableAcrossLocaleTimezoneAndThread() {
        val state = CanonicalFixtures.state(
            items = listOf(CanonicalFixtures.appItem()),
        )
        val baseline = runInLocale("en-US", "America/New_York") {
            RevisionCalculator.revisionOf(state)
        }
        val localeFr = runInLocale("fr-FR", "Europe/Paris") {
            RevisionCalculator.revisionOf(state)
        }
        val localeJa = runInLocale("ja-JP", "Asia/Tokyo") {
            RevisionCalculator.revisionOf(state)
        }
        assertEquals(baseline, localeFr)
        assertEquals(baseline, localeJa)
    }

    @Test
    fun revisionChangesWhenItemPlacementMoves() {
        val itemA = CanonicalFixtures.appItem(cell = GridCell(0, 0))
        val itemB = CanonicalFixtures.appItem(cell = GridCell(1, 1))
        val s0 = CanonicalFixtures.state(items = listOf(itemA))
        val s1 = CanonicalFixtures.state(items = listOf(itemB))
        assertNotEquals(
            "moving cellX/cellY must change the revision",
            RevisionCalculator.revisionOf(s0),
            RevisionCalculator.revisionOf(s1),
        )
    }

    @Test
    fun revisionChangesWhenSpanChanges() {
        val s0 = CanonicalFixtures.state(
            items = listOf(CanonicalFixtures.appItem(span = GridSpan(1, 1))),
        )
        val s1 = CanonicalFixtures.state(
            items = listOf(CanonicalFixtures.appItem(span = GridSpan(2, 2))),
        )
        assertNotEquals(RevisionCalculator.revisionOf(s0), RevisionCalculator.revisionOf(s1))
    }

    @Test
    fun revisionChangesWhenPageOrderChanges() {
        val page0 = CanonicalFixtures.page(ApplicationPageRef.PersistentPage(PageId("p0")), 0)
        val page1 = CanonicalFixtures.page(ApplicationPageRef.PersistentPage(PageId("p0")), 1)
        val s0 = CanonicalFixtures.state(pages = listOf(page0))
        val s1 = CanonicalFixtures.state(pages = listOf(page1))
        assertNotEquals(RevisionCalculator.revisionOf(s0), RevisionCalculator.revisionOf(s1))
    }

    @Test
    fun revisionChangesWhenWorkspaceReservationChanges() {
        val base = CanonicalFixtures.state()
        val qsbEnabled = base.copy(
            reservedWorkspaceRegions = listOf(
                ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1)),
            ),
        )
        val qsbSpanChanged = base.copy(
            reservedWorkspaceRegions = listOf(
                ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(3, 1)),
            ),
        )

        assertNotEquals(RevisionCalculator.revisionOf(base), RevisionCalculator.revisionOf(qsbEnabled))
        assertNotEquals(RevisionCalculator.revisionOf(qsbEnabled), RevisionCalculator.revisionOf(qsbSpanChanged))
    }

    @Test
    fun revisionChangesWhenProfileAddedEvenWithNoItems() {
        val s0 = CanonicalFixtures.state(profiles = listOf(CanonicalFixtures.profile("personal")))
        val s1 = CanonicalFixtures.state(
            profiles = listOf(
                CanonicalFixtures.profile("personal"),
                CanonicalFixtures.profile("work"),
            ),
        )
        assertNotEquals(
            "adding a profile with no items must change the revision",
            RevisionCalculator.revisionOf(s0),
            RevisionCalculator.revisionOf(s1),
        )
    }

    @Test
    fun revisionChangesWhenProfileAvailabilityChanges() {
        val s0 = CanonicalFixtures.state(
            profiles = listOf(
                CanonicalFixtures.profile("personal", ProfileAvailability.AVAILABLE),
            ),
        )
        val s1 = CanonicalFixtures.state(
            profiles = listOf(
                CanonicalFixtures.profile("personal", ProfileAvailability.UNAVAILABLE),
            ),
        )
        assertNotEquals(RevisionCalculator.revisionOf(s0), RevisionCalculator.revisionOf(s1))
    }

    @Test
    fun revisionChangesWhenTargetComponentChanges() {
        val itemA = CanonicalFixtures.appItem(
            target = TargetKey.AppKey(ComponentKey("com.example.a/.Main"), ProfileId("personal")),
        )
        val itemB = CanonicalFixtures.appItem(
            target = TargetKey.AppKey(ComponentKey("com.example.b/.Main"), ProfileId("personal")),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(itemA))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(itemB))),
        )
    }

    @Test
    fun revisionChangesWhenWidgetProviderOrIdChanges() {
        val wA = CanonicalFixtures.widgetItem(provider = "com.example.w1/.W", appWidgetId = 1)
        val wB = CanonicalFixtures.widgetItem(provider = "com.example.w2/.W", appWidgetId = 1)
        val wC = CanonicalFixtures.widgetItem(provider = "com.example.w1/.W", appWidgetId = 2)
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(wA))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(wB))),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(wA))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(wC))),
        )
    }

    @Test
    fun revisionChangesWhenWidgetRestoredOptionsOrSourceChanges() {
        val base = CanonicalFixtures.widgetItem(restored = 1, options = 0, source = 0)
        val restoredChanged = CanonicalFixtures.widgetItem(restored = 2, options = 0, source = 0)
        val optionsChanged = CanonicalFixtures.widgetItem(restored = 1, options = 7, source = 0)
        val sourceChanged = CanonicalFixtures.widgetItem(restored = 1, options = 0, source = 4)
        val baseState = CanonicalFixtures.state(items = listOf(base))
        assertNotEquals(
            RevisionCalculator.revisionOf(baseState),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(restoredChanged))),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(baseState),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(optionsChanged))),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(baseState),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(sourceChanged))),
        )
    }

    @Test
    fun revisionChangesWhenLockStateChanges() {
        val unlocked = CanonicalFixtures.appItem(lockState = OrganizerLockState.UNLOCKED)
        val locked = CanonicalFixtures.appItem(lockState = OrganizerLockState.LOCKED)
        val unknown = CanonicalFixtures.appItem(lockState = OrganizerLockState.UNKNOWN)
        val baseRev = RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(unlocked)))
        assertNotEquals(
            baseRev,
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(locked))),
        )
        assertNotEquals(
            baseRev,
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(unknown))),
        )
    }

    @Test
    fun revisionChangesWhenFolderMembershipChanges() {
        val firstMember = CanonicalItemMembers.workspaceAppItem("a")
        val folderParent = CanonicalFixtures.appItem(
            itemId = "folder.0",
            target = TargetKey.FolderKey(app.lawnchair.organizer.planning.FolderId("f0")),
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(
                members = listOf(
                    app.lawnchair.organizer.application.public.RankedMember(
                        ApplicationItemRef.PersistentItem(ItemId("a")),
                        rank = 0,
                    ),
                ),
            ),
        )
        val s0 = CanonicalFixtures.state(items = listOf(firstMember, folderParent))
        val folderNoMembers = CanonicalFixtures.appItem(
            itemId = "folder.0",
            target = TargetKey.FolderKey(app.lawnchair.organizer.planning.FolderId("f0")),
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
        )
        val s1 = CanonicalFixtures.state(items = listOf(firstMember, folderNoMembers))
        assertNotEquals(
            "folder membership change must change the revision",
            RevisionCalculator.revisionOf(s0),
            RevisionCalculator.revisionOf(s1),
        )
    }

    @Test
    fun revisionChangesWhenAppPairMembershipChanges() {
        val first = ApplicationItemRef.PersistentItem(ItemId("a"))
        val second = ApplicationItemRef.PersistentItem(ItemId("b"))
        val pairParent = CanonicalFixtures.appItem(
            itemId = "pair.0",
            target = TargetKey.AppPairKey(app.lawnchair.organizer.planning.AppPairId("ap0")),
            kind = CanonicalItemKind.AppPair,
            structure = CanonicalFixtures.appPairStructure(
                first = first,
                second = second,
                firstStage = SplitStage.TOP_OR_LEFT,
                secondStage = SplitStage.BOTTOM_OR_RIGHT,
                snapPosition = OptionalSnapPosition.Absent,
            ),
        )
        val swappedStages = CanonicalFixtures.appItem(
            itemId = "pair.0",
            target = TargetKey.AppPairKey(app.lawnchair.organizer.planning.AppPairId("ap0")),
            kind = CanonicalItemKind.AppPair,
            structure = CanonicalFixtures.appPairStructure(
                first = first,
                second = second,
                firstStage = SplitStage.BOTTOM_OR_RIGHT,
                secondStage = SplitStage.TOP_OR_LEFT,
                snapPosition = OptionalSnapPosition.Absent,
            ),
        )
        val withSnap = CanonicalFixtures.appItem(
            itemId = "pair.0",
            target = TargetKey.AppPairKey(app.lawnchair.organizer.planning.AppPairId("ap0")),
            kind = CanonicalItemKind.AppPair,
            structure = CanonicalFixtures.appPairStructure(
                first = first,
                second = second,
                firstStage = SplitStage.TOP_OR_LEFT,
                secondStage = SplitStage.BOTTOM_OR_RIGHT,
                snapPosition = OptionalSnapPosition.Present(SnapPositionToken("snap-1")),
            ),
        )
        val base = RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(pairParent)))
        assertNotEquals(
            base,
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(swappedStages))),
        )
        assertNotEquals(
            base,
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(withSnap))),
        )
    }

    @Test
    fun revisionChangesWhenDeviceCapabilitiesChange() {
        val s0 = CanonicalFixtures.state(device = CanonicalFixtures.deviceCapabilities(columns = 4))
        val s1 = CanonicalFixtures.state(device = CanonicalFixtures.deviceCapabilities(columns = 5))
        assertNotEquals(RevisionCalculator.revisionOf(s0), RevisionCalculator.revisionOf(s1))
    }

    @Test
    fun revisionChangesWhenItemAvailabilityChanges() {
        val available = CanonicalFixtures.appItem(availability = ItemAvailability.AVAILABLE)
        val quiet = CanonicalFixtures.appItem(availability = ItemAvailability.QUIET)
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(available))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(quiet))),
        )
    }

    @Test
    fun revisionChangesWhenModifiedTimestampChanges() {
        val a = CanonicalFixtures.appItem(modified = 1_000L)
        val b = CanonicalFixtures.appItem(modified = 2_000L)
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(a))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(b))),
        )
    }

    @Test
    fun revisionChangesWhenIconBytesChange() {
        val withIcon = CanonicalFixtures.appItem(
            icon = CanonicalFixtures.immutableIcon(byteArrayOf(1, 2, 3)),
        )
        val withoutIcon = CanonicalFixtures.appItem()
        val differentIcon = CanonicalFixtures.appItem(
            icon = CanonicalFixtures.immutableIcon(byteArrayOf(4, 5, 6)),
        )
        val base = RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(withoutIcon)))
        assertNotEquals(
            base,
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(withIcon))),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(withIcon))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(differentIcon))),
        )
    }

    @Test
    fun revisionChangesWhenUnsupportedContainerCodeChanges() {
        val item = CanonicalFixtures.appItem(
            itemId = "unsupported.1",
            kind = CanonicalItemKind.Unknown(app.lawnchair.organizer.planning.KindCode(7)),
            target = TargetKey.AppKey(ComponentKey("com.example.x/.Main"), ProfileId("personal")),
        ).copy(
            placement = app.lawnchair.organizer.application.public.PlacementState.UnsupportedContainer(
                ContainerCode(99),
            ),
        )
        val different = item.copy(
            placement = app.lawnchair.organizer.application.public.PlacementState.UnsupportedContainer(
                ContainerCode(100),
            ),
        )
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(item))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(different))),
        )
    }

    @Test
    fun revisionChangesWhenTargetKeyAppWidgetIdChanges() {
        val w = CanonicalFixtures.widgetItem(appWidgetId = 7)
        val wDiff = CanonicalFixtures.widgetItem(appWidgetId = 8)
        assertNotEquals(
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(w))),
            RevisionCalculator.revisionOf(CanonicalFixtures.state(items = listOf(wDiff))),
        )
    }

    @Test
    fun intendedStateDigestIsDistinctFromPreStateDigestForSameShape() {
        val state = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem()))
        val pre = RevisionCalculator.revisionOf(state)
        val intended = RevisionCalculator.intendedRevisionOf(state)
        assertNotEquals(
            "Pre-state and intended-post-state digests must use different domain tags",
            pre.value,
            intended.value,
        )
    }

    @Test
    fun actionSetDigestIsStableAndOrderSensitive() {
        val preserve = listOf(
            app.lawnchair.organizer.application.public.ApplyAction.Preserve(
                ref = ApplicationItemRef.PersistentItem(ItemId("a")),
                expected = CanonicalFixtures.appItem(),
            ),
        )
        val d0 = RevisionCalculator.actionSetDigestOf(preserve)
        val d1 = RevisionCalculator.actionSetDigestOf(preserve)
        assertEquals("same actions must produce same digest", d0.toList(), d1.toList())
    }

    private fun <T> runInLocale(language: String, timezone: String, block: () -> T): T {
        val prevLocale = Locale.getDefault()
        val prevTz = TimeZone.getDefault()
        return try {
            Locale.setDefault(Locale.forLanguageTag(language))
            TimeZone.setDefault(TimeZone.getTimeZone(timezone))
            block()
        } finally {
            Locale.setDefault(prevLocale)
            TimeZone.setDefault(prevTz)
        }
    }

    private object CanonicalItemMembers {
        fun workspaceAppItem(id: String): app.lawnchair.organizer.application.public.CanonicalItemState = CanonicalFixtures.appItem(
            itemId = id,
            cell = GridCell(0, 0),
            span = GridSpan(1, 1),
            target = TargetKey.AppKey(
                component = ComponentKey("com.example.$id/.Main"),
                profile = ProfileId("personal"),
            ),
        )
    }
}

@Suppress("unused")
private fun AppWidgetId.unused(): Int = value
