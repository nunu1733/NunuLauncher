package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.IntentWireContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 AC-1: the exchange package composes an instruction/data
 * separated single text whose separation is machine-verifiable, and the
 * instruction carries the required sections and the canonical authoring form
 * (spec 205 Decision 2 as amended by spec 348 Decisions 2/3).
 *
 * The descriptor-derived output contract content is pinned in depth by
 * [Issue348AiFacingContractSyncTest]; this class owns the composer's own
 * structural contract.
 */
class ExchangePackageComposerTest {

    private val exportJson = """{"schemaVersion":"personalization-context-v3","exportId":"id-0"}"""

    @Test
    fun composeAndParseRoundTripRecoversTheExactContextData() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        val parsed = ExchangePackageComposer.parsePackageStructure(pkg)
        assertEquals(PackageStructureResult.Valid(exportJson), parsed)
    }

    @Test
    fun composedPackageContainsTheRequiredInstructionSections() {
        // Spec 205 Decision 2 as amended by spec 348 Decision 3, extended by
        // spec 327 with the Phase 1 interview section.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("Goal:"))
        assertTrue(pkg.contains("Interview first (Phase 1):"))
        assertTrue(pkg.contains("You may:"))
        assertTrue(pkg.contains("Output contract ("))
        assertTrue(pkg.contains("You must:"))
        assertTrue(pkg.contains("Before sending your final answer, verify:"))
        assertTrue(pkg.contains("Response format:"))
    }

    @Test
    fun interviewFirstPhase1IsPinnedInTheOpening() {
        // Spec 327 AC-1: the instruction mandates a bounded interview before
        // any final artifact, a policy summary, and the user's confirmation.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("Your first response must NOT contain the final JSON artifact"))
        assertTrue(pkg.contains("2 to 4 short questions"))
        assertTrue(pkg.contains("summarize the organization policy"))
        assertTrue(pkg.contains("Produce the final JSON artifact only after the user confirms"))
        // The interview contract lives in the opening (before the CONTEXT
        // data), and the footer gates the final answer on the confirmation.
        val interview = pkg.indexOf("Interview first (Phase 1):")
        val contextData = pkg.indexOf(ExchangeContract.CONTEXT_BEGIN_MARKER)
        assertTrue("interview section must precede the CONTEXT data", interview in 0 until contextData)
        assertTrue(pkg.contains("Send the final answer only after the user confirmed the policy summary"))
    }

    @Test
    fun skipDeclarationContractIsPinned() {
        // Spec 327 AC-3 (Decision 1): a user skip declaration substitutes for
        // the confirmation, never for the summary — summary and final artifact
        // travel in the same reply, with no extra questions.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("explicitly says the questions are not needed"))
        assertTrue(pkg.contains("reply with your brief policy summary and the final artifact together in that same reply"))
        assertTrue(pkg.contains("never skip the summary"))
    }

    @Test
    fun instructionRequestsTheCanonicalAuthoringFormInsteadOfMarkers() {
        // Spec 348 Decision 2: the agent is asked for one fenced `json` code
        // block; the marker framing stays accepted on import but is no
        // longer requested from the producer.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("```json"))
        assertTrue(pkg.contains("exactly one JSON object"))
        assertTrue(pkg.contains("single fenced code block"))
        assertTrue(pkg.contains("\"${ContextExportContract.INTENT_SCHEMA_VERSION}\""))
        assertTrue(pkg.contains("\"unresolvedRefs\""))
        assertTrue(!pkg.contains("-----BEGIN NUNULAUNCHER INTENT-----"))
        assertTrue(!pkg.contains("-----END NUNULAUNCHER INTENT-----"))
    }

    @Test
    fun sectionsAppearInTheSpecifiedOrder() {
        // Spec 348 Decision 3: Goal / You may / Output contract / You must /
        // Before sending your final answer / Response format.
        val pkg = ExchangePackageComposer.compose(exportJson)
        val headings = listOf(
            "Goal:",
            "You may:",
            "Output contract (",
            "You must:",
            "Before sending your final answer, verify:",
            "Response format:",
        )
        val indexes = headings.map { pkg.indexOf(it) }
        assertTrue("a heading is missing: $indexes", indexes.all { it >= 0 })
        assertEquals("headings out of order: $indexes", indexes, indexes.sorted())
    }

    @Test
    fun instructionAsksForPartialAuthoringInsteadOfFullCoverage() {
        // Issue #330 (v3, spec 330 contract detail 9): the v2 "cover every ref
        // exactly once" requirement is replaced by the partial-authoring
        // contract — author only judged items; omissions stay unguessed.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("Author only what you actually judged"))
        assertTrue(pkg.contains("you do not have to cover every ref"))
        assertTrue(!pkg.contains("Cover every"))
    }

    @Test
    fun canonicalIntentTemplateIsPresentSemanticNeutralAndUnfenced() {
        // Spec 327 AC-2 (structure lane): the template shows the payload
        // structure with all-placeholder values and seeds no actual judgment;
        // it is unfenced and marker-free so it cannot be mistaken for the
        // requested reply code block.
        val pkg = ExchangePackageComposer.compose(exportJson)
        val templateLine = pkg.split('\n')
            .filter { it.contains("REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA") }
        assertEquals("exactly one template line", 1, templateLine.size)
        val template = templateLine.single()
        val importancePlaceholder = "REPLACE_WITH_" +
            IntentWireContract.enumClaims.getValue("importance").joinToString("_")
        assertTrue(
            template.contains("\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\""),
        )
        assertTrue(template.contains("\"exportId\":\"REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA\""))
        assertTrue(template.contains("\"ref\":\"REPLACE_WITH_A_REF_YOU_HAVE_JUDGED\""))
        assertTrue(template.contains("\"importance\":\"$importancePlaceholder\""))
        assertTrue(template.contains("\"unresolvedRefs\":[\"REPLACE_WITH_A_REF_YOU_CANNOT_JUDGE\"]"))
        assertTrue(template.contains("REPLACE_WITH_ONE_SHORT_SENTENCE_ABOUT_YOUR_POLICY"))
        // No judgment-bearing optional field is seeded in the template.
        for (field in listOf("desiredGroup", "groupSemantic", "pageAffinity", "regionAffinity", "preserve", "globalPreference", "confidence")) {
            assertTrue("template must not contain \"$field\"", !template.contains("\"$field\""))
        }
        // Unfenced and marker-free.
        val lines = pkg.split('\n')
        val templateIndex = lines.indexOf(template)
        assertTrue(!template.contains("```"))
        assertTrue(!lines[templateIndex - 1].startsWith("```"))
        assertTrue(!lines[templateIndex + 1].startsWith("```"))
        assertTrue(!template.contains("-----BEGIN NUNULAUNCHER INTENT-----"))
        assertTrue(!template.contains("-----END NUNULAUNCHER INTENT-----"))
    }

    @Test
    fun dataBlockStaysASingleVerbatimLineBetweenContextMarkers() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        val lines = pkg.split('\n')
        val dataLines = lines.filter { it == exportJson }
        assertEquals(1, dataLines.size)
    }

    @Test
    fun everyDescriptorPropertyNameIsRenderedIntoThePackage() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        for (group in listOf(
            IntentWireContract.topLevel,
            IntentWireContract.item,
            IntentWireContract.globalPreference,
            IntentWireContract.groupSemantic,
        )) {
            for (field in group) {
                assertTrue("missing ${field.name}", pkg.contains("\"${field.name}\""))
            }
        }
    }

    @Test
    fun tamperedStructuresAreTypedRejects() {
        assertEquals(
            PackageStructureProblem.MISSING_CONTEXT_BLOCK,
            (ExchangePackageComposer.parsePackageStructure("no block here") as PackageStructureResult.Invalid).problem,
        )
        val pkg = ExchangePackageComposer.compose(exportJson)
        val doubled = pkg + "\n" + pkg
        assertEquals(
            PackageStructureProblem.AMBIGUOUS_CONTEXT_BLOCK,
            (ExchangePackageComposer.parsePackageStructure(doubled) as PackageStructureResult.Invalid).problem,
        )
        val emptyData = pkg.replace(exportJson, "   ")
        assertEquals(
            PackageStructureProblem.EMPTY_DATA,
            (ExchangePackageComposer.parsePackageStructure(emptyData) as PackageStructureResult.Invalid).problem,
        )
        // The footer is multi-line (spec 348 self-check + response format),
        // so strip everything after the CONTEXT END marker instead of
        // trimming lines from the end.
        val withoutFooter = pkg.substringBefore(ExchangeContract.CONTEXT_END_MARKER) +
            ExchangeContract.CONTEXT_END_MARKER + "\n"
        assertEquals(
            PackageStructureProblem.EMPTY_INSTRUCTION,
            (ExchangePackageComposer.parsePackageStructure(withoutFooter) as PackageStructureResult.Invalid).problem,
        )
    }
}
