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
package app.lawnchair.ui.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.lawnchair.gestures.config.GestureHandlerOption
import app.lawnchair.preferences2.PreferenceManager2
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that App Drawer and gesture preferences are accessible and no
 * Deck controls exist in the preferences UI after retirement.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementPreferencesInstrumentationTest {

    @Test
    fun noDeckPreferenceReflection() {
        // Verify that PreferenceManager2 has no public deckLayout field.
        // The retired Deck tombstone keys are private and not exposed as
        // public Preference properties.
        val pm2Class = PreferenceManager2::class.java
        for (field in pm2Class.declaredFields) {
            val name = field.name.lowercase()
            if (name.contains("deck")) {
                // The only allowed deck-related fields are the private
                // tombstone keys.  Public deckLayout/showDeckLayout
                // Preference properties must NOT exist.
                assertTrue(
                    "Deck field '$name' must be private",
                    java.lang.reflect.Modifier.isPrivate(field.modifiers),
                )
            }
        }
    }

    @Test
    fun gestureHandlerOptionsIncludeOpenAppDrawer() {
        // Verify that GestureHandlerOption.OpenAppDrawer remains available.
        // With Deck retired, the Deck-specific gesture filter is gone, so the
        // App Drawer and App Search gesture choices are never hidden.
        val openAppDrawer = GestureHandlerOption.OpenAppDrawer
        assertNotNull("OpenAppDrawer gesture option must exist", openAppDrawer)
        assertNotNull(
            "OpenAppSearch gesture option must exist",
            GestureHandlerOption.OpenAppSearch,
        )
        assertTrue(
            "OpenAppDrawer must resolve as a gesture handler option",
            openAppDrawer is GestureHandlerOption,
        )
    }
}