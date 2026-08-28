package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
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
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertHasClickAction()
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_work),
        ).assertHasClickAction()
    }

    @Test
    fun cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale() {
        val longLabel = "A very long localized application label that must remain reachable in category overrides"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator(label = longLabel))
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(longLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal, longLabel),
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_cancel)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_cancel))
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_overrides_summary)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
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
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_game)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_game))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_save)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_save))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_saved)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun keyboardDpadNavigatesToProfileRowAndActivatesEditor() {
        val coordinator = coordinator()
        var inputModeManager: InputModeManager? = null
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            requireNotNull(inputModeManager).requestInputMode(InputMode.Keyboard)
        }
        val summary = composeRule.onNodeWithText(
            context.getString(R.string.organizer_category_overrides_summary),
        )
        summary.requestFocus().assertIsFocused()
        summary.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNode(hasScrollAction()).printToLog("CategoryOverrideDpadFocus")
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertIsFocused()
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_use_automatic)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_use_automatic)).assertIsDisplayed()
    }

    @Test
    fun switchEquivalentSemanticsActivationOpensEditor() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_use_automatic)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_use_automatic)).assertIsDisplayed()
    }

    @Test
    fun appRowMeetsMinimumFortyEightDpTouchTarget() {
        val coordinator = coordinator()
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val appRow = composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        )
        composeRule.waitUntil(5_000) {
            try {
                appRow.fetchSemanticsNode()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        val height = appRow.fetchSemanticsNode().boundsInRoot.height
        val minimumHeight = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("Category override app row must provide a 48dp touch target", height >= minimumHeight)
    }

    @Test
    fun targetUnavailableReturnsToFreshDestinationAndRestoresFocus() {
        val personal = app("0", CategoryOverrideProfile.PERSONAL, "Example")
        var inventoryReads = 0
        val coordinator = coordinator(
            inventory = CategoryOverrideAppInventory {
                inventoryReads += 1
                if (inventoryReads == 1) listOf(personal) else emptyList()
            },
        )
        composeRule.setContent {
            LawnchairTheme {
                CategoryOverridePreferences(coordinator = coordinator)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_category_override_save)),
        )
        composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_save))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.organizer_category_override_unavailable)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun longestLocalizedCategoryPresentationLabelRemainsReachableAtTwoHundredPercentFontScale() {
        val coordinator = coordinator()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val longestLabel = requireNotNull(coordinator.categories())
            .map { CategoryOverrideCategoryPresentations.forCategory(it).labelRes }
            .map(context::getString)
            .maxBy(String::length)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CategoryOverridePreferences(coordinator = coordinator)
                }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            appContentDescription(context, R.string.organizer_category_override_profile_personal),
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(longestLabel))
        composeRule.onNodeWithText(longestLabel).assertIsDisplayed()
    }

    private fun appContentDescription(context: Context, profileResource: Int, label: String = "Example"): String =
        context.getString(
            R.string.organizer_category_override_app_description,
            label,
            context.getString(profileResource),
            context.getString(R.string.organizer_category_override_automatic),
        )

    private fun coordinator(
        label: String = "Example",
        inventory: CategoryOverrideAppInventory? = null,
    ): CategoryOverrideAuthoringCoordinator {
        val personal = app("0", CategoryOverrideProfile.PERSONAL, label)
        val work = app("10", CategoryOverrideProfile.WORK, label)
        return CategoryOverrideAuthoringCoordinator(
            TestStore(),
            BuiltInOrganizerPolicyBundleSource,
            inventory ?: CategoryOverrideAppInventory { listOf(personal, work) },
        )
    }

    private fun app(profileId: String, profile: CategoryOverrideProfile, label: String) = CategoryOverrideApp(
        key = CategoryOverrideKey(PackageName("com.example.same"), ProfileId(profileId)),
        label = label,
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
