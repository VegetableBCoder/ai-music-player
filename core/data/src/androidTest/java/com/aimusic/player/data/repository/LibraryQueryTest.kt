package com.aimusic.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertCategory
import com.aimusic.player.data.insertFile
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.insertTag
import com.aimusic.player.data.linkFile
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.data.scalar
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `03 §9` 点名要测的一项：**排序 / 筛选 / 搜索 SQL 正确性（含 NULL 处理）**。
 *
 * 这些断言必须在真机 SQLite 上跑 —— 排序规则（`COLLATE NOCASE`、`NULLS LAST` 的写法）
 * 与 `LIKE` 的大小写行为都是**数据库行为**，用假数据或内存里的 Kotlin 排序验证不了。
 */
@RunWith(AndroidJUnit4::class)
class LibraryQueryTest {

    private lateinit var db: MusicDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LibraryRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun titlesOf(
        scope: SongScope,
        sort: SongSort,
        filter: SongFilter = SongFilter(),
    ) = repo.observeSongs(scope, sort, filter).first().map { it.title }

    private fun tag(name: String, categoryName: String, isMain: Boolean = true): String {
        val category = db.insertCategory(categoryName, isMain = isMain)
        db.insertTag(name, category)
        return name
    }

    private fun tagSong(entityId: Long, tagName: String) = db.exec(
        "INSERT INTO entity_tag (entity_id, tag_id, created_at) VALUES ($entityId, '$tagName', 0)"
    )

    private fun attachLyric(entityId: Long, path: String) = db.exec(
        "INSERT INTO lyric (entity_id, path, source, match_level, matched_at) " +
            "VALUES ($entityId, '$path', 'EXTERNAL_DIR', 1, 0)"
    )

    private fun playable(entityId: Long, path: String) {
        val file = db.insertFile(path, size = 100, status = "LINKED")
        db.linkFile(file, entityId, isRepresentative = true)
    }

    // —— 排序 ——

    @Test
    fun `按名称排序时忽略大小写`() = runDbTest {
        db.insertSong("banana", "a")
        db.insertSong("Apple", "a")
        db.insertSong("cherry", "a")

        assertThat(titlesOf(SongScope.Songs, SongSort.NAME))
            .containsExactly("Apple", "banana", "cherry").inOrder()
    }

    @Test
    fun `按最近播放排序时从未播放的排在最后`() = runDbTest {
        val never = db.insertSong("从未播放", "a")
        val old = db.insertSong("很久以前", "a")
        val recent = db.insertSong("刚刚", "a")
        db.exec("UPDATE song_entity SET last_played_at = 100 WHERE id = $old")
        db.exec("UPDATE song_entity SET last_played_at = 900 WHERE id = $recent")

        // NULLS LAST：不能因为 last_played_at 是 NULL 就排到最前面，那会让「最近播放」名不副实
        assertThat(titlesOf(SongScope.Songs, SongSort.RECENT_PLAYED))
            .containsExactly("刚刚", "很久以前", "从未播放").inOrder()
        assertThat(db.scalar("SELECT last_played_at FROM song_entity WHERE id = $never")).isNull()
    }

    @Test
    fun `按完整播放次数排序时并列者按名称`() = runDbTest {
        val many = db.insertSong("多", "a")
        db.insertSong("b", "a")
        db.insertSong("a", "a")
        db.exec("UPDATE song_entity SET complete_count = 5 WHERE id = $many")

        assertThat(titlesOf(SongScope.Songs, SongSort.COMPLETE_COUNT))
            .containsExactly("多", "a", "b").inOrder()
    }

    // —— 筛选 ——

    @Test
    fun `名称筛选是包含匹配`() = runDbTest {
        db.insertSong("晴天", "周杰伦")
        db.insertSong("雨天", "张三")

        assertThat(titlesOf(SongScope.Songs, SongSort.NAME, SongFilter(titleQuery = "晴")))
            .containsExactly("晴天")
    }

    @Test
    fun `歌词筛选只保留已关联歌词的歌`() = runDbTest {
        val withLyric = db.insertSong("有词", "a")
        db.insertSong("无词", "a")
        attachLyric(withLyric, "/lrc/a.lrc")

        assertThat(titlesOf(SongScope.Songs, SongSort.NAME, SongFilter(hasLyricOnly = true)))
            .containsExactly("有词")
    }

    // —— 维度 ——

    @Test
    fun `歌手维度只返回该歌手的歌`() = runDbTest {
        val first = db.insertSong("晴天", "周杰伦")
        db.insertSong("小酒窝", "林俊杰")
        db.exec("INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($first, '周杰伦', 0)")

        assertThat(titlesOf(SongScope.Artist("周杰伦"), SongSort.NAME)).containsExactly("晴天")
    }

    @Test
    fun `专辑维度按专辑名与专辑歌手一起匹配`() = runDbTest {
        db.insertSong("甲", "a", albumName = "叶惠美")
        db.exec("UPDATE song_entity SET album_artist = '周杰伦' WHERE canonical_title = '甲'")
        db.insertSong("乙", "a", albumName = "叶惠美")
        db.exec("UPDATE song_entity SET album_artist = '别人' WHERE canonical_title = '乙'")

        assertThat(titlesOf(SongScope.Album("叶惠美", "周杰伦"), SongSort.NAME)).containsExactly("甲")
    }

    @Test
    fun `标签维度只返回挂了该标签的歌`() = runDbTest {
        val tagged = db.insertSong("晴天", "a")
        db.insertSong("雨天", "a")
        val name = tag("抒情", "曲风")
        tagSong(tagged, name)

        assertThat(titlesOf(SongScope.Tag("抒情"), SongSort.NAME)).containsExactly("晴天")
    }

    @Test
    fun `搜索同时命中歌名与歌手名`() = runDbTest {
        val byArtist = db.insertSong("小酒窝", "林俊杰")
        db.insertSong("晴天", "周杰伦")
        db.exec(
            "INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($byArtist, '林俊杰', 0)"
        )

        assertThat(repo.observeSearch("林俊杰").first().map { it.title }).containsExactly("小酒窝")
        assertThat(repo.observeSearch("晴天").first().map { it.title }).containsExactly("晴天")
    }

    // —— 派生字段 ——

    @Test
    fun `可播性由可用文件数派生而不是落库字段`() = runDbTest {
        val playableSong = db.insertSong("有文件", "a")
        val broken = db.insertSong("文件未分析", "a")
        playable(playableSong, "/m/good.mp3")
        // 已挂靠但状态不是 LINKED ⇒ 不可播
        val bad = db.insertFile("/m/bad.mp3", size = 100, status = "UNANALYZED")
        db.linkFile(bad, broken)

        val items = repo.observeSongs(SongScope.Songs, SongSort.NAME, SongFilter()).first()
        assertThat(items.single { it.entityId == playableSong }.isPlayable).isTrue()
        assertThat(items.single { it.entityId == broken }.isPlayable).isFalse()

        assertThat(repo.getAvailableFileIds(listOf(playableSong, broken)))
            .containsExactly(playableSong)
    }

    @Test
    fun `列表标签只取主要分类下的`() = runDbTest {
        val song = db.insertSong("晴天", "a")
        tagSong(song, tag("抒情", "曲风", isMain = true))
        tagSong(song, tag("深夜", "心情", isMain = false))

        val item = repo.observeSongs(SongScope.Songs, SongSort.NAME, SongFilter()).first().single()

        assertThat(item.tags.map { it.name }).containsExactly("抒情")
        assertThat(item.tags.single().categoryName).isEqualTo("曲风")
    }

    @Test
    fun `列表带出演唱者拆行与封面路径`() = runDbTest {
        val song = db.insertSong("小酒窝", "a")
        db.exec("INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($song, '林俊杰', 0)")
        db.exec("INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($song, '蔡卓妍', 1)")
        db.exec("UPDATE song_entity SET cover_cache_path = '/cache/x.webp' WHERE id = $song")

        val item = repo.observeSongs(SongScope.Songs, SongSort.NAME, SongFilter()).first().single()

        assertThat(item.artistNames).containsExactly("林俊杰", "蔡卓妍").inOrder()
        assertThat(item.coverCachePath).isEqualTo("/cache/x.webp")
    }

    // —— 聚合 ——

    @Test
    fun `专辑聚合按专辑名与专辑歌手分组并统计歌曲数`() = runDbTest {
        db.insertSong("甲", "a", albumName = "叶惠美")
        db.insertSong("乙", "a", albumName = "叶惠美")
        db.insertSong("无专辑", "a")
        db.exec("UPDATE song_entity SET album_artist = '周杰伦' WHERE album_name = '叶惠美'")

        val albums = repo.observeAlbums().first()

        // 专辑名为 NULL 的实体不进专辑维度（否则会出现一个没有名字的「专辑」）
        assertThat(albums.map { it.albumName }).containsExactly("叶惠美")
        assertThat(albums.single().songCount).isEqualTo(2)
        assertThat(albums.single().albumArtist).isEqualTo("周杰伦")
    }

    @Test
    fun `详情带出完整标签与文件列表_代表文件置顶`() = runDbTest {
        val song = db.insertSong("晴天", "a")
        tagSong(song, tag("抒情", "曲风", isMain = true))
        tagSong(song, tag("深夜", "心情", isMain = false))
        val small = db.insertFile("/m/small.mp3", size = 100, status = "LINKED")
        val big = db.insertFile("/m/big.mp3", size = 900, status = "LINKED")
        db.linkFile(small, song)
        db.linkFile(big, song, isRepresentative = true)

        val detail = repo.getSongDetail(song)

        assertThat(detail.entity.canonicalTitle).isEqualTo("晴天")
        // 详情要**完整标签**，不按主要分类折叠
        assertThat(detail.tags.map { it.name }).containsExactly("深夜", "抒情")
        assertThat(detail.files.first().fileId).isEqualTo(big)
        assertThat(detail.representativeFileId).isEqualTo(big)
        assertThat(detail.lyric).isNull()
    }
}
