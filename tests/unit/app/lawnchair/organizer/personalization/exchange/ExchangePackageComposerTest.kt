package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 AC-1: the exchange package composes an instruction/data
 * separated single text whose separation is machine-verifiable, and the
 * instruction carries the four required sections plus the exact framing
 * markers the agent must echo (spec 205 Decision 2).
 */
class ExchangePackageComposerTest {

    private val exportJson = """{"schemaVersion":"personalization-context-v1","exportId":"id-0"}"""

    @Test
    fun composeAndParseRoundTripRecoversTheExactContextData() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        val parsed = ExchangePackageComposer.parsePackageStructure(pkg)
        assertEquals(PackageStructureResult.Valid(exportJson), parsed)
    }

    @Test
    fun composedPackageContainsTheFourInstructionSections() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains("Goal:"))
        assertTrue(pkg.contains("You may:"))
        assertTrue(pkg.contains("You must:"))
        assertTrue(pkg.contains("Response format:"))
    }

    @Test
    fun instructionEmbedsTheExactIntentMarkersTheAgentMustEcho() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        assertTrue(pkg.contains(INTENT_BEGIN_MARKER))
        assertTrue(pkg.contains(INTENT_END_MARKER))
        assertTrue(pkg.contains("\"personalized-intent-v1\""))
        assertTrue(pkg.contains("\"unresolvedRefs\""))
    }

    @Test
    fun dataBlockStaysASingleVerbatimLineBetweenContextMarkers() {
        val pkg = ExchangePackageComposer.compose(exportJson)
        val lines = pkg.split('\n')
        val dataLines = lines.filter { it == exportJson }
        assertEquals(1, dataLines.size)
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
        val noFooter = pkg.substringBeforeLast('\n').let { it.substringBeforeLast('\n') }
        assertEquals(
            PackageStructureProblem.EMPTY_INSTRUCTION,
            (ExchangePackageComposer.parsePackageStructure("$noFooter\n") as PackageStructureResult.Invalid).problem,
        )
    }
}
