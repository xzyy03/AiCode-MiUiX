package com.aicode.feature.agent.domain.tool.container

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** terminal 工具的批量关闭入参与 read 的 filter 解析。 */
class TerminalToolArgsTest {

    @Test
    fun tabIdsArrayIsParsedInOrder() {
        val element = Json.parseToJsonElement("""["term-1"," term-2 ", ""]""")
        assertEquals(listOf("term-1", "term-2"), parseTabIds(element))
    }

    @Test
    fun commaSeparatedStringIsTolerated() {
        assertEquals(listOf("term-1", "term-2"), parseTabIds(JsonPrimitive("term-1, term-2")))
        assertEquals(listOf("term-3", "term-4"), parseTabIds(JsonPrimitive("term-3;term-4")))
    }

    @Test
    fun missingIdsYieldEmptyList() {
        assertTrue(parseTabIds(null).isEmpty())
        assertTrue(parseTabIds(JsonPrimitive("")).isEmpty())
        assertTrue(parseTabIds(JsonPrimitive("  ,  ")).isEmpty())
    }

    @Test
    fun filterKeepsOnlyMatchingLines() {
        val output = "Starting build\n" +
            "src/A.kt:12: warning: unused variable\n" +
            "src/B.kt:7: error: unresolved reference\n" +
            "BUILD FAILED\n"

        val matched = filterOutputLines(output, Regex("""(error|warning)"""))

        assertEquals(
            listOf(
                "src/A.kt:12: warning: unused variable",
                "src/B.kt:7: error: unresolved reference"
            ),
            matched
        )
    }

    @Test
    fun filterMatchesAnywhereInLineAndHandlesCrlf() {
        val output = "line one\r\nERROR boom\r\nline three"

        assertEquals(listOf("ERROR boom"), filterOutputLines(output, Regex("ERROR")))
        // 行内任意位置命中即可，不要求整行匹配
        assertEquals(listOf("ERROR boom"), filterOutputLines(output, Regex("bo+m")))
    }

    @Test
    fun filterWithoutMatchReturnsEmptyList() {
        assertTrue(filterOutputLines("all good", Regex("ERROR")).isEmpty())
        assertTrue(filterOutputLines("", Regex("ERROR")).isEmpty())
    }
}
