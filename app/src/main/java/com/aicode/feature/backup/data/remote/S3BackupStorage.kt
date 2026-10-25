package com.aicode.feature.backup.data.remote

import com.aicode.feature.backup.domain.remote.BackupRemoteStorage
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.RemoteBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant

/**
 * S3 兼容对象存储：PUT/GET/DELETE + ListObjectsV2，SigV4 签名。
 *
 * [BackupTarget.endpoint] 是服务地址（如 `https://s3.us-east-1.amazonaws.com`、`https://<id>.r2.cloudflarestorage.com`、
 * `http://minio.local:9000`）；[BackupTarget.pathStyle] 为 true 时用路径风格 `endpoint/bucket/key`，
 * 否则用虚拟主机风格 `bucket.endpoint/key`；[BackupTarget.basePath] 作为对象前缀。
 */
class S3BackupStorage(
    private val client: OkHttpClient,
    private val target: BackupTarget
) : BackupRemoteStorage {

    private val endpointUrl: HttpUrl =
        target.endpoint.trim().trimEnd('/').toHttpUrlOrNull()
            ?: error("无效的 S3 Endpoint：${target.endpoint}")

    private val bucket: String = target.bucket.trim()
    private val region: String = target.region.trim().ifEmpty { "us-east-1" }
    private val prefix: String = target.basePath.trim().trim('/')

    init {
        require(bucket.isNotEmpty()) { "S3 存储桶不能为空" }
    }

    private fun listPrefix(): String = if (prefix.isEmpty()) "" else "$prefix/"

    private fun objectKey(name: String): List<String> =
        (if (prefix.isEmpty()) name else "$prefix/$name").split('/').filter { it.isNotEmpty() }

    /** 构造对象 URL（含 bucket 路径或虚拟主机前缀，不含 query）。 */
    private fun buildUrl(segments: List<String>): HttpUrl {
        val builder = endpointUrl.newBuilder().query(null)
        if (target.pathStyle) {
            builder.addPathSegment(bucket)
        } else {
            builder.host("$bucket.${endpointUrl.host}")
        }
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun signHeaders(
        method: String,
        url: HttpUrl,
        canonicalQuery: String,
        payloadHash: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> = S3SigV4.sign(
        method = method,
        url = url,
        canonicalQuery = canonicalQuery,
        region = region,
        accessKey = target.accessKey,
        secretKey = target.secretKey,
        sessionToken = target.sessionToken.ifEmpty { null },
        payloadHash = payloadHash,
        extraHeaders = extraHeaders
    )

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // max-keys=0：仅校验凭据与桶可达，不拉取列表内容
            val (url, query) = listUrl("", maxKeys = 0)
            val request = Request.Builder()
                .url(url)
                .apply { signHeaders("GET", url, query, S3SigV4.emptyPayloadHash()).forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("S3 连接失败：HTTP ${response.code}")
            }
            Unit
        }
    }

    override suspend fun upload(name: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(objectKey(name))
            val payloadHash = S3SigV4.sha256Hex(source)
            // 上传带 Content-Type，必须一并签名：部分实现（如 Ceph RGW）会拒绝「存在却未签名」的 Content-Type
            val contentType = OCTET_STREAM_VALUE
            val request = Request.Builder()
                .url(url)
                .apply {
                    signHeaders("PUT", url, "", payloadHash, mapOf("Content-Type" to contentType))
                        .forEach { (k, v) -> header(k, v) }
                    header("Content-Type", contentType)
                }
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
            val (url, query) = listUrl(listPrefix(), maxKeys = null)
            val request = Request.Builder()
                .url(url)
                .apply { signHeaders("GET", url, query, S3SigV4.emptyPayloadHash()).forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("读取远端备份列表失败：HTTP ${response.code}")
                response.body?.byteStream()?.readBytes() ?: ByteArray(0)
            }
            parseListResult(body)
                .filter { it.name.endsWith(SUFFIX) }
                .sortedByDescending { it.lastModified }
        }
    }

    override suspend fun download(name: String, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(objectKey(name))
            val request = Request.Builder()
                .url(url)
                .apply { signHeaders("GET", url, "", S3SigV4.emptyPayloadHash()).forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
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
            val url = buildUrl(objectKey(name))
            val request = Request.Builder()
                .url(url)
                .apply { signHeaders("DELETE", url, "", S3SigV4.emptyPayloadHash()).forEach { (k, v) -> header(k, v) } }
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("删除失败：HTTP ${response.code}")
            }
            Unit
        }
    }

    /** 构造 ListObjectsV2 请求 URL：canonical query 与最终 URL 用同一份编码串。 */
    private fun listUrl(prefix: String, maxKeys: Int?): Pair<HttpUrl, String> {
        val params = buildList {
            add("list-type" to "2")
            if (prefix.isNotEmpty()) add("prefix" to prefix)
            if (maxKeys != null) add("max-keys" to maxKeys.toString())
        }.sortedBy { it.first }

        val canonicalQuery = params.joinToString("&") {
            "${S3SigV4.uriEncode(it.first)}=${S3SigV4.uriEncode(it.second)}"
        }
        val url = buildUrl(emptyList()).newBuilder().encodedQuery(canonicalQuery).build()
        return url to canonicalQuery
    }

    private fun parseListResult(xml: ByteArray): List<RemoteBackup> {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(xml.inputStream(), null)

        val result = mutableListOf<RemoteBackup>()
        var key: String? = null
        var size = 0L
        var modified = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name == "Contents" -> {
                    key = null; size = 0L; modified = 0L
                }
                event == XmlPullParser.START_TAG && parser.name == "Key" -> key = parser.nextText()
                event == XmlPullParser.START_TAG && parser.name == "Size" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                event == XmlPullParser.START_TAG && parser.name == "LastModified" -> modified = parseIso(parser.nextText())
                event == XmlPullParser.END_TAG && parser.name == "Contents" -> {
                    val fullKey = key
                    if (fullKey != null) {
                        val name = fullKey.removePrefix(listPrefix())
                        if (name.isNotEmpty()) result += RemoteBackup(name, size, modified)
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseIso(value: String): Long = runCatching {
        Instant.parse(value.trim()).toEpochMilli()
    }.getOrDefault(0L)

    private companion object {
        const val SUFFIX = ".tar.gz"
        const val OCTET_STREAM_VALUE = "application/octet-stream"
        val OCTET_STREAM = OCTET_STREAM_VALUE.toMediaType()
    }
}
