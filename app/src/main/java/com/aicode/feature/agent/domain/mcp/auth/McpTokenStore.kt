package com.aicode.feature.agent.domain.mcp.auth

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * OAuth 客户端身份与令牌的持久化，按授权服务器 issuer 为键（规范要求凭据绑定 issuer）。
 *
 * 文件内容做 base64 + 整体反转处理，与 `git-credentials` 保持一致：**属混淆、非加密**，
 * 只为避免明文直读，不构成安全承诺。内存缓存 + Mutex 串行化读写。
 */
@Singleton
class McpTokenStore @Inject constructor(
    // 限定符与 McpAuthModule 的 @Provides 保持一致。
    @param:Named("mcpOAuthFile") private val file: File
) {
    private companion object {
        const val TAG = "McpTokenStore"
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }

    private val mutex = Mutex()

    @Volatile
    private var cache: StoreRoot? = null

    suspend fun getClient(issuer: String): OAuthClientRegistration? {
        val stored = load().clients[issuer] ?: return null
        return OAuthClientRegistration(issuer, stored.clientId, stored.clientSecret, stored.redirectUri)
    }

    suspend fun putClient(registration: OAuthClientRegistration) = update { root ->
        root.copy(
            clients = root.clients + (registration.issuer to
                StoredClient(registration.clientId, registration.clientSecret, registration.redirectUri))
        )
    }

    suspend fun getTokens(issuer: String): OAuthTokens? {
        val stored = load().tokens[issuer] ?: return null
        return OAuthTokens(stored.accessToken, stored.refreshToken, stored.expiresAt, stored.scope)
    }

    suspend fun putTokens(issuer: String, tokens: OAuthTokens) = update { root ->
        root.copy(
            tokens = root.tokens + (issuer to
                StoredTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtEpochSeconds, tokens.scope))
        )
    }

    /** 清除某个 issuer 的客户端注册（回调端口变化需重新注册时用）。 */
    suspend fun clearClient(issuer: String) = update { root ->
        root.copy(clients = root.clients - issuer)
    }

    suspend fun clear(issuer: String) = update { root ->
        root.copy(
            clients = root.clients - issuer,
            tokens = root.tokens - issuer,
            serverIssuers = root.serverIssuers.filterValues { it != issuer }
        )
    }

    /** server 名到 issuer 的映射，用于重启后按 server 恢复会话与刷新。 */
    suspend fun getIssuerForServer(serverName: String): String? = load().serverIssuers[serverName]

    suspend fun putServerIssuer(serverName: String, issuer: String) = update { root ->
        root.copy(serverIssuers = root.serverIssuers + (serverName to issuer))
    }

    suspend fun clearServerIssuer(serverName: String) = update { root ->
        root.copy(serverIssuers = root.serverIssuers - serverName)
    }

    private suspend fun load(): StoreRoot {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: readFromDisk().also { cache = it }
        }
    }

    private suspend fun update(transform: (StoreRoot) -> StoreRoot) {
        mutex.withLock {
            val next = transform(cache ?: readFromDisk())
            writeToDisk(next)
            cache = next
        }
    }

    private suspend fun readFromDisk(): StoreRoot = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext StoreRoot()
        runCatching {
            val raw = file.readText()
            val decoded = tryDecode(raw) ?: raw
            JSON.decodeFromString(StoreRoot.serializer(), decoded)
        }.getOrElse {
            FileLogger.w(TAG, "读取 OAuth 存储失败，按空处理：${it.message}")
            StoreRoot()
        }
    }

    private suspend fun writeToDisk(root: StoreRoot) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = encode(JSON.encodeToString(StoreRoot.serializer(), root))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encoded)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(encoded)
                tmp.delete()
            }
        }.onFailure { FileLogger.w(TAG, "写入 OAuth 存储失败：${it.message}") }
        Unit
    }

    /** 编码：base64 后整体反转，使文件不是可直接回显的明文。 */
    private fun encode(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8)).reversed()

    /**
     * 解码编码串；不是本格式时返回 null。用往返校验判定格式，避免把明文误当编码串解出乱码。
     */
    private fun tryDecode(text: String): String? = runCatching {
        val raw = text.trim()
        if (raw.isEmpty()) return@runCatching null
        val decoded = String(Base64.getDecoder().decode(raw.reversed()), Charsets.UTF_8)
        if (encode(decoded) != raw) return@runCatching null
        decoded
    }.getOrNull()
}

@Serializable
internal data class StoreRoot(
    val clients: Map<String, StoredClient> = emptyMap(),
    val tokens: Map<String, StoredTokens> = emptyMap(),
    val serverIssuers: Map<String, String> = emptyMap()
)

@Serializable
internal data class StoredClient(
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String
)

@Serializable
internal data class StoredTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val scope: String? = null
)
