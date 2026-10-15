/*
 * Issue #449: pure apply-plan builder for the edit surface session. Android-free.
 * Maps the capture (LayoutState + revision) plus the confirmed session plan to
 * a ValidatedLayoutPlan consumed by the existing organizer apply protocol.
 * Deletions are expressed as absence from the intended state (the apply path's
 * manifest-absence delete pass materializes them); the new folder is declared
 * as a PlannedFolder + NewFolder with an absent title (upstream drag-created
 * folders are untitled the same way). Conservation is verified before the plan
 * is returned: every capture item is preserved, updated, or removed — nothing
 * else — and an empty diff is never produced (the NoChanges unreachability
 * invariant, spec/plan AC-16).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TaxonomyVersion

/** 適用計画構築のclosed result。失敗は零書込み（UIはtypedに扱う）。 */
sealed interface EditSurfaceApplyPlan {
    /** 構築成功。同一入力からは常に同一値（決定性）。 */
    data class Ready(val plan: ValidatedLayoutPlan) : EditSurfaceApplyPlan

    /** セッション計画が空（確定不可。spec: セッション計画が空の間は確定できない）。 */
    data object Empty : EditSurfaceApplyPlan

    /** セッション計画がcaptureと照合できない（実装不具合。防御到達）。 */
    data class Inconsistent(val reason: String) : EditSurfaceApplyPlan
}

object EditSurfacePlanBuilder {

    /**
     * capture（セッション開始時。確定時の再captureとの一致検証は適用経路の契約）と
     * セッション計画から適用計画を構築する。[ruleVersion] / [taxonomyVersion] は
     * 現行policy bundleのversion（homeeditの計画は整理ルール体系の外であり、
     * provenance列の記録値として使う。結合点3）。
     */
    fun build(
        capture: LayoutState,
        sourceRevision: RevisionId,
        session: EditSurfaceSession,
        ruleVersion: RuleVersion,
        taxonomyVersion: TaxonomyVersion,
    ): EditSurfaceApplyPlan {
        if (session.isEmpty) return EditSurfaceApplyPlan.Empty
        val sourceByItemId = HashMap<Int, CanonicalItemState>()
        for (item in capture.items) {
            val ref = item.ref as? ApplicationItemRef.PersistentItem
                ?: return EditSurfaceApplyPlan.Inconsistent("capture contains a non-persistent item ref")
            val id = itemIdValue(ref)
                ?: return EditSurfaceApplyPlan.Inconsistent("capture contains a non-numeric item id")
            sourceByItemId[id] = item
        }

        val intended = capture.items.toMutableList()
        for (change in session.changes) {
            val index = intended.indexOfFirst { item ->
                (item.ref as? ApplicationItemRef.PersistentItem)?.let { itemIdValue(it) == change.targetId } == true
            }
            if (index < 0) return EditSurfaceApplyPlan.Inconsistent("session change targets a missing item: $change")
            when (change) {
                is SessionItemChange.Removal -> intended.removeAt(index)

                is SessionItemChange.PageMove -> {
                    val source = intended[index]
                    val workspace = source.placement as? PlacementState.Workspace
                        ?: return EditSurfaceApplyPlan.Inconsistent("page move on a non-workspace item: $change")
                    intended[index] = source.copy(
                        placement = workspace.copy(
                            page = ApplicationPageRef.PersistentPage(PageId(change.screenId.toString())),
                            cell = change.cell,
                        ),
                    )
                }

                is SessionItemChange.FolderAdd -> {
                    val source = intended[index]
                    val parent = folderParentRef(change.folderId, session)
                        ?: return EditSurfaceApplyPlan.Inconsistent("folder add to an unknown session folder: $change")
                    intended[index] = source.copy(placement = PlacementState.FolderChild(parent, change.rank))
                }
            }
        }

        val folderItems = mutableListOf<CanonicalItemState>()
        val declaredFolders = mutableListOf<NewFolder>()
        for (folder in session.newFolders) {
            val firstMember = folder.memberIds.firstOrNull()
                ?: return EditSurfaceApplyPlan.Inconsistent("session folder without members: $folder")
            val firstItem = sourceByItemId[firstMember]
                ?: return EditSurfaceApplyPlan.Inconsistent("session folder member missing from capture: $folder")
            val availability = capture.profiles.firstOrNull { it.id == firstItem.profile }
                ?: return EditSurfaceApplyPlan.Inconsistent("session folder profile missing from capture: $folder")
            val ordinal = NewFolderOrdinal(folder.ordinal)
            val ref = ApplicationItemRef.PlannedFolder(ordinal)
            val workspacePlacement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId(folder.screenId.toString())),
                cell = GridCell(folder.cellX, folder.cellY),
                span = GridSpan(1, 1),
            )
            val members = folder.memberIds.map { ApplicationItemRef.PersistentItem(ItemId(it.toString())) }
            // Untitled like an upstream drag-created folder (title absent); the
            // naming variant records the user-creation provenance without a
            // semantic grouping identity (結合点2).
            folderItems += CanonicalItemState(
                ref = ref,
                kind = CanonicalItemKind.Folder,
                targetKey = TargetKey.FolderKey(FolderId("planned-folder-${folder.ordinal}")),
                profile = firstItem.profile,
                profileAvailability = availability.availability,
                itemAvailability = if (availability.availability == ProfileAvailability.AVAILABLE) {
                    ItemAvailability.AVAILABLE
                } else {
                    ItemAvailability.UNAVAILABLE
                },
                placement = workspacePlacement,
                title = OptionalText.Absent,
                intent = OptionalText.Absent,
                icon = OptionalBytes.Absent,
                widget = WidgetState.NoWidget,
                modified = app.lawnchair.organizer.application.public.ModifiedAtMillis(0),
                lockState = OrganizerLockState.UNLOCKED,
                structure = StructureState.FolderMembers(
                    members.mapIndexed { rank, member -> RankedMember(member, rank) },
                ),
            )
            // The NewFolder declaration must reference the same member ids.
            declaredFolders += NewFolder(
                ordinal = ordinal,
                profile = firstItem.profile,
                naming = FolderNaming.FromUserCreation,
                workspacePlacement = PlacementTarget.WorkspaceTarget(
                    page = PageRef(PageId(folder.screenId.toString())),
                    cell = GridCell(folder.cellX, folder.cellY),
                    span = GridSpan(1, 1),
                ),
                members = folder.memberIds.map { ItemId(it.toString()) },
            )
        }

        val allIntended = intended + folderItems
        val rebuilt = rebuildFolderStructures(allIntended)
            ?: return EditSurfaceApplyPlan.Inconsistent("folder member ranks are not distinct")
        val intendedState = capture.copy(items = rebuilt)

        val intendedByRef = intendedState.items.associateBy { it.ref }
        val actions = buildList {
            capture.items.forEach { source ->
                val target = intendedByRef[source.ref]
                    ?: return@forEach // removed: expressed by absence from the intended state
                if (target == source) {
                    add(ApplyAction.Preserve(source.ref, source))
                } else {
                    add(ApplyAction.Update(source.ref, source, target))
                }
            }
            folderItems.forEach { folder ->
                val target = intendedByRef[folder.ref]
                    ?: return EditSurfaceApplyPlan.Inconsistent("declared folder missing from intended state")
                add(ApplyAction.Insert(folder.ref, target))
            }
        }

        // Conservation: every capture item is preserved, updated, or removed;
        // no invented persistent items. Deletions must be explained by session
        // changes, not silent omissions (spec/AGENTS.md conservation).
        val removals = session.changes.filterIsInstance<SessionItemChange.Removal>().map { it.targetId }.toSet()
        val expectedPersistent = sourceByItemId.keys - removals
        val actualPersistent = intendedState.items.mapNotNull { item ->
            (item.ref as? ApplicationItemRef.PersistentItem)?.let(::itemIdValue)
        }.toSet()
        if (actualPersistent != expectedPersistent) {
            return EditSurfaceApplyPlan.Inconsistent("conservation violated: persistent item set mismatch")
        }
        // NoChanges unreachability (AC-16): an empty diff is never produced.
        // A removal-only session produces only Preserve actions but still
        // differs from the capture — the exact-state comparison is the
        // NoChanges contract.
        if (intendedState == capture && actions.all { it is ApplyAction.Preserve }) {
            return EditSurfaceApplyPlan.Inconsistent("session produced an empty diff")
        }

        return EditSurfaceApplyPlan.Ready(
            ValidatedLayoutPlan(
                sourceRevision = sourceRevision,
                sourceState = capture,
                intendedState = intendedState,
                actions = actions,
                newPages = emptyList(),
                newFolders = declaredFolders,
                ruleVersion = ruleVersion,
                taxonomyVersion = taxonomyVersion,
            ),
        )
    }

    private fun folderParentRef(folderId: Int, session: EditSurfaceSession): ApplicationItemRef? {
        if (!isEditSurfaceNewFolderKey(folderId)) {
            return ApplicationItemRef.PersistentItem(ItemId(folderId.toString()))
        }
        val ordinal = session.newFolders.firstOrNull { editSurfaceNewFolderKey(it.ordinal) == folderId }?.ordinal
            ?: return null
        return ApplicationItemRef.PlannedFolder(NewFolderOrdinal(ordinal))
    }

    /**
     * Rebuilds every folder's member list from the FolderChild placements in
     * canonical rank order — the same projection the capture and the planner
     * materializer use, so an unchanged folder compares equal to its capture
     * (Preserve) and only folders the session touched become Updates.
     */
    private fun rebuildFolderStructures(items: List<CanonicalItemState>): List<CanonicalItemState>? {
        val membersByParent = items.mapNotNull { child ->
            val placement = child.placement as? PlacementState.FolderChild ?: return@mapNotNull null
            placement.parent to RankedMember(child.ref, placement.rank)
        }.groupBy({ it.first }, { it.second })
        return items.map { item ->
            val members = membersByParent[item.ref] ?: return@map item
            val isFolder = item.kind is CanonicalItemKind.Folder || item.ref is ApplicationItemRef.PlannedFolder
            if (!isFolder) return@map item
            if (members.map { it.rank }.distinct().size != members.size) return null
            item.copy(structure = StructureState.FolderMembers(members.sortedBy { it.rank }))
        }
    }

    private fun itemIdValue(ref: ApplicationItemRef.PersistentItem): Int? = ref.itemId.value.toIntOrNull()
}
