package com.android.launcher3

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.preferences2.SharedPreferencesMigration
import com.android.launcher3.LauncherFiles.SHARED_PREFERENCES_KEY
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #532 rebase Phase 2 / G4 T7 added oracle, device half (#522 assessment
 * §test表): the production [SharedPreferencesMigration] wiring runs against a
 * real SharedPreferences XML file and a real DataStore file, exercising the
 * androidx migration integration that the JVM half cannot observe.
 *
 * Ownership boundary: the JVM half
 * (`tests/unit/app/lawnchair/preferences2/SharedPreferencesLegacyKeyMigrationTest.kt`)
 * owns the pure key/type conversion. This class owns the device-dependent
 * readback: the migrated values land in a real DataStore with real types, the
 * androidx clean-up consumes the migrated XML keys while unknown keys are
 * left un-deleted (#522 "未知キーは未消去"), an existing DataStore value wins
 * the conflict with a legacy XML value, and a legacy key re-added after
 * migration cannot flip the converted value back ("removed consumer / new
 * defaults で DB 選択が逆戻り" regression).
 *
 * The production shared-preferences name is routed to an isolated file via a
 * ContextWrapper (LauncherPrefsCommitTest pattern) so the launcher's real
 * XML is never touched.
 */
@RunWith(AndroidJUnit4::class)
class PrefsLegacyXmlMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolatedPrefsName = "t7_legacy_xml"
    private val dataStoreFileA = File(context.cacheDir, "t7_migration_a.preferences_pb")
    private val dataStoreFileB = File(context.cacheDir, "t7_migration_b.preferences_pb")

    @After
    fun tearDown() {
        isolatedPrefs().edit().clear().commit()
        dataStoreFileA.delete()
        dataStoreFileB.delete()
    }

    @Test
    fun migrationConvertsIntoRealDataStoreConsumesXmlAndKeepsUnknownKeys() = runBlocking {
        seedLegacyXml(
            mapOf(
                "pref_darkStatusBar" to true,
                "pref_allAppsColumns" to 7,
                "pref_showStatusBar" to 1L,
                "pref_iconSizeFactor" to 1.25f,
                "pref_searchAutoShowKeyboard" to "search",
                "hidden-app-set" to setOf("com.a", "com.b"),
                // An unknown legacy key must not be migrated and must not be
                // consumed by the clean-up.
                "t7_unknown_key" to "keepme",
            ),
        )

        withDataStore(dataStoreFileA, withMigration = true) { store ->
            val data = store.data.first()
            assertEquals(true, data[booleanPreferencesKey("dark_status_bar")])
            assertEquals(7, data[intPreferencesKey("drawer_columns")])
            assertEquals(1L, data[longPreferencesKey("show_status_bar")])
            assertEquals(1.25f, data[floatPreferencesKey("home_icon_size_factor")])
            assertEquals("search", data[stringPreferencesKey("auto_show_keyboard_in_drawer")])
            assertEquals(setOf("com.a", "com.b"), data[stringSetPreferencesKey("hidden_apps")])
            assertEquals(null, data[stringPreferencesKey("t7_unknown_key")])
        }

        val prefs = isolatedPrefs()
        assertFalse(prefs.contains("pref_darkStatusBar"))
        assertEquals(-1, prefs.getInt("pref_allAppsColumns", -1))
        assertEquals("keepme", prefs.getString("t7_unknown_key", null))
        Unit
    }

    @Test
    fun existingDataStoreValueWinsConflictAndBlocksFlipBack() = runBlocking {
        // Pre-existing converted state in the DataStore.
        withDataStore(dataStoreFileB, withMigration = false) { store ->
            store.edit { it[booleanPreferencesKey("dark_status_bar")] = false }
        }
        seedLegacyXml(
            mapOf(
                "pref_darkStatusBar" to true,
                "pref_allAppsColumns" to 7,
            ),
        )

        withDataStore(dataStoreFileB, withMigration = true) { store ->
            val data = store.data.first()
            // Conflict: the existing DataStore value wins over the legacy XML.
            assertEquals(false, data[booleanPreferencesKey("dark_status_bar")])
            // A still-missing target key is migrated from the XML.
            assertEquals(7, data[intPreferencesKey("drawer_columns")])
        }

        // Fresh-start regression: a legacy key re-added after the migration
        // (removed consumer, stale restore) must not flip the converted key.
        seedLegacyXml(mapOf("pref_darkStatusBar" to true))
        withDataStore(dataStoreFileB, withMigration = true) { store ->
            assertEquals(false, store.data.first()[booleanPreferencesKey("dark_status_bar")])
        }
        Unit
    }

    // -- helpers --

    private fun isolatedPrefs(): SharedPreferences =
        context.getSharedPreferences(isolatedPrefsName, Context.MODE_PRIVATE)

    private fun seedLegacyXml(values: Map<String, Any>) {
        val editor = isolatedPrefs().edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.commit()
    }

    private fun <T> withDataStore(
        file: File,
        withMigration: Boolean,
        block: suspend (DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = if (withMigration) {
                listOf(
                    SharedPreferencesMigration(IsolatedPrefsContext(context, isolatedPrefsName))
                        .produceMigration(),
                )
            } else {
                emptyList()
            },
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            scope.cancel()
        }
    }

    /** Routes only the production shared-preferences name to the isolated file. */
    private class IsolatedPrefsContext(
        base: Context,
        private val isolatedPrefsName: String,
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == SHARED_PREFERENCES_KEY) {
                super.getSharedPreferences(isolatedPrefsName, mode)
            } else {
                super.getSharedPreferences(name, mode)
            }
    }
}
