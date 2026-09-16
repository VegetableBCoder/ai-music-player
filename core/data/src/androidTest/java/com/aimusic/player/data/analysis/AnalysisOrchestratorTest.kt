package com.aimusic.player.data.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.CategoryEntity
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.entity.TagEntity
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.llm.LlmFailureKind
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.storage.MetadataReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 记录每次收到的请求，按预设脚本逐个回答。 */
private class FakeNormalizer(
    private val answer: suspend (List<NormalizeRequest>) -> List<NormalizeOutcome>,
) : LlmNormalizer {
    val calls = mutableListOf<List<NormalizeRequest>>()

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        calls += requests
        return answer(requests)
    }
}

@RunWith(AndroidJUnit4::class)
class AnalysisOrchestratorTest {

    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    // 方法用块体（不是 `= runBlocking {}`）：后者返回非 Unit，JUnit4 会以 "should be void" 拒收整类。
    @Test
    fun `一批20个只发一次请求_逐步上报FileUpdated`() {
        runBlocking {
            givenPendingFiles(20)
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            val events = build(fake).analyzePending(runId).toList()

            assertThat(fake.calls).hasSize(1)
            assertThat(fake.calls.single()).hasSize(20)
            assertThat(events.first()).isEqualTo(AnalysisProgress.Started(runId, 20))
            assertThat(
                events.filterIsInstance<AnalysisProgress.FileUpdated>()
                    .count { it.status == AnalysisStatus.ANALYZING },
            ).isEqualTo(20)
            assertThat(events.last())
                .isEqualTo(AnalysisProgress.Finished(runId, ok = 20, failed = 0, aborted = false))
        }
    }

    @Test
    fun `21个文件分成两批_最后一批是余数`() {
        runBlocking {
            givenPendingFiles(21)
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            build(fake).analyzePending(runId).toList()

            assertThat(fake.calls.map { it.size }).containsExactly(20, 1).inOrder()
        }
    }

    private fun build(normalizer: LlmNormalizer, batchSize: Int = 20) = AnalysisOrchestrator(
        db = db,
        metadataReader = MetadataReader { AudioMetadata(null, null, null, null, null, 0L, false) },
        normalizer = normalizer,
        batchSize = batchSize,
    )

    private fun successFor(name: String) = NormalizeOutcome.Success(
        NormalizeResult(canonicalTitle = name, artists = listOf("周杰伦"), tagAssignments = emptyList()),
    )

    private suspend fun givenPendingFiles(count: Int) {
        db.musicFileDao().insertIgnoreAll(
            (1..count).map {
                MusicFileEntity(
                    path = "/music/$it.mp3",
                    fileName = "f$it.mp3",
                    size = 1L,
                    format = "mp3",
                    addedAt = 0L,
                )
            },
        )
    }

    @Test
    fun `目录每批实时读取_不是快照`() {
        runBlocking {
            givenPendingFiles(21)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val seenCategories = mutableListOf<List<String>>()
            val fake = FakeNormalizer { reqs ->
                seenCategories += reqs[0].categories
                reqs.map { successFor(it.fileName) }
            }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            build(fake).analyzePending(runId).toList()

            assertThat(seenCategories).hasSize(2)   // 两批各读一次目录
        }
    }

    @Test
    fun `成功逐文件累加analyzed_ok_并把文件置LINKED`() {
        runBlocking {
            givenPendingFiles(3)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            val events = build(fake).analyzePending(runId).toList()

            assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(3)
            assertThat(db.musicFileDao().pendingForAnalysis()).isEmpty()
            assertThat(
                events.filterIsInstance<AnalysisProgress.FileUpdated>()
                    .last { it.status == AnalysisStatus.LINKED }.linkedEntityId,
            ).isNotNull()
        }
    }

    @Test
    fun `已经LINKED的文件再次触发_零网络请求`() {
        runBlocking {
            givenPendingFiles(2)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }

            val run1 = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))
            build(fake).analyzePending(run1).toList()
            val callsAfterFirst = fake.calls.size

            val run2 = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))
            build(fake).analyzePending(run2).toList()

            assertThat(fake.calls.size).isEqualTo(callsAfterFirst)   // 入口 pendingForAnalysis 已滤掉 LINKED（I4）
        }
    }

    private suspend fun givenCategory(name: String): Long =
        db.categoryDao().insertCategory(CategoryEntity(name = name))

    private suspend fun givenTag(name: String, category: String) {
        db.tagDao().insertTagIgnoring(
            TagEntity(name = name, categoryId = db.categoryDao().categoryIdByName(category)!!, createdAt = 0L),
        )
    }

    @Test
    fun `调用失败_整批20个都FAILED且error_kind为NETWORK`() {
        runBlocking {
            givenPendingFiles(20)
            val fake = FakeNormalizer { reqs ->
                reqs.map { NormalizeOutcome.Failure(LlmFailureKind.NETWORK, java.io.IOException("boom")) }
            }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            build(fake).analyzePending(runId).toList()

            assertThat(db.analysisRunDao().latest()!!.failedCount).isEqualTo(20)
            val failedFiles = db.musicFileDao().allByStatus(AnalysisStatus.FAILED)
            assertThat(failedFiles).hasSize(20)
            assertThat(failedFiles.map { it.errorKind }.toSet()).containsExactly(FailureKind.NETWORK.name)
        }
    }

    @Test
    fun `条目级解析失败_只连坐那一个_其余照常LINKED`() {
        runBlocking {
            givenPendingFiles(3)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val fake = FakeNormalizer { reqs ->
                reqs.mapIndexed { i, r ->
                    if (i == 1) NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, null)
                    else successFor(r.fileName)
                }
            }
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            build(fake).analyzePending(runId).toList()

            val run = db.analysisRunDao().latest()!!
            assertThat(run.analyzedOk).isEqualTo(2)
            assertThat(run.failedCount).isEqualTo(1)
            // INVALID_OUTPUT → PARSE（05 §6）
            assertThat(db.musicFileDao().allByStatus(AnalysisStatus.FAILED).single().errorKind)
                .isEqualTo(FailureKind.PARSE.name)
        }
    }

    @Test
    fun `中途取消_第k个回UNANALYZED_run为ABORTED_且收到Finished_aborted`() {
        runBlocking {
            givenPendingFiles(3)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val fake = FakeNormalizer { reqs ->
                // 按**调用次数**卡住而不是按文件名：pendingForAnalysis() 的顺序未定义，
                // 假设"某个文件先被处理"会让用例随机红（首版就是这么红的）。
                calls++
                if (calls >= 2) {
                    gate.await()   // 永不返回，等测试取消
                }
                reqs.map { successFor(it.fileName) }
            }
            val orchestrator = build(fake, batchSize = 1)   // 每文件一批，便于定位第 k 个
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            val finished = mutableListOf<AnalysisProgress>()
            val watcher = launch(Dispatchers.Default) {
                orchestrator.progress.collect { if (it is AnalysisProgress.Finished) finished += it }
            }
            val worker = launch(Dispatchers.Default) { orchestrator.analyzePending(runId).collect {} }

            // 等"第 2 次调用已发出"，而不是等"有文件处于 ANALYZING" ——
            // 后者在第 1 个文件刚被置为 ANALYZING 时就满足，会在它分析完之前就取消，
            // 于是连它也被回退成 UNANALYZED（首版即如此，断言 3 vs 2）。
            awaitUntil { fake.calls.size >= 2 }
            orchestrator.cancel()
            worker.join()

            val run = db.analysisRunDao().latest()!!
            assertThat(run.status).isEqualTo(RunStatus.ABORTED)
            assertThat(run.finishedAt).isNotNull()
            assertThat(db.musicFileDao().allByStatus(AnalysisStatus.UNANALYZED)).hasSize(2)  // f2 + f3
            assertThat(db.musicFileDao().allByStatus(AnalysisStatus.LINKED)).hasSize(1)      // f1 不回滚
            assertThat((finished.single() as AnalysisProgress.Finished).aborted).isTrue()
            watcher.cancel()
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("等待条件超时（${timeoutMs}ms）")
    }

    @Test
    fun `retry只为给定文件建批次_不扫全量pending`() {
        runBlocking {
            givenPendingFiles(5)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val target = db.musicFileDao().pendingForAnalysis().take(2).map { it.id }
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }

            val events = build(fake).retry(target).toList()

            assertThat(fake.calls.single()).hasSize(2)
            assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(2)
            assertThat(db.musicFileDao().pendingForAnalysis()).hasSize(3)
            assertThat((events.first() as AnalysisProgress.Started).total).isEqualTo(2)
        }
    }

    @Test
    fun `retry传入已LINKED的id_零网络请求`() {
        runBlocking {
            givenPendingFiles(1)
            givenCategory("音乐类型")
            givenTag("流行", "音乐类型")
            val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
            val orchestrator = build(fake)
            val id = db.musicFileDao().pendingForAnalysis().single().id
            orchestrator.retry(listOf(id)).toList()
            val calls = fake.calls.size

            orchestrator.retry(listOf(id)).toList()   // 第二次：该文件已 LINKED

            assertThat(fake.calls.size).isEqualTo(calls)   // I4 兜底生效
        }
    }
}
