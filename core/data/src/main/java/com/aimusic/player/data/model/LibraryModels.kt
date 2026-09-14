package com.aimusic.player.data.model

import com.aimusic.player.common.model.TagRef
import com.aimusic.player.data.entity.LyricEntity
import com.aimusic.player.data.entity.SongEntity

/** 歌曲列表行：四个维度、搜索结果、标签歌曲列表共用同一投影（`06 §3.1`）。 */
data class SongListItem(
    val entityId: Long,
    val title: String,
    val displayArtists: String,
    val artistNames: List<String>,
    val albumName: String?,
    val albumArtist: String?,
    val coverCachePath: String?,
    /** **仅主要分类下**的标签（I6） */
    val tags: List<TagRef>,
    /** 派生：存在可用文件。不落库，避免脏字段（`03 §5`）。 */
    val isPlayable: Boolean,
)

/** 歌曲详情（覆盖页数据）。 */
data class SongDetail(
    val entity: SongEntity,
    val artists: List<String>,
    /** **完整标签**（不按主要分类折叠，供详情/播放页） */
    val tags: List<TagRef>,
    val lyric: LyricEntity?,
    /** 管理的文件列表，按 size DESC，代表文件置顶 */
    val files: List<MusicFileItem>,
    val representativeFileId: Long?,
)

data class MusicFileItem(
    val fileId: Long,
    val path: String,
    val fileName: String,
    val size: Long,
    val format: String,
    val durationMs: Long?,
    val isRepresentative: Boolean,
    val analysisStatus: AnalysisStatus,
)

data class AlbumListItem(
    val albumName: String,
    val albumArtist: String,
    /** 代表文件内嵌，缺省为 null */
    val releaseDate: String?,
    val songCount: Int,
    val coverCachePath: String?,
)

/** 排序：仅三种（锁定决策，无拼音、无分组）。中文按 Unicode 码位排序。 */
enum class SongSort { NAME, RECENT_PLAYED, COMPLETE_COUNT }

/** 浏览维度 = 队列来源（`02 §11` 术语映射：SongScope）。 */
sealed interface SongScope {
    data object Songs : SongScope
    data class Artist(val name: String) : SongScope
    data class Album(val albumName: String, val albumArtist: String?) : SongScope
    data class Tag(val name: String) : SongScope
    data class SearchResult(val query: String) : SongScope
}

/** 筛选（可叠加于任意 scope）。 */
data class SongFilter(
    /** 按名称筛选：`canonical_title LIKE '%' || :q || '%'` */
    val titleQuery: String? = null,
    /** 按歌词筛选：仅保留**已关联歌词**的歌曲 */
    val hasLyricOnly: Boolean = false,
)

/** 可播性批量查询的行：实体 id + 其可用文件数。 */
data class EntityPlayableRow(
    val entityId: Long,
    val playableCount: Int,
)
