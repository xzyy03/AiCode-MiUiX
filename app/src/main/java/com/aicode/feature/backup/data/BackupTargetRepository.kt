package com.aicode.feature.backup.data

import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import com.aicode.feature.backup.data.remote.S3BackupStorage
import com.aicode.feature.backup.data.remote.WebDavBackupStorage
import com.aicode.feature.backup.domain.remote.BackupRemoteStorage
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.BackupTargetType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络备份目标的读写与远端存储构造。
 *
 * 敏感字段（WebDAV 密码、S3 SecretKey / SessionToken）经 KeystoreCipher 加解密，明文只在内存流转。
 */
@Singleton
class BackupTargetRepository @Inject constructor(
    private val dao: BackupTargetDao,
    private val okHttpClient: OkHttpClient
) {
    val targets: Flow<List<BackupTarget>> = dao.getAll().map { list -> list.map { it.toModel() } }

    suspend fun getTarget(id: String): BackupTarget? = dao.getById(id)?.toModel()

    /** 新建或更新目标；[BackupTarget.id] 为空时分配新 id。 */
    suspend fun save(target: BackupTarget) {
        val normalized = if (target.id.isBlank()) target.copy(id = UUID.randomUUID().toString()) else target
        dao.upsert(normalized.toEntity())
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    /** 按目标类型构造对应的远端存储实现。 */
    fun storageFor(target: BackupTarget): BackupRemoteStorage = when (target.type) {
        BackupTargetType.WEBDAV -> WebDavBackupStorage(okHttpClient, target)
        BackupTargetType.S3 -> S3BackupStorage(okHttpClient, target)
    }

    private fun BackupTargetEntity.toModel() = BackupTarget(
        id = id,
        name = name,
        type = type,
        endpoint = endpoint,
        basePath = basePath,
        username = username,
        password = KeystoreCipher.decryptString(password),
        accessKey = accessKey,
        secretKey = KeystoreCipher.decryptString(secretKey),
        sessionToken = KeystoreCipher.decryptString(sessionToken),
        bucket = bucket,
        region = region,
        pathStyle = pathStyle,
        createdAt = createdAt
    )

    private fun BackupTarget.toEntity() = BackupTargetEntity(
        id = id,
        name = name,
        type = type,
        endpoint = endpoint,
        basePath = basePath,
        username = username,
        password = KeystoreCipher.encryptString(password),
        accessKey = accessKey,
        secretKey = KeystoreCipher.encryptString(secretKey),
        sessionToken = KeystoreCipher.encryptString(sessionToken),
        bucket = bucket,
        region = region,
        pathStyle = pathStyle,
        createdAt = if (createdAt == 0L) System.currentTimeMillis() else createdAt
    )
}
