package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.subagent.SubagentModelTier
import com.aicode.feature.agent.domain.subagent.SubagentTierSelection
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.local.ModelSheetCollapseStore
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Image
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Type
import compose.icons.feathericons.Zap

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    titleProviderId: String,
    titleModel: String,
    imageGenProviderId: String,
    imageGenModel: String,
    subagentTierSelections: Map<SubagentModelTier, SubagentTierSelection>,
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit,
    onSelectTitleModel: (providerId: String, model: String) -> Unit,
    onClearTitleModel: () -> Unit,
    onSelectImageGenModel: (providerId: String, model: String) -> Unit,
    onClearImageGenModel: () -> Unit,
    onSelectSubagentTierModel: (SubagentModelTier, providerId: String, model: String) -> Unit,
    onClearSubagentTierModel: (SubagentModelTier) -> Unit
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }
    var showTitleSheet by remember { mutableStateOf(false) }
    var showImageGenSheet by remember { mutableStateOf(false) }
    // 三档共用一个弹层：记下当前在编辑哪一档即可，不必三个 boolean。
    var tierSheet by remember { mutableStateOf<SubagentModelTier?>(null) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    val visionValue = if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        visionModel
    }

    val compactionValue = if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        compactionModel
    }

    val titleValue = if (titleProviderId.isBlank() || titleModel.isBlank()) {
        stringResource(R.string.settings_title_follow_chat)
    } else {
        titleModel
    }

    val imageGenValue = if (imageGenProviderId.isBlank() || imageGenModel.isBlank()) {
        stringResource(R.string.settings_image_gen_unconfigured)
    } else {
        imageGenModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_vision_model),
                onClick = { showVisionSheet = true },
                trailing = {
                    Text(
                        text = visionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Minimize2,
                title = stringResource(R.string.settings_compaction_model),
                onClick = { showCompactionSheet = true },
                trailing = {
                    Text(
                        text = compactionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Type,
                title = stringResource(R.string.settings_title_model),
                onClick = { showTitleSheet = true },
                trailing = {
                    Text(
                        text = titleValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Camera,
                title = stringResource(R.string.settings_image_gen_model),
                onClick = { showImageGenSheet = true },
                trailing = {
                    Text(
                        text = imageGenValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
        }

        // 子代理模型档位：AI 派发子代理时按任务难度选档，这里定义「哪一档对应哪个模型」。
        SettingsGroupHeader(text = stringResource(R.string.settings_subagent_tiers_header))
        SettingsGroup {
            SubagentModelTier.entries.forEachIndexed { index, tier ->
                if (index > 0) SettingsDivider()
                val selection = subagentTierSelections[tier] ?: SubagentTierSelection()
                SettingsRow(
                    icon = tierIcon(tier),
                    title = stringResource(tierTitleRes(tier)),
                    onClick = { tierSheet = tier },
                    trailing = {
                        Text(
                            text = if (selection.isConfigured) {
                                selection.model
                            } else {
                                stringResource(R.string.settings_subagent_tier_unconfigured)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(2f)
                        )
                    }
                )
            }
        }
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false }
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false }
        )
    }

    if (showTitleSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_title_model),
            noModelsText = stringResource(R.string.title_no_models),
            providers = providers,
            currentProviderId = titleProviderId,
            currentModel = titleModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectTitleModel(pid, model)
                showTitleSheet = false
            },
            onClear = {
                onClearTitleModel()
                showTitleSheet = false
            },
            onDismiss = { showTitleSheet = false }
        )
    }

    if (showImageGenSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_image_gen_model),
            noModelsText = stringResource(R.string.image_gen_no_models),
            providers = providers,
            currentProviderId = imageGenProviderId,
            currentModel = imageGenModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectImageGenModel(pid, model)
                showImageGenSheet = false
            },
            onClear = {
                onClearImageGenModel()
                showImageGenSheet = false
            },
            onDismiss = { showImageGenSheet = false }
        )
    }

    tierSheet?.let { tier ->
        val selection = subagentTierSelections[tier] ?: SubagentTierSelection()
        ModelSelectionSheet(
            title = stringResource(tierTitleRes(tier)),
            noModelsText = stringResource(R.string.subagent_no_models),
            providers = providers,
            currentProviderId = selection.providerId,
            currentModel = selection.model,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectSubagentTierModel(tier, pid, model)
                tierSheet = null
            },
            onClear = {
                onClearSubagentTierModel(tier)
                tierSheet = null
            },
            onDismiss = { tierSheet = null }
        )
    }
}

/** 档位显示名：轻量 / 标准 / 强力。 */
@StringRes
private fun tierTitleRes(tier: SubagentModelTier): Int = when (tier) {
    SubagentModelTier.LIGHT -> R.string.settings_subagent_tier_light
    SubagentModelTier.STANDARD -> R.string.settings_subagent_tier_standard
    SubagentModelTier.STRONG -> R.string.settings_subagent_tier_strong
}

/** 档位图标：由弱到强用不同图示，方便一屏扫读。 */
private fun tierIcon(tier: SubagentModelTier): ImageVector = when (tier) {
    SubagentModelTier.LIGHT -> FeatherIcons.Zap
    SubagentModelTier.STANDARD -> FeatherIcons.Sliders
    SubagentModelTier.STRONG -> FeatherIcons.Cpu
}

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 *
 * 测试支持：传入 [onTestModel]（主页场景）时每个模型行尾显示「测试」按钮与结果，不显示选中小勾；
 * 未传（识图/压缩等设置页场景）保持原样，仅在选中行尾显示小勾。测试态与结果由调用方持有，key 用 [modelMetadataKey]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    onTestModel: ((provider: AIProviderConfig, model: String) -> Unit)? = null,
    testingModels: Set<String> = emptySet(),
    testResults: Map<String, ModelTestResult> = emptyMap()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val collapseStore = remember { ModelSheetCollapseStore(context.applicationContext) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedProviderIds by remember { mutableStateOf(collapseStore.collapsedProviderIds()) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                onClear?.let { onClear ->
                    Text(
                        text = stringResource(R.string.common_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint)
            )

            val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            if (activeProviders.isEmpty()) {
                SettingsGroup {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = noModelsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    activeProviders.forEach { provider ->
                        val filteredModels = provider.models.filter {
                            searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                        }
                        if (filteredModels.isNotEmpty()) {
                            // 搜索时强制展开，否则用折叠状态：用户搜到名字却看不到结果会很困惑。
                            val expanded = searchQuery.isNotBlank() || provider.id !in collapsedProviderIds
                            item(key = "header_${provider.id}") {
                                CollapsibleGroupHeader(
                                    text = "${provider.name} (${filteredModels.size})",
                                    expanded = expanded,
                                    onToggle = {
                                        val updated = if (provider.id in collapsedProviderIds) {
                                            collapsedProviderIds - provider.id
                                        } else {
                                            collapsedProviderIds + provider.id
                                        }
                                        collapsedProviderIds = updated
                                        collapseStore.save(updated)
                                    }
                                )
                            }
                            if (expanded) {
                                item(key = "card_${provider.id}") {
                                    SettingsGroup {
                                        filteredModels.forEachIndexed { index, model ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            val testKey = modelMetadataKey(provider.id, model)
                                            ModelSelectionRow(
                                                model = model,
                                                selected = provider.id == currentProviderId && model == currentModel,
                                                metadata = modelMetadata[testKey],
                                                onClick = { onSelect(provider.id, model) },
                                                onTest = onTestModel?.let { test -> { test(provider, model) } },
                                                testing = testKey in testingModels,
                                                result = if (onTestModel != null) testResults[testKey] else null
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个模型行。传 [onTest]（主页场景）时行尾显示「测试」按钮 / 测试中转圈与结果行，选中态只用名称颜色表达；
 * 未传时保持原样：选中行尾显示小勾。
 */
@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit,
    onTest: (() -> Unit)? = null,
    testing: Boolean = false,
    result: ModelTestResult? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
            result?.let {
                ModelTestResultLine(model = model, result = it, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (onTest != null) {
            // 弹层行高较紧凑，关掉 48dp 最小触摸尺寸约束，让按钮贴合行高。
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                if (testing) {
                    Box(
                        modifier = Modifier.width(56.dp).height(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                } else {
                    TextButton(
                        onClick = onTest,
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp),
                        modifier = Modifier.width(56.dp).height(32.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.provider_test),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        } else if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
