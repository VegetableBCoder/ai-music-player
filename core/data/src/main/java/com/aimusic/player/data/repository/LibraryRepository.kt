package com.aimusic.player.data.repository

import com.aimusic.player.data.model.AlbumListItem
import com.aimusic.player.data.model.ArtistListItem
import com.aimusic.player.data.model.SongDetail
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import kotlinx.coroutines.flow.Flow

/**
 * 歌曲库读接口：`02 §5.4` 的原有签名 + `06 §3.3` 的扩展（扩展只是重载/新增，不改既有语义）。
 */
interface LibraryRepository {

    fun observeSongs(scope: SongScope, sort: SongSort): Flow<List<SongListItem>>

    fun observeSongs(scope: SongScope, sort: SongSort, filter: SongFilter): Flow<List<SongListItem>>

    fun observeArtists(): Flow<List<ArtistListItem>>

    fun observeAlbums(): Flow<List<AlbumListItem>>

    /** `06 §3.3`：搜索把歌曲与歌手合并命中，统一不分组。 */
    fun observeSearch(query: String): Flow<List<SongListItem>>

    /** 详情需响应标签 / 文件变化，故是 Flow。 */
    fun observeSongDetail(entityId: Long): Flow<SongDetail>

    /** 给定实体集合中**存在可用文件**的子集 = `isPlayable` 为真的实体（`07 §4.10` 入队过滤入口）。 */
    suspend fun getAvailableFileIds(entityIds: List<Long>): Set<Long>

    suspend fun getSongDetail(entityId: Long): SongDetail
}
