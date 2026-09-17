package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryIdentity

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
 *
 * Issue #336: the resolved classification enters the row as the
 * [CategoryIdentity]'s kind discriminator + stable ID (`canonicalValue`) —
 * built-in categories keep the pre-336 raw value byte for byte, a
 * user-defined identity contributes `u:<uuid>`. A raw user ID therefore
 * touches only this one-way session digest input; it is never persisted as an
 * export/session *field* (the export presentation layer redacts it to the
 * absent category instead), so an A→B reassignment or an assigned-category
 * deletion is detected exactly as a built-in resolved-category change is.
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

    private fun CandidateScopeProjection.canonicalRow(): String = "candidate|${target.component.value}|${target.profile.value}|${availability.name}|${category?.canonicalValue ?: "-"}"
}

/**
 * The authority-bearing projection of one scope candidate (spec 331 D-4).
 * [category] is the resolved classification as a closed planning identity
 * (override included) or null when unresolved — the identity-preserving
 * freshness input, not the redacted export field.
 */
data class CandidateScopeProjection(
    val target: CandidateTarget.AppKey,
    val availability: Availability,
    /** Resolved classification identity (override included) or null when unresolved. */
    val category: CategoryIdentity?,
)
