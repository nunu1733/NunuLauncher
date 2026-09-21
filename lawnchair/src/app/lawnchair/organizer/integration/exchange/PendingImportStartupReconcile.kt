package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.exchange.PendingIntentReconcile
import app.lawnchair.organizer.personalization.exchange.reconcilePendingIntent

/**
 * Issue #374 (spec 374 DI-AC-05 / Contract notes 8): the STARTUP application
 * point of the read-time reconcile. The pending store's reconcile is
 * store-complete (pending store + session store + clock only — no readiness
 * gate, no model load), so it runs at the HEAD of the shared idempotent
 * startup trigger (`LawnchairApp.ensureOrganizerStartupReconciliation()`)
 * and a fresh process cleans a stale record even when the hub is never
 * opened. The read-time reconcile at the status-card/ImportReview reads
 * remains the master defense; this is early cleanup only.
 */
object PendingImportStartupReconcile {

    /**
     * Loads the pending record plus the ACTIVE export session at
     * [nowEpochMs], runs the pure [reconcilePendingIntent], and —
     * fail-closed — deletes the record when it is [PendingIntentReconcile.Invalid].
     * A [PendingIntentReconcile.Valid] record and an
     * [PendingIntentReconcile.Absent] store write nothing (validity is
     * subordinate to the session; nothing is invented or replaced here).
     *
     * Never throws: the store implementations fail-closed clean unreadable
     * residue themselves and read it as absent (DI-AC-05), and any residual
     * failure is swallowed so the shared startup thread continues to the
     * model-load reconciliation undisturbed (spec 374 "読取時reconcileの
     * fail-closed" and the Scenario 「起動時reconcileはhubを開かなくても実行される」).
     */
    fun reconcileAtStartup(
        store: PendingImportedIntentStore,
        sessionStore: ExportSessionStore,
        nowEpochMs: Long,
    ) {
        runCatching {
            val record = store.load() ?: return
            if (reconcilePendingIntent(record, sessionStore.active(nowEpochMs), nowEpochMs) is PendingIntentReconcile.Invalid) {
                store.delete()
            }
        }
    }
}
