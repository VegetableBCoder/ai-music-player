package com.aimusic.player.data.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.storage.MetadataReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 记录每次收到的请求，按预设脚本逐个回答。 */
private class FakeNormalizer(
    private val answer: (List<NormalizeRequest>) -> List<NormalizeOutcome>,
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

    private fun build(normalizer: LlmNormalizer) = AnalysisOrchestrator(
        db = db,
        metadataReader = MetadataReader { AudioMetadata(null, null, null, null, null, 0L, false) },
        normalizer = normalizer,
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
}
