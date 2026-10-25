package com.muse.feature.agent.domain.tool.editor

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
import com.muse.feature.workspace.domain.sha256Fingerprint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "EditFileTool"

/**
 * 基于「精确字符串匹配」的文件编辑工具，取代旧的按行号 insert/replace/delete 三件套。
 *
 * 选择字符串匹配而非行号的原因：连续编辑时，第一次修改会让后续所有行号发生漂移，
 * 行号方案因此天然脆弱。字符串匹配只依赖内容本身，且一个工具即可覆盖三种语义：
 *   - 替换：old_string -> new_string
 *   - 删除：new_string 传空串
 *   - 插入：把某段替换为「它自身 + 新内容」
 *
 * 一次调用可对同一个文件进行多处编辑：传入 edits 数组，按数组顺序依次应用，
 * 每个编辑都在前一个编辑的结果之上匹配。整批编辑是「全有或全无」的——只要有任何
 * 一个编辑匹配失败（找不到或匹配多处而未开 replace_all），整次调用都不写盘并报错，
 * 文件不会处于改了一半的状态。
 *
 * 为保证安全，默认要求每个 old_string 在当前内容中唯一；若有多处匹配，需提供更长的
 * 上下文，或对该编辑显式设置 replace_all=true 才会全部替换。
 *
 * ### 并发保护
 *
 * 本工具是典型的「读-改-写」，因此在两个层次上防护：
 *
 * 1. **路径互斥锁** [WorkspaceFileLock]：同一逻辑文件在同一时刻只有一个会话在改。
 * 2. **写前版本校验** [FileVersionRegistry]：比对模型上次读到的内容指纹与磁盘当前指纹，
 *    不一致就拒绝并提示重新读取。锁拦不住「读发生在很久以前」的过期写，这一步才能拦住。
 *
 * 校验在**锁内**执行，且以锁内重读到的内容为准（而非锁外那次读），否则"校验通过 → 拿到锁"
 * 之间仍存在被插入的窗口。
 */
class EditFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val fileLock: WorkspaceFileLock,
    private val versions: FileVersionRegistry
) : AgentTool() {
    override val name = "editFile"
    override val description =
        "按精确字符串匹配局部修改已有文件，是修改文件的首选。edits 可一次提交多处修改，整批原子生效（任一处失败则全部回滚）。整文件重写用 writeFile。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    /** edits 数组单个元素的结构，供 function-calling 的 items schema。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，除 LF/CRLF 换行差异外需与文件当前内容精确匹配（含缩进）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容，换行沿用匹配区域或文件的换行格式。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "edits" to ToolParameter(
            "edits",
            ParameterType.ARRAY,
            "要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = true,
            itemsSchema = editItemSchema
        )
    )

    /** 解析后的单个编辑。 */
    private data class Edit(val oldString: String, val newString: String, val replaceAll: Boolean)

    /** 单个编辑应用后的差异，用于 UI 渲染与喂回模型。 */
    private data class Hunk(val startLine: Int, val added: Int, val removed: Int, val diff: String)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "未知文件"
        val editCount = (args["edits"] as? JsonArray)?.size ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认修改文件",
            summary = "AI 请求修改 $path",
            details = "编辑数量：$editCount\n工具会先完整匹配，任一编辑失败则不会写入。",
            argsPreview = argsPreview
        )
    }

    /**
     * 有会话上下文的执行路径（`StatefulAgentWorkflow` 走这条）。版本校验与路径锁都在这里，
     * 因为两者都需要 [AgentContext.sessionId] 才能把"谁读到的版本"关联起来。
     */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：path", "MISSING_PATH")
        return withEditGuards(path, context.sessionId) { performEdit(path, args) }
    }

    /**
     * 无上下文的执行路径。正常流程不会走到这里（`StatefulAgentWorkflow` 一律走
     * [executeWithContext]），保留它是为了兼容直接调用工具的测试与内部调用方。
     *
     * 这里**没有**并发保护：没有 sessionId 就无从判断"你读到的版本"。为不改变旧行为，
     * 此路径直接执行；需要并发安全的调用方必须走 [executeWithContext]。
     */
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：path", "MISSING_PATH")
        return performEdit(path, args)
    }

    /**
     * 并发保护外壳：版本预检 → 加路径锁 → 锁内复检并执行 → 更新登记。
     *
     * 之所以要有**锁外预检 + 锁内复检**两次校验：锁外那次负责给出不带锁的快速失败
     * （绝大多数冲突在这里就被挡住，不必排队等锁）；锁内那次才是权威判定，因为
     * "预检通过"与"真正拿到锁"之间可能已被其他会话写入。
     */
    private suspend fun withEditGuards(
        path: String,
        sessionId: String?,
        block: suspend () -> ToolResult
    ): ToolResult {
        val key = fileAccess.toDisplayPath(path)
        checkVersion(path, key, sessionId)?.let { return it }

        return fileLock.withLock(key) {
            checkVersion(path, key, sessionId)?.let { return@withLock it }
            val result = block()
            // 写成功后把新版本登记下来，使同一会话的连续编辑不被自己判为冲突。
            // 用 runCatching 包住：写已经落盘，登记失败不能反过来把成功报成失败
            // （最坏后果只是该文件退化成"未登记"的放行语义）。
            if (result is ToolResult.Success) {
                runCatching { fileAccess.fingerprint(path) }
                    .onFailure { FileLogger.w(TAG, "edit_file 写后登记版本失败: $key", it) }
                    .getOrNull()
                    ?.let { versions.record(sessionId, key, it) }
            }
            result
        }
    }

    /**
     * 版本比对。返回 null 表示可以继续，返回 Error 表示内容已过期、拒绝写入。
     *
     * [path] 用于访问文件，[key] 用于查登记表——两者必须分开传：key 是给用户看的逻辑路径
     * （`~/workspace/...`），虽然 [FileAccessProvider] 也能接受它，但显式区分意图更不容易出错。
     *
     * **fail-open**：拿不到当前指纹时（远端文件超上限、IO 瞬时失败）放行并记日志。
     * 版本校验是**辅助保护**，它自己的失败绝不能阻塞正常编辑——宁可漏判一次过期写，
     * 也不能因为算不出指纹就让用户改不了文件。
     */
    private fun checkVersion(path: String, key: String, sessionId: String?): ToolResult? {
        val recorded = versions.get(sessionId, key) ?: return null
        val current = runCatching { fileAccess.fingerprint(path) }.getOrElse { e ->
            FileLogger.w(TAG, "edit_file 无法计算当前指纹，跳过版本校验: $key", e)
            return null
        } ?: return null
        if (recorded == current) return null
        FileLogger.w(TAG, "edit_file 拒绝过期写: $key（记录版本与当前不一致）")
        return ToolResult.Error(staleFileMessage(key), STALE_FILE_CODE)
    }

    /** 真正的编辑逻辑：不涉及锁与版本，纯粹按 [args] 完成一次「读-改-写」。 */
    private suspend fun performEdit(path: String, args: Map<String, JsonElement>): ToolResult {
        return try {
            val edits = parseEdits(args)
                ?: return ToolResult.Error("缺少必需参数：edits", "MISSING_EDITS")

            // 逐个校验编辑本身的合法性（空 old_string / 无变化），避免无意义改动。
            edits.forEachIndexed { i, e ->
                if (e.oldString.isEmpty()) {
                    return ToolResult.Error("第 ${i + 1} 个编辑的 old_string 不能为空，创建文件请用 writeFile", "EMPTY_OLD_STRING")
                }
                if (e.oldString == e.newString) {
                    return ToolResult.Error("第 ${i + 1} 个编辑的 old_string 与 new_string 相同，无需修改", "NO_OP")
                }
            }

            FileLogger.d(TAG, "edit_file path=$path (edits=${edits.size})")
            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "edit_file 文件不存在: $path")
                return ToolResult.Error("文件不存在：$path", "FILE_NOT_FOUND")
            }

            // 先在内存里顺序应用所有编辑；任一失败立刻返回、绝不写盘（全有或全无）。
            var content = fileAccess.readFile(path)
            val hunks = ArrayList<Hunk>(edits.size)
            var totalReplacements = 0

            edits.forEachIndexed { i, e ->
                // readFile 按行返回 LF，匹配时只兼容换行差异，避免改写文件的其它区域。
                val matches = findLineEndingTolerant(content, e.oldString.replace("\r\n", "\n"))
                val occurrences = matches.size
                if (occurrences == 0) {
                    FileLogger.w(TAG, "edit_file 第 ${i + 1} 个编辑未匹配: $path")
                    return ToolResult.Error(
                        "第 ${i + 1} 个编辑未在文件中找到 old_string，请确认内容（含缩进，LF/CRLF 换行差异除外）与当前文件完全一致",
                        "NO_MATCH"
                    )
                }
                if (occurrences > 1 && !e.replaceAll) {
                    FileLogger.w(TAG, "edit_file 第 ${i + 1} 个编辑匹配 $occurrences 处且非 replace_all: $path")
                    return ToolResult.Error(
                        "第 ${i + 1} 个编辑的 old_string 在文件中匹配到 $occurrences 处，请提供更长的唯一上下文，或对该编辑设置 replace_all=true",
                        "MULTIPLE_MATCHES"
                    )
                }

                // 变更在「应用本编辑前」内容中的起始行号（1 基）。因为内容已包含此前所有编辑的结果，
                // 这个行号已经反映了前序编辑造成的行漂移，对自上而下的常规编辑顺序是准确的。
                val startLine = content.substring(0, matches.first().first).count { it == '\n' } + 1

                val diff = LineDiff.toUnified(e.oldString, e.newString)
                val added = diff.lines().count { it.startsWith("+") }
                val removed = diff.lines().count { it.startsWith("-") }
                hunks.add(Hunk(startLine = startLine, added = added, removed = removed, diff = diff))

                val fileLineEnding = firstLineEnding(content) ?: "\n"
                val normalizedNew = e.newString.replace("\r\n", "\n")
                val sb = StringBuilder(content.length)
                var cursor = 0
                for ((start, endExclusive) in matches) {
                    sb.append(content, cursor, start)
                    // 替换文本的换行沿用匹配区域首个换行的格式，匹配区域无换行时沿用文件格式。
                    val lineEnding = firstLineEnding(content, start, endExclusive) ?: fileLineEnding
                    sb.append(normalizedNew.replace("\n", lineEnding))
                    cursor = endExclusive
                }
                sb.append(content, cursor, content.length)
                content = sb.toString()
                totalReplacements += if (e.replaceAll) occurrences else 1
            }

            fileAccess.writeFile(path, content, overwrite = true)

            val addedTotal = hunks.sumOf { it.added }
            val removedTotal = hunks.sumOf { it.removed }
            val hunksJson = JsonArray(hunks.map { h ->
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(h.startLine),
                    "added" to JsonPrimitive(h.added),
                    "removed" to JsonPrimitive(h.removed),
                    "diff" to JsonPrimitive(h.diff)
                ))
            })

            FileLogger.v(TAG, "edit_file 成功 path=$path edits=${edits.size} replacements=$totalReplacements")
            ToolResult.Success(
                JsonObject(mapOf(
                    "status" to JsonPrimitive("edited"),
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "edits_count" to JsonPrimitive(edits.size),
                    "replacements" to JsonPrimitive(totalReplacements),
                    "total_lines" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(addedTotal),
                    "removed_lines" to JsonPrimitive(removedTotal),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "edit_file 异常", e)
            ToolResult.Error(e.message ?: "编辑文件失败", "EDIT_ERROR")
        }
    }

    /**
     * 解析编辑列表：读取 edits 数组。
     */
    private fun parseEdits(args: Map<String, JsonElement>): List<Edit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            Edit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * 在 [content] 中查找 [pattern]（字面文本，换行需先归一为 LF）的全部出现位置，容忍换行差异：
     * pattern 里的 \n 可匹配 content 的 \r\n 或单个 \n，其余字符逐字相等。返回非重叠的
     * [start, endExclusive) 区间，按出现顺序排列。
     *
     * 特意不用正则实现：把多行 old_string 拼成单条正则时，正则引擎的匹配递归深度随行数增长，
     * 模型整段替换上千行会抛 StackOverflowError——它是 Error，catch(Exception) 拦不住，
     * 会穿透协程作用域直接崩进程。逐字符迭代扫描没有栈深问题。
     */
    private fun findLineEndingTolerant(content: String, pattern: String): List<Pair<Int, Int>> {
        val matches = ArrayList<Pair<Int, Int>>()
        val n = content.length
        val m = pattern.length
        // 每个模式字符至少消耗一个内容字符，起点超过 n-m 不可能完整匹配。
        if (m == 0 || m > n) return matches
        var i = 0
        outer@ while (i <= n - m) {
            var ci = i
            var pj = 0
            while (pj < m) {
                val pc = pattern[pj]
                if (pc == '\n') {
                    if (ci + 1 < n && content[ci] == '\r' && content[ci + 1] == '\n') {
                        ci += 2
                    } else if (ci < n && content[ci] == '\n') {
                        ci += 1
                    } else {
                        i++
                        continue@outer
                    }
                } else if (ci < n && content[ci] == pc) {
                    ci += 1
                } else {
                    i++
                    continue@outer
                }
                pj++
            }
            matches.add(i to ci)
            i = ci
        }
        return matches
    }

    /**
     * [text] 的 [fromIndex, toIndex) 区间内首个换行的形态（"\r\n" 或 "\n"），无换行返回 null。
     * 判断 \r 是否成对只在区间内进行：能进入匹配区间的 \n，其配对 \r（若有）必在区间内。
     */
    private fun firstLineEnding(text: String, fromIndex: Int = 0, toIndex: Int = text.length): String? {
        for (k in fromIndex until toIndex) {
            if (text[k] == '\n') return if (k > fromIndex && text[k - 1] == '\r') "\r\n" else "\n"
        }
        return null
    }
}
