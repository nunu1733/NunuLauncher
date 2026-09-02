package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.NewPageChange
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.PreviewChange
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.Planned

/**
 * Pure projection of a materialized plan plus its semantic plan into the
 * UI-facing change list (Issue #194). No I/O, no clock, no randomness, no
 * localized strings: identical inputs produce identical output.
 *
 * Responsibility split (assessment §5-3): actions and before/after come from
 * the [ValidatedLayoutPlan]; rationale, preserve reasons, and warnings come
 * from the planner's [Planned]. The join is by item identity and is
 * deterministic because the materializer derives actions deterministically
 * from the same `(input, result)` pair. A join miss or a disposition the
 * rows cannot truthfully represent is a contract violation and yields
 * [Result.Invalid] (surfaced as a typed non-write preview rejection).
 */
object PlanPreviewProjector {

    sealed interface Result {
        data class Ready(val details: PlanPreviewDetails) : Result
        data object Invalid : Result
    }

    fun project(plan: ValidatedLayoutPlan, planned: Planned): Result {
        val dispositionByItem = planned.placements.associate { it.item to it.disposition }
        val sourceItemByItemId = plan.sourceState.items
            .mapNotNull { item -> item.persistentItemId()?.let { it to item } }
            .toMap()
        val context = PositionContext(plan, sourceItemByItemId)
        val changes = mutableListOf<PreviewChange>()

        plan.actions.forEach { action ->
            when (val ref = action.ref) {
                is ApplicationItemRef.PlannedFolder -> return@forEach

                is ApplicationItemRef.PersistentItem -> {
                    val rows = itemRows(action, ref.itemId, dispositionByItem, context)
                        ?: return Result.Invalid
                    changes.addAll(rows)
                }

                is ApplicationItemRef.PlannedCandidate -> return Result.Invalid
            }
        }

        plan.newFolders.sortedBy { it.ordinal.value }.forEach { folder ->
            val insert = plan.actions.filterIsInstance<ApplyAction.Insert>()
                .firstOrNull { (it.ref as? ApplicationItemRef.PlannedFolder)?.ordinal == folder.ordinal }
                ?: return Result.Invalid
            val placement = context.workspacePosition(insert.intended) ?: return Result.Invalid
            val memberLabels = folder.members.map { memberId ->
                sourceItemByItemId[memberId]?.let(::itemLabel) ?: return Result.Invalid
            }
            changes.add(
                NewFolderChange(
                    ordinal = folder.ordinal,
                    placement = placement,
                    memberLabels = memberLabels,
                ),
            )
        }

        plan.newPages.sortedBy { it.order }.forEach { page ->
            val position = context.plannedPageDisplayPosition(page.ordinal) ?: return Result.Invalid
            changes.add(NewPageChange(ordinal = page.ordinal, displayPosition = position))
        }

        planned.warnings.forEach { warning ->
            val itemParams = warning.params.filterIsInstance<DiagnosticParam.ItemParam>()
            if (itemParams.size == 1) {
                val state = sourceItemByItemId[itemParams.single().item] ?: return Result.Invalid
                changes.add(
                    ItemWarningChange(
                        item = itemParams.single().item,
                        label = itemLabel(state),
                        code = warning.code,
                    ),
                )
            }
        }

        val details = PlanPreviewDetails(
            changes = changes.toList(),
            counts = PreviewCounts(
                movedCount = changes.count { it is MoveChange },
                preservedCount = changes.count { it is PreservedChange },
                newFolderCount = plan.newFolders.size,
                newPageCount = plan.newPages.size,
                warningCounts = planned.warnings.groupingBy { it.code }.eachCount(),
            ),
        )
        return Result.Ready(details)
    }

    /**
     * Rows for one persistent-item action, in action order. `null` is a join
     * / disposition contract violation; an empty list means the action needs
     * no dedicated row (e.g. an `Update` whose placement is unchanged — the
     * member moves already describe the visible change).
     */
    private fun itemRows(
        action: ApplyAction,
        itemId: ItemId,
        dispositionByItem: Map<ItemId, Disposition>,
        context: PositionContext,
    ): List<PreviewChange>? {
        val disposition = dispositionByItem[itemId] ?: return null
        return when (action) {
            is ApplyAction.Preserve -> {
                val reason = (disposition as? Disposition.Preserved)?.reason ?: return null
                listOf(
                    PreservedChange(
                        item = itemId,
                        label = context.label(action.expected),
                        reason = reason,
                    ),
                )
            }

            is ApplyAction.Update -> {
                if (action.expected.placement == action.intended.placement) return emptyList()
                listOf(
                    MoveChange(
                        item = itemId,
                        label = context.label(action.expected),
                        source = context.position(action.expected) ?: return null,
                        destination = context.position(action.intended) ?: return null,
                        rationale = (disposition as? Disposition.Moved)?.rationale,
                    ),
                )
            }

            is ApplyAction.Insert -> null
        }
    }

    private fun CanonicalItemState.persistentItemId(): ItemId? = (ref as? ApplicationItemRef.PersistentItem)?.itemId

    private fun itemLabel(state: CanonicalItemState): PreviewLabel = when (val title = state.title) {
        is OptionalText.Present -> PreviewLabel.Named(title.value)
        OptionalText.Absent -> PreviewLabel.KindFallback(state.kind)
    }

    /**
     * Shared position normalization: 1-based page display ordinal over the
     * combined `PageOrder` sort, 3x3 bands from the start cell
     * (`floor(coord * 3 / dimension)`, clamped), and a 1-based row ordinal.
     */
    private class PositionContext(
        private val plan: ValidatedLayoutPlan,
        private val sourceItemByItemId: Map<ItemId, CanonicalItemState>,
    ) {
        private val plannedFolderOrdinals: Set<NewFolderOrdinal> = plan.newFolders.map { it.ordinal }.toSet()

        private val persistentPageOrdinals: Map<PageId, Int> = buildMap {
            var position = 1
            plan.sourceState.pages
                .sortedBy { it.order }
                .forEach { page ->
                    (page.ref as? ApplicationPageRef.PersistentPage)?.let {
                        put(it.pageId, position++)
                    }
                }
        }

        private val plannedPageOrdinals: Map<NewPageOrdinal, Int> = buildMap {
            var position = persistentPageOrdinals.size + 1
            plan.newPages
                .sortedBy { it.order }
                .forEach { page -> put(page.ordinal, position++) }
        }

        fun label(state: CanonicalItemState): PreviewLabel = itemLabel(state)

        fun position(state: CanonicalItemState): PreviewPosition? = when (val placement = state.placement) {
            is PlacementState.Workspace -> workspacePosition(state)

            is PlacementState.Dock -> PreviewPosition.DockRank(placement.rank)

            is PlacementState.FolderChild -> when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> sourceItemByItemId[parent.itemId]
                    ?.takeIf { it.kind is CanonicalItemKind.Folder }
                    ?.let { PreviewPosition.InFolder(PreviewFolderRef.Existing(itemLabel(it))) }

                is ApplicationItemRef.PlannedFolder ->
                    parent.ordinal
                        .takeIf { it in plannedFolderOrdinals }
                        ?.let { PreviewPosition.InFolder(PreviewFolderRef.Planned(it)) }

                is ApplicationItemRef.PlannedCandidate -> null
            }

            is PlacementState.AppPairChild -> when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> sourceItemByItemId[parent.itemId]
                    ?.let { PreviewPosition.InAppPair(itemLabel(it)) }

                else -> null
            }

            is PlacementState.UnsupportedContainer -> null
        }

        fun workspacePosition(state: CanonicalItemState): PreviewPosition.Workspace? {
            val placement = state.placement as? PlacementState.Workspace ?: return null
            val device = plan.sourceState.deviceCapabilities
            val ordinal: Int
            val isNewPage: Boolean
            when (val page = placement.page) {
                is ApplicationPageRef.PersistentPage -> {
                    ordinal = persistentPageOrdinals[page.pageId] ?: return null
                    isNewPage = false
                }

                is ApplicationPageRef.PlannedPage -> {
                    ordinal = plannedPageOrdinals[page.ordinal] ?: return null
                    isNewPage = true
                }
            }
            return PreviewPosition.Workspace(
                pageDisplayOrdinal = ordinal,
                isNewPage = isNewPage,
                rowBand = band(placement.cell.y, device.rows, RowBand.entries),
                columnBand = band(placement.cell.x, device.columns, ColumnBand.entries),
                rowOrdinal = placement.cell.y + 1,
            )
        }

        fun plannedPageDisplayPosition(ordinal: NewPageOrdinal): Int? = plannedPageOrdinals[ordinal]

        private fun <B : Enum<B>> band(coordinate: Int, dimension: Int, bands: List<B>): B {
            val scaled = if (dimension <= 0) 0 else coordinate * 3 / dimension
            val index = scaled.coerceIn(0, bands.size - 1)
            return bands[index]
        }
    }
}
