package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.ProductionOrganizationInputComposer
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec #130: canonical capture must derive orientation from the same
 * constructed DeviceProfile authority as the launcher UI, preserve it through
 * the production composer, and treat orientation-value changes as revision
 * changes (stale rejection without any DB write).
 */
@RunWith(AndroidJUnit4::class)
class TwoPanelOrientationCaptureInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        cleanRecoveryArtifacts()
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            restoreFavorites(snapshotRows)
            launcher.model.forceReload()
            val model = launcher.model
            val deadline = System.currentTimeMillis() + 5_000L
            while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            cleanRecoveryArtifacts()
        }
    }

    /**
     * Removes the recovery database and its companion inspection inventory.
     * Deleting only the database leaves the startup classifier in
     * SuspiciousAbsence, which fail-closes reconciliation for the next test.
     */
    private fun cleanRecoveryArtifacts() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).deleteRecursively()
    }

    @Test
    fun capturedOrientationMatchesConstructedDeviceProfileAuthority() {
        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("orientation-authority"))
        val activeProfile = InvariantDeviceProfile.INSTANCE.get(context).getDeviceProfile(context)
        val captured = capture.layoutState.deviceCapabilities.orientation

        assertEquals(activeProfile.isTwoPanels, captured.isTwoPanel())
        if (!activeProfile.isTwoPanels) {
            val expected = if (
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                DeviceOrientation.LANDSCAPE
            } else {
                DeviceOrientation.PORTRAIT
            }
            assertEquals(expected, captured)
        }
    }

    @Test
    fun productionComposerPreservesCapturedOrientationIntoPlannerInput() {
        ensureLauncherRow(context, launcher)
        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("orientation-compose"))
        val composition = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
        assertTrue(composition is OrganizationInputComposition.Ready)
        val ready = composition as OrganizationInputComposition.Ready
        assertEquals(
            capture.layoutState.deviceCapabilities.orientation.name,
            ready.input.snapshot.device.orientation.name,
        )
    }

    @Test
    fun orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite() {
        val plannedRowId = ensureLauncherRow(context, launcher)
        val writer = realWriter()
        val originalAccelerometer = systemSetting(Settings.System.ACCELEROMETER_ROTATION)
        val originalUserRotation = systemSetting(Settings.System.USER_ROTATION)
        try {
            bringLauncherToForeground()
            // The launcher locks portrait on phones; the upstream test hook
            // (TestProtocol REQUEST_ENABLE_ROTATION) lifts that for testing.
            enableLauncherTestRotation()
            lockRotationTo(android.content.res.Configuration.ORIENTATION_PORTRAIT)

            val capture = writer.captureCurrent(CaptureId("orientation-stale"))
            assertTrue(capture.layoutState.items.isNotEmpty())
            val plannedId = ItemId(plannedRowId.toString())
            val sourceItem = capture.layoutState.items.single {
                (it.ref as? ApplicationItemRef.PersistentItem)?.itemId == plannedId
            }
            val intendedItem = sourceItem.copy(title = OptionalText.Present("orientation-stale"))
            val plan = ValidatedLayoutPlan(
                capture.revision,
                capture.layoutState,
                capture.layoutState.copy(
                    items = capture.layoutState.items.map { if (it.ref == sourceItem.ref) intendedItem else it },
                ),
                listOf(ApplyAction.Update(sourceItem.ref, sourceItem, intendedItem)),
                emptyList(),
                emptyList(),
                RuleVersion("instrumentation"),
                TaxonomyVersion("instrumentation"),
            )
            val rowsBefore = snapshotFavorites()

            lockRotationTo(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            assertTrue(
                "Host configuration did not report landscape within timeout",
                awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE),
            )

            val clock = SystemClock()
            val module = LayoutApplicationModule(
                writer,
                RecoveryStore(context, clock::nowMillis),
                clock,
                SecureRandomOperationIdSource(),
                folderTitleResolver = GeneratedFolderTitles.resolver(context),
            )
            module.reconcileAtStart()
            val result = module.apply(plan)

            assertTrue(result is ApplyResult.Rejected)
            assertEquals(
                PreWriteRejection.STALE_REVISION,
                (result as ApplyResult.Rejected).reason,
            )
            // The rejected apply must not have landed. Unrelated rows may
            // legitimately change during rotation relayout (the launcher fills
            // placement/modified on folder children), so no-write is asserted
            // on the plan's own row and marker title.
            val rowsAfter = snapshotFavorites()
            assertTrue(rowsAfter.none { it.getAsString(Favorites.TITLE) == "orientation-stale" })
            assertEquals(
                rowsBefore.single { it.getAsString(Favorites._ID) == plannedRowId.toString() },
                rowsAfter.single { it.getAsString(Favorites._ID) == plannedRowId.toString() },
            )
        } finally {
            restoreRotation(originalAccelerometer, originalUserRotation)
        }
    }

    private fun realWriter() = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

    /** Enables launcher rotation for testing on the live activity, as upstream TestProtocol does. */
    private fun enableLauncherTestRotation() {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var enabled = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val activity = Launcher.ACTIVITY_TRACKER.getCreatedActivity<Launcher>()
                    if (activity != null) {
                        activity.getRotationHelper().forceAllowRotationForTesting(true)
                        enabled = true
                    }
                } finally {
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            if (enabled) return
            Thread.sleep(200)
        }
        error("Launcher activity was not created; cannot enable test rotation")
    }

    private fun DeviceOrientation.isTwoPanel() = this == DeviceOrientation.TWO_PANEL_PORTRAIT ||
        this == DeviceOrientation.TWO_PANEL_LANDSCAPE

    private fun lockRotationTo(orientation: Int) {
        shell("settings put system accelerometer_rotation 0")
        val userRotation = when (orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 1

            else -> 0
        }
        shell("settings put system user_rotation $userRotation")
        assertTrue(
            "Host configuration did not report orientation $orientation within timeout",
            awaitOrientation(orientation),
        )
    }

    private fun awaitOrientation(orientation: Int): Boolean {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            if (context.resources.configuration.orientation == orientation) return true
            Thread.sleep(100)
        }
        return context.resources.configuration.orientation == orientation
    }

    private fun restoreRotation(originalAccelerometer: Int?, originalUserRotation: Int?) {
        if (originalUserRotation != null) {
            shell("settings put system user_rotation $originalUserRotation")
        } else {
            shell("settings delete system user_rotation")
        }
        if (originalAccelerometer != null) {
            shell("settings put system accelerometer_rotation $originalAccelerometer")
        } else {
            shell("settings delete system accelerometer_rotation")
        }
    }

    private fun shell(command: String) = shellOutput(command) { true }

    /** Brings the debug launcher home activity to the foreground so host rotation reaches its configuration. */
    private fun bringLauncherToForeground() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(
                ComponentName(context.packageName, LawnchairLauncher::class.java.name),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val focus = shellOutput("dumpsys window") { line -> line.startsWith("  mCurrentFocus") }
            if (focus.contains(context.packageName)) return
            Thread.sleep(250)
        }
        // Fall through: awaitOrientation below reports a precise failure if the
        // launcher never reached the foreground.
    }

    private fun shellOutput(command: String, filter: (String) -> Boolean): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            return stream.readBytes().decodeToString().lineSequence().filter(filter).joinToString("\n")
        }
    }

    private fun systemSetting(name: String): Int? = try {
        Settings.System.getInt(context.contentResolver, name)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    /** Returns the _ID of an existing or freshly inserted stable launcher row. */
    private fun ensureLauncherRow(
        context: android.content.Context,
        launcher: LauncherAppState,
    ): Long {
        val db = launcher.model.modelDbController.db
        // Reuse an existing row only when it is a workspace/hotseat item: the
        // no-write assertion below compares this row before and after a
        // rotation, and the launcher's relayout legitimately rewrites
        // placement/modified on folder children (container = a folder id, with
        // null screen/cells). Lane ordering decides whether rows from earlier
        // instrumented classes are present, so without this filter the reused
        // row — and therefore the test's outcome — depends on which row the
        // unordered query happens to return first.
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.CONTAINER),
            null,
            null,
            null,
            null,
            Favorites._ID,
        ).use {
            while (it.moveToNext()) {
                val container = it.getLong(it.getColumnIndexOrThrow(Favorites.CONTAINER))
                if (container == Favorites.CONTAINER_DESKTOP.toLong() || container == Favorites.CONTAINER_HOTSEAT.toLong()) {
                    return it.getLong(it.getColumnIndexOrThrow(Favorites._ID))
                }
            }
        }
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Orientation capture")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()))
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 1)
            },
        )
        return id.toLong()
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val db = launcher.model.modelDbController.db
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows.add(readRow(cursor, columns))
        }
        return rows
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues {
        val values = ContentValues()
        for (index in columns.indices) {
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
            }
        }
        return values
    }

    private fun restoreFavorites(snapshot: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in snapshot) {
                db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
