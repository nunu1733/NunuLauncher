package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.exchange.ExchangeContract.CONTEXT_BEGIN_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.CONTEXT_END_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER

/**
 * Issue #205: composes the exchange package — the agent-facing instruction
 * part and the machine-delimited CONTEXT data part in one immutable text
 * (spec 205 "Scenario: export package生成と送信前確認"). The composed value is
 * complete at generation time; disclosure and transport must hand over this
 * exact value (AC-12).
 *
 * The instruction/data separation is machine-verifiable via
 * [parsePackageStructure] (AC-1): the data block sits between full-line
 * CONTEXT markers, so a consumer can always recover the data without
 * interpreting the prose.
 */
object ExchangePackageComposer {

    /**
     * Composes the package for one canonical export JSON document. The data
     * must be the #204 codec's canonical single-line JSON; the composer never
     * interprets or rewraps it.
     */
    fun compose(exportJson: String): String = buildString {
        append(INSTRUCTION_HEADER.trim('\n'))
        append('\n')
        append(CONTEXT_BEGIN_MARKER)
        append('\n')
        append(exportJson)
        append('\n')
        append(CONTEXT_END_MARKER)
        append('\n')
        append(INSTRUCTION_FOOTER.trim('\n'))
        append('\n')
    }

    /**
     * Machine verification of the package structure (AC-1): exactly one
     * CONTEXT marker pair, a non-empty data line region, and non-empty
     * instruction header/footer around it.
     */
    fun parsePackageStructure(packageText: String): PackageStructureResult {
        val lines = packageText.split('\n')
        val begin = lines.withIndex()
            .filter { IntentImportParser.isMarkerLine(it.value, CONTEXT_BEGIN_MARKER) }
            .map { it.index }
        val end = lines.withIndex()
            .filter { IntentImportParser.isMarkerLine(it.value, CONTEXT_END_MARKER) }
            .map { it.index }
        return when {
            begin.isEmpty() || end.isEmpty() ->
                PackageStructureResult.Invalid(PackageStructureProblem.MISSING_CONTEXT_BLOCK)

            begin.size > 1 || end.size > 1 ->
                PackageStructureResult.Invalid(PackageStructureProblem.AMBIGUOUS_CONTEXT_BLOCK)

            begin[0] > end[0] ->
                PackageStructureResult.Invalid(PackageStructureProblem.MISSING_CONTEXT_BLOCK)

            else -> {
                val data = lines.subList(begin[0] + 1, end[0]).joinToString("\n").trim()
                val header = lines.subList(0, begin[0]).joinToString("\n").trim()
                val footer = lines.subList(end[0] + 1, lines.size).joinToString("\n").trim()
                when {
                    data.isEmpty() -> PackageStructureResult.Invalid(PackageStructureProblem.EMPTY_DATA)
                    header.isEmpty() -> PackageStructureResult.Invalid(PackageStructureProblem.EMPTY_INSTRUCTION)
                    footer.isEmpty() -> PackageStructureResult.Invalid(PackageStructureProblem.EMPTY_INSTRUCTION)
                    else -> PackageStructureResult.Valid(data)
                }
            }
        }
    }
}

sealed interface PackageStructureResult {
    /** The CONTEXT data block content (the canonical export JSON). */
    data class Valid(val contextDataJson: String) : PackageStructureResult

    data class Invalid(val problem: PackageStructureProblem) : PackageStructureResult
}

enum class PackageStructureProblem {
    MISSING_CONTEXT_BLOCK,
    AMBIGUOUS_CONTEXT_BLOCK,
    EMPTY_DATA,
    EMPTY_INSTRUCTION,
}

/**
 * The agent-facing instruction part (spec 205 Decision 2/4): English, four
 * sections (Goal / You may / You must / Response format), with the exact
 * INTENT marker lines the agent must echo. The header ends right before the
 * CONTEXT marker; the footer reminds the response format after the data so
 * the marker requirement is the last thing the agent reads.
 */
private const val INSTRUCTION_HEADER = """
NunuLauncher External Agent Exchange

Goal:
Propose a semantic organization intent for this user's home screen.

You may:
- Search the web to identify unfamiliar apps
- Compare multiple sources about app purposes and relationships
- Consider the usage signals and the current grouping in the CONTEXT data as preference signals
- Ask the user clarifying questions if the request is ambiguous

You must:
- Use only the "ref" values that appear in the CONTEXT data below
- Treat every item with mobility "FIXED" as immovable: only "preserve" or an "unresolvedRefs" entry is valid for it
- Not propose widget spans or sizes, exact screen coordinates, or database changes
- Cover every "ref" exactly once across "itemIntents" and "unresolvedRefs"
- Echo the "exportId" of this context data in your response

Response format:
Return the final answer as one JSON object with "schemaVersion" "personalized-intent-v1", placed between these two exact marker lines with nothing else between them:
-----BEGIN NUNULAUNCHER INTENT-----
-----END NUNULAUNCHER INTENT-----
Text before or after the marker lines is allowed and will be ignored.

CONTEXT data (machine-readable; do not modify):
"""

private const val INSTRUCTION_FOOTER = """
Reminder: reply with your commentary (if any) and the intent JSON between the exact INTENT marker lines shown above. Do not repeat these instructions or the CONTEXT data.
"""
