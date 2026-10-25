package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val projectionJson = Json { ignoreUnknownKeys = true }

/**
 * 搭车通知补回时的前缀。投影正文是英文，而通知字段值（summary/hint/notice）是中文，
 * 这里用中文说明，保证模型能区分「工具输出」与「平台事件」。
 */
private const val NOTIFICATIONS_HEADER = "平台通知（工具执行期间到达，不代表用户的新请求或授权）："

/**
 * 文件类工具喂给模型的精简结果文本，对齐 opencode 的 edit / write 语义：
 * - editFile：一句话确认 + 替换数 + 增删行数 + diff 预览（截断）；
 * - writeFile：一句话确认 + 行数，不回显内容。
 *
 * 只投影成功结果，其它工具 / 失败返回 null，由调用方回退用完整 result。
 * 注意：此文本仅喂模型，UI 与持久化仍走 result 的完整 diff。
 *
 * 搭车通知（后台任务/子代理完成等）由
 * [com.aicode.feature.agent.domain.notification.AgentEventInjector] 写进 transport JSON 顶层的
 * `notifications` 字段，投影只读 `data` 会把它丢掉；而通知在工具结果落库后就被 ack，
 * 丢给模型没看到就等于永久丢失。这里在投影文本后原样补回该字段。
 */
fun modelToolResultText(toolName: String, transportJson: String): String? {
    val raw = transportJson.trim()
    if (raw.isEmpty()) return null
    val obj = runCatching { projectionJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if (obj["status"]?.jsonPrimitive?.contentOrNull != "success") return null
    val data = obj["data"] as? JsonObject ?: return null
    val projected = when (toolName) {
        "editFile" -> editProjection(data)
        "writeFile" -> writeProjection(data)
        else -> null
    } ?: return null
    val notifications = (obj["notifications"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return projected
    return projected + "\n\n" + NOTIFICATIONS_HEADER + "\n" + notifications
}

private fun editProjection(data: JsonObject): String? {
    val path = data["path"]?.jsonPrimitive?.contentOrNull ?: return null
    val replacements = data["replacements"]?.jsonPrimitive?.intOrNull ?: 0
    val added = data["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = data["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val lines = buildList {
        add("Edited file successfully: $path")
        add("Replacements: $replacements")
        add("Changed lines: +$added -$removed")
        diffPreview(data)?.let { add("```diff"); addAll(it); add("```") }
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

private fun writeProjection(data: JsonObject): String? {
    val path = data["path"]?.jsonPrimitive?.contentOrNull ?: return null
    val created = data["created"]?.jsonPrimitive?.contentOrNull == "true"
    val added = data["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = data["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val total = data["lines_written"]?.jsonPrimitive?.intOrNull ?: data["total_lines"]?.jsonPrimitive?.intOrNull
    val verb = if (created) "Created" else "Wrote"
    val lines = buildList {
        add("$verb file successfully: $path (lines: ${total ?: "?"}, +$added -$removed)")
    }
    return lines.joinToString("\n")
}

/** 从 hunks 里取 diff 的前几行做预览，每行超长截断。hunks 为空时返回 null。 */
private fun diffPreview(data: JsonObject): List<String>? {
    val hunks = data["hunks"]?.jsonArray ?: return null
    val all = buildList {
        hunks.forEach { el ->
            val h = el.jsonObject
            (h["diff"]?.jsonPrimitive?.contentOrNull)?.let { this += it }
        }
    }
    if (all.isEmpty()) return null
    val lines = all
        .flatMap { it.split("\n") }
        .map { if (it.length > 240) it.take(240) + "..." else it }
    return lines.take(6)
}