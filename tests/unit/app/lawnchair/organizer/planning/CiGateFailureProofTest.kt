package app.lawnchair.organizer.planning

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Temporary Issue #41 CI gate proof. This test intentionally fails so the
 * organizer-unit-tests job and final-status demonstrably block merge. It is
 * reverted in the next commit once the failing run has been recorded.
 */
class CiGateFailureProofTest {
    @Test
    fun intentionalFailureBlocksMerge() {
        assertTrue("Issue #41 failing-test proof: this assertion must fail in CI", false)
    }
}
