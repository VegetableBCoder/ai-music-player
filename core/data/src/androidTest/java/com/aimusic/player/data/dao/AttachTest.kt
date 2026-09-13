package com.aimusic.player.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 挂靠文件（`03 §4.1`）—— 覆盖 I1（一个文件至多属于一个实体）与 I4（LINKED 不重分析）。
 *
 * 夹具用原生 SQL 插入，断言走真实事务，避免测试自己依赖待验证的 DAO 查询。
 * 用例名里的空格一律写成 `_`（DEX 040 之前方法名不允许空格，见 01 §2.2 第 15 条）。
 */
@RunWith(AndroidJUnit4::class)
class AttachTest {

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

    // —— 夹具（原生 SQL，不依赖待测 DAO） ——

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun insertFile(path: String, size: Long, status: String = "UNANALYZED"): Long {
        exec(
            "INSERT INTO music_file (path, file_name, size, format, analysis_status, added_at) " +
                "VALUES ('$path', '${path.substringAfterLast('/')}', $size, 'mp3', '$status', 0)"
        )
        return firstColumn("SELECT id FROM music_file WHERE path = '$path'").single().toLong()
    }

    private fun insertCategory(name: String) =
        exec("INSERT INTO category (name, is_main, is_builtin, sort_order) VALUES ('$name', 1, 0, 0)")

    private fun insertSong(title: String, artistsKey: String) = exec(
        "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
            "VALUES ('$title', '$artistsKey', '$artistsKey', 0)"
    )

    private fun firstColumn(sql: String): List<String> =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }

    private fun songCount() = firstColumn("SELECT COUNT(*) FROM song_entity").single().toInt()

    private fun entityIdOf(fileId: Long): String =
        firstColumn("SELECT entity_id FROM music_file WHERE id = $fileId").single()

    private fun representativeIdOf(entityId: Long): String =
        firstColumn("SELECT id FROM music_file WHERE entity_id = $entityId AND is_representative = 1")
            .single()

    private fun artistNamesInOrder(entityId: Long): List<String> =
        firstColumn("SELECT artist_name FROM song_artist WHERE entity_id = $entityId ORDER BY position")

    private fun tagNames(): List<String> = firstColumn("SELECT name FROM tag ORDER BY name")

    private fun result(title: String, artists: List<String>, tags: List<TagAssignment> = emptyList()) =
        NormalizeResult(title, artists, tags)

    // —— I1：实体归组 ——

    @Test
    fun `同一身份键的两个文件挂到同一实体`() = runDbTest {
        val first = insertFile("/m/1.mp3", 100)
        val second = insertFile("/m/2.mp3", 200)

        songDao.attachAnalysisResult(first, result("晴天", listOf("周杰伦")), now = 10)
        songDao.attachAnalysisResult(second, result("晴天", listOf("周杰伦")), now = 20)

        assertThat(songCount()).isEqualTo(1)
        assertThat(entityIdOf(first)).isEqualTo(entityIdOf(second))
    }

    @Test
    fun `演唱者集合不同则挂到不同实体`() = runDbTest {
        val first = insertFile("/m/1.mp3", 100)
        val second = insertFile("/m/2.mp3", 100)

        songDao.attachAnalysisResult(first, result("晴天", listOf("周杰伦")), now = 10)
        songDao.attachAnalysisResult(second, result("晴天", listOf("张三")), now = 20)

        assertThat(songCount()).isEqualTo(2)
        assertThat(entityIdOf(first)).isNotEqualTo(entityIdOf(second))
    }

    @Test
    fun `演唱者书写顺序不同但集合相同则归为同一实体`() = runDbTest {
        val first = insertFile("/m/1.mp3", 100)
        val second = insertFile("/m/2.mp3", 100)

        songDao.attachAnalysisResult(first, result("小酒窝", listOf("林俊杰", "蔡卓妍")), now = 10)
        songDao.attachAnalysisResult(second, result("小酒窝", listOf("蔡卓妍", "林俊杰")), now = 20)

        assertThat(songCount()).isEqualTo(1)
    }

    @Test
    fun `演唱者行按归一化顺序写入_position_连续`() = runDbTest {
        val file = insertFile("/m/1.mp3", 100)

        songDao.attachAnalysisResult(
            file,
            result("小酒窝", listOf("林俊杰", "蔡卓妍")),
            now = 10,
        )

        assertThat(artistNamesInOrder(entityIdOf(file).toLong()))
            .containsExactly("林俊杰", "蔡卓妍").inOrder()
    }

    // —— I4：LINKED 不重复挂靠 ——

    @Test
    fun `已挂靠的文件再次挂靠会被忽略`() = runDbTest {
        val file = insertFile("/m/1.mp3", 100)
        songDao.attachAnalysisResult(file, result("晴天", listOf("周杰伦")), now = 10)
        val entityAfterFirst = entityIdOf(file)

        // 第二次故意给完全不同的身份键：若真的重跑，会新建实体并改写 entity_id
        songDao.attachAnalysisResult(file, result("完全不同的歌", listOf("别人")), now = 20)

        assertThat(songCount()).isEqualTo(1)
        assertThat(entityIdOf(file)).isEqualTo(entityAfterFirst)
    }

    // —— 代表文件（I2 的挂靠侧） ——

    @Test
    fun `挂靠后按体积最大者当选代表文件`() = runDbTest {
        val small = insertFile("/m/small.mp3", 100)
        val big = insertFile("/m/big.mp3", 500)

        songDao.attachAnalysisResult(small, result("晴天", listOf("周杰伦")), now = 10)
        songDao.attachAnalysisResult(big, result("晴天", listOf("周杰伦")), now = 20)

        assertThat(representativeIdOf(entityIdOf(big).toLong())).isEqualTo(big.toString())
    }

    // —— 标签写入：AI 不得新建分类 ——

    @Test
    fun `标签写入只接受当前有效分类，其余被丢弃`() = runDbTest {
        insertCategory("曲风")
        val file = insertFile("/m/1.mp3", 100)

        songDao.attachAnalysisResult(
            file,
            result(
                "晴天",
                listOf("周杰伦"),
                listOf(
                    TagAssignment("曲风", "抒情"),
                    TagAssignment("AI 编出来的分类", "野标签"),
                ),
            ),
            now = 10,
        )

        assertThat(tagNames()).containsExactly("抒情")
    }
}
