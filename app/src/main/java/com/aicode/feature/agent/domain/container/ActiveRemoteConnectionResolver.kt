package com.aicode.feature.agent.domain.container

import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.normalizeRemoteWorkspacePath
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前激活容器 profile 对应的远程连接配置解析器。
 *
 * 取代旧的 execution_mode_prefs 连接参数副本：激活 profile 变化、profile 内容变化（编辑远程路径或换绑通道）
 * 或连接表变化（改 host/端口/账号密码）都会重新解析，产出可直接交给 [RemoteSshConnection.connect] 的
 * [RemoteConnectionConfig]，因此改连接即时生效，无需切换容器再切回。
 *
 * 仅按连接自身的 authType 解析认证：密码连接解出密码，密钥连接解出私钥路径与口令，
 * 与工作区同步的 SFTP 通道保持一致。
 */
@Singleton
class ActiveRemoteConnectionResolver @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val dao: RemoteConnectionDao,
    private val loginKeyStore: SshLoginKeyStore
) {
    /** 激活 profile 解析出的连接配置；本地 profile、通道缺失或连接被删时为 null。 */
    val activeConfigFlow: Flow<RemoteConnectionConfig?> = combine(
        containerSettingsRepository.activeProfileIdFlow,
        containerSettingsRepository.customProfilesFlow,
        dao.getAllConnections()
    ) { activeId, profiles, connections ->
        resolve(activeId, profiles, connections)
    }.distinctUntilChanged()

    private fun resolve(
        activeId: String,
        profiles: List<ContainerProfile>,
        connections: List<RemoteConnectionEntity>
    ): RemoteConnectionConfig? {
        val profile = profiles.firstOrNull { it.id == activeId }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeId }
            ?: return null
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return null
        val conn = connections.firstOrNull { it.id == ssh.connectionId } ?: return null
        val auth = if (conn.authType.equals("PASSWORD", ignoreCase = true)) {
            RemoteAuth.Password(KeystoreCipher.decryptString(conn.authData))
        } else {
            val passphrase = loginKeyStore.entries().firstOrNull { it.path == conn.authData }?.passphrase
                ?: conn.passphrase?.let { KeystoreCipher.decryptString(it) }
            RemoteAuth.PrivateKey(
                privateKeyPath = conn.authData,
                passphrase = passphrase
            )
        }
        return RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = auth,
            remoteWorkspacePath = normalizeRemoteWorkspacePath(ssh.remoteWorkspacePath, conn.username)
        )
    }
}
