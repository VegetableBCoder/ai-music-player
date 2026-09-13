package com.aimusic.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2a：表结构与数据库层约束。
 *
 * 这里刻意**绕过 DAO、直接用原生 SQL** —— 目的是验证数据库自己能不能挡住非法写入
 * （唯一索引、复合唯一索引、CHECK、外键级联），而不是验证某段业务逻辑。
 * 业务语义的正确性在 2b 的事务测试里做。
 *
 * ⚠️ 用例名里的空格一律写成 `_`：反引号名会变成方法名，而 DEX 040（minSdk ≥ 30）之前
 * 不允许方法名含空格；本项目 minSdk = 26，带空格会导致 D8 dex 失败。
 * JVM 单测不受此限（不经 dex）。
 */
@RunWith(AndroidJUnit4::class)
class MusicDatabaseSchemaTest {

    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun firstColumn(sql: String): List<String> =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }

    private fun indexColumns(indexName: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA index_info('$indexName')").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(2) ?: "") }
        }

    private fun exec(sql: String) {
        db.openHelper.writableDatabase.execSQL(sql)
    }

    /** 返回被数据库拒绝时抛出的异常；若竟然成功了，测试直接失败。 */
    private fun expectRejected(sql: String): Throwable {
        val thrown = runCatching { exec(sql) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        return thrown!!
    }

    private fun insertSong(title: String = "t", artistsKey: String = "k") = exec(
        "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
            "VALUES ('$title', '$artistsKey', 'd', 0)"
    )

    private fun insertFile(entityId: Long?, path: String, representative: Int?) {
        val entity = entityId?.toString() ?: "NULL"
        val rep = representative?.toString() ?: "NULL"
        exec(
            "INSERT INTO music_file (entity_id, path, file_name, size, format, " +
                "is_representative, added_at) " +
                "VALUES ($entity, '$path', '${path.substringAfterLast('/')}', 1, 'mp3', $rep, 0)"
        )
    }

    private fun fileCount() = firstColumn("SELECT COUNT(*) FROM music_file").single().toInt()

    @Test
    fun `建出_14_张业务表_与_03_§2_2_的_DDL_一致`() {
        val tables = firstColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                "AND name NOT LIKE 'android_%' ORDER BY name"
        )

        assertThat(tables).containsExactly(
            "analysis_run", "analysis_run_file", "category", "entity_tag", "llm_cache",
            "lyric", "music_file", "play_history", "playback_state", "queue_item",
            "scan_source", "song_artist", "song_entity", "tag",
        ).inOrder()
    }

    @Test
    fun `外键约束在连接上已启用`() {
        assertThat(firstColumn("PRAGMA foreign_keys").single()).isEqualTo("1")
    }

    @Test
    fun `I2_兜底索引建在_entity_id_与_is_representative_两列上且唯一`() {
        val indexName = firstColumn(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND tbl_name = 'music_file' AND sql LIKE '%is_representative%'"
        ).single()

        assertThat(indexColumns(indexName)).containsExactly("entity_id", "is_representative").inOrder()

        val ddl = firstColumn(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$indexName'"
        ).single()
        assertThat(ddl).contains("UNIQUE")
    }

    @Test
    fun `同一实体插第二个代表文件会被数据库拒绝`() {
        insertSong()
        insertFile(entityId = 1, path = "/m/1.mp3", representative = 1)

        val thrown = expectRejected("INSERT INTO music_file " +
            "(entity_id, path, file_name, size, format, is_representative, added_at) " +
            "VALUES (1, '/m/2.mp3', '2.mp3', 2, 'mp3', 1, 0)")

        assertThat(thrown).isInstanceOf(android.database.sqlite.SQLiteConstraintException::class.java)
    }

    @Test
    fun `不同实体各自可以有一个代表文件`() {
        insertSong(title = "a")
        insertSong(title = "b")

        insertFile(entityId = 1, path = "/m/1.mp3", representative = 1)
        insertFile(entityId = 2, path = "/m/2.mp3", representative = 1)

        assertThat(firstColumn("SELECT COUNT(*) FROM music_file WHERE is_representative = 1").single())
            .isEqualTo("2")
    }

    @Test
    fun `同一实体可以有多条非代表文件`() {
        insertSong()

        insertFile(entityId = 1, path = "/m/1.mp3", representative = null)
        insertFile(entityId = 1, path = "/m/2.mp3", representative = null)

        assertThat(fileCount()).isEqualTo(2)
    }

    @Test
    fun `未挂靠的文件可以有多条，不受代表文件索引影响`() {
        insertFile(entityId = null, path = "/m/1.mp3", representative = null)
        insertFile(entityId = null, path = "/m/2.mp3", representative = null)

        assertThat(fileCount()).isEqualTo(2)
    }

    @Test
    fun `同一路径不能入库两次（I1_的文件侧）`() {
        insertFile(entityId = null, path = "/m/1.mp3", representative = null)

        expectRejected(
            "INSERT INTO music_file (entity_id, path, file_name, size, format, added_at) " +
                "VALUES (NULL, '/m/1.mp3', '1.mp3', 1, 'mp3', 0)"
        )
    }

    @Test
    fun `相同身份键的实体不能重复建（I1_的实体侧）`() {
        exec(
            "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
                "VALUES ('晴天', '周杰伦', '周杰伦', 0)"
        )

        expectRejected(
            "INSERT INTO song_entity (canonical_title, artists_key, display_artists, created_at) " +
                "VALUES ('晴天', '周杰伦', '周杰伦', 1)"
        )
    }

    @Test
    fun `同一个_lrc_路径至多关联一个实体（I7）`() {
        insertSong(title = "a")
        insertSong(title = "b")

        exec("INSERT INTO lyric (entity_id, path, source, match_level, matched_at) " +
            "VALUES (1, '/m/a.lrc', 'SAME_DIR', 1, 0)")

        expectRejected(
            "INSERT INTO lyric (entity_id, path, source, match_level, matched_at) " +
                "VALUES (2, '/m/a.lrc', 'EXTERNAL_DIR', 2, 0)"
        )
    }

    @Test
    fun `playback_state_的_id_主键保证单例行不会重复`() {
        // 文档 03 §2.2 原写的是 CHECK (id = 0)，但 Room 不生成 CHECK 约束，
        // 而 SQLite 的 ALTER TABLE 又不支持追加 CHECK（只能在 CREATE TABLE 时写），
        // 那张 CREATE TABLE 由 Room 生成 —— 所以该约束无法表达。
        // 退而求其次：「只写 id = 0」由 PlaybackStateDao 保证（2b 用 DAO 测试），
        // 数据库层保底的是主键唯一性，即下面这条。
        exec("INSERT INTO playback_state (id, mode, position_ms, session_max_position_ms, updated_at) " +
            "VALUES (0, 'LIST_LOOP', 0, 0, 0)")

        expectRejected(
            "INSERT INTO playback_state (id, mode, position_ms, session_max_position_ms, updated_at) " +
                "VALUES (0, 'SINGLE_LOOP', 0, 0, 1)"
        )
    }

    @Test
    fun `删除实体时_music_file_被外键级联清掉`() {
        insertSong()
        insertFile(entityId = 1, path = "/m/1.mp3", representative = 1)

        exec("DELETE FROM song_entity WHERE id = 1")

        assertThat(fileCount()).isEqualTo(0)
    }
}
