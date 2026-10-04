package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.WarningCode

/**
 * Read-only plan preview result (Issue #194). `inspectPlan` returns one closed
 * variant; every path is a non-write outcome. Process-local like the opaque
 * confirmation: never serialized, journaled, or exported.
 */
sealed interface PlanPreviewResult {
    data class Previewed(val preview: PlanPreview) : PlanPreviewResult
    data object Stale : PlanPreviewResult
    data class NotPlannable(val reason: PlanPreviewRejection) : PlanPreviewResult
    data class Unavailable(val reason: PlanPreviewUnavailable) : PlanPreviewResult
    data object WriterBusy : PlanPreviewResult
    data object Concurrent : PlanPreviewResult
}

enum class PlanPreviewRejection {
    OUTCOME_NOT_PLANNED,
    CAPTURE_FAILED,
    MATERIALIZATION_INVALID,
}

enum class PlanPreviewUnavailable {
    RECONCILIATION_PENDING,
    RECONCILIATION_FAILED,
}

/**
 * The materialized executable plan plus its UI-facing projection. [plan] is
 * the canonical preview source the coordinator applies on confirmation; it is
 * never exposed through observable run state. [details] is the only part the
 * UI consumes.
 */
data class PlanPreview(
    val plan: ValidatedLayoutPlan,
    val details: PlanPreviewDetails,
)

data class PlanPreviewDetails(
    val changes: List<PreviewChange>,
    val counts: PreviewCounts,
)

/**
 * Per-item concrete change projected for the confirmation UI. Rows derive
 * actions / before-after from the [ValidatedLayoutPlan] and rationale /
 * reasons / warnings from the planner's `Planned` outcome; [ItemId] is an
 * opaque correlation key only. No raw package, component, page id, cell
 * coordinate, digest, or profile identity is carried.
 */
sealed interface PreviewChange

data class MoveChange(
    val item: ItemId,
    val label: PreviewLabel,
    val source: PreviewPosition,
    val destination: PreviewPosition,
    val rationale: PlacementCode?,
) : PreviewChange {
    /**
     * Same-region fine adjustment: both ends normalize to the same band on the
     * same page, so ordinal or region wording alone would hide the change.
     * Derived, so it cannot disagree with [source] / [destination].
     */
    val sameBandAdjustment: Boolean
        get() = source is PreviewPosition.Workspace &&
            destination is PreviewPosition.Workspace &&
            source.pageDisplayOrdinal == destination.pageDisplayOrdinal &&
            source.rowBand == destination.rowBand &&
            source.columnBand == destination.columnBand
}

data class PreservedChange(
    val item: ItemId,
    val label: PreviewLabel,
    val reason: PreserveReason,
) : PreviewChange

data class NewFolderChange(
    val ordinal: NewFolderOrdinal,
    /**
     * Issue #201: the resolved generated-folder title, identical to the value
     * the apply writer persists. Always [PreviewLabel.Named]; raw identifiers
     * are never carried here.
     */
    val name: PreviewLabel,
    val placement: PreviewPosition,
    val memberLabels: List<PreviewLabel>,
) : PreviewChange

data class NewPageChange(
    val ordinal: NewPageOrdinal,
    val displayPosition: Int,
) : PreviewChange

data class ItemWarningChange(
    val item: ItemId,
    val label: PreviewLabel,
    val code: WarningCode,
) : PreviewChange

/**
 * Display name carried by a change row. Real titles flow as [Named]; absent
 * titles fall back to the canonical kind, whose generic wording the UI owns
 * (the projection carries no localized strings).
 */
sealed interface PreviewLabel {
    data class Named(val value: String) : PreviewLabel
    data class KindFallback(val kind: CanonicalItemKind) : PreviewLabel
}

sealed interface PreviewPosition {
    data class Workspace(
        val pageDisplayOrdinal: Int,
        val isNewPage: Boolean,
        val rowBand: RowBand,
        val columnBand: ColumnBand,
        val rowOrdinal: Int,
    ) : PreviewPosition

    data class DockRank(val rank: Int) : PreviewPosition
    data class InFolder(val folder: PreviewFolderRef) : PreviewPosition
    data class InAppPair(val pair: PreviewLabel) : PreviewPosition
}

sealed interface PreviewFolderRef {
    data class Existing(val label: PreviewLabel) : PreviewFolderRef

    /** Issue #201: planned-folder destinations carry the same resolved title
     *  as the folder's own change row — UI never renders the planner ordinal. */
    data class Planned(val ordinal: NewFolderOrdinal, val name: PreviewLabel) : PreviewFolderRef
}

enum class RowBand { TOP, CENTER, BOTTOM }

enum class ColumnBand { LEFT, CENTER, RIGHT }

/**
 * Header counts derived from the executable plan (rows). The existing count
 * summary stays the UI truth for #194 standalone; a UI rendering the concrete
 * list uses these counts so rows and header always agree.
 */
data class PreviewCounts(
    val movedCount: Int,
    val preservedCount: Int,
    val newFolderCount: Int,
    val newPageCount: Int,
    val warningCounts: Map<WarningCode, Int>,
)
