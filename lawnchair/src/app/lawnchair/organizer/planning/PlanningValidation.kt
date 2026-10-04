package app.lawnchair.organizer.planning

internal sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reasons: List<RejectionReason>) : ValidationResult
    data class Impossible(val unplaced: List<UnplacedItem>) : ValidationResult
}

internal object PlanningValidation {

    fun validate(input: OrganizationInput): ValidationResult {
        val reasons = mutableListOf<RejectionReason>()

        reasons += checkInvalidRules(input)
        reasons += checkInvalidDimensions(input)
        reasons += checkDuplicateItemIds(input)
        reasons += checkDuplicatePages(input)
        reasons += checkUnknownItemKind(input)
        reasons += checkInvalidContainer(input)
        reasons += checkUnknownPage(input)
        reasons += checkBoundsViolation(input)
        reasons += checkOverlap(input)
        reasons += checkContainerIntegrity(input)
        reasons += checkMalformedAppPair(input)
        reasons += checkLockedOutOfBounds(input)
        reasons += checkKindTargetMismatch(input)
        reasons += checkTargetProfileMismatch(input)
        reasons += checkUnknownSignalItem(input)
        reasons += checkUnknownCategory(input)
        reasons += checkDuplicateTarget(input)
        reasons += checkMissingTarget(input)
        reasons += checkIncompleteTargetPartition(input)
        reasons += checkAdditionsUnderFull(input)

        if (reasons.isNotEmpty()) {
            return ValidationResult.Invalid(reasons.distinct().sortedWith(rejectionReasonComparator))
        }

        val unplaced = checkCandidates(input)
        if (unplaced.isNotEmpty()) {
            return ValidationResult.Impossible(
                unplaced.sortedWith(
                    compareBy({ it.item }, { it.reason }),
                ),
            )
        }
        return ValidationResult.Valid
    }

    private fun checkInvalidRules(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        if (input.rules.version != RuleVersion("v1")) {
            reasons += RejectionReason(RejectionCode.INVALID_RULES, emptyList())
        }
        if (input.rules.folderPolicy.minGroupSize < 2) {
            reasons += RejectionReason(RejectionCode.INVALID_RULES, emptyList())
        }
        val categories = input.taxonomy.allowedCategories
        if (categories.size != categories.distinct().size) {
            reasons += RejectionReason(RejectionCode.INVALID_RULES, emptyList())
        }
        if (input.taxonomy.fallbackCategory !in categories) {
            reasons += RejectionReason(RejectionCode.INVALID_RULES, emptyList())
        }
        return reasons
    }

    private fun checkInvalidDimensions(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val device = input.snapshot.device
        if (device.columns <= 0) {
            reasons += RejectionReason(
                RejectionCode.INVALID_DIMENSIONS,
                listOf(DiagnosticParam.DimensionParam(DeviceDimension.COLUMNS, device.columns)),
            )
        }
        if (device.rows <= 0) {
            reasons += RejectionReason(
                RejectionCode.INVALID_DIMENSIONS,
                listOf(DiagnosticParam.DimensionParam(DeviceDimension.ROWS, device.rows)),
            )
        }
        if (device.hotseatSlots <= 0) {
            reasons += RejectionReason(
                RejectionCode.INVALID_DIMENSIONS,
                listOf(DiagnosticParam.DimensionParam(DeviceDimension.HOTSEAT_SLOTS, device.hotseatSlots)),
            )
        }
        if (device.folderMaxColumns <= 0) {
            reasons += RejectionReason(
                RejectionCode.INVALID_DIMENSIONS,
                listOf(DiagnosticParam.DimensionParam(DeviceDimension.FOLDER_MAX_COLUMNS, device.folderMaxColumns)),
            )
        }
        if (device.folderMaxRows <= 0) {
            reasons += RejectionReason(
                RejectionCode.INVALID_DIMENSIONS,
                listOf(DiagnosticParam.DimensionParam(DeviceDimension.FOLDER_MAX_ROWS, device.folderMaxRows)),
            )
        }
        for (item in input.snapshot.items) {
            val span = (item.placement as? CapturedPlacement.Workspace)?.span
            if (span != null && (span.width <= 0 || span.height <= 0)) {
                reasons += RejectionReason(RejectionCode.INVALID_DIMENSIONS, listOf(DiagnosticParam.SpanParam(span)))
            }
        }
        for (candidate in input.targets.additions) {
            if (candidate.span.width <= 0 || candidate.span.height <= 0) {
                reasons += RejectionReason(
                    RejectionCode.INVALID_DIMENSIONS,
                    listOf(DiagnosticParam.SpanParam(candidate.span)),
                )
            }
        }
        for (reservation in input.snapshot.reservedWorkspaceRegions) {
            if (reservation.span.width <= 0 || reservation.span.height <= 0) {
                reasons += RejectionReason(
                    RejectionCode.INVALID_DIMENSIONS,
                    listOf(DiagnosticParam.SpanParam(reservation.span)),
                )
            }
        }
        return reasons
    }

    private fun checkDuplicateItemIds(input: OrganizationInput): List<RejectionReason> {
        val ids = input.snapshot.items.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return duplicates.map { RejectionReason(RejectionCode.DUPLICATE_ITEM_ID, listOf(DiagnosticParam.ItemParam(it))) }
    }

    private fun checkDuplicatePages(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val pages = input.snapshot.pages
        val idCounts = pages.groupingBy { it.id }.eachCount()
        idCounts.filterValues { it > 1 }.keys.forEach { pid ->
            reasons += RejectionReason(RejectionCode.DUPLICATE_PAGE, listOf(DiagnosticParam.PageParam(pid)))
        }
        pages.groupBy { it.order }.filterValues { it.size > 1 }.values.flatten().forEach { page ->
            reasons += RejectionReason(
                RejectionCode.DUPLICATE_PAGE,
                listOf(DiagnosticParam.PageParam(page.id)),
            )
        }
        return reasons
    }

    private fun checkUnknownItemKind(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        for (item in input.snapshot.items) {
            val unknown = item.kind as? ItemKind.Unknown
            if (unknown != null) {
                reasons += RejectionReason(
                    RejectionCode.UNKNOWN_ITEM_KIND,
                    listOf(DiagnosticParam.KindParam(unknown.code)),
                )
            }
        }
        return reasons
    }

    private fun checkInvalidContainer(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        for (item in input.snapshot.items) {
            val unsupported = item.placement as? CapturedPlacement.UnsupportedContainer
            if (unsupported != null) {
                reasons += RejectionReason(
                    RejectionCode.INVALID_CONTAINER,
                    listOf(DiagnosticParam.ContainerCodeParam(unsupported.code)),
                )
            }
        }
        return reasons
    }

    private fun checkUnknownPage(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val pageIds = input.snapshot.pages.map { it.id }.toSet()
        for (item in input.snapshot.items) {
            val ws = item.placement as? CapturedPlacement.Workspace ?: continue
            if (ws.page.pageId !in pageIds) {
                reasons += RejectionReason(
                    RejectionCode.UNKNOWN_PAGE,
                    listOf(DiagnosticParam.PageParam(ws.page.pageId)),
                )
            }
        }
        for (reservation in input.snapshot.reservedWorkspaceRegions) {
            if (reservation.page.pageId !in pageIds) {
                reasons += RejectionReason(
                    RejectionCode.UNKNOWN_PAGE,
                    listOf(DiagnosticParam.PageParam(reservation.page.pageId)),
                )
            }
        }
        return reasons
    }

    private fun checkBoundsViolation(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val device = input.snapshot.device
        for (item in input.snapshot.items) {
            if (item.locked) continue
            val ws = item.placement as? CapturedPlacement.Workspace ?: continue
            if (ws.span.width <= 0 || ws.span.height <= 0) continue
            if (isOutOfBounds(ws, device)) {
                reasons += RejectionReason(
                    RejectionCode.BOUNDS_VIOLATION,
                    listOf(DiagnosticParam.SpanParam(ws.span)),
                )
            }
        }
        for (reservation in input.snapshot.reservedWorkspaceRegions) {
            if (reservation.span.width <= 0 || reservation.span.height <= 0) continue
            if (isOutOfBounds(reservation.cell, reservation.span, device)) {
                reasons += RejectionReason(
                    RejectionCode.BOUNDS_VIOLATION,
                    listOf(DiagnosticParam.SpanParam(reservation.span)),
                )
            }
        }
        for (item in input.snapshot.items) {
            val invalidRank = when (val placement = item.placement) {
                is CapturedPlacement.Dock -> placement.rank.takeIf {
                    it < 0 || it >= device.hotseatSlots
                }

                is CapturedPlacement.FolderMember -> placement.rank.takeIf { it < 0 }

                else -> null
            }
            if (invalidRank != null) {
                reasons += RejectionReason(
                    RejectionCode.BOUNDS_VIOLATION,
                    listOf(DiagnosticParam.RankParam(invalidRank)),
                )
            }
        }
        return reasons
    }

    private fun checkOverlap(input: OrganizationInput): List<RejectionReason> {
        val byPage = mutableMapOf<PageId, MutableList<CapturedItem>>()
        for (item in input.snapshot.items) {
            val ws = item.placement as? CapturedPlacement.Workspace ?: continue
            if (ws.span.width <= 0 || ws.span.height <= 0) continue
            byPage.getOrPut(ws.page.pageId) { mutableListOf() }.add(item)
        }
        var hasOverlap = false
        for ((_, occupants) in byPage) {
            for (i in occupants.indices) {
                for (j in i + 1 until occupants.size) {
                    val aWs = occupants[i].placement as CapturedPlacement.Workspace
                    val bWs = occupants[j].placement as CapturedPlacement.Workspace
                    if (rectanglesOverlap(aWs.cell, aWs.span, bWs.cell, bWs.span)) {
                        hasOverlap = true
                    }
                }
            }
        }
        for (reservation in input.snapshot.reservedWorkspaceRegions) {
            if (reservation.span.width <= 0 || reservation.span.height <= 0) continue
            val occupants = byPage[reservation.page.pageId].orEmpty()
            if (occupants.any { item ->
                    val ws = item.placement as? CapturedPlacement.Workspace
                    ws != null && rectanglesOverlap(reservation.cell, reservation.span, ws.cell, ws.span)
                }
            ) {
                hasOverlap = true
            }
        }
        val reservations = input.snapshot.reservedWorkspaceRegions
        for (i in reservations.indices) {
            for (j in i + 1 until reservations.size) {
                val a = reservations[i]
                val b = reservations[j]
                if (a.page.pageId == b.page.pageId &&
                    a.span.width > 0 && a.span.height > 0 && b.span.width > 0 && b.span.height > 0 &&
                    rectanglesOverlap(a.cell, a.span, b.cell, b.span)
                ) {
                    hasOverlap = true
                }
            }
        }
        val duplicateDockRank = input.snapshot.items
            .mapNotNull { (it.placement as? CapturedPlacement.Dock)?.rank }
            .groupingBy { it }
            .eachCount()
            .any { it.value > 1 }
        val duplicateFolderRank = input.snapshot.items
            .mapNotNull { item ->
                (item.placement as? CapturedPlacement.FolderMember)?.let {
                    it.folder.folderId to it.rank
                }
            }
            .groupingBy { it }
            .eachCount()
            .any { it.value > 1 }
        return if (hasOverlap || duplicateDockRank || duplicateFolderRank) {
            listOf(RejectionReason(RejectionCode.OVERLAP, emptyList()))
        } else {
            emptyList()
        }
    }

    private fun checkContainerIntegrity(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val items = input.snapshot.items
        val itemById = items.groupBy { it.id }

        fun dangling(item: ItemId) = RejectionReason(
            RejectionCode.DANGLING_REFERENCE,
            listOf(DiagnosticParam.ItemParam(item)),
        )

        val folderParents = items
            .filter { it.kind == ItemKind.FOLDER && it.folderId != null }
            .groupBy { it.folderId!! }
        val appPairParents = items
            .filter { it.kind == ItemKind.APP_PAIR && it.appPairId != null }
            .groupBy { it.appPairId!! }

        folderParents.filterValues { it.size > 1 }.values.flatten().forEach { parent ->
            reasons += dangling(parent.id)
        }
        appPairParents.filterValues { it.size > 1 }.values.flatten().forEach { parent ->
            reasons += dangling(parent.id)
        }

        val itemToFolders = mutableMapOf<ItemId, MutableSet<FolderId>>()
        for ((folderId, parents) in folderParents) {
            if (parents.size != 1) continue
            val parent = parents.single()
            val memberCounts = parent.members.groupingBy { it }.eachCount()
            memberCounts.filterValues { it > 1 }.keys.forEach { member ->
                reasons += dangling(member)
            }
            val distinctMembers = parent.members.distinct()
            for (memberId in distinctMembers) {
                itemToFolders.getOrPut(memberId) { mutableSetOf() }.add(folderId)
                val memberItems = itemById[memberId]
                if (memberItems == null || memberItems.size != 1) {
                    reasons += dangling(memberId)
                    continue
                }
                val member = memberItems.single()
                val fmPlacement = member.placement as? CapturedPlacement.FolderMember
                if (fmPlacement == null || fmPlacement.folder.folderId != folderId) {
                    reasons += dangling(member.id)
                }
                if (member.kind != ItemKind.APPLICATION && member.kind != ItemKind.DEEP_SHORTCUT) {
                    reasons += dangling(member.id)
                }
            }
        }
        itemToFolders.filterValues { it.size > 1 }.keys.forEach { member ->
            reasons += dangling(member)
        }

        for (item in items) {
            val fm = item.placement as? CapturedPlacement.FolderMember ?: continue
            val parents = folderParents[fm.folder.folderId]
            if (parents == null || parents.size != 1) {
                reasons += dangling(item.id)
            } else {
                val parent = parents.single()
                if (item.id !in parent.members) {
                    reasons += dangling(item.id)
                }
                if (item.kind != ItemKind.APPLICATION && item.kind != ItemKind.DEEP_SHORTCUT) {
                    reasons += dangling(item.id)
                }
            }
        }

        for (item in items) {
            val apm = item.placement as? CapturedPlacement.AppPairMember ?: continue
            val parents = appPairParents[apm.pair.appPairId]
            if (parents == null || parents.size != 1) {
                reasons += dangling(item.id)
            }
        }

        detectFolderCycles(items, folderParents).forEach { folderId ->
            folderParents[folderId].orEmpty().forEach { parent -> reasons += dangling(parent.id) }
        }

        return reasons
    }

    private fun detectFolderCycles(
        items: List<CapturedItem>,
        folderParents: Map<FolderId, List<CapturedItem>>,
    ): Set<FolderId> {
        val folderIdByItem = items
            .filter { it.folderId != null }
            .associate { it.id to it.folderId!! }
        val edges = mutableMapOf<FolderId, FolderId>()
        for (item in items) {
            val childFolderId = folderIdByItem[item.id] ?: continue
            val fm = item.placement as? CapturedPlacement.FolderMember ?: continue
            val parentFolderId = fm.folder.folderId
            if (folderParents.containsKey(childFolderId) && folderParents.containsKey(parentFolderId)) {
                edges[childFolderId] = parentFolderId
            }
        }
        val cyclic = mutableSetOf<FolderId>()
        for (start in edges.keys) {
            val seen = mutableSetOf<FolderId>()
            var current: FolderId? = start
            while (current != null && seen.add(current)) {
                current = edges[current]
            }
            if (current != null) cyclic.add(start)
        }
        return cyclic
    }

    private fun checkMalformedAppPair(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        for (item in input.snapshot.items) {
            if (item.kind != ItemKind.APP_PAIR) continue
            val metadata = item.appPair ?: continue
            val pairId = item.appPairId
            val members = metadata.members
            var malformed = false
            if (members.size != 2) malformed = true
            if (members.size == 2) {
                if (members[0].item == members[1].item) malformed = true
                if (members[0].stage == members[1].stage) malformed = true
                if (members[0].snapPosition != members[1].snapPosition) malformed = true
                if (members[0].snapPosition == null) malformed = true
            }
            for (memberEntry in members) {
                val memberItems = input.snapshot.items.filter { it.id == memberEntry.item }
                if (memberItems.size != 1) {
                    malformed = true
                    continue
                }
                val member = memberItems.single()
                if (member.kind != ItemKind.APPLICATION && member.kind != ItemKind.DEEP_SHORTCUT) {
                    malformed = true
                }
                if (pairId != null) {
                    val apm = member.placement as? CapturedPlacement.AppPairMember
                    if (apm == null || apm.pair.appPairId != pairId) {
                        malformed = true
                    }
                }
            }
            if (pairId != null) {
                val reverseMembers = input.snapshot.items
                    .filter {
                        (it.placement as? CapturedPlacement.AppPairMember)?.pair?.appPairId == pairId
                    }
                    .map { it.id }
                    .sorted()
                if (reverseMembers != members.map { it.item }.sorted()) {
                    malformed = true
                }
            }
            if (malformed) {
                reasons += RejectionReason(
                    RejectionCode.MALFORMED_APP_PAIR,
                    listOf(DiagnosticParam.ItemParam(item.id)),
                )
            }
        }
        return reasons
    }

    private fun checkLockedOutOfBounds(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val device = input.snapshot.device
        for (item in input.snapshot.items) {
            if (!item.locked) continue
            val ws = item.placement as? CapturedPlacement.Workspace ?: continue
            if (ws.span.width <= 0 || ws.span.height <= 0) continue
            if (isOutOfBounds(ws, device)) {
                reasons += RejectionReason(
                    RejectionCode.LOCKED_OUT_OF_BOUNDS,
                    listOf(DiagnosticParam.SpanParam(ws.span)),
                )
            }
        }
        return reasons
    }

    private fun checkKindTargetMismatch(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        for (item in input.snapshot.items) {
            val mismatch = when (item.kind) {
                ItemKind.APPLICATION -> item.target !is TargetKey.AppKey

                ItemKind.DEEP_SHORTCUT -> item.target !is TargetKey.ShortcutKey

                ItemKind.SHORTCUT_LEGACY -> item.target !is TargetKey.LegacyShortcutKey

                ItemKind.FOLDER -> {
                    val target = item.target as? TargetKey.FolderKey
                    target == null || item.folderId == null || target.folderId != item.folderId
                }

                ItemKind.APPWIDGET, ItemKind.CUSTOM_APPWIDGET -> item.target !is TargetKey.WidgetKey

                ItemKind.APP_PAIR -> {
                    val target = item.target as? TargetKey.AppPairKey
                    target == null || item.appPairId == null || target.appPairId != item.appPairId || item.appPair == null
                }

                is ItemKind.Unknown -> false
            }
            if (mismatch) {
                reasons += RejectionReason(RejectionCode.KIND_TARGET_MISMATCH, listOf(DiagnosticParam.ItemParam(item.id)))
            }
            if (item.kind != ItemKind.FOLDER) {
                if (item.folderId != null || item.members.isNotEmpty()) {
                    reasons += RejectionReason(RejectionCode.KIND_TARGET_MISMATCH, listOf(DiagnosticParam.ItemParam(item.id)))
                }
            }
            if (item.kind != ItemKind.APP_PAIR) {
                if (item.appPairId != null || item.appPair != null) {
                    reasons += RejectionReason(RejectionCode.KIND_TARGET_MISMATCH, listOf(DiagnosticParam.ItemParam(item.id)))
                }
            }
        }
        for (candidate in input.targets.additions) {
            val mismatch = when (candidate.kind) {
                CandidateKind.APPLICATION -> candidate.target !is CandidateTarget.AppKey
                CandidateKind.DEEP_SHORTCUT -> candidate.target !is CandidateTarget.ShortcutKey
            }
            if (mismatch) {
                reasons += RejectionReason(RejectionCode.KIND_TARGET_MISMATCH, listOf(DiagnosticParam.ItemParam(candidate.id)))
            }
        }
        return reasons
    }

    private fun checkTargetProfileMismatch(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        for (item in input.snapshot.items) {
            val targetProfile = when (item.target) {
                is TargetKey.AppKey -> item.target.profile
                is TargetKey.ShortcutKey -> item.target.profile
                is TargetKey.WidgetKey -> item.target.profile
                else -> null
            }
            if (targetProfile != null && targetProfile != item.profile) {
                reasons += RejectionReason(RejectionCode.TARGET_PROFILE_MISMATCH, listOf(DiagnosticParam.ItemParam(item.id)))
            }
        }
        for (candidate in input.targets.additions) {
            val targetProfile = when (candidate.target) {
                is CandidateTarget.AppKey -> candidate.target.profile
                is CandidateTarget.ShortcutKey -> candidate.target.profile
            }
            if (targetProfile != candidate.profile) {
                reasons += RejectionReason(RejectionCode.TARGET_PROFILE_MISMATCH, listOf(DiagnosticParam.ItemParam(candidate.id)))
            }
        }
        return reasons
    }

    private fun checkUnknownSignalItem(input: OrganizationInput): List<RejectionReason> {
        val knownItems = input.snapshot.items.map { it.id }.toSet() + input.targets.additions.map { it.id }.toSet()
        return input.signals.entries
            .filter { it.item !in knownItems }
            .map { RejectionReason(RejectionCode.UNKNOWN_SIGNAL_ITEM, listOf(DiagnosticParam.ItemParam(it.item))) }
            .distinct()
    }

    private fun checkUnknownCategory(input: OrganizationInput): List<RejectionReason> {
        val allowed = input.taxonomy.allowedCategories.toSet()
        return input.signals.entries
            .filter { it.candidate !in allowed }
            .map { RejectionReason(RejectionCode.UNKNOWN_CATEGORY, listOf(DiagnosticParam.CategoryParam(it.candidate))) }
            .distinct()
    }

    private fun checkDuplicateTarget(input: OrganizationInput): List<RejectionReason> {
        val reasons = mutableListOf<RejectionReason>()
        val existingIds = input.targets.existing.map { it.item }
        val existingDuplicates = existingIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        existingDuplicates.forEach { id ->
            reasons += RejectionReason(RejectionCode.DUPLICATE_TARGET, listOf(DiagnosticParam.ItemParam(id)))
        }
        val additionIds = input.targets.additions.map { it.id }
        val additionDuplicates = additionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        additionDuplicates.forEach { id ->
            reasons += RejectionReason(RejectionCode.DUPLICATE_TARGET, listOf(DiagnosticParam.ItemParam(id)))
        }
        val capturedIds = input.snapshot.items.map { it.id }.toSet()
        for (candidate in input.targets.additions) {
            if (candidate.id in capturedIds) {
                reasons += RejectionReason(RejectionCode.DUPLICATE_TARGET, listOf(DiagnosticParam.ItemParam(candidate.id)))
            }
        }
        return reasons
    }

    private fun checkMissingTarget(input: OrganizationInput): List<RejectionReason> {
        val capturedIds = input.snapshot.items.map { it.id }.toSet()
        return input.targets.existing
            .filter { it.item !in capturedIds }
            .map { RejectionReason(RejectionCode.MISSING_TARGET, listOf(DiagnosticParam.ItemParam(it.item))) }
            .distinct()
    }

    private fun checkIncompleteTargetPartition(input: OrganizationInput): List<RejectionReason> {
        val existingIds = input.targets.existing.map { it.item }.toSet()
        return if (input.snapshot.items.any { it.id !in existingIds }) {
            listOf(RejectionReason(RejectionCode.INCOMPLETE_TARGET_PARTITION, emptyList()))
        } else {
            emptyList()
        }
    }

    private fun checkAdditionsUnderFull(input: OrganizationInput): List<RejectionReason> {
        return if (input.runMode == RunMode.FullOrganization && input.targets.additions.isNotEmpty()) {
            listOf(RejectionReason(RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION, emptyList()))
        } else {
            emptyList()
        }
    }

    private fun checkCandidates(input: OrganizationInput): List<UnplacedItem> {
        val device = input.snapshot.device
        val unplaced = mutableListOf<UnplacedItem>()
        for (candidate in input.targets.additions) {
            if (candidate.span.width > device.columns || candidate.span.height > device.rows) {
                unplaced += UnplacedItem(candidate.id, candidate.span, UnplacedReason.EXCEEDS_GRID_DIMENSIONS)
            }
            if (candidate.availability != Availability.AVAILABLE) {
                unplaced += UnplacedItem(candidate.id, candidate.span, UnplacedReason.TARGET_UNAVAILABLE)
            }
        }
        return unplaced
    }

    private fun rectanglesOverlap(a: GridCell, aSpan: GridSpan, b: GridCell, bSpan: GridSpan): Boolean = a.x.toLong() < b.x.toLong() + bSpan.width.toLong() &&
        b.x.toLong() < a.x.toLong() + aSpan.width.toLong() &&
        a.y.toLong() < b.y.toLong() + bSpan.height.toLong() &&
        b.y.toLong() < a.y.toLong() + aSpan.height.toLong()

    private fun isOutOfBounds(
        workspace: CapturedPlacement.Workspace,
        device: DeviceCapabilities,
    ): Boolean = isOutOfBounds(workspace.cell, workspace.span, device)

    private fun isOutOfBounds(
        cell: GridCell,
        span: GridSpan,
        device: DeviceCapabilities,
    ): Boolean = cell.x < 0 || cell.y < 0 ||
        cell.x.toLong() + span.width.toLong() > device.columns.toLong() ||
        cell.y.toLong() + span.height.toLong() > device.rows.toLong()
}

internal val rejectionReasonComparator: Comparator<RejectionReason> = Comparator { a, b ->
    compareValues(a.code.ordinal, b.code.ordinal).takeIf { it != 0 }
        ?: compareDiagnosticParamLists(a.params, b.params)
}

internal fun compareDiagnosticParamLists(a: List<DiagnosticParam>, b: List<DiagnosticParam>): Int {
    val minSize = minOf(a.size, b.size)
    for (i in 0 until minSize) {
        val cmp = compareDiagnosticParams(a[i], b[i])
        if (cmp != 0) return cmp
    }
    return a.size - b.size
}

private fun compareDiagnosticParams(a: DiagnosticParam, b: DiagnosticParam): Int {
    val typeCmp = compareValues(diagnosticParamTypeRank(a), diagnosticParamTypeRank(b))
    if (typeCmp != 0) return typeCmp
    return when {
        a is DiagnosticParam.ItemParam && b is DiagnosticParam.ItemParam -> a.item.compareTo(b.item)

        a is DiagnosticParam.KindParam && b is DiagnosticParam.KindParam -> a.code.compareTo(b.code)

        a is DiagnosticParam.ContainerCodeParam && b is DiagnosticParam.ContainerCodeParam -> a.code.compareTo(b.code)

        a is DiagnosticParam.SpanParam && b is DiagnosticParam.SpanParam ->
            compareValues(a.span.width, b.span.width).takeIf { it != 0 } ?: compareValues(a.span.height, b.span.height)

        a is DiagnosticParam.RankParam && b is DiagnosticParam.RankParam -> compareValues(a.rank, b.rank)

        a is DiagnosticParam.DimensionParam && b is DiagnosticParam.DimensionParam ->
            compareValues(a.dimension.ordinal, b.dimension.ordinal).takeIf { it != 0 } ?: compareValues(a.value, b.value)

        a is DiagnosticParam.PageParam && b is DiagnosticParam.PageParam -> a.page.compareTo(b.page)

        a is DiagnosticParam.CategoryParam && b is DiagnosticParam.CategoryParam -> a.category.compareTo(b.category)

        else -> 0
    }
}

private fun diagnosticParamTypeRank(param: DiagnosticParam): Int = when (param) {
    is DiagnosticParam.ItemParam -> 0
    is DiagnosticParam.KindParam -> 1
    is DiagnosticParam.ContainerCodeParam -> 2
    is DiagnosticParam.SpanParam -> 3
    is DiagnosticParam.RankParam -> 4
    is DiagnosticParam.DimensionParam -> 5
    is DiagnosticParam.PageParam -> 6
    is DiagnosticParam.CategoryParam -> 7
}
