package com.aimusic.player.data.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 扫描所需的 DAO 扩充（`04 §2` 点名的 `allPaths` / `findByPath` / `insertIgnoreAll`，
 * 以及 `AnalysisRunDao` 置 `ABORTED`）。
 *
 * `insertIgnoreAll` 的返回码是提交事务的关键：`-1` 表示「这一行被唯一索引忽略了」，
 * 那种行**不能**记进 `analysis_run_file` —— 否则批次清单会指向一条不存在的文件行。
 */
@RunWith(AndroidJUnit4::class)
class ScanDaoTest {

    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(path: String) = MusicFileEntity(
        path = path,
        fileName = path.substringAfterLast('/'),
        size = 10,
        format = path.substringAfterLast('.'),
        addedAt = 0,
    )

    @Test
    fun `insertIgnoreAll_新行给_id_重复行给_-1`() = runDbTest {
        val dao = db.musicFileDao()

        val first = dao.insertIgnoreAll(listOf(file("/m/a.mp3"), file("/m/b.mp3")))
        val second = dao.insertIgnoreAll(listOf(file("/m/b.mp3"), file("/m/c.mp3")))

        assertThat(first).hasSize(2)
        assertThat(first.all { it > 0 }).isTrue()
        assertThat(second[0]).isEqualTo(-1L) // b 已存在 → 被忽略
        assertThat(second[1]).isGreaterThan(0L)
    }

    @Test
    fun `路径唯一_重复插入不会多出行`() = runDbTest {
        val dao = db.musicFileDao()

        dao.insertIgnoreAll(listOf(file("/m/a.mp3")))
        dao.insertIgnoreAll(listOf(file("/m/a.mp3")))

        assertThat(dao.allPaths()).containsExactly("/m/a.mp3")
    }

    @Test
    fun `allPaths_一次给出全部已入库路径`() = runDbTest {
        val dao = db.musicFileDao()
        dao.insertIgnoreAll(listOf(file("/m/a.mp3"), file("/m/b.flac"), file("/m/c.wav")))

        assertThat(dao.allPaths()).containsExactly("/m/a.mp3", "/m/b.flac", "/m/c.wav")
    }

    @Test
    fun `findByPath_命中与未命中`() = runDbTest {
        val dao = db.musicFileDao()
        dao.insertIgnoreAll(listOf(file("/m/a.mp3")))

        assertThat(dao.findByPath("/m/a.mp3")?.fileName).isEqualTo("a.mp3")
        assertThat(dao.findByPath("/m/没有.mp3")).isNull()
    }

    @Test
    fun `analysis_run_建批次后能置为_ABORTED`() = runDbTest {
        val dao = db.analysisRunDao()
        val runId = dao.createRun(
            AnalysisRunEntity(
                status = RunStatus.RUNNING,
                startedAt = 100,
                newCount = 3,
                skippedCount = 1,
                cleanedCount = 2,
            ),
        )

        dao.setStatus(runId, RunStatus.ABORTED)

        val run = dao.latest()!!
        assertThat(run.status).isEqualTo(RunStatus.ABORTED)
        // 计数如实保留：取消不该抹掉「这一批比对出了什么」
        assertThat(run.newCount).isEqualTo(3)
        assertThat(run.skippedCount).isEqualTo(1)
        assertThat(run.cleanedCount).isEqualTo(2)
    }

    @Test
    fun `批次与文件行能批量关联`() = runDbTest {
        val fileDao = db.musicFileDao()
        val runDao = db.analysisRunDao()
        val ids = fileDao.insertIgnoreAll(listOf(file("/m/a.mp3"), file("/m/b.mp3")))
        val runId = runDao.createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 0))

        runDao.linkFiles(ids.map { AnalysisRunFileCrossRef(runId, it) })

        assertThat(runDao.observeRunFiles(runId).first()).hasSize(2)
    }
}
