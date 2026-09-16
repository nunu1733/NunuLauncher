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
 * Issue #330 (spec 330 AC-2/AC-4): the completer attaches a state to every
 * export ref without generating any semantic field, normalizes bare entries to
 * unresolved (D-6), and yields one stable identity for every "no judgment"
 * expression form — explicit unresolved, bare entry, and omission.
 */
class IntentCompletionTest {

    private val now = 1_000_000L

    private fun app(id: String, x: Int = 0, locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun buildExport(items: List<CapturedItem>): BuiltExport {
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
    }

    private fun refOf(built: BuiltExport, item: String): String =
        built.session.itemRefs.entries.first { it.value.value == item }.key

    private fun validate(built: BuiltExport, intent: PersonalizedIntentV1): IntentValidation =
        IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = now + 1,
            currentStructuralDigest = built.session.sourceContextDigest,
        )

    @Test
    fun unmentionedRefsCompleteToUnresolvedByOmissionOnly() {
        val built = buildExport(listOf(app("a"), app("b", x = 1), app("c", x = 2)))
        val refA = refOf(built, "a")
        val completed = IntentCompletion.complete(
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refA, importance = Importance.HIGH)),
            ),
            built.export.items.map { it.ref }.toSet(),
        )
        // Completeness: every export ref appears exactly once (spec 330 AC-4).
        assertEquals(built.export.items.map { it.ref }.toSet(), completed.decisions.keys)
        assertEquals(RefDecision.Authored::class, completed.decisions.getValue(refA)::class)
        assertEquals(1, completed.authoredItemCount)
        assertEquals(0, completed.authoredUnresolvedCount)
        assertEquals(2, completed.omittedCount)
        // No inference: omitted refs carry a state only, never field values.
        completed.decisions.forEach { (ref, decision) ->
            if (ref != refA) assertEquals(RefDecision.UnresolvedByOmission, decision)
        }
    }

    @Test
    fun completedIntentNeverCarriesGeneratedItemIntentFields() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val refs = built.export.items.map { it.ref }
        val completed = IntentCompletion.complete(
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refs[0], importance = Importance.NORMAL)),
                unresolvedRefs = listOf(refs[1]),
            ),
            refs.toSet(),
        )
        completed.decisions.forEach { (_, decision) ->
            when (decision) {
                is RefDecision.Authored -> assertEquals(Importance.NORMAL, decision.intent.importance)
                RefDecision.UnresolvedAuthored, RefDecision.UnresolvedByOmission -> Unit
            }
        }
    }

    @Test
    fun aFullyOmittedIntentCompletesToAllUnresolved() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val completed = IntentCompletion.complete(
            PersonalizedIntentV1(exportId = built.export.exportId, itemIntents = emptyList()),
            built.export.items.map { it.ref }.toSet(),
        )
        assertEquals(built.export.items.size, completed.omittedCount)
        assertEquals(0, completed.authoredItemCount + completed.authoredUnresolvedCount)
    }

    @Test
    fun completionIsDeterministic() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val exportRefs = built.export.items.map { it.ref }.toSet()
        val authored = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = exportRefs.first(), pageAffinity = 0)),
        )
        assertEquals(IntentCompletion.complete(authored, exportRefs), IntentCompletion.complete(authored, exportRefs))
    }

    @Test
    fun aBareEntryNormalizesToUnresolvedAuthored() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val refA = refOf(built, "a")
        val completed = IntentCompletion.complete(
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refA)),
            ),
            built.export.items.map { it.ref }.toSet(),
        )
        assertEquals(RefDecision.UnresolvedAuthored, completed.decisions.getValue(refA))
        assertEquals(1, completed.authoredUnresolvedCount)
        assertEquals(0, completed.authoredItemCount)
    }

    @Test
    fun aNonBareEntryIsNeverNormalized() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val refA = refOf(built, "a")
        // `preserve = false` is authored content (spec 330 D-6).
        val completed = IntentCompletion.complete(
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refA, preserve = false)),
            ),
            built.export.items.map { it.ref }.toSet(),
        )
        assertTrue(completed.decisions.getValue(refA) is RefDecision.Authored)
    }

    // ---- stable identity / replay (spec 330 D-5/D-6, AC-4) -----------------

    private fun validatedOf(built: BuiltExport, intent: PersonalizedIntentV1): ValidatedPersonalizedIntent =
        (validate(built, intent) as IntentValidation.Validated).validated

    @Test
    fun bareEntryExplicitUnresolvedAndOmissionShareOneIdentity() {
        val built = buildExport(listOf(app("a"), app("b", x = 1), app("c", x = 2)))
        val refA = refOf(built, "a")
        val refB = refOf(built, "b")
        val bare = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refA)),
            unresolvedRefs = listOf(refB),
        )
        val explicit = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = listOf(refA, refB),
        )
        val omitted = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = listOf(refB),
        )
        val identities = listOf(bare, explicit, omitted).map { validatedOf(built, it).identity }
        assertEquals(identities[0], identities[1])
        assertEquals(identities[1], identities[2])
        // The canonical rows make the equivalence explicit: every "no judgment"
        // form of refA collapses into `unresolved|refA`.
        val canonical = IntentIdentityCalculator.canonicalRepresentation(validatedOf(built, bare).completed)
        assertTrue(canonical.contains("unresolved|$refA"))
        assertTrue(!canonical.contains("item|$refA"))
    }

    @Test
    fun replayOfTheSameSemanticContentYieldsTheSameIdentityAndProjection() {
        val built = buildExport(listOf(app("a"), app("b", x = 1)))
        val refA = refOf(built, "a")
        val refB = refOf(built, "b")
        // Same semantic content ("no judgment for refA, preference for refB"),
        // expressed via a bare entry versus via an explicit unresolved marker.
        val first = validatedOf(
            built,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refA), ItemIntent(ref = refB, importance = Importance.HIGH)),
            ),
        )
        val second = validatedOf(
            built,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refB, importance = Importance.HIGH)),
                unresolvedRefs = listOf(refA),
            ),
        )
        assertEquals(first.identity, second.identity)
        assertEquals(IntentPlannerAdapter.project(first), IntentPlannerAdapter.project(second))
    }

    @Test
    fun plannerProjectionExcludesEveryUnresolvedForm() {
        val built = buildExport(listOf(app("a"), app("b", x = 1), app("c", x = 2)))
        val refA = refOf(built, "a")
        val refB = refOf(built, "b")
        val validated = validatedOf(
            built,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(
                    ItemIntent(ref = refA, importance = Importance.LOW),
                    ItemIntent(ref = refB, preserve = true),
                ),
            ),
        )
        val projection = IntentPlannerAdapter.project(validated)
        assertEquals(setOf(refA, refB).map { built.session.itemRefs.getValue(it) }.toSet(), projection.itemPreferences.map { it.item }.toSet())
        val refC = refOf(built, "c")
        assertTrue(projection.itemPreferences.none { it.item == built.session.itemRefs.getValue(refC) })
    }

    @Test
    fun fixedOmissionIsAcceptedAndProjectedLikeAnyOmission() {
        val built = buildExport(listOf(app("a", locked = true), app("b", x = 1)))
        val refA = refOf(built, "a")
        val refB = refOf(built, "b")
        val validated = validatedOf(
            built,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(ItemIntent(ref = refB, importance = Importance.HIGH)),
            ),
        )
        assertEquals(RefDecision.UnresolvedByOmission, validated.completed.decisions.getValue(refA))
        assertTrue(IntentPlannerAdapter.project(validated).itemPreferences.none { it.item == built.session.itemRefs.getValue(refA) })
    }
}
