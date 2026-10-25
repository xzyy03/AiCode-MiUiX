package com.aicode.feature.agent.domain.container

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 镜像目录条目的展示名、EOL 判定与 JSON 契约。
 *
 * 回归点：Debian 的 rootfs 走 Jenkins `lastSuccessfulBuild`，内容随上游滚动，而目录里只写了主版本
 * （`12`），界面上完全看不出实际是 12.15 还是 12.16——用户无法判断要不要重下。
 * 现在由 [ContainerImageEntry.versionPath] 运行时探测补上补丁号，这里锁住拼接与回退规则。
 */
class ContainerImageEntryLogicTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(
        name: String = "Debian",
        version: String = "12",
        versionSuffix: String = "",
        versionPath: String = "",
        eol: String = "",
        supportEnds: String = "",
        supportExtension: String = ""
    ) = ContainerImageEntry(
        id = "debian-12",
        name = name,
        version = version,
        versionSuffix = versionSuffix,
        versionPath = versionPath,
        eol = eol,
        supportEnds = supportEnds,
        supportExtension = supportExtension,
        path = "some/rootfs.tar.xz"
    )

    @Test
    fun displayLabel_未探测到版本_回退内置版本与后缀() {
        val e = entry(version = "12", versionSuffix = "(bookworm)")

        assertEquals("Debian 12 (bookworm)", e.displayLabel())
        assertEquals("Debian 12 (bookworm)", e.displayLabel(null))
        assertEquals("Debian 12 (bookworm)", e.displayLabel(""))
    }

    @Test
    fun displayLabel_探测到补丁号_用精确版本替换内置版本() {
        val e = entry(version = "12", versionSuffix = "(bookworm)")

        assertEquals("Debian 12.15 (bookworm)", e.displayLabel("12.15"))
    }

    @Test
    fun displayLabel_无后缀条目_不产生多余空格() {
        val e = entry(name = "Alpine Linux", version = "3.21.8")

        assertEquals("Alpine Linux 3.21.8", e.displayLabel())
    }

    @Test
    fun displayLabel_版本里已含后缀_不重复追加() {
        // 旧记录的版本号是 `12 (bookworm)` 这种自带后缀的写法，再拼一次会变成
        // 「Debian 12 (bookworm) (bookworm)」
        val e = entry(version = "12", versionSuffix = "(bookworm)")

        assertEquals("Debian 12 (bookworm)", e.displayLabel("12 (bookworm)"))
    }

    @Test
    fun eolStatus_未配置日期_不标注() {
        assertEquals(ContainerImageEol.NONE, entry(eol = "").eolStatus(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun eolStatus_日期非法_不标注() {
        assertEquals(ContainerImageEol.NONE, entry(eol = "not-a-date").eolStatus(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun eolStatus_距今不足预警窗口_标记即将停止支持() {
        val today = LocalDate.of(2026, 10, 12)
        // Alpine 3.21 的 EOL 是 2026-11-01，距 2026-10-12 还有 20 天
        assertEquals(ContainerImageEol.SOON, entry(eol = "2026-11-01").eolStatus(today))
    }

    @Test
    fun eolStatus_预警窗口边界_刚好等于窗口天数仍不标注() {
        val today = LocalDate.of(2026, 10, 12)
        // 相差正好 EOL_SOON_DAYS 天时不算「即将」；少一天才进入预警
        val boundary = today.plusDays(EOL_SOON_DAYS).toString()
        assertEquals(ContainerImageEol.NONE, entry(eol = boundary).eolStatus(today))
        assertEquals(
            ContainerImageEol.SOON,
            entry(eol = today.plusDays(EOL_SOON_DAYS - 1).toString()).eolStatus(today)
        )
    }

    @Test
    fun eolStatus_已过日期_标记停止支持() {
        assertEquals(
            ContainerImageEol.ENDED,
            entry(eol = "2026-11-01").eolStatus(LocalDate.of(2026, 11, 2))
        )
    }

    @Test
    fun eolStatus_当天到期_仍算即将而非已停止() {
        assertEquals(
            ContainerImageEol.SOON,
            entry(eol = "2026-11-01").eolStatus(LocalDate.of(2026, 11, 1))
        )
    }

    // ---- 两级支持周期（Ubuntu 的 ESM / Debian 的 LTS）----

    @Test
    fun eolStatus_已过常规支持但未完全结束_标记延长支持() {
        val today = LocalDate.of(2026, 10, 12)
        // Debian 12 的实际数据：常规支持 2026-07-11 已结束，LTS 到 2028-06-30
        val e = entry(eol = "2028-06-30", supportEnds = "2026-07-11", supportExtension = "LTS")

        assertEquals(ContainerImageEol.EXTENDED, e.eolStatus(today))
    }

    @Test
    fun eolStatus_Ubuntu实际数据_已过标准支持进入ESM() {
        // Ubuntu 20.04：标准支持 2025-05-31 结束，ESM 到 2030-04-23。
        // 按旧口径（只比一个日期）它会被误标成「已停止支持」——这正是本次修掉的口径问题。
        val e = entry(eol = "2030-04-23", supportEnds = "2025-05-31", supportExtension = "ESM")

        assertEquals(ContainerImageEol.EXTENDED, e.eolStatus(LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun eolStatus_常规支持将在预警窗口内结束_标记即将停止支持() {
        val today = LocalDate.of(2026, 10, 12)
        val e = entry(eol = "2028-06-30", supportEnds = today.plusDays(10).toString())

        assertEquals(ContainerImageEol.SOON, e.eolStatus(today))
    }

    @Test
    fun eolStatus_完全结束在即_即使处于延长支持期也优先提示即将停止() {
        val today = LocalDate.of(2026, 10, 12)
        // 迫近的截止比「已进入延长支持」更该被看见
        val e = entry(eol = today.plusDays(5).toString(), supportEnds = "2024-01-01", supportExtension = "LTS")

        assertEquals(ContainerImageEol.SOON, e.eolStatus(today))
    }

    @Test
    fun eolStatus_两个日期相同_不产生延长支持态() {
        val today = LocalDate.of(2026, 10, 12)
        // 脚本对没有中间态的发行版会省略 supportEnds；即便上游给了相同值也不该标成「延长支持」
        val e = entry(eol = "2028-06-30", supportEnds = "2028-06-30", supportExtension = "LTS")

        assertEquals(ContainerImageEol.NONE, e.eolStatus(today))
    }

    @Test
    fun eolStatus_常规支持日期非法_按无中间态处理() {
        val today = LocalDate.of(2026, 10, 12)
        val e = entry(eol = "2028-06-30", supportEnds = "不是日期")

        assertEquals(ContainerImageEol.NONE, e.eolStatus(today))
    }

    // ---- 旧记录的版本号回退：从下载文件名解析 ----

    @Test
    fun 文件名解析_三种扩展名都能取出版本() {
        assertEquals("3.21.7", versionFromDownloadFileName("alpine-3.21", "download_alpine-3.21_3.21.7_1699999999999.tar.gz"))
        assertEquals("3.21.7", versionFromDownloadFileName("alpine-3.21", "download_alpine-3.21_3.21.7_1699999999999.tgz"))
        assertEquals("12.15", versionFromDownloadFileName("debian-12", "download_debian-12_12.15_1699999999999.tar.xz"))
    }

    @Test
    fun 文件名解析_支持file协议uri() {
        val uri = "file:///data/user/0/com.aicode/files/rootfs_images/download_alpine-3.21_3.21.7_1699999999999.tar.gz"

        assertEquals("3.21.7", versionFromDownloadFileName("alpine-3.21", uri))
    }

    @Test
    fun 文件名解析_条目id对不上_返回空串() {
        // 防误配：文件名里的 id 与当前条目不一致时不猜
        assertEquals("", versionFromDownloadFileName("alpine-3.22", "download_alpine-3.21_3.21.7_1699999999999.tar.gz"))
    }

    @Test
    fun 文件名解析_不符合命名规则_返回空串() {
        assertEquals("", versionFromDownloadFileName("alpine-3.21", ""))
        assertEquals("", versionFromDownloadFileName("", "download_alpine-3.21_3.21.7_1.tar.gz"))
        assertEquals("", versionFromDownloadFileName("alpine-3.21", "rootfs.tar.gz"))
        assertEquals("", versionFromDownloadFileName("alpine-3.21", "download_alpine-3.21_3.21.7.tar.gz"))
    }

    @Test
    fun 文件名解析_旧版Debian版本号含空格与括号_也能取出() {
        // 旧版目录里 Debian 的 version 写作 `12 (bookworm)`，下载文件名照抄了它；
        // 只认数字的正则会漏掉这类记录，让旧用户看不到「有新版」
        val name = "download_debian-12_12 (bookworm)_1699999999999.tar.xz"

        assertEquals("12 (bookworm)", versionFromDownloadFileName("debian-12", name))
    }

    @Test
    fun hasNewerVersion_旧版Debian记录_能识别出有新版() {
        // 串起旧记录 -> 文件名 -> 与当前探测版本比对的完整链路
        val legacyVersion = versionFromDownloadFileName(
            "debian-12",
            "download_debian-12_12 (bookworm)_1699999999999.tar.xz"
        )

        assertEquals(true, hasNewerVersion(legacyVersion, "12.15"))
    }

    @Test
    fun json_解析新增字段() {
        val parsed = json.decodeFromString(
            ContainerImageEntry.serializer(),
            """
            {
              "id": "debian-12",
              "name": "Debian",
              "version": "12",
              "versionSuffix": "(bookworm)",
              "versionPath": "job/{abi}/artifact/bookworm/rootfs.debian_version",
              "eol": "2028-06-30",
              "supportEnds": "2026-07-11",
              "supportExtension": "LTS",
              "path": "job/{abi}/artifact/bookworm/rootfs.tar.xz",
              "abiNames": { "arm64": "arm64v8", "x86_64": "amd64" }
            }
            """.trimIndent()
        )

        assertEquals("(bookworm)", parsed.versionSuffix)
        assertEquals("job/{abi}/artifact/bookworm/rootfs.debian_version", parsed.versionPath)
        assertEquals("2028-06-30", parsed.eol)
        assertEquals("2026-07-11", parsed.supportEnds)
        assertEquals("LTS", parsed.supportExtension)
    }

    @Test
    fun json_旧目录缺新字段_取默认值不抛异常() {
        val parsed = json.decodeFromString(
            ContainerImageEntry.serializer(),
            """
            {
              "id": "alpine-3.21",
              "name": "Alpine Linux",
              "version": "3.21.7",
              "path": "alpine/v3.21/releases/{abi}/alpine-minirootfs-3.21.7-{abi}.tar.gz"
            }
            """.trimIndent()
        )

        assertEquals("", parsed.versionSuffix)
        assertEquals("", parsed.versionPath)
        assertEquals("", parsed.eol)
        assertEquals("", parsed.supportEnds)
        assertEquals("", parsed.supportExtension)
        assertEquals(emptyMap<String, String>(), parsed.sha256ByAbi)
        assertEquals("Alpine Linux 3.21.7", parsed.displayLabel())
    }

    @Test
    fun json_解析各架构校验和() {
        val parsed = json.decodeFromString(
            ContainerImageEntry.serializer(),
            """
            {
              "id": "alpine-3.21",
              "name": "Alpine Linux",
              "version": "3.21.8",
              "path": "alpine/v3.21/releases/{abi}/alpine-minirootfs-3.21.8-{abi}.tar.gz",
              "sha256ByAbi": {
                "arm64": "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001",
                "x86_64": "6ea461b0225faad280b7e13df878b30fdd6d969dde25ef05f85a1bcec9033348"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001",
            parsed.sha256ByAbi["arm64"]
        )
        assertEquals(
            "6ea461b0225faad280b7e13df878b30fdd6d969dde25ef05f85a1bcec9033348",
            parsed.sha256ByAbi["x86_64"]
        )
    }

    @Test
    fun hasNewerVersion_下载时的版本与当前版本不同_提示有新版() {
        assertEquals(true, hasNewerVersion("3.21.7", "3.21.8"))
        assertEquals(true, hasNewerVersion("12.15", "12.16"))
    }

    @Test
    fun hasNewerVersion_版本一致_不提示() {
        assertEquals(false, hasNewerVersion("3.21.8", "3.21.8"))
    }

    @Test
    fun hasNewerVersion_旧记录没有版本号_不提示() {
        // 升级前下载的记录没有 version 字段：宁可漏提示，也不能把用户刚下好的镜像误报成「有新版」
        assertEquals(false, hasNewerVersion(null, "3.21.8"))
        assertEquals(false, hasNewerVersion("", "3.21.8"))
    }
}
