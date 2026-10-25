package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通知两种送达形态的格式：兜底的 user 消息文本（UI 提示条按 <status>/<summary> 正则提取，
 * 格式变了提示条会失效）与搭车用的 `notifications` JSON 数组。
 */
class AgentNotificationFormatterTest {

    private val backgroundTask = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = "term-2",
        title = "gradle build",
        outcome = NotificationOutcome.FAILED,
        command = "./gradlew assemble",
        exitCode = 1,
        tailOutput = "error: <unresolved> & failed"
    )

    private val subAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-1",
        title = "调研缓存实现",
        outcome = NotificationOutcome.COMPLETED
    )

    private val stoppedSubAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-2",
        title = "重构缓存层",
        outcome = NotificationOutcome.STOPPED,
        detail = "用户在界面上手动停止了这个子代理，任务未完成。"
    )

    /** 完成通知自带摘要与改动文件：父代理据此推进，无需再 read 一次。 */
    private val digestSubAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-3",
        title = "修复登录 bug",
        outcome = NotificationOutcome.COMPLETED,
        resultExcerpt = "已修复 <登录> 空指针 & 补了单测",
        changedFiles = listOf("~/workspace/A.kt", "~/workspace/B.kt")
    )

    private val modeChange = PendingNotification(
        kind = AgentNotificationKind.MODE_CHANGE,
        sourceId = "user",
        title = "AUTO",
        outcome = NotificationOutcome.COMPLETED,
        newMode = AgentMode.AUTO
    )

    private val userMessage = PendingNotification(
        kind = AgentNotificationKind.USER_MESSAGE,
        sourceId = "session-1",
        title = "",
        outcome = NotificationOutcome.COMPLETED,
        message = "改用 <方案 B> & 注意兼容性"
    )

    @Test
    fun message_singleBackgroundTask_hasFenceAndFields() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<task-id>term-2</task-id>"))
        assertTrue(text.contains("<exit-code>1</exit-code>"))
        assertTrue(text.contains("<status>failed</status>"))
        assertTrue(text.contains("<summary>后台任务「gradle build」已结束（退出码 1）</summary>"))
        assertTrue(text.contains("terminal(action=\"read\", tab_id=\"term-2\")"))
    }

    /** tail-output 里的裸尖括号会被 UI 的 <status>/<summary> 正则误匹配，必须转义。 */
    @Test
    fun message_escapesTailOutput() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.contains("&lt;unresolved&gt;"))
        assertTrue(text.contains("&amp;"))
        assertFalse(text.contains("<unresolved>"))
    }

    @Test
    fun message_subAgentUsesSubagentTags() {
        val text = AgentNotificationFormatter.buildMessage(listOf(subAgent))

        assertTrue(text.contains("<subagent-notification>"))
        assertTrue(text.contains("<subagent-id>sub-1</subagent-id>"))
        assertTrue(text.contains("<summary>子代理「调研缓存实现」已执行完成</summary>"))
        assertTrue(text.contains("task(action=\"read\", id=\"sub-1\")"))
    }

    /** 带摘要的完成通知：摘要与改动文件都要在，且提示从「去读」改成「通常无需再读」。 */
    @Test
    fun message_subAgentWithDigestCarriesExcerptAndFiles() {
        val text = AgentNotificationFormatter.buildMessage(listOf(digestSubAgent))

        assertTrue(text.contains("<changed-files>~/workspace/A.kt, ~/workspace/B.kt</changed-files>"))
        assertTrue(text.contains("<result-excerpt>已修复 &lt;登录&gt; 空指针 &amp; 补了单测</result-excerpt>"))
        assertFalse(text.contains("<登录>"))
        assertTrue(text.contains("结果摘要已随通知给出"))
        // 完整报告仍可读，提示保留 task(read) 这条兜底路径
        assertTrue(text.contains("task(action=\"read\", id=\"sub-3\")"))
    }

    @Test
    fun jsonArray_subAgentWithDigestCarriesFields() {
        val obj = AgentNotificationFormatter.buildJsonArray(listOf(digestSubAgent))[0] as JsonObject

        assertEquals("修复登录 bug", (obj["subagent_title"] as JsonPrimitive).content)
        assertEquals("已修复 <登录> 空指针 & 补了单测", (obj["result_excerpt"] as JsonPrimitive).content)
        val files = obj["changed_files"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, files.size)
        assertEquals("~/workspace/A.kt", (files[0] as JsonPrimitive).content)
        assertTrue((obj["hint"] as JsonPrimitive).content.contains("结果摘要已随通知给出"))
    }

    /** 无摘要时不得凭空造出空字段，提示保持原有「去读一次」的措辞。 */
    @Test
    fun jsonArray_subAgentWithoutDigestOmitsFields() {
        val obj = AgentNotificationFormatter.buildJsonArray(listOf(subAgent))[0] as JsonObject

        assertEquals(null, obj["result_excerpt"])
        assertEquals(null, obj["changed_files"])
        assertTrue((obj["hint"] as JsonPrimitive).content.contains("读取子代理的最后输出"))
    }

    @Test
    fun message_mergesMixedKinds() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask, subAgent))

        assertTrue(text.contains("应用通知（2 条）"))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<subagent-notification>"))
        // 两类都在时两句提示都要给出，AI 才知道各自怎么取回结果
        assertTrue(text.contains("tab_id=\"term-2\""))
        assertTrue(text.contains("id=\"sub-1\""))
    }

    @Test
    fun message_mixedUserInputAndEvents_distinguishesTheirAuthority() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask, userMessage))

        assertTrue(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertTrue(text.contains("其中 user-message 是用户补充的输入"))
        assertTrue(text.contains("其他事件本身不代表用户的新请求或授权"))
        assertTrue(text.contains("<user-message>"))
        assertTrue(text.contains("改用 &lt;方案 B&gt; &amp; 注意兼容性"))
        assertFalse(text.contains("这些是后台完成事件，不是来自用户的消息"))
    }

    @Test
    fun jsonArray_carriesStatusSummaryAndHint() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask, subAgent))

        assertEquals(2, array.size)
        val first = array[0] as JsonObject
        assertEquals("background_task", (first["kind"] as JsonPrimitive).content)
        assertEquals("term-2", (first["task_id"] as JsonPrimitive).content)
        assertEquals("failed", (first["status"] as JsonPrimitive).content)
        assertTrue((first["summary"] as JsonPrimitive).content.contains("gradle build"))
        assertTrue((first["hint"] as JsonPrimitive).content.contains("terminal(action=\"read\""))
        // notice 是围栏说明：AI 只看单项也能知道这不是用户消息
        assertTrue((first["notice"] as JsonPrimitive).content.contains("不代表用户的新请求或授权"))

        val second = array[1] as JsonObject
        assertEquals("subagent", (second["kind"] as JsonPrimitive).content)
        assertEquals("sub-1", (second["subagent_id"] as JsonPrimitive).content)
        assertEquals("completed", (second["status"] as JsonPrimitive).content)
    }

    /** JSON 形态由 kotlinx 负责转义，tail_output 应保持原文，不做 XML 转义。 */
    @Test
    fun jsonArray_keepsTailOutputRaw() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask))
        val obj = array[0] as JsonObject

        assertEquals("error: <unresolved> & failed", (obj["tail_output"] as JsonPrimitive).content)
    }

    /**
     * 被用户手动停止不能报成 failed：那会让 AI 把人为中断当成故障去排查或直接重试。
     * detail 携带停止原因，免得父代理只知道「没成」。
     */
    @Test
    fun message_stoppedSubAgentCarriesReasonAndNoRetryHint() {
        val text = AgentNotificationFormatter.buildMessage(listOf(stoppedSubAgent))

        assertTrue(text.contains("<status>stopped</status>"))
        assertTrue(text.contains("<summary>子代理「重构缓存层」已被用户手动终止，任务未完成</summary>"))
        assertTrue(text.contains("<detail>用户在界面上手动停止了这个子代理，任务未完成。</detail>"))
        assertTrue(text.contains("重新派发前需用户确认"))
    }

    @Test
    fun jsonArray_stoppedSubAgentCarriesDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(stoppedSubAgent))
        val obj = array[0] as JsonObject

        assertEquals("stopped", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["detail"] as JsonPrimitive).content.contains("手动停止"))
    }

    /** 正常完成不带 detail，避免白白占用上下文。 */
    @Test
    fun jsonArray_completedSubAgentHasNoDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(subAgent))
        val obj = array[0] as JsonObject

        assertEquals(null, obj["detail"])
    }

    /** 模式切换通知：XML 用 <mode-change>，status 取 changed（UI 据此按非失败渲染）。 */
    @Test
    fun message_modeChangeUsesModeChangeTag() {
        val text = AgentNotificationFormatter.buildMessage(listOf(modeChange))

        assertTrue(text.contains("<mode-change>"))
        assertTrue(text.contains("<new-mode>AUTO</new-mode>"))
        assertTrue(text.contains("<status>changed</status>"))
        assertTrue(text.contains("<summary>用户已将模式切换为 AUTO</summary>"))
    }

    @Test
    fun jsonArray_modeChangeCarriesNewMode() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(modeChange))
        val obj = array[0] as JsonObject

        assertEquals("mode_change", (obj["kind"] as JsonPrimitive).content)
        assertEquals("AUTO", (obj["new_mode"] as JsonPrimitive).content)
        assertEquals("changed", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["hint"] as JsonPrimitive).content.contains("新模式"))
    }

    /**
     * 用户插话作为 user 消息落库时直接输出原文、不带系统通知前缀——否则 UI 会渲染成
     * 系统提示条、完成通知正文也会误判。
     */
    @Test
    fun message_userMessageIsPlainUserText() {
        val text = AgentNotificationFormatter.buildMessage(listOf(userMessage))

        assertEquals(userMessage.message, text)
        assertFalse(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertFalse(text.contains("不是来自用户的消息"))
    }

    /** 搭车注入的 JSON 形态：kind=user_message，notice 说明它是用户本人输入而非系统事件。 */
    @Test
    fun jsonArray_userMessageCarriesUserNotice() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(userMessage))
        val obj = array[0] as JsonObject

        assertEquals("user_message", (obj["kind"] as JsonPrimitive).content)
        assertEquals("message", (obj["status"] as JsonPrimitive).content)
        assertEquals(userMessage.message, (obj["message"] as JsonPrimitive).content)
        assertTrue((obj["notice"] as JsonPrimitive).content.contains("用户"))
        assertFalse((obj["notice"] as JsonPrimitive).content.contains("不是来自用户的消息"))
    }
}
