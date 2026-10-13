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
package app.lawnchair.preferences2

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #532 rebase Phase 2 / G4 T7 added oracle, JVM half (#522 assessment
 * §test表): the legacy SharedPreferences XML → DataStore key conversion logic
 * in [SharedPreferencesMigration], pinned at the lowest deterministic
 * boundary.
 *
 * Ownership boundary: the device readback half
 * (`tests/organizer-instrumentation/com/android/launcher3/PrefsLegacyXmlMigrationTest`)
 * owns the real-file androidx integration — XML consumption, unknown-key
 * retention and the fresh-start flip-back regression. LauncherPrefsCommitTest
 * owns the LauncherPrefs write path. This class owns the conversion through
 * the real androidx [androidx.datastore.core.DataMigration.migrate] glue:
 * legacy key names map to the DataStore names with value AND type preserved,
 * the androidx keySet view hides unknown keys from the conversion, an
 * existing DataStore value blocks re-conversion of its target key, and the
 * migration is required while any target key is missing.
 *
 * The migration's context is only used by the androidx glue to resolve
 * [Context.getSharedPreferences]; a [ContextWrapper] routes it to the
 * in-memory fake, so no Android runtime is needed on the JVM.
 */
class SharedPreferencesLegacyKeyMigrationTest {

    private val values = mapOf<String, Any>(
        "pref_darkStatusBar" to true,
        "pref_allAppsColumns" to 7,
        "pref_showStatusBar" to 1L,
        "pref_iconSizeFactor" to 1.25f,
        "pref_searchAutoShowKeyboard" to "search",
        "hidden-app-set" to setOf("com.a", "com.b"),
        // Unknown keys are invisible through the androidx keySet view and
        // therefore never reach the conversion.
        "pref_unknown_legacy" to 1,
    )

    private val migration = SharedPreferencesMigration(FakePrefsContext(fakePreferences(values)))

    private fun migrate(currentData: Preferences): Preferences = runBlocking {
        migration.produceMigration().migrate(currentData)
    }

    @Test
    fun legacyKeysConvertToDataStoreKeysPreservingValueAndType() {
        val converted = migrate(emptyPreferences())

        assertEquals(true, converted[booleanPreferencesKey("dark_status_bar")])
        assertEquals(7, converted[intPreferencesKey("drawer_columns")])
        assertEquals(1L, converted[longPreferencesKey("show_status_bar")])
        assertEquals(1.25f, converted[floatPreferencesKey("home_icon_size_factor")])
        assertEquals("search", converted[stringPreferencesKey("auto_show_keyboard_in_drawer")])
        assertEquals(setOf("com.a", "com.b"), converted[stringSetPreferencesKey("hidden_apps")])
        // Unknown legacy keys are not migrated and legacy names do not leak
        // into the DataStore.
        assertEquals(null, converted[intPreferencesKey("pref_unknown_legacy")])
        assertTrue(converted.asMap().keys.none { it.name.startsWith("pref_") })
    }

    @Test
    fun existingDataStoreValueBlocksReconversionOfItsTargetKey() {
        val converted = migrate(
            mutablePreferencesOf(booleanPreferencesKey("dark_status_bar") to false),
        )

        // The already-converted target keeps its DataStore value; the other
        // legacy keys still convert.
        assertEquals(false, converted[booleanPreferencesKey("dark_status_bar")])
        assertEquals(7, converted[intPreferencesKey("drawer_columns")])
    }

    @Test
    fun migrationIsRequiredWhileAnyTargetKeyIsMissing() = runBlocking {
        val dataMigration = migration.produceMigration()

        assertTrue(
            "empty DataStore must require the legacy XML migration",
            dataMigration.shouldMigrate(emptyPreferences()),
        )
        val partial = mutablePreferencesOf(booleanPreferencesKey("dark_status_bar") to true)
        assertTrue(
            "a DataStore with only some converted keys must still require the migration",
            dataMigration.shouldMigrate(partial),
        )
        Unit
    }

    /**
     * Minimal in-memory [SharedPreferences]: the androidx migration view reads
     * [SharedPreferences.getAll]; the editor is only used by the androidx
     * clean-up, which this boundary never triggers.
     */
    private fun fakePreferences(values: Map<String, Any>): SharedPreferences = object : SharedPreferences {
        override fun getAll(): Map<String, *> = values

        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = @Suppress("UNCHECKED_CAST")
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("clean-up is not part of this boundary")

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    /**
     * Routes the production shared-preferences name to the in-memory fake.
     * The base context stays null: every member this boundary touches is
     * overridden.
     */
    private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
}
