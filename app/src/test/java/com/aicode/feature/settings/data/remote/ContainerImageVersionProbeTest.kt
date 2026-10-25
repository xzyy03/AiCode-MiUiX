package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本探测的白名单校验。
 *
 * 回归点：Debian 的 rootfs 走 Jenkins `lastSuccessfulBuild`，版本号靠读同目录的
 * `rootfs.debian_version` 得到。该请求失败时上游可能返回 HTML 错误页，若不做校验，
 * 界面上就会把一段 HTML 当成版本号显示出来——比不显示更糟。
 */
class ContainerImageVersionProbeTest {

    @Test
    fun 正常版本号_原样返回() {
        assertEquals("12.15", normalizeImageVersion("12.15"))
        assertEquals("13.7", normalizeImageVersion("13.7"))
        assertEquals("3.21.8", normalizeImageVersion("3.21.8"))
        assertEquals("20.04.5", normalizeImageVersion("20.04.5"))
    }

    @Test
    fun 带空白与换行_裁剪后返回() {
        assertEquals("12.15", normalizeImageVersion("  12.15\n"))
        assertEquals("12.15", normalizeImageVersion("\t12.15\r\n"))
    }

    @Test
    fun 空内容_返回null() {
        assertNull(normalizeImageVersion(null))
        assertNull(normalizeImageVersion(""))
        assertNull(normalizeImageVersion("   \n\t "))
    }

    @Test
    fun HTML错误页_返回null() {
        assertNull(normalizeImageVersion("<html><body>404 Not Found</body></html>"))
        assertNull(normalizeImageVersion("<!DOCTYPE html>"))
        assertNull(normalizeImageVersion("{\"error\":\"not found\"}"))
    }

    @Test
    fun 不以数字开头_返回null() {
        // 拿不准就回退内置版本号，不猜
        assertNull(normalizeImageVersion("v3.21.8"))
        assertNull(normalizeImageVersion("bookworm"))
    }

    @Test
    fun 超长内容_返回null() {
        assertNull(normalizeImageVersion("1" + "0".repeat(40)))
    }

    @Test
    fun 含空格或路径分隔符_返回null() {
        assertNull(normalizeImageVersion("12.15 extra"))
        assertNull(normalizeImageVersion("job/tianon/12.15"))
    }
}
