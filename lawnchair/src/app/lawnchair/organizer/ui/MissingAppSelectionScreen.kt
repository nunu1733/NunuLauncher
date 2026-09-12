package app.lawnchair.organizer.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.planning.CandidateTarget
import com.android.launcher3.R
import com.android.launcher3.pm.UserCache

/**
 * Issue #228: pure selection state for the missing-app selection surface
 * (spec §2). Selection is keyed by stable identity and survives query/filter
 * changes; "select all" applies to the currently matching (displayed) set and
 * keeps every other selection; "clear all" clears the whole candidate set's
 * selection regardless of the query. All functions are deterministic and
 * side-effect free — JVM tests drive them directly.
 */
data class MissingAppSelectionState(
    val candidates: List<DetectedCandidate>,
    val selected: Set<CandidateTarget.AppKey>,
    val query: String = "",
) {
    val selectedCount: Int get() = selected.size

    fun matches(candidate: DetectedCandidate): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return candidate.label.contains(needle, ignoreCase = true) ||
            candidate.target.component.value.contains(needle, ignoreCase = true)
    }

    val displayed: List<DetectedCandidate> get() = candidates.filter(::matches)

    fun toggle(candidate: DetectedCandidate): MissingAppSelectionState = copy(
        selected = if (candidate.target in selected) selected - candidate.target else selected + candidate.target,
    )

    /** Selects every candidate matching the current query; other selections persist. */
    fun selectAllMatching(): MissingAppSelectionState = copy(
        selected = selected + displayed.map { it.target },
    )

    /** Clears the selection of the whole candidate set, regardless of query. */
    fun clearAll(): MissingAppSelectionState = copy(selected = emptySet())

    fun withQuery(query: String): MissingAppSelectionState = copy(query = query)
}

/**
 * Issue #228: the selection surface as LazyColumn items — the host preference
 * list owns the scroll; this emits the heading, the announced whole-selection
 * count, search, Select all / Clear all, one row per displayed candidate, and
 * the confirm/cancel pair. Browsing never writes; confirming hands the
 * selected identities to [ManualOrganizationRun.confirmSelection].
 */
fun LazyListScope.missingAppSelectionItems(
    selection: MissingAppSelectionState,
    onSelectionChange: (MissingAppSelectionState) -> Unit,
    onConfirm: (Set<CandidateTarget.AppKey>) -> Unit,
    onCancel: () -> Unit,
) {
    item(key = "missing-app-selection-heading") {
        Text(
            text = stringResource(R.string.manual_organization_missing_apps_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("missing-app-selection-title"),
        )
    }
    item(key = "missing-app-selection-count") {
        Text(
            text = if (selection.candidates.isEmpty()) {
                stringResource(R.string.manual_organization_missing_apps_empty)
            } else {
                pluralStringResource(
                    R.plurals.manual_organization_missing_apps_selected_count,
                    selection.selectedCount,
                    selection.selectedCount,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    item(key = "missing-app-selection-search") {
        OutlinedTextField(
            value = selection.query,
            onValueChange = { onSelectionChange(selection.withQuery(it)) },
            label = { Text(stringResource(R.string.manual_organization_missing_apps_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("missing-app-selection-search"),
        )
    }
    item(key = "missing-app-selection-bulk-actions") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { onSelectionChange(selection.selectAllMatching()) },
                enabled = selection.displayed.isNotEmpty(),
                modifier = Modifier.testTag("missing-app-selection-select-all"),
            ) {
                Text(stringResource(R.string.manual_organization_missing_apps_select_all))
            }
            OutlinedButton(
                onClick = { onSelectionChange(selection.clearAll()) },
                enabled = selection.selected.isNotEmpty(),
                modifier = Modifier.testTag("missing-app-selection-clear-all"),
            ) {
                Text(stringResource(R.string.manual_organization_missing_apps_clear_all))
            }
        }
    }
    val displayed = selection.displayed
    items(
        count = displayed.size,
        key = { index -> "missing-app-selection-row-" + displayed[index].target.component.value + ":" + displayed[index].target.profile.value },
    ) { index ->
        val candidate = displayed[index]
        MissingAppSelectionRow(
            candidate = candidate,
            checked = candidate.target in selection.selected,
            onToggle = { onSelectionChange(selection.toggle(candidate)) },
        )
    }
    item(key = "missing-app-selection-actions") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onConfirm(selection.selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("missing-app-selection-confirm"),
            ) {
                Text(stringResource(R.string.manual_organization_missing_apps_continue))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.manual_organization_cancel))
            }
        }
    }
}

/** One multi-select candidate row; TalkBack reads label + checked state as one node. */
@Composable
private fun MissingAppSelectionRow(
    candidate: DetectedCandidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val checkedText = stringResource(R.string.manual_organization_missing_apps_state_checked)
    val uncheckedText = stringResource(R.string.manual_organization_missing_apps_state_unchecked)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("missing-app-selection-row"),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        CandidateAppIcon(candidate = candidate, modifier = Modifier.padding(start = 8.dp))
        Text(
            text = candidate.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { stateDescription = if (checked) checkedText else uncheckedText },
        )
    }
}

/**
 * Issue #228: app icon for a candidate row. Resolution follows the
 * category-override authoring precedent — the UI reads the launcher-authorized
 * LauncherApps face directly for presentation only; identity never depends on
 * the icon. A missing icon renders nothing (the label carries the row).
 */
@Composable
private fun CandidateAppIcon(candidate: DetectedCandidate, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(candidate.target) { resolveIcon(context, candidate.target) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(24.dp),
        )
    }
}

private fun resolveIcon(context: Context, target: CandidateTarget.AppKey): Bitmap? = try {
    val appContext = context.applicationContext
    val userCache = UserCache.INSTANCE.get(appContext)
    val launcherApps = appContext.getSystemService(LauncherApps::class.java) ?: return null
    val user = userCache.userProfiles.firstOrNull { handle ->
        try {
            userCache.getSerialNumberForUser(handle).toString() == target.profile.value
        } catch (_: RuntimeException) {
            false
        }
    } ?: return null
    val info = launcherApps.getActivityList(target.component.value.substringBefore('/'), user)
        .firstOrNull { it.componentName.flattenToString() == target.component.value }
        ?: return null
    drawableToBitmap(info.getBadgedIcon(0))
} catch (_: RuntimeException) {
    null
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
