package app.lawnchair.organizer.diagnostics.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The captured export timestamp is held in saved instance state only until
 * the picker result is delivered. 0 marks "no pending export session".
 */
private const val NO_PENDING_EXPORT = 0L

/**
 * Toast requires a prepared Looper on the calling thread; post to the main
 * looper so the export notification is safe from any dispatcher that resumes
 * the write (the write itself runs on Dispatchers.IO).
 */
private fun showExportToast(context: Context, messageRes: Int) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
    }
}

/**
 * A ClickablePreference composable that triggers the SAF CreateDocument flow
 * for exporting the organizer diagnostics journal.
 *
 * The export timestamp is captured exactly once when the SAF intent is
 * created; it derives both the suggested filename and the header's
 * `exportedAtWallMillis`, so the two always agree (Issue #288). The value
 * survives activity recreation / process death while the picker is open via
 * saved instance state, and is consumed (read once, cleared immediately) at
 * result delivery — after that, ownership belongs to the in-flight write, so
 * no stale pending session can outlive a delivered result.
 *
 * AC-67-08: Export is explicit-user-initiated only.
 * AC-67-13: The control has a localized accessible label and remains operable
 * with TalkBack/keyboard/switch navigation.
 */
@Composable
fun OrganizerDiagnosticsExportPreference(diagnosticsPort: DiagnosticsPort) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExportedAtWallMillis by rememberSaveable { mutableLongStateOf(NO_PENDING_EXPORT) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // A delivered result is never re-delivered by the registry, and a
        // mid-write recreation cancels the write coroutine — so consume the
        // session before any outcome validation; every delivery path
        // (cancel, OK + null URI, missing session) leaves it empty.
        val exportedAtWallMillis = pendingExportedAtWallMillis
        pendingExportedAtWallMillis = NO_PENDING_EXPORT

        if (result.resultCode != Activity.RESULT_OK || exportedAtWallMillis == NO_PENDING_EXPORT) {
            // User cancelled or stale result — journal remains intact, no automatic retry
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExportWriter.writeToUri(context, diagnosticsPort, uri, exportedAtWallMillis)
                }
                showExportToast(context, R.string.organizer_diagnostics_export_success)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Write failure — journal remains intact, no network fallback
                showExportToast(context, R.string.organizer_diagnostics_export_error)
            }
        }
    }

    ClickablePreference(
        label = stringResource(id = R.string.organizer_diagnostics_export_label),
        subtitle = stringResource(id = R.string.organizer_diagnostics_export_subtitle),
        onClick = {
            val exportedAtWallMillis = System.currentTimeMillis()
            pendingExportedAtWallMillis = exportedAtWallMillis
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/jsonl"
                // Timestamped technical identifier from the pure formatter,
                // not UI copy.
                putExtra(Intent.EXTRA_TITLE, DiagnosticsExportFilename.format(exportedAtWallMillis))
            }
            launcher.launch(intent)
        },
    )
}
