package app.lawnchair

import android.content.Context
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstBlocking
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.InvariantDeviceProfile.INDEX_DEFAULT
import com.android.launcher3.InvariantDeviceProfile.INDEX_LANDSCAPE
import com.android.launcher3.InvariantDeviceProfile.INDEX_TWO_PANEL_LANDSCAPE
import com.android.launcher3.InvariantDeviceProfile.INDEX_TWO_PANEL_PORTRAIT
import com.android.launcher3.InvariantDeviceProfile.TYPE_MULTI_DISPLAY
import com.android.launcher3.InvariantDeviceProfile.TYPE_PHONE
import com.android.launcher3.InvariantDeviceProfile.TYPE_TABLET
import com.android.launcher3.logging.FileLog
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.SafeCloseable
import com.patrykmichalik.opto.core.firstBlocking

class DeviceProfileOverrides(context: Context) : SafeCloseable {
    private val appContext = context.applicationContext
    private val prefs = PreferenceManager.getInstance(context)
    private val preferenceManager2 = PreferenceManager2.getInstance(context)

    fun getGridInfo() = DBGridInfo(prefs).also {
        // Issue #168: log the raw pref values behind every grid recompute so the
        // restore-window interleaving (Open item A of the Nova two-pass restore
        // investigation) is readable from logcat alone.
        FileLog.d(
            TAG,
            "Grid recompute from prefs: rows=${it.numRows} columns=${it.numColumns} " +
                "hotseat=${it.numHotseatColumns}",
        )
    }

    fun getGridInfo(gridName: String): DBGridInfo {
        val presets = enabledPresets()
        return presets.firstOrNull { it.name == gridName }?.grid
            ?: throw NoSuchElementException(
                "grid preset \"$gridName\" is not enabled for deviceType=${currentDeviceType()}; " +
                    "enabled presets=${presets.map { it.name }}",
            )
    }

    fun getGridName(gridInfo: DBGridInfo): String = ceilingMatchPreset(enabledPresets(), gridInfo).name

    fun getCurrentGridName() = getGridName(getGridInfo())

    fun setCurrentGrid(gridName: String) {
        val gridInfo = getGridInfo(gridName)
        prefs.workspaceRows.set(gridInfo.numRows)
        prefs.workspaceColumns.set(gridInfo.numColumns)
        prefs.hotseatColumns.set(gridInfo.numHotseatColumns)
    }

    fun getOverrides(defaultGrid: InvariantDeviceProfile.GridOption) = Options(
        prefs = prefs,
        prefs2 = preferenceManager2,
        defaultGrid = defaultGrid,
    )

    fun getTextFactors() = TextFactors(preferenceManager2)
    override fun close() {
        TODO("Not yet implemented")
    }

    // Issue #134: the enabled-preset inventory is resolved against the current device
    // type at query time. A construction-time snapshot freezes phone-category presets
    // because InvariantDeviceProfile sets its static deviceType only inside initGrid,
    // after both grid-driven constructors have already touched this singleton.
    private fun enabledPresets(): List<DeclaredGridPreset> = resolveEnabledPresets(
        InvariantDeviceProfile.parseAllDefinedGridOptions(appContext)
            .map { option ->
                DeclaredGridPreset(
                    name = option.name,
                    grid = DBGridInfo(option.numHotseatIcons, option.numRows, option.numColumns),
                    enabledDeviceTypes = DEVICE_TYPES.filterTo(mutableSetOf()) { option.isEnabled(it) },
                )
            },
        currentDeviceType(),
    )

    private fun currentDeviceType(): Int = DisplayController.INSTANCE.get(appContext).getInfo().getDeviceType()

    data class DBGridInfo(
        val numHotseatColumns: Int,
        val numRows: Int,
        val numColumns: Int,
    ) {
        val dbFile get() = "launcher_${numRows}_${numColumns}_$numHotseatColumns.db"

        constructor(prefs: PreferenceManager) : this(
            numHotseatColumns = prefs.hotseatColumns.get(),
            numRows = prefs.workspaceRows.get(),
            numColumns = prefs.workspaceColumns.get(),
        )
    }

    /**
     * Platform-free declaration of one grid preset. [enabledDeviceTypes] mirrors
     * `GridOption.isEnabled` over the known device types so the pure companion
     * seam stays the single production filter path.
     */
    data class DeclaredGridPreset(
        val name: String,
        val grid: DBGridInfo,
        val enabledDeviceTypes: Set<Int>,
    )

    data class Options(
        val numAllAppsColumns: Int,
        val numFolderRows: Int,
        val numFolderColumns: Int,

        val iconSizeFactor: Float,
        val allAppsIconSizeFactor: Float,

        val enableTaskbarOnPhone: Boolean,
    ) {
        constructor(
            prefs: PreferenceManager,
            prefs2: PreferenceManager2,
            defaultGrid: InvariantDeviceProfile.GridOption,
        ) : this(
            numAllAppsColumns = prefs2.drawerColumns.firstBlocking(gridOption = defaultGrid),
            numFolderRows = prefs.folderRows.get(defaultGrid),
            numFolderColumns = prefs2.folderColumns.firstBlocking(gridOption = defaultGrid),

            iconSizeFactor = prefs2.homeIconSizeFactor.firstBlocking(),
            allAppsIconSizeFactor = prefs2.drawerIconSizeFactor.firstBlocking(),

            enableTaskbarOnPhone = prefs2.enableTaskbarOnPhone.firstBlocking(),
        )

        fun applyUi(idp: InvariantDeviceProfile) {
            // apply grid size
            idp.numAllAppsColumns = numAllAppsColumns
            idp.numDatabaseAllAppsColumns = numAllAppsColumns
            idp.numFolderRows[INDEX_DEFAULT] = numFolderRows
            idp.numFolderColumns[INDEX_DEFAULT] = numFolderColumns

            // apply icon and text size
            idp.iconSize[INDEX_DEFAULT] *= iconSizeFactor
            idp.iconSize[INDEX_LANDSCAPE] *= iconSizeFactor
            idp.iconSize[INDEX_TWO_PANEL_PORTRAIT] *= iconSizeFactor
            idp.iconSize[INDEX_TWO_PANEL_LANDSCAPE] *= iconSizeFactor

            idp.allAppsIconSize[INDEX_DEFAULT] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_LANDSCAPE] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_TWO_PANEL_PORTRAIT] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_TWO_PANEL_LANDSCAPE] *= allAppsIconSizeFactor
        }
    }

    data class TextFactors(
        val iconTextSizeFactor: Float,
        val allAppsIconTextSizeFactor: Float,
        val iconFolderTextSizeFactor: Float,
    ) {
        constructor(
            prefs2: PreferenceManager2,
        ) : this(
            enableIconText = prefs2.showIconLabelsOnHomeScreen.firstBlocking(),
            iconTextSizeFactor = prefs2.homeIconLabelSizeFactor.firstBlocking(),
            enableIconTextFolder = prefs2.showIconLabelsOnHomeScreenFolder.firstBlocking(),
            iconFolderTextSizeFactor = prefs2.homeIconLabelFolderSizeFactor.firstBlocking(),
            enableAllAppsIconText = prefs2.showIconLabelsInDrawer.firstBlocking(),
            allAppsIconTextSizeFactor = prefs2.drawerIconLabelSizeFactor.firstBlocking(),
        )

        constructor(
            enableIconText: Boolean,
            iconTextSizeFactor: Float,
            enableIconTextFolder: Boolean,
            iconFolderTextSizeFactor: Float,
            enableAllAppsIconText: Boolean,
            allAppsIconTextSizeFactor: Float,
        ) : this(
            iconTextSizeFactor = if (enableIconText) iconTextSizeFactor else 0f,
            allAppsIconTextSizeFactor = if (enableAllAppsIconText) allAppsIconTextSizeFactor else 0f,
            iconFolderTextSizeFactor = if (enableIconTextFolder) iconFolderTextSizeFactor else 0f,
        )
    }

    companion object {
        private const val TAG = "DeviceProfileOverrides"

        @JvmField
        val INSTANCE = MainThreadInitializedObject(::DeviceProfileOverrides)

        private val DEVICE_TYPES = intArrayOf(TYPE_PHONE, TYPE_TABLET, TYPE_MULTI_DISPLAY)

        /** Enabled-preset inventory for [deviceType], preserving declaration order. */
        fun resolveEnabledPresets(declared: List<DeclaredGridPreset>, deviceType: Int): List<DeclaredGridPreset> = declared.filter { deviceType in it.enabledDeviceTypes }

        /**
         * Deterministic ceiling match over a non-empty inventory: the first declared preset
         * whose rows and columns both fit [target], else the last enabled preset. The
         * fallback is the documented approximation for live dimensions that no enabled
         * preset matches exactly (spec 134, scenario "current grid name").
         */
        fun ceilingMatchPreset(presets: List<DeclaredGridPreset>, target: DBGridInfo): DeclaredGridPreset {
            require(presets.isNotEmpty()) { "enabled grid preset inventory must not be empty" }
            return presets.firstOrNull {
                it.grid.numRows >= target.numRows && it.grid.numColumns >= target.numColumns
            }
                ?: presets.last()
        }
    }
}
