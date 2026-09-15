package app.lawnchair.organizer.integration

import android.content.Context
import app.lawnchair.organizer.personalization.LauncherOriginAvailability
import app.lawnchair.organizer.personalization.LauncherOriginSection
import app.lawnchair.organizer.personalization.PersonalizationSignalSnapshot
import app.lawnchair.organizer.personalization.PersonalizationSignalSnapshotSource
import app.lawnchair.organizer.personalization.UsageSignalRequest
import java.time.Instant
import java.time.ZoneId

/**
 * Issue #203: the composition owner of the two independent sources — system
 * usage and launcher-origin. It merges both reads into the final
 * [PersonalizationSignalSnapshot] and performs the per-source availability
 * bookkeeping. A failure of one source never drops the other section; the
 * read itself is total (never throws), so a personalization failure can never
 * make the composition `NotReady` (spec #203, optional source contract).
 *
 * The window anchor is read exactly once here (clock of the snapshot) and
 * handed to both readers, so the two sections describe one capture instant.
 */
class AndroidPersonalizationSignalSnapshotSource(
    appContext: Context,
    private val systemUsage: AndroidSystemUsageSignalReader = AndroidSystemUsageSignalReader(appContext),
    private val launcherOrigin: LauncherOriginSignalReader = LauncherOriginSignalReader(LauncherOriginLaunchCounterStore.from(appContext)),
) : PersonalizationSignalSnapshotSource {

    override fun read(request: UsageSignalRequest): PersonalizationSignalSnapshot = try {
        val zone = ZoneId.systemDefault()
        val windowAnchorElapsedMs = System.currentTimeMillis()
        val windowAnchorEpochDay = Instant.ofEpochMilli(windowAnchorElapsedMs).atZone(zone).toLocalDate().toEpochDay()
        val systemUsageRead = systemUsage.read(request, windowAnchorElapsedMs)
        val launcherOriginSection = launcherOrigin.read(request, windowAnchorEpochDay)
        PersonalizationSignalSnapshot(
            usageAccess = systemUsageRead.usageAccess,
            launcherOriginAvailability = if (launcherOriginSection is LauncherOriginSection.Available) {
                LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE
            } else {
                LauncherOriginAvailability.LAUNCHER_ORIGIN_UNAVAILABLE
            },
            profileAvailability = systemUsageRead.profileAvailability,
            systemUsage = systemUsageRead.systemUsage,
            launcherOrigin = launcherOriginSection,
        )
    } catch (_: RuntimeException) {
        // Defense in depth for the composer's total-read contract: the readers
        // are already total, so this path means a bug — degrade to the
        // all-unavailable snapshot instead of failing the composition.
        PersonalizationSignalSnapshot.unavailable(request.profiles)
    }
}
