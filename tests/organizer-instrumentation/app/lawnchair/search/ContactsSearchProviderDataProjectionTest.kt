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
package app.lawnchair.search

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.engine.provider.ContactsSearchProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #574 RED oracle: `ContactsSearchProvider.search` must emit a
 * [SearchResult.Contact] (name/number/contactId/photo — the fields the UI
 * binds) for a granted, pref-on people query when the ContactsContract.Data
 * view rejects `account_type`/`account_name` projection columns (API 37 CP2
 * behavior), while the API 36-style provider path and the denied-permission
 * zero-query short-circuit stay regression-free.
 *
 * Fixture fidelity: the fake [ContentProvider] emulates the *runtime contract*
 * of the platform Data view — it throws `IllegalArgumentException("Invalid
 * column …")` for forbidden projection columns and serves one privacy-safe
 * synthetic row (`ZZ574_TEST_CONTACT`) for allowed projections — NOT a
 * source-text/regex check of the production projection. `phonebook_label`
 * remains permitted (device probe 2026-10-11: still readable), matching the
 * owner decision on issue #574 to remove only account_type/account_name.
 *
 * Seam: the public `ContactsSearchProvider.search(context, query)` entry only.
 * No private `findContactsByName` call, no test-only production interface, no
 * real contact seeding and no permission mutation (permission is observed via
 * the Context the provider receives).
 */
@RunWith(AndroidJUnit4::class)
class ContactsSearchProviderDataProjectionTest {

    private val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private var originalPeoplePref: Boolean = false

    @Before
    fun enablePeopleResultsAndRememberPref() {
        val prefs = PreferenceManager.getInstance(targetContext)
        originalPeoplePref = prefs.searchResultPeople.get()
        prefs.searchResultPeople.set(true)
    }

    @After
    fun restorePeoplePref() {
        PreferenceManager.getInstance(targetContext).searchResultPeople.set(originalPeoplePref)
    }

    @Test
    fun granted_searchEmitsContact_whenAccountColumnsRejectedLikeApi37() {
        val provider = FakeContactsDataProvider(acceptsAccountColumns = false)
        val results = runBlocking {
            ContactsSearchProvider.search(searchContext(provider, permissionGranted = true), QUERY).first()
        }

        val contacts = results.filterIsInstance<SearchResult.Contact>()
        assertEquals(
            "API 37 Data view rejects account_type/account_name projections; search must still " +
                "emit the synthetic contact instead of silently emitting empty",
            1,
            contacts.size,
        )
        val contact = contacts.single().data
        assertEquals(SYNTHETIC_NAME, contact.name)
        assertEquals(SYNTHETIC_NUMBER, contact.number)
        assertEquals(SYNTHETIC_CONTACT_ID, contact.contactId)
        assertTrue("fixture must have been queried at least once", provider.queryCount >= 1)
    }

    @Test
    fun granted_searchEmitsContact_whenAccountColumnsAllowedLikeApi36() {
        val provider = FakeContactsDataProvider(acceptsAccountColumns = true)
        val results = runBlocking {
            ContactsSearchProvider.search(searchContext(provider, permissionGranted = true), QUERY).first()
        }

        val contacts = results.filterIsInstance<SearchResult.Contact>()
        assertEquals("API 36-style provider results must not regress", 1, contacts.size)
        val contact = contacts.single().data
        assertEquals(SYNTHETIC_NAME, contact.name)
        assertEquals(SYNTHETIC_NUMBER, contact.number)
        assertEquals(SYNTHETIC_CONTACT_ID, contact.contactId)
    }

    @Test
    fun denied_shortCircuitsWithoutAnyProviderQuery() {
        val provider = FakeContactsDataProvider(acceptsAccountColumns = false)
        val results = runBlocking {
            ContactsSearchProvider.search(searchContext(provider, permissionGranted = false), QUERY).first()
        }

        assertTrue("denied READ_CONTACTS must emit no results", results.isEmpty())
        assertEquals(
            "denied READ_CONTACTS must zero-query short-circuit (v6-07 contract)",
            0,
            provider.queryCount,
        )
    }

    private fun searchContext(provider: FakeContactsDataProvider, permissionGranted: Boolean): Context {
        provider.attachInfo(
            targetContext,
            ProviderInfo().apply {
                authority = CONTACTS_AUTHORITY
                exported = false
            },
        )
        return PermissionAndResolverContext(
            targetContext,
            FakeContactsResolver(targetContext, provider),
            permissionGranted,
        )
    }

    /** Routes `contentResolver` to the fixture and controls `checkSelfPermission`. */
    private class PermissionAndResolverContext(
        base: Context,
        private val resolver: ContentResolver,
        private val permissionGranted: Boolean,
    ) : ContextWrapper(base) {
        override fun getContentResolver(): ContentResolver = resolver

        override fun checkSelfPermission(permission: String): Int =
            if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    /** Minimal [ContentResolver] dispatching every provider lookup to the fixture. */
    private class FakeContactsResolver(
        context: Context,
        private val provider: FakeContactsDataProvider,
    ) : ContentResolver(context) {
        override fun acquireProvider(c: Context?, name: String?): IContentProvider? =
            provider.getIContentProvider()

        override fun acquireUnstableProvider(c: Context?, name: String?): IContentProvider? =
            provider.getIContentProvider()

        override fun releaseProvider(icp: IContentProvider?): Boolean = true

        override fun releaseUnstableProvider(icp: IContentProvider?): Boolean = true

        override fun unstableProviderDied(icp: IContentProvider?) = Unit
    }

    /**
     * Emulates the platform Data view contract observed on API 37 CP2
     * (physical Pixel 9a + emulator-5554, 2026-10-11): a projection asking for
     * `account_type`/`account_name` fails the whole query with
     * `IllegalArgumentException("Invalid column …")`; allowed projections serve
     * one privacy-safe synthetic phone row. With `acceptsAccountColumns = true`
     * it behaves like the API 36 Data view (emulator-5556) and also serves
     * account columns.
     */
    private class FakeContactsDataProvider(
        private val acceptsAccountColumns: Boolean,
    ) : ContentProvider() {

        var queryCount: Int = 0
            private set

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queryCount++
            val columns = projection ?: SYNTHETIC_VALUES.keys.toTypedArray()
            if (!acceptsAccountColumns) {
                val forbidden = columns.firstOrNull { it in FORBIDDEN_COLUMNS }
                if (forbidden != null) {
                    throw IllegalArgumentException("Invalid column $forbidden")
                }
            }
            return MatrixCursor(columns).apply {
                addRow(columns.map { syntheticValue(it) }.toTypedArray())
            }
        }

        override fun getType(uri: Uri): String = "vnd.android.cursor.dir/data"

        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            throw UnsupportedOperationException("fixture is read-only")

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private fun syntheticValue(column: String): String = SYNTHETIC_VALUES[column] ?: ""
    }

    companion object {
        private const val QUERY = "ZZ574"
        private const val CONTACTS_AUTHORITY = "com.android.contacts"
        private const val SYNTHETIC_NAME = "ZZ574_TEST_CONTACT"
        private const val SYNTHETIC_NUMBER = "555-0574"
        private const val SYNTHETIC_CONTACT_ID = "5741"

        /** Columns the API 37 CP2 Data view refuses in a projection. */
        private val FORBIDDEN_COLUMNS = setOf("account_type", "account_name")

        /** Privacy-safe synthetic row: no real person, no real account. */
        private val SYNTHETIC_VALUES = mapOf(
            "_id" to "1",
            "contact_id" to SYNTHETIC_CONTACT_ID,
            "display_name" to SYNTHETIC_NAME,
            "data1" to SYNTHETIC_NUMBER,
            "data3" to SYNTHETIC_NUMBER,
            "data5" to "",
            "phonebook_label" to "Z",
            "account_type" to "com.zz574.invalid.account",
            "account_name" to "zz574@invalid.test",
            "mimetype" to "vnd.android.cursor.item/phone_v2",
            "photo_uri" to "",
        )
    }
}
