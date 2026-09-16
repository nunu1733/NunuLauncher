package app.lawnchair.organizer.personalization.exchange

/**
 * Issue #205: the session replacement gate (spec 205 "Scenario: activityな
 * exchangeがある状態での新規生成"). A new exchange flow must not silently
 * invalidate a live exchange through the #204 single-active-session rule:
 * when an active (unexpired) export session exists, generation only starts
 * after the user explicitly confirms the replacement. Declining leaves the
 * existing session untouched, so answers addressed to it stay importable.
 *
 * The launcher cannot learn from a completed transport whether the package
 * actually left the device, so the gate applies uniformly to any active
 * session regardless of assumed sent state.
 */
object ExchangeGenerationGate {

    /**
     * @param activeSessionExists whether the #204 store currently reports an
     *   active (unexpired) export session.
     * @param userConfirmation the user's answer to the replacement dialog, or
     *   null while no answer has been given yet.
     */
    fun evaluate(activeSessionExists: Boolean, userConfirmation: Boolean?): ExchangeGenerationGateOutcome = when {
        !activeSessionExists -> ExchangeGenerationGateOutcome.Proceed
        userConfirmation == null -> ExchangeGenerationGateOutcome.RequiresConfirmation
        userConfirmation -> ExchangeGenerationGateOutcome.Proceed
        else -> ExchangeGenerationGateOutcome.Aborted
    }
}

sealed interface ExchangeGenerationGateOutcome {
    /** Generation may start now (no live session, or replacement confirmed). */
    data object Proceed : ExchangeGenerationGateOutcome

    /** A live session exists; show the replacement confirmation first. */
    data object RequiresConfirmation : ExchangeGenerationGateOutcome

    /** The user declined the replacement; the existing session stays intact. */
    data object Aborted : ExchangeGenerationGateOutcome
}
