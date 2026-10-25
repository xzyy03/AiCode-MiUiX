package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** 更新通道：稳定版（仅正式版）/ 最新版（含预览版）。 */
enum class UpdateChannel {
    STABLE, LATEST
}

/**
 * 「自动检查更新」偏好存储：开关、更新通道、上次检测日期统一存 SharedPreferences
 * （键值文件，非数据库）。上次检测日期不写数据库，但也不放 cacheDir——缓存目录可能被
 * 系统清理导致「每天一次」失效，持久化到 SharedPreferences 更可靠。
 */
@Singleton
class UpdateCheckSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)

    /** 自动检查更新开关，默认开启。 */
    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 更新通道，默认稳定版。 */
    var channel: UpdateChannel
        get() = prefs.getString(KEY_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /**
     * 更新包下载镜像源：留空表示直连 GitHub。默认走 gh-proxy 的 CDN 节点
     * （实测整包 sha256 与 GitHub API 的 digest 一致，首字节延迟也最低）。
     */
    var downloadMirror: String
        get() = prefs.getString(KEY_DOWNLOAD_MIRROR, DEFAULT_DOWNLOAD_MIRROR) ?: DEFAULT_DOWNLOAD_MIRROR
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_MIRROR, value.trim()).apply()
        }

    /** 今天是否已检测过（按记录日期判断）。 */
    fun hasCheckedToday(): Boolean = prefs.getString(KEY_LAST_CHECKED, null) == today()

    /** 记录今天已检测。 */
    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECKED, today()).apply()
    }

    /**
     * 把版本与更新信息写入 `~/.aicode/update-info.json`（宿主 filesDir/aicode/），
     * 供容器内 AI 读取（当前版本、更新通道、最近检查时间、最新版本与逐版本更新日志）。
     * 写入失败静默，不影响检测流程。
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "aicode").apply { mkdirs() }
            val obj = JSONObject()
            obj.put("currentVersion", currentVersion)
            obj.put("channel", channel.name.lowercase())
            obj.put("lastCheckedAt", java.time.LocalDateTime.now().toString())
            when (result) {
                is UpdateCheckResult.UpToDate -> obj.put("hasUpdate", false)
                is UpdateCheckResult.NewVersion -> {
                    obj.put("hasUpdate", true)
                    obj.put("latestVersion", result.info.latestTag)
                    val updates = org.json.JSONArray()
                    result.info.updates.forEach { u ->
                        updates.put(
                            JSONObject().apply {
                                put("tag", u.tag)
                                put("changelog", u.changelog)
                            }
                        )
                    }
                    obj.put("updates", updates)
                }
                is UpdateCheckResult.Error -> {
                    obj.put("hasUpdate", false)
                    obj.put("error", result.message)
                }
            }
            File(dir, "update-info.json").writeText(obj.toString())
        }
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private companion object {
        const val KEY_ENABLED = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_LAST_CHECKED = "last_checked_date"
        const val KEY_DOWNLOAD_MIRROR = "download_mirror"
    }
}

/** 默认下载镜像源：gh-proxy 的 CDN 节点。 */
const val DEFAULT_DOWNLOAD_MIRROR = "https://cdn.gh-proxy.com/"

/** 备选镜像源（设置页快捷项）：gh-proxy 的 IPv6 优选节点。 */
const val ALT_DOWNLOAD_MIRROR = "https://v6.gh-proxy.org/"

/** 镜像源是否合法：留空（直连）或以 http/https 开头。 */
fun isValidDownloadMirror(value: String): Boolean {
    val v = value.trim()
    return v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")
}

/**
 * 把 GitHub 原始下载地址套上镜像前缀。
 *
 * 留空或非法（非 http/https）时原样返回直连地址；含 `{url}` 占位符时按占位符替换，
 * 否则按 gh-proxy 系约定做前缀拼接：`https://cdn.gh-proxy.com/https://github.com/...`。
 */
fun buildDownloadUrl(original: String, mirror: String): String {
    val m = mirror.trim()
    if (!isValidDownloadMirror(m) || m.isEmpty()) return original
    return if (m.contains("{url}")) m.replace("{url}", original) else m.trimEnd('/') + "/" + original
}
