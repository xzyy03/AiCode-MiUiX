package com.aicode.feature.agent.domain.tool.file

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.STALE_FILE_CODE
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `writeFile` 的写前版本校验：覆盖已有文件时必须基于最新内容，新建文件不校验。
 */
class WriteFileToolStaleWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()

    private fun tool() = WriteFileTool(fileAccess, WorkspaceFileLock(), versions)

    private fun context(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = tmp.root.absolutePath,
        language = "kotlin",
        sessionId = sessionId
    )

    private fun writeArgs(path: String, content: String, overwrite: Boolean = true): Map<String, JsonElement> = mapOf(
        "path" to JsonPrimitive(path),
        "content" to JsonPrimitive(content),
        "overwrite" to JsonPrimitive(overwrite)
    )

    private fun recordCurrentVersion(sessionId: String, file: File) {
        versions.record(sessionId, fileAccess.toDisplayPath(file.path), fileAccess.fingerprint(file.path)!!)
    }

    @Test
    fun overwriteAfterExternalChange_isRejected() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)
        file.writeText("v2-changed-by-other\n")

        val result = tool().executeWithContext(writeArgs(file.path, "v3-overwrite\n"), context("s1"))

        assertTrue("过期覆盖必须被拒绝，实际=$result", result is ToolResult.Error)
        assertEquals(STALE_FILE_CODE, (result as ToolResult.Error).code)
        assertEquals("被拒绝的写入绝不能落盘", "v2-changed-by-other\n", file.readText())
    }

    @Test
    fun overwriteWithCurrentVersion_succeeds() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }
        recordCurrentVersion("s1", file)

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n"), context("s1"))

        assertTrue("版本一致时应正常写入，实际=$result", result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun creatingNewFile_isNotBlockedByRegistry() = runTest {
        // 登记表里有一条同路径记录，但磁盘上文件不存在（如刚被删除）：应视为新建，不拦截。
        val target = File(tmp.root, "new.kt")
        versions.record("s1", fileAccess.toDisplayPath(target.path), "sha256:stale-value")

        val result = tool().executeWithContext(writeArgs(target.path, "fresh\n"), context("s1"))

        assertTrue("新建文件不应被版本校验拦截，实际=$result", result is ToolResult.Success)
        assertEquals("fresh\n", target.readText())
    }

    @Test
    fun unreadExistingFile_isAllowedThrough() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n"), context("s-never-read"))

        assertTrue("未读过的覆盖应放行（避免打断既有流程），实际=$result", result is ToolResult.Success)
        assertEquals("v2\n", file.readText())
    }

    @Test
    fun overwriteFalse_stillReportsFileExists() = runTest {
        val file = tmp.newFile("a.kt").apply { writeText("v1\n") }

        val result = tool().executeWithContext(writeArgs(file.path, "v2\n", overwrite = false), context("s1"))

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_EXISTS", (result as ToolResult.Error).code)
        assertEquals("v1\n", file.readText())
    }
}
