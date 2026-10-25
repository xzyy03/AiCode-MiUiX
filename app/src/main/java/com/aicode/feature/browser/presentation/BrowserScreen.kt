package com.aicode.feature.browser.presentation

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.browser.BrowserDialog
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.domain.tool.browser.BrowserTabState
import com.aicode.feature.agent.domain.tool.browser.BrowserUserAgentPreset
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Sun
import compose.icons.feathericons.Tablet
import compose.icons.feathericons.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "BrowserScreen"

/**
 * 界面触发的浏览器操作统一走此兜底：navigate/reload/newTab 等在加载失败时会抛出异常，
 * 未捕获会直接让 UI 协程崩溃。失败信息已由 BrowserManager 写入 tab.error 展示给用户，这里仅记录日志。
 */
private fun CoroutineScope.launchBrowserOp(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "浏览器界面操作失败", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserManager: BrowserManager,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by browserManager.state.collectAsStateWithLifecycle()
    val activeTab = state.activeTab
    val scope = rememberCoroutineScope()

    // 每个页面实例各持一个容器：NavHost 过渡期间新旧页面会短暂共存，若共用同一个 FrameLayout，
    // 后创建的 AndroidView 会拿到仍挂在旧节点上的容器，AndroidViewHolder 构造时 addView 直接抛
    // 「The specified child already has a parent」。各持一份即可彻底避免。
    val context = LocalContext.current
    val container = remember { FrameLayout(context) }

    var showTabsSheet by rememberSaveable { mutableStateOf(false) }
    val tabsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showMenuSheet by rememberSaveable { mutableStateOf(false) }
    val menuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showCustomUaDialog by rememberSaveable { mutableStateOf(false) }

    var isEditingAddress by rememberSaveable { mutableStateOf(false) }

    // 标签页切换时退出编辑态
    LaunchedEffect(state.activeTabId) {
        isEditingAddress = false
    }

    // 拦截物理/手势返回键：若处于地址栏编辑态，优先退出编辑态
    BackHandler(enabled = isEditingAddress) {
        isEditingAddress = false
    }

    // 网页对话框：面板可见时交给用户处理（UI 优先，AI 的 dialog action 会被拒）
    state.pendingDialog?.let { dialog ->
        BrowserJsDialog(
            dialog = dialog,
            onResolve = { accept, text ->
                scope.launchBrowserOp { browserManager.resolveDialogFromUi(accept, text) }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrowserTopBar(
                tabId = state.activeTabId,
                title = activeTab?.title.orEmpty(),
                url = activeTab?.url.orEmpty(),
                loading = activeTab?.loading == true,
                isEditing = isEditingAddress,
                onEditingChange = { isEditingAddress = it },
                embedded = embedded,
                onNavigateBack = onNavigateBack,
                onReload = { scope.launchBrowserOp { browserManager.reload() } },
                onNavigate = { url ->
                    isEditingAddress = false
                    scope.launchBrowserOp { browserManager.navigate(url) }
                }
            )
        },
        bottomBar = {
            BrowserBottomBar(
                canGoBack = activeTab?.loading == false,
                canGoForward = activeTab?.loading == false,
                tabsCount = state.tabs.size,
                hasActiveSettings = state.nightMode || state.devToolsOpen || state.userAgentPreset != BrowserUserAgentPreset.DEFAULT,
                embedded = embedded,
                onGoBack = { scope.launchBrowserOp { browserManager.goBack() } },
                onGoForward = { scope.launchBrowserOp { browserManager.goForward() } },
                onNewTab = { scope.launchBrowserOp { browserManager.newTab() } },
                onOpenTabs = { showTabsSheet = true },
                onOpenMenu = { showMenuSheet = true }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = {
                    browserManager.attachContainerView(container)
                    container
                },
                modifier = Modifier.fillMaxSize()
            )

            // 当地址栏处于编辑态时覆盖遮罩，拦截手势并支持轻点任意空白区域退出编辑态
            if (isEditingAddress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isEditingAddress = false
                        }
                )
            }

            // 错误提示
            if (activeTab?.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(Spacing.lg)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = activeTab.error.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        IconButton(onClick = { scope.launchBrowserOp { browserManager.reload() } }) {
                            Icon(
                                FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.browser_reload),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 空白标签页状态：引导输入网址
            if (activeTab != null && activeTab.url.isEmpty() && !activeTab.loading && activeTab.error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(Spacing.xl)
                    ) {
                        Icon(
                            FeatherIcons.Globe,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.browser_empty_click_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 多标签页管理抽屉
    if (showTabsSheet) {
        AdaptiveModalBottomSheet(
            onDismissRequest = { showTabsSheet = false },
            sheetState = tabsSheetState,
            containerColor = MaterialTheme.semanticColors.pageBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xl)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.browser_tabs_count, state.tabs.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = {
                            scope.launchBrowserOp {
                                browserManager.newTab()
                                showTabsSheet = false
                            }
                        }
                    ) {
                        Icon(
                            FeatherIcons.Plus,
                            contentDescription = stringResource(R.string.browser_new_tab),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                SettingsGroup {
                    state.tabs.forEachIndexed { index, tab ->
                        if (index > 0) SettingsDivider()
                        BrowserTabItemRow(
                            tab = tab,
                            selected = tab.id == state.activeTabId,
                            onClick = {
                                scope.launchBrowserOp {
                                    browserManager.selectTab(tab.id)
                                    showTabsSheet = false
                                }
                            },
                            onClose = {
                                scope.launchBrowserOp {
                                    browserManager.closeTab(tab.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 更多设置与工具抽屉（夜间模式、调试工具、浏览器 UA）
    if (showMenuSheet) {
        BrowserMenuSheet(
            sheetState = menuSheetState,
            nightMode = state.nightMode,
            devToolsOpen = state.devToolsOpen,
            userAgentPreset = state.userAgentPreset,
            customUserAgent = state.customUserAgent,
            onDismiss = { showMenuSheet = false },
            onToggleNightMode = { browserManager.toggleNightMode() },
            onToggleDevTools = {
                scope.launchBrowserOp {
                    browserManager.toggleDevTools()
                }
            },
            onSelectUaPreset = { preset ->
                if (preset == BrowserUserAgentPreset.CUSTOM) {
                    showMenuSheet = false
                    showCustomUaDialog = true
                } else {
                    scope.launchBrowserOp {
                        browserManager.setUserAgent(preset, reloadCurrent = true)
                    }
                    showMenuSheet = false
                }
            }
        )
    }

    // 自定义 User-Agent 编辑对话框
    if (showCustomUaDialog) {
        BrowserCustomUaDialog(
            initialValue = state.customUserAgent.ifBlank { state.currentUserAgent },
            onDismiss = { showCustomUaDialog = false },
            onConfirm = { customUa ->
                showCustomUaDialog = false
                scope.launchBrowserOp {
                    browserManager.setUserAgent(
                        preset = BrowserUserAgentPreset.CUSTOM,
                        customUa = customUa,
                        reloadCurrent = true
                    )
                }
            }
        )
    }

    DisposableEffect(container) {
        onDispose {
            browserManager.detachContainerView(container)
        }
    }
}

/**
 * 顶部栏独立组件：将输入态和文字变化隔离在此处，避免打字导致整屏重组卡顿。
 */
@Composable
private fun BrowserTopBar(
    tabId: String,
    title: String,
    url: String,
    loading: Boolean,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    embedded: Boolean,
    onNavigateBack: () -> Unit,
    onReload: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var addressInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(url))
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 标签页切换或新建时，重置输入框内容
    LaunchedEffect(tabId) {
        addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
    }

    // 当非编辑态下 URL 变更时同步
    LaunchedEffect(url, isEditing) {
        if (!isEditing) {
            addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
        }
    }

    // 处理编辑态切换聚焦与键盘
    LaunchedEffect(isEditing) {
        if (isEditing) {
            addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：返回 / 取消编辑
                IconButton(onClick = {
                    if (isEditing) {
                        onEditingChange(false)
                    } else {
                        onNavigateBack()
                    }
                }) {
                    Icon(
                        if (isEditing || embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                        contentDescription = stringResource(
                            if (isEditing || embedded) R.string.common_close else R.string.common_back
                        )
                    )
                }

                // 中间：标题与输入胶囊框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                        .clickable(!isEditing) { onEditingChange(true) }
                        .padding(horizontal = Spacing.sm),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (!isEditing) {
                        val displayTitle = when {
                            title.isNotBlank() -> title
                            url.isNotBlank() -> url.removePrefix("https://").removePrefix("http://")
                            else -> stringResource(R.string.browser_title)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                FeatherIcons.Globe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (title.isNotBlank() || url.isNotBlank()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                FeatherIcons.Globe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            BasicTextField(
                                value = addressInput,
                                onValueChange = { addressInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    val input = addressInput.text.trim()
                                    if (input.isNotEmpty()) {
                                        onEditingChange(false)
                                        onNavigate(input)
                                    }
                                }),
                                decorationBox = { innerTextField ->
                                    if (addressInput.text.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.browser_address_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (addressInput.text.isNotEmpty()) {
                                IconButton(
                                    onClick = { addressInput = TextFieldValue("") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        FeatherIcons.X,
                                        contentDescription = stringResource(R.string.common_clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 右侧：刷新按钮
                IconButton(
                    onClick = onReload,
                    enabled = !loading,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.browser_reload),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 底部操作栏独立组件
 */
@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabsCount: Int,
    hasActiveSettings: Boolean,
    embedded: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = DividerDefaults.color.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 后退
                IconButton(
                    onClick = onGoBack,
                    enabled = canGoBack
                ) {
                    Icon(
                        FeatherIcons.ChevronLeft,
                        contentDescription = stringResource(R.string.browser_back),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 2. 前进
                IconButton(
                    onClick = onGoForward,
                    enabled = canGoForward
                ) {
                    Icon(
                        FeatherIcons.ChevronRight,
                        contentDescription = stringResource(R.string.browser_forward),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 3. 新建标签页
                IconButton(onClick = onNewTab) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.browser_new_tab),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 4. 标签页管理按钮（数字方框徽标）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            1.8.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable(onClick = onOpenTabs),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$tabsCount",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 5. 更多更改菜单（三条杠图标）
                IconButton(onClick = onOpenMenu) {
                    Box {
                        Icon(
                            FeatherIcons.Menu,
                            contentDescription = stringResource(R.string.browser_menu),
                            tint = if (hasActiveSettings) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
    sheetState: androidx.compose.material3.SheetState,
    nightMode: Boolean,
    devToolsOpen: Boolean,
    userAgentPreset: BrowserUserAgentPreset,
    customUserAgent: String,
    onDismiss: () -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleDevTools: () -> Unit,
    onSelectUaPreset: (BrowserUserAgentPreset) -> Unit
) {
    // 菜单层级：0 = 一级主菜单，1 = 二级 UA 场景选择菜单
    var menuLevel by rememberSaveable { mutableStateOf(0) }

    AdaptiveModalBottomSheet(
        onDismissRequest = {
            menuLevel = 0
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.semanticColors.pageBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.xl)
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (menuLevel == 1) {
                    IconButton(
                        onClick = { menuLevel = 0 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(
                    text = stringResource(
                        if (menuLevel == 1) R.string.browser_ua_section else R.string.browser_menu_title
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (menuLevel == 0) {
                // 一级菜单：单行按钮列表（夜间模式、开发者工具、浏览器标识）
                val uaIcon: ImageVector = when (userAgentPreset) {
                    BrowserUserAgentPreset.DEFAULT -> FeatherIcons.Globe
                    BrowserUserAgentPreset.ANDROID_PHONE -> FeatherIcons.Smartphone
                    BrowserUserAgentPreset.ANDROID_TABLET -> FeatherIcons.Tablet
                    BrowserUserAgentPreset.DESKTOP_PC -> FeatherIcons.Monitor
                    BrowserUserAgentPreset.IPHONE -> FeatherIcons.Smartphone
                    BrowserUserAgentPreset.IPAD -> FeatherIcons.Tablet
                    BrowserUserAgentPreset.CUSTOM -> FeatherIcons.Sliders
                }

                SettingsGroup {
                    // 1. 夜间模式
                    SettingsRow(
                        icon = if (nightMode) FeatherIcons.Moon else FeatherIcons.Sun,
                        title = stringResource(R.string.browser_night_mode_title),
                        trailing = {
                            AppSwitch(
                                checked = nightMode,
                                onCheckedChange = { onToggleNightMode() }
                            )
                        }
                    )

                    SettingsDivider()

                    // 2. 开发者工具
                    SettingsRow(
                        icon = FeatherIcons.Code,
                        title = stringResource(R.string.browser_devtools_title),
                        trailing = {
                            AppSwitch(
                                checked = devToolsOpen,
                                onCheckedChange = { onToggleDevTools() }
                            )
                        }
                    )

                    SettingsDivider()

                    // 3. 浏览器标识 (UA) - 单行按钮，点击展开二级菜单
                    SettingsRow(
                        icon = uaIcon,
                        title = stringResource(R.string.browser_ua_section),
                        trailing = {
                            Text(
                                text = stringResource(userAgentPreset.titleRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { menuLevel = 1 }
                    )
                }
            } else {
                // 二级菜单：选择 User-Agent 场景
                SettingsGroup {
                    val presets = BrowserUserAgentPreset.entries
                    presets.forEachIndexed { index, preset ->
                        if (index > 0) SettingsDivider()
                        val selected = preset == userAgentPreset
                        val icon: ImageVector = when (preset) {
                            BrowserUserAgentPreset.DEFAULT -> FeatherIcons.Globe
                            BrowserUserAgentPreset.ANDROID_PHONE -> FeatherIcons.Smartphone
                            BrowserUserAgentPreset.ANDROID_TABLET -> FeatherIcons.Tablet
                            BrowserUserAgentPreset.DESKTOP_PC -> FeatherIcons.Monitor
                            BrowserUserAgentPreset.IPHONE -> FeatherIcons.Smartphone
                            BrowserUserAgentPreset.IPAD -> FeatherIcons.Tablet
                            BrowserUserAgentPreset.CUSTOM -> FeatherIcons.Sliders
                        }
                        val subtitle = if (preset == BrowserUserAgentPreset.CUSTOM && customUserAgent.isNotBlank()) {
                            customUserAgent
                        } else {
                            stringResource(preset.descRes)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    menuLevel = 0
                                    onSelectUaPreset(preset)
                                }
                                .padding(horizontal = Spacing.lg, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(preset.titleRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Spacer(Modifier.width(Spacing.sm))
                                Icon(
                                    imageVector = FeatherIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserCustomUaDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var uaText by rememberSaveable { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.browser_ua_custom_dialog_title))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = uaText,
                    onValueChange = { uaText = it },
                    placeholder = stringResource(R.string.browser_ua_custom_input_hint),
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(uaText.trim()) },
                enabled = uaText.isNotBlank()
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * 标签页管理列表行：集成 [SwipeToDeleteRow] 支持左滑删除与点击切换，
 * 采用全局统一卡片行风格。
 */
@Composable
private fun BrowserTabItemRow(
    tab: BrowserTabState,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val title = when {
        tab.title.isNotBlank() -> tab.title
        tab.url.isNotBlank() -> tab.url.removePrefix("https://").removePrefix("http://")
        else -> stringResource(R.string.browser_tab_default_title)
    }

    SwipeToDeleteRow(
        onDelete = onClose,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Globe,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tab.url.isNotBlank()) {
                    Text(
                        text = tab.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (tab.loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = Spacing.xs),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    FeatherIcons.X,
                    contentDescription = stringResource(R.string.browser_close_tab),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 网页弹出的 alert/confirm/prompt。只在浏览器面板可见时展示，且必须由用户显式选择：
 * 点击外部不关闭，否则 JsResult 一直悬空，页面 JS 会持续阻塞到 30 秒超时。
 */
@Composable
private fun BrowserJsDialog(
    dialog: BrowserDialog,
    onResolve: (accept: Boolean, text: String?) -> Unit
) {
    var text by remember(dialog) { mutableStateOf(dialog.defaultValue.orEmpty()) }

    val title = when (dialog.type) {
        "confirm" -> stringResource(R.string.browser_dialog_confirm_title)
        "prompt" -> stringResource(R.string.browser_dialog_prompt_title)
        else -> stringResource(R.string.browser_dialog_alert_title)
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = dialog.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (dialog.type == "prompt") {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = dialogTextFieldColors()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true, text) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = if (dialog.type == "alert") {
            null
        } else {
            {
                TextButton(onClick = { onResolve(false, null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}
