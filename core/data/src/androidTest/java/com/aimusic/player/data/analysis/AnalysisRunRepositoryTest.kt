package com.aimusic.player.data.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisRunRepositoryTest {

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
    fun `没有批次时发null_有批次时发最近一条`() {
        runBlocking {
            val repo = AnalysisRunRepository(db)
            assertThat(repo.observeLatest().first()).isNull()

            db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 5L))

            assertThat(repo.observeLatest().first()!!.startedAt).isEqualTo(5L)
        }
    }

    @Test
    fun `移除记录只解绑批次_不删文件行`() {
        runBlocking {
            val repo = AnalysisRunRepository(db)
            val runId = db.analysisRunDao().createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 1L))
            val ids = db.musicFileDao().insertIgnoreAll(
                (1..2).map {
                    MusicFileEntity(path = "/m/$it.mp3", fileName = "f$it.mp3", size = 1L, format = "mp3", addedAt = 0L)
                },
            )
            db.analysisRunDao().linkFiles(ids.map { AnalysisRunFileCrossRef(runId, it) })

            repo.removeRecords(runId, listOf(ids.first()))

            assertThat(repo.observeRunFiles(runId).first()).hasSize(1)
            assertThat(db.musicFileDao().allPaths()).hasSize(2)   // 文件行仍在
        }
    }
}
