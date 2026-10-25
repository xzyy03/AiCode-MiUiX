package com.aicode.feature.agent.domain.mcp.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE（RFC 7636）与 state 生成，授权码流程必需的明文校验对。
 *
 * 只用 JDK 的 SecureRandom / MessageDigest / Base64（Android API 26+ 均可用），
 * 不依赖 android.* ，便于在纯 JVM 单测里验证。
 */
object McpPkce {

    private val random = SecureRandom()

    /** code_verifier：32 字节随机数的 base64url（无 padding）得到 43 个 unreserved 字符。 */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    /** code_challenge = BASE64URL(SHA256(verifier))，不含 padding（S256）。 */
    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    /** state：防 CSRF，32 字节随机。 */
    fun generateState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
