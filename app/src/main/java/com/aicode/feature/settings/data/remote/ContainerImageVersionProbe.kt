package com.aicode.feature.settings.data.remote

import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 探测条目的「精确补丁版本」：读取 base URL 下同目录的一个纯文本版本文件（见
 * [com.aicode.feature.agent.domain.container.ContainerImageEntry.versionPath]）。
 *
 * 典型场景是 Debian：其 rootfs 走 Jenkins `lastSuccessfulBuild`，内容随上游滚动，
 * 而内置目录里只有主版本（`12`），需读取 `rootfs.debian_version`（内容如 `12.15`）才能把
 * 「列表显示的版本」与「实际下载到的内容」对上。
 *
 * 失败一律返回 null（调用方回退到内置版本号）：版本号只是展示增强，不该让整页报错。
 * 结果按 URL 在进程内缓存，列表重组不会重复发请求；失败不缓存，下次进页面可重试。
 */
@Singleton
class ContainerImageVersionProbe @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    /** URL → 版本号（仅缓存成功结果）。 */
    private val cache = ConcurrentHashMap<String, String>()

    /** 读取 [url] 指向的版本文件并返回规整后的版本号；任何异常或内容不像版本号都返回 null。 */
    suspend fun probe(url: String): String? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        val version = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                // 版本文件只有十几字节；peekBody 限读，避免把异常的大响应体（如 HTML 错误页）整个读进来
                normalizeImageVersion(response.peekBody(MAX_BODY_BYTES).string())
            }
        }.onFailure { FileLogger.d(TAG, "版本探测失败 url=$url: ${it.message}") }.getOrNull()

        if (version != null) cache[url] = version
        version
    }

    companion object {
        private const val TAG = "ContainerImageVersionProbe"
        private const val MAX_BODY_BYTES = 64L
    }
}

/**
 * 把探测到的原始响应文本规整为可信的版本号；不像版本号的一律返回 null。
 *
 * 这是白名单而非黑名单：以数字开头，只含数字/字母/点/下划线/加号/连字符，长度受限。
 * 目的是把 HTML 错误页、跳转页、鉴权页面挡在外面——把一段 HTML 当成版本号显示给用户
 * 比完全不显示更糟。典型匹配：`12.15`、`3.21.8`、`20.04.5`。
 */
internal fun normalizeImageVersion(raw: String?): String? =
    raw?.trim()?.takeIf(IMAGE_VERSION_PATTERN::matches)

private val IMAGE_VERSION_PATTERN = Regex("""^[0-9][0-9A-Za-z._+-]{0,31}$""")
