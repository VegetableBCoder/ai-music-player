package com.aimusic.player.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.aimusic.player.data.entity.MusicFileEntity

/** 歌手列表项：歌手名 + 名下歌曲数（`03 §3.3`）。 */
data class ArtistListItem(
    val name: String,
    @ColumnInfo(name = "songCount") val songCount: Int,
)

/** 某次分析批次里的文件行：文件本身 + 已挂靠到的实体 id（未挂靠为 null）。 */
data class RunFileRow(
    @Embedded val file: MusicFileEntity,
    @ColumnInfo(name = "linkedEntityId") val linkedEntityId: Long?,
)
