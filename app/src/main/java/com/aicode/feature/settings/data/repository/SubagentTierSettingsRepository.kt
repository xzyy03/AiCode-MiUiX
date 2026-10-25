package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.agent.domain.subagent.SubagentModelTier
import com.aicode.feature.agent.domain.subagent.SubagentTierSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.subagentTierDataStore by preferencesDataStore(
    name = "subagent_tier_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 子代理模型档位（轻量 / 标准 / 强力）到 provider + model 的映射。
 *
 * 与「标题总结模型」等单用途配置同构，但一个用途有三个槽位，故不复用
 * [ModelSelectionSettingsRepository]（那个基类固定一对 key），改为单 DataStore 内按档位分组存。
 *
 * 未配置的档位返回空字符串（[SubagentTierSelection.isConfigured] 为 false），派发时按降级链处理。
 */
@Singleton
class SubagentTierSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val dataStore get() = context.subagentTierDataStore

    private fun providerKey(tier: SubagentModelTier) =
        stringPreferencesKey("subagent_${tier.apiValue}_provider_id")

    private fun modelKey(tier: SubagentModelTier) =
        stringPreferencesKey("subagent_${tier.apiValue}_model")

    fun selectionFlow(tier: SubagentModelTier): Flow<SubagentTierSelection> =
        dataStore.data.map { prefs -> prefs.readSelection(tier) }

    /** 一次读出三档快照：派发子代理时用（冷读，避免每条流各等一次）。 */
    suspend fun snapshot(): Map<SubagentModelTier, SubagentTierSelection> {
        val prefs = dataStore.data.first()
        return SubagentModelTier.entries.associateWith { tier -> prefs.readSelection(tier) }
    }

    /** 写入某档位的模型；两个参数都为空等同 [clear]。 */
    suspend fun setSelection(tier: SubagentModelTier, providerId: String, model: String) {
        dataStore.edit { prefs ->
            prefs[providerKey(tier)] = providerId
            prefs[modelKey(tier)] = model
        }
    }

    /** 清空某档位——该档回退到「未配置」，派发时按降级链处理。 */
    suspend fun clear(tier: SubagentModelTier) {
        dataStore.edit { prefs ->
            prefs.remove(providerKey(tier))
            prefs.remove(modelKey(tier))
        }
    }

    /**
     * 提供商被删除或停用时清掉引用它的档位，避免派发时才发现模型不可用。
     * 返回被清掉的档位，供调用方提示用户。
     */
    suspend fun clearTiersUsingProvider(providerId: String): List<SubagentModelTier> {
        val affected = snapshot().filterValues { it.providerId == providerId }.keys.toList()
        affected.forEach { clear(it) }
        return affected
    }

    private fun androidx.datastore.preferences.core.Preferences.readSelection(
        tier: SubagentModelTier
    ): SubagentTierSelection = SubagentTierSelection(
        providerId = this[providerKey(tier)] ?: "",
        model = this[modelKey(tier)] ?: ""
    )
}
