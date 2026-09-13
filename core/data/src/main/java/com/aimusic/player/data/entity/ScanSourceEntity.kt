package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aimusic.player.data.model.SourceKind

/** 音乐扫描来源 / 外部歌词目录，`(kind, path)` 唯一。 */
@Entity(
    tableName = "scan_source",
    indices = [Index(value = ["kind", "path"], unique = true)],
)
data class ScanSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SourceKind,
    val path: String,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
