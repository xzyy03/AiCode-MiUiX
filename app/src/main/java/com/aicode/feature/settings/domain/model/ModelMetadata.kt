package com.aicode.feature.settings.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    /**
     * models.dev `temperature`：该模型是否接受自定义 temperature。
     * false（含元数据缺失）表示服务端把采样温度固定住了，请求里必须不带该字段——
     * kimi-k3 / gpt-5 系等模型带了会直接 400。
     */
    val supportsCustomTemperature: Boolean = false,
    val modelType: ModelType = ModelType.CHAT,
    val supportsImageOutput: Boolean = false,
    val source: Source = Source.INFERRED,
    /** models.dev 思考档位（reasoning_options 中 effort 类型的 values，如 ["low","medium","high"]）；null/空 = 无档位。 */
    val reasoningLevels: List<String>? = null,
    /** models.dev reasoning_options 是否声明思考可开关（`toggle` 类型）。 */
    val reasoningCanDisable: Boolean = false,
    /** 预算型模型的思考预算下界（`budget_tokens.min`）；null 表示该模型不是预算型。 */
    val reasoningBudgetMin: Int? = null,
    /** 预算型模型的思考预算上界（`budget_tokens.max`）。 */
    val reasoningBudgetMax: Int? = null,
    /** 服务端默认思考档位；未知为 null（不参与请求，仅用于 UI 标注）。 */
    val reasoningDefaultLevel: String? = null,
    /** models.dev cost：输入单价（USD/1M tokens）。 */
    val inputCostUsdPerM: Double? = null,
    /** models.dev cost：输出单价（USD/1M tokens）。 */
    val outputCostUsdPerM: Double? = null,
    /** models.dev cost：缓存读取单价（USD/1M tokens）。 */
    val cacheReadCostUsdPerM: Double? = null,
    /** models.dev cost：缓存写入单价（USD/1M tokens），通常高于普通输入价。 */
    val cacheWriteCostUsdPerM: Double? = null
) {
    /** 兼容旧调用点：可选档位的 apiValue 列表（来自 [reasoningLevels]）。 */
    val reasoningEffortOptions: List<String>?
        get() = reasoningLevels?.takeIf { it.isNotEmpty() }

    enum class ModelType { CHAT, EMBEDDING }

    enum class Source {
        MODELS_DEV,
        INFERRED
    }
}

/**
 * 模型元数据缓存的键。
 * 元数据不能只按模型名索引：自定义单价按「提供商ID:模型名」存储，不同类型渠道对同一模型名的
 * 自动匹配结果也不同，只按模型名会串台（拿别的渠道的单价/能力）。
 */
fun modelMetadataKey(providerId: String, model: String): String = "$providerId:$model"

/**
 * 合并自定义元数据与自动解析（拉取/内置）元数据，自定义优先；窗口未填时保留自动值。
 * [base] 为空时构造兜底元数据（窗口 0，能力全 false）。
 */
fun mergeModelMetadata(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata {
    val a = base?.copy(id = model) ?: ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 0,
        inputTokens = null,
        outputTokens = null
    )
    val c = custom ?: return a
    return a.copy(
        modelType = c.modelType,
        supportsVision = c.supportsVision,
        supportsImageOutput = c.supportsImageOutput,
        supportsTools = c.supportsTools,
        supportsReasoning = c.supportsReasoning,
        contextTokens = c.contextTokens.takeIf { it > 0 } ?: a.contextTokens,
        inputTokens = c.inputTokens ?: a.inputTokens,
        outputTokens = c.outputTokens ?: a.outputTokens,
        inputCostUsdPerM = c.inputCostUsdPerM ?: a.inputCostUsdPerM,
        outputCostUsdPerM = c.outputCostUsdPerM ?: a.outputCostUsdPerM,
        cacheReadCostUsdPerM = c.cacheReadCostUsdPerM ?: a.cacheReadCostUsdPerM,
        cacheWriteCostUsdPerM = c.cacheWriteCostUsdPerM ?: a.cacheWriteCostUsdPerM,
        // 思考档位：自定义模型显式填了就以自定义为准（中转站改名后可自行纠正），未填沿用自动识别。
        reasoningLevels = c.reasoningLevels?.takeIf { it.isNotEmpty() } ?: a.reasoningLevels,
        reasoningCanDisable = c.reasoningCanDisable || a.reasoningCanDisable,
        reasoningBudgetMin = c.reasoningBudgetMin ?: a.reasoningBudgetMin,
        reasoningBudgetMax = c.reasoningBudgetMax ?: a.reasoningBudgetMax,
        reasoningDefaultLevel = c.reasoningDefaultLevel ?: a.reasoningDefaultLevel
    )
}

