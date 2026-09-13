package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 实际播放顺序：随机模式「上一首」的依据。 */
@Entity(
    tableName = "play_history",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["played_at"], orders = [Index.Order.DESC], name = "idx_history_played")],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "play_session_id") val playSessionId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)
