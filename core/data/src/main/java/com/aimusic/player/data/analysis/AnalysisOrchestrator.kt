package com.aimusic.player.data.analysis

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.asFailureKind
import com.aimusic.player.llm.NormalizeRequest
import androidx.room.withTransaction
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.MetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

const val DEFAULT_BATCH_SIZE = 20
const val DEFAULT_PROGRESS_THROTTLE_MS = 250L

/**
 * 分析编排（`05 §4.9`、spec §10）。
 *
 * - **冷 Flow + 单 worker**：`analyzePending` 是冷流，谁收集谁驱动；同一时刻只允许一个分析在跑
 *   （`Mutex.tryLock` 失败就直接结束 —— 不排队，排队的分析对用户没有意义）。
 * - **热镜像** [state] / [progress]：冷流会被 AppScope 的触发器独占，UI 收集不到；而取消之后
 *   编排器仍要通过热镜像广播终态。
 * - **取消在批次边界生效**：`ensureActive()` 放在每批开头，第 k 个文件回 `UNANALYZED`（Task 5）。
 */
class AnalysisOrchestrator(
    private val db: MusicDatabase,
    private val metadataReader: MetadataReader,
    private val normalizer: LlmNormalizer,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val progressThrottleMs: Long = DEFAULT_PROGRESS_THROTTLE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow(AnalysisSessionState())
    val state: StateFlow<AnalysisSessionState> = stateFlow.asStateFlow()

    private val progressSink = MutableSharedFlow<AnalysisProgress>(extraBufferCapacity = 64)
    val progress: SharedFlow<AnalysisProgress> = progressSink.asSharedFlow()

    private var runningJob: Job? = null

    /** 分析待分析文件（`runId` 由建批次方给出）。冷 Flow，非 suspend。 */
    fun analyzePending(runId: Long): Flow<AnalysisProgress> = flow {
        if (!mutex.tryLock()) return@flow
        try {
            val files = db.musicFileDao().pendingForAnalysis()
            emitAll(driveLocked(runId, files))
        } finally {
            mutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        runningJob?.cancel()
    }

    private fun driveLocked(runId: Long, files: List<MusicFileEntity>): Flow<AnalysisProgress> = flow {
        runningJob = currentCoroutineContext()[Job]
        val total = files.size
        var done = 0
        var ok = 0
        var failed = 0
        var lastEmit = 0L

        try {
        publish(AnalysisProgress.Started(runId, total))
        stateFlow.update {
            it.copy(running = true, runId = runId, total = total, done = 0, ok = 0, failed = 0, aborted = false)
        }

        files.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            currentCoroutineContext().ensureActive()

            stateFlow.update {
                it.copy(
                    batchIndex = batchIndex + 1,
                    batchCount = (total + batchSize - 1) / batchSize,
                )
            }

            val batchFiles = pendingOf(batch)
            batchFiles.forEach { file ->
                db.musicFileDao().setStatus(file.id, AnalysisStatus.ANALYZING, null, null)
                publish(AnalysisProgress.FileUpdated(file.id, file.fileName, AnalysisStatus.ANALYZING))
            }

            // 目录每批实时读一次（不是快照）：分析期间用户可能新加了标签/分类。
            val categories = db.categoryDao().currentNames()
            val tags = db.tagDao().currentTagRefs()
            val requests = batchFiles.map { file ->
                NormalizeRequest(
                    fileName = file.fileName,
                    metadata = metadataReader.read(FileRef(file.path, file.fileName, file.size, 0)),
                    categories = categories,
                    tags = tags,
                )
            }

            val outcomes = normalizer.normalize(requests)
            batchFiles.forEachIndexed { index, file ->
                when (val outcome = outcomes.getOrNull(index)) {
                    is NormalizeOutcome.Success -> {
                        // 挂靠与计数**同事务**（`05 §4.9`）：否则会出现「文件已关联但批次没记账」的中间态
                        val entityId = db.withTransaction {
                            db.songDao().attachAnalysisResult(file.id, outcome.result, now())
                            db.analysisRunDao().bumpAnalyzedOk(runId)
                            // 状态也要落库：T3 只发布了 LINKED 事件却没写状态，
                            // 而 pendingForAnalysis() 不认 ANALYZING（也不算 pending），于是测试全绿、
                            // 实际文件一直停在 ANALYZING —— T5 的取消用例把我回退成 UNANALYZED 才照出来。
                            db.musicFileDao().setStatus(file.id, AnalysisStatus.LINKED, null, null)
                            db.musicFileDao().entityIdOf(file.id)
                        }
                        ok++
                        publish(
                            AnalysisProgress.FileUpdated(
                                file.id, file.fileName, AnalysisStatus.LINKED, linkedEntityId = entityId,
                            ),
                        )
                    }
                    is NormalizeOutcome.RateLimited -> {
                        // 429 退避耗尽：这是"限流"，不是解析失败
                        db.musicFileDao().setStatus(
                            file.id, AnalysisStatus.FAILED,
                            FailureKind.RATE_LIMIT.name, FailureKind.RATE_LIMIT.name,
                        )
                        db.analysisRunDao().bumpFailed(runId)
                        failed++
                        publish(
                            AnalysisProgress.FileUpdated(
                                file.id, file.fileName, AnalysisStatus.FAILED,
                                error = FailureKind.RATE_LIMIT.name,
                            ),
                        )
                    }
                    is NormalizeOutcome.Failure -> {
                        // 整批同命运由上游保证（spec §9.1：调用失败时每一项都是同一个 Failure）；
                        // 编排器只逐文件落库，不按文件重发。
                        val fk = outcome.kind.asFailureKind()
                        db.musicFileDao().setStatus(file.id, AnalysisStatus.FAILED, fk.name, fk.name)
                        db.analysisRunDao().bumpFailed(runId)
                        failed++
                        publish(
                            AnalysisProgress.FileUpdated(
                                file.id, file.fileName, AnalysisStatus.FAILED, error = fk.name,
                            ),
                        )
                    }
                    null -> failed++
                }
                done++
                stateFlow.update { it.copy(done = done, ok = ok, failed = failed) }
                val t = now()
                if (t - lastEmit >= progressThrottleMs || done == total) {   // 进度节流（`05 §7.2`）
                    lastEmit = t
                    publish(AnalysisProgress.Running(done, total, ok, failed))
                }
            }
        }

        db.analysisRunDao().finish(runId, RunStatus.COMPLETED, now())
        stateFlow.update { it.copy(running = false) }
        publish(AnalysisProgress.Finished(runId, ok, failed, aborted = false))
        } catch (e: CancellationException) {
            // 取消不是错误（05 §5.3）：把本批仍在 ANALYZING 的文件退回 UNANALYZED，不置 FAILED。
            // NonCancellable：收尾本身必须跑完 —— 否则会留下"running=true、状态 RUNNING"的僵尸会话。
            withContext(NonCancellable) {
                // 直接回退"当前仍停在 ANALYZING 的文件"，而不是按 runId 关联查 ——
                // 编排器并不负责写 analysis_run_file（那是建批次方的事），按 runId 查会查空。
                // 单 worker 下"当前 ANALYZING"就是"本次没跑完的"，语义等价且不依赖关联表。
                db.musicFileDao().allByStatus(AnalysisStatus.ANALYZING).forEach {
                    db.musicFileDao().setStatus(it.id, AnalysisStatus.UNANALYZED, null, null)
                }
                db.analysisRunDao().finish(runId, RunStatus.ABORTED, now())
                stateFlow.update { it.copy(running = false, aborted = true, retrying = null) }
                // 只发**热**镜像：此刻冷流正在取消，向它 emit 会触发 Flow invariant 违例
                // （concurrent emissions are prohibited）。热镜像存在的意义正是在这里。
                progressSink.tryEmit(AnalysisProgress.Finished(runId, ok, failed, aborted = true))
            }
            throw e
        } finally {
            runningJob = null
        }
    }.flowOn(Dispatchers.IO)

    /** I4 兜底：`pendingForAnalysis` 已经过滤过，这里再挡一次（并发写入下不能只靠一处）。 */
    private fun pendingOf(batch: List<MusicFileEntity>) =
        batch.filter { it.analysisStatus != AnalysisStatus.LINKED }

    private suspend fun FlowCollector<AnalysisProgress>.publish(event: AnalysisProgress) {
        progressSink.tryEmit(event)
        emit(event)
    }
}
