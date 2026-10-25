package com.aicode.feature.agent.domain.mcp.auth

/** OAuth 流程中的可预期失败（元数据缺失、注册失败、回调超时等），消息面向日志与排查。 */
class McpOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 用户主动取消授权流程。与普通失败区分，避免把取消显示成错误。 */
class McpOAuthCancelledException(cause: Throwable? = null) : Exception("授权已取消", cause)
