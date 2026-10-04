package app.lawnchair.organizer.integration.exchange

import android.content.Context
import app.lawnchair.organizer.integration.AndroidPendingImportedIntentStore
import app.lawnchair.organizer.personalization.PendingImportedIntentStore

/**
 * Issue #374: process-local accessor for the durable pending imported intent
 * store (spec 374). Follows the module-per-concern object convention
 * (`ExchangeSessionStoreModule` / `CategoryOverrideStoreModule`); the store
 * itself is the #374-owned `AndroidPendingImportedIntentStore`: app-private,
 * backup-excluded, single active record, with the export session as the
 * validity master.
 */
object PendingImportedIntentModule {

    @Volatile
    private var instance: PendingImportedIntentStore? = null

    fun store(context: Context): PendingImportedIntentStore {
        return instance ?: synchronized(this) {
            instance ?: AndroidPendingImportedIntentStore(context.applicationContext).also { instance = it }
        }
    }
}
