package app.lawnchair.organizer.integration

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import app.lawnchair.organizer.application.adapter.canonicalProfileId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.pm.UserCache

/**
 * Issue #228: one detected missing-app candidate row for the selection UI.
 * The stable identity is [CandidateTarget.AppKey] (component + profile);
 * [label] is display-only and never participates in identity.
 */
data class DetectedCandidate(
    val target: CandidateTarget.AppKey,
    val label: String,
    val availability: Availability,
)

sealed interface CandidateDetectionResult {
    data class Ready(val candidates: List<DetectedCandidate>) : CandidateDetectionResult

    /** Typed non-write failure; the run falls back to the plain full organize. */
    data class Unavailable(val reason: DetectionUnavailableReason) : CandidateDetectionResult
}

enum class DetectionUnavailableReason {
    /** A user profile's canonical serial could not be resolved. */
    PROFILE_SERIAL_UNAVAILABLE,

    /** The canonical capture that detection diffs against could not be read. */
    CAPTURE_FAILED,
}

/**
 * Read-only detection seam (issue #228): diffs the launchable installed-app
 * inventory against the apps already represented in the captured snapshot.
 */
interface MissingAppCandidateSource {
    fun detect(snapshot: CapturedSnapshot): CandidateDetectionResult
}

/** One launchable activity as enumerated by the platform, in canonical identity form. */
data class InstalledLaunchableApp(
    val component: ComponentKey,
    val profile: ProfileId,
    val label: String,
    val availability: Availability,
)

/**
 * Pure set difference shared by production and tests (spec §1):
 * `inventory − represented identities = candidates`. Only `AVAILABLE`
 * entries survive; duplicate inventory rows collapse by identity; the result
 * is ordered deterministically by (profile, label, component) for display
 * (D-3; label is locale-dependent but the order is a deterministic function
 * of the input).
 */
object MissingAppDetection {

    fun detect(
        inventory: List<InstalledLaunchableApp>,
        represented: Set<CandidateTarget.AppKey>,
    ): List<DetectedCandidate> = inventory
        .asSequence()
        .filter { it.availability == Availability.AVAILABLE }
        .map { DetectedCandidate(CandidateTarget.AppKey(it.component, it.profile), it.label, Availability.AVAILABLE) }
        .distinctBy { it.target }
        .filter { it.target !in represented }
        .sortedWith(compareBy({ it.target.profile.value }, { it.label }, { it.target.component.value }))
        .toList()

    /**
     * Stable identities of the apps the snapshot already represents: every
     * captured item carrying a [TargetKey.AppKey], regardless of container
     * (workspace, dock, folder member, app-pair member) or duplication.
     */
    fun representedIdentities(snapshot: CapturedSnapshot): Set<CandidateTarget.AppKey> = snapshot.layoutState.items
        .asSequence()
        .mapNotNull { it.targetKey as? TargetKey.AppKey }
        .map { CandidateTarget.AppKey(it.component, it.profile) }
        .toSet()
}

/**
 * Production detection over the launcher-authorized [LauncherApps] surface —
 * the same permission face as the classification snapshot source and the
 * category-override authoring inventory. Per-profile enumeration skips
 * locked/quiet profiles (their apps are legitimately invisible, not
 * "missing"); a profile whose canonical serial cannot be resolved fails the
 * whole detection closed rather than silently dropping its apps.
 */
class AndroidMissingAppCandidateSource(
    appContext: Context,
) : MissingAppCandidateSource {
    private val context = appContext.applicationContext
    private val userCache = UserCache.INSTANCE.get(context)
    private val userManager = checkNotNull(context.getSystemService(UserManager::class.java))
    private val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))

    override fun detect(snapshot: CapturedSnapshot): CandidateDetectionResult {
        val inventory = mutableListOf<InstalledLaunchableApp>()
        val currentUser = Process.myUserHandle()
        val users = (userCache.userProfiles + currentUser).distinct()
        for (user in users) {
            // Locked/quiet profiles cannot be organized into; their apps are
            // excluded from the candidate list by the availability contract.
            if (!userManager.isUserUnlocked(user) || userManager.isQuietModeEnabled(user)) continue
            val profile = canonicalProfileId(userCache, user)
                ?: return CandidateDetectionResult.Unavailable(DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE)
            for (activity in launcherApps.getActivityList(null, user)) {
                // Suspension follows the launcher's own ApplicationInfo flag
                // face (PackageManagerHelper.isAppSuspended); an unreadable
                // flag set is not evidence of availability, so it excludes.
                val suspended = try {
                    (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
                } catch (_: RuntimeException) {
                    true
                }
                inventory += InstalledLaunchableApp(
                    component = ComponentKey(activity.componentName.flattenToString()),
                    profile = profile,
                    label = activity.label?.toString().orEmpty(),
                    availability = if (suspended) Availability.UNAVAILABLE else Availability.AVAILABLE,
                )
            }
        }
        return CandidateDetectionResult.Ready(
            MissingAppDetection.detect(inventory, MissingAppDetection.representedIdentities(snapshot)),
        )
    }
}
