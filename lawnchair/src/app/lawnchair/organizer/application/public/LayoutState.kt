package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey

/**
 * Canonical complete layout state — the public, platform-free view of every
 * captured item and the persistent resources that participate in apply,
 * recovery, and verification. Spec §“Apply input”.
 *
 * Issue #14 Stage B step 1.
 */
data class LayoutState(
    val pages: List<PageState>,
    val profiles: List<ProfileState>,
    val deviceCapabilities: DeviceCapabilities,
    val items: List<CanonicalItemState>,
) {
    init {
        require(pages.distinctBy { it.ref }.size == pages.size) {
            "LayoutState.pages must be duplicate-free by ref"
        }
        require(profiles.distinctBy { it.id }.size == profiles.size) {
            "LayoutState.profiles must be duplicate-free by id"
        }
        require(items.distinctBy { it.ref }.size == items.size) {
            "LayoutState.items must be duplicate-free by ref"
        }
    }
}

/**
 * Public page reference. Persistent pages refer to a real [PageId]; planned
 * pages refer to a [NewPageOrdinal] allocated inside the apply transaction.
 */
sealed interface ApplicationPageRef {
    data class PersistentPage(val pageId: PageId) : ApplicationPageRef
    data class PlannedPage(val ordinal: NewPageOrdinal) : ApplicationPageRef
}

data class PageState(val ref: ApplicationPageRef, val order: PageOrder)

enum class ProfileAvailability { AVAILABLE, UNAVAILABLE }

data class ProfileState(val id: ProfileId, val availability: ProfileAvailability)

/**
 * Public device capability snapshot used by revision and verification.
 * Re-exported from the planner type because the spec defines one canonical
 * shape; do not duplicate the structure.
 */
data class DeviceCapabilities(
    val columns: Int,
    val rows: Int,
    val hotseatSlots: Int,
    val folderMaxColumns: Int,
    val folderMaxRows: Int,
    val orientation: DeviceOrientation,
)

enum class DeviceOrientation {
    PORTRAIT,
    LANDSCAPE,
    TWO_PANEL_PORTRAIT,
    TWO_PANEL_LANDSCAPE,
}

/**
 * Public item reference. Persistent items refer to an existing [ItemId];
 * planned candidates and folders use their planner-issued identifiers and are
 * resolved to persistent IDs only inside the Launcher transaction.
 */
sealed interface ApplicationItemRef {
    data class PersistentItem(val itemId: ItemId) : ApplicationItemRef
    data class PlannedCandidate(val itemId: ItemId) : ApplicationItemRef
    data class PlannedFolder(val ordinal: NewFolderOrdinal) : ApplicationItemRef
}

sealed interface PlacementState {
    data class Workspace(
        val page: ApplicationPageRef,
        val cell: GridCell,
        val span: GridSpan,
    ) : PlacementState
    data class Dock(val rank: Int) : PlacementState
    data class FolderChild(val parent: ApplicationItemRef, val rank: Int) : PlacementState
    data class AppPairChild(
        val parent: ApplicationItemRef,
        val stage: SplitStage,
    ) : PlacementState
    data class UnsupportedContainer(val code: ContainerCode) : PlacementState
}

/**
 * Canonical complete per-item state for the apply/recovery contract. Each field
 * is required to participate in revision and exact-precondition checks.
 */
data class CanonicalItemState(
    val ref: ApplicationItemRef,
    val kind: CanonicalItemKind,
    val targetKey: TargetKey,
    val profile: ProfileId,
    val profileAvailability: ProfileAvailability,
    val itemAvailability: ItemAvailability,
    val placement: PlacementState,
    val title: OptionalText,
    val intent: OptionalText,
    val icon: OptionalBytes,
    val widget: WidgetState,
    val modified: ModifiedAtMillis,
    val lockState: OrganizerLockState,
    val structure: StructureState,
)

sealed interface CanonicalItemKind {
    data object Application : CanonicalItemKind
    data object DeepShortcut : CanonicalItemKind
    data object ShortcutLegacy : CanonicalItemKind
    data object Folder : CanonicalItemKind
    data object AppWidget : CanonicalItemKind
    data object CustomAppWidget : CanonicalItemKind
    data object AppPair : CanonicalItemKind
    data class Unknown(val code: KindCode) : CanonicalItemKind
}

enum class ItemAvailability {
    AVAILABLE,
    DISABLED,
    QUIET,
    LOCKED_PRIVATE_SPACE,
    UNAVAILABLE,
}

enum class OrganizerLockState { UNKNOWN, UNLOCKED, LOCKED }

sealed interface WidgetState {
    data object NoWidget : WidgetState
    data class Widget(
        val provider: ComponentKey,
        val appWidgetId: AppWidgetId,
        val restored: WidgetRestoreState,
        val options: WidgetOptions,
        val source: WidgetSource,
    ) : WidgetState
}

@JvmInline
value class WidgetRestoreState(val value: Int)

@JvmInline
value class WidgetOptions(val value: Int)

@JvmInline
value class WidgetSource(val value: Int)

sealed interface StructureState {
    data object Plain : StructureState
    data class FolderMembers(val members: List<RankedMember>) : StructureState
    data class AppPairMembers(
        val members: List<AppPairMemberState>,
        val snapPosition: OptionalSnapPosition,
    ) : StructureState
}

/**
 * One decoded app-pair member row, carried in persisted rank order. The list is
 * not restricted to two entries: member-count and stage/snap coherence are
 * planner-owned validity judgments (V-07), not capture preconditions.
 */
data class AppPairMemberState(
    val item: ApplicationItemRef,
    val stage: SplitStage,
)

data class RankedMember(val item: ApplicationItemRef, val rank: Int)

sealed interface OptionalSnapPosition {
    data object Absent : OptionalSnapPosition
    data class Present(val token: SnapPositionToken) : OptionalSnapPosition
}

sealed interface OptionalText {
    data object Absent : OptionalText
    data class Present(val value: String) : OptionalText
}

sealed interface OptionalBytes {
    data object Absent : OptionalBytes
    data class Present(val value: ImmutableByteString) : OptionalBytes
}

/**
 * Immutable byte sequence with value-copy semantics and byte-value equality.
 * Source bytes are copied on construction so external mutation cannot affect
 * the canonical state.
 */
data class ImmutableByteString(val bytes: List<Byte>) {
    init {
        require(bytes.size <= MAX_BYTES) { "ImmutableByteString exceeds $MAX_BYTES bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImmutableByteString) return false
        if (bytes.size != other.bytes.size) return false
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] != other.bytes[i]) return false
            i += 1
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 1
        for (b in bytes) {
            h = 31 * h + b.toInt()
        }
        return h
    }

    fun asByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }

    companion object {
        const val MAX_BYTES: Int = 8 * 1024 * 1024

        fun copyFrom(array: ByteArray): ImmutableByteString = ImmutableByteString(List(array.size) { array[it] })
    }
}

@JvmInline
value class ModifiedAtMillis(val value: Long) {
    init {
        require(value >= 0L) { "ModifiedAtMillis must be non-negative" }
    }
}

/**
 * Public apply action. Exactly one variant per represented item.
 */
sealed interface ApplyAction {
    val ref: ApplicationItemRef

    data class Preserve(
        override val ref: ApplicationItemRef,
        val expected: CanonicalItemState,
    ) : ApplyAction
    data class Update(
        override val ref: ApplicationItemRef,
        val expected: CanonicalItemState,
        val intended: CanonicalItemState,
    ) : ApplyAction
    data class Insert(
        override val ref: ApplicationItemRef,
        val intended: CanonicalItemState,
    ) : ApplyAction
}
