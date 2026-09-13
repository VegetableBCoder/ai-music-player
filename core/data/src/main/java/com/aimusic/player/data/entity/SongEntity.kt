package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "song_entity",
    indices = [
        // entityKey 精确相等 = 同一实体（I1 的实体侧保证）
        Index(
            value = ["canonical_title", "artists_key"],
            unique = true,
            name = "idx_song_identity",
        ),
        Index(value = ["last_played_at"], orders = [Index.Order.DESC], name = "idx_song_last_play"),
        Index(value = ["complete_count"], name = "idx_song_complete"),
        Index(value = ["album_name", "album_artist"], name = "idx_song_album"),
    ],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "canonical_title") val canonicalTitle: String,
    /** 排序去重后以 U+001F 连接，见 TextNormalizer.artistsKey */
    @ColumnInfo(name = "artists_key") val artistsKey: String,
    @ColumnInfo(name = "display_artists") val displayArtists: String,
    @ColumnInfo(name = "album_name") val albumName: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "album_release_date") val albumReleaseDate: String? = null,
    @ColumnInfo(name = "cover_cache_path") val coverCachePath: String? = null,
    @ColumnInfo(name = "play_count", defaultValue = "0") val playCount: Int = 0,
    @ColumnInfo(name = "complete_count", defaultValue = "0") val completeCount: Int = 0,
    @ColumnInfo(name = "skip_count", defaultValue = "0") val skipCount: Int = 0,
    @ColumnInfo(name = "interrupt_count", defaultValue = "0") val interruptCount: Int = 0,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
