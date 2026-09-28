package app.lawnchair.ui.preferences.destinations

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.planning.StrategyId
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #368 (spec AC-2/AC-8; formerly spec 328 AC-5): the strategy picker's
 * frozen affordance on T-05 — a run/recovery operation in progress disables
 * every radio row (disabled semantics, not only a visual change) and
 * announces the reason in a live region; typed refusals from other authoring
 * occupancy or write single flight announce their own retry copy, distinct
 * from the frozen reason.
 */
@RunWith(AndroidJUnit4::class)
class StrategyPickerFreezeInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val catalog = listOf(
        StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        StrategyId("STABLE_PAGE_TIDY_V1"),
    )

    private fun setPicker(enabled: Boolean, frozenReason: String?, retryNotice: String? = null) {
        composeRule.setContent {
            app.lawnchair.ui.theme.LawnchairTheme {
                LazyColumn {
                    strategyPickerItems(
                        catalog = catalog,
                        selected = catalog.first(),
                        enabled = enabled,
                        frozenReason = frozenReason,
                        retryNotice = retryNotice,
                        onSelect = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun frozenPickerDisablesTheRadioRowsAndAnnouncesTheReason() {
        val reason = context.getString(R.string.organizer_strategy_frozen_operation_active)
        setPicker(enabled = false, frozenReason = reason)
        composeRule.onNodeWithTag("strategy-picker-frozen-reason")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val name = context.getString(R.string.organization_strategy_canonical_name)
        composeRule.onNode(hasText(name, substring = true) and isSelectable())
            .assertIsNotEnabled()
    }

    @Test
    fun enabledPickerKeepsTheRowsSelectableAndShowsNoReason() {
        setPicker(enabled = true, frozenReason = null)
        assertEquals(
            "an unfrozen picker carries no reason row",
            0,
            composeRule.onAllNodesWithTag("strategy-picker-frozen-reason").fetchSemanticsNodes().size,
        )
        assertEquals(
            "an unfrozen picker carries no retry notice",
            0,
            composeRule.onAllNodesWithTag("strategy-picker-retry-notice").fetchSemanticsNodes().size,
        )
        val name = context.getString(R.string.organization_strategy_canonical_name)
        composeRule.onNode(hasText(name, substring = true) and isSelectable())
            .assertIsEnabled()
    }

    @Test
    fun aTypedRefusalAnnouncesItsOwnRetryCopyDistinctFromTheFrozenReason() {
        // Issue #368 AC-8: the retry notice for other-authoring occupancy or
        // single flight must not reuse the run/recovery frozen copy.
        val frozen = context.getString(R.string.organizer_strategy_frozen_operation_active)
        val retry = context.getString(R.string.organizer_strategy_retry_when_busy)
        setPicker(enabled = true, frozenReason = null, retryNotice = retry)
        composeRule.onNodeWithTag("strategy-picker-retry-notice")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText(frozen).assertDoesNotExist()
        composeRule.onNode(hasText(context.getString(R.string.organization_strategy_canonical_name), substring = true) and isSelectable())
            .assertIsEnabled()
    }
}
