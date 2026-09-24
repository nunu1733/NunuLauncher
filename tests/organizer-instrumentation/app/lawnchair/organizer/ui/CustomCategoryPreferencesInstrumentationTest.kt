package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #336 management-surface evidence + spec 336 AC-13 automated a11y
 * asserts (issue #342; compile-gated here, device runs are CI's job):
 * create/rename/delete flows with typed feedback, localized
 * "Rename <name>"/"Delete <name>" click actions, a polite live region on the
 * summary, Compose/keyboard input focus restore on editor and dialog exits,
 * keyboard/DPAD and Switch Access equivalent activation, the color-independent
 * "Custom" marker, 200%-font-scale reachability with a 48dp touch target, and
 * a real contains-based raw-ID semantics scan — never a raw category ID.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CustomCategoryPreferencesInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val userId = UserCategoryId("3f2b8c4e-1234-4abc-9de0-1234567890ab")

    private val mintedId = "00000000-0000-4000-8000-000000000001"

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

        // AC-6 state 2: the create editor's full semantics tree carries no raw id.
        assertNoRawIdsPresent(userId.value, mintedId)
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
        // AC-6 state 5: the partial-delete view carries no raw id either.
        assertNoRawIdsPresent(userId.value, mintedId)
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
        // The row exposes the rename affordance as its content description,
        // not as visible text.
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        ).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").performTextClearance()
        composeRule.onNodeWithTag("custom-category-name-field").performTextInput("Morning routine")
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_rename_confirm)).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Morning routine").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- spec 336 AC-13 asserts (issue #342) --------------------------------

    @Test
    fun rowsExposeLocalizedActionLabelsAndLiveRegion() {
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
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        ).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_delete_action, "Commute"),
        ).assertIsDisplayed().assertHasClickAction()
        val summaryConfig = composeRule.onNodeWithText(
            context.getString(R.string.organizer_custom_category_summary),
        ).fetchSemanticsNode().config
        assertTrue(
            "The summary node must declare a polite live region",
            summaryConfig.contains(SemanticsProperties.LiveRegion) &&
                summaryConfig[SemanticsProperties.LiveRegion] == LiveRegionMode.Polite,
        )
        // AC-6 state 1: the plain list carries no raw id.
        assertNoRawIdsPresent(userId.value, mintedId)
    }

    @Test
    fun editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary = context.getString(R.string.organizer_custom_category_summary)
        val cancel = context.getString(R.string.organizer_custom_category_cancel)
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply { seed(mapOf(key("com.a") to CategoryIdentity.UserDefined(userId))) }
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, overrides))
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()
        }

        // Create editor → cancel.
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_create)).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").assertIsDisplayed()
        composeRule.onNodeWithText(cancel).performClick()
        awaitSummaryFocus(summary)

        // Rename editor → cancel.
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        ).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").assertIsDisplayed()
        // AC-6 state 3: the rename editor carries no raw id.
        assertNoRawIdsPresent(userId.value, mintedId)
        composeRule.onNodeWithText(cancel).performClick()
        awaitSummaryFocus(summary)

        // Delete dialog → cancel.
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_delete_action, "Commute"),
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_title)).assertIsDisplayed()
        // AC-6 state 4: the delete confirmation dialog carries no raw id.
        assertNoRawIdsPresent(userId.value, mintedId)
        composeRule.onNodeWithText(cancel).performClick()
        awaitSummaryFocus(summary)

        // Delete dialog → confirm.
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_delete_action, "Commute"),
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_confirm)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Commute").fetchSemanticsNodes().isEmpty()
        }
        awaitSummaryFocus(summary)
    }

    @Test
    fun keyboardDpadActivatesCreateActionFromTheSummaryNode() {
        var inputModeManager: InputModeManager? = null
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(FakeCatalogStore(), FakeOverrideStore()))
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val create = context.getString(R.string.organizer_custom_category_create)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(create).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            requireNotNull(inputModeManager).requestInputMode(InputMode.Keyboard)
        }
        val summary = composeRule.onNodeWithText(
            context.getString(R.string.organizer_custom_category_summary),
        )
        summary.requestFocus().assertIsFocused()
        // The create action is the first item under the summary, so one
        // DirectionDown from the summary must land on it.
        summary.performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithText(create).assertIsFocused()
        composeRule.onNodeWithText(create).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("custom-category-name-field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("custom-category-name-field").assertIsDisplayed()
    }

    @Test
    fun switchEquivalentSemanticsActivationOpensTheRenameEditor() {
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
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        ).assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_rename_confirm)).assertIsDisplayed()
    }

    @Test
    fun rowsRemainReachableAtTwoHundredPercentFontScale() {
        // Exactly 50 code points, the accepted AC-5 maximum-length fixture.
        val longName = "N".repeat(50)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, longName))) }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, FakeOverrideStore()))
                }
            }
        }

        val renameLabel = context.getString(R.string.organizer_custom_category_rename_action, longName)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(renameLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(renameLabel))
        composeRule.onNodeWithContentDescription(renameLabel).assertIsDisplayed().assertHasClickAction()

        // Non-color state: the row's marker is the "Custom" text itself.
        assertTrue(
            "The entry row must carry the Custom text marker",
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_category_override_custom_marker))
                .fetchSemanticsNodes().isNotEmpty(),
        )

        val deleteLabel = context.getString(R.string.organizer_custom_category_delete_action, longName)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(deleteLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(deleteLabel))
        // The delete confirmation is readable and operable at 200%.
        composeRule.onNodeWithContentDescription(deleteLabel).performClick()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.organizer_custom_category_delete_text,
                0,
                longName,
                0,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_delete_confirm))
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_cancel))
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        // The create action stays reachable at 200%, and typed feedback is text.
        val create = context.getString(R.string.organizer_custom_category_create)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(create))
        composeRule.onNodeWithText(create).assertIsDisplayed()
        composeRule.onNodeWithText(create).performClick()
        composeRule.onNodeWithTag("custom-category-name-field").performTextInput(longName)
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_save)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_custom_category_error_duplicate_name))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_custom_category_error_duplicate_name)).assertIsDisplayed()
    }

    @Test
    fun entryRowMeetsMinimumFortyEightDpTouchTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        composeRule.setContent {
            LawnchairTheme {
                CustomCategoryPreferences(coordinator = UserDefinedCategoryAuthoringCoordinator(catalog, FakeOverrideStore()))
            }
        }

        val entryRow = composeRule.onNodeWithContentDescription(
            context.getString(R.string.organizer_custom_category_rename_action, "Commute"),
        )
        composeRule.waitUntil(5_000) {
            try {
                entryRow.fetchSemanticsNode()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        val height = entryRow.fetchSemanticsNode().boundsInRoot.height
        val minimumHeight = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("The custom-category entry row must provide a 48dp touch target", height >= minimumHeight)
    }

    // ---- shared helpers ------------------------------------------------------

    private fun awaitSummaryFocus(summary: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(summary).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun collectSemanticsStrings(node: SemanticsNode, out: MutableList<String>) {
        val config = node.config
        if (config.contains(SemanticsProperties.EditableText)) {
            out += config[SemanticsProperties.EditableText].text
        }
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].forEach { out += it.text }
        }
        if (config.contains(SemanticsProperties.ContentDescription)) {
            out += config[SemanticsProperties.ContentDescription]
        }
        node.children.forEach { collectSemanticsStrings(it, out) }
    }

    private fun assertNoRawIdsPresent(seedId: String, mintedId: String) {
        val forbidden = listOf(seedId, mintedId)
        val strings = mutableListOf<String>()
        composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().forEach { root ->
            collectSemanticsStrings(root, strings)
        }
        for (value in forbidden) {
            for (candidate in strings) {
                assertFalse("Raw category id '$value' must not surface in semantics text '$candidate'", candidate.contains(value))
            }
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
            // The coordinator's verified-Create contract requires a Committed
            // Create to report the minted entry (spec #336), so the fake must
            // carry it instead of a null third member.
            var minted: UserDefinedCategory? = null
            val next = when (request) {
                is UserDefinedCategoryMutation.Create -> {
                    val normalized = request.displayName.trim()
                    if (snapshot.categories.any { it.displayName == normalized }) return UserDefinedCategoryWriteResult.DuplicateName
                    val created = UserDefinedCategory(UserCategoryId("00000000-0000-4000-8000-000000000001"), normalized)
                    minted = created
                    snapshot.categories + created
                }

                is UserDefinedCategoryMutation.Rename -> snapshot.categories.map {
                    if (it.id == request.id) it.copy(displayName = request.displayName.trim()) else it
                }

                is UserDefinedCategoryMutation.Delete -> snapshot.categories.filterNot { it.id == request.id }
            }
            if (next == snapshot.categories) return UserDefinedCategoryWriteResult.NoChange(snapshot.identity, visible().identity)
            snapshot = storedSnapshot(snapshot.identity.generation + 1L, next)
            return UserDefinedCategoryWriteResult.Committed(snapshot.identity, visible().identity, minted)
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
