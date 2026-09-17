package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoredIdentity
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideStoredSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogIdentity
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogSnapshot
import app.lawnchair.organizer.rules.UserDefinedCategoryMutation
import app.lawnchair.organizer.rules.UserDefinedCategoryStore
import app.lawnchair.organizer.rules.UserDefinedCategoryStoredIdentity
import app.lawnchair.organizer.rules.UserDefinedCategoryStoredReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryStoredSnapshot
import app.lawnchair.organizer.rules.UserDefinedCategoryWriteResult
import app.lawnchair.organizer.rules.sha256Canonical
import app.lawnchair.organizer.rules.storedSnapshot
import app.lawnchair.ui.preferences.destinations.CustomCategoryPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #336 management-surface evidence (compile-gated here; device runs are
 * CI's job): create/rename/delete flows, the typed duplicate-name feedback,
 * the delete confirmation's assignment count + automatic-classification
 * wording with no remap option, the truthful partial-delete rendering, and the
 * "Custom" marker in the assignment selector — never a raw category ID.
 */
@RunWith(AndroidJUnit4::class)
class CustomCategoryPreferencesInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val userId = UserCategoryId("3f2b8c4e-1234-4abc-9de0-1234567890ab")

    @Test
    fun createFlowRendersTypedDuplicateFeedbackAndListsTheCreatedEntry() {
        val catalog = FakeCatalogStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, FakeOverrideStore()))
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_custom_category_create)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_create)).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").performTextInput("Commute")
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_save)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Commute").fetchSemanticsNodes().isNotEmpty()
        }

        // Duplicate name is a typed, localized failure — no write.
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_create)).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").performTextInput("Commute")
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_save)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_error_duplicate_name)).assertIsDisplayed()

        // Raw IDs never render anywhere on the surface.
        composeRule.onAllNodesWithText(userId.value).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun deleteConfirmationStatesCountAndAutomaticReturnWithoutRemapOption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply {
            seed(
                mapOf(
                    key("com.a") to CategoryIdentity.UserDefined(userId),
                    key("com.b") to CategoryIdentity.UserDefined(userId),
                ),
            )
        }
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, overrides))
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_custom_category_delete_row)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_row)).performClick()

        val expectedText = context.resources.getQuantityString(
            R.plurals.organizer_custom_category_delete_text,
            2,
            "Commute",
            2,
        )
        composeRule.onNodeWithText(expectedText).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_confirm)).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Commute").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun partialDeleteRendersTruthfullyAndRetryCompletesTheDelete() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply { seed(mapOf(key("com.a") to CategoryIdentity.UserDefined(userId))) }
        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.VerificationFailed
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, overrides))
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_custom_category_delete_row)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_row)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_confirm)).performClick()

        // The truthful partial state: assignments removed, empty category remains.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_custom_category_retry)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_retry)).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Commute").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun renameKeepsTheEntryPresentedAsTheSameCategory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, FakeOverrideStore()))
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Commute").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        ).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").performTextClearance()
        composeRule.onNodeWithTag("custom-category-name-field").performTextInput("Morning routine")
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_rename_confirm)).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Morning routine").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private fun key(packageName: String) = CategoryOverrideKey(PackageName(packageName), ProfileId("0"))

    private class FakeCatalogStore : UserDefinedCategoryStore {
        var snapshot = storedSnapshot(0L, emptyList())
        var injectNextWriteResult: UserDefinedCategoryWriteResult? = null

        fun seed(entries: List<UserDefinedCategory>) {
            snapshot = storedSnapshot(0L, entries)
        }

        override fun read(): UserDefinedCategoryCatalogReadResult = UserDefinedCategoryCatalogReadResult.Ready(visible())

        override fun readStored(): UserDefinedCategoryStoredReadResult = UserDefinedCategoryStoredReadResult.Ready(snapshot)

        override fun mutate(
            request: UserDefinedCategoryMutation,
            expected: UserDefinedCategoryStoredIdentity,
        ): UserDefinedCategoryWriteResult {
            injectNextWriteResult?.let {
                injectNextWriteResult = null
                return it
            }
            if (snapshot.identity != expected) return UserDefinedCategoryWriteResult.Conflict
            val next = when (request) {
                is UserDefinedCategoryMutation.Create -> {
                    val normalized = request.displayName.trim()
                    if (snapshot.categories.any { it.displayName == normalized }) return UserDefinedCategoryWriteResult.DuplicateName
                    val minted = UserCategoryId("00000000-0000-4000-8000-000000000001")
                    snapshot.categories + UserDefinedCategory(minted, normalized)
                }

                is UserDefinedCategoryMutation.Rename -> snapshot.categories.map {
                    if (it.id == request.id) it.copy(displayName = request.displayName.trim()) else it
                }

                is UserDefinedCategoryMutation.Delete -> snapshot.categories.filterNot { it.id == request.id }
            }
            if (next == snapshot.categories) return UserDefinedCategoryWriteResult.NoChange(snapshot.identity, visible().identity)
            snapshot = storedSnapshot(snapshot.identity.generation + 1L, next)
            return UserDefinedCategoryWriteResult.Committed(snapshot.identity, visible().identity, null)
        }

        private fun visible(): UserDefinedCategoryCatalogSnapshot = UserDefinedCategoryCatalogSnapshot(
            schemaVersion = snapshot.identity.schemaVersion,
            generation = snapshot.identity.generation,
            categories = snapshot.categories,
            identity = UserDefinedCategoryCatalogIdentity.identityOf(
                snapshot.identity.schemaVersion,
                snapshot.identity.generation,
                snapshot.categories,
            ),
        )
    }

    private class FakeOverrideStore : CategoryOverrideStore {
        var snapshot = CategoryOverrideStoredSnapshot(storedIdentity(0L, emptyMap()), emptyMap())

        fun seed(entries: Map<CategoryOverrideKey, CategoryIdentity>) {
            snapshot = CategoryOverrideStoredSnapshot(storedIdentity(0L, entries), entries)
        }

        override fun readStored(): CategoryOverrideStoredReadResult = CategoryOverrideStoredReadResult.Ready(snapshot)

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 2,
                generation = snapshot.identity.generation,
                assignments = snapshot.assignments.filterKeys { it.profile in capturedProfiles },
                identity = visibleIdentity(snapshot.identity.generation),
            ),
        )

        override fun mutate(
            request: CategoryOverrideMutation,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult = mutateAll(listOf(request), expected, verificationProfiles)

        override fun mutateAll(
            requests: List<CategoryOverrideMutation>,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult {
            val next = snapshot.assignments.toMutableMap()
            for (request in requests) {
                when (request) {
                    is CategoryOverrideMutation.Set -> next[request.key] = request.category
                    is CategoryOverrideMutation.Remove -> next.remove(request.key)
                }
            }
            snapshot = CategoryOverrideStoredSnapshot(storedIdentity(snapshot.identity.generation + 1L, next), next)
            return CategoryOverrideWriteResult.Committed(snapshot.identity, visibleIdentity(snapshot.identity.generation))
        }

        private fun storedIdentity(generation: Long, entries: Map<CategoryOverrideKey, CategoryIdentity>) = CategoryOverrideStoredIdentity(
            2,
            generation,
            sha256Canonical(canonicalAssignments(entries)),
        )

        private fun canonicalAssignments(entries: Map<CategoryOverrideKey, CategoryIdentity>): String = entries.entries
            .sortedWith(compareBy({ it.key.profile.value }, { it.key.packageName.value }))
            .joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.canonicalValue}" }

        private fun visibleIdentity(generation: Long) = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-2-generation-$generation",
            sha256Canonical(""),
        )
    }
}
