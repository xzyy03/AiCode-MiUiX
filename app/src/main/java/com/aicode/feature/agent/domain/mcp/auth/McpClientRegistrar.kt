package com.aicode.feature.agent.domain.mcp.auth

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * 获取 OAuth 客户端身份，按规范优先级：预注册 → 动态客户端注册（DCR）。
 *
 * 已获得的结果按 issuer 缓存进 [McpTokenStore]，避免重复注册。都拿不到时返回 null，
 * 交由 UI 让用户手工填写 client_id。
 */
class McpClientRegistrar @Inject constructor(
    private val client: OkHttpClient,
    private val tokenStore: McpTokenStore
) {
    companion object {
        private const val TAG = "McpClientRegistrar"
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    suspend fun resolveClient(
        issuer: String,
        metadata: AuthorizationServerMetadata,
        redirectUri: String,
        preRegisteredClientId: String?
    ): OAuthClientRegistration? {
        val cached = tokenStore.getClient(issuer)
        val trimmedClientId = preRegisteredClientId?.trim()

        if (!trimmedClientId.isNullOrBlank()) {
            if (cached != null && cached.clientId == trimmedClientId) {
                return cached
            }
            val reg = OAuthClientRegistration(
                issuer = issuer,
                clientId = trimmedClientId,
                clientSecret = null,
                redirectUri = redirectUri
            )
            tokenStore.putClient(reg)
            return reg
        }

        if (cached != null) return cached

        val endpoint = metadata.registrationEndpoint
        if (!endpoint.isNullOrBlank()) {
            val reg = registerDynamically(endpoint, issuer, redirectUri, metadata.scopesSupported)
            tokenStore.putClient(reg)
            return reg
        }

        FileLogger.w(TAG, "issuer=$issuer 既不支持 DCR 也未提供预注册 client_id，需用户手工填写")
        return null
    }

    private suspend fun registerDynamically(
        endpoint: String,
        issuer: String,
        redirectUri: String,
        scopesSupported: List<String>
    ): OAuthClientRegistration = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("client_name", "AiCode")
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
            if (scopesSupported.isNotEmpty()) put("scope", scopesSupported.joinToString(" "))
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(DEFAULT_JSON.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw McpOAuthException("动态客户端注册失败：HTTP ${resp.code} ${resp.message}")
            val body = resp.body?.string() ?: throw McpOAuthException("动态客户端注册响应为空")
            val obj = runCatching { DEFAULT_JSON.parseToJsonElement(body).jsonObject }.getOrElse {
                throw McpOAuthException("动态客户端注册响应解析失败：${it.message}", it)
            }
            val clientId = obj["client_id"]?.jsonPrimitive?.contentOrNull
                ?: throw McpOAuthException("动态客户端注册响应缺少 client_id")
            val clientSecret = obj["client_secret"]?.jsonPrimitive?.contentOrNull
            FileLogger.i(TAG, "issuer=$issuer 动态注册成功 client_id=$clientId")
            OAuthClientRegistration(issuer = issuer, clientId = clientId, clientSecret = clientSecret, redirectUri = redirectUri)
        }
    }
}
