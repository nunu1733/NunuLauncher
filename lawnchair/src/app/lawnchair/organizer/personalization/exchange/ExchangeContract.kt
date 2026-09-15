package app.lawnchair.organizer.personalization.exchange

/**
 * Issue #205: the External Agent Exchange contract constants owned by this
 * workflow (spec 205 "Domain language" / "Decisions").
 *
 * The exchange framing is #205-owned and distinct from the #204 payload
 * schema: full-line markers delimit the `PersonalizedIntentV1` payload inside
 * the agent's free-form reply, and the import envelope limit bounds the whole
 * reply (prose included) as a resource-safety border the #204 payload limit
 * cannot provide.
 */
object ExchangeContract {

    /** Framing: full-line marker starting the intent payload region. */
    const val INTENT_BEGIN_MARKER = "-----BEGIN NUNULAUNCHER INTENT-----"

    /** Framing: full-line marker ending the intent payload region. */
    const val INTENT_END_MARKER = "-----END NUNULAUNCHER INTENT-----"

    /** Package structure: full-line marker starting the CONTEXT data block. */
    const val CONTEXT_BEGIN_MARKER = "-----BEGIN NUNULAUNCHER CONTEXT-----"

    /** Package structure: full-line marker ending the CONTEXT data block. */
    const val CONTEXT_END_MARKER = "-----END NUNULAUNCHER CONTEXT-----"

    /**
     * Import envelope limit (spec 205 Decision 6): the whole import text —
     * free prose around the framing markers included — is bounded by 1 MiB
     * UTF-8 bytes. This is a #205-owned contract, separate from the #204
     * payload limit (`MAX_INTENT_BYTES` = 128 KiB) which applies to the
     * extracted payload only. The byte length is the canonical measure
     * (the actual resource unit); every import route applies the same limit.
     */
    const val MAX_EXCHANGE_IMPORT_BYTES: Int = 1024 * 1024
}
