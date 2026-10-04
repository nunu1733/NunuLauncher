package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #203: launcher-origin observation write-side contract. The recorder
 * applies the item-type filter (app pair / promise icon excluded by contract),
 * honors the recording preference, writes asynchronously, and absorbs every
 * store failure so the launch flow and the existing `logAppLaunch` side
 * effects are never affected (spec AC-15 / AC-14 / AC-16).
 */
class LauncherOriginLaunchRecorderTest {

    private class RecordingStore : LauncherOriginLaunchStore {
        val records = mutableListOf<Triple<ProfileId, PackageName, Long>>()

        override fun record(profile: ProfileId, packageName: PackageName, epochDay: Long) {
            records.add(Triple(profile, packageName, epochDay))
        }

        override fun read(profile: ProfileId, packageName: PackageName): LauncherOriginRecord? = null

        override fun clear() = Unit
    }

    private class ThrowingStore : LauncherOriginLaunchStore {
        override fun record(profile: ProfileId, packageName: PackageName, epochDay: Long) {
            throw LauncherOriginLaunchStore.CorruptCounterStateException()
        }

        override fun read(profile: ProfileId, packageName: PackageName): LauncherOriginRecord? = null

        override fun clear() = Unit
    }

    private val profile = ProfileId("0")
    private val packageName = PackageName("com.example.a")
    private val directExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

    private fun observation(
        isAppPair: Boolean = false,
        isPromiseIcon: Boolean = false,
    ) = LauncherOriginLaunchRecorder.LaunchObservation(
        profile = profile,
        packageName = packageName,
        isAppPair = isAppPair,
        isPromiseIcon = isPromiseIcon,
    )

    @Test
    fun acceptedLaunchIsRecordedWithTheClockDayAnchor() {
        val store = RecordingStore()
        val recorder = LauncherOriginLaunchRecorder(store, { true }, { 20000L }, directExecutor)

        recorder.onLaunch(observation())

        assertEquals(listOf(Triple(profile, packageName, 20000L)), store.records)
    }

    @Test
    fun appPairAndPromiseIconLaunchesAreFilteredByItemType() {
        val store = RecordingStore()
        val recorder = LauncherOriginLaunchRecorder(store, { true }, { 20000L }, directExecutor)

        recorder.onLaunch(observation(isAppPair = true))
        recorder.onLaunch(observation(isPromiseIcon = true))

        assertTrue(store.records.isEmpty())
    }

    @Test
    fun disabledRecordingStopsWrites() {
        val store = RecordingStore()
        val recorder = LauncherOriginLaunchRecorder(store, { false }, { 20000L }, directExecutor)

        recorder.onLaunch(observation())

        assertTrue(store.records.isEmpty())
    }

    @Test
    fun storeFailuresAreAbsorbedAndNeverPropagate() {
        val recorder = LauncherOriginLaunchRecorder(ThrowingStore(), { true }, { 20000L }, directExecutor)

        // AC-15: the write is best-effort — a corrupted store must not throw
        // into the launch flow. The direct executor surfaces any propagation
        // synchronously, so this assertion only passes if the failure is
        // absorbed inside the recorder.
        recorder.onLaunch(observation())
    }
}
