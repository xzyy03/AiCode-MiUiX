package com.aicode.feature.agent.domain.mcp.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class McpClientRegistrarTest {

    private fun tempStore(): Pair<McpTokenStore, File> {
        val file = File.createTempFile("mcp-oauth-reg", ".json")
        file.delete()
        return McpTokenStore(file) to file
    }

    @Test
    fun preregistered_clientId_overrides_stale_cache() = runBlocking {
        val (store, _) = tempStore()
        val registrar = McpClientRegistrar(OkHttpClient(), store)
        val metadata = AuthorizationServerMetadata(issuer = "https://github.com/login/oauth")

        // 第一次使用了错误的/旧的 clientId
        val first = registrar.resolveClient(
            issuer = "https://github.com/login/oauth",
            metadata = metadata,
            redirectUri = "http://127.0.0.1:8765/callback",
            preRegisteredClientId = "v23likIO5f2MvtjrHZq"
        )
        assertEquals("v23likIO5f2MvtjrHZq", first?.clientId)

        // 用户在配置中更新为正确的 clientId，应覆盖旧缓存而不是死抱旧值
        val second = registrar.resolveClient(
            issuer = "https://github.com/login/oauth",
            metadata = metadata,
            redirectUri = "http://127.0.0.1:8765/callback",
            preRegisteredClientId = "Iv23likIO5f2MvtjrHZq"
        )
        assertEquals("Iv23likIO5f2MvtjrHZq", second?.clientId)
        assertEquals("Iv23likIO5f2MvtjrHZq", store.getClient("https://github.com/login/oauth")?.clientId)
    }

    @Test
    fun cached_client_used_when_no_preregistered_id() = runBlocking {
        val (store, _) = tempStore()
        val registrar = McpClientRegistrar(OkHttpClient(), store)
        val metadata = AuthorizationServerMetadata(issuer = "https://github.com/login/oauth")

        store.putClient(
            OAuthClientRegistration(
                issuer = "https://github.com/login/oauth",
                clientId = "Iv23likIO5f2MvtjrHZq",
                clientSecret = null,
                redirectUri = "http://127.0.0.1:8765/callback"
            )
        )

        val resolved = registrar.resolveClient(
            issuer = "https://github.com/login/oauth",
            metadata = metadata,
            redirectUri = "http://127.0.0.1:8765/callback",
            preRegisteredClientId = null
        )
        assertEquals("Iv23likIO5f2MvtjrHZq", resolved?.clientId)
    }
}
