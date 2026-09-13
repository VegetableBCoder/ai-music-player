package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.error.AddTagResult
import com.aimusic.player.data.error.DeleteCategoryResult
import com.aimusic.player.data.error.DeleteTagResult
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 标签 / 分类的 CRUD 与删除保护 —— 覆盖 I5（标签名全局唯一）与 I12（删除保护）。
 *
 * 用例名里的空格一律写成 `_`（DEX 040 之前方法名不允许空格，见 01 §2.2 第 15 条）。
 */
@RunWith(AndroidJUnit4::class)
class TagCrudTest {

    private lateinit var db: MusicDatabase
    private lateinit var tagDao: TagDao
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tagDao = db.tagDao()
        categoryDao = db.categoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // —— 夹具 ——

    private val NOW = 1_000L

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun insertSong(title: String, artistsKey: String): Long {
        exec(
            "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
                "VALUES ('$title', '$artistsKey', '$artistsKey', 0)"
        )
        return firstColumn("SELECT id FROM song_entity WHERE canonical_title = '$title'")
            .single().toLong()
    }

    private fun firstColumn(sql: String): List<String> =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }

    private fun countOf(table: String) = firstColumn("SELECT COUNT(*) FROM $table").single().toInt()

    private fun isBuiltin(categoryId: Long): Boolean =
        firstColumn("SELECT is_builtin FROM category WHERE id = $categoryId").single() == "1"

    private fun mainCategories(): List<String> =
        firstColumn("SELECT name FROM category WHERE is_main = 1 ORDER BY name")

    // —— I5：标签名全局唯一 ——

    @Test
    fun `新建分类默认不是主要分类也不是内置分类`() = runDbTest {
        val id = categoryDao.addCategory("我的分类")

        assertThat(isBuiltin(id)).isFalse()
        assertThat(mainCategories()).isEmpty()
    }

    @Test
    fun `新增标签返回_Added`() = runDbTest {
        val category = categoryDao.addCategory("曲风")

        assertThat(tagDao.addTag(category, "抒情", now = NOW)).isEqualTo(AddTagResult.Added(category))
        assertThat(countOf("tag")).isEqualTo(1)
    }

    @Test
    fun `同名标签返回复用并带上原有分类`() = runDbTest {
        val rock = categoryDao.addCategory("曲风")
        val mood = categoryDao.addCategory("心情")
        tagDao.addTag(rock, "抒情", now = NOW)

        // 换个分类再建同名标签：标签全局唯一，应引导复用而不是新建
        assertThat(tagDao.addTag(mood, "抒情", now = NOW))
            .isEqualTo(AddTagResult.ReuseExisting(existingCategoryId = rock))
        assertThat(countOf("tag")).isEqualTo(1)
    }

    @Test
    fun `重复挂靠同一标签是幂等的`() = runDbTest {
        val category = categoryDao.addCategory("曲风")
        tagDao.addTag(category, "抒情", now = NOW)
        val song = insertSong("晴天", "周杰伦")

        tagDao.attachTags(listOf(song), listOf("抒情"), now = NOW)
        tagDao.attachTags(listOf(song), listOf("抒情"), now = NOW)

        assertThat(countOf("entity_tag")).isEqualTo(1)
    }

    @Test
    fun `挂靠不存在的标签会被忽略而不是外键报错`() = runDbTest {
        val song = insertSong("晴天", "周杰伦")

        tagDao.attachTags(listOf(song), listOf("根本不存在的标签"), now = NOW)

        assertThat(countOf("entity_tag")).isEqualTo(0)
    }

    // —— I12：删除保护 ——

    @Test
    fun `标签下有歌时删除被拒绝且行仍在`() = runDbTest {
        val category = categoryDao.addCategory("曲风")
        tagDao.addTag(category, "抒情", now = NOW)
        val song = insertSong("晴天", "周杰伦")
        tagDao.attachTags(listOf(song), listOf("抒情"), now = NOW)

        assertThat(tagDao.deleteTag("抒情")).isEqualTo(DeleteTagResult.Blocked(songCount = 1))
        assertThat(countOf("tag")).isEqualTo(1)
    }

    @Test
    fun `无歌标签可删除并清掉悬挂关联`() = runDbTest {
        val category = categoryDao.addCategory("曲风")
        tagDao.addTag(category, "抒情", now = NOW)
        val song = insertSong("晴天", "周杰伦")
        tagDao.attachTags(listOf(song), listOf("抒情"), now = NOW)
        tagDao.detachTags(listOf(song), listOf("抒情"))

        assertThat(tagDao.deleteTag("抒情")).isEqualTo(DeleteTagResult.Deleted)
        assertThat(countOf("tag")).isEqualTo(0)
        assertThat(countOf("entity_tag")).isEqualTo(0)
    }

    @Test
    fun `分类下有标签时删除被拒绝且行仍在`() = runDbTest {
        val category = categoryDao.addCategory("曲风")
        tagDao.addTag(category, "抒情", now = NOW)

        assertThat(categoryDao.deleteCategory(category))
            .isEqualTo(DeleteCategoryResult.Blocked(tagCount = 1))
        assertThat(countOf("category")).isEqualTo(1)
    }

    @Test
    fun `空分类可删除`() = runDbTest {
        val category = categoryDao.addCategory("空分类")

        assertThat(categoryDao.deleteCategory(category)).isEqualTo(DeleteCategoryResult.Deleted)
        assertThat(countOf("category")).isEqualTo(0)
    }

    @Test
    fun `主要分类可多选且不互斥`() = runDbTest {
        val rock = categoryDao.addCategory("曲风")
        val mood = categoryDao.addCategory("心情")

        categoryDao.setMainCategory(rock, isMain = true)
        categoryDao.setMainCategory(mood, isMain = true)

        assertThat(mainCategories()).containsExactly("心情", "曲风")
    }
}
