package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从 400 文案里学习档位白名单：两种真实错误格式各有用例。 */
class ReasoningLearningTest {

    @After
    fun tearDown() = ReasoningLearning.clear()

    @Test
    fun parse_validLevelsFormat() {
        val message = """{"error":{"message":"level \"minimal\" not supported, valid levels: low, medium, high, xhigh, max"}}"""
        assertEquals(
            listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH, ReasoningEffort.MAX
            ),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_supportedValuesFormat() {
        val message = "Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. " +
            "Supported values are: 'low', 'medium', 'high', 'xhigh', 'max'"
        assertEquals(
            listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH, ReasoningEffort.MAX
            ),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_geminiStyleFormat() {
        assertEquals(
            listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH),
            ReasoningLearning.parseSupportedLevels("""level "low" not supported, valid levels: minimal, high""")
        )
    }

    @Test
    fun parse_unrelatedMessage_returnsNull() {
        assertNull(ReasoningLearning.parseSupportedLevels("401 invalid api key"))
        assertNull(ReasoningLearning.parseSupportedLevels(""))
        assertNull(ReasoningLearning.parseSupportedLevels(null))
    }

    @Test
    fun observe_narrowsLevelsAndKeepsDisableInfo() {
        val current = ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            canDisable = true,
            disableKind = ReasoningCapability.DisableKind.TOGGLE_FIELD,
            source = ReasoningCapability.Source.VENDOR_TABLE
        )
        ReasoningLearning.observe(
            "p1",
            "deepseek-v4-pro",
            IllegalStateException("""level "max" not supported, valid levels: low, high"""),
            current
        )

        val learned = ReasoningLearning.recall("p1", "deepseek-v4-pro")!!
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), learned.levels)
        // 错误文案里读不到「能否关闭」，沿用已知能力。
        assertTrue(learned.canDisable)
        assertEquals(ReasoningCapability.Source.LEARNED, learned.source)
    }

    @Test
    fun observe_isScopedPerProvider() {
        ReasoningLearning.observe(
            "p1", "gpt-5.6-sol",
            IllegalStateException("valid levels: low, high"), null
        )
        assertTrue(ReasoningLearning.recall("p1", "gpt-5.6-sol") != null)
        // 换渠道（providerId 不同）不能串台：同一模型在不同中转站白名单可能不同。
        assertNull(ReasoningLearning.recall("p2", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", "gpt-6-sol"))
    }

    @Test
    fun observe_effortNoneWithoutNoneInWhitelist_dropsDisable() {
        // EFFORT_NONE 的关闭与档位同值空间：白名单没有 none 时必须摘掉可关闭性，
        // 否则下次仍发 effort="none"，学习永远不生效、无限重复同一个 400。
        val current = ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = true,
            disableKind = ReasoningCapability.DisableKind.EFFORT_NONE,
            source = ReasoningCapability.Source.VENDOR_TABLE
        )
        ReasoningLearning.observe(
            "p1",
            "gpt-6-astra",
            IllegalStateException(
                "Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. " +
                    "Supported values are: 'low', 'medium', 'high'"
            ),
            current
        )

        val learned = ReasoningLearning.recall("p1", "gpt-6-astra")!!
        assertFalse(learned.canDisable)
        assertEquals(ReasoningCapability.DisableKind.UNSUPPORTED, learned.disableKind)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), learned.levels)
    }

    @Test
    fun observe_effortNoneWithNoneInWhitelist_keepsDisable() {
        val current = ReasoningCapability(
            levels = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            canDisable = true,
            disableKind = ReasoningCapability.DisableKind.EFFORT_NONE,
            source = ReasoningCapability.Source.VENDOR_TABLE
        )
        ReasoningLearning.observe(
            "p1", "gpt-5.6-sol",
            IllegalStateException("valid levels: none, low, high"), current
        )

        val learned = ReasoningLearning.recall("p1", "gpt-5.6-sol")!!
        assertTrue(learned.canDisable)
        assertEquals(ReasoningCapability.DisableKind.EFFORT_NONE, learned.disableKind)
        // none 不算档位，只保留在关闭能力里。
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), learned.levels)
    }

    @Test
    fun observe_unparsableError_learnsNothing() {
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("429 rate limited"), null)
        assertNull(ReasoningLearning.recall("p1", "gpt-5.6-sol"))
    }

    @Test
    fun observe_ignoresBlankKeys() {
        ReasoningLearning.observe("", "gpt-5.6-sol", IllegalStateException("valid levels: low"), null)
        ReasoningLearning.observe("p1", "", IllegalStateException("valid levels: low"), null)
        assertNull(ReasoningLearning.recall("", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", ""))
    }

    @Test
    fun clear_removesLearnedEntries() {
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low"), null)
        assertFalse(ReasoningLearning.recall("p1", "m") == null)
        ReasoningLearning.clear(providerId = "p1")
        assertNull(ReasoningLearning.recall("p1", "m"))
    }
}
