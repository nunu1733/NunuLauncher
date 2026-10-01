/*
 * Issue #497: destination policy row for the home-screen settings
 * (ADR-0015 Decision 15). The three choices map onto the existing
 * "add icon to home" toggle and a designated-folder id — no independent
 * "don't add" toggle exists (the toggle's off state IS that choice). The
 * one-shot fallback notice is consumed on display here.
 */
package app.lawnchair.homeedit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.AppDestinationNotice
import app.lawnchair.homeedit.AppDestinationPolicyPrefs
import app.lawnchair.homeedit.HomeEditExecutor
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.pm.UserCache

/**
 * The destination policy row. Place right below the "add new apps to home
 * screen" switch; disabled while the home screen is locked (no automatic
 * adds happen while locked, matching the upstream switch).
 */
@Composable
fun DestinationPolicyPreference(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = preferenceManager()
    val addIconToHomeAdapter = prefs.addIconToHome.getAdapter()
    val destinationAdapter = prefs.newAppDestination.getAdapter()

    var showChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }
    var folderOptions by remember { mutableStateOf<List<HomeEditExecutor.FolderOption>?>(null) }
    // One-shot fallback notice: consumed exactly once when the row displays
    // (spec 497: the settings row tells the user once per fallback).
    val pendingNotice = remember { AppDestinationNotice.consumePending(context) }

    val addIconOn = addIconToHomeAdapter.state.value
    val destinationValue = destinationAdapter.state.value
    val selectedFolderId = AppDestinationPolicyPrefs.folderIdFromValue(destinationValue)
    val launcher = LawnchairLauncher.instance
    val executor = remember(launcher) { launcher?.let { HomeEditExecutor(it) } }
    LaunchedEffect(showFolderPicker) {
        if (showFolderPicker) executor?.fetchAllFolderOptions { fetched -> folderOptions = fetched }
    }

    val folderSummary = selectedFolderId
        ?.let { folderTitle(launcher, it) }
        ?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.homeedit_folder_default_label)
    val summary = buildString {
        append(
            when {
                !addIconOn -> stringResource(R.string.destination_policy_summary_dont_add)

                selectedFolderId != null ->
                    stringResource(R.string.destination_policy_summary_folder, folderSummary)

                else -> stringResource(R.string.destination_policy_summary_upstream)
            },
        )
        if (pendingNotice != null) {
            append("\n")
            append(noticeText(pendingNotice))
        }
    }

    PreferenceTemplate(
        title = { Text(stringResource(R.string.destination_policy_label)) },
        description = { Text(summary) },
        enabled = enabled,
        modifier = modifier.clickable(enabled) { showChoiceDialog = true },
    )

    if (showChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showChoiceDialog = false },
            title = { Text(stringResource(R.string.destination_policy_dialog_title)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            AppDestinationPolicyPrefs.setUpstream(context)
                            if (!addIconOn) addIconToHomeAdapter.onChange(true)
                        },
                    ) { Text(stringResource(R.string.destination_policy_choice_upstream)) }
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            showFolderPicker = true
                        },
                    ) { Text(stringResource(R.string.destination_policy_choice_folder)) }
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            addIconToHomeAdapter.onChange(false)
                        },
                    ) { Text(stringResource(R.string.destination_policy_choice_dont_add)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChoiceDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showFolderPicker) {
        DestinationFolderPickerDialog(
            launcher = launcher,
            options = folderOptions,
            onDismiss = { showFolderPicker = false },
            onPick = { folderId ->
                showFolderPicker = false
                AppDestinationPolicyPrefs.setDesignatedFolder(context, folderId)
                if (!addIconOn) addIconToHomeAdapter.onChange(true)
            },
            onClear = {
                showFolderPicker = false
                AppDestinationPolicyPrefs.setUpstream(context)
            },
        )
    }
}

@Composable
private fun DestinationFolderPickerDialog(
    launcher: LawnchairLauncher?,
    options: List<HomeEditExecutor.FolderOption>?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val mainSerial = UserCache.getInstance(context).getSerialNumberForUser(
        android.os.Process.myUserHandle(),
    )
    val multipleProfiles = (options ?: emptyList()).map { it.userSerial }.distinct().size > 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.destination_policy_folder_picker_title)) },
        text = {
            Column {
                val loaded = options
                if (loaded == null) {
                    Text(stringResource(R.string.all_apps_loading_message))
                } else {
                    loaded.forEach { option ->
                        val label = stringResource(
                            R.string.homeedit_folder_entry,
                            folderTitle(launcher, option.folderId).ifEmpty {
                                stringResource(R.string.homeedit_folder_default_label)
                            },
                            option.itemCount,
                        ) + if (multipleProfiles && option.userSerial != mainSerial) {
                            " " + stringResource(R.string.destination_policy_other_profile)
                        } else {
                            ""
                        }
                        Text(
                            text = label,
                            modifier = Modifier
                                .clickable { onPick(option.folderId) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.destination_policy_folder_stop),
                        modifier = Modifier
                            .clickable { onClear() }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun folderTitle(launcher: LawnchairLauncher?, folderId: Int): CharSequence {
    val icon = launcher?.workspace?.getHomescreenIconByItemId(folderId)
    val info = (icon as? FolderIcon)?.mInfo
    return info?.title?.takeIf { it.isNotEmpty() } ?: ""
}

/** Maps a typed fallback reason to the localized one-shot notice text. */
@Composable
private fun noticeText(reasonKey: String): String = when (reasonKey) {
    "DEST_FOLDER_MISSING" -> stringResource(R.string.destination_policy_notice_missing)
    "DEST_PROFILE_MISMATCH" -> stringResource(R.string.destination_policy_notice_profile)
    "DEST_DOCK_FOLDER" -> stringResource(R.string.destination_policy_notice_dock)
    "DEST_CONSTRAINT_VIOLATION" -> stringResource(R.string.destination_policy_notice_constraint)
    else -> stringResource(R.string.destination_policy_notice_snapshot)
}
