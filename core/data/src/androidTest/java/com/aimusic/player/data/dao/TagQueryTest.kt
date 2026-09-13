package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.insertCategory
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.insertTag
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I6：歌曲列表的标签展示取「主要分类」标签还是全部标签，由 `onlyMain` 精确控制。
 */
@RunWith(AndroidJUnit4::class)
class TagQueryTest {

    private lateinit var db: MusicDatabase
    private lateinit var tagDao: TagDao

    private val NOW = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tagDao = db.tagDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `onlyMain_为真时只返回主要分类下的标签`() = runDbTest {
        val main = db.insertCategory("曲风", isMain = true)
        val other = db.insertCategory("心情", isMain = false)
        db.insertTag("抒情", main)
        db.insertTag("深夜", other)
        val song = db.insertSong("晴天", "周杰伦")

        tagDao.attachTags(listOf(song), listOf("抒情"), now = NOW)
        tagDao.attachTags(listOf(song), listOf("深夜"), now = NOW)

        assertThat(tagDao.tagsOfSongs(listOf(song), onlyMain = false).map { it.name })
            .containsExactly("深夜", "抒情")
        assertThat(tagDao.tagsOfSongs(listOf(song), onlyMain = true).map { it.name })
            .containsExactly("抒情")
    }

    @Test
    fun `批量查询一次覆盖多首歌_避免逐首查询`() = runDbTest {
        val main = db.insertCategory("曲风", isMain = true)
        db.insertTag("抒情", main)
        val first = db.insertSong("晴天", "周杰伦")
        val second = db.insertSong("小酒窝", "林俊杰")
        tagDao.attachTags(listOf(first, second), listOf("抒情"), now = NOW)

        val rows = tagDao.tagsOfSongs(listOf(first, second), onlyMain = false)

        assertThat(rows.map { it.entityId }).containsExactly(first, second)
        assertThat(rows.map { it.name }).containsExactly("抒情", "抒情")
        assertThat(rows.first().categoryId).isEqualTo(main)
    }

    @Test
    fun `没有标签的歌不会出现在结果里`() = runDbTest {
        val song = db.insertSong("晴天", "周杰伦")

        assertThat(tagDao.tagsOfSongs(listOf(song), onlyMain = false)).isEmpty()
    }
}
