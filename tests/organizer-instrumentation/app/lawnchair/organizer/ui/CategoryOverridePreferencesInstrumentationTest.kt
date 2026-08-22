package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
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
import app.lawnchair.organizer.rules.sha256Canonical
import app.lawnchair.ui.preferences.destinations.CategoryOverridePreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryOverridePreferencesInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun samePackageProfilesExposeTextStateAndIndependentAccessibleRows() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().size == 2
        }
        composeRule.onNodeWithText("${context.getString(R.string.organizer_category_override_profile_personal)} · ${context.getString(R.string.organizer_category_override_automatic)}")
            .assertIsDisplayed()
        composeRule.onNodeWithText("${context.getString(R.string.organizer_category_override_profile_work)} · ${context.getString(R.string.organizer_category_override_automatic)}")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Example, ${context.getString(R.string.organizer_category_override_profile_personal)}, ${context.getString(R.string.organizer_category_override_automatic)}",
        ).assertHasClickAction()
        composeRule.onNodeWithContentDescription(
            "Example, ${context.getString(R.string.organizer_category_override_profile_work)}, ${context.getString(R.string.organizer_category_override_automatic)}",
        ).assertHasClickAction()
    }

    @Test
    fun editorIsReadableAtTwoHundredPercentFontScaleAndRestoresFocusAfterSave() {
        val coordinator = coordinator()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator)
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Example").performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_game)).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_save)).assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_saved)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun coordinator(): CategoryOverrideAuthoringCoordinator {
        val personal = app("0", CategoryOverrideProfile.PERSONAL)
        val work = app("10", CategoryOverrideProfile.WORK)
        return CategoryOverrideAuthoringCoordinator(
            TestStore(),
            BuiltInOrganizerPolicyBundleSource,
            CategoryOverrideAppInventory { listOf(personal, work) },
        )
    }

    private fun app(profileId: String, profile: CategoryOverrideProfile) = CategoryOverrideApp(
        key = CategoryOverrideKey(PackageName("com.example.same"), ProfileId(profileId)),
        label = "Example",
        profile = profile,
        icon = null,
        assignedCategory = null,
    )

    private class TestStore : CategoryOverrideStore {
        private var snapshot = stored(0L, emptyMap())

        override fun readStored(): CategoryOverrideStoredReadResult = CategoryOverrideStoredReadResult.Ready(snapshot)

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 1,
                generation = snapshot.identity.generation,
                assignments = snapshot.assignments.filterKeys { it.profile in capturedProfiles },
                identity = visibleIdentity(snapshot.identity.generation),
            ),
        )

        override fun mutate(
            request: CategoryOverrideMutation,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult {
            val entries = snapshot.assignments.toMutableMap()
            when (request) {
                is CategoryOverrideMutation.Set -> entries[request.key] = request.category
                is CategoryOverrideMutation.Remove -> entries.remove(request.key)
            }
            snapshot = stored(snapshot.identity.generation + 1L, entries)
            return CategoryOverrideWriteResult.Committed(
                snapshot.identity,
                visibleIdentity(snapshot.identity.generation),
            )
        }

        private fun stored(
            generation: Long,
            assignments: Map<CategoryOverrideKey, CategoryId>,
        ): CategoryOverrideStoredSnapshot = CategoryOverrideStoredSnapshot(
            CategoryOverrideStoredIdentity(1, generation, sha256Canonical("")),
            assignments,
        )

        private fun visibleIdentity(generation: Long) = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-1-generation-$generation",
            sha256Canonical(""),
        )
    }
}
