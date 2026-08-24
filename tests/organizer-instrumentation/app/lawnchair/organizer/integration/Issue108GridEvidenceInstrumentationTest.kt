package app.lawnchair.organizer.integration

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #108 alternate-grid evidence through Lawnchair's grid-control state.
 * Grid changes are always restored to the pre-test dimensions.
 *
 * The preset transition is applied through the same Lawnchair preference keys
 * that [DeviceProfileOverrides.setCurrentGrid] writes. The named-preset seam
 * itself is broken on non-phone hosts because its preset snapshot is built
 * before the launcher determines the device type (finding split from Issue
 * #108), so applying dimensions directly is the only working official path
 * here; the target preset must still be an enabled GridOption declaration.
 */
@RunWith(AndroidJUnit4::class)
class Issue108GridEvidenceInstrumentationTest {
    @Test
    fun freshProductionCaptureReflectsOfficialAlternateGrid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val original = currentDimensions()
        val alternate = requestedAlternate(context, original)
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

        try {
            applyPresetDimensions(context, alternate)
            awaitGrid(idp, alternate.numColumns, alternate.numRows)

            val capture = writer.captureCurrent(CaptureId("issue108-fresh-grid"))
            assertEquals(alternate.numColumns, capture.layoutState.deviceCapabilities.columns)
            assertEquals(alternate.numRows, capture.layoutState.deviceCapabilities.rows)
            val composed = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
            assertTrue(composed is OrganizationInputComposition.Ready)
            val ready = composed as OrganizationInputComposition.Ready
            assertEquals(alternate.numColumns, ready.input.snapshot.device.columns)
            assertEquals(alternate.numRows, ready.input.snapshot.device.rows)
        } finally {
            restoreDimensions(context, idp, original)
        }
    }

    @Test
    fun staleGridCaptureIsRejectedWithoutLauncherWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val original = currentDimensions()
        val alternate = requestedAlternate(context, original)
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val module = LayoutApplicationModule.production(context, launcher)
        val reconciliation = module.reconcileAtStart()
        check(reconciliation == app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean) {
            "production module must be reconciled before stale-grid evidence: $reconciliation"
        }

        try {
            restoreDimensions(context, idp, original)
            val before = writer.captureCurrent(CaptureId("issue108-grid-plan"))
            val plan = ValidatedLayoutPlan(
                sourceRevision = before.revision,
                sourceState = before.layoutState,
                intendedState = before.layoutState,
                actions = before.layoutState.items.map { ApplyAction.Preserve(it.ref, it) },
                newPages = emptyList(),
                newFolders = emptyList(),
                ruleVersion = RuleVersion("issue108"),
                taxonomyVersion = TaxonomyVersion("issue108"),
            )

            applyPresetDimensions(context, alternate)
            awaitGrid(idp, alternate.numColumns, alternate.numRows)
            val afterGrid = writer.captureCurrent(CaptureId("issue108-grid-stale"))
            assertNotEquals("grid capabilities participate in the revision", before.revision, afterGrid.revision)

            val result = module.apply(plan)
            assertTrue(result is ApplyResult.Rejected)
            assertEquals(PreWriteRejection.STALE_REVISION, (result as ApplyResult.Rejected).reason)
            val afterApply = writer.recaptureDb()
            assertEquals(
                "stale grid plan must not mutate Launcher rows",
                afterGrid.manifest.rows,
                afterApply.manifest.rows,
            )
            assertEquals(afterGrid.manifest.rowCount, afterApply.manifest.rowCount)
        } finally {
            restoreDimensions(context, idp, original)
        }
    }

    private data class GridDimensions(val columns: Int, val rows: Int, val hotseat: Int)

    private fun currentDimensions(): GridDimensions {
        val info = DeviceProfileOverrides.INSTANCE.get(
            ApplicationProvider.getApplicationContext<Context>(),
        ).getGridInfo()
        return GridDimensions(info.numColumns, info.numRows, info.numHotseatColumns)
    }

    private fun requestedAlternate(
        context: Context,
        original: GridDimensions,
    ): InvariantDeviceProfile.GridOption {
        val options = InvariantDeviceProfile.parseAllGridOptions(context)
        val requested = InstrumentationRegistry.getArguments().getString("issue108.grid")
        val alternate = if (requested == null) {
            options.firstOrNull { it.numColumns != original.columns || it.numRows != original.rows }
        } else {
            options.firstOrNull { it.name == requested }
                ?: error("issue108.grid=$requested is not an available grid option: ${options.map { it.name }}")
        }
        checkNotNull(alternate) {
            "no enabled grid option differs from the pre-test grid ${original.columns}x${original.rows}: " +
                "${options.map { it.name }}"
        }
        return alternate
    }

    private fun applyPresetDimensions(
        context: Context,
        option: InvariantDeviceProfile.GridOption,
    ) {
        setWorkspaceDimensions(context, GridDimensions(option.numColumns, option.numRows, option.numHotseatIcons))
    }

    private fun restoreDimensions(
        context: Context,
        idp: InvariantDeviceProfile,
        original: GridDimensions,
    ) {
        setWorkspaceDimensions(context, original)
        awaitGrid(idp, original.columns, original.rows)
    }

    private fun setWorkspaceDimensions(context: Context, dimensions: GridDimensions) {
        // Identical keys to DeviceProfileOverrides.setCurrentGrid.
        val prefs = PreferenceManager.getInstance(context)
        prefs.workspaceRows.set(dimensions.rows)
        prefs.workspaceColumns.set(dimensions.columns)
        prefs.hotseatColumns.set(dimensions.hotseat)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitGrid(idp: InvariantDeviceProfile, columns: Int, rows: Int) {
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (idp.numColumns == columns && idp.numRows == rows) return
            SystemClock.sleep(100)
        }
        error("IDP did not converge to requested grid ${columns}x$rows (actual ${idp.numColumns}x${idp.numRows})")
    }
}
