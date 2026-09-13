package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertFile
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.linkFile
import com.aimusic.player.data.scalar
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I11：代表文件变更后，专辑 / 封面等展示信息随之切换。
 *
 * 注意「谁来读元数据」：`refreshDisplayFields` 只负责**确定性写入**，取值由调用方完成 ——
 * DAO 由 Room 实例化，拿不到 `MetadataReader`；且 `03 §1` 给 `:core:data` 的存储职责
 * 只有「物理删除 + 读封面缓存路径」，不含读元数据。
 */
@RunWith(AndroidJUnit4::class)
class DisplayFieldsTest {

    private lateinit var db: MusicDatabase
    private lateinit var songDao: SongDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `换代表文件后展示字段随之切换并作废封面缓存`() = runDbTest {
        val small = db.insertFile("/m/small.mp3", size = 100)
        val big = db.insertFile("/m/big.mp3", size = 500)
        val song = db.insertSong("晴天", "周杰伦", albumName = "旧专辑")
        db.linkFile(small, song, isRepresentative = true)
        db.linkFile(big, song)
        // 先塞一个封面缓存路径，验证刷新时会作废
        db.exec("UPDATE song_entity SET cover_cache_path = '/cache/old.webp' WHERE id = $song")

        // 代表文件换成 big，按 big 的内嵌元数据刷新
        songDao.refreshDisplayFields(
            entityId = song,
            albumName = "叶惠美",
            albumArtist = "周杰伦",
            albumReleaseDate = "2003-07-31",
        )

        assertThat(db.scalar("SELECT album_name FROM song_entity WHERE id = $song")).isEqualTo("叶惠美")
        assertThat(db.scalar("SELECT album_artist FROM song_entity WHERE id = $song")).isEqualTo("周杰伦")
        assertThat(db.scalar("SELECT album_release_date FROM song_entity WHERE id = $song"))
            .isEqualTo("2003-07-31")
        assertThat(db.scalar("SELECT cover_cache_path FROM song_entity WHERE id = $song")).isNull()
    }

    @Test
    fun `元数据缺专辑信息时写入_null_而不是空串`() = runDbTest {
        val file = db.insertFile("/m/a.mp3", size = 100)
        val song = db.insertSong("晴天", "周杰伦", albumName = "旧专辑")
        db.linkFile(file, song, isRepresentative = true)

        songDao.refreshDisplayFields(
            entityId = song,
            albumName = null,
            albumArtist = null,
            albumReleaseDate = null,
        )

        assertThat(db.scalar("SELECT album_name FROM song_entity WHERE id = $song")).isNull()
    }
}
