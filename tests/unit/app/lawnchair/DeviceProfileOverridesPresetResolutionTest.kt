package app.lawnchair

import com.android.launcher3.InvariantDeviceProfile.TYPE_MULTI_DISPLAY
import com.android.launcher3.InvariantDeviceProfile.TYPE_PHONE
import com.android.launcher3.InvariantDeviceProfile.TYPE_TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contract for the Issue #134 enabled-preset inventory resolution:
 * device-type filtering and the deterministic ceiling-match name approximation.
 * The fixture mirrors the runtime catalog declared in lawnchair/res/xml/device_profiles.xml.
 */
class DeviceProfileOverridesPresetResolutionTest {

    private fun preset(
        name: String,
        numColumns: Int,
        numRows: Int,
        vararg enabledDeviceTypes: Int,
    ) = DeviceProfileOverrides.DeclaredGridPreset(
        name = name,
        grid = DeviceProfileOverrides.DBGridInfo(
            numHotseatColumns = numColumns,
            numRows = numRows,
            numColumns = numColumns,
        ),
        enabledDeviceTypes = enabledDeviceTypes.toSet(),
    )

    private val declared = listOf(
        preset("3_by_3", 3, 3, TYPE_PHONE),
        preset("4_by_5", 4, 5, TYPE_PHONE, TYPE_MULTI_DISPLAY),
        preset("5_by_5", 5, 5, TYPE_PHONE, TYPE_MULTI_DISPLAY),
        preset("6_by_5", 6, 5, TYPE_TABLET),
        preset("practical", 4, 5, TYPE_MULTI_DISPLAY),
    )

    @Test
    fun phoneInventoryMatchesTheLegacyFrozenSnapshot() {
        val names = DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_PHONE).map { it.name }
        assertEquals(listOf("3_by_3", "4_by_5", "5_by_5"), names)
    }

    @Test
    fun tabletAndMultiDisplayInventoriesContainOnlyTheirEnabledPresets() {
        assertEquals(listOf("6_by_5"), DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_TABLET).map { it.name })
        assertEquals(
            listOf("4_by_5", "5_by_5", "practical"),
            DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_MULTI_DISPLAY).map { it.name },
        )
    }

    @Test
    fun unknownDeviceTypeYieldsAnEmptyInventory() {
        assertTrue(DeviceProfileOverrides.resolveEnabledPresets(declared, -1).isEmpty())
    }

    @Test
    fun ceilingMatchReturnsTheExactPresetWhenOneFits() {
        val tabletInventory = DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_TABLET)
        val target = DeviceProfileOverrides.DBGridInfo(numHotseatColumns = 6, numRows = 5, numColumns = 6)
        assertEquals("6_by_5", DeviceProfileOverrides.ceilingMatchPreset(tabletInventory, target).name)
    }

    @Test
    fun ceilingMatchApproximatesWithTheFirstDeclarationThatFits() {
        val tabletInventory = DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_TABLET)
        // Tablet default live dimensions 4x5/hotseat 4 fit no enabled preset exactly;
        // the documented approximation is the single enabled preset.
        val target = DeviceProfileOverrides.DBGridInfo(numHotseatColumns = 4, numRows = 5, numColumns = 4)
        assertEquals("6_by_5", DeviceProfileOverrides.ceilingMatchPreset(tabletInventory, target).name)

        // Phone live dimensions 5x5 match the later 5_by_5 declaration, not the earlier 4_by_5.
        val phoneInventory = DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_PHONE)
        val fiveByFive = DeviceProfileOverrides.DBGridInfo(numHotseatColumns = 5, numRows = 5, numColumns = 5)
        assertEquals("5_by_5", DeviceProfileOverrides.ceilingMatchPreset(phoneInventory, fiveByFive).name)
    }

    @Test
    fun ceilingMatchFallsBackToTheLastPresetWhenNoneFit() {
        val multiDisplayInventory = DeviceProfileOverrides.resolveEnabledPresets(declared, TYPE_MULTI_DISPLAY)
        val oversized = DeviceProfileOverrides.DBGridInfo(numHotseatColumns = 8, numRows = 9, numColumns = 9)
        assertEquals("practical", DeviceProfileOverrides.ceilingMatchPreset(multiDisplayInventory, oversized).name)
    }

    @Test
    fun ceilingMatchIsDeterministicAndStaysWithinTheInventory() {
        val inventories = listOf(TYPE_PHONE, TYPE_TABLET, TYPE_MULTI_DISPLAY).map {
            DeviceProfileOverrides.resolveEnabledPresets(declared, it)
        }
        for (inventory in inventories) {
            for (columns in 1..8) {
                for (rows in 1..8) {
                    val target = DeviceProfileOverrides.DBGridInfo(columns, rows, columns)
                    val match = DeviceProfileOverrides.ceilingMatchPreset(inventory, target)
                    val repeat = DeviceProfileOverrides.ceilingMatchPreset(inventory, target)
                    assertTrue(match.name in inventory.map { it.name })
                    assertEquals(match.name, repeat.name)
                }
            }
        }
    }

    @Test
    fun emptyInventoryFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileOverrides.ceilingMatchPreset(
                emptyList(),
                DeviceProfileOverrides.DBGridInfo(4, 5, 4),
            )
        }
    }
}
