package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `task` 卡片的解析：参数给「派的是什么活」，结果给「派到哪个会话、什么档位/模型」。
 *
 * 关键容错：结果里 `data` 既可能是对象也可能被序列化成字符串；派发失败时不出卡；
 * 结果还没回来（流式中）也要能按参数出「派发中」的卡。
 */
class SubAgentCardComponentsTest {

    private val createArgs = """
        {"action":"create","description":"调研缓存实现","agent":"explorer","prompt":"读取缓存相关文件并总结"}
    """.trimIndent()

    @Test
    fun parse_readsArgsAndResultData() {
        val content = """
            {
              "status": "success",
              "data": {
                "id": "sub-1",
                "state": "running",
                "agent": "explorer",
                "tier": "light",
                "model": "cheap-model",
                "warning": "该子代理定义里已写死模型",
                "message": "子代理已创建并开始执行"
              }
            }
        """.trimIndent()

        val parsed = parseSubAgentDispatch(createArgs, content)

        assertNotNull(parsed)
        assertEquals("sub-1", parsed!!.subSessionId)
        assertEquals("explorer", parsed.agentName)
        assertEquals("调研缓存实现", parsed.description)
        assertEquals("light", parsed.tier)
        assertEquals("cheap-model", parsed.model)
        assertEquals("该子代理定义里已写死模型", parsed.warning)
    }

    /** data 被序列化成字符串的形态也要能读出来（两种传输形态都出现过）。 */
    @Test
    fun parse_readsResultDataAsJsonString() {
        val content = """
            {
              "status": "success",
              "data": "{\"id\":\"sub-2\",\"state\":\"running\",\"tier\":\"strong\",\"model\":\"strong-model\"}"
            }
        """.trimIndent()

        val parsed = parseSubAgentDispatch(createArgs, content)

        assertEquals("sub-2", parsed!!.subSessionId)
        assertEquals("strong", parsed.tier)
    }

    /** 结果还没回来（流式中）：按参数出「派发中」的卡，id 为空只影响跳转。 */
    @Test
    fun parse_withoutResultStillRendersFromArgs() {
        val parsed = parseSubAgentDispatch(createArgs, null)

        assertNotNull(parsed)
        assertNull(parsed!!.subSessionId)
        assertEquals("explorer", parsed.agentName)
        assertEquals("调研缓存实现", parsed.description)
        assertNull(parsed.tier)
    }

    /** 工具行还是「执行中」占位文本时，结果解析不出来，但不能因此丢掉整张卡。 */
    @Test
    fun parse_withPlaceholderResultStillRendersFromArgs() {
        val parsed = parseSubAgentDispatch(createArgs, "[running] 正在启动子代理")

        assertNotNull(parsed)
        assertNull(parsed!!.subSessionId)
        assertEquals("调研缓存实现", parsed.description)
    }

    /** 派发失败（定义不存在等）交给通用工具行显示错误文本，不出「运行中」的假卡。 */
    @Test
    fun parse_returnsNullWhenDispatchFailed() {
        val content = """
            {"status":"error","message":"子代理定义不存在：nope","code":"AGENT_NOT_FOUND"}
        """.trimIndent()

        assertNull(parseSubAgentDispatch(createArgs, content))
    }

    /** read/send/stop/list 不是派发，仍是普通工具行。 */
    @Test
    fun parse_returnsNullForNonCreateAction() {
        val readArgs = """{"action":"read","id":"sub-1"}"""

        assertNull(parseSubAgentDispatch(readArgs, """{"status":"success","data":{"id":"sub-1"}}"""))
    }

    /** 没给 description 时退化为 prompt 首行，卡片不会空标题。 */
    @Test
    fun parse_fallsBackToPromptFirstLine() {
        val args = """{"prompt":"修复登录空指针\n只改 AuthManager" }"""

        val parsed = parseSubAgentDispatch(args, null)

        assertEquals("修复登录空指针", parsed!!.description)
    }

    /** 批量任务创建：能正确从 tasks 入参与 subagents 结果中解析多个子任务项。 */
    @Test
    fun parse_readsBatchTasksAndSubagents() {
        val batchArgs = """
            {
              "action": "create",
              "tasks": [
                {"description": "调研方案A", "agent": "explorer", "prompt": "调研A"},
                {"description": "调研方案B", "agent": "writer", "prompt": "调研B"}
              ]
            }
        """.trimIndent()

        val batchResult = """
            {
              "status": "success",
              "data": {
                "count": 2,
                "subagents": [
                  {"id": "sub-101", "agent": "explorer", "description": "调研方案A", "tier": "light", "model": "m1"},
                  {"id": "sub-102", "agent": "writer", "description": "调研方案B", "tier": "standard", "model": "m2"}
                ]
              }
            }
        """.trimIndent()

        val parsed = parseSubAgentDispatch(batchArgs, batchResult)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.items.size)
        assertEquals("sub-101", parsed.items[0].subSessionId)
        assertEquals("explorer", parsed.items[0].agentName)
        assertEquals("调研方案A", parsed.items[0].description)
        assertEquals("sub-102", parsed.items[1].subSessionId)
        assertEquals("writer", parsed.items[1].agentName)
        assertEquals("调研方案B", parsed.items[1].description)
    }

    /** 批量任务在流式尚未返回结果时，能按 tasks 参数生成占位项。 */
    @Test
    fun parse_batchTasksStreamingWithoutResult() {
        val batchArgs = """
            {
              "action": "create",
              "tasks": [
                {"description": "任务1", "prompt": "指令1"},
                {"description": "任务2", "prompt": "指令2"}
              ]
            }
        """.trimIndent()

        val parsed = parseSubAgentDispatch(batchArgs, null)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.items.size)
        assertNull(parsed.items[0].subSessionId)
        assertEquals("任务1", parsed.items[0].description)
        assertNull(parsed.items[1].subSessionId)
        assertEquals("任务2", parsed.items[1].description)
    }
}
