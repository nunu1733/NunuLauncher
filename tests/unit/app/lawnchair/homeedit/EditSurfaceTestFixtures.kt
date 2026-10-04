/*
 * Issue #449: shared synthetic fixtures for the edit-surface JVM tests. Plain
 * data only; a 4x6 grid with pages 0..2 and one profile unless overridden.
 */
package app.lawnchair.homeedit

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
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.TargetKey

internal const val SERIAL_A = 10L
internal val PROFILE_A = ProfileId("10")

internal val editSurfaceCapabilities = DeviceCapabilities(4, 6, 5, 4, 3, DeviceOrientation.PORTRAIT)

internal fun editSurfacePage(id: Int, order: Int = id) = PageState(ApplicationPageRef.PersistentPage(app.lawnchair.organizer.planning.PageId(id.toString())), PageOrder(order))

internal fun onSurfaceWorkspace(
    page: Int,
    x: Int,
    y: Int,
    spanX: Int = 1,
    spanY: Int = 1,
) = PlacementState.Workspace(
    ApplicationPageRef.PersistentPage(app.lawnchair.organizer.planning.PageId(page.toString())),
    GridCell(x, y),
    GridSpan(spanX, spanY),
)

internal fun canonicalSurfaceItem(
    id: Int,
    kind: CanonicalItemKind = CanonicalItemKind.Application,
    placement: PlacementState,
    title: OptionalText = OptionalText.Absent,
    lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
    icon: OptionalBytes = OptionalBytes.Absent,
    structure: StructureState = StructureState.Plain,
    profile: ProfileId = PROFILE_A,
) = CanonicalItemState(
    ref = ApplicationItemRef.PersistentItem(ItemId(id.toString())),
    kind = kind,
    targetKey = when (kind) {
        CanonicalItemKind.Folder -> TargetKey.FolderKey(FolderId(id.toString()))
        else -> TargetKey.AppKey(ComponentKey("com.example/.App-$id"), profile)
    },
    profile = profile,
    profileAvailability = ProfileAvailability.AVAILABLE,
    itemAvailability = ItemAvailability.AVAILABLE,
    placement = placement,
    title = title,
    intent = OptionalText.Absent,
    icon = icon,
    widget = WidgetState.NoWidget,
    modified = ModifiedAtMillis(0),
    lockState = lockState,
    structure = structure,
)

internal fun editSurfaceLayout(
    items: List<CanonicalItemState>,
    pageIds: List<Int> = listOf(0, 1, 2),
    reservations: List<ReservedWorkspaceRegion> = emptyList(),
) = LayoutState(
    pages = pageIds.map { editSurfacePage(it) },
    profiles = listOf(ProfileState(PROFILE_A, ProfileAvailability.AVAILABLE)),
    deviceCapabilities = editSurfaceCapabilities,
    items = items,
    reservedWorkspaceRegions = reservations,
)

internal fun editSurfaceReservation(
    page: Int,
    x: Int,
    y: Int,
    spanX: Int,
    spanY: Int,
) = ReservedWorkspaceRegion(
    page = PageRef(app.lawnchair.organizer.planning.PageId(page.toString())),
    cell = GridCell(x, y),
    span = GridSpan(spanX, spanY),
)

/** The #448 snapshot projection of [editSurfaceLayout]. */
internal fun editSurfaceCapture(
    items: List<CanonicalItemState>,
    pageIds: List<Int> = listOf(0, 1, 2),
    reservations: List<ReservedWorkspaceRegion> = emptyList(),
): HomeEditSnapshot = EditSurfaceProjection.homeEditSnapshot(editSurfaceLayout(items, pageIds, reservations))
