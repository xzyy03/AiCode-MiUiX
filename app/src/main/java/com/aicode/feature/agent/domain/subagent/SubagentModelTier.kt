package com.aicode.feature.agent.domain.subagent

/**
 * 子代理模型档位：主代理派发时按任务难度选档，档位到「哪个提供商的哪个模型」的映射由用户在
 * 设置里配置。
 *
 * 为什么用档位而不是裸 model id：自建中转站常有上百个模型，让模型自己去猜 id 既容易拼错，
 * 也看不出哪个便宜。档位把「谁强谁弱」这个判断留在用户手里，AI 只判断任务难度。
 */
enum class SubagentModelTier(val apiValue: String) {
    /** 只读探索、批量定位、机械改名这类活。 */
    LIGHT("light"),

    /** 单文件修改、常规修复。 */
    STANDARD("standard"),

    /** 跨模块重构、疑难排查、需要慎重判断的方案设计。 */
    STRONG("strong");

    companion object {
        /** 宽松解析：忽略大小写与空白；无法识别时返回 null（当作没传）。 */
        fun fromToken(raw: String?): SubagentModelTier? {
            val token = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.apiValue == token }
        }
    }
}

/** 某档位绑定的模型；两个字段都非空才算「已配置」。 */
data class SubagentTierSelection(
    val providerId: String = "",
    val model: String = ""
) {
    val isConfigured: Boolean get() = providerId.isNotBlank() && model.isNotBlank()
}

/** 派发时实际采用的模型绑定，以及是否需要向主代理解释「为什么跟你请求的不一样」。 */
data class ResolvedSubagentModel(
    /** 生效的 provider id；null 表示继承父会话。 */
    val providerId: String? = null,
    /** 生效的模型名；null 表示继承父会话。 */
    val model: String? = null,
    /** 实际生效的档位；定义锁定模型或最终继承父会话时为 null。 */
    val tier: SubagentModelTier? = null,
    /** 主代理请求的档位（原样回显，便于工具结果说明差异）。 */
    val requestedTier: SubagentModelTier? = null,
    val fallback: SubagentModelFallback? = null
) {
    /** 是否最终继承父会话模型。 */
    val inheritsParent: Boolean get() = model.isNullOrBlank()
}

/** 档位没按请求生效的原因，用于生成给主代理的说明文案。 */
enum class SubagentModelFallback {
    /** 请求的档位没配置，或它绑定的提供商已删除/停用；已退到「均衡」档或继承父会话。 */
    TIER_UNAVAILABLE,

    /** 子代理定义里写死了 model，请求的档位被忽略。 */
    DEFINITION_PINNED
}

/**
 * 把「请求的档位 + 子代理定义」解析成实际要用的 provider/model。
 *
 * 优先级（与用户可见文档一致）：
 * 1. 定义里写死了 `model` → 用定义（视为用户锁定该子代理的模型，忽略档位）；
 * 2. 请求的档位已配置且提供商可用 → 用该档位；
 * 3. 退到「均衡」档（若已配置且可用）；
 * 4. 都不可用 → 继承父会话。
 *
 * 纯函数：提供商可用性由调用方以谓词传入，便于单测覆盖各种降级组合。
 */
object SubagentModelResolver {

    fun resolve(
        requestedTier: SubagentModelTier?,
        definitionModel: String?,
        definitionProviderId: String?,
        selections: Map<SubagentModelTier, SubagentTierSelection>,
        isProviderAvailable: (String) -> Boolean
    ): ResolvedSubagentModel {
        fun usable(tier: SubagentModelTier): SubagentTierSelection? =
            selections[tier]?.takeIf { it.isConfigured && isProviderAvailable(it.providerId) }

        // 1) 定义锁定模型：尊重用户对该子代理的显式配置，但要让主代理知道它的请求被忽略了。
        if (!definitionModel.isNullOrBlank()) {
            return ResolvedSubagentModel(
                providerId = definitionProviderId,
                model = definitionModel,
                requestedTier = requestedTier,
                fallback = if (requestedTier != null) SubagentModelFallback.DEFINITION_PINNED else null
            )
        }

        // 没请求档位 → 保持原有行为（继承父会话）。
        if (requestedTier == null) return ResolvedSubagentModel()

        usable(requestedTier)?.let { selection ->
            return ResolvedSubagentModel(
                providerId = selection.providerId,
                model = selection.model,
                tier = requestedTier,
                requestedTier = requestedTier
            )
        }

        // 2) 降级：请求档位不可用时退到「均衡」。
        if (requestedTier != SubagentModelTier.STANDARD) {
            usable(SubagentModelTier.STANDARD)?.let { selection ->
                return ResolvedSubagentModel(
                    providerId = selection.providerId,
                    model = selection.model,
                    tier = SubagentModelTier.STANDARD,
                    requestedTier = requestedTier,
                    fallback = SubagentModelFallback.TIER_UNAVAILABLE
                )
            }
        }

        // 3) 全都没配 → 继承父会话，并说明原因。
        return ResolvedSubagentModel(
            requestedTier = requestedTier,
            fallback = SubagentModelFallback.TIER_UNAVAILABLE
        )
    }
}
