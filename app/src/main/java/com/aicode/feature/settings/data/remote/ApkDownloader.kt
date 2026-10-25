package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.net.AppProxy
import com.aicode.feature.settings.data.repository.buildDownloadUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 更新包下载结果。 */
sealed interface ApkDownloadResult {
    /** 下载完成且（若有摘要）校验通过；[file] 已落在 cacheDir，可直接交给安装器。 */
    data class Success(val file: File, val sha256: String) : ApkDownloadResult

    /** 镜像与直连都失败，[message] 为最后一次失败原因。 */
    data class Failure(val message: String) : ApkDownloadResult
}

/**
 * 更新包下载：默认先走镜像源加速，失败自动回退直连 GitHub 重试一次。
 *
 * 与 [UpdateCheckService] 一样必须挂 [AppProxy.okHttpAuthenticator]——OkHttp 默认不响应代理的
 * 407 质询，漏挂会让配了代理的用户下载必然失败（检测却正常，现象很迷惑）。
 *
 * 文件落 `cacheDir/update/`（`file_paths.xml` 已声明 cache-path，可直接 FileProvider 授权）；
 * 下载前清理目录里的历史版本，边下边算 sha256，失败或取消时删除半成品。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    /**
     * 下载 [url] 指向的更新包。[mirror] 非空且合法时先试镜像，再回退直连。
     *
     * [expectedSize] / [expectedSha256] 任一不符都视为该地址失败（继续换下一个地址），
     * 因为镜像返回 HTML 错误页或半截文件时 HTTP 状态码可能仍是 200。
     */
    suspend fun download(
        url: String,
        mirror: String,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // 清掉历史版本的包，别在 cacheDir 里堆积（每个约 15MB）
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val dest = File(dir, fileName)

        val candidates = buildList {
            buildDownloadUrl(url, mirror).takeIf { it != url }?.let(::add)
            add(url)
        }

        var lastError = ""
        for (candidate in candidates) {
            val result = runCatching {
                fetch(candidate, dest, expectedSize, expectedSha256, onProgress)
            }
            result.getOrNull()?.let { return@withContext ApkDownloadResult.Success(dest, it) }
            lastError = result.exceptionOrNull()?.message.orEmpty().ifBlank { UNKNOWN_ERROR }
            dest.delete()
        }
        ApkDownloadResult.Failure(lastError)
    }

    /** 单地址下载：返回实际文件的 sha256，任一步不符直接抛异常（由调用方换下一个地址）。 */
    private suspend fun fetch(
        url: String,
        dest: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("响应体为空")
                    // 镜像可能不给 Content-Length，退回 API 报的大小用于进度显示
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0L
                    var lastNotify = 0L
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                // 节流：大文件逐块回调会把进度状态刷成重组风暴
                                val now = System.currentTimeMillis()
                                if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                    lastNotify = now
                                    onProgress(read, total)
                                }
                            }
                        }
                    }
                    onProgress(read, total)
                    if (expectedSize > 0 && read != expectedSize) {
                        throw IOException("安装包大小不符（$read/$expectedSize）")
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!expectedSha256.isNullOrBlank() && !sha.equals(expectedSha256, ignoreCase = true)) {
                        throw IOException("安装包摘要校验未通过")
                    }
                    cont.resume(sha)
                } catch (e: Exception) {
                    dest.delete()
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        const val UPDATE_DIR = "update"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val PROGRESS_INTERVAL_MS = 100L
        const val UNKNOWN_ERROR = "下载失败"
    }
}
