package app.lawnchair.organizer.integration

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
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
 * Issue #108 alternate-grid evidence through the official IDP/grid-control
 * seam. Grid changes are always restored to the pre-test option.
 */
@RunWith(AndroidJUnit4::class)
class Issue108GridEvidenceInstrumentationTest {
    @Test
    fun freshProductionCaptureReflectsOfficialAlternateGrid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val overrides = DeviceProfileOverrides.INSTANCE.get(context)
        val originalName = overrides.getCurrentGridName()
        val alternate = alternateGrid(context, originalName)
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

        try {
            setGrid(context, idp, alternate.name)
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
            setGrid(context, idp, originalName)
            awaitOriginalGrid(context, idp, originalName)
        }
    }

    @Test
    fun staleGridCaptureIsRejectedWithoutLauncherWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val overrides = DeviceProfileOverrides.INSTANCE.get(context)
        val originalName = overrides.getCurrentGridName()
        val alternate = alternateGrid(context, originalName)
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val module = LayoutApplicationModule.production(context, launcher)
        val reconciliation = module.reconcileAtStart()
        check(reconciliation == app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean) {
            "production module must be reconciled before stale-grid evidence: $reconciliation"
        }

        try {
            setGrid(context, idp, originalName)
            awaitOriginalGrid(context, idp, originalName)
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

            setGrid(context, idp, alternate.name)
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
            setGrid(context, idp, originalName)
            awaitOriginalGrid(context, idp, originalName)
        }
    }

    private fun setGrid(context: Context, idp: InvariantDeviceProfile, name: String) {
        idp.setCurrentGrid(context, name)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun alternateGrid(context: Context, originalName: String): InvariantDeviceProfile.GridOption {
        val options = InvariantDeviceProfile.parseAllGridOptions(context)
        val requested = InstrumentationRegistry.getArguments().getString("issue108.grid")
        val alternate = if (requested == null) {
            options.firstOrNull { it.name != originalName }
        } else {
            options.firstOrNull { it.name == requested }
                ?: error("issue108.grid=$requested is not an available grid option: ${options.map { it.name }}")
        }
        requireNotNull(alternate) {
            "issue108.grid=${requested ?: "<default>"} must differ from the original grid $originalName"
        }
        require(alternate.name != originalName) {
            "issue108.grid=${alternate.name} must differ from the original grid $originalName"
        }
        return alternate
    }

    private fun awaitGrid(idp: InvariantDeviceProfile, columns: Int, rows: Int) {
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (idp.numColumns == columns && idp.numRows == rows) return
            SystemClock.sleep(100)
        }
        error("IDP did not converge to requested grid ${columns}x$rows (actual ${idp.numColumns}x${idp.numRows})")
    }

    private fun awaitOriginalGrid(context: Context, idp: InvariantDeviceProfile, name: String) {
        val option = InvariantDeviceProfile.parseAllGridOptions(context).first { it.name == name }
        awaitGrid(idp, option.numColumns, option.numRows)
    }
}
