package app.lawnchair.organizer.ui

import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 spec §2 / AC-2: the selection state rules — selection survives
 * query changes, Select all matches the displayed set, Clear all clears the
 * whole candidate set, and the count is always the whole-selection count.
 */
class MissingAppSelectionStateTest {

    private fun candidate(component: String, label: String, profile: String = "0") = DetectedCandidate(
        target = CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile)),
        label = label,
        availability = Availability.AVAILABLE,
    )

    private val candidates = listOf(
        candidate("com.example.mail/.Main", "Mail"),
        candidate("com.example.maps/.Main", "Maps"),
        candidate("com.example.music/.Main", "Music Player"),
        candidate("com.example.mood/.Main", "Mood"),
    )

    @Test
    fun initialSelectionIsEmptyForEveryCandidate() {
        val state = MissingAppSelectionState(candidates, selected = emptySet())
        assertEquals(0, state.selectedCount)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun selectionSurvivesQueryChangesIncludingInvisibility() {
        var state = MissingAppSelectionState(candidates, emptySet())
        state = state.toggle(candidates[0])
        state = state.toggle(candidates[3])

        state = state.withQuery("maps")
        assertEquals(setOf(candidates[0].target, candidates[3].target), state.selected)
        assertEquals(listOf(candidates[1]), state.displayed)

        state = state.withQuery("zzz")
        assertEquals(setOf(candidates[0].target, candidates[3].target), state.selected)
        assertTrue(state.displayed.isEmpty())
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun selectAllUnderFilterKeepsOtherSelections() {
        var state = MissingAppSelectionState(candidates, emptySet())
            .toggle(candidates[0])
            .withQuery("maps")

        // Spec §2 scenario: the pre-existing Mail selection persists while
        // Select all adds exactly the displayed set (Maps).
        state = state.selectAllMatching()

        assertEquals(
            setOf(candidates[0].target, candidates[1].target),
            state.selected,
        )
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun clearAllUnderEmptyFilterClearsTheWholeSet() {
        var state = MissingAppSelectionState(candidates, emptySet())
            .toggle(candidates[0])
            .toggle(candidates[2])
            .withQuery("maps")

        state = state.clearAll()

        assertTrue(state.selected.isEmpty())
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun searchMatchesLabelCaseInsensitivelyAndComponent() {
        val state = MissingAppSelectionState(candidates, emptySet()).withQuery("MAIL")
        assertEquals(listOf(candidates[0]), state.displayed)

        val byComponent = MissingAppSelectionState(candidates, emptySet()).withQuery("com.example.maps")
        assertEquals(listOf(candidates[1]), byComponent.displayed)
    }

    @Test
    fun emptyCandidateListSelectsNothingAndDisplaysAll() {
        val state = MissingAppSelectionState(emptyList(), emptySet()).selectAllMatching()
        assertEquals(0, state.selectedCount)
        assertTrue(state.displayed.isEmpty())
    }
}
