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

/**
 * Issue #375 (spec "原因別remedyの導出と表示"): the confirm-time early gate's
 * cause derivation. Mirrors [evaluate]'s ordering — an unresolvable scope
 * candidate wins over a set mismatch, so the user is never told to
 * "re-select" a candidate that no longer resolves (uninstalled, disabled, or
 * placed on Home). Returns `null` when the confirmed selection matches the
 * export scope exactly (the gate passes; identical pass/fail outcome to the
 * pre-#375 sorted-list comparison).
 *
 * Pure addition: the gate's own [evaluate] rule and [ScopeMismatchCause] enum
 * are unchanged; this only derives the *label* for the early gate's failure.
 */
object ScopeBindingCauseDerivation {

    /**
     * Issue #375: the confirm-time cause derivation over the same canonical
     * ordering as [ScopeBindingGate.evaluate] (unresolvable-before-mismatch).
     */
    fun deriveConfirmMismatch(
        sessionScope: List<CandidateTarget.AppKey>,
        detected: List<DetectedCandidateScope>,
        selected: Set<CandidateTarget.AppKey>,
    ): ScopeMismatchCause? {
        val detectedById = detected.associateBy { it.target }
        for (target in sessionScope) {
            val found = detectedById[target]
                ?: return ScopeMismatchCause.CANDIDATE_UNRESOLVED
            if (found.availability != Availability.AVAILABLE) {
                return ScopeMismatchCause.CANDIDATE_UNRESOLVED
            }
            if (target !in selected) {
                return ScopeMismatchCause.SET_MISMATCH
            }
        }
        val sessionSet = sessionScope.toSet()
        if (selected.any { it !in sessionSet }) {
            return ScopeMismatchCause.SET_MISMATCH
        }
        return null
    }

    /**
     * Issue #375 (spec SR-AC-01): the selection surface's diff against the
     * export scope. [missing] are scope candidates that are resolvable in the
     * current detection cut but not selected (rows the user can fix);
     * [extra] are selected candidates outside the scope (rows the user can
     * unselect); [unresolvable] are scope candidates that no longer resolve
     * (no row exists — selection edits cannot fix them, so the remedy is
     * re-creating the request). UI renders this result; it never re-derives it.
     */
    fun deriveSelectionDiff(
        sessionScope: Set<CandidateTarget.AppKey>,
        detected: List<DetectedCandidateScope>,
        selected: Set<CandidateTarget.AppKey>,
    ): ScopeSelectionDiff {
        val detectedById = detected.associateBy { it.target }
        val resolvableScope = sessionScope.filter { target ->
            detectedById[target]?.availability == Availability.AVAILABLE
        }.toSet()
        val missing = resolvableScope - selected
        val extra = selected - sessionScope
        val unresolvable = sessionScope - resolvableScope
        return ScopeSelectionDiff(missing = missing, extra = extra, unresolvable = unresolvable)
    }

    /**
     * Issue #375 (spec "選択復元初期値"): the rebind selection surface's
     * initial values — the export scope candidates that still resolve in the
     * current detection cut. Deterministic and side-effect free; the values
     * are an initial state only and become the run's selection solely through
     * the user's explicit confirm (spec 228 D-1 is not weakened).
     */
    fun deriveRestoredSelection(
        sessionScope: Set<CandidateTarget.AppKey>,
        detected: List<DetectedCandidateScope>,
    ): Set<CandidateTarget.AppKey> {
        val available = detected.filter { it.availability == Availability.AVAILABLE }.map { it.target }.toSet()
        return sessionScope.intersect(available)
    }
}

/** Issue #375: the selection surface's diff against the export scope. */
data class ScopeSelectionDiff(
    val missing: Set<CandidateTarget.AppKey>,
    val extra: Set<CandidateTarget.AppKey>,
    val unresolvable: Set<CandidateTarget.AppKey>,
)
