package app.lawnchair.organizer.locks

import android.content.Context
import app.lawnchair.organizer.locks.adapter.LockStateDbAdapter

/**
 * Process-wide lock-authoring composition. Constructed lazily so the launcher
 * process pays for it only when a lock surface is opened; no `LawnchairApp`
 * bootstrap change is needed.
 */
object OrganizerLocks {

    @Volatile
    private var module: LockAuthoringModule? = null

    fun get(context: Context): LockAuthoringModule = module ?: synchronized(this) {
        module ?: LockStateDbAdapter.production(context).let { adapter ->
            LockAuthoringModule(adapter, adapter)
        }.also { module = it }
    }
}
