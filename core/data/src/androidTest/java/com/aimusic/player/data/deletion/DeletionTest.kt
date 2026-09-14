package com.aimusic.player.data.deletion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.column
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.error.DeleteFileResult
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertCategory
import com.aimusic.player.data.insertFile
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.insertTag
import com.aimusic.player.data.linkFile
import com.aimusic.player.data.representativeIdOf
import com.aimusic.player.data.scalar
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.StorageSource
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I8：删除整首歌**不触碰磁盘**；物理删除**先删磁盘再改库**，磁盘失败则库必须原样不动。
 *
 * 「磁盘调用次数 = 0」这条断言的意义在于：它把「删除实体不做物理删除」从一句口头约定
 * 变成可回归的检查 —— 一旦有人顺手在 `deleteSong` 里加了删文件，这条就会红。
 */
@RunWith(AndroidJUnit4::class)
class DeletionTest {

    private lateinit var db: MusicDatabase
    private lateinit var storage: FakeStorageSource
    private lateinit var deletionService: DeletionService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = FakeStorageSource()
        deletionService = DeletionService(db, storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 造一个「什么都有」的实体：两个文件、歌词、标签、历史、队列、播放状态指向队列项。 */
    private fun seed(): Seeded {
        val entity = db.insertSong("晴天", "周杰伦")
        val small = db.insertFile("/m/small.mp3", size = 100, status = "LINKED")
        val big = db.insertFile("/m/big.mp3", size = 500, status = "LINKED")
        db.linkFile(small, entity, isRepresentative = false)
        db.linkFile(big, entity, isRepresentative = true)

        db.exec(
            "INSERT INTO lyric (entity_id, path, source, match_level, matched_at) " +
                "VALUES ($entity, '/lrc/晴天.lrc', 'EXTERNAL', 1, 0)"
        )
        val category = db.insertCategory("曲风")
        db.insertTag("抒情", category)
        db.exec("INSERT INTO entity_tag (entity_id, tag_id, created_at) VALUES ($entity, '抒情', 0)")
        db.exec("INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($entity, '周杰伦', 0)")
        db.exec(
            "INSERT INTO play_history (entity_id, play_session_id, played_at) " +
                "VALUES ($entity, 's-1', 0)"
        )
        db.exec(
            "INSERT INTO queue_item (position, entity_id, is_forced, created_at) " +
                "VALUES (0, $entity, 0, 0)"
        )
        val queueItem = db.column("SELECT id FROM queue_item").single().toLong()
        db.exec(
            "INSERT INTO playback_state (id, current_queue_item_id, mode, position_ms, " +
                "session_max_position_ms, updated_at) VALUES (0, $queueItem, 'LIST_LOOP', 0, 0, 0)"
        )
        return Seeded(entity, small, big)
    }

    private data class Seeded(val entity: Long, val small: Long, val big: Long)

    // —— I8：删除实体不触碰磁盘 ——

    @Test
    fun `删除实体不触碰磁盘且关联行全部级联清空`() = runDbTest {
        val seeded = seed()

        deletionService.deleteSong(seeded.entity)

        assertThat(storage.deleted).isEmpty()
        assertThat(db.countOf("song_entity")).isEqualTo(0)
        assertThat(db.countOf("music_file")).isEqualTo(0)
        assertThat(db.countOf("song_artist")).isEqualTo(0)
        assertThat(db.countOf("entity_tag")).isEqualTo(0)
        assertThat(db.countOf("lyric")).isEqualTo(0)
        assertThat(db.countOf("play_history")).isEqualTo(0)
        assertThat(db.countOf("queue_item")).isEqualTo(0)
        // 播放状态单例仍在，只是当前项被 SET NULL
        assertThat(db.countOf("playback_state")).isEqualTo(1)
        assertThat(db.scalar("SELECT current_queue_item_id FROM playback_state WHERE id = 0")).isNull()
    }

    @Test
    fun `保留标签与分类本身_只清掉关联`() = runDbTest {
        val seeded = seed()

        deletionService.deleteSong(seeded.entity)

        assertThat(db.countOf("tag")).isEqualTo(1)
        assertThat(db.countOf("category")).isEqualTo(1)
    }

    // —— 移除文件 ——

    @Test
    fun `移除非代表文件时代表文件不变`() = runDbTest {
        val seeded = seed()

        deletionService.unlinkFile(seeded.small)

        assertThat(db.countOf("song_entity")).isEqualTo(1)
        assertThat(db.representativeIdOf(seeded.entity)).isEqualTo(seeded.big)
        assertThat(storage.deleted).isEmpty()
    }

    @Test
    fun `移除代表文件后改选剩余文件中体积最大者`() = runDbTest {
        val seeded = seed()
        val third = db.insertFile("/m/middle.mp3", size = 300, status = "LINKED")
        db.linkFile(third, seeded.entity)

        deletionService.unlinkFile(seeded.big)

        assertThat(db.representativeIdOf(seeded.entity)).isEqualTo(third)
    }

    @Test
    fun `移除最后一个文件会连带删除实体`() = runDbTest {
        val entity = db.insertSong("晴天", "周杰伦")
        val only = db.insertFile("/m/only.mp3", size = 100, status = "LINKED")
        db.linkFile(only, entity, isRepresentative = true)

        deletionService.unlinkFile(only)

        assertThat(db.countOf("song_entity")).isEqualTo(0)
        assertThat(db.countOf("music_file")).isEqualTo(0)
        assertThat(storage.deleted).isEmpty()
    }

    // —— I8：物理删除先删磁盘，失败则库不变 ——

    @Test
    fun `磁盘删除失败时数据库保持原样`() = runDbTest {
        val seeded = seed()
        storage.deleteResult = false

        val result = deletionService.deleteFilePhysically(seeded.small)

        assertThat(result).isEqualTo(DeleteFileResult.DiskDeleteFailed("/m/small.mp3"))
        assertThat(storage.deleted).containsExactly("/m/small.mp3")
        assertThat(db.countOf("music_file")).isEqualTo(2)
        assertThat(db.representativeIdOf(seeded.entity)).isEqualTo(seeded.big)
    }

    @Test
    fun `磁盘删除成功后才改动数据库`() = runDbTest {
        val seeded = seed()

        val result = deletionService.deleteFilePhysically(seeded.small)

        assertThat(result).isEqualTo(DeleteFileResult.Deleted)
        assertThat(storage.deleted).containsExactly("/m/small.mp3")
        assertThat(db.countOf("music_file")).isEqualTo(1)
        assertThat(db.representativeIdOf(seeded.entity)).isEqualTo(seeded.big)
    }

    @Test
    fun `物理删除代表文件会改选剩余文件并保留实体`() = runDbTest {
        val seeded = seed()

        deletionService.deleteFilePhysically(seeded.big)

        assertThat(db.countOf("song_entity")).isEqualTo(1)
        assertThat(db.representativeIdOf(seeded.entity)).isEqualTo(seeded.small)
    }
}

/** 只关心删除调用的记录：断言「删了几次、删了谁」。 */
private class FakeStorageSource : StorageSource {
    val deleted = mutableListOf<String>()
    var deleteResult = true

    override fun listFiles(
        roots: List<String>,
        exts: Set<String>,
        onProgress: (Int) -> Unit,
    ): Sequence<FileRef> = emptySequence()

    override fun exists(path: String) = true
    override fun isDirectory(path: String) = true
    override fun listDirectories(parent: String) = emptyList<FileRef>()
    override fun size(path: String) = 0L
    override fun readBytes(path: String, maxBytes: Int) = ByteArray(0)
    override fun canWrite() = true

    override fun delete(path: String): Boolean {
        deleted += path
        return deleteResult
    }
}
