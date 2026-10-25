package com.aicode.feature.agent.presentation.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 审批通知上按钮的接收器：把用户在通知上的选择直接回传给挂起的审批请求，无需回到 App。
 *
 * 按通知绑定的请求 id 回传，过期请求不会影响其它待决审批。
 */
@AndroidEntryPoint
class AgentApprovalActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var toolPermissionManager: ToolPermissionManager

    @Inject
    lateinit var planApprovalManager: PlanApprovalManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { value -> ApprovalKind.entries.find { it.name == value } } ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        when (kind) {
            ApprovalKind.PERMISSION -> {
                val permissionChoice = PermissionChoice.entries.find { it.name == choice } ?: return
                if (permissionChoice != PermissionChoice.ONCE && permissionChoice != PermissionChoice.REJECT) return
                toolPermissionManager.resolve(requestId, permissionChoice)
            }
            ApprovalKind.PLAN -> {
                val planChoice = PlanApprovalChoice.entries.find { it.name == choice } ?: return
                planApprovalManager.resolve(requestId, planChoice)
            }
            ApprovalKind.QUESTION -> Unit
        }
    }

    companion object {
        const val ACTION = "com.aicode.action.AGENT_APPROVAL"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_CHOICE = "extra_choice"
    }
}
