package app.lawnchair.icons

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import androidx.annotation.DrawableRes
import com.android.launcher3.R
import com.android.launcher3.icons.mono.ThemedIconDrawable as MonoThemedIconDrawable
import java.util.Calendar

/**
 * Rebase Phase 2 adapt (#532): the anchor removed `com.android.launcher3.icons.ThemedIconDrawable`
 * (ComponentName-keyed theme maps, ThemeData.wrapDrawable) in favor of the monochrome-layer +
 * MonoIconThemeController pipeline (`com.android.launcher3.icons.mono.ThemedIconDrawable`).
 * The fork's icon pack theming still needs the old surface, so it is provided here while the
 * rendering itself is delegated to the new pipeline (theme data becomes the adaptive icon's
 * monochrome layer and MonoIconThemeController produces the themed bitmap).
 */
object ThemedIconDrawable {
    const val TAG = "ThemedIconDrawable"

    const val ICON_TYPE_DEFAULT = 0
    const val ICON_TYPE_CALENDAR = 1
    const val ICON_TYPE_CLOCK = 2

    const val THEMED_ICON_MAP_FILE = "grayscale_icon_map"
    const val TAG_ICON = "icon"
    const val ATTR_PACKAGE = "package"
    const val ATTR_COMPONENT = "component"
    const val ATTR_DRAWABLE = "drawable"

    private const val ID_NULL = 0

    /** Get an int array representing background and foreground colors for themed icons. */
    @JvmStatic
    fun getThemedColors(context: Context): IntArray = MonoThemedIconDrawable.getColors(context)

    /**
     * Returns the per-day dynamic calendar themed icon resource for the launcher package.
     */
    @SuppressLint("DiscouragedApi")
    @DrawableRes
    fun getDynamicCalendarResource(context: Context): Int {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return context.resources.getIdentifier(
            "themed_icon_calendar_$day",
            "drawable",
            context.packageName,
        )
    }

    /**
     * Returns theme data for dynamic calendars not covered by the static icon map.
     */
    fun getDynamicIconsFromMap(
        context: Context,
        themeMap: Map<ComponentName, ThemeData>,
        componentName: ComponentName,
    ): ThemeData? {
        if (!isDynamicCalendarPackage(context, componentName.packageName)) return null
        val resId = getDynamicCalendarResource(context)
        if (resId == ID_NULL) return null
        return ThemeData(context.resources, componentName.packageName, resId)
    }

    /**
     * Adds theme data entries for dynamic calendars that are missing from the parsed icon map.
     */
    fun updateMapWithDynamicIcons(
        context: Context,
        map: MutableMap<ComponentName, ThemeData>,
    ) {
        val resId = getDynamicCalendarResource(context)
        if (resId == ID_NULL) return
        dynamicCalendarPackages(context)
            .map { ComponentName(it, "") }
            .filter { map[it] == null }
            .forEach { pkg ->
                map[pkg] = ThemeData(context.resources, pkg.packageName, resId)
            }
    }

    private fun dynamicCalendarPackages(context: Context): List<String> = context.resources.getStringArray(R.array.dynamic_calendar_components_name).toList()

    private fun isDynamicCalendarPackage(context: Context, packageName: String): Boolean = dynamicCalendarPackages(context).any { it.equals(packageName, ignoreCase = true) }

    class ThemeData(
        val resources: Resources,
        val packageName: String,
        @DrawableRes val resId: Int,
    ) {

        /**
         * The monochrome layer describing this theme entry, padded to the adaptive icon
         * inset. Rendered (tinted) by MonoIconThemeController when themed icons are enabled.
         */
        fun loadPaddedDrawable(): Drawable? {
            if (resources.getResourceTypeName(resId) != "drawable") return null
            val d = resources.getDrawable(resId, null).mutate()
            val inset = InsetDrawable(d, 0.2f)
            val extraInsetFraction = CustomAdaptiveIconDrawable.getExtraInsetFraction()
            val extraInset = extraInsetFraction / (1 + 2 * extraInsetFraction)
            return InsetDrawable(inset, extraInset)
        }

        /**
         * Attaches this theme data to [original] as its monochrome layer, mirroring the old
         * ThemedIconDrawable.ThemeData.wrapDrawable semantics. Non-adaptive icons are returned
         * unchanged (matching the anchor's IconProvider behavior).
         */
        fun wrapDrawable(original: Drawable, iconType: Int): Drawable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return original
            if (original !is AdaptiveIconDrawable) return original
            if (original.monochrome != null) return original

            var effectiveResId = resId
            when (resources.getResourceTypeName(resId)) {
                "array" -> {
                    when (iconType) {
                        ICON_TYPE_CALENDAR -> {
                            val ta = resources.obtainTypedArray(resId)
                            val id = ta.getResourceId(
                                Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
                                ID_NULL,
                            )
                            ta.recycle()
                            if (id == ID_NULL) return original
                            effectiveResId = id
                            val themed = resources.getResourceTypeName(id)
                            if (themed != "drawable") return original
                        }

                        else -> return original
                    }
                }

                "drawable" -> Unit

                else -> return original
            }

            val mono = ThemeData(resources, packageName, effectiveResId).loadPaddedDrawable()
                ?: return original
            return CustomAdaptiveIconDrawable(
                original.background,
                original.foreground,
                mono,
            )
        }

        override fun toString(): String = "ThemeData(pkg=$packageName, resId=$resId)"
    }
}
