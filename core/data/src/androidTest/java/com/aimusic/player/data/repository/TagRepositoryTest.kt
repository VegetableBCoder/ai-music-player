package com.aimusic.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertCategory
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.insertTag
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TagRepository`（`06 §3.4`）：仓储层只做**装配与封装**，写操作全走 DAO 的事务方法（`03 §5`）。
 * 这里钉的是 DAO 层没有的两件事：`Map` 装配与 `searchTags` 的投影补全。
 */
@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {

    private lateinit var db: MusicDatabase
    private lateinit var repo: TagRepository

    private val NOW = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TagRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tagSong(entityId: Long, tagName: String) = db.exec(
        "INSERT INTO entity_tag (entity_id, tag_id, created_at) VALUES ($entityId, '$tagName', 0)"
    )

    @Test
    fun `批量标签组装成按实体分组的_Map`() = runDbTest {
        val main = db.insertCategory("曲风", isMain = true)
        val other = db.insertCategory("心情", isMain = false)
        db.insertTag("抒情", main)
        db.insertTag("深夜", other)
        val first = db.insertSong("晴天", "周杰伦")
        val second = db.insertSong("小酒窝", "林俊杰")
        tagSong(first, "抒情")
        tagSong(first, "深夜")
        tagSong(second, "抒情")

        val map = repo.observeTagsOfSongs(listOf(first, second), onlyMain = false).first()

        assertThat(map[first]?.map { it.name }).containsExactly("深夜", "抒情")
        assertThat(map[second]?.map { it.name }).containsExactly("抒情")
        assertThat(map[first]?.single { it.name == "抒情" }?.categoryId).isEqualTo(main)
        assertThat(map[first]?.single { it.name == "抒情" }?.categoryName).isEqualTo("曲风")
    }

    @Test
    fun `只取主要分类时非主要分类的标签不出现`() = runDbTest {
        val main = db.insertCategory("曲风", isMain = true)
        val other = db.insertCategory("心情", isMain = false)
        db.insertTag("抒情", main)
        db.insertTag("深夜", other)
        val song = db.insertSong("晴天", "周杰伦")
        tagSong(song, "抒情")
        tagSong(song, "深夜")

        val map = repo.observeTagsOfSongs(listOf(song), onlyMain = true).first()

        assertThat(map[song]?.map { it.name }).containsExactly("抒情")
    }

    @Test
    fun `搜索结果补齐分类与歌曲数_而不是只有名字`() = runDbTest {
        val category = db.insertCategory("曲风", isMain = true)
        db.insertTag("抒情", category)
        db.insertTag("摇滚", category)
        val song = db.insertSong("晴天", "周杰伦")
        tagSong(song, "抒情")

        val results = repo.searchTags("抒").first()

        assertThat(results.map { it.name }).containsExactly("抒情")
        assertThat(results.single().categoryId).isEqualTo(category)
        assertThat(results.single().songCount).isEqualTo(1)
        assertThat(results.single().isBuiltin).isFalse()
    }

    @Test
    fun `没有歌的标签也出现在分类下_计数为零`() = runDbTest {
        val category = db.insertCategory("曲风", isMain = true)
        db.insertTag("无人用", category)

        val tags = repo.observeTags(category).first()

        assertThat(tags.map { it.name }).containsExactly("无人用")
        assertThat(tags.single().songCount).isEqualTo(0)
    }

    @Test
    fun `写操作结果经仓储如实透传`() = runDbTest {
        val category = db.insertCategory("曲风", isMain = true)

        val added = repo.addTag(category, "抒情")
        val reused = repo.addTag(category, "抒情")
        val blocked = repo.deleteTag("抒情")

        assertThat(added.toString()).contains("Added")
        assertThat(reused.toString()).contains("ReuseExisting")
        // 没有歌时删得掉，说明上面的 ReuseExisting 没有把行写重
        assertThat(blocked.toString()).contains("Deleted")
    }

    @Test
    fun `分类列表带出标签数`() = runDbTest {
        val category = db.insertCategory("曲风", isMain = true)
        db.insertTag("抒情", category)
        db.insertTag("摇滚", category)

        val categories = repo.observeCategories().first()

        assertThat(categories.single().name).isEqualTo("曲风")
        assertThat(categories.single().tagCount).isEqualTo(2)
        assertThat(categories.single().isMain).isTrue()
    }
}
