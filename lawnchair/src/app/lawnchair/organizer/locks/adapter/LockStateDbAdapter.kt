package app.lawnchair.organizer.locks.adapter

import android.content.ContentValues
import android.content.Context
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.locks.LockCapture
import app.lawnchair.organizer.locks.LockCapturePort
import app.lawnchair.organizer.locks.LockStateWriterPort
import app.lawnchair.organizer.locks.LockTargetState
import app.lawnchair.organizer.locks.LockWriteOutcome
import app.lawnchair.organizer.locks.LockWritePlan
import app.lawnchair.organizer.locks.LockWriteRejection
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.LayoutWriteCoordinator
import com.android.launcher3.model.ModelDbController

/**
 * Production lock-authoring adapter. Captures through the Issue #14 canonical
 * boundary ([LauncherLayoutAdapter]) so lock authoring reads exactly what
 * apply/recovery read, and writes through the same coordinator lease and
 * `ModelDbController` transaction discipline: acquire the organizer lease,
 * reread the revision and every exact row precondition inside the
 * transaction, update only `favorites.organizerLockState`, commit atomically.
 * No Issue #14 file is modified.
 */
internal class LockStateDbAdapter(
    private val captureAdapter: LauncherLayoutAdapter,
    private val controller: ModelDbController,
) : LockCapturePort,
    LockStateWriterPort {

    override fun capture(): LockCapture {
        val snapshot: CapturedSnapshot = captureAdapter.captureCurrent(CAPTURE_ID)
        return LockCapture(snapshot.layoutState, snapshot.revision)
    }

    override fun write(plan: LockWritePlan): LockWriteOutcome {
        val lease = LayoutWriteCoordinator.getInstance()
            .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER)
            ?: return LockWriteOutcome.Rejected(LockWriteRejection.WRITER_BUSY)
        return lease.use {
            val tx = controller.newTransaction(lease.token())
            try {
                // The reread observes the same locked state the update mutates.
                val before = captureAdapter.captureCurrent(CAPTURE_ID)
                if (before.revision != plan.sourceRevision) {
                    tx.close()
                    return LockWriteOutcome.Rejected(LockWriteRejection.STALE_REVISION)
                }
                val itemsByRef = before.layoutState.items.associateBy { it.ref }
                plan.writes.forEach { write ->
                    val current = itemsByRef[ApplicationItemRef.PersistentItem(write.item)]
                    if (current != write.expected) {
                        tx.close()
                        return LockWriteOutcome.Rejected(LockWriteRejection.PRECONDITION_FAILED)
                    }
                }
                plan.writes.forEach { write ->
                    val updated = tx.db.update(
                        Favorites.TABLE_NAME,
                        lockColumnValues(write.newState),
                        "${Favorites._ID}=?",
                        arrayOf(write.rowId.toString()),
                    )
                    if (updated != 1) {
                        tx.close()
                        return LockWriteOutcome.Rejected(LockWriteRejection.PRECONDITION_FAILED)
                    }
                }
                tx.commit()
                tx.close()
                // The write is durable; the new revision is observable through a
                // fresh capture rather than asserted here, so a recapture failure
                // after commit is never misreported as a failed write.
                LockWriteOutcome.Committed(newRevision = null)
            } catch (t: Throwable) {
                runCatching { tx.close() }
                LockWriteOutcome.Failed(t)
            }
        }
    }

    companion object {
        private val CAPTURE_ID = CaptureId("organizer-lock-authoring")

        /** Issue #38 production composition; the only Android construction path. */
        fun production(context: Context): LockStateDbAdapter {
            val appContext = context.applicationContext
            val model: LauncherModel = LauncherAppState.getInstance(appContext).model
            return LockStateDbAdapter(
                captureAdapter = LauncherLayoutAdapter(appContext, model.modelDbController, model),
                controller = model.modelDbController,
            )
        }
    }
}

private fun lockColumnValues(target: LockTargetState): ContentValues = ContentValues().apply {
    put(Favorites.ORGANIZER_LOCK_STATE, target.toStored().ordinal)
}
