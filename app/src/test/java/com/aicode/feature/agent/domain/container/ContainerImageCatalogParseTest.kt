package com.aicode.feature.agent.domain.container

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录 JSON 的解析与「坏数据必须被识别出来」的保证。
 *
 * 回归点：目录有三条来源（内存 → 仓库磁盘缓存 → 内置 assets），逐级回退的前提是
 * **每一级都能判断出自己拿到的是不是有效目录**。若把空/半截 JSON 当成有效目录，
 * 「下载镜像」页就会变成空列表——而磁盘缓存是网络下载物，被截断或写成错误页面的可能性真实存在。
 */
class ContainerImageCatalogParseTest {

    private val minimalManifest = """
        {
          "sources": {
            "official": { "name": { "zh": "官方源" }, "distros": { "alpine": "https://example.com" } }
          },
          "images": [
            {
              "id": "alpine-3.21",
              "name": "Alpine Linux",
              "version": "3.21.8",
              "eol": "2026-11-01",
              "sizeBytes": 3831912,
              "distro": "alpine",
              "path": "alpine/v3.21/releases/{abi}/alpine-minirootfs-3.21.8-{abi}.tar.gz",
              "sha256ByAbi": { "arm64": "f25a96d2" },
              "abiNames": { "arm64": "aarch64", "x86_64": "x86_64" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun 完整目录_解析出源与条目() {
        val parsed = parseContainerImageCatalog(minimalManifest)

        assertNotNull(parsed)
        assertEquals(listOf("official"), parsed!!.sources.keys.toList())
        assertEquals(1, parsed.images.size)
        assertEquals("alpine-3.21", parsed.images[0].id)
        assertEquals("3.21.8", parsed.images[0].version)
        assertEquals("2026-11-01", parsed.images[0].eol)
        assertEquals("f25a96d2", parsed.images[0].sha256ByAbi["arm64"])
    }

    @Test
    fun 空内容_返回null以便回退下一级() {
        assertNull(parseContainerImageCatalog(null))
        assertNull(parseContainerImageCatalog(""))
        assertNull(parseContainerImageCatalog("   \n\t "))
    }

    @Test
    fun 非法JSON_返回null而不是抛异常() {
        assertNull(parseContainerImageCatalog("{"))
        assertNull(parseContainerImageCatalog("<html><body>404 Not Found</body></html>"))
        assertNull(parseContainerImageCatalog("""{"images": [ }"""))
    }

    @Test
    fun 条目为空_返回null避免页面变空列表() {
        // 这正是「空目录」与「坏目录」必须同等对待的原因：拿它当有效值，UI 就只剩空态
        assertNull(parseContainerImageCatalog("""{"sources": {}, "images": []}"""))
    }

    @Test
    fun 缺少images键_返回null() {
        assertNull(parseContainerImageCatalog("""{"sources": {"official": {}}}"""))
    }

    @Test
    fun 多出未知字段_忽略而不整份报废() {
        // 目录由 CI 生成，将来加字段（如生成时间）不应让旧版 App 的列表直接不可用
        val parsed = parseContainerImageCatalog(
            """
            {
              "generatedAt": "2026-10-12T04:30:00Z",
              "futureField": { "nested": [1, 2, 3] },
              "sources": { "official": { "distros": { "alpine": "https://example.com" } } },
              "images": [
                { "id": "a", "name": "Alpine Linux", "version": "3.21.8", "path": "p", "extra": 1 }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(1, parsed!!.images.size)
    }

    @Test
    fun 条目缺可选字段_用默认值补上() {
        val parsed = parseContainerImageCatalog(
            """
            {
              "images": [ { "id": "a", "name": "Alpine Linux", "version": "3.21.8", "path": "p" } ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        val entry = parsed!!.images[0]
        assertEquals("", entry.eol)
        assertEquals("", entry.versionPath)
        assertEquals("", entry.versionSuffix)
        assertEquals(emptyMap<String, String>(), entry.sha256ByAbi)
        assertEquals(0L, entry.sizeBytes)
    }

    /**
     * 直接校验真正打进 APK 的那份内置基线。
     *
     * 该文件由 `scripts/sync-container-images.py` 生成后提交，手改或脚本改坏都不会被编译器发现，
     * 而它一旦坏掉，离线首启与网络拉取失败时「下载镜像」页就没内容了。这里锁住：
     * - 能解析且条目数不少于历史规模（防被截断/清空）；
     * - 每个条目都带 sources 里存在的 distro、两个架构的 abiNames，以及可下载的 path；
     * - 条目 id 唯一（UI 与「已下载」记录都以 id 为键，重复会串行）。
     */
    @Test
    fun 内置assets基线_结构完整且条目可下载() {
        val file = listOf(
            File("src/main/assets/container-images.json"),
            File("app/src/main/assets/container-images.json")
        ).firstOrNull { it.isFile }
        assertNotNull("找不到内置 assets，测试工作目录=${File("").absolutePath}", file)

        val parsed = parseContainerImageCatalog(file!!.readText())
        assertNotNull("内置 assets 解析失败：${file.absolutePath}", parsed)
        val catalog = parsed!!

        assertTrue(
            "内置基线条目数异常（${catalog.images.size}），疑似被截断或清空",
            catalog.images.size >= 10
        )
        assertEquals(
            "条目 id 必须唯一：UI 与已下载记录都以 id 为键",
            catalog.images.size,
            catalog.images.map { it.id }.toSet().size
        )

        catalog.images.forEach { entry ->
            // sources 以「源 id」(official/huawei/…) 为键，distro 在其 distros 子表里；
            // 官方源必须提供每个发行版，否则该条目在任何源下都拼不出下载 URL
            val official = catalog.sources["official"]
            assertNotNull("内置基线缺 official 源", official)
            assertTrue(
                "${entry.id} 的 distro 不在 official 源的 distros 中：${entry.distro}",
                official!!.distros.containsKey(entry.distro)
            )
            assertTrue("${entry.id} 缺 path", entry.path.isNotBlank())
            assertTrue("${entry.id} 缺名称或版本", entry.name.isNotBlank() && entry.version.isNotBlank())
            listOf("arm64", "x86_64").forEach { abi ->
                assertTrue("${entry.id} 缺 $abi 的目录名映射", entry.abiNames[abi].isNullOrBlank().not())
            }
            // 支持周期必须自洽：有中间态时「常规支持结束」不能晚于「完全结束」，
            // 否则两级判定会得出互相矛盾的状态
            if (entry.supportEnds.isNotBlank()) {
                val support = LocalDate.parse(entry.supportEnds)
                val eol = LocalDate.parse(entry.eol)
                assertTrue(
                    "${entry.id} 的 supportEnds(${entry.supportEnds}) 晚于 eol(${entry.eol})",
                    !support.isAfter(eol)
                )
            }
        }
    }
}
