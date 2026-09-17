package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.personalization.ContextExportBuilder.build
import app.lawnchair.organizer.personalization.ScopeMismatchCause
import app.lawnchair.organizer.personalization.exchange.DetectedCandidateScope
import app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer
import app.lawnchair.organizer.personalization.exchange.PackageStructureResult
import app.lawnchair.organizer.personalization.exchange.ReconstructionResult
import app.lawnchair.organizer.personalization.exchange.ScopeBindingCurrentScope
import app.lawnchair.organizer.personalization.exchange.ScopeBindingGate
import app.lawnchair.organizer.personalization.exchange.ScopeBindingOutcome
import app.lawnchair.organizer.personalization.exchange.ScopeBindingSessionScope
import app.lawnchair.organizer.personalization.exchange.SessionExportReconstructor
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #331: the exchange target-scope coupling contract — candidate
 * subjects in the v2 export, the session scope record, the validator's
 * candidate mobility policy, the reconstruction of candidate entries, and the
 * scope binding gate's exact-equality/projection checks (spec D-1..D-5).
 */
class ExchangeTargetScopeCouplingTest {

    private val now = 1_000_000L

    // ---- fixtures ---------------------------------------------------------

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun pages(count: Int = 2) = (0 until count).map { Page(PageId("p$it"), PageOrder(it)) }

    private fun app(id: String) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun candidate(
        component: String,
        profile: String = "p0",
        availability: Availability = Availability.AVAILABLE,
    ) = CandidateItem(
        id = CandidatePlanningIds.planningId(
            CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile)),
        ),
        profile = ProfileId(profile),
        kind = CandidateKind.APPLICATION,
        target = CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile)),
        availability = availability,
        span = GridSpan(1, 1),
    )

    private fun target(component: String, profile: String = "p0") = CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile))

    private fun inputs(
        items: List<CapturedItem>,
        additions: List<CandidateItem> = emptyList(),
        labels: Map<ItemId, String> = emptyMap(),
        resolved: Map<ItemId, String?> = emptyMap(),
    ): ExportInputs {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages(), items, emptyList())
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, additions)
        return ExportInputs(
            snapshot = snapshot,
            targets = targets,
            resolvedIdentities = resolved.mapValues { (_, value) -> builtInIdentity(value) },
            userLabels = labels,
            nowEpochMs = now,
        )
    }

    private fun builtInIdentity(value: String?): CategoryIdentity? = value?.let { CategoryIdentity.BuiltIn(CategoryId(it)) }

    private fun candidateOf(result: BuiltExport) = result.export.items.single { it.subject == ExportItemSubject.CANDIDATE }

    private fun placedOf(result: BuiltExport) = result.export.items.single { it.subject == ExportItemSubject.PLACED }

    // ---- builder: candidate subjects (AC-1/AC-3) ---------------------------

    @Test
    fun selectedCandidatesBecomeCandidateSubjectItems() {
        val additions = listOf(candidate("com.zed"), candidate("com.alpha"))
        val result = build(
            inputs(listOf(app("a")), additions = additions),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val candidates = result.export.items.filter { it.subject == ExportItemSubject.CANDIDATE }
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.mobility == Mobility.CANDIDATE && it.fixReason == null })
        assertTrue(candidates.all { it.role == ExportItemRole.APP_OR_SHORTCUT })
        // Deterministic order independent of the composition input order.
        val planningIds = candidates.map { it.ref }
        val expectedOrder = additions
            .map { it.target as CandidateTarget.AppKey }
            .sortedWith(compareBy({ it.component.value }, { it.profile.value }))
            .map { CandidatePlanningIds.planningId(it) }
        assertEquals(expectedOrder, planningIds.map { result.session.itemRefs.getValue(it) })
        // Session binds candidate refs to their planning identities and
        // records the export scope's candidate identities.
        val candidateRefIds = result.session.candidateRefs.mapNotNull { result.session.itemRefs[it] }
        assertEquals(additions.map { it.id }.toSet(), candidateRefIds.toSet())
        assertEquals(additions.map { it.target }.toSet(), result.session.scopeCandidates.toSet())
    }

    @Test
    fun unselectedCandidatesNeverAppearInTheExport() {
        val result = build(
            inputs(listOf(app("a")), additions = listOf(candidate("com.selected"))),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        assertEquals(2, result.export.items.size) // 1 placed + 1 candidate
        assertEquals(1, result.export.items.count { it.subject == ExportItemSubject.CANDIDATE })
    }

    @Test
    fun candidateLabelsAndCategoriesFollowTierControl() {
        val addition = candidate("com.selected")
        val labels = mapOf(addition.id to "Selected App")
        val resolved = mapOf(addition.id to "ENTERTAINMENT")
        val redacted = build(
            inputs(emptyList(), additions = listOf(addition), labels = labels, resolved = resolved),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        )
        val withLabels = build(
            inputs(emptyList(), additions = listOf(addition), labels = labels, resolved = resolved),
            PrivacyTier.EXTERNAL_WITH_LABELS,
            SequentialIdAllocator(),
        )
        assertNull(redacted.export.items.single().label)
        // Category is taxonomy, not free text: the redacted tier keeps it.
        assertEquals("ENTERTAINMENT", redacted.export.items.single().category)
        assertEquals("Selected App", withLabels.export.items.single().label?.value)
        // No raw identity ever reaches the document.
        val document = redacted.export.toString()
        assertFalse(document.contains("com.selected"))
        assertFalse(document.contains(addition.id.value))
    }

    @Test
    fun candidateUsageProjectionIsIncludedWhenSignalsArePresent() {
        val key = PersonalizationEntryKey(ProfileId("p0"), PackageName("com.selected"))
        val signals = PersonalizationSignalSnapshot(
            usageAccess = UsageAccessState.GRANTED,
            launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE,
            profileAvailability = mapOf(ProfileId("p0") to SystemUsageProfileAvailability.SYSTEM_USAGE_AVAILABLE),
            systemUsage = SystemUsageSection.Available(
                mapOf(
                    key to SystemUsageEntry(
                        SignalField.Value(ForegroundBucket(2)),
                        SignalField.Absent,
                        SignalField.Absent,
                        SignalField.Absent,
                    ),
                ),
            ),
            launcherOrigin = LauncherOriginSection.Available(emptyMap()),
        )
        val addition = candidate("com.selected")
        val result = build(
            inputs(emptyList(), additions = listOf(addition)).copy(
                signals = signals,
                usageKeysByItem = mapOf(addition.id to key),
            ),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val entry = result.export.usageSignals!!.entries.single()
        assertEquals(2, entry.usage.foreground30d)
    }

    // ---- v2 codec + validator policy (AC-5/AC-10) --------------------------

    @Test
    fun codecRoundTripsTheCandidateSubjectAndRejectsV1() {
        val result = build(
            inputs(listOf(app("a")), additions = listOf(candidate("com.selected"))),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        )
        val encoded = ContextExportCodec.encode(result.export) as ContextExportResult.Success
        val decoded = ContextExportCodec.decode(encoded.bytes)
        assertTrue(decoded is ContextExportResult.Success)
        val v3Json = encoded.bytes.decodeToString()
        assertTrue(v3Json.contains("personalization-context-v3"))
        assertTrue(v3Json.contains("\"subject\":\"CANDIDATE\""))
        // v1 documents fail closed on the version check (spec 330 D-3 keeps the
        // single-version runtime; v2 is retired with the same rule).
        val v1Decode = ContextExportCodec.decode(
            v3Json.replace("personalization-context-v3", "personalization-context-v1").encodeToByteArray(),
        )
        assertEquals(ExportEncodeProblem.SchemaMismatch, (v1Decode as ContextExportResult.Failure).problem)
    }

    @Test
    fun preserveOnACandidateRefIsAMobilityContradiction() {
        val result = build(
            inputs(listOf(app("a")), additions = listOf(candidate("com.selected"))),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val candidateRef = candidateOf(result).ref
        val preserve = validateRef(result, candidateRef) { it.copy(preserve = true) }
        assertTrue(
            (preserve as IntentValidation.Failure).failure is IntentValidationFailure.MobilityContradiction,
        )

        // Placement-independent signals stay valid for candidates (spec §4).
        val importance = validateRef(result, candidateRef) {
            it.copy(importance = Importance.HIGH, pageAffinity = 0)
        }
        assertTrue(importance is IntentValidation.Validated)
    }

    @Test
    fun anOmittedCandidateRefCompletesToCanonicalUnresolved() {
        // Issue #330 (v3, spec 330 D-1/D-2): a candidate ref missing from both
        // lists is no longer a coverage violation — candidates are full
        // subjects and their omission means "no judgment".
        val result = build(
            inputs(listOf(app("a")), additions = listOf(candidate("com.selected"))),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val candidateRef = candidateOf(result).ref
        val partial = IntentValidator.validate(
            PersonalizedIntentV1(
                exportId = result.export.exportId,
                itemIntents = listOf(ItemIntent(ref = placedOf(result).ref)),
                unresolvedRefs = emptyList(),
            ),
            result.export,
            result.session,
            now,
            result.session.sourceContextDigest,
        )
        val validated = (partial as IntentValidation.Validated).validated
        assertEquals(RefDecision.UnresolvedByOmission, validated.completed.decisions.getValue(candidateRef))
        // The candidate ref is a full subject: referencing it directly is fine.
        assertTrue(validateRef(result, candidateRef) is IntentValidation.Validated)
    }

    @Test
    fun v1IntentsFailClosedOnDecode() {
        assertTrue(ContextExportContract.INTENT_SCHEMA_VERSION == "personalized-intent-v3")
        val encoded = IntentCodec.encode(
            PersonalizedIntentV1(exportId = "x", itemIntents = emptyList()),
        ).decodeToString()
        val v1Bytes = encoded
            .replace("personalized-intent-v3", "personalized-intent-v1")
            .encodeToByteArray()
        assertTrue(IntentCodec.decode(v1Bytes) is IntentDecodeResult.Failure)
    }

    // ---- planner adapter (AC-5) -------------------------------------------

    @Test
    fun adapterResolvesCandidateRefsToTheirPlanningIds() {
        val addition = candidate("com.selected")
        val result = build(
            inputs(listOf(app("a")), additions = listOf(addition)),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val candidateRef = candidateOf(result).ref
        val validation = validateRef(result, candidateRef) { it.copy(importance = Importance.HIGH) }
        assertTrue(validation is IntentValidation.Validated)
        val projection = IntentPlannerAdapter.project((validation as IntentValidation.Validated).validated)
        val candidatePreference = projection.itemPreferences.single { it.item == addition.id }
        assertEquals(Importance.HIGH, candidatePreference.importance)
    }

    // ---- reconstructor + binding gate (AC-2/AC-6) --------------------------

    @Test
    fun reconstructorRebuildsCandidateEntriesAndTheGatePassesTheSameScope() {
        val addition = candidate("com.selected")
        val result = build(
            inputs(listOf(app("a")), additions = listOf(addition)),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        )
        val structural = CanonicalStructuralInputs(
            LayoutSnapshot(RevisionId("rev"), device(), pages(), listOf(app("a")), emptyList()),
            TargetSet(emptyList(), emptyList()),
            emptyMap(),
        )
        val rebuilt = SessionExportReconstructor.rebuild(result.session, structural)
        val view = (rebuilt as ReconstructionResult.Rebuilt).export
        assertEquals(result.export.items.map { it.ref }.toSet(), view.items.map { it.ref }.toSet())
        assertEquals(Mobility.CANDIDATE, view.items.single { it.subject == ExportItemSubject.CANDIDATE }.mobility)

        // The same scope + projection passes the gate.
        val projections = result.session.scopeCandidates.map {
            CandidateScopeProjection(it, Availability.AVAILABLE, null)
        }
        val outcome = ScopeBindingGate.evaluate(
            ScopeBindingSessionScope(result.session.scopeCandidates, result.session.scopeCandidateDigest),
            ScopeBindingCurrentScope(
                detected = result.session.scopeCandidates.map { DetectedCandidateScope(it, Availability.AVAILABLE) },
                selectedTargets = result.session.scopeCandidates.toSet(),
                candidateProjections = projections,
            ),
        )
        assertEquals(ScopeBindingOutcome.Pass, outcome)
    }

    @Test
    fun gateRejectsDivergentSelectionsAndCategoryDrift() {
        val a = target("com.a")
        val b = target("com.b")
        val digest = CandidateScopeIdentity.digest(
            listOf(CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("NEWS"))),
        )
        val sessionScope = ScopeBindingSessionScope(listOf(a), digest)
        val detected = listOf(
            DetectedCandidateScope(a, Availability.AVAILABLE),
            DetectedCandidateScope(b, Availability.AVAILABLE),
        )

        // Missing selection → set mismatch.
        assertEquals(
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.SET_MISMATCH),
            ScopeBindingGate.evaluate(sessionScope, ScopeBindingCurrentScope(detected, emptySet(), emptyList())),
        )
        // Extra selection → set mismatch (exact equality, spec D-2).
        assertEquals(
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.SET_MISMATCH),
            ScopeBindingGate.evaluate(
                sessionScope,
                ScopeBindingCurrentScope(
                    detected,
                    setOf(a, b),
                    listOf(
                        CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("NEWS")),
                        CandidateScopeProjection(b, Availability.AVAILABLE, null),
                    ),
                ),
            ),
        )
        // Unresolved candidate (no longer detected) → candidate unresolved.
        assertEquals(
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.CANDIDATE_UNRESOLVED),
            ScopeBindingGate.evaluate(sessionScope, ScopeBindingCurrentScope(emptyList(), setOf(a), emptyList())),
        )
        // Unavailable candidate → candidate unresolved.
        assertEquals(
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.CANDIDATE_UNRESOLVED),
            ScopeBindingGate.evaluate(
                sessionScope,
                ScopeBindingCurrentScope(
                    listOf(DetectedCandidateScope(a, Availability.UNAVAILABLE)),
                    setOf(a),
                    listOf(CandidateScopeProjection(a, Availability.UNAVAILABLE, builtInIdentity("NEWS"))),
                ),
            ),
        )
        // Category drift with no layout change → projection mismatch (D-4).
        assertEquals(
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.PROJECTION_MISMATCH),
            ScopeBindingGate.evaluate(
                sessionScope,
                ScopeBindingCurrentScope(
                    detected,
                    setOf(a),
                    listOf(CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("SPORTS"))),
                ),
            ),
        )
        // Exact match with the exported projection passes.
        assertEquals(
            ScopeBindingOutcome.Pass,
            ScopeBindingGate.evaluate(
                sessionScope,
                ScopeBindingCurrentScope(
                    detected,
                    setOf(a),
                    listOf(CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("NEWS"))),
                ),
            ),
        )
    }

    @Test
    fun candidateScopeDigestIsDeterministicAndDriftSensitive() {
        val a = target("com.a")
        val b = target("com.b")
        val same = CandidateScopeIdentity.digest(
            listOf(
                CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("X")),
                CandidateScopeProjection(b, Availability.AVAILABLE, null),
            ),
        )
        val reordered = CandidateScopeIdentity.digest(
            listOf(
                CandidateScopeProjection(b, Availability.AVAILABLE, null),
                CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("X")),
            ),
        )
        assertEquals(same, reordered)
        assertNotEquals(same, CandidateScopeIdentity.digest(listOf(CandidateScopeProjection(a, Availability.AVAILABLE, builtInIdentity("Y")))))
        assertNotEquals(same, CandidateScopeIdentity.digest(listOf(CandidateScopeProjection(a, Availability.UNAVAILABLE, builtInIdentity("X")))))
        assertEquals(CandidateScopeIdentity.digest(emptyList()), CandidateScopeIdentity.EMPTY_DIGEST)
    }

    @Test
    fun failureTaxonomyCarriesThirteenContractClassesIncludingScopeMismatch() {
        // 12 pre-331 classes + SCOPE_MISMATCH = 13 contract classes
        // (spec 331 D-5); with the 4 #205 envelope/framing failures the UI
        // surface is 17 kinds. The exhaustive `when` in the UI failure text
        // enforces the mapping at compile time; this pins the typed class.
        val failure = IntentValidationFailure.ScopeMismatch(ScopeMismatchCause.PROJECTION_MISMATCH)
        assertEquals(
            setOf(ScopeMismatchCause.SET_MISMATCH, ScopeMismatchCause.CANDIDATE_UNRESOLVED, ScopeMismatchCause.PROJECTION_MISMATCH),
            ScopeMismatchCause.values().toSet(),
        )
        assertTrue(failure is IntentValidationFailure)
    }

    @Test
    fun instructionV2ExplainsCandidates() {
        // Issue #348: the instruction still explains CANDIDATE subjects, but
        // no longer requests the INTENT marker lines (the canonical authoring
        // form is a single fenced `json` block; markers stay accepted on
        // import per spec 205/329).
        val packageText = ExchangePackageComposer.compose("{}")
        assertTrue(packageText.contains("personalized-intent-v3"))
        assertTrue(packageText.contains("CANDIDATE"))
        assertTrue(!packageText.contains("-----BEGIN NUNULAUNCHER INTENT-----"))
        assertTrue(ExchangePackageComposer.parsePackageStructure(packageText) is PackageStructureResult.Valid)
    }

    // ---- helpers -----------------------------------------------------------

    private fun validateRef(
        built: BuiltExport,
        ref: String,
        mutate: (ItemIntent) -> ItemIntent = { it },
    ): IntentValidation {
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(mutate(ItemIntent(ref = ref))),
            unresolvedRefs = built.export.items.map { it.ref }.filterNot { it == ref },
        )
        return IntentValidator.validate(intent, built.export, built.session, now, built.session.sourceContextDigest)
    }
}
