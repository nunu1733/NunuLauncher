package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CandidateScopeIdentity
import app.lawnchair.organizer.personalization.CandidateScopeProjection
import app.lawnchair.organizer.personalization.ScopeMismatchCause
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget

/**
 * Issue #331 (spec D-2/D-4/D-5): the fail-closed scope binding gate evaluated
 * when a validated intent is applied to an organizer run whose target scope is
 * fixed (selection confirmed + canonical composition). The run's candidate
 * scope must equal the export scope exactly, every candidate must still
 * resolve as available, and the candidate projection (identity + availability
 * + resolved category) must still match the export-time digest. Any divergence
 * is a typed `SCOPE_MISMATCH` (zero-write; the remedy is re-export).
 */
object ScopeBindingGate {

    fun evaluate(session: ScopeBindingSessionScope, current: ScopeBindingCurrentScope): ScopeBindingOutcome {
        val sessionTargets = session.scopeCandidates
            .sortedWith(compareBy({ it.component.value }, { it.profile.value }))
        val detectedById = current.detected.associateBy { it.target }

        // Every export candidate must still resolve from the current detection
        // cut (installed, launchable, AVAILABLE, still not on Home) and must
        // have been selected for this run.
        for (target in sessionTargets) {
            val detected = detectedById[target]
                ?: return ScopeBindingOutcome.Mismatch(ScopeMismatchCause.CANDIDATE_UNRESOLVED)
            if (detected.availability != Availability.AVAILABLE) {
                return ScopeBindingOutcome.Mismatch(ScopeMismatchCause.CANDIDATE_UNRESOLVED)
            }
            if (detected.target !in current.selectedTargets) {
                return ScopeBindingOutcome.Mismatch(ScopeMismatchCause.SET_MISMATCH)
            }
        }
        // No unexported candidate may join an intent-consuming run: additions
        // beyond the export scope recreate the export/run scope divergence this
        // gate exists to close (spec D-2 exact equality).
        val sessionSet = sessionTargets.toSet()
        if (current.selectedTargets.any { it !in sessionSet }) {
            return ScopeBindingOutcome.Mismatch(ScopeMismatchCause.SET_MISMATCH)
        }
        // The authority-bearing projection (availability + resolved category)
        // must still match the export-time digest — a category authority
        // change with no layout change is invisible to the placed-item
        // structural digest (spec D-4).
        if (session.scopeCandidateDigest != CandidateScopeIdentity.digest(current.candidateProjections)) {
            return ScopeBindingOutcome.Mismatch(ScopeMismatchCause.PROJECTION_MISMATCH)
        }
        return ScopeBindingOutcome.Pass
    }
}

/** The export scope recorded in the export session (spec 331 "Data and state"). */
data class ScopeBindingSessionScope(
    val scopeCandidates: List<CandidateTarget.AppKey>,
    val scopeCandidateDigest: String,
)

/**
 * The run-side scope at binding time: the current detection cut (identity +
 * availability), the confirmed selection, and the selected candidates'
 * authority-bearing projections (identity + availability + resolved category)
 * derived from the same canonical composition the planner consumes.
 */
data class ScopeBindingCurrentScope(
    val detected: List<DetectedCandidateScope>,
    val selectedTargets: Set<CandidateTarget.AppKey>,
    val candidateProjections: List<CandidateScopeProjection>,
)

/** Detection-cut view of one candidate: identity plus availability. */
data class DetectedCandidateScope(
    val target: CandidateTarget.AppKey,
    val availability: Availability,
)

sealed interface ScopeBindingOutcome {
    data object Pass : ScopeBindingOutcome

    data class Mismatch(val cause: ScopeMismatchCause) : ScopeBindingOutcome
}
