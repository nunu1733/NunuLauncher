package app.lawnchair.organizer.ui.exchange

import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.ui.ManualOrganizationRunTestSupport
import app.lawnchair.organizer.ui.UsageAccessJitGate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #371 (spec 371): the exchange holder's JIT Usage Access pause — the
 * generation kickoff gates on the process-wide request opportunity, the
 * awaiting screen carries the attempt token, and owner destruction follows
 * the gate's state-specific rules (release while un-presented,
 * abandon-resolve once presented).
 */
class ExchangeFlowJitGateTest {

    private val tier = PrivacyTier.EXTERNAL_REDACTED

    /** Production never reaches the controller while the JIT pause holds. */
    private fun explodingScope(): CoroutineScope = CoroutineScope(
        Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )

    @Test
    fun ungrantedGateSuspendsGenerationAtTheAwaitingScreen() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { error("controller must not run while paused") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )

        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)

        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit
        assertTrue(awaiting.isPresenter)
        assertEquals(tier, awaiting.tier)
        assertEquals(null, awaiting.scoped)
        // The controller was never touched (factory throws if reached).
        assertTrue(gate.ownedPhase(ExchangeJitAttemptOwner(awaiting.attemptToken)) == UsageAccessJitGate.Phase.Reserved)
    }

    @Test
    fun grantedGateProceedsStraightToGeneration() {
        val gate = UsageAccessJitGate(isGranted = { true })
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { error("background generation failure is out of scope here") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )

        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)

        assertEquals(ExchangeScreen.Generating, holder.screen)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
    }

    @Test
    fun closeWhileUnpresentedReleasesTheOpportunity() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { error("controller must not run while paused") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit

        holder.close()

        assertEquals(ExchangeScreen.Closed, holder.screen)
        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)
        assertNullGatePhase(gate, awaiting.attemptToken)
    }

    @Test
    fun closeAfterPresentationResolvesSoWaitersProceed() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val owner = ExchangeFlowStateHolder(
            controllerFactory = { error("controller must not run while paused") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        owner.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        val awaiting = owner.screen as ExchangeScreen.AwaitingUsageAccessJit
        gate.markPresented(ExchangeJitAttemptOwner(awaiting.attemptToken))

        owner.close()

        // Abandon resolution: the barrier resolves, the destroyed attempt's
        // generation is never resumed.
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)

        val waiter = ExchangeFlowStateHolder(
            controllerFactory = { error("waiter proceeds without presenting") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        waiter.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        assertEquals(ExchangeScreen.Generating, waiter.screen)
    }

    @Test
    fun staleResumeAfterCloseAndRegenerateIsDropped() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { error("controller must not run while paused") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        val staleToken = (holder.screen as ExchangeScreen.AwaitingUsageAccessJit).attemptToken
        holder.close()

        // A second attempt under the same conditions mints a fresh token.
        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        val fresh = holder.screen as ExchangeScreen.AwaitingUsageAccessJit
        assertTrue(fresh.attemptToken != staleToken)

        // The stale callback must not resume the fresh attempt nor touch the gate.
        holder.continueUsageAccessJit(staleToken)
        assertTrue(holder.screen is ExchangeScreen.AwaitingUsageAccessJit)
        assertEquals(
            UsageAccessJitGate.Phase.Reserved,
            gate.ownedPhase(ExchangeJitAttemptOwner(fresh.attemptToken)),
        )
    }

    @Test
    fun continueResolvesAndGeneratesExactlyOnce() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = ExchangeFlowStateHolder(
            // The resume path's background generation is out of scope here —
            // the screen assertions below are synchronous.
            controllerFactory = { error("background generation failure is out of scope here") },
            run = ManualOrganizationRunTestSupport.newRun(),
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        holder.requestGeneration(replacementConfirmationRequired = false, tier = tier)
        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit
        gate.markPresented(ExchangeJitAttemptOwner(awaiting.attemptToken))

        holder.continueUsageAccessJit(awaiting.attemptToken)
        holder.continueUsageAccessJit(awaiting.attemptToken)

        // After the resume the screen has left the awaiting state; the second
        // callback finds nothing to continue.
        assertFalse(holder.screen is ExchangeScreen.AwaitingUsageAccessJit)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
    }

    private fun assertNullGatePhase(gate: UsageAccessJitGate, attemptToken: Long) {
        assertEquals(null, gate.ownedPhase(ExchangeJitAttemptOwner(attemptToken)))
    }
}
