package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.settings.data.repository.ALT_DOWNLOAD_MIRROR
import com.aicode.feature.settings.data.repository.DEFAULT_DOWNLOAD_MIRROR
import com.aicode.feature.settings.data.repository.UpdateChannel
import com.aicode.feature.settings.data.repository.isValidDownloadMirror
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Book
import compose.icons.feathericons.Check
import compose.icons.feathericons.Download
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Tag

/**
 * 关于页：顶部应用信息、版本号（点击手动检查更新）、自动检查更新开关、更新通道、
 * 下载镜像源、GitHub 仓库、开源许可证、用户协议与隐私政策。检查更新逻辑与弹窗由全局 [SettingsViewModel]
 * 承载，本页只负责触发与展示设置项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSection(
    updateCheckEnabled: Boolean,
    updateCheckChannel: UpdateChannel,
    updateCheckMirror: String,
    onToggleUpdateCheck: (Boolean) -> Unit,
    onSelectChannel: (UpdateChannel) -> Unit,
    onSelectMirror: (String) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var showMirrorDialog by remember { mutableStateOf(false) }
    val termsUrl = stringResource(R.string.agreement_terms_url)
    val privacyUrl = stringResource(R.string.agreement_privacy_url)

    // 通过 PackageManager 读取 versionName/versionCode（项目未开启 BuildConfig）
    val appInfo = remember {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            AppVersion(name = info.versionName ?: "unknown", code = code)
        }.getOrDefault(AppVersion("unknown", 0L))
    }

    // 应用图标：用 PackageManager.loadIcon 加载自适应图标，避免 mipmap-anydpi 的 XML
    // 在 Compose painterResource 中不被支持的问题（Only VectorDrawables and rasterized types）。
    val appIcon = remember { loadAppIconBitmap(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        AboutHeaderCard(appName = stringResource(R.string.app_name), appIcon = appIcon)

        SettingsGroup {
            // 版本：点击手动检查更新
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.about_version),
                onClick = onCheckUpdate,
                trailing = {
                    Text(
                        text = "v${appInfo.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            // 自动检查更新：开关
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.about_auto_check_update),
                subtitle = stringResource(R.string.about_auto_check_update_desc),
                trailing = {
                    AppSwitch(
                        checked = updateCheckEnabled,
                        onCheckedChange = onToggleUpdateCheck
                    )
                }
            )
            SettingsDivider()
            // 下载镜像源：更新包下载走它加速，留空表示直连 GitHub
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.about_update_mirror),
                onClick = { showMirrorDialog = true },
                trailing = {
                    Text(
                        text = updateCheckMirror.ifBlank {
                            stringResource(R.string.about_update_mirror_preset_direct)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp)
                    )
                }
            )
            SettingsDivider()
            // GitHub 仓库：原上游项目
            SettingsRow(
                icon = FeatherIcons.Github,
                title = stringResource(R.string.about_github_repo),
                onClick = { openUrl(context, GITHUB_REPO_URL) }
            )
            SettingsDivider()
            // GitHub 仓库：本二改仓库
            SettingsRow(
                icon = FeatherIcons.Github,
                title = stringResource(R.string.about_github_project_repo),
                onClick = { openUrl(context, PROJECT_REPO_URL) }
            )
            SettingsDivider()
            // 许可证
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(R.string.about_license),
                onClick = { openUrl(context, LICENSE_URL) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(R.string.agreement_terms),
                onClick = { openUrl(context, termsUrl) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.AlertCircle,
                title = stringResource(R.string.agreement_privacy),
                onClick = { openUrl(context, privacyUrl) }
            )
        }
    }

    if (showMirrorDialog) {
        MirrorSourceDialog(
            current = updateCheckMirror,
            onConfirm = {
                onSelectMirror(it)
                showMirrorDialog = false
            },
            onDismiss = { showMirrorDialog = false }
        )
    }
}

/** 顶部信息卡：左 app 图标，右上 app 名，右下一句简介。 */
@Composable
private fun AboutHeaderCard(appName: String, appIcon: androidx.compose.ui.graphics.ImageBitmap?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                // 极端情况下图标加载失败的占位，保持布局占位
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 更新通道底部弹窗：稳定版 / 最新版 单选，样式对齐语言切换。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateChannelSheet(
    current: UpdateChannel,
    onSelect: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.about_update_channel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            listOf(
                UpdateChannel.STABLE to stringResource(R.string.update_channel_stable),
                UpdateChannel.LATEST to stringResource(R.string.update_channel_latest)
            ).forEach { (channel, label) ->
                val selected = channel == current
                Surface(
                    onClick = {
                        onDismiss()
                        onSelect(channel)
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = stringResource(
                                    if (channel == UpdateChannel.STABLE) {
                                        R.string.update_channel_stable_desc
                                    } else {
                                        R.string.update_channel_latest_desc
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 镜像源快捷项：预置常用节点 + 直连，省得用户手打前缀。 */
private data class MirrorPreset(val labelRes: Int, val value: String)

private val MIRROR_PRESETS = listOf(
    MirrorPreset(R.string.about_update_mirror_preset_cdn, DEFAULT_DOWNLOAD_MIRROR),
    MirrorPreset(R.string.about_update_mirror_preset_v6, ALT_DOWNLOAD_MIRROR),
    MirrorPreset(R.string.about_update_mirror_preset_direct, "")
)

/**
 * 下载镜像源编辑弹窗：预置快捷项 + 自定义前缀 + 恢复默认。
 *
 * 约定是「前缀拼接」（`https://镜像/https://github.com/...`），含 `{url}` 占位符时按占位符
 * 替换，所以第三方 gh-proxy 实例与自建节点都能直接填；留空 = 直连 GitHub。
 * 「恢复默认」只把输入框填回默认值，仍需点「保存」才落盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MirrorSourceDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(current) { mutableStateOf(current) }
    val valid = isValidDownloadMirror(text)
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_update_mirror)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.about_update_mirror_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MIRROR_PRESETS.forEach { preset ->
                    val selected = trimmed == preset.value
                    Surface(onClick = { text = preset.value }, color = Color.Transparent) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(preset.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = FeatherIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = DEFAULT_DOWNLOAD_MIRROR,
                    isError = !valid,
                    supportingText = if (valid) {
                        null
                    } else {
                        { Text(stringResource(R.string.about_update_mirror_invalid)) }
                    },
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = { text = DEFAULT_DOWNLOAD_MIRROR }) {
                    Text(stringResource(R.string.about_update_mirror_default))
                }
                TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** GitHub tag 形如 v1.7.0 / 1.7.0 / v1.7.0-rc1，提取出纯版本号。 */
internal fun parseVersionTag(tag: String): String? {
    val raw = tag.trim().removePrefix("v")
    val seg = raw.substringBefore(' ')
    return seg.ifBlank { null }
}

/**
 * 语义化版本比较：判断最新版本 [latest] 是否严格大于当前版本 [current]。
 * 若 latest 不大于 current（即最新版本已安装或当前属于开发/测试版），返回 true 表示已是最新。
 */
internal fun isUpToDate(latest: String, current: String): Boolean {
    val cmp = compareVersions(latest, current)
    return cmp <= 0
}

/**
 * 语义化版本号比较器（SemVer 兼容）：
 * 返回 >0 表示 v1 > v2，<0 表示 v1 < v2，0 表示相等。
 * 规则：
 * 1. 优先比较主.次.修（如 1.7.0 > 1.6.9）
 * 2. 主次修相同时，正式版 > 预发布版（1.7.0 > 1.7.0-rc1 > 1.7.0-dev）
 * 3. 均为预发布版时，同标识比序号（rc2 > rc1、dev.10 > dev.9），不同标识按字典序
 */
internal fun compareVersions(v1: String, v2: String): Int {
    if (v1 == v2) return 0

    val (base1, pre1) = splitVersion(v1)
    val (base2, pre2) = splitVersion(v2)

    val parts1 = base1.split('.').mapNotNull { it.toIntOrNull() }
    val parts2 = base2.split('.').mapNotNull { it.toIntOrNull() }

    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }

    // 主版本号相同的情况下：
    // 正式版 (pre为空) > 预发布版 (pre不为空)
    if (pre1.isEmpty() && pre2.isNotEmpty()) return 1
    if (pre1.isNotEmpty() && pre2.isEmpty()) return -1

    return comparePreRelease(pre1, pre2)
}

private val PRE_RELEASE_TOKEN = Regex("^(\\D*?)(\\d+)$")

/**
 * 预发布段比较：「同标识 + 序号」按数字比（rc10 > rc9），避免字典序把 rc10 排在 rc9 前；
 * 标识不同或不含尾随数字时退回字典序。
 */
private fun comparePreRelease(a: String, b: String): Int {
    val ma = PRE_RELEASE_TOKEN.matchEntire(a)
    val mb = PRE_RELEASE_TOKEN.matchEntire(b)
    if (ma != null && mb != null && ma.groupValues[1] == mb.groupValues[1]) {
        return ma.groupValues[2].toInt().compareTo(mb.groupValues[2].toInt())
    }
    return a.compareTo(b)
}

internal fun splitVersion(v: String): Pair<String, String> {
    val clean = v.substringBefore('+') // 去掉构建哈希如 +g04bc2fa
    val base = clean.substringBefore('-')
    val pre = if (clean.contains('-')) clean.substringAfter('-') else ""
    return base to pre
}

private data class AppVersion(val name: String, val code: Long)

/**
 * 通过 PackageManager.loadIcon 加载应用图标并转为 ImageBitmap，兼容自适应图标
 * (adaptive icon XML)。解决 painterResource(R.mipmap.ic_launcher) 在 v26+ 设备上
 * 因解析到 mipmap-anydpi-v26/ic_launcher.xml 而抛 IllegalArgumentException 的问题。
 * 失败返回 null（调用方预留占位）。
 */
private fun loadAppIconBitmap(context: Context): ImageBitmap? {
    return runCatching {
        val pm = context.packageManager
        val drawable: Drawable = pm.getApplicationInfo(context.packageName, 0).loadIcon(pm)
        val sizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            ICON_PX_DP.toFloat(),
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        Canvas(bitmap).also { drawable.draw(it) }
        bitmap.asImageBitmap()
    }.getOrNull()
}

private const val GITHUB_REPO_URL = "https://github.com/jieapi/aicode"
private const val PROJECT_REPO_URL = "https://github.com/xzyy03/AiCode-MiUiX"
private const val LICENSE_URL = "https://github.com/xzyy03/AiCode-MiUiX/blob/main/LICENSE"
private const val ICON_PX_DP = 48
