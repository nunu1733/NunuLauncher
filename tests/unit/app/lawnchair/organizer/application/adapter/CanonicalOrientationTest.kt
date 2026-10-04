package app.lawnchair.organizer.application.adapter

import android.content.res.Configuration
import app.lawnchair.organizer.application.public.DeviceOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec #130: the pure two-panel/orientation mapping used by production
 * canonical capture. Covers every branch of the accepted authority
 * combination deterministically on the JVM.
 */
class CanonicalOrientationTest {

    @Test
    fun twoPanelHostsCaptureTwoPanelOrientations() {
        assertEquals(
            DeviceOrientation.TWO_PANEL_PORTRAIT,
            canonicalOrientation(isTwoPanels = true, configurationOrientation = Configuration.ORIENTATION_PORTRAIT),
        )
        assertEquals(
            DeviceOrientation.TWO_PANEL_LANDSCAPE,
            canonicalOrientation(isTwoPanels = true, configurationOrientation = Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun ordinaryHostsKeepPlainPortraitAndLandscape() {
        assertEquals(
            DeviceOrientation.PORTRAIT,
            canonicalOrientation(isTwoPanels = false, configurationOrientation = Configuration.ORIENTATION_PORTRAIT),
        )
        assertEquals(
            DeviceOrientation.LANDSCAPE,
            canonicalOrientation(isTwoPanels = false, configurationOrientation = Configuration.ORIENTATION_LANDSCAPE),
        )
    }
}
