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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.lawnchairApp
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.model.LayoutWriteCoordinator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #168: one Nova restore must be authoritative and DB cleanup must be
 * restore-safe. Covers the two seams the fix introduces:
 *
 * 1. `InvariantDeviceProfile.applyGridInfo` binds the converted grid values to
 *    the live IDP deterministically (grid mismatch and grid match).
 * 2. `LawnchairApp.cleanUpDatabases` never deletes a `launcher*` file while a
 *    restore-family lease is held or a staged `restored.db` exists, and still
 *    cleans unmatched files on ordinary loads.
 */
@RunWith(AndroidJUnit4::class)
class NovaRestoreGridApplicationTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun databasesDir(): File {
        val context = context()
        val dbName = InvariantDeviceProfile.INSTANCE.get(context).dbFile
        val dir = context.getDatabasePath(dbName).parentFile!!
        dir.mkdirs()
        return dir
    }

    @Test
    fun applyGridInfoBindsConvertedDbNameForMismatchAndMatch() {
        val context = context()
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val original = DeviceProfileOverrides.INSTANCE.get(context).getGridInfo()
        runOnMain {
            // Grid mismatch: values different from the current grid.
            val converted = DeviceProfileOverrides.DBGridInfo(
                numHotseatColumns = original.numHotseatColumns,
                numRows = original.numRows + 1,
                numColumns = original.numColumns + 1,
            )
            idp.applyGridInfo(context, converted)
            assertEquals(converted.dbFile, idp.dbFile)
            assertEquals(converted.numRows, idp.numRows)
            assertEquals(converted.numColumns, idp.numColumns)

            // Grid match: applying identical values again stays stable.
            idp.applyGridInfo(context, converted)
            assertEquals(converted.dbFile, idp.dbFile)

            // applyGridInfo writes no prefs; reapplying the prefs-backed grid
            // leaves the app exactly as it was found.
            idp.applyGridInfo(context, original)
        }
        assertEquals(original.dbFile, idp.dbFile)
    }

    @Test
    fun cleanUpDatabasesSparesFilesWhileRestoreStaged() {
        val app = context().lawnchairApp
        assertFalse(LayoutWriteCoordinator.getInstance().hasActiveRestoreFamilyLease())
        val sibling = File(databasesDir(), "launcher_9_9_9_issue168_staged.db")
        val restored = File(databasesDir(), LawnchairBackup.RESTORED_DB_FILE_NAME)
        val restoredPreExisted = restored.exists()
        sibling.createNewFile()
        if (!restoredPreExisted) restored.createNewFile()
        try {
            app.cleanUpDatabases()
            assertTrue("a staged restored.db must block launcher* deletion", sibling.exists())
        } finally {
            if (!restoredPreExisted) restored.delete()
            sibling.delete()
        }
    }

    @Test
    fun cleanUpDatabasesSparesFilesUnderRestoreFamilyLease() {
        val app = context().lawnchairApp
        val sibling = File(databasesDir(), "launcher_9_9_9_issue168_lease.db")
        sibling.createNewFile()
        val lease = requireNotNull(
            LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE),
        ) { "another lease is currently held; test precondition violated" }
        try {
            assertTrue(LayoutWriteCoordinator.getInstance().hasActiveRestoreFamilyLease())
            app.cleanUpDatabases()
            assertTrue("a restore-family lease must block launcher* deletion", sibling.exists())
        } finally {
            lease.close()
            sibling.delete()
        }
    }

    @Test
    fun cleanUpDatabasesDeletesUnmatchedFilesWithoutRestore() {
        val app = context().lawnchairApp
        assertFalse(LayoutWriteCoordinator.getInstance().hasActiveRestoreFamilyLease())
        assertFalse(
            "precondition: no staged restored.db",
            File(databasesDir(), LawnchairBackup.RESTORED_DB_FILE_NAME).exists(),
        )
        val sibling = File(databasesDir(), "launcher_9_9_9_issue168_delete.db")
        sibling.createNewFile()
        app.cleanUpDatabases()
        assertFalse(
            "unmatched launcher* files must still be cleaned when no restore is in flight",
            sibling.exists(),
        )
    }
}
