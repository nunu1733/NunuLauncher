/*
 * Issue #450: production availability determination for the remove-undo.
 * The same determination the organizer uses for candidate availability
 * (Issue #228) and the edit surface uses for icon resolution: apps resolve
 * through LauncherApps.getActivityList, deep shortcuts through getShortcuts
 * with PINNED|MANIFEST. Any verification failure maps to UNKNOWN (fail-closed
 * in the planner). The identical instance backs stage 1 and stage 2 so both
 * stages reach the same determination (spec 450 Scope).
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DirectEditContract
import com.android.launcher3.pm.UserCache

interface HomeEditUndoAvailabilitySource {

    /** Side-effect-free availability read for a captured remove row. */
    fun availabilityOf(payload: DirectEditContract.UndoRowPayload): HomeEditUndoAvailability
}

class ProductionHomeEditUndoAvailabilitySource(
    private val context: Context,
) : HomeEditUndoAvailabilitySource {

    override fun availabilityOf(payload: DirectEditContract.UndoRowPayload): HomeEditUndoAvailability {
        return try {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            // The recorded profile identity is part of the availability
            // contract (component × profile): an unresolvable serial (removed
            // work profile, unknown serial) is NOT the current user — it is a
            // verification failure and must fail closed (UNDO_ITEM_UNAVAILABLE),
            // never a fallback that could accept a same-component row on
            // another profile.
            val user = UserCache.INSTANCE.get(context).getUserForSerialNumber(payload.userSerial)
                ?: return HomeEditUndoAvailability.UNKNOWN
            when {
                payload.itemType == Favorites.ITEM_TYPE_APPLICATION && payload.componentName != null -> {
                    val component = ComponentName.unflattenFromString(payload.componentName)
                        ?: return HomeEditUndoAvailability.UNAVAILABLE
                    val present = launcherApps.getActivityList(component.packageName, user)
                        .any { it.componentName == component }
                    if (present) {
                        HomeEditUndoAvailability.AVAILABLE
                    } else {
                        HomeEditUndoAvailability.UNAVAILABLE
                    }
                }

                payload.itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT &&
                    payload.packageName != null && payload.shortcutId != null -> {
                    val query = LauncherApps.ShortcutQuery().apply {
                        setPackage(payload.packageName)
                        setShortcutIds(listOf(payload.shortcutId))
                        setQueryFlags(
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                        )
                    }
                    val present = launcherApps.getShortcuts(query, user)?.isNotEmpty() == true
                    if (present) {
                        HomeEditUndoAvailability.AVAILABLE
                    } else {
                        HomeEditUndoAvailability.UNAVAILABLE
                    }
                }

                // A captured row without a resolvable launch target has no
                // undoable precondition — a typed rejection, not a resurrect.
                else -> HomeEditUndoAvailability.UNAVAILABLE
            }
        } catch (_: Exception) {
            // Binder/security/platform read failures are not proof of absence
            // but neither of presence — fail closed.
            HomeEditUndoAvailability.UNKNOWN
        }
    }
}
