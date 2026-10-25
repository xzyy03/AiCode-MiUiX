package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.agent.domain.mcp.McpScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText

/**
 * 「添加 MCP 服务器」底部弹层：顶部选作用域（全局 / 当前项目），下方两种添加方式——
 * 手动填写、粘贴标准 mcpServers JSON 批量导入。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun McpAddSheet(
    scope: McpScope,
    onScopeChange: (McpScope) -> Unit,
    onManual: () -> Unit,
    onImportJson: () -> Unit,
    onDismiss: () -> Unit
) {
    val scopes = listOf(McpScope.GLOBAL, McpScope.PROJECT)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.mcp_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.mcp_scope_label),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SegmentedTabs(
                    selected = scopes.indexOf(scope).coerceAtLeast(0),
                    labels = listOf(
                        stringResource(R.string.mcp_scope_global),
                        stringResource(R.string.mcp_scope_project)
                    ),
                    onSelect = { onScopeChange(scopes[it]) }
                )
            }

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Edit3,
                    title = stringResource(R.string.mcp_add_manual),
                    subtitle = stringResource(R.string.mcp_add_manual_desc),
                    onClick = onManual
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.mcp_add_from_json),
                    subtitle = stringResource(R.string.mcp_add_from_json_desc),
                    onClick = onImportJson
                )
            }
        }
    }
}
