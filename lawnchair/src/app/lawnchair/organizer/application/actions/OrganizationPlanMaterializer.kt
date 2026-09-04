package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderRef
import app.lawnchair.organizer.planning.NewPageRef
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.TargetKey

/**
 * Bridges the exact planner artifact for one captured [OrganizationInput] to
 * the accepted layout-application input. The bridge is deliberately strict:
 * malformed, incomplete, stale, destructive, or structurally inconsistent
 * plans return [Result.Invalid] and never reach the application protocol.
 */
internal object OrganizationPlanMaterializer {
    sealed interface Result {
        data class Ready(val plan: ValidatedLayoutPlan) : Result
        data object Invalid : Result
    }

    fun materialize(
        input: OrganizationInput,
        result: PlanningResult,
        sourceState: LayoutState,
        titleResolver: FolderTitleResolver,
    ): Result {
        if (result.revision != input.snapshot.revision ||
            result.ruleVersion != input.rules.version ||
            result.taxonomyVersion != input.taxonomy.version
        ) {
            return Result.Invalid
        }
        val planned = result.outcome as? Planned ?: return Result.Invalid
        if (ActionMaterializer.validateOrdinals(planned) !is ActionMaterializer.OrdinalValidation.Ok) return Result.Invalid
        // Issue #185 / ADR-0010: the reservation guard protects the planner's
        // targets. A preserved item's target is its captured placement, so an
        // authoritative-reservation overlap is accepted only when the target
        // keeps that item exactly where it was captured; anything that moves
        // into a reserved cell stays invalid. This is a plan-time guard — the
        // A5/recovery exact preconditions are not relaxed by it.
        val capturedByItem = input.snapshot.items.associate { it.id to it.placement }
        if (
            planned.placements.any { placement ->
                overlapsReservation(placement.target, input.snapshot.reservedWorkspaceRegions) &&
                    !targetPreservesCapturedWorkspace(placement.target, capturedByItem[placement.item])
            } ||
            planned.newFolders.any { overlapsReservation(it.workspacePlacement, input.snapshot.reservedWorkspaceRegions) }
        ) {
            return Result.Invalid
        }

        val sourceItems = sourceState.items.associateBy { (it.ref as? ApplicationItemRef.PersistentItem)?.itemId }
        if (sourceItems.size != sourceState.items.size) return Result.Invalid
        val snapshotIds = input.snapshot.items.map { it.id }.toSet()
        if (snapshotIds.size != input.snapshot.items.size || snapshotIds != sourceItems.keys.filterNotNull().toSet()) return Result.Invalid
        if (planned.placements.map { it.item }.toSet() != snapshotIds || planned.placements.distinctBy { it.item }.size != planned.placements.size) {
            return Result.Invalid
        }

        val plannedPageOrdinals = planned.newPages.map { it.ordinal }.toSet()
        val originalItems = linkedMapOf<ApplicationItemRef, CanonicalItemState>()
        for (placement in planned.placements) {
            val original = sourceItems[placement.item] ?: return Result.Invalid
            val intendedPlacement = placementState(placement.target, original, plannedPageOrdinals) ?: return Result.Invalid
            originalItems[original.ref] = original.copy(placement = intendedPlacement)
        }

        val folderItems = planned.newFolders.map { folder ->
            // Issue #201: resolve exactly once per planned folder. The resolved
            // title is frozen into the plan (creation-time locale snapshot) and
            // preview and apply both read this value without re-resolving.
            val resolvedTitle = titleResolver.resolve(folder.naming)
            if (resolvedTitle.isBlank()) return Result.Invalid
            newFolder(folder, sourceState, plannedPageOrdinals, resolvedTitle) ?: return Result.Invalid
        }
        val allItems = (originalItems.values + folderItems).toMutableList()
        val parentMembers = allItems
            .mapNotNull { child ->
                val placement = child.placement as? PlacementState.FolderChild ?: return@mapNotNull null
                placement.parent to RankedMember(child.ref, placement.rank)
            }
            .groupBy({ it.first }, { it.second })

        if (parentMembers.any { (parent, members) ->
                members.map { it.rank }.distinct().size != members.size ||
                    allItems.none { it.ref == parent && it.kind is CanonicalItemKind.Folder }
            }
        ) {
            return Result.Invalid
        }
        val rebuiltItems = allItems.map { item ->
            val members = parentMembers[item.ref].orEmpty().sortedBy { it.rank }
            when {
                item.ref is ApplicationItemRef.PlannedFolder -> {
                    if (members.isEmpty()) return Result.Invalid
                    item.copy(structure = StructureState.FolderMembers(members))
                }

                item.kind is CanonicalItemKind.Folder -> item.copy(structure = StructureState.FolderMembers(members))

                else -> item
            }
        }

        val intendedState = sourceState.copy(
            pages = (sourceState.pages + planned.newPages.map { PageState(ApplicationPageRef.PlannedPage(it.ordinal), it.order) })
                .sortedBy { it.order },
            items = rebuiltItems,
        )
        val intendedByRef = intendedState.items.associateBy { it.ref }
        val actions = buildList {
            sourceState.items.forEach { source ->
                val intended = intendedByRef[source.ref] ?: return Result.Invalid
                if (intended == source) {
                    add(ApplyAction.Preserve(source.ref, source))
                } else {
                    add(ApplyAction.Update(source.ref, source, intended))
                }
            }
            folderItems.forEach { folder ->
                val intended = intendedByRef[folder.ref] ?: return Result.Invalid
                add(ApplyAction.Insert(folder.ref, intended))
            }
        }
        if (actions.any { it !is ApplyAction.Preserve && it !is ApplyAction.Update && it !is ApplyAction.Insert }) return Result.Invalid

        return Result.Ready(
            ValidatedLayoutPlan(
                sourceRevision = input.snapshot.revision,
                sourceState = sourceState,
                intendedState = intendedState,
                actions = actions,
                newPages = planned.newPages,
                newFolders = planned.newFolders,
                ruleVersion = input.rules.version,
                taxonomyVersion = input.taxonomy.version,
            ),
        )
    }

    private fun overlapsReservation(
        target: PlacementTarget,
        reservations: List<app.lawnchair.organizer.planning.ReservedWorkspaceRegion>,
    ): Boolean {
        val workspace = target as? PlacementTarget.WorkspaceTarget ?: return false
        val page = workspace.page as? PageRef ?: return false
        return reservations.any { reservation ->
            reservation.page == page && rectanglesOverlap(
                reservation.cell,
                reservation.span,
                workspace.cell,
                workspace.span,
            )
        }
    }

    /**
     * Issue #185 / ADR-0010: true only when the planned target reproduces the
     * item's captured workspace placement — the exact in-place preservation the
     * composer projects for a reservation-overlapping item.
     */
    private fun targetPreservesCapturedWorkspace(
        target: PlacementTarget,
        captured: CapturedPlacement?,
    ): Boolean {
        if (captured !is CapturedPlacement.Workspace) return false
        val workspace = target as? PlacementTarget.WorkspaceTarget ?: return false
        val page = workspace.page as? PageRef ?: return false
        return page == captured.page &&
            workspace.cell == captured.cell &&
            workspace.span == captured.span
    }

    private fun rectanglesOverlap(
        aCell: app.lawnchair.organizer.planning.GridCell,
        aSpan: app.lawnchair.organizer.planning.GridSpan,
        bCell: app.lawnchair.organizer.planning.GridCell,
        bSpan: app.lawnchair.organizer.planning.GridSpan,
    ): Boolean = aCell.x.toLong() < bCell.x.toLong() + bSpan.width.toLong() &&
        bCell.x.toLong() < aCell.x.toLong() + aSpan.width.toLong() &&
        aCell.y.toLong() < bCell.y.toLong() + bSpan.height.toLong() &&
        bCell.y.toLong() < aCell.y.toLong() + aSpan.height.toLong()

    private fun placementState(
        target: PlacementTarget,
        source: CanonicalItemState,
        plannedPageOrdinals: Set<app.lawnchair.organizer.planning.NewPageOrdinal>,
    ): PlacementState? = when (target) {
        is PlacementTarget.WorkspaceTarget -> PlacementState.Workspace(
            page = when (val page = target.page) {
                is PageRef -> ApplicationPageRef.PersistentPage(page.pageId)
                is NewPageRef -> page.ordinal.takeIf { it in plannedPageOrdinals }?.let(ApplicationPageRef::PlannedPage) ?: return null
            },
            cell = target.cell,
            span = target.span,
        )

        is PlacementTarget.Dock -> PlacementState.Dock(target.rank)

        is PlacementTarget.FolderMember -> PlacementState.FolderChild(
            parent = when (val folder = target.folder) {
                is FolderRef -> ApplicationItemRef.PersistentItem(folder.folderId.let { app.lawnchair.organizer.planning.ItemId(it.value) })
                is NewFolderRef -> ApplicationItemRef.PlannedFolder(folder.ordinal)
            },
            rank = target.rank,
        )

        is PlacementTarget.AppPairMember -> {
            val existing = source.placement as? PlacementState.AppPairChild ?: return null
            if (existing.parent !is ApplicationItemRef.PersistentItem ||
                (existing.parent as ApplicationItemRef.PersistentItem).itemId.value != target.pair.appPairId.value
            ) {
                return null
            }
            existing
        }
    }

    private fun newFolder(
        folder: NewFolder,
        sourceState: LayoutState,
        plannedPageOrdinals: Set<app.lawnchair.organizer.planning.NewPageOrdinal>,
        resolvedTitle: String,
    ): CanonicalItemState? {
        val availability = sourceState.profiles.firstOrNull { it.id == folder.profile }?.availability ?: return null
        val base = placeholderFolder(folder, availability, resolvedTitle)
        val workspace = placementState(folder.workspacePlacement, base, plannedPageOrdinals)
            as? PlacementState.Workspace ?: return null
        return base.copy(placement = workspace)
    }

    private fun placeholderFolder(
        folder: NewFolder,
        availability: ProfileAvailability,
        resolvedTitle: String,
    ) = CanonicalItemState(
        ref = ApplicationItemRef.PlannedFolder(folder.ordinal),
        kind = CanonicalItemKind.Folder,
        targetKey = TargetKey.FolderKey(FolderId("planned-folder-${folder.ordinal.value}")),
        profile = folder.profile,
        profileAvailability = availability,
        itemAvailability = if (availability == ProfileAvailability.AVAILABLE) ItemAvailability.AVAILABLE else ItemAvailability.UNAVAILABLE,
        placement = PlacementState.UnsupportedContainer(app.lawnchair.organizer.planning.ContainerCode(Int.MIN_VALUE)),
        title = OptionalText.Present(resolvedTitle),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.FolderMembers(emptyList()),
    )
}
