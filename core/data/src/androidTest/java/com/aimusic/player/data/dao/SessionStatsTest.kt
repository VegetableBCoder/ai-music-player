package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.PlayHistoryEntity
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.scalar
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I10 的数据层半边：`play_count` 只在**会话开始**时 +1，结算只动 complete / skip / interrupt。
 *
 * 完整规则（85% / 15% 的分档、进程被杀后恢复不重复计）在 `:core:playback` 的
 * `recordSessionStart` / `settleSession`（`07 §4.6`），属 Phase 6；这里钉住它们依赖的
 * 数据层契约 —— 只要结算不碰 play_count，「被杀不重复计」就有基础。
 */
@RunWith(AndroidJUnit4::class)
class SessionStatsTest {

    private lateinit var db: MusicDatabase
    private lateinit var songDao: SongDao
    private lateinit var stateDao: PlaybackStateDao
    private lateinit var historyDao: PlayHistoryDao

    private val NOW = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        stateDao = db.playbackStateDao()
        historyDao = db.playHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun playCountOf(entityId: Long) =
        db.scalar("SELECT play_count FROM song_entity WHERE id = $entityId")!!.toInt()

    private fun sessionStartedAt() = db.scalar("SELECT session_started_at FROM playback_state WHERE id = 0")

    @Test
    fun `会话开始只加一次播放次数并写最近播放时间`() = runDbTest {
        val song = db.insertSong("晴天", "周杰伦")

        songDao.bumpPlayCount(song, now = NOW)

        assertThat(playCountOf(song)).isEqualTo(1)
        assertThat(db.scalar("SELECT last_played_at FROM song_entity WHERE id = $song"))
            .isEqualTo(NOW.toString())
    }

    @Test
    fun `完整播放结算只加完整次数不碰播放次数`() = runDbTest {
        val song = db.insertSong("晴天", "周杰伦")
        songDao.bumpPlayCount(song, now = NOW)

        songDao.bumpSessionResult(song, complete = 1, skip = 0, interrupt = 0)

        assertThat(playCountOf(song)).isEqualTo(1)
        assertThat(db.scalar("SELECT complete_count FROM song_entity WHERE id = $song")).isEqualTo("1")
        assertThat(db.scalar("SELECT skip_count FROM song_entity WHERE id = $song")).isEqualTo("0")
        assertThat(db.scalar("SELECT interrupt_count FROM song_entity WHERE id = $song")).isEqualTo("0")
    }

    @Test
    fun `一次会话结束后播放次数仍为一_结算不重复计数`() = runDbTest {
        val song = db.insertSong("晴天", "周杰伦")

        // 会话开始（07 §4.6.2 的写入集合）
        songDao.bumpPlayCount(song, now = NOW)
        historyDao.addHistory(
            PlayHistoryEntity(entityId = song, playSessionId = "s-1", playedAt = NOW),
        )
        stateDao.putSession(sessionStartedAt = NOW, sessionMaxPositionMs = 0, updatedAt = NOW)

        // 结算（07 §4.6.5：只改统计字段 + 清空会话）
        songDao.bumpSessionResult(song, complete = 1, skip = 0, interrupt = 0)
        stateDao.putSession(sessionStartedAt = null, sessionMaxPositionMs = 0, updatedAt = NOW)

        assertThat(playCountOf(song)).isEqualTo(1)
        assertThat(sessionStartedAt()).isNull()
    }

    @Test
    fun `历史按时间倒序保留最近若干条`() = runDbTest {
        val song = db.insertSong("晴天", "周杰伦")
        repeat(5) { index ->
            historyDao.addHistory(
                PlayHistoryEntity(
                    entityId = song,
                    playSessionId = "s-$index",
                    playedAt = index.toLong(),
                ),
            )
        }

        historyDao.pruneHistory(keep = 3)

        assertThat(db.countOf("play_history")).isEqualTo(3)
        assertThat(historyDao.recentSongs(limit = 50).size).isEqualTo(3)
    }
}
