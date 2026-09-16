package app.lawnchair.organizer.integration.exchange

import android.content.Context
import app.lawnchair.organizer.integration.AndroidExportSessionStore
import app.lawnchair.organizer.personalization.ExportSessionStore

/**
 * Issue #205: process-local accessor for the #204 durable export session
 * store. Follows the module-per-concern object convention
 * (`LayoutStrategySelectionModule` / `CategoryOverrideStoreModule`); the store
 * itself remains #204-owned (`AndroidExportSessionStore`: app-private,
 * backup-excluded, single active session).
 */
object ExchangeSessionStoreModule {

    @Volatile
    private var instance: ExportSessionStore? = null

    fun store(context: Context): ExportSessionStore {
        return instance ?: synchronized(this) {
            instance ?: AndroidExportSessionStore(context.applicationContext).also { instance = it }
        }
    }
}
