package com.aimusic.player.data.dao

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
class AnalysisRunDaoTest {

    private lateinit var db: MusicDatabase
    private lateinit var runDao: AnalysisRunDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runDao = db.analysisRunDao()
    }

    @After
    fun tearDown() = db.close()

    // 注意：跑分体用 `{ … }` 而不是 `= runBlocking { … }` —— 后者块末是断言（返回非 Unit 值），
    // 推断返回类型不是 void，JUnit4 会以 "should be void" 拒收整个类（P3-T5 踩过）。
    @Test
    fun `计数逐次累加_结束时写状态与完成时刻`() {
        runBlocking {
            val runId = runDao.createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 100L))

            runDao.bumpAnalyzedOk(runId)
            runDao.bumpAnalyzedOk(runId)
            runDao.bumpFailed(runId)
            runDao.finish(runId, RunStatus.ABORTED, finishedAt = 200L)

            val row = runDao.latest()!!
            assertThat(row.analyzedOk).isEqualTo(2)
            assertThat(row.failedCount).isEqualTo(1)
            assertThat(row.status).isEqualTo(RunStatus.ABORTED)
            assertThat(row.finishedAt).isEqualTo(200L)
        }
    }

    @Test
    fun `移除记录只删批次关联_不动文件行`() {
        runBlocking {
            val runId = runDao.createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 1L))
            val fileId = db.musicFileDao().insertIgnoreAll(listOf(fileEntity("/a.mp3"))).first()
            runDao.linkFiles(listOf(AnalysisRunFileCrossRef(runId, fileId)))

            val removed = runDao.unlinkFiles(runId, listOf(fileId))

            assertThat(removed).isEqualTo(1)
            assertThat(runDao.observeRunFiles(runId).first()).isEmpty()
            assertThat(db.musicFileDao().findByPath("/a.mp3")).isNotNull()
        }
    }

    private fun fileEntity(path: String) = MusicFileEntity(
        path = path, fileName = "a.mp3", size = 1L, format = "mp3", addedAt = 0L,
    )
}
