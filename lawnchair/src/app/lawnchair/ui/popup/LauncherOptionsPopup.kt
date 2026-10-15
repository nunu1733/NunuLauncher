package app.lawnchair.ui.popup

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.lawnchair.preferences2.PreferenceManager2.Companion.getInstance
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.logging.StatsLogManager.LauncherEvent
import com.android.launcher3.views.OptionsPopupView.OptionItem
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking

object LauncherOptionsPopup {
    val DEFAULT_ORDER = listOf(
        LauncherOptionPopupItem("carousel", true),
        LauncherOptionPopupItem("lock", false),
        LauncherOptionPopupItem("edit_mode", false),
        LauncherOptionPopupItem("organize_home", true),
        LauncherOptionPopupItem("edit_surface", true),
        LauncherOptionPopupItem("wallpaper", true),
        LauncherOptionPopupItem("widgets", true),
        LauncherOptionPopupItem("all_apps", true),
        LauncherOptionPopupItem("home_settings", true),
        LauncherOptionPopupItem("sys_settings", false),
    )

    // Issue #452: while the home screen is locked, the editing/organizing
    // entries stay out of the popup (the lock's own toggle remains).
    private val hiddenWhileLocked = setOf("edit_mode", "organize_home", "edit_surface", "widgets")

    fun restoreMissingPopupOptions(
        launcher: Launcher,
    ) {
        val prefs2 = getInstance(launcher)

        val currentOrder = prefs2.launcherPopupOrder.firstBlocking()
        val currentOptions = currentOrder.toLauncherOptions()
        val mergedOptions = mergeMissingPopupOptions(currentOptions)

        // Issue #452: skip the preference write when the merge changed nothing
        // (fresh installs and already-restored orders), instead of rewriting
        // the same value on every launch.
        if (mergedOptions == currentOptions) return

        prefs2.launcherPopupOrder.setBlocking(mergedOptions.toOptionOrderString())
    }

    /**
     * Returns [current] with the [DEFAULT_ORDER] entries it lacks inserted at
     * their DEFAULT_ORDER-relative position: before the first entry of
     * [current] that follows them in [DEFAULT_ORDER], or at the end when no
     * such entry exists. Missing entries insert in DEFAULT_ORDER sequence with
     * their default enabled state; existing entries keep their position and
     * enabled state exactly as saved (Issue #452).
     */
    fun mergeMissingPopupOptions(current: List<LauncherOptionPopupItem>): List<LauncherOptionPopupItem> {
        val present = current.map { it.identifier }.toSet()
        val missing = DEFAULT_ORDER.filter { it.identifier !in present }
        if (missing.isEmpty()) return current

        var merged = current.toList()
        for (item in missing) {
            val followers = DEFAULT_ORDER
                .subList(DEFAULT_ORDER.indexOf(item) + 1, DEFAULT_ORDER.size)
                .map { it.identifier }
                .toSet()
            val insertAt = merged.indexOfFirst { it.identifier in followers }
                .let { if (it == -1) merged.size else it }
            merged = merged.subList(0, insertAt) + item + merged.subList(insertAt, merged.size)
        }
        return merged
    }

    /**
     * Returns the option order entries that may show in the popup: enabled,
     * non-carousel, and not hidden while the home screen is locked.
     */
    fun filterVisiblePopupOptions(
        optionOrder: List<LauncherOptionPopupItem>,
        lockHomeScreen: Boolean,
    ): List<LauncherOptionPopupItem> = optionOrder
        .filter { it.isEnabled && it.identifier != "carousel" }
        .filter { !lockHomeScreen || it.identifier !in hiddenWhileLocked }

    /**
     * Returns the list of supported actions
     */
    fun getLauncherOptions(
        launcher: Launcher?,
        onLockToggle: (View) -> Boolean,
        onStartSystemSettings: (View) -> Boolean,
        onStartEditMode: (View) -> Boolean,
        onStartAllApps: (View) -> Boolean,
        onStartWallpaperPicker: (View) -> Boolean,
        onStartWidgetsMenu: (View) -> Boolean,
        onStartHomeSettings: (View) -> Boolean,
    ): ArrayList<OptionItem> {
        val prefs2 = getInstance(launcher!!)
        val lockHomeScreen = prefs2.lockHomeScreen.firstBlocking()
        val optionOrder = prefs2
            .launcherPopupOrder.firstBlocking().toLauncherOptions()

        val wallpaperResString =
            if (Utilities.existsStyleWallpapers(launcher)) R.string.styles_wallpaper_button_text else R.string.wallpapers
        val wallpaperResDrawable =
            if (Utilities.existsStyleWallpapers(launcher)) R.drawable.ic_palette else R.drawable.ic_wallpaper

        val optionsList = mapOf(
            "lock" to OptionItem(
                launcher,
                if (lockHomeScreen) R.string.home_screen_unlock else R.string.home_screen_lock,
                if (lockHomeScreen) R.drawable.ic_lock_open else R.drawable.ic_lock,
                LauncherEvent.IGNORE,
                onLockToggle,
            ),
            "sys_settings" to OptionItem(
                launcher,
                R.string.system_settings,
                R.drawable.ic_setting,
                LauncherEvent.IGNORE,
                onStartSystemSettings,
            ),
            "edit_mode" to OptionItem(
                launcher,
                R.string.edit_home_screen,
                R.drawable.enter_home_gardening_icon,
                LauncherEvent.LAUNCHER_SETTINGS_BUTTON_TAP_OR_LONGPRESS,
                onStartEditMode,
            ),
            // Issue #449: visual edit surface entry (ADR-0014 case B). The
            // handler stays inside this fork file, so the upstream bridge
            // signature is unchanged. Hidden while the home screen is locked
            // together with edit_mode/widgets below.
            "edit_surface" to OptionItem(
                launcher,
                R.string.edit_surface_menu_open,
                R.drawable.ic_folder,
                LauncherEvent.IGNORE,
                { view ->
                    app.lawnchair.homeedit.ui.HomeEditSurfaceActivity.start(view.context)
                    true
                },
            ),
            // Issue #452: direct organizer-run entry. The handler only opens
            // the settings activity at the manual-organization run route (the
            // same mechanism the onboarding proposal uses); run admission
            // stays exclusive to the run surface's start row. Hidden while the
            // home screen is locked together with edit_mode/edit_surface.
            "organize_home" to OptionItem(
                launcher,
                R.string.home_screen_organize,
                R.drawable.ic_organize_home,
                LauncherEvent.IGNORE,
                { view ->
                    view.context.startActivity(
                        PreferenceActivity.createIntent(
                            view.context,
                            HomeScreenManualOrganization(),
                        ),
                    )
                    true
                },
            ),
            "wallpaper" to OptionItem(
                launcher,
                wallpaperResString,
                wallpaperResDrawable,
                LauncherEvent.IGNORE,
                onStartWallpaperPicker,
            ),
            "widgets" to OptionItem(
                launcher,
                R.string.widget_button_text,
                R.drawable.ic_widget,
                LauncherEvent.LAUNCHER_WIDGETSTRAY_BUTTON_TAP_OR_LONGPRESS,
                onStartWidgetsMenu,
            ),
            // Rebase Phase 2 adapt (#532): anchor all-apps popup entry; the anchor
            // OptionsPopupView passes the handler through the restored signature.
            "all_apps" to OptionItem(
                launcher,
                R.string.all_apps_button_label,
                R.drawable.ic_apps,
                LauncherEvent.LAUNCHER_ALL_APPS_TAP_OR_LONGPRESS,
                onStartAllApps,
            ),
            "home_settings" to OptionItem(
                launcher,
                R.string.settings_button_text,
                R.drawable.ic_home_screen,
                LauncherEvent.LAUNCHER_SETTINGS_BUTTON_TAP_OR_LONGPRESS,
                onStartHomeSettings,
            ),
        )

        val options = ArrayList<OptionItem>()
        filterVisiblePopupOptions(optionOrder, lockHomeScreen)
            .mapNotNull { optionsList[it.identifier] }
            .forEach { options.add(it) }

        return options
    }

    fun getMetadataForOption(identifier: String): LauncherOptionMetadata {
        return when (identifier) {
            "carousel" -> LauncherOptionMetadata(
                label = R.string.wallpaper_quick_picker,
                icon = R.drawable.ic_wallpaper,
                isCarousel = true,
            )

            "lock" -> LauncherOptionMetadata(
                label = R.string.home_screen_lock,
                icon = R.drawable.ic_lock,
            )

            "sys_settings" -> LauncherOptionMetadata(
                label = R.string.system_settings,
                icon = R.drawable.ic_setting,
            )

            "edit_mode" -> LauncherOptionMetadata(
                label = R.string.edit_home_screen,
                icon = R.drawable.enter_home_gardening_icon,
            )

            "edit_surface" -> LauncherOptionMetadata(
                label = R.string.edit_surface_menu_open,
                icon = R.drawable.ic_folder,
            )

            "organize_home" -> LauncherOptionMetadata(
                label = R.string.home_screen_organize,
                icon = R.drawable.ic_organize_home,
            )

            "wallpaper" -> LauncherOptionMetadata(
                label = R.string.styles_wallpaper_button_text,
                icon = R.drawable.ic_palette,
            )

            "widgets" -> LauncherOptionMetadata(
                label = R.string.widget_button_text,
                icon = R.drawable.ic_widget,
            )

            "all_apps" -> LauncherOptionMetadata(
                label = R.string.all_apps_button_label,
                icon = R.drawable.ic_apps,
            )

            "home_settings" -> LauncherOptionMetadata(
                label = R.string.settings_button_text,
                icon = R.drawable.ic_home_screen,
            )

            else -> throw IllegalArgumentException("invalid popup option")
        }
    }

    fun migrateLegacyPreferences(
        launcher: Launcher,
    ) {
        val prefs2 = getInstance(launcher)

        val lockHomeScreenButtonOnPopUp = prefs2.lockHomeScreenButtonOnPopUp.firstBlocking()
        val editHomeScreenButtonOnPopUp = prefs2.editHomeScreenButtonOnPopUp.firstBlocking()
        val showSystemSettingsEntryOnPopUp = prefs2.showSystemSettingsEntryOnPopUp.firstBlocking()

        val optionOrder = prefs2.launcherPopupOrder
        val legacyPopupOptionsMigrated = prefs2.legacyPopupOptionsMigrated.firstBlocking()

        if (!legacyPopupOptionsMigrated) {
            prefs2.legacyPopupOptionsMigrated.setBlocking(true)

            val options = optionOrder.firstBlocking().toLauncherOptions()

            options.forEachIndexed { index, item ->
                if (item.identifier == "lock") {
                    options[index].isEnabled = lockHomeScreenButtonOnPopUp
                }
                if (item.identifier == "edit_mode") {
                    options[index].isEnabled = editHomeScreenButtonOnPopUp
                }
                if (item.identifier == "sys_settings") {
                    options[index].isEnabled = showSystemSettingsEntryOnPopUp
                }
            }

            optionOrder.setBlocking(options.toOptionOrderString())
        }
    }
}

data class LauncherOptionPopupItem(
    val identifier: String,
    var isEnabled: Boolean,
)

data class LauncherOptionMetadata(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val isCarousel: Boolean = false,
)

fun String.toLauncherOptions(): List<LauncherOptionPopupItem> {
    return this.split("|").map { item ->
        val (identifier, isEnabled) = when {
            item.startsWith("+") -> item.drop(1) to true
            item.startsWith("-") -> item.drop(1) to false
            else -> item to true // Default to enabled if no prefix
        }
        LauncherOptionPopupItem(identifier, isEnabled)
    }
}

fun List<LauncherOptionPopupItem>.toOptionOrderString(): String {
    return this.joinToString("|") {
        if (it.isEnabled) "+${it.identifier}" else "-${it.identifier}"
    }
}
