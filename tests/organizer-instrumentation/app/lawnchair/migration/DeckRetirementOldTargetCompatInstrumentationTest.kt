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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reflection-based old-target compatibility test.
 *
 * Compiled against the retirement APK but designed to run against the
 * pre-retirement (old) binary via host smoke scripts.  All references to
 * removed Deck APIs (PreferenceManager2.deckLayout, showDeckLayout, etc.)
 * use reflection with string names so that the test class does not load
 * any new migration types at compile time.
 *
 * The host script installs the old APK plus this test APK, then runs the
 * test methods to seed deck state, create a backup archive, and capture
 * layout digests.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementOldTargetCompatInstrumentationTest {

    @Test
    fun seedDeckEnabledStateAndCreateBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce argument")
        val expectedVersionCode = args.getString("expected_target_version_code")
            ?: error("Missing expected_target_version_code argument")
        val expectedVersionName = args.getString("expected_target_version_name")

        // Preflight: verify installed package version matches expectations.
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("Version code mismatch", expectedVersionCode.toLong(), packageInfo.longVersionCode)
        if (expectedVersionName != null) {
            assertEquals("Version name mismatch", expectedVersionName, packageInfo.versionName)
        }

        // Emit OLD_COMPAT_READY marker.
        println("OLD_COMPAT_READY typed=true")

        // Reflectively enable deckLayout and showDeckLayout preferences.
        reflectivelySetDeckPreference(context, "deckLayout", true)
        reflectivelySetDeckPreference(context, "showDeckLayout", true)

        // Create Lawnchair backup archive at the expected path.
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

        Log.i(TAG, "BACKUP_CREATED nonce=$nonce typed=true")
        println("BACKUP_CREATED nonce=$nonce typed=true")
    }

    @Test
    fun captureLayoutState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce")

        // Reflectively read deck preferences from the old binary.
        val deckLayout = reflectivelyReadDeckPreference(context, "deckLayout")
        val showDeckLayout = reflectivelyReadDeckPreference(context, "showDeckLayout")

        // Compute a SHA-256 digest of the favorites table.
        val digest = computeFavoritesDigest(context)

        Log.i(TAG, "CAPTURE nonce=$nonce enable_lawn_deck=$deckLayout " +
            "show_deck_layout=$showDeckLayout digest=$digest typed=true")
        println("CAPTURE nonce=$nonce enable_lawn_deck=$deckLayout " +
            "show_deck_layout=$showDeckLayout digest=$digest typed=true")
    }

    // ------------------------------------------------------------------
    // Reflection helpers — these reference old field/method names that
    // exist only on the pre-retirement binary.  No new migration types
    // are loaded.
    // ------------------------------------------------------------------

    private fun reflectivelySetDeckPreference(context: Context, fieldName: String, value: Boolean) {
        val pm2Class = Class.forName("app.lawnchair.preferences2.PreferenceManager2")
        val getInstance = pm2Class.getMethod("getInstance", Context::class.java)
        val pm2 = getInstance.invoke(null, context)
        val field = pm2Class.getDeclaredField(fieldName)
        field.isAccessible = true
        val pref = field.get(pm2)
        // Preference<T>.setBlocking(value) is the non-suspend variant.
        val setBlocking = pref.javaClass.getMethod("setBlocking", Any::class.java)
        setBlocking.invoke(pref, value as Any)
    }

    private fun reflectivelyReadDeckPreference(context: Context, fieldName: String): Boolean {
        return try {
            val pm2Class = Class.forName("app.lawnchair.preferences2.PreferenceManager2")
            val getInstance = pm2Class.getMethod("getInstance", Context::class.java)
            val pm2 = getInstance.invoke(null, context)
            val field = pm2Class.getDeclaredField(fieldName)
            field.isAccessible = true
            val pref = field.get(pm2)
            // Preference<T>.firstBlocking() returns the current value.
            val firstBlocking = pref.javaClass.getMethod("firstBlocking")
            (firstBlocking.invoke(pref) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read deck preference $fieldName reflectively", e)
            false
        }
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

    private companion object {
        private const val TAG = "DeckRetirementOldCompat"
    }
}