package app.lawnchair.organizer.planning

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary Issue #41 green-run proof. Reverted immediately after an Actions
 * run shows organizer-unit-tests executing and passing on a source PR.
 */
class CiGatePassProofTest {
    @Test
    fun intentionalPassRunsInCi() {
        assertTrue(true)
    }
}
