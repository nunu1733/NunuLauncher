package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lawnchair.organizer.ui.UserDefinedCategoryAuthoringCoordinator
import app.lawnchair.organizer.ui.UserDefinedCategoryAuthoringResult
import app.lawnchair.organizer.ui.UserDefinedCategoryEntry
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #336: create/rename/delete surface for user-defined categories, next
 * to the #99 override editor in the Home Screen settings area. Every state is
 * typed and localized; raw category IDs, serials, and taxonomy vocabulary are
 * never rendered. Deletion states the current assignment count and that those
 * apps return to automatic classification — a silent "move to another
 * category" remap is never offered.
 */
@Composable
internal fun CustomCategoryPreferences(
    modifier: Modifier = Modifier,
    coordinator: UserDefinedCategoryAuthoringCoordinator? = null,
) {
    val context = LocalContext.current
    val authoring = coordinator ?: remember { UserDefinedCategoryAuthoringCoordinator(context) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var entries by remember { mutableStateOf<List<UserDefinedCategoryEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<Int?>(null) }
    // Editor state: null in list mode; [creating] selects the Create editor,
    // a non-null [editorTarget] the Rename editor for that entry.
    var creating by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<UserDefinedCategoryEntry?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var inlineFeedback by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<UserDefinedCategoryEntry?>(null) }
    // The category whose delete committed step 1 (assignments removed) but
    // failed step 2: rendered truthfully with a completion retry.
    var partialDeleteTarget by remember { mutableStateOf<UserDefinedCategoryEntry?>(null) }

    fun reload(clearStatus: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { authoring.load() }
            when (result) {
                is UserDefinedCategoryAuthoringResult.Loaded -> {
                    entries = result.entries
                    loaded = true
                    if (clearStatus) statusMessage = null
                }

                else -> {
                    loaded = true
                    statusMessage = R.string.organizer_custom_category_unavailable
                }
            }
        }
    }

    fun onAuthoringFailure(result: UserDefinedCategoryAuthoringResult) {
        when (result) {
            UserDefinedCategoryAuthoringResult.OrganizationRunActive ->
                statusMessage = R.string.organizer_custom_category_busy

            UserDefinedCategoryAuthoringResult.Conflict ->
                statusMessage = R.string.organizer_custom_category_conflict

            UserDefinedCategoryAuthoringResult.UnsupportedSchema,
            UserDefinedCategoryAuthoringResult.CatalogUnreadable,
            UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable,
            -> statusMessage = R.string.organizer_custom_category_unavailable

            UserDefinedCategoryAuthoringResult.WriteFailed,
            UserDefinedCategoryAuthoringResult.VerificationFailed,
            -> statusMessage = R.string.organizer_custom_category_failed

            else -> Unit
        }
    }

    fun delete(entry: UserDefinedCategoryEntry) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { authoring.delete(entry.id) }
            when (result) {
                is UserDefinedCategoryAuthoringResult.Deleted -> {
                    partialDeleteTarget = null
                    reload(clearStatus = true)
                }

                is UserDefinedCategoryAuthoringResult.PartialDelete -> {
                    partialDeleteTarget = entry
                    reload(clearStatus = false)
                }

                else -> onAuthoringFailure(result)
            }
        }
    }

    LaunchedEffect(Unit) { reload(clearStatus = false) }
    LaunchedEffect(creating, editorTarget, pendingDelete, statusMessage) {
        if (!creating && editorTarget == null && pendingDelete == null) focusRequester.requestFocus()
    }

    PreferenceScaffold(
        label = stringResource(R.string.organizer_custom_category_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            item {
                Text(
                    text = statusMessage?.let { stringResource(it) }
                        ?: stringResource(R.string.organizer_custom_category_summary),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when {
                // Issue #336 (delete step-2 partial state): the truthful
                // committed state — assignments removed, the empty category
                // remains — renders as such (never as an undone operation),
                // and the retry completes the delete.
                partialDeleteTarget != null -> {
                    item(key = "partial-delete-state") {
                        ClickablePreference(
                            label = stringResource(R.string.organizer_custom_category_retry),
                            subtitle = stringResource(R.string.organizer_custom_category_partial_delete),
                            onClick = { partialDeleteTarget?.let(::delete) },
                        )
                    }
                    item(key = "partial-delete-back") {
                        ClickablePreference(
                            label = stringResource(R.string.organizer_custom_category_back_to_list),
                            onClick = {
                                partialDeleteTarget = null
                                reload(clearStatus = true)
                            },
                        )
                    }
                }

                creating || editorTarget != null -> {
                    item(key = "name-editor") {
                        val fieldLabel = if (creating) {
                            stringResource(R.string.organizer_custom_category_name_label)
                        } else {
                            stringResource(R.string.organizer_custom_category_rename_label)
                        }
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                inlineFeedback = null
                            },
                            label = { Text(fieldLabel) },
                            supportingText = inlineFeedback?.let { feedback -> { Text(stringResource(feedback)) } },
                            isError = inlineFeedback != null,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .testTag("custom-category-name-field"),
                        )
                    }
                    item(key = "name-editor-save") {
                        val target = editorTarget
                        ClickablePreference(
                            label = stringResource(
                                if (creating) R.string.organizer_custom_category_save else R.string.organizer_custom_category_rename_confirm,
                            ),
                            onClick = {
                                if (creating) {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { authoring.create(nameInput) }
                                        when (result) {
                                            is UserDefinedCategoryAuthoringResult.Created -> {
                                                creating = false
                                                nameInput = ""
                                                inlineFeedback = null
                                                reload(clearStatus = true)
                                            }

                                            UserDefinedCategoryAuthoringResult.InvalidName ->
                                                inlineFeedback = R.string.organizer_custom_category_error_invalid_name

                                            UserDefinedCategoryAuthoringResult.DuplicateName ->
                                                inlineFeedback = R.string.organizer_custom_category_error_duplicate_name

                                            UserDefinedCategoryAuthoringResult.CapacityExceeded ->
                                                inlineFeedback = R.string.organizer_custom_category_error_capacity

                                            else -> onAuthoringFailure(result)
                                        }
                                    }
                                } else if (target != null) {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { authoring.rename(target.id, nameInput) }
                                        when (result) {
                                            is UserDefinedCategoryAuthoringResult.Renamed,
                                            is UserDefinedCategoryAuthoringResult.NoChange,
                                            -> {
                                                editorTarget = null
                                                nameInput = ""
                                                inlineFeedback = null
                                                reload(clearStatus = true)
                                            }

                                            UserDefinedCategoryAuthoringResult.InvalidName ->
                                                inlineFeedback = R.string.organizer_custom_category_error_invalid_name

                                            UserDefinedCategoryAuthoringResult.DuplicateName ->
                                                inlineFeedback = R.string.organizer_custom_category_error_duplicate_name

                                            else -> onAuthoringFailure(result)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    item(key = "name-editor-cancel") {
                        ClickablePreference(
                            label = stringResource(R.string.organizer_custom_category_cancel),
                            onClick = {
                                creating = false
                                editorTarget = null
                                nameInput = ""
                                inlineFeedback = null
                            },
                        )
                    }
                }

                else -> {
                    item(key = "create-action") {
                        ClickablePreference(
                            label = stringResource(R.string.organizer_custom_category_create),
                            onClick = {
                                nameInput = ""
                                inlineFeedback = null
                                creating = true
                            },
                        )
                    }
                    entries.forEach { entry ->
                        item(key = "entry-${entry.id.value}") {
                            CategoryEntryRow(
                                entry = entry,
                                onRename = {
                                    nameInput = entry.displayName
                                    inlineFeedback = null
                                    editorTarget = entry
                                },
                                modifier = Modifier,
                            )
                        }
                        item(key = "delete-${entry.id.value}") {
                            CategoryEntryDeleteRow(entry = entry, onDelete = { pendingDelete = entry })
                        }
                    }
                    if (!loaded) {
                        item { Text(stringResource(R.string.all_apps_loading_message)) }
                    }
                }
            }
        }
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.organizer_custom_category_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.organizer_custom_category_delete_text,
                        deleteTarget.assignedCount,
                        deleteTarget.displayName,
                        deleteTarget.assignedCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(deleteTarget)
                }) {
                    Text(stringResource(R.string.organizer_custom_category_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.organizer_custom_category_cancel))
                }
            },
        )
    }
}

@Composable
private fun CategoryEntryRow(
    entry: UserDefinedCategoryEntry,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val renameLabel = stringResource(R.string.organizer_custom_category_rename_action, entry.displayName)
    val customMarker = stringResource(R.string.organizer_category_override_custom_marker)
    PreferenceTemplate(
        title = { Text(entry.displayName) },
        description = { Text(customMarker) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onRename)
            .semantics { contentDescription = renameLabel },
        verticalPadding = 12.dp,
    )
}

@Composable
private fun CategoryEntryDeleteRow(
    entry: UserDefinedCategoryEntry,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countText = pluralStringResource(
        R.plurals.organizer_custom_category_entry_count,
        entry.assignedCount,
        entry.assignedCount,
    )
    val deleteLabel = stringResource(R.string.organizer_custom_category_delete_action, entry.displayName)
    ClickablePreference(
        label = stringResource(R.string.organizer_custom_category_delete_row),
        subtitle = countText,
        modifier = modifier.semantics { contentDescription = deleteLabel },
        onClick = onDelete,
    )
}
