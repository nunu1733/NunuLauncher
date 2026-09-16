package app.lawnchair.organizer.personalization

/**
 * Issue #204: content identity of one accepted intent. The digest is taken
 * over the canonical byte representation of the completed intent (spec 330
 * D-5), so two semantically identical but differently formatted intents share
 * identity only when their canonical representation matches (NFR-003-style
 * determinism).
 */
data class IntentIdentity(
    val schemaVersion: String,
    val digest: String,
) {
    init {
        require(schemaVersion == ContextExportContract.INTENT_SCHEMA_VERSION)
        require(digest.length == 64)
    }
}

object IntentIdentityCalculator {
    fun identity(completed: CompletedPersonalIntent): IntentIdentity = IntentIdentity(
        schemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
        digest = sha256Hex(canonicalRepresentation(completed)),
    )

    /**
     * Issue #330 (spec 330 D-5/D-6): canonical rows over the completed
     * representation, sorted by ref. Authored refs keep the `item|...` row;
     * every "no judgment" form — explicit unresolved, a normalized bare entry,
     * and an unmentioned ref — produces the same `unresolved|ref` row, so the
     * digest is a function of semantic content only.
     */
    fun canonicalRepresentation(completed: CompletedPersonalIntent): String {
        val rows = mutableListOf<String>()
        rows += "intent|${ContextExportContract.INTENT_SCHEMA_VERSION}|${completed.exportId}"
        rows += "global|${completed.globalPreference?.minimizeMovement ?: "-"}"
        rows += "rationale|${completed.rationale ?: "-"}|${completed.confidence ?: "-"}"
        for ((ref, decision) in completed.decisions.entries.sortedBy { it.key }) {
            when (decision) {
                is RefDecision.Authored -> rows += decision.intent.canonicalRow()
                RefDecision.UnresolvedAuthored, RefDecision.UnresolvedByOmission -> rows += "unresolved|$ref"
            }
        }
        return rows.joinToString("\n")
    }
}

private fun ItemIntent.canonicalRow(): String = "item|$ref|${importance?.name ?: "-"}|" +
    (desiredGroupRefs?.sorted()?.joinToString(",") ?: "-") + "|" +
    (groupSemantic?.category ?: "-") + "|" +
    (groupSemantic?.freeText ?: "-") + "|" +
    (pageAffinity ?: -1) + "|" + (regionAffinity?.name ?: "-") + "|" + (preserve ?: "-")

/**
 * Issue #204: provenance bridge. The accepted intent's identity joins
 * `InputProvenance` as the 7th policy input; runs without an intent carry the
 * canonical no-intent sentinel, a valid content-addressed identity that
 * satisfies the `PolicyInputIdentity` type invariants.
 */
object PersonalizedIntentIdentity {

    /** Canonical no-intent sentinel representation. */
    const val NO_INTENT_CANONICAL = "personalized-intent:none"
    const val NO_INTENT_VERSION = "none"

    fun noIntentSentinel(): app.lawnchair.organizer.rules.PolicyInputIdentity = app.lawnchair.organizer.rules.PolicyInputIdentity(
        app.lawnchair.organizer.rules.PolicySourceKind.PERSONALIZED_INTENT,
        NO_INTENT_VERSION,
        sha256Hex(NO_INTENT_CANONICAL),
    )
}

fun IntentIdentity.policyIdentity(): app.lawnchair.organizer.rules.PolicyInputIdentity = app.lawnchair.organizer.rules.PolicyInputIdentity(
    app.lawnchair.organizer.rules.PolicySourceKind.PERSONALIZED_INTENT,
    schemaVersion,
    digest,
)
