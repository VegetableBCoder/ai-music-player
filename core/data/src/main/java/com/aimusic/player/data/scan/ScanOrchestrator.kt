package com.aimusic.player.data.scan

import android.content.Context
import androidx.room.withTransaction
import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.common.error.IoError
import com.aimusic.player.common.error.IoOperation
import com.aimusic.player.common.error.PermissionError
import com.aimusic.player.common.error.PermissionScope
import com.aimusic.player.common.error.UnknownError
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.deletion.DeletionService
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.LyricSource
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.data.model.SourceKind
import com.aimusic.player.data.settings.ScanFilter
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.storage.AudioFormats
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.MediaStoreAudioSource
import com.aimusic.player.storage.MetadataReader
import com.aimusic.player.storage.PathNormalizer
import com.aimusic.player.storage.StorageAccessChecker
import com.aimusic.player.storage.StorageAccessLevel
import com.aimusic.player.storage.StorageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * 扫描的唯一入口（`04 §3.5`、`02 §5.6`）。
 *
 * 三条纪律：
 * - **单例 + `Mutex`**：`scan` / `commit` / `cancel` 共用一把锁，同一时刻只可能有一次扫描
 *   （`04 §7`）。重复触发返回 `AlreadyRunning`。
 * - **只写该写的**：本模块只写 `music_file` 与 `analysis_run*`；不写实体、不改
 *   `analysis_status`（除初值 `UNANALYZED`）、不碰磁盘删除（`04 §1.2`）。
 * - **不加 `Result` 包装**：每个操作有自己的 sealed 结果（`ScanOutcome` / `CommitResult`）。
 *
 * `context` 出现在数据层，是因为 `StorageAccessChecker.currentLevel(context)` 的签名如此
 * （`04 §3.3`）；换权限判定实现不必再动这里。
 */
class ScanOrchestrator(
    private val context: Context,
    private val storage: StorageSource,
    private val metadataReader: MetadataReader,
    private val accessChecker: StorageAccessChecker,
    private val mediaStoreAudio: MediaStoreAudioSource,
    private val db: MusicDatabase,
    private val deletionService: DeletionService,
    private val settings: SettingsRepository,
    private val scanSources: ScanSourceRepository,
    private val primaryRoot: String,
    private val analysisTrigger: AnalysisTrigger = AnalysisTrigger {},
    private val lyricHandoff: LyricHandoff = LyricHandoff {},
    private val parallelism: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow(ScanSessionState(accessLevel = StorageAccessLevel.NONE))

    val state: StateFlow<ScanSessionState> = stateFlow.asStateFlow()

    private var runningJob: Job? = null

    /** 从系统设置返回后刷新门禁（`04 §3.5`）。 */
    fun refreshAccessLevel(context: Context) {
        stateFlow.update { it.copy(accessLevel = accessChecker.currentLevel(context)) }
    }

    suspend fun scan(sources: List<ScanSourceEntity>? = null): ScanOutcome {
        if (!mutex.tryLock()) return ScanOutcome.AlreadyRunning

        try {
            val level = accessChecker.currentLevel(context)
            stateFlow.update {
                it.copy(
                    accessLevel = level,
                    diff = null,
                    warnings = emptyList(),
                    error = null,
                    discovered = 0,
                    metadataProcessed = 0,
                )
            }

            if (level == StorageAccessLevel.NONE) {
                stateFlow.update { it.copy(phase = ScanPhase.IDLE, error = PermissionError(PermissionScope.ALL_FILES)) }
                return ScanOutcome.Failed(FailureKind.PERMISSION, null)
            }

            val all = sources ?: scanSources.enabledSources()
            val music = all.filter { it.kind == SourceKind.MUSIC && it.enabled }
            val lyricSources = all.filter { it.kind == SourceKind.LYRICS && it.enabled }
            if (music.isEmpty()) {
                // `04 §5.1`：未选来源前不启动扫描。这里**不**返回 PERMISSION ——
                // 把「没选来源」说成权限问题会误导用户去授权；空状态由 state 驱动，
                // UI 用「来源列表是否为空」区分两种引导（见 §4.1 落地补充）。
                stateFlow.update {
                    it.copy(phase = ScanPhase.IDLE, warnings = listOf("未选择音乐来源"))
                }
                return ScanOutcome.NoChanges(ScanSummary(0, 0, 0, 0))
            }

            val unreachable = music.filterNot { storage.exists(it.path) && storage.isDirectory(it.path) }
            if (unreachable.size == music.size) {
                // `04 §4.3` 规则 2：只有全部根目录都拿不到时才升级为致命
                stateFlow.update {
                    it.copy(
                        phase = ScanPhase.IDLE,
                        error = PermissionError(PermissionScope.ALL_FILES),
                        warnings = it.warnings + unreachable.map { s -> "来源不可读：${s.path}" },
                    )
                }
                return ScanOutcome.Failed(FailureKind.PERMISSION, null)
            }

            runningJob = currentCoroutineContext()[Job]
            stateFlow.update { it.copy(phase = ScanPhase.SCANNING) }

            // 过滤规则读一次、两条通道共用（需求 `../需求文档/01-歌曲库管理.md` §2.5）
            val filter = settings.scanFilter.first()

            val scanned = if (level == StorageAccessLevel.MEDIA_LIBRARY_ONLY) {
                mediaStoreAudio.query { n -> stateFlow.update { s -> s.copy(discovered = n) } }
                    .filter { filter.acceptsSize(it.ref.size) }
                    .filter { filter.acceptsDuration(it.metadata.durationMs) }
                    .map { entry ->
                        ScannedFile(
                            ref = entry.ref,
                            normalizedPath = PathNormalizer.normalize(entry.ref.path, primaryRoot),
                            format = AudioFormats.formatOf(entry.ref.name),
                            metadata = entry.metadata,
                        )
                    }
            } else {
                extractMetadataPipelined(music.map { it.path }, filter)
            }

            stateFlow.update { it.copy(phase = ScanPhase.DIFFING) }
            val diff = diff(scanned, lrcCandidates(level, music, lyricSources), level)

            val summary = ScanSummary(
                newCount = diff.newFiles.size,
                skippedCount = diff.skippedPaths.size,
                cleanedCount = diff.cleanedPaths.size,
                lrcCount = diff.lrcCandidates.size,
            )

            // `discovered` 不在结尾被改写：它是**遍历计数**（§4.6），表达「扫到几个文件」，
            // 含被过滤挡下的那些；「找到几首能用的」由 `diff.newFiles` 表达，两者不是一回事。
            return if (diff.newFiles.isEmpty() && diff.cleanedPaths.isEmpty()) {
                stateFlow.update { it.copy(phase = ScanPhase.IDLE, diff = null) }
                ScanOutcome.NoChanges(summary)
            } else {
                stateFlow.update { it.copy(phase = ScanPhase.AWAITING_USER, diff = diff) }
                ScanOutcome.AwaitingUser(summary)
            }
        } catch (e: CancellationException) {
            stateFlow.update { it.copy(phase = ScanPhase.IDLE, diff = null) }
            return ScanOutcome.Cancelled
        } catch (t: Throwable) {
            stateFlow.update {
                it.copy(phase = ScanPhase.IDLE, error = UnknownError(cause = t))
            }
            return ScanOutcome.Failed(FailureKind.UNKNOWN, t)
        } finally {
            runningJob = null
            mutex.unlock()
        }
    }

    /**
     * 「分析并添加」（`04 §4.8`）。写库序列：建批次 → 分批插 `music_file` → 批量关联批次
     * → 清理已删记录 → 写 `last_scan_at` → 置 `ANALYZING` 并交棒。
     */
    suspend fun commit(): CommitResult {
        val diff = stateFlow.value.diff ?: return CommitResult.NothingToCommit("没有待提交的差异")
        if (!mutex.tryLock()) return CommitResult.NothingToCommit("扫描进行中")

        var runId: Long? = null

        try {
            stateFlow.update { it.copy(phase = ScanPhase.COMMITTING) }
            runningJob = currentCoroutineContext()[Job]

            val runDao = db.analysisRunDao()
            val fileDao = db.musicFileDao()

            val created = db.withTransaction {
                runDao.createRun(
                    AnalysisRunEntity(
                        status = RunStatus.RUNNING,
                        startedAt = now(),
                        newCount = diff.newFiles.size,
                        skippedCount = diff.skippedPaths.size,
                        cleanedCount = diff.cleanedPaths.size,
                    ),
                )
            }
            runId = created

            val crossRefs = mutableListOf<AnalysisRunFileCrossRef>()
            diff.newFiles.chunked(INSERT_BATCH).forEach { chunk ->
                currentCoroutineContext().ensureActive() // 取消只在批次边界生效（04 §4.9）
                db.withTransaction {
                    val ids = fileDao.insertIgnoreAll(
                        chunk.map { sf ->
                            MusicFileEntity(
                                entityId = null, // 未挂靠：实体最早的写入点在 05
                                path = sf.normalizedPath,
                                fileName = sf.ref.name,
                                size = sf.ref.size,
                                format = sf.format,
                                // 时长未知写 NULL 而不是 0（03 §2.2 的语义）
                                durationMs = sf.metadata.durationMs.takeIf { it > 0 },
                                isRepresentative = null, // 绝不写 0（复合唯一索引会误判冲突）
                                analysisStatus = AnalysisStatus.UNANALYZED,
                                addedAt = now(),
                            )
                        },
                    )
                    // -1 = 被 path 唯一索引忽略，绝不能进批次清单
                    crossRefs += ids.filter { it != -1L }
                        .map { AnalysisRunFileCrossRef(created, it) }
                }
            }
            db.withTransaction { if (crossRefs.isNotEmpty()) runDao.linkFiles(crossRefs) }

            diff.cleanedPaths.chunked(INSERT_BATCH).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                db.withTransaction { chunk.forEach { cleanMissingFile(it) } }
            }

            settings.setLastScanAt(now())
            stateFlow.update {
                it.copy(phase = ScanPhase.ANALYZING, runId = created, diff = null)
            }

            lyricHandoff.ingest(diff.lrcCandidates)
            analysisTrigger.analyzePending(created)

            return CommitResult.Committed(created, crossRefs.size, diff.cleanedPaths.size)
        } catch (e: CancellationException) {
            runId?.let { id ->
                db.withTransaction { db.analysisRunDao().setStatus(id, RunStatus.ABORTED) }
            }
            stateFlow.update { it.copy(phase = ScanPhase.IDLE, runId = null) }
            return CommitResult.Cancelled
        } catch (t: Throwable) {
            stateFlow.update { it.copy(phase = ScanPhase.IDLE, error = IoError(IoOperation.DB, cause = t)) }
            return CommitResult.Failed(FailureKind.IO, t)
        } finally {
            runningJob = null
            mutex.unlock()
        }
    }

    /**
     * 「放弃」——**不写库**（`04 §4.1`）。
     *
     * 只把内存态置空：不建 `analysis_run`、不写 `music_file`，因此不存在「放弃后仍留记录」
     * 的违规态。下次扫描因来源与磁盘都没变，会重新把这些文件比成「新增」再提示一次。
     */
    suspend fun discard() {
        stateFlow.update { it.copy(phase = ScanPhase.DISCARDED, diff = null) }
    }

    fun cancel() {
        runningJob?.cancel()
    }

    // —— 内部：遍历与元数据流水线（04 §4.5） ——

    /**
     * 过滤分两段落下（`04 §4.6`）：**体积在遍历阶段**、**时长在元数据之后**。
     *
     * `discovered` 仍是「遍历扫到几个文件」（进度语义，含被挡下来的）——
     * 「找到几首能用的」由 `diff.newFiles` 表达，两者不是一回事。
     */
    private suspend fun extractMetadataPipelined(
        roots: List<String>,
        filter: ScanFilter,
    ): List<ScannedFile> =
        coroutineScope {
            val gate = Dispatchers.IO.limitedParallelism(parallelism)
            storage.listFiles(roots, AudioFormats.AUDIO_EXTS) { n ->
                stateFlow.update { it.copy(discovered = n) }
            }
                // 体积先挡：`FileRef.size` 现成，碎片文件连元数据都不必解
                .filter { filter.acceptsSize(it.size) }
                .map { ref ->
                    async(gate) {
                        ensureActive()
                        // 超时与降级都归 metadataReader 自己（§4.4 落地补充）
                        val metadata = metadataReader.read(ref)
                        stateFlow.update { it.copy(metadataProcessed = it.metadataProcessed + 1) }
                        if (!filter.acceptsDuration(metadata.durationMs)) {
                            null
                        } else {
                            ScannedFile(
                                ref = ref,
                                normalizedPath = PathNormalizer.normalize(ref.path, primaryRoot),
                                format = AudioFormats.formatOf(ref.name),
                                metadata = metadata,
                            )
                        }
                    }
                }.toList().awaitAll().filterNotNull()
        }

    // —— 内部：.lrc 候选登记（04 §4.7） ——

    private fun lrcCandidates(
        level: StorageAccessLevel,
        musicSources: List<ScanSourceEntity>,
        lyricSources: List<ScanSourceEntity>,
    ): List<LrcCandidate> {
        // 降级通道下无法遍历目录，不产出任何候选（§4.7 第 4 条）
        if (level != StorageAccessLevel.FULL) return emptyList()

        val byPath = LinkedHashMap<String, LrcCandidate>()

        fun collect(roots: List<String>, origin: LyricSource) {
            if (roots.isEmpty()) return
            storage.listFiles(roots, AudioFormats.LRC_EXTS) {}.forEach { ref ->
                val normalized = PathNormalizer.normalize(ref.path, primaryRoot)
                // 同一目录既可能是音乐来源又是外部歌词目录：先去重，且同源优先
                val existing = byPath[normalized]
                if (existing == null || existing.origin != LyricSource.SAME_DIR) {
                    byPath[normalized] = LrcCandidate(
                        path = normalized,
                        name = ref.name,
                        size = ref.size,
                        lastModified = ref.lastModified,
                        origin = origin,
                    )
                }
            }
        }

        collect(musicSources.map { it.path }, LyricSource.SAME_DIR)
        collect(lyricSources.map { it.path }, LyricSource.EXTERNAL_DIR)
        return byPath.values.toList()
    }

    // —— 内部：差异比对（04 §4.6，唯一键 = 规范化绝对路径） ——

    private suspend fun diff(
        scanned: List<ScannedFile>,
        lrc: List<LrcCandidate>,
        level: StorageAccessLevel,
    ): ScanDiff {
        val existing = db.musicFileDao().allPaths().toHashSet()

        val newFiles = mutableListOf<ScannedFile>()
        val skipped = mutableListOf<String>()
        val seenLowercase = HashSet<String>()
        for (sf in scanned) {
            val key = sf.normalizedPath
            when {
                key in existing -> skipped += key
                // 大小写不敏感卷（SD/exFAT）上的兜底去重：唯一索引仍会拦住，这里只是少试一次
                !seenLowercase.add(key.lowercase()) -> skipped += key
                else -> newFiles += sf
            }
        }

        // 已删除清理只在**完整权限**下做。
        //
        // 降级通道下媒体库查不到 ≠ 磁盘上没了：照 04 §4.6 的公式（existing - scanned）
        // 会把「不在媒体库」当成「已删除」，经 I3 连实体一起删掉 —— 用户一旦失去
        // 「所有文件访问」，整个库就没了。验证不了存在性就不动库（见 §4.6 落地补充）。
        val cleaned = if (level == StorageAccessLevel.FULL) {
            existing.filterNot { storage.exists(it) }
        } else {
            emptyList()
        }

        return ScanDiff(newFiles, skipped, cleaned, lrc)
    }

    // —— 内部：清理已删记录（04 §4.8，复用 03 §4.4 的内核，**不触盘**） ——

    private suspend fun cleanMissingFile(path: String) {
        val row = db.musicFileDao().findByPath(path) ?: return

        val entityId = row.entityId
        if (entityId == null) {
            // 未挂靠（扫描刚写入、分析还没跑）：删行即可
            db.musicFileDao().deleteById(row.id)
            return
        }

        deletionService.unlinkFile(row.id)

        // 实体还在 → 重选代表（I2）并用新代表的内嵌元数据刷新展示字段（I11）。
        // 取值必须由这里做：DAO 拿不到 MetadataReader（03 §4.3 的注释已说明同一分工）。
        if (db.musicFileDao().countForEntity(entityId) > 0) {
            val representative = db.musicFileDao().representativeOf(entityId) ?: return
            val metadata = metadataReader.read(
                FileRef(representative.path, representative.fileName, representative.size, 0),
            )
            db.songDao().refreshDisplayFields(
                entityId = entityId,
                albumName = metadata.album,
                albumArtist = metadata.albumArtist,
                albumReleaseDate = metadata.date,
            )
        }
        // 实体已无文件 → unlinkFile 已按「删除实体」处理（级联清理，磁盘不动，I8/I3）
    }

    private companion object {
        const val INSERT_BATCH = 200
    }
}
