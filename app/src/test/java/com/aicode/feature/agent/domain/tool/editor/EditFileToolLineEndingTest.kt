package com.aicode.feature.agent.domain.tool.editor

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.agent.domain.tool.file.ReadFileTool
import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditFileToolLineEndingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = TestFileAccessProvider()
    private val versions = FileVersionRegistry()
    private val tool = EditFileTool(fileAccess, WorkspaceFileLock(), versions)

    private fun edit(old: String, new: String, replaceAll: Boolean = false) = JsonObject(
        mapOf(
            "old_string" to JsonPrimitive(old),
            "new_string" to JsonPrimitive(new),
            "replace_all" to JsonPrimitive(replaceAll)
        )
    )

    private suspend fun apply(file: File, vararg edits: JsonObject): ToolResult = tool.execute(
        mapOf("path" to JsonPrimitive(file.path), "edits" to JsonArray(edits.toList()))
    )

    @Test
    fun crlfFile_readThenEdit_preservesCrlf() = runTest {
        val file = tmp.newFile("windows.txt").apply { writeText("first\r\nsecond\r\n") }
        val context = AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = tmp.root.path,
            language = "text",
            sessionId = "line-endings"
        )
        val read = ReadFileTool(fileAccess, versions).executeWithContext(
            mapOf("path" to JsonPrimitive(file.path)), context
        ) as ToolResult.Success
        val content = (read.data as JsonObject).getValue("content").jsonPrimitive.content
        assertEquals("first\nsecond", content)

        val result = tool.executeWithContext(
            mapOf(
                "path" to JsonPrimitive(file.path),
                "edits" to JsonArray(listOf(edit(content, "first\nupdated")))
            ), context
        )

        assertTrue(result is ToolResult.Success)
        assertEquals("first\r\nupdated\r\n", file.readText())
    }

    @Test
    fun lfFile_acceptsCrlfInputAndKeepsLf() = runTest {
        val file = tmp.newFile().apply { writeText("first\nsecond\n") }

        val result = apply(file, edit("first\r\nsecond", "first\r\nupdated"))

        assertTrue(result is ToolResult.Success)
        assertEquals("first\nupdated\n", file.readText())
    }

    @Test
    fun singleLineMatch_newLinesUseFileLineEnding() = runTest {
        val file = tmp.newFile().apply { writeText("first\r\nsecond\r\n") }

        val result = apply(file, edit("second", "updated\nthird"))

        assertTrue(result is ToolResult.Success)
        assertEquals("first\r\nupdated\r\nthird\r\n", file.readText())
    }

    @Test
    fun mixedFile_onlyMatchedRegionChanges() = runTest {
        val file = tmp.newFile().apply { writeText("prefix\nsame\r\nold\r\nsuffix\n") }

        val result = apply(file, edit("same\nold", "same\nupdated"))

        assertTrue(result is ToolResult.Success)
        assertEquals("prefix\nsame\r\nupdated\r\nsuffix\n", file.readText())
    }

    @Test
    fun equivalentMatches_requireReplaceAll() = runTest {
        val original = "first\nsecond\nfirst\r\nsecond\r\n"
        val file = tmp.newFile().apply { writeText(original) }

        val result = apply(file, edit("first\nsecond", "updated"))

        assertEquals("MULTIPLE_MATCHES", (result as ToolResult.Error).code)
        assertEquals(original, file.readText())
    }

    @Test
    fun replaceAll_preservesEachMatchedRegionsLineEnding() = runTest {
        val file = tmp.newFile().apply { writeText("first\nsecond\nfirst\r\nsecond\r\n") }

        val result = apply(file, edit("first\nsecond", "first\nupdated", replaceAll = true))

        assertTrue(result is ToolResult.Success)
        assertEquals("first\nupdated\nfirst\r\nupdated\r\n", file.readText())
        assertEquals(
            "2",
            ((result as ToolResult.Success).data as JsonObject).getValue("replacements").jsonPrimitive.content
        )
    }

    @Test
    fun batchEdits_matchPreviousResults() = runTest {
        val file = tmp.newFile().apply { writeText("first\r\nsecond\r\n") }

        val result = apply(
            file,
            edit("first\nsecond", "first\nupdated"),
            edit("first\nupdated", "final\nvalue")
        )

        assertTrue(result is ToolResult.Success)
        assertEquals("final\r\nvalue\r\n", file.readText())
    }

    @Test
    fun batchFailure_doesNotWriteEarlierEdits() = runTest {
        val original = "first\r\nsecond\r\n"
        val file = tmp.newFile().apply { writeText(original) }

        val result = apply(
            file,
            edit("first\nsecond", "first\nupdated"),
            edit("missing", "value")
        )

        assertEquals("NO_MATCH", (result as ToolResult.Error).code)
        assertEquals(original, file.readText())
    }

    @Test
    fun indentationStillRequiresExactMatch() = runTest {
        val original = "first\r\n    second\r\n"
        val file = tmp.newFile().apply { writeText(original) }

        val result = apply(file, edit("first\nsecond", "updated"))

        assertEquals("NO_MATCH", (result as ToolResult.Error).code)
        assertEquals(original, file.readText())
    }

    @Test
    fun regexAndReplacementCharacters_areLiteral() = runTest {
        val file = tmp.newFile().apply { writeText("[first].*\r\n(second)?\r\n") }

        val result = apply(file, edit("[first].*\n(second)?", "\$1\\path\nupdated"))

        assertTrue(result is ToolResult.Success)
        assertEquals("\$1\\path\r\nupdated\r\n", file.readText())
    }

    @Test
    fun newlineOnlyMatch_consumesWholeCrlf() = runTest {
        val file = tmp.newFile().apply { writeText("first\r\nsecond") }

        val result = apply(file, edit("\n", "\ninserted\n"))

        assertTrue(result is ToolResult.Success)
        assertEquals("first\r\ninserted\r\nsecond", file.readText())
    }

    @Test
    fun longMultilineContext_preservesCrlf() = runTest {
        val old = (1..2000).joinToString("\n") { "line-$it" }
        val file = tmp.newFile().apply { writeText(old.replace("\n", "\r\n") + "\r\n") }

        val result = apply(file, edit(old, "updated\nvalue"))

        assertTrue(result is ToolResult.Success)
        assertEquals("updated\r\nvalue\r\n", file.readText())
    }

    @Test
    fun separatorRunLongerThanContent_failsCleanly() = runTest {
        // 模式里的换行数多于内容剩余字符时，扫描不得越界，按未匹配处理
        val original = "a\r\n\r\n"
        val file = tmp.newFile().apply { writeText(original) }

        val result = apply(file, edit("\n\n\nq", "x"))

        assertEquals("NO_MATCH", (result as ToolResult.Error).code)
        assertEquals(original, file.readText())
    }

    @Test
    fun deletion_preservesSurroundingLineEndings() = runTest {
        val file = tmp.newFile().apply { writeText("first\r\nsecond\r\nthird\r\n") }

        val result = apply(file, edit("first\nsecond\n", ""))

        assertTrue(result is ToolResult.Success)
        assertEquals("third\r\n", file.readText())
    }
}
