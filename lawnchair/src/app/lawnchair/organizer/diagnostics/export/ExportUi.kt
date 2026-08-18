package app.lawnchair.organizer.diagnostics.export

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A ClickablePreference composable that triggers the SAF CreateDocument flow
 * for exporting the organizer diagnostics journal.
 *
 * AC-67-08: Export is explicit-user-initiated only.
 * AC-67-13: The control has a localized accessible label and remains operable
 * with TalkBack/keyboard/switch navigation.
 */
@Composable
fun OrganizerDiagnosticsExportPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User cancelled — journal remains intact, no automatic retry
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExportWriter.writeToUri(context, uri)
                }
                Toast.makeText(
                    context,
                    R.string.organizer_diagnostics_export_success,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                // Write failure — journal remains intact, no network fallback
                Toast.makeText(
                    context,
                    R.string.organizer_diagnostics_export_error,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    ClickablePreference(
        label = stringResource(id = R.string.organizer_diagnostics_export_label),
        subtitle = stringResource(id = R.string.organizer_diagnostics_export_subtitle),
        onClick = {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/jsonl"
                putExtra(Intent.EXTRA_TITLE, "organizer_diagnostics.jsonl")
            }
            launcher.launch(intent)
        },
    )
}
