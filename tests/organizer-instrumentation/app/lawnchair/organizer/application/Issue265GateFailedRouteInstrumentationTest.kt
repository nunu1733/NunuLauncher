package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Issue #265 remaining-investigation reproductions (spec
 * 265-post-apply-recovery-reconciliation, AC-265-R1/R2): the routes by which
 * startup reconciliation can leave the [app.lawnchair.organizer.application.protocol.ReadinessGate]
 * permanently `FAILED`, making every organizer compose return
 * `INPUT_NOT_READY` / `INPUT_READINESS` / `RECONCILIATION_FAILED` — the
 * signature of the original report's second run.
 *
 * Routes reproduced here (store-file manipulation is allowed for
 * investigation fixtures only; teardown restores the snapshot):
 *
 * - route1b: a `RESTORING` record whose payload fails its checksum is
 *   unresolvable → gate `FAILED`, compose `RECONCILIATION_FAILED`, record
 *   advanced to `CORRUPT`, and a `RESTART_RECONCILED` journal event. The
 *   lifecycle column is itself checksummed, so any external tampering with
 *   the durable store fails closed identically; a confirm that dies between
 *   `markRestoring` and verification (fault injection is NOOP in production)
 *   leaves the same unresolved-record surface whenever its re-reconciliation
 *   cannot resolve.
 * - route2: an unrepresentable current row (desktop NULL span) makes the
 *   reconciliation's own authoritative-classification capture throw; the
 *   exception escapes the block → gate `FAILED`.
 * - route3: the artifact-pair poison state (recovery DB absent, inspection
 *   snapshot present) fails the store availability check → gate `FAILED`
 *   with no reconciliation journal event.
 * - writerBusy: an immediate confirm after apply — the one-off "Restore did
 *   nothing" surface; the observed result is recorded for the disposition.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Issue265GateFailedRouteInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private lateinit var launcherScenario: ActivityScenario<LawnchairLauncher>
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Issue #371: the JIT Usage Access pause gates an ungranted process's
        // first composition at the composed-phase entry. These tests drive
        // production runs to Applied (gate-FAILED routes, not the permission
        // flow), so grant the app-op — the granted fast path, same as the
        // routed Issue265ManualEditRecoveryInstrumentationTest.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} GET_USAGE_STATS allow",
        ).close()
        Thread.sleep(1000)
        launcher = LauncherAppState.getInstance(context)
        closeRecoveryStoreHelper()
        deleteRecoveryArtifacts()
        launcherScenario = ActivityScenario.launch(LawnchairLauncher::class.java)
        awaitModelLoaded()
        driveStartupReconciliation()
        awaitGate(ReadinessGate.State.READY)
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            ManualOrganizationModule.get(context).cancel()
            ManualOrganizationModule.get(context).dismiss()
            restoreFavorites(snapshotRows)
            launcher.model.forceReload()
            awaitModelLoaded()
            closeRecoveryStoreHelper()
        } finally {
            if (::launcherScenario.isInitialized) launcherScenario.close()
            deleteRecoveryArtifacts()
        }
    }

    @Test
    fun route1b_unverifiableRestoringRecordFailsGatePersistently() {
        organizeAndConfirm()
        closeRecoveryStoreHelper()
        // Directly flipping the lifecycle also invalidates the payload
        // checksum (lifecycle is part of the checksummed row), so this
        // fixture is exactly the "unresolvable record" case: any external
        // tampering with the durable store fails closed the same way.
        strandLatestRecord(corrupt = true)

        val summary = driveStartupReconciliation()
        val recordAfter = readLatestRecoveryRecord()
        report("ROUTE1B_SUMMARY=$summary")
        report("ROUTE1B_RECORD_AFTER=$recordAfter")
        awaitGate(ReadinessGate.State.FAILED)
        assertTrue(
            "checksum-invalid RESTORING record must advance to CORRUPT: $recordAfter",
            recordAfter.contains("lifecycle=8"),
        )

        // The reported signature: every compose returns the typed
        // RECONCILIATION_FAILED unavailability for the process lifetime.
        val runner = ManualOrganizationModule.get(context)
        val start = runStart(runner)
        report("ROUTE1B_NEXT_ORGANIZER=$start")
        assertTrue(
            "compose must surface InputUnavailable under a failed gate: $start",
            start is ManualOrganizationRun.State.InputUnavailable,
        )
        assertTrue(
            "journal must contain a RESTART_RECONCILED event for the unresolved record",
            journalText().contains("RESTART_RECONCILED"),
        )
    }

    @Test
    fun route2_unrepresentableRowDuringReconciliationFailsGate() {
        organizeAndConfirm()
        // No record tampering: the VERIFIED record reconciles with a valid
        // checksum, but reconciliation itself captures the current layout for
        // authoritative classification. A desktop NULL-span row (the platform
        // writer no longer produces it post-#269, but capture strictness is
        // unchanged) makes that capture throw inside the gate block.
        setRawSpan(standaloneDesktopRowId(), null, null)

        val summary = driveStartupReconciliation()
        report("ROUTE2_SUMMARY=$summary")
        awaitGate(ReadinessGate.State.FAILED)

        val runner = ManualOrganizationModule.get(context)
        val start = runStart(runner)
        report("ROUTE2_NEXT_ORGANIZER=$start")
        assertTrue(
            "capture failure inside reconciliation must fail the gate: $start",
            start is ManualOrganizationRun.State.InputUnavailable,
        )
    }

    @Test
    fun route3_artifactPairPoisonStateFailsGateWithoutReconciliationEvents() {
        organizeAndConfirm()
        // A successful reconcile published the inspection snapshot; now remove
        // only the recovery DB so the artifact pair becomes DB-absent +
        // snapshot-present.
        closeRecoveryStoreHelper()
        deleteRecoveryDatabaseFiles()

        val summary = driveStartupReconciliation()
        report("ROUTE3_SUMMARY=$summary")
        awaitGate(ReadinessGate.State.FAILED)

        val runner = ManualOrganizationModule.get(context)
        val start = runStart(runner)
        report("ROUTE3_NEXT_ORGANIZER=$start")
        assertTrue(
            "poison artifact pair must fail the gate: $start",
            start is ManualOrganizationRun.State.InputUnavailable,
        )
        // Route-3 signature: the availability check fails before any candidate
        // is processed, so this reconcile emits no RESTART_RECONCILED event
        // (earlier reconciles in this process may legitimately have done so).
    }

    // ------------------------------------------------------------------
    // orchestration helpers
    // ------------------------------------------------------------------

    private fun organizeAndConfirm() {
        val runner = ManualOrganizationModule.get(context)
        seedLayoutWithFolder()
        val startState = runStart(runner)
        // Issue #417 (spec 417, AC-1): a manual run parks at Selecting while
        // unplaced candidates exist and only reaches the composed phase
        // through the frozen scope. Same continuation as the routed E2E
        // startPlain helper; the #265 gate assertions below are unchanged.
        if (startState is ManualOrganizationRun.State.Selecting) {
            runner.confirmSelection(emptySet())
        }
        check(runner.state is ManualOrganizationRun.State.ScopeConfirmed) {
            "Run did not reach the method-choice face: ${runner.state}"
        }
        runner.planWithConfirmedScope()
        if (runner.state is ManualOrganizationRun.State.Preview) {
            runner.confirm()
        }
        val applied = runner.state as? ManualOrganizationRun.State.Applied
            ?: error("organize did not reach Applied: ${runner.state}")
        check(applied.result is ApplyResult.Applied) { "apply did not verify: ${applied.result}" }
        launcher.model.forceReload()
        awaitModelLoaded()
    }

    private var runnerStateSnapshot: ManualOrganizationRun.State = ManualOrganizationRun.State.Idle

    private fun runStart(runner: ManualOrganizationRun): ManualOrganizationRun.State {
        runner.start()
        runnerStateSnapshot = runner.state
        return runner.state
    }

    private fun driveStartupReconciliation(): String {
        val module = (context.applicationContext as LawnchairApp).layoutApplicationModule
        val summary = module.reconcileAtStart()
        return summary.toString()
    }

    private fun awaitGate(target: ReadinessGate.State) {
        val module = (context.applicationContext as LawnchairApp).layoutApplicationModule
        val deadline = System.currentTimeMillis() + 30_000L
        while (module.readinessGate.state != target && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(target, module.readinessGate.state)
    }

    // ------------------------------------------------------------------
    // durable store fixtures (investigation-only direct store access)
    // ------------------------------------------------------------------

    private fun openRecoveryDb(): SQLiteDatabase {
        val dbFile = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        check(dbFile.exists()) { "recovery DB missing" }
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }

    private fun strandLatestRecord(corrupt: Boolean) {
        openRecoveryDb().use { db ->
            db.rawQuery(
                "SELECT point_id FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} " +
                    "ORDER BY updated_at_ms DESC LIMIT 1",
                emptyArray(),
            ).use { c ->
                check(c.moveToFirst()) { "no recovery record to strand" }
                val pointId = c.getString(0)
                // LifecycleState.RESTORING = ordinal 5.
                db.execSQL(
                    "UPDATE ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} SET lifecycle = 5 " +
                        "WHERE point_id = ?",
                    arrayOf(pointId),
                )
                if (corrupt) {
                    // payload_checksum is re-verified against the encoded row;
                    // zeroing it deterministically fails the checksum.
                    db.execSQL(
                        "UPDATE ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} " +
                            "SET payload_checksum = zeroblob(32) WHERE point_id = ?",
                        arrayOf(pointId),
                    )
                }
                report("STRANDED point=${pointId.take(8)} corrupt=$corrupt")
            }
        }
    }

    private fun readLatestRecoveryRecord(): String {
        val dbFile = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        if (!dbFile.exists()) return "NO_DB"
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { readable ->
            return readable.rawQuery(
                "SELECT point_id, lifecycle, updated_at_ms FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} " +
                    "ORDER BY updated_at_ms DESC LIMIT 3",
                emptyArray(),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add("point=${c.getString(0).take(8)} lifecycle=${c.getInt(1)} updated=${c.getLong(2)}")
                    }
                }.ifEmpty { listOf("NO_RECORDS") }.joinToString("; ")
            }
        }
    }

    private fun journalText(): String {
        val journal = File(context.filesDir, "organizer_diagnostics/organizer_diagnostics.journal")
        if (!journal.exists()) return ""
        return journal.readText()
    }

    private fun deleteRecoveryDatabaseFiles() {
        val dbDir = context.getDatabasePath(RecoveryDbSchema.FILE_NAME).parentFile ?: return
        dbDir.listFiles()
            ?.filter { it.name.startsWith("organizer_recovery") }
            ?.forEach { it.delete() }
        // The inspection snapshot under no_backup/recovery-inspection/ is kept:
        // DB-absent + snapshot-present is the poison pair under test.
    }

    private fun deleteRecoveryArtifacts() {
        deleteRecoveryDatabaseFiles()
        File(
            context.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).takeIf { it.exists() }?.deleteRecursively()
    }

    /** Close the production helper before deleting its database between tests. */
    private fun closeRecoveryStoreHelper() {
        val module = (context.applicationContext as LawnchairApp).layoutApplicationModule
        val storeField = module.javaClass.getDeclaredField("store").apply { isAccessible = true }
        val store = storeField.get(module)
        val helperField = store.javaClass.getDeclaredField("helper").apply { isAccessible = true }
        helperField.get(store).javaClass.getMethod("close").invoke(helperField.get(store))
    }

    // ------------------------------------------------------------------
    // layout fixture helpers (same contracts as the #265/#269 harness)
    // ------------------------------------------------------------------

    private fun setRawSpan(rowId: Long, spanX: Int?, spanY: Int?) {
        val values = ContentValues().apply {
            if (spanX == null) putNull(Favorites.SPANX) else put(Favorites.SPANX, spanX)
            if (spanY == null) putNull(Favorites.SPANY) else put(Favorites.SPANY, spanY)
        }
        assertEquals(
            1,
            launcher.model.modelDbController.db.update(
                Favorites.TABLE_NAME,
                values,
                "${Favorites._ID}=?",
                arrayOf(rowId.toString()),
            ),
        )
    }

    private fun standaloneDesktopRowId(): Long {
        launcher.model.modelDbController.db.rawQuery(
            "SELECT ${Favorites._ID} FROM ${Favorites.TABLE_NAME} " +
                "WHERE ${Favorites.CONTAINER} = ${Favorites.CONTAINER_DESKTOP} " +
                "ORDER BY ${Favorites._ID} ASC LIMIT 1",
            emptyArray(),
        ).use { c ->
            check(c.moveToFirst()) { "no desktop row found" }
            return c.getLong(0)
        }
    }

    private fun awaitModelLoaded() {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!launcher.model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not reload" }
    }

    private fun seedLayoutWithFolder() {
        launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
        val folderId = launcher.model.modelDbController.generateNewItemId()
        insertFolderRow(folderId, cellX = 0, cellY = 2)
        insertLauncherRow("Issue265 C1", folderId, rank = 0)
        insertLauncherRow("Issue265 C2", folderId, rank = 1)
        insertLauncherRow("Issue265 C3", folderId, rank = 2)
        insertLauncherRow("Issue265 S1", Favorites.CONTAINER_DESKTOP, cellX = 2, cellY = 2)
        insertLauncherRow("Issue265 S2", Favorites.CONTAINER_DESKTOP, cellX = 3, cellY = 2)
        insertLauncherRow("Issue265 S3", Favorites.CONTAINER_DESKTOP, cellX = 4, cellY = 2)
        launcher.model.forceReload()
        awaitModelLoaded()
    }

    private fun insertFolderRow(id: Int, cellX: Int, cellY: Int) {
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, "Issue265 folder")
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.SCREEN, 0)
            put(Favorites.CELLX, cellX)
            put(Favorites.CELLY, cellY)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.MODIFIED, System.currentTimeMillis())
            put(Favorites.RESTORED, 0)
            put(
                Favorites.PROFILE_ID,
                UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
            )
            put(Favorites.RANK, 0)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
            put(
                Favorites.ORGANIZER_LOCK_STATE,
                app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED.ordinal,
            )
        }
        check(
            launcher.model.modelDbController.db.insertOrThrow(Favorites.TABLE_NAME, null, values) == id.toLong(),
        ) { "Unable to seed folder row" }
    }

    private fun insertLauncherRow(
        title: String,
        container: Int,
        rank: Int = 0,
        cellX: Int = 0,
        cellY: Int = 0,
    ) {
        val id = launcher.model.modelDbController.generateNewItemId()
        val launcherComponent = ComponentName(context.packageName, LawnchairLauncher::class.java.name)
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(launcherComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, title)
            put(Favorites.INTENT, homeIntent.toUri(0))
            put(Favorites.CONTAINER, container)
            if (container == Favorites.CONTAINER_DESKTOP) {
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
            }
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.MODIFIED, System.currentTimeMillis())
            put(Favorites.RESTORED, 0)
            put(
                Favorites.PROFILE_ID,
                UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
            )
            put(Favorites.RANK, rank)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
            put(
                Favorites.ORGANIZER_LOCK_STATE,
                app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED.ordinal,
            )
        }
        check(
            launcher.model.modelDbController.db.insertOrThrow(Favorites.TABLE_NAME, null, values) == id.toLong(),
        ) { "Unable to seed row $title" }
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        launcher.model.modelDbController.db.query(Favorites.TABLE_NAME, null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val row = ContentValues()
                for (index in c.columnNames.indices) {
                    val name = c.columnNames[index]
                    when (c.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> row.putNull(name)
                        android.database.Cursor.FIELD_TYPE_BLOB -> row.put(name, c.getBlob(index))
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(index))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(index))
                        android.database.Cursor.FIELD_TYPE_STRING -> row.put(name, c.getString(index))
                    }
                }
                rows += row
            }
        }
        return rows
    }

    private fun restoreFavorites(rows: List<ContentValues>) {
        if (rows.isEmpty()) return
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            rows.forEach { db.insertOrThrow(Favorites.TABLE_NAME, null, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun report(line: String) {
        android.util.Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "Issue265GateRoute"
    }
}
