package com.aimusic.player.mine.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.analysis.AnalysisOrchestrator
import com.aimusic.player.data.analysis.AnalysisProgress
import com.aimusic.player.data.analysis.AnalysisRunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

import kotlinx.coroutines.launch

/**
 * 分析记录页的 ViewModel（`09 §3.2.10`）。
 *
 * **为什么 state 要"热起来"**：`orchestrator.progress` 是 `SharedFlow`（没有初值），
 * 直接塞进 `combine` 会等它先发一次才产出 —— 界面就会长时间空着。做法是把热流的两个关心点
 * （`Retrying` 与最近一条 `FileUpdated`）收进内部 `MutableStateFlow`，再四路 combine。
 *
 * **重试的取消语义**：`onRetry` 是"收集即驱动、离开界面即取消"（与 `analyzePending` 同一套冷流语义）。
 * 也就是说用户点了重试又退出界面，这次重试会被取消成 ABORTED，而不是偷偷在后台跑完。
 */
@HiltViewModel
class AnalysisRunViewModel @Inject constructor(
    private val orchestrator: AnalysisOrchestrator,
    private val runs: AnalysisRunRepository,
) : ViewModel() {

    private val retrying = MutableStateFlow<AnalysisProgress.Retrying?>(null)
    private val lastFile = MutableStateFlow<AnalysisProgress.FileUpdated?>(null)

    private val eventsSink = MutableSharedFlow<AnalysisRunEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AnalysisRunEvent> = eventsSink.asSharedFlow()

    private val runFiles = runs.observeLatest().flatMapLatest { run ->
        if (run == null) flowOf(emptyList()) else runs.observeRunFiles(run.id)
    }

    val state: StateFlow<AnalysisRunUiState> = combine(
        runs.observeLatest(),
        orchestrator.state,
        retrying,
        lastFile,
        runFiles,
    ) { run, session, retryingNow, fileEvent, rows ->
        AnalysisRunUiState(
            runId = run?.id,
            running = session.running,
            total = session.total,
            done = session.done,
            ok = session.ok,
            failed = session.failed,
            retrying = retryingNow,
            aborted = session.aborted,
            rows = rows.map { row ->
                AnalysisFileUi(
                    fileId = row.file.id,
                    fileName = row.file.fileName,
                    status = row.file.analysisStatus,
                    linkedEntityId = row.linkedEntityId,
                    errorKind = row.file.errorKind,
                    // 重试中优先于底层状态：09 §5.1 第 4 行 ——「重试中」不计失败
                    retrying = retryingNow?.fileId == row.file.id,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalysisRunUiState(),
    )

    init {
        viewModelScope.launch {
            orchestrator.progress.collect { event ->
                when (event) {
                    is AnalysisProgress.Retrying -> retrying.value = event
                    is AnalysisProgress.FileUpdated -> {
                        lastFile.value = event
                        if (event.fileId == retrying.value?.fileId) retrying.value = null
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onRetry(fileId: Long) {
        // 收集即驱动；界面离开 → viewModelScope 取消 → 该次重试变 ABORTED（不偷偷跑完）
        viewModelScope.launch { orchestrator.retry(listOf(fileId)).collect {} }
    }

    fun onRemoveRecord(fileId: Long) {
        val runId = state.value.runId ?: return
        viewModelScope.launch {
            runs.removeRecords(runId, listOf(fileId))
            eventsSink.tryEmit(AnalysisRunEvent.ShowMessage("已移除记录"))
        }
    }
}

/** 一次性事件（导航 / 提示），不放进 state —— 否则旋转屏幕会重放。 */
sealed interface AnalysisRunEvent {
    data class OpenSongDetail(val entityId: Long) : AnalysisRunEvent
    data class ShowMessage(val text: String) : AnalysisRunEvent
    data object OpenSettings : AnalysisRunEvent
}
