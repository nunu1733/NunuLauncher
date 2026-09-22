package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CompletedPersonalIntent
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.GlobalPreference
import app.lawnchair.organizer.personalization.GroupSemantic
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.IntentIdentity
import app.lawnchair.organizer.personalization.IntentIdentityCalculator
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PersonalizationContextExportV1
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RefDecision
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.durablePendingIntentFrom
import app.lawnchair.organizer.personalization.toDecisionsMap
import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.Availability
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
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #374 DI-AC-01 (summary + planner-projection equivalence part): a
 * durable record derived at import time plus its export session reproduce
 * (a) the exact privacy-safe import summary (every count, multiple distinct
 * proposal labels, built-in/user category split, minimizeMovement) and
 * (b) the planner projection (`IntentPlannerAdapter.project`) with full field
 * equality — identity from the SAVED digest, itemPreferences with the
 * `groupProposalLabel` formation keys, and globalMinimizeMovement.
 */
class DurableImportSummaryTest {

    private val now = 1_000L

    private fun app(id: String, x: Int, y: Int) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    /** One built-in (GAMES) plus one user-defined category so both kinds resolve. */
    private fun catalog() = ActiveCategoryCatalog(
        builtIn = TaxonomyContract(
            TaxonomyVersion("tv1"),
            listOf(CategoryId("OTHER"), CategoryId("GAMES")),
            CategoryId("OTHER"),
        ),
        userDefined = listOf(UserDefinedCategory(UserCategoryId(USER_CATEGORY_ID), "Commute")),
    )

    private fun builtExport(): BuiltExport {
        val items = (0 until 6).map { app("item-$it", x = it % 4, y = it / 4) }
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, catalog = catalog(), nowEpochMs = now),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
    }

    /**
     * A validated fixture exercising every durable field: authored decisions
     * with an existing built-in category ref, an existing user-defined
     * category ref, two DISTINCT proposal labels, desiredGroupRefs, placement
     * and preserve, plus explicit-unresolved and omitted refs — and a
     * rationale/confidence the durable record must NOT carry.
     */
    private fun validatedFixture(): ValidatedPersonalizedIntent {
        val built = builtExport()
        val refs = built.export.items.map { it.ref }
        val gamesRef = built.session.categoryRefs.entries
            .single { (_, identity) -> identity == CategoryIdentity.BuiltIn(CategoryId("GAMES")) }
            .key
        val userCategoryRef = built.session.categoryRefs.entries
            .single { (_, identity) -> identity is CategoryIdentity.UserDefined }
            .key
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(
                    ref = refs[0],
                    importance = Importance.HIGH,
                    desiredGroupRefs = listOf(refs[1]),
                    groupSemantic = GroupSemantic(categoryRef = gamesRef, proposalLabel = null),
                    pageAffinity = 0,
                    regionAffinity = ExportRegionKind.TOP,
                    preserve = true,
                ),
                ItemIntent(
                    ref = refs[1],
                    groupSemantic = GroupSemantic(categoryRef = userCategoryRef, proposalLabel = null),
                ),
                ItemIntent(ref = refs[2], groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Morning")),
                ItemIntent(ref = refs[3], groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Evening")),
            ),
            unresolvedRefs = listOf(refs[4]),
            globalPreference = GlobalPreference(minimizeMovement = true),
            rationale = "agent note",
            confidence = 72,
        )
        val validation = IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = now + 1,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        return (validation as IntentValidation.Validated).validated
    }

    private fun durableRecord(validated: ValidatedPersonalizedIntent): DurablePendingIntent = durablePendingIntentFrom(
        completed = validated.completed,
        identity = validated.identity,
        entryKind = PendingImportEntryKind.RUN_IN,
        nowEpochMs = now + 1,
        expiresAtEpochMs = validated.session.expiresAtEpochMs,
    )

    @Test
    fun durableImportSummaryReproducesEveryCountOfTheImportTimeSummary() {
        val validated = validatedFixture()
        val expected = exchangeImportSummary(
            completed = validated.completed,
            scopeCandidateCount = validated.session.scopeCandidates.size,
            categoryKindByRef = validated.export.categories.associate { it.ref to it.kind },
        )
        // Fixture sanity: the interesting dimensions are all exercised.
        assertEquals("two distinct proposal labels", 2, expected.proposedGroupCount)
        assertEquals(1, expected.builtInCategoryCount)
        assertEquals(1, expected.userCategoryCount)
        assertEquals(4, expected.recognizedCount)
        assertEquals("explicit-unresolved + omitted", 2, expected.noJudgmentCount)
        assertTrue(expected.minimizeMovement)

        val record = durableRecord(validated)
        assertEquals(expected, durableImportSummary(record, validated.session))
    }

    /**
     * DI-AC-01 planner projection equivalence: rebuilding a
     * [ValidatedPersonalizedIntent] from the durable record (Authored entries
     * as item intents, explicit-unresolved refs, the SAVED identity) projects
     * to exactly the import-time projection. `UnresolvedByOmission` is not
     * expressible in `PersonalizedIntentV1` but produces the same canonical
     * decision (omission), so the rebuilt completion matches field for field.
     */
    @Test
    fun plannerProjectionRebuiltFromTheDurableRecordEqualsTheImportTimeProjection() {
        val validated = validatedFixture()
        val original = IntentPlannerAdapter.project(validated)
        val record = durableRecord(validated)
        val rebuilt = rebuiltValidatedIntent(record, export = validated.export, session = validated.session)

        // The saved identity is the import-time digest over the canonical
        // representation INCLUDING the dropped rationale/confidence; a
        // recomputation from the rationale-free rebuilt intent differs, so
        // passing the saved identity is the contract under test.
        assertNotEquals(record.intentIdentityDigest, IntentIdentityCalculator.identity(rebuilt.completed).digest)

        assertEquals(original, IntentPlannerAdapter.project(rebuilt))
    }

    private fun rebuiltValidatedIntent(
        record: DurablePendingIntent,
        export: PersonalizationContextExportV1,
        session: ExportSession,
    ): ValidatedPersonalizedIntent {
        val decisions = record.toDecisionsMap()
        val intent = PersonalizedIntentV1(
            exportId = record.exportId,
            itemIntents = decisions.values.filterIsInstance<RefDecision.Authored>().map { it.intent },
            unresolvedRefs = decisions.filterValues { it == RefDecision.UnresolvedAuthored }.keys.toList(),
            globalPreference = if (record.minimizeMovement) GlobalPreference(minimizeMovement = true) else null,
        )
        return ValidatedPersonalizedIntent(
            intent = intent,
            export = export,
            session = session,
            identity = IntentIdentity(record.intentIdentitySchemaVersion, record.intentIdentityDigest),
        )
    }

    /**
     * Spec 374 "同一の純粋導出と同一入力基準": the completed intent rebuilt for
     * the summary keeps exactly the canonical decisions and the
     * planner-effective global preference — rationale/confidence stay null.
     */
    @Test
    fun summaryRebuildDropsRationaleAndConfidenceByConstruction() {
        val validated = validatedFixture()
        val record = durableRecord(validated)
        val completed = CompletedPersonalIntent(
            exportId = record.exportId,
            decisions = record.toDecisionsMap(),
            globalPreference = if (record.minimizeMovement) GlobalPreference(minimizeMovement = true) else null,
            rationale = null,
            confidence = null,
        )
        assertEquals(validated.completed.decisions, completed.decisions)
        assertEquals(validated.completed.authoredItemCount, completed.authoredItemCount)
        assertEquals(validated.completed.authoredUnresolvedCount, completed.authoredUnresolvedCount)
        assertEquals(validated.completed.omittedCount, completed.omittedCount)
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, record.intentIdentitySchemaVersion)
    }

    private companion object {
        /** Canonical lowercase UUID v4 fixture; a session-only identity. */
        const val USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
    }
}
