package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search

/**
 * 设置页分组组件：浅灰背景 + 白色分组圆角卡片 + 组内缩进分隔线 + 黑色线条图标。
 *
 * 用法：
 * ```
 * SettingsGroupHeader("分组标题")
 * SettingsGroup {
 *     SettingsRow(icon, title, onClick = { ... })
 *     SettingsDivider()
 *     SettingsRow(icon, title, trailing = { AppSwitch(...) })
 * }
 * ```
 */

/** 当前是否浅色模式（据此切换浅灰/主题深色配色）。 */
@Composable
internal fun settingsLightMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() > 0.5f

/**
 * 设置页图标取色表：miuix 设置页靠“白卡 + 彩色小图标”建立层次，
 * 同一图标在所有页面用同一个色，不随主题预设漂移。
 */
private val SettingsIconTints: Map<String, Color> = mapOf(
    "Cloud" to Color(0xFF3482FF),
    "Server" to Color(0xFF00A3A3),
    "HardDrive" to Color(0xFF00A3A3),
    "Cpu" to Color(0xFF3482FF),
    "Sliders" to Color(0xFF3482FF),
    "Settings" to Color(0xFF3482FF),
    "Layers" to Color(0xFF3482FF),
    "Book" to Color(0xFFFF8A00),
    "BookOpen" to Color(0xFFFF8A00),
    "FileText" to Color(0xFF5B8DEF),
    "MessageSquare" to Color(0xFF00C2A8),
    "Users" to Color(0xFFFF8A00),
    "Shield" to Color(0xFF00B34A),
    "Lock" to Color(0xFF00B34A),
    "Globe" to Color(0xFF3482FF),
    "Terminal" to Color(0xFF00B34A),
    "Zap" to Color(0xFFFFB020),
    "Moon" to Color(0xFF6B5BD6),
    "Image" to Color(0xFFFF8A00),
    "Download" to Color(0xFF3482FF),
    "BarChart2" to Color(0xFF3482FF),
    "PieChart" to Color(0xFF3482FF),
    "Edit2" to Color(0xFF3482FF),
    "Trash2" to Color(0xFFE54545),
    "Info" to Color(0xFF3482FF),
    "RefreshCw" to Color(0xFF8A8A8E),
    "RotateCcw" to Color(0xFF8A8A8E),
    "Save" to Color(0xFF3482FF),
    "Box" to Color(0xFF8B5CF6),
    "GitBranch" to Color(0xFFFF8A00)
)

/** 按图标名取色，未登记的图标统一用主色蓝。 */
internal fun settingsIconTint(icon: ImageVector): Color =
    SettingsIconTints[icon.name.substringAfterLast('.')] ?: Brand.Blue

/** 可折叠分组标题：点击展开/收起，右侧 chevron 指示状态。与工具授权页的分组一致。 */
@Composable
internal fun CollapsibleGroupHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            size = 16.dp,
            tint = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** 设置页背景：统一使用语义定义的 pageBackground。 */
@Composable
internal fun settingsPageBackground(): Color =
    MaterialTheme.semanticColors.pageBackground

/** 分组小标题：卡片上方灰色小字，左对齐。 */
@Composable
internal fun SettingsGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 0.dp, top = 20.dp, bottom = 8.dp)
    )
}

/** 白色/深色分组圆角卡片容器：内部按行排布，行间用 [SettingsDivider] 分隔。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

/**
 * [SettingsGroup] 的 LazyColumn 版：逐项绘制卡片背景，首项圆上角、末项圆下角，项间自动补分隔线。
 *
 * 技能 / 子代理这类列表条目数不可控（项目里可能有上百个），必须走 LazyColumn 逐项虚拟化，
 * 只组合可见行；卡片外观靠这里的首尾圆角拼出来，视觉与 [SettingsGroup] 一致。
 */
@Composable
internal fun LazyGroupItem(
    index: Int,
    lastIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 22.dp else 0.dp,
        topEnd = if (index == 0) 22.dp else 0.dp,
        bottomStart = if (index == lastIndex) 22.dp else 0.dp,
        bottomEnd = if (index == lastIndex) 22.dp else 0.dp
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.semanticColors.cardSurface)
    ) {
        content()
        if (index != lastIndex) SettingsDivider()
    }
}

/**
 * 分组内单行：左侧线条图标 + 标题，右侧可选尾随组件（右箭头/开关/值）。
 *
 * @param icon 左侧图标，null 则标题与无图标行对齐。
 * @param iconTint 图标颜色；不传时按图标名从 miuix 取色表取色。
 * @param trailing 右侧尾随内容（如 [com.aicode.core.ui.AppSwitch]、chevron）。
 * @param onClick null 表示无点击行为（如开关行）；非 null 时行尾自动显示右箭头。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconTint: Color? = null
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null && enabled) it.clickable { onClick() } else it }
        .padding(horizontal = Spacing.lg, vertical = 14.dp)
    Row(
        modifier = baseModifier.alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: settingsIconTint(icon),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing?.invoke(this)
        if (onClick != null && enabled) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** 分组内行间分隔线：从文字起始处开始，末行不显示。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 46.dp, end = Spacing.lg),
        thickness = 0.5.dp,
        color = MaterialTheme.semanticColors.subtleBorder
    )
}

/** miuix 风搜索框：浅灰填充、无描边大圆角，与设置页分组风格一致。
 *  [trailing] 为可选尾部控件（如清除按钮）。 */
@Composable
internal fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val isLight = settingsLightMode()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isLight) MaterialTheme.semanticColors.mutedSurface else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            // 行高固定：尾部控件（清除按钮的 IconButton 自带 48dp 最小触摸目标）比正文行高更高，
            // 若由内容撑高，有无尾部控件时高度不一致，输入后整条搜索框会突然变高。
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing?.invoke()
        }
    }
}
