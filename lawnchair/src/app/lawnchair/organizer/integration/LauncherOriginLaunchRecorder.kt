package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import com.android.launcher3.util.Executors

/**
 * Issue #203: the write-side of the launcher-origin signal. The launcher
 * activity hands the observation over best-effort and asynchronously — the
 * write never blocks the UI thread and never propagates a failure into the
 * launch flow or the existing logging path (2026-09-15 review Required).
 *
 * Observation unit (spec #203): a launch request observed at
 * `Launcher.logAppLaunch`. App pairs and promise icons/market flows are the
 * item-type filter (excluded by contract); taskbar icon taps converge through
 * the same seam and are in scope, taskbar recents never arrive here.
 */
class LauncherOriginLaunchRecorder(
    private val store: LauncherOriginLaunchStore,
    private val isRecordingEnabled: () -> Boolean,
    private val clockEpochDay: () -> Long,
    private val executor: java.util.concurrent.Executor = Executors.MODEL_EXECUTOR,
) {

    fun onLaunch(observation: LaunchObservation) {
        // Item-type filter: app pairs and promise/market flows are contract
        // exclusions — a filtered launch is not an error and not a record.
        if (observation.isAppPair || observation.isPromiseIcon) return
        if (!isRecordingEnabled()) return
        val epochDay = clockEpochDay()
        executor.execute {
            try {
                store.record(observation.profile, observation.packageName, epochDay)
            } catch (_: RuntimeException) {
                // Best-effort bookkeeping: a counter failure must never break
                // the launch flow or the existing logAppLaunch side effects.
            }
        }
    }

    data class LaunchObservation(
        val profile: ProfileId,
        val packageName: PackageName,
        val isAppPair: Boolean,
        val isPromiseIcon: Boolean,
    )
}
