package com.aimusic.player.mine.scan

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.SourceKind
import com.aimusic.player.data.scan.AddSourceResult
import com.aimusic.player.data.scan.CommitResult
import com.aimusic.player.data.scan.ScanOrchestrator
import com.aimusic.player.data.scan.ScanOutcome
import com.aimusic.player.data.scan.ScanPhase
import com.aimusic.player.data.scan.ScanSourceRepository
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.StorageAccessLevel
import com.aimusic.player.storage.StorageSource
import com.aimusic.player.storage.di.PrimaryStorageRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 差异摘要（UI 只需要三个数）。 */
@Immutable
data class ScanDiffUi(val newCount: Int, val skippedCount: Int, val cleanedCount: Int)

/**
 * 扫描收工后的空状态（`09 §5.1`）。
 *
 * **必须是显式字段，不能从 `phase` + `discovered` 反推**：提交成功后相位同样会落到 `DONE`，
 * 而 `discovered` 仍是正数 —— 反推就会在刚刚导入完的界面上写出「未发现新文件」。
 */
enum class ScanEmptyNotice { NO_MUSIC_FOUND, NO_NEW_FILES }

/**
 * 扫描页状态。
 *
 * **相位直接复用数据层的 `ScanPhase`**：`09 §3.3.3` 的示例另立了一个同名 UI 枚举，
 * 但两者取值逐一对应，映射函数只会成为一处没有信息量的翻译；数据的枚举本来就是契约
 * （`04 §4.1` 的状态机），UI 读它不构成越界。
 */
@Immutable
data class ScanUiState(
    val accessLevel: StorageAccessLevel = StorageAccessLevel.NONE,
    val sources: List<ScanSourceEntity> = emptyList(),
    val phase: ScanPhase = ScanPhase.IDLE,
    val discoveredCount: Int = 0,
    val metadataProcessed: Int = 0,
    val diff: ScanDiffUi? = null,
    val error: AppError? = null,
    val emptyNotice: ScanEmptyNotice? = null,
    val scanMinDurationMs: Long = 0L,
    val scanMinSizeBytes: Long = 0L,
) {
    val permissionGranted: Boolean get() = accessLevel != StorageAccessLevel.NONE

    /** 降级通道：只能扫媒体库，任意目录 / 物理删除不可用（`10 §4.3`）。 */
    val degraded: Boolean get() = accessLevel == StorageAccessLevel.MEDIA_LIBRARY_ONLY

    val scanning: Boolean get() = phase == ScanPhase.SCANNING || phase == ScanPhase.DIFFING

    /** 半路上：扫描中、提交中、分析中都不该再发起一次扫描。 */
    val busy: Boolean get() = scanning || phase == ScanPhase.COMMITTING || phase == ScanPhase.ANALYZING

    /**
     * 能不能发起（或重新发起）一次扫描。
     *
     * 原先写成 `phase == IDLE`，于是**扫描结束后主按钮就是灰的** —— 而 `09 §5.1` 要求空状态
     * 给的出口正是「重新扫描」。所以判据改成「不在半路，且没有一份待决定的差异摆在面前」。
     */
    val canStart: Boolean
        get() = permissionGranted && !busy && (diff == null || diff.newCount == 0)

    /** 无事可做：空状态块与底部主按钮都要据此改口径（`09 §5.1`）。 */
    val nothingNew: Boolean get() = emptyNotice != null || diff?.newCount == 0

    val durationRuleOn: Boolean get() = scanMinDurationMs > 0L

    val sizeRuleOn: Boolean get() = scanMinSizeBytes > 0L
}

/** 一次性事件（`02 §7`：导航与提示一律走 Channel，不用 StateFlow）。 */
sealed interface ScanEvent {
    data object RequestAllFilesPermission : ScanEvent

    data object RequestMediaPermission : ScanEvent

    data class ShowMessage(val text: String) : ScanEvent

    /** 提交成功 → 跳最近分析记录（Phase 3 的空窗在这里收口）。 */
    data object NavigateToAnalysis : ScanEvent
}

/**
 * 扫描页的 ViewModel（`09 §3.3.3`）。
 *
 * **相对文档示例多出的三个依赖**，各有理由：
 * - `@ApplicationContext`：`ScanOrchestrator.refreshAccessLevel(context)` 的签名要它，
 *   而权限状态必须在**第一次扫描前**就知道（否则「去授权」按钮永远不出现）
 * - `SettingsRepository`：扫描设置里那两条过滤规则要读写（`03 §6`）
 * - `StorageSource` + 主存储根：一键扫描要判断预设目录**是否真的存在**，
 *   不能把一批不存在的路径当来源写进库
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: ScanOrchestrator,
    private val sources: ScanSourceRepository,
    private val settings: SettingsRepository,
    private val storage: StorageSource,
    @PrimaryStorageRoot private val primaryRoot: String,
) : ViewModel() {

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    /** 空状态由 ViewModel 直接决定（`09 §5.1`），见 [ScanEmptyNotice] 的注释。 */
    private val _emptyNotice = MutableStateFlow<ScanEmptyNotice?>(null)

    val state: StateFlow<ScanUiState> = combine(
        orchestrator.state,
        sources.observeSources(SourceKind.MUSIC),
        settings.scanFilter,
        _emptyNotice,
    ) { session, sourceList, filter, emptyNotice ->
        ScanUiState(
            accessLevel = session.accessLevel,
            sources = sourceList,
            phase = session.phase,
            discoveredCount = session.discovered,
            metadataProcessed = session.metadataProcessed,
            diff = session.diff?.let {
                ScanDiffUi(
                    newCount = it.newFiles.size,
                    skippedCount = it.skippedPaths.size,
                    cleanedCount = it.cleanedPaths.size,
                )
            },
            error = session.error,
            emptyNotice = emptyNotice,
            scanMinDurationMs = filter.minDurationMs,
            scanMinSizeBytes = filter.minSizeBytes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
        initialValue = ScanUiState(),
    )

    init {
        refreshPermission()
    }

    /** 从系统设置返回后刷新门禁（`10 §4.2.1`：启动 / 回前台 / 扫描前各查一次）。 */
    fun refreshPermission() {
        orchestrator.refreshAccessLevel(context)
    }

    /**
     * 主按钮「开始扫描」。
     *
     * 没有来源时**自动走一键扫描**（把常见音乐目录里存在的加成来源），而不是先逼用户去选目录 ——
     * 这是对照 QQ 音乐扫描页定下的主路径（需求 `01 §2.4`）。一个预设目录都没找到时，
     * 才提示用户改用自定义扫描。
     */
    fun onStartScan() {
        viewModelScope.launch {
            _emptyNotice.value = null
            refreshPermission()
            if (!state.value.permissionGranted) {
                _events.send(ScanEvent.RequestAllFilesPermission)
                return@launch
            }
            if (state.value.sources.isEmpty()) {
                if (!addPresetSources()) {
                    _events.send(ScanEvent.ShowMessage(ErrorText.resolve(KEY_ONE_CLICK_EMPTY)))
                    return@launch
                }
            }
            runScan()
        }
    }

    private suspend fun runScan() {
        when (val outcome = orchestrator.scan()) {
            is ScanOutcome.NoChanges ->
                // 分两种：真的一首没扫到，还是扫到了但都是已存在 / 被过滤（后者才该提扫描设置）。
                // 走**持久空状态**而不是一次性提示：09 §5.1 把这两条列为空状态（文案 + 动作），
                // 一闪而过的 snackbar 会让用户看不到「重新扫描」这个出口。
                _emptyNotice.value = if (orchestrator.state.value.discovered == 0) {
                    ScanEmptyNotice.NO_MUSIC_FOUND
                } else {
                    ScanEmptyNotice.NO_NEW_FILES
                }
            is ScanOutcome.Failed -> {
                if (outcome.kind == FailureKind.PERMISSION) {
                    _events.send(ScanEvent.RequestAllFilesPermission)
                } else {
                    _events.send(ScanEvent.ShowMessage(ErrorText.resolve(null)))
                }
            }
            ScanOutcome.AlreadyRunning ->
                _events.send(ScanEvent.ShowMessage(ErrorText.resolve("scan.running", mapOf("new" to 0))))
            // AwaitingUser / Cancelled：界面由 state 驱动，不需要额外提示
            else -> Unit
        }
    }

    /** 「分析并添加」。 */
    fun onConfirmImport() {
        viewModelScope.launch {
            when (val result = orchestrator.commit()) {
                is CommitResult.Committed -> {
                    _emptyNotice.value = null
                    _events.send(
                        ScanEvent.ShowMessage(
                            ErrorText.resolve("scan.committed", mapOf("count" to result.inserted)),
                        ),
                    )
                    // 先提示"已提交"，再跳转 —— 顺序反了用户就看不到提交结果了
                    _events.send(ScanEvent.NavigateToAnalysis)
                }
                is CommitResult.NothingToCommit ->
                    _events.send(ScanEvent.ShowMessage(result.reason))
                CommitResult.Cancelled ->
                    _events.send(ScanEvent.ShowMessage(ErrorText.resolve("scan.commit.cancelled")))
                is CommitResult.Failed ->
                    _events.send(ScanEvent.ShowMessage(ErrorText.resolve(null)))
            }
        }
    }

    /** 「放弃」——不写库（`04 §4.1`）。 */
    fun onDiscard() {
        viewModelScope.launch {
            _emptyNotice.value = null
            orchestrator.discard()
        }
    }

    fun onCancelScan() {
        orchestrator.cancel()
    }

    /** 从目录浏览器选定一个目录（「自定义扫描」）。 */
    fun onAddSource(path: String) {
        viewModelScope.launch {
            when (val result = sources.add(SourceKind.MUSIC, path)) {
                is AddSourceResult.Added -> _events.send(ScanEvent.ShowMessage(path))
                is AddSourceResult.AlreadyExists ->
                    _events.send(ScanEvent.ShowMessage(path))
                is AddSourceResult.Invalid ->
                    _events.send(ScanEvent.ShowMessage(result.reason))
            }
        }
    }

    fun onRemoveSource(id: Long) {
        viewModelScope.launch { sources.remove(id) }
    }

    /**
     * 目录浏览器的列目录。
     *
     * 放在 ViewModel 而不是屏幕里：这是文件系统调用，必须离开主线程（`02 §7`），
     * 而屏幕只该收到结果。
     */
    suspend fun listDirectories(parent: String): List<FileRef> = withContext(Dispatchers.IO) {
        storage.listDirectories(parent)
    }

    /** 浏览器起点：主存储根。 */
    val primaryRootPath: String get() = primaryRoot

    fun onToggleSource(id: Long, enabled: Boolean) {
        viewModelScope.launch { sources.setEnabled(id, enabled) }
    }

    // —— 扫描设置（需求 `01 §2.5`：两条规则各自可关，0 = 不启用） ——

    fun onToggleDurationRule(enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.scanFilter.first()
            settings.setScanFilter(
                minDurationMs = if (enabled) SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS else 0L,
                minSizeBytes = current.minSizeBytes,
            )
        }
    }

    fun onToggleSizeRule(enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.scanFilter.first()
            settings.setScanFilter(
                minDurationMs = current.minDurationMs,
                minSizeBytes = if (enabled) SettingsRepository.DEFAULT_SCAN_MIN_SIZE_BYTES else 0L,
            )
        }
    }

    /**
     * 一键扫描的预设目录（相对主存储根）；只采纳**实际存在**的那些。
     *
     * 返回「现在有来源了没有」，**而不是让调用方回头再读一次 `state.value.sources`**：
     * `state` 是 `stateIn`，来源写库之后要绕一圈才回到这个 flow。补完预设立刻去读，读到的
     * 往往还是空 —— 于是用户点了「开始扫描」，目录加进去了，却弹出「没有找到常见的音乐目录」
     * 并且**不扫描**。用 `add()` 的返回值判断就没有这个时序依赖。
     */
    private suspend fun addPresetSources(): Boolean {
        var hasSource = false
        PRESET_DIRS.forEach { relative ->
            val path = "$primaryRoot/$relative"
            if (storage.exists(path) && storage.isDirectory(path)) {
                // AlreadyExists 也算：来源确实在那儿（可能是 flow 还没推过来）
                when (sources.add(SourceKind.MUSIC, path)) {
                    is AddSourceResult.Added, is AddSourceResult.AlreadyExists -> hasSource = true
                    is AddSourceResult.Invalid -> Unit
                }
            }
        }
        return hasSource
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        const val KEY_ONE_CLICK_EMPTY = "scan.one_click.empty"

        /**
         * 常见音乐目录。顺序只影响写入顺序，不影响结果。
         *
         * 不含主存储根本身：整盘扫描会把 `Android/` 之类的无关目录一起卷进来，且耗时长；
         * 用户真要扫整盘，可以用自定义扫描选中它。
         */
        val PRESET_DIRS = listOf(
            "Music",
            "Download",
            "Downloads",
            "Documents",
            "Recordings",
            "Podcasts",
            "Ringtones",
            "Music/Download",
            "MIUI/sound_recorder",
            "netease/cloudmusic/Music",
            "qqmusic/song",
        )
    }
}
