package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderRef
import app.lawnchair.organizer.planning.NewPageRef
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet

internal sealed interface MaterializationResult {
    data class Success(val input: OrganizationInput) : MaterializationResult
    data class Failed(val findings: List<OracleFinding>) : MaterializationResult
}

internal object PostPlanMaterializer {
    fun materialize(input: OrganizationInput, planned: Planned): MaterializationResult {
        val findings = mutableListOf<OracleFinding>()
        val pageGroups = planned.newPages.groupBy { it.ordinal }
        val folderGroups = planned.newFolders.groupBy { it.ordinal }
        pageGroups.filterValues { it.size != 1 }.keys.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.NewPage(it), "Cannot materialize duplicate new-page ordinal")
        }
        folderGroups.filterValues { it.size != 1 }.keys.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.NewFolder(it), "Cannot materialize duplicate new-folder ordinal")
        }

        val pageIds = pageGroups.filterValues { it.size == 1 }.mapValues { (ordinal, _) ->
            PageId("fixture.materialized.page.${input.snapshot.revision.value}.${ordinal.value}")
        }
        val folderIds = folderGroups.filterValues { it.size == 1 }.mapValues { (ordinal, _) ->
            FolderId("fixture.materialized.folder.${input.snapshot.revision.value}.${ordinal.value}")
        }
        val folderItemIds = folderGroups.filterValues { it.size == 1 }.mapValues { (ordinal, _) ->
            ItemId("fixture.materialized.folder-item.${input.snapshot.revision.value}.${ordinal.value}")
        }
        pageIds.values.filter { id -> input.snapshot.pages.any { it.id == id } }.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.Page(it), "Synthetic page ID collides with captured page")
        }

        fun convert(subject: FindingSubject, target: PlacementTarget): CapturedPlacement? = when (target) {
            is PlacementTarget.WorkspaceTarget -> {
                val page = when (val ref = target.page) {
                    is PageRef -> ref

                    is NewPageRef -> pageIds[ref.ordinal]?.let(::PageRef) ?: run {
                        findings += OracleFinding(ContractCheck.IDEMPOTENCE, subject, "Cannot materialize unresolved new-page reference")
                        null
                    }
                }
                page?.let { CapturedPlacement.Workspace(it, target.cell, target.span) }
            }

            is PlacementTarget.Dock -> CapturedPlacement.Dock(target.rank)

            is PlacementTarget.FolderMember -> {
                val folder = when (val ref = target.folder) {
                    is FolderRef -> ref

                    is NewFolderRef -> folderIds[ref.ordinal]?.let(::FolderRef) ?: run {
                        findings += OracleFinding(ContractCheck.IDEMPOTENCE, subject, "Cannot materialize unresolved new-folder reference")
                        null
                    }
                }
                folder?.let { CapturedPlacement.FolderMember(it, target.rank) }
            }

            is PlacementTarget.AppPairMember -> CapturedPlacement.AppPairMember(target.pair)
        }

        val placements = planned.placements.groupBy { it.item }
        input.snapshot.items.forEach { item ->
            if (placements[item.id]?.size != 1) {
                findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(item.id), "Cannot materialize item without exactly one placement")
            }
        }
        val originalIds = input.snapshot.items.map { it.id }.toSet()
        folderItemIds.values.filter { it in originalIds }.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(it), "Synthetic folder item ID collides with captured item")
        }
        folderIds.values.filter { id -> input.snapshot.items.any { it.folderId == id } }.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.Folder(it), "Synthetic folder ID collides with captured folder")
        }

        val convertedOriginals = input.snapshot.items.mapNotNull { item ->
            val target = placements[item.id]?.singleOrNull()?.target ?: return@mapNotNull null
            convert(FindingSubject.Item(item.id), target)?.let { item.copy(placement = it) }
        }
        val syntheticFolders = planned.newFolders.mapNotNull { folder ->
            val folderId = folderIds[folder.ordinal] ?: return@mapNotNull null
            val itemId = folderItemIds[folder.ordinal] ?: return@mapNotNull null
            val placement = convert(FindingSubject.NewFolder(folder.ordinal), folder.workspacePlacement) as? CapturedPlacement.Workspace
                ?: return@mapNotNull null
            CapturedItem(
                id = itemId,
                profile = folder.profile,
                kind = ItemKind.FOLDER,
                target = TargetKey.FolderKey(folderId),
                placement = placement,
                locked = false,
                availability = Availability.AVAILABLE,
                folderId = folderId,
                members = folder.members,
            )
        }

        if (findings.isNotEmpty()) return MaterializationResult.Failed(findings)

        val pages = input.snapshot.pages + planned.newPages.map { newPage ->
            Page(id = pageIds.getValue(newPage.ordinal), order = newPage.order)
        }
        val rolesByItem = input.targets.existing.associate { it.item to it.role }
        input.snapshot.items.filter { it.id !in rolesByItem }.forEach {
            findings += OracleFinding(ContractCheck.IDEMPOTENCE, FindingSubject.Item(it.id), "Cannot materialize item without an ExistingRole")
        }
        if (findings.isNotEmpty()) return MaterializationResult.Failed(findings)
        val existingMemberships = input.snapshot.items.map { item ->
            ExistingTargetMembership(item.id, requireNotNull(rolesByItem[item.id]))
        }
        val syntheticMemberships = syntheticFolders.map { ExistingTargetMembership(it.id, ExistingRole.Preserved) }
        return MaterializationResult.Success(
            input.copy(
                snapshot = LayoutSnapshot(
                    revision = RevisionId("fixture.materialized.revision.${input.snapshot.revision.value}"),
                    device = input.snapshot.device,
                    pages = pages,
                    items = convertedOriginals + syntheticFolders,
                ),
                targets = TargetSet(existing = existingMemberships + syntheticMemberships, additions = emptyList()),
                runMode = RunMode.FullOrganization,
            ),
        )
    }
}
