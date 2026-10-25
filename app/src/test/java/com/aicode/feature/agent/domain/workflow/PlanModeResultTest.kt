package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanModeResultTest {

    @Test
    fun appendPlanModeNotice_enterPlan_appendsTrimmedNoticeInsideData() {
        val raw = """{"status":"success","data":"已进入 PLAN 模式"}"""
        val notice = "\n\n  当前处于 PLAN 模式，仅进行只读探索与规划。  \n"

        val result = Json.parseToJsonElement(appendPlanModeNotice(raw, notice)).jsonObject

        assertEquals("success", result["status"]!!.jsonPrimitive.content)
        assertEquals(
            "已进入 PLAN 模式\n\n当前处于 PLAN 模式，仅进行只读探索与规划。",
            result["data"]!!.jsonPrimitive.content
        )
        assertEquals(setOf("status", "data"), result.keys)
    }

    @Test
    fun appendPlanModeNotice_approvedExit_appendsNoticeInsideData() {
        val raw = """{"status":"success","data":"计划已获批准"}"""

        for (mode in listOf("BUILD", "AUTO")) {
            val notice = "【模式切换】计划已获批准，已恢复 $mode 模式。"

            val result = Json.parseToJsonElement(appendPlanModeNotice(raw, notice)).jsonObject

            assertEquals("success", result["status"]!!.jsonPrimitive.content)
            assertEquals("计划已获批准\n\n$notice", result["data"]!!.jsonPrimitive.content)
            assertEquals(setOf("status", "data"), result.keys)
        }
    }

    @Test
    fun appendPlanModeNotice_quotesNewlinesAndBackslashes_roundTripAsJsonString() {
        val originalData = "结果：\"PLAN\"\n路径 C:\\plans\\draft"
        val notice = "\n  使用 \"只读\" 模式。\n保留路径 C:\\plans\\next\n  "
        val raw = buildJsonObject {
            put("status", "success")
            put("data", originalData)
        }.toString()

        val result = Json.parseToJsonElement(appendPlanModeNotice(raw, notice)).jsonObject

        assertEquals(originalData + "\n\n" + notice.trim(), result["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun appendPlanModeNotice_otherFields_preservesOriginalJsonValues() {
        val raw = """{"status":"success","data":"结果","images":[{"path":"/tmp/plan.png","mimeType":"image/png"}],"notifications":[{"kind":"background_task","detail":"已完成"}],"extra":{"items":[1,true,null,"value"],"nested":{"key":"value"}},"count":2,"optional":null}"""
        val original = Json.parseToJsonElement(raw).jsonObject

        val result = Json.parseToJsonElement(appendPlanModeNotice(raw, "说明")).jsonObject

        assertEquals(original.keys, result.keys)
        assertEquals(original.filterKeys { it != "data" }, result.filterKeys { it != "data" })
        assertEquals("结果\n\n说明", result["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun appendPlanModeNotice_thenInjectNotification_keepsAppendedDataAndOtherFields() {
        val raw = """{"status":"success","data":"已进入 PLAN 模式","images":[],"extra":{"request_id":"plan-1"}}"""
        val notice = "当前处于 PLAN 模式。\n先整理计划，再申请退出。"
        val appended = appendPlanModeNotice(raw, notice)
        val event = PendingNotification(
            kind = AgentNotificationKind.BACKGROUND_TASK,
            sourceId = "term-1",
            title = "检查工作区",
            outcome = NotificationOutcome.COMPLETED,
            exitCode = 0
        )

        val injected = DefaultAgentEventInjector().inject(appended, listOf(event))
        val result = Json.parseToJsonElement(injected).jsonObject

        assertEquals(
            Json.parseToJsonElement(appended).jsonObject,
            result.filterKeys { it != "notifications" }
        )
        assertEquals("已进入 PLAN 模式\n\n$notice", result["data"]!!.jsonPrimitive.content)
        val notifications = result["notifications"]!!.jsonArray
        assertEquals(1, notifications.size)
        assertEquals("background_task", notifications[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("term-1", notifications[0].jsonObject["task_id"]!!.jsonPrimitive.content)
    }
}
