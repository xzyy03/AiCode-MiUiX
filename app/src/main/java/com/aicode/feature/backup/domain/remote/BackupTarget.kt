package com.aicode.feature.backup.domain.remote

/** 网络备份目标类型。 */
enum class BackupTargetType {
    /** WebDAV：PUT/PROPFIND/GET/DELETE + Basic 认证。 */
    WEBDAV,

    /** S3 兼容对象存储：SigV4 签名，覆盖 AWS S3 / R2 / OSS / COS / MinIO 等。 */
    S3
}

/**
 * 一个网络备份目标（远端账号 + 位置）。
 *
 * 字段按类型复用：WebDAV 用 [endpoint] + [basePath] + [username]/[password]；
 * S3 用 [endpoint] + [region] + [bucket] + [accessKey]/[secretKey]/[sessionToken]，[basePath] 作为对象前缀。
 */
data class BackupTarget(
    val id: String,
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
