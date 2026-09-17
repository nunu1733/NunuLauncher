package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.IntentWireContract
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.CONTEXT_BEGIN_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.CONTEXT_END_MARKER

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
 *
 * Issue #348: the output contract section of the instruction is rendered
 * from the shared [IntentWireContract] wire descriptor (the same source the
 * codec's allow-lists derive from), and the response format requests the
 * single canonical authoring form — one fenced `json` code block containing
 * exactly one JSON object. The INTENT marker framing stays accepted on
 * import (spec 205 framing rules unchanged) but is no longer requested from
 * the agent.
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
        append(outputContractSection())
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
 * The agent-facing instruction part (spec 205 Decision 2 as amended by spec
 * 348): English sections Goal / You may / Output contract / You must / Before
 * sending your final answer / Response format. The Output contract section is
 * rendered from [IntentWireContract] so the allowed properties, enum
 * spellings, and numeric limits are production truth, not hand-written text.
 * The header ends right before the CONTEXT marker; the footer reminds the
 * response format after the data so the single-fenced-block requirement is
 * the last thing the agent reads.
 */
private const val INSTRUCTION_HEADER = """
NunuLauncher External Agent Exchange

Goal:
Propose a semantic organization intent for this user's home screen.

You may:
- Search the web to identify unfamiliar apps
- Compare multiple sources about app purposes and relationships
- Consider the usage signals and the current grouping in the CONTEXT data as preference signals
- Ask the user clarifying questions while you work, before you finalize

You must:
- Use only the properties listed in the Output contract below. Do not add any other property — not as a helpful extra, not under any name. Undefined properties make the whole reply unusable.
- Use only the "ref" values that appear in the CONTEXT data below — in "itemIntents[].ref", in "desiredGroup", and in "unresolvedRefs"
- Mention every "ref" at most once across "itemIntents" and "unresolvedRefs"; you do not have to cover every ref, and anything you leave out is treated as "no judgment" and is never guessed
- Write string values as JSON strings, enum values in UPPERCASE exactly as listed, and numbers as integers — never decimals
- Treat every item with mobility "FIXED" as immovable: author only "preserve": true for it, or leave it out, or list it under "unresolvedRefs" — no other field is allowed on it
- Treat every item with mobility "CONDITIONAL" as position-flexible only: never use "desiredGroup" or "groupSemantic" for it
- Treat every item with subject "CANDIDATE" as an app that is not yet on the home screen: never use "preserve" for it; instead propose its importance, grouping, and page or region preference like for the other apps
- Not propose widget spans or sizes, exact screen coordinates, or database changes
- Author only what you actually judged: put the items you decided on in "itemIntents" with the fields you chose, and put a "ref" in "unresolvedRefs" only when you explicitly decided not to judge it
- If information you need is missing, ask the user before you finalize — do not fill the gap by inventing properties or values, and do not invent a property for an idea the contract cannot express
"""

/**
 * The Output contract section, rendered from the shared wire descriptor
 * (spec 348 Decision 1): the property names, enum spellings, and static
 * limits come from the same source the codec's allow-lists derive from.
 */
private fun outputContractSection(): String = buildString {
    val contract = IntentWireContract
    append("Output contract (the only properties your final JSON may contain):\n")
    for (field in contract.topLevel) {
        append("- ${renderField(field)}\n")
    }
    append("  Each \"itemIntents\" entry is an object with \"ref\" (string, required) and any of:\n")
    for (field in contract.item.filter { it.name != "ref" }) {
        append("  - ${renderField(field)}\n")
    }
    for (field in contract.globalPreference) {
        append("  A \"globalPreference\" object may set ${renderField(field)}.\n")
    }
    for (field in contract.groupSemantic) {
        append("  A \"groupSemantic\" object may set ${renderField(field)}.\n")
    }
    val (anyOfA, anyOfB) = contract.groupSemanticAnyOf
    append("  A \"groupSemantic\" object must set at least one of \"$anyOfA\" or \"$anyOfB\".\n")
    append("  \"pageAffinity\" is a whole number from 0 to (\"gridContext\".\"pageCount\" in the CONTEXT data minus 1).\n")
    append("  At most ${contract.maxItemIntents} \"itemIntents\" entries and at most ${contract.maxUnresolvedRefs} \"unresolvedRefs\" entries.\n")
}

private fun renderField(field: IntentWireContract.WireField): String {
    val required = if (field.optional) "optional" else "required"
    val type = when (field.type) {
        IntentWireContract.WireType.STRING -> "string"
        IntentWireContract.WireType.BOOLEAN -> "boolean"
        IntentWireContract.WireType.INTEGER -> "integer"
        IntentWireContract.WireType.STRING_ARRAY -> "array of strings"
        IntentWireContract.WireType.OBJECT -> "object"
        IntentWireContract.WireType.OBJECT_ARRAY -> "array of objects"
    }
    val detail = when {
        field.enumValues.isNotEmpty() -> ": one of ${field.enumValues.joinToString(", ")}"
        field.name == "schemaVersion" -> ": exactly \"${IntentWireContract.intentSchemaVersion}\""
        field.name == "exportId" -> ": echo the \"exportId\" of the CONTEXT data"
        field.name == "confidence" -> ": whole number from ${IntentWireContract.confidenceMin} to ${IntentWireContract.confidenceMax}"
        field.name == "rationale" -> ": at most ${IntentWireContract.maxRationaleChars} characters, display only"
        field.name == "desiredGroup" -> ": refs from the CONTEXT data that belong in one group; if present, non-empty"
        field.name == "freeText" -> ": at most ${IntentWireContract.maxGroupSemanticFreeTextChars} characters"
        else -> ""
    }
    return "\"${field.name}\" ($type, $required)$detail"
}

/**
 * The finalization self-check and the response format (spec 348 Decisions
 * 2/3): the agent verifies the contract facts right before responding, and
 * the canonical authoring form is one fenced `json` code block containing
 * exactly one JSON object — the framing that survives both message copy and
 * code-block copy on representative provider surfaces (#345 evidence).
 */
private val INSTRUCTION_FOOTER = """
Before sending your final answer, verify:
- "schemaVersion" is exactly "${IntentWireContract.intentSchemaVersion}" and "exportId" echoes the CONTEXT data
- Every property you used is listed in the Output contract — there is no extra field
- Enum values are UPPERCASE as listed, "confidence" is an integer ${IntentWireContract.confidenceMin}-${IntentWireContract.confidenceMax}, and "pageAffinity" is within the page range
- Every "ref" you used exists in the CONTEXT data and is mentioned at most once
- The FIXED, CONDITIONAL, and CANDIDATE rules are respected
- Your reply contains exactly one importable JSON artifact

Response format:
Return the final answer as exactly one JSON object inside a single fenced code block that opens with a line containing only ```json and closes with a line containing only ```. The block contains exactly one JSON object and nothing else. Use exactly one code block in the whole reply — do not return multiple candidates. Keep any commentary outside the code block minimal. Do not repeat these instructions or the CONTEXT data.
""".trimIndent()
