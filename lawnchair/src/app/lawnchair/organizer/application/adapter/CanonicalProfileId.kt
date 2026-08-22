package app.lawnchair.organizer.application.adapter

import android.os.UserHandle
import app.lawnchair.organizer.planning.ProfileId
import com.android.launcher3.pm.UserCache

/**
 * The single production mapping from a current Android user to Organizer's
 * canonical ProfileId. Callers decide whether an unavailable serial is a
 * capture failure or an unavailable authoring target.
 */
internal fun canonicalProfileId(userCache: UserCache, user: UserHandle): ProfileId? = try {
    userCache.getSerialNumberForUser(user).takeIf { it >= 0L }?.let { ProfileId(it.toString()) }
} catch (_: RuntimeException) {
    null
}
