package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aimusic.player.data.model.LyricSource

/** `path` 唯一 ⇒ 一个 `.lrc` 至多关联一个实体（I7）。 */
@Entity(
    tableName = "lyric",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entity_id"], name = "idx_lyric_entity"),
        Index(value = ["path"], unique = true),
    ],
)
data class LyricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    val path: String,
    val source: LyricSource,
    /** 1 | 2 | 3，对应三级匹配（见 08 §4.3） */
    @ColumnInfo(name = "match_level") val matchLevel: Int,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
)
