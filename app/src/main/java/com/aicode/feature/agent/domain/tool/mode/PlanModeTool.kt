package com.aicode.feature.agent.domain.tool.mode

import android.content.Context
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 进入或退出 PLAN（计划）模式。退出时恢复到进入 PLAN 之前的模式（见 [AgentContext.modeBeforePlan]）。
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    @param:ApplicationContext private val appContext: Context
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "申请进入或退出 PLAN（计划）模式。进入后仅做只读探索与方案设计。退出前先在当前回复中展示计划正文，再于同一轮回复调用本工具；不能跳过计划直接调用，也不能只展示计划就结束回复。用户审查批准后自动恢复进入前的 BUILD 或 AUTO 模式，成功结果的 data 包含模式说明。本工具只能在 PLAN 与进入前的模式之间切换；直接开启 AUTO 需用户在界面操作。目标自主运行期间不支持切换 PLAN。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "enter：进入只读规划；exit：先向用户展示计划，再调用工具提交审查，获批后恢复进入前的模式。",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "切换理由，供用户审查。退出时简述已向用户展示的计划，不能用此参数代替计划正文。",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error(appContext.getString(R.string.common_tool_missing_argument, "action"), "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error(appContext.getString(R.string.common_tool_missing_argument, "reason"), "MISSING_REASON")

        val entering = when (action) {
            "enter" -> true
            "exit" -> false
            else -> return ToolResult.Error(appContext.getString(R.string.plan_mode_invalid_action, action), "INVALID_ACTION")
        }

        if (entering && context.mode == AgentMode.PLAN) {
            return ToolResult.Success(JsonPrimitive(appContext.getString(R.string.plan_mode_already_active)))
        }
        if (!entering && context.mode != AgentMode.PLAN) {
            return ToolResult.Error(appContext.getString(R.string.plan_mode_not_active), "NOT_IN_PLAN")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error(appContext.getString(R.string.plan_mode_session_required), "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error(appContext.getString(R.string.plan_mode_session_missing), "SESSION_NOT_FOUND")

        // goal 自主循环进行中禁用 PLAN：该模式的退出需人工审查（[PlanApprovalManager]），与自主循环冲突。
        if (sessionEntity.goalStatus == GoalStatus.ACTIVE.name) {
            return ToolResult.Error(
                appContext.getString(R.string.plan_mode_goal_active),
                "GOAL_ACTIVE"
            )
        }

        // 进入 PLAN 时记下当时的模式，退出时恢复到它：从 AUTO 进入 PLAN 的规划往返结束后应回到 AUTO，
        // 而不是一律落回 BUILD。该字段只在处于 PLAN 时被读取，每次进入 PLAN 都会覆写，因此无需在退出时清理。
        val effectiveMode: AgentMode
        val modeBeforePlan: String?
        if (entering) {
            effectiveMode = AgentMode.PLAN
            modeBeforePlan = context.mode.name
        } else {
            effectiveMode = sessionEntity.modeBeforePlan
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.BUILD
            modeBeforePlan = sessionEntity.modeBeforePlan
        }

        // 保存到数据库。UI 层通过 flow 监听，会自动更新外观与后续流程的上下文。
        chatSessionDao.upsert(sessionEntity.copy(mode = effectiveMode.name, modeBeforePlan = modeBeforePlan))

        return ToolResult.Success(
            JsonPrimitive(
                if (entering) appContext.getString(R.string.plan_mode_entered)
                else appContext.getString(R.string.plan_mode_exited, effectiveMode.name)
            )
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val entering = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("enter", ignoreCase = true) ?: true
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: appContext.getString(R.string.plan_mode_reason_unspecified)
        val label = appContext.getString(if (entering) R.string.plan_mode_enter_action else R.string.plan_mode_exit_action)

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = appContext.getString(R.string.plan_mode_request_title),
            summary = appContext.getString(R.string.plan_mode_request_summary, label),
            details = appContext.getString(R.string.plan_mode_request_details, label, reason),
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}