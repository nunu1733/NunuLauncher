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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lawnchair.homeedit.EditSurfaceDiagram
import app.lawnchair.homeedit.EditSurfaceItem
import app.lawnchair.homeedit.EditSurfaceSessionPlanner
import app.lawnchair.homeedit.HomeEditContainers
import app.lawnchair.homeedit.HomeEditItemTypes
import app.lawnchair.homeedit.SelectionEligibility
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
    onToggleSelection: (Int) -> Unit,
    onCreateFolder: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onPickPage: (Int) -> Unit,
    onPickFolder: (Int) -> Unit,
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
}

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
    Box(
        modifier = Modifier
            .width(cellSize * diagram.columnCount)
            .height(cellSize * diagram.rowCount)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(2.dp),
    ) {
        val reservedDescription = stringResource(R.string.edit_surface_a11y_reserved)
        diagram.reservedRegions
            .filter { it.screenId == screenId }
            .forEach { reserved ->
                Box(
                    modifier = Modifier
                        .placeInGrid(reserved.cellX, reserved.cellY, cellSize, reserved.spanX, reserved.spanY)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
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

/** セル座標 → 図上の配置（1セル分の枠差分を含む）。 */
private fun Modifier.placeInGrid(
    cellX: Int,
    cellY: Int,
    cellSize: Dp,
    spanX: Int,
    spanY: Int,
): Modifier = this
    .offset(x = cellSize * cellX + 2.dp, y = cellSize * cellY + 2.dp)
    .width(cellSize * spanX - 4.dp)
    .height(cellSize * spanY - 4.dp)

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
    val description = itemContentDescription(item, memberCount, pageLabel, cellLabel, selected)
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
    val selectedText = stringResource(R.string.edit_surface_a11y_selected)
    Column(
        modifier = modifier
            .then(borderModifier)
            .then(clickableModifier)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(1.dp)
            .semantics {
                contentDescription = description
                // 選択状態はCompose標準のselected stateで支援技術に伝わる。
                if (item.eligibility != SelectionEligibility.UNSUPPORTED) {
                    this.selected = selected
                }
                stateDescription = if (selected) selectedText else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val iconSize = cellSize / 2
        when {
            item.itemType == HomeEditItemTypes.FOLDER -> {
                Image(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
                Text(
                    text = memberCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }

            item.itemType == HomeEditItemTypes.APP_WIDGET ||
                item.itemType == HomeEditItemTypes.CUSTOM_APP_WIDGET -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.edit_surface_widget_label),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                val painter: Painter? = icon?.let { BitmapPainter(it) }
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                    )
                }
                item.label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun itemContentDescription(
    item: EditSurfaceItem,
    memberCount: Int,
    pageLabel: String?,
    cellLabel: String?,
    selected: Boolean,
): String = editSurfaceItemDescription(
    label = item.label ?: stringResource(R.string.homeedit_folder_default_label),
    eligibility = item.eligibility,
    isFolder = item.itemType == HomeEditItemTypes.FOLDER,
    memberCount = memberCount,
    selected = selected,
    selectedText = stringResource(R.string.edit_surface_a11y_selected),
    lockedText = stringResource(R.string.edit_surface_a11y_locked),
    lockUnknownText = stringResource(R.string.edit_surface_a11y_lock_unknown),
    notSelectableText = stringResource(R.string.edit_surface_a11y_not_selectable),
    folderText = stringResource(R.string.edit_surface_a11y_folder, memberCount),
    pageLabel = pageLabel,
    cellLabel = cellLabel,
)

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
