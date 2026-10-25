package com.aicode.feature.agent.domain.mcp.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用 MCP OAuth 客户端：为任意远程 MCP server 完成「发现 → 注册 → PKCE 授权 → 换令牌 → 刷新」。
 *
 * 令牌按授权服务器 issuer 持久化（[McpTokenStore]），内存另存 server 名到会话的映射，供请求头同步取用。
 * 授权交互走 loopback 回调 + 系统浏览器，符合规范对 redirect URI 的约束。
 */
@Singleton
class McpOAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val discovery: McpOAuthDiscovery,
    private val registrar: McpClientRegistrar,
    private val tokenStore: McpTokenStore
) {
    private companion object {
        const val TAG = "McpOAuthManager"
        const val CALLBACK_PORT = 8765
        const val CALLBACK_PATH = "/callback"
        const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val JSON_MEDIA = "application/json".toMediaType()
    }

    private data class AuthSession(
        val issuer: String,
        val registration: OAuthClientRegistration,
        val tokens: OAuthTokens
    )

    private val sessions = ConcurrentHashMap<String, AuthSession>()

    /** 正在进行授权、等待回调的 server 对应的回调服务器，供取消时主动关闭。 */
    private val activeServers = ConcurrentHashMap<String, McpLoopbackServer>()

    /** 供传输层同步取用的当前 access token；无有效会话时返回 null。 */
    fun cachedToken(serverName: String): String? = sessions[serverName]?.tokens?.accessToken

    fun isAuthorized(serverName: String): Boolean = sessions.containsKey(serverName)

    /** 取消正在进行的授权：关闭其回调服务器，等待回调的协程会以 [McpOAuthCancelledException] 结束。 */
    fun cancel(serverName: String) {
        activeServers.remove(serverName)?.close()
    }

    /**
     * 连接前准备：恢复已有会话并在令牌过期时刷新。失败不抛出——拿不到令牌就让连接按 401 处理，
     * 由 UI 引导用户重新授权。
     */
    suspend fun prepare(cfg: McpServerConfig) {
        if (cfg.auth == null) return
        val existing = sessions[cfg.name]
        if (existing != null) {
            refreshIfNeeded(cfg, existing)
            return
        }
        val issuer = tokenStore.getIssuerForServer(cfg.name) ?: return
        val registration = tokenStore.getClient(issuer) ?: return
        val tokens = tokenStore.getTokens(issuer) ?: return
        val state = AuthSession(issuer, registration, tokens)
        sessions[cfg.name] = state
        refreshIfNeeded(cfg, state)
    }

    /** 完整授权流程。成功返回令牌并写入存储与内存会话。 */
    suspend fun authorize(cfg: McpServerConfig): OAuthTokens {
        val challenge = probeChallenge(cfg)
        val prm = challenge?.resourceMetadataUrl?.takeIf { it.isNotBlank() }
            ?.let { discovery.fetchProtectedResourceMetadata(it) }
            ?: discovery.fetchProtectedResourceMetadataForResource(cfg.url.orEmpty())
        val issuer = cfg.auth?.authServer?.takeIf { it.isNotBlank() }
            ?: prm.authorizationServers.firstOrNull()
            ?: throw McpOAuthException("受保护资源元数据未声明 authorization_servers")
        val metadata = discovery.fetchAuthorizationServerMetadata(issuer)

        val existing = tokenStore.getClient(issuer)
        val preferredPort = existing?.let { portFromRedirectUri(it.redirectUri) } ?: CALLBACK_PORT
        val server = bindLoopbackServer(preferredPort, issuer, metadata)
        activeServers[cfg.name] = server
        try {
            val requestedRedirectUri = "http://127.0.0.1:${server.boundPort}$CALLBACK_PATH"
            val registration = registrar.resolveClient(issuer, metadata, requestedRedirectUri, cfg.auth?.clientId)
                ?: throw McpOAuthException("授权服务器不支持动态注册，请在配置里填写 client_id")
            // 以注册时登记的地址为准，否则授权服务器会因 redirect_uri 不匹配而拒绝。
            val redirectUri = registration.redirectUri

            val resource = prm.resource?.takeIf { it.isNotBlank() } ?: cfg.url.orEmpty().trimEnd('/')
            val scope = challenge?.scope?.takeIf { it.isNotBlank() }
                ?: cfg.auth?.scopes?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                ?: prm.scopesSupported.takeIf { it.isNotEmpty() }?.joinToString(" ")

            val verifier = McpPkce.generateCodeVerifier()
            val state = McpPkce.generateState()
            val authUrl = buildAuthorizationUrl(metadata, registration, redirectUri, resource, scope, verifier, state)

            return coroutineScope {
                val callbackDeferred = async(Dispatchers.IO) { server.awaitCallback(CALLBACK_TIMEOUT_MS) }
                openBrowser(authUrl)
                val callback = callbackDeferred.await()

                if (!callback.error.isNullOrBlank()) {
                    throw McpOAuthException("授权被拒绝：${callback.error} ${callback.errorDescription.orEmpty()}".trim())
                }
                if (callback.state != state) {
                    throw McpOAuthException("state 校验失败，可能遭遇 CSRF，已中止")
                }
                // RFC 9207：授权服务器声明支持 iss 时必须比对记录的 issuer。
                if (metadata.authorizationResponseIssParameterSupported && callback.iss != null &&
                    !McpOAuthDiscovery.issuerMatches(issuer, callback.iss)
                ) {
                    throw McpOAuthException("iss 校验失败：期望 $issuer，实际 ${callback.iss}")
                }
                val code = callback.code ?: throw McpOAuthException("授权回调缺少 code")

                val tokens = exchangeCode(metadata, registration, code, redirectUri, verifier, resource)
                tokenStore.putTokens(issuer, tokens)
                tokenStore.putServerIssuer(cfg.name, issuer)
                sessions[cfg.name] = AuthSession(issuer, registration, tokens)
                FileLogger.i(TAG, "[${cfg.name}] OAuth 授权成功 issuer=$issuer")
                tokens
            }
        } finally {
            activeServers.remove(cfg.name)
            server.close()
        }
    }

    suspend fun logout(cfg: McpServerConfig) {
        val issuer = sessions[cfg.name]?.issuer ?: tokenStore.getIssuerForServer(cfg.name)
        if (issuer != null) tokenStore.clear(issuer)
        tokenStore.clearServerIssuer(cfg.name)
        sessions.remove(cfg.name)
    }

    private suspend fun refreshIfNeeded(cfg: McpServerConfig, state: AuthSession) {
        if (!state.tokens.isExpired(nowSeconds())) return
        val refreshToken = state.tokens.refreshToken ?: return
        val metadata = runCatching { discovery.fetchAuthorizationServerMetadata(state.issuer) }
            .getOrElse { FileLogger.w(TAG, "[${cfg.name}] 刷新令牌前获取元数据失败：${it.message}"); return }
        val refreshed = runCatching { exchangeRefreshToken(metadata, state.registration, refreshToken) }
            .getOrElse { FileLogger.w(TAG, "[${cfg.name}] 刷新令牌失败：${it.message}"); return }
        tokenStore.putTokens(state.issuer, refreshed)
        sessions[cfg.name] = state.copy(tokens = refreshed)
    }

    /**
     * 无令牌发一次 initialize 探测授权挑战。返回 null 表示 server 匿名可用（不要求授权），
     * 但这类 server 仍可能支持登录，调用方会改用 RFC 9728 标准位置继续发现。
     */
    private suspend fun probeChallenge(cfg: McpServerConfig): BearerChallenge? = withContext(Dispatchers.IO) {
        val url = cfg.url?.takeIf { it.isNotBlank() } ?: throw McpOAuthException("server 未配置 url")
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "ai-code-editor")
                    put("version", "1.0.0")
                }
            }
        }
        val body = JSON.encodeToString(JsonObject.serializer(), payload)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> WwwAuthenticateParser.parse(resp.header("WWW-Authenticate"))
                resp.isSuccessful -> null
                else -> throw McpOAuthException("server 返回 HTTP ${resp.code}，无法开始授权")
            }
        }
    }

    private suspend fun exchangeCode(
        metadata: AuthorizationServerMetadata,
        registration: OAuthClientRegistration,
        code: String,
        redirectUri: String,
        verifier: String,
        resource: String
    ): OAuthTokens {
        val endpoint = metadata.tokenEndpoint ?: throw McpOAuthException("授权服务器缺少 token_endpoint")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", registration.clientId)
            .add("code_verifier", verifier)
            .add("resource", resource)
            .apply { registration.clientSecret?.let { add("client_secret", it) } }
            .build()
        return postToken(endpoint, form)
    }

    private suspend fun exchangeRefreshToken(
        metadata: AuthorizationServerMetadata,
        registration: OAuthClientRegistration,
        refreshToken: String
    ): OAuthTokens {
        val endpoint = metadata.tokenEndpoint ?: throw McpOAuthException("授权服务器缺少 token_endpoint")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", registration.clientId)
            .apply { registration.clientSecret?.let { add("client_secret", it) } }
            .build()
        return postToken(endpoint, form)
    }

    private suspend fun postToken(endpoint: String, form: FormBody): OAuthTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw McpOAuthException("换取令牌失败：HTTP ${resp.code} $body")
            val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrElse {
                throw McpOAuthException("令牌响应解析失败：${it.message}", it)
            }
            val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: throw McpOAuthException("令牌响应缺少 access_token")
            OAuthTokens(
                accessToken = accessToken,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresAtEpochSeconds = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { nowSeconds() + it },
                scope = obj["scope"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    private fun buildAuthorizationUrl(
        metadata: AuthorizationServerMetadata,
        registration: OAuthClientRegistration,
        redirectUri: String,
        resource: String,
        scope: String?,
        verifier: String,
        state: String
    ): String {
        val base = metadata.authorizationEndpoint ?: throw McpOAuthException("授权服务器缺少 authorization_endpoint")
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to registration.clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to McpPkce.codeChallengeS256(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to resource
        )
        if (!scope.isNullOrBlank()) params["scope"] = scope
        val separator = if (base.contains('?')) "&" else "?"
        return base + separator + params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 绑定回环端口。已有客户端注册时优先复用其登记端口，保证 redirect_uri 一致；
     * 端口不可用且授权服务器支持动态注册时清掉旧注册、改用随机端口重新注册；否则报错。
     */
    private suspend fun bindLoopbackServer(
        preferredPort: Int,
        issuer: String,
        metadata: AuthorizationServerMetadata
    ): McpLoopbackServer {
        return try {
            McpLoopbackServer(preferredPort, CALLBACK_PATH).bind()
        } catch (e: IOException) {
            if (metadata.registrationEndpoint.isNullOrBlank()) {
                throw McpOAuthException("回调端口 $preferredPort 被占用且客户端不支持重新注册，请释放端口后重试", e)
            }
            FileLogger.w(TAG, "回调端口 $preferredPort 不可用，改用随机端口重新注册：${e.message}")
            tokenStore.clearClient(issuer)
            McpLoopbackServer(0, CALLBACK_PATH).bind()
        }
    }

    private fun portFromRedirectUri(uri: String): Int? =
        runCatching { java.net.URI(uri).port.takeIf { it > 0 } }.getOrNull()

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
