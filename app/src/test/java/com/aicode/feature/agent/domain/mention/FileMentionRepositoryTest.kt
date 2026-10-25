package com.aicode.feature.agent.domain.mention

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** @ 文件引用的清单拉取与模糊匹配。 */
class FileMentionRepositoryTest {

    private val engine = mockk<CommandEngine>(relaxed = true)
    private val repository = FileMentionRepository(engine)

    private val files = listOf(
        "chat.md",
        "docs/chat.md",
        "ChatInputBar.kt",
        "a/b/c.txt"
    )

    @Test
    fun match_emptyQuery_returnsLeadingItemsWithinLimit() {
        assertEquals(files.take(2), repository.match(files, "", limit = 2))
        assertEquals(files, repository.match(files, ""))
    }

    @Test
    fun match_subsequence_filtersAndRanksByGapThenLength() {
        // 贪心子序列匹配：chat.md、ChatInputBar.kt 从首字符起连续命中（gap 0），按长度排前后；
        // docs/chat.md 的 "docs" 里先有个 c 被吃掉、要跨段补全，gap 更大排最后。
        assertEquals(
            listOf("chat.md", "ChatInputBar.kt", "docs/chat.md"),
            repository.match(files, "chat")
        )
    }

    @Test
    fun match_isCaseInsensitive() {
        assertEquals(listOf("ChatInputBar.kt"), repository.match(files, "CIB"))
    }

    @Test
    fun match_noMatch_returnsEmpty() {
        assertTrue(repository.match(files, "zzzz").isEmpty())
    }

    @Test
    fun loadFiles_success_parsesAndDeduplicatesLines() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult(
            "a.kt\nsub/b.kt\n\na.kt\n",
            0
        )
        val result = repository.loadFiles("/root/workspace") as FileMentionRepository.LoadResult.Success
        assertEquals(listOf("a.kt", "sub/b.kt"), result.files)
    }

    @Test
    fun loadFiles_containerNotReady_returnsUnavailable() = runTest {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns null
        assertTrue(
            repository.loadFiles("/root/workspace") is FileMentionRepository.LoadResult.Unavailable
        )
    }

    @Test
    fun loadFiles_blankPath_returnsUnavailable() = runTest {
        assertTrue(repository.loadFiles("") is FileMentionRepository.LoadResult.Unavailable)
    }
}
