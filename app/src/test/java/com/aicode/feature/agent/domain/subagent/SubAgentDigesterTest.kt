package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理摘要提炼：父代理只靠这条摘要推进时，必须拿到「结论正文 + 改动文件」，
 * 且在各种残缺输入（只有思考、toolArgs 被截断、无助手回复）下都不能崩。
 */
class SubAgentDigesterTest {

    private fun message(
        id: String,
        role: String,
        content: String = "",
        timestamp: Long = 0,
        toolName: String? = null,
        toolArgs: String? = null
    ) = AgentMessageEntity(
        id = id,
        sessionId = "sub",
        role = role,
        content = content,
        timestamp = timestamp,
        toolName = toolName,
        toolArgs = toolArgs
    )

    @Test
    fun digest_takesLastAssistantText() {
        val digest = SubAgentDigester.digest(
            listOf(
                message("1", "USER", "调研缓存实现", timestamp = 10),
                message("2", "ASSISTANT", "第一版结论", timestamp = 20),
                message("3", "ASSISTANT", "最终报告：缓存命中率提升 12%", timestamp = 30)
            )
        )

        assertEquals("最终报告：缓存命中率提升 12%", digest.excerpt)
        assertTrue(digest.changedFiles.isEmpty())
    }

    /** reasoning-only 的中间消息 content 为空：跳过它，否则父代理会收到一条空摘要。 */
    @Test
    fun digest_skipsBlankAssistantMessages() {
        val digest = SubAgentDigester.digest(
            listOf(
                message("1", "ASSISTANT", "有效结论", timestamp = 10),
                message("2", "ASSISTANT", "   ", timestamp = 20)
            )
        )

        assertEquals("有效结论", digest.excerpt)
    }

    /** 上下文压缩生成的内部摘要行也是 ASSISTANT，不能当作子代理的最终答复。 */
    @Test
    fun digest_skipsCompactionSummaryRows() {
        val digest = SubAgentDigester.digest(
            listOf(
                message("1", "ASSISTANT", "真正的最终报告", timestamp = 10),
                AgentMessageEntity(
                    id = "2",
                    sessionId = "sub",
                    role = "ASSISTANT",
                    content = "（上下文压缩摘要）",
                    timestamp = 20,
                    isContextSummary = true
                )
            )
        )

        assertEquals("真正的最终报告", digest.excerpt)
    }

    @Test
    fun digest_noAssistantText_returnsNullExcerpt() {
        val digest = SubAgentDigester.digest(
            listOf(message("1", "USER", "只派了任务，没等到回复", timestamp = 10))
        )

        assertNull(digest.excerpt)
        assertTrue(digest.changedFiles.isEmpty())
    }

    @Test
    fun digest_truncatesLongExcerpt() {
        val long = "字".repeat(SubAgentDigester.EXCERPT_MAX_CHARS + 200)

        val excerpt = SubAgentDigester.digest(listOf(message("1", "ASSISTANT", long))).excerpt

        assertEquals(SubAgentDigester.EXCERPT_MAX_CHARS + "…（已截断）".length, excerpt!!.length)
        assertTrue(excerpt.endsWith("…（已截断）"))
    }

    @Test
    fun digest_collectsChangedFilesFromMutatingTools() {
        val digest = SubAgentDigester.digest(
            listOf(
                message("1", "TOOL", "ok", toolName = "editFile", toolArgs = """{"path":"~/workspace/a.kt"}"""),
                message("2", "TOOL", "ok", toolName = "writeFile", toolArgs = """{"path":"~/workspace/b.kt"}"""),
                // 只读工具不算改动
                message("3", "TOOL", "ok", toolName = "readFile", toolArgs = """{"path":"~/workspace/c.kt"}"""),
                // 同一文件改两次只报一次
                message("4", "TOOL", "ok", toolName = "editFile", toolArgs = """{"path":"~/workspace/a.kt"}""")
            )
        )

        assertEquals(listOf("~/workspace/a.kt", "~/workspace/b.kt"), digest.changedFiles)
    }

    /** 落库的 toolArgs 是 take(500) 预览，长参数会被截成非法 JSON：解析失败须退化为字面提取而不是丢数据。 */
    @Test
    fun digest_parsesPathFromTruncatedToolArgs() {
        val truncated = """{"path":"~/workspace/long.kt","content":"aaaa"""

        val digest = SubAgentDigester.digest(
            listOf(message("1", "TOOL", "ok", toolName = "writeFile", toolArgs = truncated))
        )

        assertEquals(listOf("~/workspace/long.kt"), digest.changedFiles)
    }

    @Test
    fun digest_ignoresMalformedToolArgs() {
        val digest = SubAgentDigester.digest(
            listOf(
                message("1", "TOOL", "ok", toolName = "writeFile", toolArgs = "not json at all"),
                message("2", "TOOL", "ok", toolName = "writeFile", toolArgs = null)
            )
        )

        assertTrue(digest.changedFiles.isEmpty())
    }

    @Test
    fun digest_capsChangedFileCount() {
        val messages = (1..20).map { index ->
            message("$index", "TOOL", "ok", toolName = "editFile", toolArgs = """{"path":"~/workspace/f$index.kt"}""")
        }

        val digest = SubAgentDigester.digest(messages)

        assertEquals(SubAgentDigester.MAX_CHANGED_FILES, digest.changedFiles.size)
    }
}
