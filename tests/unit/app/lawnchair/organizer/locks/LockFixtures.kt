package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalSnapPosition
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
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey

/**
 * Synthetic lock-authoring fixtures covering every actionable kind and
 * placement. Never copy production layouts into tests.
 *
 * Issue #38.
 */
object LockFixtures {

    val CAPABILITIES = DeviceCapabilities(
        columns = 4,
        rows = 5,
        hotseatSlots = 4,
        folderMaxColumns = 4,
        folderMaxRows = 4,
        orientation = DeviceOrientation.PORTRAIT,
    )

    fun revision(value: String = "r0") = RevisionId(value)

    fun capture(state: LayoutState, revision: String = "r0") = LockCapture(state, revision(revision))

    fun item(
        id: String,
        kind: CanonicalItemKind = CanonicalItemKind.Application,
        profile: String = "personal",
        placement: PlacementState = PlacementState.Workspace(
            page = ApplicationPageRef.PersistentPage(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(1, 1),
        ),
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        structure: StructureState = StructureState.Plain,
        title: String? = id,
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(id)),
        kind = kind,
        targetKey = TargetKey.AppKey(
            component = ComponentKey("com.example/$id"),
            profile = ProfileId(profile),
        ),
        profile = ProfileId(profile),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = placement,
        title = if (title != null) OptionalText.Present(title) else OptionalText.Absent,
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(1_000L),
        lockState = lockState,
        structure = structure,
    )

    fun folder(
        id: String = "201",
        children: List<CanonicalItemState>,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        profile: String = "personal",
    ): CanonicalItemState = item(
        id = id,
        kind = CanonicalItemKind.Folder,
        profile = profile,
        lockState = lockState,
        structure = StructureState.FolderMembers(
            members = children.mapIndexed { index, child -> RankedMember(child.ref, index) },
        ),
    )

    fun folderChild(
        id: String,
        parent: String,
        rank: Int,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        profile: String = "personal",
    ): CanonicalItemState = item(
        id = id,
        profile = profile,
        lockState = lockState,
        placement = PlacementState.FolderChild(
            parent = ApplicationItemRef.PersistentItem(ItemId(parent)),
            rank = rank,
        ),
    )

    fun appPair(
        id: String = "301",
        first: String,
        second: String,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        firstLock: OrganizerLockState = OrganizerLockState.UNLOCKED,
        secondLock: OrganizerLockState = OrganizerLockState.UNLOCKED,
        profile: String = "personal",
    ): List<CanonicalItemState> {
        val parentRef = ApplicationItemRef.PersistentItem(ItemId(id))
        return listOf(
            item(
                id = id,
                kind = CanonicalItemKind.AppPair,
                profile = profile,
                lockState = lockState,
                structure = StructureState.AppPairMembers(
                    first = ApplicationItemRef.PersistentItem(ItemId(first)),
                    second = ApplicationItemRef.PersistentItem(ItemId(second)),
                    firstStage = SplitStage.TOP_OR_LEFT,
                    secondStage = SplitStage.BOTTOM_OR_RIGHT,
                    snapPosition = OptionalSnapPosition.Absent,
                ),
            ),
            item(
                id = first,
                profile = profile,
                lockState = firstLock,
                placement = PlacementState.AppPairChild(parent = parentRef, stage = SplitStage.TOP_OR_LEFT),
            ),
            item(
                id = second,
                profile = profile,
                lockState = secondLock,
                placement = PlacementState.AppPairChild(parent = parentRef, stage = SplitStage.BOTTOM_OR_RIGHT),
            ),
        )
    }

    fun dockItem(
        id: String,
        rank: Int = 0,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        profile: String = "personal",
    ): CanonicalItemState = item(
        id = id,
        profile = profile,
        lockState = lockState,
        placement = PlacementState.Dock(rank = rank),
    )

    fun widgetItem(
        id: String,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        profile: String = "personal",
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(id)),
        kind = CanonicalItemKind.AppWidget,
        targetKey = TargetKey.WidgetKey(
            provider = ComponentKey("com.example.widget/.$id"),
            appWidgetId = AppWidgetId(5),
            profile = ProfileId(profile),
        ),
        profile = ProfileId(profile),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(
            page = ApplicationPageRef.PersistentPage(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(2, 2),
        ),
        title = OptionalText.Absent,
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.Widget(
            provider = ComponentKey("com.example.widget/.$id"),
            appWidgetId = AppWidgetId(5),
            restored = WidgetRestoreState(1),
            options = WidgetOptions(0),
            source = WidgetSource(0),
        ),
        modified = ModifiedAtMillis(1_000L),
        lockState = lockState,
        structure = StructureState.Plain,
    )

    fun unknownKindItem(id: String, lockState: OrganizerLockState = OrganizerLockState.UNLOCKED): CanonicalItemState = item(
        id = id,
        kind = CanonicalItemKind.Unknown(KindCode(-1)),
        lockState = lockState,
    )

    fun unsupportedContainerItem(id: String): CanonicalItemState = item(
        id = id,
        placement = PlacementState.UnsupportedContainer(ContainerCode(-5)),
    )

    fun state(
        items: List<CanonicalItemState>,
        profiles: List<ProfileState> = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
        capabilities: DeviceCapabilities = CAPABILITIES,
    ): LayoutState {
        val pageIds = items.mapNotNull { itemState ->
            (itemState.placement as? PlacementState.Workspace)?.page as? ApplicationPageRef.PersistentPage
        }.map { it.pageId }.distinct().sortedBy { it.value }
        val pages = pageIds.mapIndexed { index, id ->
            PageState(ApplicationPageRef.PersistentPage(id), PageOrder(index))
        }.ifEmpty {
            listOf(PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)))
        }
        return LayoutState(pages, profiles, capabilities, items)
    }
}
