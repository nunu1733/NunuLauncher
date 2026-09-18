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
 * Issue #328 (spec AC-5, audit D-1): the strategy picker's frozen affordance —
 * a frozen picker disables every radio row (disabled semantics, not only a
 * visual change) and announces the reason in a live region; an enabled picker
 * shows no reason row and keeps the rows selectable.
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

    private fun setPicker(enabled: Boolean, frozenReason: String?) {
        composeRule.setContent {
            app.lawnchair.ui.theme.LawnchairTheme {
                LazyColumn {
                    strategyPickerItems(
                        catalog = catalog,
                        selected = catalog.first(),
                        enabled = enabled,
                        frozenReason = frozenReason,
                        onSelect = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun frozenPickerDisablesTheRadioRowsAndAnnouncesTheReason() {
        val reason = context.getString(R.string.exchange_strategy_frozen_import)
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
        val name = context.getString(R.string.organization_strategy_canonical_name)
        composeRule.onNode(hasText(name, substring = true) and isSelectable())
            .assertIsEnabled()
    }

    @Test
    fun continuingFreezeUsesItsOwnReasonCopy() {
        // The idle continuation freeze must not reuse the import copy.
        val continuingReason = context.getString(R.string.exchange_strategy_frozen_continuing)
        setPicker(enabled = false, frozenReason = continuingReason)
        composeRule.onNodeWithText(continuingReason).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.exchange_strategy_frozen_import))
            .assertDoesNotExist()
    }
}
