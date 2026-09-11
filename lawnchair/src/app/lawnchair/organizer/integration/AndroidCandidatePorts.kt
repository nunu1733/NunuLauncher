package app.lawnchair.organizer.integration

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserHandle
import android.os.UserManager
import app.lawnchair.organizer.application.protocol.AvailabilityVerification
import app.lawnchair.organizer.application.protocol.CandidateApplicationResolution
import app.lawnchair.organizer.application.protocol.CandidateApplicationResolver
import app.lawnchair.organizer.application.protocol.CandidateAvailabilityPort
import app.lawnchair.organizer.application.protocol.CandidateResolutionFailure
import app.lawnchair.organizer.application.public.ImmutableByteString
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ProfileId
import com.android.launcher3.pm.UserCache
import java.io.ByteArrayOutputStream

/**
 * Maps a canonical [ProfileId] (capture-serial string) back to the current
 * Android user, using the same serial face the capture uses. A profile that
 * no longer resolves maps to null; callers decide the failure semantics.
 */
private fun userForProfile(userCache: UserCache, profile: ProfileId): UserHandle? = userCache.userProfiles.firstOrNull { handle ->
    try {
        userCache.getSerialNumberForUser(handle).toString() == profile.value
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * Issue #228: platform implementation of the composition/plan-time candidate
 * resolver. Resolves title / canonical launch intent / icon through the same
 * launcher-authorized [LauncherApps] face the detection source uses; a
 * component that is no longer enumerated resolves to a typed failure instead
 * of stale data.
 */
class AndroidCandidateApplicationResolver(
    appContext: Context,
) : CandidateApplicationResolver {
    private val context = appContext.applicationContext
    private val userCache = UserCache.INSTANCE.get(context)
    private val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))

    override fun resolve(target: CandidateTarget.AppKey): CandidateApplicationResolution {
        val user = userForProfile(userCache, target.profile)
        val info = user?.let { launcherApps.getActivityList(null, it) }
            ?.firstOrNull { it.componentName.flattenToString() == target.component.value }
            ?: return CandidateApplicationResolution.Unavailable(CandidateResolutionFailure.COMPONENT_NOT_FOUND)
        val label = info.label?.toString()?.takeIf { it.isNotBlank() }
            ?: return CandidateApplicationResolution.Unavailable(CandidateResolutionFailure.LABEL_UNAVAILABLE)
        return try {
            // The canonical app-icon intent the launcher itself persists for
            // application rows (AppInfo.makeLaunchIntent): ACTION_MAIN +
            // CATEGORY_LAUNCHER + component + launch flags, serialized exactly
            // like ModelWriter stores it.
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(info.componentName)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            CandidateApplicationResolution.Ready(
                title = label,
                intentText = intent.toUri(0),
                icon = encodeIcon(info.getBadgedIcon(0)),
                itemAvailability = ItemAvailability.AVAILABLE,
            )
        } catch (_: RuntimeException) {
            CandidateApplicationResolution.Unavailable(CandidateResolutionFailure.PLATFORM_READ_FAILED)
        }
    }

    private fun encodeIcon(drawable: android.graphics.drawable.Drawable): OptionalBytes = try {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        OptionalBytes.Present(ImmutableByteString.copyFrom(stream.toByteArray()))
    } catch (_: RuntimeException) {
        OptionalBytes.Absent
    }
}

/**
 * Issue #228: apply-time availability re-verification over the platform
 * surface. Unknown profile, locked/quiet profile, suspended package, or a
 * component that left the launchable enumeration all count as unavailable;
 * a platform read failure is a typed [AvailabilityVerification.Unknown]
 * (fail-closed, never optimistic).
 */
class AndroidCandidateAvailabilityPort(
    appContext: Context,
) : CandidateAvailabilityPort {
    private val context = appContext.applicationContext
    private val userCache = UserCache.INSTANCE.get(context)
    private val userManager = checkNotNull(context.getSystemService(UserManager::class.java))
    private val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))

    override fun verifyLaunchable(candidates: List<CandidateTarget.AppKey>): AvailabilityVerification {
        val unavailable = mutableListOf<CandidateTarget.AppKey>()
        try {
            for (candidate in candidates) {
                val user = userForProfile(userCache, candidate.profile)
                if (user == null || !userManager.isUserUnlocked(user) || userManager.isQuietModeEnabled(user)) {
                    unavailable += candidate
                    continue
                }
                if (!isLaunchable(candidate, user)) unavailable += candidate
            }
        } catch (_: RuntimeException) {
            return AvailabilityVerification.Unknown("availability re-verification failed")
        }
        return if (unavailable.isEmpty()) {
            AvailabilityVerification.AllAvailable
        } else {
            AvailabilityVerification.Unavailable(unavailable)
        }
    }

    private fun isLaunchable(candidate: CandidateTarget.AppKey, user: UserHandle): Boolean {
        val activity = launcherApps.getActivityList(candidate.component.value.substringBefore('/'), user)
            .firstOrNull { it.componentName.flattenToString() == candidate.component.value }
            ?: return false
        // Suspension follows the launcher's ApplicationInfo flag face
        // (PackageManagerHelper.isAppSuspended).
        return (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0
    }
}
