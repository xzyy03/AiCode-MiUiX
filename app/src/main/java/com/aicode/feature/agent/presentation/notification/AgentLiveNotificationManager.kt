
package com.aicode.feature.agent.presentation.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
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
 * AI 工作期间的实时通知（Android 16 Live Updates）。
 *
 * 多会话聚合为一条通知；状态切换（思考/搜索/命令…）做节流合并；仅在 App 处于后台时展示，
 * 回到前台立即撤销。低版本设备由 [NotificationCompat] 自动降级为普通通知，
 * [NotificationCompat.Builder.setRequestPromotedOngoing] 是否被系统采纳由系统决定
 * （Android 16 起才可能提升到状态栏/锁屏）。
 */
@Singleton
class AgentLiveNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: LiveNotificationSettingsRepository
) {
    /** 一个运行中的会话在通知里的展示信息。 */
    data class RunningSession(
        val sessionId: String,
        val title: String,
        val statusLabel: String
    )

    private companion object {
        const val CHANNEL_ID = "agent_live"
        const val NOTIFICATION_ID = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = MutableStateFlow<List<RunningSession>>(emptyList())

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 回到前台：用户正在看，撤掉通知。
                scope.launch { render(emptyList()) }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { render(sessions.value) }
            }
        })
        // 节流由上游（AIAgentViewModel 的 500ms debounce）完成，这里直接渲染。
        scope.launch {
            sessions.collect { render(it) }
        }
    }

    /** 推入当前运行中的会话列表；空列表等价于撤销。 */
    fun update(running: List<RunningSession>) {
        sessions.value = running
    }

    /** 立即撤销（开关关闭、任务全部结束等）。 */
    fun dismiss() {
        sessions.value = emptyList()
        scope.launch { render(emptyList()) }
    }

    private suspend fun render(running: List<RunningSession>) {
        val manager = NotificationManagerCompat.from(context)
        if (running.isEmpty() || !settings.isEnabled() || inForeground()) {
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
        val title = if (running.size == 1) {
            running.first().statusLabel
        } else {
            context.getString(R.string.notification_agent_live_title_multi, running.size)
        }
        val body = running.joinToString("\n") { "${it.title} · ${it.statusLabel}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(if (running.size == 1) running.first().title else body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setRequestPromotedOngoing(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
