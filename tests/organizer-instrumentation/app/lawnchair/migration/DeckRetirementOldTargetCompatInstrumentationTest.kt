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
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reflection-based old-target compatibility entrypoint (DRR-RED-004).
 *
 * Compiled against the retirement source tree but designed to run against the
 * pre-retirement (old) binary through the deck-retirement smoke host scripts.
 * Every reference to a removed Deck surface (PreferenceManager2.deckLayout /
 * showDeckLayout fields) and to the opto extension functions is resolved by
 * reflection with string names, so this class never statically references
 * removed or new-only migration types. Baseline APIs that exist in both
 * binaries (LawnchairBackup, InvariantDeviceProfile, the old
 * PreferenceManager) are used directly.
 *
 * Each method performs its non-mutating preflight first, emits
 * `OLD_COMPAT_READY typed=true` immediately before its single allowed
 * mutation, and then emits a typed completion marker. The host script never
 * calls the same action twice.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementOldTargetCompatInstrumentationTest {

    /** Seeds both Deck preferences to true; no backup is created. */
    @Test
    fun seedDeckEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        reflectivelySetDeckPreference(context, "deckLayout", true)
        reflectivelySetDeckPreference(context, "showDeckLayout", true)

        emitCapture(context, "SEED_DECK_ENABLED")
    }

    /** Captures preference and layout state without mutating anything. */
    @Test
    fun captureOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        emitCapture(context, "CAPTURE")
    }

    /** Seeds Deck-enabled state, then captures the resulting state. */
    @Test
    fun seedAndCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        reflectivelySetDeckPreference(context, "deckLayout", true)
        reflectivelySetDeckPreference(context, "showDeckLayout", true)

        emitCapture(context, "SEED_AND_CAPTURE")
    }

    /**
     * Seeds Deck-enabled state, then creates a real Lawnchair backup archive
     * at `cache/logs/deck-retirement-backup/<nonce>.lawnchairbackup`. The
     * pre-archive canonical layout digest is emitted so the new-target oracle
     * can compare the post-restore digest against it.
     */
    @Test
    fun seedAndCreateBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        reflectivelySetDeckPreference(context, "deckLayout", true)
        reflectivelySetDeckPreference(context, "showDeckLayout", true)

        val preArchiveDigest = canonicalFavoritesDigest(context)
        println("PRE_ARCHIVE_DIGEST nonce=$nonce digest=$preArchiveDigest typed=true")

        val backupDir = context.filesDir.parentFile!!
            .resolve("cache/logs/deck-retirement-backup")
        backupDir.mkdirs()
        val backupFile = File(backupDir, "$nonce.lawnchairbackup")
        val uri = LawnchairApp.getUriForFile(context, backupFile)

        runBlocking {
            val screenshot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            LawnchairBackup.create(
                context,
                LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS,
                screenshot,
                uri,
            )
        }
        assertTrue("Backup archive must exist after create", backupFile.exists())

        Log.i(TAG, "BACKUP_CREATED nonce=$nonce typed=true")
        println("BACKUP_CREATED nonce=$nonce typed=true")
    }

    /** Inserts a distinguishable synthetic favorites row to mutate the layout. */
    @Test
    fun mutateLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        val dbFile = com.android.launcher3.InvariantDeviceProfile.INSTANCE.get(context).dbFile
        val dbPath = context.getDatabasePath(dbFile)
        assertTrue("Active grid database must exist before mutation", dbPath.exists())
        val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.delete(
                "favorites",
                "_id = ?",
                arrayOf(SYNTHETIC_ROW_ID.toString()),
            )
            val offsetX = nonce.first().digitToInt(16) % 4
            val offsetY = nonce.last().digitToInt(16) % 4
            val values = ContentValues().apply {
                put("_id", SYNTHETIC_ROW_ID)
                put("title", "DeckRetirementMutatedRow.$nonce")
                put("itemType", 5) // FOLDER
                put("container", -100) // CONTAINER_DESKTOP
                put("screen", 0)
                put("cellX", 4 + offsetX)
                put("cellY", 4 + offsetY)
                put("spanX", 1)
                put("spanY", 1)
            }
            db.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }

        emitCapture(context, "MUTATED")
    }

    /** Restores the nonce archive through the real LawnchairBackup restore path. */
    @Test
    fun restoreBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = preflightOldTarget(context)
        println("OLD_COMPAT_READY typed=true")

        val backupFile = File(
            context.filesDir.parentFile!!
                .resolve("cache/logs/deck-retirement-backup"),
            "$nonce.lawnchairbackup",
        )
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

        runBlocking {
            backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
        }

        Log.i(TAG, "OLD_RESTORED nonce=$nonce typed=true")
        println("OLD_RESTORED nonce=$nonce typed=true")
    }

    // ------------------------------------------------------------------
    // Preflight and capture helpers
    // ------------------------------------------------------------------

    /** Verifies installed package identity and returns the nonce argument. */
    private fun preflightOldTarget(context: Context): String {
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce argument")
        val expectedVersionCode = args.getString("expected_target_version_code")
            ?: error("Missing expected_target_version_code argument")
        val expectedVersionName = args.getString("expected_target_version_name")
        val expectedApkPath = args.getString("expected_target_apk_path")

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(
            "Installed version code must match the expected old target",
            expectedVersionCode.toLong(),
            packageInfo.longVersionCode,
        )
        if (expectedVersionName != null) {
            assertEquals("Version name mismatch", expectedVersionName, packageInfo.versionName)
        }
        if (expectedApkPath != null) {
            assertEquals(
                "Installed APK path must match the expected old target",
                expectedApkPath,
                packageInfo.applicationInfo!!.sourceDir,
            )
        }
        return nonce
    }

    /** Emits a typed capture marker with preference and layout evidence. */
    private fun emitCapture(context: Context, action: String) {
        val nonce = InstrumentationRegistry.getArguments()
            .getString("deck_retirement_nonce") ?: "none"
        val deckLayout = reflectivelyReadDeckPreference(context, "deckLayout")
        val showDeckLayout = reflectivelyReadDeckPreference(context, "showDeckLayout")
        val swipeUp = readSwipeUpGesture(context)
        val addIcon = readAddIconToHome(context)
        val digest = canonicalFavoritesDigest(context)
        val line = "CAPTURED action=$action nonce=$nonce enable_lawn_deck=$deckLayout " +
            "show_deck_layout=$showDeckLayout swipe_up=$swipeUp add_icon_to_home=$addIcon " +
            "digest=$digest typed=true"
        Log.i(TAG, line)
        println(line)
    }

    private fun readSwipeUpGesture(context: Context): String {
        return try {
            val pm2Class = Class.forName("app.lawnchair.preferences2.PreferenceManager2")
            val getInstance = pm2Class.getMethod("getInstance", Context::class.java)
            val pm2 = getInstance.invoke(null, context)
            val field = pm2Class.getDeclaredField("swipeUpGestureHandler")
            field.isAccessible = true
            val pref = field.get(pm2)
            val preferenceClass = Class.forName("com.patrykmichalik.opto.domain.Preference")
            val extensions = Class.forName("com.patrykmichalik.opto.core.PreferenceExtensionsKt")
            val firstBlocking = extensions.getMethod("firstBlocking", preferenceClass)
            firstBlocking.invoke(null, pref)?.toString() ?: "unknown"
        } catch (e: Exception) {
            "reflection-error"
        }
    }

    private fun readAddIconToHome(context: Context): Boolean {
        return try {
            app.lawnchair.preferences.PreferenceManager
                .getInstance(context).addIconToHome.get()
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Reflection helpers — old field names exist only on the pre-retirement
    // binary; firstBlocking/setBlocking are static extensions on the opto
    // PreferenceExtensionsKt. No new migration types load here.
    // ------------------------------------------------------------------

    private fun reflectivelySetDeckPreference(context: Context, fieldName: String, value: Boolean) {
        val pm2Class = Class.forName("app.lawnchair.preferences2.PreferenceManager2")
        val getInstance = pm2Class.getMethod("getInstance", Context::class.java)
        val pm2 = getInstance.invoke(null, context)
        val field = pm2Class.getDeclaredField(fieldName)
        field.isAccessible = true
        val pref = field.get(pm2)

        val preferenceClass = Class.forName("com.patrykmichalik.opto.domain.Preference")
        val extensions = Class.forName("com.patrykmichalik.opto.core.PreferenceExtensionsKt")
        val setBlocking = extensions.getMethod("setBlocking", preferenceClass, Any::class.java)
        setBlocking.invoke(null, pref, value as Any)
    }

    private fun reflectivelyReadDeckPreference(context: Context, fieldName: String): Boolean {
        return try {
            val pm2Class = Class.forName("app.lawnchair.preferences2.PreferenceManager2")
            val getInstance = pm2Class.getMethod("getInstance", Context::class.java)
            val pm2 = getInstance.invoke(null, context)
            val field = pm2Class.getDeclaredField(fieldName)
            field.isAccessible = true
            val pref = field.get(pm2)

            val preferenceClass = Class.forName("com.patrykmichalik.opto.domain.Preference")
            val extensions = Class.forName("com.patrykmichalik.opto.core.PreferenceExtensionsKt")
            val firstBlocking = extensions.getMethod("firstBlocking", preferenceClass)
            firstBlocking.invoke(null, pref) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read deck preference $fieldName reflectively", e)
            false
        }
    }

    /** Canonical ordered favorites digest over the active grid database. */
    private fun canonicalFavoritesDigest(context: Context): String {
        val dbFile = com.android.launcher3.InvariantDeviceProfile.INSTANCE.get(context).dbFile
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
        private const val TAG = "DeckRetirementOldCompat"
        private const val SYNTHETIC_ROW_ID = -9998L
    }
}
