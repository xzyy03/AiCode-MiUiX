package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadUrlTest {

    private val original =
        "https://github.com/jieapi/aicode/releases/download/v1.12.0-rc4/aicode-arm64-v1.12.0-rc4.apk"

    // ---- isValidDownloadMirror ----

    @Test
    fun isValidDownloadMirror_acceptsHttpAndHttps() {
        assertTrue(isValidDownloadMirror("https://cdn.gh-proxy.com/"))
        assertTrue(isValidDownloadMirror("http://127.0.0.1:8080/gh"))
        assertTrue(isValidDownloadMirror("  https://cdn.gh-proxy.com/  "))
    }

    @Test
    fun isValidDownloadMirror_blankMeansDirect() {
        assertTrue(isValidDownloadMirror(""))
        assertTrue(isValidDownloadMirror("   "))
    }

    @Test
    fun isValidDownloadMirror_rejectsOtherSchemes() {
        assertFalse(isValidDownloadMirror("cdn.gh-proxy.com/"))
        assertFalse(isValidDownloadMirror("ftp://example.com/"))
        assertFalse(isValidDownloadMirror("file:///tmp/"))
    }

    // ---- buildDownloadUrl ----

    @Test
    fun buildDownloadUrl_prefixConcat() {
        assertEquals(
            "https://cdn.gh-proxy.com/$original",
            buildDownloadUrl(original, "https://cdn.gh-proxy.com/")
        )
        // 尾部多余的斜杠不该拼出双斜杠
        assertEquals(
            "https://cdn.gh-proxy.com/$original",
            buildDownloadUrl(original, "https://cdn.gh-proxy.com")
        )
    }

    @Test
    fun buildDownloadUrl_urlPlaceholder() {
        assertEquals(
            "https://my-proxy.example.com/fetch?url=$original",
            buildDownloadUrl(original, "https://my-proxy.example.com/fetch?url={url}")
        )
    }

    @Test
    fun buildDownloadUrl_blankOrInvalidFallsBackToDirect() {
        assertEquals(original, buildDownloadUrl(original, ""))
        assertEquals(original, buildDownloadUrl(original, "   "))
        assertEquals(original, buildDownloadUrl(original, "cdn.gh-proxy.com/"))
    }

    @Test
    fun buildDownloadUrl_builtInMirrorsAreUsable() {
        assertEquals("https://cdn.gh-proxy.com/$original", buildDownloadUrl(original, DEFAULT_DOWNLOAD_MIRROR))
        assertTrue(isValidDownloadMirror(ALT_DOWNLOAD_MIRROR))
    }
}
