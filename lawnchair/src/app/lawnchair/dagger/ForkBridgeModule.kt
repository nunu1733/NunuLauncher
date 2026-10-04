package app.lawnchair.dagger

import android.content.Context
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.HeadlessWidgetsManager
import app.lawnchair.NotificationManager
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.data.wallpaper.service.WallpaperService
import app.lawnchair.font.FontCache
import app.lawnchair.font.FontManager
import app.lawnchair.font.googlefonts.GoogleFontsListing
import app.lawnchair.icons.LawnchairIconProvider
import app.lawnchair.icons.shape.IconShapeManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.provider.SmartspaceProvider
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreferenceModelList
import app.lawnchair.ui.preferences.data.liveinfo.LiveInformationManager
import app.lawnchair.util.LawnchairWindowManagerProxy
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.model.BgDataModel
import dagger.Module
import dagger.Provides

/**
 * Rebase Phase 2 bridge: the fork (main) resolves these singletons through
 * MainThreadInitializedObject accessors instead of Dagger `@Inject` constructors,
 * while the anchor's component graph exposes them as bindings. Each provider
 * delegates to the fork-accepted accessor so both worlds keep their contract.
 */
@Module
class ForkBridgeModule {

    @Provides
    @LauncherAppSingleton
    fun provideLauncherAppState(@ApplicationContext context: Context): LauncherAppState =
        LauncherAppState.getInstance(context)

    @Provides
    @LauncherAppSingleton
    fun provideLauncherModel(app: LauncherAppState): LauncherModel = app.model

    @Provides
    @LauncherAppSingleton
    fun provideBgDataModel(app: LauncherAppState): BgDataModel = app.model.mBgDataModel

    @Provides
    @LauncherAppSingleton
    fun provideIdp(@ApplicationContext context: Context): InvariantDeviceProfile =
        InvariantDeviceProfile.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideDeviceProfileOverrides(@ApplicationContext context: Context): DeviceProfileOverrides =
        DeviceProfileOverrides.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideHeadlessWidgetsManager(@ApplicationContext context: Context): HeadlessWidgetsManager =
        HeadlessWidgetsManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        NotificationManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFolderService(@ApplicationContext context: Context): FolderService =
        FolderService.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideIconOverrideRepository(@ApplicationContext context: Context): IconOverrideRepository =
        IconOverrideRepository.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideWallpaperService(@ApplicationContext context: Context): WallpaperService =
        WallpaperService.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFontCache(@ApplicationContext context: Context): FontCache =
        FontCache.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFontManager(@ApplicationContext context: Context): FontManager =
        FontManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideGoogleFontsListing(@ApplicationContext context: Context): GoogleFontsListing =
        GoogleFontsListing.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideLawnchairIconProvider(@ApplicationContext context: Context): LawnchairIconProvider =
        LawnchairIconProvider(context)

    @Provides
    @LauncherAppSingleton
    fun provideIconShapeManager(@ApplicationContext context: Context): IconShapeManager =
        IconShapeManager(context)

    @Provides
    @LauncherAppSingleton
    fun providePreferenceManager2(@ApplicationContext context: Context): PreferenceManager2 =
        PreferenceManager2.getInstance(context)

    @Provides
    @LauncherAppSingleton
    fun provideSmartspaceProvider(@ApplicationContext context: Context): SmartspaceProvider =
        SmartspaceProvider.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideThemeProvider(@ApplicationContext context: Context): ThemeProvider =
        ThemeProvider.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideColorPreferenceModelList(@ApplicationContext context: Context): ColorPreferenceModelList =
        ColorPreferenceModelList(context)

    @Provides
    @LauncherAppSingleton
    fun provideLiveInformationManager(@ApplicationContext context: Context): LiveInformationManager =
        LiveInformationManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideLawnchairWindowManagerProxy(@ApplicationContext context: Context): LawnchairWindowManagerProxy =
        LawnchairWindowManagerProxy.INSTANCE.get(context)
}
