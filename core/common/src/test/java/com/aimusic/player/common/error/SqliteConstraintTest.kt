package com.aimusic.player.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `SQLiteConstraintException` 的报文是自由文本，只能靠解析。
 * 这里钉死"哪种报文 → 哪种冲突类型"，避免上层拿到错误语义。
 */
class SqliteConstraintTest {

    @Test
    fun `唯一索引命中按列名判定冲突类型`() {
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: music_file.path")?.type)
            .isEqualTo(ConflictType.DUPLICATE_FILE)
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: lyric.path")?.type)
            .isEqualTo(ConflictType.DUPLICATE_LYRIC)
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: tag.name")?.type)
            .isEqualTo(ConflictType.DUPLICATE_TAG)
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: entity_tag")?.type)
            .isEqualTo(ConflictType.DUPLICATE_TAG)
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: scan_source")?.type)
            .isEqualTo(ConflictType.DUPLICATE_SOURCE)
    }

    @Test
    fun `未识别的列名回落为重复文件`() {
        assertThat(SqliteConstraint.parse("UNIQUE constraint failed: some_other.column")?.type)
            .isEqualTo(ConflictType.DUPLICATE_FILE)
    }

    @Test
    fun `非约束报文返回 null，不猜语义`() {
        assertThat(SqliteConstraint.parse(null)).isNull()
        assertThat(SqliteConstraint.parse("disk I/O error")).isNull()
        assertThat(SqliteConstraint.parse("PRIMARY KEY constraint failed")).isNull()
    }

    @Test
    fun `detail 只保留表列名，不残留路径值`() {
        val parsed =
            SqliteConstraint.parse("UNIQUE constraint failed: music_file.path, /sdcard/secret/song.mp3")

        assertThat(parsed?.detail).isEqualTo("music_file.path")
    }

    @Test
    fun `无可识别标识符时 detail 回落为 constraint`() {
        val parsed = SqliteConstraint.parse("CHECK constraint failed: play_count >= 0")

        assertThat(parsed?.type).isEqualTo(ConflictType.DUPLICATE_FILE)
        assertThat(parsed?.detail).isEqualTo("constraint")
    }
}
