package com.aicode.feature.agent.domain.mcp.auth

/**
 * 解析 `WWW-Authenticate: Bearer ...` 挑战头。
 *
 * 形如：`Bearer resource_metadata="https://...", scope="a b", error="insufficient_scope"`。
 * 值可能带引号（可含逗号与空格），未带引号时读到下一个逗号为止。只处理 Bearer 方案。
 */
object WwwAuthenticateParser {

    fun parse(header: String?): BearerChallenge {
        if (header.isNullOrBlank()) return BearerChallenge()
        val trimmed = header.trim()
        val schemeEnd = trimmed.indexOf(' ')
        val scheme = if (schemeEnd < 0) trimmed else trimmed.substring(0, schemeEnd)
        if (!scheme.equals("Bearer", ignoreCase = true)) return BearerChallenge()
        val params = if (schemeEnd < 0) "" else trimmed.substring(schemeEnd + 1)
        val map = parseParams(params)
        return BearerChallenge(
            resourceMetadataUrl = map["resource_metadata"],
            scope = map["scope"],
            error = map["error"],
            errorDescription = map["error_description"]
        )
    }

    private fun parseParams(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < input.length) {
            while (i < input.length && (input[i].isWhitespace() || input[i] == ',')) i++
            val keyStart = i
            while (i < input.length && input[i] != '=' && input[i] != ',') i++
            if (i >= input.length) break
            if (input[i] == ',') continue
            val key = input.substring(keyStart, i).trim()
            i++ // 跳过 '='
            val value: String
            if (i < input.length && input[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < input.length && input[i] != '"') {
                    if (input[i] == '\\' && i + 1 < input.length) {
                        i++
                        sb.append(input[i])
                    } else {
                        sb.append(input[i])
                    }
                    i++
                }
                i++ // 跳过收尾引号
                value = sb.toString()
            } else {
                val valueStart = i
                while (i < input.length && input[i] != ',') i++
                value = input.substring(valueStart, i).trim()
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
