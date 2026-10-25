package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影结果必须把搭车通知原样带给模型：通知在工具结果落库后即被 ack，
 * 一旦投影把它丢掉，模型就再也看不到（见 [modelToolResultText] 的注释）。
 */
class ModelProjectionTest {

    private val notification =
        """{"kind":"background_task","notice":"应用事件通知，不代表用户的新请求或授权。",""" +
            """"task_id":"tab1","title":"build","exit_code":1,"status":"failed",""" +
            """"summary":"后台任务「build」已结束（退出码 1）","hint":"已附带最后 50 行输出。"}"""

    private val editFileResult =
        """{"status":"success","data":{"status":"edited","path":"/repo/a.kt","replacements":1,""" +
            """"added_lines":2,"removed_lines":1,"hunks":[{"start_line":3,"added":2,"removed":1,""" +
            """"diff":"+a\n-b"}]}}"""

    @Test
    fun editFileKeepsPiggybackedNotifications() {
        val injected = editFileResult.dropLast(1) + ""","notifications":[$notification]}"""

        val text = modelToolResultText("editFile", injected)!!

        assertTrue(text.contains("Edited file successfully: /repo/a.kt"))
        assertTrue(text.contains("平台通知"))
        assertTrue(text.contains("后台任务「build」已结束（退出码 1）"))
        // 仍是投影（只带 diff 预览），不是完整 result
        assertTrue(text.contains("```diff"))
    }

    @Test
    fun writeFileKeepsPiggybackedNotifications() {
        val writeResult =
            """{"status":"success","data":{"status":"written","path":"/repo/b.kt",""" +
                """"created":"true","added_lines":3,"removed_lines":0,"lines_written":3},""" +
                """"notifications":[$notification]}"""

        val text = modelToolResultText("writeFile", writeResult)!!

        assertTrue(text.contains("Created file successfully: /repo/b.kt"))
        assertTrue(text.contains("后台任务「build」已结束（退出码 1）"))
    }

    @Test
    fun noNotificationsLeavesProjectionUnchanged() {
        val text = modelToolResultText("editFile", editFileResult)!!

        assertFalse(text.contains("平台通知"))
        assertFalse(text.contains("notifications"))
    }

    @Test
    fun emptyNotificationArrayIsIgnored() {
        val injected = editFileResult.dropLast(1) + ""","notifications":[]}"""

        assertFalse(modelToolResultText("editFile", injected)!!.contains("平台通知"))
    }

    @Test
    fun failedResultFallsBackToFullResult() {
        val errorResult =
            """{"status":"error","message":"编辑失败","code":"EDIT_ERROR","notifications":[$notification]}"""

        // 失败结果不投影，调用方用完整 result（其中已含 notifications），这里返回 null 即为正确回退。
        assertNull(modelToolResultText("editFile", errorResult))
    }

    @Test
    fun nonFileToolIsNotProjected() {
        assertNull(modelToolResultText("readFile", editFileResult))
    }
}
