package com.aicode.feature.workspace.domain.remote.ftp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.R
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ftpServerDataStore by preferencesDataStore(
    name = "ftp_server_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class FtpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "FtpServerManager"

        /** 旧版本内置的固定弱口令；读到即视为「未设置」，改用随机口令。 */
        const val LEGACY_DEFAULT_PASSWORD = "123456"

        private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateRandomPassword(): String {
            val random = SecureRandom()
            return (1..10).map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")
        }

        val PORT_KEY = intPreferencesKey("port")
        val USERNAME_KEY = stringPreferencesKey("username")
        val PASSWORD_KEY = stringPreferencesKey("password")
        val ANONYMOUS_KEY = booleanPreferencesKey("anonymous")
        val AUTO_START_KEY = booleanPreferencesKey("auto_start")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ftpServer: FtpServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(2121)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _username = MutableStateFlow("aicode")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(generateRandomPassword())
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isAnonymous = MutableStateFlow(false)
    val isAnonymous: StateFlow<Boolean> = _isAnonymous.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val defaultSharedPath: String by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }.absolutePath
    }

    init {
        scope.launch {
            val prefs = context.ftpServerDataStore.data.first()
            _port.value = prefs[PORT_KEY] ?: 2121
            _username.value = prefs[USERNAME_KEY] ?: "aicode"
            val storedPassword = prefs[PASSWORD_KEY]?.let { KeystoreCipher.decryptString(it) }
            // 从未设置或仍是旧版固定弱口令时，改用随机口令并在 UI 展示供用户复制，避免默认 123456 被同网段利用。
            _password.value = if (storedPassword.isNullOrBlank() || storedPassword == LEGACY_DEFAULT_PASSWORD) {
                generateRandomPassword().also { fresh ->
                    context.ftpServerDataStore.edit { it[PASSWORD_KEY] = KeystoreCipher.encryptString(fresh) }
                }
            } else {
                storedPassword
            }
            _isAnonymous.value = prefs[ANONYMOUS_KEY] ?: false
            _autoStart.value = prefs[AUTO_START_KEY] ?: false

            updateServerUrl()

            if (_autoStart.value) {
                startServer()
            }
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "获取本机 IP 失败: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    private fun updateServerUrl() {
        val ip = getLocalIpAddress()
        _serverUrl.value = "ftp://$ip:${_port.value}"
    }

    suspend fun saveConfig(port: Int, username: String, password: String, isAnonymous: Boolean, autoStart: Boolean) = withContext(Dispatchers.IO) {
        val wasRunning = _isRunning.value
        if (wasRunning) {
            stopServerInternal()
        }

        _port.value = port
        _username.value = username
        _password.value = password
        _isAnonymous.value = isAnonymous
        _autoStart.value = autoStart
        updateServerUrl()

        context.ftpServerDataStore.edit { prefs ->
            prefs[PORT_KEY] = port
            prefs[USERNAME_KEY] = username
            prefs[PASSWORD_KEY] = KeystoreCipher.encryptString(password)
            prefs[ANONYMOUS_KEY] = isAnonymous
            prefs[AUTO_START_KEY] = autoStart
        }

        if (wasRunning) {
            startServerInternal()
        }
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            stopServerInternal()
            false
        } else {
            startServerInternal()
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true
        startServerInternal()
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        if (!_isRunning.value) return@withContext
        stopServerInternal()
    }

    private fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()

            val legacyUserFile = File(context.cacheDir, "ftp_users.properties")
            check(!legacyUserFile.exists() || legacyUserFile.delete()) {
                context.getString(R.string.ftp_user_cache_cleanup_failed)
            }

            if (!_isAnonymous.value && _username.value == "anonymous") {
                _errorMessage.value = context.getString(R.string.ftp_reserved_username)
                _isRunning.value = false
                return false
            }
            if (!_isAnonymous.value && (_username.value.isBlank() || _password.value.isBlank())) {
                _errorMessage.value = context.getString(R.string.ftp_password_required)
                _isRunning.value = false
                return false
            }

            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = _port.value
            serverFactory.addListener("default", listenerFactory.createListener())

            val anonymousAllowed = _isAnonymous.value
            val userManager = object : UserManager {
                private val users = ConcurrentHashMap<String, User>()

                override fun getUserByName(name: String): User? = users[name]?.let { BaseUser(it) }

                override fun getAllUserNames(): Array<String> = users.keys.toTypedArray()

                override fun delete(name: String) {
                    users.remove(name)
                }

                override fun save(user: User) {
                    users[user.name] = BaseUser(user)
                }

                override fun doesExist(name: String): Boolean = users.containsKey(name)

                override fun authenticate(authentication: Authentication): User {
                    val user = when (authentication) {
                        is UsernamePasswordAuthentication -> authentication.username?.let { users[it] }?.takeIf {
                            it.name != "anonymous" && !it.password.isNullOrBlank() &&
                                it.password == authentication.password
                        }
                        is AnonymousAuthentication -> if (anonymousAllowed) users["anonymous"] else null
                        else -> null
                    }
                    if (user == null || !user.enabled) throw AuthenticationFailedException()
                    return BaseUser(user)
                }

                override fun getAdminName(): String? = null

                override fun isAdmin(name: String): Boolean = false
            }

            val authorities = ArrayList<Authority>()
            authorities.add(WritePermission())
            authorities.add(ConcurrentLoginPermission(20, 20))
            authorities.add(TransferRatePermission(0, 0))

            // 正常用户
            if (_username.value.isNotBlank() && _username.value != "anonymous" && _password.value.isNotBlank()) {
                val user = BaseUser()
                user.name = _username.value
                user.password = _password.value
                user.homeDirectory = defaultSharedPath
                user.authorities = authorities
                userManager.save(user)
            }

            // 匿名用户
            if (anonymousAllowed) {
                val anonUser = BaseUser()
                anonUser.name = "anonymous"
                anonUser.homeDirectory = defaultSharedPath
                anonUser.authorities = authorities
                userManager.save(anonUser)
            }

            serverFactory.userManager = userManager
            val server = serverFactory.createServer()
            server.start()
            ftpServer = server
            _isRunning.value = true
            updateServerUrl()
            FileLogger.i(TAG, "内置 FTP 服务端已启动: ${_serverUrl.value}, 共享目录: $defaultSharedPath")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动内置 FTP 服务端失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，可能端口被占用"
            _isRunning.value = false
            return false
        }
    }

    private fun stopServerInternal() {
        try {
            ftpServer?.stop()
            ftpServer = null
            _isRunning.value = false
            FileLogger.i(TAG, "内置 FTP 服务端已停止")
        } catch (e: Exception) {
            FileLogger.e(TAG, "停止内置 FTP 服务端出错: ${e.message}", e)
        }
    }
}
