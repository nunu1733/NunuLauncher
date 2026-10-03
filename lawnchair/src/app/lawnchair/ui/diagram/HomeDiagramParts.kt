/*
 * Issue #508: the minimal read-only drawing parts shared by the #449 edit
 * surface diagram and the organizer confirmation before/after diagram. Visual
 * geometry only — no selection rules, no session state, no icon resolution,
 * no semantics authority (each surface supplies its own semantics wrapper).
 */
package app.lawnchair.ui.diagram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Cell coordinates → diagram placement. Offsets include the 2.dp page-surface
 * padding and each item insets 2.dp on every side (span sizes shrink by
 * 4.dp), keeping a uniform gap between neighboring items. This is the exact
 * geometry the edit surface's original `placeInGrid` used — the shared part
 * must not change it.
 */
fun Modifier.diagramCellPlacement(
    cellX: Int,
    cellY: Int,
    cellSize: Dp,
    spanX: Int,
    spanY: Int,
): Modifier = this
    .offset(x = cellSize * cellX + 2.dp, y = cellSize * cellY + 2.dp)
    .width(cellSize * spanX - 4.dp)
    .height(cellSize * spanY - 4.dp)

/**
 * One page surface: the sized, rounded grid background the page's items and
 * reserved regions are placed into.
 */
@Composable
fun DiagramPageSurface(
    columns: Int,
    rows: Int,
    cellSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .width(cellSize * columns)
            .height(cellSize * rows)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(2.dp),
        content = content,
    )
}

/** Visual of a platform-reserved region (QSB etc.): never a layout item. */
@Composable
fun DiagramReservedSurface(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    )
}

/**
 * Visual content of one read-only diagram item: a folder icon with its member
 * count, a widget footprint, or an icon (with a neutral placeholder when the
 * icon is unavailable) plus an optional label. Both surfaces map their own
 * item models onto these parameters.
 */
@Composable
fun DiagramItemContent(
    isFolder: Boolean,
    memberCount: Int,
    isWidget: Boolean,
    icon: Painter?,
    label: String?,
    cellSize: Dp,
    folderIcon: Painter,
    widgetLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val iconSize = cellSize / 2
        when {
            isFolder -> {
                Image(
                    painter = folderIcon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
                Text(
                    text = memberCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }

            isWidget -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = widgetLabel,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                if (icon != null) {
                    Image(
                        painter = icon,
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
                label?.let {
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
