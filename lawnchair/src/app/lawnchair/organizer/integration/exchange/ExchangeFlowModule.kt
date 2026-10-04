package app.lawnchair.organizer.integration.exchange

import android.content.Context
import app.lawnchair.LawnchairApp
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.integration.ProductionOrganizationInputComposer
import com.android.launcher3.LauncherAppState

/**
 * Issue #205: process-local construction of the exchange flow controller.
 * Follows the module-per-concern object convention; construction itself is
 * read-only. The composer is a fresh `ProductionOrganizationInputComposer`
 * over the same canonical capture seam the manual run uses, so the export
 * generation and import reconstruction share the production derivation path
 * (spec 205 "canonical入力sourceの単一化").
 */
object ExchangeFlowModule {

    @Volatile
    private var instance: ExchangeFlowController? = null

    fun controller(context: Context): ExchangeFlowController {
        return instance ?: synchronized(this) {
            instance ?: buildController(context.applicationContext).also { instance = it }
        }
    }

    private fun buildController(appContext: Context): ExchangeFlowController {
        // Same app-state guarantee as ManualOrganizationModule: this surface
        // may be the first screen of a fresh process.
        val launcher = LauncherAppState.getInstance(appContext)
        (appContext as? LawnchairApp)?.ensureOrganizerStartupReconciliation()
        val writer = LauncherLayoutAdapter(
            appContext,
            launcher.model.modelDbController,
            launcher.model,
        )
        return ExchangeFlowController(
            adapter = ExchangeInputAdapter(
                composer = ProductionOrganizationInputComposer(appContext, writer),
                titleSource = LayoutWriterExchangeItemTitleSource(writer),
            ),
            store = ExchangeSessionStoreModule.store(appContext),
            allocator = app.lawnchair.organizer.integration.SecureRandomIdAllocator(),
            clock = System::currentTimeMillis,
            // Issue #374 (spec 374 DI-AC-03): the #374-owned durable pending
            // imported intent store — a successful generation (session
            // replacement) invalidates the previous imported proposal's record
            // right after the new session's save.
            pendingImportStore = PendingImportedIntentModule.store(appContext),
            // Issue #375 (spec "exchange mutation gate"): the replacement
            // commit and the pre-send invalidation share THE process-wide
            // gate with the holder and the rebind admission anchor.
            exchangeMutationGate = PendingImportedIntentModule.gate(),
        )
    }
}
