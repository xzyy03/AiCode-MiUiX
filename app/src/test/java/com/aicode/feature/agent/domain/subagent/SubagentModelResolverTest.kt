package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位解析：定义锁定 > 请求档位 > 均衡档 > 继承父会话。
 *
 * 背景：中转站常有上百个模型，主代理只判断任务难度并给出档位，具体用哪个模型由用户在设置里
 * 绑定。档位没配或提供商不可用时必须降级**并且讲清原因**，否则主代理会以为换了模型在等效果。
 */
class SubagentModelResolverTest {

    private val configuredLight = SubagentTierSelection("prov-light", "cheap-model")
    private val configuredStandard = SubagentTierSelection("prov-std", "balanced-model")
    private val configuredStrong = SubagentTierSelection("prov-strong", "strong-model")

    private val allConfigured = mapOf(
        SubagentModelTier.LIGHT to configuredLight,
        SubagentModelTier.STANDARD to configuredStandard,
        SubagentModelTier.STRONG to configuredStrong
    )

    private fun resolve(
        requestedTier: SubagentModelTier?,
        definitionModel: String? = null,
        definitionProviderId: String? = null,
        selections: Map<SubagentModelTier, SubagentTierSelection> = allConfigured,
        unavailableProviders: Set<String> = emptySet()
    ) = SubagentModelResolver.resolve(
        requestedTier = requestedTier,
        definitionModel = definitionModel,
        definitionProviderId = definitionProviderId,
        selections = selections,
        isProviderAvailable = { it !in unavailableProviders }
    )

    @Test
    fun noTierRequested_inheritsParent() {
        val resolved = resolve(null)

        assertTrue(resolved.inheritsParent)
        assertNull(resolved.tier)
        assertNull(resolved.fallback)
    }

    @Test
    fun requestedTierConfigured_usesItsProviderAndModel() {
        val resolved = resolve(SubagentModelTier.STRONG)

        assertEquals("prov-strong", resolved.providerId)
        assertEquals("strong-model", resolved.model)
        assertEquals(SubagentModelTier.STRONG, resolved.tier)
        assertNull(resolved.fallback)
    }

    /** 定义里写死 model 视为用户锁定：请求的档位被忽略，但要告诉主代理原因。 */
    @Test
    fun definitionModelWins_andReportsPinned() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.LIGHT,
            definitionModel = "pinned-model",
            definitionProviderId = "prov-pinned"
        )

        assertEquals("pinned-model", resolved.model)
        assertEquals("prov-pinned", resolved.providerId)
        assertNull(resolved.tier)
        assertEquals(SubagentModelFallback.DEFINITION_PINNED, resolved.fallback)
    }

    /** 定义锁定模型、主代理没请求档位时不算降级，不该多一条说明。 */
    @Test
    fun definitionModelWithoutTierRequest_hasNoFallbackNotice() {
        val resolved = resolve(requestedTier = null, definitionModel = "pinned-model")

        assertEquals("pinned-model", resolved.model)
        assertNull(resolved.fallback)
    }

    /** 请求的档位没配 → 退到「均衡」档，并标记降级。 */
    @Test
    fun unconfiguredTier_fallsBackToStandard() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            selections = mapOf(
                SubagentModelTier.LIGHT to SubagentTierSelection(),
                SubagentModelTier.STANDARD to configuredStandard,
                SubagentModelTier.STRONG to SubagentTierSelection()
            )
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelTier.STRONG, resolved.requestedTier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    /** 请求的就是「均衡」档且没配 → 没有下一档可退，直接继承父会话。 */
    @Test
    fun unconfiguredStandardTier_inheritsParent() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STANDARD,
            selections = mapOf(
                SubagentModelTier.LIGHT to configuredLight,
                SubagentModelTier.STANDARD to SubagentTierSelection(),
                SubagentModelTier.STRONG to configuredStrong
            )
        )

        assertTrue(resolved.inheritsParent)
        assertNull(resolved.tier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
        assertEquals(SubagentModelTier.STANDARD, resolved.requestedTier)
    }

    /** 档位绑定的提供商被删除/停用 → 等同于未配置，不得拿一个不可用的 provider 去起会话。 */
    @Test
    fun providerUnavailable_fallsBackLikeUnconfigured() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.STRONG,
            unavailableProviders = setOf("prov-strong")
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelTier.STANDARD, resolved.tier)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    /** 只配了 half（有 provider 没 model）也按未配置处理，避免发出空模型名的请求。 */
    @Test
    fun partialSelection_isTreatedAsUnconfigured() {
        val resolved = resolve(
            requestedTier = SubagentModelTier.LIGHT,
            selections = mapOf(
                SubagentModelTier.LIGHT to SubagentTierSelection("prov-light", ""),
                SubagentModelTier.STANDARD to configuredStandard,
                SubagentModelTier.STRONG to SubagentTierSelection()
            )
        )

        assertEquals("balanced-model", resolved.model)
        assertEquals(SubagentModelFallback.TIER_UNAVAILABLE, resolved.fallback)
    }

    @Test
    fun tierTokenParsing_isLenient() {
        assertEquals(SubagentModelTier.LIGHT, SubagentModelTier.fromToken(" Light "))
        assertEquals(SubagentModelTier.STRONG, SubagentModelTier.fromToken("STRONG"))
        assertNull(SubagentModelTier.fromToken("turbo"))
        assertNull(SubagentModelTier.fromToken(""))
        assertNull(SubagentModelTier.fromToken(null))
    }
}
