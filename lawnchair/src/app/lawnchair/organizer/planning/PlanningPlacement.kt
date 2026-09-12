package app.lawnchair.organizer.planning

internal data class PlacementOutput(
    val placements: List<PlannedPlacement>,
    val newPages: List<NewPage>,
    val newFolders: List<NewFolder>,
    val preservationWarnings: List<Warning>,
    /** Issue #228: candidates the strategy's scope could not place (composed run only). */
    val unplaced: List<UnplacedItem> = emptyList(),
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
        // Issue #235: widget relocation applies to the strategy-governed run
        // modes (full organization and the scope-composed full-run phase);
        // the incremental candidate tail keeps widgets fixed.
        val relocateWidgets = !isIncremental && strategy.widgetPolicy != null
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
            val reason = determinePreservation(item, rolesById[item.id], input.snapshot.reservedWorkspaceRegions, relocateWidgets)
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

        return when (input.runMode) {
            RunMode.FullOrganization -> strategy.placeFullRun(
                FullRunContext(
                    input = input,
                    classification = classification,
                    strategy = strategy,
                    rolesById = rolesById,
                    itemById = input.snapshot.items.associateBy { it.id },
                    movableItems = input.snapshot.items.filter {
                        determinePreservation(it, rolesById[it.id], input.snapshot.reservedWorkspaceRegions, relocateWidgets) == null
                    },
                    allocator = allocator,
                    pageOrderMap = pageOrderMap,
                    preservationWarnings = preservationWarnings,
                ),
            )

            RunMode.ScopeComposedOrganization -> placeScopeComposedRun(
                input,
                classification,
                strategy,
                rolesById,
                pageOrderMap,
                allocator,
                preservationWarnings,
                relocateWidgets,
            )

            RunMode.IncrementalPlacement -> placeIncrementalRun(input, classification, pageOrderMap, allocator, preservationWarnings)
        }
    }

    /**
     * Issue #228 (D-2, `ScopeComposedOrganization`): the existing layout is
     * re-organized by exactly the selected strategy's full-run executor
     * first; the selected candidates are then appended under the *same*
     * strategy semantics ([StrategyDefinition.createsFolders] and
     * [StrategyDefinition.pageScope] are consumed, never bypassed — review
     * P1): folder formation only when the strategy creates folders, and
     * allocation only within the strategy's declared page scope. A unit the
     * strategy cannot place within its scope is reported unplaced
     * (`UnplacedReason.STRATEGY_SCOPE_FULL`) instead of the strategy
     * violating its own semantics. With an empty selection the output is
     * byte-equivalent to the full-organization run over the same input.
     * Candidate folder groups continue the new-folder ordinal sequence after
     * the existing items' folders, so planned-folder identities never
     * collide.
     */
    private fun placeScopeComposedRun(
        input: OrganizationInput,
        classification: ClassificationOutput,
        strategy: StrategyDefinition,
        rolesById: Map<ItemId, ExistingRole>,
        pageOrderMap: Map<PageId, PageOrder>,
        allocator: Allocator,
        preservationWarnings: List<Warning>,
        relocateWidgets: Boolean,
    ): PlacementOutput {
        val fullOutput = strategy.placeFullRun(
            FullRunContext(
                input = input,
                classification = classification,
                strategy = strategy,
                rolesById = rolesById,
                itemById = input.snapshot.items.associateBy { it.id },
                movableItems = input.snapshot.items.filter {
                    determinePreservation(it, rolesById[it.id], input.snapshot.reservedWorkspaceRegions, relocateWidgets) == null
                },
                allocator = allocator,
                pageOrderMap = pageOrderMap,
                preservationWarnings = preservationWarnings,
            ),
        )
        val placements = fullOutput.placements.toMutableList()
        val newFolders = fullOutput.newFolders.toMutableList()
        val unplacedCandidates = appendCandidatePlacements(
            input,
            classification,
            strategy,
            allocator,
            placements,
            newFolders,
            folderOrdinalOffset = fullOutput.newFolders.size,
        )
        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            // Cumulative on the shared allocator: the full run's pages plus
            // any page appended for candidate overflow.
            newPages = allocator.buildNewPages(),
            newFolders = newFolders.sortedBy { it.ordinal },
            preservationWarnings = preservationWarnings,
            unplaced = unplacedCandidates,
        )
    }

    private fun placeIncrementalRun(
        input: OrganizationInput,
        classification: ClassificationOutput,
        pageOrderMap: Map<PageId, PageOrder>,
        allocator: Allocator,
        preservationWarnings: List<Warning>,
    ): PlacementOutput {
        val items = input.snapshot.items
        val rolesById = input.targets.existing.associate { it.item to it.role }

        val placements = items.map { item ->
            val reason = determinePreservation(item, rolesById[item.id], input.snapshot.reservedWorkspaceRegions)
            val effectiveReason = reason ?: PreserveReason.ALREADY_CANONICAL
            PlannedPlacement(
                item = item.id,
                disposition = Disposition.Preserved(effectiveReason),
                target = capturedToOutput(item.placement),
            )
        }.toMutableList()

        val newFolders = mutableListOf<NewFolder>()
        // The incremental run keeps its pre-182 canonical tail semantics:
        // folders allowed, captured pages first, overflow onto new pages.
        appendCandidatePlacements(input, classification, incrementalCandidateStrategy, allocator, placements, newFolders, folderOrdinalOffset = 0)

        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            newPages = allocator.buildNewPages(),
            newFolders = newFolders.sortedBy { it.ordinal },
            preservationWarnings = preservationWarnings,
        )
    }

    /**
     * Issue #228: the candidate-tail semantics of the pre-#228 incremental
     * run (the behavior its tests pin) expressed as strategy data: folder
     * formation allowed, captured pages first with new-page overflow. The
     * scope-composed run passes the user-selected strategy instead, so its
     * `createsFolders`/`pageScope` decisions are consumed rather than this
     * default.
     */
    private val incrementalCandidateStrategy = StrategyDefinition(
        identity = StrategyId("__INCREMENTAL_CANDIDATE_TAIL__"),
        createsFolders = true,
        eligibleUnitFilter = { true },
        unitOrder = UnitOrdering.CANONICAL_TIE_BREAK,
        pageScope = PageScope.CAPTURED_THEN_NEW,
        cellTraversal = CellTraversal.TOP_LEFT_ROW_MAJOR,
        placeFullRun = { error("tail-only semantics; never a full-run executor") },
    )

    /**
     * Shared candidate-unit placement (issue #228): forms same-profile
     * same-category folder groups plus singleton units from the validated
     * additions and allocates them under [strategy]'s declared page scope.
     * Used by both the incremental run (whose pre-182 semantics are the
     * canonical captured-then-new tail) and the scope-composed run, which
     * passes the user-selected strategy so its `createsFolders`/`pageScope`
     * semantics are consumed rather than bypassed (review P1). A unit that
     * cannot be placed within the scope (no free captured cell under
     * `CAPTURED_PAGE_ONLY`, or folder formation under `createsFolders=false`
     * with no singleton fit) is returned as unplaced
     * (`UnplacedReason.STRATEGY_SCOPE_FULL`) instead of the strategy
     * violating its own page/folder scope. [folderOrdinalOffset] continues
     * the new-folder ordinal sequence after any folders the caller's
     * existing-item placement already created.
     */
    private fun appendCandidatePlacements(
        input: OrganizationInput,
        classification: ClassificationOutput,
        strategy: StrategyDefinition,
        allocator: Allocator,
        placements: MutableList<PlannedPlacement>,
        newFolders: MutableList<NewFolder>,
        folderOrdinalOffset: Int,
    ): List<UnplacedItem> {
        val device = input.snapshot.device
        val taxonomy = input.taxonomy
        val candidates = input.targets.additions
        val unplaced = mutableListOf<UnplacedItem>()

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize

        val eligibleCandidates = candidates.filter { it.availability == Availability.AVAILABLE }
        // Folder formation is a strategy decision, not a constant (review P1):
        // strategies that never create folders keep every candidate a
        // singleton unit.
        val folderGroups = if (strategy.createsFolders) {
            formFolderGroups(
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
        } else {
            emptyList()
        }.map { group ->
            if (folderOrdinalOffset == 0) {
                group
            } else {
                group.copy(ordinal = NewFolderOrdinal(group.ordinal.value + folderOrdinalOffset))
            }
        }
        val folderMemberIds = folderGroups.flatMapTo(mutableSetOf()) { it.members }

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
            // Page scope is a strategy decision too (review P1): the
            // incremental tail's captured-then-new overflow belongs to the
            // canonical-family strategies and the incremental run; a
            // page-local strategy (`CAPTURED_PAGE_ONLY`) keeps its candidates
            // on captured pages and reports the overflow as unplaced instead.
            // A CAPTURED_THEN_NEW/PREFERRED_THEN_NEW allocation returning null
            // is only possible under the injected allocation fault, which
            // stays a loud invariant failure — never a silent unplaced row.
            val allocated = when (strategy.pageScope) {
                PageScope.CAPTURED_THEN_NEW, PageScope.PREFERRED_THEN_NEW -> allocator.allocateCapturedThenNew(unit.span)
                    ?: error("Validated item ${unit.sortItem} could not be allocated")

                PageScope.CAPTURED_PAGE_ONLY -> allocator.allocateCapturedPageOnly(unit.span)
            }
            val (pageRef, cell) = allocated ?: run {
                val unplacedUnitIds = unit.members ?: listOf(unit.sortItem)
                unplacedUnitIds.forEach { id ->
                    unplaced += UnplacedItem(id, unit.span, UnplacedReason.STRATEGY_SCOPE_FULL)
                }
                return@run null
            } ?: continue

            allocator.markOccupied(pageRef, cell, unit.span)

            if (unit.members != null) {
                val nf = folderGroups.single { it.ordinal == unit.sortOrdinal }
                val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                newFolders += NewFolder(
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
        return unplaced
    }
}

internal fun determinePreservation(
    item: CapturedItem,
    role: ExistingRole?,
    reservations: List<ReservedWorkspaceRegion>,
    relocateWidgets: Boolean = false,
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

    // Issue #235: the widget branch is terminal for widget kinds. Under a
    // widget-capable strategy an unlocked, available, top-level widget is
    // movable for the widget stream REGARDLESS of its target-set role — the
    // production composer marks every widget `ExistingRole.Preserved` by kind
    // (widgets are never user-selected organization targets), so a role-based
    // exclusion would make widget relocation unreachable in production
    // (found by the AC-10 device evaluation). Higher-precedence reasons above
    // (reserved overlap, lock, unavailability, Dock) still fix such widgets.
    (item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET) -> {
        if (relocateWidgets) null else PreserveReason.WIDGET
    }

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
