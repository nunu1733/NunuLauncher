package app.lawnchair.organizer.planning

/**
 * Shared materialization handed to a strategy's full-run executor
 * (spec 182 internal seam): the constraint/unit/preservation work that is
 * invariant across strategies, computed once by `PlanningPlacement.place`.
 */
internal data class FullRunContext(
    val input: OrganizationInput,
    val classification: ClassificationOutput,
    val strategy: StrategyDefinition,
    val rolesById: Map<ItemId, ExistingRole>,
    val itemById: Map<ItemId, CapturedItem>,
    /** Captured items with no preservation predicate — the strategy's raw material. */
    val movableItems: List<CapturedItem>,
    val allocator: Allocator,
    val pageOrderMap: Map<PageId, PageOrder>,
    val preservationWarnings: List<Warning>,
)

/**
 * The single shared full-run executor (spec 182 internal seam item 4): it
 * branches only on the registered [StrategyDefinition]'s declared fields
 * (createsFolders / eligibleUnitFilter / unitOrder / pageScope; cell traversal
 * is carried by the shared allocator). Strategy semantics that are not yet
 * represented here belong to their own child issue — registering a definition
 * whose declared fields have no executor branch fails loudly instead of
 * silently running another strategy's layout.
 */
internal object FullRunExecution {

    fun execute(context: FullRunContext): PlacementOutput = when (context.strategy.unitOrder) {
        UnitOrdering.CANONICAL_TIE_BREAK -> executeCanonicalPageCompact(context)

        UnitOrdering.CAPTURED_VISUAL_PAGE_LOCAL -> executeStablePageTidy(context)

        UnitOrdering.CAPTURED_VISUAL_GLOBAL -> throw IllegalStateException(
            "UnitOrdering ${context.strategy.unitOrder} has no full-run executor yet (its child issue owns it)",
        )
    }

    /** Shared tail: naturally preserved items keep their captured placement with their precedence reason. */
    private fun appendPreservedPlacements(
        context: FullRunContext,
        placements: MutableList<PlannedPlacement>,
    ) {
        for (item in context.input.snapshot.items) {
            val reason = determinePreservation(item, context.rolesById[item.id], context.input.snapshot.reservedWorkspaceRegions)
            if (reason != null) {
                placements += PlannedPlacement(
                    item = item.id,
                    disposition = Disposition.Preserved(reason),
                    target = capturedToOutput(item.placement),
                )
            }
        }
    }

    private fun executeStablePageTidy(context: FullRunContext): PlacementOutput {
        val input = context.input
        val strategy = context.strategy
        val allocator = context.allocator

        // Spec 182 STABLE_PAGE_TIDY_V1: lift-then-place page-local compaction.
        // Otherwise-movable items the strategy intentionally keeps fixed —
        // existing folders and non-1x1 apps/deep shortcuts — are occupancy
        // constraints reported with the truthful STRATEGY_PRESERVED reason.
        val strategyFixed = context.movableItems.filter { !strategy.eligibleUnitFilter(it) }
        val eligible = context.movableItems.filter { strategy.eligibleUnitFilter(it) }

        val placements = mutableListOf<PlannedPlacement>()
        for (item in strategyFixed) {
            val ws = item.placement as CapturedPlacement.Workspace
            allocator.markOccupied(PageRef(ws.page.pageId), ws.cell, ws.span)
            placements += PlannedPlacement(
                item = item.id,
                disposition = Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
                target = capturedToOutput(item.placement),
            )
        }

        // Per page: eligible 1x1 units in captured visual order (cell.y, cell.x,
        // ItemId) take the earliest free row-major 1x1 cell on their captured
        // page. The captured layout is valid and non-overlapping, so after
        // lifting the eligible units their count never exceeds the page's free
        // cells: every unit is placeable, and a placement failure is a planner
        // invariant violation that fails loudly (never a new page, never a
        // partial plan).
        val byPage = eligible.groupBy { item ->
            (item.placement as CapturedPlacement.Workspace).page
        }
        for ((page, units) in byPage) {
            val ordered = units.sortedWith(
                compareBy(
                    { (it.placement as CapturedPlacement.Workspace).cell.y },
                    { (it.placement as CapturedPlacement.Workspace).cell.x },
                    { it.id },
                ),
            )
            for (item in ordered) {
                val ws = item.placement as CapturedPlacement.Workspace
                val capturedTarget = PlacementTarget.WorkspaceTarget(PageRef(ws.page.pageId), ws.cell, ws.span)
                val allocated = allocator.allocateOnPageOnly(ws.span, PageRef(ws.page.pageId))
                val (pageRef, cell) = allocated ?: error(
                    "STABLE_PAGE_TIDY_V1 could not place eligible unit ${item.id} on its captured page " +
                        "(lift-then-place placeability invariant violated)",
                )
                allocator.markOccupied(pageRef, cell, ws.span)

                val newTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, ws.span)
                val disposition = if (newTarget != capturedTarget) {
                    Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)
                } else {
                    Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
                }
                placements += PlannedPlacement(item.id, disposition, newTarget)
            }
        }

        appendPreservedPlacements(context, placements)

        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            newPages = allocator.buildNewPages(),
            newFolders = emptyList(),
            preservationWarnings = context.preservationWarnings,
        )
    }

    private fun executeCanonicalPageCompact(context: FullRunContext): PlacementOutput {
        val input = context.input
        val strategy = context.strategy
        val itemById = context.itemById
        val device = input.snapshot.device
        val taxonomy = input.taxonomy
        val movableItems = context.movableItems
        val allocator = context.allocator

        val existingFolderUnits = movableItems.filter { it.kind == ItemKind.FOLDER }
        val movableApps = movableItems.filter { strategy.eligibleUnitFilter(it) }

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize

        data class FormedFolder(
            val ordinal: NewFolderOrdinal,
            val profile: ProfileId,
            val naming: FolderNaming,
            val members: List<ItemId>,
            val preferredPage: PageRef,
        )

        val folderGroups = if (strategy.createsFolders) {
            formFolderGroups(
                candidates = movableApps.map { item ->
                    FolderCandidate(
                        item.id,
                        item.profile,
                        context.classification.decisions[item.id]?.category ?: taxonomy.fallbackCategory,
                    )
                },
                fallbackCategory = taxonomy.fallbackCategory,
                capacity = capacity,
                minGroupSize = minGroupSize,
            )
        } else {
            emptyList()
        }
        val newFolders = folderGroups.map { group ->
            val preferredPage = group.members
                .map { id -> (itemById.getValue(id).placement as CapturedPlacement.Workspace).page }
                .minWith(pageRefComparator(context.pageOrderMap))
            FormedFolder(group.ordinal, group.profile, FolderNaming.FromCategory(group.category), group.members, preferredPage)
        }
        val folderMemberIds = folderGroups.flatMapTo(mutableSetOf()) { it.members }

        val singletonItems = movableApps.filter { it.id !in folderMemberIds }

        data class FullUnit(
            val itemId: ItemId,
            val span: GridSpan,
            val preferredPage: PageRef,
            val isFolder: Boolean,
            val sortProfile: ProfileId,
            val sortCategory: CategoryId,
            val isNewFolder: Boolean,
            val newFolderOrdinal: NewFolderOrdinal?,
        )

        val units = mutableListOf<FullUnit>()
        for (folder in existingFolderUnits) {
            val ws = folder.placement as CapturedPlacement.Workspace
            units += FullUnit(
                itemId = folder.id,
                span = ws.span,
                preferredPage = ws.page,
                isFolder = true,
                sortProfile = folder.profile,
                sortCategory = context.classification.decisions[folder.id]?.category ?: taxonomy.fallbackCategory,
                isNewFolder = false,
                newFolderOrdinal = null,
            )
        }
        for (nf in newFolders) {
            units += FullUnit(
                itemId = nf.members.first(),
                span = GridSpan(1, 1),
                preferredPage = nf.preferredPage,
                isFolder = true,
                sortProfile = nf.profile,
                sortCategory = taxonomy.fallbackCategory,
                isNewFolder = true,
                newFolderOrdinal = nf.ordinal,
            )
        }
        for (item in singletonItems) {
            val ws = item.placement as CapturedPlacement.Workspace
            units += FullUnit(
                itemId = item.id,
                span = ws.span,
                preferredPage = ws.page,
                isFolder = false,
                sortProfile = item.profile,
                sortCategory = context.classification.decisions[item.id]?.category ?: taxonomy.fallbackCategory,
                isNewFolder = false,
                newFolderOrdinal = null,
            )
        }

        val pageGroups = units.groupBy { it.preferredPage }
        val sortedPages = pageGroups.keys.sortedWith(pageRefComparator(context.pageOrderMap))

        val placements = mutableListOf<PlannedPlacement>()
        val outputNewFolders = mutableListOf<NewFolder>()

        for (page in sortedPages) {
            val pageUnits = pageGroups.getValue(page)

            val ordered = when (strategy.unitOrder) {
                UnitOrdering.CANONICAL_TIE_BREAK -> {
                    val existingFolders = pageUnits.filter { it.isFolder && !it.isNewFolder }
                        .sortedBy { it.itemId }
                    val newFolderUnits = pageUnits.filter { it.isNewFolder }
                        .sortedBy { it.newFolderOrdinal }
                    val singletons = pageUnits.filter { !it.isFolder }
                        .sortedWith(compareBy({ it.sortProfile }, { it.sortCategory }, { it.itemId }))
                    existingFolders + newFolderUnits + singletons
                }

                UnitOrdering.CAPTURED_VISUAL_PAGE_LOCAL,
                UnitOrdering.CAPTURED_VISUAL_GLOBAL,
                -> throw IllegalStateException(
                    "UnitOrdering ${strategy.unitOrder} has no full-run executor yet (its child issue owns it)",
                )
            }

            for (unit in ordered) {
                val allocated = when (strategy.pageScope) {
                    PageScope.PREFERRED_THEN_NEW -> allocator.allocatePreferred(unit.span, unit.preferredPage)

                    PageScope.CAPTURED_THEN_NEW -> allocator.allocateCapturedThenNew(unit.span)

                    // Declared by page-local strategies; the canonical flow
                    // never selects it. Page-local strategies run through
                    // executeStablePageTidy, whose allocation never creates a
                    // page.
                    PageScope.CAPTURED_PAGE_ONLY -> throw IllegalStateException(
                        "PageScope ${strategy.pageScope} has no canonical-flow executor branch",
                    )
                }
                val (pageRef, cell) = allocated ?: error(
                    "Validated item ${unit.itemId} could not be allocated",
                )

                allocator.markOccupied(pageRef, cell, unit.span)

                if (unit.isNewFolder) {
                    val nf = newFolders.single { it.ordinal == unit.newFolderOrdinal }
                    val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                    outputNewFolders += NewFolder(
                        ordinal = nf.ordinal,
                        profile = nf.profile,
                        naming = nf.naming,
                        workspacePlacement = wsTarget,
                        members = nf.members,
                    )
                    for ((rank, memberId) in nf.members.withIndex()) {
                        placements += PlannedPlacement(
                            item = memberId,
                            disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                            target = PlacementTarget.FolderMember(
                                NewFolderRef(nf.ordinal),
                                rank,
                            ),
                        )
                    }
                } else {
                    val item = itemById[unit.itemId]!!
                    val capturedWs = item.placement as CapturedPlacement.Workspace
                    val capturedTarget = PlacementTarget.WorkspaceTarget(
                        PageRef(capturedWs.page.pageId),
                        capturedWs.cell,
                        capturedWs.span,
                    )
                    val newTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                    val isChanged = newTarget != capturedTarget
                    val disposition = if (isChanged) {
                        Disposition.Moved(
                            if (unit.isFolder) PlacementCode.FOLDER_UNIT else PlacementCode.SINGLE_PLACEMENT,
                        )
                    } else {
                        Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
                    }
                    placements += PlannedPlacement(item.id, disposition, newTarget)
                }
            }
        }

        appendPreservedPlacements(context, placements)

        val sortedPlacements = placements.sortedBy { it.item }
        val sortedNewFolders = outputNewFolders.sortedBy { it.ordinal }

        return PlacementOutput(
            placements = sortedPlacements,
            newPages = allocator.buildNewPages(),
            newFolders = sortedNewFolders,
            preservationWarnings = context.preservationWarnings,
        )
    }
}
