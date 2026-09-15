package app.lawnchair.organizer.integration.exchange

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.lawnchair.organizer.personalization.exchange.ExchangeContract
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Issue #205: the thin exchange transport adapters (spec 205 Decision 3).
 * Each adapter receives the already-disclosed immutable package value and
 * only moves it to an Android surface — none of them can recompose or swap
 * the package (AC-12). Every failure is typed and reported; nothing writes to
 * the layout DB.
 */
sealed interface ExchangeTransportResult {
    /** Marker emitted when a transport starts (holder tracks in-flight state). */
    data object InFlight : ExchangeTransportResult

    data object Success : ExchangeTransportResult

    data class Failure(val kind: ExchangeTransportFailure) : ExchangeTransportResult
}

enum class ExchangeTransportFailure {
    CLIPBOARD_UNAVAILABLE,
    SHARE_TARGET_ABSENT,
    FILE_WRITE_FAILED,
}

/** Clipboard copy of the disclosed package text. */
class ClipboardExchangeTransport(
    private val context: Context,
) {
    fun copy(packageText: String): ExchangeTransportResult {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ExchangeTransportResult.Failure(ExchangeTransportFailure.CLIPBOARD_UNAVAILABLE)
        return try {
            manager.setPrimaryClip(ClipData.newPlainText("NunuLauncher exchange package", packageText))
            ExchangeTransportResult.Success
        } catch (_: RuntimeException) {
            ExchangeTransportResult.Failure(ExchangeTransportFailure.CLIPBOARD_UNAVAILABLE)
        }
    }
}

/**
 * Android Share Sheet (`ACTION_SEND` text/plain + chooser). Returns
 * [ExchangeTransportFailure.SHARE_TARGET_ABSENT] when no target can handle
 * the intent. The share fires from the caller's activity context.
 */
class ShareSheetExchangeTransport {
    fun share(activityContext: Context, packageText: String): ExchangeTransportResult {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, packageText)
            putExtra(Intent.EXTRA_TITLE, "NunuLauncher External Agent Exchange")
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (send.resolveActivity(activityContext.packageManager) == null &&
            chooser.resolveActivity(activityContext.packageManager) == null
        ) {
            return ExchangeTransportResult.Failure(ExchangeTransportFailure.SHARE_TARGET_ABSENT)
        }
        return try {
            activityContext.startActivity(chooser)
            ExchangeTransportResult.Success
        } catch (_: RuntimeException) {
            ExchangeTransportResult.Failure(ExchangeTransportFailure.SHARE_TARGET_ABSENT)
        }
    }
}

/**
 * SAF file export/import. Writing persists the disclosed package bytes to a
 * user-chosen URI; reading bounds the input at the #205 envelope limit + 1
 * byte so an oversized reply fails before it is fully materialized (spec 205
 * Decision 6).
 */
class FileExchangeTransport(private val context: Context) {

    fun write(packageText: String, uri: Uri): ExchangeTransportResult = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(packageText.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: return ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
        ExchangeTransportResult.Success
    } catch (_: IOException) {
        ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
    } catch (_: FileNotFoundException) {
        ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
    } catch (_: SecurityException) {
        ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
    } catch (_: RuntimeException) {
        ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
    }

    fun read(uri: Uri): FileExchangeRead = try {
        val limit = ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(limit + 1)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read > limit) {
                FileExchangeRead.Oversize
            } else {
                FileExchangeRead.Text(String(buffer, 0, read, Charsets.UTF_8))
            }
        } ?: FileExchangeRead.Failure
    } catch (_: IOException) {
        FileExchangeRead.Failure
    } catch (_: SecurityException) {
        FileExchangeRead.Failure
    } catch (_: RuntimeException) {
        FileExchangeRead.Failure
    }
}

sealed interface FileExchangeRead {
    data class Text(val text: String) : FileExchangeRead

    data object Oversize : FileExchangeRead

    data object Failure : FileExchangeRead
}
