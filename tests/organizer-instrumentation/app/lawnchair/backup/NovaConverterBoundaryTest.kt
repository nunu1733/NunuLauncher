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

import com.android.launcher3.LauncherSettings.Favorites
import org.junit.Test

/**
 * Issue #532 rebase Phase 2 / G4 T8 added oracle, smartspace ON (#522
 * assessment §test表): with the smartspace conflict toggle consumed as ON,
 * the converter shifts every desktop cellY by +1, commits rows+1, and
 * compensates the clamp/skip bounds — the #522 adopted smartspace port that
 * must not regress into a warning-only toggle.
 *
 * One restore per process (#299 contract); the OFF state is a separate
 * class (NovaConverterBoundarySmartspaceOffTest) with its own invocation.
 */
class NovaConverterBoundaryTest : NovaConverterBoundaryScenarioBase() {

    override val smartspaceEnabled = true

    @Test
    fun parseInfoFlagsSubgridWarningDriverAndParsesNormalGrid() {
        assertParseInfoFlagsSubgrid()
        assertParseInfoNormalGrid(buildBoundaryZip())
    }

    @Test
    fun converterShiftsCellYCompensatesRowsAndAppliesClampAndSkip() {
        restoreBoundaryBackup(buildBoundaryZip())

        // Kept desktop rows: rounded, then +1 smartspace shift on cellY.
        assertConvertedRow(
            id = 1, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 0, cellY = 2, spanX = 1, spanY = 1,
        )
        assertConvertedRow(
            id = 2, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 2, cellY = 3, spanX = 3, spanY = 3,
        )
        // Oversized spans clamp and survive.
        assertConvertedRow(
            id = 5, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 0, cellY = 3, spanX = 5, spanY = 2,
        )
        // Hotseat rounds the fractional slot and never shifts.
        assertConvertedRow(
            id = 6, container = Favorites.CONTAINER_HOTSEAT,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 1, cellX = 1, cellY = 0, spanX = 1, spanY = 1, rank = 1,
        )
        // Folder keeps its desktop placement shifted; the child does not
        // shift and carries the screen/cellX/cellY rank.
        assertConvertedRow(
            id = 8, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_FOLDER,
            screen = 0, cellX = 1, cellY = 3, spanX = 1, spanY = 1,
        )
        assertConvertedRow(
            id = 9, container = 8, itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 1, cellY = 0, spanX = 1, spanY = 1, rank = 1,
        )
        // Second desktop page keeps its screen id.
        assertConvertedRow(
            id = 10, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 1, cellX = 0, cellY = 1, spanX = 1, spanY = 1,
        )

        // Skipped: column overflow, row-bound overflow, hotseat overflow.
        assertSkippedRow(id = 3)
        assertSkippedRow(id = 4)
        assertSkippedRow(id = 7)

        // The smartspace compensation commits rows+1 and binds the IDP.
        assertCommittedGrid()
    }
}
