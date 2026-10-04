package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportCodec
import app.lawnchair.organizer.personalization.ContextExportResult
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.IntentWireContract
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RefDecision
import app.lawnchair.organizer.personalization.SequentialIdAllocator
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
 * Issue #327: the canonical intent template embedded in the exchange
 * instruction is anchored to production truth (accepted spec 327 AC-2).
 *
 * Blocking oracle: the template's placeholders are machine-substituted with
 * a synthetic export/session fixture's real values, wrapped in the #348
 * canonical authoring form (a single fenced `json` block), and passed
 * through [ExchangeImportPipeline.import] — reaching `Validated` proves
 * `canonical authoring ⊆ production accepted`, so a template that would
 * drift into a payload the production validator rejects fails here.
 * Auxiliary facts (codec allow-list, descriptor containment, unfenced
 * structure) are pinned in [ExchangePackageComposerTest].
 *
 * The harness follows the same pattern as [Issue348AiFacingContractSyncTest]
 * (which follows `ExchangeImportPipelineTest`): real export builder, real
 * session store, production pipeline seam only.
 */
class Issue327InterviewFirstContractTest {

    private val now = 1_000_000L

    // ---- builders (same harness pattern as Issue348AiFacingContractSyncTest) ----

    private fun app(id: String, x: Int = 0) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    /** Three MOVABLE subjects over two pages: one judged, one unresolved, one omitted. */
    private fun buildState(): Pair<BuiltExport, CanonicalStructuralInputs> {
        val items = listOf(app("a"), app("b", x = 1), app("c", x = 2))
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val structural = CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>())
        val inputs = ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now)
        return ContextExportBuilder.build(inputs, PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator()) to structural
    }

    private fun fencedReply(payloadJson: String): String = "```json\n$payloadJson\n```"

    private fun importOf(
        text: String,
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
    ): ExchangeImportResult = ExchangeImportPipeline.import(text, built.session, structural, now + 1)

    private fun canonicalExportJson(built: BuiltExport): String = when (val encoded = ContextExportCodec.encode(built.export)) {
        is ContextExportResult.Success -> String(encoded.bytes, Charsets.UTF_8)
        is ContextExportResult.Failure -> error("export encode failed: ${encoded.problem}")
    }

    private fun templateLineIn(pkg: String): String = pkg.split('\n')
        .filter { it.contains("REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA") }
        .single()

    private val importancePlaceholder: String =
        "REPLACE_WITH_" + IntentWireContract.enumClaims.getValue("importance").joinToString("_")

    // ---- AC-2 blocking oracle: template ⊆ production accepted ----

    @Test
    fun templateSubstitutionReachesValidationThroughTheProductionPipeline() {
        val (built, structural) = buildState()
        val pkg = ExchangePackageComposer.compose(canonicalExportJson(built))
        val template = templateLineIn(pkg)

        // One judged ref, one explicitly unresolved ref, one omitted ref, a
        // descriptor-spelled importance, and an authored rationale — exactly
        // what the template instructs the agent to produce.
        val judgedRef = built.export.items[0].ref
        val unresolvedRef = built.export.items[1].ref
        val payload = template
            .replace("REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA", built.export.exportId)
            .replace("REPLACE_WITH_A_REF_YOU_HAVE_JUDGED", judgedRef)
            .replace(importancePlaceholder, IntentWireContract.enumClaims.getValue("importance").first())
            .replace("REPLACE_WITH_A_REF_YOU_CANNOT_JUDGE", unresolvedRef)
            .replace("REPLACE_WITH_ONE_SHORT_SENTENCE_ABOUT_YOUR_POLICY", "Keep most apps in place, group work apps")

        assertTrue("substitution must replace every placeholder", !payload.contains("REPLACE_WITH_"))

        val result = importOf(fencedReply(payload), built, structural)
        val validated = result as ExchangeImportResult.Validated
        // Spec 330 partial authoring: the omitted third ref completes as
        // canonical unresolved (no judgment), never guessed.
        assertEquals(1, validated.validated.completed.authoredItemCount)
        assertEquals(1, validated.validated.completed.authoredUnresolvedCount)
        assertEquals(1, validated.validated.completed.omittedCount)
        assertTrue(
            "the judged ref must complete as an authored decision",
            validated.validated.completed.decisions[judgedRef] is RefDecision.Authored,
        )
        assertEquals(
            RefDecision.UnresolvedAuthored,
            validated.validated.completed.decisions[unresolvedRef],
        )
    }

    @Test
    fun templatePlaceholdersNeverCollideWithRealExportData() {
        val (built, _) = buildState()
        val exportJson = canonicalExportJson(built)
        val pkg = ExchangePackageComposer.compose(exportJson)
        val template = templateLineIn(pkg)

        assertTrue(!exportJson.contains("REPLACE_WITH_"))
        assertTrue("template must not echo the real exportId", !template.contains(built.export.exportId))
        for (item in built.export.items) {
            assertTrue("template must not echo the real ref ${item.ref}", !template.contains(item.ref))
        }
        // The template is a single line in the package, distinct from the
        // CONTEXT data block.
        assertEquals(1, pkg.split('\n').count { it == template })
        assertEquals(1, pkg.split('\n').count { it == exportJson.trim() })
    }
}
