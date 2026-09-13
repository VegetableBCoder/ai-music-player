package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.aimusic.player.data.model.PlayMode

/**
 * 播放状态单行表。
 *
 * 文档 03 §2.2 原本写 `CHECK (id = 0)`，但 Room 不生成 CHECK 约束、SQLite 也不支持
 * `ALTER TABLE ... ADD CHECK`，故无法表达。数据库层保底的是主键唯一性，
 * 「只写 id = 0」由 `PlaybackStateDao` 保证。
 */
@Entity(
    tableName = "playback_state",
    foreignKeys = [
        ForeignKey(
            entity = QueueItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["current_queue_item_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "current_queue_item_id") val currentQueueItemId: Long? = null,
    @ColumnInfo(name = "mode", defaultValue = "LIST_LOOP") val mode: PlayMode = PlayMode.LIST_LOOP,
    @ColumnInfo(name = "position_ms", defaultValue = "0") val positionMs: Long = 0,
    @ColumnInfo(name = "session_max_position_ms", defaultValue = "0") val sessionMaxPositionMs: Long = 0,
    @ColumnInfo(name = "session_started_at") val sessionStartedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
