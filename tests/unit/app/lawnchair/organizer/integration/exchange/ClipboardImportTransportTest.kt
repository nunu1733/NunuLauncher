package app.lawnchair.organizer.integration.exchange

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #332 (spec AC-1): the clipboard read adapter's typed read surface.
 * The Android `ClipboardManager` cannot be faked on the JVM, so the holder
 * path runs through the injected read hook (`readOverride`, mirroring the
 * #205 `writeOverride` pattern); these tests pin the one-read-per-call
 * contract and the typed passthrough. The no-listener guarantee (no
 * `OnPrimaryClipChangedListener` anywhere in the adapter) is a structural
 * review property of the same file.
 */
class ClipboardImportTransportTest {

    @Suppress("DEPRECATION")
    private fun noContext(): Context = ContextWrapper(null)

    @Test
    fun aReadPerformsExactlyOneInjectedClipAccess() {
        val transport = ClipboardImportTransport(noContext())
        var reads = 0
        transport.readOverride = {
            reads++
            ClipboardImportRead.Text("reply")
        }
        val first = transport.read()
        val second = transport.read()
        assertEquals(2, reads)
        assertEquals(ClipboardImportRead.Text("reply"), first)
        assertEquals(ClipboardImportRead.Text("reply"), second)
    }

    @Test
    fun typedReadResultsPassThroughWithoutInterpretation() {
        val transport = ClipboardImportTransport(noContext())
        transport.readOverride = { ClipboardImportRead.EmptyOrUnavailable }
        assertEquals(ClipboardImportRead.EmptyOrUnavailable, transport.read())
        transport.readOverride = { ClipboardImportRead.NotText }
        assertEquals(ClipboardImportRead.NotText, transport.read())
        transport.readOverride = { ClipboardImportRead.Text("AI reply body") }
        val text = transport.read()
        assertTrue(text is ClipboardImportRead.Text)
        assertEquals("AI reply body", (text as ClipboardImportRead.Text).text)
    }
}
