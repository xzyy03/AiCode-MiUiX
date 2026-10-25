package com.aicode.feature.backup.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aicode.feature.backup.domain.remote.BackupTargetType

/**
 * 网络备份目标表。
 *
 * [password] / [secretKey] / [sessionToken] 落库前经 KeystoreCipher 加密；[username] / [accessKey] 明文保存。
 */
@Entity(tableName = "backup_targets")
data class BackupTargetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: BackupTargetType,
    val endpoint: String,
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val sessionToken: String = "",
    val bucket: String = "",
    val region: String = "",
    val pathStyle: Boolean = true,
    val createdAt: Long = 0
)
