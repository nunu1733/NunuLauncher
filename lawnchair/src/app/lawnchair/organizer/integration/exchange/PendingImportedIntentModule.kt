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

    @Volatile
    private var gateInstance: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate? = null

    fun store(context: Context): PendingImportedIntentStore {
        return instance ?: synchronized(this) {
            instance ?: AndroidPendingImportedIntentStore(context.applicationContext).also { instance = it }
        }
    }

    /**
     * Issue #375: THE process-wide exchange mutation gate — shared by the
     * exchange holder (durable writes, discard, invalidation cleanups, the
     * rebind admission anchor), the flow controller (replacement commit,
     * pre-send invalidation) and the startup reconcile, so no record/session
     * mutation can interleave with the rebind admission's fresh verification.
     */
    fun gate(): app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate {
        return gateInstance ?: synchronized(this) {
            gateInstance ?: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate().also { gateInstance = it }
        }
    }
}
