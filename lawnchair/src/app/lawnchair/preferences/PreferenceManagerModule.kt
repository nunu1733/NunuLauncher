package app.lawnchair.preferences

import android.content.Context
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import dagger.Module
import dagger.Provides

/**
 * Fork-owned non-model service binding (ADR-0018 Decision 9 allows these on
 * individual review): the fork's PreferenceManager keeps a private
 * constructor with a companion accessor, so it is exposed to the anchor
 * component graph via a small @Provides. It does not create or own any
 * Launcher model/data instance.
 */
@Module
class PreferenceManagerModule {
    @Provides
    @LauncherAppSingleton
    fun providePreferenceManager(@ApplicationContext context: Context): PreferenceManager =
        PreferenceManager.getInstance(context)
}
