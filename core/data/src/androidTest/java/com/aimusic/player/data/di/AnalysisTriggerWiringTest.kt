package com.aimusic.player.data.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.data.analysis.AnalysisOrchestrator
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.data.scan.AnalysisTrigger
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.storage.MetadataReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 交棒语义：**后台启动**、不阻塞调用方，且真的把分析驱动到完成（Phase 3 的空实现做不到）。 */
@RunWith(AndroidJUnit4::class)
class AnalysisTriggerWiringTest {

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

    @Test
    fun `交棒立刻返回_分析在后台推进到完成`() {
        runBlocking {
            val fake = object : LlmNormalizer {
                override suspend fun normalize(requests: List<NormalizeRequest>) =
                    requests.map { NormalizeOutcome.Success(NormalizeResult(it.fileName, listOf("周杰伦"), emptyList())) }
            }
            val orchestrator = AnalysisOrchestrator(
                db = db,
                metadataReader = MetadataReader { AudioMetadata(null, null, null, null, null, 0L, false) },
                normalizer = fake,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // 照 RepositoryModule 的装配方式手工接一遍
            val trigger = AnalysisTrigger { runId ->
                scope.launch { orchestrator.analyzePending(runId).collect {} }
            }
            db.musicFileDao().insertIgnoreAll(
                (1..3).map {
                    MusicFileEntity(path = "/m/$it.mp3", fileName = "f$it.mp3", size = 1L, format = "mp3", addedAt = 0L)
                },
            )
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0L))

            val t0 = System.currentTimeMillis()
            trigger.analyzePending(runId)                       // 必须立刻返回
            assertThat(System.currentTimeMillis() - t0).isLessThan(500L)

            val deadline = System.currentTimeMillis() + 5_000
            while (db.analysisRunDao().latest()?.status != RunStatus.COMPLETED &&
                System.currentTimeMillis() < deadline
            ) {
                delay(10)
            }
            assertThat(db.analysisRunDao().latest()!!.status).isEqualTo(RunStatus.COMPLETED)
            assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(3)
            scope.cancel()
        }
    }
}
