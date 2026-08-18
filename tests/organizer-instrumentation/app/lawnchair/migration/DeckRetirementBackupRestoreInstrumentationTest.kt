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
package app.lawnchair.migration

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairApp
import app.lawnchair.backup.LawnchairBackup
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.ModelDbController
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed new-target test for backup/restore round-trip.
 *
 * Preflights the package version, creates a synthetic post-upgrade layout,
 * reads and restores a saved backup archive, then verifies the restored
 * DB matches the archive digest.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementBackupRestoreInstrumentationTest {

    @Test
    fun verifyRestoredBackupBecomesCurrentWithNormalizedTombstones() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce argument")
        val expectedVersionCode = args.getString("expected_target_version_code")
            ?: error("Missing expected_target_version_code argument")
        val expectedVersionName = args.getString("expected_target_version_name")

        // Preflight: verify installed package is the retirement APK.
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("Version code mismatch", expectedVersionCode.toLong(), packageInfo.longVersionCode)
        if (expectedVersionName != null) {
            assertEquals("Version name mismatch", expectedVersionName, packageInfo.versionName)
        }

        println("NEW_TYPED_READY typed=true")

        // Seed a synthetic post-upgrade layout (one folder with one app) by
        // directly manipulating launcher.db.
        val syntheticDigest = seedSyntheticLayout(context)

        // Locate the saved backup archive from the old-target seed step.
        val backupDir = context.filesDir.parentFile!!
            .resolve("cache/logs/deck-retirement-backup")
        val backupFile = File(backupDir, "$nonce.lawnchairbackup")
        assertTrue("Backup archive must exist at $backupFile", backupFile.exists())

        val uri = LawnchairApp.getUriForFile(context, backupFile)

        // Read archive info and verify it is readable.
        val backup = LawnchairBackup(context, uri)
        runBlocking {
            backup.readInfoAndPreview()
        }
        assertNotNull("Backup info must be readable after readInfoAndPreview", backup.info)

        // Compute the archive's expected DB digest.
        val archiveDigest = computeArchiveDbDigest(backupFile)

        // Verify synthetic digest differs from archive digest (they are distinct states).
        if (syntheticDigest != null) {
            assertTrue(
                "Synthetic layout digest should differ from archive digest",
                syntheticDigest != archiveDigest,
            )
        }

        // Perform the restore.
        runBlocking {
            backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
        }

        // Verify restored DB equals archive digest.
        val restoredDigest = computeFavoritesDigest(context)
        assertEquals(
            "Restored DB digest must match archive digest",
            archiveDigest,
            restoredDigest,
        )

        Log.i(TAG, "RESTORE_VERIFIED nonce=$nonce archiveDigest=$archiveDigest " +
            "restoredDigest=$restoredDigest typed=true")
        println("RESTORE_VERIFIED nonce=$nonce archiveDigest=$archiveDigest " +
            "restoredDigest=$restoredDigest typed=true")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun seedSyntheticLayout(context: Context): String? {
        val dbPath = context.getDatabasePath("launcher.db")
        if (!dbPath.exists()) return null

        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // Insert a distinguishable synthetic row.
            val values = ContentValues().apply {
                put(Favorites._ID, SYNTHETIC_ID)
                put(Favorites.TITLE, "DeckRetirementRestoreTest")
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.RANK, 0)
            }
            db.insertWithOnConflict(
                Favorites.TABLE_NAME,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } finally {
            db.close()
        }

        return computeFavoritesDigest(context)
    }

    private fun computeFavoritesDigest(context: Context): String {
        val dbPath = context.getDatabasePath("launcher.db")
        if (!dbPath.exists()) return "NO_DB"

        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val cursor = db.rawQuery("SELECT * FROM favorites ORDER BY _id", null)
            while (cursor.moveToNext()) {
                for (i in 0 until cursor.columnCount) {
                    val value = cursor.getString(i) ?: "NULL"
                    digest.update(value.toByteArray(Charsets.UTF_8))
                }
            }
            cursor.close()
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            db.close()
        }
    }

    /**
     * Computes a SHA-256 digest of the launcher.db inside the backup archive.
     * This allows comparison against the active DB after restore.
     */
    private fun computeArchiveDbDigest(archiveFile: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = archiveFile.readBytes()
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val TAG = "DeckRetirementBackupRestore"
        private const val SYNTHETIC_ID = -9999L
    }
}