package app.lawnchair.organizer.integration

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.util.DisplayController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #134 evidence for the production named-preset seam
 * ([InvariantDeviceProfile.setCurrentGrid], which delegates to
 * [DeviceProfileOverrides.setCurrentGrid] and schedules the production re-init).
 *
 * `durableNamedPresetSwitch` supports phased execution with the instrumentation
 * argument `issue134.phase` so a driver script can prove durability across a real
 * process restart:
 *  - `apply`: records the pre-test dimensions and switches through the seam;
 *  - `verify`: run after an external force-stop/relaunch, asserts the durable state;
 *  - `restore`: best-effort return to the recorded pre-test dimensions;
 *  - absent (default): single-process apply → assert → restore sequence.
 *
 * Restoring non-enabled dimensions mirrors the Issue #108 harness: the values are
 * written through Lawnchair's own preference keys and may be superseded by the
 * durable grid chain at the next launcher start (Issue #134 durability finding).
 */
@RunWith(AndroidJUnit4::class)
class Issue134GridPresetInstrumentationTest {

    @Test
    fun disabledOrUnknownPresetIsRejectedWithoutPreferenceChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overrides = DeviceProfileOverrides.INSTANCE.get(context)
        val prefs = PreferenceManager.getInstance(context)
        val declared = InvariantDeviceProfile.parseAllDefinedGridOptions(context)
        val enabledNames = authoritativeEnabledNames(context)
        val disabledName = declared.firstOrNull { it.name !in enabledNames }?.name

        val beforeRows = prefs.workspaceRows.get()
        val beforeColumns = prefs.workspaceColumns.get()
        val beforeHotseat = prefs.hotseatColumns.get()

        val unknown = assertThrows(NoSuchElementException::class.java) {
            overrides.getGridInfo("issue134_no_such_preset")
        }
        assertTrue(unknown.message!!.contains("issue134_no_such_preset"))

        // The production named-preset seam rejects invalid names before any
        // preference write or re-initialization is scheduled.
        val unknownThroughIdp = assertThrows(NoSuchElementException::class.java) {
            InvariantDeviceProfile.INSTANCE.get(context).setCurrentGrid(context, "issue134_no_such_preset")
        }
        assertTrue(unknownThroughIdp.message!!.contains("issue134_no_such_preset"))

        if (disabledName != null) {
            val rejected = assertThrows(NoSuchElementException::class.java) {
                InvariantDeviceProfile.INSTANCE.get(context).setCurrentGrid(context, disabledName)
            }
            assertTrue(rejected.message!!.contains(disabledName))
            assertTrue(rejected.message!!.contains(enabledNames.joinToString(prefix = "[", postfix = "]")))
        }

        assertEquals(beforeRows, prefs.workspaceRows.get())
        assertEquals(beforeColumns, prefs.workspaceColumns.get())
        assertEquals(beforeHotseat, prefs.hotseatColumns.get())
    }

    @Test
    fun currentGridNameIsAlwaysAnEnabledPreset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overrides = DeviceProfileOverrides.INSTANCE.get(context)
        val enabledNames = authoritativeEnabledNames(context)
        val current = overrides.getCurrentGridName()
        assertTrue(
            "getCurrentGridName()=$current must be an enabled preset on this host: $enabledNames",
            current in enabledNames,
        )
    }

    @Test
    fun durableNamedPresetSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        when (phase()) {
            "apply" -> {
                recordOriginalDimensions(context)
                applyNamedPreset(context)
            }
            "verify" -> assertDurableState(context)
            "restore" -> restoreOriginalDimensions(context)
            else -> {
                recordOriginalDimensions(context)
                try {
                    applyNamedPreset(context)
                } finally {
                    restoreOriginalDimensions(context)
                }
            }
        }
    }

    /**
     * Enabled preset names derived from the authoritative device type. The test must
     * not use the static-field-filtered `parseAllGridOptions` here: before the first
     * grid initialization it reports the frozen phone inventory (the very defect of
     * Issue #134).
     */
    private fun authoritativeEnabledNames(context: Context): List<String> {
        val deviceType = DisplayController.INSTANCE.get(context).info.deviceType
        return InvariantDeviceProfile.parseAllDefinedGridOptions(context)
            .filter { it.isEnabled(deviceType) }
            .map { it.name }
    }

    private fun phase(): String =
        InstrumentationRegistry.getArguments().getString("issue134.phase") ?: "full"

    /** Switches through the production named-preset seam and asserts live convergence. */
    private fun applyNamedPreset(context: Context) {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val overrides = DeviceProfileOverrides.INSTANCE.get(context)

        // Seed the observed tablet default dimensions (4x5/hotseat 4, not an enabled
        // preset on TYPE_TABLET) when the host already rests on the target preset,
        // so the transition below exercises a real dimension change.
        if (idp.numColumns == targetColumns() && idp.numRows == targetRows()) {
            seedDimensions(context)
            awaitGrid(idp, SEED_COLUMNS, SEED_ROWS)
            // Live dimensions match no enabled preset exactly; the resolved name must
            // still be the enabled preset (documented ceiling-match approximation).
            assertEquals(TARGET_PRESET, overrides.getCurrentGridName())
        }

        idp.setCurrentGrid(context, TARGET_PRESET)
        awaitGrid(idp, targetColumns(), targetRows())

        assertEquals(targetColumns(), idp.numColumns)
        assertEquals(targetRows(), idp.numRows)
        assertEquals(TARGET_PRESET, overrides.getCurrentGridName())
        assertEquals(targetHotseat(), PreferenceManager.getInstance(context).hotseatColumns.get())
    }

    private fun seedDimensions(context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        prefs.workspaceRows.set(SEED_ROWS)
        prefs.workspaceColumns.set(SEED_COLUMNS)
        prefs.hotseatColumns.set(SEED_HOTSEAT)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** After a real process restart the applied preset must be the durable state. */
    private fun assertDurableState(context: Context) {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        awaitGrid(idp, targetColumns(), targetRows())
        assertEquals(targetColumns(), idp.numColumns)
        assertEquals(targetRows(), idp.numRows)

        val prefs = PreferenceManager.getInstance(context)
        assertEquals(targetRows(), prefs.workspaceRows.get())
        assertEquals(targetColumns(), prefs.workspaceColumns.get())
        assertEquals(targetHotseat(), prefs.hotseatColumns.get())
        assertEquals(TARGET_PRESET, DeviceProfileOverrides.INSTANCE.get(context).getCurrentGridName())
        assertEquals(
            "persisted grid selection must agree with the resolved current preset",
            TARGET_PRESET,
            LauncherPrefs.get(context).get(LauncherPrefs.GRID_NAME),
        )
    }

    private fun recordOriginalDimensions(context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        originalDimensionsFile(context)
            .writeText("${prefs.workspaceColumns.get()} ${prefs.workspaceRows.get()} ${prefs.hotseatColumns.get()}")
    }

    private fun restoreOriginalDimensions(context: Context) {
        val file = originalDimensionsFile(context)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return
        }
        val (columns, rows, hotseat) = file.readText().trim().split(" ").map { it.toInt() }
        // Identical keys to DeviceProfileOverrides.setCurrentGrid (see Issue #108 harness).
        val prefs = PreferenceManager.getInstance(context)
        prefs.workspaceRows.set(rows)
        prefs.workspaceColumns.set(columns)
        prefs.hotseatColumns.set(hotseat)
        awaitGrid(InvariantDeviceProfile.INSTANCE.get(context), columns, rows)
        file.delete()
    }

    private fun originalDimensionsFile(context: Context) = File(context.cacheDir, "issue134-original-dims.txt")

    private fun targetColumns(): Int =
        requiredTargetOption().numColumns

    private fun targetRows(): Int =
        requiredTargetOption().numRows

    private fun targetHotseat(): Int =
        requiredTargetOption().numHotseatIcons

    private fun requiredTargetOption(): InvariantDeviceProfile.GridOption {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceType = DisplayController.INSTANCE.get(context).info.deviceType
        return InvariantDeviceProfile.parseAllDefinedGridOptions(context)
            .filter { it.name == TARGET_PRESET && it.isEnabled(deviceType) }
            .firstOrNull()
            ?: error("$TARGET_PRESET is not an enabled preset on this host")
    }

    private fun awaitGrid(idp: InvariantDeviceProfile, columns: Int, rows: Int) {
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (idp.numColumns == columns && idp.numRows == rows) return
            SystemClock.sleep(100)
        }
        error("IDP did not converge to requested grid ${columns}x$rows (actual ${idp.numColumns}x${idp.numRows})")
    }

    private companion object {
        /** The only tablet-enabled declared preset in the adopted catalog. */
        const val TARGET_PRESET = "6_by_5"

        /** Observed Pixel Tablet AVD default dimensions; not an enabled preset (Issue #108). */
        const val SEED_COLUMNS = 4
        const val SEED_ROWS = 5
        const val SEED_HOTSEAT = 4
    }
}
