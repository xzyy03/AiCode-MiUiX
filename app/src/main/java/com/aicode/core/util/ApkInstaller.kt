package com.aicode.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装的公共入口：未知来源授权判定/引导 + FileProvider 授权 URI + 拉起系统安装器。
 *
 * 「应用内更新」与「聊天里打开 APK 附件」共用同一套逻辑——授权判定与 Intent 拼装任一处理
 * 不当都会退化成「没有权限安装」或「无法打开文件」，两处各写一份必然漂移。
 */
object ApkInstaller {

    /** 系统安装器识别的 MIME 类型。 */
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** 安装动作的结果。 */
    enum class Result {
        /** 已成功拉起系统安装器（用户仍需在系统弹窗里确认一次安装）。 */
        STARTED,

        /** 未授予「安装未知应用」，需先引导用户去设置页开启。 */
        NEED_PERMISSION,

        /** 文件不存在、取不到授权 URI，或系统没有可处理安装 Intent 的应用。 */
        NO_HANDLER
    }

    /** 当前是否已允许安装未知来源应用（minSdk 26，该接口恒可用）。 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * 跳系统「允许安装未知应用」设置页。返回 false 表示跳转失败（极少数定制 ROM
     * 没有该页面），调用方据此提示用户手动前往。
     */
    fun openInstallPermissionSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /**
     * 用系统安装器安装 [apk]。[apk] 必须落在 FileProvider 已声明的目录树内
     * （`res/xml/file_paths.xml` 覆盖 filesDir / cacheDir / 外部存储），否则取不到授权 URI。
     *
     * 必带 `FLAG_ACTIVITY_NEW_TASK`：调用方可能是 ViewModel 的 ApplicationContext，
     * 而非 Activity 的 Context 起 Activity 会直接抛异常（安装器本身是独立任务，加该标志无副作用）。
     */
    fun install(context: Context, apk: File): Result {
        if (!apk.isFile) return Result.NO_HANDLER
        if (!canInstall(context)) return Result.NEED_PERMISSION
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull() ?: return Result.NO_HANDLER
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            Result.STARTED
        }.getOrDefault(Result.NO_HANDLER)
    }
}
