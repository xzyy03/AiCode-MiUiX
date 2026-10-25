package com.aicode.feature.agent.domain.mcp.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 令牌存储：按 issuer 读写、跨实例持久化、文件不做明文直读。 */
class McpTokenStoreTest {

    private fun tempStore(): Pair<McpTokenStore, File> {
        val file = File.createTempFile("mcp-oauth", ".json")
        file.delete()
        return McpTokenStore(file) to file
    }

    @Test
    fun client_and_tokens_roundtrip() = runBlocking {
        val (store, _) = tempStore()
        store.putClient(
            OAuthClientRegistration(
                issuer = "https://as.example.com",
                clientId = "cid",
                clientSecret = null,
                redirectUri = "http://127.0.0.1:8765/callback"
            )
        )
        store.putTokens("https://as.example.com", OAuthTokens("access-1", "refresh-1", 1234567890, "repo"))

        assertEquals("cid", store.getClient("https://as.example.com")?.clientId)
        assertEquals("access-1", store.getTokens("https://as.example.com")?.accessToken)
        assertEquals("refresh-1", store.getTokens("https://as.example.com")?.refreshToken)
    }

    @Test
    fun persists_across_instances_and_obfuscates_plaintext() = runBlocking {
        val (store, file) = tempStore()
        store.putTokens("issuer", OAuthTokens("secret-token"))
        assertFalse("文件不应明文包含令牌", file.readText().contains("secret-token"))

        val reopened = McpTokenStore(file)
        assertEquals("secret-token", reopened.getTokens("issuer")?.accessToken)
    }

    @Test
    fun server_issuer_mapping() = runBlocking {
        val (store, _) = tempStore()
        store.putServerIssuer("github", "https://github.com/login/oauth")
        assertEquals("https://github.com/login/oauth", store.getIssuerForServer("github"))
        store.clearServerIssuer("github")
        assertNull(store.getIssuerForServer("github"))
    }
}
