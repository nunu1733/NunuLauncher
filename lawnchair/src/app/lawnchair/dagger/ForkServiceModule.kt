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
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import dagger.Module
import dagger.Provides

/**
 * Fork-owned NON-model service bindings (ADR-0018 Decision 9: allowed on
 * individual review). Each provider delegates to the fork-accepted accessor
 * and does not create or own any Launcher model/data instance; the model
 * identity (LauncherAppState/LauncherModel/BgDataModel) is provided solely
 * by the anchor DI constructors. This module intentionally replaces the
 * deleted ForkBridgeModule, whose model providers (LauncherAppState /
 * LauncherModel / BgDataModel via legacy accessors) are forbidden under
 * Decision 9. InvariantDeviceProfile is included because the fork IDP keeps
 * DeviceProfileOverrides-based constructors (S2/S3 port target) and has no
 * @Inject constructor yet.
 */
@Module
class ForkServiceModule {

    @Provides
    @LauncherAppSingleton
    fun provideIdp(@ApplicationContext context: Context): InvariantDeviceProfile = InvariantDeviceProfile.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideDeviceProfileOverrides(@ApplicationContext context: Context): DeviceProfileOverrides = DeviceProfileOverrides.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideHeadlessWidgetsManager(@ApplicationContext context: Context): HeadlessWidgetsManager = HeadlessWidgetsManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager = NotificationManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFolderService(@ApplicationContext context: Context): FolderService = FolderService.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideIconOverrideRepository(@ApplicationContext context: Context): IconOverrideRepository = IconOverrideRepository.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideWallpaperService(@ApplicationContext context: Context): WallpaperService = WallpaperService.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFontCache(@ApplicationContext context: Context): FontCache = FontCache.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideFontManager(@ApplicationContext context: Context): FontManager = FontManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideGoogleFontsListing(@ApplicationContext context: Context): GoogleFontsListing = GoogleFontsListing.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideLawnchairIconProvider(@ApplicationContext context: Context): LawnchairIconProvider = LawnchairIconProvider(context)

    @Provides
    @LauncherAppSingleton
    fun provideIconShapeManager(@ApplicationContext context: Context): IconShapeManager = IconShapeManager(context)

    @Provides
    @LauncherAppSingleton
    fun providePreferenceManager2(@ApplicationContext context: Context): PreferenceManager2 = PreferenceManager2.getInstance(context)

    @Provides
    @LauncherAppSingleton
    fun provideSmartspaceProvider(@ApplicationContext context: Context): SmartspaceProvider = SmartspaceProvider.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideThemeProvider(@ApplicationContext context: Context): ThemeProvider = ThemeProvider.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideColorPreferenceModelList(@ApplicationContext context: Context): ColorPreferenceModelList = ColorPreferenceModelList(context)

    @Provides
    @LauncherAppSingleton
    fun provideLiveInformationManager(@ApplicationContext context: Context): LiveInformationManager = LiveInformationManager.INSTANCE.get(context)

    @Provides
    @LauncherAppSingleton
    fun provideLawnchairWindowManagerProxy(): LawnchairWindowManagerProxy = LawnchairWindowManagerProxy()
}
