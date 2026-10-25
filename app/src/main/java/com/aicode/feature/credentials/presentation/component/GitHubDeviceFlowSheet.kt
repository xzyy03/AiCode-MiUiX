package com.aicode.feature.credentials.presentation.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.credentials.data.remote.GITHUB_APP_INSTALL_URL
import com.aicode.feature.credentials.presentation.QuickLoginUiState
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Github
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GitHub OAuth 设备码授权弹层：采用 Apple HIG 风格设计，
 * 具有清晰的状态层级、醒目的设备码卡片和一键「复制并前往授权」体验。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GitHubDeviceFlowSheet(
    state: QuickLoginUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSuccessDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 成功后短暂展示反馈再自动收起；未安装 App 时保留弹层供用户继续操作
    LaunchedEffect(state) {
        if (state is QuickLoginUiState.Success && !state.needsInstall) {
            delay(1400)
            onSuccessDismiss()
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            // 顶部 Header：GitHub 品牌标识与标题
            DeviceFlowHeader()

            when (state) {
                QuickLoginUiState.Idle -> Unit

                QuickLoginUiState.RequestingCode -> {
                    RequestingCodeContent()
                }

                is QuickLoginUiState.Awaiting -> {
                    AwaitingContent(
                        userCode = state.userCode,
                        verificationUri = state.verificationUri,
                        context = context,
                        onCopyCode = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("device-code", state.userCode))
                                )
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.credential_device_code_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onAuthorizeAndOpen = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("device-code", state.userCode))
                                )
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.credential_device_code_copied_opening),
                                Toast.LENGTH_SHORT
                            ).show()
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri))
                                )
                            }
                        }
                    )
                }

                is QuickLoginUiState.Success -> {
                    SuccessContent(
                        username = state.username,
                        needsInstall = state.needsInstall,
                        context = context,
                        onSuccessDismiss = onSuccessDismiss
                    )
                }

                is QuickLoginUiState.Failed -> {
                    FailedContent(
                        errorMessage = state.message,
                        onRetry = onRetry,
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

/** 顶部品牌 Header */
@Composable
private fun DeviceFlowHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(Radius.mdLarge)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Github,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.credential_device_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.credential_device_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 申请设备码中的过渡态 */
@Composable
private fun RequestingCodeContent() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.credential_device_requesting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 等待授权核心态 */
@Composable
private fun AwaitingContent(
    userCode: String,
    verificationUri: String,
    context: Context,
    onCopyCode: () -> Unit,
    onAuthorizeAndOpen: () -> Unit
) {
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        // 核心验证码展示卡片（支持点击直接复制）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xl))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.semanticColors.subtleBorder.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(Radius.xl)
                )
                .clickable {
                    justCopied = true
                    onCopyCode()
                },
            shape = RoundedCornerShape(Radius.xl),
            color = MaterialTheme.semanticColors.cardSurface,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.credential_device_card_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (justCopied) {
                                    MaterialTheme.semanticColors.successContainer
                                } else {
                                    MaterialTheme.semanticColors.mutedSurface
                                },
                                shape = RoundedCornerShape(Radius.pill)
                            )
                            .padding(horizontal = Spacing.sm, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (justCopied) FeatherIcons.Check else FeatherIcons.Copy,
                                contentDescription = null,
                                tint = if (justCopied) {
                                    MaterialTheme.semanticColors.onSuccessContainer
                                } else {
                                    MaterialTheme.semanticColors.subtleText
                                },
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (justCopied) {
                                    stringResource(R.string.credential_device_code_copied)
                                } else {
                                    stringResource(R.string.credential_device_tap_to_copy)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (justCopied) {
                                    MaterialTheme.semanticColors.onSuccessContainer
                                } else {
                                    MaterialTheme.semanticColors.subtleText
                                },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 大号等宽代码
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )

                Text(
                    text = verificationUri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 步骤提示条
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.semanticColors.mutedSurface.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.credential_device_step_1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.credential_device_step_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 主操作：复制并前往授权
        Button(
            onClick = onAuthorizeAndOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(Radius.mdLarge)
        ) {
            Icon(
                imageVector = FeatherIcons.ExternalLink,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.credential_device_authorize_and_open),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 次操作：仅复制设备码
        OutlinedButton(
            onClick = onCopyCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(Radius.mdLarge)
        ) {
            Icon(
                imageVector = FeatherIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.credential_device_copy_code),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 底部轮询等待指示条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.credential_device_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 成功态 */
@Composable
private fun SuccessContent(
    username: String,
    needsInstall: Boolean,
    context: Context,
    onSuccessDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.xl),
            color = MaterialTheme.semanticColors.cardSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.semanticColors.successContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.semanticColors.onSuccessContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.credential_device_success_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.credential_device_success, username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (needsInstall) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.semanticColors.warningContainer.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.semanticColors.warning.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Text(
                        text = stringResource(R.string.credential_device_install_required, username),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_APP_INSTALL_URL))
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.mdLarge)
                        ) {
                            Text(stringResource(R.string.credential_device_install_now))
                        }

                        TextButton(
                            onClick = onSuccessDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.credential_device_install_later))
                        }
                    }
                }
            }
        }
    }
}

/** 失败态 */
@Composable
private fun FailedContent(
    errorMessage: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xl),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = stringResource(R.string.credential_device_failed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.credential_device_failed, errorMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.mdLarge)
                ) {
                    Text(stringResource(R.string.common_retry))
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.mdLarge)
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
