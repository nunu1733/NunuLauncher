package app.lawnchair.organizer.integration

import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.adapter.canonicalProfileId
import app.lawnchair.organizer.application.protocol.CaptureId
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.DisplayController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #108 host-shape evidence through the production capture/composition
 * seams. This test is intentionally read-only: it does not seed or mutate the
 * Launcher database.
 */
@RunWith(AndroidJUnit4::class)
class Issue108DeviceEvidenceInstrumentationTest {
    @Test
    fun productionCaptureRetainsEveryValidUserCacheProfileIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val userCache = UserCache.INSTANCE.get(context)
        val userManager = checkNotNull(context.getSystemService(UserManager::class.java))
        val handles = (userManager.userProfiles + Process.myUserHandle()).distinct()
        val profileDeadline = SystemClock.elapsedRealtime() + 5_000L
        while (!userCache.userProfiles.containsAll(handles) && SystemClock.elapsedRealtime() < profileDeadline) {
            SystemClock.sleep(100)
        }
        assertTrue("UserCache must converge to the authoritative profile inventory", userCache.userProfiles.containsAll(handles))
        val expectedIds = handles.map { handle ->
            checkNotNull(canonicalProfileId(userCache, handle)) {
                "Every valid profile must have a stable serial"
            }
        }.toSet()
        val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val activityCounts = handles.associateWith { handle ->
            launcherApps.getActivityList(null, handle).size
        }

        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("issue108-profile-inventory"))
        val capturedIds = capture.layoutState.profiles.map { it.id }.toSet()

        assertEquals(expectedIds, capturedIds)
        assertEquals(capturedIds.size, capture.layoutState.profiles.size)
        Log.i(
            TAG,
            "ISSUE108_PROFILE_EVIDENCE " +
                "handles=${handles.map { it.identifier }} serials=${expectedIds.sortedBy { it.value }} " +
                "launcherApps=${activityCounts.mapKeys { it.key.identifier }}",
        )
    }

    @Test
    fun productionCaptureAndComposerExposeCurrentHostProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = LauncherAppState.getInstance(context)
        val configuration = context.resources.configuration
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("issue108-host-profile"))
        val device = capture.layoutState.deviceCapabilities

        assertEquals(idp.numColumns, device.columns)
        assertEquals(idp.numRows, device.rows)
        assertEquals(idp.numDatabaseHotseatIcons, device.hotseatSlots)
        assertEquals(
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                app.lawnchair.organizer.application.public.DeviceOrientation.LANDSCAPE
            } else {
                app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT
            },
            device.orientation,
        )

        val displayInfo = DisplayController.INSTANCE.get(context).info
        val expectedTwoPanels = displayInfo.deviceType == InvariantDeviceProfile.TYPE_MULTI_DISPLAY
        var launcherTwoPanels: Boolean? = null
        ActivityScenario.launch(LawnchairLauncher::class.java).use { scenario ->
            scenario.onActivity { activity ->
                launcherTwoPanels = activity.deviceProfile.isTwoPanels
            }
        }
        assertEquals(expectedTwoPanels, launcherTwoPanels)

        val composed = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
        assertTrue("production composer must be ready on a supported host", composed is OrganizationInputComposition.Ready)
        val ready = composed as OrganizationInputComposition.Ready
        assertEquals(device.columns, ready.input.snapshot.device.columns)
        assertEquals(device.rows, ready.input.snapshot.device.rows)
        assertEquals(device.orientation.name, ready.input.snapshot.device.orientation.name)

        Log.i(
            TAG,
            "ISSUE108_DEVICE_EVIDENCE " +
                "model=${Build.MODEL} device=${Build.DEVICE} sdk=${Build.VERSION.SDK_INT} " +
                "orientation=${configuration.orientation} " +
                "screenDp=${configuration.screenWidthDp}x${configuration.screenHeightDp} " +
                "smallestDp=${configuration.smallestScreenWidthDp} " +
                "displayType=${displayInfo.deviceType} " +
                "displayTwoPanels=$expectedTwoPanels launcherTwoPanels=$launcherTwoPanels " +
                "idp=${idp.numColumns}x${idp.numRows} hotseat=${idp.numDatabaseHotseatIcons} " +
                "capture=${device.columns}x${device.rows}/${device.orientation.name}",
        )
    }

    private companion object {
        const val TAG = "Issue108DeviceEvidence"
    }
}
