package app.lawnchair.organizer.planning

data class OrganizationInput(
    val snapshot: LayoutSnapshot,
    val rules: RuleSemantics,
    val taxonomy: TaxonomyContract,
    val signals: ClassificationSignals,
    val targets: TargetSet,
    val runMode: RunMode,
)

enum class RunMode {
    FullOrganization,
    IncrementalPlacement,
}

data class LayoutSnapshot(
    val revision: RevisionId,
    val device: DeviceCapabilities,
    val pages: List<Page>,
    val items: List<CapturedItem>,
)

data class Page(
    val id: PageId,
    val order: PageOrder,
)

data class DeviceCapabilities(
    val columns: Int,
    val rows: Int,
    val hotseatSlots: Int,
    val folderMaxColumns: Int,
    val folderMaxRows: Int,
    val orientation: Orientation,
)

enum class Orientation {
    PORTRAIT,
    LANDSCAPE,
    TWO_PANEL_PORTRAIT,
    TWO_PANEL_LANDSCAPE,
}

data class CapturedItem(
    val id: ItemId,
    val profile: ProfileId,
    val kind: ItemKind,
    val target: TargetKey,
    val placement: CapturedPlacement,
    val locked: Boolean,
    val availability: Availability,
    val folderId: FolderId? = null,
    val appPairId: AppPairId? = null,
    val members: List<ItemId> = emptyList(),
    val appPair: AppPairMetadata? = null,
)

@Suppress("ktlint:standard:class-naming")
sealed interface ItemKind {
    data object APPLICATION : ItemKind
    data object DEEP_SHORTCUT : ItemKind
    data object SHORTCUT_LEGACY : ItemKind
    data object FOLDER : ItemKind
    data object APPWIDGET : ItemKind
    data object CUSTOM_APPWIDGET : ItemKind
    data object APP_PAIR : ItemKind
    data class Unknown(val code: KindCode) : ItemKind
}

enum class Availability {
    AVAILABLE,
    DISABLED,
    QUIET,
    LOCKED_PRIVATE_SPACE,
    UNAVAILABLE,
}

sealed interface TargetKey {
    data class AppKey(val component: ComponentKey, val profile: ProfileId) : TargetKey
    data class ShortcutKey(
        val packageName: PackageName,
        val shortcutId: ShortcutId,
        val profile: ProfileId,
    ) : TargetKey
    data object LegacyShortcutKey : TargetKey
    data class WidgetKey(
        val provider: ComponentKey,
        val appWidgetId: AppWidgetId,
        val profile: ProfileId,
    ) : TargetKey
    data class FolderKey(val folderId: FolderId) : TargetKey
    data class AppPairKey(val appPairId: AppPairId) : TargetKey
}

sealed interface CapturedPlacement {
    data class Workspace(
        val page: PageRef,
        val cell: GridCell,
        val span: GridSpan,
    ) : CapturedPlacement
    data class Dock(val rank: Int) : CapturedPlacement
    data class FolderMember(val folder: FolderRef, val rank: Int) : CapturedPlacement
    data class AppPairMember(val pair: AppPairRef) : CapturedPlacement
    data class UnsupportedContainer(val code: ContainerCode) : CapturedPlacement
}

data class AppPairMetadata(
    val members: List<AppPairMember>,
)

data class AppPairMember(
    val item: ItemId,
    val stage: SplitStage,
    val snapPosition: SnapPositionToken?,
)

enum class SplitStage {
    TOP_OR_LEFT,
    BOTTOM_OR_RIGHT,
}

data class CandidateItem(
    val id: ItemId,
    val profile: ProfileId,
    val kind: CandidateKind,
    val target: CandidateTarget,
    val availability: Availability,
    val span: GridSpan,
)

enum class CandidateKind {
    APPLICATION,
    DEEP_SHORTCUT,
}

sealed interface CandidateTarget {
    data class AppKey(val component: ComponentKey, val profile: ProfileId) : CandidateTarget
    data class ShortcutKey(
        val packageName: PackageName,
        val shortcutId: ShortcutId,
        val profile: ProfileId,
    ) : CandidateTarget
}

data class RuleSemantics(
    val version: RuleVersion,
    val folderPolicy: FolderPolicy,
    val dockPolicy: DockPolicy,
    val overflowPolicy: OverflowPolicy,
    val fallbackCategoryPolicy: FallbackCategoryPolicy,
    val orderingPolicy: OrderingPolicy,
)

data class FolderPolicy(
    val minGroupSize: Int,
    val newFolderProfileScope: NewFolderProfileScope,
)

enum class NewFolderProfileScope {
    SAME_PROFILE_ONLY,
}

enum class DockPolicy {
    PRESERVE,
}

enum class OverflowPolicy {
    ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
}

enum class FallbackCategoryPolicy {
    KEEP_AS_SINGLETON,
}

enum class OrderingPolicy {
    CANONICAL_V1,
}

data class TaxonomyContract(
    val version: TaxonomyVersion,
    val allowedCategories: List<CategoryId>,
    val fallbackCategory: CategoryId,
)

data class ClassificationSignals(
    val entries: List<ClassificationSignal>,
)

data class ClassificationSignal(
    val item: ItemId,
    val source: SignalSource,
    val candidate: CategoryId,
)

enum class SignalSource {
    S1,
    S2,
    S3,
    S4,
    S5,
    S6,
}

data class TargetSet(
    val existing: List<ExistingTargetMembership>,
    val additions: List<CandidateItem>,
)

data class ExistingTargetMembership(
    val item: ItemId,
    val role: ExistingRole,
)

enum class ExistingRole {
    Movable,
    Preserved,
}
