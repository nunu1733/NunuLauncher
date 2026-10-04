package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
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
    ): Result {
        if (result.revision != input.snapshot.revision ||
            result.ruleVersion != input.rules.version ||
            result.taxonomyVersion != input.taxonomy.version
        ) {
            return Result.Invalid
        }
        val planned = result.outcome as? Planned ?: return Result.Invalid
        if (ActionMaterializer.validateOrdinals(planned) !is ActionMaterializer.OrdinalValidation.Ok) return Result.Invalid
        if (planned.placements.any { overlapsReservation(it.target, input.snapshot.reservedWorkspaceRegions) } ||
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
            newFolder(folder, sourceState, plannedPageOrdinals) ?: return Result.Invalid
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
    ): CanonicalItemState? {
        val availability = sourceState.profiles.firstOrNull { it.id == folder.profile }?.availability ?: return null
        val workspace = placementState(folder.workspacePlacement, placeholderFolder(folder, availability), plannedPageOrdinals)
            as? PlacementState.Workspace ?: return null
        return placeholderFolder(folder, availability).copy(placement = workspace)
    }

    private fun placeholderFolder(folder: NewFolder, availability: ProfileAvailability) = CanonicalItemState(
        ref = ApplicationItemRef.PlannedFolder(folder.ordinal),
        kind = CanonicalItemKind.Folder,
        targetKey = TargetKey.FolderKey(FolderId("planned-folder-${folder.ordinal.value}")),
        profile = folder.profile,
        profileAvailability = availability,
        itemAvailability = if (availability == ProfileAvailability.AVAILABLE) ItemAvailability.AVAILABLE else ItemAvailability.UNAVAILABLE,
        placement = PlacementState.UnsupportedContainer(app.lawnchair.organizer.planning.ContainerCode(Int.MIN_VALUE)),
        title = OptionalText.Present("Folder"),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.FolderMembers(emptyList()),
    )
}
