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
 *
 * Issue #417: the kickoff under test is the scoped generation
 * ([ExchangeFlowStateHolder.generateScoped]) — the only creation entry the
 * hosted faces retain — so the fixture run is driven to its frozen scope
 * (`State.ScopeConfirmed`) first. The controller is never touched while the
 * pause holds (the factory throws if reached), exactly as before.
 */
class ExchangeFlowJitGateTest {

    private val tier = PrivacyTier.EXTERNAL_REDACTED
    private val candidate = ManualOrganizationRunTestSupport.readyDetectionCandidate

    /** Production never reaches the controller while the JIT pause holds. */
    private fun explodingScope(): CoroutineScope = CoroutineScope(
        Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )

    /** A holder whose run holds its frozen scope — the scoped generation's claim source. */
    private fun newConfirmedScopeHolder(
        gate: UsageAccessJitGate,
        controllerFactory: () -> app.lawnchair.organizer.integration.exchange.ExchangeFlowController = {
            error("controller must not run while paused")
        },
    ): ExchangeFlowStateHolder {
        val run = ManualOrganizationRunTestSupport.newReadyDetectionRun()
        val holder = ExchangeFlowStateHolder(
            controllerFactory = controllerFactory,
            run = run,
            scope = explodingScope(),
            usageAccessGate = gate,
        )
        run.start()
        run.confirmSelection(setOf(candidate))
        return holder
    }

    private fun requestGeneration(holder: ExchangeFlowStateHolder) {
        holder.generateScoped(tier, listOf(candidate), mapOf(candidate to "c1"))
    }

    @Test
    fun ungrantedGateSuspendsGenerationAtTheAwaitingScreen() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = newConfirmedScopeHolder(gate)

        requestGeneration(holder)

        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit
        assertTrue(awaiting.isPresenter)
        assertEquals(tier, awaiting.tier)
        assertEquals(listOf(candidate), awaiting.scoped!!.first)
        // The controller was never touched (factory throws if reached).
        assertTrue(gate.ownedPhase(ExchangeJitAttemptOwner(awaiting.attemptToken)) == UsageAccessJitGate.Phase.Reserved)
    }

    @Test
    fun grantedGateProceedsStraightToGeneration() {
        val gate = UsageAccessJitGate(isGranted = { true })
        val holder = newConfirmedScopeHolder(
            gate,
            controllerFactory = { error("background generation failure is out of scope here") },
        )

        requestGeneration(holder)

        assertEquals(ExchangeScreen.Generating, holder.screen)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
    }

    @Test
    fun closeWhileUnpresentedReleasesTheOpportunity() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = newConfirmedScopeHolder(gate)
        requestGeneration(holder)
        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit

        holder.close()

        assertEquals(ExchangeScreen.Closed, holder.screen)
        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)
        assertNullGatePhase(gate, awaiting.attemptToken)
    }

    @Test
    fun closeAfterPresentationResolvesSoWaitersProceed() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val owner = newConfirmedScopeHolder(gate)
        requestGeneration(owner)
        val awaiting = owner.screen as ExchangeScreen.AwaitingUsageAccessJit
        gate.markPresented(ExchangeJitAttemptOwner(awaiting.attemptToken))

        owner.close()

        // Abandon resolution: the barrier resolves, the destroyed attempt's
        // generation is never resumed.
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)

        val waiter = newConfirmedScopeHolder(
            gate,
            controllerFactory = { error("waiter proceeds without presenting") },
        )
        requestGeneration(waiter)
        assertEquals(ExchangeScreen.Generating, waiter.screen)
    }

    @Test
    fun staleResumeAfterCloseAndRegenerateIsDropped() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = newConfirmedScopeHolder(gate)
        requestGeneration(holder)
        val staleToken = (holder.screen as ExchangeScreen.AwaitingUsageAccessJit).attemptToken
        holder.close()

        // A second attempt under the same conditions mints a fresh token.
        requestGeneration(holder)
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
        val holder = newConfirmedScopeHolder(
            gate,
            // The resume path's background generation is out of scope here —
            // the screen assertions below are synchronous.
            controllerFactory = { error("background generation failure is out of scope here") },
        )
        requestGeneration(holder)
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

    @Test
    fun staleAttemptTeardownDoesNotActOnANewerAttempt() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val holder = newConfirmedScopeHolder(gate)
        requestGeneration(holder)
        val tokenA = (holder.screen as ExchangeScreen.AwaitingUsageAccessJit).attemptToken
        // A second request under the same conditions mints a new attempt (the
        // first still owns the reservation, so this one waits).
        requestGeneration(holder)
        val tokenB = (holder.screen as ExchangeScreen.AwaitingUsageAccessJit).attemptToken
        assertTrue(tokenA != tokenB)

        // The stale host teardown must not touch the newer attempt.
        holder.disposeUsageAccessJitAttempt(tokenA)
        val awaiting = holder.screen as ExchangeScreen.AwaitingUsageAccessJit
        assertEquals(tokenB, awaiting.attemptToken)
        assertEquals(UsageAccessJitGate.Phase.Reserved, gate.ownedPhase(ExchangeJitAttemptOwner(tokenB)))

        // The current attempt's own teardown abandons itself.
        holder.disposeUsageAccessJitAttempt(tokenB)
        assertEquals(ExchangeScreen.Closed, holder.screen)
        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)
    }
}
