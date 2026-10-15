/*
 * Issue #497: one-shot fallback notice semantics (spec 497 AC-3). The store
 * uses real SharedPreferences, so the canonical owner is instrumentation:
 * the JVM cannot execute android.content.SharedPreferences. Posted once,
 * consumed exactly once, then empty.
 */
package app.lawnchair.homeedit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppDestinationNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun postedReasonIsConsumedExactlyOnce() {
        AppDestinationNotice.postPending(context, "DEST_FOLDER_MISSING")
        assertEquals("DEST_FOLDER_MISSING", AppDestinationNotice.consumePending(context))
        assertNull("the notice is one-shot", AppDestinationNotice.consumePending(context))
    }

    @Test
    fun noFallbackYieldsNull() {
        assertNull(AppDestinationNotice.consumePending(context))
        AppDestinationNotice.postPending(context, "DEST_SNAPSHOT_INVALID")
        assertEquals("DEST_SNAPSHOT_INVALID", AppDestinationNotice.consumePending(context))
        assertNull(AppDestinationNotice.consumePending(context))
    }
}
