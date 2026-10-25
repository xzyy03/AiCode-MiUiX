package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputDeltaTest {

    private val tool = TerminalSessionTool(mockk<TerminalSessionProvider>())
    private val outputDelta = TerminalSessionTool::class.java.getDeclaredMethod(
        "outputDelta",
        String::class.java,
        String::class.java
    ).apply { isAccessible = true }

    private fun delta(previous: String, current: String): String =
        outputDelta.invoke(tool, previous, current) as String

    @Test
    fun emptyCurrentAfterNonEmptyOutput_returnsEmptyDelta() {
        assertEquals("", delta("previous output", ""))
    }

    @Test
    fun bothOutputsEmpty_returnsEmptyDelta() {
        assertEquals("", delta("", ""))
    }

    @Test
    fun emptyPrevious_returnsCurrentOutput() {
        assertEquals("first output", delta("", "first output"))
    }

    @Test
    fun unchangedOutput_returnsEmptyDelta() {
        assertEquals("", delta("same output", "same output"))
    }

    @Test
    fun appendedOutput_returnsOnlyNewSuffix() {
        assertEquals("XYZ", delta("abc", "abcXYZ"))
    }

    @Test
    fun overlappingOutput_returnsOnlyNewSuffix() {
        assertEquals("XYZ", delta("start:abc", "abcXYZ"))
    }

    @Test
    fun multipleOverlaps_usesLongestMatch() {
        assertEquals("c", delta("ababa", "abac"))
    }

    @Test
    fun noOverlap_returnsCurrentOutput() {
        assertEquals("XYZ", delta("abc", "XYZ"))
    }
}
