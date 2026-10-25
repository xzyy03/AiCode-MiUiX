package com.aicode.feature.agent.domain.checkpoint

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次代码回滚的结果。
 *
 * @param restored 实际恢复（覆盖写回或删除）的文件数
 * @param skipped  因「快照之后已被其他会话/用户修改」而**跳过**的文件（AI 视角的逻辑路径）。
 *                 调用方必须把这个清单展示给用户——静默跳过等于把数据丢失换成了无声的失败。
 */
data class CheckpointRestoreResult(
    val restored: Int,
    val skipped: List<String>
)

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val fileAccess: FileAccessProvider,
    private val fileLock: WorkspaceFileLock,
    private val versions: FileVersionRegistry
) {
    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // 活动 checkpoint 必须按会话隔离：多个会话（含父会话与其子代理）会并行跑 workflow，
    // 用单一字段会被后创建的会话覆盖，导致文件快照挂到别的会话名下，
    // 那个会话一撤销就会把本会话刚写好的文件还原/删除。
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()

    /**
     * 在用户发送新消息时调用，创建一个新的 Checkpoint 节点
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        val entity = CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAt = System.currentTimeMillis()
        )
        checkpointDao.insertCheckpoint(entity)
        activeCheckpointIds[sessionId] = checkpointId
        entity
    }

    fun setActiveCheckpointId(sessionId: String, checkpointId: String?) {
        if (checkpointId == null) {
            activeCheckpointIds.remove(sessionId)
        } else {
            activeCheckpointIds[sessionId] = checkpointId
        }
    }

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String
    ) = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext

        // 查重：同一个 checkpointId 内对同一文件只保留最原始的一次快照
        if (checkpointDao.countSnapshot(checkpointId, filePath) > 0) {
            return@withContext
        }

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        // 记下拍摄时的内容指纹，供回滚前判断「这文件在快照之后是否被别人改过」。
        // 算不出来（文件过大、IO 异常）就留 null，回滚时按「未知版本」放行——
        // 版本信息是锦上添花，不能因为它拿不到就让快照本身失败。
        val fingerprint = if (exists) {
            runCatching { fileAccess.fingerprint(filePath) }
                .onFailure { FileLogger.w(TAG, "快照指纹计算失败，按未知版本记录: $filePath", it) }
                .getOrNull()
        } else {
            null
        }

        if (changeType == "MODIFY") {
            val originalContent = fileAccess.readFile(filePath)
            snapshotFile.writeText(originalContent)
        } else {
            snapshotFile.writeText("") // 标示创建空记录
        }

        val snapshotEntity = CheckpointFileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType,
            fingerprint = fingerprint
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "快照: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态。
     *
     * ### 并发保护
     *
     * checkpoint 是**跨时刻**的操作：快照拍摄在几分钟甚至几小时前，期间文件可能被改过。
     * 关键在于区分两种"被改过"：
     *
     * - **本会话自己的改动**——这正是用户要撤销的东西。绝大多数回滚都属于这一类，
     *   若把它一并拒绝，回滚功能本身就废了。
     * - **外部（其他会话/用户）叠加的改动**——必须保留，不能无声抹掉。
     *
     * 判定方式见下。核心依据是 [FileVersionRegistry] 记的「**本会话最后看到的版本**」，
     * 而不是「快照时记的指纹」——后者只在拍快照那一刻算过一次，本会话其后的写入不会刷新它，
     * 拿它比对会把本会话自己的正常改动误判成外部改动，等于让回滚永远拒绝执行。
     *
     * | 快照类型 | 判定 | 行为 |
     * | --- | --- | --- |
     * | MODIFY | 当前指纹 == 本会话记录的指纹（或本会话无记录） | 照常写回快照内容 |
     * | MODIFY | 当前指纹 ≠ 本会话记录的指纹 | 存在本会话未见过的改动，**跳过并记入 [CheckpointRestoreResult.skipped]** |
     * | MODIFY | 文件已被删除 | 恢复到快照内容 |
     * | CREATE | 恒成立 | 删除该文件（已知局限：CREATE 快照没有内容指纹，无法判断它是否被后续写入改过） |
     *
     * 真正写回/删除时都走 [WorkspaceFileLock]，与其他写路径共用同一把锁。
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): CheckpointRestoreResult = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext CheckpointRestoreResult(0, emptyList())

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        val skipped = ArrayList<String>()
        FileLogger.i(
            TAG,
            "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${checkpointsToRollback.size} 个检查点"
        )

        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            for (snapshot in snapshots) {
                val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)
                val displayPath = fileAccess.toDisplayPath(snapshot.filePath)

                if (snapshot.changeType == "MODIFY") {
                    if (!snapshotFile.exists()) continue

                    // 「隔离他人的改动」与「撤销自己的改动」必须区分开，否则回滚这个功能本身就废了：
                    // 绝大多数情况下是**本会话**在快照之后改了文件，用户正是想把它们撤销回快照状态。
                    //
                    // 判据不能是「当前内容 != 快照内容」——本会话自己的正常改动恰好满足它，
                    // 那样每次回滚都会拒绝，等于把功能废掉。判据也不能是「快照时记的指纹」——
                    // 那个指纹只在拍快照那一刻算过一次，本会话其后的写入不会更新它，同样是误判。
                    //
                    // 可靠判据是**本会话最后看到的版本**（[FileVersionRegistry]）：本会话每次写入、
                    // 以及每次 readFile，都会刷新它。因此「磁盘当前指纹 != 本会话记录指纹」恰好
                    // 等价于「存在本会话没见过的新改动」，那才是必须保留、只跳过的情况。
                    val currentFp = runCatching { fileAccess.fingerprint(snapshot.filePath) }
                        .onFailure { FileLogger.w(TAG, "无法计算当前指纹，按未知版本放行: $displayPath", it) }
                        .getOrNull()
                    val knownFp = versions.get(sessionId, displayPath)
                    if (currentFp != null && knownFp != null && currentFp != knownFp) {
                        FileLogger.w(TAG, "还原跳过（存在本会话未见过的改动）: $displayPath")
                        skipped += displayPath
                        continue
                    }

                    val snapshotContent = snapshotFile.readText()
                    // 文件在快照后被删除时 currentFp == null，同样按快照恢复。
                    fileLock.withLock(displayPath) {
                        fileAccess.writeFile(snapshot.filePath, snapshotContent, overwrite = true)
                    }
                    // 回滚是一次「外部写入」：所有会话手上的版本都过期了
                    fileAccess.fingerprint(snapshot.filePath)
                        ?.let { versions.recordExternal(displayPath, it) }
                    restoredFileCount++
                    FileLogger.i(TAG, "还原覆盖: cp=${cp.id} path=$displayPath")
                } else if (snapshot.changeType == "CREATE") {
                    // 创建快照意味着「这个文件是本会话新建的」，因此常规回滚就是把它删掉。
                    //
                    // 已知局限：CREATE 快照的 [CheckpointFileSnapshotEntity.fingerprint] 必然是 null
                    // （拍摄时文件还不存在），所以**无法**判断快照之后是否有人往这个文件里写过东西，
                    // 这里一律删除。要覆盖该场景需要在文件首次写入后补记一次指纹，属后续工作。
                    if (fileAccess.exists(snapshot.filePath)) {
                        fileLock.withLock(displayPath) {
                            fileAccess.delete(snapshot.filePath)
                        }
                        versions.forget(sessionId, displayPath)
                        restoredFileCount++
                        FileLogger.i(TAG, "还原删除: cp=${cp.id} path=$displayPath")
                    }
                }
            }
        }
        FileLogger.i(
            TAG,
            "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件，跳过 ${skipped.size} 个"
        )
        CheckpointRestoreResult(restoredFileCount, skipped)
    }

    /**
     * 删除 Session 关联的所有 Checkpoint 快照与记录
     */
    suspend fun clearSessionCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        activeCheckpointIds.remove(sessionId)
        checkpointDao.deleteFileSnapshotsForSession(sessionId)
        checkpointDao.deleteCheckpointsForSession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "CheckpointManager"
    }
}
