package com.aimusic.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.probe.ProbeDao
import com.aimusic.player.probe.ProbeDatabase
import com.aimusic.player.probe.ProbeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0.4 技术探针：确认 Room 能在真机 SQLite 上跑。
 * 正式的不变量测试 I1–I12（文档 11 §8.2）在 Phase 2 落地。
 */
@RunWith(AndroidJUnit4::class)
class ProbeRoomTest {

    private lateinit var db: ProbeDatabase
    private lateinit var dao: ProbeDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProbeDatabase::class.java).build()
        dao = db.probeDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 读写往返正常() = runBlocking {
        dao.upsert(ProbeEntity(1, "/sdcard/a.mp3"))
        dao.upsert(ProbeEntity(2, "/sdcard/b.mp3"))

        val all = dao.all()
        assertEquals(2, all.size)
        assertEquals("/sdcard/a.mp3", all[0].path)
    }

    @Test
    fun 主键冲突时替换而非报错() = runBlocking {
        dao.upsert(ProbeEntity(1, "/sdcard/a.mp3"))
        dao.upsert(ProbeEntity(1, "/sdcard/changed.mp3"))

        val all = dao.all()
        assertEquals(1, all.size)
        assertEquals("/sdcard/changed.mp3", all[0].path)
    }
}
