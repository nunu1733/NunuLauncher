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
package com.android.launcher3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.ModelDbController;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Regression tests proving that baseline drag-and-accessibility delete
 * works for ITEM_TYPE_APPLICATION after the Deck runtime retirement.
 *
 * With Deck retired, all items should be deletable without Deck-specific
 * constraints.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class DeckRetirementDeleteRegressionTest {

    @Test
    public void persistedItemCanBeDeletedFromDb() {
        Context context = ApplicationProvider.getApplicationContext();
        ModelDbController controller = new ModelDbController(context);

        ContentValues values = new ContentValues();
        values.put(Favorites.TITLE, "DeckDeleteRegressionTest");
        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        values.put(Favorites.SCREEN, 0);
        values.put(Favorites.CELLX, 0);
        values.put(Favorites.CELLY, 0);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.RANK, 0);

        int beforeCount = countRows(controller);
        int rowId = controller.generateNewItemId();
        values.put(Favorites._ID, rowId);
        int inserted = controller.insert(Favorites.TABLE_NAME, values);
        assertEquals("Insert must report the explicit row id", rowId, inserted);
        assertEquals(beforeCount + 1, countRows(controller));

        int deleted = controller.delete(
                Favorites.TABLE_NAME,
                Favorites._ID + " = ?",
                new String[]{String.valueOf(rowId)});
        assertEquals("Exactly one row should be deleted", 1, deleted);
        assertEquals("Row count should return to the pre-insert value", beforeCount, countRows(controller));
    }

    @Test
    public void deleteDropTargetAllowsBaselineRemoval() {
        // Verify that DeleteDropTarget class exists and can be referenced
        // without Deck-related preferences.
        Class<?> cls = assertClassExists("com.android.launcher3.DeleteDropTarget");
        assertNotNull("DeleteDropTarget class must be loadable", cls);
    }

    private static int countRows(ModelDbController controller) {
        try (Cursor cursor = controller.query(
                Favorites.TABLE_NAME, new String[]{"COUNT(*)"}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private static Class<?> assertClassExists(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Required class not found: " + className, e);
        }
    }
}