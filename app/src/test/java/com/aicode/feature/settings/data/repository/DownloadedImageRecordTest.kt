package com.aicode.feature.settings.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 已下载记录的版本号解析。
 *
 * 回归点：`version` 字段是随镜像目录动态化一起加的。**升级前写入的记录里没有这个字段**，
 * 解码后是空串，于是「有新版可下载」永远不提示——用户下过旧补丁却看不到任何信号，
 * 恰好是这次要解决的问题本身。补上月名解析后，旧记录不必重新下载也能被识别。
 */
class DownloadedImageRecordTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun 旧版记录没有版本字段_解码为空串而不是报错() {
        // 旧版本写进 DataStore 的真实格式
        val legacy = """{"entryId":"alpine-3.21","fileUri":"file:///data/files/rootfs_images/download_alpine-3.21_3.21.7_1699999999999.tar.gz","installed":false}"""

        val record = json.decodeFromString(DownloadedImageRecord.serializer(), legacy)

        assertEquals("alpine-3.21", record.entryId)
        assertEquals("", record.version)
        assertEquals(false, record.installed)
    }

    @Test
    fun 旧版记录_退回文件名解析出的版本() {
        val legacy = DownloadedImageRecord(
            entryId = "alpine-3.21",
            fileUri = "file:///data/files/rootfs_images/download_alpine-3.21_3.21.7_1699999999999.tar.gz"
        )

        assertEquals("3.21.7", legacy.effectiveVersion())
    }

    @Test
    fun 新记录_直接用记录字段() {
        val record = DownloadedImageRecord(
            entryId = "alpine-3.21",
            fileUri = "file:///data/files/rootfs_images/download_alpine-3.21_3.21.8_1699999999999.tar.gz",
            version = "3.21.8"
        )

        assertEquals("3.21.8", record.effectiveVersion())
    }

    @Test
    fun 记录字段为空_仍退回文件名而不是当成无版本() {
        // 兜底路径不能因为字段存在但为空就失效
        val record = DownloadedImageRecord(
            entryId = "debian-12",
            fileUri = "file:///data/files/rootfs_images/download_debian-12_12.15_1.tar.xz",
            version = ""
        )

        assertEquals("12.15", record.effectiveVersion())
    }

    @Test
    fun 旧版Debian记录_版本号含空格与括号也能解析() {
        // 旧版目录里 Debian 的 version 是 `12 (bookworm)`，下载文件名照抄了它
        val record = DownloadedImageRecord(
            entryId = "debian-12",
            fileUri = "file:///data/files/rootfs_images/download_debian-12_12 (bookworm)_1699999999999.tar.xz"
        )

        assertEquals("12 (bookworm)", record.effectiveVersion())
    }

    @Test
    fun 文件名也解析不出_返回空串不误报() {
        val record = DownloadedImageRecord(entryId = "alpine-3.21", fileUri = "file:///tmp/unknown.bin")

        assertEquals("", record.effectiveVersion())
    }

    @Test
    fun 新记录序列化后能原样读回版本() {
        val record = DownloadedImageRecord("alpine-3.21", "file:///a.tar.gz", version = "3.21.8", installed = true)

        val text = json.encodeToString(DownloadedImageRecord.serializer(), record)
        val back = json.decodeFromString(DownloadedImageRecord.serializer(), text)

        assertEquals(record, back)
        assertEquals("3.21.8", back.effectiveVersion())
    }
}
