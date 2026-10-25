package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkpoint_file_snapshots",
    indices = [Index(value = ["checkpointId"])]
)
data class CheckpointFileSnapshotEntity(
    @PrimaryKey val id: String,
    val checkpointId: String,
    val filePath: String,
    val snapshotRelativePath: String,
    val changeType: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 拍摄快照时该文件的内容指纹（`sha256:...`，见 [com.aicode.feature.workspace.domain.sha256Fingerprint]）。
     *
     * 回滚前用它判断「这个文件在快照之后是否被其他会话/用户改过」：不一致就跳过，
     * 避免用旧快照覆盖别人的改动。
     *
     * 可空：本列随 v60 迁移加入，历史快照没有指纹，按「未知版本」放行。
     * [changeType] 为 CREATE 时该文件当时不存在，指纹为 null 属正常。
     */
    val fingerprint: String? = null
)
