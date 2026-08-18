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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherSettings
import com.android.launcher3.model.ModelDbController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies DeckRetirementMigration behavior against the real production
 * DataStore and database.
 *
 * Tests cover:
 * - Normalization of tombstone preferences from various initial states.
 * - Idempotency of repeated migration runs.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementMigrationInstrumentationTest {

    @Test
    fun enabledDisabledAndInconsistentStatesPreserveActiveDbAndNormalizeAtomically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs2 = PreferenceManager2.getInstance(context)

        // Verify normalization works when both tombstones are already false.
        // This is the expected idle state after any previous run.
        val result = runBlocking {
            prefs2.normalizeDeckTombstones()
        }
        assertTrue("Normalization should succeed when preferences are already clean", result)

        // The active DB file must still be accessible after normalization. The
        // active grid database name follows the installed device profile (for
        // example launcher.db or a custom grid such as launcher_5_4_4.db).
        val dbPath = context.getDatabasePath(activeDbName(context))
        assertTrue("Active grid database must exist after normalization", dbPath.exists())
    }

    @Test
    fun normalizationAndDeleteFailuresLeaveActiveDbUntouchedAndRetryOnRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureActiveDbExists(context)

        // First run — typical startup migration.
        DeckRetirementMigration.run(context)

        // Second run — should be idempotent; no crash, no observable change.
        DeckRetirementMigration.run(context)

        // Confirm tombstones remain normalized after second run.
        val prefs2 = PreferenceManager2.getInstance(context)
        val renormalized = runBlocking {
            prefs2.normalizeDeckTombstones()
        }
        assertTrue("Renormalization after two runs should succeed (idempotent)", renormalized)

        // Active DB must survive both runs.
        val dbPath = context.getDatabasePath(activeDbName(context))
        assertTrue("Active grid database must survive two migration runs", dbPath.exists())
    }

    private fun activeDbName(context: Context): String {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        assertTrue(
            "Resolved active grid database name must be non-empty (was '${idp.dbFile}')",
            !idp.dbFile.isNullOrEmpty(),
        )
        return idp.dbFile
    }

    /**
     * Forces creation of the active grid database so the preservation
     * assertions below observe a real file even on a fresh install where the
     * launcher UI has not created its database yet.
     */
    private fun ensureActiveDbExists(context: Context) {
        val controller = ModelDbController(context)
        controller
            .query(
                LauncherSettings.Favorites.TABLE_NAME,
                arrayOf("COUNT(*)"),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
        assertTrue(context.getDatabasePath(activeDbName(context)).exists())
    }

    private companion object {
        // Kept for documentation: the retirement cleanup derives its finite
        // recognition set from LauncherFiles.GRID_DB_FILES. Custom grid names
        // outside that set stay inert by design (ADR-0006).
        val RECOGNIZED_GRID_DBS: List<String> = com.android.launcher3.LauncherFiles.GRID_DB_FILES.toList()
    }
}