package app.lawnchair.preferences

import android.content.Context
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.model.ModelDbController
import dagger.Module
import dagger.Provides

/**
 * Rebase Phase 2 bridge: the fork's PreferenceManager keeps a private
 * constructor with a companion accessor (fork-accepted contract), while the
 * anchor component graph exposes it as a singleton binding.
 */
@Module
class PreferenceManagerModule {
    @Provides
    @LauncherAppSingleton
    fun providePreferenceManager(@ApplicationContext context: Context): PreferenceManager =
        PreferenceManager.getInstance(context)

    /**
     * Rebase Phase 2 bridge: the fork's ModelDbController keeps its
     * GridMigrationRuntime-driven constructor (spec 118 / grid migration split),
     * while the anchor component graph expects a singleton binding.
     */
    @Provides
    @LauncherAppSingleton
    fun provideModelDbController(@ApplicationContext context: Context): ModelDbController =
        ModelDbController(context)
}
