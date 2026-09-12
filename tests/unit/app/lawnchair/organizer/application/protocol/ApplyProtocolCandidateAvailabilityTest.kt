package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #228 spec §6 / AC-7: apply-time candidate availability
 * re-verification — a non-launchable candidate (or a failing verification)
 * rejects the apply before any checkpoint or write; the check never fires for
 * candidate-free plans.
 */
class ApplyProtocolCandidateAvailabilityTest {

    private val candidateTarget = CandidateTarget.AppKey(ComponentKey("com.example.new/.Main"), ProfileId("personal"))

    private class FakeAvailabilityPort(var result: AvailabilityVerification) : CandidateAvailabilityPort {
        var calls = 0
        var lastCandidates: List<CandidateTarget.AppKey> = emptyList()

        override fun verifyLaunchable(candidates: List<CandidateTarget.AppKey>): AvailabilityVerification {
            calls += 1
            lastCandidates = candidates
            return result
        }
    }

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var port: FakeAvailabilityPort
    private lateinit var protocol: ApplyProtocol

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(sourceState())
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        port = FakeAvailabilityPort(AvailabilityVerification.AllAvailable)
        protocol = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(), RecordingFaultInjector(), RunMutex(), candidateAvailability = port)
    }

    @Test
    fun unavailableCandidateRejectsBeforeAnyWrite() {
        port.result = AvailabilityVerification.Unavailable(listOf(candidateTarget))

        val result = protocol.apply(candidatePlan())

        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CANDIDATE_UNAVAILABLE, (result as ApplyResult.Rejected).reason)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
        assertEquals(1, port.calls)
        assertEquals(listOf(candidateTarget), port.lastCandidates)
    }

    @Test
    fun failedVerificationIsFailClosed() {
        port.result = AvailabilityVerification.Unknown("platform read failed")

        val result = protocol.apply(candidatePlan())

        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CANDIDATE_UNAVAILABLE, (result as ApplyResult.Rejected).reason)
        assertEquals(0, writer.appliedWriteSets)
    }

    @Test
    fun allAvailableCandidatesApplyThroughTheNormalPath() {
        val plan = candidatePlan()

        val result = protocol.apply(plan)

        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)
        assertEquals(1, writer.appliedWriteSets)
        // Identity resolution is the writer's job: the candidate lands as a
        // persistent application row with its resolved content.
        val finalItems = writer.currentState().items
        assertEquals(2, finalItems.size)
        val inserted = finalItems.single { it.ref != plan.sourceState.items.single().ref }
        assertEquals(CanonicalItemKind.Application, inserted.kind)
        assertEquals(TargetKey.AppKey(candidateTarget.component, candidateTarget.profile), inserted.targetKey)
        assertEquals("New App", (inserted.title as OptionalText.Present).value)
    }

    @Test
    fun candidateFreePlanNeverConsultsThePort() {
        val result = protocol.apply(preserveOnlyPlan())

        assertTrue(result is ApplyResult.NoChanges)
        assertEquals(0, port.calls)
    }

    @Test
    fun candidatePlanWithoutAPortFailsClosed() {
        val legacyProtocol = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(), RecordingFaultInjector(), RunMutex())

        val result = legacyProtocol.apply(candidatePlan())

        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CANDIDATE_UNAVAILABLE, (result as ApplyResult.Rejected).reason)
        assertEquals(0, writer.appliedWriteSets)
    }

    private fun sourceState() = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(0, 0))))

    private fun candidateItem(): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PlannedCandidate(ItemId("candidate-1")),
        kind = CanonicalItemKind.Application,
        targetKey = TargetKey.AppKey(candidateTarget.component, candidateTarget.profile),
        profile = candidateTarget.profile,
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(
            page = sourceState().pages.single().ref,
            cell = GridCell(1, 1),
            span = GridSpan(1, 1),
        ),
        title = OptionalText.Present("New App"),
        intent = OptionalText.Present("#Intent;"),
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.Plain,
    )

    private fun candidatePlan(): ValidatedLayoutPlan {
        val source = sourceState()
        val intended = source.copy(items = source.items + candidateItem())
        return ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(source),
            sourceState = source,
            intendedState = intended,
            actions = listOf(
                ApplyAction.Preserve(source.items.single().ref, source.items.single()),
                ApplyAction.Insert(candidateItem().ref, candidateItem()),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    private fun preserveOnlyPlan(): ValidatedLayoutPlan {
        val source = sourceState()
        return ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(source),
            sourceState = source,
            intendedState = source,
            actions = listOf(ApplyAction.Preserve(source.items.single().ref, source.items.single())),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }
}
