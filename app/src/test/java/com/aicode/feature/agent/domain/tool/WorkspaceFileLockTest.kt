package com.aicode.feature.agent.domain.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * [WorkspaceFileLock]：同一逻辑路径串行、不同路径并行、条目可回收、多键不死锁。
 */
class WorkspaceFileLockTest {

    private val lock = WorkspaceFileLock()

    @Test
    fun sameKey_serializes() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val jobs = (1..5).map { i ->
            launch {
                lock.withLock("~/workspace/a.kt") {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, now) }
                    delay(10)
                    order.add("t$i")
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.joinAll()

        assertEquals("同一把锁下不应有并发进入", 1, maxConcurrent.get())
        assertEquals(5, order.size)
    }

    @Test
    fun differentKeys_runConcurrently() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..4).map { i ->
            launch {
                lock.withLock("~/workspace/f$i.kt") {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, now) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.joinAll()

        assertTrue("不同路径不应互相阻塞，实测最大并发=${maxConcurrent.get()}", maxConcurrent.get() > 1)
    }

    @Test
    fun entriesAreReclaimedAfterUse() = runTest {
        lock.withLock("~/workspace/a.kt") { /* 什么都不做 */ }
        assertEquals("无持有者也无等待者时条目应被回收", 0, lock.trackedKeyCount())

        lock.withLock(listOf("a", "b", "c")) { }
        assertEquals(0, lock.trackedKeyCount())
    }

    @Test
    fun entriesSurviveWhileWaiterIsQueued() = runTest {
        val started = AtomicInteger(0)
        val first = async {
            lock.withLock("k") {
                started.incrementAndGet()
                delay(30)
            }
        }
        // 让 first 先拿到锁
        while (started.get() == 0) delay(1)

        val second = async {
            lock.withLock("k") { started.incrementAndGet() }
        }
        // second 此刻在等待，条目必须还在（否则两者会各持一把不同的锁）
        assertTrue("有等待者时条目不应被回收", lock.trackedKeyCount() >= 1)

        first.await()
        second.await()
        assertEquals(2, started.get())
        assertEquals(0, lock.trackedKeyCount())
    }

    @Test
    fun exceptionInBlock_stillReleasesLock() = runTest {
        runCatching {
            lock.withLock("k") { throw IllegalStateException("boom") }
        }
        assertEquals("异常路径也必须释放并回收", 0, lock.trackedKeyCount())
        // 能再次拿到锁，说明没有死锁
        var ran = false
        lock.withLock("k") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun multipleKeys_doNotDeadlock() = runTest {
        // 两个协程以相反顺序请求同一组键：排序获取保证不会互相等死。
        val a = async {
            lock.withLock(listOf("x", "y")) { delay(20) }
        }
        val b = async {
            lock.withLock(listOf("y", "x")) { delay(20) }
        }
        a.await()
        b.await()
        assertEquals(0, lock.trackedKeyCount())
    }

    @Test
    fun blankKeys_areIgnored() = runTest {
        var ran = false
        lock.withLock(listOf(null, "", "  ")) { ran = true }
        assertTrue("全空键等价于不加锁，仍应执行", ran)
        assertEquals(0, lock.trackedKeyCount())
    }
}
