/*
 * Issue #450 (review round 2 finding 2): the production availability source
 * oracle. Exercises the real LauncherApps-backed determinations (the same
 * resolution path the organizer uses for candidate availability and the edit
 * surface uses for icon resolution) so the fail-closed contract — app gone,
 * shortcut gone, unresolvable recorded profile, verification failure — is
 * proven at the production seam, not only through planner inputs.
 */
package app.lawnchair.homeedit

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DirectEditContract
import com.android.launcher3.pm.UserCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeEditUndoAvailabilityInstrumentationTest {

    private lateinit var context: android.content.Context
    private lateinit var source: HomeEditUndoAvailabilitySource
    private var mySerial: Long = 0

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        source = ProductionHomeEditUndoAvailabilitySource(context)
        mySerial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(android.os.Process.myUserHandle())
    }

    private fun payload(
        itemType: Int,
        componentName: String? = null,
        packageName: String? = null,
        shortcutId: String? = null,
        userSerial: Long = mySerial,
    ) = DirectEditContract.UndoRowPayload(
        501, itemType, Favorites.CONTAINER_DESKTOP, 0,
        1, 2, 1, 1, 0, userSerial,
        "intent", "title", 0, 1,
        componentName, packageName, shortcutId,
    )

    @Test
    fun anInstalledAppResolvesAvailable() {
        // The test process's own launcher activity is always installed.
        val componentName = android.content.ComponentName(
            context.packageName,
            app.lawnchair.LawnchairLauncher::class.java.name,
        ).flattenToString()
        assertEquals(
            HomeEditUndoAvailability.AVAILABLE,
            source.availabilityOf(
                payload(Favorites.ITEM_TYPE_APPLICATION, componentName = componentName),
            ),
        )
    }

    @Test
    fun aGonePackageResolvesUnavailableWithZeroWriteSemantics() {
        assertEquals(
            HomeEditUndoAvailability.UNAVAILABLE,
            source.availabilityOf(
                payload(
                    Favorites.ITEM_TYPE_APPLICATION,
                    componentName = "com.example.definitely.not.installed/.MainActivity",
                ),
            ),
        )
    }

    @Test
    fun aGoneDeepShortcutNeverReadsAvailable() {
        // On this emulator the shortcut query itself can be rejected by the
        // system (SecurityException for an uninstalled caller package) — the
        // fail-closed contract covers both readings: the shortcut is either
        // observed gone (UNAVAILABLE) or unverifiable (UNKNOWN). What the
        // contract forbids is AVAILABLE, which would resurrect the row.
        val availability = source.availabilityOf(
            payload(
                Favorites.ITEM_TYPE_DEEP_SHORTCUT,
                packageName = "com.example.definitely.not.installed",
                shortcutId = "gone-shortcut",
            ),
        )
        assertTrue(
            "a gone shortcut must never read AVAILABLE, got $availability",
            availability == HomeEditUndoAvailability.UNAVAILABLE ||
                availability == HomeEditUndoAvailability.UNKNOWN,
        )
    }

    @Test
    fun anUnresolvableRecordedProfileFailsClosedAsUnknown() {
        // The round-1 fix under test: an unknown/removed profile serial must
        // NOT fall back to the current user — a same-component row on another
        // profile must never read as AVAILABLE.
        val componentName = android.content.ComponentName(
            context.packageName,
            app.lawnchair.LawnchairLauncher::class.java.name,
        ).flattenToString()
        val unknownSerial = 999_999L
        val userCache = UserCache.INSTANCE.get(context)
        val isActuallyUsed = userCache.getUserForSerialNumber(unknownSerial) != null
        if (isActuallyUsed) {
            // Environment guard: the probe serial is in use on this device;
            // the fail-closed oracle would be vacuous, so pick another.
            val alt = 999_998L
            org.junit.Assume.assumeTrue(userCache.getUserForSerialNumber(alt) == null)
            assertEquals(
                HomeEditUndoAvailability.UNKNOWN,
                source.availabilityOf(
                    payload(
                        Favorites.ITEM_TYPE_APPLICATION,
                        componentName = componentName,
                        userSerial = alt,
                    ),
                ),
            )
        } else {
            assertEquals(
                HomeEditUndoAvailability.UNKNOWN,
                source.availabilityOf(
                    payload(
                        Favorites.ITEM_TYPE_APPLICATION,
                        componentName = componentName,
                        userSerial = unknownSerial,
                    ),
                ),
            )
        }
    }

    @Test
    fun aVerifierExceptionFailsClosedAsUnknown() {
        // Deterministic exception injection at the production source seam
        // (round 3 finding 4): the same identity as the installed app, but the
        // platform read throws — the catch-all branch must map the failure to
        // UNKNOWN (fail-closed), never AVAILABLE.
        val componentName = android.content.ComponentName(
            context.packageName,
            app.lawnchair.LawnchairLauncher::class.java.name,
        ).flattenToString()
        val throwingSource = object : ProductionHomeEditUndoAvailabilitySource(context) {
            override fun launcherApps(): android.content.pm.LauncherApps =
                throw java.lang.IllegalStateException("injected binder failure")
        }
        assertEquals(
            HomeEditUndoAvailability.UNKNOWN,
            throwingSource.availabilityOf(
                payload(Favorites.ITEM_TYPE_APPLICATION, componentName = componentName),
            ),
        )
    }

    @Test
    fun aRowWithoutAResolvableLaunchTargetFailsClosed() {
        // A captured row with neither component nor shortcut identity has no
        // undoable precondition: typed rejection, never a resurrect.
        assertEquals(
            HomeEditUndoAvailability.UNAVAILABLE,
            source.availabilityOf(payload(Favorites.ITEM_TYPE_APPLICATION)),
        )
        assertEquals(
            HomeEditUndoAvailability.UNAVAILABLE,
            source.availabilityOf(payload(Favorites.ITEM_TYPE_DEEP_SHORTCUT, packageName = "p")),
        )
    }
}
