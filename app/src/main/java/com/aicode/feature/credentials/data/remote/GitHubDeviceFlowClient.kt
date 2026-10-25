package com.aicode.feature.credentials.data.remote

import com.aicode.core.net.AppProxy
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub 设备码流返回的设备授权信息。 */
data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

/** 轮询 access token 的结果。 */
sealed interface GitHubTokenPollResult {
    data class Success(val accessToken: String) : GitHubTokenPollResult

    /** 用户尚未完成授权，按间隔继续轮询。 */
    data object Pending : GitHubTokenPollResult

    /** 轮询过快，需延长间隔后继续。 */
    data object SlowDown : GitHubTokenPollResult

    /** 设备码过期，需重新申请。 */
    data object Expired : GitHubTokenPollResult

    /** 用户拒绝授权。 */
    data object Denied : GitHubTokenPollResult

    data class Error(val code: String) : GitHubTokenPollResult
}

/** GitHub App 的 slug（安装页 URL 用），与 App 名称对应。 */
const val GITHUB_APP_SLUG = "aicode-app"

/** GitHub App 安装页：用户在此选择要授权的仓库。 */
const val GITHUB_APP_INSTALL_URL = "https://github.com/apps/$GITHUB_APP_SLUG/installations/new"

/**
 * GitHub App 设备码流（Device Authorization Grant）客户端：无浏览器场景下，让用户在浏览器
 * 输入设备码完成授权，再换取 user access token。GitHub App 不传 scope，权限由 App 注册时的
 * 细粒度权限决定，用户安装时可选择授权的仓库。
 *
 * 参考 https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app#using-the-device-flow-to-generate-a-user-access-token
 * 与其它网络组件一致，OkHttpClient 挂 [AppProxy.okHttpAuthenticator]，随全局代理配置走。
 */
@Singleton
class GitHubDeviceFlowClient @Inject constructor() {

    private companion object {
        /**
         * GitHub App 的 client_id（设备码流不需要 client_secret）。
         * 该 App 必须在设置里启用 Device Flow；权限由 App 注册的细粒度权限决定。
         */
        const val GITHUB_APP_CLIENT_ID = "Iv23likIO5f2MvtjrHZq"

        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_API_URL = "https://api.github.com/user"
        const val USER_INSTALLATIONS_URL = "https://api.github.com/user/installations"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    /**
     * 申请设备码与用户验证码。失败（如 client_id 无效、未启用设备流）时抛出携带 GitHub 错误码的异常。
     */
    suspend fun requestDeviceCode(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GITHUB_APP_CLIENT_ID)
            .build()
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("error")?.asString?.let { throw IllegalStateException(it) }
            GitHubDeviceCode(
                deviceCode = obj.get("device_code").asString,
                userCode = obj.get("user_code").asString,
                verificationUri = obj.get("verification_uri").asString,
                expiresInSeconds = obj.get("expires_in").asInt,
                intervalSeconds = obj.get("interval").asInt
            )
        }
    }

    /** 轮询用户是否已完成授权；成功时返回 access token，未完成返回 [GitHubTokenPollResult.Pending]。 */
    suspend fun pollAccessToken(deviceCode: String): GitHubTokenPollResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GITHUB_APP_CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT_TYPE)
            .build()
        val req = Request.Builder()
            .url(ACCESS_TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("access_token")?.asString?.takeIf { it.isNotBlank() }?.let {
                return@withContext GitHubTokenPollResult.Success(it)
            }
            when (obj.get("error")?.asString) {
                "authorization_pending" -> GitHubTokenPollResult.Pending
                "slow_down" -> GitHubTokenPollResult.SlowDown
                "expired_token", "token_expired" -> GitHubTokenPollResult.Expired
                "access_denied" -> GitHubTokenPollResult.Denied
                else -> GitHubTokenPollResult.Error(obj.get("error")?.asString ?: "unknown_error")
            }
        }
    }

    /** 用 access token 拉取登录用户名（凭据的用户名部分）。 */
    suspend fun fetchUserLogin(accessToken: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(USER_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            JsonParser.parseString(body).asJsonObject.get("login").asString
        }
    }

    /**
     * 判断当前用户是否已安装本 GitHub App（决定是否需要在登录后引导去安装）。
     * 仅授权而未安装时，令牌没有仓库访问权，需引导用户到安装页选择仓库。
     */
    suspend fun isAppInstalled(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(USER_INSTALLATIONS_URL)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val installations = JsonParser.parseString(body).asJsonObject.getAsJsonArray("installations")
                ?: return@withContext false
            installations.any { it.asJsonObject.get("app_slug")?.asString == GITHUB_APP_SLUG }
        }
    }
}
