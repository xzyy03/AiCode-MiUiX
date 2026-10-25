package com.aicode.feature.backup.data.remote

import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 签名（服务固定 s3）。
 *
 * 只做 S3 需要的部分：单次签名、必需头（host / x-amz-content-sha256 / x-amz-date / 可选 x-amz-security-token），
 * 查询串由调用方拼好并保证与最终 URL 一致（用 encodedQuery 落库，避免二次编码差异）。
 */
internal object S3SigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun emptyPayloadHash(): String = EMPTY_SHA256

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 流式计算文件 SHA-256，避免整文件入内存。 */
    fun sha256Hex(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    /** RFC 3986 编码（unreserved 保留，空格 %20，其余 %XX 大写），用于 canonical query。 */
    fun uriEncode(value: String): String {
        val sb = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> sb.append(c.toChar())
                else -> sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * 用给定的 canonicalQuery 与 payloadHash 计算签名头。
     * @param extraHeaders 需一并纳入签名的额外请求头（键大小写不敏感，如 `Content-Type`）；
     *   调用方必须保证实际请求也带同名同值的头，否则签名不符。
     * @return 需要附加到请求的头（x-amz-date / x-amz-content-sha256 / Authorization，必要时含 x-amz-security-token）
     */
    fun sign(
        method: String,
        url: HttpUrl,
        canonicalQuery: String,
        region: String,
        accessKey: String,
        secretKey: String,
        sessionToken: String?,
        payloadHash: String,
        extraHeaders: Map<String, String> = emptyMap(),
        now: Instant = Instant.now()
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val dateStamp = DATE_STAMP.format(now)

        val headerPairs = linkedMapOf(
            "host" to hostHeader(url),
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        )
        extraHeaders.forEach { (name, value) -> headerPairs[name.lowercase()] = value }
        if (!sessionToken.isNullOrEmpty()) headerPairs["x-amz-security-token"] = sessionToken

        val sortedNames = headerPairs.keys.sorted()
        val canonicalHeaders = sortedNames.joinToString("") { "$it:${headerPairs.getValue(it)}\n" }
        val signedHeaders = sortedNames.joinToString(";")

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(url.encodedPath).append('\n')
            append(canonicalQuery).append('\n')
            // AWS SigV4 规范：CanonicalHeaders 自身以 '\n' 结尾，公式在此再补一个 '\n'，
            // 于是 headers 块与 signedHeaders 之间会留一个空行；少了它 Ceph/AWS 均返回 SignatureDoesNotMatch
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/$region/$SERVICE/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val signingKey = signingKey(secretKey, dateStamp, region)
        val signature = hex(hmac(signingKey, stringToSign))

        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        val headers = linkedMapOf(
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to authorization
        )
        if (!sessionToken.isNullOrEmpty()) headers["x-amz-security-token"] = sessionToken
        return headers
    }

    private fun signingKey(secretKey: String, dateStamp: String, region: String): ByteArray {
        val kDate = hmac(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, SERVICE)
        return hmac(kService, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hostHeader(url: HttpUrl): String {
        val defaultPort = if (url.isHttps) 443 else 80
        return if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
