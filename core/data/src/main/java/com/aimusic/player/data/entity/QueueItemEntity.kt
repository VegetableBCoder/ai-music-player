package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 播放队列项：允许重复、有 `position` 排序（I9）。 */
@Entity(
    tableName = "queue_item",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["position"], name = "idx_queue_position")],
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "is_forced", defaultValue = "0") val isForced: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
