package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.column
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I7：同一个 `.lrc` 至多关联一个实体。
 *
 * 数据库侧由 `lyric.path` 唯一索引兜底（2a 的 schema 测试已钉住）；这里钉的是**避免撞上它**
 * 的机制 —— 匹配前先载入「已占用路径集合」，让扫描器在候选阶段就跳过被占用的歌词文件。
 */
@RunWith(AndroidJUnit4::class)
class LyricTest {

    private lateinit var db: MusicDatabase
    private lateinit var lyricDao: LyricDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lyricDao = db.lyricDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun attachLyric(entityId: Long, path: String, matchLevel: Int = 1) = db.exec(
        "INSERT INTO lyric (entity_id, path, source, match_level, matched_at) " +
            "VALUES ($entityId, '$path', 'EXTERNAL_DIR', $matchLevel, 0)"
    )

    @Test
    fun `已占用路径集合在匹配前就能取到`() = runDbTest {
        db.exec(
            "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
                "VALUES ('晴天', '周杰伦', '周杰伦', 0)"
        )
        val entityId = db.column("SELECT id FROM song_entity").single().toLong()
        attachLyric(entityId, "/lrc/晴天.lrc")
        attachLyric(entityId, "/lrc/晴天.zh.lrc", matchLevel = 2)

        assertThat(lyricDao.allMatchedPaths())
            .containsExactly("/lrc/晴天.lrc", "/lrc/晴天.zh.lrc")
    }

    @Test
    fun `没有歌词时占用集合为空`() = runDbTest {
        assertThat(lyricDao.allMatchedPaths()).isEmpty()
    }
}
