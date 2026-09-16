package app.lawnchair.organizer.personalization.exchange

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #205 AC-13 (gate half): a live export session forces an explicit
 * replacement confirmation before generation starts; declining keeps the
 * existing exchange importable; no live session proceeds directly.
 */
class ExchangeGenerationGateTest {

    @Test
    fun noActiveSessionProceedsImmediately() {
        assertEquals(
            ExchangeGenerationGateOutcome.Proceed,
            ExchangeGenerationGate.evaluate(activeSessionExists = false, userConfirmation = null),
        )
        assertEquals(
            ExchangeGenerationGateOutcome.Proceed,
            ExchangeGenerationGate.evaluate(activeSessionExists = false, userConfirmation = false),
        )
    }

    @Test
    fun activeSessionRequiresConfirmationBeforeAnyGeneration() {
        assertEquals(
            ExchangeGenerationGateOutcome.RequiresConfirmation,
            ExchangeGenerationGate.evaluate(activeSessionExists = true, userConfirmation = null),
        )
    }

    @Test
    fun confirmedReplacementProceeds() {
        assertEquals(
            ExchangeGenerationGateOutcome.Proceed,
            ExchangeGenerationGate.evaluate(activeSessionExists = true, userConfirmation = true),
        )
    }

    @Test
    fun declinedReplacementAbortsAndKeepsTheExistingSessionUntouched() {
        assertEquals(
            ExchangeGenerationGateOutcome.Aborted,
            ExchangeGenerationGate.evaluate(activeSessionExists = true, userConfirmation = false),
        )
    }
}
