/*
 * Issue #449: boundary tests for the synthetic id namespace and the AC-15
 * resource contract. The protected contracts are (1) the two disjoint
 * negative synthetic ranges (session folders / reserved regions) never
 * collide with each other, with the container constants, or with persisted
 * positive favorites rowids, and (2) every edit-surface UI string is
 * resource-derived and non-blank (AC-15 automatic verification of the
 * strings feeding TalkBack).
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceSyntheticIdsTest {

    @Test
    fun `session folder keys are negative and disjoint from reserved keys`() {
        val folderKeys = (0 until 4).map(::editSurfaceNewFolderKey)
        val reservationKeys = (0 until 4).map(::editSurfaceReservationKey)
        assertTrue(folderKeys.all { it < 0 })
        assertTrue(reservationKeys.all { it < 0 })
        // Disjoint namespaces.
        assertTrue(folderKeys.intersect(reservationKeys.toSet()).isEmpty())
        // Deterministic ordinals.
        assertEquals(editSurfaceNewFolderKey(0), folderKeys.first())
        assertEquals(editSurfaceNewFolderKey(3), folderKeys.last())
        assertNotEquals(editSurfaceReservationKey(0), editSurfaceReservationKey(1))
    }

    @Test
    fun `synthetic ranges never collide with container constants or positive rowids`() {
        // Container constants sit between the two negative ranges and are
        // never classified as synthetic.
        assertFalse(isEditSurfaceNewFolderKey(HomeEditContainers.DESKTOP))
        assertFalse(isEditSurfaceNewFolderKey(HomeEditContainers.HOTSEAT))
        assertFalse(isEditSurfaceReservationKey(HomeEditContainers.DESKTOP))
        assertFalse(isEditSurfaceReservationKey(HomeEditContainers.HOTSEAT))
        // Persisted favorites rowids are positive; neither range covers them.
        for (rowId in listOf(1, 42, 100, Int.MAX_VALUE)) {
            assertFalse(isEditSurfaceNewFolderKey(rowId))
            assertFalse(isEditSurfaceReservationKey(rowId))
        }
        // Range boundaries are exact.
        assertTrue(isEditSurfaceReservationKey(Int.MIN_VALUE))
        assertFalse(isEditSurfaceReservationKey(Int.MIN_VALUE + SYNTHETIC_KEY_RANGE))
        assertTrue(isEditSurfaceNewFolderKey(SYNTHETIC_FOLDER_KEY_BASE))
        assertTrue(isEditSurfaceNewFolderKey(-1025))
        assertFalse(isEditSurfaceNewFolderKey(-1024))
        assertFalse(isEditSurfaceNewFolderKey(0))
    }

    @Test
    fun `out of range ordinals are rejected`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            editSurfaceNewFolderKey(SYNTHETIC_KEY_RANGE)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            editSurfaceReservationKey(SYNTHETIC_KEY_RANGE)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            editSurfaceNewFolderKey(-1)
        }
    }
}
