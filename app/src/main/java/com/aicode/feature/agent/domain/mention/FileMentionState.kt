package com.aicode.feature.agent.domain.mention

/**
 * 输入框 `@` 文件引用菜单的状态。
 *
 * - [Idle]：菜单未激活，无清单。
 * - [Loading]：正在拉取文件清单。
 * - [Ready]：清单已就绪，匹配在内存进行。
 * - [Unavailable]：容器未就绪或未安装 rg，无法搜索。
 * - [Error]：清单拉取失败。
 */
sealed interface FileMentionState {
    data object Idle : FileMentionState
    data object Loading : FileMentionState
    data class Ready(val files: List<String>) : FileMentionState
    data object Unavailable : FileMentionState
    data class Error(val message: String) : FileMentionState
}

/**
 * 从输入文本末尾提取 `@` 查询词：`@` 前必须是行首或空白（避开 email 等），
 * `@` 之后不含空白。不满足返回 null，表示不进入 `@` 补全。
 */
fun fileMentionQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val query = text.substring(at + 1)
    return if (query.any { it.isWhitespace() }) null else query
}
