package app.lawnchair.dagger

import android.content.Context
import com.android.launcher3.dagger.ApplicationContext
import dagger.Module
import dagger.Provides

/** Rebase Phase 2 bridge: exposes the application context inside the preview sandbox. */
@Module
class PreviewContextModule {
    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context
}
