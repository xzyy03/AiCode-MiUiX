package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表多选状态的行为约定：各列表页（供应商 / MCP / 技能 / 子代理 / 提示词 / 镜像）共用这一份状态，
 * 这里锁住「勾选切换、全选替换、数据刷新裁剪、退出清空」四条最容易被改坏的语义。
 */
class ListSelectionStateTest {

    @Test
    fun `初始为非多选且无选中`() {
        val state = ListSelectionState()
        assertFalse(state.isActive)
        assertEquals(0, state.selectedCount)
        assertTrue(state.selectedKeys.isEmpty())
    }

    @Test
    fun `toggle 可反复切换同一 key`() {
        val state = ListSelectionState()
        state.enter()
        state.toggle("a")
        assertTrue(state.isSelected("a"))
        assertEquals(1, state.selectedCount)

        state.toggle("a")
        assertFalse(state.isSelected("a"))
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `replaceAll 为整体替换而非追加`() {
        val state = ListSelectionState()
        state.enter()
        state.replaceAll(listOf("a", "b"))
        assertEquals(setOf("a", "b"), state.selectedKeys.toSet())

        state.replaceAll(listOf("c"))
        assertEquals(listOf("c"), state.selectedKeys)
    }

    @Test
    fun `retainOnly 剔除已消失的 key`() {
        val state = ListSelectionState()
        state.enter()
        state.replaceAll(listOf("a", "b", "c"))

        // 列表刷新后 b 被删掉（或被搜索过滤掉）：只能保留仍存在的 key，避免批量操作打到不存在的条目。
        state.retainOnly(setOf("a", "c"))
        assertEquals(setOf("a", "c"), state.selectedKeys.toSet())
    }

    @Test
    fun `exit 同时关闭模式并清空选中`() {
        val state = ListSelectionState()
        state.enter()
        state.replaceAll(listOf("a", "b"))
        assertTrue(state.isActive)

        state.exit()
        assertFalse(state.isActive)
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `clear 只清空选中不退出模式`() {
        val state = ListSelectionState()
        state.enter()
        state.replaceAll(listOf("a"))
        state.clear()
        assertTrue(state.isActive)
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `selectedKeys 返回快照 后续改动不影响已取出的列表`() {
        val state = ListSelectionState()
        state.enter()
        state.toggle("a")
        val snapshot = state.selectedKeys
        state.toggle("b")
        assertEquals(listOf("a"), snapshot)
        assertEquals(2, state.selectedCount)
    }
}
