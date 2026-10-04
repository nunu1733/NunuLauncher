package app.lawnchair.organizer.personalization

/**
 * Issue #204: content identity of one accepted intent. The digest is taken
 * over the canonical byte representation, so two semantically identical but
 * differently formatted intents share identity only when their canonical
 * representation matches (NFR-003-style determinism).
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
    fun identity(intent: PersonalizedIntentV1): IntentIdentity = IntentIdentity(
        schemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
        digest = sha256Hex(canonicalRepresentation(intent)),
    )

    /**
     * Canonical rows: header, global preference, then one sorted row per item
     * intent, and the unresolved refs. Field order is fixed; collections are
     * sorted.
     */
    fun canonicalRepresentation(intent: PersonalizedIntentV1): String {
        val rows = mutableListOf<String>()
        rows += "intent|${ContextExportContract.INTENT_SCHEMA_VERSION}|${intent.exportId}"
        rows += "global|${intent.globalPreference?.minimizeMovement ?: "-"}"
        rows += "rationale|${intent.rationale ?: "-"}|${intent.confidence ?: "-"}"
        for (item in intent.itemIntents.sortedBy { it.ref }) {
            rows += item.canonicalRow()
        }
        for (ref in intent.unresolvedRefs.sorted()) {
            rows += "unresolved|$ref"
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
