package app.lawnchair.organizer.integration.exchange

import android.content.ClipboardManager
import android.content.Context

/**
 * Issue #332: the clipboard read adapter of the import flow (spec 332 D-7).
 * One explicit user action performs exactly one `getPrimaryClip()` call —
 * there is no listener registration, no resume/open read, and no
 * `coerceToText`-style URI/Intent resolution: only the raw text item of the
 * primary clip is taken. Failures are typed without guessing causes: a null
 * manager/clip or an empty text item is [ClipboardImportRead.EmptyOrUnavailable]
 * (the OS may legitimately return nothing — API 29+ focus rules, sensitive
 * flags), a clip without a text item is [ClipboardImportRead.NotText]. The
 * envelope limit is not owned here; the holder's receipt gate settles it so
 * every source shares one bound and one guidance string.
 */
sealed interface ClipboardImportRead {
    data class Text(val text: String) : ClipboardImportRead

    /** Null clip / empty text / unavailable clipboard — cause is not distinguished. */
    data object EmptyOrUnavailable : ClipboardImportRead

    /** The clip exists but carries no text item (Intent/URI clip). */
    data object NotText : ClipboardImportRead
}

class ClipboardImportTransport(private val context: Context) {

    /**
     * Test-only read hook (spec 332 AC-1 failure injection): when non-null the
     * read body is injected, so holder tests exercise the real
     * `importFromClipboard()` path without an Android ClipboardManager.
     */
    internal var readOverride: (() -> ClipboardImportRead)? = null

    fun read(): ClipboardImportRead {
        readOverride?.let { return it() }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardImportRead.EmptyOrUnavailable
        return try {
            val clip = manager.primaryClip ?: return ClipboardImportRead.EmptyOrUnavailable
            if (clip.itemCount < 1) return ClipboardImportRead.EmptyOrUnavailable
            val text = clip.getItemAt(0)?.text
            when {
                text == null -> ClipboardImportRead.NotText
                text.isEmpty() -> ClipboardImportRead.EmptyOrUnavailable
                else -> ClipboardImportRead.Text(text.toString())
            }
        } catch (_: RuntimeException) {
            ClipboardImportRead.EmptyOrUnavailable
        }
    }
}
