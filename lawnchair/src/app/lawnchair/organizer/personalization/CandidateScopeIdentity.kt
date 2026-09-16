package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget

/**
 * Issue #331 (spec D-4): the session-local freshness identity of the export
 * scope's candidate projection. Candidates have no captured `ItemId`s, so the
 * placed-item structural digest (`SourceContextIdentity`) cannot represent
 * them; this digest covers the authority-bearing candidate fields instead —
 * stable identity, availability, and the resolved category (the #228 policy
 * authority output the AI judged against).
 *
 * Like the structural digest, this is a pure function of the canonical
 * projection, deterministic and stable across processes, session-local (never
 * part of the export document), and independent of `ref`/`exportId`
 * allocation and privacy tier. Package/component names exist only inside the
 * canonical rows, which never leave the session.
 */
object CandidateScopeIdentity {

    /** Digest of the empty projection (full-organization exports). */
    val EMPTY_DIGEST: String = digest(emptyList())

    /** Stable digest of the canonical candidate scope projection. */
    fun digest(projections: List<CandidateScopeProjection>): String = sha256Hex(canonicalRepresentation(projections))

    /**
     * Canonical rows: one sorted row per candidate, ordered by (component,
     * profile) so the digest is enumeration-order independent.
     */
    fun canonicalRepresentation(projections: List<CandidateScopeProjection>): String = projections
        .sortedWith(compareBy({ it.target.component.value }, { it.target.profile.value }))
        .joinToString("\n") { it.canonicalRow() }

    private fun CandidateScopeProjection.canonicalRow(): String = "candidate|${target.component.value}|${target.profile.value}|${availability.name}|${category ?: "-"}"
}

/** The authority-bearing projection of one scope candidate (spec 331 D-4). */
data class CandidateScopeProjection(
    val target: CandidateTarget.AppKey,
    val availability: Availability,
    /** Resolved classification (override included) or null when unresolved. */
    val category: String?,
)
