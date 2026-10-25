package com.aicode.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.X

/**
 * 列表多选状态。key 由各页面自定义（提供商 id / `作用域:名称` / 提示词编号 / 镜像 id），
 * 这样一套组件能服务所有「可批量操作的字符串键列表」，页面侧只需提供 key 映射。
 *
 * 约定：
 * - key 必须在列表内唯一，且数据刷新后保持稳定（不要用下标）。
 * - 数据刷新后调用 [retainOnly] 剔除已消失的选中项，避免批量操作打到已删除的条目。
 * - 退出多选（[exit]）会清空选中集合，返回键与顶栏开关共用它。
 */
class ListSelectionState {

    /** 是否处于多选模式。false 时列表行为与改造前完全一致（左滑删除、点击进详情）。 */
    var isActive by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<String>()

    val selectedCount: Int get() = _selected.size

    /** 当前选中 key 的快照：批量回调触发时取一次，避免边遍历边被改动。 */
    val selectedKeys: List<String> get() = _selected.toList()

    fun isSelected(key: String): Boolean = key in _selected

    fun toggle(key: String) {
        if (!_selected.remove(key)) _selected.add(key)
    }

    fun enter() {
        isActive = true
    }

    fun exit() {
        isActive = false
        _selected.clear()
    }

    fun clear() {
        _selected.clear()
    }

    /** 全选 / 取消全选：整体替换选中集合。 */
    fun replaceAll(keys: Collection<String>) {
        _selected.clear()
        _selected.addAll(keys)
    }

    /** 数据刷新后剔除失效 key（被删掉、被筛掉的条目）。 */
    fun retainOnly(keys: Set<String>) {
        _selected.retainAll(keys)
    }
}

/**
 * 创建一份多选状态。设置页各列表的多选状态由 SettingsScreen 统一持有（顶栏按钮在那边），
 * 离开列表页进详情 / 编辑时显式退出，因此这里用 remember 而不做跨配置持久化。
 */
@Composable
fun rememberListSelectionState(): ListSelectionState = remember { ListSelectionState() }

/**
 * 顶部栏右上角的多选开关：与模型页同一套图标与配色（`FeatherIcons.CheckSquare`）。
 * 列表为空时调用方不显示它；[enabled] = false 时置灰不可点（如提示词全部为只读来源，
 * 进多选也无项可勾）。
 */
@Composable
fun SelectionToggleAction(selection: ListSelectionState, enabled: Boolean = true) {
    IconButton(
        onClick = { if (selection.isActive) selection.exit() else selection.enter() },
        enabled = enabled
    ) {
        Icon(
            FeatherIcons.CheckSquare,
            contentDescription = stringResource(R.string.common_select_mode),
            tint = if (selection.isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 列表顶部操作条：左侧「已选 N 项」，中间「全选 / 取消全选」，右侧页面自己的批量动作，最右退出。
 *
 * 与模型页（ProviderEditorScreen 的模型 Tab）视觉一致：13sp 主色计数、labelMedium 文本按钮、36dp 图标按钮。
 * [actions] 里的按钮建议用 [SelectionActionButton] 保持同一套尺寸与配色。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // 操作条只在多选模式出现：顺手把返回键接管成「退出多选」，与模型页行为一致。
    BackHandler { onExit() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.common_selected_count, selectedCount),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onToggleSelectAll,
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text(
                text = stringResource(
                    if (allSelected) R.string.common_deselect_all else R.string.common_select_all
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        actions()
        IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.common_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 操作条上的紧凑动作按钮。窄屏要在一行里放下「全选 + 两三个动作 + 退出」，
 * 所以内边距压到 [Spacing.sm]、文字用 labelMedium、图标 16dp。
 */
@Composable
fun SelectionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = tint),
        modifier = modifier.height(36.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * 操作条上的图标按钮：动作多于两个时（如 MCP 的启用/停用/重连/删除），
 * 全部用文字会在窄屏上挤掉计数与全选，所以次要动作用图标并配 contentDescription。
 */
@Composable
fun SelectionActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 批量删除二次确认弹窗。[inProgress] 为 true 时确认按钮转圈并锁定两个按钮，
 * 避免删除中途被关掉导致「看不见到底成没成」。
 */
@Composable
fun BatchDeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    inProgress: Boolean = false,
    progressText: String? = null
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                if (progressText != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !inProgress) {
                if (inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
