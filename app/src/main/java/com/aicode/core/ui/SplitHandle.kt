package com.aicode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R

/** 触摸热区宽度：视觉上只有一条细线，但要给手指留够可抓取的宽度。 */
private val HandleTouchWidth = 14.dp

/**
 * 左右分栏之间的拖拽分割条浮层。[onDragDelta] 收到的是水平位移像素，由调用方换算成分栏比例。
 *
 * 排版中的实际分割线由 `Row` 内的 `VerticalDivider`（1.dp）承担，保证左右面板无缝贴合、无留白；
 * 本组件作为 [HandleTouchWidth] 宽度的触控浮层，以 [anchorX]（左栏右边缘，即分割线起点）为中心骑跨在分割线上，
 * 既不挤占 `Row` 的排版宽度，又保留完整的触摸与鼠标悬停热区。
 */
@Composable
fun VerticalSplitHandle(
    anchorX: Int,
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val description = stringResource(R.string.workbench_split_handle)
    Box(
        modifier = modifier
            .offset {
                val touchWidthPx = HandleTouchWidth.roundToPx()
                val dividerWidthPx = 1.dp.roundToPx()
                IntOffset(x = anchorX - (touchWidthPx - dividerWidthPx) / 2, y = 0)
            }
            .fillMaxHeight()
            .width(HandleTouchWidth)
            .semantics { contentDescription = description }
            .draggable(
                state = rememberDraggableState { onDragDelta(it) },
                orientation = Orientation.Horizontal,
                interactionSource = interactionSource
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        if (dragging) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
