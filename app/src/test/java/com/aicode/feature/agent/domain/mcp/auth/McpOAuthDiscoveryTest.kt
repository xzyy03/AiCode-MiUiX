package com.aicode.feature.agent.domain.mcp.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** well-known 地址拼接与 issuer 比对。 */
class McpOAuthDiscoveryTest {

    @Test
    fun well_known_urls_with_path_cover_rfc8414_and_oidc() {
        val urls = McpOAuthDiscovery.wellKnownUrls("https://github.com/login/oauth")
        assertTrue(urls.contains("https://github.com/.well-known/oauth-authorization-server/login/oauth"))
        assertTrue(urls.contains("https://github.com/.well-known/openid-configuration/login/oauth"))
        assertTrue(urls.contains("https://github.com/login/oauth/.well-known/openid-configuration"))
    }

    @Test
    fun well_known_urls_without_path() {
        val urls = McpOAuthDiscovery.wellKnownUrls("https://mcp.example.com")
        assertTrue(urls.contains("https://mcp.example.com/.well-known/oauth-authorization-server"))
    }

    @Test
    fun resource_metadata_well_known_url_keeps_path() {
        val urls = McpOAuthDiscovery.wellKnownResourceMetadataUrls("https://mcp.context7.com/mcp")
        assertTrue(urls.contains("https://mcp.context7.com/.well-known/oauth-protected-resource/mcp"))
    }

    @Test
    fun issuer_match_is_case_insensitive_for_host_only() {
        assertTrue(McpOAuthDiscovery.issuerMatches("https://GitHub.com/login/oauth", "https://github.com/login/oauth"))
        assertFalse(McpOAuthDiscovery.issuerMatches("https://github.com/login/oauth", "https://github.com/login/oauth2"))
        assertFalse(McpOAuthDiscovery.issuerMatches("https://github.com/login/oauth", "https://evil.com/login/oauth"))
    }
}
