package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.GlobalPreference
import app.lawnchair.organizer.personalization.GroupSemantic
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.IntentIdentityCalculator
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
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
 * Issue #375 (spec SR-AC-03, Test oracle): the rebind rebuild seam.
 *
 * - **Projection equivalence**: the rebuilt planning input projects
 *   (`IntentPlannerAdapter.project`) to exactly the import-time projection —
 *   identity from the RECORD's saved `IntentIdentity` (injected, never
 *   re-derived), itemPreferences with the `groupProposalLabel` formation
 *   keys, `globalMinimizeMovement` — plus the decisions round-trip
 *   (record decisions → rebuild → completed == record decisions).
 * - **Typed fail-closed**: digest mismatch → `ContextStale` (proposal
 *   survives); discarded record / identity-shape corruption →
 *   `InvalidProposal` — never an exception path.
 */
class RebindIntentRebuilderTest {

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

    private fun builtInputs(): BuiltExportInputs {
        val items = (0 until 6).map { app("item-$it", x = it % 4, y = it / 4) }
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return BuiltExportInputs(
            snapshot = snapshot,
            targets = targets,
            catalog = catalog(),
        )
    }

    private data class BuiltExportInputs(
        val snapshot: LayoutSnapshot,
        val targets: TargetSet,
        val catalog: ActiveCategoryCatalog,
    )

    private fun validatedFixture(): ValidatedPersonalizedIntent {
        val built = builtExport()
        val refs = built.export.items.map { it.ref }
        val gamesRef = built.session.categoryRefs.entries
            .single { (_, identity) -> identity == CategoryIdentity.BuiltIn(CategoryId("GAMES")) }
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

    /** Structural inputs byte-identical to the export-time ones (same snapshot/targets). */
    private fun structural(inputs: BuiltExportInputs) = CanonicalStructuralInputs(
        snapshot = inputs.snapshot,
        targets = inputs.targets,
        resolvedIdentities = emptyMap(),
        catalog = inputs.catalog,
    )

    @Test
    fun rebindProjectionEqualsTheImportTimeProjectionFieldForField() {
        val inputs = builtInputs()
        val built = ContextExportBuilder.build(
            ExportInputs(
                snapshot = inputs.snapshot,
                targets = inputs.targets,
                catalog = inputs.catalog,
                nowEpochMs = now,
            ),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val validated = validatedFixture()
        val original = IntentPlannerAdapter.project(validated)
        val record = durableRecord(validated)

        val outcome = RebindIntentRebuilder.rebuild(
            record = record,
            session = validated.session,
            currentStructural = structural(inputs),
            nowEpochMs = now + 2,
        )
        val rebuilt = (outcome as RebindIntentRebuilder.Outcome.Rebuilt).intent

        // Identity injected from the record — the import-time digest (which
        // covers the dropped rationale/confidence), NOT a re-derivation.
        assertEquals(validated.identity, rebuilt.identity)
        // A re-derivation over the rationale-free rebuilt document differs —
        // proving the seam injected rather than re-derived.
        assertNotEquals(
            record.intentIdentityDigest,
            IntentIdentityCalculator.identity(rebuilt.completed).digest,
        )
        // Full-field projection equality (identity + preferences + label keys).
        assertEquals(original, IntentPlannerAdapter.project(rebuilt))
        // Decisions round-trip: rebuild → completed == record decisions.
        assertEquals(record.toDecisionsMap(), rebuilt.completed.decisions)
        // The anchor's equality reference is the source record itself.
        assertEquals(record, outcome.sourceRecord)
    }

    @Test
    fun digestMismatchFailsTypedContextStaleAndKeepsTheProposal() {
        val inputs = builtInputs()
        val built = ContextExportBuilder.build(
            ExportInputs(
                snapshot = inputs.snapshot,
                targets = inputs.targets,
                catalog = inputs.catalog,
                nowEpochMs = now,
            ),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val validated = validatedFixture()
        val record = durableRecord(validated)
        // A changed home: one item's placement moved — the structural digest
        // covers placements, so this diverges from the session's export-time
        // digest.
        val movedItems = inputs.snapshot.items.mapIndexed { index, item ->
            val placement = item.placement as? CapturedPlacement.Workspace
            if (index == 0 && placement != null) {
                item.copy(
                    placement = CapturedPlacement.Workspace(
                        placement.page,
                        GridCell(placement.cell.x + 1, placement.cell.y),
                        placement.span,
                    ),
                )
            } else {
                item
            }
        }
        val moved = inputs.snapshot.copy(items = movedItems)
        val diverged = inputs.copy(snapshot = moved)

        val outcome = RebindIntentRebuilder.rebuild(
            record = record,
            session = validated.session,
            currentStructural = structural(diverged),
            nowEpochMs = now + 2,
        )
        assertEquals(RebindIntentRebuilder.Outcome.ContextStale, outcome)
        // The proposal itself survives (not invalidated by the structure change).
        assertTrue(record.discarded.not())
    }

    @Test
    fun discardedRecordIsInvalidProposalNotAnException() {
        val inputs = builtInputs()
        val validated = validatedFixture()
        val record = durableRecord(validated).copy(discarded = true)

        val outcome = RebindIntentRebuilder.rebuild(
            record = record,
            session = validated.session,
            currentStructural = structural(inputs),
            nowEpochMs = now + 2,
        )
        assertEquals(record, (outcome as RebindIntentRebuilder.Outcome.InvalidProposal).record)
    }

    @Test
    fun absentRecordIsInvalidProposal() {
        val validated = validatedFixture()
        val outcome = RebindIntentRebuilder.rebuild(
            record = null,
            session = validated.session,
            currentStructural = structural(builtInputs()),
            nowEpochMs = now + 2,
        )
        assertTrue(outcome is RebindIntentRebuilder.Outcome.InvalidProposal)
    }

    @Test
    fun identityShapeCorruptionFailsClosedWithoutThrowing() {
        val inputs = builtInputs()
        val validated = validatedFixture()
        val record = durableRecord(validated)
            .copy(intentIdentitySchemaVersion = "personalized-intent-v3")
        val outcome = RebindIntentRebuilder.rebuild(
            record = record,
            session = validated.session,
            currentStructural = structural(inputs),
            nowEpochMs = now + 2,
        )
        // The reconcile's #375 identity-shape check turns corruption into the
        // typed fail-closed verdict — no IllegalArgumentException from the
        // `IntentIdentity` require.
        assertTrue(outcome is RebindIntentRebuilder.Outcome.InvalidProposal)
    }

    @Test
    fun recordSchemaContractIsUnchanged() {
        // The tombstone reuse keeps the #374 record model intact.
        val validated = validatedFixture()
        val record = durableRecord(validated)
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, record.intentIdentitySchemaVersion)
        assertEquals(64, record.intentIdentityDigest.length)
    }
}

private const val USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
