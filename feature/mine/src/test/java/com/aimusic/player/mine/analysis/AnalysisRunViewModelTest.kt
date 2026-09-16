package com.aimusic.player.mine.analysis

import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.analysis.AnalysisOrchestrator
import com.aimusic.player.data.analysis.AnalysisProgress
import com.aimusic.player.data.analysis.AnalysisRunRepository
import com.aimusic.player.data.analysis.AnalysisSessionState
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.RunFileRow
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.data.entity.MusicFileEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 分析记录页 VM 的单元测试（JVM）。照 `ScanViewModelTest` 的写法：MockK 顶掉协作者、真机无关。
 *
 * ⚠ `state` 是 `stateIn(WhileSubscribed)` —— **没有订阅者时它只会停在初值**，
 * 所以每个用例都要先把它热起来（`backgroundScope.launch { vm.state.collect {} }`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisRunViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun file(id: Long, name: String, status: AnalysisStatus, errorKind: String? = null) =
        RunFileRow(
            file = MusicFileEntity(
                id = id, path = "/m/$name", fileName = name, size = 1L, format = "mp3",
                addedAt = 0L, analysisStatus = status, errorKind = errorKind,
            ),
            linkedEntityId = null,
        )

    private fun newViewModel(
        rows: List<RunFileRow> = listOf(
            file(1L, "晴天.mp3", AnalysisStatus.LINKED),
            file(2L, "吻别.mp3", AnalysisStatus.FAILED, FailureKind.PARSE.name),
            file(3L, "菊花台.mp3", AnalysisStatus.UNANALYZED),
        ),
        progress: MutableSharedFlow<AnalysisProgress> = MutableSharedFlow(extraBufferCapacity = 8),
        session: MutableStateFlow<AnalysisSessionState> = MutableStateFlow(AnalysisSessionState(running = false)),
    ): Triple<AnalysisRunViewModel, AnalysisOrchestrator, MutableSharedFlow<AnalysisProgress>> {
        val orchestrator = mockk<AnalysisOrchestrator>(relaxed = true)
        every { orchestrator.state } returns session
        every { orchestrator.progress } returns progress
        every { orchestrator.analyzePending(any()) } returns emptyFlow()
        every { orchestrator.retry(any()) } returns emptyFlow()

        val runs = mockk<AnalysisRunRepository>(relaxed = true)
        every { runs.observeLatest() } returns flowOf(
            AnalysisRunEntity(id = 1L, status = RunStatus.RUNNING, startedAt = 0L),
        )
        every { runs.observeRunFiles(any()) } returns flowOf(rows)

        return Triple(AnalysisRunViewModel(orchestrator, runs), orchestrator, progress)
    }

    @Test
    fun `重试只看FAILED与UNANALYZED_LINKED不给重试入口`() {
        runTest {
            val (vm, _, _) = newViewModel()
            backgroundScope.launch { vm.state.collect {} }

            // 等状态就绪：stateIn(WhileSubscribed) 在上游首次合并前只发初值，立刻读 .value 会拿到空 rows
            val ready = vm.state.first { it.rows.isNotEmpty() }
            val byStatus = ready.rows.associateBy { it.status }
            assertThat(byStatus.getValue(AnalysisStatus.LINKED).canRetry).isFalse()
            assertThat(byStatus.getValue(AnalysisStatus.FAILED).canRetry).isTrue()
            assertThat(byStatus.getValue(AnalysisStatus.UNANALYZED).canRetry).isTrue()
        }
    }

    @Test
    fun `点重试_调orchestrator_retry且只带这一个fileId`() {
        runTest {
            val (vm, orchestrator, _) = newViewModel()
            backgroundScope.launch { vm.state.collect {} }

            vm.onRetry(42L)

            verify(exactly = 1) { orchestrator.retry(listOf(42L)) }
        }
    }

    @Test
    fun `429退避中_该行标记重试中_不算失败`() {
        runTest {
            val progress = MutableSharedFlow<AnalysisProgress>(extraBufferCapacity = 8)
            val session = MutableStateFlow(
                AnalysisSessionState(running = true, runId = 1L, total = 20, done = 3, ok = 3, failed = 0),
            )
            val (vm, _, _) = newViewModel(
                rows = listOf(file(3L, "菊花台.mp3", AnalysisStatus.ANALYZING)),
                progress = progress,
                session = session,
            )
            backgroundScope.launch { vm.state.collect {} }

            progress.emit(AnalysisProgress.Retrying(fileId = 3L, attempt = 2, delayMs = 2_000L))

            // 同样等就绪，而不是发完立刻读
            val ready = vm.state.first { it.rows.any { row -> row.retrying } }
            assertThat(ready.rows.single { it.fileId == 3L }.retrying).isTrue()
            assertThat(ready.failed).isEqualTo(0)   // 09 §5.1：重试中不计失败
        }
    }
}
