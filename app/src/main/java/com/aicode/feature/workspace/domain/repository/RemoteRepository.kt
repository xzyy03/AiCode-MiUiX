package com.aicode.feature.workspace.domain.repository

import androidx.room.withTransaction
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.WatchFilter
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.SshHostKeyStore
import com.aicode.feature.agent.domain.container.SshHostKeyVerifier
import com.aicode.feature.agent.domain.container.SshLoginKeyStore
import com.aicode.feature.agent.domain.container.SshPrivateKeyStore
import com.aicode.feature.agent.domain.container.friendlySshError
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteMount
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import com.aicode.feature.workspace.domain.model.SyncConnectionState
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.workspace.domain.remote.RemoteSyncClient
import com.aicode.feature.workspace.domain.remote.SyncEngine
import com.aicode.feature.workspace.domain.remote.SyncIndexStore
import com.aicode.feature.workspace.domain.remote.ftp.FtpSyncClient
import com.aicode.feature.workspace.domain.remote.sftp.SftpSyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteRepository @Inject constructor(
    private val dao: RemoteConnectionDao,
    private val syncSettings: com.aicode.feature.settings.data.repository.SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val loginKeyStore: SshLoginKeyStore,
    private val fileChangeHub: FileChangeHub,
    private val containerInstaller: ContainerInstaller,
    private val agentDatabase: AgentDatabase
) {
    /** 每个挂载的同步指纹索引，用于跳过未变文件；落在 app 私有的 `aicode/sync-index/` 下。 */
    private val syncIndexStore = SyncIndexStore(File(containerInstaller.aicodeDir, "sync-index"))
    private val activeEngines = ConcurrentHashMap<String, SyncEngine>()
    private val connectionLock = Any()
    private val connectionAttempts = mutableMapOf<String, Any>()
    private val activeEngineIds = MutableStateFlow<Set<String>>(emptySet())

    /** 各挂载的实时连接健康度，由 SyncEngine.connectionState 回写。 */
    private val mountStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())
    private val stateJobs = ConcurrentHashMap<String, Job>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 每个挂载的自动连接重试协程，切换工作区/手动断开时取消。 */
    private val autoConnectJobs = ConcurrentHashMap<String, Job>()

    /** 每个挂载的本地文件变更订阅协程，断开挂载时取消。 */
    private val watchJobs = ConcurrentHashMap<String, Job>()

    init {
        // 跟随当前工作区：App 启动（工作区就绪）与切换工作区时，自动断开非当前工作区的挂载、
        // 连接当前工作区里勾了「应用启动时自动连接」的挂载（失败退避重试直到成功）。
        scope.launch {
            workspaceRepository.current.collect { workspace ->
                syncMountsToWorkspace(workspace?.path)
            }
        }
    }

    /** 把激活挂载集合对齐到当前工作区。 */
    private suspend fun syncMountsToWorkspace(workspacePath: String?) {
        val mounts = dao.getAllMountsOnce()
        // 1. 断开不属于当前工作区的激活挂载（含手动连接的，保证切换后只同步当前工作区）
        val trackedIds = synchronized(connectionLock) {
            activeEngineIds.value + autoConnectJobs.keys + connectionAttempts.keys
        }
        trackedIds
            .filter { activeId ->
                workspacePath == null || mounts.firstOrNull { it.id == activeId }?.let { !belongsTo(it, workspacePath) } ?: true
            }
            .forEach { disconnectMount(it) }
        // 2. 自动连接当前工作区里勾了 autoConnect 的挂载
        if (workspacePath == null || workspaceRepository.current.value?.path != workspacePath) return
        mounts.filter { it.autoConnect && belongsTo(it, workspacePath) && it.id !in activeEngineIds.value }
            .forEach { connectMountWithRetry(it.id, workspacePath) }
    }

    private fun belongsTo(mount: RemoteMountEntity, workspacePath: String): Boolean =
        mount.localMountPath == workspacePath || mount.localMountPath.startsWith("$workspacePath/")

    /** 带退避重试的自动连接：直到成功，或挂载不再属于当前工作区/被手动断开。 */
    private fun connectMountWithRetry(mountId: String, workspacePath: String) {
        autoConnectJobs[mountId]?.cancel()
        autoConnectJobs[mountId] = scope.launch {
            var backoffMs = 30_000L
            while (isActive) {
                val mount = dao.getMountById(mountId) ?: break
                if (workspaceRepository.current.value?.path != workspacePath || !belongsTo(mount, workspacePath)) break
                if (mountId in activeEngineIds.value) break
                val result = connectMount(mountId)
                if (result.isSuccess) break
                FileLogger.w(TAG, "自动连接挂载 ${mount.localMountPath} 失败: ${result.exceptionOrNull()?.message}，${backoffMs / 1000}s 后重试")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    private companion object {
        const val TAG = "RemoteRepository"
    }

    fun getConnections(): Flow<List<RemoteConnection>> = dao.getAllConnections().map { list ->
        list.map { it.toDomainModel() }
    }.flowOn(Dispatchers.IO)
    
    fun getMounts(): Flow<List<RemoteMount>> = combine(
        dao.getAllMounts(),
        activeEngineIds,
        mountStates
    ) { list, activeIds, states ->
        list.map { mountEntity ->
            val connEntity = dao.getConnectionById(mountEntity.connectionId)
            mountEntity.toDomainModel(connEntity?.toDomainModel()).copy(
                isActive = activeIds.contains(mountEntity.id),
                connectionState = states[mountEntity.id]
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun addConnection(conn: RemoteConnection, auth: RemoteAuth) = withContext(Dispatchers.IO) {
        val authType = if (auth is RemoteAuth.Password) "PASSWORD" else "PRIVATE_KEY"
        val authData = if (auth is RemoteAuth.Password) auth.password else (auth as RemoteAuth.PrivateKey).privateKeyPath
        val passphrase = if (auth is RemoteAuth.PrivateKey) auth.passphrase else null
        // 编辑（updateConnection 复用本方法）时保留原创建时间与排序位置，避免刷新列表顺序
        val existing = dao.getConnectionById(conn.id)
        val entity = RemoteConnectionEntity(
            id = conn.id,
            name = conn.name,
            protocol = conn.protocol,
            host = conn.host,
            port = conn.port,
            username = conn.username,
            authType = authType,
            authData = if (authType == "PASSWORD") KeystoreCipher.encryptString(authData) else authData,
            // 口令以密钥条目为准。这里只在密钥口令有值时镜像到连接；密钥未设置口令时保留连接上的
            // 存量口令（升级前用户在连接里填的），避免编辑连接把它抹掉。
            passphrase = passphrase?.let { KeystoreCipher.encryptString(it) }
                ?: existing?.takeIf { authType == "PRIVATE_KEY" }?.passphrase,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            // 排序值以数据库当前值为准（重排异步落库，UI 传入的快照可能陈旧）；新建取最大排序 +1。
            sortOrder = existing?.sortOrder ?: (dao.getMaxSortOrder() + 1)
        )
        dao.insertConnection(entity)
    }

    /**
     * 按传入顺序重排连接配置。只写 id + sort_order 两列，
     * 避免整行 REPLACE 覆盖并发修改的其它字段（如 authData/passphrase）。
     */
    suspend fun reorderConnections(connections: List<RemoteConnection>) {
        FileLogger.d(TAG, "重排连接配置 共 ${connections.size} 个")
        agentDatabase.withTransaction {
            connections.forEachIndexed { index, conn -> dao.updateSortOrder(conn.id, index) }
        }
    }

    suspend fun updateConnection(conn: RemoteConnection, auth: RemoteAuth) {
        // Will overwrite existing connection ID
        addConnection(conn, auth)
    }

    suspend fun deleteConnection(id: String) {
        val entity = dao.getConnectionById(id)
        if (entity != null) {
            // 关联挂载在 DB 里会级联删除，但需先断开内存中的同步引擎；只断该连接下的挂载，
            // 不能遍历所有 activeEngines（那会连带断开其它连接，且在遍历时修改 map）。
            dao.getMountsByConnectionId(id).first().forEach { disconnectMount(it.id) }
            dao.deleteConnection(entity)
        }
    }

    suspend fun addMount(mount: RemoteMount) {
        dao.insertMount(RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect,
            createdAt = System.currentTimeMillis()
        ))
    }
    
    suspend fun updateMount(mount: RemoteMount) {
        dao.updateMount(RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect
        ))
        // 本地挂载路径可能变化，旧索引的 key 不再对应，清掉让下次上传重新建立
        syncIndexStore.clear(mount.id)
    }
    
    suspend fun deleteMount(mountId: String) {
        disconnectMount(mountId)
        val entity = dao.getMountById(mountId)
        if (entity != null) {
            dao.deleteMount(entity)
        }
    }

    suspend fun connectMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val attempt = Any()
        val workspacePath = workspaceRepository.current.value?.path
        var client: RemoteSyncClient? = null
        var published = false
        val previous = synchronized(connectionLock) {
            connectionAttempts[mountId] = attempt
            watchJobs.remove(mountId)?.cancel()
            activeEngineIds.update { it - mountId }
            stopStateWatch(mountId)
            activeEngines.remove(mountId)
        }
        try {
            previous?.shutdown()

            val mountEntity = dao.getMountById(mountId) ?: return@withContext Result.failure(Exception("Mount not found"))
            val connEntity = dao.getConnectionById(mountEntity.connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))
            if (workspacePath == null || !belongsTo(mountEntity, workspacePath)) {
                throw CancellationException("Mount is outside the current workspace")
            }

            val conn = connEntity.toDomainModel()
            val mount = mountEntity.toDomainModel(conn)

            val connectingClient = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }

            client = connectingClient
            val auth = resolveAuth(connEntity)

            synchronized(connectionLock) {
                if (connectionAttempts[mountId] !== attempt || workspaceRepository.current.value?.path != workspacePath) {
                    throw CancellationException("Mount connection is no longer current")
                }
            }
            connectingClient.connect(conn.host, conn.port, conn.username, auth)
            currentCoroutineContext().ensureActive()
            
            synchronized(connectionLock) {
                if (connectionAttempts[mountId] !== attempt || workspaceRepository.current.value?.path != workspacePath) {
                    throw CancellationException("Mount connection is no longer current")
                }
                val engine = SyncEngine(
                    mount = mount,
                    connection = conn,
                    syncClient = connectingClient,
                    auth = auth,
                    ignoredPatternsStr = syncSettings.ignoredPatterns.value,
                    useGitIgnore = syncSettings.useGitIgnore.value,
                    maxSyncBatchSize = syncSettings.maxSyncBatchSize.value,
                    indexStore = syncIndexStore
                )

                activeEngines[mountId] = engine
                activeEngineIds.update { it + mountId }
                startLocalWatching(mountId, mount, engine)
                startStateWatch(mountId, engine)
                published = true
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 挂载连接不弹确认：提示用户先去连接配置页测试连通性完成确认
            val pending = hostKeyVerifier.consumePending()
            if (pending != null) {
                Result.failure(Exception("主机密钥未确认，请先在「连接配置」页测试连通性完成确认"))
            } else {
                Result.failure(Exception(friendlySshError(e), e))
            }
        } finally {
            synchronized(connectionLock) {
                if (connectionAttempts[mountId] === attempt) connectionAttempts.remove(mountId)
            }
            if (!published) {
                withContext(NonCancellable) {
                    runCatching { client?.disconnect() }
                        .onFailure { FileLogger.w(TAG, "关闭未启用的挂载连接失败", it) }
                }
            }
        }
    }

    /**
     * 本地文件变更由中心服务统一监听（含剪枝与合并批量），这里只把事件投进该挂载的同步队列；
     * 订阅随挂载断开而取消，无人订阅时中心服务不持有任何句柄。
     */
    private fun startLocalWatching(mountId: String, mount: RemoteMount, engine: SyncEngine) {
        watchJobs.remove(mountId)?.cancel()
        watchJobs[mountId] = scope.launch {
            fileChangeHub.watchHostDir(
                hostDir = File(mount.localMountPath),
                recursive = true,
                filter = WatchFilter(
                    ignoredNames = syncSettings.ignoredPatterns.value
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    followGitignore = syncSettings.useGitIgnore.value
                ),
                fallbackPoll = false
            ).collect { batch ->
                FileLogger.d(TAG, "同步监听收到变更: mount=$mountId count=${batch.changes.size} truncated=${batch.truncated}")
                for (change in batch.changes) engine.enqueueLocalChange(change.hostPath)
            }
        }
    }

    suspend fun disconnectMount(mountId: String) {
        val engine = synchronized(connectionLock) {
            connectionAttempts.remove(mountId)
            autoConnectJobs.remove(mountId)?.cancel()
            watchJobs.remove(mountId)?.cancel()
            stopStateWatch(mountId)
            activeEngineIds.update { it - mountId }
            activeEngines.remove(mountId)
        }
        engine?.shutdown()
    }

    /** 订阅某挂载的实时连接健康度并回写到 [mountStates]。 */
    private fun startStateWatch(mountId: String, engine: SyncEngine) {
        mountStates.update { it + (mountId to engine.connectionState.value) }
        stateJobs[mountId]?.cancel()
        stateJobs[mountId] = scope.launch {
            engine.connectionState.collect { state ->
                mountStates.update { it + (mountId to state) }
            }
        }
    }

    private fun stopStateWatch(mountId: String) {
        stateJobs.remove(mountId)?.cancel()
        mountStates.update { it - mountId }
    }

    suspend fun forceUploadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("请先连接该挂载点"))
            engine.uploadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 强制全量上传：忽略本地索引，把全部文件重传一遍（远端被外部清空等恢复场景）。 */
    suspend fun forceUploadMountFull(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("请先连接该挂载点"))
            engine.uploadWorkspace(force = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 清除某挂载的同步指纹索引；下次上传将视为全部未同步（全量）。 */
    suspend fun clearSyncIndex(mountId: String) = withContext(Dispatchers.IO) {
        syncIndexStore.clear(mountId)
    }

    suspend fun forceDownloadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("请先连接该挂载点"))
            engine.downloadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 保存用户确认过的主机指纹，之后同主机连接直接放行。 */
    fun confirmHostKey(host: String, port: Int, fingerprint: String) {
        hostKeyStore.save(host, port, fingerprint)
    }

    /** 测试连通性：待确认（首次/指纹变化）时抛 [HostKeyConfirmationRequiredException]，供 UI 弹出确认。 */
    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = when (protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }
            client.connect(host, port, username, auth)
            client.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            val pending = hostKeyVerifier.consumePending()
            if (pending != null) {
                throw HostKeyConfirmationRequiredException(
                    pending.host, pending.port, pending.keyType, pending.fingerprint, pending.changed
                )
            }
            Result.failure(Exception(friendlySshError(e), e))
        }
    }

    suspend fun listRemoteDirectories(connectionId: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connEntity = dao.getConnectionById(connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))
            val conn = connEntity.toDomainModel()
            
            val client = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }
            val auth = resolveAuth(connEntity)
            
            client.connect(conn.host, conn.port, conn.username, auth)
            val files = client.listFiles(path).filter { it.isDirectory }.map { it.name }
            client.disconnect()
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 解析连接的认证方式：密钥口令优先取密钥条目上的设置，回退到连接自身的存量口令。 */
    private fun resolveAuth(connEntity: RemoteConnectionEntity): RemoteAuth =
        if (connEntity.authType == "PASSWORD") {
            RemoteAuth.Password(KeystoreCipher.decryptString(connEntity.authData))
        } else {
            val passphrase = loginKeyStore.entries().firstOrNull { it.path == connEntity.authData }?.passphrase
                ?: connEntity.passphrase?.let { KeystoreCipher.decryptString(it) }
            RemoteAuth.PrivateKey(connEntity.authData, passphrase)
        }

    private fun RemoteConnectionEntity.toDomainModel() = RemoteConnection(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = if (authType == "PASSWORD") KeystoreCipher.decryptString(authData) else "",
        authType = if (authType == "PRIVATE_KEY") "key" else "password",
        authData = authData,
        sortOrder = sortOrder
    )

    private fun RemoteMountEntity.toDomainModel(conn: RemoteConnection?) = RemoteMount(
        id = id,
        connectionId = connectionId,
        remotePath = remotePath,
        localMountPath = localMountPath,
        isActive = isActive,
        autoConnect = autoConnect,
        connection = conn
    )
}

/** 主机密钥待确认（首次连接或指纹变化），由测试连通性抛出供 UI 确认。 */
class HostKeyConfirmationRequiredException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
) : Exception("SSH 主机密钥需要确认: $host:$port")
