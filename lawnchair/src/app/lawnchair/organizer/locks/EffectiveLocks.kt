package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId

/**
 * What a stored `LOCKED` state protects for one row, per ADR-0004
 * “Identity and effective-lock rules”. Drives both the mutation decision and
 * the pre-mutation explanation rendered by the UI.
 */
enum class LockProtectionScope {
    /** Application, shortcut, deep shortcut: the row's own captured placement. */
    OWN_PLACEMENT,

    /** Widget row: cell, span, and the full occupied region. */
    WIDGET_REGION,

    /** Dock row: Dock rank and slot. */
    DOCK_SLOT,

    /** Folder parent: the parent cell and every child's captured container/rank. */
    FOLDER_WITH_CHILDREN,

    /** App-pair parent: placement, membership, and split encoding of both members. */
    APP_PAIR_WITH_MEMBERS,

    /** Folder child: its own container/rank inside the folder. */
    FOLDER_CHILD_RANK,

    /** App-pair member: its own membership/split placement. */
    APP_PAIR_MEMBER_PLACEMENT,
}

/**
 * Typed explanation notes. The UI maps each note to a localized string; the
 * domain layer never formats user text.
 */
enum class LockEffectNote {
    /** Locking a folder also fixes every item placed inside it. */
    FOLDER_PARENT_COVERS_CHILDREN,

    /** Unlocking a folder leaves children with their own lock still protected. */
    FOLDER_CHILDREN_OWN_LOCK_REMAINS,

    /** Locking a folder child keeps its rank even while the folder is unlocked. */
    FOLDER_CHILD_OWN_LOCK_BINDS,

    /** Unlocking a folder child under a locked folder has no effect on organization. */
    FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK,

    /** Locking an app pair fixes both members' membership and split placement. */
    APP_PAIR_PARENT_COVERS_BOTH_MEMBERS,

    /** Unlocking a member under a locked app pair has no effect on organization. */
    APP_PAIR_MEMBER_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK,

    /** A locked member keeps its split placement even while the pair is unlocked. */
    APP_PAIR_MEMBER_OWN_LOCK_BINDS,
}

/** Structured, renderable summary of one row's placement for list/dialog UI. */
sealed interface LockPlacementSummary {
    data class Desktop(
        val pageOrder: Int?,
        val cell: GridCell,
        val span: GridSpan,
    ) : LockPlacementSummary

    data class DockSlot(val rank: Int) : LockPlacementSummary

    data class InFolder(val parent: ItemId, val rank: Int) : LockPlacementSummary

    data class InAppPair(val parent: ItemId) : LockPlacementSummary

    data class Unsupported(val code: ContainerCode) : LockPlacementSummary
}

/**
 * Effective-lock view of one row: stored state, effective protection, the
 * scope a lock would protect, and structural relations used by explanations.
 */
data class EffectiveLockView(
    val item: ItemId,
    val stored: OrganizerLockState,
    val effectivelyProtected: Boolean,
    val scope: LockProtectionScope,
    val parent: ItemId?,
    val parentStored: OrganizerLockState?,
    val memberItems: List<ItemId>,
    val membersWithOwnLock: List<ItemId>,
)

/** Computes the effective-lock view for [item] inside [state]. */
fun effectiveLockViewOf(state: LayoutState, item: CanonicalItemState): EffectiveLockView {
    val byRef = state.items.associateBy { it.ref }
    val parentRef = when (val placement = item.placement) {
        is PlacementState.FolderChild -> placement.parent as? ApplicationItemRef.PersistentItem
        is PlacementState.AppPairChild -> placement.parent as? ApplicationItemRef.PersistentItem
        else -> null
    }
    val parentState = parentRef?.let { byRef[ApplicationItemRef.PersistentItem(it.itemId)] }

    val memberItems = when (item.kind) {
        CanonicalItemKind.Folder ->
            (item.structure as? StructureState.FolderMembers)?.members.orEmpty()
                .mapNotNull { (it.item as? ApplicationItemRef.PersistentItem)?.itemId }

        CanonicalItemKind.AppPair -> {
            val members = item.structure as? StructureState.AppPairMembers
            listOfNotNull(members?.first, members?.second)
                .mapNotNull { (it as? ApplicationItemRef.PersistentItem)?.itemId }
        }

        else -> emptyList()
    }

    val scope = when {
        item.kind == CanonicalItemKind.Folder -> LockProtectionScope.FOLDER_WITH_CHILDREN

        item.kind == CanonicalItemKind.AppPair -> LockProtectionScope.APP_PAIR_WITH_MEMBERS

        item.placement is PlacementState.FolderChild -> LockProtectionScope.FOLDER_CHILD_RANK

        item.placement is PlacementState.AppPairChild -> LockProtectionScope.APP_PAIR_MEMBER_PLACEMENT

        item.placement is PlacementState.Dock -> LockProtectionScope.DOCK_SLOT

        item.kind == CanonicalItemKind.AppWidget ||
            item.kind == CanonicalItemKind.CustomAppWidget -> LockProtectionScope.WIDGET_REGION

        else -> LockProtectionScope.OWN_PLACEMENT
    }

    val parentLocked = parentState?.lockState == OrganizerLockState.LOCKED
    return EffectiveLockView(
        item = itemIdOf(item),
        stored = item.lockState,
        effectivelyProtected = item.lockState == OrganizerLockState.LOCKED || parentLocked,
        scope = scope,
        parent = parentState?.let(::itemIdOf),
        parentStored = parentState?.lockState,
        memberItems = memberItems,
        membersWithOwnLock = memberItems.mapNotNull { member ->
            val memberState = byRef[ApplicationItemRef.PersistentItem(member)]
            if (memberState?.lockState == OrganizerLockState.LOCKED) member else null
        },
    )
}

/**
 * Typed explanation for changing [item] to [target]: which precedence notes
 * the UI must surface before mutation. Pure; no intent is required to preview.
 */
fun explainEffect(
    state: LayoutState,
    item: CanonicalItemState,
    target: LockTargetState,
): List<LockEffectNote> {
    val view = effectiveLockViewOf(state, item)
    val locking = target == LockTargetState.LOCKED
    val notes = mutableListOf<LockEffectNote>()
    when (view.scope) {
        LockProtectionScope.FOLDER_WITH_CHILDREN -> {
            val note = if (locking) {
                LockEffectNote.FOLDER_PARENT_COVERS_CHILDREN
            } else if (view.membersWithOwnLock.isNotEmpty()) {
                LockEffectNote.FOLDER_CHILDREN_OWN_LOCK_REMAINS
            } else {
                null
            }
            if (note != null) notes += note
        }

        LockProtectionScope.FOLDER_CHILD_RANK -> {
            val note = if (locking && view.parentStored != OrganizerLockState.LOCKED) {
                LockEffectNote.FOLDER_CHILD_OWN_LOCK_BINDS
            } else if (!locking && view.parentStored == OrganizerLockState.LOCKED) {
                LockEffectNote.FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK
            } else {
                null
            }
            if (note != null) notes += note
        }

        LockProtectionScope.APP_PAIR_WITH_MEMBERS -> if (locking) {
            notes += LockEffectNote.APP_PAIR_PARENT_COVERS_BOTH_MEMBERS
        }

        LockProtectionScope.APP_PAIR_MEMBER_PLACEMENT -> {
            val note = if (locking && view.parentStored != OrganizerLockState.LOCKED) {
                LockEffectNote.APP_PAIR_MEMBER_OWN_LOCK_BINDS
            } else if (!locking && view.parentStored == OrganizerLockState.LOCKED) {
                LockEffectNote.APP_PAIR_MEMBER_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK
            } else {
                null
            }
            if (note != null) notes += note
        }

        LockProtectionScope.OWN_PLACEMENT,
        LockProtectionScope.WIDGET_REGION,
        LockProtectionScope.DOCK_SLOT,
        -> Unit
    }
    return notes
}

/** Deterministic placement summary for one row. */
fun placementSummaryOf(state: LayoutState, item: CanonicalItemState): LockPlacementSummary = when (val placement = item.placement) {
    is PlacementState.Workspace -> LockPlacementSummary.Desktop(
        pageOrder = state.pages.indexOfFirst { it.ref == placement.page }.takeIf { it >= 0 },
        cell = placement.cell,
        span = placement.span,
    )

    is PlacementState.Dock -> LockPlacementSummary.DockSlot(placement.rank)

    is PlacementState.FolderChild -> LockPlacementSummary.InFolder(
        parent = (placement.parent as? ApplicationItemRef.PersistentItem)?.itemId
            ?: ItemId(placement.parent.toString()),
        rank = placement.rank,
    )

    is PlacementState.AppPairChild -> LockPlacementSummary.InAppPair(
        parent = (placement.parent as? ApplicationItemRef.PersistentItem)?.itemId
            ?: ItemId(placement.parent.toString()),
    )

    is PlacementState.UnsupportedContainer -> LockPlacementSummary.Unsupported(placement.code)
}

internal fun itemIdOf(item: CanonicalItemState): ItemId = when (val ref = item.ref) {
    is ApplicationItemRef.PersistentItem -> ref.itemId
    is ApplicationItemRef.PlannedCandidate -> ref.itemId
    is ApplicationItemRef.PlannedFolder -> ItemId("planned-folder-${ref.ordinal.value}")
}
