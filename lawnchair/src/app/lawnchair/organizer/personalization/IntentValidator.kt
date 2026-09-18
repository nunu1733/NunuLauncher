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
     * schema/limits (codec) → export binding → session expiry → item/
     * desiredGroup/unresolved ref resolution → **category ref resolution
     * (`UNKNOWN_CATEGORY_REF`, Issue #337)** → coverage partition → enum →
     * capability (reserved in V1) → structural staleness → per-ref mobility.
     * The pipeline's digest gate runs before this validator, so an
     * assignment-bearing category deletion settles as `CONTEXT_STALE` there.
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

        // Issue #337 (spec 337 D-5): category refs resolve against the
        // advertised category namespace of the reconstructed export view. The
        // view advertises exactly the session's refs whose identity still
        // exists in the import-time catalog, so an unadvertised ref covers
        // every stale case (fabricated name, deleted category, broken session
        // mapping) with one fail-closed class. A name is never resolved.
        val advertisedCategoryRefs = export.categories.map { it.ref }.toSet()
        for (item in intent.itemIntents) {
            val categoryRef = item.groupSemantic?.categoryRef ?: continue
            if (categoryRef !in advertisedCategoryRefs || categoryRef !in session.categoryRefs) {
                return IntentValidation.Failure(IntentValidationFailure.UnknownCategoryRef(categoryRef))
            }
        }

        // Duplicate refs across item intents.
        val intentRefs = intent.itemIntents.map { it.ref }
        if (intentRefs.size != intentRefs.toSet().size) {
            return IntentValidation.Failure(IntentValidationFailure.DuplicateRef)
        }

        // Coverage partition (v3, spec 330 D-1): itemIntents refs and
        // unresolvedRefs must be disjoint (including no duplicates inside
        // unresolvedRefs). The v2 "cover every ref" rule is gone — an
        // unmentioned ref is completed to canonical unresolved downstream.
        val unresolved = intent.unresolvedRefs.toSet()
        if (unresolved.size != intent.unresolvedRefs.size) {
            return IntentValidation.Failure(IntentValidationFailure.IncompleteCoverage)
        }
        val covered = intentRefs.toSet()
        if ((covered intersect unresolved).isNotEmpty()) {
            return IntentValidation.Failure(IntentValidationFailure.IncompleteCoverage)
        }

        // pageAffinity must reference an existing export page (review P3-2
        // follow-up; the range is the export grid's page count).
        for (item in intent.itemIntents) {
            item.pageAffinity?.let { ordinal ->
                if (ordinal < 0 || ordinal >= export.grid.pageCount) {
                    return IntentValidation.Failure(IntentValidationFailure.InvalidEnum)
                }
            }
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
            // Issue #331 (v2): a candidate subject has no current placement, so
            // a keep-current-position assertion is a mobility contradiction.
            // Placement-independent signals (importance / grouping / affinity)
            // stay valid for candidates.
            if (mobility == Mobility.CANDIDATE && item.preserve != null) {
                return IntentValidation.Failure(IntentValidationFailure.MobilityContradiction(item.ref))
            }
        }

        // Structural freshness: recompute the digest over the current canonical
        // structural state and compare with the session value. The #203 signal
        // snapshot is NOT part of this digest, so signal changes never reject.
        if (session.sourceContextDigest != currentStructuralDigest) {
            return IntentValidation.Failure(IntentValidationFailure.ContextStale)
        }

        // Issue #330 (spec 330 D-4/D-5): completion runs inside the validator's
        // success path, and the identity digest is taken over the completed
        // canonical representation — explicit unresolved and bare entries share
        // the `unresolved|ref` row with unmentioned refs (D-6).
        val completed = IntentCompletion.complete(intent, exportRefs)
        return IntentValidation.Validated(
            validated = ValidatedPersonalizedIntent(
                intent = intent,
                export = export,
                session = session,
                identity = IntentIdentityCalculator.identity(completed),
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
) {
    /**
     * Issue #330 (spec 330 D-4): the complete canonical representation built
     * at validation time. Deterministic in the authored intent and the export
     * refs, so re-deriving it here reproduces the validator's completion; the
     * authored document itself stays diagnostics-only.
     */
    val completed: CompletedPersonalIntent by lazy {
        IntentCompletion.complete(intent, export.items.map { it.ref }.toSet())
    }
}

sealed interface IntentValidation {
    data class Validated(val validated: ValidatedPersonalizedIntent) : IntentValidation
    data class Failure(val failure: IntentValidationFailure) : IntentValidation
}

/**
 * Typed failure classes (spec 204 "Validation / fail-closed", extended by
 * spec 331 D-5). Zero-write in every case. `CAPABILITY_UNSUPPORTED` is
 * reserved in V1 (the export always advertises the full fixed capability set)
 * and activates in a future schema version that allows subset advertisement.
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

    /**
     * Issue #337 (v4, spec 337 D-5/D-8): a `groupSemantic.categoryRef` that the
     * export did not advertise, or whose session identity no longer exists in
     * the import-time catalog. Zero-write; the remedy is re-export. A display
     * name is never resolved to a category, so a name-shaped ref lands here.
     */
    data class UnknownCategoryRef(val ref: String) : IntentValidationFailure

    /**
     * Issue #331 (D-5): the scope binding gate rejected the run — the
     * confirmed selection / candidate projection diverged from the export
     * session's scope. Zero-write; the remedy is re-select or re-export.
     */
    data class ScopeMismatch(val cause: ScopeMismatchCause) : IntentValidationFailure
}

/** `SCOPE_MISMATCH` cause detail (spec 331 D-5); user-facing remedy is re-export. */
enum class ScopeMismatchCause {
    /** The confirmed selection diverges from the export scope (missing or extra). */
    SET_MISMATCH,

    /** An export candidate no longer resolves as installed/launchable/AVAILABLE. */
    CANDIDATE_UNRESOLVED,

    /** The candidate projection (availability/resolved category) drifted. */
    PROJECTION_MISMATCH,
}
