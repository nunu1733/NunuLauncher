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
        // Spec 205 Decision 2 as amended by spec 348 Decision 3.
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("Goal:"))
        assertTrue(pkg.contains("You may:"))
        assertTrue(pkg.contains("Output contract ("))
        assertTrue(pkg.contains("You must:"))
        assertTrue(pkg.contains("Before sending your final answer, verify:"))
        assertTrue(pkg.contains("Response format:"))
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
