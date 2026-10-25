package com.aicode.feature.agent.domain.tool.goal

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 更新当前会话持续目标（goal）的生命周期状态，供自主循环中的 AI 自判使用。
 *
 * 只在会话存在激活目标时可用：
 * - `action=complete`：AI 判定目标已达成（reason 需给出验证证据），停止自主循环；
 * - `action=blocked`：受阻、需要用户介入（reason 说明原因），停止自主循环。
 */
class GoalStatusTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "goalStatus"
    override val description = "更新当前会话目标（goal）的状态。仅当会话存在激活中的目标时可用：" +
        "action=\"complete\" 表示你已判定目标达成（在 reason 里给出验证证据）；" +
        "action=\"blocked\" 表示受阻、需要用户介入（在 reason 里说明原因）。" +
        "两种情况都会停止自主循环，把控制权交还用户。未达成且需继续时不要调用本工具。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：'complete' 判定目标达成，'blocked' 受阻需用户介入",
            required = true,
            enum = listOf("complete", "blocked")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "完成时的验证证据，或受阻的原因；将展示给用户",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val targetStatus = when (action) {
            "complete" -> GoalStatus.COMPLETED
            "blocked" -> GoalStatus.BLOCKED
            else -> return ToolResult.Error("无效的 action：$action，只能是 'complete' 或 'blocked'", "INVALID_ACTION")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话 ID，无法更新目标状态", "NO_SESSION")
        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        if (sessionEntity.goal.isNullOrBlank()) {
            return ToolResult.Error("当前会话未设定目标，无需更新状态", "NO_GOAL")
        }
        if (sessionEntity.goalStatus != GoalStatus.ACTIVE.name) {
            return ToolResult.Error("当前目标不是激活状态，无需更新", "GOAL_NOT_ACTIVE")
        }

        chatSessionDao.updateGoalStatus(sessionId, targetStatus.name)

        val message = when (targetStatus) {
            GoalStatus.COMPLETED -> "已将目标标记为「已完成」，自主循环结束。" +
                (if (reason.isNotEmpty()) "完成说明：$reason" else "")
            else -> "已将目标标记为「受阻」，自主循环暂停，等待用户介入。" +
                (if (reason.isNotEmpty()) "受阻原因：$reason" else "")
        }
        return ToolResult.Success(JsonPrimitive(message))
    }
}
