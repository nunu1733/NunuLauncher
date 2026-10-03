package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PlanPreviewDiagrams
import app.lawnchair.organizer.application.public.PreviewDiagram
import app.lawnchair.organizer.application.public.PreviewDiagramItem
import app.lawnchair.organizer.application.public.PreviewDiagramItemRef
import app.lawnchair.organizer.application.public.PreviewDiagramPage
import app.lawnchair.organizer.application.public.PreviewDiagramPageRef
import app.lawnchair.organizer.application.public.PreviewDiagramRegion
import app.lawnchair.organizer.application.public.PreviewExcludableItem
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProposalExclusionKey

/**
 * Issue #508: pure projection of one materialized plan into the confirmation
 * before/after diagrams plus the exclusion surface. `before` projects the
 * plan's `sourceState`, `after` its `intendedState`, so the picture and the
 * change list are two views of the same plan object — never an independent
 * capture. No I/O, no clock, no randomness: identical inputs produce
 * identical output. A structural mismatch (a planned page where the capture
 * cannot have one, an excludable member missing from the source state) is a
 * contract violation and fails closed via [Result.Invalid], exactly like the
 * row projection.
 */
object PlanDiagramProjector {

    sealed interface Result {
        data class Ready(
            val diagrams: PlanPreviewDiagrams,
            val excludableItems: List<PreviewExcludableItem>,
        ) : Result

        data object Invalid : Result
    }

    fun project(plan: ValidatedLayoutPlan, input: OrganizationInput): Result {
        val before = diagram(plan.sourceState, plannedPagesAllowed = false) ?: return Result.Invalid
        val after = diagram(plan.intendedState, plannedPagesAllowed = true) ?: return Result.Invalid
        val excludable = excludableItems(plan, input) ?: return Result.Invalid
        return Result.Ready(PlanPreviewDiagrams(before = before, after = after), excludable)
    }

    /**
     * Top-level diagram of one layout state: workspace items on their pages
     * (display order by `PageOrder`), dock items by rank, folder/app-pair
     * members represented by the container item's member count, and reserved
     * regions drawn but never treated as items.
     */
    private fun diagram(state: LayoutState, plannedPagesAllowed: Boolean): PreviewDiagram? {
        val pageRefById = buildMap<ApplicationPageRef, PreviewDiagramPageRef> {
            for (page in state.pages) {
                val ref = when (val applicationRef = page.ref) {
                    is ApplicationPageRef.PersistentPage -> PreviewDiagramPageRef.Persistent(applicationRef.pageId)

                    is ApplicationPageRef.PlannedPage -> {
                        if (!plannedPagesAllowed) return null
                        PreviewDiagramPageRef.Planned(applicationRef.ordinal)
                    }
                }
                put(page.ref, ref)
            }
        }
        val workspaceItemsByPage = state.items
            .mapNotNull { item ->
                val placement = item.placement as? PlacementState.Workspace ?: return@mapNotNull null
                // An item on a page the state does not declare is a contract
                // violation (a capture can never carry a planned page).
                if (placement.page !in pageRefById) return null
                placement.page to item
            }
            .groupBy({ it.first }, { it.second })
        val pages = state.pages
            .sortedBy { it.order }
            .map { page ->
                PreviewDiagramPage(
                    ref = pageRefById.getValue(page.ref),
                    items = workspaceItemsByPage[page.ref].orEmpty().map { item ->
                        diagramItem(item) ?: return null
                    },
                )
            }
        val dockItems = state.items
            .filter { it.placement is PlacementState.Dock }
            .sortedBy { (it.placement as PlacementState.Dock).rank }
            .map { item -> diagramItem(item) ?: return null }
        return PreviewDiagram(
            columns = state.deviceCapabilities.columns,
            rows = state.deviceCapabilities.rows,
            pages = pages,
            dockItems = dockItems,
            reservedRegions = state.reservedWorkspaceRegions.map { reservation ->
                PreviewDiagramRegion(
                    page = pageRefById[persistentPageRef(reservation)] ?: return null,
                    cell = reservation.cell,
                    span = reservation.span,
                )
            },
        )
    }

    /** Reservations live on persisted pages only; a planned one fails closed. */
    private fun persistentPageRef(reservation: app.lawnchair.organizer.planning.ReservedWorkspaceRegion): ApplicationPageRef = ApplicationPageRef.PersistentPage((reservation.page as PageRef).pageId)

    private fun diagramItem(item: CanonicalItemState): PreviewDiagramItem? {
        // Dock items share the item model with a nominal cell; only
        // workspace items carry real geometry.
        val (cell, span) = when (val placement = item.placement) {
            is PlacementState.Workspace -> placement.cell to placement.span
            is PlacementState.Dock -> GridCell(0, 0) to GridSpan(1, 1)
            else -> return null
        }
        val ref = when (val itemRef = item.ref) {
            is ApplicationItemRef.PersistentItem -> PreviewDiagramItemRef.Persistent(itemRef.itemId)
            is ApplicationItemRef.PlannedCandidate -> PreviewDiagramItemRef.PlannedCandidate(itemRef.itemId)
            is ApplicationItemRef.PlannedFolder -> PreviewDiagramItemRef.PlannedFolder(itemRef.ordinal)
        }
        val memberCount = when (val structure = item.structure) {
            is StructureState.FolderMembers -> structure.members.size
            else -> if (item.kind is CanonicalItemKind.Folder) 0 else null
        }
        return PreviewDiagramItem(
            ref = ref,
            label = itemLabel(item),
            kind = item.kind,
            cell = cell,
            span = span,
            memberCount = memberCount,
        )
    }

    private fun itemLabel(state: CanonicalItemState): PreviewLabel = when (val title = state.title) {
        is OptionalText.Present -> PreviewLabel.Named(title.value)
        OptionalText.Absent -> PreviewLabel.KindFallback(state.kind)
    }

    /**
     * The exclusion surface of this proposal (spec D-3): existing members the
     * input marks `Movable` whose captured placement is a top-level workspace
     * app/deep-shortcut, plus every placed candidate (an unplaced candidate
     * has no Add row to act on — the overflow warning path owns it). A
     * `Movable` member missing from the source state is a join contract
     * violation and fails closed.
     */
    private fun excludableItems(plan: ValidatedLayoutPlan, input: OrganizationInput): List<PreviewExcludableItem>? {
        val sourceById = plan.sourceState.items.mapNotNull { item ->
            (item.ref as? ApplicationItemRef.PersistentItem)?.let { it.itemId to item }
        }.toMap()
        val existing = mutableListOf<PreviewExcludableItem>()
        for (membership in input.targets.existing) {
            if (membership.role != ExistingRole.Movable) continue
            val item = sourceById[membership.item] ?: return null
            val excludable = item.placement is PlacementState.Workspace &&
                (item.kind == CanonicalItemKind.Application || item.kind == CanonicalItemKind.DeepShortcut)
            if (!excludable) continue
            existing += PreviewExcludableItem(
                key = ProposalExclusionKey.Existing(membership.item),
                label = itemLabel(item),
                kind = item.kind,
                isCandidate = false,
            )
        }
        val candidateInserts = plan.actions.filterIsInstance<ApplyAction.Insert>()
            .mapNotNull { insert ->
                (insert.ref as? ApplicationItemRef.PlannedCandidate)?.let { it.itemId to insert.intended }
            }
            .toMap()
        val candidates = input.targets.additions.mapNotNull { addition ->
            val intended = candidateInserts[addition.id] ?: return@mapNotNull null
            PreviewExcludableItem(
                key = ProposalExclusionKey.Candidate(addition.id),
                label = itemLabel(intended),
                kind = intended.kind,
                isCandidate = true,
            )
        }
        return existing + candidates
    }
}
