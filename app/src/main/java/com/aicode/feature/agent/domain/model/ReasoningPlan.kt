package com.aicode.feature.agent.domain.model

/**
 * 一次请求最终要发出去的思考控制意图。适配器只负责把 [ReasoningWire] 翻译成自家字段，
 * 「档位是否支持 / 能不能关闭 / 该降级到哪一档」全部由 ReasoningResolver 事先算好。
 */
sealed interface ReasoningWire {
    /** 档位直传：`reasoning_effort` / `output_config.effort` / `thinkingLevel`。 */
    data class Effort(val value: String) : ReasoningWire

    /** 预算直传：`thinkingBudget` / `thinking.budget_tokens`。 */
    data class Budget(val tokens: Int) : ReasoningWire

    /** 用厂商私有开关关闭思考（`thinking.type = disabled` / `enable_thinking = false`）。 */
    data class DisableToggle(val field: DisableFieldKind) : ReasoningWire

    /** 用 `effort = "none"` 关闭思考（OpenAI Responses 端点）。 */
    data object DisableEffortNone : ReasoningWire

    /** Claude Sonnet 5.5 的 between_tools：把思考压到工具调用之间的最低强度。 */
    data object BetweenTools : ReasoningWire

    /** 不发任何思考相关字段，完全交给服务端默认。 */
    data object Omit : ReasoningWire
}

/** 厂商私有开关字段的具体形态。 */
enum class DisableFieldKind {
    /** `thinking: {"type": "disabled"}`（DeepSeek / Kimi / GLM / MiniMax / Anthropic）。 */
    THINKING_TYPE,

    /** `enable_thinking: false`（Qwen / DashScope）。 */
    ENABLE_THINKING
}

/**
 * 解析结果。适配器只看 [wire]，UI 只看 [effectiveLevel] 与 [reason]（提示「实际生效：高」等）。
 *
 * @param effectiveLevel 实际生效的档位；关闭或完全不发字段时为 null。
 * @param reason 降级原因，供 UI 明示与日志排查。
 * @param extraEffort 关闭思考时仍需附加的档位（Anthropic adaptive 关闭时也要带 effort）。
 */
data class ReasoningPlan(
    val wire: ReasoningWire,
    val effectiveLevel: ReasoningEffort? = null,
    val reason: Reason = Reason.NONE,
    val extraEffort: String? = null
) {
    /** 本次请求是否表达了「关闭思考」。注意：只代表发得出去，不代表服务端一定照做。 */
    val isDisableRequested: Boolean
        get() = wire is ReasoningWire.DisableToggle ||
            wire == ReasoningWire.DisableEffortNone ||
            wire == ReasoningWire.BetweenTools

    enum class Reason {
        /** 直传，无降级。 */
        NONE,

        /** 请求档位高于模型最高档，已向下取最近档。 */
        CLAMPED_DOWN,

        /** 请求档位低于模型最低档，已向上取最近档。 */
        CLAMPED_UP,

        /** 请求关闭但模型思考恒开，已降级为最低档。 */
        CANNOT_DISABLE,

        /** 模型完全没有可调的思考参数。 */
        UNSUPPORTED,

        /** 能力未知，按协议保守集合发送。 */
        INFERRED,

        /** 关闭请求已发出，但当前端点可能忽略（chat/completions 常把 none 当 low）。 */
        ENDPOINT_MAY_IGNORE,

        /** 不干预：用户选择跟随服务端默认。 */
        FOLLOW_DEFAULT
    }
}
