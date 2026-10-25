package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃子代理集合的维护语义：并发槽位靠它计数，只要有一条路径忘了交回槽位，
 * 用户就会在没有任何子代理运行时被告知「已达上限」，直到进程重启。
 */
class SubAgentEventBusTest {

    private fun spawn(bus: SubAgentEventBus, id: String) = bus.emit(
        SubAgentEvent(subSessionId = id, parentSessionId = "parent", type = SubAgentEventType.SPAWNED)
    )

    @Test
    fun spawn_marksActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertFalse(bus.isFull)
    }

    @Test
    fun terminalEvents_freeSlot() {
        listOf(
            SubAgentEventType.COMPLETED,
            SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED
        ).forEach { type ->
            val bus = SubAgentEventBus()
            spawn(bus, "sub-1")
            bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = type))

            assertEquals("$type 应交回槽位", emptySet<String>(), bus.activeSubSessionIds.value)
        }
    }

    @Test
    fun isFull_atMaxRunning() {
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { spawn(bus, "sub-$it") }

        assertTrue(bus.isFull)

        bus.emit(SubAgentEvent(subSessionId = "sub-0", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertFalse("腾出一个后应能再派发", bus.isFull)
    }

    /** 用户手动停止走 release：必须真的交回槽位，否则上限被永久占用。 */
    @Test
    fun release_freesSlotAndReportsWhetherItWasActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 幂等且如实回报：TaskTool 的 stop 已经 emit 过 STOPPED 时，ViewModel 随后的 release
     * 必须返回 false，否则会给父代理重复投递一条「已被终止」通知。
     */
    @Test
    fun release_isIdempotentForUnknownId() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        bus.release("sub-1")

        assertFalse(bus.release("sub-1"))
        assertFalse(bus.release("never-existed"))
    }

    @Test
    fun release_doesNotTouchOtherSubAgents() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        spawn(bus, "sub-2")

        bus.release("sub-1")

        assertEquals(setOf("sub-2"), bus.activeSubSessionIds.value)
    }

    // ── 预留槽位：task(create) 的「检查+占位」必须原子，否则同一批并行工具会超额创建 ──

    @Test
    fun reserve_countsAgainstLimitUntilSpawned() {
        val bus = SubAgentEventBus()
        assertTrue(bus.tryReserveSlots(3))

        // 预留未落地时 active 集合还是空，但计数必须把预留算进去，否则并行的创建会双双通过检查
        assertEquals(0, bus.activeSubSessionIds.value.size)
        assertEquals(3, bus.activeCount)
        assertFalse("3 预留 + 3 再要 > 5，应拒绝", bus.tryReserveSlots(3))
        assertTrue("3 预留 + 2 再要 = 5，应允许", bus.tryReserveSlots(2))
    }

    @Test
    fun spawned_consumesOneReservation() {
        val bus = SubAgentEventBus()
        bus.tryReserveSlots(2)

        spawn(bus, "sub-1")

        // 1 个已落地 + 1 个尚未落地的预留，都算在占用里
        assertEquals(2, bus.activeCount)
        spawn(bus, "sub-2")
        assertEquals(2, bus.activeCount)
        // 无预留时 SPAWNED 不应把计数减到负数
        spawn(bus, "sub-3")
        assertEquals(3, bus.activeCount)
    }

    @Test
    fun releaseSlots_returnsUnusedReservations() {
        val bus = SubAgentEventBus()
        bus.tryReserveSlots(3)
        spawn(bus, "sub-1")

        bus.releaseSlots(2)

        assertEquals(1, bus.activeCount)
        // 归还多余的不应减到负
        bus.releaseSlots(10)
        assertEquals(1, bus.activeCount)
    }

    @Test
    fun reserve_beyondMaxIsRejected() {
        val bus = SubAgentEventBus()
        assertFalse(bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING + 1))
        assertTrue(bus.tryReserveSlots(SubAgentEventBus.MAX_RUNNING))
    }

    /** 并发 reserve 总占位不会超过上限：串行循环里每次读到的都是最新计数。 */
    @Test
    fun reserve_concurrentRequestsDoNotOversubscribe() {
        val bus = SubAgentEventBus()
        val acquired = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map {
            Thread {
                // 每个线程反复抢 1 个名额再立即归还，与其它线程交错执行
                repeat(50) {
                    if (bus.tryReserveSlots(1)) bus.releaseSlots(1)
                }
                // 最后真实占住：全部线程占完后总数不得超过上限
                if (bus.tryReserveSlots(1)) acquired.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发下恰好只有 5 个线程能占住名额", SubAgentEventBus.MAX_RUNNING, acquired.get())
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
    }
}
