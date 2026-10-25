package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.DisableFieldKind
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningCapability.DisableKind
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.model.ReasoningWire

/** 请求实际走的协议面：同一样模型在不同端点上的思考行为可能不同（见各分支注释）。 */
enum class ReasoningProtocol {
    /** OpenAI 兼容 `chat/completions`：关闭思考不可靠，`effort="none"` 常被降成 low。 */
    OPENAI_CHAT,

    /** OpenAI Responses：`reasoning.effort` 的 `none` 才能真把思考归零。 */
    OPENAI_RESPONSES,

    /** Anthropic Messages：adaptive 代际用 `output_config.effort`，旧代际用 `thinking.budget_tokens`。 */
    ANTHROPIC,

    /** Gemini generateContent / interactions：3 系用 thinkingLevel，2.5 系用 thinkingBudget。 */
    GEMINI
}

/**
 * 思考强度解析器：把「用户选的档位」+「模型能力」+「协议」翻译成可以直接写进请求体的 [ReasoningWire]。
 *
 * 规则（取代此前各适配器各自的硬编码 clamp）：
 * 1. 档位在能力集合内 → 直传；
 * 2. 档位高于最高档 → 向下取最近档（`CLAMPED_DOWN`）；
 * 3. 档位低于最低档 → 向上取最近档（`CLAMPED_UP`）；
 * 4. 请求关闭但模型思考恒开 → 取最低档（`CANNOT_DISABLE`）；
 * 5. 能力未知 → 用协议保守集合，标记 `INFERRED`，由 UI 提示「可能不生效」。
 */
object ReasoningResolver {

    /** 合并 models.dev 目录与厂商兜底表：档位以目录为准，关闭语义以兜底表为准。 */
    fun capability(
        protocol: ReasoningProtocol,
        model: String,
        declared: ReasoningCapability? = null
    ): ReasoningCapability {
        val fromTable = ReasoningCapabilityTables.forProtocol(protocol, model)
        val base = declared?.takeIf { it.isKnown } ?: return fromTable
        val levels = base.levels.takeIf { it.isNotEmpty() } ?: fromTable.levels
        val canDisable = base.canDisable || fromTable.canDisable
        return when (protocol) {
            // Anthropic / Gemini 的「能否关闭」需要代际知识（models.dev 的 toggle 标注不全），
            // 档位集合则用目录值（目录与实测一致）。
            ReasoningProtocol.ANTHROPIC, ReasoningProtocol.GEMINI -> base.copy(
                levels = levels,
                canDisable = canDisable,
                disableKind = fromTable.disableKind,
                effortWhileDisabled = fromTable.effortWhileDisabled,
                budgetMin = base.budgetMin ?: fromTable.budgetMin,
                budgetMax = base.budgetMax ?: fromTable.budgetMax,
                defaultLevel = base.defaultLevel ?: fromTable.defaultLevel
            )
            else -> base.copy(
                levels = levels,
                canDisable = canDisable,
                disableKind = when {
                    !canDisable -> DisableKind.UNSUPPORTED
                    // 厂商私有开关优先；没有方言命中时退回 OpenAI 官方的 effort="none"。
                    fromTable.disableKind == DisableKind.TOGGLE_FIELD -> DisableKind.TOGGLE_FIELD
                    else -> DisableKind.EFFORT_NONE
                },
                defaultLevel = base.defaultLevel ?: fromTable.defaultLevel
            )
        }
    }

    /**
     * @param effortApiValue 会话里保存的档位（[ReasoningEffort.apiValue]）；null / "default" 表示不干预。
     */
    fun plan(
        protocol: ReasoningProtocol,
        model: String,
        effortApiValue: String?,
        declared: ReasoningCapability? = null
    ): ReasoningPlan {
        if (effortApiValue == null) return ReasoningPlan(ReasoningWire.Omit, reason = ReasoningPlan.Reason.FOLLOW_DEFAULT)
        val requested = ReasoningEffort.fromApiValue(effortApiValue)
            ?: return ReasoningPlan(ReasoningWire.Omit, reason = ReasoningPlan.Reason.FOLLOW_DEFAULT)
        val cap = capability(protocol, model, declared)
        val inferred = !cap.isKnown

        if (requested == ReasoningEffort.NONE) return disablePlan(protocol, model, cap)

        // 开关型模型（只有开/关、没有中间档，如 Kimi K2.5）：用户要档位时不能自作主张关掉思考，
        // 直接不发该字段，交给服务端默认。
        if (cap.levels.isEmpty()) {
            return ReasoningPlan(
                ReasoningWire.Omit,
                reason = if (inferred) ReasoningPlan.Reason.INFERRED else ReasoningPlan.Reason.UNSUPPORTED
            )
        }

        val exact = cap.levels.firstOrNull { it == requested }
        if (exact != null) {
            return levelPlan(
                protocol, cap, exact,
                if (inferred) ReasoningPlan.Reason.INFERRED else ReasoningPlan.Reason.NONE
            )
        }
        val lower = cap.levels.filter { it.ordinal < requested.ordinal }.maxByOrNull { it.ordinal }
        val picked = lower ?: cap.levels.minByOrNull { it.ordinal }!!
        return levelPlan(
            protocol, cap, picked,
            if (lower != null) ReasoningPlan.Reason.CLAMPED_DOWN else ReasoningPlan.Reason.CLAMPED_UP
        )
    }

    // region 内部

    private fun levelPlan(
        protocol: ReasoningProtocol,
        cap: ReasoningCapability,
        level: ReasoningEffort,
        reason: ReasoningPlan.Reason
    ): ReasoningPlan {
        val budgetModel = cap.budgetMin != null
        return when {
            protocol == ReasoningProtocol.ANTHROPIC && budgetModel ->
                ReasoningPlan(ReasoningWire.Budget(budgetForLevel(level, cap, ANTHROPIC_BUDGETS)), level, reason)
            protocol == ReasoningProtocol.GEMINI && budgetModel ->
                ReasoningPlan(ReasoningWire.Budget(budgetForLevel(level, cap, GEMINI_BUDGETS)), level, reason)
            else -> ReasoningPlan(ReasoningWire.Effort(level.apiValue), level, reason)
        }
    }

    private fun disablePlan(
        protocol: ReasoningProtocol,
        model: String,
        cap: ReasoningCapability
    ): ReasoningPlan {
        if (!cap.canDisable) {
            return lowestFallback(protocol, cap, ReasoningPlan.Reason.CANNOT_DISABLE)
        }
        return when (cap.disableKind) {
            DisableKind.BETWEEN_TOOLS -> ReasoningPlan(ReasoningWire.BetweenTools)
            DisableKind.BUDGET_ZERO -> ReasoningPlan(ReasoningWire.Budget(0))
            DisableKind.TOGGLE_FIELD -> ReasoningPlan(
                ReasoningWire.DisableToggle(dialectField(model)),
                extraEffort = cap.effortWhileDisabled?.apiValue
            )
            DisableKind.EFFORT_NONE -> when (protocol) {
                ReasoningProtocol.OPENAI_RESPONSES -> ReasoningPlan(ReasoningWire.DisableEffortNone)
                ReasoningProtocol.OPENAI_CHAT -> ReasoningPlan(
                    ReasoningWire.Effort(ReasoningEffort.NONE.apiValue),
                    reason = ReasoningPlan.Reason.ENDPOINT_MAY_IGNORE
                )
                else -> lowestFallback(protocol, cap, ReasoningPlan.Reason.CANNOT_DISABLE)
            }
            DisableKind.UNSUPPORTED -> lowestFallback(protocol, cap, ReasoningPlan.Reason.CANNOT_DISABLE)
        }
    }

    /** 想关闭却关不掉时退回最低档；连档位都没有就干脆不发。 */
    private fun lowestFallback(
        protocol: ReasoningProtocol,
        cap: ReasoningCapability,
        reason: ReasoningPlan.Reason
    ): ReasoningPlan {
        val lowest = cap.levels.minByOrNull { it.ordinal }
            ?: return ReasoningPlan(ReasoningWire.Omit, reason = reason)
        return levelPlan(protocol, cap, lowest, reason)
    }

    /** 各厂商的关闭字段不通用：Qwen 只认 enable_thinking，其余走 thinking.type。 */
    private fun dialectField(model: String): DisableFieldKind {
        val id = model.lowercase().substringAfterLast('/')
        return if (id.startsWith("qwen") || id.contains("dashscope")) {
            DisableFieldKind.ENABLE_THINKING
        } else {
            DisableFieldKind.THINKING_TYPE
        }
    }

    private fun budgetForLevel(
        level: ReasoningEffort,
        cap: ReasoningCapability,
        table: Map<ReasoningEffort, Int>
    ): Int {
        val raw = table[level] ?: table.getValue(ReasoningEffort.MEDIUM)
        val min = cap.budgetMin ?: return raw
        val max = cap.budgetMax ?: Int.MAX_VALUE
        return raw.coerceIn(min, max)
    }

    /** Anthropic 旧代际（≤4.5）的 budget_tokens 换算，须小于 max_tokens。 */
    private val ANTHROPIC_BUDGETS = mapOf(
        ReasoningEffort.MINIMAL to 1024,
        ReasoningEffort.LOW to 1024,
        ReasoningEffort.MEDIUM to 4096,
        ReasoningEffort.HIGH to 8192,
        ReasoningEffort.XHIGH to 12288,
        ReasoningEffort.MAX to 16384
    )

    /** Gemini 2.5 系的 thinkingBudget 换算。 */
    private val GEMINI_BUDGETS = mapOf(
        ReasoningEffort.NONE to 0,
        ReasoningEffort.MINIMAL to 512,
        ReasoningEffort.LOW to 1024,
        ReasoningEffort.MEDIUM to 4096,
        ReasoningEffort.HIGH to 8192,
        ReasoningEffort.XHIGH to 12288,
        ReasoningEffort.MAX to 16384
    )

    // endregion
}
