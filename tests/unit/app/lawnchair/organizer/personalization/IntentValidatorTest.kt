package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
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
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 AC-5/AC-13: every typed failure is fail-closed zero-write, and the
 * coverage partition invariant plus per-ref mobility semantics hold.
 */
class IntentValidatorTest {

    private val now = 1_000_000L
    private val ttl = ContextExportContract.SESSION_TTL_MS

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun app(id: String, x: Int = 0, y: Int = 0, locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String) = app(id).copy(kind = ItemKind.APPWIDGET)

    private fun docked(id: String) = app(id).copy(placement = CapturedPlacement.Dock(0))

    private fun buildState(items: List<CapturedItem>): Pair<BuiltExport, CanonicalStructuralInputs> {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), listOf(Page(PageId("p0"), PageOrder(0))), items)
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val structural = CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>())
        val inputs = ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now)
        return ContextExportBuilder.build(inputs, PrivacyTier.LOCAL_FULL, SequentialIdAllocator()) to structural
    }

    private fun validate(
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
        intent: PersonalizedIntentV1,
        currentDigest: String = SourceContextIdentity.digest(structural),
        at: Long = now + 1,
    ): IntentValidation = IntentValidator.validate(
        intent = intent,
        export = built.export,
        session = built.session,
        nowEpochMs = at,
        currentStructuralDigest = currentDigest,
    )

    private fun refsOf(built: BuiltExport): Map<String, String> = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }

    @Test
    fun aFullyCoveredIntentIsAcceptedWithAnIdentity() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it, importance = Importance.NORMAL) },
        )
        val validation = validate(built, structural, intent)
        assertTrue(validation is IntentValidation.Validated)
        val validated = (validation as IntentValidation.Validated).validated
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, validated.identity.schemaVersion)
        assertEquals(64, validated.identity.digest.length)
    }

    @Test
    fun unknownRefIsRejectedFailClosed() {
        val (built, structural) = buildState(listOf(app("a")))
        val ref = built.export.items.first().ref
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = ref, importance = Importance.HIGH)),
            unresolvedRefs = listOf("ghost"),
        )
        assertEquals(
            IntentValidationFailure.UnknownRef("ghost"),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun duplicateRefsAreRejectedFailClosed() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0]), ItemIntent(ref = refs[0])),
        )
        assertEquals(
            IntentValidationFailure.DuplicateRef,
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun aPartialResponseIsAcceptedAsCanonicalUnresolvedOmission() {
        // Issue #330 (v3, spec 330 D-1): a ref missing from both lists is no
        // longer a coverage violation — it completes to canonical unresolved.
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        val missing = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0])),
        )
        val validated = (validate(built, structural, missing) as IntentValidation.Validated).validated
        assertEquals(
            RefDecision.UnresolvedByOmission,
            validated.completed.decisions.getValue(refs[1]),
        )
        // A full-coverage document stays valid (v3 is a superset of v2).
        val full = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = refs,
        )
        assertTrue(validate(built, structural, full) is IntentValidation.Validated)
    }

    @Test
    fun coverageSplitViolationsAreStillRejected() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        // Overlap: the same ref in both collections.
        val overlapping = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0])),
            unresolvedRefs = listOf(refs[0]),
        )
        assertEquals(
            IntentValidationFailure.IncompleteCoverage,
            (validate(built, structural, overlapping) as IntentValidation.Failure).failure,
        )
        // Duplicate inside unresolvedRefs.
        val duplicatedUnresolved = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = listOf(refs[0], refs[0]),
        )
        assertEquals(
            IntentValidationFailure.IncompleteCoverage,
            (validate(built, structural, duplicatedUnresolved) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun bareEntryWithAnUnknownRefIsStillUnknownRef() {
        // Issue #330 (spec 330 D-6): a bare entry validates like any authored
        // entry — normalization to unresolved happens after validation, so a
        // fabricated ref is never accepted through the bare form.
        val (built, structural) = buildState(listOf(app("a")))
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = "ghost")),
        )
        assertEquals(
            IntentValidationFailure.UnknownRef("ghost"),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun aFullyOmittedIntentIsAccepted() {
        // Issue #330 (v3, spec 330 contract detail 8): an empty intent is
        // legal; the planner effect equals an all-unresolved explicit intent.
        val (built, structural) = buildState(listOf(app("a")))
        val intent = PersonalizedIntentV1(exportId = built.export.exportId, itemIntents = emptyList())
        val validated = (validate(built, structural, intent) as IntentValidation.Validated).validated
        assertEquals(
            RefDecision.UnresolvedByOmission,
            validated.completed.decisions.getValue(refsOf(built).values.first()),
        )
    }

    @Test
    fun unmatchedExportIdIsAnExportMismatch() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = "other-export",
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.ExportMismatch,
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun expiredSessionsAreRejectedWithSessionExpired() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.SessionExpired,
            (validate(built, structural, intent, at = now + ttl + 1) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun structuralChangeIsContextStale() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val movedState = structural.copy(
            snapshot = structural.snapshot.copy(
                items = structural.snapshot.items.map { it.copy(placement = (it.placement as CapturedPlacement.Workspace).copy(cell = GridCell(3, 3))) },
            ),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.ContextStale,
            (validate(built, structural, intent, currentDigest = SourceContextIdentity.digest(movedState)) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun signalOnlyChangesNeverRejectTheImport() {
        // Simulate the #205 round trip: export, usage signal rebuild, import.
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        // The current structural digest is unchanged; the #203 snapshot the AI
        // saw at export time is recorded as provenance only, never matched.
        assertTrue(validate(built, structural, intent) is IntentValidation.Validated)
    }

    @Test
    fun fixedRefContradictionsAreMobilityContradictions() {
        val (built, structural) = buildState(listOf(app("a", locked = true), app("b", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("a"), pageAffinity = 0),
                ItemIntent(ref = refs.getValue("b")),
            ),
        )
        assertEquals(
            IntentValidationFailure.MobilityContradiction(refs.getValue("a")),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun lockedItemMovementPreferenceIsNotForbiddenContent() {
        // 3rd review: locked ref + semantic field is MOBILITY_CONTRADICTION
        // (schema-permitted field), never FORBIDDEN_CONTENT.
        val (built, structural) = buildState(listOf(app("a", locked = true)))
        val lockedRef = refsOf(built).getValue("a")
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = lockedRef, importance = Importance.HIGH)),
        )
        val failure = (validate(built, structural, intent) as IntentValidation.Failure).failure
        assertEquals(IntentValidationFailure.MobilityContradiction(lockedRef), failure)
    }

    @Test
    fun widgetGroupMembershipIsAMobilityContradiction() {
        val (built, structural) = buildState(listOf(widget("w1"), app("a", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("w1"), desiredGroupRefs = listOf(refs.getValue("a"))),
                ItemIntent(ref = refs.getValue("a")),
            ),
        )
        assertEquals(
            IntentValidationFailure.MobilityContradiction(refs.getValue("w1")),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun fixedRefPreserveOnlyIntentsAreAccepted() {
        val (built, structural) = buildState(listOf(docked("d"), app("a", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("d"), preserve = true),
                ItemIntent(ref = refs.getValue("a"), pageAffinity = 0),
            ),
        )
        assertTrue(validate(built, structural, intent) is IntentValidation.Validated)
    }

    @Test
    fun validatorIsTotalOverArbitraryInputs() {
        val (built, structural) = buildState(listOf(app("a")))
        val digest = SourceContextIdentity.digest(structural)
        val arbitrary = listOf(
            ByteArray(0),
            "not json".encodeToByteArray(),
            "{}".encodeToByteArray(),
            """{"schemaVersion":"personalized-intent-v3","exportId":"${built.export.exportId}","itemIntents":[{"ref":"ghost"}]}"""
                .encodeToByteArray(),
        )
        for (bytes in arbitrary) {
            val validation = when (val decoded = IntentCodec.decode(bytes)) {
                is IntentDecodeResult.Failure -> IntentValidation.Failure(decoded.failure)

                is IntentDecodeResult.Success -> IntentValidator.validate(
                    decoded.intent,
                    built.export,
                    built.session,
                    now + 1,
                    digest,
                )
            }
            assertTrue(
                "validator must resolve every input: ${bytes.decodeToString()}",
                validation is IntentValidation.Validated || validation is IntentValidation.Failure,
            )
        }
    }
}
