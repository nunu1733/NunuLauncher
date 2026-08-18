package app.lawnchair.organizer.diagnostics.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AC-67-01: ApplyStage enum values are exactly A0–A8; no extra values.
 */
class ApplyStageTest {

    @Test
    fun applyStageHasExactlyA0ThroughA8() {
        val expected = listOf("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8")
        val actual = ApplyStage.entries.map { it.name }
        assertEquals(expected, actual)
    }

    @Test
    fun applyStageValuesAreCorrect() {
        assertEquals("A0", ApplyStage.A0.name)
        assertEquals("A1", ApplyStage.A1.name)
        assertEquals("A2", ApplyStage.A2.name)
        assertEquals("A3", ApplyStage.A3.name)
        assertEquals("A4", ApplyStage.A4.name)
        assertEquals("A5", ApplyStage.A5.name)
        assertEquals("A6", ApplyStage.A6.name)
        assertEquals("A7", ApplyStage.A7.name)
        assertEquals("A8", ApplyStage.A8.name)
    }
}
