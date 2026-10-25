package com.aicode.feature.agent.domain.mcp.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `WWW-Authenticate: Bearer` 挑战解析：引号值、逗号、错误字段与非 Bearer 方案。 */
class WwwAuthenticateParserTest {

    @Test
    fun parses_resource_metadata_and_scope() {
        val challenge = WwwAuthenticateParser.parse(
            "Bearer resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource\", " +
                "scope=\"files:read files:write\""
        )
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource", challenge.resourceMetadataUrl)
        assertEquals("files:read files:write", challenge.scope)
    }

    @Test
    fun parses_insufficient_scope_error() {
        val challenge = WwwAuthenticateParser.parse(
            "Bearer error=\"insufficient_scope\", scope=\"files:write\", resource_metadata=\"https://x/meta\""
        )
        assertEquals("insufficient_scope", challenge.error)
        assertEquals("files:write", challenge.scope)
        assertEquals("https://x/meta", challenge.resourceMetadataUrl)
    }

    @Test
    fun parses_unquoted_value() {
        val challenge = WwwAuthenticateParser.parse("Bearer resource_metadata=https://x/meta,scope=read")
        assertEquals("https://x/meta", challenge.resourceMetadataUrl)
        assertEquals("read", challenge.scope)
    }

    @Test
    fun ignores_non_bearer_scheme() {
        assertNull(WwwAuthenticateParser.parse("Basic realm=\"x\"").resourceMetadataUrl)
    }

    @Test
    fun handles_blank_input() {
        assertNull(WwwAuthenticateParser.parse(null).resourceMetadataUrl)
        assertNull(WwwAuthenticateParser.parse("").scope)
    }
}
