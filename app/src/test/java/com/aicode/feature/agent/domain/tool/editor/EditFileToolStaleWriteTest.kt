package com.aicode.feature.agent.domain.tool.editor

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.STALE_FILE_CODE
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `editFile` 的写前版本校验：模拟「本会话登记版本后，文件被并发改动」这一核心场景。
 *
 * 这些用例守护的是**静默丢更新**：没有校验时，过期的那次编辑会直接覆盖别人的改动，
 * 且返回成功——正是本次改动要消灭的行为。
 */
class EditFileToolStaleWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()

    private fun tool() = EditFileTool(fileAccess, WorkspaceFileLock(), versions)

    private fun context(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.absolutePath,
        language = "kotlin",
        sessionId = sessionId
    )

    private fun editArgs(path: String, old: String, new: String): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "edits" to JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "old_string" to JsonPrimitive(old),
                        "new_string" to JsonPrimitive(new)
                    )
                )
            )
        )
    )

    /** 模拟「本会话读过这个文件」：登记当前内容的指纹。 */
    private fun recordCurrentVersion(sessionId: String, file: File) {
        versions.record(sessionId, fileAccess.toDisplayPath(file.path), fileAccess.fingerprint(file.path)!!)
    }

    @Test
    fun staleWrite_isRejectedAndFileUntouched() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val session = "s1"
        recordCurrentVersion(session, file)

        // 另一个会话（或用户）在此期间改了同一个文件
        file.writeText("val a = 1\nval b = 2\n")

        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 100"),
            context(session)
        )

        assertTrue("过期写必须被拒绝，实际=$result", result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertTrue(
            "错误信息要给出自愈路径（提示重新读取）",
            result.message.contains("重新读取")
        )
        assertEquals(
            "被拒绝的写入绝不能落盘",
            "val a = 1\nval b = 2\n",
            file.readText()
        )
    }

    @Test
    fun freshWrite_succeeds() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }
        val session = "s1"
        recordCurrentVersion(session, file)

        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context(session)
        )

        assertTrue("版本未变时应正常写入，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun neverReadFile_isAllowedThrough() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("val a = 1\n") }

        // 没有为该会话登记过任何版本：无从判断过期，放行（避免打断正常流程）
        val result = tool().executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context("s-fresh")
        )

        assertTrue("未读过的文件应放行，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }

    @Test
    fun otherSessionStale_doesNotAffectThisSession() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)
        // s1 写入后登记新版本；s2 从未读过，因此不受 s1 的影响
        tool().executeWithContext(editArgs(file.path, "v1", "v2"), context("s1"))
        recordCurrentVersion("s1", file)

        val result = tool().executeWithContext(editArgs(file.path, "v2", "v3"), context("s1"))
        assertTrue("同一会话连续编辑不应被自己判为冲突，实际=$result", result is ToolResult.Success)
        assertEquals("v3\n", file.readText())
    }

    @Test
    fun writeRequiresRead_afterExternalChangeIsRefused() = runTest {
        val file = tmp.newFile("b.kt").apply { writeText("original\n") }
        recordCurrentVersion("s1", file)
        file.writeText("changed-by-someone-else\n")

        val result = tool().executeWithContext(
            editArgs(file.path, "original", "mine"),
            context("s1")
        )

        assertTrue(result is ToolResult.Error)
        assertEquals("changed-by-someone-else\n", file.readText())
    }

    @Test
    fun fingerprintUnavailable_doesNotBlockEdit() = runTest {
        // 远端文件超过读取上限时 fingerprint 会抛异常。版本校验是辅助保护，
        // 它自己算不出指纹时**必须放行**，否则用户会因为"算不出指纹"而改不了文件。
        val file = tmp.newFile("big.kt").apply { writeText("val a = 1\n") }
        val throwing = object : FileAccessProvider by fileAccess {
            override fun fingerprint(path: String): String? =
                throw IllegalStateException("模拟：文件过大，无法计算指纹")
        }
        val guardedTool = EditFileTool(throwing, WorkspaceFileLock(), versions)
        // 先登记一条版本，确保校验逻辑真的会走到 fingerprint 调用
        versions.record("s1", fileAccess.toDisplayPath(file.path), "sha256:whatever")

        val result = guardedTool.executeWithContext(
            editArgs(file.path, "val a = 1", "val a = 2"),
            context("s1")
        )

        assertTrue("算不出指纹时必须放行，实际=$result", result is ToolResult.Success)
        assertEquals("val a = 2\n", file.readText())
    }
}
