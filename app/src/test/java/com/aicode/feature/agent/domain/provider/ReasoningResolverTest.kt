package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.DisableFieldKind
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.model.ReasoningWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度解析的回归测试。表中每个期望值都对应一次真实渠道实测（见文档
 * `docs-site/docs/advanced/reasoning-modes.md`）：不再出现 max 被压成 high、none 被静默吞掉。
 */
class ReasoningResolverTest {

    private fun plan(
        protocol: ReasoningProtocol,
        model: String,
        effort: String?,
        declared: ReasoningCapability? = null
    ) = ReasoningResolver.plan(protocol, model, effort, declared)

    private fun effortOf(plan: ReasoningPlan) = (plan.wire as? ReasoningWire.Effort)?.value
    private fun budgetOf(plan: ReasoningPlan) = (plan.wire as? ReasoningWire.Budget)?.tokens

    // region OpenAI 兼容：档位不再被无脑压成 high

    @Test
    fun gpt56_passesMaxThrough() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.6-sol", "max")
        assertEquals("max", effortOf(p))
        assertEquals(ReasoningPlan.Reason.NONE, p.reason)
    }

    @Test
    fun gpt56_passesXhighThrough() {
        assertEquals("xhigh", effortOf(plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.6-sol", "xhigh")))
    }

    @Test
    fun gpt5_keepsMinimalInsteadOfDroppingField() {
        assertEquals("minimal", effortOf(plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5", "minimal")))
    }

    @Test
    fun gpt51_noneOnResponsesUsesDisableEffortNone() {
        val p = plan(ReasoningProtocol.OPENAI_RESPONSES, "gpt-5.1", "none")
        assertEquals(ReasoningWire.DisableEffortNone, p.wire)
    }

    @Test
    fun gpt51_noneOnChatStillSendsNoneButFlagsEndpoint() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.1", "none")
        assertEquals("none", effortOf(p))
        assertEquals(ReasoningPlan.Reason.ENDPOINT_MAY_IGNORE, p.reason)
    }

    @Test
    fun gpt6Astra_cannotDisable_fallsBackToLowest() {
        val p = plan(ReasoningProtocol.OPENAI_RESPONSES, "gpt-6-astra", "none")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CANNOT_DISABLE, p.reason)
    }

    @Test
    fun gpt55_aboveHighestClampsDown() {
        // 5.5 最高只到 xhigh。
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.5", "max")
        assertEquals("xhigh", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_DOWN, p.reason)
    }

    @Test
    fun oSeries_rejectsMinimalByClampingUp() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "o3", "minimal")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_UP, p.reason)
    }

    @Test
    fun unknownModel_usesConservativeSetAndMarksInferred() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "some-relay-model", "high")
        assertEquals("high", effortOf(p))
        assertEquals(ReasoningPlan.Reason.INFERRED, p.reason)
    }

    // endregion

    // region 厂商私有开关：关闭只认各自的字段

    @Test
    fun deepseek_noneUsesThinkingTypeToggle() {
        // 实测：DeepSeek 只有 thinking.type=disabled 有效，effort="none" 仍会思考。
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "deepseek-v4-pro", "none")
        assertEquals(ReasoningWire.DisableToggle(DisableFieldKind.THINKING_TYPE), p.wire)
        assertTrue(p.isDisableRequested)
    }

    @Test
    fun qwen_noneUsesEnableThinkingToggle() {
        // 实测：Qwen 只认 enable_thinking=false，thinking.type 无效。
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "qwen/qwen3.8-max", "none")
        assertEquals(ReasoningWire.DisableToggle(DisableFieldKind.ENABLE_THINKING), p.wire)
    }

    @Test
    fun kimiK3_noneUsesToggle() {
        assertEquals(
            ReasoningWire.DisableToggle(DisableFieldKind.THINKING_TYPE),
            plan(ReasoningProtocol.OPENAI_CHAT, "kimi-k3", "none").wire
        )
    }

    @Test
    fun glmMediumClampsDownToLow() {
        // GLM 5.3 档位只有 low/high/max：medium 向下取最近档 low。
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "z-ai/glm-5.3", "medium")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_DOWN, p.reason)
    }

    @Test
    fun grok_cannotDisable() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "grok-4.7", "none")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CANNOT_DISABLE, p.reason)
    }

    // endregion

    // region Anthropic

    @Test
    fun anthropic47_passesMaxThrough() {
        assertEquals("max", effortOf(plan(ReasoningProtocol.ANTHROPIC, "claude-opus-4-7", "max")))
    }

    @Test
    fun anthropic46_clampsXhighToHigh() {
        // 4.6 官方 effort 只有 low/medium/high/max，没有 xhigh：向下取最近档。
        val p = plan(ReasoningProtocol.ANTHROPIC, "anthropic/claude-sonnet-4-6", "xhigh")
        assertEquals("high", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_DOWN, p.reason)
    }

    @Test
    fun anthropicLegacy_usesBudgetTokens() {
        val p = plan(ReasoningProtocol.ANTHROPIC, "claude-sonnet-4-5", "medium")
        assertEquals(4096, budgetOf(p))
    }

    @Test
    fun anthropicLegacyOldNaming_usesBudgetTokens() {
        assertEquals(1024, budgetOf(plan(ReasoningProtocol.ANTHROPIC, "claude-3-5-sonnet-20241022", "low")))
    }

    @Test
    fun anthropicOpus55_cannotDisable() {
        // 实测：Opus 5.5 收到 disabled 仍返回 thinking 块（静默忽略），故直接降为最低档。
        val p = plan(ReasoningProtocol.ANTHROPIC, "anthropic/claude-opus-5.5", "none")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CANNOT_DISABLE, p.reason)
    }

    @Test
    fun anthropicSonnet55_disableUsesBetweenTools() {
        val p = plan(ReasoningProtocol.ANTHROPIC, "anthropic/claude-sonnet-5.5", "none")
        assertEquals(ReasoningWire.BetweenTools, p.wire)
    }

    @Test
    fun anthropicOpus5_disableCarriesHighEffort() {
        val p = plan(ReasoningProtocol.ANTHROPIC, "claude-opus-5", "none")
        assertEquals(ReasoningWire.DisableToggle(DisableFieldKind.THINKING_TYPE), p.wire)
        assertEquals("high", p.extraEffort)
    }

    @Test
    fun anthropicOldNaming_usesLegacyBudget() {
        // 3.x 老命名（claude-sonnet-3-1）走预算型分支，档位换算成 token 预算。
        assertEquals(8192, budgetOf(plan(ReasoningProtocol.ANTHROPIC, "claude-sonnet-3-1", "high")))
    }

    // endregion

    // region Gemini

    @Test
    fun gemini3Pro_clampsMinimalUpToLow() {
        val p = plan(ReasoningProtocol.GEMINI, "gemini-3-pro", "minimal")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_UP, p.reason)
    }

    @Test
    fun gemini3Flash_keepsMinimal() {
        assertEquals("minimal", effortOf(plan(ReasoningProtocol.GEMINI, "gemini-3-flash-preview", "minimal")))
    }

    @Test
    fun gemini38Flash_dropsMinimal() {
        val p = plan(ReasoningProtocol.GEMINI, "gemini-3.8-flash-high", "minimal")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_UP, p.reason)
    }

    @Test
    fun gemini3Pro_cannotDisable() {
        val p = plan(ReasoningProtocol.GEMINI, "gemini-3-pro", "none")
        assertEquals("low", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CANNOT_DISABLE, p.reason)
    }

    @Test
    fun gemini25Flash_disableUsesBudgetZero() {
        val p = plan(ReasoningProtocol.GEMINI, "gemini-2.5-flash", "none")
        assertEquals(0, budgetOf(p))
    }

    @Test
    fun gemini25Pro_cannotDisable_fallsBackToBudget() {
        val p = plan(ReasoningProtocol.GEMINI, "gemini-2.5-pro", "none")
        assertEquals(1024, budgetOf(p))
        assertEquals(ReasoningPlan.Reason.CANNOT_DISABLE, p.reason)
    }

    @Test
    fun gemini25FlashLevelsMapToBudget() {
        assertEquals(8192, budgetOf(plan(ReasoningProtocol.GEMINI, "gemini-2.5-flash", "high")))
    }

    // endregion

    // region 无档位 / 跟随默认

    @Test
    fun nullEffort_omitsField() {
        val p = plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.6-sol", null)
        assertEquals(ReasoningWire.Omit, p.wire)
        assertEquals(ReasoningPlan.Reason.FOLLOW_DEFAULT, p.reason)
    }

    @Test
    fun defaultApiValue_omitsField() {
        assertEquals(ReasoningWire.Omit, plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.6-sol", "default").wire)
    }

    @Test
    fun unknownApiValue_omitsField() {
        assertEquals(ReasoningWire.Omit, plan(ReasoningProtocol.OPENAI_CHAT, "gpt-5.6-sol", "ultra").wire)
    }

    @Test
    fun gemma_hasNoLevels() {
        val cap = ReasoningResolver.capability(ReasoningProtocol.GEMINI, "gemma-4-31b-it")
        assertTrue(cap.levels.isEmpty())
        assertFalse(cap.canDisable)
    }

    // endregion

    // region models.dev 目录数据优先

    @Test
    fun declaredLevels_overrideVendorTable() {
        // 目录里 gemini-3.1-flash-image 只有 minimal/high：medium 向下取 minimal。
        val declared = ReasoningCapability(
            levels = listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH),
            canDisable = false,
            source = ReasoningCapability.Source.MODELS_DEV
        )
        val p = plan(ReasoningProtocol.GEMINI, "gemini-3.1-flash-image", "medium", declared)
        assertEquals("minimal", effortOf(p))
        assertEquals(ReasoningPlan.Reason.CLAMPED_DOWN, p.reason)
    }

    @Test
    fun declaredEffortNone_marksDisableOnEveryProtocol() {
        val declared = ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = true,
            source = ReasoningCapability.Source.MODELS_DEV
        )
        val p = plan(ReasoningProtocol.OPENAI_RESPONSES, "gpt-5.2", "none", declared)
        assertEquals(ReasoningWire.DisableEffortNone, p.wire)
    }

    @Test
    fun declaredBudgetRange_fillsMissingLevelsFromTable() {
        // 目录只给了预算范围（没有 effort 档位）：档位集合由厂商表补齐，预算边界以目录为准。
        val declared = ReasoningCapability(
            levels = emptyList(),
            canDisable = false,
            budgetMin = 128,
            budgetMax = 32768,
            source = ReasoningCapability.Source.MODELS_DEV
        )
        val cap = ReasoningResolver.capability(ReasoningProtocol.GEMINI, "gemini-2.5-pro", declared)
        assertEquals(128, cap.budgetMin)
        assertEquals(32768, cap.budgetMax)
        assertEquals(3, cap.levels.size)
        assertFalse(cap.canDisable)
    }

    // endregion
}
