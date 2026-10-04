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
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherFiles
import kotlinx.coroutines.runBlocking

/**
 * Observer notified when the migration reaches a defined phase.
 * Production default is a no-op; androidTest installations may supply
 * a real observer to verify phase ordering.
 */
internal fun interface DeckRetirementPhaseObserver {
    fun onPhaseReached(phase: String)
}

/**
 * Startup migration for ADR-0006 (Deck runtime retirement).
 *
 * Contract:
 * - The active Launcher DB is the sole authority; historical artifact files
 *   are never restore inputs.
 * - Deck tombstone preferences must be normalized to false before any
 *   artifact cleanup runs.
 * - Cleanup failures are deferred to the next process start.
 * - Only exact recognized artifact names (derived from GRID_DB_FILES) are
 *   deleted; no prefix scans, no directory listings, no active DB access.
 */
internal object DeckRetirementMigration {
    const val PHASE_AFTER_NORMALIZATION_BEFORE_CLEANUP: String = "AFTER_NORMALIZATION_BEFORE_CLEANUP"

    /** Production default is a no-op; only androidTest installs a real observer. */
    var phaseObserver: DeckRetirementPhaseObserver = DeckRetirementPhaseObserver {}

    private const val TAG = "DeckRetirementMigration"

    /**
     * Returns the exact recognized historical artifact names derived from the
     * given grid-database basenames. For each basename B, produces:
     *   bk_B, bk_B-journal, lawndeck_B, lawndeck_B-journal
     *
     * This mirrors the retired Deck runtime naming convention (suffix "bk"
     * on enable-path backup, "lawndeck" on disable-path backup; SQLite journal
     * companions are `<name>-journal`).
     */
    fun recognizedArtifactNames(gridDbFiles: List<String>): List<String> {
        return gridDbFiles.flatMap { b ->
            listOf("bk_$b", "bk_$b-journal", "lawndeck_$b", "lawndeck_$b-journal")
        }
    }

    /**
     * Runs the full retirement migration:
     * 1. Normalizes both Deck tombstone preferences to false via [PreferenceManager2].
     * 2. Notifies the [phaseObserver].
     * 3. Deletes all recognized historical artifact files.
     *
     * If normalization fails, cleanup is skipped entirely and will retry on
     * the next process start.
     */
    fun run(context: Context) {
        val prefs2 = PreferenceManager2.getInstance(context)
        val normalized = runCatching {
            runBlocking {
                prefs2.normalizeDeckTombstones()
            }
        }.getOrDefault(false)

        if (!normalized) {
            Log.w(TAG, "Deck tombstone normalization failed — skipping cleanup, will retry on next start")
            return
        }

        phaseObserver.onPhaseReached(PHASE_AFTER_NORMALIZATION_BEFORE_CLEANUP)

        val artifactNames = recognizedArtifactNames(LauncherFiles.GRID_DB_FILES.toList())
        for (name in artifactNames) {
            val file = context.getDatabasePath(name)
            if (file.exists()) {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                if (!deleted) {
                    Log.w(TAG, "Failed to delete artifact: $name")
                }
            }
        }
    }
}
