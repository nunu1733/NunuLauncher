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
import app.lawnchair.homeedit.AppDestinationPolicyTextKeys
import app.lawnchair.homeedit.AppDestinationSummaryState
import app.lawnchair.homeedit.CategoryMappingKey
import app.lawnchair.homeedit.DestinationSummaryKind
import app.lawnchair.homeedit.HomeEditExecutor
import app.lawnchair.homeedit.destinationNoticeText
import app.lawnchair.homeedit.destinationSummaryText
import app.lawnchair.organizer.planning.CategoryId
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
    // Issue #594: the category mapping editor is a second-level dialog of the
    // choice dialog (per-category folder rows; unset rows fall back upstream).
    var showCategoryEditor by rememberSaveable { mutableStateOf(false) }
    var folderOptions by remember { mutableStateOf<List<HomeEditExecutor.FolderOption>?>(null) }
    // One-shot fallback notice: consumed exactly once when the row displays
    // (spec 497: the settings row tells the user once per fallback).
    val pendingNotice = remember { AppDestinationNotice.consumePending(context) }

    val addIconOn = addIconToHomeAdapter.state.value
    val destinationValue = destinationAdapter.state.value
    val categoryMode = destinationValue == AppDestinationPolicyPrefs.VALUE_CATEGORY
    val selectedFolderId = AppDestinationPolicyPrefs.folderIdFromValue(destinationValue)
    val launcher = LawnchairLauncher.instance
    val executor = remember(launcher) { launcher?.let { HomeEditExecutor(it) } }
    LaunchedEffect(showFolderPicker) {
        if (showFolderPicker) executor?.fetchAllFolderOptions { fetched -> folderOptions = fetched }
    }

    // The folder's existence and its title are distinct (spec AC-3): a
    // designated id that no longer resolves shows the "choose again" state
    // instead of a default-labelled folder.
    val folderIcon = selectedFolderId?.let { folderIcon(launcher, it) }
    val folderExists = folderIcon != null
    val folderDisplayTitle = (folderIcon?.mInfo?.title as? String)?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.homeedit_folder_default_label)
    val summaryKind = AppDestinationSummaryState.resolve(
        addIconOn = addIconOn,
        designatedFolderId = selectedFolderId,
        folderExists = folderExists,
        folderTitle = folderIcon?.mInfo?.title?.toString(),
        categoryMode = categoryMode,
    )
    val summary = buildString {
        append(
            when (summaryKind) {
                DestinationSummaryKind.FOLDER_NAMED ->
                    stringResource(destinationSummaryText(summaryKind), folderDisplayTitle)

                DestinationSummaryKind.FOLDER_UNTITLED ->
                    stringResource(destinationSummaryText(summaryKind), folderDisplayTitle)

                else -> stringResource(destinationSummaryText(summaryKind))
            },
        )
        if (pendingNotice != null) {
            append("\n")
            append(stringResource(destinationNoticeText(pendingNotice)))
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
            title = { Text(stringResource(AppDestinationPolicyTextKeys.dialogTitle)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            AppDestinationPolicyPrefs.setUpstream(context)
                            if (!addIconOn) addIconToHomeAdapter.onChange(true)
                        },
                    ) { Text(stringResource(AppDestinationPolicyTextKeys.choiceUpstream)) }
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            showFolderPicker = true
                        },
                    ) { Text(stringResource(AppDestinationPolicyTextKeys.choiceFolder)) }
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            showCategoryEditor = true
                        },
                    ) { Text(stringResource(AppDestinationPolicyTextKeys.choiceCategory)) }
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            addIconToHomeAdapter.onChange(false)
                        },
                    ) { Text(stringResource(AppDestinationPolicyTextKeys.choiceDontAdd)) }
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

    if (showCategoryEditor) {
        DestinationCategoryEditorDialog(
            context = context,
            launcher = launcher,
            executor = executor,
            previousAddIconOn = addIconOn,
            setAddIconOn = { addIconToHomeAdapter.onChange(it) },
            onDismiss = { showCategoryEditor = false },
            onEdit = { },
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
        title = { Text(stringResource(AppDestinationPolicyTextKeys.folderPickerTitle)) },
        text = {
            Column {
                val loaded = options
                if (loaded == null) {
                    Text(stringResource(R.string.all_apps_loading_message))
                } else {
                    loaded.forEach { option ->
                        val title = folderIcon(launcher, option.folderId)?.mInfo?.title
                            ?.toString()?.takeIf { it.isNotEmpty() }
                            ?: stringResource(R.string.homeedit_folder_default_label)
                        val label = stringResource(
                            R.string.homeedit_folder_entry,
                            title,
                            option.itemCount,
                        ) + if (multipleProfiles && option.userSerial != mainSerial) {
                            " " + stringResource(AppDestinationPolicyTextKeys.otherProfile)
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
                        text = stringResource(AppDestinationPolicyTextKeys.folderStop),
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

private fun folderIcon(launcher: LawnchairLauncher?, folderId: Int): FolderIcon? = launcher?.workspace?.getHomescreenIconByItemId(folderId) as? FolderIcon

/**
 * Issue #594: per-category folder mapping editor. Rows are the mappable
 * categories (built-in ids reachable at capture time: S2's 8 Android
 * categories plus S5's TOOLS; user-defined categories join via S1 overrides),
 * keyed by the existing stable identities — display names are presentation
 * only, never persistence keys (ADR-0017 Decision 3). An unset row falls
 * back upstream at capture (spec 594 Scenario「分類・mappingが解決できない
 * 場合」), and the coverage limitation is stated in the dialog body.
 */
@Composable
private fun DestinationCategoryEditorDialog(
    context: android.content.Context,
    launcher: LawnchairLauncher?,
    executor: HomeEditExecutor?,
    previousAddIconOn: Boolean,
    setAddIconOn: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val mainSerial = UserCache.getInstance(context).getSerialNumberForUser(
        android.os.Process.myUserHandle(),
    )
    // The mapping binds a category to a folder of its own profile. The editor
    // lists one row per (profile x mappable category): the personal profile's
    // S1 overrides come from this side's override store; work-profile rows
    // reuse the existing "another profile" presentation convention of the
    // folder picker so a work mapping is creatable from this single surface
    // (spec 594 profile-separation scenarios require a real settings path).
    var entries by remember {
        mutableStateOf(AppDestinationPolicyPrefs.categoryMappings(context).toMutableMap())
    }
    var pickerFor by remember { mutableStateOf<CategoryMappingKey?>(null) }
    var folderOptions by remember { mutableStateOf<List<HomeEditExecutor.FolderOption>?>(null) }
    var pickableCategories by remember {
        mutableStateOf<List<Pair<CategoryMappingKey, String>>>(emptyList())
    }

    // Mappable categories: S2's 8 Android categories plus S5's TOOLS from the
    // existing bundle (per profile), plus user-defined categories that already
    // have at least one S1 override in that profile (only they can be resolved
    // at capture time). Profile serials come from the launcher's own profile
    // enumeration (UserCache.getUserProfiles), NOT from existing mappings — a
    // first-ever work mapping must be creatable from a zero-mapping state
    // (spec 594 profile-separation scenarios; review round 2 high 1).
    LaunchedEffect(Unit) {
        val bundle = app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource.readActive()
        val overrideStore = app.lawnchair.organizer.rules.CategoryOverrideStoreModule.source(context)
        val userCache = UserCache.getInstance(context)
        val profileSerials = userCache.userProfiles
            .map { userCache.getSerialNumberForUser(it) }
            .filter { it >= 0 }
            .distinct()
        val builtInRows = when (bundle) {
            is app.lawnchair.organizer.rules.BundleReadResult.Ready ->
                app.lawnchair.homeedit.CategoryMappingRowBuilder
                    .buildCategoryRows(
                        bundle.bundle.classification.androidCategoryMapping.values.map { it.value },
                        bundle.bundle.classification.googleCategory.value,
                        profileSerials,
                    )

            else -> emptyList()
        }
        // Per-profile S1 rows: each profile reads its own override store view.
        // No cross-profile copying of personal rows (review round 2 high 1).
        val s1Rows = profileSerials.flatMap { serial ->
            userDefinedRowsForProfile(overrideStore, context, serial)
        }
        pickableCategories = (builtInRows + s1Rows).distinct().map { key ->
            key to categoryLabel(context, key.categoryIdentity)
        }
    }
    LaunchedEffect(pickerFor) {
        if (pickerFor != null) {
            executor?.fetchFolderOptionsForProfile(pickerFor!!.userSerial) { fetched ->
                folderOptions = fetched
            }
        }
    }

    // The Cancel path routes both the explicit button and the
    // outside-tap/back dismiss through the tested seam, with the SAME
    // production mutators the Done path uses (review round 5 medium: the seam
    // must observe the real callbacks so the 0-call oracle detects future
    // writes added to the Cancel path).
    val cancelThroughSeam: () -> Unit = {
        app.lawnchair.homeedit.CategoryModeEditorCommit.onCancel(
            currentEntries = entries,
            currentAddIconOn = previousAddIconOn,
            persistMappings = { AppDestinationPolicyPrefs.setCategoryMappings(context, it) },
            selectCategoryMode = { AppDestinationPolicyPrefs.setCategoryMode(context) },
            setAddIconOn = setAddIconOn,
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = cancelThroughSeam,
        title = { Text(stringResource(AppDestinationPolicyTextKeys.categoryEditorTitle)) },
        text = {
            Column {
                Text(
                    stringResource(AppDestinationPolicyTextKeys.categoryEditorCoverage),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (pickableCategories.isEmpty()) {
                    Text(stringResource(AppDestinationPolicyTextKeys.categoryEditorEmpty))
                } else {
                    pickableCategories.forEach { (key, label) ->
                        val rowLabel = categoryRowLabel(context, launcher, label, key, entries)
                        Text(
                            text = rowLabel,
                            modifier = Modifier
                                .clickable {
                                    pickerFor = key
                                    folderOptions = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Done is the category-mode activation point (spec 594
                    // Scope 4択拡張): mapping save + category-mode selection +
                    // AC-1 toggle restore are one transition (review round 2
                    // medium 1 — the state helper is JVM-regression-tested).
                    app.lawnchair.homeedit.CategoryModeEditorCommit.onDone(
                        entries = entries,
                        previousAddIconOn = previousAddIconOn,
                        persistMappings = { AppDestinationPolicyPrefs.setCategoryMappings(context, it) },
                        selectCategoryMode = { AppDestinationPolicyPrefs.setCategoryMode(context) },
                        setAddIconOn = setAddIconOn,
                    )
                    onEdit()
                    onDismiss()
                },
            ) { Text(stringResource(AppDestinationPolicyTextKeys.categoryEditorDone)) }
        },
        dismissButton = {
            TextButton(onClick = cancelThroughSeam) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )

    if (pickerFor != null) {
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text(stringResource(AppDestinationPolicyTextKeys.folderPickerTitle)) },
            text = {
                Column {
                    val loaded = folderOptions
                    if (loaded == null) {
                        Text(stringResource(R.string.all_apps_loading_message))
                    } else {
                        loaded.forEach { option ->
                            val title = folderIcon(launcher, option.folderId)?.mInfo?.title
                                ?.toString()?.takeIf { it.isNotEmpty() }
                                ?: stringResource(R.string.homeedit_folder_default_label)
                            Text(
                                text = stringResource(
                                    R.string.homeedit_folder_entry,
                                    title,
                                    option.itemCount,
                                ),
                                modifier = Modifier
                                    .clickable {
                                        entries[pickerFor!!] = option.folderId.toString()
                                        pickerFor = null
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                        Text(
                            text = stringResource(AppDestinationPolicyTextKeys.folderStop),
                            modifier = Modifier
                                .clickable {
                                    entries.remove(pickerFor!!)
                                    pickerFor = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickerFor = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** mapping行の表示label。built-inは既存のpresentation resource、user-definedは表示名（keyには載らない）。 */
private fun categoryLabel(context: android.content.Context, identity: String): String {
    val builtIn = app.lawnchair.organizer.ui.CategoryOverrideCategoryPresentations
        .findForCategory(CategoryId(identity))
    if (builtIn != null) {
        return context.getString(builtIn.labelRes)
    }
    // User-defined: the u:<uuid> canonical value is the key; the display name
    // comes from the existing user-defined catalog (presentation only).
    val uuid = identity.removePrefix("u:")
    val displayName = app.lawnchair.organizer.rules.UserDefinedCategoryStoreModule.source(context)
        .read()
        .let { read ->
            (read as? app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult.Ready)
                ?.snapshot?.categories
                ?.firstOrNull { it.id.value == uuid }?.displayName
        }
    // Fail closed: an unresolvable user-defined identity is an opaque internal
    // value and must never surface (CategoryIdentity contract). The stale row
    // is shown as unavailable, not as a raw UUID.
    return displayName ?: context.getString(R.string.destination_policy_category_unavailable)
}

/**
 * One mapping row's label: "<category>: <target>". Separates "an existing
 * folder without a title" from "a mapped id whose folder no longer resolves"
 * (spec: a deleted mapping target must not look like a valid untitled
 * folder); the stored mapping itself is kept for re-edit, never silently
 * dropped on a transient catalog/folder read failure.
 */
@Composable
private fun categoryRowLabel(
    context: android.content.Context,
    launcher: LawnchairLauncher?,
    categoryDisplay: String,
    key: CategoryMappingKey,
    entries: Map<CategoryMappingKey, String>,
): String {
    val profileSuffix = if (key.userSerial != UserCache.getInstance(context)
            .getSerialNumberForUser(android.os.Process.myUserHandle())
    ) {
        " " + stringResource(AppDestinationPolicyTextKeys.otherProfile)
    } else {
        ""
    }
    val storedId = entries[key]?.toIntOrNull()
    val target = when {
        storedId == null -> stringResource(AppDestinationPolicyTextKeys.categoryEntryFolderUnset)

        else -> {
            val icon = folderIcon(launcher, storedId)
            val title = icon?.mInfo?.title?.toString()?.takeIf { it.isNotEmpty() }
            when {
                title != null -> title

                icon != null -> stringResource(R.string.homeedit_folder_default_label)

                // The mapped id no longer resolves to a live folder.
                else -> stringResource(R.string.destination_policy_summary_folder_missing)
            }
        }
    }
    return "$categoryDisplay$profileSuffix: $target"
}

/** One profile's S1-derived user-defined rows (u:<uuid> canonical values). */
private fun userDefinedRowsForProfile(
    overrideStore: app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource,
    context: android.content.Context,
    profileSerial: Long,
): List<CategoryMappingKey> = overrideStore
    .read(setOf(app.lawnchair.organizer.planning.ProfileId(profileSerial.toString())))
    .let { read ->
        (read as? app.lawnchair.organizer.rules.OverrideSnapshotReadResult.Ready)
            ?.snapshot?.assignments
            ?.mapNotNull { (_, identity) ->
                // User-defined identities carry the u:<uuid> canonical value
                // (CategoryIdentity.canonicalValue); built-in ones are covered
                // by the bundle rows.
                identity as? app.lawnchair.organizer.planning.CategoryIdentity.UserDefined
                    ?: return@mapNotNull null
                CategoryMappingKey(identity.canonicalValue, profileSerial)
            }
            ?.distinct()
            ?: emptyList()
    }
