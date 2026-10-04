package app.lawnchair.organizer.locks

import app.lawnchair.organizer.locks.LockAuthoringDecision.evaluateChange
import app.lawnchair.organizer.locks.LockAuthoringDecision.evaluateReviewBatch
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.RevisionId

/**
 * Public lock-authoring seam. Callers (launcher popup, preferences screen)
 * and tests share this interface; neither reaches the writer or DB directly.
 *
 * Preview first ([explain], [lockStateListing], [reviewListing]), then mutate
 * only from an explicit confirm action supplying [UserReviewedIntent].
 */
class LockAuthoringModule(
    private val captures: LockCapturePort,
    private val writer: LockStateWriterPort,
) {

    /** Fresh authoritative capture; the only read path used by the UI. */
    fun currentCapture(): LockCapture = captures.capture()

    /** Every actionable row with its stored/effective lock view. */
    fun lockStateListing(): List<LockStateEntry> = lockStateListing(captures.capture().state)

    /** Deterministic `UNKNOWN` review listing for the current capture. */
    fun reviewListing(): LockReviewListing = LockReviewListing.of(captures.capture().state)

    /**
     * Pre-mutation explanation for changing [item] to [target]: current
     * state, effective protection, and the precedence notes the UI must show.
     * Performs no write and needs no intent.
     */
    fun explain(item: ItemId, target: LockTargetState): LockExplanation {
        val capture = captures.capture()
        val state = capture.state.lockEntryOf(item)
            ?: return LockExplanation.Unavailable(LockRejection.ITEM_NOT_FOUND)
        val entry = lockStateListing(capture.state).firstOrNull { it.item == item }
            ?: return LockExplanation.Unavailable(LockRejection.UNSUPPORTED_ITEM)
        return LockExplanation.Available(
            entry = entry,
            notes = explainEffect(capture.state, state, target),
        )
    }

    /** Executes one reviewed single-item change. */
    fun setLock(request: LockStateChangeRequest): LockChangeResult = execute(evaluateChange(captures.capture(), request))

    /** Executes one reviewed batch of `UNKNOWN` resolutions atomically. */
    fun reviewBatch(request: LockBatchReviewRequest): LockChangeResult = execute(evaluateReviewBatch(captures.capture(), request))

    private fun execute(decision: LockDecision): LockChangeResult = when (decision) {
        LockDecision.NoChange -> LockChangeResult.NoChange

        is LockDecision.Rejected -> LockChangeResult.Rejected(decision.reason)

        is LockDecision.Ready -> when (val outcome = writer.write(decision.plan)) {
            is LockWriteOutcome.Committed ->
                LockChangeResult.Changed(decision.plan.writes, outcome.newRevision)

            is LockWriteOutcome.Rejected -> LockChangeResult.Rejected(
                when (outcome.reason) {
                    LockWriteRejection.STALE_REVISION,
                    LockWriteRejection.PRECONDITION_FAILED,
                    -> LockRejection.STALE_CAPTURE

                    LockWriteRejection.WRITER_BUSY -> LockRejection.WRITER_BUSY
                },
            )

            is LockWriteOutcome.Failed -> LockChangeResult.Failed(outcome.cause)
        }
    }
}

/** Pre-mutation preview result. */
sealed interface LockExplanation {
    data class Available(
        val entry: LockStateEntry,
        val notes: List<LockEffectNote>,
    ) : LockExplanation

    data class Unavailable(val reason: LockRejection) : LockExplanation
}

/** Post-mutation result for UI messaging. */
sealed interface LockChangeResult {
    data class Changed(
        val writes: List<LockRowWrite>,
        val newRevision: RevisionId?,
    ) : LockChangeResult

    data object NoChange : LockChangeResult
    data class Rejected(val reason: LockRejection) : LockChangeResult
    data class Failed(val cause: Throwable) : LockChangeResult
}
