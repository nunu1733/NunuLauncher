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
    ): PlacementOutput {
        val rolesById = input.targets.existing.associate { it.item to it.role }
        val pageOrderMap = input.snapshot.pages.associate { it.id to it.order }
        val capturedPagesSorted = input.snapshot.pages.sortedWith(
            compareBy({ it.order }, { it.id }),
        )
        val maxCapturedOrder = input.snapshot.pages.maxOfOrNull { it.order }
        val allocator = Allocator(input.snapshot.device, capturedPagesSorted, maxCapturedOrder)
        val isIncremental = input.runMode == RunMode.IncrementalPlacement

        for (item in input.snapshot.items) {
            val reason = determinePreservation(item, rolesById[item.id])
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
            placeFullRun(input, classification, rolesById, pageOrderMap, allocator, preservationWarnings)
        } else {
            placeIncrementalRun(input, classification, pageOrderMap, allocator, preservationWarnings)
        }
    }

    private fun placeFullRun(
        input: OrganizationInput,
        classification: ClassificationOutput,
        rolesById: Map<ItemId, ExistingRole>,
        pageOrderMap: Map<PageId, PageOrder>,
        allocator: Allocator,
        preservationWarnings: List<Warning>,
    ): PlacementOutput {
        val items = input.snapshot.items
        val itemById = items.associateBy { it.id }
        val device = input.snapshot.device
        val taxonomy = input.taxonomy

        val movableItems = items.filter { determinePreservation(it, rolesById[it.id]) == null }
        val existingFolderUnits = movableItems.filter { it.kind == ItemKind.FOLDER }
        val movableApps = movableItems.filter {
            it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT
        }

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize

        data class FormedFolder(
            val ordinal: NewFolderOrdinal,
            val profile: ProfileId,
            val members: List<ItemId>,
            val preferredPage: PageRef,
        )

        val folderGroups = formFolderGroups(
            candidates = movableApps.map { item ->
                FolderCandidate(
                    item.id,
                    item.profile,
                    classification.decisions[item.id]?.category ?: taxonomy.fallbackCategory,
                )
            },
            fallbackCategory = taxonomy.fallbackCategory,
            capacity = capacity,
            minGroupSize = minGroupSize,
        )
        val newFolders = folderGroups.map { group ->
            val preferredPage = group.members
                .map { id -> (itemById.getValue(id).placement as CapturedPlacement.Workspace).page }
                .minWith(pageRefComparator(pageOrderMap))
            FormedFolder(group.ordinal, group.profile, group.members, preferredPage)
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
                sortCategory = classification.decisions[folder.id]?.category ?: taxonomy.fallbackCategory,
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
                sortCategory = classification.decisions[item.id]?.category ?: taxonomy.fallbackCategory,
                isNewFolder = false,
                newFolderOrdinal = null,
            )
        }

        val pageGroups = units.groupBy { it.preferredPage }
        val sortedPages = pageGroups.keys.sortedWith(pageRefComparator(pageOrderMap))

        val placements = mutableListOf<PlannedPlacement>()
        val outputNewFolders = mutableListOf<NewFolder>()

        for (page in sortedPages) {
            val pageUnits = pageGroups.getValue(page)

            val existingFolders = pageUnits.filter { it.isFolder && !it.isNewFolder }
                .sortedBy { it.itemId }
            val newFolderUnits = pageUnits.filter { it.isNewFolder }
                .sortedBy { it.newFolderOrdinal }
            val singletons = pageUnits.filter { !it.isFolder }
                .sortedWith(compareBy({ it.sortProfile }, { it.sortCategory }, { it.itemId }))

            val ordered = existingFolders + newFolderUnits + singletons

            for (unit in ordered) {
                val allocated = allocator.allocatePreferred(unit.span, unit.preferredPage)
                val (pageRef, cell) = allocated ?: continue

                allocator.markOccupied(pageRef, cell, unit.span)

                if (unit.isNewFolder) {
                    val nf = newFolders.single { it.ordinal == unit.newFolderOrdinal }
                    val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                    outputNewFolders += NewFolder(
                        ordinal = nf.ordinal,
                        profile = nf.profile,
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

        for (item in items) {
            val reason = determinePreservation(item, rolesById[item.id])
            if (reason != null) {
                placements += PlannedPlacement(
                    item = item.id,
                    disposition = Disposition.Preserved(reason),
                    target = capturedToOutput(item.placement),
                )
            }
        }

        val sortedPlacements = placements.sortedBy { it.item }
        val sortedNewFolders = outputNewFolders.sortedBy { it.ordinal }

        return PlacementOutput(
            placements = sortedPlacements,
            newPages = allocator.buildNewPages(),
            newFolders = sortedNewFolders,
            preservationWarnings = preservationWarnings,
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
        val device = input.snapshot.device
        val taxonomy = input.taxonomy
        val rolesById = input.targets.existing.associate { it.item to it.role }
        val candidates = input.targets.additions

        val placements = items.map { item ->
            val reason = determinePreservation(item, rolesById[item.id])
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
            val (pageRef, cell) = allocated ?: continue

            allocator.markOccupied(pageRef, cell, unit.span)

            if (unit.members != null) {
                val nf = folderGroups.single { it.ordinal == unit.sortOrdinal }
                val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                outputNewFolders += NewFolder(
                    ordinal = nf.ordinal,
                    profile = nf.profile,
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

    private fun partitionMembers(
        members: List<ItemId>,
        effectiveCapacity: Int,
        minGroupSize: Int,
    ): List<List<ItemId>> {
        if (members.size <= effectiveCapacity) {
            return listOf(members)
        }

        val folders = mutableListOf<List<ItemId>>()
        var index = 0
        while (index + effectiveCapacity <= members.size) {
            folders += members.subList(index, index + effectiveCapacity).toList()
            index += effectiveCapacity
        }

        val remainder = members.size - index
        if (remainder == 0) {
            return folders
        }

        val remainderItems = members.subList(index, members.size).toList()

        if (remainder >= minGroupSize) {
            folders += remainderItems
            return folders
        }

        val needed = minGroupSize - remainder
        if (folders.isNotEmpty()) {
            val preceding = folders.last()
            val precedingNewSize = preceding.size - needed
            if (precedingNewSize >= minGroupSize) {
                folders[folders.lastIndex] = preceding.subList(0, precedingNewSize)
                folders += preceding.subList(precedingNewSize, preceding.size) + remainderItems
                return folders
            }
        }

        return folders
    }

    private data class FolderCandidate(
        val item: ItemId,
        val profile: ProfileId,
        val category: CategoryId,
    )

    private data class FolderGroup(
        val ordinal: NewFolderOrdinal,
        val profile: ProfileId,
        val members: List<ItemId>,
    )

    private fun formFolderGroups(
        candidates: List<FolderCandidate>,
        fallbackCategory: CategoryId,
        capacity: Long,
        minGroupSize: Int,
    ): List<FolderGroup> {
        if (capacity < minGroupSize.toLong()) return emptyList()

        val groups = candidates
            .groupBy { it.profile to it.category }
            .toSortedMap(compareBy({ it.first }, { it.second }))
        val result = mutableListOf<FolderGroup>()
        var ordinal = 0
        for ((key, groupCandidates) in groups) {
            val (profile, category) = key
            val members = groupCandidates.map { it.item }.sorted()
            if (category == fallbackCategory || members.size < minGroupSize) continue

            val effectiveCapacity = minOf(capacity, members.size.toLong()).toInt()
            for (folderMembers in partitionMembers(members, effectiveCapacity, minGroupSize)) {
                result += FolderGroup(NewFolderOrdinal(ordinal++), profile, folderMembers)
            }
        }
        return result
    }

    private fun determinePreservation(item: CapturedItem, role: ExistingRole?): PreserveReason? = when {
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

    private fun capturedToOutput(placement: CapturedPlacement): PlacementTarget = when (placement) {
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

    private fun pageRefComparator(pageOrderMap: Map<PageId, PageOrder>): Comparator<PageRef> = Comparator { a, b ->
        val orderA = pageOrderMap[a.pageId]
        val orderB = pageOrderMap[b.pageId]
        if (orderA != null && orderB != null) {
            val cmp = orderA.compareTo(orderB)
            if (cmp != 0) return@Comparator cmp
        }
        a.pageId.compareTo(b.pageId)
    }
}

private data class Rect(val x: Long, val y: Long, val width: Long, val height: Long) {
    val right: Long get() = x + width
    val bottom: Long get() = y + height
}

private class Allocator(
    val device: DeviceCapabilities,
    val capturedPages: List<Page>,
    val maxCapturedOrder: PageOrder?,
) {
    private val occupancy = mutableMapOf<PageTargetRef, MutableList<Rect>>()
    private val newPages = mutableListOf<NewPage>()
    private var nextNewPageOrder: PageOrder = if (maxCapturedOrder != null) maxCapturedOrder + 1 else PageOrder(0)

    fun markOccupied(page: PageTargetRef, cell: GridCell, span: GridSpan) {
        occupancy.getOrPut(page) { mutableListOf() }
            .add(Rect(cell.x.toLong(), cell.y.toLong(), span.width.toLong(), span.height.toLong()))
    }

    fun allocatePreferred(span: GridSpan, preferredPage: PageRef): Pair<PageTargetRef, GridCell>? {
        val occupied = occupancy[preferredPage] ?: emptyList()
        val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span)
        if (cell != null) return preferredPage to cell
        return allocateOnNewPages(span)
    }

    fun allocateCapturedThenNew(span: GridSpan): Pair<PageTargetRef, GridCell>? {
        for (page in capturedPages) {
            val ref: PageTargetRef = PageRef(page.id)
            val occupied = occupancy[ref] ?: emptyList()
            val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span)
            if (cell != null) return ref to cell
        }
        return allocateOnNewPages(span)
    }

    private fun allocateOnNewPages(span: GridSpan): Pair<PageTargetRef, GridCell>? {
        for (np in newPages) {
            val ref: PageTargetRef = NewPageRef(np.ordinal)
            val occupied = occupancy[ref] ?: emptyList()
            val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span)
            if (cell != null) return ref to cell
        }
        val ordinal = NewPageOrdinal(newPages.size)
        val order = nextNewPageOrder
        nextNewPageOrder = nextNewPageOrder + 1
        newPages += NewPage(ordinal, order)
        val ref: PageTargetRef = NewPageRef(ordinal)
        val cell = findRowMajorFirstFit(emptyList(), device.columns, device.rows, span)
        return if (cell != null) ref to cell else null
    }

    fun buildNewPages(): List<NewPage> = newPages.toList()
}

private fun findRowMajorFirstFit(
    occupied: List<Rect>,
    columns: Int,
    rows: Int,
    span: GridSpan,
): GridCell? {
    val w = span.width.toLong()
    val h = span.height.toLong()
    val cols = columns.toLong()
    val rws = rows.toLong()

    if (w > cols || h > rws) return null

    val candidateYs = (listOf(0L) + occupied.map { it.bottom }).distinct().sorted()

    for (y in candidateYs) {
        if (y + h > rws) break

        val blockingIntervals = occupied
            .filter { it.y < y + h && y < it.bottom }
            .map { it.x to it.right }
            .sortedBy { it.first }

        var cursor = 0L
        for ((start, end) in blockingIntervals) {
            if (start - cursor >= w) {
                return GridCell(cursor.toInt(), y.toInt())
            }
            cursor = maxOf(cursor, end)
        }
        if (cursor + w <= cols) {
            return GridCell(cursor.toInt(), y.toInt())
        }
    }
    return null
}
