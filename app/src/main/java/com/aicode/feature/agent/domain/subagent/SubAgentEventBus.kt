package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理事件类型。前五种为生命周期事件；后两种为收发消息事件，只投递内容，不影响活跃集合。
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /**
     * 子代理会话已被 task(action="del") 删除（含全部消息）。与 STOPPED 的区别：
     * 收集方不得再给该会话补送通知/起后续轮次（会话已不存在，只会烧一轮 LLM 并落孤儿消息），
     * 而应清掉其全部运行时状态。
     */
    DELETED,

    /** 主会话向子代理发来一条消息（发送方是父会话）。 */
    MESSAGE_FROM_PARENT,

    /** 子代理向主会话发来一条消息（发送方是子会话）。 */
    MESSAGE_FROM_SUB
}

/**
 * 子代理生命周期事件。
 *
 * @property subSessionId 子代理会话 id。
 * @property parentSessionId 父会话 id（子会话记录里 parentId）。
 * @property type 事件类型。
 * @property detail 附加说明：SPAWNED 为任务指令；COMPLETED/FAILED 为子代理最终输出/错误信息；
 *   MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB 为消息正文。
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = ""
)

/**
 * 子代理事件总线：TaskTool 发出 SPAWNED（子代理已创建），ViewModel 收集后自动
 * 在子会话上启动 AI 工作流；子会话工作流结束时 ViewModel 再发 COMPLETED/FAILED，
 * 父会话据此注入后台通知（类比 terminal 的 notify=true 异步回调）。
 *
 * 同时维护活跃子代理会话 id 集合，供 TaskTool 查询并发上限（最多 5 个运行中）。
 * 活跃集合与预留计数都在 [slotLock] 内更新：同一批工具并行执行时多个 task(create)
 * 会并发触达这里，读-改-写不持锁会丢更新（多占或漏占名额）。
 */
@Singleton
class SubAgentEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    private val slotLock = Any()

    /** 当前活跃（运行中）的子代理会话 id 集合。 */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /** 已通过 [tryReserveSlots] 占住、尚未落到具体会话 id 上的名额数。 */
    @Volatile private var reservedSlots = 0

    /** 运行中的子代理数量（含已预留未创建的）。 */
    val activeCount: Int get() = synchronized(slotLock) { _activeSubSessionIds.value.size + reservedSlots }

    /** 是否已达并发上限（含已预留未创建的）。 */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    /**
     * 为一次批量创建原子地占住 [count] 个并发名额：检查与占位在同一锁内完成，
     * 并行的 task(create) 不会双双通过检查后超额创建。占位后随每个子会话 emit(SPAWNED)
     * 转为正式活跃；创建中途失败用 [releaseSlots] 归还剩余名额。
     */
    fun tryReserveSlots(count: Int): Boolean {
        if (count <= 0) return true
        synchronized(slotLock) {
            val active = _activeSubSessionIds.value.size
            if (active + reservedSlots + count > MAX_RUNNING) return false
            reservedSlots += count
            return true
        }
    }

    /** 归还 [tryReserveSlots] 占住但未用掉的名额（每个 SPAWNED 自动消耗一个）。 */
    fun releaseSlots(count: Int) {
        if (count <= 0) return
        synchronized(slotLock) {
            reservedSlots = (reservedSlots - count).coerceAtLeast(0)
        }
    }

    /**
     * 直接把某个子代理移出活跃集合，不广播事件；返回它此前是否处于活跃状态。
     *
     * 用户在界面上手动停止或删除运行中的子会话时走这条路径：不能改用 [emit]，
     * 因为 ViewModel 收到 STOPPED 事件后又会回调 stopAgentSession，形成无限循环。
     * 返回值同时用于区分「用户手动终止」与「TaskTool 已处理过」（后者返回 false），
     * 避免向父代理重复投递通知。
     */
    fun release(subSessionId: String): Boolean = synchronized(slotLock) {
        var wasActive = false
        _activeSubSessionIds.update { current ->
            wasActive = subSessionId in current
            if (wasActive) current - subSessionId else current
        }
        wasActive
    }

    fun emit(event: SubAgentEvent) {
        synchronized(slotLock) {
            // 同步维护活跃集合；SPAWNED 同时把一个预留名额转为正式活跃
            when (event.type) {
                SubAgentEventType.SPAWNED -> {
                    _activeSubSessionIds.update { it + event.subSessionId }
                    reservedSlots = (reservedSlots - 1).coerceAtLeast(0)
                }
                SubAgentEventType.COMPLETED, SubAgentEventType.FAILED,
                SubAgentEventType.STOPPED, SubAgentEventType.DELETED -> {
                    _activeSubSessionIds.update { it - event.subSessionId }
                }
                SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                    // 收发消息不改变运行状态，活跃集合保持不变。
                }
            }
        }
        // 缓冲满时丢弃事件会造成「子会话已建但永不启动」「父代理等不到完成通知」，
        // 必须留痕便于排查（正常 5 个并发远够 64 的缓冲，出现即异常）。
        if (!_events.tryEmit(event)) {
            FileLogger.e(TAG, "事件缓冲已满，事件被丢弃: ${event.type} sub=${event.subSessionId}")
        }
    }

    companion object {
        private const val TAG = "SubAgentEventBus"

        /** 同时运行的子代理上限。判定以此为准，工具层只引用不自己再写一份。 */
        const val MAX_RUNNING = 5
    }
}
