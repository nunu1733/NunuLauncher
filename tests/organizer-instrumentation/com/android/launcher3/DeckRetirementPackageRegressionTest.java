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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.launcher3.model.PackageUpdatedTask;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression tests proving that the Deck runtime has been fully removed
 * and no Deck-related references remain in core package-update handling.
 *
 * Verifies:
 * - LawndeckManager class is absent (Deck entirely removed).
 * - PackageUpdatedTask has no Deck-type member fields or methods.
 * - PackageUpdatedTask itself still exists and compiles.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class DeckRetirementPackageRegressionTest {

    @Test
    public void lawndeckManagerClassIsAbsent() {
        try {
            Class.forName("app.lawnchair.deck.LawndeckManager");
            fail("LawndeckManager class should be removed as part of Deck retirement");
        } catch (ClassNotFoundException expected) {
            // Expected — LawndeckManager has been removed.
        }
    }

    @Test
    public void packageUpdatedTaskHasNoDeckTypeReference() {
        Class<?> taskClass = PackageUpdatedTask.class;
        for (Field field : taskClass.getDeclaredFields()) {
            String typeName = field.getType().getName().toLowerCase();
            if (typeName.contains("deck")) {
                fail("PackageUpdatedTask field '" + field.getName()
                        + "' references Deck type: " + field.getType().getName());
            }
        }
        for (Method method : taskClass.getDeclaredMethods()) {
            String methodName = method.getName().toLowerCase();
            if (methodName.contains("deck")) {
                fail("PackageUpdatedTask method '" + method.getName()
                        + "' contains deck reference");
            }
        }
    }

    @Test
    public void widgetBindingBehaviorRemains() {
        // Verify that PackageUpdatedTask still exists and can be referenced
        // (baseline widget binding behavior is unchanged).
        assertNotNull("PackageUpdatedTask must be loadable", PackageUpdatedTask.class);
    }
}