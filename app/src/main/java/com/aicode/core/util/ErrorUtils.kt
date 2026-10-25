package com.aicode.core.util

import android.content.Context
import com.aicode.R
import java.io.IOException

/**
 * 把异常转换成对用户有意义的提示文本。
 *
 * 已知网络异常先给出归类文案，原始 message 随后保留：只透出系统原文（如
 * "Unable to resolve host ...: No address associated with hostname"）会让用户误判成域名不存在，
 * 而丢掉原文又不利于排查。空 message 的异常回退到异常类名（直接使用会显示成 "null"）。
 */
fun Throwable.toUserMessage(context: Context): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        val t = current
        val msg = t.message?.trim().orEmpty()
        val label = t.networkLabel(context)
        if (label != null) {
            parts.addIfAbsent(label)
            if (msg.isNotEmpty()) parts.addIfAbsent(msg)
        } else {
            parts.addIfAbsent(msg.ifEmpty { t.javaClass.simpleName })
        }
        current = t.cause
        depth++
    }
    return parts.joinToString(" · ").ifBlank { context.getString(R.string.common_error_unknown) }
}

private fun MutableList<String>.addIfAbsent(value: String) {
    if (none { it == value }) add(value)
}

/** 针对常见网络异常给出中文归类；非网络类异常返回 null，由原始 message 兜底。 */
private fun Throwable.networkLabel(context: Context): String? = when (this) {
    is java.net.UnknownHostException -> context.getString(R.string.network_error_dns)
    is java.net.SocketTimeoutException -> context.getString(R.string.network_error_timeout)
    is java.net.ConnectException -> context.getString(R.string.network_error_connect)
    // SSLException / ConnectException / UnknownHostException 都是 IOException 子类，必须先于兜底匹配
    is javax.net.ssl.SSLException -> context.getString(R.string.network_error_ssl)
    is IOException -> null
    else -> null
}
