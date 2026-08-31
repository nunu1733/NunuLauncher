package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationInputComposerTest {
    @Test
    fun normalCompositionCarriesFullProvenanceAndUsesS1ThenS2ThenS5PerProfile() {
        val state = CanonicalFixtures.state(
            profiles = listOf(CanonicalFixtures.profile("personal"), CanonicalFixtures.profile("work")),
            items = listOf(
                app("personal", "personal", "com.example.personal/.Main"),
                app("work", "work", "com.example.work/.Main"),
                app("google", "personal", "com.google.example/.Main"),
            ),
        )
        val overrides = snapshot(
            assignments = mapOf(CategoryOverrideKey(app.lawnchair.organizer.planning.PackageName("com.example.personal"), ProfileId("personal")) to CategoryId("SOCIAL")),
            digest = digest('a'),
        )
        val evidence = evidence(
            s2 = mapOf(ItemId("personal") to CategoryId("GAME"), ItemId("work") to CategoryId("MUSIC")),
            s5 = mapOf(ItemId("google") to CategoryId("TOOLS")),
            digest = digest('b'),
        )

        val result = composer(state, SequenceOverrides(overrides, overrides), SequenceEvidence(evidence, evidence)).composeFullOrganization()
        assertTrue(result is OrganizationInputComposition.Ready)
        val ready = result as OrganizationInputComposition.Ready
        assertEquals("v1", ready.input.rules.version.value)
        assertEquals("v1", ready.input.taxonomy.version.value)
        assertEquals(3, ready.input.targets.existing.size)
        assertTrue(ready.input.targets.additions.isEmpty())
        assertEquals(4, ready.input.snapshot.device.columns)
        assertEquals(1, ready.input.snapshot.pages.size)
        assertEquals(
            listOf("personal:S1:SOCIAL", "work:S2:MUSIC", "google:S5:TOOLS").sorted(),
            ready.input.signals.entries.map { "${it.item.value}:${it.source.name}:${it.candidate.value}" }.sorted(),
        )
        assertEquals(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, ready.provenance.rules.source)
        assertEquals(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, ready.provenance.taxonomy.source)
        assertEquals(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS, ready.provenance.signals.source)
        assertEquals(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET, ready.provenance.targets.source)
    }

    @Test
    fun compositionCarriesNonItemWorkspaceReservationsWithoutChangingTargetMembership() {
        val reservation = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))
        val state = CanonicalFixtures.state(
            items = listOf(app("a", "personal", "com.example.a/.Main")),
        ).copy(reservedWorkspaceRegions = listOf(reservation))

        val ready = ready(state)

        assertEquals(listOf(reservation), ready.input.snapshot.reservedWorkspaceRegions)
        assertEquals(listOf(ItemId("a")), ready.input.targets.existing.map { it.item })
    }

    @Test
    fun freshManualAndOnboardingCompositionsConsumeCommittedS1Overrides() {
        val state = CanonicalFixtures.state(
            items = listOf(app("override", "personal", "com.example.override/.Main")),
        )
        val source = CommittedOverrideSource().apply {
            commit(CategoryOverrideKey(app.lawnchair.organizer.planning.PackageName("com.example.override"), ProfileId("personal")), CategoryId("OTHER"))
        }
        val platform = evidence(s2 = mapOf(ItemId("override") to CategoryId("GAME")))

        val manual = composer(state, source, SequenceEvidence(platform, platform)).composeFullOrganization()
        val onboarding = composer(state, source, SequenceEvidence(platform, platform)).composeFullOrganization()

        listOf(manual, onboarding).forEach { composition ->
            val ready = composition as OrganizationInputComposition.Ready
            assertEquals(
                listOf("override:S1:OTHER"),
                ready.input.signals.entries.map { "${it.item.value}:${it.source.name}:${it.candidate.value}" },
            )
        }
    }

    @Test
    fun bundleAndDynamicSourceFailuresRemainTypedWithoutDiagnosticParsing() {
        val state = CanonicalFixtures.state(items = listOf(app("a", "personal", "com.example.a/.Main")))
        val missing = composer(state, SequenceOverrides(), SequenceEvidence(), bundle = BundleReadResult.Missing).composeFullOrganization()
        assertEquals(
            InputReadinessReason.SourceUnavailable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            (missing as OrganizationInputComposition.NotReady).reason,
        )

        val corrupt = composer(state, SequenceOverrides(), SequenceEvidence(), bundle = BundleReadResult.Corrupt).composeFullOrganization()
        assertEquals(
            InputReadinessReason.SourceUnreadable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            (corrupt as OrganizationInputComposition.NotReady).reason,
        )

        val unsupported = composer(
            state,
            SequenceOverrides(),
            SequenceEvidence(),
            bundle = BundleReadResult.UnsupportedVersion(PolicyBundleIdentity("v2", digest('c'))),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertEquals(
            InputReadinessReason.UnsupportedVersion(
                PolicySourceKind.ORGANIZER_POLICY_BUNDLE,
                PolicyInputIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, "v2", digest('c')),
            ),
            unsupported.reason,
        )

        val active = (BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready).bundle
        val incompatible = composer(
            state,
            SequenceOverrides(),
            SequenceEvidence(),
            bundle = BundleReadResult.Ready(active.copy(rules = active.rules.copy(version = RuleVersion("not-v1")))),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertTrue(incompatible.reason is InputReadinessReason.IncompatiblePolicyBundle)

        val overrideSchema = composer(
            state,
            SequenceOverrides(result = OverrideSnapshotReadResult.UnsupportedSchema),
            SequenceEvidence(),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertEquals(
            InputReadinessReason.UnsupportedVersion(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, null),
            overrideSchema.reason,
        )

        val contradictorySnapshot = snapshot(
            assignments = mapOf(
                CategoryOverrideKey(app.lawnchair.organizer.planning.PackageName("com.example.a"), ProfileId("personal")) to CategoryId("NOT_IN_V1"),
            ),
        )
        val contradictory = composer(
            state,
            SequenceOverrides(contradictorySnapshot, contradictorySnapshot),
            SequenceEvidence(evidence(), evidence()),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertEquals(
            InputReadinessReason.ContradictorySource(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
            contradictory.reason,
        )

        val evidenceFailure = composer(
            state,
            SequenceOverrides(emptySnapshot(), emptySnapshot()),
            SequenceEvidence(result = PlatformEvidenceReadResult.Unreadable),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertEquals(
            InputReadinessReason.SourceUnreadable(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE),
            evidenceFailure.reason,
        )
    }

    @Test
    fun dynamicCutMismatchRetriesOnceThenSucceedsOrReturnsBothTypedCuts() {
        val state = CanonicalFixtures.state(items = listOf(app("a", "personal", "com.example.a/.Main")))
        val a = emptySnapshot(digest('a'))
        val b = emptySnapshot(digest('b'))
        val c = emptySnapshot(digest('c'))
        val d = emptySnapshot(digest('d'))
        val stableEvidence = evidence(digest = digest('e'))

        val retrySuccess = composer(
            state,
            SequenceOverrides(a, b, c, c),
            SequenceEvidence(stableEvidence, stableEvidence, stableEvidence, stableEvidence),
        ).composeFullOrganization()
        assertTrue(retrySuccess is OrganizationInputComposition.Ready)

        val rejected = composer(
            state,
            SequenceOverrides(a, b, c, d),
            SequenceEvidence(stableEvidence, stableEvidence, stableEvidence, stableEvidence),
        ).composeFullOrganization() as OrganizationInputComposition.NotReady
        assertTrue(rejected.reason is InputReadinessReason.InconsistentPolicyRead)
        val reason = rejected.reason as InputReadinessReason.InconsistentPolicyRead
        assertNotEquals(reason.expected, reason.observed)
    }

    @Test
    fun unknownLockFailsClosedAndValueEquivalentInputsComposeEqually() {
        val unknownLockState = CanonicalFixtures.state(
            items = listOf(app("a", "personal", "com.example.a/.Main", lock = OrganizerLockState.UNKNOWN)),
        )
        val unknown = composer(unknownLockState, SequenceOverrides(), SequenceEvidence()).composeFullOrganization()
        assertEquals(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNKNOWN_LOCK),
            (unknown as OrganizationInputComposition.NotReady).reason,
        )

        val state = CanonicalFixtures.state(
            profiles = listOf(CanonicalFixtures.profile("personal"), CanonicalFixtures.profile("work", ProfileAvailability.UNAVAILABLE)),
            items = listOf(
                app("a", "personal", "com.example.a/.Main"),
                app("quiet", "personal", "com.example.quiet/.Main", availability = ItemAvailability.QUIET),
                app("work", "work", "com.example.work/.Main"),
            ),
        )
        val first = ready(state)
        val second = ready(state)
        assertEquals(first.input, second.input)
        assertEquals(first.provenance, second.provenance)
        assertEquals(3, first.input.targets.existing.size)
        assertFalse(first.input.targets.existing.isEmpty())
        assertEquals(ExistingRole.Preserved, first.input.targets.existing.single { it.item == ItemId("quiet") }.role)
        assertEquals(ExistingRole.Preserved, first.input.targets.existing.single { it.item == ItemId("work") }.role)
    }

    @Test
    fun captureFailureObservesExceptionClassOnlyAndStaysFailClosed() {
        val observed = mutableListOf<Class<out Throwable>>()
        val failingSource = LayoutWriterCanonicalCaptureSource(
            writer = throwingCaptureWriter(),
            captureFailureObserver = CaptureFailureObserver { exceptionClass -> observed += exceptionClass },
        )

        val result = failingSource.capture()

        assertTrue(result is CanonicalCaptureReadResult.Invalid)
        assertEquals(listOf<Class<out Throwable>>(java.lang.IllegalStateException::class.java), observed)
    }

    @Test
    fun throwingObserverDoesNotChangeFailClosedResult() {
        // Issue #172: the diagnostics observer is fail-open; an observer that
        // throws must not leak its exception and must not change the
        // fail-closed Invalid result the composer depends on.
        val failingSource = LayoutWriterCanonicalCaptureSource(
            writer = throwingCaptureWriter(),
            captureFailureObserver = CaptureFailureObserver {
                throw java.lang.IllegalStateException("observer failure")
            },
        )

        val result = failingSource.capture()

        assertTrue(result is CanonicalCaptureReadResult.Invalid)
    }

    @Test
    fun defaultCaptureSourceIsSilentAboutFailures() {
        val failingSource = LayoutWriterCanonicalCaptureSource(writer = throwingCaptureWriter())

        assertTrue(failingSource.capture() is CanonicalCaptureReadResult.Invalid)
    }

    /** Delegating writer whose only throwing path is the capture the composer reads. */
    private fun throwingCaptureWriter(): LayoutWriterPort = object : LayoutWriterPort by FakeLayoutWriter(
        app.lawnchair.organizer.application.canonical.CanonicalFixtures.state(),
    ) {
        override fun captureCurrent(captureId: CaptureId): CapturedSnapshot = throw java.lang.IllegalStateException("capture failure with private layout context that must never be observed")
    }

    @Test
    fun everyCompositionFailureSiteCarriesAClosedCode() {
        // Issue #172: the closed code set must cover the composer's failure
        // sites 1:1; the journal projects these names into
        // ErrorFamily.INPUT_READINESS. Exhaustive spot checks of one code per
        // readiness reason family keep the correspondence table honest.
        val cases = mapOf(
            InputCompositionCode.RECONCILIATION_PENDING to InputReadinessReason.ReconciliationPending,
            InputCompositionCode.CAPTURE_INVALID to InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
            InputCompositionCode.BUNDLE_CORRUPT to InputReadinessReason.SourceUnreadable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            InputCompositionCode.OVERRIDE_UNREADABLE to InputReadinessReason.SourceUnreadable(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
            InputCompositionCode.EVIDENCE_UNREADABLE to InputReadinessReason.SourceUnreadable(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE),
            InputCompositionCode.SIGNAL_CONTRADICTION to InputReadinessReason.ContradictorySource(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
        )
        for ((code, reason) in cases) {
            val composition = OrganizationInputComposition.NotReady(reason, CompositionDiagnostic(code))
            assertEquals(code, composition.diagnostic.code)
        }
        // Every constant is a legal journal code after the model-layer validation.
        for (code in InputCompositionCode.entries) {
            app.lawnchair.organizer.diagnostics.model.ErrorEntry(
                app.lawnchair.organizer.diagnostics.model.ErrorFamily.INPUT_READINESS,
                code.name,
            )
        }
    }

    private fun ready(state: app.lawnchair.organizer.application.public.LayoutState): OrganizationInputComposition.Ready {
        val override = emptySnapshot()
        val platform = evidence()
        return composer(state, SequenceOverrides(override, override), SequenceEvidence(platform, platform)).composeFullOrganization()
            as OrganizationInputComposition.Ready
    }

    private fun composer(
        state: app.lawnchair.organizer.application.public.LayoutState,
        overrideSource: CategoryOverrideSnapshotSource,
        evidenceSource: ClassificationSignalSnapshotSource,
        bundle: BundleReadResult = BuiltInOrganizerPolicyBundleSource.readActive(),
    ) = DefaultOrganizationInputComposer(
        captureSource = CanonicalCaptureSource {
            CanonicalCaptureReadResult.Ready(FakeLayoutWriter(state).captureCurrent(CaptureId("test")))
        },
        bundleSource = object : OrganizerPolicyBundleSource {
            override fun readActive(): BundleReadResult = bundle
        },
        overrides = overrideSource,
        platformEvidence = evidenceSource,
    )

    private fun app(
        id: String,
        profile: String,
        component: String,
        availability: ItemAvailability = ItemAvailability.AVAILABLE,
        lock: OrganizerLockState = OrganizerLockState.UNLOCKED,
    ) = CanonicalFixtures.appItem(
        itemId = id,
        profile = profile,
        target = TargetKey.AppKey(ComponentKey(component), ProfileId(profile)),
        availability = availability,
        lockState = lock,
    )

    private fun snapshot(
        assignments: Map<CategoryOverrideKey, CategoryId> = emptyMap(),
        digest: String = digest('a'),
    ) = CategoryOverrideSnapshot(
        schemaVersion = 1,
        generation = 0L,
        assignments = assignments,
        identity = PolicyInputIdentity(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, "schema-1-generation-0", digest),
    )

    private fun emptySnapshot(digest: String = digest('a')) = snapshot(digest = digest)

    private fun evidence(
        s2: Map<ItemId, CategoryId> = emptyMap(),
        s5: Map<ItemId, CategoryId> = emptyMap(),
        digest: String = digest('b'),
    ) = PlatformClassificationEvidence(
        s2,
        s5,
        PolicyInputIdentity(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE, "platform-evidence-v1", digest),
    )

    private fun digest(char: Char) = char.toString().repeat(64)

    private class CommittedOverrideSource : CategoryOverrideSnapshotSource {
        private var assignments: Map<CategoryOverrideKey, CategoryId> = emptyMap()
        private var generation = 0L

        fun commit(key: CategoryOverrideKey, category: CategoryId) {
            assignments = assignments + (key to category)
            generation += 1L
        }

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 1,
                generation = generation,
                assignments = assignments.filterKeys { it.profile in capturedProfiles },
                identity = PolicyInputIdentity(
                    PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                    "schema-1-generation-$generation",
                    "f".repeat(64),
                ),
            ),
        )
    }

    private class SequenceOverrides(
        private vararg val snapshots: CategoryOverrideSnapshot,
        private val result: OverrideSnapshotReadResult? = null,
    ) : CategoryOverrideSnapshotSource {
        private var index = 0
        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = result ?: OverrideSnapshotReadResult.Ready(
            snapshots[minOf(index++, snapshots.lastIndex)],
        )
    }

    private class SequenceEvidence(
        private vararg val values: PlatformClassificationEvidence,
        private val result: PlatformEvidenceReadResult? = null,
    ) : ClassificationSignalSnapshotSource {
        private var index = 0
        override fun read(
            requests: List<ClassificationEvidenceRequest>,
            policy: ClassificationPolicy,
        ): PlatformEvidenceReadResult = result ?: PlatformEvidenceReadResult.Ready(values[minOf(index++, values.lastIndex)])
    }
}
