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
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairApp
import app.lawnchair.backup.LawnchairBackup
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed new-target backup/restore entrypoint (DRR-RED-004 / AC-006).
 *
 * Runs against the retirement APK after upgrade. Each method preflights the
 * installed package identity, emits `NEW_TYPED_READY typed=true`, performs
 * exactly one action, and emits a typed completion marker with canonical
 * digests. The host script drives the ordering; this class never performs
 * more than its named action per invocation.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementBackupRestoreInstrumentationTest {

    /** Inserts a distinguishable synthetic favorites row (distinct layout). */
    @Test
    fun createDistinctLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightNewTarget(context)
        println("NEW_TYPED_READY typed=true")

        val digestBefore = canonicalFavoritesDigest(context)
        val offsetX = nonce.first().digitToInt(16) % 4
        val offsetY = nonce.last().digitToInt(16) % 4
        insertSyntheticRow(context, cellX = 3 + offsetX, cellY = 3 + offsetY, title = "DeckRetirementDistinct.$nonce")
        val digestAfter = canonicalFavoritesDigest(context)
        assertNotEquals(
            "Distinct layout must change the canonical digest",
            digestBefore,
            digestAfter,
        )

        val line = "DISTINCT_LAYOUT nonce=$nonce digest=$digestAfter typed=true"
        Log.i(TAG, line)
        println(line)
    }

    /** Restores the nonce archive and captures the post-restore digest. */
    @Test
    fun restoreAndCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightNewTarget(context)
        val expectedArchiveDigest = InstrumentationRegistry.getArguments()
            .getString("expected_archive_digest")
        println("NEW_TYPED_READY typed=true")

        val backupFile = backupFileFor(context, nonce)
        assertTrue("Backup archive must exist at $backupFile", backupFile.exists())
        val uri = LawnchairApp.getUriForFile(context, backupFile)

        val backup = LawnchairBackup(context, uri)
        runBlocking {
            backup.readInfoAndPreview()
        }
        val infoInitialized = try {
            backup.info
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
        assertTrue("Backup info must be readable", infoInitialized)

        val preRestoreDigest = canonicalFavoritesDigest(context)

        runBlocking {
            backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
        }

        val restoredDigest = canonicalFavoritesDigest(context)
        if (expectedArchiveDigest != null && expectedArchiveDigest != "NO_DB") {
            assertEquals(
                "Post-restore canonical digest must equal the pre-archive digest",
                expectedArchiveDigest,
                restoredDigest,
            )
        }

        val line = "RESTORE_PERFORMED nonce=$nonce preRestoreDigest=$preRestoreDigest " +
            "restoredDigest=$restoredDigest typed=true"
        Log.i(TAG, line)
        println(line)
    }

    /**
     * Post-restart capture: proves the restored database stays current and
     * both Deck tombstones normalized to false together after the next
     * startup, with unrelated swipe-up/add-icon values still readable.
     */
    @Test
    fun captureAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightNewTarget(context)
        println("NEW_TYPED_READY typed=true")

        val normalized = runBlocking {
            PreferenceManager2.getInstance(context).areDeckTombstonesNormalized()
        }
        assertTrue(
            "Both Deck tombstones must read false together after restart",
            normalized,
        )

        val swipeUp = PreferenceManager2.getInstance(context).swipeUpGestureHandler
        val swipeUpValue = runBlocking { swipeUp.get().first() }
        val addIconToHome = PreferenceManager.getInstance(context).addIconToHome.get()
        val digest = canonicalFavoritesDigest(context)
        assertTrue("Active grid database must exist after restart", digest != "NO_DB")

        val line = "RESTART_CAPTURE nonce=$nonce tombstones=false swipe_up=$swipeUpValue " +
            "add_icon_to_home=$addIconToHome digest=$digest typed=true"
        Log.i(TAG, line)
        println(line)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Verifies installed package identity and returns the nonce argument. */
    private fun preflightNewTarget(context: Context): String {
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce argument")
        val expectedVersionCode = args.getString("expected_target_version_code")
            ?: error("Missing expected_target_version_code argument")
        val expectedVersionName = args.getString("expected_target_version_name")
        val expectedApkPath = args.getString("expected_target_apk_path")

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(
            "Installed version code must match the expected new target",
            expectedVersionCode.toLong(),
            packageInfo.longVersionCode,
        )
        if (expectedVersionName != null) {
            assertEquals("Version name mismatch", expectedVersionName, packageInfo.versionName)
        }
        if (expectedApkPath != null) {
            assertEquals(
                "Installed APK path must match the expected new target",
                expectedApkPath,
                packageInfo.applicationInfo!!.sourceDir,
            )
        }
        return nonce
    }

    private fun backupFileFor(context: Context, nonce: String): File {
        return File(
            context.filesDir.parentFile!!
                .resolve("cache/logs/deck-retirement-backup"),
            "$nonce.lawnchairbackup",
        )
    }

    private fun insertSyntheticRow(context: Context, cellX: Int, cellY: Int, title: String) {
        val dbFile = InvariantDeviceProfile.INSTANCE.get(context).dbFile
        val dbPath = context.getDatabasePath(dbFile)
        assertTrue("Active grid database must exist before mutation", dbPath.exists())
        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.delete("favorites", "_id = ?", arrayOf(SYNTHETIC_ROW_ID.toString()))
            val values = ContentValues().apply {
                put("_id", SYNTHETIC_ROW_ID)
                put("title", title)
                put("itemType", 5) // FOLDER
                put("container", -100) // CONTAINER_DESKTOP
                put("screen", 0)
                put("cellX", cellX)
                put("cellY", cellY)
                put("spanX", 1)
                put("spanY", 1)
            }
            db.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }

    /** Canonical ordered favorites digest over the active grid database. */
    private fun canonicalFavoritesDigest(context: Context): String {
        val dbFile = InvariantDeviceProfile.INSTANCE.get(context).dbFile
        val dbPath = context.getDatabasePath(dbFile)
        if (!dbPath.exists()) return "NO_DB"

        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
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

    private companion object {
        private const val TAG = "DeckRetirementBackupRestore"
        private const val SYNTHETIC_ROW_ID = -9999L
    }
}
