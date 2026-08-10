package app.lawnchair.organizer.planning

data class PlanningResult(
    val revision: RevisionId,
    val ruleVersion: RuleVersion,
    val taxonomyVersion: TaxonomyVersion,
    val outcome: PlanningOutcome,
)

sealed interface PlanningOutcome

data class Planned(
    val placements: List<PlannedPlacement>,
    val newPages: List<NewPage>,
    val newFolders: List<NewFolder>,
    val categories: List<CategoryDecision>,
    val warnings: List<Warning>,
) : PlanningOutcome

sealed interface Rejected : PlanningOutcome {
    data class Invalid(
        val reasons: List<RejectionReason>,
        val warnings: List<Warning>,
    ) : Rejected

    data class Impossible(
        val unplaced: List<UnplacedItem>,
        val warnings: List<Warning>,
    ) : Rejected
}

data class PlannedPlacement(
    val item: ItemId,
    val disposition: Disposition,
    val target: PlacementTarget,
)

sealed interface Disposition {
    data class Moved(val rationale: PlacementCode) : Disposition
    data class Preserved(val reason: PreserveReason) : Disposition
}

enum class PlacementCode {
    SINGLE_PLACEMENT,
    FOLDER_MEMBER,
    FOLDER_UNIT,
}

enum class PreserveReason {
    LOCKED,
    UNAVAILABLE_TARGET,
    DOCK,
    WIDGET,
    APP_PAIR,
    LEGACY_SHORTCUT,
    NON_TARGET,
    STRUCTURAL,
    ALREADY_CANONICAL,
}

sealed interface PlacementTarget {
    data class WorkspaceTarget(
        val page: PageTargetRef,
        val cell: GridCell,
        val span: GridSpan,
    ) : PlacementTarget

    data class Dock(val rank: Int) : PlacementTarget
    data class FolderMember(val folder: FolderTargetRef, val rank: Int) : PlacementTarget
    data class AppPairMember(val pair: AppPairRef) : PlacementTarget
}

data class NewPage(
    val ordinal: NewPageOrdinal,
    val order: PageOrder,
)

data class NewFolder(
    val ordinal: NewFolderOrdinal,
    val profile: ProfileId,
    val workspacePlacement: PlacementTarget.WorkspaceTarget,
    val members: List<ItemId>,
)

data class CategoryDecision(
    val item: ItemId,
    val category: CategoryId,
    val decidedSignal: SignalSource,
    val confidence: Confidence,
)

enum class Confidence {
    EXPLICIT,
    RULE,
    FALLBACK,
}

data class Warning(
    val code: WarningCode,
    val params: List<DiagnosticParam>,
)

data class UnplacedItem(
    val item: ItemId,
    val requiredSpan: GridSpan,
    val reason: UnplacedReason,
)

data class RejectionReason(
    val code: RejectionCode,
    val params: List<DiagnosticParam>,
)

sealed interface DiagnosticParam {
    data class ItemParam(val item: ItemId) : DiagnosticParam
    data class KindParam(val code: KindCode) : DiagnosticParam
    data class ContainerCodeParam(val code: ContainerCode) : DiagnosticParam
    data class SpanParam(val span: GridSpan) : DiagnosticParam
    data class RankParam(val rank: Int) : DiagnosticParam
    data class DimensionParam(val dimension: DeviceDimension, val value: Int) : DiagnosticParam
    data class PageParam(val page: PageId) : DiagnosticParam
    data class CategoryParam(val category: CategoryId) : DiagnosticParam
}

enum class DeviceDimension {
    COLUMNS,
    ROWS,
    HOTSEAT_SLOTS,
    FOLDER_MAX_COLUMNS,
    FOLDER_MAX_ROWS,
}

enum class WarningCode {
    LEGACY_SHORTCUT_REVIEW,
    FALLBACK_CATEGORY,
    UNAVAILABLE_PRESERVED,
}

enum class UnplacedReason {
    EXCEEDS_GRID_DIMENSIONS,
    TARGET_UNAVAILABLE,
}

enum class RejectionCode {
    UNKNOWN_ITEM_KIND,
    INVALID_CONTAINER,
    UNKNOWN_PAGE,
    BOUNDS_VIOLATION,
    OVERLAP,
    DANGLING_REFERENCE,
    MALFORMED_APP_PAIR,
    LOCKED_OUT_OF_BOUNDS,
    DUPLICATE_TARGET,
    MISSING_TARGET,
    INCOMPLETE_TARGET_PARTITION,
    ADDITIONS_UNDER_FULL_ORGANIZATION,
    INVALID_RULES,
    DUPLICATE_ITEM_ID,
    DUPLICATE_PAGE,
    INVALID_DIMENSIONS,
    KIND_TARGET_MISMATCH,
    TARGET_PROFILE_MISMATCH,
    UNKNOWN_SIGNAL_ITEM,
    UNKNOWN_CATEGORY,
}
