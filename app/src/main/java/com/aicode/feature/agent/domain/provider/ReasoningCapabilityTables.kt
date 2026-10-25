package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningCapability.DisableKind
import com.aicode.feature.agent.domain.model.ReasoningCapability.Source
import com.aicode.feature.agent.domain.model.ReasoningEffort

/**
 * 厂商能力兜底表：models.dev 目录未命中（中转站改名、自定义模型、快照滞后）时按模型名推断思考能力。
 *
 * 优先级低于 models.dev：调用方拿到目录数据时只用本表的「能否关闭 / 关闭方式」，
 * 档位集合仍以目录为准。表中的档位与开关结论来自官方文档 + 真实渠道实测验：
 * - Anthropic：effort 档位随代际增长（4.6 无 xhigh），Opus 5.5 / Fable 5.1 思考恒开，
 *   Sonnet 5.5 关闭语义为 between_tools，Opus 5 关闭时 effort 需 ≤ high；
 * - OpenAI：5.1 起才能关，5.2 起有 xhigh，5.6 起有 max，GPT-6 Astra / 6.1 Sol 不能关；
 * - Gemini：3 系不能完全关闭，Pro 无 minimal，2.5 系只有 Flash / Flash-Lite 能用预算 0 关闭；
 * - 兼容厂商：DeepSeek / Kimi / GLM 用 `thinking.type`，Qwen 只认 `enable_thinking`。
 */
internal object ReasoningCapabilityTables {

    private val ANTHROPIC_MODEL = Regex("""claude-(?:opus|sonnet|haiku|fable|mythos)-(\d+)(?:[-.](\d+))?""")

    /** 老命名（`claude-3-5-sonnet-20241022`）：版本号在族名之前。 */
    private val ANTHROPIC_LEGACY_MODEL = Regex("""claude-(\d+)(?:[-.](\d+))?-(?:opus|sonnet|haiku)""")

    /** 按协议 + 模型名推断能力；完全无法推断时回退 [fallbackForProtocol]。 */
    fun forProtocol(protocol: ReasoningProtocol, model: String): ReasoningCapability = when (protocol) {
        ReasoningProtocol.ANTHROPIC -> forAnthropic(model)
        ReasoningProtocol.GEMINI -> forGemini(model)
        ReasoningProtocol.OPENAI_CHAT, ReasoningProtocol.OPENAI_RESPONSES -> forOpenAiCompatible(model)
    }

    // region OpenAI 兼容

    private fun forOpenAiCompatible(model: String): ReasoningCapability {
        val id = model.lowercase().substringAfterLast('/')
        openAiOfficial(id)?.let { return it }
        vendor(id)?.let { return it }
        return fallbackForProtocol(ReasoningProtocol.OPENAI_CHAT)
    }

    /** OpenAI 官方模型（按代际判定档位与能否关闭）。 */
    private fun openAiOfficial(id: String): ReasoningCapability? {
        fun cap(
            levels: List<ReasoningEffort>,
            canDisable: Boolean = false,
            default: ReasoningEffort? = null
        ): ReasoningCapability {
            // models.dev 会把这些写成 effort values 里的 none，统一摘出来转成「可关闭」标记。
            val disable = canDisable || levels.contains(ReasoningEffort.NONE)
            return ReasoningCapability(
                levels = levels.filter { it != ReasoningEffort.NONE },
                canDisable = disable,
                disableKind = if (disable) DisableKind.EFFORT_NONE else DisableKind.UNSUPPORTED,
                defaultLevel = default,
                source = Source.VENDOR_TABLE
            )
        }

        val core4 = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)
        val core6 = core4 + ReasoningEffort.MAX
        return when {
            // GPT-6 系：Astra / Sol 不支持 none（官方 400），其余支持。
            id.startsWith("gpt-6.1-sol") || id.startsWith("gpt-6-astra") -> cap(core6, canDisable = false)
            id.startsWith("gpt-6") -> cap(listOf(ReasoningEffort.NONE) + core6, canDisable = true, default = ReasoningEffort.MEDIUM)
            id.startsWith("gpt-5.6") -> cap(listOf(ReasoningEffort.NONE) + core6, canDisable = true, default = ReasoningEffort.MEDIUM)
            id.startsWith("gpt-5.5-pro") || id.startsWith("gpt-5.4-pro") || id.startsWith("gpt-5.2-pro") ->
                cap(listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH))
            id.startsWith("gpt-5-pro") -> cap(listOf(ReasoningEffort.HIGH))
            id.startsWith("gpt-5.5") || id.startsWith("gpt-5.4") || id.startsWith("gpt-5.3") || id.startsWith("gpt-5.2") ->
                cap(listOf(ReasoningEffort.NONE) + core4, canDisable = true, default = ReasoningEffort.MEDIUM)
            id.startsWith("gpt-5.1") -> cap(
                listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                canDisable = true,
                default = ReasoningEffort.NONE
            )
            id.startsWith("gpt-5") -> cap(
                listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                default = ReasoningEffort.MEDIUM
            )
            id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ->
                cap(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))
            else -> null
        }
    }

    /** 走 OpenAI 兼容协议的第三方厂商（关闭字段各不相同）。 */
    private fun vendor(id: String): ReasoningCapability? {
        fun cap(
            levels: List<ReasoningEffort>,
            canDisable: Boolean,
            default: ReasoningEffort? = null
        ): ReasoningCapability {
            val disable = canDisable || levels.contains(ReasoningEffort.NONE)
            return ReasoningCapability(
                levels = levels.filter { it != ReasoningEffort.NONE },
                canDisable = disable,
                disableKind = if (disable) DisableKind.TOGGLE_FIELD else DisableKind.UNSUPPORTED,
                defaultLevel = default,
                source = Source.VENDOR_TABLE
            )
        }

        val gradedThree = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)
        return when {
            // DeepSeek：档位低/高/最高 + 私有开关（实测 effort="none" 无效，只有开关能关）。
            id.startsWith("deepseek") -> cap(gradedThree, canDisable = true)
            // Kimi：K3 为档位型（实测开关字段同样生效），K2.5/2.6 只有开关没有中间档。
            id.startsWith("kimi-k3") -> cap(gradedThree, canDisable = true)
            id.startsWith("kimi") || id.startsWith("moonshot") -> cap(emptyList(), canDisable = true)
            // GLM：5.2 只有高/最高，5.3 为低/高/最高；开关字段实测有效。
            id.contains("glm-5.2") -> cap(listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX), canDisable = true)
            id.contains("glm") -> cap(gradedThree, canDisable = true)
            // Qwen：档位低/中/极高，关闭只认 enable_thinking。
            id.startsWith("qwen") -> cap(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH),
                canDisable = true
            )
            id.startsWith("minimax") -> cap(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX),
                canDisable = id.contains("flash").not()
            )
            // Grok：档位型；实测部分渠道忽略关闭与档位，故不承诺关闭。
            id.startsWith("grok") -> cap(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH),
                canDisable = false
            )
            else -> null
        }
    }

    // endregion

    // region Anthropic

    private fun forAnthropic(model: String): ReasoningCapability {
        val id = model.lowercase().substringAfterLast('/')
        // 两种命名：claude-<family>-<major>[-<minor>]（4.6+）与 claude-<major>[-<minor>]-<family>（3.x 老命名）。
        val match = ANTHROPIC_MODEL.find(id) ?: ANTHROPIC_LEGACY_MODEL.find(id)
            ?: return fallbackForProtocol(ReasoningProtocol.ANTHROPIC)
        val major = match.groupValues[1].toIntOrNull() ?: return fallbackForProtocol(ReasoningProtocol.ANTHROPIC)
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val family = listOf("opus", "sonnet", "haiku", "fable", "mythos")
            .firstOrNull { id.contains(it) } ?: "sonnet"

        // 4.5 及更早：只有 thinking.budget_tokens，没有 effort 档位。
        if (major < 5 && !(major == 4 && minor >= 6)) {
            return ReasoningCapability(
                levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                canDisable = true,
                disableKind = DisableKind.TOGGLE_FIELD,
                budgetMin = 1024,
                budgetMax = 8192,
                defaultLevel = ReasoningEffort.MEDIUM,
                source = Source.VENDOR_TABLE
            )
        }

        val levels = if (major == 4) {
            // 4.6 是 adaptive 首代，官方 effort 只有 low/medium/high/max。
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX)
        } else {
            listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH, ReasoningEffort.MAX
            )
        }

        // 关闭能力按代际细分：
        // - Opus 5.5 / Fable 5.1：思考恒开，关闭请求不报错但不生效；
        // - Sonnet 5.5：关闭语义是 between_tools（仅低/中/高可用）；
        // - Opus 5：可关，但关闭时 effort 不能超过 high，否则 400；
        // - Haiku 5.5：可关，同样受 high 上限约束。
        val (canDisable, kind, effortWhileDisabled) = when {
            major >= 5 && minor >= 5 && (family == "opus" || family == "fable") ->
                Triple(false, DisableKind.UNSUPPORTED, null)
            major >= 5 && minor >= 5 && family == "sonnet" -> Triple(true, DisableKind.BETWEEN_TOOLS, null)
            major >= 5 && family == "fable" -> Triple(false, DisableKind.UNSUPPORTED, null)
            major >= 5 && family == "opus" -> Triple(true, DisableKind.TOGGLE_FIELD, ReasoningEffort.HIGH)
            major >= 5 && family == "haiku" -> Triple(true, DisableKind.TOGGLE_FIELD, ReasoningEffort.HIGH)
            else -> Triple(true, DisableKind.TOGGLE_FIELD, null)
        }

        return ReasoningCapability(
            levels = levels,
            canDisable = canDisable,
            disableKind = kind,
            effortWhileDisabled = effortWhileDisabled,
            defaultLevel = ReasoningEffort.HIGH,
            source = Source.VENDOR_TABLE
        )
    }

    // endregion

    // region Gemini

    private fun forGemini(model: String): ReasoningCapability {
        val id = model.lowercase().substringAfterLast('/')
        if (!id.contains("gemini") && !id.contains("gemma")) {
            return fallbackForProtocol(ReasoningProtocol.GEMINI)
        }

        // 1.x / 2.0 没有思考控制（2.0 Flash 的思考不可调）。
        if (id.contains("gemini-1") || id.contains("gemini-2.0")) {
            return ReasoningCapability(source = Source.UNKNOWN)
        }

        // 2.5 及更早：thinkingBudget 预算型。只有 Flash / Flash-Lite 能用 0 关闭。
        if (id.contains("gemini-2.5")) {
            val isPro = id.contains("pro")
            val (min, max) = when {
                isPro -> 128 to 32768
                id.contains("flash-lite") -> 512 to 24576
                else -> 1 to 24576
            }
            return ReasoningCapability(
                levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                canDisable = !isPro,
                disableKind = if (isPro) DisableKind.UNSUPPORTED else DisableKind.BUDGET_ZERO,
                budgetMin = min,
                budgetMax = max,
                defaultLevel = ReasoningEffort.MEDIUM,
                source = Source.VENDOR_TABLE
            )
        }

        // Gemini 3 / 4：thinkingLevel 档位型，全部不能完全关闭。
        val isFlash = id.contains("flash")
        val isImage = id.contains("image")
        val noMinimal = id.contains("3.7-flash") || id.contains("3.8-flash")
        val levels = when {
            isImage -> listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH)
            !isFlash -> listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
            noMinimal -> listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
            else -> listOf(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH
            )
        }
        if (id.contains("gemma")) {
            // Gemma 无 thinkingLevel 档位，只有内置思考。
            return ReasoningCapability(source = Source.UNKNOWN)
        }
        return ReasoningCapability(
            levels = levels,
            canDisable = false,
            disableKind = DisableKind.UNSUPPORTED,
            defaultLevel = if (isFlash) ReasoningEffort.MEDIUM else ReasoningEffort.HIGH,
            source = Source.VENDOR_TABLE
        )
    }

    // endregion

    /** 协议级保守兜底：能力完全未知时也不发可能 400 的档位。 */
    fun fallbackForProtocol(protocol: ReasoningProtocol): ReasoningCapability = when (protocol) {
        ReasoningProtocol.OPENAI_CHAT, ReasoningProtocol.OPENAI_RESPONSES -> ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = false,
            disableKind = DisableKind.UNSUPPORTED,
            defaultLevel = ReasoningEffort.MEDIUM,
            source = Source.UNKNOWN
        )
        ReasoningProtocol.ANTHROPIC -> ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = true,
            disableKind = DisableKind.TOGGLE_FIELD,
            defaultLevel = ReasoningEffort.HIGH,
            source = Source.UNKNOWN
        )
        ReasoningProtocol.GEMINI -> ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = false,
            disableKind = DisableKind.UNSUPPORTED,
            defaultLevel = ReasoningEffort.MEDIUM,
            source = Source.UNKNOWN
        )
    }
}
