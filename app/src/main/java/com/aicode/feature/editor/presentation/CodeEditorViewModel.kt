package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器页状态。 */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** 保存结果，一次性事件，经 [CodeEditorViewModel.saveEvents] 下发。 */
sealed interface SaveResult {
    data object Success : SaveResult

    /**
     * 磁盘上的内容在编辑器打开之后被改过（多半是 AI 会话写的），本次保存**未写入**。
     *
     * 携带磁盘当前内容供 UI 展示差异：不直接覆盖，也不直接丢弃用户的编辑，由用户选。
     *
     * @param diskContent 磁盘当前内容（可能是 AI 刚写进去的）
     */
    data class Conflict(val diskContent: String) : SaveResult

    data class Error(val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository,
    private val versions: FileVersionRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** 是否正在写盘（远程模式下经 SFTP，耗时较长，供 UI 转圈）。 */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    private var loadedPath: String? = null

    /** 加载时磁盘内容的指纹，用于保存前判断「文件是否被 AI/其他会话改过」。 */
    private var loadedFingerprint: String? = null

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        // 换文件时先清空基线：否则旧文件的指纹会被拿来跟新文件比，误报冲突。
        loadedFingerprint = null
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching EditorUiState.Error(null)
                }
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching EditorUiState.TooLarge(size)
                }
                val bytes = fileAccess.readBytes(path)
                if (looksBinary(bytes)) {
                    return@runCatching EditorUiState.Binary
                }
                // 语法包解析放在这里，确保 AndroidView factory 在主线程创建编辑器时 registry 已就绪。
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    // 预热 grammar：某种语法首次构造要编译大量正则，不在这里做就会压到主线程并拖后首次上色。
                    // 不将对象传给 UI：Language 绑编辑器生命周期，editor.release() 会连带销毁它，
                    // 跨重建复用已销毁实例会出问题——此处仅为把编译结果缓进 registry。
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                EditorUiState.Success(
                    content = bytes.toString(Charsets.UTF_8),
                    scopeName = scope
                ).also {
                    // 记下磁盘指纹：保存前据此判断「编辑器打开后文件是否被 AI/其他会话改过」。
                    loadedFingerprint = runCatching { fileAccess.fingerprint(path) }.getOrNull()
                    // 同时登记给并发保护：用户保存后，所有 AI 会话手上的版本都算过期。
                    loadedFingerprint?.let { fp ->
                        versions.recordExternal(fileAccess.toDisplayPath(path), fp)
                    }
                }
            }.getOrElse { e ->
                FileLogger.w(TAG, "打开文件失败: $path", e)
                EditorUiState.Error(e.message)
            }
        }
    }

    /**
     * 把编辑器当前内容写回文件。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。
     *
     * 保存前先比对磁盘指纹：若文件在编辑器打开之后被 AI 会话或别的进程改过，**不写入**，
     * 改为下发 [SaveResult.Conflict]，由用户决定覆盖还是放弃自己的编辑。否则用户的保存会
     * 静默抹掉 AI 刚写的改动（反向亦然）。
     */
    fun save(content: String) {
        val path = loadedPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val current = runCatching { fileAccess.fingerprint(path) }.getOrNull()
                if (loadedFingerprint != null && current != null && current != loadedFingerprint) {
                    FileLogger.w(TAG, "保存冲突（磁盘内容已变）: $path")
                    val disk = runCatching { fileAccess.readFile(path) }.getOrNull()
                    _saveEvents.send(SaveResult.Conflict(disk.orEmpty()))
                    return@launch
                }
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 无视冲突直接覆盖磁盘（用户在冲突提示里选了「覆盖」）。
     *
     * 这是用户显式确认的覆盖，因此不再比对指纹——但仍走 [fileAccess] 的原子写。
     */
    fun saveForce(content: String) {
        val path = loadedPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 放弃编辑器里的修改、重新从磁盘加载（用户在冲突提示里选了「放弃我的修改」）。
     *
     * 复用 [load] 会因 `loadedPath` 未变而直接返回，所以这里先清掉再走加载，
     * 保证拿到的是磁盘最新内容。
     */
    fun reloadFromDisk() {
        val path = loadedPath ?: return
        loadedPath = null
        load(path)
    }

    /** 真正写盘并维护版本基线；成功/失败统一转成 [SaveResult]。 */
    private fun writeThrough(path: String, content: String): SaveResult =
        runCatching { fileAccess.writeFile(path, content) }
            .fold(
                onSuccess = {
                    // 更新基线，否则紧接着的第二次保存会被自己上一次的写入判成冲突。
                    loadedFingerprint = runCatching { fileAccess.fingerprint(path) }.getOrNull()
                    loadedFingerprint?.let { fp ->
                        versions.recordExternal(fileAccess.toDisplayPath(path), fp)
                    }
                    SaveResult.Success
                },
                onFailure = { e ->
                    FileLogger.w(TAG, "保存文件失败: $path", e)
                    SaveResult.Error(e.message)
                }
            )

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** 全量读入内存，超过该体积拒绝打开以避免 OOM 与长时间卡顿。 */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

        /** 二进制嗅探的采样字节数：与 git 一致，仅看开头一段。 */
        const val BINARY_SNIFF_BYTES = 8000

        /** 二进制判定：开头采样段内出现 NUL 字节即视为二进制（文本文件不含 NUL，误判率极低）。 */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
