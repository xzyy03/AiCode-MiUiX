package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 子代理结束时从其会话消息里提炼的摘要，供父会话的完成通知直接携带。
 *
 * @property excerpt 最后一条有正文的助手回复（已截断），子代理未能产出正文时为 null。
 * @property changedFiles 本次执行改动过的文件容器路径（最多 [SubAgentDigester.MAX_CHANGED_FILES] 个）。
 */
data class SubAgentDigest(
    val excerpt: String? = null,
    val changedFiles: List<String> = emptyList()
)

/**
 * 从子代理会话的消息序列中提炼「结果摘要 + 改动文件」。
 *
 * 父代理原本只能靠 `task(action="read")` 再取一次才能知道子代理干了什么，既是额外一次工具调用，
 * 又多消耗一轮上下文。把摘要随完成通知送达后，多数场景无需再读；需要完整报告时才读。
 *
 * 纯函数、不依赖 Android 与数据库，便于单测覆盖各种残缺输入：
 * - 子代理只产出思考（reasoning-only 消息 content 为空）时，须跳过它去取上一条有正文的回复；
 * - `toolArgs` 落库的是 `take(500)` 的预览，可能被截断成非法 JSON，解析失败时退化为字面提取。
 */
object SubAgentDigester {

    /** 摘要字符上限：够说明结论与依据，又不至于让每条通知多占上千 token。 */
    const val EXCERPT_MAX_CHARS = 800

    /** 改动文件列表上限：再多对父代理也无用，只会挤占上下文。 */
    const val MAX_CHANGED_FILES = 8

    /** 会改动文件的工具：其 `path` 参数即改动目标。 */
    private val MUTATING_TOOLS = setOf("editFile", "writeFile", "generateImage")

    /** 从被截断的 `toolArgs` 预览里兜底提取 path 字面量。 */
    private val PATH_LITERAL = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")

    private val json = Json { ignoreUnknownKeys = true }

    fun digest(messages: List<AgentMessageEntity>): SubAgentDigest {
        return SubAgentDigest(
            excerpt = lastAssistantText(messages),
            changedFiles = changedFiles(messages)
        )
    }

    private fun lastAssistantText(messages: List<AgentMessageEntity>): String? =
        messages.lastOrNull {
            // 压缩产生的内部摘要行也是 ASSISTANT 角色，但它不是子代理的最终答复，必须排除。
            it.role == MessageRole.ASSISTANT.name &&
                it.content.isNotBlank() &&
                !it.isContextSummary &&
                !it.isCompactionMarker
        }?.content?.trim()?.takeIf { it.isNotEmpty() }?.let(::truncate)

    private fun changedFiles(messages: List<AgentMessageEntity>): List<String> =
        messages.asSequence()
            .filter { it.role == MessageRole.TOOL.name && it.toolName in MUTATING_TOOLS }
            .mapNotNull { pathOf(it.toolArgs) }
            .distinct()
            .take(MAX_CHANGED_FILES)
            .toList()

    private fun pathOf(toolArgs: String?): String? {
        if (toolArgs.isNullOrBlank()) return null
        val parsed = runCatching { json.parseToJsonElement(toolArgs) as? JsonObject }.getOrNull()
        parsed?.get("path")?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }?.takeIf { it.isNotBlank() }?.let { return it }
        return PATH_LITERAL.find(toolArgs)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun truncate(text: String): String =
        if (text.length <= EXCERPT_MAX_CHARS) {
            text
        } else {
            text.take(EXCERPT_MAX_CHARS).trimEnd() + "…（已截断）"
        }
}
