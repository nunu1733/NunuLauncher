package app.lawnchair.organizer.application.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for Issue #84's no-create inspection contract. These
 * tests run against the production SQLite adapter, not FakeRecoveryStore.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryStoreInspectionInstrumentationTest {

    private val pointId = RecoveryPointId("22222222222222222222222222222222")

    @Test
    fun missingDatabaseInspectionDoesNotCreateSchemaWalOrRecoveryFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val dbFile = recoveryDatabaseFile(context)
        val store = RecoveryStore(context) { 1_000L }

        val record = store.readRecordForInspection(pointId)
        val tombstone = store.readTombstoneForInspection(pointId)

        assertTrue(record is RecoveryStorePort.InspectionRead.Value && record.value == null)
        assertTrue(tombstone is RecoveryStorePort.InspectionRead.Value && tombstone.value == null)
        assertFalse(dbFile.exists())
        assertFalse(File("${dbFile.absolutePath}-wal").exists())
        assertFalse(File("${dbFile.absolutePath}-shm").exists())
    }

    @Test
    fun invalidExistingDatabaseInspectionReturnsUnavailableWithoutHelperRepairOrWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val dbFile = recoveryDatabaseFile(context)
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("not a sqlite database")
        val sizeBefore = dbFile.length()
        val store = RecoveryStore(context) { 1_000L }

        val record = store.readRecordForInspection(pointId)
        val tombstone = store.readTombstoneForInspection(pointId)

        assertTrue(record is RecoveryStorePort.InspectionRead.Unavailable)
        assertTrue(tombstone is RecoveryStorePort.InspectionRead.Unavailable)
        assertTrue(dbFile.exists())
        assertTrue(dbFile.length() == sizeBefore)
        assertFalse(File("${dbFile.absolutePath}-wal").exists())
        assertFalse(File("${dbFile.absolutePath}-shm").exists())
        deleteRecoveryDatabaseArtifacts(context)
    }

    private fun recoveryDatabaseFile(context: Context): File =
        context.applicationContext.getDatabasePath(RecoveryDbSchema.FILE_NAME)

    private fun deleteRecoveryDatabaseArtifacts(context: Context) {
        val dbFile = recoveryDatabaseFile(context)
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-journal"),
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
        ).forEach { file ->
            if (file.exists()) check(file.delete()) { "Unable to delete ${file.absolutePath}" }
        }
    }
}
