package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.RevisionId

/**
 * Public recover input. Spec §“Recovery input”.
 *
 * [expectedCurrentRevision] is the revision the user reviewed; recovery is
 * rejected with `STALE_REVISION` if the current layout state differs at the
 * recovery transaction.
 *
 * Issue #14 Stage B step 1.
 */
data class RecoveryRequest(
    val pointId: RecoveryPointId,
    val expectedCurrentRevision: RevisionId,
)
