package app.lawnchair.ui.popup

import app.lawnchair.ui.popup.LauncherOptionsPopup.DEFAULT_ORDER
import app.lawnchair.ui.popup.LauncherOptionsPopup.filterVisiblePopupOptions
import app.lawnchair.ui.popup.LauncherOptionsPopup.getMetadataForOption
import app.lawnchair.ui.popup.LauncherOptionsPopup.mergeMissingPopupOptions
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #452: the workspace long-press popup owns a persisted option order
 * (`launcher_popup_order`). These tests pin the pure contracts behind that
 * preference at their lowest deterministic boundary:
 *
 * - [LauncherOptionsPopup.mergeMissingPopupOptions] restores default items a
 *   saved order lacks at their DEFAULT_ORDER-relative position (upgrades from
 *   before/after #449's `edit_surface` both converge deterministically) while
 *   leaving existing items' order and enabled flags untouched. A merge that
 *   changes nothing returns an equal list, which is the no-write guard
 *   condition for `restoreMissingPopupOptions`.
 * - [LauncherOptionsPopup.filterVisiblePopupOptions] keeps disabled and
 *   carousel rows out of the popup and hides the editing/organizing entries
 *   while the home screen is locked.
 * - [LauncherOptionsPopup.getMetadataForOption] maps `organize_home` to its
 *   finalized label/icon resources (spec Revision 3).
 */
class LauncherOptionsPopupOrderTest {
    private fun identifiers(items: List<LauncherOptionPopupItem>) = items.map { it.identifier }

    private fun enabledFlags(items: List<LauncherOptionPopupItem>) = items.map { it.isEnabled }

    @Test
    fun `default order places organize_home between edit_mode and edit_surface, enabled by default`() {
        assertEquals(
            listOf(
                "carousel",
                "lock",
                "edit_mode",
                "organize_home",
                "edit_surface",
                "wallpaper",
                "widgets",
                "home_settings",
                "sys_settings",
            ),
            identifiers(DEFAULT_ORDER),
        )
        val organizeHome = DEFAULT_ORDER.first { it.identifier == "organize_home" }
        assertEquals(true, organizeHome.isEnabled)
    }

    @Test
    fun `metadata maps organize_home to its finalized label and icon`() {
        val metadata = getMetadataForOption("organize_home")

        assertEquals(R.string.home_screen_organize, metadata.label)
        assertEquals(R.drawable.ic_organize_home, metadata.icon)
    }

    @Test
    fun `merge inserts both missing entries positionally into a pre-449 saved order`() {
        val saved = "+carousel|-lock|-edit_mode|+wallpaper|+widgets|+home_settings|-sys_settings"
            .toLauncherOptions()

        val merged = mergeMissingPopupOptions(saved)

        assertEquals(
            listOf(
                "carousel",
                "lock",
                "edit_mode",
                "organize_home",
                "edit_surface",
                "wallpaper",
                "widgets",
                "home_settings",
                "sys_settings",
            ),
            identifiers(merged),
        )
        // Existing rows keep their saved enabled state; inserted rows use the default one.
        assertEquals(
            listOf(true, false, false, true, true, true, true, true, false),
            enabledFlags(merged),
        )
    }

    @Test
    fun `merge inserts organize_home before an edit_surface that #449 prepended`() {
        // #449 restored its entry with the old prepend behavior, so upgraded
        // installs can carry edit_surface at the front of their saved order.
        val saved = "+edit_surface|+carousel|-lock|-edit_mode|+wallpaper|+widgets|+home_settings|-sys_settings"
            .toLauncherOptions()

        val merged = mergeMissingPopupOptions(saved)

        assertEquals(
            listOf(
                "organize_home",
                "edit_surface",
                "carousel",
                "lock",
                "edit_mode",
                "wallpaper",
                "widgets",
                "home_settings",
                "sys_settings",
            ),
            identifiers(merged),
        )
    }

    @Test
    fun `merge keeps a user-reordered arrangement and inserts by DEFAULT_ORDER followers`() {
        val saved = "+wallpaper|-edit_mode|+carousel|-sys_settings|+widgets|-lock|+home_settings"
            .toLauncherOptions()

        val merged = mergeMissingPopupOptions(saved)

        assertEquals(
            listOf(
                "organize_home",
                "edit_surface",
                "wallpaper",
                "edit_mode",
                "carousel",
                "sys_settings",
                "widgets",
                "lock",
                "home_settings",
            ),
            identifiers(merged),
        )
        assertEquals(false, merged.first { it.identifier == "edit_mode" }.isEnabled)
        assertEquals(false, merged.first { it.identifier == "sys_settings" }.isEnabled)
    }

    @Test
    fun `merge returns an equal list when nothing is missing, the no-write guard condition`() {
        val current = DEFAULT_ORDER.toList().map { it.copy() }

        assertEquals(current, mergeMissingPopupOptions(current))
    }

    @Test
    fun `merge keeps unknown identifiers in place and skips them when inserting`() {
        val saved = (
            "+carousel|-lock|-edit_mode|+future_entry|+wallpaper|+widgets|+home_settings|-sys_settings"
            ).toLauncherOptions()

        val merged = mergeMissingPopupOptions(saved)

        // future_entry stays exactly where the user (or a newer build) left
        // it; the missing defaults insert before their first known follower,
        // which lands them after the unknown entry.
        assertEquals(
            listOf(
                "carousel",
                "lock",
                "edit_mode",
                "future_entry",
                "organize_home",
                "edit_surface",
                "wallpaper",
                "widgets",
                "home_settings",
                "sys_settings",
            ),
            identifiers(merged),
        )
    }

    @Test
    fun `visible filter hides the editing and organizing entries while the home screen is locked`() {
        val order = DEFAULT_ORDER.toList()

        val visible = filterVisiblePopupOptions(order, lockHomeScreen = true)

        // carousel never shows; edit_mode/edit_surface/organize_home/widgets
        // hide while locked; lock and sys_settings are disabled in the default
        // order, leaving wallpaper and home_settings visible.
        assertEquals(
            listOf("wallpaper", "home_settings"),
            identifiers(visible),
        )
    }

    @Test
    fun `visible filter keeps enabled non-carousel entries when unlocked and drops disabled ones`() {
        val order = "+carousel|-lock|-edit_mode|+organize_home|+edit_surface|+wallpaper|+widgets|+home_settings|-sys_settings"
            .toLauncherOptions()

        assertEquals(
            listOf("organize_home", "edit_surface", "wallpaper", "widgets", "home_settings"),
            identifiers(filterVisiblePopupOptions(order, lockHomeScreen = false)),
        )
    }
}
