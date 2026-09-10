package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.os.CountDownTimer
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.celllayout.CellPosMapper
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Issue #265 two-path reproduction with identical instrumentation, per the
 * review request on the issue:
 *
 * path A (manual edit): organize -> move one item out of the folder through
 * the baseline writer (the real drag call-site: real in-memory ItemInfo,
 * `ModelWriter.moveItemInDatabase` on the main thread) -> Restore -> open
 * Organizer again;
 * path B (control): identical minus the manual edit.
 *
 * Both paths record: recovery preview result, confirmation/result, durable
 * recovery lifecycle (direct recovery-DB read), favorites rows at each stage,
 * pre/post restore manifest equality, the ManualOrganizationRun state machine
 * projection (what Settings renders), and the next organizer start state.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Issue265ManualEditRecoveryInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        deleteRecoveryArtifacts()
        // The model binds when a Launcher activity is alive; the Settings flow
        // under investigation also runs with the launcher in the back stack.
        ActivityScenario.launch(LawnchairLauncher::class.java)
        awaitModelLoaded()
        awaitGateReady()
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            restoreFavorites(snapshotRows)
            launcher.model.forceReload()
            awaitModelLoaded()
        } finally {
            deleteRecoveryArtifacts()
        }
    }

    @Test
    fun pathA_manualEditBeforeRestore() {
        runPath(manualEdit = true)
    }

    @Test
    fun pathB_controlWithoutManualEdit() {
        runPath(manualEdit = false)
    }

    /**
     * Issue #269: a legacy positive span must also converge when the icon
     * reaches the desktop through the Hotseat. The organizer intentionally
     * leaves FolderChild raw geometry nullable; this fixture represents a row
     * written by an older organizer version before the two real writer moves.
     */
    @Test
    fun legacyPositiveSpanThroughHotseatConvergesOnDesktopEntry() {
        val runner = ManualOrganizationModule.get(context)
        seedLayoutWithFolder()
        val startState = runStart(runner)
        if (startState is ManualOrganizationRun.State.Preview) {
            runner.confirm()
        }
        val applied = runner.state as? ManualOrganizationRun.State.Applied
        check(applied?.result is app.lawnchair.organizer.application.public.ApplyResult.Applied) {
            "organize did not reach Applied: ${runner.state}"
        }

        val childRowId = pickFolderChildRowId()
        check(childRowId > 0) { "no folder child row found after organize" }
        setRawSpan(childRowId, spanX = 2, spanY = 2)
        launcher.model.forceReload()
        awaitModelLoaded()

        moveRowToHotseatViaWriter(childRowId, slot = 0)
        assertEquals(2, querySpan(childRowId).first)
        assertEquals(2, querySpan(childRowId).second)

        moveRowToDesktopViaWriter(childRowId, findFreeDesktopCell())
        launcher.model.forceReload()
        awaitModelLoaded()
        assertEquals(1, querySpan(childRowId).first)
        assertEquals(1, querySpan(childRowId).second)
        val reloaded = modelItemOf(childRowId)
        assertEquals(1, reloaded?.spanX)
        assertEquals(1, reloaded?.spanY)
        runner.dismiss()
    }

    private fun runPath(manualEdit: Boolean) {
        report("=== PATH ${if (manualEdit) "A(manual-edit)" else "B(control)"} START ===")
        val runner = ManualOrganizationModule.get(context)

        seedLayoutWithFolder()
        val beforeOrganize = adapter().captureCurrent(CaptureId("issue265-pre-organize"))
        report("PRE_ORGANIZE_ROWS=${beforeOrganize.manifest.rows.size}")
        dumpRawRows("PRE_ORGANIZE")

        // 1. Organize through the real orchestration (the Settings state
        //    machine), then confirm the proposal.
        val startState = runStart(runner)
        report("ORGANIZE_STATE=$startState")
        if (startState is ManualOrganizationRun.State.Preview) {
            runner.confirm()
            report("APPLY_STATE=${runner.state}")
        }
        val applied = runner.state as? ManualOrganizationRun.State.Applied
        check(applied != null) {
            "organize did not reach Applied (state=${runner.state}); path aborted"
        }
        report("APPLY_RESULT=${applied.result}")
        check(applied.result is app.lawnchair.organizer.application.public.ApplyResult.Applied) {
            "apply did not verify: ${applied.result}"
        }
        dumpRawRows("POST_APPLY")
        report("RECOVERY_RECORD_POST_APPLY=${readLatestRecoveryRecord()}")

        // 2. Manual edit: drag-equivalent move of one folder child onto the
        //    workspace (real ItemInfo, baseline writer, main thread).
        if (manualEdit) {
            val childRowId = pickFolderChildRowId()
            check(childRowId > 0) { "no folder child row found post-apply" }
            val target = findFreeDesktopCell()
            moveRowToDesktopViaWriter(childRowId, target)
            launcher.model.forceReload()
            awaitModelLoaded()
            dumpRawRows("POST_MANUAL_EDIT")
        }

        // 3. Restore: preview then confirm, through the same state machine.
        //    A preview-stage exception is itself evidence (the Restore surface
        //    throwing instead of returning a typed result) and is recorded,
        //    then the remaining observation points still run.
        val previewException = runCatching { runner.beginRecoveryPreview() }.exceptionOrNull()
        if (previewException != null) {
            report("PREVIEW_RESULT=THREW ${previewException.javaClass.simpleName}: ${previewException.message}")
        } else {
            report("PREVIEW_STATE=${runner.state}")
            val previewState = runner.state as? ManualOrganizationRun.State.RecoveryPreview
            report("PREVIEW_RESULT=${previewState?.result}")
            if (previewState?.result
                is app.lawnchair.organizer.application.public.RecoveryPreviewResult.Restorable
            ) {
                runner.confirmRecovery()
                report("RECOVERY_STATE=${runner.state}")
                val recoveryState = runner.state
                    as? ManualOrganizationRun.State.RecoveryResultState
                report("RECOVERY_RESULT=${recoveryState?.result}")
                if (recoveryState?.result
                    is app.lawnchair.organizer.application.public.RecoveryResult.WriterBusy
                ) {
                    // A real user presses Restore again; record the retry too.
                    Thread.sleep(2_000)
                    runner.beginRecoveryPreview()
                    val retryPreview = runner.state as? ManualOrganizationRun.State.RecoveryPreview
                    report("RETRY_PREVIEW_RESULT=${retryPreview?.result}")
                    if (retryPreview?.result
                        is app.lawnchair.organizer.application.public.RecoveryPreviewResult.Restorable
                    ) {
                        runner.confirmRecovery()
                        report("RETRY_RECOVERY_RESULT=${(runner.state as? ManualOrganizationRun.State.RecoveryResultState)?.result}")
                    }
                }
            }
        }
        report("RECOVERY_RECORD_POST_RESTORE=${readLatestRecoveryRecord()}")

        // 4. Post-restore layout: manifest equality with the pre-organize
        //    capture is the exact-restore check. A throwing capture is itself
        //    evidence and must not stop the remaining observation points.
        val afterRestore = runCatching {
            adapter().captureCurrent(CaptureId("issue265-post-restore"))
        }
        afterRestore.fold(
            onSuccess = { snapshot ->
                report(
                    "POST_RESTORE_ROWS_MATCH_PRE_ORGANIZE=" +
                        (snapshot.manifest.rows == beforeOrganize.manifest.rows),
                )
            },
            onFailure = { failure ->
                report("POST_RESTORE_CAPTURE=THREW ${failure.javaClass.simpleName}: ${failure.message}")
            },
        )
        dumpRawRows("POST_RESTORE")

        // 5. Open Organizer again: the readiness result a re-opened Settings
        //    would produce.
        val nextStart = runStart(runner)
        report("NEXT_ORGANIZER_STATE=$nextStart")
        runner.dismiss()

        // Contract expectations (spec 13): a verified restore is the only
        // Restored outcome and must equal the pre-organize manifest; anything
        // else must leave the durable record non-RESTORED.
        val resultState = runnerStateSnapshot
        report("=== PATH ${if (manualEdit) "A" else "B"} END state=$resultState ===")
        assertTrue(
            "next organizer start must leave a terminal or preview state: $nextStart",
            nextStart !is ManualOrganizationRun.State.Applying,
        )
    }

    private var runnerStateSnapshot: ManualOrganizationRun.State = ManualOrganizationRun.State.Idle

    private fun runStart(runner: ManualOrganizationRun): ManualOrganizationRun.State {
        runner.start()
        runnerStateSnapshot = runner.state
        return runner.state
    }

    private fun adapter(): LauncherLayoutAdapter = LauncherLayoutAdapter(
        context,
        launcher.model.modelDbController,
        launcher.model,
    )

    /**
     * Fetches the model-owned ItemInfo on the model executor and issues the
     * same `ModelWriter.moveItemInDatabase` call the workspace drop path uses.
     */
    private fun moveRowToDesktopViaWriter(rowId: Long, target: Pair<Int, Int>) {
        val info = checkNotNull(modelItemOf(rowId)) { "model has no ItemInfo for row $rowId" }
        val writer = launcher.model.getWriter(
            /* verifyChanges = */ false,
            CellPosMapper.DEFAULT,
            /* owner = */ null,
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer.moveItemInDatabase(info, Favorites.CONTAINER_DESKTOP, 0, target.first, target.second)
        }
        val db = launcher.model.modelDbController.db
        val deadline = System.currentTimeMillis() + 10_000L
        var container = -1
        do {
            db.rawQuery(
                "SELECT ${Favorites.CONTAINER} FROM ${Favorites.TABLE_NAME} WHERE ${Favorites._ID}=?",
                arrayOf(rowId.toString()),
            ).use { c ->
                container = if (c.moveToFirst()) c.getInt(0) else -1
            }
            if (container == Favorites.CONTAINER_DESKTOP) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        check(container == Favorites.CONTAINER_DESKTOP) { "manual move never reached the DB ($container)" }
        assertEquals(1, info.spanX)
        assertEquals(1, info.spanY)
        assertEquals(1, querySpan(rowId).first)
        assertEquals(1, querySpan(rowId).second)
        report("MANUAL_MOVE_APPLIED row=$rowId target=$target")
    }

    private fun moveRowToHotseatViaWriter(rowId: Long, slot: Int) {
        val info = checkNotNull(modelItemOf(rowId)) { "model has no ItemInfo for row $rowId" }
        val writer = launcher.model.getWriter(false, CellPosMapper.DEFAULT, null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer.moveItemInDatabase(info, Favorites.CONTAINER_HOTSEAT, 0, slot, 0)
        }
        val deadline = System.currentTimeMillis() + 10_000L
        var container = -1
        do {
            launcher.model.modelDbController.db.rawQuery(
                "SELECT ${Favorites.CONTAINER} FROM ${Favorites.TABLE_NAME} WHERE ${Favorites._ID}=?",
                arrayOf(rowId.toString()),
            ).use { c ->
                container = if (c.moveToFirst()) c.getInt(0) else -1
            }
            if (container == Favorites.CONTAINER_HOTSEAT) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        check(container == Favorites.CONTAINER_HOTSEAT) {
            "hotseat move never reached the DB ($container)"
        }
        // The loader already exposes the icon as 1x1 in memory, while this
        // legacy DB row remains 2x2 until the desktop-entry transition.
        assertEquals(1, info.spanX)
        assertEquals(1, info.spanY)
        report("HOTSEAT_MOVE_APPLIED row=$rowId slot=$slot")
    }

    private fun setRawSpan(rowId: Long, spanX: Int, spanY: Int) {
        val values = ContentValues().apply {
            put(Favorites.SPANX, spanX)
            put(Favorites.SPANY, spanY)
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

    private fun querySpan(rowId: Long): Pair<Int?, Int?> {
        launcher.model.modelDbController.db.rawQuery(
            "SELECT ${Favorites.SPANX}, ${Favorites.SPANY} FROM ${Favorites.TABLE_NAME} " +
                "WHERE ${Favorites._ID}=?",
            arrayOf(rowId.toString()),
        ).use { c ->
            check(c.moveToFirst()) { "row $rowId not found" }
            return (if (c.isNull(0)) null else c.getInt(0)) to
                (if (c.isNull(1)) null else c.getInt(1))
        }
    }

    private fun modelItemOf(rowId: Long): ItemInfo? {
        val latch = CountDownLatch(1)
        var found: ItemInfo? = null
        Executors.MODEL_EXECUTOR.execute {
            try {
                val modelField = LauncherModel::class.java.getDeclaredField("mBgDataModel")
                modelField.isAccessible = true
                val bgDataModel = modelField.get(launcher.model)
                val items = bgDataModel.javaClass.getField("itemsIdMap").get(bgDataModel)
                    as com.android.launcher3.util.IntSparseArrayMap<*>
                found = items.get(rowId.toInt()) as? ItemInfo
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "model item lookup failed", t)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "model item lookup timed out" }
        return found
    }

    /** First child (by rank) of the first folder row currently in favorites. */
    private fun pickFolderChildRowId(): Long {
        launcher.model.modelDbController.db.rawQuery(
            "SELECT child.${Favorites._ID} FROM ${Favorites.TABLE_NAME} AS child " +
                "JOIN ${Favorites.TABLE_NAME} AS folder ON child.${Favorites.CONTAINER} = folder.${Favorites._ID} " +
                "WHERE folder.${Favorites.ITEM_TYPE} = ${Favorites.ITEM_TYPE_FOLDER} " +
                "ORDER BY folder.${Favorites._ID} DESC, child.${Favorites.RANK} DESC LIMIT 1",
            emptyArray(),
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else -1L
        }
    }

    /** First free desktop cell on screen 0 below the QSB row. */
    private fun findFreeDesktopCell(): Pair<Int, Int> {
        val occupied = mutableSetOf<Pair<Int, Int>>()
        launcher.model.modelDbController.db.rawQuery(
            "SELECT ${Favorites.CELLX}, ${Favorites.CELLY} FROM ${Favorites.TABLE_NAME} " +
                "WHERE ${Favorites.CONTAINER} = ${Favorites.CONTAINER_DESKTOP} " +
                "AND ${Favorites.SCREEN} = 0 AND ${Favorites.CELLX} IS NOT NULL AND ${Favorites.CELLY} IS NOT NULL",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) occupied += c.getInt(0) to c.getInt(1)
        }
        for (y in 1..3) {
            for (x in 0..4) {
                if (x to y !in occupied) return x to y
            }
        }
        error("no free desktop cell on screen 0 (occupied=$occupied)")
    }

    private fun readLatestRecoveryRecord(): String {
        val dbFile = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        if (!dbFile.exists()) return "NO_DB"
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return db.use { readable ->
            readable.rawQuery(
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

    private fun report(line: String) {
        android.util.Log.i(TAG, line)
    }

    private fun dumpRawRows(stage: String) {
        launcher.model.modelDbController.db.rawQuery(
            "SELECT _id, screen, cellX, cellY, spanX, spanY, container, itemType, rank, title FROM ${Favorites.TABLE_NAME} ORDER BY _id",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val parts = (0 until c.columnCount).map { index -> "${c.columnNames[index]}=${c.getString(index)}" }
                android.util.Log.i(TAG, "$stage ROW ${parts.joinToString(" ")}")
            }
        }
    }

    private fun awaitModelLoaded() {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!launcher.model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not reload" }
    }

    private fun awaitGateReady() {
        val appModule = (context.applicationContext as LawnchairApp).layoutApplicationModule
        val gateDeadline = System.currentTimeMillis() + 60_000L
        while (appModule.readinessGate.state != ReadinessGate.State.READY && System.currentTimeMillis() < gateDeadline) {
            Thread.sleep(250)
        }
        check(appModule.readinessGate.state == ReadinessGate.State.READY) {
            "startup reconciliation not READY: ${appModule.readinessGate.state}"
        }
    }

    /**
     * Deterministic layout: one folder with three children plus three
     * standalone items, all pointing at the launcher itself so classification
     * is stable. Standalone items sit away from the QSB reservation row.
     */
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
        dumpRawRows("SEEDED")
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

    private fun deleteRecoveryArtifacts() {
        val dbDir = context.getDatabasePath(
            app.lawnchair.organizer.application.store.RecoveryDbSchema.FILE_NAME,
        ).parentFile ?: return
        dbDir.listFiles()?.filter { it.name.startsWith("organizer_recovery") }?.forEach { it.delete() }
        java.io.File(
            context.noBackupFilesDir,
            app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).takeIf { it.exists() }?.deleteRecursively()
    }

    private companion object {
        const val TAG = "Issue265Repro"
    }
}
