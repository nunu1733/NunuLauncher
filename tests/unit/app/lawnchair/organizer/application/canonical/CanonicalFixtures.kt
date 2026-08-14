package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.ImmutableByteString
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
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetOptions
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.application.public.WidgetSource
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.AppWidgetId
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
 * Minimal canonical [LayoutState] fixtures for revision and protocol tests.
 * Intentionally synthetic — never copy production layouts into tests.
 *
 * Issue #14 Stage B step 1.
 */
object CanonicalFixtures {

    fun deviceCapabilities(
        columns: Int = 4,
        rows: Int = 5,
        hotseatSlots: Int = 4,
        folderMaxColumns: Int = 4,
        folderMaxRows: Int = 4,
        orientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    ): DeviceCapabilities = DeviceCapabilities(columns, rows, hotseatSlots, folderMaxColumns, folderMaxRows, orientation)

    fun page(ref: ApplicationPageRef, order: Int): PageState = PageState(ref, PageOrder(order))

    fun profile(id: String, availability: ProfileAvailability = ProfileAvailability.AVAILABLE): ProfileState = ProfileState(ProfileId(id), availability)

    fun appItem(
        itemId: String = "app.a",
        profile: String = "personal",
        page: ApplicationPageRef = ApplicationPageRef.PersistentPage(PageId("p0")),
        cell: GridCell = GridCell(0, 0),
        span: GridSpan = GridSpan(1, 1),
        target: TargetKey = TargetKey.AppKey(
            component = ComponentKey("com.example.a/.Main"),
            profile = ProfileId("personal"),
        ),
        availability: ItemAvailability = ItemAvailability.AVAILABLE,
        title: OptionalText = OptionalText.Present("A"),
        intent: OptionalText = OptionalText.Present("#Intent;"),
        icon: OptionalBytes = OptionalBytes.Absent,
        widget: WidgetState = WidgetState.NoWidget,
        modified: Long = 1_000L,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        structure: StructureState = StructureState.Plain,
        kind: CanonicalItemKind = CanonicalItemKind.Application,
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(itemId)),
        kind = kind,
        targetKey = target,
        profile = ProfileId(profile),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = availability,
        placement = PlacementState.Workspace(page = page, cell = cell, span = span),
        title = title,
        intent = intent,
        icon = icon,
        widget = widget,
        modified = ModifiedAtMillis(modified),
        lockState = lockState,
        structure = structure,
    )

    fun widgetItem(
        itemId: String = "widget.1",
        profile: String = "personal",
        page: ApplicationPageRef = ApplicationPageRef.PersistentPage(PageId("p0")),
        cell: GridCell = GridCell(0, 0),
        span: GridSpan = GridSpan(2, 2),
        provider: String = "com.example.widget/.Widget",
        appWidgetId: Int = 5,
        profileAvailability: ProfileAvailability = ProfileAvailability.AVAILABLE,
        restored: Int = 1,
        options: Int = 0,
        source: Int = 0,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(itemId)),
        kind = CanonicalItemKind.AppWidget,
        targetKey = TargetKey.WidgetKey(
            provider = ComponentKey(provider),
            appWidgetId = AppWidgetId(appWidgetId),
            profile = ProfileId(profile),
        ),
        profile = ProfileId(profile),
        profileAvailability = profileAvailability,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(page = page, cell = cell, span = span),
        title = OptionalText.Absent,
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.Widget(
            provider = ComponentKey(provider),
            appWidgetId = AppWidgetId(appWidgetId),
            restored = WidgetRestoreState(restored),
            options = WidgetOptions(options),
            source = WidgetSource(source),
        ),
        modified = ModifiedAtMillis(1_000L),
        lockState = lockState,
        structure = StructureState.Plain,
    )

    fun immutableIcon(bytes: ByteArray): OptionalBytes = OptionalBytes.Present(ImmutableByteString.copyFrom(bytes))

    fun appPairStructure(
        first: ApplicationItemRef,
        second: ApplicationItemRef,
        firstStage: SplitStage = SplitStage.TOP_OR_LEFT,
        secondStage: SplitStage = SplitStage.BOTTOM_OR_RIGHT,
        snapPosition: OptionalSnapPosition = OptionalSnapPosition.Absent,
    ): StructureState = StructureState.AppPairMembers(
        first = first,
        second = second,
        firstStage = firstStage,
        secondStage = secondStage,
        snapPosition = snapPosition,
    )

    fun state(
        pages: List<PageState> = listOf(page(ApplicationPageRef.PersistentPage(PageId("p0")), 0)),
        profiles: List<ProfileState> = listOf(profile("personal")),
        items: List<CanonicalItemState> = emptyList(),
        device: DeviceCapabilities = deviceCapabilities(),
    ): LayoutState = LayoutState(pages = pages, profiles = profiles, deviceCapabilities = device, items = items)
}
