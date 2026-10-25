
package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.liveNotificationDataStore by preferencesDataStore(
    name = "live_notification_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「AI 工作期间的实时通知」开关。默认关闭——需用户在「设置 → 后台运行」手动开启并授予通知权限。
 *
 * 开启后由 [com.aicode.feature.agent.presentation.AIAgentViewModel] 把运行中的会话状态推给
 * [com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager]，
 * 在 App 处于后台时展示实时通知。DataStore 用法与 [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class LiveNotificationSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("live_notification_enabled")
    }

    /** 当前持久化的开关流；未设置时回退到 false（默认关闭，需手动开启）。 */
    val enabledFlow: Flow<Boolean> = context.liveNotificationDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 写入开关。 */
    suspend fun setEnabled(enabled: Boolean) {
        context.liveNotificationDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** 读取一次当前值。 */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** 备份快照：当前开关是否开启。 */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** 从备份还原开关。 */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
