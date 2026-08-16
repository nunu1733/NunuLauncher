package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.planning.ItemId

/**
 * Closed set of writable states. `UNKNOWN` is never a write target; it is
 * only resolved by review (ADR-0004).
 */
enum class LockTargetState {
    LOCKED,
    UNLOCKED,
    ;

    fun toStored(): OrganizerLockState = when (this) {
        LOCKED -> OrganizerLockState.LOCKED
        UNLOCKED -> OrganizerLockState.UNLOCKED
    }
}

/**
 * Evidence that a user explicitly confirmed this change. Only the UI confirm
 * action constructs it; the domain layer never fabricates intent. The label
 * identifies the confirming control for diagnostics.
 */
@JvmInline
value class UserReviewedIntent(val confirmationLabel: String) {
    init {
        require(confirmationLabel.isNotBlank()) { "confirmationLabel must identify the confirm action" }
    }
}

/** Typed rejection of a lock change request before any write. */
enum class LockRejection {
    /** The requested item is absent from the capture (deleted, recreated, or not a persisted row). */
    ITEM_NOT_FOUND,

    /** The capture no longer matches the Launcher DB at write time; retry from a fresh capture. */
    STALE_CAPTURE,

    /** The item exists but is not in the `UNKNOWN` state required for review. */
    ITEM_NOT_UNKNOWN,

    /** The item's profile is captured but currently unavailable (quiet/locked). */
    PROFILE_UNAVAILABLE,

    /** The item's profile is absent from the authoritative profile inventory. */
    PROFILE_UNKNOWN,

    /** Non-actionable kind or unsupported container; a lock never makes it actionable. */
    UNSUPPORTED_ITEM,

    /** Placement does not fit the captured device capabilities (D-006). */
    PLACEMENT_OUT_OF_PROFILE,

    /** No confirmed user intent accompanied the request. */
    INTENT_REQUIRED,

    /** Another writer holds the coordinator lease. */
    WRITER_BUSY,
}

/** Public request: change one item's lock state. */
data class LockStateChangeRequest(
    val item: ItemId,
    val target: LockTargetState,
    val intent: UserReviewedIntent?,
)

/** Public request: resolve a concrete list of `UNKNOWN` rows in one transaction. */
data class LockBatchReviewRequest(
    val items: List<ItemId>,
    val target: LockTargetState,
    val intent: UserReviewedIntent?,
) {
    init {
        require(items.distinct().size == items.size) { "Batch review items must be duplicate-free" }
    }
}

/**
 * Pure decision output: a validated single-column write plan, a typed
 * rejection, or a no-op that must not issue a write. The module layer
 * executes ready plans through the writer port; the decision performs no I/O.
 */
sealed interface LockDecision {
    data class Ready(val plan: LockWritePlan, val effects: List<LockEffectNote>) : LockDecision
    data object NoChange : LockDecision
    data class Rejected(val reason: LockRejection) : LockDecision
}

/**
 * Platform-free lock-authoring decisions. All rules from spec
 * §“Behavior scenarios”: identity/profile/support/bounds checks, intent
 * requirement, closed targets, and batch atomicity.
 */
object LockAuthoringDecision {

    fun evaluateChange(capture: LockCapture, request: LockStateChangeRequest): LockDecision {
        val item = capture.state.findItem(request.item)
            ?: return LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND)
        sharedRejection(capture.state, item)?.let { return LockDecision.Rejected(it) }
        if (request.intent == null) return LockDecision.Rejected(LockRejection.INTENT_REQUIRED)
        if (item.lockState == request.target.toStored()) return LockDecision.NoChange
        val write = item.toWrite(request.target)
            ?: return LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND)
        return LockDecision.Ready(
            plan = LockWritePlan(
                writes = listOf(write),
                sourceRevision = capture.revision,
            ),
            effects = explainEffect(capture.state, item, request.target),
        )
    }

    fun evaluateReviewBatch(capture: LockCapture, request: LockBatchReviewRequest): LockDecision {
        if (request.intent == null) return LockDecision.Rejected(LockRejection.INTENT_REQUIRED)
        if (request.items.isEmpty()) return LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND)
        val writes = mutableListOf<LockRowWrite>()
        for (id in request.items) {
            val state = capture.state.findItem(id)
                ?: return LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND)
            sharedRejection(capture.state, state)?.let { return LockDecision.Rejected(it) }
            if (state.lockState != OrganizerLockState.UNKNOWN) {
                return LockDecision.Rejected(LockRejection.ITEM_NOT_UNKNOWN)
            }
            writes += state.toWrite(request.target)
                ?: return LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND)
        }
        return LockDecision.Ready(
            plan = LockWritePlan(
                writes = writes.toList(),
                sourceRevision = capture.revision,
            ),
            effects = emptyList(),
        )
    }

    private fun sharedRejection(state: LayoutState, item: CanonicalItemState): LockRejection? {
        if (item.ref !is ApplicationItemRef.PersistentItem) return LockRejection.ITEM_NOT_FOUND
        val profile = state.profiles.firstOrNull { it.id == item.profile }
            ?: return LockRejection.PROFILE_UNKNOWN
        if (profile.availability != ProfileAvailability.AVAILABLE) {
            return LockRejection.PROFILE_UNAVAILABLE
        }
        if (!isActionable(item)) return LockRejection.UNSUPPORTED_ITEM
        if (!fitsProfile(state, item)) return LockRejection.PLACEMENT_OUT_OF_PROFILE
        return null
    }

    /**
     * ADR-0004 identity table: unknown kinds and unsupported containers stay
     * non-actionable regardless of their stored lock value.
     */
    private fun isActionable(item: CanonicalItemState): Boolean = item.kind !is CanonicalItemKind.Unknown && item.placement !is PlacementState.UnsupportedContainer

    private fun fitsProfile(state: LayoutState, item: CanonicalItemState): Boolean {
        val caps = state.deviceCapabilities
        return when (val placement = item.placement) {
            is PlacementState.Workspace ->
                placement.cell.x >= 0 &&
                    placement.cell.y >= 0 &&
                    placement.cell.x + placement.span.width <= caps.columns &&
                    placement.cell.y + placement.span.height <= caps.rows

            is PlacementState.Dock -> placement.rank in 0 until caps.hotseatSlots

            is PlacementState.FolderChild ->
                placement.rank >= 0 &&
                    placement.rank < caps.folderMaxColumns * caps.folderMaxRows

            is PlacementState.AppPairChild -> true

            is PlacementState.UnsupportedContainer -> false
        }
    }

    /**
     * Row-addressable write for one captured item. Production item ids are
     * numeric row ids; a non-numeric id cannot address a persisted row and is
     * treated as not found rather than crashing the authoring path.
     */
    private fun CanonicalItemState.toWrite(target: LockTargetState): LockRowWrite? {
        val ref = ref as? ApplicationItemRef.PersistentItem ?: return null
        val rowId = ref.itemId.value.toLongOrNull() ?: return null
        return LockRowWrite(
            rowId = rowId,
            item = ref.itemId,
            expected = this,
            newState = target,
        )
    }

    private fun LayoutState.findItem(item: ItemId): CanonicalItemState? = items.firstOrNull { itemIdOf(it) == item }
}
