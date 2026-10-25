package com.aicode.feature.agent.presentation.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权审批通知：AI 在后台阻塞等待用户确认（工具授权 / askUserQuestion 提问 / 退出 PLAN 计划审批）时弹出。
 *
 * 多会话聚合为一条通知；仅在 App 处于后台且开关开启时展示，回到前台或全部解决后立即撤销。
 * 与 [AgentLiveNotificationManager] 的静默常驻通知不同，本通知需要弹窗+声音提醒用户处理。
 */
@Singleton
class AgentApprovalNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApprovalNotificationSettingsRepository
) {
    /** 待审批项的类型，决定通知上挂哪组按钮。 */
    enum class ApprovalKind { PERMISSION, QUESTION, PLAN }

    /** 一个等待用户审批的会话在通知里的展示信息。 */
    data class PendingApproval(
        val sessionId: String,
        val sessionTitle: String,
        val label: String,
        val kind: ApprovalKind,
        val requestId: String?
    )

    private companion object {
        const val CHANNEL_ID = "agent_approval"
        const val NOTIFICATION_ID = 1003
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val approvals = MutableStateFlow<List<PendingApproval>>(emptyList())

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 回到前台：用户正在看，撤掉通知。
                scope.launch { render(emptyList()) }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { render(approvals.value) }
            }
        })
        scope.launch {
            approvals.collect { render(it) }
        }
    }

    /** 推入当前等待审批的会话列表；空列表等价于撤销。 */
    fun update(pending: List<PendingApproval>) {
        approvals.value = pending
    }

    /** 立即撤销（开关关闭、全部解决等）。 */
    fun dismiss() {
        approvals.value = emptyList()
        scope.launch { render(emptyList()) }
    }

    private suspend fun render(pending: List<PendingApproval>) {
        val manager = NotificationManagerCompat.from(context)
        if (pending.isEmpty() || !settings.isEnabled() || inForeground()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (pending.size == 1) {
            context.getString(R.string.notification_approval_title)
        } else {
            context.getString(R.string.notification_approval_multi_title, pending.size)
        }
        val body = pending.joinToString("\n") { "${it.sessionTitle} · ${it.label}" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        pending.singleOrNull()?.let { single ->
            val requestId = single.requestId ?: return@let
            when (single.kind) {
                ApprovalKind.PERMISSION -> {
                    builder.addAction(0, context.getString(R.string.notification_approval_action_reject), actionIntent(ApprovalKind.PERMISSION, PermissionChoice.REJECT.name, requestId, 11))
                    builder.addAction(0, context.getString(R.string.notification_approval_action_allow), actionIntent(ApprovalKind.PERMISSION, PermissionChoice.ONCE.name, requestId, 12))
                }
                ApprovalKind.PLAN -> {
                    builder.addAction(0, context.getString(R.string.notification_approval_action_approve_plan), actionIntent(ApprovalKind.PLAN, PlanApprovalChoice.APPROVE.name, requestId, 13))
                    builder.addAction(0, context.getString(R.string.notification_approval_action_reject), actionIntent(ApprovalKind.PLAN, PlanApprovalChoice.REFINE.name, requestId, 14))
                }
                ApprovalKind.QUESTION -> Unit
            }
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    /**
     * PendingIntent 的等价判断忽略 extras，故 data 必须包含请求 id 和选择，
     * 避免新通知更新旧按钮，使旧通知意外批准后来的请求。
     */
    private fun actionIntent(kind: ApprovalKind, choice: String, requestId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AgentApprovalActionReceiver::class.java).apply {
            action = AgentApprovalActionReceiver.ACTION
            data = Uri.Builder().scheme("aicode").authority("approval")
                .appendPath(kind.name).appendPath(requestId).appendPath(choice).build()
            putExtra(AgentApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(AgentApprovalActionReceiver.EXTRA_KIND, kind.name)
            putExtra(AgentApprovalActionReceiver.EXTRA_CHOICE, choice)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
