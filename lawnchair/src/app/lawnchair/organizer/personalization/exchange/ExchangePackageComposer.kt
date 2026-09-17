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
     * interprets or rewraps it. Section order follows accepted spec 348
     * Decision 3: Goal / You may / Output contract / You must / [CONTEXT
     * data] / Before sending your final answer + Response format.
     */
    fun compose(exportJson: String): String = buildString {
        append(INSTRUCTION_OPEN.trim('\n'))
        append('\n')
        append(outputContractSection())
        append('\n')
        append(youMustSection())
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
 * The agent-facing instruction opening (spec 205 Decision 2 as amended by
 * spec 348 Decision 3): the Goal and You may sections. The header ends right
 * before the Output contract section.
 */
private const val INSTRUCTION_OPEN = """
NunuLauncher External Agent Exchange

Goal:
Propose a semantic organization intent for this user's home screen.

You may:
- Search the web to identify unfamiliar apps
- Compare multiple sources about app purposes and relationships
- Consider the usage signals and the current grouping in the CONTEXT data as preference signals
- Ask the user clarifying questions while you work, before you finalize
"""

/**
 * The Output contract section, rendered from the shared wire descriptor
 * (spec 348 Decision 1): field facts come from the [IntentWireContract.FieldSpec]
 * objects, entry limits from the entry-limit claims, and policy sentences
 * from the policy claims — the same source the sync test's parity cases are
 * keyed on.
 */
private fun outputContractSection(): String = buildString {
    val contract = IntentWireContract
    append("Output contract (the only properties your final JSON may contain):\n")
    for (spec in contract.topLevel) {
        append("- ${renderField(spec)}\n")
    }
    append("  Each \"itemIntents\" entry is an object with \"ref\" (string, required) and any of:\n")
    for (spec in contract.item.filter { it.name != "ref" }) {
        append("  - ${renderField(spec)}\n")
    }
    for (spec in contract.globalPreference) {
        append("  A \"globalPreference\" object may set ${renderField(spec)}.\n")
    }
    for (spec in contract.groupSemantic) {
        append("  A \"groupSemantic\" object may set ${renderField(spec)}.\n")
    }
    val (anyOfA, anyOfB) = contract.groupSemanticAnyOf
    append("  A \"groupSemantic\" object must set at least one of \"$anyOfA\" or \"$anyOfB\".\n")
    val pageSpec = contract.field("pageAffinity")
    append(
        "  \"pageAffinity\" is a whole number from ${pageSpec.min} to " +
            "(\"gridContext\".\"pageCount\" in the CONTEXT data minus 1).\n",
    )
    fun entryLimit(name: String): Int = (contract.claim("$name.entryLimit").semantic as IntentWireContract.Semantic.EntryLimit).max
    append(
        "  At most ${entryLimit("itemIntents")} \"itemIntents\" entries " +
            "and at most ${entryLimit("unresolvedRefs")} \"unresolvedRefs\" entries.\n",
    )
}

private fun renderField(spec: IntentWireContract.FieldSpec): String {
    val required = if (spec.required) "required" else "optional"
    val type = when (spec.type) {
        IntentWireContract.WireType.STRING -> "string"
        IntentWireContract.WireType.BOOLEAN -> "boolean"
        IntentWireContract.WireType.INTEGER -> "integer"
        IntentWireContract.WireType.STRING_ARRAY -> IntentWireContract.policySentence("policy.stringListElements")
        IntentWireContract.WireType.OBJECT -> "object"
        IntentWireContract.WireType.OBJECT_ARRAY -> "array of objects"
    }
    val detail = when {
        spec.enumValues.isNotEmpty() -> ": one of ${spec.enumValues.joinToString(", ")}"

        spec.exactValue != null -> ": exactly \"${spec.exactValue}\""

        spec.name == "exportId" -> ": echo the \"exportId\" of the CONTEXT data"

        spec.name == "confidence" -> ": whole number from ${spec.min} to ${spec.max}"

        spec.name == "rationale" -> ": at most ${spec.maxLength} characters, display only"

        spec.name == "desiredGroup" -> {
            ": refs from the CONTEXT data that belong in one group; " +
                IntentWireContract.policySentence("policy.desiredGroupNonEmpty")
        }

        spec.name == "freeText" -> ": at most ${spec.maxLength} characters"

        else -> ""
    }
    return "\"${spec.name}\" ($type, $required)$detail"
}

/**
 * The You must section: the production-enforced authoring rules plus the
 * authoring-policy sentences, each rendered from its policy claim value so a
 * claim edit changes the instruction (and vice versa a prose edit breaks the
 * positive-render oracle).
 */
private fun youMustSection(): String {
    fun policy(id: String): String = IntentWireContract.policySentence(id)
    return """
        You must:
        - Use only the properties listed in the Output contract above. Do not add any other property — not as a helpful extra, not under any name. Undefined properties make the whole reply unusable.
        - Use only the "ref" values that appear in the CONTEXT data below — in "itemIntents[].ref", in "desiredGroup", and in "unresolvedRefs"
        - Mention every "ref" at most once across "itemIntents" and "unresolvedRefs"; you do not have to cover every ref, and anything you leave out is treated as "no judgment" and is never guessed
        - ${policy("policy.stringFieldsAsJsonStrings")}, enum values in ${policy("policy.uppercaseEnums")}, and numbers as integers — never decimals
        - Treat every item with mobility "FIXED" as immovable: ${policy("policy.fixedAuthoring")} for it, or leave it out, or list it under "unresolvedRefs" — no other field is allowed on it
        - Treat every item with mobility "CONDITIONAL" as position-flexible only: never use "desiredGroup" or "groupSemantic" for it
        - Treat every item with subject "CANDIDATE" as an app that is not yet on the home screen: never use "preserve" for it; instead propose its importance, grouping, and page or region preference like for the other apps
        - Not propose widget spans or sizes, exact screen coordinates, or database changes
        - Author only what you actually judged: put the items you decided on in "itemIntents" with the fields you chose, and put a "ref" in "unresolvedRefs" only when you explicitly decided not to judge it
        - If information you need is missing, ask the user before you finalize — do not fill the gap by inventing properties or values, and do not invent a property for an idea the contract cannot express
    """.trimIndent()
}

/**
 * The finalization self-check and the response format (spec 348 Decisions
 * 2/3): the agent verifies the contract facts right before responding, and
 * the canonical authoring form is one fenced `json` code block containing
 * exactly one JSON object — the framing that survives both message copy and
 * code-block copy on representative provider surfaces (#345 evidence).
 */
private val INSTRUCTION_FOOTER = run {
    val confidence = IntentWireContract.field("confidence")
    val schemaVersion = IntentWireContract.field("schemaVersion").exactValue!!
    """
    Before sending your final answer, verify:
    - "schemaVersion" is exactly "$schemaVersion" and "exportId" echoes the CONTEXT data
    - Every property you used is listed in the Output contract — there is no extra field
    - Enum values are UPPERCASE as listed, "confidence" is an integer ${confidence.min}-${confidence.max}, and "pageAffinity" is within the page range
    - Every "ref" you used exists in the CONTEXT data and is mentioned at most once
    - The FIXED, CONDITIONAL, and CANDIDATE rules are respected
    - Your reply contains exactly one importable JSON artifact

    Response format:
    Return the final answer as exactly one JSON object inside a single fenced code block that opens with a line containing only ```json and closes with a line containing only ```. The block contains exactly one JSON object and nothing else. Use exactly one code block in the whole reply — do not return multiple candidates. Keep any commentary outside the code block minimal. Do not repeat these instructions or the CONTEXT data.
    """.trimIndent()
}
