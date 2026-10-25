package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.settings.presentation.McpAuthUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Info
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.X

/**
 * HTTP 形态的 OAuth 认证区块：
 * 1. OAuth 开关卡片（带说明副标题）
 * 2. 授权状态与操作卡片（状态徽章、进行中独立进度与取消授权按钮、登录/重授权/退出操作、失败详情）
 * 3. 可折叠的高级认证选项（预注册 client_id、自定义授权服务器）
 */
@Composable
internal fun McpAuthFields(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    clientId: String,
    onClientIdChange: (String) -> Unit,
    authServer: String,
    onAuthServerChange: (String) -> Unit,
    serverName: String?,
    authState: McpAuthUiState,
    connected: Boolean,
    onAuthorize: (String) -> Unit,
    onLogout: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    // 授权状态按 server 隔离：只展示属于当前 server 的进行中/失败信息，避免 A 的报错串到 B。
    val state = if (authState.serverName == serverName) authState else McpAuthUiState()
    var advancedExpanded by remember {
        mutableStateOf(clientId.isNotBlank() || authServer.isNotBlank())
    }

    // ── 1. OAuth 认证主开关卡片 ──
    Card(
        shape = RoundedCornerShape(Radius.mdLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(Radius.sm)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Shield,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mcp_auth_enable),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.mcp_auth_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))
            AppSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }

    AnimatedVisibility(visible = enabled) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // ── 2. 授权状态与操作面板 ──
            if (serverName == null) {
                Card(
                    shape = RoundedCornerShape(Radius.mdLarge),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.mcp_auth_save_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                val isAuthorized = state.success || connected
                Card(
                    shape = RoundedCornerShape(Radius.lg),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        // 顶部状态栏：左侧标题，右侧状态胶囊
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.mcp_auth_status_label),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            val statusBg = when {
                                state.inProgress -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                isAuthorized -> MaterialTheme.semanticColors.successContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            val statusFg = when {
                                state.inProgress -> MaterialTheme.colorScheme.primary
                                isAuthorized -> MaterialTheme.semanticColors.onSuccessContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val statusLabel = when {
                                state.inProgress -> stringResource(R.string.mcp_auth_status_authorizing)
                                state.success -> stringResource(R.string.mcp_auth_success)
                                isAuthorized -> stringResource(R.string.mcp_auth_status_authorized)
                                else -> stringResource(R.string.mcp_auth_status_unauthorized)
                            }

                            Row(
                                modifier = Modifier
                                    .background(statusBg, RoundedCornerShape(Radius.pill))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when {
                                    state.inProgress -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.8.dp,
                                            color = statusFg
                                        )
                                    }
                                    isAuthorized -> {
                                        Icon(
                                            imageVector = FeatherIcons.Check,
                                            contentDescription = null,
                                            tint = statusFg,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    color = statusFg
                                )
                            }
                        }

                        // 错误提示横幅
                        state.error?.let { message ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                        shape = RoundedCornerShape(Radius.md)
                                    )
                                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.AlertCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(top = 1.dp)
                                )
                                Text(
                                    text = stringResource(R.string.mcp_auth_failed, message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // 动作区：授权进行中 vs 常规状态分开排布，避免同一行堆叠导致按钮被遮挡
                        if (state.inProgress) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(Radius.mdLarge)
                                    )
                                    .padding(Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.mcp_auth_in_progress),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.mcp_auth_in_progress_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { onCancel(serverName) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(Radius.md),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.X,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.mcp_auth_cancel),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onAuthorize(serverName) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    shape = RoundedCornerShape(Radius.mdLarge)
                                ) {
                                    Icon(
                                        imageVector = if (isAuthorized) FeatherIcons.RefreshCw else FeatherIcons.ExternalLink,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAuthorized) {
                                            stringResource(R.string.mcp_auth_reauthorize)
                                        } else {
                                            stringResource(R.string.mcp_auth_login)
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onLogout(serverName) },
                                    modifier = Modifier.height(42.dp),
                                    shape = RoundedCornerShape(Radius.mdLarge),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.mcp_auth_logout),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. 可折叠高级认证配置（client_id / 授权服务器）──
            Card(
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.lg))
                            .clickable { advancedExpanded = !advancedExpanded }
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Sliders,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.mcp_auth_advanced),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        ExpandableChevronIcon(
                            expanded = advancedExpanded,
                            style = ChevronRotationStyle.RIGHT_DOWN,
                            size = 16.dp,
                            tint = MaterialTheme.semanticColors.subtleText
                        )
                    }

                    AnimatedVisibility(visible = advancedExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(bottom = Spacing.xs)
                            )
                            AppTextField(
                                value = clientId,
                                onValueChange = onClientIdChange,
                                label = stringResource(R.string.mcp_auth_client_id),
                                placeholder = stringResource(R.string.mcp_auth_client_id_hint),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            AppTextField(
                                value = authServer,
                                onValueChange = onAuthServerChange,
                                label = stringResource(R.string.mcp_auth_server),
                                placeholder = stringResource(R.string.mcp_auth_server_hint),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
