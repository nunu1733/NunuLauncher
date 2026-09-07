package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewFolderRef
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.NewPageRef
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.Warning

internal data class OracleFinding(
    val check: ContractCheck,
    val subject: FindingSubject,
    val message: String,
)

internal sealed interface FindingSubject {
    data class Item(val id: ItemId) : FindingSubject
    data class Page(val id: PageId) : FindingSubject
    data class Folder(val id: FolderId) : FindingSubject
    data class AppPair(val id: AppPairId) : FindingSubject
    data class NewPage(val ordinal: NewPageOrdinal) : FindingSubject
    data class NewFolder(val ordinal: NewFolderOrdinal) : FindingSubject
    data object None : FindingSubject
}

internal object Oracle {
    fun matchesExpectedFamilyAndEcho(
        input: OrganizationInput,
        result: PlanningResult,
        expectation: FixtureExpectation,
    ): Boolean = result.revision == input.snapshot.revision &&
        result.ruleVersion == input.rules.version &&
        result.taxonomyVersion == input.taxonomy.version &&
        when (expectation.outcome) {
            is ExpectedOutcome.Planned -> result.outcome is Planned
            is ExpectedOutcome.Invalid -> result.outcome is Rejected.Invalid
            is ExpectedOutcome.Impossible -> result.outcome is Rejected.Impossible
        }

    fun checkExpectation(
        input: OrganizationInput,
        result: PlanningResult,
        expectation: FixtureExpectation,
    ): List<OracleFinding> = buildList {
        fun mismatch(message: String) = add(finding(ContractCheck.EXPECTATION, FindingSubject.None, message))
        if (result.revision != input.snapshot.revision) mismatch("Revision echo mismatch")
        if (result.ruleVersion != input.rules.version) mismatch("Rule-version echo mismatch")
        if (result.taxonomyVersion != input.taxonomy.version) mismatch("Taxonomy-version echo mismatch")

        val actualWarnings = warningsOf(result.outcome).map(Warning::code).toSet()
        val missingWarnings = expectation.requiredWarningCodes - actualWarnings
        if (missingWarnings.isNotEmpty()) mismatch("Missing warning codes: ${missingWarnings.sortedBy { it.ordinal }}")

        when (val expected = expectation.outcome) {
            is ExpectedOutcome.Planned -> {
                val actual = result.outcome as? Planned
                if (actual == null) {
                    mismatch("Expected Planned but was ${result.outcome::class.simpleName}")
                    return@buildList
                }
                expected.requiredPreservations.forEach { (item, reason) ->
                    val placement = actual.placements.singleOrNull { it.item == item }
                    val disposition = placement?.disposition
                    if (disposition !is Disposition.Preserved || disposition.reason != reason) {
                        add(finding(ContractCheck.EXPECTATION, FindingSubject.Item(item), "Required preservation $reason was absent"))
                    }
                }
                val missingCategories = expected.requiredCategories - actual.categories.toSet()
                missingCategories.forEach {
                    add(finding(ContractCheck.EXPECTATION, FindingSubject.Item(it.item), "Required category decision was absent: $it"))
                }
                if (expected.expectedNewPageCount != null && actual.newPages.size != expected.expectedNewPageCount) {
                    mismatch("Expected ${expected.expectedNewPageCount} new pages but was ${actual.newPages.size}")
                }
                if (expected.expectedNewFolderCount != null && actual.newFolders.size != expected.expectedNewFolderCount) {
                    mismatch("Expected ${expected.expectedNewFolderCount} new folders but was ${actual.newFolders.size}")
                }
            }

            is ExpectedOutcome.Invalid -> {
                val actual = result.outcome as? Rejected.Invalid
                if (actual == null) {
                    mismatch("Expected Invalid but was ${result.outcome::class.simpleName}")
                    return@buildList
                }
                val missingCodes = expected.requiredCodes - actual.reasons.map { it.code }.toSet()
                if (missingCodes.isNotEmpty()) mismatch("Missing rejection codes: ${missingCodes.sortedBy { it.ordinal }}")
                val missingDetails = expected.requiredDetails - actual.reasons.toSet()
                if (missingDetails.isNotEmpty()) mismatch("Missing rejection details: $missingDetails")
            }

            is ExpectedOutcome.Impossible -> {
                val actual = result.outcome as? Rejected.Impossible
                if (actual == null) {
                    mismatch("Expected Impossible but was ${result.outcome::class.simpleName}")
                    return@buildList
                }
                val missingReasons = expected.requiredReasons - actual.unplaced.map { it.reason }.toSet()
                if (missingReasons.isNotEmpty()) mismatch("Missing unplaced reasons: ${missingReasons.sortedBy { it.ordinal }}")
                val missingItems = expected.requiredItems - actual.unplaced.toSet()
                if (missingItems.isNotEmpty()) mismatch("Missing unplaced items: $missingItems")
            }
        }
    }

    fun checkConservation(input: OrganizationInput, planned: Planned): List<OracleFinding> = buildList {
        val expected = (input.snapshot.items.map { it.id } + input.targets.additions.map { it.id }).toSet()
        val counts = planned.placements.groupingBy { it.item }.eachCount()
        expected.forEach { item ->
            when (val count = counts[item] ?: 0) {
                0 -> add(finding(ContractCheck.CONSERVATION, FindingSubject.Item(item), "Item has no placement"))
                1 -> Unit
                else -> add(finding(ContractCheck.CONSERVATION, FindingSubject.Item(item), "Item has $count placements"))
            }
        }
        (counts.keys - expected).forEach { item ->
            add(finding(ContractCheck.CONSERVATION, FindingSubject.Item(item), "Unexpected item has a placement"))
        }
    }

    fun checkBounds(input: OrganizationInput, planned: Planned): List<OracleFinding> = buildList {
        val device = input.snapshot.device
        fun workspace(subject: FindingSubject, target: PlacementTarget.WorkspaceTarget, label: String) {
            val span = target.span
            val cell = target.cell
            if (span.width <= 0 || span.height <= 0) {
                add(finding(ContractCheck.BOUNDS, subject, "$label has non-positive span $span"))
            }
            if (cell.x < 0 || cell.y < 0 || cell.x + span.width > device.columns || cell.y + span.height > device.rows) {
                add(finding(ContractCheck.BOUNDS, subject, "$label is outside device bounds at $cell with $span"))
            }
        }
        planned.placements.forEach { placement ->
            when (val target = placement.target) {
                is PlacementTarget.WorkspaceTarget -> workspace(FindingSubject.Item(placement.item), target, "Workspace placement")

                is PlacementTarget.Dock -> if (target.rank !in 0 until device.hotseatSlots) {
                    add(finding(ContractCheck.BOUNDS, FindingSubject.Item(placement.item), "Dock rank ${target.rank} is outside bounds"))
                }

                is PlacementTarget.FolderMember -> if (target.rank < 0) {
                    add(finding(ContractCheck.BOUNDS, FindingSubject.Item(placement.item), "Folder rank ${target.rank} is negative"))
                }

                is PlacementTarget.AppPairMember -> Unit
            }
        }
        planned.newFolders.forEach { folder ->
            workspace(FindingSubject.NewFolder(folder.ordinal), folder.workspacePlacement, "New folder")
            if (folder.workspacePlacement.span != GridSpan(1, 1)) {
                add(finding(ContractCheck.BOUNDS, FindingSubject.NewFolder(folder.ordinal), "New folder span must be GridSpan(1, 1)"))
            }
        }
    }

    fun checkNoOverlap(planned: Planned): List<OracleFinding> = buildList {
        data class Occupant(val subject: FindingSubject, val cell: GridCell, val span: GridSpan)
        val workspace = mutableMapOf<Any, MutableList<Occupant>>()
        planned.placements.forEach { placement ->
            val target = placement.target as? PlacementTarget.WorkspaceTarget ?: return@forEach
            workspace.getOrPut(target.page) { mutableListOf() } += Occupant(FindingSubject.Item(placement.item), target.cell, target.span)
        }
        planned.newFolders.forEach { folder ->
            val target = folder.workspacePlacement
            workspace.getOrPut(target.page) { mutableListOf() } += Occupant(FindingSubject.NewFolder(folder.ordinal), target.cell, target.span)
        }
        workspace.values.forEach { occupants ->
            occupants.forEachIndexed { index, left ->
                occupants.drop(index + 1).forEach { right ->
                    if (rectanglesOverlap(left.cell, left.span, right.cell, right.span)) {
                        add(finding(ContractCheck.NO_OVERLAP, left.subject, "Workspace overlap with ${right.subject}"))
                    }
                }
            }
        }
        planned.placements.filter { it.target is PlacementTarget.Dock }
            .groupBy { (it.target as PlacementTarget.Dock).rank }
            .filterValues { it.size > 1 }
            .values.flatten().forEach {
                add(finding(ContractCheck.NO_OVERLAP, FindingSubject.Item(it.item), "Duplicate Dock rank"))
            }
        planned.placements.filter { it.target is PlacementTarget.FolderMember }
            .groupBy { (it.target as PlacementTarget.FolderMember).folder to (it.target as PlacementTarget.FolderMember).rank }
            .filterValues { it.size > 1 }
            .values.flatten().forEach {
                add(finding(ContractCheck.NO_OVERLAP, FindingSubject.Item(it.item), "Duplicate folder rank"))
            }
    }

    fun checkContainerIntegrity(input: OrganizationInput, planned: Planned): List<OracleFinding> = buildList {
        val existingPages = input.snapshot.pages.groupingBy { it.id }.eachCount()
        val newPages = planned.newPages.groupingBy { it.ordinal }.eachCount()
        planned.newPages.filter { newPages.getValue(it.ordinal) > 1 }.forEach {
            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.NewPage(it.ordinal), "Duplicate new-page ordinal"))
        }
        val allPageOrders = input.snapshot.pages.map { it.order } + planned.newPages.map { it.order }
        allPageOrders.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.None, "Duplicate page order $it"))
        }
        val expectedPageOrdinals = planned.newPages.indices.toList()
        if (planned.newPages.map { it.ordinal.value } != expectedPageOrdinals) {
            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.None, "New-page ordinals are not canonical and ascending"))
        }
        val maxCapturedOrder = input.snapshot.pages.maxOfOrNull { it.order }
        planned.newPages.forEach {
            if (maxCapturedOrder != null && it.order <= maxCapturedOrder) {
                add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.NewPage(it.ordinal), "New-page order does not follow captured pages"))
            }
        }
        val existingFolders = input.snapshot.items.filter { it.kind == ItemKind.FOLDER && it.folderId != null }
            .groupBy { requireNotNull(it.folderId) }
        val existingPairs = input.snapshot.items.filter { it.kind == ItemKind.APP_PAIR && it.appPairId != null }
            .groupBy { requireNotNull(it.appPairId) }
        val newFolders = planned.newFolders.groupBy { it.ordinal }
        newFolders.filterValues { it.size > 1 }.forEach { (ordinal, _) ->
            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.NewFolder(ordinal), "Duplicate new-folder ordinal"))
        }
        if (planned.newFolders.map { it.ordinal.value } != planned.newFolders.indices.toList()) {
            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.None, "New-folder ordinals are not canonical and ascending"))
        }

        fun checkPage(subject: FindingSubject, target: PlacementTarget.WorkspaceTarget) {
            when (val page = target.page) {
                is PageRef -> if (existingPages[page.pageId] != 1) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, subject, "Existing page reference does not resolve exactly once"))
                }

                is NewPageRef -> if (newPages[page.ordinal] != 1) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, subject, "New-page reference does not resolve exactly once"))
                }
            }
        }
        planned.placements.forEach { placement ->
            when (val target = placement.target) {
                is PlacementTarget.WorkspaceTarget -> checkPage(FindingSubject.Item(placement.item), target)

                is PlacementTarget.FolderMember -> when (val folder = target.folder) {
                    is FolderRef -> {
                        val parents = existingFolders[folder.folderId]
                        if (parents?.size != 1) {
                            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "Folder reference does not resolve exactly once"))
                        } else if (parents.single().members.count { it == placement.item } != 1) {
                            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "Existing-folder placement is not listed exactly once"))
                        }
                    }

                    is NewFolderRef -> if (newFolders[folder.ordinal]?.size != 1) {
                        add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "New-folder reference does not resolve exactly once"))
                    }
                }

                is PlacementTarget.AppPairMember -> {
                    val parents = existingPairs[target.pair.appPairId]
                    if (parents?.size != 1) {
                        add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "App-pair reference does not resolve exactly once"))
                    } else {
                        val parent = parents.single()
                        val metadataMembers = parent.appPair?.members?.map { it.item }.orEmpty()
                        if (metadataMembers.count { it == placement.item } != 1) {
                            add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "App-pair parent does not list member"))
                        }
                    }
                }

                is PlacementTarget.Dock -> Unit
            }
        }
        planned.newFolders.forEach { folder ->
            checkPage(FindingSubject.NewFolder(folder.ordinal), folder.workspacePlacement)
            val duplicateMembers = folder.members.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            duplicateMembers.forEach {
                add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(it), "New folder lists member more than once"))
            }
            folder.members.distinct().forEach { item ->
                val matches = planned.placements.count {
                    it.item == item && (it.target as? PlacementTarget.FolderMember)?.folder == NewFolderRef(folder.ordinal)
                }
                if (matches != 1) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(item), "New-folder member has $matches matching placements"))
                }
                val kind = input.itemKind(item)
                if (kind != ItemKind.APPLICATION && kind != ItemKind.DEEP_SHORTCUT) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(item), "Kind is not allowed in new folder"))
                }
            }
        }
        planned.placements.forEach { placement ->
            val ref = (placement.target as? PlacementTarget.FolderMember)?.folder as? NewFolderRef ?: return@forEach
            val listed = newFolders[ref.ordinal]?.singleOrNull()?.members?.count { it == placement.item } ?: 0
            if (listed != 1) {
                add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(placement.item), "New-folder placement is not listed exactly once"))
            }
        }

        existingFolders.values.flatten().forEach { parent ->
            val folderId = requireNotNull(parent.folderId)
            parent.members.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { member ->
                add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(member), "Existing folder lists member more than once"))
            }
            parent.members.distinct().forEach { member ->
                val placements = planned.placements.filter { it.item == member }
                val matches = placements.count { (it.target as? PlacementTarget.FolderMember)?.folder == FolderRef(folderId) }
                if (matches != 1) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(member), "Existing-folder member has $matches matching placements"))
                }
                val capturedTarget = input.snapshot.items.singleOrNull { it.id == member }?.placement?.toOutputTarget()
                if (placements.singleOrNull()?.target != capturedTarget) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(member), "Existing-folder member target or rank changed"))
                }
                val kind = input.itemKind(member)
                if (kind != ItemKind.APPLICATION && kind != ItemKind.DEEP_SHORTCUT && kind != ItemKind.APP_PAIR) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(member), "Kind is not allowed in existing folder"))
                }
            }
        }

        existingPairs.values.flatten().forEach { parent ->
            val pairId = requireNotNull(parent.appPairId)
            parent.appPair?.members.orEmpty().map { it.item }.distinct().forEach { member ->
                val matches = planned.placements.count {
                    it.item == member && (it.target as? PlacementTarget.AppPairMember)?.pair?.appPairId == pairId
                }
                if (matches != 1) {
                    add(finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Item(member), "App-pair member has $matches matching placements"))
                }
            }
        }
        addAll(checkFolderCycles(input, planned))
    }

    fun checkLockPreservation(input: OrganizationInput, planned: Planned): List<OracleFinding> = buildList {
        input.snapshot.items.filter { it.locked }.forEach { captured ->
            val placements = planned.placements.filter { it.item == captured.id }
            val expectedTarget = captured.placement.toOutputTarget()
            if (placements.size != 1 || placements.single().target != expectedTarget) {
                add(finding(ContractCheck.LOCK_PRESERVATION, FindingSubject.Item(captured.id), "Item ${captured.id.value}: locked target changed"))
            }
            val disposition = placements.singleOrNull()?.disposition
            if (disposition !is Disposition.Preserved || disposition.reason != app.lawnchair.organizer.planning.PreserveReason.LOCKED) {
                add(finding(ContractCheck.LOCK_PRESERVATION, FindingSubject.Item(captured.id), "Item ${captured.id.value}: not preserved as LOCKED"))
            }
        }
    }

    fun checkProfileIsolation(input: OrganizationInput, planned: Planned): List<OracleFinding> = buildList {
        val profiles = input.snapshot.items.associate { it.id to it.profile } + input.targets.additions.associate { it.id to it.profile }
        planned.newFolders.forEach { folder ->
            folder.members.forEach { member ->
                if (profiles[member] != folder.profile) {
                    add(finding(ContractCheck.PROFILE_ISOLATION, FindingSubject.Item(member), "New-folder member profile differs from folder profile"))
                }
            }
        }
        val folderParents = input.snapshot.items.filter { it.kind == ItemKind.FOLDER && it.folderId != null }
            .associateBy { requireNotNull(it.folderId) }
        folderParents.values.forEach { parent ->
            parent.members.forEach { member ->
                val captured = input.snapshot.items.singleOrNull { it.id == member } ?: return@forEach
                if (captured.profile == parent.profile) return@forEach
                val plannedTarget = planned.placements.singleOrNull { it.item == member }?.target
                if (plannedTarget != captured.placement.toOutputTarget()) {
                    add(finding(ContractCheck.PROFILE_ISOLATION, FindingSubject.Item(member), "Pre-existing cross-profile folder member target changed"))
                }
            }
        }
        planned.placements.forEach { placement ->
            val ref = (placement.target as? PlacementTarget.FolderMember)?.folder as? FolderRef ?: return@forEach
            val parent = folderParents[ref.folderId] ?: return@forEach
            val childProfile = profiles[placement.item] ?: return@forEach
            if (childProfile == parent.profile) return@forEach
            val captured = input.snapshot.items.singleOrNull { it.id == placement.item }
            val unchanged = captured?.placement.toOutputTarget() == placement.target && placement.item in parent.members
            if (!unchanged) {
                add(finding(ContractCheck.PROFILE_ISOLATION, FindingSubject.Item(placement.item), "Cross-profile captured-folder membership is new or changed"))
            }
        }
    }

    fun checkDeterminism(first: PlanningResult, second: PlanningResult): List<OracleFinding> = if (first == second) emptyList() else listOf(finding(ContractCheck.DETERMINISM, FindingSubject.None, "Same value input produced unequal complete PlanningResults"))

    fun checkIdempotence(input: OrganizationInput, replan: PlanningResult): List<OracleFinding> {
        val echoFindings = buildList {
            if (replan.revision != input.snapshot.revision) add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.None, "Replan revision echo mismatch"))
            if (replan.ruleVersion != input.rules.version) add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.None, "Replan rule-version echo mismatch"))
            if (replan.taxonomyVersion != input.taxonomy.version) add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.None, "Replan taxonomy-version echo mismatch"))
        }
        val planned = replan.outcome as? Planned
            ?: return echoFindings + finding(ContractCheck.IDEMPOTENCE, FindingSubject.None, "Materialized full result replanned as a rejected outcome")
        val capturedById = input.snapshot.items.associateBy { it.id }
        val rolesById = input.targets.existing.associate { it.item to it.role }
        val placementsById = planned.placements.groupBy { it.item }

        fun expectedReason(item: CapturedItem): PreserveReason = when {
            item.locked -> PreserveReason.LOCKED

            item.availability != Availability.AVAILABLE -> PreserveReason.UNAVAILABLE_TARGET

            item.placement is CapturedPlacement.Dock -> PreserveReason.DOCK

            item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET -> PreserveReason.WIDGET

            item.kind == ItemKind.APP_PAIR || item.placement is CapturedPlacement.AppPairMember -> PreserveReason.APP_PAIR

            item.kind == ItemKind.SHORTCUT_LEGACY -> PreserveReason.LEGACY_SHORTCUT

            rolesById[item.id] == ExistingRole.Preserved -> PreserveReason.NON_TARGET

            item.placement is CapturedPlacement.FolderMember -> PreserveReason.STRUCTURAL

            // Spec 182/237: a movable item the selected strategy intentionally
            // keeps fixed reports STRATEGY_PRESERVED on every run, including
            // the replan (spec 237: V1 re-pins materialized folders this way).
            input.rules.organizationStrategy.let { strategyId ->
                app.lawnchair.organizer.planning.LayoutStrategyRegistry.definition(strategyId)
            }?.strategyFixes(item) == true -> PreserveReason.STRATEGY_PRESERVED

            else -> PreserveReason.ALREADY_CANONICAL
        }

        return echoFindings + buildList {
            input.snapshot.items.forEach { captured ->
                val matches = placementsById[captured.id].orEmpty()
                if (matches.size != 1) {
                    add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(captured.id), "Replan must return exactly one placement for each materialized item"))
                    return@forEach
                }
                val placement = matches.single()
                if (placement.disposition is Disposition.Moved) {
                    add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(placement.item), "Replan moved an item"))
                }
                if (captured.placement.toOutputTarget() != placement.target) {
                    add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(placement.item), "Replan changed an item target"))
                }
                val expected = expectedReason(captured)
                if (placement.disposition != Disposition.Preserved(expected)) {
                    add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(placement.item), "Replan item did not use expected preserve reason $expected"))
                }
            }
            planned.placements.filter { it.item !in capturedById }.forEach {
                add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(it.item), "Replan returned an unknown item"))
            }
            planned.newPages.forEach {
                add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.NewPage(it.ordinal), "Replan created a page"))
            }
            planned.newFolders.forEach {
                add(finding(ContractCheck.IDEMPOTENCE, FindingSubject.NewFolder(it.ordinal), "Replan created a folder"))
            }
        }
    }

    private fun warningsOf(outcome: app.lawnchair.organizer.planning.PlanningOutcome): List<Warning> = when (outcome) {
        is Planned -> outcome.warnings
        is Rejected.Invalid -> outcome.warnings
        is Rejected.Impossible -> outcome.warnings
    }

    private fun checkFolderCycles(input: OrganizationInput, planned: Planned): List<OracleFinding> {
        val folderByItem = input.snapshot.items.filter { it.folderId != null }.associate { it.id to requireNotNull(it.folderId) }
        val edges = mutableMapOf<FolderId, FolderId>()
        planned.placements.forEach { placement ->
            val child = folderByItem[placement.item] ?: return@forEach
            val parent = ((placement.target as? PlacementTarget.FolderMember)?.folder as? FolderRef)?.folderId ?: return@forEach
            edges[child] = parent
        }
        return edges.keys.mapNotNull { start ->
            val seen = mutableSetOf<FolderId>()
            var current: FolderId? = start
            while (current != null && seen.add(current)) current = edges[current]
            if (current != null) finding(ContractCheck.CONTAINER_INTEGRITY, FindingSubject.Folder(start), "Folder cycle detected") else null
        }
    }

    private fun OrganizationInput.itemKind(id: ItemId): ItemKind? = snapshot.items.singleOrNull { it.id == id }?.kind ?: when (targets.additions.singleOrNull { it.id == id }?.kind) {
        app.lawnchair.organizer.planning.CandidateKind.APPLICATION -> ItemKind.APPLICATION
        app.lawnchair.organizer.planning.CandidateKind.DEEP_SHORTCUT -> ItemKind.DEEP_SHORTCUT
        null -> null
    }

    private fun CapturedPlacement?.toOutputTarget(): PlacementTarget? = when (this) {
        is CapturedPlacement.Workspace -> PlacementTarget.WorkspaceTarget(page, cell, span)
        is CapturedPlacement.Dock -> PlacementTarget.Dock(rank)
        is CapturedPlacement.FolderMember -> PlacementTarget.FolderMember(folder, rank)
        is CapturedPlacement.AppPairMember -> PlacementTarget.AppPairMember(pair)
        is CapturedPlacement.UnsupportedContainer, null -> null
    }

    private fun rectanglesOverlap(a: GridCell, aSpan: GridSpan, b: GridCell, bSpan: GridSpan): Boolean = a.x < b.x + bSpan.width && b.x < a.x + aSpan.width && a.y < b.y + bSpan.height && b.y < a.y + aSpan.height

    private fun finding(check: ContractCheck, subject: FindingSubject, message: String) = OracleFinding(check, subject, message)
}
