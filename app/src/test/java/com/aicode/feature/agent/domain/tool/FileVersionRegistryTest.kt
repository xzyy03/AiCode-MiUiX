package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileVersionRegistry]：按会话分桶、LRU 上限、外部写入广播。
 */
class FileVersionRegistryTest {

    private val registry = FileVersionRegistry()

    @Test
    fun recordAndGet_roundTrip() {
        registry.record("s1", "~/workspace/a.kt", "sha256:aaa")
        assertEquals("sha256:aaa", registry.get("s1", "~/workspace/a.kt"))
    }

    @Test
    fun unknownSessionOrKey_returnsNull() {
        assertNull(registry.get("nobody", "k"))
        registry.record("s1", "k", "v")
        assertNull(registry.get("s1", "other"))
    }

    @Test
    fun sessionsAreIsolated() {
        registry.record("s1", "k", "v1")
        registry.record("s2", "k", "v2")
        assertEquals("v1", registry.get("s1", "k"))
        assertEquals("v2", registry.get("s2", "k"))
    }

    @Test
    fun blankSessionId_isIgnored() {
        registry.record(null, "k", "v")
        registry.record("  ", "k", "v")
        assertNull(registry.get(null, "k"))
        assertEquals(0, registry.trackedSessionCount())
    }

    @Test
    fun record_overwritesPreviousValue() {
        registry.record("s1", "k", "v1")
        registry.record("s1", "k", "v2")
        assertEquals("v2", registry.get("s1", "k"))
        assertEquals(1, registry.trackedEntryCount("s1"))
    }

    @Test
    fun perSessionCap_evictsOldestByAccessOrder() {
        val cap = 512
        for (i in 0 until cap) registry.record("s1", "k$i", "v$i")
        assertEquals(cap, registry.trackedEntryCount("s1"))

        // 再写一条：应淘汰最久未访问的 k0，而不是整表清空
        registry.record("s1", "kNew", "vNew")
        assertEquals(cap, registry.trackedEntryCount("s1"))
        assertNull("最旧的条目应被淘汰", registry.get("s1", "k0"))
        assertEquals("vNew", registry.get("s1", "kNew"))
        assertEquals("最新的条目应保留", "v511", registry.get("s1", "k511"))
    }

    @Test
    fun recordExternal_updatesEveryExistingSession() {
        registry.record("s1", "k", "old")
        registry.record("s2", "k", "old")
        registry.recordExternal("k", "new")
        assertEquals("new", registry.get("s1", "k"))
        assertEquals("new", registry.get("s2", "k"))
    }

    @Test
    fun recordExternal_doesNotCreateSessionBuckets() {
        registry.recordExternal("k", "v")
        assertEquals("没有会话时不应凭空建桶", 0, registry.trackedSessionCount())
    }

    @Test
    fun forget_removesSingleEntry() {
        registry.record("s1", "a", "v")
        registry.record("s1", "b", "v")
        registry.forget("s1", "a")
        assertNull(registry.get("s1", "a"))
        assertEquals("v", registry.get("s1", "b"))
    }

    @Test
    fun clearSession_dropsEverythingForThatSession() {
        registry.record("s1", "a", "v")
        registry.record("s2", "a", "v")
        registry.clearSession("s1")
        assertNull(registry.get("s1", "a"))
        assertEquals("v", registry.get("s2", "a"))
        assertEquals(1, registry.trackedSessionCount())
    }
}
