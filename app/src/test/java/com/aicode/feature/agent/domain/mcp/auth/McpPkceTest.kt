package com.aicode.feature.agent.domain.mcp.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PKCE 生成：与 RFC 7636 附录 B 的向量一致，且 verifier 符合字符集与长度约束。 */
class McpPkceTest {

    @Test
    fun challenge_matches_rfc7636_vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", McpPkce.codeChallengeS256(verifier))
    }

    @Test
    fun verifier_is_unreserved_and_correct_length() {
        val verifier = McpPkce.generateCodeVerifier()
        assertTrue("verifier 长度应落在 43..128", verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun state_is_unique() {
        assertNotEquals(McpPkce.generateState(), McpPkce.generateState())
    }
}
