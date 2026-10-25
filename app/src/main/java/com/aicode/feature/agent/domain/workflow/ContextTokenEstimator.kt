package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson

object ContextTokenEstimator {
    private val gson = Gson()

    fun estimate(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN
    ): Int {
        val total = ModelContextPolicy.estimateTextTokens(systemPrompt, charsPerToken).toLong() +
            tools.sumOf { tool ->
                ModelContextPolicy.estimateTextTokens(tool.name + tool.description + gson.toJson(tool.toJsonSchema()), charsPerToken).toLong() + 16
            } + messages.sumOf { estimate(it, charsPerToken).toLong() }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimate(message: AgentMessage, charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN): Int {
        val text: String
        val images: Int
        when (message) {
            is AgentMessage.UserMessage -> {
                text = message.content
                images = message.images.size
            }
            is AgentMessage.AssistantMessage -> {
                text = message.content + message.reasoning + message.toolCalls.joinToString { it.name + gson.toJson(it.arguments) }
                images = message.images.size
            }
            is AgentMessage.ToolResultMessage -> {
                text = message.toolName + (message.modelResult ?: modelToolResultText(message.toolName, message.result) ?: message.result)
                images = message.images.size
            }
        }
        return (ModelContextPolicy.estimateTextTokens(text, charsPerToken).toLong() + images * 4_096L + 12)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun calibrated(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int =
        if (baselineUsage > 0) {
            maxOf(estimated.toLong(), baselineUsage.toLong() + estimated - baselineEstimate)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } else estimated
}
