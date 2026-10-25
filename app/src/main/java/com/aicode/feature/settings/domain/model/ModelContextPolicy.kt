package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 128_000
    const val CHARS_PER_TOKEN = 4

    // 压缩分块预算的保守口径：代码/JSON 等内容的真实 token 密度高于自然语言，
    // 4 字符/token 会低估，改用 3 并叠加 COMPACTION_BUDGET_RATIO，避免摘要请求超上游窗口。
    const val COMPACTION_CHARS_PER_TOKEN = 3
    const val COMPACTION_BUDGET_RATIO = 0.8

    // 压缩时原样保留的最近上下文按窗口大小取（1/8），上下限兜底，避免大窗口只留固定 20K。
    fun preserveRecentTokens(usableTokens: Int): Int =
        (usableTokens / 8).coerceIn(MIN_PRESERVE_RECENT_TOKENS, MAX_PRESERVE_RECENT_TOKENS)

    fun estimateTokens(chars: Int, charsPerToken: Int = CHARS_PER_TOKEN): Int =
        chars / charsPerToken + if (chars % charsPerToken == 0) 0 else 1

    fun estimateTextTokens(text: String, charsPerToken: Int = CHARS_PER_TOKEN): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 128) ascii++ else other++ }
        return estimateTokens(ascii, charsPerToken) + other
    }

    fun outputReserveTokens(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val reserve = (context / 10).coerceIn(1_024, 8_192).coerceAtMost(context / 4)
        return metadata.outputTokens?.takeIf { it > 0 }?.coerceAtMost(reserve) ?: reserve
    }

    fun effectiveInputBudget(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val shared = (context - outputReserveTokens(metadata)).coerceAtLeast(1)
        return metadata.inputTokens?.takeIf { it > 0 }?.coerceAtMost(shared) ?: shared
    }
}
