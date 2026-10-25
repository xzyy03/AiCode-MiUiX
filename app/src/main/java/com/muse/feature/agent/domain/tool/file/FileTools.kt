package com.muse.feature.agent.domain.tool.file

import com.muse.feature.agent.domain.model.AgentContext
import com.muse.feature.agent.domain.tool.AgentTool
import com.muse.feature.agent.domain.tool.FileVersionRegistry
import com.muse.feature.agent.domain.tool.ParameterType
import com.muse.feature.agent.domain.tool.PendingToolPermission
import com.muse.feature.agent.domain.tool.STALE_FILE_CODE
import com.muse.feature.agent.domain.tool.ToolParameter
import com.muse.feature.agent.domain.tool.ToolCapability
import com.muse.feature.agent.domain.tool.ToolPermissionPolicy
import com.muse.feature.agent.domain.tool.ToolResult
import com.muse.feature.agent.domain.tool.WorkspaceFileLock
import com.muse.feature.agent.domain.tool.staleFileMessage
import com.muse.core.util.FileLogger
import com.muse.core.util.LineDiff
import com.muse.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileTools"

/**
 * 读取文件内容（工作区文件或容器绝对路径的系统文件）。
 *
 * 除返回内容外，本工具还承担一个**副作用**：把本次读到的文件内容指纹登记到
 * [FileVersionRegistry]，供后续 `editFile` / `writeFile` 做写前版本校验。因此
 * 「先 readFile 再改」不只是好习惯，也是拿到并发保护的前提。
 */
class ReadFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val versions: FileVersionRegistry
) : AgentTool() {
    override val name = "readFile"
    override val description = "读取文件内容（工作区文件或容器绝对路径的系统文件）。单次读取有行数与字节上限，超出可用 start_line 分段续读。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "开始行号（从 1 计）。", required = false),
        "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "结束行号；与 start_line 的跨度最多 2000 行，超出按 2000 行截断。", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "read_file 缺少 path 参数")
                return ToolResult.Error("缺少必需参数：path", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file 文件不存在: $path")
                return ToolResult.Error("文件不存在：$path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // 行窗口上界：显式 end_line 与 start_line+MAX_LINES 取较小值，缺省则按 MAX_LINES 上限。
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // 逐行流式读取，避免 readText() 整篇进内存导致移动端 OOM；
            // 只保留 [startLine, endCap] 窗口，并在累计字节超过 MAX_BYTES 时提前停止。
            // 走 IO 线程：本地 File 读与远程 exec 流的阻塞读写都不应占着调用方线程。
            val sb = StringBuilder()
            var totalLines = 0
            var emittedLines = 0
            var byteCount = 0
            var truncatedByBytes = false
            var lineNo = 0
            withContext(Dispatchers.IO) {
                fileAccess.readLines(path).forEach { line ->
                    lineNo++
                    totalLines = lineNo
                    if (lineNo < startLine) return@forEach
                    if (lineNo > endCap) {
                        // 已越过窗口，但仍需继续计数以得到准确 total_lines。
                        return@forEach
                    }
                    if (!truncatedByBytes) {
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                            truncatedByBytes = true
                        } else {
                            if (emittedLines > 0) sb.append('\n')
                            sb.append(line)
                            byteCount += lineBytes
                            emittedLines++
                        }
                    }
                }
            }

            val lastEmittedLine = startLine + emittedLines - 1
            // 用户想要的窗口末行：给了 end_line 取 min(end_line, EOF)，否则到 EOF。
            // 我们只发到了 lastEmittedLine，若它落在窗口末行之前，说明被截断、还有内容可读。
            val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "内容超过 ${MAX_BYTES / 1024}KB 上限已截断，可从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                else -> "内容超过 $MAX_LINES 行上限已截断，可从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
            }

            FileLogger.v(TAG, "read_file 成功 path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
            val resultMap = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(sb.toString()),
                "total_lines" to JsonPrimitive(totalLines),
                "start_line" to JsonPrimitive(startLine),
                "end_line" to JsonPrimitive(maxOf(lastEmittedLine, startLine - 1)),
                "read_lines" to JsonPrimitive(emittedLines),
                "truncated" to JsonPrimitive(truncated)
            )
            if (note != null) resultMap["note"] = JsonPrimitive(note)
            ToolResult.Success(JsonObject(resultMap))
        } catch (e: Exception) {
            FileLogger.e(TAG, "read_file 异常", e)
            ToolResult.Error(e.message ?: "读取文件失败", "READ_ERROR")
        }
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val result = execute(args)
        // 只有成功读取才登记版本；读失败（文件不存在/过大）时没有可信基线，登记反而会
        // 让后续写入被误判为过期。
        if (result is ToolResult.Success) {
            args["path"]?.jsonPrimitive?.contentOrNull?.let { path -> recordVersion(path, context.sessionId) }
        }
        return result
    }

    /**
     * 登记读到的版本。失败一律忽略——这里只是为后续写入建立基线，登记不上最多退化成
     * 「没读过就写」的放行语义，不应让一次成功的读取因为登记失败而报错。
     */
    private fun recordVersion(path: String, sessionId: String?) {
        runCatching {
            fileAccess.fingerprint(path)?.let { versions.record(sessionId, fileAccess.toDisplayPath(path), it) }
        }.onFailure {
            FileLogger.w(TAG, "read_file 登记版本失败: $path", it)
        }
    }

    private companion object {
        /** 单次最多返回的行数，超出截断并提示用 start_line 续读。 */
        const val MAX_LINES = 2000
        /** 单次最多返回的字节数（UTF-8，约 200KB），防止超大行撑爆内存/上下文。 */
        const val MAX_BYTES = 200 * 1024
    }
}

/**
 * 写入整个文件，文件不存在时会自动创建（含父目录）。
 *
 * 通过 overwrite 参数吸收了旧 create_file 的「不覆盖已有文件」语义：
 * overwrite=false 且目标已存在时报错，可用于安全地新建文件。
 *
 * ### 并发保护
 *
 * 覆盖已有文件时做写前版本校验（[FileVersionRegistry]）：若模型上次读到的内容与磁盘当前
 * 内容不一致，说明文件已被其他会话或用户改过，**拒绝覆盖**并提示重新读取。
 * 新建文件不做校验（没有可比较的旧版本）。校验与写入都在 [WorkspaceFileLock] 内进行。
 */
class WriteFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val fileLock: WorkspaceFileLock,
    private val versions: FileVersionRegistry
) : AgentTool() {
    override val name = "writeFile"
    override val description = "写入完整文件内容，文件不存在时自动创建（含父目录）。局部修改请用 editFile。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "content" to ToolParameter("content", ParameterType.STRING, "文件内容", required = true),
        "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "目标已存在时是否覆盖。默认 true；设为 false 时若文件已存在则报错。", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "未知文件"
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认写入文件",
            summary = "AI 请求写入 $path",
            details = "字符数：${content.length}\n行数：${content.lines().size}\n允许覆盖：${if (overwrite) "是" else "否"}",
            argsPreview = argsPreview
        )
    }

    /** 有会话上下文的执行路径（`StatefulAgentWorkflow` 走这条），带并发保护。 */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：path", "MISSING_PATH")

        val key = fileAccess.toDisplayPath(path)
        val existedBefore = fileAccess.exists(path)
        checkVersion(path, key, context.sessionId, existedBefore)?.let { return it }

        return fileLock.withLock(key) {
            checkVersion(path, key, context.sessionId, existedBefore)?.let { return@withLock it }
            val result = performWrite(path, args)
            // 写成功后登记新版本；登记失败不能把已成功的写入报成失败（见 EditFileTool 同处注释）。
            if (result is ToolResult.Success) {
                runCatching { fileAccess.fingerprint(path) }
                    .onFailure { FileLogger.w(TAG, "write_file 写后登记版本失败: $key", it) }
                    .getOrNull()
                    ?.let { versions.record(context.sessionId, key, it) }
            }
            result
        }
    }

    /**
     * 无上下文的执行路径。正常流程不会走到这里，保留以兼容直接调用工具的测试与内部调用方；
     * 此路径**没有**并发保护（没有 sessionId 就无从判断"你读到的版本"）。
     */
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：path", "MISSING_PATH")
        return performWrite(path, args)
    }

    /**
     * 版本比对。返回 null 表示可以继续，返回 Error 表示内容已过期、拒绝写入。
     *
     * [existed] 为 false（新建文件）时直接放行：没有旧版本可比较，登记表里的任何记录
     * 也不代表"这个文件曾经存在过"。
     *
     * **fail-open**：拿不到当前指纹时（远端文件超上限、IO 瞬时失败）放行并记日志。
     * 版本校验是辅助保护，它自己的失败不应阻塞正常写入。
     */
    private fun checkVersion(
        path: String,
        key: String,
        sessionId: String?,
        existed: Boolean
    ): ToolResult? {
        if (!existed) return null
        val recorded = versions.get(sessionId, key) ?: return null
        val current = runCatching { fileAccess.fingerprint(path) }.getOrElse { e ->
            FileLogger.w(TAG, "write_file 无法计算当前指纹，跳过版本校验: $key", e)
            return null
        } ?: return null
        if (recorded == current) return null
        FileLogger.w(TAG, "write_file 拒绝过期写: $key（记录版本与当前不一致）")
        return ToolResult.Error(staleFileMessage(key), STALE_FILE_CODE)
    }

    /** 真正的写入逻辑：不涉及锁与版本，纯粹按 [args] 完成一次整文件写。 */
    private suspend fun performWrite(path: String, args: Map<String, JsonElement>): ToolResult {
        return try {
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} 字符, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file 文件已存在且 overwrite=false: $path")
                return ToolResult.Error("文件已存在：$path（overwrite=false）", "FILE_EXISTS")
            }

            // 写前留存旧内容，供生成「旧→新」差异（与 edit_file 同构，UI 据此渲染彩色 diff）。
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // 生成统一差异文本：新建文件按「整体新增」呈现（旧内容视为空，避免一行伪删除）；
            // 覆盖写则计算旧→新的行级增删。LineDiff 为 O(n·m) 内存，超大文件重写时跳过 LCS、
            // 退化为整体新增，防止移动端因构造 DP 表而 OOM。
            val diff = buildWriteDiff(existed, oldContent, content)
            val added = diff.lines().count { it.startsWith("+") }
            val removed = diff.lines().count { it.startsWith("-") }
            val hunksJson = JsonArray(listOf(
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(1),
                    "added" to JsonPrimitive(added),
                    "removed" to JsonPrimitive(removed),
                    "diff" to JsonPrimitive(diff)
                ))
            ))

            FileLogger.v(TAG, "write_file 成功 path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
            ToolResult.Success(
                JsonObject(mapOf(
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.length),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "write_file 异常", e)
            ToolResult.Error(e.message ?: "写入文件失败", "WRITE_ERROR")
        }
    }

    /**
     * 构造 write_file 的统一差异文本（每行以 `+`/`-`/` ` 起头）：
     * - 新建文件：旧内容视为空，整篇按「全部新增」呈现，不跑 LCS；
     * - 覆盖写且规模可控：计算旧→新的行级 LCS 差异；
     * - 覆盖写但任一侧行数超过 [MAX_DIFF_LINES]：跳过 O(n·m) 的 LCS（移动端易 OOM），
     *   退化为整篇「全部新增」，仅展示落盘后的内容。
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file 文件过大跳过 LCS 差异 (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** 旧/新任一侧行数超过此值即跳过 LCS：DP 表为 O(n·m) ints，过大会拖垮移动端内存。 */
        const val MAX_DIFF_LINES = 2000
    }
}
