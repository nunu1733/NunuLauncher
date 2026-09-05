package app.lawnchair.organizer.planning

internal data class PlacementOutput(
    val placements: List<PlannedPlacement>,
    val newPages: List<NewPage>,
    val newFolders: List<NewFolder>,
    val preservationWarnings: List<Warning>,
)

internal object PlanningPlacement {

    fun place(
        input: OrganizationInput,
        classification: ClassificationOutput,
        strategy: StrategyDefinition,
        allocationFault: AllocationFault = AllocationFault.NONE,
    ): PlacementOutput {
        val rolesById = input.targets.existing.associate { it.item to it.role }
        val pageOrderMap = input.snapshot.pages.associate { it.id to it.order }
        val capturedPagesSorted = input.snapshot.pages.sortedWith(
            compareBy({ it.order }, { it.id }),
        )
        val maxCapturedOrder = input.snapshot.pages.maxOfOrNull { it.order }
        val isIncremental = input.runMode == RunMode.IncrementalPlacement
        // Strategies govern full organization only (spec 182): the incremental
        // run keeps the pre-182 canonical traversal regardless of selection.
        val cellTraversal = if (isIncremental) CellTraversal.TOP_LEFT_ROW_MAJOR else strategy.cellTraversal
        val allocator = Allocator(
            input.snapshot.device,
            capturedPagesSorted,
            maxCapturedOrder,
            allocationFault,
            cellTraversal,
        )

        input.snapshot.reservedWorkspaceRegions.forEach { reservation ->
            allocator.markOccupied(reservation.page, reservation.cell, reservation.span)
        }

        for (item in input.snapshot.items) {
            val reason = determinePreservation(item, rolesById[item.id], input.snapshot.reservedWorkspaceRegions)
            if (reason != null || isIncremental) {
                val ws = item.placement as? CapturedPlacement.Workspace
                if (ws != null) {
                    allocator.markOccupied(PageRef(ws.page.pageId), ws.cell, ws.span)
                }
            }
        }

        val preservationWarnings = mutableListOf<Warning>()
        for (item in input.snapshot.items) {
            if (item.kind == ItemKind.SHORTCUT_LEGACY) {
                preservationWarnings += Warning(
                    WarningCode.LEGACY_SHORTCUT_REVIEW,
                    listOf(DiagnosticParam.ItemParam(item.id)),
                )
            }
            if (item.availability != Availability.AVAILABLE) {
                preservationWarnings += Warning(
                    WarningCode.UNAVAILABLE_PRESERVED,
                    listOf(DiagnosticParam.ItemParam(item.id)),
                )
            }
        }

        return if (input.runMode == RunMode.FullOrganization) {
            val movableItems = input.snapshot.items.filter {
                determinePreservation(it, rolesById[it.id], input.snapshot.reservedWorkspaceRegions) == null
            }
            strategy.placeFullRun(
                FullRunContext(
                    input = input,
                    classification = classification,
                    strategy = strategy,
                    rolesById = rolesById,
                    itemById = input.snapshot.items.associateBy { it.id },
                    movableItems = movableItems,
                    allocator = allocator,
                    pageOrderMap = pageOrderMap,
                    preservationWarnings = preservationWarnings,
                ),
            )
        } else {
            placeIncrementalRun(input, classification, pageOrderMap, allocator, preservationWarnings)
        }
    }

    private fun placeIncrementalRun(
        input: OrganizationInput,
        classification: ClassificationOutput,
        pageOrderMap: Map<PageId, PageOrder>,
        allocator: Allocator,
        preservationWarnings: List<Warning>,
    ): PlacementOutput {
        val items = input.snapshot.items
        val device = input.snapshot.device
        val taxonomy = input.taxonomy
        val rolesById = input.targets.existing.associate { it.item to it.role }
        val candidates = input.targets.additions

        val placements = items.map { item ->
            val reason = determinePreservation(item, rolesById[item.id], input.snapshot.reservedWorkspaceRegions)
            val effectiveReason = reason ?: PreserveReason.ALREADY_CANONICAL
            PlannedPlacement(
                item = item.id,
                disposition = Disposition.Preserved(effectiveReason),
                target = capturedToOutput(item.placement),
            )
        }.toMutableList()

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize

        val eligibleCandidates = candidates.filter { it.availability == Availability.AVAILABLE }
        val folderGroups = formFolderGroups(
            candidates = eligibleCandidates.map { candidate ->
                FolderCandidate(
                    candidate.id,
                    candidate.profile,
                    classification.decisions[candidate.id]?.category ?: taxonomy.fallbackCategory,
                )
            },
            fallbackCategory = taxonomy.fallbackCategory,
            capacity = capacity,
            minGroupSize = minGroupSize,
        )
        val folderMemberIds = folderGroups.flatMapTo(mutableSetOf()) { it.members }

        val outputNewFolders = mutableListOf<NewFolder>()

        data class IncUnit(
            val sortOrdinal: NewFolderOrdinal?,
            val sortProfile: ProfileId,
            val sortCategory: CategoryId,
            val sortItem: ItemId,
            val span: GridSpan,
            val members: List<ItemId>?,
            val profile: ProfileId,
            val candidate: CandidateItem?,
        )

        val incUnits = mutableListOf<IncUnit>()
        for (nf in folderGroups) {
            incUnits += IncUnit(
                sortOrdinal = nf.ordinal,
                sortProfile = nf.profile,
                sortCategory = taxonomy.fallbackCategory,
                sortItem = nf.members.first(),
                span = GridSpan(1, 1),
                members = nf.members,
                profile = nf.profile,
                candidate = null,
            )
        }
        for (candidate in eligibleCandidates.filter { it.id !in folderMemberIds }) {
            incUnits += IncUnit(
                sortOrdinal = null,
                sortProfile = candidate.profile,
                sortCategory = classification.decisions[candidate.id]?.category ?: taxonomy.fallbackCategory,
                sortItem = candidate.id,
                span = candidate.span,
                members = null,
                profile = candidate.profile,
                candidate = candidate,
            )
        }

        val sortedIncUnits = incUnits.sortedWith(
            compareBy<IncUnit> { if (it.sortOrdinal != null) 0 else 1 }
                .thenBy { it.sortOrdinal }
                .thenBy { it.sortProfile }
                .thenBy { it.sortCategory }
                .thenBy { it.sortItem },
        )

        for (unit in sortedIncUnits) {
            val allocated = allocator.allocateCapturedThenNew(unit.span)
            val (pageRef, cell) = allocated ?: error(
                "Validated item ${unit.sortItem} could not be allocated",
            )

            allocator.markOccupied(pageRef, cell, unit.span)

            if (unit.members != null) {
                val nf = folderGroups.single { it.ordinal == unit.sortOrdinal }
                val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                outputNewFolders += NewFolder(
                    ordinal = nf.ordinal,
                    profile = nf.profile,
                    naming = FolderNaming.FromCategory(nf.category),
                    workspacePlacement = wsTarget,
                    members = nf.members,
                )
                for ((rank, memberId) in nf.members.withIndex()) {
                    placements += PlannedPlacement(
                        item = memberId,
                        disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                        target = PlacementTarget.FolderMember(NewFolderRef(nf.ordinal), rank),
                    )
                }
            } else {
                val candidate = unit.candidate!!
                placements += PlannedPlacement(
                    item = candidate.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(pageRef, cell, candidate.span),
                )
            }
        }

        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            newPages = allocator.buildNewPages(),
            newFolders = outputNewFolders.sortedBy { it.ordinal },
            preservationWarnings = preservationWarnings,
        )
    }
}

internal fun determinePreservation(
    item: CapturedItem,
    role: ExistingRole?,
    reservations: List<ReservedWorkspaceRegion>,
): PreserveReason? = when {
    // Issue #185 / ADR-0010: an item whose captured placement overlaps an
    // authoritative reservation is kept exactly where it is, ahead of every
    // other preservation reason — the loader tolerates it only under the
    // current overlap policy, so the planner must neither move it nor
    // allocate anything into the reserved cells.
    (item.placement as? CapturedPlacement.Workspace)
        ?.let { ws -> ReservationOverlapAcceptance.overlaps(ws.page.pageId, ws.cell, ws.span, reservations) } == true -> PreserveReason.RESERVED_REGION

    item.locked -> PreserveReason.LOCKED

    item.availability != Availability.AVAILABLE -> PreserveReason.UNAVAILABLE_TARGET

    item.placement is CapturedPlacement.Dock -> PreserveReason.DOCK

    item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET -> PreserveReason.WIDGET

    item.kind == ItemKind.APP_PAIR || item.placement is CapturedPlacement.AppPairMember -> PreserveReason.APP_PAIR

    item.kind == ItemKind.SHORTCUT_LEGACY -> PreserveReason.LEGACY_SHORTCUT

    role == ExistingRole.Preserved -> PreserveReason.NON_TARGET

    item.placement is CapturedPlacement.FolderMember -> PreserveReason.STRUCTURAL

    else -> null
}

internal fun capturedToOutput(placement: CapturedPlacement): PlacementTarget = when (placement) {
    is CapturedPlacement.Workspace -> PlacementTarget.WorkspaceTarget(placement.page, placement.cell, placement.span)

    is CapturedPlacement.Dock -> PlacementTarget.Dock(placement.rank)

    is CapturedPlacement.FolderMember -> PlacementTarget.FolderMember(placement.folder, placement.rank)

    is CapturedPlacement.AppPairMember -> PlacementTarget.AppPairMember(placement.pair)

    is CapturedPlacement.UnsupportedContainer -> PlacementTarget.WorkspaceTarget(
        PageRef(PageId("invalid")),
        GridCell(0, 0),
        GridSpan(1, 1),
    )
}

internal fun pageRefComparator(pageOrderMap: Map<PageId, PageOrder>): Comparator<PageRef> = Comparator { a, b ->
    val orderA = pageOrderMap[a.pageId]
    val orderB = pageOrderMap[b.pageId]
    if (orderA != null && orderB != null) {
        val cmp = orderA.compareTo(orderB)
        if (cmp != 0) return@Comparator cmp
    }
    a.pageId.compareTo(b.pageId)
}
