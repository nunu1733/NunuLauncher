package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.ScopeMismatchCause
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #375 (spec SR-AC-01/02, Test oracle): the pure confirm-time cause
 * derivation and the selection-diff/restore derivations. Deterministic
 * table-style oracles over the same ordering the composed-phase gate uses
 * (unresolvable-before-set-mismatch).
 */
class ScopeBindingCauseDerivationTest {

    private fun target(name: String): CandidateTarget.AppKey = CandidateTarget.AppKey(
        ComponentKey("com.example.$name/.Main"),
        ProfileId("profile-0"),
    )

    private fun detected(target: CandidateTarget.AppKey, availability: Availability = Availability.AVAILABLE) = DetectedCandidateScope(target, availability)

    private fun targets(vararg names: String): List<CandidateTarget.AppKey> = names.map(::target)

    // ---- deriveConfirmMismatch ----

    @Test
    fun matchingSelectionPasses() {
        val scope = targets("a", "b")
        val cut = listOf(detected(scope[0]), detected(scope[1]))
        assertNull(ScopeBindingCauseDerivation.deriveConfirmMismatch(scope, cut, scope.toSet()))
    }

    @Test
    fun missingAndExtraSelectionsAreSetMismatch() {
        val scope = targets("a", "b")
        val cut = listOf(detected(scope[0]), detected(scope[1]), detected(target("x")))
        assertEquals(
            ScopeMismatchCause.SET_MISMATCH,
            ScopeBindingCauseDerivation.deriveConfirmMismatch(scope, cut, setOf(scope[1], target("x"))),
        )
    }

    @Test
    fun unresolvableCandidateWinsOverSetMismatch() {
        val a = target("a")
        val b = target("b")
        val scope = listOf(a, b)
        // `a` no longer resolves AND the selection is missing `b` and adds `x`:
        // the unresolvable cause wins — "re-select" would be an impossible remedy.
        val cut = listOf(
            detected(b),
            detected(target("x")),
        )
        assertEquals(
            ScopeMismatchCause.CANDIDATE_UNRESOLVED,
            ScopeBindingCauseDerivation.deriveConfirmMismatch(scope, cut, setOf(b, target("x"))),
        )
    }

    @Test
    fun nonAvailableCandidateIsUnresolvable() {
        val a = target("a")
        val b = target("b")
        val scope = listOf(a, b)
        val cut = listOf(detected(a, Availability.DISABLED), detected(b))
        assertEquals(
            ScopeMismatchCause.CANDIDATE_UNRESOLVED,
            ScopeBindingCauseDerivation.deriveConfirmMismatch(scope, cut, setOf(a, b)),
        )
    }

    @Test
    fun extraOnlySelectionIsSetMismatch() {
        val scope = targets("a")
        val cut = listOf(detected(scope[0]), detected(target("x")))
        assertEquals(
            ScopeMismatchCause.SET_MISMATCH,
            ScopeBindingCauseDerivation.deriveConfirmMismatch(scope, cut, setOf(scope[0], target("x"))),
        )
    }

    // ---- deriveSelectionDiff ----

    @Test
    fun diffClassifiesMissingExtraAndUnresolvable() {
        val a = target("a")
        val b = target("b")
        val c = target("c")
        val scope = setOf(a, b, c)
        val cut = listOf(detected(a), detected(b), detected(target("x")))
        val selected = setOf(b, target("x"))
        val diff = ScopeBindingCauseDerivation.deriveSelectionDiff(scope, cut, selected)
        assertEquals(setOf(a), diff.missing)
        assertEquals(setOf(target("x")), diff.extra)
        assertEquals(setOf(c), diff.unresolvable)
    }

    @Test
    fun diffEmptyForExactSelection() {
        val scope = targets("a", "b").toSet()
        val cut = targets("a", "b").map { detected(it) }
        val diff = ScopeBindingCauseDerivation.deriveSelectionDiff(scope, cut, scope)
        assertTrue(diff.missing.isEmpty())
        assertTrue(diff.extra.isEmpty())
        assertTrue(diff.unresolvable.isEmpty())
    }

    // ---- deriveRestoredSelection ----

    @Test
    fun restoreIsScopeIntersectionWithResolvableCut() {
        val a = target("a")
        val b = target("b")
        val c = target("c")
        val scope = setOf(a, b, c)
        val cut = listOf(detected(a), detected(b, Availability.DISABLED), detected(target("x")))
        val restored = ScopeBindingCauseDerivation.deriveRestoredSelection(scope, cut)
        assertEquals(setOf(a), restored)
    }

    @Test
    fun restoreIsDeterministic() {
        val scope = targets("a", "b").toSet()
        val cut = targets("a", "b").map { detected(it) }
        assertEquals(
            ScopeBindingCauseDerivation.deriveRestoredSelection(scope, cut),
            ScopeBindingCauseDerivation.deriveRestoredSelection(scope, cut),
        )
    }

    @Test
    fun restoreOfEmptyScopeIsEmpty() {
        assertTrue(ScopeBindingCauseDerivation.deriveRestoredSelection(emptySet(), emptyList()).isEmpty())
        assertFalse(
            ScopeBindingCauseDerivation.deriveRestoredSelection(emptySet(), listOf(detected(target("a"))))
                .isNotEmpty(),
        )
    }
}
