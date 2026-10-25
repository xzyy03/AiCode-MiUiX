package com.aicode.feature.agent.domain.mcp.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth 相关数据模型。字段名对齐 RFC 9728 / RFC 8414 / RFC 6749 的线格式，
 * 用 @SerialName 映射下划线命名，避免解析时逐字段手工取值。
 */

/** RFC 9728 受保护资源元数据（MCP server 在 401 里指向的文档）。 */
@Serializable
data class ProtectedResourceMetadata(
    val resource: String? = null,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String> = emptyList()
)

/** RFC 8414 / OIDC Discovery 授权服务器元数据（只取实现需要的子集）。 */
@Serializable
data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String> = emptyList(),
    @SerialName("authorization_response_iss_parameter_supported") val authorizationResponseIssParameterSupported: Boolean = false
)

/** 一次 401/403 响应里 `WWW-Authenticate: Bearer` 挑战携带的信息。 */
data class BearerChallenge(
    val resourceMetadataUrl: String? = null,
    val scope: String? = null,
    val error: String? = null,
    val errorDescription: String? = null
)

/** 已获得的 OAuth 客户端身份，绑定到具体授权服务器（issuer）。 */
data class OAuthClientRegistration(
    val issuer: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String
)

/** 一次授权换取的令牌。 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val scope: String? = null
) {
    /** 是否已过期（提前 [skewSeconds] 视为过期，避免边界重试）。 */
    fun isExpired(nowEpochSeconds: Long, skewSeconds: Long = 30): Boolean =
        expiresAtEpochSeconds != null && nowEpochSeconds >= expiresAtEpochSeconds - skewSeconds
}

/** 运行时的一次授权会话（仅内存，不落盘）。 */
data class OAuthAuthorizationSession(
    val issuer: String,
    val state: String,
    val codeVerifier: String,
    val resource: String,
    val scope: String?,
    val redirectUri: String
)
