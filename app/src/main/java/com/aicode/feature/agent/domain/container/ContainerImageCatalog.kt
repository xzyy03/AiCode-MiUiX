package com.aicode.feature.agent.domain.container

import android.content.Context
import android.os.Build
import com.aicode.core.net.RepoDataFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可下载的镜像条目：[path] 是相对路径，{abi} 占位符由 [ContainerImageCatalog.urlFor] 按架构替换。 */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    /**
     * 内置版本号。Alpine/Ubuntu 直接是完整补丁版本（如 `3.21.8`）；
     * Debian 这类「版本随上游滚动、内置值只是主版本」的条目，精确补丁号由 [versionPath] 运行时探测覆盖。
     */
    val version: String,
    /** 版本号后缀（如 Debian 的 `(bookworm)`），与解析后的版本拼成展示名。 */
    val versionSuffix: String = "",
    /**
     * 版本元数据文件的相对路径（{abi} 同 [path]），内容为纯文本版本号（如 Debian 的 `rootfs.debian_version` → `12.15`）。
     * 留空表示该条目版本固定、无需探测。
     */
    val versionPath: String = "",
    /** 上游 EOL 日期（`yyyy-MM-dd`），留空表示未知；这是**完全结束**日（含延长支持期）。 */
    val eol: String = "",
    /**
     * **常规支持**结束日（`yyyy-MM-dd`）。过了它但未过 [eol] 说明只剩延长支持期
     * （Ubuntu 的 ESM / Debian 的 LTS）。留空表示该发行版没有两段式支持周期（如 Alpine）。
     */
    val supportEnds: String = "",
    /** 延长支持计划名（如 `ESM` / `LTS`），用于把「已进入延长支持」说清楚；留空则用通用文案。 */
    val supportExtension: String = "",
    val description: String = "",
    val sizeBytes: Long = 0,
    /** 所属发行版（对应全局 sources 表的键，如 alpine/ubuntu）。 */
    val distro: String = "",
    val path: String,
    /** 标准架构键（arm64/x86_64）→ 该发行版实际目录名（如 aarch64/amd64）。 */
    val abiNames: Map<String, String> = emptyMap(),
    /**
     * 标准架构键（arm64/x86_64）→ 官方公布的 SHA-256。下载完成后据此校验，避免镜像被截断或投毒。
     * 留空表示上游不提供校验和（如 Debian 的 Jenkins artifact），此时跳过校验。
     */
    val sha256ByAbi: Map<String, String> = emptyMap()
)

/** 发行版分支的支持状态，用于列表标注。 */
enum class ContainerImageEol {
    /** 正常，无需标注。 */
    NONE,

    /** 某个支持期将在 [EOL_SOON_DAYS] 天内结束（常规支持或完全结束都算）。 */
    SOON,

    /** 已过常规支持、仍在延长支持期（Ubuntu 的 ESM / Debian 的 LTS）。 */
    EXTENDED,

    /** 已过完全结束日，彻底停止支持。 */
    ENDED
}

/**
 * 按条目自带的支持周期判断状态（两级）：
 *
 * - 已过 [ContainerImageEntry.eol]（完全结束）→ [ContainerImageEol.ENDED]；
 * - 任一支持期距今不足 [EOL_SOON_DAYS] 天 → [ContainerImageEol.SOON]（迫近的截止优先提示）；
 * - 已过 [ContainerImageEntry.supportEnds] 但未过 eol → [ContainerImageEol.EXTENDED]；
 * - 其余 → [ContainerImageEol.NONE]。
 *
 * 未配置日期或日期非法一律当作「不知道」——宁可漏标也不误标。[today] 可注入，便于单测。
 */
fun ContainerImageEntry.eolStatus(today: java.time.LocalDate = java.time.LocalDate.now()): ContainerImageEol {
    val eolDate = parseIsoDateOrNull(eol) ?: return ContainerImageEol.NONE
    // 当天到期仍算「即将」而非「已停止」：isBefore 不含当天
    if (eolDate.isBefore(today)) return ContainerImageEol.ENDED
    if (eolDate.toEpochDay() - today.toEpochDay() < EOL_SOON_DAYS) return ContainerImageEol.SOON

    val supportDate = parseIsoDateOrNull(supportEnds) ?: return ContainerImageEol.NONE
    if (supportDate.isBefore(today)) return ContainerImageEol.EXTENDED
    return if (supportDate.toEpochDay() - today.toEpochDay() < EOL_SOON_DAYS) ContainerImageEol.SOON
    else ContainerImageEol.NONE
}

private fun parseIsoDateOrNull(raw: String): java.time.LocalDate? =
    runCatching { java.time.LocalDate.parse(raw) }.getOrNull()

/** EOL 预警窗口：距停止支持不足这些天即标注。 */
const val EOL_SOON_DAYS = 30L

/**
 * 展示用名称：发行版名 + 版本 + 可选后缀。
 * [resolvedVersion] 为运行时探测到的精确版本（如 Debian 的 `12.15`），为空时退回条目内置的 [ContainerImageEntry.version]。
 * 版本里已经带了后缀就不重复追加——旧记录的版本号是 `12 (bookworm)` 这种含后缀的写法。
 */
fun ContainerImageEntry.displayLabel(resolvedVersion: String? = null): String {
    val shown = resolvedVersion?.takeIf { it.isNotBlank() } ?: version
    val suffix = versionSuffix.takeIf { it.isNotBlank() && !shown.contains(it) }.orEmpty()
    return listOf(name, shown, suffix).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 已下载的文件是否落后于目录当前版本。
 * [downloadedVersion] 为空时返回 false——宁可漏提示，也不能把用户刚下好的东西误报成「有新版」。
 */
fun hasNewerVersion(downloadedVersion: String?, currentVersion: String): Boolean =
    !downloadedVersion.isNullOrBlank() &&
        currentVersion.isNotBlank() &&
        downloadedVersion != currentVersion

/**
 * 从下载文件名里取回版本号。
 *
 * 下载器一直用 `download_<条目 id>_<版本>_<时间戳>.<扩展名>` 命名，所以**升级前写入的旧记录**
 * （那时已下载记录还没有「下载时版本」字段）也能靠文件名判断「磁盘上这份是不是该重下了」，
 * 不必做数据迁移。
 *
 * 版本段按「不含下划线的任意字符」匹配，而不是只认数字：旧版 Debian 条目的版本号写作
 * `12 (bookworm)`（含空格与括号），收紧成正则会把这类记录漏掉。
 *
 * 解析不出、或 id 对不上（防误配）返回空串，调用方按「不知道版本」处理而不是误报。
 */
fun versionFromDownloadFileName(entryId: String, fileUriOrPath: String): String {
    if (entryId.isBlank() || fileUriOrPath.isBlank()) return ""
    val fileName = fileUriOrPath.substringAfterLast('/')
    val match = DOWNLOAD_FILE_NAME.matchEntire(fileName) ?: return ""
    val (id, version) = match.destructured
    return if (id == entryId) version else ""
}

/** 与 ContainerImageDownloader 的文件命名保持一致：download_<id>_<version>_<时间戳>.<tar.gz|tgz|tar.xz> */
private val DOWNLOAD_FILE_NAME =
    Regex("""^download_(.+)_([^_]+)_\d+\.(?:tar\.gz|tgz|tar\.xz)$""")

/** 一个下载源：中英文显示名 + 各发行版的前缀（缺失/为空表示该源不提供此发行版）。 */
@Serializable
data class ContainerImageSource(
    val name: Map<String, String> = emptyMap(),
    val distros: Map<String, String> = emptyMap()
)

/** 全局镜像目录：sources（源 id → 定义）+ 镜像列表。 */
@Serializable
data class ContainerImageCatalogData(
    val sources: Map<String, ContainerImageSource> = emptyMap(),
    val images: List<ContainerImageEntry> = emptyList()
)

/** 目录 JSON 统一用宽松解析：新增字段（如后续加 generatedAt）不该让老版本 App 整份目录报废。 */
private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * 解析目录 JSON，供 [ContainerImageCatalog] 的三级读取逐级尝试。
 *
 * 内容为空、JSON 非法、或解析出来一个条目都没有，一律返回 null——调用方据此继续往下一级回退。
 * 这是「断网首启也不能出现空列表」的兜底点：任何一级坏数据都必须能被识别出来而不是当成有效目录。
 */
internal fun parseContainerImageCatalog(raw: String?): ContainerImageCatalogData? {
    if (raw.isNullOrBlank()) return null
    val parsed = runCatching { catalogJson.decodeFromString<ContainerImageCatalogData>(raw) }.getOrNull()
    return parsed?.takeIf { it.images.isNotEmpty() }
}

/**
 * 可下载镜像目录，三级来源：**内存 → 仓库磁盘缓存 → 内置 assets**（与 ProviderPresetLibrary 同构）。
 *
 * 内置 assets 是发版快照，版本号只会越来越旧；真实版本清单由 CI 每日同步到仓库 data 分支的
 * [REMOTE_PATH]，[refreshFromNetworkIfStale] 在启动阶段后台拉取并落盘。UI 读取走 [load]，
 * 纯本地 IO、绝不发网络请求，因此断网首启也能拿到内置基线，不会出现空列表。
 *
 * 源与镜像分离存储：URL = sources[源][发行版] 前缀 + 条目 path（{abi} 按设备架构替换）。
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _catalog = MutableStateFlow(ContainerImageCatalogData())

    /** 目录快照；启动预热拉到新数据后会重新发射，UI 据此自动刷新。 */
    val catalog: StateFlow<ContainerImageCatalogData> = _catalog.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var refreshAttempted = false

    /**
     * 同步读取目录（内存 → 磁盘缓存 → 内置 assets），毫秒级返回、不发网络请求。
     * 磁盘缓存解析失败或为空时同样回退 assets——宁可给出稍旧的基线，也不能给空列表。
     *
     * 取磁盘缓存优先于 assets：缓存来自仓库 data 分支（CI 每日刷新），通常比随 APK 固化的 assets 新。
     * 唯一例外是「刚升级完 App、而缓存还是旧版拉的」这一小段窗口，此时会短暂显示比 assets 略旧的版本；
     * [RepoDataFetcher] 的 12h TTL 一过即被网络结果覆盖，属可自愈的取舍，不值得为此引入版本时间戳比对。
     */
    fun load(): List<ContainerImageEntry> {
        if (!loaded) {
            val fromDisk = runCatching { RepoDataFetcher(context, branch = REMOTE_BRANCH).readLocalCache(REMOTE_PATH) }
                .getOrNull()
            _catalog.value = parseContainerImageCatalog(fromDisk)
                ?: parseContainerImageCatalog(readAsset())
                ?: ContainerImageCatalogData()
            loaded = true
        }
        return _catalog.value.images
    }

    /**
     * 仅供 App 启动阶段（AIEditorApp）在后台协程调用：拉取仓库最新目录并落盘，失败完全静默。
     * 成功后 [catalog] 会发射新值，已订阅的界面自动拿到新版本号。
     */
    suspend fun refreshFromNetworkIfStale() = withContext(Dispatchers.IO) {
        if (refreshAttempted) return@withContext
        refreshAttempted = true

        val result = runCatching {
            RepoDataFetcher(context, branch = REMOTE_BRANCH).fetch(REMOTE_PATH)
        }.getOrNull()
        val content = when (result) {
            is RepoDataFetcher.FetchResult.Success -> result.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
            else -> null
        }
        val parsed = parseContainerImageCatalog(content)
        if (parsed != null) {
            _catalog.value = parsed
            loaded = true
        }
    }

    private fun readAsset(): String? = runCatching {
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 全局源 id 列表（保持 JSON 顺序），供右上角源切换展示。 */
    val sourceIds: List<String>
        get() = _catalog.value.sources.keys.toList()

    /** 源显示名（按语言键 zh/en 取，缺省回退任意可用名）；源不存在返回 null。 */
    fun sourceName(sourceId: String, lang: String): String? {
        val names = _catalog.value.sources[sourceId]?.name ?: return null
        return names[lang] ?: names.values.firstOrNull()
    }

    /** 当前设备架构下，[entry] 指定源的完整下载 URL；源/发行版/架构缺失返回 null。 */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? =
        buildUrl(sourceId, entry.distro, entry.path, entry.abiNames, abi)

    /**
     * 当前设备架构下，[entry] 版本元数据文件的完整 URL（纯文本版本号）。
     * 条目未配置 [ContainerImageEntry.versionPath] 或源/发行版/架构缺失时返回 null——调用方据此跳过探测。
     */
    fun versionUrlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? =
        buildUrl(sourceId, entry.distro, entry.versionPath, entry.abiNames, abi)

    /**
     * 当前设备架构下该条目官方给出的 SHA-256；目录未提供（如 Debian 的 Jenkins artifact）返回 null，
     * 调用方据此跳过校验而不是当成失败。
     */
    fun sha256For(entry: ContainerImageEntry, abi: String): String? =
        entry.sha256ByAbi[abi]?.takeIf { it.isNotBlank() }

    /** 由「源前缀 + 发行版相对路径 + 架构名」拼 URL，任一环节缺失返回 null。 */
    private fun buildUrl(
        sourceId: String,
        distro: String,
        path: String,
        abiNames: Map<String, String>,
        abi: String
    ): String? {
        if (path.isBlank()) return null
        val prefix = _catalog.value.sources[sourceId]?.distros?.get(distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = abiNames[abi] ?: return null
        return "$prefix/$path".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"

        /** CI 每日同步的远程目录：仓库 data 分支上的同一份 JSON（与 models.json 同分支）。 */
        const val REMOTE_BRANCH = "data"
        const val REMOTE_PATH = "data/container-images.json"

        /** 与 [com.aicode.feature.agent.domain.container.ContainerInstaller] 一致的架构判定：x86 设备走 x86_64，其余走 arm64。 */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
