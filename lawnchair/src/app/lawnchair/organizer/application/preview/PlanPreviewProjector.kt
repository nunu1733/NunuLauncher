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
import app.lawnchair.organizer.application.public.PreviewPlacementIdentity
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
import app.lawnchair.organizer.planning.PreserveReason

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
        // Issue #201: join each planned folder ordinal to its resolved title
        // (the same value the new-folder row and the apply writer use). A join
        // miss is a contract violation and fails closed.
        val plannedFolderNames = plannedFolderNames(plan) ?: return Result.Invalid
        val warningItemIds = planned.warnings.flatMap { warning ->
            warning.params.filterIsInstance<DiagnosticParam.ItemParam>().map { it.item }
        }
        val context = PositionContext(plan, sourceItemByItemId, plannedFolderNames, warningItemIds)
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
            // Issue #201: the folder name is the same resolved title the apply
            // writer persists — read from the intended state, never re-derived.
            // Issue #208: the placement goes through the single identity ->
            // position path like every other row.
            val name = plannedFolderNames[folder.ordinal] ?: return Result.Invalid
            val folderIdentity = context.identity(insert.intended) ?: return Result.Invalid
            val placement = context.position(folderIdentity) ?: return Result.Invalid
            val memberLabels = folder.members.map { memberId ->
                sourceItemByItemId[memberId]?.let(::itemLabel) ?: return Result.Invalid
            }
            changes.add(
                NewFolderChange(
                    ordinal = folder.ordinal,
                    name = name,
                    placement = placement,
                    memberLabels = memberLabels,
                ),
            )
        }

        plan.newPages.sortedBy { it.ordinal.value }.forEach { page ->
            val position = context.plannedPageDisplayPosition(page.ordinal) ?: return Result.Invalid
            changes.add(NewPageChange(ordinal = page.ordinal, displayPosition = position))
        }

        planned.warnings.forEach { warning ->
            val itemParams = warning.params.filterIsInstance<DiagnosticParam.ItemParam>()
            if (itemParams.size == 1) {
                val state = sourceItemByItemId[itemParams.single().item] ?: return Result.Invalid
                // Issue #208: the same source item keeps one identity across
                // its preserve and warning rows (source-item-keyed
                // discriminators), so a warned item never splits identities.
                val identity = context.identity(state) ?: return Result.Invalid
                changes.add(
                    ItemWarningChange(
                        item = itemParams.single().item,
                        label = itemLabel(state),
                        identity = identity,
                        kind = state.kind,
                        current = context.position(identity) ?: return Result.Invalid,
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
                // Spec 182 strategy consequences, derived from the same rows
                // the change list renders so header and rows share one truth.
                crossPageMovedCount = changes.count { change ->
                    change is MoveChange &&
                        change.source is PreviewPosition.Workspace &&
                        change.destination is PreviewPosition.Workspace &&
                        (change.source as PreviewPosition.Workspace).pageDisplayOrdinal !=
                        (change.destination as PreviewPosition.Workspace).pageDisplayOrdinal
                },
                preservedByStrategyCount = changes.count { change ->
                    change is PreservedChange && change.reason == PreserveReason.STRATEGY_PRESERVED
                },
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
                val identity = context.identity(action.expected) ?: return null
                listOf(
                    PreservedChange(
                        item = itemId,
                        label = context.label(action.expected),
                        identity = identity,
                        kind = action.expected.kind,
                        // `current == position(identity)` by construction; a
                        // miss here is a parent-title contract violation.
                        current = context.position(identity) ?: return null,
                        reason = reason,
                    ),
                )
            }

            is ApplyAction.Update -> {
                if (action.expected.placement == action.intended.placement) return emptyList()
                val sourceIdentity = context.identity(action.expected) ?: return null
                val destinationIdentity = context.identity(action.intended) ?: return null
                // A move row never speaks about an undescribed container: the
                // planner targets supported placements only (fail-closed,
                // matching the pre-#208 null-position behavior).
                if (sourceIdentity is PreviewPlacementIdentity.Unidentified ||
                    destinationIdentity is PreviewPlacementIdentity.Unidentified
                ) {
                    return null
                }
                listOf(
                    MoveChange(
                        item = itemId,
                        label = context.label(action.expected),
                        identity = sourceIdentity,
                        kind = action.expected.kind,
                        source = context.position(sourceIdentity) ?: return null,
                        destination = context.position(destinationIdentity) ?: return null,
                        rationale = (disposition as? Disposition.Moved)?.rationale,
                    ),
                )
            }

            is ApplyAction.Insert -> null
        }
    }

    private fun CanonicalItemState.persistentItemId(): ItemId? = (ref as? ApplicationItemRef.PersistentItem)?.itemId

    /**
     * Issue #201: one resolved name per planned folder ordinal, sourced from
     * the Insert's intended title. `null` = contract violation (missing
     * Insert, absent or blank title) and the projection fails closed.
     */
    private fun plannedFolderNames(plan: ValidatedLayoutPlan): Map<NewFolderOrdinal, PreviewLabel>? {
        if (plan.newFolders.isEmpty()) return emptyMap()
        val names = mutableMapOf<NewFolderOrdinal, PreviewLabel>()
        for (folder in plan.newFolders) {
            val insert = plan.actions.filterIsInstance<ApplyAction.Insert>()
                .firstOrNull { (it.ref as? ApplicationItemRef.PlannedFolder)?.ordinal == folder.ordinal }
                ?: return null
            val name = when (val title = insert.intended.title) {
                is OptionalText.Present -> title.value.takeIf { it.isNotBlank() } ?: return null
                OptionalText.Absent -> return null
            }
            names[folder.ordinal] = PreviewLabel.Named(name)
        }
        return names
    }

    private fun itemLabel(state: CanonicalItemState): PreviewLabel = when (val title = state.title) {
        is OptionalText.Present -> PreviewLabel.Named(title.value)
        OptionalText.Absent -> PreviewLabel.KindFallback(state.kind)
    }

    /**
     * Shared identity + position normalization (Issue #208): 1-based page
     * display ordinal over the combined `PageOrder` sort, identity anchors at
     * the exact start cell, and the presentation bands it into 3x3 regions
     * (`floor(coord * 3 / dimension)`, clamped) with a 1-based row ordinal.
     * Identity is generated once from the capture placement; every
     * `PreviewPosition` derives from an identity, never from a second capture
     * lookup (single derivation path).
     */
    private class PositionContext(
        private val plan: ValidatedLayoutPlan,
        private val sourceItemByItemId: Map<ItemId, CanonicalItemState>,
        private val plannedFolderNames: Map<NewFolderOrdinal, PreviewLabel>,
        warningItemIds: List<ItemId>,
    ) {
        private val plannedFolderOrdinals: Set<NewFolderOrdinal> = plan.newFolders.map { it.ordinal }.toSet()

        /**
         * Issue #208: `Unidentified` discriminators keyed by source item and
         * assigned once per proposal — the same item keeps one identity
         * across its move / preserve / warning rows regardless of how many
         * times identity() is called.
         */
        private val unidentifiedDiscriminatorByItemId: Map<ItemId, Int> = buildMap {
            var next = 1
            fun claim(state: CanonicalItemState?) {
                (state?.ref as? ApplicationItemRef.PersistentItem)?.let { ref ->
                    if (state.placement is PlacementState.UnsupportedContainer) putIfAbsent(ref.itemId, next++)
                }
            }
            plan.actions.forEach { action ->
                when (action) {
                    is ApplyAction.Update -> claim(action.expected)
                    is ApplyAction.Preserve -> claim(action.expected)
                    is ApplyAction.Insert -> Unit
                }
            }
            warningItemIds.forEach { id -> claim(sourceItemByItemId[id]) }
        }

        /**
         * Combined `PageOrder` sequence: persistent and planned pages share one
         * sort, so a planned page ordered between existing pages gets its
         * in-between display position (spec §Position normalization).
         */
        private val combinedPagePositions: Map<PageKey, Int> = buildMap {
            val entries = buildList {
                plan.sourceState.pages.forEach { page ->
                    (page.ref as? ApplicationPageRef.PersistentPage)?.let {
                        add(PageKey.Persistent(it.pageId) to page.order)
                    }
                }
                plan.newPages.forEach { page ->
                    add(PageKey.Planned(page.ordinal) to page.order)
                }
            }
            var position = 1
            entries.sortedBy { it.second }.forEach { (key, _) -> put(key, position++) }
        }

        fun persistentPageOrdinal(pageId: PageId): Int? = combinedPagePositions[PageKey.Persistent(pageId)]

        fun plannedPageOrdinal(ordinal: NewPageOrdinal): Int? = combinedPagePositions[PageKey.Planned(ordinal)]

        private sealed interface PageKey {
            data class Persistent(val pageId: PageId) : PageKey
            data class Planned(val ordinal: NewPageOrdinal) : PageKey
        }

        fun label(state: CanonicalItemState): PreviewLabel = itemLabel(state)

        /**
         * Issue #208: canonical identity of a capture placement. `null` means
         * a parent-title contract violation (fail-closed); unsupported
         * containers resolve to [PreviewPlacementIdentity.Unidentified] with
         * the source-item-keyed discriminator, so unsupported placements keep
         * unique identities too.
         */
        fun identity(state: CanonicalItemState): PreviewPlacementIdentity? = when (val placement = state.placement) {
            is PlacementState.Workspace -> workspaceIdentity(state)

            is PlacementState.Dock -> PreviewPlacementIdentity.Dock(placement.rank)

            is PlacementState.FolderChild -> when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> sourceItemByItemId[parent.itemId]
                    ?.takeIf { it.kind is CanonicalItemKind.Folder }
                    ?.let { parentState ->
                        val parentIdentity = identity(parentState)
                        parentIdentity?.let { PreviewPlacementIdentity.FolderChild(parentIdentity, placement.rank) }
                    }

                is ApplicationItemRef.PlannedFolder ->
                    parent.ordinal
                        .takeIf { it in plannedFolderOrdinals }
                        ?.takeIf { plannedFolderNames.containsKey(it) }
                        ?.let { PreviewPlacementIdentity.FolderChild(PreviewPlacementIdentity.PlannedFolder(it), placement.rank) }

                is ApplicationItemRef.PlannedCandidate -> null
            }

            is PlacementState.AppPairChild -> when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> sourceItemByItemId[parent.itemId]?.let { parentState ->
                    val parentIdentity = identity(parentState)
                    parentIdentity?.let { PreviewPlacementIdentity.AppPairChild(parentIdentity, placement.stage) }
                }

                else -> null
            }

            is PlacementState.UnsupportedContainer -> {
                val itemId = (state.ref as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
                unidentifiedDiscriminatorByItemId[itemId]?.let {
                    PreviewPlacementIdentity.Unidentified(code = placement.code, proposalLocalDiscriminator = it)
                }
            }
        }

        /**
         * Identity anchors carry no display titles, so folder / app-pair
         * presentation resolves the parent's title through this map, keyed by
         * the parent item's own identity. Source identities are unique (no
         * two source items share a placement), so the map is collision-free.
         */
        private val labelByIdentity: Map<PreviewPlacementIdentity, PreviewLabel> = buildMap {
            sourceItemByItemId.values.forEach { state ->
                val id = identity(state) ?: return@buildMap
                put(id, itemLabel(state))
            }
        }

        fun workspaceIdentity(state: CanonicalItemState): PreviewPlacementIdentity.Workspace? {
            val placement = state.placement as? PlacementState.Workspace ?: return null
            val ordinal: Int
            val isNewPage: Boolean
            when (val page = placement.page) {
                is ApplicationPageRef.PersistentPage -> {
                    ordinal = persistentPageOrdinal(page.pageId) ?: return null
                    isNewPage = false
                }

                is ApplicationPageRef.PlannedPage -> {
                    ordinal = plannedPageOrdinal(page.ordinal) ?: return null
                    isNewPage = true
                }
            }
            return PreviewPlacementIdentity.Workspace(
                pageDisplayOrdinal = ordinal,
                isNewPage = isNewPage,
                cellX = placement.cell.x,
                cellY = placement.cell.y,
            )
        }

        /**
         * Issue #208: the single identity -> presentation path. `null` only
         * for a parent title whose identity was not resolved (contract
         * violation); every well-formed identity maps to a presentation.
         */
        fun position(identity: PreviewPlacementIdentity): PreviewPosition? = when (identity) {
            is PreviewPlacementIdentity.Workspace -> workspacePosition(identity)

            is PreviewPlacementIdentity.Dock -> PreviewPosition.DockRank(identity.rank)

            is PreviewPlacementIdentity.FolderChild -> when (val parent = identity.parent) {
                is PreviewPlacementIdentity.PlannedFolder -> plannedFolderNames[parent.ordinal]?.let {
                    PreviewPosition.InFolder(PreviewFolderRef.Planned(parent.ordinal, it), identity.rank + 1)
                }

                else -> labelByIdentity[parent]?.let {
                    PreviewPosition.InFolder(PreviewFolderRef.Existing(it), identity.rank + 1)
                }
            }

            is PreviewPlacementIdentity.AppPairChild -> labelByIdentity[identity.parent]?.let {
                PreviewPosition.InAppPair(it)
            }

            is PreviewPlacementIdentity.Unidentified -> PreviewPosition.Unidentified(identity.proposalLocalDiscriminator)

            is PreviewPlacementIdentity.PlannedFolder -> null
        }

        fun workspacePosition(position: PreviewPlacementIdentity.Workspace): PreviewPosition.Workspace {
            val device = plan.sourceState.deviceCapabilities
            return PreviewPosition.Workspace(
                pageDisplayOrdinal = position.pageDisplayOrdinal,
                isNewPage = position.isNewPage,
                rowBand = band(position.cellY, device.rows, RowBand.entries),
                columnBand = band(position.cellX, device.columns, ColumnBand.entries),
                rowOrdinal = position.cellY + 1,
            )
        }

        fun plannedPageDisplayPosition(ordinal: NewPageOrdinal): Int? = plannedPageOrdinal(ordinal)

        private fun <B : Enum<B>> band(coordinate: Int, dimension: Int, bands: List<B>): B {
            val scaled = if (dimension <= 0) 0 else coordinate * 3 / dimension
            val index = scaled.coerceIn(0, bands.size - 1)
            return bands[index]
        }
    }
}
