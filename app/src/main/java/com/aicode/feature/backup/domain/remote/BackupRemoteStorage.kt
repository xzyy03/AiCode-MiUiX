package com.aicode.feature.backup.domain.remote

import java.io.File

/** 远端的一个备份文件。 */
data class RemoteBackup(
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * 网络备份的远端存储抽象：上传/列目录/下载/删除/连通性测试。
 *
 * 实现不持有流式状态，文件以本地 [File] 传入传出（调用方负责临时文件生命周期）。
 * 所有方法在 [kotlinx.coroutines.Dispatchers.IO] 下调用，失败通过 [Result] 返回。
 */
interface BackupRemoteStorage {
    /** 校验配置与凭据能否连通远端；成功即配置可用。 */
    suspend fun testConnection(): Result<Unit>

    /** 上传本地文件为远端 [name]，覆盖同名对象。 */
    suspend fun upload(name: String, source: File): Result<Unit>

    /** 列出远端目录/前缀下的备份文件（按修改时间倒序）。 */
    suspend fun list(): Result<List<RemoteBackup>>

    /** 下载远端 [name] 到本地 [target]。 */
    suspend fun download(name: String, target: File): Result<Unit>

    /** 删除远端 [name]。 */
    suspend fun delete(name: String): Result<Unit>
}
