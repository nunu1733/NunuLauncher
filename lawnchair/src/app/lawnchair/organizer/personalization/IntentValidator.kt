package app.lawnchair.organizer.personalization

/**
 * Issue #204: strict intent validation (spec 204 "Validation / fail-closed").
 * Pure, total function: any input resolves to a typed failure or a validated
 * intent. Zero-write is structural — this validator writes nothing.
 */
object IntentValidator {

    /**
     * Validates a decoded intent against the export session and the current
     * canonical structural state.
     *
     * Check order (typed classes are exclusive by construction):
     * schema/limits (codec) → export binding → session expiry → ref
     * resolution → coverage partition → enum → capability (reserved in V1) →
     * structural staleness → per-ref mobility.
     */
    fun validate(
        intent: PersonalizedIntentV1,
        export: PersonalizationContextExportV1,
        session: ExportSession,
        nowEpochMs: Long,
        currentStructuralDigest: String,
    ): IntentValidation {
        if (session.isExpired(nowEpochMs)) {
            return IntentValidation.Failure(IntentValidationFailure.SessionExpired)
        }
        if (session.exportId != export.exportId || intent.exportId != session.exportId) {
            return IntentValidation.Failure(IntentValidationFailure.ExportMismatch)
        }

        val exportRefs = export.items.map { it.ref }.toSet()
        val mobilityByRef = export.items.associate { it.ref to it.mobility }

        // Unknown refs: item intents, desired groups, unresolved markers.
        for (item in intent.itemIntents) {
            if (item.ref !in exportRefs) {
                return IntentValidation.Failure(IntentValidationFailure.UnknownRef(item.ref))
            }
            item.desiredGroupRefs.orEmpty().forEach { groupRef ->
                if (groupRef !in exportRefs) {
                    return IntentValidation.Failure(IntentValidationFailure.UnknownRef(groupRef))
                }
            }
        }
        intent.unresolvedRefs.forEach { ref ->
            if (ref !in exportRefs) {
                return IntentValidation.Failure(IntentValidationFailure.UnknownRef(ref))
            }
        }

        // Duplicate refs across item intents.
        val intentRefs = intent.itemIntents.map { it.ref }
        if (intentRefs.size != intentRefs.toSet().size) {
            return IntentValidation.Failure(IntentValidationFailure.DuplicateRef)
        }

        // Coverage partition: itemIntents refs ∪ unresolvedRefs == all exported
        // refs, and the two sets are disjoint.
        val unresolved = intent.unresolvedRefs.toSet()
        if (unresolved.size != intent.unresolvedRefs.size) {
            return IntentValidation.Failure(IntentValidationFailure.IncompleteCoverage)
        }
        val covered = intentRefs.toSet()
        if ((covered intersect unresolved).isNotEmpty()) {
            return IntentValidation.Failure(IntentValidationFailure.IncompleteCoverage)
        }
        if (covered + unresolved != exportRefs) {
            return IntentValidation.Failure(IntentValidationFailure.IncompleteCoverage)
        }

        // Per-ref mobility semantics. Schema-permitted semantic fields
        // contradicting per-ref mobility are MOBILITY_CONTRADICTION; locked
        // refs are FIXED and therefore fall here too.
        for (item in intent.itemIntents) {
            val mobility = mobilityByRef.getValue(item.ref)
            if (mobility == Mobility.FIXED) {
                if (item.importance != null || item.pageAffinity != null ||
                    item.regionAffinity != null || item.desiredGroupRefs != null || item.groupSemantic != null
                ) {
                    return IntentValidation.Failure(IntentValidationFailure.MobilityContradiction(item.ref))
                }
            }
            if (mobility == Mobility.CONDITIONAL) {
                if (item.desiredGroupRefs != null || item.groupSemantic != null) {
                    return IntentValidation.Failure(IntentValidationFailure.MobilityContradiction(item.ref))
                }
            }
        }

        // Structural freshness: recompute the digest over the current canonical
        // structural state and compare with the session value. The #203 signal
        // snapshot is NOT part of this digest, so signal changes never reject.
        if (session.sourceContextDigest != currentStructuralDigest) {
            return IntentValidation.Failure(IntentValidationFailure.ContextStale)
        }

        return IntentValidation.Validated(
            validated = ValidatedPersonalizedIntent(
                intent = intent,
                export = export,
                session = session,
                identity = IntentIdentityCalculator.identity(intent),
            ),
        )
    }
}

/** A validated, accepted intent: an immutable planning input. */
data class ValidatedPersonalizedIntent(
    val intent: PersonalizedIntentV1,
    val export: PersonalizationContextExportV1,
    val session: ExportSession,
    val identity: IntentIdentity,
)

sealed interface IntentValidation {
    data class Validated(val validated: ValidatedPersonalizedIntent) : IntentValidation
    data class Failure(val failure: IntentValidationFailure) : IntentValidation
}

/**
 * Typed failure classes (spec 204 "Validation / fail-closed"). Zero-write in
 * every case. `CAPABILITY_UNSUPPORTED` is reserved in V1 (the export always
 * advertises the full fixed capability set) and activates in a future schema
 * version that allows subset advertisement.
 */
sealed interface IntentValidationFailure {
    data object SchemaMismatch : IntentValidationFailure
    data object ExportMismatch : IntentValidationFailure
    data object SessionExpired : IntentValidationFailure
    data object ContextStale : IntentValidationFailure
    data object Oversize : IntentValidationFailure
    data class UnknownRef(val ref: String) : IntentValidationFailure
    data object DuplicateRef : IntentValidationFailure
    data object IncompleteCoverage : IntentValidationFailure
    data object InvalidEnum : IntentValidationFailure
    data object ForbiddenContent : IntentValidationFailure
    data class MobilityContradiction(val ref: String) : IntentValidationFailure
    data object CapabilityUnsupported : IntentValidationFailure
}
