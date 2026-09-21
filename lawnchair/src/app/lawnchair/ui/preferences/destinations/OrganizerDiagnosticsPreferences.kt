package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairApp
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.export.OrganizerDiagnosticsExportPreference
import app.lawnchair.organizer.ui.exchange.ExchangeImportFailureDiagnostics
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R

/**
 * Issue #138: supported release Settings route for the organizer diagnostics
 * journal export. Composes the existing #67 export action with the single live
 * diagnostics port; no second journal/export seam is created.
 */
@Composable
fun OrganizerDiagnosticsPreferences(
    modifier: Modifier = Modifier,
    port: DiagnosticsPort? = null,
) {
    PreferenceLayout(
        label = stringResource(id = R.string.organizer_diagnostics_title),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        // Issue #123: match the sibling organizer screens' body text treatment.
        Text(
            text = stringResource(id = R.string.organizer_diagnostics_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        // Issue #373 (D-11): the 診断を開く means of the import failure face
        // records the current attempt's typed cause into the process-scoped
        // transient holder; while a recording exists (process alive), this
        // auxiliary row names it. Contract vocabulary only — never user data,
        // never journaled, lost on process death together with the holder.
        ExchangeImportFailureDiagnostics.recent?.let { recent ->
            Text(
                text = stringResource(id = R.string.organizer_diagnostics_recent_import_failure),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("organizer-diagnostics-recent-import-failure"),
            )
            Text(
                text = recent.typeName,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("organizer-diagnostics-recent-import-failure-type"),
            )
            Text(
                text = recent.explanation,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .testTag("organizer-diagnostics-recent-import-failure-explanation"),
            )
        }
        OrganizerDiagnosticsExportPreference(
            diagnosticsPort = port ?: LawnchairApp.instance.layoutApplicationModule.diagnostics,
        )
    }
}
