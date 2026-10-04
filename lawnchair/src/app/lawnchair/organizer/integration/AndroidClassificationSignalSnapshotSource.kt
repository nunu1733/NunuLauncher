package app.lawnchair.organizer.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical
import com.android.launcher3.pm.UserCache

/**
 * Android-only evidence adapter. It is deliberately outside planning and Rule Management.
 *
 * Per-profile reads use the launcher-authorized [LauncherApps] surface: a default
 * launcher UID does not hold INTERACT_ACROSS_USERS[_FULL], so resolving another
 * profile's package through `createContextAsUser` is denied (#129). The read stays
 * bound to the requested profile; a package absent in that profile resolves to no
 * value here and fails closed instead of falling back to the same package in a
 * different profile.
 */
class AndroidClassificationSignalSnapshotSource(
    private val appContext: Context,
    private val userCache: UserCache = UserCache.INSTANCE.get(appContext),
    private val launcherApps: LauncherApps = checkNotNull(appContext.getSystemService(LauncherApps::class.java)),
) : ClassificationSignalSnapshotSource {
    override fun read(
        requests: List<ClassificationEvidenceRequest>,
        policy: ClassificationPolicy,
    ): PlatformEvidenceReadResult = try {
        val s2 = linkedMapOf<app.lawnchair.organizer.planning.ItemId, app.lawnchair.organizer.planning.CategoryId>()
        val s5 = linkedMapOf<app.lawnchair.organizer.planning.ItemId, app.lawnchair.organizer.planning.CategoryId>()
        val rows = mutableListOf<String>()
        for (request in requests.sortedWith(compareBy({ it.profile.value }, { it.packageName.value }, { it.item.value }))) {
            val serial = request.profile.value.toLongOrNull() ?: return PlatformEvidenceReadResult.Unreadable
            val user = userCache.getUserForSerialNumber(serial)
            if (userCache.getSerialNumberForUser(user) != serial) return PlatformEvidenceReadResult.Unreadable
            val info = launcherApps.getApplicationInfo(request.packageName.value, 0, user)
                ?: return PlatformEvidenceReadResult.Unreadable
            val androidCategory = policy.androidCategoryMapping[info.category]
            if (androidCategory != null) s2[request.item] = androidCategory
            val systemOrGoogle = when {
                request.packageName.value.startsWith("com.google.") -> policy.googleCategory
                info.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> policy.systemCategory
                else -> null
            }
            if (systemOrGoogle != null) s5[request.item] = systemOrGoogle
            rows += "${request.item.value}:${info.category}:${info.flags}:${androidCategory?.value ?: "-"}:${systemOrGoogle?.value ?: "-"}"
        }
        PlatformEvidenceReadResult.Ready(
            PlatformClassificationEvidence(
                s2 = s2,
                s5 = s5,
                identity = PolicyInputIdentity(
                    PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE,
                    "platform-evidence-v1",
                    sha256Canonical(rows.joinToString("\n")),
                ),
            ),
        )
    } catch (_: Exception) {
        PlatformEvidenceReadResult.Unreadable
    }
}
