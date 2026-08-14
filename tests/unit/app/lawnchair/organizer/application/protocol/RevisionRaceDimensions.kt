package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetOptions
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.application.public.WidgetSource
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey

/**
 * Canonical dimension matrix for AC-1 revision race tests. Every dimension
 * represents a single revision-significant mutation that must force exact
 * stale/precondition rejection through the public seam with zero committed
 * writes. Shared between [ApplyProtocolTest] (A5 in-transaction) and
 * [RecoveryProtocolTest] (A2-equivalent reviewed-current check).
 *
 * Each [Dimension] supplies both the [source] state (the plan's baseline) and
 * the [mutated] state (the raced/stale state the seam sees at re-read or
 * capture time).
 *
 * Issue #14 Stage B step 4 — AC-1.
 */
object RevisionRaceDimensions {

    data class Dimension(
        val name: String,
        val mutated: LayoutState,
        val source: LayoutState,
    )

    // ── Base states ────────────────────────────────────────────────

    private val p0 = ApplicationPageRef.PersistentPage(PageId("p0"))
    private val p1 = ApplicationPageRef.PersistentPage(PageId("p1"))

    private val DEFAULT_STATE: LayoutState = CanonicalFixtures.state(
        items = listOf(CanonicalFixtures.appItem()),
    )

    private val DEFAULT_WIDGET_STATE: LayoutState = CanonicalFixtures.state(
        items = listOf(CanonicalFixtures.widgetItem()),
    )

    private val TWO_ITEM_STATE: LayoutState = CanonicalFixtures.state(
        items = listOf(
            CanonicalFixtures.appItem(),
            CanonicalFixtures.appItem(
                itemId = "app.b",
                cell = GridCell(1, 0),
                target = TargetKey.AppKey(
                    component = ComponentKey("com.example.b/.Main"),
                    profile = ProfileId("personal"),
                ),
            ),
        ),
    )

    private val DOCK_STATE: LayoutState = CanonicalFixtures.state(
        items = listOf(
            CanonicalItemState(
                ref = ApplicationItemRef.PersistentItem(ItemId("app.a")),
                kind = CanonicalItemKind.Application,
                targetKey = TargetKey.AppKey(
                    component = ComponentKey("com.example.a/.Main"),
                    profile = ProfileId("personal"),
                ),
                profile = ProfileId("personal"),
                profileAvailability = ProfileAvailability.AVAILABLE,
                itemAvailability = ItemAvailability.AVAILABLE,
                placement = PlacementState.Dock(rank = 0),
                title = OptionalText.Present("A"),
                intent = OptionalText.Present("#Intent;"),
                icon = OptionalBytes.Absent,
                widget = WidgetState.NoWidget,
                modified = app.lawnchair.organizer.application.public.ModifiedAtMillis(1_000L),
                lockState = app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED,
                structure = StructureState.Plain,
            ),
        ),
    )

    // ── All dimensions ─────────────────────────────────────────────

    val all: List<Dimension> = listOf(
        Dimension(
            name = "cell changed",
            mutated = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 0)))),
            source = DEFAULT_STATE,
        ),
        // ── page/order ─────────────────────────────────────────
        Dimension(
            name = "page added",
            mutated = CanonicalFixtures.state(
                pages = listOf(CanonicalFixtures.page(p0, 0), CanonicalFixtures.page(p1, 1)),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "page order changed",
            mutated = CanonicalFixtures.state(
                pages = listOf(CanonicalFixtures.page(p0, 1)),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "page removed",
            mutated = CanonicalFixtures.state(
                pages = emptyList(),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = CanonicalFixtures.state(
                pages = listOf(CanonicalFixtures.page(p0, 0), CanonicalFixtures.page(p1, 1)),
                items = listOf(CanonicalFixtures.appItem()),
            ),
        ),
        // ── span ───────────────────────────────────────────────
        Dimension(
            name = "span changed",
            mutated = CanonicalFixtures.state(
                items = listOf(CanonicalFixtures.appItem(span = GridSpan(2, 2))),
            ),
            source = DEFAULT_STATE,
        ),
        // ── rank ───────────────────────────────────────────────
        Dimension(
            name = "dock rank changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    dockItem(rank = 1),
                ),
            ),
            source = DOCK_STATE,
        ),
        // ── container ──────────────────────────────────────────
        Dimension(
            name = "container changed to dock",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    dockItem(rank = 0),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "container changed to folder child",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    folderChildItem(),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        // ── target identity ────────────────────────────────────
        Dimension(
            name = "target component changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        target = TargetKey.AppKey(
                            component = ComponentKey("com.other/.Other"),
                            profile = ProfileId("personal"),
                        ),
                    ),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "target profile changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        target = TargetKey.AppKey(
                            component = ComponentKey("com.example.a/.Main"),
                            profile = ProfileId("work"),
                        ),
                    ),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "item profile changed",
            mutated = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(profile = "work"))),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "item profile availability changed",
            mutated = CanonicalFixtures.state(
                items = listOf(CanonicalFixtures.appItem().copy(profileAvailability = ProfileAvailability.UNAVAILABLE)),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "profile inventory added",
            mutated = CanonicalFixtures.state(
                profiles = listOf(
                    CanonicalFixtures.profile("personal"),
                    CanonicalFixtures.profile("work"),
                ),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "profile inventory removed",
            mutated = DEFAULT_STATE,
            source = CanonicalFixtures.state(
                profiles = listOf(
                    CanonicalFixtures.profile("personal"),
                    CanonicalFixtures.profile("work"),
                ),
                items = listOf(CanonicalFixtures.appItem()),
            ),
        ),
        Dimension(
            name = "profile inventory availability changed",
            mutated = CanonicalFixtures.state(
                profiles = listOf(CanonicalFixtures.profile("personal", ProfileAvailability.UNAVAILABLE)),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "device capabilities changed",
            mutated = CanonicalFixtures.state(
                device = CanonicalFixtures.deviceCapabilities(columns = 5),
                items = listOf(CanonicalFixtures.appItem()),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "lock changed",
            mutated = CanonicalFixtures.state(
                items = listOf(CanonicalFixtures.appItem(lockState = OrganizerLockState.LOCKED)),
            ),
            source = DEFAULT_STATE,
        ),
        // ── item availability ──────────────────────────────────
        Dimension(
            name = "item availability changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(availability = ItemAvailability.DISABLED),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        // ── widget provider/id/options/source ──────────────────
        Dimension(
            name = "widget provider changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.widgetItem(provider = "com.other/.OtherWidget"),
                ),
            ),
            source = DEFAULT_WIDGET_STATE,
        ),
        Dimension(
            name = "widget appWidgetId changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.widgetItem(appWidgetId = 6),
                ),
            ),
            source = DEFAULT_WIDGET_STATE,
        ),
        Dimension(
            name = "widget options changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    widgetItemWithOptions(1),
                ),
            ),
            source = DEFAULT_WIDGET_STATE,
        ),
        Dimension(
            name = "widget restored changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.widgetItem().copy(
                        widget = (CanonicalFixtures.widgetItem().widget as WidgetState.Widget).copy(
                            restored = WidgetRestoreState(2),
                        ),
                    ),
                ),
            ),
            source = DEFAULT_WIDGET_STATE,
        ),
        Dimension(
            name = "widget source changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    widgetItemWithSource(1),
                ),
            ),
            source = DEFAULT_WIDGET_STATE,
        ),
        // ── title/intent/icon/modified ─────────────────────────
        Dimension(
            name = "title changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(title = OptionalText.Present("B")),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "intent changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(intent = OptionalText.Present("#Intent;B")),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "icon changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        icon = CanonicalFixtures.immutableIcon(byteArrayOf(1)),
                    ),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "modified changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(modified = 2_000L),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "folder membership changed",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        kind = CanonicalItemKind.Folder,
                        structure = StructureState.FolderMembers(
                            listOf(RankedMember(ApplicationItemRef.PersistentItem(ItemId("app.b")), 1)),
                        ),
                    ),
                ),
            ),
            source = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        kind = CanonicalItemKind.Folder,
                        structure = StructureState.FolderMembers(
                            listOf(RankedMember(ApplicationItemRef.PersistentItem(ItemId("app.b")), 0)),
                        ),
                    ),
                ),
            ),
        ),
        Dimension(
            name = "app pair structure changed",
            mutated = appPairState(SplitStage.BOTTOM_OR_RIGHT, SplitStage.TOP_OR_LEFT),
            source = appPairState(SplitStage.TOP_OR_LEFT, SplitStage.BOTTOM_OR_RIGHT),
        ),
        // ── item addition/removal ──────────────────────────────
        Dimension(
            name = "item added",
            mutated = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(),
                    CanonicalFixtures.appItem(
                        itemId = "app.c",
                        cell = GridCell(2, 0),
                        target = TargetKey.AppKey(
                            component = ComponentKey("com.example.c/.Main"),
                            profile = ProfileId("personal"),
                        ),
                    ),
                ),
            ),
            source = DEFAULT_STATE,
        ),
        Dimension(
            name = "item removed",
            mutated = DEFAULT_STATE,
            source = TWO_ITEM_STATE,
        ),
    )

    // ── Helpers ─────────────────────────────────────────────────

    private fun dockItem(rank: Int): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId("app.a")),
        kind = CanonicalItemKind.Application,
        targetKey = TargetKey.AppKey(
            component = ComponentKey("com.example.a/.Main"),
            profile = ProfileId("personal"),
        ),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Dock(rank = rank),
        title = OptionalText.Present("A"),
        intent = OptionalText.Present("#Intent;"),
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = app.lawnchair.organizer.application.public.ModifiedAtMillis(1_000L),
        lockState = app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED,
        structure = StructureState.Plain,
    )

    private fun folderChildItem(): CanonicalItemState {
        val parentRef = ApplicationItemRef.PersistentItem(ItemId("folder.1"))
        return CanonicalItemState(
            ref = ApplicationItemRef.PersistentItem(ItemId("app.a")),
            kind = CanonicalItemKind.Application,
            targetKey = TargetKey.AppKey(
                component = ComponentKey("com.example.a/.Main"),
                profile = ProfileId("personal"),
            ),
            profile = ProfileId("personal"),
            profileAvailability = ProfileAvailability.AVAILABLE,
            itemAvailability = ItemAvailability.AVAILABLE,
            placement = PlacementState.FolderChild(parent = parentRef, rank = 0),
            title = OptionalText.Present("A"),
            intent = OptionalText.Present("#Intent;"),
            icon = OptionalBytes.Absent,
            widget = WidgetState.NoWidget,
            modified = app.lawnchair.organizer.application.public.ModifiedAtMillis(1_000L),
            lockState = app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED,
            structure = StructureState.Plain,
        )
    }

    private fun widgetItemWithOptions(options: Int): CanonicalItemState {
        val base = CanonicalFixtures.widgetItem()
        return base.copy(
            widget = WidgetState.Widget(
                provider = ComponentKey("com.example.widget/.Widget"),
                appWidgetId = app.lawnchair.organizer.planning.AppWidgetId(5),
                restored = app.lawnchair.organizer.application.public.WidgetRestoreState(1),
                options = WidgetOptions(options),
                source = WidgetSource(0),
            ),
        )
    }

    private fun widgetItemWithSource(source: Int): CanonicalItemState {
        val base = CanonicalFixtures.widgetItem()
        return base.copy(
            widget = WidgetState.Widget(
                provider = ComponentKey("com.example.widget/.Widget"),
                appWidgetId = app.lawnchair.organizer.planning.AppWidgetId(5),
                restored = app.lawnchair.organizer.application.public.WidgetRestoreState(1),
                options = WidgetOptions(0),
                source = WidgetSource(source),
            ),
        )
    }

    private fun appPairState(firstStage: SplitStage, secondStage: SplitStage): LayoutState = CanonicalFixtures.state(
        items = listOf(
            CanonicalFixtures.appItem(
                itemId = "pair.a",
                kind = CanonicalItemKind.AppPair,
                structure = CanonicalFixtures.appPairStructure(
                    ApplicationItemRef.PersistentItem(ItemId("child.a")),
                    ApplicationItemRef.PersistentItem(ItemId("child.b")),
                    firstStage,
                    secondStage,
                ),
            ),
        ),
    )
}
