package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.ScopeMismatchCause
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #369 (spec RD-7): every coordinator state has exactly one
 * deterministic face — display never depends on StateFlow conflation. This
 * table fixes the 20状態 → 8ユーザー状態対応表 from accepted spec 369.
 */
class ManualOrganizationFaceTest {

    @Test
    fun everyStateMapsToItsSpecifiedFace() {
        val cases: List<Pair<ManualOrganizationRun.State, ManualOrganizationFace>> = listOf(
            ManualOrganizationRun.State.Idle to ManualOrganizationFace.PREAMBLE,
            ManualOrganizationRun.State.Cancelled to ManualOrganizationFace.PREAMBLE,

            // TO-BE D-06: the empty cut without intent scope or rejection is the
            // internal pass-through — preparation, never the selection surface.
            ManualOrganizationRun.State.Selecting(
                runId = RunId(FACE_TEST_RUN_ID),
                candidates = emptyList(),
            ) to ManualOrganizationFace.PREPARATION,
            // A non-empty cut (or scope mismatch) is the real T-08 surface.
            ManualOrganizationRun.State.Selecting(
                runId = RunId(FACE_TEST_RUN_ID),
                candidates = listOf(detected("com.example.a/.Main")),
            ) to ManualOrganizationFace.SELECTION,
            ManualOrganizationRun.State.Selecting(
                runId = RunId(FACE_TEST_RUN_ID),
                candidates = emptyList(),
                intentScopeCount = 2,
            ) to ManualOrganizationFace.SELECTION,
            ManualOrganizationRun.State.Selecting(
                runId = RunId(FACE_TEST_RUN_ID),
                candidates = emptyList(),
                scopeRejection = IntentValidationFailure.ScopeMismatch(ScopeMismatchCause.SET_MISMATCH),
            ) to ManualOrganizationFace.SELECTION,

            ManualOrganizationRun.State.Capturing to ManualOrganizationFace.PREPARATION,
            ManualOrganizationRun.State.CandidateDetection to ManualOrganizationFace.PREPARATION,
            ManualOrganizationRun.State.Planning to ManualOrganizationFace.PREPARATION,

            ManualOrganizationRun.State.Preview(summary(), details = null) to ManualOrganizationFace.CONFIRMATION,
            ManualOrganizationRun.State.PreviewUnavailable(summary()) to ManualOrganizationFace.CONFIRMATION,

            ManualOrganizationRun.State.Applying to ManualOrganizationFace.APPLYING,

            ManualOrganizationRun.State.Applied(appliedResult(), summary()) to ManualOrganizationFace.RESULT,
            ManualOrganizationRun.State.NoChanges to ManualOrganizationFace.RESULT,
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED) to ManualOrganizationFace.RESULT,

            ManualOrganizationRun.State.InputUnavailable(
                InputReadinessReason.InvalidCanonicalCapture(
                    app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                ),
            ) to ManualOrganizationFace.FAILURE,
            ManualOrganizationRun.State.PlanningRejected(
                ManualOrganizationRun.PlanningFailureKind.INVALID,
                summary(),
            ) to ManualOrganizationFace.FAILURE,
            ManualOrganizationRun.State.ScopeMismatchFailed(
                IntentValidationFailure.ScopeMismatch(ScopeMismatchCause.SET_MISMATCH),
            ) to ManualOrganizationFace.FAILURE,

            ManualOrganizationRun.State.InspectingRecovery to ManualOrganizationFace.RECOVERY,
            ManualOrganizationRun.State.Recovering to ManualOrganizationFace.RECOVERY,
            ManualOrganizationRun.State.RecoveryResultState(RecoveryResult.WriterBusy) to ManualOrganizationFace.RECOVERY,
        )

        for ((state, expected) in cases) {
            assertEquals(
                "state ${state::class.simpleName} mapped to the wrong face",
                expected,
                manualOrganizationFace(state),
            )
        }
    }

    @Test
    fun readinessReasonsAreAFailureFace() {
        // The T-13 failure family is decided by the state kind; a
        // ReconciliationPending reason keeps its spec 172 waiting copy.
        assertEquals(
            ManualOrganizationFace.FAILURE,
            manualOrganizationFace(
                ManualOrganizationRun.State.InputUnavailable(InputReadinessReason.ReconciliationPending),
            ),
        )
    }

    private fun summary() = ManualOrganizationRun.Summary(
        movedCount = 0,
        preservedCount = 0,
        newFolderCount = 0,
        newPageCount = 0,
        organizationStrategy = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        scope = ManualOrganizationRun.Summary.Scope(
            targetCount = 0,
            targetProfileCount = 1,
            pageCount = 1,
            columns = 4,
            rows = 5,
            hotseatSlots = 4,
        ),
        movedByReason = emptyMap(),
        preservedByReason = emptyMap(),
        rejectedByReason = emptyMap(),
        unplacedByReason = emptyMap(),
        warningCounts = emptyMap(),
        constraints = ManualOrganizationRun.Summary.Constraints(
            lockedCount = 0,
            unavailableCount = 0,
            widgetCount = 0,
            appPairCount = 0,
            legacyShortcutCount = 0,
            emptyFolderCount = 0,
            availabilityCounts = emptyMap(),
        ),
    )

    private fun detected(component: String) = app.lawnchair.organizer.integration.DetectedCandidate(
        target = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
            app.lawnchair.organizer.planning.ComponentKey(component),
            app.lawnchair.organizer.planning.ProfileId("personal"),
        ),
        label = component,
        availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
    )

    private fun appliedResult() = ApplyResult.Applied(RunId(FACE_TEST_RUN_ID), RecoveryPointId(FACE_TEST_POINT_ID))

    private companion object {
        const val FACE_TEST_RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FACE_TEST_POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
