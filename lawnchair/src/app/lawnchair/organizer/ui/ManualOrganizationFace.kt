package app.lawnchair.organizer.ui

/**
 * Issue #369 (spec RD-7): the user-visible face a run coordinator state
 * renders as — the TO-BE 8-state integration target
 * (organizer-to-be-ux.md §8.1, spec 20状態→8ユーザー状態対応表). A pure
 * function on purpose: display must never depend on StateFlow conflation
 * (a main collector can observe intermediate publishes), so every coordinator
 * state has exactly one deterministic face, decided here and unit-tested.
 */
internal enum class ManualOrganizationFace {
    /** T-07 run preamble — no run is active (Idle/Cancelled). */
    PREAMBLE,

    /** T-08 missing-app selection. */
    SELECTION,

    /** T-09 preparation — detection → capture → plan progress. */
    PREPARATION,

    /** T-10 proposal confirmation (concrete / count-only / re-preview variants). */
    CONFIRMATION,

    /** T-11 applying. */
    APPLYING,

    /** T-12 result (success / no changes / not applied / partial failure variants). */
    RESULT,

    /** T-13 integrated failure — 実行できませんでした (原因 + 次の手段). */
    FAILURE,

    /** T-14 recovery confirmation. */
    RECOVERY,
}

/**
 * Issue #369 (spec RD-7 / RD-3): maps a coordinator state to its face. The
 * empty `Selecting` cut with no export-scope candidates and no rejection is
 * the internal zero-candidate pass-through (TO-BE D-06): the machine enters
 * the state (transition contract unchanged, disposition §3.3) and the
 * coordinator's own continuation immediately drives the composed phase, but
 * the face is T-09 preparation — the selection surface (T-08) only shows for
 * a real choice or for a spec 331 mismatch re-display.
 */
internal fun manualOrganizationFace(state: ManualOrganizationRun.State): ManualOrganizationFace = when (state) {
    ManualOrganizationRun.State.Idle,
    ManualOrganizationRun.State.Cancelled,
    -> ManualOrganizationFace.PREAMBLE

    is ManualOrganizationRun.State.Selecting -> if (
        state.candidates.isEmpty() &&
        state.intentScopeCount == 0 &&
        state.scopeRejection == null
    ) {
        ManualOrganizationFace.PREPARATION
    } else {
        ManualOrganizationFace.SELECTION
    }

    ManualOrganizationRun.State.Capturing,
    ManualOrganizationRun.State.CandidateDetection,
    ManualOrganizationRun.State.Planning,
    -> ManualOrganizationFace.PREPARATION

    is ManualOrganizationRun.State.Preview,
    is ManualOrganizationRun.State.PreviewUnavailable,
    -> ManualOrganizationFace.CONFIRMATION

    ManualOrganizationRun.State.Applying -> ManualOrganizationFace.APPLYING

    is ManualOrganizationRun.State.Applied,
    ManualOrganizationRun.State.NoChanges,
    -> ManualOrganizationFace.RESULT

    // Issue #369 (D-12): the stale origin decides the face — the apply-time
    // stale is a result variant, the entry-time stale never reached the
    // confirmation face and renders as the integrated failure face.
    is ManualOrganizationRun.State.Stale ->
        when (state.origin) {
            ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED -> ManualOrganizationFace.RESULT
            ManualOrganizationRun.StaleOrigin.DETECTED_BEFORE_REVIEW -> ManualOrganizationFace.FAILURE
        }

    is ManualOrganizationRun.State.InputUnavailable,
    is ManualOrganizationRun.State.CandidateResolutionFailed,
    is ManualOrganizationRun.State.PlanningRejected,
    is ManualOrganizationRun.State.ScopeMismatchFailed,
    -> ManualOrganizationFace.FAILURE

    ManualOrganizationRun.State.InspectingRecovery,
    is ManualOrganizationRun.State.RecoveryPreview,
    ManualOrganizationRun.State.Recovering,
    is ManualOrganizationRun.State.RecoveryResultState,
    -> ManualOrganizationFace.RECOVERY
}
