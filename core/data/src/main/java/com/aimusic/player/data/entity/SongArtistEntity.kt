package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 实体 ↔ 演唱者（支撑歌手维度的分组与计数）。 */
@Entity(
    tableName = "song_artist",
    primaryKeys = ["entity_id", "artist_name"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["artist_name"], name = "idx_song_artist_name")],
)
data class SongArtistEntity(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
)
