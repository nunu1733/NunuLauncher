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
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed new-target fixture for the `new_pause` mode downgrade smoke test.
 *
 * This test runs AFTER the [DeckRetirementTestRunner] has installed the
 * phase observer and the migration has completed (including the pause
 * handshake with the host script).  It validates that the handshake
 * completed successfully by checking for the .paused and .ack marker
 * files, then captures the final layout and preference state.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementDowngradeFixtureInstrumentationTest {

    @Test
    fun verifyPauseHandshakeCompleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val nonce = args.getString("deck_retirement_nonce")
            ?: error("Missing deck_retirement_nonce argument")

        val controlDir = context.filesDir.parentFile!!
            .resolve("cache/logs/deck-retirement-control")
        val pausedFile = File(controlDir, "$nonce.paused")
        val ackFile = File(controlDir, "$nonce.ack")

        // The .paused file was written by the observer when the migration
        // reached AFTER_NORMALIZATION_BEFORE_CLEANUP.
        assertTrue("Paused marker file must exist at $pausedFile", pausedFile.exists())
        assertTrue("Paused marker content must be the nonce", nonce == pausedFile.readText())

        // The .ack file was written by the observer after the host wrote
        // the .release file, confirming the handshake completed.
        assertTrue("ACK marker file must exist at $ackFile", ackFile.exists())
        assertTrue("ACK marker content must be the nonce", nonce == ackFile.readText())

        // Capture final state: compute a digest of the favorites table.
        val digest = computeFavoritesDigest(context)
        Log.i(TAG, "PAUSE_VERIFIED nonce=$nonce digest=$digest typed=true")
        println("PAUSE_VERIFIED nonce=$nonce digest=$digest typed=true")
    }

    private fun computeFavoritesDigest(context: Context): String {
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
        private const val TAG = "DeckRetirementDowngradeFixture"
    }
}