package com.aicode.feature.agent.domain.mcp.auth

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import javax.inject.Inject

/**
 * MCP OAuth 的元数据发现：从 401 指向的受保护资源元数据（RFC 9728）里取出授权服务器，
 * 再按 RFC 8414 或 OIDC Discovery 拉取授权服务器元数据。
 *
 * 规范要求客户端同时支持 RFC 8414 与 OIDC 两种发现方式，因此对同一个 issuer 会依次尝试
 * 多个 well-known 位置，取第一个 issuer 校验通过的。
 */
class McpOAuthDiscovery @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "McpOAuthDiscovery"
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 候选的 well-known 地址。RFC 8414 把 `.well-known/oauth-authorization-server` 插在
         * host 与 issuer 路径之间；OIDC Discovery 则把 `.well-known/openid-configuration`
         * 插在 host 后（带路径）或直接追加在 issuer 之后。三种都给出，逐一尝试。
         */
        fun wellKnownUrls(issuer: String): List<String> {
            val uri = runCatching { URI(issuer) }.getOrNull()
            val scheme = uri?.scheme
            val host = uri?.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                return listOf("${issuer.trimEnd('/')}/.well-known/oauth-authorization-server")
            }
            val authority = buildString {
                append(scheme).append("://").append(host)
                if (uri.port != -1) append(':').append(uri.port)
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return listOf(
                "$authority/.well-known/oauth-authorization-server$path",
                "$authority/.well-known/openid-configuration$path",
                "${issuer.trimEnd('/')}/.well-known/openid-configuration"
            ).distinct()
        }

        /**
         * RFC 9728 标准位置：把 `.well-known/oauth-protected-resource` 插在 host 与 resource 路径之间。
         * 用于 server 匿名可用、不在 401 里给 resource_metadata 但仍支持登录的情形。
         */
        fun wellKnownResourceMetadataUrls(resource: String): List<String> {
            val uri = runCatching { URI(resource) }.getOrNull()
            val scheme = uri?.scheme
            val host = uri?.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) return emptyList()
            val authority = buildString {
                append(scheme).append("://").append(host)
                if (uri.port != -1) append(':').append(uri.port)
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return listOf("$authority/.well-known/oauth-protected-resource$path").distinct()
        }

        /** issuer 比对：scheme 与 host 大小写不敏感（RFC 3986 §6.2.1），其余部分保持原样。 */
        fun issuerMatches(expected: String, actual: String): Boolean {
            if (expected == actual) return true
            return normalizeIssuer(expected) == normalizeIssuer(actual)
        }

        private fun normalizeIssuer(value: String): String = runCatching {
            val u = URI(value)
            val scheme = u.scheme?.lowercase() ?: return@runCatching value
            val host = u.host?.lowercase() ?: return@runCatching value
            buildString {
                append(scheme).append("://").append(host)
                if (u.port != -1) append(':').append(u.port)
                append(u.rawPath.orEmpty())
                u.rawQuery?.let { append('?').append(it) }
            }
        }.getOrDefault(value)
    }

    suspend fun fetchProtectedResourceMetadata(url: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            val body = get(url)
            runCatching { DEFAULT_JSON.decodeFromString(ProtectedResourceMetadata.serializer(), body) }.getOrElse {
                throw McpOAuthException("解析受保护资源元数据失败（$url）：${it.message}", it)
            }
        }

    /**
     * 从 resource 本身按 RFC 9728 标准位置取受保护资源元数据。server 匿名可用、不在 401 里
     * 给 resource_metadata 但仍支持登录时（例如 Context7）走这条。
     */
    suspend fun fetchProtectedResourceMetadataForResource(resource: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (candidate in wellKnownResourceMetadataUrls(resource)) {
                try {
                    val body = get(candidate)
                    return@withContext runCatching {
                        DEFAULT_JSON.decodeFromString(ProtectedResourceMetadata.serializer(), body)
                    }.getOrElse {
                        throw McpOAuthException("解析受保护资源元数据失败（$candidate）：${it.message}", it)
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw McpOAuthException("无法获取受保护资源元数据（$resource）：${lastError?.message ?: "无可用端点"}")
        }

    suspend fun fetchAuthorizationServerMetadata(issuer: String): AuthorizationServerMetadata =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            for (candidate in wellKnownUrls(issuer)) {
                try {
                    val body = get(candidate)
                    val metadata = DEFAULT_JSON.decodeFromString(AuthorizationServerMetadata.serializer(), body)
                    if (!issuerMatches(issuer, metadata.issuer)) {
                        FileLogger.w(TAG, "issuer 不匹配，跳过 $candidate：期望 $issuer，实际 ${metadata.issuer}")
                        continue
                    }
                    return@withContext metadata
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw McpOAuthException(
                "无法获取授权服务器元数据（$issuer）：${lastError?.message ?: "所有 well-known 端点均不可用"}"
            )
        }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw McpOAuthException("HTTP ${resp.code} 请求 $url 失败")
            return resp.body?.string() ?: throw McpOAuthException("$url 响应体为空")
        }
    }
}
