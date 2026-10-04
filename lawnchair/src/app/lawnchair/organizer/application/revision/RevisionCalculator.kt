package app.lawnchair.organizer.application.revision

import app.lawnchair.organizer.application.canonical.CanonicalMarshalling
import app.lawnchair.organizer.application.canonical.Digest
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.planning.RevisionId

/**
 * Derives the [RevisionId] for a [LayoutState] and the action-set digest for a
 * list of [ApplyAction]s. Covers every revision dimension required by
 * spec.md §“Revision semantics”:
 *
 *  - every captured item field needed to reproduce or validate the item;
 *  - complete target identity and profile identity/availability;
 *  - workspace placement, span, rank, ordered pages, and device capabilities;
 *  - folder/app-pair identity, exact membership, stage, and snap metadata;
 *  - widget provider, widget ID, bind/restore state, and profile;
 *  - lock state and its ownership metadata; and
 *  - the full profile inventory, including addition/removal of a profile with
 *    no current Favorites rows.
 *
 * `LauncherModel.getLastLoadId()` is **never** used here.
 *
 * Issue #14 Stage B step 1.
 */
object RevisionCalculator {

    /**
     * Stable [RevisionId] for the supplied [LayoutState]. Two byte-identical
     * canonical layouts produce equal revisions; any spec-listed dimension
     * change produces a different revision.
     */
    fun revisionOf(state: LayoutState): RevisionId {
        val sink = CanonicalMarshalling.sink(Digest.Kind.PRE_STATE)
        with(CanonicalMarshalling) { state.encode(sink) }
        val bytes = sink.result()
        return RevisionId(Digest.hexLowerCase(bytes))
    }

    /**
     * Intended post-state revision. Distinct digest kind so an intended state
     * cannot collide with a pre-state of the same shape — important for
     * ambiguous-commit classification.
     */
    fun intendedRevisionOf(state: LayoutState): RevisionId {
        val sink = CanonicalMarshalling.sink(Digest.Kind.INTENDED_POST_STATE)
        with(CanonicalMarshalling) { state.encode(sink) }
        return RevisionId(Digest.hexLowerCase(sink.result()))
    }

    /**
     * Stable digest over the exact list of actions, including order. Used for
     * checkpoint integrity and reconciliation.
     */
    fun actionSetDigestOf(actions: List<ApplyAction>): ByteArray {
        val sink = CanonicalMarshalling.sink(Digest.Kind.ACTION_SET)
        with(CanonicalMarshalling) { actions.encode(sink) }
        return sink.result()
    }

    /**
     * Recovery action-set digest (separate tag from apply so a recovery
     * write-set cannot collide with the original apply action-set).
     */
    fun recoveryActionSetDigestOf(actions: List<ApplyAction>): ByteArray {
        val sink = CanonicalMarshalling.sink(Digest.Kind.RECOVERY_ACTION_SET)
        with(CanonicalMarshalling) { actions.encode(sink) }
        return sink.result()
    }

    /**
     * Reviewed-current-state digest used when classifying the state at recovery
     * time. Separate tag so an exact reviewed state cannot collide with the
     * stored pre-state of the same shape.
     */
    fun reviewedCurrentDigestOf(state: LayoutState): ByteArray {
        val sink = CanonicalMarshalling.sink(Digest.Kind.REVIEWED_CURRENT_STATE)
        with(CanonicalMarshalling) { state.encode(sink) }
        return sink.result()
    }

    /** One domain tag used for every persisted/current state classification operand. */
    fun classificationDigestOf(state: LayoutState): ByteArray {
        val sink = CanonicalMarshalling.sink(Digest.Kind.AUTHORITATIVE_STATE)
        with(CanonicalMarshalling) { state.encode(sink) }
        return sink.result()
    }
}
