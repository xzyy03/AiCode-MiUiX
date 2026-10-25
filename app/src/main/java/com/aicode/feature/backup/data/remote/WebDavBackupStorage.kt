package com.aicode.feature.backup.data.remote

import com.aicode.feature.backup.domain.remote.BackupRemoteStorage
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.RemoteBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WebDAV 备份存储：PUT 上传、GET 下载、DELETE 删除、PROPFIND(Depth:1) 列目录，Basic 认证。
 *
 * [BackupTarget.endpoint] 是基础 URL（可含路径），[BackupTarget.basePath] 是远端子目录。
 */
class WebDavBackupStorage(
    private val client: OkHttpClient,
    private val target: BackupTarget
) : BackupRemoteStorage {

    private val baseUrl: String = target.endpoint.trim().trimEnd('/')
    private val dir: String = target.basePath.trim().trim('/', ' ')
    private val auth: String = Credentials.basic(target.username, target.password)

    private fun objectUrl(name: String): String =
        if (dir.isEmpty()) "$baseUrl/$name" else "$baseUrl/$dir/$name"

    private fun dirUrl(): String =
        if (dir.isEmpty()) "$baseUrl/" else "$baseUrl/$dir/"

    private fun Request.Builder.auth(): Request.Builder = header("Authorization", auth)

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Depth:0 仅探测目标目录本身是否存在且可访问
            val request = Request.Builder()
                .url(dirUrl())
                .auth()
                .header("Depth", "0")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    error("WebDAV 连接失败：HTTP ${response.code}")
                }
            }
            Unit
        }
    }

    override suspend fun upload(name: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(objectUrl(name))
                .auth()
                .put(source.asRequestBody(OCTET_STREAM))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("上传失败：HTTP ${response.code}")
            }
            Unit
        }
    }

    override suspend fun list(): Result<List<RemoteBackup>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(dirUrl())
                .auth()
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    error("读取远端备份列表失败：HTTP ${response.code}")
                }
                response.body?.byteStream()?.readBytes() ?: ByteArray(0)
            }
            parseMultiStatus(body)
                .filter { it.name.endsWith(SUFFIX) }
                .sortedByDescending { it.lastModified }
        }
    }

    override suspend fun download(name: String, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(objectUrl(name)).auth().get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                val body = response.body ?: error("下载失败：响应为空")
                target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
            Unit
        }
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(objectUrl(name)).auth().delete().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("删除失败：HTTP ${response.code}")
            }
            Unit
        }
    }

    /** 解析 207 Multi-Status，取每个非集合资源的文件名、大小与修改时间。 */
    private fun parseMultiStatus(xml: ByteArray): List<RemoteBackup> {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.inputStream(), null)

        val result = mutableListOf<RemoteBackup>()
        var href: String? = null
        var length: Long? = null
        var modified: Long? = null
        var isCollection = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> {
                        href = null; length = null; modified = null; isCollection = false
                    }
                    "href" -> href = parser.nextText()
                    "getcontentlength" -> length = parser.nextText().trim().toLongOrNull()
                    "getlastmodified" -> modified = parseHttpDate(parser.nextText())
                    "collection" -> isCollection = true
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                val path = href
                if (!isCollection && path != null) {
                    val name = path.trimEnd('/').substringAfterLast('/')
                    if (name.isNotEmpty()) {
                        result += RemoteBackup(name, length ?: 0L, modified ?: 0L)
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private companion object {
        const val SUFFIX = ".tar.gz"
        val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:resourcetype/>
                  </d:prop>
                </d:propfind>"""
    }
}
