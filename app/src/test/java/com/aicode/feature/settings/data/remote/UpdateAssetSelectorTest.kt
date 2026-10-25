package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectorTest {

    // ---- preferredAbiToken ----

    @Test
    fun preferredAbiToken_arm64() {
        assertEquals("arm64", preferredAbiToken(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
    }

    @Test
    fun preferredAbiToken_x86_64() {
        assertEquals("x86_64", preferredAbiToken(listOf("x86_64", "x86")))
    }

    @Test
    fun preferredAbiToken_followsDevicePreferenceOrder() {
        // Build.SUPPORTED_ABIS 按优先级排序，取第一个认识的架构
        assertEquals("arm64", preferredAbiToken(listOf("arm64-v8a", "x86_64")))
        assertEquals("x86_64", preferredAbiToken(listOf("x86_64", "arm64-v8a")))
    }

    @Test
    fun preferredAbiToken_unknownOrEmpty() {
        assertNull(preferredAbiToken(listOf("armeabi-v7a", "mips")))
        assertNull(preferredAbiToken(emptyList()))
    }

    // ---- selectUpdateAsset ----

    private fun asset(name: String) = UpdateAsset(
        name = name,
        url = "https://example.com/$name",
        size = 1L,
        sha256 = null
    )

    @Test
    fun selectUpdateAsset_exactAbiMatch() {
        val assets = listOf(
            asset("aicode-arm64-v1.12.0-rc4.apk"),
            asset("aicode-universal-v1.12.0-rc4.apk"),
            asset("aicode-x86_64-v1.12.0-rc4.apk")
        )
        assertEquals(
            "aicode-arm64-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
        assertEquals(
            "aicode-x86_64-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "x86_64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_fallsBackToUniversal() {
        val assets = listOf(
            asset("aicode-arm64-v1.12.0-rc4.apk"),
            asset("aicode-universal-v1.12.0-rc4.apk")
        )
        // 目标架构的包缺失时退到 universal，而不是直接放弃应用内更新
        assertEquals(
            "aicode-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "x86_64")?.name
        )
        // 设备架构认不出来（null）同样退 universal
        assertEquals(
            "aicode-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", null)?.name
        )
    }

    @Test
    fun selectUpdateAsset_ignoresMappingAndNonApk() {
        val assets = listOf(
            asset("aicode-universal-v1.12.0-rc4-mapping.txt.gz"),
            asset("aicode-universal-v1.12.0-rc4.apk")
        )
        assertEquals(
            "aicode-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_toleratesNameVariation() {
        // 精确名没命中（例如 CI 改了资产命名）时按「含架构段」退让
        val assets = listOf(
            asset("aicode-arm64-1.12.0-rc4.apk"),
            asset("aicode-universal-1.12.0-rc4.apk")
        )
        assertEquals(
            "aicode-arm64-1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_nullWhenNoApk() {
        assertNull(
            selectUpdateAsset(
                listOf(asset("aicode-universal-v1.12.0-rc4-mapping.txt.gz")),
                "v1.12.0-rc4",
                "arm64"
            )
        )
        assertNull(selectUpdateAsset(emptyList(), "v1.12.0-rc4", "arm64"))
    }
}
