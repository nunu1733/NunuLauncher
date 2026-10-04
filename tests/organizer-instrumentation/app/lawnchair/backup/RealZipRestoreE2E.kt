/*
 * Copyright 2026, NunuLauncher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.lawnchair.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.LawnchairProto.GridState
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.application.store.RecoveryStartupStorageClassifier
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.DatabaseHelper
import com.android.launcher3.model.DbDowngradeHelper
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Issue #532 rebase Phase 2 / G4 T5 added oracle (#522 assessment §test表):
 * a real Lawnchair backup ZIP carrying a launcher DB of schema 33 (and of
 * legacy schema 32) is restored through the normal production restore path
 * ([LawnchairBackup.restore]) inside the live app process.
 *
 * Ownership boundary: LawnchairBackupRestoreCriticalSectionTest /
 * RunMutexRestoreSuspensionTest (unit) own the critical-section ordering and
 * mutex concurrency on fakes; RecoveryStartupStorageClassifierTest /
 * RecoveryStartupArtifactsTest own the classifier and artifact-cleanup logic
 * on files only. No existing oracle restores a real ZIP end to end, so the
 * real-archive surface — protobuf info, real SQLite DB bytes (32 and 33),
 * databases-directory wipe, staged restored.db rename, sanitizer commit, the
 * migration_src grid prefs written from BackupInfo, and the cold-start
 * recovery-store classification — was unproven.
 *
 * Failure-mode notes (#522 T5 row): the old-epoch marker row proves the
 * databases wipe actually replaced the previous data (a restore that silently
 * kept the old DB fails here); the sanitizer flag assertions prove
 * performRestore committed rather than a partial copy being counted as
 * success.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class RealZipRestoreE2E : NovaRestoreCaptureTestBase() {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // -----------------------------------------------------------------
    // ZIP fixture construction: a real DatabaseHelper-built launcher DB
    // (schema 33, or downgraded to 32 with the production downgrade
    // recipe) zipped with a real BackupInfo proto.
    // -----------------------------------------------------------------

    private fun buildBackupZip(schemaVersion: Int): File {
        val context = context()
        val fixtureDbName = "t5_zip_fixture_$schemaVersion.db"
        val rowTitles = listOf("t5_zip${schemaVersion}_row_a", "t5_row_b")
        context.deleteDatabase(fixtureDbName)

        DatabaseHelper(context, fixtureDbName, { _ -> 0L }, { }).use { helper ->
            val db = helper.getWritableDatabase()
            seedFavorite(db, id = 1, title = rowTitles[0], screen = 0, lockState = 2)
            seedFavorite(db, id = 2, title = rowTitles[1], screen = 1, lockState = 1)
            if (schemaVersion == 32) {
                // Issue #118: the caller owns the downgrade transaction; the
                // fixture then stands at version 32 exactly like a legacy
                // device backup.
                val schema = context.resources.openRawResource(R.raw.downgrade_schema)
                    .use { it.readBytes() }
                val transaction = SQLiteTransaction(db)
                try {
                    DbDowngradeHelper.parse(schema).onDowngrade(db, 33, 32)
                    transaction.commit()
                } finally {
                    transaction.close()
                }
                db.version = 32
            }
        }
        assertEquals(schemaVersion, openVersion(context, fixtureDbName))

        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val info = BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            .setGridState(
                GridState.newBuilder()
                    .setGridSize(gridSizeString(context))
                    .setHotseatCount(idp.numDatabaseHotseatIcons)
                    .setDeviceType(InvariantDeviceProfile.TYPE_PHONE),
            )
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()

        val zip = File(context.cacheDir, "t5_zip${schemaVersion}_fixture_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
            out.write(info.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry(LawnchairBackup.LAUNCHER_DB_FILE_NAME))
            FileInputStream(context.getDatabasePath(fixtureDbName)).use { it.copyTo(out) }
            out.closeEntry()
        }
        context.deleteDatabase(fixtureDbName)
        return zip
    }

    private fun gridSizeString(context: Context): String {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        return String.format(Locale.ENGLISH, "%d,%d", idp.numColumns, idp.numRows)
    }

    private fun openVersion(context: Context, dbName: String): Int =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { it.version }

    private fun seedFavorite(
        db: SQLiteDatabase,
        id: Long,
        title: String,
        screen: Int,
        lockState: Int,
    ) {
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, title)
            put(Favorites.INTENT, "#Intent;end")
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.SCREEN, screen)
            put(Favorites.CELLX, 0)
            put(Favorites.CELLY, 0)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.PROFILE_ID, 0L)
            put(Favorites.ORGANIZER_LOCK_STATE, lockState)
        }
        assertTrue(db.insertOrThrow(Favorites.TABLE_NAME, null, values) >= 0)
    }

    /** Seeds stale recovery artifacts so the post-restore classification is meaningful. */
    private fun seedStaleRecoveryArtifacts() {
        val context = context()
        val recoveryDb = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        recoveryDb.parentFile?.mkdirs()
        recoveryDb.writeBytes(ByteArray(64))
        val snapshotDir = File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        )
        snapshotDir.mkdirs()
        File(snapshotDir, "t5_stale_snapshot").writeText("stale")
    }

    private fun assertPristineRecoveryStore(label: String) {
        val context = context()
        assertFalse(
            "$label: the databases wipe must remove the organizer recovery DB",
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).exists(),
        )
        // The startup classifier is the cold-start read: it is stateless and
        // runs before any SQLite open, so invoking it on the post-restore file
        // state is exactly what a fresh process would observe (both artifacts
        // absent = Pristine = startup availability READY; a restore that left
        // the inspection snapshot behind would classify SuspiciousAbsence).
        val state = RecoveryStartupStorageClassifier.classify(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME),
            File(
                context.applicationContext.noBackupFilesDir,
                RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
            ),
        )
        assertEquals(
            "$label: cold-start recovery store must classify Pristine",
            RecoveryStartupStorageClassifier.State.Pristine,
            state,
        )
    }

    private fun assertRestoredRowsAndPrefs(expectedTitles: List<String>, expectedLock: Int) {
        val context = context()
        // The pre-restore marker row is the old data epoch: the wipe must have
        // replaced it with the archive content.
        assertEquals(0, countActiveRows(context, OLD_EPOCH_MARKER))
        for (title in expectedTitles) {
            assertEquals("$title must be restored exactly once", 1, countActiveRows(context, title))
        }
        // The sanitizer committed: rows are remapped to the live serial and
        // carry the restored-icon flag (a partial copy that skipped
        // performRestore would leave plain rows behind).
        val serial = launcher.model.modelDbController
            .getSerialNumberForUser(Process.myUserHandle())
        assertEquals(serial, longActiveField(context, expectedTitles[0], Favorites.PROFILE_ID))
        assertTrue(
            (longActiveField(context, expectedTitles[0], Favorites.RESTORED) and
                WorkspaceItemInfo.FLAG_RESTORED_ICON.toLong()) != 0L,
        )
        assertEquals(
            expectedLock,
            intActiveField(context, expectedTitles[0], Favorites.ORGANIZER_LOCK_STATE),
        )

        // The grid prefs are rewritten from the archive's BackupInfo.
        val prefs = LauncherPrefs.getPrefs(context)
        assertEquals(gridSizeString(context), prefs.getString(DeviceGridState.KEY_WORKSPACE_SIZE, null))
        assertEquals(
            InvariantDeviceProfile.INSTANCE.get(context).numDatabaseHotseatIcons,
            prefs.getInt(DeviceGridState.KEY_HOTSEAT_COUNT, -1),
        )
        assertEquals(
            InvariantDeviceProfile.TYPE_PHONE,
            prefs.getInt(DeviceGridState.KEY_DEVICE_TYPE, -1),
        )
    }

    private fun restore(zip: File) {
        val backup = LawnchairBackup(context(), Uri.fromFile(zip))
        runBlocking {
            backup.readInfoAndPreview()
            backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
        }
    }

    private fun insertOldEpochMarker() {
        seedFavorite(
            launcher.model.modelDbController.db,
            id = -5001,
            title = OLD_EPOCH_MARKER,
            screen = 0,
            lockState = 1,
        )
    }

    private fun countActiveRows(context: Context, title: String): Int =
        activeDb(context).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM favorites WHERE ${Favorites.TITLE}=?",
                arrayOf(title),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    private fun longActiveField(context: Context, title: String, column: String): Long =
        activeField(context, title, column) { it.getLong(0) }

    private fun intActiveField(context: Context, title: String, column: String): Int =
        activeField(context, title, column) { it.getInt(0) }

    private fun <T> activeField(
        context: Context,
        title: String,
        column: String,
        read: (Cursor) -> T,
    ): T = activeDb(context).use { db ->
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(column),
            Favorites.TITLE + "=?",
            arrayOf(title),
            null,
            null,
            null,
        ).use { cursor ->
            assertTrue("row with title $title must exist", cursor.moveToFirst())
            read(cursor)
        }
    }

    private fun activeDb(context: Context): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(InvariantDeviceProfile.INSTANCE.get(context).dbFile).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    /** Defensive cleanup in case a restore aborted before its wipe. */
    @After
    fun tearDownRecoveryArtifacts() {
        val context = context()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).deleteRecursively()
    }

    // -----------------------------------------------------------------
    // Scenarios. One restore per test; tests are ordered so the legacy
    // archive runs before the current-schema archive.
    // -----------------------------------------------------------------

    @Test
    fun restoreZip32LegacyDbUpgradesRowsAndReplacesOldEpoch() {
        val zip = buildBackupZip(schemaVersion = 32)
        insertOldEpochMarker()
        seedStaleRecoveryArtifacts()
        restore(zip)
        assertRestoredRowsAndPrefs(
            expectedTitles = listOf("t5_zip32_row_a", "t5_row_b"),
            // The 32->33 upgrade marks legacy rows UNKNOWN through the restore.
            expectedLock = 0,
        )
        assertPristineRecoveryStore("zip32")
        forceReloadAndAwaitBarrier("t5 zip32 settle")
    }

    @Test
    fun restoreZip33PreservesRowsLockAndReturnsPristineRecovery() {
        val zip = buildBackupZip(schemaVersion = 33)
        insertOldEpochMarker()
        seedStaleRecoveryArtifacts()
        restore(zip)
        assertRestoredRowsAndPrefs(
            expectedTitles = listOf("t5_zip33_row_a", "t5_row_b"),
            // Schema-33 lock state must survive the restore untouched.
            expectedLock = 2,
        )
        assertPristineRecoveryStore("zip33")
        forceReloadAndAwaitBarrier("t5 zip33 settle")
    }

    companion object {
        private const val OLD_EPOCH_MARKER = "t5_old_epoch_marker"
    }
}
