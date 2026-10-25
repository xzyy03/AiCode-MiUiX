package com.aicode.core.util

import android.content.Context
import com.aicode.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorUtilsTest {

    private val labels = mapOf(
        R.string.network_error_dns to "DNS 解析失败",
        R.string.network_error_timeout to "请求超时",
        R.string.network_error_connect to "连接被拒绝",
        R.string.network_error_ssl to "SSL 握手失败",
        R.string.common_error_unknown to "未知错误"
    )

    private val context: Context = mockk {
        every { getString(any()) } answers { labels[firstArg<Int>()] ?: "?" }
    }

    @Test
    fun simpleExceptionWithMessage() {
        val e = RuntimeException("something broke")
        assertEquals("something broke", e.toUserMessage(context))
    }

    @Test
    fun exceptionWithNullMessage_fallsBackToClassName() {
        val e = RuntimeException()
        assertEquals("RuntimeException", e.toUserMessage(context))
    }

    @Test
    fun exceptionChain_joinsWithDotSeparator() {
        val root = RuntimeException("root cause")
        val mid = RuntimeException("mid level", root)
        val top = RuntimeException("top error", mid)
        assertEquals("top error · mid level · root cause", top.toUserMessage(context))
    }

    @Test
    fun exceptionChain_deduplicatesRepeatedMessages() {
        val root = RuntimeException("same error")
        val mid = RuntimeException("same error", root)
        val top = RuntimeException("same error", mid)
        assertEquals("same error", top.toUserMessage(context))
    }

    @Test
    fun exceptionChain_limitsDepthToFour() {
        val root = RuntimeException("level 5")
        val l4 = RuntimeException("level 4", root)
        val l3 = RuntimeException("level 3", l4)
        val l2 = RuntimeException("level 2", l3)
        val l1 = RuntimeException("level 1", l2)
        // depth < 4 means we traverse l1 -> l2 -> l3 -> l4 (4 levels), l5 is not reached
        assertEquals("level 1 · level 2 · level 3 · level 4", l1.toUserMessage(context))
    }

    @Test
    fun unknownHostException_labelsDnsFirstAndKeepsRawMessage() {
        val e = java.net.UnknownHostException(
            "Unable to resolve host \"api.example.com\": No address associated with hostname"
        )
        val msg = e.toUserMessage(context)
        assertTrue(msg.startsWith("DNS 解析失败"))
        assertTrue(msg.contains("Unable to resolve host"))
    }

    @Test
    fun socketTimeoutException_labelsTimeout() {
        val e = java.net.SocketTimeoutException()
        assertEquals("请求超时", e.toUserMessage(context))
    }

    @Test
    fun connectException_labelsConnectionRefused() {
        val e = java.net.ConnectException("Connection refused")
        val msg = e.toUserMessage(context)
        assertTrue(msg.startsWith("连接被拒绝"))
    }

    @Test
    fun emptyMessageAndNoCause_returnsClassName() {
        // An exception with null message and no cause falls back to class name
        val e = RuntimeException()
        assertEquals("RuntimeException", e.toUserMessage(context))
    }
}
