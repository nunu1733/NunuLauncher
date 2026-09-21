package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerHubPreferences
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizer
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.IntSet
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #376 (RS-AC-01): cold-process restore evidence through the
 * production seam, recorded as two separate `am instrument` invocations —
 * each invocation is its own process, so the restore phase runs in a genuine
 * cold process (no apply context, empty one-shot confirmation registry) with
 * the hub as the first surface and the Launcher workspace never opened.
 *
 * Evidence tooling, deliberately NOT part of any CI lane class list (same
 * status as `OrganizerDurableStatusInstrumentationTest`). Run:
 *
 * ```bash
 * # phase 1 — seed a verified apply and leave the store restorable
 * adb shell am instrument -w \
 *   -e class app.lawnchair.organizer.ui.OrganizerRestoreColdProcessEvidenceTest \
 *   -e phase seed app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner
 * # process boundary (force-stop makes the cold property explicit)
 * adb shell am force-stop app.lawnchair.debug
 * # phase 2 — cold process: hub → CTA → inspection → confirmation → restore
 * adb shell am instrument -w \
 *   -e class app.lawnchair.organizer.ui.OrganizerRestoreColdProcessEvidenceTest \
 *   -e phase restore app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OrganizerRestoreColdProcessEvidenceTest {

    private val phase: String =
        requireNotNull(InstrumentationRegistry.getArguments().getString("phase")) {
            "Pass -e phase seed|restore"
        }

    private lateinit var context: Context
    private lateinit var launcher: LauncherAppState
    private lateinit var preferenceManager: PreferenceManager2
    private lateinit var overridePreferences: android.content.SharedPreferences
    private var reloadLatch: CountDownLatch? = null
    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun finishBindingItems(pagesBoundFirst: IntSet) {
            reloadLatch?.countDown()
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun marker(): File = File(context.filesDir, "issue376_cold_evidence.marker")

    @After
    fun tearDown() {
        if (phase != "restore") return
        try {
            if (::preferenceManager.isInitialized) {
                preferenceManager.enableSmartspace.setBlocking(true)
            }
            if (::launcher.isInitialized) {
                val db = launcher.model.modelDbController.db
                db.delete(Favorites.TABLE_NAME, null, null)
                launcher.model.modelDbController.clearEmptyDbFlag()
                reloadAndWait()
                if (::overridePreferences.isInitialized) {
                    overridePreferences.edit().clear().apply()
                }
                clearRecoveryStoreArtifacts()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    launcher.model.removeCallbacks(modelCallbacks)
                }
            }
        } catch (_: IllegalStateException) {
            // The compose host may already be torn down at @After; the test
            // AVD is disposable, so residual fixture rows are acceptable here.
        }
    }

    @Test
    fun seedRestorableProductionState() {
        check(phase == "seed") { "Run with -e phase seed" }
        setupFixture()
        val module = LayoutApplicationModule.production(
            context,
            GeneratedFolderTitles.resolver(context),
            launcher,
        )
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val run = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))
        // Plain full organization: pass through the selection surface with an
        // empty selection (the #228-equivalent of the pre-#228 flow).
        run.start()
        (run.state as? ManualOrganizationRun.State.Selecting)?.let { run.confirmSelection(emptySet()) }
        val preview = run.state as? ManualOrganizationRun.State.Preview
            ?: error("Seed run did not reach preview: ${run.state}")
        assertTrue(preview.summary.movedCount >= 1)
        run.confirm()
        val applied = run.state as? ManualOrganizationRun.State.Applied
            ?: error("Seed run did not reach applied: ${run.state}")
        val applyResult = applied.result as? ApplyResult.Applied
            ?: error("Seed run returned non-success result: ${applied.result}")

        // The durable status must already present the restore entry the hub
        // will cold-derive in the restore phase.
        assertEquals(OrganizerDurableStatus.ORGANIZED_RESTORABLE, module.durableOrganizerStatus())

        marker().writeText(applyResult.pointId.value)
        check(marker().exists()) { "Unable to write the cold-evidence marker" }
        // Deliberately no cleanup: the applied layout and the restorable
        // recovery point must survive this process for the cold phase.
    }

    @Test
    fun hubRestoreCompletesFromColdProcessWithoutOpeningLauncher() {
        check(phase == "restore") { "Run with -e phase restore" }
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        check(marker().exists()) { "Run the seed phase first (marker missing)" }
        val coordinator = ManualOrganizationModule.get(context)
        var dispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val navController: NavHostController = rememberNavController()
            CompositionLocalProvider(LocalNavController provides navController) {
                LawnchairTheme {
                    NavHost(navController = navController, startDestination = HomeScreenOrganizer) {
                        composable<HomeScreenOrganizer> {
                            OrganizerHubPreferences(run = coordinator)
                        }
                        composable<HomeScreenManualOrganization> { backStackEntry ->
                            val route = backStackEntry.toRoute<HomeScreenManualOrganization>()
                            ManualOrganizationPreferences(
                                run = coordinator,
                                trigger = route.trigger,
                            )
                        }
                    }
                }
            }
        }

        // Cold-process hub: startup reconciliation + model load must complete
        // on their own before the restorable row and the CTA appear (DS-AC-10
        // initialization path; the Launcher workspace is never opened).
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText(
                context.getString(com.android.launcher3.R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                context.getString(com.android.launcher3.R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(com.android.launcher3.R.string.manual_organization_recovery),
        ).performClick()

        composeRule.waitUntil(30_000) { coordinator.state is ManualOrganizationRun.State.RecoveryPreview }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                context.getString(com.android.launcher3.R.string.manual_organization_recovery_confirm),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(com.android.launcher3.R.string.manual_organization_recovery_confirm),
        ).performClick()

        composeRule.waitUntil(30_000) { coordinator.state is ManualOrganizationRun.State.RecoveryResultState }
        val result = (coordinator.state as ManualOrganizationRun.State.RecoveryResultState).result
        assertTrue("Expected a restored result, got $result", result is RecoveryResult.Restored)

        // The explicit hub return re-derives the durable status; with the
        // single restored point the row collapses to "restored or expired".
        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        composeRule.waitUntil(30_000) { coordinator.state is ManualOrganizationRun.State.Idle }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(
                context.getString(com.android.launcher3.R.string.manual_organization_durable_status_restored_or_expired),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        check(marker().delete()) { "Unable to consume the cold-evidence marker" }
    }

    private fun setupFixture() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        preferenceManager = PreferenceManager2.getInstance(context)
        overridePreferences = context.getSharedPreferences(OVERRIDE_STORE, Context.MODE_PRIVATE)
        clearRecoveryStoreArtifacts()
        marker().delete()

        val serial = UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle())
        check(
            overridePreferences.edit()
                .putInt("schema", 1)
                .putLong("generation", 1L)
                .putString("entries", "${context.packageName}|$serial|TOOLS")
                .commit(),
        ) { "Unable to install deterministic test classification override" }

        val db = launcher.model.modelDbController.db
        db.delete(Favorites.TABLE_NAME, null, null)
        preferenceManager.enableSmartspace.setBlocking(true)
        check(FeatureFlags.topQsbOnFirstScreenEnabled(context)) {
            "Cold evidence seeding requires QSB on the first workspace screen"
        }
        insertFixtureRow(db, 0, 1, "Issue376 cold A")
        insertFixtureRow(db, 0, 2, "Issue376 cold B")
        launcher.model.modelDbController.clearEmptyDbFlag()
        reloadAndWait()
    }

    private fun insertFixtureRow(
        db: android.database.sqlite.SQLiteDatabase,
        screen: Int,
        cellY: Int,
        title: String,
    ) {
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, title)
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L + screen)
                put(Favorites.RESTORED, 0)
                put(
                    Favorites.PROFILE_ID,
                    UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
                )
                put(Favorites.RANK, screen)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
    }

    private fun clearRecoveryStoreArtifacts() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val snapshotDirectory = File(
            context.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        )
        snapshotDirectory.listFiles()?.forEach { check(it.delete()) { "Unable to delete ${it.name}" } }
        if (snapshotDirectory.exists()) check(snapshotDirectory.delete()) { "Unable to delete $snapshotDirectory" }
    }

    private fun reloadAndWait() {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(modelCallbacks)
            launcher.model.forceReload()
        }
        check(latch.await(30, TimeUnit.SECONDS)) {
            "Launcher model reload did not finish for the Issue #376 cold evidence"
        }
    }

    private companion object {
        const val OVERRIDE_STORE = "organizer_category_overrides"
    }
}
