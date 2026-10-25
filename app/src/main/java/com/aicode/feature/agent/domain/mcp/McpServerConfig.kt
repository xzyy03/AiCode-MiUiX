package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.Serializable

// 远程 HTTP（含 url）或本地 stdio（含 command）两种形态，由 isStdio 推断。
@Serializable
data class McpServerConfig(
    val name: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet(),
    val auth: McpAuthConfig? = null
) {
    val isStdio: Boolean get() = !command.isNullOrBlank()

    companion object {
        /**
         * 名称会拼进 function-calling 工具名（`mcp__{名称}__{工具名}`），只允许 ASCII 字母、数字、下划线与连字符；
         * 中文等其它 Unicode 字符不符合 provider 的工具命名规范。
         */
        private val NAME_REGEX = Regex("[a-zA-Z0-9_-]+")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }
}

/**
 * 远程 MCP server 的认证配置（仅 HTTP 形态有效）。目前只支持 OAuth；
 * 令牌与 client_secret 不入此结构，另存于 [com.aicode.feature.agent.domain.mcp.auth.McpTokenStore]。
 */
@Serializable
data class McpAuthConfig(
    val type: String = TYPE_OAUTH,
    /** 预注册的 OAuth client_id；留空则尝试动态客户端注册。 */
    val clientId: String? = null,
    /** 覆盖发现到的授权服务器 issuer（例如资源元数据未声明时手动指定）。 */
    val authServer: String? = null,
    /** 显式请求的 scope；留空则按 401 挑战与元数据推导。 */
    val scopes: List<String>? = null
) {
    companion object {
        const val TYPE_OAUTH = "oauth"
    }
}
