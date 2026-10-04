package app.lawnchair.icons

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_DATE_CHANGED
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_PACKAGE_CHANGED
import android.content.Intent.ACTION_PACKAGE_REMOVED
import android.content.Intent.ACTION_TIMEZONE_CHANGED
import android.content.Intent.ACTION_TIME_CHANGED
import android.content.Intent.ACTION_TIME_TICK
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageItemInfo
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import android.util.Log
import androidx.core.content.getSystemService
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.Constants.LAWNICONS_PACKAGE_NAME
import app.lawnchair.util.MultiSafeCloseable
import app.lawnchair.util.getPackageVersionCode
import app.lawnchair.util.isPackageInstalled
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.icons.IconProvider
import com.android.launcher3.reloadIcons
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.SafeCloseable
import org.xmlpull.v1.XmlPullParser

class LawnchairIconProvider(
    private val context: Context,
    supportsIconTheme: Boolean = false,
) : IconProvider(context) {

    private val prefs = PreferenceManager.getInstance(context)
    private val iconPackPref = prefs.iconPackPackage
    private val themedIconPackPref = prefs.themedIconPackPackage

    private val iconPackProvider = IconPackProvider.INSTANCE.get(context)
    private val overrideRepo = IconOverrideRepository.INSTANCE.get(context)

    private val iconPack
        get() = iconPackProvider.getIconPack(iconPackPref.get())?.apply { loadBlocking() }
    private val themedIconPack
        get() = iconPackProvider.getIconPack(themedIconPackPref.get())?.apply { loadBlocking() }

    private var isOlderLawniconsInstalled = context.packageManager.getPackageVersionCode(LAWNICONS_PACKAGE_NAME) in 1..3

    private var iconPackVersion = 0L

    private var themeMapName: String = ""
    private var _themeMap: Map<ComponentName, ThemedIconDrawable.ThemeData>? = null

    val themeMap: Map<ComponentName, ThemedIconDrawable.ThemeData>
        get() {
            if (!context.isThemedIconsEnabled()) {
                _themeMap = DISABLED_MAP
            }
            if (_themeMap == null) {
                _themeMap = createThemedIconMap()
            }
            if (isOlderLawniconsInstalled) {
                themeMapName = themedIconPackPref.get()
                _themeMap = createThemedIconMap()
            }
            if (themedIconPack != null && themeMapName != themedIconPack!!.packPackageName) {
                themeMapName = themedIconPack!!.packPackageName
                _themeMap = createThemedIconMap()
            }
            return _themeMap!!
        }
    private val supportsIconTheme get() = themeMap != DISABLED_MAP

    // Rebase Phase 2 adapt (#532): the anchor IconProvider no longer exposes a
    // setIconThemeSupported override; the flag only feeds isThemeEnabled now.
    val isThemeEnabled: Boolean
        get() = _themeMap != DISABLED_MAP

    init {
        setIconThemeSupported(supportsIconTheme)
    }

    private fun setIconThemeSupported(isSupported: Boolean) {
        _themeMap = if (isSupported && isOlderLawniconsInstalled) null else DISABLED_MAP
    }

    private fun resolveIconEntry(componentName: ComponentName, user: UserHandle): IconEntry? {
        val componentKey = ComponentKey(componentName, user)
        // first look for user-overridden icon
        val overrideItem = overrideRepo.overridesMap[componentKey]
        if (overrideItem != null) {
            return overrideItem.toIconEntry()
        }

        val iconPack = this.iconPack ?: return null
        // then look for dynamic calendar
        val calendarEntry = iconPack.getCalendar(componentName)
        if (calendarEntry != null) {
            return calendarEntry
        }
        // finally, look for normal icon
        return iconPack.getIcon(componentName)
    }

    /**
     * Rebase Phase 2 adapt (#532): the anchor pipeline reaches icon loading through
     * [IconProvider.getIcon] (PackageItemInfo + ApplicationInfo); the old
     * getIconWithOverrides hook no longer exists on the base class. Fork icon-pack
     * and icon-override resolution happens here instead.
     */
    override fun getIcon(info: PackageItemInfo, appInfo: ApplicationInfo, iconDpi: Int): Drawable {
        val componentName = ComponentName(info.packageName, info.name ?: "")
        val user = UserHandle.getUserHandleForUid(appInfo.uid)
        val iconEntry = resolveIconEntry(componentName, user)
            ?: return tintedSuperIcon(info, appInfo, iconDpi, componentName)

        var resolvedEntry = iconEntry
        var iconType = ThemedIconDrawable.ICON_TYPE_DEFAULT
        var themeData: ThemedIconDrawable.ThemeData? = null
        val clock = iconPackProvider.getClockMetadata(iconEntry)
        when {
            iconEntry.type == IconType.Calendar -> {
                resolvedEntry = iconEntry.resolveDynamicCalendar(getDay())
                mCalendar?.let { themeData = getThemeData(ComponentName(it.packageName, "")) }
                iconType = ThemedIconDrawable.ICON_TYPE_CALENDAR
            }

            !supportsIconTheme -> {
                // theming is disabled, don't populate theme data
            }

            clock != null -> {
                // the icon supports dynamic clock, use dynamic themed clock
                mClock?.let { themeData = getThemeData(ComponentName(it.packageName, "")) }
                iconType = ThemedIconDrawable.ICON_TYPE_CLOCK
            }

            info.packageName == mClock?.packageName -> {
                // is clock app but icon might not be adaptive, fallback to static themed clock
                themeData = ThemedIconDrawable.ThemeData(
                    context.resources,
                    BuildConfig.APPLICATION_ID,
                    R.drawable.themed_icon_static_clock,
                )
            }

            info.packageName == mCalendar?.packageName -> {
                // calendar app, apply the dynamic calendar icon
                mCalendar?.let { themeData = getThemeData(ComponentName(it.packageName, "")) }
                iconType = ThemedIconDrawable.ICON_TYPE_CALENDAR
            }

            else -> {
                // regular icon
                themeData = getThemeData(componentName)
            }
        }
        val icon = resolvedEntry?.let { iconPackProvider.getDrawable(it, iconDpi, user) }
            ?: return tintedSuperIcon(info, appInfo, iconDpi, componentName)
        val td = themeData
        return if (td != null) td.wrapDrawable(icon, iconType) else icon
    }

    private fun tintedSuperIcon(
        info: PackageItemInfo,
        appInfo: ApplicationInfo,
        iconDpi: Int,
        componentName: ComponentName,
    ): Drawable {
        val defaultIcon = super.getIcon(info, appInfo, iconDpi)
        if (context.shouldTintIconPackBackgrounds() && defaultIcon is AdaptiveIconDrawable) {
            return tintMonochrome(context, defaultIcon, componentName)
        }
        return defaultIcon
    }

    private fun getThemeData(componentName: ComponentName): ThemedIconDrawable.ThemeData? {
        val td = ThemedIconDrawable.getDynamicIconsFromMap(context, themeMap, componentName)
        if (td != null) {
            return td
        }
        return themeMap[componentName] ?: themeMap[ComponentName(componentName.packageName, "")]
    }

    // Rebase Phase 2 adapt (#532): fold the old getSystemStateForPackage/getSystemIconState
    // overrides into updateSystemState; the base no longer exposes those hooks. The fork
    // suffix is stripped before re-appending so repeated cache updates stay idempotent.
    private var appendedForkState: String? = null

    val systemIconState: String
        get() = "$isThemeEnabled,pack:${iconPackPref.get()}/${themedIconPackPref.get()},ver:$iconPackVersion"

    override fun updateSystemState() {
        appendedForkState?.let { stripped ->
            if (mSystemState.endsWith(stripped)) {
                mSystemState = mSystemState.removeSuffix(stripped)
            }
        }
        super.updateSystemState()
        if (context.packageManager.isPackageInstalled(packageName = themeMapName)) {
            iconPackVersion = context.packageManager.getPackageVersionCode(themeMapName)
        }
        appendedForkState = ",$systemIconState"
        mSystemState += appendedForkState
    }

    override fun registerIconChangeListener(
        callback: IconChangeListener,
        handler: Handler,
    ): SafeCloseable {
        return MultiSafeCloseable().apply {
            add(super.registerIconChangeListener(callback, handler))
            add(IconPackChangeReceiver(context, handler, callback))
            add(LawniconsChangeReceiver(context, handler, callback))
        }
    }

    // Rebase Phase 2 adapt (#532): IconChangeListener no longer has
    // onSystemIconStateChanged; icon pack swaps notify through a launcher reload.
    private fun notifyIconsChanged() {
        LauncherAppState.getInstanceNoCreate(context)?.reloadIcons()
    }

    private inner class IconPackChangeReceiver(
        private val context: Context,
        private val handler: Handler,
        private val callback: IconChangeListener,
    ) : SafeCloseable {

        private var calendarAndClockChangeReceiver: CalendarAndClockChangeReceiver? = null
            set(value) {
                field?.close()
                field = value
            }
        private var iconState = systemIconState
        private val iconPackPref = PreferenceManager.getInstance(context).iconPackPackage
        private val themedIconPackPref = PreferenceManager.getInstance(context).themedIconPackPackage

        private val subscription = iconPackPref.subscribeChanges {
            val newState = systemIconState
            if (iconState != newState) {
                iconState = newState
                notifyIconsChanged()
                recreateCalendarAndClockChangeReceiver()
            }
        }
        private val themedIconSubscription = themedIconPackPref.subscribeChanges {
            val newState = systemIconState
            if (iconState != newState) {
                iconState = newState
                notifyIconsChanged()
                recreateCalendarAndClockChangeReceiver()
            }
        }

        init {
            recreateCalendarAndClockChangeReceiver()
        }

        private fun recreateCalendarAndClockChangeReceiver() {
            val iconPack = IconPackProvider.INSTANCE.get(context).getIconPack(iconPackPref.get())
            calendarAndClockChangeReceiver = if (iconPack != null) {
                CalendarAndClockChangeReceiver(context, handler, iconPack, callback)
            } else {
                null
            }
        }

        override fun close() {
            calendarAndClockChangeReceiver = null
            subscription.close()
            themedIconSubscription.close()
        }
    }

    private class CalendarAndClockChangeReceiver(
        private val context: Context,
        handler: Handler,
        private val iconPack: IconPack,
        private val callback: IconChangeListener,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_TIMEZONE_CHANGED)
            filter.addAction(ACTION_TIME_TICK)
            filter.addAction(ACTION_TIME_CHANGED)
            filter.addAction(ACTION_DATE_CHANGED)
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMEZONE_CHANGED, ACTION_TIME_CHANGED, ACTION_TIME_TICK -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getClocks().forEach { componentName ->
                            callback.onAppIconChanged(
                                componentName.packageName,
                                user,
                            )
                        }
                    }
                }

                ACTION_DATE_CHANGED -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getCalendars().forEach { componentName ->
                            callback.onAppIconChanged(componentName.packageName, user)
                        }
                    }
                }
            }
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    private inner class LawniconsChangeReceiver(
        private val context: Context,
        handler: Handler,
        @Suppress("UNUSED_PARAMETER") private val callback: IconChangeListener,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_PACKAGE_ADDED)
            filter.addAction(ACTION_PACKAGE_CHANGED)
            filter.addAction(ACTION_PACKAGE_REMOVED)
            filter.addDataScheme("package")
            filter.addDataSchemeSpecificPart(themeMapName, 0)
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (isThemeEnabled) {
                setIconThemeSupported(true)
            }
            notifyIconsChanged()
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    private fun createThemedIconMap(): MutableMap<ComponentName, ThemedIconDrawable.ThemeData> {
        val map = ArrayMap<ComponentName, ThemedIconDrawable.ThemeData>()

        fun updateMapFromResources(resources: Resources, packageName: String) {
            try {
                @SuppressLint("DiscouragedApi")
                val xmlId = resources.getIdentifier(ThemedIconDrawable.THEMED_ICON_MAP_FILE, "xml", packageName)
                if (xmlId != 0) {
                    val parser = resources.getXml(xmlId)
                    val depth = parser.depth
                    var type: Int
                    while (
                        (parser.next().also { type = it } != XmlPullParser.END_TAG || parser.depth > depth) &&
                        type != XmlPullParser.END_DOCUMENT
                    ) {
                        if (type != XmlPullParser.START_TAG) continue
                        if (ThemedIconDrawable.TAG_ICON == parser.name) {
                            val pkg = parser.getAttributeValue(null, ThemedIconDrawable.ATTR_PACKAGE)
                            val cmp = parser.getAttributeValue(null, ThemedIconDrawable.ATTR_COMPONENT).orEmpty()
                            val iconId = parser.getAttributeResourceValue(null, ThemedIconDrawable.ATTR_DRAWABLE, 0)
                            if (iconId != 0 && pkg.isNotEmpty()) {
                                map[ComponentName(pkg, cmp)] = ThemedIconDrawable.ThemeData(resources, packageName, iconId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to parse icon map.", e)
            }
        }
        updateMapFromResources(
            resources = context.resources,
            packageName = context.packageName,
        )
        if (context.packageManager.isPackageInstalled(packageName = themeMapName)) {
            iconPackVersion = context.packageManager.getPackageVersionCode(themeMapName)
            updateMapFromResources(
                resources = context.packageManager.getResourcesForApplication(themeMapName),
                packageName = themeMapName,
            )
            if (isOlderLawniconsInstalled) {
                ThemedIconDrawable.updateMapWithDynamicIcons(context, map)
            }
        }

        return map
    }

    companion object {
        const val TAG = "LawnchairIconProvider"

        val DISABLED_MAP = emptyMap<ComponentName, ThemedIconDrawable.ThemeData>()

        /**
         * Rebase Phase 2 adapt (#532): kept for the fork's tint-backgrounds flow; extracts
         * the monochrome layer (or falls back to a pack-provided monochrome resource) and
         * applies the themed colors.
         */
        fun tintMonochrome(context: Context, defaultIcon: AdaptiveIconDrawable, componentName: ComponentName): Drawable {
            val themedColors = ThemedIconDrawable.getThemedColors(context)
            if (Utilities.ATLEAST_T && defaultIcon.monochrome != null) {
                val mono = defaultIcon.monochrome ?: return defaultIcon
                mono.setTint(themedColors[1])
                if (context.shouldTransparentBGIcons()) return mono
                return CustomAdaptiveIconDrawable(
                    ColorDrawable(themedColors[0]),
                    mono,
                )
            }
            val iconCompat = ThemedIconCompat.getThemedIcon(context, componentName) ?: return defaultIcon
            iconCompat.setTint(themedColors[1])
            if (context.shouldTransparentBGIcons()) return iconCompat
            return CustomAdaptiveIconDrawable(
                ColorDrawable(themedColors[0]),
                iconCompat,
            )
        }
    }
}
