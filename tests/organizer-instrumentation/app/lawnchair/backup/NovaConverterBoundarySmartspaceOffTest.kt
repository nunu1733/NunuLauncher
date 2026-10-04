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
 * Issue #532 rebase Phase 2 / G4 T8 added oracle, smartspace OFF (#522
 * assessment §test表): with the smartspace conflict toggle consumed as OFF,
 * the converter keeps the raw rounded cellY and commits the nova rows
 * unchanged — the OFF half of the #522 adopted pair, proving the toggle
 * really reaches the data conversion instead of only the warning UI.
 *
 * One restore per process (#299 contract); the ON state is a separate
 * class (NovaConverterBoundaryTest) with its own invocation.
 */
class NovaConverterBoundarySmartspaceOffTest : NovaConverterBoundaryScenarioBase() {

    override val smartspaceEnabled = false

    @Test
    fun converterKeepsRoundedCellYAndNovaRowsWhenSmartspaceOff() {
        restoreBoundaryBackup(buildBoundaryZip())

        // Kept desktop rows: rounded positions without the smartspace shift.
        assertConvertedRow(
            id = 1, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 0, cellY = 1, spanX = 1, spanY = 1,
        )
        assertConvertedRow(
            id = 2, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 2, cellY = 2, spanX = 3, spanY = 3,
        )
        // Oversized spans clamp and survive.
        assertConvertedRow(
            id = 5, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 0, cellY = 2, spanX = 5, spanY = 2,
        )
        // Hotseat rounds the fractional slot (identical to the ON state).
        assertConvertedRow(
            id = 6, container = Favorites.CONTAINER_HOTSEAT,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 1, cellX = 1, cellY = 0, spanX = 1, spanY = 1, rank = 1,
        )
        // Folder and child: identical ranks, no shift.
        assertConvertedRow(
            id = 8, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_FOLDER,
            screen = 0, cellX = 1, cellY = 2, spanX = 1, spanY = 1,
        )
        assertConvertedRow(
            id = 9, container = 8, itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 0, cellX = 1, cellY = 0, spanX = 1, spanY = 1, rank = 1,
        )
        // Second desktop page keeps its screen id.
        assertConvertedRow(
            id = 10, container = Favorites.CONTAINER_DESKTOP,
            itemType = Favorites.ITEM_TYPE_APPLICATION,
            screen = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1,
        )

        // Skipped: column overflow, row-bound overflow, hotseat overflow.
        assertSkippedRow(id = 3)
        assertSkippedRow(id = 4)
        assertSkippedRow(id = 7)

        // The nova rows are committed unchanged when smartspace is off.
        assertCommittedGrid()
    }
}
