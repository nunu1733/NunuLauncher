/*
 * Issue #449: the visual edit surface screen (ADR-0014 case B). Compose UI
 * over the pure homeedit types only — geometry comes from the working
 * projection, the selection is a plain id list in tap order, and the action
 * bar issues PendingSessionActions. Icon resolution (TargetKey + profile →
 * IconCache, custom icon bytes first, placeholder when unresolvable) lives in
 * the activity, which hands ready bitmaps to this screen.
 */
package app.lawnchair.homeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lawnchair.homeedit.EditSurfaceDiagram
import app.lawnchair.homeedit.EditSurfaceDuplicateGroup
import app.lawnchair.homeedit.EditSurfaceDuplicateGroups
import app.lawnchair.homeedit.EditSurfaceItem
import app.lawnchair.homeedit.EditSurfaceSessionPlanner
import app.lawnchair.homeedit.HomeEditContainers
import app.lawnchair.homeedit.HomeEditItemTypes
import app.lawnchair.homeedit.SelectionEligibility
import app.lawnchair.homeedit.isEditSurfaceNewFolderKey
import app.lawnchair.ui.diagram.DiagramItemContent
import app.lawnchair.ui.diagram.DiagramPageSurface
import app.lawnchair.ui.diagram.DiagramReservedSurface
import app.lawnchair.ui.diagram.diagramCellPlacement
import com.android.launcher3.R

/** ダイアログの種別（ページ移動/フォルダ追加の選択dialog。spec決定済み）。 */
sealed interface EditSurfaceDialog {
    data object MoveToPage : EditSurfaceDialog
    data object AddToFolder : EditSurfaceDialog
}

/** 1フォルダ分のdialog選択肢（同じprofileの既存フォルダに限る）。 */
data class EditSurfaceFolderOption(val folderId: Int, val label: String?, val memberCount: Int)

@Composable
fun EditSurfaceScreen(
    diagram: EditSurfaceDiagram,
    selection: List<Int>,
    sessionChangeCount: Int,
    confirmGate: EditSurfaceSessionPlanner.ConfirmGate,
    icons: Map<Int, ImageBitmap?>,
    reasonText: String?,
    busy: Boolean,
    duplicateGroups: List<EditSurfaceDuplicateGroup>,
    duplicatesOpen: Boolean,
    touchedIds: Set<Int>,
    profileLabels: Map<Long, String>,
    onToggleSelection: (Int) -> Unit,
    onCreateFolder: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onPickPage: (Int) -> Unit,
    onPickFolder: (Int) -> Unit,
    onOpenDuplicates: () -> Unit,
    onDismissDuplicates: () -> Unit,
    onToggleDuplicateMember: (Int) -> Unit,
    onRemoveFromDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<EditSurfaceDialog?>(null) }
    val selectionSerial = diagram.itemById[selection.firstOrNull()]?.userSerial
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.edit_surface_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = !busy) {
                Text(stringResource(R.string.edit_surface_reset))
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.edit_surface_cancel))
            }
        }
        Text(
            text = stringResource(R.string.edit_surface_snapshot_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(R.string.edit_surface_selection_count, selection.size),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (duplicateGroups.isNotEmpty()) {
            TextButton(
                onClick = onOpenDuplicates,
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.edit_surface_duplicate_summary, duplicateGroups.size))
            }
        }
        reasonText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        DiagramGrid(
            diagram = diagram,
            selection = selection,
            icons = icons,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onToggleSelection = onToggleSelection,
        )
        ActionBar(
            hasSelection = selection.isNotEmpty(),
            sessionChangeCount = sessionChangeCount,
            confirmGate = confirmGate,
            busy = busy,
            onMoveToPage = { dialog = EditSurfaceDialog.MoveToPage },
            onAddToFolder = { dialog = EditSurfaceDialog.AddToFolder },
            onCreateFolder = onCreateFolder,
            onRemove = onRemove,
            onConfirm = onConfirm,
        )
    }
    when (dialog) {
        EditSurfaceDialog.MoveToPage -> PagePickerDialog(
            pages = diagram.pages,
            onDismiss = { dialog = null },
            onPick = { page ->
                dialog = null
                onPickPage(page)
            },
        )

        EditSurfaceDialog.AddToFolder -> FolderPickerDialog(
            folders = diagram.items
                .filter {
                    it.itemType == HomeEditItemTypes.FOLDER && !it.isSessionCreated &&
                        it.userSerial == selectionSerial
                }
                .map { EditSurfaceFolderOption(it.id, it.label, diagram.folderMemberCounts[it.id] ?: 0) },
            onDismiss = { dialog = null },
            onPick = { folderId ->
                dialog = null
                onPickFolder(folderId)
            },
        )

        null -> Unit
    }
    if (duplicatesOpen) {
        DuplicatePickerDialog(
            diagram = diagram,
            groups = duplicateGroups,
            selection = selection,
            touchedIds = touchedIds,
            profileLabels = profileLabels,
            reasonText = reasonText,
            busy = busy,
            onDismiss = onDismissDuplicates,
            onToggleMember = onToggleDuplicateMember,
            onRemove = onRemoveFromDuplicates,
        )
    }
}

/**
 * 重複確認面（spec 507）。各グループの各メンバー行に名前・位置・所属フォルダ・
 * profile区別・選択状態・選択不可の理由を出す。メンバー行のtapは既存の選択toggle
 * （guardはActivity側の純粋関数経由）へ流し、面内の「ホームから外す」は既存の
 * RemoveFromHomeアクションの共通入口（dispatch直前のguardもActivity側）。
 */
@Composable
private fun DuplicatePickerDialog(
    diagram: EditSurfaceDiagram,
    groups: List<EditSurfaceDuplicateGroup>,
    selection: List<Int>,
    touchedIds: Set<Int>,
    profileLabels: Map<Long, String>,
    reasonText: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggleMember: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val defaultLabelText = stringResource(R.string.homeedit_folder_default_label)
    val selectedText = stringResource(R.string.edit_surface_a11y_selected)
    val lockedText = stringResource(R.string.organizer_lock_state_locked)
    val lockUnknownText = stringResource(R.string.organizer_lock_state_unknown)
    val handledText = stringResource(R.string.edit_surface_duplicate_handled)
    val noSelectableText = stringResource(R.string.edit_surface_duplicate_no_selectable)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_surface_duplicate_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                reasonText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                groups.forEach { group ->
                    val groupLabel = group.members.first().label ?: defaultLabelText
                    Text(
                        text = groupLabel,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (group.selectableMembers.isEmpty()) {
                        Text(
                            text = noSelectableText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    group.members.forEach { member ->
                        DuplicateMemberRow(
                            diagram = diagram,
                            member = member,
                            selected = member.id in selection,
                            selectable = EditSurfaceDuplicateGroups.rowSelectable(member, touchedIds),
                            handled = member.id in touchedIds,
                            profileLabel = profileLabels[member.userSerial],
                            lockedText = lockedText,
                            lockUnknownText = lockUnknownText,
                            handledText = handledText,
                            defaultLabelText = defaultLabelText,
                            selectedText = selectedText,
                            onToggleMember = onToggleMember,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove, enabled = selection.isNotEmpty() && !busy) {
                Text(stringResource(R.string.edit_surface_action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_surface_cancel)) }
        },
    )
}

@Composable
private fun DuplicateMemberRow(
    diagram: EditSurfaceDiagram,
    member: EditSurfaceItem,
    selected: Boolean,
    selectable: Boolean,
    handled: Boolean,
    profileLabel: String?,
    lockedText: String,
    lockUnknownText: String,
    handledText: String,
    defaultLabelText: String,
    selectedText: String,
    onToggleMember: (Int) -> Unit,
) {
    val position = duplicateMemberPositionText(diagram, member, defaultLabelText)
    val reason = when {
        member.eligibility == SelectionEligibility.LOCKED -> lockedText
        member.eligibility == SelectionEligibility.LOCK_UNKNOWN -> lockUnknownText
        handled -> handledText
        else -> null
    }
    val description = editSurfaceDuplicateRowDescription(
        label = member.label ?: defaultLabelText,
        position = position,
        profile = profileLabel,
        reason = reason,
        selected = selected,
        selectedText = selectedText,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier.clickable { onToggleMember(member.id) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(checked = selected, onCheckedChange = null)
        } else {
            Spacer(modifier = Modifier.width(36.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.label ?: defaultLabelText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (position.isNotBlank()) {
                Text(
                    text = position,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            profileLabel?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * メンバー行の位置表示（ページ/行/列、所属フォルダ、Dock）。純投影
 * （[EditSurfaceDuplicateGroups]）の並びと同じ視覚順の根拠に基づく表示のみ。
 */
@Composable
private fun duplicateMemberPositionText(
    diagram: EditSurfaceDiagram,
    member: EditSurfaceItem,
    defaultFolderLabel: String,
): String = when {
    member.container == HomeEditContainers.DESKTOP -> {
        val pageIndex = diagram.pages.indexOf(member.screenId)
        stringResource(
            R.string.edit_surface_duplicate_workspace_position,
            pageIndex + 1,
            member.cellY,
            member.cellX,
        )
    }

    member.container > 0 || isEditSurfaceNewFolderKey(member.container) -> {
        val folderLabel = diagram.itemById[member.container]?.label ?: defaultFolderLabel
        stringResource(R.string.edit_surface_duplicate_in_folder, folderLabel)
    }

    member.container == HomeEditContainers.HOTSEAT ->
        stringResource(R.string.edit_surface_duplicate_on_dock)

    // 未知のcontainerコード（上流のUnsupportedContainer等）。行はラベルのみで示す。
    else -> ""
}

/**
 * 確認面メンバー行のTalkBack読み上げ文言の純構築（AC-8のsemantics供給のoracle対象）。
 * 名前・位置・profile区別・選択不可の理由・選択状態を1つの純関数が決定する
 * （editSurfaceItemDescriptionと同じ単一権威の構成）。
 */
internal fun editSurfaceDuplicateRowDescription(
    label: String,
    position: String?,
    profile: String?,
    reason: String?,
    selected: Boolean,
    selectedText: String,
): String = listOfNotNull(
    label,
    position?.takeIf { it.isNotBlank() },
    profile?.takeIf { it.isNotBlank() },
    reason?.takeIf { it.isNotBlank() },
    selectedText.takeIf { selected },
).joinToString(", ")

@Composable
private fun ActionBar(
    hasSelection: Boolean,
    sessionChangeCount: Int,
    confirmGate: EditSurfaceSessionPlanner.ConfirmGate,
    busy: Boolean,
    onMoveToPage: () -> Unit,
    onAddToFolder: () -> Unit,
    onCreateFolder: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onMoveToPage, enabled = hasSelection && !busy) {
                Text(stringResource(R.string.edit_surface_action_move_to_page), maxLines = 1)
            }
            OutlinedButton(onClick = onAddToFolder, enabled = hasSelection && !busy) {
                Text(stringResource(R.string.edit_surface_action_add_to_folder), maxLines = 1)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onCreateFolder, enabled = hasSelection && !busy) {
                Text(stringResource(R.string.edit_surface_action_create_folder), maxLines = 1)
            }
            OutlinedButton(onClick = onRemove, enabled = hasSelection && !busy) {
                Text(stringResource(R.string.edit_surface_action_remove), maxLines = 1)
            }
        }
        Button(
            onClick = onConfirm,
            enabled = !busy && confirmGate == EditSurfaceSessionPlanner.ConfirmGate.Open,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = when (confirmGate) {
                    EditSurfaceSessionPlanner.ConfirmGate.Open ->
                        // 変更件数を確定ラベルに添える（選択数とは別。セッション計画の可視化）。
                        if (sessionChangeCount > 0) {
                            stringResource(R.string.edit_surface_confirm) + " ($sessionChangeCount)"
                        } else {
                            stringResource(R.string.edit_surface_error_empty_session)
                        }

                    EditSurfaceSessionPlanner.ConfirmGate.EmptySession ->
                        stringResource(R.string.edit_surface_error_empty_session)

                    EditSurfaceSessionPlanner.ConfirmGate.LockStateUnknown ->
                        stringResource(R.string.edit_surface_error_lock_unknown)
                },
            )
        }
    }
}

@Composable
private fun DiagramGrid(
    diagram: EditSurfaceDiagram,
    selection: List<Int>,
    icons: Map<Int, ImageBitmap?>,
    modifier: Modifier = Modifier,
    onToggleSelection: (Int) -> Unit,
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val cellSize = ((screenWidth - 32.dp) / diagram.columnCount).coerceAtLeast(28.dp)
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        diagram.pages.forEachIndexed { pageIndex, screenId ->
            item(key = "page-header-$screenId") {
                Text(
                    text = stringResource(R.string.homeedit_page_label, pageIndex + 1),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "page-$screenId") {
                PageGrid(
                    diagram = diagram,
                    screenId = screenId,
                    selection = selection,
                    icons = icons,
                    cellSize = cellSize,
                    onToggleSelection = onToggleSelection,
                )
            }
        }
        item(key = "dock-header") {
            Text(
                text = stringResource(R.string.edit_surface_a11y_dock),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        item(key = "dock") {
            DockRow(diagram = diagram, icons = icons, cellSize = cellSize)
        }
        item(key = "bottom-space") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun PageGrid(
    diagram: EditSurfaceDiagram,
    screenId: Int,
    selection: List<Int>,
    icons: Map<Int, ImageBitmap?>,
    cellSize: Dp,
    onToggleSelection: (Int) -> Unit,
) {
    // Issue #508: the page surface and cell placement are the shared read-only
    // diagram parts; the edit surface keeps selection semantics per item.
    DiagramPageSurface(columns = diagram.columnCount, rows = diagram.rowCount, cellSize = cellSize) {
        val reservedDescription = stringResource(R.string.edit_surface_a11y_reserved)
        diagram.reservedRegions
            .filter { it.screenId == screenId }
            .forEach { reserved ->
                // Issue #508: the reserved-region visual is the shared read-only
                // diagram part; the edit surface keeps its own semantics.
                DiagramReservedSurface(
                    modifier = Modifier
                        .placeInGrid(reserved.cellX, reserved.cellY, cellSize, reserved.spanX, reserved.spanY)
                        .semantics {
                            // Read in the composable scope; a fixed description
                            // here would break localization.
                            contentDescription = reservedDescription
                        },
                )
            }
        val pageIndex = diagram.pages.indexOf(screenId)
        diagram.items
            .filter { it.isOnWorkspace && it.screenId == screenId }
            .forEach { item ->
                DiagramItemView(
                    item = item,
                    selected = item.id in selection,
                    memberCount = diagram.folderMemberCounts[item.id] ?: 0,
                    icon = icons[item.id],
                    cellSize = cellSize,
                    pageLabel = pageIndex.takeIf { it >= 0 }?.let { index ->
                        stringResource(R.string.homeedit_page_label, index + 1)
                    },
                    cellLabel = "(${item.cellX}, ${item.cellY})",
                    modifier = Modifier.placeInGrid(
                        item.cellX,
                        item.cellY,
                        cellSize,
                        item.spanX,
                        item.spanY,
                    ),
                    onToggleSelection = onToggleSelection,
                )
            }
    }
}

/** セル座標 → 図上の配置（1セル分の枠差分を含む）。共有部品と同一幾何。 */
private fun Modifier.placeInGrid(
    cellX: Int,
    cellY: Int,
    cellSize: Dp,
    spanX: Int,
    spanY: Int,
): Modifier = diagramCellPlacement(cellX, cellY, cellSize, spanX, spanY)

@Composable
private fun DockRow(
    diagram: EditSurfaceDiagram,
    icons: Map<Int, ImageBitmap?>,
    cellSize: Dp,
) {
    val dockItems = diagram.items
        .filter { it.container == HomeEditContainers.HOTSEAT }
        .sortedBy { it.rank }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        dockItems.forEach { item ->
            Box(
                modifier = Modifier
                    .width(cellSize)
                    .height(cellSize)
                    .padding(2.dp),
            ) {
                DiagramItemView(
                    item = item,
                    selected = false,
                    memberCount = diagram.folderMemberCounts[item.id] ?: 0,
                    icon = icons[item.id],
                    cellSize = cellSize,
                    pageLabel = null,
                    cellLabel = null,
                    modifier = Modifier.fillMaxSize(),
                    onToggleSelection = null,
                )
            }
        }
    }
}

@Composable
private fun DiagramItemView(
    item: EditSurfaceItem,
    selected: Boolean,
    memberCount: Int,
    icon: ImageBitmap?,
    cellSize: Dp,
    // TalkBackの読み上げ要素（spec AC-15: title、位置、選択状態、選択不可の理由）。
    pageLabel: String?,
    cellLabel: String?,
    modifier: Modifier = Modifier,
    // dock上のアイテムは選択対象外（spec）。nullで非選択の描画になる。
    onToggleSelection: ((Int) -> Unit)?,
) {
    val selectedText = stringResource(R.string.edit_surface_a11y_selected)
    val lockedText = stringResource(R.string.edit_surface_a11y_locked)
    val lockUnknownText = stringResource(R.string.edit_surface_a11y_lock_unknown)
    val notSelectableText = stringResource(R.string.edit_surface_a11y_not_selectable)
    val folderText = stringResource(R.string.edit_surface_a11y_folder, memberCount)
    val defaultLabelText = stringResource(R.string.homeedit_folder_default_label)
    val semanticsDescriptor = editSurfaceItemSemantics(
        item,
        memberCount,
        pageLabel,
        cellLabel,
        selected,
        selectedText,
        lockedText,
        lockUnknownText,
        notSelectableText,
        folderText,
        defaultLabelText,
    )
    val borderModifier = if (selected) {
        Modifier.border(
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(6.dp),
        )
    } else {
        Modifier
    }
    // 選択不可の対象外（widget/フォルダ自身等）はtapできない。ロック中・ロック状態
    // 不明はtap可能で、Activityが理由を表示する（spec: tap時にその旨を示す）。
    val tappable = onToggleSelection != null && item.eligibility != SelectionEligibility.UNSUPPORTED
    val clickableModifier = if (tappable && onToggleSelection != null) {
        Modifier.clickable { onToggleSelection(item.id) }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(borderModifier)
            .then(clickableModifier)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(1.dp)
            .semantics {
                // 純descriptor（editSurfaceItemSemantics）がsemantics供給の単一の権威。
                // contentDescription / selected / stateDescription の全てがここから流れる。
                this.contentDescription = semanticsDescriptor.description
                if (semanticsDescriptor.selectable) {
                    this.selected = semanticsDescriptor.selected
                }
                this.stateDescription = semanticsDescriptor.stateDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Issue #508: the visual internals are the shared read-only diagram
        // part; this wrapper keeps the edit surface's selection semantics.
        DiagramItemContent(
            isFolder = item.itemType == HomeEditItemTypes.FOLDER,
            memberCount = memberCount,
            isWidget = item.itemType == HomeEditItemTypes.APP_WIDGET ||
                item.itemType == HomeEditItemTypes.CUSTOM_APP_WIDGET,
            icon = icon?.let { BitmapPainter(it) },
            label = item.label,
            cellSize = cellSize,
            folderIcon = painterResource(R.drawable.ic_folder),
            widgetLabel = stringResource(R.string.edit_surface_widget_label),
        )
    }
}

/**
 * 図アイテムのsemantics供給の純descriptor（AC-15）。contentDescription /
 * selected / stateDescription の全ての値を1つの純関数が決定し、
 * [DiagramItemView] のsemantics blockはこの値を書き込むのみ。UI接続の
 * 回帰oracle（[EditSurfaceA11yDescriptionTest]）はこの型を検証する。
 */
data class EditSurfaceItemSemantics(
    val description: String,
    /** 選択対象か（UNSUPPORTED以外）。falseならselected stateを設定しない。 */
    val selectable: Boolean,
    val selected: Boolean,
    val stateDescription: String,
)

/** [EditSurfaceItemSemantics] の純構築。全semantics値の単一の権威。 */
internal fun editSurfaceItemSemantics(
    item: EditSurfaceItem,
    memberCount: Int,
    pageLabel: String?,
    cellLabel: String?,
    selected: Boolean,
    selectedText: String,
    lockedText: String,
    lockUnknownText: String,
    notSelectableText: String,
    folderText: String,
    defaultLabelText: String,
): EditSurfaceItemSemantics {
    val description = editSurfaceItemDescription(
        label = item.label ?: defaultLabelText,
        eligibility = item.eligibility,
        isFolder = item.itemType == HomeEditItemTypes.FOLDER,
        memberCount = memberCount,
        selected = selected,
        selectedText = selectedText,
        lockedText = lockedText,
        lockUnknownText = lockUnknownText,
        notSelectableText = notSelectableText,
        folderText = folderText,
        pageLabel = pageLabel,
        cellLabel = cellLabel,
    )
    return EditSurfaceItemSemantics(
        description = description,
        selectable = item.eligibility != SelectionEligibility.UNSUPPORTED,
        selected = selected,
        stateDescription = if (item.eligibility != SelectionEligibility.UNSUPPORTED && selected) {
            selectedText
        } else {
            ""
        },
    )
}

/**
 * 図アイテムのTalkBack読み上げ文言の純構築（AC-15のsemantics供給のoracle対象）。
 * title、種別/選択不可の理由、位置（ページ+セル）、選択状態の4要素を常に含む。
 * 純関数としてJVM testで各状態のsemantics供給を固定する。
 */
internal fun editSurfaceItemDescription(
    label: String,
    eligibility: SelectionEligibility,
    isFolder: Boolean,
    memberCount: Int,
    selected: Boolean,
    selectedText: String,
    lockedText: String,
    lockUnknownText: String,
    notSelectableText: String,
    folderText: String,
    pageLabel: String?,
    cellLabel: String?,
): String {
    val kind = when (eligibility) {
        SelectionEligibility.SELECTABLE -> if (isFolder) folderText else ""
        SelectionEligibility.LOCKED -> lockedText
        SelectionEligibility.LOCK_UNKNOWN -> lockUnknownText
        SelectionEligibility.UNSUPPORTED -> notSelectableText
    }
    val selectionState = if (eligibility != SelectionEligibility.UNSUPPORTED && selected) selectedText else ""
    val position = listOfNotNull(pageLabel, cellLabel?.takeIf { it.isNotBlank() }).joinToString(" ")
    return listOf(label, kind, position, selectionState).filter { it.isNotBlank() }.joinToString(", ")
}

@Composable
private fun PagePickerDialog(
    pages: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.homeedit_dialog_title_move_to_page)) },
        text = {
            Column {
                pages.forEachIndexed { index, screenId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(screenId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = { onPick(screenId) })
                        Text(stringResource(R.string.homeedit_page_label, index + 1))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_surface_cancel)) }
        },
    )
}

@Composable
private fun FolderPickerDialog(
    folders: List<EditSurfaceFolderOption>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.homeedit_dialog_title_add_to_folder)) },
        text = {
            if (folders.isEmpty()) {
                Text(stringResource(R.string.edit_surface_no_folder_available))
            } else {
                Column {
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(folder.folderId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = false, onClick = { onPick(folder.folderId) })
                            Text(
                                text = stringResource(
                                    R.string.homeedit_folder_entry,
                                    folder.label ?: stringResource(R.string.homeedit_folder_default_label),
                                    folder.memberCount,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_surface_cancel)) }
        },
    )
}
