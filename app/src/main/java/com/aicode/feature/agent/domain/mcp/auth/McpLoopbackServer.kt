package com.aicode.feature.agent.domain.mcp.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/** loopback 回调解析结果。 */
data class LoopbackCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
    val iss: String? = null
)

/**
 * 监听 127.0.0.1 的 OAuth 回调服务器。授权完成后浏览器重定向到
 * `http://127.0.0.1:<port>/callback?code=...&state=...`，这里接收一次请求、解析查询串并回一个提示页。
 *
 * 只接受单个连接，用完即关；规范要求 redirect URI 为 localhost 或 https，loopback 是移动端的合规做法。
 * 端口用 [bind] 绑定（传 0 时由系统分配），便于上层在固定端口被占用时回退随机端口。
 */
class McpLoopbackServer(
    private val port: Int,
    private val path: String = "/callback"
) {
    private var serverSocket: ServerSocket? = null

    /** 实际绑定的端口；[bind] 之后可用（传 0 时是系统分配的端口）。 */
    val boundPort: Int get() = serverSocket?.localPort ?: port

    /** 绑定本地回环端口。端口被占用时抛 [java.io.IOException]，由上层决定回退策略。 */
    fun bind(): McpLoopbackServer {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
        serverSocket = socket
        return this
    }

    suspend fun awaitCallback(timeoutMs: Long): LoopbackCallback = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: throw McpOAuthException("回调服务器未启动")
        socket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        try {
            socket.accept().use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine().orEmpty()
                val query = parseQuery(requestLine)
                val body = resultHtml(query["error"])
                val bytes = body.toByteArray(Charsets.UTF_8)
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                conn.getOutputStream().apply {
                    write(response)
                    write(bytes)
                    flush()
                }
                LoopbackCallback(
                    code = query["code"],
                    state = query["state"],
                    error = query["error"],
                    errorDescription = query["error_description"],
                    iss = query["iss"]
                )
            }
        } catch (e: SocketTimeoutException) {
            throw McpOAuthException("等待授权回调超时", e)
        } catch (e: SocketException) {
            // 上层主动 close 会打断 accept，视作用户取消。
            throw McpOAuthCancelledException(e)
        } finally {
            runCatching { socket.close() }
            serverSocket = null
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 从 `GET /callback?... HTTP/1.1` 的请求行里取查询参数；路径不匹配时返回空。 */
    private fun parseQuery(requestLine: String): Map<String, String> {
        val target = requestLine.split(' ').getOrNull(1) ?: return emptyMap()
        if (!target.startsWith(path)) return emptyMap()
        val queryStart = target.indexOf('?')
        if (queryStart < 0) return emptyMap()
        return target.substring(queryStart + 1)
            .split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
            .toMap()
    }

    private fun resultHtml(error: String?): String {
        val message = if (error.isNullOrBlank()) {
            "Authorization complete. You can return to AiCode.<br>授权完成，可以返回 AiCode。"
        } else {
            "Authorization failed: $error<br>授权失败，请返回 AiCode 重试。"
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>AiCode</title></head>" +
            "<body style=\"font-family:sans-serif;padding:24px;line-height:1.6\">" +
            "<h3>$message</h3></body></html>"
    }
}
