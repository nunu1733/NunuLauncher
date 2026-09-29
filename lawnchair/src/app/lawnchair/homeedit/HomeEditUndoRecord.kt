/*
 * Issue #450: the undo record (spec 450 "取り消し記録"). Extends the #448
 * execution-time evidence into the process-local single-slot record with a
 * generation-bound compare-and-consume: the snackbar action closes over the
 * token observed at display time, so a stale snackbar tap (the record already
 * replaced by a newer edit whose snackbar is not on screen yet) is a zero-write
 * no-op that never consumes the current record. The undo body, lifetime, and
 * UI are owned by Issue #450 (ADR-0013 contract 5).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.planning.RevisionId
import com.android.launcher3.model.DirectEditContract
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class HomeEditActionKind {
    MOVE_TO_PAGE,
    ADD_TO_FOLDER,
    CREATE_FOLDER_AND_ADD,
    REMOVE,
}

data class HomeEditUndoEvidence(
    val action: HomeEditActionKind,
    val itemId: Int,
    val oldContainer: Int,
    val oldScreenId: Int,
    val oldCellX: Int,
    val oldCellY: Int,
    val oldSpanX: Int,
    val oldSpanY: Int,
    val oldRank: Int,
    val newContainer: Int,
    val newScreenId: Int,
    val newCellX: Int,
    val newCellY: Int,
    val newRank: Int,
    /** Reference to the folder created by this action, if any. */
    val createdFolderId: Int?,
    /** Placement of the folder this action created, if any (AC-9). */
    val createdFolderScreenId: Int?,
    val createdFolderCellX: Int?,
    val createdFolderCellY: Int?,
)

/**
 * One undoable fork edit in the single-slot record (spec 450 Scope). The
 * closed union: an item-level direct edit, or a visual edit-surface confirm.
 */
sealed interface HomeEditUndoEntry {

    /** 項目単位のアクション（#448）。[removedRow] carries the pre-DELETE row
     * for the remove-undo inverse INSERT; null for every other action. */
    data class DirectEdit(
        val evidence: HomeEditUndoEvidence,
        val removedRow: DirectEditContract.UndoRowPayload?,
    ) : HomeEditUndoEntry

    /** 視覚的編集画面の確定（#449）。The revision is the apply path's verified
     * post-apply revision (materialized post-state), received through the
     * apply receipt at confirm completion. */
    data class EditSession(
        val pointId: RecoveryPointId,
        val expectedRevision: RevisionId,
    ) : HomeEditUndoEntry
}

/** The generation-bound handle returned by [HomeEditUndoRecord.record]. */
@JvmInline
value class HomeEditUndoToken(val generation: Long)

/**
 * Process-local single-slot undo record. Replacement (next successful fork
 * edit) and consumption (undo attempt end) are atomic; [compareAndConsume]
 * only consumes when the slot still holds the exact generation, so a stale
 * snackbar action can never consume — or undo — a newer edit (spec AC-11).
 */
object HomeEditUndoRecord {

    private val generation = AtomicLong(0L)
    private val slot = AtomicReference<Slot?>(null)

    private data class Slot(val token: HomeEditUndoToken, val entry: HomeEditUndoEntry)

    /** Records [entry] as the current edit, replacing any previous one. */
    fun record(entry: HomeEditUndoEntry): HomeEditUndoToken {
        val token = HomeEditUndoToken(generation.incrementAndGet())
        slot.set(Slot(token, entry))
        return token
    }

    /**
     * Consumes the record only when it still holds [token]'s generation.
     * Returns the entry on a match, or null on mismatch/empty — in which case
     * the slot is left untouched (the current edit stays undoable).
     */
    fun compareAndConsume(token: HomeEditUndoToken): HomeEditUndoEntry? {
        while (true) {
            val current = slot.get() ?: return null
            if (current.token != token) return null
            if (slot.compareAndSet(current, null)) return current.entry
        }
    }

    /**
     * Test-only read of the current slot (no consumption). Exposes whether an
     * undo entry exists — used by the process-death smoke to observe that the
     * process-local record died with the process.
     */
    fun inspectForTest(): HomeEditUndoEntry? = slot.get()?.entry
}

/**
 * Builds the undo evidence from the validated plan and the pre-write
 * placement reported by the admitted task. Pure; unit-tested through this
 * mapping (AC-9).
 */
fun buildUndoEvidence(
    intent: HomeEditIntent,
    plan: HomeEditPlan.Success,
    oldContainer: Int,
    oldScreenId: Int,
    oldCellX: Int,
    oldCellY: Int,
    oldSpanX: Int,
    oldSpanY: Int,
    oldRank: Int,
    createdFolderId: Int,
): HomeEditUndoEvidence {
    val target = plan.targetItemPlacement
    val action = when (intent) {
        is HomeEditIntent.MoveToPage -> HomeEditActionKind.MOVE_TO_PAGE

        is HomeEditIntent.AddToFolder -> HomeEditActionKind.ADD_TO_FOLDER

        is HomeEditIntent.CreateFolderAndAdd -> HomeEditActionKind.CREATE_FOLDER_AND_ADD

        // Issue #449: the edit-surface variant never reaches the #448 popup
        // undo path (the surface applies through the organizer protocol, not
        // ModelWriter direct-edit). Kept total for the exhaustive when.
        is HomeEditIntent.CreateFolderAt -> HomeEditActionKind.CREATE_FOLDER_AND_ADD

        is HomeEditIntent.Remove -> HomeEditActionKind.REMOVE
    }
    val folderId = createdFolderId.takeIf { it != 0 }
    val (newContainer, newScreenId, newCellX, newCellY, newRank) = when (plan) {
        is HomeEditPlan.Move -> listOf(plan.container, plan.screenId, plan.cellX, plan.cellY, plan.rank)
        is HomeEditPlan.CreateFolder -> listOf(createdFolderId, 0, -1, -1, 0)
        is HomeEditPlan.RemoveItem -> listOf(oldContainer, oldScreenId, oldCellX, oldCellY, oldRank)
    }
    val folderPlacement = plan as? HomeEditPlan.CreateFolder
    return HomeEditUndoEvidence(
        action = action,
        itemId = target.id,
        oldContainer = oldContainer,
        oldScreenId = oldScreenId,
        oldCellX = oldCellX,
        oldCellY = oldCellY,
        oldSpanX = oldSpanX,
        oldSpanY = oldSpanY,
        oldRank = oldRank,
        newContainer = newContainer,
        newScreenId = newScreenId,
        newCellX = newCellX,
        newCellY = newCellY,
        newRank = newRank,
        createdFolderId = folderId,
        createdFolderScreenId = folderPlacement?.screenId,
        createdFolderCellX = folderPlacement?.cellX,
        createdFolderCellY = folderPlacement?.cellY,
    )
}
